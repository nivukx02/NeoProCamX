package com.neoproc.neoproccamx.recording

/** Fail-closed contract for a RAW-to-RAW export: every sensor sample remains unmodified. */
internal object RawCinemaDngIdentityPolicy {
    fun requirePreservedSensorFrame(
        samples: IntArray,
        width: Int,
        height: Int,
        frameNumber: Long
    ) {
        val expectedSamples = Math.multiplyExact(width.toLong(), height.toLong())
        require(expectedSamples in 1..Int.MAX_VALUE.toLong()) {
            "RAW frame $frameNumber has invalid geometry ${width}x$height"
        }
        require(samples.size.toLong() == expectedSamples) {
            "RAW frame $frameNumber sample count ${samples.size} does not match " +
                "${width}x$height ($expectedSamples)"
        }
        samples.forEachIndexed { index, sample ->
            require(sample in 0..0xffff) {
                "RAW frame $frameNumber sample $index is outside unsigned 16-bit storage: $sample"
            }
        }
    }
}
