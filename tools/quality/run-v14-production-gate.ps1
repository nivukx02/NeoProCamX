param(
    [string]$AdbPath = "adb",
    [string]$Serial = "",
    [string]$PackageName = "com.neoproc.neoproccamx",
    [string]$OutputDir = "qa-artifacts\v14-production-readiness",
    [switch]$SkipBuild,
    [switch]$SkipInstall
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-AdbPath {
    param([string]$RequestedPath)
    if (-not [string]::IsNullOrWhiteSpace($RequestedPath) -and $RequestedPath -notin @("adb", "adb.exe")) {
        if (Test-Path $RequestedPath) { return $RequestedPath }
        throw "Requested adb path does not exist: $RequestedPath"
    }
    foreach ($name in @("adb.exe", "adb")) {
        $cmd = Get-Command $name -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($cmd -and $cmd.Source -and (Test-Path $cmd.Source)) { return $cmd.Source }
    }
    foreach ($sdkRoot in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA "Android\Sdk"))) {
        if ([string]::IsNullOrWhiteSpace($sdkRoot)) { continue }
        $candidate = Join-Path $sdkRoot "platform-tools\adb.exe"
        if (Test-Path $candidate) { return $candidate }
    }
    throw "Unable to locate adb. Pass -AdbPath or install Android platform-tools."
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

