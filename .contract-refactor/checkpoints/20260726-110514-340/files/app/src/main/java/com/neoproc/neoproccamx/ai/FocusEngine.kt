package com.neoproc.neoproccamx.ai

import android.graphics.RectF
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.abs
import kotlin.math.max

/**
 * HIGH-END focus orchestration engine with predictive subject selection and
 * cinematic focus transitions.
 */
class FocusEngine {
    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private fun predictionLeadMs(): Long =
        if (strictHighEndEnabled()) PREDICTION_LEAD_MS + 25L else PREDICTION_LEAD_MS

    private fun cinematicSwitchBlendAlpha(): Float =
        if (strictHighEndEnabled()) (CINEMATIC_SWITCH_BLEND_ALPHA - 0.02f).coerceAtLeast(0.18f)
        else CINEMATIC_SWITCH_BLEND_ALPHA

    private fun switchHardCutDistance(): Float =
        if (strictHighEndEnabled()) (SWITCH_HARD_CUT_DISTANCE + 0.07f).coerceAtMost(0.62f)
        else SWITCH_HARD_CUT_DISTANCE


    companion object {
        private const val EYE_PRIORITY = 25.0f   // UPGRADED: Massive bias for eye focus
        private const val FACE_PRIORITY = 15.0f  // UPGRADED
        private const val HEAD_PRIORITY = 11.5f  // AF-SD (F10): between FACE and BODY
        private const val BODY_PRIORITY = 8.0f
        private const val OBJECT_PRIORITY = 5.0f
        // AF-SD (F09): synthetic GROUP candidate tuning (gated by DeadSubjectTypeFlag). The negative
        // id cannot collide with real (>=0) detector track ids. subjectPriority(GROUP)=7.2 keeps a
        // group well below FACE(15)/EYE(25), so a single strong face/eye always wins.
        private const val GROUP_TRACKING_ID = -900
        private const val GROUP_MEMBER_BONUS = 0.35f
        // AF-SD (F11): synthetic DOCUMENT focus-lock target id (negative => no real-id collision).
        private const val DOCUMENT_TARGET_TRACKING_ID = -910
        private const val PREDICTION_LEAD_MS = 25L // UPGRADED: Near-instant forecasting
        private const val CINEMATIC_SWITCH_BLEND_ALPHA = 0.65f // UPGRADED: Faster snap
        private const val SWITCH_HARD_CUT_DISTANCE = 0.15f // UPGRADED: More aggressive cuts
    }

    private var lastFocus: FocusPoint? = null
    private var lastPrimarySubject: PrimarySubject? = null
    // s7-f05/CP95: per-engine continuity state for the gated pose handoff; inert while the flag is off.
    private var lastPoseHandoffAnchor: PoseHandoffAnchor? = null
    private val subjectStates = LinkedHashMap<String, MotionState>()
    private var eyePriorityEnabled: Boolean = true
    // AF-SD (F11): consecutive DOCUMENT-scene frame counter for the gated document focus lock.
    // Mutated only under DocumentFocusLockFlag, so the OFF path never observes or changes it.
    private var documentStableFrames: Int = 0

    fun setEyePriorityEnabled(enabled: Boolean) {
        eyePriorityEnabled = enabled
    }

    fun selectFocusPoint(
        faces: List<TrackedFace>,
        pose: TrackedPose?,
        objects: List<TrackedObject> = emptyList(),
        timestampMs: Long
    ): FocusPoint? {
        return selectSubjectAwareness(
            faces = faces,
            pose = pose,
            objects = objects,
            segmentation = null,
            depthData = null,
            sceneType = SceneType.GENERAL,
            timestampMs = timestampMs
        ).focusPoint
    }

