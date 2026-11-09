package com.neoproc.neoproccamx.utils

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.os.Build
import android.util.Base64
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Arrays

/**
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 * CRYPTO UTILS — ENTERPRISE-GRADE CRYPTOGRAPHIC OPERATIONS AND SECURITY UTILITIES
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Comprehensive cryptographic utility providing:
 *   - AES-256 encryption with GCM mode for authenticated encryption
 *   - RSA key pair generation and management
 *   - HMAC-SHA256 for message authentication
 *   - SHA-256/SHA-512 hashing with salt support
 *   - Secure random number generation
 *   - Key derivation functions (PBKDF2)
 *   - Certificate handling and validation
 *   - Secure memory wiping for sensitive data
 *
 * Security: All operations follow OWASP and NIST recommendations.
 * Performance: Optimized algorithms with hardware acceleration support.
 *
 * @author NeoProCamX Security Team
 * @version 10.0.0-ENTERPRISE
 * @since 1.0.0
 */
object CryptoUtils {

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // CRYPTOGRAPHIC CONSTANTS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AES_ALGORITHM = "AES"
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val SHA256_ALGORITHM = "SHA-256"
    private const val SHA512_ALGORITHM = "SHA-512"
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"

    /** AES key size in bits. */
    private const val AES_KEY_SIZE = 256

    /** AES GCM tag length in bits. */
    private const val GCM_TAG_LENGTH = 128

    /** AES GCM IV length in bytes. */
    private const val GCM_IV_LENGTH = 12

    /** PBKDF2-HMAC-SHA256 work factor for password-derived compatibility keys. */
    private const val PBKDF2_ITERATIONS = 600_000

    private const val MIN_PBKDF2_ITERATIONS = 210_000

    /** Salt length in bytes. */
    private const val SALT_LENGTH = 32

    /** Secure random instance. */
    private val secureRandom = SecureRandom()

