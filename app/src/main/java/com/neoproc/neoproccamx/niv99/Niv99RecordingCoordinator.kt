package com.neoproc.neoproccamx.niv99

enum class Niv99RecordingRoute {
    VIDEO,
    PRO,
    RAW_MONITOR_ONLY,
    SENSOR_LINEAR_DEVELOPED_RECORDING
}

enum class Niv99RecordingAdmission {
    ACCEPTED,
    NOT_STARTED,
    ALREADY_STARTED,
    STALE_IDENTITY,
    NON_MONOTONIC_TIMESTAMP,
    RAW_WRITE_FORBIDDEN,
    FORMAT_MUTATION,
    GPU_BACKPRESSURE,
    GPU_UNSUPPORTED,
    ACTIVATION_SCOPE_MISMATCH,
    INVALID_FRAME_CONTRACT,
    GPU_FAILURE,
    STOPPING
}

class Niv99MediaFormatContract {
    var width: Int = 0
    var height: Int = 0
    var framesPerSecond: Int = 0
    var bitDepth: Int = 0
    var bitrate: Long = 0L
    var codecProfile: String = ""
    var colorPrimaries: String = ""
    var transferFunction: String = ""
    var matrixCoefficients: String = ""
    var audioSampleRate: Int = 0
    var audioChannelCount: Int = 0
    var audioBitrate: Int = 0
    var rawSensorSamples: Boolean = false

    fun copyFrom(other: Niv99MediaFormatContract): Niv99MediaFormatContract {
        width = other.width
        height = other.height
        framesPerSecond = other.framesPerSecond
        bitDepth = other.bitDepth
        bitrate = other.bitrate
        codecProfile = other.codecProfile
        colorPrimaries = other.colorPrimaries
        transferFunction = other.transferFunction
        matrixCoefficients = other.matrixCoefficients
        audioSampleRate = other.audioSampleRate
        audioChannelCount = other.audioChannelCount
        audioBitrate = other.audioBitrate
        rawSensorSamples = other.rawSensorSamples
        return this
    }

    fun isValid(): Boolean =
        width > 0 && height > 0 && framesPerSecond > 0 && bitDepth > 0 && bitrate >= 0L &&
            codecProfile.isNotBlank() && colorPrimaries.isNotBlank() &&
            transferFunction.isNotBlank() && matrixCoefficients.isNotBlank() &&
            audioSampleRate >= 0 && audioChannelCount >= 0 && audioBitrate >= 0

    fun preservesQualityOf(source: Niv99MediaFormatContract): Boolean =
        width == source.width && height == source.height &&
            framesPerSecond == source.framesPerSecond && bitDepth == source.bitDepth &&
            bitrate == source.bitrate && codecProfile == source.codecProfile &&
            colorPrimaries == source.colorPrimaries && transferFunction == source.transferFunction &&
            matrixCoefficients == source.matrixCoefficients &&
            audioSampleRate == source.audioSampleRate &&
            audioChannelCount == source.audioChannelCount && audioBitrate == source.audioBitrate &&
            rawSensorSamples == source.rawSensorSamples
}

class Niv99RecordingSession {
    var cameraId: String = ""
    var sessionGeneration: Long = -1L
    var recordingGeneration: Long = -1L
    var route: Niv99RecordingRoute = Niv99RecordingRoute.VIDEO
    val sourceFormat = Niv99MediaFormatContract()
    val outputFormat = Niv99MediaFormatContract()
    var rawWriterHandle: Long = 0L
    var monitorOutputHandle: Long = 0L
    var active: Boolean = false
    var stopping: Boolean = false
    var terminalGpuFailure: Boolean = false
    var lastFrameNumber: Long = -1L
    var lastTimestampNs: Long = -1L
    var admittedFrames: Long = 0L
    var rejectedFrames: Long = 0L
}

/**
 * Recording integration preserves the established encoder/RAW writer as owner. NIV99 receives only a
 * render target; RAW routes are monitor-only unless the destination is explicitly a developed stream.
 */
