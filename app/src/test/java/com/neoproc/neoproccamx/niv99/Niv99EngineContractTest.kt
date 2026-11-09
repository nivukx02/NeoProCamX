package com.neoproc.neoproccamx.niv99

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Niv99EngineContractTest {
    @Test
    fun `engine publishes coherent four-field provenance`() {
        val engine = Niv99Engine(maximumTiles = 64)
        val input = input()
        val output = Niv99StatePacket()
        val result = engine.process(input, Niv99TestFixtures.capabilityDecision(), output)
        assertEquals(Niv99ValidationResult.VALID, result)
        assertTrue(output.accepted)
        assertTrue(output.hasCoherentIdentity())
        assertEquals(Niv99TestFixtures.CAMERA_ID, output.identity.cameraId)
        assertEquals(1L, output.identity.sessionGeneration)
        assertEquals(1L, output.identity.frameNumber)
        assertEquals(40_000_000L, output.identity.sensorTimestampNs)
    }

    @Test
    fun `engine never mutates RAW source samples`() {
        val engine = Niv99Engine(maximumTiles = 64)
        val input = input()
        val before = input.plane.samples.copyOf()
        val output = Niv99StatePacket()
        engine.process(input, Niv99TestFixtures.capabilityDecision(), output)
        assertTrue(before.contentEquals(input.plane.samples))
    }

    @Test
    fun `engine preserves manual WB authority`() {
        val engine = Niv99Engine(maximumTiles = 64)
        val input = input()
        input.context.whiteBalanceIntent.apply {
            manualKelvin = 4_100
            manualTint = 18
            manualRedGain = 1.9f
            manualGreenGain = 1f
            manualBlueGain = 1.3f
        }
        val output = Niv99StatePacket()
        engine.process(input, Niv99TestFixtures.capabilityDecision(), output)
        assertEquals(Niv99WhiteBalanceAuthority.MANUAL, output.whiteBalance.authority)
        assertEquals(1.9f, output.whiteBalance.redGain, 0f)
        assertEquals(1.3f, output.whiteBalance.blueGain, 0f)
    }

    @Test
    fun `engine honors first frame AWB lock without automatic override`() {
        val engine = Niv99Engine(maximumTiles = 64)
        val input = input()
        input.context.whiteBalanceIntent.apply {
            awbLockRequested = true
            halAwbConverged = true
            halGainsAvailable = true
            halRedGain = 1.72f
            halGreenEvenGain = 1.04f
            halGreenOddGain = 0.96f
            halBlueGain = 1.31f
        }
        val output = Niv99StatePacket()
        assertEquals(
            Niv99ValidationResult.VALID,
            engine.process(input, Niv99TestFixtures.capabilityDecision(), output)
        )
        assertEquals(Niv99WhiteBalanceAuthority.AWB_LOCK, output.whiteBalance.authority)
        assertEquals(1.72f, output.whiteBalance.redGain, 0f)
        assertEquals(1f, output.whiteBalance.greenGain, 0f)
        assertEquals(1.31f, output.whiteBalance.blueGain, 0f)
    }

    @Test
    fun `engine rejects stale duplicate metadata before history publication`() {
        val engine = Niv99Engine(maximumTiles = 64)
        val input = input()
        val first = Niv99StatePacket()
        val duplicate = Niv99StatePacket()
        assertEquals(
            Niv99ValidationResult.VALID,
            engine.process(input, Niv99TestFixtures.capabilityDecision(), first)
        )
        assertEquals(
            Niv99ValidationResult.STALE_METADATA,
            engine.process(input, Niv99TestFixtures.capabilityDecision(), duplicate)
        )
        assertFalse(duplicate.accepted)
        assertEquals(Niv99ValidationResult.STALE_METADATA, duplicate.rejectionReason)
    }

    @Test
    fun `invalid calibration is rejected explicitly`() {
        val engine = Niv99Engine(maximumTiles = 64)
        val input = input().apply { calibration = Niv99TestFixtures.calibration("wrong-camera") }
        val output = Niv99StatePacket()
        val result = engine.process(input, Niv99TestFixtures.capabilityDecision(), output)
        assertEquals(Niv99ValidationResult.INVALID_CALIBRATION, result)
        assertFalse(output.accepted)
        assertEquals(Niv99ValidationResult.INVALID_CALIBRATION, output.rejectionReason)
    }

    @Test
    fun `atomic publisher returns complete packet only`() {
        val publisher = Niv99AtomicStatePublisher()
        val source = Niv99StatePacket().apply {
            identity.set(Niv99TestFixtures.CAMERA_ID, 3L, 9L, 99L)
            whiteBalance.identity.copyFrom(identity)
            whiteBalance.sourceIdentity.copyFrom(identity)
            denoise.identity.copyFrom(identity)
            accepted = true
        }
        val output = Niv99StatePacket()
        assertFalse(publisher.copyLatest(output))
        publisher.publish(source)
        assertTrue(publisher.copyLatest(output))
        assertTrue(output.hasCoherentIdentity())
    }

    @Test
    fun `timeline signature identifies every required reset boundary`() {
        val baseline = Niv99TestFixtures.context(frameNumber = 1L)
        val signature = Niv99TimelineSignature().apply { update(baseline) }
        fun reason(mutate: (Niv99FrameContext) -> Unit): Niv99ResetReason {
            val next = Niv99TestFixtures.context(frameNumber = 2L)
            mutate(next)
            return signature.resetReason(next)
        }
        assertEquals(Niv99ResetReason.CAMERA_CHANGED, reason { it.identity.set("other", 1L, 2L, 80_000_000L) })
        assertEquals(Niv99ResetReason.SESSION_CHANGED, reason { it.identity.set(Niv99TestFixtures.CAMERA_ID, 2L, 2L, 80_000_000L) })
        assertEquals(Niv99ResetReason.MODE_CHANGED, reason { it.operatingMode = Niv99OperatingMode.PRO })
        assertEquals(Niv99ResetReason.DIMENSIONS_CHANGED, reason { it.width += 1 })
        assertEquals(Niv99ResetReason.FPS_CHANGED, reason { it.targetFps = 30 })
        assertEquals(Niv99ResetReason.COLOR_PROFILE_CHANGED, reason { it.colorProfileId += 1 })
        assertEquals(Niv99ResetReason.SURFACE_CHANGED, reason { it.surfaceGeneration += 1 })
        assertEquals(Niv99ResetReason.CODEC_CHANGED, reason { it.codecGeneration += 1 })
    }

    private fun input(): Niv99FrameInput = Niv99FrameInput(64).apply {
        context.identity.set(Niv99TestFixtures.CAMERA_ID, 1L, 1L, 40_000_000L)
        context.operatingMode = Niv99OperatingMode.VIDEO
        context.signalDomain = Niv99SignalDomain.SENSOR_LINEAR_RAW
        context.detailProfile = Niv99DetailProfile.NATURAL
        context.width = 64
        context.height = 64
        context.targetFps = 25
        context.colorProfileId = 11L
        context.surfaceGeneration = 7L
        context.codecGeneration = 5L
        context.iso = 400
        context.exposureTimeNs = 8_000_000L
        context.frameDurationNs = 40_000_000L
        context.rollingShutterSkewNs = 12_000_000L
        context.motionMagnitude = 0.05f
        context.opticalFlowConfidence = 0.92f
        context.whiteBalanceIntent.clear()
        plane.set(
            Niv99TestFixtures.rawPlane().samples,
            64,
            64,
            64,
            whiteLevel = 1f,
            cfaPattern = Niv99CfaPattern.RGGB
        )
        calibration = Niv99TestFixtures.calibration()
        semantics = Niv99SemanticTileMap(64).apply { clear(4) }
        residualConfidence = 1f
    }
}
