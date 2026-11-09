param(
    [string]$OutputDir = "qa-artifacts\singularity-endurance",
    [string]$EvidenceJsonPath = "",
    [int]$PreviewMinutes = 5,
    [int]$Recording1080pMinutes = 10,
    [int]$Recording4kMinutes = 10,
    [int]$ThermalStressMinutes = 20,
    [int]$StartStopCycles = 20,
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$reportPath = Join-Path $OutputDir "singularity-endurance-report.json"
$requiredScenarios = @(
    "5-minute preview",
    "10-minute 1080p recording",
    "10-minute 4K recording where supported",
    "20-minute thermal stress",
    "20 repeated start/stop cycles",
    "background/foreground transition",
    "low-battery session",
    "storage-pressure session",
    "near-full and full-storage finalization"
)
$parameterFailures = @()
if ($PreviewMinutes -lt 5) { $parameterFailures += "PreviewMinutes<5" }
if ($Recording1080pMinutes -lt 10) { $parameterFailures += "Recording1080pMinutes<10" }
if ($Recording4kMinutes -lt 10) { $parameterFailures += "Recording4kMinutes<10" }
if ($ThermalStressMinutes -lt 20) { $parameterFailures += "ThermalStressMinutes<20" }
if ($StartStopCycles -lt 20) { $parameterFailures += "StartStopCycles<20" }
$completedScenarios = @()
$externalStatus = ""
if (-not [string]::IsNullOrWhiteSpace($EvidenceJsonPath) -and (Test-Path $EvidenceJsonPath)) {
    $external = Get-Content $EvidenceJsonPath -Raw | ConvertFrom-Json
    $externalStatus = [string]$external.Status
    $completedScenarios = @($external.CompletedEnduranceIds)
}
$missingScenarios = @($requiredScenarios | Where-Object { $completedScenarios -notcontains $_ })
$status = if ($parameterFailures.Count -gt 0) {
    "FAIL"
} elseif ($DryRun) {
    "DRY_RUN"
} elseif ([string]::IsNullOrWhiteSpace($EvidenceJsonPath) -or -not (Test-Path $EvidenceJsonPath)) {
    "NOT_TESTED_DEVICE_REQUIRED"
} elseif ($externalStatus -eq "PASS" -and $missingScenarios.Count -eq 0) {
    "PASS"
} else {
    "FAIL"
}
$report = [ordered]@{
    FormatVersion = "SINGULARITY_ENDURANCE_REPORT_1"
    Status = $status
    Stage6EnduranceStatus = if ($status -eq "PASS") { "PASS" } elseif ($status -eq "DRY_RUN") { "NOT_TESTED" } else { $status }
    QG14 = "Thermal Endurance"
    QG15 = "Dropped Frame Control"
    RequiredPreviewMinutes = $PreviewMinutes
    Required1080pRecordingMinutes = $Recording1080pMinutes
    Required4kRecordingMinutes = $Recording4kMinutes
    RequiredThermalStressMinutes = $ThermalStressMinutes
    RequiredStartStopCycles = $StartStopCycles
    RequiredScenarios = $requiredScenarios
    CompletedEnduranceIds = $completedScenarios
    MissingEnduranceIds = $missingScenarios
    EvidenceJsonPath = $EvidenceJsonPath
    ParameterFailures = $parameterFailures
}
$report | ConvertTo-Json -Depth 10 | Set-Content -Path $reportPath -Encoding UTF8
if ($parameterFailures.Count -gt 0) {
    throw "Endurance parameters are below Stage 6 minimums. Report: $reportPath"
}
if ($status -ne "PASS" -and -not $DryRun) {
    throw "Endurance execution requires a connected device runner and complete evidence. Report: $reportPath"
}
Write-Host "Singularity endurance report -> $reportPath"
