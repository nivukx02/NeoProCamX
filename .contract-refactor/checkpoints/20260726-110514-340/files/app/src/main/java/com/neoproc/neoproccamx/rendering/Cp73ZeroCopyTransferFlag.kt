package com.neoproc.neoproccamx.rendering

/**
 * s7-f11 / CP73 — process-global gate for the dedicated-transfer-queue zero-copy NPV10-export path.
 *
 * DEFAULT REQUESTED ON. The native bridge DETECTS a dedicated transfer queue family at device init
 * (read-only, no behavior change) and reports it via the `dedicatedTransferQueue` capability token.
 * The actual zero-copy transfer (dedicated-queue uploads/downloads + queue-family ownership barriers
 * + removal of the per-frame export memcpy) remains DEVICE-GATED: [Cp73TransferQueuePolicy] still
 * requires real native capability evidence before the path is ready, and unsupported devices fall
 * back to the staged single-queue export path.
 */
internal object Cp73ZeroCopyTransferFlag {
    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
