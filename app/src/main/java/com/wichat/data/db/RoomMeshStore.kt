package com.wichat.data.db

import com.wichat.data.remote.PreKeyBundleDto
import com.wichat.mesh.MeshStore
import com.wichat.model.Envelope
import com.wichat.model.WireJson

class RoomMeshStore(private val dao: MeshDao) : MeshStore {

    override fun putEnvelope(envelope: Envelope, expiresAt: Long): Boolean =
        dao.insertEnvelope(
            MeshCacheEntity(envelope.envelopeId, envelope.recipientTag, envelope.encode(), envelope.ttlHops, expiresAt)
        ) != -1L

    override fun getEnvelope(id: String): Envelope? = dao.envelope(id)?.let(Envelope::decode)

    override fun hasEnvelope(id: String) = dao.hasEnvelope(id) > 0

    override fun envelopeIds(now: Long, limit: Int) = dao.envelopeIds(now, limit)

    override fun removeEnvelope(id: String) = dao.deleteEnvelope(id)

    override fun markDelivered(id: String, expiresAt: Long) = dao.insertDelivered(MeshDeliveredEntity(id, expiresAt))

    override fun isDelivered(id: String) = dao.isDelivered(id) > 0

    override fun putBundle(bundle: PreKeyBundleDto, now: Long): Boolean {
        val json = WireJson.encodeToString(PreKeyBundleDto.serializer(), bundle)
        if (dao.bundle(bundle.username, bundle.identityKey) == json) return false
        dao.putBundle(MeshDirectoryEntity(bundle.username, bundle.identityKey, json, now))
        return true
    }

    override fun bundlesFor(userId: String) = dao.bundlesFor(userId).map(::decode)

    override fun recentBundles(limit: Int) = dao.recentBundles(limit).map(::decode)

    override fun prune(now: Long) {
        dao.pruneEnvelopes(now)
        dao.pruneDelivered(now)
    }

    private fun decode(json: String) = WireJson.decodeFromString(PreKeyBundleDto.serializer(), json)
}
