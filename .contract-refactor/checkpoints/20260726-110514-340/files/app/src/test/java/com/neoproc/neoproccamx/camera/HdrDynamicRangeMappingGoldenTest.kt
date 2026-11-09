package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * s1-f05 — golden lock for the dynamic-range profile mapping (Phase-0 no-downgrade discipline).
 *
 * Freezes the full [HdrDynamicRangePolicy.resolveProfilePure] matrix — user mode × hardware support
 * tier × surface role — so a future edit cannot silently change which 10-bit transport is negotiated,
 * silently downgrade a request, or break preview↔encoder parity. Uses `resolveProfilePure` so the
 * mapping is deterministic on the plain-JVM runner (the API-33 gate in `resolveProfile` is bypassed).
 */
class HdrDynamicRangeMappingGoldenTest {

    private val standardOnly = HdrDynamicRangePolicy.buildSupport(emptySet())
    private val hlgOnly = HdrDynamicRangePolicy.buildSupport(setOf(HdrDynamicRangePolicy.PROFILE_HLG10))
    private val hdr10Only = HdrDynamicRangePolicy.buildSupport(setOf(HdrDynamicRangePolicy.PROFILE_HDR10))
    private val hdr10PlusOnly = HdrDynamicRangePolicy.buildSupport(setOf(HdrDynamicRangePolicy.PROFILE_HDR10_PLUS))
    private val full = HdrDynamicRangePolicy.buildSupport(
        setOf(
            HdrDynamicRangePolicy.PROFILE_HLG10,
            HdrDynamicRangePolicy.PROFILE_HDR10,
            HdrDynamicRangePolicy.PROFILE_HDR10_PLUS
        )
    )

    private data class Golden(
        val mode: HdrDynamicRangeMode,
        val support: HdrDynamicRangePolicy.Support,
        val expected: Long?
    )

    // Frozen visible-path (PREVIEW/ENCODER) mapping. RAW/JPEG/ANALYSIS are always STANDARD (null).
    private val goldens = listOf(
        // SDR is always STANDARD.
        Golden(HdrDynamicRangeMode.SDR, full, null),
        Golden(HdrDynamicRangeMode.SDR, standardOnly, null),
        // Standard-only hardware never negotiates a 10-bit profile, whatever the request.
        Golden(HdrDynamicRangeMode.HLG, standardOnly, null),
        Golden(HdrDynamicRangeMode.HDR10, standardOnly, null),
        Golden(HdrDynamicRangeMode.HDR10_PLUS, standardOnly, null),
        // HLG resolves only when HLG10 is supported.
        Golden(HdrDynamicRangeMode.HLG, hlgOnly, HdrDynamicRangePolicy.PROFILE_HLG10),
        Golden(HdrDynamicRangeMode.HLG, hdr10Only, null),
        Golden(HdrDynamicRangeMode.HLG, full, HdrDynamicRangePolicy.PROFILE_HLG10),
        // HDR10 prefers HDR10, falls back to HLG10.
        Golden(HdrDynamicRangeMode.HDR10, hdr10Only, HdrDynamicRangePolicy.PROFILE_HDR10),
        Golden(HdrDynamicRangeMode.HDR10, hlgOnly, null),
        Golden(HdrDynamicRangeMode.HDR10, full, HdrDynamicRangePolicy.PROFILE_HDR10),
        // HDR10+ prefers HDR10+, then HDR10. HLG is never substituted for Neo PQ delivery.
        Golden(HdrDynamicRangeMode.HDR10_PLUS, full, HdrDynamicRangePolicy.PROFILE_HDR10_PLUS),
        Golden(HdrDynamicRangeMode.HDR10_PLUS, hdr10Only, HdrDynamicRangePolicy.PROFILE_HDR10),
        Golden(HdrDynamicRangeMode.HDR10_PLUS, hlgOnly, null),
        // HDR10+-only device: isolates the primary HDR10+ branch, and proves HDR10/HLG do NOT
        // silently fall to STANDARD via a corrupted fallback (they have no lower profile to use here).
        Golden(HdrDynamicRangeMode.HDR10_PLUS, hdr10PlusOnly, HdrDynamicRangePolicy.PROFILE_HDR10_PLUS),
        Golden(HdrDynamicRangeMode.HDR10, hdr10PlusOnly, null),
        Golden(HdrDynamicRangeMode.HLG, hdr10PlusOnly, null),
        // Dolby Vision is intentionally not negotiated as a Camera2 transport (encoder owns it).
        Golden(HdrDynamicRangeMode.DOLBY_VISION, full, null)
    )

    @Test
    fun `visible path dynamic range mapping is frozen for preview and encoder`() {
        goldens.forEach { g ->
            val preview = HdrDynamicRangePolicy.resolveProfilePure(g.mode, g.support, HdrSurfaceRole.PREVIEW)
            val encoder = HdrDynamicRangePolicy.resolveProfilePure(g.mode, g.support, HdrSurfaceRole.ENCODER)
            assertEquals("preview mode=${g.mode} support=${g.support}", g.expected, preview)
            assertEquals("encoder mode=${g.mode} support=${g.support}", g.expected, encoder)
        }
    }

    @Test
    fun `preview and encoder always negotiate the identical profile (wysiwyg parity)`() {
        val modes = HdrDynamicRangeMode.entries
        val supports = listOf(standardOnly, hlgOnly, hdr10Only, hdr10PlusOnly, full)
        modes.forEach { mode ->
            supports.forEach { support ->
                val preview = HdrDynamicRangePolicy.resolveProfilePure(mode, support, HdrSurfaceRole.PREVIEW)
                val encoder = HdrDynamicRangePolicy.resolveProfilePure(mode, support, HdrSurfaceRole.ENCODER)
                assertEquals("parity mode=$mode support=$support", preview, encoder)
            }
        }
    }

    @Test
    fun `raw jpeg and analysis surfaces are always standard regardless of mode or hardware`() {
        val sensorRoles = listOf(HdrSurfaceRole.RAW, HdrSurfaceRole.JPEG, HdrSurfaceRole.ANALYSIS)
        HdrDynamicRangeMode.entries.forEach { mode ->
            sensorRoles.forEach { role ->
                assertNull(
                    "role=$role mode=$mode must stay STANDARD",
                    HdrDynamicRangePolicy.resolveProfilePure(mode, full, role)
                )
            }
        }
    }
}
