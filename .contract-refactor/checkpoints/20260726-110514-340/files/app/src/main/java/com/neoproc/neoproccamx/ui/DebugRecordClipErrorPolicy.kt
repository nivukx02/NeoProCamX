package com.neoproc.neoproccamx.ui

import java.util.Locale

internal object DebugRecordClipErrorPolicy {
    fun handle(
        message: String,
        onStrictAdmissionFailure: () -> Unit,
        onRetryable: () -> Unit
    ) {
        if (isStrictHighEndAdmissionFailure(message)) {
            onStrictAdmissionFailure()
        } else {
            onRetryable()
        }
    }

    fun isStrictHighEndAdmissionFailure(message: String): Boolean {
        val normalized = message.lowercase(Locale.US)
        return normalized.contains("strict high-end recording refused") &&
            normalized.contains("without quality downgrade")
    }
}
