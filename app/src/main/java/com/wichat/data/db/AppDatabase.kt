package com.wichat.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        UserEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        AttachmentEntity::class,
        OutboxEntity::class,
        SignalRecordEntity::class,
        SeenEnvelopeEntity::class,
        PeerEntity::class,
        MeshCacheEntity::class,
        MeshDeliveredEntity::class,
        MeshDirectoryEntity::class,
    ],
    version = 2,
    exportSchema = true,
    // Phones already running version 1 keep their chats; v2 only adds the offline-mesh tables.
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun outboxDao(): OutboxDao
    abstract fun signalRecordDao(): SignalRecordDao
    abstract fun seenEnvelopeDao(): SeenEnvelopeDao
    abstract fun meshDao(): MeshDao

    companion object {
        fun create(context: Context, passphrase: ByteArray): AppDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, AppDatabase::class.java, "wichat.db")
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .build()
        }
    }
}
