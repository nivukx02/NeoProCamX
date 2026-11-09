package com.neoproc.neoproccamx.ai

import kotlin.math.sqrt

/**
 * AF-SD (F09) — pure, stateless face-cluster detector for GROUP framing. RectF-mock-safe: every
 * value is derived from the raw left/top/right/bottom floats (never RectF.centerX()/width(), which
 * return 0 under the mockable android.jar).
 */
internal data class FaceBoundsLite(
    val trackingId: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float
) {
    fun cx(): Float = (left + right) * 0.5f
    fun cy(): Float = (top + bottom) * 0.5f
    fun w(): Float = right - left
    fun h(): Float = bottom - top
}

internal data class GroupCluster(
    val memberIds: List<Int>,
    val unionLeft: Float,
    val unionTop: Float,
    val unionRight: Float,
    val unionBottom: Float,
    val centroidX: Float,
    val centroidY: Float,
    val meanConfidence: Float,
    val memberCount: Int
)

internal object GroupClusterPolicy {
    const val MIN_MEMBERS = 2

    /** Ignore weak detections when forming a cluster. */
    const val MIN_FACE_CONFIDENCE = 0.30f

    /**
     * Two faces are "near" when their centre distance is within this multiple of the mean of their
     * four box dimensions (touching side-by-side faces are ~1.0x apart; this allows a moderate gap).
     */
    const val NEAR_MULTIPLE = 1.8f

    /** If the strongest face dominates the runner-up by this ratio, there is one clear subject — no group. */
    const val DOMINANCE_RATIO = 1.6f

    /**
     * @return the connected cluster grown from the strongest face (>= [MIN_MEMBERS] members), or null
     *         when there are too few strong faces, one face dominates, or no two faces are near.
     */
    fun detectCluster(faces: List<FaceBoundsLite>): GroupCluster? {
        val strong = faces.filter { it.confidence >= MIN_FACE_CONFIDENCE && it.w() > 0f && it.h() > 0f }
        if (strong.size < MIN_MEMBERS) return null

        val sorted = strong.sortedByDescending { it.confidence }
        // Dominance guard: a clearly-strongest face means the user wants that face, not a group.
        if (sorted.size >= 2 && sorted[0].confidence >= sorted[1].confidence * DOMINANCE_RATIO) return null

        // Grow a connected component from the strongest face.
        val members = ArrayList<FaceBoundsLite>()
        members.add(sorted[0])
        val remaining = ArrayList(sorted.drop(1))
        var changed = true
        while (changed) {
            changed = false
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                if (members.any { near(it, candidate) }) {
                    members.add(candidate)
                    iterator.remove()
                    changed = true
                }
            }
        }
        if (members.size < MIN_MEMBERS) return null

        var minLeft = Float.MAX_VALUE
        var minTop = Float.MAX_VALUE
        var maxRight = -Float.MAX_VALUE
        var maxBottom = -Float.MAX_VALUE
        var sumCx = 0f
        var sumCy = 0f
        var sumConf = 0f
        for (m in members) {
            if (m.left < minLeft) minLeft = m.left
            if (m.top < minTop) minTop = m.top
            if (m.right > maxRight) maxRight = m.right
            if (m.bottom > maxBottom) maxBottom = m.bottom
            sumCx += m.cx()
            sumCy += m.cy()
            sumConf += m.confidence
        }
        val n = members.size
        return GroupCluster(
            memberIds = members.map { it.trackingId },
            unionLeft = minLeft.coerceIn(0f, 1f),
            unionTop = minTop.coerceIn(0f, 1f),
            unionRight = maxRight.coerceIn(0f, 1f),
            unionBottom = maxBottom.coerceIn(0f, 1f),
            centroidX = (sumCx / n).coerceIn(0f, 1f),
            centroidY = (sumCy / n).coerceIn(0f, 1f),
            meanConfidence = (sumConf / n).coerceIn(0f, 1f),
            memberCount = n
        )
    }

    private fun near(a: FaceBoundsLite, b: FaceBoundsLite): Boolean {
        val dx = a.cx() - b.cx()
        val dy = a.cy() - b.cy()
        val dist = sqrt(dx * dx + dy * dy)
        val avgExtent = (a.w() + a.h() + b.w() + b.h()) * 0.25f
        return dist <= avgExtent * NEAR_MULTIPLE
    }
}
