package com.neoproc.neoproccamx.camera

import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.utils.ExposureCalculator
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Capture-pipeline identity used by the unified video-control decision.
 *
 * This deliberately separates ordinary SDR video from the PRO pipeline even though both currently
 * share [CinemaVideoMode.PRO] at the persistence boundary. The controller integration supplies the
 * professional-controls signal when it maps its runtime state into this pure model.
 */
internal enum class UnifiedVideoControlMode {
    VIDEO,
    PRO,
    NDR,
    LOG,
    RAW;

    companion object {
        fun fromRuntime(
            cinemaMode: CinemaVideoMode,
            hdrMode: String?,
            rawSensorActive: Boolean,
            professionalControlsActive: Boolean
        ): UnifiedVideoControlMode {
            if (rawSensorActive || cinemaMode == CinemaVideoMode.RAW) return RAW
            val normalizedHdrMode = hdrMode?.trim().orEmpty()
            if (normalizedHdrMode.isNotEmpty() &&
                !normalizedHdrMode.equals("OFF", ignoreCase = true)
            ) {
                return NDR
            }
            if (cinemaMode == CinemaVideoMode.LOG) return LOG
            return if (professionalControlsActive) PRO else VIDEO
        }
    }
}

internal data class UnifiedVideoSensorLimits(
    val isoRange: CameraIntRange,
    val exposureTimeRangeNs: CameraLongRange,
    val exposureCompensationRange: CameraIntRange,
    val exposureCompensationStepStops: Float,
    val whiteBalanceRangeKelvin: CameraIntRange,
    val maxFocusDistanceDiopters: Float,
    val supportsManualSensor: Boolean,
    val supportsFixedWhiteBalance: Boolean,
    val supportsManualFocusDistance: Boolean,
    /** API 36 AE priority: operator ISO remains fixed while the HAL continuously meters shutter. */
    val supportsIsoPriority: Boolean = false,
    /** API 36 AE priority: operator shutter remains fixed while the HAL continuously meters ISO. */
    val supportsShutterPriority: Boolean = false,
    /** App-owned luma loop used only when the HAL does not expose ISO-priority AE. */
    val supportsSoftwareIsoPriority: Boolean = false,
    /** App-owned luma loop used only when the HAL does not expose shutter-priority AE. */
    val supportsSoftwareShutterPriority: Boolean = false
)

/** Nullable values mean automatic authority; override flags distinguish an explicit zero from AUTO. */
internal data class UnifiedVideoManualControls(
    val iso: Int? = null,
    val exposureTimeNs: Long? = null,
    val exposureCompensationIndex: Int = 0,
    val exposureCompensationOverrideActive: Boolean = false,
    val whiteBalanceKelvin: Int? = null,
    val whiteBalanceTint: Int = 0,
    val focusDistanceDiopters: Float? = null,
    val focusOverrideActive: Boolean = false
)

/**
 * Timestamp-coherent automatic inputs. Upstream engines may measure the scene independently, but
 * only this coordinator decides which value owns each Camera2 control key.
 */
internal data class UnifiedVideoAutomaticSignals(
    val meteredIso: Int? = null,
    val meteredExposureTimeNs: Long? = null,
    val faceExposureBiasStops: Float = 0f,
    val faceExposureConfidence: Float = 0f,
    val highlightProtectionBiasStops: Float = 0f,
    val sensorLinearNdrBiasStops: Float = 0f,
    val measuredWhiteBalanceKelvin: Int? = null,
    val measuredWhiteBalanceTint: Int = 0,
    val measuredWhiteBalanceConfidence: Float = 0f,
    val autoWhiteBalanceConverged: Boolean = false,
    val aiFocusDistanceDiopters: Float? = null,
    val aiFocusConfidence: Float = 0f,
    val previousExposureCompensationIndex: Int? = null,
    val previousFocusDistanceDiopters: Float? = null,
    val elapsedSincePreviousDecisionSeconds: Float = 0f,
    /** Complementary shutter generated from a fresh app-owned luma loop for fixed-ISO operation. */
    val softwareIsoPriorityExposureTimeNs: Long? = null,
    /** Complementary ISO generated from a fresh app-owned luma loop for fixed-shutter operation. */
    val softwareShutterPriorityIso: Int? = null,
    /** System-owned shutter cap used only by the narrowly scoped NPV10 1K/30 cadence guard. */
    val cadenceGuardExposureTimeNs: Long? = null,
    /** Fresh, luma-controlled ISO paired atomically with [cadenceGuardExposureTimeNs]. */
    val cadenceGuardSensorSensitivityIso: Int? = null
)

