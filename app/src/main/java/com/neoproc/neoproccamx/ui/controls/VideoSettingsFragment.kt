package com.neoproc.neoproccamx.ui.controls

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.VideoBitrateMode
import com.neoproc.neoproccamx.ui.CameraHapticPerformer
import com.neoproc.neoproccamx.ui.CameraViewModel
import com.neoproc.neoproccamx.ui.MainActivity
import kotlinx.coroutines.launch

class VideoSettingsFragment : Fragment() {

    companion object {
        private const val ARG_FULL_SCREEN = "arg_full_screen"
        const val SETTINGS_REJECTION_BANNER_TAG = "settingsInvariantRejectionBanner"

        fun newInstance(fullScreen: Boolean = false): VideoSettingsFragment =
            VideoSettingsFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_FULL_SCREEN, fullScreen)
                }
            }
    }

    // Keep settings/conversion on MainActivity's process-owned live camera instance. Using the
    // Activity ViewModelStore here creates a second CameraViewModel because MainActivity uses the
    // application's ViewModelStore to preserve background recording across Activity recreation.
    private val viewModel: CameraViewModel get() = (requireActivity() as MainActivity).vm

    private lateinit var bitrateValue: TextView
    private lateinit var bitrateSeekBar: SeekBar
    private lateinit var contentContainer: FrameLayout
    private lateinit var settingsRejectionBanner: TextView
    private var settingsContentView: View? = null
    private var currentViewHasResumed = false
    private var renderedCapabilitySignature: SettingsCapabilitySignature? = null

    private data class SettingsCapabilitySignature(
        val lensFacing: Int,
        val supportsRaw: Boolean,
        val supportsOis: Boolean,
        val supportsVideoStabilization: Boolean,
        val supportsPreviewStabilization: Boolean,
        val cinemaVideoMode: CinemaVideoMode,
        val videoHdrMode: String
    )

    private val isFullScreenPresentation: Boolean
        get() = arguments?.getBoolean(ARG_FULL_SCREEN, false) == true

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        contentContainer = FrameLayout(requireContext())
        renderContent()
        settingsRejectionBanner = createSettingsRejectionBanner()
        contentContainer.addView(settingsRejectionBanner)
        return contentContainer
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        currentViewHasResumed = false
        bindLiveState()
    }

    /**
     * Compose the settings screen and mount it into [contentContainer]. Passing itself as the rebuild hook
     * lets a section (e.g. Resolution) recompose the sheet in place so capability-filtered choices and the
     * applied selection stay synchronized without changing the user's requested recording cadence.
     */
    private fun renderContent() {
        // Preserve the scroll position across an in-place rebuild (e.g. Resolution->Frame-Rate, or the
        // Custom Recording Name toggle at the bottom of the SYSTEM section) so a rebuild does not bounce
        // the user back to the top of a long settings list.
        val previousScrollY = (contentContainer.getChildAt(0) as? ScrollView)?.scrollY ?: 0
        val screenContent = SettingsScreenComposer(
            context = requireContext(),
            viewModel = viewModel,
            openLutImporter = { (activity as? MainActivity)?.launchCustomLutImport() },
            hapticPerformer = { action -> (activity as? CameraHapticPerformer)?.performCameraHaptic(action) },
            requestSettingsRebuild = { if (isAdded) renderContent() }
        ).compose(fullScreen = isFullScreenPresentation)

        bitrateValue = screenContent.runtimeViews.bitrateValue
        bitrateSeekBar = screenContent.runtimeViews.bitrateSeekBar
        settingsContentView?.let(contentContainer::removeView)
        settingsContentView = screenContent.rootView
        contentContainer.addView(screenContent.rootView, 0)
        if (previousScrollY > 0) {
            (screenContent.rootView as? ScrollView)?.let { scrollView ->
                scrollView.post { scrollView.scrollTo(0, previousScrollY) }
            }
        }
        renderedCapabilitySignature = currentCapabilitySignature()
        applyBitrateMode(viewModel.videoBitrateMode.value)
    }

    private fun currentCapabilitySignature(): SettingsCapabilitySignature? {
        val lensFacing = viewModel.frameGeometry.value?.lensFacing ?: return null
        return SettingsCapabilitySignature(
            lensFacing = lensFacing,
            supportsRaw = viewModel.camera.supportsRaw,
            supportsOis = viewModel.camera.supportsOIS,
            supportsVideoStabilization = viewModel.camera.supportsVideoStabilization,
            supportsPreviewStabilization = viewModel.camera.supportsPreviewStabilization,
            cinemaVideoMode = viewModel.cinemaVideoMode.value,
            videoHdrMode = viewModel.videoHdrMode.value
        )
    }

    private fun rebuildForCapabilityChangeIfNeeded() {
        val current = currentCapabilitySignature() ?: return
        if (current != renderedCapabilitySignature && isAdded) renderContent()
    }

    override fun onResume() {
        super.onResume()
        // onCreateView already composed this view. Recompose only after a real pause/resume, such as
        // returning from the LUT picker, instead of building the entire Settings tree twice at launch.
        if (currentViewHasResumed) {
            renderContent()
        } else {
            currentViewHasResumed = true
        }
    }


    private fun bindLiveState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.settingsRejection.collect { rejection ->
                        if (rejection == null) {
                            settingsRejectionBanner.visibility = View.GONE
                        } else {
                            // A rejected selector is optimistic for only the current callback. Rebuild
                            // from authoritative ViewModel state immediately so its old valid value is
                            // restored, then retain an explicit accessible reason over the sheet.
                            renderContent()
                            settingsRejectionBanner.text = rejection.message
                            settingsRejectionBanner.contentDescription =
                                "Setting rejected: ${rejection.message}"
                            settingsRejectionBanner.visibility = View.VISIBLE
                            settingsRejectionBanner.alpha = 0f
                            settingsRejectionBanner.animate().cancel()
                            settingsRejectionBanner.animate().alpha(1f).setDuration(140L).start()
                        }
                    }
                }
                launch {
                    viewModel.cinemaRuntimeStats.collect { stats ->
                        bitrateValue.text = SettingsRuntimeFormatter.formatBitrateStatusLabel(
                            bitrateMode = viewModel.videoBitrateMode.value,
                            requestedBitrateBitsPerSecond = viewModel.requestedVideoBitrateBitsPerSecond(),
                            appliedBitrateBitsPerSecond = viewModel.reportedVideoBitrateBitsPerSecond()
                        )
                        view?.findViewWithTag<TextView>(SettingsSectionFactory.STORAGE_INFO_TAG)?.text =
                            stats.storageLabel
                    }
                }
                launch {
                    viewModel.videoBitrateMode.collect { mode ->
                        applyBitrateMode(mode)
                    }
                }
                launch {
                    // Camera control mutations may complete after a capability-triggered Settings
                    // rebuild. Keep the visible Flash value bound to the authoritative Camera2 state
                    // so the row can never display a stale pre-switch value.
                    viewModel.cameraParams.collect { params ->
                        val label = SettingsOptionCatalog.flashChoices
                            .firstOrNull { it.value == params.flash }
                            ?.label ?: params.flash.name
                        view?.findViewWithTag<TextView>(SettingsSectionFactory.FLASH_VALUE_TAG)?.text = label
                    }
                }
                launch {
                    viewModel.frameGeometry.collect { geometry ->
                        if (geometry != null) rebuildForCapabilityChangeIfNeeded()
                    }
                }
                launch {
                    viewModel.cinemaVideoMode.collect {
                        rebuildForCapabilityChangeIfNeeded()
                    }
                }
                launch {
                    viewModel.videoHdrMode.collect {
                        rebuildForCapabilityChangeIfNeeded()
                    }
                }
                launch {
                    viewModel.recordingSavedPath.collect {
                        if (isAdded) renderContent()
                    }
                }
                launch {
                    // Live "RAW Tier (active)" indicator — updates on camera/lens switch, app start,
                    // capability re-query, and RAW-resolution mode changes, without a session restart.
                    viewModel.rawCaptureTierStatus.collect { label ->
                        rebuildForCapabilityChangeIfNeeded()
                        view?.findViewWithTag<TextView>(SettingsSectionFactory.RAW_TIER_INFO_TAG)?.text = label
                    }
                }
            }
        }
    }

    private fun applyBitrateMode(mode: VideoBitrateMode) {
        val isCustom = strictHighEndEnabled() || SettingsRuntimeFormatter.isCustomBitrateEnabled(mode)
        bitrateSeekBar.isEnabled = isCustom
        bitrateSeekBar.alpha = if (isCustom) 1f else 0.45f
    }

    private fun createSettingsRejectionBanner(): TextView {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        return TextView(requireContext()).apply {
            tag = SETTINGS_REJECTION_BANNER_TAG
            visibility = View.GONE
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            isFocusable = true
            // Updating text on an ASSERTIVE live region is announced automatically by modern
            // accessibility services without calling deprecated announcement APIs.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
            elevation = dp(8).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(0xF2261438.toInt())
                setStroke(dp(1), 0xFFB46CFF.toInt())
            }
            setOnClickListener { viewModel.dismissSettingsRejection() }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply {
                setMargins(dp(12), dp(10), dp(12), 0)
            }
        }
    }
}
