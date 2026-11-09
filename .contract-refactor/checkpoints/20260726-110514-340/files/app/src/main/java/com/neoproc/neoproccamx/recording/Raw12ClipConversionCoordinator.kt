package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata
import java.io.File
import java.io.InputStream

/**
 * Raw12ClipConversionCoordinator — drives a full offline RAW clip → 10-bit NeoLog .MOV conversion:
 * reads the develop.meta sidecar, streams `stream.npraw` through [RawToNeoLogMovConverter.convertStream],
 * and muxes each developed 10-bit sample into a QuickTime MOV via [QuickTimeMovFileWriter] (v210 4:2:2
 * or r210 4:4:4). Every uncompressed frame is a sync sample.
 *
 * [QuickTimeMovFileWriter] is pure RandomAccessFile, so this whole convert is JVM-testable end-to-end
 * (it produces a real .mov whose box structure can be asserted). The remaining device-runtime concerns
 * are: on-disk/MediaStore file access, MediaStore album publish, and MOV playback validation in NLEs
 * (the r210 sample-entry / colr tagging in particular should be checked on a device).
 */
class Raw12ClipConversionCoordinator(
    private val converter: RawToNeoLogMovConverter = RawToNeoLogMovConverter()
) {

    companion object {
        private const val CONTAINER_LABEL = "NeoProCamX NeoLog RAW develop"
        private const val SYNC_SAMPLE_FLAG = 1 // every uncompressed frame is a keyframe
    }

    /**
     * Convert a clip's decoded sidecar + open NPRAW stream into [outputPath] (.mov).
     *
     * [parallelism] > 1 develops frames across that many CPU worker threads (byte-identical output, only
     * faster); [onFrame] fires once per developed frame (in order) with the running count, so the caller
     * can drive a progress bar / ETA.
     */
    fun convert(
        streamInput: InputStream,
        meta: RawDevelopMetadata,
        options: RawToNeoLogMovConverter.Options,
        outputPath: String,
        parallelism: Int = 1,
        onFrame: ((framesDone: Int) -> Unit)? = null
    ): RawToNeoLogMovConverter.ConvertResult {
        val outputFile = File(outputPath)
        val writer = QuickTimeMovFileWriter(
            outputPath = outputPath,
            containerLabel = CONTAINER_LABEL,
            preallocationPlan = RecordingPreallocationPolicy.processedOutputFile(outputFile)
        )
        try {
            writer.setOrientationHint(meta.orientationHintDegrees)
            val fps = meta.fps.coerceAtLeast(1)
            val frameDurationUs = (1_000_000L / fps).coerceAtLeast(1L)
            val trackIndex = writer.addVideoTrack(
                QuickTimeVideoTrackConfig(
                    mime = options.format.movMime,
                    width = meta.width,
                    height = meta.height,
                    fps = fps,
                    codecConfig = ByteArray(0), // uncompressed: no codec-specific config
                    colorConfig = colorConfigFor(options.format)
                )
            )
            val sink: (ByteArray, Int) -> Unit = { sample, index ->
                writer.writeSample(
                    trackIndex,
                    QuickTimeSampleBuffer(
                        bytes = sample,
                        presentationTimeUs = index * frameDurationUs,
                        flags = SYNC_SAMPLE_FLAG
                    )
                )
            }
            val result = if (parallelism > 1) {
                converter.convertStreamParallel(streamInput, meta, options, parallelism, onFrame, sink)
            } else {
                converter.convertStream(streamInput, meta, options) { sample, index ->
                    sink(sample, index)
                    onFrame?.invoke(index + 1)
                }
            }
            // #21 fix: finalize (write the moov) ONLY on success. Doing it in a finally wrote a
            // playable-but-truncated master on a mid-clip failure, and dropped an orphan .cube next to it.
            writer.close()
            // Ship the professional companion viewing LUT next to the log master so the NeoLog(ACEScg)
            // .mov reads correctly in an NLE (drop the .cube on the clip). The master is never modified.
            NeoLogRec709LutWriter.writeSidecar(outputPath)
            return result
        } catch (t: Throwable) {
            // #21 fix: on failure abort() (close the file WITHOUT writing the moov, so no truncated master
            // is left) and skip the companion LUT; then rethrow so the caller reports a genuine failure.
            runCatching { writer.abort() }
            throw t
        }
    }

    /** Convenience: convert a [Raw12Clip] from disk (reads develop.meta, streams stream.npraw). */
    fun convert(
        clip: Raw12Clip,
        options: RawToNeoLogMovConverter.Options,
        outputPath: String,
        parallelism: Int = 1,
        onFrame: ((framesDone: Int) -> Unit)? = null
    ): RawToNeoLogMovConverter.ConvertResult {
        val meta = RawDevelopMetadataSidecar.decode(clip.metaFile.readBytes())
        return clip.streamFile.inputStream().buffered().use { input ->
            convert(input, meta, options, outputPath, parallelism, onFrame)
        }
    }

    // NeoLog is tagged BT.2020 primaries + BT.709 transfer (matches the NPV10 log color-tag policy).
    // v210 is Y'CbCr (limited range, BT.2020 NCL matrix); r210 is RGB (full range, RGB matrix).
    // The exact RGB tagging for r210 is device-verified against target NLEs.
    private fun colorConfigFor(format: RawToNeoLogMovConverter.OutputFormat): QuickTimeColorAtomConfig =
        when (format) {
            RawToNeoLogMovConverter.OutputFormat.MASTER_444 -> QuickTimeColorAtomConfig(
                colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_BT709,
                matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT709,
                fullRange = true
            )
            RawToNeoLogMovConverter.OutputFormat.COMPAT_V210 -> QuickTimeColorAtomConfig(
                colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_BT709,
                matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
                fullRange = false
            )
        }

    /** Where a converted clip's .mov should be staged before album publish (mirrors NPV10). */
    fun defaultOutputFile(clip: Raw12Clip, cacheDir: File, format: RawToNeoLogMovConverter.OutputFormat): File {
        val suffix = if (format == RawToNeoLogMovConverter.OutputFormat.MASTER_444) "444" else "v210"
        return File(cacheDir, "${clip.name}_neolog_$suffix.mov")
    }
}
