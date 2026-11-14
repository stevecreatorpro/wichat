package com.wichat.crypto

import com.wichat.data.remote.PreKeyBundleDto
import com.wichat.data.remote.RegisterRequest
import com.wichat.model.Envelope
import com.wichat.model.EnvelopeType
import com.wichat.model.MessageContent
import com.wichat.model.ReceiptStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Runs the real Signal Protocol (desktop libsignal build) through our store and session code. */
class SessionManagerTest {

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

    private class Device(val userId: String) {
        val records = MemoryRecords()
        val changed = mutableListOf<String>()
        val store = SignalStore(records) { id, _ -> changed += id }
        val keys = KeyManager(store)
        val sessions = SessionManager(store)
        val registration: RegisterRequest = keys.buildRegistration(userId)

        /** What the relay would hand out, consuming one one-time prekey. */
        fun bundle(preKeyIndex: Int = 0) = PreKeyBundleDto(
            username = userId,
            deviceId = 1,
            registrationId = registration.registrationId,
            identityKey = registration.identityKey,
            signedPreKey = registration.signedPreKey,
            kyberPreKey = registration.kyberPreKey,
            preKey = registration.preKeys[preKeyIndex],
        )
    }

    /** Simulates the relay: stamps the authenticated sender on the envelope. */
    private fun Envelope.viaRelay(from: String) = copy(senderId = from)

    @Test
    fun aliceAndBobExchangeMessages() {
        val alice = Device("alice")
        val bob = Device("bob")

        alice.sessions.processBundle(bob.bundle())
        val first = alice.sessions.encrypt("bob", MessageContent.Text("hi bob"))
        assertEquals(EnvelopeType.PREKEY_MESSAGE, first.type)
        assertEquals(MessageContent.Text("hi bob"), bob.sessions.decrypt(first.viaRelay("alice")))

        val reply = bob.sessions.encrypt("alice", MessageContent.Receipt(listOf(first.envelopeId), ReceiptStatus.READ))
        assertEquals(EnvelopeType.MESSAGE, reply.type)
        assertEquals(
            MessageContent.Receipt(listOf(first.envelopeId), ReceiptStatus.READ),
            alice.sessions.decrypt(reply.viaRelay("bob")),
        )

        // After Bob replied, Alice is on the Double Ratchet proper.
        val second = alice.sessions.encrypt("bob", MessageContent.Text("second"))
        assertEquals(EnvelopeType.MESSAGE, second.type)
        assertEquals(MessageContent.Text("second"), bob.sessions.decrypt(second.viaRelay("alice")))
    }

    @Test
    fun outOfOrderDeliveryStillDecrypts() {
        val alice = Device("alice")
        val bob = Device("bob")
        alice.sessions.processBundle(bob.bundle())

        val messages = (1..5).map { alice.sessions.encrypt("bob", MessageContent.Text("m$it")) }
        messages.reversed().forEach { env ->
            val text = (bob.sessions.decrypt(env.viaRelay("alice")) as MessageContent.Text).body
            assertTrue(text.startsWith("m"))
        }
    }

    @Test
    fun replayedEnvelopeIsRejected() {
        val alice = Device("alice")
        val bob = Device("bob")
        alice.sessions.processBundle(bob.bundle())
        val env = alice.sessions.encrypt("bob", MessageContent.Text("once")).viaRelay("alice")
        bob.sessions.decrypt(env)
        try {
            bob.sessions.decrypt(env)
            fail("Replay should not decrypt")
        } catch (expected: Exception) {
        }
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val alice = Device("alice")
        val bob = Device("bob")
        alice.sessions.processBundle(bob.bundle())
        val env = alice.sessions.encrypt("bob", MessageContent.Text("secret"))
        val bytes = env.ciphertext.fromBase64()
        bytes[bytes.size - 5] = (bytes[bytes.size - 5].toInt() xor 0x01).toByte()
        try {
            bob.sessions.decrypt(env.copy(ciphertext = bytes.toBase64()).viaRelay("alice"))
            fail("Tampered message should not decrypt")
        } catch (expected: Exception) {
        }
    }

    @Test
    fun sessionSurvivesRestartThroughRecordStore() {
        val alice = Device("alice")
        val bob = Device("bob")
        alice.sessions.processBundle(bob.bundle())
        bob.sessions.decrypt(alice.sessions.encrypt("bob", MessageContent.Text("1")).viaRelay("alice"))

        // Rebuild Bob's crypto objects from the same persisted records.
        val bobAgain = SessionManager(SignalStore(bob.records))
        val env = alice.sessions.encrypt("bob", MessageContent.Text("after restart"))
        assertEquals(MessageContent.Text("after restart"), bobAgain.decrypt(env.viaRelay("alice")))
    }

    @Test
    fun safetyNumbersMatchOnBothSides() {
        val alice = Device("alice")
        val bob = Device("bob")
        alice.sessions.processBundle(bob.bundle())
        bob.sessions.decrypt(alice.sessions.encrypt("bob", MessageContent.Text("hi")).viaRelay("alice"))

        val fromAlice = alice.sessions.safetyNumber("alice", "bob")
        val fromBob = bob.sessions.safetyNumber("bob", "alice")
        assertNotNull(fromAlice)
        assertEquals(60, fromAlice!!.length)
        assertEquals(fromAlice, fromBob)
    }

    @Test
    fun identityChangeIsReported() {
        val alice = Device("alice")
        val bob = Device("bob")
        alice.sessions.processBundle(bob.bundle())

        val bobNewPhone = Device("bob")
        alice.sessions.processBundle(bobNewPhone.bundle())
        assertEquals(listOf("bob"), alice.changed)
    }
}
