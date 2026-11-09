package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraLensSelectionPolicyTest {

    @Test
    fun `back selection prefers logical zoom-capable candidate`() {
        val selected = CameraLensSelectionPolicy.selectPreferredCandidate(
            requestedFacing = CameraCharacteristics.LENS_FACING_BACK,
            candidates = listOf(
                CameraLensCandidate("wide", CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL, 24f, false, 1f, 1f),
                CameraLensCandidate("logical", CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED, 24f, true, 0.5f, 6f)
            )
        )

        assertEquals("logical", selected?.cameraId)
    }

    @Test
    fun `back selection falls back to median focal when no logical candidate exists`() {
        val selected = CameraLensSelectionPolicy.selectPreferredCandidate(
            requestedFacing = CameraCharacteristics.LENS_FACING_BACK,
            candidates = listOf(
                CameraLensCandidate("ultra", CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL, 13f, false, 1f, 1f),
                CameraLensCandidate("main", CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL, 24f, false, 1f, 1f),
                CameraLensCandidate("tele", CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL, 70f, false, 1f, 1f)
            )
        )

        assertEquals("main", selected?.cameraId)
    }

    @Test
    fun `front selection prefers higher-rank logical candidate`() {
        val selected = CameraLensSelectionPolicy.selectPreferredCandidate(
            requestedFacing = CameraCharacteristics.LENS_FACING_FRONT,
            candidates = listOf(
                CameraLensCandidate("front-basic", CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED, 24f, false, 1f, 1f),
                CameraLensCandidate("front-logical", CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL, 24f, true, 1f, 2f)
            )
        )

        assertEquals("front-logical", selected?.cameraId)
    }

    @Test
    fun `back selection prefers 35MP+ sensor candidate when available`() {
        val selected = CameraLensSelectionPolicy.selectPreferredCandidate(
            requestedFacing = CameraCharacteristics.LENS_FACING_BACK,
            candidates = listOf(
                CameraLensCandidate(
                    cameraId = "logical12mp",
                    level = CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3,
                    focalLength = 24f,
                    logical = true,
                    zoomLower = 0.5f,
                    zoomUpper = 6f,
                    sensorPixels = 12_000_000L,
                    supportsRaw = true
                ),
                CameraLensCandidate(
                    cameraId = "main50mp",
                    level = CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
                    focalLength = 24f,
                    logical = false,
                    zoomLower = 1f,
                    zoomUpper = 1f,
                    sensorPixels = 50_000_000L,
                    supportsRaw = true,
                    hasOis = true
                )
            )
        )

        assertEquals("main50mp", selected?.cameraId)
    }

    @Test
    fun `back selection does not prefer higher megapixel legacy sensor over high end full level sensor`() {
        val selected = CameraLensSelectionPolicy.selectPreferredCandidate(
            requestedFacing = CameraCharacteristics.LENS_FACING_BACK,
            candidates = listOf(
                CameraLensCandidate(
                    cameraId = "legacy64mp",
                    level = CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY,
                    focalLength = 24f,
                    logical = false,
                    zoomLower = 1f,
                    zoomUpper = 1f,
                    sensorPixels = 64_000_000L,
                    supportsRaw = false,
                    supports10BitHdr = false,
                    hasOis = false,
                    maxFps = 30
                ),
                CameraLensCandidate(
                    cameraId = "full50mp-ois-main10",
                    level = CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
                    focalLength = 24f,
                    logical = false,
                    zoomLower = 1f,
                    zoomUpper = 2f,
                    sensorPixels = 50_000_000L,
                    supportsRaw = true,
                    supports10BitHdr = true,
                    hasOis = true,
                    hasPhaseDetect = true,
                    maxFps = 60
                )
            )
        )

        assertEquals("full50mp-ois-main10", selected?.cameraId)
    }

    @Test
    fun `back selection ignores non-openable candidate when openable candidate exists`() {
        val selected = CameraLensSelectionPolicy.selectPreferredCandidate(
            requestedFacing = CameraCharacteristics.LENS_FACING_BACK,
            candidates = listOf(
                CameraLensCandidate(
                    cameraId = "hidden-physical",
                    level = CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3,
                    focalLength = 70f,
                    logical = false,
                    zoomLower = 1f,
                    zoomUpper = 1f,
                    sensorPixels = 64_000_000L,
                    supportsRaw = true,
                    openable = false
                ),
                CameraLensCandidate(
                    cameraId = "main-openable",
                    level = CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
                    focalLength = 24f,
                    logical = true,
                    zoomLower = 0.5f,
                    zoomUpper = 6f,
                    sensorPixels = 50_000_000L,
                    supportsRaw = true,
                    hasOis = true,
                    openable = true
                )
            )
        )

        assertEquals("main-openable", selected?.cameraId)
    }

    @Test
    fun `equivalent focal length uses real sensor diagonal instead of fixed crop only`() {
        val equivalent = CameraLensSelectionPolicy.equivalentFocalLength35mm(
            focalLengthMm = 4.3f,
            sensorWidthMm = 7.0f,
            sensorHeightMm = 5.2f
        )

        val resolvedEquivalent = requireNotNull(equivalent)
        assertTrue(resolvedEquivalent in 21f..23f)
        assertEquals(LensType.WIDE, CameraLensSelectionPolicy.classifyLensTypeByEquivalent(resolvedEquivalent))
    }

    // CP-QV23 — golden lock on the 35mm-equivalent HUD badge label.

    @Test
    fun `label for equivalent focal length is a rounded mm plus lens type`() {
        // 4.3mm on a 7.0x5.2mm sensor -> ~21mm equivalent (Wide band 18..35).
        val wide = CameraLensSelectionPolicy.equivalentFocalLength35mm(
            focalLengthMm = 4.3f,
            sensorWidthMm = 7.0f,
            sensorHeightMm = 5.2f
        )
        assertEquals("21mm Wide", CameraLensSelectionPolicy.labelForEquivalentFocalLength(wide))

        // 8.0mm on the same sensor -> ~40mm equivalent (Standard/Main band 35..60).
        val main = CameraLensSelectionPolicy.equivalentFocalLength35mm(
            focalLengthMm = 8.0f,
            sensorWidthMm = 7.0f,
            sensorHeightMm = 5.2f
        )
        assertEquals("40mm Main", CameraLensSelectionPolicy.labelForEquivalentFocalLength(main))
    }

    @Test
    fun `label for null equivalent focal length falls back to Camera without a mm prefix`() {
        assertEquals("Camera", CameraLensSelectionPolicy.labelForEquivalentFocalLength(null))
    }

    // ─── s5-f10 / CP-F11: task-aware selection wiring ───────────────────────────────────────────

    private val taskFixtureBack: List<CameraLensCandidate> = listOf(
        CameraLensCandidate(
            cameraId = "bright-ois-12mp",
            level = CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
            focalLength = 24f,
            logical = false,
            zoomLower = 1f,
            zoomUpper = 1f,
            aperture = 1.4f,
            sensorPixels = 12_000_000L,
            hasOis = true,
            supportsRaw = true,
            supports10BitHdr = true,
            maxFps = 60,
            lensType = LensType.STANDARD
        ),
        CameraLensCandidate(
            cameraId = "dim-tele-noois",
            level = CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
            focalLength = 70f,
            logical = false,
            zoomLower = 1f,
            zoomUpper = 1f,
            aperture = 2.4f,
            sensorPixels = 50_000_000L,
            hasOis = false,
            supportsRaw = false,
            supports10BitHdr = false,
            maxFps = 30,
            lensType = LensType.SHORT_TELEPHOTO
        ),
        CameraLensCandidate(
            cameraId = "wide-ultra",
            level = CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED,
            focalLength = 13f,
            logical = false,
            zoomLower = 1f,
            zoomUpper = 1f,
            aperture = 2.2f,
            sensorPixels = 8_000_000L,
            hasOis = false,
            supportsRaw = false,
            supports10BitHdr = false,
            maxFps = 30,
            lensType = LensType.ULTRA_WIDE
        )
    )

    @Test
    fun `low-light task picks the brightest aperture candidate`() {
        val selected = CameraLensSelectionPolicy.selectForTask(
            requestedFacing = CameraCharacteristics.LENS_FACING_BACK,
            candidates = taskFixtureBack,
            hint = LensSelectionTaskHint.LowLight,
            gateEnabled = true
        )

        // f/1.4 (1/1.4=0.714) beats f/2.4 and f/2.2 on the first comparator of selectLowLightCandidate.
        assertEquals("bright-ois-12mp", selected?.cameraId)
        assertEquals(
            CameraLensSelectionPolicy.selectLowLightCandidate(taskFixtureBack)?.cameraId,
            selected?.cameraId
        )
    }

    @Test
    fun `video task picks the cinema-capable OIS candidate`() {
        val selected = CameraLensSelectionPolicy.selectForTask(
            requestedFacing = CameraCharacteristics.LENS_FACING_BACK,
            candidates = taskFixtureBack,
            hint = LensSelectionTaskHint.Video(),
            gateEnabled = true
        )

        // bright-ois-12mp is the only isCinemaCapable (FULL + RAW + 60fps) and has OIS.
        assertEquals("bright-ois-12mp", selected?.cameraId)
        assertEquals(
            CameraLensSelectionPolicy.selectVideoCandidate(taskFixtureBack)?.cameraId,
            selected?.cameraId
        )
    }

    @Test
    fun `by-type task returns exact lens-type match when present`() {
        val selected = CameraLensSelectionPolicy.selectForTask(
            requestedFacing = CameraCharacteristics.LENS_FACING_BACK,
            candidates = taskFixtureBack,
            hint = LensSelectionTaskHint.ByLensType(LensType.SHORT_TELEPHOTO),
            gateEnabled = true
        )

        assertEquals("dim-tele-noois", selected?.cameraId)
    }

    @Test
    fun `by-type task falls back to closest focal length when no exact match`() {
        // No TELEPHOTO candidate exists; target focal = 85/5.5 ~= 15.45mm.
        val candidates = listOf(
            CameraLensCandidate(
                cameraId = "near20",
                level = CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
                focalLength = 20f,
                logical = false,
                zoomLower = 1f,
                zoomUpper = 1f,
                lensType = LensType.STANDARD
            ),
            CameraLensCandidate(
                cameraId = "far5",
                level = CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
                focalLength = 5f,
                logical = false,
                zoomLower = 1f,
                zoomUpper = 1f,
                lensType = LensType.ULTRA_WIDE
            )
        )

        val selected = CameraLensSelectionPolicy.selectForTask(
            requestedFacing = CameraCharacteristics.LENS_FACING_BACK,
            candidates = candidates,
            hint = LensSelectionTaskHint.ByLensType(LensType.TELEPHOTO),
            gateEnabled = true
        )

        // |20-15.45|=4.55 < |5-15.45|=10.45 -> near20 is the closest focal.
        assertEquals("near20", selected?.cameraId)
        assertEquals(
            CameraLensSelectionPolicy.selectCandidateForLensType(candidates, LensType.TELEPHOTO)?.cameraId,
            selected?.cameraId
        )
    }

    @Test
    fun `task selection is deterministic for a fixed candidate fixture`() {
        val first = CameraLensSelectionPolicy.selectForTask(
            CameraCharacteristics.LENS_FACING_BACK,
            taskFixtureBack,
            LensSelectionTaskHint.LowLight,
            gateEnabled = true
        )
        val second = CameraLensSelectionPolicy.selectForTask(
            CameraCharacteristics.LENS_FACING_BACK,
            taskFixtureBack,
            LensSelectionTaskHint.LowLight,
            gateEnabled = true
        )

        assertEquals(first?.cameraId, second?.cameraId)
        assertEquals("bright-ois-12mp", first?.cameraId)
    }

    @Test
    fun `default-unchanged - gate off reproduces selectPreferredCandidate exactly`() {
        val expected = CameraLensSelectionPolicy.selectPreferredCandidate(
            CameraCharacteristics.LENS_FACING_BACK,
            taskFixtureBack
        )

        // Gate OFF: even a non-None hint is ignored.
        val gateOff = CameraLensSelectionPolicy.selectForTask(
            CameraCharacteristics.LENS_FACING_BACK,
            taskFixtureBack,
            LensSelectionTaskHint.LowLight,
            gateEnabled = false
        )
        assertEquals(expected?.cameraId, gateOff?.cameraId)

        // Gate ON but hint None: still exactly the preferred pick.
        val noHint = CameraLensSelectionPolicy.selectForTask(
            CameraCharacteristics.LENS_FACING_BACK,
            taskFixtureBack,
            LensSelectionTaskHint.None,
            gateEnabled = true
        )
        assertEquals(expected?.cameraId, noHint?.cameraId)
    }

    @Test
    fun `task selection on empty candidate list returns null`() {
        val empty = emptyList<CameraLensCandidate>()
        assertEquals(null, CameraLensSelectionPolicy.selectForTask(
            CameraCharacteristics.LENS_FACING_BACK, empty, LensSelectionTaskHint.LowLight, gateEnabled = true))
        assertEquals(null, CameraLensSelectionPolicy.selectForTask(
            CameraCharacteristics.LENS_FACING_BACK, empty, LensSelectionTaskHint.Video(), gateEnabled = true))
        assertEquals(null, CameraLensSelectionPolicy.selectForTask(
            CameraCharacteristics.LENS_FACING_BACK, empty, LensSelectionTaskHint.ByLensType(LensType.WIDE), gateEnabled = true))
        assertEquals(null, CameraLensSelectionPolicy.selectForTask(
            CameraCharacteristics.LENS_FACING_BACK, empty, LensSelectionTaskHint.None, gateEnabled = false))
    }

    @Test
    fun `task selection ignores non-openable candidate via openable pre-filter`() {
        val candidates = listOf(
            CameraLensCandidate(
                cameraId = "hidden-bright",
                level = CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3,
                focalLength = 24f,
                logical = false,
                zoomLower = 1f,
                zoomUpper = 1f,
                aperture = 1.2f,
                sensorPixels = 12_000_000L,
                hasOis = true,
                openable = false
            ),
            CameraLensCandidate(
                cameraId = "openable-main",
                level = CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
                focalLength = 24f,
                logical = false,
                zoomLower = 1f,
                zoomUpper = 1f,
                aperture = 1.8f,
                sensorPixels = 50_000_000L,
                hasOis = true,
                openable = true
            )
        )

        // hidden-bright has the wider aperture but is not openable -> filtered out before scoring.
        val selected = CameraLensSelectionPolicy.selectForTask(
            CameraCharacteristics.LENS_FACING_BACK,
            candidates,
            LensSelectionTaskHint.LowLight,
            gateEnabled = true
        )
        assertEquals("openable-main", selected?.cameraId)
    }
}
