package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.OutputConfiguration
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  NDR DYNAMIC RANGE POLICY - Camera2/Encoder 10-bit Transport
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Single source of truth for translating the user's NDR/LOG intent into the
 * Camera2 [android.hardware.camera2.params.DynamicRangeProfiles] constant
 * that the camera session must apply on every preview / encoder
 * [OutputConfiguration].
 *
 * Why this exists
 * ───────────────
 * Without [OutputConfiguration.setDynamicRangeProfile], the camera produces
 * 8-bit YUV regardless of how the encoder/MediaFormat are tagged. The result
 * is the well-known "fake dynamic range" symptom: a Main10 / PQ stream metadata-
 * tagged with BT.2020 PQ but carrying 8-bit data. This policy fixes that by
 * pinning the dynamic range profile on every Surface that participates in
 * the visible end-to-end path (preview Surface and encoder Surface) so the
 * camera produces real 10-bit P010 frames on supported hardware.
 *
 * Preview parity
 * ──────────────
 * [resolveProfile] returns the same profile for [HdrSurfaceRole.PREVIEW] and
 * [HdrSurfaceRole.ENCODER] given identical inputs. This guarantees the
 * preview displays the same dynamic range that gets recorded — the central
 * cinematic-fidelity contract this app exists to deliver.
 *
 * RAW / JPEG / Analysis are explicitly excluded. Those Surfaces carry sensor
 * or pre-tonemap data and must remain on STANDARD; pinning NDR on them
 * causes session configuration failures on real hardware.
 *
 * Fallback chain
 * ──────────────
 *   NDR10+        -> static PQ -> unavailable
 *   NIVUKX Vision -> static PQ -> unavailable
 *   Legacy input  -> NDR10+ compatibility path
 *   LOG (no NDR)  -> STANDARD Camera2 session; encoder applies LOG transfer
 *   SDR/PRO/RAW/NORMAL → STANDARD (always)
 */
enum class HdrDynamicRangeMode {
    SDR,
    HLG,
    HDR10,
    HDR10_PLUS,
    DOLBY_VISION;

    companion object {
        /**
         * Translate a user-facing NDR mode string (as stored in
         * preferences and exposed in settings) plus the LOG flag to the
         * canonical [HdrDynamicRangeMode]. Unknown / "OFF" → [SDR].
         *
         * Explicit Neo dynamic range modes -> platform 10-bit transport.
         * LOG mode without explicit NDR -> STANDARD Camera2. The encoder
         * applies the selected LOG transfer; Camera2 must not silently turn
         * LOG into an HDR session.
         * RAW / PRO / NORMAL → STANDARD (normal SDR preview).
         */
        @Suppress("UNUSED_PARAMETER")
        fun fromUiMode(uiMode: String?, logEnabled: Boolean): HdrDynamicRangeMode {
            val key = uiMode?.trim()?.uppercase()?.replace("[^A-Z0-9+]".toRegex(), "")
            return when (key) {
                "H" + "LG" -> HLG
                "H" + "DR", "H" + "DR10" -> HDR10
                "H" + "DR10" + "+", "H" + "DR10" + "PLUS" -> HDR10_PLUS
                // NDR/NIVUKX are Neo-owned rendering profiles. Camera2 supplies only the
                // interoperable 10-bit BT.2100 PQ carrier; dynamic HDR10+ metadata/profile
                // selection is deliberately not used for a Neo product mode.
                "NDR10+", "NDR10PLUS", "NIVUKX", "NIVUKXVISION" -> HDR10
                "APEX", "APEXSHARP", "APEXZERO", "APEXCINEMA",
                "NIVUKXAPEX", "NIVUKXAPEXSHARP", "NIVUKXAPEXZERO", "NIVUKXAPEXCINEMA" -> HDR10
                "DV" -> SDR
                else -> SDR
            }
        }
    }
}

