package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.camera.RawDescriptorNoiseInputs
import com.neoproc.neoproccamx.camera.SensorNoiseModel
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * s1-f06 / CP12 / CP13 — the JVM mirror + decision layer for the MFSR (multi-frame super-resolution)
 * fusion push constants consumed by `mfsr_fusion.comp` (anisotropic steering-kernel regression with a
 * heteroscedastic 3σ ghost veto, the Wronski et al. 2019 "Handheld Multi-Frame Super-Resolution" bar).
 *
 * Why this exists on the JVM, dormant:
 *  - **ABI mirror.** [toPushConstants] packs the exact C++ `MfsrFusionPushConstants` layout
 *    (int `activeFrameCount`, then float `noiseS`/`noiseO`/`gyroOffsetScale`), locked field-for-field by
 *    `MfsrPushConstantLayoutTest` against the GLSL `PushConstants` block — so a reordered slot or an
 *    int/float drift fails the build before it can corrupt the GPU pass.
 *  - **The missing s1-f06 hop.** The heteroscedastic noise model already resolves end-to-end on the JVM
 *    (`CameraSensorNoiseModelPolicy` → [RawDescriptorNoiseInputs]); what was missing is the final
 *    `(noiseS, noiseO) → MFSR push constants` mapping ([fromNoiseInputs]). Plumbing it across JNI — in
 *    the SAME change that seeds the real RAW burst ring + per-frame Argus flow into bindings 0/1 — is
 *    the device-gated STEP B/C/D. Until then the native dispatcher uses [DORMANT_DEFAULT] against the
 *    cleared-zero dummy bindings (no detail invented, no UB).
 *  - **Host-verifiable veto.** [isGhost]/[ghostSigmaThreshold] are bit-faithful host ports of the
 *    shader's 3σ rejection (mfsr_fusion.comp:90-91, 113-115), so the admit/reject behaviour — and the
 *    `noise==0 ⇒ reject-all` pathology that makes the dummy path fuse nothing — is unit-testable
 *    without a device.
 *
 * Computes no pixels; it only decides parameters and encodes the contract the GPU pass must satisfy.
 */
