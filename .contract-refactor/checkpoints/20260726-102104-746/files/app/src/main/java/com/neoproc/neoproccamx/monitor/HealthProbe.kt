package com.neoproc.neoproccamx.monitor

import com.neoproc.neoproccamx.monitor.HealthThresholds.COMPUTE_LATENCY_ERROR_MS
import com.neoproc.neoproccamx.monitor.HealthThresholds.COMPUTE_LATENCY_WARN_MS
import com.neoproc.neoproccamx.monitor.HealthThresholds.DROP_ESTIMATE_WARN
import com.neoproc.neoproccamx.monitor.HealthThresholds.FRAME_STALL_ERROR_MS
import com.neoproc.neoproccamx.monitor.HealthThresholds.FRAME_STALL_WARN_MS
import com.neoproc.neoproccamx.monitor.HealthThresholds.IMPORT_FAILURE_ERROR
import com.neoproc.neoproccamx.monitor.HealthThresholds.IMPORT_FAILURE_WARN

/**
 * A pure verifier for one [MonitoredModule]. Given a [RuntimeSignalSnapshot] of live signals it
 * returns the module's current [ModuleHealthReading]. No side effects, no clocks, no Android — every
 * branch is reproducible in a unit test.
 *
 * Cardinal rule: only emit [HealthState.HEALTHY] when a live signal POSITIVELY confirms the module is
 * operating; a missing/ambiguous signal degrades to WARNING or INACTIVE, never a fabricated healthy.
 */
interface HealthProbe {
    val module: MonitoredModule
    fun evaluate(s: RuntimeSignalSnapshot): ModuleHealthReading
}

/**
 * PROV CORE — end-to-end pipeline liveness: session active, Vulkan pipeline up, frames flowing,
 * buffer queue healthy, no surfaced exception. Active in preview and recording.
 */
object ProvCoreHealthProbe : HealthProbe {
    override val module = MonitoredModule.PROV_CORE

