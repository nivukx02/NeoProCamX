package com.neoproc.neoproccamx.rendering

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
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
 *  PREVIEW GL COMPOSITOR  (additive per-mode preview look)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * An isolated `SurfaceTexture → GLES2 → SurfaceView` stage that sits in front of
 * the VISIBLE preview only. The camera renders into [inputSurface] (a SurfaceTexture
 * at the stream size); this compositor samples that external texture, applies the
 * per-mode monitor grade ([GlVisiblePreviewPolicy.PreviewGrade]), and draws to the
 * SurfaceView's output surface at the SAME stream size — so the existing
 * View-level framing/rotation/overlay pipeline (which fits a stream-sized buffer
 * into the view) is byte-for-byte unchanged; only the pixels are shaped.
 *
 * Isolation guarantees (see [GlVisiblePreviewPolicy]):
 *  • Own EGL display/context, shared with EGL_NO_CONTEXT — no state shared with the
 *    encoder/Vulkan record path.
 *  • 8-bit RGBA window surface — never a 10-bit / HDR / MediaCodec surface, so none
 *    of the vivo 10-bit-EGL / Vulkan-encoder-ANR hazards apply.
 *  • Runs on its own thread; frame-driven (one draw per camera frame), so it self-
 *    paces to the camera cadence and cannot spin.
 *
 * All public methods are safe to call from the main thread. GL work is confined to
 * the internal render thread.
 */
internal class PreviewGlCompositor {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile
    private var running = false

    @Volatile
    private var inputSurface: Surface? = null

    @Volatile
    private var gradeCode = GRADE_IDENTITY

    // Identity of the current output surface + size, so a re-engage with the same
    // target only swaps the grade instead of rebuilding EGL.
    private var boundOutput: Surface? = null
    private var boundWidth = 0
    private var boundHeight = 0

    // ── GL/EGL state — touched ONLY on the render thread ──────────────────────
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null
    private var program = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTexMatrixLoc = 0
    private var uTextureLoc = 0
    private var uGradeLoc = 0
    private var uTexelSizeLoc = 0
    private var oesTextureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val texMatrix = FloatArray(16)
    private var frameAvailable = false

    private val quadVertices: FloatBuffer = floatBuffer(
        // x, y            s, t
        -1f, -1f, 0f, 0f,
        1f, -1f, 1f, 0f,
        -1f, 1f, 0f, 1f,
        1f, 1f, 1f, 1f
    )

