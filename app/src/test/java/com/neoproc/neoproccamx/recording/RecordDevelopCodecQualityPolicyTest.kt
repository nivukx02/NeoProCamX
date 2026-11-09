package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordDevelopCodecQualityPolicyTest {
    @Test
    fun smallGeometryRetainsIdealTargetAndIndependentViableFloor() {
        assertEquals(
            200_000_000,
            RecordDevelopEncoderQualityPolicy.requiredBitrate(1280, 720, 30)
        )
        assertEquals(
            80_000_000,
            RecordDevelopEncoderQualityPolicy.minimumViableBitrate(1280, 720, 30)
        )
    }

    @Test
    fun uhdThirtyFpsHasExactUnclampedQualityFloor() {
        assertEquals(
            335_923_200,
            RecordDevelopEncoderQualityPolicy.requiredBitrate(3840, 2160, 30)
        )
    }

    @Test
    fun fullSensorTwentyFiveFpsHasExactUnclampedQualityFloor() {
        assertEquals(
            424_673_280,
            RecordDevelopEncoderQualityPolicy.requiredBitrate(4096, 3072, 25)
        )
    }

    @Test
    fun unrepresentableMediaCodecBitrateIsRejectedInsteadOfClamped() {
        val failure = assertThrows(IllegalStateException::class.java) {
            RecordDevelopEncoderQualityPolicy.requiredBitrate(7680, 4320, 120)
        }

        assertTrue(failure.message.orEmpty().contains("exceeds MediaCodec's integer bitrate limit"))
        assertTrue(failure.message.orEmpty().contains("bitrate was not clamped"))
    }

    @Test
    fun elitePlanSeparatesRequiredConfiguredAndAdvertisedRates() {
        val plan = RecordDevelopEncoderQualityPolicy.resolve(
            width = 3840,
            height = 2160,
            fps = 30,
            bitrateLower = 10_000_000,
            bitrateUpper = 500_000_000,
            complexityLower = 0,
            complexityUpper = 10
        )

        assertEquals(335_923_200, plan.policyTargetBitrate)
        assertEquals(111_974_400, plan.minimumViableBitrate)
        assertEquals(335_923_200, plan.configuredBitrate)
        assertEquals(500_000_000, plan.advertisedBitrateCeiling)
        assertEquals(100, plan.quality)
        assertEquals(10, plan.complexity)
        assertEquals(18, plan.maximumQp)
    }

    @Test
    fun vivoLikeOneHundredSixtyMegabitCandidateSaturatesItsCeilingWithoutHidingIdealTarget() {
        val report = RecordDevelopEncoderQualityPolicy.strictCapabilityReport(
            width = 1280,
            height = 720,
            fps = 30,
            candidates = listOf(
                encoderCandidate(
                    name = "c2.qti.hevc.encoder",
                    bitrateUpper = 160_000_000,
                    rateControlModes = setOf(RecordDevelopEncoderRateControl.VBR)
                )
            )
        )

        assertTrue(report.accepted)
        assertEquals(200_000_000, report.requiredBitrateBps)
        assertEquals(80_000_000, report.minimumViableBitrateBps)
        assertEquals(160_000_000, report.bestAdvertisedEligibleBitrateBps)
        assertEquals("c2.qti.hevc.encoder", report.selectedCandidateName)
        assertEquals(RecordDevelopEncoderRateControl.VBR, report.selectedRateControl)
        assertEquals(160_000_000, report.selectedConfiguredBitrateBps)
        assertTrue(report.capabilityBound)
        assertTrue(report.candidates.single().rejections.isEmpty())
    }

    @Test
    fun candidateBelowMinimumViableFloorIsRejectedWithExactEvidence() {
        val report = RecordDevelopEncoderQualityPolicy.strictCapabilityReport(
            width = 1280,
            height = 720,
            fps = 30,
            candidates = listOf(
                encoderCandidate(
                    name = "undersized-hevc",
                    bitrateUpper = 70_000_000,
                    rateControlModes = setOf(RecordDevelopEncoderRateControl.VBR)
                )
            )
        )

        assertFalse(report.accepted)
        assertEquals(200_000_000, report.requiredBitrateBps)
        assertEquals(80_000_000, report.minimumViableBitrateBps)
        assertEquals(70_000_000, report.bestAdvertisedEligibleBitrateBps)
        assertNull(report.selectedCandidateName)
        assertEquals(
            listOf(RecordDevelopEncoderCapabilityRejection.BITRATE_CEILING_BELOW_MINIMUM_VIABLE),
            report.candidates.single().rejections
        )
        val evidence = assertThrows(IllegalStateException::class.java) {
            report.requireAccepted()
        }.message.orEmpty()
        assertTrue(evidence.contains("requiredBitrateBps=200000000"))
        assertTrue(evidence.contains("minimumViableBitrateBps=80000000"))
        assertTrue(evidence.contains("bestAdvertisedEligibleBitrateBps=70000000"))
        assertTrue(
            evidence.contains(
                "undersized-hevc[max=70000000,modes=VBR," +
                    "status=BITRATE_CEILING_BELOW_MINIMUM_VIABLE]"
            )
        )
        assertTrue(evidence.contains("output transport was not substituted"))
    }

    @Test
    fun cqOnlyCandidateIsRejectedEvenWithHighAdvertisedCeiling() {
        val report = RecordDevelopEncoderQualityPolicy.strictCapabilityReport(
            width = 3840,
            height = 2160,
            fps = 30,
            candidates = listOf(
                encoderCandidate(
                    name = "cq-only",
                    bitrateUpper = 800_000_000,
                    rateControlModes = setOf(RecordDevelopEncoderRateControl.CQ)
                )
            )
        )

        assertFalse(report.accepted)
        assertEquals(0, report.bestAdvertisedEligibleBitrateBps)
        assertEquals(
            listOf(RecordDevelopEncoderCapabilityRejection.RATE_CONTROL_UNSUPPORTED),
            report.candidates.single().rejections
        )
        assertTrue(report.rejectionMessage().contains("modes=CQ,status=RATE_CONTROL_UNSUPPORTED"))
    }

    @Test
    fun allCandidatesAreEvaluatedAndLaterStrictCandidateIsSelected() {
        val report = RecordDevelopEncoderQualityPolicy.strictCapabilityReport(
            width = 3840,
            height = 2160,
            fps = 30,
            candidates = listOf(
                encoderCandidate(
                    name = "low-ceiling",
                    bitrateUpper = 160_000_000,
                    rateControlModes = setOf(RecordDevelopEncoderRateControl.VBR)
                ),
                encoderCandidate(
                    name = "cq-ineligible",
                    bitrateUpper = 900_000_000,
                    rateControlModes = setOf(RecordDevelopEncoderRateControl.CQ)
                ),
                encoderCandidate(
                    name = "strict-main10-p010",
                    bitrateUpper = 500_000_000,
                    rateControlModes = setOf(RecordDevelopEncoderRateControl.CBR)
                )
            )
        )

        assertTrue(report.accepted)
        assertEquals("strict-main10-p010", report.selectedCandidateName)
        assertEquals(RecordDevelopEncoderRateControl.CBR, report.selectedRateControl)
        assertEquals(335_923_200, report.selectedConfiguredBitrateBps)
        assertEquals(500_000_000, report.bestAdvertisedEligibleBitrateBps)
        assertEquals(3, report.candidates.size)
        assertTrue(report.candidates.single { it.candidate.name == "strict-main10-p010" }.selected)
    }

    @Test
    fun resolveUsesExactDeviceCeilingWhenItClearsViableFloor() {
        val plan = RecordDevelopEncoderQualityPolicy.resolve(
            width = 3840,
            height = 2160,
            fps = 30,
            bitrateLower = 20_000_000,
            bitrateUpper = 240_000_000
        )

        assertEquals(335_923_200, plan.policyTargetBitrate)
        assertEquals(111_974_400, plan.minimumViableBitrate)
        assertEquals(240_000_000, plan.configuredBitrate)
        assertEquals(240_000_000, plan.advertisedBitrateCeiling)
        assertTrue(plan.capabilityBound)
    }

    @Test
    fun resolveRefusesCeilingBelowMinimumViableFloor() {
        val failure = assertThrows(IllegalStateException::class.java) {
            RecordDevelopEncoderQualityPolicy.resolve(
                width = 3840,
                height = 2160,
                fps = 30,
                bitrateLower = 20_000_000,
                bitrateUpper = 100_000_000
            )
        }

        assertTrue(failure.message.orEmpty().contains("100000000 is below minimum viable 111974400"))
    }

    @Test
    fun decoderSelectionRejectsAnySilentEightBitOrNonP010Fallback() {
        val candidates = listOf(
            RecordDevelopDecoderCandidate("hardware-8bit", true, true, false, false, true),
            RecordDevelopDecoderCandidate("software-main10", false, false, true, true, true),
            RecordDevelopDecoderCandidate("hardware-main10", true, true, true, true, true)
        )

        assertEquals("hardware-main10", RecordDevelopDecoderQualityPolicy.select(candidates)?.name)
        assertNull(
            RecordDevelopDecoderQualityPolicy.select(
                candidates.filter { !it.supportsP010 }
            )
        )
    }

    @Test
    fun decoderOutputRequiresExactP010GeometryAndStrictPts() {
        RecordDevelopDecoderQualityPolicy.requireOutput(
            expectedWidth = 3840,
            expectedHeight = 2160,
            actualWidth = 3840,
            actualHeight = 2160,
            p010 = true,
            strideBytes = 7680,
            sliceHeight = 2160
        )
        RecordDevelopDecoderQualityPolicy.requireMonotonicPts(33_333L, 66_666L)

        assertThrows(IllegalArgumentException::class.java) {
            RecordDevelopDecoderQualityPolicy.requireMonotonicPts(66_666L, 66_666L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecordDevelopDecoderQualityPolicy.requireOutput(
                expectedWidth = 3840,
                expectedHeight = 2160,
                actualWidth = 3840,
                actualHeight = 2160,
                p010 = false,
                strideBytes = 7680,
                sliceHeight = 2160
            )
        }
    }

    private fun encoderCandidate(
        name: String,
        bitrateUpper: Int,
        rateControlModes: Set<RecordDevelopEncoderRateControl>,
        supportsMain10: Boolean = true,
        supportsP010: Boolean = true,
        supportsRequestedGeometryAndRate: Boolean = true,
        bitrateLower: Int = 1_000_000,
        hardwareAccelerated: Boolean = true,
        vendor: Boolean = true
    ): RecordDevelopEncoderCapabilityCandidate = RecordDevelopEncoderCapabilityCandidate(
        name = name,
        hardwareAccelerated = hardwareAccelerated,
        vendor = vendor,
        supportsMain10 = supportsMain10,
        supportsP010 = supportsP010,
        supportsRequestedGeometryAndRate = supportsRequestedGeometryAndRate,
        bitrateLower = bitrateLower,
        bitrateUpper = bitrateUpper,
        rateControlModes = rateControlModes
    )
}
