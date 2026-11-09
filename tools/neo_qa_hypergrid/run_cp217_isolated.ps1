param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [string]$InventoryPath = "artifacts/device-qa/lq034-20260827-final/cp217-test-inventory.txt",

    [string]$ArtifactRoot = "artifacts/device-qa/lq034-cp217-isolated",

    [ValidateRange(1, 10000)]
    [int]$StartIndex = 1,

    [ValidateRange(1, 10000)]
    [int]$EndIndex = 10000,

    [ValidateRange(30, 1800)]
    [int]$TimeoutSeconds = 900,

    [ValidateRange(0, 6)]
    [int]$MaxThermalStatus = 1,

    [ValidateRange(30, 3600)]
    [int]$ThermalAdmissionTimeoutSeconds = 900,

    [ValidateRange(1, 60)]
    [int]$ThermalPollSeconds = 10,

    [ValidateRange(0, 1800)]
    [int]$ThreadDumpAfterSeconds = 0,

    [string[]]$SkipTest = @(),

    [string]$ExtraArgumentsJson = "",

    [switch]$Force,

    [switch]$UseKeepAliveActivity,

    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = "Stop"
$PackageName = "com.neoproc.neoproccamx"
$TestPackageName = "$PackageName.test"
$Runner = "$TestPackageName/androidx.test.runner.AndroidJUnitRunner"

if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    throw "ADB executable was not found: $AdbPath"
}
if (-not (Test-Path -LiteralPath $InventoryPath -PathType Leaf)) {
    throw "CP-217 inventory was not found: $InventoryPath"
}

$ResolvedRoot = [System.IO.Path]::GetFullPath((Join-Path $PWD $ArtifactRoot))
New-Item -ItemType Directory -Force -Path $ResolvedRoot | Out-Null

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [switch]$AllowFailure
    )
    # A short instrumentation can exit between pidof and the following /proc probe. In Windows
    # PowerShell, expected adb stderr from that race becomes an ErrorRecord and the script-level
    # Stop preference would abort a test that has already passed. AllowFailure must therefore relax
    # native stderr handling locally while still returning the exact output for diagnostics.
    $previousPreference = $ErrorActionPreference
    if ($AllowFailure) { $ErrorActionPreference = "Continue" }
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

function Get-RemotePackageHash {
    param([Parameter(Mandatory = $true)][string]$Package)
    $pathLine = @(Invoke-Adb -Arguments @("shell", "pm", "path", $Package)) |
        Where-Object { $_ -match "^package:" } |
        Select-Object -First 1
    if (-not $pathLine) { throw "Package is not installed: $Package" }
    $remotePath = ([string]$pathLine).Substring("package:".Length).Trim()
    $hashLine = @(Invoke-Adb -Arguments @("shell", "sha256sum", $remotePath))[0]
    $match = [regex]::Match([string]$hashLine, "^([0-9a-fA-F]{64})")
    if (-not $match.Success) { throw "Unable to hash installed package $Package" }
    return $match.Groups[1].Value.ToUpperInvariant()
}

function Get-ThermalStatus {
    $lines = @(Invoke-Adb -Arguments @("shell", "dumpsys", "thermalservice") -AllowFailure)
    $match = [regex]::Match(($lines -join "`n"), "Thermal Status:\s*(\d+)")
    if ($match.Success) { return [int]$match.Groups[1].Value }
    return -1
}

