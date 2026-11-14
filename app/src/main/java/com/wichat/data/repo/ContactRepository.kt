package com.wichat.data.repo

import com.wichat.data.db.UserDao
import com.wichat.data.db.UserEntity
import kotlinx.coroutines.flow.Flow

class ContactRepository(private val users: UserDao) {

    suspend fun ensure(userId: String) {
        if (users.get(userId) == null) users.upsert(UserEntity(id = userId, displayName = userId))
    }

    fun observe(userId: String): Flow<UserEntity?> = users.observe(userId)

    /** Called after the user compared safety numbers with the contact. */
    suspend fun setVerified(userId: String, verified: Boolean) = users.setVerified(userId, verified)
}