    fun selectSubjectAwareness(
        faces: List<TrackedFace>,
        pose: TrackedPose?,
        objects: List<TrackedObject> = emptyList(),
        segmentation: SegmentationData?,
        depthData: DepthData?,
        sceneType: SceneType,
        timestampMs: Long
    ): SubjectAwarenessResult {
        val candidates = buildCandidates(
            faces = faces,
            pose = pose,
            objects = objects,
            depthData = depthData,
            timestampMs = timestampMs
        )
        val primaryCandidate = candidates.maxByOrNull { candidate ->
            candidate.selectionScore + continuityScore(candidate)
        }
        val primarySubject = primaryCandidate?.toPrimarySubject()

        // AF-SD (F11): DOCUMENT/whiteboard foreground-plane focus lock (gated OFF => no-op). Only
        // engages when DOCUMENT has been stable for STABILITY_FRAMES AND no real subject won, so a
        // genuine higher-priority subject is never overridden. The counter mutates only under the
        // flag, so the OFF path never touches it and stays bit-for-bit.
        var resolvedPrimary = primarySubject
        if (DocumentFocusLockFlag.enabled) {
            documentStableFrames = if (sceneType == SceneType.DOCUMENT) documentStableFrames + 1 else 0
            if (sceneType == SceneType.DOCUMENT &&
                documentStableFrames >= DocumentFocusLockFlag.STABILITY_FRAMES &&
                primaryCandidate == null
            ) {
                val plane = DocumentPlaneLocatorPolicy.locate(segmentation, DocumentFocusLockFlag.MIN_SEG_COVERAGE)
                resolvedPrimary = PrimarySubject(
                    trackingId = DOCUMENT_TARGET_TRACKING_ID,
                    bounds = plane.bounds,
                    subjectType = SubjectType.OBJECT,
                    category = SubjectCategory.OBJECT,
                    confidence = plane.confidence,
                    focusX = plane.focusX,
                    focusY = plane.focusY,
                    exposureX = plane.focusX,
                    exposureY = plane.focusY,
                    depthScore = 0f
                )
            }
        }
        lastPrimarySubject = resolvedPrimary

        // Identity guard (mandatory no-downgrade): on the OFF path resolvedPrimary === primarySubject,
        // so the focus point is derived from the ORIGINAL primaryCandidate exactly as before; only the
        // synthesized document target uses the reconstructed point.
        val focusPoint = if (resolvedPrimary === primarySubject) {
            primaryCandidate?.toFocusPoint()?.let { smooth(lastFocus, it) }
        } else {
            smooth(
                lastFocus,
                FocusPoint(
                    focusX = resolvedPrimary!!.focusX,
                    focusY = resolvedPrimary.focusY,
                    confidence = resolvedPrimary.confidence,
                    subjectType = resolvedPrimary.subjectType,
                    trackingId = resolvedPrimary.trackingId
                )
            )
        }
        lastFocus = focusPoint

        return SubjectAwarenessResult(
            focusPoint = focusPoint,
            primarySubject = resolvedPrimary,
            subjectMask = buildSubjectMask(resolvedPrimary, segmentation, depthData),
            hdrSuggested =
                sceneType == SceneType.BACKLIGHT ||
                    sceneType == SceneType.BRIGHT_EXTREME ||
                    sceneType == SceneType.SUNSET ||
                    sceneType == SceneType.GOLDEN_HOUR,
            lowLightSuggested =
                sceneType == SceneType.NIGHT ||
                    sceneType == SceneType.NIGHT_EXTREME ||
                    sceneType == SceneType.PORTRAIT_LOW_LIGHT ||
                    sceneType == SceneType.CONCERT ||
                    sceneType == SceneType.ASTRO ||
                    sceneType == SceneType.BLUE_HOUR
        )
    }