function Wait-ThermalAdmission {
    param(
        [Parameter(Mandatory = $true)][int]$MaximumStatus,
        [Parameter(Mandatory = $true)][int]$TimeoutSeconds,
        [Parameter(Mandatory = $true)][int]$PollSeconds,
        [Parameter(Mandatory = $true)][string]$EvidencePath
    )
    $samples = [System.Collections.Generic.List[object]]::new()
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $status = Get-ThermalStatus
        $samples.Add([pscustomobject]@{
            observedAt = (Get-Date).ToString("o")
            status = $status
        })
        if ($status -ge 0 -and $status -le $MaximumStatus) {
            $evidence = [ordered]@{
                schema = "neo-qa-thermal-admission-v1"
                status = "PASS"
                maximumStatus = $MaximumStatus
                observedStatus = $status
                samples = @($samples)
            }
            $evidence | ConvertTo-Json -Depth 6 |
                Set-Content -LiteralPath $EvidencePath -Encoding UTF8
            return $evidence
        }
        if ([DateTime]::UtcNow -ge $deadline) { break }
        Start-Sleep -Seconds $PollSeconds
    } while ($true)

    $evidence = [ordered]@{
        schema = "neo-qa-thermal-admission-v1"
        status = "BLOCKED"
        maximumStatus = $MaximumStatus
        observedStatus = $samples[$samples.Count - 1].status
        samples = @($samples)
    }
    $evidence | ConvertTo-Json -Depth 6 |
        Set-Content -LiteralPath $EvidencePath -Encoding UTF8
    throw "Thermal admission timed out: required status <=$MaximumStatus; observed=$($evidence.observedStatus)"
}

