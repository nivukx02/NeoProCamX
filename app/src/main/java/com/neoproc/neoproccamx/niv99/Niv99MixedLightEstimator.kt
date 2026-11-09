package com.neoproc.neoproccamx.niv99

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class Niv99LocalIlluminantField(capacity: Int) {
    val redGain: FloatArray = FloatArray(capacity) { 1f }
    val blueGain: FloatArray = FloatArray(capacity) { 1f }
    val confidence: FloatArray = FloatArray(capacity)
    val cluster: IntArray = IntArray(capacity) { -1 }
    var columns: Int = 0
        internal set
    var rows: Int = 0
        internal set
    var count: Int = 0
        internal set
    var clusterCount: Int = 0
        internal set
    var mixedLightConfidence: Float = 0f
        internal set
    var revision: Long = 0L
        internal set

    /** This field is a render correction only and is never legal Camera2 request state. */
    val renderingOnly: Boolean
        get() = true

    fun reset(columns: Int, rows: Int) {
        require(columns >= 0 && rows >= 0 && columns.toLong() * rows <= redGain.size)
        this.columns = columns
        this.rows = rows
        count = columns * rows
        clusterCount = 0
        mixedLightConfidence = 0f
        for (index in 0 until count) {
            redGain[index] = 1f
            blueGain[index] = 1f
            confidence[index] = 0f
            cluster[index] = -1
        }
    }
}

/** Robust deterministic clustering in sensor log-chromaticity space. */
class Niv99MixedLightEstimator(maxTiles: Int) {
    private val logRed = FloatArray(maxTiles)
    private val logBlue = FloatArray(maxTiles)
    private val weight = FloatArray(maxTiles)
    private val sourceTile = IntArray(maxTiles)
    private val assignments = IntArray(maxTiles)
    private val centersRed = FloatArray(MAX_CLUSTERS)
    private val centersBlue = FloatArray(MAX_CLUSTERS)
    private val clusterWeight = FloatArray(MAX_CLUSTERS)
    private val clusterResidual = FloatArray(MAX_CLUSTERS)
    private val smoothRed = FloatArray(maxTiles)
    private val smoothBlue = FloatArray(maxTiles)
    private val smoothConfidence = FloatArray(maxTiles)
    private var pointCount = 0

    fun estimate(
        statistics: Niv99TileStatistics,
        global: Niv99IlluminantEstimate,
        output: Niv99LocalIlluminantField
    ): Niv99ValidationResult {
        if (statistics.count > logRed.size || statistics.count > output.redGain.size) {
            return Niv99ValidationResult.INVALID_DIMENSIONS
        }
        output.reset(statistics.columns, statistics.rows)
        pointCount = collectPoints(statistics)
        if (pointCount < MIN_CLUSTER_POINTS) {
            fillGlobal(global, statistics, output)
            return Niv99ValidationResult.VALID
        }
        initializeTwoCenters()
        fitClusters(2)
        var selectedClusters = if (twoClustersAreMeaningful()) 2 else 1
        if (selectedClusters == 2 && pointCount >= MIN_THREE_CLUSTER_POINTS) {
            initializeThirdCenter()
            fitClusters(3)
            if (threeClustersAreMeaningful()) selectedClusters = 3 else fitClusters(2)
        }
        if (selectedClusters == 1) {
            fillGlobal(global, statistics, output)
            return Niv99ValidationResult.VALID
        }
        output.clusterCount = selectedClusters
        output.mixedLightConfidence = mixedConfidence(selectedClusters)
        publishLocalField(statistics, global, selectedClusters, output)
        smoothField(output)
        output.revision += 1L
        return Niv99ValidationResult.VALID
    }

    private fun collectPoints(statistics: Niv99TileStatistics): Int {
        var count = 0
        for (tile in 0 until statistics.count) {
            val confidence = statistics.reliability[tile] *
                max(statistics.neutralConfidence[tile], MIN_NEUTRAL_FLOOR)
            if (confidence <= MIN_POINT_WEIGHT) continue
            val red = statistics.redOverGreen[tile]
            val blue = statistics.blueOverGreen[tile]
            if (!red.isFinite() || !blue.isFinite() || red <= 0f || blue <= 0f) continue
            logRed[count] = Niv99Math.safeLog(red)
            logBlue[count] = Niv99Math.safeLog(blue)
            weight[count] = confidence
            sourceTile[count] = tile
            assignments[count] = 0
            count += 1
        }
        return count
    }

    private fun initializeTwoCenters() {
        var first = 0
        var bestWeight = -1f
        for (index in 0 until pointCount) {
            if (weight[index] > bestWeight) {
                bestWeight = weight[index]
                first = index
            }
        }
        centersRed[0] = logRed[first]
        centersBlue[0] = logBlue[first]
        var second = first
        var farthest = -1f
        for (index in 0 until pointCount) {
            val distance = squaredDistance(index, centersRed[0], centersBlue[0]) * weight[index]
            if (distance > farthest) {
                farthest = distance
                second = index
            }
        }
        centersRed[1] = logRed[second]
        centersBlue[1] = logBlue[second]
    }

