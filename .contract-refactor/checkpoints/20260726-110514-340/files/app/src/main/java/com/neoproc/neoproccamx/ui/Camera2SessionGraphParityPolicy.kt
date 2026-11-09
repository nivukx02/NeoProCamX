package com.neoproc.neoproccamx.ui

import android.util.Size

internal enum class Camera2SessionGraphStage {
    IDLE,
    PREARMING,
    PREARMED,
    STARTING,
    RECORDING,
    STOPPING
}

internal enum class Camera2SessionGraphKind {
    PROCESSED_PREVIEW_ENCODER_PARITY,
    PROCESSED_HARDWARE_IMPORT_ENCODER,
    RAW_SENSOR_ORIGINAL
}

internal enum class Camera2SessionRefreshAction {
    NONE,
    REPEATING_REQUEST_ONLY,
    REBUILD_SESSION,
    REBUILD_SESSION_AND_READERS
}

internal data class Camera2SessionGraphParityRequest(
    val graphContract: VideoPipelineContract,
    val parityPlan: RecordingPipelineParityPlan,
    val previewSize: Size,
    val encoderSize: Size,
    val proposedAnalysisSize: Size,
    val proposedReadoutOverrideSize: Size?,
    val recordSurfaceAttachedToSession: Boolean,
    val encoderSurfaceAvailable: Boolean,
    val previousSessionSignature: String? = null,
    val stage: Camera2SessionGraphStage
)

internal data class Camera2SessionGraphParityPlan(
    val graphKind: Camera2SessionGraphKind,
    val deliveryKind: VideoDeliveryKind,
    val previewSize: Size,
    val encoderSize: Size,
    val analysisTargetSize: Size,
    val readoutOverrideSize: Size?,
    val attachRecordSurfaceToSession: Boolean,
    val enablePreviewContinuityRecordSurface: Boolean,
    val rawSensorOwnsReadout: Boolean,
    val dualSwapchainParityRequired: Boolean,
    val geometryCropScaleMatrix: FloatArray,
    val geometryMatrixSignature: String,
    val parityPlanViewportScale: Float,
    val viewportResolutionScale: Float,
    val refreshAction: Camera2SessionRefreshAction,
    val sessionSignature: String,
    val diagnosticReason: String
)

internal object Camera2SessionGraphParityPolicy {

