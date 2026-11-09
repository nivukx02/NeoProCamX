package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PreviewDisplayPresentationCoordinatorTest {
    private var generation = 1L
    private var surface: Any? = Any()
    private val valid = mutableSetOf<Any>().apply { surface?.let(::add) }

    private fun coordinator(
        capacity: Int = 4,
        timestampMatchToleranceNs: Long = 0L
    ) =
        PreviewDisplayPresentationCoordinator(
            capacity = capacity,
            currentSessionGeneration = { generation },
            currentPreviewSurface = { surface },
            isOutputSurfaceValid = { it in valid },
            timestampMatchToleranceNs = timestampMatchToleranceNs
        ).also { coordinator ->
            coordinator.updateSurface(surface)
        }

    @Test
    fun `capture first preserves route and accepts the matching visible swap`() {
        val coordinator = coordinator(timestampMatchToleranceNs = 2_000_000L)
        val currentSurface = requireNotNull(surface)

        assertNull(coordinator.recordCapture(40L, generation))
        val accepted = coordinator.recordCompositedPresentation(
            40L, currentSurface, 900L, PreviewPixelRouteStage.DIRECT_VISIBLE_SWAP
        )

        assertEquals(40L, accepted?.event?.sensorTimestampNs)
        assertEquals(900L, accepted?.event?.presentationTimestampNs)
        assertEquals(PreviewPixelRouteStage.DIRECT_VISIBLE_SWAP, accepted?.visibleRouteStage)
    }

    @Test
    fun `presentation first keeps original swap timestamp and route until capture arrives`() {
        val coordinator = coordinator(timestampMatchToleranceNs = 2_000_000L)
        val currentSurface = requireNotNull(surface)

        assertNull(coordinator.recordCompositedPresentation(
            40L, currentSurface, 777L, PreviewPixelRouteStage.PERSISTENT_ROUTER_VISIBLE_SWAP
        ))
        val accepted = coordinator.recordCapture(40L, generation)

        assertEquals(777L, accepted?.event?.presentationTimestampNs)
        assertEquals(PreviewPixelRouteStage.PERSISTENT_ROUTER_VISIBLE_SWAP, accepted?.visibleRouteStage)
    }

    @Test
    fun `capture first reconciles a sub two millisecond compositor clock skew`() {
        val coordinator = coordinator(timestampMatchToleranceNs = 2_000_000L)
        val currentSurface = requireNotNull(surface)
        val camera2TimestampNs = 1_000_000_000L

        assertNull(coordinator.recordCapture(camera2TimestampNs, generation))
        val accepted = coordinator.recordCompositedPresentation(
            camera2TimestampNs + 1_500_000L,
            currentSurface,
            900L,
            PreviewPixelRouteStage.PERSISTENT_ROUTER_VISIBLE_SWAP
        )

        assertEquals(camera2TimestampNs, accepted?.event?.sensorTimestampNs)
        assertEquals(900L, accepted?.event?.presentationTimestampNs)
    }

    @Test
    fun `presentation first reconciles a sub two millisecond camera clock skew`() {
        val coordinator = coordinator(timestampMatchToleranceNs = 2_000_000L)
        val currentSurface = requireNotNull(surface)
        val camera2TimestampNs = 1_000_000_000L

        assertNull(coordinator.recordCompositedPresentation(
            camera2TimestampNs - 1_500_000L,
            currentSurface,
            777L,
            PreviewPixelRouteStage.PERSISTENT_ROUTER_VISIBLE_SWAP
        ))
        val accepted = coordinator.recordCapture(camera2TimestampNs, generation)

        assertEquals(camera2TimestampNs, accepted?.event?.sensorTimestampNs)
        assertEquals(777L, accepted?.event?.presentationTimestampNs)
    }

    @Test
    fun `clock skew outside strict tolerance remains unmatched`() {
        val coordinator = coordinator(timestampMatchToleranceNs = 2_000_000L)
        val currentSurface = requireNotNull(surface)
        val camera2TimestampNs = 1_000_000_000L

        assertNull(coordinator.recordCapture(camera2TimestampNs, generation))
        assertNull(coordinator.recordCompositedPresentation(
            camera2TimestampNs + 2_000_001L,
            currentSurface,
            900L,
            PreviewPixelRouteStage.PERSISTENT_ROUTER_VISIBLE_SWAP
        ))
    }

    @Test
    fun `session change between callback halves rejects retired presentation`() {
        val coordinator = coordinator()
        val currentSurface = requireNotNull(surface)

        assertNull(coordinator.recordCompositedPresentation(
            40L, currentSurface, 777L, PreviewPixelRouteStage.DIRECT_VISIBLE_SWAP
        ))
        generation = 2L
        coordinator.onSessionGenerationAdvanced()

        assertNull(coordinator.recordCapture(40L, 1L))
    }

    @Test
    fun `surface change between callback halves rejects retired presentation`() {
        val coordinator = coordinator()
        val retiredSurface = requireNotNull(surface)
        assertNull(coordinator.recordCompositedPresentation(
            40L, retiredSurface, 777L, PreviewPixelRouteStage.DIRECT_VISIBLE_SWAP
        ))

        val replacement = Any()
        valid += replacement
        surface = replacement
        coordinator.updateSurface(replacement)

        assertNull(coordinator.recordCapture(40L, generation))
    }

    @Test
    fun `duplicate sensor timestamp emits exactly once`() {
        val coordinator = coordinator()
        val currentSurface = requireNotNull(surface)
        coordinator.recordCapture(40L, generation)

        val first = coordinator.recordCompositedPresentation(
            40L, currentSurface, 777L, PreviewPixelRouteStage.DIRECT_VISIBLE_SWAP
        )
        val duplicate = coordinator.recordCompositedPresentation(
            40L, currentSurface, 888L, PreviewPixelRouteStage.DIRECT_VISIBLE_SWAP
        )

        assertEquals(1L, first?.event?.sequence)
        assertNull(duplicate)
    }

    @Test
    fun `bounded capture history evicts the oldest unmatched frame`() {
        val coordinator = coordinator(capacity = 2)
        val currentSurface = requireNotNull(surface)
        coordinator.recordCapture(10L, generation)
        coordinator.recordCapture(20L, generation)
        coordinator.recordCapture(30L, generation)

        assertNull(coordinator.recordCompositedPresentation(
            10L, currentSurface, 100L, PreviewPixelRouteStage.DIRECT_VISIBLE_SWAP
        ))
        assertEquals(20L, coordinator.recordCompositedPresentation(
            20L, currentSurface, 200L, PreviewPixelRouteStage.DIRECT_VISIBLE_SWAP
        )?.event?.sensorTimestampNs)
    }

    @Test
    fun `invalid or non-current surface never produces accepted evidence`() {
        val coordinator = coordinator()
        val currentSurface = requireNotNull(surface)
        coordinator.recordCapture(40L, generation)
        valid.remove(currentSurface)

        assertNull(coordinator.recordCompositedPresentation(
            40L, currentSurface, 777L, PreviewPixelRouteStage.DIRECT_VISIBLE_SWAP
        ))
        val foreignSurface = Any()
        valid += foreignSurface
        assertNull(coordinator.recordCompositedPresentation(
            40L, foreignSurface, 888L, PreviewPixelRouteStage.DIRECT_VISIBLE_SWAP
        ))
        assertSame(currentSurface, surface)
    }

    @Test
    fun `late reconciliation cannot publish an older presentation after a newer swap`() {
        val coordinator = coordinator()
        val currentSurface = requireNotNull(surface)
        assertNull(coordinator.recordCompositedPresentation(
            10L, currentSurface, 100L, PreviewPixelRouteStage.DIRECT_VISIBLE_SWAP
        ))
        coordinator.recordCapture(20L, generation)
        val newer = coordinator.recordCompositedPresentation(
            20L, currentSurface, 200L, PreviewPixelRouteStage.DIRECT_VISIBLE_SWAP
        )

        assertEquals(20L, newer?.event?.sensorTimestampNs)
        assertNull(coordinator.recordCapture(10L, generation))
    }

    @Test
    fun `retired capture callback is rejected before entering new generation history`() {
        val coordinator = coordinator()
        generation = 2L
        coordinator.onSessionGenerationAdvanced()

        assertNull(coordinator.recordCapture(10L, 1L))
        val currentSurface = requireNotNull(surface)
        assertNull(coordinator.recordCompositedPresentation(
            10L, currentSurface, 100L, PreviewPixelRouteStage.DIRECT_VISIBLE_SWAP
        ))
    }
}
