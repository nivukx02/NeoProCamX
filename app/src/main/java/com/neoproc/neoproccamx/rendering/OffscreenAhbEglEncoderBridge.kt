package com.neoproc.neoproccamx.rendering

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.util.Log
import android.view.Surface
import com.neoproc.neoproccamx.niv99.Niv99CameraIdentityHash
import com.neoproc.neoproccamx.niv99.Niv99LiveFrameTuning
import com.neoproc.neoproccamx.niv99.Niv99WhiteBalanceAuthority
import com.neoproc.neoproccamx.recording.NdrWarmSkinRedTameFlag
import java.io.Closeable

enum class OffscreenEglDeliveryMode(internal val nativeToken: String) {
    SDR("SDR"),
    HLG("HLG"),
    PQ("PQ"),
    /**
     * Project-owned ST.2084 code values carried through an internal SurfaceTexture only.
     *
     * Unlike [PQ], this is deliberately not tagged as a display HDR surface. The downstream
     * compositor validates the frame's project-owned proof and performs the one explicit PQ decode.
     * Keeping an intermediate RGB10_A2 queue untagged prevents a device GLConsumer from applying a
     * hidden PQ presentation transform before that explicit decode. It is never valid for MediaCodec.
     */
    PROJECT_PQ_TEXTURE("PROJECT_PQ_TEXTURE")
}

internal enum class ProjectOwnedInputReadFencePoll(val nativeCode: Int) {
    UNKNOWN(0),
    PENDING(1),
    SIGNALED(2),
    FAILED(3);

    companion object {
        fun fromNative(code: Int): ProjectOwnedInputReadFencePoll =
            entries.firstOrNull { it.nativeCode == code } ?: FAILED
    }
}

/**
 * Semantic carried only by a project-owned Vulkan output whose pixels are already the final
 * BT.2020/ST.2084 encoder codes. Camera2 HDR and the scene-linear/scene-log intermediates must never
 * construct this proof.
 */
internal enum class PregradedBt2020PqSemantic(val nativeCode: Int) {
    PRE_GRADED_BT2020_PQ(1)
}

/**
 * Immutable cross-API proof for the one-draw Vulkan -> EGL -> MediaCodec handoff.
 *
 * [effectiveGradeFingerprint] covers the complete effective grade packet (capped sharpness,
 * spatial/chroma denoise, temporal blend including AI motion, delivery/peak, warm-skin treatment,
 * output flip, profile generation and revision). The EGL bridge does not reinterpret that packet;
 * it accepts only the final semantic and performs a code-value-preserving identity presentation.
 */
internal data class PregradedBt2020PqProof(
    val semantic: PregradedBt2020PqSemantic,
    val abiVersion: Int,
    val profileGeneration: Long,
    val profileRevision: Long,
    val slotIndex: Int,
    val slotToken: Long,
    val effectiveGradeFingerprint: Long,
    val width: Int,
    val height: Int,
    val rgbRange: NdrInputRange
) {
    companion object {
        const val ABI_VERSION = 1
        const val TRANSPORT_SLOT_COUNT = 2
    }

    internal fun isExactFor(width: Int, height: Int): Boolean =
        semantic == PregradedBt2020PqSemantic.PRE_GRADED_BT2020_PQ &&
            abiVersion == ABI_VERSION &&
            profileGeneration > 0L &&
            profileRevision > 0L &&
            slotIndex in 0 until TRANSPORT_SLOT_COUNT &&
            slotToken != 0L &&
            effectiveGradeFingerprint != 0L &&
            this.width > 0 &&
            this.height > 0 &&
            this.width == width &&
            this.height == height &&
            rgbRange == NdrInputRange.FULL
}

/**
 * Owns the native handle across a fail-closed final-close transaction.
 *
 * A rejected native destroy must not make the handle reachable by normal bridge operations: the
 * native state may be partially retired and is safe only for another destroy attempt. At the same
 * time, dropping the handle would abandon the retained EGLSurface/ANativeWindow. This small state
 * machine makes those two requirements explicit and is independent of Android/JNI so its ownership
 * transitions can be covered by deterministic host tests.
 */
internal class OffscreenNativeCloseOwnership(initialHandle: Long) {
    internal enum class Phase {
        OPEN,
        CLOSE_QUARANTINED,
        CLOSED
    }

    // JNI returns an opaque pointer bit-pattern, not a signed numeric address. Valid arm64
    // pointers commonly have bit 63 set and therefore appear as negative Kotlin Long values.
    // Zero is the only null handle; normalizing negative values to zero silently abandons the
    // native owner and makes every subsequent profile/surface operation look "not ready".
    private var ownedHandle: Long = initialHandle

