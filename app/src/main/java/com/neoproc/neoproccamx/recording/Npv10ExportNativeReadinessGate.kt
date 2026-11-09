package com.neoproc.neoproccamx.recording

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Fail-closed admission gate for the optional NPV10 Vulkan export engine.
 *
 * A vendor Vulkan implementation may block indefinitely while creating a device or compute pipeline,
 * before the native dispatch fence exists. Running the one-time readiness probe on a daemon thread and
 * bounding the caller's wait keeps conversion responsive. A failed or timed-out probe permanently
 * selects the existing full-quality CPU implementation for this process; it never changes pixels,
 * resolution, bit depth, bitrate, or colour metadata.
 */
internal class Npv10ExportNativeReadinessGate(
    private val timeoutMillis: Long,
    threadFactory: ThreadFactory = ThreadFactory { runnable ->
        Thread(runnable, "npv10-export-native-probe").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    },
    private val probe: () -> String
) {
    private enum class State { UNCHECKED, PROBING, READY, UNAVAILABLE }

    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor(threadFactory)

    @Volatile
    private var state = State.UNCHECKED

    @Volatile
    private var reason = NOT_PROBED

    private var future: Future<String>? = null

    init {
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
    }

    fun awaitReady(): Boolean {
        when (state) {
            State.READY -> return true
            State.UNAVAILABLE -> return false
            State.UNCHECKED,
            State.PROBING -> Unit
        }

        val activeFuture = synchronized(lock) {
            when (state) {
                State.READY -> return true
                State.UNAVAILABLE -> return false
                State.UNCHECKED -> {
                    state = State.PROBING
                    executor.submit<String> { probe() }.also { future = it }
                }
                State.PROBING -> checkNotNull(future)
            }
        }

        return try {
            val nativeStatus = activeFuture.get(timeoutMillis, TimeUnit.MILLISECONDS)
            if (nativeStatus.trim().equals(READY, ignoreCase = true)) {
                publishReady()
                true
            } else {
                publishUnavailable(nativeStatus.ifBlank { EMPTY_STATUS })
                false
            }
        } catch (_: TimeoutException) {
            publishUnavailable("NATIVE_INITIALIZATION_TIMEOUT_${timeoutMillis}MS")
            false
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            publishUnavailable("NATIVE_INITIALIZATION_INTERRUPTED")
            false
        } catch (failure: ExecutionException) {
            publishUnavailable(
                "NATIVE_INITIALIZATION_FAILED:${failure.cause?.javaClass?.simpleName ?: failure.javaClass.simpleName}"
            )
            false
        } catch (failure: RuntimeException) {
            publishUnavailable("NATIVE_INITIALIZATION_FAILED:${failure.javaClass.simpleName}")
            false
        }
    }

    fun unavailableReason(): String = when (state) {
        State.READY -> READY
        State.UNAVAILABLE -> reason
        State.UNCHECKED,
        State.PROBING -> if (awaitReady()) READY else reason
    }

    internal fun closeForTesting() {
        synchronized(lock) {
            if (state != State.READY) {
                state = State.UNAVAILABLE
                reason = CLOSED_FOR_TESTING
            }
            executor.shutdownNow()
        }
    }

    private fun publishReady() {
        synchronized(lock) {
            if (state == State.PROBING) {
                state = State.READY
                reason = READY
                executor.shutdown()
            }
        }
    }

    private fun publishUnavailable(message: String) {
        synchronized(lock) {
            if (state == State.READY || state == State.UNAVAILABLE) return
            state = State.UNAVAILABLE
            reason = message
            // shutdown() rejects new work without depending on an interrupt reaching a blocked vendor JNI
            // call. The single daemon probe is process-bounded even if the driver never returns.
            executor.shutdown()
        }
    }

    private companion object {
        const val READY = "ready"
        const val NOT_PROBED = "NATIVE_INITIALIZATION_NOT_PROBED"
        const val EMPTY_STATUS = "NATIVE_INITIALIZATION_EMPTY_STATUS"
        const val CLOSED_FOR_TESTING = "NATIVE_INITIALIZATION_GATE_CLOSED"
    }
}
