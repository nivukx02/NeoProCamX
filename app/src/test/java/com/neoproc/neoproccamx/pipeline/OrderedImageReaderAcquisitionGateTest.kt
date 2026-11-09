package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OrderedImageReaderAcquisitionGateTest {

    @Test
    fun `callback and stop drain cannot publish adjacent images out of acquisition order`() {
        val gate = OrderedImageReaderAcquisitionGate()
        val nextImage = AtomicInteger(0)
        val firstAcquired = CountDownLatch(1)
        val allowFirstPublication = CountDownLatch(1)
        val published = mutableListOf<Int>()
        val workers = Executors.newFixedThreadPool(2)

        try {
            val callback = workers.submit {
                gate.runExclusive {
                    val image = nextImage.incrementAndGet()
                    firstAcquired.countDown()
                    check(allowFirstPublication.await(2, TimeUnit.SECONDS))
                    synchronized(published) { published += image }
                }
            }
            assertTrue(firstAcquired.await(1, TimeUnit.SECONDS))
            val stopDrain = workers.submit {
                gate.runExclusive {
                    val image = nextImage.incrementAndGet()
                    synchronized(published) { published += image }
                }
            }

            Thread.sleep(100L)
            assertEquals("stop drain overtook the callback", 1, nextImage.get())
            allowFirstPublication.countDown()
            callback.get(1, TimeUnit.SECONDS)
            stopDrain.get(1, TimeUnit.SECONDS)
            assertEquals(listOf(1, 2), synchronized(published) { published.toList() })
        } finally {
            allowFirstPublication.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun `exception releases ownership for the next reader drain`() {
        val gate = OrderedImageReaderAcquisitionGate()
        runCatching {
            gate.runExclusive<Unit> { throw IllegalStateException("injected acquisition failure") }
        }

        assertEquals(7, gate.runExclusive { 7 })
    }

    @Test
    fun `frame pipeline uses the same gate for callback and exact stop tail`() {
        val source = java.io.File(
            "src/main/java/com/neoproc/neoproccamx/pipeline/FramePipelineManager.kt"
        ).readText()
        val callbackPath = source
            .substringAfter("fun enqueue(reader: ImageReader)")
            .substringBefore("fun drainRawYuvImageTail(reader: ImageReader)")
        val stopTailPath = source
            .substringAfter("fun drainRawYuvImageTail(reader: ImageReader)")
            .substringBefore("fun awaitRawYuvImageSinkAcceptance")

        assertTrue(source.contains("private val rawYuvImageDrainGate = OrderedImageReaderAcquisitionGate()"))
        assertTrue(callbackPath.contains("rawYuvImageDrainGate.runExclusive"))
        assertTrue(stopTailPath.contains("rawYuvImageDrainGate.runExclusive"))
    }
}
