package com.wichat.domain

import android.net.Uri
import android.util.Log
import com.wichat.crypto.AttachmentCodec
import com.wichat.crypto.FileCipher
import com.wichat.crypto.SessionManager
import com.wichat.crypto.toBase64
import com.wichat.data.db.AttachmentDao
import com.wichat.data.db.AttachmentEntity
import com.wichat.data.db.OutboxDao
import com.wichat.data.db.OutboxEntity
import com.wichat.data.db.TransferState
import com.wichat.data.prefs.AccountStore
import com.wichat.data.remote.PreKeyBundleDto
import com.wichat.data.remote.RelayApi
import com.wichat.data.repo.ContactRepository
import com.wichat.data.repo.MessageRepository
import com.wichat.media.AttachmentTransfers
import com.wichat.media.MediaImporter
import com.wichat.mesh.MeshStore
import com.wichat.model.AttachmentKind
import com.wichat.model.Envelope
import com.wichat.model.MessageContent
import com.wichat.model.ReceiptStatus
import com.wichat.model.uuidV7
import com.wichat.service.OutboxProcessor
import com.wichat.transport.RelayTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Encrypt → store locally → queue → let the outbox route it (SYSTEM_DESIGN.md section 9). */
class SendMessageUseCase(
    private val sessions: SessionManager,
    private val api: RelayApi,
    private val accounts: AccountStore,
    private val messages: MessageRepository,
    private val contacts: ContactRepository,
    private val outbox: OutboxDao,
    private val outboxProcessor: OutboxProcessor,
    private val attachments: AttachmentDao,
    private val importer: MediaImporter,
    private val transfers: AttachmentTransfers,
    private val relay: RelayTransport,
    private val meshStore: MeshStore,
    private val scope: CoroutineScope,
) {
    suspend operator fun invoke(to: String, content: MessageContent) {
        val me = accounts.current ?: error("Not registered")
        contacts.ensure(to)
        val envelope = encrypt(to, content)
        messages.saveOutgoing(envelope.envelopeId, me.userId, to, content, envelope.timestamp)
        enqueue(envelope)
    }

    /**
     * Sends a document, photo, audio file or voice note (SYSTEM_DESIGN.md 8.2):
     * copy locally → encrypt in chunks → upload to the relay's blob store → send the
     * key and blob id to the recipient inside an E2EE message.
     */
    suspend fun sendAttachment(to: String, uri: Uri, kind: AttachmentKind, durationMs: Long? = null) {
        val me = accounts.current ?: error("Not registered")
        check(relay.isConnected) {
            "Photos, files and voice notes need the relay (internet) for now. Text messages work offline."
        }
        contacts.ensure(to)
        ensureSession(to)
        val id = uuidV7()
        val imported = withContext(Dispatchers.IO) { importer.import(id, uri, kind, durationMs) }
        val keys = FileCipher.newKeys()
        val attachment = AttachmentEntity(
            id = id,
            outgoing = true,
            kind = kind,
            fileName = imported.fileName,
            mime = imported.mime,
            size = imported.size,
            sha256 = imported.sha256.toBase64(),
            fileKey = keys.fileKey.toBase64(),
            nonceBase = keys.nonceBase.toBase64(),
            chunkCount = AttachmentCodec.chunkCount(imported.size),
            durationMs = imported.durationMs,
            localPath = imported.file.absolutePath,
            state = TransferState.UPLOADING,
        )
        attachments.insert(attachment)
        messages.saveOutgoing(id, me.userId, to, attachment.toPointer(), System.currentTimeMillis())
        scope.launch { deliverAttachment(id) }
    }

    /** Tap-to-retry, and resuming uploads interrupted by the app being killed. */
    fun retryAttachment(id: String) {
        scope.launch {
            attachments.setState(id, TransferState.UPLOADING)
            messages.markQueued(id)
            deliverAttachment(id)
        }
    }

    private suspend fun deliverAttachment(id: String) {
        val attachment = attachments.get(id) ?: return
        val to = messages.get(id)?.conversationId ?: return
        try {
            val blobId = transfers.upload(attachment)
            val envelope = encrypt(to, attachment.toPointer(blobId), envelopeId = id)
            attachments.setState(id, TransferState.UPLOADED)
            enqueue(envelope)
        } catch (e: Exception) {
            Log.w("SendMessage", "Attachment $id failed to upload", e)
            attachments.setState(id, TransferState.FAILED)
            messages.markFailed(id)
        }
    }

    suspend fun sendReceipt(to: String, messageIds: List<String>, status: ReceiptStatus) {
        if (messageIds.isEmpty()) return
        enqueue(encrypt(to, MessageContent.Receipt(messageIds, status)))
    }

    /**
     * Starts an encrypted session with [to] if there isn't one: from the relay's key directory
     * when connected, otherwise from public keys heard on the offline mesh.
     */
    suspend fun ensureSession(to: String) = withContext(Dispatchers.IO) {
        if (sessions.hasSession(to)) return@withContext
        var relayError: Exception? = null
        if (accounts.current?.hasRelay == true && relay.isConnected) {
            try {
                sessions.processBundle(api.fetchPreKeyBundle(to))
                return@withContext
            } catch (e: Exception) {
                relayError = e
            }
        }
        val meshBundle = pickMeshBundle(to)
        if (meshBundle != null) {
            sessions.processBundle(meshBundle)
            return@withContext
        }
        throw relayError ?: IllegalStateException(
            "@$to isn't reachable: no internet connection to the relay, and they haven't been seen nearby yet"
        )
    }

    /**
     * Keys heard on the mesh are only used when they can't be a trap: they must match the identity
     * we already know for this person, or be the only identity anyone nearby claims for that name.
     */
    private fun pickMeshBundle(to: String): PreKeyBundleDto? {
        val candidates = meshStore.bundlesFor(to)
        if (candidates.isEmpty()) return null
        val known = sessions.identityOf(to)
        if (known != null) {
            return candidates.firstOrNull { it.identityKey == known }
                ?: throw IllegalStateException("The @$to nearby has a different security key than before. Verify in person first.")
        }
        val identities = candidates.distinctBy { it.identityKey }
        check(identities.size == 1) { "Two different people nearby call themselves @$to. Check in person which one is real." }
        return identities.single()
    }

    private suspend fun encrypt(to: String, content: MessageContent, envelopeId: String = uuidV7()): Envelope {
        ensureSession(to)
        return withContext(Dispatchers.IO) { sessions.encrypt(to, content, envelopeId) }
    }

    private suspend fun enqueue(envelope: Envelope) {
        outbox.insert(OutboxEntity(envelope.envelopeId, envelope.recipientTag, envelope.encode()))
        outboxProcessor.kick()
    }

    private fun AttachmentEntity.toPointer(blobId: String? = null) = MessageContent.Attachment(
        kind = kind,
        fileName = fileName,
        mime = mime,
        sizeBytes = size,
        sha256 = sha256,
        fileKey = fileKey,
        nonceBase = nonceBase,
        chunkCount = chunkCount,
        blobId = blobId,
        durationMs = durationMs,
    )
}
