package com.neoproc.neoproccamx.camera

/**
 * Keeps a full-rate P010 recording session inside the Camera2 stream budget.
 *
 * Device cadence evidence showed that a full-resolution JPEG output configured beside P010 caused
 * periodic sensor-timestamp gaps even though application queues and storage stayed below deadline.
 * The JPEG reader remains allocated, and idle still capture temporarily enters its own full-quality
 * session. Only the unused JPEG output is detached while the P010 session owns the sensor.
 */
object P010CadenceSafeSessionFlag {

    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
