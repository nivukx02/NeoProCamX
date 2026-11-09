package com.neoproc.neoproccamx.security

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureSecretVaultSourceContractTest {

    @Test
    fun `keystore key and authenticated preference envelope share one process transaction`() {
        val source = repoFile(
            "app/src/main/java/com/neoproc/neoproccamx/security/SecureSecretVault.kt"
        ).readText()

        assertTrue(source.contains("private val PROCESS_WIDE_VAULT_KEY_LOCK = Any()"))
        assertTrue(
            source.contains(
                "fun ensureMasterKey(): Boolean = synchronized(PROCESS_WIDE_VAULT_KEY_LOCK)"
            )
        )
        assertTrue(
            source.contains(
                "fun getOrCreateInstallationSecret(): String = " +
                    "synchronized(PROCESS_WIDE_VAULT_KEY_LOCK)"
            )
        )

        val createTransaction = source
            .substringAfter("fun getOrCreateInstallationSecret(): String")
            .substringBefore("// Security-critical exception")
        assertTrue(createTransaction.contains("getStringLocked(INSTALLATION_SECRET_KEY)"))
        assertTrue(createTransaction.contains("putAuthenticatedBytesLocked("))
        assertTrue(
            createTransaction.contains(
                "return@synchronized checkNotNull(getStringLocked(INSTALLATION_SECRET_KEY))"
            )
        )
    }

    @Test
    fun `instrumentation vault cleanup is isolated from the application namespace`() {
        val source = repoFile(
            "app/src/androidTest/java/com/neoproc/neoproccamx/security/" +
                "SecureSecretVaultInstrumentedTest.kt"
        ).readText()

        assertTrue(source.contains("isolatedPreferencesName = TEST_PREFS_NAME"))
        assertTrue(source.contains("isolatedMasterKeyAlias = TEST_MASTER_KEY_ALIAS"))
        assertTrue(source.contains("camx_secure_vault_instrumentation"))
        assertTrue(source.contains("camx_master_key_v1_instrumentation"))
        assertFalse(source.contains("val vault = SecureSecretVault(context)"))

        val cleanup = source.substringAfter("private fun clearVaultState()")
            .substringBefore("private companion object")
        assertTrue(cleanup.contains("getSharedPreferences(TEST_PREFS_NAME"))
        assertTrue(
            cleanup.contains(
                "assertTrue(CryptoUtils.deleteKeyFromKeystore(TEST_MASTER_KEY_ALIAS))"
            )
        )
        assertTrue(
            cleanup.contains(
                "assertNull(CryptoUtils.getKeyFromKeystore(TEST_MASTER_KEY_ALIAS))"
            )
        )
    }

    @Test
    fun `tamper fixture always changes a valid payload character`() {
        val source = repoFile(
            "app/src/androidTest/java/com/neoproc/neoproccamx/security/" +
                "SecureSecretVaultInstrumentedTest.kt"
        ).readText()
        val mutation = source.substringAfter("private fun mutateAuthenticatedEnvelope(")
            .substringBefore("private fun clearVaultState()")

        assertTrue(mutation.contains("AUTHENTICATED_ENVELOPE_PREFIX.length"))
        assertTrue(mutation.contains("if (stored[payloadIndex] == 'A') 'B' else 'A'"))
        assertTrue(mutation.contains("replaceRange(payloadIndex, payloadIndex + 1"))
        assertTrue(source.contains("assertNotEquals(stored, tampered)"))
        assertTrue(
            source.contains(
                "assertTrue(prefs.edit().putString(\"primary_secret\", tampered).commit())"
            )
        )
        assertFalse(source.contains("stored.dropLast(1) + \"A\""))
    }

    private fun repoFile(relativePath: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            File(directory, relativePath).takeIf(File::exists)?.let { return it }
            directory = directory.parentFile ?: return File(relativePath)
        }
        return File(relativePath)
    }
}
