package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.color.NeoGamutCat16Math
import kotlin.math.abs
import kotlin.math.max

/** Project-owned scene-referred source represented by the completed RAW demosaic master. */
enum class ProjectOwnedNdrSourceContract {
    RAW_DEMOSAIC_ACESCG
}

/** Exact transport contract promised by the project-owned visible-preview producer. */
enum class ProjectOwnedNdrProducerContract {
    BT2020_PQ_FULL
}

enum class ProjectOwnedNdrNeutralAxisStatus {
    PASSED,
    INVALID_MATRIX,
    NON_FINITE,
    NON_POSITIVE,
    CHROMATIC
}

/** Immutable result of composing sensor -> XYZ(D50) -> ACEScg on a neutral sensor vector. */
data class ProjectOwnedNdrNeutralAxisValidation(
    val status: ProjectOwnedNdrNeutralAxisStatus,
    val acesCgR: Float,
    val acesCgG: Float,
    val acesCgB: Float,
    val maximumChannelSpread: Float
) {
    val passed: Boolean get() = status == ProjectOwnedNdrNeutralAxisStatus.PASSED
}

/**
 * Immutable sideband proof for one project-owned RAW -> NDR preview frame.
 *
 * Pixel values remain owned by the existing NDR/NIVUKX implementation. This packet proves only that
 * the frame used stabilized RAW colour metadata, a neutral-safe sensor -> ACEScg transform, and the
 * dedicated BT.2020/PQ/full-range producer contract. It deliberately contains no mutable arrays.
 */
