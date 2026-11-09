param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [Parameter(Mandatory = $true)]
    [string]$ClassName,

    [Parameter(Mandatory = $true)]
    [string]$ArtifactRoot,

    [ValidateNotNullOrEmpty()]
    [string]$Checkpoint = 'LQ-031',

    [ValidateRange(30, 1800)]
    [int]$TimeoutSeconds = 900,

    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot 'bounded_adb_cleanup.ps1')
$PackageName = "com.neoproc.neoproccamx"
$TestPackageName = "$PackageName.test"
$Runner = "$TestPackageName/androidx.test.runner.AndroidJUnitRunner"

if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    throw "ADB executable was not found: $AdbPath"
}

$ResolvedRoot = [System.IO.Path]::GetFullPath($(if ([System.IO.Path]::IsPathRooted($ArtifactRoot)) {
    $ArtifactRoot
} else {
    Join-Path $PWD $ArtifactRoot
}))
New-Item -ItemType Directory -Force -Path $ResolvedRoot | Out-Null
$RunRoot = Join-Path $ResolvedRoot (
    "instrumentation-{0}-{1}" -f (Get-Date -Format "yyyyMMdd-HHmmssfff"), $PID
)
New-Item -ItemType Directory -Force -Path $RunRoot | Out-Null

function Invoke-Adb {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    $output = & $AdbPath -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "ADB failed (exit=$LASTEXITCODE): $($Arguments -join ' ')`n$($output -join "`n")"
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

$report = [ordered]@{
    schema = "neo-qa-lq031-instrumentation-v1"
    checkpoint = $Checkpoint
    serial = $Serial
    class = $ClassName
    status = "FAIL"
    startedAt = (Get-Date).ToString("o")
}

try {
    $devices = Invoke-Adb -Arguments @("devices", "-l")
    if (-not (@($devices | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+device\b" }).Count)) {
        throw "ADB target is not online: $Serial"
    }
    $instrumentation = Invoke-Adb -Arguments @("shell", "pm", "list", "instrumentation")
    if (-not (@($instrumentation | Where-Object { $_ -like "*$Runner*" }).Count)) {
        throw "Instrumentation runner is not installed: $Runner"
    }

    $report.deviceModel = (@(Invoke-Adb -Arguments @("shell", "getprop", "ro.product.model"))[0]).Trim()
    $report.deviceFingerprint = (@(Invoke-Adb -Arguments @("shell", "getprop", "ro.build.fingerprint"))[0]).Trim()
    $report.androidRelease = (@(Invoke-Adb -Arguments @("shell", "getprop", "ro.build.version.release"))[0]).Trim()
    $report.appInstalledSha256 = Get-RemotePackageHash -Package $PackageName
    $report.testInstalledSha256 = Get-RemotePackageHash -Package $TestPackageName
    $report.storageBefore = @(Invoke-Adb -Arguments @("shell", "df", "-h", "/data"))
    $report.batteryBefore = @(Invoke-Adb -Arguments @("shell", "dumpsys", "battery"))
    $report.thermalBefore = @(Invoke-Adb -Arguments @("shell", "dumpsys", "thermalservice"))

    Invoke-Adb -Arguments @("logcat", "-c") | Out-Null
    Invoke-Adb -Arguments @("shell", "am", "force-stop", $PackageName) | Out-Null
    Invoke-Adb -Arguments @("shell", "input", "keyevent", "KEYCODE_WAKEUP") | Out-Null
    Invoke-Adb -Arguments @("shell", "wm", "dismiss-keyguard") | Out-Null
    $stdoutPath = Join-Path $RunRoot "instrumentation.txt"
    $stderrPath = Join-Path $RunRoot "instrumentation-stderr.txt"
    $arguments = @(
        "-s", $Serial, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", $ClassName, $Runner
    )
    $processOptions = @{
        FilePath = $AdbPath
        ArgumentList = $arguments
        WindowStyle = "Hidden"
        RedirectStandardOutput = $stdoutPath
        RedirectStandardError = $stderrPath
        PassThru = $true
    }
    $process = Start-Process @processOptions

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $watchdogTicks = 0
    $watchdogUnfreezes = 0
    $watchdogFreezeRecoveries = 0
    $watchdogUnfreezeFailures = 0
    $watchdogKeepAliveStarts = 0
    $watchdogRetiredProcessProbes = 0
    $keepAliveStarted = $false
    while (-not $process.HasExited -and [DateTime]::UtcNow -lt $deadline) {
        Start-Sleep -Milliseconds 750
        $process.Refresh()
        if (($watchdogTicks++ % 2) -eq 0) {
            # Instrumentation starts the target process asynchronously after `am instrument` begins.
            # Address Vivo's cached-app freezer by exact PID. Package-name requests can return exit 0
            # while leaving an instrumentation process in do_freezer_trap, which invalidates a codec
            # result. A sticky PID unfreeze is scoped to this process lifetime and cannot alter pixels,
            # codec policy, bitrate, bit depth, resolution, or any production conversion behavior.
            $targetPid = & $AdbPath -s $Serial shell pidof -s $PackageName 2>$null
            if ($LASTEXITCODE -eq 0 -and ([string]$targetPid).Trim() -match "^\d+$") {
                $targetPid = ([string]$targetPid).Trim()
                if (-not $keepAliveStarted) {
                    # The component exists only in the debug variant used by this physical oracle. It is
                    # intentionally inert and owns no camera/codec/GPU; the release APK cannot expose it.
                    $keepAliveOutput = @(
                        & $AdbPath -s $Serial shell am start -W -n `
                            "$PackageName/.testing.Lq031KeepAliveActivity" 2>&1
                    )
                    if (
                        $LASTEXITCODE -eq 0 -and
                        ($keepAliveOutput -match "(?m)^Status:\s*ok\s*$")
                    ) {
                        $keepAliveStarted = $true
                        $watchdogKeepAliveStarts += 1
                    }
                }
                $unfreezeOutput = @(
                    & $AdbPath -s $Serial shell cmd activity unfreeze --sticky $targetPid 2>&1
                )
                if ($LASTEXITCODE -eq 0 -and -not ($unfreezeOutput -match "(?i)error")) {
                    $watchdogUnfreezes += 1
                } else {
                    $watchdogUnfreezeFailures += 1
                }

                # The instrumented process may exit normally between pidof and this optional probe.
                # Capture its exit/error as data; PowerShell native stderr must not abort evaluation
                # of the authoritative completed test result. Other failures remain in the report.
                $waitProbe = Invoke-QABoundedAdbCleanup -AdbPath $AdbPath -Serial $Serial `
                    -Arguments @('shell', 'run-as', $PackageName, 'cat', "/proc/$targetPid/task/$targetPid/wchan")
                $mainThreadWait = if ($waitProbe.success) { $waitProbe.output } else { '' }
                if (-not $waitProbe.success) {
                    if ($waitProbe.error -match 'No such file or directory') {
                        $watchdogRetiredProcessProbes += 1
                    } else {
                        $report.watchdogProbeFailure = $waitProbe.error
                    }
                }
                if ($mainThreadWait.Trim() -eq "do_freezer_trap") {
                    $watchdogFreezeRecoveries += 1
                    & $AdbPath -s $Serial shell input keyevent KEYCODE_WAKEUP *> $null
                    & $AdbPath -s $Serial shell wm dismiss-keyguard *> $null
                    & $AdbPath -s $Serial shell cmd activity unfreeze --sticky $PackageName *> $null
                    & $AdbPath -s $Serial shell cmd activity unfreeze --sticky $targetPid *> $null
                    # Vivo can immediately reapply its vendor freeze when another task covers the
                    # oracle. Reassert the inert debug activity; becoming TOP is the only device-proven
                    # operation that clears both Android's and Vivo's independent freezer states.
                    & $AdbPath -s $Serial shell am start -W -n `
                        "$PackageName/.testing.Lq031KeepAliveActivity" *> $null
                    if ($LASTEXITCODE -eq 0) { $watchdogKeepAliveStarts += 1 }
                }
            }
        }
        if (($watchdogTicks % 24) -eq 0) {
            # A physical-device codec/GPU oracle is invalid if the OEM suspends the device halfway
            # through it. Keep the display power domain awake without injecting any UI coordinates.
            & $AdbPath -s $Serial shell input keyevent KEYCODE_WAKEUP *> $null
        }
    }
    if (-not $process.HasExited) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        Invoke-Adb -Arguments @("shell", "am", "force-stop", $PackageName) | Out-Null
        throw "Instrumentation timed out after $TimeoutSeconds seconds"
    }
    $process.WaitForExit()
    $report.hostAdbExitCode = $process.ExitCode
    $report.watchdogTicks = $watchdogTicks
    $report.watchdogUnfreezes = $watchdogUnfreezes
    $report.watchdogFreezeRecoveries = $watchdogFreezeRecoveries
    $report.watchdogUnfreezeFailures = $watchdogUnfreezeFailures
    $report.watchdogKeepAliveStarts = $watchdogKeepAliveStarts
    $report.watchdogRetiredProcessProbes = $watchdogRetiredProcessProbes
    $report.keepAliveStarted = $keepAliveStarted

    $instrumentationText = Get-Content -Raw -LiteralPath $stdoutPath
    $testCountMatch = [regex]::Match($instrumentationText, "OK \((\d+) tests?\)")
    $validPass =
        $instrumentationText.Contains("INSTRUMENTATION_CODE: -1") -and
        $testCountMatch.Success -and
        -not $instrumentationText.Contains("FAILURES!!!") -and
        -not $instrumentationText.Contains("INSTRUMENTATION_FAILED")
    if (-not $validPass) {
        throw "Instrumentation did not produce an unambiguous passing result"
    }
    $report.testsPassed = [int]$testCountMatch.Groups[1].Value
    $report.status = "PASS"
} catch {
    $report.failure = $_.Exception.Message
} finally {
    try {
        $postmortem = [ordered]@{}
        foreach ($probe in @(
            @{ name = 'logcat'; arguments = @('logcat', '-d', '-v', 'threadtime') },
            @{ name = 'crashBuffer'; arguments = @('logcat', '-b', 'crash', '-d', '-v', 'threadtime') },
            @{ name = 'thermalAfter'; arguments = @('shell', 'dumpsys', 'thermalservice') },
            @{ name = 'storageAfter'; arguments = @('shell', 'df', '-h', '/data') }
        )) {
            $result = Invoke-QABoundedAdbCleanup -AdbPath $AdbPath -Serial $Serial -Arguments $probe.arguments
            $postmortem[$probe.name] = $result
            if ($probe.name -eq 'logcat') {
                $result.output | Set-Content -LiteralPath (Join-Path $RunRoot 'logcat.txt') -Encoding UTF8
            } else {
                $report[$probe.name] = @($result.output -split '\r?\n')
            }
            if (-not $result.success) { $report.cleanupFailure = "Postmortem $($probe.name): $($result.error)" }
        }
        $postmortem | ConvertTo-Json -Depth 5 |
            Set-Content -LiteralPath (Join-Path $RunRoot 'postmortem-result.json') -Encoding UTF8
    } catch {
        $report.cleanupFailure = $_.Exception.Message
    }
    # Each command is independent and bounded, including after USB loss or failed log collection.
    $report.deviceCleanup = [ordered]@{
        force_stop = Invoke-QABoundedAdbCleanup -AdbPath $AdbPath -Serial $Serial `
            -Arguments @('shell', 'am', 'force-stop', $PackageName)
        screen_off = Invoke-QABoundedAdbCleanup -AdbPath $AdbPath -Serial $Serial `
            -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_SLEEP')
    }
    if (-not $report.deviceCleanup.force_stop.success -or -not $report.deviceCleanup.screen_off.success) {
        $report.cleanupFailure = 'Camera closure or screen-off could not be proven'
    }
    if ($report.Contains('cleanupFailure')) { $report.status = 'FAIL' }
    $report.completedAt = (Get-Date).ToString("o")
    $report | ConvertTo-Json -Depth 8 |
        Set-Content -LiteralPath (Join-Path $RunRoot "result.json") -Encoding UTF8
    Split-Path -Leaf $RunRoot |
        Set-Content -LiteralPath (Join-Path $ResolvedRoot "LATEST") -Encoding ASCII
}

$report | ConvertTo-Json -Depth 8
if ($report.status -ne "PASS") { exit 1 }
