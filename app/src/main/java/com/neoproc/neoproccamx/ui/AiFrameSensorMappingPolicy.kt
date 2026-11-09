package com.neoproc.neoproccamx.ui

import android.graphics.PointF
import android.graphics.RectF
import com.neoproc.neoproccamx.ai.AICameraFrameResult
import com.neoproc.neoproccamx.ai.DepthData
import com.neoproc.neoproccamx.ai.DetectedFace
import com.neoproc.neoproccamx.ai.EnhancementResult
import com.neoproc.neoproccamx.ai.FocusTarget
import com.neoproc.neoproccamx.ai.FocusTargetType
import com.neoproc.neoproccamx.ai.PrimarySubject
import com.neoproc.neoproccamx.ai.SceneType
import com.neoproc.neoproccamx.ai.SubjectMask
import com.neoproc.neoproccamx.ai.SubjectType
import com.neoproc.neoproccamx.camera.FrameGeometry

internal data class MappedAICameraFrameResult(
    val faces: List<DetectedFace>,
    val depthData: DepthData?,
    val primaryFocus: FocusTarget?,
    val primarySubject: PrimarySubject?,
    val subjectMask: SubjectMask?,
    val sceneType: SceneType,
    val hdrSuggested: Boolean,
    val lowLightSuggested: Boolean,
    val enhancementResult: EnhancementResult?
)

internal object AiFrameSensorMappingPolicy {
    fun map(result: AICameraFrameResult): MappedAICameraFrameResult {
        val geometry = result.frameGeometry
        val faces = result.faces.map { face ->
            DetectedFace(
                id = face.trackingId,
                bounds = rectToSensor(face.boundingBox, geometry),
                leftEye = face.leftEye?.let { pointToSensor(it.x, it.y, geometry) },
                rightEye = face.rightEye?.let { pointToSensor(it.x, it.y, geometry) },
                leftEyeOpenProb = face.leftEye?.confidence ?: face.confidence,
                rightEyeOpenProb = face.rightEye?.confidence ?: face.confidence,
                smileProb = 0f,
                eulerY = face.velocityX * 8f,
                eulerZ = face.velocityY * 8f
            )
        }
        val primaryFocus = result.focusPoint?.let { focusPoint ->
            val mappedPoint = pointToSensor(focusPoint.focusX, focusPoint.focusY, geometry)
            FocusTarget(
                normalizedX = mappedPoint.x,
                normalizedY = mappedPoint.y,
                type = when (focusPoint.subjectType) {
                    SubjectType.EYE -> FocusTargetType.EYE
                    SubjectType.FACE, SubjectType.HEAD -> FocusTargetType.FACE
                    SubjectType.BODY,
                    SubjectType.FULL_BODY,
                    SubjectType.UPPER_BODY,
                    SubjectType.GROUP -> FocusTargetType.BODY
                    SubjectType.OBJECT -> FocusTargetType.OBJECT
                },
                trackId = focusPoint.trackingId
            )
        }
        val primarySubject = result.primarySubject?.let { subject ->
            val mappedFocus = pointToSensor(subject.focusX, subject.focusY, geometry)
            val mappedExposure = pointToSensor(subject.exposureX, subject.exposureY, geometry)
            subject.copy(
                bounds = rectToSensor(subject.bounds, geometry),
                focusX = mappedFocus.x,
                focusY = mappedFocus.y,
                exposureX = mappedExposure.x,
                exposureY = mappedExposure.y
            )
        }
        return MappedAICameraFrameResult(
            faces = faces,
            depthData = result.depthData,
            primaryFocus = primaryFocus,
            primarySubject = primarySubject,
            subjectMask = result.subjectMask,
            sceneType = result.sceneType,
            hdrSuggested = result.hdrSuggested,
            lowLightSuggested = result.lowLightSuggested,
            enhancementResult = result.enhancementResult
        )
    }

    fun pointToSensor(x: Float, y: Float, geometry: FrameGeometry?): PointF {
        return geometry?.cropNormalizedPointToSensorNormalized(x, y)
            ?: point(
                x = x.coerceIn(0f, 1f),
                y = y.coerceIn(0f, 1f)
            )
    }

    fun rectToSensor(bounds: RectF, geometry: FrameGeometry?): RectF {
        return geometry?.cropNormalizedRectToSensorNormalized(bounds)
            ?: rect(
                left = bounds.left.coerceIn(0f, 1f),
                top = bounds.top.coerceIn(0f, 1f),
                right = bounds.right.coerceIn(0f, 1f),
                bottom = bounds.bottom.coerceIn(0f, 1f)
            )
    }

    private fun point(x: Float, y: Float): PointF =
        PointF().apply {
            this.x = x
            this.y = y
        }

    private fun rect(left: Float, top: Float, right: Float, bottom: Float): RectF =
        RectF().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }
}
