package com.neoproc.neoproccamx.ai

import android.graphics.Rect
import android.graphics.RectF
import android.hardware.camera2.CaptureResult
import android.os.SystemClock
import android.util.Log
import com.neoproc.neoproccamx.camera.CameraController
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow
import kotlin.math.sqrt

@OptIn(ExperimentalCoroutinesApi::class)
class AIAutoFocusCoordinator(
    private val camera: CameraController
) {
    companion object {
        private const val TAG = "AIAFCoordinator"
        
        // ═══════════════════════════════════════════════════════════════════════════
        // UPGRADED HIGH-END AF PARAMETERS
        // ═══════════════════════════════════════════════════════════════════════════
        // Advanced priority management
        private const val HIGH_PRIORITY_CONFIDENCE_THRESHOLD = 0.85f
        private const val CONFIDENCE_BOOST_FOR_FACE = 0.15f
        private const val SUBJECT_SWITCH_HYSTERESIS = 0.20f
        
        // Cinematic focus transitions
        private const val FOCUS_TRANSITION_SMOOTH_FRAMES = 8
        private const val FOCUS_EASE_POWER = 2.5f
        private const val PREDICTION_LEAD_SECONDS_STRICT = 0.055f
        private const val PREDICTION_LEAD_SECONDS_STANDARD = 0.035f
        private const val MAX_PREDICTION_OFFSET = 0.06f

        // CP-F1: predicted-hunting-risk above this suppresses a redundant AF re-trigger (anti-pump).
        private const val HUNT_RISK_SUPPRESS_THRESHOLD = 0.85f

        // CP-F5 (s5-f04): no-downgrade gate for the scene-cut-aware single re-acquire + explicit
        // low-confidence/low-contrast risk-gate HOLD. DEFAULT false: the gated branch is unreachable
        // and CP-F1 retrigger/hold/follow output is bit-for-bit unchanged until the owner enables it
        // device-side. See AIFocusSceneCutRiskPolicy + AIFocusCpF1RegressionGoldenTest.
        private const val SCENE_CUT_RISK_GATING_ENABLED = false

        // CP-F5: AF has no real per-frame scene-contrast producer today (sceneComplexity is a
        // constant). A neutral 1.0f means "no low-contrast" => the risk gate's contrast leg never
        // fires by default, reproducing today's behavior. Wired as a seam for a future producer.
        private const val DEFAULT_SCENE_CONTRAST = 1.0f

        /**
         * CP-F1: suppress a redundant single-shot AF re-trigger when the hardware AF is already scanning
         * (don't pile on), when oscillation in the reported lens position indicates active hunting, or
         * when predicted hunting risk is high. Pure + unit-tested; never suppresses on a settled,
         * non-hunting frame, so it cannot cause a focus miss.
         */
        internal fun shouldSuppressRetrigger(
            report: AIFocusStabilityMetrics.FocusStabilityReport,
            afScanning: Boolean
        ): Boolean =
            afScanning || report.isHunting || report.huntingRiskPrediction >= HUNT_RISK_SUPPRESS_THRESHOLD
    }

    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    // Enhanced tracking state
    private var smoothedTarget: AIFloatRect? = null
    private var lastUpdateMs = 0L
    private var lastTargetMs = 0L
    private var trackingEnabled = false
    private var lastSubjectType: SubjectType? = null
    private var lastTrackId = -1
    private var lastTapAfMs = 0L
    private var lastAcceptedTargetMs = 0L
    private var lossWatchdogJob: Job? = null
    private var aiObserveJob: Job? = null
    private var lastAppliedFocusRegion: AIMeteringRect? = null
    private var lastAppliedExposureRegion: AIMeteringRect? = null
    private var focusPidState = FocusPidState()
    // CP-F6 (s5-f05): one-shot latch for the depth-prior lens-distance direction hint on
    // region-capable devices. Ensures the hint fires AT MOST once per acquisition and can never
    // become a continuous lens drive that fights the hardware AF servo. Reset when depth evidence
    // is lost (acquisition over). Inert while AIDepthPriorFocusBias is gated OFF (the default).
    private var depthPriorLensHintLatched = false
    private val targetSequence = AtomicLong(0L)

    // CP-F1: anti-hunting stability loop, fed the REAL hardware-reported lens position (not a constant).
    private val focusStability = AIFocusStabilityMetrics()
    private var focusLockUntilMs = 0L

    // CP-F5 (s5-f04): single-deliberate-re-acquire latch — armed when a hard cut / whip-pan fires one
    // re-acquire, disarmed once subject motion settles below the cut threshold. Prevents a pump train
    // across the frames of one discontinuity. Only consulted when SCENE_CUT_RISK_GATING_ENABLED.
    private var sceneCutReacquireArmed = false
    
    // UPGRADED: Advanced tracking state
    private var subjectPriority: Float = 0f
    private var consecutiveTrackingFrames: Int = 0
    private var focusTransitionProgress: Float = 0f
    private var previousFocusTarget: AIFloatRect? = null
    private var smoothedFocusPoint: Pair<Float, Float>? = null
    private var subjectVelocity: Pair<Float, Float> = 0f to 0f
    private var predictiveOffset: Pair<Float, Float> = 0f to 0f

    private val sensorRect: Rect? get() = camera.activeArray

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private fun focusResponsivenessProfile(): FocusResponsivenessProfile =
        LogFocusResponsivenessPolicy.resolve(
            logEnabled = camera.previewLogLookEnabled,
            hdrEnabled = camera.previewHdrLookEnabled,
            strictHighEnd = strictHighEndEnabled(),
            recordingActive = camera.isVideoRecordingActive,
            targetFps = camera.params.value.fps
        )

    fun setEnabled(enabled: Boolean, clearMeteringRegionWhenDisabling: Boolean = true) {
        targetSequence.incrementAndGet()
        scope.launch {
            trackingEnabled = enabled
            if (!enabled) {
                Log.i(
                    TAG,
                    if (clearMeteringRegionWhenDisabling) {
                        "AI AF disabled - clearing metering region"
                    } else {
                        "AI AF disabled - preserving current metering region"
                    }
                )
                resetTrackingState(clearMeteringRegion = clearMeteringRegionWhenDisabling)
            } else {
                Log.i(TAG, "AI AF enabled")
            }
        }
    }

    fun submitTarget(target: SubjectMeteringTarget?) {
        val sequence = targetSequence.incrementAndGet()
        scope.launch {
            if (sequence != targetSequence.get()) return@launch
            if (!trackingEnabled) return@launch
            if (camera.params.value.focusDist >= 0f || camera.aeAwbLocked) {
                resetLensDriveState()
                return@launch
            }

            val now = SystemClock.elapsedRealtime()
            val responsiveness = focusResponsivenessProfile()
            if (target == null) {
                resetLensDriveState()
                if (smoothedTarget == null || now - lastTargetMs >= responsiveness.lossTimeoutMs / 2L) {
                    resetTrackingState(clearMeteringRegion = true)
                } else {
                    scheduleTrackingLoss(responsiveness.lossTimeoutMs)
                }
                return@launch
            }

            val newRegion = target.subjectBounds.toAIFloatRect().normalizedSafe()
            val current = smoothedTarget
            if (sequence != targetSequence.get()) return@launch
            val predictedRegion = predictMotionCompensatedRegion(
                current = current,
                incoming = newRegion,
                nowMs = now
            )
            val smoothed = AIFocusMeteringMath.smoothBounds(
                current = current,
                target = predictedRegion,
                subjectType = target.subjectType,
                confidence = target.confidence
            )
            val stableFocusPoint = AIFocusMeteringMath.smoothFocusPoint(
                current = smoothedFocusPoint,
                targetX = target.focusX,
                targetY = target.focusY,
                subjectType = target.subjectType,
                confidence = target.confidence
            )
            val moved = AIFocusMeteringMath.movementMagnitude(current, smoothed)

            val sr = sensorRect ?: return@launch
            val sensorBounds = sr.toAIMeteringRect()
            val focusRegion = AIFocusMeteringMath.clampMeteringRect(
                AIFocusMeteringMath.normalizedRectToSensorRect(
                    region = AIFocusMeteringMath.focusRegionFor(
                        subjectType = target.subjectType,
                        focusX = stableFocusPoint.first,
                        focusY = stableFocusPoint.second,
                        smoothedBounds = smoothed,
                        confidence = target.confidence
                    ),
                    sensorBounds = sensorBounds
                ),
                sensorBounds
            )
            val exposureRegion = AIFocusMeteringMath.clampMeteringRect(
                AIFocusMeteringMath.normalizedRectToSensorRect(
                    region = AIFocusMeteringMath.exposureRegionFor(
                        subjectType = target.subjectType,
                        smoothedBounds = smoothed,
                        confidence = target.confidence
                    ),
                    sensorBounds = sensorBounds
                ),
                sensorBounds
            )

            val regionsChanged = AIFocusMeteringMath.regionsChanged(
                lastFocusRegion = lastAppliedFocusRegion,
                lastExposureRegion = lastAppliedExposureRegion,
                focusRegion = focusRegion,
                exposureRegion = exposureRegion
            )

            val decision = AIAfTargetStabilityPolicy.resolveDecision(
                target = target,
                currentTarget = current,
                moved = moved,
                nowMs = now,
                lastUpdateMs = lastUpdateMs,
                lastAcceptedTargetMs = lastAcceptedTargetMs,
                lastTapAfMs = lastTapAfMs,
                lastTrackId = lastTrackId,
                lastSubjectType = lastSubjectType,
                regionsChanged = regionsChanged,
                moveThreshold = adaptiveMoveThreshold(responsiveness.moveThreshold),
                reacquireThreshold = responsiveness.reacquireThreshold,
                updateIntervalMs = responsiveness.updateIntervalMs,
                retriggerIntervalMs = responsiveness.retriggerIntervalMs
            )

            if (!decision.acceptTarget) {
                if (decision.holdExistingLock) {
                    lastTargetMs = now
                    cancelLossWatchdog()
                } else {
                    resetLensDriveState()
                    scheduleTrackingLoss(responsiveness.lossTimeoutMs)
                }
                return@launch
            }

            if (sequence != targetSequence.get()) return@launch
            val newPriority = computeSubjectPriority(target)
            subjectPriority = (subjectPriority * 0.70f + newPriority * 0.30f).coerceIn(0f, 1f)
            consecutiveTrackingFrames += 1
            focusTransitionProgress = (
                (consecutiveTrackingFrames.toFloat() / FOCUS_TRANSITION_SMOOTH_FRAMES.toFloat())
                    .coerceIn(0f, 1f)
                    .pow(FOCUS_EASE_POWER)
            )
            previousFocusTarget = smoothedTarget
            smoothedTarget = smoothed
            smoothedFocusPoint = stableFocusPoint
            lastTargetMs = now
            lastAcceptedTargetMs = now
            lastSubjectType = target.subjectType
            lastTrackId = target.trackingId
            cancelLossWatchdog()
            if (sequence != targetSequence.get()) return@launch
            applyAiDrivenFocus(target)

            // CP-F1: feed the REAL hardware-reported lens position into the anti-hunting loop and
            // suppress redundant single-shot AF re-triggers that cause visible pumping. The subject
            // metering region still follows the subject when a trigger is suppressed, so tracking is
            // never downgraded — only the pump-inducing re-trigger is throttled.
            val live = camera.captureResult
            val afScanning = live?.afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN ||
                live?.afState == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN
            val stability = focusStability.analyzeFocusStability(
                currentFocusDiopters = live?.focusDist ?: 0f,
                subjectConfidence = target.confidence,
                // AF-SD (F12): real per-frame complexity when the consumer gate is on; else legacy 0.5f.
                sceneComplexity = if (AIFocusSceneContrastFlag.enabled) {
                    AIFocusSceneContrastPolicy.sceneComplexityFromContrast(target.sceneContrast)
                } else {
                    0.5f
                },
                timestampMs = now
            )
            if (stability.shouldLockFocus) {
                focusLockUntilMs = now + stability.recommendedLockDuration
            }
            val suppressRetrigger = shouldSuppressRetrigger(stability, afScanning)

            // CP-F5 (s5-f04): scene-cut-aware single re-acquire + low-confidence/low-contrast risk
            // HOLD, behind the default-OFF gate. When disabled this whole block is skipped and the
            // CP-F1 path below is bit-for-bit unchanged.
            if (SCENE_CUT_RISK_GATING_ENABLED) {
                // Disarm the single-shot latch once the subject discontinuity has settled.
                if (!AIFocusSceneCutRiskPolicy.isSceneCut(moved, decision.hardReacquire)) {
                    sceneCutReacquireArmed = false
                }
                val sceneCutRisk = AIFocusSceneCutRiskPolicy.resolveSceneCutRisk(
                    moved = moved,
                    hardReacquire = decision.hardReacquire,
                    subjectConfidence = target.confidence,
                    sceneContrast = effectiveSceneContrast(target),
                    sceneCutAlreadyArmed = sceneCutReacquireArmed
                )
                if (sceneCutRisk.holdLastGood) {
                    // Risk frame: HOLD the last good target — keep following the subject metering
                    // region but never retrigger AF into transient garbage (hold-not-hunt).
                    focusStability.notePumpDecision(suppressed = true)
                    camera.setSubjectMeteringRegions(
                        focusRegion.toAndroidRect(),
                        exposureRegion.toAndroidRect()
                    )
                    lastAppliedFocusRegion = focusRegion
                    lastAppliedExposureRegion = exposureRegion
                    lastUpdateMs = now
                    return@launch
                }
                if (sceneCutRisk.reacquireOnce && !suppressRetrigger) {
                    // Deliberate, single-shot re-acquire across a detected hard cut / whip-pan.
                    sceneCutReacquireArmed = true
                    focusStability.recordReacquireEvent()
                    focusStability.notePumpDecision(suppressed = false)
                    camera.triggerSubjectAutoFocus(
                        focusRegion = focusRegion.toAndroidRect(),
                        exposureRegion = exposureRegion.toAndroidRect(),
                        focusNormalizedX = target.focusX,
                        focusNormalizedY = target.focusY
                    )
                    lastTapAfMs = now
                    lastAppliedFocusRegion = focusRegion
                    lastAppliedExposureRegion = exposureRegion
                    lastUpdateMs = now
                    return@launch
                }
            }

            if (decision.shouldTriggerAutofocus && !suppressRetrigger) {
                camera.triggerSubjectAutoFocus(
                    focusRegion = focusRegion.toAndroidRect(),
                    exposureRegion = exposureRegion.toAndroidRect(),
                    focusNormalizedX = target.focusX,
                    focusNormalizedY = target.focusY
                )
                lastTapAfMs = now
            } else if (decision.shouldUpdateMetering ||
                (decision.shouldTriggerAutofocus && suppressRetrigger)) {
                camera.setSubjectMeteringRegions(focusRegion.toAndroidRect(), exposureRegion.toAndroidRect())
            } else {
                return@launch
            }

            lastAppliedFocusRegion = focusRegion
            lastAppliedExposureRegion = exposureRegion
            lastUpdateMs = now

            Log.v(
                TAG,
                "AI AF center=(%.2f, %.2f) subject=%s hdr=%s lowLight=%s".format(
                    target.focusX,
                    target.focusY,
                    target.subjectType,
                    target.hdrSuggested,
                    target.lowLightSuggested
                )
            )
        }
    }

    fun observeAI(subjectMetering: StateFlow<SubjectMeteringTarget?>) {
        aiObserveJob?.cancel()
        aiObserveJob = scope.launch {
            subjectMetering.collect { target -> submitTarget(target) }
        }
    }

    fun destroy() {
        scope.cancel()
        camera.clearAiDrivenFocusDistance()
        camera.clearFocusRegion()
    }

    private fun scheduleTrackingLoss(timeoutMs: Long = focusResponsivenessProfile().lossTimeoutMs) {
        cancelLossWatchdog()
        lossWatchdogJob = scope.launch {
            delay(timeoutMs)
            if (SystemClock.elapsedRealtime() - lastTargetMs >= timeoutMs) {
                Log.d(TAG, "Tracking lost - clearing subject metering and returning to continuous AF")
                resetTrackingState(clearMeteringRegion = true)
            }
        }
    }

    private fun applyAiDrivenFocus(target: SubjectMeteringTarget) {
        if (camera.maxAfRegions > 0) {
            // CP-F6 (s5-f05): depth-prior HYBRID for region-capable devices. Default OFF =>
            // identical to today's no-op early-return below. When enabled AND depth confidence is
            // high, bias hardware AF with a depth-derived metering region (and an optional one-shot
            // lens-distance direction hint) — it NEVER seizes the continuous servo. Low confidence
            // degrades to the exact baseline. The region-less PID path (further down) is untouched.
            if (AIDepthPriorFocusBias.enabled) {
                val priorMotion = sqrt(
                    subjectVelocity.first * subjectVelocity.first +
                        subjectVelocity.second * subjectVelocity.second
                ).coerceIn(0f, 1f)
                val maxDiopters = camera.minFocusDist.coerceAtLeast(0f)
                // Reuse resolveDepthAwareFocusStep ONLY to derive the prior (direction/confidence),
                // not to drive the lens. We deliberately discard its nextState here so the prior
                // never advances the region-less PID integrator on a region-capable device.
                val prior = AIFocusDistanceController.resolveDepthAwareFocusStep(
                    normalizedDepth = target.focusDepthScore,
                    maxDiopters = maxDiopters,
                    subjectConfidence = target.confidence,
                    edgeConfidence = estimateFocusEdgeConfidence(target),
                    motionScore = priorMotion,
                    state = FocusPidState(),
                    nowNs = System.nanoTime()
                )
                if (prior.targetDiopters == null) {
                    depthPriorLensHintLatched = false
                }
                val decision = AIDepthPriorFocusBias.resolve(
                    enabledFlag = AIDepthPriorFocusBias.enabled,
                    oneShotEnabledFlag = AIDepthPriorFocusBias.oneShotLensHintEnabled,
                    hasDepthTarget = prior.targetDiopters != null,
                    depthConfidence = prior.confidence,
                    targetDiopters = prior.targetDiopters ?: -1f,
                    maxDiopters = maxDiopters,
                    lensHintLatched = depthPriorLensHintLatched
                )
                if (decision.applyRegionBias) {
                    val biasRegions = buildDepthBiasMeteringRegions(target)
                    if (biasRegions != null) {
                        camera.setSubjectMeteringRegions(biasRegions.first, biasRegions.second)
                    }
                    if (decision.fireOneShotLensHint) {
                        // One-shot only: latched immediately so it cannot re-fire this acquisition.
                        camera.setAiDrivenFocusDistance(
                            decision.directionDiopters,
                            prior.confidence
                        )
                        depthPriorLensHintLatched = decision.latchHint
                    }
                    return
                }
            }
            if (camera.aiDrivenFocusDist >= 0f) {
                resetLensDriveState()
            }
            return
        }
        val focusMotionScore = sqrt(
            subjectVelocity.first * subjectVelocity.first +
                subjectVelocity.second * subjectVelocity.second
        ).coerceIn(0f, 1f)
        val depthStep = AIFocusDistanceController.resolveDepthAwareFocusStep(
            normalizedDepth = target.focusDepthScore,
            maxDiopters = camera.minFocusDist.coerceAtLeast(0f),
            subjectConfidence = target.confidence,
            edgeConfidence = estimateFocusEdgeConfidence(target),
            motionScore = focusMotionScore,
            state = focusPidState,
            nowNs = System.nanoTime()
        )
        if (depthStep.targetDiopters == null) {
            if (focusPidState.lastAppliedDiopters >= 0f) {
                resetLensDriveState()
            }
            return
        }
        focusPidState = depthStep.nextState
        if (!depthStep.shouldApply) {
            return
        }
        camera.setAiDrivenFocusDistance(
            depthStep.appliedDiopters,
            depthStep.confidence
        )
        focusPidState = focusPidState.copy(lastAppliedDiopters = depthStep.appliedDiopters)
    }

    /**
     * CP-F6 (s5-f05): build the depth-derived AF/AE metering region for the region-capable
     * hybrid, reusing the SAME math as the live subject-tracking path (smoothed bounds + smoothed
     * focus point + active-array mapping). Returns the (focus, exposure) Android rects, or null if
     * tracking state is not yet established (caller then leaves hardware AF untouched).
     */
    private fun buildDepthBiasMeteringRegions(target: SubjectMeteringTarget): Pair<Rect, Rect>? {
        val smoothed = smoothedTarget ?: return null
        val focusPoint = smoothedFocusPoint ?: (target.focusX to target.focusY)
        val sr = sensorRect ?: return null
        val sensorBounds = sr.toAIMeteringRect()
        val focusRegion = AIFocusMeteringMath.clampMeteringRect(
            AIFocusMeteringMath.normalizedRectToSensorRect(
                region = AIFocusMeteringMath.focusRegionFor(
                    subjectType = target.subjectType,
                    focusX = focusPoint.first,
                    focusY = focusPoint.second,
                    smoothedBounds = smoothed,
                    confidence = target.confidence
                ),
                sensorBounds = sensorBounds
            ),
            sensorBounds
        )
        val exposureRegion = AIFocusMeteringMath.clampMeteringRect(
            AIFocusMeteringMath.normalizedRectToSensorRect(
                region = AIFocusMeteringMath.exposureRegionFor(
                    subjectType = target.subjectType,
                    smoothedBounds = smoothed,
                    confidence = target.confidence
                ),
                sensorBounds = sensorBounds
            ),
            sensorBounds
        )
        return focusRegion.toAndroidRect() to exposureRegion.toAndroidRect()
    }

    private fun estimateFocusEdgeConfidence(target: SubjectMeteringTarget): Float {
        val typeWeight = when (target.subjectType) {
            SubjectType.EYE -> 1.0f
            SubjectType.FACE -> 0.92f
            SubjectType.HEAD -> 0.88f
            SubjectType.OBJECT -> 0.78f
            SubjectType.UPPER_BODY -> 0.72f
            SubjectType.BODY -> 0.66f
            SubjectType.FULL_BODY -> 0.62f
            SubjectType.GROUP -> 0.54f
        }
        val boundsArea = (target.subjectBounds.width() * target.subjectBounds.height()).coerceIn(0f, 1f)
        val sizeConfidence = ((boundsArea - 0.015f) / 0.16f).coerceIn(0f, 1f)
        return (
            target.confidence.coerceIn(0f, 1f) * 0.58f +
                typeWeight * 0.28f +
                sizeConfidence * 0.14f
            ).coerceIn(0f, 1f)
    }

    private fun cancelLossWatchdog() {
        lossWatchdogJob?.cancel()
        lossWatchdogJob = null
    }

    private fun resetTrackingState(clearMeteringRegion: Boolean) {
        if (clearMeteringRegion) {
            camera.clearFocusRegion()
        }
        resetLensDriveState()
        focusStability.reset()
        focusLockUntilMs = 0L
        sceneCutReacquireArmed = false
        smoothedTarget = null
        lastSubjectType = null
        lastTrackId = -1
        lastAppliedFocusRegion = null
        lastAppliedExposureRegion = null
        lastAcceptedTargetMs = 0L
        subjectPriority = 0f
        consecutiveTrackingFrames = 0
        focusTransitionProgress = 0f
        previousFocusTarget = null
        smoothedFocusPoint = null
        subjectVelocity = 0f to 0f
        predictiveOffset = 0f to 0f
    }

    private fun resetLensDriveState() {
        if (camera.aiDrivenFocusDist >= 0f) {
            camera.clearAiDrivenFocusDistance()
        }
        focusPidState = FocusPidState()
    }

    private fun adaptiveMoveThreshold(base: Float): Float {
        val priorityBias = 1f - (subjectPriority.coerceIn(0f, 1f) * 0.18f)
        return (base * priorityBias).coerceAtLeast(0.002f)
    }

    // AF-SD (F12): consume the per-frame scene contrast only when the consumer gate is on; otherwise
    // reproduce the legacy DEFAULT_SCENE_CONTRAST constant (bit-for-bit). The scene-cut risk block is
    // additionally still behind SCENE_CUT_RISK_GATING_ENABLED, so this is a validation-ready seam.
    private fun effectiveSceneContrast(target: SubjectMeteringTarget): Float =
        if (AIFocusSceneContrastFlag.enabled) target.sceneContrast else DEFAULT_SCENE_CONTRAST

    private fun computeSubjectPriority(target: SubjectMeteringTarget): Float {
        val typeWeight = when (target.subjectType) {
            SubjectType.EYE -> 1.0f
            SubjectType.FACE -> 0.92f
            SubjectType.HEAD -> 0.86f
            SubjectType.UPPER_BODY -> 0.80f
            SubjectType.BODY -> 0.74f
            SubjectType.FULL_BODY -> 0.70f
            SubjectType.OBJECT -> 0.66f
            SubjectType.GROUP -> 0.58f
        }
        val confidenceWeight = target.confidence.coerceIn(0f, 1f)
        return (typeWeight * 0.65f + confidenceWeight * 0.35f).coerceIn(0f, 1f)
    }

    private fun predictMotionCompensatedRegion(
        current: AIFloatRect?,
        incoming: AIFloatRect,
        nowMs: Long
    ): AIFloatRect {
        if (current == null || lastTargetMs <= 0L) {
            subjectVelocity = 0f to 0f
            predictiveOffset = 0f to 0f
            return incoming
        }

        val dtSeconds = ((nowMs - lastTargetMs).coerceAtLeast(1L)).toFloat() / 1000f
        val instantVx = (incoming.centerX() - current.centerX()) / dtSeconds
        val instantVy = (incoming.centerY() - current.centerY()) / dtSeconds
        val velocityAlpha = if (strictHighEndEnabled()) 0.40f else 0.28f
        subjectVelocity = (
            subjectVelocity.first + (instantVx - subjectVelocity.first) * velocityAlpha
            ) to (
            subjectVelocity.second + (instantVy - subjectVelocity.second) * velocityAlpha
            )

        val leadSeconds = if (strictHighEndEnabled()) {
            PREDICTION_LEAD_SECONDS_STRICT
        } else {
            PREDICTION_LEAD_SECONDS_STANDARD
        }
        val offsetX =
            (subjectVelocity.first * leadSeconds).coerceIn(-MAX_PREDICTION_OFFSET, MAX_PREDICTION_OFFSET)
        val offsetY =
            (subjectVelocity.second * leadSeconds).coerceIn(-MAX_PREDICTION_OFFSET, MAX_PREDICTION_OFFSET)
        predictiveOffset = offsetX to offsetY

        return AIFloatRect(
            left = incoming.left + offsetX,
            top = incoming.top + offsetY,
            right = incoming.right + offsetX,
            bottom = incoming.bottom + offsetY
        ).normalizedSafe()
    }

    private fun RectF.toAIFloatRect(): AIFloatRect =
        AIFloatRect(left = left, top = top, right = right, bottom = bottom)

    private fun Rect.toAIMeteringRect(): AIMeteringRect =
        AIMeteringRect(left = left, top = top, right = right, bottom = bottom)

    private fun AIMeteringRect.toAndroidRect(): Rect =
        Rect(left, top, right, bottom)
}