    internal var phase: Phase = if (ownedHandle == 0L) Phase.CLOSED else Phase.OPEN
        private set

    /** Only an OPEN bridge may issue ordinary JNI calls. */
    internal fun operationHandle(): Long =
        if (phase == Phase.OPEN) ownedHandle else 0L

    /** Returns the exact retained owner for a first or retrying close attempt. */
    internal fun beginClose(): Long {
        if (ownedHandle == 0L) {
            phase = Phase.CLOSED
            return 0L
        }
        phase = Phase.CLOSE_QUARANTINED
        return ownedHandle
    }

    /** Commits release only after JNI proves complete native destruction. */
    internal fun completeClose(attemptedHandle: Long, acknowledged: Boolean): Boolean {
        check(attemptedHandle != 0L && attemptedHandle == ownedHandle) {
            "Close acknowledgement does not match the retained native owner"
        }
        if (acknowledged) {
            ownedHandle = 0L
            phase = Phase.CLOSED
        } else {
            phase = Phase.CLOSE_QUARANTINED
        }
        return acknowledged
    }

    internal val isClosed: Boolean
        get() = phase == Phase.CLOSED

    internal val hasQuarantinedOwnership: Boolean
        get() = phase == Phase.CLOSE_QUARANTINED && ownedHandle != 0L
}

class OffscreenAhbEglEncoderBridge : Closeable {
    companion object {
        private const val TAG = "OffscreenAhbEgl"
        private const val NDR10_MASTER_PEAK_NITS = 5_000f
        private const val NIVUKX_MASTER_PEAK_NITS = 10_000f
        private const val NIV99_SPATIAL_BLEND_SCALE = 0.72f
        private const val NIV99_CHROMA_BLEND_SCALE = 0.84f
        private const val NIV99_RECONSTRUCTION_SCALE = 0.68f

        init {
            System.loadLibrary("neoproc_vulkan")
        }

        /** Row-major 3x3 identity — the no-op record grade (bit-identical output). */
        val IDENTITY9: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    }

    private val nativeCloseOwnership = OffscreenNativeCloseOwnership(nativeCreate())
    private val nativeHandle: Long
        get() = nativeCloseOwnership.operationHandle()
    @Volatile private var ndrPeakPlan: NdrPeakCapabilityPolicy.Plan? = null
    private var appliedNdrMeasuredMode = NdrNivukxMeasuredMode.OFF
    private var appliedNdrProfileGeneration = 0L
    private var lastCoherenceRejection = ""
    private var nextProjectOwnedInputReadReceipt = 1L

    fun setNdrPeakPlan(plan: NdrPeakCapabilityPolicy.Plan?) {
        ndrPeakPlan = plan
    }

    /**
     * Invalidates only the temporal source/history validity for this bridge. The EGL surface, cached
     * textures, measured NDR profile, record grade, and creative LUT remain intact, so a presentation
     * epoch can start from a clean first frame without a destructive surface rebind.
     */
    @Synchronized
    internal fun resetTemporalHistory(): Boolean {
        val handle = nativeHandle
        if (handle == 0L) return false
        return nativeResetTemporalHistory(handle)
    }

    /**
     * Pushes the already confidence-gated measured correction out of band. The native bridge caches this
     * packet, so the frame-submit JNI path remains allocation-free and preview/recording use one authority.
     */
    @Synchronized
    internal fun setNdrNivukxMeasuredCorrection(
        correction: NdrNivukxMeasuredCorrection,
        profileGeneration: Long,
        profile: NdrLiveProfile = NdrLiveProfilePolicy.resolve(correction.mode)
    ): Boolean {
        val handle = nativeHandle
        if (handle == 0L) return false
        if (profileGeneration < appliedNdrProfileGeneration) {
            Log.i(
                TAG,
                "NDR_PROFILE_SET_STALE_REJECTED requested=$profileGeneration " +
                    "applied=$appliedNdrProfileGeneration mode=${correction.mode}"
            )
            return false
        }
        nativeSetNdrMeasuredCorrection(
            handle = handle,
            enabled = correction.active,
            redScale = correction.redScale,
            greenScale = correction.greenScale,
            blueScale = correction.blueScale,
            highlightRolloffBias = correction.highlightRolloffBias,
            shadowLiftBias = correction.shadowLiftBias,
            saturationScale = correction.saturationScale,
            skinHueCorrectionDegrees = correction.skinHueCorrectionDegrees,
            skyHueCorrectionDegrees = correction.skyHueCorrectionDegrees,
            sensorDiffuseScene = profile.sensorDiffuseScene,
            signalGamma = profile.signalGamma,
            toeWidth = profile.toeWidth,
            toeAmount = profile.toeAmount,
            highlightDesat = profile.highlightDesat,
            shadowMidtoneLift = profile.shadowMidtoneLift,
            midtoneContrast = profile.midtoneContrast,
            chromaBoost = profile.chromaBoost,
            skyChromaBonus = profile.skyChromaBonus,
            localDetailStrength = profile.localDetailStrength,
            localDetailLowerStops = profile.localDetailLowerStops,
            localDetailUpperStops = profile.localDetailUpperStops
        )
        appliedNdrMeasuredMode = correction.mode
        appliedNdrProfileGeneration = profileGeneration
        lastCoherenceRejection = ""
        return true
    }