data class ProjectOwnedNdrColorProof(
    val stabilizerGeneration: Long,
    val calibrationGeneration: Long,
    val whiteBalanceAndMatrixFingerprint: Long,
    val transformCompatibilityFingerprint: Long,
    val sourceContract: ProjectOwnedNdrSourceContract,
    val producerContract: ProjectOwnedNdrProducerContract,
    val neutralAxisValidation: ProjectOwnedNdrNeutralAxisValidation
) {
    val isValid: Boolean
        get() = stabilizerGeneration > 0L &&
            calibrationGeneration >= 0L &&
            sourceContract == ProjectOwnedNdrSourceContract.RAW_DEMOSAIC_ACESCG &&
            producerContract == ProjectOwnedNdrProducerContract.BT2020_PQ_FULL &&
            neutralAxisValidation.passed

    /** Exact WB may adapt between frames; compatibility is scoped to one proven calibration stream. */
    fun isCompatibleWith(previous: ProjectOwnedNdrColorProof): Boolean =
        isValid && previous.isValid &&
            stabilizerGeneration == previous.stabilizerGeneration &&
            calibrationGeneration == previous.calibrationGeneration &&
            transformCompatibilityFingerprint == previous.transformCompatibilityFingerprint &&
            sourceContract == previous.sourceContract &&
            producerContract == previous.producerContract

    fun diagnosticSummary(): String =
        "stabilizer=$stabilizerGeneration calibration=$calibrationGeneration " +
            "wbMatrix=${java.lang.Long.toUnsignedString(whiteBalanceAndMatrixFingerprint, 16)} " +
            "compat=${java.lang.Long.toUnsignedString(transformCompatibilityFingerprint, 16)} " +
            "neutral=${neutralAxisValidation.status} " +
            "spread=${neutralAxisValidation.maximumChannelSpread}"

    companion object {
        private const val FINGERPRINT_VERSION = 1
        // Camera2 exposes the DNG matrices as Rational values. The validation vivo I2405 quantizes
        // every coefficient to 1/128; its valid forward matrix maps neutral to XYZ
        // (0.96094, 1.00781, 0.82813), then AP1 (0.99103, 1.01501, 1.00349). A synthetic 0.02 AP1
        // cut therefore rejected every real RAW frame. Permit the bounded CAT amplification of that
        // public-metadata quantization, while the independent D50 target test below makes this gate
        // stricter against scaled or genuinely chromatic matrices than a spread-only test.
        private const val MAXIMUM_NEUTRAL_CHANNEL_SPREAD = 0.03f
        private const val MAXIMUM_D50_COMPONENT_ERROR = 0.015f
        private const val MINIMUM_NEUTRAL_CHANNEL = 1.0e-6f
        private val D50_XYZ = floatArrayOf(0.96422f, 1.0f, 0.82521f)
        private const val FNV64_OFFSET_BASIS = -3750763034362895579L
        private const val FNV64_PRIME = 1099511628211L

        fun create(
            stabilizerGeneration: Long,
            calibrationGeneration: Long,
            whiteBalanceRggb: FloatArray,
            sensorToXyz: FloatArray,
            sourceContract: ProjectOwnedNdrSourceContract =
                ProjectOwnedNdrSourceContract.RAW_DEMOSAIC_ACESCG,
            producerContract: ProjectOwnedNdrProducerContract =
                ProjectOwnedNdrProducerContract.BT2020_PQ_FULL
        ): ProjectOwnedNdrColorProof {
            val neutral = validateNeutralAxis(sensorToXyz)
            val wbMatrixFingerprint = fingerprintFloats(whiteBalanceRggb, sensorToXyz)
            var compatibility = FNV64_OFFSET_BASIS
            compatibility = mixLong(compatibility, FINGERPRINT_VERSION.toLong())
            compatibility = mixLong(compatibility, stabilizerGeneration)
            compatibility = mixLong(compatibility, calibrationGeneration)
            compatibility = mixLong(compatibility, sourceContract.ordinal.toLong())
            compatibility = mixLong(compatibility, producerContract.ordinal.toLong())
            compatibility = mixLong(compatibility, neutral.status.ordinal.toLong())
            // A valid neutral has positive channels and bounded chroma. Keep the compatibility token
            // stable while legitimate WB/matrix adaptation changes the exact per-frame fingerprint.
            val polarityMask =
                (if (neutral.acesCgR > 0f) 1L else 0L) or
                    (if (neutral.acesCgG > 0f) 2L else 0L) or
                    (if (neutral.acesCgB > 0f) 4L else 0L)
            compatibility = mixLong(compatibility, polarityMask)
            return ProjectOwnedNdrColorProof(
                stabilizerGeneration = stabilizerGeneration,
                calibrationGeneration = calibrationGeneration,
                whiteBalanceAndMatrixFingerprint = wbMatrixFingerprint,
                transformCompatibilityFingerprint = compatibility,
                sourceContract = sourceContract,
                producerContract = producerContract,
                neutralAxisValidation = neutral
            )
        }

        fun validateNeutralAxis(sensorToXyz: FloatArray?): ProjectOwnedNdrNeutralAxisValidation {
            if (sensorToXyz == null || sensorToXyz.size != 9 || sensorToXyz.any { !it.isFinite() }) {
                return invalidNeutral(ProjectOwnedNdrNeutralAxisStatus.INVALID_MATRIX)
            }
            val neutralPair = runCatching {
                val neutralXyz = NeoGamutCat16Math.multiplyVector(
                    sensorToXyz,
                    floatArrayOf(1f, 1f, 1f)
                )
                val sensorToAcesCg = NeoGamutCat16Math.multiply3x3(
                    XYZ_D50_TO_ACESCG,
                    sensorToXyz
                )
                neutralXyz to NeoGamutCat16Math.multiplyVector(
                    sensorToAcesCg,
                    floatArrayOf(1f, 1f, 1f)
                )
            }.getOrElse {
                return invalidNeutral(ProjectOwnedNdrNeutralAxisStatus.INVALID_MATRIX)
            }
            val neutralXyz = neutralPair.first
            val neutral = neutralPair.second
            if (
                neutralXyz.size != 3 || neutralXyz.any { !it.isFinite() } ||
                neutral.size != 3 || neutral.any { !it.isFinite() }
            ) {
                return invalidNeutral(ProjectOwnedNdrNeutralAxisStatus.NON_FINITE)
            }
            val maximumD50Error = neutralXyz.indices.maxOf { index ->
                abs(neutralXyz[index] - D50_XYZ[index])
            }
            val spread = max(
                max(abs(neutral[0] - neutral[1]), abs(neutral[1] - neutral[2])),
                abs(neutral[0] - neutral[2])
            )
            val status = when {
                neutralXyz.any { it <= MINIMUM_NEUTRAL_CHANNEL } ||
                    neutral.any { it <= MINIMUM_NEUTRAL_CHANNEL } ->
                    ProjectOwnedNdrNeutralAxisStatus.NON_POSITIVE
                maximumD50Error > MAXIMUM_D50_COMPONENT_ERROR ||
                    spread > MAXIMUM_NEUTRAL_CHANNEL_SPREAD ->
                    ProjectOwnedNdrNeutralAxisStatus.CHROMATIC
                else -> ProjectOwnedNdrNeutralAxisStatus.PASSED
            }
            return ProjectOwnedNdrNeutralAxisValidation(
                status = status,
                acesCgR = neutral[0],
                acesCgG = neutral[1],
                acesCgB = neutral[2],
                maximumChannelSpread = spread
            )
        }

        private fun invalidNeutral(
            status: ProjectOwnedNdrNeutralAxisStatus
        ): ProjectOwnedNdrNeutralAxisValidation = ProjectOwnedNdrNeutralAxisValidation(
            status = status,
            acesCgR = Float.NaN,
            acesCgG = Float.NaN,
            acesCgB = Float.NaN,
            maximumChannelSpread = Float.POSITIVE_INFINITY
        )

        private fun fingerprintFloats(vararg arrays: FloatArray): Long {
            var hash = FNV64_OFFSET_BASIS
            arrays.forEach { values ->
                hash = mixLong(hash, values.size.toLong())
                values.forEach { value ->
                    val canonical = if (value == 0f) 0f else value
                    hash = mixLong(
                        hash,
                        java.lang.Float.floatToRawIntBits(canonical).toLong() and 0xffff_ffffL
                    )
                }
            }
            return hash
        }

        private fun mixLong(current: Long, value: Long): Long {
            var hash = current
            hash = (hash xor (value and 0xffff_ffffL)) * FNV64_PRIME
            hash = (hash xor (value ushr 32)) * FNV64_PRIME
            return hash
        }
    }
}

