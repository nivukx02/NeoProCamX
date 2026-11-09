package com.neoproc.neoproccamx.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.commit
import com.neoproc.neoproccamx.R
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.ui.controls.VideoSettingsFragment

class CameraSettingsSheetFragment : DialogFragment() {

    companion object {
        const val TAG = "camera_settings_sheet"
        private const val KEY_CONTAINER_ID = "camera_settings_container_id"
    }

    // CameraViewModel is deliberately process-owned so recording survives Activity recreation.
    // Resolve the exact instance used by MainActivity; activityViewModels() would create a second,
    // idle camera owner and conversion/settings teardown would never reach the live Camera2 session.
    private val viewModel: CameraViewModel get() = (requireActivity() as MainActivity).vm
    private var containerId: Int = View.generateViewId()

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Restore the SAME container id used before process death. Otherwise a fresh generateViewId()
        // no longer matches the id the restored VideoSettingsFragment was committed to, and the settings
        // body renders blank (or throws "No view found for id ...").
        savedInstanceState?.getInt(KEY_CONTAINER_ID, 0)?.takeIf { it != 0 }?.let { containerId = it }
        setStyle(STYLE_NORMAL, R.style.Theme_NeoProCamX_SettingsFullScreen)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CONTAINER_ID, containerId)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt())
        }

        val header = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ctx.dp(if (strictHighEndEnabled()) 70 else 66)
            )
            setPadding(ctx.dp(18), ctx.dp(6), ctx.dp(18), ctx.dp(6))
        }

        val titleBlock = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER_VERTICAL
            )
            setPadding(ctx.dp(18), 0, ctx.dp(18), 0)
            addView(TextView(ctx).apply {
                text = ctx.getString(R.string.settings_camera_title)
                setTextColor(0xFFF4F7FA.toInt())
                textSize = 17f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
            })
            addView(TextView(ctx).apply {
                text = ctx.getString(R.string.settings_camera_subtitle)
                setTextColor(0x99FFFFFF.toInt())
                textSize = 10f
                gravity = Gravity.CENTER
                setPadding(0, ctx.dp(2), 0, 0)
            })
        }

        header.addView(titleBlock)
        root.addView(header)
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ctx.dp(1)
            )
            setBackgroundColor(0x12FFFFFF)
        })

        root.addView(FrameLayout(ctx).apply {
            id = containerId
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setBackgroundColor(Color.TRANSPARENT)
        })

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val headerHeight = systemBars.top + ctx.dp(if (strictHighEndEnabled()) 70 else 66)
            (header.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                if (params.height != headerHeight) {
                    params.height = headerHeight
                    header.layoutParams = params
                }
            }
            header.setPadding(
                ctx.dp(18),
                systemBars.top + ctx.dp(6),
                ctx.dp(18),
                ctx.dp(6)
            )
            root.setPadding(0, 0, 0, systemBars.bottom)
            insets
        }

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (childFragmentManager.findFragmentByTag("video_settings_sheet_content") == null) {
            childFragmentManager.commit {
                replace(containerId, VideoSettingsFragment.newInstance(fullScreen = true), "video_settings_sheet_content")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setDimAmount(0f)
            setBackgroundDrawable(0xFF06080B.toInt().toDrawable())
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        // Suspend the active camera pipeline (GPU rendering, AI tracking, ISP stream)
        // while the opaque settings menu covers the screen to prevent massive thermal/power leaks.
        viewModel.closeCamera()
    }

    override fun onStop() {
        super.onStop()
        // Resume the camera pipeline as the settings menu is dismissed.
        viewModel.resumeCameraForForeground()
    }

    private fun android.content.Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
