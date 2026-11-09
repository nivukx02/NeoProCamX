# Shared, side-effect-free interpretation of the Android thermalservice evidence.
# A cached value is the last threshold event, not necessarily the current temperature.
function ConvertFrom-QAThermalServiceSnapshot {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Text)

    $statusMatch = [regex]::Match($Text, 'Thermal Status:\s*([0-6])\b')
    $status = if ($statusMatch.Success) { [int]$statusMatch.Groups[1].Value } else { -1 }
    $currentMatch = [regex]::Match(
        $Text,
        '(?ms)^Current temperatures from HAL:\s*\r?\n(.*?)(?=^Current cooling devices from HAL:|^Temperature static thresholds from HAL:|^Temperature headroom thresholds:|\z)'
    )
    $cachedMatch = [regex]::Match(
        $Text,
        '(?ms)^Cached temperatures:\s*\r?\n(.*?)(?=^HAL Ready:|^HAL connection:|^Current temperatures from HAL:|\z)'
    )
    $readTemperatures = {
        param([string]$Section)
        foreach ($entry in [regex]::Matches(
            $Section,
            'Temperature\{mValue=([^,]+),\s*mType=(-?\d+),\s*mName=([^,]+),\s*mStatus=([0-6])\}'
        )) {
            $value = 0.0
            if ([double]::TryParse(
                    $entry.Groups[1].Value,
                    [Globalization.NumberStyles]::Float,
                    [Globalization.CultureInfo]::InvariantCulture,
                    [ref]$value) -and
                -not [double]::IsNaN($value) -and -not [double]::IsInfinity($value)) {
                [pscustomobject]@{
                    Value = $value
                    Type = [int]$entry.Groups[2].Value
                    Name = $entry.Groups[3].Value
                    Status = [int]$entry.Groups[4].Value
                }
            }
        }
    }
    $current = @(& $readTemperatures $currentMatch.Groups[1].Value)
    $cached = @(& $readTemperatures $cachedMatch.Groups[1].Value)
    $skin = $current | Where-Object { $_.Type -eq 3 } | Sort-Object Value -Descending | Select-Object -First 1
    $battery = $current | Where-Object { $_.Type -eq 2 } | Sort-Object Value -Descending | Select-Object -First 1
    $cachedSkin = $cached | Where-Object { $_.Type -eq 3 } | Sort-Object Value -Descending | Select-Object -First 1
    $halMaximum = if ($current.Count -gt 0) { [int]($current | Measure-Object Status -Maximum).Maximum } else { -1 }
    [pscustomobject]@{
        Text = $Text
        Status = $status
        CurrentHalMaximumStatus = $halMaximum
        AdmissionStatus = [math]::Max($status, $halMaximum)
        SkinC = if ($skin) { $skin.Value.ToString('0.###', [Globalization.CultureInfo]::InvariantCulture) } else { 'NA' }
        SkinSource = if ($skin) { 'CURRENT_HAL' } else { 'CURRENT_HAL_UNAVAILABLE' }
        SkinSensor = if ($skin) { $skin.Name } else { '' }
        BatteryC = if ($battery) { $battery.Value.ToString('0.###', [Globalization.CultureInfo]::InvariantCulture) } else { 'NA' }
        CachedSkinC = if ($cachedSkin) { $cachedSkin.Value.ToString('0.###', [Globalization.CultureInfo]::InvariantCulture) } else { 'NA' }
    }
}
