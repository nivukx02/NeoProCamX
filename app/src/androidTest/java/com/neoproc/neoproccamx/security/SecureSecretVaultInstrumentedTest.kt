package com.neoproc.neoproccamx.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neoproc.neoproccamx.utils.CryptoUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SecureSecretVaultInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        clearVaultState()
    }

    @After
    fun tearDown() {
        clearVaultState()
    }

    @Test
    fun installationSecretCanBeCreatedWithAndroidKeystoreBackedGcmKey() {
        val vault = isolatedVault()

        val generated = vault.getOrCreateInstallationSecret()
        val persisted = vault.getOrCreateInstallationSecret()

        assertTrue(generated.isNotBlank())
        assertEquals(generated, persisted)
    }

    @Test
    fun ciphertextIsBoundToItsVaultEntryAndRejectsTampering() {
        val vault = isolatedVault()
        assertTrue(vault.putString("primary_secret", "camera-private-value"))

        val prefs = context.getSharedPreferences(TEST_PREFS_NAME, Context.MODE_PRIVATE)
        val stored = requireNotNull(prefs.getString("primary_secret", null))
        assertTrue(stored.startsWith("gcm2:"))

        assertTrue(prefs.edit().putString("copied_secret", stored).commit())
        assertNull(vault.getString("copied_secret"))

        // Mutate a Base64 payload character deterministically. Replacing the final character with
        // a fixed value is flaky when that character is already the replacement (and the final
        // character may also be padding), leaving the authenticated envelope unchanged.
        val tampered = mutateAuthenticatedEnvelope(stored)
        assertNotEquals(stored, tampered)
        assertTrue(prefs.edit().putString("primary_secret", tampered).commit())
        assertNull(vault.getString("primary_secret"))
    }

    @Test
    fun byteValuesRoundTripWithoutUnauthenticatedInnerStorage() {
        val vault = isolatedVault()
        val expected = ByteArray(64) { index -> (index * 3).toByte() }

        assertTrue(vault.putBytes("binary_secret", expected))

        assertTrue(expected.contentEquals(vault.getBytes("binary_secret")))
    }

    @Test
    fun legacyGcmValueMigratesToEntryBoundEnvelopeAfterSuccessfulRead() {
        val vault = isolatedVault()
        assertTrue(vault.ensureMasterKey())
        val key = requireNotNull(CryptoUtils.getKeyFromKeystore(TEST_MASTER_KEY_ALIAS))
        val legacy = CryptoUtils.encryptString("legacy-value", key)
        val prefs = context.getSharedPreferences(TEST_PREFS_NAME, Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("legacy_secret", legacy).commit())

        assertEquals("legacy-value", vault.getString("legacy_secret"))
        assertTrue(requireNotNull(prefs.getString("legacy_secret", null)).startsWith("gcm2:"))
    }

    @Test
    fun concurrentVaultInstancesCreateOneStableInstallationSecret() {
        val workerCount = 12
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(6)
        try {
            val futures = List(workerCount) {
                executor.submit<String> {
                    start.await(10, TimeUnit.SECONDS)
                    isolatedVault().getOrCreateInstallationSecret()
                }
            }
            start.countDown()
            val generated = futures.map { it.get(30, TimeUnit.SECONDS) }

            assertEquals(workerCount, generated.size)
            assertEquals(1, generated.toSet().size)
            assertEquals(generated.first(), isolatedVault().getOrCreateInstallationSecret())
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun isolatedVault(): SecureSecretVault = SecureSecretVault(
        context = context,
        isolatedPreferencesName = TEST_PREFS_NAME,
        isolatedMasterKeyAlias = TEST_MASTER_KEY_ALIAS
    )

    private fun mutateAuthenticatedEnvelope(stored: String): String {
        require(stored.startsWith(AUTHENTICATED_ENVELOPE_PREFIX))
        val payloadIndex = AUTHENTICATED_ENVELOPE_PREFIX.length
        require(stored.length > payloadIndex)
        val replacement = if (stored[payloadIndex] == 'A') 'B' else 'A'
        return stored.replaceRange(payloadIndex, payloadIndex + 1, replacement.toString())
    }

    private fun clearVaultState() {
        assertTrue(
            context.getSharedPreferences(TEST_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        )
        assertTrue(CryptoUtils.deleteKeyFromKeystore(TEST_MASTER_KEY_ALIAS))
        assertNull(CryptoUtils.getKeyFromKeystore(TEST_MASTER_KEY_ALIAS))
    }

    private companion object {
        private const val AUTHENTICATED_ENVELOPE_PREFIX = "gcm2:"
        private const val TEST_PREFS_NAME = "camx_secure_vault_instrumentation"
        private const val TEST_MASTER_KEY_ALIAS = "camx_master_key_v1_instrumentation"
    }
}
