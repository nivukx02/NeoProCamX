package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.recording.RecordDevelopP010ColorIngress.OutputTransfer
import com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar.DevelopColorSpace
import com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar.DevelopColorTransfer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 7 — drives the CPU+GPU HYBRID develop scheduler entirely on the JVM through a fake
 * [RecordDevelopGpuAccelerator]. It proves the properties the device-only Vulkan lane can't be unit-tested for:
 *
 *   • NO-DOWNGRADE — a null factory, the OFF flag, or an unavailable accelerator all develop pure-CPU and are
 *     byte-identical to the Phase 2–6 output (and never touch the GPU seam);
 *   • PARITY GATE — a parity-close GPU is chosen; a diverging GPU is rejected for the whole clip;
 *   • UNIFORM PER CLIP — once decided, EVERY frame follows one path (the develop never mixes algorithms — the
 *     RAW-hybrid lesson), asserted through the accelerator's own call count;
 *   • PER-FRAME FALLBACK — a transient GPU failure develops that one frame on the byte-exact CPU path;
 *   • DETERMINISM — GPU output is byte-identical across worker counts.
 */
class RecordDevelopHybridSchedulingTest {

    private val temps = mutableListOf<File>()

    @After
    fun cleanup() {
        // The acceleration flag is a process-global — never leak an ON state into another suite.
        RecordDevelopGpuAccelerationFlag.setEnabled(false)
        temps.forEach { it.delete() }
    }

    private fun tempFile(name: String): File =
        File.createTempFile("recdevhy_$name", ".bin").also { temps += it }

    // ── canonical uniform P010 frame builder (matches RecordDevelopP010FrameReader layout) ─────────────

    private fun uniformP010(width: Int, height: Int, yCode: Int, cbCode: Int = 512, crCode: Int = 512): ByteArray {
        val chromaW = (width + 1) / 2
        val chromaH = (height + 1) / 2
        val lumaBytes = width * height * 2
        val out = ByteArray(lumaBytes + chromaW * chromaH * 2 * 2)
        fun put(offset: Int, code: Int) {
            val v = (code and 0x3FF) shl 6
            out[offset] = (v and 0xFF).toByte()
            out[offset + 1] = ((v ushr 8) and 0xFF).toByte()
        }
        for (p in 0 until width * height) put(p * 2, yCode)
        var o = lumaBytes
        for (p in 0 until chromaW * chromaH) {
            put(o, cbCode); put(o + 2, crCode); o += 4
        }
        return out
    }

    private fun frames(width: Int, height: Int, count: Int): List<RecordDevelopP010Frame> =
        (0 until count).map { i ->
            RecordDevelopP010Frame(
                index = i.toLong(),
                presentationTimeUs = 0L,
                width = width,
                height = height,
                p010 = uniformP010(width, height, yCode = (80 + i * 40).coerceAtMost(1000))
            )
        }

    private fun readerFactoryOf(list: List<RecordDevelopP010Frame>, w: Int, h: Int): (File) -> RecordDevelopP010FrameReader =
        { InMemoryP010FrameReader(list, RecordDevelopDecodedFormat(w, h, 30)) }

    /** SDR / Rec.709 develop with strength 0 + enhance OFF ⇒ a pure decode→encode passthrough (identity). */
    private val identityOptions = RecordDevelopConversionOptions(
        snceAppearanceStrength = 0f,
        filmPrintStrength = 0f,
        enhance = RecordDevelopEnhanceOptions.DISABLED
    )

