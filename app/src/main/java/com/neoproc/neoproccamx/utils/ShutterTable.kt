package com.neoproc.neoproccamx.utils

import com.neoproc.neoproccamx.core.HighEndQualityPolicy

/**
 * ShutterTable — Standard cinema shutter speeds (1/8000 to 30s).
 * All times stored as nanoseconds for Camera2 SENSOR_EXPOSURE_TIME.
 */
object ShutterTable {
    private const val NS_PER_SECOND = 1_000_000_000L
    private const val DEFAULT_FPS = 24

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private fun normalizeFpsForMode(fps: Int): Int {
        val safeFps = if (fps > 0) fps else DEFAULT_FPS
        return if (strictHighEndEnabled()) {
            HighEndQualityPolicy.snapToCinemaFps(safeFps)
        } else {
            safeFps
        }
    }

    data class Entry(val label: String, val ns: Long)

    val entries: List<Entry> = listOf(
        Entry("1/8000",    125_000L),
        Entry("1/6400",    156_250L),
        Entry("1/5000",    200_000L),
        Entry("1/4000",    250_000L),
        Entry("1/3200",    312_500L),
        Entry("1/2500",    400_000L),
        Entry("1/2000",    500_000L),
        Entry("1/1600",    625_000L),
        Entry("1/1250",    800_000L),
        Entry("1/1000",  1_000_000L),
        Entry("1/800",   1_250_000L),
        Entry("1/640",   1_562_500L),
        Entry("1/500",   2_000_000L),
        Entry("1/400",   2_500_000L),
        Entry("1/320",   3_125_000L),
        Entry("1/250",   4_000_000L),
        Entry("1/200",   5_000_000L),
        Entry("1/160",   6_250_000L),
        Entry("1/125",   8_000_000L),
        Entry("1/100",  10_000_000L),
        Entry("1/80",   12_500_000L),
        Entry("1/60",   16_666_667L),
        Entry("1/50",   20_000_000L),
        Entry("1/40",   25_000_000L),
        Entry("1/30",   33_333_333L),
        Entry("1/25",   40_000_000L),
        Entry("1/20",   50_000_000L),
        Entry("1/15",   66_666_667L),
        Entry("1/10",  100_000_000L),
        Entry("1/8",   125_000_000L),
        Entry("1/6",   166_666_667L),
        Entry("1/5",   200_000_000L),
        Entry("1/4",   250_000_000L),
        Entry("1/3",   333_333_333L),
        Entry("1/2",   500_000_000L),
        Entry("0.6s",  600_000_000L),
        Entry("0.8s",  800_000_000L),
        Entry("1s",  1_000_000_000L),
        Entry("1.3s",1_300_000_000L),
        Entry("1.6s",1_600_000_000L),
        Entry("2s",  2_000_000_000L),
        Entry("2.5s",2_500_000_000L),
        Entry("3s",  3_000_000_000L),
        Entry("4s",  4_000_000_000L),
        Entry("5s",  5_000_000_000L),
        Entry("6s",  6_000_000_000L),
        Entry("8s",  8_000_000_000L),
        Entry("10s",10_000_000_000L),
        Entry("15s",15_000_000_000L),
        Entry("20s",20_000_000_000L),
        Entry("25s",25_000_000_000L),
        Entry("30s",30_000_000_000L)
    )

    fun nearest(ns: Long): Entry =
        entries.minByOrNull { distance(it.ns, ns) } ?: entries.first()

    fun for180Rule(fps: Int): Entry {
        val safeFps = normalizeFpsForMode(fps)
        return nearest(NS_PER_SECOND / (safeFps * 2L))
    }

    fun forShutterAngle(fps: Int, angleDegrees: Float): Entry {
        val safeFps = normalizeFpsForMode(fps)
        val safeAngle = angleDegrees.takeIf { it.isFinite() }?.coerceIn(1f, 360f) ?: 180f
        val targetNs = (safeAngle / 360f * (NS_PER_SECOND.toFloat() / safeFps.toFloat())).toLong()
        return nearest(targetNs.coerceAtLeast(1L))
    }

    private fun distance(lhs: Long, rhs: Long): Long {
        return if (lhs >= rhs) lhs - rhs else rhs - lhs
    }
}
