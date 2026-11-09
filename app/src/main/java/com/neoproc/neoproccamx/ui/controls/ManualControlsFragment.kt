package com.neoproc.neoproccamx.ui.controls

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.util.Range
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.neoproc.neoproccamx.R
import com.neoproc.neoproccamx.camera.CameraLongRange
import com.neoproc.neoproccamx.camera.LiveResult
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.ui.CameraHapticAction
import com.neoproc.neoproccamx.ui.CameraHapticPerformer
import com.neoproc.neoproccamx.ui.CameraManualControlStateSyncPolicy
import com.neoproc.neoproccamx.ui.CameraViewModel
import com.neoproc.neoproccamx.ui.MainActivity
import com.neoproc.neoproccamx.ui.ManualControlValue
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

private const val LIVE_TELEMETRY_SAMPLE_MS = 100L

@SuppressLint("SetTextI18n", "DefaultLocale")
class ManualControlsFragment : Fragment() {

    private val vm: CameraViewModel get() = (requireActivity() as MainActivity).vm

    private lateinit var controlStack: View
    private lateinit var tabISO: Button
    private lateinit var tabSS: Button
    private lateinit var tabWB: Button
    private lateinit var tabMF: Button
    private lateinit var tabEV: Button
    private lateinit var tabAFMode: Button
    private lateinit var tabMeteringMode: Button
    private lateinit var tabRawToggle: Button

    private lateinit var panelISO: View
    private lateinit var panelSS: View
    private lateinit var panelWB: View
    private lateinit var panelMF: View
    private lateinit var panelEV: View

    private lateinit var sbISO: SeekBar
    private lateinit var tvISO: TextView
    private lateinit var sbSS: SeekBar
    private lateinit var tvSS: TextView
    private lateinit var sbWB: SeekBar
    private lateinit var tvWB: TextView
    private lateinit var sbMF: SeekBar
    private lateinit var tvMF: TextView
    private lateinit var sbEV: SeekBar
    private lateinit var tvEV: TextView
    private lateinit var tvIsoMode: TextView
    private lateinit var tvSsMode: TextView
    private lateinit var tvWbMode: TextView
    private lateinit var tvMfMode: TextView
    private lateinit var tvEvMode: TextView
    private lateinit var tvMetricProfile: TextView
    private lateinit var tvMetricFocus: TextView
    private var evDisplayMin = -4
    private var evDisplayMax = 4
    private var evDisplayStepStops = 1f
    private var supportedIsoValues: List<Int> = emptyList()
    private var supportedIsoRange: Range<Int>? = null
    private var supportedShutterEntries: List<CinemaShutterAngleEntry> = emptyList()
    private var supportedShutterExposureRange: Range<Long>? = null
    private var supportedShutterFps: Int? = null
    private var supportedWhiteBalanceRange = CinemaManualControlPolicy.whiteBalanceRange()
    private var supportedWhiteBalanceHardwareRange: Range<Int>? = null
    private var latestLiveResult: LiveResult? = null

    private val isoUpdates = MutableStateFlow<Int?>(null)
    private val shutterUpdates = MutableStateFlow<Long?>(null)
    private val wbUpdates = MutableStateFlow<Int?>(null)
    private val focusUpdates = MutableStateFlow<Float?>(null)
    private val evUpdates = MutableStateFlow<Int?>(null)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        return inflater.inflate(R.layout.fragment_manual_controls, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        installSeekBarHaptics()
        bindListeners()
        bindReactiveManualUpdates()
        observeLive()
        hideAdjustmentPanel()
        renderCurrentControlState()
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        // FragmentManager restores child visibility/selection after onViewCreated. Reassert the Pro
        // entry contract here so an old open ruler can never reappear after process recreation.
        resetForProEntry()
    }

    fun resetForProEntry() {
        if (view == null) return
        hideAdjustmentPanel()
        renderCurrentControlState()
    }