internal data class UnifiedVideoControlRequest(
    val generation: Long,
    val mode: UnifiedVideoControlMode,
    val detailProfile: VideoDetailProfile,
    val targetFps: Int,
    val frameExposureBudgetNs: Long = 0L,
    val recording: Boolean,
    val limits: UnifiedVideoSensorLimits,
    val manual: UnifiedVideoManualControls = UnifiedVideoManualControls(),
    val automatic: UnifiedVideoAutomaticSignals = UnifiedVideoAutomaticSignals()
)

internal enum class UnifiedExposureAuthority {
    PLATFORM_AE,
    USER_ISO_PRIORITY,
    USER_SHUTTER_PRIORITY,
    USER_SOFTWARE_ISO_PRIORITY,
    USER_SOFTWARE_SHUTTER_PRIORITY,
    SYSTEM_NPV10_CADENCE_GUARD,
    USER_MANUAL_SENSOR
}

internal data class UnifiedExposureDecision(
    val authority: UnifiedExposureAuthority,
    val sensorSensitivityIso: Int?,
    val exposureTimeNs: Long?,
    val exposureCompensationIndex: Int,
    val adaptiveBiasStops: Float,
    val appliedManualEvStops: Float,
    val frameExposureBudgetNs: Long,
    val userIsoPreserved: Boolean,
    val userExposureTimePreserved: Boolean
)

internal enum class UnifiedWhiteBalanceAuthority {
    PLATFORM_AUTO,
    USER_FIXED
}

internal data class UnifiedWhiteBalanceDecision(
    val authority: UnifiedWhiteBalanceAuthority,
    val fixedKelvin: Int?,
    val fixedTint: Int?,
    val requestAutoLock: Boolean,
    val stableFallbackKelvin: Int?,
    val stableFallbackTint: Int
)

internal enum class UnifiedFocusAuthority {
    CONTINUOUS_AUTO,
    AI_LENS_DISTANCE,
    USER_MANUAL
}

internal data class UnifiedFocusDecision(
    val authority: UnifiedFocusAuthority,
    val lensFocusDistanceDiopters: Float?,
    val applyAutoFocusRegions: Boolean,
    val sourceConfidence: Float
)

internal enum class UnifiedVideoControlRejection {
    MANUAL_SENSOR_UNSUPPORTED,
    FIXED_WHITE_BALANCE_UNSUPPORTED,
    MANUAL_FOCUS_UNSUPPORTED,
    INVALID_MANUAL_FOCUS,
    FULL_MANUAL_EXPOSURE_IGNORES_EV,
    PARTIAL_MANUAL_ADAPTIVE_AUTHORITY_UNAVAILABLE,
    INVALID_SOFTWARE_ISO_PRIORITY_EVIDENCE,
    INVALID_SOFTWARE_SHUTTER_PRIORITY_EVIDENCE
}

/**
 * One immutable request decision. Every controlled Camera2 key has exactly one authority in this
 * object, preventing WB/AE/focus helpers from independently overwriting one another.
 */
internal data class UnifiedVideoControlDecision(
    val generation: Long,
    val mode: UnifiedVideoControlMode,
    val detailProfile: VideoDetailProfile,
    val exposure: UnifiedExposureDecision,
    val whiteBalance: UnifiedWhiteBalanceDecision,
    val focus: UnifiedFocusDecision,
    val rejections: Set<UnifiedVideoControlRejection>
)

internal object UnifiedVideoControlCoordinator {
    private const val MIN_FACE_EXPOSURE_CONFIDENCE = 0.55f
    private const val MIN_WB_CONFIDENCE = 0.55f
    private const val SUPPORTED_TINT_MIN = -150
    private const val SUPPORTED_TINT_MAX = 150
    private const val FOCUS_SCENE_CHANGE_CONFIDENCE_START = 0.82f
    private const val FOCUS_SCENE_CHANGE_FULL_DELTA_DIOPTERS = 1.5f
    private const val FOCUS_SCENE_CHANGE_MAX_MULTIPLIER = 3.5f

