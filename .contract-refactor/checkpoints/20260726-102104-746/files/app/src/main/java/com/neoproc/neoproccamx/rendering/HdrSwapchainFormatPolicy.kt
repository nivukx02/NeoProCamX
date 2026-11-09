package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.camera.HdrDynamicRangeMode

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  HDR SWAPCHAIN FORMAT POLICY  —  10-bit / HDR colourspace selection
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Authoritative decision rule for picking a Vulkan swapchain
 * `(VkFormat, VkColorSpaceKHR)` pair that delivers the user-selected HDR
 * dynamic range mode end-to-end.
 *
 * Why this exists
 * ───────────────
 * The Phase 1 fix made Camera2 produce real 10-bit P010 frames, but those
 * frames still have to survive the GPU compositor on the way to the
 * display and to the encoder input surface. If the swapchain is created
 * with an 8-bit format, all downstream precision is lost. If it is
 * created with a 10-bit format but an SDR colourspace, the system
 * compositor tone-maps the HDR signal down to SDR brightness on capable
 * panels — the classic "10-bit but everything looks dim" symptom.
 *
 * This policy encodes the canonical decision tree as pure Kotlin so it
 * can be unit-tested on the JVM without needing a Vulkan loader. The
 * native C++ side (`vulkan_bridge.cpp::chooseSurfaceFormat`) implements
 * the same algorithm.
 *
 * Inputs
 * ──────
 *  - The list of `(format, colorSpace)` pairs reported by
 *    `vkGetPhysicalDeviceSurfaceFormatsKHR`. The Android Vulkan loader
 *    only exposes HDR colour-space enums (PQ, HLG, BT.2020 linear) when
 *    the instance extension `VK_EXT_swapchain_colorspace` is enabled —
 *    so this policy will only see them if the bridge has opted in.
 *  - The active [HdrDynamicRangeMode] (the same one used by the camera
 *    pipeline in Phase 1).
 *
 * Output
 * ──────
 * A single `(format, colorSpace)` pair. Returns the legal
 * "STANDARD UNDEFINED → 8-bit BGRA + sRGB" fallback when the input list
 * is the Vulkan-canonical "format=UNDEFINED" sentinel.
 */
object HdrSwapchainFormatPolicy {

    // ── Vulkan VkFormat constants (mirrored to keep this file JVM-pure) ──────
    // These are ordinary `int` enum values from the Vulkan headers; we
    // mirror only the formats that appear in the decision tree.
    const val VK_FORMAT_UNDEFINED: Int = 0
    const val VK_FORMAT_R8G8B8A8_UNORM: Int = 37
    const val VK_FORMAT_R8G8B8A8_SRGB: Int = 43
    const val VK_FORMAT_B8G8R8A8_UNORM: Int = 44
    const val VK_FORMAT_B8G8R8A8_SRGB: Int = 50
    const val VK_FORMAT_A2B10G10R10_UNORM_PACK32: Int = 64
    const val VK_FORMAT_A2R10G10B10_UNORM_PACK32: Int = 58
    const val VK_FORMAT_R16G16B16A16_SFLOAT: Int = 97

    // ── Vulkan VkColorSpaceKHR constants (subset) ────────────────────────────
    // The HDR enums require VK_EXT_swapchain_colorspace.
    const val VK_COLOR_SPACE_SRGB_NONLINEAR_KHR: Int = 0
    const val VK_COLOR_SPACE_DISPLAY_P3_NONLINEAR_EXT: Int = 1000104001
    const val VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT: Int = 1000104002
    const val VK_COLOR_SPACE_DISPLAY_P3_LINEAR_EXT: Int = 1000104003
    const val VK_COLOR_SPACE_BT2020_LINEAR_EXT: Int = 1000104007
    const val VK_COLOR_SPACE_HDR10_ST2084_EXT: Int = 1000104008
    const val VK_COLOR_SPACE_HDR10_HLG_EXT: Int = 1000104010
    const val VK_COLOR_SPACE_EXTENDED_SRGB_NONLINEAR_EXT: Int = 1000104014

    /**
     * Combined VkSurfaceFormatKHR — we use a tiny data class instead of
     * pulling in a Vulkan-bound type because this file must compile on
     * the JVM side.
     */
    data class SurfaceFormat(val format: Int, val colorSpace: Int)