    private fun bindViews(root: View) {
        // Capability caches describe the currently bound widgets. A Fragment view can be recreated
        // while this Fragment instance survives, so force the new SeekBars to receive their maxima.
        supportedIsoRange = null
        supportedShutterExposureRange = null
        supportedShutterFps = null
        supportedWhiteBalanceHardwareRange = null

        controlStack = root.findViewById(R.id.controlStack)
        tabISO = root.findViewById(R.id.tabISO)
        tabSS = root.findViewById(R.id.tabSS)
        tabWB = root.findViewById(R.id.tabWB)
        tabMF = root.findViewById(R.id.tabMF)
        tabEV = root.findViewById(R.id.tabEV)
        tabAFMode = root.findViewById(R.id.tabAFMode)
        tabMeteringMode = root.findViewById(R.id.tabMeteringMode)
        tabRawToggle = root.findViewById(R.id.tabRawToggle)

        panelISO = root.findViewById(R.id.sectionISO)
        panelSS = root.findViewById(R.id.sectionSS)
        panelWB = root.findViewById(R.id.sectionWB)
        panelMF = root.findViewById(R.id.sectionMF)
        panelEV = root.findViewById(R.id.sectionEV)

        sbISO = root.findViewById(R.id.sbISO)
        tvISO = root.findViewById(R.id.tvISO)
        sbSS = root.findViewById(R.id.sbSS)
        tvSS = root.findViewById(R.id.tvSS)
        sbWB = root.findViewById(R.id.sbWB)
        tvWB = root.findViewById(R.id.tvWB)
        sbMF = root.findViewById(R.id.sbMF)
        tvMF = root.findViewById(R.id.tvMF)
        sbEV = root.findViewById(R.id.sbEV)
        tvEV = root.findViewById(R.id.tvEV)
        tvIsoMode = root.findViewById(R.id.tvIsoMode)
        tvSsMode = root.findViewById(R.id.tvSsMode)
        tvWbMode = root.findViewById(R.id.tvWbMode)
        tvMfMode = root.findViewById(R.id.tvMfMode)
        tvEvMode = root.findViewById(R.id.tvEvMode)

        listOf(
            R.id.btnIsoAuto,
            R.id.btnSsAuto,
            R.id.btnWbAuto,
            R.id.btnMfAuto,
            R.id.btnEvAuto
        ).forEach { buttonId ->
            root.findViewById<Button>(buttonId).apply {
                // The app theme supplies a purple default Button tint. The approved reference uses
                // a literal white camera-A disc, so keep the drawable's own state colours untouched.
                backgroundTintList = null
                setTextColor(Color.BLACK)
            }
        }

        tvMetricProfile = root.findViewById(R.id.tvMetricProfile)
        tvMetricFocus = root.findViewById(R.id.tvMetricFocus)
    }