function Start-DebugKeepAliveActivity {
    $output = @(Invoke-Adb -Arguments @(
        "shell", "am", "start", "-W", "-n",
        "$PackageName/.testing.Lq031KeepAliveActivity"
    ))
    if (($output -join "`n") -notmatch "(?m)^Status:\s*ok\s*$") {
        throw "Debug keep-alive activity did not report Status: ok`n$($output -join "`n")"
    }
    return @($output)
}

function Capture-ProcessDiagnostics {
    param(
        [Parameter(Mandatory = $true)][string]$PidText,
        [Parameter(Mandatory = $true)][string]$NativeBacktracePath,
        [Parameter(Mandatory = $true)][string]$NativeBacktraceStderrPath,
        [Parameter(Mandatory = $true)][string]$SignalResultPath,
        [Parameter(Mandatory = $true)][string]$LogcatSnapshotPath,
        [Parameter(Mandatory = $true)][string]$ThreadStatePath
    )
    $threadState = [System.Collections.Generic.List[string]]::new()
    $threadState.Add("=== ps -T ===")
    $threadState.AddRange([string[]]@(
        Invoke-Adb -Arguments @(
            "shell", "ps", "-T", "-p", $PidText, "-o", "PID,TID,STAT,WCHAN,NAME"
        ) -AllowFailure
    ))
    $threadState.Add("=== top -H ===")
    $threadState.AddRange([string[]]@(
        Invoke-Adb -Arguments @(
            "shell", "top", "-H", "-b", "-n", "1", "-p", $PidText
        ) -AllowFailure
    ))
    Set-Content -LiteralPath $ThreadStatePath -Encoding UTF8 -Value $threadState
    $native = Start-Process -FilePath $AdbPath -ArgumentList @(
        "-s", $Serial, "shell", "debuggerd", "-b", $PidText
    ) -WindowStyle Hidden -RedirectStandardOutput $NativeBacktracePath `
        -RedirectStandardError $NativeBacktraceStderrPath -PassThru
    if (-not $native.WaitForExit(15000)) {
        Stop-Process -Id $native.Id -Force -ErrorAction SilentlyContinue
        Add-Content -LiteralPath $NativeBacktraceStderrPath `
            -Value "debuggerd backtrace timed out after 15000ms"
    }
    $signalResult = @(
        Invoke-Adb -Arguments @(
            "shell", "run-as", $PackageName, "kill", "-3", $PidText
        ) -AllowFailure
    )
    Set-Content -LiteralPath $SignalResultPath -Encoding UTF8 `
        -Value ($signalResult -join "`n")
    Start-Sleep -Seconds 2
    $snapshot = @(
        Invoke-Adb -Arguments @("logcat", "-d", "-v", "threadtime", "--pid", $PidText) -AllowFailure
    )
    Set-Content -LiteralPath $LogcatSnapshotPath -Encoding UTF8 `
        -Value ($snapshot -join "`n")
}

function Get-TestCases {
    $currentClass = ""
    $seen = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    $cases = [System.Collections.Generic.List[object]]::new()
    foreach ($line in Get-Content -LiteralPath $InventoryPath) {
        if ($line -match "^INSTRUMENTATION_STATUS: class=(.+)$") {
            $currentClass = $matches[1].Trim()
            continue
        }
        if ($line -match "^INSTRUMENTATION_STATUS: test=(.+)$" -and $currentClass) {
            $method = $matches[1].Trim()
            $identity = "$currentClass#$method"
            if ($seen.Add($identity)) {
                $cases.Add([pscustomobject]@{
                    Index = $cases.Count + 1
                    Class = $currentClass
                    Method = $method
                    Identity = $identity
                })
            }
        }
    }
    return @($cases)
}

function Read-ExtraArguments {
    if ([string]::IsNullOrWhiteSpace($ExtraArgumentsJson)) { return @{} }
    if (-not (Test-Path -LiteralPath $ExtraArgumentsJson -PathType Leaf)) {
        throw "Instrumentation argument map was not found: $ExtraArgumentsJson"
    }
    $parsed = Get-Content -Raw -LiteralPath $ExtraArgumentsJson | ConvertFrom-Json
    if ($null -eq $parsed) { return @{} }
    if ($parsed -is [System.Collections.IDictionary]) { return $parsed }

    # Windows PowerShell 5.1 does not provide ConvertFrom-Json -AsHashtable. Normalize the
    # two-level JSON argument map explicitly so the exact same runner works on both 5.1 and 7+.
    $argumentMap = @{}
    foreach ($testProperty in $parsed.PSObject.Properties) {
        $testArguments = @{}
        if ($null -ne $testProperty.Value) {
            foreach ($argumentProperty in $testProperty.Value.PSObject.Properties) {
                $testArguments[$argumentProperty.Name] = $argumentProperty.Value
            }
        }
        $argumentMap[$testProperty.Name] = $testArguments
    }
    return $argumentMap
}

function Get-PlaybackSourceAuthentication {
    param(
        [Parameter(Mandatory = $true)][string]$TestIdentity,
        [Parameter(Mandatory = $true)][hashtable]$TestArguments,
        [Parameter(Mandatory = $true)][string]$EvidencePath
    )
    $playbackEvidenceIdentity =
        "com.neoproc.neoproccamx.ui.PlaybackEvidenceInstrumentedTest#" +
        "configuredMasterMeasuresCadenceErrorsPtsAndExactRelease"
    if ($TestIdentity -ne $playbackEvidenceIdentity) { return $null }
    if (-not $TestArguments.ContainsKey("neoPlaybackUri")) { return $null }

    $sourceUriText = [string]$TestArguments["neoPlaybackUri"]
    $sourceUri = $null
    if (-not [System.Uri]::TryCreate($sourceUriText, [System.UriKind]::Absolute, [ref]$sourceUri)) {
        throw "Playback source URI is invalid: $sourceUriText"
    }
    if (-not $sourceUri.IsFile) {
        # Content URIs remain authenticated inside instrumentation because shell cannot resolve
        # their bytes without changing URI-grant semantics.
        return $null
    }

    $remotePath = [System.Uri]::UnescapeDataString($sourceUri.AbsolutePath)
    $allowedPrefixes = @(
        "/storage/emulated/0/Android/data/$PackageName/files/",
        "/sdcard/Android/data/$PackageName/files/"
    )
    $isAppExternalFile = @(
        $allowedPrefixes | Where-Object {
            $remotePath.StartsWith($_, [System.StringComparison]::Ordinal)
        }
    ).Count -gt 0
    if (-not $isAppExternalFile) {
        throw "Runner-native authentication is restricted to app external files; path=$remotePath"
    }
    if ($remotePath -notmatch '^/[A-Za-z0-9._/-]+$') {
        throw "Playback source path contains unsupported shell characters: $remotePath"
    }

    $startedAt = Get-Date
    $beforeSizeLine = (@(Invoke-Adb -Arguments @("shell", "stat", "-c", "%s", $remotePath))[0] -as [string]).Trim()
    if ($beforeSizeLine -notmatch '^\d+$' -or [int64]$beforeSizeLine -le 0) {
        throw "Unable to read a positive source size before authentication: $beforeSizeLine"
    }
    $hashLine = (@(Invoke-Adb -Arguments @("shell", "sha256sum", $remotePath))[0] -as [string]).Trim()
    $hashMatch = [regex]::Match($hashLine, '^([0-9a-fA-F]{64})\s+')
    if (-not $hashMatch.Success) {
        throw "Unable to authenticate playback source bytes: $hashLine"
    }
    $afterSizeLine = (@(Invoke-Adb -Arguments @("shell", "stat", "-c", "%s", $remotePath))[0] -as [string]).Trim()
    if ($afterSizeLine -ne $beforeSizeLine) {
        throw "Playback source size changed during authentication: before=$beforeSizeLine after=$afterSizeLine"
    }

    $sha256 = $hashMatch.Groups[1].Value.ToLowerInvariant()
    $byteCount = [int64]$beforeSizeLine
    $TestArguments["neoPlaybackPreverifiedSourceSha256"] = $sha256
    $TestArguments["neoPlaybackPreverifiedSourceBytes"] = [string]$byteCount
    $evidence = [ordered]@{
        schema = "neo-qa-playback-source-authentication-v1"
        method = "ADB_TOYBOX_SHA256SUM_FULL_BYTES"
        sourceUri = $sourceUriText
        sourcePath = $remotePath
        sha256 = $sha256
        byteCount = $byteCount
        sizeStableDuringHash = $true
        startedAt = $startedAt.ToString("o")
        completedAt = (Get-Date).ToString("o")
        elapsedSeconds = [math]::Round(((Get-Date) - $startedAt).TotalSeconds, 3)
    }
    $evidence | ConvertTo-Json -Depth 5 |
        Set-Content -LiteralPath $EvidencePath -Encoding UTF8
    return $evidence
}

$allCases = @(Get-TestCases)
$canonicalTestCount = 108
if ($allCases.Count -ne $canonicalTestCount) {
    throw "Expected the canonical $canonicalTestCount CP-217 tests, discovered $($allCases.Count)"
}
$selected = @(
    $allCases | Where-Object {
        $_.Index -ge $StartIndex -and
        $_.Index -le $EndIndex -and
        $SkipTest -notcontains $_.Identity
    }
)
$extraArguments = Read-ExtraArguments
$appHash = Get-RemotePackageHash -Package $PackageName
$testHash = Get-RemotePackageHash -Package $TestPackageName
$deviceModel = (@(Invoke-Adb -Arguments @("shell", "getprop", "ro.product.model"))[0]).Trim()
$deviceFingerprint = (@(Invoke-Adb -Arguments @("shell", "getprop", "ro.build.fingerprint"))[0]).Trim()

[ordered]@{
    schema = "neo-qa-cp217-isolated-selection-v1"
    serial = $Serial
    deviceModel = $deviceModel
    deviceFingerprint = $deviceFingerprint
    appInstalledSha256 = $appHash
    testInstalledSha256 = $testHash
    inventoryCount = $allCases.Count
    selectedCount = $selected.Count
    startIndex = $StartIndex
    endIndex = $EndIndex
    skippedByRequest = @($SkipTest)
    tests = @($selected)
} | ConvertTo-Json -Depth 6 |
    Set-Content -LiteralPath (Join-Path $ResolvedRoot "selection.json") -Encoding UTF8

$results = [System.Collections.Generic.List[object]]::new()
foreach ($test in $selected) {
    $testRoot = Join-Path $ResolvedRoot ("{0:D3}" -f $test.Index)
    New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
    $resultPath = Join-Path $testRoot "result.json"
    if (-not $Force -and (Test-Path -LiteralPath $resultPath -PathType Leaf)) {
        $existing = Get-Content -Raw -LiteralPath $resultPath | ConvertFrom-Json
        if (
            $existing.status -eq "PASS" -and
            $existing.appInstalledSha256 -eq $appHash -and
            $existing.testInstalledSha256 -eq $testHash
        ) {
            $results.Add($existing)
            Write-Host ("CP-217 {0:D3}/{1} PASS (sealed) {2}" -f $test.Index, $canonicalTestCount, $test.Identity)
            continue
        }
    }

    $result = [ordered]@{
        schema = "neo-qa-cp217-isolated-result-v1"
        checkpoint = "CP-217"
        index = $test.Index
        class = $test.Class
        method = $test.Method
        identity = $test.Identity
        status = "FAIL"
        serial = $Serial
        deviceModel = $deviceModel
        deviceFingerprint = $deviceFingerprint
        appInstalledSha256 = $appHash
        testInstalledSha256 = $testHash
        startedAt = (Get-Date).ToString("o")
    }
    $stdoutPath = Join-Path $testRoot "instrumentation.txt"
    $stderrPath = Join-Path $testRoot "instrumentation-stderr.txt"
    $logcatPath = Join-Path $testRoot "logcat.txt"
    $crashPath = Join-Path $testRoot "logcat-crash.txt"
    $threadDumpPath = Join-Path $testRoot "thread-dump.txt"
    $threadDumpSignalPath = Join-Path $testRoot "thread-dump-signal.txt"
    $nativeBacktracePath = Join-Path $testRoot "native-backtrace.txt"
    $nativeBacktraceStderrPath = Join-Path $testRoot "native-backtrace-stderr.txt"
    $threadStatePath = Join-Path $testRoot "thread-state.txt"
    $thermalAdmissionPath = Join-Path $testRoot "thermal-admission.json"
    $sourceAuthenticationPath = Join-Path $testRoot "source-authentication.json"
    $lastObservedPid = ""
    try {
        Invoke-Adb -Arguments @("shell", "am", "force-stop", $PackageName) | Out-Null
        Invoke-Adb -Arguments @("shell", "am", "force-stop", $TestPackageName) -AllowFailure | Out-Null
        $testArguments = @{}
        if ($extraArguments.ContainsKey($test.Identity)) {
            foreach ($entry in $extraArguments[$test.Identity].GetEnumerator()) {
                $testArguments[[string]$entry.Key] = [string]$entry.Value
            }
        }
        $sourceAuthentication = Get-PlaybackSourceAuthentication `
            -TestIdentity $test.Identity `
            -TestArguments $testArguments `
            -EvidencePath $sourceAuthenticationPath
        if ($null -ne $sourceAuthentication) {
            $result.sourceAuthentication = $sourceAuthentication
        }
        # Performance and camera evidence is invalid when captured after the device has already
        # entered a throttled band. Keep the screen asleep and both packages stopped until the
        # physical device returns to the admitted band; never reinterpret a hot failure as PASS.
        Invoke-Adb -Arguments @("shell", "input", "keyevent", "KEYCODE_SLEEP") -AllowFailure | Out-Null
        $result.thermalAdmission = Wait-ThermalAdmission `
            -MaximumStatus $MaxThermalStatus `
            -TimeoutSeconds $ThermalAdmissionTimeoutSeconds `
            -PollSeconds $ThermalPollSeconds `
            -EvidencePath $thermalAdmissionPath
        Invoke-Adb -Arguments @("shell", "input", "keyevent", "KEYCODE_WAKEUP") | Out-Null
        Invoke-Adb -Arguments @("shell", "wm", "dismiss-keyguard") | Out-Null
        Invoke-Adb -Arguments @("logcat", "-c") | Out-Null
        $result.thermalBefore = Get-ThermalStatus

        # Never overlap a prior instrumentation generation. A force-stop is asynchronous on some
        # vendor builds, and starting the next case before both processes retire can invalidate the
        # test result and its log ownership.
        $quiescenceDeadline = [DateTime]::UtcNow.AddSeconds(10)
        do {
            $appPidBefore = (@(
                Invoke-Adb -Arguments @("shell", "pidof", "-s", $PackageName) -AllowFailure
            ) -join "").Trim()
            $testPidBefore = (@(
                Invoke-Adb -Arguments @("shell", "pidof", "-s", $TestPackageName) -AllowFailure
            ) -join "").Trim()
            if (-not $appPidBefore -and -not $testPidBefore) { break }
            Start-Sleep -Milliseconds 250
        } while ([DateTime]::UtcNow -lt $quiescenceDeadline)
        if ($appPidBefore -or $testPidBefore) {
            throw "Exclusive device preflight failed: appPid=$appPidBefore testPid=$testPidBefore"
        }

        $arguments = @(
            "-s", $Serial, "shell", "am", "instrument", "-w", "-r",
            "-e", "class", $test.Identity
        )
        if ($testArguments.Count -gt 0) {
            foreach ($entry in $testArguments.GetEnumerator()) {
                $arguments += @("-e", [string]$entry.Key, [string]$entry.Value)
            }
        }
        $arguments += $Runner

        $process = Start-Process -FilePath $AdbPath -ArgumentList $arguments `
            -WindowStyle Hidden -RedirectStandardOutput $stdoutPath `
            -RedirectStandardError $stderrPath -PassThru
        # Vivo's cached-app freezer can suspend a headless instrumentation target while its camera,
        # codec, or Vulkan worker is still active. Apply one sticky unfreeze as soon as the process
        # appears and before the workload reaches capture/encode. This avoids the mid-frame scheduler
        # disturbance caused by periodic unconditional unfreeze calls while keeping the process
        # runnable for its lifetime only (the sticky state dies with this isolated test process).
        $launchStickyUnfreezeApplied = $false
        $launchStickyUnfreezePid = ""
        $launchStickyUnfreezeAt = ""
        $launchStickyUnfreezeOutput = @()
        $keepAliveStarted = $false
        $keepAliveStarts = 0
        $keepAliveOutput = @()
        $launchAdmissionDeadline = [DateTime]::UtcNow.AddSeconds(5)
        do {
            $launchPid = (@(
                Invoke-Adb -Arguments @("shell", "pidof", "-s", $PackageName) -AllowFailure
            ) -join "").Trim()
            if ($launchPid -match "^\d+$") {
                if ($UseKeepAliveActivity -and -not $keepAliveStarted) {
                    $keepAliveOutput = @(Start-DebugKeepAliveActivity)
                    $keepAliveStarted = $true
                    $keepAliveStarts += 1
                }
                $launchStickyUnfreezeOutput = @(Invoke-Adb -Arguments @(
                    "shell", "cmd", "activity", "unfreeze", "--sticky", $launchPid
                ))
                $lastObservedPid = $launchPid
                $launchStickyUnfreezePid = $launchPid
                $launchStickyUnfreezeAt = (Get-Date).ToString("o")
                $launchStickyUnfreezeApplied = $true
                break
            }
            Start-Sleep -Milliseconds 100
        } while (-not $process.HasExited -and [DateTime]::UtcNow -lt $launchAdmissionDeadline)
        $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        $watchdogTicks = 0
        $watchdogUnfreezes = 0
        $watchdogFreezeRecoveries = 0
        $threadDumpCaptured = $false
        $instrumentationStartedAt = [DateTime]::UtcNow
        while (-not $process.HasExited -and [DateTime]::UtcNow -lt $deadline) {
            Start-Sleep -Seconds 1
            $process.Refresh()
            if (($watchdogTicks % 5) -eq 0) {
                $pidText = (@(
                    Invoke-Adb -Arguments @("shell", "pidof", "-s", $PackageName) -AllowFailure
                ) -join "").Trim()
                if ($pidText -match "^\d+$") {
                    $lastObservedPid = $pidText
                    # A heavily loaded vendor build can create the target after the bounded launch
                    # poll. Apply the process-lifetime sticky unfreeze on the first watchdog PID too.
                    # Invoke-Adb is deliberately strict here: never record this control as applied
                    # unless both adb and ActivityManager report command success.
                    if (-not $launchStickyUnfreezeApplied) {
                        if ($UseKeepAliveActivity -and -not $keepAliveStarted) {
                            $keepAliveOutput = @(Start-DebugKeepAliveActivity)
                            $keepAliveStarted = $true
                            $keepAliveStarts += 1
                        }
                        $launchStickyUnfreezeOutput = @(Invoke-Adb -Arguments @(
                            "shell", "cmd", "activity", "unfreeze", "--sticky", $pidText
                        ))
                        $launchStickyUnfreezePid = $pidText
                        $launchStickyUnfreezeAt = (Get-Date).ToString("o")
                        $launchStickyUnfreezeApplied = $true
                    }
                    $waitChannel = (@(
                        Invoke-Adb -Arguments @(
                            "shell", "run-as", $PackageName, "cat",
                            "/proc/$pidText/task/$pidText/wchan"
                        ) -AllowFailure
                    ) -join "").Trim()
                    if ($waitChannel -eq "do_freezer_trap") {
                        $watchdogFreezeRecoveries += 1
                        Invoke-Adb -Arguments @("shell", "input", "keyevent", "KEYCODE_WAKEUP") -AllowFailure | Out-Null
                        Invoke-Adb -Arguments @("shell", "wm", "dismiss-keyguard") -AllowFailure | Out-Null
                        Invoke-Adb -Arguments @(
                            "shell", "cmd", "activity", "unfreeze", "--sticky", $PackageName
                        ) -AllowFailure | Out-Null
                        Invoke-Adb -Arguments @(
                            "shell", "cmd", "activity", "unfreeze", "--sticky", $pidText
                        ) -AllowFailure | Out-Null
                        $watchdogUnfreezes += 1
                        if ($UseKeepAliveActivity) {
                            $keepAliveOutput = @(Start-DebugKeepAliveActivity)
                            $keepAliveStarted = $true
                            $keepAliveStarts += 1
                        }
                    }
                    $elapsedInstrumentationSeconds =
                        ([DateTime]::UtcNow - $instrumentationStartedAt).TotalSeconds
                    if (
                        -not $threadDumpCaptured -and
                        $ThreadDumpAfterSeconds -gt 0 -and
                        $elapsedInstrumentationSeconds -ge $ThreadDumpAfterSeconds
                    ) {
                        Capture-ProcessDiagnostics `
                            -PidText $pidText `
                            -NativeBacktracePath $nativeBacktracePath `
                            -NativeBacktraceStderrPath $nativeBacktraceStderrPath `
                            -SignalResultPath $threadDumpSignalPath `
                            -LogcatSnapshotPath $threadDumpPath `
                            -ThreadStatePath $threadStatePath
                        $threadDumpCaptured = $true
                    }
                }
            }
            $watchdogTicks += 1
        }
        if (-not $process.HasExited) {
            if (-not $threadDumpCaptured -and $lastObservedPid -match "^\d+$") {
                Capture-ProcessDiagnostics `
                    -PidText $lastObservedPid `
                    -NativeBacktracePath $nativeBacktracePath `
                    -NativeBacktraceStderrPath $nativeBacktraceStderrPath `
                    -SignalResultPath $threadDumpSignalPath `
                    -LogcatSnapshotPath $threadDumpPath `
                    -ThreadStatePath $threadStatePath
                $threadDumpCaptured = $true
            }
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
            Invoke-Adb -Arguments @("shell", "am", "force-stop", $PackageName) -AllowFailure | Out-Null
            $result.timedOut = $true
            throw "Instrumentation timed out after $TimeoutSeconds seconds"
        }
        $process.WaitForExit()
        $result.timedOut = $false
        $result.watchdogTicks = $watchdogTicks
        $result.watchdogUnfreezes = $watchdogUnfreezes
        $result.watchdogFreezeRecoveries = $watchdogFreezeRecoveries
        $result.launchStickyUnfreezeApplied = $launchStickyUnfreezeApplied
        $result.launchStickyUnfreezePid = $launchStickyUnfreezePid
        $result.launchStickyUnfreezeAt = $launchStickyUnfreezeAt
        $result.launchStickyUnfreezeOutput = @($launchStickyUnfreezeOutput)
        $result.keepAliveRequested = [bool]$UseKeepAliveActivity
        $result.keepAliveStarted = $keepAliveStarted
        $result.keepAliveStarts = $keepAliveStarts
        $result.keepAliveOutput = @($keepAliveOutput)
        $result.threadDumpCaptured = $threadDumpCaptured

        $instrumentationText = Get-Content -Raw -LiteralPath $stdoutPath
        $validPass =
            $instrumentationText.Contains("INSTRUMENTATION_CODE: -1") -and
            $instrumentationText.Contains("OK (1 test)") -and
            -not $instrumentationText.Contains("FAILURES!!!") -and
            -not $instrumentationText.Contains("INSTRUMENTATION_FAILED")
        if (-not $validPass) {
            throw "Instrumentation did not produce an unambiguous one-test PASS"
        }
        $result.status = "PASS"
    } catch {
        $result.failure = $_.Exception.Message
    } finally {
        try {
            $logArgs = @("logcat", "-d", "-v", "threadtime")
            # `logcat --pid` rejects a PID after a short-lived instrumentation process exits, which
            # previously lost the entire already-buffered failure trace. The buffer was cleared at
            # this case's admission boundary, so a full-buffer fallback remains case-isolated while
            # preserving evidence from processes that retired before collection.
            $livePidAfterTest = (@(
                Invoke-Adb -Arguments @("shell", "pidof", "-s", $PackageName) -AllowFailure
            ) -join "").Trim()
            if ($lastObservedPid -match "^\d+$" -and $livePidAfterTest -eq $lastObservedPid) {
                $logArgs += @("--pid", $lastObservedPid)
            }
            @(Invoke-Adb -Arguments $logArgs -AllowFailure) |
                Set-Content -LiteralPath $logcatPath -Encoding UTF8
            @(Invoke-Adb -Arguments @("logcat", "-b", "crash", "-d", "-v", "threadtime") -AllowFailure) |
                Set-Content -LiteralPath $crashPath -Encoding UTF8
            $result.thermalAfter = Get-ThermalStatus
            $result.crashLogBytes = if (Test-Path -LiteralPath $crashPath) {
                (Get-Item -LiteralPath $crashPath).Length
            } else { 0 }
        } catch {
            $result.evidenceCaptureFailure = $_.Exception.Message
        }
        $result.completedAt = (Get-Date).ToString("o")
        if (-not $result.Contains("threadDumpCaptured")) {
            $result.threadDumpCaptured = Test-Path -LiteralPath $threadDumpPath -PathType Leaf
        }
        if (-not $result.Contains("launchStickyUnfreezeApplied")) {
            $result.launchStickyUnfreezeApplied = $launchStickyUnfreezeApplied
            $result.launchStickyUnfreezePid = $launchStickyUnfreezePid
            $result.launchStickyUnfreezeAt = $launchStickyUnfreezeAt
            $result.launchStickyUnfreezeOutput = @($launchStickyUnfreezeOutput)
        }
        if (-not $result.Contains("keepAliveRequested")) {
            $result.keepAliveRequested = [bool]$UseKeepAliveActivity
            $result.keepAliveStarted = $keepAliveStarted
            $result.keepAliveStarts = $keepAliveStarts
            $result.keepAliveOutput = @($keepAliveOutput)
        }
        $result.elapsedSeconds = [math]::Round(
            ([DateTime]$result.completedAt - [DateTime]$result.startedAt).TotalSeconds,
            3
        )
        $result | ConvertTo-Json -Depth 8 |
            Set-Content -LiteralPath $resultPath -Encoding UTF8
        $results.Add([pscustomobject]$result)
        Write-Host (
            "CP-217 {0:D3}/{1} {2} {3} ({4}s)" -f
            $test.Index, $canonicalTestCount, $result.status, $test.Identity, $result.elapsedSeconds
        )
    }
}

$summary = [ordered]@{
    schema = "neo-qa-cp217-isolated-summary-v1"
    serial = $Serial
    appInstalledSha256 = $appHash
    testInstalledSha256 = $testHash
    selected = $selected.Count
    passed = @($results | Where-Object { $_.status -eq "PASS" }).Count
    failed = @($results | Where-Object { $_.status -eq "FAIL" }).Count
    results = @($results)
    completedAt = (Get-Date).ToString("o")
}
$summary | ConvertTo-Json -Depth 8 |
    Set-Content -LiteralPath (Join-Path $ResolvedRoot "summary.json") -Encoding UTF8
@($results) | Select-Object index,status,elapsedSeconds,thermalBefore,thermalAfter,identity,failure |
    Export-Csv -NoTypeInformation -Encoding UTF8 -LiteralPath (Join-Path $ResolvedRoot "summary.csv")
$summary | ConvertTo-Json -Depth 5
if ($summary.failed -gt 0) { exit 1 }
