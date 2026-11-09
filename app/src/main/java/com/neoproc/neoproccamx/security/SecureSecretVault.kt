package com.neoproc.neoproccamx.security

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import com.neoproc.neoproccamx.utils.CryptoUtils
import java.nio.charset.StandardCharsets
import javax.crypto.SecretKey

private data class SecureSecretVaultNamespace(
    val preferencesName: String,
    val masterKeyAlias: String
)

class SecureSecretVault private constructor(
    private val context: Context,
    namespace: SecureSecretVaultNamespace
) {
    constructor(context: Context) : this(
        context,
        SecureSecretVaultNamespace(PREFS_NAME, MASTER_KEY_ALIAS)
    )

    /** Test-only namespace injection keeps instrumentation away from live application secrets. */
    internal constructor(
        context: Context,
        isolatedPreferencesName: String,
        isolatedMasterKeyAlias: String
    ) : this(
        context,
        SecureSecretVaultNamespace(isolatedPreferencesName, isolatedMasterKeyAlias)
    )

    private val preferencesName = namespace.preferencesName
    private val masterKeyAlias = namespace.masterKeyAlias

    companion object {
        private const val PREFS_NAME = "camx_secure_vault"
        private const val MASTER_KEY_ALIAS = "camx_master_key_v1"
        private const val INSTALLATION_SECRET_KEY = "installation_secret"
        private const val AUTHENTICATED_ENVELOPE_PREFIX = "gcm2:"
        private val VALID_ENTRY_KEY = Regex("[A-Za-z0-9._-]{1,128}")
        private val VALID_NAMESPACE_COMPONENT = Regex("[A-Za-z0-9._-]{1,128}")

        /**
         * AndroidKeyStore and its encrypted SharedPreferences envelope form one transaction. A
         * process-wide monitor prevents different vault instances (app warmup, workers, and tests)
         * from observing a generated/deleted key between the corresponding preference operation.
         */
        private val PROCESS_WIDE_VAULT_KEY_LOCK = Any()
    }

    init {
        require(VALID_NAMESPACE_COMPONENT.matches(preferencesName)) {
            "Invalid secure-vault preferences namespace"
        }
        require(VALID_NAMESPACE_COMPONENT.matches(masterKeyAlias)) {
            "Invalid secure-vault key alias"
        }
    }

    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    fun ensureMasterKey(): Boolean = synchronized(PROCESS_WIDE_VAULT_KEY_LOCK) {
        ensureMasterKeyLocked()
    }

    @SuppressLint("UseKtx")
    fun putString(key: String, value: String): Boolean {
        val plaintext = value.toByteArray(StandardCharsets.UTF_8)
        return try {
            synchronized(PROCESS_WIDE_VAULT_KEY_LOCK) {
                putAuthenticatedBytesLocked(key, plaintext)
            }
        } finally {
            CryptoUtils.secureWipe(plaintext)
        }
    }

    fun getString(key: String): String? = synchronized(PROCESS_WIDE_VAULT_KEY_LOCK) {
        getStringLocked(key)
    }

    private fun getStringLocked(key: String): String? {
        val stored = getStoredValue(key) ?: return null
        val decrypted = if (stored.startsWith(AUTHENTICATED_ENVELOPE_PREFIX)) {
            decryptAuthenticatedBytesLocked(key, stored)
        } else {
            decryptLegacyStringLocked(stored)?.toByteArray(StandardCharsets.UTF_8)
        } ?: return null
        return try {
            String(decrypted, StandardCharsets.UTF_8).also {
                if (!stored.startsWith(AUTHENTICATED_ENVELOPE_PREFIX)) {
                    putAuthenticatedBytesLocked(key, decrypted)
                }
            }
        } finally {
            CryptoUtils.secureWipe(decrypted)
        }
    }

    fun putBytes(key: String, value: ByteArray): Boolean =
        synchronized(PROCESS_WIDE_VAULT_KEY_LOCK) {
            putAuthenticatedBytesLocked(key, value)
        }

    fun getBytes(key: String): ByteArray? = synchronized(PROCESS_WIDE_VAULT_KEY_LOCK) {
        getBytesLocked(key)
    }

    private fun getBytesLocked(key: String): ByteArray? {
        val stored = getStoredValue(key) ?: return null
        if (stored.startsWith(AUTHENTICATED_ENVELOPE_PREFIX)) {
            return decryptAuthenticatedBytesLocked(key, stored)
        }
        val legacy = decryptLegacyStringLocked(stored) ?: return null
        return runCatching { Base64.decode(legacy, Base64.NO_WRAP) }
            .getOrNull()
            ?.also { putAuthenticatedBytesLocked(key, it) }
    }

    fun getOrCreateInstallationSecret(): String = synchronized(PROCESS_WIDE_VAULT_KEY_LOCK) {
        getStringLocked(INSTALLATION_SECRET_KEY)?.let { return@synchronized it }
        val generated = CryptoUtils.generateSecureToken()
        val plaintext = generated.toByteArray(StandardCharsets.UTF_8)
        try {
            check(putAuthenticatedBytesLocked(INSTALLATION_SECRET_KEY, plaintext)) {
                "Unable to persist the installation secret"
            }
        } finally {
            CryptoUtils.secureWipe(plaintext)
        }
        return@synchronized checkNotNull(getStringLocked(INSTALLATION_SECRET_KEY)) {
            "Unable to verify the persisted installation secret"
        }
    }

    // Security-critical exception: callers must know whether the authenticated secret reached
    // durable SharedPreferences storage. apply() cannot report persistence failure.
    @SuppressLint("ApplySharedPref", "UseKtx")
    private fun putAuthenticatedBytesLocked(key: String, value: ByteArray): Boolean {
        requireValidEntryKey(key)
        val secretKey = resolveKeyLocked() ?: return false
        val encrypted = runCatching {
            CryptoUtils.encryptAesGcm(value, secretKey, associatedDataFor(key))
        }.getOrNull() ?: return false
        return try {
            val encoded = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            prefs.edit()
                .putString(key, AUTHENTICATED_ENVELOPE_PREFIX + encoded)
                .commit()
        } finally {
            CryptoUtils.secureWipe(encrypted)
        }
    }

    private fun decryptAuthenticatedBytesLocked(key: String, stored: String): ByteArray? {
        requireValidEntryKey(key)
        val secretKey = resolveKeyLocked() ?: return null
        val encrypted = runCatching {
            Base64.decode(stored.removePrefix(AUTHENTICATED_ENVELOPE_PREFIX), Base64.NO_WRAP)
        }.getOrNull() ?: return null
        return try {
            runCatching {
                CryptoUtils.decryptAesGcm(encrypted, secretKey, associatedDataFor(key))
            }.getOrNull()
        } finally {
            CryptoUtils.secureWipe(encrypted)
        }
    }

    private fun decryptLegacyStringLocked(stored: String): String? {
        val secretKey = resolveKeyLocked() ?: return null
        return runCatching { CryptoUtils.decryptString(stored, secretKey) }.getOrNull()
    }

    private fun getStoredValue(key: String): String? {
        requireValidEntryKey(key)
        return prefs.getString(key, null)
    }

    private fun associatedDataFor(key: String): ByteArray =
        "$preferencesName:$key".toByteArray(StandardCharsets.UTF_8)

    private fun requireValidEntryKey(key: String) {
        require(VALID_ENTRY_KEY.matches(key)) { "Invalid secure-vault entry key" }
    }

    private fun ensureMasterKeyLocked(): Boolean =
        CryptoUtils.getKeyFromKeystore(masterKeyAlias) != null ||
            CryptoUtils.generateKeyInKeystore(masterKeyAlias)

    private fun resolveKeyLocked(): SecretKey? {
        if (!ensureMasterKeyLocked()) return null
        return CryptoUtils.getKeyFromKeystore(masterKeyAlias)
    }
}
