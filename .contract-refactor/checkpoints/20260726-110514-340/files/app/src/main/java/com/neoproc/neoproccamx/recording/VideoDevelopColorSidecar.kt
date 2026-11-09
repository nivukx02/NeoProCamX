package com.neoproc.neoproccamx.recording

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32

/**
 * VideoDevelopColorSidecar — the develop-critical COLOUR metadata for a record-then-develop LIGHT
 * intermediate `.mov`.
 *
 * The live LIGHT lane lays down a flat, high-fidelity 10-bit intermediate but deliberately withholds the
 * heavy colour stack (that runs OFFLINE). To reconstruct exact colour, the offline develop
 * ([VideoDevelopColorPipeline]) needs the capture-time facts the HEVC container does not carry per-clip:
 * the device-true white-balance gains, the working-RGB→XYZ matrix (which selects Rec.709 vs Rec.2020),
 * the scene adapting luminance (feeds CAM16 `sceneLuminanceLA`, replacing the 80 cd/m² default), the
 * container transfer (SDR / HLG / PQ), and the stabilised CCT + tint. This sidecar persists exactly those.
 *
 * It is written ONCE per intermediate as a sibling file (`<clip>.mov` → `<clip>.mov.devcolor`), mirroring
 * the RAW lane's [com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar] contract:
 *
 *   body  = magic 8 "NEOVDEV1" · version 2 · fields · colorProfileTag/detailProfileTag (UTF)
 *   frame = body · crc32 8 (CRC32 of body). The trailing CRC lets a reader reject a truncated/corrupt
 *           sidecar and fall back to develop defaults instead of grading with garbage colour metadata.
 *
 * NO-DOWNGRADE: absent/unreadable sidecar ⇒ the develop uses its documented neutral defaults (identity
 * working matrix, DEFAULT_SCENE_LA, SDR/Rec.709), so an un-tagged clip still develops sensibly. The
 * version field is bumped for any new field, with the decoder defaulting missing fields for old clips.
 */
object VideoDevelopColorSidecar {

    /** Sibling-file suffix appended to the intermediate's file name (`foo.mov` → `foo.mov.devcolor`). */
    const val FILE_SUFFIX = ".devcolor"

    const val VERSION = 3

    private val MAGIC = "NEOVDEV1".toByteArray(Charsets.US_ASCII) // 8 bytes
    private const val MATRIX_SIZE = 9
    private const val MIN_FRAME_BYTES = 8 + 4 + 8 // magic + version + trailing crc lower bound

    /** Working colour space of the intermediate; selects the develop's working-RGB→XYZ + ACES input. */
    enum class DevelopColorSpace(val id: Int) {
        REC709(0),
        REC2020(1);

        companion object {
            fun fromId(id: Int): DevelopColorSpace = values().firstOrNull { it.id == id } ?: REC709
        }
    }

    /** Container transfer the intermediate was tagged with; drives the develop's tone/PQ handling. */
    enum class DevelopColorTransfer(val id: Int) {
        SDR(0),
        HLG(1),
        PQ(2);

        companion object {
            fun fromId(id: Int): DevelopColorTransfer = values().firstOrNull { it.id == id } ?: SDR
        }
    }

