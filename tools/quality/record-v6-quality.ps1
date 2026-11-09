param(
    [string]$AdbPath = "adb",
    [string]$Serial = "",
    [string]$PackageName = "com.neoproc.neoproccamx",
    [string]$OutputDir = "qa-artifacts\v6-quality-evidence",
    [int]$DurationMs = 1500,
    [int]$PostRecordWaitSec = 28,
    [string[]]$IncludeRows = @(),
    [int]$MaxRows = 0,
    [switch]$DryRun
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
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path $candidate)) { return $candidate }
    }
    throw "Unable to locate adb. Pass -AdbPath explicitly or install Android platform-tools."
}

function Resolve-FfprobePath {
    $cmd = Get-Command "ffprobe" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cmd -and $cmd.Source -and (Test-Path $cmd.Source)) { return $cmd.Source }
    if ($cmd -and $cmd.Path -and (Test-Path $cmd.Path)) { return $cmd.Path }
    $repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
    $bundledCandidates = @(
        (Join-Path $repoRoot "tools\ffmpeg\ffmpeg-8.1.1-essentials_build\bin\ffprobe.exe"),
        (Join-Path $repoRoot "tools\ffmpeg\bin\ffprobe.exe")
    )
    foreach ($candidate in $bundledCandidates) {
        if (Test-Path $candidate) { return $candidate }
    }
    return ""
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

function Invoke-ProcessChecked {
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
    Invoke-ProcessChecked -FilePath $script:ResolvedAdbPath -CommandArgs (Get-AdbArgs -CommandArgs $CommandArgs) -TimeoutSec $TimeoutSec -IgnoreExitCode:$IgnoreExitCode
}

function ConvertTo-AdbShellLiteral {
    param([string]$Value)
    if ($Value -match '^[A-Za-z0-9_./:@%+=,-]+$') { return $Value }
    return "'" + ($Value -replace "'", "'\\''") + "'"
}

function Join-AdbShellArgs {
    param([string[]]$ShellArgs)
    return (($ShellArgs | ForEach-Object { ConvertTo-AdbShellLiteral -Value $_ }) -join " ")
}

function Invoke-AdbShell {
    param([string]$Command, [int]$TimeoutSec = 60, [switch]$IgnoreExitCode)
    Invoke-Adb -CommandArgs @("shell", $Command) -TimeoutSec $TimeoutSec -IgnoreExitCode:$IgnoreExitCode
}

function Start-AppForeground {
    Invoke-Adb -CommandArgs @("shell", "am", "start", "-W", "-n", "$PackageName/.ui.MainActivity") -TimeoutSec 60 | Out-Null
    Start-Sleep -Milliseconds 1800
}

function Get-AppProcessId {
    $result = Invoke-AdbShell -Command "pidof -s $PackageName" -TimeoutSec 20 -IgnoreExitCode
    $appPid = $result.Output.Trim()
    if ($appPid -match "^\d+$") { return $appPid }
    return ""
}

function Get-AppLogcat {
    $appPid = Get-AppProcessId
    if ([string]::IsNullOrWhiteSpace($appPid)) {
        return (Invoke-Adb -CommandArgs @("logcat", "-d", "-v", "time") -TimeoutSec 60 -IgnoreExitCode).Output
    }
    return (Invoke-Adb -CommandArgs @("logcat", "-d", "-v", "time", "--pid", $appPid) -TimeoutSec 60 -IgnoreExitCode).Output
}

function Get-DeviceSerials {
    $devices = Invoke-ProcessChecked -FilePath $script:ResolvedAdbPath -CommandArgs @("devices") -TimeoutSec 20
    return ($devices.StdOut -split "`r?`n") |
        Where-Object { $_ -match "^\S+\s+device(\s|$)" } |
        ForEach-Object { ($_ -split "\s+")[0] }
}

function New-QualityRow {
    param(
        [string]$Id,
        [string]$Preset,
        [string]$VideoMode,
        [bool]$Raw,
        [bool]$Log,
        [string]$HdrMode,
        [string]$ColorSpace,
        [string]$Resolution,
        [int]$Fps,
        [string]$ColorProfile,
        [string]$ExpectedMode,
        [string]$ExpectedPrimaries,
        [string]$ExpectedTransfer,
        [int]$ExpectedBitDepth
    )
    [pscustomobject]@{
        Id = $Id
        Label = "v6_$Id"
        Preset = $Preset
        Aspect = "16:9"
        VideoMode = $VideoMode
        Raw = $Raw
        Log = $Log
        HdrMode = $HdrMode
        ColorSpace = $ColorSpace
        Resolution = $Resolution
        Fps = $Fps
        ColorProfile = $ColorProfile
        ExpectedMode = $ExpectedMode
        ExpectedPrimaries = $ExpectedPrimaries
        ExpectedTransfer = $ExpectedTransfer
        ExpectedBitDepth = $ExpectedBitDepth
    }
}

