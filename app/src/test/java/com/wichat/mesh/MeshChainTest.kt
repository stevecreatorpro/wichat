package com.wichat.mesh

import com.wichat.crypto.KeyManager
import com.wichat.crypto.RecordStore
import com.wichat.crypto.SessionManager
import com.wichat.crypto.SignalStore
import com.wichat.crypto.fromBase64
import com.wichat.data.remote.PreKeyBundleDto
import com.wichat.model.Envelope
import com.wichat.model.MessageContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

/**
 * Simulates phones in a line, each only in range of its neighbours, with the real Signal crypto:
 * phone 1 messages phone 10 with no internet and no prior contact.
 */
class MeshChainTest {

    private class MemoryRecords : RecordStore {
        val data = mutableMapOf<Pair<String, String>, ByteArray>()
        override fun get(namespace: String, key: String) = data[namespace to key]
        override fun getAll(namespace: String) = data.filterKeys { it.first == namespace }.values.toList()
        override fun keysWithPrefix(namespace: String, prefix: String) =
            data.keys.filter { it.first == namespace && it.second.startsWith(prefix) }.map { it.second }
        override fun count(namespace: String) = data.keys.count { it.first == namespace }
        override fun put(namespace: String, key: String, value: ByteArray) { data[namespace to key] = value }
        override fun delete(namespace: String, key: String) { data.remove(namespace to key) }
        override fun deleteWithPrefix(namespace: String, prefix: String) {
            data.keys.removeAll { it.first == namespace && it.second.startsWith(prefix) }
        }
    }

    private class MemoryMeshStore : MeshStore {
        val envelopes = mutableMapOf<String, Pair<Envelope, Long>>()
        val delivered = mutableMapOf<String, Long>()
        val bundles = linkedMapOf<Pair<String, String>, PreKeyBundleDto>()
        override fun putEnvelope(envelope: Envelope, expiresAt: Long) =
            envelopes.putIfAbsent(envelope.envelopeId, envelope to expiresAt) == null
        override fun getEnvelope(id: String) = envelopes[id]?.first
        override fun hasEnvelope(id: String) = id in envelopes
        override fun envelopeIds(now: Long, limit: Int) = envelopes.filterValues { it.second > now }.keys.take(limit)
        override fun removeEnvelope(id: String) { envelopes.remove(id) }
        override fun markDelivered(id: String, expiresAt: Long) { delivered[id] = expiresAt }
        override fun isDelivered(id: String) = id in delivered
        override fun putBundle(bundle: PreKeyBundleDto, now: Long): Boolean {
            val key = bundle.username to bundle.identityKey
            if (bundles[key] == bundle) return false
            bundles[key] = bundle
            return true
        }
        override fun bundlesFor(userId: String) = bundles.values.filter { it.username == userId }
        override fun recentBundles(limit: Int) = bundles.values.toList().takeLast(limit)
        override fun prune(now: Long) {
            envelopes.values.removeAll { it.second <= now }
            delivered.values.removeAll { it <= now }
        }
    }

    /** Delivers frames between phones in order, like radio links. */
    private class Air {
        val queue = ArrayDeque<Triple<String, String, ByteArray>>()
        val phones = mutableMapOf<String, Phone>()
        val framesSent = mutableListOf<Pair<String, ByteArray>>()

        fun connect(a: Phone, b: Phone) {
            a.neighbours += b.id
            b.neighbours += a.id
            a.engine.onPeerConnected(b.id)
            b.engine.onPeerConnected(a.id)
            run()
        }

        fun run() {
            var steps = 0
            while (queue.isNotEmpty()) {
                check(steps++ < 100_000) { "mesh never settled" }
                val (from, to, bytes) = queue.removeFirst()
                phones.getValue(to).engine.onFrame(from, bytes)
            }
        }
    }

    private class Phone(val id: String, air: Air) {
        val records = MemoryRecords()
        val signal = SignalStore(records)
        val sessions = SessionManager(signal)
        val mesh = MemoryMeshStore()
        val crypto = MeshCrypto(signal) { id }
        val inbox = mutableListOf<Envelope>()
        val neighbours = mutableSetOf<String>()
        val engine: MeshEngine

        init {
            KeyManager(signal).buildRegistration(id)
            air.phones[id] = this
            engine = MeshEngine(
                store = mesh,
                links = object : MeshLinks {
                    override val peers: Set<String> get() = neighbours
                    override fun send(peer: String, frame: ByteArray) {
                        air.queue.addLast(Triple(id, peer, frame))
                        air.framesSent += id to frame
                    }
                },
                crypto = crypto,
                onDeliver = { inbox += it },
            )
        }

