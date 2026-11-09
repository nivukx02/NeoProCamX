package com.neoproc.neoproccamx.bridge

import android.Manifest
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class CamxDesktopBridgeProviderInstrumentedTest {
    @Test
    fun authoritativeCatalogStreamsOnlyTheOwnedPublishedArtifactWithVerifiedDigest() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val payload = "CamX desktop bridge integration fixture".toByteArray()
        val name = "CAMX_BRIDGE_${System.currentTimeMillis()}.mov"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/quicktime")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/NeoProCamX/BridgeTest")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val mediaUri = requireNotNull(
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        )
        try {
            resolver.openOutputStream(mediaUri, "w")!!.use { it.write(payload) }
            assertEquals(
                1,
                resolver.update(
                    mediaUri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
            )
            instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.DUMP)

            val catalogText = resolver.openInputStream(
                Uri.parse("content://${CamxDesktopBridgeContract.AUTHORITY}/catalog")
            )!!.bufferedReader().use { it.readText() }
            val catalog = JSONObject(catalogText)
            assertEquals(CamxDesktopBridgeContract.SCHEMA, catalog.getString("schema"))
            assertEquals(CamxDesktopBridgeContract.AUTHENTICATION, catalog.getString("authentication"))
            val item = (0 until catalog.getJSONArray("items").length())
                .map { catalog.getJSONArray("items").getJSONObject(it) }
                .firstOrNull { it.getString("name") == name }
            assertNotNull(item)

            val streamed = resolver.openInputStream(Uri.parse(item!!.getString("contentUri")))!!.use { it.readBytes() }
            assertArrayEquals(payload, streamed)
            val digest = JSONObject(
                resolver.openInputStream(Uri.parse(item.getString("digestUri")))!!
                    .bufferedReader().use { it.readText() }
            )
            assertEquals(payload.size.toLong(), digest.getLong("bytes"))
            assertEquals(sha256(payload), digest.getString("sha256"))
            assertTrue(catalog.getString("signingCertificateSha256").matches(Regex("^[a-f0-9]{64}$")))
            val repeatedCatalog = JSONObject(
                resolver.openInputStream(
                    Uri.parse("content://${CamxDesktopBridgeContract.AUTHORITY}/catalog")
                )!!.bufferedReader().use { it.readText() }
            )
            assertEquals(catalog.getString("deviceIdSha256"), repeatedCatalog.getString("deviceIdSha256"))
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            resolver.delete(mediaUri, null, null)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
}
