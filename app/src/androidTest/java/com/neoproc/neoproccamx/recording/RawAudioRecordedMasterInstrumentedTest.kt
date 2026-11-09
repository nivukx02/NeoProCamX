package com.neoproc.neoproccamx.recording

import android.media.MediaCodec
import android.media.MediaFormat
import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.neoproc.neoproccamx.pipeline.NprawFrameCodec
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Inspect an explicitly named, real Camera2 recording. No generated RAW frames or audio fixtures. */
@RunWith(AndroidJUnit4::class)
class RawAudioRecordedMasterInstrumentedTest {
    @Test
    fun recordedRawMasterOwnsDecodableSynchronizedAudio() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Vivo can freeze every headless instrumentation thread after ten seconds. Reuse the
        // existing debug-only foreground host; it opens no camera/codec and changes no quality policy.
        val intent = Intent().setClassName(context.packageName,
            "com.neoproc.neoproccamx.testing.Lq031KeepAliveActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<Activity>(intent).use { verifyRecordedMaster() }
    }

    private fun verifyRecordedMaster() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val name = requireNotNull(args.getString("neoRawAudioClipName")) {
            "An exact retained real-device RAW clip name is required"
        }
        val clip = requireNotNull(RawClipMediaStoreDiscovery(context).listClips().singleOrNull {
            it.name == name
        }) { "The requested RAW recording is absent: $name" }
        assertFalse("RAW stream was not published", clip.streamPending)
        val metadata = context.contentResolver.openInputStream(clip.metaUri)!!.use {
            RawDevelopMetadataSidecar.decode(it.readBytes())
        }
        assertTrue("New microphone-enabled RAW metadata did not retain its audio obligation", metadata.audioExpected)
        assertEquals(25, metadata.fps)
        val firstFrame = context.contentResolver.openInputStream(clip.streamUri)!!.buffered().use {
            requireNotNull(NprawFrameCodec.readFrame(it))
        }
        assertEquals(metadata.width, firstFrame.width)
        assertEquals(metadata.height, firstFrame.height)
        RawClipAudioSource.open(context, clip.audioUri, required = true).use { staged ->
            val master = requireNotNull(staged.master)
            Npv10AudioSidecarReader(master).use { reader ->
                assertEquals(firstFrame.timestamp, reader.header.videoOriginSensorTimestampNs)
                assertEquals(48_000, reader.header.sampleRate)
                assertEquals(2, reader.header.channelCount)
                assertEquals(256_000, reader.header.bitRate)
            }
            val decodedBytes = decodeCapturedAac(master)
            assertTrue("The recorded AAC sidecar produced no PCM", decodedBytes > 0L)
            val audit = Npv10AudioSidecarReader(master).use { reader ->
                while (reader.readNextPacket() != null) { /* Validate complete captured file. */ }
                reader.requireAudit()
            }
            Log.i(TAG, "RAW_AUDIO_DEVICE_AUDIT clip=$name width=${metadata.width} height=${metadata.height} " +
                "fps=${metadata.fps} packets=${audit.packetCount} pcmBytes=$decodedBytes " +
                "audioEndUs=${audit.finalPresentationTimeUs} crc=${audit.crcValidated} " +
                "origin=${firstFrame.timestamp}")
            if (args.getString("neoRawAudioConvert") == "true") {
                val output = File(requireNotNull(context.getExternalFilesDir("lq001-raw-audio")),
                    "${name}_audio_neolog_${SystemClock.elapsedRealtime()}.mov")
                val result = context.contentResolver.openInputStream(clip.streamUri)!!.buffered().use { input ->
                    Raw12ClipConversionCoordinator().convert(
                        streamInput = input, meta = metadata,
                        options = RawToNeoLogMovConverter.Options(
                            format = RawToNeoLogMovConverter.OutputFormat.HEVC_MAIN10_420
                        ),
                        outputPath = output.absolutePath,
                        onFrame = { frames -> if (frames % 100 == 0) Log.i(TAG, "RAW_AUDIO_CONVERT frames=$frames") },
                        audioSidecarMaster = master
                    )
                }
                assertTrue(result.framesConverted > 0)
                assertTrue(output.isFile && output.length() > 0L)
                Log.i(TAG, "RAW_AUDIO_CONVERT_PASS output=${output.absolutePath} frames=${result.framesConverted}")
            }
        }
    }

    private fun decodeCapturedAac(master: File): Long = Npv10AudioSidecarReader(master).use { reader ->
        val owner = Thread.currentThread()
        val finished = AtomicBoolean(false)
        val watchdog = Thread({
            try {
                while (!finished.get()) {
                    Thread.sleep(15_000L)
                    if (!finished.get()) Log.i(TAG, "RAW_AAC_DECODER_PROGRESS_SAMPLE thread=${owner.name} " +
                        owner.stackTrace.joinToString(" <- "))
                }
            } catch (_: InterruptedException) { /* Decoder transaction completed. */ }
        }, "raw-aac-diagnostic-watchdog").apply { isDaemon = true }
        Log.i(TAG, "RAW_AAC_DECODER_CREATE")
        val decoder = MediaCodec.createDecoderByType(Npv10AudioSidecarPolicy.MIME)
        watchdog.start()
        var started = false
        try {
            decoder.configure(MediaFormat.createAudioFormat(Npv10AudioSidecarPolicy.MIME,
                reader.header.sampleRate, reader.header.channelCount).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(reader.header.codecConfig))
            }, null, null, 0)
            Log.i(TAG, "RAW_AAC_DECODER_CONFIGURED")
            decoder.start()
            Log.i(TAG, "RAW_AAC_DECODER_STARTED")
            started = true
            val info = MediaCodec.BufferInfo()
            var inputEos = false
            var outputEos = false
            var pcmBytes = 0L
            var latestInputPtsUs = 0L
            var packetsQueued = 0L
            var buffersDrained = 0L
            val deadline = SystemClock.elapsedRealtime() + 60_000L
            while (!outputEos && SystemClock.elapsedRealtime() < deadline) {
                if (!inputEos) {
                    val inputIndex = decoder.dequeueInputBuffer(2_000L)
                    if (inputIndex >= 0) {
                        val packet = reader.readNextPacket()
                        if (packet == null) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, latestInputPtsUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                            Log.i(TAG, "RAW_AAC_INPUT_EOS packets=$packetsQueued")
                        } else {
                            val input = requireNotNull(decoder.getInputBuffer(inputIndex)).apply { clear() }
                            check(input.remaining() >= packet.payload.size)
                            input.put(packet.payload)
                            decoder.queueInputBuffer(inputIndex, 0, packet.payload.size,
                                packet.presentationTimeUs, 0)
                            latestInputPtsUs = packet.presentationTimeUs
                            packetsQueued++
                            if (packetsQueued % 1000L == 0L) Log.i(TAG,
                                "RAW_AAC_PROGRESS queued=$packetsQueued drained=$buffersDrained pcmBytes=$pcmBytes")
                        }
                    }
                }
                val outputIndex = decoder.dequeueOutputBuffer(info, 2_000L)
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    assertEquals(48_000, decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    assertEquals(2, decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                } else if (outputIndex >= 0) {
                    try {
                        pcmBytes += info.size
                        buffersDrained++
                        outputEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally {
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            assertTrue("Captured AAC failed to decode through EOS", outputEos)
            Log.i(TAG, "RAW_AAC_OUTPUT_EOS packets=$packetsQueued buffers=$buffersDrained pcmBytes=$pcmBytes")
            assertTrue(reader.requireAudit().crcValidated)
            pcmBytes
        } finally {
            Log.i(TAG, "RAW_AAC_DECODER_STOPPING")
            if (started) runCatching { decoder.stop() }
            Log.i(TAG, "RAW_AAC_DECODER_RELEASING")
            decoder.release()
            finished.set(true)
            watchdog.interrupt()
            Log.i(TAG, "RAW_AAC_DECODER_RELEASED")
        }
    }

    private companion object { const val TAG = "RawAudioDeviceTest" }
}
