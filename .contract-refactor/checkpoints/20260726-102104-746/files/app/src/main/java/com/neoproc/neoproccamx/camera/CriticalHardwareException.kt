package com.neoproc.neoproccamx.camera

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * CRITICAL HARDWARE EXCEPTION
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * Thrown when Supreme Fidelity mode requires hardware capabilities that the device
 * does not possess. Examples:
 *
 * - 10-bit color output (Rec.2020)
 * - RAW_SENSOR / 12-bit RAW capture
 * - Full sensor pixel readout
 * - Uncompressed video recording at 2.5+ Gbps
 *
 * This is a FATAL error — fallback to 8-bit or standard mode is PROHIBITED.
 * The application must fail loudly and clearly notify the user.
 *
 * Thread safety: Immutable exception, safe for concurrent use.
 */
class CriticalHardwareException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause) {
    
    companion object {
        private const val serialVersionUID = 1L
    }
}
