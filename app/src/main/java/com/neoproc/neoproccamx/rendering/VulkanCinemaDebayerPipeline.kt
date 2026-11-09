package com.neoproc.neoproccamx.rendering

import android.hardware.HardwareBuffer
import android.media.Image
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.neoproc.neoproccamx.color.NeoGamutApexPipeline
import com.neoproc.neoproccamx.color.NeoGamutCat16Math
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * VULKAN 12-BIT RAW PIPELINE — CINEMA DEBAYER INTEGRATION
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * Manages Vulkan-accelerated 12-bit RAW buffer processing via the Cinema-Debayer
 * compute shader. Handles direct RAW_SENSOR buffer submission, debayer dispatch,
 * and highlight latitude recovery bypassing default Android tone-mapping.
 *
 * Thread safety: All mutable state is @Volatile for lock-free reads.
 * The debayer pipeline runs on the Vulkan compute queue.
 *
 * Integration: VulkanRuntimeManager delegates RAW frames to this pipeline
 * when the cinema debayer mode is active.
 */

/**
 * CP11 / s1-f09 — the demosaic's constant second leg `M_XYZ_TO_ACESCG`: XYZ(D50) → ACEScg(AP1), the
 * project's own [NeoGamutApexPipeline.xyzD50ToAcesAp1Matrix] chain (CAT16 D50→D55 → Neo Wide Gamut →
 * AP1), row-major 3×3. It is D50-matched to the forward sensor→XYZ(D50) leg, so the two legs compose
 * into the exact CP11 forward product the CP-Q4 gate validates — NOT the published D60 ACES matrix,
 * which would mix white points against a D50 forward matrix.
 */
internal val XYZ_D50_TO_ACESCG: FloatArray = NeoGamutApexPipeline.xyzD50ToAcesAp1Matrix()

/**
 * Default sensor → XYZ(D50) matrix when the camera advertises no public SENSOR_FORWARD_MATRIX: the
 * numeric inverse of [XYZ_D50_TO_ACESCG], so the combined sensor→ACEScg transform is exactly identity
 * and a neutral mosaic stays achromatic in ACEScg — a safe, honest placeholder, byte-identical to the
 * pre-s1-f09 default, until the real per-camera forward matrix is wired in from metadata (RawVideoManager).
 */
internal val DEFAULT_SENSOR_TO_XYZ: FloatArray = NeoGamutCat16Math.invert3x3(XYZ_D50_TO_ACESCG)

data class RawBufferDescriptor(
    val width: Int,
    val height: Int,
    val stride: Int,
    val blackLevel: Int = 64,
    /** Semantic R, Gr, Gb, B black offsets; [blackLevel] remains the sidecar-compatible mean. */
    val blackLevelRggb: FloatArray = floatArrayOf(
        blackLevel.toFloat(),
        blackLevel.toFloat(),
        blackLevel.toFloat(),
        blackLevel.toFloat()
    ),
    val whiteLevel: Int = 4095,
    val bayerPattern: BayerPattern = BayerPattern.RGGB,
    val whiteBalanceR: Float = 1.0f,
    val whiteBalanceGr: Float = 1.0f,
    val whiteBalanceGb: Float = 1.0f,
    val whiteBalanceB: Float = 1.0f,
    val exposureTimeNs: Long = 0L,
    val analogGain: Float = 1.0f,
    val digitalGain: Float = 1.0f,
    val noiseS: Float = 0.0f,
    val noiseO: Float = 0.0f,
    /**
     * Row-major 3×3 sensor→XYZ(D50) forward matrix (CP11 / s1-f09 — the mired-interpolated DNG
     * SENSOR_FORWARD_MATRIX). Defaults to [DEFAULT_SENSOR_TO_XYZ] (identity-safe) so an unwired/
     * unsupported sensor keeps a neutral demosaic; RawVideoManager overrides it from camera metadata.
     */
    val sensorToXyz: FloatArray = DEFAULT_SENSOR_TO_XYZ,
    /** Preview-only Camera2 lens-shading correction, row-major 3x3 of RGGB vec4 gains. */
    val lensShadingGridRggb: FloatArray = RawLensShadingGridPolicy.identityGrid(),
    /** False keeps the demosaic bit-identical to the legacy path when metadata is unavailable. */
    val lensShadingCorrectionEnabled: Boolean = false,
    /**
     * Immutable presentation/record lineage proof generated only after RAW colour stabilization.
     * This is sideband metadata; it never changes the project-owned NDR/NIVUKX pixel equations.
     */
    val projectOwnedNdrColorProof: ProjectOwnedNdrColorProof? = null
)

