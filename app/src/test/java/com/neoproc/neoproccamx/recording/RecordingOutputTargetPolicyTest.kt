package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingOutputTargetPolicyTest {

    @Test
    fun movSelection_buildsQuickTimeOutputTarget() {
        val target = RecordingOutputTargetPolicy.resolve(
            selection = RecordingContainerSelection(
                container = RecordingContainer.MOV,
                fileExtension = "mov",
                mediaStoreMime = "video/quicktime",
                userVisibleLabel = "QuickTime MOV"
            ),
            baseName = "CamX 25042026",
            outputLabelSuffix = "_LOG"
        )

        assertEquals("CamX 25042026_LOG.mov", target.fileName)
        assertEquals("video/quicktime", target.mimeType)
        assertEquals("QuickTime MOV", target.containerLabel)
    }
}