    private data class ProfileTuning(
        val focusConfidenceFloor: Float,
        val focusSlewDioptersPerSecond: Float,
        val focusDeadbandDiopters: Float,
        val wbConfidenceFloor: Float,
        val maxEvDecreaseSteps: Int,
        val maxEvIncreaseSteps: Int
    )

    fun resolve(request: UnifiedVideoControlRequest): UnifiedVideoControlDecision {
        val rejections = linkedSetOf<UnifiedVideoControlRejection>()
        val tuning = tuning(request.detailProfile)
        return UnifiedVideoControlDecision(
            generation = request.generation.coerceAtLeast(0L),
            mode = request.mode,
            detailProfile = request.detailProfile,
            exposure = resolveExposure(request, tuning, rejections),
            whiteBalance = resolveWhiteBalance(request, tuning, rejections),
            focus = resolveFocus(request, tuning, rejections),
            rejections = rejections.toSet()
        )
    }

    private fun resolveExposure(
        request: UnifiedVideoControlRequest,
        tuning: ProfileTuning,
        rejections: MutableSet<UnifiedVideoControlRejection>
    ): UnifiedExposureDecision {
        val isoRange = normalized(request.limits.isoRange, minimum = 1)
        val exposureRange = normalized(request.limits.exposureTimeRangeNs, minimum = 1L)
        val evRange = normalized(request.limits.exposureCompensationRange)
        val frameBudget = resolveFrameExposureBudget(request, exposureRange)
        val exposureUpper = minOf(exposureRange.upper, frameBudget).coerceAtLeast(exposureRange.lower)
        val boundedExposureRange = CameraLongRange(exposureRange.lower, exposureUpper)
        val requestedIso = request.manual.iso
        val requestedExposure = request.manual.exposureTimeNs
        val manualSensorRequested = requestedIso != null || requestedExposure != null
        val userIso = requestedIso?.coerceIn(isoRange.lower, isoRange.upper)
        val userExposure = requestedExposure?.coerceIn(
            boundedExposureRange.lower,
            boundedExposureRange.upper
        )
        val userEvIndex = if (request.manual.exposureCompensationOverrideActive) {
            request.manual.exposureCompensationIndex.coerceIn(evRange.lower, evRange.upper)
        } else {
            0
        }

        // A single manual axis must remain adaptive on the other axis while the camera is reframed.
        // API 36 AE-priority modes provide exactly that contract without freezing one stale metered
        // frame: ISO-priority lets AE drive shutter; shutter-priority lets AE drive ISO.
        if (userIso != null && userExposure == null && request.limits.supportsIsoPriority) {
            return UnifiedExposureDecision(
                authority = UnifiedExposureAuthority.USER_ISO_PRIORITY,
                sensorSensitivityIso = userIso,
                exposureTimeNs = null,
                exposureCompensationIndex = userEvIndex,
                adaptiveBiasStops = 0f,
                appliedManualEvStops = userEvIndex * validEvStep(request.limits),
                frameExposureBudgetNs = frameBudget,
                userIsoPreserved = true,
                userExposureTimePreserved = true
            )
        }
        if (userIso == null && userExposure != null && request.limits.supportsShutterPriority) {
            return UnifiedExposureDecision(
                authority = UnifiedExposureAuthority.USER_SHUTTER_PRIORITY,
                sensorSensitivityIso = null,
                exposureTimeNs = userExposure,
                exposureCompensationIndex = userEvIndex,
                adaptiveBiasStops = 0f,
                appliedManualEvStops = userEvIndex * validEvStep(request.limits),
                frameExposureBudgetNs = frameBudget,
                userIsoPreserved = true,
                userExposureTimePreserved = true
            )
        }

        val softwareExposureEvidence = request.automatic.softwareIsoPriorityExposureTimeNs
        val softwareExposure = softwareExposureEvidence
            ?.takeIf { it in boundedExposureRange.lower..boundedExposureRange.upper }
        if (userIso != null && userExposure == null &&
            request.limits.supportsSoftwareIsoPriority &&
            softwareExposureEvidence != null && softwareExposure == null
        ) {
            // Never turn a stale/corrupt app-owned meter result into a legal boundary value. A
            // boundary clamp can be many stops from the scene and would seize manual authority.
            rejections += UnifiedVideoControlRejection.INVALID_SOFTWARE_ISO_PRIORITY_EVIDENCE
        }
        if (userIso != null && userExposure == null &&
            request.limits.supportsSoftwareIsoPriority && softwareExposure != null
        ) {
            return UnifiedExposureDecision(
                authority = UnifiedExposureAuthority.USER_SOFTWARE_ISO_PRIORITY,
                sensorSensitivityIso = userIso,
                exposureTimeNs = softwareExposure,
                exposureCompensationIndex = 0,
                adaptiveBiasStops = 0f,
                appliedManualEvStops = userEvIndex * validEvStep(request.limits),
                frameExposureBudgetNs = frameBudget,
                userIsoPreserved = true,
                userExposureTimePreserved = true
            )
        }
        val softwareIsoEvidence = request.automatic.softwareShutterPriorityIso
        val softwareIso = softwareIsoEvidence?.takeIf { it in isoRange.lower..isoRange.upper }
        if (userIso == null && userExposure != null &&
            request.limits.supportsSoftwareShutterPriority &&
            softwareIsoEvidence != null && softwareIso == null
        ) {
            rejections += UnifiedVideoControlRejection.INVALID_SOFTWARE_SHUTTER_PRIORITY_EVIDENCE
        }
        if (userIso == null && userExposure != null &&
            request.limits.supportsSoftwareShutterPriority && softwareIso != null
        ) {
            return UnifiedExposureDecision(
                authority = UnifiedExposureAuthority.USER_SOFTWARE_SHUTTER_PRIORITY,
                sensorSensitivityIso = softwareIso,
                exposureTimeNs = userExposure,
                exposureCompensationIndex = 0,
                adaptiveBiasStops = 0f,
                appliedManualEvStops = userEvIndex * validEvStep(request.limits),
                frameExposureBudgetNs = frameBudget,
                userIsoPreserved = true,
                userExposureTimePreserved = true
            )
        }

        val partialManualSensorRequested = (userIso == null) != (userExposure == null)

        if (manualSensorRequested && request.limits.supportsManualSensor &&
            !partialManualSensorRequested
        ) {
            val baselineIso = userIso ?: request.automatic.meteredIso
                ?.coerceIn(isoRange.lower, isoRange.upper)
                ?: isoRange.lower
            val baselineExposure = userExposure
                ?: request.automatic.meteredExposureTimeNs
                    ?.coerceIn(boundedExposureRange.lower, boundedExposureRange.upper)
                ?: ExposureCalculator
                    .shutterFor180Rule(request.targetFps.coerceAtLeast(1))
                    .coerceIn(boundedExposureRange.lower, boundedExposureRange.upper)
            val requestedEvStops = if (request.manual.exposureCompensationOverrideActive) {
                request.manual.exposureCompensationIndex
                    .coerceIn(evRange.lower, evRange.upper) * validEvStep(request.limits)
            } else {
                0f
            }
            val adjusted = when {
                userIso != null && userExposure != null -> {
                    if (request.manual.exposureCompensationOverrideActive &&
                        request.manual.exposureCompensationIndex != 0
                    ) {
                        rejections += UnifiedVideoControlRejection.FULL_MANUAL_EXPOSURE_IGNORES_EV
                    }
                    ManualSensorEvCompensationResult(userIso, userExposure, 0f)
                }
                userIso != null -> {
                    val adjustedExposure = scaleExposure(
                        baselineExposure,
                        requestedEvStops,
                        boundedExposureRange
                    )
                    ManualSensorEvCompensationResult(
                        iso = userIso,
                        exposureNs = adjustedExposure,
                        evStops = realizedStops(
                            baseline = baselineExposure.toDouble(),
                            adjusted = adjustedExposure.toDouble()
                        )
                    )
                }
                else -> {
                    val adjustedIso = scaleIso(baselineIso, requestedEvStops, isoRange)
                    ManualSensorEvCompensationResult(
                        iso = adjustedIso,
                        exposureNs = requireNotNull(userExposure),
                        evStops = realizedStops(
                            baseline = baselineIso.toDouble(),
                            adjusted = adjustedIso.toDouble()
                        )
                    )
                }
            }
            return UnifiedExposureDecision(
                authority = UnifiedExposureAuthority.USER_MANUAL_SENSOR,
                sensorSensitivityIso = adjusted.iso,
                exposureTimeNs = adjusted.exposureNs,
                exposureCompensationIndex = 0,
                adaptiveBiasStops = 0f,
                appliedManualEvStops = adjusted.evStops,
                frameExposureBudgetNs = frameBudget,
                userIsoPreserved = userIso == null || adjusted.iso == userIso,
                userExposureTimePreserved = userExposure == null || adjusted.exposureNs == userExposure
            )
        }

        if (partialManualSensorRequested && request.limits.supportsManualSensor) {
            // Freezing one pre-switch metered axis is not adaptive exposure and can white out a pan.
            // If neither HAL priority nor a fresh app-owned luma loop is available, retain platform
            // AE and make the unsupported partial authority explicit.
            rejections +=
                UnifiedVideoControlRejection.PARTIAL_MANUAL_ADAPTIVE_AUTHORITY_UNAVAILABLE
        }

        if (manualSensorRequested && !request.limits.supportsManualSensor) {
            rejections += UnifiedVideoControlRejection.MANUAL_SENSOR_UNSUPPORTED
        }
        // The cadence guard is intentionally lower priority than every operator control. It becomes
        // authoritative only when both values came from one fresh software-AE decision; an incomplete
        // pair falls through to platform AE instead of freezing stale or invented sensor state.
        val cadenceExposure = request.automatic.cadenceGuardExposureTimeNs
            ?.takeIf { it in boundedExposureRange.lower..boundedExposureRange.upper }
        val cadenceIso = request.automatic.cadenceGuardSensorSensitivityIso
            ?.takeIf { it in isoRange.lower..isoRange.upper }
        if (!manualSensorRequested && request.limits.supportsManualSensor &&
            cadenceExposure != null && cadenceIso != null
        ) {
            return UnifiedExposureDecision(
                authority = UnifiedExposureAuthority.SYSTEM_NPV10_CADENCE_GUARD,
                sensorSensitivityIso = cadenceIso,
                exposureTimeNs = cadenceExposure,
                exposureCompensationIndex = 0,
                adaptiveBiasStops = 0f,
                appliedManualEvStops = 0f,
                frameExposureBudgetNs = frameBudget,
                userIsoPreserved = true,
                userExposureTimePreserved = true
            )
        }
        val baseEvIndex = request.manual.exposureCompensationIndex
            .coerceIn(evRange.lower, evRange.upper)
        val adaptiveBias = if (request.manual.exposureCompensationOverrideActive) {
            0f
        } else {
            resolveAdaptiveExposureBias(request)
        }
        val finalEvIndex = if (request.manual.exposureCompensationOverrideActive) {
            baseEvIndex
        } else {
            ExposureCompensationHysteresisPolicy.resolveIndex(
                currentIndex = request.automatic.previousExposureCompensationIndex,
                baseEvIndex = baseEvIndex,
                biasStops = adaptiveBias,
                evStepStops = validEvStep(request.limits),
                evLowerIndex = evRange.lower,
                evUpperIndex = evRange.upper,
                maxDecreaseSteps = tuning.maxEvDecreaseSteps,
                maxIncreaseSteps = tuning.maxEvIncreaseSteps
            )
        }
        return UnifiedExposureDecision(
            authority = UnifiedExposureAuthority.PLATFORM_AE,
            sensorSensitivityIso = null,
            exposureTimeNs = null,
            exposureCompensationIndex = finalEvIndex,
            adaptiveBiasStops = adaptiveBias,
            appliedManualEvStops = 0f,
            frameExposureBudgetNs = frameBudget,
            userIsoPreserved = requestedIso == null,
            userExposureTimePreserved = requestedExposure == null
        )
    }

