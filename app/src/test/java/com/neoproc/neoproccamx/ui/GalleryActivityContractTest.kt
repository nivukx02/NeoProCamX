package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GalleryActivityContractTest {
    private val source = File("src/main/java/com/neoproc/neoproccamx/ui/GalleryActivity.kt").readText()

    @Test
    fun galleryAdapterUsesLruThumbnailCacheAndBackgroundDecoding() {
        assertTrue(source.contains("LruCache<String, Bitmap>"))
        assertTrue(source.contains("Runtime.getRuntime().maxMemory() / 8"))
        assertTrue(source.contains("withContext(Dispatchers.IO)"))
        assertTrue(source.contains("contentResolver.loadThumbnail"))
        assertTrue(source.contains("BitmapFactory.Options"))
        assertFalse(source.contains("ViewGroup.LayoutParams.MATCH_PARENT,\n                120"))
    }

    @Test
    fun galleryViewportUsesAspectLockedCardsAndDiffUtilUpdates() {
        assertTrue(source.contains("GalleryLayoutPolicy.cardHeight"))
        assertTrue(source.contains("ASPECT_RATIO_16_9"))
        assertTrue(source.contains("DiffUtil.calculateDiff"))
        assertTrue(source.contains("submitItems"))
        assertTrue(source.contains("animate().scaleX(1.03f).scaleY(1.03f)"))
    }

    @Test
    fun galleryRegistersFlowBasedMediaObserverWithDebouncedRefresh() {
        assertTrue(source.contains("ContentObserver"))
        assertTrue(source.contains("callbackFlow"))
        assertTrue(source.contains("MediaStore.Video.Media.EXTERNAL_CONTENT_URI"))
        assertTrue(source.contains("MediaStore.Images.Media.EXTERNAL_CONTENT_URI"))
        assertTrue(source.contains("debounce(CameraMediaLibraryQueryPolicy.MEDIA_OBSERVER_DEBOUNCE_MS)"))
        assertTrue(source.contains("refreshIncrementalMedia"))
    }
}
