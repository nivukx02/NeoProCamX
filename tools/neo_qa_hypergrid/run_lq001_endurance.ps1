param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('VIDEO', 'PRO')]
    [string]$Mode,

    [ValidateSet('DIRECT', 'CINEMA')]
    [string]$Pipeline = 'DIRECT',

    [ValidateSet('NATURAL_DETAIL', 'FOCUSED_DETAIL', 'EXTREME_DETAIL')]
    [string]$DetailProfile = 'FOCUSED_DETAIL',

    [ValidateSet('4096x3072', '3840x2160', '3264x2448', '2048x1080', '1920x1080', '1280x720')]
    [string]$Resolution = '3840x2160',

    [ValidateSet(25, 30)]
    [int]$Fps = 25,

    [ValidateSet('AUTO', '4:3', '16:9')]
    [string]$Aspect = 'AUTO',

    [bool]$AfRecordingUnlock = $true,

    [bool]$GlPreview = $true,

    [bool]$P010CadenceSafeSession = $true,

    [bool]$GpuStageTiming = $false,

    [bool]$EglDecodeOnce = $true,

    [bool]$EglGatherGrid = $false,

    # Some vendor camera HALs lose capture cadence when repeated adb shell processes are launched
    # during a thermally constrained recording. Default to boundary-only device inspection so the
    # observer cannot perturb the flow it is measuring.
    [bool]$CaptureSafeMonitoring = $true,

    [ValidateRange(0, 6)]
    [int]$MinimumThermalStatus = 0,

    [string]$Serial = '10BF8M2NDX003P3',
    [string]$AdbPath = 'C:\Users\ELCOT\AppData\Local\Android\Sdk\platform-tools\adb.exe',
    [string]$EvidenceRoot = 'D:\NeoProCamX_QA',

    # Explicit lifecycle regression only. Zero keeps the ordinary matrix procedure unchanged.
    [ValidateRange(0, 7000)]
    [int]$BackgroundAtSeconds = 0,

    [ValidateRange(1, 30)]
    [int]$BackgroundDurationSeconds = 5,
    [ValidateRange(35, 7200)]
    [int]$DurationSeconds = 610
)

$ErrorActionPreference = 'Stop'
if ($BackgroundAtSeconds -gt 0 -and
    ($BackgroundAtSeconds + $BackgroundDurationSeconds + 5) -ge $DurationSeconds) {
    throw 'Lifecycle interruption must leave time to resume and verify before the recording ends'
}
. (Join-Path $PSScriptRoot 'bounded_adb_cleanup.ps1')
. (Join-Path $PSScriptRoot 'passive_recording_log.ps1')
. (Join-Path $PSScriptRoot 'thermal_evidence.ps1')
$packageName = 'com.neoproc.neoproccamx'
$checkpoint = if ($Mode -eq 'VIDEO') { 'LQ-001.05' } else { 'LQ-001.06' }
$resolutionParts = $Resolution -split 'x'
$requestedWidth = [int]$resolutionParts[0]
$requestedHeight = [int]$resolutionParts[1]
$isFourK = [math]::Max($requestedWidth, $requestedHeight) -ge 3840
if ($isFourK -and $Fps -ne 25) {
    throw "$Resolution is a 4K-class selection and only exact 25 FPS is permitted"
}
$resolvedAspect = if ($Aspect -ne 'AUTO') {
    $Aspect
} elseif ($requestedWidth * 3 -eq $requestedHeight * 4) {
    '4:3'
} else {
    '16:9'
}
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$labelMode = $Mode.ToLowerInvariant()
$labelPipeline = $Pipeline.ToLowerInvariant()
$labelDetailProfile = $DetailProfile.ToLowerInvariant()
$label = "lq001_$($checkpoint.Split('.')[-1])_${labelMode}_${labelPipeline}_${labelDetailProfile}_${Resolution}_${Fps}fps_hot_endurance_$stamp"
$runDirectory = Join-Path $EvidenceRoot $label
$lockPath = Join-Path $EvidenceRoot '.neo-qa-exclusive-adb.lock'
$logProcess = $null
$focusedLogProcess = $null
$eventLogProcess = $null
$persistentLogProcess = $null
$recordingWasStarted = $false
$stopWasRequested = $false
$earlyStopMarker = ''
$externalStopMarker = ''
$appOwnedLogPath = ''
$persistentStartVerification = ''
$primaryTestFailure = ''
$terminalDirectFailure = ''

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
        # KEYCODE_WAKEUP and `cmd power wakeup` are acknowledged but ignored by some vivo builds
        # after an explicit KEYCODE_SLEEP. Guard the toggle with a fresh state read so POWER can
        # never turn an already-awake panel off.
        Invoke-Adb shell input keyevent KEYCODE_POWER | Out-Null
        for ($attempt = 0; $attempt -lt 12; $attempt++) {
            Start-Sleep -Milliseconds 250
            $powerState = Read-PowerState
            if ($powerState -match 'mWakefulness=Awake') { break }
        }
    }
    if ($powerState -notmatch 'mWakefulness=Awake') {
        throw "Device did not reach the Awake state"
    }
    Invoke-Adb shell wm dismiss-keyguard | Out-Null
    Invoke-Adb shell input keyevent KEYCODE_MENU | Out-Null
    Start-Sleep -Milliseconds 500
    $powerState = Read-PowerState
    if ($powerState -notmatch 'mWakefulness=Awake') {
        throw "Device returned to sleep during wake admission"
    }
    return $powerState
}

function Save-AdbExecOutBinary {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$Destination
    )
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $AdbPath
    $escapedArguments = @('-s', $Serial) + $Arguments | ForEach-Object {
        '"' + ([string]$_).Replace('"', '\"') + '"'
    }
    $startInfo.Arguments = $escapedArguments -join ' '
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    $output = $null
    try {
        if (-not $process.Start()) { throw 'Unable to start adb exec-out process' }
        $output = [System.IO.File]::Create($Destination)
        $process.StandardOutput.BaseStream.CopyTo($output)
        $output.Flush()
        $output.Dispose()
        $output = $null
        $stderr = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            throw "adb exec-out failed ($($process.ExitCode)): $stderr"
        }
        if ((Get-Item -LiteralPath $Destination).Length -le 0L) {
            throw 'adb exec-out produced an empty binary artifact'
        }
    } finally {
        if ($output) { $output.Dispose() }
        $process.Dispose()
    }
}

function Read-ThermalSnapshot {
    $thermalText = (Invoke-Adb shell dumpsys thermalservice) -join "`n"
    ConvertFrom-QAThermalServiceSnapshot -Text $thermalText
}

function Read-BatteryTemperature {
    $batteryText = (Invoke-Adb shell dumpsys battery) -join "`n"
    $match = [regex]::Match($batteryText, 'temperature:\s*(\d+)')
    if ($match.Success) { return $match.Groups[1].Value }
    return 'NA'
}

function Read-LightweightThermalSample {
    param([Parameter(Mandatory = $true)][string]$BoundaryStatus)

    # Do not call dumpsys while the camera owns its real-time capture window. On the target vivo
    # build, thermalservice/battery/activity dumps all fan out through Binder and can briefly
    # contend with the vendor camera service. The resulting pause is long enough to look like one
    # missing 25-fps sensor frame even when every app-owned queue remains lossless. These sysfs
    # reads are bounded, allocation-light and do not ask system_server to enumerate services.
    $skinRaw = ((Invoke-Adb shell cat /sys/class/thermal/thermal_zone68/temp) -join '').Trim()
    $batteryRaw = ((Invoke-Adb shell cat /sys/class/thermal/thermal_zone79/temp) -join '').Trim()
    $skinC = if ($skinRaw -match '^\d+$') {
        ([double]$skinRaw / 1000.0).ToString(
            '0.000',
            [Globalization.CultureInfo]::InvariantCulture
        )
    } else { 'NA' }
    $batteryDeciC = if ($batteryRaw -match '^\d+$') {
        [long][math]::Round([double]$batteryRaw / 100.0)
    } else { 'NA' }
    [pscustomobject]@{
        Status = $BoundaryStatus
        SkinC = $skinC
        BatteryDeciC = $batteryDeciC
    }
}

function Read-DataAvailableKib {
    $line = ((Invoke-Adb shell df -k /data | Select-Object -Last 1) -join ' ').Trim()
    $fields = $line -split '\s+'
    if ($fields.Count -ge 4) { return $fields[3] }
    return 'NA'
}

function Read-MediaStore {
    (Invoke-Adb shell content query `
        --uri content://media/external/video/media `
        --projection _id:_display_name:relative_path:is_pending:_size:duration:date_modified) -join "`n"
}

function Test-RecordingForegroundServiceActive {
    $serviceState = (Invoke-Adb shell dumpsys activity services $packageName) -join "`n"
    return $serviceState -match [regex]::Escape(
        "$packageName/.recording.RecordingForegroundService"
    )
}

function Read-MediaDurationMs {
    param([string]$Row)
    $match = [regex]::Match([string]$Row, '(?:^|,\s*)duration=([0-9]+)(?:,|$)')
    if (-not $match.Success) { return -1L }
    return [long]$match.Groups[1].Value
}

function Find-LabeledMediaRow {
    param([string]$Rows)
    $Rows -split "`r?`n" |
        Where-Object { $_ -like "*$label*" } |
        Select-Object -Last 1
}

function Read-Npv10Directory {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    try {
        $lines = & $AdbPath -s $Serial shell run-as $packageName find files/CamXNPV10 -maxdepth 1 -type f 2>$null
        if ($LASTEXITCODE -ne 0) { return '' }
        return ($lines -join "`n")
    } finally {
        $ErrorActionPreference = $previousPreference
    }
}

function Find-LabeledNpv10Master {
    param([string]$Rows)
    $escapedLabel = [regex]::Escape($label)
    $Rows -split "`r?`n" |
        Where-Object { $_ -match "$escapedLabel.*\.npv10$" } |
        Select-Object -Last 1
}

function Read-Npv10Marker {
    param([string]$MasterPath)
    if ([string]::IsNullOrWhiteSpace($MasterPath)) { return '' }
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    try {
        $text = & $AdbPath -s $Serial exec-out run-as $packageName cat "$MasterPath.verified" 2>$null
        if ($LASTEXITCODE -ne 0) { return '' }
        return ($text -join "`n")
    } finally {
        $ErrorActionPreference = $previousPreference
    }
}

function Read-AppPrivateText {
    param([string]$RelativePath)
    if ([string]::IsNullOrWhiteSpace($RelativePath)) { return '' }
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    try {
        $text = & $AdbPath -s $Serial exec-out run-as $packageName cat $RelativePath 2>$null
        if ($LASTEXITCODE -ne 0) { return '' }
        return ($text -join "`n")
    } finally {
        $ErrorActionPreference = $previousPreference
    }
}

function Get-CurrentAppOwnedLogPath {
    $appLogRoot = "/sdcard/Android/data/$packageName/files"
    $command = "ls -1t $appLogRoot/neoproc_*.log 2>/dev/null | head -n 1"
    return ((Invoke-Adb shell $command) -join '').Trim()
}

