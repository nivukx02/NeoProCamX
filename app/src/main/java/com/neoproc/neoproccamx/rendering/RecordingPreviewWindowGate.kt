package com.neoproc.neoproccamx.rendering

/**
 * Owns only the visible EGL window, never Camera2 inputs or the recording render-profile epoch.
 * A requested detach closes presentation immediately; GL work completes on the render thread.
 * Tickets prevent a late detach/attach completion from reviving a superseded Android Surface.
 */
internal class RecordingPreviewWindowGate {
    class Ticket internal constructor(val generation: Long, val attach: Boolean)

    private var generation = 0L
    private var active = false
    private var attachRequested = false
    private var visible = false
    private var presentationOccluded = false

    @Synchronized
    fun startVisible() {
        generation += 1L
        active = true
        attachRequested = true
        visible = true
        presentationOccluded = false
    }

    @Synchronized
    fun requestDetach(): Ticket? {
        if (!active) return null
        generation += 1L
        attachRequested = false
        visible = false
        return Ticket(generation, attach = false)
    }

    @Synchronized
    fun requestAttach(): Ticket? {
        if (!active) return null
        generation += 1L
        attachRequested = true
        visible = false
        return Ticket(generation, attach = true)
    }

    @Synchronized
    fun isCurrent(ticket: Ticket): Boolean = active &&
        ticket.generation == generation && ticket.attach == attachRequested

    @Synchronized
    fun commitAttached(ticket: Ticket): Boolean {
        if (!ticket.attach || !isCurrent(ticket)) return false
        visible = true
        return true
    }

    @Synchronized
    fun mayPresent(): Boolean = active && visible && !presentationOccluded

    /**
     * Notification shade/other focused system windows do not necessarily pause the Activity.
     * Stop only visible swaps while continuing to consume both private child inputs. This is not
     * a window detach: do not revoke attachment tickets, destroy the EGL surface or rebuild Camera2.
     */
    @Synchronized
    fun setPresentationOccluded(occluded: Boolean): Boolean {
        if (!active || presentationOccluded == occluded) return false
        presentationOccluded = occluded
        return true
    }

    @Synchronized
    fun isWindowSuspended(): Boolean = active && !visible

    @Synchronized
    fun retire() {
        generation += 1L
        active = false
        attachRequested = false
        visible = false
        presentationOccluded = false
    }
}
