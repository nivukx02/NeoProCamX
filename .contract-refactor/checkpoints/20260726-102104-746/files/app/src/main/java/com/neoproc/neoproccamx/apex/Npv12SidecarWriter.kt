package com.neoproc.neoproccamx.apex

import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * TASK 8 — NPV12 SIDECAR METADATA WRITER
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Writes per-frame NPV12 metadata sidecar alongside MP4/MOV output.
 * Format: NDJSON (newline-delimited JSON) — one JSON object per frame.
 *
 * MANDATORY TRUTHFUL FIELDS:
 *   vendorCertifiedHdr10Plus  = false  (ALWAYS)
 *   vendorCertifiedDolbyVision = false (ALWAYS)
 *
 * Thread Safety: Single writer thread. Buffer flushed every 30 frames.
 */
class Npv12SidecarWriter {

    companion object {
        private const val TAG = "Npv12SidecarWriter"
        private const val FLUSH_INTERVAL = 30
    }

    private var writer: BufferedWriter? = null
    private val isOpen = AtomicBoolean(false)
    private val frameCount = AtomicInteger(0)
    private var sidecarPath: String = ""

    /**
     * Open sidecar file for writing.
     * @param videoFilePath Path to the MP4/MOV being recorded.
     *                      Sidecar will be saved as <path>.npv12.json
     */
    fun open(videoFilePath: String) {
        sidecarPath = "$videoFilePath.npv12.json"
        try {
            writer = BufferedWriter(FileWriter(File(sidecarPath), false))
            isOpen.set(true)
            frameCount.set(0)
            NeoLogger.i(TAG, "NPV12 sidecar opened: $sidecarPath")
        } catch (e: Exception) {
            NeoLogger.e(TAG, "Failed to open sidecar: $sidecarPath", e)
        }
    }