    @Synchronized
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
            // Select between two pixel-equivalent cardinal-sampling implementations. This is pushed
            // once per encoder surface so a qualification A/B cannot switch shaders mid-recording.
            nativeSetGatherGridEnabled(handle, EglGatherGridFlag.enabled)
            // Diagnostic-only and default OFF. The internal project-PQ preview bridge is excluded so
            // a full-resolution encoder profile cannot contend with or be confused with 1280x960 data.
            nativeSetGpuStageTimingEnabled(
                handle,
                EglGpuStageTimingFlag.enabled &&
                    preferredDeliveryMode != OffscreenEglDeliveryMode.PROJECT_PQ_TEXTURE
            )
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

    @Synchronized
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

    /**
     * Imports and samples one stable project-owned RAW recorder transport before ImageReader ingress.
     * The native operation warms only EGLImage/decode resources and never timestamps, swaps, or submits
     * an encoder frame. Unsupported cache/decode configurations return false so callers can retain the
     * existing compatible frame path without changing output quality or ownership.
     */
    @Synchronized
    internal fun prewarmProjectOwnedRawTransport(
        hardwareBuffer: HardwareBuffer,
        width: Int,
        height: Int,
        slot: Int
    ): Boolean {
        val handle = nativeHandle
        if (handle == 0L || width <= 0 || height <= 0 || slot !in 0 until 2) return false
        val format = hardwareBuffer.format
        if (format != HardwareBuffer.RGBA_FP16 && format != HardwareBuffer.RGBA_1010102) {
            return false
        }
        return nativePrewarmProjectOwnedRawTransport(
            handle = handle,
            hardwareBuffer = hardwareBuffer,
            width = width,
            height = height,
            format = format,
            slot = slot
        )
    }

    /**
     * Exercises the exact Camera2 P010 -> RGB10 decode and SDR grade path while the encoder gate is
     * closed. Native renders into a private RGB10 scratch texture, finishes the GPU work, and returns
     * the stable AHardwareBuffer identity. It never timestamps or swaps the MediaCodec surface.
     */
    @Synchronized
    internal fun prewarmCameraP010Sdr(
        hardwareBuffer: HardwareBuffer,
        width: Int,
        height: Int,
        format: Int,
        dataSpace: Int,
        appliedProfile: Long?,
        uniforms: GpuImageEnhancementUniforms?,
        sensorTimestampNs: Long
    ): Long {
        val handle = nativeHandle
        if (handle == 0L || width <= 0 || height <= 0 ||
            format != ImageFormat.YCBCR_P010 || hardwareBuffer.format != format
        ) return 0L
        val warmed = submitHardwareBufferInternal(
            hardwareBuffer = hardwareBuffer,
            width = width,
            height = height,
            format = format,
            dataSpace = dataSpace,
            appliedProfile = appliedProfile,
            presentationTimeNs = 0L,
            uniforms = uniforms,
            sensorLinearAcesCg = false,
            sceneLog10AcesCg = false,
            sensorTimestampNs = sensorTimestampNs,
            niv99Tuning = null,
            prewarmOnly = true
        )
        return if (warmed) nativeGetLastCameraP010SdrPrewarmBufferId(handle) else 0L
    }

    /**
     * Presents one transfer-invariant black transport frame to wake the MediaCodec consumer.
     * Native does not sample an AHardwareBuffer, mutate temporal history, or increment clip frames.
     * RecordingManager discards the corresponding encoded access unit before opening the clip.
     */
    @Synchronized
    internal fun submitEncoderReadinessFrame(): Boolean {
        return submitEncoderReadinessFrame(System.nanoTime())
    }

    @Synchronized
    internal fun submitEncoderReadinessFrame(presentationTimeNs: Long): Boolean {
        val handle = nativeHandle
        if (handle == 0L || presentationTimeNs <= 0L) return false
        return nativeSubmitEncoderReadinessFrame(handle, presentationTimeNs)
    }