    enum class SurfaceRole {
        DISPLAY,
        ENCODER
    }

    /**
     * Result of [resolve] — the chosen format plus a small diagnostic
     * stating which tier of the decision tree produced it. The tier
     * label is surfaced in capability-summary logs so we can tell from
     * a bug report whether HDR was actually engaged or whether we fell
     * back to SDR.
     */
    data class Resolution(
        val format: SurfaceFormat,
        val tier: Tier,
        val surfaceRole: SurfaceRole = SurfaceRole.DISPLAY,
        val formatBitDepth: Int = 0,
        val tenBitSdrDeliveryCertified: Boolean = false,
        val deliveryIdentity: String = ""
    )

    /**
     * Decision tiers, ordered from "best for the requested mode" to
     * "last-resort fallback". Tests assert the exact tier reached so
     * regressions in selection priority are caught immediately.
     */
    enum class Tier {
        /** 10-bit packed format paired with the matching HDR EOTF colourspace. */
        TEN_BIT_HDR_NATIVE,

        /** FP16 paired with extended-sRGB linear (still HDR-capable). */
        FP16_HDR_EXTENDED_LINEAR,

        /** 10-bit packed format with sRGB colourspace (system tone-maps). */
        TEN_BIT_SRGB_FALLBACK,

        /** 10-bit packed SDR format selected specifically for encoder delivery. */
        TEN_BIT_SDR_ENCODER,

        /**
         * 10-bit packed SDR format selected specifically for DISPLAY (preview) delivery.
         * sRGB-tagged, never PQ/HLG — keeps SDR intent while giving the preview 10-bit tonal
         * precision (dithered to 1023 levels) so far-field gradients/texture don't 8-bit band.
         */
        TEN_BIT_SDR_DISPLAY,

        /** FP16 with sRGB colourspace. */
        FP16_SRGB_FALLBACK,

        /** Standard 8-bit sRGB — the SDR baseline. */
        EIGHT_BIT_SRGB,

        /**
         * The driver returned only the `(UNDEFINED, anything)` sentinel —
         * the Vulkan-canonical signal that the implementation will accept
         * any format. We hand back A2B10G10R10 + sRGB for HDR modes and
         * BGRA8 + sRGB for SDR.
         */
        UNDEFINED_SENTINEL,

        /**
         * No format matched any tier — handed back the first available
         * pair as a defensive fallback. Indicates a malformed driver
         * report; logged loudly elsewhere.
         */
        DRIVER_FIRST_AVAILABLE
    }

