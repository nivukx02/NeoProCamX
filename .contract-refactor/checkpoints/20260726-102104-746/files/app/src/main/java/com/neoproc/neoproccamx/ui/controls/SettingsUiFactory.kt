package com.neoproc.neoproccamx.ui.controls

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.drawable.toDrawable
import com.neoproc.neoproccamx.R
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.recording.ConversionProgress
import com.neoproc.neoproccamx.ui.CameraHapticAction
import kotlin.math.roundToInt

internal data class SettingsRoot(
    val scrollView: ScrollView,
    val content: LinearLayout
)

internal class SettingsUiFactory(
    private val context: Context,
    private val hapticPerformer: ((CameraHapticAction) -> Unit)? = null
) {
    private var currentStyle = SettingsUiStylePolicy.resolve(fullScreen = false)

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    fun createRoot(fullScreen: Boolean): SettingsRoot {
        currentStyle = SettingsUiStylePolicy.resolve(fullScreen)
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(Color.TRANSPARENT)
            clipToPadding = false
            if (fullScreen) {
                setPadding(
                    dp(currentStyle.rootHorizontalPaddingDp),
                    dp(currentStyle.rootTopPaddingDp),
                    dp(currentStyle.rootHorizontalPaddingDp),
                    dp(currentStyle.rootBottomPaddingDp)
                )
            } else {
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            if (fullScreen) {
                setPadding(0, 0, 0, dp(14))
            } else {
                setPadding(
                    dp(currentStyle.rootHorizontalPaddingDp),
                    dp(currentStyle.rootTopPaddingDp),
                    dp(currentStyle.rootHorizontalPaddingDp),
                    dp(currentStyle.rootBottomPaddingDp)
                )
                setBackgroundResource(R.drawable.bg_settings_dialog_surface)
            }
        }
        scroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return SettingsRoot(scroll, root)
    }

    fun addDialogHeader(
        root: LinearLayout,
        title: String,
        subtitle: String
    ) {
        root.addView(TextView(context).apply {
            text = title
            setTextColor(0xFFF7FAFC.toInt())
            textSize = 24f
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            setPadding(0, dp(6), 0, dp(12))
        })
        root.addView(TextView(context).apply {
            text = subtitle
            setTextColor(0xA8FFFFFF.toInt())
            textSize = 12f
            setPadding(0, 0, 0, dp(6))
        })
    }

    fun sectionCard(title: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground(
                fillColor = 0xF2141A21.toInt(),
                radiusDp = currentStyle.sectionCornerRadiusDp.toFloat(),
                strokeColor = 0x1FFFFFFF
            )
            setPadding(
                dp(currentStyle.sectionHorizontalPaddingDp),
                dp(currentStyle.sectionVerticalPaddingDp),
                dp(currentStyle.sectionHorizontalPaddingDp),
                dp(currentStyle.sectionVerticalPaddingDp)
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
            addView(TextView(context).apply {
                text = title
                setTextColor(0xA8FFFFFF.toInt())
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                letterSpacing = 0f
                setPadding(dp(2), dp(4), dp(2), dp(10))
            })
        }

    fun settingTitle(title: String, subtitle: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            addView(TextView(context).apply {
                text = subtitle
                setTextColor(0x8FFFFFFF.toInt())
                textSize = 10f
                setPadding(0, dp(3), 0, 0)
            })
        }

    fun <T> selectorRow(
        title: String,
        subtitle: String,
        options: List<SettingsChoice<T>>,
        selected: T,
        onSelect: (T) -> Unit
    ): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            background = settingsRowBackground()
            setPadding(dp(16), dp(currentStyle.rowVerticalPaddingDp), dp(16), dp(currentStyle.rowVerticalPaddingDp))
            layoutParams = rowLayoutParams()
        }
        var currentSelection = selected
        val selectedLabel = options.firstOrNull { it.value == currentSelection }?.label
            ?: options.firstOrNull()?.label.orEmpty()
        val settingRow = settingRow(title, subtitle, selectedLabel, showChevron = true)
        val valueView = settingRow.findViewWithTag<TextView>(SETTING_VALUE_TAG)
        container.addView(settingRow)
        container.setOnClickListener {
            performClickHaptic()
            showChoiceDialog(
                title = title,
                subtitle = subtitle,
                options = options,
                selected = currentSelection
            ) { newValue, label ->
                currentSelection = newValue
                valueView?.text = label
                onSelect(newValue)
            }
        }
        container.addView(divider())
        return container
    }

    fun toggleRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        onToggle: (Boolean) -> Unit
    ): View {
        val initialGlow = SettingsToggleGlowPolicy.resolve(checked)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = settingsRowBackground(
                fillColor = initialGlow.rowFillColor,
                strokeColor = initialGlow.rowStrokeColor
            )
            elevation = dp(initialGlow.rowElevationDp).toFloat()
            translationZ = dp(initialGlow.rowElevationDp).toFloat()
            setPadding(dp(16), dp(currentStyle.rowVerticalPaddingDp), dp(16), dp(currentStyle.rowVerticalPaddingDp))
            layoutParams = rowLayoutParams()
        }
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            addView(TextView(context).apply {
                text = subtitle
                setTextColor(0x88FFFFFF.toInt())
                textSize = 11f
                setPadding(0, dp(3), 0, 0)
            })
        }
        val switchView = SwitchCompat(context).apply {
            isChecked = checked
            showText = false
            thumbTintList = ColorStateList.valueOf(initialGlow.thumbColor)
            trackTintList = trackTint(checked)
            setOnCheckedChangeListener { _, isCheckedNow ->
                performClickHaptic()
                val glow = SettingsToggleGlowPolicy.resolve(isCheckedNow)
                thumbTintList = ColorStateList.valueOf(glow.thumbColor)
                trackTintList = trackTint(isCheckedNow)
                applyToggleGlow(container, isCheckedNow)
                onToggle(isCheckedNow)
            }
        }
        topRow.isClickable = true
        topRow.isFocusable = true
        topRow.setOnClickListener {
            switchView.isChecked = !switchView.isChecked
        }
        topRow.addView(titleBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topRow.addView(switchView)
        container.addView(topRow)
        container.addView(divider())
        return container
    }

    /**
     * A labelled slider row: title + subtitle on the left, a live value chip on the right, and a
     * SeekBar beneath. [progress] is the initial position in [0, max]; [valueLabel] formats the chip.
     * When [enabled] is false the row is dimmed and non-interactive (matches the bitrate row's UX).
     */
    fun sliderRow(
        title: String,
        subtitle: String,
        max: Int,
        progress: Int,
        enabled: Boolean = true,
        valueLabel: (Int) -> String,
        onProgress: (Int) -> Unit
    ): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = settingsRowBackground()
            setPadding(dp(16), dp(currentStyle.rowVerticalPaddingDp), dp(16), dp(currentStyle.rowVerticalPaddingDp))
            layoutParams = rowLayoutParams()
            alpha = if (enabled) 1f else 0.45f
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            addView(TextView(context).apply {
                text = subtitle
                setTextColor(0x88FFFFFF.toInt())
                textSize = 11f
                setPadding(0, dp(3), 0, 0)
            })
        }
        val valueView = TextView(context).apply {
            text = valueLabel(progress)
            setTextColor(0xFFF6CD4D.toInt())
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        header.addView(titleBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(valueView)
        container.addView(header)
        container.addView(SeekBar(context).apply {
            this.max = max.coerceAtLeast(1)
            this.progress = progress.coerceIn(0, this.max)
            isEnabled = enabled
            thumbTintList = ColorStateList.valueOf(0xFFF6CD4D.toInt())
            progressTintList = ColorStateList.valueOf(0xFFF6CD4D.toInt())
            progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
            setPadding(0, dp(8), 0, dp(2))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    valueView.text = valueLabel(value)
                    if (fromUser) onProgress(value)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = performClickHaptic()
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })
        container.addView(divider())
        return container
    }

    fun actionRow(
        title: String,
        subtitle: String,
        actionLabel: String,
        onClick: () -> Unit
    ): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            background = settingsRowBackground()
            setPadding(dp(16), dp(currentStyle.rowVerticalPaddingDp), dp(16), dp(currentStyle.rowVerticalPaddingDp))
            layoutParams = rowLayoutParams()
        }
        container.addView(settingRow(title, subtitle, actionLabel, showChevron = true))
        container.setOnClickListener {
            performClickHaptic()
            onClick()
        }
        container.addView(divider())
        return container
    }

    /**
     * A row that opens a single-line free-text entry dialog (the only text-input control in Settings).
     * Shows [value] on the right, or [placeholder] when [value] is blank; on confirm the row's value
     * updates in place (no full rebuild needed) and [onValue] fires with the entered text.
     */
    fun textInputRow(
        title: String,
        subtitle: String,
        value: String,
        placeholder: String,
        dialogTitle: String,
        dialogSubtitle: String,
        hint: String,
        maxLength: Int,
        onValue: (String) -> Unit
    ): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            background = settingsRowBackground()
            setPadding(dp(16), dp(currentStyle.rowVerticalPaddingDp), dp(16), dp(currentStyle.rowVerticalPaddingDp))
            layoutParams = rowLayoutParams()
        }
        var currentValue = value
        val settingRow = settingRow(title, subtitle, value.ifBlank { placeholder }, showChevron = true)
        val valueView = settingRow.findViewWithTag<TextView>(SETTING_VALUE_TAG)
        container.addView(settingRow)
        container.setOnClickListener {
            performClickHaptic()
            showTextInputDialog(
                title = dialogTitle,
                subtitle = dialogSubtitle,
                initialValue = currentValue,
                hint = hint,
                maxLength = maxLength
            ) { entered ->
                currentValue = entered
                valueView?.text = entered.ifBlank { placeholder }
                onValue(entered)
            }
        }
        container.addView(divider())
        return container
    }

    fun infoRow(
        title: String,
        value: String,
        valueTag: String? = null
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = settingsRowBackground()
        setPadding(dp(16), dp(currentStyle.rowVerticalPaddingDp), dp(16), dp(currentStyle.rowVerticalPaddingDp))
        layoutParams = rowLayoutParams()
        addView(settingRow(title, "", value, showChevron = false).also { row ->
            if (valueTag != null) {
                row.findViewWithTag<TextView>(SETTING_VALUE_TAG)?.tag = valueTag
            }
        })
        addView(divider())
    }

    /** Views returned by [clipRow]; [thumbnail] is populated asynchronously by the caller. */
    class ClipRow(val view: View, val thumbnail: ImageView)

    /** One labelled action in an [actionSheet]. [destructive] tints the label red. */
    data class SettingsActionChoice(
        val label: String,
        val meta: String = "",
        val destructive: Boolean = false,
        val onClick: () -> Unit
    )

    /**
     * A converter clip row: a thumbnail on the left, name + subtitle in the middle, a chevron on the
     * right; the whole row is clickable (opens a per-clip [actionSheet]). The thumbnail starts as a
     * dark placeholder — the caller renders it OFF the UI thread and sets it on [ClipRow.thumbnail]
     * (the develop is far too slow to run during layout).
     */
    fun clipRow(title: String, subtitle: String, onClick: () -> Unit): ClipRow {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            background = settingsRowBackground()
            setPadding(dp(12), dp(currentStyle.rowVerticalPaddingDp), dp(12), dp(currentStyle.rowVerticalPaddingDp))
        }
        val thumb = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = panelBackground(0x33202832, 8f, 0x1FFFFFFF)
            layoutParams = LinearLayout.LayoutParams(dp(84), dp(56)).apply {
                rightMargin = dp(12)
            }
        }
        val textBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = title
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            addView(TextView(context).apply {
                text = subtitle
                setTextColor(0x88FFFFFF.toInt())
                textSize = 11f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            })
        }
        container.addView(thumb)
        container.addView(textBlock)
        container.addView(TextView(context).apply {
            text = ">"
            setTextColor(0x66FFFFFF)
            textSize = 18f
            setPadding(dp(10), 0, 0, 0)
        })
        container.setOnClickListener {
            performClickHaptic()
            onClick()
        }
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = rowLayoutParams()
            addView(container)
            addView(divider())
        }
        return ClipRow(wrapper, thumb)
    }

    /**
     * A non-cancelable modal with a determinate progress bar + a live count line + an ETA line, for the
     * offline RAW/NPV10 converters. Returns a [ProgressController] the caller drives from the conversion
     * callback (call [ProgressController.update] on the UI thread, [ProgressController.dismiss] at the
     * end). Non-cancelable on purpose: the converter guarantees "convert ALL frames without downgrade",
     * so there is no partial-cancel path that could leave a half-written master.
     */
    fun progressDialog(title: String, subtitle: String): ProgressController {
        val visual = SettingsChoiceVisualPolicy.resolve()
        val dialog = Dialog(context)
        dialog.setCancelable(false)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(22))
            background = panelBackground(
                fillColor = visual.dialogSurfaceColor,
                radiusDp = 28f,
                strokeColor = visual.dialogStrokeColor
            )
        }
        root.addView(TextView(context).apply {
            text = title
            setTextColor(0xFFF5F8FB.toInt())
            textSize = 20f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        if (subtitle.isNotBlank()) {
            root.addView(TextView(context).apply {
                text = subtitle
                setTextColor(0x9FE7EDF2.toInt())
                textSize = 12f
                setPadding(0, dp(6), 0, dp(2))
            })
        }
        val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = PROGRESS_BAR_RESOLUTION
            isIndeterminate = true
            progressTintList = ColorStateList.valueOf(PROGRESS_ACCENT_COLOR)
            indeterminateTintList = ColorStateList.valueOf(PROGRESS_ACCENT_COLOR)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(16)
                bottomMargin = dp(12)
            }
        }
        root.addView(bar)
        val countText = TextView(context).apply {
            text = context.getString(R.string.settings_preparing)
            setTextColor(0xFFF5F8FB.toInt())
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        root.addView(countText)
        val etaText = TextView(context).apply {
            text = context.getString(R.string.settings_estimating)
            setTextColor(0x9FE7EDF2.toInt())
            textSize = 12f
            setPadding(0, dp(4), 0, 0)
        }
        root.addView(etaText)
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            setDimAmount(0.6f)
            setGravity(Gravity.CENTER)
            val metrics = context.resources.displayMetrics
            val width = SettingsChoiceDialogLayoutPolicy.resolveWidthPx(
                screenWidthPx = metrics.widthPixels,
                density = metrics.density,
                strictHighEnd = strictHighEndEnabled()
            )
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        return ProgressController(dialog, bar, countText, etaText)
    }

    /** Handle for a live [progressDialog]; drive [update] from the conversion progress callback. */
    class ProgressController internal constructor(
        private val dialog: Dialog,
        private val bar: ProgressBar,
        private val countText: TextView,
        private val etaText: TextView
    ) {
        /** Push a progress snapshot into the bar + text lines. Must be called on the UI thread. */
        fun update(progress: ConversionProgress) {
            if (progress.isDeterminate) {
                if (bar.isIndeterminate) bar.isIndeterminate = false
                bar.progress = progress.perMille()
            } else if (!bar.isIndeterminate) {
                bar.isIndeterminate = true
            }
            countText.text = progress.countLine()
            etaText.text = progress.etaLine()
        }

        fun dismiss() {
            runCatching { dialog.dismiss() }
        }
    }

    /** A modal list of labelled actions, styled like the choice dialog. */
    fun actionSheet(title: String, subtitle: String, actions: List<SettingsActionChoice>) {
        val visual = SettingsChoiceVisualPolicy.resolve()
        val dialog = Dialog(context)
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(Color.TRANSPARENT)
            clipToPadding = false
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(22))
            background = panelBackground(
                fillColor = visual.dialogSurfaceColor,
                radiusDp = 28f,
                strokeColor = visual.dialogStrokeColor
            )
        }
        scroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(TextView(context).apply {
            text = title
            setTextColor(0xFFF5F8FB.toInt())
            textSize = 20f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        if (subtitle.isNotBlank()) {
            root.addView(TextView(context).apply {
                text = subtitle
                setTextColor(0x9FE7EDF2.toInt())
                textSize = 12f
                setPadding(0, dp(6), 0, dp(14))
            })
        }
        actions.forEach { action ->
            root.addView(actionSheetRow(action, visual) { dialog.dismiss() })
        }
        dialog.setContentView(scroll)
        dialog.window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            setDimAmount(0.5f)
            setGravity(Gravity.CENTER)
            val metrics = context.resources.displayMetrics
            val width = SettingsChoiceDialogLayoutPolicy.resolveWidthPx(
                screenWidthPx = metrics.widthPixels,
                density = metrics.density,
                strictHighEnd = strictHighEndEnabled()
            )
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    private fun actionSheetRow(
        action: SettingsActionChoice,
        visual: SettingsChoiceVisual,
        dismiss: () -> Unit
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        background = panelBackground(
            fillColor = visual.unselectedRowColor,
            radiusDp = visual.rowRadiusDp,
            strokeColor = visual.unselectedStrokeColor
        )
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        }
        addView(TextView(context).apply {
            text = action.label
            setTextColor(if (action.destructive) 0xFFFF6B6B.toInt() else 0xFFF4F7FA.toInt())
            textSize = visual.optionTextSizeSp
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (action.meta.isNotBlank()) {
            addView(TextView(context).apply {
                text = action.meta
                setTextColor(0x8FFFFFFF.toInt())
                textSize = visual.optionMetaTextSizeSp
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
        }
        setOnClickListener {
            performClickHaptic()
            dismiss()
            action.onClick()
        }
    }

    fun divider(): View =
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(8)
            }
            setBackgroundColor(0x0FFFFFFF)
        }

    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private fun dp(value: Float): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private fun <T> showChoiceDialog(
        title: String,
        subtitle: String,
        options: List<SettingsChoice<T>>,
        selected: T,
        onSelect: (T, String) -> Unit
    ) {
        val visual = SettingsChoiceVisualPolicy.resolve()
        val dialog = Dialog(context)
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(Color.TRANSPARENT)
            clipToPadding = false
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(22))
            background = panelBackground(
                fillColor = visual.dialogSurfaceColor,
                radiusDp = 28f,
                strokeColor = visual.dialogStrokeColor
            )
        }
        scroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(TextView(context).apply {
            text = title
            setTextColor(0xFFF5F8FB.toInt())
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        if (subtitle.isNotBlank()) {
            root.addView(TextView(context).apply {
                text = subtitle
                setTextColor(0x9FE7EDF2.toInt())
                textSize = 12f
                setPadding(0, dp(6), 0, dp(14))
            })
        }

        options.forEach { choice ->
            root.addView(
                choiceSheetRow(
                    label = choice.label,
                    selected = choice.value == selected,
                    visual = visual
                ) {
                    onSelect(choice.value, choice.label)
                    dialog.dismiss()
                }
            )
        }

        dialog.setContentView(scroll)
        dialog.window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            setDimAmount(0.5f)
            setGravity(Gravity.CENTER)
            val metrics = context.resources.displayMetrics
            val width = SettingsChoiceDialogLayoutPolicy.resolveWidthPx(
                screenWidthPx = metrics.widthPixels,
                density = metrics.density,
                strictHighEnd = strictHighEndEnabled()
            )
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    /**
     * A single-line text-entry modal that mirrors [showChoiceDialog]'s panel/window styling so it feels
     * native to Settings. Confirming reports the trimmed text (may be blank => caller treats as "clear").
     */
    private fun showTextInputDialog(
        title: String,
        subtitle: String,
        initialValue: String,
        hint: String,
        maxLength: Int,
        onConfirm: (String) -> Unit
    ) {
        val visual = SettingsChoiceVisualPolicy.resolve()
        val dialog = Dialog(context)
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(Color.TRANSPARENT)
            clipToPadding = false
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(22))
            background = panelBackground(
                fillColor = visual.dialogSurfaceColor,
                radiusDp = 28f,
                strokeColor = visual.dialogStrokeColor
            )
        }
        scroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(TextView(context).apply {
            text = title
            setTextColor(0xFFF5F8FB.toInt())
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        if (subtitle.isNotBlank()) {
            root.addView(TextView(context).apply {
                text = subtitle
                setTextColor(0x9FE7EDF2.toInt())
                textSize = 12f
                setPadding(0, dp(6), 0, dp(14))
            })
        }

        val editText = EditText(context).apply {
            setText(initialValue)
            setSelection(initialValue.length)
            this.hint = hint
            setHintTextColor(0x66FFFFFF)
            setTextColor(0xFFF4F7FA.toInt())
            textSize = 16f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            filters = arrayOf(InputFilter.LengthFilter(maxLength))
            background = panelBackground(
                fillColor = visual.unselectedRowColor,
                radiusDp = visual.rowRadiusDp,
                strokeColor = visual.unselectedStrokeColor
            )
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        root.addView(
            editText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        )

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }
        buttonRow.addView(dialogTextButton("Cancel", accent = false) { dialog.dismiss() })
        buttonRow.addView(dialogTextButton("Save", accent = true) {
            onConfirm(editText.text?.toString()?.trim().orEmpty())
            dialog.dismiss()
        })
        root.addView(buttonRow)

        dialog.setContentView(scroll)
        dialog.window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            setDimAmount(0.5f)
            setGravity(Gravity.CENTER)
            val metrics = context.resources.displayMetrics
            val width = SettingsChoiceDialogLayoutPolicy.resolveWidthPx(
                screenWidthPx = metrics.widthPixels,
                density = metrics.density,
                strictHighEnd = strictHighEndEnabled()
            )
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.show()
        editText.requestFocus()
    }

    private fun dialogTextButton(
        label: String,
        accent: Boolean,
        onClick: () -> Unit
    ): View = TextView(context).apply {
        text = label
        setTextColor(if (accent) 0xFFF8CC55.toInt() else 0xC8FFFFFF.toInt())
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", if (accent) Typeface.BOLD else Typeface.NORMAL)
        isClickable = true
        isFocusable = true
        setPadding(dp(18), dp(10), dp(18), dp(10))
        background = panelBackground(
            fillColor = if (accent) 0x33F8CC55 else 0x14FFFFFF,
            radiusDp = 16f,
            strokeColor = if (accent) 0xFFF8CC55.toInt() else 0x1FFFFFFF
        )
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(10) }
        setOnClickListener {
            performClickHaptic()
            onClick()
        }
    }

    private fun choiceSheetRow(
        label: String,
        selected: Boolean,
        visual: SettingsChoiceVisual,
        onClick: () -> Unit
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        background = panelBackground(
            fillColor = if (selected) visual.selectedRowColor else visual.unselectedRowColor,
            radiusDp = visual.rowRadiusDp,
            strokeColor = if (selected) visual.selectedStrokeColor else visual.unselectedStrokeColor
        )
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        }
        addView(TextView(context).apply {
            text = label
            setTextColor(if (selected) 0xFFF8CC55.toInt() else 0xFFF4F7FA.toInt())
            textSize = visual.optionTextSizeSp
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.create(
                "sans-serif-medium",
                if (selected && visual.selectedTextBold) Typeface.BOLD else Typeface.NORMAL
            )
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(context).apply {
            text = if (selected) "Selected" else "Choose"
            setTextColor(if (selected) 0xFFF8CC55.toInt() else 0x8FFFFFFF.toInt())
            textSize = visual.optionMetaTextSizeSp
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        setOnClickListener {
            performClickHaptic()
            onClick()
        }
    }

    private fun panelBackground(
        fillColor: Int,
        radiusDp: Float,
        strokeColor: Int = 0x1FFFFFFF
    ): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            gradientType = GradientDrawable.LINEAR_GRADIENT
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            colors = intArrayOf(fillColor, fillColor)
            if (strokeColor != 0) {
                setStroke(dp(1), strokeColor)
            }
        }

    private fun settingsRowBackground(
        fillColor: Int = 0xB8141B22.toInt(),
        strokeColor: Int = 0x1FFFFFFF
    ): GradientDrawable =
        panelBackground(
            fillColor = fillColor,
            radiusDp = currentStyle.rowCornerRadiusDp.toFloat(),
            strokeColor = strokeColor
        )

    private fun applyToggleGlow(container: View, checked: Boolean) {
        val glow = SettingsToggleGlowPolicy.resolve(checked)
        container.background = settingsRowBackground(
            fillColor = glow.rowFillColor,
            strokeColor = glow.rowStrokeColor
        )
        val targetZ = dp(glow.rowElevationDp).toFloat()
        container.animate().cancel()
        container.elevation = targetZ
        if (checked) {
            container.animate()
                .translationZ(targetZ)
                .scaleX(1.012f)
                .scaleY(1.012f)
                .setDuration(110L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction {
                    container.animate()
                        .translationZ(targetZ)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(90L)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                }
                .start()
        } else {
            container.animate()
                .translationZ(targetZ)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(140L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    private fun rowLayoutParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(4)
        }

    private fun settingRow(
        title: String,
        subtitle: String,
        value: String,
        showChevron: Boolean
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val topLine = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topLine.addView(TextView(context).apply {
            text = title
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        topLine.addView(TextView(context).apply {
            tag = SETTING_VALUE_TAG
            text = value
            setTextColor(0xFFF6CD4D.toInt())
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            gravity = Gravity.END
            maxWidth = dp(if (strictHighEndEnabled()) 168 else 148)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        if (showChevron) {
            topLine.addView(TextView(context).apply {
                text = ">"
                setTextColor(0x66FFFFFF)
                textSize = 18f
                setPadding(dp(10), 0, 0, 0)
            })
        }
        row.addView(topLine)
        if (subtitle.isNotBlank()) {
            row.addView(TextView(context).apply {
                text = subtitle
                setTextColor(0x88FFFFFF.toInt())
                textSize = 12f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            })
        }
        return row
    }

    private fun trackTint(checked: Boolean): ColorStateList =
        ColorStateList.valueOf(SettingsToggleGlowPolicy.resolve(checked).trackColor)

    private fun performClickHaptic() {
        hapticPerformer?.invoke(CameraHapticAction.BUTTON_CLICK)
    }

    private companion object {
        const val SETTING_VALUE_TAG = "settingValue"

        // Progress bar granularity (per-mille) + accent tint for the conversion progress dialog.
        const val PROGRESS_BAR_RESOLUTION = 1000
        const val PROGRESS_ACCENT_COLOR = 0xFF5AC8FA.toInt()
    }
}
