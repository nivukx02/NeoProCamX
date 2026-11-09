package com.neoproc.neoproccamx.ui

internal object RealtimeAiLoadPolicy {
    fun resolve(
        startupBlocked: Boolean,
        aiAssistEnabled: Boolean,
        recordingOrHighLoad: Boolean,
        faceTrackingEnabled: Boolean,
        eyeTrackingEnabled: Boolean,
        subjectMaskEnabled: Boolean,
        cinemaAutoLockActive: Boolean
    ): AppliedAiTrackingState {
        if (startupBlocked) {
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