    /**
     * Write per-frame metadata. Called once per encoded frame.
     *
     * @param metrics Scene analysis results for this frame
     * @param params  Profile parameters computed for this frame
     * @param detailMetrics Optional detail system metrics
     * @param qualityMetrics Optional quality measurement metrics
     */
    fun writeFrame(
        metrics: SceneMetrics,
        params: ApexProfileParams,
        detailMetrics: DetailMetrics? = null,
        qualityMetrics: QualityFrameMetrics? = null
    ) {
        if (!isOpen.get()) return

        try {
            val json = JSONObject().apply {
                // ── Identity ──────────────────────────────────────────────
                put("frameIndex", frameCount.get())
                put("frameTimestampNs", metrics.frameTimestampNs)
                put("selectedProfile", params.mode.displayName)
                put("profileFamily", "NEOPROCAMX_APEX")
                put("profileVersion", "1.0.0")
                put("internalColorSpace", "ICtCp")
                put("internalMathPrecision", "FP32_SOLVE_FP16_STORAGE")
                put("activeTransport", "BT.2100_PQ_MAIN10")
                put("transfer", "ST2084/PQ")
                put("primaries", "BT.2020")
                put("matrix", "BT.2020-NCL")
                put("encodedBitDepth", 10)
                put("masterBitDepth", 12)

                // ── TRUTHFUL CERTIFICATION (MANDATORY) ────────────────────
                put("dynamicMetadataWritten", "NEO_APEX_SIDECAR")
                put("vendorCertifiedHdr10Plus", false)
                put("vendorCertifiedDolbyVision", false)

                // ── Scene Analysis ────────────────────────────────────────
                put("dynamicRangeScore", metrics.dynamicRangeScore.toDouble())
                put("shadowNeed", metrics.shadowNeed.toDouble())
                put("shadowRatio", metrics.shadowRatio.toDouble())
                put("highlightRatio", metrics.highlightRatio.toDouble())
                put("noiseRisk", metrics.noiseRisk.toDouble())
                put("chromaNoiseRisk", metrics.chromaNoiseRisk.toDouble())
                put("motionScore", metrics.motionScore.toDouble())
                put("exposureStability", metrics.exposureStability.toDouble())
                put("localContrastScore", metrics.localContrastScore.toDouble())
                put("histogramSpread", metrics.histogramSpread.toDouble())
                put("clipRisk", metrics.clipRisk.toDouble())
                put("perceptualStopEstimate", metrics.perceptualStopEstimate.toDouble())
                put("p01Nits", metrics.p01Nits.toDouble())
                put("p50Nits", metrics.p50Nits.toDouble())
                put("p95Nits", metrics.p95Nits.toDouble())
                put("p99Nits", metrics.p99Nits.toDouble())
                put("maxCllNits", metrics.maxCllNits.toDouble())
                put("maxFallNits", metrics.maxFallNits.toDouble())
                put("sceneWhite", metrics.sceneWhite.toDouble())
                put("iso", metrics.iso)
                put("exposureTimeNs", metrics.exposureTimeNs)

                // ── Profile Parameters ────────────────────────────────────
                put("sourcePeakNits", params.sourcePeakNits.toDouble())
                put("targetPeakNits", params.targetPeakNits.toDouble())
                put("kneeRatio", params.kneeRatio.toDouble())
                put("shoulderStrength", params.shoulderStrength.toDouble())
                put("shadowLift", params.shadowLift.toDouble())
                put("saturationPreservation", params.saturationPreservation.toDouble())
                put("colorVolumeCompression", params.colorVolumeCompression.toDouble())
                put("toeStrength", params.toeStrength.toDouble())
                put("midtoneSlope", params.midtoneSlope.toDouble())
                put("localToneStrength", params.localToneStrength.toDouble())
                put("motionDetailDamping", params.motionDetailDamping.toDouble())
                put("noiseDetailGate", params.noiseDetailGate.toDouble())
                put("temporalDamping", params.temporalDamping.toDouble())

                // ── Detail System ─────────────────────────────────────────
                if (detailMetrics != null) {
                    put("opticalSharpScore", detailMetrics.opticalSharpScore.toDouble())
                    put("detailBandD1Mean", detailMetrics.d1Mean.toDouble())
                    put("detailBandD2Mean", detailMetrics.d2Mean.toDouble())
                    put("detailBandD3Mean", detailMetrics.d3Mean.toDouble())
                    put("haloSuppressionApplied", detailMetrics.haloSuppressionApplied)
                    put("noiseMaskActive", detailMetrics.noiseMaskActive)
                    put("pyramidMode", params.mode.name)
                    put("gaussianSigma1", 1.2)
                    put("gaussianSigma2", 2.4)
                    put("gaussianSigma3", 4.8)
                }

                // ── Quality Metrics ───────────────────────────────────────
                if (qualityMetrics != null) {
                    put("snrEstimate", qualityMetrics.snrDb.toDouble())
                    put("highlightClipRate", qualityMetrics.highlightClipRate.toDouble())
                    put("frameDropCount", qualityMetrics.frameDropCount)
                    put("processingTimeMs", qualityMetrics.processingTimeMs.toDouble())
                }
            }

            writer?.apply {
                write(json.toString())
                newLine()
            }

            val count = frameCount.incrementAndGet()
            if (count % FLUSH_INTERVAL == 0) {
                writer?.flush()
            }
        } catch (e: Exception) {
            NeoLogger.w(TAG, "Failed to write sidecar frame", e)
        }
    }

    /** Close and flush the sidecar file. */
    fun close() {
        if (!isOpen.compareAndSet(true, false)) return
        try {
            writer?.flush()
            writer?.close()
            writer = null
            NeoLogger.i(TAG, "NPV12 sidecar closed: $sidecarPath (${ frameCount.get()} frames)")
        } catch (e: Exception) {
            NeoLogger.w(TAG, "Error closing sidecar", e)
        }
    }

    /** Detail system per-frame metrics. */
    data class DetailMetrics(
        val opticalSharpScore: Float = 0f,
        val d1Mean: Float = 0f,
        val d2Mean: Float = 0f,
        val d3Mean: Float = 0f,
        val haloSuppressionApplied: Boolean = false,
        val noiseMaskActive: Boolean = false
    )

    /** Quality measurement per-frame metrics. */
    data class QualityFrameMetrics(
        val snrDb: Float = 0f,
        val highlightClipRate: Float = 0f,
        val frameDropCount: Int = 0,
        val processingTimeMs: Float = 0f
    )
}