    fun resolve(request: Camera2SessionGraphParityRequest): Camera2SessionGraphParityPlan {
        val rawOriginal =
            request.parityPlan.deliveryKind == VideoDeliveryKind.RAW &&
                request.parityPlan.encoderRawEnabled
        val graphKind = when {
            rawOriginal -> Camera2SessionGraphKind.RAW_SENSOR_ORIGINAL
            request.parityPlan.feedPath in setOf(
                RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER,
                RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER
            ) ->
                Camera2SessionGraphKind.PROCESSED_HARDWARE_IMPORT_ENCODER
            else -> Camera2SessionGraphKind.PROCESSED_PREVIEW_ENCODER_PARITY
        }
        val activeRecordingStage = request.stage != Camera2SessionGraphStage.IDLE
        val attachRecordSurface =
            request.encoderSurfaceAvailable &&
                activeRecordingStage &&
                graphKind != Camera2SessionGraphKind.PROCESSED_HARDWARE_IMPORT_ENCODER
        val readoutOverride = if (rawOriginal) {
            request.proposedReadoutOverrideSize ?: request.encoderSize
        } else {
            null
        }
        val enablePreviewContinuity =
            graphKind == Camera2SessionGraphKind.PROCESSED_PREVIEW_ENCODER_PARITY &&
                attachRecordSurface &&
                request.stage in setOf(
                    Camera2SessionGraphStage.PREARMED,
                    Camera2SessionGraphStage.STARTING,
                    Camera2SessionGraphStage.RECORDING
                )
        val geometryMatrix = geometryCropScaleMatrix(
            previewWidth = request.graphContract.previewWidth,
            previewHeight = request.graphContract.previewHeight,
            encoderWidth = request.graphContract.encoderWidth,
            encoderHeight = request.graphContract.encoderHeight
        )
        val geometrySignature = geometryMatrixSignature(geometryMatrix)
        val dualSwapchainParityRequired = graphKind != Camera2SessionGraphKind.RAW_SENSOR_ORIGINAL
        val sessionSignature = buildSessionSignature(
            request = request,
            graphKind = graphKind,
            readoutOverride = readoutOverride,
            attachRecordSurface = attachRecordSurface,
            geometryMatrixSignature = geometrySignature
        )
        val sessionChanged =
            request.previousSessionSignature != null &&
                request.previousSessionSignature != sessionSignature
        val refreshAction = resolveRefreshAction(
            graphKind = graphKind,
            stage = request.stage,
            attachRecordSurface = attachRecordSurface,
            recordSurfaceAttachedToSession = request.recordSurfaceAttachedToSession,
            sessionChanged = sessionChanged,
            previousSessionKnown = request.previousSessionSignature != null
        )
        return Camera2SessionGraphParityPlan(
            graphKind = graphKind,
            deliveryKind = request.parityPlan.deliveryKind,
            previewSize = request.previewSize,
            encoderSize = request.encoderSize,
            analysisTargetSize = request.proposedAnalysisSize,
            readoutOverrideSize = readoutOverride,
            attachRecordSurfaceToSession = attachRecordSurface,
            enablePreviewContinuityRecordSurface = enablePreviewContinuity,
            rawSensorOwnsReadout = rawOriginal,
            dualSwapchainParityRequired = dualSwapchainParityRequired,
            geometryCropScaleMatrix = geometryMatrix,
            geometryMatrixSignature = geometrySignature,
            parityPlanViewportScale = request.parityPlan.viewportResolutionScale,
            viewportResolutionScale = request.parityPlan.viewportResolutionScale,
            refreshAction = refreshAction,
            sessionSignature = sessionSignature,
            diagnosticReason = diagnosticReason(
                graphKind = graphKind,
                refreshAction = refreshAction,
                sessionChanged = sessionChanged,
                recordSurfaceAttachedToSession = request.recordSurfaceAttachedToSession
            )
        )
    }

    private fun resolveRefreshAction(
        graphKind: Camera2SessionGraphKind,
        stage: Camera2SessionGraphStage,
        attachRecordSurface: Boolean,
        recordSurfaceAttachedToSession: Boolean,
        sessionChanged: Boolean,
        previousSessionKnown: Boolean
    ): Camera2SessionRefreshAction {
        if (graphKind == Camera2SessionGraphKind.RAW_SENSOR_ORIGINAL) {
            return when {
                stage == Camera2SessionGraphStage.IDLE && !sessionChanged ->
                    Camera2SessionRefreshAction.NONE
                else -> Camera2SessionRefreshAction.REBUILD_SESSION_AND_READERS
            }
        }
        if (graphKind == Camera2SessionGraphKind.PROCESSED_HARDWARE_IMPORT_ENCODER) {
            return when {
                stage == Camera2SessionGraphStage.IDLE && !sessionChanged ->
                    Camera2SessionRefreshAction.NONE
                sessionChanged -> Camera2SessionRefreshAction.REBUILD_SESSION
                previousSessionKnown &&
                    recordSurfaceAttachedToSession &&
                    stage in setOf(
                        Camera2SessionGraphStage.STARTING,
                        Camera2SessionGraphStage.RECORDING
                    ) -> Camera2SessionRefreshAction.REPEATING_REQUEST_ONLY
                stage in setOf(
                    Camera2SessionGraphStage.STARTING,
                    Camera2SessionGraphStage.RECORDING
                ) -> Camera2SessionRefreshAction.REBUILD_SESSION
                else -> Camera2SessionRefreshAction.NONE
            }
        }
        if (sessionChanged) return Camera2SessionRefreshAction.REBUILD_SESSION
        if (!attachRecordSurface) return Camera2SessionRefreshAction.NONE
        return when {
            recordSurfaceAttachedToSession &&
                stage in setOf(
                    Camera2SessionGraphStage.STARTING,
                    Camera2SessionGraphStage.RECORDING
                ) -> Camera2SessionRefreshAction.REPEATING_REQUEST_ONLY
            recordSurfaceAttachedToSession -> Camera2SessionRefreshAction.NONE
            else -> Camera2SessionRefreshAction.REBUILD_SESSION
        }
    }

