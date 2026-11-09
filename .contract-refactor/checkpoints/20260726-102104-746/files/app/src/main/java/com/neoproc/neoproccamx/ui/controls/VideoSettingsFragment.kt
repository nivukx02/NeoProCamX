package com.neoproc.neoproccamx.ui.controls

import android.os.Bundle
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
import com.neoproc.neoproccamx.recording.VideoBitrateMode
import com.neoproc.neoproccamx.ui.CameraHapticPerformer
import com.neoproc.neoproccamx.ui.CameraViewModel
import com.neoproc.neoproccamx.ui.MainActivity
import kotlinx.coroutines.launch

class VideoSettingsFragment : Fragment() {

    companion object {
        private const val ARG_FULL_SCREEN = "arg_full_screen"

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
        return contentContainer
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindLiveState()
    }

    /**
     * Compose the settings screen and mount it into [contentContainer]. Passing itself as the rebuild hook
     * lets a section (e.g. Resolution) recompose the sheet in place, so the Frame-Rate options re-derive from
     * the newly-selected resolution live (4K-class -> up to 25; 1080p/2K -> all rates).
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
        contentContainer.removeAllViews()
        contentContainer.addView(screenContent.rootView)
        if (previousScrollY > 0) {
            (screenContent.rootView as? ScrollView)?.let { scrollView ->
                scrollView.post { scrollView.scrollTo(0, previousScrollY) }
            }
        }
        applyBitrateMode(viewModel.videoBitrateMode.value)
    }

    override fun onResume() {
        super.onResume()
        renderContent()
    }


    private fun bindLiveState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
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
                    viewModel.recordingSavedPath.collect {
                        if (isAdded) renderContent()
                    }
                }
                launch {
                    // Live "RAW Tier (active)" indicator — updates on camera/lens switch, app start,
                    // capability re-query, and RAW-resolution mode changes, without a session restart.
                    viewModel.rawCaptureTierStatus.collect { label ->
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
}