internal enum class ProjectOwnedNdrDataSpaceAdmissionSource {
    BUFFER_DATASPACE,
    PRODUCER_PROOF,
    REJECTED
}

internal data class ProjectOwnedNdrDataSpaceAdmission(
    val accepted: Boolean,
    val source: ProjectOwnedNdrDataSpaceAdmissionSource,
    val reason: String
)

internal data class ProjectOwnedNdrProofStabilityDecision(
    val stable: Boolean,
    val consecutiveCompatibleFrames: Int,
    val proof: ProjectOwnedNdrColorProof?,
    val reason: String
)

/**
 * Requires a short run of mutually compatible, immutable colour proofs before visible ownership.
 *
 * A valid but incompatible frame becomes frame one of a new run. That makes normal recovery bounded
 * while still preventing a one-frame WB/matrix/session discontinuity from becoming visible. This gate
 * is presentation-only; recording and the RAW master never wait for it.
 */
internal class ProjectOwnedNdrProofStabilityGate(
    private val requiredConsecutiveFrames: Int = 2
) {
    init {
        require(requiredConsecutiveFrames >= 2)
    }

    private var previousProof: ProjectOwnedNdrColorProof? = null
    private var consecutiveCompatibleFrames = 0

    @Synchronized
    fun observe(proof: ProjectOwnedNdrColorProof?): ProjectOwnedNdrProofStabilityDecision {
        if (proof?.isValid != true) {
            resetLocked()
            return ProjectOwnedNdrProofStabilityDecision(
                stable = false,
                consecutiveCompatibleFrames = 0,
                proof = null,
                reason = "invalid-proof-reset"
            )
        }
        val previous = previousProof
        val compatible = previous == null || proof.isCompatibleWith(previous)
        consecutiveCompatibleFrames = if (compatible) {
            (consecutiveCompatibleFrames + 1).coerceAtMost(requiredConsecutiveFrames)
        } else {
            1
        }
        previousProof = proof
        val stable = consecutiveCompatibleFrames >= requiredConsecutiveFrames
        return ProjectOwnedNdrProofStabilityDecision(
            stable = stable,
            consecutiveCompatibleFrames = consecutiveCompatibleFrames,
            proof = proof,
            reason = when {
                stable -> "stable-compatible-proof-run"
                compatible -> "awaiting-compatible-proof"
                else -> "incompatible-proof-reset"
            }
        )
    }

    @Synchronized
    fun reset() {
        resetLocked()
    }

    private fun resetLocked() {
        previousProof = null
        consecutiveCompatibleFrames = 0
    }
}

/** Per-latched-buffer validation of the producer's advertised Android dataspace. */
internal object ProjectOwnedNdrDataSpaceAdmissionPolicy {
    fun evaluate(
        dataSpace: Int,
        proof: ProjectOwnedNdrColorProof?
    ): ProjectOwnedNdrDataSpaceAdmission {
        if (proof?.isValid != true) {
            return ProjectOwnedNdrDataSpaceAdmission(
                accepted = false,
                source = ProjectOwnedNdrDataSpaceAdmissionSource.REJECTED,
                reason = "missing-or-invalid-producer-proof"
            )
        }
        if (dataSpace == NdrInputTransportPolicy.DATASPACE_UNKNOWN) {
            return ProjectOwnedNdrDataSpaceAdmission(
                accepted = true,
                source = ProjectOwnedNdrDataSpaceAdmissionSource.PRODUCER_PROOF,
                reason = "unknown-dataspace-with-valid-producer-proof"
            )
        }
        val transport = NdrInputTransportPolicy.resolve(
            dataSpace = dataSpace,
            appliedProfile = null,
            preferPqWhenUnknown = false
        )
        val exactContract = transport.dataSpaceKnown &&
            transport.transfer == NdrInputTransfer.PQ &&
            transport.primaries == NdrInputPrimaries.BT2020 &&
            transport.range == NdrInputRange.FULL
        return if (exactContract) {
            ProjectOwnedNdrDataSpaceAdmission(
                accepted = true,
                source = ProjectOwnedNdrDataSpaceAdmissionSource.BUFFER_DATASPACE,
                reason = "bt2020-pq-full"
            )
        } else {
            ProjectOwnedNdrDataSpaceAdmission(
                accepted = false,
                source = ProjectOwnedNdrDataSpaceAdmissionSource.REJECTED,
                reason = "explicit-dataspace-contract-mismatch"
            )
        }
    }
}