    private fun buildCandidates(
        faces: List<TrackedFace>,
        pose: TrackedPose?,
        objects: List<TrackedObject> = emptyList(),
        depthData: DepthData?,
        timestampMs: Long
    ): List<SubjectCandidate> {
        val candidates = ArrayList<SubjectCandidate>()

        // AF-SD (F09): detect a face cluster (gated). When a cluster forms, its member FACE/EYE
        // candidates are suppressed and represented by a single GROUP candidate below, so group
        // portraits frame everyone. A dominant single face suppresses the cluster (GroupClusterPolicy),
        // so a clear primary subject still wins as FACE/EYE. Flag OFF => faceCluster is null, nothing
        // is skipped or appended, and output is bit-for-bit the legacy path.
        val faceCluster = if (DeadSubjectTypeFlag.enabled && faces.size >= GroupClusterPolicy.MIN_MEMBERS) {
            GroupClusterPolicy.detectCluster(
                faces.map {
                    FaceBoundsLite(
                        trackingId = it.trackingId,
                        left = it.boundingBox.left,
                        top = it.boundingBox.top,
                        right = it.boundingBox.right,
                        bottom = it.boundingBox.bottom,
                        confidence = it.confidence.coerceIn(0f, 1f)
                    )
                }
            )
        } else {
            null
        }
        val clusterMemberIds = faceCluster?.memberIds?.toSet() ?: emptySet()

        faces.forEach { face ->
            if (face.trackingId in clusterMemberIds) return@forEach
            val state = updateState(
                key = "face:${face.trackingId}",
                x = face.boundingBox.centerX(),
                y = face.boundingBox.centerY(),
                confidence = face.confidence,
                timestampMs = timestampMs
            )
            val predictedCenter = TrackingMath.predictCenter(state, predictionLeadMs())
            val faceEyeTarget = FaceEyeFocusTargetPolicy.resolve(
                face = face.copy(
                    predictedX = predictedCenter.first,
                    predictedY = predictedCenter.second
                ),
                eyePriorityEnabled = eyePriorityEnabled
            )
            val focusType = faceEyeTarget?.subjectType ?: SubjectType.FACE
            val focusX = faceEyeTarget?.focusX ?: predictedCenter.first
            val focusY = faceEyeTarget?.focusY ?: predictedCenter.second
            val focusConfidence = faceEyeTarget?.confidence ?: face.confidence.coerceIn(0f, 1f)
            val depthScore = depthData?.averageDepth(face.boundingBox) ?: 0f
            candidates += SubjectCandidate(
                trackingId = face.trackingId,
                bounds = face.boundingBox,
                focusX = focusX,
                focusY = focusY,
                subjectType = focusType,
                category = SubjectCategory.HUMAN,
                confidence = focusConfidence.coerceIn(0f, 1f),
                depthScore = depthScore,
                areaScore = (face.boundingBox.width() * face.boundingBox.height()).coerceIn(0f, 1f),
                selectionScore = subjectPriority(focusType) +
                    focusConfidence * 0.75f +
                    depthScore * 1.05f +
                    (face.boundingBox.width() * face.boundingBox.height()) * 0.95f
            )
        }

        faceCluster?.let { cluster ->
            val clusterBounds = RectF(
                cluster.unionLeft, cluster.unionTop, cluster.unionRight, cluster.unionBottom
            )
            val depthScore = depthData?.averageDepth(clusterBounds) ?: 0f
            val area = ((cluster.unionRight - cluster.unionLeft) *
                (cluster.unionBottom - cluster.unionTop)).coerceIn(0f, 1f)
            candidates += SubjectCandidate(
                trackingId = GROUP_TRACKING_ID,
                bounds = clusterBounds,
                focusX = cluster.centroidX.coerceIn(0f, 1f),
                focusY = cluster.centroidY.coerceIn(0f, 1f),
                subjectType = SubjectType.GROUP,
                category = SubjectCategory.HUMAN,
                confidence = cluster.meanConfidence.coerceIn(0f, 1f),
                depthScore = depthScore,
                areaScore = area,
                selectionScore = subjectPriority(SubjectType.GROUP) +
                    cluster.meanConfidence * 0.70f +
                    depthScore * 0.95f +
                    area * 0.90f +
                    ((cluster.memberCount - 1).coerceAtMost(3)) * GROUP_MEMBER_BONUS
            )
        }

        pose?.let { trackedPose ->
            val state = updateState(
                key = "pose:${trackedPose.trackingId}",
                x = trackedPose.boundingBox.centerX(),
                y = trackedPose.boundingBox.centerY(),
                confidence = trackedPose.confidence,
                timestampMs = timestampMs
            )
            val predictedCenter = TrackingMath.predictCenter(state, predictionLeadMs())
            val depthScore = depthData?.averageDepth(trackedPose.boundingBox) ?: 0f
            // AF-SD (F09): gated UPPER_BODY vs FULL_BODY from lower-body landmark visibility. Flag
            // OFF => SubjectType.BODY (bit-for-bit). selectionScore is untouched (BODY_PRIORITY).
            val poseSubjectType = if (DeadSubjectTypeFlag.enabled) {
                BodySpanClassifierPolicy.classify(trackedPose.landmarks)
            } else {
                SubjectType.BODY
            }

            // s7-f05/CP95: gated face<->body handoff + centroid gating/smoothing. Flag OFF (default)
            // => the three locals equal today's expressions, so the candidate is bit-for-bit. The
            // selectionScore below intentionally keeps the RAW trackedPose.confidence unchanged.
            var poseFocusX = predictedCenter.first
            var poseFocusY = predictedCenter.second
            var poseConfidence = trackedPose.confidence.coerceIn(0f, 1f)
            if (PoseHandoffFlag.enabled) {
                val bestFace = faces.maxByOrNull { it.confidence }
                val faceAnchorX = bestFace?.let { (it.boundingBox.left + it.boundingBox.right) * 0.5f }
                val faceAnchorY = bestFace?.let { (it.boundingBox.top + it.boundingBox.bottom) * 0.5f }
                val anchor = PoseFaceHandoffPolicy.resolveBodyAnchor(
                    landmarks = trackedPose.landmarks,
                    bboxCenterX = predictedCenter.first,
                    bboxCenterY = predictedCenter.second,
                    bodyConfidence = poseConfidence,
                    faceAnchorX = faceAnchorX,
                    faceAnchorY = faceAnchorY,
                    faceConfidence = bestFace?.confidence?.coerceIn(0f, 1f) ?: 0f,
                    previousAnchor = lastPoseHandoffAnchor
                )
                lastPoseHandoffAnchor = anchor
                poseFocusX = anchor.focusX
                poseFocusY = anchor.focusY
                poseConfidence = anchor.confidence
            }
            candidates += SubjectCandidate(
                trackingId = trackedPose.trackingId,
                bounds = trackedPose.boundingBox,
                focusX = poseFocusX,
                focusY = poseFocusY,
                subjectType = poseSubjectType,
                category = SubjectCategory.HUMAN,
                confidence = poseConfidence,
                depthScore = depthScore,
                areaScore = (trackedPose.boundingBox.width() * trackedPose.boundingBox.height()).coerceIn(0f, 1f),
                selectionScore = BODY_PRIORITY +
                    trackedPose.confidence * 0.65f +
                    depthScore * 0.95f +
                    (trackedPose.boundingBox.width() * trackedPose.boundingBox.height()) * 1.05f
            )
        }

        objects.forEach { obj ->
            val state = updateState(
                key = "object:${obj.trackingId}",
                x = obj.boundingBox.centerX(),
                y = obj.boundingBox.centerY(),
                confidence = obj.confidence,
                timestampMs = timestampMs
            )
            val predictedCenter = TrackingMath.predictCenter(state, predictionLeadMs())
            val depthScore = depthData?.averageDepth(obj.boundingBox) ?: 0f
            // AF-SD (F10): gated correct categorization. Default OFF keeps the legacy className map
            // (bit-for-bit). When on, consume the authoritative ObjectTracker category so bird ->
            // ANIMAL and horse/sheep/cow/etc -> ANIMAL (not the drifted PET), while VEHICLE/NATURE
            // are preserved — the inline map and objectCategoryForClass no longer disagree.
            val category = if (HeadSubjectFlag.enabled) {
                obj.category
            } else {
                when (obj.className.lowercase()) {
                    "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe" -> SubjectCategory.PET
                    "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat" -> SubjectCategory.VEHICLE
                    "potted plant" -> SubjectCategory.NATURE
                    else -> SubjectCategory.OBJECT
                }
            }
            // AF-SD (F08): gated animal/pet head-eye anchor. Default OFF => focus the bbox centre
            // exactly as the legacy path. When on, a PET/ANIMAL object's focus POINT is moved to a
            // head/eye prior (subjectType EYE for the tight cinematic snap); the selectionScore
            // below is left untouched, so ranking vs humans is unchanged and an animal can never
            // steal primary from a face/eye.
            var objFocusX = predictedCenter.first
            var objFocusY = predictedCenter.second
            var objSubjectType = SubjectType.OBJECT
            if (AnimalEyeAfFlag.enabled &&
                (category == SubjectCategory.PET || category == SubjectCategory.ANIMAL ||
                    obj.category == SubjectCategory.PET || obj.category == SubjectCategory.ANIMAL)
            ) {
                val animalTarget = AnimalEyeFocusTargetPolicy.resolve(
                    left = obj.boundingBox.left,
                    top = obj.boundingBox.top,
                    right = obj.boundingBox.right,
                    bottom = obj.boundingBox.bottom,
                    predictedX = predictedCenter.first,
                    predictedY = predictedCenter.second,
                    velocityX = obj.velocityX,
                    confidence = obj.confidence
                )
                if (animalTarget != null) {
                    objFocusX = animalTarget.focusX
                    objFocusY = animalTarget.focusY
                    objSubjectType = animalTarget.subjectType
                }
            }
            candidates += SubjectCandidate(
                trackingId = obj.trackingId,
                bounds = obj.boundingBox,
                focusX = objFocusX,
                focusY = objFocusY,
                subjectType = objSubjectType,
                category = category,
                confidence = obj.confidence.coerceIn(0f, 1f),
                depthScore = depthScore,
                areaScore = (obj.boundingBox.width() * obj.boundingBox.height()).coerceIn(0f, 1f),
                selectionScore = OBJECT_PRIORITY +
                    obj.confidence * 0.55f +
                    depthScore * 0.85f +
                    (obj.boundingBox.width() * obj.boundingBox.height()) * 0.75f
            )
        }

        return candidates
    }

