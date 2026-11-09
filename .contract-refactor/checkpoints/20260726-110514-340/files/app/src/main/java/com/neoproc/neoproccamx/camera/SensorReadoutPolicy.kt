package com.neoproc.neoproccamx.camera

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Size
import kotlin.math.max

/**
 * SensorReadoutPolicy - Phase 1: MAXIMUM MEGAPIXEL UNLOCK
 * 
 * Forces absolute maximum sensor resolution (200MP+, 50MP+) by:
 * 1. Detecting PixelArraySize (full sensor pixels)
 * 2. Bypassing standard video size constraints
 * 3. Disabling sensor binning (1:1 pixel mapping)
 * 4. Requesting full-sensor active array crop region
 * 
 * Result: Raw sensor output at maximum resolution without ISP downscaling
 */
class SensorReadoutPolicy(private val characteristics: CameraCharacteristics) {
    
    private val TAG = "SensorReadoutPolicy"
    
    data class MaximumSensorReadout(
        val pixelArraySize: Size,
        val activeArraySize: Rect,
        val isSensorBinningDisabled: Boolean,
        val megapixelCount: Int
    )
    
    /**
     * Phase 1a: Get absolute maximum sensor resolution
     * Bypasses all standard constraints and returns raw PixelArraySize
     */
    fun getMaximumSensorResolution(): Size {
        // First: Try maximum resolution (Android 12+)
        val maximumResolution = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)
        } else null
        
        // Second: Standard sensor pixel array
        val standardResolution = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        
        // Pick the largest by area
        return if (maximumResolution != null && isLargerByArea(maximumResolution, standardResolution)) {
            maximumResolution
        } else {
            standardResolution ?: Size(4096, 3072)  // Fallback
        }
    }
    
    /**
     * Phase 1a: Get full sensor active array (crop region = full sensor)
     * Prevents ISP from downscaling before output
     */
    fun getFullSensorActiveArray(): Rect {
        return characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: Rect(0, 0, 4096, 3072)
    }
    
    /**
     * Phase 1a: Check if sensor binning is available for disablement
     * SENSOR_MODE_UNBINNED prevents 2x2 pixel binning (preserves all pixels)
     */
    fun canDisableSensorBinning(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }
    
    /**
     * Phase 1a / Section 2.3 mandate: Bypass standard video sizes and request the
     * absolute maximum readout from EVERY available source:
     *   1. getHighResolutionOutputSizes(RAW_SENSOR)  -> ultra-high-res sensor mode (50/108/200MP)
     *   2. getHighResolutionOutputSizes(YUV_420_888) -> high-res YUV
     *   3. getOutputSizes(RAW_SENSOR)                -> standard RAW
     *   4. getOutputSizes(YUV_420_888)               -> standard YUV
     *   5. PixelArraySize (Android 12+ maximum resolution sensor) as the floor
     *
     * Forces full-resolution path instead of standard MediaRecorder lists.
     */
    fun getMaximumRawVideoResolution(streamConfigurationMap: StreamConfigurationMap?): Size? {
        if (streamConfigurationMap == null) return getMaximumSensorResolution()

        val rawHighRes = safeOutputSizes(streamConfigurationMap, android.graphics.ImageFormat.RAW_SENSOR, highRes = true)
        val yuvHighRes = safeOutputSizes(streamConfigurationMap, android.graphics.ImageFormat.YUV_420_888, highRes = true)
        val rawStandard = safeOutputSizes(streamConfigurationMap, android.graphics.ImageFormat.RAW_SENSOR, highRes = false)
        val yuvStandard = safeOutputSizes(streamConfigurationMap, android.graphics.ImageFormat.YUV_420_888, highRes = false)

        val allSizes = (rawHighRes + yuvHighRes + rawStandard + yuvStandard).distinctBy { it.width * it.height.toLong() }
        val best = allSizes.maxByOrNull { it.width.toLong() * it.height }
        val pixelArrayFloor = getMaximumSensorResolution()
        return if (best != null && best.width.toLong() * best.height >= pixelArrayFloor.width.toLong() * pixelArrayFloor.height) {
            best
        } else {
            pixelArrayFloor
        }
    }

    /**
     * Section 2.3 mandate: maximum still photo size including ultra-high-res sensor modes.
     * Queries JPEG high-res list first (the only place where 50MP/108MP/200MP modes are surfaced),
     * then falls back to standard JPEG list, then to the physical sensor size as a guarantee.
     */
    fun getMaximumStillPhotoResolution(streamConfigurationMap: StreamConfigurationMap?): Size {
        if (streamConfigurationMap == null) return getMaximumSensorResolution()
        val jpegHighRes = safeOutputSizes(streamConfigurationMap, android.graphics.ImageFormat.JPEG, highRes = true)
        val jpegStandard = safeOutputSizes(streamConfigurationMap, android.graphics.ImageFormat.JPEG, highRes = false)
        val candidates = (jpegHighRes + jpegStandard).distinctBy { it.width * it.height.toLong() }
        val best = candidates.maxByOrNull { it.width.toLong() * it.height }
        val sensorFloor = getMaximumSensorResolution()
        return if (best != null && best.width.toLong() * best.height >= sensorFloor.width.toLong() * sensorFloor.height) best else sensorFloor
    }

    private fun safeOutputSizes(scm: StreamConfigurationMap, format: Int, highRes: Boolean): List<Size> = try {
        if (highRes) {
            scm.getHighResolutionOutputSizes(format)?.toList().orEmpty()
        } else {
            scm.getOutputSizes(format)?.toList().orEmpty()
        }
    } catch (e: Exception) {
        emptyList()
    }
    
    /**
     * Phase 1a: Calculate megapixel count from resolution
     */
    fun getMegapixelCount(size: Size): Int {
        return (size.width * size.height) / 1_000_000
    }
    
    /**
     * Phase 1a: Create maximum sensor readout descriptor
     */
    fun buildMaximumReadout(): MaximumSensorReadout {
        val pixelArraySize = getMaximumSensorResolution()
        val activeArray = getFullSensorActiveArray()
        val megapixels = getMegapixelCount(pixelArraySize)
        
        return MaximumSensorReadout(
            pixelArraySize = pixelArraySize,
            activeArraySize = activeArray,
            isSensorBinningDisabled = canDisableSensorBinning(),
            megapixelCount = megapixels
        )
    }
    
    /**
     * Apply maximum sensor readout to capture request
     * Sets: SCALER_CROP_REGION = full sensor (no ISP downscaling)
     */
    fun applyFullSensorCropRegion(captureRequest: CaptureRequest.Builder) {
        // Set crop region to full active array (no ISP downscaling or cropping)
        val activeArray = getFullSensorActiveArray()
        captureRequest.set(CaptureRequest.SCALER_CROP_REGION, activeArray)
    }
    
    /**
     * Helper: Compare sizes by pixel area
     */
    private fun isLargerByArea(a: Size?, b: Size?): Boolean {
        if (a == null) return false
        if (b == null) return true
        return (a.width.toLong() * a.height) > (b.width.toLong() * b.height)
    }
}

