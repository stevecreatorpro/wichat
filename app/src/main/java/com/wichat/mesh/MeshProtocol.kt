package com.wichat.mesh

import com.wichat.data.remote.PreKeyBundleDto
import com.wichat.model.Envelope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Messages phones exchange with direct neighbours on the offline mesh. */
@Serializable
sealed class MeshFrame {
    /** "These are the envelopes I'm carrying." */
    @Serializable
    @SerialName("offer")
    data class Offer(val ids: List<String>) : MeshFrame()

    /** "Send me these, I don't have them yet." */
    @Serializable
    @SerialName("request")
    data class Request(val ids: List<String>) : MeshFrame()

    @Serializable
    @SerialName("envelopes")
    data class Envelopes(val items: List<Envelope>) : MeshFrame()

    /** Anti-packet: the recipient has these, so everyone can stop carrying them. */
    @Serializable
    @SerialName("delivered")
    data class Delivered(val ids: List<String>) : MeshFrame()

    /** Public key bundles, so people can start encrypted chats with no server. */
    @Serializable
    @SerialName("bundles")
    data class Bundles(val items: List<PreKeyBundleDto>) : MeshFrame()

    fun encode(): ByteArray = MeshJson.encodeToString(serializer(), this).encodeToByteArray()

    companion object {
        fun decode(bytes: ByteArray): MeshFrame = MeshJson.decodeFromString(serializer(), bytes.decodeToString())
    }
}

internal val MeshJson = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "frame"
}

/**
 * Who an envelope is for, as relaying phones see it: a hash of the recipient's username and the
 * day, so the plain username never travels and the tag changes daily.
 */
object MeshTags {
    private val DAY = TimeUnit.DAYS.toMillis(1)

    fun tag(userId: String, now: Long): String = tagForDay(userId, now / DAY)

    /** Today's tag plus neighbours, to tolerate clock differences between phones. */
    fun tagsFor(userId: String, now: Long): Set<String> {
        val day = now / DAY
        return setOf(tagForDay(userId, day - 1), tagForDay(userId, day), tagForDay(userId, day + 1))
    }

    private fun tagForDay(userId: String, day: Long): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("wichat-mesh-v1|$userId|$day".encodeToByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.copyOf(16))
    }
}
