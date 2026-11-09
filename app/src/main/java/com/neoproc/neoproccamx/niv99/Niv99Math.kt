package com.neoproc.neoproccamx.niv99

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Allocation-free numerical primitives shared by the CPU reference and shader parity tests. */
object Niv99Math {
    private const val EPSILON = 1e-8f
    private const val DOUBLE_EPSILON = 1e-12

    fun saturateFinite(value: Float): Float = when {
        !value.isFinite() -> 0f
        value <= 0f -> 0f
        value >= 1f -> 1f
        else -> value
    }

    fun positiveFinite(value: Float, fallback: Float): Float =
        if (value.isFinite() && value > 0f) value else fallback

    fun safeLog(value: Float): Float = ln(max(value, EPSILON))

    fun safeExp(value: Float): Float = exp(value.coerceIn(-20f, 20f))

    fun square(value: Float): Float = value * value

    fun lerp(start: Float, end: Float, amount: Float): Float =
        start + (end - start) * saturateFinite(amount)

    fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        if (edge1 <= edge0) return if (value >= edge1) 1f else 0f
        val x = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    fun huberWeight(residual: Float, scale: Float): Float {
        val absolute = abs(residual)
        val safeScale = max(scale, EPSILON)
        return if (absolute <= safeScale) 1f else safeScale / absolute
    }

    fun tukeyWeight(residual: Float, cutoff: Float): Float {
        val safeCutoff = max(cutoff, EPSILON)
        val normalized = abs(residual) / safeCutoff
        if (normalized >= 1f) return 0f
        val complement = 1f - normalized * normalized
        return complement * complement
    }

    fun cauchyWeight(residualSquared: Float, scaleSquared: Float): Float =
        1f / (1f + max(residualSquared, 0f) / max(scaleSquared, EPSILON))

    fun weightedMean(values: FloatArray, weights: FloatArray, count: Int): Float {
        require(count in 0..values.size && count <= weights.size)
        var weightedSum = 0.0
        var weightSum = 0.0
        for (index in 0 until count) {
            val value = values[index]
            val weight = weights[index]
            if (!value.isFinite() || !weight.isFinite() || weight <= 0f) continue
            weightedSum += value.toDouble() * weight
            weightSum += weight
        }
        return if (weightSum > DOUBLE_EPSILON) (weightedSum / weightSum).toFloat() else 0f
    }

    fun weightedVariance(
        values: FloatArray,
        weights: FloatArray,
        count: Int,
        mean: Float
    ): Float {
        require(count in 0..values.size && count <= weights.size)
        var weightedSum = 0.0
        var weightSum = 0.0
        for (index in 0 until count) {
            val value = values[index]
            val weight = weights[index]
            if (!value.isFinite() || !weight.isFinite() || weight <= 0f) continue
            val delta = value - mean
            weightedSum += delta.toDouble() * delta * weight
            weightSum += weight
        }
        return if (weightSum > DOUBLE_EPSILON) (weightedSum / weightSum).toFloat() else 0f
    }

    /** In-place quickselect avoids sorting/allocation for robust medians. */
    fun medianInPlace(values: FloatArray, count: Int): Float {
        require(count in 1..values.size)
        val middle = count / 2
        val upper = selectInPlace(values, 0, count - 1, middle)
        if (count and 1 == 1) return upper
        var lower = values[0]
        for (index in 1 until middle) lower = max(lower, values[index])
        return (lower + upper) * 0.5f
    }

    fun medianAbsoluteDeviationInPlace(
        source: FloatArray,
        scratch: FloatArray,
        count: Int,
        median: Float
    ): Float {
        require(count in 1..source.size && count <= scratch.size)
        for (index in 0 until count) scratch[index] = abs(source[index] - median)
        return medianInPlace(scratch, count)
    }

    private fun selectInPlace(
        values: FloatArray,
        initialLeft: Int,
        initialRight: Int,
        target: Int
    ): Float {
        var left = initialLeft
        var right = initialRight
        while (true) {
            if (left == right) return values[left]
            var pivotIndex = (left + right) ushr 1
            pivotIndex = partition(values, left, right, pivotIndex)
            when {
                target == pivotIndex -> return values[target]
                target < pivotIndex -> right = pivotIndex - 1
                else -> left = pivotIndex + 1
            }
        }
    }