    private fun resolveAdaptiveExposureBias(request: UnifiedVideoControlRequest): Float {
        val face = request.automatic.faceExposureBiasStops
            .takeIf { it.isFinite() && request.automatic.faceExposureConfidence.isFinite() &&
                request.automatic.faceExposureConfidence >= MIN_FACE_EXPOSURE_CONFIDENCE }
            ?: 0f
        val highlight = request.automatic.highlightProtectionBiasStops
            .takeIf(Float::isFinite)
            ?.coerceIn(-3f, 0f)
            ?: 0f
        val ndr = request.automatic.sensorLinearNdrBiasStops
            .takeIf(Float::isFinite)
            ?.coerceIn(SensorLinearNdrExposurePolicy.MIN_STOPS, 0f)
            ?: 0f
        val protective = minOf(highlight, ndr)
        val candidate = when (request.mode) {
            UnifiedVideoControlMode.VIDEO -> if (protective < 0f) protective else face
            UnifiedVideoControlMode.PRO -> if (protective < 0f) protective else face
            UnifiedVideoControlMode.LOG -> if (protective < 0f) protective else minOf(face, 0.33f)
            UnifiedVideoControlMode.NDR,
            UnifiedVideoControlMode.RAW -> protective
        }
        val bounds = when (request.mode) {
            UnifiedVideoControlMode.VIDEO -> -1.0f to 1.0f
            UnifiedVideoControlMode.PRO -> -1.25f to 0.75f
            UnifiedVideoControlMode.LOG -> -1.25f to 0.33f
            UnifiedVideoControlMode.NDR,
            UnifiedVideoControlMode.RAW -> -1.0f to 0f
        }
        return candidate.coerceIn(bounds.first, bounds.second)
    }

