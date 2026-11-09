package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoHdrRuntimeAuthorityPolicyTest {
    @Test
    fun `Video OFF stays OFF when the processed runtime is eligible`() {
        assertEquals(
            "OFF",
            VideoHdrRuntimeAuthorityPolicy.resolve(
                runtimeEligible = true,
                activeHdrMode = "OFF"
            )
        )
    }

    @Test
    fun `ineligible RAW or LOG graph fails closed without changing stored intent`() {
        assertEquals(
            "OFF",
            VideoHdrRuntimeAuthorityPolicy.resolve(
                runtimeEligible = false,
                activeHdrMode = "NDR10+"
            )
        )
    }

    @Test
    fun `explicit NDR and NIVUKX runtime selections remain authoritative`() {
        assertEquals(
            "NDR10+",
            VideoHdrRuntimeAuthorityPolicy.resolve(true, "NDR10+")
        )
        assertEquals(
            "NIVUKXVISION",
            VideoHdrRuntimeAuthorityPolicy.resolve(true, "NIVUKX Vision")
        )
    }

    @Test
    fun `unknown or malformed runtime state fails closed`() {
        assertEquals("OFF", VideoHdrRuntimeAuthorityPolicy.resolve(true, null))
        assertEquals("OFF", VideoHdrRuntimeAuthorityPolicy.resolve(true, "stale-profile"))
    }
}