    /** Immutable develop-colour record for one light intermediate. */
    data class VideoDevelopColorMetadata(
        val width: Int,
        val height: Int,
        /** Encoded bit depth of the intermediate (e.g. 10). */
        val storageBitDepth: Int,
        val fps: Int,
        val whiteBalanceR: Float,
        val whiteBalanceGr: Float,
        val whiteBalanceGb: Float,
        val whiteBalanceB: Float,
        /** Row-major 3×3 working-RGB→XYZ matrix (Rec.709 or Rec.2020 primaries). */
        val workingRgbToXyz: FloatArray,
        /** Scene adapting luminance (cd/m² hint); feeds CAM16 sceneLuminanceLA. 0 ⇒ use develop default. */
        val adaptingLuminance: Float,
        /** Stabilised correlated colour temperature (Kelvin) from the drift governor. */
        val cctKelvin: Float,
        /** Stabilised tint. */
        val tint: Float,
        val colorSpace: DevelopColorSpace,
        val colorTransfer: DevelopColorTransfer,
        /** Free-form profile/look hint (e.g. "rec709" / "hlg"); the develop card picks the final look. */
        val colorProfileTag: String,
        /** Selected recording detail profile; retained through offline conversion. */
        val detailProfileTag: String = "",
        /** Frozen Camera2 sensor-to-output rotation for the converted MOV display matrix. */
        val orientationHintDegrees: Int = 0
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is VideoDevelopColorMetadata) return false
            return width == other.width &&
                height == other.height &&
                storageBitDepth == other.storageBitDepth &&
                fps == other.fps &&
                whiteBalanceR == other.whiteBalanceR &&
                whiteBalanceGr == other.whiteBalanceGr &&
                whiteBalanceGb == other.whiteBalanceGb &&
                whiteBalanceB == other.whiteBalanceB &&
                workingRgbToXyz.contentEquals(other.workingRgbToXyz) &&
                adaptingLuminance == other.adaptingLuminance &&
                cctKelvin == other.cctKelvin &&
                tint == other.tint &&
                colorSpace == other.colorSpace &&
                colorTransfer == other.colorTransfer &&
                colorProfileTag == other.colorProfileTag &&
                detailProfileTag == other.detailProfileTag &&
                orientationHintDegrees == other.orientationHintDegrees
        }

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + storageBitDepth
            result = 31 * result + fps
            result = 31 * result + whiteBalanceR.toBits()
            result = 31 * result + whiteBalanceGr.toBits()
            result = 31 * result + whiteBalanceGb.toBits()
            result = 31 * result + whiteBalanceB.toBits()
            result = 31 * result + workingRgbToXyz.contentHashCode()
            result = 31 * result + adaptingLuminance.toBits()
            result = 31 * result + cctKelvin.toBits()
            result = 31 * result + tint.toBits()
            result = 31 * result + colorSpace.hashCode()
            result = 31 * result + colorTransfer.hashCode()
            result = 31 * result + colorProfileTag.hashCode()
            result = 31 * result + detailProfileTag.hashCode()
            result = 31 * result + orientationHintDegrees
            return result
        }
    }

    class SidecarFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** The sibling sidecar path for a given intermediate `.mov` file. */
    fun sidecarFileFor(movFile: File): File = File(movFile.parentFile, movFile.name + FILE_SUFFIX)

    fun encode(meta: VideoDevelopColorMetadata): ByteArray {
        val body = ByteArrayOutputStream(160)
        DataOutputStream(body).use { out ->
            out.write(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(meta.width)
            out.writeInt(meta.height)
            out.writeInt(meta.storageBitDepth)
            out.writeInt(meta.fps)
            out.writeFloat(meta.whiteBalanceR)
            out.writeFloat(meta.whiteBalanceGr)
            out.writeFloat(meta.whiteBalanceGb)
            out.writeFloat(meta.whiteBalanceB)
            val matrix = if (meta.workingRgbToXyz.size == MATRIX_SIZE) {
                meta.workingRgbToXyz
            } else {
                FloatArray(MATRIX_SIZE) { meta.workingRgbToXyz.getOrElse(it) { 0f } }
            }
            for (v in matrix) out.writeFloat(v)
            out.writeFloat(meta.adaptingLuminance)
            out.writeFloat(meta.cctKelvin)
            out.writeFloat(meta.tint)
            out.writeInt(meta.colorSpace.id)
            out.writeInt(meta.colorTransfer.id)
            out.writeUTF(meta.colorProfileTag)
            out.writeUTF(meta.detailProfileTag)
            out.writeInt(normalizeRightAngle(meta.orientationHintDegrees))
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

    fun decode(bytes: ByteArray): VideoDevelopColorMetadata {
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
            val fps = inp.readInt()
            val wbR = inp.readFloat()
            val wbGr = inp.readFloat()
            val wbGb = inp.readFloat()
            val wbB = inp.readFloat()
            val matrix = FloatArray(MATRIX_SIZE) { inp.readFloat() }
            val adaptingLuminance = inp.readFloat()
            val cctKelvin = inp.readFloat()
            val tint = inp.readFloat()
            val colorSpace = DevelopColorSpace.fromId(inp.readInt())
            val colorTransfer = DevelopColorTransfer.fromId(inp.readInt())
            val colorProfileTag = inp.readUTF()
            val detailProfileTag = if (version >= 2) inp.readUTF() else ""
            val orientationHintDegrees = if (version >= 3) normalizeRightAngle(inp.readInt()) else 0
            VideoDevelopColorMetadata(
                width = width,
                height = height,
                storageBitDepth = storageBitDepth,
                fps = fps,
                whiteBalanceR = wbR,
                whiteBalanceGr = wbGr,
                whiteBalanceGb = wbGb,
                whiteBalanceB = wbB,
                workingRgbToXyz = matrix,
                adaptingLuminance = adaptingLuminance,
                cctKelvin = cctKelvin,
                tint = tint,
                colorSpace = colorSpace,
                colorTransfer = colorTransfer,
                colorProfileTag = colorProfileTag,
                detailProfileTag = detailProfileTag,
                orientationHintDegrees = orientationHintDegrees
            )
        }
    }

    private fun normalizeRightAngle(degrees: Int): Int {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized % 90 != 0) throw SidecarFormatException("invalid orientation $degrees")
        return normalized
    }

    /**
     * Atomically writes the sidecar next to [movFile] (temp + fsync + rename), returning the sidecar file.
     * Mirrors the atomic-write discipline used by the recording quality certificate store.
     */
    fun write(movFile: File, meta: VideoDevelopColorMetadata): File {
        val target = sidecarFileFor(movFile)
        val parent = target.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val temp = File(parent, target.name + ".tmp")
        temp.outputStream().use { fos ->
            fos.write(encode(meta))
            fos.fd.sync()
        }
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            // Fallback: copy bytes if rename across the same dir somehow fails.
            target.writeBytes(temp.readBytes())
            temp.delete()
        }
        return target
    }

    /** Reads the sidecar for [movFile], or null when absent/corrupt (develop then uses its defaults). */
    fun read(movFile: File): VideoDevelopColorMetadata? {
        val sidecar = sidecarFileFor(movFile)
        if (!sidecar.isFile) return null
        return runCatching { decode(sidecar.readBytes()) }.getOrNull()
    }
}
