$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\recording_process_evidence.ps1')
$checks = 0
function Assert-RecordingEvidence($Expected, $Actual, [string]$Name) {
    if ($Expected -cne $Actual) { throw "$Name expected=$Expected actual=$Actual" }
    $script:checks++
}
$currentLogcat = '09-08 02:50:11.600 27067 27098 I RawVideoManager: RAW_CAPTURE_GATE_OPEN'
$currentPersistent = '2026-09-08 02:50:11.600 I/RawVideoManager [DefaultDispatcher-worker-4(pid:27067)] RAW_CAPTURE_START'
$oldFailure = '2026-09-08 02:33:24.572 E/MainActivity [main(pid:22022)] DEBUG_RECORDING_CONFIG_REJECTED'
$oldSuccess = '09-08 02:31:24.572 22022 22022 I RawVideoManager: RAW_CAPTURE_GATE_OPEN'
$currentFailure = '09-08 02:50:12.600 27067 27067 E MainActivity: DEBUG_RECORDING_CONFIG_REJECTED'
Assert-RecordingEvidence $currentLogcat (Select-QARecordingProcessEvidence $currentLogcat '27067') 'current logcat retained'
Assert-RecordingEvidence $currentPersistent (Select-QARecordingProcessEvidence $currentPersistent '27067') 'current persistent retained'
Assert-RecordingEvidence '' (Select-QARecordingProcessEvidence $oldFailure '27067') 'old failure cannot fail a new attempt'
Assert-RecordingEvidence '' (Select-QARecordingProcessEvidence $oldSuccess '27067') 'old success cannot pass a new attempt'
Assert-RecordingEvidence $currentFailure (Select-QARecordingProcessEvidence $currentFailure '27067') 'current failure remains fatal'
Assert-RecordingEvidence '' (Select-QARecordingProcessEvidence $currentLogcat '') 'missing process cannot prove anything'
Assert-RecordingEvidence '' (Select-QARecordingProcessEvidence $currentPersistent '2706') 'PID prefix never matches'
Assert-RecordingEvidence '' (Select-QARecordingProcessEvidence $currentPersistent '127067') 'PID suffix never matches'
Assert-RecordingEvidence '' (Select-QARecordingProcessEvidence '' '27067') 'empty evidence stays empty'
Assert-RecordingEvidence '' (Select-QARecordingProcessEvidence ('unrelated payload ' + $currentPersistent) '27067') 'embedded timestamp cannot impersonate a record'
$mixed = $oldFailure + "`r`n" + $currentLogcat + "`r`n" + $oldSuccess + "`r`n" + $currentPersistent
Assert-RecordingEvidence ($currentLogcat + "`n" + $currentPersistent) (Select-QARecordingProcessEvidence $mixed '27067') 'mixed generations retain exact current records'
foreach ($badPid in @('0', '-1', '27067 22022', '.*', ('27067' + "`n"))) {
    $rejected = $false
    try { Select-QARecordingProcessEvidence $mixed $badPid | Out-Null } catch { $rejected = $true }
    Assert-RecordingEvidence $true $rejected 'malformed or multiple PIDs rejected'
}
Write-Output "PASS recording-process-evidence assertions=$checks scope=host-parser-not-camera-performance"
