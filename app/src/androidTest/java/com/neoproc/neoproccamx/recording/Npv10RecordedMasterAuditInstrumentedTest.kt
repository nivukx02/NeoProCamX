package com.neoproc.neoproccamx.recording

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.os.Bundle
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only diagnosis of a named physical-camera master, never generated/simulated frames. */
@RunWith(AndroidJUnit4::class)
class Npv10RecordedMasterAuditInstrumentedTest {
    /** Preserve exact decoded sensor codes from actual first/middle/last recorded frames. */
    @Test
    fun retainedRealMasterExportsThreeBitExactReviewFrames() = withForegroundHost { master ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expectedFrames = requireNotNull(InstrumentationRegistry.getArguments()
            .getString("neoNpv10ExpectedFrames")).toInt()
        require(expectedFrames > 2)
        val beforeLength = master.length()
        val beforeModified = master.lastModified()
        val directory = File(context.filesDir,
            "qa-recorded-frame-audits/${master.nameWithoutExtension}-${SystemClock.elapsedRealtimeNanos()}")
        check(!directory.exists() && directory.mkdirs()) { "Review evidence must not overwrite an older run" }
        Npv10FileReader(master).use { reader ->
            assertEquals(Npv10PayloadFormat.P010_LE, reader.header.payloadFormat)
            assertTrue(reader.header.sourceBitDepth >= 10)
            assertEquals(expectedFrames, reader.countFramesForPlayback())
            for ((name, index) in listOf("first" to 0, "middle" to expectedFrames / 2, "last" to expectedFrames - 1)) {
                assertEquals(index, reader.seekToFrameForPlayback(index))
                val frame = requireNotNull(reader.readNextFrame())
                val output = File(directory, "$name.p010")
                FileOutputStream(output).use { stream ->
                    stream.write(frame.payload)
                    stream.fd.sync()
                }
                assertEquals(frame.payload.size.toLong(), output.length())
                val sha = MessageDigest.getInstance("SHA-256").digest(frame.payload).joinToString("") {
                    "%02x".format(Locale.ROOT, it.toInt() and 0xFF)
                }
                emitOriginalProof("ACTUAL_RECORDED_SAMPLE name=$name index=$index " +
                    "timestampNs=${frame.timestampNs} width=${frame.width} height=${frame.height} " +
                    "orientation=${reader.header.orientationHintDegrees} format=P010_LE " +
                    "bytes=${frame.payload.size} sha256=$sha " +
                    "path=${output.relativeTo(context.filesDir).invariantSeparatorsPath} " +
                    "master=${master.name} scope=EXACT_DECODED_RECORDED_CODES")
            }
        }
        assertEquals(beforeLength, master.length())
        assertEquals(beforeModified, master.lastModified())
    }