    private fun resolveWhiteBalance(
        request: UnifiedVideoControlRequest,
        tuning: ProfileTuning,
        rejections: MutableSet<UnifiedVideoControlRejection>
    ): UnifiedWhiteBalanceDecision {
        val kelvinRange = normalized(request.limits.whiteBalanceRangeKelvin, minimum = 1)
        val requestedKelvin = request.manual.whiteBalanceKelvin
        if (requestedKelvin != null && request.limits.supportsFixedWhiteBalance) {
            return UnifiedWhiteBalanceDecision(
                authority = UnifiedWhiteBalanceAuthority.USER_FIXED,
                fixedKelvin = requestedKelvin.coerceIn(kelvinRange.lower, kelvinRange.upper),
                fixedTint = request.manual.whiteBalanceTint.coerceIn(
                    SUPPORTED_TINT_MIN,
                    SUPPORTED_TINT_MAX
                ),
                requestAutoLock = false,
                stableFallbackKelvin = null,
                stableFallbackTint = 0
            )
        }
        if (requestedKelvin != null) {
            rejections += UnifiedVideoControlRejection.FIXED_WHITE_BALANCE_UNSUPPORTED
        }
        val measuredConfidence = request.automatic.measuredWhiteBalanceConfidence
            .takeIf(Float::isFinite)
            ?.coerceIn(0f, 1f)
            ?: 0f
        val confidenceFloor = maxOf(MIN_WB_CONFIDENCE, tuning.wbConfidenceFloor, modeWbFloor(request.mode))
        val measuredKelvin = request.automatic.measuredWhiteBalanceKelvin
            ?.takeIf { it in kelvinRange.lower..kelvinRange.upper }
        val measuredTint = request.automatic.measuredWhiteBalanceTint
            .takeIf { it in SUPPORTED_TINT_MIN..SUPPORTED_TINT_MAX }
        // Convergence/confidence without a concrete measured white point is not sufficient lock
        // evidence. On a HAL that cannot lock AUTO, accepting that state would make the fallback
        // jump to an unrelated default Kelvin value at record start. Invalid automatic metadata
        // also fails closed; clamping it to a range boundary would turn corruption into lock proof.
        val stable = request.automatic.autoWhiteBalanceConverged &&
            measuredConfidence >= confidenceFloor && measuredKelvin != null && measuredTint != null
        val fallbackKelvin = measuredKelvin?.takeIf { stable }
        return UnifiedWhiteBalanceDecision(
            authority = UnifiedWhiteBalanceAuthority.PLATFORM_AUTO,
            fixedKelvin = null,
            fixedTint = null,
            requestAutoLock = request.recording && stable,
            stableFallbackKelvin = fallbackKelvin,
            stableFallbackTint = measuredTint?.takeIf { fallbackKelvin != null } ?: 0
        )
    }

