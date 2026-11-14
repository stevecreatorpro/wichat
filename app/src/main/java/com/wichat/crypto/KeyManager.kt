package com.wichat.crypto

import com.wichat.data.remote.PreKeyDto
import com.wichat.data.remote.RegisterRequest
import com.wichat.data.remote.SignedPreKeyDto
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** Creates and rotates this device's identity and prekeys. Private halves stay in [SignalStore]. */
class KeyManager(private val store: SignalStore) {

    fun buildRegistration(username: String): RegisterRequest {
        if (!store.hasLocalIdentity) {
            store.initializeLocalIdentity(IdentityKeyPair.generate(), KeyHelper.generateRegistrationId(false))
        }
        return RegisterRequest(
            username = username,
            registrationId = store.localRegistrationId,
            identityKey = store.identityKeyPair.publicKey.serialize().toBase64(),
            signedPreKey = newSignedPreKey(),
            kyberPreKey = newKyberPreKey(),
            preKeys = generatePreKeys(PREKEY_BATCH),
        )
    }

    fun generatePreKeys(count: Int): List<PreKeyDto> = List(count) {
        val id = store.nextId(COUNTER_PREKEY, randomStartId())
        val record = PreKeyRecord(id, ECKeyPair.generate())
        store.storePreKey(id, record)
        PreKeyDto(id, record.keyPair.publicKey.serialize().toBase64())
    }

    fun needsMorePreKeys(serverCount: Int) = serverCount < PREKEY_LOW_WATERMARK

    /** Returns a new signed prekey to upload if the current one is older than a week. */
    fun rotateSignedPreKeyIfDue(now: Long = System.currentTimeMillis()): SignedPreKeyDto? {
        val last = store.getLocalLong(KEY_SIGNED_ROTATED_AT) ?: 0L
        if (now - last < SIGNED_PREKEY_MAX_AGE) return null
        val fresh = newSignedPreKey(now)
        // Keep previous signed prekeys briefly so in-flight first messages still decrypt.
        store.loadSignedPreKeys()
            .filter { now - it.timestamp > 2 * SIGNED_PREKEY_MAX_AGE }
            .forEach { store.removeSignedPreKey(it.id) }
        return fresh
    }

    private fun newSignedPreKey(now: Long = System.currentTimeMillis()): SignedPreKeyDto {
        val id = store.nextId(COUNTER_SIGNED, randomStartId())
        val keyPair = ECKeyPair.generate()
        val signature = store.identityKeyPair.privateKey.calculateSignature(keyPair.publicKey.serialize())
        store.storeSignedPreKey(id, SignedPreKeyRecord(id, now, keyPair, signature))
        store.putLocalLong(KEY_SIGNED_ROTATED_AT, now)
        return SignedPreKeyDto(id, keyPair.publicKey.serialize().toBase64(), signature.toBase64())
    }

    private fun newKyberPreKey(now: Long = System.currentTimeMillis()): SignedPreKeyDto {
        val id = store.nextId(COUNTER_KYBER, randomStartId())
        val keyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val publicKey = keyPair.publicKey.serialize()
        val signature = store.identityKeyPair.privateKey.calculateSignature(publicKey)
        store.storeKyberPreKey(id, KyberPreKeyRecord(id, now, keyPair, signature))
        return SignedPreKeyDto(id, publicKey.toBase64(), signature.toBase64())
    }

    private fun randomStartId() = SecureRandom().nextInt(SignalStore.MAX_PREKEY_ID / 2) + 1

    companion object {
        const val PREKEY_BATCH = 100
        const val PREKEY_LOW_WATERMARK = 20
        val SIGNED_PREKEY_MAX_AGE = TimeUnit.DAYS.toMillis(7)
        private const val COUNTER_PREKEY = "next_prekey_id"
        private const val COUNTER_SIGNED = "next_signed_prekey_id"
        private const val COUNTER_KYBER = "next_kyber_prekey_id"
        private const val KEY_SIGNED_ROTATED_AT = "signed_prekey_rotated_at"
    }
}
