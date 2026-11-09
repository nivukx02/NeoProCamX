package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.pipeline.VideoNoiseMetrics
import com.neoproc.neoproccamx.rendering.GpuSignalFamily
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.zip.CheckedInputStream
import java.util.zip.CheckedOutputStream
import java.util.zip.CRC32

/**
 * Persistent sidecar for [NeoQualityInstruction] decisions.
 *
 * The cache stores measurements and bounded controls only; source or generated pixels are never stored.
 * Its fingerprint includes the immutable master's identity and every input that can affect an instruction.
 * A miss or any malformed byte falls back to normal analysis, so this optimisation cannot block export.
 */
internal object NeoQualityInstructionCache {
    private const val FILE_FORMAT_VERSION = 1
    private const val EXTENSION = "nqis"
    private val MAGIC = byteArrayOf(0x4E, 0x51, 0x49, 0x53, 0x43, 0x41, 0x43, 0x48) // NQISCACH

    data class Fingerprint(
        val sourceLength: Long,
        val sourceLastModified: Long,
        val sourceSha256: String,
        val width: Int,
        val height: Int,
        val signalFamily: GpuSignalFamily,
        val detailProfile: VideoDetailProfile,
        val snceChromaEnabled: Boolean = false,
        val sourceSceneAnalysisEnabled: Boolean = true,
        val sharpnessScale: Float = 1f,
        val denoiseScale: Float = 1f,
        val frameCount: Int
    )

    fun open(source: File, fingerprint: Fingerprint): NeoQualityInstructionSession {
        if (!isUsable(fingerprint)) return NeoQualityInstructionSession.disabled(fingerprint)
        val cacheFile = cacheFileFor(source)
        val cached = runCatching { openValidatedSource(cacheFile, fingerprint) }.getOrNull()
        return if (cached != null) {
            NeoQualityInstructionSession.cacheHit(fingerprint, cached)
        } else {
            NeoQualityInstructionSession.cacheMiss(fingerprint, cacheFile)
        }
    }

