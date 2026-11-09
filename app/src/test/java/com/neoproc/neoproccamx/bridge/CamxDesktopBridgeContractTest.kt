package com.neoproc.neoproccamx.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CamxDesktopBridgeContractTest {
    @Test
    fun `artifact ids are stable opaque sha256 identities`() {
        val first = CamxDesktopBridgeContract.stableArtifactId("private", "/data/user/0/app/CamX/a.npv10")
        val repeated = CamxDesktopBridgeContract.stableArtifactId("private", "/data/user/0/app/CamX/a.npv10")
        val different = CamxDesktopBridgeContract.stableArtifactId("mediastore", "content://media/1")

        assertEquals(first, repeated)
        assertNotEquals(first, different)
        assertTrue(first.matches(Regex("^[a-f0-9]{64}$")))
        assertFalse(first.contains("CamX"))
    }

    @Test
    fun `catalog admits every CamX video carrier but rejects unrelated assets`() {
        listOf(
            "CAMX.mov", "CAMX.mp4", "CAMX.mkv", "CAMX.mxf", "CAMX.avi", "CAMX.webm",
            "CAMX.m4v", "CAMX.hevc", "CAMX.npv10", "stream.npraw", "frame.dng", "clip.raw",
            "CAMX.nivukx"
        ).forEach { name -> assertTrue(name, CamxDesktopBridgeContract.isSupportedMediaName(name)) }

        assertFalse(CamxDesktopBridgeContract.isSupportedMediaName("photo.jpg"))
        assertFalse(CamxDesktopBridgeContract.isSupportedMediaName("catalog.json"))
        assertEquals("raw", CamxDesktopBridgeContract.categoryFor("clip.npv10"))
        assertEquals("sdr", CamxDesktopBridgeContract.categoryFor("clip.nivukx"))
    }

    @Test
    fun `catalog fingerprint is order independent and binds companions`() {
        val media = artifact("media", "CAMX.mov", 100L)
        val metadata = artifact("metadata", "CAMX.mov.metadata.json", 20L, "camera-metadata")
        val item = CamxBridgeMediaDescriptor(media, "sdr", "camx-owned-mediastore", listOf(metadata))
        val other = CamxBridgeMediaDescriptor(artifact("raw", "stream.npraw", 200L), "raw", "camx-owned-mediastore", emptyList())

        assertEquals(
            CamxDesktopBridgeContract.catalogFingerprint(listOf(item, other)),
            CamxDesktopBridgeContract.catalogFingerprint(listOf(other, item))
        )
        assertNotEquals(
            CamxDesktopBridgeContract.catalogFingerprint(listOf(item)),
            CamxDesktopBridgeContract.catalogFingerprint(listOf(item.copy(companions = emptyList())))
        )
    }

    @Test
    fun `wire json escapes names and carries only opaque content routes`() {
        val media = artifact("quoted", "CAMX_\"line\n.mov", 42L)
        val json = CamxDesktopBridgeContract.buildCatalogJson(
            packageName = "com.neoproc.neoproccamx",
            versionName = "test",
            versionCode = 1L,
            signingCertificateSha256 = "a".repeat(64),
            deviceIdSha256 = "b".repeat(64),
            generatedAtEpochMs = 123L,
            items = listOf(CamxBridgeMediaDescriptor(media, "sdr", "camx-owned-mediastore", emptyList()))
        )

        assertTrue(json.contains("CAMX_\\\"line\\n.mov"))
        assertTrue(json.contains(CamxDesktopBridgeContract.artifactUri(media.id)))
        assertFalse(json.contains("/data/user/"))
    }

    private fun artifact(identity: String, name: String, bytes: Long, role: String = "media") =
        CamxBridgeArtifactDescriptor(
            id = CamxDesktopBridgeContract.stableArtifactId("test", identity),
            name = name,
            extension = CamxDesktopBridgeContract.normalizedExtension(name),
            bytes = bytes,
            modifiedAtEpochMs = 10L,
            mimeType = "application/octet-stream",
            role = role
        )
}
