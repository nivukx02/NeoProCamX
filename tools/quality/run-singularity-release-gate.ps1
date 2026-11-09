param(
    [string]$QualityReportPath = "",
    [string]$CertificatePath = "",
    [string]$SmfPath = "",
    [string]$DeviceLabReportPath = "",
    [string]$SceneMatrixReportPath = "",
    [string]$EnduranceReportPath = "",
    [string]$PerformanceReportPath = "",
    [string]$OutputDir = "qa-artifacts\singularity-release-gate",
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$requiredGateIds = @(
    "QG01", "QG02", "QG03", "QG04",
    "QG05", "QG06", "QG07", "QG08",
    "QG09", "QG10", "QG11", "QG12",
    "QG13", "QG14", "QG15", "QG16"
)

function Write-SingularityJson {
    param(
        [Parameter(Mandatory=$true)]$Data,
        [Parameter(Mandatory=$true)][string]$Path
    )
    $parent = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    $Data | ConvertTo-Json -Depth 12 | Set-Content -Path $Path -Encoding UTF8
}

function Read-RequiredJsonReport {
    param(
        [Parameter(Mandatory=$true)][string]$Path,
        [Parameter(Mandatory=$true)][string]$Name
    )
    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path $Path)) {
    return [pscustomobject][ordered]@{
        Name = $Name
        Path = $Path
        Status = "MISSING"
        Json = $null
        }
    }
    $json = Get-Content $Path -Raw | ConvertFrom-Json
    $status = if ($null -ne $json.Status -and -not [string]::IsNullOrWhiteSpace([string]$json.Status)) {
        [string]$json.Status
    } else {
        "MALFORMED"
    }
    return [pscustomobject][ordered]@{
        Name = $Name
        Path = $Path
        Status = $status
        Json = $json
    }
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$reportPath = Join-Path $OutputDir "singularity-release-gate-report.json"

if ($DryRun) {
    Write-SingularityJson -Path $reportPath -Data ([ordered]@{
        FormatVersion = "SINGULARITY_RELEASE_GATE_REPORT_1"
        Status = "DRY_RUN"
        RequiredGateIds = $requiredGateIds
        RequiredInputs = @(
            "qg-results.json",
            "quality-certificate.txt",
            "SMF-SINGULARITY-1",
            "singularity-device-lab-report.json",
            "singularity-scene-matrix-report.json",
            "singularity-endurance-report.json",
            "singularity-performance-gate-report.json"
        )
        RequiredStage6Statuses = @("DeviceLab=PASS", "SceneMatrix=PASS", "Endurance=PASS", "Performance=PASS", "LargeScreenReview=PASS", "NativePath=PASS")
        GatePolicyVersion = "QG_SINGULARITY_1"
        ReleasePolicyVersion = "SINGULARITY_RELEASE_POLICY_1"
        ExternalCertificationClaimsRequiredFalse = @(
            "officialImaxCertificationClaimed",
            "officialDolbyVisionCertificationClaimed",
            "officialHdr10PlusCertificationClaimed",
            "officialHdr10CertificationClaimed"
        )
    })
    Write-Host "Dry-run Singularity release gate report -> $reportPath"
    exit 0
}

if ([string]::IsNullOrWhiteSpace($QualityReportPath) -or -not (Test-Path $QualityReportPath)) {
    throw "Missing Stage 4 quality gate report: $QualityReportPath"
}
if ([string]::IsNullOrWhiteSpace($CertificatePath) -or -not (Test-Path $CertificatePath)) {
    throw "Missing recording quality certificate: $CertificatePath"
}
if ([string]::IsNullOrWhiteSpace($SmfPath) -or -not (Test-Path $SmfPath)) {
    throw "Missing SMF-Singularity sidecar: $SmfPath"
}

$qualityReport = Get-Content $QualityReportPath -Raw | ConvertFrom-Json
$gateRows = @($qualityReport.qualityGates)
$gateIds = @($gateRows | ForEach-Object { [string]$_.gateId })
$missingGateIds = @($requiredGateIds | Where-Object { $gateIds -notcontains $_ })
$duplicateGateIds = @($gateIds | Group-Object | Where-Object { $_.Count -gt 1 } | ForEach-Object { $_.Name })
$unknownGateIds = @($gateIds | Where-Object { $requiredGateIds -notcontains $_ })
$unknownStatuses = @($gateRows | Where-Object {
    @("PASS","WARN","FAIL","NOT_APPLICABLE","NOT_TESTED") -notcontains [string]$_.status
} | ForEach-Object { "$($_.gateId):$($_.status)" })
$missingEvidenceReferences = @($gateRows | Where-Object {
    [string]$_.mandatory -eq "True" -and [string]::IsNullOrWhiteSpace([string]$_.evidenceReference)
} | ForEach-Object { [string]$_.gateId })
$notPassed = @($gateRows | Where-Object { [string]$_.mandatory -eq "True" -and [string]$_.status -ne "PASS" })
$failed = @($gateRows | Where-Object { [string]$_.status -eq "FAIL" })
$certificateText = Get-Content $CertificatePath -Raw
$smfText = Get-Content $SmfPath -Raw

