package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.recording.RecordDevelopP010ColorIngress.OutputTransfer
import com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar.DevelopColorSpace
import com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar.DevelopColorTransfer
import com.neoproc.neoproccamx.snce.SnceNeuralEnhancement

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  RECORD-THEN-DEVELOP — Phase 7 hybrid CPU+GPU develop SEAM
 * ════════════════════════════════════════════════════════════════════════════
 *
 * The offline develop ([RecordDevelopMovConverter]) is CPU-parallel by default. Phase 7 adds an OPTIONAL GPU
 * lane behind this seam so a future on-device Vulkan develop pipeline can offload the heavy per-pixel colour
 * develop (decode → [VideoDevelopColorPipeline] → egress OETF → 10-bit codes) while the CPU keeps the
 * ordering-sensitive stages (serial decode, Phase-4 enhance, packing, in-order muxing) — GPU and CPU busy
 * together. It mirrors the proven [RawDevelopGpuAccelerator] hybrid (single permit, first-frame parity gate,
 * UNIFORM algorithm per clip, per-frame CPU fallback).
 *
 * WHY AN INJECTABLE INTERFACE (not a static like the RAW accelerator): it lets the pure-JVM suite drive the
 * ENTIRE scheduler — parity accept/reject, uniform-per-clip commitment, per-frame dispatch failure — through
 * a fake, so the concurrency-sensitive logic is verified off-device. Only the actual Vulkan dispatch stays
 * device-gated. The converter injects it via a factory ([RecordDevelopMovConverter.convertWithAudit]); a null
 * factory (the default, and every current call site) runs the byte-exact CPU develop unchanged.
 *
 * NO-DOWNGRADE: the accelerator only ever REPLACES the per-pixel colour develop, and only when
 *   1. [RecordDevelopGpuAccelerationFlag] is on,
 *   2. a factory is injected AND its accelerator reports [isAvailable], and
 *   3. the first-frame parity gate accepts it (a broken transform / wrong colour space blows past tolerance
 *      → CPU for the whole clip), and per-frame [developFrameToCodes] returns null → CPU for that frame.
 * If native GPU conversion is unavailable or parity-rejected, the develop runs pure CPU (byte-identical to
 * Phase 2–6).
 */
internal interface RecordDevelopGpuAccelerator {
    /** True only when a supported GPU develop pipeline is attached AND enabled. Cheap; performs no dispatch. */
    fun isAvailable(): Boolean

    /**
     * Develop ONE frame's ALREADY-Phase-4-enhanced P010 fully on the GPU into interleaved 10-bit RGB codes
     * (row-major, R fastest) — the EXACT output contract of the CPU develop, so the two are directly
     * parity-comparable. The GPU reproduces only the colour develop + egress encode (the CPU still ran the
     * denoise/detail enhance on [p010] and still packs + muxes the returned codes). Returns null on ANY
     * dispatch/readback failure ⇒ the caller develops this frame on the byte-exact CPU path (no-downgrade).
     *
     * It MUST treat [p010] as READ-ONLY: the very same buffer is fed forward as the successor frame's temporal
     * prior, so mutating it in place would corrupt the next frame's develop.
     */
    fun developFrameToCodes(p010: ByteArray, width: Int, height: Int): ShortArray?

    /**
     * Faster equivalent of [developFrameToCodes] + [RawTenBitPacker]. Implementations may return the final
     * QuickTime sample payload directly to avoid a huge intermediate RGB [ShortArray]. The default keeps the
     * existing byte-exact path for tests/fallbacks.
     */
    fun developFrameToPackedPayload(
        p010: ByteArray,
        width: Int,
        height: Int,
        outputFormat: RecordDevelopOutputFormat
    ): ByteArray? {
        val codes = developFrameToCodes(p010, width, height) ?: return null
        return when (outputFormat) {
            RecordDevelopOutputFormat.R210_444 -> RawTenBitPacker.packR210(codes, width, height)
            RecordDevelopOutputFormat.V210_422 -> RawTenBitPacker.packV210(codes, width, height)
        }
    }

    /**
     * Faster equivalent of [developFrameToCodes] + RGB→P010 4:2:0 packing for HEVC Main10 delivery.
     * Implementations return null when the native path is unavailable; the converter then uses the existing
     * byte-exact CPU fallback. [bt709Matrix] selects Rec.709 limited-range YCbCr; false selects BT.2020 NCL.
     */
    fun developFrameToP010420Payload(
        p010: ByteArray,
        width: Int,
        height: Int,
        bt709Matrix: Boolean
    ): ByteArray? = null

    /**
     * Release device resources (Vulkan pipelines/buffers/descriptor pools). A FRESH accelerator is built per
     * clip via the converter's factory, so the converter calls this once the develop finishes (in a `finally`)
     * — a device impl MUST free here or it leaks across a batch of develops. Default no-op: the dormant stub
     * and the JVM fakes hold nothing.
     */
    fun close() {}
}

/**
 * The immutable per-clip develop facts the converter hands the GPU factory once, so the accelerator can
 * reproduce the CPU develop without re-deriving the plan (the converter's [VideoDevelopColorPipeline] build +
 * colour-delivery resolution stay the single source of truth). The [p010] fed to
 * [RecordDevelopGpuAccelerator.developFrameToCodes] is already Phase-4-enhanced, so this carries only the
 * colour-develop + egress spec — never the enhance config.
 */
internal class RecordDevelopGpuContext(
    val format: RecordDevelopDecodedFormat,
    val target: DevelopTarget,
    val decodeSpace: DevelopColorSpace,
    val decodeTransfer: DevelopColorTransfer,
    val outputTransfer: OutputTransfer,
    val snceAppearanceStrength: Float,
    val filmPrintStrength: Float,
    val acesToneMap: Boolean,
    val semanticClass: SnceNeuralEnhancement.SemanticClass,
    /** The CPU develop clamps its pipeline output to [0,1]; the GPU MUST too, or highlights diverge past the
     *  single-frame parity probe (a mid-tone probe passes, then a bright frame silently blows out). */
    val clampOutput: Boolean,
    val ndrGenuineHdr: Boolean,
    val diffuseWhiteNits: Float,
    val widenToRec2020: Boolean,
    val workingRgbToXyz: FloatArray?,
    val sceneLuminanceLA: Float
)

/**
 * Device-gated master switch for the offline GPU develop lane. DEFAULT ON, but still protected by native
 * capability checks plus the converter's first-frame CPU parity gate. If either fails, output remains CPU.
 */
internal object RecordDevelopGpuAccelerationFlag {
    @Volatile
    var enabled: Boolean = true
        private set

    fun setEnabled(value: Boolean) {
        enabled = value
    }
}

/**
 * The disabled DEVICE seam stub. Production uses [NativeRecordDevelopGpuAccelerator]; this remains for tests
 * and for explicit CPU-only injection.
 */
internal class DormantRecordDevelopGpuAccelerator(
    @Suppress("UNUSED_PARAMETER") context: RecordDevelopGpuContext
) : RecordDevelopGpuAccelerator {
    override fun isAvailable(): Boolean = false
    override fun developFrameToCodes(p010: ByteArray, width: Int, height: Int): ShortArray? = null
}
