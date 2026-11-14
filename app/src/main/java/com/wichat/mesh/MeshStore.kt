package com.wichat.mesh

import com.wichat.data.remote.PreKeyBundleDto
import com.wichat.model.Envelope

/** What a phone remembers for the mesh: envelopes it carries, finished ones, and people's public keys. */
interface MeshStore {
    /** Returns false if the envelope was already carried. */
    fun putEnvelope(envelope: Envelope, expiresAt: Long): Boolean
    fun getEnvelope(id: String): Envelope?
    fun hasEnvelope(id: String): Boolean
    fun envelopeIds(now: Long, limit: Int): List<String>
    fun removeEnvelope(id: String)

    fun markDelivered(id: String, expiresAt: Long)
    fun isDelivered(id: String): Boolean

    /** Returns true if this is new or changed information. */
    fun putBundle(bundle: PreKeyBundleDto, now: Long): Boolean
    fun bundlesFor(userId: String): List<PreKeyBundleDto>
    fun recentBundles(limit: Int): List<PreKeyBundleDto>

    fun prune(now: Long)
}
