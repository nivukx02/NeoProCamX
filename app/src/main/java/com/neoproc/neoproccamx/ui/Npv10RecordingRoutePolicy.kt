package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.SdrConversionPipelinePolicy

/**
 * Single authority for admitting the Camera2 P010 private-master route.
 *
 * Proprietary NDR owns RAW_SENSOR -> ACEScg processing. A Camera2 P010 buffer is vendor-ISP output,
 * so admitting it here would preserve ten-bit storage while silently replacing the selected NDR
 * pixels. NDR therefore remains on the project-owned RAW-derived Main10 path.
 */
internal object Npv10RecordingRoutePolicy {
    fun shouldUseCamera2P010Master(
        uiMode: AppMode,
        cinemaMode: CinemaVideoMode,
        rawEnabled: Boolean,
        logEnabled: Boolean,
        hdrMode: String,
        uncompressedCinemaPipelineEnabled: Boolean,
        sdrConversionPipelineEnabled: Boolean
    ): Boolean =
        uiMode == AppMode.VIDEO &&
            cinemaMode != CinemaVideoMode.RAW &&
            uncompressedCinemaPipelineEnabled &&
            SdrConversionPipelinePolicy.allowsPrivateConversionMaster(
                sdrConversionPipelineEnabled = sdrConversionPipelineEnabled,
                cinemaMode = cinemaMode,
                rawEnabled = rawEnabled,
                logEnabled = logEnabled,
                hdrMode = hdrMode
            ) &&
            !SensorLinearNdrModePolicy.isActive(
                uiMode = uiMode,
                cinemaMode = cinemaMode,
                rawEnabled = rawEnabled,
                logEnabled = logEnabled,
                hdrMode = hdrMode
            )
}
