package com.neoproc.neoproccamx.camera

/** Immutable AI-focus publication so distance, confidence, and session lineage cannot tear. */
internal data class AiFocusControlSignal(
    val distanceDiopters: Float,
    val confidence: Float,
    val sessionGeneration: Long
) {
    fun isCurrent(generation: Long): Boolean =
        sessionGeneration == generation &&
            distanceDiopters.isFinite() && distanceDiopters >= 0f &&
            confidence.isFinite() && confidence > 0f

    companion object {
        val NONE = AiFocusControlSignal(
            distanceDiopters = -1f,
            confidence = 0f,
            sessionGeneration = -1L
        )

        fun create(
            distanceDiopters: Float,
            confidence: Float,
            sessionGeneration: Long
        ): AiFocusControlSignal = if (
            distanceDiopters.isFinite() && distanceDiopters >= 0f &&
            confidence.isFinite() && confidence > 0f &&
            sessionGeneration >= 0L
        ) {
            AiFocusControlSignal(
                distanceDiopters = distanceDiopters,
                confidence = confidence.coerceIn(0f, 1f),
                sessionGeneration = sessionGeneration
            )
        } else {
            NONE
        }
    }
}