/**
 * Bayer pattern enumeration matching sensor CFA layouts.
 */
enum class BayerPattern(val nativeValue: Int) {
    RGGB(0),
    GRBG(1),
    GBRG(2),
    BGGR(3);

    companion object {
        fun fromCameraCharacteristic(value: Int): BayerPattern = when (value) {
            0 -> RGGB
            1 -> GRBG
            2 -> GBRG
            3 -> BGGR
            else -> RGGB
        }
    }
}

/**
 * Configuration for highlight latitude recovery in the debayer pipeline.
 */
data class HighlightRecoveryConfig(
    /** Threshold above which highlight recovery kicks in (0.0-1.0, default 0.92) */
    val threshold: Float = 0.92f,
    /** Recovery strength (0.0 = off, 1.0 = maximum, default 0.85) */
    val strength: Float = 0.85f,
    /** Enable shoulder compression for cinema-style roll-off */
    val shoulderCompression: Boolean = true
) {
    internal fun normalized(): HighlightRecoveryConfig = copy(
        threshold = threshold.takeIf(Float::isFinite)?.coerceIn(0.50f, 1.0f) ?: 1.0f,
        strength = strength.takeIf(Float::isFinite)?.coerceIn(0.0f, 1.0f) ?: 0.0f
    )

    /** std140 vec4: threshold, strength, shoulder-enable, recovery-enable. */
    internal fun shaderParameters(): FloatArray {
        val safe = normalized()
        return floatArrayOf(
            safe.threshold,
            safe.strength,
            if (safe.shoulderCompression) 1.0f else 0.0f,
            if (safe.strength > 0.0f && safe.threshold < 1.0f) 1.0f else 0.0f
        )
    }

    companion object {
        /** Default config for cinema-oriented highlight latitude preservation */
        val CINEMA = HighlightRecoveryConfig(
            threshold = 0.90f,
            strength = 0.92f,
            shoulderCompression = true
        )

        /** Neutral config — minimal recovery */
        val NEUTRAL = HighlightRecoveryConfig(
            threshold = 0.96f,
            strength = 0.3f,
            shoulderCompression = false
        )

        /** Disabled — no highlight recovery */
        val OFF = HighlightRecoveryConfig(
            threshold = 1.0f,
            strength = 0.0f,
            shoulderCompression = false
        )
    }
}

/**
 * Statistics from the cinema debayer pipeline.
 */
data class CinemaDebayerStats(
    val debayeredFrames: Long = 0L,
    val failedDebayers: Long = 0L,
    val lastDebayerTimeMs: Long = 0L,
    val averageDebayerTimeMs: Float = 0f,
    val pipelineActive: Boolean = false,
    val lastBufferWidth: Int = 0,
    val lastBufferHeight: Int = 0,
    val highlightRecoveryEnabled: Boolean = false,
    val rawMonitorPipeline: String = "RAW_MONITOR_CINEMA_DEBAYER_PREVIEW_ONLY",
    val destructiveToRawMaster: Boolean = false,
    val sensorMetadataRequired: Boolean = true
)

/**
 * Manages the Vulkan Cinema-Debayer compute pipeline for 12-bit RAW processing.
 */
class VulkanCinemaDebayerPipeline {