    private fun resolveFocus(
        request: UnifiedVideoControlRequest,
        tuning: ProfileTuning,
        rejections: MutableSet<UnifiedVideoControlRejection>
    ): UnifiedFocusDecision {
        val maxDiopters = request.limits.maxFocusDistanceDiopters
            .takeIf { it.isFinite() && it > 0f }
        if (request.manual.focusOverrideActive) {
            val requested = request.manual.focusDistanceDiopters
            if (!request.limits.supportsManualFocusDistance || maxDiopters == null) {
                rejections += UnifiedVideoControlRejection.MANUAL_FOCUS_UNSUPPORTED
                return automaticFocusFallback()
            }
            if (requested == null || !requested.isFinite() || requested < 0f) {
                rejections += UnifiedVideoControlRejection.INVALID_MANUAL_FOCUS
                return automaticFocusFallback()
            }
            return UnifiedFocusDecision(
                authority = UnifiedFocusAuthority.USER_MANUAL,
                lensFocusDistanceDiopters = requested.coerceIn(0f, maxDiopters),
                applyAutoFocusRegions = false,
                sourceConfidence = 1f
            )
        }
        val aiDistance = request.automatic.aiFocusDistanceDiopters
        val aiConfidence = request.automatic.aiFocusConfidence
            .takeIf(Float::isFinite)
            ?.coerceIn(0f, 1f)
            ?: 0f
        if (request.limits.supportsManualFocusDistance && maxDiopters != null &&
            aiDistance != null && aiDistance.isFinite() && aiDistance >= 0f &&
            aiConfidence >= tuning.focusConfidenceFloor
        ) {
            val target = aiDistance.coerceIn(0f, maxDiopters)
            val previous = request.automatic.previousFocusDistanceDiopters
                ?.takeIf { it.isFinite() && it >= 0f }
                ?.coerceIn(0f, maxDiopters)
            val dt = request.automatic.elapsedSincePreviousDecisionSeconds
                .takeIf { it.isFinite() && it > 0f }
                ?: 0f
            val modeSpeedScale = when (request.mode) {
                UnifiedVideoControlMode.NDR,
                UnifiedVideoControlMode.LOG,
                UnifiedVideoControlMode.RAW -> 0.85f
                UnifiedVideoControlMode.VIDEO,
                UnifiedVideoControlMode.PRO -> 1f
            }
            val resolved = if (previous != null) {
                val targetDelta = abs(target - previous)
                when {
                    targetDelta <= tuning.focusDeadbandDiopters -> previous
                    dt > 0f -> approach(
                        current = previous,
                        target = target,
                        maximumDelta = tuning.focusSlewDioptersPerSecond * modeSpeedScale * dt *
                            focusSceneChangeMultiplier(targetDelta, aiConfidence)
                    )
                    else -> target
                }
            } else {
                target
            }
            return UnifiedFocusDecision(
                authority = UnifiedFocusAuthority.AI_LENS_DISTANCE,
                lensFocusDistanceDiopters = resolved.coerceIn(0f, maxDiopters),
                applyAutoFocusRegions = false,
                sourceConfidence = aiConfidence
            )
        }
        return automaticFocusFallback()
    }

