package com.neoproc.neoproccamx.security

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import com.neoproc.neoproccamx.utils.CryptoUtils
import javax.crypto.SecretKey

class SecureSecretVault(private val context: Context) {
    companion object {
        private const val PREFS_NAME = "camx_secure_vault"
        private const val MASTER_KEY_ALIAS = "camx_master_key_v1"
        private const val INSTALLATION_SECRET_KEY = "installation_secret"
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun ensureMasterKey(): Boolean =
        CryptoUtils.getKeyFromKeystore(MASTER_KEY_ALIAS) != null ||
            CryptoUtils.generateKeyInKeystore(MASTER_KEY_ALIAS)

    @SuppressLint("UseKtx")
    fun putString(key: String, value: String): Boolean {
        val secretKey = resolveKey() ?: return false
        val encrypted = CryptoUtils.encryptString(value, secretKey)
        return prefs.edit().putString(key, encrypted).commit()
    }

    fun getString(key: String): String? {
        val secretKey = resolveKey() ?: return null
        val encrypted = prefs.getString(key, null) ?: return null
        return runCatching {
            CryptoUtils.decryptString(encrypted, secretKey)
        }.getOrNull()
    }

    fun putBytes(key: String, value: ByteArray): Boolean {
        val encoded = Base64.encodeToString(value, Base64.NO_WRAP)
        return putString(key, encoded)
    }

    fun getBytes(key: String): ByteArray? =
        getString(key)?.let { Base64.decode(it, Base64.NO_WRAP) }

    fun getOrCreateInstallationSecret(): String {
        getString(INSTALLATION_SECRET_KEY)?.let { return it }
        val generated = CryptoUtils.generateSecureToken()
        putString(INSTALLATION_SECRET_KEY, generated)
        return generated
    }

    private fun resolveKey(): SecretKey? {
        if (!ensureMasterKey()) return null
        return CryptoUtils.getKeyFromKeystore(MASTER_KEY_ALIAS)
    }
}
