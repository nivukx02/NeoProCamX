from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
RUNNER = ROOT / "tools" / "neo_qa_hypergrid" / "run_lq001_endurance.ps1"
RECORDING_MANAGER = (
    ROOT
    / "app"
    / "src"
    / "main"
    / "java"
    / "com"
    / "neoproc"
    / "neoproccamx"
    / "recording"
    / "RecordingManager.kt"
)


class Lq001EnduranceRunnerContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.source = RUNNER.read_text(encoding="utf-8")
        cls.recording_source = RECORDING_MANAGER.read_text(encoding="utf-8")

    def test_every_pipeline_start_can_use_current_process_persistent_telemetry(self) -> None:
        for contract in (
            "Get-CurrentAppOwnedLogPath",
            "Read-CurrentProcessStartupTelemetry",
            "DIRECT_RECORDING_START_COMMIT",
            "NPV10_STARTUP_COMMIT",
            "NPV10_USER_CONTROL_LATE_BOUNDARY",
            "app-persistent-start-verification.txt",
        ):
            self.assertIn(contract, self.source)

        persistent_poll = self.source.index("$latestAppOwnedLogPath = Get-CurrentAppOwnedLogPath")
        polling_loop = self.source.rfind("for ($attempt = 0; $attempt -lt 40;", 0, persistent_poll)
        self.assertNotIn(
            "if ($Pipeline -eq 'CINEMA')",
            self.source[polling_loop:persistent_poll],
        )

    def test_direct_commit_marker_is_persisted_by_the_app(self) -> None:
        commit = self.recording_source.index("DIRECT_RECORDING_START_COMMIT stable=true")
        next_return = self.recording_source.index("\n            true\n", commit)
        self.assertIn("NeoLogger.i(TAG, readinessCommitEvidence)", self.recording_source[commit:next_return])

    def test_persistent_marker_is_bound_to_current_process(self) -> None:
        self.assertIn("pidof $packageName", self.source)
        self.assertIn("\\(pid:$([regex]::Escape($ProcessId))\\)", self.source)

    def test_durable_start_gate_still_requires_marker_and_foreground_service(self) -> None:
        self.assertIn(
            "if ($durablePipelineStartObserved -and $recordingServiceActive)",
            self.source,
        )
        self.assertNotIn(
            "if ($durablePipelineStartObserved -or $recordingServiceActive)",
            self.source,
        )

    def test_postmortem_is_captured_before_force_stop(self) -> None:
        capture = self.source.index("app-persistent-log-postmortem.txt")
        force_stop = self.source.index(
            "'shell', 'am', 'force-stop', $packageName",
            capture,
        )
        self.assertLess(capture, force_stop)

    def test_detail_profile_is_parameterized_and_proven(self) -> None:
        self.assertIn(
            "[ValidateSet('NATURAL_DETAIL', 'FOCUSED_DETAIL', 'EXTREME_DETAIL')]",
            self.source,
        )
        self.assertIn(
            "'--es', 'debug_video_detail_profile', $DetailProfile",
            self.source,
        )
        self.assertNotIn(
            "'--es', 'debug_video_detail_profile', 'FOCUSED_DETAIL'",
            self.source,
        )
        self.assertIn("selectedDetailProfile", self.source)
        self.assertIn("detailProfileEvidenceValid", self.source)
        self.assertIn("requested_detail_profile = $DetailProfile", self.source)
        self.assertIn(
            "if (-not $detailProfileEvidenceValid)",
            self.source,
        )

    def test_explicit_quality_certificate_failure_blocks_run_complete(self) -> None:
        status_read = self.source.index(
            "$qualityEvidenceStatus = [string]$certificateFields['qualityEvidenceStatus']"
        )
        failure_gate = self.source.index("if (-not $qualityEvidenceNotFailed)")
        completion = self.source.index('Write-Output "RUN_COMPLETE')

        self.assertLess(status_read, failure_gate)
        self.assertLess(failure_gate, completion)
        self.assertIn("quality_evidence_status = $qualityEvidenceStatus", self.source)
        self.assertIn("qualityEvidence=$qualityEvidenceStatus", self.source)

    def test_timed_recording_loop_avoids_heavy_binder_diagnostics(self) -> None:
        loop_start = self.source.index(
            "while ($timer.Elapsed.TotalSeconds -lt $DurationSeconds)"
        )
        loop_end = self.source.index("$timer.Stop()", loop_start)
        recording_loop = self.source[loop_start:loop_end]

        for prohibited in (
            "Read-ThermalSnapshot",
            "Read-BatteryTemperature",
            "Test-RecordingForegroundServiceActive",
            "dumpsys thermalservice",
            "dumpsys battery",
            "dumpsys activity services",
        ):
            self.assertNotIn(prohibited, recording_loop)
        self.assertIn("Read-LightweightThermalSample", recording_loop)

    def test_timed_wait_is_bounded_by_remaining_duration_without_relaxing_output_gate(self) -> None:
        loop_start = self.source.index("while ($timer.Elapsed.TotalSeconds -lt $DurationSeconds)")
        loop_end = self.source.index("$timer.Stop()", loop_start)
        recording_loop = self.source[loop_start:loop_end]
        self.assertIn("Get-QARecordingMonitorSleepMilliseconds", recording_loop)
        self.assertIn("-TargetSeconds $DurationSeconds -ElapsedSeconds $timer.Elapsed.TotalSeconds", recording_loop)
        self.assertIn("Start-Sleep -Milliseconds $monitorSleepMs", recording_loop)
        self.assertNotIn("Start-Sleep -Seconds 10", recording_loop)
        self.assertIn("$measuredOutputDurationMs -ge $minimumRequiredDurationMs", self.source)
        self.assertIn("605000L", self.source)

    def test_lightweight_monitor_reads_target_sysfs_nodes(self) -> None:
        self.assertIn("/sys/class/thermal/thermal_zone68/temp", self.source)
        self.assertIn("/sys/class/thermal/thermal_zone79/temp", self.source)
        self.assertIn("recordingWindowProhibitedDiagnostics=", self.source)

    def test_capture_safe_monitoring_avoids_adb_shell_processes_in_hot_window(self) -> None:
        self.assertIn("[bool]$CaptureSafeMonitoring = $true", self.source)
        self.assertIn("if (-not $CaptureSafeMonitoring)", self.source)
        self.assertIn("healthPollSeconds=disabled-during-capture", self.source)
        self.assertIn("recordingWindowProhibitedDiagnostics=all adb shell process launches", self.source)

    def test_thermal_four_can_be_a_mandatory_evidence_gate(self) -> None:
        self.assertIn("[int]$MinimumThermalStatus = 0", self.source)
        self.assertIn("Thermal admission failed:", self.source)
        self.assertIn("thermal_before_status = $thermalBeforeStatus", self.source)
        self.assertIn("thermal_after_status = $thermalAfterStatus", self.source)
        self.assertIn("if (-not $thermalEvidenceValid)", self.source)

    def test_usb_transport_is_the_default_and_capture_window_detects_early_stop_passively(self) -> None:
        self.assertIn("[string]$Serial = '10BF8M2NDX003P3'", self.source)
        self.assertIn("RecordingStopOrigin:I", self.source)
        self.assertIn("RECORDING_STOP_ORIGIN committed=true origin=", self.source)
        loop_start = self.source.index(
            "while ($timer.Elapsed.TotalSeconds -lt $DurationSeconds)"
        )
        loop_end = self.source.index("$timer.Stop()", loop_start)
        recording_loop = self.source[loop_start:loop_end]
        self.assertIn("Select-String -LiteralPath $focusedLogOutput", recording_loop)
        self.assertNotIn("Read-AppOwnedLogTail", recording_loop)
        self.assertIn("plannedStopOriginValid", self.source)

    def test_oem_force_stop_is_captured_passively_and_never_relaunches_the_app(self) -> None:
        self.assertIn("'logcat', '-b', 'events', '-v', 'threadtime'", self.source)
        self.assertIn("am_kill.*$([regex]::Escape($packageName))", self.source)
        self.assertIn("external-process-stop.txt", self.source)
        self.assertIn("exit-info-postmortem-before-cleanup.txt", self.source)

        recording_loop_start = self.source.index(
            "while ($timer.Elapsed.TotalSeconds -lt $DurationSeconds)"
        )
        recording_loop_end = self.source.index("$timer.Stop()", recording_loop_start)
        recording_loop = self.source[recording_loop_start:recording_loop_end]
        self.assertIn("Select-String -LiteralPath $eventLogOutput", recording_loop)
        self.assertNotIn("dumpsys activity exit-info", recording_loop)

        cleanup = self.source.rindex("\nfinally {")
        stop_broadcast = self.source.index("'--es', 'debug_action', 'stop_recording'", cleanup)
        self.assertIn(
            "[string]::IsNullOrWhiteSpace($externalStopMarker)",
            self.source[cleanup:stop_broadcast],
        )

    def test_planned_host_stop_must_be_persisted_by_the_candidate(self) -> None:
        self.assertIn("$recordingStopOrigin -eq 'DEBUG_COMMAND'", self.source)
        self.assertIn("if (-not $plannedStopOriginValid)", self.source)
        self.assertIn("recording_stop_origin = $recordingStopOrigin", self.source)

    def test_failed_publication_does_not_wait_for_nonexistent_mediastore_row(self) -> None:
        self.assertIn("RECORDING_STOP_THREW", self.source)
        self.assertIn("terminal-recording-failure.txt", self.source)
        self.assertIn("run-error.json", self.source)
        self.assertIn("candidate_identity_evidence", self.source)

    def test_cleanup_is_bounded_and_still_closes_camera_after_log_failure(self) -> None:
        cleanup = (RUNNER.parent / "bounded_adb_cleanup.ps1").read_text(encoding="utf-8")
        for contract in ("WaitForExit($TimeoutMs)", "$process.Kill()", "CreateNoWindow = $true"):
            self.assertIn(contract, cleanup)
        self.assertIn("$cleanupResults.force_stop = Invoke-QABoundedAdbCleanup", self.source)
        self.assertIn("$cleanupResults.screen_off = Invoke-QABoundedAdbCleanup", self.source)
        self.assertIn("cleanup-result.json", self.source)


if __name__ == "__main__":
    unittest.main()
