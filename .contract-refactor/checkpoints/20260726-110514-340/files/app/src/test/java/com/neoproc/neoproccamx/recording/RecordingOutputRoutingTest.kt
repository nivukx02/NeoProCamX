package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingOutputRoutingTest {

    @Test
    fun videosRouteToCameraDcimLibraryInsteadOfSeparateMoviesFolder() {
        val route = RecordingOutputRouting.resolve()

        assertEquals("DCIM/CamX", route.relativePath)
        assertEquals("DCIM", route.legacyRootDirectory)
        assertEquals("CamX", route.legacySubdirectory)
    }
}