    /**
     * The EXACT interleaved 10-bit codes the CPU develop produces for a spatially-uniform SDR frame under
     * [identityOptions] (decode pixel (0,0) → identity pipeline → SDR OETF → replicate). This lets the fake GPU
     * return byte-exact CPU codes (⇒ parity accepted + GPU file == CPU file) for any frame it is handed.
     */
    private fun cpuIdentityCodes(p010: ByteArray, w: Int, h: Int): ShortArray {
        val yc = RecordDevelopP010ColorIngress.readLumaCode(p010, w, h, 0, 0)
        val cb = RecordDevelopP010ColorIngress.readChromaCode(p010, w, h, 0, 0, 0)
        val cr = RecordDevelopP010ColorIngress.readChromaCode(p010, w, h, 0, 0, 1)
        val lin = RecordDevelopP010ColorIngress.decodeToLinearRgb(yc, cb, cr, DevelopColorSpace.REC709, DevelopColorTransfer.SDR)
        val enc = RecordDevelopP010ColorIngress.encodeLinearRgb(lin[0], lin[1], lin[2], OutputTransfer.SDR_REC709_OETF, 203f)
        val codes = ShortArray(w * h * 3)
        var i = 0
        while (i < codes.size) {
            codes[i] = enc[0].toShort(); codes[i + 1] = enc[1].toShort(); codes[i + 2] = enc[2].toShort()
            i += 3
        }
        return codes
    }

    private class FakeGpuAccelerator(
        private val available: Boolean,
        private val develop: (ByteArray, Int, Int) -> ShortArray?
    ) : RecordDevelopGpuAccelerator {
        val developCalls = AtomicInteger(0)
        @Volatile var closed = false
        override fun isAvailable(): Boolean = available
        override fun developFrameToCodes(p010: ByteArray, width: Int, height: Int): ShortArray? {
            developCalls.incrementAndGet()
            return develop(p010, width, height)
        }
        override fun close() { closed = true }
    }

    private fun convert(
        list: List<RecordDevelopP010Frame>,
        w: Int,
        h: Int,
        name: String,
        options: RecordDevelopConversionOptions = identityOptions,
        factory: ((RecordDevelopGpuContext) -> RecordDevelopGpuAccelerator?)? = null
    ): Pair<RecordDevelopConversionAudit, ByteArray> {
        val out = tempFile(name)
        val audit = RecordDevelopMovConverter.convertWithAudit(
            master = tempFile("${name}_m"),
            output = out,
            sidecar = null, // SDR / Rec.709
            options = options,
            sourceKind = RecordDevelopMasterKind.SDR,
            readerFactory = readerFactoryOf(list, w, h),
            gpuAcceleratorFactory = factory
        )
        return audit to out.readBytes()
    }

    private fun assertStrictGpuFailure(expected: String, block: () -> Unit) {
        try {
            block()
            fail("expected strict GPU conversion to fail with: $expected")
        } catch (t: IllegalStateException) {
            assertTrue(
                "message '${t.message}' should contain '$expected'",
                t.message?.contains(expected) == true
            )
        }
    }

