package com.neoproc.neoproccamx.niv99

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

/** Descriptor bindings and byte layouts shared by every NIV99 Vulkan stage. */
object Niv99ShaderAbi {
    const val RAW_INPUT_BINDING = 0
    const val LENS_SHADING_BINDING = 1
    const val DEFECT_MAP_BINDING = 2
    const val DENOISED_RAW_BINDING = 3
    const val CURRENT_LINEAR_BINDING = 4
    const val HISTORY_LINEAR_BINDING = 5
    const val FORWARD_FLOW_BINDING = 6
    const val BACKWARD_FLOW_BINDING = 7
    const val SEMANTIC_PROTECTION_BINDING = 8
    const val MOTION_CONFIDENCE_BINDING = 9
    const val TEMPORAL_OUTPUT_BINDING = 10
    const val RECONSTRUCTED_OUTPUT_BINDING = 11
    const val STATISTICS_BUFFER_BINDING = 12
    const val TILE_CONTROL_BUFFER_BINDING = 13
    const val TELEMETRY_BUFFER_BINDING = 14
    const val UNIFORM_BUFFER_BINDING = 15
    const val DESCRIPTOR_BINDING_COUNT = 16

    const val UNIFORM_VEC4_COUNT = 16
    const val UNIFORM_BYTES = UNIFORM_VEC4_COUNT * 16
    const val TILE_CONTROL_BYTES = 64
    const val TILE_STATISTICS_BYTES = 64
    const val TELEMETRY_RECORD_BYTES = 80

    const val IDENTITY_LOW_OFFSET = 32
    const val IDENTITY_HIGH_OFFSET = 48
    const val FLAGS_OFFSET = 64
    const val BLACK_LEVEL_OFFSET = 80
    const val WHITE_AND_THRESHOLDS_OFFSET = 96
    const val SHOT_NOISE_OFFSET = 112
    const val READ_NOISE_OFFSET = 128
    const val WHITE_BALANCE_OFFSET = 144
    const val COLOR_MATRIX_ROW_0_OFFSET = 160
    const val COLOR_MATRIX_ROW_1_OFFSET = 176
    const val COLOR_MATRIX_ROW_2_OFFSET = 192
    const val MOTION_PARAMETERS_OFFSET = 208
    const val TEMPORAL_PARAMETERS_OFFSET = 224
    const val RECONSTRUCTION_PARAMETERS_OFFSET = 240

    const val LOCAL_SIZE_IMAGE_X = 16
    const val LOCAL_SIZE_IMAGE_Y = 16
    const val LOCAL_SIZE_TILE_X = 8
    const val LOCAL_SIZE_TILE_Y = 8

