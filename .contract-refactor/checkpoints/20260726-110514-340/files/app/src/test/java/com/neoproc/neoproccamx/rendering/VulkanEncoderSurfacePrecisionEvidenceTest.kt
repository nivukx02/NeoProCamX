package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VulkanEncoderSurfacePrecisionEvidenceTest {

    @Test
    fun `precision enum contains measured and native trusted producer precision values`() {
        assertEquals(
            listOf("UNKNOWN", "EIGHT_BIT", "PACKED_10_BIT", "TRUSTED_EXTERNAL_10_BIT", "FP16"),
            VulkanEncoderSurfacePrecision.entries.map { precision -> precision.name }
        )
    }

    @Test
    fun `packed ten bit encoder surface is parsed as proven actual precision`() {
        val status = VulkanEncoderSurfaceStatusParser.parse(
            "supported encoderSurfaceBound=true " +
                "encoderSurfaceFormat=A2B10G10R10_UNORM_PACK32 " +
                "encoderSurfaceBitDepth=10 encoderSurfacePrecision=PACKED_10_BIT " +
                "encoderSurfacePrecisionFallback=false " +
                "encoderSurfaceColorSpace=SRGB_NONLINEAR " +
                "encoderSurfaceDeliveryMode=SDR " +
                "encoderSurfaceDeliveryCompatible=true"
        )

        assertTrue(status.bound)
        assertTrue(status.precisionProven)
        assertEquals(10, status.bitDepth)
        assertEquals(VulkanEncoderSurfacePrecision.PACKED_10_BIT, status.precision)
        assertEquals("SRGB_NONLINEAR", status.colorSpace)
        assertEquals("SDR", status.deliveryMode)
        assertTrue(status.deliveryCompatible)
        assertFalse(status.precisionFallback)
    }

    @Test
    fun `fp16 encoder surface proves precision while preserving actual sixteen bit producer`() {
        val status = VulkanEncoderSurfaceStatusParser.parse(
            "supported encoderSurfaceBound=true " +
                "encoderSurfaceFormat=R16G16B16A16_SFLOAT " +
                "encoderSurfaceBitDepth=16 encoderSurfacePrecision=FP16 " +
                "encoderSurfacePrecisionFallback=false " +
                "encoderSurfaceColorSpace=EXTENDED_SRGB_LINEAR " +
                "encoderSurfaceDeliveryMode=SDR " +
                "encoderSurfaceDeliveryCompatible=true"
        )

        assertTrue(status.precisionProven)
        assertEquals(16, status.bitDepth)
        assertEquals(VulkanEncoderSurfacePrecision.FP16, status.precision)
    }

    @Test
    fun `eight bit fallback is explicit and can never parse as ten bit`() {
        val status = VulkanEncoderSurfaceStatusParser.parse(
            "supported encoderSurfaceBound=true " +
                "encoderSurfaceFormat=B8G8R8A8_UNORM " +
                "encoderSurfaceBitDepth=8 encoderSurfacePrecision=EIGHT_BIT " +
                "encoderSurfacePrecisionFallback=true " +
                "encoderSurfaceColorSpace=SRGB_NONLINEAR " +
                "encoderSurfaceDeliveryMode=SDR " +
                "encoderSurfaceDeliveryCompatible=true"
        )

        assertTrue(status.precisionProven)
        assertEquals(8, status.bitDepth)
        assertTrue(status.precisionFallback)
        assertFalse(status.supportsTenBitProducer)
    }

    @Test
    fun `sdr packed ten bit with pq or hlg color space is not proven`() {
        val pq = parseStatus(
            precision = "PACKED_10_BIT",
            format = "A2B10G10R10_UNORM_PACK32",
            bitDepth = 10,
            deliveryMode = "SDR",
            colorSpace = "HDR10_ST2084",
            deliveryCompatible = false
        )
        val hlg = parseStatus(
            precision = "PACKED_10_BIT",
            format = "A2R10G10B10_UNORM_PACK32",
            bitDepth = 10,
            deliveryMode = "SDR",
            colorSpace = "HDR10_HLG",
            deliveryCompatible = false
        )

        listOf(pq, hlg).forEach { status ->
            assertTrue(status.bound)
            assertEquals(10, status.bitDepth)
            assertFalse(status.deliveryCompatible)
            assertFalse(status.precisionProven)
            assertFalse(status.supportsTenBitProducer)
        }
    }

    @Test
    fun `hdr pq and hlg packed ten bit surfaces are proven for matching modes`() {
        val pq = parseStatus(
            precision = "PACKED_10_BIT",
            format = "A2B10G10R10_UNORM_PACK32",
            bitDepth = 10,
            deliveryMode = "PQ",
            colorSpace = "HDR10_ST2084",
            deliveryCompatible = true
        )
        val hlg = parseStatus(
            precision = "PACKED_10_BIT",
            format = "A2R10G10B10_UNORM_PACK32",
            bitDepth = 10,
            deliveryMode = "HLG",
            colorSpace = "HDR10_HLG",
            deliveryCompatible = true
        )

        listOf(pq, hlg).forEach {
            assertTrue(it.deliveryCompatible)
            assertTrue(it.precisionProven)
            assertTrue(it.supportsTenBitProducer)
        }
    }

    @Test
    fun `pq and hlg mode mismatch is rejected even when native token claims compatible`() {
        val pqInHlg = parseStatus(
            precision = "PACKED_10_BIT",
            format = "A2B10G10R10_UNORM_PACK32",
            bitDepth = 10,
            deliveryMode = "HLG",
            colorSpace = "HDR10_ST2084",
            deliveryCompatible = true
        )
        val hlgInPq = parseStatus(
            precision = "PACKED_10_BIT",
            format = "A2R10G10B10_UNORM_PACK32",
            bitDepth = 10,
            deliveryMode = "PQ",
            colorSpace = "HDR10_HLG",
            deliveryCompatible = true
        )

        listOf(pqInHlg, hlgInPq).forEach {
            assertFalse(it.deliveryCompatible)
            assertFalse(it.precisionProven)
        }
    }

    @Test
    fun `documented hdr fallback color spaces remain delivery compatible`() {
        val statuses = listOf(
            parseStatus(
                precision = "PACKED_10_BIT",
                format = "A2B10G10R10_UNORM_PACK32",
                bitDepth = 10,
                deliveryMode = "HLG",
                colorSpace = "BT2020_LINEAR",
                deliveryCompatible = true
            ),
            parseStatus(
                precision = "FP16",
                format = "R16G16B16A16_SFLOAT",
                bitDepth = 16,
                deliveryMode = "PQ",
                colorSpace = "EXTENDED_SRGB_LINEAR",
                deliveryCompatible = true
            )
        )

        statuses.forEach {
            assertTrue(it.deliveryCompatible)
            assertTrue(it.precisionProven)
        }

        // Plain sRGB (SDR transfer) is NEVER an acceptable HDR fallback: an sRGB-tagged surface
        // reported for an HLG/PQ route must be rejected as delivery-incompatible, so a non-HDR
        // surface is never treated as HDR (no-downgrade).
        val sRgbTaggedAsPq = parseStatus(
            precision = "PACKED_10_BIT",
            format = "A2B10G10R10_UNORM_PACK32",
            bitDepth = 10,
            deliveryMode = "PQ",
            colorSpace = "SRGB_NONLINEAR",
            deliveryCompatible = true
        )
        assertFalse(sRgbTaggedAsPq.deliveryCompatible)
        assertFalse(sRgbTaggedAsPq.precisionProven)
    }

    @Test
    fun `missing unknown or contradictory compatibility tokens remain unproven`() {
        val missing = VulkanEncoderSurfaceStatusParser.parse(
            "supported encoderSurfaceBound=true " +
                "encoderSurfaceFormat=A2B10G10R10_UNORM_PACK32 " +
                "encoderSurfaceBitDepth=10 encoderSurfacePrecision=PACKED_10_BIT " +
                "encoderSurfacePrecisionFallback=false"
        )
        val unknown = parseStatus(
            precision = "PACKED_10_BIT",
            format = "A2B10G10R10_UNORM_PACK32",
            bitDepth = 10,
            deliveryMode = "UNKNOWN",
            colorSpace = "UNKNOWN",
            deliveryCompatible = false
        )
        val contradictory = parseStatus(
            precision = "PACKED_10_BIT",
            format = "A2B10G10R10_UNORM_PACK32",
            bitDepth = 10,
            deliveryMode = "SDR",
            colorSpace = "HDR10_ST2084",
            deliveryCompatible = true
        )

        listOf(missing, unknown, contradictory).forEach {
            assertFalse(it.precisionProven)
            assertFalse(it.supportsTenBitProducer)
        }
    }

    @Test
    fun `delivery incompatibility retains measured precision and remains unproven`() {
        val parsed = parseStatus(
            precision = "PACKED_10_BIT",
            format = "A2B10G10R10_UNORM_PACK32",
            bitDepth = 10,
            deliveryMode = "HLG",
            colorSpace = "HDR10_ST2084",
            deliveryCompatible = false
        )
        val reconstructed = VulkanEncoderSurfaceStatus(
            bound = parsed.bound,
            format = parsed.format,
            bitDepth = parsed.bitDepth,
            precision = parsed.precision,
            precisionFallback = parsed.precisionFallback,
            colorSpace = parsed.colorSpace,
            deliveryMode = parsed.deliveryMode,
            deliveryCompatible = parsed.deliveryCompatible
        )

        assertEquals(VulkanEncoderSurfacePrecision.PACKED_10_BIT, reconstructed.precision)
        assertFalse(reconstructed.deliveryCompatible)
        assertFalse(reconstructed.precisionProven)
    }

    @Test
    fun `bridge evidence model avoids compiler inference hot path`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanBridge.kt"
        ).readText()
        val evidenceBlock = source
            .substringAfter("enum class VulkanEncoderSurfacePrecision")
            .substringBefore("data class VulkanEnhancementTelemetry")

        assertFalse(evidenceBlock.contains("DELIVERY_INCOMPATIBLE"))
        assertFalse(evidenceBlock.contains("constructor("))
        assertFalse(evidenceBlock.contains("setOf("))
        assertFalse(evidenceBlock.contains("runCatching"))
        assertFalse(evidenceBlock.contains("valueOf("))
        assertTrue(evidenceBlock.contains("deliveryCompatible"))
    }

    @Test
    fun `unknown malformed and unbound summaries remain unproven`() {
        val unknown = VulkanEncoderSurfaceStatusParser.parse(
            "supported encoderSurfaceBound=true encoderSurfaceFormat=UNDEFINED " +
                "encoderSurfaceBitDepth=10 encoderSurfacePrecision=UNKNOWN " +
                "encoderSurfacePrecisionFallback=false " +
                "encoderSurfaceColorSpace=UNKNOWN encoderSurfaceDeliveryMode=UNKNOWN " +
                "encoderSurfaceDeliveryCompatible=false"
        )
        val malformed = VulkanEncoderSurfaceStatusParser.parse(
            "supported encoderSurfaceBound=true encoderSurfaceBitDepth=10"
        )
        val unbound = VulkanEncoderSurfaceStatusParser.parse(
            "supported encoderSurfaceBound=false encoderSurfaceFormat=UNDEFINED " +
                "encoderSurfaceBitDepth=0 encoderSurfacePrecision=UNKNOWN " +
                "encoderSurfacePrecisionFallback=false " +
                "encoderSurfaceColorSpace=UNDEFINED encoderSurfaceDeliveryMode=UNKNOWN " +
                "encoderSurfaceDeliveryCompatible=false"
        )

        listOf(unknown, malformed, unbound).forEach {
            assertFalse(it.precisionProven)
            assertEquals(0, it.bitDepth)
            assertFalse(it.supportsTenBitProducer)
        }
        assertTrue(unknown.bound)
        assertFalse(unbound.bound)
    }

    @Test
    fun `runtime binding resolution clears stale precision on unbind or failed bind`() {
        val proven = VulkanEncoderSurfaceStatus(
            bound = true,
            format = "A2B10G10R10_UNORM_PACK32",
            bitDepth = 10,
            precision = VulkanEncoderSurfacePrecision.PACKED_10_BIT,
            precisionFallback = false,
            colorSpace = "SRGB_NONLINEAR",
            deliveryMode = "SDR",
            deliveryCompatible = true
        )

        assertEquals(
            VulkanEncoderSurfaceStatus.UNBOUND,
            VulkanRuntimeManager.resolveEncoderSurfaceStatus(
                surfaceRequested = false,
                bindSucceeded = true,
                reportedStatus = proven
            )
        )
        assertEquals(
            VulkanEncoderSurfaceStatus.UNBOUND,
            VulkanRuntimeManager.resolveEncoderSurfaceStatus(
                surfaceRequested = true,
                bindSucceeded = false,
                reportedStatus = proven
            )
        )
        assertEquals(
            proven,
            VulkanRuntimeManager.resolveEncoderSurfaceStatus(
                surfaceRequested = true,
                bindSucceeded = true,
                reportedStatus = proven
            )
        )
    }

    @Test
    fun `offscreen runtime accepts only proven ten bit sdr producer`() {
        val proven = VulkanEncoderSurfaceStatus(
            bound = true,
            format = "EGL_RGB10_A2",
            bitDepth = 10,
            precision = VulkanEncoderSurfacePrecision.PACKED_10_BIT,
            precisionFallback = false,
            colorSpace = "SRGB_NONLINEAR",
            deliveryMode = "SDR",
            deliveryCompatible = true,
            sourceFormat = "AHARDWAREBUFFER_FORMAT_YCbCr_P010",
            sourceBitDepth = 10,
            sourcePrecision = VulkanEncoderSurfacePrecision.PACKED_10_BIT,
            sourcePrecisionProven = true
        )
        val eightBit = proven.copy(
            format = "RGBA8",
            bitDepth = 8,
            precision = VulkanEncoderSurfacePrecision.EIGHT_BIT,
            precisionFallback = true
        )
        val incompatible = proven.copy(deliveryCompatible = false)

        assertEquals(
            proven,
            VulkanRuntimeManager.resolveOffscreenEncoderSurfaceStatus(
                surfaceRequested = true,
                bindSucceeded = true,
                reportedStatus = proven
            )
        )
        listOf(eightBit, incompatible).forEach { status ->
            assertEquals(
                VulkanEncoderSurfaceStatus.UNBOUND,
                VulkanRuntimeManager.resolveOffscreenEncoderSurfaceStatus(
                    surfaceRequested = true,
                    bindSucceeded = true,
                    reportedStatus = status
                )
            )
        }
    }

    @Test
    fun `offscreen runtime rejects delivery mode mismatch for requested route`() {
        val sdr = VulkanEncoderSurfaceStatus(
            bound = true,
            format = "EGL_RGB10_A2",
            bitDepth = 10,
            precision = VulkanEncoderSurfacePrecision.PACKED_10_BIT,
            precisionFallback = false,
            colorSpace = "SRGB_NONLINEAR",
            deliveryMode = "SDR",
            deliveryCompatible = true,
            sourceFormat = "AHARDWAREBUFFER_FORMAT_YCbCr_P010",
            sourceBitDepth = 10,
            sourcePrecision = VulkanEncoderSurfacePrecision.PACKED_10_BIT,
            sourcePrecisionProven = true
        )

        assertEquals(
            sdr,
            VulkanRuntimeManager.resolveOffscreenEncoderSurfaceStatus(
                surfaceRequested = true,
                bindSucceeded = true,
                reportedStatus = sdr,
                expectedDeliveryMode = "SDR"
            )
        )
        assertEquals(
            VulkanEncoderSurfaceStatus.UNBOUND,
            VulkanRuntimeManager.resolveOffscreenEncoderSurfaceStatus(
                surfaceRequested = true,
                bindSucceeded = true,
                reportedStatus = sdr,
                expectedDeliveryMode = "PQ"
            )
        )
    }

    @Test
    fun `offscreen status requires measured ten bit source precision`() {
        val missingSource = parseStatus(
            precision = "PACKED_10_BIT",
            format = "EGL_RGB10_A2",
            bitDepth = 10,
            deliveryMode = "SDR",
            colorSpace = "SRGB_NONLINEAR",
            deliveryCompatible = true
        )
        val privateSource = parseStatus(
            precision = "PACKED_10_BIT",
            format = "EGL_RGB10_A2",
            bitDepth = 10,
            deliveryMode = "SDR",
            colorSpace = "SRGB_NONLINEAR",
            deliveryCompatible = true,
            sourceFormat = "AHARDWAREBUFFER_FORMAT_BLOB",
            sourceBitDepth = 8,
            sourcePrecision = "EIGHT_BIT",
            sourcePrecisionProven = false
        )
        val p010Source = parseStatus(
            precision = "PACKED_10_BIT",
            format = "EGL_RGB10_A2",
            bitDepth = 10,
            deliveryMode = "SDR",
            colorSpace = "SRGB_NONLINEAR",
            deliveryCompatible = true,
            sourceFormat = "AHARDWAREBUFFER_FORMAT_YCbCr_P010",
            sourceBitDepth = 10,
            sourcePrecision = "PACKED_10_BIT",
            sourcePrecisionProven = true
        )

        assertTrue(missingSource.surfacePrecisionProven)
        assertFalse(missingSource.sourcePrecisionProven)
        assertFalse(missingSource.precisionProven)
        assertFalse(privateSource.precisionProven)
        assertEquals("AHARDWAREBUFFER_FORMAT_BLOB", privateSource.sourceFormat)
        assertEquals(8, privateSource.sourceBitDepth)
        assertTrue(p010Source.sourcePrecisionProven)
        assertTrue(p010Source.precisionProven)
        assertTrue(p010Source.supportsTenBitProducer)
    }

    @Test
    fun `offscreen status accepts native trusted opaque ten bit source precision`() {
        val trustedOpaqueSource = parseStatus(
            precision = "PACKED_10_BIT",
            format = "EGL_RGB10_A2",
            bitDepth = 10,
            deliveryMode = "SDR",
            colorSpace = "SRGB_NONLINEAR",
            deliveryCompatible = true,
            sourceFormat = "AHARDWAREBUFFER_FORMAT_2141391881",
            sourceBitDepth = 10,
            sourcePrecision = "TRUSTED_EXTERNAL_10_BIT",
            sourcePrecisionProven = true
        )
        val namedEightBitSource = parseStatus(
            precision = "PACKED_10_BIT",
            format = "EGL_RGB10_A2",
            bitDepth = 10,
            deliveryMode = "SDR",
            colorSpace = "SRGB_NONLINEAR",
            deliveryCompatible = true,
            sourceFormat = "AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM",
            sourceBitDepth = 10,
            sourcePrecision = "TRUSTED_EXTERNAL_10_BIT",
            sourcePrecisionProven = true
        )

        assertTrue(trustedOpaqueSource.sourcePrecisionProven)
        assertTrue(trustedOpaqueSource.precisionProven)
        assertTrue(trustedOpaqueSource.supportsTenBitProducer)
        assertFalse(namedEightBitSource.sourcePrecisionProven)
        assertFalse(namedEightBitSource.precisionProven)
    }

    @Test
    fun `offscreen hardware wall is recognized only for genuine driver refusals`() {
        // Genuine GPU-driver walls — persist across launches (scoped to build+OS fingerprint):
        assertTrue(
            VulkanRuntimeManager.offscreenSummaryIndicatesHardwareWall(
                "offscreenAhbEglEncoder=idle status=recordable RGB10_A2 config unavailable (enumerated 34 configs)"
            )
        )
        assertTrue(
            VulkanRuntimeManager.offscreenSummaryIndicatesHardwareWall(
                "offscreenAhbEglEncoder=idle status=recordable RGB10_A2 config unavailable actual=8/8/8/8 recordable=0"
            )
        )
        assertTrue(
            VulkanRuntimeManager.offscreenSummaryIndicatesHardwareWall(
                "offscreenAhbEglEncoder=idle status=eglCreateWindowSurface failed for all colorspaces (HLG/PQ/default/sRGB)"
            )
        )
        // Transient / token / source-precision misses stay RE-PROBABLE (never persisted as a wall):
        assertFalse(
            VulkanRuntimeManager.offscreenSummaryIndicatesHardwareWall(
                "offscreenAhbEglEncoder=idle status=offscreen source precision rejected format=AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM"
            )
        )
        assertFalse(
            VulkanRuntimeManager.offscreenSummaryIndicatesHardwareWall(
                "offscreenAhbEglEncoder=idle status=egl context ready"
            )
        )
        assertFalse(VulkanRuntimeManager.offscreenSummaryIndicatesHardwareWall("unavailable"))
    }

    private fun parseStatus(
        precision: String,
        format: String,
        bitDepth: Int,
        deliveryMode: String,
        colorSpace: String,
        deliveryCompatible: Boolean,
        sourceFormat: String? = null,
        sourceBitDepth: Int? = null,
        sourcePrecision: String? = null,
        sourcePrecisionProven: Boolean? = null
    ): VulkanEncoderSurfaceStatus =
        VulkanEncoderSurfaceStatusParser.parse(
            "supported encoderSurfaceBound=true " +
                "encoderSurfaceFormat=$format encoderSurfaceBitDepth=$bitDepth " +
                "encoderSurfacePrecision=$precision encoderSurfacePrecisionFallback=false " +
                "encoderSurfaceColorSpace=$colorSpace " +
                "encoderSurfaceDeliveryMode=$deliveryMode " +
                "encoderSurfaceDeliveryCompatible=$deliveryCompatible" +
                listOfNotNull(
                    sourceFormat?.let { " encoderSourceFormat=$it" },
                    sourceBitDepth?.let { " encoderSourceBitDepth=$it" },
                    sourcePrecision?.let { " encoderSourcePrecision=$it" },
                    sourcePrecisionProven?.let { " encoderSourcePrecisionProven=$it" }
                ).joinToString(separator = "")
        )
}
