package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraVendorStreamCandidatePolicyTest {

    @Test
    fun `marks vendor size safe only when public hal stream map exposes exact output`() {
        val report = CameraVendorStreamCandidatePolicy.resolve(
            vendorSizesByKey = mapOf(
                "com.vendor.highResVideoSizes" to listOf(
                    CameraStreamSize(4096, 3072),
                    CameraStreamSize(8192, 6144)
                )
            ),
            safeExposedSizes = setOf(CameraStreamSize(4096, 3072))
        )

        assertEquals(1, report.safeCandidates.size)
        assertEquals(CameraStreamSize(4096, 3072), report.safeCandidates.single().size)
        assertEquals(CameraVendorReadoutCandidateStatus.SAFE_EXPOSED, report.safeCandidates.single().status)

        assertEquals(1, report.gatedCandidates.size)
        assertEquals(CameraStreamSize(8192, 6144), report.gatedCandidates.single().size)
        assertEquals(CameraVendorReadoutCandidateStatus.GATED_PRIVATE, report.gatedCandidates.single().status)
        assertTrue(report.gatedCandidates.single().reason.contains("HAL stream map did not expose"))
    }

    @Test
    fun `deduplicates repeated vendor candidates by key and size`() {
        val report = CameraVendorStreamCandidatePolicy.resolve(
            vendorSizesByKey = mapOf(
                "com.vendor.highResVideoSizes" to listOf(
                    CameraStreamSize(4096, 3072),
                    CameraStreamSize(4096, 3072)
                )
            ),
            safeExposedSizes = setOf(CameraStreamSize(4096, 3072))
        )

        assertEquals(1, report.candidates.size)
    }
}
