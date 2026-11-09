package com.neoproc.neoproccamx.recording

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectNdrRecordedArtifactVerifierPolicyInstrumentedTest {

    @Test
    fun authoritativeContainerIdentity_acceptsMovAndNivukxOnly() {
        assertEquals(
            ProjectNdrRecordedArtifactVerifier.PublishedContainer.QUICKTIME_MOV,
            ProjectNdrRecordedArtifactVerifier.containerForDisplayName("CamX master.MOV")
        )
        assertEquals(
            ProjectNdrRecordedArtifactVerifier.PublishedContainer.NIVUKX,
            ProjectNdrRecordedArtifactVerifier.containerForDisplayName("CamX master.NIVUKX")
        )
        assertTrue(ProjectNdrRecordedArtifactVerifier.isSupportedRecordedFileName("CamX.mov"))
        assertTrue(ProjectNdrRecordedArtifactVerifier.isSupportedRecordedFileName("CamX.nivukx"))
        assertFalse(ProjectNdrRecordedArtifactVerifier.isSupportedRecordedFileName("CamX.mp4"))

        val rejected = runCatching {
            ProjectNdrRecordedArtifactVerifier.containerForDisplayName("CamX.mp4")
        }.exceptionOrNull()
        assertTrue(rejected is AssertionError)
    }

    @Test
    fun nivukxSidecarName_supportsMediaStoreCollisionRenamingWithoutGuessingAnotherClip() {
        assertEquals(
            listOf("CamX master.nivukx.metadata.json"),
            ProjectNdrRecordedArtifactVerifier.sidecarDisplayNameCandidates("CamX master.nivukx")
        )
        assertEquals(
            listOf(
                "CamX master (4).nivukx.metadata.json",
                "CamX master.nivukx.metadata (4).json"
            ),
            ProjectNdrRecordedArtifactVerifier.sidecarDisplayNameCandidates("CamX master (4).nivukx")
        )
    }

    @Test
    fun nivukxSidecarFileReference_requiresExactFileOrItsMediaStoreCollisionOrigin() {
        assertTrue(
            ProjectNdrRecordedArtifactVerifier.sidecarFileReferenceMatchesClip(
                recordedFileName = "CamX master.nivukx",
                sidecarFileName = "CamX master.nivukx"
            )
        )
        assertTrue(
            ProjectNdrRecordedArtifactVerifier.sidecarFileReferenceMatchesClip(
                recordedFileName = "CamX master (4).nivukx",
                sidecarFileName = "CamX master.nivukx"
            )
        )
        assertFalse(
            ProjectNdrRecordedArtifactVerifier.sidecarFileReferenceMatchesClip(
                recordedFileName = "CamX master (4).nivukx",
                sidecarFileName = "CamX other.nivukx"
            )
        )
    }
}
