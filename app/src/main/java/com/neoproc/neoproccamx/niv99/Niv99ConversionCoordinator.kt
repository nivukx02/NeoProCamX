package com.neoproc.neoproccamx.niv99

enum class Niv99ConversionKind {
    P010_TO_SDR,
    P010_TO_NDR,
    RECORD_DEVELOP_TO_SDR,
    RECORD_DEVELOP_TO_NDR,
    RAW_TO_LOG,
    RAW_TO_RAW_DEVELOPED,
    RAW_TO_NDR10,
    RAW_TO_XDR10,
    RAW_TO_NIVUKX
}

enum class Niv99ConversionStatus {
    READY,
    ACCEPTED,
    NOT_STARTED,
    ALREADY_STARTED,
    INVALID_SOURCE,
    SOURCE_DESTINATION_ALIAS,
    FORMAT_MUTATION,
    FRAME_COUNT_MISMATCH,
    NON_MONOTONIC_PTS,
    DURATION_MISMATCH,
    SOURCE_MUTATION_DETECTED,
    GPU_BACKPRESSURE,
    GPU_FAILURE,
    ABORTED,
    COMPLETE
}

class Niv99ConversionContract {
    var sourceIdentity: String = ""
    var destinationIdentity: String = ""
    var sourceFingerprint: String = ""
    var expectedFrameCount: Long = 0L
    var expectedDurationUs: Long = 0L
    var kind: Niv99ConversionKind = Niv99ConversionKind.RAW_TO_LOG
    val sourceFormat = Niv99MediaFormatContract()
    val destinationFormat = Niv99MediaFormatContract()

    fun copyFrom(other: Niv99ConversionContract): Niv99ConversionContract {
        sourceIdentity = other.sourceIdentity
        destinationIdentity = other.destinationIdentity
        sourceFingerprint = other.sourceFingerprint
        expectedFrameCount = other.expectedFrameCount
        expectedDurationUs = other.expectedDurationUs
        kind = other.kind
        sourceFormat.copyFrom(other.sourceFormat)
        destinationFormat.copyFrom(other.destinationFormat)
        return this
    }

    fun validate(): Niv99ConversionStatus {
        if (sourceIdentity.isBlank() || sourceFingerprint.isBlank() || expectedFrameCount < 0L ||
            expectedDurationUs < 0L || !sourceFormat.isValid() ||
            kind.requiresRawSensorSource() && !sourceFormat.rawSensorSamples
        ) {
            return Niv99ConversionStatus.INVALID_SOURCE
        }
        if (destinationIdentity.isBlank() || sourceIdentity == destinationIdentity) {
            return Niv99ConversionStatus.SOURCE_DESTINATION_ALIAS
        }
        if (!preservesProtectedFormat()) return Niv99ConversionStatus.FORMAT_MUTATION
        return Niv99ConversionStatus.READY
    }

    private fun preservesProtectedFormat(): Boolean =
        sourceFormat.width == destinationFormat.width &&
            sourceFormat.height == destinationFormat.height &&
            sourceFormat.framesPerSecond == destinationFormat.framesPerSecond &&
            destinationFormat.bitDepth >= minRequiredOutputBitDepth() &&
            destinationFormat.bitrate == sourceFormat.bitrate &&
            destinationFormat.audioSampleRate == sourceFormat.audioSampleRate &&
            destinationFormat.audioChannelCount == sourceFormat.audioChannelCount &&
            destinationFormat.audioBitrate == sourceFormat.audioBitrate

    private fun minRequiredOutputBitDepth(): Int = when (kind) {
        Niv99ConversionKind.P010_TO_SDR,
        Niv99ConversionKind.P010_TO_NDR,
        Niv99ConversionKind.RECORD_DEVELOP_TO_SDR,
        Niv99ConversionKind.RECORD_DEVELOP_TO_NDR,
        Niv99ConversionKind.RAW_TO_LOG,
        Niv99ConversionKind.RAW_TO_NDR10,
        Niv99ConversionKind.RAW_TO_XDR10,
        Niv99ConversionKind.RAW_TO_NIVUKX -> 10
        Niv99ConversionKind.RAW_TO_RAW_DEVELOPED -> sourceFormat.bitDepth
    }

