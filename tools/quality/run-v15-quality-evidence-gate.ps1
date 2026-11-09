param(
    [string]$AdbPath = "adb",
    [string]$Serial = "",
    [string]$PackageName = "com.neoproc.neoproccamx",
    [string]$OutputDir = "qa-artifacts\v15-quality-evidence",
    [int]$DurationMs = 2200,
    [int]$PostRecordWaitSec = 32,
    [string[]]$IncludeRows = @(),
    [int]$MaxRows = 0,
    [string]$VideoDetailProfile = "",
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Normalize-IncludeRows {
    param([string[]]$Rows)
    $normalized = New-Object System.Collections.Generic.List[string]
    foreach ($row in $Rows) {
        if ([string]::IsNullOrWhiteSpace($row)) { continue }
        foreach ($part in ($row -split ",")) {
            $trimmed = $part.Trim()
            if (-not [string]::IsNullOrWhiteSpace($trimmed)) {
                [void]$normalized.Add($trimmed)
            }
        }
    }
    return @($normalized.ToArray())
}

$IncludeRows = @(Normalize-IncludeRows -Rows $IncludeRows)

function Resolve-AdbPath {
    param([string]$RequestedPath)
    if (-not [string]::IsNullOrWhiteSpace($RequestedPath) -and $RequestedPath -notin @("adb", "adb.exe")) {
        if (Test-Path $RequestedPath) { return $RequestedPath }
        throw "Requested adb path does not exist: $RequestedPath"
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

function Resolve-ToolPath {
    param([string]$Name)
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cmd -and $cmd.Source -and (Test-Path $cmd.Source)) { return $cmd.Source }
    if ($cmd -and $cmd.Path -and (Test-Path $cmd.Path)) { return $cmd.Path }
    if ($Name -ieq "mediainfo" -and -not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        $wingetPackagesRoot = Join-Path $env:LOCALAPPDATA "Microsoft\WinGet\Packages"
        if (Test-Path $wingetPackagesRoot) {
            $wingetMediaInfo = Get-ChildItem `
                -Path $wingetPackagesRoot `
                -Directory `
                -Filter "MediaArea.MediaInfo*" `
                -ErrorAction SilentlyContinue |
                ForEach-Object { Join-Path $_.FullName "MediaInfo.exe" } |
                Where-Object { Test-Path $_ } |
                Select-Object -First 1
            if (-not [string]::IsNullOrWhiteSpace($wingetMediaInfo)) { return $wingetMediaInfo }
        }
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

function Get-DeviceSerials {
    $devices = Invoke-ProcessChecked -FilePath $script:ResolvedAdbPath -CommandArgs @("devices") -TimeoutSec 20
    return ($devices.StdOut -split "`r?`n") |
        Where-Object { $_ -match "^\S+\s+device(\s|$)" } |
        ForEach-Object { ($_ -split "\s+")[0] }
}

function Ensure-DeviceInteractive {
    Invoke-Adb -CommandArgs @("shell", "svc", "power", "stayon", "usb") -TimeoutSec 20 -IgnoreExitCode | Out-Null
    Invoke-Adb -CommandArgs @("shell", "input", "keyevent", "KEYCODE_WAKEUP") -TimeoutSec 20 -IgnoreExitCode | Out-Null
    Invoke-Adb -CommandArgs @("shell", "wm", "dismiss-keyguard") -TimeoutSec 20 -IgnoreExitCode | Out-Null
    Invoke-Adb -CommandArgs @("shell", "cmd", "statusbar", "collapse") -TimeoutSec 20 -IgnoreExitCode | Out-Null
}

function Test-AppForeground {
    $windowDump = (Invoke-Adb -CommandArgs @("shell", "dumpsys", "window", "windows") -TimeoutSec 30 -IgnoreExitCode).Output
    if ($windowDump -match ([regex]::Escape($PackageName) + "/(\.ui\.MainActivity|" + [regex]::Escape($PackageName) + "\.ui\.MainActivity)")) {
        return $true
    }
    if ($windowDump -match "mCurrentFocus|mFocusedApp|topResumedActivity" -and
        $windowDump -match [regex]::Escape($PackageName) -and
        $windowDump -match "MainActivity"
    ) {
        return $true
    }
    return $false
}

function Wait-AppForeground {
    param([int]$TimeoutSec = 15)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Test-AppForeground) { return $true }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Start-AppForeground {
    Ensure-DeviceInteractive
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        Invoke-Adb -CommandArgs @("shell", "am", "start", "-W", "-n", "$PackageName/.ui.MainActivity") -TimeoutSec 60 -IgnoreExitCode | Out-Null
        Start-Sleep -Milliseconds 2200
        if (Wait-AppForeground -TimeoutSec 8) { return }
        Ensure-DeviceInteractive
    }
    throw "MainActivity did not reach foreground before DEBUG_COMMAND dispatch."
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

function Start-ContinuousLogcatCapture {
    param([string]$OutputPath, [string[]]$FilterSpecs = @())
    $errorPath = "$OutputPath.err"
    $logcatArgs = @("logcat", "-v", "time")
    if ($FilterSpecs.Count -gt 0) {
        $logcatArgs += $FilterSpecs
    }
    $process = Start-Process `
        -FilePath $script:ResolvedAdbPath `
        -ArgumentList (Join-CommandArgs -CommandArgs (Get-AdbArgs -CommandArgs $logcatArgs)) `
        -RedirectStandardOutput $OutputPath `
        -RedirectStandardError $errorPath `
        -PassThru `
        -WindowStyle Hidden
    return [pscustomobject]@{
        Process = $process
        OutputPath = $OutputPath
        ErrorPath = $errorPath
    }
}

function Stop-ContinuousLogcatCapture {
    param([object]$Capture)
    if ($null -eq $Capture -or $null -eq $Capture.Process) { return }
    try {
        if (-not $Capture.Process.HasExited) {
            $Capture.Process.Kill()
            [void]$Capture.Process.WaitForExit(5000)
        }
    } catch {
        Write-Warning "Unable to stop continuous logcat capture: $($_.Exception.Message)"
    }
}

function New-V15Row {
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
        [string]$ExpectedCodec,
        [string]$ExpectedPrimaries,
        [string]$ExpectedTransfer,
        [string]$ExpectedMatrix,
        [int]$ExpectedBitDepth
    )
    [pscustomobject]@{
        Id = $Id
        Label = "v15_$Id"
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
        ExpectedCodec = $ExpectedCodec
        ExpectedPrimaries = $ExpectedPrimaries
        ExpectedTransfer = $ExpectedTransfer
        ExpectedMatrix = $ExpectedMatrix
        ExpectedBitDepth = $ExpectedBitDepth
        ExpectedRotationDegrees = 0
        AllowedRotationDegrees = @(0, 90, 180, 270)
        ExpectedMirrored = $false
    }
}

function Get-V15Rows {
    $targets = @(
        @{ Suffix = "1920x1080_30fps"; Resolution = "1920x1080"; Fps = 30 },
        @{ Suffix = "3840x2160_30fps"; Resolution = "3840x2160"; Fps = 30 }
    )
    $profiles = @(
        @{ Prefix = "normal_sdr"; Preset = "VIDEO"; Mode = "PRO"; Raw = $false; Log = $false; Hdr = "OFF"; Color = "Rec.709"; Profile = "Natural"; Expected = "NORMAL"; Codec = "hevc"; Primaries = "BT.709"; Transfer = "BT.709/OETF"; Matrix = "BT.709"; Depth = 8 },
        @{ Prefix = "pro_sdr"; Preset = "PRO"; Mode = "PRO"; Raw = $false; Log = $false; Hdr = "OFF"; Color = "Rec.709"; Profile = "Natural"; Expected = "PRO"; Codec = "hevc"; Primaries = "BT.709"; Transfer = "BT.709/OETF"; Matrix = "BT.709"; Depth = 8 },
        @{ Prefix = "ndr10_plus"; Preset = "HDR"; Mode = "PRO"; Raw = $false; Log = $false; Hdr = "NDR10+"; Color = "Rec.2020-NDR10+"; Profile = "NDR10+"; Expected = "HDR"; Codec = "hevc"; Primaries = "BT.2020"; Transfer = "ST2084/PQ"; Matrix = "BT.2020-NCL"; Depth = 10 },
        @{ Prefix = "nivukx_vision"; Preset = "HDR"; Mode = "PRO"; Raw = $false; Log = $false; Hdr = "NIVUKX Vision"; Color = "Rec.2020-NIVUKX"; Profile = "NIVUKX Vision"; Expected = "HDR"; Codec = "hevc"; Primaries = "BT.2020"; Transfer = "ST2084/PQ"; Matrix = "BT.2020-NCL"; Depth = 10 },
        @{ Prefix = "log_nlog"; Preset = "LOG_VIDEO"; Mode = "LOG"; Raw = $false; Log = $true; Hdr = "OFF"; Color = "Rec.2020-N-Log"; Profile = "N-Log Cinema"; Expected = "LOG"; Codec = "hevc"; Primaries = "BT.2020"; Transfer = "BT.709/OETF"; Matrix = "BT.2020-NCL"; Depth = 10 }
    )
    $rows = New-Object System.Collections.Generic.List[object]
    foreach ($profile in $profiles) {
        foreach ($target in $targets) {
            [void]$rows.Add((New-V15Row `
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
                -ExpectedCodec $profile.Codec `
                -ExpectedPrimaries $profile.Primaries `
                -ExpectedTransfer $profile.Transfer `
                -ExpectedMatrix $profile.Matrix `
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
    Start-AppForeground
    $component = "$PackageName/.ui.MainActivity"
    $shellArgs = @(
        "am", "start",
        "-W",
        "-a", "com.neoproc.neoproccamx.DEBUG_COMMAND",
        "-n", $component,
        "-f", "0x34000000",
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
    if (-not [string]::IsNullOrWhiteSpace($VideoDetailProfile)) {
        $shellArgs += @("--es", "debug_video_detail_profile", $VideoDetailProfile)
    }
    $shellCommand = Join-AdbShellArgs -ShellArgs $shellArgs
    Invoke-AdbShell -Command $shellCommand -TimeoutSec 30
}

function Wait-ForRecordingEvidence {
    param([object]$Row)
    $deadline = (Get-Date).AddSeconds($PostRecordWaitSec)
    $latest = ""
    while ((Get-Date) -lt $deadline) {
        $latest = Get-AppLogcat
        if ($latest -match [regex]::Escape($Row.Label) -and $latest -match "Recording saved ->") { return $latest }
        if ($latest -match "FATAL EXCEPTION|AndroidRuntime") { return $latest }
        Start-Sleep -Seconds 2
    }
    return $latest
}

function Get-MediaStoreRow {
    param([object]$Row, [long]$MinimumMediaId = 0)
    $projection = "_id:_display_name:mime_type:width:height:duration:bitrate:capture_framerate:color_standard:color_transfer:color_range:relative_path"
    $where = "_display_name LIKE '%$($Row.Label)%'"
    if ($MinimumMediaId -gt 0L) {
        $where = "$where AND _id > $MinimumMediaId"
    }
    $command = "content query --uri content://media/external/video/media --projection $projection --where `"$where`" --sort `"date_added DESC`""
    return (Invoke-AdbShell -Command $command -TimeoutSec 60 -IgnoreExitCode).Output
}

function Get-LatestMediaIdForRow {
    param([object]$Row)
    $mediaText = Get-MediaStoreRow -Row $Row
    $mediaIdText = Get-MediaValue -ContentQueryOutput $mediaText -Key "_id"
    $mediaId = 0L
    if ([long]::TryParse($mediaIdText, [ref]$mediaId)) { return $mediaId }
    return 0L
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
        "-count_frames",
        "-select_streams", "v:0",
        "-show_entries", "stream=codec_name,pix_fmt,bits_per_raw_sample,bits_per_sample,color_space,color_transfer,color_primaries,width,height,avg_frame_rate,r_frame_rate,bit_rate,nb_read_frames,duration:stream_tags=rotate:stream_side_data=rotation",
        "-of", "default=noprint_wrappers=1",
        $VideoPath
    )
    $result = Invoke-ProcessChecked -FilePath $script:FfprobeExecutablePath -CommandArgs $args -TimeoutSec 120 -IgnoreExitCode
    Set-Content -Path $OutputPath -Value $result.Output -Encoding UTF8
    return $result.Output
}

function Invoke-MediaInfo {
    param([string]$VideoPath, [string]$OutputPath)
    if ([string]::IsNullOrWhiteSpace($script:MediaInfoExecutablePath) -or
        -not (Test-Path $script:MediaInfoExecutablePath) -or
        -not (Test-Path $VideoPath)
    ) {
        return ""
    }
    $result = Invoke-ProcessChecked -FilePath $script:MediaInfoExecutablePath -CommandArgs @("--Output=JSON", $VideoPath) -TimeoutSec 120 -IgnoreExitCode
    Set-Content -Path $OutputPath -Value $result.Output -Encoding UTF8
    return $result.Output
}

function ConvertFrom-FfprobeText {
    param([string]$Text)
    $fields = @{}
    foreach ($line in ($Text -split "`r?`n")) {
        $trimmed = $line.Trim()
        $separator = $trimmed.IndexOf("=")
        if ($separator -le 0) { continue }
        $fields[$trimmed.Substring(0, $separator)] = $trimmed.Substring($separator + 1)
    }
    return $fields
}

function Normalize-Primaries {
    param([string]$Value)
    $lower = $Value.ToLowerInvariant()
    if ($lower.Contains("2020")) { return "BT.2020" }
    if ($lower.Contains("p3")) { return "Display P3" }
    return "BT.709"
}

function Normalize-Transfer {
    param([string]$Value)
    $lower = $Value.ToLowerInvariant()
    if ($lower.Contains("2084") -or $lower.Contains("pq") -or $lower.Contains("smpte")) { return "ST2084/PQ" }
    if ($lower.Contains("hlg") -or $lower.Contains("arib-std-b67")) { return "HLG" }
    if ($lower.Contains("log")) { return "LOG" }
    return "BT.709/OETF"
}

function Normalize-Matrix {
    param([string]$Value)
    $lower = $Value.ToLowerInvariant()
    if ($lower.Contains("2020")) { return "BT.2020-NCL" }
    return "BT.709"
}

function Resolve-BitDepth {
    param([hashtable]$Fields)
    foreach ($key in @("bits_per_raw_sample", "bits_per_sample")) {
        if ($Fields.ContainsKey($key)) {
            $parsed = 0
            if ([int]::TryParse([string]$Fields[$key], [ref]$parsed) -and $parsed -gt 0) { return $parsed }
        }
    }
    $pix = if ($Fields.ContainsKey("pix_fmt")) { ([string]$Fields["pix_fmt"]).ToLowerInvariant() } else { "" }
    if ($pix.Contains("12")) { return 12 }
    if ($pix.Contains("10") -or $pix.Contains("p010")) { return 10 }
    return 8
}

function Parse-Fps {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -eq "0/0") { return 0.0 }
    $parts = $Value.Split("/")
    if ($parts.Count -eq 2) {
        $num = 0.0
        $den = 0.0
        if ([double]::TryParse($parts[0], [ref]$num) -and [double]::TryParse($parts[1], [ref]$den) -and $den -ne 0.0) {
            return $num / $den
        }
        return 0.0
    }
    $fps = 0.0
    if ([double]::TryParse($Value, [ref]$fps)) { return $fps }
    return 0.0
}

function Resolve-FieldValue {
    param([hashtable]$Fields, [string[]]$Keys)
    foreach ($key in $Keys) {
        if ($Fields.ContainsKey($key)) { return [string]$Fields[$key] }
    }
    foreach ($entryKey in $Fields.Keys) {
        $lower = ([string]$entryKey).ToLowerInvariant()
        foreach ($key in $Keys) {
            $needle = $key.ToLowerInvariant()
            if ($lower -eq $needle -or $lower.EndsWith(":$needle") -or $lower.EndsWith(".$needle")) {
                return [string]$Fields[$entryKey]
            }
        }
    }
    return ""
}

function Normalize-RotationDegrees {
    param([string]$Value)
    $clean = $Value.Trim() -replace "\.0$", ""
    $raw = 0
    if (-not [int]::TryParse($clean, [ref]$raw)) { return 0 }
    $normalized = (($raw % 360) + 360) % 360
    if ($normalized -lt 45) { return 0 }
    if ($normalized -lt 135) { return 90 }
    if ($normalized -lt 225) { return 180 }
    if ($normalized -lt 315) { return 270 }
    return 0
}

function Resolve-RotationDegrees {
    param([hashtable]$Fields)
    return Normalize-RotationDegrees -Value (Resolve-FieldValue -Fields $Fields -Keys @("rotation", "rotate"))
}

function Resolve-Mirrored {
    param([hashtable]$Fields)
    $explicit = Resolve-FieldValue -Fields $Fields -Keys @("mirror", "mirrored")
    if ($explicit -match "^(true|1|yes)$") { return $true }
    foreach ($entry in $Fields.GetEnumerator()) {
        $key = ([string]$entry.Key).ToLowerInvariant()
        $value = [string]$entry.Value
        if (($key.Contains("displaymatrix") -or $key.Contains("display_matrix")) -and
            $value.ToLowerInvariant().Contains("mirror")
        ) {
            return $true
        }
    }
    return $false
}

function New-ProbeObservation {
    param([string]$FfprobeText)
    $fields = ConvertFrom-FfprobeText -Text $FfprobeText
    $width = 0
    $height = 0
    $bitrate = 0
    $frames = 0
    $duration = 0.0
    [int]::TryParse([string]$fields["width"], [ref]$width) | Out-Null
    [int]::TryParse([string]$fields["height"], [ref]$height) | Out-Null
    [int]::TryParse([string]$fields["bit_rate"], [ref]$bitrate) | Out-Null
    [int]::TryParse([string]$fields["nb_read_frames"], [ref]$frames) | Out-Null
    [double]::TryParse([string]$fields["duration"], [ref]$duration) | Out-Null
    [pscustomobject]@{
        Codec = if ($fields.ContainsKey("codec_name")) { [string]$fields["codec_name"] } else { "" }
        PixelFormat = if ($fields.ContainsKey("pix_fmt")) { [string]$fields["pix_fmt"] } else { "" }
        Width = $width
        Height = $height
        Fps = Parse-Fps -Value ($(if ($fields.ContainsKey("avg_frame_rate")) { [string]$fields["avg_frame_rate"] } else { [string]$fields["r_frame_rate"] }))
        BitrateBps = $bitrate
        BitDepth = Resolve-BitDepth -Fields $fields
        Primaries = Normalize-Primaries -Value ($(if ($fields.ContainsKey("color_primaries")) { [string]$fields["color_primaries"] } else { "" }))
        Transfer = Normalize-Transfer -Value ($(if ($fields.ContainsKey("color_transfer")) { [string]$fields["color_transfer"] } else { "" }))
        Matrix = Normalize-Matrix -Value ($(if ($fields.ContainsKey("color_space")) { [string]$fields["color_space"] } else { "" }))
        FrameCount = $frames
        DurationSeconds = $duration
        RotationDegrees = Resolve-RotationDegrees -Fields $fields
        Mirrored = Resolve-Mirrored -Fields $fields
    }
}

function Resolve-LogKeyValue {
    param([string]$Text, [string]$Key)
    if ([string]::IsNullOrWhiteSpace($Text) -or [string]::IsNullOrWhiteSpace($Key)) { return "" }
    $pattern = "(?<![A-Za-z0-9_])" + [regex]::Escape($Key) + "=([^\s,;]+)"
    $matches = [regex]::Matches($Text, $pattern)
    if ($matches.Count -le 0) { return "" }
    return $matches[$matches.Count - 1].Groups[1].Value.Trim()
}

function Test-FalseMetadataFlag {
    param(
        [string]$Key,
        [string]$Value,
        [bool]$Required,
        [System.Collections.Generic.List[string]]$Failures,
        [System.Collections.Generic.List[string]]$NotProven
    )
    if ([string]::IsNullOrWhiteSpace($Value)) {
        if ($Required) { [void]$NotProven.Add("missing $Key=false proprietary metadata proof") }
        return $false
    }
    if ($Value -ne "false") {
        [void]$Failures.Add("$Key must remain false but was $Value")
        return $false
    }
    return $true
}

function Test-V15Row {
    param(
        [object]$Row,
        [string]$LogText,
        [string]$DeviceLogText,
        [string]$DeviceLogPath,
        [string]$MediaText,
        [string]$FfprobeText,
        [string]$MediaInfoText,
        [string]$LocalVideo
    )
    $failures = New-Object System.Collections.Generic.List[string]
    $notProven = New-Object System.Collections.Generic.List[string]
    $warnings = New-Object System.Collections.Generic.List[string]

    $localBytes = if ((Test-Path $LocalVideo)) { (Get-Item $LocalVideo).Length } else { 0L }
    $deviceLogValidated =
        -not [string]::IsNullOrWhiteSpace($DeviceLogPath) -and
        (Test-Path $DeviceLogPath) -and
        -not [string]::IsNullOrWhiteSpace($DeviceLogText) -and
        $DeviceLogText -match [regex]::Escape($Row.Label) -and
        $DeviceLogText -match "Recording saved ->" -and
        $DeviceLogText -match "Quality certificate ->"
    if ($localBytes -le 0L) { [void]$failures.Add("recorded file is missing or empty") }
    if ($LogText -notmatch [regex]::Escape($Row.Label)) { [void]$notProven.Add("missing validation label in adb logcat") }
    if (-not $deviceLogValidated) { [void]$notProven.Add("missing complete per-recording device log proof") }
    if ($LogText -notmatch "Recording saved ->") { [void]$failures.Add("recording did not report saved output") }
    if ($LogText -match "FATAL EXCEPTION|AndroidRuntime|CameraAccessException|MediaCodec.*CodecException") {
        [void]$failures.Add("adb logcat contains fatal, camera, or codec error")
    }
    if ($LogText -match "Dropping oldest queued muxer sample|dropped=\d*[1-9]\d*") {
        [void]$failures.Add("frame-drop evidence found in adb logcat")
    }
    if ($LogText -notmatch "Quality certificate ->") {
        [void]$notProven.Add("app did not log a per-recording quality certificate")
    }
    $proprietaryRow = $Row.ExpectedMode -in @("HDR", "LOG")
    $vendorHdrCertification = Resolve-LogKeyValue -Text $LogText -Key "vendorHdrCertification"
    $officialHdr10PlusMetadataWritten = Resolve-LogKeyValue -Text $LogText -Key "officialHdr10PlusMetadataWritten"
    $vendorCertifiedHdr10Plus = Resolve-LogKeyValue -Text $LogText -Key "vendorCertifiedHdr10Plus"
    $vendorCertifiedDolbyVision = Resolve-LogKeyValue -Text $LogText -Key "vendorCertifiedDolbyVision"
    $dolbyRpuWritten = Resolve-LogKeyValue -Text $LogText -Key "dolbyRpuWritten"
    $externalHdrStandardSubstitution = Resolve-LogKeyValue -Text $LogText -Key "externalHdrStandardSubstitution"
    $proprietaryProfilePreserved = Resolve-LogKeyValue -Text $LogText -Key "proprietaryProfilePreserved"
    $androidHdrTransportOnly = Resolve-LogKeyValue -Text $LogText -Key "androidHdrTransportOnly"
    $neoHdrNoVendorDependency = Resolve-LogKeyValue -Text $LogText -Key "neoHdrNoVendorDependency"
    $nLogVendorDependency = Resolve-LogKeyValue -Text $LogText -Key "nLogVendorDependency"
    $vendorHdrCertificationFlagsFalse = $true
    foreach ($flag in @(
        @{ Key = "vendorHdrCertification"; Value = $vendorHdrCertification },
        @{ Key = "officialHdr10PlusMetadataWritten"; Value = $officialHdr10PlusMetadataWritten },
        @{ Key = "vendorCertifiedHdr10Plus"; Value = $vendorCertifiedHdr10Plus },
        @{ Key = "vendorCertifiedDolbyVision"; Value = $vendorCertifiedDolbyVision },
        @{ Key = "dolbyRpuWritten"; Value = $dolbyRpuWritten },
        @{ Key = "externalHdrStandardSubstitution"; Value = $externalHdrStandardSubstitution }
    )) {
        if (-not (Test-FalseMetadataFlag `
            -Key $flag.Key `
            -Value $flag.Value `
            -Required $proprietaryRow `
            -Failures $failures `
            -NotProven $notProven)
        ) {
            $vendorHdrCertificationFlagsFalse = $false
        }
    }
    if ($proprietaryRow) {
        if ($proprietaryProfilePreserved -ne "true") {
            [void]$failures.Add("proprietaryProfilePreserved must be true for $($Row.ExpectedMode) but was $proprietaryProfilePreserved")
        }
        if ($Row.ExpectedMode -eq "HDR") {
            if ($androidHdrTransportOnly -ne "true") {
                [void]$failures.Add("androidHdrTransportOnly=true is required for proprietary NDR/NIVUKX Camera2 transport")
            }
            if ($neoHdrNoVendorDependency -ne "true") {
                [void]$failures.Add("neoHdrNoVendorDependency=true is required for proprietary NDR/NIVUKX rows")
            }
        }
        if ($Row.ExpectedMode -eq "LOG" -and $nLogVendorDependency -ne "false") {
            [void]$failures.Add("nLogVendorDependency must be false for LOG rows but was $nLogVendorDependency")
        }
    }

    $p010FramesQueued = 0L
    $p010FrameDrops = -1L
    $p010ByteBufferProof = $false
    $requiresP010ByteBufferProof =
        $VideoDetailProfile -ieq "EXTREME_DETAIL" -and
        -not [bool]$Row.Raw -and
        $Row.ExpectedCodec -ieq "hevc"
    if ($requiresP010ByteBufferProof) {
        if ($LogText -notmatch "Recording feed path selected=BYTEBUFFER_P010_ENCODER") {
            [void]$failures.Add("Extreme Detail did not select BYTEBUFFER_P010_ENCODER")
        }
        if ($LogText -notmatch "Video encoder using P010 bytebuffer input") {
            [void]$failures.Add("P010 bytebuffer MediaCodec input was not configured")
        }
        if ($LogText -notmatch "P010 bytebuffer recording started") {
            [void]$failures.Add("P010 bytebuffer recording did not start")
        }
        if ($LogText -notmatch "Camera2 pipeline=ENCODED_RECORDING_WITH_READOUT.*yuv=true") {
            [void]$failures.Add("Camera2 did not target the P010/YUV readout during recording")
        }
        $p010EosMatches = [regex]::Matches($LogText, "P010 bytebuffer EOS queued frames=(\d+)\s+drops=(\d+)")
        if ($p010EosMatches.Count -eq 0) {
            [void]$notProven.Add("missing P010 bytebuffer EOS frame/drop summary")
        } else {
            $lastP010Eos = $p010EosMatches[$p010EosMatches.Count - 1]
            [int64]::TryParse($lastP010Eos.Groups[1].Value, [ref]$p010FramesQueued) | Out-Null
            [int64]::TryParse($lastP010Eos.Groups[2].Value, [ref]$p010FrameDrops) | Out-Null
            if ($p010FramesQueued -le 0L) {
                [void]$failures.Add("P010 bytebuffer queued zero frames")
            }
            if ($p010FrameDrops -ne 0L) {
                [void]$failures.Add("P010 bytebuffer frame drops=$p010FrameDrops")
            }
        }
        if ($LogText -match "P010 bytebuffer frame drop reason=") {
            [void]$failures.Add("P010 bytebuffer input drop reason logged")
        }
        if ($LogText -notmatch "P010 bytebuffer evidence ->") {
            [void]$notProven.Add("missing final P010 bytebuffer evidence summary")
        }
        $p010ByteBufferProof = $p010FramesQueued -gt 0L -and $p010FrameDrops -eq 0L
    }

    $mediaId = Get-MediaValue -ContentQueryOutput $MediaText -Key "_id"
    $displayName = Get-MediaValue -ContentQueryOutput $MediaText -Key "_display_name"
    $mediaWidth = Get-MediaValue -ContentQueryOutput $MediaText -Key "width"
    $mediaHeight = Get-MediaValue -ContentQueryOutput $MediaText -Key "height"
    $mediaFps = Get-MediaValue -ContentQueryOutput $MediaText -Key "capture_framerate"
    $mediaTransfer = Get-MediaValue -ContentQueryOutput $MediaText -Key "color_transfer"
    $mediaStandard = Get-MediaValue -ContentQueryOutput $MediaText -Key "color_standard"
    if ([string]::IsNullOrWhiteSpace($mediaId)) { [void]$notProven.Add("missing MediaStore row") }
    if (-not [string]::IsNullOrWhiteSpace($displayName) -and $displayName -notlike "*$($Row.Label)*") {
        [void]$failures.Add("display name does not contain validation label")
    }
    if ($mediaWidth -and $mediaHeight -and "${mediaWidth}x${mediaHeight}" -ne $Row.Resolution) {
        [void]$failures.Add("MediaStore resolution ${mediaWidth}x${mediaHeight} does not match $($Row.Resolution)")
    }
    if ($mediaFps) {
        $mediaFpsValue = 0.0
        if ([double]::TryParse($mediaFps, [ref]$mediaFpsValue) -and [math]::Abs($mediaFpsValue - [double]$Row.Fps) -gt 0.75) {
            [void]$failures.Add("MediaStore fps $mediaFpsValue does not match $($Row.Fps)")
        }
    }

    $observed = $null
    if ([string]::IsNullOrWhiteSpace($FfprobeText)) {
        [void]$notProven.Add("ffprobe output unavailable")
    } else {
        $observed = New-ProbeObservation -FfprobeText $FfprobeText
        if ([string]::IsNullOrWhiteSpace($observed.Codec)) { [void]$notProven.Add("ffprobe missing codec") }
        if ($observed.Codec -and $observed.Codec.ToLowerInvariant() -ne $Row.ExpectedCodec.ToLowerInvariant()) {
            [void]$failures.Add("codec $($observed.Codec) does not match expected $($Row.ExpectedCodec)")
        }
        if ("$($observed.Width)x$($observed.Height)" -ne $Row.Resolution) {
            [void]$failures.Add("ffprobe resolution $($observed.Width)x$($observed.Height) does not match $($Row.Resolution)")
        }
        if ($observed.Fps -gt 0.0 -and [math]::Abs($observed.Fps - [double]$Row.Fps) -gt 0.75) {
            [void]$failures.Add("ffprobe fps $($observed.Fps) does not match $($Row.Fps)")
        }
        if ($observed.BitDepth -lt [int]$Row.ExpectedBitDepth) {
            [void]$failures.Add("bit depth $($observed.BitDepth) is below expected $($Row.ExpectedBitDepth)")
        }
        if ($requiresP010ByteBufferProof -and $observed.BitDepth -lt 10) {
            [void]$failures.Add("Extreme Detail P010 route did not produce 10-bit output")
        }
        if ($requiresP010ByteBufferProof -and
            -not [string]::IsNullOrWhiteSpace($observed.PixelFormat) -and
            $observed.PixelFormat -notmatch "p010|yuv420p10|10"
        ) {
            [void]$failures.Add("Extreme Detail P010 route pixel format is not 10-bit/P010: $($observed.PixelFormat)")
        }
        if ($observed.Primaries -ne $Row.ExpectedPrimaries) {
            [void]$failures.Add("primaries $($observed.Primaries) do not match expected $($Row.ExpectedPrimaries)")
        }
        if ($observed.Matrix -ne $Row.ExpectedMatrix) {
            [void]$failures.Add("matrix $($observed.Matrix) does not match expected $($Row.ExpectedMatrix)")
        }
        $observedHdrTransfer = $observed.Transfer -in @("ST2084/PQ", "HLG")
        if ($Row.ExpectedMode -eq "HDR" -and -not $observedHdrTransfer) {
            [void]$failures.Add("HDR mode is not tagged as PQ/HLG HDR")
        }
        if ($Row.ExpectedMode -in @("NORMAL", "PRO", "LOG") -and $observedHdrTransfer) {
            [void]$failures.Add("$($Row.ExpectedMode) mode is wrongly HDR tagged")
        }
        if ($Row.ExpectedMode -eq "LOG") {
            if ($observed.BitDepth -lt 10) { [void]$failures.Add("LOG mode is not 10-bit") }
            if ($observed.Primaries -ne "BT.2020") { [void]$failures.Add("LOG mode is not wide-gamut BT.2020") }
        } elseif ($observed.Transfer -ne $Row.ExpectedTransfer) {
            [void]$failures.Add("transfer $($observed.Transfer) does not match expected $($Row.ExpectedTransfer)")
        }
        if ($observed.FrameCount -gt 0) {
            $expectedFrames = [math]::Floor(($DurationMs / 1000.0) * [double]$Row.Fps * 0.90)
            if ($observed.FrameCount -lt $expectedFrames) {
                [void]$failures.Add("frame count $($observed.FrameCount) below expected floor $expectedFrames")
            }
        } else {
            [void]$notProven.Add("ffprobe frame count unavailable")
        }
        $allowedRotationDegrees = @($Row.AllowedRotationDegrees)
        if ($allowedRotationDegrees -notcontains [int]$observed.RotationDegrees) {
            [void]$failures.Add(
                "expected rotation within allowed rotations $($allowedRotationDegrees -join ', ') " +
                    "but ffprobe reported $($observed.RotationDegrees)"
            )
        }
        if ($observed.Mirrored -ne [bool]$Row.ExpectedMirrored -or $observed.Mirrored) {
            [void]$failures.Add("unexpected mirrored output: expected $($Row.ExpectedMirrored) but ffprobe reported $($observed.Mirrored)")
        }
    }

    if ([string]::IsNullOrWhiteSpace($MediaInfoText)) {
        [void]$notProven.Add("MediaInfo output unavailable")
    }

    $status = if ($failures.Count -gt 0) {
        "FAIL"
    } elseif ($notProven.Count -gt 0) {
        "NOT_PROVEN"
    } else {
        "PASS"
    }

    return [pscustomobject]@{
        RowId = $Row.Id
        Label = $Row.Label
        Status = $status
        Mode = $Row.ExpectedMode
        Codec = if ($observed) { $observed.Codec } else { "" }
        PixelFormat = if ($observed) { $observed.PixelFormat } else { "" }
        Resolution = if ($observed) { "$($observed.Width)x$($observed.Height)" } else { "" }
        Fps = if ($observed) { $observed.Fps } else { 0 }
        BitrateBps = if ($observed) { $observed.BitrateBps } else { 0 }
        BitDepth = if ($observed) { $observed.BitDepth } else { 0 }
        Transfer = if ($observed) { $observed.Transfer } else { "" }
        Primaries = if ($observed) { $observed.Primaries } else { "" }
        Matrix = if ($observed) { $observed.Matrix } else { "" }
        FrameCount = if ($observed) { $observed.FrameCount } else { 0 }
        RotationDegrees = if ($observed) { $observed.RotationDegrees } else { 0 }
        Mirrored = if ($observed) { $observed.Mirrored } else { $false }
        RecordedFileBytes = $localBytes
        MediaStoreId = $mediaId
        MediaStoreDisplayName = $displayName
        MediaStoreColorStandard = $mediaStandard
        MediaStoreColorTransfer = $mediaTransfer
        Failures = ($failures -join " | ")
        NotProven = ($notProven -join " | ")
        Warnings = ($warnings -join " | ")
        ByteBufferP010FramesQueued = $p010FramesQueued
        ByteBufferP010FrameDrops = $p010FrameDrops
        ByteBufferP010Proof = $p010ByteBufferProof
        DeviceLogValidated = $deviceLogValidated
        VendorHdrCertificationFlagsFalse = $vendorHdrCertificationFlagsFalse
        VendorHdrCertification = if ($vendorHdrCertification) { $vendorHdrCertification } else { "false" }
        OfficialHdr10PlusMetadataWritten = if ($officialHdr10PlusMetadataWritten) { $officialHdr10PlusMetadataWritten } else { "false" }
        VendorCertifiedHdr10Plus = if ($vendorCertifiedHdr10Plus) { $vendorCertifiedHdr10Plus } else { "false" }
        VendorCertifiedDolbyVision = if ($vendorCertifiedDolbyVision) { $vendorCertifiedDolbyVision } else { "false" }
        DolbyRpuWritten = if ($dolbyRpuWritten) { $dolbyRpuWritten } else { "false" }
        ExternalHdrStandardSubstitution = if ($externalHdrStandardSubstitution) { $externalHdrStandardSubstitution } else { "false" }
        ProprietaryProfilePreserved = if ($proprietaryProfilePreserved) { $proprietaryProfilePreserved } else { "false" }
        AndroidHdrTransportOnly = if ($androidHdrTransportOnly) { $androidHdrTransportOnly } else { "false" }
        NeoHdrNoVendorDependency = if ($neoHdrNoVendorDependency) { $neoHdrNoVendorDependency } else { "false" }
        NLogVendorDependency = if ($nLogVendorDependency) { $nLogVendorDependency } else { "false" }
        SyntheticGrainEnabled = "false"
        Strict10QualityProven = ($status -eq "PASS")
    }
}

function Write-V15Certificate {
    param(
        [object]$Row,
        [object]$Result,
        [string]$OutputDirectory,
        [string]$LogPath,
        [string]$DeviceLogPath,
        [string]$MediaStorePath,
        [string]$FfprobePath,
        [string]$MediaInfoPath,
        [string]$LocalVideoPath
    )
    $certificatePath = Join-Path $OutputDirectory "$($Row.Id).quality-certificate.txt"
    $lines = @(
        "qualityEvidenceGeneration=V15_STRICT_QUALITY_EVIDENCE_GATE",
        "v10QualityEvidenceGeneration=V10_EVIDENCE_CALIBRATED_GATE",
        "qualityEvidenceStatus=$($Result.Status)",
        "strict10QualityProven=$($Result.Strict10QualityProven)",
        "mode=$($Result.Mode)",
        "codec=$($Result.Codec)",
        "pixelFormat=$($Result.PixelFormat)",
        "resolution=$($Result.Resolution)",
        "fps=$($Result.Fps)",
        "bitrateBps=$($Result.BitrateBps)",
        "bitDepth=$($Result.BitDepth)",
        "transfer=$($Result.Transfer)",
        "primaries=$($Result.Primaries)",
        "matrix=$($Result.Matrix)",
        "frameCount=$($Result.FrameCount)",
        "rotationDegrees=$($Result.RotationDegrees)",
        "mirrored=$($Result.Mirrored)",
        "recordedFileBytes=$($Result.RecordedFileBytes)",
        "byteBufferP010Proof=$($Result.ByteBufferP010Proof)",
        "byteBufferP010FramesQueued=$($Result.ByteBufferP010FramesQueued)",
        "byteBufferP010FrameDrops=$($Result.ByteBufferP010FrameDrops)",
        "vendorHdrCertificationFlagsFalse=$($Result.VendorHdrCertificationFlagsFalse)",
        "vendorHdrCertification=$($Result.VendorHdrCertification)",
        "officialHdr10PlusMetadataWritten=$($Result.OfficialHdr10PlusMetadataWritten)",
        "vendorCertifiedHdr10Plus=$($Result.VendorCertifiedHdr10Plus)",
        "vendorCertifiedDolbyVision=$($Result.VendorCertifiedDolbyVision)",
        "dolbyRpuWritten=$($Result.DolbyRpuWritten)",
        "externalHdrStandardSubstitution=$($Result.ExternalHdrStandardSubstitution)",
        "proprietaryProfilePreserved=$($Result.ProprietaryProfilePreserved)",
        "androidHdrTransportOnly=$($Result.AndroidHdrTransportOnly)",
        "neoHdrNoVendorDependency=$($Result.NeoHdrNoVendorDependency)",
        "nLogVendorDependency=$($Result.NLogVendorDependency)",
        "syntheticGrainEnabled=$($Result.SyntheticGrainEnabled)",
        "npv10FallbackMislabeledNative10Bit=false",
        "deviceLogValidated=$($Result.DeviceLogValidated)",
        "adbLogPath=$DeviceLogPath",
        "deviceLogPath=$DeviceLogPath",
        "appLogPath=$LogPath",
        "mediaStorePath=$MediaStorePath",
        "ffprobePath=$FfprobePath",
        "mediaInfoPath=$MediaInfoPath",
        "localVideoPath=$LocalVideoPath",
        "failureCodes=$($Result.Failures)",
        "notProven=$($Result.NotProven)"
    )
    Set-Content -Path $certificatePath -Value $lines -Encoding UTF8
    return $certificatePath
}

$script:ResolvedAdbPath = Resolve-AdbPath -RequestedPath $AdbPath
$script:FfprobeExecutablePath = Resolve-ToolPath -Name "ffprobe"
$script:MediaInfoExecutablePath = Resolve-ToolPath -Name "mediainfo"
$rows = @(Get-V15Rows)
if ($DryRun) {
    $rows | Select-Object Id, Label, Preset, VideoMode, Raw, Log, HdrMode, ColorSpace, Resolution, Fps, ExpectedMode, ExpectedBitDepth
    return
}
if ($rows.Count -eq 0) { throw "No V15 quality rows selected." }
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
$appLogFilterSpecs = @(
    "MainActivity:V",
    "DebugCommandReceiver:V",
    "CameraViewModel:V",
    "RecordingManager:V",
    "CameraDeviceOrchestrator:V",
    "AICameraPipeline:V",
    "FaceTracker:V",
    "AndroidRuntime:E",
    "MediaCodec:E",
    "CCodec:E",
    "ACodec:E",
    "*:S"
)
foreach ($row in $rows) {
    Write-Host "[V15] Recording $($row.Id)"
    Invoke-Adb -CommandArgs @("logcat", "-c") -TimeoutSec 30 -IgnoreExitCode | Out-Null
    $preExistingMediaId = Get-LatestMediaIdForRow -Row $row
    $logPath = Join-Path $OutputDir "$($row.Id).logcat.txt"
    $deviceLogPath = Join-Path $OutputDir "$($row.Id).device-logcat.txt"
    $logCapture = Start-ContinuousLogcatCapture -OutputPath $logPath -FilterSpecs $appLogFilterSpecs
    $deviceLogCapture = Start-ContinuousLogcatCapture -OutputPath $deviceLogPath
    $logText = ""
    try {
        Start-AppForeground
        Invoke-RecordCommand -Row $row | Out-Null
        $logText = Wait-ForRecordingEvidence -Row $row
    } finally {
        Stop-ContinuousLogcatCapture -Capture $logCapture
        Stop-ContinuousLogcatCapture -Capture $deviceLogCapture
    }
    $continuousLogText = if (Test-Path $logPath) { Get-Content -Path $logPath -Raw } else { "" }
    $deviceLogText = if (Test-Path $deviceLogPath) { Get-Content -Path $deviceLogPath -Raw } else { "" }
    $logText = (@($continuousLogText, $logText) |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join [Environment]::NewLine
    $mediaText = Get-MediaStoreRow -Row $row -MinimumMediaId $preExistingMediaId
    $mediaId = Get-MediaValue -ContentQueryOutput $mediaText -Key "_id"
    $localVideo = Join-Path $OutputDir "$($row.Id).mov"
    $ffprobeOutputPath = Join-Path $OutputDir "$($row.Id).ffprobe.txt"
    $mediaInfoOutputPath = Join-Path $OutputDir "$($row.Id).mediainfo.json"
    $pulled = Export-MediaContent -MediaId $mediaId -OutputPath $localVideo
    $ffprobeText = if ($pulled) { Invoke-Ffprobe -VideoPath $localVideo -OutputPath $ffprobeOutputPath } else { "" }
    $mediaInfoText = if ($pulled) { Invoke-MediaInfo -VideoPath $localVideo -OutputPath $mediaInfoOutputPath } else { "" }

    $mediaPath = Join-Path $OutputDir "$($row.Id).mediastore.txt"
    Set-Content -Path $logPath -Value $logText -Encoding UTF8
    Set-Content -Path $deviceLogPath -Value $deviceLogText -Encoding UTF8
    Set-Content -Path $mediaPath -Value $mediaText -Encoding UTF8

    $result = Test-V15Row `
        -Row $row `
        -LogText $logText `
        -DeviceLogText $deviceLogText `
        -DeviceLogPath $deviceLogPath `
        -MediaText $mediaText `
        -FfprobeText $ffprobeText `
        -MediaInfoText $mediaInfoText `
        -LocalVideo $localVideo
    $certificatePath = Write-V15Certificate `
        -Row $row `
        -Result $result `
        -OutputDirectory $OutputDir `
        -LogPath $logPath `
        -DeviceLogPath $deviceLogPath `
        -MediaStorePath $mediaPath `
        -FfprobePath ($(if ($ffprobeText) { $ffprobeOutputPath } else { "" })) `
        -MediaInfoPath ($(if ($mediaInfoText) { $mediaInfoOutputPath } else { "" })) `
        -LocalVideoPath ($(if ($pulled) { $localVideo } else { "" }))
    $result | Add-Member -NotePropertyName LogPath -NotePropertyValue $logPath
    $result | Add-Member -NotePropertyName DeviceLogPath -NotePropertyValue $deviceLogPath
    $result | Add-Member -NotePropertyName MediaStorePath -NotePropertyValue $mediaPath
    $result | Add-Member -NotePropertyName LocalVideoPath -NotePropertyValue ($(if ($pulled) { $localVideo } else { "" }))
    $result | Add-Member -NotePropertyName FfprobePath -NotePropertyValue ($(if ($ffprobeText) { $ffprobeOutputPath } else { "" }))
    $result | Add-Member -NotePropertyName MediaInfoPath -NotePropertyValue ($(if ($mediaInfoText) { $mediaInfoOutputPath } else { "" }))
    $result | Add-Member -NotePropertyName CertificatePath -NotePropertyValue $certificatePath
    [void]$summary.Add($result)
    Write-Host "[V15] $($row.Id) => $($result.Status)"
}

$csvPath = Join-Path $OutputDir "v15-quality-report.csv"
$jsonPath = Join-Path $OutputDir "v15-quality-report.json"
$txtPath = Join-Path $OutputDir "v15-quality-report.txt"
$summary | Export-Csv -NoTypeInformation -Path $csvPath
$summary | ConvertTo-Json -Depth 8 | Set-Content -Path $jsonPath -Encoding UTF8
$lines = New-Object System.Collections.Generic.List[string]
[void]$lines.Add("NeoProCamX V15 Strict Quality Evidence Report")
[void]$lines.Add("GeneratedAt=$((Get-Date).ToString('o'))")
[void]$lines.Add("AdbPath=$script:ResolvedAdbPath")
[void]$lines.Add("FfprobePath=$script:FfprobeExecutablePath")
[void]$lines.Add("MediaInfoPath=$script:MediaInfoExecutablePath")
foreach ($item in $summary) {
    [void]$lines.Add(
        "$($item.Status) $($item.RowId) mode=$($item.Mode) codec=$($item.Codec) " +
        "res=$($item.Resolution) fps=$($item.Fps) bitDepth=$($item.BitDepth) " +
        "transfer=$($item.Transfer) primaries=$($item.Primaries) matrix=$($item.Matrix) " +
        "failures=[$($item.Failures)] notProven=[$($item.NotProven)]"
    )
}
Set-Content -Path $txtPath -Value $lines -Encoding UTF8

$failCount = @($summary | Where-Object { $_.Status -eq "FAIL" }).Count
$notProvenCount = @($summary | Where-Object { $_.Status -eq "NOT_PROVEN" }).Count
$passCount = @($summary | Where-Object { $_.Status -eq "PASS" }).Count
Write-Host "[V15] Summary PASS=$passCount NOT_PROVEN=$notProvenCount FAIL=$failCount"
Write-Host "[V15] Report: $txtPath"
if ($failCount -gt 0) { exit 1 }
if ($notProvenCount -gt 0) { exit 2 }
exit 0
