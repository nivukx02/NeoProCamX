param(
    [string]$AdbPath = "adb",
    [string]$Serial = "",
    [string]$PackageName = "com.neoproc.neoproccamx",
    [string]$ApkPath = "app\build\outputs\apk\debug\app-debug.apk",
    [string]$OutputDir = "qa-artifacts\phase6-recording-matrix",
    [ValidateRange(250, 3600000)]
    [int]$DurationMs = 1200,
    [ValidateRange(1, 3600)]
    [int]$PostRecordWaitSec = 24,
    [ValidateRange(1, 3600)]
    [int]$Npv10PostRecordWaitSec = 900,
    [string[]]$IncludeRows = @(),
    [int]$MaxRows = 0,
    [switch]$Install,
    [switch]$SkipRaw,
    [switch]$DryRun,
    [string]$RunId = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-AdbPath {
    param([string]$RequestedPath)
    if (-not [string]::IsNullOrWhiteSpace($RequestedPath) -and $RequestedPath -notin @("adb", "adb.exe")) {
        return $RequestedPath
    }
    $candidates = New-Object System.Collections.Generic.List[string]
    foreach ($name in @("adb.exe", "adb")) {
        $cmd = Get-Command $name -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($cmd -and $cmd.Source) { [void]$candidates.Add($cmd.Source) }
    }
    foreach ($sdkRoot in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA "Android\Sdk"))) {
        if ([string]::IsNullOrWhiteSpace($sdkRoot)) { continue }
        [void]$candidates.Add((Join-Path $sdkRoot "platform-tools\adb.exe"))
        [void]$candidates.Add((Join-Path $sdkRoot "platform-tools\adb"))
    }
    foreach ($candidate in $candidates | Select-Object -Unique) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path $candidate)) {
            return $candidate
        }
    }
    throw "Unable to locate adb. Pass -AdbPath explicitly or install Android platform-tools."
}

