# Parse completed physical-device audit evidence even when its integrity gate returns FAIL.
# Execution errors and malformed/inconsistent evidence still throw; no gate is weakened.
function ConvertFrom-QANprawAuditResult {
    param(
        [Parameter(Mandatory = $true)][string]$Json,
        [Parameter(Mandatory = $true)][int]$ExitCode
    )
    if ($ExitCode -notin @(0, 1)) { throw "NPRAW auditor execution failed ($ExitCode)" }
    $audit = $Json | ConvertFrom-Json -ErrorAction Stop
    $expected = if ($ExitCode -eq 0) { 'PASS' } else { 'FAIL' }
    if ($audit.status -ne $expected) { throw 'NPRAW audit exit/status mismatch' }
    foreach ($field in @('measuredFps', 'frameCount', 'crcFailures', 'fieldFailures',
                          'nonMonotonicGaps', 'gapsOver60ms', 'storageBitDepth')) {
        $property = $audit.PSObject.Properties[$field]
        if ($null -eq $property -or $null -eq $property.Value -or
            $property.Value -is [string] -or $property.Value -is [bool]) {
            throw "NPRAW audit numeric evidence missing: $field"
        }
        $value = [double]$property.Value
        if ([double]::IsNaN($value) -or [double]::IsInfinity($value) -or $value -lt 0) {
            throw "NPRAW audit numeric evidence invalid: $field"
        }
    }
    $audit | Add-Member -NotePropertyName processExitCode -NotePropertyValue $ExitCode
    return $audit
}
