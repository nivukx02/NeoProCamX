package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.recording.HdrImagingMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-golden mirror of the shader helper `ndrDeliveryPqEncode` in
 * ycbcr_preview.frag (the NDR10+/NIVUKX/apex record PQ delivery).
 *
 * The NDR/NIVUKX/apex tone-mapped delivery signal is normalized so 1.0 == the
 * mode's master peak nits (NDR 1420, NIVUKX 1680), NOT 10000. ST2084 expects
 * 1.0 == 10000 nits, so the shader now scales by peak/10000 before encode:
 *
 *     bt2100PqInverseEotf(clamp(signal * peak / 10000, 0, 1))
 *
 * which (since bt2100PqInverseEotf(x) == pqEncodeFromNits(x * 10000)) equals
 * pqEncodeFromNits(clamp(signal * peak, 0, 10000)). These goldens lock that the
 * delivered luminance tracks the true master peak instead of shipping diffuse
 * content at thousands of nits (the PQ-nit-scale defect, audit rank 1).
 */
class NdrDeliveryPqScaleParityTest {

    private val ndrPeak = 1420.0
    private val nivukxPeak = 1680.0

    /** Exact port of the shader helper's math. */
    private fun deliveredNits(signal: Double, peak: Double): Double {
        val nits = (signal * peak).coerceIn(0.0, 10000.0)
        return HdrImagingMath.pqDecodeToNits(HdrImagingMath.pqEncodeFromNits(nits))
    }

    @Test
    fun peakSignalMapsToMasterPeakNotTenThousand() {
        assertEquals(ndrPeak, deliveredNits(1.0, ndrPeak), 3.0)
        assertEquals(nivukxPeak, deliveredNits(1.0, nivukxPeak), 3.0)
    }

    @Test
    fun diffuseWhitePlateauIsHundredsNotThousandsOfNits() {
        // The old bug fed the raw [0,1] signal to bt2100PqInverseEotf, i.e. treated
        // 1.0 as 10000 nits: the 0.945 white plateau shipped at ~9450 nits.
        val oldBugNits = HdrImagingMath.pqDecodeToNits(HdrImagingMath.pqEncodeFromNits(0.945 * 10000.0))
        assertTrue("sanity: old behavior really was ~9450 nits", oldBugNits > 9000.0)

        // Fixed: 0.945 * peak, bounded by the declared master peak.
        assertEquals(0.945 * nivukxPeak, deliveredNits(0.945, nivukxPeak), 5.0)
        assertTrue("diffuse plateau must be < 2000 nits now", deliveredNits(0.945, nivukxPeak) < 2000.0)
    }

    @Test
    fun midtoneIsNotBlownOut() {
        // Old bug: mid-tone 0.20 -> 2000 nits. Fixed: 0.20 * peak.
        assertEquals(0.20 * ndrPeak, deliveredNits(0.20, ndrPeak), 3.0)
        assertTrue(deliveredNits(0.20, ndrPeak) < 400.0)
    }

    @Test
    fun deliveryNeverExceedsMasterPeak() {
        for (peak in listOf(ndrPeak, nivukxPeak)) {
            var s = 0.0
            while (s <= 1.0 + 1e-9) {
                assertTrue("signal $s exceeds master peak $peak", deliveredNits(s, peak) <= peak + 3.0)
                s += 0.01
            }
        }
    }

    @Test
    fun deliveryIsMonotonic() {
        for (peak in listOf(ndrPeak, nivukxPeak)) {
            var prev = -1.0
            var s = 0.0
            while (s <= 1.0 + 1e-9) {
                val n = deliveredNits(s, peak)
                assertTrue("non-monotone delivery at signal=$s", n >= prev - 1e-6)
                prev = n
                s += 0.005
            }
        }
    }
}
