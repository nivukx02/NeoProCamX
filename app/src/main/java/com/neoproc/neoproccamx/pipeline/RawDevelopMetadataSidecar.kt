package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.rendering.RawBufferDescriptor
import com.neoproc.neoproccamx.rendering.RawLensShadingGridPolicy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.CRC32

/**
 * RawDevelopMetadataSidecar — the develop-critical sensor metadata for a 12-bit uncompressed RAW
 * video clip.
 *
 * The NPRAW pixel stream ([NprawFrameCodec], `stream.npraw`) carries geometry + black/white level +
 * a per-frame CRC, but NOT the colour metadata a debayer/develop needs (white-balance gains, the
 * Bayer CFA phase, or the sensor→XYZ(D50) forward matrix). This sidecar is written ONCE per clip
 * next to the stream and read by the offline RAW→NeoLog converter so it can debayer, white-balance
 * and colour-transform the sensor data correctly — WITHOUT touching the golden-locked frame codec.
 *
 * RAW mode captures with AWB locked (sensor-truthful, ISP-bypassed), so a single per-clip
 * white-balance / matrix snapshot is exact for the whole clip.
 *
 * Layout (big-endian, java.io DataOutput):
 *   body  = magic 8 "NRAWMETA" · version 4-byte · fields · colorProfileTag (UTF)
 *   frame = body · crc32 8-byte (CRC32 of the body). The trailing CRC lets a reader reject a
 *           truncated/corrupt sidecar instead of developing with garbage colour metadata.
 */
object RawDevelopMetadataSidecar {

    /** Canonical file name written alongside `stream.npraw` in the RAW clip's session directory. */
    const val FILE_NAME = "develop.meta"

    // v2 appends sensorPixelModeMaximumResolutionUsed; v3 appends the frozen recording orientation;
    // v4 preserves the per-CFA black calibration and the captured 3x3 lens-shading field. Older
    // sidecars remain readable and resolve those additions to the legacy scalar/identity behaviour.
    // decode() still accepts older sidecars and defaults missing fields for backward compatibility.
    // v5 adds a CRC-protected audio obligation. New clips must never be converted silently when
    // microphone capture was requested; v1-v4 keep their explicitly legacy, video-only behaviour.
    const val VERSION = 5

    private val MAGIC = "NRAWMETA".toByteArray(Charsets.US_ASCII) // 8 bytes
    private const val SENSOR_MATRIX_SIZE = 9
    private const val MIN_FRAME_BYTES = 8 + 4 + 8 // magic + version + trailing crc lower bound

