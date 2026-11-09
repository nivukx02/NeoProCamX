package com.neoproc.neoproccamx.niv99

internal object Niv99TestFixtures {
    const val CAMERA_ID = "test-camera-0"

    fun calibration(cameraId: String = CAMERA_ID): Niv99SensorCalibration =
        Niv99SensorCalibration().apply {
            this.cameraId = cameraId
            cfaPattern = Niv99CfaPattern.RGGB
            blackLevel.fill(0f)
            whiteLevel = 1f
            referenceIlluminant1Kelvin = 2_850f
            referenceIlluminant2Kelvin = 6_500f
            baseShotNoise[0] = 0.0020f
            baseShotNoise[1] = 0.0018f
            baseShotNoise[2] = 0.0018f
            baseShotNoise[3] = 0.0024f
            baseReadNoise[0] = 0.00004f
            baseReadNoise[1] = 0.00003f
            baseReadNoise[2] = 0.00003f
            baseReadNoise[3] = 0.00005f
            referenceIso = 100
            referenceTemperatureCelsius = 25f
            Niv99Math.setIdentity3(calibrationTransform1)
            Niv99Math.setIdentity3(calibrationTransform2)
            Niv99Math.setIdentity3(colorTransform1)
            Niv99Math.setIdentity3(colorTransform2)
            Niv99Math.setIdentity3(forwardMatrix1)
            Niv99Math.setIdentity3(forwardMatrix2)
            colorTransformPairAvailable = true
            calibrationTransformPairAvailable = true
            forwardMatrixPairAvailable = true
            baseNoiseSource = Niv99NoiseCalibrationSource.PROJECT_SENSOR_CALIBRATION
            markUpdated()
        }

    fun context(
        frameNumber: Long = 1L,
        timestampNs: Long = frameNumber * 40_000_000L,
        sessionGeneration: Long = 1L,
        width: Int = 64,
        height: Int = 64,
        mode: Niv99OperatingMode = Niv99OperatingMode.VIDEO,
        profile: Niv99DetailProfile = Niv99DetailProfile.NATURAL
    ): Niv99FrameContext = Niv99FrameContext().apply {
        identity.set(CAMERA_ID, sessionGeneration, frameNumber, timestampNs)
        operatingMode = mode
        signalDomain = Niv99SignalDomain.SENSOR_LINEAR_RAW
        detailProfile = profile
        this.width = width
        this.height = height
        targetFps = 25
        colorProfileId = 11L
        surfaceGeneration = 7L
        codecGeneration = 5L
        iso = 400
        exposureTimeNs = 8_000_000L
        frameDurationNs = 40_000_000L
        rollingShutterSkewNs = 12_000_000L
        motionMagnitude = 0.05f
        gyroMagnitudeRadiansPerSecond = 0.02f
        opticalFlowConfidence = 0.92f
        clippingFraction = 0f
        meanLinearSignal = 0.18f
        memoryPressure = 0.10f
        thermalPressure = 0.10f
        whiteBalanceIntent.clear()
    }

    fun rawPlane(
        width: Int = 64,
        height: Int = 64,
        redResponse: Float = 0.75f,
        greenResponse: Float = 1f,
        blueResponse: Float = 1.25f,
        baseSignal: Float = 0.25f,
        noiseAmplitude: Float = 0f,
        seed: Int = 7
    ): Niv99LinearPlane {
        val samples = FloatArray(width * height)
        var randomState = seed
        for (y in 0 until height) {
            for (x in 0 until width) {
                randomState = randomState * 1_664_525 + 1_013_904_223
                val random = ((randomState ushr 8) and 0xffff) / 65_535f - 0.5f
                val response = when {
                    y and 1 == 0 && x and 1 == 0 -> redResponse
                    y and 1 == 1 && x and 1 == 1 -> blueResponse
                    else -> greenResponse
                }
                val lowFrequencyTexture = ((x / 8 + y / 8) and 1) * 0.012f
                samples[y * width + x] =
                    (baseSignal * response + lowFrequencyTexture + random * noiseAmplitude)
                        .coerceIn(0f, 1f)
            }
        }
        return Niv99LinearPlane().set(
            samples = samples,
            width = width,
            height = height,
            rowStride = width,
            whiteLevel = 1f,
            cfaPattern = Niv99CfaPattern.RGGB
        )
    }