    /** Independent exact on-disk timeline; full payload CRC/SHA remains a separate mandatory audit. */
    @Test
    fun retainedRealMasterFrameHeadersProveSelectedCadence() = withForegroundHost { master ->
        val expectedFrames = requireNotNull(InstrumentationRegistry.getArguments()
            .getString("neoNpv10ExpectedFrames")).toLong()
        val (header, firstFrameOffset) = Npv10FileReader(master).use { it.header to it.bytePosition }
        require(expectedFrames > 1L)
        assertEquals(25, header.fps)
        val originalBytes = master.length()
        val originalModified = master.lastModified()
        val frameHeader = ByteArray(28)
        val headerDigest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        var firstTimestamp = 0L
        var lastTimestamp = 0L
        var largestInterval = 0L
        var minimumInterval = Long.MAX_VALUE
        var intervalsOverOneAndHalfPeriods = 0L
        var storedBytes = 0L
        RandomAccessFile(master, "r").use { source ->
            source.seek(firstFrameOffset)
            while (source.filePointer < originalBytes) {
                source.readFully(frameHeader)
                headerDigest.update(frameHeader)
                val fields = ByteBuffer.wrap(frameHeader).order(ByteOrder.BIG_ENDIAN)
                val magic = ByteArray(4).also(fields::get)
                check(magic.contentEquals(Npv10ContainerPolicy.FRAME_MAGIC_BYTES))
                val timestamp = fields.long
                assertEquals(header.width, fields.int)
                assertEquals(header.height, fields.int)
                val payloadBytes = fields.int
                fields.int // Stored CRC is validated by the complete production integrity audit.
                assertTrue(payloadBytes > 0 && timestamp > 0L)
                if (count == 0L) firstTimestamp = timestamp else {
                    val interval = timestamp - lastTimestamp
                    assertTrue("Frame $count has non-monotonic sensor time", interval > 0L)
                    largestInterval = maxOf(largestInterval, interval)
                    minimumInterval = minOf(minimumInterval, interval)
                    if (interval > 1_500_000_000L / header.fps) {
                        intervalsOverOneAndHalfPeriods++
                    }
                }
                lastTimestamp = timestamp
                storedBytes += payloadBytes
                val next = Math.addExact(source.filePointer, payloadBytes.toLong())
                assertTrue("Frame $count is truncated", next <= originalBytes)
                source.seek(next)
                count++
            }
            assertEquals(originalBytes, source.filePointer)
        }
        val measuredFps = (count - 1L) * 1e9 / (lastTimestamp - firstTimestamp).toDouble()
        val headersSha = headerDigest.digest().joinToString("") {
            "%02x".format(Locale.ROOT, it.toInt() and 0xFF)
        }
        emitOriginalProof("ACTUAL_COMPLETE_FRAME_TIMELINE frames=$count fps=$measuredFps " +
            "selectedFps=${header.fps} firstTimestampNs=$firstTimestamp lastTimestampNs=$lastTimestamp " +
            "minimumIntervalNs=$minimumInterval maximumIntervalNs=$largestInterval " +
            "intervalsAbove1p5FramePeriods=$intervalsOverOneAndHalfPeriods storedBytes=$storedBytes " +
            "headerSha256=$headersSha sourceBytes=$originalBytes scope=ALL_FRAME_HEADERS " +
            "payloadIntegrity=REQUIRES_SEPARATE_FULL_AUDIT master=${master.name}")
        assertEquals(originalBytes, master.length())
        assertEquals(originalModified, master.lastModified())
        assertEquals(expectedFrames, count)
        assertTrue(count > 1L && measuredFps.isFinite())
        assertEquals(25, header.fps)
        assertEquals(header.fps.toDouble(), measuredFps, header.fps * 0.005)
        assertEquals("Recorded timeline contains an actual frame gap", 0L, intervalsOverOneAndHalfPeriods)
    }

