package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugCommandIntentReaderTest {

    @Test
    fun hasDebugExtraKey_detectsKnownDebugKeys() {
        assertTrue(
            DebugCommandIntentReader.hasDebugExtraKey(
                setOf(DebugCommandIntentReader.EXTRA_DEBUG_ACTION)
            )
        )
        assertFalse(DebugCommandIntentReader.hasDebugExtraKey(setOf("unrelated")))
    }

    @Test
    fun isDebugCommandAction_acceptsOnlyReceiverAction() {
        assertTrue(DebugCommandIntentReader.isDebugCommandAction(DebugCommandReceiver.ACTION_DEBUG_COMMAND))
        assertFalse(DebugCommandIntentReader.isDebugCommandAction("other"))
        assertFalse(DebugCommandIntentReader.isDebugCommandAction(null))
    }

    @Test
    fun readExtras_prefersTypedDurationValuesBeforeCharSequenceFallback() {
        val extras = DebugCommandIntentReader.readExtras(
            DebugCommandIntentValues(
                action = "record_clip",
                durationLong = 3200L,
                durationInt = 1600,
                durationText = "900"
            )
        )

        assertEquals("3200", extras.durationMs)
    }

    @Test
    fun readExtras_usesIntDurationWhenNoStringOrLongValueExists() {
        val extras = DebugCommandIntentReader.readExtras(
            DebugCommandIntentValues(
                action = "record_clip",
                durationInt = 1600,
                durationText = "900"
            )
        )

        assertEquals("1600", extras.durationMs)
    }

    @Test
    fun readExtras_passesVideoFpsForRecordingMatrixCommands() {
        val extras = DebugCommandIntentReader.readExtras(
            DebugCommandIntentValues(
                action = "record_clip",
                videoFps = "60"
            )
        )

        assertEquals("60", extras.videoFps)
    }

    @Test
    fun readExtras_acceptsTypedVideoFpsFromAdbIntegerExtras() {
        val extras = DebugCommandIntentReader.readExtras(
            DebugCommandIntentValues(
                action = "record_clip",
                videoFpsInt = 25
            )
        )

        assertEquals("25", extras.videoFps)
    }

    @Test
    fun readExtras_passesVideoDetailProfileForRecordedQualityCommands() {
        val extras = DebugCommandIntentReader.readExtras(
            DebugCommandIntentValues(
                action = "record_clip",
                videoDetailProfile = "EXTREME_DETAIL"
            )
        )

        assertEquals("EXTREME_DETAIL", extras.videoDetailProfile)
    }

    @Test
    fun readExtras_passesUncompressedCinemaPipelineForNpv10Commands() {
        val extras = DebugCommandIntentReader.readExtras(
            DebugCommandIntentValues(
                action = "record_clip",
                uncompressedCinemaPipeline = true
            )
        )

        assertEquals(true, extras.uncompressedCinemaPipeline)
    }

    @Test
    fun coerceLongExtraValue_acceptsAdbShellStringWithoutTypedBundleCast() {
        assertEquals(1500L, DebugCommandIntentReader.coerceLongExtraValue("1500"))
    }

    @Test
    fun readExtras_normalizesColorAndLutProfilesThroughCallbacks() {
        val extras = DebugCommandIntentReader.readExtras(
            values = DebugCommandIntentValues(
                colorProfile = " neo flat ",
                lutProfile = " warm lut "
            ),
            normalizeColorProfile = { "color:${it.trim()}" },
            normalizeLutProfile = { "lut:${it.trim()}" }
        )

        assertEquals("color:neo flat", extras.colorProfile)
        assertEquals("lut:warm lut", extras.lutProfile)
    }

    @Test
    fun readParsedCommand_returnsNullForEmptyValues() {
        val parsed = DebugCommandIntentReader.readParsedCommand(DebugCommandIntentValues())

        assertNull(parsed)
    }
}