    private fun buildSessionSignature(
        request: Camera2SessionGraphParityRequest,
        graphKind: Camera2SessionGraphKind,
        readoutOverride: Size?,
        attachRecordSurface: Boolean,
        geometryMatrixSignature: String
    ): String = listOf(
        graphKind.name,
        request.parityPlan.deliveryKind.name,
        request.parityPlan.encoderHdrMode,
        request.parityPlan.encoderColorSpace,
        request.parityPlan.encoderLogEnabled.toString(),
        request.parityPlan.encoderRawEnabled.toString(),
        request.parityPlan.tenBitRequestedForSessionPlan.toString(),
        "${request.previewSize.width}x${request.previewSize.height}",
        "${request.encoderSize.width}x${request.encoderSize.height}",
        "${request.proposedAnalysisSize.width}x${request.proposedAnalysisSize.height}",
        readoutOverride?.let { "${it.width}x${it.height}" } ?: "none",
        attachRecordSurface.toString(),
        request.graphContract.fps.toString(),
        request.graphContract.cameraRequestFamily.name,
        geometryMatrixSignature,
        "viewportScale=${request.parityPlan.viewportResolutionScale}"
    ).joinToString(separator = "|")

    private fun geometryCropScaleMatrix(
        previewWidth: Int,
        previewHeight: Int,
        encoderWidth: Int,
        encoderHeight: Int
    ): FloatArray {
        val safePreviewWidth = previewWidth.coerceAtLeast(1)
        val safePreviewHeight = previewHeight.coerceAtLeast(1)
        val safeEncoderWidth = encoderWidth.coerceAtLeast(1)
        val safeEncoderHeight = encoderHeight.coerceAtLeast(1)
        val previewAspect = safePreviewWidth.toFloat() / safePreviewHeight.toFloat()
        val encoderAspect = safeEncoderWidth.toFloat() / safeEncoderHeight.toFloat()
        if (kotlin.math.abs(previewAspect - encoderAspect) < 0.0001f) {
            return floatArrayOf(
                1f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1f
            )
        }
        return if (previewAspect > encoderAspect) {
            val scaleX = encoderAspect / previewAspect
            floatArrayOf(
                scaleX, 0f, (1f - scaleX) * 0.5f,
                0f, 1f, 0f,
                0f, 0f, 1f
            )
        } else {
            val scaleY = previewAspect / encoderAspect
            floatArrayOf(
                1f, 0f, 0f,
                0f, scaleY, (1f - scaleY) * 0.5f,
                0f, 0f, 1f
            )
        }
    }

    private fun geometryMatrixSignature(matrix: FloatArray): String =
        "geom=" + matrix.joinToString(separator = ",") { value ->
            "%.5f".format(java.util.Locale.US, value)
        }

    private fun diagnosticReason(
        graphKind: Camera2SessionGraphKind,
        refreshAction: Camera2SessionRefreshAction,
        sessionChanged: Boolean,
        recordSurfaceAttachedToSession: Boolean
    ): String =
        "kind=$graphKind refresh=$refreshAction changed=$sessionChanged " +
            "recordSurfaceAttached=$recordSurfaceAttachedToSession"
}
