package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NdrExplicitYuvDecodePolicyTest {

    @Test
    fun `vivo I2405 family uses validated hardware conversion`() {
        assertFalse(NdrExplicitYuvDecodePolicy.isAllowed("vivo", "I2405", "I2405", "I2405i"))
        assertFalse(NdrExplicitYuvDecodePolicy.isAllowed("VIVO", "i2405", "other", "other"))
        assertFalse(NdrExplicitYuvDecodePolicy.isAllowed("vivo", "other", "other", "i2405i"))
    }

    @Test
    fun `conforming device families retain explicit decode`() {
        assertTrue(NdrExplicitYuvDecodePolicy.isAllowed("vivo", "V2301", "V2301", "V2301"))
        assertTrue(NdrExplicitYuvDecodePolicy.isAllowed("Google", "Pixel 10", "mustang", "mustang"))
        assertTrue(NdrExplicitYuvDecodePolicy.isAllowed(null, null, null, null))
    }
}
