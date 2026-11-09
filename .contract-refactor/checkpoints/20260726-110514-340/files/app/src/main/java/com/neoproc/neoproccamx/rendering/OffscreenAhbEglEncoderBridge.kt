package com.neoproc.neoproccamx.rendering

import android.hardware.HardwareBuffer
import android.util.Log
import android.view.Surface
import com.neoproc.neoproccamx.recording.NdrWarmSkinRedTameFlag
import java.io.Closeable

enum class OffscreenEglDeliveryMode(internal val nativeToken: String) {
    SDR("SDR"),
    HLG("HLG"),
    PQ("PQ")
}

class OffscreenAhbEglEncoderBridge : Closeable {
    companion object {
        private const val TAG = "OffscreenAhbEgl"

        init {
            System.loadLibrary("neoproc_vulkan")
        }

        /** Row-major 3x3 identity — the no-op record grade (bit-identical output). */
        val IDENTITY9: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    }

    private var nativeHandle: Long = nativeCreate()

    fun setEncoderSurface(
        surface: Surface?,
        width: Int,
        height: Int,
        preferredDeliveryMode: OffscreenEglDeliveryMode = OffscreenEglDeliveryMode.SDR,
        outputMirrorPlan: RecordOutputMirrorPlan = RecordOutputMirrorFlag.currentManualPlan()
    ): Boolean {
        val handle = nativeHandle
        if (handle == 0L) return false
        val ok = nativeSetEncoderSurface(
            handle = handle,
            surface = surface,
            width = width.coerceAtLeast(0),
            height = height.coerceAtLeast(0),
            preferredDeliveryMode = preferredDeliveryMode.nativeToken
        )
        if (ok) {
            // Device-gated cache toggle: push at surface setup so
            // a flag flip takes effect on the next recording (matches the other pipeline/request flags).
            nativeSetEglImageCacheEnabled(handle, EglImageCacheFlag.enabled)
            // Device-gated decode-once toggle: push at surface setup too.
            nativeSetDecodeOnceEnabled(handle, EglDecodeOnceFlag.enabled)
            // MIRRORFIX v5: push the record-output orientation flip at surface setup so a flag change
            // takes effect on the next recording (matches the decode-once / EGL-cache flip semantics).
            nativeSetEncoderOutputFlip(
                handle = handle,
                flipX = outputMirrorPlan.flipX,
                flipY = outputMirrorPlan.flipY
            )
        }
        return ok
    }

    fun setEncoderOutputFlip(outputMirrorPlan: RecordOutputMirrorPlan): Boolean {
        val handle = nativeHandle
        if (handle == 0L) return false
        nativeSetEncoderOutputFlip(
            handle = handle,
            flipX = outputMirrorPlan.flipX,
            flipY = outputMirrorPlan.flipY
        )
        return true
    }

