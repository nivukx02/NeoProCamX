package com.neoproc.neoproccamx.recording

import android.content.Context
import android.net.Uri
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream

/** Small, private, transaction-owned copy of a scoped-storage audio companion, never of the RAW video. */
internal class RawClipAudioSource private constructor(val master: File?) : Closeable {
    fun requireTimeline(firstSensorTimestampNs: Long, lastSensorTimestampNs: Long, fps: Int) {
        val anchor = master ?: return
        Npv10AudioSidecarReader(anchor).use { reader ->
            while (reader.readNextPacket() != null) { /* Verify the full persisted timeline. */ }
            val audit = reader.requireAudit()
            RawAudioTimelinePolicy.requireAssociation(
                firstSensorTimestampNs, lastSensorTimestampNs, fps,
                reader.header.videoOriginSensorTimestampNs, audit.finalPresentationTimeUs
            )
        }
    }

    override fun close() {
        val anchor = master ?: return
        check(Npv10AudioSidecarPolicy.deleteBundleAudio(anchor)) { "RAW audio staging cleanup failed" }
        check(!anchor.exists() || anchor.delete()) { "RAW audio staging anchor cleanup failed" }
    }

    companion object {
        fun open(context: Context, uri: Uri?, required: Boolean): RawClipAudioSource {
            if (uri == null) {
                check(!required) { "Required RAW microphone sidecar is missing; refusing silent conversion" }
                return RawClipAudioSource(null)
            }
            val directory = File(context.cacheDir, "raw_audio_conversion")
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create RAW audio staging" }
            val staged = RawClipAudioSource(File.createTempFile("raw-audio-", ".npraw", directory))
            try {
                val anchor = requireNotNull(staged.master)
                FileOutputStream(Npv10AudioSidecarPolicy.sidecarFor(anchor)).use { output ->
                    context.contentResolver.openInputStream(uri)?.use { input -> input.copyTo(output) }
                        ?: error("Required RAW audio cannot be read")
                    output.fd.sync()
                }
                Npv10AudioSidecarReader(anchor).use { reader ->
                    while (reader.readNextPacket() != null) { /* Check every captured packet. */ }
                    val audit = reader.requireAudit()
                    check(audit.packetCount > 0 && audit.crcValidated && audit.monotonicPts) {
                        "RAW audio companion failed its independent read audit"
                    }
                }
                return staged
            } catch (failure: Throwable) {
                runCatching { staged.close() }.onFailure(failure::addSuppressed)
                throw failure
            }
        }
    }
}