$falseClaimTokens = @(
    "officialImaxCertificationClaimed=true",
    "officialDolbyVisionCertificationClaimed=true",
    "officialHdr10PlusCertificationClaimed=true",
    "officialHdr10CertificationClaimed=true",
    "vendorHdrCertification=true",
    "vendorCertifiedHdr10Plus=true",
    "vendorCertifiedDolbyVision=true",
    "dolbyRpuWritten=true",
    "externalHdrStandardSubstitution=true"
)
$falseClaims = @($falseClaimTokens | Where-Object { $certificateText -match [regex]::Escape($_) })
$smfMissing = @()
if ($smfText -notmatch '"formatVersion"\s*:\s*"SMF-SINGULARITY-1"') { $smfMissing += "SMF-SINGULARITY-1" }
if ($smfText -notmatch '"officialImaxCertificationClaimed"\s*:\s*false') { $smfMissing += "officialImaxCertificationClaimed=false" }
if ($smfText -notmatch '"officialDolbyVisionCertificationClaimed"\s*:\s*false') { $smfMissing += "officialDolbyVisionCertificationClaimed=false" }
if ($smfText -notmatch '"officialHdr10PlusCertificationClaimed"\s*:\s*false') { $smfMissing += "officialHdr10PlusCertificationClaimed=false" }
if ($smfText -notmatch '"officialHdr10CertificationClaimed"\s*:\s*false') { $smfMissing += "officialHdr10CertificationClaimed=false" }

$stage6Reports = @(
    Read-RequiredJsonReport -Path $DeviceLabReportPath -Name "DeviceLab",
    Read-RequiredJsonReport -Path $SceneMatrixReportPath -Name "SceneMatrix",
    Read-RequiredJsonReport -Path $EnduranceReportPath -Name "Endurance",
    Read-RequiredJsonReport -Path $PerformanceReportPath -Name "Performance"
)
$stage6NotPassed = @($stage6Reports | Where-Object { [string]$_.Status -ne "PASS" } | ForEach-Object { "$($_.Name):$($_.Status)" })
$deviceLabReport = $stage6Reports | Where-Object { $_.Name -eq "DeviceLab" } | Select-Object -First 1
$deviceLabJson = $deviceLabReport.Json
$largeScreenReviewStatus = if ($null -ne $deviceLabJson -and $null -ne $deviceLabJson.LargeScreenReviewStatus) {
    [string]$deviceLabJson.LargeScreenReviewStatus
} else {
    "MISSING"
}
$nativePathStatus = if ($null -ne $deviceLabJson -and $null -ne $deviceLabJson.NativePathStatus) {
    [string]$deviceLabJson.NativePathStatus
} else {
    "MISSING"
}
if ($largeScreenReviewStatus -ne "PASS") { $stage6NotPassed += "LargeScreenReview:$largeScreenReviewStatus" }
if ($nativePathStatus -ne "PASS") { $stage6NotPassed += "NativePath:$nativePathStatus" }

$status = if (
    $missingGateIds.Count -eq 0 -and
    $duplicateGateIds.Count -eq 0 -and
    $unknownGateIds.Count -eq 0 -and
    $unknownStatuses.Count -eq 0 -and
    $missingEvidenceReferences.Count -eq 0 -and
    $notPassed.Count -eq 0 -and
    $failed.Count -eq 0 -and
    $falseClaims.Count -eq 0 -and
    $smfMissing.Count -eq 0 -and
    $stage6NotPassed.Count -eq 0
) {
    "PASS"
} else {
    "FAIL"
}

Write-SingularityJson -Path $reportPath -Data ([ordered]@{
    FormatVersion = "SINGULARITY_RELEASE_GATE_REPORT_1"
    Status = $status
    QualityReportPath = $QualityReportPath
    CertificatePath = $CertificatePath
    SmfPath = $SmfPath
    DeviceLabReportPath = $DeviceLabReportPath
    SceneMatrixReportPath = $SceneMatrixReportPath
    EnduranceReportPath = $EnduranceReportPath
    PerformanceReportPath = $PerformanceReportPath
    QualityGateCount = $gateRows.Count
    MissingGateIds = $missingGateIds
    DuplicateGateIds = $duplicateGateIds
    UnknownGateIds = $unknownGateIds
    UnknownGateStatuses = $unknownStatuses
    MissingEvidenceReferences = $missingEvidenceReferences
    MandatoryNotPassed = @($notPassed | ForEach-Object { [string]$_.gateId })
    FailedGateIds = @($failed | ForEach-Object { [string]$_.gateId })
    FalseCertificationClaims = $falseClaims
    SmfMissingRequirements = $smfMissing
    Stage6Reports = $stage6Reports
    Stage6NotPassed = $stage6NotPassed
    GatePolicyVersion = "QG_SINGULARITY_1"
    ReleasePolicyVersion = "SINGULARITY_RELEASE_POLICY_1"
})

if ($status -ne "PASS") {
    throw "Singularity release gate failed. Report: $reportPath"
}

Write-Host "Singularity release gate PASS -> $reportPath"