    fun deleteFor(source: File) {
        runCatching { cacheFileFor(source).delete() }
        val prefix = ".${source.name}.nqis-"
        source.parentFile?.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".tmp") }
            .forEach { temporary -> runCatching { temporary.delete() } }
    }

    private fun cacheFileFor(source: File): File {
        val absoluteSource = source.absoluteFile
        val parent = absoluteSource.parentFile
        return if (parent != null) {
            File(parent, ".${absoluteSource.name}.$EXTENSION")
        } else {
            File("${absoluteSource.path}.$EXTENSION")
        }
    }

    private fun isUsable(fingerprint: Fingerprint): Boolean =
        fingerprint.sourceLength > 0L &&
            fingerprint.sourceLastModified >= 0L &&
            fingerprint.sourceSha256.length == 64 &&
            fingerprint.sourceSha256.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } &&
            fingerprint.width > 0 &&
            fingerprint.height > 0 &&
            fingerprint.frameCount > 0 &&
            fingerprint.sharpnessScale in 0.25f..1.25f &&
            fingerprint.denoiseScale in 0.75f..1.75f

    private fun openValidatedSource(
        cacheFile: File,
        expected: Fingerprint
    ): CachedSource? {
        if (!cacheFile.isFile || cacheFile.length() <= MAGIC.size + Long.SIZE_BYTES) return null
        val maximumBytes = 512L + expected.frameCount.toLong() * 72L + Long.SIZE_BYTES
        if (cacheFile.length() > maximumBytes) return null
        val valid = FileInputStream(cacheFile).use { file ->
            val checked = CheckedInputStream(file, CRC32())
            val input = DataInputStream(checked)
            if (!readAndValidateHeader(input, expected)) return@use false
            repeat(expected.frameCount) { index ->
                val instruction = readInstruction(input, expected) ?: return@use false
                if (instruction.frameIndex != index.toLong()) return@use false
            }
            val actualCrc = checked.checksum.value
            val trailer = ByteArray(Long.SIZE_BYTES)
            if (file.read(trailer) != trailer.size || file.read() != -1) return@use false
            actualCrc == trailer.toLongBigEndian()
        }
        return if (valid) CachedSource.open(cacheFile, expected) else null
    }

    private fun readAndValidateHeader(
        input: DataInputStream,
        expected: Fingerprint
    ): Boolean {
        val magic = ByteArray(MAGIC.size)
        input.readFully(magic)
        if (!magic.contentEquals(MAGIC)) return false
        if (input.readInt() != FILE_FORMAT_VERSION) return false
        if (input.readInt() != NeoQualityInstructionStream.VERSION) return false
        if (input.readLong() != expected.sourceLength) return false
        if (input.readLong() != expected.sourceLastModified) return false
        if (input.readUTF() != expected.sourceSha256) return false
        if (input.readInt() != expected.width || input.readInt() != expected.height) return false
        if (input.readUTF() != expected.signalFamily.name) return false
        if (input.readUTF() != expected.detailProfile.name) return false
        if (input.readBoolean() != expected.snceChromaEnabled) return false
        if (input.readBoolean() != expected.sourceSceneAnalysisEnabled) return false
        if (input.readInt() != expected.sharpnessScale.toRawBits()) return false
        if (input.readInt() != expected.denoiseScale.toRawBits()) return false
        return input.readInt() == expected.frameCount
    }

    private fun readInstruction(
        input: DataInputStream,
        fingerprint: Fingerprint
    ): NeoQualityInstruction? {
        val frameIndex = input.readLong()
        val noise = VideoNoiseMetrics(
            chromaNoise = input.readFiniteUnitFloat() ?: return null,
            lumaNoise = input.readFiniteUnitFloat() ?: return null,
            temporalInstability = input.readFiniteUnitFloat() ?: return null,
            frameToFrameRgbVariance = input.readFiniteUnitFloat() ?: return null
        )
        val scene = if (input.readBoolean()) {
            P010SceneDetailMetrics(
                meanLuma = input.readFiniteUnitFloat() ?: return null,
                shadowFraction = input.readFiniteUnitFloat() ?: return null,
                highlightFraction = input.readFiniteUnitFloat() ?: return null,
                edgeDensity = input.readFiniteUnitFloat() ?: return null,
                textureConfidence = input.readFiniteUnitFloat() ?: return null
            )
        } else {
            null
        }
        val saturation = input.readFiniteFloat(0.90f, 1.10f) ?: return null
        val warmth = input.readFiniteFloat(-0.10f, 0.10f) ?: return null
        if (!fingerprint.snceChromaEnabled &&
            (saturation.toRawBits() != 1f.toRawBits() || warmth.toRawBits() != 0f.toRawBits())
        ) return null
        val storedQuality = ConversionFrameQualityPolicy.Plan(
            sharpen = input.readFiniteUnitFloat() ?: return null,
            denoise = input.readFiniteUnitFloat() ?: return null,
            severity = input.readFiniteUnitFloat() ?: return null,
            textureConfidence = input.readFiniteUnitFloat() ?: return null
        )
        val resolvedQuality = ConversionFrameQualityPolicy.resolve(
            family = fingerprint.signalFamily,
            detailProfile = fingerprint.detailProfile,
            metrics = noise,
            sceneDetail = scene,
            sharpnessScale = fingerprint.sharpnessScale,
            denoiseScale = fingerprint.denoiseScale
        )
        if (!storedQuality.rawBitsEqual(resolvedQuality)) return null
        return NeoQualityInstruction(
            version = NeoQualityInstructionStream.VERSION,
            frameIndex = frameIndex,
            width = fingerprint.width,
            height = fingerprint.height,
            signalFamily = fingerprint.signalFamily,
            detailProfile = fingerprint.detailProfile,
            noiseMetrics = noise,
            sceneDetail = scene,
            saturation = saturation,
            warmth = warmth,
            quality = storedQuality
        )
    }

    private fun DataInputStream.readFiniteUnitFloat(): Float? {
        return readFiniteFloat(0f, 1f)
    }

    private fun DataInputStream.readFiniteFloat(minimum: Float, maximum: Float): Float? {
        val value = Float.fromBits(readInt())
        return value.takeIf { it.isFinite() && it in minimum..maximum }
    }

    private fun ConversionFrameQualityPolicy.Plan.rawBitsEqual(
        other: ConversionFrameQualityPolicy.Plan
    ): Boolean =
        sharpen.toRawBits() == other.sharpen.toRawBits() &&
            denoise.toRawBits() == other.denoise.toRawBits() &&
            severity.toRawBits() == other.severity.toRawBits() &&
            textureConfidence.toRawBits() == other.textureConfidence.toRawBits()

    private fun ByteArray.toLongBigEndian(): Long {
        if (size != Long.SIZE_BYTES) return -1L
        var value = 0L
        forEach { byte -> value = (value shl 8) or (byte.toLong() and 0xFFL) }
        return value
    }

    private fun writeHeader(output: DataOutputStream, fingerprint: Fingerprint) = with(output) {
        write(MAGIC)
        writeInt(FILE_FORMAT_VERSION)
        writeInt(NeoQualityInstructionStream.VERSION)
        writeLong(fingerprint.sourceLength)
        writeLong(fingerprint.sourceLastModified)
        writeUTF(fingerprint.sourceSha256)
        writeInt(fingerprint.width)
        writeInt(fingerprint.height)
        writeUTF(fingerprint.signalFamily.name)
        writeUTF(fingerprint.detailProfile.name)
        writeBoolean(fingerprint.snceChromaEnabled)
        writeBoolean(fingerprint.sourceSceneAnalysisEnabled)
        writeInt(fingerprint.sharpnessScale.toRawBits())
        writeInt(fingerprint.denoiseScale.toRawBits())
        writeInt(fingerprint.frameCount)
    }

    private fun writeInstruction(output: DataOutputStream, instruction: NeoQualityInstruction) = with(output) {
        writeLong(instruction.frameIndex)
        writeFloatBits(instruction.noiseMetrics.chromaNoise)
        writeFloatBits(instruction.noiseMetrics.lumaNoise)
        writeFloatBits(instruction.noiseMetrics.temporalInstability)
        writeFloatBits(instruction.noiseMetrics.frameToFrameRgbVariance)
        val scene = instruction.sceneDetail
        writeBoolean(scene != null)
        if (scene != null) {
            writeFloatBits(scene.meanLuma)
            writeFloatBits(scene.shadowFraction)
            writeFloatBits(scene.highlightFraction)
            writeFloatBits(scene.edgeDensity)
            writeFloatBits(scene.textureConfidence)
        }
        writeFloatBits(instruction.saturation)
        writeFloatBits(instruction.warmth)
        writeFloatBits(instruction.quality.sharpen)
        writeFloatBits(instruction.quality.denoise)
        writeFloatBits(instruction.quality.severity)
        writeFloatBits(instruction.quality.textureConfidence)
    }

    private fun DataOutputStream.writeFloatBits(value: Float) = writeInt(value.toRawBits())

    /** Streams cache records while frames are already in flight, keeping memory use constant for long clips. */
    internal class Sink private constructor(
        private val cacheFile: File,
        private val temporaryFile: File,
        private val fileOutput: FileOutputStream,
        private val checkedOutput: CheckedOutputStream,
        private var output: DataOutputStream?,
        private val expectedFrameCount: Int
    ) {
        private var writtenFrameCount = 0

        fun append(instruction: NeoQualityInstruction): Boolean {
            val active = output ?: return false
            if (instruction.frameIndex != writtenFrameCount.toLong()) {
                abort()
                return false
            }
            return runCatching {
                writeInstruction(active, instruction)
                writtenFrameCount += 1
                true
            }.getOrElse {
                abort()
                false
            }
        }

        fun commit(convertedFrameCount: Long): Boolean {
            val active = output ?: return false
            if (convertedFrameCount != expectedFrameCount.toLong() ||
                writtenFrameCount != expectedFrameCount
            ) {
                abort()
                return false
            }
            var committed = false
            try {
                active.flush()
                val crc = checkedOutput.checksum.value
                // Write the checksum outside CheckedOutputStream so the trailer does not hash itself.
                val trailer = ByteArray(Long.SIZE_BYTES) { index ->
                    (crc ushr ((Long.SIZE_BYTES - 1 - index) * 8)).toByte()
                }
                fileOutput.write(trailer)
                fileOutput.fd.sync()
                active.close()
                output = null
                if (cacheFile.exists() && !cacheFile.delete()) return false
                committed = temporaryFile.renameTo(cacheFile)
                return committed
            } catch (_: Throwable) {
                return false
            } finally {
                if (!committed) {
                    runCatching { active.close() }
                    output = null
                    runCatching { temporaryFile.delete() }
                }
            }
        }

        fun abort() {
            val active = output
            output = null
            runCatching { active?.close() }
            runCatching { temporaryFile.delete() }
        }

        companion object {
            fun create(cacheFile: File, fingerprint: Fingerprint): Sink? = runCatching {
                cacheFile.parentFile?.mkdirs()
                val temporary = File(
                    cacheFile.parentFile,
                    "${cacheFile.name}-${System.nanoTime()}.tmp"
                )
                val file = FileOutputStream(temporary)
                try {
                    val checked = CheckedOutputStream(file, CRC32())
                    val data = DataOutputStream(checked)
                    writeHeader(data, fingerprint)
                    Sink(
                        cacheFile = cacheFile,
                        temporaryFile = temporary,
                        fileOutput = file,
                        checkedOutput = checked,
                        output = data,
                        expectedFrameCount = fingerprint.frameCount
                    )
                } catch (throwable: Throwable) {
                    runCatching { file.close() }
                    runCatching { temporary.delete() }
                    throw throwable
                }
            }.getOrNull()
        }
    }

    /** Sequential cache reader; it keeps one compact instruction resident instead of the full clip. */
    internal class CachedSource private constructor(
        private val input: DataInputStream,
        private val expected: Fingerprint
    ) {
        var consumedFrameCount: Int = 0
            private set

        fun next(frameIndex: Long): NeoQualityInstruction? = runCatching {
            if (frameIndex != consumedFrameCount.toLong()) return@runCatching null
            val instruction = readInstruction(input, expected) ?: return@runCatching null
            if (instruction.frameIndex != frameIndex) return@runCatching null
            consumedFrameCount += 1
            instruction
        }.getOrNull()

        fun close() {
            runCatching { input.close() }
        }

        companion object {
            fun open(cacheFile: File, fingerprint: Fingerprint): CachedSource? = runCatching {
                val input = DataInputStream(FileInputStream(cacheFile))
                if (!readAndValidateHeader(input, fingerprint)) {
                    input.close()
                    return@runCatching null
                }
                CachedSource(input, fingerprint)
            }.getOrNull()
        }
    }
}