    /**
     * (Re)start the compositor rendering [output] at [width]×[height] with [grade].
     * Returns true if it is running (ready to feed [inputSurface] to the camera).
     * If GL init fails on this device, returns false and the caller must fall back
     * to the direct camera→SurfaceView path.
     */
    fun start(output: Surface, width: Int, height: Int, grade: Int): Boolean {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        if (running && boundOutput === output && boundWidth == w && boundHeight == h) {
            setGrade(grade)
            return true
        }
        // Any change of output/size gets a clean rebuild for correctness.
        release()
        gradeCode = grade
        // Run the visible-preview GL render thread at URGENT_DISPLAY (real Process nice) so the
        // viewfinder is never preempted by background analysis/AI. Scheduling only — no pixel change.
        val t = HandlerThread("PreviewGL", HotPathScheduling.urgentDisplayPriority()).also { it.start() }
        val hnd = Handler(t.looper)
        thread = t
        handler = hnd
        val latch = CountDownLatch(1)
        var ok = false
        hnd.post {
            ok = runCatching { initGl(output, w, h) }
                .onFailure { Log.w(TAG, "PreviewGlCompositor init failed: ${it.message}") }
                .getOrDefault(false)
            latch.countDown()
        }
        val completed = runCatching { latch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
        if (!completed || !ok) {
            Log.w(TAG, "PreviewGlCompositor start aborted (completed=$completed ok=$ok)")
            release()
            return false
        }
        running = true
        boundOutput = output
        boundWidth = w
        boundHeight = h
        Log.i(TAG, "PreviewGlCompositor started ${w}x${h} grade=$grade")
        return true
    }

    /** The surface the camera should target for preview. Valid only while [running]. */
    fun inputSurface(): Surface? = inputSurface.takeIf { running }

    fun isRunning(): Boolean = running

    /** Change the per-mode grade with no EGL rebuild. */
    fun setGrade(grade: Int) {
        gradeCode = grade
        handler?.post { requestRender() }
    }

    // ── STAGE 2: CPU-readback debayer present ─────────────────────────────────
    // A separate plain GL_TEXTURE_2D + passthrough program: the native RAW cinema-debayer produces a
    // display-ready sRGB RGBA8 monitor (ACEScg-tonemapped on the CPU-readback path), which we upload and
    // draw directly. Used INSTEAD of the camera external-OES path while a debayer frame is pending, so
    // the visible preview is the genuine sensor debayer (not the ISP-processed camera image).
    private var program2d = 0
    private var aPos2dLoc = 0
    private var aTex2dLoc = 0
    private var uTex2dLoc = 0
    private var uOrient2dLoc = 0
    private var tex2dId = 0
    // Column-major 2x2 orientation matrix for the RAW debayer present (identity = today's V-flip only).
    // Written on the main thread (setDebayerOrientation), read on the render thread — published atomically
    // by swapping the whole array reference so the render thread never sees a torn matrix.
    @Volatile private var debayerOrient: FloatArray = floatArrayOf(1f, 0f, 0f, 1f)
    @Volatile private var debayerActive = false
    @Volatile private var pendingDebayerBytes: ByteBuffer? = null
    @Volatile private var pendingDebayerW = 0
    @Volatile private var pendingDebayerH = 0
    // Master arm gate for the RAW debayer-monitor present. Co-located with the RAW callback lifecycle
    // (installRawVideoCallbacks arms; restoreRawVideoCallbacks disarms) so the 2D debayer path can ONLY
    // paint while the app is actually in RAW. Prevents a stale debayer frame from leaking its
    // (differently-oriented) image into another mode's preview after leaving RAW. Default false.
    @Volatile private var debayerPresentAllowed = false

    // ── Creative-LUT (WYSIWYG) stage ──────────────────────────────────────────
    // The SAME display-referred cube the record 3D-LUT stage grades with, R-fastest packed into a
    // 2D texture (width=N (R/X), height=N*N (G + B*N)) so it can be trilinear-sampled under GLES2
    // (no sampler3D). Applied ONLY in the SDR_ENHANCE grade. Gate: PreviewColorParityFlag (default OFF).
    private var lutTexId = 0
    private var lutCurrentSize = 0
    private var uLutLoc = 0
    private var uLutSizeLoc = 0
    private var uLutEnabledLoc = 0
    // data + size held in ONE reference so the render thread's read is atomic (no torn data/size pair).
    private class PendingLut(val data: FloatArray, val size: Int)
    @Volatile private var pendingLut: PendingLut? = null
    @Volatile private var lutDirty = false
    @Volatile private var lutUploaded = false

    /**
     * Arm/disarm the RAW debayer-monitor present. Must be armed on RAW entry and disarmed on RAW exit
     * (co-located with the RAW callback lifecycle). Disarming also clears any pending frame + repaints so
     * the compositor returns to the camera-OES path immediately (no leaked debayer frame into other modes).
     */
    fun setDebayerPresentEnabled(on: Boolean) {
        debayerPresentAllowed = on
        if (!on) {
            debayerActive = false
            pendingDebayerBytes = null
            handler?.post { requestRender() }
        }
    }

    /** Present a CPU-readback debayered RGBA8 monitor frame (already display-ready sRGB). */
    fun presentDebayerRgba8(buffer: ByteBuffer, w: Int, h: Int) {
        if (!running || !debayerPresentAllowed || w <= 0 || h <= 0) return
        pendingDebayerBytes = buffer
        pendingDebayerW = w
        pendingDebayerH = h
        debayerActive = true
        handler?.post { drawFrame() }
    }

    /** Stop presenting the debayer buffer and return to the direct camera path. */
    fun clearDebayerPresent() {
        debayerActive = false
        debayerPresentAllowed = false
        pendingDebayerBytes = null
        handler?.post { requestRender() }
    }

    /**
     * Set the RAW debayer-monitor display orientation (rotation + front-camera mirror), derived from the
     * app's geometry seams. (0, false) => identity => byte-identical to today's fixed V-flip. The 2x2
     * matrix is published by whole-array swap so the render thread never reads a torn value.
     */
    fun setDebayerOrientation(rotationDegrees: Int, mirrored: Boolean) {
        val r = ((rotationDegrees % 360) + 360) % 360
        val c: Float
        val s: Float
        when (r) {
            90 -> { c = 0f; s = 1f }
            180 -> { c = -1f; s = 0f }
            270 -> { c = 0f; s = -1f }
            else -> { c = 1f; s = 0f }
        }
        val mx = if (mirrored) -1f else 1f
        // Rotation R=[[c,-s],[s,c]] composed with mirror-on-x M=diag(mx,1); column-major R*M:
        //   col0 = R*(mx,0) = (c*mx, s*mx);  col1 = R*(0,1) = (-s, c).
        debayerOrient = floatArrayOf(c * mx, s * mx, -s, c)
        handler?.post { requestRender() }
    }

    /**
     * Upload (or clear) the display-referred creative preview cube (R,G,B interleaved, R-fastest —
     * the exact packing of ColorScience.getPreviewLutFloatArray and the record 3D-LUT stage). null or
     * size<=1 clears it (SDR_ENHANCE becomes byte-identical). Safe to call from any thread.
     */
    fun setCreativeLut(data: FloatArray?, size: Int) {
        pendingLut = if (data == null || size <= 1 || data.size < size * size * size * 3) {
            null
        } else {
            PendingLut(data, size)
        }
        lutDirty = true
        handler?.post { requestRender() }
    }

    /** Tear down the render thread, EGL, and the SurfaceTexture. Safe to call repeatedly. */
    fun release() {
        val hnd = handler
        val t = thread
        running = false
        // NOTE: do NOT null inputSurface here. destroyGl() (render-thread) is what releases the native
        // Surface; nulling the field first turned that release into a no-op and leaked a Surface per
        // engage/disengage cycle. destroyGl() nulls the field after releasing it.
        boundOutput = null
        boundWidth = 0
        boundHeight = 0
        if (hnd != null) {
            val latch = CountDownLatch(1)
            hnd.post {
                runCatching { destroyGl() }
                latch.countDown()
            }
            runCatching { latch.await(RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        } else {
            // No render thread ever started (or already gone): release the surface directly.
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

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
            numConfigs[0] <= 0 || configs[0] == null
        ) {
            return false
        }
        eglConfig = configs[0]

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        // Unshared context (EGL_NO_CONTEXT) — full isolation from the record/encoder GL.
        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0
        )
        if (eglContext === EGL14.EGL_NO_CONTEXT) return false

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, output, surfaceAttribs, 0)
        if (eglSurface === EGL14.EGL_NO_SURFACE) return false

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return false

        program = buildProgram() ?: return false
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")
        uGradeLoc = GLES20.glGetUniformLocation(program, "uGrade")
        uTexelSizeLoc = GLES20.glGetUniformLocation(program, "uTexelSize")
        // Creative-LUT (WYSIWYG) stage uniforms + its 2D-packed cube texture.
        uLutLoc = GLES20.glGetUniformLocation(program, "uLut")
        uLutSizeLoc = GLES20.glGetUniformLocation(program, "uLutSize")
        uLutEnabledLoc = GLES20.glGetUniformLocation(program, "uLutEnabled")
        val lutTex = IntArray(1)
        GLES20.glGenTextures(1, lutTex, 0)
        lutTexId = lutTex[0]
        // Keep unit-1's LUT texture COMPLETE from the start (mirrors the record path's default 1x1
        // sampler) so the always-bound uLut sampler is never an incomplete sampler on the default-OFF path.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val lutDefault = ByteBuffer.allocateDirect(3).order(ByteOrder.nativeOrder())
        lutDefault.put(0).put(0).put(0)
        lutDefault.position(0)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, 1, 1, 0,
            GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, lutDefault
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        oesTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )

        // STAGE 2 debayer present: a plain 2D program + texture for the CPU-readback RGBA8 monitor.
        // Best-effort — if it fails to build, the debayer present is simply unavailable (camera path unaffected).
        program2d = build2dProgram() ?: 0
        if (program2d != 0) {
            aPos2dLoc = GLES20.glGetAttribLocation(program2d, "aPosition")
            aTex2dLoc = GLES20.glGetAttribLocation(program2d, "aTexCoord")
            uTex2dLoc = GLES20.glGetUniformLocation(program2d, "uTexture")
            uOrient2dLoc = GLES20.glGetUniformLocation(program2d, "uOrient2d")
            val tex2 = IntArray(1)
            GLES20.glGenTextures(1, tex2, 0)
            tex2dId = tex2[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex2dId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

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
        val hnd = handler ?: return
        hnd.post { drawFrame() }
    }

    private fun drawFrame() {
        val st = surfaceTexture ?: return
        if (eglSurface === EGL14.EGL_NO_SURFACE) return
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return
        // STAGE 2: while a debayer frame is pending, present the genuine sensor debayer (2D RGBA8) and
        // bypass the camera external-OES path entirely.
        if (debayerActive && debayerPresentAllowed && program2d != 0 && tex2dId != 0 && pendingDebayerBytes != null) {
            drawDebayer2d()
            return
        }
        if (frameAvailable) {
            runCatching {
                st.updateTexImage()
                st.getTransformMatrix(texMatrix)
            }.onFailure { return }
            frameAvailable = false
        }

        val widthArr = IntArray(1)
        val heightArr = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, widthArr, 0)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, heightArr, 0)
        GLES20.glViewport(0, 0, widthArr[0].coerceAtLeast(1), heightArr[0].coerceAtLeast(1))

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        uploadCreativeLutIfDirty()

        quadVertices.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, STRIDE, quadVertices)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        quadVertices.position(2)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, STRIDE, quadVertices)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)

        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(uTextureLoc, 0)
        GLES20.glUniform1i(uGradeLoc, gradeCode)
        // Source-stream texel size (SurfaceTexture default buffer size == boundWidth/Height) for the
        // SDR_ENHANCE neighbour taps. Harmless for the other grades (their fragment path ignores it).
        GLES20.glUniform2f(
            uTexelSizeLoc,
            1f / boundWidth.coerceAtLeast(1),
            1f / boundHeight.coerceAtLeast(1)
        )
        // Creative-LUT (WYSIWYG): uLut ALWAYS references texture unit 1 (a complete texture), so it can
        // never alias the external-OES uTexture on unit 0 — GLES2 §2.10.4 forbids two different-typed
        // samplers referencing one unit (undefined even when uLut is dynamically unsampled). uLutEnabled
        // gates the ACTUAL sampling; default 0 keeps the enhance-only path byte-identical to today.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(uLutLoc, 1)
        GLES20.glUniform1f(uLutSizeLoc, lutCurrentSize.coerceAtLeast(1).toFloat())
        val lutOn = lutUploaded && gradeCode == GRADE_SDR_ENHANCE
        GLES20.glUniform1i(uLutEnabledLoc, if (lutOn) 1 else 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    // Render-thread only. Rasters the R-fastest cube (no repack) into a 2D texture:
    //   width = N (R/X), height = N*N (row y = gi + bi*N) — the identical packing the record path's
    //   GL_TEXTURE_3D consumes. Float->8-bit here; preview output is 8-bit, so this is true WYSIWYG.
    private fun uploadCreativeLutIfDirty() {
        if (!lutDirty) return
        lutDirty = false
        val up = pendingLut
        if (up == null || lutTexId == 0) {
            lutUploaded = false
            return
        }
        val data = up.data
        val n = up.size
        val texels = n * n * n
        val buf = ByteBuffer.allocateDirect(texels * 3).order(ByteOrder.nativeOrder())
        var i = 0
        while (i < texels) {
            val o = i * 3
            buf.put(((data[o].coerceIn(0f, 1f)) * 255f + 0.5f).toInt().toByte())
            buf.put(((data[o + 1].coerceIn(0f, 1f)) * 255f + 0.5f).toInt().toByte())
            buf.put(((data[o + 2].coerceIn(0f, 1f)) * 255f + 0.5f).toInt().toByte())
            i++
        }
        buf.position(0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexId)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1) // rows are N*3 bytes
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        // width = N (R/X), height = N*N (row y = gi + bi*N). This IS the R-fastest packing raster.
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, n, n * n, 0,
            GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, buf
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        lutCurrentSize = n
        lutUploaded = true
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
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            if (program != 0) {
                runCatching { GLES20.glDeleteProgram(program) }
                program = 0
            }
            if (oesTextureId != 0) {
                runCatching { GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0) }
                oesTextureId = 0
            }
            if (program2d != 0) {
                runCatching { GLES20.glDeleteProgram(program2d) }
                program2d = 0
            }
            if (tex2dId != 0) {
                runCatching { GLES20.glDeleteTextures(1, intArrayOf(tex2dId), 0) }
                tex2dId = 0
            }
            if (lutTexId != 0) {
                runCatching { GLES20.glDeleteTextures(1, intArrayOf(lutTexId), 0) }
                lutTexId = 0
            }
            lutUploaded = false
            lutCurrentSize = 0
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
    }

    // STAGE 2: draw the CPU-readback debayered RGBA8 monitor (display-ready sRGB) via the plain 2D
    // program. Uploads the pending bytes into tex2dId and blits a fullscreen quad.
    private fun drawDebayer2d() {
        val bytes = pendingDebayerBytes ?: return
        val w = pendingDebayerW
        val h = pendingDebayerH
        if (w <= 0 || h <= 0) return
        bytes.position(0)
        val wArr = IntArray(1)
        val hArr = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, wArr, 0)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, hArr, 0)
        GLES20.glViewport(0, 0, wArr[0].coerceAtLeast(1), hArr[0].coerceAtLeast(1))
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program2d)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex2dId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes
        )
        GLES20.glUniform1i(uTex2dLoc, 0)
        // Always upload the orientation matrix (an unset mat2 uniform is all-zero and would collapse the
        // sampling to a single texel). Default is identity => today's V-flip-only present.
        GLES20.glUniformMatrix2fv(uOrient2dLoc, 1, false, debayerOrient, 0)
        quadVertices.position(0)
        GLES20.glVertexAttribPointer(aPos2dLoc, 2, GLES20.GL_FLOAT, false, STRIDE, quadVertices)
        GLES20.glEnableVertexAttribArray(aPos2dLoc)
        quadVertices.position(2)
        GLES20.glVertexAttribPointer(aTex2dLoc, 2, GLES20.GL_FLOAT, false, STRIDE, quadVertices)
        GLES20.glEnableVertexAttribArray(aTex2dLoc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos2dLoc)
        GLES20.glDisableVertexAttribArray(aTex2dLoc)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun build2dProgram(): Int? {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_2D) ?: return null
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_2D) ?: return null
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.w(TAG, "2D program link failed: ${GLES20.glGetProgramInfoLog(prog)}")
            GLES20.glDeleteProgram(prog)
            return null
        }
        return prog
    }

    private fun buildProgram(): Int? {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER) ?: return null
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER) ?: return null
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.w(TAG, "Program link failed: ${GLES20.glGetProgramInfoLog(prog)}")
            GLES20.glDeleteProgram(prog)
            return null
        }
        return prog
    }

    private fun compileShader(type: Int, source: String): Int? {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            Log.w(TAG, "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
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
        private const val TAG = "PreviewGlCompositor"
        // #12: bounded MAIN-thread blocking. start()/release() are reached synchronously from the main thread
        // on a LOG/RAW preview engage, so these caps bound how long a slow cold shader-compile — or a wedged
        // GL thread — can freeze the UI. A typical engage is ~100-400ms (well under START_TIMEOUT_MS); a rare
        // timeout returns false → the direct camera preview stays live and the existing bounded retry
        // re-engages once the driver shader cache is warm. This keeps a LOG↔RAW switch (release()+start())
        // safely under the ANR window instead of the old 2s+2s = 4s worst case.
        private const val START_TIMEOUT_MS = 1_200L
        private const val RELEASE_TIMEOUT_MS = 800L
        private const val STRIDE = 4 * 4 // 4 floats per vertex

        // Grade codes shared with the fragment shader's uGrade uniform.
        const val GRADE_IDENTITY = 0
        const val GRADE_NEOLOG = 1
        const val GRADE_RAW_MONITOR = 2
        const val GRADE_SDR_ENHANCE = 3

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            uniform vec2 uTexelSize;
            varying vec2 vTexCoord;
            varying vec2 vStepX;
            varying vec2 vStepY;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
                // Neighbour texel steps carried through the SAME SurfaceTexture matrix (w=0 =>
                // direction only) so sampling is correct under the stream's flip/crop/scale.
                vStepX = (uTexMatrix * vec4(uTexelSize.x, 0.0, 0.0, 0.0)).xy;
                vStepY = (uTexMatrix * vec4(0.0, uTexelSize.y, 0.0, 0.0)).xy;
            }
        """

        // STAGE 2 debayer present: plain 2D sampler, passthrough (the RGBA8 is already display-ready
        // sRGB). V is flipped because the CPU-readback rows are top-first while GL samples bottom-first.
        private const val VERTEX_SHADER_2D = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat2 uOrient2d;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                // Explicit display rotation/mirror about the texture centre (identity => no change),
                // THEN the fixed V-flip (CPU-readback rows are top-first while GL samples bottom-first).
                vec2 uv = uOrient2d * (aTexCoord.xy - 0.5) + 0.5;
                vTexCoord = vec2(uv.x, 1.0 - uv.y);
            }
        """
        private const val FRAGMENT_SHADER_2D = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        // The NEOLOG grade shows the user's OWN log technology — NeoLogCurve (N-LOG v5 APEX-LOG,
        // core-math/.../core/NeoLogCurve.kt), NOT an invented approximation. The camera feeds a
        // display-referred (~sRGB/Rec.709) 8-bit image, so we FIRST linearize it back to approximate
        // scene-linear, THEN apply the genuine NeoLog transfer (BASE variant: quintic toe below
        // toeCut, natural-log body above). That is the mathematically faithful "this scene through
        // NeoLog" monitor image: 18% gray → 0.50, white rolls off to ~0.74, shadows lifted at the toe.
        // This LIFTS midtones (fixing the earlier crushed/dark look) while giving the authentic flat
        // low-contrast log reading. The prior grade used a made-up pow()/scale curve that capped white
        // at 0.775 and did NOT use NeoLog — that was the "looks dark / not my log" defect.
        //   NEOLOG      → linearize → NeoLogCurve.encode(BASE) → mild log desaturation.
        //   RAW_MONITOR → clean, neutral, viewable lift of the flat RAW preview (full saturation).
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            varying vec2 vTexCoord;
            varying vec2 vStepX;
            varying vec2 vStepY;
            uniform samplerExternalOES uTexture;
            uniform int uGrade;
            uniform sampler2D uLut;      // R-fastest cube in a 2D tex: width=N (R), height=N*N (G + B*N)
            uniform float uLutSize;      // N
            uniform int uLutEnabled;     // 1 = apply creative LUT (WYSIWYG), 0 = bypass (default)

            const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

            // Display-referred sRGB -> approximate scene-linear (per channel).
            float srgbToLinear(float c) {
                return (c <= 0.04045) ? (c / 12.92) : pow((c + 0.055) / 1.055, 2.4);
            }

            // SDR_ENHANCE: edge-preserving BILATERAL denoise + halo-controlled unsharp + flat-gated
            // chroma-noise smoothing on the LIVE preview. NO tone/colour shift — clarity only. This now
            // mirrors the offscreen-EGL record enhance (range-weighted bilateral instead of a flat
            // Gaussian) so the live monitor is as clean AND detailed as the recorded file (WYSIWYG),
            // rather than looking softer/noisier than the recording.
            vec3 enhance(vec3 center) {
                vec3 n  = texture2D(uTexture, vTexCoord + vStepY).rgb;
                vec3 s  = texture2D(uTexture, vTexCoord - vStepY).rgb;
                vec3 e  = texture2D(uTexture, vTexCoord + vStepX).rgb;
                vec3 w  = texture2D(uTexture, vTexCoord - vStepX).rgb;
                vec3 ne = texture2D(uTexture, vTexCoord + vStepX + vStepY).rgb;
                vec3 nw = texture2D(uTexture, vTexCoord - vStepX + vStepY).rgb;
                vec3 se = texture2D(uTexture, vTexCoord + vStepX - vStepY).rgb;
                vec3 sw = texture2D(uTexture, vTexCoord - vStepX - vStepY).rgb;
                float lc = dot(center, LUMA);
                // Range sigma matched to the record path's hard clamp (<=0.05): edges keep ~0 range
                // weight so genuine detail (pores/foliage/fabric) is never blended toward the mean.
                float sigmaR = 0.05;
                float invR2 = 1.0 / (2.0 * sigmaR * sigmaR);
                vec3 accB = center * 4.0; float wB = 4.0;   // bilateral: spatial * range
                vec3 accG = center * 4.0; float wG = 4.0;   // spatial-only low-pass: unsharp reference
                float dl, rw;
                dl = dot(n,  LUMA) - lc; rw = exp(-dl * dl * invR2); accB += n  * 2.0 * rw; wB += 2.0 * rw; accG += n  * 2.0; wG += 2.0;
                dl = dot(s,  LUMA) - lc; rw = exp(-dl * dl * invR2); accB += s  * 2.0 * rw; wB += 2.0 * rw; accG += s  * 2.0; wG += 2.0;
                dl = dot(e,  LUMA) - lc; rw = exp(-dl * dl * invR2); accB += e  * 2.0 * rw; wB += 2.0 * rw; accG += e  * 2.0; wG += 2.0;
                dl = dot(w,  LUMA) - lc; rw = exp(-dl * dl * invR2); accB += w  * 2.0 * rw; wB += 2.0 * rw; accG += w  * 2.0; wG += 2.0;
                dl = dot(ne, LUMA) - lc; rw = exp(-dl * dl * invR2); accB += ne * rw;       wB += rw;       accG += ne;       wG += 1.0;
                dl = dot(nw, LUMA) - lc; rw = exp(-dl * dl * invR2); accB += nw * rw;       wB += rw;       accG += nw;       wG += 1.0;
                dl = dot(se, LUMA) - lc; rw = exp(-dl * dl * invR2); accB += se * rw;       wB += rw;       accG += se;       wG += 1.0;
                dl = dot(sw, LUMA) - lc; rw = exp(-dl * dl * invR2); accB += sw * rw;       wB += rw;       accG += sw;       wG += 1.0;
                vec3 bilateral = accB / wB;
                vec3 gauss = accG / wG;
                // Edge-preserving denoise; safe at high strength because edges keep ~0 range weight.
                vec3 denoised = mix(center, bilateral, 0.70);
                // Halo-controlled unsharp on the DENOISED high-freq (real structure, not grain).
                vec3 detail = denoised - gauss;
                float lcd = abs(dot(detail, LUMA));
                float halo = 1.0 - smoothstep(0.06, 0.20, lcd);
                vec3 boost = clamp(detail * 0.60 * halo, -0.06, 0.06);
                vec3 sharpened = denoised + boost;
                // Flat-area-gated chroma-noise smoothing (luma detail untouched, colour richness kept).
                float y  = dot(sharpened, LUMA);
                float gy = dot(gauss, LUMA);
                vec3 sc = sharpened - vec3(y);
                vec3 gc = gauss - vec3(gy);
                float chromaFlat = 1.0 - smoothstep(0.02, 0.10, lcd);
                vec3 outc = vec3(y) + mix(sc, gc, 0.30 * chromaFlat);
                return clamp(outc, 0.0, 1.0);
            }

            // NeoLogCurve N-LOG v5 APEX-LOG, BASE variant — matches core-math NeoLogCurve.encode():
            // quintic C2 toe below toeCut, natural-log body above; 18% scene-linear -> 0.50 code.
            float neoLogEncode(float x) {
                const float toeCut     = 0.010;
                const float toeHeight  = 0.080;
                const float logScale   = 0.137099;
                const float logDensity = 120.0;
                const float xMax       = 6.843034;
                const float c1 = 0.080;
                const float c3 = -0.436788;
                const float c4 = 0.789056;
                const float c5 = -0.352269;
                float xx = clamp(x, 0.0, xMax);
                if (xx < toeCut) {
                    float t  = clamp(xx / toeCut, 0.0, 1.0);
                    float t2 = t * t;
                    float t3 = t2 * t;
                    float t4 = t3 * t;
                    float t5 = t4 * t;
                    return c1 * t + c3 * t3 + c4 * t4 + c5 * t5;
                }
                return toeHeight + logScale * log(1.0 + logDensity * (xx - toeCut));
            }

            // One LUT node (ri,gi,bi are integers-as-float). Matches ColorScience R-fastest packing
            // exactly:  idx = ri + gi*N + bi*N*N  ->  x = ri, y = gi + bi*N.
            vec3 lutFetch(float ri, float gi, float bi, float n) {
                float u = (ri + 0.5) / n;
                float v = (gi + bi * n + 0.5) / (n * n);
                return texture2D(uLut, vec2(u, v)).rgb;
            }
            // Manual trilinear over the 2D-packed cube — reproduces the record path's GL_TEXTURE_3D
            // GL_LINEAR sample on the identical cube (8-bit here; preview output is 8-bit => WYSIWYG).
            vec3 sampleCreativeLut(vec3 rgb, float n) {
                // Match the record path's GL_TEXTURE_3D GL_LINEAR sample exactly: texture(uLut, v) places
                // the node lookup at v*N - 0.5 (texel centres at (k+0.5)/N), edge-clamped. Reproducing
                // that convention (NOT v*(N-1)) is what makes preview == recorded pixels.
                vec3 c  = clamp(clamp(rgb, 0.0, 1.0) * n - 0.5, 0.0, n - 1.0);
                vec3 c0 = floor(c);
                vec3 c1 = min(c0 + 1.0, n - 1.0);
                vec3 f  = c - c0;
                vec3 c000 = lutFetch(c0.r, c0.g, c0.b, n);
                vec3 c100 = lutFetch(c1.r, c0.g, c0.b, n);
                vec3 c010 = lutFetch(c0.r, c1.g, c0.b, n);
                vec3 c110 = lutFetch(c1.r, c1.g, c0.b, n);
                vec3 c001 = lutFetch(c0.r, c0.g, c1.b, n);
                vec3 c101 = lutFetch(c1.r, c0.g, c1.b, n);
                vec3 c011 = lutFetch(c0.r, c1.g, c1.b, n);
                vec3 c111 = lutFetch(c1.r, c1.g, c1.b, n);
                vec3 x00 = mix(c000, c100, f.r);
                vec3 x10 = mix(c010, c110, f.r);
                vec3 x01 = mix(c001, c101, f.r);
                vec3 x11 = mix(c011, c111, f.r);
                return mix(mix(x00, x10, f.g), mix(x01, x11, f.g), f.b);
            }

            // Anti-banding dither: hash value-noise for a triangular-PDF (TPDF) dither before the
            // 8-bit quantize, so baked-LUT gradients (skies/skin) decorrelate into imperceptible
            // noise instead of posterizing on the live monitor.
            float ditherHash(vec2 p) {
                vec3 p3 = fract(vec3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }

            void main() {
                vec3 c = clamp(texture2D(uTexture, vTexCoord).rgb, 0.0, 1.0);
                vec3 outc = c;
                if (uGrade == 3) {
                    // SDR_ENHANCE: clarity only, no tone/colour shift. Kept OUT of the NEOLOG/
                    // RAW_MONITOR branches so those device-verified looks are byte-unchanged.
                    outc = enhance(c);
                    // WYSIWYG: layer the graded creative cube on the enhanced SDR image so preview
                    // matches record. Gated by uLutEnabled (default 0 => byte-identical enhance-only).
                    if (uLutEnabled == 1) {
                        outc = clamp(sampleCreativeLut(outc, uLutSize), 0.0, 1.0);
                    }
                } else if (uGrade == 1) {
                    // NEOLOG: linearize the preview, then apply the user's genuine NeoLog transfer.
                    vec3 lin = vec3(srgbToLinear(c.r), srgbToLinear(c.g), srgbToLinear(c.b));
                    vec3 logc = vec3(neoLogEncode(lin.r), neoLogEncode(lin.g), neoLogEncode(lin.b));
                    float y = dot(logc, LUMA);
                    outc = mix(vec3(y), logc, 0.90);        // -10% saturation (log is mildly desaturated)
                    // LOG_ORIGINAL preview: keep the flat encoded signal visible without a display-referred
                    // monitor lift so preview and recorded LOG tone stay matched.
                } else if (uGrade == 2) {
                    // NEUTRAL RAW MONITOR: a faithful "ungraded RAW" reading of the flat, ISP-stripped
                    // RAW preview — flat/low-contrast, neutral colour (per-channel identical → no cast,
                    // no added saturation or contrast). The device-tuned shadow/mid lift (pow 0.72 × 1.06)
                    // is UNCHANGED below the highlight knee, so preview brightness is byte-identical to
                    // the proven, device-verified lift (no "too dark" regression). Only the top end is
                    // improved: the old hard clip to 1.0 (which crushed near-white RAW detail to flat
                    // white) is replaced by a soft knee that rolls highlights off smoothly, preserving
                    // highlight texture. Preview only — the recorded RAW data is never touched.
                    vec3 gained = pow(c, vec3(0.72)) * 1.06;
                    const float kKnee = 0.90;
                    vec3 excess = max(gained - kKnee, 0.0);
                    vec3 rolled = kKnee + (1.0 - kKnee) * (excess / (excess + (1.0 - kKnee)));
                    outc = clamp(mix(gained, rolled, step(kKnee, gained)), 0.0, 1.0);
                    // Viewable monitor colour: a mild saturation lift around luma so the RAW preview reads
                    // as a developed monitor image (colourful) rather than a flat/neutral gray fallback when
                    // the Vulkan debayer frame is not yet available. Preview only; recorded RAW untouched.
                    float ymon = dot(outc, LUMA);
                    outc = clamp(mix(vec3(ymon), outc, 1.15), 0.0, 1.0);
                }
                // TPDF dither at ~1 LSB of 8-bit, applied to every grade branch so no baked gradient bands.
                vec2 dpx = gl_FragCoord.xy;
                vec3 dither = vec3(
                    ditherHash(dpx)                    - ditherHash(dpx + vec2(41.13, 17.79)),
                    ditherHash(dpx + vec2(19.0,  7.0)) - ditherHash(dpx + vec2( 3.71, 91.20)),
                    ditherHash(dpx + vec2(53.0, 31.0)) - ditherHash(dpx + vec2(67.40, 13.60))
                );
                outc = clamp(clamp(outc, 0.0, 1.0) + dither * (1.0 / 255.0), 0.0, 1.0);
                gl_FragColor = vec4(outc, 1.0);
            }
        """
    }
}