    @Test
    fun retainedRealFramesIdentifyReadDecodeAndHashCost() = withForegroundHost { master ->
        var readNs = 0L
        var decodeNs = 0L
        var hashNs = 0L
        var storedBytes = 0L
        var frames = 0
        val digest = MessageDigest.getInstance("SHA-256")
        Npv10FileReader(master).use { reader ->
            assertEquals(10, reader.header.bitDepth)
            assertEquals(25, reader.header.fps)
            assertEquals(Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2, reader.header.packetCodec)
            val decodedBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
                reader.header.width, reader.header.height, reader.header.payloadFormat
            ).toInt()
            val packet = ByteBuffer.allocateDirect(Npv10PacketCodecEngine.storedBytes(
                decodedBytes.toLong(), reader.header.packetCodec
            ).toInt())
            val decoded = ByteBuffer.allocateDirect(decodedBytes)
            repeat(DIAGNOSTIC_FRAMES) {
                val readStart = SystemClock.elapsedRealtimeNanos()
                val frame = requireNotNull(reader.readNextStoredFrameInto(packet)) {
                    "Retained master does not contain the required diagnostic window"
                }
                readNs += SystemClock.elapsedRealtimeNanos() - readStart
                val decodeStart = SystemClock.elapsedRealtimeNanos()
                assertTrue("Actual stored frame $frames failed native source-CRC validation",
                    Npv10PacketCodecEngine.decodeChunkedLosslessNativeForTest(
                        frame.payload, frame.storedPayloadBytes, decodedBytes, decoded
                    ))
                decodeNs += SystemClock.elapsedRealtimeNanos() - decodeStart
                val hashStart = SystemClock.elapsedRealtimeNanos()
                digest.update(frame.payload.duplicate())
                hashNs += SystemClock.elapsedRealtimeNanos() - hashStart
                storedBytes += frame.storedPayloadBytes
                frames++
                if (frames % 16 == 0) Log.i(TAG, "ACTUAL_FRAME_DIAGNOSTIC frames=$frames " +
                    "readAndStoredCrcMs=${readNs / 1e6} nativeDecodeAndSourceCrcMs=${decodeNs / 1e6} " +
                    "sha256Ms=${hashNs / 1e6} storedBytes=$storedBytes scope=PREFIX_ONLY")
            }
        }
        assertEquals(DIAGNOSTIC_FRAMES, frames)
        assertEquals(32, digest.digest().size)
        Log.i(TAG, "ACTUAL_FRAME_DIAGNOSTIC_COMPLETE frames=$frames " +
            "readAndStoredCrcMs=${readNs / 1e6} nativeDecodeAndSourceCrcMs=${decodeNs / 1e6} " +
            "sha256Ms=${hashNs / 1e6} scope=PREFIX_ONLY notMatrixPass=true master=${master.name}")
    }

    @Test
    fun retainedRealMasterPassesCompleteProductionAudit() = withForegroundHost { master ->
        val arguments = InstrumentationRegistry.getArguments()
        val expectedFrames = requireNotNull(arguments.getString("neoNpv10ExpectedFrames")).toLong()
        val expectedHash = arguments.getString("neoNpv10ExpectedSessionHash")
        val decodedBytes = Npv10FileReader(master).use { reader ->
            Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
                reader.header.width, reader.header.height, reader.header.payloadFormat
            )
        }
        require(expectedFrames > 0)
        val beforeLength = master.length()
        val beforeModified = master.lastModified()
        val began = SystemClock.elapsedRealtimeNanos()
        val audit = Npv10QualityGate.auditMasterFile(
            master, expectedFrames, Math.multiplyExact(decodedBytes, expectedFrames), expectedHash
        )
        Log.i(TAG, "ACTUAL_COMPLETE_MASTER_AUDIT elapsedMs=" +
            "${(SystemClock.elapsedRealtimeNanos() - began) / 1e6} result=$audit master=${master.name}")
        assertEquals("Read-only audit changed source extent", beforeLength, master.length())
        assertEquals("Read-only audit changed source modification time", beforeModified, master.lastModified())
        assertEquals(audit.failureReasons.joinToString(), Npv10AuditStatus.PASS, audit.auditStatus)
        assertTrue(audit.crcValidated && audit.sessionHashCheckPass)
    }

    private fun emitOriginalProof(message: String) {
        Log.i(TAG, message)
        // Preserve the same real-file proof on instrumentation's separate output channel even
        // when a vendor log filter or bounded logcat ring drops it. No fabricated replacement.
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("neoNpv10OriginalProof", message)
        })
    }

    private fun withForegroundHost(block: (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val relative = requireNotNull(InstrumentationRegistry.getArguments()
            .getString("neoNpv10MasterRelativePath")) { "An exact real recorded master is required" }
        val root = File(context.filesDir, "CamXNPV10").canonicalFile
        val master = File(context.filesDir, relative).canonicalFile
        require(master.parentFile == root && master.extension == "npv10" && master.isFile) {
            "Only an existing directly contained private camera master may be inspected"
        }
        val intent = Intent().setClassName(context.packageName,
            "com.neoproc.neoproccamx.testing.Lq031KeepAliveActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<Activity>(intent).use { block(master) }
    }

    companion object {
        private const val TAG = "Npv10ActualMasterAudit"
        private const val DIAGNOSTIC_FRAMES = 96
    }
}
