package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SdrCreativeLutSelectionPolicyTest {

    private val fullColor = floatArrayOf(0.11f, 0.22f, 0.33f) to 17
    private val userCreative = floatArrayOf(0.44f, 0.55f, 0.66f) to 17
    private val calibratedFallback = floatArrayOf(0.77f, 0.88f, 0.99f) to 33

    @Test
    fun `natural sdr uses calibrated fallback when full color resolves null`() {
        val resolved = resolve(fullColorCandidate = null)

        assertSame(calibratedFallback, resolved)
    }

    @Test
    fun `full color cube retains priority over user and fallback cubes`() {
        val resolved = resolve(
            fullColorCandidate = fullColor,
            userCreativeLutEnabled = true,
            userCreativeCandidate = userCreative
        )

        assertSame(fullColor, resolved)
    }

    @Test
    fun `user creative cube retains priority when full color cube is absent`() {
        val resolved = resolve(
            fullColorCandidate = null,
            userCreativeLutEnabled = true,
            userCreativeCandidate = userCreative
        )

        assertSame(userCreative, resolved)
    }

    @Test
    fun `hdr ndr log and raw delivery cannot receive the sdr fallback`() {
        assertNull(resolve(isSdrRecordDelivery = false, fullColorCandidate = null))
    }

    @Test
    fun `record develop light master remains flat`() {
        assertNull(resolve(recordDevelopLightMaster = true, fullColorCandidate = null))
    }

    @Test
    fun `disabled calibrated fallback preserves neutral output`() {
        assertNull(resolve(fullColorCandidate = null, calibratedFallbackEnabled = false))
    }

    @Test
    fun `sdr preview uses the exact recorded cube for wysiwyg parity`() {
        val previewOnly = floatArrayOf(0.12f, 0.34f, 0.56f) to 17

        val resolved = SdrCreativeLutSelectionPolicy.resolvePreviewCandidate(
            isSdrVideoDelivery = true,
            previewColorParityEnabled = true,
            recordCandidate = calibratedFallback,
            previewCandidate = previewOnly
        )

        assertSame(calibratedFallback, resolved)
    }

    @Test
    fun `non-sdr preview cannot receive an sdr record or preview cube`() {
        val resolved = SdrCreativeLutSelectionPolicy.resolvePreviewCandidate(
            isSdrVideoDelivery = false,
            previewColorParityEnabled = true,
            recordCandidate = calibratedFallback,
            previewCandidate = fullColor
        )

        assertNull(resolved)
    }

    private fun resolve(
        isSdrRecordDelivery: Boolean = true,
        recordDevelopLightMaster: Boolean = false,
        fullColorCandidate: Pair<FloatArray, Int>? = fullColor,
        userCreativeLutEnabled: Boolean = false,
        userCreativeCandidate: Pair<FloatArray, Int>? = null,
        calibratedFallbackEnabled: Boolean = true
    ): Pair<FloatArray, Int>? = SdrCreativeLutSelectionPolicy.resolveRecordCandidate(
        isSdrRecordDelivery = isSdrRecordDelivery,
        recordDevelopLightMaster = recordDevelopLightMaster,
        fullColorCandidate = fullColorCandidate,
        userCreativeLutEnabled = userCreativeLutEnabled,
        userCreativeCandidate = userCreativeCandidate,
        calibratedFallbackEnabled = calibratedFallbackEnabled,
        calibratedFallbackCandidate = calibratedFallback
    )
}
