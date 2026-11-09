package com.neoproc.neoproccamx.pipeline

import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicInteger
import com.neoproc.neoproccamx.camera.RawSensorReaderDepthPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Virtual-time proof of the recorder-owned ImageReader budget.
 *
 * This deliberately models a single ordered processing lane: each accepted frame starts after the
 * preceding frame completes. No wall clock, thread scheduling, Camera2 implementation, or device load
 * can make these tests flaky. Production reservation is exercised directly; only completion time is
 * simulated.
 */
class SensorLinearNdrRecordingIngressCadenceSimulationTest {

    @Test
    fun `more than fourteen slow frames backpressure without rejection or ownership loss`() {
        listOf(41L, 160L).forEach { serviceMs ->
            val readerDepth = RawSensorReaderDepthPolicy.minimumBoundedStallDepth(25)
            val simulation = OrderedIngressSimulation(readerMaxImages = readerDepth)

            val result = simulation.runBackpressuredArrivals(
                frameCount = 1_000,
                arrivalIntervalNs = 40L.ms,
                serviceTimeNs = { serviceMs.ms }
            )

            assertEquals("$serviceMs ms service must not reject an admitted frame", 0, result.rejectedFrames)
            assertEquals(1_000, result.acceptedFrames)
            assertEquals(
                "$serviceMs ms service must exercise the full production ownership budget",
                SensorLinearNdrRecordingIngressPolicy.ownedFrameLimit(readerDepth),
                result.highWatermark
            )
            assertTrue("capacity pressure must be handled as flow control", result.backpressureEvents > 0)

            simulation.drainAll()
            simulation.assertEveryAcceptedFrameClosedExactlyOnce()
        }
    }

    @Test
    fun `25 fps with service at or below 40 ms remains bounded`() {
        listOf(40L, 20L).forEach { serviceMs ->
            val simulation = OrderedIngressSimulation(readerMaxImages = 8)

            val result = simulation.runArrivals(
                frameCount = 10_000,
                arrivalIntervalNs = 40L.ms,
                serviceTimeNs = { serviceMs.ms }
            )

            assertEquals("$serviceMs ms service must reject no frame", 0, result.rejectedFrames)
            assertEquals(
                "completion at the next cadence boundary is retired before the next reservation",
                1,
                result.highWatermark
            )
            assertEquals(1, result.ownedAfterLastArrival)

            simulation.drainAll()
            simulation.assertEveryAcceptedFrameClosedExactlyOnce()
        }
    }

    @Test
    fun `one bounded service burst is absorbed and the ordered lane recovers`() {
        val simulation = OrderedIngressSimulation(readerMaxImages = 8)

        val result = simulation.runArrivals(
            frameCount = 1_000,
            arrivalIntervalNs = 40L.ms,
            serviceTimeNs = { frameId ->
                if (frameId == 50L) 200L.ms else 30L.ms
            }
        )

        assertEquals(0, result.rejectedFrames)
        assertTrue("the injected burst must create observable queued ownership", result.highWatermark > 1)
        assertTrue(
            "the bounded burst must stay below the finite ownership ceiling",
            result.highWatermark < SensorLinearNdrRecordingIngressPolicy.ownedFrameLimit(8)
        )
        assertEquals("the faster steady state must catch back up", 1, result.ownedAfterLastArrival)

        simulation.drainAll()
        simulation.assertEveryAcceptedFrameClosedExactlyOnce()
    }

    @Test
    fun `evidenced 334 ms stall plus two join frames is absorbed without reordering`() {
        val readerDepth = RawSensorReaderDepthPolicy.minimumBoundedStallDepth(25)
        val simulation = OrderedIngressSimulation(readerMaxImages = readerDepth)

        val result = simulation.runArrivals(
            frameCount = 1_000,
            arrivalIntervalNs = 40L.ms,
            serviceTimeNs = { frameId ->
                // The extra 80 ms models the two-frame exact Camera2 result join margin included in
                // the physical reader-depth proof, without skipping or coalescing any arrival.
                if (frameId == 50L) 414L.ms else 30L.ms
            }
        )

        assertEquals(16, readerDepth)
        assertEquals(0, result.rejectedFrames)
        assertTrue(result.highWatermark < readerDepth)
        assertEquals(1, result.ownedAfterLastArrival)

        simulation.drainAll()
        simulation.assertEveryAcceptedFrameClosedExactlyOnce()
    }

