package com.neoproc.neoproccamx.recording

/** Actual native completions, not an inferred GPU/FPS or image-quality measurement. */
data class Npv10KernelAioTelemetry(
    val enabled: Boolean = false,
    val admissionErrno: Int = 0,
    val submittedRequests: Long = 0L,
    val completedRequests: Long = 0L,
    val ownedRecordWrites: Long = 0L,
    val ownedPayloadBytes: Long = 0L,
    val immutableStreamRecordWrites: Long = 0L
) {
    val allSubmittedRequestsCompleted: Boolean
        get() = enabled && admissionErrno == 0 && submittedRequests > 0L &&
            completedRequests == submittedRequests

    fun toMetadata(): Map<String, String> = mapOf(
        "npv10DmcKernelAioEnabled" to enabled.toString(),
        "npv10DmcKernelAioAdmissionErrno" to admissionErrno.toString(),
        "npv10DmcKernelAioSubmittedRequests" to submittedRequests.toString(),
        "npv10DmcKernelAioCompletedRequests" to completedRequests.toString(),
        "npv10DmcKernelAioAllSubmittedCompleted" to allSubmittedRequestsCompleted.toString(),
        "npv10DmcKernelAioMaximumRequests" to "4",
        "npv10DmcKernelAioOwnership" to if (ownedRecordWrites > 0L)
            "SINGLE_OWNED_PACKET_COMPLETE_BEFORE_RECORD_RETURN"
        else "SINGLE_STAGING_RANGE_COMPLETE_BEFORE_RECORD_RETURN",
        "npv10DmcOwnedRecordWrites" to ownedRecordWrites.toString(),
        "npv10DmcOwnedPayloadBytes" to ownedPayloadBytes.toString(),
        "npv10DmcImmutableStreamRecordWrites" to immutableStreamRecordWrites.toString()
    )

    companion object {
        fun fromNative(values: LongArray?): Npv10KernelAioTelemetry = Npv10KernelAioTelemetry(
            enabled = values?.getOrNull(48) == 1L,
            admissionErrno = values?.getOrNull(49)?.toInt() ?: 0,
            submittedRequests = values?.getOrNull(50) ?: 0L,
            completedRequests = values?.getOrNull(51) ?: 0L,
            ownedRecordWrites = values?.getOrNull(52) ?: 0L,
            ownedPayloadBytes = values?.getOrNull(53) ?: 0L,
            immutableStreamRecordWrites = values?.getOrNull(54) ?: 0L
        )
    }
}
