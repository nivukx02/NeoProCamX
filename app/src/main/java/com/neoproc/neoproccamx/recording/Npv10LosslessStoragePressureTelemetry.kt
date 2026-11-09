package com.neoproc.neoproccamx.recording

/** Measured lossless effort and bytes; never interpreted as recording/colour/FPS success. */
data class Npv10LosslessStoragePressureTelemetry(
    val enabled: Boolean = false,
    val active: Boolean = false,
    val entries: Long = 0L,
    val fastFrames: Long = 0L,
    val pressureFrames: Long = 0L,
    val cpuBackoffs: Long = 0L,
    val fastEncodeNs: Long = 0L,
    val pressureEncodeNs: Long = 0L,
    val pressureMaxEncodeNs: Long = 0L,
    val fastInputBytes: Long = 0L,
    val fastStoredBytes: Long = 0L,
    val pressureInputBytes: Long = 0L,
    val pressureStoredBytes: Long = 0L,
    val sourceIntervalNs: Long = 0L,
    val failures: Long = 0L,
    val cooldownFrames: Long = 0L
) {
    fun toEvidenceMetadata(): Map<String, String> = linkedMapOf(
        "npv10LosslessPressureEnabled" to enabled.toString(),
        "npv10LosslessPressureActive" to active.toString(),
        "npv10LosslessPressureEntries" to entries.toString(),
        "npv10LosslessFastFrames" to fastFrames.toString(),
        "npv10LosslessPressureFrames" to pressureFrames.toString(),
        "npv10LosslessPressureCpuBackoffs" to cpuBackoffs.toString(),
        "npv10LosslessFastEncodeNs" to fastEncodeNs.toString(),
        "npv10LosslessPressureEncodeNs" to pressureEncodeNs.toString(),
        "npv10LosslessPressureMaxEncodeNs" to pressureMaxEncodeNs.toString(),
        "npv10LosslessFastInputBytes" to fastInputBytes.toString(),
        "npv10LosslessFastStoredBytes" to fastStoredBytes.toString(),
        "npv10LosslessPressureInputBytes" to pressureInputBytes.toString(),
        "npv10LosslessPressureStoredBytes" to pressureStoredBytes.toString(),
        "npv10LosslessPressureSourceIntervalNs" to sourceIntervalNs.toString(),
        "npv10LosslessPressureFailures" to failures.toString(),
        "npv10LosslessPressureCooldownFrames" to cooldownFrames.toString()
    )

    companion object {
        /** Older native snapshots remain readable without inventing new measurements. */
        fun fromNative(values: LongArray?): Npv10LosslessStoragePressureTelemetry {
            if (values == null || values.size < 48) return Npv10LosslessStoragePressureTelemetry()
            return Npv10LosslessStoragePressureTelemetry(
                enabled = values[32] == 1L,
                active = values[33] == 1L,
                entries = values[34],
                fastFrames = values[35],
                pressureFrames = values[36],
                cpuBackoffs = values[37],
                fastEncodeNs = values[38],
                pressureEncodeNs = values[39],
                pressureMaxEncodeNs = values[40],
                fastInputBytes = values[41],
                fastStoredBytes = values[42],
                pressureInputBytes = values[43],
                pressureStoredBytes = values[44],
                sourceIntervalNs = values[45],
                failures = values[46],
                cooldownFrames = values[47]
            )
        }
    }
}
