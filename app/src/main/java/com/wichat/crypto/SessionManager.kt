package com.wichat.crypto

import com.wichat.data.remote.PreKeyBundleDto
import com.wichat.model.Envelope
import com.wichat.model.EnvelopeType
import com.wichat.model.MessageContent
import com.wichat.model.uuidV7
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle

/**
 * End-to-end encryption with the Signal Protocol:
 * PQXDH to start a session (works while the recipient is offline), then the Double Ratchet
 * for every message (forward secrecy + post-compromise security).
 */
class SessionManager(private val store: SignalStore) {

    private val lock = Any()

    fun hasSession(userId: String): Boolean = synchronized(lock) { store.containsSession(address(userId)) }

    /** Base64 identity key we already trust for [userId], or null if we've never talked. */
    fun identityOf(userId: String): String? = synchronized(lock) {
        store.getIdentity(address(userId))?.serialize()?.toBase64()
    }

    /** Starts a session from the recipient's published prekey bundle. */
    fun processBundle(bundle: PreKeyBundleDto) = synchronized(lock) {
        val preKey = bundle.preKey
        val signalBundle = PreKeyBundle(
            bundle.registrationId,
            bundle.deviceId,
            preKey?.keyId ?: PreKeyBundle.NULL_PRE_KEY_ID,
            preKey?.let { ECPublicKey(it.publicKey.fromBase64()) },
            bundle.signedPreKey.keyId,
            ECPublicKey(bundle.signedPreKey.publicKey.fromBase64()),
            bundle.signedPreKey.signature.fromBase64(),
            IdentityKey(bundle.identityKey.fromBase64()),
            bundle.kyberPreKey.keyId,
            KEMPublicKey(bundle.kyberPreKey.publicKey.fromBase64()),
            bundle.kyberPreKey.signature.fromBase64(),
        )
        SessionBuilder(store, SignalProtocolAddress(bundle.username, bundle.deviceId)).process(signalBundle)
    }

    fun encrypt(recipientId: String, content: MessageContent, envelopeId: String = uuidV7()): Envelope =
        synchronized(lock) {
            val message = SessionCipher(store, address(recipientId)).encrypt(content.encode())
            Envelope(
                envelopeId = envelopeId,
                type = if (message.type == CiphertextMessage.PREKEY_TYPE) EnvelopeType.PREKEY_MESSAGE else EnvelopeType.MESSAGE,
                recipientTag = recipientId,
                timestamp = System.currentTimeMillis(),
                ciphertext = message.serialize().toBase64(),
            )
        }

    /** Throws if the envelope was tampered with, replayed, or isn't for a known session. */
    fun decrypt(envelope: Envelope): MessageContent = synchronized(lock) {
        val sender = requireNotNull(envelope.senderId) { "Envelope has no sender" }
        val cipher = SessionCipher(store, SignalProtocolAddress(sender, envelope.deviceId))
        val bytes = envelope.ciphertext.fromBase64()
        val plain = when (envelope.type) {
            EnvelopeType.PREKEY_MESSAGE -> cipher.decrypt(PreKeySignalMessage(bytes))
            EnvelopeType.MESSAGE -> cipher.decrypt(SignalMessage(bytes))
        }
        MessageContent.decode(plain)
    }

    /** 60-digit safety number both people can compare in person or over a call. */
    fun safetyNumber(localUserId: String, remoteUserId: String): String? = synchronized(lock) {
        val remoteKey = store.getIdentity(address(remoteUserId)) ?: return null
        NumericFingerprintGenerator(FINGERPRINT_ITERATIONS)
            .createFor(
                FINGERPRINT_VERSION,
                localUserId.encodeToByteArray(), store.identityKeyPair.publicKey,
                remoteUserId.encodeToByteArray(), remoteKey,
            )
            .displayableFingerprint.displayText
    }

    private fun address(userId: String) = SignalProtocolAddress(userId, SignalStore.PRIMARY_DEVICE_ID)

    private companion object {
        const val FINGERPRINT_ITERATIONS = 5200
        const val FINGERPRINT_VERSION = 2
    }
}