    override fun evaluate(s: RuntimeSignalSnapshot): ModuleHealthReading {
        if (!s.cameraOpen) {
            return reading(
                HealthState.INACTIVE,
                "Camera session closed",
                HealthDiagnostics(
                    pipelineStage = "Camera session",
                    suggestedSolution = "Open the camera to begin monitoring."
                )
            )
        }

        // Hard faults — surfaced exceptions / Error FSM states / hardware rejection. Shown immediately.
        s.cameraErrorMessage?.let { msg ->
            return reading(
                HealthState.ERROR,
                "Camera pipeline error",
                HealthDiagnostics(
                    failureReason = "Camera state machine reported an error.",
                    runtimeException = msg,
                    pipelineStage = "Camera2 CaptureSession",
                    performanceImpact = "Preview/record unavailable until the session recovers.",
                    suggestedSolution = "Reopen the camera; if it persists, restart the app."
                ),
                hardFault = true,
                recoverable = false
            )
        }
        s.gpuRecordErrorMessage?.let { msg ->
            if (isStrictHighEndAdmissionRefusal(msg)) {
                return reading(
                    HealthState.WARNING,
                    "Strict quality admission refused",
                    HealthDiagnostics(
                        failureReason = "Recording was refused because the device cannot currently guarantee the requested high-end session without a quality downgrade.",
                        runtimeException = msg,
                        pipelineStage = "Recording admission control",
                        performanceImpact = "Recording did not start; preview remains live and no lower-quality fallback was used.",
                        suggestedSolution = "Cool the device or retry when thermal/storage headroom is available.",
                        metrics = baseMetrics(s)
                    )
                )
            }
            return reading(
                HealthState.ERROR,
                "Encoder pipeline error",
                HealthDiagnostics(
                    failureReason = "The recording encoder reported an error.",
                    runtimeException = msg,
                    pipelineStage = "MediaCodec encoder",
                    performanceImpact = "Recording may have stopped or failed to start.",
                    suggestedSolution = "Stop and restart the recording."
                ),
                hardFault = true,
                recoverable = false
            )
        }

        // Buffer-queue / GPU-import health (systemic backpressure).
        if (s.consecutiveFailedImports >= IMPORT_FAILURE_ERROR || !s.vulkanPipelineHealthy) {
            return reading(
                HealthState.ERROR,
                "Buffer queue unhealthy",
                HealthDiagnostics(
                    failureReason = "${s.consecutiveFailedImports} consecutive GPU buffer imports failed.",
                    pipelineStage = "GPU buffer import queue",
                    performanceImpact = "Frames are being dropped from the processing queue.",
                    suggestedSolution = "Non-destructive recovery will reset the import queue.",
                    metrics = baseMetrics(s)
                ),
                recoverable = true
            )
        }

        // Frame-delivery liveness. There is no render-thread flag on this device (Choreographer on the
        // main thread), so liveness is inferred honestly from the capture-result delivery pulse.
        val stall = s.frameStallMs()
        if (stall != null && stall >= FRAME_STALL_ERROR_MS) {
            return reading(
                HealthState.ERROR,
                "Frame delivery stalled",
                HealthDiagnostics(
                    failureReason = "No camera frame delivered for ${stall} ms.",
                    pipelineStage = "Camera2 frame delivery",
                    performanceImpact = "Preview is frozen; recording may be dropping frames.",
                    suggestedSolution = "Recovery will re-probe the pipeline; reopen the camera if it persists.",
                    metrics = baseMetrics(s)
                ),
                recoverable = true
            )
        }

        // Degraded (WARNING) conditions.

        // A surfaced advisory (thermal throttle, background/battery restriction, memory pressure) is
        // INFORMATIONAL — not a pipeline hardware fault. Genuine hardware faults arrive through the
        // dedicated cameraErrorMessage / gpuRecordErrorMessage / import-failure / frame-stall signals
        // above (all still hard ERRORs). Surfacing the advisory as a WARNING keeps it honest and
        // visible without fabricating a "hardware fault" or spinning an import-health recovery that
        // can never clear a UI advisory string (e.g. a startup battery-optimization notice that used
        // to escalate PROV_CORE to a red ERROR while the EGL/GPU pipeline was actually healthy).
        // A genuine hardware advisory (thermal throttle / background-battery restriction / memory /
        // storage). Show the EXACT classified reason instead of a generic "Hardware advisory" — and note
        // that the DESCRIPTIVE capability report (HAL Pro Cinema 83/100 / SENSOR RAW LIMITED ISP) is NOT
        // routed here any more (CameraController no longer emits it as a warning), so a real, present
        // capability never masquerades as a fault. Descriptive capability limitations are surfaced as
        // diagnostics metrics below, never as a WARNING state.
        s.hardwareWarning?.let { msg ->
            return reading(
                HealthState.WARNING,
                classifyAdvisory(msg),
                HealthDiagnostics(
                    failureReason = "Hardware advisory: $msg",
                    runtimeException = msg,
                    pipelineStage = "Camera HAL / hardware",
                    performanceImpact = "The affected capability may be degraded; the pipeline remains live.",
                    suggestedSolution = "Informational — no pipeline action required.",
                    metrics = baseMetrics(s)
                )
            )
        }

        if (!s.vulkanSupported) {
            return reading(
                HealthState.WARNING,
                "GPU pipeline unavailable — CPU fallback",
                HealthDiagnostics(
                    failureReason = "Vulkan runtime is not supported/initialized on this device.",
                    pipelineStage = "Vulkan runtime",
                    currentFallback = "CPU compatibility path",
                    performanceImpact = "Reduced throughput; GPU-accelerated processing is off.",
                    suggestedSolution = "None required — the CPU path is functioning.",
                    metrics = baseMetrics(s)
                )
            )
        }
        if (stall != null && stall >= FRAME_STALL_WARN_MS) {
            return reading(
                HealthState.WARNING,
                "Frame delivery slow (${stall} ms gap)",
                HealthDiagnostics(
                    pipelineStage = "Camera2 frame delivery",
                    performanceImpact = "Occasional stutter possible.",
                    metrics = baseMetrics(s)
                )
            )
        }
        if (s.consecutiveFailedImports >= IMPORT_FAILURE_WARN) {
            return reading(
                HealthState.WARNING,
                "Intermittent buffer import failures",
                HealthDiagnostics(
                    pipelineStage = "GPU buffer import queue",
                    performanceImpact = "Minor frame loss possible.",
                    metrics = baseMetrics(s)
                )
            )
        }
        if (s.recording && s.dropEstimate >= DROP_ESTIMATE_WARN) {
            return reading(
                HealthState.WARNING,
                "${s.dropEstimate} dropped frame(s)",
                HealthDiagnostics(
                    pipelineStage = "Record frame queue",
                    performanceImpact = "Dropped frames in the current recording.",
                    metrics = baseMetrics(s)
                )
            )
        }

        // No frame has ever been delivered this session (stall is null) — liveness is UNPROVEN, so we
        // must not claim HEALTHY/ACTIVE. Honestly report UNKNOWN "awaiting first frame" until a real
        // delivery pulse resolves it.
        if (stall == null) {
            return reading(
                HealthState.UNKNOWN,
                "Awaiting first frame",
                HealthDiagnostics(
                    failureReason = "No camera frame has been delivered yet this session.",
                    pipelineStage = "Camera2 frame delivery",
                    performanceImpact = "Pipeline liveness not yet confirmed.",
                    metrics = baseMetrics(s)
                )
            )
        }

        // Frames are positively flowing and every checkable signal is healthy — the pipeline is ACTIVELY
        // delivering (capture → process → preview/encode). ACTIVE (not merely HEALTHY) because a live
        // per-frame delivery pulse positively confirms work is happening this instant.
        return reading(
            HealthState.ACTIVE,
            if (s.recording) "Pipeline active — recording" else "Pipeline active — preview",
            HealthDiagnostics(
                pipelineStage = if (s.recording) "Capture → process → encode" else "Capture → process → preview",
                metrics = baseMetrics(s)
            )
        )
    }

