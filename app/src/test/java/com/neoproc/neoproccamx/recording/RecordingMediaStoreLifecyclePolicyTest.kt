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
    fun androidQAndAboveStoresNivukxAsPendingGenericFile() {
        val plan = RecordingMediaStoreLifecyclePolicy.resolveCreate(
            sdkInt = 36,
            contract = nivukxContract()
        )

        assertEquals(RecordingOutputStorageKind.MEDIASTORE_PENDING_FILE, plan.storageKind)
        assertTrue(plan.pending)
        assertEquals("CAMX_GPU_20260514_101500.nivukx", plan.displayName)
        assertEquals(NivukxContainerFormat.MEDIASTORE_MIME_TYPE, plan.mimeType)
        assertEquals("Documents/CamX", plan.relativePath)
    }

    @Test
    fun pendingNivukxFilePublishesAndCleansUpThroughMediaStore() {
        val finalize = RecordingMediaStoreLifecyclePolicy.resolveFinalize(
            storageKind = RecordingOutputStorageKind.MEDIASTORE_PENDING_FILE,
            pending = true,
            finalDescription = "dynamicRange=NIVUKX Vision;finalized=true"
        )
        val cleanup = RecordingMediaStoreLifecyclePolicy.resolveFailureCleanup(
            storageKind = RecordingOutputStorageKind.MEDIASTORE_PENDING_FILE,
            hasUri = true,
            hasFile = false
        )

        assertTrue(finalize.publishMediaStore)
        assertFalse(finalize.scanLegacyFile)
        assertTrue(cleanup.deleteMediaStoreUri)
        assertFalse(cleanup.deleteLegacyFile)
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
    fun hdrDescriptionTransportFailsClosedToPq() {
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
            assertEquals(10_000.0, pq.masteringPeakNits, 0.0)

            // Finalization is bound to the session description, not this mutable debug flag.
            NeoNdrTruePqTransportPolicy.set(false)
            val failClosed = RecordingMediaStoreLifecyclePolicy.resolveColorMetadata(description)
            assertEquals(RecordingMediaStoreColorTransfer.ST2084, failClosed.colorTransfer)
            assertEquals("BT.2100 PQ", failClosed.workingSpace)
            assertEquals(10_000.0, failClosed.masteringPeakNits, 0.0)
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
    fun explicitSessionRangeOverridesEveryLegacyModeFallback() {
        val hdrFull = RecordingMediaStoreLifecyclePolicy.resolveColorMetadata(
            "hdrIntent=NDR10+;dynamicRange=NDR10+ BT.2100 PQ;colorRange=FULL;masteringPeakNits=5000"
        )
        val logLimited = RecordingMediaStoreLifecyclePolicy.resolveColorMetadata(
            "dynamicRange=LOG SDR (Rec.2020 10-bit);logCurve=N-LOG;colorRange=LIMITED;" +
                "masteringPeakNits=NONE"
        )
        val sdrFull = RecordingMediaStoreLifecyclePolicy.resolveColorMetadata(
            "dynamicRange=Original SDR (Rec.709 8-bit);bitDepth=8-bit;colorRange=FULL;" +
                "masteringPeakNits=NONE"
        )

        assertEquals(RecordingMediaStoreColorRange.FULL, hdrFull.colorRange)
        assertEquals(RecordingMediaStoreColorRange.LIMITED, logLimited.colorRange)
        assertEquals(RecordingMediaStoreColorRange.FULL, sdrFull.colorRange)
    }

    @Test
    fun brandedPqModesUseCanonicalMasteringPeaks() {
        val ndr10 = RecordingMediaStoreLifecyclePolicy.resolveColorMetadata(
            "hdrIntent=NDR10+;dynamicRange=NDR10+ BT.2100 PQ;masteringPeakNits=10000"
        )
        val nivukx = RecordingMediaStoreLifecyclePolicy.resolveColorMetadata(
            "hdrIntent=NIVUKXVISION;dynamicRange=NIVUKX Vision;masteringPeakNits=5000"
        )
        val apex = RecordingMediaStoreLifecyclePolicy.resolveColorMetadata(
            "hdrIntent=APEX_ZERO;dynamicRange=NIVUKX APEX ZERO;masteringPeakNits=5000"
        )

        assertEquals(5_000.0, ndr10.masteringPeakNits, 0.0)
        assertEquals(10_000.0, nivukx.masteringPeakNits, 0.0)
        assertEquals(10_000.0, apex.masteringPeakNits, 0.0)
    }

    @Test
    fun genericPqAcceptsOnlyFiniteInRangeExplicitPeak() {
        val valid = RecordingMediaStoreLifecyclePolicy.resolveColorMetadata(
            "dynamicRange=BT.2100 PQ;colorRange=FULL;masteringPeakNits=4321.5"
        )
        assertEquals(4_321.5, valid.masteringPeakNits, 0.0)

        listOf(
            "",
            "NONE",
            "malformed",
            "NaN",
            "Infinity",
            "999.9",
            "10000.1"
        ).forEach { value ->
            val metadata = RecordingMediaStoreLifecyclePolicy.resolveColorMetadata(
                "dynamicRange=BT.2100 PQ;masteringPeakNits=$value"
            )
            assertEquals(1_000.0, metadata.masteringPeakNits, 0.0)
        }
    }

    @Test
    fun appendedSceneMetadataCannotOverrideAuthoritativeRangeOrPeak() {
        val metadata = RecordingMediaStoreLifecyclePolicy.resolveColorMetadata(
            "dynamicRange=BT.2100 PQ;colorRange=FULL;masteringPeakNits=4000;" +
                "colorRange=LIMITED;masteringPeakNits=9000"
        )

        assertEquals(RecordingMediaStoreColorRange.FULL, metadata.colorRange)
        assertEquals(4_000.0, metadata.masteringPeakNits, 0.0)
    }

    @Test
    fun malformedRangePreservesLegacyFallback() {
        val hdr = RecordingMediaStoreLifecyclePolicy.resolveColorMetadata(
            "dynamicRange=BT.2100 PQ;colorRange=full;masteringPeakNits=4000"
        )
        val log = RecordingMediaStoreLifecyclePolicy.resolveColorMetadata(
            "dynamicRange=LOG SDR (Rec.2020 10-bit);logCurve=N-LOG;colorRange=WIDE"
        )

        assertEquals(RecordingMediaStoreColorRange.LIMITED, hdr.colorRange)
        assertEquals(RecordingMediaStoreColorRange.FULL, log.colorRange)
    }

    @Test
    fun finalizeCarriesExactSessionRangeAndMasteringPeak() {
        val decision = RecordingMediaStoreLifecyclePolicy.resolveFinalize(
            storageKind = RecordingOutputStorageKind.MEDIASTORE_PENDING_VIDEO,
            pending = true,
            finalDescription = "dynamicRange=BT.2100 PQ;colorRange=FULL;masteringPeakNits=6789.25"
        )

        assertEquals(RecordingMediaStoreColorRange.FULL, decision.colorMetadata.colorRange)
        assertEquals(6_789.25, decision.colorMetadata.masteringPeakNits, 0.0)
        assertEquals(decision.colorMetadata, decision.transaction.colorMetadata)
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

    private fun nivukxContract() = contract().copy(
        selection = RecordingContainerSelection(
            container = RecordingContainer.NIVUKX,
            fileExtension = NivukxContainerFormat.FILE_EXTENSION,
            mediaStoreMime = NivukxContainerFormat.MEDIASTORE_MIME_TYPE,
            userVisibleLabel = NivukxContainerFormat.USER_VISIBLE_LABEL
        ),
        descriptor = RecordingOutputTargetDescriptor(
            fileName = "CAMX_GPU_20260514_101500.nivukx",
            mimeType = NivukxContainerFormat.MEDIASTORE_MIME_TYPE,
            containerLabel = NivukxContainerFormat.USER_VISIBLE_LABEL
        ),
        description = "video=3840x2160@30fps;dynamicRange=NIVUKX Vision"
    )
}