    fun submitHardwareBuffer(
        hardwareBuffer: HardwareBuffer,
        width: Int,
        height: Int,
        format: Int,
        dataSpace: Int,
        appliedProfile: Long?,
        presentationTimeNs: Long,
        uniforms: GpuImageEnhancementUniforms?
    ): Boolean {
        val handle = nativeHandle
        if (handle == 0L) return false
        // MANDATORY offscreen-record cap (do NOT remove without also weakening the shader).
        // The strict-high-end floors drive uniforms.spatialDenoise/sharpening to ~0.99, which
        // would push the new edge-aware bilateral in the offscreen fragment into waxy/watercolour
        // over-smoothing. Cap them HERE (offscreen submit only) so the checksummed
        // GpuImageEnhancementUniforms.toFloatArray ABI used by the Vulkan/NPV10 path is untouched.
        // Fix C: the HDR/LOG/RAW caps are now ISO/noise-adaptive (NdrRecordEnhancementCapsPolicy) — a
        // noisy (high-ISO/dim) frame gets a higher denoise ceiling AND a lower sharpen ceiling so grain
        // is cleaned instead of re-amplified. Clean scenes keep the previous gentle caps (no downgrade).
        val family = uniforms?.signalFamily
        val caps = NdrRecordEnhancementCapsPolicy.resolve(
            family = family,
            iso = uniforms?.iso ?: 0f,
            noiseMetric = uniforms?.noiseMetric ?: 0f
        )
        val sharpness = (uniforms?.sharpening ?: 0.55f).coerceIn(0f, caps.sharpenCap)
        val denoise = (uniforms?.spatialDenoise ?: 0.45f).coerceIn(0f, caps.denoiseCap)
        val chromaDenoise = (uniforms?.chromaDenoise ?: denoise).coerceIn(0f, 1f)
        // NDR10+/NIVUKX delivery mode for the record tone operator (0 => NDR10+, 1 => NIVUKX Vision).
        val ndrDeliveryMode = if (uniforms?.colorScience == GpuColorScience.NIVUKX_VISION) 1 else 0
        val ndrInputTransport = NdrInputTransportPolicy.resolve(
            dataSpace = dataSpace,
            appliedProfile = appliedProfile,
            preferPqWhenUnknown = false
        )
        val ndrActive =
            uniforms?.colorScience == GpuColorScience.NDR10_PLUS ||
                uniforms?.colorScience == GpuColorScience.NIVUKX_VISION
        if (ndrActive && !ndrInputTransport.isStrictNdrTransport) {
            Log.e(
                TAG,
                "Rejecting NDR frame: input must be BT.2020 PQ; " +
                    "dataspace=$dataSpace transfer=${ndrInputTransport.transfer} " +
                    "primaries=${ndrInputTransport.primaries} range=${ndrInputTransport.range} " +
                    "source=${ndrInputTransport.source} " +
                    "appliedProfile=$appliedProfile"
            )
            return false
        }
        val ndrWarmSkinRedTame = if (
            uniforms?.colorScience == GpuColorScience.NDR10_PLUS ||
            uniforms?.colorScience == GpuColorScience.NIVUKX_VISION
        ) {
            NdrWarmSkinRedTameFlag.effectiveStrength.toFloat()
        } else {
            0f
        }
        // Temporal denoise is DEFAULT-ON for cleaner recordings (the strongest "zero noise" lever):
        // route the higher of the scene-computed base temporalBlend and the AI-Motion field. The
        // shader's per-pixel motion guard (tightened) forces the blend to ~0 on ANY moving pixel, so
        // only genuinely static areas accumulate clean history while moving subjects never ghost or
        // smear (no motion blur). Capped at 0.35 so even the guard's near-static ramp cannot over-blend.
        val temporalBlend = maxOf(
            uniforms?.temporalBlend ?: 0f,
            uniforms?.aiMotionTemporalBlend ?: 0f
        ).coerceIn(0f, 0.35f)
        // RECORD-THEN-DEVELOP LIGHT lane (reversible, default-OFF): strip every per-frame enhancement so
        // the intermediate is a FLAT, high-fidelity capture — the full elite denoise/sharpen/chroma-NR +
        // temporal stack runs OFFLINE in the develop pass instead of on the single-GPU live path. This is
        // what makes the locked full-sensor cadence achievable. The NDR delivery mode (transport) is left
        // as-is so pixels stay coherent with the container tags; only the pixel-enhancement levers zero out.
        val lightMode = RecordDevelopModeFlag.enabled
        val effSharpness = if (lightMode) 0f else sharpness
        val effDenoise = if (lightMode) 0f else denoise
        val effChromaDenoise = if (lightMode) 0f else chromaDenoise
        val effTemporalBlend = if (lightMode) 0f else temporalBlend
        // A record-develop NDR master already arrives from Camera2 as PQ on HDR10/HDR10_PLUS capture.
        // Keep those source PQ codes flat on the now-PQ encoder surface; the offline NDR conversion
        // applies the project tone operator exactly once. This preserves highlight data and prevents a
        // live tone-map followed by a second offline tone-map. Non-PQ/normal recording is unchanged.
        val preserveInputPqMaster =
            lightMode &&
                ndrInputTransport.transfer == NdrInputTransfer.PQ &&
                ndrActive
        return nativeSubmitHardwareBuffer(
            handle = handle,
            hardwareBuffer = hardwareBuffer,
            width = width,
            height = height,
            format = format,
            presentationTimeNs = presentationTimeNs,
            sharpness = effSharpness,
            denoise = effDenoise,
            chromaDenoise = effChromaDenoise,
            temporalBlend = effTemporalBlend,
            ndrDeliveryMode = ndrDeliveryMode,
            ndrWarmSkinRedTame = ndrWarmSkinRedTame,
            preserveInputPqMaster = preserveInputPqMaster,
            inputTransfer = ndrInputTransport.transfer.shaderCode,
            inputPrimaries = ndrInputTransport.primaries.ordinal,
            inputRange = ndrInputTransport.range.shaderCode
        )
    }