function Get-QualityRows {
    $targets = @(
        @{ Suffix = "1920x1080_30fps"; Resolution = "1920x1080"; Fps = 30 },
        @{ Suffix = "3840x2160_30fps"; Resolution = "3840x2160"; Fps = 30 },
        @{ Suffix = "3840x2160_60fps"; Resolution = "3840x2160"; Fps = 60 }
    )
    $profiles = @(
        @{ Prefix = "normal_sdr"; Preset = "VIDEO"; Mode = "PRO"; Raw = $false; Log = $false; Hdr = "OFF"; Color = "Rec.709"; Profile = "Natural"; Expected = "NORMAL"; Primaries = "bt709"; Transfer = "bt709"; Depth = 8 },
        @{ Prefix = "pro_sdr"; Preset = "PRO"; Mode = "PRO"; Raw = $false; Log = $false; Hdr = "OFF"; Color = "Rec.709"; Profile = "Natural"; Expected = "PRO"; Primaries = "bt709"; Transfer = "bt709"; Depth = 8 },
        @{ Prefix = "ndr10_plus"; Preset = "HDR"; Mode = "PRO"; Raw = $false; Log = $false; Hdr = "NDR10+"; Color = "Rec.2020-NDR10+"; Profile = "NDR10+"; Expected = "HDR"; Primaries = "bt2020"; Transfer = "smpte2084"; Depth = 10 },
        @{ Prefix = "nivukx_vision"; Preset = "HDR"; Mode = "PRO"; Raw = $false; Log = $false; Hdr = "NIVUKX Vision"; Color = "Rec.2020-NIVUKX"; Profile = "NIVUKX Vision"; Expected = "HDR"; Primaries = "bt2020"; Transfer = "smpte2084"; Depth = 10 },
        @{ Prefix = "n_log_cinema"; Preset = "LOG_VIDEO"; Mode = "LOG"; Raw = $false; Log = $true; Hdr = "OFF"; Color = "Rec.2020-N-Log"; Profile = "N-Log Cinema"; Expected = "LOG"; Primaries = "bt2020"; Transfer = "bt709"; Depth = 10 }
    )
    $rows = New-Object System.Collections.Generic.List[object]
    foreach ($profile in $profiles) {
        foreach ($target in $targets) {
            [void]$rows.Add((New-QualityRow `
                -Id "$($profile.Prefix)_$($target.Suffix)" `
                -Preset $profile.Preset `
                -VideoMode $profile.Mode `
                -Raw $profile.Raw `
                -Log $profile.Log `
                -HdrMode $profile.Hdr `
                -ColorSpace $profile.Color `
                -Resolution $target.Resolution `
                -Fps $target.Fps `
                -ColorProfile $profile.Profile `
                -ExpectedMode $profile.Expected `
                -ExpectedPrimaries $profile.Primaries `
                -ExpectedTransfer $profile.Transfer `
                -ExpectedBitDepth $profile.Depth))
        }
    }
    $selected = @($rows.ToArray())
    if ($IncludeRows.Count -gt 0) {
        $include = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        foreach ($rowId in $IncludeRows) { [void]$include.Add($rowId) }
        $selected = @($selected | Where-Object { $include.Contains($_.Id) })
    }
    if ($MaxRows -gt 0) { $selected = @($selected | Select-Object -First $MaxRows) }
    return @($selected)
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
        "--es", "debug_video_mode", $Row.VideoMode
    )
    Invoke-AdbShell -Command $shellCommand -TimeoutSec 30
}

function Wait-ForRecordingEvidence {
    param([object]$Row)
    $deadline = (Get-Date).AddSeconds($PostRecordWaitSec)
    $latest = ""
    while ((Get-Date) -lt $deadline) {
        $latest = Get-AppLogcat
        if ($latest -match [regex]::Escape($Row.Label) -and $latest -match "Recording saved ->") { return $latest }
        if ($latest -match "FATAL EXCEPTION|AndroidRuntime|record_clip_start_failed") { return $latest }
        Start-Sleep -Seconds 2
    }
    return $latest
}