class Niv99RecordingCoordinator(
    private val gpuPipeline: Niv99GpuPipeline
) {
    private val session = Niv99RecordingSession()

    @Synchronized
    fun start(
        cameraId: String,
        sessionGeneration: Long,
        recordingGeneration: Long,
        route: Niv99RecordingRoute,
        sourceFormat: Niv99MediaFormatContract,
        outputFormat: Niv99MediaFormatContract,
        rawWriterHandle: Long,
        monitorOutputHandle: Long
    ): Niv99RecordingAdmission {
        if (session.active) return Niv99RecordingAdmission.ALREADY_STARTED
        if (!activationScopeMatches(route)) {
            return Niv99RecordingAdmission.ACTIVATION_SCOPE_MISMATCH
        }
        if (cameraId.isBlank() || sessionGeneration < 0L || recordingGeneration < 0L) {
            return Niv99RecordingAdmission.INVALID_FRAME_CONTRACT
        }
        if (!sourceFormat.isValid() || !outputFormat.isValid() ||
            !outputFormat.preservesQualityOf(sourceFormat)
        ) {
            return Niv99RecordingAdmission.FORMAT_MUTATION
        }
        if (route == Niv99RecordingRoute.RAW_MONITOR_ONLY &&
            (!sourceFormat.rawSensorSamples || rawWriterHandle == 0L ||
                monitorOutputHandle == 0L || monitorOutputHandle == rawWriterHandle)
        ) {
            return Niv99RecordingAdmission.RAW_WRITE_FORBIDDEN
        }
        session.cameraId = cameraId
        session.sessionGeneration = sessionGeneration
        session.recordingGeneration = recordingGeneration
        session.route = route
        session.sourceFormat.copyFrom(sourceFormat)
        session.outputFormat.copyFrom(outputFormat)
        session.rawWriterHandle = rawWriterHandle
        session.monitorOutputHandle = monitorOutputHandle
        session.active = true
        session.stopping = false
        session.terminalGpuFailure = false
        session.lastFrameNumber = -1L
        session.lastTimestampNs = -1L
        session.admittedFrames = 0L
        session.rejectedFrames = 0L
        return Niv99RecordingAdmission.ACCEPTED
    }

    @Synchronized
    fun submit(
        context: Niv99FrameContext,
        inputHandle: Long,
        developedOutputHandle: Long,
        parameters: Niv99GpuDispatchParameters,
        token: Niv99GpuFrameToken
    ): Niv99RecordingAdmission {
        if (!session.active) return Niv99RecordingAdmission.NOT_STARTED
        if (session.stopping) return Niv99RecordingAdmission.STOPPING
        if (session.terminalGpuFailure) {
            session.rejectedFrames += 1L
            return Niv99RecordingAdmission.GPU_FAILURE
        }
        val identity = context.identity
        if (identity.cameraId != session.cameraId ||
            identity.sessionGeneration != session.sessionGeneration ||
            !identity.isComplete()
        ) {
            session.rejectedFrames += 1L
            return Niv99RecordingAdmission.STALE_IDENTITY
        }
        if (identity.frameNumber <= session.lastFrameNumber ||
            identity.sensorTimestampNs <= session.lastTimestampNs
        ) {
            session.rejectedFrames += 1L
            return Niv99RecordingAdmission.NON_MONOTONIC_TIMESTAMP
        }
        val outputHandle = when (session.route) {
            Niv99RecordingRoute.RAW_MONITOR_ONLY -> session.monitorOutputHandle
            Niv99RecordingRoute.VIDEO,
            Niv99RecordingRoute.PRO,
            Niv99RecordingRoute.SENSOR_LINEAR_DEVELOPED_RECORDING -> developedOutputHandle
        }
        if (session.route == Niv99RecordingRoute.RAW_MONITOR_ONLY &&
            outputHandle == session.rawWriterHandle
        ) {
            session.rejectedFrames += 1L
            return Niv99RecordingAdmission.RAW_WRITE_FORBIDDEN
        }
        return when (gpuPipeline.submit(context, inputHandle, outputHandle, parameters, token)) {
            Niv99GpuSubmitStatus.ACCEPTED -> {
                session.lastFrameNumber = identity.frameNumber
                session.lastTimestampNs = identity.sensorTimestampNs
                session.admittedFrames += 1L
                Niv99RecordingAdmission.ACCEPTED
            }
            Niv99GpuSubmitStatus.BACKPRESSURE -> {
                session.rejectedFrames += 1L
                Niv99RecordingAdmission.GPU_BACKPRESSURE
            }
            Niv99GpuSubmitStatus.UNSUPPORTED -> reject(Niv99RecordingAdmission.GPU_UNSUPPORTED)
            Niv99GpuSubmitStatus.STALE_GENERATION -> reject(Niv99RecordingAdmission.STALE_IDENTITY)
            Niv99GpuSubmitStatus.INVALID_ARGUMENT ->
                reject(Niv99RecordingAdmission.INVALID_FRAME_CONTRACT)
            Niv99GpuSubmitStatus.FAILED,
            Niv99GpuSubmitStatus.CLOSED -> {
                session.terminalGpuFailure = true
                reject(Niv99RecordingAdmission.GPU_FAILURE)
            }
        }
    }

    @Synchronized
    fun beginStop() {
        if (!session.active) return
        session.stopping = true
        gpuPipeline.beginDrain()
    }

    @Synchronized
    fun finishStopIfDrained(): Boolean {
        if (!session.active) return true
        if (!session.stopping || !gpuPipeline.pollDrainComplete()) return false
        session.active = false
        session.stopping = false
        session.terminalGpuFailure = false
        session.rawWriterHandle = 0L
        session.monitorOutputHandle = 0L
        return true
    }

    fun admittedFrames(): Long = synchronized(this) { session.admittedFrames }

    fun rejectedFrames(): Long = synchronized(this) { session.rejectedFrames }

    private fun reject(admission: Niv99RecordingAdmission): Niv99RecordingAdmission {
        session.rejectedFrames += 1L
        return admission
    }

    private fun activationScopeMatches(route: Niv99RecordingRoute): Boolean = when (route) {
        Niv99RecordingRoute.VIDEO ->
            gpuPipeline.isActivatedFor(Niv99ActivationTarget.VIDEO_RECORDING)

        Niv99RecordingRoute.PRO ->
            gpuPipeline.isActivatedFor(Niv99ActivationTarget.PRO_RECORDING)

        Niv99RecordingRoute.RAW_MONITOR_ONLY ->
            gpuPipeline.isActivatedFor(Niv99ActivationTarget.RAW_PREVIEW)

        Niv99RecordingRoute.SENSOR_LINEAR_DEVELOPED_RECORDING ->
            gpuPipeline.isActivatedFor(Niv99ActivationTarget.VIDEO_RECORDING) ||
                gpuPipeline.isActivatedFor(Niv99ActivationTarget.PRO_RECORDING)
    }
}
