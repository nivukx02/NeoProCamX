package com.neoproc.neoproccamx.niv99

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Niv99RuntimeActivationTest {
    @Test
    fun `production policy keeps live frames disabled without an approved certificate`() {
        val identity = Niv99TestFixtures.runtimeIdentity()
        val output = Niv99RuntimeActivationDecision()
        val permit = Niv99RuntimeActivationGate().evaluate(
            explicitlyRequested = true,
            target = Niv99ActivationTarget.VIDEO_PREVIEW,
            identity = identity,
            certificate = Niv99TestFixtures.evidenceCertificate(identity = identity),
            capabilities = Niv99TestFixtures.hardwareCapabilities(),
            nowEpochMs = Niv99TestFixtures.TEST_NOW_EPOCH_MS,
            output = output
        )
        assertNull(permit)
        assertEquals(Niv99RuntimeActivationStatus.BUILD_DISABLED, output.status)
        assertFalse(output.isApproved())
    }

    @Test
    fun `explicit runtime request is mandatory even with complete evidence`() {
        val identity = Niv99TestFixtures.runtimeIdentity()
        val output = Niv99RuntimeActivationDecision()
        val permit = gate(identity).evaluate(
            explicitlyRequested = false,
            target = Niv99ActivationTarget.VIDEO_PREVIEW,
            identity = identity,
            certificate = Niv99TestFixtures.evidenceCertificate(identity = identity),
            capabilities = Niv99TestFixtures.hardwareCapabilities(),
            nowEpochMs = Niv99TestFixtures.TEST_NOW_EPOCH_MS,
            output = output
        )
        assertNull(permit)
        assertEquals(Niv99RuntimeActivationStatus.NOT_EXPLICITLY_REQUESTED, output.status)
    }

    @Test
    fun `host-only validation cannot authorize Android live frames`() {
        val identity = Niv99TestFixtures.runtimeIdentity()
        val required = Niv99ActivationEvidenceMask.requiredFor(Niv99ActivationTarget.VIDEO_PREVIEW) or
            Niv99ActivationEvidence.RAW_BIT_EXACT.bit
        val hostOnly = required and Niv99ActivationEvidence.ANDROID_VULKAN_PARITY.bit.inv() and
            Niv99ActivationEvidence.DEVICE_INTEGRATION.bit.inv()
        val output = Niv99RuntimeActivationDecision()
        val permit = gate(identity).evaluate(
            explicitlyRequested = true,
            target = Niv99ActivationTarget.VIDEO_PREVIEW,
            identity = identity,
            certificate = Niv99TestFixtures.evidenceCertificate(
                identity = identity,
                passedEvidenceMask = hostOnly
            ),
            capabilities = Niv99TestFixtures.hardwareCapabilities(),
            nowEpochMs = Niv99TestFixtures.TEST_NOW_EPOCH_MS,
            output = output
        )
        assertNull(permit)
        assertEquals(Niv99RuntimeActivationStatus.EVIDENCE_INCOMPLETE, output.status)
        assertTrue(
            Niv99ActivationEvidenceMask.contains(
                output.missingEvidenceMask,
                Niv99ActivationEvidence.ANDROID_VULKAN_PARITY
            )
        )
        assertTrue(
            Niv99ActivationEvidenceMask.contains(
                output.missingEvidenceMask,
                Niv99ActivationEvidence.DEVICE_INTEGRATION
            )
        )
    }

    @Test
    fun `certificate cannot cross device camera mode or graph configuration`() {
        val baseContext = Niv99TestFixtures.context()
        val identity = Niv99TestFixtures.runtimeIdentity(baseContext)
        val certificate = Niv99TestFixtures.evidenceCertificate(identity = identity)
        val variants = listOf(
            identity.copy(deviceBuildFingerprint = "different-device"),
            identity.copy(gpuDriverFingerprint = "different-driver"),
            identity.copy(cameraId = "different-camera"),
            identity.copy(width = identity.width + 16),
            identity.copy(height = identity.height + 16),
            identity.copy(targetFps = identity.targetFps + 5),
            identity.copy(colorProfileId = identity.colorProfileId + 1L),
            identity.copy(signalDomain = Niv99SignalDomain.P010_YUV)
        )
        for (variant in variants) {
            val output = Niv99RuntimeActivationDecision()
            assertNull(
                gate(identity).evaluate(
                    explicitlyRequested = true,
                    target = Niv99ActivationTarget.VIDEO_PREVIEW,
                    identity = variant,
                    certificate = certificate,
                    capabilities = Niv99TestFixtures.hardwareCapabilities(),
                    nowEpochMs = Niv99TestFixtures.TEST_NOW_EPOCH_MS,
                    output = output
                )
            )
            assertEquals(Niv99RuntimeActivationStatus.IDENTITY_MISMATCH, output.status)
        }
    }

    @Test
    fun `expired certificate fails closed`() {
        val identity = Niv99TestFixtures.runtimeIdentity()
        val certificate = Niv99TestFixtures.evidenceCertificate(identity = identity).copy(
            issuedAtEpochMs = 1_000L,
            expiresAtEpochMs = 2_000L
        )
        val output = Niv99RuntimeActivationDecision()
        assertNull(
            gate(identity).evaluate(
                explicitlyRequested = true,
                target = Niv99ActivationTarget.VIDEO_PREVIEW,
                identity = identity,
                certificate = certificate,
                capabilities = Niv99TestFixtures.hardwareCapabilities(),
                nowEpochMs = 2_001L,
                output = output
            )
        )
        assertEquals(Niv99RuntimeActivationStatus.CERTIFICATE_EXPIRED, output.status)
    }

    @Test
    fun `extended storage feature and motion shader are mandatory`() {
        val identity = Niv99TestFixtures.runtimeIdentity()
        val certificate = Niv99TestFixtures.evidenceCertificate(identity = identity)
        val capabilities = Niv99TestFixtures.hardwareCapabilities().apply {
            storageImageExtendedFormats = false
        }
        val output = Niv99RuntimeActivationDecision()
        assertNull(
            gate(identity).evaluate(
                explicitlyRequested = true,
                target = Niv99ActivationTarget.VIDEO_PREVIEW,
                identity = identity,
                certificate = certificate,
                capabilities = capabilities,
                nowEpochMs = Niv99TestFixtures.TEST_NOW_EPOCH_MS,
                output = output
            )
        )
        assertEquals(Niv99RuntimeActivationStatus.CAPABILITY_UNSUPPORTED, output.status)
        assertEquals(
            Niv99UnsupportedReason.EXTENDED_STORAGE_IMAGE_FEATURE_UNAVAILABLE,
            output.capabilityReason
        )

        capabilities.storageImageExtendedFormats = true
        capabilities.shaderMotionConfidence = false
        assertNull(
            gate(identity).evaluate(
                explicitlyRequested = true,
                target = Niv99ActivationTarget.VIDEO_PREVIEW,
                identity = identity,
                certificate = certificate,
                capabilities = capabilities,
                nowEpochMs = Niv99TestFixtures.TEST_NOW_EPOCH_MS,
                output = output
            )
        )
        assertEquals(Niv99UnsupportedReason.REQUIRED_SHADER_UNAVAILABLE, output.capabilityReason)
    }

    @Test
    fun `synchronization2 cannot impersonate an unavailable timeline semaphore`() {
        val identity = Niv99TestFixtures.runtimeIdentity()
        val capabilities = Niv99TestFixtures.hardwareCapabilities().apply {
            timelineSemaphore = false
            synchronization2 = true
        }
        val output = Niv99RuntimeActivationDecision()
        assertNull(
            gate(identity).evaluate(
                explicitlyRequested = true,
                target = Niv99ActivationTarget.VIDEO_PREVIEW,
                identity = identity,
                certificate = Niv99TestFixtures.evidenceCertificate(identity = identity),
                capabilities = capabilities,
                nowEpochMs = Niv99TestFixtures.TEST_NOW_EPOCH_MS,
                output = output
            )
        )
        assertEquals(
            Niv99UnsupportedReason.EXPLICIT_SYNCHRONIZATION_UNAVAILABLE,
            output.capabilityReason
        )
    }

    @Test
    fun `realtime certificate must prove bounded tail latency`() {
        val identity = Niv99TestFixtures.runtimeIdentity()
        val certificate = Niv99TestFixtures.evidenceCertificate(identity = identity).copy(
            p99FrameTimeNs = 41_000_000L,
            maximumFrameTimeNs = 45_000_000L,
            deadlineMissCount = 2L
        )
        val output = Niv99RuntimeActivationDecision()
        assertNull(
            gate(identity).evaluate(
                explicitlyRequested = true,
                target = Niv99ActivationTarget.VIDEO_PREVIEW,
                identity = identity,
                certificate = certificate,
                capabilities = Niv99TestFixtures.hardwareCapabilities(),
                nowEpochMs = Niv99TestFixtures.TEST_NOW_EPOCH_MS,
                output = output
            )
        )
        assertEquals(Niv99RuntimeActivationStatus.METRICS_FAILED, output.status)
        assertTrue(output.failedMetricMask and Niv99ActivationMetric.FRAME_TIMING.bit != 0L)
    }

    @Test
    fun `sensor linear activation requires bit exact RAW evidence and measurement`() {
        val identity = Niv99TestFixtures.runtimeIdentity()
        val withoutRawEvidence = Niv99TestFixtures.allActivationEvidence() and
            Niv99ActivationEvidence.RAW_BIT_EXACT.bit.inv()
        val missingOutput = Niv99RuntimeActivationDecision()
        assertNull(
            gate(identity).evaluate(
                explicitlyRequested = true,
                target = Niv99ActivationTarget.VIDEO_PREVIEW,
                identity = identity,
                certificate = Niv99TestFixtures.evidenceCertificate(
                    identity = identity,
                    passedEvidenceMask = withoutRawEvidence
                ),
                capabilities = Niv99TestFixtures.hardwareCapabilities(),
                nowEpochMs = Niv99TestFixtures.TEST_NOW_EPOCH_MS,
                output = missingOutput
            )
        )
        assertTrue(
            Niv99ActivationEvidenceMask.contains(
                missingOutput.missingEvidenceMask,
                Niv99ActivationEvidence.RAW_BIT_EXACT
            )
        )

        val mismatchOutput = Niv99RuntimeActivationDecision()
        assertNull(
            gate(identity).evaluate(
                explicitlyRequested = true,
                target = Niv99ActivationTarget.VIDEO_PREVIEW,
                identity = identity,
                certificate = Niv99TestFixtures.evidenceCertificate(identity = identity).copy(
                    rawByteMismatchCount = 1L
                ),
                capabilities = Niv99TestFixtures.hardwareCapabilities(),
                nowEpochMs = Niv99TestFixtures.TEST_NOW_EPOCH_MS,
                output = mismatchOutput
            )
        )
        assertTrue(mismatchOutput.failedMetricMask and Niv99ActivationMetric.RAW_FIDELITY.bit != 0L)
    }

    @Test
    fun `valid certificate returns one session-bound permit`() {
        val context = Niv99TestFixtures.context()
        val identity = Niv99TestFixtures.runtimeIdentity(context)
        val output = Niv99RuntimeActivationDecision()
        val permit = gate(identity).evaluate(
            explicitlyRequested = true,
            target = Niv99ActivationTarget.VIDEO_PREVIEW,
            identity = identity,
            certificate = Niv99TestFixtures.evidenceCertificate(identity = identity),
            capabilities = Niv99TestFixtures.hardwareCapabilities(),
            nowEpochMs = Niv99TestFixtures.TEST_NOW_EPOCH_MS,
            output = output
        )
        assertNotNull(permit)
        assertTrue(output.isApproved())
        assertTrue(checkNotNull(permit).matches(context))
        assertFalse(permit.matches(Niv99TestFixtures.context(sessionGeneration = 2L)))
        assertFalse(permit.matches(Niv99TestFixtures.context(width = context.width + 16)))
    }

    @Test
    fun `recording route requires long recording evidence`() {
        val identity = Niv99TestFixtures.runtimeIdentity()
        val evidence = Niv99TestFixtures.allActivationEvidence() and
            Niv99ActivationEvidence.LONG_RECORDING.bit.inv()
        val output = Niv99RuntimeActivationDecision()
        assertNull(
            gate(identity).evaluate(
                explicitlyRequested = true,
                target = Niv99ActivationTarget.VIDEO_RECORDING,
                identity = identity,
                certificate = Niv99TestFixtures.evidenceCertificate(
                    target = Niv99ActivationTarget.VIDEO_RECORDING,
                    identity = identity,
                    passedEvidenceMask = evidence
                ),
                capabilities = Niv99TestFixtures.hardwareCapabilities(),
                nowEpochMs = Niv99TestFixtures.TEST_NOW_EPOCH_MS,
                output = output
            )
        )
        assertTrue(
            Niv99ActivationEvidenceMask.contains(
                output.missingEvidenceMask,
                Niv99ActivationEvidence.LONG_RECORDING
            )
        )
    }

    @Test
    fun `offline conversion requires integrity evidence without claiming realtime`() {
        val context = Niv99TestFixtures.context(mode = Niv99OperatingMode.CONVERSION_RAW_TO_LOG)
        val identity = Niv99TestFixtures.runtimeIdentity(context)
        val certificate = Niv99TestFixtures.evidenceCertificate(
            target = Niv99ActivationTarget.CONVERSION,
            identity = identity
        ).copy(
            medianFrameTimeNs = 0L,
            p95FrameTimeNs = 0L,
            p99FrameTimeNs = 0L,
            maximumFrameTimeNs = 0L,
            frameDeadlineNs = 0L,
            deadlineMissCount = -1L,
            thermalRunDurationNs = 0L
        )
        val output = Niv99RuntimeActivationDecision()
        val permit = gate(identity).evaluate(
            explicitlyRequested = true,
            target = Niv99ActivationTarget.CONVERSION,
            identity = identity,
            certificate = certificate,
            capabilities = Niv99TestFixtures.hardwareCapabilities(),
            nowEpochMs = Niv99TestFixtures.TEST_NOW_EPOCH_MS,
            output = output
        )
        assertNotNull(permit)
        assertEquals(Niv99RuntimeActivationStatus.APPROVED, output.status)
    }

    private fun gate(identity: Niv99RuntimeGraphIdentity): Niv99RuntimeActivationGate =
        Niv99RuntimeActivationGate(Niv99TestFixtures.activationPolicy(identity))
}
