package com.wichat.domain

import android.util.Log
import com.wichat.BuildConfig
import com.wichat.crypto.KeyManager
import com.wichat.data.prefs.Account
import com.wichat.data.prefs.AccountStore
import com.wichat.data.remote.RelayApi
import com.wichat.model.normalizeUserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RegisterUseCase(
    private val keys: KeyManager,
    private val api: RelayApi,
    private val accounts: AccountStore,
) {
    /**
     * Generates identity + prekeys on the device and publishes only the public halves.
     * Also used to connect an existing (e.g. offline-only) account to a relay: the identity is kept.
     */
    suspend operator fun invoke(rawUsername: String, relayUrl: String) {
        val username = validUsername(rawUsername)
        val url = relayUrl.trim().trimEnd('/')
        require(BuildConfig.DEBUG || url.startsWith("https://") && url.length > "https://".length) {
            "Enter your relay's https:// address"
        }
        val request = withContext(Dispatchers.IO) { keys.buildRegistration(username) }
        val response = api.register(url, request)
        accounts.save(Account(username, response.token, url))
    }

    /**
     * No internet: create the account on this phone only. It chats over the nearby mesh, and can
     * connect to a relay later in Settings (the username might be taken there by then).
     */
    suspend fun createOffline(rawUsername: String) {
        val username = validUsername(rawUsername)
        withContext(Dispatchers.IO) { keys.buildRegistration(username) }
        accounts.save(Account(username, token = null, relayUrl = null))
    }

    private fun validUsername(raw: String): String {
        val username = normalizeUserId(raw)
        require(USERNAME.matches(username)) { "Use 3–32 letters, digits, '.' or '_'" }
        return username
    }

    private companion object {
        val USERNAME = Regex("^[a-z0-9._]{3,32}$")
    }
}

/** Keeps enough one-time prekeys on the relay and rotates the signed prekey weekly. */
class KeyMaintenanceUseCase(
    private val keys: KeyManager,
    private val api: RelayApi,
    private val accounts: AccountStore,
) {
    suspend operator fun invoke() {
        if (accounts.current?.hasRelay != true) return
        runCatching {
            if (keys.needsMorePreKeys(api.preKeyCount())) {
                api.uploadPreKeys(withContext(Dispatchers.IO) { keys.generatePreKeys(KeyManager.PREKEY_BATCH) })
            }
            withContext(Dispatchers.IO) { keys.rotateSignedPreKeyIfDue() }?.let { api.uploadSignedPreKey(it) }
        }.onFailure { Log.w("KeyMaintenance", "Key refresh failed, will retry next start", it) }
    }
}