    companion object {
        private const val TAG = "VulkanCinemaDebayer"

        /** Maximum RAW buffer dimension supported by the debayer pipeline */
        private const val MAX_RAW_DIMENSION = 9248 // sensor-native max edge guard

        /** Minimum RAW buffer dimension to avoid degenerate cases */
        private const val MIN_RAW_DIMENSION = 64
    }

    private val initialized = AtomicBoolean(false)
    private val pipelineActive = AtomicBoolean(false)

    @Volatile private var stats = CinemaDebayerStats()
    @Volatile private var highlightConfig = HighlightRecoveryConfig.CINEMA
    @Volatile private var totalDebayerTimeMs = 0L
    @Volatile private var loggedFirstDispatchDiag = false

    /** Bridge captured at [initialize] so [setActive] can toggle the native RAW-preview demosaic gate. */
    @Volatile private var attachedBridge: VulkanBridge? = null

    /**
     * Initialize the cinema debayer compute pipeline.
     * Must be called after VulkanBridge is initialized.
     */
    fun initialize(bridge: VulkanBridge): Boolean {
        if (!initialized.compareAndSet(false, true)) return true
        return runCatching {
            val supported = bridge.isSupported()
            if (supported) {
                attachedBridge = bridge
                Log.i(TAG, "Cinema-Debayer pipeline initialized (12-bit RAW support active)")
            } else {
                // Keep JVM state consistent: an unsupported bridge must not leave the pipeline 'initialized'
                // (else setActive could mark it active while the native gate is never set).
                initialized.set(false)
            }
            supported
        }.onFailure { throwable ->
            Log.w(TAG, "Cinema-Debayer pipeline initialization failed", throwable)
            initialized.set(false)
        }.getOrDefault(false)
    }

    /**
     * Set highlight recovery configuration.
     */
    fun setHighlightRecovery(config: HighlightRecoveryConfig) {
        val safe = config.normalized()
        highlightConfig = safe
        stats = stats.copy(highlightRecoveryEnabled = safe.strength > 0f && safe.threshold < 1f)
    }

    /**
     * Enable or disable the cinema debayer pipeline. Activation is gated to RAW+VIDEO by the caller
     * (VulkanRuntimeManager.setCinemaDebayerActive); flipping it also toggles the native RAW-preview
     * demosaic gate, so when inactive the native dispatch is unreachable and the shipping YUV preview
     * path stays byte-identical (no-downgrade).
     */
    fun setActive(active: Boolean) {
        val effective = active && initialized.get()
        pipelineActive.set(effective)
        runCatching { attachedBridge?.setRawPreviewDemosaicEnabled(effective) }
            .onFailure { Log.w(TAG, "Failed to toggle native RAW-preview demosaic gate", it) }
        stats = stats.copy(pipelineActive = pipelineActive.get())
    }

    fun isActive(): Boolean = pipelineActive.get()

    private fun shaderBlackLevels(descriptor: RawBufferDescriptor): FloatArray {
        val levels = descriptor.blackLevelRggb
        if (levels.size == 4 && levels.all { it.isFinite() && it >= 0f && it < descriptor.whiteLevel }) {
            return levels.copyOf()
        }
        return FloatArray(4) { descriptor.blackLevel.toFloat() }
    }

    private fun shaderWhiteBalance(descriptor: RawBufferDescriptor): FloatArray {
        val gains = floatArrayOf(
            descriptor.whiteBalanceR,
            descriptor.whiteBalanceGr,
            descriptor.whiteBalanceGb,
            descriptor.whiteBalanceB
        )
        return if (gains.all { it.isFinite() && it in 0.20f..12.0f }) gains else FloatArray(4) { 1f }
    }