    fun dispatchCount(extent: Int, localSize: Int): Int {
        if (extent <= 0 || localSize <= 0) return 0
        return ((extent.toLong() + localSize - 1L) / localSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun cameraIdHash(cameraId: String): Long {
        var hash = FNV64_OFFSET_BASIS
        for (character in cameraId) {
            val code = character.code
            hash = (hash xor (code and 0xff).toLong()) * FNV64_PRIME
            hash = (hash xor ((code ushr 8) and 0xff).toLong()) * FNV64_PRIME
        }
        return hash
    }

    internal fun isSameFrame(left: Niv99FrameIdentity, right: Niv99FrameIdentity): Boolean =
        left.cameraId == right.cameraId &&
            left.sessionGeneration == right.sessionGeneration &&
            left.frameNumber == right.frameNumber &&
            left.sensorTimestampNs == right.sensorTimestampNs

    private const val FNV64_OFFSET_BASIS = -3750763034362895579L
    private const val FNV64_PRIME = 1099511628211L
}

/** Motion values are resolved outside the shader so gyro/flow coordinate conventions stay explicit. */
class Niv99GpuMotionParameters {
    var gyroDisplacementX: Float = 0f
    var gyroDisplacementY: Float = 0f
    var rollingShutterHorizontalSkewPixels: Float = 0f
    var opticalFlowScale: Float = 1f

    fun validate(): Boolean =
        gyroDisplacementX.isFinite() && gyroDisplacementY.isFinite() &&
            rollingShutterHorizontalSkewPixels.isFinite() &&
            opticalFlowScale.isFinite() && opticalFlowScale > 0f
}

/**
 * Persistently allocated host-upload storage for the NIV99 shader ABI. The caller owns one workspace
 * per in-flight slot; [write] rewinds and overwrites the buffers without allocating frame objects.
 */
class Niv99GpuUploadWorkspace(maxTiles: Int) {
    val maximumTiles: Int = maxTiles.coerceAtLeast(1)
    val uniformBuffer: ByteBuffer = ByteBuffer.allocateDirect(Niv99ShaderAbi.UNIFORM_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
    val tileControlBuffer: ByteBuffer = ByteBuffer.allocateDirect(
        Math.multiplyExact(maximumTiles, Niv99ShaderAbi.TILE_CONTROL_BYTES)
    ).order(ByteOrder.LITTLE_ENDIAN)
    var tileCount: Int = 0
        private set

    fun write(
        context: Niv99FrameContext,
        calibration: Niv99SensorCalibration,
        statistics: Niv99TileStatistics,
        noise: Niv99NoiseModelSnapshot,
        whiteBalance: Niv99WhiteBalanceSolution,
        spatial: Niv99SpatialPlan,
        temporal: Niv99TemporalFusionPlan,
        localIlluminant: Niv99LocalIlluminantField?,
        motion: Niv99GpuMotionParameters,
        persistentDefectMapEnabled: Boolean
    ): Niv99ValidationResult {
        val contextValidation = context.validate()
        if (contextValidation != Niv99ValidationResult.VALID) return contextValidation
        if (calibration.validate() != Niv99ValidationResult.VALID ||
            calibration.cameraId != context.identity.cameraId || cfaCode(calibration.cfaPattern) < 0
        ) {
            return Niv99ValidationResult.INVALID_CALIBRATION
        }
        if (!Niv99ShaderAbi.isSameFrame(noise.identity, context.identity) ||
            !Niv99ShaderAbi.isSameFrame(whiteBalance.identity, context.identity) ||
            !validWhiteBalanceSource(whiteBalance, context.identity) ||
            !Niv99ShaderAbi.isSameFrame(temporal.identity, context.identity)
        ) {
            return Niv99ValidationResult.STALE_METADATA
        }
        if (!whiteBalance.colorMatrixValid || !motion.validate()) {
            return Niv99ValidationResult.INVALID_CALIBRATION
        }
        if (statistics.count <= 0 || statistics.count > maximumTiles ||
            statistics.columns <= 0 || statistics.rows <= 0 ||
            statistics.columns.toLong() * statistics.rows != statistics.count.toLong() ||
            spatial.count != statistics.count ||
            localIlluminant != null && (
                localIlluminant.count != statistics.count ||
                    localIlluminant.columns != statistics.columns ||
                    localIlluminant.rows != statistics.rows
                )
        ) {
            return Niv99ValidationResult.INVALID_DIMENSIONS
        }
        if (!allFinite(noise.shotCoefficient) || !allFinite(noise.readCoefficient) ||
            !allFinite(spatial.rawStrength, spatial.count) ||
            !allFinite(spatial.lumaStrength, spatial.count) ||
            !allFinite(spatial.chromaStrength, spatial.count) ||
            !allFinite(spatial.waveletFineThreshold, spatial.count) ||
            !allFinite(spatial.waveletMediumThreshold, spatial.count) ||
            !allFinite(spatial.waveletCoarseThreshold, spatial.count) ||
            !allFinite(spatial.edgeProtection, spatial.count) ||
            !allFinite(spatial.textureProtection, spatial.count) ||
            !allFinite(spatial.skinProtection, spatial.count) ||
            !allFinite(spatial.sharpening, spatial.count)
        ) {
            return Niv99ValidationResult.NON_FINITE_METADATA
        }

        writeUniforms(
            context,
            calibration,
            statistics,
            noise,
            whiteBalance,
            spatial,
            temporal,
            motion,
            persistentDefectMapEnabled
        )
        writeTileControls(spatial, temporal, localIlluminant)
        tileCount = statistics.count
        return Niv99ValidationResult.VALID
    }

    private fun writeUniforms(
        context: Niv99FrameContext,
        calibration: Niv99SensorCalibration,
        statistics: Niv99TileStatistics,
        noise: Niv99NoiseModelSnapshot,
        whiteBalance: Niv99WhiteBalanceSolution,
        spatial: Niv99SpatialPlan,
        temporal: Niv99TemporalFusionPlan,
        motion: Niv99GpuMotionParameters,
        persistentDefectMapEnabled: Boolean
    ) {
        val output = uniformBuffer
        output.clear()
        putInt4(output, context.width, context.height, statistics.columns, statistics.rows)
        val tileWidth = divideRoundUp(context.width, statistics.columns)
        val tileHeight = divideRoundUp(context.height, statistics.rows)
        putInt4(output, tileWidth, tileHeight, cfaCode(calibration.cfaPattern), contextBitDepth(context))
        val cameraHash = Niv99ShaderAbi.cameraIdHash(context.identity.cameraId)
        putLowWords(
            output,
            cameraHash,
            context.identity.sessionGeneration,
            context.identity.frameNumber,
            context.identity.sensorTimestampNs
        )
        putHighWords(
            output,
            cameraHash,
            context.identity.sessionGeneration,
            context.identity.frameNumber,
            context.identity.sensorTimestampNs
        )
        putInt4(
            output,
            if (calibration.lensShadingColumns > 0 && calibration.lensShadingRows > 0) 1 else 0,
            if (temporal.historyCount > 0) 1 else 0,
            if (context.sceneCutHint || temporal.resetReason != Niv99ResetReason.NONE) 1 else 0,
            if (persistentDefectMapEnabled) 1 else 0
        )
        putFloat4(output, calibration.blackLevel)
        putFloat4(output, calibration.whiteLevel, SHADOW_THRESHOLD, HIGHLIGHT_THRESHOLD, CLIP_TOLERANCE)
        putFloat4(output, noise.shotCoefficient)
        putFloat4(output, noise.readCoefficient)
        putFloat4(
            output,
            whiteBalance.redGain,
            whiteBalance.greenGain,
            whiteBalance.greenGain,
            whiteBalance.blueGain
        )
        putMatrixRow(output, whiteBalance.colorMatrix, 0)
        putMatrixRow(output, whiteBalance.colorMatrix, 1)
        putMatrixRow(output, whiteBalance.colorMatrix, 2)
        putFloat4(
            output,
            motion.gyroDisplacementX,
            motion.gyroDisplacementY,
            motion.rollingShutterHorizontalSkewPixels,
            motion.opticalFlowScale
        )
        putFloat4(
            output,
            temporal.totalHistoryWeight.coerceIn(0f, 8f),
            temporal.temporalStrength.coerceIn(0f, 1f),
            temporal.chromaFlickerSuppression.coerceIn(0f, 1f),
            DEFAULT_TEMPORAL_RESIDUAL_SIGMA
        )
        val meanNoiseSigma = sqrt(max(
            noise.meanVariance(context.meanLinearSignal, context.meanLinearSignal, context.meanLinearSignal),
            0f
        ))
        putFloat4(
            output,
            RECONSTRUCTION_STRENGTH,
            spatial.meanSharpening.coerceIn(0f, 1f),
            meanNoiseSigma,
            HALO_ALLOWANCE
        )
        check(output.position() == Niv99ShaderAbi.UNIFORM_BYTES)
        output.flip()
    }

    private fun writeTileControls(
        spatial: Niv99SpatialPlan,
        temporal: Niv99TemporalFusionPlan,
        localIlluminant: Niv99LocalIlluminantField?
    ) {
        val output = tileControlBuffer
        output.clear()
        for (index in 0 until spatial.count) {
            putFloat4(
                output,
                spatial.rawStrength[index].coerceIn(0f, 1f),
                spatial.lumaStrength[index].coerceIn(0f, 1f),
                spatial.chromaStrength[index].coerceIn(0f, 1f),
                temporal.temporalStrength.coerceIn(0f, 1f)
            )
            putFloat4(
                output,
                spatial.edgeProtection[index].coerceIn(0f, 1f),
                spatial.textureProtection[index].coerceIn(0f, 1f),
                spatial.skinProtection[index].coerceIn(0f, 1f),
                spatial.sharpening[index].coerceIn(0f, 1f)
            )
            putFloat4(
                output,
                max(spatial.waveletFineThreshold[index], 0f),
                max(spatial.waveletMediumThreshold[index], 0f),
                max(spatial.waveletCoarseThreshold[index], 0f),
                DEFAULT_TILE_RESIDUAL_SIGMA
            )
            if (localIlluminant == null) {
                putFloat4(output, 1f, 1f, 1f, 0f)
            } else {
                putFloat4(
                    output,
                    localIlluminant.redGain[index].coerceIn(MIN_LOCAL_GAIN, MAX_LOCAL_GAIN),
                    1f,
                    localIlluminant.blueGain[index].coerceIn(MIN_LOCAL_GAIN, MAX_LOCAL_GAIN),
                    localIlluminant.confidence[index].coerceIn(0f, 1f)
                )
            }
        }
        output.flip()
    }

    private fun contextBitDepth(context: Niv99FrameContext): Int = when (context.signalDomain) {
        Niv99SignalDomain.SENSOR_LINEAR_RAW -> 16
        Niv99SignalDomain.SENSOR_LINEAR_DEMOSAICED -> 16
        Niv99SignalDomain.P010_YUV -> 10
        Niv99SignalDomain.LINEAR_RGB -> 16
        Niv99SignalDomain.DISPLAY_RGB -> 10
    }

    private fun cfaCode(pattern: Niv99CfaPattern): Int = when (pattern) {
        Niv99CfaPattern.RGGB -> 0
        Niv99CfaPattern.GRBG -> 1
        Niv99CfaPattern.GBRG -> 2
        Niv99CfaPattern.BGGR -> 3
        Niv99CfaPattern.MONO -> -1
        Niv99CfaPattern.UNKNOWN -> -1
    }

    private fun divideRoundUp(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor - 1L) / divisor).toInt()

    private fun putLowWords(output: ByteBuffer, a: Long, b: Long, c: Long, d: Long) {
        output.putInt(a.toInt())
        output.putInt(b.toInt())
        output.putInt(c.toInt())
        output.putInt(d.toInt())
    }

    private fun putHighWords(output: ByteBuffer, a: Long, b: Long, c: Long, d: Long) {
        output.putInt((a ushr 32).toInt())
        output.putInt((b ushr 32).toInt())
        output.putInt((c ushr 32).toInt())
        output.putInt((d ushr 32).toInt())
    }

    private fun putInt4(output: ByteBuffer, x: Int, y: Int, z: Int, w: Int) {
        output.putInt(x)
        output.putInt(y)
        output.putInt(z)
        output.putInt(w)
    }

    private fun putFloat4(output: ByteBuffer, values: FloatArray) {
        require(values.size >= 4)
        putFloat4(output, values[0], values[1], values[2], values[3])
    }

    private fun putFloat4(output: ByteBuffer, x: Float, y: Float, z: Float, w: Float) {
        output.putFloat(x)
        output.putFloat(y)
        output.putFloat(z)
        output.putFloat(w)
    }

    private fun putMatrixRow(output: ByteBuffer, matrix: FloatArray, row: Int) {
        val offset = row * 3
        putFloat4(output, matrix[offset], matrix[offset + 1], matrix[offset + 2], 0f)
    }

    private fun allFinite(values: FloatArray, count: Int = values.size): Boolean {
        if (count !in 0..values.size) return false
        for (index in 0 until count) if (!values[index].isFinite()) return false
        return true
    }

    private fun validWhiteBalanceSource(
        whiteBalance: Niv99WhiteBalanceSolution,
        frameIdentity: Niv99FrameIdentity
    ): Boolean {
        val source = whiteBalance.sourceIdentity
        if (whiteBalance.authority != Niv99WhiteBalanceAuthority.AWB_LOCK) {
            return Niv99ShaderAbi.isSameFrame(source, frameIdentity)
        }
        return source.isComplete() &&
            source.sameTimeline(frameIdentity) &&
            source.frameNumber <= frameIdentity.frameNumber &&
            source.sensorTimestampNs <= frameIdentity.sensorTimestampNs
    }

    companion object {
        private const val SHADOW_THRESHOLD = 0.015f
        private const val HIGHLIGHT_THRESHOLD = 0.98f
        private const val CLIP_TOLERANCE = 0.02f
        private const val DEFAULT_TEMPORAL_RESIDUAL_SIGMA = 3f
        private const val DEFAULT_TILE_RESIDUAL_SIGMA = 3f
        private const val RECONSTRUCTION_STRENGTH = 1f
        private const val HALO_ALLOWANCE = 0.015f
        private const val MIN_LOCAL_GAIN = 0.5f
        private const val MAX_LOCAL_GAIN = 2f
    }
}
