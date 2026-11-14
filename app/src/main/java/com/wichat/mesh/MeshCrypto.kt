package com.wichat.mesh

import com.wichat.crypto.SignalStore
import com.wichat.crypto.fromBase64
import com.wichat.crypto.toBase64
import com.wichat.data.remote.PreKeyBundleDto
import com.wichat.data.remote.SignedPreKeyDto
import com.wichat.model.Envelope
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey

/**
 * Mesh-specific crypto on top of the Signal session: hides the sender from relaying phones
 * (sealed with the recipient's identity key) and checks key bundles heard from neighbours.
 */
class MeshCrypto(
    private val store: SignalStore,
    private val localUserId: () -> String?,
) {
    fun myTags(now: Long): Set<String> = localUserId()?.let { MeshTags.tagsFor(it, now) } ?: emptySet()

    /** Turns a relay-style envelope (recipientTag = username) into a mesh envelope. */
    fun seal(envelope: Envelope, now: Long): Envelope {
        val me = requireNotNull(localUserId()) { "No account" }
        val recipient = envelope.recipientTag
        val recipientKey = store.getIdentity(SignalProtocolAddress(recipient, SignalStore.PRIMARY_DEVICE_ID))
            ?: error("No identity key for $recipient")
        val sealed = recipientKey.publicKey.seal(me.encodeToByteArray(), SEAL_INFO, envelope.envelopeId.encodeToByteArray())
        return envelope.copy(
            senderId = null,
            sealedSender = sealed.toBase64(),
            recipientTag = MeshTags.tag(recipient, now),
            ttlHops = DEFAULT_TTL,
        )
    }

    /** Returns the envelope with [Envelope.senderId] filled in, or null if it isn't really for us. */
    fun open(envelope: Envelope): Envelope? {
        val sealed = envelope.sealedSender ?: return null
        return runCatching {
            val sender = store.identityKeyPair.privateKey
                .open(sealed.fromBase64(), SEAL_INFO, envelope.envelopeId.encodeToByteArray())
                .decodeToString()
            envelope.copy(senderId = sender, sealedSender = null)
        }.getOrNull()
    }

    /** This phone's public bundle to share with neighbours (no one-time prekey: those are for the relay). */
    fun ownBundle(): PreKeyBundleDto? {
        val me = localUserId() ?: return null
        if (!store.hasLocalIdentity) return null
        val signed = store.loadSignedPreKeys().maxByOrNull { it.timestamp } ?: return null
        val kyber = store.loadKyberPreKeys().maxByOrNull { it.timestamp } ?: return null
        return PreKeyBundleDto(
            username = me,
            deviceId = SignalStore.PRIMARY_DEVICE_ID,
            registrationId = store.localRegistrationId,
            identityKey = store.identityKeyPair.publicKey.serialize().toBase64(),
            signedPreKey = SignedPreKeyDto(signed.id, signed.keyPair.publicKey.serialize().toBase64(), signed.signature.toBase64()),
            kyberPreKey = SignedPreKeyDto(kyber.id, kyber.keyPair.publicKey.serialize().toBase64(), kyber.signature.toBase64()),
            preKey = null,
        )
    }

    /** A bundle is only kept if its prekeys are really signed by the identity key it claims. */
    fun verifyBundle(bundle: PreKeyBundleDto): Boolean = runCatching {
        val identity = IdentityKey(bundle.identityKey.fromBase64()).publicKey
        bundle.preKey == null &&
            bundle.username.isNotBlank() && bundle.username.length <= 32 &&
            identity.verifySignature(bundle.signedPreKey.publicKey.fromBase64(), bundle.signedPreKey.signature.fromBase64()) &&
            identity.verifySignature(bundle.kyberPreKey.publicKey.fromBase64(), bundle.kyberPreKey.signature.fromBase64()) &&
            ECPublicKey(bundle.signedPreKey.publicKey.fromBase64()).serialize().isNotEmpty()
    }.getOrDefault(false)

    companion object {
        const val DEFAULT_TTL = 16
        private const val SEAL_INFO = "wichat-mesh-sealed-sender-v1"
    }
}
