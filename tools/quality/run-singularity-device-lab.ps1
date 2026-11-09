param(
    [string]$AdbPath = "adb",
    [string]$Serial = "",
    [string]$PackageName = "com.neoproc.neoproccamx",
    [string]$OutputDir = "qa-artifacts\singularity-device-lab",
    [string]$SceneMatrixReportPath = "",
    [string]$EnduranceReportPath = "",
    [string]$PerformanceReportPath = "",
    [string]$ReleaseGateReportPath = "",
    [string]$LargeScreenReviewReportPath = "",
    [string]$NativePathReportPath = "",
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$sceneScript = Join-Path $PSScriptRoot "run-singularity-scene-matrix.ps1"
$enduranceScript = Join-Path $PSScriptRoot "run-singularity-endurance.ps1"
$performanceScript = Join-Path $PSScriptRoot "run-singularity-performance-gate.ps1"
$releaseScript = Join-Path $PSScriptRoot "run-singularity-release-gate.ps1"
$v17Script = Join-Path $PSScriptRoot "run-v17-recorded-quality-repair-gate.ps1"
$reportPath = Join-Path $OutputDir "singularity-device-lab-report.json"
$requiredScripts = @($sceneScript, $enduranceScript, $performanceScript, $releaseScript, $v17Script)
$missingScripts = @($requiredScripts | Where-Object { -not (Test-Path $_) })
$stage6ReportPaths = [ordered]@{
    SceneMatrix = $SceneMatrixReportPath
    Endurance = $EnduranceReportPath
    Performance = $PerformanceReportPath
    ReleaseGate = $ReleaseGateReportPath
    LargeScreenReview = $LargeScreenReviewReportPath
    NativePath = $NativePathReportPath
}

function Get-ReportStatus {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path $Path)) {
        return "MISSING"
    }
    $json = Get-Content $Path -Raw | ConvertFrom-Json
    if ($null -eq $json.Status -or [string]::IsNullOrWhiteSpace([string]$json.Status)) {
        return "MALFORMED"
    }
    return [string]$json.Status
}

$stage6ReportStatuses = [ordered]@{}
foreach ($item in $stage6ReportPaths.GetEnumerator()) {
    $stage6ReportStatuses[$item.Key] = Get-ReportStatus -Path $item.Value
}
$missingStage6Reports = @($stage6ReportStatuses.GetEnumerator() | Where-Object { $_.Value -eq "MISSING" } | ForEach-Object { $_.Key })
$notPassedStage6Reports = @($stage6ReportStatuses.GetEnumerator() | Where-Object { $_.Value -ne "PASS" } | ForEach-Object { "$($_.Key):$($_.Value)" })
$deviceState = "NOT_CHECKED"
if (-not $DryRun -and $missingScripts.Count -eq 0) {
    $adbArgs = @("devices")
    $adbOutput = & $AdbPath @adbArgs 2>$null
    if ($LASTEXITCODE -eq 0) {
        $deviceLines = @($adbOutput | Where-Object { $_ -match "\tdevice$" })
        if (-not [string]::IsNullOrWhiteSpace($Serial)) {
            $deviceLines = @($deviceLines | Where-Object { $_ -match "^$([regex]::Escape($Serial))\tdevice$" })
        }
        $deviceState = if ($deviceLines.Count -gt 0) { "ONLINE" } else { "OFFLINE_OR_MISSING" }
    } else {
        $deviceState = "ADB_ERROR"
    }
}

$status = if ($missingScripts.Count -gt 0) {
    "FAIL"
} elseif ($DryRun) {
    "DRY_RUN"
} elseif ($deviceState -ne "ONLINE") {
    "NOT_TESTED_DEVICE_REQUIRED"
} elseif ($notPassedStage6Reports.Count -eq 0) {
    "PASS"
} else {
    "FAIL"
}

$report = [ordered]@{
    FormatVersion = "SINGULARITY_DEVICE_LAB_REPORT_1"
    Status = $status
    Stage6DeviceLabStatus = if ($status -eq "PASS") { "PASS" } elseif ($status -eq "DRY_RUN") { "NOT_TESTED" } else { $status }
    LargeScreenReviewStatus = Get-ReportStatus -Path $LargeScreenReviewReportPath
    NativePathStatus = Get-ReportStatus -Path $NativePathReportPath
    PackageName = $PackageName
    Serial = $Serial
    DeviceState = $deviceState
    RequiredScripts = $requiredScripts
    MissingScripts = $missingScripts
    Stage6ReportPaths = $stage6ReportPaths
    Stage6ReportStatuses = $stage6ReportStatuses
    MissingStage6Reports = $missingStage6Reports
    NotPassedStage6Reports = $notPassedStage6Reports
    RequiredOutputs = @(
        "singularity-scene-matrix-report.json",
        "singularity-endurance-report.json",
        "singularity-performance-gate-report.json",
        "singularity-release-gate-report.json",
        "qg-results.json"
    )
    RequiredGateIds = @("QG01","QG02","QG03","QG04","QG05","QG06","QG07","QG08","QG09","QG10","QG11","QG12","QG13","QG14","QG15","QG16")
}
$report | ConvertTo-Json -Depth 10 | Set-Content -Path $reportPath -Encoding UTF8
if ($missingScripts.Count -gt 0) {
    throw "Singularity device lab missing scripts. Report: $reportPath"
}
if (-not $DryRun) {
    if ($deviceState -ne "ONLINE") {
        throw "Device lab requires a connected calibrated device. Report: $reportPath"
    }
    if ($notPassedStage6Reports.Count -gt 0) {
        throw "Device lab requires PASS Stage 6 reports. Report: $reportPath"
    }
}
Write-Host "Singularity device-lab report -> $reportPath"
