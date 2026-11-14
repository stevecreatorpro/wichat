package com.wichat.di

import android.content.Context
import com.wichat.crypto.DbKeyProvider
import com.wichat.crypto.KeyManager
import com.wichat.crypto.SessionManager
import com.wichat.crypto.SignalStore
import com.wichat.crypto.toBase64
import com.wichat.data.db.AppDatabase
import com.wichat.data.db.AttachmentDao
import com.wichat.data.db.OutboxDao
import com.wichat.data.db.RoomRecordStore
import com.wichat.data.db.SeenEnvelopeDao
import com.wichat.data.prefs.AccountStore
import com.wichat.data.remote.RelayApi
import com.wichat.data.repo.ContactRepository
import com.wichat.data.repo.MessageRepository
import com.wichat.domain.IncomingMessageProcessor
import com.wichat.domain.KeyMaintenanceUseCase
import com.wichat.domain.RegisterUseCase
import com.wichat.domain.SendMessageUseCase
import com.wichat.media.AttachmentTransfers
import com.wichat.media.MediaImporter
import com.wichat.media.VoiceRecorder
import com.wichat.service.NetworkMonitor
import com.wichat.service.Notifier
import com.wichat.service.OutboxProcessor
import com.wichat.mesh.MeshCrypto
import com.wichat.mesh.MeshStore
import com.wichat.data.db.MeshDao
import com.wichat.data.db.RoomMeshStore
import com.wichat.transport.MeshTransport
import com.wichat.transport.RelayTransport
import com.wichat.transport.Router
import com.wichat.transport.WebRtcTransport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.create(context, DbKeyProvider(context).passphrase())

    @Provides fun outboxDao(db: AppDatabase): OutboxDao = db.outboxDao()
    @Provides fun seenDao(db: AppDatabase): SeenEnvelopeDao = db.seenEnvelopeDao()

    @Provides @Singleton
    fun accountStore(@ApplicationContext context: Context) = AccountStore(context)

    @Provides @Singleton
    fun signalStore(db: AppDatabase): SignalStore {
        val users = db.userDao()
        return SignalStore(RoomRecordStore(db.signalRecordDao())) { userId, key ->
            users.markKeyChanged(userId, key.serialize().toBase64())
        }
    }

    @Provides @Singleton fun keyManager(store: SignalStore) = KeyManager(store)
    @Provides @Singleton fun sessionManager(store: SignalStore) = SessionManager(store)

    @Provides @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // Keeps NAT mappings alive and detects dead sockets.
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    @Provides @Singleton
    fun relayApi(http: OkHttpClient, accounts: AccountStore) = RelayApi(http, accounts)

    @Provides @Singleton
    fun messageRepository(db: AppDatabase) = MessageRepository(db.conversationDao(), db.messageDao())

    @Provides @Singleton
    fun contactRepository(db: AppDatabase) = ContactRepository(db.userDao())

    @Provides @Singleton
    fun networkMonitor(@ApplicationContext context: Context) = NetworkMonitor(context)

    @Provides @Singleton
    fun notifier(@ApplicationContext context: Context) = Notifier(context)

    @Provides @Singleton
    fun relayTransport(http: OkHttpClient, accounts: AccountStore, @AppScope scope: CoroutineScope) =
        RelayTransport(http, accounts, scope)

    @Provides @Singleton
    fun meshStore(db: AppDatabase): MeshStore = RoomMeshStore(db.meshDao())

    @Provides fun meshDao(db: AppDatabase): MeshDao = db.meshDao()

    @Provides @Singleton
    fun meshCrypto(store: SignalStore, accounts: AccountStore) = MeshCrypto(store) { accounts.current?.userId }

    @Provides @Singleton
    fun meshTransport(
        @ApplicationContext context: Context,
        accounts: AccountStore,
        crypto: MeshCrypto,
        store: MeshStore,
        @AppScope scope: CoroutineScope,
    ) = MeshTransport(context, accounts, crypto, store, scope)

    @Provides @Singleton fun webRtcTransport() = WebRtcTransport()

    @Provides @Singleton
    fun router(relay: RelayTransport, webrtc: WebRtcTransport, mesh: MeshTransport) = Router(relay, webrtc, mesh)

    @Provides @Singleton
    fun outboxProcessor(outbox: OutboxDao, router: Router, messages: MessageRepository) =
        OutboxProcessor(outbox, router, messages)

    @Provides fun attachmentDao(db: AppDatabase): AttachmentDao = db.attachmentDao()

    @Provides @Singleton
    fun mediaImporter(@ApplicationContext context: Context) = MediaImporter(context)

    @Provides @Singleton
    fun attachmentTransfers(
        api: RelayApi,
        attachments: AttachmentDao,
        importer: MediaImporter,
        @AppScope scope: CoroutineScope,
    ) = AttachmentTransfers(api, attachments, importer, scope)

    @Provides @Singleton
    fun sendMessage(
        sessions: SessionManager,
        api: RelayApi,
        accounts: AccountStore,
        messages: MessageRepository,
        contacts: ContactRepository,
        outbox: OutboxDao,
        processor: OutboxProcessor,
        attachments: AttachmentDao,
        importer: MediaImporter,
        transfers: AttachmentTransfers,
        relay: RelayTransport,
        meshStore: MeshStore,
        @AppScope scope: CoroutineScope,
    ) = SendMessageUseCase(
        sessions, api, accounts, messages, contacts, outbox, processor, attachments, importer, transfers,
        relay, meshStore, scope,
    )

    @Provides @Singleton
    fun incomingProcessor(
        seen: SeenEnvelopeDao,
        sessions: SessionManager,
        messages: MessageRepository,
        contacts: ContactRepository,
        attachments: AttachmentDao,
        transfers: AttachmentTransfers,
        send: SendMessageUseCase,
        notifier: Notifier,
    ) = IncomingMessageProcessor(seen, sessions, messages, contacts, attachments, transfers, send, notifier)

    @Provides fun voiceRecorder(@ApplicationContext context: Context) = VoiceRecorder(context)

    @Provides fun register(keys: KeyManager, api: RelayApi, accounts: AccountStore) = RegisterUseCase(keys, api, accounts)
    @Provides fun keyMaintenance(keys: KeyManager, api: RelayApi, accounts: AccountStore) =
        KeyMaintenanceUseCase(keys, api, accounts)
}