    private fun continuityScore(candidate: SubjectCandidate): Float {
        val strictHighEnd = strictHighEndEnabled()
        val previous = lastPrimarySubject ?: return 0f
        var score = 0f
        if (candidate.trackingId == previous.trackingId) {
            score += if (strictHighEnd) 1.2f else 1.1f
        }
        if (candidate.subjectType == previous.subjectType) {
            score += if (strictHighEnd) 0.46f else 0.4f
        }
        val distance = TrackingMath.distance(
            previous.focusX,
            previous.focusY,
            candidate.focusX,
            candidate.focusY
        )
        score += (1f - distance / if (strictHighEnd) 0.46f else 0.40f).coerceIn(0f, 1f) *
            if (strictHighEnd) 0.72f else 0.65f
        return score
    }

    private fun buildSubjectMask(
        primarySubject: PrimarySubject?,
        segmentation: SegmentationData?,
        depthData: DepthData?
    ): SubjectMask? {
        val subject = primarySubject ?: return null
        val maskWidth = segmentation?.maskWidth ?: depthData?.mapWidth ?: return null
        val maskHeight = segmentation?.maskHeight ?: depthData?.mapHeight ?: return null
        if (maskWidth <= 0 || maskHeight <= 0) return null
        val strictHighEnd = strictHighEndEnabled()
        val alphaMask = ByteArray(maskWidth * maskHeight)
        val expandedBounds = expandBounds(
            subject.bounds,
            (if (subject.category == SubjectCategory.HUMAN) 0.14f else 0.08f) +
                if (strictHighEnd) 0.02f else 0f
        )
        val targetDepth = depthData?.sampleNormalizedDepth(subject.focusX, subject.focusY) ?: subject.depthScore

        for (y in 0 until maskHeight) {
            val ny = if (maskHeight > 1) y / (maskHeight - 1f) else 0f
            for (x in 0 until maskWidth) {
                val nx = if (maskWidth > 1) x / (maskWidth - 1f) else 0f
                val index = y * maskWidth + x
                val segmentationAlpha = segmentation?.sampleForegroundConfidence(nx, ny) ?: 0f
                val depthAlpha = depthData?.sampleNormalizedDepth(nx, ny)?.let { sample ->
                    val distance = abs(sample - targetDepth)
                    (1f - distance / 0.42f).coerceIn(0f, 1f)
                } ?: 0f
                val boxAlpha = if (expandedBounds.contains(nx, ny)) {
                    1f
                } else {
                    val dx = distanceToInterval(nx, expandedBounds.left, expandedBounds.right)
                    val dy = distanceToInterval(ny, expandedBounds.top, expandedBounds.bottom)
                    (1f - (dx + dy) / 0.18f).coerceIn(0f, 1f)
                }
                val maskValue = when (subject.category) {
                    SubjectCategory.HUMAN -> max(segmentationAlpha * boxAlpha, depthAlpha * boxAlpha * 0.72f)
                    SubjectCategory.ANIMAL,
                    SubjectCategory.PET -> max(segmentationAlpha * boxAlpha * 0.9f, depthAlpha * boxAlpha * 0.7f)
                    SubjectCategory.VEHICLE,
                    SubjectCategory.NATURE,
                    SubjectCategory.OBJECT -> max(depthAlpha * boxAlpha, boxAlpha * 0.28f)
                }
                alphaMask[index] = (maskValue * 255f).toInt().coerceIn(0, 255).toByte()
            }
        }

        return SubjectMask(
            maskWidth = maskWidth,
            maskHeight = maskHeight,
            alphaMask = alphaMask,
            trackingId = subject.trackingId,
            category = subject.category,
                intensity = ((when (subject.subjectType) {
                    SubjectType.EYE -> 0.78f
                    SubjectType.FACE -> 0.72f
                    SubjectType.HEAD -> 0.71f
                    SubjectType.BODY -> 0.68f
                    SubjectType.FULL_BODY -> 0.66f
                    SubjectType.UPPER_BODY -> 0.70f
                    SubjectType.GROUP -> 0.64f
                    SubjectType.OBJECT -> 0.60f
                } + if (strictHighEnd) 0.04f else 0f)).coerceIn(0f, 0.95f)
        )
    }

