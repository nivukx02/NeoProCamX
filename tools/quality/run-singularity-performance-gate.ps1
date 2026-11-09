param(
    [string]$AdbPath = "adb",
    [string]$Serial = "",
    [string]$PackageName = "com.neoproc.neoproccamx",
    [string]$OutputDir = "qa-artifacts\singularity-performance-gate",
    [string]$EvidenceJsonPath = "",
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$reportPath = Join-Path $OutputDir "singularity-performance-gate-report.json"
$v16Script = Join-Path $PSScriptRoot "run-v16-real-device-encoder-validation.ps1"
$requiredMetrics = @("CpuUsagePercent", "MaxFrameTimeMs", "TargetFrameTimeMs", "DroppedFrames", "ThermalStatus")
$externalStatus = ""
$presentMetrics = @()
if (-not [string]::IsNullOrWhiteSpace($EvidenceJsonPath) -and (Test-Path $EvidenceJsonPath)) {
    $external = Get-Content $EvidenceJsonPath -Raw | ConvertFrom-Json
    $externalStatus = [string]$external.Status
    $presentMetrics = @($requiredMetrics | Where-Object { $null -ne $external.$_ })
}
$missingMetrics = @($requiredMetrics | Where-Object { $presentMetrics -notcontains $_ })
$status = if ($DryRun) {
    "DRY_RUN"
} elseif (-not (Test-Path $v16Script)) {
    "FAIL"
} elseif ([string]::IsNullOrWhiteSpace($EvidenceJsonPath) -or -not (Test-Path $EvidenceJsonPath)) {
    "NOT_TESTED_DEVICE_REQUIRED"
} elseif ($externalStatus -eq "PASS" -and $missingMetrics.Count -eq 0) {
    "PASS"
} else {
    "FAIL"
}
$report = [ordered]@{
    FormatVersion = "SINGULARITY_PERFORMANCE_GATE_REPORT_1"
    Status = $status
    Stage6PerformanceStatus = if ($status -eq "PASS") { "PASS" } elseif ($status -eq "DRY_RUN") { "NOT_TESTED" } else { $status }
    Stage6NativePathStatus = if ($status -eq "PASS") { "PASS" } elseif ($status -eq "DRY_RUN") { "NOT_TESTED" } else { $status }
    PackageName = $PackageName
    Serial = $Serial
    V16Script = $v16Script
    RequiredCollectors = @("dumpsys gfxinfo", "top -b -n 1", "logcat", "thermalservice", "MediaCodec cadence")
    RequiredGates = @("QG14", "QG15")
    RequiredMetrics = $requiredMetrics
    PresentMetrics = $presentMetrics
    MissingMetrics = $missingMetrics
    EvidenceJsonPath = $EvidenceJsonPath
}
$report | ConvertTo-Json -Depth 10 | Set-Content -Path $reportPath -Encoding UTF8
if (-not $DryRun) {
    if (-not (Test-Path $v16Script)) {
        throw "Missing V16 performance evidence script: $v16Script"
    }
    if ($status -ne "PASS") {
        throw "Performance gate requires connected-device evidence and calibrated metrics. Report: $reportPath"
    }
}
Write-Host "Singularity performance report -> $reportPath"
