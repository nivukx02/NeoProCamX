package com.neoproc.neoproccamx.ui.health

import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.neoproc.neoproccamx.R
import com.neoproc.neoproccamx.monitor.HealthState
import com.neoproc.neoproccamx.monitor.ModuleHealthStatus
import com.neoproc.neoproccamx.monitor.MonitoredModule
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Implemented by the host Activity so the panel can observe live health and update while open. */
interface HealthDiagnosticsHost {
    val healthStatusFlow: StateFlow<Map<MonitoredModule, ModuleHealthStatus>>
}

/**
 * Tap-to-open diagnostics for one module. Renders the full spec panel — state, reason, runtime
 * exception, pipeline stage, recovery attempts/result, current fallback, performance impact, suggested
 * solution, timestamps and live metrics — and refreshes itself as the monitor publishes new status,
 * so the panel never goes stale while the user is looking at it.
 */
class HealthDiagnosticsDialogFragment : DialogFragment() {

    private lateinit var module: MonitoredModule
    private val values = LinkedHashMap<String, TextView>()
    private lateinit var stateValue: TextView
    private lateinit var metricsBlock: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        module = runCatching { MonitoredModule.valueOf(requireArguments().getString(ARG_MODULE)!!) }
            .getOrDefault(MonitoredModule.PROV_CORE)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF00A0A0A.toInt())
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }

        root.addView(TextView(ctx).apply {
            text = module.displayLabel
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        stateValue = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(2), 0, dp(12))
        }
        root.addView(stateValue)

        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val fields = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(fields)
        root.addView(scroll)

        for (label in FIELD_ORDER) {
            values[label] = addRow(fields, label)
        }
        fields.addView(TextView(ctx).apply {
            text = getString(R.string.health_live_metrics)
            setTextColor(0xFF8AB4F8.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(10), 0, dp(4))
        })
        metricsBlock = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fields.addView(metricsBlock)

        root.addView(TextView(ctx).apply {
            text = getString(R.string.close)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(14), 0, dp(4))
            setOnClickListener { dismissAllowingStateLoss() }
        })

        observeHealth()
        return root
    }

    private fun observeHealth() {
        val host = activity as? HealthDiagnosticsHost ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                host.healthStatusFlow.collect { map ->
                    map[module]?.let { render(it) }
                }
            }
        }
    }

    private fun render(status: ModuleHealthStatus) {
        val now = SystemClock.elapsedRealtime()
        val recovering = status.recovering && status.state == HealthState.ERROR
        stateValue.text = getString(R.string.health_state_format, status.displayStateLabel)
        stateValue.setTextColor(
            if (recovering) HealthChipVisualPolicy.COLOR_RECOVERING else stateColor(status.state)
        )
        val d = status.diagnostics
        set("Reason", status.reason)
        set("Runtime exception", d.runtimeException)
        set("Pipeline stage", d.pipelineStage)
        set("Current fallback", d.currentFallback)
        set("Performance impact", d.performanceImpact)
        set("Suggested solution", d.suggestedSolution)
        set("Recovery attempts", status.recoveryAttempts.toString())
        set("Recovery result", status.recoveryResult)
        set("In state for", "${((now - status.stateSinceElapsedMs).coerceAtLeast(0L)) / 1000}s")
        set("Updated", "${((now - status.updatedElapsedMs).coerceAtLeast(0L))} ms ago")

        metricsBlock.removeAllViews()
        val ctx = requireContext()
        if (d.metrics.isEmpty()) {
            metricsBlock.addView(TextView(ctx).apply {
                text = "—"
                setTextColor(0xFFBBBBBB.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
        } else {
            for ((k, v) in d.metrics) {
                metricsBlock.addView(TextView(ctx).apply {
                    text = getString(R.string.health_metric_format, k, v)
                    setTextColor(0xFFDDDDDD.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, dp(1), 0, dp(1))
                })
            }
        }
    }

    private fun set(label: String, value: String?) {
        values[label]?.text = value?.takeIf { it.isNotBlank() } ?: "—"
    }

    private fun addRow(parent: LinearLayout, label: String): TextView {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        row.addView(TextView(ctx).apply {
            text = label
            setTextColor(0xFF9AA0A6.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })
        val value = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            text = "—"
        }
        row.addView(value)
        parent.addView(row)
        return value
    }

    private fun stateColor(state: HealthState): Int = when (state) {
        HealthState.ACTIVE -> HealthChipVisualPolicy.COLOR_ACTIVE
        HealthState.READY -> HealthChipVisualPolicy.COLOR_READY
        HealthState.HEALTHY -> HealthChipVisualPolicy.COLOR_HEALTHY
        HealthState.UNKNOWN -> HealthChipVisualPolicy.COLOR_UNKNOWN
        HealthState.WARNING -> HealthChipVisualPolicy.COLOR_WARNING
        HealthState.ERROR -> HealthChipVisualPolicy.COLOR_ERROR
        HealthState.INACTIVE -> HealthChipVisualPolicy.COLOR_INACTIVE
        HealthState.UNSUPPORTED -> HealthChipVisualPolicy.COLOR_UNSUPPORTED
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val TAG = "health_diagnostics_sheet"
        private const val ARG_MODULE = "module"
        private val FIELD_ORDER = listOf(
            "Reason",
            "Runtime exception",
            "Pipeline stage",
            "Current fallback",
            "Performance impact",
            "Suggested solution",
            "Recovery attempts",
            "Recovery result",
            "In state for",
            "Updated"
        )

        fun newInstance(module: MonitoredModule): HealthDiagnosticsDialogFragment =
            HealthDiagnosticsDialogFragment().apply {
                arguments = Bundle().apply { putString(ARG_MODULE, module.name) }
            }
    }
}