    /** Turn an opaque advisory string into a specific, honest reason (never a generic "advisory"). */
    private fun classifyAdvisory(msg: String): String = when {
        msg.contains("thermal", ignoreCase = true) -> "Thermal throttling"
        msg.contains("memory", ignoreCase = true) -> "Memory pressure"
        msg.contains("battery", ignoreCase = true) ||
            msg.contains("background", ignoreCase = true) ||
            msg.contains("restrict", ignoreCase = true) -> "Background/battery restriction"
        msg.contains("storage", ignoreCase = true) -> "Storage throughput advisory"
        msg.contains("timeout", ignoreCase = true) -> "Camera hardware timeout"
        else -> "Advisory: " + msg.take(40)
    }

    private fun isStrictHighEndAdmissionRefusal(msg: String): Boolean =
        msg.contains("strict high-end recording refused", ignoreCase = true) &&
            msg.contains("without quality downgrade", ignoreCase = true)

    private fun baseMetrics(s: RuntimeSignalSnapshot): List<Pair<String, String>> =
        buildList {
            add("Preview FPS" to fps(s.previewFps))
            add("Imported frames" to s.sessionImportedFrames.toString())
            add("Encoder active" to yn(s.encoderActive))
            // Issue 3: the REAL, hardware-interrogated capability report — descriptive, never a fault.
            s.capabilityBadge?.let { add("HAL profile" to it) }
            s.capabilityScore?.let { add("Capability score" to "$it/100") }
            s.capabilityLimitations.forEachIndexed { i, lim -> add("Limitation ${i + 1}" to lim) }
            addAll(runtimeDiagnosticsMetrics(s))
        }

    private fun reading(state: HealthState, reason: String, d: HealthDiagnostics, hardFault: Boolean = false, recoverable: Boolean = false) =
        ModuleHealthReading(module, state, reason, d, hardFault, recoverable)
}

/**
 * P010 — is the active recording transport genuinely 10-bit, with no silent 8-bit / NV12 fallback.
 * Recording-only: INACTIVE in preview. Honesty is critical here — encoder-output profile read-back is
 * device-gated, so "not reported" is surfaced as NOT-PROVEN rather than a healthy claim.
 */
object P010HealthProbe : HealthProbe {
    override val module = MonitoredModule.P010

