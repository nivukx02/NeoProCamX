package com.neoproc.neoproccamx.ui

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual HAL metadata, no camera recording or inferred image/FPS claim. */
@RunWith(AndroidJUnit4::class)
class CameraMetadataSnapshotInstrumentedTest {
    private fun characteristics(): CameraCharacteristics {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = requireNotNull(context.getSystemService(CameraManager::class.java))
        val id = manager.cameraIdList.first()
        return manager.getCameraCharacteristics(id)
    }

    @Test fun samePhysicalSnapshotReusesMetadataWithoutWeakeningCapabilities() {
        val physical = characteristics()
        val capabilities = physical.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.toSet().orEmpty()
        val first = VideoModePipelineContractPolicy.resolveHardwareCapabilities(physical, true)
        assertEquals(capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW), first.rawSupported)
        assertEquals(capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR), first.manualSensorSupported)
        assertEquals(physical.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList().orEmpty(), first.awbAvailableModes)
        val started = SystemClock.elapsedRealtimeNanos()
        repeat(15_000) {
            assertSame(first, VideoModePipelineContractPolicy.resolveHardwareCapabilities(physical, true))
        }
        Log.i("CameraMetadataSnapshot", "ACTUAL_HAL_CACHE calls=15000 elapsedNs=" +
            "${SystemClock.elapsedRealtimeNanos() - started} sameSnapshot=true cameraFps=NOT_TESTED")
    }

    @Test fun gpuCapabilityChangesRevalidateAndRecoverOriginalMetadata() {
        val physical = characteristics()
        val original = VideoModePipelineContractPolicy.resolveHardwareCapabilities(physical, true)
        val unavailable = VideoModePipelineContractPolicy.resolveHardwareCapabilities(physical, false)
        assertNotSame(original, unavailable)
        assertFalse(unavailable.tenBitSupported)
        assertFalse(unavailable.tenBitEncoderSupported)
        assertFalse(unavailable.fp16Supported)
        val restored = VideoModePipelineContractPolicy.resolveHardwareCapabilities(physical, true)
        assertNotSame(original, restored)
        assertEquals(original, restored)
    }

    @Test fun missingCharacteristicsDoNotReusePreviousPhysicalCapabilities() {
        val first = VideoModePipelineContractPolicy.resolveHardwareCapabilities(characteristics(), true)
        val missing = VideoModePipelineContractPolicy.resolveHardwareCapabilities(null, false)
        assertNotSame(first, missing)
        assertFalse(missing.tenBitSupported)
        assertFalse(missing.tenBitEncoderSupported)
    }

    @Test fun geometryCacheRetainsActualSensorAndCalibrationIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = requireNotNull(context.getSystemService(CameraManager::class.java))
        val id = manager.cameraIdList.first()
        val physical = manager.getCameraCharacteristics(id)
        val cache = PhysicalCameraGeometryIdentityCache()
        val identity = cache.resolve(physical, id)
        val active = physical.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val pixels = physical.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        assertTrue(identity.startsWith("$id|facing=${physical.get(CameraCharacteristics.LENS_FACING) ?: -1}"))
        assertTrue(identity.contains("|orientation=${physical.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: -1}"))
        assertTrue(identity.contains("|active=" + if (active == null) "UNKNOWN" else
            "${active.left},${active.top},${active.right},${active.bottom}"))
        assertTrue(identity.contains("|pixels=" + if (pixels == null) "UNKNOWN" else
            "${pixels.width}x${pixels.height}"))
        val calibration = listOf(
            CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1,
            CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2,
            CameraCharacteristics.SENSOR_COLOR_TRANSFORM1,
            CameraCharacteristics.SENSOR_COLOR_TRANSFORM2,
            CameraCharacteristics.SENSOR_FORWARD_MATRIX1,
            CameraCharacteristics.SENSOR_FORWARD_MATRIX2
        ).joinToString(":") { (physical.get(it)?.hashCode() ?: 0).toString() }
        assertTrue(identity.endsWith("|calibration=$calibration"))
        repeat(15_000) { assertSame(identity, cache.resolve(physical, id)) }
        assertNotSame(identity, cache.resolve(null, id))
        assertTrue(cache.resolve(physical, "other-camera").startsWith("other-camera|"))
        assertEquals(identity, cache.resolve(physical, id))
        Log.i("CameraMetadataSnapshot", "ACTUAL_HAL_GEOMETRY calls=15000 unchanged=true cameraFps=NOT_TESTED")
    }
}
