package com.neoproc.neoproccamx.rendering

data class HdrPosterizationHistogramResult(
    val passes: Boolean,
    val occupiedBins: Int,
    val maxConsecutiveEmptyBins: Int,
    val totalSamples: Int
)

object HdrPosterizationHistogramValidator {
    fun validateNoPosterization8Bit(
        samples: IntArray,
        minimumOccupiedBins: Int = 192,
        maximumConsecutiveEmptyBins: Int = 4
    ): HdrPosterizationHistogramResult {
        if (samples.isEmpty()) {
            return HdrPosterizationHistogramResult(
                passes = false,
                occupiedBins = 0,
                maxConsecutiveEmptyBins = 256,
                totalSamples = 0
            )
        }
        val histogram = IntArray(256)
        samples.forEach { sample ->
            histogram[sample.coerceIn(0, 255)] += 1
        }
        val occupiedBins = histogram.count { it > 0 }
        var maxEmptyRun = 0
        var currentEmptyRun = 0
        histogram.forEach { count ->
            if (count == 0) {
                currentEmptyRun += 1
                maxEmptyRun = maxOf(maxEmptyRun, currentEmptyRun)
            } else {
                currentEmptyRun = 0
            }
        }
        return HdrPosterizationHistogramResult(
            passes = occupiedBins >= minimumOccupiedBins &&
                maxEmptyRun <= maximumConsecutiveEmptyBins,
            occupiedBins = occupiedBins,
            maxConsecutiveEmptyBins = maxEmptyRun,
            totalSamples = samples.size
        )
    }
}
