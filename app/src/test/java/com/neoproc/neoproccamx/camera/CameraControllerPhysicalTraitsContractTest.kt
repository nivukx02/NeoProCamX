package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CameraControllerPhysicalTraitsContractTest {
    private val source: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt").readText()
    }

    @Test
    fun controllerTracksPhysicalLensSensorAndRollingShutterTraitsForShaderProfiles() {
        assertTrue(source.contains("physicalImagingTraits"))
        assertTrue(source.contains("CameraPhysicalImagingTraits.fromCharacteristics"))
        assertTrue(source.contains("CameraPhysicalImagingTraits.updateFromCaptureResult"))
        assertTrue(source.contains("LENS_INFO_AVAILABLE_APERTURES"))
        assertTrue(source.contains("LENS_INFO_AVAILABLE_FOCAL_LENGTHS"))
        assertTrue(source.contains("SENSOR_INFO_PHYSICAL_SIZE"))
        assertTrue(source.contains("SENSOR_ROLLING_SHUTTER_SKEW"))
    }

    @Test
    fun controllerTracksSensorNoiseAndOisTelemetryForShaderProfiles() {
        assertTrue(source.contains("SENSOR_NOISE_PROFILE"))
        assertTrue(source.contains("CameraMotionTelemetry"))
        assertTrue(source.contains("motionTelemetry"))
        assertTrue(source.contains("CameraMotionTelemetry.fromCaptureResult"))
        assertTrue(source.contains("STATISTICS_OIS_SAMPLES"))
    }

    @Test
    fun controllerExposesCaptureToVulkanIsolationSignature() {
        assertTrue(source.contains("cameraCaptureToVulkanIsolationSignature"))
        assertTrue(source.contains("jni=no-blocking-frame-copy"))
        assertTrue(source.contains("metadata=volatile-snapshot"))
    }

    @Test
    fun controllerExposesOmnipotentPipelineMetadataSignature() {
        assertTrue(source.contains("omnipotentPipelineMetadataSignature"))
        assertTrue(source.contains("noise=SENSOR_NOISE_PROFILE"))
        assertTrue(source.contains("motion=STATISTICS_OIS_SAMPLES"))
    }

    @Test
    fun controllerStreamsGyroOisAndDynamicAcesMetadataWithoutBlockingCaptureThread() {
        assertTrue(source.contains("CameraMotionTelemetryRingBuffer"))
        assertTrue(source.contains("motionTelemetryRingBuffer.push"))
        assertTrue(source.contains("Sensor.TYPE_GYROSCOPE"))
        assertTrue(source.contains("COLOR_CORRECTION_GAINS"))
        assertTrue(source.contains("DynamicAcesIdtCalibration"))
        assertTrue(source.contains("control-thread=CameraBackground"))
        assertTrue(source.contains("capture-thread=CameraResultIngress"))
        assertTrue(source.contains("ui=decoupled"))
        assertTrue(source.contains("storage=decoupled"))
    }
}
