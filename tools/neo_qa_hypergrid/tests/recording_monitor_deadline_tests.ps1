$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../passive_recording_log.ps1')
$passed = 0
foreach ($case in @(
    @{ target = 300.0; elapsed = 0.0; expected = 10000 },
    @{ target = 58.0; elapsed = 50.0; expected = 8000 },
    @{ target = 58.0; elapsed = 57.5; expected = 500 },
    @{ target = 58.0; elapsed = 57.9999; expected = 0 },
    @{ target = 58.0; elapsed = 58.0; expected = 0 },
    @{ target = 58.0; elapsed = 59.0; expected = 0 },
    @{ target = 7200.0; elapsed = 100.0; expected = 10000 }
)) {
    $actual = Get-QARecordingMonitorSleepMilliseconds `
        -TargetSeconds $case.target -ElapsedSeconds $case.elapsed
    if ($actual -ne $case.expected) { throw "Wrong deadline delay: actual=$actual expected=$($case.expected)" }
    $passed += 1
}
foreach ($case in @(
    @{ target = 0.0; elapsed = 0.0 },
    @{ target = -1.0; elapsed = 0.0 },
    @{ target = 1.0; elapsed = -1.0 },
    @{ target = [double]::NaN; elapsed = 0.0 },
    @{ target = 1.0; elapsed = [double]::PositiveInfinity },
    @{ target = [double]::PositiveInfinity; elapsed = 0.0 }
)) {
    $rejected = $false
    try {
        Get-QARecordingMonitorSleepMilliseconds `
            -TargetSeconds $case.target -ElapsedSeconds $case.elapsed | Out-Null
    } catch { $rejected = $true }
    if (-not $rejected) { throw 'Invalid deadline was accepted' }
    $passed += 1
}
# Deterministic coverage of fractional endpoints, without pretending host time proves camera FPS.
for ($index = 0; $index -lt 2000; $index++) {
    $elapsed = $index * 0.029
    $delay = Get-QARecordingMonitorSleepMilliseconds -TargetSeconds 58.0 -ElapsedSeconds $elapsed
    if ($delay -lt 0 -or $delay -gt 10000 -or $delay -gt (58.0 - $elapsed) * 1000.0 + 0.000001) {
        throw "Deadline exceeded at elapsed=$elapsed delay=$delay"
    }
}
$passed += 1
Write-Output "PASS: $passed recording-monitor deadline tests (including 2000 fractional endpoints). No device-performance claim."
