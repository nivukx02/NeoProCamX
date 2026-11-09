package com.neoproc.neoproccamx.rendering

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.neoproc.neoproccamx.recording.NdrWarmSkinRedTameFlag
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.neoproc.neoproccamx.core.HotPathScheduling
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  HDR10 PQ PREVIEW COMPOSITOR  (WYSIWYG live NDR10+/NIVUKX look)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * An isolated `SurfaceTexture → GLES3 → SurfaceView` stage that sits in front of
 * the VISIBLE preview only, gated by [Hdr10PqPreviewPolicy]. It samples the
 * camera's HLG10 external texture and re-renders it through the SAME canonical
 * NDR10+/NIVUKX engine the record path bakes into the file (the GLSL-ES port of
 * com.neoproc.neoproccamx.color.NdrNivukxDeliveryEngine), presenting genuine
 * nit-accurate PQ so the monitor image matches the recording (WYSIWYG).
 *
 * Isolation / safety (mirrors [PreviewGlCompositor]):
 *  • Own EGL display/context (unshared) on its own thread — never touches the
 *    encoder / Vulkan record GL state.
 *  • Two output-transfer modes, chosen by [Hdr10PqPreviewSdrOutputFlag]:
 *      – SDR-output (default): a plain 8-bit sRGB window surface (the config proven
 *        to bind on this device by [PreviewGlCompositor]). The shader PQ-decodes the
 *        camera frame, runs the NDR10+/NIVUKX hue-preserving operator, then outputs
 *        display-referred SDR (BT.2020→BT.709 + sRGB OETF). This is the path that
 *        actually binds on vivo and fixes the direct-preview red/oversaturation.
 *      – PQ-output: a 10-bit RGBA1010102 window surface tagged
 *        EGL_GL_COLORSPACE_BT2020_PQ (genuine PQ WYSIWYG on devices that support it;
 *        rejected on vivo — the reason SDR-output mode exists).
 *  • Frame-driven (one draw per camera frame) — self-paces to the camera cadence.
 *  • Never binds the encoder surface, so it cannot perturb the 25fps record path.
 *
 * DEVICE-GATED: [start] returns false on any EGL/GL failure and the caller MUST fall
 * back to the direct camera preview. Flip the compositor on/off via
 * [Hdr10PqPreviewPolicy]; flip the output transfer via [Hdr10PqPreviewSdrOutputFlag].
 */
internal class Hdr10PqPreviewCompositor {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile private var running = false
    @Volatile private var inputSurface: Surface? = null
    @Volatile private var modeCode = MODE_NDR10_PLUS

    private var boundOutput: Surface? = null
    private var boundWidth = 0
    private var boundHeight = 0

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null
    private var program = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTexMatrixLoc = 0
    private var uTextureLoc = 0
    private var uModeLoc = 0
    private var uInputTransferLoc = 0
    private var uInputPrimariesLoc = 0
    private var uInputRangeLoc = 0
    private var uSdrOutputLoc = 0
    private var uWarmSkinRedTameLoc = 0
    // Snapshot of Hdr10PqPreviewSdrOutputFlag for this EGL session (read at start(), consistent for the
    // life of the surface — a mid-session flip forces a release+rebind via the flag's onChanged).
    private var sdrOutput = false
    private var oesTextureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val texMatrix = FloatArray(16)
    private var frameAvailable = false
    @Volatile private var appliedInputProfile: Long? = null
    private var inputTransferCode = NdrInputTransfer.SDR.shaderCode
    private var inputPrimariesCode = NdrInputPrimaries.BT709.ordinal
    private var inputRangeCode = NdrInputRange.UNKNOWN.shaderCode
    private var explicitYuvDecodeActive = false
    private var lastInputTransportSignature = ""

    private val quadVertices: FloatBuffer = floatBuffer(
        -1f, -1f, 0f, 0f,
        1f, -1f, 1f, 0f,
        -1f, 1f, 0f, 1f,
        1f, 1f, 1f, 1f
    )