    /**
     * Pick the best `(format, colorSpace)` pair for the given HDR mode.
     */
    fun resolve(
        availableFormats: List<SurfaceFormat>,
        mode: HdrDynamicRangeMode,
        surfaceRole: SurfaceRole = SurfaceRole.DISPLAY,
        requireTenBitSdr: Boolean = false,
        preferTenBitSdrDisplay: Boolean = false
    ): Resolution {
        // Vulkan signals "any format accepted" by returning a single
        // entry with format = UNDEFINED. Honour the contract.
        if (availableFormats.size == 1 &&
            availableFormats.first().format == VK_FORMAT_UNDEFINED
        ) {
            val sentinel = if (
                mode == HdrDynamicRangeMode.SDR &&
                !(surfaceRole == SurfaceRole.ENCODER && requireTenBitSdr) &&
                !(surfaceRole == SurfaceRole.DISPLAY && preferTenBitSdrDisplay)
            ) {
                SurfaceFormat(VK_FORMAT_B8G8R8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)
            } else {
                SurfaceFormat(
                    VK_FORMAT_A2B10G10R10_UNORM_PACK32,
                    VK_COLOR_SPACE_SRGB_NONLINEAR_KHR
                )
            }
            return withDeliveryEvidence(
                resolution = Resolution(sentinel, Tier.UNDEFINED_SENTINEL),
                mode = mode,
                surfaceRole = surfaceRole,
                requireTenBitSdr = requireTenBitSdr,
                preferTenBitSdrDisplay = preferTenBitSdrDisplay
            )
        }
        if (availableFormats.isEmpty()) {
            return withDeliveryEvidence(
                resolution = Resolution(
                    SurfaceFormat(VK_FORMAT_B8G8R8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR),
                    Tier.DRIVER_FIRST_AVAILABLE
                ),
                mode = mode,
                surfaceRole = surfaceRole,
                requireTenBitSdr = requireTenBitSdr,
                preferTenBitSdrDisplay = preferTenBitSdrDisplay
            )
        }

        val resolution = when (mode) {
            HdrDynamicRangeMode.SDR ->
                if (surfaceRole == SurfaceRole.ENCODER && requireTenBitSdr) {
                    resolveTenBitSdrEncoder(availableFormats)
                } else if (surfaceRole == SurfaceRole.DISPLAY && preferTenBitSdrDisplay) {
                    resolveTenBitSdrDisplay(availableFormats)
                } else {
                    resolveSdr(availableFormats)
                }
            HdrDynamicRangeMode.HLG -> resolveHdr(
                availableFormats,
                primaryHdrColorSpace = VK_COLOR_SPACE_HDR10_HLG_EXT,
                secondaryHdrColorSpace = VK_COLOR_SPACE_BT2020_LINEAR_EXT
            )
            HdrDynamicRangeMode.HDR10,
            HdrDynamicRangeMode.HDR10_PLUS,
            HdrDynamicRangeMode.DOLBY_VISION -> resolveHdr(
                availableFormats,
                primaryHdrColorSpace = VK_COLOR_SPACE_HDR10_ST2084_EXT,
                secondaryHdrColorSpace = VK_COLOR_SPACE_BT2020_LINEAR_EXT
            )
        }
        return withDeliveryEvidence(
            resolution = resolution,
            mode = mode,
            surfaceRole = surfaceRole,
            requireTenBitSdr = requireTenBitSdr,
            preferTenBitSdrDisplay = preferTenBitSdrDisplay
        )
    }

    private fun resolveTenBitSdrEncoder(formats: List<SurfaceFormat>): Resolution {
        formats.firstOrNull {
            isTenBitPackedFormat(it.format) &&
                it.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR
        }?.let { return Resolution(it, Tier.TEN_BIT_SDR_ENCODER) }

        formats.firstOrNull {
            isTenBitPackedFormat(it.format) && !isStrictEotfColorSpace(it.colorSpace)
        }?.let { return Resolution(it, Tier.TEN_BIT_SDR_ENCODER) }

        return resolveSdr(formats)
    }

    /**
     * 10-bit SDR DISPLAY (preview) path. Mirrors [resolveTenBitSdrEncoder] but emits the
     * DISPLAY tier. Prefers a 10-bit packed format paired with core sRGB (which does NOT require
     * VK_EXT_swapchain_colorspace), then any 10-bit packed format that is not a strict-EOTF
     * (PQ/HLG) colourspace, and finally delegates to the 8-bit [resolveSdr] baseline. The final
     * delegation makes the no-downgrade guarantee structural: on a driver that does not advertise a
     * 10-bit SDR display pair the result is byte-identical to today's 8-bit preview.
     */
    private fun resolveTenBitSdrDisplay(formats: List<SurfaceFormat>): Resolution {
        formats.firstOrNull {
            isTenBitPackedFormat(it.format) &&
                it.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR
        }?.let { return Resolution(it, Tier.TEN_BIT_SDR_DISPLAY) }

        formats.firstOrNull {
            isTenBitPackedFormat(it.format) && !isStrictEotfColorSpace(it.colorSpace)
        }?.let { return Resolution(it, Tier.TEN_BIT_SDR_DISPLAY) }

        return resolveSdr(formats)
    }

