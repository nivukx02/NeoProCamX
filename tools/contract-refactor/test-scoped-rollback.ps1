Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$engine = Join-Path $PSScriptRoot "contract-refactor.ps1"
$manifest = Join-Path $PSScriptRoot "rollback-smoke-manifest.json"
$mutation = Join-Path $PSScriptRoot "fixtures/rollback-mutation.ps1"
$hostFile = Join-Path $PSScriptRoot "fixtures/rollback-host.txt"
$protectedFile = Join-Path $PSScriptRoot "fixtures/rollback-protected.txt"
$hostHashBefore = (Get-FileHash -LiteralPath $hostFile -Algorithm SHA256).Hash
$protectedHashBefore = (Get-FileHash -LiteralPath $protectedFile -Algorithm SHA256).Hash
$failedAsDesigned = $false

try {
    & $engine -Mode Run -Manifest $manifest -MutationScript $mutation
} catch {
    if ($_.Exception.Message -notmatch "Synthetic mutation failure") { throw }
    $failedAsDesigned = $true
}

if (-not $failedAsDesigned) { throw "Rollback smoke mutation unexpectedly passed." }
$hostHashAfter = (Get-FileHash -LiteralPath $hostFile -Algorithm SHA256).Hash
$protectedHashAfter = (Get-FileHash -LiteralPath $protectedFile -Algorithm SHA256).Hash
if ($hostHashBefore -ne $hostHashAfter) { throw "Scoped rollback did not restore the mutable host file." }
if ($protectedHashBefore -ne $protectedHashAfter) { throw "Scoped rollback changed the protected file." }
Write-Host "Scoped rollback smoke test passed."
