package com.neoproc.neoproccamx.quality

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NivukxVisionDeliverablesTest {

    @Test
    fun promptAndNlogsSpecArtifactsExistAndAvoidExternalProfileClaims() {
        val prompt = File("deliverables/NIVUKX_Vision_Elite_Upgrade_Prompt.txt")
        val nlogs = File("deliverables/NLogs12_Spec.md")
        val qa = File("deliverables/nivukx_vision_qa_a1_a15.json")

        listOf(prompt, nlogs, qa).forEach { file ->
            assertTrue("${file.path} must exist", file.isFile)
            val text = file.readText()
            assertFalse(text.contains("Dolby"))
            assertFalse(text.contains("HDR10+"))
            assertFalse(text.contains("IMAX"))
            assertFalse(text.contains("RPU"))
        }
        assertTrue(nlogs.readText().contains("double nlogs12_encode"))
        assertTrue(nlogs.readText().contains("double nlogs12_decode"))
        (1..15).forEach { index ->
            assertTrue(qa.readText().contains("\"A$index\""))
        }
    }
}