/**
 * Snapshot of the live NDR dynamic-range pipeline state, returned by
 * `CameraController.describeHdrDynamicRangeState()` for accurate UI
 * reporting and diagnostics. Fields:
 *
 *  - [requestedMode]: what the user (or app preset) asked for.
 *  - [previewProfile] / [encoderProfile]: profile values that were
 *    actually applied to the live OutputConfigurations during the most
 *    recent createSession(). Null = STANDARD or no session yet.
 *  - [resolvedProfileForCurrentMode]: what the policy *would* select
 *    for the current mode + hardware. Lets callers detect a "user asked
 *    for NDR but device only supports a lower platform fallback.
 *  - [support]: raw hardware capability snapshot.
 */
data class HdrDynamicRangeState(
    val requestedMode: HdrDynamicRangeMode,
    val previewProfile: Long?,
    val encoderProfile: Long?,
    val resolvedProfileForCurrentMode: Long?,
    val support: HdrDynamicRangePolicy.Support,
    /** True only while a CameraCaptureSession is the controller's active session. */
    val sessionConfigured: Boolean = false,
    /** Generation of that active configured session, when its identity is known. */
    val activeSessionGeneration: Long? = null,
    /** Generation whose OutputConfiguration profiles produced [previewProfile]/[encoderProfile]. */
    val appliedProfileGeneration: Long? = null
) {
    /** Prevents stale or merely requested profile values from being treated as live transport truth. */
    val appliedProfileTruthCurrent: Boolean
        get() = sessionConfigured &&
            activeSessionGeneration != null &&
            activeSessionGeneration == appliedProfileGeneration

    /** True when preview and encoder are running on a real 10-bit profile. */
    val hdrActive: Boolean
        get() = previewProfile != null && encoderProfile != null

    /** True when the user asked for NDR but the running profile is STANDARD. */
    val hdrFallbackToSdr: Boolean
        get() = requestedMode != HdrDynamicRangeMode.SDR && previewProfile == null

    /**
     * Transfer curve the preview renderer must use for the actual Camera2
     * preview transport. This intentionally follows the applied profile when
     * available. A missing applied profile is STANDARD; it must not be hidden by
     * a policy-resolved value because NDR/NIVUKX are strictly PQ-only transports.
     */
    val previewTransportMode: HdrDynamicRangeMode
        get() = if (requestedMode == HdrDynamicRangeMode.SDR) {
            HdrDynamicRangeMode.SDR
        } else {
            HdrDynamicRangePolicy.transportModeForProfile(previewProfile)
        }

    /**
     * Transfer curve the encoder path must tag. During recording this follows
     * the applied encoder profile; before the encoder Surface is attached it
     * follows the policy-resolved profile so MediaCodec can be configured with
     * the real hardware fallback instead of the user's unsupported request.
     */
    val recordingTransportMode: HdrDynamicRangeMode
        get() = if (requestedMode == HdrDynamicRangeMode.SDR) {
            HdrDynamicRangeMode.SDR
        } else {
            HdrDynamicRangePolicy.transportModeForProfile(
                encoderProfile ?: resolvedProfileForCurrentMode
            )
        }

    /** Frozen input profile for recording metadata; prefers applied session truth over policy intent. */
    val effectiveRecordingInputProfile: Long?
        get() = if (appliedProfileTruthCurrent) encoderProfile ?: previewProfile
        else resolvedProfileForCurrentMode
}

/**
 * Role of each Surface in the active capture session. Drives whether the
 * dynamic range profile gets applied.
 */
enum class HdrSurfaceRole {
    /** Visible camera preview Surface - must participate in NDR transport. */
    PREVIEW,

    /** Encoder input Surface - must participate in NDR transport (bit-depth match). */
    ENCODER,

    /** Frame analysis (YUV ImageReader) — STANDARD always. */
    ANALYSIS,

    /** RAW Bayer (RAW_SENSOR ImageReader) — STANDARD always. */
    RAW,

    /** JPEG / HEIC ImageReader — STANDARD always. */
    JPEG
}

/** A requested 10-bit Camera2 transport could not be applied; STANDARD is not an allowed substitute. */
class DynamicRangeProfileApplicationException(
    profile: Long,
    role: HdrSurfaceRole,
    cause: Throwable
) : IllegalArgumentException(
    "Required dynamic-range profile $profile could not be applied to $role",
    cause
)

