package com.wichat.transport

import com.wichat.model.Envelope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

data class Received(val envelope: Envelope, val transport: Transport)

/**
 * Chooses how each envelope travels (SYSTEM_DESIGN.md section 6). Same envelope, different pipes:
 * the relay when it's connected (any distance), otherwise the offline mesh of nearby phones.
 */
class Router(
    private val relay: RelayTransport,
    private val webrtc: WebRtcTransport,
    private val mesh: MeshTransport,
) {
    private val all: List<Transport> = listOf(relay, webrtc, mesh)

    val incoming: Flow<Received> = merge(*all.map { t -> t.incoming.map { Received(it, t) } }.toTypedArray())

    suspend fun start() = all.forEach { it.start() }

    suspend fun stop() = all.forEach { it.stop() }

    /** Returns the transport that accepted the envelope, or null if it should stay queued. */
    suspend fun route(envelope: Envelope, sizeHint: Long): Transport? {
        val candidates = buildList {
            if (relay.isConnected) {
                if (sizeHint > LARGE_PAYLOAD && webrtc.isPeerReachable(envelope.recipientTag)) add(webrtc)
                add(relay)
            }
            // Also covers an offline-only recipient the relay doesn't know ("Unknown recipient").
            if (mesh.isEnabled) add(mesh)
        }
        for (transport in candidates) {
            if (transport.send(envelope) is TransportResult.Accepted) return transport
        }
        return null
    }

    private companion object {
        const val LARGE_PAYLOAD = 5L * 1024 * 1024
    }
}
