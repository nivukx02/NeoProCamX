param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [string]$ArtifactRoot = "artifacts/device-qa/lq034-cp210-thermal-valid",

    [ValidateRange(0, 6)]
    [int]$MaximumAdmissionThermalStatus = 1,

    [ValidateRange(45, 180)]
    [int]$TraceDurationSeconds = 55,

    [ValidateRange(60, 600)]
    [int]$InstrumentationTimeoutSeconds = 180,

    [ValidateSet("RecordingSafe", "DeepSystem")]
    [string]$TraceProfile = "RecordingSafe",

    [ValidateRange(16, 256)]
    [int]$TraceBufferMegabytes = 32,

    [ValidateRange(5, 30)]
    [int]$ThermalSamplePeriodSeconds = 10,

    [string]$TraceProcessorPath =
        "$env:USERPROFILE\.local\share\perfetto\prebuilts\trace_processor_shell-9ac8c04ca44cc22f.exe",

    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = "Stop"
$PackageName = "com.neoproc.neoproccamx"
$TestPackageName = "$PackageName.test"
$Runner = "$TestPackageName/androidx.test.runner.AndroidJUnitRunner"
$TestSelector = "$PackageName.ui.HighEndModeRecordingInstrumentedTest#repeatedForegroundRawBoundaryAndRecordingStress_recoversWithoutStaleOwnership"
$ResolvedRoot = [System.IO.Path]::GetFullPath((Join-Path $PWD $ArtifactRoot))
$TraceName = "lq034-cp210-thermal-valid.pftrace"
$RemoteTrace = "/data/misc/perfetto-traces/$TraceName"
$TraceCategories = if ($TraceProfile -eq "DeepSystem") {
    @("sched", "freq", "idle", "am", "wm", "gfx", "view", "binder_driver", "hal", "dalvik")
} else {
    # The recording-safe profile uses app trace markers plus SurfaceFlinger FrameTimeline. Camera,
    # video, gfx, view and scheduler atrace are deliberately reserved for DeepSystem: this vendor
    # emits enough per-frame metadata in those categories to fill 64 MiB in 17 seconds and measurably
    # perturb the same full-sensor 4K25 producer being measured. Exact camera cadence remains
    # independently proven by the app's sensor-timestamp ledger captured in the same run.
    @("app", "surfaceflinger.frametimeline", "am", "wm")
}
$RemoteTraceConfig = "/data/misc/perfetto-configs/lq034-cp210-recording-safe.pbtxt"

if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    throw "ADB executable was not found: $AdbPath"
}
New-Item -ItemType Directory -Force -Path $ResolvedRoot | Out-Null

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [switch]$AllowFailure
    )
    # adb writes successful progress (notably `adb pull`) to stderr. Windows PowerShell promotes
    # native stderr to ErrorRecord under a script-level Stop preference, so always relax the
    # preference around the native invocation and decide success from the real process exit code.
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = @(& $AdbPath -s $Serial @Arguments 2>&1)
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if (-not $AllowFailure -and $code -ne 0) {
        throw "ADB failed (exit=$code): $($Arguments -join ' ')`n$($output -join "`n")"
    }
    return @($output)
}

function Get-RemotePackageIdentity {
    param([Parameter(Mandatory = $true)][string]$Package)
    $pathLine = @(Invoke-Adb -Arguments @("shell", "pm", "path", $Package)) |
        Where-Object { $_ -match "^package:" } |
        Select-Object -First 1
    if (-not $pathLine) { throw "Package is not installed: $Package" }
    $remotePath = ([string]$pathLine).Substring("package:".Length).Trim()
    $hashLine = @(Invoke-Adb -Arguments @("shell", "sha256sum", $remotePath))[0]
    $match = [regex]::Match([string]$hashLine, "^([0-9a-fA-F]{64})")
    if (-not $match.Success) { throw "Unable to hash installed package $Package" }
    return [ordered]@{
        package = $Package
        path = $remotePath
        sha256 = $match.Groups[1].Value.ToUpperInvariant()
    }
}

function Get-ThermalStatus {
    $dump = @(Invoke-Adb -Arguments @("shell", "dumpsys", "thermalservice") -AllowFailure)
    $match = [regex]::Match(($dump -join "`n"), "Thermal Status:\s*(\d+)")
    if ($match.Success) { return [int]$match.Groups[1].Value }
    return -1
}

