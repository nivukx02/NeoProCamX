package com.neoproc.neoproccamx.ui

internal object Npv10CapturePrearmPolicy {
    fun shouldRefresh(
        requestedSignature: String,
        preparedSignature: String?,
        inFlightSignature: String?,
        sessionConfigured: Boolean,
        forceRefresh: Boolean
    ): Boolean {
        if (inFlightSignature == requestedSignature) return false
        if (preparedSignature == requestedSignature && sessionConfigured) return false
        return forceRefresh || preparedSignature != requestedSignature || !sessionConfigured
    }
}