    private fun shaderSensorToXyz(descriptor: RawBufferDescriptor): FloatArray {
        val matrix = descriptor.sensorToXyz
        if (matrix.size != 9 || matrix.any { !it.isFinite() || abs(it) > 8f }) {
            return DEFAULT_SENSOR_TO_XYZ.copyOf()
        }
        val determinant =
            matrix[0] * (matrix[4] * matrix[8] - matrix[5] * matrix[7]) -
                matrix[1] * (matrix[3] * matrix[8] - matrix[5] * matrix[6]) +
                matrix[2] * (matrix[3] * matrix[7] - matrix[4] * matrix[6])
        return if (determinant.isFinite() && abs(determinant) >= 1.0e-5f) {
            matrix.copyOf()
        } else {
            DEFAULT_SENSOR_TO_XYZ.copyOf()
        }
    }

    /**
     * Submit a 12-bit RAW buffer for cinema debayering (s1-f07 / CP10c).
     *
     * The buffer is dispatched through the **demosaic_vulkan.comp** compute shader (the demosaic
     * CP10b compiled into the native build), NOT the legacy cinema_debayer.comp:
     * 1. Per-CFA black-level subtraction + WB-before-demosaic (CP14 ordering)
     * 2. Hard-gradient edge-directed green reconstruction (CP14; Hamilton-Adams-style)
     * 3. sensor→XYZ→ACEScg via the CP11 forward matrices (s1-f09) into a linear rgba16f image
     * (Highlight reconstruction is handled downstream, not in this demosaic.)
     *
     * No-downgrade: returns false when the native gate is off / the device cannot import the mosaic as
     * a storage R16_UINT image, so the caller falls back to the standard YUV preview path unchanged.
     *
     * @param hardwareBuffer The RAW sensor HardwareBuffer
     * @param descriptor Buffer metadata (dimensions, black/white levels, Bayer pattern, sensor CCM)
     * @param bridge VulkanBridge for native dispatch
     * @return true if the demosaic was dispatched successfully
     */
    fun submitRawBuffer(
        hardwareBuffer: HardwareBuffer,
        descriptor: RawBufferDescriptor,
        bridge: VulkanBridge,
        completionWaitNs: Long = VulkanBridge.DEFAULT_RAW_DEMOSAIC_COMPLETION_WAIT_NS,
        recordSynchronousCompletion: Boolean = true
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (!pipelineActive.get()) return false
        if (!initialized.get()) return false

        // Validate buffer dimensions
        if (descriptor.width < MIN_RAW_DIMENSION || descriptor.height < MIN_RAW_DIMENSION) {
            Log.w(TAG, "RAW buffer too small: ${descriptor.width}x${descriptor.height}")
            return false
        }
        if (descriptor.width > MAX_RAW_DIMENSION || descriptor.height > MAX_RAW_DIMENSION) {
            Log.w(TAG, "RAW buffer exceeds max dimension: ${descriptor.width}x${descriptor.height}")
            return false
        }

        val startTime = SystemClock.elapsedRealtime()

        return try {
            // Pack the std140 RawDemosaicUniforms inputs. Per-CFA black/WB drive the WB-before-demosaic
            // ordering inside the shader; the CP11 forward matrices carry sensor→XYZ→ACEScg.
            val blackLevelRggb = shaderBlackLevels(descriptor)
            val whiteBalanceRggb = shaderWhiteBalance(descriptor)
            val sensorToXyz = shaderSensorToXyz(descriptor)
            val exposureMs = descriptor.exposureTimeNs.coerceAtLeast(0L).toFloat() / 1_000_000f
            // exposureThermal = (exposureMs, sensorTempC, referenceTempC, Q10); Q10=2.0 matches the
            // shader constant. The dark-current map is zero by default so thermal scaling stays inert.
            val exposureThermal = floatArrayOf(exposureMs, 25f, 25f, 2.0f)
            val highlightRecovery = highlightConfig.shaderParameters()

            // Dispatch demosaic_vulkan.comp. Returns false → caller YUV fallback (no-downgrade).
            val success = bridge.dispatchRawDemosaicPreview(
                hardwareBuffer = hardwareBuffer,
                width = descriptor.width,
                height = descriptor.height,
                bayerPattern = descriptor.bayerPattern.nativeValue,
                whiteLevel = descriptor.whiteLevel,
                blackLevelRggb = blackLevelRggb,
                whiteBalanceRggb = whiteBalanceRggb,
                exposureThermal = exposureThermal,
                highlightRecovery = highlightRecovery,
                sensorToXyz = sensorToXyz,
                xyzToAcescg = XYZ_D50_TO_ACESCG,
                lensShadingGridRggb = descriptor.lensShadingGridRggb,
                lensShadingCorrectionEnabled = descriptor.lensShadingCorrectionEnabled,
                completionWaitNs = completionWaitNs
            )

            if (!loggedFirstDispatchDiag) {
                loggedFirstDispatchDiag = true
                Log.i(TAG, "First dispatchRawDemosaicPreview -> $success " +
                    "(${descriptor.width}x${descriptor.height} bayer=${descriptor.bayerPattern} " +
                    "white=${descriptor.whiteLevel})")
            }

            val elapsed = SystemClock.elapsedRealtime() - startTime
            if (success && recordSynchronousCompletion) {
                recordCompletedDemosaic(descriptor.width, descriptor.height, elapsed)
            }

            success
        } catch (throwable: Throwable) {
            stats = stats.copy(failedDebayers = stats.failedDebayers + 1)
            Log.w(TAG, "Cinema-Debayer failed for ${descriptor.width}x${descriptor.height}", throwable)
            false
        }
    }