    private fun withDeliveryEvidence(
        resolution: Resolution,
        mode: HdrDynamicRangeMode,
        surfaceRole: SurfaceRole,
        requireTenBitSdr: Boolean,
        preferTenBitSdrDisplay: Boolean = false
    ): Resolution {
        val formatBitDepth = formatBitDepth(resolution.format.format)
        val tenBitSdrDeliveryCertified =
            mode == HdrDynamicRangeMode.SDR &&
                isTenBitPackedFormat(resolution.format.format) &&
                !isStrictEotfColorSpace(resolution.format.colorSpace) &&
                (
                    (surfaceRole == SurfaceRole.ENCODER && requireTenBitSdr) ||
                        (surfaceRole == SurfaceRole.DISPLAY && preferTenBitSdrDisplay)
                )
        val deliveryIdentity = listOf(
            surfaceRole.name,
            mode.name,
            "format=${resolution.format.format}",
            "colorSpace=${resolution.format.colorSpace}",
            "formatBitDepth=$formatBitDepth",
            "tenBitSdrCertified=$tenBitSdrDeliveryCertified"
        ).joinToString(separator = "|")
        return resolution.copy(
            surfaceRole = surfaceRole,
            formatBitDepth = formatBitDepth,
            tenBitSdrDeliveryCertified = tenBitSdrDeliveryCertified,
            deliveryIdentity = deliveryIdentity
        )
    }

    private fun formatBitDepth(format: Int): Int = when (format) {
        VK_FORMAT_A2B10G10R10_UNORM_PACK32,
        VK_FORMAT_A2R10G10B10_UNORM_PACK32 -> 10
        VK_FORMAT_R16G16B16A16_SFLOAT -> 16
        else -> 8
    }

    private fun isTenBitPackedFormat(format: Int): Boolean =
        format == VK_FORMAT_A2B10G10R10_UNORM_PACK32 ||
            format == VK_FORMAT_A2R10G10B10_UNORM_PACK32

    /**
     * SDR path. We must NOT pick an HDR colourspace here — doing so
     * would make the system compositor expect PQ/HLG-encoded data and
     * the SDR preview would look washed out / desaturated. Prefer the
     * canonical 8-bit sRGB pair.
     */
    private fun resolveSdr(formats: List<SurfaceFormat>): Resolution {
        // 1. Standard 8-bit sRGB — the SDR baseline Android compositor expects.
        formats.firstOrNull {
            (it.format == VK_FORMAT_B8G8R8A8_UNORM ||
                it.format == VK_FORMAT_R8G8B8A8_UNORM) &&
                it.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR
        }?.let { return Resolution(it, Tier.EIGHT_BIT_SRGB) }

        // 2. 8-bit sRGB-tagged formats (gamma-correct hardware sampling).
        formats.firstOrNull {
            it.format == VK_FORMAT_B8G8R8A8_SRGB || it.format == VK_FORMAT_R8G8B8A8_SRGB
        }?.let { return Resolution(it, Tier.EIGHT_BIT_SRGB) }

        // 3. Any format that carries the sRGB colourspace — preserves the
        //    SDR intent even when 8-bit isn't on offer. This catches odd
        //    driver lists where only 10-bit sRGB or FP16 sRGB is reported.
        formats.firstOrNull {
            it.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR
        }?.let { return Resolution(it, Tier.EIGHT_BIT_SRGB) }

        // 4. Defensive fallback — when the driver only advertises HDR
        //    colourspaces (pathological — happens on emulators with
        //    incomplete WSI). We must still avoid the strict-EOTF
        //    colourspaces (PQ, HLG) because they make the compositor
        //    apply an inverse transfer function we never produced. Among
        //    the remaining HDR colourspaces, BT.2020 linear and
        //    extended-sRGB are least-harmful (no enforced EOTF mismatch
        //    on SDR content) — pick the first such match.
        formats.firstOrNull { !isStrictEotfColorSpace(it.colorSpace) }
            ?.let { return Resolution(it, Tier.DRIVER_FIRST_AVAILABLE) }

        // 5. Last-resort: nothing safe is on offer. Logged loudly elsewhere.
        return Resolution(formats.first(), Tier.DRIVER_FIRST_AVAILABLE)
    }

    /**
     * True for colourspaces that enforce a specific Electro-Optical
     * Transfer Function the compositor inverts at scan-out: HDR10 (PQ /
     * ST.2084) and HLG. Routing SDR content through these produces a
     * picture that's clearly wrong (washed out / over-bright). Used by
     * the SDR fallback path to actively avoid them.
     */
    private fun isStrictEotfColorSpace(colorSpace: Int): Boolean = when (colorSpace) {
        VK_COLOR_SPACE_HDR10_ST2084_EXT,
        VK_COLOR_SPACE_HDR10_HLG_EXT -> true
        else -> false
    }