/** Per-conversion cache session. All calls are made by the ordered frame producer. */
internal class NeoQualityInstructionSession private constructor(
    private val fingerprint: NeoQualityInstructionCache.Fingerprint,
    private var cachedSource: NeoQualityInstructionCache.CachedSource?,
    private var sink: NeoQualityInstructionCache.Sink?
) {
    val isCacheHit: Boolean get() = cachedSource != null

    fun resolve(
        frameIndex: Long,
        payload: ByteArray,
        previousP010Payload: ByteArray?,
        saturation: Float = 1f,
        warmth: Float = 0f
    ): NeoQualityInstruction {
        val source = cachedSource
        if (source != null) {
            val cached = source.next(frameIndex)
            if (cached != null) return cached
            source.close()
            cachedSource = null
        }
        val instruction = NeoQualityInstructionStream.analyzeP010(
            frameIndex = frameIndex,
            payload = payload,
            previousP010Payload = previousP010Payload,
            width = fingerprint.width,
            height = fingerprint.height,
            signalFamily = fingerprint.signalFamily,
            detailProfile = fingerprint.detailProfile,
            sourceSceneAnalysisEnabled = fingerprint.sourceSceneAnalysisEnabled,
            sharpnessScale = fingerprint.sharpnessScale,
            denoiseScale = fingerprint.denoiseScale,
            saturation = saturation,
            warmth = warmth
        )
        sink?.append(instruction)
        return instruction
    }

    fun resolve(
        frameIndex: Long,
        payload: ByteBuffer,
        previousP010Payload: ByteBuffer?,
        saturation: Float = 1f,
        warmth: Float = 0f
    ): NeoQualityInstruction {
        val source = cachedSource
        if (source != null) {
            val cached = source.next(frameIndex)
            if (cached != null) return cached
            source.close()
            cachedSource = null
        }
        val instruction = NeoQualityInstructionStream.analyzeP010(
            frameIndex = frameIndex,
            payload = payload,
            previousP010Payload = previousP010Payload,
            width = fingerprint.width,
            height = fingerprint.height,
            signalFamily = fingerprint.signalFamily,
            detailProfile = fingerprint.detailProfile,
            sourceSceneAnalysisEnabled = fingerprint.sourceSceneAnalysisEnabled,
            sharpnessScale = fingerprint.sharpnessScale,
            denoiseScale = fingerprint.denoiseScale,
            saturation = saturation,
            warmth = warmth
        )
        sink?.append(instruction)
        return instruction
    }

    fun commit(convertedFrameCount: Long): Boolean {
        val source = cachedSource
        if (source != null) {
            val complete = convertedFrameCount == fingerprint.frameCount.toLong() &&
                source.consumedFrameCount == fingerprint.frameCount
            source.close()
            cachedSource = null
            return complete
        }
        val active = sink ?: return false
        return active.commit(convertedFrameCount).also { sink = null }
    }

    fun abort() {
        cachedSource?.close()
        cachedSource = null
        sink?.abort()
        sink = null
    }

    companion object {
        fun cacheHit(
            fingerprint: NeoQualityInstructionCache.Fingerprint,
            source: NeoQualityInstructionCache.CachedSource
        ) = NeoQualityInstructionSession(fingerprint, source, null)

        fun cacheMiss(
            fingerprint: NeoQualityInstructionCache.Fingerprint,
            cacheFile: File
        ) = NeoQualityInstructionSession(
            fingerprint = fingerprint,
            cachedSource = null,
            sink = NeoQualityInstructionCache.Sink.create(cacheFile, fingerprint)
        )

        fun disabled(fingerprint: NeoQualityInstructionCache.Fingerprint) =
            NeoQualityInstructionSession(fingerprint, null, null)
    }
}
