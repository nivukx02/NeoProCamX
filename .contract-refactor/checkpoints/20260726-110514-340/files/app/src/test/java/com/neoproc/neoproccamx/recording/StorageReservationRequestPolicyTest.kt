package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageReservationRequestPolicyTest {
    @Test fun `ample usable space skips cache reclamation request`() {
        val plan = RecordingPreallocationPolicy.processed(100L * 1024L * 1024L * 1024L)
        assertFalse(StorageReservationRequestPolicy.shouldRequestAllocation(100L * 1024L * 1024L * 1024L, plan))
    }

    @Test fun `tight usable space requests allocation before fallocate`() {
        val plan = RecordingPreallocationPolicy.processed(20L * 1024L * 1024L * 1024L)
        assertTrue(StorageReservationRequestPolicy.shouldRequestAllocation(9L * 1024L * 1024L * 1024L, plan))
    }
}