    /**
     * HDR path. Decision tree:
     *  1. 10-bit packed + matching HDR EOTF colourspace (HDR10 → PQ, HLG → HLG).
     *  2. 10-bit packed + BT.2020 linear (still HDR-capable, driver tone-maps).
     *  3. FP16 + extended-sRGB linear (Vulkan's HDR-capable linear path).
     *  4. 10-bit packed + sRGB colourspace (system compositor tone-maps —
     *     the bit depth is preserved even if extended brightness isn't).
     *  5. FP16 + sRGB.
     *  6. 8-bit sRGB as last-resort SDR fallback.
     */
    private fun resolveHdr(
        formats: List<SurfaceFormat>,
        primaryHdrColorSpace: Int,
        secondaryHdrColorSpace: Int
    ): Resolution {
        val tenBitPacked = setOf(
            VK_FORMAT_A2B10G10R10_UNORM_PACK32,
            VK_FORMAT_A2R10G10B10_UNORM_PACK32
        )
        // 1. Native 10-bit + matching HDR colourspace
        formats.firstOrNull {
            it.format in tenBitPacked && it.colorSpace == primaryHdrColorSpace
        }?.let { return Resolution(it, Tier.TEN_BIT_HDR_NATIVE) }

        // 2. 10-bit + BT.2020 linear (still real HDR delivery)
        formats.firstOrNull {
            it.format in tenBitPacked && it.colorSpace == secondaryHdrColorSpace
        }?.let { return Resolution(it, Tier.TEN_BIT_HDR_NATIVE) }

        // 3. FP16 + extended-sRGB linear — Vulkan's universal HDR path
        formats.firstOrNull {
            it.format == VK_FORMAT_R16G16B16A16_SFLOAT &&
                (it.colorSpace == VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT ||
                    it.colorSpace == VK_COLOR_SPACE_EXTENDED_SRGB_NONLINEAR_EXT)
        }?.let { return Resolution(it, Tier.FP16_HDR_EXTENDED_LINEAR) }

        // 4. 10-bit + sRGB — bit depth preserved, system handles tone-mapping
        formats.firstOrNull {
            it.format in tenBitPacked &&
                it.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR
        }?.let { return Resolution(it, Tier.TEN_BIT_SRGB_FALLBACK) }

        // 5. FP16 + sRGB
        formats.firstOrNull {
            it.format == VK_FORMAT_R16G16B16A16_SFLOAT &&
                it.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR
        }?.let { return Resolution(it, Tier.FP16_SRGB_FALLBACK) }

        // 6. 8-bit sRGB SDR baseline — last resort
        formats.firstOrNull {
            (it.format == VK_FORMAT_B8G8R8A8_UNORM ||
                it.format == VK_FORMAT_R8G8B8A8_UNORM) &&
                it.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR
        }?.let { return Resolution(it, Tier.EIGHT_BIT_SRGB) }

        // 7. Defensive fallback.
        return Resolution(formats.first(), Tier.DRIVER_FIRST_AVAILABLE)
    }

    /**
     * Convenience translation from [HdrDynamicRangeMode] to a small int
     * used across the JNI boundary. Stable across releases — never
     * reorder.
     */
    fun toNativeModeId(mode: HdrDynamicRangeMode): Int = when (mode) {
        HdrDynamicRangeMode.SDR -> 0
        HdrDynamicRangeMode.HLG -> 1
        HdrDynamicRangeMode.HDR10 -> 2
        HdrDynamicRangeMode.HDR10_PLUS -> 3
        HdrDynamicRangeMode.DOLBY_VISION -> 4
    }

    /** Inverse of [toNativeModeId] for tests / debugging. */
    fun fromNativeModeId(id: Int): HdrDynamicRangeMode = when (id) {
        1 -> HdrDynamicRangeMode.HLG
        2 -> HdrDynamicRangeMode.HDR10
        3 -> HdrDynamicRangeMode.HDR10_PLUS
        4 -> HdrDynamicRangeMode.DOLBY_VISION
        else -> HdrDynamicRangeMode.SDR
    }
}
