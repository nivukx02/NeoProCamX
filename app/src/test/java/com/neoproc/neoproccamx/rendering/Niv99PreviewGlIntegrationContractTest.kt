package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Niv99PreviewGlIntegrationContractTest {
    private val appRoot = sequenceOf(File("."), File("../../app"))
        .first { File(it, "src/main/java/com/neoproc/neoproccamx/rendering/PreviewGlCompositor.kt").isFile }
    private val compositor = File(
        appRoot,
        "src/main/java/com/neoproc/neoproccamx/rendering/PreviewGlCompositor.kt"
    ).readText()
    private val viewModel = File(
        appRoot,
        "src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
    ).readText()
    private val tuning = File(
        appRoot,
        "src/main/java/com/neoproc/neoproccamx/niv99/Niv99PreviewShaderTuning.kt"
    ).readText()

    @Test
    fun `surface timestamp prefers exact tuning and reports either proven source only after swap`() {
        val draw = compositor
            .substringAfter("private fun drawFrame()")
            .substringBefore("private fun beginNiv99LatchedFrame")

        assertTrue(draw.indexOf("st.updateTexImage()") < draw.indexOf("beginNiv99LatchedFrame("))
        assertTrue(draw.indexOf("prepareNiv99TuningForLatchedFrame()") < draw.indexOf("glDrawArrays"))
        assertTrue(draw.indexOf("eglSwapBuffers") < draw.indexOf("reportNiv99PixelsPresentedAfterSwap()"))
        assertTrue(compositor.contains("Niv99PreviewTuningSource.EXACT_FRAME"))
        assertTrue(compositor.contains("Niv99PreviewTuningSource.RECENT_CONTINUITY"))
        assertTrue(compositor.contains("niv99ShaderTuning.updateRecent("))
        assertTrue(tuning.contains("tuning.identity.sensorTimestampNs != sensorTimestampNs"))
        assertTrue(compositor.contains("NIV99_PACKET_ACCEPTED_GL"))
        assertTrue(compositor.contains("NIV99_PIXEL_APPLIED_PREVIEW"))
    }

    @Test
    fun `dormant metadata support is deadline bounded and not wired into the live view model`() {
        val draw = compositor
            .substringAfter("private fun drawFrame()")
            .substringBefore("private fun beginNiv99LatchedFrame")

        assertTrue(compositor.contains("fun exactFrameMetadataWaitNs(): Long"))
        assertTrue(compositor.contains("Niv99PreviewFrameJoinPolicy.MINIMUM_WAIT_NS"))
        assertTrue(compositor.contains("Niv99PreviewFrameJoinPolicy.MAXIMUM_WAIT_NS"))
        assertTrue(compositor.contains("Niv99PreviewMetadataWakePolicy.isExactWake("))
        assertTrue(compositor.contains("Niv99PreviewMetadataWakePolicy.deadlineDelayMs("))
        assertTrue(compositor.contains("niv99AwaitedMetadataTimestampNs"))
        assertTrue(compositor.contains("niv99MetadataWakeQueued.compareAndSet(false, true)"))
        assertTrue(compositor.contains("private val niv99MetadataDeadlineRunnable = Runnable"))
        assertFalse(compositor.contains("NIV99_METADATA_RETRY_INTERVAL_MS"))
        assertTrue(compositor.contains("private val renderRunnable = Runnable"))
        assertTrue(compositor.contains("niv99LookupReportedForLatchedFrame"))
        assertTrue(compositor.contains("onTuningLookupFinalized("))
        assertFalse(viewModel.contains("pipeline.onNiv99PreviewTuningLookupFinalized("))
        assertTrue(draw.contains("frameAvailable && !mustHoldLatchedFrameForNiv99()"))
        assertTrue(compositor.contains("discardUnprovenNiv99Frame()"))
        assertTrue(compositor.contains("Keeps the last proven window buffer visible"))
        assertTrue(compositor.contains("copyCurrentContinuity("))
        assertTrue(compositor.contains("copyRecentTuning("))
        assertTrue(compositor.contains("exact=\$lookup fallback=\$fallbackStatus"))
        assertFalse(draw.contains("val widthArr = IntArray(1)"))
        assertFalse(draw.contains("val heightArr = IntArray(1)"))
    }

    @Test
    fun `unchanged preview state cannot enqueue a duplicate swap`() {
        val setGrade = compositor
            .substringAfter("fun setGrade(grade: Int)")
            .substringBefore("private var program2d")
        val setDebayerPresentation = compositor
            .substringAfter("fun setDebayerPresentEnabled(on: Boolean)")
            .substringBefore("fun beginDebayerPresentationEpoch")
        val setDebayerOrientation = compositor
            .substringAfter("fun setDebayerOrientation(rotationDegrees: Int, mirrored: Boolean)")
            .substringBefore("fun setCreativeLut")

        assertTrue(setGrade.contains("if (!changed) return"))
        assertTrue(setGrade.contains("requestRender()"))
        assertFalse(setGrade.contains("handler?.post { requestRender() }"))
        assertTrue(setDebayerPresentation.contains("debayerPresentationChanged("))
        assertTrue(setDebayerPresentation.indexOf(") return") <
            setDebayerPresentation.indexOf("requestTemporalHistoryReset("))
        assertTrue(setDebayerOrientation.contains("debayerOrientationChanged("))
        assertTrue(setDebayerOrientation.contains("debayerPresentAllowed && debayerActive"))
    }

    @Test
    fun `shader applies full frame peak safe WB and adaptive detail protected denoise`() {
        val shader = compositor
            .substringAfter("private const val FRAGMENT_SHADER = \"\"\"")
            .substringBefore("\"\"\"")

        assertTrue(shader.contains("uniform vec3 uNiv99RgbCorrection;"))
        assertTrue(shader.contains("vec3 niv99ApplyWhiteBalance(vec3 source)"))
        assertTrue(shader.contains("sourcePeak / correctedPeak"))
        assertTrue(shader.contains("vec3 c = niv99ApplyWhiteBalance("))
        assertTrue(shader.contains("uNiv99SpatialDenoise"))
        assertTrue(shader.contains("uNiv99ChromaDenoise"))
        assertTrue(shader.contains("uNiv99SharpeningCeiling"))
        assertTrue(shader.contains("float edgeProtect"))
        assertTrue(shader.contains("float fineTexture"))
        assertTrue(shader.contains("float microResidual"))
        assertTrue(shader.contains("float mesoResidual"))
        assertTrue(shader.contains("float noiseEvidenceFloor"))
        assertTrue(shader.contains("float localMinY"))
        assertTrue(shader.contains("float localMaxY"))
        assertTrue(shader.contains("float reconstructedY = clamp(filteredY + lumaBoost, localMinY, localMaxY)"))
        assertTrue(shader.contains("denoised + vec3(reconstructedY - filteredY)"))
        assertTrue(shader.contains("float reconstructionBound"))
    }

    @Test
    fun `temporal preview uses only fp16 gpu history and fails closed to current pixels`() {
        val shader = compositor
            .substringAfter("private const val FRAGMENT_SHADER = \"\"\"")
            .substringBefore("\"\"\"")
        val draw = compositor
            .substringAfter("private fun drawFrame()")
            .substringBefore("private fun requestTemporalHistoryReset")

        assertTrue(compositor.contains("GL_OES_texture_half_float"))
        assertTrue(compositor.contains("GL_HALF_FLOAT_OES"))
        assertTrue(compositor.contains("TEMPORAL_HISTORY_CAPACITY = 2"))
        assertTrue(compositor.contains("TEMPORAL_TEXTURE_POOL_SIZE = TEMPORAL_HISTORY_CAPACITY + 1"))
        assertTrue(compositor.contains("glFramebufferTexture2D"))
        assertTrue(compositor.contains("glCheckFramebufferStatus"))
        assertTrue(compositor.contains("precision=RGBA16F renderTarget=true cpuReadback=false"))
        assertTrue(compositor.contains("fallback=single_frame"))
        assertFalse(compositor.contains("glReadPixels"))
        assertFalse(compositor.contains("glCopyTexSubImage2D"))
        assertTrue(draw.indexOf("prepareTemporalFusionForFrame") < draw.indexOf("glDrawArrays"))
        assertTrue(draw.indexOf("glDrawArrays") < draw.indexOf("presentTemporalTargetToWindow"))
        assertTrue(draw.indexOf("presentTemporalTargetToWindow") < draw.indexOf("eglSwapBuffers"))
        assertTrue(draw.indexOf("eglSwapBuffers") < draw.indexOf("reportTemporalFusionAfterSwap"))

        assertTrue(shader.contains("uniform sampler2D uHistory0;"))
        assertTrue(shader.contains("uniform sampler2D uHistory1;"))
        assertTrue(shader.contains("vec3 fuseTemporalHistory(vec3 currentDisplay)"))
        assertTrue(shader.contains("float temporalResidualWeight"))
        assertTrue(shader.contains("float temporalMedian3"))
        assertTrue(shader.contains("float temporalTukeyWeight"))
        assertTrue(shader.contains("const float OUTLIER_SIGMA = 2.55"))
        assertTrue(shader.contains("float temporalMad = 1.4826 * temporalMedian3"))
        assertTrue(shader.contains("float robustScale = max(temporalMad, noiseScaleFloor)"))
        assertTrue(shader.contains("weight0 *= temporalTukeyWeight(history0Deviation, robustScale)"))
        assertTrue(shader.contains("temporalTukeyWeight(history1Deviation, robustScale)"))
        assertTrue(shader.contains("float exposureGate"))
        assertTrue(shader.contains("float coherenceGate"))
        assertTrue(shader.contains("uGrade == 3 && uTemporalHistoryCount > 0.5"))
        assertTrue(shader.contains("uniform float uOutputDitherEnabled;"))
        assertTrue(shader.indexOf("outc = fuseTemporalHistory(outc);") <
            shader.indexOf("float neutralDither"))
        assertTrue(compositor.contains("NIV99_TEMPORAL_STAGE_PRESENTED"))
        assertTrue(compositor.contains("perPixelEffectMeasured=false"))
    }

    @Test
    fun `camera view model leaves live NIV99 preview processing detached`() {
        assertTrue(viewModel.contains("previewGlCompositor.setNiv99FrameProcessor(null)"))
        assertFalse(viewModel.contains("setNiv99FrameProcessor(object : Niv99PreviewFrameProcessor"))
        assertFalse(viewModel.contains("pipeline.copyNiv99TuningForPreviewFrame"))
        assertFalse(viewModel.contains("pipeline.copyRecentNiv99TuningForPreviewFrame"))
        assertFalse(viewModel.contains("pipeline.onNiv99PreviewFramePresented"))
        assertFalse(viewModel.contains("private fun publishNiv99CaptureMetadata("))
    }
}
