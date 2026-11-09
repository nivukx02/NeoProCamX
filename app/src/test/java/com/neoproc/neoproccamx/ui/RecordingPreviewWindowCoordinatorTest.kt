package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.camera.CameraBackgroundCloseDecision
import org.junit.Assert.*
import org.junit.Test

class RecordingPreviewWindowCoordinatorTest {
    private class Fixture {
        var active = true
        var eligible = true
        var admitDetach = true
        var admitAttach = true
        val detaches = mutableListOf<(Boolean, String) -> Unit>()
        val attaches = mutableListOf<Pair<Any, (Boolean, String) -> Unit>>()
        val events = mutableListOf<String>()
        val restored = mutableListOf<Any>()
        val failures = mutableListOf<String>()
        val coordinator = RecordingPreviewWindowCoordinator<Any>(
            runOnMain = { it() }, canRetain = { eligible }, recordingActive = { active },
            detachWindow = { detaches += it; admitDetach },
            attachWindow = { surface, done -> attaches += surface to done; admitAttach },
            onRetained = { events += "retained" }, onRestored = { restored += it },
            onFailure = { failures += it }, onEvidence = { events += it }
        )
    }

    @Test fun `retention does not proceed without an active healthy recording`() {
        val f = Fixture()
        f.active = false
        assertFalse(f.coordinator.retain())
        f.active = true
        f.eligible = false
        assertFalse(f.coordinator.retain())
        assertTrue(f.detaches.isEmpty())
    }

    @Test fun `duplicate background and surface loss retire a window exactly once`() {
        val f = Fixture()
        assertTrue(f.coordinator.retain())
        assertTrue(f.coordinator.retain())
        assertEquals(1, f.detaches.size)
        f.detaches.single()(true, "parked")
        assertTrue(f.coordinator.retained)
        assertEquals(1, f.events.count { it.startsWith("RECORDING_PREVIEW_WINDOW_DETACHED") })
    }

    @Test fun `reattach publishes only the latest replacement and clears retention`() {
        val f = Fixture()
        f.coordinator.retain()
        val stale = Any()
        val current = Any()
        f.coordinator.restore(stale)
        f.coordinator.restore(current)
        f.attaches[0].second(true, "stale")
        assertTrue(f.restored.isEmpty())
        f.attaches[1].second(true, "current")
        assertSame(current, f.restored.single())
        assertFalse(f.coordinator.retained)
    }

    @Test fun `duplicate restoration cannot enqueue the same window twice`() {
        val f = Fixture()
        val window = Any()
        f.coordinator.retain()
        f.coordinator.restore(window)
        f.coordinator.restore(window)
        assertEquals(1, f.attaches.size)
    }

    @Test fun `close invalidates pending restoration and stale failure`() {
        val f = Fixture()
        f.coordinator.retain()
        f.coordinator.restore(Any())
        f.coordinator.reset()
        f.detaches.single()(false, "superseded detach")
        f.attaches.single().second(true, "superseded restore")
        assertFalse(f.coordinator.retained)
        assertTrue(f.restored.isEmpty())
        assertTrue(f.failures.isEmpty())
    }

    @Test fun `new background invalidates an in flight visible replacement`() {
        val f = Fixture()
        f.coordinator.retain()
        f.coordinator.restore(Any())
        f.coordinator.retain()
        f.attaches.single().second(true, "stale restore")
        assertTrue(f.restored.isEmpty())
        assertTrue(f.coordinator.retained)
        assertEquals(2, f.detaches.size)
    }

    @Test fun `current restoration failure stops without publishing an invalid window`() {
        val f = Fixture()
        f.coordinator.retain()
        f.coordinator.restore(Any())
        f.attaches.single().second(false, "context lost")
        assertEquals(listOf("context lost"), f.failures)
        assertFalse(f.coordinator.retained)
        assertTrue(f.restored.isEmpty())
    }

    @Test fun `rejected detach permits existing explicit fallback`() {
        val f = Fixture()
        f.admitDetach = false
        assertFalse(f.coordinator.retain())
        assertFalse(f.coordinator.retained)
        f.detaches.single()(true, "late callback")
        assertTrue(f.events.isEmpty())
    }

    @Test fun `rejected same size restore fails closed`() {
        val f = Fixture()
        f.coordinator.retain()
        f.admitAttach = false
        f.coordinator.restore(Any())
        assertEquals(1, f.failures.size)
        assertFalse(f.coordinator.retained)
        assertTrue(f.restored.isEmpty())
    }

    @Test fun `synchronous detach failure cannot resurrect retained state`() {
        var failure: String? = null
        var published = false
        val c = RecordingPreviewWindowCoordinator<Any>(
            runOnMain = { it() }, canRetain = { true }, recordingActive = { true },
            detachWindow = { it(false, "park failed"); true },
            attachWindow = { _, _ -> error("unexpected attach") },
            onRetained = { published = true }, onRestored = { error("unexpected restore") },
            onFailure = { failure = it }, onEvidence = { error("unexpected success") }
        )
        assertTrue(c.retain())
        assertFalse(c.retained)
        assertFalse(published)
        assertEquals("park failed", failure)
    }

    @Test fun `background effect ordering preserves active recording`() {
        for (stop in listOf(false, true)) {
            val events = mutableListOf<String>()
            CameraPreviewLifecycleDispatcher.background(
                CameraBackgroundCloseDecision(requestRecordingStop = stop, deferCameraCloseUntilRecordingStops = true),
                deferClose = { events += "defer=$it" }, retainDisplayInputs = { events += "retain" },
                stopRecording = { events += "stop" }, closeCamera = { events += "close" }
            )
            assertEquals(listOf("defer=true", if (stop) "stop" else "retain"), events)
        }
    }

    @Test fun `retirement only binds a valid replacement after matching close`() {
        val owner = PreviewSurfaceRetirementCoordinator<Any>()
        val old = owner.begin()
        val current = owner.begin()
        val surface = Any()
        owner.deferReplacement(surface)
        val bindings = mutableListOf<Any>()
        val validity = mutableListOf<Boolean>()
        CameraPreviewLifecycleDispatcher.bindReleasedReplacement(owner, old, { true }, { bindings += it }, { validity += it })
        assertTrue(bindings.isEmpty())
        CameraPreviewLifecycleDispatcher.bindReleasedReplacement(owner, current, { true }, { bindings += it }, { validity += it })
        assertSame(surface, bindings.single())
        assertEquals(listOf(true), validity)
    }

    @Test fun `invalid retired replacement never reopens camera`() {
        val owner = PreviewSurfaceRetirementCoordinator<Any>()
        val ticket = owner.begin()
        owner.deferReplacement(Any())
        var valid: Boolean? = null
        CameraPreviewLifecycleDispatcher.bindReleasedReplacement(
            owner, ticket, { throw IllegalStateException("retired") },
            { error("must not bind invalid surface") }, { valid = it }
        )
        assertEquals(false, valid)
    }
}
