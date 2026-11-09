package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewTransportAspectPolicyTest {

    @Test
    fun resolve_keepsSensorNativeTransportAcrossDisplayAspectChanges() {
        val sensorNative = PreviewTransportAspectPolicy.resolve(
            sensorAspect = 4f / 3f,
            currentStreamAspect = 16f / 9f
        )

        assertEquals(4f / 3f, sensorNative, 0.0001f)
    }

    @Test
    fun resolve_usesCurrentTransportOnlyWhenSensorMetadataIsUnavailable() {
        val current = PreviewTransportAspectPolicy.resolve(
            sensorAspect = null,
            currentStreamAspect = 16f / 9f
        )

        assertEquals(16f / 9f, current, 0.0001f)
    }

    @Test
    fun resolve_preservesLegitimateSquareSensorTransport() {
        val square = PreviewTransportAspectPolicy.resolve(
            sensorAspect = 1f,
            currentStreamAspect = 4f / 3f
        )

        assertEquals(1f, square, 0.0001f)
    }

    @Test
    fun resolve_preservesSquareCurrentTransportWhenSensorMetadataIsUnavailable() {
        val square = PreviewTransportAspectPolicy.resolve(
            sensorAspect = null,
            currentStreamAspect = 1f
        )

        assertEquals(1f, square, 0.0001f)
    }

    @Test
    fun resolve_normalizesPortraitSensorMetadataWithoutDiscardingIt() {
        val normalized = PreviewTransportAspectPolicy.resolve(
            sensorAspect = 3f / 4f,
            currentStreamAspect = 16f / 9f
        )

        assertEquals(4f / 3f, normalized, 0.0001f)
    }

    @Test
    fun resolve_fallsBackToFourThreeForInvalidMetadata() {
        val fallback = PreviewTransportAspectPolicy.resolve(
            sensorAspect = Float.NaN,
            currentStreamAspect = 0f
        )

        assertEquals(4f / 3f, fallback, 0.0001f)
    }
}