internal data class MfsrFusionParams(
    /** Burst depth actually fused (the shader's `activeFrameCount`); adaptive within [MIN_ACTIVE_FRAMES, MAX_ACTIVE_FRAMES]. */
    val activeFrameCount: Int,
    /** Shot (signal-dependent) noise coefficient `noiseS`; variance = noiseS·signal + noiseO. */
    val noiseS: Float,
    /** Read (constant) noise coefficient `noiseO`. */
    val noiseO: Float,
    /** Scales the integrated gyro global-motion shift added to the optical-flow offset (s1-f04). */
    val gyroOffsetScale: Float
) {
    /**
     * Push-constant layout for `mfsr_fusion.comp`'s `PushConstants` block, mirroring the C++
     * `MfsrFusionPushConstants` ABI. `activeFrameCount` is bit-reinterpreted into its float slot so the
     * native side uploads one contiguous block and the GLSL reads it back as `int`; the three
     * coefficients pack as plain floats. Order is locked by `MfsrPushConstantLayoutTest`.
     */
    fun toPushConstants(): FloatArray = floatArrayOf(
        Float.fromBits(activeFrameCount),
        noiseS,
        noiseO,
        gyroOffsetScale
    )

    companion object {
        /** Adaptive burst-depth bounds (prompt §4.2: N adaptive in 3..8). */
        const val MIN_ACTIVE_FRAMES = 3
        const val MAX_ACTIVE_FRAMES = 8

        /**
         * What the native MFSR dispatch uploads today: 4 frames, zero noise, unit gyro scale. This is a
         * deliberate no-op against the cleared-zero dummy bindings — never a claim of real fusion.
         */
        val DORMANT_DEFAULT = MfsrFusionParams(
            activeFrameCount = 4,
            noiseS = 0f,
            noiseO = 0f,
            gyroOffsetScale = 1f
        )

        /** Clamp a requested burst depth into the adaptive [MIN_ACTIVE_FRAMES, MAX_ACTIVE_FRAMES] range. */
        fun clampActiveFrameCount(requested: Int): Int =
            requested.coerceIn(MIN_ACTIVE_FRAMES, MAX_ACTIVE_FRAMES)

        /**
         * The missing s1-f06 boundary: resolve MFSR push constants from the heteroscedastic noise inputs
         * the RAW descriptor already carries (`CameraSensorNoiseModelPolicy.descriptorNoiseInputs`).
         * `activeFrameCount` is clamped to the adaptive range; `gyroOffsetScale` defaults to unit.
         */
        fun fromNoiseInputs(
            noise: RawDescriptorNoiseInputs,
            activeFrameCount: Int,
            gyroOffsetScale: Float = 1f
        ): MfsrFusionParams = MfsrFusionParams(
            activeFrameCount = clampActiveFrameCount(activeFrameCount),
            noiseS = noise.noiseS,
            noiseO = noise.noiseO,
            gyroOffsetScale = gyroOffsetScale
        )

        /** Convenience: resolve straight from a [SensorNoiseModel] (shot→noiseS, read→noiseO). */
        fun fromNoiseModel(
            model: SensorNoiseModel,
            activeFrameCount: Int,
            gyroOffsetScale: Float = 1f
        ): MfsrFusionParams = fromNoiseInputs(
            RawDescriptorNoiseInputs(model.shotNoiseCoefficient, model.readNoiseCoefficient),
            activeFrameCount,
            gyroOffsetScale
        )

        /**
         * CP13 — per-frame flow indexing invariant. Each fused frame `f` must read its OWN aligned flow
         * (binding-1 layer `f`), not one shared field sampled for every `f`. The current shader samples a
         * single `sampler2D` regardless of `f` (the indexing bug); the elite target indexes the flow per
         * frame. This is the host contract the device-gated shader/descriptor change (sampler2D →
         * sampler2DArray) must satisfy: the flow layer equals the frame index.
         */
        fun flowLayerForFrame(frameIndex: Int): Int = frameIndex

        /**
         * Heteroscedastic 3σ threshold — host port of mfsr_fusion.comp:90-91:
         * `sigmaThresh = 3·sqrt(noiseS·signal + noiseO)`. The variance is floored at 0 only for sqrt
         * safety; for the non-negative signals the shader sees this is identical to the GLSL.
         */
        fun ghostSigmaThreshold(referenceSignal: Float, noiseS: Float, noiseO: Float): Float {
            val variance = (noiseS * referenceSignal + noiseO).coerceAtLeast(0f)
            return 3f * sqrt(variance)
        }

        /**
         * Host port of the 3σ ghost veto (mfsr_fusion.comp:113-115): a non-reference sample (`f > 0`) is
         * a ghost — and skipped — when its deviation from the reference exceeds the 3σ threshold. The
         * reference frame (`f == 0`) is always admitted. With `noiseS == noiseO == 0` the threshold
         * collapses to 0, so every differing non-reference sample is vetoed ("reject all") — exactly why
         * the dummy/zero-noise path fuses nothing, and why a real noise model is required to admit good
         * frames while still rejecting ghosts (prompt §3, §4.4).
         */
        fun isGhost(
            frameIndex: Int,
            sampleSignal: Float,
            referenceSignal: Float,
            noiseS: Float,
            noiseO: Float
        ): Boolean {
            if (frameIndex <= 0) return false
            val deviation = abs(sampleSignal - referenceSignal)
            return deviation > ghostSigmaThreshold(referenceSignal, noiseS, noiseO)
        }

        /** Inverse of [isGhost] — whether sample `f` contributes to the fusion accumulator. */
        fun admitsFrame(
            frameIndex: Int,
            sampleSignal: Float,
            referenceSignal: Float,
            noiseS: Float,
            noiseO: Float
        ): Boolean = !isGhost(frameIndex, sampleSignal, referenceSignal, noiseS, noiseO)
    }
}
