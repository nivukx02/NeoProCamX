package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingMediaStoreLifecyclePolicyTest {

    @Test
    fun androidQAndAboveUsesPendingMediaStoreVideoRow() {
        val plan = RecordingMediaStoreLifecyclePolicy.resolveCreate(
            sdkInt = 35,
            contract = contract()
        )

        assertEquals(RecordingOutputStorageKind.MEDIASTORE_PENDING_VIDEO, plan.storageKind)
        assertTrue(plan.pending)
        assertEquals("CAMX_GPU_20260514_101500.mov", plan.displayName)
        assertEquals("video/quicktime", plan.mimeType)
        assertEquals("DCIM/CamX", plan.relativePath)
        assertEquals("video=3840x2160@30fps", plan.description)
    }

    @Test
    fun legacyAndroidUsesPublicDcimFile() {
        val plan = RecordingMediaStoreLifecyclePolicy.resolveCreate(
            sdkInt = 28,
            contract = contract()
        )

        assertEquals(RecordingOutputStorageKind.LEGACY_PUBLIC_FILE, plan.storageKind)
        assertFalse(plan.pending)
        assertEquals("DCIM", plan.legacyRootDirectory)
        assertEquals("CamX", plan.legacySubdirectory)
    }

    @Test
    fun publishRequiresPendingMediaStoreTargetAndFinalDescription() {
        val decision = RecordingMediaStoreLifecyclePolicy.resolveFinalize(
            storageKind = RecordingOutputStorageKind.MEDIASTORE_PENDING_VIDEO,
            pending = true,
            finalDescription = "video=3840x2160@30fps;finalized=true"
        )

        assertTrue(decision.closeDescriptorFirst)
        assertTrue(decision.publishMediaStore)
        assertFalse(decision.scanLegacyFile)
        assertEquals("video=3840x2160@30fps;finalized=true", decision.finalDescription)
    }

    @Test
    fun legacyFinalizeScansPublicFileWithoutMediaStorePublish() {
        val decision = RecordingMediaStoreLifecyclePolicy.resolveFinalize(
            storageKind = RecordingOutputStorageKind.LEGACY_PUBLIC_FILE,
            pending = false,
            finalDescription = "video=1920x1080@24fps"
        )

        assertTrue(decision.closeDescriptorFirst)
        assertFalse(decision.publishMediaStore)
        assertTrue(decision.scanLegacyFile)
    }

    @Test
    fun failedPendingMediaStoreTargetIsDeleted() {
        val decision = RecordingMediaStoreLifecyclePolicy.resolveFailureCleanup(
            storageKind = RecordingOutputStorageKind.MEDIASTORE_PENDING_VIDEO,
            hasUri = true,
            hasFile = false
        )

        assertTrue(decision.closeDescriptorFirst)
        assertTrue(decision.deleteMediaStoreUri)
        assertFalse(decision.deleteLegacyFile)
    }

    @Test
    fun failedLegacyTargetDeletesPartialFile() {
        val decision = RecordingMediaStoreLifecyclePolicy.resolveFailureCleanup(
            storageKind = RecordingOutputStorageKind.LEGACY_PUBLIC_FILE,
            hasUri = false,
            hasFile = true
        )

        assertTrue(decision.closeDescriptorFirst)
        assertFalse(decision.deleteMediaStoreUri)
        assertTrue(decision.deleteLegacyFile)
    }

    @Test
    fun hdrDescriptionTransportFollowsNdrTruePqFlag() {
        val description =
            "video=3840x2160@60fps;color=Rec.2020-NDR10+;hdrIntent=NDR10+;" +
                "dynamicRange=NIVUKX Vision (10-bit);logCurve=OFF;bitDepth=10-bit"
        val original = NeoNdrTruePqTransportPolicy.enabled
        try {
            // Fork A (default): the record shader converts HLG->PQ, so the MediaStore column + sidecar
            // are tagged ST.2084 to match the genuinely-PQ pixels.
            NeoNdrTruePqTransportPolicy.set(true)
            val pq = RecordingMediaStoreLifecyclePolicy.resolveColorMetadata(description)
            assertEquals(RecordingMediaStoreColorStandard.BT2020, pq.colorStandard)
            assertEquals(RecordingMediaStoreColorTransfer.ST2084, pq.colorTransfer)
            assertEquals(RecordingMediaStoreColorRange.LIMITED, pq.colorRange)
            assertTrue(pq.sidecarRequired)
            assertEquals("BT.2100 PQ", pq.workingSpace)

            // Fork B fallback: no shader conversion (HLG pixels), so the tag is HLG to match — never a
            // PQ tag over HLG pixels (#2 / commit c1b2d2e). Nominal 1000-nit reference, no ST2086 block.
            NeoNdrTruePqTransportPolicy.set(false)
            val hlg = RecordingMediaStoreLifecyclePolicy.resolveColorMetadata(description)
            assertEquals(RecordingMediaStoreColorTransfer.HLG, hlg.colorTransfer)
            assertEquals("BT.2100 HLG", hlg.workingSpace)
            assertEquals(1_000, hlg.masteringPeakNits)
        } finally {
            NeoNdrTruePqTransportPolicy.set(original)
        }
    }

    @Test
    fun nLogDescriptionPublishesBt2020LinearFullRangeMetadataAndSidecar() {
        val metadata = RecordingMediaStoreLifecyclePolicy.resolveColorMetadata(
            "video=3840x2160@24fps;color=Rec.2020;hdrIntent=OFF;" +
                "dynamicRange=LOG SDR (Rec.2020 10-bit);logCurve=N-LOG v5 APEX-LOG;bitDepth=10-bit"
        )

        assertEquals(RecordingMediaStoreColorStandard.BT2020, metadata.colorStandard)
        assertEquals(RecordingMediaStoreColorTransfer.LINEAR, metadata.colorTransfer)
        assertEquals(RecordingMediaStoreColorRange.FULL, metadata.colorRange)
        assertTrue(metadata.sidecarRequired)
        assertEquals("ACEScg", metadata.workingSpace)
    }

    @Test
    fun wideGamutSdrDescriptionPublishesBt2020SdrLimitedMetadata() {
        val metadata = RecordingMediaStoreLifecyclePolicy.resolveColorMetadata(
            "video=3840x2160@30fps;color=Rec.2020;hdrIntent=OFF;" +
                "dynamicRange=Wide Gamut SDR (Rec.2020 10-bit);logCurve=OFF;bitDepth=10-bit"
        )

        assertEquals(RecordingMediaStoreColorStandard.BT2020, metadata.colorStandard)
        assertEquals(RecordingMediaStoreColorTransfer.SDR_VIDEO, metadata.colorTransfer)
        assertEquals(RecordingMediaStoreColorRange.LIMITED, metadata.colorRange)
    }

    @Test
    fun rec709TenBitSdrDescriptionStaysBt709InsteadOfWideGamut() {
        val metadata = RecordingMediaStoreLifecyclePolicy.resolveColorMetadata(
            "video=1920x1080@30fps;color=Rec.709;hdrIntent=OFF;" +
                "dynamicRange=Original SDR (Rec.709 10-bit);logCurve=OFF;bitDepth=Hardware 10-bit"
        )

        assertEquals(RecordingMediaStoreColorStandard.BT709, metadata.colorStandard)
        assertEquals(RecordingMediaStoreColorTransfer.SDR_VIDEO, metadata.colorTransfer)
        assertEquals(RecordingMediaStoreColorRange.FULL, metadata.colorRange)
        assertFalse(metadata.sidecarRequired)
    }

    @Test
    fun manualSelectedNeoAcesMetadataOverridesStaleRec709BaseColor() {
        val metadata = RecordingMediaStoreLifecyclePolicy.resolveColorMetadata(
            "video=3840x2160@30fps;color=Rec.709;hdrIntent=OFF;" +
                "dynamicRange=Original SDR (Rec.709 10-bit);logCurve=OFF;bitDepth=10-bit;" +
                "selectedColorSpace=Neo ACES AP1;selectedColorScience=Neo ACES High End"
        )

        assertEquals(RecordingMediaStoreColorStandard.BT2020, metadata.colorStandard)
        assertEquals(RecordingMediaStoreColorTransfer.SDR_VIDEO, metadata.colorTransfer)
        assertEquals(RecordingMediaStoreColorRange.LIMITED, metadata.colorRange)
        assertTrue(metadata.sidecarRequired)
        assertEquals("ACEScg", metadata.workingSpace)
    }

    @Test
    fun finalizeDecisionCarriesResolvedColorMetadata() {
        val decision = RecordingMediaStoreLifecyclePolicy.resolveFinalize(
            storageKind = RecordingOutputStorageKind.MEDIASTORE_PENDING_VIDEO,
            pending = true,
            finalDescription = "video=3840x2160@24fps;color=Rec.2020;hdrIntent=OFF;" +
                "dynamicRange=LOG SDR (Rec.2020 10-bit);logCurve=N-LOG v5 APEX-LOG;bitDepth=10-bit"
        )

        assertEquals(RecordingMediaStoreColorTransfer.LINEAR, decision.colorMetadata.colorTransfer)
        assertTrue(decision.writeSidecarMetadata)
    }

    @Test
    fun mediaStoreSidecarsUseAllowedDocumentsCollectionPath() {
        assertEquals(
            "Documents/CamX/Metadata",
            RecordingMediaStoreLifecyclePolicy.resolveMetadataSidecarRelativePath()
        )
    }

    private fun contract() = RecordingMovOutputContract(
        selection = RecordingContainerSelection(
            container = RecordingContainer.MOV,
            fileExtension = "mov",
            mediaStoreMime = "video/quicktime",
            userVisibleLabel = "QuickTime MOV"
        ),
        descriptor = RecordingOutputTargetDescriptor(
            fileName = "CAMX_GPU_20260514_101500.mov",
            mimeType = "video/quicktime",
            containerLabel = "QuickTime MOV"
        ),
        route = RecordingOutputRoute(
            relativePath = "DCIM/CamX",
            legacyRootDirectory = "DCIM",
            legacySubdirectory = "CamX"
        ),
        description = "video=3840x2160@30fps"
    )
}
