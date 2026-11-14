package com.wichat.data.db

import com.wichat.crypto.RecordStore

class RoomRecordStore(private val dao: SignalRecordDao) : RecordStore {
    override fun get(namespace: String, key: String) = dao.get(namespace, key)
    override fun getAll(namespace: String) = dao.getAll(namespace)
    override fun keysWithPrefix(namespace: String, prefix: String) = dao.keysWithPrefix(namespace, prefix)
    override fun count(namespace: String) = dao.count(namespace)
    override fun put(namespace: String, key: String, value: ByteArray) =
        dao.put(SignalRecordEntity(namespace, key, value))
    override fun delete(namespace: String, key: String) = dao.delete(namespace, key)
    override fun deleteWithPrefix(namespace: String, prefix: String) = dao.deleteWithPrefix(namespace, prefix)
}
