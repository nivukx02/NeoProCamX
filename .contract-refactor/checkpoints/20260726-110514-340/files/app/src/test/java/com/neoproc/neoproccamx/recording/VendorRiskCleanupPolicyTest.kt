package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.camera.HdrDynamicRangeMode
import com.neoproc.neoproccamx.camera.HdrDynamicRangePolicy
import com.neoproc.neoproccamx.camera.HdrSurfaceRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VendorRiskCleanupPolicyTest {

    @Test
    fun legacyHdrInputsNormalizeToNeoProfilesAndVendorInputsAreRejected() {
        listOf("HDR", "HLG", "HDR10", "HDR10+", "HDR10PLUS").forEach { legacy ->
            assertEquals(legacy, "NDR10+", RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(legacy))
        }
        listOf("Dolby Vision", "DOLBYVISION", "DV", "Dolby").forEach { legacy ->
            assertEquals(legacy, "OFF", RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(legacy))
        }
    }

    @Test
    fun legacyHdrInputsResolveToNeoLabelsAndVendorInputsStaySdr() {
        val legacyHdr = RecordingHdrVideoLookPolicy.resolveLookProfile(
            mime = "video/hevc",
            videoHdrMode = "HDR10+",
            videoColorSpace = "Rec.2020",
            sdkInt = 35
        )
        val legacyDolby = RecordingHdrVideoLookPolicy.resolveLookProfile(
            mime = "video/hevc",
            videoHdrMode = "Dolby Vision",
            videoColorSpace = "Rec.2020",
            sdkInt = 35
        )

        assertEquals("NDR10+ BT.2100 PQ", legacyHdr.requestedDynamicRangeLabel)
        assertEquals("Wide Gamut SDR (Rec.2020)", legacyDolby.requestedDynamicRangeLabel)
        listOf(legacyHdr.requestedDynamicRangeLabel, legacyDolby.requestedDynamicRangeLabel).forEach { label ->
            assertFalse(label.contains("HLG", ignoreCase = true))
            assertFalse(label.contains("Dolby", ignoreCase = true))
        }
    }

    @Test
    fun productPolicyFilesDoNotAdvertiseVendorHdrModes() {
        val productFiles = listOf(
            "app/src/main/java/com/neoproc/neoproccamx/recording/RecordingHdrVideoLookPolicy.kt",
            "app/src/main/java/com/neoproc/neoproccamx/color/VideoProfileIntentPolicy.kt",
            "app/src/main/java/com/neoproc/neoproccamx/ui/controls/CinemaPreset.kt",
            "app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt",
            "app/src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt",
            "app/src/main/java/com/neoproc/neoproccamx/ui/VideoFormatDeliveryPolicy.kt",
            "app/src/main/java/com/neoproc/neoproccamx/ui/VideoModePipelineContractPolicy.kt",
            "app/src/main/java/com/neoproc/neoproccamx/ui/VideoHdrRecordingTransportPolicy.kt"
        )
        val forbidden = listOf(
            "Dolby Vision",
            "DOLBYVISION",
            "HDR10+",
            "HDR10PLUS",
            "\"HDR10\"",
            "\"HLG\"",
            "HDR10 Native",
            "HLG Native"
        )

        productFiles.forEach { path ->
            val text = locate(path).readText()
            forbidden.forEach { token ->
                assertFalse("$path must not contain product/vendor token $token", text.contains(token))
            }
        }
    }

    @Test
    fun encoderFormatRequestNeverAsksForCertifiedVendorMetadataForNeoProfiles() {
        val text = locate("app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt").readText()

        assertTrue(text.contains("hdr10PlusRequested = false"))
        assertTrue(text.contains("certifiedVendorFallbackRequested = false"))
        assertFalse(text.contains("hdr10PlusRequested = normalizedRecordingHdrMode"))
        assertFalse(text.contains("certifiedVendorFallbackRequested = normalizedRecordingHdrMode"))
    }

    @Test
    fun productionRecordingCodeDoesNotInjectUserRegisteredT35DynamicHdrSei() {
        val productionFiles = locate("app/src/main/java/com/neoproc/neoproccamx")
            .walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java", "cpp", "h") }
            .filterNot { it.name == "HevcColorMetadataRewriter.kt" }
            .toList()

        productionFiles.forEach { file ->
            val text = file.readText()
            assertFalse(
                "${file.path} must not call official-looking ITU-T T.35 HDR SEI injection",
                text.contains("injectUserRegisteredItutT35Sei(") ||
                    text.contains("buildUserRegisteredItutT35PrefixSeiNal(")
            )
        }
    }

    @Test
    fun cameraDynamicRangePolicyDoesNotSelectCertifiedVendorProfilesForNeoModes() {
        val vendorOnlySupport = HdrDynamicRangePolicy.buildSupport(
            setOf(
                HdrDynamicRangePolicy.PROFILE_STANDARD,
                HdrDynamicRangePolicy.PROFILE_DOLBY_VISION_10B_HDR_REF,
                HdrDynamicRangePolicy.PROFILE_DOLBY_VISION_10B_HDR_OEM
            )
        )
        val mixedSupport = HdrDynamicRangePolicy.Support(
            standardOnly = false,
            hlg10Supported = true,
            hdr10Supported = true,
            hdr10PlusSupported = true,
            certifiedVendorDynamicRangeProfile = HdrDynamicRangePolicy.PROFILE_DOLBY_VISION_10B_HDR_OEM
        )

        assertFalse(vendorOnlySupport.anyHdrSupported)
        assertNull(vendorOnlySupport.certifiedVendorDynamicRangeProfile)
        assertEquals(
            HdrDynamicRangePolicy.PROFILE_HDR10_PLUS,
            HdrDynamicRangePolicy.resolveProfilePure(
                HdrDynamicRangeMode.HDR10_PLUS,
                mixedSupport,
                HdrSurfaceRole.ENCODER
            )
        )
        assertFalse(
            HdrDynamicRangePolicy.resolveProfilePure(
                HdrDynamicRangeMode.HDR10_PLUS,
                mixedSupport,
                HdrSurfaceRole.ENCODER
            ) == HdrDynamicRangePolicy.PROFILE_DOLBY_VISION_10B_HDR_OEM
        )
    }

    private fun locate(path: String): File {
        val rootPath = File(path)
        if (rootPath.isFile) return rootPath
        val modulePath = File(path.removePrefix("app/"))
        return modulePath.takeIf { it.isFile } ?: rootPath
    }
}
