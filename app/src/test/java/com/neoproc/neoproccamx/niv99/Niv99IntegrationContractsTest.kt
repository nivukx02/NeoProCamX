package com.neoproc.neoproccamx.niv99

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Niv99IntegrationContractsTest {
    @Test
    fun `capability resolver requires every production shader`() {
        val capabilities = completeCapabilities()
        val context = Niv99TestFixtures.context()
        val output = Niv99CapabilityDecision()
        Niv99CapabilityResolver.resolve(capabilities, context, output)
        assertEquals(Niv99CapabilityStatus.SUPPORTED_ZERO_COPY, output.status)
        capabilities.shaderTemporalFusion = false
        Niv99CapabilityResolver.resolve(capabilities, context, output)
        assertEquals(Niv99CapabilityStatus.UNSUPPORTED, output.status)
        assertEquals(Niv99UnsupportedReason.REQUIRED_SHADER_UNAVAILABLE, output.reason)
    }

    @Test
    fun `missing zero copy is truthful bounded fallback only when validated`() {
        val capabilities = completeCapabilities().apply { ahardwareBufferImport = false }
        val output = Niv99CapabilityDecision()
        Niv99CapabilityResolver.resolve(capabilities, Niv99TestFixtures.context(), output)
        assertEquals(Niv99CapabilityStatus.UNSUPPORTED, output.status)
        assertEquals(Niv99UnsupportedReason.ZERO_COPY_UNAVAILABLE, output.reason)
        capabilities.boundedCopyFallbackValidated = true
        Niv99CapabilityResolver.resolve(capabilities, Niv99TestFixtures.context(), output)
        assertEquals(Niv99CapabilityStatus.SUPPORTED_BOUNDED_COPY, output.status)
        assertEquals(Niv99UnsupportedReason.ZERO_COPY_UNAVAILABLE, output.reason)
    }

    @Test
    fun `P010 and RAW capabilities are gated independently`() {
        val capabilities = completeCapabilities()
        val output = Niv99CapabilityDecision()
        val p010 = Niv99TestFixtures.context().apply { signalDomain = Niv99SignalDomain.P010_YUV }
        capabilities.p010Import = false
        Niv99CapabilityResolver.resolve(capabilities, p010, output)
        assertEquals(Niv99UnsupportedReason.P010_INPUT_UNAVAILABLE, output.reason)
        val raw = Niv99TestFixtures.context().apply { signalDomain = Niv99SignalDomain.SENSOR_LINEAR_RAW }
        capabilities.p010Import = true
        capabilities.sensorLinearImport = false
        Niv99CapabilityResolver.resolve(capabilities, raw, output)
        assertEquals(Niv99UnsupportedReason.SENSOR_LINEAR_INPUT_UNAVAILABLE, output.reason)
    }

    @Test
    fun `GPU pipeline applies bounded backpressure`() {
        val backend = FakeGpuBackend()
        val pipeline = Niv99GpuPipeline(backend, maximumInFlight = 2)
        val firstContext = Niv99TestFixtures.context(frameNumber = 1L)
        val secondContext = Niv99TestFixtures.context(frameNumber = 2L)
        val thirdContext = Niv99TestFixtures.context(frameNumber = 3L)
        assertTrue(pipeline.initialize(completeCapabilities(), Niv99TestFixtures.activationPermit(firstContext)))
        val first = Niv99GpuFrameToken()
        val second = Niv99GpuFrameToken()
        val third = Niv99GpuFrameToken()
        assertEquals(
            Niv99GpuSubmitStatus.ACCEPTED,
            pipeline.submit(firstContext, 1L, 2L, Niv99TestFixtures.gpuParameters(firstContext), first)
        )
        assertEquals(
            Niv99GpuSubmitStatus.ACCEPTED,
            pipeline.submit(secondContext, 1L, 2L, Niv99TestFixtures.gpuParameters(secondContext), second)
        )
        assertEquals(
            Niv99GpuSubmitStatus.BACKPRESSURE,
            pipeline.submit(thirdContext, 1L, 2L, Niv99TestFixtures.gpuParameters(thirdContext), third)
        )
        assertEquals(2, backend.submissions)
    }

    @Test
    fun `implicit completion failure prevents another backend submission`() {
        val backend = FakeGpuBackend()
        val pipeline = Niv99GpuPipeline(backend, maximumInFlight = 2)
        val firstContext = Niv99TestFixtures.context(frameNumber = 1L)
        val secondContext = Niv99TestFixtures.context(frameNumber = 2L)
        assertTrue(
            pipeline.initialize(
                completeCapabilities(),
                Niv99TestFixtures.activationPermit(firstContext)
            )
        )

        assertEquals(
            Niv99GpuSubmitStatus.ACCEPTED,
            pipeline.submit(
                firstContext,
                1L,
                2L,
                Niv99TestFixtures.gpuParameters(firstContext),
                Niv99GpuFrameToken()
            )
        )
        backend.completion = Niv99GpuCompletionStatus.FAILED
        val secondToken = Niv99GpuFrameToken()

        assertEquals(
            Niv99GpuSubmitStatus.FAILED,
            pipeline.submit(
                secondContext,
                3L,
                4L,
                Niv99TestFixtures.gpuParameters(secondContext),
                secondToken
            )
        )
        assertEquals(1, backend.submissions)
        assertFalse(secondToken.active)
        assertEquals(Niv99GpuState.FAILED, pipeline.currentState())
        assertTrue(pipeline.failureReason().contains("invalid completion"))
    }

    @Test
    fun `GPU generation cancellation makes outstanding token stale`() {
        val backend = FakeGpuBackend()
        val pipeline = Niv99GpuPipeline(backend, 2)
        val context = Niv99TestFixtures.context()
        pipeline.initialize(completeCapabilities(), Niv99TestFixtures.activationPermit(context))
        val token = Niv99GpuFrameToken()
        pipeline.submit(
            context,
            1L,
            2L,
            Niv99TestFixtures.gpuParameters(context),
            token
        )
        pipeline.cancelAndAdvanceGeneration()
        assertEquals(Niv99GpuCompletionStatus.STALE_GENERATION, pipeline.poll(token))
        assertEquals(1, backend.cancelledGenerations)
    }

    @Test
    fun `activation permit can be claimed by only one GPU owner`() {
        val context = Niv99TestFixtures.context()
        val permit = Niv99TestFixtures.activationPermit(context)
        val firstBackend = FakeGpuBackend()
        val secondBackend = FakeGpuBackend()
        val first = Niv99GpuPipeline(firstBackend, 2)
        val second = Niv99GpuPipeline(secondBackend, 2)
        assertTrue(first.initialize(completeCapabilities(), permit))
        assertFalse(second.initialize(completeCapabilities(), permit))
        assertEquals(1, firstBackend.initializations)
        assertEquals(0, secondBackend.initializations)
        assertEquals(Niv99GpuState.FAILED, second.currentState())
    }

    @Test
    fun `GPU owner revalidates capabilities after permit issuance`() {
        val context = Niv99TestFixtures.context()
        val backend = FakeGpuBackend()
        val pipeline = Niv99GpuPipeline(backend, 2)
        val changedCapabilities = completeCapabilities().apply {
            storageImageExtendedFormats = false
        }
        assertFalse(
            pipeline.initialize(
                changedCapabilities,
                Niv99TestFixtures.activationPermit(context)
            )
        )
        assertEquals(0, backend.initializations)
        assertEquals(Niv99GpuState.FAILED, pipeline.currentState())
    }

    @Test
    fun `failed backend retirement quarantines ownership until retry succeeds`() {
        val context = Niv99TestFixtures.context()
        val backend = FakeGpuBackend()
        val pipeline = Niv99GpuPipeline(backend, 2)
        assertTrue(
            pipeline.initialize(
                completeCapabilities(),
                Niv99TestFixtures.activationPermit(context)
            )
        )
        val token = Niv99GpuFrameToken()
        assertEquals(
            Niv99GpuSubmitStatus.ACCEPTED,
            pipeline.submit(
                context,
                1L,
                2L,
                Niv99TestFixtures.gpuParameters(context),
                token
            )
        )
        backend.closeSucceeds = false
        assertFalse(pipeline.closeWithRetirementProof())
        assertEquals(Niv99GpuState.QUARANTINED, pipeline.currentState())
        assertTrue(token.active)
        assertEquals(1, backend.closeCalls)

        backend.closeSucceeds = true
        assertTrue(pipeline.closeWithRetirementProof())
        assertEquals(Niv99GpuState.CLOSED, pipeline.currentState())
        assertEquals(Niv99GpuCompletionStatus.STALE_GENERATION, pipeline.poll(token))
        assertEquals(2, backend.closeCalls)
        assertEquals(1, backend.cancelledGenerations)
    }

    @Test
    fun `unacknowledged generation cancellation preserves outstanding ownership`() {
        val context = Niv99TestFixtures.context()
        val backend = FakeGpuBackend()
        val pipeline = Niv99GpuPipeline(backend, 2)
        pipeline.initialize(completeCapabilities(), Niv99TestFixtures.activationPermit(context))
        val token = Niv99GpuFrameToken()
        pipeline.submit(
            context,
            1L,
            2L,
            Niv99TestFixtures.gpuParameters(context),
            token
        )
        backend.cancelSucceeds = false
        assertEquals(0L, pipeline.cancelAndAdvanceGeneration())
        assertEquals(Niv99GpuState.QUARANTINED, pipeline.currentState())
        assertTrue(token.active)
    }

    @Test
    fun `GPU submission rejects stale aliased and non finite packets before backend`() {
        val context = Niv99TestFixtures.context()
        val backend = FakeGpuBackend()
        val pipeline = Niv99GpuPipeline(backend, 2)
        assertTrue(
            pipeline.initialize(
                completeCapabilities(),
                Niv99TestFixtures.activationPermit(context)
            )
        )

        val staleContext = Niv99TestFixtures.context(sessionGeneration = 2L)
        assertEquals(
            Niv99GpuSubmitStatus.STALE_GENERATION,
            pipeline.submit(
                staleContext,
                1L,
                2L,
                Niv99TestFixtures.gpuParameters(staleContext),
                Niv99GpuFrameToken()
            )
        )

        val aliased = Niv99TestFixtures.gpuParameters(context)
        assertEquals(
            Niv99GpuSubmitStatus.INVALID_ARGUMENT,
            pipeline.submit(context, 9L, 9L, aliased, Niv99GpuFrameToken())
        )

        val nonFinite = Niv99TestFixtures.gpuParameters(context).apply {
            temporalAdmission[0] = Float.NaN
        }
        assertEquals(
            Niv99GpuSubmitStatus.INVALID_ARGUMENT,
            pipeline.submit(context, 1L, 2L, nonFinite, Niv99GpuFrameToken())
        )

        val wrongIdentity = Niv99TestFixtures.gpuParameters(context).apply {
            identity.set(Niv99TestFixtures.CAMERA_ID, 1L, 99L, 99L)
        }
        assertEquals(
            Niv99GpuSubmitStatus.INVALID_ARGUMENT,
            pipeline.submit(context, 1L, 2L, wrongIdentity, Niv99GpuFrameToken())
        )
        assertEquals(0, backend.submissions)
    }

    @Test
    fun `RAW recording route cannot target raw writer for processing`() {
        val backend = FakeGpuBackend()
        val pipeline = Niv99GpuPipeline(backend, 2)
        val context = Niv99TestFixtures.context(mode = Niv99OperatingMode.RAW_PREVIEW)
        pipeline.initialize(completeCapabilities(), Niv99TestFixtures.activationPermit(context))
        val coordinator = Niv99RecordingCoordinator(pipeline)
        val raw = Niv99TestFixtures.format(raw = true)
        assertEquals(
            Niv99RecordingAdmission.ACCEPTED,
            coordinator.start(
                Niv99TestFixtures.CAMERA_ID,
                1L,
                1L,
                Niv99RecordingRoute.RAW_MONITOR_ONLY,
                raw,
                Niv99MediaFormatContract().copyFrom(raw),
                rawWriterHandle = 55L,
                monitorOutputHandle = 77L
            )
        )
        val admission = coordinator.submit(
            context,
            inputHandle = 55L,
            developedOutputHandle = 55L,
            parameters = Niv99TestFixtures.gpuParameters(context),
            token = Niv99GpuFrameToken()
        )
        assertEquals(Niv99RecordingAdmission.ACCEPTED, admission)
        assertEquals(77L, backend.lastOutputHandle)
        assertTrue(backend.lastOutputHandle != 55L)
    }

    @Test
    fun `recording rejects format downgrade`() {
        val pipeline = Niv99GpuPipeline(FakeGpuBackend(), 2)
        val context = Niv99TestFixtures.context()
        pipeline.initialize(
            completeCapabilities(),
            Niv99TestFixtures.activationPermit(context, Niv99ActivationTarget.VIDEO_RECORDING)
        )
        val coordinator = Niv99RecordingCoordinator(pipeline)
        val source = Niv99TestFixtures.format()
        val downgraded = Niv99MediaFormatContract().copyFrom(source).apply { bitDepth = 8 }
        assertEquals(
            Niv99RecordingAdmission.FORMAT_MUTATION,
            coordinator.start(
                Niv99TestFixtures.CAMERA_ID,
                1L,
                1L,
                Niv99RecordingRoute.VIDEO,
                source,
                downgraded,
                0L,
                0L
            )
        )
    }

    @Test
    fun `preview activation permit cannot authorize recording`() {
        val context = Niv99TestFixtures.context()
        val pipeline = Niv99GpuPipeline(FakeGpuBackend(), 2)
        assertTrue(
            pipeline.initialize(
                completeCapabilities(),
                Niv99TestFixtures.activationPermit(context, Niv99ActivationTarget.VIDEO_PREVIEW)
            )
        )
        val format = Niv99TestFixtures.format()
        assertEquals(
            Niv99RecordingAdmission.ACTIVATION_SCOPE_MISMATCH,
            Niv99RecordingCoordinator(pipeline).start(
                cameraId = Niv99TestFixtures.CAMERA_ID,
                sessionGeneration = 1L,
                recordingGeneration = 1L,
                route = Niv99RecordingRoute.VIDEO,
                sourceFormat = format,
                outputFormat = Niv99MediaFormatContract().copyFrom(format),
                rawWriterHandle = 0L,
                monitorOutputHandle = 9L
            )
        )
    }

    @Test
    fun `active recording session cannot be silently replaced`() {
        val context = Niv99TestFixtures.context()
        val pipeline = Niv99GpuPipeline(FakeGpuBackend(), 2)
        assertTrue(
            pipeline.initialize(
                completeCapabilities(),
                Niv99TestFixtures.activationPermit(
                    context,
                    Niv99ActivationTarget.VIDEO_RECORDING
                )
            )
        )
        val coordinator = Niv99RecordingCoordinator(pipeline)
        val format = Niv99TestFixtures.format()
        assertEquals(
            Niv99RecordingAdmission.ACCEPTED,
            coordinator.start(
                Niv99TestFixtures.CAMERA_ID,
                1L,
                1L,
                Niv99RecordingRoute.VIDEO,
                format,
                Niv99MediaFormatContract().copyFrom(format),
                0L,
                9L
            )
        )
        assertEquals(
            Niv99RecordingAdmission.ALREADY_STARTED,
            coordinator.start(
                Niv99TestFixtures.CAMERA_ID,
                1L,
                2L,
                Niv99RecordingRoute.VIDEO,
                format,
                Niv99MediaFormatContract().copyFrom(format),
                0L,
                10L
            )
        )
    }

    @Test
    fun `recording latches implicit GPU completion failure`() {
        val backend = FakeGpuBackend()
        val firstContext = Niv99TestFixtures.context(frameNumber = 1L)
        val pipeline = Niv99GpuPipeline(backend, 2)
        assertTrue(
            pipeline.initialize(
                completeCapabilities(),
                Niv99TestFixtures.activationPermit(
                    firstContext,
                    Niv99ActivationTarget.VIDEO_RECORDING
                )
            )
        )
        val coordinator = Niv99RecordingCoordinator(pipeline)
        val format = Niv99TestFixtures.format()
        assertEquals(
            Niv99RecordingAdmission.ACCEPTED,
            coordinator.start(
                Niv99TestFixtures.CAMERA_ID,
                1L,
                1L,
                Niv99RecordingRoute.VIDEO,
                format,
                Niv99MediaFormatContract().copyFrom(format),
                0L,
                9L
            )
        )
        assertEquals(
            Niv99RecordingAdmission.ACCEPTED,
            coordinator.submit(
                firstContext,
                1L,
                2L,
                Niv99TestFixtures.gpuParameters(firstContext),
                Niv99GpuFrameToken()
            )
        )

        backend.completion = Niv99GpuCompletionStatus.FAILED
        val secondContext = Niv99TestFixtures.context(frameNumber = 2L)
        assertEquals(
            Niv99RecordingAdmission.GPU_FAILURE,
            coordinator.submit(
                secondContext,
                3L,
                4L,
                Niv99TestFixtures.gpuParameters(secondContext),
                Niv99GpuFrameToken()
            )
        )
        assertEquals(
            Niv99RecordingAdmission.GPU_FAILURE,
            coordinator.submit(
                firstContext,
                0L,
                0L,
                Niv99TestFixtures.gpuParameters(firstContext),
                Niv99GpuFrameToken()
            )
        )
        assertEquals(1, backend.submissions)
        assertEquals(2L, coordinator.rejectedFrames())
    }

    @Test
    fun `recording rejects duplicate frame identity`() {
        val pipeline = Niv99GpuPipeline(FakeGpuBackend(), 3)
        val context = Niv99TestFixtures.context()
        pipeline.initialize(
            completeCapabilities(),
            Niv99TestFixtures.activationPermit(context, Niv99ActivationTarget.VIDEO_RECORDING)
        )
        val coordinator = Niv99RecordingCoordinator(pipeline)
        val format = Niv99TestFixtures.format()
        coordinator.start(
            Niv99TestFixtures.CAMERA_ID,
            1L,
            1L,
            Niv99RecordingRoute.VIDEO,
            format,
            Niv99MediaFormatContract().copyFrom(format),
            0L,
            9L
        )
        assertEquals(
            Niv99RecordingAdmission.ACCEPTED,
            coordinator.submit(
                context,
                1L,
                2L,
                Niv99TestFixtures.gpuParameters(context),
                Niv99GpuFrameToken()
            )
        )
        assertEquals(
            Niv99RecordingAdmission.NON_MONOTONIC_TIMESTAMP,
            coordinator.submit(
                context,
                1L,
                2L,
                Niv99TestFixtures.gpuParameters(context),
                Niv99GpuFrameToken()
            )
        )
    }

    @Test
    fun `conversion never aliases or mutates source`() {
        val raw = Niv99TestFixtures.format(raw = true)
        val destination = Niv99TestFixtures.format(raw = false).apply { bitrate = raw.bitrate }
        val contract = Niv99ConversionContract().apply {
            sourceIdentity = "source.npraw"
            destinationIdentity = "source.npraw"
            sourceFingerprint = "sha256:abc"
            expectedFrameCount = 2
            expectedDurationUs = 40_000L
            kind = Niv99ConversionKind.RAW_TO_LOG
            sourceFormat.copyFrom(raw)
            destinationFormat.copyFrom(destination)
        }
        assertEquals(Niv99ConversionStatus.SOURCE_DESTINATION_ALIAS, contract.validate())
        contract.destinationIdentity = "output.mov"
        val coordinator = Niv99ConversionCoordinator()
        assertEquals(Niv99ConversionStatus.READY, coordinator.start(contract))
        assertEquals(Niv99ConversionStatus.ACCEPTED, coordinator.admitFrame("sha256:abc", 0L))
        assertEquals(
            Niv99ConversionStatus.SOURCE_MUTATION_DETECTED,
            coordinator.admitFrame("sha256:changed", 40_000L)
        )
    }

    @Test
    fun `conversion preserves frame count duration and monotonic PTS`() {
        val contract = conversionContract()
        val coordinator = Niv99ConversionCoordinator()
        assertEquals(Niv99ConversionStatus.READY, coordinator.start(contract))
        assertEquals(Niv99ConversionStatus.ACCEPTED, coordinator.admitFrame("sha256:abc", 0L))
        assertEquals(Niv99ConversionStatus.ACCEPTED, coordinator.admitFrame("sha256:abc", 40_000L))
        assertEquals(Niv99ConversionStatus.ACCEPTED, coordinator.admitFrame("sha256:abc", 80_000L))
        assertEquals(Niv99ConversionStatus.COMPLETE, coordinator.finish("sha256:abc"))
        assertEquals(3L, coordinator.acceptedFrameCount())
    }

    @Test
    fun `conversion snapshots contract and rejects active replacement`() {
        val requested = conversionContract()
        val coordinator = Niv99ConversionCoordinator()
        assertEquals(Niv99ConversionStatus.READY, coordinator.start(requested))
        assertEquals(Niv99ConversionStatus.ACCEPTED, coordinator.admitFrame("sha256:abc", 0L))

        requested.expectedFrameCount = 99L
        requested.expectedDurationUs = 9_900_000L
        requested.sourceFormat.framesPerSecond = 1
        requested.sourceFingerprint = "sha256:mutated-caller-object"
        assertEquals(
            Niv99ConversionStatus.ALREADY_STARTED,
            coordinator.start(conversionContract())
        )

        assertEquals(Niv99ConversionStatus.ACCEPTED, coordinator.admitFrame("sha256:abc", 40_000L))
        assertEquals(Niv99ConversionStatus.ACCEPTED, coordinator.admitFrame("sha256:abc", 80_000L))
        assertEquals(Niv99ConversionStatus.COMPLETE, coordinator.finish("sha256:abc"))
        assertEquals(3L, coordinator.acceptedFrameCount())
    }

    @Test
    fun `conversion integrity failure latches until explicit abort`() {
        val coordinator = Niv99ConversionCoordinator()
        assertEquals(Niv99ConversionStatus.READY, coordinator.start(conversionContract()))
        assertEquals(Niv99ConversionStatus.ACCEPTED, coordinator.admitFrame("sha256:abc", 40_000L))
        assertEquals(
            Niv99ConversionStatus.NON_MONOTONIC_PTS,
            coordinator.admitFrame("sha256:abc", 20_000L)
        )
        assertEquals(
            Niv99ConversionStatus.NON_MONOTONIC_PTS,
            coordinator.admitFrame("sha256:abc", 80_000L)
        )
        assertEquals(
            Niv99ConversionStatus.NON_MONOTONIC_PTS,
            coordinator.finish("sha256:abc")
        )
        assertEquals(Niv99ConversionStatus.ABORTED, coordinator.abort())
        assertEquals(Niv99ConversionStatus.READY, coordinator.start(conversionContract()))
    }

    @Test
    fun `conversion rejects reordered timestamp`() {
        val coordinator = Niv99ConversionCoordinator()
        coordinator.start(conversionContract())
        coordinator.admitFrame("sha256:abc", 40_000L)
        assertEquals(
            Niv99ConversionStatus.NON_MONOTONIC_PTS,
            coordinator.admitFrame("sha256:abc", 20_000L)
        )
    }

    @Test
    fun `telemetry reports frame time percentiles and deadline misses`() {
        val telemetry = Niv99Telemetry()
        for (index in 1L..100L) {
            val packet = packet(index).apply {
                processingTimeNs = index * 1_000_000L
                denoise.realtimeDeadlineNs = 40_000_000L
            }
            telemetry.record(packet, residentBytes = index * 1024L)
        }
        val snapshot = Niv99TelemetrySnapshot()
        telemetry.snapshot(snapshot)
        assertEquals(100L, snapshot.processedFrames)
        assertTrue(snapshot.medianFrameTimeNs in 32_000_000L..64_000_000L)
        assertTrue(snapshot.p95FrameTimeNs >= snapshot.medianFrameTimeNs)
        assertEquals(100_000_000L, snapshot.maximumFrameTimeNs)
        assertEquals(60L, snapshot.deadlineMisses)
        assertEquals(102_400L, snapshot.maximumResidentBytes)
    }

    private fun completeCapabilities(): Niv99HardwareCapabilities = Niv99HardwareCapabilities().apply {
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

    private fun conversionContract(): Niv99ConversionContract {
        val source = Niv99TestFixtures.format(raw = true)
        val destination = Niv99TestFixtures.format().apply { bitrate = source.bitrate }
        return Niv99ConversionContract().apply {
            sourceIdentity = "source.npraw"
            destinationIdentity = "output.mov"
            sourceFingerprint = "sha256:abc"
            expectedFrameCount = 3L
            expectedDurationUs = 80_000L
            kind = Niv99ConversionKind.RAW_TO_NIVUKX
            sourceFormat.copyFrom(source)
            destinationFormat.copyFrom(destination)
        }
    }

    private fun packet(frameNumber: Long): Niv99StatePacket = Niv99StatePacket().apply {
        identity.set(Niv99TestFixtures.CAMERA_ID, 1L, frameNumber, frameNumber * 40_000_000L)
        whiteBalance.identity.copyFrom(identity)
        whiteBalance.sourceIdentity.copyFrom(identity)
        whiteBalance.confidence = 0.8f
        whiteBalance.mixedLightConfidence = 0.2f
        denoise.identity.copyFrom(identity)
        denoise.temporalStrength = 0.4f
        denoise.temporalHistoryFrames = 3
        accepted = true
    }

    private class FakeGpuBackend : Niv99GpuBackend {
        var initializations = 0
        var submissions = 0
        var cancelledGenerations = 0
        var lastOutputHandle = 0L
        var completion = Niv99GpuCompletionStatus.PENDING
        var cancelSucceeds = true
        var closeSucceeds = true
        var closeCalls = 0

        override fun initialize(capabilities: Niv99HardwareCapabilities, maximumInFlight: Int): Boolean {
            initializations += 1
            return maximumInFlight > 0 && capabilities.vulkanCompute
        }

        override fun submit(token: Niv99GpuFrameToken, parameters: Niv99GpuDispatchParameters): Boolean {
            submissions += 1
            lastOutputHandle = token.outputHandle
            return parameters.bitDepth >= 10
        }

        override fun poll(token: Niv99GpuFrameToken): Niv99GpuCompletionStatus = completion

        override fun cancelGeneration(generation: Long): Boolean {
            cancelledGenerations += 1
            return cancelSucceeds
        }

        override fun closeAcknowledged(): Boolean {
            closeCalls += 1
            return closeSucceeds
        }
    }
}
