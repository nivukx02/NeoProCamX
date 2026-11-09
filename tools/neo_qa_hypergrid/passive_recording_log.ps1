# One persistent read-only stream prevents OEM logcat filtering from concealing a recorder stop.
# It is started during admission, never polled with new device processes inside the timed window.
function Get-QARecordingMonitorSleepMilliseconds {
    param(
        [Parameter(Mandatory = $true)][double]$TargetSeconds,
        [Parameter(Mandatory = $true)][double]$ElapsedSeconds
    )
    if ([double]::IsNaN($TargetSeconds) -or [double]::IsInfinity($TargetSeconds) -or
        [double]::IsNaN($ElapsedSeconds) -or [double]::IsInfinity($ElapsedSeconds) -or
        $TargetSeconds -le 0 -or $ElapsedSeconds -lt 0) {
        throw 'Recording deadline requires finite positive target and non-negative elapsed seconds'
    }
    # A fixed ten-second sleep extended a 58-second request to more than 60 seconds before
    # stop dispatch. Keep passive monitoring, but never knowingly sleep past its deadline.
    # This bounds host wait only; actual source/output duration must still be measured.
    $remainingMilliseconds = [math]::Floor(($TargetSeconds - $ElapsedSeconds) * 1000.0)
    return [int][math]::Max(0.0, [math]::Min(10000.0, $remainingMilliseconds))
}

function Start-QAPassiveRecordingLog {
    param(
        [Parameter(Mandatory = $true)][string]$AdbPath,
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)]
        [ValidatePattern('^/sdcard/Android/data/com\.neoproc\.neoproccamx/files/neoproc_[A-Za-z0-9_.-]+\.log$')]
        [string]$RemotePath,
        [Parameter(Mandatory = $true)][string]$OutputPath,
        [Parameter(Mandatory = $true)][string]$ErrorPath,
        [ValidateRange(90, 9000)][int]$MaximumSeconds = 1200
    )

    # Include the recent prefix: an early backend failure can race with durable-start admission.
    # The host oracle separately binds every stop marker to the exact PID and validation label.
    $process = Start-Process -FilePath $AdbPath -ArgumentList @(
        '-s', $Serial, 'exec-out', 'timeout', $MaximumSeconds.ToString(),
        'tail', '-n', '4096', '-F', $RemotePath
    ) -RedirectStandardOutput $OutputPath -RedirectStandardError $ErrorPath `
        -WindowStyle Hidden -PassThru
    Start-Sleep -Milliseconds 100
    if ($process.HasExited) {
        throw "Passive recording-log stream ended during admission (exit=$($process.ExitCode)); inspect $ErrorPath"
    }
    return $process
}

function Find-QARecordingStopMarker {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][AllowEmptyString()][string[]]$Lines,
        [Parameter(Mandatory = $true)][ValidatePattern('^[1-9][0-9]*$')][string]$ProcessId,
        [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9_.-]+$')][string]$ValidationLabel
    )

    $escapedPid = [regex]::Escape($ProcessId)
    $escapedLabel = [regex]::Escape($ValidationLabel)
    $pidPattern = "\(pid:${escapedPid}\)|^\d{2}-\d{2}\s+\S+\s+${escapedPid}\s+\d+\s+[A-Z]\s"
    $labelPattern = "(?:^|\s)label=${escapedLabel}(?:\s|$)"
    for ($index = $Lines.Count - 1; $index -ge 0; $index--) {
        $line = $Lines[$index]
        if ($line -match $pidPattern -and $line -match $labelPattern -and
            $line -match 'RECORDING_STOP_ORIGIN committed=true origin=[A-Z][A-Z0-9_]*(?:\s|$)') {
            return $line
        }
    }
    return $null
}
