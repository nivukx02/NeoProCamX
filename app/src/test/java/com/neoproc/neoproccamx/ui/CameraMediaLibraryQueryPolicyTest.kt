package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraMediaLibraryQueryPolicyTest {

    @Test
    fun `media store query includes new and legacy camera folders`() {
        val query = CameraMediaLibraryQueryPolicy.mediaStoreRelativePathSelection("relative_path")

        assertEquals("(relative_path LIKE ? OR relative_path LIKE ? OR relative_path LIKE ?)", query.selection)
        assertArrayEquals(arrayOf("%NeoProCamX/%", "%CamX/%", "%CamXNPV10/%"), query.selectionArgs)
    }

    @Test
    fun `legacy query includes new folder legacy folder and camera file prefix`() {
        val query = CameraMediaLibraryQueryPolicy.legacyFileSelection(
            dataColumn = "_data",
            displayNameColumn = "display_name"
        )

        assertEquals(
            "(_data LIKE ? OR _data LIKE ? OR _data LIKE ? OR display_name LIKE ? OR display_name LIKE ?)",
            query.selection
        )
        assertArrayEquals(
            arrayOf("%/NeoProCamX/%", "%/CamX/%", "%/CamXNPV10/%", "CAMX_%", "NPV10_%"),
            query.selectionArgs
        )
    }

    @Test
    fun `incremental query appends date added gate without rebuilding whole library`() {
        val base = CameraMediaLibraryQueryPolicy.mediaStoreRelativePathSelection("relative_path")
        val query = CameraMediaLibraryQueryPolicy.withDateAddedAfter(
            base = base,
            dateAddedColumn = "date_added",
            minDateAddedSeconds = 1_776_614_400L
        )

        assertEquals(
            "((relative_path LIKE ? OR relative_path LIKE ? OR relative_path LIKE ?) AND date_added > ?)",
            query.selection
        )
        assertArrayEquals(
            arrayOf("%NeoProCamX/%", "%CamX/%", "%CamXNPV10/%", "1776614400"),
            query.selectionArgs
        )
    }

    @Test
    fun `nivukx files query is extension gated inside camera folders`() {
        val base = CameraMediaLibraryQueryPolicy.mediaStoreRelativePathSelection("relative_path")
        val query = CameraMediaLibraryQueryPolicy.withFileExtension(
            base = base,
            displayNameColumn = "display_name",
            extension = ".NIVUKX"
        )

        assertEquals(
            "((relative_path LIKE ? OR relative_path LIKE ? OR relative_path LIKE ?) AND display_name LIKE ?)",
            query.selection
        )
        assertArrayEquals(
            arrayOf("%NeoProCamX/%", "%CamX/%", "%CamXNPV10/%", "%.nivukx"),
            query.selectionArgs
        )
    }

    @Test
    fun `media change throttle documents vsync gated debounce window`() {
        assertTrue(CameraMediaLibraryQueryPolicy.MEDIA_OBSERVER_DEBOUNCE_MS >= 500L)
    }
}
