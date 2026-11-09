package com.neoproc.neoproccamx.snce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** s7-f04 / CP4A — pure nearest-neighbour category-mask adapter. */
class SnceSegmentationAdapterTest {

    @Test
    fun nullMaskReturnsNull() {
        assertNull(SnceSegmentationAdapter.fromCategoryMask(null, 4, 4, 2, 2))
    }

    @Test
    fun degenerateDimensionsReturnNull() {
        val m = ByteArray(16) { 2 }
        assertNull(SnceSegmentationAdapter.fromCategoryMask(m, 0, 4, 2, 2))
        assertNull(SnceSegmentationAdapter.fromCategoryMask(m, 4, 4, 2, 0))
    }

    @Test
    fun bufferSmallerThanSourceReturnsNull() {
        assertNull(SnceSegmentationAdapter.fromCategoryMask(ByteArray(8), 4, 4, 2, 2))
    }

    @Test
    fun uniformMaskResamplesToUniformWithConfidence() {
        val src = ByteArray(16) { 2 }
        val r = SnceSegmentationAdapter.fromCategoryMask(src, 4, 4, 2, 2)!!
        assertEquals(2, r.width)
        assertEquals(2, r.height)
        r.classMask.forEach { assertEquals(2, it) }
        r.confidence.forEach { assertEquals(1f, it, 0f) }
    }

    @Test
    fun leftRightBoundaryIsPreservedAtNearestNeighbour() {
        // 4x4: columns 0,1 -> class 2 ; columns 2,3 -> class 3.
        val src = ByteArray(16) { i -> if ((i % 4) < 2) 2 else 3 }
        val r = SnceSegmentationAdapter.fromCategoryMask(src, 4, 4, 2, 2)!!
        // dst cols sample src cols 0 and 3 -> 2 (left) and 3 (right) on every row.
        assertEquals(2, r.classMask[0])
        assertEquals(3, r.classMask[1])
        assertEquals(2, r.classMask[2])
        assertEquals(3, r.classMask[3])
    }

    @Test
    fun confidenceLambdaIsAppliedAndCoerced() {
        val src = ByteArray(16) { 5 }
        val r = SnceSegmentationAdapter.fromCategoryMask(src, 4, 4, 2, 2) { cls -> if (cls == 5) 2f else -1f }
        // 2f coerced to 1f
        r!!.confidence.forEach { assertEquals(1f, it, 0f) }
    }
}
