package com.neoproc.neoproccamx.recording

/**
 * Joins the writer's frame-1 fsync proof back to the exact Camera2 producer and sink handoff that
 * admitted recording. A merely positive timestamp is insufficient: the durable frame must be the
 * first frame owned by this sink binding and may never predate the proven repeating request.
 */
internal object Npv10FirstDurableFramePolicy {
    fun validate(
        proof: Npv10FirstDurableFrameProof,
        minimumProducerTimestampNs: Long,
        firstSinkTimestampNs: Long
    ): Npv10FirstDurableFrameProof {
        require(minimumProducerTimestampNs > 0L) { "Producer timestamp proof must be positive" }
        require(firstSinkTimestampNs > 0L) { "First NPV10 sink timestamp must be positive" }
        require(proof.frameCount == 1L) { "Frame-1 durability proof count was ${proof.frameCount}" }
        require(proof.committedBytes > 0L) { "Frame-1 durability proof contained no committed bytes" }
        require(proof.sensorTimestampNs >= minimumProducerTimestampNs) {
            "Durable frame predates producer proof: durable=${proof.sensorTimestampNs} " +
                "producer=$minimumProducerTimestampNs"
        }
        require(proof.sensorTimestampNs == firstSinkTimestampNs) {
            "Durable frame does not own the first sink timestamp: durable=${proof.sensorTimestampNs} " +
                "sink=$firstSinkTimestampNs"
        }
        return proof
    }
}