    /** Immutable develop-metadata record for one RAW clip. */
    data class RawDevelopMetadata(
        val width: Int,
        val height: Int,
        /** Storage bit depth of the packed NPRAW samples (e.g. 12). */
        val storageBitDepth: Int,
        /** SENSOR_INFO_WHITE_LEVEL — the companding/native-depth domain. */
        val sensorWhiteLevel: Int,
        val blackLevel: Int,
        val whiteLevel: Int,
        /** [com.neoproc.neoproccamx.rendering.BayerPattern.nativeValue]: RGGB=0/GRBG=1/GBRG=2/BGGR=3. */
        val bayerPatternValue: Int,
        val whiteBalanceR: Float,
        val whiteBalanceGr: Float,
        val whiteBalanceGb: Float,
        val whiteBalanceB: Float,
        val exposureTimeNs: Long,
        val analogGain: Float,
        val digitalGain: Float,
        val noiseS: Float,
        val noiseO: Float,
        /** Row-major 3×3 sensor→XYZ(D50) forward matrix. */
        val sensorToXyz: FloatArray,
        /** Throttle-derived target fps hint; the converter may recompute exact fps from timestamps. */
        val fps: Int,
        /** Default NeoLog variant hint for the converter (the user picks the final variant on convert). */
        val colorProfileTag: String,
        /**
         * True when SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION was actually engaged for this clip (un-binned
         * maximum-sensor capture); false for the full active-array path. Lets the offline converter and
         * diagnostic tools determine how each frame was captured. (Sidecar v2+; false for v1 clips.)
         */
        val sensorPixelModeMaximumResolutionUsed: Boolean = false,
        val orientationHintDegrees: Int = 0,
        /** Semantic R, Gr, Gb, B offsets captured with the RAW stream. */
        val blackLevelRggb: FloatArray = FloatArray(4) { blackLevel.toFloat() },
        /** Row-major 3x3 grid of semantic R, Gr, Gb, B optical-shading gains. */
        val lensShadingGridRggb: FloatArray = RawLensShadingGridPolicy.identityGrid(),
        /** True only when [lensShadingGridRggb] came from valid Camera2 calibration metadata. */
        val lensShadingCorrectionEnabled: Boolean = false,
        val audioExpected: Boolean = false
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RawDevelopMetadata) return false
            return width == other.width &&
                height == other.height &&
                storageBitDepth == other.storageBitDepth &&
                sensorWhiteLevel == other.sensorWhiteLevel &&
                blackLevel == other.blackLevel &&
                whiteLevel == other.whiteLevel &&
                bayerPatternValue == other.bayerPatternValue &&
                whiteBalanceR == other.whiteBalanceR &&
                whiteBalanceGr == other.whiteBalanceGr &&
                whiteBalanceGb == other.whiteBalanceGb &&
                whiteBalanceB == other.whiteBalanceB &&
                exposureTimeNs == other.exposureTimeNs &&
                analogGain == other.analogGain &&
                digitalGain == other.digitalGain &&
                noiseS == other.noiseS &&
                noiseO == other.noiseO &&
                sensorToXyz.contentEquals(other.sensorToXyz) &&
                fps == other.fps &&
                colorProfileTag == other.colorProfileTag &&
                sensorPixelModeMaximumResolutionUsed == other.sensorPixelModeMaximumResolutionUsed &&
                orientationHintDegrees == other.orientationHintDegrees &&
                blackLevelRggb.contentEquals(other.blackLevelRggb) &&
                lensShadingGridRggb.contentEquals(other.lensShadingGridRggb) &&
                lensShadingCorrectionEnabled == other.lensShadingCorrectionEnabled &&
                audioExpected == other.audioExpected
        }

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + storageBitDepth
            result = 31 * result + sensorWhiteLevel
            result = 31 * result + blackLevel
            result = 31 * result + whiteLevel
            result = 31 * result + bayerPatternValue
            result = 31 * result + whiteBalanceR.toBits()
            result = 31 * result + whiteBalanceGr.toBits()
            result = 31 * result + whiteBalanceGb.toBits()
            result = 31 * result + whiteBalanceB.toBits()
            result = 31 * result + exposureTimeNs.hashCode()
            result = 31 * result + analogGain.toBits()
            result = 31 * result + digitalGain.toBits()
            result = 31 * result + noiseS.toBits()
            result = 31 * result + noiseO.toBits()
            result = 31 * result + sensorToXyz.contentHashCode()
            result = 31 * result + fps
            result = 31 * result + colorProfileTag.hashCode()
            result = 31 * result + sensorPixelModeMaximumResolutionUsed.hashCode()
            result = 31 * result + orientationHintDegrees
            result = 31 * result + blackLevelRggb.contentHashCode()
            result = 31 * result + lensShadingGridRggb.contentHashCode()
            result = 31 * result + lensShadingCorrectionEnabled.hashCode()
            result = 31 * result + audioExpected.hashCode()
            return result
        }
    }

    class SidecarFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Build a metadata record from the per-frame [RawBufferDescriptor] plus clip-level fields. */
    fun fromDescriptor(
        descriptor: RawBufferDescriptor,
        storageBitDepth: Int,
        sensorWhiteLevel: Int,
        fps: Int,
        colorProfileTag: String,
        sensorPixelModeMaximumResolutionUsed: Boolean = false,
        orientationHintDegrees: Int = 0,
        audioExpected: Boolean = false
    ): RawDevelopMetadata = RawDevelopMetadata(
        width = descriptor.width,
        height = descriptor.height,
        storageBitDepth = storageBitDepth,
        sensorWhiteLevel = sensorWhiteLevel,
        blackLevel = descriptor.blackLevel,
        whiteLevel = descriptor.whiteLevel,
        bayerPatternValue = descriptor.bayerPattern.nativeValue,
        whiteBalanceR = descriptor.whiteBalanceR,
        whiteBalanceGr = descriptor.whiteBalanceGr,
        whiteBalanceGb = descriptor.whiteBalanceGb,
        whiteBalanceB = descriptor.whiteBalanceB,
        exposureTimeNs = descriptor.exposureTimeNs,
        analogGain = descriptor.analogGain,
        digitalGain = descriptor.digitalGain,
        noiseS = descriptor.noiseS,
        noiseO = descriptor.noiseO,
        sensorToXyz = descriptor.sensorToXyz.copyOf(),
        fps = fps,
        colorProfileTag = colorProfileTag,
        sensorPixelModeMaximumResolutionUsed = sensorPixelModeMaximumResolutionUsed,
        orientationHintDegrees = normalizeRightAngle(orientationHintDegrees),
        blackLevelRggb = descriptor.blackLevelRggb.copyOf(),
        lensShadingGridRggb = descriptor.lensShadingGridRggb.copyOf(),
        lensShadingCorrectionEnabled = descriptor.lensShadingCorrectionEnabled,
        audioExpected = audioExpected
    )

    fun encode(meta: RawDevelopMetadata): ByteArray {
        validateCalibration(meta.blackLevelRggb, meta.lensShadingGridRggb, meta.whiteLevel)
        val body = ByteArrayOutputStream(192)
        DataOutputStream(body).use { out ->
            out.write(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(meta.width)
            out.writeInt(meta.height)
            out.writeInt(meta.storageBitDepth)
            out.writeInt(meta.sensorWhiteLevel)
            out.writeInt(meta.blackLevel)
            out.writeInt(meta.whiteLevel)
            out.writeInt(meta.bayerPatternValue)
            out.writeFloat(meta.whiteBalanceR)
            out.writeFloat(meta.whiteBalanceGr)
            out.writeFloat(meta.whiteBalanceGb)
            out.writeFloat(meta.whiteBalanceB)
            out.writeLong(meta.exposureTimeNs)
            out.writeFloat(meta.analogGain)
            out.writeFloat(meta.digitalGain)
            out.writeFloat(meta.noiseS)
            out.writeFloat(meta.noiseO)
            val matrix = if (meta.sensorToXyz.size == SENSOR_MATRIX_SIZE) {
                meta.sensorToXyz
            } else {
                FloatArray(SENSOR_MATRIX_SIZE) { meta.sensorToXyz.getOrElse(it) { 0f } }
            }
            for (v in matrix) out.writeFloat(v)
            out.writeInt(meta.fps)
            out.writeUTF(meta.colorProfileTag)
            out.writeBoolean(meta.sensorPixelModeMaximumResolutionUsed)
            out.writeInt(normalizeRightAngle(meta.orientationHintDegrees))
            meta.blackLevelRggb.forEach(out::writeFloat)
            meta.lensShadingGridRggb.forEach(out::writeFloat)
            out.writeBoolean(meta.lensShadingCorrectionEnabled)
            out.writeBoolean(meta.audioExpected)
        }
        val bodyBytes = body.toByteArray()
        val crc = CRC32().apply { update(bodyBytes) }.value
        val full = ByteArrayOutputStream(bodyBytes.size + 8)
        DataOutputStream(full).use { out ->
            out.write(bodyBytes)
            out.writeLong(crc)
        }
        return full.toByteArray()
    }

    fun decode(bytes: ByteArray): RawDevelopMetadata {
        if (bytes.size < MIN_FRAME_BYTES) throw SidecarFormatException("truncated sidecar (${bytes.size} bytes)")
        val bodyLen = bytes.size - 8
        val storedCrc = DataInputStream(ByteArrayInputStream(bytes, bodyLen, 8)).use { it.readLong() }
        val actualCrc = CRC32().apply { update(bytes, 0, bodyLen) }.value
        if (storedCrc != actualCrc) throw SidecarFormatException("crc mismatch")
        return DataInputStream(ByteArrayInputStream(bytes, 0, bodyLen)).use { inp ->
            val magic = ByteArray(MAGIC.size)
            inp.readFully(magic)
            if (!magic.contentEquals(MAGIC)) throw SidecarFormatException("bad magic")
            val version = inp.readInt()
            if (version !in 1..VERSION) throw SidecarFormatException("unsupported version $version")
            val width = inp.readInt()
            val height = inp.readInt()
            val storageBitDepth = inp.readInt()
            val sensorWhiteLevel = inp.readInt()
            val blackLevel = inp.readInt()
            val whiteLevel = inp.readInt()
            val bayerPatternValue = inp.readInt()
            val wbR = inp.readFloat()
            val wbGr = inp.readFloat()
            val wbGb = inp.readFloat()
            val wbB = inp.readFloat()
            val exposureTimeNs = inp.readLong()
            val analogGain = inp.readFloat()
            val digitalGain = inp.readFloat()
            val noiseS = inp.readFloat()
            val noiseO = inp.readFloat()
            val matrix = FloatArray(SENSOR_MATRIX_SIZE) { inp.readFloat() }
            val fps = inp.readInt()
            val colorProfileTag = inp.readUTF()
            // v1 sidecars have no trailing flag byte — default to false.
            val sensorPixelModeMaximumResolutionUsed = if (version >= 2) inp.readBoolean() else false
            val orientationHintDegrees = if (version >= 3) normalizeRightAngle(inp.readInt()) else 0
            val blackLevelRggb = if (version >= 4) {
                FloatArray(4) { inp.readFloat() }
            } else {
                FloatArray(4) { blackLevel.toFloat() }
            }
            val lensShadingGridRggb = if (version >= 4) {
                FloatArray(RawLensShadingGridPolicy.FLOAT_COUNT) { inp.readFloat() }
            } else {
                RawLensShadingGridPolicy.identityGrid()
            }
            val lensShadingCorrectionEnabled = version >= 4 && inp.readBoolean()
            val audioExpected = version >= 5 && inp.readBoolean()
            validateCalibration(blackLevelRggb, lensShadingGridRggb, whiteLevel)
            RawDevelopMetadata(
                width = width,
                height = height,
                storageBitDepth = storageBitDepth,
                sensorWhiteLevel = sensorWhiteLevel,
                blackLevel = blackLevel,
                whiteLevel = whiteLevel,
                bayerPatternValue = bayerPatternValue,
                whiteBalanceR = wbR,
                whiteBalanceGr = wbGr,
                whiteBalanceGb = wbGb,
                whiteBalanceB = wbB,
                exposureTimeNs = exposureTimeNs,
                analogGain = analogGain,
                digitalGain = digitalGain,
                noiseS = noiseS,
                noiseO = noiseO,
                sensorToXyz = matrix,
                fps = fps,
                colorProfileTag = colorProfileTag,
                sensorPixelModeMaximumResolutionUsed = sensorPixelModeMaximumResolutionUsed,
                orientationHintDegrees = orientationHintDegrees,
                blackLevelRggb = blackLevelRggb,
                lensShadingGridRggb = lensShadingGridRggb,
                lensShadingCorrectionEnabled = lensShadingCorrectionEnabled,
                audioExpected = audioExpected
            )
        }
    }

    private fun validateCalibration(
        blackLevelRggb: FloatArray,
        lensShadingGridRggb: FloatArray,
        whiteLevel: Int
    ) {
        if (blackLevelRggb.size != 4 ||
            blackLevelRggb.any { !it.isFinite() || it < 0f || it >= whiteLevel.toFloat() }
        ) {
            throw SidecarFormatException("invalid per-CFA black-level calibration")
        }
        if (lensShadingGridRggb.size != RawLensShadingGridPolicy.FLOAT_COUNT ||
            lensShadingGridRggb.any { !it.isFinite() || it !in 0.25f..8f }
        ) {
            throw SidecarFormatException("invalid lens-shading calibration grid")
        }
    }

    private fun normalizeRightAngle(degrees: Int): Int {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized % 90 != 0) throw SidecarFormatException("invalid orientation $degrees")
        return normalized
    }
}
