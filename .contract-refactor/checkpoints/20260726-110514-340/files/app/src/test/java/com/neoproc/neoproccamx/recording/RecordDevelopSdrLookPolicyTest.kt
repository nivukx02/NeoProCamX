package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.rendering.FilmPrintLookRecordFlag
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Locks the OFFLINE SDR develop creative-look parity fix ([RecordDevelopSdrLookPolicy]):
 *  - the film-print strength tracks the user's film-look preference (ON ⇒ full grade, OFF ⇒ 0 neutral);
 *  - a develop pipeline built with the ON strength is genuinely NON-identity on SDR (the grade reaches
 *    pixels), while the OFF strength is a strict passthrough (no-downgrade);
 *  - the strength stays in the pipeline's legal [0,1] blend range.
 */
class RecordDevelopSdrLookPolicyTest {

    @Before
    fun clearFilmLookCallback() {
        // Defensive: drop any onChanged callback another test may have left registered, so this test's
        // set() toggles can never invoke a stale listener (matches FilmPrintLookRecordFlagTest hygiene).
        FilmPrintLookRecordFlag.registerOnChanged(null)
    }

    @After
    fun restoreFilmLookFlag() {
        // The film-look preference is a process-global A/B flag; never leak a toggled state across tests.
        FilmPrintLookRecordFlag.set(true)
    }

    @Test
    fun `film-look ON applies the full film-print grade`() {
        assertEquals(
            RecordDevelopSdrLookPolicy.FILM_PRINT_DEVELOP_STRENGTH,
            RecordDevelopSdrLookPolicy.filmPrintStrength(filmLookEnabled = true),
            0f
        )
    }

    @Test
    fun `film-look OFF collapses to a neutral develop`() {
        assertEquals(0f, RecordDevelopSdrLookPolicy.filmPrintStrength(filmLookEnabled = false), 0f)
    }

    @Test
    fun `develop strength stays within the legal blend range`() {
        val on = RecordDevelopSdrLookPolicy.filmPrintStrength(filmLookEnabled = true)
        assertTrue("strength must be in [0,1] for the Stage-C blend", on in 0f..1f)
    }

    @Test
    fun `product wiring tracks the live film-look flag both ways`() {
        FilmPrintLookRecordFlag.set(true)
        assertEquals(
            RecordDevelopSdrLookPolicy.FILM_PRINT_DEVELOP_STRENGTH,
            RecordDevelopSdrLookPolicy.developFilmPrintStrength(),
            0f
        )
        FilmPrintLookRecordFlag.set(false)
        assertEquals(0f, RecordDevelopSdrLookPolicy.developFilmPrintStrength(), 0f)
    }

    @Test
    fun `ON strength makes the SDR develop pipeline genuinely non-identity`() {
        // Prove the fix actually reaches pixels: a mid-tone SDR pixel developed with only the film-print
        // stage active must move (creative grade applied), while the OFF strength is a strict passthrough.
        val on = RecordDevelopSdrLookPolicy.filmPrintStrength(filmLookEnabled = true)
        val graded = VideoDevelopColorPipeline(
            options = VideoDevelopColorOptions(
                target = DevelopTarget.SDR_REC709,
                snceAppearanceStrength = 0f, // isolate Stage C so this asserts the film-print grade specifically
                filmPrintStrength = on,
                acesToneMap = false
            )
        )
        val neutral = VideoDevelopColorPipeline(
            options = VideoDevelopColorOptions(
                target = DevelopTarget.SDR_REC709,
                snceAppearanceStrength = 0f,
                filmPrintStrength = 0f,
                acesToneMap = false
            )
        )

        val gradedPixel = graded.developPixel(0.45f, 0.5f, 0.55f)
        val neutralPixel = neutral.developPixel(0.45f, 0.5f, 0.55f)

        // OFF path is a strict passthrough (no-downgrade contract).
        assertEquals(0.45f, neutralPixel[0], 1e-6f)
        assertEquals(0.5f, neutralPixel[1], 1e-6f)
        assertEquals(0.55f, neutralPixel[2], 1e-6f)

        // ON path moves at least one channel — the creative grade genuinely reaches pixels.
        val moved = kotlin.math.abs(gradedPixel[0] - neutralPixel[0]) > 1e-4f ||
            kotlin.math.abs(gradedPixel[1] - neutralPixel[1]) > 1e-4f ||
            kotlin.math.abs(gradedPixel[2] - neutralPixel[2]) > 1e-4f
        assertTrue("film-print grade must change the developed SDR pixel", moved)
        assertNotEquals(0f, on)
    }
}