    override fun evaluate(s: RuntimeSignalSnapshot): ModuleHealthReading {
        if (!s.recording) {
            // Issue 2: preview must not read a permanent INACTIVE. Detect the real 10-bit P010 capability
            // and report READY when it is present. Honesty rule: only claim the live PREVIEW pixels are
            // 10-bit when an HDR DynamicRangeProfile is actually applied — otherwise the capability is a
            // (proven) recording capability while the SDR preview surface itself is 8-bit.
            val capable = s.deviceTenBitHdrSupported || s.encoderP010Supported
            return when {
                s.previewTenBitProfileApplied -> reading(
                    HealthState.READY,
                    "Ready — 10-bit HDR preview live",
                    HealthDiagnostics(
                        pipelineStage = "Preview 10-bit HDR (DynamicRangeProfile applied)",
                        performanceImpact = "Live preview surface is running a 10-bit HDR transfer.",
                        metrics = metrics(s)
                    )
                )
                capable -> reading(
                    HealthState.READY,
                    "Ready — 10-bit P010 recording available",
                    HealthDiagnostics(
                        pipelineStage = "P010 transport armed",
                        performanceImpact = "10-bit P010 recording is supported; the SDR preview surface itself is 8-bit.",
                        suggestedSolution = "Start a recording to engage the 10-bit transport.",
                        metrics = metrics(s)
                    )
                )
                else -> reading(
                    HealthState.INACTIVE,
                    "10-bit standing by",
                    HealthDiagnostics(
                        pipelineStage = "Recording transport",
                        performanceImpact = "10-bit P010 capability not reported for this device/mode yet.",
                        suggestedSolution = "Start a recording to verify the 10-bit transport.",
                        metrics = metrics(s)
                    )
                )
            }
        }

        // A hard-failed 10-bit prepare surfaces as a GpuRecordingState.Error already reported by PROV;
        // here we react to the explicit 8-bit fallback tripwire.
        if (s.simulatedTenBitFallbackActive) {
            return reading(
                HealthState.ERROR,
                "Fell back to 8-bit",
                HealthDiagnostics(
                    failureReason = "Hardware could not honor a real 10-bit (P010/Main10) transport.",
                    pipelineStage = "Encoder transport negotiation",
                    currentFallback = "8-bit encoding",
                    performanceImpact = "Recording is 8-bit, not 10-bit.",
                    suggestedSolution = "Use a resolution/fps the HW Main10 encoder supports.",
                    metrics = metrics(s)
                ),
                hardFault = true,
                recoverable = false
            )
        }

        return when (s.recordingTransportLabel) {
            "P010" -> judgeByteBufferP010(s)
            "AHB_GPU_COMPATIBILITY" -> judgeGpuSurface(s)
            "ENCODER_SURFACE_DIRECT" -> judgeDirectSurface(s)
            null -> reading(
                HealthState.WARNING,
                "Transport not yet resolved",
                HealthDiagnostics(
                    pipelineStage = "Encoder transport negotiation",
                    performanceImpact = "10-bit not yet confirmed for this session.",
                    metrics = metrics(s)
                )
            )
            else -> judgeDirectSurface(s)
        }
    }

    /** CPU BYTEBUFFER_P010 arm: the prepare gate hard-requires COLOR_FormatYUVP010 + HEVC-Main10, so
     *  an active arm with a 10-bit source is genuine P010. Only a proven 8-bit output demotes it. */
    private fun judgeByteBufferP010(s: RuntimeSignalSnapshot): ModuleHealthReading {
        if (s.encoderOutputTenBit == false) {
            return reading(
                HealthState.ERROR,
                "Encoder output is 8-bit",
                HealthDiagnostics(
                    failureReason = "Encoder reported an 8-bit output profile despite a P010 input.",
                    pipelineStage = "HEVC encoder output",
                    currentFallback = "8-bit output",
                    performanceImpact = "Recorded file is not 10-bit.",
                    metrics = metrics(s)
                ),
                hardFault = true,
                recoverable = false
            )
        }
        if (s.p010SourceBitDepth != null && s.p010SourceBitDepth < 10) {
            return reading(
                HealthState.WARNING,
                "P010 source below 10-bit",
                HealthDiagnostics(
                    pipelineStage = "Camera P010 ImageReader",
                    performanceImpact = "Source is ${s.p010SourceBitDepth}-bit.",
                    metrics = metrics(s)
                )
            )
        }
        val reason = if (s.encoderOutputTenBit == true) {
            "Active — true P010 10-bit (output profile proven)"
        } else {
            "Active — true P010 10-bit (encoder profile not reported by device)"
        }
        return reading(
            HealthState.ACTIVE,
            reason,
            HealthDiagnostics(
                pipelineStage = "P010 bytebuffer → HEVC Main10",
                suggestedSolution = if (s.encoderOutputTenBit == null) {
                    "10-bit is proven at source + encoder config; the muxed profile atom was not reported (device-gated)."
                } else null,
                metrics = metrics(s)
            )
        )
    }

    /** GPU AHB/EGL arm: 10-bit is proven through the encoder surface precision, not an app bytebuffer. */
    private fun judgeGpuSurface(s: RuntimeSignalSnapshot): ModuleHealthReading {
        if (s.encoderSurfacePrecisionFallback) {
            return reading(
                HealthState.ERROR,
                "GPU surface fell back below 10-bit",
                HealthDiagnostics(
                    failureReason = "The GPU encoder surface could not stay at 10-bit precision.",
                    pipelineStage = "Vulkan encoder surface",
                    currentFallback = "Reduced precision",
                    performanceImpact = "Recording precision is below 10-bit.",
                    metrics = metrics(s)
                ),
                hardFault = true,
                recoverable = false
            )
        }
        if (s.encoderSurfacePrecisionProven && (s.encoderSurfaceBitDepth ?: 0) >= 10) {
            return reading(
                HealthState.ACTIVE,
                "Active — GPU 10-bit encoder surface (proven)",
                HealthDiagnostics(
                    pipelineStage = "Vulkan PACKED_10_BIT encoder surface",
                    metrics = metrics(s)
                )
            )
        }
        return reading(
            HealthState.WARNING,
            "10-bit not yet proven on GPU surface",
            HealthDiagnostics(
                failureReason = "The GPU encoder surface has not reported proven 10-bit precision.",
                pipelineStage = "Vulkan encoder surface",
                performanceImpact = "10-bit is not yet confirmed for this session (NOT_PROVEN).",
                metrics = metrics(s)
            )
        )
    }

