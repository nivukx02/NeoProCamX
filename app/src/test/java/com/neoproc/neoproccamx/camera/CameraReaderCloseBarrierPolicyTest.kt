package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class CameraReaderCloseBarrierPolicyTest {

    @Test
    fun `destructive close action executes inside the bound barrier`() {
        val events = mutableListOf<String>()

        val result = CameraReaderCloseBarrierPolicy.execute(
            barrier = { timeoutMs, closeAction ->
                assertEquals(1_000L, timeoutMs)
                events += "barrier-enter"
                closeAction()
                events += "barrier-exit"
                true
            },
            timeoutMs = 1_000L,
            directCloseAllowed = false,
            closeAction = { events += "close-readers" }
        )

        assertTrue(result.closeCompleted)
        assertTrue(result.closeActionInvoked)
        assertNull(result.failure)
        assertEquals(listOf("barrier-enter", "close-readers", "barrier-exit"), events)
    }

    @Test
    fun `timeout path never invokes destructive close`() {
        val closeCalls = AtomicInteger(0)

        val result = CameraReaderCloseBarrierPolicy.execute(
            barrier = { _, _ -> false },
            timeoutMs = 1_000L,
            directCloseAllowed = false,
            closeAction = { closeCalls.incrementAndGet() }
        )

        assertFalse(result.closeCompleted)
        assertFalse(result.closeActionInvoked)
        assertNull(result.failure)
        assertEquals(0, closeCalls.get())
    }

    @Test
    fun `timeout callback cannot retain close action beyond barrier scope`() {
        val closeCalls = AtomicInteger(0)
        var escapedAction: (() -> Unit)? = null

        val result = CameraReaderCloseBarrierPolicy.execute(
            barrier = { _, closeAction ->
                escapedAction = closeAction
                false
            },
            timeoutMs = 1_000L,
            directCloseAllowed = false,
            closeAction = { closeCalls.incrementAndGet() }
        )

        assertFalse(result.closeCompleted)
        assertThrows(IllegalStateException::class.java) { requireNotNull(escapedAction).invoke() }
        assertEquals(0, closeCalls.get())
    }

    @Test
    fun `barrier success without invoking close action fails closed`() {
        val closeCalls = AtomicInteger(0)

        val result = CameraReaderCloseBarrierPolicy.execute(
            barrier = { _, _ -> true },
            timeoutMs = 1_000L,
            directCloseAllowed = false,
            closeAction = { closeCalls.incrementAndGet() }
        )

        assertFalse(result.closeCompleted)
        assertFalse(result.closeActionInvoked)
        assertEquals(0, closeCalls.get())
    }

    @Test
    fun `unbound direct close requires explicit detached-callback proof`() {
        val closeCalls = AtomicInteger(0)
        val denied = CameraReaderCloseBarrierPolicy.execute(
            barrier = null,
            timeoutMs = 1_000L,
            directCloseAllowed = false,
            closeAction = { closeCalls.incrementAndGet() }
        )
        assertFalse(denied.closeCompleted)
        assertEquals(0, closeCalls.get())

        val allowed = CameraReaderCloseBarrierPolicy.execute(
            barrier = null,
            timeoutMs = 1_000L,
            directCloseAllowed = true,
            closeAction = { closeCalls.incrementAndGet() }
        )
        assertTrue(allowed.closeCompleted)
        assertEquals(1, closeCalls.get())
    }

    @Test
    fun `controller routes all ImageReader destruction through one barrier helper`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt"
        ).readText()
        val helper = source
            .substringAfter("private fun closeReaderSetsWithPipelineBarrier(")
            .substringBefore("private fun closeReaderSetsNow(")
        val release = source
            .substringAfter("internal fun releaseCameraResourcesWithProof(clearPreviewSurface: Boolean): Boolean {")
            .substringBefore("override fun prepareCameraSessionForDeviceRecovery()")

        assertTrue(helper.contains("CameraReaderCloseBarrierPolicy.execute("))
        assertTrue(helper.contains("val callbackSeal = readerCallbackIngress.seal()"))
        assertTrue(helper.contains("retireReaderBindings(readerSets)"))
        assertTrue(helper.contains("readerCallbackIngress.awaitIdle(timeoutMs)"))
        assertTrue(helper.contains("downstreamBarrier(closeRequest, remainingMs, closeAction)"))
        assertTrue(helper.contains("directCloseAllowed = false"))
        assertTrue(helper.contains("closeAction = { closeReaderSetsNow(readerSets) }"))
        assertTrue(helper.contains("if (result.closeCompleted) return true"))
        assertTrue(helper.indexOf("if (result.closeCompleted) return true") <
            helper.indexOf("quarantinedReaderCloseBatches.addAll(readerSets)"))
        assertTrue(source.contains("reason = \"partial reader setup cleanup\""))
        assertTrue(source.contains("reason = \"no active session\""))
        assertTrue(source.contains("closeReaderSetsWithPipelineBarrier(batches, reason)"))
        assertTrue(release.contains("reason = \"current camera resources released\""))
        assertTrue(release.contains("return allReadersClosed && quarantineEmpty"))
        assertTrue(source.contains("private val readerBindings = ReaderBindingRegistry<ImageReader>()"))
        assertTrue(source.contains("readerCallbackAdmission.dispatch(binding, reader)"))
        assertTrue(source.contains("bindReaderCallback(ReaderKind.JPEG"))
        assertTrue(source.contains("bindReaderCallback(ReaderKind.RAW"))
        assertTrue(source.contains("bindReaderCallback(ReaderKind.YUV"))
        assertTrue(source.contains("bindReaderCallback(ReaderKind.HARDWARE_IMPORT"))
        assertTrue(source.contains("detachReaderCallbacks(readerSets)"))
        assertTrue(source.contains("check(destroyWithRetirementProof())"))
        assertEquals(1, Regex("""\breader\.close\(\)""").findAll(source).count())
        assertFalse(source.contains("r?.close()"))
        assertFalse(source.contains("jpegReader?.close()"))
        assertFalse(source.contains("rawReader?.close()"))
        assertFalse(source.contains("yuvReader?.close()"))
        assertFalse(source.contains("hardwareImportReader?.close()"))
    }
}
