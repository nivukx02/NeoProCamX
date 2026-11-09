package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Npv10PhysicalReservationPolicyTest {
    @Test
    fun `terminal reservation telemetry exports complete durable evidence`() {
        val telemetry = Npv10PhysicalReservationTelemetry(
            enabled = true,
            frameRecordBytes = 100L,
            bytesPerSecond = 2_500L,
            initialCoverageRequiredBytes = 10L,
            initialReservedBytes = 10L,
            currentReservedBytes = 20L,
            rollingLeadBytes = 1L,
            rollingExtensionBytes = 10L,
            extensionAttempts = 1L,
            extensionSuccesses = 1L,
            extensionFailures = 0L,
            totalExtendedBytes = 10L,
            minimumHeadroomBytes = 2L,
            unreservedWriteRefusals = 0L,
            lastFailure = ""
        )

        val evidence = telemetry.toEvidenceMetadata()

        assertEquals("true", evidence["npv10PhysicalReservationEnabled"])
        assertEquals("10", evidence["npv10PhysicalReservationInitialReservedBytes"])
        assertEquals("20", evidence["npv10PhysicalReservationCurrentReservedBytes"])
        assertEquals("10", evidence["npv10PhysicalReservationRollingExtensionBytes"])
        assertEquals("1", evidence["npv10PhysicalReservationExtensionSuccesses"])
        assertEquals("10", evidence["npv10PhysicalReservationTotalExtendedBytes"])
        assertEquals("0", evidence["npv10PhysicalReservationExtensionFailures"])
        assertEquals("0", evidence["npv10PhysicalReservationUnreservedWriteRefusals"])
        assertEquals(18, evidence.size)
        assertEquals("0", evidence["npv10PhysicalReservationAllocationSliceLimitBytes"])
        assertEquals("0", evidence["npv10PhysicalReservationAllocationSlices"])
        assertEquals("0", evidence["npv10PhysicalReservationMaxAllocationSliceNs"])
    }


    private val profile = Npv10PhysicalReservationPolicy.profile(
        width = 4096,
        height = 3072,
        fps = 25,
        payloadFormat = Npv10PayloadFormat.P010_LE,
        packetCodec = Npv10PacketCodec.PACKED_P010_10_LE_V1
    )

    @Test
    fun fullSensorProfileUsesTenGibInitialExtentAndOneGibLead() {
        val decodedBytes = 4096L * 3072L * 3L
        val packedBytes = decodedBytes * 10L / 16L
        val expectedRecordBytes = packedBytes + Npv10PhysicalReservationPolicy.FRAME_HEADER_BYTES

        assertEquals(expectedRecordBytes, profile.frameRecordBytes)
        assertEquals(expectedRecordBytes * 25L, profile.bytesPerSecond)
        assertEquals(Npv10PhysicalReservationPolicy.INITIAL_RESERVATION_BYTES, profile.initialCoverageBytes)
        assertEquals(Npv10PhysicalReservationPolicy.ROLLING_LEAD_BYTES, profile.rollingLeadBytes)
        assertEquals(Npv10PhysicalReservationPolicy.ROLLING_EXTENSION_BYTES, profile.rollingExtensionBytes)
        assertEquals(0L, profile.initialCoverageBytes % RecordingPreallocationPolicy.BLOCK_BYTES)
    }

    @Test
    fun initialPlanExtendsLegacyEightGibReservationToTenGib() {
        val eightGib = 8L * 1024L * 1024L * 1024L
        val available = 96L * 1024L * 1024L * 1024L
        val plan = Npv10PhysicalReservationPolicy.initialPlan(profile, available, eightGib)

        assertTrue(plan.enabled)
        assertTrue(plan.fullyCoversRequest)
        assertEquals(profile.initialCoverageBytes, plan.targetReservedBytes)
        assertEquals(profile.initialCoverageBytes - eightGib, plan.growthBytes)
        assertTrue(plan.targetReservedBytes > eightGib)
    }

    @Test
    fun initialPlanFailsClosedWhenTenGibCannotPreserveSafetyReserve() {
        val available = 11L * 1024L * 1024L * 1024L
        val plan = Npv10PhysicalReservationPolicy.initialPlan(profile, available, 0L)

        assertFalse(plan.fullyCoversRequest)
        assertTrue(plan.targetReservedBytes < profile.initialCoverageBytes)
        assertTrue(plan.reason.contains("space-bounded"))
    }

    @Test
    fun rollingGrowthStartsAtOneGibHeadroomAndAddsTenGibExtent() {
        val reserved = profile.initialCoverageBytes
        assertFalse(
            Npv10PhysicalReservationPolicy.shouldScheduleGrowth(
                committedBytes = reserved - profile.rollingLeadBytes - 1L,
                reservedBytes = reserved,
                rollingLeadBytes = profile.rollingLeadBytes
            )
        )
        assertTrue(
            Npv10PhysicalReservationPolicy.shouldScheduleGrowth(
                committedBytes = reserved - profile.rollingLeadBytes,
                reservedBytes = reserved,
                rollingLeadBytes = profile.rollingLeadBytes
            )
        )

        val plan = Npv10PhysicalReservationPolicy.rollingPlan(
            profile = profile,
            availableBytesIncludingCurrentReservation = 128L * 1024L * 1024L * 1024L,
            currentReservedBytes = reserved
        )
        assertTrue(plan.fullyCoversRequest)
        assertEquals(10L * 1024L * 1024L * 1024L, profile.initialCoverageBytes)
        assertEquals(1L * 1024L * 1024L * 1024L, profile.rollingLeadBytes)
        assertEquals(10L * 1024L * 1024L * 1024L, profile.rollingExtensionBytes)
        assertEquals(profile.rollingExtensionBytes, plan.growthBytes)
        assertTrue(plan.growthBytes > profile.bytesPerSecond)
        assertTrue(plan.reason.startsWith("rolling-10gib"))
    }

    @Test
    fun writerAllocatesOnlyTheUnreservedDeltaForInitialAndRollingGrowth() {
        val sourceRoot = listOf(
            File("src/main/java/com/neoproc/neoproccamx"),
            File("app/src/main/java/com/neoproc/neoproccamx")
        ).first { it.isDirectory }
        val writer = File(sourceRoot, "recording/Npv10FileWriter.kt").readText()
        val initialGrowth = writer
            .substringAfter("private fun initializePhysicalReservation(")
            .substringBefore("private fun markCommittedBoundary()")
        val rollingGrowth = writer
            .substringAfter("private fun scheduleReservationGrowth(force: Boolean)")
            .substringBefore("private fun awaitReservationGrowth()")
        val initialFallocate = initialGrowth
            .substringAfter("Os.posix_fallocate(")
            .substringBefore(")")
        val rollingFallocate = rollingGrowth
            .substringAfter("Os.posix_fallocate(")
            .substringBefore(")")

        assertTrue(initialFallocate.contains("preparedReservedBytes"))
        assertTrue(initialFallocate.contains("reservationPlan.growthBytes"))
        assertFalse(initialFallocate.contains("reservationPlan.targetReservedBytes"))
        assertTrue(rollingGrowth.contains("Npv10RollingExtentAllocator.allocate("))
        assertTrue(rollingGrowth.contains("initialReservedBytes = reservedAtSubmission"))
        assertTrue(rollingGrowth.contains("growthBytes = plan.growthBytes"))
        assertTrue(rollingFallocate.contains("offsetBytes"))
        assertTrue(rollingFallocate.contains("lengthBytes"))
        assertFalse(rollingFallocate.contains("plan.targetReservedBytes"))
        assertTrue(rollingGrowth.contains("allocationProof.confirmedEndBytes == plan.targetReservedBytes"))

        val fallocateIndex = rollingGrowth.indexOf("Os.posix_fallocate(")
        val resultIndex = rollingGrowth.indexOf("preallocationResult = preallocationResult.copy(")
        val publishIndex = rollingGrowth.indexOf("physicalReservedBytes = plan.targetReservedBytes")
        assertTrue(fallocateIndex >= 0)
        assertTrue(resultIndex > fallocateIndex)
        assertTrue(publishIndex > resultIndex)
    }

    @Test
    fun noFrameCanCrossThePhysicalReservationBoundary() {
        val reserved = profile.initialCoverageBytes
        assertTrue(Npv10PhysicalReservationPolicy.isWritePhysicallyCovered(reserved, reserved))
        assertFalse(Npv10PhysicalReservationPolicy.isWritePhysicallyCovered(reserved + 1L, reserved))
        assertFalse(Npv10PhysicalReservationPolicy.isWritePhysicallyCovered(-1L, reserved))
    }

    @Test
    fun availableSpaceAccountingSaturatesInsteadOfOverflowing() {
        assertEquals(
            Long.MAX_VALUE,
            Npv10PhysicalReservationPolicy.availableIncludingReservation(Long.MAX_VALUE - 5L, 10L)
        )
    }

    @Test
    fun writerRefusesUnreservedBytesAndSealsOnlyAfterMaintenanceCompletes() {
        val sourceRoot = listOf(
            File("src/main/java/com/neoproc/neoproccamx"),
            File("app/src/main/java/com/neoproc/neoproccamx")
        ).first { it.isDirectory }
        val writer = File(sourceRoot, "recording/Npv10FileWriter.kt").readText()
        val commitBody = writer
            .substringAfter("private fun commitEncodedP010Frame(")
            .substringBefore("override fun close()")
        val coverageIndex = commitBody.indexOf("ensurePhysicalReservationForFrame(encodedBytes)")
        val headerIndex = commitBody.indexOf("writeFrameHeader(")
        val payloadIndex = commitBody.indexOf("writeFramePayload(encodedPacket, encodedBytes)")
        assertTrue(coverageIndex >= 0)
        assertTrue(headerIndex > coverageIndex)
        assertTrue(payloadIndex > headerIndex)

        val closeBody = writer
            .substringAfter("private fun closeInternal(")
            .substringBefore("private fun writeHeader()")
        val growthJoin = closeBody.indexOf("awaitReservationGrowth()")
        val checkpointJoin = closeBody.indexOf("awaitDurabilityCheckpoint()")
        val truncate = closeBody.indexOf("channel.truncate(committedBytes)")
        val force = closeBody.indexOf("channel.force(true)")
        assertTrue(growthJoin >= 0)
        assertTrue(checkpointJoin > growthJoin)
        assertTrue(truncate > checkpointJoin)
        assertTrue(force > truncate)
    }
}
