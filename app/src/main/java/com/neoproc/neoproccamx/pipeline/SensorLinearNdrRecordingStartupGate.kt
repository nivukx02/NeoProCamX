package com.neoproc.neoproccamx.pipeline

/**
 * One-shot readiness transaction for the RAW_SENSOR recording cutover.
 *
 * A transaction snapshots the newest exact idle RAW/result pair before Camera2 changes request or
 * session ownership. A readiness lease can be issued only for a strictly newer exact pair from the
 * final session. The lease is then consumed exactly once by ordered recording ingress, preventing a
 * stale preview result, a superseded start, or an ABA session transition from opening a clip.
 */
internal class SensorLinearNdrRecordingStartupGate {
    data class ExactPairProof(
        val sessionGeneration: Long,
        val sensorTimestampNs: Long
    )

    class Transaction internal constructor(
        internal val id: Long,
        internal val baselineSessionGeneration: Long,
        internal val baselineSensorTimestampNs: Long
    )

    class ReadinessLease internal constructor(
        internal val transaction: Transaction,
        val proof: ExactPairProof
    )

    private val lock = Any()
    private var nextTransactionId = 0L
    private var activeTransaction: Transaction? = null
    private var latestExactPair: ExactPairProof? = null

    fun publishExactPair(sessionGeneration: Long, sensorTimestampNs: Long): Boolean {
        if (sessionGeneration < 0L || sensorTimestampNs <= 0L) return false
        return synchronized(lock) {
            val current = latestExactPair
            val newer = current == null ||
                sessionGeneration > current.sessionGeneration ||
                (sessionGeneration == current.sessionGeneration &&
                    sensorTimestampNs > current.sensorTimestampNs)
            if (newer) {
                latestExactPair = ExactPairProof(sessionGeneration, sensorTimestampNs)
            }
            newer
        }
    }

    fun begin(
        baselineSessionGeneration: Long,
        baselineSensorTimestampNs: Long = 0L
    ): Transaction? {
        if (baselineSessionGeneration < 0L) return null
        return synchronized(lock) {
            nextTransactionId = if (nextTransactionId == Long.MAX_VALUE) 1L else nextTransactionId + 1L
            val exactPairBaselineNs = latestExactPair
                ?.takeIf { it.sessionGeneration == baselineSessionGeneration }
                ?.sensorTimestampNs
                ?: 0L
            Transaction(
                id = nextTransactionId,
                baselineSessionGeneration = baselineSessionGeneration,
                baselineSensorTimestampNs = maxOf(
                    baselineSensorTimestampNs.coerceAtLeast(0L),
                    exactPairBaselineNs
                )
            ).also { activeTransaction = it }
        }
    }

    fun tryAcquire(
        transaction: Transaction,
        expectedSessionGeneration: Long,
        minimumSensorTimestampNs: Long = 1L
    ): ReadinessLease? = synchronized(lock) {
        if (activeTransaction !== transaction || expectedSessionGeneration < 0L ||
            expectedSessionGeneration < transaction.baselineSessionGeneration
        ) return@synchronized null

        val proof = latestExactPair ?: return@synchronized null
        if (proof.sessionGeneration != expectedSessionGeneration) return@synchronized null
        if (minimumSensorTimestampNs <= 0L ||
            proof.sensorTimestampNs < minimumSensorTimestampNs
        ) return@synchronized null
        val isPostSwitch =
            expectedSessionGeneration != transaction.baselineSessionGeneration ||
                proof.sensorTimestampNs > transaction.baselineSensorTimestampNs
        if (!isPostSwitch) return@synchronized null
        ReadinessLease(transaction, proof)
    }

    fun consume(lease: ReadinessLease, currentSessionGeneration: Long): Boolean =
        synchronized(lock) {
            if (activeTransaction !== lease.transaction ||
                lease.proof.sessionGeneration != currentSessionGeneration
            ) return@synchronized false
            activeTransaction = null
            true
        }

    fun cancel(transaction: Transaction): Boolean = synchronized(lock) {
        if (activeTransaction !== transaction) return@synchronized false
        activeTransaction = null
        true
    }

    fun cancel(lease: ReadinessLease): Boolean = cancel(lease.transaction)

    fun cancelActive() = synchronized(lock) {
        activeTransaction = null
    }
}