    /** HAL-direct surface: the app does not control the pixel format; only a proven output profile confirms 10-bit. */
    private fun judgeDirectSurface(s: RuntimeSignalSnapshot): ModuleHealthReading =
        when (s.encoderOutputTenBit) {
            true -> reading(
                HealthState.ACTIVE,
                "Active — direct surface 10-bit (output profile proven)",
                HealthDiagnostics(pipelineStage = "HAL-direct encoder surface", metrics = metrics(s))
            )
            false -> reading(
                HealthState.ERROR,
                "Direct surface output is 8-bit",
                HealthDiagnostics(
                    failureReason = "Encoder reported an 8-bit output profile.",
                    pipelineStage = "HAL-direct encoder surface",
                    currentFallback = "8-bit output",
                    performanceImpact = "Recorded file is not 10-bit.",
                    metrics = metrics(s)
                ),
                hardFault = true
            )
            null -> reading(
                HealthState.WARNING,
                "HAL-direct transport — 10-bit not app-verified",
                HealthDiagnostics(
                    pipelineStage = "HAL-direct encoder surface",
                    performanceImpact = "The app cannot prove 10-bit on this transport (device-gated).",
                    metrics = metrics(s)
                )
            )
        }

    private fun metrics(s: RuntimeSignalSnapshot): List<Pair<String, String>> = listOf(
        "10-bit enabled" to yn(s.previewTenBitProfileApplied || s.encoderOutputTenBit == true || s.encoderSurfacePrecisionProven),
        "Transport" to (s.recordingTransportLabel ?: "—"),
        "Feed path" to (s.activeFeedPath ?: "—"),
        "Source bit depth" to (s.p010SourceBitDepth?.let { "$it-bit" } ?: "—"),
        "Surface bit depth" to (s.encoderSurfaceBitDepth?.let { "$it-bit" } ?: "—"),
        "Encoder 10-bit" to when (s.encoderOutputTenBit) { true -> "proven"; false -> "8-bit"; null -> "not reported" },
        "Device 10-bit HDR" to yn(s.deviceTenBitHdrSupported),
        "Encoder Main10 (P010)" to yn(s.encoderP010Supported),
        "Preview 10-bit applied" to yn(s.previewTenBitProfileApplied),
        "Transfer characteristics" to (s.previewTransferCharacteristics ?: "—")
    ) + runtimeDiagnosticsMetrics(s)

    private fun reading(state: HealthState, reason: String, d: HealthDiagnostics, hardFault: Boolean = false, recoverable: Boolean = false) =
        ModuleHealthReading(module, state, reason, d, hardFault, recoverable)
}

/**
 * LAB — the GPU compute processing path: runtime initialized, compute pipeline dispatching, latency
 * within budget, no processing failure. Active in preview and recording. INACTIVE when the device has
 * no Vulkan support (the module is intentionally not engaged, not "broken").
 */
object LabHealthProbe : HealthProbe {
    override val module = MonitoredModule.LAB

