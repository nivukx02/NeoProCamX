package com.neoproc.neoproccamx.pipeline

/**
 * Owns the allocation-free admission contract for a realtime RAW packet.
 *
 * There is intentionally no heap-encoder callback: buffer exhaustion or loss of the native in-place
 * encoder is a hard recording error, never permission to allocate one full sensor frame on the ART heap.
 */
internal object RealtimeRawPacketAdmission {
    data class Prepared<B, R>(val buffer: B, val result: R)

    fun <B, R : Any> acquireAndEncode(
        acquireBuffer: () -> B?,
        encodeInto: (B) -> R?,
        recycleBuffer: (B) -> Unit
    ): Prepared<B, R> {
        val buffer = acquireBuffer()
            ?: throw RealtimeRawPacketUnavailableException(
                "Timed out waiting for a reusable realtime NPRAW packet buffer"
            )
        return try {
            val result = encodeInto(buffer)
                ?: throw RealtimeRawPacketUnavailableException(
                    "Native in-place realtime NPRAW encoder is unavailable"
                )
            Prepared(buffer = buffer, result = result)
        } catch (failure: Throwable) {
            try {
                recycleBuffer(buffer)
            } catch (recycleFailure: Throwable) {
                failure.addSuppressed(recycleFailure)
            }
            throw failure
        }
    }
}

internal class RealtimeRawPacketUnavailableException(message: String) : IllegalStateException(message)
