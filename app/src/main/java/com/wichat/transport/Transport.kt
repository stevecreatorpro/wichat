package com.wichat.transport

import com.wichat.model.Envelope
import kotlinx.coroutines.flow.Flow

/**
 * One way of moving envelopes between phones. Every transport carries the same
 * already-encrypted [Envelope], so none of them needs to be trusted with content.
 */
interface Transport {
    val name: String
    val incoming: Flow<Envelope>

    suspend fun start()
    suspend fun stop()

    fun isPeerReachable(recipientTag: String): Boolean
    suspend fun send(envelope: Envelope): TransportResult

    /** Tells the transport the envelope is safely stored, so it can drop its own copy. */
    suspend fun acknowledge(envelopeId: String) {}
}

sealed class TransportResult {
    data object Accepted : TransportResult()
    data object Queued : TransportResult()
    data class Failed(val reason: Throwable) : TransportResult()
}

/** Placeholder transports return this until their phase lands. */
class NotYetAvailable(phase: Int, what: String) : UnsupportedOperationException("$what arrives in phase $phase")
