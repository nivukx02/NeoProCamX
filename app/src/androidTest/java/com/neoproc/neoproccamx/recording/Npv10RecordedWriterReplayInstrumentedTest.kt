package com.neoproc.neoproccamx.recording

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Physical storage isolation, not a substitute for a real five-minute camera test. */
@RunWith(AndroidJUnit4::class)
class Npv10RecordedWriterReplayInstrumentedTest {
    @Test(timeout = 900_000L)
    fun realRecordedPacketsThroughProductionWriterWithoutCamera() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val relative = requireNotNull(args.getString("neoNpv10MasterRelativePath"))
        val frames = requireNotNull(args.getString("neoWriterReplayFrames")).toInt()
        val backend = args.getString("neoWriterReplayBackend") ?: "production"
        require(backend == "production" || backend == "streaming" || backend == "streaming8" ||
            backend == "pipelined" || backend == "single_completion" || backend == "batched_completion")
        require(frames in 25..7500)
        val root = File(context.filesDir, "CamXNPV10").canonicalFile
        val master = File(context.filesDir, relative).canonicalFile
        require(master.parentFile == root && master.extension == "npv10" && master.isFile)
        val beforeSize = master.length()
        val beforeModified = master.lastModified()
        val intent = Intent().setClassName(context.packageName,
            "com.neoproc.neoproccamx.testing.Lq031KeepAliveActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<Activity>(intent).use {
            val packets = Npv10FileReader(master).use { reader ->
                assertEquals(25, reader.header.fps)
                assertEquals(10, reader.header.storageBitDepth)
                val count = reader.countFramesForPlayback()
                require(count >= 3)
                val capacity = Npv10PacketCodecEngine.storedBytes(
                    reader.header.width.toLong() * reader.header.height * 3L,
                    reader.header.packetCodec).toInt()
                val scratch = ByteBuffer.allocateDirect(capacity)
                intArrayOf(0, count / 2, count - 1).map { index ->
                    reader.seekToFrameForPlayback(index)
                    val stored = requireNotNull(reader.readNextStoredFrameInto(scratch))
                    val bytes = ByteArray(stored.storedPayloadBytes)
                    stored.payload.duplicate().get(bytes)
                    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                        .joinToString("") { "%02x".format(it) }
                    Log.i(TAG, "REAL_RECORDED_PACKET index=$index bytes=${bytes.size} " +
                        "sha256=$digest crc_verified=true master=${master.name}")
                    ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); flip() }
                }.toTypedArray()
            }
            val directory = File(context.filesDir, "qa-recorded-writer-${System.nanoTime()}")
            check(!directory.exists() && directory.mkdirs())
            try {
                val depth = if (backend == "batched_completion") 64 else if (backend == "single_completion") 32
                    else if (backend == "pipelined") 16 else if (backend == "streaming8") 8
                    else if (backend == "streaming") 4 else 0
                val report = nativeReplay(directory.canonicalPath, packets, frames, depth)
                Log.i(TAG, report)
                assertTrue(report, report.startsWith("REPLAY_RESULT "))
                assertTrue(report, report.contains("all_bytes_exact=true full_file_readback=true"))
                assertTrue("Replay leaked temporary files", directory.list()?.isEmpty() == true)
                val overBudget = Regex("writes_above_40ms=(\\d+)").find(report)
                    ?.groupValues?.get(1)?.toInt()
                assertEquals("Real storage exceeded the unchanged 40-ms gate: $report", 0, overBudget)
            } finally {
                assertTrue("Only the empty owned directory may be removed", directory.delete())
            }
        }
        assertEquals("Replay modified original extent", beforeSize, master.length())
        assertEquals("Replay modified original timestamp", beforeModified, master.lastModified())
    }

    companion object {
        private const val TAG = "Npv10WriterReplay"
        init { System.loadLibrary("neoproc_storage_qa") }
        @JvmStatic private external fun nativeReplay(
            directory: String, packets: Array<ByteBuffer>, frames: Int, depth: Int
        ): String
    }
}