    private fun partition(
        values: FloatArray,
        left: Int,
        right: Int,
        pivotIndex: Int
    ): Int {
        val pivot = values[pivotIndex]
        swap(values, pivotIndex, right)
        var storeIndex = left
        for (index in left until right) {
            if (values[index] < pivot) {
                swap(values, storeIndex, index)
                storeIndex += 1
            }
        }
        swap(values, right, storeIndex)
        return storeIndex
    }

    private fun swap(values: FloatArray, first: Int, second: Int) {
        val temporary = values[first]
        values[first] = values[second]
        values[second] = temporary
    }

    fun setIdentity3(output: FloatArray) {
        require(output.size >= 9)
        output.fill(0f, 0, 9)
        output[0] = 1f
        output[4] = 1f
        output[8] = 1f
    }

    fun copy3(source: FloatArray, output: FloatArray) {
        require(source.size >= 9 && output.size >= 9)
        source.copyInto(output, endIndex = 9)
    }

    fun multiply3(left: FloatArray, right: FloatArray, output: FloatArray) {
        require(left.size >= 9 && right.size >= 9 && output.size >= 9)
        val a00 = left[0]
        val a01 = left[1]
        val a02 = left[2]
        val a10 = left[3]
        val a11 = left[4]
        val a12 = left[5]
        val a20 = left[6]
        val a21 = left[7]
        val a22 = left[8]
        val b00 = right[0]
        val b01 = right[1]
        val b02 = right[2]
        val b10 = right[3]
        val b11 = right[4]
        val b12 = right[5]
        val b20 = right[6]
        val b21 = right[7]
        val b22 = right[8]
        output[0] = a00 * b00 + a01 * b10 + a02 * b20
        output[1] = a00 * b01 + a01 * b11 + a02 * b21
        output[2] = a00 * b02 + a01 * b12 + a02 * b22
        output[3] = a10 * b00 + a11 * b10 + a12 * b20
        output[4] = a10 * b01 + a11 * b11 + a12 * b21
        output[5] = a10 * b02 + a11 * b12 + a12 * b22
        output[6] = a20 * b00 + a21 * b10 + a22 * b20
        output[7] = a20 * b01 + a21 * b11 + a22 * b21
        output[8] = a20 * b02 + a21 * b12 + a22 * b22
    }

    fun multiply3Vector(matrix: FloatArray, x: Float, y: Float, z: Float, output: FloatArray) {
        require(matrix.size >= 9 && output.size >= 3)
        output[0] = matrix[0] * x + matrix[1] * y + matrix[2] * z
        output[1] = matrix[3] * x + matrix[4] * y + matrix[5] * z
        output[2] = matrix[6] * x + matrix[7] * y + matrix[8] * z
    }

    fun transpose3(matrix: FloatArray, output: FloatArray) {
        require(matrix.size >= 9 && output.size >= 9)
        output[0] = matrix[0]
        output[1] = matrix[3]
        output[2] = matrix[6]
        output[3] = matrix[1]
        output[4] = matrix[4]
        output[5] = matrix[7]
        output[6] = matrix[2]
        output[7] = matrix[5]
        output[8] = matrix[8]
    }

    fun determinant3(matrix: FloatArray): Float {
        require(matrix.size >= 9)
        return matrix[0] * (matrix[4] * matrix[8] - matrix[5] * matrix[7]) -
            matrix[1] * (matrix[3] * matrix[8] - matrix[5] * matrix[6]) +
            matrix[2] * (matrix[3] * matrix[7] - matrix[4] * matrix[6])
    }

    fun invert3(matrix: FloatArray, output: FloatArray): Boolean {
        require(matrix.size >= 9 && output.size >= 9)
        val determinant = determinant3(matrix)
        if (!determinant.isFinite() || abs(determinant) < EPSILON) return false
        val inverseDeterminant = 1f / determinant
        output[0] = (matrix[4] * matrix[8] - matrix[5] * matrix[7]) * inverseDeterminant
        output[1] = (matrix[2] * matrix[7] - matrix[1] * matrix[8]) * inverseDeterminant
        output[2] = (matrix[1] * matrix[5] - matrix[2] * matrix[4]) * inverseDeterminant
        output[3] = (matrix[5] * matrix[6] - matrix[3] * matrix[8]) * inverseDeterminant
        output[4] = (matrix[0] * matrix[8] - matrix[2] * matrix[6]) * inverseDeterminant
        output[5] = (matrix[2] * matrix[3] - matrix[0] * matrix[5]) * inverseDeterminant
        output[6] = (matrix[3] * matrix[7] - matrix[4] * matrix[6]) * inverseDeterminant
        output[7] = (matrix[1] * matrix[6] - matrix[0] * matrix[7]) * inverseDeterminant
        output[8] = (matrix[0] * matrix[4] - matrix[1] * matrix[3]) * inverseDeterminant
        for (index in 0 until 9) {
            if (!output[index].isFinite()) return false
        }
        return true
    }

