param(
    [string]$SceneManifestPath = "",
    [string]$EvidenceDir = "",
    [string]$OutputDir = "qa-artifacts\singularity-scene-matrix",
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($SceneManifestPath)) {
    $SceneManifestPath = Join-Path $PSScriptRoot "singularity-scenes.json"
}
if (-not (Test-Path $SceneManifestPath)) {
    throw "Missing Singularity scene manifest: $SceneManifestPath"
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$reportPath = Join-Path $OutputDir "singularity-scene-matrix-report.json"
$manifest = Get-Content $SceneManifestPath -Raw | ConvertFrom-Json
$requiredGateIds = @("QG01","QG02","QG03","QG04","QG05","QG06","QG07","QG08","QG09","QG10","QG11","QG12","QG13","QG14","QG15","QG16")
$coverageNames = @($manifest.gateCoverage.PSObject.Properties | ForEach-Object { $_.Name })
$missingCoverage = @($requiredGateIds | Where-Object { $coverageNames -notcontains $_ })
$declaredCoverageTargets = @(
    @($manifest.requiredScenes)
    @($manifest.requiredCharts)
    @($manifest.requiredEndurance)
    @($manifest.artifactRequirements)
) | ForEach-Object { $_ }
$unknownCoverageTargets = @()
foreach ($property in $manifest.gateCoverage.PSObject.Properties) {
    foreach ($target in @($property.Value)) {
        if ($declaredCoverageTargets -notcontains [string]$target) {
            $unknownCoverageTargets += "$($property.Name):$target"
        }
    }
}
$artifactOutputs = @(
    "QG01_edge_overshoot.json",
    "QG02_temporal_shimmer.json",
    "QG03_chroma_crawl.json",
    "QG04_highlight_rolloff.json",
    "QG05_shadow_depth.json",
    "QG06_skin_integrity.json",
    "QG07_fine_texture.json",
    "QG08_text_stability.json",
    "QG09_neural_resolution_honesty.json",
    "QG10_tone_stability.json",
    "QG11_color_neutrality.json",
    "QG12_encode_survival.json",
    "QG13_preview_match.json",
    "QG14_thermal_endurance.json",
    "QG15_dropped_frame_control.json",
    "QG16_metadata_honesty.json"
)
$missingArtifactOutputs = @()
if (-not $DryRun) {
    if ([string]::IsNullOrWhiteSpace($EvidenceDir) -or -not (Test-Path $EvidenceDir)) {
        $missingArtifactOutputs = $artifactOutputs
    } else {
        $missingArtifactOutputs = @($artifactOutputs | Where-Object { -not (Test-Path (Join-Path $EvidenceDir $_)) })
    }
}

$status = if ($missingCoverage.Count -eq 0 -and $unknownCoverageTargets.Count -eq 0 -and $missingArtifactOutputs.Count -eq 0) {
    if ($DryRun) { "DRY_RUN" } else { "PASS" }
} else {
    if ($missingArtifactOutputs.Count -gt 0 -and $missingCoverage.Count -eq 0 -and $unknownCoverageTargets.Count -eq 0) {
        "NOT_TESTED_ARTIFACTS_REQUIRED"
    } else {
        "FAIL"
    }
}
$report = [ordered]@{
    FormatVersion = "SINGULARITY_SCENE_MATRIX_REPORT_1"
    Status = $status
    Stage6SceneMatrixStatus = if ($status -eq "PASS") { "PASS" } elseif ($status -eq "DRY_RUN") { "NOT_TESTED" } else { $status }
    ManifestPath = $SceneManifestPath
    RequiredModeCount = @($manifest.requiredModes).Count
    RequiredOutputModeCount = @($manifest.requiredOutputModes).Count
    RequiredSceneCount = @($manifest.requiredScenes).Count
    RequiredChartCount = @($manifest.requiredCharts).Count
    RequiredEnduranceCount = @($manifest.requiredEndurance).Count
    RequiredReviewSurfaceCount = @($manifest.requiredReviewSurfaces).Count
    RequiredGateIds = $requiredGateIds
    MissingGateCoverage = $missingCoverage
    UnknownGateCoverageTargets = $unknownCoverageTargets
    RequiredScenes = @($manifest.requiredScenes)
    RequiredCharts = @($manifest.requiredCharts)
    RequiredEndurance = @($manifest.requiredEndurance)
    RequiredReviewSurfaces = @($manifest.requiredReviewSurfaces)
    ArtifactOutputs = $artifactOutputs
    EvidenceDir = $EvidenceDir
    MissingArtifactOutputs = $missingArtifactOutputs
}
$report | ConvertTo-Json -Depth 12 | Set-Content -Path $reportPath -Encoding UTF8
if ($missingCoverage.Count -gt 0) {
    throw "Singularity scene matrix is missing gate coverage. Report: $reportPath"
}
if ($unknownCoverageTargets.Count -gt 0) {
    throw "Singularity scene matrix references undeclared coverage targets. Report: $reportPath"
}
if ($missingArtifactOutputs.Count -gt 0) {
    throw "Singularity scene matrix artifacts are not tested. Report: $reportPath"
}
Write-Host "Singularity scene matrix report -> $reportPath"
