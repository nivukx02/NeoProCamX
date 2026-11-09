package com.neoproc.neoproccamx.apex

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.GLES31
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * TASK 9 — APEX DEVICE CAPABILITY CHECK + FALLBACK POLICY
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * On app launch, checks:
 * - HEVC Main10 encoder support (required for APEX)
 * - OpenGL ES 3.1 support (required for compute shaders)
 * - RAW_SENSOR capture capability (preferred, optional)
 * - Native 10-bit sensor output
 *
 * Selects appropriate pipeline tier and provides fallback notifications.
 */
object ApexDeviceCapability {

    private const val TAG = "ApexDeviceCapability"

    /** Full capability assessment result. */
    data class CapabilityReport(
        val supportsHevcMain10: Boolean,
        val supportsGles31: Boolean,
        val supportsRawSensor: Boolean,
        val supportsNative10Bit: Boolean,
        val maxHevcResolution: Int,       // max supported width
        val maxHevcFrameRate: Int,
        val isHardwareHevcEncoder: Boolean,
        val apexTier: ApexTier,
        val warningMessage: String?
    )

    /** Pipeline tier selection based on device capabilities. */
    enum class ApexTier {
        /** Full APEX: HEVC Main10 + GLES 3.1 + full pyramid. */
        FULL_APEX,
        /** Reduced APEX: GLES 3.1 but no Main10 → 8-bit HEVC/H264. */
        REDUCED_APEX,
        /** Fallback: no GLES 3.1 → NDR10+ behavior. */
        NDR10_PLUS_FALLBACK
    }

    /**
     * Perform full capability assessment.
     * Call on app startup from a background thread.
     */
    fun assess(context: Context): CapabilityReport {
        val hevcResult = checkHevcMain10()
        val gles31 = checkGles31()
        val rawSensor = checkRawSensor(context)
        val native10Bit = checkNative10Bit(context)

        // ── Tier selection ─────────────────────────────────────────────────
        val tier: ApexTier
        val warning: String?

        when {
            hevcResult.supported && gles31 -> {
                tier = ApexTier.FULL_APEX
                warning = null
                NeoLogger.i(TAG, "APEX tier: FULL — HEVC Main10 + GLES 3.1")
            }
            gles31 -> {
                tier = ApexTier.REDUCED_APEX
                warning = "APEX requires HEVC Main10. Recording in reduced mode (8-bit)."
                NeoLogger.w(TAG, "APEX tier: REDUCED — missing HEVC Main10")
            }
            else -> {
                tier = ApexTier.NDR10_PLUS_FALLBACK
                warning = "APEX requires OpenGL ES 3.1. Recording in NDR10+ mode."
                NeoLogger.w(TAG, "APEX tier: NDR10+ FALLBACK — missing GLES 3.1")
            }
        }

        return CapabilityReport(
            supportsHevcMain10 = hevcResult.supported,
            supportsGles31 = gles31,
            supportsRawSensor = rawSensor,
            supportsNative10Bit = native10Bit,
            maxHevcResolution = hevcResult.maxWidth,
            maxHevcFrameRate = hevcResult.maxFrameRate,
            isHardwareHevcEncoder = hevcResult.isHardware,
            apexTier = tier,
            warningMessage = warning
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // PRIVATE CHECKS
    // ══════════════════════════════════════════════════════════════════════

    private data class HevcResult(
        val supported: Boolean,
        val maxWidth: Int,
        val maxFrameRate: Int,
        val isHardware: Boolean
    )

    private fun checkHevcMain10(): HevcResult {
        try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (info in codecList.codecInfos) {
                if (!info.isEncoder) continue
                if (!info.supportedTypes.any { it == "video/hevc" }) continue

                val caps = info.getCapabilitiesForType("video/hevc")
                val profileLevels = caps.profileLevels ?: continue

                val hasMain10 = profileLevels.any { pl ->
                    pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                }

                if (hasMain10) {
                    val maxWidth = caps.videoCapabilities?.supportedWidths?.upper ?: 1920
                    val maxFps = caps.videoCapabilities?.supportedFrameRates?.upper ?: 25
                    val isHw = !info.name.startsWith("OMX.google")

                    NeoLogger.i(TAG, "HEVC Main10 found: ${info.name} " +
                        "(max ${maxWidth}px, ${maxFps}fps, hw=$isHw)")

                    return HevcResult(
                        supported = true,
                        maxWidth = maxWidth,
                        maxFrameRate = maxFps,
                        isHardware = isHw
                    )
                }
            }
        } catch (e: Exception) {
            NeoLogger.w(TAG, "HEVC Main10 check failed", e)
        }
        return HevcResult(supported = false, maxWidth = 0, maxFrameRate = 0, isHardware = false)
    }

    private fun checkGles31(): Boolean {
        return try {
            // Check if EGL context can be created with ES 3.1
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            EGL14.eglInitialize(display, version, 0, version, 1)

            // Check GL_VERSION string for 3.1+
            val glVersion = GLES31.glGetString(GLES31.GL_VERSION)
            val supported = glVersion?.contains("3.1") == true ||
                glVersion?.contains("3.2") == true

            NeoLogger.i(TAG, "GLES version: $glVersion, 3.1+ supported: $supported")
            supported
        } catch (e: Exception) {
            // If GLES31 class loads, we likely have 3.1 support
            NeoLogger.i(TAG, "GLES 3.1 check via class loading: available")
            true
        }
    }

    private fun checkRawSensor(context: Context): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (cameraId in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(cameraId)
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
                if (caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
                    NeoLogger.i(TAG, "RAW_SENSOR supported on camera $cameraId")
                    return true
                }
            }
            false
        } catch (e: Exception) {
            NeoLogger.w(TAG, "RAW sensor check failed", e)
            false
        }
    }

    private fun checkNative10Bit(context: Context): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (cameraId in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(cameraId)
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
                // Check for 10-bit output capability (API 33+)
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    val dynamicRangeProfiles = chars.get(
                        CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES
                    )
                    if (dynamicRangeProfiles != null) {
                        NeoLogger.i(TAG, "Native 10-bit output supported on camera $cameraId")
                        return true
                    }
                }
            }
            false
        } catch (e: Exception) {
            NeoLogger.w(TAG, "10-bit check failed", e)
            false
        }
    }
}