function Join-CommandArgs {
    param([string[]]$CommandArgs)
    return (($CommandArgs | ForEach-Object {
        if ($_ -match '[\s"]') {
            '"' + (($_ -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"'
        } else {
            $_
        }
    }) -join ' ')
}

function Invoke-Process {
    param(
        [string]$FilePath,
        [string[]]$CommandArgs,
        [int]$TimeoutSec = 60,
        [switch]$IgnoreExitCode
    )
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $FilePath
    $psi.Arguments = Join-CommandArgs -CommandArgs $CommandArgs
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $proc = [System.Diagnostics.Process]::new()
    $proc.StartInfo = $psi
    [void]$proc.Start()
    $stdoutTask = $proc.StandardOutput.ReadToEndAsync()
    $stderrTask = $proc.StandardError.ReadToEndAsync()
    $timedOut = -not $proc.WaitForExit($TimeoutSec * 1000)
    if ($timedOut) {
        try { $proc.Kill($true) } catch {}
        $null = $proc.WaitForExit()
    }
    $stdout = $stdoutTask.GetAwaiter().GetResult()
    $stderr = $stderrTask.GetAwaiter().GetResult()
    $exitCode = if ($timedOut) { 124 } else { $proc.ExitCode }
    $result = [pscustomobject]@{
        ExitCode = $exitCode
        TimedOut = $timedOut
        StdOut = $stdout
        StdErr = $stderr
        Output = (@($stdout, $stderr) -join [Environment]::NewLine).Trim()
    }
    $proc.Dispose()
    if (-not $IgnoreExitCode -and $exitCode -ne 0) {
        throw "Command failed: $FilePath $($CommandArgs -join ' ')`n$($result.Output)"
    }
    return $result
}

function Get-AdbArgs {
    param([string[]]$CommandArgs)
    if ([string]::IsNullOrWhiteSpace($Serial)) { return $CommandArgs }
    return @("-s", $Serial) + $CommandArgs
}

function Invoke-Adb {
    param([string[]]$CommandArgs, [int]$TimeoutSec = 60, [switch]$IgnoreExitCode)
    Invoke-Process -FilePath $script:ResolvedAdbPath -CommandArgs (Get-AdbArgs -CommandArgs $CommandArgs) -TimeoutSec $TimeoutSec -IgnoreExitCode:$IgnoreExitCode
}

function Invoke-AdbShell {
    param([string]$Command, [int]$TimeoutSec = 60, [switch]$IgnoreExitCode)
    Invoke-Adb -CommandArgs @("shell", $Command) -TimeoutSec $TimeoutSec -IgnoreExitCode:$IgnoreExitCode
}

function Invoke-WakeDeviceForRecording {
    Invoke-AdbShell -Command "input keyevent KEYCODE_WAKEUP" -TimeoutSec 10 -IgnoreExitCode | Out-Null
    Invoke-AdbShell -Command "wm dismiss-keyguard" -TimeoutSec 10 -IgnoreExitCode | Out-Null
    Invoke-AdbShell -Command "input keyevent KEYCODE_MENU" -TimeoutSec 10 -IgnoreExitCode | Out-Null
}

function ConvertTo-AdbShellLiteral {
    param([string]$Value)
    if ($Value -match '^[A-Za-z0-9_./:@%+=,-]+$') {
        return $Value
    }
    return "'" + ($Value -replace "'", "'\\''") + "'"
}

function Join-AdbShellArgs {
    param([string[]]$ShellArgs)
    return (($ShellArgs | ForEach-Object { ConvertTo-AdbShellLiteral -Value $_ }) -join " ")
}

function Get-DeviceSerials {
    $devices = Invoke-Process -FilePath $script:ResolvedAdbPath -CommandArgs @("devices") -TimeoutSec 20
    return ($devices.StdOut -split "`r?`n") |
        Where-Object { $_ -match "^\S+\s+device(\s|$)" } |
        ForEach-Object { ($_ -split "\s+")[0] }
}

function New-Row {
    param(
        [string]$Id,
        [string]$Preset,
        [string]$Aspect,
        [string]$VideoMode,
        [bool]$Raw,
        [bool]$Log,
        [string]$HdrMode,
        [string]$ColorSpace,
        [string]$Resolution,
        [int]$Fps,
        [string]$ColorProfile,
        [string]$ExpectedDelivery,
        [string]$ExpectedTransfer,
        [bool]$LiveCaptureSupported = $true,
        [string]$SkipReason = ""
    )
    [pscustomobject]@{
        Id = $Id
        Label = "phase6_$Id"
        Preset = $Preset
        Aspect = $Aspect
        VideoMode = $VideoMode
        Raw = $Raw
        Log = $Log
        HdrMode = $HdrMode
        ColorSpace = $ColorSpace
        Resolution = $Resolution
        Fps = $Fps
        ColorProfile = $ColorProfile
        ExpectedDelivery = $ExpectedDelivery
        ExpectedTransfer = $ExpectedTransfer
        LiveCaptureSupported = $LiveCaptureSupported
        SkipReason = $SkipReason
    }
}

function Get-MatrixRows {
    $targets = @(
        @{ Suffix = "1920x1080_30fps"; Aspect = "16:9"; Resolution = "1920x1080"; Fps = 30 },
        @{ Suffix = "3840x2160_30fps"; Aspect = "16:9"; Resolution = "3840x2160"; Fps = 30 },
        @{ Suffix = "3840x2160_60fps"; Aspect = "16:9"; Resolution = "3840x2160"; Fps = 60 }
    )
    $profiles = @(
        @{ Prefix = "video_sdr"; Preset = "VIDEO"; Mode = "PRO"; Raw = $false; Log = $false; Hdr = "OFF"; Color = "Rec.709"; Profile = "Natural"; Delivery = "SDR"; Transfer = "SDR"; Live = $true; Skip = "" },
        @{ Prefix = "pro_sdr"; Preset = "PRO"; Mode = "PRO"; Raw = $false; Log = $false; Hdr = "OFF"; Color = "Rec.709"; Profile = "Natural"; Delivery = "SDR"; Transfer = "SDR"; Live = $true; Skip = "" },
        @{ Prefix = "hdr_hlg"; Preset = "HDR"; Mode = "PRO"; Raw = $false; Log = $false; Hdr = "HLG"; Color = "Rec.2020"; Profile = "Natural"; Delivery = "HDR"; Transfer = "HLG"; Live = $true; Skip = "" },
        @{ Prefix = "ndr10_plus"; Preset = "HDR"; Mode = "PRO"; Raw = $false; Log = $false; Hdr = "NDR10+"; Color = "Rec.2020-NDR10+"; Profile = "NDR10+"; Delivery = "HDR"; Transfer = "HLG"; Live = $true; Skip = "" },
        @{ Prefix = "log_nlog_base"; Preset = "LOG_VIDEO"; Mode = "LOG"; Raw = $false; Log = $true; Hdr = "OFF"; Color = "Rec.2020"; Profile = "N-Log Base"; Delivery = "LOG"; Transfer = "LOG"; Live = $false; Skip = "NeoLog is convert-only; legacy LOG_VIDEO is not a supported live capture preset" },
        @{ Prefix = "log_nlog_cinema"; Preset = "LOG_VIDEO"; Mode = "LOG"; Raw = $false; Log = $true; Hdr = "OFF"; Color = "Rec.2020"; Profile = "N-Log Cinema"; Delivery = "LOG"; Transfer = "LOG"; Live = $false; Skip = "NeoLog is convert-only; legacy LOG_VIDEO is not a supported live capture preset" }
    )
    $rows = New-Object System.Collections.Generic.List[object]
    foreach ($profile in $profiles) {
        foreach ($target in $targets) {
            [void]$rows.Add((New-Row `
                -Id "$($profile.Prefix)_$($target.Suffix)" `
                -Preset $profile.Preset `
                -Aspect $target.Aspect `
                -VideoMode $profile.Mode `
                -Raw $profile.Raw `
                -Log $profile.Log `
                -HdrMode $profile.Hdr `
                -ColorSpace $profile.Color `
                -Resolution $target.Resolution `
                -Fps $target.Fps `
                -ColorProfile $profile.Profile `
                -ExpectedDelivery $profile.Delivery `
                -ExpectedTransfer $profile.Transfer `
                -LiveCaptureSupported $profile.Live `
                -SkipReason $profile.Skip))
        }
    }
    if (-not $SkipRaw) {
        [void]$rows.Add((New-Row `
            -Id "raw_12bit_1920x1080_24fps" `
            -Preset "RAW_MODE" `
            -Aspect "16:9" `
            -VideoMode "RAW" `
            -Raw $true `
            -Log $false `
            -HdrMode "OFF" `
            -ColorSpace "RAW" `
            -Resolution "1920x1080" `
            -Fps 24 `
            -ColorProfile "Natural" `
            -ExpectedDelivery "RAW" `
            -ExpectedTransfer "RAW"))
    }
    $selected = @($rows.ToArray())
    if ($IncludeRows.Count -gt 0) {
        $include = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        foreach ($rowId in $IncludeRows) { [void]$include.Add($rowId) }
        $selected = @($rows | Where-Object { $include.Contains($_.Id) })
    }
    if ($MaxRows -gt 0) {
        $selected = @($selected | Select-Object -First $MaxRows)
    }
    return @($selected)
}

function Get-KnownPlatformNoise {
    @(
        "VideoCapabilities: Unsupported mime",
        "VideoCapabilities: Unrecognized profile",
        "AudioCapabilities: Unsupported",
        "AString : ctor got NULL",
        "MediaCodec: no metrics handle found",
        "AIBinder_linkToDeath",
        "ColorUtils: expected specified color aspects",
        "Failed to query component interface for required system resources",
        "GraphicBufferSource: released unpopulated slots",
        "Access denied finding property",
        "InteractionJankMonitor: Initializing without READ_DEVICE_CONFIG"
    )
}

function Test-KnownPlatformNoise {
    param([string]$Line)
    foreach ($pattern in Get-KnownPlatformNoise) {
        if ($Line.IndexOf($pattern, [StringComparison]::OrdinalIgnoreCase) -ge 0) { return $true }
    }
    return $false
}

function Get-BlockingLogLines {
    param([string[]]$Lines)
    $blocking = New-Object System.Collections.Generic.List[string]
    foreach ($line in $Lines) {
        if (Test-KnownPlatformNoise -Line $line) { continue }
        if ($line -match "FATAL EXCEPTION|AndroidRuntime.*(Exception|FATAL|Error)|RecordingManager:.*(Failed|Exception|Error|error)|NPV10.*(conversion failed|publish failed|Failed to|Exception|error)|CameraDevice.*error|CameraAccessException|MediaCodec.*CodecException|EGL_BAD_(ALLOC|SURFACE|CONTEXT|DISPLAY)|\balready connected\b|Critical memory pressure|CRITICAL_MEMORY") {
            [void]$blocking.Add($line)
        }
    }
    return @($blocking)
}

function Get-RowScopedLogLines {
    param([object]$Row, [string[]]$Lines)
    $startIndex = -1
    $labelPattern = [regex]::Escape("validationLabel=$($Row.Label)")
    for ($i = 0; $i -lt $Lines.Count; $i++) {
        if ($Lines[$i] -match $labelPattern) {
            $startIndex = $i
            break
        }
    }
    if ($startIndex -lt 0) {
        $labelOnlyPattern = [regex]::Escape($Row.Label)
        for ($i = 0; $i -lt $Lines.Count; $i++) {
            if ($Lines[$i] -match $labelOnlyPattern) {
                $startIndex = $i
                break
            }
        }
    }
    if ($startIndex -lt 0) { return @($Lines) }
    return @($Lines[$startIndex..($Lines.Count - 1)])
}

function Get-PostAppliedDebugLogLines {
    param([string[]]$RowLines)
    $startIndex = -1
    for ($i = 0; $i -lt $RowLines.Count; $i++) {
        if ($RowLines[$i] -match "\bappliedDebugConfig\b") {
            $startIndex = $i
            break
        }
    }
    if ($startIndex -lt 0) { return @($RowLines) }
    return @($RowLines[$startIndex..($RowLines.Count - 1)])
}

function Invoke-RecordCommand {
    param([object]$Row)
    $component = "$PackageName/.ui.DebugCommandReceiver"
    $shellCommand = Join-AdbShellArgs -ShellArgs @(
        "am", "broadcast",
        "-a", "com.neoproc.neoproccamx.DEBUG_COMMAND",
        "-n", $component,
        "--es", "debug_preset", $Row.Preset,
        "--es", "debug_action", "record_clip",
        "--es", "debug_duration_ms", $DurationMs.ToString(),
        "--es", "debug_validation_label", $Row.Label,
        "--es", "debug_aspect", $Row.Aspect,
        "--es", "debug_color_profile", $Row.ColorProfile,
        "--ez", "debug_raw", $Row.Raw.ToString().ToLowerInvariant(),
        "--ez", "debug_log", $Row.Log.ToString().ToLowerInvariant(),
        "--es", "debug_video_hdr_mode", $Row.HdrMode,
        "--es", "debug_video_color_space", $Row.ColorSpace,
        "--es", "debug_video_resolution", $Row.Resolution,
        "--es", "debug_video_fps", $Row.Fps.ToString(),
        "--es", "debug_video_mode", $Row.VideoMode,
        "--es", "debug_raw_compression", "HIGH_COMPRESSION"
    )
    Invoke-AdbShell -Command $shellCommand -TimeoutSec 30 -IgnoreExitCode
}

function Wait-ForRecordingSavedLog {
    param([object]$Row, [string]$DeviceTime)
    $recordingBudgetSec = [math]::Ceiling($DurationMs / 1000.0)
    $startedAt = Get-Date
    $deadline = $startedAt.AddSeconds($recordingBudgetSec + $PostRecordWaitSec)
    $npv10Deadline = $startedAt.AddSeconds($recordingBudgetSec + [math]::Max($PostRecordWaitSec, $Npv10PostRecordWaitSec))
    $extendedForNpv10 = $false
    $latest = ""
    $stableMediaIdentity = ""
    $stableMediaPolls = 0
    # Sleep briefly before the first poll. The deadline already includes the requested clip duration,
    # so long clips are not judged before their scheduled stop can publish the MediaStore row.
    Start-Sleep -Seconds 5
    while ((Get-Date) -lt $deadline) {
        $logcatArgs = @("logcat")
        if (-not [string]::IsNullOrWhiteSpace($DeviceTime)) {
            $logcatArgs += @("-T", $DeviceTime)
        }
        $logcatArgs += @("-d", "-v", "time")
        $latest = (Invoke-Adb -CommandArgs $logcatArgs -TimeoutSec 60 -IgnoreExitCode).Output
        if ($latest -match "FATAL EXCEPTION|AndroidRuntime.*(Exception|FATAL|Error)") {
            return [pscustomobject]@{
                Status = "CRASH"
                LogText = $latest
                Details = "Fatal process failure observed before durable MOV publication"
            }
        }
        $rowScopedLines = @(Get-RowScopedLogLines -Row $Row -Lines @($latest -split "`r?`n"))
        $rowScopedLogText = ($rowScopedLines -join "`n")
        # A rejected NPV10 master is a terminal, row-scoped result: the production path has
        # deliberately preserved the private source and refused MediaStore publication. Waiting
        # for the long conversion deadline cannot change that outcome and obscures the real quality
        # failure behind a generic timeout. Return immediately while retaining the complete log.
        $terminalNpv10Failure = $rowScopedLines | Where-Object {
            $_ -match "NPV10 failed master preserved privately and not announced as saved:" -or
            $_ -match "NPV10 master was preserved for recovery but failed publication:"
        } | Select-Object -Last 1
        if ($terminalNpv10Failure) {
            $failureDetail = if ($terminalNpv10Failure -match "(?:reason=|failed publication:\s*)(.+)$") {
                $Matches[1].Trim()
            } else {
                $terminalNpv10Failure.Trim()
            }
            return [pscustomobject]@{
                Status = "QUALITY_FAILURE"
                LogText = $latest
                Details = "NPV10 failed closed before MOV publication: $failureDetail"
            }
        }
        if (-not $extendedForNpv10 -and
            $npv10Deadline -gt $deadline -and
            $rowScopedLogText -match "NPV10 private master|Pending NPV10 private master|NPV10_") {
            $deadline = $npv10Deadline
            $extendedForNpv10 = $true
            Write-Host "   [Wait] NPV10 conversion detected; waiting up to $Npv10PostRecordWaitSec seconds after stop for MOV publish." -ForegroundColor Yellow
        }

        # The completion signal must be row-specific. A broad "Recording saved ->" log can belong to
        # the previous row when long matrix runs roll into the next mode.
        $mediaText = Get-MediaStoreMetadata -Row $Row
        $displayName = Get-MediaValue -ContentQueryOutput $mediaText -Key "_display_name"
        $isPending = Get-MediaValue -ContentQueryOutput $mediaText -Key "is_pending"
        $sizeText = Get-MediaValue -ContentQueryOutput $mediaText -Key "_size"
        $sizeBytes = 0L
        [void][long]::TryParse($sizeText, [ref]$sizeBytes)
        if (-not [string]::IsNullOrWhiteSpace($displayName) -and
            $displayName -like "*$($Row.Label)*" -and
            $displayName -like "*.mov" -and
            $isPending -eq "0" -and
            $sizeBytes -gt 0) {
            $mediaIdentity = "$displayName|$sizeBytes"
            if ($mediaIdentity -eq $stableMediaIdentity) {
                $stableMediaPolls += 1
            } else {
                $stableMediaIdentity = $mediaIdentity
                $stableMediaPolls = 1
            }
            if ($stableMediaPolls -ge 2) {
                return [pscustomobject]@{
                    Status = "PUBLISHED"
                    LogText = $latest
                    Details = "Durable MOV published: name=$displayName size=$sizeBytes"
                }
            }
        } else {
            $stableMediaIdentity = ""
            $stableMediaPolls = 0
        }

        Start-Sleep -Seconds 4
    }
    return [pscustomobject]@{
        Status = "TIMEOUT"
        LogText = $latest
        Details = "Timed out waiting for a non-pending, non-empty labeled MOV"
    }
}

function Get-MediaStoreMetadata {
    param([object]$Row)
    $projection = "_id:_display_name:mime_type:width:height:duration:bitrate:capture_framerate:color_standard:color_transfer:color_range:relative_path:_size:is_pending"
    $where = "_display_name LIKE '%$($Row.Label)%'"
    $command = "content query --uri content://media/external/video/media --projection $projection --where `"$where`" --sort `"date_added DESC`""
    $query = Invoke-AdbShell -Command $command -TimeoutSec 60 -IgnoreExitCode
    return $query.Output
}

function Get-MediaValue {
    param([string]$ContentQueryOutput, [string]$Key)
    foreach ($part in ($ContentQueryOutput -split ", ")) {
        $clean = ($part -replace "^Row:\s*\d+\s+", "").Trim()
        $prefix = "$Key="
        if ($clean.StartsWith($prefix, [StringComparison]::Ordinal)) {
            $value = $clean.Substring($prefix.Length).Trim()
            if ($value -eq "NULL") { return "" }
            return $value
        }
    }
    return ""
}

function Get-HarnessRuntimePermissions {
    param([int]$SdkInt)
    $permissions = @(
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO"
    )
    if ($SdkInt -ge 33) {
        $permissions += "android.permission.POST_NOTIFICATIONS"
    }
    return $permissions
}

function Get-PermissionGrantState {
    param([Parameter(Mandatory)][string]$Permission)
    $dump = Invoke-Adb -CommandArgs @("shell", "dumpsys", "package", $PackageName) -TimeoutSec 40 -IgnoreExitCode
    if ($dump.TimedOut -or $dump.ExitCode -ne 0) { return $null }
    $pattern = "^\s*$([regex]::Escape($Permission)):\s+granted=(true|false)"
    $line = $dump.Output -split "`r?`n" |
        Where-Object { $_ -match $pattern } |
        Select-Object -First 1
    if ($line -match $pattern) { return ($Matches[1] -eq "true") }
    return $null
}

function Save-OriginalPermissionStates {
    param([int]$SdkInt, [bool]$PackageWasInstalled)
    $script:OriginalPermissionStates.Clear()
    foreach ($permission in (Get-HarnessRuntimePermissions -SdkInt $SdkInt)) {
        $state = if ($PackageWasInstalled) {
            Get-PermissionGrantState -Permission $permission
        } else {
            $false
        }
        if ($null -eq $state) {
            throw "Unable to snapshot runtime permission state for $permission; refusing to mutate permissions"
        }
        $script:OriginalPermissionStates[$permission] = [bool]$state
    }
    $script:PermissionSnapshotCaptured = $true
}

function Grant-HarnessRuntimePermissions {
    foreach ($permission in $script:OriginalPermissionStates.Keys) {
        $grant = Invoke-Adb -CommandArgs @("shell", "pm", "grant", $PackageName, $permission) -TimeoutSec 20 -IgnoreExitCode
        if ($grant.TimedOut -or $grant.ExitCode -ne 0) {
            throw "Unable to grant declared runtime permission ${permission}: $($grant.Output)"
        }
        $script:MutatedPermissions.Add($permission) | Out-Null
        $actual = Get-PermissionGrantState -Permission $permission
        if ($actual -ne $true) {
            throw "Declared runtime permission did not become granted: $permission"
        }
    }
}

function Restore-OriginalPermissionStates {
    if (-not $script:PermissionSnapshotCaptured) { return }
    $failures = New-Object System.Collections.Generic.List[string]
    foreach ($permission in $script:MutatedPermissions) {
        $expectedGranted = [bool]$script:OriginalPermissionStates[$permission]
        $verb = if ($expectedGranted) { "grant" } else { "revoke" }
        $restore = Invoke-Adb -CommandArgs @("shell", "pm", $verb, $PackageName, $permission) -TimeoutSec 20 -IgnoreExitCode
        if ($restore.TimedOut -or $restore.ExitCode -ne 0) {
            [void]$failures.Add("$permission command exit=$($restore.ExitCode)")
            continue
        }
        $actual = Get-PermissionGrantState -Permission $permission
        if ($null -eq $actual -or [bool]$actual -ne $expectedGranted) {
            [void]$failures.Add("$permission expected=$expectedGranted actual=$actual")
        }
    }
    if ($failures.Count -gt 0) {
        throw "Unable to restore runtime permissions: $($failures -join '; ')"
    }
}

function Stop-TargetAppForCleanup {
    if (-not $script:DeviceReadyForCleanup) { return }
    if ($script:RecordingCommandOutstanding) {
        $stop = Invoke-Adb -CommandArgs @(
            "shell", "am", "broadcast",
            "-a", "com.neoproc.neoproccamx.DEBUG_COMMAND",
            "-n", "$PackageName/.ui.DebugCommandReceiver",
            "--es", "debug_action", "stop_recording",
            "--es", "debug_validation_label", "phase6_cleanup_$effectiveRunId"
        ) -TimeoutSec 20 -IgnoreExitCode
        if ($stop.TimedOut) {
            Write-Warning "stop_recording cleanup broadcast timed out; force-stop will terminate the target process"
        }
    }
    $forceStop = Invoke-Adb -CommandArgs @("shell", "am", "force-stop", $PackageName) -TimeoutSec 20 -IgnoreExitCode
    if ($forceStop.TimedOut -or $forceStop.ExitCode -ne 0) {
        throw "force-stop failed: exit=$($forceStop.ExitCode) output=$($forceStop.Output)"
    }
    $remainingPid = Invoke-Adb -CommandArgs @("shell", "pidof", "-s", $PackageName) -TimeoutSec 10 -IgnoreExitCode
    if (-not [string]::IsNullOrWhiteSpace($remainingPid.StdOut)) {
        throw "target process still alive after force-stop: pid=$($remainingPid.StdOut.Trim())"
    }
    $script:RecordingCommandOutstanding = $false
}

function Invoke-Cleanup {
    if ($script:CleanupCompleted -or -not $script:DeviceReadyForCleanup) { return }
    $errors = New-Object System.Collections.Generic.List[string]
    try { Stop-TargetAppForCleanup } catch { [void]$errors.Add("app stop: $($_.Exception.Message)") }
    try { Restore-OriginalPermissionStates } catch { [void]$errors.Add("permission restore: $($_.Exception.Message)") }
    $script:CleanupCompleted = $true
    if ($errors.Count -gt 0) { throw ($errors -join "; ") }
}

function New-NonExecutedRowResult {
    param([object]$Row, [ValidateSet("SKIP", "FAIL")][string]$Status, [string]$Reason)
    return [pscustomobject]@{
        RowId = $Row.Id
        Label = $Row.Label
        Status = $Status
        ExpectedDelivery = $Row.ExpectedDelivery
        ExpectedTransfer = $Row.ExpectedTransfer
        DisplayName = ""
        MimeType = ""
        Width = ""
        Height = ""
        CaptureFps = ""
        AppliedFps = ""
        ColorStandard = ""
        ColorTransfer = ""
        RelativePath = ""
        DurationMs = ""
        Bitrate = ""
        SizeBytes = 0L
        IsPending = ""
        Failures = $(if ($Status -eq "FAIL") { $Reason } else { "" })
        Warnings = $(if ($Status -eq "SKIP") { $Reason } else { "" })
    }
}

function Test-RowEvidence {
    param([object]$Row, [string]$LogText, [string]$MediaText)
    $lines = @($LogText -split "`r?`n")
    $rowLines = @(Get-RowScopedLogLines -Row $Row -Lines $lines)
    $postAppliedLines = @(Get-PostAppliedDebugLogLines -RowLines $rowLines)
    $rowLogText = ($rowLines -join "`n")
    $postAppliedLogText = ($postAppliedLines -join "`n")
    $blocking = Get-BlockingLogLines -Lines $rowLines
    $warnings = New-Object System.Collections.Generic.List[string]
    $failures = New-Object System.Collections.Generic.List[string]

    foreach ($line in $blocking) { [void]$failures.Add($line) }

    $displayName = Get-MediaValue -ContentQueryOutput $MediaText -Key "_display_name"
    $mimeType = Get-MediaValue -ContentQueryOutput $MediaText -Key "mime_type"
    $width = Get-MediaValue -ContentQueryOutput $MediaText -Key "width"
    $height = Get-MediaValue -ContentQueryOutput $MediaText -Key "height"
    $fps = Get-MediaValue -ContentQueryOutput $MediaText -Key "capture_framerate"
    $colorTransfer = Get-MediaValue -ContentQueryOutput $MediaText -Key "color_transfer"
    $colorStandard = Get-MediaValue -ContentQueryOutput $MediaText -Key "color_standard"
    $duration = Get-MediaValue -ContentQueryOutput $MediaText -Key "duration"
    $bitrate = Get-MediaValue -ContentQueryOutput $MediaText -Key "bitrate"
    $relativePath = Get-MediaValue -ContentQueryOutput $MediaText -Key "relative_path"
    $sizeText = Get-MediaValue -ContentQueryOutput $MediaText -Key "_size"
    $isPending = Get-MediaValue -ContentQueryOutput $MediaText -Key "is_pending"

    $hasLogcatEvidence = $rowLogText -match "Recording saved ->"
    if ($hasLogcatEvidence) {
        if ($rowLogText -notmatch [regex]::Escape($Row.Label)) { [void]$failures.Add("Missing validation label in log") }
        if ($postAppliedLogText -notmatch "delivery=$([regex]::Escape($Row.ExpectedDelivery))") { [void]$failures.Add("Missing expected delivery=$($Row.ExpectedDelivery)") }
        if ($Row.ExpectedDelivery -eq "SDR" -and $postAppliedLogText -match "delivery=HDR") { [void]$failures.Add("SDR row logged HDR delivery") }
        if ($Row.ExpectedDelivery -eq "LOG" -and ($postAppliedLogText -match "delivery=HDR" -or $postAppliedLogText -match "hdr=(HLG|HDR10|HDR10\+|NDR10\+|NIVUKX)")) {
            [void]$failures.Add("LOG row logged HDR delivery")
        }
        if ($Row.ExpectedDelivery -eq "HDR" -and $postAppliedLogText -notmatch "hdr=$([regex]::Escape($Row.HdrMode))") {
            [void]$failures.Add("HDR row missing hdr=$($Row.HdrMode)")
        }
    } else {
        $hasValidMediaStore = (-not [string]::IsNullOrWhiteSpace($displayName)) -and ($displayName -like "*$($Row.Label)*")
        if ($hasValidMediaStore) {
            Write-Host "   [Fallback] Logcat debug logs suppressed/empty; fallback verified via MediaStore record." -ForegroundColor Yellow
        } else {
            [void]$failures.Add("Missing 'Recording saved ->' in log and no valid MediaStore record found")
        }
    }
    $appliedFps = ""
    foreach ($line in $postAppliedLines) {
        if ($line -match "\bappliedDebugConfig\b.*\bfps=(\d+)\b") {
            $appliedFps = $Matches[1]
        } elseif ($line -match "\bPrepared:\s+\d+x\d+@(\d+)fps\b") {
            $appliedFps = $Matches[1]
        }
    }

    if ([string]::IsNullOrWhiteSpace($displayName)) {
        [void]$failures.Add("Missing MediaStore row")
    } else {
        if ($displayName -notlike "*$($Row.Label)*") { [void]$failures.Add("MediaStore display name mismatch: $displayName") }
        if ($displayName -notlike "*.mov") { [void]$failures.Add("MediaStore file is not MOV: $displayName") }
        $isNpv10Published = $displayName -like "NPV10_*" -or $relativePath -like "*CamXNPV10*"
        $expectedRelativePath = if ($isNpv10Published) { "Movies/CamXNPV10/" } else { "DCIM/CamX/" }
        if ([string]::IsNullOrWhiteSpace($relativePath)) {
            [void]$failures.Add("Missing MediaStore relative_path")
        } elseif ($relativePath -ne $expectedRelativePath) {
            [void]$failures.Add("MediaStore folder mismatch: expected $expectedRelativePath, got $relativePath")
        }
    }
    if ($mimeType -and $mimeType -ne "video/quicktime") {
        if ($mimeType -eq "video/mp4") {
            [void]$warnings.Add("MediaStore scanner reported video/mp4 for MOV container")
        } else {
            [void]$failures.Add("MIME mismatch: $mimeType")
        }
    }
    $sizeBytes = 0L
    if (-not [long]::TryParse($sizeText, [ref]$sizeBytes) -or $sizeBytes -le 0L) {
        [void]$failures.Add("MediaStore MOV has no non-zero stable payload: _size=$sizeText")
    }
    if ($isPending -ne "0") {
        [void]$failures.Add("MediaStore MOV is not durably published: is_pending=$isPending")
    }
    $durationValue = 0L
    if (-not [long]::TryParse($duration, [ref]$durationValue) -or $durationValue -le 0L) {
        [void]$failures.Add("MediaStore MOV has invalid duration: $duration")
    }
    if ($width -and $height -and "$($width)x$($height)" -ne $Row.Resolution) {
        [void]$warnings.Add("Saved ${width}x${height}, requested $($Row.Resolution)")
    }
    $fpsValue = 0.0
    if ($fps -and [double]::TryParse($fps, [ref]$fpsValue)) {
        if ([math]::Abs($fpsValue - [double]$Row.Fps) -gt 0.5) {
            [void]$warnings.Add("Saved ${fpsValue}fps, requested $($Row.Fps)fps")
        }
    }
    $appliedFpsValue = 0
    if ($appliedFps -and [int]::TryParse($appliedFps, [ref]$appliedFpsValue)) {
        if ($appliedFpsValue -ne $Row.Fps) {
            [void]$warnings.Add("Applied ${appliedFpsValue}fps, requested $($Row.Fps)fps")
        }
    }
    $expectedTransferCode = switch ($Row.ExpectedTransfer) {
        "SDR" { "3" }
        "LOG" { "3" }
        "RAW" { "3" }
        "HLG" { "7" }
        "PQ" { "6" }
        default { "" }
    }
    if (-not [string]::IsNullOrWhiteSpace($expectedTransferCode) -and
        -not [string]::IsNullOrWhiteSpace($colorTransfer) -and
        $colorTransfer -ne $expectedTransferCode) {
        [void]$failures.Add(
            "Color transfer mismatch: expected $($Row.ExpectedTransfer) ($expectedTransferCode), got $colorTransfer"
        )
    }

    $status = if ($failures.Count -gt 0) {
        "FAIL"
    } elseif ($warnings.Count -gt 0) {
        "WARN"
    } else {
        "PASS"
    }
    return [pscustomobject]@{
        RowId = $Row.Id
        Label = $Row.Label
        Status = $status
        ExpectedDelivery = $Row.ExpectedDelivery
        ExpectedTransfer = $Row.ExpectedTransfer
        DisplayName = $displayName
        MimeType = $mimeType
        Width = $width
        Height = $height
        CaptureFps = $fps
        AppliedFps = $appliedFps
        ColorStandard = $colorStandard
        ColorTransfer = $colorTransfer
        RelativePath = $relativePath
        DurationMs = $duration
        Bitrate = $bitrate
        SizeBytes = $sizeBytes
        IsPending = $isPending
        Failures = ($failures -join " | ")
        Warnings = ($warnings -join " | ")
    }
}

$script:ResolvedAdbPath = $null
$script:OriginalPermissionStates = @{}
$script:MutatedPermissions = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$script:PermissionSnapshotCaptured = $false
$script:DeviceReadyForCleanup = $false
$script:CleanupCompleted = $false
$script:RecordingCommandOutstanding = $false
$rows = @(Get-MatrixRows)
$effectiveRunId = if ([string]::IsNullOrWhiteSpace($RunId)) {
    Get-Date -Format "yyyyMMdd_HHmmss"
} else {
    $RunId
}
if ($effectiveRunId -notmatch '^[A-Za-z0-9_-]+$') {
    throw "RunId may contain only letters, digits, underscore, and hyphen."
}
if ($PackageName -notmatch '^[A-Za-z0-9_.]+$') {
    throw "PackageName contains unsupported characters."
}
foreach ($row in $rows) {
    $row.Label = "phase6_${effectiveRunId}_$($row.Id)"
}

if ($DryRun) {
    $rows | Select-Object Id, Label, Preset, Aspect, VideoMode, Raw, Log, HdrMode, ColorSpace, Resolution, Fps, ExpectedDelivery, LiveCaptureSupported, SkipReason
    return
}

if ($rows.Count -eq 0) { throw "No Phase 6 rows selected." }

$script:ResolvedAdbPath = Resolve-AdbPath -RequestedPath $AdbPath

if ([string]::IsNullOrWhiteSpace($Serial)) {
    $serials = @(Get-DeviceSerials)
    if ($serials.Count -ne 1) {
        throw "Expected exactly one adb device, found $($serials.Count). Pass -Serial."
    }
    $Serial = $serials[0]
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$summary = New-Object System.Collections.Generic.List[object]
$cleanupFailure = ""
try {
    $script:DeviceReadyForCleanup = $true
    $sdkText = (Invoke-Adb -CommandArgs @("shell", "getprop", "ro.build.version.sdk") -TimeoutSec 10).Output.Trim()
    $sdkInt = 0
    if (-not [int]::TryParse($sdkText, [ref]$sdkInt) -or $sdkInt -le 0) {
        throw "Unable to resolve Android SDK level from device: '$sdkText'"
    }
    $preInstallPackage = Invoke-Adb -CommandArgs @("shell", "pm", "path", $PackageName) -TimeoutSec 20 -IgnoreExitCode
    $packageWasInstalled = $preInstallPackage.ExitCode -eq 0 -and $preInstallPackage.Output -match '(?m)^package:'
    if (-not $packageWasInstalled -and -not $Install) {
        throw "Package $PackageName is not installed. Pass -Install with a valid APK."
    }
    Save-OriginalPermissionStates -SdkInt $sdkInt -PackageWasInstalled $packageWasInstalled

    if ($Install) {
        if (-not (Test-Path $ApkPath)) { throw "APK not found: $ApkPath" }
        $installRes = Invoke-Adb -CommandArgs @("install", "-r", "-d", $ApkPath) -TimeoutSec 180 -IgnoreExitCode
        if ($installRes.ExitCode -ne 0 -and $installRes.Output -notmatch "Success") {
            throw "Installation failed: $($installRes.Output)"
        }
    }

    $postInstallPackage = Invoke-Adb -CommandArgs @("shell", "pm", "path", $PackageName) -TimeoutSec 20 -IgnoreExitCode
    if ($postInstallPackage.ExitCode -ne 0 -or $postInstallPackage.Output -notmatch '(?m)^package:') {
        throw "Package $PackageName is unavailable after install preflight."
    }
    # Only permissions declared by NeoProCamX are primed. MediaStore queries run as shell and do not
    # justify READ_MEDIA_*, legacy storage grants, or MANAGE_EXTERNAL_STORAGE app-ops.
    Grant-HarnessRuntimePermissions

    Invoke-WakeDeviceForRecording
    Invoke-Adb -CommandArgs @("shell", "am", "force-stop", $PackageName) -TimeoutSec 30 -IgnoreExitCode | Out-Null
    Invoke-Adb -CommandArgs @("shell", "am", "start", "-W", "-n", "$PackageName/.ui.MainActivity") -TimeoutSec 60 | Out-Null
    Start-Sleep -Seconds 5

    foreach ($row in $rows) {
        if (-not $row.LiveCaptureSupported) {
            $result = New-NonExecutedRowResult -Row $row -Status "SKIP" -Reason $row.SkipReason
            $result | Add-Member -NotePropertyName LogPath -NotePropertyValue ""
            $result | Add-Member -NotePropertyName MediaStorePath -NotePropertyValue ""
            $result | Add-Member -NotePropertyName CrashPath -NotePropertyValue ""
            [void]$summary.Add($result)
            Write-Host "[Phase6] $($row.Id) => SKIP ($($row.SkipReason))"
            continue
        }

        Write-Host "[Phase6] Recording $($row.Id) ($($row.ExpectedDelivery) $($row.Resolution)@$($row.Fps))"
        Invoke-WakeDeviceForRecording
        Invoke-Adb -CommandArgs @("shell", "am", "start", "-n", "$PackageName/.ui.MainActivity") -IgnoreExitCode | Out-Null
        Start-Sleep -Seconds 2

        $deviceTimeRaw = (Invoke-AdbShell -Command "date +%m-%d\ %H:%M:%S.000" -TimeoutSec 10 -IgnoreExitCode).Output.Trim()
        $deviceTime = ""
        if ($deviceTimeRaw -match '(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})') {
            $deviceTime = $Matches[1]
        }

        $recordCommand = Invoke-RecordCommand -Row $row
        if ($recordCommand.TimedOut -or $recordCommand.ExitCode -ne 0 -or
            $recordCommand.Output -match '(?i)securityexception|permission denial|unable to resolve|not found') {
            $reason = "record_clip command failed: $((($recordCommand.Output -split "`r?`n") | Select-Object -First 2) -join ' ')"
            $result = New-NonExecutedRowResult -Row $row -Status "FAIL" -Reason $reason
            [void]$summary.Add($result)
            Write-Host "[Phase6] $($row.Id) => FAIL ($reason)" -ForegroundColor Red
            break
        }

        $script:RecordingCommandOutstanding = $true
        $waitResult = Wait-ForRecordingSavedLog -Row $row -DeviceTime $deviceTime
        $logText = $waitResult.LogText
        $mediaText = Get-MediaStoreMetadata -Row $row
        $crashText = (Invoke-Adb -CommandArgs @("logcat", "-b", "crash", "-d", "-v", "time") -TimeoutSec 30 -IgnoreExitCode).Output

        $logPath = Join-Path $OutputDir "$($row.Id).logcat.txt"
        $mediaPath = Join-Path $OutputDir "$($row.Id).mediastore.txt"
        $crashPath = Join-Path $OutputDir "$($row.Id).crash.txt"
        Set-Content -Path $logPath -Value $logText -Encoding UTF8
        Set-Content -Path $mediaPath -Value $mediaText -Encoding UTF8
        Set-Content -Path $crashPath -Value $crashText -Encoding UTF8

        $result = Test-RowEvidence -Row $row -LogText $logText -MediaText $mediaText
        if ($waitResult.Status -ne "PUBLISHED") {
            $result.Status = "FAIL"
            $result.Failures = (@($result.Failures, "$($waitResult.Status): $($waitResult.Details)") |
                Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join " | "
        } else {
            $script:RecordingCommandOutstanding = $false
        }
        $result | Add-Member -NotePropertyName LogPath -NotePropertyValue $logPath
        $result | Add-Member -NotePropertyName MediaStorePath -NotePropertyValue $mediaPath
        $result | Add-Member -NotePropertyName CrashPath -NotePropertyValue $crashPath
        [void]$summary.Add($result)
        Write-Host "[Phase6] $($row.Id) => $($result.Status)"

        if ($waitResult.Status -ne "PUBLISHED") {
            Write-Host "[Phase6] Aborting remaining live rows to prevent overlap with an unresolved recording/conversion." -ForegroundColor Red
            break
        }
    }
} finally {
    try {
        Invoke-Cleanup
    } catch {
        $cleanupFailure = $_.Exception.Message
        Write-Warning "Phase 6 cleanup failed: $cleanupFailure"
    }
}

if (-not [string]::IsNullOrWhiteSpace($cleanupFailure)) {
    $cleanupRow = [pscustomobject]@{ Id = "cleanup"; Label = "phase6_${effectiveRunId}_cleanup"; ExpectedDelivery = ""; ExpectedTransfer = "" }
    [void]$summary.Add((New-NonExecutedRowResult -Row $cleanupRow -Status "FAIL" -Reason $cleanupFailure))
}

$csvPath = Join-Path $OutputDir "phase6-recording-matrix-summary.csv"
$jsonPath = Join-Path $OutputDir "phase6-recording-matrix-summary.json"
$summary | Export-Csv -NoTypeInformation -Path $csvPath
$summary | ConvertTo-Json -Depth 6 | Set-Content -Path $jsonPath -Encoding UTF8

$failCount = @($summary | Where-Object { $_.Status -eq "FAIL" }).Count
$warnCount = @($summary | Where-Object { $_.Status -eq "WARN" }).Count
$passCount = @($summary | Where-Object { $_.Status -eq "PASS" }).Count
$skipCount = @($summary | Where-Object { $_.Status -eq "SKIP" }).Count
Write-Host "[Phase6] Summary PASS=$passCount WARN=$warnCount SKIP=$skipCount FAIL=$failCount"
Write-Host "[Phase6] CSV: $csvPath"
Write-Host "[Phase6] JSON: $jsonPath"

if ($failCount -gt 0) {
    exit 1
}
