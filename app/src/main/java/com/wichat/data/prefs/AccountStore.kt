package com.wichat.data.prefs

import android.content.Context
import com.wichat.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [token] and [relayUrl] are null for an offline-only account (created with no internet): it chats
 * over the nearby mesh until it connects to a relay in Settings.
 */
data class Account(val userId: String, val token: String?, val relayUrl: String?) {
    val hasRelay: Boolean get() = token != null && relayUrl != null
}

/**
 * Who this device is registered as. The auth token is a bearer credential for the relay only;
 * it can't decrypt anything.
 */
class AccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("wichat_account", Context.MODE_PRIVATE)
    private val _account = MutableStateFlow(load())
    val account: StateFlow<Account?> = _account.asStateFlow()

    val current: Account? get() = _account.value

    /** Last relay address typed, to pre-fill forms. */
    var relayUrl: String
        get() = prefs.getString(KEY_RELAY, null) ?: BuildConfig.DEFAULT_RELAY_URL
        set(value) = prefs.edit().putString(KEY_RELAY, value.trimEnd('/')).apply()

    var meshEnabled: Boolean
        get() = prefs.getBoolean(KEY_MESH, true)
        set(value) = prefs.edit().putBoolean(KEY_MESH, value).apply()

    fun save(account: Account) {
        val editor = prefs.edit()
            .putString(KEY_USER, account.userId)
            .putString(KEY_TOKEN, account.token)
            .putBoolean(KEY_HAS_RELAY, account.hasRelay)
        if (account.relayUrl != null) editor.putString(KEY_RELAY, account.relayUrl)
        editor.commit()
        _account.value = account
    }

    private fun load(): Account? {
        val user = prefs.getString(KEY_USER, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null)
        // Accounts saved before offline mode existed always had a relay.
        val hasRelay = prefs.getBoolean(KEY_HAS_RELAY, token != null)
        val relay = if (hasRelay) prefs.getString(KEY_RELAY, null) ?: BuildConfig.DEFAULT_RELAY_URL else null
        return Account(user, if (hasRelay) token else null, relay)
    }

    private companion object {
        const val KEY_USER = "user_id"
        const val KEY_TOKEN = "token"
        const val KEY_RELAY = "relay_url"
        const val KEY_HAS_RELAY = "has_relay"
        const val KEY_MESH = "mesh_enabled"
    }
}
