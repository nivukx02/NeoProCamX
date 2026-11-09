package com.neoproc.neoproccamx.ui.controls

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.os.Bundle
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
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.ui.CameraHapticAction
import com.neoproc.neoproccamx.ui.CameraHapticPerformer
import com.neoproc.neoproccamx.ui.CameraViewModel
import com.neoproc.neoproccamx.ui.MainActivity
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

@SuppressLint("SetTextI18n", "DefaultLocale")
class ManualControlsFragment : Fragment() {

    private val vm: CameraViewModel get() = (requireActivity() as MainActivity).vm
    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

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
    private lateinit var tvMetricProfile: TextView
    private lateinit var tvMetricHistogram: TextView
    private lateinit var tvMetricFocus: TextView
    private var evDisplayMin = -4
    private var evDisplayMax = 4
    private var evDisplayStepStops = 1f
    private var supportedIsoValues: List<Int> = emptyList()
    private var supportedShutterEntries: List<CinemaShutterAngleEntry> = emptyList()
    private var supportedWhiteBalanceRange = CinemaManualControlPolicy.whiteBalanceRange()

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
        if (strictHighEndEnabled()) {
            showTab(panelSS)
            markActive(tabSS)
        } else {
            showTab(panelISO)
            markActive(tabISO)
        }
        renderAuxControlStates()
    }

    private fun bindViews(root: View) {
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

        tvMetricProfile = root.findViewById(R.id.tvMetricProfile)
        tvMetricHistogram = root.findViewById(R.id.tvMetricHistogram)
        tvMetricFocus = root.findViewById(R.id.tvMetricFocus)

        root.findViewById<View>(R.id.btnProGear).setOnClickWithHaptic {
            vm.toggleHist()
        }
    }

    private fun bindListeners() {
        refreshIsoSliderRange()
        refreshShutterSliderRange()
        refreshWhiteBalanceSliderRange()
        sbISO.setOnSeekBarChangeListener(onChange { p ->
            val iso = ManualIsoRangePolicy.isoForProgress(p, supportedIsoValues)
            tvISO.text = ManualControlLabelFormatter.isoLabel(iso)
            isoUpdates.value = iso
        })
        findAuto(panelISO).setOnClickWithHaptic(CameraHapticAction.SNAP_TO_DEFAULT) {
            vm.setAutoISO()
            tvISO.text = "ISO AUTO"
            sbISO.progress = ManualIsoRangePolicy.progressForIso(
                ManualControlValueMapper.nativeIsoRange.first,
                supportedIsoValues
            )
        }

        sbSS.setOnSeekBarChangeListener(onChange { p ->
            val entry = CinemaManualControlPolicy.shutterEntryForProgress(p, supportedShutterEntries)
            tvSS.text = entry.label
            shutterUpdates.value = entry.exposureNs
        })
        findAuto(panelSS).setOnClickWithHaptic(CameraHapticAction.SNAP_TO_DEFAULT) {
            vm.setAutoExposure()
            tvSS.text = "AUTO"
        }

        sbWB.setOnSeekBarChangeListener(onChange { p ->
            val kelvin = CinemaManualControlPolicy.whiteBalanceKelvinForProgress(
                p,
                supportedWhiteBalanceRange
            )
            tvWB.text = ManualControlLabelFormatter.whiteBalanceLabel(kelvin)
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
        }

        sbMF.max = ManualControlValueMapper.focusProgressMax
        sbMF.setOnSeekBarChangeListener(onChange { p ->
            val dist = ManualControlValueMapper.focusDistanceForProgress(
                progress = p,
                minFocusDistance = vm.camera.minFocusDist
            )
            tvMF.text = ManualControlLabelFormatter.focusLabel(dist)
            focusUpdates.value = dist
        })
        sbMF.progress = ManualControlValueMapper.focusProgressMax
        findAuto(panelMF).setOnClickWithHaptic(CameraHapticAction.SNAP_TO_DEFAULT) {
            vm.setAutoFocus()
            tvMF.text = "AF"
            sbMF.progress = ManualControlValueMapper.focusProgressMax
        }

        val evRange = vm.camera.evDisplayRange
        evDisplayMin = evRange.lower
        evDisplayMax = evRange.upper
        evDisplayStepStops = vm.camera.evDisplayStepStops
        sbEV.max = (evDisplayMax - evDisplayMin).coerceAtLeast(1)
        sbEV.setOnSeekBarChangeListener(onChange { p ->
            val ev = ManualControlValueMapper.evValueForProgress(p, evDisplayMin, evDisplayMax)
            tvEV.text = ManualControlLabelFormatter.evIndexLabel(ev, evDisplayStepStops)
            evUpdates.value = ev
        })
        sbEV.progress = ManualControlValueMapper.evProgressForValue(0, evDisplayMin, evDisplayMax)
        findAuto(panelEV).setOnClickWithHaptic(CameraHapticAction.SNAP_TO_DEFAULT) {
            vm.setEV(0)
            tvEV.text = ManualControlLabelFormatter.evIndexLabel(0, evDisplayStepStops)
            sbEV.progress = ManualControlValueMapper.evProgressForValue(0, evDisplayMin, evDisplayMax)
        }

        tabISO.setOnClickWithHaptic(CameraHapticAction.NAVIGATION_TICK) { showTab(panelISO); markActive(tabISO) }
        tabSS.setOnClickWithHaptic(CameraHapticAction.NAVIGATION_TICK) { showTab(panelSS); markActive(tabSS) }
        tabWB.setOnClickWithHaptic(CameraHapticAction.NAVIGATION_TICK) { showTab(panelWB); markActive(tabWB) }
        tabMF.setOnClickWithHaptic(CameraHapticAction.NAVIGATION_TICK) { showTab(panelMF); markActive(tabMF) }
        tabEV.setOnClickWithHaptic(CameraHapticAction.NAVIGATION_TICK) { showTab(panelEV); markActive(tabEV) }

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

    private fun observeLive() {
        viewLifecycleOwner.lifecycleScope.launch {
            vm.liveResult.collectLatest { result: LiveResult? ->
                result ?: return@collectLatest
                val params = vm.cameraParams.value
                tvMetricProfile.text = ManualControlLabelFormatter.metricProfileLabel(vm.currentColorProfile)
                tvMetricHistogram.text = ManualControlLabelFormatter.metricHistogramLabel(
                    showHistogram = vm.isHistogramEnabled,
                    showWaveform = vm.isWaveformEnabled,
                    showZebra = vm.isZebraEnabled
                )
                tvMetricFocus.text = ManualControlLabelFormatter.metricFocusLabel(
                    peakingEnabled = vm.isPeakingEnabled,
                    faceDetectionEnabled = vm.faceDetectionEnabled,
                    eyeAutofocusEnabled = vm.eyeAutofocusEnabled
                )
                refreshIsoSliderRange()
                refreshShutterSliderRange()
                refreshWhiteBalanceSliderRange()
                evDisplayStepStops = vm.camera.evDisplayStepStops
                val displayIso = if (params.iso > 0) params.iso else result.iso
                tvISO.text = if (displayIso > 0) {
                    sbISO.progress = ManualIsoRangePolicy.progressForIso(displayIso, supportedIsoValues)
                    ManualControlLabelFormatter.isoLabel(displayIso)
                } else {
                    "ISO AUTO"
                }
                tvSS.text = if (params.exposureNs > 0L) {
                    val shutterProgress = CinemaManualControlPolicy.progressForExposureNs(
                        params.exposureNs,
                        supportedShutterEntries
                    )
                    sbSS.progress = shutterProgress
                    supportedShutterEntries.getOrNull(shutterProgress)?.label
                        ?: ManualControlLabelFormatter.shutterLabel(params.exposureNs)
                } else {
                    result.shutterLabel
                }
                if (params.wbKelvin > 0) {
                    sbWB.progress = CinemaManualControlPolicy.progressForWhiteBalanceKelvin(
                        params.wbKelvin,
                        supportedWhiteBalanceRange
                    )
                    tvWB.text = ManualControlLabelFormatter.whiteBalanceLabel(params.wbKelvin)
                }
                if (params.focusDist >= 0f) {
                    tvMF.text = ManualControlLabelFormatter.focusLabel(params.focusDist)
                }
                tvEV.text = ManualControlLabelFormatter.evIndexLabel(params.ev, evDisplayStepStops)
                sbEV.progress = ManualControlValueMapper.evProgressForValue(
                    params.ev,
                    evDisplayMin,
                    evDisplayMax
                )
                renderAuxControlStates()
            }
        }
    }

    private fun refreshIsoSliderRange() {
        val values = ManualIsoRangePolicy.supportedValues(vm.camera.effectiveIsoRange)
        if (values == supportedIsoValues) return
        supportedIsoValues = values
        sbISO.max = (supportedIsoValues.size - 1).coerceAtLeast(0)
    }

    private fun refreshShutterSliderRange() {
        val exposureRange = vm.camera.exposureRange
        val fps = vm.cameraParams.value.fps.coerceAtLeast(24)
        val entries = CinemaManualControlPolicy.shutterAngleEntries(
            fps = fps,
            exposureRange = CameraLongRange(exposureRange.lower, exposureRange.upper)
        )
        if (entries == supportedShutterEntries) return
        supportedShutterEntries = entries
        sbSS.max = (supportedShutterEntries.size - 1).coerceAtLeast(0)
        val exposure = vm.cameraParams.value.exposureNs.takeIf { it > 0L }
            ?: CinemaManualControlPolicy.shutterAngleToExposureNs(180f, fps)
        sbSS.progress = CinemaManualControlPolicy.progressForExposureNs(
            exposure,
            supportedShutterEntries
        )
    }

    private fun refreshWhiteBalanceSliderRange() {
        val range = vm.camera.manualWhiteBalanceRange
        val policyRange = CinemaManualControlPolicy.whiteBalanceRange(range.lower, range.upper)
        if (policyRange == supportedWhiteBalanceRange) return
        supportedWhiteBalanceRange = policyRange
        sbWB.max = CinemaManualControlPolicy.whiteBalanceProgressMax(supportedWhiteBalanceRange)
        val kelvin = vm.cameraParams.value.wbKelvin.takeIf { it > 0 } ?: 5_600
        sbWB.progress = CinemaManualControlPolicy.progressForWhiteBalanceKelvin(
            kelvin,
            supportedWhiteBalanceRange
        )
    }

    private fun showTab(panel: View) {
        if (panel.isVisible) return
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

    private fun markActive(tab: Button) {
        listOf(tabISO, tabSS, tabWB, tabMF, tabEV).forEach {
            it.setTextColor(0xFFB8C2CC.toInt())
            it.setBackgroundResource(R.drawable.bg_manual_control_chip)
            it.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            it.alpha = 0.82f
        }
        tab.setTextColor(0xFFF6CD4D.toInt())
        tab.setBackgroundResource(R.drawable.bg_manual_control_chip_active)
        tab.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        tab.alpha = 1f
    }

    private fun renderAuxControlStates() {
        val eyeAfEnabled = vm.eyeAutofocusEnabled
        tabAFMode.isSelected = eyeAfEnabled
        tabAFMode.text = if (eyeAfEnabled) "AF-C" else "AF-S"

        val meteringMode = vm.manualMeteringModeFlow.value
        tabMeteringMode.isSelected = meteringMode != CinemaManualMeteringMode.MATRIX
        tabMeteringMode.text = ManualAuxControlLabelPolicy.meteringLabel(meteringMode)

        val rawEnabled = vm.isRawEnabled
        tabRawToggle.isSelected = rawEnabled
        // s8-f02: route the RAW label through the honest-claim policy. With the default flag OFF this
        // returns exactly "RAW ON"/"RAW OFF" (no-downgrade); enforced mode demands proven transport.
        tabRawToggle.text = ManualControlRawClaimPolicy.text(ManualControlRawClaimPolicy.labelFor(rawEnabled))
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
