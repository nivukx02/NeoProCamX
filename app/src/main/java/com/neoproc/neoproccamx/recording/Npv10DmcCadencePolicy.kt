package com.neoproc.neoproccamx.recording

internal data class Npv10DmcCadenceAdmission(
    val accepted: Boolean,
    val requestedFps: Int,
    val maximumFps: Int,
    val fourKClass: Boolean,
    val failureReason: String = ""
) {
    fun requireAccepted() {
        require(accepted) { failureReason }
    }
}

/** Exact admission policy: it rejects unsupported cadence and never rewrites the user's FPS. */
internal object Npv10DmcCadencePolicy {
    const val FOUR_K_MAX_FPS = 25
    const val LOWER_RESOLUTION_MAX_FPS = 60
    private const val FOUR_K_LONG_EDGE = 3_840

    fun evaluate(width: Int, height: Int, requestedFps: Int): Npv10DmcCadenceAdmission {
        val fourKClass = maxOf(width, height) >= FOUR_K_LONG_EDGE
        val maximum = if (fourKClass) FOUR_K_MAX_FPS else LOWER_RESOLUTION_MAX_FPS
        val accepted = width > 0 && height > 0 && requestedFps in 1..maximum
        val reason = when {
            width <= 0 || height <= 0 ->
                "NPV10 DMC requires positive dimensions; requested=${width}x$height"
            requestedFps <= 0 ->
                "NPV10 DMC requires a positive selected frame rate; requested=$requestedFps"
            requestedFps > maximum ->
                "NPV10 DMC rejects ${width}x$height@$requestedFps: maximum is $maximum FPS"
            else -> ""
        }
        return Npv10DmcCadenceAdmission(
            accepted = accepted,
            requestedFps = requestedFps,
            maximumFps = maximum,
            fourKClass = fourKClass,
            failureReason = reason
        )
    }
}