    // ── tests ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun noFactoryDevelopsOnCpuAndReportsNotAccelerated() {
        val (audit, _) = convert(frames(12, 4, 4), 12, 4, "nofac")
        assertFalse("no GPU factory ⇒ pure CPU develop", audit.gpuAccelerated)
    }

    @Test
    fun flagOffKeepsGpuDormantEvenWithAvailableFactory() {
        // NO-DOWNGRADE: with the master flag OFF (the default), an injected + available accelerator must be
        // completely dormant — never invoked, and the output byte-identical to the pure-CPU develop.
        RecordDevelopGpuAccelerationFlag.setEnabled(false)
        val w = 12; val h = 4; val list = frames(w, h, 5)
        val fake = FakeGpuAccelerator(available = true) { p, fw, fh -> cpuIdentityCodes(p, fw, fh) }

        val (cpuAudit, cpuBytes) = convert(list, w, h, "flagoff_cpu")
        val (gpuAudit, gpuBytes) = convert(list, w, h, "flagoff_gpu", factory = { fake })

        assertFalse("flag OFF ⇒ not accelerated", gpuAudit.gpuAccelerated)
        assertEquals("flag OFF ⇒ accelerator never touched", 0, fake.developCalls.get())
        assertFalse(cpuAudit.gpuAccelerated)
        assertTrue("flag OFF output byte-identical to pure CPU", cpuBytes.contentEquals(gpuBytes))
    }

    @Test
    fun unavailableAcceleratorFallsBackToCpuByteIdentical() {
        RecordDevelopGpuAccelerationFlag.setEnabled(true)
        val w = 12; val h = 4; val list = frames(w, h, 5)
        val fake = FakeGpuAccelerator(available = false) { p, fw, fh -> cpuIdentityCodes(p, fw, fh) }

        val (_, cpuBytes) = convert(list, w, h, "unavail_cpu")
        val (audit, gpuBytes) = convert(list, w, h, "unavail_gpu", factory = { fake })

        assertFalse("unavailable accelerator ⇒ not accelerated", audit.gpuAccelerated)
        assertEquals("unavailable accelerator ⇒ develop never called", 0, fake.developCalls.get())
        assertTrue("unavailable accelerator output byte-identical to CPU", cpuBytes.contentEquals(gpuBytes))
    }

    @Test
    fun strictGpuWithoutFactoryFailsInsteadOfFallingBackToCpu() {
        RecordDevelopGpuAccelerationFlag.setEnabled(true)
        assertStrictGpuFailure("strict GPU requested") {
            convert(
                frames(12, 4, 3), 12, 4, "strict_nofac",
                options = identityOptions.copy(requireGpuDevelop = true)
            )
        }
    }

    @Test
    fun strictGpuWithUnavailableAcceleratorFailsInsteadOfFallingBackToCpu() {
        RecordDevelopGpuAccelerationFlag.setEnabled(true)
        val fake = FakeGpuAccelerator(available = false) { p, fw, fh -> cpuIdentityCodes(p, fw, fh) }

        assertStrictGpuFailure("no available colour-develop accelerator") {
            convert(
                frames(12, 4, 3), 12, 4, "strict_unavailable",
                options = identityOptions.copy(requireGpuDevelop = true),
                factory = { fake }
            )
        }
        assertEquals("unavailable accelerator is never dispatched", 0, fake.developCalls.get())
    }

    @Test
    fun parityAcceptedGpuDevelopsWholeClipUniformlyAndMatchesCpuBytes() {
        RecordDevelopGpuAccelerationFlag.setEnabled(true)
        val w = 16; val h = 8; val n = 6; val list = frames(w, h, n)
        val fake = FakeGpuAccelerator(available = true) { p, fw, fh -> cpuIdentityCodes(p, fw, fh) }

        val (_, cpuBytes) = convert(list, w, h, "acc_cpu")
        val (audit, gpuBytes) = convert(list, w, h, "acc_gpu", factory = { fake })

        assertTrue("parity-close GPU is chosen", audit.gpuAccelerated)
        // Byte-exact GPU codes ⇒ the GPU file equals the CPU file (delivery == CPU by construction here).
        assertTrue("accepted GPU output byte-identical to CPU", cpuBytes.contentEquals(gpuBytes))
        // UNIFORM per clip: EVERY frame developed on the GPU (n) + one parity probe = n + 1 dispatches. If any
        // frame had silently fallen back to CPU, this count would be lower — the "never mix" guarantee.
        assertEquals("every frame + parity probe on GPU", n + 1, fake.developCalls.get())
        // Lifecycle: the per-clip accelerator is torn down once the develop finishes (device resources freed).
        assertTrue("accelerator is closed after the develop", fake.closed)
    }

    @Test
    fun parityRejectedGpuFallsBackToUniformCpu() {
        RecordDevelopGpuAccelerationFlag.setEnabled(true)
        val w = 16; val h = 8; val n = 6; val list = frames(w, h, n)
        // A GPU that offsets every code by +100 blows past the max tolerance (24) ⇒ rejected for the clip.
        val fake = FakeGpuAccelerator(available = true) { p, fw, fh ->
            val base = cpuIdentityCodes(p, fw, fh)
            ShortArray(base.size) { ((base[it].toInt() and 0xFFFF) + 100).coerceAtMost(1023).toShort() }
        }

        val (_, cpuBytes) = convert(list, w, h, "rej_cpu")
        val (audit, gpuBytes) = convert(list, w, h, "rej_gpu", factory = { fake })

        assertFalse("diverging GPU is rejected", audit.gpuAccelerated)
        assertTrue("rejected ⇒ output byte-identical to pure CPU", cpuBytes.contentEquals(gpuBytes))
        // Only the single first-frame parity probe hit the GPU; NO frame developed on it (uniform CPU).
        assertEquals("rejected GPU used exactly once (the probe)", 1, fake.developCalls.get())
    }

    @Test
    fun strictGpuParityRejectFailsInsteadOfFallingBackToCpu() {
        RecordDevelopGpuAccelerationFlag.setEnabled(true)
        val w = 16; val h = 8; val n = 4
        val fake = FakeGpuAccelerator(available = true) { p, fw, fh ->
            val base = cpuIdentityCodes(p, fw, fh)
            ShortArray(base.size) { ((base[it].toInt() and 0xFFFF) + 100).coerceAtMost(1023).toShort() }
        }

        assertStrictGpuFailure("GPU path was rejected") {
            convert(
                frames(w, h, n), w, h, "strict_reject",
                options = identityOptions.copy(requireGpuDevelop = true, memoryBudgetBytes = 1L),
                factory = { fake }
            )
        }
        assertEquals("strict reject only runs the parity probe", 1, fake.developCalls.get())
    }

    @Test
    fun perFrameGpuFailureFallsBackToCpuForThatFrameByteIdentical() {
        RecordDevelopGpuAccelerationFlag.setEnabled(true)
        val w = 16; val h = 8; val n = 6; val list = frames(w, h, n)
        val nulls = AtomicInteger(0)
        // Parity-close for every frame EXCEPT it returns null (a transient dispatch failure) for the frame
        // whose luma is 160 (frame index 2). Single worker ⇒ frame 0 (luma 80) deterministically probes and
        // passes, so the clip commits to GPU; frame 2 then transiently fails and must fall back to CPU.
        val fake = FakeGpuAccelerator(available = true) { p, fw, fh ->
            val luma = RecordDevelopP010ColorIngress.readLumaCode(p, fw, fh, 0, 0)
            if (luma == 160) { nulls.incrementAndGet(); null } else cpuIdentityCodes(p, fw, fh)
        }

        val (_, cpuBytes) = convert(list, w, h, "fail_cpu")
        val (audit, gpuBytes) = convert(
            list, w, h, "fail_gpu",
            options = identityOptions.copy(memoryBudgetBytes = 1L), // ⇒ single worker, deterministic order
            factory = { fake }
        )

        assertTrue("clip committed to GPU (frame 0 passed parity)", audit.gpuAccelerated)
        assertTrue("a transient GPU failure fired", nulls.get() >= 1)
        assertTrue("per-frame CPU fallback keeps output byte-identical to pure CPU", cpuBytes.contentEquals(gpuBytes))
    }

    @Test
    fun strictGpuPerFrameDispatchFailureFailsInsteadOfFallingBackToCpu() {
        RecordDevelopGpuAccelerationFlag.setEnabled(true)
        val w = 16; val h = 8; val n = 6
        val gpuCall = AtomicInteger(0)
        val fake = FakeGpuAccelerator(available = true) { p, fw, fh ->
            if (gpuCall.incrementAndGet() == 1) cpuIdentityCodes(p, fw, fh) else null
        }

        assertStrictGpuFailure("dispatch returned null") {
            convert(
                frames(w, h, n), w, h, "strict_dispatch_null",
                options = identityOptions.copy(requireGpuDevelop = true, memoryBudgetBytes = 1L),
                factory = { fake }
            )
        }
        assertTrue("strict mode fails after the accepted parity probe", fake.developCalls.get() >= 2)
    }

    @Test
    fun strictGpuAcceptedPathSucceedsAndReportsAccelerated() {
        RecordDevelopGpuAccelerationFlag.setEnabled(true)
        val w = 16; val h = 8; val n = 5; val list = frames(w, h, n)
        val fake = FakeGpuAccelerator(available = true) { p, fw, fh -> cpuIdentityCodes(p, fw, fh) }

        val (audit, _) = convert(
            list, w, h, "strict_accept",
            options = identityOptions.copy(requireGpuDevelop = true),
            factory = { fake }
        )

        assertTrue("strict GPU output is GPU-produced", audit.gpuAccelerated)
        assertTrue("strict flag is captured in the audit", audit.requireGpuDevelop)
        assertEquals("every frame + parity probe on GPU", n + 1, fake.developCalls.get())
    }

    @Test
    fun gpuOutputIsByteIdenticalAcrossWorkerCounts() {
        RecordDevelopGpuAccelerationFlag.setEnabled(true)
        val w = 16; val h = 8; val n = 24; val list = frames(w, h, n)
        val singleFake = FakeGpuAccelerator(available = true) { p, fw, fh -> cpuIdentityCodes(p, fw, fh) }
        val (single, singleBytes) = convert(
            list, w, h, "det_single",
            options = identityOptions.copy(memoryBudgetBytes = 1L),
            factory = { singleFake }
        )
        assertEquals("tiny budget forces a single worker", 1, single.workerCount)
        assertTrue("single-worker GPU develop is accelerated", single.gpuAccelerated)

        Assume.assumeTrue(
            "needs >=3 cores to exercise the multi-worker GPU path",
            Runtime.getRuntime().availableProcessors() >= 3
        )
        val multiFake = FakeGpuAccelerator(available = true) { p, fw, fh -> cpuIdentityCodes(p, fw, fh) }
        val (multi, multiBytes) = convert(list, w, h, "det_multi", factory = { multiFake })
        assertTrue("default budget fans out to >1 worker (got ${multi.workerCount})", multi.workerCount > 1)
        assertTrue("hybrid GPU output byte-identical across worker counts", singleBytes.contentEquals(multiBytes))
    }

    @Test
    fun decisionGpuButEveryDispatchFailsReportsNotAcceleratedAndByteIdentical() {
        // Provenance honesty: a flaky GPU can PASS the parity probe (first call) then fail EVERY real dispatch.
        // The written file is then 100% CPU — so audit.gpuAccelerated MUST report false (it counts frames that
        // actually came from the GPU, not the decision) and the bytes MUST equal the pure-CPU develop.
        RecordDevelopGpuAccelerationFlag.setEnabled(true)
        val w = 16; val h = 8; val n = 6; val list = frames(w, h, n)
        val gpuCall = AtomicInteger(0)
        // Call 1 = the parity probe → return CPU-exact codes (passes → clip commits to GPU). Calls 2.. (every
        // real per-frame dispatch) → null → CPU fallback. Deterministic only under a single worker.
        val fake = FakeGpuAccelerator(available = true) { p, fw, fh ->
            if (gpuCall.incrementAndGet() == 1) cpuIdentityCodes(p, fw, fh) else null
        }

        val (_, cpuBytes) = convert(list, w, h, "flaky_cpu")
        val (audit, gpuBytes) = convert(
            list, w, h, "flaky_gpu",
            options = identityOptions.copy(memoryBudgetBytes = 1L), // ⇒ single worker, deterministic order
            factory = { fake }
        )

        assertFalse("no frame actually developed on GPU ⇒ not accelerated", audit.gpuAccelerated)
        assertTrue("all-CPU fallback output byte-identical to pure CPU", cpuBytes.contentEquals(gpuBytes))
        assertTrue("accelerator still torn down", fake.closed)
    }
}
