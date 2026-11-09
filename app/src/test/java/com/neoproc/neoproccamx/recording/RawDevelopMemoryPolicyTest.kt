package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.core.NeoLogCurve
import com.neoproc.neoproccamx.pipeline.RawDevelopEliteDetailFlag
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawDevelopMemoryPolicyTest {

    @Test
    fun `sampled parity grid is deterministic unique and bounded`() {
        val first = RawDevelopSampledParityPolicy.stratifiedCoordinates(4_096, 3_072)
        val second = RawDevelopSampledParityPolicy.stratifiedCoordinates(4_096, 3_072)
        assertTrue(first.contentEquals(second))
        assertTrue(first.size / 2 <= 2_048)
        val points = first.asList().chunked(2).map { it[0] to it[1] }
        assertEquals(points.size, points.toSet().size)
        assertTrue(points.all { (x, y) -> x in 0 until 4_096 && y in 0 until 3_072 })
    }

    @Test
    fun `direct callback Java heap stays scanline bounded at full sensor resolution`() {
        val oneK = RawDevelopMemoryPolicy.gpuDirectCallbackJavaHeapBytes(1_024)
        val fourK = RawDevelopMemoryPolicy.gpuDirectCallbackJavaHeapBytes(4_096)

        assertTrue(fourK > oneK)
        assertTrue(fourK < 20L * 1024L * 1024L)
        assertTrue(fourK < RawDevelopMemoryPolicy.rawSamplesBytes(4_096, 3_072))
    }

    @Test
    fun `mapped direct route excludes duplicate full frame float plane from Java admission`() {
        val width = 4_096
        val height = 3_072
        val mapped = RawDevelopMemoryPolicy.gpuMappedCallbackJavaHeapBytes(width, height)
        val formerFloatPlane = RawDevelopMemoryPolicy.acescgBytes(width, height)

        assertTrue(mapped < formerFloatPlane)
        assertEquals(
            RawDevelopMemoryPolicy.pixels(width, height) * 5L +
                width.toLong() * 24L * Float.SIZE_BYTES +
                16L * 1024L * 1024L,
            mapped
        )
    }

    @Test
    fun `gpu native p010 admission retains only upload storage on Java side`() {
        val width = 4_096
        val height = 3_072
        val mapped = RawDevelopMemoryPolicy.gpuP010MappedCallbackJavaHeapBytes(width, height)

        assertEquals(
            RawDevelopMemoryPolicy.pixels(width, height) * Short.SIZE_BYTES +
                16L * 1024L * 1024L,
            mapped
        )
        assertTrue(mapped < RawDevelopMemoryPolicy.gpuMappedCallbackJavaHeapBytes(width, height))
    }

    @Test
    fun `gpu p010 transfer packet preserves every delivery profile contract`() {
        val originalDetail = RawDevelopEliteDetailFlag.enabled
        try {
            RawDevelopEliteDetailFlag.set(true)
            val log = RawDevelopP010TransferPolicy.create(
                NeoLogCurve.Variant.CINEMA,
                RawToNeoLogMovConverter.DeliveryProfile.NEOLOG,
                1f
            )
            val ndr = RawDevelopP010TransferPolicy.create(
                NeoLogCurve.Variant.BASE,
                RawToNeoLogMovConverter.DeliveryProfile.NDR10_PLUS,
                0f
            )
            val vision = RawDevelopP010TransferPolicy.create(
                NeoLogCurve.Variant.BASE,
                RawToNeoLogMovConverter.DeliveryProfile.NIVUKX_VISION,
                0f
            )

            assertEquals(0, log.deliveryMode)
            assertEquals(1, ndr.deliveryMode)
            assertEquals(2, vision.deliveryMode)
            assertEquals(RawDevelopP010TransferPolicy.FLOAT_COUNT, log.packet.size)
            assertEquals(2f, log.packet[0], 0f)
            assertEquals(5_000f, ndr.packet[13], 0f)
            assertEquals(10_000f, vision.packet[13], 0f)
            assertTrue(log.detailEnabled)
            assertTrue(log.packet.all { it.isFinite() })
            assertTrue(ndr.packet.all { it.isFinite() })
            assertTrue(vision.packet.all { it.isFinite() })
        } finally {
            RawDevelopEliteDetailFlag.set(originalDetail)
        }
    }

    @Test
    fun `final p010 parity oracle accepts exact output and rejects changed codes`() {
        val width = 8
        val height = 4
        val bytes = width * height * 3
        val reference = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val candidate = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN)
        repeat(bytes / Short.SIZE_BYTES) { index ->
            val value = ((64 + index) and 1023) shl 6
            reference.putShort(index * Short.SIZE_BYTES, value.toShort())
            candidate.putShort(index * Short.SIZE_BYTES, value.toShort())
        }
        val exact = RawDevelopP010SampledParityPolicy.evaluate(
            reference, candidate, width, height, meanTolerance = 0.0, maximumTolerance = 0
        )
        assertTrue(exact.accepted)

        candidate.putShort(0, (1023 shl 6).toShort())
        val changed = RawDevelopP010SampledParityPolicy.evaluate(
            reference, candidate, width, height, meanTolerance = 0.0, maximumTolerance = 0
        )
        assertFalse(changed.accepted)
        assertTrue(changed.maximumAbsoluteCodeDifference > 0)
    }

    @Test
    fun `bounded p010 oracle coordinates are deterministic even unique and interior`() {
        val first = RawDevelopP010SampledParityPolicy.stratifiedBlockOrigins(4_096, 3_072)
        val second = RawDevelopP010SampledParityPolicy.stratifiedBlockOrigins(4_096, 3_072)

        assertTrue(first.contentEquals(second))
        val blocks = first.asList().chunked(2).map { it[0] to it[1] }
        assertTrue(blocks.isNotEmpty())
        assertTrue(blocks.size <= 128)
        assertEquals(blocks.size, blocks.toSet().size)
        assertTrue(blocks.all { (x, y) ->
            x % 2 == 0 && y % 2 == 0 && x in 2..4_092 && y in 2..3_068
        })
    }

    @Test
    fun `bounded p010 oracle accepts canonical frame and rejects corruption at sampled component`() {
        val width = 32
        val height = 24
        val acescg = FloatArray(width * height * 3) { index ->
            // Smooth non-neutral signal exercises luma and chroma without clipping.
            when (index % 3) {
                0 -> 0.15f + (index / 3 % width) / width.toFloat() * 0.35f
                1 -> 0.20f + (index / 3 / width) / height.toFloat() * 0.30f
                else -> 0.25f
            }
        }
        val packed = RawAcescgTenBitPacker.packP010420(
            acescg = acescg,
            width = width,
            height = height,
            variant = NeoLogCurve.Variant.BASE,
            exposureStops = 0f
        )
        val oracle = requireNotNull(
            RawDevelopP010SampledParityPolicy.buildReferenceFromAcescg(
                acescg = FloatBuffer.wrap(acescg),
                width = width,
                height = height,
                variant = NeoLogCurve.Variant.BASE,
                deliveryProfile = RawToNeoLogMovConverter.DeliveryProfile.NEOLOG,
                exposureStops = 0f
            )
        )
        val candidate = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN)
        val exact = RawDevelopP010SampledParityPolicy.evaluate(
            reference = oracle,
            candidate = candidate,
            meanTolerance = 1.0,
            maximumTolerance = 1
        )
        assertTrue(exact.accepted)
        assertEquals(oracle.sampledComponents, exact.sampledComponents)

        val corrupted = ByteBuffer.wrap(packed.copyOf()).order(ByteOrder.LITTLE_ENDIAN)
        val sampledOffset = oracle.offsetAndCode[0]
        val originalCode = (corrupted.getShort(sampledOffset).toInt() and 0xffff) ushr 6
        val corruptCode = if (originalCode < 512) 1023 else 0
        corrupted.putShort(sampledOffset, (corruptCode shl 6).toShort())
        val rejected = RawDevelopP010SampledParityPolicy.evaluate(
            reference = oracle,
            candidate = corrupted,
            meanTolerance = 1.0,
            maximumTolerance = 1
        )
        assertFalse(rejected.accepted)
        assertTrue(rejected.maximumAbsoluteCodeDifference > 1)
    }

    @Test
    fun `gpu admission counts all simultaneous persistent host buffers`() {
        val width = 16
        val height = 12
        val pixels = width.toLong() * height

        assertEquals(
            pixels * (Short.SIZE_BYTES + 3L * Float.SIZE_BYTES),
            RawDevelopMemoryPolicy.gpuWorkspaceDirectBytes(width, height)
        )
        assertEquals(
            pixels * 4L * Short.SIZE_BYTES,
            RawDevelopMemoryPolicy.gpuReadbackBytes(width, height)
        )

        val formerEstimate = RawDevelopMemoryPolicy.rawSamplesBytes(width, height) +
            RawDevelopMemoryPolicy.acescgBytes(width, height) +
            RawDevelopMemoryPolicy.eliteDetailExtraBytes(width, height) +
            48L * 1024L * 1024L
        assertEquals(
            formerEstimate +
                RawDevelopMemoryPolicy.gpuWorkspaceDirectBytes(width, height) +
                RawDevelopMemoryPolicy.gpuReadbackBytes(width, height),
            RawDevelopMemoryPolicy.gpuDevelopHostPeakBytes(width, height)
        )
        assertTrue(RawDevelopMemoryPolicy.gpuDevelopHostPeakBytes(width, height) > formerEstimate)
    }
}
