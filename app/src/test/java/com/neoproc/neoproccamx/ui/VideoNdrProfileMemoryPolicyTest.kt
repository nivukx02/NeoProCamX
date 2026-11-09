package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoNdrProfileMemoryPolicyTest {
    @Test
    fun resolvePreferred_preservesNivukxWhileRawMakesActiveHdrOff() {
        assertEquals(
            "NIVUKXVISION",
            VideoNdrProfileMemoryPolicy.resolvePreferred(
                storedPreferred = "NIVUKX Vision",
                activeHdrMode = "OFF"
            )
        )
    }

    @Test
    fun remember_doesNotReplaceNivukxWithTemporaryOff() {
        assertEquals(
            "NIVUKXVISION",
            VideoNdrProfileMemoryPolicy.remember(
                storedPreferred = "NIVUKXVISION",
                selectedHdrMode = "OFF"
            )
        )
    }

    @Test
    fun remember_tracksExplicitNdrProfileChange() {
        assertEquals(
            "NDR10+",
            VideoNdrProfileMemoryPolicy.remember(
                storedPreferred = "NIVUKXVISION",
                selectedHdrMode = "NDR10+"
            )
        )
    }

    @Test
    fun resolvePreferred_defaultsToNdr10PlusWhenNoNdrWasSelected() {
        assertEquals(
            "NDR10+",
            VideoNdrProfileMemoryPolicy.resolvePreferred(
                storedPreferred = "OFF",
                activeHdrMode = "OFF"
            )
        )
    }
}
