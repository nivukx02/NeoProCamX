package com.neoproc.neoproccamx.ui.controls

import android.app.Activity
import android.content.Context
import com.neoproc.neoproccamx.core.NdrFeatureGate
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.neoproc.neoproccamx.R
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.ConversionProgress
import com.neoproc.neoproccamx.recording.ConversionProgressListener
import com.neoproc.neoproccamx.recording.Npv10MasterClip
import com.neoproc.neoproccamx.recording.RecordDevelopMasterClip
import com.neoproc.neoproccamx.recording.RecordDevelopMasterKind
import com.neoproc.neoproccamx.recording.RecordingFileNamePolicy
import com.neoproc.neoproccamx.ui.CameraViewModel
import com.neoproc.neoproccamx.ui.BatteryOptimizationExemptionRequester
import com.neoproc.neoproccamx.utils.PreferencesManager
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

private typealias RawClipRef = com.neoproc.neoproccamx.recording.RawClipMediaStoreDiscovery.RawClipRef

internal class SettingsSectionFactory(
    private val context: Context,
    private val viewModel: CameraViewModel,
    private val openLutImporter: () -> Unit,
    private val requestSettingsRebuild: () -> Unit = {}
) {
    // Snapshot of the Settings-sheet generation this factory belongs to (see COMPOSE_EPOCH). Captured at
    // construction — exactly one factory is built per Settings compose — and compared by async
    // converter-card workers before they touch the UI, so only the live sheet is ever populated.
    private val buildEpoch: Long = COMPOSE_EPOCH.incrementAndGet()

    private sealed class DevelopMasterEntry {
        data class Light(val clip: RecordDevelopMasterClip) : DevelopMasterEntry()
        data class Packet(val clip: Npv10MasterClip) : DevelopMasterEntry()
    }

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    fun build(
        key: SettingsSectionKey,
        uiFactory: SettingsUiFactory
    ): SettingsSectionBuildResult =
        when (key) {
            SettingsSectionKey.VIDEO -> buildVideoSection(uiFactory)
            SettingsSectionKey.RAW_CONVERTER -> SettingsSectionBuildResult(buildRawConverterSection(uiFactory))
            SettingsSectionKey.NPV10_CONVERTER -> SettingsSectionBuildResult(buildNpv10ConverterSection(uiFactory))
            SettingsSectionKey.RECORD_DEVELOP_SDR ->
                SettingsSectionBuildResult(buildRecordDevelopConverterSection(uiFactory, RecordDevelopMasterKind.SDR))
            SettingsSectionKey.RECORD_DEVELOP_NDR ->
                SettingsSectionBuildResult(buildRecordDevelopConverterSection(uiFactory, RecordDevelopMasterKind.NDR))
            SettingsSectionKey.CAMERA -> SettingsSectionBuildResult(buildImageSection(uiFactory))
            SettingsSectionKey.VIDEO_COLOR -> SettingsSectionBuildResult(buildColorSection(uiFactory))
            SettingsSectionKey.FOCUS -> SettingsSectionBuildResult(buildFocusSection(uiFactory))
            SettingsSectionKey.SYSTEM -> SettingsSectionBuildResult(buildAdvancedSection(uiFactory))
        }

    private fun buildImageSection(uiFactory: SettingsUiFactory): View {
        val card = uiFactory.sectionCard("CAMERA")
        val currentFacing =
            viewModel.frameGeometry.value?.lensFacing ?: CameraCharacteristics.LENS_FACING_BACK
        val lensChoices = SettingsOptionCatalog.lensChoices(
            hasFrontCamera = viewModel.camera.hasCameraFacing(CameraCharacteristics.LENS_FACING_FRONT)
        )
        card.addView(
            uiFactory.selectorRow(
                title = "Preview Aspect",
                subtitle = "Keep framing in the selected ratio without stretching",
                options = SettingsOptionCatalog.previewAspectChoices,
                selected = viewModel.previewAspect.value,
                onSelect = viewModel::setPreviewAspectRatio
            )
        )

        card.addView(
            uiFactory.selectorRow(
                title = "Lens",
                subtitle = "Switch between rear and front camera without cluttering the preview",
                options = lensChoices,
                selected = currentFacing,
                onSelect = { lensFacing ->
                    if (viewModel.frameGeometry.value?.lensFacing != lensFacing) {
                        viewModel.switchFacing()
                    }
                }
            )
        )
        card.addView(
            uiFactory.selectorRow(
                title = "Flash",
                subtitle = "Photo flash and torch behavior",
                options = SettingsOptionCatalog.flashChoices,
                selected = viewModel.cameraParams.value.flash,
                onSelect = viewModel::setFlash
            )
        )
        if (PreferencesManager.AI_ENHANCEMENT_SETTINGS_ENABLED) {
            card.addView(
                uiFactory.toggleRow(
                    title = "AI Enhancement",
                    subtitle = "Master switch for semantic scene and subject assist",
                    checked = viewModel.aiEnhancementEnabled,
                    onToggle = viewModel::setAiEnhancementEnabled
                )
            )
        }
        card.addView(
            uiFactory.toggleRow(
                title = "AI Motion Compensation",
                subtitle = "Neural optical-flow temporal denoise when hardware support is available",
                checked = viewModel.aiMotionCompensationEnabled,
                onToggle = viewModel::setAiMotionCompensationEnabled
            )
        )
        card.addView(
            uiFactory.toggleRow(
                title = "Noise Reduction",
                subtitle = "High quality sensor-side noise cleanup for photo and preview",
                checked = viewModel.noiseReductionEnabled,
                onToggle = viewModel::setNoiseReductionEnabled
            )
        )

        return card
    }

    private fun buildVideoSection(uiFactory: SettingsUiFactory): SettingsSectionBuildResult {
        val card = uiFactory.sectionCard("RECORD")
        val currentSize = Size(viewModel.gpuRecording.videoWidth, viewModel.gpuRecording.videoHeight)
        val supportedOutputDimensions = viewModel.availableVideoDimensionsForSettings()
        val resolutionChoices = SettingsOptionCatalog.resolutionChoicesForMode(
            mode = viewModel.cinemaVideoMode.value,
            supportedOutputDimensions = supportedOutputDimensions
        )
        val selectedResolution = SettingsOptionCatalog.selectedResolutionOrDefault(
            currentSize = currentSize,
            mode = viewModel.cinemaVideoMode.value,
            supportedOutputDimensions = supportedOutputDimensions
        )

        card.addView(
            uiFactory.selectorRow(
                title = "Resolution",
                subtitle = "Capture format for photo-style video and cinema recording",
                options = resolutionChoices,
                selected = selectedResolution,
                onSelect = { size ->
                    viewModel.setVideoResolution(size)
                    // ELITE — 4K-class recording is capped at 25fps: if a higher rate was selected, clamp it so
                    // the stored cadence matches what the picker (and the HAL) can honor, then rebuild the
                    // sheet so the Frame-Rate options reflect the new resolution live (4K -> up to 25; 2K/
                    // 1080p -> all rates).
                    val cap = SettingsOptionCatalog.maxFpsForResolution(size)
                    if (viewModel.gpuRecording.videoFps > cap) viewModel.setFPS(cap)
                    requestSettingsRebuild()
                }
            )
        )
        // ELITE — Frame-Rate options depend on the SELECTED resolution: 1080p/2K show every supported rate,
        // 4K-class resolutions show 24/25 only, with 30fps intentionally removed from the settings.
        card.addView(
            uiFactory.selectorRow(
                title = "Frame Rate",
                subtitle = "Recording cadence - 25 at 4K, up to 60 at 1080p",
                options = SettingsOptionCatalog.fpsChoicesForResolution(selectedResolution),
                selected = SettingsOptionCatalog.selectedFpsForResolution(
                    viewModel.gpuRecording.videoFps,
                    selectedResolution
                ),
                onSelect = viewModel::setFPS
            )
        )
        card.addView(
            uiFactory.selectorRow(
                title = "Codec",
                subtitle = "HEVC is the single truthful codec path for the 10-bit cinema contract",
                options = SettingsOptionCatalog.videoCodecChoices,
                selected = viewModel.recording.codec,
                onSelect = viewModel::setVideoCodec
            )
        )
        if (NdrFeatureGate.enabled) {
            card.addView(
                uiFactory.selectorRow(
                    title = "NDR Mode",
                    subtitle = "Use Neo Dynamic Range over a hardware 10-bit transfer path",
                    options = SettingsOptionCatalog.videoHdrChoices,
                    selected = viewModel.currentVideoHdrMode,
                    onSelect = viewModel::setVideoHdrMode
                )
            )
        }
        card.addView(
            uiFactory.selectorRow(
                title = "Bitrate Mode",
                subtitle = "Automatic flagship scaling or direct manual override",
                options = SettingsOptionCatalog.bitrateModeChoices,
                selected = viewModel.videoBitrateMode.value,
                onSelect = viewModel::setVideoBitrateMode
            )
        )

        val bitrateCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, uiFactory.dp(10))
        }
        bitrateCard.addView(
            uiFactory.settingTitle(
                title = "Manual Bitrate",
                subtitle = "Target output rate for pro video capture"
            )
        )
        val bitrateValue = TextView(context).apply {
            setTextColor(0xFFF6CD4D.toInt())
            textSize = 18f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(0, uiFactory.dp(8), 0, uiFactory.dp(8))
            text = SettingsRuntimeFormatter.formatBitrateStatusLabel(
                bitrateMode = viewModel.videoBitrateMode.value,
                requestedBitrateBitsPerSecond = viewModel.requestedVideoBitrateBitsPerSecond(),
                appliedBitrateBitsPerSecond = viewModel.reportedVideoBitrateBitsPerSecond()
            )
        }
        bitrateCard.addView(bitrateValue)
        val bitrateSteps = SettingsOptionCatalog.bitrateStepsMbps
        val currentBitrateMbps =
            (viewModel.requestedVideoBitrateBitsPerSecond() / 1_000_000).coerceAtLeast(1)
        val bitrateSeekBar = SeekBar(context).apply {
            max = bitrateSteps.lastIndex
            progress = bitrateSteps.indexOfLast { it <= currentBitrateMbps }.takeIf { it >= 0 } ?: 3
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFFF6CD4D.toInt())
            progressTintList = android.content.res.ColorStateList.valueOf(0xFFF6CD4D.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x33FFFFFF)
            isEnabled = strictHighEndEnabled() ||
                SettingsRuntimeFormatter.isCustomBitrateEnabled(viewModel.videoBitrateMode.value)
            alpha = if (isEnabled) 1f else 0.45f
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val mbps = bitrateSteps[progress.coerceIn(0, bitrateSteps.lastIndex)]
                    if (fromUser) {
                        viewModel.setVideoBitrate(mbps * 1_000_000)
                        bitrateValue.text = SettingsRuntimeFormatter.formatBitrateStatusLabel(
                            bitrateMode = viewModel.videoBitrateMode.value,
                            requestedBitrateBitsPerSecond = viewModel.requestedVideoBitrateBitsPerSecond(),
                            appliedBitrateBitsPerSecond = viewModel.reportedVideoBitrateBitsPerSecond()
                        )
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        bitrateCard.addView(bitrateSeekBar)
        card.addView(bitrateCard)

        if (viewModel.camera.supportsRaw) {
            card.addView(
                uiFactory.toggleRow(
                    title = "Video RAW",
                    subtitle = "Enable Cinema RAW frame-sequence capture for grading-heavy workflows",
                    checked = viewModel.cinemaVideoMode.value == CinemaVideoMode.RAW,
                    onToggle = viewModel::setRawEnabled
                )
            )
        } else {
            card.addView(
                uiFactory.infoRow(
                    title = "Video RAW",
                    value = "UNSUPPORTED"
                )
            )
        }
        card.addView(
            uiFactory.selectorRow(
                title = "Video Format Engine",
                subtitle = "Use the normal processed pipeline or SENSOR RAW (LOG is convert-only)",
                options = SettingsOptionCatalog.cinemaEngineChoices(
                    rawSupported = viewModel.camera.supportsRaw
                ),
                selected = viewModel.cinemaVideoMode.value.takeIf { mode ->
                    // LOG is never a live engine; RAW only in the normal pipeline. Anything
                    // else falls back to PRO so the selector never shows a hidden option.
                    mode == CinemaVideoMode.PRO ||
                        (mode == CinemaVideoMode.RAW &&
                            viewModel.camera.supportsRaw)
                } ?: CinemaVideoMode.PRO,
                onSelect = viewModel::setCinemaVideoMode
            )
        )
        // The RAW converter lives in its own section card. NPV10 conversion remains implemented but is not
        // exposed from Settings.
        if (viewModel.camera.supportsRaw &&
            viewModel.cinemaVideoMode.value == CinemaVideoMode.RAW
        ) {
            card.addView(
                uiFactory.selectorRow(
                    title = "RAW Resolution",
                    subtitle = "AUTO uses maximum sensor resolution (burst) when viable, else full active array",
                    options = SettingsOptionCatalog.rawResolutionModeChoices(),
                    selected = viewModel.rawResolutionMode.value,
                    onSelect = viewModel::setRawResolutionMode
                )
            )
            card.addView(
                uiFactory.infoRow(
                    title = "RAW Tier (active)",
                    value = viewModel.rawCaptureTierStatusLabel(),
                    valueTag = RAW_TIER_INFO_TAG
                )
            )
            card.addView(
                uiFactory.selectorRow(
                    title = "RAW Compression",
                    subtitle = "Cinema RAW stays on the single 12-bit capture path",
                    options = SettingsOptionCatalog.rawCompressionChoicesForMode(CinemaVideoMode.RAW),
                    selected = viewModel.rawVideoCompression.value,
                    onSelect = viewModel::setRawVideoCompressionMode
                )
            )
        }

        return SettingsSectionBuildResult(
            view = card,
            runtimeViews = SettingsRuntimeViews(
                bitrateValue = bitrateValue,
                bitrateSeekBar = bitrateSeekBar
            )
        )
    }

    // ── RAW converter card ──────────────────────────────────────────────────────
    private fun buildRawConverterSection(uiFactory: SettingsUiFactory): View {
        val card = uiFactory.sectionCard("RAW CONVERTER")
        card.addView(
            uiFactory.settingTitle(
                title = "Recorded RAW clips",
                subtitle = "Develop to NeoLog LOG (.MOV), export a CinemaDNG sequence, or delete. The RAW master is kept."
            )
        )
        populateConverterCardAsync(
            card = card,
            uiFactory = uiFactory,
            loadingTitle = "RAW clips",
            emptyRow = { uiFactory.infoRow(title = "RAW clips", value = "NO RAW CLIPS") },
            enumerate = { viewModel.listRawClipsForConversion() }
        ) { clips ->
            val rows = ArrayList<Pair<RawClipRef, SettingsUiFactory.ClipRow>>(clips.size)
            clips.forEach { clip ->
                lateinit var holder: SettingsUiFactory.ClipRow
                val subtitle = String.format(Locale.US, "%.1f MB", clip.streamBytes / (1024f * 1024f))
                holder = uiFactory.clipRow(clip.name, subtitle) { showRawClipActions(uiFactory, clip, holder) }
                card.addView(holder.view)
                rows.add(clip to holder)
            }
            renderThumbnailsAsync(rows.map { it.second }) { index -> viewModel.renderRawClipThumbnailArgb(rows[index].first) }
        }
        return card
    }

    private fun showRawClipActions(
        uiFactory: SettingsUiFactory,
        clip: RawClipRef,
        holder: SettingsUiFactory.ClipRow
    ) {
        uiFactory.actionSheet(
            title = clip.name,
            subtitle = "Choose an action",
            actions = listOf(
                SettingsUiFactory.SettingsActionChoice("Convert to NeoLog LOG (.MOV)") {
                    chooseRawLogLook(uiFactory, clip)
                },
                SettingsUiFactory.SettingsActionChoice("Export as CinemaDNG sequence") {
                    runRawDngAsync(uiFactory, clip)
                },
                SettingsUiFactory.SettingsActionChoice("Delete clip", destructive = true) {
                    confirmDeleteRaw(uiFactory, clip, holder)
                }
            )
        )
    }

    private fun chooseRawLogLook(uiFactory: SettingsUiFactory, clip: RawClipRef) {
        val looks = listOf(
            "N-Log Base" to com.neoproc.neoproccamx.core.NeoLogCurve.Variant.BASE,
            "N-Log Shadow" to com.neoproc.neoproccamx.core.NeoLogCurve.Variant.SHADOW,
            "N-Log Highlight" to com.neoproc.neoproccamx.core.NeoLogCurve.Variant.HIGHLIGHT,
            "N-Log Cinema" to com.neoproc.neoproccamx.core.NeoLogCurve.Variant.CINEMA
        )
        uiFactory.actionSheet(
            title = "Choose LOG look",
            subtitle = "NeoLog variant for this develop",
            actions = looks.map { (label, variant) ->
                SettingsUiFactory.SettingsActionChoice(label) { chooseRawLogFormat(uiFactory, clip, variant) }
            }
        )
    }

    private fun chooseRawLogFormat(
        uiFactory: SettingsUiFactory,
        clip: RawClipRef,
        variant: com.neoproc.neoproccamx.core.NeoLogCurve.Variant
    ) {
        val formats = listOf(
            "4:4:4 master (r210)" to com.neoproc.neoproccamx.recording.RawToNeoLogMovConverter.OutputFormat.MASTER_444,
            "4:2:2 compat (v210)" to com.neoproc.neoproccamx.recording.RawToNeoLogMovConverter.OutputFormat.COMPAT_V210
        )
        uiFactory.actionSheet(
            title = "Choose container",
            subtitle = "r210 = bit-exact master · v210 = wide NLE support, ~1/3 smaller",
            actions = formats.map { (label, format) ->
                SettingsUiFactory.SettingsActionChoice(label) { runRawLogConvertAsync(uiFactory, clip, variant, format) }
            }
        )
    }

    private fun runRawLogConvertAsync(
        uiFactory: SettingsUiFactory,
        clip: RawClipRef,
        variant: com.neoproc.neoproccamx.core.NeoLogCurve.Variant,
        format: com.neoproc.neoproccamx.recording.RawToNeoLogMovConverter.OutputFormat
    ) {
        runConversionWithProgress(
            uiFactory = uiFactory,
            title = "Converting RAW → NeoLog MOV",
            subtitle = clip.name,
            work = { onProgress -> viewModel.convertRawClipToMov(clip, variant, format, onProgress) },
            onSuccess = { name -> Toast.makeText(context, "NeoLog MOV saved to Albums: $name", Toast.LENGTH_LONG).show() },
            onFailure = { t -> Toast.makeText(context, "RAW convert failed: ${t.message ?: "unknown error"}", Toast.LENGTH_LONG).show() }
        )
    }

    private fun runRawDngAsync(uiFactory: SettingsUiFactory, clip: RawClipRef) {
        runConversionWithProgress(
            uiFactory = uiFactory,
            title = "Exporting CinemaDNG sequence",
            subtitle = clip.name,
            work = { onProgress -> viewModel.convertRawClipToCinemaDng(clip, onProgress) },
            onSuccess = { summary -> Toast.makeText(context, "CinemaDNG exported: $summary", Toast.LENGTH_LONG).show() },
            onFailure = { t -> Toast.makeText(context, "CinemaDNG export failed: ${t.message ?: "unknown error"}", Toast.LENGTH_LONG).show() }
        )
    }

    private fun confirmDeleteRaw(
        uiFactory: SettingsUiFactory,
        clip: RawClipRef,
        holder: SettingsUiFactory.ClipRow
    ) {
        uiFactory.actionSheet(
            title = "Delete ${clip.name}?",
            subtitle = "Permanently removes the RAW master (stream + metadata). This cannot be undone.",
            actions = listOf(
                SettingsUiFactory.SettingsActionChoice("Delete permanently", destructive = true) {
                    runDeleteClip(holder) { viewModel.deleteRawClip(clip) }
                },
                SettingsUiFactory.SettingsActionChoice("Cancel") { }
            )
        )
    }

    // ── NPV10 converter card ────────────────────────────────────────────────────
    private fun buildNpv10ConverterSection(uiFactory: SettingsUiFactory): View {
        val card = uiFactory.sectionCard("NPV10 CONVERTER")
        card.addView(
            uiFactory.settingTitle(
                title = "Private NPV10 masters",
                subtitle = "Wrap a 10-bit LOG master into a shareable .MOV, or delete it."
            )
        )
        populateConverterCardAsync(
            card = card,
            uiFactory = uiFactory,
            loadingTitle = "NPV10 masters",
            emptyRow = { uiFactory.infoRow(title = "NPV10 masters", value = "NO PRIVATE MASTERS") },
            enumerate = { viewModel.listNpv10MastersForConversion() }
        ) { masters ->
            val rows = ArrayList<Pair<Npv10MasterClip, SettingsUiFactory.ClipRow>>(masters.size)
            masters.forEach { clip ->
                lateinit var holder: SettingsUiFactory.ClipRow
                val subtitle = String.format(Locale.US, "%.1f MB", clip.bytes / (1024f * 1024f))
                holder = uiFactory.clipRow(clip.file.nameWithoutExtension, subtitle) {
                    showNpv10Actions(uiFactory, clip, holder)
                }
                card.addView(holder.view)
                rows.add(clip to holder)
            }
            renderThumbnailsAsync(rows.map { it.second }) { index -> viewModel.renderNpv10ThumbnailArgb(rows[index].first) }
        }
        return card
    }

    private fun showNpv10Actions(
        uiFactory: SettingsUiFactory,
        clip: Npv10MasterClip,
        holder: SettingsUiFactory.ClipRow
    ) {
        uiFactory.actionSheet(
            title = clip.file.nameWithoutExtension,
            subtitle = "Choose an action",
            actions = listOf(
                SettingsUiFactory.SettingsActionChoice("Convert to MOV") {
                    runNpv10ConvertAsync(uiFactory, clip, holder)
                },
                SettingsUiFactory.SettingsActionChoice("Delete master", destructive = true) {
                    confirmDeleteNpv10(uiFactory, clip, holder)
                }
            )
        )
    }

    private fun runNpv10ConvertAsync(
        uiFactory: SettingsUiFactory,
        clip: Npv10MasterClip,
        holder: SettingsUiFactory.ClipRow
    ) {
        runConversionWithProgress(
            uiFactory = uiFactory,
            title = "Converting NPV10 → MOV",
            subtitle = clip.file.nameWithoutExtension,
            work = { onProgress -> viewModel.convertNpv10MasterToMov(clip, onProgress) },
            onSuccess = { name ->
                removeRow(holder) // the private source master is consumed by the convert
                Toast.makeText(context, "MOV saved to Albums: $name", Toast.LENGTH_LONG).show()
            },
            onFailure = { t ->
                Toast.makeText(context, "NPV10 convert failed: ${t.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
            }
        )
    }

    /**
     * Run a converter [work] on a background thread behind a live [SettingsUiFactory.progressDialog].
     * Progress snapshots are throttled to ~20 Hz before being posted to the UI thread (a multi-thousand
     * frame clip would otherwise flood the main looper), with the terminal frame always delivered. The
     * dialog is dismissed and the success/failure handler runs on the UI thread when [work] returns.
     */
    private fun <R> runConversionWithProgress(
        uiFactory: SettingsUiFactory,
        title: String,
        subtitle: String,
        work: (onProgress: ConversionProgressListener) -> R,
        onSuccess: (R) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        val handler = Handler(Looper.getMainLooper())
        val controller = uiFactory.progressDialog(title, subtitle)
        val lastPostNanos = AtomicLong(0L)
        Thread {
            val result = runCatching {
                viewModel.runWithExclusiveConversionResources {
                    work { progress ->
                        val now = System.nanoTime()
                        val isFinal = progress.isDeterminate && progress.framesDone >= progress.framesTotal
                        if (isFinal || now - lastPostNanos.get() >= PROGRESS_POST_INTERVAL_NANOS) {
                            lastPostNanos.set(now)
                            handler.post { controller.update(progress) }
                        }
                    }
                }
            }
            handler.post {
                controller.dismiss()
                result.fold(onSuccess = onSuccess, onFailure = onFailure)
            }
        }.start()
    }

    private fun confirmDeleteNpv10(
        uiFactory: SettingsUiFactory,
        clip: Npv10MasterClip,
        holder: SettingsUiFactory.ClipRow
    ) {
        uiFactory.actionSheet(
            title = "Delete ${clip.file.nameWithoutExtension}?",
            subtitle = "Permanently removes this private NPV10 master. This cannot be undone.",
            actions = listOf(
                SettingsUiFactory.SettingsActionChoice("Delete permanently", destructive = true) {
                    runDeleteClip(holder) { viewModel.deleteNpv10Master(clip) }
                },
                SettingsUiFactory.SettingsActionChoice("Cancel") { }
            )
        )
    }

    // ── Record-then-develop SDR / NDR develop cards ─────────────────────────────
    // Each card lists the private LIGHT record intermediates of ONE lane (SDR / NDR) and develops a chosen
    // clip through the full elite OFFLINE stack (VideoDevelopColorPipeline + pre-develop denoise/sharpen/
    // temporal) into a graded, uncompressed 10-bit .MOV published to Albums. The colour LOOK is carried by
    // each clip's sidecar (WB / matrix / transfer), so the only per-develop choice is the output container.
    private fun buildRecordDevelopConverterSection(
        uiFactory: SettingsUiFactory,
        kind: RecordDevelopMasterKind
    ): View {
        val laneLabel = kind.name // "SDR" / "NDR"
        val card = uiFactory.sectionCard("$laneLabel DEVELOP")
        card.addView(
            uiFactory.settingTitle(
                title = "Recorded $laneLabel intermediates",
                subtitle = "Develop a custom packed-10 or flat light-record master into a graded 10-bit .MOV, or delete it."
            )
        )
        card.addView(
            uiFactory.infoRow(
                title = "$laneLabel outputs",
                value = "R210 4:4:4 · V210 4:2:2 · HEVC Main10 4:2:0 · HEVC 4:2:2 strict"
            )
        )
        populateConverterCardAsync(
            card = card,
            uiFactory = uiFactory,
            loadingTitle = "$laneLabel intermediates",
            emptyRow = {
                uiFactory.infoRow(title = "$laneLabel intermediates", value = "NO $laneLabel CLIPS")
            },
            enumerate = {
                viewModel.listRecordDevelopMastersForConversion(kind).map(DevelopMasterEntry::Light) +
                    viewModel.listNpv10MastersForConversion(kind).map(DevelopMasterEntry::Packet)
            }
        ) { masters ->
            val rows = ArrayList<Pair<DevelopMasterEntry, SettingsUiFactory.ClipRow>>(masters.size)
            masters.forEach { entry ->
                lateinit var holder: SettingsUiFactory.ClipRow
                val clipFile = when (entry) {
                    is DevelopMasterEntry.Light -> entry.clip.file
                    is DevelopMasterEntry.Packet -> entry.clip.file
                }
                val bytes = when (entry) {
                    is DevelopMasterEntry.Light -> entry.clip.bytes
                    is DevelopMasterEntry.Packet -> entry.clip.bytes
                }
                val subtitle = String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
                holder = uiFactory.clipRow(clipFile.nameWithoutExtension, subtitle) {
                    when (entry) {
                        is DevelopMasterEntry.Light -> showRecordDevelopActions(uiFactory, entry.clip, holder)
                        is DevelopMasterEntry.Packet -> showNpv10Actions(uiFactory, entry.clip, holder)
                    }
                }
                card.addView(holder.view)
                rows.add(entry to holder)
            }
            renderThumbnailsAsync(rows.map { it.second }) { index ->
                when (val entry = rows[index].first) {
                    is DevelopMasterEntry.Light -> viewModel.renderRecordDevelopThumbnailArgb(entry.clip)
                    is DevelopMasterEntry.Packet -> viewModel.renderNpv10ThumbnailArgb(entry.clip)
                }
            }
        }
        return card
    }

    private fun showRecordDevelopActions(
        uiFactory: SettingsUiFactory,
        clip: RecordDevelopMasterClip,
        holder: SettingsUiFactory.ClipRow
    ) {
        uiFactory.actionSheet(
            title = clip.file.nameWithoutExtension,
            subtitle = "Choose an action",
            actions = listOf(
                SettingsUiFactory.SettingsActionChoice("Develop to graded .MOV") {
                    chooseRecordDevelopFormat(uiFactory, clip, holder)
                },
                SettingsUiFactory.SettingsActionChoice("Delete master", destructive = true) {
                    confirmDeleteRecordDevelop(uiFactory, clip, holder)
                }
            )
        )
    }

    private fun chooseRecordDevelopFormat(
        uiFactory: SettingsUiFactory,
        clip: RecordDevelopMasterClip,
        holder: SettingsUiFactory.ClipRow
    ) {
        // R210/V210 stay as uncompressed masters; HEVC Main10 is the playable delivery path.
        val formats = listOf(
            com.neoproc.neoproccamx.recording.RecordDevelopDeliveryFormat.R210_444_UNCOMPRESSED_MOV,
            com.neoproc.neoproccamx.recording.RecordDevelopDeliveryFormat.V210_422_UNCOMPRESSED_MOV,
            com.neoproc.neoproccamx.recording.RecordDevelopDeliveryFormat.HEVC_MAIN10_420_MOV,
            com.neoproc.neoproccamx.recording.RecordDevelopDeliveryFormat.HEVC_MAIN10_422_MOV
        )
        uiFactory.actionSheet(
            title = "Choose container",
            subtitle = "R210/V210 are uncompressed masters · HEVC Main10 4:2:0/4:2:2 are playable delivery choices",
            actions = formats.map { deliveryFormat ->
                val isStrictHevc422 =
                    deliveryFormat ==
                        com.neoproc.neoproccamx.recording.RecordDevelopDeliveryFormat.HEVC_MAIN10_422_MOV
                SettingsUiFactory.SettingsActionChoice(
                    label = deliveryFormat.uiLabel,
                    meta = if (isStrictHevc422) "strict" else ""
                ) {
                    if (isStrictHevc422) {
                        Toast.makeText(
                            context,
                            "HEVC 4:2:2 needs a real 10-bit 4:2:2 encoder path. Use V210 for true 4:2:2.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        runRecordDevelopConvertAsync(uiFactory, clip, deliveryFormat, holder)
                    }
                }
            }
        )
    }

    private fun runRecordDevelopConvertAsync(
        uiFactory: SettingsUiFactory,
        clip: RecordDevelopMasterClip,
        deliveryFormat: com.neoproc.neoproccamx.recording.RecordDevelopDeliveryFormat,
        holder: SettingsUiFactory.ClipRow
    ) {
        runConversionWithProgress(
            uiFactory = uiFactory,
            title = "Developing ${clip.kind.name} → MOV",
            subtitle = clip.file.nameWithoutExtension,
            work = { onProgress -> viewModel.convertRecordDevelopMaster(clip, deliveryFormat, onProgress) },
            onSuccess = { name ->
                Toast.makeText(context, "Developed MOV saved to Albums: $name", Toast.LENGTH_LONG).show()
            },
            onFailure = { t ->
                Toast.makeText(
                    context,
                    "Develop failed: ${t.message ?: "unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    private fun confirmDeleteRecordDevelop(
        uiFactory: SettingsUiFactory,
        clip: RecordDevelopMasterClip,
        holder: SettingsUiFactory.ClipRow
    ) {
        uiFactory.actionSheet(
            title = "Delete ${clip.file.nameWithoutExtension}?",
            subtitle = "Permanently removes this private ${clip.kind.name} light intermediate. This cannot be undone.",
            actions = listOf(
                SettingsUiFactory.SettingsActionChoice("Delete permanently", destructive = true) {
                    runDeleteClip(holder) { viewModel.deleteRecordDevelopMaster(clip) }
                },
                SettingsUiFactory.SettingsActionChoice("Cancel") { }
            )
        )
    }

    // ── shared converter-card helpers ───────────────────────────────────────────
    /**
     * Enumerate a converter card's clips OFF the UI thread, then populate the card back on the main
     * thread. Building the Settings sheet must never block on a cross-process MediaStore scan
     * (listRawClipsForConversion / listMasters) — that is main-thread I/O that can jank or ANR the
     * sheet. The card is returned to the composer immediately carrying a "LOADING…" placeholder; a
     * background Thread runs [enumerate]; the result is posted to the main thread, where the
     * placeholder is swapped for [emptyRow] (empty result) or [populate] (build + attach the rows).
     * The main-thread step is skipped unless this sheet is still the live one ([buildEpoch] equals the
     * current [COMPOSE_EPOCH]), so a rebuilt or dismissed sheet's in-flight scan never mutates a
     * discarded card.
     */
    private fun <T> populateConverterCardAsync(
        card: LinearLayout,
        uiFactory: SettingsUiFactory,
        loadingTitle: String,
        emptyRow: () -> View,
        enumerate: () -> List<T>,
        populate: (List<T>) -> Unit
    ) {
        val epoch = buildEpoch
        val handler = Handler(Looper.getMainLooper())
        val placeholder = uiFactory.infoRow(title = loadingTitle, value = "LOADING…")
        card.addView(placeholder)
        CONVERTER_SCAN_EXECUTOR.execute {
            val result = runCatching { enumerate() }
            handler.post {
                if (epoch != COMPOSE_EPOCH.get()) return@post
                card.removeView(placeholder)
                result.fold(
                    onSuccess = { items ->
                        if (items.isEmpty()) card.addView(emptyRow()) else populate(items)
                    },
                    onFailure = { throwable ->
                        Log.e(TAG, "Converter clip scan failed: $loadingTitle", throwable)
                        card.addView(uiFactory.infoRow(title = loadingTitle, value = "SCAN FAILED - REOPEN SETTINGS"))
                    }
                )
            }
        }
    }

    /** Render each row's thumbnail sequentially on ONE background thread (the develop is heavy). */
    private fun renderThumbnailsAsync(
        holders: List<SettingsUiFactory.ClipRow>,
        render: (Int) -> com.neoproc.neoproccamx.recording.ThumbnailImage?
    ) {
        val epoch = buildEpoch
        val handler = Handler(Looper.getMainLooper())
        THUMBNAIL_EXECUTOR.execute {
            holders.indices.forEach { index ->
                val bmp = bitmapFrom(runCatching { render(index) }.getOrNull()) ?: return@forEach
                handler.post {
                    if (epoch == COMPOSE_EPOCH.get()) {
                        holders[index].thumbnail.setImageBitmap(bmp)
                    } else {
                        bmp.recycle()
                    }
                }
            }
        }
    }

    private fun runDeleteClip(holder: SettingsUiFactory.ClipRow, delete: () -> Boolean) {
        Thread {
            val ok = runCatching { delete() }.getOrDefault(false)
            Handler(Looper.getMainLooper()).post {
                if (ok) removeRow(holder)
                Toast.makeText(context, if (ok) "Deleted" else "Delete failed", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun removeRow(holder: SettingsUiFactory.ClipRow) {
        (holder.view.parent as? ViewGroup)?.removeView(holder.view)
    }

    private fun bitmapFrom(thumb: com.neoproc.neoproccamx.recording.ThumbnailImage?): Bitmap? {
        if (thumb == null) return null
        return runCatching {
            Bitmap.createBitmap(thumb.argb, thumb.width, thumb.height, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }

    private fun buildColorSection(uiFactory: SettingsUiFactory): View {
        val card = uiFactory.sectionCard("VIDEO COLOR")

        card.addView(
            uiFactory.selectorRow(
                title = "Color Space",
                subtitle = "Primary video working space",
                options = SettingsOptionCatalog.videoColorSpaceChoices,
                selected = viewModel.currentVideoColorSpace,
                onSelect = viewModel::setVideoColorSpace
            )
        )
        card.addView(
            uiFactory.selectorRow(
                title = "Color Science",
                subtitle = "Premium video color science profile for cinema capture",
                options = SettingsOptionCatalog.videoColorProfileChoices,
                selected = SettingsOptionCatalog.selectedVideoColorProfile(viewModel.currentColorProfile),
                onSelect = viewModel::setColorProfile
            )
        )
        card.addView(
            uiFactory.selectorRow(
                title = "Detail Profile",
                subtitle = "Focused subject detail or clean natural rendering without added sharpening",
                options = SettingsOptionCatalog.videoDetailProfileChoices,
                selected = viewModel.currentVideoDetailProfile,
                onSelect = viewModel::setVideoDetailProfile
            )
        )
        return card
    }

    private fun buildFocusSection(uiFactory: SettingsUiFactory): View {
        val card = uiFactory.sectionCard("FOCUS")
        card.addView(
            uiFactory.toggleRow(
                title = "Eye Autofocus",
                subtitle = "Track eyes only when this setting is enabled",
                checked = viewModel.eyeAutofocusEnabled,
                onToggle = viewModel::setEyeAutofocusEnabled
            )
        )
        return card
    }

    private fun buildAdvancedSection(uiFactory: SettingsUiFactory): View {
        val card = uiFactory.sectionCard("SYSTEM")
        val audioSelected = SettingsOptionCatalog.selectedAudioSource(viewModel.recording.audioSource)

        card.addView(
            uiFactory.selectorRow(
                title = "Grid Type",
                subtitle = "Preview composition guides",
                options = SettingsOptionCatalog.gridChoices,
                selected = viewModel.gridMode.value,
                onSelect = viewModel::setGridMode
            )
        )
        card.addView(
            uiFactory.toggleRow(
                title = "Framing Guides",
                subtitle = "2.39:1 anamorphic scope guide (follows portrait / landscape)",
                checked = viewModel.isSafeAreaGuidesEnabled,
                onToggle = viewModel::setSafeAreaGuidesEnabled
            )
        )
        card.addView(
            uiFactory.selectorRow(
                title = "Stabilization",
                subtitle = "Optical, electronic, or hybrid motion smoothing",
                options = SettingsOptionCatalog.stabilizationChoices,
                selected = viewModel.stabilizationMode,
                onSelect = viewModel::setStabilizationMode
            )
        )
        card.addView(
            uiFactory.selectorRow(
                title = "Audio",
                subtitle = "Recording source for cinema video clips",
                options = SettingsOptionCatalog.audioChoices,
                selected = audioSelected,
                onSelect = viewModel::setAudioSourceIndex
            )
        )
        card.addView(
            uiFactory.toggleRow(
                title = "Custom Recording Name",
                subtitle = "Name the next video yourself; resets to CamX date after it saves",
                checked = viewModel.customRecordingNameEnabled,
                onToggle = { enabled ->
                    viewModel.setCustomRecordingNameEnabled(enabled)
                    requestSettingsRebuild()
                }
            )
        )
        if (viewModel.customRecordingNameEnabled) {
            val defaultName = RecordingFileNamePolicy.defaultBaseName(Date())
            card.addView(
                uiFactory.textInputRow(
                    title = "Recording Name",
                    subtitle = "Applies to the next recording only",
                    value = viewModel.customRecordingName,
                    placeholder = defaultName,
                    dialogTitle = "Recording Name",
                    dialogSubtitle = "Name the next video. It resets to \"$defaultName\" after it saves.",
                    hint = "e.g. Beach Sunset",
                    maxLength = RecordingFileNamePolicy.MAX_CUSTOM_NAME_LENGTH,
                    onValue = { name ->
                        viewModel.setCustomRecordingName(name)
                        requestSettingsRebuild()
                    }
                )
            )
        }
        card.addView(uiFactory.infoRow("Save Location", "MediaStore library"))
        card.addView(
            uiFactory.actionRow(
                title = "Battery optimization",
                subtitle = "Open Android settings and set NeoProCamX to Unrestricted for background recording",
                actionLabel = "OPEN",
                onClick = {
                    (context as? Activity)?.let(BatteryOptimizationExemptionRequester::requestIfNeeded)
                        ?: Toast.makeText(context, "Open battery optimization settings from the app screen", Toast.LENGTH_LONG).show()
                }
            )
        )
        card.addView(
            uiFactory.infoRow(
                title = "Storage Info",
                value = "Waiting for camera stats...",
                valueTag = STORAGE_INFO_TAG
            )
        )

        return card
    }

    companion object {
        private const val TAG = "SettingsSectionFactory"
        const val STORAGE_INFO_TAG = "storageInfoRowValue"
        const val RAW_TIER_INFO_TAG = "rawTierInfoRowValue"

        // Monotonic Settings-sheet generation. Each compose builds a fresh SettingsSectionFactory that
        // captures the next value into buildEpoch; an async converter-card worker only touches the UI
        // when its captured epoch still equals this counter, so a rebuilt/dismissed sheet's in-flight
        // MediaStore scan can never populate a discarded card.
        private val COMPOSE_EPOCH = AtomicLong(0L)
        private val CONVERTER_SCAN_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "Settings-ConverterScan").apply { isDaemon = true }
        }
        private val THUMBNAIL_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "Settings-Thumbnails").apply { isDaemon = true }
        }

        // Throttle conversion-progress UI posts to ~20 Hz so a multi-thousand-frame clip can't flood
        // the main looper (the terminal frame is always posted regardless).
        private const val PROGRESS_POST_INTERVAL_NANOS = 50_000_000L
    }
}
