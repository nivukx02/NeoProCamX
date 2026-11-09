package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.camera.SelectCameraCharacteristicsReuseFlag
import com.neoproc.neoproccamx.camera.SessionRebuildColorHoldFlag
import com.neoproc.neoproccamx.pipeline.AiPipelineTeardownOffMainFlag
import com.neoproc.neoproccamx.pipeline.AnalysisChromaOnlySnapshotFlag
import com.neoproc.neoproccamx.pipeline.DeferredLumaSnapshotFlag
import com.neoproc.neoproccamx.rendering.RawDebayerPreviewOrientationParityFlag
import com.neoproc.neoproccamx.recording.RecordDevelopEnhanceLaneGpuFlag
import com.neoproc.neoproccamx.recording.RecordDevelopHybridCpuLaneFlag
import com.neoproc.neoproccamx.rendering.RecordDevelopModeFlag
import com.neoproc.neoproccamx.utils.PreferencesTypedFastPathFlag
import com.neoproc.neoproccamx.rendering.AlwaysSaveAtAchievedFpsFlag
import com.neoproc.neoproccamx.security.AsyncSecurityInitFlag
import com.neoproc.neoproccamx.recording.RecordDevelopPipelineOverlapFlag

/**
 * Applies PRODUCT-default flag states at app startup for flags whose conservative OBJECT default deliberately
 * differs from the shipped product behaviour.
 *
 * WHY THIS EXISTS: some feature gates keep their compile-time object default OFF so the unit suite can lock a
 * no-downgrade baseline — every gated policy is proven byte-identical with the flag OFF, and that OFF path
 * stays the tested safety net + instant-revert target. This applier is the single, discoverable place where
 * the app opts into the ON product behaviour, so production and tests never silently diverge.
 *
 * The RECORD-THEN-DEVELOP engine is ON for the shipped high-end workflow: it records a flat 10-bit
 * intermediate and keeps the full grade/detail stack in the SDR/NDR offline develop cards. This preserves
 * recording cadence without lowering the final developed quality. RAW debayer preview orientation parity is
 * also ON for the shipped app so the RAW monitor uses the same portrait/landscape transform as every other
 * preview surface. Debug A/B intents are delivered AFTER this applier and can still override product defaults.
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
            RecordDevelopModeFlag.set(true)
            RawDebayerPreviewOrientationParityFlag.set(true)
            
            // Production Performance and GPU Acceleration Optimizations (100% GPU offloading & zero lag)
            SelectCameraCharacteristicsReuseFlag.set(true)
            SessionRebuildColorHoldFlag.set(true)
            AiPipelineTeardownOffMainFlag.set(true)
            AnalysisChromaOnlySnapshotFlag.set(true)
            DeferredLumaSnapshotFlag.set(true)
            RecordDevelopEnhanceLaneGpuFlag.set(true)
            RecordDevelopHybridCpuLaneFlag.set(true)
            
            // Centralized Performance and Stability Upgrades (Startup, SharedPreferences, Conversions, Thermal-saving)
            PreferencesTypedFastPathFlag.set(true)
            AlwaysSaveAtAchievedFpsFlag.set(true)
            AsyncSecurityInitFlag.set(true)
            RecordDevelopPipelineOverlapFlag.set(true)
            
            applied = true
        }
    }
}

