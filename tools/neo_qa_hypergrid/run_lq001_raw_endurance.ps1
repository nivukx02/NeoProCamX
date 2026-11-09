param(
    [ValidateSet('NATURAL_DETAIL', 'FOCUSED_DETAIL', 'EXTREME_DETAIL')]
    [string]$DetailProfile = 'FOCUSED_DETAIL',

    [ValidateSet('4096x3072', '3840x2160', '3264x2448')]
    [string]$Resolution = '3840x2160',

    [ValidateSet(25)]
    [int]$Fps = 25,

    [ValidateSet('4:3', '16:9')]
    [string]$Aspect = '16:9',

    [ValidateRange(0, 6)]
    [int]$MinimumThermalStatus = 0,

    [ValidateRange(35, 7200)]
    [int]$DurationSeconds = 300,

    [string]$Serial = '10BF8M2NDX003P3',
    [string]$AdbPath = 'C:\Users\ELCOT\AppData\Local\Android\Sdk\platform-tools\adb.exe',
    [string]$NdkRoot = 'C:\Users\ELCOT\AppData\Local\Android\Sdk\ndk\29.0.14206865',
    [string]$EvidenceRoot = 'D:\NeoProCamX_QA'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'thermal_evidence.ps1')
. (Join-Path $PSScriptRoot 'recording_process_evidence.ps1')
$packageName = 'com.neoproc.neoproccamx'
$checkpoint = 'LQ-001.07'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$label = "lq001_07_raw_cinema_$($DetailProfile.ToLowerInvariant())_${Resolution}_${Fps}fps_hot_endurance_$stamp"
$runDirectory = Join-Path $EvidenceRoot $label
$lockPath = Join-Path $EvidenceRoot '.neo-qa-exclusive-adb.lock'
$logProcess = $null
$focusedLogProcess = $null
$recordingWasStarted = $false
$stopWasRequested = $false
$earlyStopMarker = ''
$appOwnedLogPath = ''
$recordingProcessId = ''
$executionPhase = 'ADMISSION'
$recordingThermalSamples = [System.Collections.Generic.List[object]]::new()

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & $AdbPath -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed ($LASTEXITCODE): $($Arguments -join ' ')"
    }
}

function Read-PowerState {
    (Invoke-Adb shell dumpsys power) -join "`n"
}

function Ensure-DeviceAwake {
    $powerState = Read-PowerState
    if ($powerState -notmatch 'mWakefulness=Awake') {
        Invoke-Adb shell input keyevent KEYCODE_POWER | Out-Null
        for ($attempt = 0; $attempt -lt 12; $attempt++) {
            Start-Sleep -Milliseconds 250
            $powerState = Read-PowerState
            if ($powerState -match 'mWakefulness=Awake') { break }
        }
    }
    if ($powerState -notmatch 'mWakefulness=Awake') {
        throw 'Device did not reach the Awake state'
    }
    Invoke-Adb shell wm dismiss-keyguard | Out-Null
    Invoke-Adb shell input keyevent KEYCODE_MENU | Out-Null
    Start-Sleep -Milliseconds 500
    return Read-PowerState
}

function Read-ThermalSnapshot {
    $text = (Invoke-Adb shell dumpsys thermalservice) -join "`n"
    ConvertFrom-QAThermalServiceSnapshot -Text $text
}

function Save-RecordingThermalSample {
    param([long]$ElapsedMs)
    $snapshot = Read-ThermalSnapshot
    $sample = [pscustomobject]@{
        elapsed_ms = $ElapsedMs
        utc = [DateTime]::UtcNow.ToString('o')
        framework_status = $snapshot.Status
        current_hal_maximum_status = $snapshot.CurrentHalMaximumStatus
        admission_status = $snapshot.AdmissionStatus
        skin_c = $snapshot.SkinC
        skin_source = $snapshot.SkinSource
    }
    $recordingThermalSamples.Add($sample)
    $sample | ConvertTo-Json -Compress | Add-Content -LiteralPath (Join-Path $runDirectory 'recording-thermal-series.jsonl')
    $snapshot.Text | Set-Content -LiteralPath (Join-Path $runDirectory "recording-thermal-$ElapsedMs.txt")
    return $snapshot
}

