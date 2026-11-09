package com.neoproc.neoproccamx.color

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoLogProfilePolicyTest {

    @Test
    fun supportedLogFormats_listsOnlyNeoProCamXOwnedCurves() {
        assertEquals(
            listOf(
                "N-Log Base",
                "N-Log Shadow",
                "N-Log Highlight",
                "N-Log Cinema"
            ),
            NeoLogProfilePolicy.supportedLogFormats()
        )
        val joined = NeoLogProfilePolicy.supportedLogFormats().joinToString(separator = " ")
        listOf("Sony", "ARRI", "Canon", "Panasonic", "RED", "Blackmagic", "DaVinci").forEach { vendor ->
            assertFalse(joined.contains(vendor, ignoreCase = true))
        }
    }

    @Test
    fun canonicalSupportedLogFormat_collapsesAliasesToNeoProCamXNames() {
        assertEquals("N-Log Base", NeoLogProfilePolicy.canonicalSupportedLogFormat("NeoPro N Log"))
        assertEquals("N-Log Shadow", NeoLogProfilePolicy.canonicalSupportedLogFormat("N-Log Shadow"))
        assertEquals("N-Log Highlight", NeoLogProfilePolicy.canonicalSupportedLogFormat("N-Log Highlight"))
        assertEquals("N-Log Cinema", NeoLogProfilePolicy.canonicalSupportedLogFormat("external log alias"))
        assertEquals("N-Log Cinema", NeoLogProfilePolicy.canonicalSupportedLogFormat("external cinema gamut alias"))
        assertEquals("N-Log Base", NeoLogProfilePolicy.effectiveOriginalLogFormat("Natural", logEnabled = true))
        assertEquals(null, NeoLogProfilePolicy.canonicalSupportedLogFormat("Natural"))
        assertEquals(null, NeoLogProfilePolicy.canonicalSupportedLogFormat("Neo Wide Gamut"))
    }

    @Test
    fun naturalProfileWithLogEnabled_usesNLogPolicy() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = false

            val profile = NeoLogProfilePolicy.resolve("Natural", logEnabled = true)

            assertTrue(profile.enabled)
            assertEquals(NeoLogCurve.N_LOG_BASE, profile.curve)
            assertEquals("N-Log Base", profile.label)
            assertEquals(0.82f, profile.bitrateBonus, 0.0001f)
            assertEquals(12, profile.simulatedDynamicRangeBits)
            assertEquals(0f, profile.nativeTenBitDitherFloor, 0.0001f)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun explicitNLogProfile_usesOriginalHighEndTuning() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = false

            val profile = NeoLogProfilePolicy.resolve("N-Log Base", logEnabled = false)

            assertTrue(profile.enabled)
            assertEquals(NeoLogCurve.N_LOG_BASE, profile.curve)
            assertEquals("N-Log Base", profile.label)
            assertTrue(profile.logStrengthFloor >= 0.98f)
            assertTrue(profile.temporalBlendCap >= 0.38f)
            assertTrue(profile.chromaDenoiseCap >= 0.54f)
            assertEquals(HighEndQualityPolicy.highPrecisionLutSize, profile.preferredLutCubeSize)
            assertEquals(12, profile.simulatedDynamicRangeBits)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun legacyNeoAndExternalLogNames_resolveToNeoProCamXCinemaCurve() {
        val flex = NeoLogProfilePolicy.resolve("Neo Flex Curve", logEnabled = true)
        val legacy = NeoLogProfilePolicy.resolve("Neo Legacy Curve", logEnabled = true)
        val film = NeoLogProfilePolicy.resolve("Neo Film Curve", logEnabled = true)
        val external = NeoLogProfilePolicy.resolve("external cinema gamut alias", logEnabled = true)

        listOf(flex, legacy, film, external).forEach { profile ->
            assertEquals(NeoLogCurve.N_LOG_CINEMA, profile.curve)
            assertEquals("N-Log Cinema", profile.label)
        }
    }

    @Test
    fun explicitNLogShadowAndHighlightProfilesUseDistinctHighEndTuning() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = false

            val shadow = NeoLogProfilePolicy.resolve("N-Log Shadow", logEnabled = false)
            val highlight = NeoLogProfilePolicy.resolve("N-Log Highlight", logEnabled = false)

            assertTrue(shadow.enabled)
            assertTrue(highlight.enabled)
            assertEquals(NeoLogCurve.N_LOG_SHADOW, shadow.curve)
            assertEquals(NeoLogCurve.N_LOG_HIGHLIGHT, highlight.curve)
            assertTrue(shadow.shadowBoostFloor > highlight.shadowBoostFloor)
            assertTrue(highlight.highlightCompressionFloor > shadow.highlightCompressionFloor)
            assertEquals(HighEndQualityPolicy.highPrecisionLutSize, shadow.preferredLutCubeSize)
            assertEquals(HighEndQualityPolicy.highPrecisionLutSize, highlight.preferredLutCubeSize)
            assertEquals(12, shadow.simulatedDynamicRangeBits)
            assertEquals(12, highlight.simulatedDynamicRangeBits)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun externalLogAliasProfile_resolvesToNLogCinemaCurve() {
        val profile = NeoLogProfilePolicy.resolve("external log alias", logEnabled = false)

        assertTrue(profile.enabled)
        assertEquals(NeoLogCurve.N_LOG_CINEMA, profile.curve)
        assertEquals("N-Log Cinema", profile.label)
    }

    @Test
    fun nonLogProfileWithoutToggle_disablesLogPolicy() {
        val profile = NeoLogProfilePolicy.resolve("Natural", logEnabled = false)

        assertFalse(profile.enabled)
        assertEquals(NeoLogCurve.NONE, profile.curve)
        assertEquals("OFF", profile.label)
    }

    @Test
    fun equivalentNormalizedProfileRequestsReuseImmutableResolution() {
        val first = NeoLogProfilePolicy.resolve(" N-Log_Cinema ", logEnabled = false)
        val second = NeoLogProfilePolicy.resolve("n log cinema", logEnabled = false)

        assertSame("Equivalent profile names rebuilt immutable policy", first, second)
        assertEquals(first, second)
    }

    @Test
    fun profileResolutionCacheSeparatesStrictPolicyAndReusesEachIdentity() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = false
            val standard = NeoLogProfilePolicy.resolve("N-Log Base", logEnabled = false)

            HighEndQualityPolicy.strictHighEndOnly = true
            val strict = NeoLogProfilePolicy.resolve("N-Log Base", logEnabled = false)

            HighEndQualityPolicy.strictHighEndOnly = false
            val standardAgain = NeoLogProfilePolicy.resolve("N-Log Base", logEnabled = false)

            assertNotSame("Strict and standard policies shared a stale cache entry", standard, strict)
            assertTrue(strict.sharpeningCap > standard.sharpeningCap)
            assertSame("Returning to an unchanged policy rebuilt its resolution", standard, standardAgain)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun concurrentEquivalentProfileRequestsPublishOneResolutionIdentity() {
        val workerCount = 12
        val start = CountDownLatch(1)
        val done = CountDownLatch(workerCount)
        val executor = Executors.newFixedThreadPool(workerCount)
        val results = Collections.synchronizedList(mutableListOf<NeoLogLookProfile>())

        repeat(workerCount) { index ->
            executor.execute {
                try {
                    start.await()
                    val profileName = if (index % 2 == 0) "N-Log Cinema" else " n_log_cinema "
                    results += NeoLogProfilePolicy.resolve(profileName, logEnabled = false)
                } finally {
                    done.countDown()
                }
            }
        }

        start.countDown()
        assertTrue("Concurrent profile requests did not finish", done.await(10, TimeUnit.SECONDS))
        executor.shutdownNow()

        assertEquals(workerCount, results.size)
        val first = results.first()
        results.forEach { profile ->
            assertSame("Concurrent request rebuilt immutable profile resolution", first, profile)
            assertEquals(NeoLogCurve.N_LOG_CINEMA, profile.curve)
        }
    }

    @Test
    fun strictMode_raisesBitrateWithoutRaisingDitherNoise() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = false
            val standard = NeoLogProfilePolicy.resolve("Natural", logEnabled = true)

            HighEndQualityPolicy.strictHighEndOnly = true
            val strict = NeoLogProfilePolicy.resolve("Natural", logEnabled = true)

            assertTrue(strict.bitrateBonus > standard.bitrateBonus)
            assertTrue(strict.nativeTenBitDitherFloor <= standard.nativeTenBitDitherFloor)
            assertTrue(strict.simulatedTenBitDitherFloor <= standard.simulatedTenBitDitherFloor)
            assertTrue(strict.simulatedTenBitDitherFloor <= 0.008f)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun strictMode_raisesLogDetailCapsWithoutBreakingFlatSignal() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = true

            val profile = NeoLogProfilePolicy.resolve("N-Log Cinema", logEnabled = false)

            assertTrue("sharpeningCap=${profile.sharpeningCap}", profile.sharpeningCap >= 0.16f)
            assertTrue("spatialDenoiseCap=${profile.spatialDenoiseCap}", profile.spatialDenoiseCap >= 0.52f)
            assertTrue("chromaDenoiseCap=${profile.chromaDenoiseCap}", profile.chromaDenoiseCap >= 0.62f)
            assertTrue("temporalBlendCap=${profile.temporalBlendCap}", profile.temporalBlendCap >= 0.44f)
            assertTrue("contrastCap=${profile.contrastCap}", profile.contrastCap <= 0.92f)
            assertTrue("saturationCap=${profile.saturationCap}", profile.saturationCap <= 0.88f)
            assertEquals(12, profile.simulatedDynamicRangeBits)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun preferredLutCubeSize_keepsExternalAliasesAtNLogCinemaPrecision() {
        assertEquals(
            HighEndQualityPolicy.highPrecisionLutSize,
            NeoLogProfilePolicy.preferredLutCubeSize("external log c4 alias", logEnabled = true)
        )
    }

    @Test
    fun nativeTenBitHardwareSupport_bypassesFallbackDitherPath() {
        val path = NeoLogProfilePolicy.resolveTenBitRenderPath(
            profileName = "external log c4 alias",
            logEnabled = true,
            hardwareSupport = NeoTenBitHardwareSupport(hdr10Supported = true),
            tenBitRequested = true
        )

        assertTrue(path.useNativeHardwareBuffer)
        assertEquals(NeoTenBitDitherMode.NONE, path.ditherMode)
        assertEquals(
            NeoLogProfilePolicy.resolve("external log c4 alias", logEnabled = true).nativeTenBitDitherFloor,
            path.ditherFloor,
            0.0001f
        )
    }

    @Test
    fun neoLogCurveEnumDoesNotExposeExternalVendorCurves() {
        val names = NeoLogCurve.entries.joinToString(separator = " ") { it.name }

        listOf("ARRI", "SONY", "CANON", "PANASONIC", "RED", "BLACKMAGIC", "DAVINCI").forEach { token ->
            assertFalse(names.contains(token, ignoreCase = true))
        }
    }

    @Test
    fun missingTenBitHardware_usesDynamicBlueNoiseTemporalFallback() {
        val path = NeoLogProfilePolicy.resolveTenBitRenderPath(
            profileName = "Natural",
            logEnabled = true,
            hardwareSupport = NeoTenBitHardwareSupport.none(),
            tenBitRequested = true
        )

        assertFalse(path.useNativeHardwareBuffer)
        assertEquals(NeoTenBitDitherMode.DYNAMIC_BLUE_NOISE_TEMPORAL, path.ditherMode)
        assertTrue(
            path.ditherFloor >=
                NeoLogProfilePolicy.resolve("Natural", logEnabled = true).simulatedTenBitDitherFloor
        )
    }
}