    fun mixedLightPlane(width: Int = 128, height: Int = 64): Niv99LinearPlane {
        val samples = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val warm = x < width / 2
                val redResponse = if (warm) 1.45f else 0.72f
                val blueResponse = if (warm) 0.58f else 1.38f
                val response = when {
                    y and 1 == 0 && x and 1 == 0 -> redResponse
                    y and 1 == 1 && x and 1 == 1 -> blueResponse
                    else -> 1f
                }
                samples[y * width + x] = 0.28f * response
            }
        }
        return Niv99LinearPlane().set(
            samples,
            width,
            height,
            width,
            whiteLevel = 1f,
            cfaPattern = Niv99CfaPattern.RGGB
        )
    }

    fun noiseSnapshot(context: Niv99FrameContext = context()): Niv99NoiseModelSnapshot {
        val output = Niv99NoiseModelSnapshot()
        val result = Niv99NoiseModel().resolve(context, calibration(), null, output)
        check(result == Niv99ValidationResult.VALID)
        return output
    }

    fun capabilityDecision(): Niv99CapabilityDecision = Niv99CapabilityDecision().apply {
        Niv99CapabilityResolver.cpuReferenceDecision(this)
    }

    fun format(raw: Boolean = false): Niv99MediaFormatContract = Niv99MediaFormatContract().apply {
        width = 3840
        height = 2160
        framesPerSecond = 25
        bitDepth = if (raw) 12 else 10
        bitrate = 160_000_000L
        codecProfile = if (raw) "RAW12" else "HEVC_MAIN10"
        colorPrimaries = "BT2020"
        transferFunction = "LINEAR"
        matrixCoefficients = "BT2020_NCL"
        audioSampleRate = 48_000
        audioChannelCount = 2
        audioBitrate = 320_000
        rawSensorSamples = raw
    }

    fun hardwareCapabilities(): Niv99HardwareCapabilities = Niv99HardwareCapabilities().apply {
        vulkanCompute = true
        ahardwareBufferImport = true
        p010Import = true
        sensorLinearImport = true
        rgba16fStorage = true
        rgba32fCalibration = true
        storageImageExtendedFormats = true
        timelineSemaphore = true
        synchronization2 = true
        shaderStatistics = true
        shaderRawDenoise = true
        shaderMotionConfidence = true
        shaderTemporalFusion = true
        shaderReconstruction = true
        shaderQualityTelemetry = true
        maximumHistoryFrames = 8
        maximumWidth = 8192
        maximumHeight = 8192
    }

    fun runtimeIdentity(context: Niv99FrameContext = context()): Niv99RuntimeGraphIdentity =
        Niv99RuntimeGraphIdentity(
            appBuildFingerprint = TEST_BUILD_FINGERPRINT,
            shaderGraphSha256 = TEST_SHADER_SHA256,
            nativeLibrarySha256 = TEST_NATIVE_SHA256,
            deviceBuildFingerprint = TEST_DEVICE_FINGERPRINT,
            gpuDriverFingerprint = TEST_DRIVER_FINGERPRINT,
            cameraId = context.identity.cameraId,
            sessionGeneration = context.identity.sessionGeneration,
            surfaceGeneration = context.surfaceGeneration,
            codecGeneration = context.codecGeneration,
            width = context.width,
            height = context.height,
            targetFps = context.targetFps,
            colorProfileId = context.colorProfileId,
            operatingMode = context.operatingMode,
            signalDomain = context.signalDomain
        )

    fun activationPolicy(identity: Niv99RuntimeGraphIdentity = runtimeIdentity()) =
        Niv99RuntimeActivationPolicy(
            liveFrameActivationEnabled = true,
            approvedCertificateId = TEST_CERTIFICATE_ID,
            approvedEvidenceManifestSha256 = TEST_MANIFEST_SHA256,
            approvedAppBuildFingerprint = identity.appBuildFingerprint,
            approvedShaderGraphSha256 = identity.shaderGraphSha256,
            approvedNativeLibrarySha256 = identity.nativeLibrarySha256
        )

    fun evidenceCertificate(
        target: Niv99ActivationTarget = Niv99ActivationTarget.VIDEO_PREVIEW,
        identity: Niv99RuntimeGraphIdentity = runtimeIdentity(),
        passedEvidenceMask: Long = allActivationEvidence()
    ): Niv99RuntimeEvidenceCertificate = Niv99RuntimeEvidenceCertificate(
        schemaVersion = Niv99RuntimeActivationGate.CERTIFICATE_SCHEMA_VERSION,
        certificateId = TEST_CERTIFICATE_ID,
        evidenceManifestSha256 = TEST_MANIFEST_SHA256,
        appBuildFingerprint = identity.appBuildFingerprint,
        shaderGraphSha256 = identity.shaderGraphSha256,
        nativeLibrarySha256 = identity.nativeLibrarySha256,
        nativeAbiVersion = Niv99RuntimeActivationPolicy.NATIVE_ABI_VERSION,
        deviceBuildFingerprint = identity.deviceBuildFingerprint,
        gpuDriverFingerprint = identity.gpuDriverFingerprint,
        cameraId = identity.cameraId,
        target = target,
        operatingMode = identity.operatingMode,
        signalDomain = identity.signalDomain,
        width = identity.width,
        height = identity.height,
        targetFps = identity.targetFps,
        colorProfileId = identity.colorProfileId,
        issuedAtEpochMs = TEST_NOW_EPOCH_MS - 1_000L,
        expiresAtEpochMs = TEST_NOW_EPOCH_MS + 1_000L,
        passedEvidenceMask = passedEvidenceMask,
        shaderComparedValueCount = 1_895L,
        shaderMaximumAbsoluteError = 0.0009765625,
        physicalDeviceFrameCount = 12_000L,
        controlledSceneCount = 12,
        medianFrameTimeNs = 10_000_000L,
        p95FrameTimeNs = 15_000_000L,
        p99FrameTimeNs = 20_000_000L,
        maximumFrameTimeNs = 30_000_000L,
        frameDeadlineNs = 40_000_000L,
        deadlineMissCount = 0L,
        maximumResidentBytes = 128L * 1024L * 1024L,
        certifiedMemoryBudgetBytes = 512L * 1024L * 1024L,
        thermalRunDurationNs = 31L * 60L * 1_000_000_000L,
        mtf50Retention = 0.95,
        clippingIncrease = 0.001,
        clippingTolerance = 0.002,
        awbStabilityImprovement = 0.08,
        denoiseQualityImprovement = 0.12,
        unexplainedRecordingFrameLoss = 0L,
        nonMonotonicTimestampCount = 0L,
        temporalHistoryLeakCount = 0L,
        visibleTileSeamCount = 0L,
        rawByteMismatchCount = 0L,
        conversionIntegrityFailureCount = 0L,
        longRecordingDurationNs = 31L * 60L * 1_000_000_000L
    )

    fun activationPermit(
        context: Niv99FrameContext = context(),
        target: Niv99ActivationTarget = targetFor(context.operatingMode)
    ): Niv99RuntimeActivationPermit {
        val identity = runtimeIdentity(context)
        val decision = Niv99RuntimeActivationDecision()
        return checkNotNull(
            Niv99RuntimeActivationGate(activationPolicy(identity)).evaluate(
                explicitlyRequested = true,
                target = target,
                identity = identity,
                certificate = evidenceCertificate(target, identity),
                capabilities = hardwareCapabilities(),
                nowEpochMs = TEST_NOW_EPOCH_MS,
                output = decision
            )
        )
    }

    fun gpuParameters(
        context: Niv99FrameContext,
        maximumTiles: Int = 16
    ): Niv99GpuDispatchParameters = Niv99GpuDispatchParameters(maximumTiles).apply {
        identity.copyFrom(context.identity)
        tileCount = 1
        width = context.width
        height = context.height
        bitDepth = if (context.signalDomain == Niv99SignalDomain.P010_YUV) 10 else 16
        inputDomain = context.signalDomain
    }

    fun allActivationEvidence(): Long {
        var mask = 0L
        for (evidence in Niv99ActivationEvidence.entries) mask = mask or evidence.bit
        return mask
    }

    private fun targetFor(mode: Niv99OperatingMode): Niv99ActivationTarget = when (mode) {
        Niv99OperatingMode.VIDEO -> Niv99ActivationTarget.VIDEO_PREVIEW
        Niv99OperatingMode.PRO -> Niv99ActivationTarget.PRO_PREVIEW
        Niv99OperatingMode.RAW_PREVIEW -> Niv99ActivationTarget.RAW_PREVIEW
        Niv99OperatingMode.PHOTO -> Niv99ActivationTarget.PHOTO
        Niv99OperatingMode.CONVERSION_RAW_TO_LOG,
        Niv99OperatingMode.CONVERSION_RAW_TO_RAW_DEVELOPED,
        Niv99OperatingMode.CONVERSION_RAW_TO_XDR10,
        Niv99OperatingMode.CONVERSION_RAW_TO_NIVUKX -> Niv99ActivationTarget.CONVERSION
    }

    const val TEST_NOW_EPOCH_MS = 2_000_000L
    const val TEST_CERTIFICATE_ID = "niv99-test-certificate"
    const val TEST_BUILD_FINGERPRINT = "test-build-fingerprint"
    const val TEST_DEVICE_FINGERPRINT = "test-device-fingerprint"
    const val TEST_DRIVER_FINGERPRINT = "test-vulkan-driver"
    const val TEST_SHADER_SHA256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    const val TEST_NATIVE_SHA256 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    const val TEST_MANIFEST_SHA256 = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
}