    override fun evaluate(s: RuntimeSignalSnapshot): ModuleHealthReading {
        val summary = s.capabilitySummary
        if (summary.contains("init failed", ignoreCase = true)) {
            return reading(
                HealthState.ERROR,
                "GPU runtime init failed",
                HealthDiagnostics(
                    failureReason = "The Vulkan runtime failed to initialize.",
                    runtimeException = summary,
                    pipelineStage = "Vulkan runtime init",
                    currentFallback = "CPU compatibility path",
                    performanceImpact = "GPU-accelerated processing is unavailable.",
                    suggestedSolution = "Recovery will attempt to re-probe GPU capabilities."
                ),
                hardFault = true,
                recoverable = true
            )
        }
        if (!s.vulkanSupported) {
            // Distinguish "still initializing" from a genuinely-absent Vulkan capability. The runtime
            // initializes asynchronously at cold start; until it settles, `vulkanSupported` is
            // transiently false while the capability summary is still the pre-init sentinel. A
            // not-yet-proven signal must degrade to UNKNOWN — never a fabricated definite UNSUPPORTED.
            if (s.capabilitySummary.contains("not initialized", ignoreCase = true) ||
                s.capabilitySummary.isBlank()
            ) {
                return reading(
                    HealthState.UNKNOWN,
                    "GPU runtime initializing",
                    HealthDiagnostics(
                        pipelineStage = "Vulkan runtime init",
                        performanceImpact = "GPU support is not yet determined.",
                        metrics = metrics(s)
                    )
                )
            }
            return reading(
                HealthState.UNSUPPORTED,
                "GPU compute unsupported — CPU path",
                HealthDiagnostics(
                    pipelineStage = "Vulkan compute",
                    currentFallback = "CPU compatibility path",
                    performanceImpact = "Vulkan is not available on this device; the CPU path is used instead.",
                    metrics = metrics(s)
                )
            )
        }
        if (!s.vulkanPipelineHealthy || s.consecutiveFailedImports >= IMPORT_FAILURE_ERROR) {
            return reading(
                HealthState.ERROR,
                "GPU processing failing",
                HealthDiagnostics(
                    failureReason = "${s.consecutiveFailedImports} consecutive GPU operations failed.",
                    pipelineStage = "Vulkan compute dispatch",
                    performanceImpact = "Processing output is degraded or dropping.",
                    suggestedSolution = "Non-destructive recovery will refresh the GPU pipeline.",
                    metrics = metrics(s)
                ),
                recoverable = true
            )
        }

        // Gate every "active work" claim on a FRESH dispatch this instant. `computeDispatched` is the
        // per-tick dispatch-count ADVANCE (not the lifetime-cumulative native counter, which never
        // resets), so when compute is not dispatching now — e.g. idle preview on this device, whose
        // on-screen preview uses the zero-copy direct-camera path and engages GPU compute only on the
        // capture/record pipeline — the module is armed & idle (READY). A persisted EMA average must
        // NOT be read as ACTIVE/WARNING/ERROR when no dispatch is actually happening.
        if (!s.computeDispatched) {
            return reading(
                HealthState.READY,
                "GPU pipeline armed — engages on capture/record",
                HealthDiagnostics(
                    pipelineStage = "Vulkan compute dispatch",
                    performanceImpact = "GPU compute is built and ready; it dispatches on the capture/record path.",
                    suggestedSolution = "Timing and dispatch counts appear once processing runs.",
                    metrics = metrics(s)
                )
            )
        }

        // Actively dispatching (fresh advance) — evaluate the live per-dispatch latency.
        val avg = s.computeAverageMs
        if (avg != null) {
            if (avg > COMPUTE_LATENCY_ERROR_MS) {
                return reading(
                    HealthState.ERROR,
                    "GPU latency critical (${fmtMs(avg)})",
                    HealthDiagnostics(
                        failureReason = "GPU compute latency exceeds the error budget.",
                        pipelineStage = "Vulkan compute dispatch",
                        performanceImpact = "Frame processing cannot keep up; drops likely.",
                        suggestedSolution = "Recovery will re-probe; lower resolution/fps if it persists.",
                        metrics = metrics(s)
                    ),
                    recoverable = true
                )
            }
            if (avg > COMPUTE_LATENCY_WARN_MS) {
                return reading(
                    HealthState.WARNING,
                    "GPU latency high (${fmtMs(avg)})",
                    HealthDiagnostics(
                        pipelineStage = "Vulkan compute dispatch",
                        performanceImpact = "Reduced processing headroom.",
                        metrics = metrics(s)
                    )
                )
            }
            return reading(
                HealthState.ACTIVE,
                "GPU compute active (${fmtMs(avg)})",
                HealthDiagnostics(pipelineStage = "Vulkan compute dispatch", metrics = metrics(s))
            )
        }
        return reading(
            HealthState.ACTIVE,
            "GPU compute active",
            HealthDiagnostics(pipelineStage = "Vulkan compute dispatch", metrics = metrics(s))
        )
    }