function Get-MediaStoreRow {
    param([object]$Row)
    $projection = "_id:_display_name:mime_type:width:height:duration:bitrate:capture_framerate:color_standard:color_transfer:color_range:relative_path"
    $where = "_display_name LIKE '%$($Row.Label)%'"
    $command = "content query --uri content://media/external/video/media --projection $projection --where `"$where`" --sort `"date_added DESC`""
    return (Invoke-AdbShell -Command $command -TimeoutSec 60 -IgnoreExitCode).Output
}

function Get-MediaValue {
    param([string]$ContentQueryOutput, [string]$Key)
    foreach ($part in ($ContentQueryOutput -split ", ")) {
        $clean = ($part -replace "^Row:\s*\d+\s+", "").Trim()
        $prefix = "$Key="
        if ($clean.StartsWith($prefix, [StringComparison]::Ordinal)) {
            return $clean.Substring($prefix.Length).Trim()
        }
    }
    return ""
}

function Export-MediaContent {
    param([string]$MediaId, [string]$OutputPath)
    if ([string]::IsNullOrWhiteSpace($MediaId)) { return $false }
    $uri = "content://media/external/video/media/$MediaId"
    $parent = Split-Path -Parent $OutputPath
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        [System.IO.Directory]::CreateDirectory((Resolve-Path -LiteralPath $parent).Path) | Out-Null
    }
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $script:ResolvedAdbPath
    $psi.Arguments = Join-CommandArgs -CommandArgs (Get-AdbArgs -CommandArgs @("exec-out", "content", "read", "--uri", $uri))
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $proc = [System.Diagnostics.Process]::new()
    $proc.StartInfo = $psi
    $stream = [System.IO.File]::Create($OutputPath)
    try {
        [void]$proc.Start()
        $stderrTask = $proc.StandardError.ReadToEndAsync()
        $copyTask = $proc.StandardOutput.BaseStream.CopyToAsync($stream)
        $timedOut = -not $proc.WaitForExit(120000)
        if ($timedOut) {
            try { $proc.Kill($true) } catch {}
            $null = $proc.WaitForExit()
        }
        $copyTask.GetAwaiter().GetResult()
        $stderrTask.GetAwaiter().GetResult() | Out-Null
    } finally {
        $stream.Dispose()
        $proc.Dispose()
    }
    return (Test-Path $OutputPath) -and ((Get-Item $OutputPath).Length -gt 0)
}

function Invoke-Ffprobe {
    param([string]$VideoPath, [string]$OutputPath)
    if ([string]::IsNullOrWhiteSpace($script:FfprobeExecutablePath) -or
        -not (Test-Path $script:FfprobeExecutablePath) -or
        -not (Test-Path $VideoPath)
    ) {
        return ""
    }
    $args = @(
        "-v", "error",
        "-select_streams", "v:0",
        "-show_entries", "stream=codec_name,pix_fmt,bits_per_raw_sample,color_space,color_transfer,color_primaries,width,height,avg_frame_rate,bit_rate",
        "-of", "default=noprint_wrappers=1",
        $VideoPath
    )
    $result = Invoke-ProcessChecked -FilePath $script:FfprobeExecutablePath -CommandArgs $args -TimeoutSec 120 -IgnoreExitCode
    Set-Content -Path $OutputPath -Value $result.Output -Encoding UTF8
    return $result.Output
}