function Get-CurrentAppOwnedLogPath {
    $root = "/sdcard/Android/data/$packageName/files"
    ((Invoke-Adb shell "ls -1t $root/neoproc_*.log 2>/dev/null | head -n 1") -join '').Trim()
}

function Read-AppOwnedLogTail {
    param([string]$RemotePath, [int]$MaximumLines = 4096)
    if ([string]::IsNullOrWhiteSpace($RemotePath)) { return '' }
    (Invoke-Adb shell "tail -n $MaximumLines $RemotePath 2>/dev/null") -join "`n"
}

function Read-RawStreamInventory {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    try {
        $rows = & $AdbPath -s $Serial shell find /sdcard/Documents/CamX `
            -type f -name stream.npraw 2>$null
        if ($LASTEXITCODE -ne 0) { return @() }
        return @($rows | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ })
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Test-RecordingForegroundServiceActive {
    ((Invoke-Adb shell dumpsys activity services $packageName) -join "`n") -match
        [regex]::Escape("$packageName/.recording.RecordingForegroundService")
}

function Ensure-NprawAuditBinary {
    $source = Join-Path $PSScriptRoot '..\lq001_npraw_stream_audit.c'
    $source = [IO.Path]::GetFullPath($source)
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
        throw "NPRAW audit source is missing: $source"
    }
    $compiler = Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android28-clang.cmd'
    if (-not (Test-Path -LiteralPath $compiler -PathType Leaf)) {
        throw "Android NDK compiler is missing: $compiler"
    }
    $hostBinary = Join-Path $EvidenceRoot 'lq001_npraw_stream_audit'
    & $compiler -O3 -std=c11 -D_GNU_SOURCE -march=armv8-a+crc $source -o $hostBinary
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $hostBinary -PathType Leaf)) {
        throw 'NPRAW audit binary compilation failed'
    }
    Invoke-Adb push $hostBinary /data/local/tmp/lq001_npraw_stream_audit | Out-Null
    Invoke-Adb shell chmod 755 /data/local/tmp/lq001_npraw_stream_audit | Out-Null
    return $hostBinary
}

function Invoke-NprawAudit {
    param(
        [Parameter(Mandatory = $true)][string]$RemotePath,
        [Parameter(Mandatory = $true)][int]$Width,
        [Parameter(Mandatory = $true)][int]$Height
    )
    $stdoutPath = Join-Path $runDirectory 'npraw-stream-audit.json'
    $stderrPath = Join-Path $runDirectory 'npraw-stream-audit-progress.txt'
    $process = Start-Process -FilePath $AdbPath -ArgumentList @(
        '-s', $Serial, 'shell', '/data/local/tmp/lq001_npraw_stream_audit',
        $RemotePath, $Width.ToString(), $Height.ToString()
    ) -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath `
        -WindowStyle Hidden -PassThru -Wait
    . (Join-Path $PSScriptRoot 'npraw_audit_result.ps1')
    return ConvertFrom-QANprawAuditResult -Json (Get-Content -Raw -LiteralPath $stdoutPath) `
        -ExitCode $process.ExitCode
}

if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    throw "ADB executable not found: $AdbPath"
}
if (($Resolution -eq '3840x2160' -and $Aspect -ne '16:9') -or
    ($Resolution -ne '3840x2160' -and $Aspect -ne '4:3')) {
    throw "Aspect $Aspect does not match RAW target $Resolution"
}

