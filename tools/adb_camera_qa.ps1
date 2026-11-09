param(
    [string]$AdbPath = "adb",
    [string]$Serial,
    [string]$PackageName = "com.neoproc.neoproccamx",
    [string]$MainActivityComponent = "com.neoproc.neoproccamx/.ui.MainActivity",
    [string]$DebugReceiverComponent = "com.neoproc.neoproccamx/.ui.DebugCommandReceiver",
    [string]$ReportRoot = "",
    [ValidateRange(0, 1000)]
    [int]$CaptureStressCount = 20,
    [ValidateRange(0, 10000)]
    [int]$LongRunCaptureCount = 100,
    [ValidateRange(1, 3600)]
    [int]$VideoRecordDurationSec = 10,
    [ValidateRange(1, 32768)]
    [int]$LowStorageFillMb = 2048,
    [switch]$SafeSmoke,
    [switch]$SkipGenericIntents,
    [switch]$SkipExplicitAppLaunch,
    [switch]$SkipBaselinePhotos,
    [switch]$SkipHdrCapture,
    [switch]$SkipRawCapture,
    [switch]$SkipFocus,
    [switch]$SkipFacingSwitch,
    [switch]$SkipVideos,
    [switch]$SkipPerformanceVideo,
    [switch]$SkipCameraWatch,
    [switch]$SkipPermissionMutation,
    [switch]$SkipLogcatClear,
    [switch]$SkipCrashRecovery,
    [switch]$SkipPowerAndAppSwitch,
    [switch]$SkipLowStorage,
    [switch]$SkipLowBattery,
    [switch]$SkipIncomingCall,
    [switch]$EnableLegacyMovVideoOracle,
    [switch]$EnablePerformanceVideoProbe,
    [switch]$EnableLowStorageScenario,
    [switch]$EnableLowBatteryScenario,
    [switch]$RequireThermalPreflight = $true,
    [ValidateRange(1, 20)]
    [int]$ThermalReadyReadingCount = 2,
    [ValidateRange(0, 3600)]
    [int]$ThermalReadyIntervalSec = 60,
    [ValidateRange(12288, 1048576)]
    [int]$MinimumDataFreeMb = 12288,
    [ValidateRange(12288, 1048576)]
    [int]$MinimumSdcardFreeMb = 12288
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# SafeSmoke is intentionally read-only on the device. Keep this assignment centralized so future
# mutating scenarios cannot accidentally escape the profile: every new mutation must gain a skip
# switch and be added here before SafeSmoke can truthfully remain safe.
if ($SafeSmoke) {
    $CaptureStressCount = 0
    $LongRunCaptureCount = 0
    $SkipGenericIntents = $true
    $SkipExplicitAppLaunch = $true
    $SkipBaselinePhotos = $true
    $SkipHdrCapture = $true
    $SkipRawCapture = $true
    $SkipFocus = $true
    $SkipFacingSwitch = $true
    $SkipVideos = $true
    $SkipPerformanceVideo = $true
    $SkipCameraWatch = $true
    $SkipPermissionMutation = $true
    $SkipLogcatClear = $true
    $SkipCrashRecovery = $true
    $SkipPowerAndAppSwitch = $true
    $SkipLowStorage = $true
    $SkipLowBattery = $true
    $SkipIncomingCall = $true
    $EnableLegacyMovVideoOracle = $false
    $EnablePerformanceVideoProbe = $false
    $EnableLowStorageScenario = $false
    $EnableLowBatteryScenario = $false
}

if (-not $RequireThermalPreflight) {
    throw "Thermal preflight is mandatory for this production QA harness."
}

function Resolve-AdbPath {
    param([string]$RequestedPath)

    if (-not [string]::IsNullOrWhiteSpace($RequestedPath) -and
        $RequestedPath -notin @("adb", "adb.exe")) {
        return $RequestedPath
    }

    $candidates = New-Object System.Collections.Generic.List[string]
    foreach ($name in @("adb.exe", "adb")) {
        $cmd = Get-Command $name -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($cmd -and $cmd.Source) {
            [void]$candidates.Add($cmd.Source)
        }
    }

    foreach ($sdkRoot in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA "Android\\Sdk"))) {
        if ([string]::IsNullOrWhiteSpace($sdkRoot)) { continue }
        [void]$candidates.Add((Join-Path $sdkRoot "platform-tools\\adb.exe"))
        [void]$candidates.Add((Join-Path $sdkRoot "platform-tools\\adb"))
    }

    foreach ($candidate in $candidates | Select-Object -Unique) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path $candidate)) {
            return $candidate
        }
    }

    throw "Unable to locate adb. Pass -AdbPath explicitly or install Android platform-tools."
}

$AdbPath = Resolve-AdbPath -RequestedPath $AdbPath

if ($PackageName -notmatch '^[A-Za-z0-9_.]+$') {
    throw "PackageName contains unsupported characters."
}
foreach ($component in @($MainActivityComponent, $DebugReceiverComponent)) {
    if ($component -notmatch '^[A-Za-z0-9_.$/]+$') {
        throw "Android component contains unsupported characters: $component"
    }
}

if ([string]::IsNullOrWhiteSpace($ReportRoot)) {
    $ReportRoot = Join-Path $PSScriptRoot "reports"
}

$runId = Get-Date -Format "yyyyMMdd_HHmmss"
$runDir = Join-Path $ReportRoot "camera_qa_$runId"
$logDir = Join-Path $runDir "logs"
$mediaDir = Join-Path $runDir "media"
$reportPath = Join-Path $runDir "summary.md"
$jsonPath = Join-Path $runDir "summary.json"
$sessionLog = Join-Path $runDir "session.log"
New-Item -ItemType Directory -Force -Path $runDir, $logDir, $mediaDir | Out-Null

$script:Results = New-Object System.Collections.Generic.List[object]
$script:BatteryOverrideActive = $false
$script:LowStorageFillPath = "/sdcard/camx_qa_fill_$runId.bin"
$script:LowStorageFillPidPath = "/data/local/tmp/camx_qa_fill_$runId.pid"
$script:MinimumPostFillReserveMb = 2048L
$script:SessionLogWriteWarningEmitted = $false
$script:OriginalPermissionStates = @{}
$script:PermissionSnapshotCaptured = $false
$script:CleanupCompleted = $false
$script:DeviceReadyForCleanup = $false
$script:DeviceMutationStarted = $false
$script:TargetAppTouched = $false
$script:PermissionsMutated = $false
$script:LowStorageFillMayExist = $false
$script:LowStorageFreeBeforeMb = $null
$script:CameraWatchMayBeActive = $false
$script:ReportWriteFailed = $false
$script:TopLevelCleanupFailed = $false