    private fun automaticFocusFallback(): UnifiedFocusDecision = UnifiedFocusDecision(
        authority = UnifiedFocusAuthority.CONTINUOUS_AUTO,
        lensFocusDistanceDiopters = null,
        applyAutoFocusRegions = true,
        sourceConfidence = 0f
    )

    private fun tuning(profile: VideoDetailProfile): ProfileTuning = when (profile) {
        VideoDetailProfile.NATURAL_DETAIL -> ProfileTuning(
            focusConfidenceFloor = 0.72f,
            focusSlewDioptersPerSecond = 2f,
            focusDeadbandDiopters = 0.012f,
            wbConfidenceFloor = 0.58f,
            maxEvDecreaseSteps = 1,
            maxEvIncreaseSteps = 1
        )
        VideoDetailProfile.FOCUSED_DETAIL -> ProfileTuning(
            focusConfidenceFloor = 0.62f,
            focusSlewDioptersPerSecond = 3f,
            focusDeadbandDiopters = 0.008f,
            wbConfidenceFloor = 0.62f,
            maxEvDecreaseSteps = 2,
            maxEvIncreaseSteps = 1
        )
        VideoDetailProfile.EXTREME_DETAIL -> ProfileTuning(
            focusConfidenceFloor = 0.56f,
            focusSlewDioptersPerSecond = 5f,
            focusDeadbandDiopters = 0.004f,
            wbConfidenceFloor = 0.68f,
            maxEvDecreaseSteps = 2,
            maxEvIncreaseSteps = 1
        )
    }