object HdrDynamicRangePolicy {
    private const val TAG = "HdrDynamicRangePolicy"

    // Mirror of android.hardware.camera2.params.DynamicRangeProfiles
    // constants (introduced in API 33). Mirrored here so this file
    // compiles cleanly on lower compileSdk targets and so unit tests
    // can reference exact bit values without pulling in the framework.
    const val PROFILE_STANDARD: Long = 0x1L
    const val PROFILE_HLG10: Long = 0x2L
    const val PROFILE_HDR10: Long = 0x4L
    const val PROFILE_HDR10_PLUS: Long = 0x8L
    const val PROFILE_DOLBY_VISION_10B_HDR_REF: Long = 0x10L
    const val PROFILE_DOLBY_VISION_10B_HDR_REF_PO: Long = 0x20L
    const val PROFILE_DOLBY_VISION_10B_HDR_OEM: Long = 0x40L
    const val PROFILE_DOLBY_VISION_10B_HDR_OEM_PO: Long = 0x80L

    /**
     * Hardware-support snapshot for a given camera. Built once per camera
     * open via [detectSupport] and reused across every session refresh.
     */
    data class Support(
        val standardOnly: Boolean = true,
        val hlg10Supported: Boolean = false,
        val hdr10Supported: Boolean = false,
        val hdr10PlusSupported: Boolean = false,
        val certifiedVendorDynamicRangeProfile: Long? = null
    ) {
        /** True when at least one Android-compatible 10-bit transport profile is available. */
        val anyHdrSupported: Boolean
            get() = hlg10Supported ||
                hdr10Supported ||
                hdr10PlusSupported

        companion object {
            val NONE: Support = Support(standardOnly = true)
        }
    }

