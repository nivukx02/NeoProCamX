package com.neoproc.neoproccamx.ui

import android.os.Build
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.GpuRecordingState
import com.neoproc.neoproccamx.recording.VideoCodecType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeVideoHdrPreviewPolicyTest {

    @Test
    fun `request-bound mode contract matches full parity plan for admitted delivery modes`() {
        val graphs = listOf(
            resolveGraph(cinemaMode = CinemaVideoMode.PRO, requestedHdrMode = "OFF"),
            resolveGraph(cinemaMode = CinemaVideoMode.PRO, requestedHdrMode = "HLG"),
            resolveGraph(cinemaMode = CinemaVideoMode.PRO, requestedHdrMode = "HDR10"),
            resolveGraph(cinemaMode = CinemaVideoMode.PRO, requestedHdrMode = "NDR10+"),
            resolveGraph(cinemaMode = CinemaVideoMode.PRO, requestedHdrMode = "NIVUKX VISION"),
            resolveGraph(
                cinemaMode = CinemaVideoMode.LOG,
                logEnabled = true,
                requestedHdrMode = "OFF"
            ),
            resolveGraph(
                cinemaMode = CinemaVideoMode.RAW,
                rawVideoActive = true,
                requestedHdrMode = "OFF"
            )
        )

        graphs.forEach { graph ->
            val parityPlan = resolveParityPlan(graph)
            val fromParity = resolveWithParity(graph, parityPlan)
            val fromModeContract = resolveWithModeContract(graph)

            assertEquals(
                "Request-bound HDR safety diverged for ${graph.deliveryKind}/${graph.effectiveHdrMode}",
                fromParity,
                fromModeContract
            )
        }
    }

    @Test
    fun `admitted hdr aliases and native profiles activate on an hdr capable Android runtime`() {
        listOf("HLG", "HDR10", "NDR10+", "NIVUKX VISION").forEach { requestedHdrMode ->
            val graph = resolveGraph(
                cinemaMode = CinemaVideoMode.PRO,
                requestedHdrMode = requestedHdrMode
            )
            val parityPlan = resolveParityPlan(graph)
            val modeContract = graph.deliveryState.pipelineContract

            assertEquals(
                "Expected $requestedHdrMode to be admitted by the premium capability model",
                VideoPipelineAdmissionState.ADMITTED,
                modeContract.admissionState
            )
            assertTrue("Expected $requestedHdrMode to retain an HDR mode", graph.effectiveHdrMode != "OFF")
            assertTrue("Expected $requestedHdrMode to retain HDR tone mapping", modeContract.hdrToneMapActive)
            assertHdrRuntimeResult(requestedHdrMode, resolveWithParity(graph, parityPlan))
            assertHdrRuntimeResult(requestedHdrMode, resolveWithModeContract(graph))
        }
    }

    @Test
    fun `sdr log raw and unavailable raw remain outside the hdr preview path`() {
        val cases = listOf(
            Triple(
                "SDR",
                resolveGraph(cinemaMode = CinemaVideoMode.PRO, requestedHdrMode = "OFF"),
                true
            ),
            Triple(
                "LOG",
                resolveGraph(
                    cinemaMode = CinemaVideoMode.LOG,
                    logEnabled = true,
                    requestedHdrMode = "OFF"
                ),
                true
            ),
            Triple(
                "RAW",
                resolveGraph(
                    cinemaMode = CinemaVideoMode.RAW,
                    rawVideoActive = true,
                    requestedHdrMode = "OFF"
                ),
                true
            ),
            Triple(
                "RAW_UNSUPPORTED",
                resolveGraph(
                    cinemaMode = CinemaVideoMode.RAW,
                    rawVideoActive = true,
                    requestedHdrMode = "OFF",
                    rawSupported = false
                ),
                false
            )
        )

        cases.forEach { (label, graph, rawSupported) ->
            val parityPlan = resolveParityPlan(graph, rawSupported = rawSupported)
            assertFalse("$label must not activate HDR through the parity plan", resolveWithParity(graph, parityPlan))
            assertFalse("$label must not activate HDR through the mode contract", resolveWithModeContract(graph))
        }
    }

    @Test
    fun `ineligible preview and simulated ten bit fallback fail closed`() {
        val graph = resolveGraph(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "NDR10+"
        )
        val parityPlan = resolveParityPlan(graph)

        assertFalse(
            resolveWithParity(
                graph = graph,
                parityPlan = parityPlan,
                eligibleVideoPreview = false
            )
        )
        assertFalse(resolveWithModeContract(graph, eligibleVideoPreview = false))
        assertFalse(
            resolveWithParity(
                graph = graph,
                parityPlan = parityPlan,
                simulatedTenBitFallback = true
            )
        )
        assertFalse(resolveWithModeContract(graph, simulatedTenBitFallback = true))
    }

    @Test
    fun `rejected hdr and strict ten bit requests fail closed`() {
        val rejectedHdr = resolveGraph(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "NDR10+",
            tenBitRequested = true,
            gpu10BitSupported = false
        )
        val rejectedSdr = resolveGraph(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "OFF",
            tenBitRequested = true,
            gpu10BitSupported = false
        )

        listOf(rejectedHdr, rejectedSdr).forEach { graph ->
            val parityPlan = resolveParityPlan(graph)
            assertEquals(
                VideoPipelineAdmissionState.REJECTED,
                graph.deliveryState.pipelineContract.admissionState
            )
            assertEquals(resolveWithParity(graph, parityPlan), resolveWithModeContract(graph))
            assertFalse(resolveWithModeContract(graph))
        }
    }

    @Test
    fun `codec and av1 capability matrix preserves request-bound equivalence`() {
        val hdrModes = listOf("HLG", "HDR10", "NDR10+", "NIVUKX VISION")
        val codecs = VideoCodecType.entries
        val av1Capabilities = listOf(false, true)

        hdrModes.forEach { hdrMode ->
            val graph = resolveGraph(
                cinemaMode = CinemaVideoMode.PRO,
                requestedHdrMode = hdrMode
            )
            val parityPlan = resolveParityPlan(graph)

            codecs.forEach { codec ->
                av1Capabilities.forEach { av1Supported ->
                    val fromParity = resolveWithParity(
                        graph = graph,
                        parityPlan = parityPlan,
                        requestedCodec = codec,
                        av1Supported = av1Supported
                    )
                    val fromModeContract = resolveWithModeContract(
                        graph = graph,
                        requestedCodec = codec,
                        av1Supported = av1Supported
                    )
                    val label = "$hdrMode/$codec/av1Supported=$av1Supported"

                    assertEquals("Overloads diverged for $label", fromParity, fromModeContract)
                    assertHdrRuntimeResult(label, fromModeContract)
                }
            }
        }
    }

    @Test
    fun `simulated fallback is false across every requested codec`() {
        val graph = resolveGraph(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "NIVUKX VISION"
        )
        val parityPlan = resolveParityPlan(graph)

        VideoCodecType.entries.forEach { codec ->
            assertFalse(
                resolveWithParity(
                    graph = graph,
                    parityPlan = parityPlan,
                    requestedCodec = codec,
                    simulatedTenBitFallback = true
                )
            )
            assertFalse(
                resolveWithModeContract(
                    graph = graph,
                    requestedCodec = codec,
                    simulatedTenBitFallback = true
                )
            )
        }
    }

    private fun assertHdrRuntimeResult(label: String, actual: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertTrue("$label must activate HDR on API 29+", actual)
        } else {
            // Local JVM tests use the mockable Android stub (SDK_INT=0). Production deliberately
            // fails closed when the runtime cannot prove Android Q+ HDR transfer support.
            assertFalse("$label must fail closed on a pre-Q or host-stub runtime", actual)
        }
    }

    private fun resolveParityPlan(
        graph: VideoPipelineContract,
        rawSupported: Boolean = true
    ): RecordingPipelineParityPlan = RecordingPipelineParityPolicy.resolve(
        graphContract = graph,
        rawSupported = rawSupported,
        vulkanRuntimeSupported = true
    )

    private fun resolveWithParity(
        graph: VideoPipelineContract,
        parityPlan: RecordingPipelineParityPlan,
        requestedCodec: VideoCodecType = VideoCodecType.HEVC,
        av1Supported: Boolean = true,
        eligibleVideoPreview: Boolean = true,
        simulatedTenBitFallback: Boolean = false
    ): Boolean = SafeVideoHdrPreviewPolicy.isActive(
        eligibleVideoPreview = eligibleVideoPreview,
        requestedCodec = requestedCodec,
        av1Supported = av1Supported,
        parityPlan = parityPlan,
        effectiveHdrMode = graph.effectiveHdrMode,
        simulatedTenBitFallback = simulatedTenBitFallback
    )

    private fun resolveWithModeContract(
        graph: VideoPipelineContract,
        requestedCodec: VideoCodecType = VideoCodecType.HEVC,
        av1Supported: Boolean = true,
        eligibleVideoPreview: Boolean = true,
        simulatedTenBitFallback: Boolean = false
    ): Boolean =
        SafeVideoHdrPreviewPolicy.isActive(
            eligibleVideoPreview = eligibleVideoPreview,
            requestedCodec = requestedCodec,
            av1Supported = av1Supported,
            pipelineContract = graph.deliveryState.pipelineContract,
            effectiveHdrMode = graph.effectiveHdrMode,
            simulatedTenBitFallback = simulatedTenBitFallback
        )

    private fun resolveGraph(
        cinemaMode: CinemaVideoMode,
        rawVideoActive: Boolean = false,
        logEnabled: Boolean = false,
        requestedHdrMode: String,
        tenBitRequested: Boolean = true,
        gpu10BitSupported: Boolean = true,
        rawSupported: Boolean = true
    ): VideoPipelineContract = VideoPipelineGraphController.resolve(
        VideoPipelineGraphRequest(
            appMode = AppMode.VIDEO,
            cinemaMode = cinemaMode,
            rawVideoActive = rawVideoActive,
            logEnabled = logEnabled,
            requestedHdrMode = requestedHdrMode,
            preferredColorSpace = if (requestedHdrMode == "OFF") "Rec.709" else "Rec.2020",
            tenBitRequested = tenBitRequested,
            previewWidth = 1920,
            previewHeight = 1080,
            encoderWidth = 3840,
            encoderHeight = 2160,
            fps = 30,
            recordingState = GpuRecordingState.Idle,
            videoDetailProfile = VideoDetailProfile.NATURAL_DETAIL,
            gpu10BitSupported = gpu10BitSupported,
            hardwareCapabilities = VideoModeHardwareCapabilities(
                rawSupported = rawSupported,
                manualSensorSupported = rawSupported,
                tenBitSupported = gpu10BitSupported,
                tenBitEncoderSupported = gpu10BitSupported
            ),
            physicalCameraGeometryIdentity = "test-camera",
            sourceWidth = 4096,
            sourceHeight = 3072
        )
    )
}