    @Test
    fun `capacity remains fail closed beyond certified bounded stall`() {
        val readerDepth = RawSensorReaderDepthPolicy.minimumBoundedStallDepth(25)
        val simulation = OrderedIngressSimulation(readerMaxImages = readerDepth)

        val result = simulation.runArrivals(
            frameCount = 1_000,
            arrivalIntervalNs = 40L.ms,
            // Deliberately exceeds the enlarged thermal/storage transient window so the finite
            // ImageReader safety boundary is still proven fail-closed.
            serviceTimeNs = { frameId -> if (frameId == 50L) 720L.ms else 30L.ms },
            stopAtFirstRejection = true
        )

        assertEquals(1, result.rejectedFrames)
        assertEquals(readerDepth, result.highWatermark)
        assertNotNull(result.firstRejectedAtNs)

        simulation.drainAll()
        simulation.assertEveryAcceptedFrameClosedExactlyOnce()
    }

    @Test
    fun `exact owner accounting closes each accepted frame once and never closes a rejection`() {
        val simulation = OrderedIngressSimulation(readerMaxImages = 4)

        val result = simulation.runArrivals(
            frameCount = 400,
            arrivalIntervalNs = 40L.ms,
            serviceTimeNs = { 80L.ms }
        )

        assertTrue("the deliberately under-capacity lane must exercise rejection", result.rejectedFrames > 0)
        simulation.drainAll()
        simulation.assertEveryAcceptedFrameClosedExactlyOnce()
        assertEquals(result.acceptedFrames, simulation.closedFrameCount)
        assertEquals(0, simulation.rejectedFramesClosed)
    }

    private data class SimulationResult(
        val acceptedFrames: Int,
        val rejectedFrames: Int,
        val firstRejectedAtNs: Long?,
        val highWatermark: Int,
        val ownedAfterLastArrival: Int,
        val backpressureEvents: Int = 0
    )

    private data class PendingCompletion(
        val frameId: Long,
        val completesAtNs: Long
    )