    fun submitHardwareBuffer(
        hardwareBuffer: HardwareBuffer,
        width: Int,
        height: Int,
        format: Int,
        dataSpace: Int,
        appliedProfile: Long?,
        presentationTimeNs: Long,
        uniforms: GpuImageEnhancementUniforms?,
        sensorTimestampNs: Long = 0L,
        niv99Tuning: Niv99LiveFrameTuning? = null
    ): Boolean = submitHardwareBufferInternal(
        hardwareBuffer = hardwareBuffer,
        width = width,
        height = height,
        format = format,
        dataSpace = dataSpace,
        appliedProfile = appliedProfile,
        presentationTimeNs = presentationTimeNs,
        uniforms = uniforms,
        sensorLinearAcesCg = false,
        sceneLog10AcesCg = false,
        sensorTimestampNs = sensorTimestampNs,
        niv99Tuning = niv99Tuning
    )

    /**
     * Submit the project-owned RAW-derived RGBA16F frame. This is the only valid ingress for an
     * active NDR10+/NIVUKX grade; Camera2 HLG/PQ buffers are intentionally rejected.
     */
    fun submitSensorLinearAcesCg(
        hardwareBuffer: HardwareBuffer,
        width: Int,
        height: Int,
        presentationTimeNs: Long,
        uniforms: GpuImageEnhancementUniforms?
    ): Boolean = submitHardwareBufferInternal(
        hardwareBuffer = hardwareBuffer,
        width = width,
        height = height,
        format = HardwareBuffer.RGBA_FP16,
        dataSpace = NdrInputTransportPolicy.DATASPACE_UNKNOWN,
        appliedProfile = null,
        presentationTimeNs = presentationTimeNs,
        uniforms = uniforms,
        sensorLinearAcesCg = true,
        sceneLog10AcesCg = false
    )

    /**
     * Recorder-only variant. A positive receipt proves that MediaCodec accepted the frame and names
     * the exact EGL fence which retires the imported RAW transport read. The caller must poll that
     * receipt and acknowledge it before allowing Vulkan to overwrite the corresponding AHB lane.
     */
    @Synchronized
    internal fun submitSensorLinearAcesCgWithInputReadFence(
        hardwareBuffer: HardwareBuffer,
        width: Int,
        height: Int,
        presentationTimeNs: Long,
        uniforms: GpuImageEnhancementUniforms?
    ): Long? {
        val receipt = allocateProjectOwnedInputReadReceipt()
        val submitted = submitHardwareBufferInternal(
            hardwareBuffer = hardwareBuffer,
            width = width,
            height = height,
            format = HardwareBuffer.RGBA_FP16,
            dataSpace = NdrInputTransportPolicy.DATASPACE_UNKNOWN,
            appliedProfile = null,
            presentationTimeNs = presentationTimeNs,
            uniforms = uniforms,
            sensorLinearAcesCg = true,
            sceneLog10AcesCg = false,
            inputReadReceipt = receipt
        )
        return receipt.takeIf { submitted }
    }

    /**
     * Submit the full-resolution project-owned packed scene-log fallback. The native shader performs
     * the exact inverse before calibration and NDR tone/color processing; this is not HLG/PQ ingress.
     */
    fun submitSceneLog10AcesCg(
        hardwareBuffer: HardwareBuffer,
        width: Int,
        height: Int,
        presentationTimeNs: Long,
        uniforms: GpuImageEnhancementUniforms?
    ): Boolean = submitHardwareBufferInternal(
        hardwareBuffer = hardwareBuffer,
        width = width,
        height = height,
        format = HardwareBuffer.RGBA_1010102,
        dataSpace = NdrInputTransportPolicy.DATASPACE_UNKNOWN,
        appliedProfile = null,
        presentationTimeNs = presentationTimeNs,
        uniforms = uniforms,
        sensorLinearAcesCg = false,
        sceneLog10AcesCg = true
    )

    /** Recorder-only packed scene-log equivalent of [submitSensorLinearAcesCgWithInputReadFence]. */
    @Synchronized
    internal fun submitSceneLog10AcesCgWithInputReadFence(
        hardwareBuffer: HardwareBuffer,
        width: Int,
        height: Int,
        presentationTimeNs: Long,
        uniforms: GpuImageEnhancementUniforms?
    ): Long? {
        val receipt = allocateProjectOwnedInputReadReceipt()
        val submitted = submitHardwareBufferInternal(
            hardwareBuffer = hardwareBuffer,
            width = width,
            height = height,
            format = HardwareBuffer.RGBA_1010102,
            dataSpace = NdrInputTransportPolicy.DATASPACE_UNKNOWN,
            appliedProfile = null,
            presentationTimeNs = presentationTimeNs,
            uniforms = uniforms,
            sensorLinearAcesCg = false,
            sceneLog10AcesCg = true,
            inputReadReceipt = receipt
        )
        return receipt.takeIf { submitted }
    }

