package com.neoproc.neoproccamx.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class RawCaptureTierUiState {
    private val mutableStatus = MutableStateFlow("—")
    val status: StateFlow<String> = mutableStatus.asStateFlow()

    fun publish(value: String) {
        mutableStatus.value = value
    }
}

