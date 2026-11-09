package com.neoproc.neoproccamx.ui

import android.app.Application
import android.util.Size
import com.neoproc.neoproccamx.camera.CameraController
import com.neoproc.neoproccamx.camera.CameraState
import com.neoproc.neoproccamx.pipeline.FramePipelineManager
import com.neoproc.neoproccamx.recording.CinemaRuntimeStats
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.GpuRecordingState
import com.neoproc.neoproccamx.recording.RawCompressionMode
import com.neoproc.neoproccamx.pipeline.RawVideoManager
import com.neoproc.neoproccamx.recording.RecordingManager
import com.neoproc.neoproccamx.recording.ResolvedRecordingSelection
import com.neoproc.neoproccamx.rendering.CameraVulkanPreviewView
import com.neoproc.neoproccamx.rendering.VulkanRuntimeManager
import com.neoproc.neoproccamx.utils.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// CameraViewModel Host Interface
//
// Defines the contract between CameraViewModel and its extracted
// coordinators (RecordingOrchestrator, CinemaRuntimeStatsEngine, etc.).
// Exposes shared state accessors and basic operations needed by
// multiple independent coordinators.
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

interface CameraViewModelHost {
    val application: Application
    val viewModelScope: CoroutineScope
    val prefs: PreferencesManager

    // Sub-systems
    val camera: CameraController
    val gpuRecording: RecordingManager
    val rawVideoManager: RawVideoManager
    val vulkanRuntime: VulkanRuntimeManager
    val pipeline: FramePipelineManager

    // Shared State Flows
    val gpuRecordState: StateFlow<GpuRecordingState>
    val cameraState: StateFlow<CameraState>
    val uiMode: StateFlow<AppMode>
    val cinemaVideoMode: StateFlow<CinemaVideoMode>
    val rawOn: StateFlow<Boolean>
    val logOn: StateFlow<Boolean>
    val rawVideoCompression: StateFlow<RawCompressionMode>
    val cinemaRuntimeStats: StateFlow<CinemaRuntimeStats>

    // Mutable Configuration
    val recordingConfig: RecordingConfig

    // Operations
    suspend fun runOnMain(block: () -> Unit)
    fun runOnMainIfNeeded(block: () -> Unit)
    suspend fun updatePreviewViewAwait(block: suspend CameraVulkanPreviewView.() -> Unit)
    fun handleCameraError(throwable: Throwable, operation: String, userVisibleMessage: String? = null)
    fun maybeApplyThermalGuard(thermalLabel: String)
    fun updateShutterRuleGuidance()
    
    // Preview Management
    fun syncGeometryTargets()
    fun refreshPreviewPipelineIfNeeded(forceSessionRefresh: Boolean = false)
    fun syncEffectiveRecordingColorProfile()
    suspend fun syncPreviewPerformanceModeOnMain()
    fun syncActivePreviewFrameRate()
    fun syncCameraPreviewLookControls()
    
    // Recording Specific Overrides & Look Controls
    fun applyRecordingOutputAspectOverride(size: Size?)
    fun clearRecordingReadoutOverride()
    fun applyRecordingCameraLookControls()
    fun releaseCinemaAutoLock(resetCameraState: Boolean, reason: String)
    fun scheduleCenterAutoPrime()

    // Current State Helpers
    fun preferredRecordingSize(): Size
    fun preferredRecordingAnalysisSize(base: Size): Size
    fun preferredPreviewAnalysisSize(): Size
    fun isEffectiveVideoHdrRequested(): Boolean
    fun currentThermalLabel(): String
    
    // Recording Source
    val lastRecordingSelection: ResolvedRecordingSelection?
    val selectedVideoSize: Size?
    fun resolveRecordingSourcePlan(requestedOutput: Size): RecordingSourceResolutionPlan
}