    private fun smooth(previous: FocusPoint?, next: FocusPoint): FocusPoint {
        val strictHighEnd = strictHighEndEnabled()
        if (previous == null) return next
        if (previous.subjectType != next.subjectType || previous.trackingId != next.trackingId) {
            val switchDistance = TrackingMath.distance(previous.focusX, previous.focusY, next.focusX, next.focusY)
            if (switchDistance > switchHardCutDistance()) return next
            val cinematicAlpha = (cinematicSwitchBlendAlpha() + next.confidence *
                if (strictHighEnd) 0.07f else 0.08f).coerceIn(0.18f, 0.35f)
            return next.copy(
                focusX = previous.focusX + (next.focusX - previous.focusX) * cinematicAlpha,
                focusY = previous.focusY + (next.focusY - previous.focusY) * cinematicAlpha,
                confidence = previous.confidence + (next.confidence - previous.confidence) *
                    if (strictHighEnd) 0.38f else 0.35f
            )
        }
        val alpha = when (next.subjectType) {
            SubjectType.EYE -> 0.46f
            SubjectType.FACE -> 0.38f
            SubjectType.HEAD -> 0.35f
            SubjectType.BODY -> 0.32f
            SubjectType.FULL_BODY -> 0.30f
            SubjectType.UPPER_BODY -> 0.34f
            SubjectType.GROUP -> 0.28f
            SubjectType.OBJECT -> 0.28f
        }
        return next.copy(
            focusX = previous.focusX + (next.focusX - previous.focusX) * alpha,
            focusY = previous.focusY + (next.focusY - previous.focusY) * alpha,
            confidence = previous.confidence + (next.confidence - previous.confidence) *
                if (strictHighEnd) 0.44f else 0.4f
        )
    }