    /**
     * Submit a Camera2 RAW Image for cinema debayering.
     * Convenience wrapper that extracts HardwareBuffer from Image.
     */
    fun submitRawImage(
        image: Image,
        descriptor: RawBufferDescriptor,
        bridge: VulkanBridge,
        completionWaitNs: Long = VulkanBridge.DEFAULT_RAW_DEMOSAIC_COMPLETION_WAIT_NS,
        recordSynchronousCompletion: Boolean = true
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false

        val hardwareBuffer = runCatching { image.hardwareBuffer }.getOrNull() ?: return false
        val dispatched = try {
            submitRawBuffer(
                hardwareBuffer,
                descriptor,
                bridge,
                completionWaitNs,
                recordSynchronousCompletion
            )
        } finally {
            hardwareBuffer.close()
        }
        // Contract with the caller (RawVideoManager.onRawFrameAvailable): returning true means
        // this processor took ownership of the Image and must close it — the caller only closes
        // on false. A completed dispatch closes the Image here. A deferred dispatch returns false
        // after native retains its own AHardwareBuffer reference, so the caller can close the Image
        // without invalidating the in-flight GPU import or exhausting the RAW ImageReader.
        if (dispatched) runCatching { image.close() }
        return dispatched
    }

    /**
     * Record-time live-monitor submit (s1-f07 / CP10c): demosaic a CPU RAW mosaic COPY held in [rawBytes]
     * (row-strided uint16), NOT an [Image] — the caller already lifted the bytes off the camera RAW Image
     * and freed it, so this never holds a scarce RAW reader buffer away from the recorder. [decimate]
     * (2 = half-res) keeps the record-time monitor at ~1/4 the cost of the full-res idle path. No Image
     * ownership is taken (the caller owns [rawBytes]). Returns true iff the demosaic was dispatched.
     */
    fun submitRawBytes(
        rawBytes: java.nio.ByteBuffer,
        rowStrideBytes: Int,
        decimate: Int,
        descriptor: RawBufferDescriptor,
        bridge: VulkanBridge
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (!pipelineActive.get()) return false
        if (!initialized.get()) return false
        if (descriptor.width < MIN_RAW_DIMENSION || descriptor.height < MIN_RAW_DIMENSION) return false
        if (descriptor.width > MAX_RAW_DIMENSION || descriptor.height > MAX_RAW_DIMENSION) return false

        val startTime = SystemClock.elapsedRealtime()
        return try {
            val blackLevelRggb = shaderBlackLevels(descriptor)
            val whiteBalanceRggb = shaderWhiteBalance(descriptor)
            val sensorToXyz = shaderSensorToXyz(descriptor)
            val exposureMs = descriptor.exposureTimeNs.coerceAtLeast(0L).toFloat() / 1_000_000f
            val exposureThermal = floatArrayOf(exposureMs, 25f, 25f, 2.0f)
            val highlightRecovery = highlightConfig.shaderParameters()

            val success = bridge.dispatchRawDemosaicPreviewFromBytes(
                rawBytes = rawBytes,
                rowStrideBytes = rowStrideBytes,
                width = descriptor.width,
                height = descriptor.height,
                decimate = decimate,
                bayerPattern = descriptor.bayerPattern.nativeValue,
                whiteLevel = descriptor.whiteLevel,
                blackLevelRggb = blackLevelRggb,
                whiteBalanceRggb = whiteBalanceRggb,
                exposureThermal = exposureThermal,
                highlightRecovery = highlightRecovery,
                sensorToXyz = sensorToXyz,
                xyzToAcescg = XYZ_D50_TO_ACESCG,
                lensShadingGridRggb = descriptor.lensShadingGridRggb,
                lensShadingCorrectionEnabled = descriptor.lensShadingCorrectionEnabled
            )

            val elapsed = SystemClock.elapsedRealtime() - startTime
            if (success) recordCompletedDemosaic(descriptor.width, descriptor.height, elapsed)
            success
        } catch (throwable: Throwable) {
            stats = stats.copy(failedDebayers = stats.failedDebayers + 1)
            Log.w(TAG, "Cinema-Debayer (record-tap) failed for ${descriptor.width}x${descriptor.height}", throwable)
            false
        }
    }

