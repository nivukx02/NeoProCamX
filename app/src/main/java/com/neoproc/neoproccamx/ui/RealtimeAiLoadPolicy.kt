package com.neoproc.neoproccamx.ui

internal object RealtimeAiLoadPolicy {
    fun resolve(
        startupBlocked: Boolean,
        aiAssistEnabled: Boolean,
        recordingOrHighLoad: Boolean,
        faceTrackingEnabled: Boolean,
        eyeTrackingEnabled: Boolean,
        subjectMaskEnabled: Boolean,
        cinemaAutoLockActive: Boolean,
        thermalIsolationActive: Boolean = false
    ): AppliedAiTrackingState {
        if (startupBlocked || thermalIsolationActive) {
            return AppliedAiTrackingState(
                faceDetectionEnabled = false,
                poseTrackingEnabled = false,
                segmentationEnabled = false,
                enhancementEnabled = false,
                eyeTrackingEnabled = false,
                depthEnabled = false,
                sceneDetectionEnabled = false,
                aiAutoFocusEnabled = false
            )
        }

        if (recordingOrHighLoad) {
            // Preserve only tracking stages the user explicitly enabled. Semantic enhancement,
            // segmentation, depth, pose and scene analysis remain shed so recording cadence keeps
            // priority, but Eye Autofocus no longer becomes a silent no-op during a video clip.
            return AppliedAiTrackingState(
                faceDetectionEnabled = faceTrackingEnabled,
                poseTrackingEnabled = false,
                segmentationEnabled = false,
                enhancementEnabled = false,
                eyeTrackingEnabled = eyeTrackingEnabled,
                depthEnabled = false,
                sceneDetectionEnabled = false,
                aiAutoFocusEnabled = faceTrackingEnabled && !cinemaAutoLockActive
            )
        }

        val realtimeAiAssistEnabled = aiAssistEnabled && !recordingOrHighLoad
        return AppliedAiTrackingState(
            faceDetectionEnabled = faceTrackingEnabled,
            poseTrackingEnabled = realtimeAiAssistEnabled || faceTrackingEnabled,
            segmentationEnabled = subjectMaskEnabled || realtimeAiAssistEnabled,
            enhancementEnabled = realtimeAiAssistEnabled,
            eyeTrackingEnabled = eyeTrackingEnabled,
            depthEnabled = realtimeAiAssistEnabled,
            sceneDetectionEnabled = realtimeAiAssistEnabled,
            aiAutoFocusEnabled = faceTrackingEnabled && !cinemaAutoLockActive
        )
    }
}
