$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\thermal_evidence.ps1')
$checks = 0
function Assert-QAEqual($Expected, $Actual, [string]$Name) {
    if ($Expected -cne $Actual) { throw "$Name expected=$Expected actual=$Actual" }
    $script:checks++
}
$snapshot = @'
Thermal Status: 4
Cached temperatures:
    Temperature{mValue=48.014, mType=3, mName=skin, mStatus=4}
HAL Ready: true
Current temperatures from HAL:
    Temperature{mValue=56.864, mType=3, mName=skin, mStatus=4}
    Temperature{mValue=49.9, mType=2, mName=battery, mStatus=0}
Current cooling devices from HAL:
    CoolingDevice{mValue=10, mType=3, mName=gpu}
'@
$result = ConvertFrom-QAThermalServiceSnapshot $snapshot
Assert-QAEqual 4 $result.Status 'framework status preserved'
Assert-QAEqual '56.864' $result.SkinC 'current HAL replaces cached event temperature'
Assert-QAEqual '48.014' $result.CachedSkinC 'cached event retained separately'
Assert-QAEqual '49.9' $result.BatteryC 'current battery temperature'
Assert-QAEqual 'CURRENT_HAL' $result.SkinSource 'temperature provenance'
Assert-QAEqual 4 $result.AdmissionStatus 'no framework thermal override'
# Use an explicit cached-only report; no cached temperature may silently become live proof.
$missing = ConvertFrom-QAThermalServiceSnapshot "Thermal Status: 4`nCached temperatures:`n    Temperature{mValue=48.014, mType=3, mName=skin, mStatus=4}`nHAL Ready: false`n"
Assert-QAEqual 'NA' $missing.SkinC 'missing live temperature is unknown'
Assert-QAEqual 'CURRENT_HAL_UNAVAILABLE' $missing.SkinSource 'missing source is explicit'
$hot = ConvertFrom-QAThermalServiceSnapshot ($snapshot.Replace('mValue=56.864', 'mValue=61').Replace('mName=skin, mStatus=4}', 'mName=skin, mStatus=5}'))
Assert-QAEqual 4 $hot.Status 'framework status must not be rewritten'
Assert-QAEqual 5 $hot.AdmissionStatus 'newer emergency HAL status blocks admission'
$multiple = $snapshot.Replace('    Temperature{mValue=56.864', "    Temperature{mValue=57.2, mType=3, mName=skin-back, mStatus=4}`n    Temperature{mValue=56.864")
$multi = ConvertFrom-QAThermalServiceSnapshot $multiple
Assert-QAEqual '57.2' $multi.SkinC 'hottest current skin sensor'
Assert-QAEqual 'skin-back' $multi.SkinSensor 'no hardcoded skin sensor name'
foreach ($invalid in 'NaN', 'Infinity', 'invalid') {
    $bad = ConvertFrom-QAThermalServiceSnapshot ($snapshot.Replace('56.864', $invalid))
    Assert-QAEqual 'NA' $bad.SkinC "invalid temperature $invalid"
}
$empty = ConvertFrom-QAThermalServiceSnapshot ''
Assert-QAEqual -1 $empty.Status 'missing framework status fails closed'
Assert-QAEqual 'NA' $empty.SkinC 'empty report is not evidence'
$negative = ConvertFrom-QAThermalServiceSnapshot ($snapshot.Replace('56.864', '-2.5'))
Assert-QAEqual '-2.5' $negative.SkinC 'negative finite reading preserved'
$oldCulture = [Threading.Thread]::CurrentThread.CurrentCulture
try {
    [Threading.Thread]::CurrentThread.CurrentCulture = [Globalization.CultureInfo]::GetCultureInfo('fr-FR')
    $localized = ConvertFrom-QAThermalServiceSnapshot $snapshot
    Assert-QAEqual '56.864' $localized.SkinC 'invariant decimal parsing and evidence'
} finally { [Threading.Thread]::CurrentThread.CurrentCulture = $oldCulture }
Write-Output "PASS: $checks thermal evidence assertions; no ADB and no physical performance claim."