    private fun updateState(
        key: String,
        x: Float,
        y: Float,
        confidence: Float,
        timestampMs: Long
    ): MotionState {
        val current = subjectStates[key]
        val updated = TrackingMath.updateMotionState(
            current = current,
            centerX = x,
            centerY = y,
            confidence = confidence,
            nowMs = timestampMs,
            smoothing = if (strictHighEndEnabled()) 0.40f else 0.35f
        )
        subjectStates[key] = updated
        val staleKeys = subjectStates
            .filterValues { timestampMs - it.updatedAtMs > if (strictHighEndEnabled()) 1500L else 1200L }
            .keys
            .toList()
        staleKeys.forEach(subjectStates::remove)
        return updated
    }

    private fun subjectPriority(subjectType: SubjectType): Float {
        return when (subjectType) {
            SubjectType.EYE -> EYE_PRIORITY
            SubjectType.FACE -> FACE_PRIORITY
            SubjectType.HEAD -> HEAD_PRIORITY
            SubjectType.BODY -> BODY_PRIORITY
            SubjectType.FULL_BODY -> BODY_PRIORITY * 0.95f
            SubjectType.UPPER_BODY -> BODY_PRIORITY * 1.05f
            SubjectType.GROUP -> BODY_PRIORITY * 0.90f
            SubjectType.OBJECT -> OBJECT_PRIORITY
        }
    }

