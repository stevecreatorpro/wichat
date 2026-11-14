package com.wichat.service

import android.util.Log
import com.wichat.data.db.OutboxDao
import com.wichat.data.repo.MessageRepository
import com.wichat.model.Envelope
import com.wichat.transport.Router
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Drains the outbox through the [Router]. Envelopes stay queued (and encrypted) until a
 * transport accepts them, with exponential backoff, and are retried immediately on a kick
 * (network change, relay reconnect, new message).
 */
class OutboxProcessor(
    private val outbox: OutboxDao,
    private val router: Router,
    private val messages: MessageRepository,
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        this.scope = scope
        job = scope.launch {
            while (true) {
                drain()
                val next = outbox.nextRetryAt()
                val wait = next?.let { (it - System.currentTimeMillis()).coerceAtLeast(0) } ?: Long.MAX_VALUE
                withTimeoutOrNull(wait) { wake.receive() }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Process now. With [retryAll], also ignore backoff (connectivity just came back). */
    fun kick(retryAll: Boolean = false) {
        val scope = scope
        if (retryAll && scope != null) {
            scope.launch { outbox.retryAllNow(); wake.trySend(Unit) }
        } else {
            wake.trySend(Unit)
        }
    }

    private suspend fun drain() {
        val now = System.currentTimeMillis()
        for (item in outbox.due(now)) {
            val envelope = runCatching { Envelope.decode(item.payload) }.getOrNull()
            if (envelope == null) {
                Log.e(TAG, "Dropping unreadable outbox item ${item.envelopeId}")
                outbox.delete(item.envelopeId)
                continue
            }
            if (now - envelope.timestamp > MAX_AGE) {
                outbox.delete(item.envelopeId)
                messages.markFailed(item.envelopeId)
                continue
            }
            val transport = router.route(envelope, item.payload.length.toLong())
            if (transport != null) {
                outbox.delete(item.envelopeId)
                messages.markSent(item.envelopeId, transport.name)
            } else {
                val backoff = minOf(MAX_BACKOFF, BASE_BACKOFF shl minOf(item.attempts, 10))
                outbox.reschedule(item.envelopeId, now + backoff)
            }
        }
    }

    private companion object {
        const val TAG = "OutboxProcessor"
        const val BASE_BACKOFF = 2_000L
        val MAX_BACKOFF = TimeUnit.MINUTES.toMillis(10)
        val MAX_AGE = TimeUnit.DAYS.toMillis(30)
    }
}
