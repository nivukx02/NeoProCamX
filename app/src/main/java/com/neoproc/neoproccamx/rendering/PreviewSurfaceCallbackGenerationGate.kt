package com.neoproc.neoproccamx.rendering

/**
 * Orders SurfaceHolder notifications that may be delivered through a posted callback. A destroy
 * notification is intentionally posted by the view so Camera2 teardown does not run inside the
 * SurfaceHolder callback. If Android creates a replacement surface before that post executes, the
 * generation carried by the destroy event is stale and must not invalidate the replacement.
 */
internal data class PreviewSurfaceCallbackEvent<S : Any>(
    val generation: Long,
    val surface: S?
)

internal class PreviewSurfaceCallbackGenerationGate<S : Any> {
    private val lock = Any()
    private var generation = 0L
    private var latest: PreviewSurfaceCallbackEvent<S>? = null

    fun publish(surface: S?): PreviewSurfaceCallbackEvent<S> = synchronized(lock) {
        PreviewSurfaceCallbackEvent(
            generation = ++generation,
            surface = surface
        ).also { latest = it }
    }

    fun snapshot(): PreviewSurfaceCallbackEvent<S>? = synchronized(lock) { latest }

    fun isCurrent(event: PreviewSurfaceCallbackEvent<S>): Boolean = synchronized(lock) {
        val current = latest
        current?.generation == event.generation && current.surface === event.surface
    }
}