    private fun bindListeners() {
        refreshIsoSliderRange()
        refreshShutterSliderRange()
        refreshWhiteBalanceSliderRange()
        sbISO.setOnSeekBarChangeListener(onChange { p ->
            val iso = ManualIsoRangePolicy.isoForProgress(p, supportedIsoValues)
            tvISO.text = iso.toString()
            renderAutoState(panelISO, manual = true)
            isoUpdates.value = iso
        })
        findAuto(panelISO).setOnClickWithHaptic(CameraHapticAction.SNAP_TO_DEFAULT) {
            vm.setAutoISO()
            tvISO.text = "AUTO"
            sbISO.progress = ManualIsoRangePolicy.progressForIso(
                ManualControlValueMapper.nativeIsoRange.first,
                supportedIsoValues
            )
            renderAutoState(panelISO, manual = false)
            hideAdjustmentPanel()
        }

        sbSS.setOnSeekBarChangeListener(onChange { p ->
            val entry = CinemaManualControlPolicy.shutterEntryForProgress(p, supportedShutterEntries)
            tvSS.text = entry.label
            renderAutoState(panelSS, manual = true)
            shutterUpdates.value = entry.exposureNs
        })
        findAuto(panelSS).setOnClickWithHaptic(CameraHapticAction.SNAP_TO_DEFAULT) {
            vm.setAutoExposure()
            tvSS.text = "AUTO"
            renderAutoState(panelSS, manual = false)
            hideAdjustmentPanel()
        }

        sbWB.setOnSeekBarChangeListener(onChange { p ->
            val kelvin = CinemaManualControlPolicy.whiteBalanceKelvinForProgress(
                p,
                supportedWhiteBalanceRange
            )
            tvWB.text = ManualControlLabelFormatter.whiteBalanceLabel(kelvin)
            renderAutoState(panelWB, manual = true)
            wbUpdates.value = kelvin
        })
        sbWB.progress = CinemaManualControlPolicy.progressForWhiteBalanceKelvin(
            5_600,
            supportedWhiteBalanceRange
        )
        findAuto(panelWB).setOnClickWithHaptic(CameraHapticAction.SNAP_TO_DEFAULT) {
            vm.setAutoWB()
            tvWB.text = "AUTO WB"
            sbWB.progress = CinemaManualControlPolicy.progressForWhiteBalanceKelvin(
                5_600,
                supportedWhiteBalanceRange
            )
            renderAutoState(panelWB, manual = false)
            hideAdjustmentPanel()
        }

        sbMF.max = ManualControlValueMapper.focusProgressMax
        sbMF.setOnSeekBarChangeListener(onChange { p ->
            val dist = ManualControlValueMapper.focusDistanceForProgress(
                progress = p,
                minFocusDistance = vm.camera.minFocusDist
            )
            tvMF.text = ManualControlLabelFormatter.focusLabel(dist)
            renderAutoState(panelMF, manual = true)
            focusUpdates.value = dist
        })
        sbMF.progress = ManualControlValueMapper.focusProgressMax
        findAuto(panelMF).setOnClickWithHaptic(CameraHapticAction.SNAP_TO_DEFAULT) {
            vm.setAutoFocus()
            tvMF.text = "AF"
            sbMF.progress = ManualControlValueMapper.focusProgressMax
            renderAutoState(panelMF, manual = false)
            hideAdjustmentPanel()
        }

        val evRange = vm.camera.evDisplayRange
        evDisplayMin = evRange.lower
        evDisplayMax = evRange.upper
        evDisplayStepStops = vm.camera.evDisplayStepStops
        sbEV.max = (evDisplayMax - evDisplayMin).coerceAtLeast(1)
        sbEV.setOnSeekBarChangeListener(onChange { p ->
            val ev = ManualControlValueMapper.evValueForProgress(p, evDisplayMin, evDisplayMax)
            tvEV.text = compactEvLabel(ev)
            renderAutoState(panelEV, manual = ev != 0)
            evUpdates.value = ev
        })
        sbEV.progress = ManualControlValueMapper.evProgressForValue(0, evDisplayMin, evDisplayMax)
        findAuto(panelEV).setOnClickWithHaptic(CameraHapticAction.SNAP_TO_DEFAULT) {
            vm.setEV(0)
            tvEV.text = compactEvLabel(0)
            sbEV.progress = ManualControlValueMapper.evProgressForValue(0, evDisplayMin, evDisplayMax)
            renderAutoState(panelEV, manual = false)
            hideAdjustmentPanel()
        }

        tabISO.setOnClickWithHaptic(CameraHapticAction.NAVIGATION_TICK) { selectTab(panelISO, tabISO) }
        tabSS.setOnClickWithHaptic(CameraHapticAction.NAVIGATION_TICK) { selectTab(panelSS, tabSS) }
        tabWB.setOnClickWithHaptic(CameraHapticAction.NAVIGATION_TICK) { selectTab(panelWB, tabWB) }
        tabMF.setOnClickWithHaptic(CameraHapticAction.NAVIGATION_TICK) { selectTab(panelMF, tabMF) }
        tabEV.setOnClickWithHaptic(CameraHapticAction.NAVIGATION_TICK) { selectTab(panelEV, tabEV) }

        tabAFMode.setOnClickWithHaptic(CameraHapticAction.NAVIGATION_TICK) {
            val enabled = !vm.eyeAutofocusEnabled
            vm.setEyeAutofocusEnabled(enabled)
            renderAuxControlStates()
        }
        tabMeteringMode.setOnClickWithHaptic(CameraHapticAction.NAVIGATION_TICK) {
            vm.cycleManualMeteringMode()
            renderAuxControlStates()
        }
        tabRawToggle.setOnClickWithHaptic(CameraHapticAction.NAVIGATION_TICK) {
            val enableRaw = !vm.isRawEnabled
            vm.setRawEnabled(enableRaw)
            if (enableRaw) {
                vm.setCinemaVideoMode(CinemaVideoMode.RAW)
            } else if (vm.cinemaVideoMode.value == CinemaVideoMode.RAW) {
                vm.setCinemaVideoMode(CinemaVideoMode.PRO)
            }
            renderAuxControlStates()
        }
    }

