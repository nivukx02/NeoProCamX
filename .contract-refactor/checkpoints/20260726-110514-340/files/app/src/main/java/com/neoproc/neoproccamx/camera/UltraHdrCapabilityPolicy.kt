package com.neoproc.neoproccamx.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.util.Log

/**
 * UltraHdrCapabilityPolicy — Detects and configures Ultra HDR (Gain Map JPEG) capture.
 *
 * ═════════════════════════════════════════════════════════════════════════════════════
 * ABOUT ULTRA HDR (JPEG_R)
 * ═════════════════════════════════════════════════════════════════════════════════════
 * Ultra HDR is Google's ISO 21496-1 gain-map JPEG format, introduced in Android 14
 * (API 34).  A single file contains:
 *
 *   1. A standard SDR base JPEG that renders correctly on every existing viewer.
 *   2. A logarithmic gain map describing per-pixel highlight boost for HDR displays.
 *   3. Metadata (XMP) allowing viewers to reconstruct the HDR image quality-preservingly.
 *
 * On Android 14+ the platform exposes [ImageFormat.JPEG_R] so CameraX / Camera2 can
 * request an Ultra HDR capture directly from the HAL — no manual fusion required.
 *
 * ═════════════════════════════════════════════════════════════════════════════════════
 * FAIL-SAFE
 * ═════════════════════════════════════════════════════════════════════════════════════
 * Every device-dependent call is guarded.  If the OS or the specific camera ID does
 * not advertise JPEG_R support, the caller receives `supported == false` and falls
 * back to the existing JPEG capture pipeline with zero behaviour change.
 */
object UltraHdrCapabilityPolicy {

    private const val TAG = "UltraHdrCapabilityPolicy"

    /**
     * [ImageFormat.JPEG_R] constant value.  Referenced by name-and-value to remain
     * compilable on older SDKs; the runtime guard ensures we only use it when the
     * platform exposes the symbol.
     */
    const val FORMAT_JPEG_R = 0x1005

    data class UltraHdrSupport(
        val cameraId: String,
        val supported: Boolean,
        val hdrDynamicRange: Boolean,
        val reason: String = if (supported) "ok" else "unsupported"
    )

    /**
     * Determines whether Ultra HDR (JPEG_R) capture is supported for this camera.
     */
    fun probe(characteristics: CameraCharacteristics?, cameraId: String): UltraHdrSupport {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return UltraHdrSupport(cameraId, supported = false, hdrDynamicRange = false,
                reason = "Android < 14; JPEG_R unavailable")
        }
        if (characteristics == null) {
            return UltraHdrSupport(cameraId, supported = false, hdrDynamicRange = false,
                reason = "null characteristics")
        }
        return try {
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return UltraHdrSupport(cameraId, supported = false, hdrDynamicRange = false,
                    reason = "no SCALER_STREAM_CONFIGURATION_MAP")
            val sizes = map.getOutputSizes(FORMAT_JPEG_R)
            val supported = sizes != null && sizes.isNotEmpty()
            val hdrRange = queryHdrDynamicRange(characteristics)
            UltraHdrSupport(
                cameraId = cameraId,
                supported = supported,
                hdrDynamicRange = hdrRange,
                reason = if (supported) "JPEG_R reported ${sizes.size} sizes" else "JPEG_R sizes empty"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Ultra HDR probe failed for cameraId=$cameraId", t)
            UltraHdrSupport(cameraId, supported = false, hdrDynamicRange = false,
                reason = "exception: ${t.javaClass.simpleName}")
        }
    }

    private fun queryHdrDynamicRange(characteristics: CameraCharacteristics): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return try {
            val profiles = characteristics.get(
                CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES
            ) ?: return false
            profiles.supportedProfiles.isNotEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "Dynamic range profile query failed", t)
            false
        }
    }
}
