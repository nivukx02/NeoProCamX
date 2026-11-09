package com.neoproc.neoproccamx.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
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

/**
 * Narrow lifecycle boundary used by the settings fragment.
 *
 * Keeping the fragment coupled to this contract prevents lifecycle callbacks from recursively walking
 * the complete Activity/ViewModel call graph during AndroidX lint analysis, while the Activity remains
 * the sole owner of camera and sensor suspension/resumption.
 */
interface CameraSettingsLifecycleHost {
    fun onSettingsSheetStarted()
    fun onSettingsSheetDismissed()
}

/**
 * Exact launch transaction for the settings sheet.
 *
 * Camera and sensor suspension begins before FragmentManager composition. If that composition rejects
 * the transaction, the same dismissal callback must roll back every suspended owner; reopening only
 * Camera2 would leave orientation and overlay sensors stopped until a later Activity resume.
 */
internal object CameraSettingsLaunchTransaction {
    inline fun launch(
        onStarted: () -> Unit,
        show: () -> Unit,
        onShowFailure: (Throwable) -> Unit
    ): Boolean {
        onStarted()
        return try {
            show()
            true
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
            onShowFailure(failure)
            false
        }
    }
}

class CameraSettingsSheetFragment : DialogFragment() {

    companion object {
        const val TAG = "camera_settings_sheet"
        private const val KEY_CONTAINER_ID = "camera_settings_container_id"
    }

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
            setBackgroundResource(R.drawable.bg_settings_screen_surface)
        }

        val header = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ctx.dp(if (strictHighEndEnabled()) 70 else 66)
            )
            setPadding(ctx.dp(18), ctx.dp(6), ctx.dp(18), ctx.dp(6))
            setBackgroundResource(R.drawable.bg_settings_header_surface)
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
        animateHeaderEntrance(header, ctx.dp(10).toFloat())
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
            setBackgroundDrawable(0xFF080A0D.toInt().toDrawable())
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        // Suspend the active camera pipeline (GPU rendering, AI tracking, ISP stream)
        // while the opaque settings menu covers the screen to prevent massive thermal/power leaks.
        // MainActivity starts this at tap time; this idempotent call also covers restored fragments and
        // pauses orientation/overlay sensors that are invisible behind this screen.
        (activity as? CameraSettingsLifecycleHost)?.onSettingsSheetStarted()
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        // onDismiss is the earliest definitive return-to-camera signal. onStop also runs when the app
        // backgrounds, so reopening there could power Camera2 while the app is invisible.
        (activity as? CameraSettingsLifecycleHost)?.onSettingsSheetDismissed()
    }

    private fun android.content.Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun animateHeaderEntrance(header: View, offsetPx: Float) {
        header.alpha = 0f
        header.translationY = -offsetPx
        header.scaleX = 0.99f
        header.scaleY = 0.99f
        header.post {
            if (!header.isAttachedToWindow) return@post
            header.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            header.animate().cancel()
            header.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(360L)
                .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
                .withEndAction { header.setLayerType(View.LAYER_TYPE_NONE, null) }
                .start()
        }
    }
}
