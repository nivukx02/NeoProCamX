param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
)

$ErrorActionPreference = "Stop"

$specRoot = Join-Path $RepositoryRoot "docs\superpowers\specs"
$designPath = Join-Path $specRoot "2026-06-08-singularity-imax-design.md"

if (-not (Test-Path -LiteralPath $designPath -PathType Leaf)) {
    throw "Missing Singularity specification file: $designPath"
}

$designLines = Get-Content -LiteralPath $designPath
$design = Get-Content -Raw -LiteralPath $designPath

$stageMatches = @(
    $designLines |
        Select-String -Pattern '^- ([0-9]{3}) `([a-z0-9_]+)`$'
)

if ($stageMatches.Count -ne 256) {
    throw "Expected 256 named stages, found $($stageMatches.Count)."
}

$stageIds = @(
    $stageMatches |
        ForEach-Object { [int]$_.Matches[0].Groups[1].Value }
)

for ($expected = 0; $expected -lt 256; $expected++) {
    if ($stageIds[$expected] -ne $expected) {
        throw "Stage ID sequence breaks at index ${expected}: found $($stageIds[$expected])."
    }
}

$duplicateNames = @(
    $stageMatches |
        ForEach-Object { $_.Matches[0].Groups[2].Value } |
        Group-Object |
        Where-Object Count -gt 1
)

if ($duplicateNames.Count -gt 0) {
    throw "Duplicate stage names: $($duplicateNames.Name -join ', ')"
}

$requiredDesignTerms = @(
    "com.neoproc.neoproccamx",
    "SMF-SINGULARITY-1",
    "NPV10-SINGULARITY-MASTER",
    "NDR10X-SINGULARITY",
    "NLOG-SINGULARITY",
    "NRAW-SINGULARITY",
    "multi_lens_fusion",
    "depth_subject",
    "texture_authority",
    "QG01 Edge Overshoot",
    "QG16 Metadata Honesty",
    "NOT_TESTED",
    "SINGULARITY-100",
    "CORE-HONEST",
    "NO-RELEASE",
    "## 21. Complete 256-Stage Catalog",
    "## 22. Source-To-Project Traceability"
)

foreach ($term in $requiredDesignTerms) {
    if (-not $design.Contains($term)) {
        throw "Design is missing required term: $term"
    }
}

$blockCount = @(
    $designLines |
        Select-String -Pattern '^### Block [0-9]{2} - '
).Count

if ($blockCount -ne 16) {
    throw "Expected 16 stage blocks, found $blockCount."
}

Write-Output "Singularity specification validation PASS"
Write-Output "Stages: 256"
Write-Output "Blocks: 16"
Write-Output "Namespace: com.neoproc.neoproccamx"
Write-Output "Authority: SMF-SINGULARITY-1"
Write-Output "FileMode: single"
