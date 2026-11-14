package com.wichat.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Provides the SQLCipher passphrase. The passphrase is random and only stored
 * wrapped by an Android Keystore AES key (hardware-backed where available), so the
 * database, and the Signal private keys inside it, can't be read off the disk.
 */
class DbKeyProvider(private val context: Context) {

    fun passphrase(): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wrapped = prefs.getString(KEY_WRAPPED, null)
        val iv = prefs.getString(KEY_IV, null)
        if (wrapped != null && iv != null) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, iv.fromBase64()))
            return cipher.doFinal(wrapped.fromBase64())
        }
        val fresh = ByteArray(32).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        prefs.edit()
            .putString(KEY_WRAPPED, cipher.doFinal(fresh).toBase64())
            .putString(KEY_IV, cipher.iv.toBase64())
            .commit()
        return fresh
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "wichat_db_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFS = "wichat_db"
        const val KEY_WRAPPED = "wrapped_passphrase"
        const val KEY_IV = "iv"
    }
}
