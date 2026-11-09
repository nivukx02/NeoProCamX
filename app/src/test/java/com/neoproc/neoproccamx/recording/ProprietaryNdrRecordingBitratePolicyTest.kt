package com.neoproc.neoproccamx.recording

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProprietaryNdrRecordingBitratePolicyTest {
    @Test
    fun explicitNdrNivukxAndApexLabelsOwnTheAutomaticMain10Policy() {
        val identities = listOf(
            "NDR10+ BT.2100 PQ" to EliteVideoQualityTier.ELITE_HDR_NDR10_PLUS_10BIT,
            "NIVUKX Vision BT.2100 PQ" to EliteVideoQualityTier.ELITE_HDR_NIVUKX_VISION_10BIT,
            "NIVUKX APEX SHARP" to EliteVideoQualityTier.ELITE_HDR_NIVUKX_VISION_10BIT,
            "NIVUKX APEX ZERO" to EliteVideoQualityTier.ELITE_HDR_NIVUKX_VISION_10BIT,
            "NIVUKX APEX CINEMA" to EliteVideoQualityTier.ELITE_HDR_NIVUKX_VISION_10BIT
        )

        identities.forEach { (label, tier) ->
            assertTrue(
                label,
                ProprietaryNdrRecordingBitratePolicy.isAutomaticMain10Route(
                    requestedDynamicRangeLabel = label,
                    eliteQualityTier = tier,
                    strictHighEnd = true,
                    bt2020PqTransport = true,
                    realMain10Transport = true,
                    manualRateControlledDelivery = false
                )
            )
        }
    }

    @Test
    fun genericHdrAndIncompleteTransportNeverEnterTheProprietaryPolicy() {
        fun admitted(
            label: String,
            tier: EliteVideoQualityTier? = EliteVideoQualityTier.ELITE_HDR_NDR10_PLUS_10BIT,
            strict: Boolean = true,
            pq: Boolean = true,
            main10: Boolean = true,
            manual: Boolean = false
        ): Boolean = ProprietaryNdrRecordingBitratePolicy.isAutomaticMain10Route(
            requestedDynamicRangeLabel = label,
            eliteQualityTier = tier,
            strictHighEnd = strict,
            bt2020PqTransport = pq,
            realMain10Transport = main10,
            manualRateControlledDelivery = manual
        )

        assertFalse(admitted("HDR10+"))
        assertFalse(admitted("HLG"))
        assertFalse(admitted("NDR10+", tier = null))
        assertFalse(admitted("NDR10+", strict = false))
        assertFalse(admitted("NDR10+", pq = false))
        assertFalse(admitted("NDR10+", main10 = false))
        assertFalse(admitted("NDR10+", manual = true))
    }

    @Test
    fun deviceAdvertised160MbpsBecomesTheStrictAutomaticTarget() {
        var capabilityProbe = 0

        val decision = ProprietaryNdrRecordingBitratePolicy.resolveMaximumAdmittedTarget(
            codecRequestCeilingBps = 2_000_000_000L,
            minimumViableBitrateBps = 120_000_000,
            clampBitrateToEncoderCapabilities = { requested ->
                capabilityProbe = requested
                requested.coerceAtMost(160_000_000)
            }
        )

        assertEquals(2_000_000_000, capabilityProbe)
        assertEquals(2_000_000_000, decision.codecPolicyCeilingBps)
        assertEquals(120_000_000, decision.minimumViableBitrateBps)
        assertEquals(160_000_000, decision.encoderAdmittedMaximumBps)
        assertEquals(160_000_000, decision.targetBitrateBps)
        assertTrue(decision.capabilityBound)
        assertTrue(decision.telemetryLine().contains("admission=STRICT_DEVICE_MAX"))
    }

    @Test
    fun oversizedCodecCeilingSaturatesSafelyWithoutProducingAnOverflowedTarget() {
        var capabilityProbe = 0

        val decision = ProprietaryNdrRecordingBitratePolicy.resolveMaximumAdmittedTarget(
            codecRequestCeilingBps = Long.MAX_VALUE,
            minimumViableBitrateBps = 120_000_000,
            clampBitrateToEncoderCapabilities = { requested ->
                capabilityProbe = requested
                160_000_000
            }
        )

        assertEquals(Int.MAX_VALUE, capabilityProbe)
        assertEquals(Int.MAX_VALUE, decision.codecPolicyCeilingBps)
        assertEquals(160_000_000, decision.targetBitrateBps)
    }

    @Test
    fun encoderMaximumBelowMinimumViableQualityFloorIsRejectedInsteadOfRedefiningTheFloor() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            ProprietaryNdrRecordingBitratePolicy.resolveMaximumAdmittedTarget(
                codecRequestCeilingBps = 2_000_000_000L,
                minimumViableBitrateBps = 120_000_000,
                clampBitrateToEncoderCapabilities = { 1 }
            )
        }

        assertTrue(failure.message.orEmpty().contains("below the minimum viable quality floor"))
        assertTrue(failure.message.orEmpty().contains("refused instead of downgraded"))
    }

    @Test
    fun liveNdrNivukxAndApexPlansUse160MbpsWithoutChangingPqMain10Transport() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = true
            val identities = listOf(
                "NDR10+ BT.2100 PQ" to EliteVideoQualityTier.ELITE_HDR_NDR10_PLUS_10BIT,
                "NIVUKX Vision BT.2100 PQ" to EliteVideoQualityTier.ELITE_HDR_NIVUKX_VISION_10BIT,
                "NIVUKX APEX SHARP" to EliteVideoQualityTier.ELITE_HDR_NIVUKX_VISION_10BIT,
                "NIVUKX APEX ZERO" to EliteVideoQualityTier.ELITE_HDR_NIVUKX_VISION_10BIT,
                "NIVUKX APEX CINEMA" to EliteVideoQualityTier.ELITE_HDR_NIVUKX_VISION_10BIT
            )

            identities.forEach { (label, tier) ->
                val clampRequests = mutableListOf<Int>()
                val requiredFormatClampRequests = mutableListOf<Triple<Int, Int, Int?>>()
                val plan = RecordingVideoEncoderPlanResolver.resolvePlan(
                    mime = "video/hevc",
                    width = 1632,
                    height = 1224,
                    fps = 25,
                    requestedBitrate = 160_000_000,
                    tenBitEnabled = true,
                    colorEncoding = RecordingColorEncodingConfig(
                        colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                        colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
                        colorRange = MediaFormat.COLOR_RANGE_FULL,
                        requiresTenBit = true,
                        main10Requested = true
                    ),
                    hardwareTenBitSupported = true,
                    av1Main10Profile = null,
                    sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                    // Reproduce the Vivo runtime contract: the generic strict floor is 240 Mbps,
                    // while its exact HEVC Main10 profile/level/size/rate capability is 160 Mbps.
                    // Proprietary NDR must use that proved device maximum instead of reapplying a
                    // generic floor the selected encoder cannot physically admit.
                    minimumBitrateFloorFor = { _, _ -> 240_000_000 },
                    clampBitrate = { requested ->
                        clampRequests += requested
                        requested.coerceAtMost(160_000_000)
                    },
                    clampBitrateForRequiredFormat = { requested, profile, level ->
                        requiredFormatClampRequests += Triple(requested, profile, level)
                        requested.coerceAtMost(160_000_000)
                    },
                    requestedDynamicRangeLabel = label,
                    eliteQualityTier = tier,
                    // Reproduce the live failure: fresh RAW scene evidence raises the adaptive
                    // request above 160 Mbps. The exact codec maximum must remain the high-quality
                    // target instead of becoming an impossible post-capability admission floor.
                    sceneQualityHints = RecordingEncoderSceneQualityHints(
                        sceneComplexity = 0.92f,
                        estimatedLux = 3f,
                        motionRisk = 0.84f,
                        noiseRisk = 0.73f,
                        sourceQualityTier = "MOTION_STABLE_LOW_NOISE_DETAIL",
                        sourceQualityBudgetMultiplier = 1.32,
                        antiMotionBlurEnabled = true
                    )
                )

                assertEquals(label, 160_000_000, plan.effectiveBitrate)
                assertFalse(label, clampRequests.contains(Int.MAX_VALUE))
                assertTrue(label, clampRequests.isEmpty())
                assertEquals(label, 1, requiredFormatClampRequests.size)
                assertEquals(label, 2_000_000_000, requiredFormatClampRequests.single().first)
                assertEquals(
                    label,
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                    requiredFormatClampRequests.single().second
                )
                assertEquals(
                    label,
                    MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel31,
                    checkNotNull(requiredFormatClampRequests.single().third)
                )
                assertTrue(label, plan.useTenBit)
                assertFalse(label, plan.simulatedTenBitFallback)
                assertEquals(label, MediaFormat.COLOR_STANDARD_BT2020, plan.colorEncoding.colorStandard)
                assertEquals(label, MediaFormat.COLOR_TRANSFER_ST2084, plan.colorEncoding.colorTransfer)
                assertEquals(label, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, plan.profile)
                assertTrue(
                    label,
                    plan.encoderQualityTelemetry.logLines.any {
                        it.contains("target=160000000") &&
                            it.contains("minimumViable=160000000") &&
                            it.contains("admission=STRICT_DEVICE_MAX")
                    }
                )
            }
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }
}
