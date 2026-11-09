param(
    [string]$RetainedEvidencePath = '',
    [string]$RemoteLogPath = '',
    [string]$AdbPath = 'C:\Users\ELCOT\AppData\Local\Android\Sdk\platform-tools\adb.exe',
    [string]$Serial = '10BF8M2NDX003P3',
    [string]$EvidenceDirectory = 'D:\NeoProCamX_QA\lq001-matrix-resume-20260907'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\passive_recording_log.ps1')
$label = 'lq001_05_video_cinema_natural_detail_4096x3072_25fps_hot_endurance_20260907-111450'
$tail = "RECORDING_STOP_ORIGIN committed=true origin=NPV10_WRITER_FAILURE generation=1 label=$label detail=writer_queue_full"
$persistent = "2026-09-07 11:16:44.581 I/RecordingStopOrigin [main(pid:6700)] $tail"
$logcat = "09-07 11:16:44.581  6700  6750 I RecordingStopOrigin: $tail"
$passed = 0

function Assert-Marker {
    param([string[]]$Lines, [AllowNull()][string]$Expected, [string]$Name)
    $actual = Find-QARecordingStopMarker -Lines $Lines -ProcessId '6700' -ValidationLabel $label
    if ([string]$actual -cne [string]$Expected) { throw "Marker test failed: $Name" }
    $script:passed++
}

Assert-Marker -Lines @($persistent) -Expected $persistent -Name 'persistent_current_pid_and_label'
Assert-Marker -Lines @($logcat) -Expected $logcat -Name 'logcat_current_pid_and_label'
Assert-Marker -Lines @() -Expected $null -Name 'empty_stream'
Assert-Marker -Lines @('unrelated data', '') -Expected $null -Name 'no_stop_event'
Assert-Marker -Lines @($persistent.Replace('pid:6700', 'pid:16700')) -Expected $null -Name 'different_pid'
Assert-Marker -Lines @($logcat.Replace(' 6700 ', ' 16700 ')) -Expected $null -Name 'different_logcat_pid'
Assert-Marker -Lines @($persistent.Replace("label=$label", "label=${label}_other")) -Expected $null -Name 'label_prefix_rejected'
Assert-Marker -Lines @($persistent.Replace('committed=true', 'committed=false')) -Expected $null -Name 'uncommitted_stop_rejected'
Assert-Marker -Lines @($persistent.Replace("label=$label", 'label=old_run')) -Expected $null -Name 'stale_run_rejected'
Assert-Marker -Lines @($persistent, $logcat) -Expected $logcat -Name 'latest_matching_event'

if ($RetainedEvidencePath) {
    $observed = Find-QARecordingStopMarker -Lines @(Get-Content -LiteralPath $RetainedEvidencePath -Encoding UTF8) `
        -ProcessId '6700' -ValidationLabel $label
    if (-not $observed -or $observed -notmatch 'origin=NPV10_WRITER_FAILURE') {
        throw 'Retained real-device failure was not recognized'
    }
    $passed++
}
if ($RemoteLogPath) {
    New-Item -ItemType Directory -Path $EvidenceDirectory -Force | Out-Null
    $streamOutput = Join-Path $EvidenceDirectory 'passive-stream-integration.txt'
    $streamError = Join-Path $EvidenceDirectory 'passive-stream-integration-stderr.txt'
    $streamProcess = $null
    try {
        $streamProcess = Start-QAPassiveRecordingLog -AdbPath $AdbPath -Serial $Serial `
            -RemotePath $RemoteLogPath -OutputPath $streamOutput -ErrorPath $streamError -MaximumSeconds 90
        Start-Sleep -Milliseconds 800
        $observed = Find-QARecordingStopMarker -Lines @(Get-Content -LiteralPath $streamOutput) `
            -ProcessId '6700' -ValidationLabel $label
        if (-not $observed -or $observed -notmatch 'origin=NPV10_WRITER_FAILURE') {
            throw 'Live SDK-ADB passive stream did not retain the known stop event'
        }
        $passed++
    } finally {
        if ($streamProcess -and -not $streamProcess.HasExited) {
            Stop-Process -Id $streamProcess.Id -Force
        }
    }
}
[pscustomobject]@{
    scope = 'host_oracle_and_device_log_transport_not_camera_fps'
    passed = $passed
    failed = 0
    livePassiveStreamTested = [bool]$RemoteLogPath
} | ConvertTo-Json -Compress