        /** What the app does: start a session from a bundle heard on the mesh, encrypt, seal, submit. */
        fun send(to: String, text: String, air: Air): Envelope {
            if (!sessions.hasSession(to)) sessions.processBundle(mesh.bundlesFor(to).single())
            val sealed = crypto.seal(sessions.encrypt(to, MessageContent.Text(text)), System.currentTimeMillis())
            engine.submit(sealed)
            air.run()
            return sealed
        }
    }

    private fun chain(n: Int): Pair<Air, List<Phone>> {
        val air = Air()
        val phones = (1..n).map { Phone("person$it", air) }
        phones.zipWithNext().forEach { (a, b) -> air.connect(a, b) }
        return air to phones
    }

    @Test
    fun messageHopsAcrossTenPhonesAndOnlyTheRecipientCanReadIt() {
        val (air, phones) = chain(10)
        val me = phones.first()
        val target = phones.last()

        // Person 10's public keys reached person 1 hop by hop, with no server.
        assertEquals(1, me.mesh.bundlesFor("person10").size)

        val sent = me.send("person10", "meet at the north gate at 6", air)

        // Delivered to person 10, who can see who sent it and read it.
        val received = target.inbox.single()
        assertEquals("person1", received.senderId)
        assertEquals(MessageContent.Text("meet at the north gate at 6"), target.sessions.decrypt(received))

        // Nobody in between learns anything: no plaintext, no names, and they can't unseal the sender.
        val secret = "north gate".encodeToByteArray().toList()
        for (phone in phones.drop(1).dropLast(1)) {
            assertTrue("${phone.id} got nothing for itself", phone.inbox.isEmpty())
            assertNull("${phone.id} can't unseal the sender", phone.crypto.open(sent))
        }
        for ((_, frame) in air.framesSent) {
            val text = frame.decodeToString()
            // Key bundles are a public phone book; message frames must carry no usernames at all.
            if (text.contains("\"frame\":\"envelopes\"")) assertFalse(text, text.contains("person"))
            assertFalse(frame.toList().windowed(secret.size).any { it == secret })
        }
        assertFalse(sent.ciphertext.fromBase64().toList().windowed(secret.size).any { it == secret })

        // Once person 10 has it, everyone stops carrying it.
        target.engine.markDelivered(received.envelopeId)
        air.run()
        phones.forEach { assertFalse("${it.id} still carries it", it.mesh.hasEnvelope(received.envelopeId)) }
    }

    @Test
    fun replyTravelsBackTheOtherWay() {
        val (air, phones) = chain(10)
        val me = phones.first()
        val target = phones.last()
        me.send("person10", "hello", air)
        target.sessions.decrypt(target.inbox.single())

        target.send("person1", "got it", air)
        assertEquals(MessageContent.Text("got it"), me.sessions.decrypt(me.inbox.single()))
    }

    @Test
    fun phoneCarriesMessageUntilSomeoneComesIntoRange() {
        val air = Air()
        val alice = Phone("alice", air)
        val carrier = Phone("carrier", air)
        val bob = Phone("bob", air)

        // Alice learned Bob's keys earlier through the carrier, then everyone split up.
        air.connect(alice, carrier)
        air.connect(carrier, bob)
        listOf(alice, carrier, bob).forEach { it.neighbours.clear() }

        // Alice is only near the carrier, who takes the message although Bob is out of range.
        air.connect(alice, carrier)
        val sent = alice.send("bob", "are you safe?", air)
        assertTrue(bob.inbox.isEmpty())
        assertTrue(carrier.mesh.hasEnvelope(sent.envelopeId))

        // The carrier walks over to Bob later.
        listOf(alice, carrier, bob).forEach { it.neighbours.clear() }
        air.connect(carrier, bob)
        assertEquals(MessageContent.Text("are you safe?"), bob.sessions.decrypt(bob.inbox.single()))
    }

    @Test
    fun hopLimitStopsEndlessFlooding() {
        val (air, phones) = chain(20)
        val sent = phones.first().send("person20", "too far", air)
        assertTrue("16 hops is not enough for a 20-phone line", phones.last().inbox.isEmpty())
        assertFalse(phones.last().mesh.hasEnvelope(sent.envelopeId))
    }

    @Test
    fun forgedKeyBundlesAreIgnored() {
        val air = Air()
        val alice = Phone("alice", air)
        val mallory = Phone("mallory", air)
        val real = mallory.crypto.ownBundle()!!
        // Mallory claims to be "bob" but swaps in keys not signed by the identity she presents.
        val forged = real.copy(username = "bob", signedPreKey = real.signedPreKey.copy(signature = real.kyberPreKey.signature))
        alice.engine.onFrame("mallory", MeshFrame.Bundles(listOf(forged)).encode())
        assertTrue(alice.mesh.bundlesFor("bob").isEmpty())
        alice.engine.onFrame("mallory", MeshFrame.Bundles(listOf(real)).encode())
        assertNotNull(alice.mesh.bundlesFor("mallory").singleOrNull())
    }
}
