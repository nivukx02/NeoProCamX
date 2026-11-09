package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPendingReservationPolicyTest {
    @Test
    fun `recognizes only unpublished app-owned raw master reservations`() {
        assertTrue(
            RawPendingReservationPolicy.isOwnedPendingStream(
                displayName = "stream.npraw",
                relativePath = "Documents/CamX/NEO_RADIANCE_RAW_20260804_153427/",
                isPending = true
            )
        )
        assertTrue(
            RawPendingReservationPolicy.isOwnedPendingStream(
                displayName = "stream.npraw",
                relativePath = "DCIM\\CamX\\NEO_RADIANCE_RAW_20260804_153427",
                isPending = true
            )
        )
        assertTrue(
            RawPendingReservationPolicy.isOwnedPendingStream(
                displayName = ".pending-1786443885-stream.npraw",
                relativePath = "Documents/CamX/NEO_RADIANCE_RAW_20260804_155444/",
                isPending = true
            )
        )
    }

    @Test
    fun `never selects published clips or unrelated user media`() {
        assertFalse(
            RawPendingReservationPolicy.isOwnedPendingStream(
                displayName = "stream.npraw",
                relativePath = "Documents/CamX/NEO_RADIANCE_RAW_20260804_153427/",
                isPending = false
            )
        )
        assertFalse(
            RawPendingReservationPolicy.isOwnedPendingStream(
                displayName = "stream.npraw",
                relativePath = "Documents/OtherApp/NEO_RADIANCE_RAW_20260804_153427/",
                isPending = true
            )
        )
        assertFalse(
            RawPendingReservationPolicy.isOwnedPendingStream(
                displayName = "family-video.npraw",
                relativePath = "Documents/CamX/NEO_RADIANCE_RAW_20260804_153427/",
                isPending = true
            )
        )
        assertFalse(
            RawPendingReservationPolicy.isOwnedPendingStream(
                displayName = ".pending-user-backup-stream.npraw",
                relativePath = "Documents/CamX/NEO_RADIANCE_RAW_20260804_153427/",
                isPending = true
            )
        )
        assertFalse(
            RawPendingReservationPolicy.isOwnedPendingStream(
                displayName = "stream.npraw",
                relativePath = "Documents/CamX/Exports/NEO_RADIANCE_RAW_20260804_153427/",
                isPending = true
            )
        )
    }
}