    private fun metrics(s: RuntimeSignalSnapshot): List<Pair<String, String>> = listOf(
        "Vulkan supported" to yn(s.vulkanSupported),
        "Pipeline healthy" to yn(s.vulkanPipelineHealthy),
        "Compute dispatched" to yn(s.computeDispatched),
        "Dispatch count" to (s.computeTotalDispatches?.toString() ?: "—"),
        "Avg compute" to (s.computeAverageMs?.let { fmtMs(it) } ?: "—"),
        "Peak compute" to (s.computePeakMs?.let { fmtMs(it) } ?: "—"),
        "GPU utilization" to gpuUtilization(s),
        "Timing source" to if (s.computeGpuTimestamped) "GPU timestamp" else "CPU estimate"
    ) + runtimeDiagnosticsMetrics(s)

    /** Rough duty-cycle estimate: compute latency vs a 60fps frame budget. Honest "—" without timing. */
    private fun gpuUtilization(s: RuntimeSignalSnapshot): String {
        val avg = s.computeAverageMs ?: return "—"
        val pct = ((avg / 16.67) * 100.0).coerceIn(0.0, 100.0)
        return "${pct.toLong()}%"
    }

    private fun fmtMs(v: Double): String = "${(v * 10).toLong() / 10.0} ms"

    private fun reading(state: HealthState, reason: String, d: HealthDiagnostics, hardFault: Boolean = false, recoverable: Boolean = false) =
        ModuleHealthReading(module, state, reason, d, hardFault, recoverable)
}

/**
 * NATIVE — ISP metadata read-back: the HAL is delivering capture results and honored the app's
 * requested ISP configuration (verified against CaptureResult metadata, CP17). Active in preview and
 * recording. NOTE: the app's read-back proves the requested ISP state was HONORED; when no ISP-bypass
 * request is active (the common processed pipeline) the verdict is NOT_APPLICABLE and the module is
 * honestly INACTIVE rather than falsely HEALTHY.
 */
object NativeHealthProbe : HealthProbe {
    override val module = MonitoredModule.NATIVE

    override fun evaluate(s: RuntimeSignalSnapshot): ModuleHealthReading {
        if (!s.cameraOpen) {
            return reading(
                HealthState.INACTIVE,
                "Camera closed",
                HealthDiagnostics(pipelineStage = "Camera HAL metadata")
            )
        }
        // Issue 4: a POSITIVELY-established hardware verdict that the HAL cannot bypass the vendor ISP is
        // UNSUPPORTED (not the old catch-all NOT_APPLICABLE/INACTIVE). Only when the signal is definitely
        // false — null (not yet reported) must never fabricate an UNSUPPORTED claim.
        if (s.ispBypassSupportedByHardware == false) {
            return reading(
                HealthState.UNSUPPORTED,
                "Hardware cannot bypass the vendor ISP",
                HealthDiagnostics(
                    failureReason = "This camera HAL does not expose a way to disable the vendor ISP (sensor-native RAW bypass unavailable).",
                    pipelineStage = "Camera HAL / ISP capability",
                    performanceImpact = "Sensor-native (ISP-off) capture is not possible on this hardware.",
                    metrics = metrics(s)
                )
            )
        }
        if (!s.halResultsFlowing) {
            // No capture-result metadata yet — liveness/verification is genuinely indeterminate.
            return reading(
                HealthState.UNKNOWN,
                "Awaiting HAL metadata",
                HealthDiagnostics(
                    failureReason = "No capture-result metadata has been delivered yet.",
                    pipelineStage = "Camera2 CaptureResult stream",
                    performanceImpact = "ISP honored-state cannot be confirmed until metadata flows.",
                    metrics = metrics(s)
                )
            )
        }
        return when (s.ispVerdictLabel.uppercase()) {
            "PROVEN" -> reading(
                HealthState.ACTIVE,
                "ISP bypass honored by HAL",
                HealthDiagnostics(
                    pipelineStage = "Camera HAL / ISP (sensor-native RAW active)",
                    suggestedSolution = null,
                    metrics = metrics(s)
                )
            )
            "NOT_PROVEN" -> reading(
                HealthState.ERROR,
                "HAL did not honor ISP request",
                HealthDiagnostics(
                    failureReason = "The HAL reported an ISP mode different from the requested one (rejection/override).",
                    runtimeException = s.ispReadbackWarning,
                    pipelineStage = "Camera HAL / ISP metadata read-back",
                    performanceImpact = "The requested ISP processing was not applied as asked.",
                    suggestedSolution = "Recovery will re-arm the read-back and re-request the ISP mode.",
                    metrics = metrics(s)
                ),
                recoverable = true
            )
            // NOT_APPLICABLE — split the old dead INACTIVE into the three genuinely different cases.
            else -> if (s.ispRawRequestActive || s.rawStreamActive || s.nativeBayerActive) {
                // A RAW / ISP-off request IS engaged but the read-back has not settled/proven yet.
                reading(
                    HealthState.UNKNOWN,
                    "RAW ISP-bypass engaged — verifying read-back",
                    HealthDiagnostics(
                        pipelineStage = "Camera HAL / ISP metadata read-back",
                        performanceImpact = "Sensor-native RAW capture is active; honored-state verification is pending.",
                        metrics = metrics(s)
                    )
                )
            } else {
                // Normal processed YUV/JPEG preview: the HAL is demonstrably alive (halResultsFlowing),
                // the ISP-bypass verification is simply not engaged — READY, not a dead INACTIVE.
                reading(
                    HealthState.READY,
                    "Processed ISP path ready",
                    HealthDiagnostics(
                        pipelineStage = "Camera HAL / processed ISP pipeline",
                        performanceImpact = "Processed pipeline live; sensor-native ISP verification engages on a RAW request.",
                        metrics = metrics(s)
                    )
                )
            }
        }
    }

