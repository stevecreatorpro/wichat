package com.wichat.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.wichat.model.AttachmentKind
import com.wichat.model.MessageStatus

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    /** Base64 identity public key, mirrored from the Signal store for display. */
    val identityKey: String? = null,
    val verified: Boolean = false,
    /** Set when the contact's identity key changed (new phone, or a possible MITM). */
    val keyChanged: Boolean = false,
    val lastSeen: Long? = null,
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    /** For direct chats this is the peer's userId. */
    @PrimaryKey val id: String,
    val type: String = TYPE_DIRECT,
    val title: String,
    val lastMessagePreview: String? = null,
    val lastMessageAt: Long = 0,
    val unread: Int = 0,
    val muted: Boolean = false,
) {
    companion object {
        const val TYPE_DIRECT = "DIRECT"
        const val TYPE_GROUP = "GROUP"
    }
}

@Entity(tableName = "messages", indices = [Index("conversationId", "createdAt")])
data class MessageEntity(
    /** Same as the envelopeId it travelled in. */
    @PrimaryKey val id: String,
    val conversationId: String,
    val senderId: String,
    val outgoing: Boolean,
    val kind: String,
    val body: String?,
    val attachmentId: String? = null,
    val status: MessageStatus,
    val transportUsed: String? = null,
    val createdAt: Long,
) {
    companion object {
        const val KIND_TEXT = "TEXT"
        const val KIND_ATTACHMENT = "ATTACHMENT"
    }
}

enum class TransferState { UPLOADING, UPLOADED, PENDING_DOWNLOAD, DOWNLOADING, DONE, FAILED }

/**
 * An encrypted file transfer. [id] equals the id of the message that carries it.
 * [fileKey] never leaves the device except inside the E2EE attachment pointer.
 */
@Entity(tableName = "attachments")
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val outgoing: Boolean,
    val kind: AttachmentKind,
    val fileName: String,
    val mime: String,
    val size: Long,
    val sha256: String,
    val fileKey: String,
    val nonceBase: String,
    val blobId: String? = null,
    val chunkCount: Int,
    val chunksDone: Int = 0,
    val durationMs: Long? = null,
    val localPath: String? = null,
    val state: TransferState,
)

data class MessageWithAttachment(
    @Embedded val message: MessageEntity,
    @Relation(parentColumn = "attachmentId", entityColumn = "id")
    val attachment: AttachmentEntity?,
)

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val envelopeId: String,
    val recipientId: String,
    /** Serialized, already-encrypted [com.wichat.model.Envelope]. */
    val payload: String,
    val attempts: Int = 0,
    val nextRetryAt: Long = 0,
    val preferredTransport: String? = null,
)

/** Generic encrypted key/value storage backing the Signal protocol store. */
@Entity(tableName = "signal_records", primaryKeys = ["namespace", "key"])
data class SignalRecordEntity(
    val namespace: String,
    val key: String,
    val value: ByteArray,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Envelope ids already processed, so a message arriving over two transports is shown once. */
@Entity(tableName = "seen_envelopes")
data class SeenEnvelopeEntity(
    @PrimaryKey val envelopeId: String,
    val receivedAt: Long,
)

/** Phase 3: devices found on the LAN / Wi-Fi Direct. */
@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val peerId: String,
    val address: String,
    val transport: String,
    val lastSeen: Long,
    val hasInternet: Boolean = false,
)

/** Envelopes this phone is carrying for other people on the offline mesh. */
@Entity(tableName = "mesh_cache")
data class MeshCacheEntity(
    @PrimaryKey val envelopeId: String,
    val recipientTag: String,
    val payload: String,
    val hopsLeft: Int,
    val expiresAt: Long,
)

/** Envelopes the mesh has already delivered, so nobody picks them up again. */
@Entity(tableName = "mesh_delivered")
data class MeshDeliveredEntity(
    @PrimaryKey val envelopeId: String,
    val expiresAt: Long,
)

/** Public key bundles heard on the mesh, for starting encrypted chats with no server. */
@Entity(tableName = "mesh_directory", primaryKeys = ["userId", "identityKey"])
data class MeshDirectoryEntity(
    val userId: String,
    val identityKey: String,
    /** Serialized [com.wichat.data.remote.PreKeyBundleDto]. */
    val bundle: String,
    val updatedAt: Long,
)
