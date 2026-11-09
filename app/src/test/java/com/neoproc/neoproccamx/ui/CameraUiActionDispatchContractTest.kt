package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CameraUiActionDispatchContractTest {
    private val source = File(
        "src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
    ).readText()

    @Test
    fun viewModelExposesNonBlockingUiActionChannel() {
        assertTrue(source.contains("Channel<UiAction>("))
        assertTrue(source.contains("capacity = CameraUiResponsivenessPolicy.UI_ACTION_BUFFER_CAPACITY"))
        assertTrue(source.contains("onBufferOverflow = BufferOverflow.SUSPEND"))
        assertTrue(source.contains("import kotlinx.coroutines.channels.BufferOverflow"))
        assertTrue(!source.contains("Channel<UiAction>(capacity = Channel.UNLIMITED)"))
        assertTrue(source.contains("fun dispatch(action: UiAction)"))
        assertTrue(source.contains("actionChannel.trySend(action)"))
        assertTrue(source.contains("CameraUiResponsivenessPolicy.shouldDropWhenActionQueueFull(action)"))
        assertTrue(source.contains("viewModelScope.launch(Dispatchers.Default)"))
        assertTrue(source.contains("actionChannel.send(action)"))
        assertTrue(source.contains("private fun startActionProcessor()"))
        assertTrue(source.contains("Dispatchers.Default"))
        assertTrue(source.contains("private suspend fun processAction(action: UiAction)"))
    }

    @Test
    fun viewModelProcessesCoreUiActionsThroughExistingCameraOperations() {
        assertTrue(source.contains("UiAction.ToggleRaw ->"))
        assertTrue(source.contains("UiAction.ToggleRecording ->"))
        assertTrue(source.contains("UiAction.PauseRecording ->"))
        assertTrue(source.contains("UiAction.ResumeRecording ->"))
        assertTrue(source.contains("is UiAction.SetColorProfile ->"))
        assertTrue(source.contains("UiAction.SwitchFacing ->"))
    }
}