internal data class ProjectOwnedNdrAdmittedFrame(
    val epoch: Long,
    val colorProof: ProjectOwnedNdrColorProof
)

/**
 * Atomic admission gate for the project-owned RAW -> NDR preview queue.
 *
 * Registration happens on the RAW processing thread while admission happens on the compositor's GL
 * thread. Keeping the enabled/epoch/commit/submission tuple behind one very small monitor prevents a
 * rapid mode switch from observing a torn pair and admitting a buffer from the previous look.
 */
internal class ProjectOwnedNdrFrameAdmission {
    private val lock = Any()
    private var enabled = false
    private var epoch = 0L
    private var committedEpoch = NO_EPOCH
    private var submittedEpoch = NO_EPOCH
    private var submittedTimestampNs = 0L
    private var submittedColorProof: ProjectOwnedNdrColorProof? = null

    fun setEnabled(value: Boolean) = synchronized(lock) {
        enabled = value
        if (!value) resetCommitAndSubmission()
    }

    fun begin(nextEpoch: Long) = synchronized(lock) {
        epoch = nextEpoch
        resetCommitAndSubmission()
    }

    fun commit(expectedEpoch: Long): Boolean = synchronized(lock) {
        if (!enabled || expectedEpoch != epoch) return@synchronized false
        // Camera2 may report the same configured session more than once. Re-committing an already
        // active epoch must not erase the exact timestamp registered by an in-flight RAW frame.
        if (committedEpoch == expectedEpoch) return@synchronized true
        committedEpoch = expectedEpoch
        submittedEpoch = NO_EPOCH
        submittedTimestampNs = 0L
        submittedColorProof = null
        true
    }

    fun register(
        expectedEpoch: Long,
        sensorTimestampNs: Long,
        colorProof: ProjectOwnedNdrColorProof
    ): Boolean = synchronized(lock) {
        if (
            !enabled || sensorTimestampNs <= 0L || expectedEpoch != epoch ||
            committedEpoch != expectedEpoch || !colorProof.isValid
        ) return@synchronized false
        submittedEpoch = expectedEpoch
        submittedTimestampNs = sensorTimestampNs
        submittedColorProof = colorProof
        true
    }

    fun cancel(expectedEpoch: Long, sensorTimestampNs: Long) = synchronized(lock) {
        if (submittedEpoch == expectedEpoch && submittedTimestampNs == sensorTimestampNs) {
            submittedEpoch = NO_EPOCH
            submittedTimestampNs = 0L
            submittedColorProof = null
        }
    }

    /** Returns the exact epoch+proof, or null without consuming a newer pending submission. */
    fun admit(sensorTimestampNs: Long): ProjectOwnedNdrAdmittedFrame? = synchronized(lock) {
        val proof = submittedColorProof
        if (
            !enabled || sensorTimestampNs <= 0L || committedEpoch != epoch ||
            submittedEpoch != epoch || submittedTimestampNs != sensorTimestampNs ||
            proof?.isValid != true
        ) return@synchronized null
        val admitted = ProjectOwnedNdrAdmittedFrame(epoch = epoch, colorProof = proof)
        submittedEpoch = NO_EPOCH
        submittedTimestampNs = 0L
        submittedColorProof = null
        admitted
    }

    fun isCommitted(expectedEpoch: Long): Boolean = synchronized(lock) {
        enabled && epoch == expectedEpoch && committedEpoch == expectedEpoch
    }

    private fun resetCommitAndSubmission() {
        committedEpoch = NO_EPOCH
        submittedEpoch = NO_EPOCH
        submittedTimestampNs = 0L
        submittedColorProof = null
    }

    private companion object {
        const val NO_EPOCH = Long.MIN_VALUE
    }
}