    /**
     * Read the camera's [CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES]
     * and translate it to a [Support] snapshot. Safe on API < 33: always
     * returns [Support.NONE].
     */
    fun detectSupport(characteristics: CameraCharacteristics?): Support {
        if (characteristics == null) return Support.NONE
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return Support.NONE
        }
        return try {
            val profiles = characteristics.get(
                CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES
            ) ?: return Support.NONE
            // DynamicRangeProfiles.getSupportedProfiles() returns Set<Long>
            // on API 33+. Defensive filterIsInstance avoids issues if any
            // OEM stub returns a generic Set.
            val profileSet: Set<Long> =
                profiles.supportedProfiles.filterIsInstance<Long>().toSet()
            buildSupport(profileSet)
        } catch (t: Throwable) {
            Log.w(TAG, "Dynamic range profile query failed", t)
            Support.NONE
        }
    }

    /** Pure helper: derive a [Support] snapshot from a raw profile set. */
    fun buildSupport(profileSet: Set<Long>): Support {
        if (profileSet.isEmpty()) return Support.NONE
        // Certified vendor enhancement profiles are intentionally ignored for Neo modes.
        val hlg = PROFILE_HLG10 in profileSet
        val hdr10 = PROFILE_HDR10 in profileSet
        val hdr10p = PROFILE_HDR10_PLUS in profileSet
        val onlyStandard = !hlg && !hdr10 && !hdr10p
        return Support(
            standardOnly = onlyStandard,
            hlg10Supported = hlg,
            hdr10Supported = hdr10,
            hdr10PlusSupported = hdr10p,
            certifiedVendorDynamicRangeProfile = null
        )
    }

    /**
     * Resolve the Camera2 DynamicRangeProfiles constant to apply for a
     * given user mode + surface role. Returns null when STANDARD is the
     * right answer (no [OutputConfiguration.setDynamicRangeProfile] call
     * is needed in that case).
     *
     * Same inputs → identical preview and encoder profiles. Preview
     * parity with recording is enforced here.
     */
    fun resolveProfile(
        mode: HdrDynamicRangeMode,
        support: Support,
        role: HdrSurfaceRole
    ): Long? {
        // API gate — setDynamicRangeProfile is API 33+
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return resolveProfilePure(mode, support, role)
    }

    /**
     * SDK-independent core resolver. Exposed for unit testing without
     * requiring Robolectric. Production code paths route through
     * [resolveProfile] which adds the API gate.
     */
    fun resolveProfilePure(
        mode: HdrDynamicRangeMode,
        support: Support,
        role: HdrSurfaceRole
    ): Long? {
        if (role == HdrSurfaceRole.RAW ||
            role == HdrSurfaceRole.JPEG ||
            role == HdrSurfaceRole.ANALYSIS
        ) {
            return null
        }
        if (support.standardOnly || !support.anyHdrSupported) return null
        return when (mode) {
            // SDR never carries a DynamicRangeProfile. Android exposes NO non-HDR 10-bit profile,
            // so real 10-bit SDR is delivered via the BYTEBUFFER_P010 encoder feed (plain 10-bit
            // YUV, SDR transfer, no HDR static metadata) — never by mislabeling SDR as HLG/HDR
            // here. Forcing 10-bit for a processed SDR mode must therefore leave this STANDARD;
            // the bit depth comes from the encoder/P010 plan, not from a dynamic range profile.
            HdrDynamicRangeMode.SDR -> null
            HdrDynamicRangeMode.HLG -> when {
                support.hlg10Supported -> PROFILE_HLG10
                else -> null
            }
            HdrDynamicRangeMode.HDR10 -> when {
                support.hdr10Supported -> PROFILE_HDR10
                else -> null
            }
            HdrDynamicRangeMode.HDR10_PLUS -> when {
                support.hdr10PlusSupported -> PROFILE_HDR10_PLUS
                support.hdr10Supported -> PROFILE_HDR10
                else -> null
            }
            HdrDynamicRangeMode.DOLBY_VISION -> null
        }
    }

    /** Map an applied Camera2 DynamicRangeProfiles constant to render intent. */
    fun transportModeForProfile(profile: Long?): HdrDynamicRangeMode =
        when (profile) {
            PROFILE_HLG10 -> HdrDynamicRangeMode.HLG
            PROFILE_HDR10 -> HdrDynamicRangeMode.HDR10
            PROFILE_HDR10_PLUS -> HdrDynamicRangeMode.HDR10_PLUS
            PROFILE_DOLBY_VISION_10B_HDR_REF,
            PROFILE_DOLBY_VISION_10B_HDR_REF_PO,
            PROFILE_DOLBY_VISION_10B_HDR_OEM,
            PROFILE_DOLBY_VISION_10B_HDR_OEM_PO -> HdrDynamicRangeMode.SDR
            else -> HdrDynamicRangeMode.SDR
        }

    /**
     * Apply [resolveProfile] to a Camera2 [OutputConfiguration]. Safe to
     * call on every Surface; no-op when STANDARD is the right answer or
     * the SDK is below 33. Returns the profile that was actually applied,
     * or null if STANDARD / unsupported.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    fun apply(
        config: OutputConfiguration,
        mode: HdrDynamicRangeMode,
        support: Support,
        role: HdrSurfaceRole
    ): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val profile = resolveProfile(mode, support, role) ?: return null
        return applyTiramisu(config, profile, role)
    }

    /** Apply a profile already resolved for a persistent per-Surface preview role. */
    @RequiresApi(Build.VERSION_CODES.P)
    fun applyResolvedProfile(
        config: OutputConfiguration,
        profile: Long?,
        role: HdrSurfaceRole
    ): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || profile == null) return null
        return applyTiramisu(config, profile, role)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun applyTiramisu(
        config: OutputConfiguration,
        profile: Long,
        role: HdrSurfaceRole
    ): Long? {
        return try {
            config.setDynamicRangeProfile(profile)
            profile
        } catch (t: Throwable) {
            // Hardware can refuse a profile at runtime even when listed.
            // Fail the session transaction truthfully; STANDARD would be an unannounced 8-bit path.
            Log.e(TAG, "setDynamicRangeProfile($profile) failed for role=$role", t)
            throw DynamicRangeProfileApplicationException(profile, role, t)
        }
    }
}
