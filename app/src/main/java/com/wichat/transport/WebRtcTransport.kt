package com.wichat.transport

import com.wichat.model.Envelope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Tier 3, phase 6: direct peer-to-peer over the internet for big files and calls.
 * Plan: E2EE SDP offer/answer through the relay, STUN hole-punching, TURN fallback,
 * encrypted chunks over a DataChannel.
 */
class WebRtcTransport : Transport {
    override val name = "webrtc"
    override val incoming: Flow<Envelope> = emptyFlow()
    override suspend fun start() {}
    override suspend fun stop() {}
    override fun isPeerReachable(recipientTag: String) = false
    override suspend fun send(envelope: Envelope): TransportResult =
        TransportResult.Failed(NotYetAvailable(6, "WebRTC transport"))
}
