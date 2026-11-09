package com.neoproc.neoproccamx.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.neoproc.neoproccamx.core.HighEndQualityPolicy

/**
 * Factory for CameraViewModel that provides Application context.
 * Used when custom constructor params are needed.
 */
class CameraViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {
    private fun enforceStrictHighEndDefaults() {
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CameraViewModel::class.java)) {
            enforceStrictHighEndDefaults()
            return CameraViewModel(application).also(RetainedRecordingStopCommands::bind) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
