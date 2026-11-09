package com.neoproc.neoproccamx.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetainedRecordingStopCommandsTest {
    private class Owner { var stops = 0 }

    @Test fun `existing recording owner receives stop without any Activity`() {
        val router = ExistingRecordingStopRouter<Owner>()
        val owner = Owner()
        router.bind(owner)
        assertEquals(ExistingRecordingStopRouter.Result.DELIVERED,
            router.dispatch(true, "stop_recording") { it.stops++ })
        assertEquals(1, owner.stops)
    }

    @Test fun `release build rejects stop even if an owner exists`() {
        val router = ExistingRecordingStopRouter<Owner>()
        val owner = Owner()
        router.bind(owner)
        assertEquals(ExistingRecordingStopRouter.Result.NOT_HANDLED,
            router.dispatch(false, "stop_recording") { it.stops++ })
        assertEquals(0, owner.stops)
    }

    @Test fun `unrelated commands remain on the existing UI dispatch path`() {
        val router = ExistingRecordingStopRouter<Owner>()
        val owner = Owner()
        router.bind(owner)
        for (action in listOf(null, "", "start_recording", "record_clip", "stop_recording_extra")) {
            assertEquals(ExistingRecordingStopRouter.Result.NOT_HANDLED,
                router.dispatch(true, action) { it.stops++ })
        }
        assertEquals(0, owner.stops)
    }

    @Test fun `stop parsing retains case and whitespace normalization`() {
        val router = ExistingRecordingStopRouter<Owner>()
        val owner = Owner()
        router.bind(owner)
        assertEquals(ExistingRecordingStopRouter.Result.DELIVERED,
            router.dispatch(true, " STOP_RECORDING ") { it.stops++ })
        assertEquals(1, owner.stops)
    }

    @Test fun `idle stop is consumed and never replays onto a later owner`() {
        val router = ExistingRecordingStopRouter<Owner>()
        assertEquals(ExistingRecordingStopRouter.Result.NO_EXISTING_OWNER,
            router.dispatch(true, "stop_recording") { error("No camera may be created") })
        val later = Owner()
        router.bind(later)
        assertEquals(0, later.stops)
    }

    @Test fun `teardown detaches the current owner`() {
        val router = ExistingRecordingStopRouter<Owner>()
        val owner = Owner()
        router.bind(owner)
        router.unbind(owner)
        assertEquals(ExistingRecordingStopRouter.Result.NO_EXISTING_OWNER,
            router.dispatch(true, "stop_recording") { it.stops++ })
        assertEquals(0, owner.stops)
    }

    @Test fun `old teardown cannot detach a replacement recording owner`() {
        val router = ExistingRecordingStopRouter<Owner>()
        val old = Owner()
        val replacement = Owner()
        router.bind(old)
        router.bind(replacement)
        router.unbind(old)
        assertEquals(ExistingRecordingStopRouter.Result.DELIVERED,
            router.dispatch(true, "stop_recording") { it.stops++ })
        assertEquals(0, old.stops)
        assertEquals(1, replacement.stops)
    }

    @Test(expected = IllegalStateException::class)
    fun `stop failure is not hidden as successful dispatch`() {
        val router = ExistingRecordingStopRouter<Owner>()
        val owner = Owner()
        router.bind(owner)
        router.dispatch(true, "stop_recording") { throw IllegalStateException("stop failed") }
    }

    @Test fun `receiver routes after security gate and before Activity queue`() {
        val receiver = File("src/main/java/com/neoproc/neoproccamx/ui/DebugCommandReceiver.kt").readText()
        val guard = receiver.indexOf("if (!BuildConfig.DEBUG || intent == null) return")
        val route = receiver.indexOf("RetainedRecordingStopCommands.dispatch(")
        val activity = receiver.indexOf("MainActivity.dispatchDebugCommand(commandIntent)")
        assertTrue(guard >= 0 && route > guard && activity > route)
    }

    @Test fun `retained stop uses existing serialized production finalization`() {
        val vm = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val stop = vm.substringAfter("internal fun stopRecordingFromRetainedDebugOwner() {")
            .substringBefore("private fun requestRecordingStop(")
        assertTrue(stop.contains("!BuildConfig.DEBUG || viewModelTeardownStarted.get()"))
        assertTrue(stop.contains("!hasActiveRecordingBackend()"))
        assertTrue(stop.contains("stopRecordingFromDebug(\"debug action stop_recording\")"))
        assertFalse(stop.contains("startActivity"))
        assertFalse(stop.contains("gpuRecording.stop()"))
        assertFalse(stop.contains("npv10Recording.release()"))
        assertTrue(vm.contains("RetainedRecordingStopCommands.unbind(this)"))
        val factory = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModelFactory.kt").readText()
        assertTrue(factory.contains("CameraViewModel(application).also(RetainedRecordingStopCommands::bind)"))
    }
}