function Test-V6Row {
    param([object]$Row, [string]$LogText, [string]$MediaText, [string]$FfprobeText)
    $failures = New-Object System.Collections.Generic.List[string]
    $notProven = New-Object System.Collections.Generic.List[string]
    $warnings = New-Object System.Collections.Generic.List[string]

    if ($LogText -notmatch [regex]::Escape($Row.Label)) { [void]$notProven.Add("missing validation label in logcat") }
    if ($LogText -notmatch "Recording saved ->") { [void]$failures.Add("recording did not report saved output") }
    if ($LogText -match "FATAL EXCEPTION|AndroidRuntime|CameraAccessException|MediaCodec.*CodecException") { [void]$failures.Add("blocking runtime error in logcat") }
    if ($LogText -notmatch "Recording parity") { [void]$notProven.Add("missing preview-recording parity log") }
    $appliedDebugFps = ""
    $appliedDebugMatch = [regex]::Match($LogText, "\bappliedDebugConfig\b.*\bfps=(\d+)\b")
    if ($appliedDebugMatch.Success) {
        $appliedDebugFps = $appliedDebugMatch.Groups[1].Value
        if ([int]$appliedDebugFps -ne [int]$Row.Fps) {
            [void]$failures.Add("applied fps $appliedDebugFps does not match requested $($Row.Fps)")
        }
    } else {
        [void]$notProven.Add("missing appliedDebugConfig fps log")
    }
    $parityFpsPattern = "\|$([regex]::Escape($Row.Resolution))\|$($Row.Fps)\|"
    if ($LogText -notmatch $parityFpsPattern) {
        [void]$failures.Add("recording parity signature does not prove requested $($Row.Resolution)@$($Row.Fps)fps")
    }

    $mediaId = Get-MediaValue -ContentQueryOutput $MediaText -Key "_id"
    $displayName = Get-MediaValue -ContentQueryOutput $MediaText -Key "_display_name"
    $width = Get-MediaValue -ContentQueryOutput $MediaText -Key "width"
    $height = Get-MediaValue -ContentQueryOutput $MediaText -Key "height"
    $fps = Get-MediaValue -ContentQueryOutput $MediaText -Key "capture_framerate"
    $transfer = Get-MediaValue -ContentQueryOutput $MediaText -Key "color_transfer"
    $standard = Get-MediaValue -ContentQueryOutput $MediaText -Key "color_standard"
    if ([string]::IsNullOrWhiteSpace($mediaId)) { [void]$notProven.Add("missing MediaStore row") }
    if (-not [string]::IsNullOrWhiteSpace($displayName) -and $displayName -notlike "*$($Row.Label)*") { [void]$failures.Add("display name does not contain validation label") }
    if ($width -and $height -and "${width}x${height}" -ne $Row.Resolution) { [void]$warnings.Add("MediaStore resolution ${width}x${height}, requested $($Row.Resolution)") }
    if ($fps) {
        $fpsValue = 0.0
        if ([double]::TryParse($fps, [ref]$fpsValue) -and [math]::Abs($fpsValue - [double]$Row.Fps) -gt 0.5) {
            [void]$warnings.Add("MediaStore fps $fpsValue, requested $($Row.Fps)")
        }
    }

    if ([string]::IsNullOrWhiteSpace($FfprobeText)) {
        [void]$notProven.Add("ffprobe output unavailable")
    } else {
        if ($FfprobeText -notmatch "codec_name=") { [void]$notProven.Add("ffprobe missing codec_name") }
        if ($Row.ExpectedBitDepth -ge 10 -and $FfprobeText -notmatch "pix_fmt=.*(10|p010)|bits_per_raw_sample=10") {
            [void]$failures.Add("ffprobe did not prove 10-bit video")
        }
        if ($Row.ExpectedMode -eq "HDR" -and $FfprobeText -notmatch "color_primaries=.*2020") { [void]$failures.Add("HDR/NDR/NIVUKX missing BT.2020 primaries") }
        if ($Row.ExpectedMode -eq "HDR" -and $FfprobeText -notmatch "color_transfer=.*(smpte2084|arib-std-b67|hlg)") { [void]$failures.Add("HDR/NDR/NIVUKX missing PQ/HLG transfer") }
        if ($Row.ExpectedMode -in @("NORMAL", "PRO", "LOG") -and $FfprobeText -match "color_transfer=.*(smpte2084|arib-std-b67|hlg)") {
            [void]$failures.Add("$($Row.ExpectedMode) was HDR tagged")
        }
        if ($Row.ExpectedMode -eq "LOG" -and $FfprobeText -notmatch "color_primaries=.*2020") { [void]$failures.Add("N-Log missing wide-gamut primaries") }
    }

    $status = if ($failures.Count -gt 0) {
        "FAIL"
    } elseif ($notProven.Count -gt 0) {
        "NOT_PROVEN"
    } elseif ($warnings.Count -gt 0) {
        "WARN"
    } else {
        "PASS"
    }
    return [pscustomobject]@{
        RowId = $Row.Id
        Label = $Row.Label
        Status = $status
        ExpectedMode = $Row.ExpectedMode
        ExpectedBitDepth = $Row.ExpectedBitDepth
        ExpectedTransfer = $Row.ExpectedTransfer
        DisplayName = $displayName
        MediaStoreId = $mediaId
        MediaStoreWidth = $width
        MediaStoreHeight = $height
        MediaStoreFps = $fps
        AppliedDebugFps = $appliedDebugFps
        MediaStoreColorStandard = $standard
        MediaStoreColorTransfer = $transfer
        Failures = ($failures -join " | ")
        NotProven = ($notProven -join " | ")
        Warnings = ($warnings -join " | ")
    }
}