    private fun initializeThirdCenter() {
        var selected = 0
        var farthest = -1f
        for (index in 0 until pointCount) {
            val distance0 = squaredDistance(index, centersRed[0], centersBlue[0])
            val distance1 = squaredDistance(index, centersRed[1], centersBlue[1])
            val nearest = min(distance0, distance1) * weight[index]
            if (nearest > farthest) {
                farthest = nearest
                selected = index
            }
        }
        centersRed[2] = logRed[selected]
        centersBlue[2] = logBlue[selected]
    }

    private fun fitClusters(clusterCount: Int) {
        repeat(CLUSTER_ITERATIONS) {
            clusterWeight.fill(0f)
            clusterResidual.fill(0f)
            val sumRed = smoothRed
            val sumBlue = smoothBlue
            sumRed.fill(0f, 0, clusterCount)
            sumBlue.fill(0f, 0, clusterCount)
            for (index in 0 until pointCount) {
                var nearestCluster = 0
                var nearestDistance = Float.POSITIVE_INFINITY
                for (cluster in 0 until clusterCount) {
                    val distance = squaredDistance(index, centersRed[cluster], centersBlue[cluster])
                    if (distance < nearestDistance) {
                        nearestDistance = distance
                        nearestCluster = cluster
                    }
                }
                assignments[index] = nearestCluster
                val robust = Niv99Math.cauchyWeight(nearestDistance, CLUSTER_SCALE_SQUARED)
                val effectiveWeight = weight[index] * robust
                clusterWeight[nearestCluster] += effectiveWeight
                sumRed[nearestCluster] += logRed[index] * effectiveWeight
                sumBlue[nearestCluster] += logBlue[index] * effectiveWeight
                clusterResidual[nearestCluster] += nearestDistance * effectiveWeight
            }
            for (cluster in 0 until clusterCount) {
                val totalWeight = clusterWeight[cluster]
                if (totalWeight > MIN_POINT_WEIGHT) {
                    centersRed[cluster] = sumRed[cluster] / totalWeight
                    centersBlue[cluster] = sumBlue[cluster] / totalWeight
                    clusterResidual[cluster] /= totalWeight
                }
            }
        }
    }

    private fun twoClustersAreMeaningful(): Boolean {
        val total = clusterWeight[0] + clusterWeight[1]
        if (total <= 0f) return false
        val support0 = clusterWeight[0] / total
        val support1 = clusterWeight[1] / total
        val separation = centerDistance(0, 1)
        val within = sqrt(max(clusterResidual[0] + clusterResidual[1], 0f) * 0.5f)
        return min(support0, support1) >= MIN_CLUSTER_SUPPORT &&
            separation >= max(MIN_CLUSTER_SEPARATION, within * MIN_SEPARATION_TO_SPREAD)
    }

    private fun threeClustersAreMeaningful(): Boolean {
        val total = clusterWeight[0] + clusterWeight[1] + clusterWeight[2]
        if (total <= 0f) return false
        for (cluster in 0 until 3) {
            if (clusterWeight[cluster] / total < MIN_THIRD_CLUSTER_SUPPORT) return false
        }
        return centerDistance(0, 1) >= MIN_CLUSTER_SEPARATION &&
            centerDistance(0, 2) >= MIN_CLUSTER_SEPARATION &&
            centerDistance(1, 2) >= MIN_CLUSTER_SEPARATION
    }

    private fun mixedConfidence(clusterCount: Int): Float {
        var minimumSeparation = Float.POSITIVE_INFINITY
        var maximumResidual = 0f
        var totalWeight = 0f
        var minimumSupport = 1f
        for (cluster in 0 until clusterCount) {
            totalWeight += clusterWeight[cluster]
            maximumResidual = max(maximumResidual, sqrt(max(clusterResidual[cluster], 0f)))
            for (other in cluster + 1 until clusterCount) {
                minimumSeparation = min(minimumSeparation, centerDistance(cluster, other))
            }
        }
        for (cluster in 0 until clusterCount) {
            minimumSupport = min(minimumSupport, clusterWeight[cluster] / max(totalWeight, MIN_POINT_WEIGHT))
        }
        val separationConfidence = Niv99Math.smoothstep(
            MIN_CLUSTER_SEPARATION,
            STRONG_CLUSTER_SEPARATION,
            minimumSeparation
        )
        val residualConfidence = 1f - Niv99Math.smoothstep(0.08f, 0.30f, maximumResidual)
        val supportConfidence = Niv99Math.smoothstep(MIN_CLUSTER_SUPPORT, 0.35f, minimumSupport)
        return Niv99Math.saturateFinite(
            separationConfidence * 0.50f + residualConfidence * 0.25f + supportConfidence * 0.25f
        )
    }