New-Item -ItemType Directory -Path $EvidenceRoot -Force | Out-Null
if (Test-Path -LiteralPath $lockPath) {
    $existing = Get-Content -Raw -LiteralPath $lockPath -ErrorAction SilentlyContinue
    $pidMatch = [regex]::Match([string]$existing, 'host_pid=(\d+)')
    if ($pidMatch.Success) {
        $existingPid = [int]$pidMatch.Groups[1].Value
        if ($existingPid -ne $PID -and (Get-Process -Id $existingPid -ErrorAction SilentlyContinue)) {
            throw "Exclusive ADB controller is already active: host PID $existingPid"
        }
    }
}

New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
@(
    "checkpoint=$checkpoint"
    "serial=$Serial"
    "host_pid=$PID"
    "started_utc=$([DateTime]::UtcNow.ToString('o'))"
    "evidence=$runDirectory"
) | Set-Content -LiteralPath $lockPath
$runDirectory | Set-Content -LiteralPath (Join-Path $EvidenceRoot 'latest-lq001-raw-endurance-path.txt')

try {
    $connected = @(& $AdbPath devices | Select-Object -Skip 1 | ForEach-Object {
        ($_ -split '\s+')[0]
    } | Where-Object { $_ })
    if ($connected -notcontains $Serial) { throw "Requested ADB device is unavailable: $Serial" }

    Invoke-Adb shell am force-stop $packageName | Out-Null
    Ensure-DeviceAwake | Set-Content -LiteralPath (Join-Path $runDirectory 'power-admission.txt')
    $packagePath = ((Invoke-Adb shell pm path $packageName) -replace '^package:', '').Trim()
    if (-not $packagePath) { throw "Installed APK path is unavailable for $packageName" }
    $thermalBefore = Read-ThermalSnapshot
    $thermalBefore.Text | Set-Content -LiteralPath (Join-Path $runDirectory 'thermal-before.txt')
    if ($thermalBefore.Status -lt 0) { throw 'Unable to read thermal status before RAW recording' }
    if ($thermalBefore.AdmissionStatus -ge 5) {
        throw "RAW recording blocked by emergency thermal state: framework=$($thermalBefore.Status) HAL=$($thermalBefore.CurrentHalMaximumStatus)"
    }
    if ($thermalBefore.Status -lt $MinimumThermalStatus) {
        throw "Thermal admission failed: status=$($thermalBefore.Status) required=$MinimumThermalStatus"
    }
    $rawBefore = @(Read-RawStreamInventory)
    $rawBefore | Set-Content -LiteralPath (Join-Path $runDirectory 'npraw-before.txt')
    Invoke-Adb shell df -h /data | Set-Content -LiteralPath (Join-Path $runDirectory 'storage-before.txt')
    @(
        "checkpoint=$checkpoint"
        "label=$label"
        "resolution=$Resolution"
        "fps=$Fps"
        "aspect=$Aspect"
        "detailProfile=$DetailProfile"
        "pipeline=CINEMA_RAW"
        "apkSha256=$((Invoke-Adb shell sha256sum $packagePath) -join ' ')"
        "model=$((Invoke-Adb shell getprop ro.product.model) -join ' ')"
        "android=$((Invoke-Adb shell getprop ro.build.version.release) -join ' ')"
        "build=$((Invoke-Adb shell getprop ro.build.fingerprint) -join ' ')"
    ) | Set-Content -LiteralPath (Join-Path $runDirectory 'candidate-device-identity.txt')

    Ensure-NprawAuditBinary | Set-Content -LiteralPath (Join-Path $runDirectory 'npraw-auditor-host-path.txt')
    foreach ($tag in @('MainActivity','CameraViewModel','CameraController','RawVideoManager','RecordingStopOrigin')) {
        Invoke-Adb shell setprop "log.tag.$tag" INFO | Out-Null
    }
    Invoke-Adb -Arguments @('logcat', '-b', 'main', '-b', 'system', '-b', 'crash', '-c') | Out-Null
    $logOutput = Join-Path $runDirectory 'continuous-logcat.txt'
    $logError = Join-Path $runDirectory 'continuous-logcat-stderr.txt'
    $focusedOutput = Join-Path $runDirectory 'focused-lifecycle-logcat.txt'
    $focusedError = Join-Path $runDirectory 'focused-lifecycle-logcat-stderr.txt'
    $logProcess = Start-Process -FilePath $AdbPath -ArgumentList @(
        '-s', $Serial, 'logcat', '-b', 'main', '-b', 'system', '-b', 'crash', '-v', 'threadtime'
    ) -RedirectStandardOutput $logOutput -RedirectStandardError $logError -WindowStyle Hidden -PassThru
    $focusedLogProcess = Start-Process -FilePath $AdbPath -ArgumentList @(
        '-s', $Serial, 'logcat', '-b', 'main', '-v', 'threadtime', '-s',
        'MainActivity:I', 'CameraViewModel:I', 'CameraController:I', 'RawVideoManager:I',
        'RecordingStopOrigin:I', 'DebugCommandReceiver:I', '*:S'
    ) -RedirectStandardOutput $focusedOutput -RedirectStandardError $focusedError -WindowStyle Hidden -PassThru

    Start-Sleep -Seconds 1
    Ensure-DeviceAwake | Set-Content -LiteralPath (Join-Path $runDirectory 'power-before-launch.txt')
    Invoke-Adb -Arguments @(
        'shell', 'am', 'start', '-W', '-S',
        '-a', "$packageName.DEBUG_COMMAND",
        '-n', "$packageName/.ui.MainActivity",
        '--es', 'debug_mode', 'VIDEO',
        '--es', 'debug_preset', 'RAW',
        '--es', 'debug_action', 'start_recording',
        '--es', 'debug_validation_label', $label,
        '--es', 'debug_aspect', $Aspect,
        '--es', 'debug_color_profile', 'Natural',
        '--es', 'debug_video_color_space', 'Rec.2020',
        '--es', 'debug_video_hdr_mode', 'OFF',
        '--es', 'debug_video_resolution', $Resolution,
        '--ei', 'debug_video_fps', $Fps.ToString(),
        '--es', 'debug_video_mode', 'RAW',
        '--es', 'debug_video_detail_profile', $DetailProfile,
        # The settings contract exposes the canonical packed-12 RAW master label. The capture
        # boundary independently resolves it to LOSSLESS_REALTIME so every sensor code is retained.
        '--es', 'debug_raw_compression', 'HIGH_COMPRESSION',
        '--ez', 'debug_raw', 'true',
        '--ez', 'debug_log', 'false',
        '--ez', 'debug_uncompressed_cinema_pipeline', 'true'
    ) | Set-Content -LiteralPath (Join-Path $runDirectory 'start-command.txt')

    $executionPhase = 'STARTING'
    $startEvidence = ''
    $currentPid = ''
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        Start-Sleep -Seconds 1
        $currentPid = ((Invoke-Adb shell pidof $packageName) -join ' ').Trim()
        if ($currentPid) {
            if ($recordingProcessId -and $currentPid -ne $recordingProcessId) {
                throw "RAW app process changed during startup: $recordingProcessId -> $currentPid"
            }
            $recordingProcessId = $currentPid
        }
        $appOwnedLogPath = Get-CurrentAppOwnedLogPath
        $startEvidence = if (Test-Path -LiteralPath $focusedOutput) {
            Get-Content -Raw -LiteralPath $focusedOutput -ErrorAction SilentlyContinue
        } else { '' }
        if ($appOwnedLogPath) {
            $startEvidence += "`n" + (Read-AppOwnedLogTail $appOwnedLogPath 512)
        }
        $startEvidence | Set-Content -LiteralPath (Join-Path $runDirectory 'start-verification-unfiltered.txt')
        $startEvidence = Select-QARecordingProcessEvidence -Text $startEvidence -ProcessId $recordingProcessId
        if ($startEvidence -match 'DEBUG_RECORDING_CONFIG_REJECTED|Custom RAW recording failed|RAW_CAPTURE_START.*failed') {
            $startEvidence | Set-Content -LiteralPath (Join-Path $runDirectory 'start-verification.txt')
            throw "RAW recording start failed closed; evidence=$runDirectory"
        }
        $serviceActive = Test-RecordingForegroundServiceActive
        if ($startEvidence -match 'Custom RAW packet recording started' -and
            $startEvidence -match 'RAW_CAPTURE_GATE_OPEN' -and $serviceActive) {
            $recordingWasStarted = $true
            break
        }
    }
    $startEvidence | Set-Content -LiteralPath (Join-Path $runDirectory 'start-verification.txt')
    if (-not $recordingWasStarted) {
        throw "Durable RAW start was not proven with an active foreground service; evidence=$runDirectory"
    }

    $executionPhase = 'RECORDING'
    Write-Output "RECORDING_CONFIRMED checkpoint=$checkpoint label=$label"
    $timer = [Diagnostics.Stopwatch]::StartNew()
    Save-RecordingThermalSample -ElapsedMs 0 | Out-Null
    $nextProgress = 30
    while ($timer.Elapsed.TotalSeconds -lt $DurationSeconds) {
        Start-Sleep -Seconds 10
        $recordingThermal = Save-RecordingThermalSample -ElapsedMs $timer.ElapsedMilliseconds
        if ($recordingThermal.AdmissionStatus -ge 5 -or $recordingThermal.Status -lt 0) {
            $earlyStopMarker = "Thermal safety stop: framework=$($recordingThermal.Status) HAL=$($recordingThermal.CurrentHalMaximumStatus)"
            $earlyStopMarker | Set-Content -LiteralPath (Join-Path $runDirectory 'early-stop-origin.txt')
            Invoke-Adb -Arguments @(
                'shell', 'am', 'broadcast', '-a', "$packageName.DEBUG_COMMAND",
                '-n', "$packageName/.ui.DebugCommandReceiver",
                '--es', 'debug_action', 'stop_recording'
            ) | Set-Content -LiteralPath (Join-Path $runDirectory 'thermal-stop-command.txt')
            $stopWasRequested = $true
            break
        }
        if (Test-Path -LiteralPath $focusedOutput) {
            $stopLines = @(Select-String -LiteralPath $focusedOutput `
                -Pattern 'RECORDING_STOP_ORIGIN committed=true origin=' -ErrorAction SilentlyContinue)
            if ($stopLines.Count -gt 0) {
                $earlyStopMarker = [string]$stopLines[-1].Line
                $earlyStopMarker | Set-Content -LiteralPath (Join-Path $runDirectory 'early-stop-origin.txt')
                break
            }
        }
        if ($timer.Elapsed.TotalSeconds -ge $nextProgress) {
            Write-Output "PROGRESS elapsed=$([math]::Round($timer.Elapsed.TotalSeconds,1))s"
            $nextProgress += 30
        }
    }
    $timer.Stop()

    if ([string]::IsNullOrWhiteSpace($earlyStopMarker)) {
        Invoke-Adb -Arguments @(
            'shell', 'am', 'broadcast', '-a', "$packageName.DEBUG_COMMAND",
            '-n', "$packageName/.ui.DebugCommandReceiver",
            '--es', 'debug_action', 'stop_recording'
        ) | Set-Content -LiteralPath (Join-Path $runDirectory 'stop-command.txt')
        $stopWasRequested = $true
    }

    $executionPhase = 'FINALIZING'
    $rawAfter = @()
    $rawPath = ''
    $finalEvidence = ''
    for ($attempt = 0; $attempt -lt 180; $attempt++) {
        Start-Sleep -Seconds 2
        $rawAfter = @(Read-RawStreamInventory)
        $newPaths = @($rawAfter | Where-Object { $_ -notin $rawBefore })
        if ($newPaths.Count -gt 0) { $rawPath = [string]$newPaths[-1] }
        $appOwnedLogPath = Get-CurrentAppOwnedLogPath
        $finalEvidence = if (Test-Path -LiteralPath $focusedOutput) {
            Get-Content -Raw -LiteralPath $focusedOutput -ErrorAction SilentlyContinue
        } else { '' }
        if ($appOwnedLogPath) {
            $finalEvidence += "`n" + (Read-AppOwnedLogTail $appOwnedLogPath 4096)
        }
        $finalEvidence | Set-Content -LiteralPath (Join-Path $runDirectory 'runtime-evidence-unfiltered.txt')
        $finalEvidence = Select-QARecordingProcessEvidence -Text $finalEvidence -ProcessId $recordingProcessId
        $serviceActive = Test-RecordingForegroundServiceActive
        if ($rawPath -and $finalEvidence -match 'RAW_CAPTURE_STOP' -and -not $serviceActive) { break }
    }
    $rawAfter | Set-Content -LiteralPath (Join-Path $runDirectory 'npraw-after.txt')
    $finalEvidence | Set-Content -LiteralPath (Join-Path $runDirectory 'runtime-evidence.txt')
    if (-not $rawPath) { throw "No newly finalized NPRAW stream was found; evidence=$runDirectory" }

    $startMatch = [regex]::Matches(
        $finalEvidence,
        'Custom RAW packet recording started readout=(\d+)x(\d+).*?fps=(\d+)'
    ) | Select-Object -Last 1
    if (-not $startMatch) { throw "RAW readout/cadence start evidence is missing; evidence=$runDirectory" }
    $actualWidth = [int]$startMatch.Groups[1].Value
    $actualHeight = [int]$startMatch.Groups[2].Value
    $actualFps = [int]$startMatch.Groups[3].Value
    $stopMatch = [regex]::Matches(
        $finalEvidence,
        'RAW_CAPTURE_STOP frames=(\d+).*?drops=(\d+).*?captureMs=(\d+).*?measuredSensorFps=([0-9.]+)'
    ) | Select-Object -Last 1
    if (-not $stopMatch) { throw "RAW terminal cadence evidence is missing; evidence=$runDirectory" }
    $reportedFrames = [long]$stopMatch.Groups[1].Value
    $reportedDrops = [long]$stopMatch.Groups[2].Value
    $reportedCaptureMs = [long]$stopMatch.Groups[3].Value
    $reportedFps = [double]::Parse($stopMatch.Groups[4].Value, [Globalization.CultureInfo]::InvariantCulture)
    $stopOriginMatch = [regex]::Matches(
        $finalEvidence,
        'RECORDING_STOP_ORIGIN committed=true origin=([A-Z_]+)[^\r\n]*label=([^\s]*)'
    ) | Select-Object -Last 1
    $stopOrigin = if ($stopOriginMatch) { $stopOriginMatch.Groups[1].Value } else { '' }
    $stopLabel = if ($stopOriginMatch) { $stopOriginMatch.Groups[2].Value } else { '' }
    $detailProfileValid = $finalEvidence -match (
        'appliedDebugConfig[^\r\n]*detailProfile=' + [regex]::Escape($DetailProfile)
    )
    $formatValid = $actualFps -eq $Fps -and
        $finalEvidence -match ('appliedDebugConfig[^\r\n]*resolution=' + [regex]::Escape($Resolution)) -and
        $finalEvidence -notmatch 'DEBUG_RECORDING_CONFIG_REJECTED'

    $executionPhase = 'AUDITING'
    $audit = Invoke-NprawAudit -RemotePath $rawPath -Width $actualWidth -Height $actualHeight
    $minimumDurationMs = [math]::Max(0L, ([long]$DurationSeconds * 1000L) - 2000L)
    $minimumFrames = [long][math]::Floor(($minimumDurationMs / 1000.0) * $Fps)
    $cadenceValid = $audit.status -eq 'PASS' -and
        [math]::Abs([double]$audit.measuredFps - [double]$Fps) -le 0.5 -and
        [long]$audit.frameCount -ge $minimumFrames -and
        [long]$audit.crcFailures -eq 0L -and
        [long]$audit.fieldFailures -eq 0L -and
        [long]$audit.nonMonotonicGaps -eq 0L -and
        [long]$audit.gapsOver60ms -eq 0L -and
        $reportedDrops -eq 0L
    $durationValid = $reportedCaptureMs -ge $minimumDurationMs
    $plannedStopValid = [string]::IsNullOrWhiteSpace($earlyStopMarker) -and
        $stopOrigin -eq 'DEBUG_COMMAND' -and $stopLabel -eq $label
    $thermalAfter = Read-ThermalSnapshot
    $thermalAfter.Text | Set-Content -LiteralPath (Join-Path $runDirectory 'thermal-after.txt')
    $thermalValid = $thermalBefore.Status -ge $MinimumThermalStatus -and
        $thermalAfter.Status -ge $MinimumThermalStatus
    $remoteBytes = [long](((Invoke-Adb shell stat -c %s $rawPath) -join '').Trim())
    Invoke-Adb shell df -h /data | Set-Content -LiteralPath (Join-Path $runDirectory 'storage-after.txt')

    [pscustomobject]@{
        schema = 'neo-qa-hypergrid/lq001-raw-endurance-run/v1'
        checkpoint = $checkpoint
        mode = 'RAW'
        pipeline = 'CINEMA'
        label = $label
        requested_resolution = $Resolution
        actual_raw_resolution = "${actualWidth}x${actualHeight}"
        requested_fps = $Fps
        reported_sensor_fps = $reportedFps
        audited_sensor_fps = [double]$audit.measuredFps
        requested_detail_profile = $DetailProfile
        detail_profile_evidence_valid = $detailProfileValid
        requested_duration_seconds = $DurationSeconds
        reported_capture_duration_ms = $reportedCaptureMs
        duration_evidence_valid = $durationValid
        reported_frames = $reportedFrames
        audited_frames = [long]$audit.frameCount
        reported_drops = $reportedDrops
        cadence_evidence_valid = $cadenceValid
        npraw_audit_status = $audit.status
        npraw_audit_exit_code = $audit.processExitCode
        npraw_crc_failures = [long]$audit.crcFailures
        npraw_non_monotonic_gaps = [long]$audit.nonMonotonicGaps
        npraw_gaps_over_60ms = [long]$audit.gapsOver60ms
        npraw_storage_bit_depth = [int]$audit.storageBitDepth
        npraw_remote_path = $rawPath
        npraw_remote_bytes = $remoteBytes
        format_selection_evidence = $formatValid
        recording_stop_origin = $stopOrigin
        planned_stop_origin_valid = $plannedStopValid
        early_stop_marker = $earlyStopMarker
        thermal_before_status = $thermalBefore.Status
        thermal_after_status = $thermalAfter.Status
        thermal_before_current_hal_skin_c = $thermalBefore.SkinC
        thermal_after_current_hal_skin_c = $thermalAfter.SkinC
        thermal_before_skin_source = $thermalBefore.SkinSource
        thermal_after_skin_source = $thermalAfter.SkinSource
        thermal_series_continuous = $false
        thermal_series_sample_count = $recordingThermalSamples.Count
        thermal_series_measurement = 'Periodic approximately 10-second HAL snapshots; not per-frame thermal proof'
        thermal_series_minimum_status = ($recordingThermalSamples | Measure-Object -Property framework_status -Minimum).Minimum
        thermal_series_maximum_status = ($recordingThermalSamples | Measure-Object -Property framework_status -Maximum).Maximum
        thermal_evidence_valid = $thermalValid
        evidence_directory = $runDirectory
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $runDirectory 'run-result.json')

    if (-not $formatValid) { throw "RAW requested format was not proven; evidence=$runDirectory" }
    if (-not $detailProfileValid) { throw "RAW detail profile was not applied; evidence=$runDirectory" }
    if (-not $durationValid) { throw "RAW duration was below the required boundary; evidence=$runDirectory" }
    if (-not $cadenceValid) { throw "RAW zero-drop $Fps FPS cadence was not proven; evidence=$runDirectory" }
    if (-not $plannedStopValid) { throw "RAW stop was not the planned debug boundary; origin=$stopOrigin evidence=$runDirectory" }
    if (-not $thermalValid) { throw "RAW thermal evidence did not satisfy status $MinimumThermalStatus; evidence=$runDirectory" }
    $executionPhase = 'VALIDATED'
    Write-Output "RUN_COMPLETE checkpoint=$checkpoint profile=$DetailProfile resolution=$Resolution actual=${actualWidth}x${actualHeight} fps=$Fps frames=$($audit.frameCount) thermalBefore=$($thermalBefore.Status) thermalAfter=$($thermalAfter.Status) evidence=$runDirectory"
}
catch {
    # Startup/configuration failures do not reach the ordinary recording result. Preserve an
    # explicit failed attempt, without inventing frame counts or overwriting a terminal result.
    [pscustomobject]@{
        schema = 'neo-qa-hypergrid/lq001-raw-attempt-failure/v1'
        checkpoint = $checkpoint
        label = $label
        phase = $executionPhase
        status = 'FAIL'
        mode = 'RAW'
        pipeline = 'CINEMA'
        requested_resolution = $Resolution
        requested_fps = $Fps
        requested_detail_profile = $DetailProfile
        requested_duration_seconds = $DurationSeconds
        recording_started = $recordingWasStarted
        recording_process_id = $recordingProcessId
        failure_type = $_.Exception.GetType().FullName
        failure = $_.Exception.Message
        candidate_identity_evidence = (Join-Path $runDirectory 'candidate-device-identity.txt')
        measured_result_present = (Test-Path -LiteralPath (Join-Path $runDirectory 'run-result.json'))
        evidence_directory = $runDirectory
        production_ready = $false
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $runDirectory 'run-failure.json')
    throw
}
finally {
    if ($recordingWasStarted -and -not $stopWasRequested -and
        [string]::IsNullOrWhiteSpace($earlyStopMarker)) {
        & $AdbPath -s $Serial shell am broadcast -a "$packageName.DEBUG_COMMAND" `
            -n "$packageName/.ui.DebugCommandReceiver" --es debug_action stop_recording | Out-Null
        Start-Sleep -Seconds 5
    }
    if ($logProcess -and -not $logProcess.HasExited) {
        Stop-Process -Id $logProcess.Id -Force -ErrorAction SilentlyContinue
    }
    if ($focusedLogProcess -and -not $focusedLogProcess.HasExited) {
        Stop-Process -Id $focusedLogProcess.Id -Force -ErrorAction SilentlyContinue
    }
    & $AdbPath -s $Serial logcat -d -b main -b system -b crash -v threadtime 2>$null |
        Set-Content -LiteralPath (Join-Path $runDirectory 'logcat-final.txt')
    if (-not [string]::IsNullOrWhiteSpace($appOwnedLogPath)) {
        Read-AppOwnedLogTail $appOwnedLogPath 4096 |
            Set-Content -LiteralPath (Join-Path $runDirectory 'app-persistent-log-postmortem.txt')
    }
    & $AdbPath -s $Serial shell am force-stop $packageName 2>$null | Out-Null
    & $AdbPath -s $Serial shell input keyevent KEYCODE_SLEEP 2>$null | Out-Null
    @(
        "released_utc=$([DateTime]::UtcNow.ToString('o'))"
        "evidence=$runDirectory"
        "recording_started=$recordingWasStarted"
        "stop_requested=$stopWasRequested"
    ) | Set-Content -LiteralPath $lockPath
}