function Get-SkinTemperatureCelsius {
    $dump = @(Invoke-Adb -Arguments @("shell", "dumpsys", "thermalservice") -AllowFailure)
    $matches = [regex]::Matches(
        ($dump -join "`n"),
        "Temperature\{mValue=([0-9.]+), mType=3, mName=skin, mStatus=\d+\}"
    )
    if ($matches.Count -eq 0) { return $null }
    return [double]$matches[$matches.Count - 1].Groups[1].Value
}

function Get-ThermalSnapshot {
    # A single thermalservice transaction supplies both values. Running two back-to-back dumpsys
    # calls while a 4K25 Camera2 session is active is observable load and can perturb the cadence
    # this checkpoint is intended to measure.
    $dump = @(Invoke-Adb -Arguments @("shell", "dumpsys", "thermalservice") -AllowFailure)
    $text = $dump -join "`n"
    $statusMatch = [regex]::Match($text, "Thermal Status:\s*(\d+)")
    $skinMatches = [regex]::Matches(
        $text,
        "Temperature\{mValue=([0-9.]+), mType=3, mName=skin, mStatus=\d+\}"
    )
    [pscustomobject]@{
        status = if ($statusMatch.Success) { [int]$statusMatch.Groups[1].Value } else { -1 }
        skinCelsius = if ($skinMatches.Count -gt 0) {
            [double]$skinMatches[$skinMatches.Count - 1].Groups[1].Value
        } else {
            $null
        }
    }
}

$startedAt = Get-Date
$appIdentity = Get-RemotePackageIdentity -Package $PackageName
$installedTestIdentity = Get-RemotePackageIdentity -Package $TestPackageName
$deviceModel = (@(Invoke-Adb -Arguments @("shell", "getprop", "ro.product.model"))[0]).Trim()
$deviceFingerprint = (@(Invoke-Adb -Arguments @("shell", "getprop", "ro.build.fingerprint"))[0]).Trim()
$thermalBefore = Get-ThermalStatus
$skinBefore = Get-SkinTemperatureCelsius

if ($thermalBefore -lt 0 -or $thermalBefore -gt $MaximumAdmissionThermalStatus) {
    throw "CP-210 thermal admission rejected: status=$thermalBefore, required <=$MaximumAdmissionThermalStatus"
}

[ordered]@{
    schema = "neo-qa-lq034-cp210-admission-v1"
    serial = $Serial
    deviceModel = $deviceModel
    deviceFingerprint = $deviceFingerprint
    app = $appIdentity
    test = $installedTestIdentity
    thermalStatus = $thermalBefore
    maximumThermalStatus = $MaximumAdmissionThermalStatus
    traceProfile = $TraceProfile
    traceBufferMegabytes = $TraceBufferMegabytes
    traceCategories = @($TraceCategories)
    thermalSamplePeriodSeconds = $ThermalSamplePeriodSeconds
    skinCelsius = $skinBefore
    battery = @(Invoke-Adb -Arguments @("shell", "dumpsys", "battery"))
    storage = @(Invoke-Adb -Arguments @("shell", "df", "/data"))
    cameraServicePid = (@(Invoke-Adb -Arguments @("shell", "pidof", "cameraserver") -AllowFailure) -join "").Trim()
    admittedAt = $startedAt.ToString("o")
} | ConvertTo-Json -Depth 6 |
    Set-Content -LiteralPath (Join-Path $ResolvedRoot "admission.json") -Encoding UTF8

Invoke-Adb -Arguments @("shell", "am", "force-stop", $PackageName) -AllowFailure | Out-Null
Invoke-Adb -Arguments @("shell", "am", "force-stop", $TestPackageName) -AllowFailure | Out-Null
Invoke-Adb -Arguments @("shell", "input", "keyevent", "KEYCODE_WAKEUP") -AllowFailure | Out-Null
Invoke-Adb -Arguments @("shell", "wm", "dismiss-keyguard") -AllowFailure | Out-Null