    private fun expandBounds(bounds: RectF, padding: Float): RectF {
        return RectF(
            (bounds.left - padding).coerceIn(0f, 1f),
            (bounds.top - padding).coerceIn(0f, 1f),
            (bounds.right + padding).coerceIn(0f, 1f),
            (bounds.bottom + padding).coerceIn(0f, 1f)
        )
    }

    private fun distanceToInterval(value: Float, start: Float, end: Float): Float {
        return when {
            value < start -> start - value
            value > end -> value - end
            else -> 0f
        }
    }

    private data class SubjectCandidate(
        val trackingId: Int,
        val bounds: RectF,
        val focusX: Float,
        val focusY: Float,
        val subjectType: SubjectType,
        val category: SubjectCategory,
        val confidence: Float,
        val depthScore: Float,
        val areaScore: Float,
        val selectionScore: Float
    ) {
        fun toFocusPoint(): FocusPoint {
            return FocusPoint(
                focusX = focusX.coerceIn(0f, 1f),
                focusY = focusY.coerceIn(0f, 1f),
                confidence = confidence.coerceIn(0f, 1f),
                subjectType = subjectType,
                trackingId = trackingId
            )
        }

        fun toPrimarySubject(): PrimarySubject {
            return PrimarySubject(
                trackingId = trackingId,
                bounds = bounds,
                subjectType = subjectType,
                category = category,
                confidence = confidence,
                focusX = focusX.coerceIn(0f, 1f),
                focusY = focusY.coerceIn(0f, 1f),
                exposureX = bounds.centerX().coerceIn(0f, 1f),
                exposureY = bounds.centerY().coerceIn(0f, 1f),
                depthScore = depthScore.coerceIn(0f, 1f)
            )
        }
    }
}