    /**
     * Presents an already-final project-owned BT.2020/ST.2084 RGB10_A2 frame to a proven PQ
     * MediaCodec surface. This is intentionally a separate ABI from [submitHardwareBufferInternal]:
     * contract mismatch returns null and can never fall back to the generic NDR grade shader.
     *
     * The returned receipt names the exact EGL input-read fence. The Vulkan slot named by [proof]
     * must not be overwritten until the caller observes SIGNALED and acknowledges the receipt.
     */
    @Synchronized
    internal fun submitPregradedBt2020PqWithInputReadFence(
        hardwareBuffer: HardwareBuffer,
        width: Int,
        height: Int,
        presentationTimeNs: Long,
        proof: PregradedBt2020PqProof
    ): Long? {
        val handle = nativeHandle
        if (handle == 0L || presentationTimeNs <= 0L || !proof.isExactFor(width, height)) {
            Log.e(
                TAG,
                "Rejecting pregraded PQ frame: invalid immutable semantic/extent/ownership proof"
            )
            return null
        }
        val receipt = allocateProjectOwnedInputReadReceipt()
        val submitted = nativeSubmitPregradedBt2020Pq(
            handle = handle,
            hardwareBuffer = hardwareBuffer,
            width = width,
            height = height,
            presentationTimeNs = presentationTimeNs,
            semantic = proof.semantic.nativeCode,
            abiVersion = proof.abiVersion,
            profileGeneration = proof.profileGeneration,
            profileRevision = proof.profileRevision,
            slotIndex = proof.slotIndex,
            slotToken = proof.slotToken,
            effectiveGradeFingerprint = proof.effectiveGradeFingerprint,
            rgbRange = proof.rgbRange.shaderCode,
            inputReadReceipt = receipt
        )
        return receipt.takeIf { submitted }
    }

    @Synchronized
    internal fun pollProjectOwnedInputReadFence(
        receipt: Long,
        timeoutNs: Long = 0L
    ): ProjectOwnedInputReadFencePoll {
        val handle = nativeHandle
        if (handle == 0L || receipt <= 0L || timeoutNs < 0L) {
            return ProjectOwnedInputReadFencePoll.FAILED
        }
        return ProjectOwnedInputReadFencePoll.fromNative(
            nativePollProjectOwnedInputReadFence(handle, receipt, timeoutNs)
        )
    }

    @Synchronized
    internal fun acknowledgeProjectOwnedInputReadFence(receipt: Long): Boolean {
        val handle = nativeHandle
        return handle != 0L && receipt > 0L &&
            nativeAcknowledgeProjectOwnedInputReadFence(handle, receipt)
    }

    private fun allocateProjectOwnedInputReadReceipt(): Long {
        val receipt = nextProjectOwnedInputReadReceipt.coerceAtLeast(1L)
        nextProjectOwnedInputReadReceipt = if (receipt == Long.MAX_VALUE) 1L else receipt + 1L
        return receipt
    }

