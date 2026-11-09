package com.neoproc.neoproccamx.ui.controls

import android.content.Context
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.ui.CameraHapticAction
import com.neoproc.neoproccamx.ui.CameraViewModel

internal class SettingsScreenComposer(
    private val context: Context,
    private val viewModel: CameraViewModel,
    private val openLutImporter: () -> Unit,
    private val hapticPerformer: ((CameraHapticAction) -> Unit)? = null,
    private val requestSettingsRebuild: () -> Unit = {}
) {
    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    fun compose(fullScreen: Boolean): SettingsScreenContent {
        val uiFactory = SettingsUiFactory(context, hapticPerformer)
        val sectionFactory = SettingsSectionFactory(
            context = context,
            viewModel = viewModel,
            openLutImporter = openLutImporter,
            requestSettingsRebuild = requestSettingsRebuild
        )
        val settingsRoot = uiFactory.createRoot(fullScreen)
        val root = settingsRoot.content

        if (!fullScreen) {
            uiFactory.addDialogHeader(
                root = root,
                title = "Settings",
                subtitle = "Separate camera controls and preferences"
            )
        }

        var runtimeViews: SettingsRuntimeViews? = null
        // The converter screen is the user's discovery point for record-develop outputs. Keep the SDR/NDR
        // cards visible even when empty so the supported delivery formats (R210/V210/HEVC Main10) are
        // reflected in the converter tab instead of being hidden until a light intermediate already exists.
        val showRecordDevelopCards = true
        val sectionKeys = if (fullScreen || strictHighEndEnabled()) {
            SettingsSectionResolver.resolveForVideoSettings(viewModel.uiMode.value, showRecordDevelopCards)
        } else {
            SettingsSectionResolver.resolve(viewModel.uiMode.value, showRecordDevelopCards)
        }
        sectionKeys.forEach { sectionKey ->
            val section = sectionFactory.build(sectionKey, uiFactory)
            root.addView(section.view)
            if (runtimeViews == null && section.runtimeViews != null) {
                runtimeViews = section.runtimeViews
            }
        }

        return SettingsScreenContent(
            rootView = settingsRoot.scrollView,
            runtimeViews = requireNotNull(runtimeViews) {
                "Settings screen must provide runtime views for bitrate controls."
            }
        )
    }
}
