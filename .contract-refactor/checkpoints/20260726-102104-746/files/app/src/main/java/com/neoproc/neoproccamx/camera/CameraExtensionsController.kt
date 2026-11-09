package com.neoproc.neoproccamx.camera

import android.content.Context
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * CameraExtensionsController — Thin safe facade over [CameraExtensionCharacteristics].
 *
 * ═════════════════════════════════════════════════════════════════════════════════════
 * OVERVIEW
 * ═════════════════════════════════════════════════════════════════════════════════════
 * OEMs (Google/Pixel, Samsung, OnePlus, Xiaomi …) ship hardware-accelerated extensions
 * via the standard Android Camera2 "Camera Extensions" API (introduced in Android 11,
 * refined in 12/13/14).  These extensions include:
 *
 *   - NIGHT        — multi-frame low-light fusion tuned by the OEM
 *   - HDR          — single-shot high-dynamic-range
 *   - BOKEH        — depth-based background blur
 *   - BEAUTY       — AI face retouching
 *   - AUTOMATIC    — OEM's own scene-adaptive pipeline
 *
 * ═════════════════════════════════════════════════════════════════════════════════════
 * WHY A FACADE
 * ═════════════════════════════════════════════════════════════════════════════════════
 * 1. Single-line query for every extension capability with zero exception leakage.
 * 2. Stable [ExtensionKind] enum insulates the rest of the codebase from platform
 *    integer constants that may differ between vendor builds.
 * 3. Graceful fallback if the OS, CameraID, or OEM does not advertise extensions.
 *
 * All methods are safe on every API level; they silently report "unsupported" on
 * API < 31 or when the OEM has not shipped extensions.
 */
class CameraExtensionsController(private val context: Context) {

    companion object {
        private const val TAG = "CameraExtensionsController"
    }

    /** Stable NeoProCamX-facing enumeration of extension kinds. */
    enum class ExtensionKind {
        AUTOMATIC,
        HDR,
        NIGHT,
        BOKEH,
        FACE_RETOUCH
    }

    /** A resolved set of extensions supported by one camera ID. */
    data class SupportReport(
        val cameraId: String,
        val supported: Set<ExtensionKind>,
        val androidApiLevel: Int = Build.VERSION.SDK_INT
    ) {
        val hasAnyExtension: Boolean get() = supported.isNotEmpty()

        fun summary(): String = buildString {
            append("camera=$cameraId")
            append(" api=$androidApiLevel")
            append(" supported=[")
            append(supported.joinToString(","))
            append(']')
        }
    }

    private val manager: CameraManager? = try {
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    } catch (t: Throwable) {
        Log.e(TAG, "CameraManager unavailable", t)
        null
    }

    /**
     * Returns [SupportReport] describing the OEM camera extensions available for
     * the supplied camera ID.  Always succeeds — on unsupported devices the returned
     * [SupportReport.supported] set is empty.
     */
    fun queryExtensions(cameraId: String): SupportReport {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return SupportReport(cameraId, emptySet())
        }
        val cameraManager = manager
            ?: return SupportReport(cameraId, emptySet())

        return try {
            val characteristics = cameraManager.getCameraExtensionCharacteristics(cameraId)
            val supportedList = characteristics.supportedExtensions
            val kinds = supportedList.mapNotNull { platform -> mapPlatformConstant(platform) }.toSet()
            SupportReport(cameraId, kinds)
        } catch (t: Throwable) {
            Log.w(TAG, "Extension query failed for cameraId=$cameraId", t)
            SupportReport(cameraId, emptySet())
        }
    }

    /**
     * Convenience: query a specific extension for a camera ID.
     * @return true when the OEM advertises hardware-level support.
     */
    fun isExtensionSupported(cameraId: String, kind: ExtensionKind): Boolean =
        queryExtensions(cameraId).supported.contains(kind)

    /**
     * Translates a platform extension constant into the stable [ExtensionKind] enum.
     * Returns null for vendor-specific codes that do not have a canonical mapping.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun mapPlatformConstant(platform: Int): ExtensionKind? = when {
        platform == CameraExtensionCharacteristics.EXTENSION_AUTOMATIC -> ExtensionKind.AUTOMATIC
        platform == CameraExtensionCharacteristics.EXTENSION_HDR -> ExtensionKind.HDR
        platform == CameraExtensionCharacteristics.EXTENSION_NIGHT -> ExtensionKind.NIGHT
        platform == CameraExtensionCharacteristics.EXTENSION_BOKEH -> ExtensionKind.BOKEH
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            platform == CameraExtensionCharacteristics.EXTENSION_FACE_RETOUCH -> ExtensionKind.FACE_RETOUCH
        else -> null
    }

    /**
     * Translates [ExtensionKind] back to the platform constant for use with the Camera2
     * extensions APIs.  Callers should gate API usage behind API 31+.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    fun platformConstantOf(kind: ExtensionKind): Int = when (kind) {
        ExtensionKind.AUTOMATIC -> CameraExtensionCharacteristics.EXTENSION_AUTOMATIC
        ExtensionKind.HDR -> CameraExtensionCharacteristics.EXTENSION_HDR
        ExtensionKind.NIGHT -> CameraExtensionCharacteristics.EXTENSION_NIGHT
        ExtensionKind.BOKEH -> CameraExtensionCharacteristics.EXTENSION_BOKEH
        ExtensionKind.FACE_RETOUCH -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                CameraExtensionCharacteristics.EXTENSION_FACE_RETOUCH
            } else {
                throw UnsupportedOperationException("FACE_RETOUCH requires Android 13+")
            }
        }
    }
}
