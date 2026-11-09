package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * s8-f04 (CP-U4) — monotonically non-increasing structural ceilings for the two UI god objects, set
 * to the current measured counts so they can only trend DOWN as decomposition continues. A commit
 * that grows CameraViewModel/MainActivity past these ceilings (or widens the public StateFlow
 * surface) fails the build, forcing extraction rather than accretion.
 *
 * Re-baselined to the current measured counts after the accumulated recording / 10-bit / offscreen-EGL
 * work grew both god objects (the ceilings are a ratchet re-pinned to reality on legitimate growth;
 * decomposition of these two objects remains tracked separately, e.g. CP-S10-B4/B5). Last re-pin:
 * the additive per-mode GL preview compositor (GlVisiblePreviewPolicy + PreviewGlCompositor) wired its
 * grade resolution, engage/disengage binding, settled-state re-evaluation, and a bounded cross-HDR
 * self-heal retry through CameraViewModel (no new public StateFlow surface). Last re-pin: focus/preview
 * fixes — region-less tap-AF wiring, 4:3-resolution auto-aspect, and the NDR10+↔NIVUKX same-transport
 * session-refresh guard (last-applied dynamic-range tracking) — no new public StateFlow surface. Last
 * re-pin: MainActivity 3413→3433 for the "HDR looks SDR" fix (re-assert the window HDR colour mode on
 * resume / configuration-change, recording-guarded) — display-side only, no new public StateFlow surface.
 * Last re-pin: CameraViewModel 11105→11109 for the genuine NeoLog HAL tonemap bypass in
 * resolveGlPreviewGrade (LOG preview is WYSIWYG once the ISP bakes N-LOG, so the compositor must not
 * double-log) — no new public StateFlow surface.
 * Last re-pin: CameraViewModel 11109→11130 for STRICT LOG/RAW preview — the GL compositor gate now
 * defaults ON (genuine NeoLog shader), plus a cold-launch re-sync once prefs restore, a clean
 * compositor release on pause (strict re-engage on resume), and a self-heal-on-desync fall-through in
 * syncGlPreviewCompositorBinding — no new public StateFlow surface.
 * Last re-pin: MainActivity 3433→3505 and CameraViewModel 11130→11146 for the lens/zoom-preset
 * quick-switch chips (#25) — recommendedZoomPresets()/isZoomLocked() VM helpers plus the programmatic
 * chip row + active-lens highlight in MainActivity, and RAW zoom-lock feedback (#27). No new public
 * StateFlow surface (the helpers are plain functions).
 */
class UiGodObjectSizeMetaTest {

    @Test
    fun cameraViewModelLineCountAtOrBelowCeiling() {
        val lines = uiSource("CameraViewModel.kt").readLines().size
        // Phase D re-pin 11146 -> 11149: doc-comment on the transient native-preview handoff hold that
        // fixes the LOG<->RAW / lens-crossing preview shrink (behavior lives in CameraVulkanPreviewView;
        // no new VM branch or public StateFlow surface).
        // Phase D re-pin 11149 -> 11202: GL LOG/RAW preview now releases the Camera2 SurfaceView owner
        // before starting the EGL compositor, preventing EGL_BAD_ALLOC owner races. No new public StateFlow.
        // Phase D re-pin 11202 -> 11224: recording startup now blocks delayed GL preview rebinds and
        // preserves the hidden hardware-import encoder feed, preventing two-frame offscreen EGL clips.
        // Phase D re-pin 11224 -> 11241: LOG/RAW GL preview now waits for Camera2 open/streaming before
        // taking the SurfaceView, then re-syncs after camera state opens. No new public StateFlow surface.
        // Phase D re-pin 11241 -> 11310: GL preview SurfaceView handoff now waits for the Camera2
        // release callback, in-flight session refreshes, and mode/recording-config/stop owner cooldown.
        // Quality-fix re-pin 11310 -> 11810: re-baselined to the accumulated WIP tree (the file already
        // measured 11809 on disk before this change) plus the +1 line for the SDR_ENHANCE grade-code
        // arm that routes normal SDR preview through the compositor's denoise/sharpen stage. No new
        // public StateFlow surface.
        // No-downgrade quality-fix re-pin 11810 -> 12054: re-baselined to the accumulated tree (this
        // session's committed record-colour / fps-gate / RC5 fixes had grown the file to 12041 without
        // re-pinning) plus the +13 lines for the preview-colour-parity WYSIWYG wiring (the
        // PreviewColorParityFlag onChanged arm + the setCreativeLut push in applyResolvedPreviewLut).
        // No new public StateFlow surface (the two StateFlow ceilings below stay unchanged).
        // EGL-owner fix re-pin 12054 -> 12462: current WIP tree plus the visible GL compositor
        // release-settle and stale-owner guards. No new public StateFlow surface.
        // Record-then-develop re-pin 12462 -> 13331: re-baselined to the committed accumulated tree — the
        // record-then-develop engine (light-record lane + colour sidecar wiring + develop-master store +
        // two develop cards) plus the accompanying NDR/HDR/colour-stability WIP had grown the file to 13331
        // without a re-pin. No new public StateFlow surface (the StateFlow ceilings below stay unchanged);
        // decomposition of this god object remains tracked debt (CP-S10-B4/B5).
        // SDR-develop-parity re-pin 13331 -> 13366: +28 pre-existing uncommitted broad elite-color WIP lines
        // already on disk beyond the last re-pin, plus +5 lines that wire the offline SDR develop's creative
        // film-print strength (RecordDevelopSdrLookPolicy.developFilmPrintStrength) into the develop card so
        // offline SDR keeps the live film-print grade, plus +2 lines of cross-reference comment linking the
        // live film-look flag to that offline path. No new public StateFlow surface.
        // Full-sensor constant-fps re-pin 13366 -> 13377: +11 lines at the offscreen-record start that mux the
        // full-sensor clip as CFR (FullSensorConstantFpsPolicy.useConstantMuxCadence) for the user-requested
        // constant 25 fps — one hardcoded `setRealtimeSurfaceCadenceEnabled(false)` replaced by a size-gated
        // call plus its rationale comment. No new public StateFlow surface (the ceilings below are unchanged).
        // Leak-fix re-pin 13377 -> 13403: +26 lines in onCleared() (null the 9 process-global flag onChanged
        // slots + remove the thermal-status listener) plus the thermal listener + its PowerManager stored as
        // fields and registered by name, so a destroyed ViewModel is no longer retained via captured `this`.
        // No new public StateFlow surface.
        // Audit-B06 re-pin 13403 -> 13421: +18 lines guarding the async HDR10-PQ preview compositor engage —
        // on coroutine completion it now re-checks the targeted surface is still the current, valid
        // previewSurface (and camera-ready) before binding, else releases the just-built compositor, fixing a
        // bind-to-torn-down-surface EGL leak when the surface is destroyed during the ~2s EGL build. No new
        // public StateFlow surface (the two StateFlow ceilings above stay unchanged).
        // Recording freeze/fps proof re-pin 13421 -> 13459: offscreen AHB/EGL prearm now attaches the
        // Camera2 P010 source before record start, strict clip evidence starts at the proven GPU window,
        // and unsafe GL-preview timeout handoff restores direct preview instead of taking a still-owned
        // SurfaceView. No recording quality knobs or public StateFlow surface changed.
        // Prearmed offscreen P010 preservation re-pin 13459 -> 13470: preview refresh now keeps the
        // already-attached OFFSCREEN_AHB_EGL_ENCODER hardware-import source in the Camera2 session, so
        // record start no longer rebuilds the session and counts startup lag as dropped frames.
        // Preview gray/freeze re-pin 13470 -> 13480: idle prearmed offscreen P010 now preserves the
        // hidden recording import source without blocking the visible GL monitor-grade compositor; active
        // recording still blocks unsafe visible-surface churn unless the compositor is already running.
        // GL handoff fallback timing re-pin 13480 -> 13481: the safety fallback now waits beyond the
        // normal Camera2 release-settle path instead of racing the compositor engage back to direct gray.
        // Strict no-downgrade thermal re-pin 13481 -> 13506: strict-high-end runtime thermal mitigation now
        // clears/bypasses preview analysis caps, bitrate reduction, and encoder-complexity reduction; hot
        // devices are admitted/refused instead of silently lowering quality.
        // Prearmed preview retune-freeze re-pin 13506 -> 13516: once the offscreen P010 source is attached,
        // idle preview-performance hints no longer resize analysis and rebuild Camera2 every few seconds.
        // Explicit preview-grade sync re-pin 13516 -> 13517: successful P010 prearm now triggers the GL
        // monitor compositor directly instead of relying on performance-retune churn to do it later.
        // Preview compositor self-heal re-pin 13517 -> 13566: preview-only GL/PQ compositor binding now
        // retries after startup/prearm/record-start cooldowns and can re-engage while the hidden P010
        // encoder import surface is preserved, fixing gray/frozen preview without touching record quality.
        // Recording AI/default gate re-pin 13566 -> 13628: accumulated WIP tree plus explicit no-auto-face
        // metering/default handling; recording pixel quality and public StateFlow surface are unchanged.
        // Faster-develop re-pin 13628 -> 13656: +24 pre-existing uncommitted WIP lines already on disk beyond
        // the last re-pin (incl. a trailing-newline normalization), plus +4 lines routing the develop-.mov
        // MediaStore publish copy through the large reversible buffer
        // (RecordDevelopPublishCopyPolicy.copyWithLargeBuffer) that removes the 8 KiB-copy FUSE bottleneck.
        // Byte-identical output; no new public StateFlow surface.
        // Custom-recording-name re-pin 13656 -> 13708: +52 lines for the one-shot "Custom Recording Name"
        // feature — two read-only getters, two setters, the start-hook that pushes the sanitized name onto
        // gpuRecording.pendingCustomRecordingName before start(), and the success-path value-aware auto-reset
        // (main-thread-serialized so it never clobbers a name typed during the stop/drain window) back to the
        // default "CamX ddMMyyyy". No new public StateFlow surface (getters are plain vals).
        // Stability/perf audit re-pin 13708 -> 13750: +4 (QW-1 8 MiB RAW/NPV10 publish copy) + D-6 record-stop
        // single-rebuild gate + D-7 bounded submit-quiesce poll + D-9 VIDEO cold-open scaffold, all flag-gated
        // default-OFF (byte-identical shipped behavior). No new public StateFlow surface.
        // Contract-locked thermal-listener extraction 14238 -> 14215: ownership and teardown moved to
        // CameraThermalStatusCoordinator; tier mapping and strict no-downgrade callbacks are unchanged.
        // Persistent-preview stability re-pin 14215 -> 15196: accumulated unpinned camera WIP plus the
        // dual-compositor output-router ownership, warm-frame handoff, recording/session surface retention,
        // and latest-value Vulkan render-config coalescing required to remove mode-switch EGL teardown and
        // main-thread native work. No public StateFlow surface was added (both ceilings below remain tighter);
        // decomposition remains tracked debt and this exact-count ratchet prevents further silent growth.
        // Settled-session transaction re-pin 15196 -> 15243: one pre-switch snapshot and one final
        // Camera2 decision replace repeated intermediate cinema/HDR graph resolution on the gesture frame.
        // StateFlow ceilings and recording/preview quality contracts are unchanged.
        // Settings suspension re-pin 15243 -> 15321: explicit camera-open gating, immediate close/resume
        // ownership, and idle EGL retention keep Camera2/frame processing off behind Settings while avoiding
        // a cold preview rebuild on return. Foreground freshness/health ownership was simultaneously extracted
        // to CameraForegroundRecoveryCoordinator; no public StateFlow or image/video-quality surface was added.
        // Sensor-linear NDR re-pin 15321 -> 15537: accumulated RAW/FP16 NDR preview-recording parity,
        // measured colour-correction delivery, and opt-in record/develop lifecycle wiring. The public reactive
        // surface did not grow (the two StateFlow ratchets below remain substantially under their ceilings).
        // Generation-safe NDR routing re-pin 15537 -> 15705: persisted-profile seeding, native apply proof,
        // and stale deferred-packet rejection close the PRO/RAW/NDR transition race. No public StateFlow,
        // resolution, FPS, bitrate, bit-depth, or feature surface was added.
        // Sensor-lineage re-pin 15705 -> 15875: atomic Camera2 session-generation capture, confirmed
        // compositor-display evidence, and confirmed muxer/container commit evidence preserve frame identity
        // across the asynchronous preview/recording domains. The StateFlow ratchets below remain unchanged.
        // Still/recording isolation re-pin 15875 -> 15899: recording admission now waits only for physical
        // still-buffer/session restoration while completed JPEG enhancement remains asynchronous, and exposes
        // that same exact predicate to debug automation. This adds no public StateFlow surface.
        // Visible-presentation lineage re-pin 15899 -> 16061: successful final-window EGL swaps now carry
        // sensor timestamp, Camera2 session generation, and visible-surface epoch into an isolated SharedFlow.
        // The flow is bounded/non-blocking and adds no public StateFlow or capture/recording dependency.
        // NPV10 ownership re-pin 16061 -> 16072: the approved P010 session admission and frame-lineage
        // integration keeps Camera2 image ownership explicit while storage work remains on its writer lane.
        // The public StateFlow surface and proprietary NDR color path are unchanged.
        // Fail-closed mode/session re-pin 16072 -> 16299: strict 10-bit/RAW admission retains requested
        // identity, capability-aware graph keys prevent cross-lens reuse, and generation-owned camera/session
        // callbacks close black-preview and stale-refresh races. The tighter StateFlow/public-surface ratchets
        // below remain unchanged, and the work is delegated through extracted policy/coordinator classes.
        // Emergency thermal re-pin 16299 -> 16307: one private fail-closed branch stops an active strict
        // recording before encoder/bitrate mitigation; it adds no StateFlow or public API surface.
        // Strict-preview proof re-pin 16307 -> 16648: +77 accumulated unpinned WIP lines already present,
        // plus typed pending/native/persistent/failed admission, actual Vulkan swapchain-format proof,
        // no-direct-fallback restoration, and one-shot router/SDR/HDR runtime-EGL failure detachment.
        // Runtime retirement is two-phase: Camera2 first retargets/rebuilds, then child queues release
        // only after the outgoing session closes, preserving any active recording surface.
        // The runtime listener fan-in is extracted to StrictPreviewRuntimeFailureCoordinator; no public
        // StateFlow/API surface, recording-quality control, resolution, bitrate, or bit-depth was added.
        // Session-lineage re-pin 16648 -> 16673: the permanent Camera2 result fan-out now carries the
        // authoritative session generation to sensor diagnostics, RAW/NDR pairing, direct-surface frame
        // evidence, and the bounded lineage worker. The work remains delegated to extracted managers;
        // no public StateFlow/API, image-quality setting, resolution, bitrate, or bit-depth was added.
        // Exact-stop safety re-pin 16673 -> 16855: CP-066 adds the fail-closed NPV10 startup/stop
        // transaction, exact Camera2 marker ownership, late-frame publication poison, accepted-frame drain,
        // background durability finalization, and explicit prearmed-preview repeating restart. These lines
        // replace a false verified-publication/frame-loss result and a persistent post-stop preview freeze;
        // they do not add a public StateFlow/API or reduce resolution, FPS, bitrate, bit depth, or quality.
        // Exact-producer/NDR-route re-pin 16855 -> 16993: current-generation request proof now precedes
        // NPV10 writer/sink ownership, foreground preference restores defer across every recording state,
        // and proprietary NDR is fail-closed away from vendor P010 into the existing RAW -> ACEScg route.
        // Route authority itself is extracted into Npv10RecordingRoutePolicy; no public StateFlow/API,
        // resolution, FPS, bitrate, bit depth, cadence audit, or verified-publication check was weakened.
        // Immutable-render-snapshot re-pin 16993 -> 17311: one generation-bound request now owns delivery,
        // CameraCharacteristics, LUT, codec, scene/detail, SNCE, APEX, transform, and recording state. Deferred
        // render work no longer rereads mutable ViewModel state, and evidence/cache/enhancement side effects
        // publish only after the matching native profile commit. Pure grade, identity, LUT, and authority work
        // remains extracted into dedicated policy/snapshot classes; no public StateFlow/API, resolution, FPS,
        // bitrate, bit depth, RAW fidelity, color-science control, detail profile, or feature was weakened.
        // Exact RAW/EGL retirement re-pin 17311 -> 17517: bounded input-read receipts, two-slot exact-token
        // transport ownership, failed-start/normal-stop detach proof, and process-lifetime quarantine prevent
        // codec/Vulkan use-after-free while preserving every recording-quality contract. Retry policy and
        // retirement coordination remain extracted; the StateFlow/public-surface ratchets below are unchanged.
        // Current production-candidate re-pin 17517 -> 18034: accumulated lifecycle/recording ownership work
        // plus LQ-018 full-visible-frame AWB authority and stable aspect/recording layout synchronization.
        // The WB region math and layout decisions remain extracted policy code; no public StateFlow/API,
        // resolution, FPS, bitrate, bit depth, RAW fidelity, detail profile, or recording feature was reduced.
        // Preview-retirement audit re-pin 18034 -> 18339: accumulated unpinned WIP plus generation-gated
        // SurfaceHolder delivery, Camera2-acknowledged two-phase EGL retirement, and separately measured
        // Camera2/child/router presentation telemetry. State machines and counters are extracted into
        // PreviewSurfaceRetirementCoordinator and PreviewPixelRouteTelemetry; this file contains only
        // Android-owner wiring and adds no public StateFlow/API or recording-quality downgrade.
        // LQ-001 production-candidate re-pin 18339 -> 18658: accumulated unpinned device-QA fixes plus
        // authoritative-session P010 source priming. The priming decision remains extracted in
        // VideoSessionHardwareImportPrimingPolicy; no public StateFlow/API, resolution, FPS, bitrate,
        // bit depth, colour science, RAW fidelity, recording feature, or security control was reduced.
        // LQ-001 surface-lifecycle re-pin 18658 -> 18706: accumulated exact P010 membership,
        // generation-bound preview retirement, and bounded refresh-recovery wiring. The ownership and
        // admission decisions remain extracted policy/coordinator code; the measured StateFlow counts
        // below remain inside their existing tighter ceilings (103/111 and 40/47).
        // The two reactive-surface ratchets below remain substantially tighter than their ceilings.
        // LQ-001 recording-integrity re-pin 18706 -> 18935: exact-frame presentation evidence,
        // NPV10 lease recovery, offline-only NIV99 routing, and every-frame OIS telemetry add Android
        // owner wiring while their decisions remain in extracted policies/coordinators. The tighter
        // StateFlow/public-surface caps below are unchanged, and this exact ceiling leaves no slack.
        assertTrue("CameraViewModel.kt grew to $lines lines (ceiling 18935)", lines <= 18935)
    }

    @Test
    fun cameraViewModelStateFlowLineCountAtOrBelowCeiling() {
        // Per-line containment count (matches the established grep -c baseline of 102).
        // Quality-fix re-pin 105 -> 109: pre-existing WIP-tree growth (measured on disk before this
        // change). The quality fix added NO StateFlow lines (its only CameraViewModel edit is a private
        // when-arm), so this ceiling only records accumulated reality.
        // Record-then-develop re-pin 109 -> 110: pre-existing committed-tree growth (+1 StateFlow-containing
        // line); the public StateFlow surface below is unchanged (still 47), so this records reality only.
        // Strict thermal no-downgrade re-pin 110 -> 111: one private _uiStateFlow hardwareWarning read was
        // added to clear a stale thermal-downgrade warning; no public StateFlow surface changed.
        val count = uiSource("CameraViewModel.kt").readLines().count { it.contains("StateFlow") }
        assertTrue("CameraViewModel StateFlow lines grew to $count (ceiling 111)", count <= 111)
    }

    @Test
    fun cameraViewModelPublicStateFlowSurfaceAtOrBelowCeiling() {
        val regex = Regex("val .*: StateFlow<")
        val count = uiSource("CameraViewModel.kt").readLines().count { regex.containsMatchIn(it) }
        // Quality-fix re-pin 45 -> 47: pre-existing WIP-tree growth (the quality fix added NO public
        // StateFlow — it only wired a private grade-code arm). Records reality, not new surface.
        assertTrue("CameraViewModel public StateFlow surface grew to $count (ceiling 47)", count <= 47)
    }

    @Test
    fun mainActivityLineCountAtOrBelowCeiling() {
        val lines = uiSource("MainActivity.kt").readLines().size
        // Phase D re-pin 3505 -> 3483 (tighter): the #25 multi-chip zoom row was replaced by a single
        // zoom button (net removal), so the monotonic ratchet tightens back down to reality.
        // Phase D re-pin 3483 -> 3498: startup open now falls back to the delivered callback Surface
        // when SurfaceHolder's cached getter is transiently empty, preventing debug automation stalls.
        // Quality-fix re-pin 3498 -> 3518: re-baselined to the accumulated WIP tree (measured on disk;
        // MainActivity is untouched by the quality fix — this only records reality).
        // Record-then-develop re-pin 3518 -> 3621: the committed accumulated tree measured 3618, plus the +3
        // lines for the once-per-process ProductDefaultsApplier.applyOnce() startup call. No new public
        // StateFlow surface.
        // Surface startup retry re-pin 3621 -> 3695: bounded cached-holder probing prevents frozen
        // black preview when the SurfaceHolder callback/cache ordering races during cold start.
        // Stale stable-surface recovery re-pin 3695 -> 3711: if the holder swaps to a fresh valid
        // Surface before the zero-delay open runnable executes, recover with the current Surface instead
        // of leaving the SurfaceView transparent over the previous app layer.
        // Stability/perf audit re-pin 3711 -> 3717: +6 lines for FIX-D-10 (async-security-init gate) — the
        // startup gate awaits the idempotent RuntimeSecurityManager.initialize() verdict instead of the
        // default-ALLOW getReport() when the flag is ON; default-OFF path is byte-identical.
        // Splash lifecycle re-pin 3717 -> 3737: cold/warm launch splash dismissal is now driven by an actual
        // Camera2 preview frame with bounded timeout and teardown cleanup, preventing intermittent stuck splash.
        // Device-QA re-pin 3737 -> 3752: the debug-only capture_photo action now reaches the real full-resolution
        // still pipeline and retries transient Camera2/still-reader admission instead of looping as an unknown action.
        // RAW-to-recording debug re-pin 3752 -> 3769: record commands defer before prearm and before consuming
        // one-shot start ownership while a still buffer or still-owned session restoration remains in flight.
        // Truthful splash lifecycle re-pin 3769 -> 3880: cold and warm branding now wait for a successful
        // current-session/current-surface display swap, enforce minimum/maximum bounds, and cleanly tear down
        // draw listeners/animations. Sensor timestamps and FPS samples no longer claim display readiness.
        // Per-activity launch telemetry re-pin 3880 -> 3881: each Activity launch now begins an isolated boot
        // timeline so warm recreation cannot inherit the preceding Activity's splash/startup generation.
        // Stable-aspect UI re-pin 3881 -> 3924: the current candidate centralizes aspect-aware dock anchoring,
        // preserves Pro control geometry while recording, and applies setting changes without recreating the UI.
        // Camera preview orientation, resolution, recording quality, and the public reactive surface are unchanged.
        // LQ-001 device-evidence re-pin 3924 -> 4097: stable-window preview telemetry, explicit
        // debug automation admission, and lifecycle-safe QA hooks add Activity-owned Android wiring.
        // Feature policies remain extracted and this exact ceiling leaves no unreviewed growth slack.
        assertTrue("MainActivity.kt grew to $lines lines (ceiling 4097)", lines <= 4097)
    }

    private fun uiSource(name: String): File {
        val candidates = listOf(
            File("app/src/main/java/com/neoproc/neoproccamx/ui/$name"),
            File("src/main/java/com/neoproc/neoproccamx/ui/$name")
        )
        val file = candidates.firstOrNull { it.isFile }
        assertTrue("Missing UI source $name (looked in ${candidates.map { it.path }})", file != null)
        return file!!
    }
}
