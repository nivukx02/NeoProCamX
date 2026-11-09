package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.rendering.FilmPrintLookRecordFlag
import com.neoproc.neoproccamx.rendering.FullColorEngineRecordFlag
import com.neoproc.neoproccamx.rendering.GlVisiblePreviewPolicy
import com.neoproc.neoproccamx.rendering.Hdr10PqPreviewPolicy
import com.neoproc.neoproccamx.rendering.Hdr10PqPreviewSdrOutputFlag
import com.neoproc.neoproccamx.rendering.PreviewColorParityFlag
import com.neoproc.neoproccamx.rendering.PreviewGradeWhileRecordingFlag
import com.neoproc.neoproccamx.rendering.RawDebayerPreviewOrientationParityFlag
import com.neoproc.neoproccamx.rendering.RecordDevelopModeFlag
import com.neoproc.neoproccamx.snce.SnceColorEngineFlag

internal object CameraViewModelGlobalCallbacks {
    fun clear() {
        SnceColorEngineFlag.registerOnChanged(null)
        GlVisiblePreviewPolicy.registerOnChanged(null)
        Hdr10PqPreviewPolicy.registerOnChanged(null)
        Hdr10PqPreviewSdrOutputFlag.registerOnChanged(null)
        FilmPrintLookRecordFlag.registerOnChanged(null)
        RecordDevelopModeFlag.registerOnChanged(null)
        PreviewGradeWhileRecordingFlag.registerOnChanged(null)
        FullColorEngineRecordFlag.registerOnChanged(null)
        PreviewColorParityFlag.registerOnChanged(null)
        RawDebayerPreviewOrientationParityFlag.registerOnChanged(null)
    }
}
