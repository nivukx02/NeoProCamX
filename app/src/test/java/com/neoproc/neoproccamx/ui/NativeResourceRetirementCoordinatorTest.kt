package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File

class NativeResourceRetirementCoordinatorTest {
    @Test
    fun `view model transfers the concrete camera retirement proof into the lease`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
        ).readText()
        val lease = source
            .substringAfter("val retirementLease = CameraNativeResourceRetirementLease(")
            .substringBefore("val retirementScheduled =")

        assertTrue(lease.contains("camera::destroyWithRetirementProof"))
        assertFalse(lease.contains("camera::destroyWithProof"))
    }

    @Test
    fun `camera timeout proof quarantines exact bundle and never destroys dependents`() {
        val events = mutableListOf<String>()
        val exactBundle = Any()
        var retainedOwner: Any? = null
        var retainedReason: String? = null

        val outcome = CameraDependentRetirementGate.execute(
            quarantineOwner = exactBundle,
            retireCamera = {
                events += "camera-proof"
                false
            },
            retireDependents = { events += "pipeline-destroy" },
            retainQuarantine = { reason, owner ->
                events += "quarantine"
                retainedReason = reason
                retainedOwner = owner
            }
        )

        assertEquals(listOf("camera-proof", "quarantine"), events)
        assertFalse(outcome.cameraRetired)
        assertFalse(outcome.dependentsInvoked)
        assertNull(outcome.cameraFailure)
        assertSame(exactBundle, retainedOwner)
        assertTrue(requireNotNull(retainedReason).contains("dependent resources preserved"))
    }

    @Test
    fun `camera proof exception quarantines exact bundle before returning and skips dependents`() {
        val events = mutableListOf<String>()
        val exactBundle = Any()
        val expectedFailure = IllegalStateException("reader-close-timeout")
        var retainedOwner: Any? = null

        val outcome = CameraDependentRetirementGate.execute(
            quarantineOwner = exactBundle,
            retireCamera = {
                events += "camera-proof"
                throw expectedFailure
            },
            retireDependents = { events += "pipeline-destroy" },
            retainQuarantine = { _, owner ->
                events += "quarantine"
                retainedOwner = owner
            }
        )

        assertEquals(listOf("camera-proof", "quarantine"), events)
        assertFalse(outcome.cameraRetired)
        assertFalse(outcome.dependentsInvoked)
        assertSame(expectedFailure, outcome.cameraFailure)
        assertSame(exactBundle, retainedOwner)
    }

    @Test
    fun `pipeline and all dependents run only after successful camera retirement proof`() {
        val events = mutableListOf<String>()

        val outcome = CameraDependentRetirementGate.execute(
            quarantineOwner = Any(),
            retireCamera = {
                events += "camera-proof"
                true
            },
            retireDependents = {
                events += "pipeline-destroy"
                events += "ai-destroy"
                events += "vulkan-destroy"
            },
            retainQuarantine = { _, _ -> events += "unexpected-quarantine" }
        )

        assertEquals(
            listOf("camera-proof", "pipeline-destroy", "ai-destroy", "vulkan-destroy"),
            events
        )
        assertTrue(outcome.cameraRetired)
        assertTrue(outcome.dependentsInvoked)
        assertNull(outcome.cameraFailure)
    }

    @Test
    fun `hung native retirement never blocks lifecycle caller and is scheduled once`() {
        val coordinator = NativeResourceRetirementCoordinator()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val completed = AtomicBoolean(false)

        val startNs = System.nanoTime()
        assertTrue(
            coordinator.schedule("test-quarantine") {
                entered.countDown()
                release.await()
                completed.set(true)
                finished.countDown()
            }
        )
        val scheduleDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue("schedule blocked for ${scheduleDurationMs}ms", scheduleDurationMs < 1_000L)
            assertFalse(completed.get())
            assertFalse(coordinator.schedule("duplicate") { completed.set(true) })
        } finally {
            release.countDown()
        }
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertTrue(completed.get())
    }

    @Test
    fun `transient retirement failure retries and stops at first proof`() {
        var attempts = 0
        val retired = NativeResourceRetirementRetryPolicy.retire {
            attempts += 1
            attempts == 2
        }

        assertTrue(retired)
        assertEquals(2, attempts)
    }

    @Test
    fun `persistent retirement failure is strictly bounded`() {
        var attempts = 0
        val retired = NativeResourceRetirementRetryPolicy.retire {
            attempts += 1
            false
        }

        assertFalse(retired)
        assertEquals(NativeResourceRetirementRetryPolicy.RAW_MANAGER_MAX_ATTEMPTS, attempts)
    }

    @Test
    fun `camera lease proves worker and native ownership before destructive release`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/ui/NativeResourceRetirementCoordinator.kt"
        ).readText().substringAfter("internal class CameraNativeResourceRetirementLease(")
        val stopOwnerBarrier = source.indexOf("while (recordingStopOwnerActive())")
        val npv10Retirement = source.indexOf("retireSafely(\"NPV10 recorder\"")
        val foregroundProtectionStop = source.indexOf(
            "retireSafely(\"recording foreground protection\""
        )
        val ingressRetry = source.indexOf("val ingressRetired = NativeResourceRetirementRetryPolicy.retire")
        val workerProof = source.indexOf("isSensorLinearNdrRecordingWorkerQuiescentForTeardown()")
        val encoderDetach = source.indexOf("vulkanRuntime.detachAllEncoderSurfacesWithProof()")
        val encoderDetachFailureGate = source.indexOf("if (!encoderDetached) {", encoderDetach)
        val rawManagerProof = source.indexOf("val rawManagerRetired = NativeResourceRetirementRetryPolicy.retire")
        val previewRetirement = source.indexOf("val previewSurfaceRetired = retireWithProof(")
        val gpuDestroy = source.indexOf("retireSafely(\"GPU recorder\"")
        val stillDestroy = source.indexOf("retireWithProof(\"still capture\"")
        val cameraDestroy = source.indexOf("val cameraRetirement = CameraDependentRetirementGate.execute(")
        val pipelineDestroy = source.indexOf("retireSafely(\"frame pipeline\"")
        val vulkanDestroy = source.indexOf("vulkanRuntime::closeWithRetirementProof")

        assertTrue(stopOwnerBarrier >= 0)
        assertTrue(npv10Retirement > stopOwnerBarrier)
        assertTrue(foregroundProtectionStop > npv10Retirement)
        assertTrue(ingressRetry > foregroundProtectionStop)
        assertTrue(workerProof > ingressRetry)
        assertTrue(encoderDetach > workerProof)
        assertTrue(encoderDetachFailureGate > encoderDetach)
        assertTrue(encoderDetachFailureGate < rawManagerProof)
        assertTrue(
            source.substring(encoderDetachFailureGate, rawManagerProof)
                .contains("dependent resources preserved")
        )
        assertTrue(source.substring(encoderDetachFailureGate, rawManagerProof).contains("return"))
        assertTrue(rawManagerProof > encoderDetach)
        assertTrue(previewRetirement > rawManagerProof)
        assertTrue(stillDestroy > rawManagerProof)
        assertTrue(cameraDestroy > rawManagerProof)
        assertTrue(cameraDestroy > stillDestroy)
        assertTrue(gpuDestroy > cameraDestroy)
        assertTrue(pipelineDestroy > cameraDestroy)
        assertTrue(pipelineDestroy > gpuDestroy)
        assertTrue(vulkanDestroy > encoderDetach)
        assertTrue(source.contains("if (encoderDetached && demosaicReleased && previewSurfaceRetired)"))
        assertTrue(source.contains("stopSensorLinearNdrRecordingIngress("))
        assertTrue(source.contains("stopResult?.teardownSafe == true && quiescent"))
        assertTrue(source.contains("stopResult?.ingressFailed == true"))
        assertTrue(source.contains("recorder worker did not prove quiescence after bounded retries"))
        assertTrue(source.contains("rawVideoManager.destroy()"))
        assertTrue(source.contains("RAW_MANAGER_MAX_ATTEMPTS"))
        assertTrue(source.contains("quarantine(\"RAW manager teardown did not finalize after bounded retries\")"))
        assertTrue(source.contains("NPV10 recorder did not retire before dependent camera resources"))
        assertTrue(source.contains("still capture retained Image ownership; camera and pipeline preserved"))
        assertTrue(source.contains("if (!cameraRetirement.cameraRetired)"))
        assertTrue(source.contains("Camera controller retirement proof threw; dependents preserved"))
        assertTrue(source.contains("!previewSurfaceRetired || !cleanupSafe"))
        assertTrue(source.contains("NativeCameraResourceQuarantine.retain(reason, this)"))
    }
}