# A previous instrumentation process invalidates timestamp-to-trace correlation. Wait for both
# packages to retire and fail closed instead of racing another device session.
$quiescenceDeadline = [DateTime]::UtcNow.AddSeconds(10)
do {
    $appPidBefore = (@(Invoke-Adb -Arguments @("shell", "pidof", "-s", $PackageName) -AllowFailure) -join "").Trim()
    $testPidBefore = (@(Invoke-Adb -Arguments @("shell", "pidof", "-s", $TestPackageName) -AllowFailure) -join "").Trim()
    if (-not $appPidBefore -and -not $testPidBefore) { break }
    Start-Sleep -Milliseconds 250
} while ([DateTime]::UtcNow -lt $quiescenceDeadline)
if ($appPidBefore -or $testPidBefore) {
    throw "CP-210 preflight could not obtain exclusive device ownership: appPid=$appPidBefore testPid=$testPidBefore"
}

Invoke-Adb -Arguments @("shell", "rm", "-f", $RemoteTrace) -AllowFailure | Out-Null
Invoke-Adb -Arguments @("shell", "rm", "-f", $RemoteTraceConfig) -AllowFailure | Out-Null
Invoke-Adb -Arguments @("logcat", "-c") | Out-Null
Invoke-Adb -Arguments @("shell", "dumpsys", "gfxinfo", $PackageName, "reset") -AllowFailure | Out-Null

$perfettoArguments = if ($TraceProfile -eq "RecordingSafe") {
    $traceConfigPath = Join-Path $ResolvedRoot "perfetto-config.pbtxt"
    $traceDurationMilliseconds = $TraceDurationSeconds * 1000
    $traceBufferKilobytes = $TraceBufferMegabytes * 1024
    $traceConfig = @"
duration_ms: $traceDurationMilliseconds
write_into_file: true
file_write_period_ms: 2500
flush_period_ms: 1000
buffers {
  size_kb: $traceBufferKilobytes
  fill_policy: RING_BUFFER
}
data_sources {
  config {
    name: "linux.ftrace"
    target_buffer: 0
    ftrace_config {
      atrace_apps: "$PackageName"
      atrace_categories: "am"
      atrace_categories: "wm"
    }
  }
}
data_sources {
  config {
    name: "android.surfaceflinger.frametimeline"
    target_buffer: 0
  }
}
data_sources {
  config {
    name: "linux.process_stats"
    target_buffer: 0
    process_stats_config {
      scan_all_processes_on_start: true
      proc_stats_poll_ms: 1000
    }
  }
}
"@
    [System.IO.File]::WriteAllText(
        $traceConfigPath,
        $traceConfig,
        [System.Text.UTF8Encoding]::new($false)
    )
    Invoke-Adb -Arguments @("push", $traceConfigPath, $RemoteTraceConfig) | Out-Null
    @("shell", "perfetto", "--background-wait", "-o", $RemoteTrace, "--txt", "-c", $RemoteTraceConfig)
} else {
    @(
        "shell", "perfetto", "--background-wait", "-o", $RemoteTrace,
        "-t", "${TraceDurationSeconds}s", "--buffer", "${TraceBufferMegabytes}mb", "--app", $PackageName
    ) + $TraceCategories
}
$perfettoOutput = @(Invoke-Adb -Arguments $perfettoArguments)
$perfettoOutput | Set-Content -LiteralPath (Join-Path $ResolvedRoot "perfetto-start.txt") -Encoding UTF8
$perfettoPid = (($perfettoOutput | Select-Object -Last 1) -as [string]).Trim()
if ($perfettoPid -notmatch "^\d+$") { throw "Perfetto did not return a valid PID: $perfettoPid" }

$stdoutPath = Join-Path $ResolvedRoot "instrumentation.txt"
$stderrPath = Join-Path $ResolvedRoot "instrumentation-stderr.txt"
$arguments = @(
    "-s", $Serial, "shell", "am", "instrument", "-w", "-r",
    "-e", "class", $TestSelector, $Runner
)
$process = Start-Process -FilePath $AdbPath -ArgumentList $arguments `
    -WindowStyle Hidden -RedirectStandardOutput $stdoutPath `
    -RedirectStandardError $stderrPath -PassThru
