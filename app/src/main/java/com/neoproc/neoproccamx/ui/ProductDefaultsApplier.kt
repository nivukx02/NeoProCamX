package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.camera.SelectCameraCharacteristicsReuseFlag
import com.neoproc.neoproccamx.camera.SessionRebuildColorHoldFlag
import com.neoproc.neoproccamx.camera.CameraLensSelectionTaskFlag
import com.neoproc.neoproccamx.pipeline.AiPipelineTeardownOffMainFlag
import com.neoproc.neoproccamx.pipeline.AnalysisChromaOnlySnapshotFlag
import com.neoproc.neoproccamx.pipeline.DeferredLumaSnapshotFlag
import com.neoproc.neoproccamx.rendering.RawDebayerPreviewOrientationParityFlag
import com.neoproc.neoproccamx.recording.RecordDevelopEnhanceLaneGpuFlag
import com.neoproc.neoproccamx.recording.RecordDevelopHybridCpuLaneFlag
import com.neoproc.neoproccamx.recording.RecordDevelopHybridCpuLanePolicy
import com.neoproc.neoproccamx.rendering.RecordDevelopModeFlag
import com.neoproc.neoproccamx.utils.PreferencesTypedFastPathFlag
import com.neoproc.neoproccamx.rendering.AlwaysSaveAtAchievedFpsFlag
import com.neoproc.neoproccamx.security.AsyncSecurityInitFlag
import com.neoproc.neoproccamx.recording.RecordDevelopPipelineOverlapFlag
import com.neoproc.neoproccamx.recording.TriSurfaceParityFlag
import com.neoproc.neoproccamx.ui.controls.SettingsCapabilityGatePolicy

/**
 * Applies PRODUCT-default flag states at app startup for flags whose conservative OBJECT default deliberately
 * differs from the shipped product behaviour.
 *
 * WHY THIS EXISTS: some feature gates keep their compile-time object default OFF so the unit suite can lock a
 * no-downgrade baseline — every gated policy is proven byte-identical with the flag OFF, and that OFF path
 * stays the tested safety net + instant-revert target. This applier is the single, discoverable place where
 * the app opts into the ON product behaviour, so production and tests never silently diverge.
 *
 * Finished-video processing is the shipped default: RECORD-THEN-DEVELOP stays OFF unless explicitly selected,
 * so normal recordings bake the complete live detail and colour pipeline instead of silently producing a flat
 * master. The offline workflow remains available through its runtime flag. RAW debayer preview orientation
 * parity is ON so the RAW monitor uses the same portrait/landscape transform as every other preview surface.
 * Debug A/B intents are delivered AFTER this applier and can still override product defaults.
 *
 * [applyOnce] runs exactly ONCE per process (not per Activity create), so a configuration change / Activity
 * recreation can never stomp a debug A/B override the user set during the session.
 */
internal object ProductDefaultsApplier {

    @Volatile
    private var applied = false

    /** Idempotent per process: the product defaults are established on the first call and never re-applied. */
    fun applyOnce() {
        if (applied) return
        synchronized(this) {
            if (applied) return
            RecordDevelopModeFlag.set(false)
            RawDebayerPreviewOrientationParityFlag.set(true)

            // Production performance defaults. GPU lanes are enabled only where their output authority
            // does not require duplicate full-frame CPU work in steady state.
            SelectCameraCharacteristicsReuseFlag.set(true)
            CameraLensSelectionTaskFlag.set(true)
            SessionRebuildColorHoldFlag.set(true)
            AiPipelineTeardownOffMainFlag.set(true)
            AnalysisChromaOnlySnapshotFlag.set(true)
            DeferredLumaSnapshotFlag.set(true)
            // The optional chroma lane currently evaluates CPU and GPU for every frame before accepting
            // the result. Shipping that diagnostic mode doubles this stage's work and heat. Keep the
            // byte-exact CPU reference authoritative until controlled device parity qualifies a GPU-only
            // operating point; the already-qualified GPU colour and luma lanes remain available.
            RecordDevelopEnhanceLaneGpuFlag.set(false)
            RecordDevelopHybridCpuLanePolicy.setCpuShareBps(
                RecordDevelopHybridCpuLanePolicy.PRODUCTION_CPU_SHARE_BPS
            )
            RecordDevelopHybridCpuLaneFlag.set(true)

            // Centralized Performance and Stability Upgrades (Startup, SharedPreferences, Conversions, Thermal-saving)
            PreferencesTypedFastPathFlag.set(true)
            // Below-target cadence is an explicit failure, never a silent temporal-quality downgrade.
            AlwaysSaveAtAchievedFpsFlag.set(false)
            // Hide only controls that the current capability report proves unsupported.
            SettingsCapabilityGatePolicy.set(true)
            // Enables only the bounded offline diagnostics admission seam; no live frame work starts.
            TriSurfaceParityFlag.set(true)
            AsyncSecurityInitFlag.set(true)
            RecordDevelopPipelineOverlapFlag.set(true)

            applied = true
        }
    }
}