    fun setRecordGrade(matrix: FloatArray, contrast: Float) {
        val handle = nativeHandle
        if (handle == 0L) return
        // LIGHT lane: force a no-op grade (identity matrix + unit contrast) so the intermediate stays flat
        // and the creative grade is deferred to the offline develop. Reversible / default-OFF.
        if (RecordDevelopModeFlag.enabled) {
            nativeSetRecordGrade(handle = handle, matrix = IDENTITY9, contrast = 1f)
            return
        }
        nativeSetRecordGrade(
            handle = handle,
            matrix = if (matrix.size >= 9) matrix else IDENTITY9,
            contrast = contrast.coerceIn(0.1f, 4f)
        )
    }

    /**
     * Upload the creative 3D LUT for the record-grade stage (out-of-band, on change only).
     * [data] is packed RGB floats of length size^3 * 3; null/size<=1 disables the LUT.
     */
    fun setCreativeLut(data: FloatArray?, size: Int, strength: Float) {
        val handle = nativeHandle
        if (handle == 0L) return
        // LIGHT lane: no creative LUT baked into the intermediate — the film-print/NRLI look is applied
        // offline by the develop pass. Reversible / default-OFF.
        if (RecordDevelopModeFlag.enabled) {
            nativeSetCreativeLut(handle, null, 0, 0f)
            return
        }
        nativeSetCreativeLut(handle, data, size, strength.coerceIn(0f, 1f))
    }

    fun summary(): String {
        val handle = nativeHandle
        return if (handle == 0L) {
            "offscreenAhbEglEncoder=released"
        } else {
            nativeGetSummary(handle)
        }
    }

    override fun close() {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeDestroy(handle)
        nativeHandle = 0L
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeSetEncoderSurface(
        handle: Long,
        surface: Surface?,
        width: Int,
        height: Int,
        preferredDeliveryMode: String
    ): Boolean
    private external fun nativeSubmitHardwareBuffer(
        handle: Long,
        hardwareBuffer: HardwareBuffer,
        width: Int,
        height: Int,
        format: Int,
        presentationTimeNs: Long,
        sharpness: Float,
        denoise: Float,
        chromaDenoise: Float,
        temporalBlend: Float,
        ndrDeliveryMode: Int,
        ndrWarmSkinRedTame: Float,
        preserveInputPqMaster: Boolean,
        inputTransfer: Int,
        inputPrimaries: Int,
        inputRange: Int
    ): Boolean
    private external fun nativeSetRecordGrade(
        handle: Long,
        matrix: FloatArray,
        contrast: Float
    )
    private external fun nativeSetCreativeLut(
        handle: Long,
        data: FloatArray?,
        size: Int,
        strength: Float
    )
    private external fun nativeSetEglImageCacheEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetDecodeOnceEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetEncoderOutputFlip(handle: Long, flipX: Boolean, flipY: Boolean)
    private external fun nativeGetSummary(handle: Long): String
}