function Read-CurrentProcessStartupTelemetry {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$RemotePath,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$ProcessId
    )
    if ([string]::IsNullOrWhiteSpace($RemotePath) -or
        [string]::IsNullOrWhiteSpace($ProcessId)) {
        return ''
    }
    $command = "grep -E 'DIRECT_RECORDING_START_COMMIT|NPV10_STARTUP_COMMIT|" +
        "NPV10_USER_CONTROL_LATE_BOUNDARY|DEBUG_RECORDING_CONFIG_REJECTED' " +
        "$RemotePath 2>/dev/null | tail -n 64"
    $lines = @(Invoke-Adb shell $command)
    # Every NeoLogger record contains its producer PID. This prevents an older process/file from
    # satisfying the current candidate's durable-start gate if file creation or directory metadata
    # is delayed by the vendor filesystem.
    return ($lines | Where-Object { $_ -match "\(pid:$([regex]::Escape($ProcessId))\)" }) -join "`n"
}

function Read-AppOwnedLogTail {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$RemotePath,
        [int]$MaximumLines = 2048
    )
    if ([string]::IsNullOrWhiteSpace($RemotePath)) { return '' }
    $command = "tail -n $MaximumLines $RemotePath 2>/dev/null"
    return (Invoke-Adb shell $command) -join "`n"
}

function ConvertFrom-KeyValueEvidence {
    param([string]$Text)
    $fields = @{}
    foreach ($line in ($Text -split "`r?`n")) {
        $separator = $line.IndexOf('=')
        if ($separator -gt 0) {
            $fields[$line.Substring(0, $separator)] = $line.Substring($separator + 1).Trim()
        }
    }
    return $fields
}

