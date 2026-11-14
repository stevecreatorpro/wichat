package com.wichat.domain

import android.util.Log
import com.wichat.crypto.AttachmentCodec
import com.wichat.crypto.SessionManager
import com.wichat.data.db.AttachmentDao
import com.wichat.data.db.AttachmentEntity
import com.wichat.data.db.SeenEnvelopeDao
import com.wichat.data.db.SeenEnvelopeEntity
import com.wichat.data.db.TransferState
import com.wichat.data.repo.ContactRepository
import com.wichat.data.repo.MessageRepository
import com.wichat.media.AttachmentTransfers
import com.wichat.media.FileNames
import com.wichat.model.MessageContent
import com.wichat.model.ReceiptStatus
import com.wichat.service.Notifier
import com.wichat.transport.Received
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Dedup → decrypt → store → receipt → notify, for envelopes from any transport. */
class IncomingMessageProcessor(
    private val seen: SeenEnvelopeDao,
    private val sessions: SessionManager,
    private val messages: MessageRepository,
    private val contacts: ContactRepository,
    private val attachments: AttachmentDao,
    private val transfers: AttachmentTransfers,
    private val send: SendMessageUseCase,
    private val notifier: Notifier,
) {
    suspend fun handle(received: Received) {
        val envelope = received.envelope
        val firstTime = seen.markSeen(SeenEnvelopeEntity(envelope.envelopeId, System.currentTimeMillis())) != -1L
        if (firstTime) {
            val sender = envelope.senderId
            val content = runCatching { withContext(Dispatchers.IO) { sessions.decrypt(envelope) } }
                .onFailure { Log.w(TAG, "Dropping envelope ${envelope.envelopeId} that failed to decrypt", it) }
                .getOrNull()
            if (sender != null && content != null) process(sender, envelope.envelopeId, envelope.timestamp, content, received)
        }
        // Only acknowledge once stored, so the relay redelivers if we crash first.
        received.transport.acknowledge(envelope.envelopeId)
    }

    private suspend fun process(sender: String, id: String, at: Long, content: MessageContent, received: Received) {
        when (content) {
            is MessageContent.Receipt -> messages.applyReceipt(content)
            is MessageContent.Text -> {
                store(sender, id, at, content, received)
                notifier.showMessage(sender, content.body)
            }
            is MessageContent.Attachment -> {
                if (!content.isWellFormed()) {
                    Log.w(TAG, "Ignoring malformed attachment pointer $id")
                    return
                }
                attachments.insert(content.toEntity(id))
                store(sender, id, at, content, received)
                notifier.showMessage(sender, content.caption ?: "Sent you ${FileNames.safe(content.fileName)}")
                if (content.sizeBytes <= AUTO_DOWNLOAD_MAX_BYTES) transfers.download(id)
            }
        }
    }

    private suspend fun store(sender: String, id: String, at: Long, content: MessageContent, received: Received) {
        contacts.ensure(sender)
        messages.saveIncoming(id, sender, content, at, received.transport.name)
        runCatching { send.sendReceipt(sender, listOf(id), ReceiptStatus.DELIVERED) }
            .onFailure { Log.w(TAG, "Could not queue delivery receipt", it) }
    }

    private fun MessageContent.Attachment.isWellFormed() =
        blobId != null && sizeBytes >= 0 && chunkCount == AttachmentCodec.chunkCount(sizeBytes)

    private fun MessageContent.Attachment.toEntity(id: String) = AttachmentEntity(
        id = id,
        outgoing = false,
        kind = kind,
        fileName = FileNames.safe(fileName),
        mime = mime,
        size = sizeBytes,
        sha256 = sha256,
        fileKey = fileKey,
        nonceBase = nonceBase,
        blobId = blobId,
        chunkCount = chunkCount,
        durationMs = durationMs,
        state = TransferState.PENDING_DOWNLOAD,
    )

    private companion object {
        const val TAG = "IncomingProcessor"
        /** Bigger files wait for a tap so they don't silently eat mobile data. */
        const val AUTO_DOWNLOAD_MAX_BYTES = 25L * 1024 * 1024
    }
}
