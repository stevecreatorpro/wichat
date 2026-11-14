package com.wichat.media

import android.util.Log
import com.wichat.crypto.AttachmentCodec
import com.wichat.crypto.BlobStore
import com.wichat.crypto.FileCipher
import com.wichat.crypto.fromBase64
import com.wichat.data.db.AttachmentDao
import com.wichat.data.db.AttachmentEntity
import com.wichat.data.db.TransferState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Encrypted chunked uploads and downloads with progress saved after every chunk,
 * so an interrupted transfer resumes where it stopped.
 */
class AttachmentTransfers(
    private val store: BlobStore,
    private val attachments: AttachmentDao,
    private val importer: MediaImporter,
    private val scope: CoroutineScope,
) {
    private val downloads = ConcurrentHashMap<String, Job>()

    /** Uploads the attachment's local file and returns its blob id. */
    suspend fun upload(attachment: AttachmentEntity): String = withContext(Dispatchers.IO) {
        val file = File(requireNotNull(attachment.localPath) { "No local file" })
        val blobId = attachment.blobId ?: store.createBlob(attachment.size, attachment.chunkCount).also {
            attachments.setBlobId(attachment.id, it)
        }
        file.inputStream().buffered().use { input ->
            AttachmentCodec.upload(
                input = input,
                keys = attachment.keys(),
                store = store,
                blobId = blobId,
                chunkCount = attachment.chunkCount,
                startAt = attachment.chunksDone,
                onChunkDone = { attachments.setProgress(attachment.id, it) },
            )
        }
        blobId
    }

    /** Starts (or joins) a background download; safe to call repeatedly. */
    fun download(id: String) {
        downloads.computeIfAbsent(id) {
            scope.launch {
                try {
                    downloadNow(id)
                } finally {
                    downloads.remove(id)
                }
            }
        }
    }

    private suspend fun downloadNow(id: String) {
        val attachment = attachments.get(id) ?: return
        if (attachment.state == TransferState.DONE) return
        val blobId = attachment.blobId ?: return attachments.setState(id, TransferState.FAILED)
        attachments.setState(id, TransferState.DOWNLOADING)
        val dir = importer.attachmentDir(id)
        val part = File(dir, "download.part")
        try {
            withContext(Dispatchers.IO) {
                part.outputStream().buffered().use { output ->
                    AttachmentCodec.download(
                        store = store,
                        blobId = blobId,
                        keys = attachment.keys(),
                        chunkCount = attachment.chunkCount,
                        expectedSize = attachment.size,
                        expectedSha256 = attachment.sha256.fromBase64(),
                        output = output,
                        onChunkDone = { attachments.setProgress(id, it) },
                    )
                }
                val target = File(dir, FileNames.safe(attachment.fileName))
                if (!part.renameTo(target)) throw java.io.IOException("Could not save file")
                attachments.setDownloaded(id, target.absolutePath)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Download of $id failed", e)
            part.delete()
            attachments.setProgress(id, 0)
            attachments.setState(id, TransferState.FAILED)
        }
    }

    private fun AttachmentEntity.keys() = FileCipher.FileKeys(fileKey.fromBase64(), nonceBase.fromBase64())

    private companion object {
        const val TAG = "AttachmentTransfers"
    }
}