    fun getStats(): CinemaDebayerStats = stats

    /** Records an externally-polled fence completion exactly when its frame identity is acknowledged. */
    internal fun recordDeferredCompletion(
        width: Int,
        height: Int,
        dispatchStartedElapsedRealtimeNs: Long,
        completionElapsedRealtimeNs: Long = SystemClock.elapsedRealtimeNanos()
    ) {
        if (dispatchStartedElapsedRealtimeNs <= 0L ||
            completionElapsedRealtimeNs < dispatchStartedElapsedRealtimeNs
        ) return
        val elapsedMs = ((completionElapsedRealtimeNs - dispatchStartedElapsedRealtimeNs) / 1_000_000L)
            .coerceAtLeast(0L)
        recordCompletedDemosaic(width, height, elapsedMs)
    }

    @Synchronized
    private fun recordCompletedDemosaic(width: Int, height: Int, elapsedMs: Long) {
        val currentStats = stats
        val newDebayered = currentStats.debayeredFrames + 1L
        totalDebayerTimeMs += elapsedMs
        stats = currentStats.copy(
            debayeredFrames = newDebayered,
            lastDebayerTimeMs = elapsedMs,
            averageDebayerTimeMs = totalDebayerTimeMs.toFloat() / newDebayered,
            lastBufferWidth = width,
            lastBufferHeight = height
        )
        if (newDebayered == 1L || newDebayered % 300L == 0L) {
            Log.i(
                TAG,
                "Cinema-Debayer completed frame=$newDebayered ${width}x$height " +
                    "last=${elapsedMs}ms avg=${stats.averageDebayerTimeMs}ms"
            )
        }
    }

    fun close() {
        pipelineActive.set(false)
        initialized.set(false)
        runCatching { attachedBridge?.setRawPreviewDemosaicEnabled(false) }
            .onFailure { Log.w(TAG, "Failed to clear native RAW-preview demosaic gate on close", it) }
        attachedBridge = null
        stats = CinemaDebayerStats()
        totalDebayerTimeMs = 0L
    }
}