    private fun publishLocalField(
        statistics: Niv99TileStatistics,
        global: Niv99IlluminantEstimate,
        clusterCount: Int,
        output: Niv99LocalIlluminantField
    ) {
        for (tile in 0 until statistics.count) {
            output.redGain[tile] = global.redGain
            output.blueGain[tile] = global.blueGain
            output.confidence[tile] = 0f
            output.cluster[tile] = -1
        }
        for (point in 0 until pointCount) {
            val tile = sourceTile[point]
            val assigned = assignments[point].coerceIn(0, clusterCount - 1)
            val residual = sqrt(squaredDistance(point, centersRed[assigned], centersBlue[assigned]))
            val localConfidence = weight[point] *
                (1f - Niv99Math.smoothstep(0.10f, 0.35f, residual)) *
                output.mixedLightConfidence
            val clusterRedGain = (1f / exp(centersRed[assigned])).coerceIn(MIN_GAIN, MAX_GAIN)
            val clusterBlueGain = (1f / exp(centersBlue[assigned])).coerceIn(MIN_GAIN, MAX_GAIN)
            output.redGain[tile] = Niv99Math.lerp(global.redGain, clusterRedGain, localConfidence)
            output.blueGain[tile] = Niv99Math.lerp(global.blueGain, clusterBlueGain, localConfidence)
            output.confidence[tile] = localConfidence
            output.cluster[tile] = assigned
        }
    }

    private fun smoothField(output: Niv99LocalIlluminantField) {
        repeat(SMOOTHING_ITERATIONS) {
            for (y in 0 until output.rows) {
                for (x in 0 until output.columns) {
                    val index = y * output.columns + x
                    var sumRed = output.redGain[index]
                    var sumBlue = output.blueGain[index]
                    var sumConfidence = output.confidence[index]
                    var normalizer = 1f
                    for (offsetY in -1..1) {
                        for (offsetX in -1..1) {
                            if (offsetX == 0 && offsetY == 0) continue
                            val sampleX = x + offsetX
                            val sampleY = y + offsetY
                            if (sampleX !in 0 until output.columns || sampleY !in 0 until output.rows) continue
                            val neighbor = sampleY * output.columns + sampleX
                            val spatialWeight = if (offsetX == 0 || offsetY == 0) 0.16f else 0.08f
                            val confidenceCompatibility = 1f - kotlin.math.abs(
                                output.confidence[index] - output.confidence[neighbor]
                            )
                            val neighborWeight = spatialWeight * confidenceCompatibility.coerceIn(0f, 1f)
                            sumRed += output.redGain[neighbor] * neighborWeight
                            sumBlue += output.blueGain[neighbor] * neighborWeight
                            sumConfidence += output.confidence[neighbor] * neighborWeight
                            normalizer += neighborWeight
                        }
                    }
                    smoothRed[index] = sumRed / normalizer
                    smoothBlue[index] = sumBlue / normalizer
                    smoothConfidence[index] = sumConfidence / normalizer
                }
            }
            for (index in 0 until output.count) {
                output.redGain[index] = smoothRed[index]
                output.blueGain[index] = smoothBlue[index]
                output.confidence[index] = smoothConfidence[index]
            }
        }
    }

    private fun fillGlobal(
        global: Niv99IlluminantEstimate,
        statistics: Niv99TileStatistics,
        output: Niv99LocalIlluminantField
    ) {
        for (index in 0 until statistics.count) {
            output.redGain[index] = global.redGain
            output.blueGain[index] = global.blueGain
            output.confidence[index] = 0f
            output.cluster[index] = 0
        }
        output.clusterCount = 1
        output.mixedLightConfidence = 0f
        output.revision += 1L
    }

    private fun squaredDistance(index: Int, centerRed: Float, centerBlue: Float): Float =
        Niv99Math.square(logRed[index] - centerRed) + Niv99Math.square(logBlue[index] - centerBlue)

    private fun centerDistance(first: Int, second: Int): Float = sqrt(
        Niv99Math.square(centersRed[first] - centersRed[second]) +
            Niv99Math.square(centersBlue[first] - centersBlue[second])
    )

    companion object {
        private const val MAX_CLUSTERS = 3
        private const val CLUSTER_ITERATIONS = 8
        private const val SMOOTHING_ITERATIONS = 2
        private const val MIN_CLUSTER_POINTS = 8
        private const val MIN_THREE_CLUSTER_POINTS = 24
        private const val MIN_POINT_WEIGHT = 1e-5f
        private const val MIN_NEUTRAL_FLOOR = 0.05f
        private const val CLUSTER_SCALE_SQUARED = 0.04f
        private const val MIN_CLUSTER_SUPPORT = 0.15f
        private const val MIN_THIRD_CLUSTER_SUPPORT = 0.10f
        private const val MIN_CLUSTER_SEPARATION = 0.16f
        private const val STRONG_CLUSTER_SEPARATION = 0.45f
        private const val MIN_SEPARATION_TO_SPREAD = 1.8f
        private const val MIN_GAIN = 0.125f
        private const val MAX_GAIN = 8f
    }
}

