package com.neoproc.neoproccamx.ui

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentitySnapshotCacheTest {
    @Test fun `every frame reuses exactly the same immutable metadata result`() {
        val cache = IdentitySnapshotCache<Boolean, Any>()
        val owner = Any()
        val result = Any()
        var calls = 0
        repeat(15_000) {
            assertSame(result, cache.resolve(owner, true) {
                calls++
                IdentitySnapshotCache.Computed(result)
            })
        }
        assertEquals(1, calls)
    }

    @Test fun `owner identity never aliases equal metadata or colliding hash values`() {
        class Owner {
            override fun equals(other: Any?) = other is Owner
            override fun hashCode() = 7
        }
        val cache = IdentitySnapshotCache<Boolean, Any>()
        val first = cache.resolve(Owner(), true) { IdentitySnapshotCache.Computed(Any()) }
        val replacement = cache.resolve(Owner(), true) { IdentitySnapshotCache.Computed(Any()) }
        assertNotSame(first, replacement)
    }

    @Test fun `capability changes and return to an old camera always revalidate`() {
        val cache = IdentitySnapshotCache<Boolean, Any>()
        val firstOwner = Any()
        val nextOwner = Any()
        val first = cache.resolve(firstOwner, true) { IdentitySnapshotCache.Computed(Any()) }
        assertNotSame(first, cache.resolve(firstOwner, false) { IdentitySnapshotCache.Computed(Any()) })
        val next = cache.resolve(nextOwner, true) { IdentitySnapshotCache.Computed(Any()) }
        assertNotSame(next, cache.resolve(firstOwner, true) { IdentitySnapshotCache.Computed(Any()) })
    }

    @Test fun `missing camera and partial reads cannot cache fallback capabilities`() {
        val cache = IdentitySnapshotCache<Boolean, Int>()
        val owner = Any()
        var calls = 0
        repeat(5) { cache.resolve(null, true) { IdentitySnapshotCache.Computed(++calls) } }
        repeat(5) { cache.resolve(owner, true) { IdentitySnapshotCache.Computed(++calls, false) } }
        assertEquals(10, calls)
        assertEquals(11, cache.resolve(owner, true) { IdentitySnapshotCache.Computed(++calls) })
        assertEquals(11, cache.resolve(owner, true) { error("Complete snapshot should be reused") })
    }

    @Test fun `resolver exception does not publish partial state or prevent a retry`() {
        val cache = IdentitySnapshotCache<Boolean, Int>()
        val owner = Any()
        assertTrue(runCatching { cache.resolve(owner, true) { error("HAL snapshot read failed") } }.isFailure)
        assertEquals(9, cache.resolve(owner, true) { IdentitySnapshotCache.Computed(9) })
    }

    @Test fun `concurrent capture and UI requests build once and see complete state`() {
        val cache = IdentitySnapshotCache<Boolean, List<Int>>()
        val owner = Any()
        val ready = CountDownLatch(1)
        val calls = AtomicInteger()
        val workers = Executors.newFixedThreadPool(8)
        try {
            val requests = (1..8).map { workers.submit(Callable {
                check(ready.await(5, TimeUnit.SECONDS))
                cache.resolve(owner, true) {
                    calls.incrementAndGet()
                    IdentitySnapshotCache.Computed((1..1_000).toList())
                }
            }) }
            ready.countDown()
            val result = requests.first().get(5, TimeUnit.SECONDS)
            requests.forEach { assertSame(result, it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, calls.get())
            assertEquals(1_000, result.size)
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun `production caches metadata reads but retains live colour and scene processing`() {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val policy = File(main, "java/com/neoproc/neoproccamx/ui/VideoModePipelineContractPolicy.kt").readText()
        assertTrue(policy.contains("physicalMetadataCache.resolve(characteristics, gpu10BitSupported)"))
        assertTrue(policy.contains("cacheable = completeRead"))
        val source = File(main, "java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val geometry = File(main, "java/com/neoproc/neoproccamx/ui/PhysicalCameraGeometryIdentityCache.kt").readText()
        assertTrue(source.contains("physicalCameraIdentityCache = PhysicalCameraGeometryIdentityCache()"))
        assertTrue(source.contains("physicalCameraIdentityCache.resolve(characteristics, cameraId)"))
        assertTrue(geometry.contains("snapshots.resolve(characteristics, cameraId)"))
        assertTrue(geometry.contains("cacheable = completeRead"))
        listOf("LENS_FACING", "SENSOR_ORIENTATION", "SENSOR_INFO_ACTIVE_ARRAY_SIZE",
            "SENSOR_INFO_PIXEL_ARRAY_SIZE", "SENSOR_CALIBRATION_TRANSFORM1",
            "SENSOR_CALIBRATION_TRANSFORM2", "SENSOR_COLOR_TRANSFORM1",
            "SENSOR_COLOR_TRANSFORM2", "SENSOR_FORWARD_MATRIX1", "SENSOR_FORWARD_MATRIX2"
        ).forEach { key -> assertTrue("Geometry identity lost $key", geometry.contains("CameraCharacteristics.$key")) }
        assertTrue(source.contains("camera.setProcessedVideoSceneQuality("))
        assertTrue(source.contains("renderProfileEngine.build("))
        assertTrue(source.contains("ApexRuntimeSceneMapper.fromPipeline("))
        assertTrue(source.contains("camera.setHdrExposureSceneMetrics("))
    }
}
