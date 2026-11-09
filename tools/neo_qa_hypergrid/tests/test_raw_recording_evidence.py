"""QA evidence isolation tests, not physical camera/FPS or image-quality proof."""
from pathlib import Path
import shutil
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[3]
TOOLS = ROOT / "tools" / "neo_qa_hypergrid"


class RawRecordingEvidenceTests(unittest.TestCase):
    def test_failed_physical_audit_is_preserved_without_turning_it_into_a_pass(self) -> None:
        powershell = shutil.which("powershell") or shutil.which("pwsh")
        if powershell is None:
            self.skipTest("PowerShell is required to execute the audit evidence parser")
        script = r'''
$ErrorActionPreference = 'Stop'
. './tools/neo_qa_hypergrid/npraw_audit_result.ps1'
$sample = '{"status":"FAIL","measuredFps":24.995,"frameCount":7122,"crcFailures":0,"fieldFailures":0,"nonMonotonicGaps":0,"gapsOver60ms":6,"storageBitDepth":10}'
$result = ConvertFrom-QANprawAuditResult -Json $sample -ExitCode 1
if ($result.status -ne 'FAIL' -or $result.gapsOver60ms -ne 6 -or $result.processExitCode -ne 1) {
    throw 'Physical failure evidence was changed'
}
$checks = @(
    @{ Json=$sample; ExitCode=0 },
    @{ Json=$sample; ExitCode=2 },
    @{ Json='{"status":"FAIL"}'; ExitCode=1 },
    @{ Json=($sample -replace '"frameCount":7122', '"frameCount":null'); ExitCode=1 },
    @{ Json=($sample -replace '"frameCount":7122', '"frameCount":"7122"'); ExitCode=1 }
)
foreach ($check in $checks) {
    $rejected = $false
    try { ConvertFrom-QANprawAuditResult @check | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw 'Malformed/mismatched audit evidence was accepted' }
}
'audit-parser assertions=6'
'''
        result = subprocess.run([powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script],
                                capture_output=True, text=True, timeout=30, cwd=ROOT)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("audit-parser assertions=6", result.stdout)

    def test_real_powershell_parser_rejects_other_recording_generations(self) -> None:
        powershell = shutil.which("powershell") or shutil.which("pwsh")
        if powershell is None:
            self.skipTest("PowerShell is required to execute the production evidence parser")
        result = subprocess.run(
            [powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
             str(TOOLS / "tests" / "run_recording_process_evidence_tests.ps1")],
            capture_output=True, text=True, timeout=30,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("assertions=16", result.stdout)

    def test_start_and_terminal_gates_use_the_same_latched_process(self) -> None:
        source = (TOOLS / "run_lq001_raw_endurance.ps1").read_text(encoding="utf-8")
        self.assertIn("RAW app process changed during startup", source)
        self.assertIn("$recordingProcessId = $currentPid", source)
        self.assertIn(
            "$startEvidence = Select-QARecordingProcessEvidence -Text $startEvidence -ProcessId $recordingProcessId",
            source,
        )
        self.assertIn(
            "$finalEvidence = Select-QARecordingProcessEvidence -Text $finalEvidence -ProcessId $recordingProcessId",
            source,
        )
        self.assertIn("start-verification-unfiltered.txt", source)
        self.assertIn("runtime-evidence-unfiltered.txt", source)

    def test_start_failure_is_structured_without_fabricating_a_recording_pass(self) -> None:
        source = (TOOLS / "run_lq001_raw_endurance.ps1").read_text(encoding="utf-8")
        start = source.index("\ncatch {\n    # Startup/configuration failures")
        end = source.index("\nfinally {", start)
        failure = source[start:end]
        for contract in ("run-failure.json", "status = 'FAIL'", "phase = $executionPhase",
                         "production_ready = $false", "failure = $_.Exception.Message",
                         "recording_started = $recordingWasStarted", "throw"):
            self.assertIn(contract, failure)
        self.assertNotIn("reported_frames =", failure)
        self.assertNotIn("audited_frames =", failure)
        self.assertIn("measured_result_present =", failure)

    def test_format_drops_crc_and_planned_stop_remain_mandatory(self) -> None:
        source = (TOOLS / "run_lq001_raw_endurance.ps1").read_text(encoding="utf-8")
        for gate in ("$reportedDrops -eq 0L", "[long]$audit.crcFailures -eq 0L",
                     "[long]$audit.nonMonotonicGaps -eq 0L", "[long]$audit.gapsOver60ms -eq 0L",
                     "if (-not $formatValid)", "if (-not $detailProfileValid)",
                     "if (-not $durationValid)", "if (-not $cadenceValid)",
                     "if (-not $plannedStopValid)", "if (-not $thermalValid)"):
            self.assertIn(gate, source)


if __name__ == "__main__":
    unittest.main()