    fun normalizeRows3(matrix: FloatArray) {
        require(matrix.size >= 9)
        for (row in 0 until 3) {
            val base = row * 3
            val sum = matrix[base] + matrix[base + 1] + matrix[base + 2]
            if (abs(sum) > EPSILON) {
                matrix[base] /= sum
                matrix[base + 1] /= sum
                matrix[base + 2] /= sum
            }
        }
    }

    fun diagonal3(x: Float, y: Float, z: Float, output: FloatArray) {
        setIdentity3(output)
        output[0] = x
        output[4] = y
        output[8] = z
    }

    fun interpolateMatrix3(first: FloatArray, second: FloatArray, amount: Float, output: FloatArray) {
        require(first.size >= 9 && second.size >= 9 && output.size >= 9)
        val t = saturateFinite(amount)
        for (index in 0 until 9) output[index] = first[index] + (second[index] - first[index]) * t
    }

    fun propagateDiagonalCovariance3(
        covarianceR: Float,
        covarianceG: Float,
        covarianceB: Float,
        redGain: Float,
        greenGain: Float,
        blueGain: Float,
        colorMatrix: FloatArray,
        output: FloatArray
    ) {
        require(colorMatrix.size >= 9 && output.size >= 9)
        val varianceR = max(covarianceR, 0f) * redGain * redGain
        val varianceG = max(covarianceG, 0f) * greenGain * greenGain
        val varianceB = max(covarianceB, 0f) * blueGain * blueGain
        for (row in 0 until 3) {
            for (column in 0 until 3) {
                val rowBase = row * 3
                val columnBase = column * 3
                output[rowBase + column] =
                    colorMatrix[rowBase] * colorMatrix[columnBase] * varianceR +
                    colorMatrix[rowBase + 1] * colorMatrix[columnBase + 1] * varianceG +
                    colorMatrix[rowBase + 2] * colorMatrix[columnBase + 2] * varianceB
            }
        }
    }

    fun rgbToXyz(red: Float, green: Float, blue: Float, output: FloatArray) {
        require(output.size >= 3)
        output[0] = 0.4124564f * red + 0.3575761f * green + 0.1804375f * blue
        output[1] = 0.2126729f * red + 0.7151522f * green + 0.0721750f * blue
        output[2] = 0.0193339f * red + 0.1191920f * green + 0.9503041f * blue
    }

    fun xyzToXy(x: Float, y: Float, z: Float, output: FloatArray) {
        require(output.size >= 2)
        val sum = x + y + z
        if (!sum.isFinite() || sum <= EPSILON) {
            output[0] = 0.3127f
            output[1] = 0.3290f
        } else {
            output[0] = x / sum
            output[1] = y / sum
        }
    }

    fun xyToUvPrime(x: Float, y: Float, output: FloatArray) {
        require(output.size >= 2)
        val denominator = -2f * x + 12f * y + 3f
        if (!denominator.isFinite() || abs(denominator) <= EPSILON) {
            output[0] = 0.19783f
            output[1] = 0.46832f
        } else {
            output[0] = 4f * x / denominator
            output[1] = 9f * y / denominator
        }
    }

    fun uvPrimeDistance(u0: Float, v0: Float, u1: Float, v1: Float): Float =
        sqrt(square(u0 - u1) + square(v0 - v1))

    /** McCamy is only an initializer; final CCT/Duv uses the Planckian search below. */
    fun approximateCctFromXy(x: Float, y: Float): Float {
        val denominator = 0.1858f - y
        if (abs(denominator) <= EPSILON) return 6_500f
        val n = (x - 0.3320f) / denominator
        return (449f * n * n * n + 3525f * n * n + 6823.3f * n + 5520.33f)
            .coerceIn(1_500f, 25_000f)
    }

