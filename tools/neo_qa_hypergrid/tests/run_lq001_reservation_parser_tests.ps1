$ErrorActionPreference = 'Stop'
$runnerPath = Join-Path $PSScriptRoot '..\run_lq001_endurance.ps1'
$source = Get-Content -Raw -LiteralPath $runnerPath
$definition = [regex]::Match($source, '(?s)\$readReservationLong = \{.*?return \$Default\s+\}')
if (-not $definition.Success) { throw 'Production reservation parser was not found' }
$calls = [regex]::Matches($source, '(?m)^\s*\$\w+ = & \$readReservationLong ''[^'']+'' [^\r\n]+')
if ($calls.Count -ne 8) { throw "Expected eight production parser calls; found $($calls.Count)" }
. ([scriptblock]::Create($definition.Value))

$reservationText = 'Npv10PhysicalReservationTelemetry(enabled=true, initialReservedBytes=10737418240, currentReservedBytes=85899345920, rollingLeadBytes=1073741824, rollingExtensionBytes=10737418240, extensionSuccesses=7, totalExtendedBytes=75161927680, extensionFailures=0, unreservedWriteRefusals=0)'
foreach ($call in $calls) { . ([scriptblock]::Create($call.Value)) }
$expected = @{
    initialReservedBytes = 10737418240L
    currentReservedBytes = 85899345920L
    rollingLeadBytes = 1073741824L
    rollingExtensionBytes = 10737418240L
    rollingExtensionCount = 7L
    rollingExtendedBytes = 75161927680L
    extensionFailures = 0L
    unreservedWriteRefusals = 0L
}
foreach ($entry in $expected.GetEnumerator()) {
    $actual = Get-Variable -Name $entry.Key -ValueOnly
    if ($actual -isnot [long] -or $actual -ne $entry.Value) {
        throw "Invalid $($entry.Key): expected $($entry.Value) as Int64, got $actual"
    }
}
$reservationText = 'Npv10PhysicalReservationTelemetry(enabled=false)'
foreach ($call in $calls) { . ([scriptblock]::Create($call.Value)) }
foreach ($name in $expected.Keys) {
    $fallback = if ($name -in @('rollingExtensionCount', 'rollingExtendedBytes')) { 0L } else { -1L }
    $actual = Get-Variable -Name $name -ValueOnly
    if ($actual -isnot [long] -or $actual -ne $fallback) {
        throw "Missing evidence did not retain the fail-closed default for $name"
    }
}
if ($source -notmatch 'early_stop_origin = \$earlyStopMarker' -or
    $source -notmatch 'external_stop_origin = \$externalStopMarker') {
    throw 'Runner errors must retain the primary recording stop origins'
}
Write-Output 'PASS: 8 exact Int64 values, 8 fail-closed defaults, primary stop-origin retention; no ADB used.'
