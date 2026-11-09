package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.rendering.GpuSignalFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NeoQualityInstructionCacheTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `second export reuses bit exact instructions without touching frame pixels`() {
        val source = temporary.newFile("master.npv10").apply { writeBytes(ByteArray(128) { it.toByte() }) }
        val fingerprint = fingerprint(source, frameCount = 2, snceChromaEnabled = true)
        val firstFrame = p010(16, 12, 210)
        val secondFrame = p010(16, 12, 590)

        val firstExport = NeoQualityInstructionCache.open(source, fingerprint)
        assertFalse(firstExport.isCacheHit)
        val instruction0 = firstExport.resolve(0L, firstFrame, null, saturation = 1.02f, warmth = -0.01f)
        val instruction1 = firstExport.resolve(1L, secondFrame, firstFrame, saturation = 1.03f, warmth = 0.02f)
        assertTrue(firstExport.commit(2L))

        val repeatedExport = NeoQualityInstructionCache.open(source, fingerprint)
        assertTrue(repeatedExport.isCacheHit)
        // A cache hit does not need source pixels to reproduce the exact NQIS decisions.
        val cached0 = repeatedExport.resolve(0L, ByteArray(0), null)
        val cached1 = repeatedExport.resolve(1L, ByteArray(0), null)
        assertEquals(instruction0.signature, cached0.signature)
        assertEquals(instruction1.signature, cached1.signature)
        assertEquals(
            instruction1.noiseMetrics.temporalInstability.toRawBits(),
            cached1.noiseMetrics.temporalInstability.toRawBits()
        )
        assertEquals(instruction1.quality.sharpen.toRawBits(), cached1.quality.sharpen.toRawBits())
        assertEquals(instruction1.quality.denoise.toRawBits(), cached1.quality.denoise.toRawBits())
        assertEquals(instruction1.saturation.toRawBits(), cached1.saturation.toRawBits())
        assertEquals(instruction1.warmth.toRawBits(), cached1.warmth.toRawBits())
    }

    @Test
    fun `source identity and quality profile changes invalidate cache`() {
        val source = temporary.newFile("identity.npv10").apply { writeBytes(ByteArray(64) { 7 }) }
        val fingerprint = fingerprint(source, frameCount = 1)
        val miss = NeoQualityInstructionCache.open(source, fingerprint)
        miss.resolve(0L, p010(16, 12, 420), null)
        assertTrue(miss.commit(1L))

        assertFalse(
            NeoQualityInstructionCache.open(
                source,
                fingerprint.copy(detailProfile = VideoDetailProfile.EXTREME_DETAIL)
            ).isCacheHit
        )
        assertFalse(
            NeoQualityInstructionCache.open(
                source,
                fingerprint.copy(sourceSha256 = "cd".repeat(32))
            ).isCacheHit
        )
        assertFalse(
            NeoQualityInstructionCache.open(
                source,
                fingerprint.copy(sourceLastModified = fingerprint.sourceLastModified + 1L)
            ).isCacheHit
        )
    }

    @Test
    fun `corrupt sidecar safely falls back to analysis and deletion removes it`() {
        val source = temporary.newFile("corrupt.npv10").apply { writeBytes(ByteArray(96) { 11 }) }
        val fingerprint = fingerprint(source, frameCount = 1)
        val first = NeoQualityInstructionCache.open(source, fingerprint)
        first.resolve(0L, p010(16, 12, 512), null)
        assertTrue(first.commit(1L))
        val sidecar = temporary.root.listFiles().orEmpty().single { it.name.endsWith(".nqis") }
        val bytes = sidecar.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x55).toByte()
        sidecar.writeBytes(bytes)

        assertFalse(NeoQualityInstructionCache.open(source, fingerprint).isCacheHit)
        NeoQualityInstructionCache.deleteFor(source)
        assertFalse(sidecar.exists())
    }

    private fun fingerprint(
        source: java.io.File,
        frameCount: Int,
        snceChromaEnabled: Boolean = false
    ) = NeoQualityInstructionCache.Fingerprint(
        sourceLength = source.length(),
        sourceLastModified = source.lastModified(),
        sourceSha256 = "ab".repeat(32),
        width = 16,
        height = 12,
        signalFamily = GpuSignalFamily.SDR,
        detailProfile = VideoDetailProfile.NATURAL_DETAIL,
        snceChromaEnabled = snceChromaEnabled,
        frameCount = frameCount
    )

    private fun p010(width: Int, height: Int, value: Int): ByteArray {
        val lumaSamples = width * height
        val chromaSamples = ((width + 1) / 2) * ((height + 1) / 2)
        val payload = ByteArray(lumaSamples * 2 + chromaSamples * 4)
        fun write(offset: Int, sample: Int) {
            val word = sample.coerceIn(0, 1023) shl 6
            payload[offset] = word.toByte()
            payload[offset + 1] = (word ushr 8).toByte()
        }
        repeat(lumaSamples) { write(it * 2, value) }
        var offset = lumaSamples * 2
        repeat(chromaSamples) {
            write(offset, 512)
            write(offset + 2, 512)
            offset += 4
        }
        return payload
    }
}
