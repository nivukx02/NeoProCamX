package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPreallocationPolicyTest {

    @Test
    fun movCertificateMetadata_preservesFinalDurableReservationFacts() {
        val plan = RecordingPreallocationPolicy.processed(40L * GIB)
        val result = RecordingPreallocationResult(
            plan = plan,
            method = RecordingPreallocationMethod.POSIX_FALLOCATE,
            reservedBytes = 20L * GIB,
            initialReservedBytes = 10L * GIB,
            rollingExtensionCount = 1L,
            rollingExtendedBytes = 10L * GIB,
            committedBytes = 11L * GIB,
            reservationTrimmed = true,
            synced = true
        )

        val metadata = result.toMovCertificateMetadata()

        assertEquals("true", metadata["movPhysicalReservationEnabled"])
        assertEquals("POSIX_FALLOCATE", metadata["movPhysicalReservationMethod"])
        assertEquals((10L * GIB).toString(), metadata["movPhysicalReservationInitialReservedBytes"])
        assertEquals((20L * GIB).toString(), metadata["movPhysicalReservationCurrentReservedBytes"])
        assertEquals("1", metadata["movPhysicalReservationExtensionSuccesses"])
        assertEquals((10L * GIB).toString(), metadata["movPhysicalReservationTotalExtendedBytes"])
        assertEquals((11L * GIB).toString(), metadata["movPhysicalReservationCommittedBytes"])
        assertEquals("true", metadata["movPhysicalReservationTrimmed"])
        assertEquals("true", metadata["movPhysicalReservationSynced"])
        assertEquals("", metadata["movPhysicalReservationFailure"])
    }
    @Test
    fun processedReservesTenGiBWhenSpaceAllows() {
        val plan = RecordingPreallocationPolicy.processed(12L * GIB)

        assertTrue(plan.enabled)
        assertEquals(10L * GIB, plan.reservationBytes)
        assertEquals(2L * GIB, plan.safetyReserveBytes)
        assertEquals("target-reserved", plan.reason)
        assertEquals(10L * GIB, plan.rollingExtensionBytes)
        assertEquals(1L * GIB, plan.rollingLeadBytes)
    }

    @Test
    fun rawReservationIsHardCappedAtTenGiB() {
        val plan = RecordingPreallocationPolicy.raw(30L * GIB)

        assertTrue(plan.enabled)
        assertEquals(10L * GIB, plan.reservationBytes)
        assertEquals(3L * GIB, plan.safetyReserveBytes)
        assertEquals(10L * GIB, plan.rollingExtensionBytes)
        assertEquals(4L * GIB, plan.rollingLeadBytes)
    }

    @Test
    fun reservationShrinksInsteadOfConsumingSafetySpace() {
        val plan = RecordingPreallocationPolicy.processed(7L * GIB)

        assertTrue(plan.enabled)
        assertEquals(5L * GIB, plan.reservationBytes)
        assertEquals("space-bounded", plan.reason)
    }

    @Test
    fun reservationDisablesWhenOnlySafetySpaceRemains() {
        val plan = RecordingPreallocationPolicy.raw(2L * GIB)

        assertFalse(plan.enabled)
        assertEquals(0L, plan.reservationBytes)
    }

    @Test
    fun offlineConversionUsesExactPayloadInsteadOfFixedEightGiB() {
        val payload = 512L * MIB
        val plan = RecordingPreallocationPolicy.offlineConversion(
            availableBytes = 20L * GIB,
            estimatedPayloadBytes = payload
        )

        assertTrue(plan.enabled)
        assertEquals(payload + RecordingPreallocationPolicy.OFFLINE_CONTAINER_HEADROOM_BYTES, plan.reservationBytes)
        assertTrue(plan.reservationBytes < RecordingPreallocationPolicy.PROCESSED_TARGET_BYTES)
        assertEquals(10L * GIB, plan.rollingExtensionBytes)
        assertEquals(1L * GIB, plan.rollingLeadBytes)
    }

    @Test
    fun tinyOfflineConversionSkipsNeedlessLargeReservation() {
        val plan = RecordingPreallocationPolicy.offlineConversion(
            availableBytes = 20L * GIB,
            estimatedPayloadBytes = 8L * MIB
        )

        assertFalse(plan.enabled)
        assertEquals(0L, plan.reservationBytes)
    }

    @Test
    fun unknownOfflineConversionRetainsEstablishedProcessedPolicy() {
        val plan = RecordingPreallocationPolicy.offlineConversion(
            availableBytes = 12L * GIB,
            estimatedPayloadBytes = null
        )

        assertEquals(RecordingPreallocationPolicy.processed(12L * GIB), plan)
    }

    @Test
    fun movReservationGrowsByAnotherTenGiBWithoutConsumingSafetyReserve() {
        val plan = RecordingPreallocationPolicy.rollingMovExtension(
            availableBytes = 80L * GIB,
            currentReservedBytes = 10L * GIB
        )

        assertEquals(10L * GIB, plan.growthBytes)
        assertEquals(20L * GIB, plan.targetReservedBytes)
        assertTrue(plan.fullyCoversRequest)
        assertEquals("rolling-target-reserved", plan.reason)
    }

    @Test
    fun movReservationSchedulesBeforeTheCurrentExtentIsConsumed() {
        assertFalse(
            RecordingPreallocationPolicy.shouldScheduleMovGrowth(
                requiredEndBytes = 8L * GIB,
                reservedBytes = 10L * GIB,
                leadBytes = 1L * GIB
            )
        )
        assertTrue(
            RecordingPreallocationPolicy.shouldScheduleMovGrowth(
                requiredEndBytes = 9L * GIB,
                reservedBytes = 10L * GIB,
                leadBytes = 1L * GIB
            )
        )
    }

    @Test
    fun rawReservationGrowsInTenGiBPhysicalExtents() {
        val plan = RecordingPreallocationPolicy.rollingRawExtension(
            availableBytes = 80L * GIB,
            currentReservedBytes = 10L * GIB
        )

        assertEquals(10L * GIB, plan.growthBytes)
        assertEquals(20L * GIB, plan.targetReservedBytes)
        assertTrue(plan.fullyCoversRequest)
        assertEquals("rolling-target-reserved", plan.reason)
    }

    @Test
    fun rawRollingReservationNeverConsumesTheSafetyReserve() {
        val plan = RecordingPreallocationPolicy.rollingRawExtension(
            availableBytes = 1L * GIB,
            currentReservedBytes = 10L * GIB
        )

        assertEquals(0L, plan.growthBytes)
        assertEquals(10L * GIB, plan.targetReservedBytes)
        assertFalse(plan.fullyCoversRequest)
        assertEquals("rolling-insufficient-space-after-safety-reserve", plan.reason)
    }

    @Test
    fun rawPhysicalReservationIsSplitIntoBoundedSchedulingSlices() {
        var remaining = 10L * GIB
        var slices = 0
        var allocated = 0L
        while (remaining > 0L) {
            val slice = RecordingPreallocationPolicy.nextRawAllocationSliceBytes(remaining)
            assertTrue(slice in 1L..RecordingPreallocationPolicy.RAW_ROLLING_ALLOCATION_SLICE_BYTES)
            remaining -= slice
            allocated += slice
            slices += 1
        }

        assertEquals(10L * GIB, allocated)
        assertEquals(
            (10L * GIB / RecordingPreallocationPolicy.RAW_ROLLING_ALLOCATION_SLICE_BYTES).toInt(),
            slices
        )
        assertEquals(
            123L,
            RecordingPreallocationPolicy.nextRawAllocationSliceBytes(123L)
        )
        assertEquals(0L, RecordingPreallocationPolicy.nextRawAllocationSliceBytes(-1L))
    }

    private companion object {
        const val MIB = 1024L * 1024L
        const val GIB = 1024L * 1024L * 1024L
    }
}
