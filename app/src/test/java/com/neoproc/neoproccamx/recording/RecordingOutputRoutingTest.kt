package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RecordingOutputRoutingTest {

    @Test
    fun videosRouteToCameraDcimLibraryInsteadOfSeparateMoviesFolder() {
        val route = RecordingOutputRouting.resolve()

        assertEquals("DCIM/CamX", route.relativePath)
        assertEquals("DCIM", route.legacyRootDirectory)
        assertEquals("CamX", route.legacySubdirectory)
    }

    @Test
    fun pendingMovRecoveryCoversEveryOwnedVideoExportAlbumExactly() {
        assertEquals(
            listOf("DCIM/CamX", "Movies/CamX", "Movies/CamXNPV10"),
            RecordingOutputRouting.ownedPendingMovRelativePaths()
        )
        assertEquals(
            listOf(
                "DCIM/CamX",
                "DCIM/CamX/",
                "Movies/CamX",
                "Movies/CamX/",
                "Movies/CamXNPV10",
                "Movies/CamXNPV10/"
            ),
            RecordingOutputRouting.ownedPendingMovStoredPathValues()
        )
        assertFalse(
            RecordingOutputRouting.ownedPendingMovStoredPathValues().contains("Movies/CamXOther/")
        )
    }
}