    @OptIn(FlowPreview::class)
    private fun bindReactiveManualUpdates() {
        viewLifecycleOwner.lifecycleScope.launch {
            isoUpdates
                .filterNotNull()
                .distinctUntilChanged()
                .debounce(ManualControlValueMapper.manualControlDebounceMs)
                .collectLatest(vm::setISO)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            shutterUpdates
                .filterNotNull()
                .distinctUntilChanged()
                .debounce(ManualControlValueMapper.manualControlDebounceMs)
                .collectLatest(vm::setExposure)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            wbUpdates
                .filterNotNull()
                .distinctUntilChanged()
                .debounce(ManualControlValueMapper.manualControlDebounceMs)
                .collectLatest(vm::setWB)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            focusUpdates
                .filterNotNull()
                .distinctUntilChanged()
                .debounce(ManualControlValueMapper.manualControlDebounceMs)
                .collectLatest(vm::setFocusDist)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            evUpdates
                .filterNotNull()
                .distinctUntilChanged()
                .debounce(ManualControlValueMapper.manualControlDebounceMs)
                .collectLatest(vm::setEV)
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeLive() {
        viewLifecycleOwner.lifecycleScope.launch {
            // This Fragment remains STARTED while its parent container is GONE. Subscribe to capture
            // results only while PRO controls are visible, then bound UI telemetry to 10 Hz.
            vm.manualControlsVisibleFlow.collectLatest { visible ->
                if (!visible) return@collectLatest
                vm.liveResult
                    .filterNotNull()
                    .sample(LIVE_TELEMETRY_SAMPLE_MS)
                    .collect(::renderLiveResult)
            }
        }
    }

    private fun renderLiveResult(result: LiveResult) {
        latestLiveResult = result
        // The Pro rail is always attached while Pro mode is active, but its five adjustment panels
        // are normally hidden. Updating every hidden TextView and SeekBar at 10 Hz needlessly makes
        // RenderThread relayout work compete with the 4K recording path. Keep the latest telemetry
        // available, and render only the one panel the operator can currently see.
        if (!controlStack.isVisible) return

        val params = vm.cameraParams.value
        // CameraParams can contain an internally locked cinema exposure while user AE is still
        // Auto. The explicit control-state policy is the authority for AUTO/MANUAL UI labels.
        val manualState = CameraManualControlStateSyncPolicy.snapshot()
        when {
            panelISO.isVisible -> {
                refreshIsoSliderRange()
                val manual = manualState.iso.isManual
                val displayIso = if (params.iso > 0) params.iso else result.iso
                val label = if (displayIso > 0) {
                    sbISO.setProgressIfChanged(
                        ManualIsoRangePolicy.progressForIso(displayIso, supportedIsoValues)
                    )
                    displayIso.toString()
                } else {
                    "AUTO"
                }
                tvISO.setTextIfChanged(label)
                tabISO.setTextIfChanged(if (manual) params.iso.toString() else "AUTO")
                renderAutoState(panelISO, manual)
            }

            panelSS.isVisible -> {
                refreshShutterSliderRange()
                val manual = manualState.exposureNs.isManual
                val label = if (manual) {
                    val progress = CinemaManualControlPolicy.progressForExposureNs(
                        params.exposureNs,
                        supportedShutterEntries
                    )
                    sbSS.setProgressIfChanged(progress)
                    supportedShutterEntries.getOrNull(progress)?.label
                        ?: ManualControlLabelFormatter.shutterLabel(params.exposureNs)
                } else {
                    result.shutterLabel
                }
                tvSS.setTextIfChanged(label)
                tabSS.setTextIfChanged(if (manual) label else "AUTO")
                renderAutoState(panelSS, manual)
            }

            panelWB.isVisible -> {
                refreshWhiteBalanceSliderRange()
                val manual = manualState.whiteBalanceKelvin.isManual
                val label = if (manual) {
                    sbWB.setProgressIfChanged(
                        CinemaManualControlPolicy.progressForWhiteBalanceKelvin(
                            params.wbKelvin,
                            supportedWhiteBalanceRange
                        )
                    )
                    ManualControlLabelFormatter.whiteBalanceLabel(params.wbKelvin)
                } else {
                    "AUTO WB"
                }
                tvWB.setTextIfChanged(label)
                tabWB.setTextIfChanged(if (manual) "${params.wbKelvin}K" else "AUTO")
                renderAutoState(panelWB, manual)
            }

            panelMF.isVisible -> {
                val manual = manualState.focusDistance.isManual
                val label = if (manual) {
                    ManualControlLabelFormatter.focusLabel(params.focusDist)
                } else {
                    "AUTO AF"
                }
                tvMF.setTextIfChanged(label)
                tabMF.setTextIfChanged(if (manual) label else "AUTO")
                renderAutoState(panelMF, manual)
            }

            panelEV.isVisible -> {
                evDisplayStepStops = vm.camera.evDisplayStepStops
                val label = compactEvLabel(params.ev)
                tvEV.setTextIfChanged(label)
                sbEV.setProgressIfChanged(
                    ManualControlValueMapper.evProgressForValue(
                        params.ev,
                        evDisplayMin,
                        evDisplayMax
                    )
                )
                tabEV.setTextIfChanged(label)
                renderAutoState(panelEV, params.ev != 0)
            }
        }
    }

    /**
     * Rebuilds the Pro-control presentation from the authoritative user state without issuing a
     * camera mutation. Fragment creation, restoration, and Pro entry are lifecycle events, not an
     * implicit request to discard manual ISO, shutter, white balance, focus, or EV intent.
     */
    private fun renderCurrentControlState() {
        val params = vm.cameraParams.value
        val manualState = CameraManualControlStateSyncPolicy.snapshot()
        val manualIso = (manualState.iso as? ManualControlValue.Manual)?.toInt()
        val manualExposure = (manualState.exposureNs as? ManualControlValue.Manual)?.toLong()
        val manualWhiteBalance =
            (manualState.whiteBalanceKelvin as? ManualControlValue.Manual)?.toInt()
        val manualFocus = (manualState.focusDistance as? ManualControlValue.Manual)?.toFloat()
        val ev = (manualState.exposureCompensation as? ManualControlValue.Manual)?.toInt()
            ?: params.ev

        refreshIsoSliderRange()
        refreshShutterSliderRange()
        refreshWhiteBalanceSliderRange()
        evDisplayStepStops = vm.camera.evDisplayStepStops

        if (manualIso != null) {
            sbISO.setProgressIfChanged(
                ManualIsoRangePolicy.progressForIso(manualIso, supportedIsoValues)
            )
        }
        tvISO.setTextIfChanged(manualIso?.toString() ?: "AUTO")

        val shutterLabel = if (manualExposure != null) {
            val progress = CinemaManualControlPolicy.progressForExposureNs(
                manualExposure,
                supportedShutterEntries
            )
            sbSS.setProgressIfChanged(progress)
            supportedShutterEntries.getOrNull(progress)?.label
                ?: ManualControlLabelFormatter.shutterLabel(manualExposure)
        } else {
            "AUTO"
        }
        tvSS.setTextIfChanged(shutterLabel)

        if (manualWhiteBalance != null) {
            sbWB.setProgressIfChanged(
                CinemaManualControlPolicy.progressForWhiteBalanceKelvin(
                    manualWhiteBalance,
                    supportedWhiteBalanceRange
                )
            )
        }
        tvWB.setTextIfChanged(
            manualWhiteBalance?.let(ManualControlLabelFormatter::whiteBalanceLabel) ?: "AUTO WB"
        )

        if (manualFocus != null) {
            sbMF.setProgressIfChanged(
                ManualControlValueMapper.progressForFocusDistance(
                    distance = manualFocus,
                    minFocusDistance = vm.camera.minFocusDist
                )
            )
        } else {
            sbMF.setProgressIfChanged(ManualControlValueMapper.focusProgressMax)
        }
        val focusLabel = manualFocus?.let(ManualControlLabelFormatter::focusLabel) ?: "AUTO AF"
        tvMF.setTextIfChanged(focusLabel)

        sbEV.setProgressIfChanged(
            ManualControlValueMapper.evProgressForValue(ev, evDisplayMin, evDisplayMax)
        )
        val evLabel = compactEvLabel(ev)
        tvEV.setTextIfChanged(evLabel)

        renderAutoState(panelISO, manualIso != null)
        renderAutoState(panelSS, manualExposure != null)
        renderAutoState(panelWB, manualWhiteBalance != null)
        renderAutoState(panelMF, manualFocus != null)
        renderAutoState(panelEV, ev != 0)
        tabISO.setTextIfChanged(manualIso?.toString() ?: "AUTO")
        tabSS.setTextIfChanged(if (manualExposure != null) shutterLabel else "AUTO")
        tabWB.setTextIfChanged(manualWhiteBalance?.let { "${it}K" } ?: "AUTO")
        tabMF.setTextIfChanged(if (manualFocus != null) focusLabel else "AUTO")
        tabEV.setTextIfChanged(evLabel)
        tvMetricProfile.setTextIfChanged(
            ManualControlLabelFormatter.metricProfileLabel(vm.currentColorProfile)
        )
        tvMetricFocus.setTextIfChanged(
            ManualControlLabelFormatter.metricFocusLabel(
                faceDetectionEnabled = vm.faceDetectionEnabled,
                eyeAutofocusEnabled = vm.eyeAutofocusEnabled
            )
        )
        renderAuxControlStates()
    }

    private fun refreshIsoSliderRange() {
        val range = vm.camera.effectiveIsoRange
        if (range == supportedIsoRange) return
        supportedIsoRange = range
        supportedIsoValues = ManualIsoRangePolicy.supportedValues(range)
        sbISO.max = (supportedIsoValues.size - 1).coerceAtLeast(0)
    }

    private fun refreshShutterSliderRange() {
        val exposureRange = vm.camera.exposureRange
        val fps = vm.cameraParams.value.fps.coerceAtLeast(24)
        if (exposureRange == supportedShutterExposureRange && fps == supportedShutterFps) return
        supportedShutterExposureRange = exposureRange
        supportedShutterFps = fps
        val entries = CinemaManualControlPolicy.shutterAngleEntries(
            fps = fps,
            exposureRange = CameraLongRange(exposureRange.lower, exposureRange.upper)
        )
        if (entries == supportedShutterEntries) return
        supportedShutterEntries = entries
        sbSS.max = (supportedShutterEntries.size - 1).coerceAtLeast(0)
        val exposure = vm.cameraParams.value.exposureNs.takeIf { it > 0L }
            ?: CinemaManualControlPolicy.shutterAngleToExposureNs(180f, fps)
        sbSS.setProgressIfChanged(
            CinemaManualControlPolicy.progressForExposureNs(exposure, supportedShutterEntries)
        )
    }

    private fun refreshWhiteBalanceSliderRange() {
        val range = vm.camera.manualWhiteBalanceRange
        if (range == supportedWhiteBalanceHardwareRange) return
        supportedWhiteBalanceHardwareRange = range
        val policyRange = CinemaManualControlPolicy.whiteBalanceRange(range.lower, range.upper)
        supportedWhiteBalanceRange = policyRange
        sbWB.max = CinemaManualControlPolicy.whiteBalanceProgressMax(supportedWhiteBalanceRange)
        val kelvin = vm.cameraParams.value.wbKelvin.takeIf { it > 0 } ?: 5_600
        sbWB.setProgressIfChanged(
            CinemaManualControlPolicy.progressForWhiteBalanceKelvin(
                kelvin,
                supportedWhiteBalanceRange
            )
        )
    }

    private fun showTab(panel: View) {
        if (panel.isVisible && controlStack.isVisible) return
        controlStack.visibility = View.VISIBLE
        listOf(panelISO, panelSS, panelWB, panelMF, panelEV).forEach {
            it.animate().cancel()
            it.visibility = View.GONE
            it.alpha = 1f
            it.translationY = 0f
        }
        panel.alpha = 0f
        panel.translationY = dp(5f)
        panel.visibility = View.VISIBLE
        panel.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(120L)
            .start()
    }

    private fun selectTab(panel: View, tab: Button) {
        if (controlStack.isVisible && panel.isVisible) {
            hideAdjustmentPanel()
            return
        }
        showTab(panel)
        markActive(tab)
        // Rebuild static state once, then replay the cached capture result immediately so opening a
        // control never waits for the next 10 Hz sample.
        renderCurrentControlState()
        latestLiveResult?.let(::renderLiveResult)
    }

    private fun hideAdjustmentPanel() {
        listOf(panelISO, panelSS, panelWB, panelMF, panelEV).forEach { panel ->
            panel.animate().cancel()
            panel.visibility = View.GONE
            panel.alpha = 1f
            panel.translationY = 0f
        }
        controlStack.visibility = View.GONE
        clearActiveTab()
    }

    private fun clearActiveTab() {
        listOf(tabISO, tabSS, tabWB, tabMF, tabEV).forEach {
            it.setTextColor(0xFFE5E9ED.toInt())
            it.background = null
            it.isSelected = false
            it.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            it.alpha = 0.9f
        }
    }

    private fun markActive(tab: Button) {
        clearActiveTab()
        tab.setTextColor(0xFFB46CFF.toInt())
        tab.background = null
        tab.isSelected = true
        tab.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        tab.alpha = 1f
    }

    private fun renderAutoState(panel: View, manual: Boolean) {
        val (buttonId, label) = when (panel.id) {
            R.id.sectionISO -> R.id.btnIsoAuto to tvIsoMode
            R.id.sectionSS -> R.id.btnSsAuto to tvSsMode
            R.id.sectionWB -> R.id.btnWbAuto to tvWbMode
            R.id.sectionMF -> R.id.btnMfAuto to tvMfMode
            R.id.sectionEV -> R.id.btnEvAuto to tvEvMode
            else -> return
        }
        panel.findViewById<Button>(buttonId).setActivatedIfChanged(manual)
        label.setTextIfChanged(if (manual) "Manual" else "Auto")
        val labelColor = 0xE6FFFFFF.toInt()
        if (label.textColors.defaultColor != labelColor) label.setTextColor(labelColor)
    }

    private fun renderAuxControlStates() {
        val eyeAfEnabled = vm.eyeAutofocusEnabled
        tabAFMode.setSelectedIfChanged(eyeAfEnabled)
        tabAFMode.setTextIfChanged(if (eyeAfEnabled) "AF-C" else "AF-S")

        val meteringMode = vm.manualMeteringModeFlow.value
        tabMeteringMode.setSelectedIfChanged(meteringMode != CinemaManualMeteringMode.MATRIX)
        tabMeteringMode.setTextIfChanged(ManualAuxControlLabelPolicy.meteringLabel(meteringMode))

        val rawEnabled = vm.isRawEnabled
        tabRawToggle.setSelectedIfChanged(rawEnabled)
        // s8-f02: route the RAW label through the honest-claim policy. With the default flag OFF this
        // returns exactly "RAW ON"/"RAW OFF" (no-downgrade); enforced mode demands proven transport.
        tabRawToggle.setTextIfChanged(
            ManualControlRawClaimPolicy.text(ManualControlRawClaimPolicy.labelFor(rawEnabled))
        )
        tvMetricProfile.setTextIfChanged(
            ManualControlLabelFormatter.metricProfileLabel(vm.currentColorProfile)
        )
        tvMetricFocus.setTextIfChanged(
            ManualControlLabelFormatter.metricFocusLabel(
                faceDetectionEnabled = vm.faceDetectionEnabled,
                eyeAutofocusEnabled = vm.eyeAutofocusEnabled
            )
        )
    }

    private fun findAuto(panel: View): Button {
        val search = panel as? ViewGroup ?: throw IllegalStateException("Panel is not a ViewGroup")
        for (i in 0 until search.childCount) {
            val v = search.getChildAt(i)
            if (v is ViewGroup) {
                for (j in 0 until v.childCount) {
                    val nested = v.getChildAt(j)
                    if (nested is Button) return nested
                }
            } else if (v is Button) {
                return v
            }
        }
        throw IllegalStateException("No auto button found in panel")
    }

    private fun compactEvLabel(ev: Int): String =
        ManualControlLabelFormatter.evIndexLabel(ev, evDisplayStepStops).removeSuffix(" EV")

    private fun TextView.setTextIfChanged(value: CharSequence) {
        if (!TextUtils.equals(text, value)) text = value
    }

    private fun SeekBar.setProgressIfChanged(value: Int) {
        if (progress != value) progress = value
    }

    private fun View.setSelectedIfChanged(value: Boolean) {
        if (isSelected != value) isSelected = value
    }

    private fun View.setActivatedIfChanged(value: Boolean) {
        if (isActivated != value) isActivated = value
    }

    private fun installSeekBarHaptics() {
        listOf(sbISO, sbSS, sbWB, sbMF, sbEV).forEach { seekBar ->
            (seekBar as? ReferenceAdjustSeekBar)?.setCameraHapticCallback(::performCameraHaptic)
        }
    }

    private fun performCameraHaptic(action: CameraHapticAction) {
        (activity as? CameraHapticPerformer)?.performCameraHaptic(action)
    }

    private fun onChange(action: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
            if (fromUser) {
                action(p)
            }
        }

        override fun onStartTrackingTouch(sb: SeekBar?) {
            performCameraHaptic(CameraHapticAction.MANUAL_CONTROL_TICK)
        }

        override fun onStopTrackingTouch(sb: SeekBar?) {
            performCameraHaptic(CameraHapticAction.MANUAL_CONTROL_TICK)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun View.setOnClickWithHaptic(
        action: CameraHapticAction = CameraHapticAction.BUTTON_CLICK,
        onClick: () -> Unit
    ) {
        setOnClickListener {
            performCameraHaptic(action)
            onClick()
        }
    }

}