$instrumentationStartedAt = Get-Date

$samples = [System.Collections.Generic.List[object]]::new()
$deadline = [DateTime]::UtcNow.AddSeconds($InstrumentationTimeoutSeconds)
$timedOut = $false
$watchdogTicks = 0
$watchdogFreezeRecoveries = 0
while (-not $process.HasExited -and [DateTime]::UtcNow -lt $deadline) {
    Start-Sleep -Seconds 1
    $process.Refresh()
    # Observe at a low cadence and mutate device state only after proving the process is frozen.
    # The former unconditional 750 ms `cmd activity unfreeze` loop coincided with a Camera2 sensor
    # cadence gap and made the profiler itself a source of interference.
    if (($watchdogTicks % $ThermalSamplePeriodSeconds) -eq 0) {
        $pidText = (@(Invoke-Adb -Arguments @("shell", "pidof", "-s", $PackageName) -AllowFailure) -join "").Trim()
        if ($pidText -match "^\d+$") {
            $waitChannel = (@(Invoke-Adb -Arguments @(
                "shell", "run-as", $PackageName, "cat", "/proc/$pidText/task/$pidText/wchan"
            ) -AllowFailure) -join "").Trim()
            if ($waitChannel -eq "do_freezer_trap") {
                $watchdogFreezeRecoveries += 1
                Invoke-Adb -Arguments @("shell", "cmd", "activity", "unfreeze", "--sticky", $PackageName) -AllowFailure | Out-Null
                Invoke-Adb -Arguments @("shell", "cmd", "activity", "unfreeze", "--sticky", $pidText) -AllowFailure | Out-Null
            }
        }
        $thermalSnapshot = Get-ThermalSnapshot
        $samples.Add([pscustomobject]@{
            hostTimestamp = (Get-Date).ToString("o")
            elapsedSeconds = [math]::Round(((Get-Date) - $startedAt).TotalSeconds, 3)
            thermalStatus = $thermalSnapshot.status
            skinCelsius = $thermalSnapshot.skinCelsius
            appPid = $pidText
        })
    }
    $watchdogTicks += 1
}
if (-not $process.HasExited) {
    $timedOut = $true
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
}
$process.WaitForExit()
$instrumentationEndedAt = Get-Date
$samples | Export-Csv -NoTypeInformation -Encoding UTF8 -LiteralPath (Join-Path $ResolvedRoot "thermal-timeline.csv")

# Stop as soon as instrumentation exits. A fixed post-test tail can overwrite startup and early
# mode-switch packets in a ring buffer, producing a non-empty but incomplete trace.
Invoke-Adb -Arguments @("shell", "kill", "-TERM", $perfettoPid) -AllowFailure | Out-Null

# Snapshot finite log buffers before a large trace transfer can evict the app's recording evidence.
Set-Content -LiteralPath (Join-Path $ResolvedRoot "logcat-all.txt") -Encoding UTF8 `
    -Value (@(Invoke-Adb -Arguments @("logcat", "-d", "-v", "threadtime") -AllowFailure) -join "`r`n")
Set-Content -LiteralPath (Join-Path $ResolvedRoot "logcat-crash.txt") -Encoding UTF8 `
    -Value (@(Invoke-Adb -Arguments @("logcat", "-b", "crash", "-d", "-v", "threadtime") -AllowFailure) -join "`r`n")
Set-Content -LiteralPath (Join-Path $ResolvedRoot "lastanr.txt") -Encoding UTF8 `
    -Value (@(Invoke-Adb -Arguments @("shell", "dumpsys", "activity", "lastanr") -AllowFailure) -join "`r`n")
Set-Content -LiteralPath (Join-Path $ResolvedRoot "gfxinfo-framestats.txt") -Encoding UTF8 `
    -Value (@(Invoke-Adb -Arguments @("shell", "dumpsys", "gfxinfo", $PackageName, "framestats") -AllowFailure) -join "`r`n")