    /**
     * (Re)start rendering [output] at [width]×[height] for [mode] (0 = NDR10+, 1 = NIVUKX).
     * Returns true if running; false on any EGL/GL failure (caller falls back to direct HLG10).
     */
    fun start(
        output: Surface,
        width: Int,
        height: Int,
        mode: Int,
        appliedProfile: Long? = null
    ): Boolean {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        appliedInputProfile = appliedProfile
        // Snapshot the output-transfer mode for this session (SDR-display 8-bit vs 10-bit BT.2020-PQ).
        sdrOutput = Hdr10PqPreviewSdrOutputFlag.enabled
        if (running && boundOutput === output && boundWidth == w && boundHeight == h) {
            setMode(mode)
            return true
        }
        release()
        modeCode = mode
        // Run the visible NDR/HDR preview GL render thread at URGENT_DISPLAY (real Process nice) so the
        // viewfinder is never preempted by background analysis/AI. Scheduling only — no pixel change.
        val t = HandlerThread("Hdr10PqPreviewGL", HotPathScheduling.urgentDisplayPriority()).also { it.start() }
        val hnd = Handler(t.looper)
        thread = t
        handler = hnd
        val latch = CountDownLatch(1)
        var ok = false
        hnd.post {
            ok = runCatching { initGl(output, w, h) }
                .onFailure { Log.w(TAG, "Hdr10PqPreviewCompositor init failed: ${it.message}") }
                .getOrDefault(false)
            latch.countDown()
        }
        val completed = runCatching { latch.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
        if (!completed || !ok) {
            Log.w(TAG, "Hdr10PqPreviewCompositor start aborted (completed=$completed ok=$ok)")
            release()
            return false
        }
        running = true
        boundOutput = output
        boundWidth = w
        boundHeight = h
        Log.i(TAG, "Hdr10PqPreviewCompositor started ${w}x${h} mode=$mode")
        return true
    }

    /** The surface the camera should target for preview. Valid only while running. */
    fun inputSurface(): Surface? = inputSurface.takeIf { running }

    fun isRunning(): Boolean = running

    /** Swap the delivery mode (NDR10+ ↔ NIVUKX) with no EGL rebuild. */
    fun setMode(mode: Int) {
        modeCode = mode
        handler?.post { requestRender() }
    }

    /** Updates the Camera2 profile fallback used only when a buffer reports DATASPACE_UNKNOWN. */
    fun setInputProfile(appliedProfile: Long?) {
        appliedInputProfile = appliedProfile
    }

    fun release() {
        val hnd = handler
        val t = thread
        running = false
        boundOutput = null
        boundWidth = 0
        boundHeight = 0
        if (hnd != null) {
            val latch = CountDownLatch(1)
            hnd.post {
                runCatching { destroyGl() }
                latch.countDown()
            }
            runCatching { latch.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        } else {
            runCatching { inputSurface?.release() }
            inputSurface = null
        }
        runCatching { t?.quitSafely() }
        thread = null
        handler = null
    }

    // ── Render-thread only ────────────────────────────────────────────────────

    private fun initGl(output: Surface, width: Int, height: Int): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay === EGL14.EGL_NO_DISPLAY) return false
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false

        // SDR-output mode (default, this device): plain 8-bit RGBA8888 config — the exact config proven
        // to bind on this device by PreviewGlCompositor. PQ mode: 10-bit RGBA1010102 (10/10/10/2) real HDR
        // bit depth (fails to bind on vivo — the reason SDR-output mode exists).
        val configAttribs = if (sdrOutput) {
            intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )
        } else {
            intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 10,
                EGL14.EGL_GREEN_SIZE, 10,
                EGL14.EGL_BLUE_SIZE, 10,
                EGL14.EGL_ALPHA_SIZE, 2,
                EGL14.EGL_NONE
            )
        }
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
            numConfigs[0] <= 0 || configs[0] == null
        ) {
            Log.w(TAG, "No EGL config available (sdrOutput=$sdrOutput)")
            return false
        }
        eglConfig = configs[0]

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0
        )
        if (eglContext === EGL14.EGL_NO_CONTEXT) return false

        // SDR-output mode: plain window surface (no BT.2020-PQ colorspace tag) — binds on this device.
        // PQ mode: tag the window surface BT.2020-PQ so the system compositor renders it as PQ HDR
        // (requires EGL_EXT_gl_colorspace_bt2020_pq — rejected on vivo, hence SDR-output mode).
        val surfaceAttribs = if (sdrOutput) {
            intArrayOf(EGL14.EGL_NONE)
        } else {
            intArrayOf(
                EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_PQ_EXT,
                EGL14.EGL_NONE
            )
        }
        eglSurface = runCatching {
            EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, output, surfaceAttribs, 0)
        }.getOrDefault(EGL14.EGL_NO_SURFACE)
        if (eglSurface === EGL14.EGL_NO_SURFACE) {
            Log.w(TAG, "Preview window surface unavailable (sdrOutput=$sdrOutput)")
            return false
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return false

        program = buildProgram() ?: return false
        aPositionLoc = GLES30.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES30.glGetAttribLocation(program, "aTexCoord")
        uTexMatrixLoc = GLES30.glGetUniformLocation(program, "uTexMatrix")
        uTextureLoc = GLES30.glGetUniformLocation(program, "uTexture")
        uModeLoc = GLES30.glGetUniformLocation(program, "uMode")
        uInputTransferLoc = GLES30.glGetUniformLocation(program, "uInputTransfer")
        uInputPrimariesLoc = GLES30.glGetUniformLocation(program, "uInputPrimaries")
        uInputRangeLoc = GLES30.glGetUniformLocation(program, "uInputRange")
        uSdrOutputLoc = GLES30.glGetUniformLocation(program, "uSdrOutput")
        uWarmSkinRedTameLoc = GLES30.glGetUniformLocation(program, "uWarmSkinRedTame")

        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        oesTextureId = textures[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(oesTextureId)
        st.setDefaultBufferSize(width, height)
        st.setOnFrameAvailableListener({
            frameAvailable = true
            requestRender()
        }, handler)
        surfaceTexture = st
        inputSurface = Surface(st)
        return true
    }

    private fun requestRender() {
        handler?.post { drawFrame() }
    }

    private fun drawFrame() {
        val st = surfaceTexture ?: return
        if (eglSurface === EGL14.EGL_NO_SURFACE) return
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return
        if (frameAvailable) {
            runCatching {
                st.updateTexImage()
                st.getTransformMatrix(texMatrix)
                val dataSpace = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    runCatching { st.dataSpace }.getOrDefault(NdrInputTransportPolicy.DATASPACE_UNKNOWN)
                } else {
                    NdrInputTransportPolicy.DATASPACE_UNKNOWN
                }
                val transport = NdrInputTransportPolicy.resolve(
                    dataSpace = dataSpace,
                    appliedProfile = appliedInputProfile,
                    preferPqWhenUnknown = false
                )
                inputTransferCode = transport.transfer.shaderCode
                inputPrimariesCode = transport.primaries.ordinal
                inputRangeCode = transport.range.shaderCode
                val signature =
                    "$dataSpace|${transport.transfer}|${transport.primaries}|${transport.range}|" +
                        "${transport.source}|explicitYuv=$explicitYuvDecodeActive"
                if (signature != lastInputTransportSignature) {
                    lastInputTransportSignature = signature
                    val logMessage =
                        "NDR preview input dataspace=$dataSpace transfer=${transport.transfer} " +
                            "primaries=${transport.primaries} range=${transport.range} " +
                            "source=${transport.source} explicitYuv=$explicitYuvDecodeActive " +
                            "strictPqBt2020=${transport.isStrictNdrTransport}"
                    if (transport.isStrictNdrTransport) Log.i(TAG, logMessage) else Log.e(TAG, logMessage)
                }
            }.onFailure { return }
            frameAvailable = false
        }

        val w = IntArray(1)
        val h = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, w, 0)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, h, 0)
        GLES30.glViewport(0, 0, w[0].coerceAtLeast(1), h[0].coerceAtLeast(1))

        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)

        quadVertices.position(0)
        GLES30.glVertexAttribPointer(aPositionLoc, 2, GLES30.GL_FLOAT, false, STRIDE, quadVertices)
        GLES30.glEnableVertexAttribArray(aPositionLoc)
        quadVertices.position(2)
        GLES30.glVertexAttribPointer(aTexCoordLoc, 2, GLES30.GL_FLOAT, false, STRIDE, quadVertices)
        GLES30.glEnableVertexAttribArray(aTexCoordLoc)

        GLES30.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glUniform1i(uTextureLoc, 0)
        GLES30.glUniform1i(uModeLoc, modeCode)
        // Per-buffer truth: 0=SDR, 1=PQ, 2=HLG; primaries 1=BT.709, 2=BT.2020.
        // This replaces the old process-global PQ switch that caused red cast and exposure errors.
        GLES30.glUniform1i(uInputTransferLoc, inputTransferCode)
        GLES30.glUniform1i(uInputPrimariesLoc, inputPrimariesCode)
        GLES30.glUniform1i(uInputRangeLoc, inputRangeCode)
        // Output transfer: 1 => display-referred SDR (sRGB, 8-bit surface — the path that binds on this
        // device); 0 => PQ (ST.2084, 10-bit BT.2020-PQ surface). See Hdr10PqPreviewSdrOutputFlag.
        GLES30.glUniform1i(uSdrOutputLoc, if (sdrOutput) 1 else 0)
        // Skin-gated warm-red taming strength (host-verified). 0 => byte-identical to the pre-fix look.
        GLES30.glUniform1f(uWarmSkinRedTameLoc, NdrWarmSkinRedTameFlag.effectiveStrength.toFloat())

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(aPositionLoc)
        GLES30.glDisableVertexAttribArray(aTexCoordLoc)

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun destroyGl() {
        runCatching { inputSurface?.release() }
        inputSurface = null
        surfaceTexture?.let { st ->
            runCatching { st.setOnFrameAvailableListener(null) }
            runCatching { st.release() }
        }
        surfaceTexture = null
        if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (program != 0) {
                runCatching { GLES30.glDeleteProgram(program) }
                program = 0
            }
            if (oesTextureId != 0) {
                runCatching { GLES30.glDeleteTextures(1, intArrayOf(oesTextureId), 0) }
                oesTextureId = 0
            }
            if (eglSurface !== EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext !== EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
        eglConfig = null
        frameAvailable = false
        explicitYuvDecodeActive = false
        lastInputTransportSignature = ""
    }

    private fun buildProgram(): Int? {
        val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
        val explicitYuvSupported = extensions
            .split(' ')
            .any { it == "GL_EXT_YUV_target" }
        if (explicitYuvSupported) {
            linkProgram(EXPLICIT_YUV_FRAGMENT_SHADER)?.let { explicitProgram ->
                explicitYuvDecodeActive = true
                Log.i(TAG, "NDR preview explicit BT.2020 YUV decode active (GL_EXT_YUV_target)")
                return explicitProgram
            }
            Log.w(TAG, "GL_EXT_YUV_target advertised but explicit NDR YUV shader was unavailable")
        }
        explicitYuvDecodeActive = false
        return linkProgram(FRAGMENT_SHADER)
    }

    private fun linkProgram(fragmentShader: String): Int? {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER) ?: return null
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentShader) ?: run {
            GLES30.glDeleteShader(vs)
            return null
        }
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, linkStatus, 0)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        if (linkStatus[0] != GLES30.GL_TRUE) {
            Log.w(TAG, "Program link failed: ${GLES30.glGetProgramInfoLog(prog)}")
            GLES30.glDeleteProgram(prog)
            return null
        }
        return prog
    }

    private fun compileShader(type: Int, source: String): Int? {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES30.GL_TRUE) {
            Log.w(TAG, "Shader compile failed: ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            return null
        }
        return shader
    }

    private fun floatBuffer(vararg values: Float): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }

    companion object {
        private const val TAG = "Hdr10PqPreviewCompositor"
        private const val INIT_TIMEOUT_MS = 2_000L
        private const val STRIDE = 4 * 4

        const val MODE_NDR10_PLUS = 0
        const val MODE_NIVUKX_VISION = 1

        // EGL colorspace constants (not exposed on EGL14): EGL_KHR_gl_colorspace +
        // EGL_EXT_gl_colorspace_bt2020_pq.
        private const val EGL_GL_COLORSPACE_KHR = 0x309D
        private const val EGL_GL_COLORSPACE_BT2020_PQ_EXT = 0x3340

        private const val VERTEX_SHADER = """#version 300 es
            in vec4 aPosition;
            in vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            out vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        // GLSL-ES port of com.neoproc.neoproccamx.color.NdrNivukxDeliveryEngine — the
        // SAME transfer the record shader bakes (neo_p010_export_enhance.comp): the camera
        // HLG10 signal is linearized (inverse OETF, diffuse-white anchored), run through the
        // hue-preserving tone + 203-nit / master-peak shoulder, and PQ-encoded for the
        // BT.2020-PQ 10-bit surface. mode 0 = NDR10+, 1 = NIVUKX Vision.
        private const val FRAGMENT_SHADER = """#version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            in vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            uniform int uMode;
            // Actual buffer transport, resolved from SurfaceTexture.getDataSpace() for every frame.
            // 0=SDR/BT.709 transfer, 1=PQ/ST.2084, 2=HLG. Primaries: 1=BT.709, 2=BT.2020.
            uniform int uInputTransfer;
            uniform int uInputPrimaries;
            // Android dataspace range: 0 unknown, 1 full, 2 limited, 3 extended.
            uniform int uInputRange;
            // 1 => output display-referred SDR (sRGB) for an 8-bit window surface (binds on this device);
            // 0 => output PQ (ST.2084) for a 10-bit BT.2020-PQ surface. See Hdr10PqPreviewSdrOutputFlag.
            uniform int uSdrOutput;
            uniform float uWarmSkinRedTame;
            out vec4 fragColor;

            const vec3 NEO_LUMA = vec3(0.2627, 0.6780, 0.0593);
            const float NEO_HLG_DIFFUSE_SCENE = 0.264967;
            const float NEO_PQ_DIFFUSE_NITS = 203.0;

            float hlgInvOetf(float e) {
                e = clamp(e, 0.0, 1.0);
                return e <= 0.5 ? (e * e / 3.0)
                    : ((exp((e - 0.55991073) / 0.17883277) + 0.28466892) / 12.0);
            }
            float rec709InvOetf(float e) {
                e = clamp(e, 0.0, 1.0);
                return e < 0.081 ? e / 4.5 : pow((e + 0.099) / 1.099, 1.0 / 0.45);
            }
            // SMPTE ST.2084 (PQ) EOTF: signal -> display-linear normalized [0,1] (1.0 == 10000 nits).
            float pqEotf(float e) {
                e = clamp(e, 0.0, 1.0);
                float ep = pow(e, 1.0 / 78.84375);
                float num = max(ep - 0.8359375, 0.0);
                float den = 18.8515625 - 18.6875 * ep;
                return pow(num / den, 1.0 / 0.1593017578125);
            }
            float ss(float e0, float e1, float x) {
                if (e0 == e1) return x >= e1 ? 1.0 : 0.0;
                float t = clamp((x - e0) / (e1 - e0), 0.0, 1.0);
                return t * t * (3.0 - 2.0 * t);
            }
            float signalShape(float x, float g, float tw, float ta) {
                if (x <= 0.0) return 0.0;
                if (x <= 1.0) return pow(x, g) + ss(0.0, tw, x) * ta * (1.0 - x);
                return 1.0 + (g - ta) * (x - 1.0);
            }
            float signalToNits(float s, float peak) {
                s = max(s, 0.0);
                float raw = s * 203.0;
                if (raw <= 203.0) return raw;
                float r = peak - 203.0;
                return min(203.0 + r * (1.0 - exp(-(raw - 203.0) / r)), peak);
            }
            float pqFromNits(float nits) {
                float y = clamp(nits / 10000.0, 0.0, 1.0);
                float ym = pow(y, 0.1593017578125);
                return pow((0.8359375 + 18.8515625 * ym) / (1.0 + 18.6875 * ym), 78.84375);
            }
            // Default external-texture path: the driver performs YUV -> R'G'B'. Devices advertising
            // GL_EXT_YUV_target replace this function with the explicit project-owned decode below.
            vec3 sampleCameraInput(vec2 uv) {
                return texture(uTexture, uv).rgb;
            }
            void main() {
                vec3 e = clamp(sampleCameraInput(vTexCoord), 0.0, 1.0);
                // Linearize with the transfer the camera ACTUALLY sent, then normalize to engine-linear
                // (1.0 == diffuse white). A known BT.709 buffer is converted to the BT.2020 working
                // primaries before the project-owned NDR operator; this prevents a stale PQ/2020
                // assumption from turning STANDARD Camera2 YUV red and overexposed.
                vec3 lin;
                if (uInputTransfer == 1) {
                    lin = vec3(pqEotf(e.r), pqEotf(e.g), pqEotf(e.b)) *
                        (10000.0 / NEO_PQ_DIFFUSE_NITS);
                } else if (uInputTransfer == 2) {
                    lin = vec3(hlgInvOetf(e.r), hlgInvOetf(e.g), hlgInvOetf(e.b)) /
                        NEO_HLG_DIFFUSE_SCENE;
                } else {
                    lin = vec3(rec709InvOetf(e.r), rec709InvOetf(e.g), rec709InvOetf(e.b));
                }
                if (uInputPrimaries == 1) {
                    const mat3 BT709_TO_2020 = mat3(
                        0.627404, 0.069097, 0.016392,
                        0.329283, 0.919540, 0.088013,
                        0.043313, 0.011362, 0.895595
                    );
                    lin = BT709_TO_2020 * lin;
                }
                lin = max(lin, vec3(0.0));
                float g   = uMode == 1 ? 0.88 : 0.92;
                float tw  = uMode == 1 ? 0.24 : 0.20;
                float ta  = uMode == 1 ? 0.026 : 0.018;
                float peak = uMode == 1 ? 1680.0 : 1420.0;
                float dc  = uMode == 1 ? 0.26 : 0.20;
                vec3 s = max(lin, vec3(0.0));
                float y = max(dot(s, NEO_LUMA), 1e-8);
                float mappedY = signalShape(y, g, tw, ta);
                vec3 t = s * (mappedY / y);
                float desat = clamp(dc * ss(0.55, 1.0, mappedY), 0.0, dc);
                t = mix(t, vec3(mappedY), desat);
                // Ratio-gated red-overflow reconstruction; exact GLSL port of NdrToneOperator.
                // Low-blue orange and extreme-red objects stay protected; corrected pixels retain luma.
                if (uWarmSkinRedTame > 0.0) {
                    float oy = max(dot(max(t, vec3(0.0)), NEO_LUMA), 1e-8);
                    float rgRatio = s.r / max(s.g, 1e-8);
                    float bgRatio = s.b / max(s.g, 1e-8);
                    float moderateRed = ss(1.18, 1.55, rgRatio) * (1.0 - ss(2.80, 4.00, rgRatio));
                    float bluePresent = ss(0.28, 0.55, bgRatio) * (1.0 - ss(1.08, 1.35, bgRatio));
                    float exposureWindow = ss(0.04, 0.16, mappedY) * (1.0 - ss(6.5, 8.0, mappedY));
                    float redOverflow = ss(1.30, 2.10, rgRatio);
                    float w = clamp(
                        uWarmSkinRedTame * moderateRed * bluePresent * exposureWindow *
                            (0.45 + 0.55 * redOverflow),
                        0.0,
                        uWarmSkinRedTame
                    );
                    vec3 corrected = t;
                    corrected.r = mix(corrected.r, min(corrected.r, corrected.g * 1.42), w);
                    corrected.b = mix(corrected.b, max(corrected.b, corrected.g * 0.64), w * 0.68);
                    float correctedY = max(dot(max(corrected, vec3(0.0)), NEO_LUMA), 1e-8);
                    t = corrected * (oy / correctedY);
                }
                if (uSdrOutput == 1) {
                    // Display-referred SDR output for an 8-bit sRGB surface (the path that binds on this
                    // device). t is engine-linear BT.2020 (1.0 == 203-nit diffuse white), already tone-mapped
                    // + hue-preserving. Map to SDR keeping BOTH exposed and highlight data:
                    //  1) luminance-only highlight shoulder (no per-channel curve => no hue rotation);
                    //  2) BT.2020 -> BT.709 (sRGB primaries) via the official ITU-R matrix;
                    //  3) hue-preserving gamut compression toward the neutral axis (tames oversaturated red);
                    //  4) sRGB OETF.
                    vec3 tp = max(t, vec3(0.0));
                    float yl = max(dot(tp, NEO_LUMA), 1e-8);
                    float knee = 0.75;
                    // PQ is absolute: diffuse white is 1.0 in engine-linear space, while this NDR
                    // monitor can legitimately receive values up to peak/203 (about 7x for NDR10+).
                    // The old 0.25-wide shoulder mapped diffuse white to 0.91 and everything above
                    // ~1.5x to almost pure white, which made the live monitor look overexposed even
                    // though the recorded PQ data still contained the highlights. Scale the shoulder
                    // across the actual NDR master range so diffuse white stays near 0.79 and the full
                    // highlight range rolls smoothly toward display white. This is monitor-only;
                    // capture exposure and the encoded 10-bit PQ master are untouched.
                    float hdrSpan = max(peak / NEO_PQ_DIFFUSE_NITS - knee, 0.25);
                    float ysdr = yl <= knee
                        ? yl
                        : knee + (1.0 - knee) *
                            (1.0 - exp(-4.0 * (yl - knee) / hdrSpan));
                    vec3 rgb2020 = tp * (ysdr / yl);
                    // BT.2020 -> BT.709 linear (ITU-R primaries), column-major for GLSL.
                    const mat3 BT2020_TO_709 = mat3(
                        1.660491, -0.124550, -0.018151,
                       -0.587641,  1.132900, -0.100579,
                       -0.072850, -0.008349,  1.118730
                    );
                    vec3 rgb709 = BT2020_TO_709 * rgb2020;
                    // Hue-preserving compression of out-of-BT.709-gamut colour toward its own luma.
                    float y709 = max(dot(rgb709, vec3(0.2126, 0.7152, 0.0722)), 0.0);
                    float lo = min(min(rgb709.r, rgb709.g), rgb709.b);
                    if (lo < 0.0) {
                        float f = y709 / (y709 - lo);
                        rgb709 = mix(vec3(y709), rgb709, clamp(f, 0.0, 1.0));
                    }
                    // Compress upper-gamut overflow radially too. The old path handled negative
                    // channels but hard-clamped values above 1.0, clipping warm BT.2020 colours into
                    // neon red/cyan. Moving toward the pixel's own luma preserves hue and detail.
                    y709 = clamp(dot(rgb709, vec3(0.2126, 0.7152, 0.0722)), 0.0, 1.0);
                    float hi = max(max(rgb709.r, rgb709.g), rgb709.b);
                    if (hi > 1.0) {
                        float f = (1.0 - y709) / max(hi - y709, 1e-8);
                        rgb709 = mix(vec3(y709), rgb709, clamp(f, 0.0, 1.0));
                    }
                    rgb709 = clamp(rgb709, 0.0, 1.0);
                    vec3 srgb = mix(
                        rgb709 * 12.92,
                        1.055 * pow(rgb709, vec3(1.0 / 2.4)) - 0.055,
                        step(vec3(0.0031308), rgb709)
                    );
                    fragColor = vec4(clamp(srgb, 0.0, 1.0), 1.0);
                    return;
                }
                float sl = max(dot(max(t, vec3(0.0)), NEO_LUMA), 1e-8);
                float nits = signalToNits(sl, peak);
                float sc = nits / sl;
                vec3 pq = vec3(pqFromNits(t.r * sc), pqFromNits(t.g * sc), pqFromNits(t.b * sc));
                fragColor = vec4(clamp(pq, 0.0, 1.0), 1.0);
            }
        """

        /**
         * Raw-YUV variant for devices exposing GL_EXT_YUV_target. The camera stream on I2405 reports
         * BT.2020/PQ/LIMITED (0x11C60000). Sampling it as ordinary samplerExternalOES delegates the
         * matrix/range expansion to the vendor driver—the only color transform shared by preview,
         * record, and conversion that remained unproven. This variant samples Y'CbCr directly and
         * applies deterministic BT.2020-NCL/BT.709 math before the existing PQ/NDR operator.
         */
        private val EXPLICIT_YUV_FRAGMENT_SHADER: String = FRAGMENT_SHADER
            .replace(
                "#extension GL_OES_EGL_image_external_essl3 : require",
                "#extension GL_EXT_YUV_target : require"
            )
            .replace(
                "uniform samplerExternalOES uTexture;",
                "uniform __samplerExternal2DY2YEXT uTexture;"
            )
            .replace(
                """vec3 sampleCameraInput(vec2 uv) {
                return texture(uTexture, uv).rgb;
            }""",
                """vec3 sampleCameraInput(vec2 uv) {
                vec3 yuv = texture(uTexture, uv).rgb;
                float y;
                float cb;
                float cr;
                if (uInputRange == 2) {
                    // Exact 10-bit legal-range expansion: Y 64..940, Cb/Cr 64..960.
                    y = (yuv.x - 64.0 / 1023.0) * (1023.0 / 876.0);
                    cb = (yuv.y - 512.0 / 1023.0) * (1023.0 / 896.0);
                    cr = (yuv.z - 512.0 / 1023.0) * (1023.0 / 896.0);
                } else {
                    y = yuv.x;
                    cb = yuv.y - 0.5;
                    cr = yuv.z - 0.5;
                }
                vec3 rgb;
                if (uInputPrimaries == 1) {
                    // BT.709 non-constant-luminance Y'CbCr -> R'G'B'.
                    rgb = vec3(
                        y + 1.5748 * cr,
                        y - 0.187324 * cb - 0.468124 * cr,
                        y + 1.8556 * cb
                    );
                } else {
                    // BT.2020 non-constant-luminance Y'CbCr -> R'G'B' (NDR path).
                    rgb = vec3(
                        y + 1.4746 * cr,
                        y - 0.164553 * cb - 0.571353 * cr,
                        y + 1.8814 * cb
                    );
                }
                return rgb;
            }"""
            )
    }
}