function Log-Line {
    param([string]$Level, [string]$Message)
    $line = "[{0}] [{1}] {2}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $Level.ToUpperInvariant(), $Message
    Write-Host $line
    try {
        Add-Content -Path $sessionLog -Value $line -ErrorAction Stop
    } catch [System.IO.IOException] {
        if (-not $script:SessionLogWriteWarningEmitted) {
            $script:SessionLogWriteWarningEmitted = $true
            Write-Host ("[{0}] [WARN] session.log is locked; continuing without file append until lock clears." -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"))
        }
    }
}

function Add-Result {
    param(
        [string]$Category,
        [string]$Step,
        [ValidateSet("PASS", "FAIL", "SKIP")]
        [string]$Status,
        [string]$Details,
        [string]$Artifact = "",
        [bool]$Required = $true
    )
    # SafeSmoke intentionally omits all device-mutating coverage. Its deliberate SKIP rows are
    # informational; every FAIL (including readiness) remains fatal. Custom/full runs retain the
    # default required semantics and cannot return success with unresolved required coverage.
    if ($SafeSmoke -and $Status -eq "SKIP") {
        $Required = $false
    }
    $item = [pscustomobject]@{
        Category = $Category
        Step = $Step
        Status = $Status
        Details = $Details
        Artifact = $Artifact
        Required = $Required
    }
    $script:Results.Add($item) | Out-Null
    Log-Line -Level $Status -Message "$Category :: $Step :: $Details"
}

function Mark-DeviceMutation {
    $script:DeviceMutationStarted = $true
}

function Mark-TargetAppInteraction {
    Mark-DeviceMutation
    $script:TargetAppTouched = $true
}

function Get-AdbArgs {
    param([string[]]$CommandArgs)
    if ([string]::IsNullOrWhiteSpace($Serial)) { return $CommandArgs }
    return @("-s", $Serial) + $CommandArgs
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

function Run-Command {
    param(
        [string]$FilePath,
        [string[]]$CommandArgs,
        [int]$TimeoutSec = 60,
        [switch]$IgnoreExitCode
    )
    $commandSummary = "$FilePath $($CommandArgs -join ' ')"
    Log-Line -Level "INFO" -Message "Running command: $commandSummary"
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $FilePath
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $psi.Arguments = Join-CommandArgs -CommandArgs $CommandArgs
    $proc = [System.Diagnostics.Process]::new()
    $proc.StartInfo = $psi
    [void]$proc.Start()
    $stdoutTask = $proc.StandardOutput.ReadToEndAsync()
    $stderrTask = $proc.StandardError.ReadToEndAsync()
    $timedOut = -not $proc.WaitForExit($TimeoutSec * 1000)
    $terminated = -not $timedOut
    $killFailure = ""
    if ($timedOut) {
        # Windows PowerShell 5.1 exposes Process.Kill() but not the newer Kill(Boolean) overload.
        # Never fall through to an unbounded WaitForExit: cleanup/reporting must still execute even
        # when the adb client cannot be terminated. A dispatched remote shell command may outlive
        # its host client, so all mutating scenarios also retain explicit cleanup fences.
        try {
            $proc.Kill()
        } catch {
            $killFailure = $_.Exception.Message
        }
        try {
            $terminated = $proc.WaitForExit(5000)
        } catch {
            $killFailure = (($killFailure, $_.Exception.Message) | Where-Object { $_ }) -join "; "
            $terminated = $false
        }
    }
    $streamsDrained = $false
    $streamDrainFailure = ""
    if ($terminated) {
        try {
            $streamsDrained = [System.Threading.Tasks.Task]::WaitAll(
                [System.Threading.Tasks.Task[]]@($stdoutTask, $stderrTask),
                2000
            )
        } catch {
            $streamDrainFailure = $_.Exception.Message
            $streamsDrained = $false
        }
    }
    if ($terminated -and $streamsDrained) {
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
    } else {
        try { $proc.StandardOutput.Close() } catch {}
        try { $proc.StandardError.Close() } catch {}
        $stdout = ""
        $stderr = if (-not $terminated) {
            "Host process did not terminate within 5 seconds after timeout."
        } else {
            "Host process exited, but redirected streams did not drain within 2 seconds; a descendant may still hold pipe handles."
        }
        if (-not [string]::IsNullOrWhiteSpace($killFailure)) {
            $stderr += " Kill failure: $killFailure"
        }
        if (-not [string]::IsNullOrWhiteSpace($streamDrainFailure)) {
            $stderr += " Stream failure: $streamDrainFailure"
        }
    }
    $exitCode = if ($timedOut) {
        124
    } elseif (-not $terminated -or -not $streamsDrained) {
        125
    } else {
        $proc.ExitCode
    }
    $res = [pscustomobject]@{
        ExitCode = $exitCode
        TimedOut = $timedOut
        StdOut = $stdout
        StdErr = $stderr
        Output = (@($stdout, $stderr) -join [Environment]::NewLine).Trim()
    }
    $proc.Dispose()
    $resultSummary = if ($timedOut) {
        "timed out after ${TimeoutSec}s"
    } else {
        "exit=$exitCode"
    }
    Log-Line -Level "INFO" -Message "Command finished: $resultSummary :: $commandSummary"
    if (-not $IgnoreExitCode -and $exitCode -ne 0) {
        throw "Command failed: $FilePath $($CommandArgs -join ' ')`n$($res.Output)"
    }
    return $res
}

function Run-Adb {
    param([string[]]$CommandArgs, [int]$TimeoutSec = 60, [switch]$IgnoreExitCode)
    Run-Command -FilePath $AdbPath -CommandArgs (Get-AdbArgs -CommandArgs $CommandArgs) -TimeoutSec $TimeoutSec -IgnoreExitCode:$IgnoreExitCode
}

function Run-AdbShell {
    param([string]$Command, [int]$TimeoutSec = 60, [switch]$IgnoreExitCode)
    Run-Adb -CommandArgs @("shell", $Command) -TimeoutSec $TimeoutSec -IgnoreExitCode:$IgnoreExitCode
}

function Save-Artifact {
    param([string]$Name, [string]$Content)
    $path = Join-Path $logDir $Name
    Set-Content -Path $path -Value $Content -Encoding UTF8
    return $path
}

function Clear-Logcat {
    param([Parameter(Mandatory)][string]$Step)

    if ($SkipLogcatClear) {
        Add-Result -Category "Evidence" -Step $Step -Status "SKIP" -Details "Device-wide logcat clear skipped by parameter"
        return
    }
    Mark-DeviceMutation
    $clear = Run-Adb -CommandArgs @("logcat", "-c") -TimeoutSec 20 -IgnoreExitCode
    $ok = -not $clear.TimedOut -and $clear.ExitCode -eq 0
    $clearStatus = if ($ok) { "PASS" } else { "FAIL" }
    $clearDetails = if ($ok) {
        "Device-wide logcat buffer cleared"
    } else {
        "logcat clear failed or timed out; exit=$($clear.ExitCode)"
    }
    Add-Result -Category "Evidence" -Step $Step -Status $clearStatus -Details $clearDetails
}

function Save-Logcat {
    param([string]$Name)
    $logcat = Run-Adb -CommandArgs @("logcat", "-d", "-v", "time") -TimeoutSec 60 -IgnoreExitCode
    Save-Artifact -Name $Name -Content $logcat.Output
}

function Get-DeviceSerials {
    $devices = Run-Adb -CommandArgs @("devices") -TimeoutSec 20
    return ($devices.StdOut -split "`r?`n") |
        Where-Object { $_ -match "^\S+\s+device$" } |
        ForEach-Object { ($_ -split "\s+")[0] }
}

function Get-TopActivityLine {
    $dump = Run-AdbShell -Command "dumpsys activity activities" -TimeoutSec 40 -IgnoreExitCode
    return ($dump.Output -split "`r?`n" |
        Where-Object { $_ -match "mResumedActivity|topResumedActivity|ResumedActivity" } |
        Select-Object -First 1)
}

function Wait-TopPackage {
    param([string]$ExpectedPackage, [int]$TimeoutSec = 20)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $line = Get-TopActivityLine
        if ($line -and $line -match [regex]::Escape($ExpectedPackage)) { return $true }
        Start-Sleep -Milliseconds 800
    }
    return $false
}

function Ensure-DeviceInteractive {
    param([int]$TimeoutSec = 12)

    # Launching a SurfaceView camera while a physical device is asleep can leave the activity resumed
    # without ever receiving a usable SurfaceHolder callback. Wake and dismiss a non-secure keyguard
    # before functional camera scenarios; lock-screen behavior is exercised separately below.
    Run-AdbShell -Command "input keyevent KEYCODE_WAKEUP" -TimeoutSec 10 -IgnoreExitCode | Out-Null
    Run-AdbShell -Command "wm dismiss-keyguard" -TimeoutSec 10 -IgnoreExitCode | Out-Null
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $power = Run-AdbShell -Command "dumpsys power" -TimeoutSec 20 -IgnoreExitCode
        if (-not $power.TimedOut -and
            $power.ExitCode -eq 0 -and
            $power.Output -match "(?m)^\s*mWakefulness=Awake\s*$"
        ) {
            return $true
        }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Wait-TopPackageAbsent {
    param([string]$UnexpectedPackage, [int]$TimeoutSec = 10)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $line = Get-TopActivityLine
        # An empty activity dump is not proof that the app backgrounded.
        if (-not [string]::IsNullOrWhiteSpace($line) -and
            $line -notmatch [regex]::Escape($UnexpectedPackage)) {
            return $true
        }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Start-App {
    Mark-TargetAppInteraction
    if (-not (Ensure-DeviceInteractive)) {
        Log-Line -Level "WARN" -Message "Device did not report Awake before app launch"
    }
    return Run-Adb -CommandArgs @("shell", "am", "start", "-n", $MainActivityComponent) -TimeoutSec 30 -IgnoreExitCode
}

function Ensure-AppForegroundForDebug {
    param([int]$TimeoutSec = 12)
    if (-not (Ensure-DeviceInteractive)) {
        Log-Line -Level "WARN" -Message "Device did not report Awake before debug action"
    }
    if (Wait-TopPackage -ExpectedPackage $PackageName -TimeoutSec 3) {
        return $true
    }
    Start-App | Out-Null
    Start-Sleep -Seconds 1
    return (Wait-TopPackage -ExpectedPackage $PackageName -TimeoutSec $TimeoutSec)
}

function Debug-Broadcast {
    param([hashtable]$Extras = @{}, [int]$TimeoutSec = 30)
    Mark-TargetAppInteraction
    if (-not (Ensure-AppForegroundForDebug -TimeoutSec 12)) {
        Log-Line -Level "WARN" -Message "App not foreground before debug broadcast; proceeding anyway"
    }
    $args = New-Object System.Collections.Generic.List[string]
    foreach ($value in @("shell", "am", "broadcast", "-a", "$PackageName.DEBUG_COMMAND", "-n", $DebugReceiverComponent)) {
        [void]$args.Add($value)
    }
    foreach ($key in $Extras.Keys) {
        $value = $Extras[$key]
        if ($null -eq $value) { continue }
        if ($value -is [bool]) {
            foreach ($part in @("--ez", $key, $value.ToString().ToLowerInvariant())) { [void]$args.Add($part) }
        } elseif ($value -is [int]) {
            foreach ($part in @("--ei", $key, $value.ToString())) { [void]$args.Add($part) }
        } elseif ($value -is [long]) {
            foreach ($part in @("--el", $key, $value.ToString())) { [void]$args.Add($part) }
        } else {
            foreach ($part in @("--es", $key, [string]$value)) { [void]$args.Add($part) }
        }
    }
    $result = Run-Adb -CommandArgs $args.ToArray() -TimeoutSec $TimeoutSec -IgnoreExitCode
    if (-not [string]::IsNullOrWhiteSpace($result.Output)) {
        $broadcastOutput = ($result.Output -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join " | "
        Log-Line -Level "INFO" -Message "Broadcast output: $broadcastOutput"
    }
    return $result
}

function Scenario-Label {
    param([string]$Prefix)
    ("{0}_{1}" -f $Prefix, $runId).ToLowerInvariant()
}

function Get-RemoteArtifacts {
    param([string]$Label)
    $safe = ($Label -replace "[^a-zA-Z0-9_\-]", "_")
    $cmd = "for d in /sdcard/DCIM/CamX /sdcard/Movies/CamX /sdcard/Documents/CamX/RAW; do if [ -d `"`$d`" ]; then find `"`$d`" -type f 2>/dev/null; fi; done | grep -F '$safe' || true"
    $list = Run-AdbShell -Command $cmd -TimeoutSec 60 -IgnoreExitCode
    return $list.StdOut -split "`r?`n" | Where-Object {
        -not [string]::IsNullOrWhiteSpace($_) -and
        $_ -notmatch '/\.pending-' -and
        $_ -notmatch '/\.trashed-'
    }
}

function Wait-RemoteArtifacts {
    param([string]$Label, [int]$MinCount = 1, [int]$TimeoutSec = 60)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $paths = @(Get-RemoteArtifacts -Label $Label)
        if ($paths.Count -ge $MinCount) { return $paths }
        Start-Sleep -Seconds 2
    }
    return @(Get-RemoteArtifacts -Label $Label)
}

function Get-ContentQueryValue {
    param([string]$Row, [string]$Key)
    foreach ($part in ($Row -split ", ")) {
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

function Get-LabeledMediaStoreRow {
    param(
        [Parameter(Mandatory)][string]$Label,
        [string]$RequiredExtension = ""
    )

    # Labels are generated by this harness, but still escape SQL quotes so this helper remains safe if
    # called directly. MediaStore.Files covers JPEG, DNG, ZIP and processed video outputs without
    # requiring broad storage/media runtime permissions.
    $escapedLabel = $Label.Replace("'", "''")
    $projection = "_id:_display_name:mime_type:relative_path:_size:is_pending:date_added"
    $where = "_display_name LIKE '%$escapedLabel%'"
    $command = "content query --uri content://media/external/file --projection $projection --where `"$where`" --sort `"date_added DESC`""
    $query = Run-AdbShell -Command $command -TimeoutSec 30 -IgnoreExitCode
    if ($query.TimedOut -or $query.ExitCode -ne 0 -or
        $query.Output -match "(?i)unknown column|invalid column|unsupported|permission denial|securityexception|error while accessing provider") {
        return [pscustomobject]@{
            QuerySupported = $false
            Found = $false
            Reason = (($query.Output -split "`r?`n" | Select-Object -First 2) -join " ").Trim()
            Id = ""
            DisplayName = ""
            RelativePath = ""
            MimeType = ""
            Size = 0L
            IsPending = ""
            Raw = $query.Output
        }
    }

    $row = $query.Output -split "`r?`n" |
        Where-Object {
            $_ -match "^Row:\s*\d+\s+" -and
            $_ -match [regex]::Escape($Label) -and
            ([string]::IsNullOrWhiteSpace($RequiredExtension) -or
                $_ -match ("_display_name=.*" + [regex]::Escape($RequiredExtension) + "(?:,|$)"))
        } |
        Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace($row)) {
        return [pscustomobject]@{
            QuerySupported = $true
            Found = $false
            Reason = "No labeled MediaStore row"
            Id = ""
            DisplayName = ""
            RelativePath = ""
            MimeType = ""
            Size = 0L
            IsPending = ""
            Raw = $query.Output
        }
    }

    $sizeText = Get-ContentQueryValue -Row $row -Key "_size"
    $size = 0L
    [void][long]::TryParse($sizeText, [ref]$size)
    return [pscustomobject]@{
        QuerySupported = $true
        Found = $true
        Reason = ""
        Id = Get-ContentQueryValue -Row $row -Key "_id"
        DisplayName = Get-ContentQueryValue -Row $row -Key "_display_name"
        RelativePath = Get-ContentQueryValue -Row $row -Key "relative_path"
        MimeType = Get-ContentQueryValue -Row $row -Key "mime_type"
        Size = $size
        IsPending = Get-ContentQueryValue -Row $row -Key "is_pending"
        Raw = $row
    }
}

function Wait-DurableLabeledMediaStoreOutput {
    param(
        [Parameter(Mandatory)][string]$Label,
        [int]$TimeoutSec = 90,
        [int]$PollIntervalMs = 1500,
        [string]$RequiredExtension = ""
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $stableIdentity = ""
    $stablePolls = 0
    $lastRow = $null
    while ((Get-Date) -lt $deadline) {
        $row = Get-LabeledMediaStoreRow -Label $Label -RequiredExtension $RequiredExtension
        $lastRow = $row
        if (-not $row.QuerySupported) {
            return [pscustomobject]@{
                Status = "UNSUPPORTED"
                Details = "MediaStore publication query unavailable: $($row.Reason)"
                Row = $row
            }
        }

        if ($row.Found -and $row.IsPending -eq "0" -and $row.Size -gt 0) {
            $identity = "$($row.Id)|$($row.Size)"
            if ($identity -eq $stableIdentity) {
                $stablePolls += 1
            } else {
                $stableIdentity = $identity
                $stablePolls = 1
            }
            # Two identical post-publication observations prove that the row is no longer pending and
            # its non-zero payload has stopped changing before another full-resolution capture starts.
            if ($stablePolls -ge 2) {
                return [pscustomobject]@{
                    Status = "PUBLISHED"
                    Details = "name=$($row.DisplayName) size=$($row.Size) path=$($row.RelativePath)"
                    Row = $row
                }
            }
        } else {
            $stableIdentity = ""
            $stablePolls = 0
        }
        Start-Sleep -Milliseconds $PollIntervalMs
    }

    $lastState = if ($null -eq $lastRow) {
        "no query result"
    } elseif (-not $lastRow.Found) {
        "no labeled row"
    } else {
        "name=$($lastRow.DisplayName) pending=$($lastRow.IsPending) size=$($lastRow.Size)"
    }
    return [pscustomobject]@{
        Status = "TIMEOUT"
        Details = "Timed out after ${TimeoutSec}s waiting for durable labeled output ($lastState)"
        Row = $lastRow
    }
}

function Invoke-SerializedPhotoCapture {
    param(
        [Parameter(Mandatory)][string]$Label,
        [string]$Preset = "PHOTO",
        [hashtable]$AdditionalExtras = @{},
        [int]$TimeoutSec = 90
    )

    $extras = @{
        debug_preset = $Preset
        debug_action = "capture_photo"
        debug_validation_label = $Label
    }
    foreach ($key in $AdditionalExtras.Keys) {
        $extras[$key] = $AdditionalExtras[$key]
    }
    $broadcast = Debug-Broadcast -Extras $extras
    if ($broadcast.TimedOut -or $broadcast.ExitCode -ne 0 -or $broadcast.Output -match "(?i)securityexception|permission denial|unable to resolve|not found") {
        return [pscustomobject]@{
            Status = "COMMAND_FAILED"
            Details = "capture_photo command failed: $((($broadcast.Output -split "`r?`n") | Select-Object -First 2) -join ' ')"
            Row = $null
        }
    }
    return Wait-DurableLabeledMediaStoreOutput -Label $Label -TimeoutSec $TimeoutSec
}

function Pull-Artifacts {
    param([string[]]$RemotePaths, [string]$ScenarioDir)
    New-Item -ItemType Directory -Force -Path $ScenarioDir | Out-Null
    $local = @()
    foreach ($remotePath in $RemotePaths) {
        $pull = Run-Adb -CommandArgs @("pull", $remotePath, $ScenarioDir) -TimeoutSec 120 -IgnoreExitCode
        if ($pull.ExitCode -ne 0 -or $pull.TimedOut) {
            Log-Line -Level "WARN" -Message "Artifact pull failed path='$remotePath' exit=$($pull.ExitCode)"
            continue
        }
        $candidate = Join-Path $ScenarioDir (Split-Path -Path $remotePath -Leaf)
        if (Test-Path $candidate) { $local += $candidate }
    }
    return $local
}

function Read-JpegInfo {
    param([string]$Path)
    Add-Type -AssemblyName System.Drawing
    $stream = [System.IO.File]::OpenRead($Path)
    try {
        $image = [System.Drawing.Image]::FromStream($stream, $false, $false)
        try {
            $keys = New-Object System.Collections.Generic.List[string]
            foreach ($item in $image.PropertyItems) {
                switch ($item.Id) {
                    271 { [void]$keys.Add("Make") }
                    272 { [void]$keys.Add("Model") }
                    305 { [void]$keys.Add("Software") }
                    36867 { [void]$keys.Add("DateTimeOriginal") }
                }
            }
            return [pscustomobject]@{
                Width = $image.Width
                Height = $image.Height
                Size = (Get-Item $Path).Length
                ExifKeys = $keys.ToArray()
            }
        } finally {
            $image.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
}

function Test-PhotoOutput {
    param(
        [string]$Label,
        [string]$Scenario,
        [int]$ExpectedMin = 1,
        [switch]$AllowNonJpegArtifacts
    )
    $remote = @(Wait-RemoteArtifacts -Label $Label -MinCount $ExpectedMin -TimeoutSec 60)
    if ($remote.Count -lt $ExpectedMin) {
        Add-Result -Category "Capture" -Step $Scenario -Status "FAIL" -Details "Expected at least $ExpectedMin artifact(s), found $($remote.Count)"
        return
    }
    $scenarioDir = Join-Path $mediaDir $Scenario
    $local = @(Pull-Artifacts -RemotePaths $remote -ScenarioDir $scenarioDir)
    $jpeg = $local | Where-Object { $_ -match "\.(jpg|jpeg)$" } | Select-Object -First 1
    if ($jpeg) {
        $info = Read-JpegInfo -Path $jpeg
        $exifOk = $info.ExifKeys.Count -gt 0
        $status = if ($info.Width -gt 0 -and $info.Height -gt 0 -and $info.Size -gt 0 -and $exifOk) { "PASS" } else { "FAIL" }
        Add-Result -Category "Capture" -Step $Scenario -Status $status -Details "files=$($local.Count) jpeg=$($info.Width)x$($info.Height) size=$($info.Size) exif=$([string]::Join(',', $info.ExifKeys))" -Artifact $scenarioDir
    } else {
        $status = if ($AllowNonJpegArtifacts -and $local.Count -gt 0) { "PASS" } else { "FAIL" }
        $details = if ($status -eq "PASS") {
            "files=$($local.Count) no jpeg in set, non-jpeg artifacts pulled"
        } else {
            "files=$($local.Count) no jpeg artifact found"
        }
        Add-Result -Category "Capture" -Step $Scenario -Status $status -Details $details -Artifact $scenarioDir
    }
}

function Test-VideoOutput {
    param([string]$Label, [string]$Scenario)
    $publication = Wait-DurableLabeledMediaStoreOutput -Label $Label -TimeoutSec 120 -RequiredExtension ".mov"
    if ($publication.Status -eq "UNSUPPORTED") {
        Add-Result -Category "Video" -Step $Scenario -Status "SKIP" -Details $publication.Details
        return
    }
    if ($publication.Status -ne "PUBLISHED") {
        Add-Result -Category "Video" -Step $Scenario -Status "FAIL" -Details $publication.Details
        return
    }
    $row = $publication.Row
    if ($row.DisplayName -notlike "*.mov") {
        Add-Result -Category "Video" -Step $Scenario -Status "FAIL" -Details "Published output is not MOV: $($row.DisplayName)"
        return
    }
    $remote = @(Wait-RemoteArtifacts -Label $Label -MinCount 1 -TimeoutSec 90)
    if ($remote.Count -lt 1) {
        Add-Result -Category "Video" -Step $Scenario -Status "FAIL" -Details "No video artifact found for label '$Label'"
        return
    }
    $scenarioDir = Join-Path $mediaDir $Scenario
    $local = @(Pull-Artifacts -RemotePaths $remote -ScenarioDir $scenarioDir)
    $video = $local | Where-Object { $_ -match "\.mov$" } | Select-Object -First 1
    if (-not $video) {
        Add-Result -Category "Video" -Step $Scenario -Status "FAIL" -Details "Pulled files but no MOV container found" -Artifact $scenarioDir
        return
    }
    $size = (Get-Item $video).Length
    $stream = [System.IO.File]::OpenRead($video)
    try {
        $header = New-Object byte[] 12
        $read = $stream.Read($header, 0, $header.Length)
        $isIsoBmff = $read -ge 8 -and [System.Text.Encoding]::ASCII.GetString($header, 4, 4) -eq "ftyp"
    } finally {
        $stream.Dispose()
    }
    $mediaSizeMatches = $row.Size -eq $size
    $mimeAccepted = $row.MimeType -in @("video/quicktime", "video/mp4")
    $passed = $size -gt 100KB -and $isIsoBmff -and $mediaSizeMatches -and $mimeAccepted
    $details = "video=$(Split-Path $video -Leaf) size=$size mediaStoreSize=$($row.Size) pending=$($row.IsPending) mime=$($row.MimeType) ftyp=$isIsoBmff"
    Add-Result -Category "Video" -Step $Scenario -Status ($(if ($passed) { "PASS" } else { "FAIL" })) -Details $details -Artifact $scenarioDir
}

function Perf-Snapshot {
    param([string]$Name)
    $top = Run-AdbShell -Command "top -n 1 | grep -i '$PackageName' || top -n 1 | grep -i 'camera'" -TimeoutSec 30 -IgnoreExitCode
    $mem = Run-AdbShell -Command "dumpsys meminfo $PackageName" -TimeoutSec 40 -IgnoreExitCode
    $artifact = Save-Artifact -Name ("{0}.perf.txt" -f $Name) -Content (@("==== TOP ====", $top.Output, "", "==== MEMINFO ====", $mem.Output) -join [Environment]::NewLine)
    # A point-in-time top/meminfo dump is evidence, not a performance threshold. Never turn process
    # presence into a performance PASS.
    Add-Result -Category "Performance" -Step $Name -Status "SKIP" -Details "top and meminfo captured; no performance threshold was evaluated" -Artifact $artifact -Required $false
}

function Stop-CameraWatch {
    if (-not $script:CameraWatchMayBeActive) { return $true }
    $stop = Run-Adb -CommandArgs @("shell", "cmd", "media.camera", "watch", "stop") -TimeoutSec 10 -IgnoreExitCode
    $ok = -not $stop.TimedOut -and $stop.ExitCode -eq 0
    if ($ok) {
        $script:CameraWatchMayBeActive = $false
    } else {
        Log-Line -Level "WARN" -Message "Unable to prove camera watch stopped; exit=$($stop.ExitCode)"
    }
    return $ok
}

function Watch-3A {
    param([string]$Name)
    if ($SkipCameraWatch) {
        Add-Result -Category "Performance" -Step $Name -Status "SKIP" -Details "Camera-service watch skipped by parameter"
        return
    }

    Mark-DeviceMutation
    $watch = $null
    $stopOk = $false
    # A timed-out watch-start client may still have registered with camera service. Arm cleanup before
    # dispatch and always issue an explicit stop; Cleanup-State retries when this immediate stop fails.
    $script:CameraWatchMayBeActive = $true
    try {
        $watch = Run-Adb -CommandArgs @("shell", "cmd", "media.camera", "watch", "start", "-m", "3a") -TimeoutSec 5 -IgnoreExitCode
    } finally {
        $stopOk = Stop-CameraWatch
    }
    $artifact = Save-Artifact -Name ("{0}.3a.txt" -f $Name) -Content $watch.Output
    $status = if (-not $stopOk) {
        "FAIL"
    } elseif (-not $watch.TimedOut -and $watch.ExitCode -eq 0 -and $watch.Output -match "AE|AF|AWB") {
        "PASS"
    } else {
        "SKIP"
    }
    $details = if (-not $stopOk) {
        "Camera 3A watch stop could not be verified"
    } elseif ($status -eq "PASS") {
        "Observed explicit AE/AF/AWB output"
    } elseif ($watch.TimedOut) {
        "Camera 3A watch start did not terminate within the bounded probe; explicit stop succeeded; no PASS claimed"
    } else {
        "Camera 3A watch is unsupported or returned no observable AE/AF/AWB evidence; explicit stop succeeded"
    }
    Add-Result -Category "Performance" -Step $Name -Status $status -Details $details -Artifact $artifact
}

function Get-DeviceFreeSpace {
    param([Parameter(Mandatory)][ValidateSet("/data", "/sdcard")][string]$DevicePath)
    $df = Run-AdbShell -Command "df -k $DevicePath" -TimeoutSec 20 -IgnoreExitCode
    $line = ([regex]::Split($df.Output, "\r?\n") | Select-Object -Last 1)
    $parts = $line -split "\s+" | Where-Object { $_ }
    if ($df.TimedOut -or $df.ExitCode -ne 0 -or $parts.Count -lt 4) {
        return [pscustomobject]@{ AvailableMb = $null; Output = $df.Output }
    }
    $availableKb = $parts[3] -as [int64]
    if ($null -eq $availableKb) {
        return [pscustomobject]@{ AvailableMb = $null; Output = $df.Output }
    }
    return [pscustomobject]@{ AvailableMb = [long]($availableKb / 1024); Output = $df.Output }
}

function Df-AvailableMb {
    return (Get-DeviceFreeSpace -DevicePath "/sdcard").AvailableMb
}

function Stop-LowStorageFillProcess {
    $pidRead = Run-AdbShell -Command "if [ -f $script:LowStorageFillPidPath ]; then cat $script:LowStorageFillPidPath; fi" -TimeoutSec 20 -IgnoreExitCode
    if ($pidRead.TimedOut -or $pidRead.ExitCode -ne 0 -or
        $pidRead.Output -match "(?i)device\s+offline|no devices?/emulators?|permission denied|transport.*error") {
        throw "fill-process pid read failed: exit=$($pidRead.ExitCode) output=$($pidRead.Output)"
    }
    $pidText = $pidRead.StdOut.Trim()
    if ([string]::IsNullOrWhiteSpace($pidText)) { return }
    if ($pidText -notmatch '^\d+$') {
        throw "invalid fill-process pid value: $pidText"
    }

    $cmdline = Run-AdbShell -Command "if [ -r /proc/$pidText/cmdline ]; then tr '\000' ' ' < /proc/$pidText/cmdline; fi" -TimeoutSec 20 -IgnoreExitCode
    if ($cmdline.TimedOut -or $cmdline.ExitCode -ne 0) {
        throw "fill-process identity read failed: exit=$($cmdline.ExitCode) output=$($cmdline.Output)"
    }
    if ([string]::IsNullOrWhiteSpace($cmdline.StdOut)) {
        $removeStalePid = Run-AdbShell -Command "rm -f $script:LowStorageFillPidPath" -TimeoutSec 20 -IgnoreExitCode
        if ($removeStalePid.TimedOut -or $removeStalePid.ExitCode -ne 0) {
            throw "stale fill-process pid cleanup failed"
        }
        return
    }
    if ($cmdline.StdOut -notmatch [regex]::Escape($script:LowStorageFillPath)) {
        throw "refusing to kill pid $pidText because its cmdline does not own the run-specific fill path: $($cmdline.StdOut)"
    }

    $stopCommand = 'kill -TERM {0} 2>/dev/null || true; i=0; while kill -0 {0} 2>/dev/null && [ $i -lt 20 ]; do i=$((i+1)); sleep 0.1; done; if kill -0 {0} 2>/dev/null; then kill -KILL {0} 2>/dev/null || true; sleep 0.2; fi; if kill -0 {0} 2>/dev/null; then echo STILL_RUNNING; exit 6; else rm -f {1}; echo STOPPED; fi' -f $pidText, $script:LowStorageFillPidPath
    $stop = Run-AdbShell -Command $stopCommand -TimeoutSec 20 -IgnoreExitCode
    if ($stop.TimedOut -or $stop.ExitCode -ne 0 -or $stop.StdOut -notmatch '(?m)^STOPPED\s*$') {
        throw "fill-process stop was not verified: exit=$($stop.ExitCode) output=$($stop.Output)"
    }
}

function Assert-ThermalReady {
    param(
        [Parameter(Mandatory)][string]$Step,
        [Parameter(Mandatory)][string]$ArtifactPrefix,
        [ValidateRange(1, 20)][int]$ReadingCount = 1,
        [ValidateRange(0, 3600)][int]$IntervalSec = 0
    )

    $observed = New-Object System.Collections.Generic.List[int]
    for ($i = 1; $i -le $ReadingCount; $i++) {
        $thermal = Run-AdbShell -Command "dumpsys thermalservice" -TimeoutSec 30 -IgnoreExitCode
        $artifact = Save-Artifact -Name ("{0}_{1:D2}.txt" -f $ArtifactPrefix, $i) -Content $thermal.Output
        $match = [regex]::Match($thermal.Output, "(?m)^\s*Thermal Status:\s*(\d+)\s*$")
        if ($thermal.TimedOut -or $thermal.ExitCode -ne 0 -or -not $match.Success) {
            Add-Result -Category "Preflight" -Step $Step -Status "FAIL" -Details "Unable to read Thermal Status on sample $i" -Artifact $artifact
            throw "$Step sample $i was unavailable."
        }
        $status = [int]$match.Groups[1].Value
        [void]$observed.Add($status)
        if ($status -ne 0) {
            Add-Result -Category "Preflight" -Step $Step -Status "FAIL" -Details "Sample $i reported Thermal Status $status; required 0" -Artifact $artifact
            throw "$Step sample $i reported status $status."
        }
        if ($i -lt $ReadingCount -and $IntervalSec -gt 0) {
            Start-Sleep -Seconds $IntervalSec
        }
    }
    Add-Result -Category "Preflight" -Step $Step -Status "PASS" -Details "status-0 readings=$($observed.Count) intervalSec=$IntervalSec"
}

function Invoke-PreMutationPreflight {
    $harnessModeDetails = if ($SafeSmoke) {
        "SafeSmoke enabled: device-mutating scenarios disabled"
    } else {
        "Full/custom scenario selection"
    }
    Add-Result -Category "Preflight" -Step "Harness Mode" -Status "PASS" -Details $harnessModeDetails -Required $false

    Assert-ThermalReady -Step "Thermal Readiness" -ArtifactPrefix "thermal_preflight" `
        -ReadingCount $ThermalReadyReadingCount -IntervalSec $ThermalReadyIntervalSec

    foreach ($check in @(
        [pscustomobject]@{ Path = "/data"; MinimumMb = [long]$MinimumDataFreeMb; Step = "/data Free Space" },
        [pscustomobject]@{ Path = "/sdcard"; MinimumMb = [long]$MinimumSdcardFreeMb; Step = "/sdcard Free Space" }
    )) {
        $space = Get-DeviceFreeSpace -DevicePath $check.Path
        $artifact = Save-Artifact -Name (($check.Path.TrimStart("/") -replace "[^A-Za-z0-9]+", "_") + "_free_space.txt") -Content $space.Output
        if ($null -eq $space.AvailableMb) {
            Add-Result -Category "Preflight" -Step $check.Step -Status "FAIL" -Details "Unable to parse df output; required minimum=$($check.MinimumMb)MB" -Artifact $artifact
            throw "Unable to determine free space for $($check.Path)."
        }
        if ($space.AvailableMb -lt $check.MinimumMb) {
            Add-Result -Category "Preflight" -Step $check.Step -Status "FAIL" -Details "available=$($space.AvailableMb)MB minimum=$($check.MinimumMb)MB" -Artifact $artifact
            throw "Insufficient free space on $($check.Path)."
        }
        Add-Result -Category "Preflight" -Step $check.Step -Status "PASS" -Details "available=$($space.AvailableMb)MB minimum=$($check.MinimumMb)MB" -Artifact $artifact
    }
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
    $dump = Run-AdbShell -Command "dumpsys package $PackageName" -TimeoutSec 40 -IgnoreExitCode
    if ($dump.TimedOut -or $dump.ExitCode -ne 0) { return $null }
    $permissionPattern = "^\s*$([regex]::Escape($Permission)):\s+granted=(true|false)"
    $line = $dump.Output -split "`r?`n" |
        Where-Object { $_ -match $permissionPattern } |
        Select-Object -First 1
    if ($line -match $permissionPattern) {
        return ($Matches[1] -eq "true")
    }
    return $null
}

function Save-OriginalPermissionStates {
    param([int]$SdkInt)
    $script:OriginalPermissionStates.Clear()
    foreach ($permission in (Get-HarnessRuntimePermissions -SdkInt $SdkInt)) {
        $state = Get-PermissionGrantState -Permission $permission
        if ($null -eq $state) {
            Log-Line -Level "WARN" -Message "Unable to snapshot permission state for $permission; it will not be mutated"
            continue
        }
        $script:OriginalPermissionStates[$permission] = [bool]$state
    }
    $script:PermissionSnapshotCaptured = $script:OriginalPermissionStates.Count -gt 0
}

function Restore-OriginalPermissions {
    if (-not $script:PermissionsMutated) {
        Add-Result -Category "Permission" -Step "Permission Restoration" -Status "SKIP" -Details "No permission mutation was attempted" -Required $false
        return
    }
    if (-not $script:PermissionSnapshotCaptured) {
        Add-Result -Category "Permission" -Step "Permission Restoration" -Status "FAIL" -Details "Permission mutation was attempted without an original permission snapshot; restoration cannot be proven"
        return
    }

    $failures = New-Object System.Collections.Generic.List[string]
    foreach ($permission in $script:OriginalPermissionStates.Keys) {
        $expectedGranted = [bool]$script:OriginalPermissionStates[$permission]
        $verb = if ($expectedGranted) { "grant" } else { "revoke" }
        $restore = Run-AdbShell -Command "pm $verb $PackageName $permission" -TimeoutSec 20 -IgnoreExitCode
        if ($restore.TimedOut -or $restore.ExitCode -ne 0) {
            [void]$failures.Add("$permission command exit=$($restore.ExitCode)")
            continue
        }
        $actual = Get-PermissionGrantState -Permission $permission
        if ($null -eq $actual -or [bool]$actual -ne $expectedGranted) {
            [void]$failures.Add("$permission expected=$expectedGranted actual=$actual")
        }
    }

    if ($failures.Count -eq 0) {
        $script:PermissionsMutated = $false
        Add-Result -Category "Permission" -Step "Permission Restoration" -Status "PASS" -Details "Restored all snapshotted runtime permissions"
    } else {
        Add-Result -Category "Permission" -Step "Permission Restoration" -Status "FAIL" -Details ($failures -join "; ")
    }
}

function Resolve-ItemsStatus {
    param([object[]]$Items)
    if ($Items.Count -eq 0) { return "SKIP" }
    if (@($Items | Where-Object { $_.Status -eq "FAIL" }).Count -gt 0) { return "FAIL" }
    # Required coverage cannot be hidden by an unrelated PASS in the same category.
    if (@($Items | Where-Object { $_.Required -and $_.Status -eq "SKIP" }).Count -gt 0) { return "SKIP" }
    if (@($Items | Where-Object { $_.Required -and $_.Status -eq "PASS" }).Count -gt 0) { return "PASS" }
    return "SKIP"
}

function Resolve-CategoryStatus {
    param([string[]]$Categories)
    $items = @(
        $script:Results | Where-Object {
            $Categories -contains $_.Category
        }
    )
    return Resolve-ItemsStatus -Items $items
}

function Write-Reports {
    # Windows PowerShell 5.1 throws "Argument types do not match" for @($genericList).
    # List<T>.ToArray() is deterministic across PS5/PS7 and preserves every result row.
    $overall = Resolve-ItemsStatus -Items $script:Results.ToArray()
    $preflight = Resolve-CategoryStatus -Categories @("Preflight")
    $launch = Resolve-CategoryStatus -Categories @("Launch")
    $capture = Resolve-CategoryStatus -Categories @("Capture")
    $video = Resolve-CategoryStatus -Categories @("Video")
    $performance = Resolve-CategoryStatus -Categories @("Performance")
    $evidence = Resolve-CategoryStatus -Categories @("Evidence")
    $stability = Resolve-CategoryStatus -Categories @("Permission", "LowStorage", "LowBattery", "Stress", "CrashRecovery", "EdgeCases", "Cleanup")

    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("# Camera QA Summary")
    $lines.Add("")
    $lines.Add("- Run ID: $runId")
    $lines.Add("- Package: $PackageName")
    $lines.Add("- Overall: $overall")
    $lines.Add("- Preflight: $preflight")
    $lines.Add("- Launch: $launch")
    $lines.Add("- Capture: $capture")
    $lines.Add("- Video: $video")
    $lines.Add("- Performance: $performance")
    $lines.Add("- Evidence: $evidence")
    $lines.Add("- Stability: $stability")
    $lines.Add("")
    $lines.Add("| Category | Step | Required | Status | Details | Artifact |")
    $lines.Add("| --- | --- | --- | --- | --- | --- |")
    foreach ($result in $script:Results) {
        $detail = ($result.Details -replace "\|", "/")
        $artifact = ($result.Artifact -replace "\|", "/")
        $lines.Add("| $($result.Category) | $($result.Step) | $($result.Required) | $($result.Status) | $detail | $artifact |")
    }
    Set-Content -Path $reportPath -Value $lines -Encoding UTF8
    $script:Results | ConvertTo-Json -Depth 4 | Set-Content -Path $jsonPath -Encoding UTF8
}

function Invoke-CleanupAction {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][scriptblock]$Action
    )
    try {
        & $Action
        Log-Line -Level "INFO" -Message "Cleanup completed: $Name"
        return $true
    } catch {
        Log-Line -Level "WARN" -Message "Cleanup failed: $Name :: $($_.Exception.Message)"
        return $false
    }
}

function Cleanup-LowStorageFill {
    if (-not $script:LowStorageFillMayExist) { return $true }

    $fillProcessStopped = Invoke-CleanupAction -Name "low-storage fill process stop" -Action {
        Stop-LowStorageFillProcess
    }
    if (-not $fillProcessStopped) {
        Add-Result -Category "Cleanup" -Step "Fill Process Stop" -Status "FAIL" -Details "Unable to prove that the run-specific dd process terminated"
    } else {
        Add-Result -Category "Cleanup" -Step "Fill Process Stop" -Status "PASS" -Details "No active run-specific dd process remains"
    }

    $fillRemoved = Invoke-CleanupAction -Name "low-storage fill removal" -Action {
        $remove = Run-AdbShell -Command "rm -f $script:LowStorageFillPath $script:LowStorageFillPidPath" -TimeoutSec 60 -IgnoreExitCode
        if ($remove.TimedOut -or $remove.ExitCode -ne 0) {
            throw "fill-file cleanup exit=$($remove.ExitCode)"
        }
        $verifyRemoval = Run-AdbShell -Command "if [ -e $script:LowStorageFillPath ] || [ -e $script:LowStorageFillPidPath ]; then echo PRESENT; exit 3; else echo ABSENT; fi" -TimeoutSec 20 -IgnoreExitCode
        if ($verifyRemoval.TimedOut -or $verifyRemoval.ExitCode -ne 0 -or
            $verifyRemoval.StdOut.Trim() -ne "ABSENT") {
            throw "fill-file/pid removal not verified: exit=$($verifyRemoval.ExitCode) output=$($verifyRemoval.Output)"
        }
        $freeAfterCleanup = Df-AvailableMb
        if ($null -eq $freeAfterCleanup) {
            throw "unable to verify free space after fill cleanup"
        }
        if ($null -ne $script:LowStorageFreeBeforeMb) {
            $recoveryFloorMb = [Math]::Max(0L, [long]$script:LowStorageFreeBeforeMb - 256L)
            if ($freeAfterCleanup -lt $recoveryFloorMb) {
                throw "free-space recovery not verified: before=$($script:LowStorageFreeBeforeMb)MB after=${freeAfterCleanup}MB floor=${recoveryFloorMb}MB"
            }
        }
        $script:LowStorageFillMayExist = $false
        $script:LowStorageFreeBeforeMb = $null
    }
    if (-not $fillRemoved) {
        Add-Result -Category "Cleanup" -Step "Fill File Removal" -Status "FAIL" -Details "Unable to remove $script:LowStorageFillPath"
    } else {
        Add-Result -Category "Cleanup" -Step "Fill File Removal" -Status "PASS" -Details "Run-specific fill artifacts are absent and free space recovered"
    }
    return ($fillProcessStopped -and $fillRemoved)
}

function Cleanup-State {
    if ($script:CleanupCompleted -or -not $script:DeviceReadyForCleanup -or -not $script:DeviceMutationStarted) { return }

    # Each cleanup is isolated so a disconnected device or one failing shell command cannot prevent
    # restoration of the remaining state. This function is called first in the top-level finally block,
    # including PowerShell pipeline interruption/Control-C unwinding.
    if ($script:CameraWatchMayBeActive) {
        $watchStopped = Invoke-CleanupAction -Name "camera service watch stop" -Action {
            if (-not (Stop-CameraWatch)) { throw "camera watch stop was not verified" }
        }
        if (-not $watchStopped) {
            Add-Result -Category "Cleanup" -Step "Camera Watch Stop" -Status "FAIL" -Details "Unable to prove camera-service watch stopped"
        } else {
            Add-Result -Category "Cleanup" -Step "Camera Watch Stop" -Status "PASS" -Details "Camera-service watch stop verified"
        }
    }

    if ($script:TargetAppTouched) {
        $appStopped = Invoke-CleanupAction -Name "recording stop and app process termination" -Action {
            $stop = Run-Adb -CommandArgs @(
                "shell", "am", "broadcast",
                "-a", "$PackageName.DEBUG_COMMAND",
                "-n", $DebugReceiverComponent,
                "--es", "debug_action", "stop_recording",
                "--es", "debug_validation_label", "qa_cleanup_$runId"
            ) -TimeoutSec 20 -IgnoreExitCode
            if ($stop.TimedOut) {
                Log-Line -Level "WARN" -Message "Cleanup stop_recording broadcast timed out; force-stop will terminate the target process"
            }
            $forceStop = Run-Adb -CommandArgs @("shell", "am", "force-stop", $PackageName) -TimeoutSec 20 -IgnoreExitCode
            if ($forceStop.TimedOut -or $forceStop.ExitCode -ne 0) {
                throw "force-stop exit=$($forceStop.ExitCode)"
            }
            $remainingPid = Run-Adb -CommandArgs @("shell", "pidof", "-s", $PackageName) -TimeoutSec 10 -IgnoreExitCode
            if ($remainingPid.TimedOut -or
                $remainingPid.Output -match "(?i)device\s+offline|no devices?/emulators?|device not found|transport.*error|connection.*closed") {
                throw "pid verification transport failed: exit=$($remainingPid.ExitCode) output=$($remainingPid.Output)"
            }
            # Android pidof normally returns 1 when no process exists; any other nonzero result is
            # accepted only when stderr/stdout are both empty and therefore contain no transport error.
            if ($remainingPid.ExitCode -notin @(0, 1)) {
                throw "pid verification exit=$($remainingPid.ExitCode) output=$($remainingPid.Output)"
            }
            if ($remainingPid.ExitCode -eq 1 -and -not [string]::IsNullOrWhiteSpace($remainingPid.Output)) {
                throw "pid verification returned ambiguous exit-1 output: $($remainingPid.Output)"
            }
            if (-not [string]::IsNullOrWhiteSpace($remainingPid.StdErr)) {
                throw "pid verification returned stderr: $($remainingPid.StdErr)"
            }
            if (-not [string]::IsNullOrWhiteSpace($remainingPid.StdOut)) {
                throw "target process still alive after force-stop: pid=$($remainingPid.StdOut.Trim())"
            }
        }
        if (-not $appStopped) {
            Add-Result -Category "Cleanup" -Step "Target Process Stop" -Status "FAIL" -Details "Unable to guarantee that recording/app workers terminated"
        } else {
            Add-Result -Category "Cleanup" -Step "Target Process Stop" -Status "PASS" -Details "Stop fence, force-stop, and empty pid verification completed"
        }
    }

    if ($script:BatteryOverrideActive) {
        $batteryReset = Invoke-CleanupAction -Name "battery override reset" -Action {
            $reset = Run-AdbShell -Command "dumpsys battery reset" -TimeoutSec 20 -IgnoreExitCode
            if ($reset.TimedOut -or $reset.ExitCode -ne 0) {
                throw "dumpsys battery reset exit=$($reset.ExitCode)"
            }
            $script:BatteryOverrideActive = $false
        }
        if (-not $batteryReset) {
            Add-Result -Category "Cleanup" -Step "Battery Reset" -Status "FAIL" -Details "Unable to reset battery override"
        } else {
            Add-Result -Category "Cleanup" -Step "Battery Reset" -Status "PASS" -Details "Battery service reset command completed"
        }
    }

    if ($script:LowStorageFillMayExist) {
        [void](Cleanup-LowStorageFill)
    }

    if ($script:PermissionsMutated) {
        $permissionsRestored = Invoke-CleanupAction -Name "runtime permission restoration" -Action {
            Restore-OriginalPermissions
            if ($script:PermissionsMutated) {
                throw "runtime permission restoration was not verified"
            }
        }
        if (-not $permissionsRestored) {
            Add-Result -Category "Cleanup" -Step "Permission Restoration Fence" -Status "FAIL" -Details "Unable to prove restoration of all snapshotted permissions"
        }
    }
    $script:CleanupCompleted = $true
}

try {
    Log-Line -Level "INFO" -Message "Starting camera QA run in $runDir"
    $serials = @(Get-DeviceSerials)
    if ([string]::IsNullOrWhiteSpace($Serial)) {
        if ($serials.Count -eq 0) { throw "No adb device connected." }
        if ($serials.Count -gt 1) { throw "Multiple adb devices found. Re-run with -Serial." }
        $Serial = $serials[0]
    } elseif ($serials -notcontains $Serial) {
        throw "Requested serial '$Serial' is not connected."
    }
    $devices = Run-Adb -CommandArgs @("devices") -TimeoutSec 20
    $model = (Run-AdbShell -Command "getprop ro.product.model" -TimeoutSec 10).Output.Trim()
    $release = (Run-AdbShell -Command "getprop ro.build.version.release" -TimeoutSec 10).Output.Trim()
    $sdk = (Run-AdbShell -Command "getprop ro.build.version.sdk" -TimeoutSec 10).Output.Trim()
    $sdkInt = 0
    if (-not [int]::TryParse($sdk, [ref]$sdkInt)) {
        throw "Unable to parse Android SDK level '$sdk'."
    }
    $deviceArtifact = Save-Artifact -Name "device_info.txt" -Content (@("adb devices", $devices.Output, "", "model=$model", "android=$release", "sdk=$sdk", "serial=$Serial") -join [Environment]::NewLine)
    Add-Result -Category "Launch" -Step "Device Connection" -Status "PASS" -Details "serial=$Serial model=$model android=$release sdk=$sdk" -Artifact $deviceArtifact

    $packagePath = Run-AdbShell -Command "pm path $PackageName" -TimeoutSec 10 -IgnoreExitCode
    if ($packagePath.Output -match "package:") {
        Add-Result -Category "Launch" -Step "Package Installed" -Status "PASS" -Details ($packagePath.Output.Trim())
    } else {
        Add-Result -Category "Launch" -Step "Package Installed" -Status "FAIL" -Details "Package $PackageName is not installed"
        throw "Package $PackageName is not installed."
    }

    # All configured readiness checks run before the first command that can change device or app
    # state. DeviceReadyForCleanup is armed only after this read-only gate succeeds.
    Invoke-PreMutationPreflight
    $script:DeviceReadyForCleanup = $true

    $declaredRuntimePermissions = @(Get-HarnessRuntimePermissions -SdkInt $sdkInt)
    if ($SkipPermissionMutation) {
        Add-Result -Category "Launch" -Step "Permission Prime" -Status "SKIP" -Details "Runtime permission snapshot and mutation skipped by parameter"
    } else {
        Save-OriginalPermissionStates -SdkInt $sdkInt
        $permissionPrimeFailures = New-Object System.Collections.Generic.List[string]
        foreach ($permission in $declaredRuntimePermissions) {
            if (-not $script:OriginalPermissionStates.ContainsKey($permission)) {
                [void]$permissionPrimeFailures.Add("$permission original-state unavailable")
                continue
            }
            # Arm restoration before every pm mutation attempt, including one that times out.
            $script:PermissionsMutated = $true
            Mark-DeviceMutation
            $grant = Run-AdbShell -Command "pm grant $PackageName $permission" -TimeoutSec 20 -IgnoreExitCode
            if ($grant.TimedOut -or $grant.ExitCode -ne 0) {
                [void]$permissionPrimeFailures.Add("$permission exit=$($grant.ExitCode)")
                continue
            }
            $actual = Get-PermissionGrantState -Permission $permission
            if ($actual -ne $true) {
                [void]$permissionPrimeFailures.Add("$permission not granted after command")
            }
        }
        $permissionPrimeStatus = if ($permissionPrimeFailures.Count -eq 0) { "PASS" } else { "FAIL" }
        $permissionPrimeDetails = "declared runtime permissions=" + ($declaredRuntimePermissions -join ",")
        if ($permissionPrimeFailures.Count -gt 0) {
            $permissionPrimeDetails += "; failures=" + ($permissionPrimeFailures -join "; ")
        }
        Add-Result -Category "Launch" -Step "Permission Prime" -Status $permissionPrimeStatus -Details $permissionPrimeDetails
    }

    if ($SkipGenericIntents) {
        Add-Result -Category "Launch" -Step "Generic Camera Intents" -Status "SKIP" -Details "Generic camera intent checks skipped by parameter"
    } else {
        foreach ($action in @("android.media.action.IMAGE_CAPTURE", "android.media.action.VIDEO_CAPTURE", "android.media.action.STILL_IMAGE_CAMERA", "android.media.action.VIDEO_CAMERA")) {
            if ($action -in @("android.media.action.IMAGE_CAPTURE", "android.media.action.VIDEO_CAPTURE")) {
                Add-Result -Category "Launch" -Step "Intent $action" -Status "SKIP" -Details "Not declared by NeoProCamX; no unrelated external capture activity was launched" -Required $false
                continue
            }

            Mark-TargetAppInteraction
            $launch = Run-Adb -CommandArgs @("shell", "am", "start", "-a", $action, "-p", $PackageName) -TimeoutSec 20 -IgnoreExitCode
            $artifact = Save-Artifact -Name ("intent_{0}.txt" -f ($action -replace "[^A-Za-z0-9]+", "_")) -Content $launch.Output
            $deliveredToTop = $launch.Output -match "Activity not started, intent has been delivered to currently running top-most instance"
            $launchRejected = $launch.TimedOut -or $launch.ExitCode -ne 0 -or
                $launch.Output -match "(?i)error|exception|unable to resolve|not found" -or
                ($launch.Output -match "(?i)activity not started" -and -not $deliveredToTop)
            $foreground = $false
            if (-not $launchRejected) {
                $foreground = Wait-TopPackage -ExpectedPackage $PackageName -TimeoutSec 20
            }
            $status = if (-not $launchRejected -and $foreground) { "PASS" } else { "FAIL" }
            $outputSummary = (($launch.Output -split "`r?`n" | Select-Object -First 2) -join " ").Trim()
            if ([string]::IsNullOrWhiteSpace($outputSummary)) { $outputSummary = "no am start output" }
            Add-Result -Category "Launch" -Step "Intent $action" -Status $status -Details "$outputSummary; packageScoped=true foreground=$foreground" -Artifact $artifact
        }
    }

    if ($SkipExplicitAppLaunch) {
        Add-Result -Category "Launch" -Step "Explicit App Launch" -Status "SKIP" -Details "Explicit component launch skipped by parameter"
    } else {
        $explicit = Start-App
        $explicitArtifact = Save-Artifact -Name "explicit_launch.txt" -Content $explicit.Output
        $explicitForeground = Wait-TopPackage -ExpectedPackage $PackageName -TimeoutSec 20
        $explicitOk = -not $explicit.TimedOut -and $explicit.ExitCode -eq 0 -and $explicitForeground
        $explicitDetails = if ($explicitOk) { Get-TopActivityLine } else { "Launch command or foreground verification failed" }
        Add-Result -Category "Launch" -Step "Explicit App Launch" -Status ($(if ($explicitOk) { "PASS" } else { "FAIL" })) -Details $explicitDetails -Artifact $explicitArtifact
    }

    if ($script:TargetAppTouched) {
        $activityDump = Run-AdbShell -Command "dumpsys activity activities" -TimeoutSec 40 -IgnoreExitCode
        $activityArtifact = Save-Artifact -Name "activity_dump.txt" -Content $activityDump.Output
        $activityVerified = -not $activityDump.TimedOut -and $activityDump.ExitCode -eq 0 -and
            $activityDump.Output -match [regex]::Escape($PackageName)
        Add-Result -Category "Launch" -Step "Activity Dump Verification" -Status ($(if ($activityVerified) { "PASS" } else { "FAIL" })) -Details "Checked target package after launch interaction" -Artifact $activityArtifact
    } else {
        Add-Result -Category "Launch" -Step "Activity Dump Verification" -Status "SKIP" -Details "No target-app launch was attempted" -Required $false
    }

    $mediaCamera = Run-AdbShell -Command "dumpsys media.camera" -TimeoutSec 60 -IgnoreExitCode
    $mediaCameraArtifact = Save-Artifact -Name "media_camera.txt" -Content $mediaCamera.Output
    $mediaCameraDetails = if (-not $mediaCamera.TimedOut -and $mediaCamera.ExitCode -eq 0) {
        "Camera service metadata captured as evidence; no behavioral or performance threshold evaluated"
    } else {
        "Camera service metadata was unavailable; no behavioral or performance claim made"
    }
    Add-Result -Category "Evidence" -Step "Camera Metadata Snapshot" -Status "SKIP" -Details $mediaCameraDetails -Artifact $mediaCameraArtifact -Required $false
    Watch-3A -Name "camera_3a_watch"

    if ($SkipBaselinePhotos) {
        Add-Result -Category "Capture" -Step "photo_standard" -Status "SKIP" -Details "Baseline photo capture skipped by parameter"
    } else {
        Assert-ThermalReady -Step "Thermal Before Standard Photo" -ArtifactPrefix "thermal_before_photo"
        Clear-Logcat -Step "Photo Standard Logcat Clear"
        $photoLabel = Scenario-Label -Prefix "qa_photo"
        $photoOutcome = Invoke-SerializedPhotoCapture -Label $photoLabel -Preset "PHOTO"
        Save-Logcat -Name "photo_standard.logcat.txt" | Out-Null
        if ($photoOutcome.Status -eq "PUBLISHED") {
            Test-PhotoOutput -Label $photoLabel -Scenario "photo_standard"
        } else {
            Add-Result -Category "Capture" -Step "photo_standard" -Status ($(if ($photoOutcome.Status -eq "UNSUPPORTED") { "SKIP" } else { "FAIL" })) -Details $photoOutcome.Details
        }
    }

    if ($SkipHdrCapture) {
        Add-Result -Category "Capture" -Step "photo_hdr" -Status "SKIP" -Details "HDR photo capture skipped by parameter"
    } else {
        Assert-ThermalReady -Step "Thermal Before HDR Photo" -ArtifactPrefix "thermal_before_hdr"
        $hdrLabel = Scenario-Label -Prefix "qa_hdr"
        $hdrOutcome = Invoke-SerializedPhotoCapture -Label $hdrLabel -Preset "HDR" -AdditionalExtras @{ debug_capture_mode = "HDR" }
        if ($hdrOutcome.Status -eq "PUBLISHED") {
            Test-PhotoOutput -Label $hdrLabel -Scenario "photo_hdr"
        } else {
            Add-Result -Category "Capture" -Step "photo_hdr" -Status ($(if ($hdrOutcome.Status -eq "UNSUPPORTED") { "SKIP" } else { "FAIL" })) -Details $hdrOutcome.Details
        }
    }

    if ($SkipRawCapture) {
        Add-Result -Category "Capture" -Step "photo_raw" -Status "SKIP" -Details "RAW photo capture skipped by parameter"
    } else {
        Assert-ThermalReady -Step "Thermal Before RAW Photo" -ArtifactPrefix "thermal_before_raw_photo"
        $rawLabel = Scenario-Label -Prefix "qa_raw"
        $rawOutcome = Invoke-SerializedPhotoCapture -Label $rawLabel -Preset "RAW" -AdditionalExtras @{ debug_raw = $true }
        if ($rawOutcome.Status -eq "PUBLISHED") {
            Test-PhotoOutput -Label $rawLabel -Scenario "photo_raw" -AllowNonJpegArtifacts
        } else {
            Add-Result -Category "Capture" -Step "photo_raw" -Status ($(if ($rawOutcome.Status -eq "UNSUPPORTED") { "SKIP" } else { "FAIL" })) -Details $rawOutcome.Details
        }
    }

    if ($SkipFocus) {
        Add-Result -Category "Capture" -Step "Autofocus Trigger" -Status "SKIP" -Details "Autofocus trigger skipped by parameter"
    } else {
        $focusBroadcast = Debug-Broadcast -Extras @{ debug_preset = "PHOTO"; debug_action = "trigger_focus"; debug_validation_label = (Scenario-Label -Prefix "qa_focus") }
        Start-Sleep -Seconds 3
        if ($focusBroadcast.TimedOut -or $focusBroadcast.ExitCode -ne 0 -or
            $focusBroadcast.Output -match "(?i)securityexception|permission denial|unable to resolve|not found") {
            Add-Result -Category "Capture" -Step "Autofocus Trigger" -Status "FAIL" -Details "Debug command delivery failed; no autofocus claim"
        } else {
            Add-Result -Category "Capture" -Step "Autofocus Trigger" -Status "SKIP" -Details "Command delivered, but the harness has no deterministic AF-completion oracle; no behavior PASS claimed"
        }
    }

    if ($SkipFacingSwitch) {
        Add-Result -Category "Capture" -Step "Front Back Switch" -Status "SKIP" -Details "Front/back camera switch skipped by parameter"
    } else {
        $switchBroadcast = Debug-Broadcast -Extras @{ debug_action = "switch_facing"; debug_validation_label = (Scenario-Label -Prefix "qa_switch") }
        Start-Sleep -Seconds 4
        if ($switchBroadcast.TimedOut -or $switchBroadcast.ExitCode -ne 0 -or
            $switchBroadcast.Output -match "(?i)securityexception|permission denial|unable to resolve|not found") {
            Add-Result -Category "Capture" -Step "Front Back Switch" -Status "FAIL" -Details "Debug command delivery failed; no camera-switch claim"
        } else {
            Add-Result -Category "Capture" -Step "Front Back Switch" -Status "SKIP" -Details "Command delivered, but camera availability/facing completion was not independently observed"
        }
    }

    $videoScenarioDispatched = $false
    if ($SkipVideos) {
        Add-Result -Category "Video" -Step "video_standard" -Status "SKIP" -Details "Standard video scenario skipped by parameter"
    } elseif (-not $EnableLegacyMovVideoOracle) {
        Add-Result -Category "Video" -Step "video_standard" -Status "SKIP" -Details "Legacy public-MOV oracle is disabled by default because current NPV10 recording output is not compatible; opt in only for a build that publishes the legacy MOV artifact"
    } else {
        Assert-ThermalReady -Step "Thermal Before Legacy Video" -ArtifactPrefix "thermal_before_legacy_video"
        $videoLabel = Scenario-Label -Prefix "qa_video"
        $videoScenarioDispatched = $true
        $videoBroadcast = Debug-Broadcast -Extras @{ debug_preset = "VIDEO"; debug_action = "record_clip"; debug_duration_ms = [long]($VideoRecordDurationSec * 1000); debug_validation_label = $videoLabel }
        if ($videoBroadcast.TimedOut -or $videoBroadcast.ExitCode -ne 0 -or
            $videoBroadcast.Output -match "(?i)securityexception|permission denial|unable to resolve|not found") {
            Add-Result -Category "Video" -Step "video_standard" -Status "FAIL" -Details "record_clip command was not accepted"
        } else {
            Start-Sleep -Seconds ($VideoRecordDurationSec + 1)
            Test-VideoOutput -Label $videoLabel -Scenario "video_standard"
        }
    }

    Add-Result -Category "Video" -Step "video_log" -Status "SKIP" -Details "NeoLog is convert-only; the legacy LOG_VIDEO debug token is not an applicable live capture mode" -Required $false

    if ($SkipPerformanceVideo -or -not $EnablePerformanceVideoProbe) {
        $performanceSkipReason = if ($SkipPerformanceVideo) {
            "Recording-time evidence collection skipped by parameter"
        } else {
            "Recording-time evidence is opt-in; enable with -EnablePerformanceVideoProbe"
        }
        Add-Result -Category "Performance" -Step "Performance Video Probe" -Status "SKIP" -Details $performanceSkipReason -Required $false
    } else {
        Assert-ThermalReady -Step "Thermal Before Performance Video" -ArtifactPrefix "thermal_before_performance_video"
        Perf-Snapshot -Name "during_preview"
        $perfClipLabel = Scenario-Label -Prefix "qa_perfvideo"
        $videoScenarioDispatched = $true
        $perfVideoBroadcast = Debug-Broadcast -Extras @{ debug_preset = "VIDEO"; debug_action = "record_clip"; debug_duration_ms = [long]8000; debug_validation_label = $perfClipLabel }
        if ($perfVideoBroadcast.TimedOut -or $perfVideoBroadcast.ExitCode -ne 0 -or
            $perfVideoBroadcast.Output -match "(?i)securityexception|permission denial|unable to resolve|not found") {
            Add-Result -Category "Performance" -Step "Performance Video Probe" -Status "FAIL" -Details "record_clip command was not accepted; recording-time evidence was not collected"
        } else {
            Start-Sleep -Seconds 2
            Perf-Snapshot -Name "during_recording"
            Start-Sleep -Seconds 8
            $perfArtifacts = @(Wait-RemoteArtifacts -Label $perfClipLabel -MinCount 1 -TimeoutSec 30)
            $performanceLog = Save-Logcat -Name "performance_recording.logcat.txt"
            Add-Result -Category "Evidence" -Step "Performance Video Probe" -Status "SKIP" -Details "Recording probe completed with artifacts=$($perfArtifacts.Count); snapshots are evidence only and no performance threshold was evaluated" -Artifact $performanceLog -Required $false
        }
    }

    if ($videoScenarioDispatched) {
        Debug-Broadcast -Extras @{ debug_preset = "VIDEO"; debug_action = "stop_recording"; debug_validation_label = (Scenario-Label -Prefix "qa_stress_preroll_stop") } | Out-Null
        Start-Sleep -Seconds 3
    }

    $stressLabel = Scenario-Label -Prefix "qa_stress"
    if ($CaptureStressCount -le 0) {
        Add-Result -Category "Stress" -Step "Photo Stress x$CaptureStressCount" -Status "SKIP" -Details "No stress captures requested"
    } else {
        Assert-ThermalReady -Step "Thermal Before Photo Stress" -ArtifactPrefix "thermal_before_photo_stress"
        $stressCount = 0
        $stressTerminal = $null
        for ($i = 1; $i -le $CaptureStressCount; $i++) {
            $label = "{0}_{1:D2}" -f $stressLabel, $i
            $outcome = Invoke-SerializedPhotoCapture -Label $label -Preset "PHOTO"
            if ($outcome.Status -ne "PUBLISHED") {
                $stressTerminal = $outcome
                break
            }
            $stressCount += 1
        }
        $stressStatus = if ($stressCount -eq $CaptureStressCount) {
            "PASS"
        } elseif ($null -ne $stressTerminal -and $stressTerminal.Status -eq "UNSUPPORTED") {
            "SKIP"
        } else {
            "FAIL"
        }
        $stressDetails = "durablyPublished=$stressCount requested=$CaptureStressCount"
        if ($null -ne $stressTerminal) {
            $stressDetails += "; terminal=$($stressTerminal.Status): $($stressTerminal.Details); remaining shots were not dispatched"
        }
        Add-Result -Category "Stress" -Step "Photo Stress x$CaptureStressCount" -Status $stressStatus -Details $stressDetails
    }

    if ($SkipPermissionMutation) {
        Add-Result -Category "Permission" -Step "Camera Permission Revoke" -Status "SKIP" -Details "Permission mutation scenario skipped by parameter"
        Add-Result -Category "Permission" -Step "Camera Permission Grant" -Status "SKIP" -Details "Permission mutation scenario skipped by parameter"
    } elseif (-not $script:OriginalPermissionStates.ContainsKey("android.permission.CAMERA") -or
        -not $script:OriginalPermissionStates.ContainsKey("android.permission.RECORD_AUDIO")) {
        Add-Result -Category "Permission" -Step "Camera Permission Revoke" -Status "SKIP" -Details "Original camera/audio permission state was unavailable; unsafe mutation was not attempted"
        Add-Result -Category "Permission" -Step "Camera Permission Grant" -Status "SKIP" -Details "Original camera/audio permission state was unavailable; unsafe mutation was not attempted"
    } else {
        Clear-Logcat -Step "Permission Scenario Logcat Clear"
        $script:PermissionsMutated = $true
        Mark-DeviceMutation
        $cameraRevoke = Run-AdbShell -Command "pm revoke $PackageName android.permission.CAMERA" -TimeoutSec 20 -IgnoreExitCode
        Start-App | Out-Null
        Start-Sleep -Seconds 3
        $cameraAfterRevoke = Get-PermissionGrantState -Permission "android.permission.CAMERA"
        $revokeStatus = if (-not $cameraRevoke.TimedOut -and $cameraRevoke.ExitCode -eq 0 -and $cameraAfterRevoke -eq $false) { "PASS" } else { "SKIP" }
        Add-Result -Category "Permission" -Step "Camera Permission Revoke" -Status $revokeStatus -Details ($(if ($revokeStatus -eq "PASS") { "Revocation verified and app relaunched" } else { "Device/package did not expose a verifiable runtime revoke result" })) -Artifact (Save-Logcat -Name "permission_revoked.logcat.txt")
        $cameraGrant = Run-AdbShell -Command "pm grant $PackageName android.permission.CAMERA" -TimeoutSec 20 -IgnoreExitCode
        $audioGrant = Run-AdbShell -Command "pm grant $PackageName android.permission.RECORD_AUDIO" -TimeoutSec 20 -IgnoreExitCode
        $cameraAfterGrant = Get-PermissionGrantState -Permission "android.permission.CAMERA"
        $audioAfterGrant = Get-PermissionGrantState -Permission "android.permission.RECORD_AUDIO"
        $grantVerified = -not $cameraGrant.TimedOut -and $cameraGrant.ExitCode -eq 0 -and
            -not $audioGrant.TimedOut -and $audioGrant.ExitCode -eq 0 -and
            $cameraAfterGrant -eq $true -and $audioAfterGrant -eq $true
        Add-Result -Category "Permission" -Step "Camera Permission Grant" -Status ($(if ($grantVerified) { "PASS" } else { "FAIL" })) -Details ($(if ($grantVerified) { "Camera and microphone grants verified for remaining scenarios; original states will be restored in finally" } else { "Unable to verify camera/microphone grant" }))
    }

    if ($EnableLowStorageScenario -and -not $SkipLowStorage) {
        Assert-ThermalReady -Step "Thermal Before Low-Storage Scenario" -ArtifactPrefix "thermal_before_low_storage"
        $availableMb = Df-AvailableMb
        if ($null -eq $availableMb -or $availableMb -le $script:MinimumPostFillReserveMb) {
            Add-Result -Category "LowStorage" -Step "Low Storage Fill" -Status "SKIP" -Details "Unable to preserve the mandatory $($script:MinimumPostFillReserveMb)MB post-fill reserve"
        } else {
            $maxSafeFillMb = $availableMb - $script:MinimumPostFillReserveMb
            $fillMb = [Math]::Min([long]$LowStorageFillMb, [long]$maxSafeFillMb)
            Mark-DeviceMutation
            $script:LowStorageFillMayExist = $true
            $script:LowStorageFreeBeforeMb = [long]$availableMb
            # Record the actual dd PID. If the host adb client times out, finally can verify that PID's
            # cmdline owns this run's unique output path, terminate it, and prove free-space recovery.
            $remoteFillScript = "rm -f $script:LowStorageFillPath $script:LowStorageFillPidPath; dd if=/dev/zero of=$script:LowStorageFillPath bs=1M count=$fillMb & child=`$!; echo `$child > $script:LowStorageFillPidPath; wait `$child; status=`$?; rm -f $script:LowStorageFillPidPath; exit `$status"
            $fill = Run-AdbShell -Command "sh -c `"$remoteFillScript`"" -TimeoutSec 600 -IgnoreExitCode
            if ($fill.TimedOut -or $fill.ExitCode -ne 0 -or $fill.Output -match "(?i)permission denied|read-only file system|not found") {
                Add-Result -Category "LowStorage" -Step "Low Storage Fill" -Status "FAIL" -Details "Bounded fill command failed or timed out; cleanup will stop any recorded dd pid and verify recovery"
                throw "Low-storage fill command did not complete safely."
            } else {
                $postFillAvailableMb = Df-AvailableMb
                if ($null -eq $postFillAvailableMb -or $postFillAvailableMb -lt $script:MinimumPostFillReserveMb) {
                    Add-Result -Category "LowStorage" -Step "Low Storage Safety Reserve" -Status "FAIL" -Details "availableAfterMb=$postFillAvailableMb required>=$($script:MinimumPostFillReserveMb)MB"
                    throw "Low-storage scenario violated its mandatory post-fill safety reserve."
                }
                Add-Result -Category "LowStorage" -Step "Low Storage Fill" -Status "PASS" -Details "Created bounded ${fillMb}MB fill file; availableBeforeMb=$availableMb availableAfterMb=$postFillAvailableMb"
                $lowStorageLabel = Scenario-Label -Prefix "qa_lowstorage"
                $lowStorageOutcome = Invoke-SerializedPhotoCapture -Label $lowStorageLabel -Preset "PHOTO"
                $lowStorageStatus = if ($lowStorageOutcome.Status -eq "PUBLISHED") {
                    "PASS"
                } elseif ($lowStorageOutcome.Status -eq "UNSUPPORTED") {
                    "SKIP"
                } else {
                    "FAIL"
                }
                Add-Result -Category "LowStorage" -Step "Low Storage Capture Attempt" -Status $lowStorageStatus -Details $lowStorageOutcome.Details
                if (-not (Cleanup-LowStorageFill)) {
                    throw "Low-storage scenario cleanup could not be verified."
                }
            }
        }
    } else {
        $lowStorageSkipReason = if ($SkipLowStorage) {
            "Skipped by parameter"
        } else {
            "Destructive storage-fill scenario is opt-in; enable with -EnableLowStorageScenario"
        }
        Add-Result -Category "LowStorage" -Step "Low Storage Fill" -Status "SKIP" -Details $lowStorageSkipReason -Required $false
    }

    if ($EnableLowBatteryScenario -and -not $SkipLowBattery) {
        Assert-ThermalReady -Step "Thermal Before Low-Battery Scenario" -ArtifactPrefix "thermal_before_low_battery"
        # Mark active before the first mutation so finally resets even if the second command or the
        # subsequent capture is interrupted.
        Mark-DeviceMutation
        $script:BatteryOverrideActive = $true
        $batteryUnplug = Run-AdbShell -Command "dumpsys battery unplug" -TimeoutSec 20 -IgnoreExitCode
        $batteryLevel = Run-AdbShell -Command "dumpsys battery set level 5" -TimeoutSec 20 -IgnoreExitCode
        $batteryState = Run-AdbShell -Command "dumpsys battery" -TimeoutSec 20 -IgnoreExitCode
        $overrideVerified = -not $batteryUnplug.TimedOut -and $batteryUnplug.ExitCode -eq 0 -and
            -not $batteryLevel.TimedOut -and $batteryLevel.ExitCode -eq 0 -and
            $batteryState.Output -match "(?m)^\s*level:\s*5\s*$"
        if (-not $overrideVerified) {
            Add-Result -Category "LowBattery" -Step "Low Battery Capture Attempt" -Status "SKIP" -Details "Battery override was not verifiably supported; finally will still issue battery reset"
        } else {
            $lowBatteryLabel = Scenario-Label -Prefix "qa_lowbattery"
            $lowBatteryOutcome = Invoke-SerializedPhotoCapture -Label $lowBatteryLabel -Preset "PHOTO"
            $lowBatteryStatus = if ($lowBatteryOutcome.Status -eq "PUBLISHED") {
                "PASS"
            } elseif ($lowBatteryOutcome.Status -eq "UNSUPPORTED") {
                "SKIP"
            } else {
                "FAIL"
            }
            Add-Result -Category "LowBattery" -Step "Low Battery Capture Attempt" -Status $lowBatteryStatus -Details $lowBatteryOutcome.Details
        }
    } else {
        $lowBatterySkipReason = if ($SkipLowBattery) {
            "Skipped by parameter"
        } else {
            "Battery-service override scenario is opt-in; enable with -EnableLowBatteryScenario"
        }
        Add-Result -Category "LowBattery" -Step "Low Battery Capture Attempt" -Status "SKIP" -Details $lowBatterySkipReason -Required $false
    }

    $longRunLabel = Scenario-Label -Prefix "qa_longrun"
    if ($LongRunCaptureCount -le 0) {
        Add-Result -Category "Stress" -Step "Long Run Capture x$LongRunCaptureCount" -Status "SKIP" -Details "No long-run captures requested"
    } else {
        Assert-ThermalReady -Step "Thermal Before Long-Run Capture" -ArtifactPrefix "thermal_before_long_run"
        $longRunCount = 0
        $longRunTerminal = $null
        for ($i = 1; $i -le $LongRunCaptureCount; $i++) {
            $label = "{0}_{1:D3}" -f $longRunLabel, $i
            $outcome = Invoke-SerializedPhotoCapture -Label $label -Preset "PHOTO"
            if ($outcome.Status -ne "PUBLISHED") {
                $longRunTerminal = $outcome
                break
            }
            $longRunCount += 1
        }
        $longRunStatus = if ($longRunCount -eq $LongRunCaptureCount) {
            "PASS"
        } elseif ($null -ne $longRunTerminal -and $longRunTerminal.Status -eq "UNSUPPORTED") {
            "SKIP"
        } else {
            "FAIL"
        }
        $longRunDetails = "durablyPublished=$longRunCount requested=$LongRunCaptureCount"
        if ($null -ne $longRunTerminal) {
            $longRunDetails += "; terminal=$($longRunTerminal.Status): $($longRunTerminal.Details); remaining shots were not dispatched"
        }
        Add-Result -Category "Stress" -Step "Long Run Capture x$LongRunCaptureCount" -Status $longRunStatus -Details $longRunDetails
    }

    if ($SkipCrashRecovery) {
        Add-Result -Category "CrashRecovery" -Step "Force Stop And Relaunch" -Status "SKIP" -Details "Crash-recovery scenario skipped by parameter"
    } else {
        Mark-TargetAppInteraction
        $forceStop = Run-Adb -CommandArgs @("shell", "am", "force-stop", $PackageName) -TimeoutSec 10 -IgnoreExitCode
        Start-Sleep -Seconds 2
        Start-App | Out-Null
        Start-Sleep -Seconds 3
        $relaunchForeground = Wait-TopPackage -ExpectedPackage $PackageName -TimeoutSec 10
        $relaunchVerified = -not $forceStop.TimedOut -and $forceStop.ExitCode -eq 0 -and $relaunchForeground
        Add-Result -Category "CrashRecovery" -Step "Force Stop And Relaunch" -Status ($(if ($relaunchVerified) { "PASS" } else { "FAIL" })) -Details "forceStopExit=$($forceStop.ExitCode) foreground=$relaunchForeground"
    }

    if ($SkipPowerAndAppSwitch) {
        Add-Result -Category "EdgeCases" -Step "Lock Screen Toggle" -Status "SKIP" -Details "Power/keyguard mutation skipped by parameter"
        Add-Result -Category "EdgeCases" -Step "Background App Switching" -Status "SKIP" -Details "App-switch mutation skipped by parameter"
    } else {
        Mark-DeviceMutation
        Run-AdbShell -Command "input keyevent 26" -TimeoutSec 10 -IgnoreExitCode | Out-Null
        Start-Sleep -Seconds 1
        Run-AdbShell -Command "input keyevent 26" -TimeoutSec 10 -IgnoreExitCode | Out-Null
        Run-AdbShell -Command "wm dismiss-keyguard" -TimeoutSec 10 -IgnoreExitCode | Out-Null
        Add-Result -Category "EdgeCases" -Step "Lock Screen Toggle" -Status "SKIP" -Details "Input sequence dispatched, but no deterministic camera lifecycle/recovery evidence was collected"

        Run-AdbShell -Command "input keyevent KEYCODE_APP_SWITCH" -TimeoutSec 10 -IgnoreExitCode | Out-Null
        $backgrounded = Wait-TopPackageAbsent -UnexpectedPackage $PackageName -TimeoutSec 10
        Start-App | Out-Null
        Start-Sleep -Seconds 2
        $returnedForeground = Wait-TopPackage -ExpectedPackage $PackageName -TimeoutSec 10
        $backgroundSwitchVerified = $backgrounded -and $returnedForeground
        Add-Result -Category "EdgeCases" -Step "Background App Switching" -Status ($(if ($backgroundSwitchVerified) { "PASS" } else { "FAIL" })) -Details "backgroundObserved=$backgrounded foregroundAfterRelaunch=$returnedForeground"
    }

    if (-not $SkipIncomingCall) {
        Add-Result -Category "EdgeCases" -Step "Incoming Call Simulation" -Status "SKIP" -Details "ADB ACTION_CALL is an outgoing call and cannot safely prove an incoming-call flow on a physical device; no call was placed" -Required $false
    } else {
        Add-Result -Category "EdgeCases" -Step "Incoming Call Simulation" -Status "SKIP" -Details "Skipped by parameter" -Required $false
    }
}
catch {
    Log-Line -Level "FAIL" -Message $_.Exception.Message
    Add-Result -Category "Launch" -Step "Unhandled Failure" -Status "FAIL" -Details $_.Exception.Message
}
finally {
    try {
        Cleanup-State
    } catch {
        $script:TopLevelCleanupFailed = $true
        try {
            Add-Result -Category "Cleanup" -Step "Top-Level Cleanup" -Status "FAIL" -Details $_.Exception.Message
        } catch {}
        try { Log-Line -Level "WARN" -Message "Top-level cleanup failure: $($_.Exception.Message)" } catch {}
    }
    try {
        Write-Reports
        Log-Line -Level "INFO" -Message "Summary written to $reportPath"
    } catch {
        $script:ReportWriteFailed = $true
        try { Log-Line -Level "WARN" -Message "Unable to write final reports: $($_.Exception.Message)" } catch {}
    }
}

$hasFailures = @($script:Results | Where-Object { $_.Status -eq "FAIL" }).Count -gt 0
if ($hasFailures -or $script:TopLevelCleanupFailed -or $script:ReportWriteFailed) {
    exit 1
}
$hasRequiredSkips = @($script:Results | Where-Object { $_.Required -and $_.Status -eq "SKIP" }).Count -gt 0
if ($hasRequiredSkips) {
    exit 2
}
exit 0