Set-Content -LiteralPath (Join-Path $ResolvedRoot "meminfo-after.txt") -Encoding UTF8 `
    -Value (@(Invoke-Adb -Arguments @("shell", "dumpsys", "meminfo", $PackageName) -AllowFailure) -join "`r`n")
Set-Content -LiteralPath (Join-Path $ResolvedRoot "camera-service-after.txt") -Encoding UTF8 `
    -Value (@(Invoke-Adb -Arguments @("shell", "dumpsys", "media.camera") -AllowFailure) -join "`r`n")
Set-Content -LiteralPath (Join-Path $ResolvedRoot "thermal-after.txt") -Encoding UTF8 `
    -Value (@(Invoke-Adb -Arguments @("shell", "dumpsys", "thermalservice") -AllowFailure) -join "`r`n")

$traceDeadline = [DateTime]::UtcNow.AddSeconds($TraceDurationSeconds + 20)
while ([DateTime]::UtcNow -lt $traceDeadline) {
    $running = (@(Invoke-Adb -Arguments @("shell", "pidof", "perfetto") -AllowFailure) -join "").Trim()
    if (-not $running) { break }
    Start-Sleep -Seconds 1
}
Start-Sleep -Seconds 2
Invoke-Adb -Arguments @("pull", $RemoteTrace, (Join-Path $ResolvedRoot $TraceName)) |
    Set-Content -LiteralPath (Join-Path $ResolvedRoot "perfetto-pull.txt") -Encoding UTF8
Invoke-Adb -Arguments @("shell", "rm", "-f", $RemoteTraceConfig) -AllowFailure | Out-Null

$instrumentationText = if (Test-Path -LiteralPath $stdoutPath) {
    Get-Content -Raw -LiteralPath $stdoutPath
} else { "" }
$instrumentationPassed =
    -not $timedOut -and
    $instrumentationText.Contains("INSTRUMENTATION_CODE: -1") -and
    $instrumentationText.Contains("OK (1 test)") -and
    -not $instrumentationText.Contains("FAILURES!!!") -and
    -not $instrumentationText.Contains("INSTRUMENTATION_FAILED")
$tracePath = Join-Path $ResolvedRoot $TraceName
$traceValid = (Test-Path -LiteralPath $tracePath -PathType Leaf) -and
    (Get-Item -LiteralPath $tracePath).Length -ge 1MB
$instrumentationElapsedSeconds = [math]::Round(
    ($instrumentationEndedAt - $instrumentationStartedAt).TotalSeconds,
    3
)
$traceCoverageSeconds = 0.0
$traceAppFrameCount = 0
$traceCoverageValid = $false
$traceAnalysisPath = Join-Path $ResolvedRoot "trace-coverage-query.txt"
if ($traceValid -and (Test-Path -LiteralPath $TraceProcessorPath -PathType Leaf)) {
    $observedAppPids = @(
        $samples |
            ForEach-Object { [string]$_.appPid } |
            Where-Object { $_ -match '^\d+$' } |
            Select-Object -Unique
    )
    $appFramePredicate = if ($observedAppPids.Count -gt 0) {
        "app_process.name = '$PackageName' or app_process.pid in ($($observedAppPids -join ','))"
    } else {
        "app_process.name = '$PackageName'"
    }
    $traceQuery = @"
select
  round((end_ts - start_ts) / 1e9, 3) as trace_duration_s,
  (select count(*)
     from actual_frame_timeline_slice frame
     join process app_process using(upid)
    where $appFramePredicate) as app_frame_count
from trace_bounds;
"@
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $traceAnalysis = @(& $TraceProcessorPath query $tracePath $traceQuery 2>&1)
        $traceProcessorExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    $traceAnalysis | Set-Content -LiteralPath $traceAnalysisPath -Encoding UTF8
    $measurement = $traceAnalysis |
        ForEach-Object { [string]$_ } |
        Where-Object { $_ -match '^([0-9]+(?:\.[0-9]+)?),([0-9]+)$' } |
        Select-Object -First 1
    if ($traceProcessorExitCode -eq 0 -and $measurement) {
        $parts = $measurement.Split(',')
        $traceCoverageSeconds = [double]::Parse(
            $parts[0],
            [System.Globalization.CultureInfo]::InvariantCulture
        )
        $traceAppFrameCount = [int64]$parts[1]
        $minimumCoverageSeconds = [math]::Max(1.0, $instrumentationElapsedSeconds * 0.90)
        $traceCoverageValid =
            $traceCoverageSeconds -ge $minimumCoverageSeconds -and
            $traceAppFrameCount -gt 0
    }
} else {
    @(
        "Trace processor unavailable or trace invalid.",
        "traceProcessor=$TraceProcessorPath",
        "traceValid=$traceValid"
    ) | Set-Content -LiteralPath $traceAnalysisPath -Encoding UTF8
}
$crashText = Get-Content -Raw -LiteralPath (Join-Path $ResolvedRoot "logcat-crash.txt")
$crashFree = [string]::IsNullOrWhiteSpace($crashText)
$maxObservedThermal = @($samples | Measure-Object -Property thermalStatus -Maximum).Maximum
$thermalAfter = Get-ThermalStatus
$skinAfter = Get-SkinTemperatureCelsius

$result = [ordered]@{
    schema = "neo-qa-lq034-cp210-result-v1"
    checkpoint = "CP-210"
    status = if ($instrumentationPassed -and $traceValid -and $traceCoverageValid -and $crashFree) {
        "PASS"
    } else {
        "FAIL"
    }
    serial = $Serial
    deviceModel = $deviceModel
    deviceFingerprint = $deviceFingerprint
    appInstalledSha256 = $appIdentity.sha256
    testInstalledSha256 = $installedTestIdentity.sha256
    test = $TestSelector
    instrumentationPassed = $instrumentationPassed
    instrumentationTimedOut = $timedOut
    instrumentationElapsedSeconds = $instrumentationElapsedSeconds
    watchdogTicks = $watchdogTicks
    watchdogFreezeRecoveries = $watchdogFreezeRecoveries
    traceValid = $traceValid
    traceBytes = if ($traceValid) { (Get-Item -LiteralPath $tracePath).Length } else { 0 }
    traceSha256 = if ($traceValid) { (Get-FileHash -LiteralPath $tracePath -Algorithm SHA256).Hash } else { "" }
    traceCoverageValid = $traceCoverageValid
    traceCoverageSeconds = $traceCoverageSeconds
    traceAppFrameCount = $traceAppFrameCount
    traceProcessorPath = $TraceProcessorPath
    traceProfile = $TraceProfile
    traceBufferMegabytes = $TraceBufferMegabytes
    traceCategories = @($TraceCategories)
    thermalSamplePeriodSeconds = $ThermalSamplePeriodSeconds
    crashFree = $crashFree
    thermalAdmissionStatus = $thermalBefore
    thermalMaximumObservedStatus = $maxObservedThermal
    thermalAfterStatus = $thermalAfter
    skinBeforeCelsius = $skinBefore
    skinAfterCelsius = $skinAfter
    elapsedSeconds = [math]::Round(((Get-Date) - $startedAt).TotalSeconds, 3)
    completedAt = (Get-Date).ToString("o")
}
$result | ConvertTo-Json -Depth 5 |
    Set-Content -LiteralPath (Join-Path $ResolvedRoot "result.json") -Encoding UTF8

Invoke-Adb -Arguments @("shell", "am", "force-stop", $PackageName) -AllowFailure | Out-Null
Invoke-Adb -Arguments @("shell", "am", "force-stop", $TestPackageName) -AllowFailure | Out-Null
Set-Content -LiteralPath (Join-Path $ResolvedRoot "services-after-cleanup.txt") -Encoding UTF8 `
    -Value (@(Invoke-Adb -Arguments @("shell", "dumpsys", "activity", "services", $PackageName) -AllowFailure) -join "`r`n")

Get-ChildItem -LiteralPath $ResolvedRoot -File |
    Where-Object { $_.Name -ne "SHA256SUMS.txt" } |
    Sort-Object Name |
    ForEach-Object {
        $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $($_.Name)"
    } | Set-Content -LiteralPath (Join-Path $ResolvedRoot "SHA256SUMS.txt") -Encoding UTF8

$result | ConvertTo-Json -Depth 5
if ($result.status -ne "PASS") { exit 1 }
