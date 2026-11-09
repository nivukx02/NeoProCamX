package com.neoproc.neoproccamx.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveContractCacheArchitectureTest {
    @Test
    fun `camera view model resolves active contracts outside shared monitors`() {
        val source = repoFile(
            "app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
        ).readText()

        assertFalse(source.contains("activeContractCacheLock"))
        assertTrue(source.contains("deliveryContractCache.resolve(key)"))
        assertTrue(source.contains("videoModeContractCache.resolve(key)"))
        assertTrue(source.contains("videoPipelineContractCache.resolve(request)"))
        assertTrue(source.contains("vulkanContractCache.resolve(key)"))
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
