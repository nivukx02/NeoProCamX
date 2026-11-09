package com.neoproc.neoproccamx.ui

import android.os.SystemClock
import android.os.StatFs
import com.neoproc.neoproccamx.BuildConfig
import com.neoproc.neoproccamx.cinema.CinemaResolutionCatalog
import com.neoproc.neoproccamx.pipeline.PipelineHealthMonitor
import com.neoproc.neoproccamx.recording.CinemaRuntimeStats
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.GpuRecordingState
import com.neoproc.neoproccamx.recording.RawCompressionMode
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.CINEMA_RUNTIME_SYSTEM_REFRESH_INTERVAL_MS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Cinema Runtime Stats Engine
//
// Encapsulates the runtime computation of cinema telemetry (FPS,
// bitrate, dropped frames, storage limits, resolution labels).
// Extracted as Sprint 2 of CameraViewModel decomposition.
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

class CinemaRuntimeStatsEngine(
    private val host: CameraViewModelHost,
    private val updateStatsFlow: (CinemaRuntimeStats) -> Unit
) {
    companion object {
        private const val DEBUG_TELEMETRY_PROFILE_TAG = "CinemaRuntimeStats"
        private val DEBUG_TELEMETRY_STAGES = listOf(
            "processFrame",
            "validate",
            "writeFrame",
            "startRecording.prepare",
            "startRecording.total",
            "stopRecording.total"
        )
    }

    private var cachedCinemaRuntimeFreeBytes = 0L
    private var cachedCinemaRuntimeThermalLabel = "Nominal"
    private var lastCinemaRuntimeSystemRefreshElapsedMs = 0L
    @Volatile private var cinemaRuntimeSystemStatsFetchInFlight = false

    private var cachedResolutionStatusLabelKey: ResolutionStatusCacheKey? = null
    private var cachedResolutionStatusLabelValue: String = ""

    fun updateCinemaRuntimeStats(previewFps: Float) {
        val now = SystemClock.elapsedRealtime()
        maybeLogDebugTelemetryProfile(now)
        val targetFps = host.recordingConfig.fps.coerceAtLeast(24)
        val quantizedPreviewFps = if (previewFps > 0f) {
            (previewFps * 2f).roundToInt() / 2f
        } else {
            0f
        }
        val bitrateMbps = currentEstimatedBitrateMbps()
        val stableBitrateMbps = (bitrateMbps * 100f).roundToInt() / 100f

        val shouldRefreshSystemStats =
            cachedCinemaRuntimeFreeBytes <= 0L ||
                now - lastCinemaRuntimeSystemRefreshElapsedMs >= CINEMA_RUNTIME_SYSTEM_REFRESH_INTERVAL_MS
        
        if (shouldRefreshSystemStats) {
            refreshCinemaRuntimeSystemStatsAsync(lastPreviewFpsSample = previewFps)
            return
        }

        val freeBytes = cachedCinemaRuntimeFreeBytes
        val remainingMinutes = if (bitrateMbps > 0f) {
            ((freeBytes * 8f) / (bitrateMbps * 1_000_000f * 60f)).toInt().coerceAtLeast(0)
        } else {
            null
        }
        val rawDrops = host.rawVideoManager.droppedFrames.value
        val muxerDrops = host.gpuRecording.droppedMuxerSamples()
        val previewDropEstimate =
            if (host.gpuRecordState.value is GpuRecordingState.Recording && quantizedPreviewFps > 0f) {
                (targetFps - quantizedPreviewFps).toInt().coerceAtLeast(0)
            } else {
                0
            }
        val dropEstimate = maxOf(rawDrops, previewDropEstimate, muxerDrops)
        val resolutionLabel = buildResolutionStatusLabel()
        val storageLabel = CinemaDisplayUtils.formatStorageLabel(freeBytes, remainingMinutes)
        val thermalLabel = cachedCinemaRuntimeThermalLabel
        val runtimeStats = CinemaRuntimeStats(
            previewFps = quantizedPreviewFps,
            targetFps = targetFps,
            bitrateMbps = stableBitrateMbps,
            dropEstimate = dropEstimate,
            remainingMinutes = remainingMinutes,
            thermalLabel = thermalLabel,
            resolutionLabel = resolutionLabel,
            storageLabel = storageLabel,
            modeLabel = host.cinemaVideoMode.value.name
        )
        
        val currentStats = host.cinemaRuntimeStats.value
        if (currentStats != runtimeStats) {
            updateStatsFlow(runtimeStats)
        }
        host.updateShutterRuleGuidance()
        host.maybeApplyThermalGuard(thermalLabel)
    }

    private fun maybeLogDebugTelemetryProfile(nowElapsedMs: Long) {
        if (!BuildConfig.DEBUG) return
        PipelineHealthMonitor.maybeLogRuntimeTelemetryProfile(
            tag = DEBUG_TELEMETRY_PROFILE_TAG,
            stageNames = DEBUG_TELEMETRY_STAGES,
            minIntervalMs = CINEMA_RUNTIME_SYSTEM_REFRESH_INTERVAL_MS,
            maxStages = 3,
            nowElapsedMs = nowElapsedMs
        )
    }

    private fun refreshCinemaRuntimeSystemStatsAsync(lastPreviewFpsSample: Float) {
        if (cinemaRuntimeSystemStatsFetchInFlight) return
        cinemaRuntimeSystemStatsFetchInFlight = true
        host.viewModelScope.launch(Dispatchers.IO) {
            val refreshedFreeBytes = availableStorageBytes()
            val refreshedThermalLabel = host.currentThermalLabel()
            val refreshedAt = SystemClock.elapsedRealtime()
            
            cachedCinemaRuntimeFreeBytes = refreshedFreeBytes
            cachedCinemaRuntimeThermalLabel = refreshedThermalLabel
            lastCinemaRuntimeSystemRefreshElapsedMs = refreshedAt
            cinemaRuntimeSystemStatsFetchInFlight = false
            
            val targetFps = host.recordingConfig.fps
            val quantizedPreviewFps = if (lastPreviewFpsSample > 0) {
                ((lastPreviewFpsSample + 2f) / 5f).toInt() * 5
            } else 0
            
            val stableBitrateMbps = currentEstimatedBitrateMbps()
            val rawDrops = if (host.cinemaVideoMode.value == CinemaVideoMode.RAW) {
                host.rawVideoManager.droppedFrames.value
            } else 0
            
            val previewDropEstimate = 0
            val muxerDrops = host.gpuRecording.droppedMuxerSamples()
            val dropEstimate = maxOf(rawDrops, previewDropEstimate, muxerDrops)
            
            val resolutionLabel = buildResolutionStatusLabel()
            val remainingMinutes = if (stableBitrateMbps > 0) {
                (refreshedFreeBytes / (stableBitrateMbps * 125_000f) / 60f).toInt()
            } else null
            val storageLabel = CinemaDisplayUtils.formatStorageLabel(refreshedFreeBytes, remainingMinutes)
            
            val runtimeStats = CinemaRuntimeStats(
                previewFps = quantizedPreviewFps.toFloat(),
                targetFps = targetFps,
                bitrateMbps = stableBitrateMbps,
                dropEstimate = dropEstimate,
                remainingMinutes = remainingMinutes,
                thermalLabel = refreshedThermalLabel,
                resolutionLabel = resolutionLabel,
                storageLabel = storageLabel,
                modeLabel = host.cinemaVideoMode.value.name
            )

            withContext(Dispatchers.Main.immediate) {
                if (host.cinemaRuntimeStats.value != runtimeStats) {
                    updateStatsFlow(runtimeStats)
                }
                host.updateShutterRuleGuidance()
                host.maybeApplyThermalGuard(refreshedThermalLabel)
            }
        }
    }

    private fun currentEstimatedBitrateMbps(): Float {
        val encodedMbps = host.recordingConfig.bitrate / 1_000_000f
        if (host.cinemaVideoMode.value != CinemaVideoMode.RAW) return encodedMbps
        val rawSize = host.camera.fullSensorSize()
        val rawMbps = run {
            val compressionFactor = when (host.rawVideoCompression.value) {
                RawCompressionMode.OFF -> 1.0f
                RawCompressionMode.LOSSLESS -> 0.65f
                RawCompressionMode.LOSSLESS_ENHANCED -> 0.50f
                RawCompressionMode.VISUALLY_LOSSLESS -> 0.38f
                RawCompressionMode.HIGH_QUALITY -> 0.28f
                RawCompressionMode.HIGH_COMPRESSION -> 0.75f
                RawCompressionMode.NEO_SPECTRAL_RAW -> 0.40f
                RawCompressionMode.NEO_PURE_RAW -> 0.32f
                RawCompressionMode.NEO_FINE_RAW -> 0.30f
                RawCompressionMode.NEO_DENSE_RAW -> 0.25f
                RawCompressionMode.LOSSLESS_REALTIME -> 1.0f
            }
            (rawSize.width.toLong() * rawSize.height.toLong() * 2L * host.recordingConfig.fps * 8L / 1_000_000f) *
                compressionFactor
        }
        return encodedMbps + rawMbps
    }

    private fun availableStorageBytes(): Long {
        val baseDir = host.application.getExternalFilesDir(null)
            ?: host.application.filesDir
        return try {
            StatFs(baseDir.absolutePath).availableBytes
        } catch (t: Throwable) {
            host.handleCameraError(
                throwable = t,
                operation = "availableStorageBytes",
                userVisibleMessage = "Storage stats are temporarily unavailable."
            )
            0L
        }
    }

    private fun buildResolutionStatusLabel(): String {
        val selection = host.lastRecordingSelection
        val requestedSize = selection?.requestedSize ?: host.selectedVideoSize ?: host.prefs.videoSize
        val preparedWidth = host.gpuRecording.videoWidth
        val preparedHeight = host.gpuRecording.videoHeight
        val sourceResolutionPlan = host.resolveRecordingSourcePlan(requestedSize)
        val cacheKey = ResolutionStatusCacheKey(
            uiMode = host.uiMode.value,
            cinemaMode = host.cinemaVideoMode.value,
            requestedWidth = requestedSize.width,
            requestedHeight = requestedSize.height,
            preparedWidth = preparedWidth,
            preparedHeight = preparedHeight,
            sourceWidth = selection?.sourceSize?.width ?: -1,
            sourceHeight = selection?.sourceSize?.height ?: -1,
            upscaledToRequested = selection?.upscaledToRequested == true,
            cineSourceWidth = sourceResolutionPlan.cineSourceSize.width,
            cineSourceHeight = sourceResolutionPlan.cineSourceSize.height
        )
        if (cachedResolutionStatusLabelKey == cacheKey) {
            return cachedResolutionStatusLabelValue
        }
        val requestedLabel = "${requestedSize.width}x${requestedSize.height}"
        val preparedSizeLabel = "${preparedWidth}x${preparedHeight}"
        val preparedPreset = CinemaResolutionCatalog.forResolution(preparedWidth, preparedHeight)
        val preparedLabel = if (preparedPreset != null) {
            "${preparedPreset.deliveryFormat} ($preparedSizeLabel)"
        } else {
            preparedSizeLabel
        }
        val encodedResolutionLabel = when {
            selection?.upscaledToRequested == true ->
                "$preparedLabel (upscaled from ${selection.sourceSize.width}x${selection.sourceSize.height})"
            requestedLabel == preparedSizeLabel ->
                preparedLabel
            else -> "$preparedLabel from $requestedLabel"
        }
        val sourceLabel = if (sourceResolutionPlan.fullSensorReadoutForced) {
            "Sensor ${CinemaDisplayUtils.megapixelLabel(sourceResolutionPlan.fullSensorMegapixels)} ${sourceResolutionPlan.deliveryFormat}"
        } else {
            "Cine Source ${CinemaDisplayUtils.megapixelLabel(sourceResolutionPlan.cineSourceMegapixels)} ${sourceResolutionPlan.deliveryFormat}"
        }
        val resolvedLabel = buildString {
            append(encodedResolutionLabel)
            append(" | ")
            append(sourceLabel)
        }
        cachedResolutionStatusLabelKey = cacheKey
        cachedResolutionStatusLabelValue = resolvedLabel
        return resolvedLabel
    }
}
