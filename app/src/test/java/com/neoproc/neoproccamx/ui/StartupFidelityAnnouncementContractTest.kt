package com.neoproc.neoproccamx.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupFidelityAnnouncementContractTest {
    private val source = File("src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt").readText()

    @Test
    fun `automatic fidelity verification is logged without a permission-obscuring toast`() {
        val verificationBlock = source
            .substringAfter("// VERIFY MAXIMUM FIDELITY")
            .substringBefore("override fun onNewIntent")

        assertTrue(verificationBlock.contains("MAXIMUM FIDELITY UNLOCKED"))
        assertTrue(verificationBlock.contains("Log.i"))
        assertFalse(verificationBlock.contains("toast("))
        assertFalse(verificationBlock.contains("Toast.makeText"))
    }
}