    private fun modeWbFloor(mode: UnifiedVideoControlMode): Float = when (mode) {
        UnifiedVideoControlMode.VIDEO -> 0.55f
        UnifiedVideoControlMode.PRO -> 0.60f
        UnifiedVideoControlMode.NDR -> 0.70f
        UnifiedVideoControlMode.LOG -> 0.68f
        UnifiedVideoControlMode.RAW -> 0.72f
    }

    private fun resolveFrameExposureBudget(
        request: UnifiedVideoControlRequest,
        exposureRange: CameraLongRange
    ): Long {
        val nominalFrameNs = (1_000_000_000L / request.targetFps.coerceAtLeast(1))
            .coerceAtLeast(1L)
        val requestedBudget = request.frameExposureBudgetNs
            .takeIf { it > 0L }
            ?.let { minOf(it, nominalFrameNs) }
            ?: nominalFrameNs
        return requestedBudget.coerceIn(exposureRange.lower, exposureRange.upper)
    }

    private fun scaleIso(baseIso: Int, stops: Float, range: CameraIntRange): Int {
        if (!stops.isFinite() || abs(stops) < 1e-6f) return baseIso.coerceIn(range.lower, range.upper)
        return (baseIso.toDouble() * 2.0.pow(stops.toDouble()))
            .roundToInt()
            .coerceIn(range.lower, range.upper)
    }

    private fun scaleExposure(baseNs: Long, stops: Float, range: CameraLongRange): Long {
        if (!stops.isFinite() || abs(stops) < 1e-6f) return baseNs.coerceIn(range.lower, range.upper)
        return (baseNs.toDouble() * 2.0.pow(stops.toDouble()))
            .roundToLong()
            .coerceIn(range.lower, range.upper)
    }

    private fun realizedStops(baseline: Double, adjusted: Double): Float {
        if (baseline <= 0.0 || adjusted <= 0.0) return 0f
        return (kotlin.math.ln(adjusted / baseline) / kotlin.math.ln(2.0)).toFloat()
    }

    private fun approach(current: Float, target: Float, maximumDelta: Float): Float {
        val delta = (target - current).coerceIn(-maximumDelta, maximumDelta)
        return current + delta
    }

    /**
     * Accelerates only a large, high-confidence refocus. Borderline AI measurements retain the
     * profile's original anti-hunt slew, while a proven subject change does not spend several
     * seconds walking from infinity toward a near subject.
     */
    private fun focusSceneChangeMultiplier(deltaDiopters: Float, confidence: Float): Float {
        val confidenceStrength = (
            (confidence - FOCUS_SCENE_CHANGE_CONFIDENCE_START) /
                (1f - FOCUS_SCENE_CHANGE_CONFIDENCE_START)
            ).coerceIn(0f, 1f)
        val distanceStrength = (
            deltaDiopters / FOCUS_SCENE_CHANGE_FULL_DELTA_DIOPTERS
            ).coerceIn(0f, 1f)
        return 1f +
            (FOCUS_SCENE_CHANGE_MAX_MULTIPLIER - 1f) * confidenceStrength * distanceStrength
    }

    private fun validEvStep(limits: UnifiedVideoSensorLimits): Float =
        limits.exposureCompensationStepStops.takeIf { it.isFinite() && it > 0f } ?: 1f

    private fun normalized(range: CameraIntRange, minimum: Int = Int.MIN_VALUE): CameraIntRange {
        val lower = minOf(range.lower, range.upper).coerceAtLeast(minimum)
        val upper = maxOf(range.lower, range.upper).coerceAtLeast(lower)
        return CameraIntRange(lower, upper)
    }

    private fun normalized(range: CameraLongRange, minimum: Long): CameraLongRange {
        val lower = minOf(range.lower, range.upper).coerceAtLeast(minimum)
        val upper = maxOf(range.lower, range.upper).coerceAtLeast(lower)
        return CameraLongRange(lower, upper)
    }
}
