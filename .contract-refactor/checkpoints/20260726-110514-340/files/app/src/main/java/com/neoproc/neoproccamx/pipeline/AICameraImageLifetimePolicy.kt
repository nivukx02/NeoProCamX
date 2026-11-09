package com.neoproc.neoproccamx.pipeline

object AICameraImageLifetimePolicy {
    fun isClosedImageFailure(throwable: Throwable?): Boolean {
        var current = throwable
        while (current != null) {
            val msg = current.message
            if (msg != null && (
                msg.contains("Image is already closed", ignoreCase = true) ||
                msg.contains("Image is closed", ignoreCase = true) ||
                msg.contains("buffer is inaccessible", ignoreCase = true)
            )) {
                return true
            }
            current = current.cause
        }
        return false
    }
}
