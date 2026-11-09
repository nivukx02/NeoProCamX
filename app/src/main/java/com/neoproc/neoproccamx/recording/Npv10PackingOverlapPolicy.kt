package com.neoproc.neoproccamx.recording

/** Scheduling only: the packet format, pixel operations and ownership ceiling are unchanged. */
internal object Npv10PackingOverlapPolicy {
    const val MAX_DISPATCH_WORKERS = 2

    fun dispatchWorkers(packetCodec: Npv10PacketCodec): Int =
        if (packetCodec == Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2) {
            // The shared native row engine releases its submission lock before slot-local LZ4.
            // A second submitter can pack the next frame during that independent entropy stage.
            // More submitters would compete for the same row helpers and memory bandwidth.
            MAX_DISPATCH_WORKERS
        } else {
            1
        }
}
