package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.recording.HdrImagingMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-golden mirror of the shader helper `ndrDeliveryPqEncode` in
 * ycbcr_preview.frag (the NDR10+/NIVUKX/apex record PQ delivery).
 *
 * NDR/NIVUKX signal 1.0 is the 203-nit diffuse-white reference. Values above
 * 1.0 retain highlight headroom and roll monotonically toward the per-mode peak.
 * APEX remains peak-normalized and is deliberately encoded by a different helper.
 */
class NdrDeliveryPqScaleParityTest {

    private val ndrPeak = 5000.0
    private val nivukxPeak = 10000.0
    private val diffuseWhite = 203.0

    /** Exact port of the shader helper's math. */
    private fun deliveredNits(signal: Double, peak: Double): Double {
        val raw = signal.coerceAtLeast(0.0) * diffuseWhite
        val nits = if (raw <= diffuseWhite) {
            raw
        } else {
            val range = peak - diffuseWhite
            diffuseWhite + range * (1.0 - kotlin.math.exp(-(raw - diffuseWhite) / range))
        }.coerceAtMost(peak)
        return HdrImagingMath.pqDecodeToNits(HdrImagingMath.pqEncodeFromNits(nits))
    }

    @Test
    fun diffuseWhiteSignalMapsTo203NitsNotMasterPeak() {
        assertEquals(diffuseWhite, deliveredNits(1.0, ndrPeak), 0.5)
        assertEquals(diffuseWhite, deliveredNits(1.0, nivukxPeak), 0.5)
    }

    @Test
    fun diffuseWhitePlateauIsHundredsNotThousandsOfNits() {
        // The old bug fed the raw [0,1] signal to bt2100PqInverseEotf, i.e. treated
        // 1.0 as 10000 nits: the 0.945 white plateau shipped at ~9450 nits.
        val oldBugNits = HdrImagingMath.pqDecodeToNits(HdrImagingMath.pqEncodeFromNits(0.945 * 10000.0))
        assertTrue("sanity: old behavior really was ~9450 nits", oldBugNits > 9000.0)

        // Fixed: 0.945 is just below the 203-nit diffuse reference.
        assertEquals(0.945 * diffuseWhite, deliveredNits(0.945, nivukxPeak), 0.5)
        assertTrue("diffuse plateau must remain near 203 nits", deliveredNits(0.945, nivukxPeak) < 203.0)
    }

    @Test
    fun midtoneIsNotBlownOut() {
        // Old bug: mid-tone 0.20 -> 2000 nits. Fixed: 0.20 * diffuse white.
        assertEquals(0.20 * diffuseWhite, deliveredNits(0.20, ndrPeak), 0.5)
        assertTrue(deliveredNits(0.20, ndrPeak) < 50.0)
    }

    @Test
    fun deliveryNeverExceedsMasterPeak() {
        for (peak in listOf(ndrPeak, nivukxPeak)) {
            var s = 0.0
            while (s <= 100.0 + 1e-9) {
                assertTrue("signal $s exceeds master peak $peak", deliveredNits(s, peak) <= peak + 3.0)
                s += 0.25
            }
        }
    }

    @Test
    fun deliveryIsMonotonic() {
        for (peak in listOf(ndrPeak, nivukxPeak)) {
            var prev = -1.0
            var s = 0.0
            while (s <= 32.0 + 1e-9) {
                val n = deliveredNits(s, peak)
                assertTrue("non-monotone delivery at signal=$s", n >= prev - 1e-6)
                prev = n
                s += 0.025
            }
        }
    }

    @Test
    fun highlightsRemainAboveDiffuseWhiteAndApproachProfilePeak() {
        for (peak in listOf(ndrPeak, nivukxPeak)) {
            assertTrue(deliveredNits(2.0, peak) > diffuseWhite)
            assertTrue(deliveredNits(8.0, peak) > deliveredNits(2.0, peak))
            // A 10,000-nit shoulder has roughly twice the headroom of the 5,000-nit
            // profile, so use a sufficiently high scene-linear highlight to verify the
            // common asymptote without changing the 203-nit diffuse-white anchor.
            assertTrue(deliveredNits(256.0, peak) > peak * 0.99)
        }
    }
}