    @Synchronized
    private fun submitHardwareBufferInternal(
        hardwareBuffer: HardwareBuffer,
        width: Int,
        height: Int,
        format: Int,
        dataSpace: Int,
        appliedProfile: Long?,
        presentationTimeNs: Long,
        uniforms: GpuImageEnhancementUniforms?,
        sensorLinearAcesCg: Boolean,
        sceneLog10AcesCg: Boolean,
        inputReadReceipt: Long = 0L,
        sensorTimestampNs: Long = 0L,
        niv99Tuning: Niv99LiveFrameTuning? = null,
        prewarmOnly: Boolean = false
    ): Boolean {
        val handle = nativeHandle
        if (handle == 0L) return false
        val pixelApplicationRequested = niv99Tuning?.pixelApplicationApproved == true
        val exactNiv99Tuning = niv99Tuning?.takeIf {
            it.valid &&
                it.pixelApplicationApproved &&
                it.identity.isComplete() &&
                sensorTimestampNs > 0L &&
                it.identity.sensorTimestampNs == sensorTimestampNs
        }
        if (pixelApplicationRequested && exactNiv99Tuning == null) {
            Log.e(TAG, "Rejecting NIV99 encoder frame: exact frame provenance is unavailable")
            return false
        }
        val coherence = NdrRenderProfileCoherencePolicy.evaluate(
            correctionMode = appliedNdrMeasuredMode,
            colorScience = uniforms?.colorScience
        )
        if (!coherence.coherent) {
            val rejection = "generation=$appliedNdrProfileGeneration " +
                "correction=${coherence.correctionMode} " +
                "expected=${coherence.expectedColorScience ?: "NON_NDR"} " +
                "science=${coherence.actualColorScience ?: "NONE"}"
            if (rejection != lastCoherenceRejection) {
                Log.w(
                    TAG,
                    "NDR_PROFILE_FRAME_REJECTED $rejection; retaining previous valid surface frame"
                )
                lastCoherenceRejection = rejection
            }
            return false
        }
        lastCoherenceRejection = ""
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
            noiseMetric = uniforms?.noiseMetric ?: 0f,
            // SceneRenderProfile.lumaNoise is an aggregate scene statistic and is not spatially
            // isolated noise. It remains useful to the full quality pipeline, but must not be
            // allowed to turn scene contrast into extra denoise at this final NDR bridge.
            noiseMetricConfidence = 0f,
            sensorShotNoiseCoefficient = uniforms?.sensorShotNoiseCoefficient ?: 0f,
            sensorReadNoiseCoefficient = uniforms?.sensorReadNoiseCoefficient ?: 0f,
            sensorNoiseModelConfidence = if (uniforms?.sensorNoiseModelPerFrame == true) 1f else 0f
        )
        val sharpness = (uniforms?.sharpening ?: 0.55f).coerceIn(0f, caps.sharpenCap)
        val denoise = (uniforms?.spatialDenoise ?: 0.45f).coerceIn(0f, caps.denoiseCap)
        val chromaDenoise = (uniforms?.chromaDenoise ?: denoise).coerceIn(0f, 1f)
        // NDR10+/NIVUKX delivery mode for the record tone operator (0 => NDR10+, 1 => NIVUKX Vision).
        val ndrDeliveryMode = if (uniforms?.colorScience == GpuColorScience.NIVUKX_VISION) 1 else 0
        val ndrBaselinePeak = if (ndrDeliveryMode == 1) {
            NIVUKX_MASTER_PEAK_NITS
        } else {
            NDR10_MASTER_PEAK_NITS
        }
        // These are fixed mastering contracts, not display-brightness requests. A capability plan
        // may describe a lower physical monitor, but it cannot relabel or alter encoded PQ pixels.
        val ndrTargetPeakNits = ndrPeakPlan
            ?.contentPeakNits
            ?.coerceIn(ndrBaselinePeak, ndrBaselinePeak)
            ?: ndrBaselinePeak
        val ndrInputTransport = NdrInputTransportPolicy.resolve(
            dataSpace = dataSpace,
            appliedProfile = appliedProfile,
            preferPqWhenUnknown = false,
            sensorLinearAcesCg = sensorLinearAcesCg,
            sceneLog10AcesCg = sceneLog10AcesCg
        )
        val ndrActive =
            uniforms?.colorScience == GpuColorScience.NDR10_PLUS ||
                uniforms?.colorScience == GpuColorScience.NIVUKX_VISION
        val projectOwnedRawIngress = sensorLinearAcesCg || sceneLog10AcesCg
        if (ndrActive && !projectOwnedRawIngress) {
            Log.e(TAG, "Rejecting NDR frame: use a RAW-derived project-owned ACEScg ingress")
            return false
        }
        if (projectOwnedRawIngress && !ndrActive) {
            Log.e(TAG, "Rejecting RAW-derived ACEScg frame without an active NDR/NIVUKX grade")
            return false
        }
        if (ndrActive && !ndrInputTransport.isSupportedNdrInput) {
            Log.e(
                TAG,
                "Rejecting NDR frame: input must be RAW-derived ACEScg FP16 or Neo Scene Log10; " +
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
        val niv99Confidence = exactNiv99Tuning?.confidence?.coerceIn(0f, 1f) ?: 0f
        val niv99Spatial = exactNiv99Tuning?.spatialDenoise?.coerceIn(0f, 1f) ?: 0f
        val niv99Chroma = exactNiv99Tuning?.chromaDenoise?.coerceIn(0f, 1f) ?: 0f
        val niv99Temporal = exactNiv99Tuning?.temporalDenoise?.coerceIn(0f, 1f) ?: 0f
        val niv99SharpeningCeiling =
            exactNiv99Tuning?.sharpeningCeiling?.coerceIn(0f, 1f) ?: 0f
        val combinedDenoise = 1f - (1f - denoise) *
            (1f - niv99Spatial * niv99Confidence * NIV99_SPATIAL_BLEND_SCALE)
        val combinedChromaDenoise = 1f - (1f - chromaDenoise) *
            (1f - niv99Chroma * niv99Confidence * NIV99_CHROMA_BLEND_SCALE)
        val niv99DetailFloor = niv99SharpeningCeiling * niv99Confidence *
            NIV99_RECONSTRUCTION_SCALE
        val combinedSharpness = maxOf(sharpness, niv99DetailFloor).coerceIn(0f, caps.sharpenCap)
        val combinedTemporal = 1f - (1f - temporalBlend) *
            (1f - niv99Temporal * niv99Confidence)
        val effSharpness = if (lightMode) 0f else combinedSharpness
        val effDenoise = if (lightMode) 0f else combinedDenoise.coerceIn(0f, caps.denoiseCap)
        val effChromaDenoise = if (lightMode) 0f else combinedChromaDenoise.coerceIn(0f, 1f)
        val effTemporalBlend = if (lightMode) 0f else combinedTemporal.coerceIn(0f, 0.35f)
        // A record-develop NDR master can arrive as PQ on HDR10/HDR10_PLUS capture. Keep those source
        // PQ codes flat on the PQ encoder surface; HLG input still requires the live HLG->PQ delivery
        // conversion so the intermediate and its PQ container tags remain coherent.
        val preserveInputPqMaster =
            lightMode &&
                ndrInputTransport.transfer == NdrInputTransfer.PQ &&
                ndrActive
        // I2405's advertised raw-YUV extension returns non-conformant P010 code values. Range is read
        // only by the explicit-YUV native branch, so UNKNOWN selects the neutral, accelerated external
        // sampler conversion without changing transfer, primaries, bit depth, NDR grade, or metadata.
        val nativeInputRange = when {
            projectOwnedRawIngress -> ndrInputTransport.range.shaderCode
            NdrExplicitYuvDecodePolicy.isAllowedOnCurrentDevice() ->
                ndrInputTransport.range.shaderCode
            else -> NdrInputRange.UNKNOWN.shaderCode
        }
        val submitted = nativeSubmitHardwareBuffer(
            handle = handle,
            hardwareBuffer = hardwareBuffer,
            width = width,
            height = height,
            format = format,
            presentationTimeNs = presentationTimeNs,
            sensorTimestampNs = sensorTimestampNs,
            sharpness = effSharpness,
            denoise = effDenoise,
            chromaDenoise = effChromaDenoise,
            temporalBlend = effTemporalBlend,
            ndrDeliveryMode = ndrDeliveryMode,
            ndrTargetPeakNits = ndrTargetPeakNits,
            ndrWarmSkinRedTame = ndrWarmSkinRedTame,
            preserveInputPqMaster = preserveInputPqMaster,
            inputTransfer = ndrInputTransport.transfer.shaderCode,
            inputPrimaries = ndrInputTransport.primaries.ordinal,
            inputRange = nativeInputRange,
            inputReadReceipt = inputReadReceipt,
            niv99PacketValid = exactNiv99Tuning != null,
            niv99PixelApplicationApproved = exactNiv99Tuning?.pixelApplicationApproved == true,
            niv99CameraIdHash = exactNiv99Tuning?.identity?.cameraId
                ?.let(Niv99CameraIdentityHash::of) ?: 0L,
            niv99SessionGeneration = exactNiv99Tuning?.identity?.sessionGeneration ?: -1L,
            niv99FrameNumber = exactNiv99Tuning?.identity?.frameNumber ?: -1L,
            niv99SensorTimestampNs = exactNiv99Tuning?.identity?.sensorTimestampNs ?: -1L,
            niv99Authority = exactNiv99Tuning?.authority?.ordinal ?: -1,
            niv99RedCorrection = if (
                exactNiv99Tuning?.authority == Niv99WhiteBalanceAuthority.NIV99_AUTO
            ) exactNiv99Tuning.rgbCorrection[0] else 1f,
            niv99GreenCorrection = if (
                exactNiv99Tuning?.authority == Niv99WhiteBalanceAuthority.NIV99_AUTO
            ) exactNiv99Tuning.rgbCorrection[1] else 1f,
            niv99BlueCorrection = if (
                exactNiv99Tuning?.authority == Niv99WhiteBalanceAuthority.NIV99_AUTO
            ) exactNiv99Tuning.rgbCorrection[2] else 1f,
            niv99Confidence = niv99Confidence,
            prewarmOnly = prewarmOnly
        )
        if (submitted && exactNiv99Tuning?.pixelApplicationApproved == true) {
            exactNiv99Tuning.markPixelApplicationSucceeded()
        }
        return submitted
    }

    @Synchronized
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
    @Synchronized
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

    @Synchronized
    fun summary(): String {
        val handle = nativeHandle
        return if (handle == 0L) {
            if (nativeCloseOwnership.hasQuarantinedOwnership) {
                "offscreenAhbEglEncoder=close-quarantined"
            } else {
                "offscreenAhbEglEncoder=released"
            }
        } else {
            nativeGetSummary(handle)
        }
    }

    /**
     * Retires this bridge transactionally. A false result keeps the exact native owner quarantined
     * for a later close retry, while [nativeHandle] remains zero so no post-close render/configuration
     * operation can reach partially retired native state.
     */
    @Synchronized
    internal fun closeAcknowledged(): Boolean {
        val handle = nativeCloseOwnership.beginClose()
        if (handle == 0L) return nativeCloseOwnership.isClosed
        val acknowledged = try {
            nativeDestroy(handle)
        } catch (failure: Throwable) {
            Log.e(TAG, "Native offscreen bridge close threw; ownership quarantined", failure)
            false
        }
        return nativeCloseOwnership.completeClose(handle, acknowledged)
    }

    override fun close() {
        if (!closeAcknowledged()) {
            Log.e(TAG, "Native offscreen bridge close was not acknowledged; retry owner retained")
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long): Boolean
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
        sensorTimestampNs: Long,
        sharpness: Float,
        denoise: Float,
        chromaDenoise: Float,
        temporalBlend: Float,
        ndrDeliveryMode: Int,
        ndrTargetPeakNits: Float,
        ndrWarmSkinRedTame: Float,
        preserveInputPqMaster: Boolean,
        inputTransfer: Int,
        inputPrimaries: Int,
        inputRange: Int,
        inputReadReceipt: Long,
        niv99PacketValid: Boolean,
        niv99PixelApplicationApproved: Boolean,
        niv99CameraIdHash: Long,
        niv99SessionGeneration: Long,
        niv99FrameNumber: Long,
        niv99SensorTimestampNs: Long,
        niv99Authority: Int,
        niv99RedCorrection: Float,
        niv99GreenCorrection: Float,
        niv99BlueCorrection: Float,
        niv99Confidence: Float,
        prewarmOnly: Boolean
    ): Boolean
    private external fun nativeSubmitPregradedBt2020Pq(
        handle: Long,
        hardwareBuffer: HardwareBuffer,
        width: Int,
        height: Int,
        presentationTimeNs: Long,
        semantic: Int,
        abiVersion: Int,
        profileGeneration: Long,
        profileRevision: Long,
        slotIndex: Int,
        slotToken: Long,
        effectiveGradeFingerprint: Long,
        rgbRange: Int,
        inputReadReceipt: Long
    ): Boolean
    private external fun nativePollProjectOwnedInputReadFence(
        handle: Long,
        receipt: Long,
        timeoutNs: Long
    ): Int
    private external fun nativeAcknowledgeProjectOwnedInputReadFence(
        handle: Long,
        receipt: Long
    ): Boolean
    private external fun nativePrewarmProjectOwnedRawTransport(
        handle: Long,
        hardwareBuffer: HardwareBuffer,
        width: Int,
        height: Int,
        format: Int,
        slot: Int
    ): Boolean
    private external fun nativeGetLastCameraP010SdrPrewarmBufferId(handle: Long): Long
    private external fun nativeSubmitEncoderReadinessFrame(
        handle: Long,
        presentationTimeNs: Long
    ): Boolean
    private external fun nativeSetRecordGrade(
        handle: Long,
        matrix: FloatArray,
        contrast: Float
    )
    private external fun nativeSetNdrMeasuredCorrection(
        handle: Long,
        enabled: Boolean,
        redScale: Float,
        greenScale: Float,
        blueScale: Float,
        highlightRolloffBias: Float,
        shadowLiftBias: Float,
        saturationScale: Float,
        skinHueCorrectionDegrees: Float,
        skyHueCorrectionDegrees: Float,
        sensorDiffuseScene: Float,
        signalGamma: Float,
        toeWidth: Float,
        toeAmount: Float,
        highlightDesat: Float,
        shadowMidtoneLift: Float,
        midtoneContrast: Float,
        chromaBoost: Float,
        skyChromaBonus: Float,
        localDetailStrength: Float,
        localDetailLowerStops: Float,
        localDetailUpperStops: Float
    )
    private external fun nativeSetCreativeLut(
        handle: Long,
        data: FloatArray?,
        size: Int,
        strength: Float
    )
    private external fun nativeSetEglImageCacheEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetDecodeOnceEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetGatherGridEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetGpuStageTimingEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetEncoderOutputFlip(handle: Long, flipX: Boolean, flipY: Boolean)
    private external fun nativeResetTemporalHistory(handle: Long): Boolean
    private external fun nativeGetSummary(handle: Long): String
}
