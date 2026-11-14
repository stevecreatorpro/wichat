package com.wichat.mesh

import com.wichat.model.Envelope
import java.util.concurrent.TimeUnit

/** How the engine reaches direct neighbours (Nearby Connections in the app, in-memory in tests). */
interface MeshLinks {
    val peers: Set<String>
    fun send(peer: String, frame: ByteArray)
}

/**
 * Store-carry-forward routing for the offline mesh (SYSTEM_DESIGN.md 7.3).
 *
 * Every phone keeps envelopes addressed to others and passes them to each neighbour it meets,
 * up to [MeshCrypto.DEFAULT_TTL] hops and [ENVELOPE_LIFETIME]. Neighbours first swap lists of ids so
 * nothing is sent twice. When the recipient gets a message it floods a "delivered" notice so
 * everyone stops carrying it. Relaying phones only ever see ciphertext, a sealed sender and a
 * daily-changing recipient tag.
 *
 * Not thread-safe: callers serialize access (one dispatcher in the app).
 */
class MeshEngine(
    private val store: MeshStore,
    private val links: MeshLinks,
    private val crypto: MeshCrypto,
    private val onDeliver: (Envelope) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** A neighbour connected: share keys and tell it what we carry. */
    fun onPeerConnected(peer: String) {
        val bundles = listOfNotNull(crypto.ownBundle()) + store.recentBundles(MAX_GOSSIP_BUNDLES)
        sendBatched(peer, bundles.distinctBy { it.username to it.identityKey }) { MeshFrame.Bundles(it) }
        val ids = store.envelopeIds(now(), MAX_OFFER)
        if (ids.isNotEmpty()) sendBatched(peer, ids) { MeshFrame.Offer(it) }
    }

    /** Our own outgoing, already sealed envelope. */
    fun submit(envelope: Envelope) {
        require(envelope.sealedSender != null && envelope.senderId == null) { "Seal before submitting to the mesh" }
        require(envelope.encode().length < MAX_FRAME_BYTES / 2) { "Envelope too large for the mesh" }
        if (store.putEnvelope(envelope, expiry(envelope))) broadcast(MeshFrame.Envelopes(listOf(envelope)), except = null)
    }

    /** Called once the app has safely stored a message the mesh delivered to us. */
    fun markDelivered(envelopeId: String) {
        if (store.isDelivered(envelopeId)) return
        store.markDelivered(envelopeId, now() + ENVELOPE_LIFETIME)
        store.removeEnvelope(envelopeId)
        broadcast(MeshFrame.Delivered(listOf(envelopeId)), except = null)
    }

    fun onFrame(peer: String, bytes: ByteArray) {
        val frame = runCatching { MeshFrame.decode(bytes) }.getOrNull() ?: return
        when (frame) {
            is MeshFrame.Offer -> {
                val wanted = frame.ids.take(MAX_OFFER).filter { !store.hasEnvelope(it) && !store.isDelivered(it) }
                if (wanted.isNotEmpty()) sendBatched(peer, wanted) { MeshFrame.Request(it) }
            }
            is MeshFrame.Request -> {
                val items = frame.ids.take(MAX_OFFER).mapNotNull(store::getEnvelope)
                sendEnvelopes(peer, items)
            }
            is MeshFrame.Envelopes -> frame.items.forEach { receive(peer, it) }
            is MeshFrame.Delivered -> {
                val fresh = frame.ids.filter { !store.isDelivered(it) }
                fresh.forEach {
                    store.markDelivered(it, now() + ENVELOPE_LIFETIME)
                    store.removeEnvelope(it)
                }
                if (fresh.isNotEmpty()) broadcast(MeshFrame.Delivered(fresh), except = peer)
            }
            is MeshFrame.Bundles -> {
                val learned = frame.items.filter { crypto.verifyBundle(it) && store.putBundle(it, now()) }
                if (learned.isNotEmpty()) {
                    links.peers.filter { it != peer }.forEach { p -> sendBatched(p, learned) { MeshFrame.Bundles(it) } }
                }
            }
        }
    }

    private fun receive(from: String, envelope: Envelope) {
        val t = now()
        if (envelope.sealedSender == null || store.isDelivered(envelope.envelopeId)) return
        if (expiry(envelope) <= t) return
        if (envelope.recipientTag in crypto.myTags(t)) {
            // Only ours if we can unseal it; otherwise it's someone else with the same username, so pass it on.
            val opened = crypto.open(envelope)
            if (opened != null) {
                onDeliver(opened)
                return
            }
        }
        if (envelope.ttlHops <= 0) return
        val carried = envelope.copy(ttlHops = envelope.ttlHops - 1)
        if (store.putEnvelope(carried, expiry(carried))) broadcast(MeshFrame.Envelopes(listOf(carried)), except = from)
    }

    private fun expiry(envelope: Envelope) = minOf(envelope.timestamp, now()) + ENVELOPE_LIFETIME

    private fun broadcast(frame: MeshFrame, except: String?) {
        val bytes = frame.encode()
        links.peers.filter { it != except }.forEach { links.send(it, bytes) }
    }

    private fun sendEnvelopes(peer: String, items: List<Envelope>) {
        var batch = mutableListOf<Envelope>()
        var size = 0
        for (e in items) {
            val len = e.encode().length
            if (batch.isNotEmpty() && size + len > MAX_FRAME_BYTES - 200) {
                links.send(peer, MeshFrame.Envelopes(batch).encode())
                batch = mutableListOf()
                size = 0
            }
            batch += e
            size += len
        }
        if (batch.isNotEmpty()) links.send(peer, MeshFrame.Envelopes(batch).encode())
    }

    /** Splits a list across frames so each stays under the Nearby payload limit. */
    private fun <T> sendBatched(peer: String, items: List<T>, frameOf: (List<T>) -> MeshFrame) {
        var batch = mutableListOf<T>()
        for (item in items) {
            batch += item
            if (frameOf(batch).encode().size > MAX_FRAME_BYTES) {
                batch.removeAt(batch.lastIndex)
                if (batch.isNotEmpty()) links.send(peer, frameOf(batch).encode())
                batch = mutableListOf(item)
            }
        }
        if (batch.isNotEmpty()) links.send(peer, frameOf(batch).encode())
    }

    companion object {
        /** Nearby Connections BYTES payloads max out at 32 KB. */
        const val MAX_FRAME_BYTES = 30_000
        val ENVELOPE_LIFETIME = TimeUnit.HOURS.toMillis(48)
        private const val MAX_OFFER = 2_000
        private const val MAX_GOSSIP_BUNDLES = 200
    }
}
