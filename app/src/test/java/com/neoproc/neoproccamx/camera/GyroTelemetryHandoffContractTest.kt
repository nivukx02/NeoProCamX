package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * s1-f04 / CP60 — golden-lock for the gyro telemetry capture + dormant-gate contract for the new
 * timestamp-reconciled hand-off. CameraController is not JVM-instantiable (Android-coupled), so these
 * assert the source invariants: (1) full-rate capture remains available on demand, and (2) every new
 * consumer hand-off is dormant by default and applies to no pixel.
 */
class GyroTelemetryHandoffContractTest {

    private val controllerSource: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt").readText()
    }
    private val halSource: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/camera/HardwareAbstractionLayer.kt").readText()
    }

    @Test
    fun fullRateCaptureSideRemainsAvailableBehindExplicitDemand() {
        // The two ring pushes that constitute live capture remain intact (no quality downgrade).
        assertTrue(controllerSource.contains("motionTelemetryRingBuffer.push(motionTelemetry)"))
        assertTrue(controllerSource.contains("CameraMotionTelemetry.fromGyroscope"))
        assertTrue(controllerSource.contains("CameraMotionTelemetry.fromCaptureResult(motionTelemetry, r)"))
        assertTrue(controllerSource.contains("SensorManager.SENSOR_DELAY_FASTEST"))
        assertTrue(controllerSource.contains("Sensor.TYPE_GYROSCOPE"))
        assertTrue(controllerSource.contains("reconcileMotionTelemetryRegistration()"))
        assertTrue(controllerSource.contains("CameraMotionTelemetryDemandPolicy.shouldCollect"))
        // Ring depth unchanged (1024-deep, taken from the engine profile, not a local literal).
        assertTrue(controllerSource.contains("OMNIPOTENT_CINEMA_ENGINE.motionTelemetryRingBufferCapacity"))
    }

    @Test
    fun handoffConsumerIsDormantByDefault() {
        assertTrue(controllerSource.contains("var gyroEisTelemetryConsumerEnabled: Boolean = false"))
        assertTrue(controllerSource.contains("var gyroToCameraClockOffsetNs: Long = 0L"))
        // Every hand-off short-circuits to null unless explicitly enabled device-side.
        assertTrue(controllerSource.contains("if (!gyroEisTelemetryConsumerEnabled) return null"))
    }

    @Test
    fun handoffExposesReconcilerMfsrAndHealthFeeds() {
        assertTrue(controllerSource.contains("fun gyroExposureWindowHandoff()"))
        assertTrue(controllerSource.contains("fun gyroGlobalMotionForMfsr("))
        assertTrue(controllerSource.contains("fun gyroRingHealth("))
        assertTrue(controllerSource.contains("GyroExposureWindowReconciler.reconcile"))
        assertTrue(controllerSource.contains("GyroGlobalMotionTerm.betweenOisFused"))
        assertTrue(controllerSource.contains("GyroRingHealth.analyse"))
        // Hand-off consumes only the gyro clock domain, never the raw mixed ring.
        assertTrue(controllerSource.contains("snapshotGyroOldestFirst"))
        // Signature advertises a dormant, pixel-untouching consumer.
        assertTrue(controllerSource.contains("pixels=untouched"))
        assertTrue(controllerSource.contains("consumer=DORMANT"))
    }

    @Test
    fun telemetryIsSourceTaggedForClockDomainSeparation() {
        assertTrue(halSource.contains("enum class Source"))
        assertTrue(halSource.contains("source = Source.GYRO"))
        assertTrue(halSource.contains("source = Source.OIS_RESULT"))
        assertTrue(halSource.contains("fun snapshotGyroOldestFirst"))
        assertTrue(halSource.contains("fun latestOisSample"))
    }
}
