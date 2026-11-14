package com.wichat.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.wichat.model.MessageStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Upsert
    suspend fun upsert(user: UserEntity)

    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun get(id: String): UserEntity?

    @Query("SELECT * FROM users WHERE id = :id")
    fun observe(id: String): Flow<UserEntity?>

    @Query("UPDATE users SET keyChanged = 1, verified = 0, identityKey = :identityKey WHERE id = :id")
    fun markKeyChanged(id: String, identityKey: String)

    @Query("UPDATE users SET verified = :verified, keyChanged = 0 WHERE id = :id")
    suspend fun setVerified(id: String, verified: Boolean)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY lastMessageAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(conversation: ConversationEntity)

    @Query(
        "UPDATE conversations SET lastMessagePreview = :preview, lastMessageAt = :at, " +
            "unread = unread + :unreadDelta WHERE id = :id"
    )
    suspend fun onNewMessage(id: String, preview: String, at: Long, unreadDelta: Int)

    @Query("UPDATE conversations SET unread = 0 WHERE id = :id")
    suspend fun clearUnread(id: String)
}

@Dao
interface MessageDao {
    @Transaction
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeConversation(conversationId: String): Flow<List<MessageWithAttachment>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: String): MessageEntity?

    @Query("UPDATE messages SET status = :status, transportUsed = COALESCE(:transport, transportUsed) WHERE id = :id")
    suspend fun updateStatus(id: String, status: MessageStatus, transport: String? = null)

    /** Only moves status forward (SENT -> DELIVERED -> READ), never back. */
    @Query(
        "UPDATE messages SET status = :status WHERE id IN (:ids) AND outgoing = 1 AND " +
            "CASE status WHEN 'QUEUED' THEN 0 WHEN 'SENT' THEN 1 WHEN 'DELIVERED' THEN 2 WHEN 'READ' THEN 3 ELSE -1 END < " +
            "CASE :status WHEN 'DELIVERED' THEN 2 WHEN 'READ' THEN 3 ELSE 0 END"
    )
    suspend fun advanceStatus(ids: List<String>, status: MessageStatus)

    @Query("SELECT id FROM messages WHERE conversationId = :conversationId AND outgoing = 0 AND status != 'READ'")
    suspend fun unreadIncomingIds(conversationId: String): List<String>

    @Query("UPDATE messages SET status = 'READ' WHERE id IN (:ids)")
    suspend fun markRead(ids: List<String>)
}

@Dao
interface AttachmentDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(attachment: AttachmentEntity)

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun get(id: String): AttachmentEntity?

    @Query("UPDATE attachments SET state = :state WHERE id = :id")
    suspend fun setState(id: String, state: TransferState)

    @Query("UPDATE attachments SET blobId = :blobId WHERE id = :id")
    suspend fun setBlobId(id: String, blobId: String)

    @Query("UPDATE attachments SET chunksDone = :chunksDone WHERE id = :id")
    suspend fun setProgress(id: String, chunksDone: Int)

    @Query("UPDATE attachments SET localPath = :path, state = 'DONE', chunksDone = chunkCount WHERE id = :id")
    suspend fun setDownloaded(id: String, path: String)

    /** Transfers interrupted by the app being killed, to resume on next start. */
    @Query("SELECT * FROM attachments WHERE state IN ('UPLOADING', 'DOWNLOADING')")
    suspend fun interrupted(): List<AttachmentEntity>
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE nextRetryAt <= :now ORDER BY nextRetryAt ASC")
    suspend fun due(now: Long): List<OutboxEntity>

    @Query("SELECT MIN(nextRetryAt) FROM outbox")
    suspend fun nextRetryAt(): Long?

    @Query("UPDATE outbox SET attempts = attempts + 1, nextRetryAt = :nextRetryAt WHERE envelopeId = :id")
    suspend fun reschedule(id: String, nextRetryAt: Long)

    @Query("UPDATE outbox SET nextRetryAt = 0")
    suspend fun retryAllNow()

    @Query("DELETE FROM outbox WHERE envelopeId = :id")
    suspend fun delete(id: String)
}

/** Blocking on purpose: libsignal calls the store synchronously from a background thread. */
@Dao
interface SignalRecordDao {
    @Query("SELECT value FROM signal_records WHERE namespace = :namespace AND `key` = :key")
    fun get(namespace: String, key: String): ByteArray?

    @Query("SELECT value FROM signal_records WHERE namespace = :namespace")
    fun getAll(namespace: String): List<ByteArray>

    @Query("SELECT `key` FROM signal_records WHERE namespace = :namespace AND `key` LIKE :prefix || '%'")
    fun keysWithPrefix(namespace: String, prefix: String): List<String>

    @Query("SELECT COUNT(*) FROM signal_records WHERE namespace = :namespace")
    fun count(namespace: String): Int

    @Upsert
    fun put(record: SignalRecordEntity)

    @Query("DELETE FROM signal_records WHERE namespace = :namespace AND `key` = :key")
    fun delete(namespace: String, key: String)

    @Query("DELETE FROM signal_records WHERE namespace = :namespace AND `key` LIKE :prefix || '%'")
    fun deleteWithPrefix(namespace: String, prefix: String)
}

/** Blocking: the mesh engine runs on its own single background thread. */
@Dao
interface MeshDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertEnvelope(entry: MeshCacheEntity): Long

    @Query("SELECT payload FROM mesh_cache WHERE envelopeId = :id")
    fun envelope(id: String): String?

    @Query("SELECT COUNT(*) FROM mesh_cache WHERE envelopeId = :id")
    fun hasEnvelope(id: String): Int

    @Query("SELECT envelopeId FROM mesh_cache WHERE expiresAt > :now ORDER BY expiresAt DESC LIMIT :limit")
    fun envelopeIds(now: Long, limit: Int): List<String>

    @Query("SELECT COUNT(*) FROM mesh_cache WHERE expiresAt > :now")
    fun carriedCount(now: Long): Int

    @Query("DELETE FROM mesh_cache WHERE envelopeId = :id")
    fun deleteEnvelope(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertDelivered(entry: MeshDeliveredEntity)

    @Query("SELECT COUNT(*) FROM mesh_delivered WHERE envelopeId = :id")
    fun isDelivered(id: String): Int

    @Query("SELECT bundle FROM mesh_directory WHERE userId = :userId AND identityKey = :identityKey")
    fun bundle(userId: String, identityKey: String): String?

    @Upsert
    fun putBundle(entry: MeshDirectoryEntity)

    @Query("SELECT bundle FROM mesh_directory WHERE userId = :userId")
    fun bundlesFor(userId: String): List<String>

    @Query("SELECT bundle FROM mesh_directory ORDER BY updatedAt DESC LIMIT :limit")
    fun recentBundles(limit: Int): List<String>

    @Query("SELECT DISTINCT userId FROM mesh_directory ORDER BY updatedAt DESC")
    fun observeDirectoryUsers(): Flow<List<String>>

    @Query("DELETE FROM mesh_cache WHERE expiresAt <= :now")
    fun pruneEnvelopes(now: Long)

    @Query("DELETE FROM mesh_delivered WHERE expiresAt <= :now")
    fun pruneDelivered(now: Long)
}

@Dao
interface SeenEnvelopeDao {
    /** Returns -1 if the id was already seen. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markSeen(entry: SeenEnvelopeEntity): Long

    @Query("DELETE FROM seen_envelopes WHERE receivedAt < :before")
    suspend fun prune(before: Long)
}
