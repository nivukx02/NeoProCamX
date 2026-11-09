package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraAutofocusRequestPolicyTest {

    @Test
    fun `ai subject focus keeps hardware af regions when the device supports them`() {
        val plan = CameraAutofocusRequestPolicy.resolve(
            manualFocusDistance = -1f,
            aiDrivenFocusDistance = 2.5f,
            selectedAfMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            maxAfRegions = 1
        )

        assertEquals(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO, plan.afMode)
        assertNull(plan.lensFocusDistance)
        assertTrue(plan.applyAfRegions)
    }

    @Test
    fun `ai subject focus keeps continuous af when metering regions are unavailable`() {
        val plan = CameraAutofocusRequestPolicy.resolve(
            manualFocusDistance = -1f,
            aiDrivenFocusDistance = 2.5f,
            selectedAfMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            maxAfRegions = 0,
            maxFocusDistance = 8f
        )

        assertEquals(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO, plan.afMode)
        assertNull(plan.lensFocusDistance)
        assertFalse(plan.applyAfRegions)
    }

    @Test
    fun `ai subject focus falls back to lens distance only when autofocus mode is unavailable`() {
        val plan = CameraAutofocusRequestPolicy.resolve(
            manualFocusDistance = -1f,
            aiDrivenFocusDistance = 2.5f,
            selectedAfMode = CaptureRequest.CONTROL_AF_MODE_OFF,
            maxAfRegions = 0,
            maxFocusDistance = 8f
        )

        assertEquals(CaptureRequest.CONTROL_AF_MODE_OFF, plan.afMode)
        assertEquals(2.5f, plan.lensFocusDistance)
        assertFalse(plan.applyAfRegions)
    }

    @Test
    fun `manual focus overrides subject autofocus`() {
        val plan = CameraAutofocusRequestPolicy.resolve(
            manualFocusDistance = 4.0f,
            aiDrivenFocusDistance = 2.5f,
            selectedAfMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            maxAfRegions = 1,
            maxFocusDistance = 8f
        )

        assertEquals(CaptureRequest.CONTROL_AF_MODE_OFF, plan.afMode)
        assertEquals(4.0f, plan.lensFocusDistance)
        assertFalse(plan.applyAfRegions)
    }

    @Test
    fun `manual focus clamps to supported lens distance range`() {
        val plan = CameraAutofocusRequestPolicy.resolve(
            manualFocusDistance = 12.0f,
            aiDrivenFocusDistance = -1f,
            selectedAfMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            maxAfRegions = 1,
            maxFocusDistance = 8f
        )

        assertEquals(CaptureRequest.CONTROL_AF_MODE_OFF, plan.afMode)
        assertEquals(8.0f, plan.lensFocusDistance)
        assertFalse(plan.applyAfRegions)
    }

    @Test
    fun `stale lens distance request does not disable continuous af on fixed focus lens`() {
        val plan = CameraAutofocusRequestPolicy.resolve(
            manualFocusDistance = 0f,
            aiDrivenFocusDistance = 2.5f,
            selectedAfMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            maxAfRegions = 0,
            maxFocusDistance = 0f
        )

        assertEquals(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO, plan.afMode)
        assertNull(plan.lensFocusDistance)
        assertFalse(plan.applyAfRegions)
    }

    @Test
    fun `zero manual focus distance keeps continuous af on supported focus lens`() {
        val plan = CameraAutofocusRequestPolicy.resolve(
            manualFocusDistance = 0f,
            aiDrivenFocusDistance = -1f,
            selectedAfMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            maxAfRegions = 1,
            maxFocusDistance = 8f
        )

        assertEquals(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO, plan.afMode)
        assertNull(plan.lensFocusDistance)
        assertTrue(plan.applyAfRegions)
    }

    @Test
    fun `explicit infinity request locks focus at infinity on a supported lens`() {
        val plan = CameraAutofocusRequestPolicy.resolve(
            manualFocusDistance = 0f,
            aiDrivenFocusDistance = -1f,
            selectedAfMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            maxAfRegions = 1,
            maxFocusDistance = 8f,
            manualFocusInfinityRequested = true
        )

        // User dragged manual focus to the infinity endpoint (0 diopters) — lock the lens there (#22).
        assertEquals(CaptureRequest.CONTROL_AF_MODE_OFF, plan.afMode)
        assertEquals(0f, plan.lensFocusDistance!!, 0f)
        assertFalse(plan.applyAfRegions)
    }

    @Test
    fun `explicit infinity request is ignored on a fixed focus lens`() {
        val plan = CameraAutofocusRequestPolicy.resolve(
            manualFocusDistance = 0f,
            aiDrivenFocusDistance = -1f,
            selectedAfMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            maxAfRegions = 1,
            maxFocusDistance = 0f,
            manualFocusInfinityRequested = true
        )

        // Fixed-focus lens (maxFocusDistance == 0) cannot honor a distance — keep autofocus.
        assertEquals(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO, plan.afMode)
        assertNull(plan.lensFocusDistance)
    }

    @Test
    fun `invalid manual focus distance is ignored without disabling autofocus`() {
        val plan = CameraAutofocusRequestPolicy.resolve(
            manualFocusDistance = Float.NaN,
            aiDrivenFocusDistance = -1f,
            selectedAfMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            maxAfRegions = 1,
            maxFocusDistance = 8f
        )

        assertEquals(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO, plan.afMode)
        assertNull(plan.lensFocusDistance)
        assertTrue(plan.applyAfRegions)
    }

    @Test
    fun `invalid ai focus distance is ignored even when autofocus mode is unavailable`() {
        val plan = CameraAutofocusRequestPolicy.resolve(
            manualFocusDistance = -1f,
            aiDrivenFocusDistance = Float.POSITIVE_INFINITY,
            selectedAfMode = CaptureRequest.CONTROL_AF_MODE_OFF,
            maxAfRegions = 0,
            maxFocusDistance = 8f
        )

        assertEquals(CaptureRequest.CONTROL_AF_MODE_OFF, plan.afMode)
        assertNull(plan.lensFocusDistance)
        assertFalse(plan.applyAfRegions)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // s5-f01 / CP-F2: golden lock for region-gating across the afMode x maxAfRegions matrix,
    // override precedence, and idempotence. Expected values ARE today's behavior.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `region gating across af mode and max region matrix`() {
        val video = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        val picture = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        val off = CaptureRequest.CONTROL_AF_MODE_OFF

        // No manual / AI override: regions attach iff afMode != OFF AND maxAfRegions > 0.
        for (mode in listOf(video, picture)) {
            for (regions in listOf(0, 1, 3)) {
                val plan = CameraAutofocusRequestPolicy.resolve(
                    manualFocusDistance = -1f,
                    aiDrivenFocusDistance = -1f,
                    selectedAfMode = mode,
                    maxAfRegions = regions,
                    maxFocusDistance = 8f
                )
                assertEquals(mode, plan.afMode)
                assertNull(plan.lensFocusDistance)
                assertEquals(
                    "mode=" + mode + " regions=" + regions,
                    regions > 0,
                    plan.applyAfRegions
                )
            }
        }
        // afMode OFF (manual-only hardware) never attaches a region even with N>0 regions.
        for (regions in listOf(0, 1, 5)) {
            val plan = CameraAutofocusRequestPolicy.resolve(
                manualFocusDistance = -1f,
                aiDrivenFocusDistance = -1f,
                selectedAfMode = off,
                maxAfRegions = regions,
                maxFocusDistance = 8f
            )
            assertEquals(off, plan.afMode)
            assertFalse("OFF mode must never attach an AF region", plan.applyAfRegions)
        }
    }

    @Test
    fun `max af regions zero never attaches a region under any input`() {
        val inputs = listOf(
            Triple(-1f, -1f, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO),
            Triple(-1f, 2.5f, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO),
            Triple(-1f, 2.5f, CaptureRequest.CONTROL_AF_MODE_OFF),
            Triple(4.0f, 2.5f, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE),
            Triple(-1f, -1f, CaptureRequest.CONTROL_AF_MODE_OFF)
        )
        for ((manual, ai, mode) in inputs) {
            val plan = CameraAutofocusRequestPolicy.resolve(
                manualFocusDistance = manual,
                aiDrivenFocusDistance = ai,
                selectedAfMode = mode,
                maxAfRegions = 0,
                maxFocusDistance = 8f
            )
            assertFalse(
                "maxAfRegions==0 must never attach an AF region (manual=" + manual +
                    " ai=" + ai + " mode=" + mode + ")",
                plan.applyAfRegions
            )
        }
    }

    @Test
    fun `manual override wins over continuous af deterministically`() {
        val plan = CameraAutofocusRequestPolicy.resolve(
            manualFocusDistance = 3.0f,
            aiDrivenFocusDistance = 2.5f,
            selectedAfMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            maxAfRegions = 3,
            maxFocusDistance = 8f
        )
        // Manual override resolves to OFF + the manual lens distance, regardless of region support.
        assertEquals(CaptureRequest.CONTROL_AF_MODE_OFF, plan.afMode)
        assertEquals(3.0f, plan.lensFocusDistance)
        assertFalse(plan.applyAfRegions)
    }

    @Test
    fun `ai override resolves to lens distance only when hardware af is off`() {
        val plan = CameraAutofocusRequestPolicy.resolve(
            manualFocusDistance = -1f,
            aiDrivenFocusDistance = 2.5f,
            selectedAfMode = CaptureRequest.CONTROL_AF_MODE_OFF,
            maxAfRegions = 3,
            maxFocusDistance = 8f
        )
        assertEquals(CaptureRequest.CONTROL_AF_MODE_OFF, plan.afMode)
        assertEquals(2.5f, plan.lensFocusDistance)
        assertFalse(plan.applyAfRegions)
    }

    @Test
    fun `manual override takes precedence over ai override`() {
        val plan = CameraAutofocusRequestPolicy.resolve(
            manualFocusDistance = 1.5f,
            aiDrivenFocusDistance = 2.5f,
            selectedAfMode = CaptureRequest.CONTROL_AF_MODE_OFF,
            maxAfRegions = 1,
            maxFocusDistance = 8f
        )
        // Manual distance wins, not the AI distance.
        assertEquals(CaptureRequest.CONTROL_AF_MODE_OFF, plan.afMode)
        assertEquals(1.5f, plan.lensFocusDistance)
        assertFalse(plan.applyAfRegions)
    }

    @Test
    fun `resolve is idempotent for identical inputs`() {
        repeat(8) {
            val plan = CameraAutofocusRequestPolicy.resolve(
                manualFocusDistance = -1f,
                aiDrivenFocusDistance = -1f,
                selectedAfMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                maxAfRegions = 2,
                maxFocusDistance = 8f
            )
            assertEquals(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO, plan.afMode)
            assertNull(plan.lensFocusDistance)
            assertTrue(plan.applyAfRegions)
        }
    }
}