    private val validKeystoreAlias = Regex("[A-Za-z0-9._-]{1,128}")

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // KEY MANAGEMENT
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Generate a new AES-256 key in the Android Keystore.
     *
     * @param alias The key alias in the keystore
     * @return true if key generation succeeded
     */
    fun generateKeyInKeystore(alias: String): Boolean {
        requireValidKeystoreAlias(alias)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                generateAndroidKeystoreKey(alias, strongBoxBacked = true)
                NeoLogger.d("CryptoUtils", "Generated hardware-isolated keystore key")
                return true
            } catch (_: StrongBoxUnavailableException) {
                NeoLogger.i("CryptoUtils", "StrongBox unavailable; using Android Keystore fallback")
            } catch (e: Exception) {
                // Some OEMs advertise StrongBox but reject individual AES parameters. The regular
                // Android Keystore path remains hardware-backed where the device supports it.
                NeoLogger.w("CryptoUtils", "StrongBox key generation unavailable; using secure fallback", e)
            }
        }

        return runCatching {
            generateAndroidKeystoreKey(alias, strongBoxBacked = false)
            NeoLogger.d("CryptoUtils", "Generated Android Keystore key")
        }.onFailure { error ->
            NeoLogger.e("CryptoUtils", "Failed to generate Android Keystore key", error)
        }.isSuccess
    }

    private fun generateAndroidKeystoreKey(alias: String, strongBoxBacked: Boolean) {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_SIZE)
            .setUserAuthenticationRequired(false)
            .setRandomizedEncryptionRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && strongBoxBacked) {
            builder.setIsStrongBoxBacked(true)
        }
        keyGenerator.init(builder.build())
        keyGenerator.generateKey()
    }

    /**
     * Retrieve a key from the Android Keystore.
     *
     * @param alias The key alias
     * @return The secret key or null if not found
     */
    fun getKeyFromKeystore(alias: String): SecretKey? {
        requireValidKeystoreAlias(alias)
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            keyStore.getKey(alias, null) as? SecretKey
        } catch (e: Exception) {
            NeoLogger.e("CryptoUtils", "Failed to retrieve Android Keystore key", e)
            null
        }
    }

    /**
     * Delete a key from the Android Keystore.
     *
     * @param alias The key alias to delete
     * @return true if deletion succeeded
     */
    fun deleteKeyFromKeystore(alias: String): Boolean {
        requireValidKeystoreAlias(alias)
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            keyStore.deleteEntry(alias)
            NeoLogger.d("CryptoUtils", "Deleted Android Keystore key")
            true
        } catch (e: Exception) {
            NeoLogger.e("CryptoUtils", "Failed to delete Android Keystore key", e)
            false
        }
    }

    /**
     * Generate a random AES key (not stored in keystore).
     *
     * @return A new random AES-256 key
     */
    fun generateRandomKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(AES_ALGORITHM)
        keyGenerator.init(AES_KEY_SIZE, secureRandom)
        return keyGenerator.generateKey()
    }

    /**
     * Derive a key from a password using PBKDF2.
     *
     * @param password The password to derive from
     * @param salt The salt (will generate if null)
     * @param iterations The iteration count
     * @return Pair of (derived key, salt used)
     */
    fun deriveKeyFromPassword(
        password: String,
        salt: ByteArray? = null,
        iterations: Int = PBKDF2_ITERATIONS
    ): Pair<SecretKey, ByteArray> {
        require(password.isNotEmpty()) { "Password must not be empty" }
        require(iterations >= MIN_PBKDF2_ITERATIONS) {
            "PBKDF2 iteration count is below the security minimum"
        }
        val actualSalt = salt ?: generateSalt()
        require(actualSalt.size >= 16) { "PBKDF2 salt must contain at least 16 bytes" }

        val passwordChars = password.toCharArray()
        val spec = javax.crypto.spec.PBEKeySpec(passwordChars, actualSalt, iterations, AES_KEY_SIZE)
        return try {
            val factory = javax.crypto.SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
            val encoded = factory.generateSecret(spec).encoded
            try {
                Pair(SecretKeySpec(encoded, AES_ALGORITHM), actualSalt)
            } finally {
                secureWipe(encoded)
            }
        } finally {
            spec.clearPassword()
            Arrays.fill(passwordChars, '\u0000')
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // ENCRYPTION AND DECRYPTION
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Encrypt data using AES-256 GCM mode.
     *
     * @param plaintext The data to encrypt
     * @param key The encryption key
     * @param associatedData Additional authenticated data (optional)
     * @return Encrypted data with IV prepended
     */
    fun encryptAesGcm(
        plaintext: ByteArray,
        key: SecretKey,
        associatedData: ByteArray? = null
    ): ByteArray {
        requireAesKey(key)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
            ?: throw IllegalStateException("AES-GCM provider did not return an IV")
        check(iv.size == GCM_IV_LENGTH) { "AES-GCM provider returned a non-standard IV length" }

        associatedData?.let { cipher.updateAAD(it) }

        val ciphertext = cipher.doFinal(plaintext)

        // Prepend IV to ciphertext
        return ByteBuffer.allocate(iv.size + ciphertext.size)
            .put(iv)
            .put(ciphertext)
            .array()
    }

    /**
     * Decrypt data using AES-256 GCM mode.
     *
     * @param encryptedData The encrypted data (IV + ciphertext)
     * @param key The decryption key
     * @param associatedData Additional authenticated data (optional)
     * @return The decrypted plaintext
     */
    fun decryptAesGcm(
        encryptedData: ByteArray,
        key: SecretKey,
        associatedData: ByteArray? = null
    ): ByteArray {
        requireAesKey(key)
        require(encryptedData.size >= GCM_IV_LENGTH + GCM_TAG_LENGTH / 8) {
            "AES-GCM payload is truncated"
        }
        val buffer = ByteBuffer.wrap(encryptedData)

        val iv = ByteArray(GCM_IV_LENGTH)
        buffer.get(iv)

        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec)

        associatedData?.let { cipher.updateAAD(it) }

        return cipher.doFinal(ciphertext)
    }

    /**
     * Encrypt a string using AES-256 GCM.
     *
     * @param plaintext The string to encrypt
     * @param key The encryption key
     * @return Base64-encoded encrypted data
     */
    fun encryptString(plaintext: String, key: SecretKey): String {
        val plaintextBytes = plaintext.toByteArray(StandardCharsets.UTF_8)
        return try {
            val encrypted = encryptAesGcm(plaintextBytes, key)
            try {
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            } finally {
                secureWipe(encrypted)
            }
        } finally {
            secureWipe(plaintextBytes)
        }
    }

    /**
     * Decrypt a string using AES-256 GCM.
     *
     * @param ciphertext The Base64-encoded encrypted string
     * @param key The decryption key
     * @return The decrypted string
     */
    fun decryptString(ciphertext: String, key: SecretKey): String {
        val encrypted = Base64.decode(ciphertext, Base64.NO_WRAP)
        return try {
            val decrypted = decryptAesGcm(encrypted, key)
            try {
                String(decrypted, StandardCharsets.UTF_8)
            } finally {
                secureWipe(decrypted)
            }
        } finally {
            secureWipe(encrypted)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // HASHING
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Compute SHA-256 hash.
     *
     * @param data The data to hash
     * @return The SHA-256 hash as a hex string
     */
    fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance(SHA256_ALGORITHM)
        val hash = digest.digest(data)
        return bytesToHex(hash)
    }

    /**
     * Compute SHA-256 hash of a string.
     *
     * @param input The string to hash
     * @return The SHA-256 hash as a hex string
     */
    fun sha256(input: String): String {
        return sha256(input.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Compute SHA-512 hash.
     *
     * @param data The data to hash
     * @return The SHA-512 hash as a hex string
     */
    fun sha512(data: ByteArray): String {
        val digest = MessageDigest.getInstance(SHA512_ALGORITHM)
        val hash = digest.digest(data)
        return bytesToHex(hash)
    }

    /**
     * Compute salted SHA-256 hash.
     *
     * @param data The data to hash
     * @param salt The salt
     * @return The salted hash as a hex string
     */
    fun sha256WithSalt(data: ByteArray, salt: ByteArray): String {
        val digest = MessageDigest.getInstance(SHA256_ALGORITHM)
        digest.update(salt)
        val hash = digest.digest(data)
        return bytesToHex(hash)
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // HMAC
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Compute HMAC-SHA256.
     *
     * @param data The data to authenticate
     * @param key The HMAC key
     * @return The HMAC as a hex string
     */
    fun hmacSha256(data: ByteArray, key: SecretKey): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(key)
        val hmac = mac.doFinal(data)
        return bytesToHex(hmac)
    }

    /**
     * Verify HMAC-SHA256.
     *
     * @param data The data to verify
     * @param key The HMAC key
     * @param expectedHmac The expected HMAC value
     * @return true if the HMAC is valid
     */
    fun verifyHmacSha256(data: ByteArray, key: SecretKey, expectedHmac: String): Boolean {
        val computedHmac = hmacSha256(data, key)
        return constantTimeEquals(computedHmac, expectedHmac)
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // RANDOM GENERATION
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Generate a random salt.
     *
     * @param length The salt length in bytes
     * @return The generated salt
     */
    fun generateSalt(length: Int = SALT_LENGTH): ByteArray {
        require(length >= 16) { "Salt must contain at least 16 bytes" }
        val salt = ByteArray(length)
        secureRandom.nextBytes(salt)
        return salt
    }

    /**
     * Generate a random IV.
     *
     * @param length The IV length in bytes
     * @return The generated IV
     */
    fun generateIv(length: Int = GCM_IV_LENGTH): ByteArray {
        require(length > 0) { "IV length must be positive" }
        val iv = ByteArray(length)
        secureRandom.nextBytes(iv)
        return iv
    }

    /**
     * Generate a secure random integer.
     *
     * @param bound The upper bound (exclusive)
     * @return A random integer in the range [0, bound)
     */
    fun secureRandomInt(bound: Int): Int {
        require(bound > 0) { "Random bound must be positive" }
        return secureRandom.nextInt(bound)
    }

    /**
     * Generate a secure random long.
     *
     * @return A random long
     */
    fun secureRandomLong(): Long {
        return secureRandom.nextLong()
    }

    /**
     * Generate secure random bytes.
     *
     * @param length The number of bytes to generate
     * @return The generated random bytes
     */
    fun secureRandomBytes(length: Int): ByteArray {
        require(length > 0) { "Random byte length must be positive" }
        val bytes = ByteArray(length)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    /**
     * Generate a secure random token.
     *
     * @param length The token length in bytes
     * @return A URL-safe Base64-encoded token
     */
    fun generateSecureToken(length: Int = 32): String {
        require(length >= 16) { "Security token must contain at least 16 random bytes" }
        val bytes = secureRandomBytes(length)
        return try {
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } finally {
            secureWipe(bytes)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // UTILITY FUNCTIONS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Convert bytes to hexadecimal string.
     *
     * @param bytes The bytes to convert
     * @return The hex string
     */
    fun bytesToHex(bytes: ByteArray): String {
        val hexArray = "0123456789ABCDEF".toCharArray()
        val hexChars = CharArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and 0xFF
            hexChars[j * 2] = hexArray[v ushr 4]
            hexChars[j * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }

    /**
     * Convert hexadecimal string to bytes.
     *
     * @param hex The hex string
     * @return The bytes
     */
    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex input must contain an even number of characters" }
        require(hex.all { it.digitToIntOrNull(16) != null }) { "Hex input contains invalid characters" }
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                    Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    /**
     * Constant-time comparison to prevent timing attacks.
     *
     * @param a First string
     * @param b Second string
     * @return true if strings are equal
     */
    fun constantTimeEquals(a: String, b: String): Boolean {
        val aBytes = a.toByteArray(StandardCharsets.UTF_8)
        val bBytes = b.toByteArray(StandardCharsets.UTF_8)
        return try {
            MessageDigest.isEqual(aBytes, bBytes)
        } finally {
            secureWipe(aBytes)
            secureWipe(bBytes)
        }
    }

    /**
     * Constant-time comparison for byte arrays.
     *
     * @param a First array
     * @param b Second array
     * @return true if arrays are equal
     */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        return MessageDigest.isEqual(a, b)
    }

    /**
     * Securely wipe a byte array.
     *
     * @param data The data to wipe
     */
    fun secureWipe(data: ByteArray) {
        secureRandom.nextBytes(data)
        Arrays.fill(data, 0.toByte())
    }

    private fun requireValidKeystoreAlias(alias: String) {
        require(validKeystoreAlias.matches(alias)) { "Invalid Android Keystore alias" }
    }

    private fun requireAesKey(key: SecretKey) {
        require(key.algorithm.equals(AES_ALGORITHM, ignoreCase = true)) {
            "AES-GCM requires an AES key"
        }
    }
}