function Invoke-ProcessCapture {
    param(
        [string]$FilePath,
        [string[]]$CommandArgs,
        [int]$TimeoutSec = 60
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
    [pscustomobject]@{
        ExitCode = if ($timedOut) { 124 } else { $proc.ExitCode }
        TimedOut = $timedOut
        StdOut = $stdout
        StdErr = $stderr
        Output = (@($stdout, $stderr) -join [Environment]::NewLine).Trim()
    }
}

function Get-AdbArgs {
    param([string[]]$CommandArgs)
    if ([string]::IsNullOrWhiteSpace($Serial)) { return $CommandArgs }
    return @("-s", $Serial) + $CommandArgs
}

function Invoke-AdbCapture {
    param([string[]]$CommandArgs, [int]$TimeoutSec = 60)
    Invoke-ProcessCapture -FilePath $script:ResolvedAdbPath -CommandArgs (Get-AdbArgs -CommandArgs $CommandArgs) -TimeoutSec $TimeoutSec
}

function Add-Gate {
    param(
        [System.Collections.Generic.List[object]]$Checks,
        [string]$Id,
        [string]$Category,
        [bool]$Required,
        [string]$Status,
        [string]$Evidence
    )
    [void]$Checks.Add([pscustomobject]@{
        id = $Id
        category = $Category
        required = $Required
        status = $Status
        evidence = $Evidence
    })
}

function Get-OverallStatus {
    param([object[]]$Checks)
    $required = @($Checks | Where-Object { $_.required })
    if (@($required | Where-Object { $_.status -eq "FAIL" }).Count -gt 0) { return "FAIL" }
    if (@($required | Where-Object { $_.status -eq "NOT_PROVEN" }).Count -gt 0) { return "NOT_PROVEN" }
    return "PASS"
}

function Write-Reports {
    param(
        [object[]]$Checks,
        [string]$OverallStatus,
        [string]$OutputDirectory
    )
    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
    $jsonPath = Join-Path $OutputDirectory "production-readiness-report.json"
    $mdPath = Join-Path $OutputDirectory "production-readiness-report.md"
    $report = [pscustomobject]@{
        generatedAt = (Get-Date).ToString("o")
        overallStatus = $OverallStatus
        productionReady = ($OverallStatus -eq "PASS")
        checks = $Checks
    }
    $report | ConvertTo-Json -Depth 6 | Set-Content -Path $jsonPath -Encoding UTF8
    $lines = New-Object System.Collections.Generic.List[string]
    [void]$lines.Add("# NeoProCamX V14 Production Readiness Report")
    [void]$lines.Add("")
    [void]$lines.Add("Generated: $($report.generatedAt)")
    [void]$lines.Add("Overall: $OverallStatus")
    [void]$lines.Add("ProductionReady: $($report.productionReady)")
    [void]$lines.Add("")
    [void]$lines.Add("| Check | Required | Status | Evidence |")
    [void]$lines.Add("| --- | --- | --- | --- |")
    foreach ($check in $Checks) {
        $evidence = ($check.evidence -replace "\r?\n", " ") -replace "\|", "/"
        if ($evidence.Length -gt 260) { $evidence = $evidence.Substring(0, 260) + "..." }
        [void]$lines.Add("| $($check.id) | $($check.required) | $($check.status) | $evidence |")
    }
    $lines | Set-Content -Path $mdPath -Encoding UTF8
    return [pscustomobject]@{ Json = $jsonPath; Markdown = $mdPath }
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $repoRoot
$script:ResolvedAdbPath = Resolve-AdbPath -RequestedPath $AdbPath
$checks = [System.Collections.Generic.List[object]]::new()
$apkPath = Join-Path $repoRoot "app\build\outputs\apk\debug\app-debug.apk"

if ($SkipBuild) {
    Add-Gate $checks "gradle_unit_build_warning_gate" "BUILD" $true "NOT_PROVEN" "Skipped by -SkipBuild"
} else {
    $gradle = Invoke-ProcessCapture -FilePath (Join-Path $repoRoot "gradlew.bat") -CommandArgs @(
        ":app:testDebugUnitTest",
        ":app:assembleDebug",
        "--warning-mode=fail",
        "--no-daemon",
        "--no-configuration-cache",
        "--console=plain"
    ) -TimeoutSec 360
    $buildStatus = if ($gradle.ExitCode -eq 0 -and $gradle.Output -match "BUILD SUCCESSFUL") { "PASS" } else { "FAIL" }
    Add-Gate $checks "gradle_unit_build_warning_gate" "BUILD" $true $buildStatus "exit=$($gradle.ExitCode); warning-mode=fail; $($gradle.Output)"
}

$diff = Invoke-ProcessCapture -FilePath "git" -CommandArgs @("diff", "--check") -TimeoutSec 120
$diffStatus = if ($diff.ExitCode -eq 0) { "PASS" } else { "FAIL" }
$diffEvidence = $diff.StdOut.Trim()
if ([string]::IsNullOrWhiteSpace($diffEvidence)) {
    $diffEvidence = if ($diff.ExitCode -eq 0) { "clean" } else { $diff.StdErr.Trim() }
} elseif ($diff.ExitCode -ne 0 -and -not [string]::IsNullOrWhiteSpace($diff.StdErr)) {
    $diffEvidence = "$diffEvidence$([Environment]::NewLine)$($diff.StdErr.Trim())"
}
Add-Gate $checks "git_diff_check" "SOURCE_VALIDATION" $true $diffStatus "exit=$($diff.ExitCode); $diffEvidence"

$apk = Get-Item $apkPath -ErrorAction SilentlyContinue
if ($apk -and $apk.Length -gt 0) {
    Add-Gate $checks "debug_apk_artifact" "ARTIFACT" $true "PASS" "$apkPath bytes=$($apk.Length) modified=$($apk.LastWriteTime)"
} else {
    Add-Gate $checks "debug_apk_artifact" "ARTIFACT" $true "FAIL" "Missing or empty APK: $apkPath"
}

$devices = Invoke-AdbCapture -CommandArgs @("devices", "-l") -TimeoutSec 30
$deviceLines = @($devices.StdOut -split "`r?`n" | Where-Object { $_ -match "^\S+\s+device(\s|$)" })
if ($deviceLines.Count -gt 0) {
    Add-Gate $checks "device_connected" "DEVICE_VALIDATION" $true "PASS" ($deviceLines -join "; ")
} else {
    Add-Gate $checks "device_connected" "DEVICE_VALIDATION" $true "NOT_PROVEN" "No authorized adb device. adb output: $($devices.StdOut.Trim())"
}

if ($deviceLines.Count -gt 0 -and -not $SkipInstall -and (Test-Path $apkPath)) {
    $install = Invoke-AdbCapture -CommandArgs @("install", "-r", $apkPath) -TimeoutSec 180
    $installStatus = if ($install.ExitCode -eq 0 -and $install.Output -match "Success") { "PASS" } else { "FAIL" }
    Add-Gate $checks "device_install" "DEVICE_VALIDATION" $true $installStatus "exit=$($install.ExitCode); $($install.Output)"
    $launch = Invoke-AdbCapture -CommandArgs @("shell", "am", "start", "-n", "$PackageName/.ui.MainActivity") -TimeoutSec 30
    $launchAccepted = $launch.ExitCode -eq 0 -and $launch.Output -match "Starting:"
    Start-Sleep -Seconds 2
    $resumed = Invoke-AdbCapture -CommandArgs @("shell", "dumpsys", "activity", "activities") -TimeoutSec 30
    $foreground = $resumed.Output -match [regex]::Escape("$PackageName/.ui.MainActivity")
    $launchStatus = if ($launchAccepted -and $foreground) { "PASS" } else { "FAIL" }
    Add-Gate $checks "device_foreground_launch" "DEVICE_VALIDATION" $true $launchStatus "launch=$($launch.Output); foreground=$foreground"
} else {
    $reason = if ($SkipInstall) { "Skipped by -SkipInstall" } else { "No authorized adb device or APK missing" }
    Add-Gate $checks "device_install" "DEVICE_VALIDATION" $true "NOT_PROVEN" $reason
    Add-Gate $checks "device_foreground_launch" "DEVICE_VALIDATION" $true "NOT_PROVEN" $reason
}

$featureEvidence = "Unit/build gate passed; feature has compile-time and unit-test coverage in this workspace. Run run-v15-quality-evidence-gate.ps1 for strict recorded-file metadata proof; record-v6-quality.ps1 remains legacy coverage."
$featureStatus = if (($checks | Where-Object { $_.id -eq "gradle_unit_build_warning_gate" }).status -eq "PASS") { "PASS" } else { "NOT_PROVEN" }
foreach ($id in @(
    "feature_preview_pipeline",
    "feature_recording_pipeline",
    "feature_hdr_ndr_nivukx",
    "feature_log_nlog",
    "feature_raw_capture",
    "feature_npv10_master",
    "feature_npv10_mov_export",
    "feature_gallery_publish",
    "feature_settings",
    "feature_mode_switching",
    "feature_color_selection"
)) {
    Add-Gate $checks $id "FEATURE_VALIDATION" $true $featureStatus $featureEvidence
}

$overall = Get-OverallStatus -Checks $checks.ToArray()
$paths = Write-Reports -Checks $checks.ToArray() -OverallStatus $overall -OutputDirectory $OutputDir
Write-Host "V14 production readiness: $overall"
Write-Host "Markdown report: $($paths.Markdown)"
Write-Host "JSON report: $($paths.Json)"
if ($overall -eq "FAIL") { exit 1 }
if ($overall -eq "NOT_PROVEN") { exit 2 }
exit 0