    private fun metrics(s: RuntimeSignalSnapshot): List<Pair<String, String>> = listOf(
        "HAL metadata" to yn(s.halResultsFlowing),
        "ISP verdict" to s.ispVerdictLabel,
        "Sensor path" to if (s.ispRawRequestActive || s.rawStreamActive || s.nativeBayerActive) "RAW sensor (ISP-off)" else "Processed YUV/JPEG",
        "RAW path" to yn(s.rawStreamActive),
        "Native Bayer" to yn(s.nativeBayerActive),
        "ISP bypass supported" to triState(s.ispBypassSupportedByHardware),
        "Vendor ISP" to when (s.vendorIspPresent) { true -> "required"; false -> "optional"; null -> "unknown" },
        "Last warning" to (s.ispReadbackWarning ?: "none")
    ) + runtimeDiagnosticsMetrics(s)

    private fun reading(state: HealthState, reason: String, d: HealthDiagnostics, hardFault: Boolean = false, recoverable: Boolean = false) =
        ModuleHealthReading(module, state, reason, d, hardFault, recoverable)
}

/** The canonical probe set, in HUD display order. */
val DEFAULT_HEALTH_PROBES: List<HealthProbe> =
    listOf(ProvCoreHealthProbe, P010HealthProbe, LabHealthProbe, NativeHealthProbe)

/**
 * The shared runtime-diagnostics block every module surfaces (spec: each module must expose these). A
 * genuinely-unavailable source renders as "—" — never fabricated. "Current pipeline stage" and
 * "Recovery count" are already dedicated typed rows in the diagnostics panel, so they are not repeated
 * here. GPU time is labelled GPU-measured vs CPU-estimated so a CPU-wall fallback is never claimed as a
 * true GPU timing.
 */
internal fun runtimeDiagnosticsMetrics(s: RuntimeSignalSnapshot): List<Pair<String, String>> {
    val gpuTime = when {
        s.computeAverageMs == null -> "—"
        s.computeGpuTimestamped -> "${dms(s.computeAverageMs)} (GPU-measured)"
        else -> "${dms(s.computeAverageMs)} (CPU est.)"
    }
    val errorCount = (s.sessionFailedImports ?: s.consecutiveFailedImports).toString()
    val dropped = (s.droppedMuxerSamples ?: s.dropEstimate).toString()
    val memory = s.memoryUsageMb?.let { mb ->
        "$mb MB" + (s.memoryUtilizationPercent?.let { " ($it%)" } ?: "")
    } ?: "—"
    return listOf(
        "Last frame ts" to (s.lastFrameSensorTimestampNs?.let { "$it ns" } ?: "—"),
        "Last successful op" to (s.lastSuccessfulImportElapsedMs?.let { "import @ ${it} ms" } ?: "—"),
        "Average latency" to (s.computeAverageMs?.let { dms(it) } ?: "—"),
        "Maximum latency" to (s.computePeakMs?.let { dms(it) } ?: "—"),
        "Error count" to errorCount,
        "Queue depth" to (s.pendingMuxerSamples?.toString() ?: "—"),
        "Dropped frames" to dropped,
        "GPU time" to gpuTime,
        "CPU time" to "—",
        "Encoder latency" to "—",
        "Memory usage" to memory
    )
}

private fun dms(v: Double): String = "${(v * 10).toLong() / 10.0} ms"
private fun triState(b: Boolean?): String = when (b) { true -> "yes"; false -> "no"; null -> "unknown" }
private fun yn(b: Boolean): String = if (b) "yes" else "no"
private fun fps(v: Float): String = "${(v * 10).toLong() / 10.0}"