    private fun Niv99ConversionKind.requiresRawSensorSource(): Boolean = when (this) {
        Niv99ConversionKind.P010_TO_SDR,
        Niv99ConversionKind.P010_TO_NDR,
        Niv99ConversionKind.RECORD_DEVELOP_TO_SDR,
        Niv99ConversionKind.RECORD_DEVELOP_TO_NDR -> false
        Niv99ConversionKind.RAW_TO_LOG,
        Niv99ConversionKind.RAW_TO_RAW_DEVELOPED,
        Niv99ConversionKind.RAW_TO_NDR10,
        Niv99ConversionKind.RAW_TO_XDR10,
        Niv99ConversionKind.RAW_TO_NIVUKX -> true
    }
}

/** Offline conversion ledger: source identity is immutable and output timing must remain exact. */
class Niv99ConversionCoordinator {
    private val contract = Niv99ConversionContract()
    private var sourceFingerprintAtStart: String = ""
    private var acceptedFrames = 0L
    private var firstPtsUs = -1L
    private var lastPtsUs = -1L
    private var started = false
    private var terminalStatus: Niv99ConversionStatus? = null

    @Synchronized
    fun start(requestedContract: Niv99ConversionContract): Niv99ConversionStatus {
        if (started) return Niv99ConversionStatus.ALREADY_STARTED
        val validation = requestedContract.validate()
        if (validation != Niv99ConversionStatus.READY) return validation
        contract.copyFrom(requestedContract)
        sourceFingerprintAtStart = contract.sourceFingerprint
        acceptedFrames = 0L
        firstPtsUs = -1L
        lastPtsUs = -1L
        terminalStatus = null
        started = true
        return Niv99ConversionStatus.READY
    }

    @Synchronized
    fun admitFrame(
        sourceFingerprintNow: String,
        presentationTimeUs: Long
    ): Niv99ConversionStatus {
        if (!started) return Niv99ConversionStatus.NOT_STARTED
        terminalStatus?.let { return it }
        if (sourceFingerprintNow != sourceFingerprintAtStart) {
            return fail(Niv99ConversionStatus.SOURCE_MUTATION_DETECTED)
        }
        if (acceptedFrames >= contract.expectedFrameCount) {
            return fail(Niv99ConversionStatus.FRAME_COUNT_MISMATCH)
        }
        if (presentationTimeUs < 0L || lastPtsUs >= 0L && presentationTimeUs <= lastPtsUs) {
            return fail(Niv99ConversionStatus.NON_MONOTONIC_PTS)
        }
        if (firstPtsUs < 0L) firstPtsUs = presentationTimeUs
        lastPtsUs = presentationTimeUs
        acceptedFrames += 1L
        return Niv99ConversionStatus.ACCEPTED
    }

    @Synchronized
    fun finish(sourceFingerprintNow: String): Niv99ConversionStatus {
        if (!started) return Niv99ConversionStatus.NOT_STARTED
        terminalStatus?.let { return it }
        if (sourceFingerprintNow != sourceFingerprintAtStart) {
            return fail(Niv99ConversionStatus.SOURCE_MUTATION_DETECTED)
        }
        if (acceptedFrames != contract.expectedFrameCount) {
            return fail(Niv99ConversionStatus.FRAME_COUNT_MISMATCH)
        }
        if (acceptedFrames > 1L) {
            val measuredDuration = lastPtsUs - firstPtsUs
            val frameTolerance = 1_000_000L / contract.sourceFormat.framesPerSecond.coerceAtLeast(1)
            if (kotlin.math.abs(measuredDuration - contract.expectedDurationUs) > frameTolerance) {
                return fail(Niv99ConversionStatus.DURATION_MISMATCH)
            }
        }
        started = false
        return Niv99ConversionStatus.COMPLETE
    }

    @Synchronized
    fun abort(): Niv99ConversionStatus {
        if (!started) return Niv99ConversionStatus.NOT_STARTED
        started = false
        terminalStatus = null
        sourceFingerprintAtStart = ""
        return Niv99ConversionStatus.ABORTED
    }

    fun acceptedFrameCount(): Long = synchronized(this) { acceptedFrames }

    private fun fail(status: Niv99ConversionStatus): Niv99ConversionStatus {
        terminalStatus = status
        return status
    }
}
