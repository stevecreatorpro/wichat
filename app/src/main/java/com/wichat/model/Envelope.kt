package com.wichat.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire format shared by every transport (relay, LAN, Wi-Fi Direct, mesh).
 * Only [ciphertext] carries content, and it is end-to-end encrypted.
 */
@Serializable
data class Envelope(
    val envelopeId: String,
    val version: Int = 1,
    val type: EnvelopeType,
    /** Filled in by the relay from the authenticated connection; null for sealed sender (later phase). */
    val senderId: String? = null,
    /** userId on the relay, rotating pseudonym on the mesh. */
    val recipientTag: String,
    val deviceId: Int = 1,
    val timestamp: Long,
    val ttlHops: Int = 8,
    /** Base64 of the Signal ciphertext. */
    val ciphertext: String,
    /**
     * Mesh only: the sender's id sealed to the recipient's identity key, so phones relaying the
     * envelope can't tell who sent it. [senderId] is null whenever this is set.
     */
    val sealedSender: String? = null,
) {
    fun encode(): String = WireJson.encodeToString(serializer(), this)

    companion object {
        fun decode(raw: String): Envelope = WireJson.decodeFromString(serializer(), raw)
    }
}

@Serializable
enum class EnvelopeType {
    /** Normal Double Ratchet message. */
    MESSAGE,
    /** First message of a session (carries the PQXDH key agreement). */
    PREKEY_MESSAGE,
}

val WireJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    // Must not clash with a property name: MessageContent.Attachment has a `kind` field.
    classDiscriminator = "type"
}
