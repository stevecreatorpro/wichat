package com.wichat.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What lives inside [Envelope.ciphertext] once decrypted. */
@Serializable
sealed class MessageContent {

    @Serializable
    @SerialName("text")
    data class Text(val body: String) : MessageContent()

    /** Pointer to an encrypted file; the file itself travels separately in chunks (Phase 2). */
    @Serializable
    @SerialName("attachment")
    data class Attachment(
        val kind: AttachmentKind,
        val fileName: String,
        val mime: String,
        val sizeBytes: Long,
        val sha256: String,
        val fileKey: String,
        val nonceBase: String,
        val chunkCount: Int,
        val blobId: String? = null,
        val durationMs: Long? = null,
        val caption: String? = null,
    ) : MessageContent()

    @Serializable
    @SerialName("receipt")
    data class Receipt(val messageIds: List<String>, val status: ReceiptStatus) : MessageContent()

    fun encode(): ByteArray = WireJson.encodeToString(serializer(), this).encodeToByteArray()

    companion object {
        fun decode(bytes: ByteArray): MessageContent =
            WireJson.decodeFromString(serializer(), bytes.decodeToString())
    }
}

@Serializable
enum class AttachmentKind { DOCUMENT, IMAGE, AUDIO, VOICE_NOTE, VIDEO }

@Serializable
enum class ReceiptStatus { DELIVERED, READ }

enum class MessageStatus { QUEUED, SENT, DELIVERED, READ, FAILED }