    private class OrderedIngressSimulation(
        private val readerMaxImages: Int
    ) {
        private val ownedFrames = AtomicInteger(0)
        private val pending = PriorityQueue(
            compareBy<PendingCompletion> { it.completesAtNs }.thenBy { it.frameId }
        )
        private val acceptedFrameIds = linkedSetOf<Long>()
        private val activeOwnerIds = linkedSetOf<Long>()
        private val closeCounts = linkedMapOf<Long, Int>()
        private val rejectedFrameIds = linkedSetOf<Long>()
        private var nextServiceAvailableNs = 0L
        private var highWatermark = 0

        val closedFrameCount: Int
            get() = closeCounts.size

        val rejectedFramesClosed: Int
            get() = rejectedFrameIds.count { closeCounts.containsKey(it) }

        fun runArrivals(
            frameCount: Int,
            arrivalIntervalNs: Long,
            serviceTimeNs: (frameId: Long) -> Long,
            stopAtFirstRejection: Boolean = false
        ): SimulationResult {
            require(frameCount > 0)
            require(arrivalIntervalNs > 0L)

            var rejectedFrames = 0
            var firstRejectedAtNs: Long? = null
            for (index in 0 until frameCount) {
                val frameId = index.toLong() + 1L
                val arrivalNs = index.toLong() * arrivalIntervalNs
                retireCompletedThrough(arrivalNs)

                val reservation = SensorLinearNdrRecordingIngressPolicy.tryReserveOwnedFrame(
                    ownedFrames = ownedFrames,
                    readerMaxImages = readerMaxImages
                )
                if (reservation == null) {
                    rejectedFrames += 1
                    rejectedFrameIds += frameId
                    if (firstRejectedAtNs == null) firstRejectedAtNs = arrivalNs
                    if (stopAtFirstRejection) break
                    continue
                }

                check(acceptedFrameIds.add(frameId)) { "frame $frameId was accepted twice" }
                check(activeOwnerIds.add(frameId)) { "frame $frameId acquired duplicate ownership" }
                highWatermark = maxOf(highWatermark, reservation)

                val durationNs = serviceTimeNs(frameId)
                require(durationNs > 0L)
                val serviceStartsAtNs = maxOf(arrivalNs, nextServiceAvailableNs)
                val completesAtNs = serviceStartsAtNs + durationNs
                nextServiceAvailableNs = completesAtNs
                pending += PendingCompletion(frameId, completesAtNs)
            }

            return SimulationResult(
                acceptedFrames = acceptedFrameIds.size,
                rejectedFrames = rejectedFrames,
                firstRejectedAtNs = firstRejectedAtNs,
                highWatermark = highWatermark,
                ownedAfterLastArrival = ownedFrames.get()
            )
        }

        fun runBackpressuredArrivals(
            frameCount: Int,
            arrivalIntervalNs: Long,
            serviceTimeNs: (frameId: Long) -> Long
        ): SimulationResult {
            require(frameCount > 0)
            require(arrivalIntervalNs > 0L)

            var callbackAvailableNs = 0L
            var backpressureEvents = 0
            for (index in 0 until frameCount) {
                val frameId = index.toLong() + 1L
                var callbackTimeNs = maxOf(index.toLong() * arrivalIntervalNs, callbackAvailableNs)
                retireCompletedThrough(callbackTimeNs)

                var reservation = SensorLinearNdrRecordingIngressPolicy.tryReserveOwnedFrame(
                    ownedFrames = ownedFrames,
                    readerMaxImages = readerMaxImages
                )
                while (reservation == null) {
                    ++backpressureEvents
                    val nextCompletion = requireNotNull(pending.peek()).completesAtNs
                    callbackTimeNs = maxOf(callbackTimeNs, nextCompletion)
                    retireCompletedThrough(callbackTimeNs)
                    reservation = SensorLinearNdrRecordingIngressPolicy.tryReserveOwnedFrame(
                        ownedFrames = ownedFrames,
                        readerMaxImages = readerMaxImages
                    )
                }
                callbackAvailableNs = callbackTimeNs

                check(acceptedFrameIds.add(frameId)) { "frame $frameId was accepted twice" }
                check(activeOwnerIds.add(frameId)) { "frame $frameId acquired duplicate ownership" }
                highWatermark = maxOf(highWatermark, reservation)

                val durationNs = serviceTimeNs(frameId)
                require(durationNs > 0L)
                val serviceStartsAtNs = maxOf(callbackTimeNs, nextServiceAvailableNs)
                val completesAtNs = serviceStartsAtNs + durationNs
                nextServiceAvailableNs = completesAtNs
                pending += PendingCompletion(frameId, completesAtNs)
            }

            return SimulationResult(
                acceptedFrames = acceptedFrameIds.size,
                rejectedFrames = 0,
                firstRejectedAtNs = null,
                highWatermark = highWatermark,
                ownedAfterLastArrival = ownedFrames.get(),
                backpressureEvents = backpressureEvents
            )
        }

        fun drainAll() {
            while (pending.isNotEmpty()) closeExactOwner(pending.remove().frameId)
        }

        fun assertEveryAcceptedFrameClosedExactlyOnce() {
            assertTrue("no accepted frame may remain active", activeOwnerIds.isEmpty())
            assertTrue("no simulated completion may remain pending", pending.isEmpty())
            assertEquals("all ownership reservations must be retired", 0, ownedFrames.get())
            assertEquals(acceptedFrameIds, closeCounts.keys)
            assertTrue(
                "every accepted frame must have one and only one close permit",
                closeCounts.values.all { it == 1 }
            )
        }

        private fun retireCompletedThrough(nowNs: Long) {
            while (pending.peek()?.completesAtNs?.let { it <= nowNs } == true) {
                closeExactOwner(pending.remove().frameId)
            }
        }

        private fun closeExactOwner(frameId: Long) {
            check(acceptedFrameIds.contains(frameId)) { "unaccepted frame $frameId cannot be closed" }
            check(activeOwnerIds.remove(frameId)) { "frame $frameId has no exact release permit" }
            val closes = (closeCounts[frameId] ?: 0) + 1
            closeCounts[frameId] = closes
            check(closes == 1) { "frame $frameId was closed $closes times" }
            check(ownedFrames.decrementAndGet() >= 0) { "owned-frame accounting underflow" }
        }
    }

    private val Long.ms: Long
        get() = this * 1_000_000L
}