    /**
     * Searches a deterministic 1 K Planckian table synthesized from the CIE daylight/blackbody
     * approximation. Output is `[CCT kelvin, signed Duv]`.
     */
    fun estimateCctAndDuv(u: Float, v: Float, output: FloatArray) {
        require(output.size >= 2)
        var bestKelvin = 6_500
        var bestDistanceSquared = Float.POSITIVE_INFINITY
        var bestU = 0f
        var bestV = 0f
        var kelvin = 1_500
        while (kelvin <= 25_000) {
            val x = blackbodyX(kelvin.toFloat())
            val y = blackbodyY(kelvin.toFloat(), x)
            val denominator = -2f * x + 12f * y + 3f
            val locusU = if (abs(denominator) > EPSILON) 4f * x / denominator else 0.19783f
            val locusV = if (abs(denominator) > EPSILON) 9f * y / denominator else 0.46832f
            val distanceSquared = square(u - locusU) + square(v - locusV)
            if (distanceSquared < bestDistanceSquared) {
                bestDistanceSquared = distanceSquared
                bestKelvin = kelvin
                bestU = locusU
                bestV = locusV
            }
            kelvin += if (kelvin < 4_000) 10 else 25
        }
        val lowerKelvin = max(1_500, bestKelvin - 25)
        val upperKelvin = min(25_000, bestKelvin + 25)
        val lowerX = blackbodyX(lowerKelvin.toFloat())
        val lowerY = blackbodyY(lowerKelvin.toFloat(), lowerX)
        val lowerDenominator = -2f * lowerX + 12f * lowerY + 3f
        val lowerU = 4f * lowerX / lowerDenominator
        val lowerV = 9f * lowerY / lowerDenominator
        val upperX = blackbodyX(upperKelvin.toFloat())
        val upperY = blackbodyY(upperKelvin.toFloat(), upperX)
        val upperDenominator = -2f * upperX + 12f * upperY + 3f
        val upperU = 4f * upperX / upperDenominator
        val upperV = 9f * upperY / upperDenominator
        val tangentU = upperU - lowerU
        val tangentV = upperV - lowerV
        val cross = tangentU * (v - bestV) - tangentV * (u - bestU)
        val sign = if (cross >= 0f) 1f else -1f
        output[0] = bestKelvin.toFloat()
        output[1] = sign * sqrt(bestDistanceSquared)
    }

    fun blackbodyXy(kelvinInput: Float, output: FloatArray) {
        require(output.size >= 2)
        val x = blackbodyX(kelvinInput)
        output[0] = x
        output[1] = blackbodyY(kelvinInput, x)
    }

    private fun blackbodyX(kelvinInput: Float): Float {
        val kelvin = kelvinInput.coerceIn(1_500f, 25_000f).toDouble()
        return when {
            kelvin <= 4_000.0 ->
                -0.2661239e9 / kelvin.pow(3.0) - 0.2343580e6 / kelvin.pow(2.0) +
                    0.8776956e3 / kelvin + 0.179910
            else ->
                -3.0258469e9 / kelvin.pow(3.0) + 2.1070379e6 / kelvin.pow(2.0) +
                    0.2226347e3 / kelvin + 0.240390
        }.toFloat()
    }

    private fun blackbodyY(kelvinInput: Float, xInput: Float): Float {
        val kelvin = kelvinInput.coerceIn(1_500f, 25_000f).toDouble()
        val x = xInput.toDouble()
        return when {
            kelvin <= 2_222.0 ->
                -1.1063814 * x.pow(3.0) - 1.34811020 * x.pow(2.0) + 2.18555832 * x - 0.20219683
            kelvin <= 4_000.0 ->
                -0.9549476 * x.pow(3.0) - 1.37418593 * x.pow(2.0) + 2.09137015 * x - 0.16748867
            else ->
                3.0817580 * x.pow(3.0) - 5.87338670 * x.pow(2.0) + 3.75112997 * x - 0.37001483
        }.toFloat()
    }

    fun logChromaDistance(
        redOverGreenA: Float,
        blueOverGreenA: Float,
        redOverGreenB: Float,
        blueOverGreenB: Float
    ): Float {
        val redDelta = safeLog(redOverGreenA) - safeLog(redOverGreenB)
        val blueDelta = safeLog(blueOverGreenA) - safeLog(blueOverGreenB)
        return sqrt(redDelta * redDelta + blueDelta * blueDelta)
    }

    fun angularDistance(firstRadians: Float, secondRadians: Float): Float {
        val delta = atan2(sin(firstRadians - secondRadians), cos(firstRadians - secondRadians))
        return abs(delta)
    }
}