$script:ResolvedAdbPath = Resolve-AdbPath -RequestedPath $AdbPath
$script:FfprobeExecutablePath = Resolve-FfprobePath
$rows = @(Get-QualityRows)
if ($DryRun) {
    $rows | Select-Object Id, Label, Preset, VideoMode, Raw, Log, HdrMode, ColorSpace, Resolution, Fps, ExpectedMode, ExpectedBitDepth
    return
}
if ($rows.Count -eq 0) { throw "No V6 quality rows selected." }
if ([string]::IsNullOrWhiteSpace($Serial)) {
    $serials = @(Get-DeviceSerials)
    if ($serials.Count -ne 1) { throw "Expected exactly one adb device, found $($serials.Count). Pass -Serial." }
    $Serial = $serials[0]
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
Invoke-Adb -CommandArgs @("shell", "am", "force-stop", $PackageName) -TimeoutSec 30 -IgnoreExitCode | Out-Null
Start-AppForeground
Start-Sleep -Seconds 5

$summary = New-Object System.Collections.Generic.List[object]
foreach ($row in $rows) {
    Write-Host "[V6] Recording $($row.Id)"
    Start-AppForeground
    Invoke-Adb -CommandArgs @("logcat", "-c") -TimeoutSec 30 -IgnoreExitCode | Out-Null
    Invoke-RecordCommand -Row $row | Out-Null
    $logText = Wait-ForRecordingEvidence -Row $row
    $mediaText = Get-MediaStoreRow -Row $row
    $mediaId = Get-MediaValue -ContentQueryOutput $mediaText -Key "_id"
    $localVideo = Join-Path $OutputDir "$($row.Id).mov"
    $ffprobeOutputPath = Join-Path $OutputDir "$($row.Id).ffprobe.txt"
    $pulled = Export-MediaContent -MediaId $mediaId -OutputPath $localVideo
    $ffprobeText = if ($pulled) { Invoke-Ffprobe -VideoPath $localVideo -OutputPath $ffprobeOutputPath } else { "" }

    $logPath = Join-Path $OutputDir "$($row.Id).logcat.txt"
    $mediaPath = Join-Path $OutputDir "$($row.Id).mediastore.txt"
    Set-Content -Path $logPath -Value $logText -Encoding UTF8
    Set-Content -Path $mediaPath -Value $mediaText -Encoding UTF8

    $result = Test-V6Row -Row $row -LogText $logText -MediaText $mediaText -FfprobeText $ffprobeText
    $result | Add-Member -NotePropertyName LogPath -NotePropertyValue $logPath
    $result | Add-Member -NotePropertyName MediaStorePath -NotePropertyValue $mediaPath
    $result | Add-Member -NotePropertyName LocalVideoPath -NotePropertyValue ($(if ($pulled) { $localVideo } else { "" }))
    $result | Add-Member -NotePropertyName FfprobePath -NotePropertyValue ($(if ($ffprobeText) { $ffprobeOutputPath } else { "" }))
    [void]$summary.Add($result)
    Write-Host "[V6] $($row.Id) => $($result.Status)"
}

$csvPath = Join-Path $OutputDir "quality-report.csv"
$jsonPath = Join-Path $OutputDir "quality-report.json"
$txtPath = Join-Path $OutputDir "quality-report.txt"
$summary | Export-Csv -NoTypeInformation -Path $csvPath
$summary | ConvertTo-Json -Depth 6 | Set-Content -Path $jsonPath -Encoding UTF8
$lines = New-Object System.Collections.Generic.List[string]
[void]$lines.Add("NeoProCamX V6 Quality Evidence Report")
[void]$lines.Add("GeneratedAt=$((Get-Date).ToString('o'))")
[void]$lines.Add("AdbPath=$script:ResolvedAdbPath")
[void]$lines.Add("FfprobePath=$script:FfprobeExecutablePath")
foreach ($item in $summary) {
    [void]$lines.Add("$($item.Status) $($item.RowId) failures=[$($item.Failures)] notProven=[$($item.NotProven)] warnings=[$($item.Warnings)]")
}
Set-Content -Path $txtPath -Value $lines -Encoding UTF8

$failCount = @($summary | Where-Object { $_.Status -eq "FAIL" }).Count
$notProvenCount = @($summary | Where-Object { $_.Status -eq "NOT_PROVEN" }).Count
$warnCount = @($summary | Where-Object { $_.Status -eq "WARN" }).Count
$passCount = @($summary | Where-Object { $_.Status -eq "PASS" }).Count
Write-Host "[V6] Summary PASS=$passCount WARN=$warnCount NOT_PROVEN=$notProvenCount FAIL=$failCount"
Write-Host "[V6] Report: $txtPath"
if ($failCount -gt 0) { exit 1 }
if ($notProvenCount -gt 0) { exit 2 }
