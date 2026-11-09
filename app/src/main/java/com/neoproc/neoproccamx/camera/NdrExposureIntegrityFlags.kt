package com.neoproc.neoproccamx.camera

/**
 * A/B gates for the NDR10+/NIVUKX capture-side exposure integrity work (highlight protection +
 * motion-blur elimination). Both default ON — this is the shipping behaviour the user asked for —
 * but each can be flipped independently on-device for an A/B without a rebuild.
 *
 * On-device toggle (vivo): `adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *   -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.ndr_highlight_protect true|false`
 * and `--ez debug.ndr_anti_blur true|false`.
 *
 * NEITHER gate touches the sensor bit-depth, gamut, or transport — they only steer the standard
 * Camera2 auto-exposure controls (exposure compensation + exposure-time priority), so OFF is
 * byte-for-byte the previous behaviour (a strict no-downgrade lever).
 */
object NdrHighlightProtectFlag {
    @Volatile
    var enabled: Boolean = true

    fun set(value: Boolean) {
        enabled = value
    }
}

/**
 * A/B gate for the NDR10+/NIVUKX closed-loop anti-motion-blur shutter cap (see
 * [NdrAntiMotionBlurShutterPolicy]). Default ON. See [NdrHighlightProtectFlag] for the toggle recipe.
 */
object NdrAntiMotionBlurFlag {
    @Volatile
    var enabled: Boolean = true

    fun set(value: Boolean) {
        enabled = value
    }
}

/**
 * A/B gate for zoom-STABLE HDR auto-exposure metering. Default ON.
 *
 * The bug: the HDR subject-metering fallback used a fixed central patch (38%x42%) in FULL-SENSOR
 * coordinates that did NOT track the zoom crop. Zoomed OUT, that central patch metered the darker
 * middle of a wide frame while bright edges (sky/window) clipped to white; the wide-FOV re-meter on
 * every zoom then lifted exposure with no damping. Turning this ON makes HDR meter the WHOLE frame
 * (no narrow centre bias), which is inherently zoom-insensitive AND highlight-inclusive, so exposure
 * no longer jumps/blows when the framing changes. OFF restores the previous centred patch.
 * Toggle: `--ez debug.ndr_zoom_stable_meter true|false`.
 */
object NdrZoomStableMeteringFlag {
    @Volatile
    var enabled: Boolean = true

    fun set(value: Boolean) {
        enabled = value
    }
}

/**
 * A/B gate for the closed-loop ADAPTIVE highlight-protection bias. Default ON.
 *
 * The fixed -0.67 EV highlight bias cannot offset a multi-stop AE lift in a bright / high-dynamic-range
 * scene, so highlights still blow and warm skin clips the red channel (the "red skin" is an
 * overexposure symptom, not a colour bug). When ON, the live scene average-luma + highlight-clip
 * fraction (from the analysis stream) drive an ADDITIONAL negative EV bias, on top of the -0.67 floor,
 * whenever real clipping is detected — never brighter than today, only darker to save highlights.
 * OFF restores the fixed -0.67 behaviour. Toggle: `--ez debug.ndr_adaptive_highlight true|false`.
 */
object NdrAdaptiveHighlightFlag {
    @Volatile
    var enabled: Boolean = true

    fun set(value: Boolean) {
        enabled = value
    }
}

/**
 * A/B gate for the true-SDR sensor highlight governor. This changes only Camera2 AE compensation;
 * Rec.709 output, resolution, frame rate, bit depth, and encoder settings are untouched.
 */
object SdrAdaptiveHighlightFlag {
    @Volatile
    var enabled: Boolean = true

    fun set(value: Boolean) {
        enabled = value
    }
}

/**
 * A/B gate for RED-channel-aware highlight protection. Default ON.
 *
 * ROOT CAUSE (ITU-T H.Sup15 + verified trace): the capture scene meter
 * ([com.neoproc.neoproccamx.pipeline.FrameAnalysisMath.computeSceneStats]) is LUMA-only, and BT.2020
 * luma weights red at just 0.2627. So a warm subject (skin) can hard-clip its RED channel while luma
 * still reads moderate — the -0.67 floor and the luma-driven adaptive highlight-protect NEVER fire, and
 * the red channel clips into the single shared HDR capture that feeds preview + record + convert (the
 * NDR/NIVUKX "red skin"). Luma Y' is not luminance (H.Sup15 §7.2.2/§7.3), and neutrals (Cb≈Cr≈0) are
 * unaffected — which is why only warm tones blow.
 *
 * When ON, a reconstructed red-clip fraction (R ≈ Y + 1.47·(Cr-128)) drives the adaptive
 * highlight-protect so exposure pulls DOWN when the RED channel is actually clipping — protecting the
 * clipping channel at the shared capture, visible live in preview. Never brighter than today (strict
 * no-regression). OFF => the red-clip term is 0 (previous luma-only behaviour). Standards-based (no
 * other-vendor tech), no res/fps/bitrate/10-bit/PQ change.
 * Toggle: `--ez debug.ndr_red_clip_highlight true|false`.
 */
object NdrRedClipHighlightFlag {
    @Volatile
    var enabled: Boolean = true

    fun set(value: Boolean) {
        enabled = value
    }
}
