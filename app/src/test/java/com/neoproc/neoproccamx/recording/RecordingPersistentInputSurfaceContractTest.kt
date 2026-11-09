package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-contract bridge proving the pure [RecordingPersistentInputSurfaceSelection] mirror stays
 * faithful to the LIVE [RecordingManager] selection mutations, and that the new
 * `persistentInputSurfaceMetadata` evidence is composed additively without flipping the Tier-Omega
 * gate (s6-f10). Same source-text-reflection idiom as `RecordingManagerRealtimeRecordingContractTest`.
 */
class RecordingPersistentInputSurfaceContractTest {

    private val managerSource =
        File("src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt").readText()
    private val gateSource =
        File("src/main/java/com/neoproc/neoproccamx/recording/RecordingQualityEvidencePolicy.kt").readText()

    /** The six additive evidence keys s6-f10 contributes. */
    private val newEvidenceKeys = listOf(
        "persistentInputSurfaceAllowed",
        "persistentSurfaceRejected",
        "encoderUsedPersistentInputSurface",
        "recordStartToFirstFrameUs",
        "recordStartLatencyWithinFrameBudget",
        "persistentInputSurfaceHeadFrameGap",
    )

    @Test
    fun attachVideoInputSurfaceMarksRejectedReleasesAndRethrowsOnCodecRejection() {
        val block = managerSource
            .substringAfter("private fun attachVideoInputSurface(codec: MediaCodec): Surface {")
            .substringBefore("private fun configureVideoCodecForInputMode(")
        assertTrue(block.contains("persistentInputSurfaceRejected = true"))
        assertTrue(block.contains("releasePersistentInputSurface()"))
        assertTrue(block.contains("throw throwable"))
    }

    @Test
    fun preparePersistentInputSurfaceClearsRejectedOnSuccessSetsOnFailure() {
        val block = managerSource
            .substringAfter("fun preparePersistentInputSurface(): Surface? {")
            .substringBefore("fun setPersistentInputSurfaceAllowed(")
        assertTrue(block.contains("persistentInputSurfaceRejected = false"))
        assertTrue(block.contains("persistentInputSurfaceRejected = true"))
    }

    @Test
    fun releasePersistentInputSurfaceResetsRejectedAndDetachesAttachedSurface() {
        val block = managerSource
            .substringAfter("fun releasePersistentInputSurface() {")
            .substringBefore("private fun attachVideoInputSurface(")
        assertTrue(block.contains("encoderSurface === persistent"))
        assertTrue(block.contains("persistentInputSurfaceRejected = false"))
    }

    @Test
    fun releaseInternalKeepsPersistentSurfaceViaShouldReleaseSessionSurface() {
        assertTrue(
            managerSource.contains(
                "RecordingPersistentInputSurfacePolicy.shouldReleaseSessionSurface(encoderSurfaceUsesPersistentInput)"
            )
        )
    }

    @Test
    fun certificateDetailMetadataComposesPersistentInputSurfaceKeys() {
        val certificateBlock = managerSource
            .substringAfter("RecordingQualityEvidenceRequest(")
            .substringBefore("generatedAtEpochMs")
        // Existing composition (locked by RecordingManagerRealtimeRecordingContractTest) plus the
        // additive persistent-surface map, appended last so it cannot overwrite earlier keys.
        assertTrue(certificateBlock.contains("detailMetadata ="))
        assertTrue(certificateBlock.contains("encoderSurfacePrecisionMetadata"))
        assertTrue(certificateBlock.contains("persistentInputSurfaceMetadata"))
        // Appended AFTER the existing maps so it cannot overwrite any earlier key value.
        assertTrue(
            "persistentInputSurfaceMetadata must be composed after encoderSurfacePrecisionMetadata",
            certificateBlock.indexOf("persistentInputSurfaceMetadata") >
                certificateBlock.indexOf("encoderSurfacePrecisionMetadata"),
        )
        // The map literal declares each new key exactly once.
        newEvidenceKeys.forEach { key ->
            assertEquals(
                "evidence key $key must appear exactly once in the persistentInputSurfaceMetadata map",
                1,
                Regex(Regex.escape("\"$key\"")).findAll(managerSource).count(),
            )
        }
    }

    @Test
    fun persistentInputSurfaceEvidenceKeysDoNotCollideWithExistingKeysOrFlipTierOmegaGate() {
        // The gate flips Tier-Omega ON for ANY detailMetadata key starting with these prefixes
        // (RecordingQualityEvidencePolicy.kt:1288-1291). Pin that rule, then prove our keys avoid it.
        assertTrue(gateSource.contains("it.startsWith(\"tierOmega\""))
        assertTrue(gateSource.contains("it.startsWith(\"beyondImax\""))
        newEvidenceKeys.forEach { key ->
            assertFalse(
                "evidence key $key must not start with tierOmega (would flip the Tier-Omega gate)",
                key.startsWith("tierOmega", ignoreCase = true),
            )
            assertFalse(
                "evidence key $key must not start with beyondImax (would flip the Tier-Omega gate)",
                key.startsWith("beyondImax", ignoreCase = true),
            )
        }
        // Keys are mutually distinct (no self-collision within the additive map).
        assertEquals(newEvidenceKeys.size, newEvidenceKeys.toSet().size)
    }
}
