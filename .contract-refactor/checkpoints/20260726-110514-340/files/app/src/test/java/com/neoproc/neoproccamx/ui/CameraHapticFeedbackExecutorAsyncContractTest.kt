package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CameraHapticFeedbackExecutorAsyncContractTest {
    private val source = File(
        "src/main/java/com/neoproc/neoproccamx/ui/CameraHapticFeedbackExecutor.kt"
    ).readText()

    @Test
    fun hapticExecutorUsesConflatedActionQueueForLowLatencyDialFeedback() {
        assertTrue(source.contains("Channel<CameraHapticAction>(capacity = Channel.CONFLATED)"))
        assertTrue(source.contains("fun enqueue(action: CameraHapticAction)"))
        assertTrue(source.contains("hapticChannel.trySend(action)"))
        assertTrue(source.contains("Dispatchers.Default"))
        assertTrue(source.contains("withContext(Dispatchers.Main.immediate)"))
    }
}
