package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeUnsignedLongWireCodecTest {

    @Test
    fun `uint64 decimal decoder preserves every bit including high vendor bits`() {
        val cases = listOf(
            "0" to 0uL,
            "9223372036854775807" to Long.MAX_VALUE.toULong(),
            "9223372036854775808" to 0x8000_0000_0000_0000uL,
            "9223372036854775874" to 0x8000_0000_0000_0042uL,
            "18446744073709551615" to ULong.MAX_VALUE
        )

        cases.forEach { (decimal, expectedBits) ->
            assertEquals(decimal, expectedBits.toLong(), parseNativeUint64DecimalBitsOrZero(decimal))
        }
    }

    @Test
    fun `both native buffer parsers retain unsigned usage bits without ABI changes`() {
        val decimal = "9223372036854775874"
        val expectedBits = 0x8000_0000_0000_0042uL.toLong()
        val handle = NativeBufferHandleInfo.parse(
            "valid=true;reason=ok;width=4096;height=3072;layers=1;format=54;" +
                "usage=$decimal;refCount=1;pixelCopy=false"
        )
        val camera = NativeHardwareBufferValidation.parse(
            "valid=true;reason=ok;width=4096;height=3072;layers=1;format=54;" +
                "usage=$decimal;pixelCopy=false"
        )

        assertEquals(expectedBits, handle.usage)
        assertEquals(expectedBits, camera.usage)
    }

    @Test
    fun `malformed or overflowing usage fails safe to zero`() {
        val malformed = listOf<String?>(
            null,
            "",
            "-1",
            "+1",
            " 1",
            "1 ",
            "0x1",
            "12vendor",
            "18446744073709551616"
        )

        malformed.forEach { value ->
            assertEquals("value=$value", 0L, parseNativeUint64DecimalBitsOrZero(value))
        }
    }
}
