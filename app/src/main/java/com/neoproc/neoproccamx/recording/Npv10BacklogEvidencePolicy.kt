package com.neoproc.neoproccamx.recording

/** Diagnostics only: no admission/quality threshold or recording setting is changed here. */
internal object Npv10BacklogEvidencePolicy {
    /**
     * Native commit returns after enqueueing. Dividing a raw-frame estimate by that enqueue time
     * invents storage bandwidth (the observed failure reported 65 GB/s). Use actual persisted
     * compressed bytes and native write-service time, or return unknown. This includes kernel
     * copying/waits but is deliberately NOT a sustained or durable-media bandwidth measurement.
     */
    fun nativeWriteServiceBytesPerSecond(stages: Npv10WriterStageTimings?): Long? {
        if (stages == null || !stages.asynchronousRecordWriterEnabled ||
            stages.asynchronouslyPersistedFrames <= 0L ||
            stages.asynchronouslyPersistedRecordBytes <= 0L ||
            !stages.averageAsynchronousRecordWriteMs.isFinite() ||
            stages.averageAsynchronousRecordWriteMs <= 0.0
        ) return null
        val elapsedMs = stages.averageAsynchronousRecordWriteMs * stages.asynchronouslyPersistedFrames
        val bytesPerSecond = stages.asynchronouslyPersistedRecordBytes.toDouble() * 1_000.0 / elapsedMs
        if (!elapsedMs.isFinite() || !bytesPerSecond.isFinite() || bytesPerSecond < 0.0 ||
            bytesPerSecond >= Long.MAX_VALUE.toDouble()
        ) return null
        return bytesPerSecond.toLong()
    }
}
