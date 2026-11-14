package com.wichat.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.wichat.data.db.AttachmentDao
import com.wichat.data.db.SeenEnvelopeDao
import com.wichat.domain.IncomingMessageProcessor
import com.wichat.domain.KeyMaintenanceUseCase
import com.wichat.domain.SendMessageUseCase
import com.wichat.media.AttachmentTransfers
import com.wichat.transport.ConnectionState
import com.wichat.transport.MeshTransport
import com.wichat.transport.RelayTransport
import com.wichat.transport.Router
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Long-running owner of the connections: starts the transports, feeds incoming envelopes to the
 * processor, and keeps the outbox draining. Runs in the foreground so Android doesn't kill it.
 */
@AndroidEntryPoint
class MessagingService : LifecycleService() {

    @Inject lateinit var router: Router
    @Inject lateinit var relay: RelayTransport
    @Inject lateinit var mesh: MeshTransport
    @Inject lateinit var network: NetworkMonitor
    @Inject lateinit var outbox: OutboxProcessor
    @Inject lateinit var incoming: IncomingMessageProcessor
    @Inject lateinit var keyMaintenance: KeyMaintenanceUseCase
    @Inject lateinit var seen: SeenEnvelopeDao
    @Inject lateinit var notifier: Notifier
    @Inject lateinit var attachments: AttachmentDao
    @Inject lateinit var transfers: AttachmentTransfers
    @Inject lateinit var send: SendMessageUseCase

    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        ServiceCompat.startForeground(
            this, Notifier.SERVICE_NOTIFICATION_ID, notifier.serviceNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        if (!started) {
            started = true
            startPipelines()
        }
        return START_STICKY
    }

    private fun startPipelines() {
        lifecycleScope.launch { router.start() }
        lifecycleScope.launch { router.incoming.collect { incoming.handle(it) } }
        outbox.start(lifecycleScope)

        lifecycleScope.launch {
            relay.state.filter { it == ConnectionState.CONNECTED }.collect {
                outbox.kick(retryAll = true)
                keyMaintenance()
            }
        }
        lifecycleScope.launch {
            network.hasInternet.drop(1).filter { it }.collect {
                relay.reconnectNow()
                outbox.kick(retryAll = true)
            }
        }
        lifecycleScope.launch {
            // A phone came into range on the offline mesh: hand it whatever is waiting.
            mesh.state.map { it.peers }.distinctUntilChanged().filter { it > 0 }.collect {
                outbox.kick(retryAll = true)
            }
        }
        lifecycleScope.launch {
            seen.prune(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(60))
        }
        lifecycleScope.launch {
            // Resume file transfers cut off when the app was last killed.
            for (attachment in attachments.interrupted()) {
                if (attachment.outgoing) send.retryAttachment(attachment.id) else transfers.download(attachment.id)
            }
        }
    }

    override fun onDestroy() {
        outbox.stop()
        // Transports are app-scoped singletons; stop their connections with the service.
        runBlocking { router.stop() }
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MessagingService::class.java))
        }
    }
}
