package com.neoproc.neoproccamx.recording

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neoproc.neoproccamx.recording.RecordDevelopP010ColorIngress.OutputTransfer
import com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar.DevelopColorSpace
import com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar.DevelopColorTransfer
import com.neoproc.neoproccamx.snce.SnceIlluminantEstimator
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RecordDevelopNativeGpuParityInstrumentedTest {

    private val tempFiles = mutableListOf<File>()

    @org.junit.Before
    fun setup() {
        RecordDevelopParityGatePolicy.setBypassGate(false)
    }

    @After
    fun cleanup() {
        RecordDevelopGpuAccelerationFlag.setEnabled(true)
        RecordDevelopParityGatePolicy.setBypassGate(true)
        RecordDevelopParityDiagnosticsFlag.set(false)
        tempFiles.forEach { it.delete() }
    }

    @Test
    fun nativeGpuAcceptedForSdrSnceFilmPrintDevelop() {
        RecordDevelopGpuAccelerationFlag.setEnabled(true)
        val width = 16
        val height = 8
        val audit = convert(
            width = width,
            height = height,
            frames = frames(width, height),
            sidecar = null,
            filmPrintStrength = 1f
        )
        assertTrue("SDR film-print record-develop GPU parity gate should accept native Vulkan", audit.gpuAccelerated)
    }

    @Test
    fun nativeGpuAcceptedForLq032WideScenePattern() {
        // LQ-032 regression: the 320x180 wide-scene fixture exposed a structured blue-channel CAM16
        // divergence that the tiny smoke fixtures did not reach. Keep the production 0.65 SNCE strength,
        // disable pre-develop enhancement so this test isolates the colour lane, and require the real parity
        // gate to accept the GPU rather than weakening or bypassing the quality contract.
        RecordDevelopParityDiagnosticsFlag.set(true)
        RecordDevelopGpuAccelerationFlag.setEnabled(true)
        val width = 320
        val height = 180
        val audit = convert(
            width = width,
            height = height,
            frames = (0 until 2).map { index ->
                RecordDevelopP010Frame(
                    index = index.toLong(),
                    presentationTimeUs = index * 40_000L,
                    width = width,
                    height = height,
                    p010 = lq032PatternedP010(width, height, index)
                )
            },
            sidecar = null,
            snceAppearanceStrength = 0.65f
        )
        assertTrue(
            "LQ-032 wide-scene SDR colour develop must pass the strict native GPU parity gate",
            audit.gpuAccelerated
        )
    }

    @Test
    fun nativeGpuAcceptedForLq032WideSceneWithoutSnce() {
        RecordDevelopParityDiagnosticsFlag.set(true)
        RecordDevelopGpuAccelerationFlag.setEnabled(true)
        val audit = convertLq032WideScene(snceAppearanceStrength = 0f)
        assertTrue(
            "LQ-032 wide-scene decode/encode must pass the strict native GPU parity gate without SNCE",
            audit.gpuAccelerated
        )
    }

    @Test
    fun nativeGpuAcceptedForLq032SingleWideSceneFrame() {
        RecordDevelopParityDiagnosticsFlag.set(true)
        RecordDevelopGpuAccelerationFlag.setEnabled(true)
        val audit = convertLq032WideScene(snceAppearanceStrength = 0.65f, frameCount = 1)
        assertTrue(
            "LQ-032 single wide frame must pass the strict native GPU parity gate",
            audit.gpuAccelerated
        )
    }

    @Test
    fun nativeGpuAcceptedForLq032WideSceneAtFullSnce() {
        RecordDevelopParityDiagnosticsFlag.set(true)
        RecordDevelopGpuAccelerationFlag.setEnabled(true)
        val audit = convertLq032WideScene(snceAppearanceStrength = 1f)
        assertTrue(
            "LQ-032 wide-scene full-strength SNCE must pass the strict native GPU parity gate",
            audit.gpuAccelerated
        )
    }

    @Test
    fun nativeGpuLq032WideSceneStageDiagnostics() {
        val width = 320
        val height = 180
        val payload = lq032PatternedP010(width, height, frameIndex = 0)
        val failures = ArrayList<String>()
        for (strength in listOf(0f, 0.65f, 1f)) {
            val context = RecordDevelopGpuContext(
                format = RecordDevelopDecodedFormat(width, height, 25),
                target = DevelopTarget.SDR_REC709,
                decodeSpace = DevelopColorSpace.REC709,
                decodeTransfer = DevelopColorTransfer.SDR,
                outputTransfer = OutputTransfer.SDR_REC709_OETF,
                snceAppearanceStrength = strength,
                filmPrintStrength = 0f,
                warmSkinRedTameStrength = NdrWarmSkinRedTameFlag.effectiveStrength.toFloat(),
                acesToneMap = false,
                semanticClass = com.neoproc.neoproccamx.snce.SnceNeuralEnhancement.SemanticClass.OTHER,
                clampOutput = true,
                ndrGenuineHdr = false,
                diffuseWhiteNits = 203f,
                widenToRec2020 = false,
                workingRgbToXyz = null,
                sceneLuminanceLA = VideoDevelopColorPipeline.DEFAULT_SCENE_LA
            )
            val accelerator = NativeRecordDevelopGpuAccelerator(context)
            try {
                assertTrue("native record-develop GPU should be available", accelerator.isAvailable())
                val gpu = requireNotNull(accelerator.developFrameToCodes(payload, width, height))
                val cpu = directWideCpuCodes(
                    payload,
                    width,
                    height,
                    strength,
                    NdrWarmSkinRedTameFlag.effectiveStrength.toFloat()
                )
                val stats = requireNotNull(
                    RecordDevelopParityDiagnostics.compareCodes(
                        gpu,
                        cpu,
                        "LQ032_SNCE_${strength}",
                        width
                    )
                )
                val probeCode = (80 * width + 264) * 3
                Log.e(
                    "Lq032StageParity",
                    "strength=$strength probe=(264,80) " +
                        "gpu=(${gpu[probeCode].toInt() and 0xFFFF},${gpu[probeCode + 1].toInt() and 0xFFFF}," +
                        "${gpu[probeCode + 2].toInt() and 0xFFFF}) " +
                        "cpu=(${cpu[probeCode].toInt() and 0xFFFF},${cpu[probeCode + 1].toInt() and 0xFFFF}," +
                        "${cpu[probeCode + 2].toInt() and 0xFFFF})"
                )
                Log.e("Lq032StageParity", RecordDevelopParityDiagnostics.format(stats))
                if (stats.meanAbs > RecordDevelopParityGatePolicy.meanTolerance ||
                    stats.maxAbs > RecordDevelopParityGatePolicy.maxTolerance ||
                    stats.p999 > RecordDevelopParityGatePolicy.p999Tolerance
                ) {
                    failures += RecordDevelopParityDiagnostics.format(stats)
                }
            } finally {
                accelerator.close()
            }
        }
        assertTrue("strict wide-scene stage parity failures: ${failures.joinToString(" | ")}", failures.isEmpty())
    }

    @Test
    fun nativeGpuAcceptedForSdrShadowBlueFilmPrintDevelop() {
        // REGRESSION GUARD for the blue-shadow Δ35 divergence. On a near-black, blue-swept frame the old
        // analytic shader path drove shadow blue to 0 while the CPU baked-LUT held a positive floor (~35
        // codes on channel 2), so the first-frame parity gate REJECTED and the develop fell back to CPU.
        // With the shader tetrahedral-sampling the SAME baked film LUT, GPU must match CPU here so the gate
        // ACCEPTS and SDR stays 100% on the GPU. Diagnostics on ⇒ the residual is logged under
        // "RecordDevelopConvert" for the on-device A/B read.
        RecordDevelopParityDiagnosticsFlag.set(true)
        RecordDevelopGpuAccelerationFlag.setEnabled(true)
        val width = 64
        val height = 32
        val audit = convert(
            width = width,
            height = height,
            frames = shadowBlueFrames(width, height),
            sidecar = null,
            filmPrintStrength = 1f
        )
        assertTrue(
            "SDR shadow-blue film-print GPU parity gate should ACCEPT (blue Δ35 closed via baked-LUT sampling)",
            audit.gpuAccelerated
        )
    }

    @Test
    fun nativeGpuAcceptedForNdrGenuineHdrDevelop() {
        RecordDevelopParityDiagnosticsFlag.set(true)
        RecordDevelopGpuAccelerationFlag.setEnabled(true)
        val width = 16
        val height = 8
        val sidecar = VideoDevelopColorSidecar.VideoDevelopColorMetadata(
            width = width,
            height = height,
            storageBitDepth = 10,
            fps = 30,
            whiteBalanceR = 1f,
            whiteBalanceGr = 1f,
            whiteBalanceGb = 1f,
            whiteBalanceB = 1f,
            workingRgbToXyz = SnceIlluminantEstimator.REC2020_TO_XYZ.copyOf(),
            adaptingLuminance = VideoDevelopColorPipeline.DEFAULT_SCENE_LA,
            cctKelvin = 6500f,
            tint = 0f,
            colorSpace = DevelopColorSpace.REC2020,
            colorTransfer = DevelopColorTransfer.HLG,
            colorProfileTag = "NDR10+"
        )
        val audit = convert(
            width = width,
            height = height,
            frames = frames(width, height),
            sidecar = sidecar
        )
        assertTrue("NDR record-develop GPU parity gate should accept native Vulkan", audit.gpuAccelerated)
    }

    @Test
    fun nativePackedR210MatchesNativeCodesPlusKotlinPack() {
        RecordDevelopGpuAccelerationFlag.setEnabled(true)
        val width = 16
        val height = 8
        val payload = patternedP010(width, height, frameIndex = 2)
        val accelerator = NativeRecordDevelopGpuAccelerator(
            RecordDevelopGpuContext(
                format = RecordDevelopDecodedFormat(width, height, 30),
                target = DevelopTarget.SDR_REC709,
                decodeSpace = DevelopColorSpace.REC709,
                decodeTransfer = DevelopColorTransfer.SDR,
                outputTransfer = OutputTransfer.SDR_REC709_OETF,
                snceAppearanceStrength = 1f,
                filmPrintStrength = 1f,
                warmSkinRedTameStrength = 0f,
                acesToneMap = false,
                semanticClass = com.neoproc.neoproccamx.snce.SnceNeuralEnhancement.SemanticClass.OTHER,
                clampOutput = true,
                ndrGenuineHdr = false,
                diffuseWhiteNits = 203f,
                widenToRec2020 = false,
                workingRgbToXyz = null,
                sceneLuminanceLA = VideoDevelopColorPipeline.DEFAULT_SCENE_LA
            )
        )
        val codes = accelerator.developFrameToCodes(payload, width, height)
        assertTrue("native code path should be available", codes != null)
        val packed = accelerator.developFrameToPackedPayload(
            payload,
            width,
            height,
            RecordDevelopOutputFormat.R210_444
        )
        assertTrue("native packed path should be available", packed != null)
        assertArrayEquals(
            "native packed r210 must match old code path exactly",
            RawTenBitPacker.packR210(codes!!, width, height),
            packed
        )
        accelerator.close()
    }

    @Test
    fun nativeGpuEnhanceLaneMatchesCpuChromaLaneWithinTolerance() {
        // Step 4: the GPU chroma enhance lane (temporal + chroma-NR + deadband + spike + dither) must
        // reproduce the CPU Npv10ExportEnhancementPolicy.enhanceP010 chroma passes to fp-noise. A profile
        // with detail gains = 0 and calibration strength = 0 makes the CPU enhanceP010 run ONLY the five
        // ported passes (luma-detail / calibration / SNCE are no-ops), so the two are directly comparable.
        RecordDevelopEnhanceLaneGpuFlag.set(true)
        val width = 64
        val height = 32
        val profile = Npv10ExportEnhancementProfile.DEFAULT
            .withoutAdditiveDetail()
            .copy(ditherAmplitude = 0.5, temporalLumaBlend = 0.7, temporalChromaBlend = 0.7)
        val current = shadowBlueP010(width, height, 1)
        val previous = shadowBlueP010(width, height, 0)

        assertTrue(
            "enhance-lane GPU pipeline should be available on device: ${RecordDevelopEnhanceLaneGpuBridge.unavailableReason()}",
            RecordDevelopEnhanceLaneGpuBridge.isAvailable()
        )
        val cpu = Npv10ExportEnhancementPolicy.enhanceP010(
            payload = current,
            width = width,
            height = height,
            previousP010Payload = previous,
            profile = profile,
            gradeLut = null,
            gradeLutSize = 0,
            saturation = 1f,
            warmth = 0f
        )
        val gpu = RecordDevelopEnhanceLaneGpuBridge.enhanceLaneP010(
            payload = current,
            previousP010Payload = previous,
            width = width,
            height = height,
            uniforms = Npv10ExportEnhancementPolicy.enhanceLaneGpuUniforms(profile)
        )
        assertTrue("GPU enhance-lane returned a payload of matching size", gpu != null && gpu.size == cpu.size)

        fun codes(p010: ByteArray): ShortArray {
            val out = ShortArray(p010.size / 2)
            var b = 0
            for (i in out.indices) {
                val v = (p010[b].toInt() and 0xFF) or ((p010[b + 1].toInt() and 0xFF) shl 8)
                out[i] = ((v ushr 6) and 0x3FF).toShort()
                b += 2
            }
            return out
        }
        val stats = RecordDevelopParityDiagnostics.compareCodes(codes(gpu!!), codes(cpu), "ENH_SDR", width)!!
        Log.i("EnhanceLaneParity", RecordDevelopParityDiagnostics.format(stats))
        assertTrue(
            "enhance-lane maxAbs within develop tolerance: ${RecordDevelopParityDiagnostics.format(stats)}",
            stats.maxAbs <= RecordDevelopParityGatePolicy.maxTolerance &&
                stats.meanAbs <= RecordDevelopParityGatePolicy.meanTolerance &&
                stats.p999 <= RecordDevelopParityGatePolicy.p999Tolerance
        )
    }

    @Test
    fun hybridCpuLaneTwoLaneConvertCompletes() {
        // Concurrent CPU+GPU hybrid: force the two-lane path (tiny frames pass the heap guard anyway) and
        // convert a multi-frame clip. Frame 0 always develops on the GPU lane and the rest interleave CPU/GPU
        // per the share, so the run exercises both lanes concurrently. The assertion is that it COMPLETES
        // (no deadlock on the gpuPermit/cpuLanePermit, no crash) and the clip is GPU-accelerated.
        val previousCpuShareBps = RecordDevelopHybridCpuLanePolicy.cpuShareBps
        RecordDevelopGpuAccelerationFlag.setEnabled(true)
        // A non-zero share is a required admission condition. Fifty percent guarantees that this
        // short fixture routes frame 0 to GPU and frame 1 to CPU instead of merely launching two idle lanes.
        RecordDevelopHybridCpuLanePolicy.setCpuShareBps(5_000)
        RecordDevelopHybridCpuLaneFlag.set(true)
        RecordDevelopHybridCpuLaneForceFlag.set(true)
        try {
            val width = 64
            val height = 32
            val audit = convert(
                width = width,
                height = height,
                frames = shadowBlueFrames(width, height),
                sidecar = null,
                filmPrintStrength = 1f
            )
            assertTrue("hybrid two-lane convert completed with a GPU-developed frame 0", audit.gpuAccelerated)
            assertTrue("hybrid conversion launched both guarded develop workers", audit.workerCount >= 2)
        } finally {
            RecordDevelopHybridCpuLaneForceFlag.set(false)
            RecordDevelopHybridCpuLaneFlag.set(false)
            RecordDevelopHybridCpuLanePolicy.setCpuShareBps(previousCpuShareBps)
        }
    }

    private fun convert(
        width: Int,
        height: Int,
        frames: List<RecordDevelopP010Frame>,
        sidecar: VideoDevelopColorSidecar.VideoDevelopColorMetadata?,
        filmPrintStrength: Float = 0f,
        snceAppearanceStrength: Float = 1f
    ): RecordDevelopConversionAudit {
        val dir = ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir
        val master = temp("recdev_gpu_master", ".mov", dir).apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val output = temp("recdev_gpu_output", ".mov", dir)
        return RecordDevelopMovConverter.convertWithAudit(
            master = master,
            output = output,
            sidecar = sidecar,
            options = RecordDevelopConversionOptions(
                snceAppearanceStrength = snceAppearanceStrength,
                filmPrintStrength = filmPrintStrength,
                outputFormat = RecordDevelopOutputFormat.R210_444,
                enhance = RecordDevelopEnhanceOptions.DISABLED
            ),
            sourceKind = if (sidecar == null) RecordDevelopMasterKind.SDR else RecordDevelopMasterKind.NDR,
            readerFactory = {
                InMemoryP010FrameReader(frames, RecordDevelopDecodedFormat(width, height, 30))
            },
            gpuAcceleratorFactory = { NativeRecordDevelopGpuAccelerator(it) }
        )
    }

    private fun frames(width: Int, height: Int): List<RecordDevelopP010Frame> =
        (0 until 4).map { index ->
            RecordDevelopP010Frame(
                index = index.toLong(),
                presentationTimeUs = index * 33_333L,
                width = width,
                height = height,
                p010 = patternedP010(width, height, index)
            )
        }

    private fun convertLq032WideScene(
        snceAppearanceStrength: Float,
        frameCount: Int = 2
    ): RecordDevelopConversionAudit {
        val width = 320
        val height = 180
        return convert(
            width = width,
            height = height,
            frames = (0 until frameCount).map { index ->
                RecordDevelopP010Frame(
                    index = index.toLong(),
                    presentationTimeUs = index * 40_000L,
                    width = width,
                    height = height,
                    p010 = lq032PatternedP010(width, height, index)
                )
            },
            sidecar = null,
            snceAppearanceStrength = snceAppearanceStrength
        )
    }

    private fun shadowBlueFrames(width: Int, height: Int): List<RecordDevelopP010Frame> =
        (0 until 4).map { index ->
            RecordDevelopP010Frame(
                index = index.toLong(),
                presentationTimeUs = index * 33_333L,
                width = width,
                height = height,
                p010 = shadowBlueP010(width, height, index)
            )
        }

    // Near-black luma (just above the 64 floor) with a neutral->blue Cb sweep and near-neutral Cr, so the
    // decoded pixels populate the shadow-blue region where the analytic film-print drove blue to 0 but the
    // baked LUT holds a small positive floor (the ~35-code channel-2 divergence).
    private fun shadowBlueP010(width: Int, height: Int, frameIndex: Int): ByteArray {
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val lumaBytes = width * height * 2
        val out = ByteArray(lumaBytes + chromaWidth * chromaHeight * 4)
        fun put(offset: Int, code: Int) {
            val value = code.coerceIn(64, 960) shl 6
            out[offset] = (value and 0xFF).toByte()
            out[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                // Deep shadows: luma stays ~66..150 across the frame.
                put((y * width + x) * 2, 66 + (x % 20) * 2 + (y % 6) + frameIndex)
            }
        }
        var offset = lumaBytes
        for (y in 0 until chromaHeight) {
            for (x in 0 until chromaWidth) {
                put(offset, 512 + x * 4 + frameIndex) // Cb: neutral -> blue
                put(offset + 2, 512 - (y % 4) * 2)    // Cr: ~neutral (blue dominates)
                offset += 4
            }
        }
        return out
    }

    private fun patternedP010(width: Int, height: Int, frameIndex: Int): ByteArray {
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val lumaBytes = width * height * 2
        val out = ByteArray(lumaBytes + chromaWidth * chromaHeight * 4)
        fun put(offset: Int, code: Int) {
            val value = code.coerceIn(64, 960) shl 6
            out[offset] = (value and 0xFF).toByte()
            out[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                put((y * width + x) * 2, 220 + frameIndex * 35 + x * 11 + y * 7)
            }
        }
        var offset = lumaBytes
        for (y in 0 until chromaHeight) {
            for (x in 0 until chromaWidth) {
                put(offset, 480 + frameIndex * 3 + x * 5 - y * 2)
                put(offset + 2, 530 - frameIndex * 4 + y * 6 - x * 3)
                offset += 4
            }
        }
        return out
    }

    private fun lq032PatternedP010(width: Int, height: Int, frameIndex: Int): ByteArray {
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val lumaBytes = width * height * 2
        val out = ByteArray(lumaBytes + chromaWidth * chromaHeight * 4)
        fun put(offset: Int, code: Int) {
            val value = code.coerceIn(64, 960) shl 6
            out[offset] = value.toByte()
            out[offset + 1] = (value ushr 8).toByte()
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                put((y * width + x) * 2, 96 + ((x * 7 + y * 5 + frameIndex * 19) % 840))
            }
        }
        var offset = lumaBytes
        for (y in 0 until chromaHeight) {
            for (x in 0 until chromaWidth) {
                put(offset, 430 + ((x * 5 + frameIndex * 7) % 165))
                put(offset + 2, 440 + ((y * 7 + frameIndex * 11) % 145))
                offset += 4
            }
        }
        return out
    }

    private fun directWideCpuCodes(
        payload: ByteArray,
        width: Int,
        height: Int,
        snceAppearanceStrength: Float,
        warmSkinRedTameStrength: Float
    ): ShortArray {
        val pipeline = VideoDevelopColorPipeline(
            options = VideoDevelopColorOptions(
                target = DevelopTarget.SDR_REC709,
                snceAppearanceStrength = snceAppearanceStrength,
                filmPrintStrength = 0f,
                acesToneMap = false,
                semanticClass = com.neoproc.neoproccamx.snce.SnceNeuralEnhancement.SemanticClass.OTHER,
                clampOutput = true,
                warmSkinRedTameStrength = warmSkinRedTameStrength
            ),
            sceneLuminanceLA = VideoDevelopColorPipeline.DEFAULT_SCENE_LA
        )
        val out = ShortArray(width * height * 3)
        val encoded = IntArray(3)
        var dst = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val linear = RecordDevelopP010ColorIngress.decodeToLinearRgb(
                    yCode = RecordDevelopP010ColorIngress.readLumaCode(payload, width, height, x, y),
                    cbCode = RecordDevelopP010ColorIngress.readChromaCode(payload, width, height, x, y, 0),
                    crCode = RecordDevelopP010ColorIngress.readChromaCode(payload, width, height, x, y, 1),
                    space = DevelopColorSpace.REC709,
                    transfer = DevelopColorTransfer.SDR
                )
                val developed = pipeline.developPixel(linear[0], linear[1], linear[2])
                RecordDevelopP010ColorIngress.encodeLinearRgbInto(
                    developed[0],
                    developed[1],
                    developed[2],
                    OutputTransfer.SDR_REC709_OETF,
                    203f,
                    encoded
                )
                out[dst++] = encoded[0].toShort()
                out[dst++] = encoded[1].toShort()
                out[dst++] = encoded[2].toShort()
            }
        }
        return out
    }

    private fun temp(prefix: String, suffix: String, dir: File): File =
        File.createTempFile(prefix, suffix, dir).also { tempFiles += it }
}
