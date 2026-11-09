package com.neoproc.neoproccamx.camera

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.OutputConfiguration
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * StreamUseCasePolicy — Applies Android 13+ `STREAM_USE_CASE_*` hints to [OutputConfiguration]s.
 *
 * ═════════════════════════════════════════════════════════════════════════════════════
 * PURPOSE
 * ═════════════════════════════════════════════════════════════════════════════════════
 * The STREAM_USE_CASE metadata tag (introduced in API 33 / Android 13) lets the camera
 * HAL optimise sensor readout timing, buffer allocation, and ISP power state for the
 * specific role each stream plays.  For pro video recording this is transformative:
 * the HAL can enable longer-line-time sensor readout, disable preview-biased tuning,
 * and minimise rolling-shutter skew.
 *
 * Recognised use cases on Android 13+:
 *   - DEFAULT                    : Legacy behaviour (no hint).
 *   - PREVIEW                    : Low-latency preview.
 *   - STILL_CAPTURE              : Full-res still shot.
 *   - VIDEO_RECORD               : Sustained recording (this is the cinema hint).
 *   - VIDEO_CALL                 : Low-latency video call (bias: encoder).
 *   - PREVIEW_VIDEO_STILL        : Shared preview + video + snapshot.
 *   - VENDOR_START+              : OEM-specific extensions.
 *
 * ═════════════════════════════════════════════════════════════════════════════════════
 * FAIL-SAFE
 * ═════════════════════════════════════════════════════════════════════════════════════
 * Every device-dependent API call is guarded by a version check and a try/catch.
 * If the hardware or OS does not support the requested use case, the hint is silently
 * dropped and the caller continues with DEFAULT behaviour — no crashes, no regressions.
 */
object StreamUseCasePolicy {

    private const val TAG = "StreamUseCasePolicy"

    /** Logical stream role used by NeoProCamX surfaces. */
    enum class StreamRole {
        /** SurfaceTexture / preview view. */
        PREVIEW,
        /** YUV analysis reader used by FramePipelineManager. */
        ANALYSIS,
        /** JPEG ImageReader. */
        STILL_JPEG,
        /** RAW_SENSOR DNG reader. */
        STILL_RAW,
        /** Hardware import reader into Vulkan / MediaCodec. */
        HARDWARE_IMPORT,
        /** Encoder input surface during sustained video recording. */
        VIDEO_RECORD,
        /** Combined preview + video + still snapshot surface. */
        PREVIEW_VIDEO_STILL,
        /** ZSL ring buffer. */
        ZSL
    }

    /**
     * Returns true when the current device reports support for the supplied use case.
     * On API < 33 always returns false.
     */
    @JvmStatic
    fun isUseCaseSupported(
        characteristics: CameraCharacteristics?,
        useCase: Long
    ): Boolean {
        if (characteristics == null) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return try {
            val supported = characteristics
                .get(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES)
                ?: return false
            supported.any { it == useCase }
        } catch (t: Throwable) {
            Log.w(TAG, "isUseCaseSupported query failed", t)
            false
        }
    }

    /**
     * Resolves the preferred STREAM_USE_CASE constant for the given logical role,
     * taking into account device capabilities.  Falls back to DEFAULT when the preferred
     * hint is not supported.
     *
     * @return the constant to pass to [OutputConfiguration.setStreamUseCase], or null
     *         if the OS / device does not support the feature at all.
     */
    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun resolveUseCase(
        characteristics: CameraCharacteristics?,
        role: StreamRole
    ): Long? {
        if (characteristics == null) return null

        val preferredLong = preferredUseCaseForRole(role)
        return if (isUseCaseSupported(characteristics, preferredLong)) {
            preferredLong
        } else {
            Log.d(TAG, "Use case $preferredLong unsupported for role=$role; falling back to DEFAULT")
            CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_DEFAULT.toLong()
        }
    }

    @JvmStatic
    @SuppressLint("InlinedApi")
    fun preferredUseCaseForRole(role: StreamRole): Long =
        when (role) {
            StreamRole.PREVIEW -> CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW
            StreamRole.ANALYSIS -> CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW
            StreamRole.STILL_JPEG -> CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_STILL_CAPTURE
            StreamRole.STILL_RAW -> CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_STILL_CAPTURE
            StreamRole.HARDWARE_IMPORT -> CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW
            StreamRole.VIDEO_RECORD -> CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD
            StreamRole.PREVIEW_VIDEO_STILL -> CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW_VIDEO_STILL
            StreamRole.ZSL -> CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_STILL_CAPTURE
        }.toLong()

    /**
     * Applies the preferred STREAM_USE_CASE hint to an [OutputConfiguration].  No-op on
     * API < 33 or when the hardware does not support the requested use case.
     *
     * @return true when a non-DEFAULT hint was applied.
     */
    @JvmStatic
    fun apply(
        configuration: OutputConfiguration,
        characteristics: CameraCharacteristics?,
        role: StreamRole
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false

        val useCase = resolveUseCase(characteristics, role) ?: return false
        if (useCase == CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_DEFAULT.toLong()) return false

        return try {
            configuration.streamUseCase = useCase
            Log.d(TAG, "Applied STREAM_USE_CASE=$useCase role=$role")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to apply STREAM_USE_CASE=$useCase role=$role", t)
            false
        }
    }
}
