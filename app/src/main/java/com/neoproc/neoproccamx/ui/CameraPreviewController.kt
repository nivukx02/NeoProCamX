package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.camera.CameraController
import com.neoproc.neoproccamx.utils.PreferencesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PreviewDomainState(
    val aspect: PreviewAspectRatio = PreviewAspectRatio.FOUR_THREE,
    val width: Int = 0,
    val height: Int = 0
)

internal class CameraPreviewController(
    private val cameraController: CameraController,
    private val prefs: PreferencesManager
) {
    private val _domainState = MutableStateFlow(
        PreviewDomainState(aspect = prefs.previewAspectRatio)
    )
    val domainState: StateFlow<PreviewDomainState> = _domainState.asStateFlow()

    fun setPreviewAspectRatio(aspectRatio: PreviewAspectRatio) {
        prefs.previewAspectRatio = aspectRatio
        _domainState.update { it.copy(aspect = aspectRatio) }
        cameraController.setPreviewViewport(
            width = _domainState.value.width,
            height = _domainState.value.height,
            contentAspectRatio = aspectRatio.outputRatio()
        )
    }

    fun onViewportChanged(width: Int, height: Int) {
        val safeWidth = width.coerceAtLeast(0)
        val safeHeight = height.coerceAtLeast(0)
        _domainState.update { it.copy(width = safeWidth, height = safeHeight) }
        cameraController.setPreviewViewport(
            width = safeWidth,
            height = safeHeight,
            contentAspectRatio = _domainState.value.aspect.outputRatio()
        )
    }

    fun previewSourceAspect(viewWidth: Int, viewHeight: Int): Float? {
        val aspect = _domainState.value.aspect.outputRatio()
        if (aspect != null) return aspect
        if (viewWidth <= 0 || viewHeight <= 0) return null
        return viewWidth.toFloat() / viewHeight.toFloat()
    }

    fun syncFromLegacy(aspect: PreviewAspectRatio, width: Int = _domainState.value.width, height: Int = _domainState.value.height) {
        _domainState.update {
            it.copy(
                aspect = aspect,
                width = width.coerceAtLeast(0),
                height = height.coerceAtLeast(0)
            )
        }
    }
}