function Find-LabeledQualityCertificate {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    try {
        $rows = & $AdbPath -s $Serial shell run-as $packageName find `
            files/recording_quality_certificates -maxdepth 1 -type f 2>$null
        if ($LASTEXITCODE -ne 0) { return '' }
        $escapedLabel = [regex]::Escape($label)
        return $rows |
            Where-Object { $_ -match "$escapedLabel.*\.quality-certificate\.txt$" } |
            Select-Object -Last 1
    } finally {
        $ErrorActionPreference = $previousPreference
    }
}

function Test-Npv10Marker {
    param([string]$MarkerText)
    if ([string]::IsNullOrWhiteSpace($MarkerText)) { return $false }
    $fields = @{}
    foreach ($line in ($MarkerText -split "`r?`n")) {
        $separator = $line.IndexOf('=')
        if ($separator -gt 0) {
            $fields[$line.Substring(0, $separator)] = $line.Substring($separator + 1)
        }
    }
    $frameCount = if ($fields.ContainsKey('frameCount')) { [long]$fields['frameCount'] } else { 0L }
    $ingress = if ($fields.ContainsKey('captureIngressFrames')) { [long]$fields['captureIngressFrames'] } else { -1L }
    $accepted = if ($fields.ContainsKey('captureAcceptedFrames')) { [long]$fields['captureAcceptedFrames'] } else { -2L }
    $committed = if ($fields.ContainsKey('captureCommittedFrames')) { [long]$fields['captureCommittedFrames'] } else { -3L }
    return $frameCount -gt 0 -and
        $ingress -eq $frameCount -and
        $accepted -eq $frameCount -and
        $committed -eq $frameCount -and
        $fields['captureInvalidTimestamps'] -eq '0' -and
        $fields['captureNonMonotonicIngress'] -eq '0' -and
        $fields['captureMissingCadenceFrames'] -eq '0' -and
        $fields['masterCertificateStatus'] -eq 'PASS' -and
        $fields['auditStatus'] -eq 'PASS' -and
        $fields['eliteVideoQualityAuditStatus'] -eq 'PASS' -and
        $fields['droppedFrames'] -eq '0' -and
        $fields['crcCheckPass'] -eq 'true' -and
        $fields['byteCountCheckPass'] -eq 'true' -and
        $fields['sessionHashCheckPass'] -eq 'true' -and
        $fields['timestampCheckPass'] -eq 'true'
}

if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    throw "ADB executable not found: $AdbPath"
}
New-Item -ItemType Directory -Path $EvidenceRoot -Force | Out-Null

if (Test-Path -LiteralPath $lockPath) {
    $existingLock = Get-Content -Raw -LiteralPath $lockPath -ErrorAction SilentlyContinue
    $pidMatch = [regex]::Match([string]$existingLock, 'host_pid=(\d+)')
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
$runDirectory | Set-Content -LiteralPath (Join-Path $EvidenceRoot 'latest-lq001-endurance-path.txt')

try {
    $connectedSerials = @(
        & $AdbPath devices |
            Select-Object -Skip 1 |
            ForEach-Object { ($_ -split '\s+')[0] } |
            Where-Object { $_ }
    )
    if ($connectedSerials -notcontains $Serial) {
        throw "Requested ADB device is unavailable: $Serial"
    }

    Invoke-Adb shell am force-stop $packageName | Out-Null
    Invoke-Adb shell dumpsys activity exit-info $packageName |
        Set-Content -LiteralPath (Join-Path $runDirectory 'exit-info-before.txt')
    $powerState = Ensure-DeviceAwake
    $powerState | Set-Content -LiteralPath (Join-Path $runDirectory 'power-admission.txt')

    $packagePath = ((Invoke-Adb shell pm path $packageName) -replace '^package:', '').Trim()
    if (-not $packagePath) { throw "Installed APK path is unavailable for $packageName" }
    @(
        "checkpoint=$checkpoint"
        "label=$label"
        "resolution=$Resolution"
        "fps=$Fps"
        "aspect=$resolvedAspect"
        "detailProfile=$DetailProfile"
        "afRecordingUnlock=$AfRecordingUnlock"
        "glPreview=$GlPreview"
        "p010CadenceSafeSession=$P010CadenceSafeSession"
        "gpuStageTiming=$GpuStageTiming"
        "eglDecodeOnce=$EglDecodeOnce"
        "eglGatherGrid=$EglGatherGrid"
        "captureSafeMonitoring=$CaptureSafeMonitoring"
        "minimumThermalStatus=$MinimumThermalStatus"
        "serial=$Serial"
        "packagePath=$packagePath"
        "apkSha256=$((Invoke-Adb shell sha256sum $packagePath) -join ' ')"
        "model=$((Invoke-Adb shell getprop ro.product.model) -join ' ')"
        "android=$((Invoke-Adb shell getprop ro.build.version.release) -join ' ')"
        "build=$((Invoke-Adb shell getprop ro.build.fingerprint) -join ' ')"
        "startedUtc=$([DateTime]::UtcNow.ToString('o'))"
    ) | Set-Content -LiteralPath (Join-Path $runDirectory 'candidate-device-identity.txt')

    $thermalBefore = Read-ThermalSnapshot
    $thermalBefore.Text | Set-Content -LiteralPath (Join-Path $runDirectory 'thermal-before.txt')
    $thermalBeforeStatus = 0
    if (-not [int]::TryParse($thermalBefore.Status, [ref]$thermalBeforeStatus)) {
        throw 'Unable to read the Android thermal status before recording'
    }
    if ($thermalBeforeStatus -lt 0) { throw 'Missing Android thermal status before recording' }
    if ($thermalBefore.AdmissionStatus -ge 5) {
        throw "Recording blocked by emergency thermal state: framework=$thermalBeforeStatus HAL=$($thermalBefore.CurrentHalMaximumStatus)"
    }
    if ($thermalBeforeStatus -lt $MinimumThermalStatus) {
        throw "Thermal admission failed: status=$thermalBeforeStatus required=$MinimumThermalStatus"
    }
    Invoke-Adb shell dumpsys battery |
        Set-Content -LiteralPath (Join-Path $runDirectory 'battery-before.txt')
    Invoke-Adb shell df -h /data |
        Set-Content -LiteralPath (Join-Path $runDirectory 'storage-before.txt')
    Read-MediaStore |
        Set-Content -LiteralPath (Join-Path $runDirectory 'mediastore-before.txt')
    Read-Npv10Directory |
        Set-Content -LiteralPath (Join-Path $runDirectory 'npv10-before.txt')

    # Keep app-owned INFO telemetry available on vendor builds whose global log level suppresses
    # unqualified tags, and avoid the very noisy events buffer evicting start/finalization proof.
    Invoke-Adb shell setprop log.tag.QuickTimeMovWriter INFO | Out-Null
    Invoke-Adb shell setprop log.tag.RecordingManager INFO | Out-Null
    Invoke-Adb shell setprop log.tag.Npv10FileWriter INFO | Out-Null
    Invoke-Adb shell setprop log.tag.MainActivity INFO | Out-Null
    Invoke-Adb shell setprop log.tag.CameraController INFO | Out-Null
    Invoke-Adb shell setprop log.tag.RecordingThermalIsolation INFO | Out-Null
    Invoke-Adb shell setprop log.tag.CameraThermalStatus INFO | Out-Null
    Invoke-Adb shell setprop log.tag.RecordingStopOrigin INFO | Out-Null
    # Preserve the native GPU and queue summaries needed to prove a thermal root fix. Some vendor
    # images retain a per-tag WARN override across package reinstall, which previously left cadence
    # valid but discarded the diagnostic reason it stayed valid.
    Invoke-Adb shell setprop log.tag.OffscreenAhbEgl INFO | Out-Null
    Invoke-Adb shell setprop log.tag.VulkanEncoder INFO | Out-Null
    Invoke-Adb shell setprop log.tag.FramePipeline INFO | Out-Null
    # MediaCodec's platform PipelineWatcher can emit one DEBUG line per frame on this device.
    # Keep warnings/errors while removing QA-only logging heat and logd churn from endurance runs.
    Invoke-Adb shell setprop log.tag.PipelineWatcher WARN | Out-Null
    # CameraViewModel emits multi-kilobyte parity snapshots at INFO. On this vivo build those can
    # exhaust logd's per-UID quota and evict the earlier preallocation proof. WARN still retains
    # fail-closed settings/camera errors while the focused writer/manager tags preserve lifecycle proof.
    Invoke-Adb shell setprop log.tag.CameraViewModel WARN | Out-Null
    Invoke-Adb -Arguments @('logcat', '-b', 'main', '-b', 'system', '-b', 'crash', '-b', 'events', '-c') | Out-Null
    $logOutput = Join-Path $runDirectory 'continuous-logcat.txt'
    $logError = Join-Path $runDirectory 'continuous-logcat-stderr.txt'
    $focusedLogOutput = Join-Path $runDirectory 'focused-lifecycle-logcat.txt'
    $focusedLogError = Join-Path $runDirectory 'focused-lifecycle-logcat-stderr.txt'
    $eventLogOutput = Join-Path $runDirectory 'system-events-logcat.txt'
    $eventLogError = Join-Path $runDirectory 'system-events-logcat-stderr.txt'
    $logProcess = Start-Process `
        -FilePath $AdbPath `
        -ArgumentList @('-s', $Serial, 'logcat', '-b', 'main', '-b', 'system', '-b', 'crash', '-v', 'threadtime') `
        -RedirectStandardOutput $logOutput `
        -RedirectStandardError $logError `
        -WindowStyle Hidden `
        -PassThru
    # The events buffer is the authoritative source for process death. Keep this passive stream
    # running for the whole capture window so OEM thermal/power force-stops cannot be mistaken for
    # an app crash, queue loss, or codec failure after the ring buffer rotates.
    $eventLogProcess = Start-Process `
        -FilePath $AdbPath `
        -ArgumentList @('-s', $Serial, 'logcat', '-b', 'events', '-v', 'threadtime') `
        -RedirectStandardOutput $eventLogOutput `
        -RedirectStandardError $eventLogError `
        -WindowStyle Hidden `
        -PassThru
    # Capture lifecycle proof continuously with server-side tag filtering. Some vendor camera HALs
    # can emit thousands of property warnings per second and evict app-owned start markers from the
    # shared logd ring before a later `logcat -d` poll observes them.
    $focusedLogProcess = Start-Process `
        -FilePath $AdbPath `
        -ArgumentList @(
            '-s', $Serial, 'logcat', '-b', 'main', '-v', 'threadtime', '-s',
            'MainActivity:I', 'CameraViewModel:I', 'CameraController:I', 'RecordingManager:I',
            'Npv10RecordingManager:I', 'Npv10FileWriter:I', 'DebugCommandReceiver:I',
            'RecordingStopOrigin:I', 'CameraThermalStatus:I', '*:S'
        ) `
        -RedirectStandardOutput $focusedLogOutput `
        -RedirectStandardError $focusedLogError `
        -WindowStyle Hidden `
        -PassThru

    Start-Sleep -Seconds 1
    # Reassert the wake state immediately beside activity launch. Identity, MediaStore, thermal,
    # and logcat preflight can take long enough for this vivo build to return to sleep even though
    # the earlier admission snapshot was Awake. Launching during that race destroys the preview
    # surface before Camera2 opens and produces an invalid endurance run.
    $launchPowerState = Ensure-DeviceAwake
    $launchPowerState |
        Set-Content -LiteralPath (Join-Path $runDirectory 'power-immediately-before-launch.txt')
    if ($launchPowerState -notmatch 'mWakefulness=Awake') {
        throw "Device fell asleep immediately before camera launch"
    }

    $preset = if ($Mode -eq 'PRO') { 'PRO' } else { 'VIDEO' }
    $sdrConversionPipeline = if ($Pipeline -eq 'CINEMA') { 'true' } else { 'false' }
    Invoke-Adb -Arguments @(
        'shell', 'am', 'start', '-W', '-S',
        '-a', "$packageName.DEBUG_COMMAND",
        '-n', "$packageName/.ui.MainActivity",
        # Video and Pro are presets on the same VIDEO recording surface. `AppMode.PRO` is the
        # separate still/manual UI and must never receive an endurance-recording command.
        '--es', 'debug_mode', 'VIDEO',
        '--es', 'debug_preset', $preset,
        '--es', 'debug_action', 'start_recording',
        '--es', 'debug_validation_label', $label,
        '--es', 'debug_aspect', $resolvedAspect,
        '--es', 'debug_color_profile', 'Natural',
        '--es', 'debug_video_color_space', 'Rec.709',
        '--es', 'debug_video_hdr_mode', 'OFF',
        '--es', 'debug_video_resolution', $Resolution,
        '--ei', 'debug_video_fps', $Fps.ToString(),
        '--es', 'debug_video_mode', 'PRO',
        '--es', 'debug_video_detail_profile', $DetailProfile,
        '--ez', 'debug.af_recording_unlock', $AfRecordingUnlock.ToString().ToLowerInvariant(),
        '--ez', 'debug.gl_preview', $GlPreview.ToString().ToLowerInvariant(),
        '--ez', 'debug.p010_cadence_safe_session', $P010CadenceSafeSession.ToString().ToLowerInvariant(),
        '--ez', 'debug.egl_gpu_stage_timing', $GpuStageTiming.ToString().ToLowerInvariant(),
        '--ez', 'debug.egl_decode_once', $EglDecodeOnce.ToString().ToLowerInvariant(),
        '--ez', 'debug.egl_gather_grid', $EglGatherGrid.ToString().ToLowerInvariant(),
        '--ez', 'debug_uncompressed_cinema_pipeline', 'true',
        '--ez', 'debug_sdr_conversion_pipeline', $sdrConversionPipeline
    ) |
        Set-Content -LiteralPath (Join-Path $runDirectory 'start-command.txt')

    $startVerification = ''
    $serviceVerification = ''
    $durableStartPattern = if ($Pipeline -eq 'CINEMA') {
        'NPV10_STARTUP_COMMIT stable=true'
    } else {
        'DIRECT_RECORDING_START_COMMIT stable=true'
    }
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        Start-Sleep -Seconds 1
        $startVerification = (Invoke-Adb -Arguments @(
            'logcat', '-d', '-v', 'threadtime', '-s',
            'MainActivity:I', 'CameraViewModel:I', 'CameraController:I', 'RecordingManager:I',
            'DebugCommandReceiver:I', '*:S'
        )) -join "`n"
        if (Test-Path -LiteralPath $focusedLogOutput) {
            $focusedStartVerification = Get-Content -Raw -LiteralPath $focusedLogOutput `
                -ErrorAction SilentlyContinue
            if ($focusedStartVerification) {
                $startVerification = $startVerification + "`n" + $focusedStartVerification
            }
        }
        # NeoLogger initialization is asynchronous. Re-resolve the newest file on each startup
        # poll so an older process's log cannot remain pinned merely because it existed first.
        # Both Direct and Cinema publish their durable transaction commit here because some vendor
        # builds suppress or evict third-party INFO records from logd under camera/HAL load.
        $latestAppOwnedLogPath = Get-CurrentAppOwnedLogPath
        if (-not [string]::IsNullOrWhiteSpace($latestAppOwnedLogPath)) {
            $appOwnedLogPath = $latestAppOwnedLogPath
        }
        $currentAppPid = @(
            ((Invoke-Adb shell pidof $packageName) -join ' ') -split '\s+' |
                Where-Object { $_ -match '^\d+$' } |
                Select-Object -First 1
        ) -join ''
        $persistentStartVerification = Read-CurrentProcessStartupTelemetry `
            -RemotePath $appOwnedLogPath `
            -ProcessId $currentAppPid
        if ($persistentStartVerification) {
            $startVerification = $startVerification + "`n" + $persistentStartVerification
        }
        $serviceVerification =
            (Invoke-Adb shell dumpsys activity services $packageName) -join "`n"
        if ($startVerification -match 'DEBUG_RECORDING_CONFIG_REJECTED') {
            $startVerification |
                Set-Content -LiteralPath (Join-Path $runDirectory 'start-verification-logcat.txt')
            throw "Exact recording configuration was rejected before start; evidence=$runDirectory"
        }
        if ($startVerification -match 'DIRECT_RECORDING_START_COMMIT stable=false|NPV10_STARTUP_COMMIT stable=false|NPV10_USER_CONTROL_LATE_BOUNDARY') {
            $startVerification |
                Set-Content -LiteralPath (Join-Path $runDirectory 'start-verification-logcat.txt')
            throw "NPV10 producer startup failed closed before durable commit; evidence=$runDirectory"
        }
        $recordingServiceActive =
            $serviceVerification -match [regex]::Escape(
                "$packageName/.recording.RecordingForegroundService"
            )
        $durablePipelineStartObserved = $startVerification -match $durableStartPattern
        if ($durablePipelineStartObserved -and $recordingServiceActive) {
            $recordingWasStarted = $true
            break
        }
    }
    $startVerification |
        Set-Content -LiteralPath (Join-Path $runDirectory 'start-verification-logcat.txt')
    $serviceVerification |
        Set-Content -LiteralPath (Join-Path $runDirectory 'start-verification-service.txt')
    $appOwnedLogPath |
        Set-Content -LiteralPath (Join-Path $runDirectory 'app-persistent-log-source.txt')
    $persistentStartVerification |
        Set-Content -LiteralPath (Join-Path $runDirectory 'app-persistent-start-verification.txt')

    if (-not $recordingWasStarted) {
        throw "Durable recording start was not proven by '$durableStartPattern' with an active service; evidence=$runDirectory"
    }

    $passivePersistentLogOutput = Join-Path $runDirectory 'passive-app-recording-log.txt'
    $passivePersistentLogError = Join-Path $runDirectory 'passive-app-recording-log-stderr.txt'
    $persistentLogProcess = Start-QAPassiveRecordingLog -AdbPath $AdbPath -Serial $Serial `
        -RemotePath $appOwnedLogPath -OutputPath $passivePersistentLogOutput `
        -ErrorPath $passivePersistentLogError -MaximumSeconds ($DurationSeconds + 600)

    Write-Output "RECORDING_CONFIRMED checkpoint=$checkpoint evidence=$runDirectory label=$label"
    '"elapsedSeconds","thermalStatus","skinC","batteryDeciC","pid","dataAvailKib"' |
        Set-Content -LiteralPath (Join-Path $runDirectory 'thermal-series.csv')
    @(
        "captureSafeMonitoring=$CaptureSafeMonitoring"
        $(if ($CaptureSafeMonitoring) { 'healthPollSeconds=disabled-during-capture' } else { 'healthPollSeconds=10' })
        $(if ($CaptureSafeMonitoring) { 'lightweightThermalSampleSeconds=disabled-during-capture' } else { 'lightweightThermalSampleSeconds=60' })
        'thermalBoundaryMethod=dumpsys thermalservice before and after recording'
        'stopEvidenceMethod=passive logcat plus PID/label-bound app-file stream; no repeated adb polling'
        $(if ($CaptureSafeMonitoring) { 'thermalSeriesMethod=boundary snapshots only; no device polling during capture' } else { 'thermalSeriesMethod=sysfs thermal_zone68 skin and thermal_zone79 battery' })
        $(if ($CaptureSafeMonitoring) { 'thermalStatusColumn=pre-capture boundary status; final status is captured after finalization' } else { 'thermalStatusColumn=last full boundary status; temperature columns are live sysfs samples' })
        $(if ($CaptureSafeMonitoring) { 'serviceLivenessMethod=foreground service and finalized output verified at capture boundaries' } else { 'serviceLivenessMethod=process liveness during capture; foreground service and output duration verified at boundaries' })
        $(if ($CaptureSafeMonitoring) { 'recordingWindowProhibitedDiagnostics=all adb shell process launches' } else { 'recordingWindowProhibitedDiagnostics=dumpsys thermalservice,dumpsys battery,dumpsys activity services' })
        'reason=prevent QA Binder diagnostics from perturbing camera HAL source cadence'
    ) | Set-Content -LiteralPath (Join-Path $runDirectory 'monitoring-policy.txt')

    $timer = [Diagnostics.Stopwatch]::StartNew()
    $nextProgress = 0
    $nextTelemetrySample = 60
    $lastThermal = $thermalBefore
    $lastBatteryTemperature = 'NA'
    $lastAvailableKib = if ($CaptureSafeMonitoring) { 'BOUNDARY_ONLY' } else { Read-DataAvailableKib }
    $recordingPid = $currentAppPid
    $lifecycleBackgroundRequested = $false
    $lifecycleForegroundRequested = $false
    $lifecycleBackgroundElapsed = -1.0
    $lifecycleForegroundElapsed = -1.0
    while ($timer.Elapsed.TotalSeconds -lt $DurationSeconds) {
        $monitorSleepMs = Get-QARecordingMonitorSleepMilliseconds `
            -TargetSeconds $DurationSeconds -ElapsedSeconds $timer.Elapsed.TotalSeconds
        if ($monitorSleepMs -le 0) { break }
        Start-Sleep -Milliseconds $monitorSleepMs
        $elapsed = [math]::Round($timer.Elapsed.TotalSeconds, 3)
        if ($BackgroundAtSeconds -gt 0 -and !$lifecycleBackgroundRequested -and
            $elapsed -ge $BackgroundAtSeconds) {
            Invoke-Adb shell input keyevent KEYCODE_HOME |
                Set-Content -LiteralPath (Join-Path $runDirectory 'lifecycle-background-command.txt')
            $lifecycleBackgroundRequested = $true
            $lifecycleBackgroundElapsed = $timer.Elapsed.TotalSeconds
            [pscustomobject]@{ event='HOME'; elapsed_seconds=$lifecycleBackgroundElapsed } |
                ConvertTo-Json -Compress | Add-Content -LiteralPath (Join-Path $runDirectory 'lifecycle-events.jsonl')
            Write-Output "LIFECYCLE_HOME elapsed=${lifecycleBackgroundElapsed}s recordingIsNotStoppedByController=true"
        }
        if ($lifecycleBackgroundRequested -and !$lifecycleForegroundRequested -and
            $timer.Elapsed.TotalSeconds -ge ($lifecycleBackgroundElapsed + $BackgroundDurationSeconds)) {
            # No -S, configuration extras, or start-recording command: resume the same process/clip.
            Invoke-Adb -Arguments @(
                'shell', 'am', 'start', '-W', '-a', 'android.intent.action.MAIN',
                '-n', "$packageName/.ui.MainActivity"
            ) |
                Set-Content -LiteralPath (Join-Path $runDirectory 'lifecycle-foreground-command.txt')
            $lifecycleForegroundRequested = $true
            $lifecycleForegroundElapsed = $timer.Elapsed.TotalSeconds
            [pscustomobject]@{ event='RESUME_SAME_ACTIVITY'; elapsed_seconds=$lifecycleForegroundElapsed } |
                ConvertTo-Json -Compress | Add-Content -LiteralPath (Join-Path $runDirectory 'lifecycle-events.jsonl')
            Write-Output "LIFECYCLE_RESUME elapsed=${lifecycleForegroundElapsed}s"
        }
        if ($persistentLogProcess.HasExited) {
            throw "Passive app-log stream ended before the planned boundary; no continued-recording claim is valid; elapsed=${elapsed}s"
        }
        $persistentStopMarker = Find-QARecordingStopMarker `
            -Lines @(Get-Content -LiteralPath $passivePersistentLogOutput -Tail 4096) `
            -ProcessId $recordingPid -ValidationLabel $label
        if ($persistentStopMarker) {
            $earlyStopMarker = $persistentStopMarker
            $earlyStopMarker | Set-Content -LiteralPath (Join-Path $runDirectory 'early-stop-origin.txt')
            Write-Output "EARLY_STOP_DETECTED elapsed=${elapsed}s source=passive-app-file marker=$earlyStopMarker"
            break
        }
        # The already-running logcat stream is a passive observer. Reading its host-side file does
        # not launch an adb process or perturb Camera2. A first-authority marker before the planned
        # boundary proves that the app ended the clip early and preserves the exact root cause.
        if (Test-Path -LiteralPath $focusedLogOutput) {
            $stopLines = @(Select-String -LiteralPath $focusedLogOutput `
                -Pattern 'RECORDING_STOP_ORIGIN committed=true origin=' `
                -ErrorAction SilentlyContinue)
            $matchedStop = Find-QARecordingStopMarker -Lines @($stopLines | ForEach-Object Line) `
                -ProcessId $recordingPid -ValidationLabel $label
            if ($matchedStop) {
                $earlyStopMarker = $matchedStop
                $earlyStopMarker |
                    Set-Content -LiteralPath (Join-Path $runDirectory 'early-stop-origin.txt')
                Write-Output "EARLY_STOP_DETECTED elapsed=${elapsed}s marker=$earlyStopMarker"
                break
            }
        }
        if (Test-Path -LiteralPath $eventLogOutput) {
            $killLines = @(Select-String -LiteralPath $eventLogOutput `
                -Pattern "am_kill.*$([regex]::Escape($packageName))" `
                -ErrorAction SilentlyContinue)
            if ($killLines.Count -gt 0) {
                $externalStopMarker = [string]$killLines[-1].Line
                $externalStopMarker |
                    Set-Content -LiteralPath (Join-Path $runDirectory 'external-process-stop.txt')
                throw "Application process was externally terminated at $elapsed seconds: $externalStopMarker"
            }
        }
        $appPid = $recordingPid
        if (-not $CaptureSafeMonitoring) {
            $appPid = ((Invoke-Adb shell pidof $packageName) -join ' ').Trim()
            if ([string]::IsNullOrWhiteSpace($appPid)) {
                throw "Application process died at $elapsed seconds"
            }
        }
        $collectTelemetry = $elapsed -ge $nextTelemetrySample -or
            $elapsed -ge $DurationSeconds
        if ($collectTelemetry) {
            if (-not $CaptureSafeMonitoring) {
                $lastThermal = Read-LightweightThermalSample -BoundaryStatus $thermalBefore.Status
                $lastBatteryTemperature = $lastThermal.BatteryDeciC
                $lastAvailableKib = Read-DataAvailableKib
            }
            ('"{0}","{1}","{2}","{3}","{4}","{5}"' -f `
                $elapsed, $lastThermal.Status, $lastThermal.SkinC,
                $lastBatteryTemperature, $appPid, $lastAvailableKib) |
                Add-Content -LiteralPath (Join-Path $runDirectory 'thermal-series.csv')
            while ($nextTelemetrySample -le $elapsed) {
                $nextTelemetrySample += 60
            }
        }
        if ($elapsed -ge $nextProgress) {
            $thermalSampleScope = if ($CaptureSafeMonitoring) { 'PRE_CAPTURE_BOUNDARY_NOT_LIVE' } else { 'LIVE_SYSFS_STATUS_FROM_BOUNDARY' }
            Write-Output ("PROGRESS elapsed={0}s thermal={1} skin={2}C battery={3}dC pid={4} availKib={5} thermalScope={6}" -f `
                $elapsed, $lastThermal.Status, $lastThermal.SkinC,
                $lastBatteryTemperature, $appPid, $lastAvailableKib, $thermalSampleScope)
            $nextProgress += 30
        }
    }
    $timer.Stop()

    if ([string]::IsNullOrWhiteSpace($earlyStopMarker)) {
        Invoke-Adb -Arguments @(
            'shell', 'am', 'broadcast',
            '-a', "$packageName.DEBUG_COMMAND",
            '-n', "$packageName/.ui.DebugCommandReceiver",
            '--es', 'debug_action', 'stop_recording'
        ) |
            Set-Content -LiteralPath (Join-Path $runDirectory 'stop-command.txt')
        $stopWasRequested = $true
        Write-Output "STOP_REQUESTED elapsed=$([math]::Round($timer.Elapsed.TotalSeconds, 3))s"
    } else {
        "hostStopSuppressed=true`nearlyStopMarker=$earlyStopMarker" |
            Set-Content -LiteralPath (Join-Path $runDirectory 'stop-command.txt')
    }

    Start-Sleep -Seconds 15
    $finalized = $false
    $mediaStoreFinalized = $false
    $privateMasterVerified = $false
    $privateMasterPath = ''
    $privateMarkerText = ''
    $privateEvidenceText = ''
    $privateCertificatePath = ''
    $privateCertificateText = ''
    $terminalPrivateEvidenceCaptured = $false
    $mediaAfter = ''
    $finalRow = ''
    $npv10After = ''
    for ($attempt = 0; $attempt -lt 150; $attempt++) {
        if ($Pipeline -eq 'DIRECT') {
            # Failed publication intentionally has no MediaStore row. Detect that terminal state
            # from this run's process-bound log instead of waiting five minutes for a row that
            # cannot appear. This is a failure exit, never an alternate PASS path.
            $terminalDirectFailure = Get-Content -LiteralPath $focusedLogOutput -Tail 500 |
                Where-Object {
                    $_ -match "\s$([regex]::Escape([string]$recordingPid))\s+\d+\s+E\s+RecordingManager:.*RECORDING_STOP_THREW"
                } | Select-Object -Last 1
            if ($terminalDirectFailure) {
                $terminalDirectFailure | Set-Content -LiteralPath (Join-Path $runDirectory 'terminal-recording-failure.txt')
                $privateCertificatePath = [string](Find-LabeledQualityCertificate)
                $privateCertificateText = Read-AppPrivateText $privateCertificatePath
                break
            }
            $mediaAfter = Read-MediaStore
            $finalRow = Find-LabeledMediaRow $mediaAfter
            if ($finalRow -and $finalRow -match 'is_pending=0') {
                $mediaStoreFinalized = $true
                $privateCertificatePath = [string](Find-LabeledQualityCertificate)
                $privateCertificateText = Read-AppPrivateText $privateCertificatePath
                $directCertificateComplete =
                    $privateCertificateText -match '(?m)^encoderCadenceStatus=PROVEN\s*$' -and
                    $privateCertificateText -match '(?m)^recordingCommittedAvDurationDriftUs=[0-9]+\s*$'
                if ($directCertificateComplete) {
                    $finalized = $true
                    break
                }
            }
        } else {
            $npv10After = Read-Npv10Directory
            $privateMasterPath = Find-LabeledNpv10Master $npv10After
            if ($privateMasterPath) {
                $privateMarkerText = Read-Npv10Marker $privateMasterPath
                $privateMasterVerified = Test-Npv10Marker $privateMarkerText
                if ($privateMasterVerified) {
                    # A successful marker already binds the durable evidence and quality report,
                    # but copy both into the host run directory as independently inspectable proof.
                    # Previously these fields were collected only on failure, leaving successful
                    # Cinema runs with an unnecessarily incomplete evidence bundle.
                    $privateEvidenceText = Read-AppPrivateText "$privateMasterPath.evidence.txt"
                    $privateCertificatePath = [string](Find-LabeledQualityCertificate)
                    $privateCertificateText = Read-AppPrivateText $privateCertificatePath
                    $finalized = $true
                    break
                }
                # The structural sidecar is written before the quality certificate and verified
                # marker. Do not mistake that intermediate file for terminal failure: a multi-gigabyte
                # master can still be undergoing its full CRC/SHA audit while preview has resumed.
                # Only stop polling once finalization itself is complete and a persisted certificate
                # proves that the marker was intentionally withheld.
                $privateEvidenceText = Read-AppPrivateText "$privateMasterPath.evidence.txt"
                $privateCertificatePath = [string](Find-LabeledQualityCertificate)
                $privateCertificateText = Read-AppPrivateText $privateCertificatePath
                $qualityCertificateComplete =
                    $privateCertificateText -match '(?m)^qualityEvidenceStatus=(PASS|FAIL|NOT_PROVEN)\s*$'
                $finalizationServiceActive = Test-RecordingForegroundServiceActive
                if (
                    $privateEvidenceText -match '(?m)^auditStatus=(PASS|FAIL)\s*$' -and
                    $qualityCertificateComplete -and
                    -not $finalizationServiceActive
                ) {
                    $terminalPrivateEvidenceCaptured = $true
                    break
                }
            }
        }
        Start-Sleep -Seconds 2
    }
    # Refresh the private listing once after polling so artifacts committed between the previous
    # observation and foreground-service retirement are never omitted from the sealed run bundle.
    if ($Pipeline -eq 'CINEMA') {
        $npv10After = Read-Npv10Directory
        $privateMasterPath = Find-LabeledNpv10Master $npv10After
        if ($privateMasterPath) {
            $privateMarkerText = Read-Npv10Marker $privateMasterPath
            $privateMasterVerified = Test-Npv10Marker $privateMarkerText
            $privateEvidenceText = Read-AppPrivateText "$privateMasterPath.evidence.txt"
            $privateCertificatePath = [string](Find-LabeledQualityCertificate)
            $privateCertificateText = Read-AppPrivateText $privateCertificatePath
            if ($privateMasterVerified) { $finalized = $true }
        }
    }
    $mediaAfter | Set-Content -LiteralPath (Join-Path $runDirectory 'mediastore-after.txt')
    [string]$finalRow |
        Set-Content -LiteralPath (Join-Path $runDirectory 'mediastore-final-row.txt')
    $npv10After | Set-Content -LiteralPath (Join-Path $runDirectory 'npv10-after.txt')
    $privateMasterPath | Set-Content -LiteralPath (Join-Path $runDirectory 'npv10-master-path.txt')
    $privateMarkerText | Set-Content -LiteralPath (Join-Path $runDirectory 'npv10-verified-marker.txt')
    $privateEvidenceText | Set-Content -LiteralPath (Join-Path $runDirectory 'npv10-evidence.txt')
    $privateCertificatePath |
        Set-Content -LiteralPath (Join-Path $runDirectory 'quality-certificate-path.txt')
    $privateCertificateText |
        Set-Content -LiteralPath (Join-Path $runDirectory 'quality-certificate.txt')

    $runtimeLog = (Invoke-Adb -Arguments @(
        'logcat', '-d', '-b', 'main', '-b', 'system', '-b', 'crash', '-v', 'threadtime'
    )) -join "`n"
    $runtimeLog | Set-Content -LiteralPath (Join-Path $runDirectory 'runtime-logcat-snapshot.txt')
    $certificateFields = ConvertFrom-KeyValueEvidence $privateCertificateText
    $npv10EvidenceFields = ConvertFrom-KeyValueEvidence $privateEvidenceText
    $qualityEvidenceStatus = [string]$certificateFields['qualityEvidenceStatus']
    $qualityFailureCodes = [string]$certificateFields['failureCodes']
    $recordingStopOrigin = [string]$certificateFields['recordingStopOrigin']
    $recordingStopDetail = [string]$certificateFields['recordingStopDetail']
    $plannedStopOriginValid =
        [string]::IsNullOrWhiteSpace($earlyStopMarker) -and
        $recordingStopOrigin -eq 'DEBUG_COMMAND'
    # A completed file is not a passing endurance run when the app's own persisted certificate
    # contains a blocking quality failure (for example an encoder that accepted 160 Mbps but
    # emitted only 10 Mbps). Keep NOT_PROVEN distinct for host-side certification, but never allow
    # an explicit FAIL to be presented as RUN_COMPLETE.
    $qualityEvidenceNotFailed =
        $qualityEvidenceStatus -in @('PASS', 'NOT_PROVEN')
    $detailProfileEvidenceValid =
        $certificateFields.ContainsKey('selectedDetailProfile') -and
        $certificateFields['selectedDetailProfile'] -eq $DetailProfile
    $niv99RequiredZeroFields = @(
        'niv99AnalysisEstimatesPublished',
        'niv99AnalysisEstimatesRejected',
        'niv99AnalysisFramesSeen',
        'niv99CaptureResultsPublished',
        'niv99CaptureResultsRejected',
        'niv99CaptureResultsSeen',
        'niv99ColorEstimateTunings',
        'niv99DenoiseEffectFrames',
        'niv99ImageMatches',
        'niv99ImageMisses',
        'niv99NonIdentityWhiteBalanceFrames',
        'niv99PixelAppliedFrames',
        'niv99PixelRenderRejectedFrames',
        'niv99PreviewFallbackAttempts',
        'niv99PreviewFallbackMaximumAgeNs',
        'niv99PreviewFallbackPresentations',
        'niv99PreviewFallbackRejections',
        'niv99PreviewFallbackSelections',
        'niv99PreviewPixelAppliedFrames',
        'niv99PreviewPixelRenderRejectedFrames',
        'niv99ReconstructionEffectFrames',
        'niv99RecordingPixelAppliedFrames',
        'niv99RecordingPixelRenderRejectedFrames',
        'niv99TimelineCaptureResultsPublished',
        'niv99TimelineCaptureResultsRejected',
        'niv99TimelineCaptureResultsSeen',
        'niv99TimelineImageMatches',
        'niv99TimelineImageMisses',
        'niv99TimelinePixelAppliedFrames',
        'niv99TimelinePixelEligibleTunings',
        'niv99TimelinePixelRenderRejectedFrames',
        'niv99TimelinePreviewPixelAppliedFrames',
        'niv99TimelinePreviewPixelRenderRejectedFrames',
        'niv99TimelineRecordingPixelAppliedFrames',
        'niv99TimelineRecordingPixelRenderRejectedFrames',
        'niv99TimelineResetCount',
        'niv99TimelineTuningAccepted',
        'niv99TimelineTuningRejected',
        'niv99TimestampAlignedImageMatches',
        'niv99TimestampAlignmentMaxNs',
        'niv99TuningAccepted',
        'niv99TuningRejected',
        'niv99TuningWaitMaxNs',
        'niv99TuningWaitTimeouts',
        'niv99TuningWaitTotalNs',
        'niv99TuningWaits',
        'niv99TuningWorkCompleted',
        'niv99TuningWorkFailed',
        'niv99TuningWorkMaximumNs',
        'niv99TuningWorkMaximumQueueDepth',
        'niv99TuningWorkQueued',
        'niv99TuningWorkRejected',
        'niv99TuningWorkRetired',
        'niv99TuningWorkStale',
        'niv99TuningWorkTotalNs'
    )
    $niv99MissingOrNonZeroFields = @($niv99RequiredZeroFields | Where-Object {
        -not $certificateFields.ContainsKey($_) -or
            -not [long]::TryParse(
                $certificateFields[$_],
                [Globalization.NumberStyles]::Integer,
                [Globalization.CultureInfo]::InvariantCulture,
                [ref]([long]$parsedNiv99Counter = 0L)
            ) -or
            $parsedNiv99Counter -ne 0L
    })
    $niv99OfflineIsolationEvidenceValid =
        $certificateFields['niv99ExecutionBoundary'] -eq 'NEO_NIV99_OFFLINE_ISOLATION_V1' -and
        $certificateFields['niv99LiveCaptureMetadataEnabled'] -eq 'false' -and
        $certificateFields['niv99LiveAnalysisEnabled'] -eq 'false' -and
        $certificateFields['niv99LivePreviewPixelProcessingEnabled'] -eq 'false' -and
        $certificateFields['niv99LiveRecordingPixelProcessingEnabled'] -eq 'false' -and
        $certificateFields['niv99OfflineConversionEnabled'] -eq 'true' -and
        $niv99MissingOrNonZeroFields.Count -eq 0
    $rollingTriggerReached = $false
    $rollingExtensionCount = 0L
    $rollingExtendedBytes = 0L
    $rollingEvidenceValid = if ($Pipeline -eq 'DIRECT') {
        $durableMovReservationEvidence =
            $certificateFields.ContainsKey('movPhysicalReservationEnabled')
        if ($durableMovReservationEvidence) {
            $reservationEnabled =
                $certificateFields['movPhysicalReservationEnabled'] -eq 'true'
            $reservationMethod = $certificateFields['movPhysicalReservationMethod']
            $initialReservedBytes =
                [long]$certificateFields['movPhysicalReservationInitialReservedBytes']
            $currentReservedBytes =
                [long]$certificateFields['movPhysicalReservationCurrentReservedBytes']
            $rollingLeadBytes =
                [long]$certificateFields['movPhysicalReservationRollingLeadBytes']
            $rollingExtensionBytes =
                [long]$certificateFields['movPhysicalReservationRollingExtensionBytes']
            $rollingExtensionCount =
                [long]$certificateFields['movPhysicalReservationExtensionSuccesses']
            $rollingExtendedBytes =
                [long]$certificateFields['movPhysicalReservationTotalExtendedBytes']
            $committedBytes =
                [long]$certificateFields['movPhysicalReservationCommittedBytes']
            $reservationTrimmed =
                $certificateFields['movPhysicalReservationTrimmed'] -eq 'true'
            $reservationSynced =
                $certificateFields['movPhysicalReservationSynced'] -eq 'true'
            $reservationFailure = $certificateFields['movPhysicalReservationFailure']
            $rollingTriggerReached = $rollingExtensionCount -ge 1L
            $baseEvidenceValid =
                $reservationEnabled -and
                $reservationMethod -eq 'POSIX_FALLOCATE' -and
                $initialReservedBytes -ge 10737418240L -and
                $currentReservedBytes -ge $initialReservedBytes -and
                $committedBytes -gt 0L -and
                $reservationTrimmed -and
                $reservationSynced -and
                [string]::IsNullOrWhiteSpace($reservationFailure)
            if ($rollingTriggerReached) {
                $baseEvidenceValid -and
                    $rollingExtensionBytes -ge 10737418240L -and
                    $rollingExtendedBytes -ge ($rollingExtensionCount * 10737418240L)
            } else {
                # A short clip must not allocate the second extent before entering the one-GiB lead window.
                $baseEvidenceValid -and
                    $rollingLeadBytes -ge 1073741824L -and
                    $committedBytes -lt ($currentReservedBytes - $rollingLeadBytes)
            }
        } else {
            # Compatibility fallback for candidates built before certificate-backed MOV evidence.
            $extensionMatches = [regex]::Matches(
                $runtimeLog,
                'rolling preallocation extension=([1-9][0-9]*).*bytes=([0-9]+)'
            )
            if ($extensionMatches.Count -gt 0) {
                $rollingExtensionCount = [long]$extensionMatches.Count
                $rollingTriggerReached = $true
                $rollingExtendedBytes = [long](
                    $extensionMatches |
                        ForEach-Object { [long]$_.Groups[2].Value } |
                        Measure-Object -Sum
                ).Sum
                @($extensionMatches | Where-Object {
                    [long]$_.Groups[2].Value -ne 10737418240L
                }).Count -eq 0
            } else {
                $finalizationMatch = [regex]::Matches(
                    $runtimeLog,
                    'finalized committed=([0-9]+) reserved=([0-9]+) rollingExtensions=0'
                ) | Select-Object -Last 1
                $initialReservationMatch = [regex]::Matches(
                    $runtimeLog,
                    'preallocation method=POSIX_FALLOCATE[^\r\n]*reserved=([0-9]+)'
                ) | Select-Object -Last 1
                $committed = if ($finalizationMatch) {
                    [long]$finalizationMatch.Groups[1].Value
                } elseif ($certificateFields.ContainsKey('recordedFileBytes')) {
                    [long]$certificateFields['recordedFileBytes']
                } else {
                    -1L
                }
                $reserved = if ($finalizationMatch) {
                    [long]$finalizationMatch.Groups[2].Value
                } elseif ($initialReservationMatch) {
                    [long]$initialReservationMatch.Groups[1].Value
                } else {
                    -1L
                }
                $committed -ge 0L -and $reserved -ge 10737418240L -and
                    $committed -lt ($reserved - 1073741824L)
            }
        }
    } else {
        $reservation = $null
        $reservationEnabled = $false
        $initialReservedBytes = -1L
        $currentReservedBytes = -1L
        $rollingLeadBytes = -1L
        $rollingExtensionBytes = -1L
        $extensionFailures = -1L
        $unreservedWriteRefusals = -1L
        $durableReservationEvidence =
            $certificateFields.ContainsKey('npv10PhysicalReservationEnabled')
        if ($durableReservationEvidence) {
            $reservationEnabled =
                $certificateFields['npv10PhysicalReservationEnabled'] -eq 'true'
            $initialReservedBytes =
                [long]$certificateFields['npv10PhysicalReservationInitialReservedBytes']
            $currentReservedBytes =
                [long]$certificateFields['npv10PhysicalReservationCurrentReservedBytes']
            $rollingLeadBytes =
                [long]$certificateFields['npv10PhysicalReservationRollingLeadBytes']
            $rollingExtensionBytes =
                [long]$certificateFields['npv10PhysicalReservationRollingExtensionBytes']
            $rollingExtensionCount =
                [long]$certificateFields['npv10PhysicalReservationExtensionSuccesses']
            $rollingExtendedBytes =
                [long]$certificateFields['npv10PhysicalReservationTotalExtendedBytes']
            $extensionFailures =
                [long]$certificateFields['npv10PhysicalReservationExtensionFailures']
            $unreservedWriteRefusals =
                [long]$certificateFields['npv10PhysicalReservationUnreservedWriteRefusals']
        } else {
            # Compatibility fallback for candidates built before durable reservation evidence.
            $reservationMatches = [regex]::Matches(
                $runtimeLog,
                'Npv10PhysicalReservationTelemetry\([^\r\n]*\)'
            )
            $reservation = $reservationMatches | Select-Object -Last 1
            if ($reservation) {
                $reservationText = $reservation.Value
                $readReservationLong = {
                    param([string]$Name, [long]$Default = -1L)
                    $match = [regex]::Match($reservationText, "(?:^|[, (])$Name=([0-9]+)")
                    if ($match.Success) { return [long]$match.Groups[1].Value }
                    return $Default
                }
                $reservationEnabled = $reservationText -match '(?:^|[, (])enabled=true'
                # In command-argument mode Windows PowerShell treats an unparenthesized -1L/0L
                # as a string. Keep the fallback typed so incomplete finalization produces the
                # actual recording FAIL evidence, not a secondary parser exception.
                $initialReservedBytes = & $readReservationLong 'initialReservedBytes' (-1L)
                $currentReservedBytes = & $readReservationLong 'currentReservedBytes' (-1L)
                $rollingLeadBytes = & $readReservationLong 'rollingLeadBytes' (-1L)
                $rollingExtensionBytes = & $readReservationLong 'rollingExtensionBytes' (-1L)
                $rollingExtensionCount = & $readReservationLong 'extensionSuccesses' (0L)
                $rollingExtendedBytes = & $readReservationLong 'totalExtendedBytes' (0L)
                $extensionFailures = & $readReservationLong 'extensionFailures' (-1L)
                $unreservedWriteRefusals = & $readReservationLong 'unreservedWriteRefusals' (-1L)
            }
        }
        if ($durableReservationEvidence -or $reservation) {
            $markerFields = ConvertFrom-KeyValueEvidence $privateMarkerText
            $masterBytes = if ($markerFields.ContainsKey('masterBytes')) {
                [long]$markerFields['masterBytes']
            } else {
                -1L
            }
            if ($rollingExtensionCount -ge 1L) {
                $rollingTriggerReached = $true
                $reservationEnabled -and
                    $initialReservedBytes -ge 10737418240L -and
                    $rollingExtensionBytes -ge 10737418240L -and
                    $rollingExtendedBytes -ge ($rollingExtensionCount * 10737418240L) -and
                    $extensionFailures -eq 0L -and $unreservedWriteRefusals -eq 0L
            } else {
                # Short/lower-resolution clips correctly retain one 10 GiB extent until they enter
                # the one-GiB trigger window; no test should demand wasteful unused allocation.
                $reservationEnabled -and
                    $masterBytes -ge 0L -and $initialReservedBytes -ge 10737418240L -and
                    $currentReservedBytes -ge 10737418240L -and
                    $rollingLeadBytes -ge 1073741824L -and
                    $masterBytes -lt ($currentReservedBytes - $rollingLeadBytes) -and
                    $extensionFailures -eq 0L -and $unreservedWriteRefusals -eq 0L
            }
        } else {
            $false
        }
    }
    $formatEvidenceValid = if ($Pipeline -eq 'DIRECT') {
        ($certificateFields['selectedResolution'] -eq $Resolution -and
            $certificateFields['fps'] -eq $Fps.ToString()) -or
            $runtimeLog -match [regex]::Escape("encoder=$Resolution@$Fps")
    } else {
        $privateMasterPath -match ([regex]::Escape("${Resolution}_${Fps}fps.npv10") + '$')
    }
    $encodedCadenceEvidenceValid = if ($Pipeline -eq 'DIRECT') {
        $observedFps = if ($certificateFields.ContainsKey('encoderCadenceObservedFps')) {
            [double]::Parse(
                $certificateFields['encoderCadenceObservedFps'],
                [Globalization.CultureInfo]::InvariantCulture
            )
        } else {
            -1.0
        }
        $certificateFields['encoderCadenceStatus'] -eq 'PROVEN' -and
            $certificateFields['deviceRuntimeFrameCadenceProven'] -eq 'true' -and
            $certificateFields['encoderCadenceTargetFps'] -eq $Fps.ToString() -and
            [math]::Abs($observedFps - [double]$Fps) -le 0.01 -and
            $certificateFields['encoderCadenceWithinTolerance'] -eq 'true' -and
            $certificateFields['encoderCadenceNonMonotonicPts'] -eq '0' -and
            $certificateFields['encoderCadenceOutOfCadenceExtrema'] -eq '0' -and
            $certificateFields['encoderPresentationDuplicatePtsFrames'] -eq '0' -and
            $certificateFields['encoderTransportDroppedMuxerSamples'] -eq '0' -and
            $certificateFields['deviceRuntimeHotPathDroppedFrames'] -eq '0' -and
            $certificateFields['droppedFrames'] -eq '0'
    } else {
        $privateMasterVerified
    }
    $tenBitEvidenceValid = if ($Pipeline -eq 'DIRECT') {
        $certificateFields['selectedResolution'] -eq $Resolution -and
            $certificateFields['encodedBitDepth'] -eq '10' -and
            $certificateFields['encoderTransportZeroDropProven'] -eq 'true'
    } else {
        $npv10EvidenceFields['selectedResolution'] -eq $Resolution -and
            $npv10EvidenceFields['sourceBitDepthCheckPass'] -eq 'true' -and
            $npv10EvidenceFields['storageBitDepthCheckPass'] -eq 'true'
    }
    $audioEvidenceValid = if ($Pipeline -eq 'DIRECT') {
        $audioDurationUs = if ($certificateFields.ContainsKey('recordingCommittedAvAudioDurationUs')) {
            [long]$certificateFields['recordingCommittedAvAudioDurationUs']
        } else { -1L }
        $videoDurationUs = if ($certificateFields.ContainsKey('recordingCommittedAvVideoDurationUs')) {
            [long]$certificateFields['recordingCommittedAvVideoDurationUs']
        } else { -2L }
        $maximumDriftUs = if ($certificateFields.ContainsKey('recordingCommittedAvMaximumDurationDriftUs')) {
            [long]$certificateFields['recordingCommittedAvMaximumDurationDriftUs']
        } else { -1L }
        $audioDurationUs -gt 0L -and $videoDurationUs -gt 0L -and $maximumDriftUs -ge 0L -and
            [math]::Abs($audioDurationUs - $videoDurationUs) -le $maximumDriftUs
    } else {
        -not [string]::IsNullOrWhiteSpace($privateMasterPath) -and
            $npv10After -match [regex]::Escape("$privateMasterPath.audio.npv10a")
    }
    $minimumRequiredDurationMs = if ($DurationSeconds -ge 605) {
        605000L
    } else {
        [math]::Max(0L, ([long]$DurationSeconds * 1000L) - 2000L)
    }
    $measuredOutputDurationMs = if ($Pipeline -eq 'DIRECT') {
        Read-MediaDurationMs $finalRow
    } else {
        $firstTimestampMatch = [regex]::Match($privateMarkerText, '(?m)^firstTimestampNs=([0-9]+)\s*$')
        $lastTimestampMatch = [regex]::Match($privateMarkerText, '(?m)^lastTimestampNs=([0-9]+)\s*$')
        if ($firstTimestampMatch.Success -and $lastTimestampMatch.Success) {
            [long][math]::Floor(
                ([long]$lastTimestampMatch.Groups[1].Value -
                    [long]$firstTimestampMatch.Groups[1].Value) / 1000000.0
            )
        } else {
            -1L
        }
    }
    $durationEvidenceValid =
        $measuredOutputDurationMs -ge $minimumRequiredDurationMs
    $sourceRollingCaptureFps = if ($certificateFields.ContainsKey('sensorPipelineCaptureFps')) {
        [double]::Parse(
            $certificateFields['sensorPipelineCaptureFps'],
            [Globalization.CultureInfo]::InvariantCulture
        )
    } else { -1.0 }
    # The rolling capture-rate window intentionally decays after recording stops and therefore is
    # not a valid terminal cadence oracle. Use the full committed sensor-timestamp span instead.
    $sourceCaptureFps = if ($certificateFields.ContainsKey('sensorPipelineRecordingSourceFps')) {
        [double]::Parse(
            $certificateFields['sensorPipelineRecordingSourceFps'],
            [Globalization.CultureInfo]::InvariantCulture
        )
    } else { -1.0 }
    $sourceRecordedFrames = if ($certificateFields.ContainsKey('sensorPipelineRecordedFrames')) {
        [long]$certificateFields['sensorPipelineRecordedFrames']
    } else { -1L }
    $sourceDroppedRecordingFrames =
        if ($certificateFields.ContainsKey('sensorPipelineDroppedRecordingFrames')) {
            [long]$certificateFields['sensorPipelineDroppedRecordingFrames']
        } else { -1L }
    $sourceExplicitDroppedRecordingFrames =
        if ($certificateFields.ContainsKey('sensorPipelineExplicitDroppedRecordingFrames')) {
            [long]$certificateFields['sensorPipelineExplicitDroppedRecordingFrames']
        } else { -1L }
    $sourceInferredTimestampGapFrames =
        if ($certificateFields.ContainsKey('sensorPipelineInferredRecordingTimestampGapFrames')) {
            [long]$certificateFields['sensorPipelineInferredRecordingTimestampGapFrames']
        } else { -1L }
    $sourceUnrecoveredTimestampGapFrames =
        if ($certificateFields.ContainsKey('sensorPipelineUnrecoveredRecordingTimestampGapFrames')) {
            [long]$certificateFields['sensorPipelineUnrecoveredRecordingTimestampGapFrames']
        } else { -1L }
    $recordFrameDeliverySteadyStateClean =
        $certificateFields.ContainsKey('recordFrameDeliverySteadyStateClean') -and
        $certificateFields['recordFrameDeliverySteadyStateClean'] -eq 'true'
    $realtimeDispatchDroppedFrames =
        if ($certificateFields.ContainsKey('realtimeDispatchDroppedFrames')) {
            [long]$certificateFields['realtimeDispatchDroppedFrames']
        } else { -1L }
    $realtimeDispatchRejectedFrames =
        if ($certificateFields.ContainsKey('realtimeDispatchRejectedFrames')) {
            [long]$certificateFields['realtimeDispatchRejectedFrames']
        } else { -1L }
    $wallActiveRecordingDurationUs =
        if ($certificateFields.ContainsKey('wallActiveRecordingDurationUs')) {
            [long]$certificateFields['wallActiveRecordingDurationUs']
        } else { -1L }
    $encoderSubmittedFrames =
        if ($certificateFields.ContainsKey('encoderCadenceSubmittedFrames')) {
            [long]$certificateFields['encoderCadenceSubmittedFrames']
        } else { -1L }
    $wallActiveRecordingDurationMs = if ($wallActiveRecordingDurationUs -gt 0L) {
        [long][math]::Floor($wallActiveRecordingDurationUs / 1000.0)
    } else { -1L }
    $maximumWallDurationLossMs = if ($wallActiveRecordingDurationMs -gt 0L) {
        [long][math]::Max(2000L, [math]::Ceiling($wallActiveRecordingDurationMs * 0.01))
    } else { -1L }
    $minimumSourceFrames = if ($wallActiveRecordingDurationUs -gt 0L) {
        [long][math]::Floor(
            (($wallActiveRecordingDurationUs - ($maximumWallDurationLossMs * 1000L)) * $Fps) /
                1000000.0
        )
    } else { -1L }
    # Encoders can synthesize perfectly spaced PTS from the frames they receive. That is not proof
    # that Camera2 supplied the selected cadence. Bind PASS to the independent sensor/recording
    # lineage, the active wall clock, and the committed video sample count so dropped source frames
    # cannot be hidden by a shorter constant-frame-rate movie.
    $sourceCadenceEvidenceValid = if ($Pipeline -eq 'DIRECT') {
        $wallActiveRecordingDurationUs -gt 0L -and
            $measuredOutputDurationMs -gt 0L -and
            [math]::Abs($wallActiveRecordingDurationMs - $measuredOutputDurationMs) -le
                $maximumWallDurationLossMs -and
            $sourceCaptureFps -ge ([double]$Fps * 0.98) -and
            $sourceCaptureFps -le ([double]$Fps * 1.02) -and
            $sourceDroppedRecordingFrames -eq 0L -and
            $sourceExplicitDroppedRecordingFrames -eq 0L -and
            $sourceUnrecoveredTimestampGapFrames -eq 0L -and
            $recordFrameDeliverySteadyStateClean -and
            $realtimeDispatchDroppedFrames -eq 0L -and
            $realtimeDispatchRejectedFrames -eq 0L -and
            $sourceRecordedFrames -ge $minimumSourceFrames -and
            $sourceRecordedFrames -eq $encoderSubmittedFrames
    } else {
        $privateMasterVerified
    }
    $cadenceEvidenceValid = $encodedCadenceEvidenceValid -and $sourceCadenceEvidenceValid
    @(
        "pipeline=$Pipeline"
        "rollingTriggerReached=$rollingTriggerReached"
        "rollingExtensionCount=$rollingExtensionCount"
        "rollingExtendedBytes=$rollingExtendedBytes"
        "rolling10GiBEvidence=$rollingEvidenceValid"
        "resolution=$Resolution"
        "fps=$Fps"
        "aspect=$resolvedAspect"
        "requestedDetailProfile=$DetailProfile"
        "observedDetailProfile=$($certificateFields['selectedDetailProfile'])"
        "detailProfileEvidenceValid=$detailProfileEvidenceValid"
        "qualityEvidenceStatus=$qualityEvidenceStatus"
        "qualityEvidenceNotFailed=$qualityEvidenceNotFailed"
        "qualityFailureCodes=$qualityFailureCodes"
        "recordingStopOrigin=$recordingStopOrigin"
        "recordingStopDetail=$recordingStopDetail"
        "plannedStopOriginValid=$plannedStopOriginValid"
        "earlyStopMarker=$earlyStopMarker"
        "formatSelectionEvidence=$formatEvidenceValid"
        "cadenceEvidenceValid=$cadenceEvidenceValid"
        "encodedCadenceEvidenceValid=$encodedCadenceEvidenceValid"
        "sourceCadenceEvidenceValid=$sourceCadenceEvidenceValid"
        "sourceCaptureFps=$sourceCaptureFps"
        "sourceRollingCaptureFps=$sourceRollingCaptureFps"
        "sourceRecordedFrames=$sourceRecordedFrames"
        "sourceDroppedRecordingFrames=$sourceDroppedRecordingFrames"
        "sourceExplicitDroppedRecordingFrames=$sourceExplicitDroppedRecordingFrames"
        "sourceInferredTimestampGapFrames=$sourceInferredTimestampGapFrames"
        "sourceUnrecoveredTimestampGapFrames=$sourceUnrecoveredTimestampGapFrames"
        "recordFrameDeliverySteadyStateClean=$recordFrameDeliverySteadyStateClean"
        "realtimeDispatchDroppedFrames=$realtimeDispatchDroppedFrames"
        "realtimeDispatchRejectedFrames=$realtimeDispatchRejectedFrames"
        "encoderSubmittedFrames=$encoderSubmittedFrames"
        "wallActiveRecordingDurationMs=$wallActiveRecordingDurationMs"
        "maximumWallDurationLossMs=$maximumWallDurationLossMs"
        "minimumSourceFrames=$minimumSourceFrames"
        "tenBitEvidenceValid=$tenBitEvidenceValid"
        "audioEvidenceValid=$audioEvidenceValid"
        "minimumRequiredDurationMs=$minimumRequiredDurationMs"
        "measuredOutputDurationMs=$measuredOutputDurationMs"
        "durationEvidenceValid=$durationEvidenceValid"
        "captureSafeMonitoring=$CaptureSafeMonitoring"
        "minimumThermalStatus=$MinimumThermalStatus"
        "niv99OfflineIsolationEvidenceValid=$niv99OfflineIsolationEvidenceValid"
        "niv99MissingOrNonZeroFields=$($niv99MissingOrNonZeroFields -join ',')"
        "privateMasterVerified=$privateMasterVerified"
        "terminalPrivateEvidenceCaptured=$terminalPrivateEvidenceCaptured"
        "privateMasterPath=$privateMasterPath"
    ) | Set-Content -LiteralPath (Join-Path $runDirectory 'pipeline-verdict.txt')

    Invoke-Adb shell dumpsys media.camera |
        Set-Content -LiteralPath (Join-Path $runDirectory 'camera-service-after.txt')
    Invoke-Adb shell dumpsys meminfo $packageName |
        Set-Content -LiteralPath (Join-Path $runDirectory 'meminfo-after.txt')
    $thermalAfter = Read-ThermalSnapshot
    $thermalAfter.Text |
        Set-Content -LiteralPath (Join-Path $runDirectory 'thermal-after.txt')
    $thermalAfterStatus = 0
    if (-not [int]::TryParse($thermalAfter.Status, [ref]$thermalAfterStatus)) {
        throw 'Unable to read the Android thermal status after recording'
    }
    $thermalEvidenceValid =
        $thermalBeforeStatus -ge $MinimumThermalStatus -and
        $thermalAfterStatus -ge $MinimumThermalStatus
    # Screenshot evidence is useful but must not hide the authoritative recording verdict. Stream
    # PNG bytes directly from exec-out: vendor builds can reject temporary device-side screenshot
    # paths, and PowerShell text redirection would corrupt the binary payload.
    $screenshotCaptured = $false
    $screenshotFailure = ''
    try {
        Invoke-Adb shell input keyevent KEYCODE_WAKEUP | Out-Null
        Start-Sleep -Milliseconds 500
        Save-AdbExecOutBinary `
            -Arguments @('exec-out', 'screencap', '-p') `
            -Destination (Join-Path $runDirectory 'end-screen.png')
        $screenshotCaptured = $true
    } catch {
        $screenshotFailure = $_.Exception.Message
        $screenshotFailure |
            Set-Content -LiteralPath (Join-Path $runDirectory 'screenshot-failure.txt')
    }

    [pscustomobject]@{
        schema = 'neo-qa-hypergrid/lq001-endurance-run/v5'
        checkpoint = $checkpoint
        mode = $Mode
        pipeline = $Pipeline
        resolution = $Resolution
        fps = $Fps
        aspect = $resolvedAspect
        requested_detail_profile = $DetailProfile
        observed_detail_profile = $certificateFields['selectedDetailProfile']
        detail_profile_evidence_valid = $detailProfileEvidenceValid
        quality_evidence_status = $qualityEvidenceStatus
        quality_evidence_not_failed = $qualityEvidenceNotFailed
        quality_failure_codes = $qualityFailureCodes
        recording_stop_origin = $recordingStopOrigin
        recording_stop_detail = $recordingStopDetail
        planned_stop_origin_valid = $plannedStopOriginValid
        early_stop_marker = $earlyStopMarker
        af_recording_unlock = $AfRecordingUnlock
        gl_preview = $GlPreview
        p010_cadence_safe_session = $P010CadenceSafeSession
        gpu_stage_timing = $GpuStageTiming
        egl_decode_once = $EglDecodeOnce
        egl_gather_grid = $EglGatherGrid
        capture_safe_monitoring = $CaptureSafeMonitoring
        minimum_thermal_status = $MinimumThermalStatus
        thermal_before_status = $thermalBeforeStatus
        thermal_after_status = $thermalAfterStatus
        thermal_before_current_hal_skin_c = $thermalBefore.SkinC
        thermal_after_current_hal_skin_c = $thermalAfter.SkinC
        thermal_before_skin_source = $thermalBefore.SkinSource
        thermal_after_skin_source = $thermalAfter.SkinSource
        thermal_series_continuous = $false
        thermal_evidence_valid = $thermalEvidenceValid
        label = $label
        requested_duration_seconds = $DurationSeconds
        measured_wait_seconds = [math]::Round($timer.Elapsed.TotalSeconds, 3)
        lifecycle_interruption_requested = ($BackgroundAtSeconds -gt 0)
        lifecycle_background_elapsed_seconds = $lifecycleBackgroundElapsed
        lifecycle_foreground_elapsed_seconds = $lifecycleForegroundElapsed
        lifecycle_commands_completed = ($BackgroundAtSeconds -eq 0 -or
            ($lifecycleBackgroundRequested -and $lifecycleForegroundRequested))
        recording_start_proven = $recordingWasStarted
        media_store_finalized = $mediaStoreFinalized
        output_finalized = $finalized
        media_store_row = $finalRow
        private_master_verified = $privateMasterVerified
        terminal_private_evidence_captured = $terminalPrivateEvidenceCaptured
        private_master_path = $privateMasterPath
        quality_certificate_path = $privateCertificatePath
        rolling_10gib_evidence = $rollingEvidenceValid
        rolling_trigger_reached = $rollingTriggerReached
        rolling_extension_count = $rollingExtensionCount
        rolling_extended_bytes = $rollingExtendedBytes
        format_selection_evidence = $formatEvidenceValid
        cadence_evidence_valid = $cadenceEvidenceValid
        encoded_cadence_evidence_valid = $encodedCadenceEvidenceValid
        source_cadence_evidence_valid = $sourceCadenceEvidenceValid
        source_capture_fps = $sourceCaptureFps
        source_rolling_capture_fps = $sourceRollingCaptureFps
        source_recorded_frames = $sourceRecordedFrames
        source_dropped_recording_frames = $sourceDroppedRecordingFrames
        source_explicit_dropped_recording_frames = $sourceExplicitDroppedRecordingFrames
        source_inferred_timestamp_gap_frames = $sourceInferredTimestampGapFrames
        source_unrecovered_timestamp_gap_frames = $sourceUnrecoveredTimestampGapFrames
        record_frame_delivery_steady_state_clean = $recordFrameDeliverySteadyStateClean
        realtime_dispatch_dropped_frames = $realtimeDispatchDroppedFrames
        realtime_dispatch_rejected_frames = $realtimeDispatchRejectedFrames
        encoder_submitted_frames = $encoderSubmittedFrames
        wall_active_recording_duration_ms = $wallActiveRecordingDurationMs
        maximum_wall_duration_loss_ms = $maximumWallDurationLossMs
        minimum_source_frames = $minimumSourceFrames
        ten_bit_evidence_valid = $tenBitEvidenceValid
        audio_evidence_valid = $audioEvidenceValid
        minimum_required_duration_ms = $minimumRequiredDurationMs
        measured_output_duration_ms = $measuredOutputDurationMs
        duration_evidence_valid = $durationEvidenceValid
        niv99_offline_isolation_evidence_valid = $niv99OfflineIsolationEvidenceValid
        niv99_missing_or_non_zero_fields = @($niv99MissingOrNonZeroFields)
        screenshot_captured = $screenshotCaptured
        screenshot_failure = $screenshotFailure
        evidence_directory = $runDirectory
    } | ConvertTo-Json -Depth 4 |
        Set-Content -LiteralPath (Join-Path $runDirectory 'run-result.json')

    if (-not $finalized) {
        throw "$Pipeline output did not finalize with valid evidence; evidence=$runDirectory"
    }
    if (-not $rollingEvidenceValid) {
        throw "$Pipeline did not prove a second 10 GiB physical reservation; evidence=$runDirectory"
    }
    if (-not $formatEvidenceValid) {
        throw "$Pipeline did not prove $Resolution at selected $Fps FPS; evidence=$runDirectory"
    }
    if (-not $detailProfileEvidenceValid) {
        throw "$Pipeline did not prove requested detail profile $DetailProfile; observed=$($certificateFields['selectedDetailProfile']); evidence=$runDirectory"
    }
    if (-not $qualityEvidenceNotFailed) {
        throw "$Pipeline quality certificate failed; status=$qualityEvidenceStatus failures=$qualityFailureCodes evidence=$runDirectory"
    }
    if (-not $cadenceEvidenceValid) {
        throw "$Pipeline did not prove zero-drop $Fps FPS cadence; evidence=$runDirectory"
    }
    if (-not $tenBitEvidenceValid) {
        throw "$Pipeline did not prove the 10-bit $Resolution transport contract; evidence=$runDirectory"
    }
    if (-not $audioEvidenceValid) {
        throw "$Pipeline did not prove synchronized audio retention; evidence=$runDirectory"
    }
    if (-not $durationEvidenceValid) {
        throw "$Pipeline output duration ${measuredOutputDurationMs}ms is below the required ${minimumRequiredDurationMs}ms boundary; evidence=$runDirectory"
    }
    if (-not $plannedStopOriginValid) {
        throw "Recording stop origin was not the planned debug boundary; origin=$recordingStopOrigin detail=$recordingStopDetail early=$earlyStopMarker evidence=$runDirectory"
    }
    if (-not $niv99OfflineIsolationEvidenceValid) {
        throw "NIV99 live-path isolation was not proven; nonzero-or-missing=$($niv99MissingOrNonZeroFields -join ','); evidence=$runDirectory"
    }
    if (-not $thermalEvidenceValid) {
        throw "Thermal evidence did not remain at or above status $MinimumThermalStatus; before=$thermalBeforeStatus after=$thermalAfterStatus evidence=$runDirectory"
    }
    Write-Output "RUN_COMPLETE checkpoint=$checkpoint pipeline=$Pipeline detailProfile=$DetailProfile resolution=$Resolution fps=$Fps evidence=$runDirectory finalized=$finalized rolling10GiB=$rollingEvidenceValid cadence=$cadenceEvidenceValid tenBit=$tenBitEvidenceValid audio=$audioEvidenceValid durationMs=$measuredOutputDurationMs qualityEvidence=$qualityEvidenceStatus thermalBefore=$thermalBeforeStatus thermalAfter=$thermalAfterStatus niv99OfflineOnly=$niv99OfflineIsolationEvidenceValid"
}
catch {
    $primaryTestFailure = $_.Exception.Message
    [ordered]@{
        schema = 'neo-qa-hypergrid/lq001-run-error/v1'
        checkpoint = $checkpoint
        mode = $Mode
        pipeline = $Pipeline
        resolution = $Resolution
        fps = $Fps
        requested_detail_profile = $DetailProfile
        requested_duration_seconds = $DurationSeconds
        status = 'FAIL'
        failure = $primaryTestFailure
        terminal_recording_failure = $terminalDirectFailure
        early_stop_origin = $earlyStopMarker
        external_stop_origin = $externalStopMarker
        candidate_identity_evidence = 'candidate-device-identity.txt'
        evidence_directory = $runDirectory
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $runDirectory 'run-error.json')
    throw
}
finally {
    if ($recordingWasStarted -and -not $stopWasRequested -and
        [string]::IsNullOrWhiteSpace($earlyStopMarker) -and
        [string]::IsNullOrWhiteSpace($externalStopMarker)) {
        Invoke-QABoundedAdbCleanup -AdbPath $AdbPath -Serial $Serial -Arguments @(
            'shell', 'am', 'broadcast', '-a', "$packageName.DEBUG_COMMAND",
            '-n', "$packageName/.ui.DebugCommandReceiver", '--es', 'debug_action', 'stop_recording'
        ) | Out-Null
        Start-Sleep -Seconds 5
    }
    if ($logProcess -and -not $logProcess.HasExited) {
        Stop-Process -Id $logProcess.Id -Force -ErrorAction SilentlyContinue
    }
    if ($focusedLogProcess -and -not $focusedLogProcess.HasExited) {
        Stop-Process -Id $focusedLogProcess.Id -Force -ErrorAction SilentlyContinue
    }
    if ($eventLogProcess -and -not $eventLogProcess.HasExited) {
        Stop-Process -Id $eventLogProcess.Id -Force -ErrorAction SilentlyContinue
    }
    if ($persistentLogProcess -and -not $persistentLogProcess.HasExited) {
        Stop-Process -Id $persistentLogProcess.Id -Force -ErrorAction SilentlyContinue
    }
    $cleanupResults = [ordered]@{}
    try {
        $cleanupResults.logcat = Invoke-QABoundedAdbCleanup -AdbPath $AdbPath -Serial $Serial -Arguments @(
            'logcat', '-d', '-b', 'main', '-b', 'system', '-b', 'crash', '-b', 'events', '-v', 'threadtime'
        )
        $cleanupResults.logcat.output | Set-Content -LiteralPath (Join-Path $runDirectory 'logcat-final.txt')
        $cleanupResults.exit_info = Invoke-QABoundedAdbCleanup -AdbPath $AdbPath -Serial $Serial -Arguments @(
            'shell', 'dumpsys', 'activity', 'exit-info', $packageName
        )
        $cleanupResults.exit_info.output |
            Set-Content -LiteralPath (Join-Path $runDirectory 'exit-info-postmortem-before-cleanup.txt')
        if (-not [string]::IsNullOrWhiteSpace($appOwnedLogPath)) {
            $cleanupResults.persistent_log = Invoke-QABoundedAdbCleanup -AdbPath $AdbPath -Serial $Serial -Arguments @(
                'shell', 'tail', '-n', '4096', $appOwnedLogPath
            )
            $cleanupResults.persistent_log.output |
                Set-Content -LiteralPath (Join-Path $runDirectory 'app-persistent-log-postmortem.txt')
        }
    } catch {
        $cleanupResults.evidence_write_error = $_.Exception.Message
    } finally {
        # Camera closure and screen-off are attempted even when evidence capture or USB failed.
        $cleanupResults.force_stop = Invoke-QABoundedAdbCleanup -AdbPath $AdbPath -Serial $Serial -Arguments @(
            'shell', 'am', 'force-stop', $packageName
        )
        $cleanupResults.screen_off = Invoke-QABoundedAdbCleanup -AdbPath $AdbPath -Serial $Serial -Arguments @(
            'shell', 'input', 'keyevent', 'KEYCODE_SLEEP'
        )
        $cleanupResults | ConvertTo-Json -Depth 5 |
            Set-Content -LiteralPath (Join-Path $runDirectory 'cleanup-result.json')
        @(
            "released_utc=$([DateTime]::UtcNow.ToString('o'))"
            "evidence=$runDirectory"
            "recording_started=$recordingWasStarted"
            "stop_requested=$stopWasRequested"
            "camera_close_command_succeeded=$($cleanupResults.force_stop.success)"
            "screen_off_command_succeeded=$($cleanupResults.screen_off.success)"
        ) | Set-Content -LiteralPath $lockPath
        if (-not $cleanupResults.force_stop.success -or -not $cleanupResults.screen_off.success) {
            Write-Output "CLEANUP_INCOMPLETE evidence=$runDirectory cameraClosed=$($cleanupResults.force_stop.success) screenOff=$($cleanupResults.screen_off.success)"
            if ([string]::IsNullOrWhiteSpace($primaryTestFailure)) {
                throw 'Test completed but device cleanup could not be confirmed; see cleanup-result.json'
            }
        }
    }
}
