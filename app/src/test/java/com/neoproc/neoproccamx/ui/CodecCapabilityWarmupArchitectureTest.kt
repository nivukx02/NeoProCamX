package com.neoproc.neoproccamx.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecCapabilityWarmupArchitectureTest {
    @Test
    fun `startup contract resolution never joins an in progress codec probe`() {
        val viewModel = repoFile(
            "app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
        ).readText()
        val recordingManager = repoFile(
            "app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt"
        ).readText()

        val deliveryKey = viewModel.substringAfter("private fun activeDeliveryContractCacheKey()")
            .substringBefore("private fun activeVideoFormatDeliveryState()")
        val graphContract = viewModel.substringAfter("private fun activeVideoPipelineContract(")
            .substringBefore("private fun activeVideoGraphPreviewSize()")

        assertTrue(deliveryKey.contains("supports10BitWithoutCodecProbeBlocking()"))
        assertTrue(graphContract.contains("supports10BitWithoutCodecProbeBlocking()"))
        assertTrue(recordingManager.contains("NonBlockingSynchronizedLazy"))
        assertTrue(recordingManager.contains("fun cachedSupports10Bit("))
        assertFalse(recordingManager.contains("LazyThreadSafetyMode.PUBLICATION"))
    }

    private fun repoFile(relativePath: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            File(directory, relativePath).takeIf(File::exists)?.let { return it }
            directory = directory.parentFile ?: return File(relativePath)
        }
        return File(relativePath)
    }
}
