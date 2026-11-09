package com.neoproc.neoproccamx.rendering

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrPreviewColorContractTest {
    private val hdrCompositor = File(
        "src/main/java/com/neoproc/neoproccamx/rendering/Hdr10PqPreviewCompositor.kt"
    ).readText()
    private val outputRouter = File(
        "src/main/java/com/neoproc/neoproccamx/rendering/PersistentPreviewOutputRouter.kt"
    ).readText()
    private val nativeBridge = File(
        "src/main/cpp/offscreen_egl_encoder_bridge.cpp"
    ).readText()

    @Test
    fun `persistent output owns a verified native pq final surface`() {
        assertTrue(hdrCompositor.contains("createColorManagedWindowSurface"))
        assertTrue(hdrCompositor.contains("EGL_GL_COLORSPACE_BT2020_PQ_EXT"))
        assertTrue(outputRouter.contains("EGL_GL_COLORSPACE_BT2020_PQ_EXT"))
        assertTrue(outputRouter.contains("createPqWindowSurface"))
        assertTrue(outputRouter.contains("EGL14.EGL_RED_SIZE, 10"))
        assertTrue(outputRouter.contains("isStrictTenBitConfig"))
        assertTrue(outputRouter.contains("float srgbToLinear(float c)"))
        assertTrue(outputRouter.contains("vec3 rec709ToBt2020"))
        assertTrue(outputRouter.contains("float pqEncodeNits"))
    }

    @Test
    fun `ndr10 plus and nivukx retain distinct canonical render identities`() {
        assertTrue(hdrCompositor.contains("NDR-NIVUKX-PY-CAL-v4"))
        assertTrue(hdrCompositor.contains("NDR10_SIGNAL_GAMMA = 0.859937"))
        assertTrue(hdrCompositor.contains("NIVUKX_SIGNAL_GAMMA = 0.800034"))
        assertTrue(hdrCompositor.contains("NDR10_SHADOW_MIDTONE_LIFT = 0.167837"))
        assertTrue(hdrCompositor.contains("NIVUKX_SHADOW_MIDTONE_LIFT = 0.232156"))
        assertTrue(hdrCompositor.contains("NDR10_CHROMA_BOOST = 0.038361"))
        assertTrue(hdrCompositor.contains("NIVUKX_CHROMA_BOOST = 0.091565"))
        assertTrue(hdrCompositor.contains("blackPreservingMidtoneLift"))
        assertTrue(hdrCompositor.contains("profileMidtoneContrast"))
        assertTrue(hdrCompositor.contains("profileChromaCharacter"))
        assertTrue(hdrCompositor.contains("profileLocalDetailStops"))
        assertTrue(hdrCompositor.contains("const float NDR10_TARGET_PEAK_NITS = 5000.0"))
        assertTrue(hdrCompositor.contains("const float NIVUKX_TARGET_PEAK_NITS = 10000.0"))
        assertTrue(hdrCompositor.contains("NDR_SDR_MONITOR_DIFFUSE_LINEAR = 0.60382734"))
        assertTrue(hdrCompositor.contains("NDR-NIVUKX-PY-CAL-v4"))
    }

    @Test
    fun `camera drain queue is excluded once project owned NDR is armed`() {
        assertTrue(hdrCompositor.contains("NdrPreviewInputAdmissionPolicy.renderPath(transport)"))
        assertTrue(hdrCompositor.contains("NdrPreviewInputAdmissionPolicy.isSupported(transport)"))
        assertTrue(hdrCompositor.contains("HLG/PQ and any unknown transport are not proprietary NDR inputs"))
        assertTrue(hdrCompositor.contains("NdrVisiblePreviewSource.HOLD_LAST_VALIDATED"))
        assertTrue(
            hdrCompositor.contains(
                "if (!projectOwnedPresentAllowed && cameraTimestampNs > 0L)"
            )
        )
        assertTrue(
            hdrCompositor.contains(
                "if (visibleSource == NdrVisiblePreviewSource.HOLD_LAST_VALIDATED)"
            )
        )
    }

    @Test
    fun `native pq compositor requires a verified ten bit GLES3 config`() {
        assertTrue(hdrCompositor.contains("EGL_OPENGL_ES3_BIT_KHR = 0x0040"))
        assertTrue(
            hdrCompositor.contains(
                "EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR"
            )
        )
        assertTrue(hdrCompositor.contains("EGL14.EGL_CONTEXT_CLIENT_VERSION, 3"))
        assertTrue(hdrCompositor.contains("!isStrictTenBitConfig(selectedConfig)"))
        assertTrue(hdrCompositor.contains("channelSize(EGL14.EGL_RED_SIZE) >= 10"))
        assertTrue(hdrCompositor.contains("channelSize(EGL14.EGL_GREEN_SIZE) >= 10"))
        assertTrue(hdrCompositor.contains("channelSize(EGL14.EGL_BLUE_SIZE) >= 10"))
    }

    @Test
    fun `raw and camera textures retain independent transforms and frame driven swaps`() {
        assertTrue(
            hdrCompositor.contains(
                "val sourceMatrix = if (useProjectOwned) projectOwnedTexMatrix else texMatrix"
            )
        )
        assertTrue(hdrCompositor.contains("private val renderGate = LatestFrameRenderGate()"))
        assertTrue(hdrCompositor.contains("renderGate.requestDispatch()"))
        assertTrue(hdrCompositor.contains("renderHandler.post(renderRunnable)"))
        assertTrue(hdrCompositor.contains("requestRender(redrawRetainedFrame = false)"))
        val renderDispatch = hdrCompositor
            .substringAfter("private val renderRunnable = Runnable {")
            .substringBefore("// Dedicated producer queue")
        assertTrue(
            renderDispatch.indexOf("renderGate.onDispatchStarted()") <
                renderDispatch.indexOf("retainedFrameRedrawRequested.getAndSet(false)")
        )
        assertTrue(
            hdrCompositor.contains(
                "if (useProjectOwned && rawTimestampNs <= 0L && !redrawRetainedFrame) return"
            )
        )
    }

    @Test
    fun `project raw preview minifies through isolated fp16 pass before canonical pq render`() {
        assertTrue(nativeBridge.contains("state->deliveryModeLabel == \"PROJECT_PQ_TEXTURE\""))
        assertTrue(nativeBridge.contains("sourceWidth > targetWidth || sourceHeight > targetHeight"))
        assertTrue(nativeBridge.contains("const bool useProjectPreviewDownsample"))
        assertTrue(nativeBridge.contains("const bool decodeIntermediateFp16"))
        assertTrue(nativeBridge.contains("fp16 ? GL_RGBA16F : GL_RGB10_A2"))
        assertTrue(nativeBridge.contains("configureHistoryTexture("))
        assertTrue(nativeBridge.contains("uSourceTexel"))
        assertTrue(nativeBridge.contains("uMinificationScale"))
        assertTrue(nativeBridge.contains("uniform int uPreserveExtendedRange"))
        assertTrue(
            nativeBridge.contains(
                "uPreserveExtendedRange != 0 ? max(sampled, vec3(0.0)) : " +
                    "clamp(sampled, 0.0, 1.0)"
            )
        )
        assertTrue(
            nativeBridge.contains(
                "glUniform1i(activeDecodePreserveExtendedRangeLocation, " +
                    "inputIsSensorLinear ? 1 : 0)"
            )
        )
        assertTrue(nativeBridge.contains("neoSampleCamera"))
        assertTrue(nativeBridge.contains("PASS 1 — decode once"))
        assertTrue(nativeBridge.contains("PASS 2 — full grade/denoise/sharpen/NDR shader"))
    }
}
