package com.neoproc.neoproccamx.capture

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class StillCaptureOutputRoutingTest {

    @Test
    fun jpegOutputsStayInImageCollection() {
        val spec = StillCaptureOutputRouting.resolve(StillCaptureOutputKind.JPEG)

        assertEquals("jpg", spec.extension)
        assertEquals("image/jpeg", spec.mimeType)
        assertEquals("DCIM/CamX", spec.relativePath)
        assertEquals("DCIM", spec.legacyRootDirectory)
        assertEquals(StillCaptureMediaCollection.IMAGES, spec.collection)
    }

    @Test
    fun dngOutputsUseDedicatedRawImageFolder() {
        val spec = StillCaptureOutputRouting.resolve(StillCaptureOutputKind.DNG)

        assertEquals("dng", spec.extension)
        assertEquals("DCIM/CamX/RAW", spec.relativePath)
        assertEquals("CamX/RAW", spec.legacySubdirectory)
        assertEquals(StillCaptureMediaCollection.IMAGES, spec.collection)
    }

    @Test
    fun rawPackagesRouteToFileCollectionOutsideGalleryImageBucket() {
        val spec = StillCaptureOutputRouting.resolve(StillCaptureOutputKind.RAW_PACKAGE)

        assertEquals("zip", spec.extension)
        assertEquals("application/zip", spec.mimeType)
        assertEquals("Documents/CamX/RAW", spec.relativePath)
        assertEquals("Documents", spec.legacyRootDirectory)
        assertEquals(StillCaptureMediaCollection.FILES, spec.collection)
    }

    @Test
    fun strictMode_routesRawPackagesIntoCinemaLegacyFolder() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = true

            val spec = StillCaptureOutputRouting.resolve(StillCaptureOutputKind.RAW_PACKAGE)

            assertEquals("CamX/RAW/CINEMA", spec.legacySubdirectory)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }
}
