package com.wichat.crypto

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.nio.ByteBuffer
import java.util.UUID

/** Minimal key/value persistence the Signal store needs. Backed by SQLCipher in the app. */
interface RecordStore {
    fun get(namespace: String, key: String): ByteArray?
    fun getAll(namespace: String): List<ByteArray>
    fun keysWithPrefix(namespace: String, prefix: String): List<String>
    fun count(namespace: String): Int
    fun put(namespace: String, key: String, value: ByteArray)
    fun delete(namespace: String, key: String)
    fun deleteWithPrefix(namespace: String, prefix: String)
}

/**
 * Persistent [SignalProtocolStore]. Identity, prekeys, Double Ratchet sessions and sender keys
 * are all stored through [RecordStore], so they survive restarts and stay encrypted at rest.
 *
 * Trust model (Phase 1): trust on first use. A changed identity key is accepted, as Signal does,
 * but [onIdentityChanged] fires so the UI can warn the user and ask them to re-verify.
 */
class SignalStore(
    private val records: RecordStore,
    private val onIdentityChanged: (userId: String, newKey: IdentityKey) -> Unit = { _, _ -> },
) : SignalProtocolStore {

    val hasLocalIdentity: Boolean
        get() = records.get(NS_LOCAL, KEY_IDENTITY) != null

    fun initializeLocalIdentity(identity: IdentityKeyPair, registrationId: Int) {
        records.put(NS_LOCAL, KEY_IDENTITY, identity.serialize())
        records.put(NS_LOCAL, KEY_REGISTRATION_ID, intBytes(registrationId))
    }

    /** Monotonic counter used for prekey ids. */
    fun nextId(counter: String, initial: Int): Int {
        val current = records.get(NS_LOCAL, counter)?.let(::bytesInt) ?: initial
        val next = if (current >= MAX_PREKEY_ID) 1 else current + 1
        records.put(NS_LOCAL, counter, intBytes(next))
        return current
    }

    fun preKeyCount(): Int = records.count(NS_PREKEY)

    fun getLocalLong(key: String): Long? = records.get(NS_LOCAL, key)?.let { ByteBuffer.wrap(it).long }

    fun putLocalLong(key: String, value: Long) =
        records.put(NS_LOCAL, key, ByteBuffer.allocate(8).putLong(value).array())

    // IdentityKeyStore

    override fun getIdentityKeyPair(): IdentityKeyPair =
        IdentityKeyPair(records.get(NS_LOCAL, KEY_IDENTITY) ?: error("Local identity not created"))

    override fun getLocalRegistrationId(): Int =
        bytesInt(records.get(NS_LOCAL, KEY_REGISTRATION_ID) ?: error("Local identity not created"))

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val existing = getIdentity(address)
        records.put(NS_IDENTITY, address.name, identityKey.serialize())
        return if (existing != null && existing != identityKey) {
            onIdentityChanged(address.name, identityKey)
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        } else {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        }
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean = true

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        records.get(NS_IDENTITY, address.name)?.let(::IdentityKey)

    // PreKeyStore

    override fun loadPreKey(preKeyId: Int): PreKeyRecord =
        PreKeyRecord(records.get(NS_PREKEY, preKeyId.toString()) ?: throw InvalidKeyIdException("No prekey $preKeyId"))

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) =
        records.put(NS_PREKEY, preKeyId.toString(), record.serialize())

    override fun containsPreKey(preKeyId: Int): Boolean = records.get(NS_PREKEY, preKeyId.toString()) != null

    override fun removePreKey(preKeyId: Int) = records.delete(NS_PREKEY, preKeyId.toString())

    // SignedPreKeyStore

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        SignedPreKeyRecord(
            records.get(NS_SIGNED_PREKEY, signedPreKeyId.toString())
                ?: throw InvalidKeyIdException("No signed prekey $signedPreKeyId")
        )

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
        records.getAll(NS_SIGNED_PREKEY).map(::SignedPreKeyRecord)

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) =
        records.put(NS_SIGNED_PREKEY, signedPreKeyId.toString(), record.serialize())

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        records.get(NS_SIGNED_PREKEY, signedPreKeyId.toString()) != null

    override fun removeSignedPreKey(signedPreKeyId: Int) =
        records.delete(NS_SIGNED_PREKEY, signedPreKeyId.toString())

    // KyberPreKeyStore (post-quantum half of PQXDH). Phase 1 uses one last-resort key that is reused.

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
        KyberPreKeyRecord(
            records.get(NS_KYBER_PREKEY, kyberPreKeyId.toString())
                ?: throw InvalidKeyIdException("No kyber prekey $kyberPreKeyId")
        )

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> =
        records.getAll(NS_KYBER_PREKEY).map(::KyberPreKeyRecord)

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) =
        records.put(NS_KYBER_PREKEY, kyberPreKeyId.toString(), record.serialize())

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean =
        records.get(NS_KYBER_PREKEY, kyberPreKeyId.toString()) != null

    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
        // A last-resort key may be used many times, but never twice with the same base key (replay).
        val usage = "$kyberPreKeyId:$signedPreKeyId:${baseKey.serialize().toBase64()}"
        if (records.get(NS_KYBER_USED, usage) != null) throw ReusedBaseKeyException()
        records.put(NS_KYBER_USED, usage, byteArrayOf(1))
    }

    // SessionStore

    override fun loadSession(address: SignalProtocolAddress): SessionRecord =
        records.get(NS_SESSION, address.key())?.let(::SessionRecord) ?: SessionRecord()

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.map {
            records.get(NS_SESSION, it.key())?.let(::SessionRecord) ?: throw NoSessionException("No session for $it")
        }

    override fun getSubDeviceSessions(name: String): List<Int> =
        records.keysWithPrefix(NS_SESSION, "$name.")
            .mapNotNull { it.substringAfterLast('.').toIntOrNull() }
            .filter { it != PRIMARY_DEVICE_ID }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) =
        records.put(NS_SESSION, address.key(), record.serialize())

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        records.get(NS_SESSION, address.key())?.let { SessionRecord(it).hasSenderChain() } ?: false

    override fun deleteSession(address: SignalProtocolAddress) = records.delete(NS_SESSION, address.key())

    override fun deleteAllSessions(name: String) = records.deleteWithPrefix(NS_SESSION, "$name.")

    // SenderKeyStore (group chats, Phase 4)

    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) =
        records.put(NS_SENDER_KEY, "${sender.key()}:$distributionId", record.serialize())

    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? =
        records.get(NS_SENDER_KEY, "${sender.key()}:$distributionId")?.let(::SenderKeyRecord)

    private fun SignalProtocolAddress.key() = "$name.$deviceId"

    private fun intBytes(value: Int) = ByteBuffer.allocate(4).putInt(value).array()

    private fun bytesInt(bytes: ByteArray) = ByteBuffer.wrap(bytes).int

    companion object {
        const val PRIMARY_DEVICE_ID = 1
        const val MAX_PREKEY_ID = 0xFFFFFF

        private const val NS_LOCAL = "local"
        private const val NS_IDENTITY = "identity"
        private const val NS_PREKEY = "prekey"
        private const val NS_SIGNED_PREKEY = "signed_prekey"
        private const val NS_KYBER_PREKEY = "kyber_prekey"
        private const val NS_KYBER_USED = "kyber_used"
        private const val NS_SESSION = "session"
        private const val NS_SENDER_KEY = "sender_key"
        private const val KEY_IDENTITY = "identity_key_pair"
        private const val KEY_REGISTRATION_ID = "registration_id"
    }
}
