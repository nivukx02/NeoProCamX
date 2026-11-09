$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$sourceRoot = Join-Path $root 'app/src/main/java'
$forbidden = @(
    'guaranteed',
    'complete ISP bypass',
    'bit-perfect',
    'bit perfect',
    'BitPerfect',
    'true zero-copy',
    'cinema-grade',
    'hardware-native',
    'lossless',
    'perfect quality',
    'without loss',
    'preserves original sensor data',
    'No quantization artifacts',
    'unlimited bitrate',
    'full sensor data without re-encoding',
    'uncompressed 12-bit RAW'
)
$allowedMachineStringValues = @(
    'LOSSLESS',
    'LOSSLESS_ENHANCED',
    'VISUALLY_LOSSLESS',
    'ZIP_LOSSLESS_16BIT'
)

$violations = New-Object System.Collections.Generic.List[string]
$files = Get-ChildItem -Path $sourceRoot -Recurse -Include *.kt

foreach ($file in $files) {
    $relative = $file.FullName.Substring($root.Length + 1)
    $lineNumber = 0
    $inBlockComment = $false
    $inRawString = $false
    foreach ($line in Get-Content -LiteralPath $file.FullName) {
        $lineNumber += 1
        $scanParts = New-Object System.Collections.Generic.List[string]

        $searchStart = 0
        if ($inBlockComment) {
            $endIndex = $line.IndexOf('*/', [System.StringComparison]::Ordinal)
            if ($endIndex -ge 0) {
                $scanParts.Add($line.Substring(0, $endIndex + 2))
                $searchStart = $endIndex + 2
                $inBlockComment = $false
            } else {
                $scanParts.Add($line)
                $searchStart = $line.Length
            }
        }

        while ($searchStart -lt $line.Length) {
            $startIndex = $line.IndexOf('/*', $searchStart, [System.StringComparison]::Ordinal)
            if ($startIndex -lt 0) {
                break
            }
            $endIndex = $line.IndexOf('*/', $startIndex + 2, [System.StringComparison]::Ordinal)
            if ($endIndex -ge 0) {
                $scanParts.Add($line.Substring($startIndex, $endIndex - $startIndex + 2))
                $searchStart = $endIndex + 2
            } else {
                $scanParts.Add($line.Substring($startIndex))
                $inBlockComment = $true
                $searchStart = $line.Length
            }
        }

        $lineCommentIndex = $line.IndexOf('//', [System.StringComparison]::Ordinal)
        if ($lineCommentIndex -ge 0) {
            $scanParts.Add($line.Substring($lineCommentIndex))
        }
        if ($line -match '^\s*\*') {
            $scanParts.Add($line)
        }

        $rawStringDelimiterCount = [regex]::Matches($line, '"""').Count
        if ($inRawString -or $rawStringDelimiterCount -gt 0) {
            $scanParts.Add($line)
        }
        if (($rawStringDelimiterCount % 2) -eq 1) {
            $inRawString = -not $inRawString
        }

        if ($rawStringDelimiterCount -eq 0) {
            $quotedStrings = [regex]::Matches($line, '"((?:\\.|[^"\\])*)"')
            foreach ($quotedString in $quotedStrings) {
                $value = $quotedString.Groups[1].Value
                if ($allowedMachineStringValues -cnotcontains $value) {
                    $scanParts.Add($value)
                }
            }
        }

        if ($scanParts.Count -eq 0) {
            continue
        }
        foreach ($term in $forbidden) {
            foreach ($scanPart in $scanParts) {
                if ($scanPart.IndexOf($term, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
                    $violations.Add("${relative}:${lineNumber}: ${term}: ${line.Trim()}")
                    break
                }
            }
        }
    }
}

if ($violations.Count -gt 0) {
    $violations | ForEach-Object { [Console]::Error.WriteLine($_) }
    exit 1
}

Write-Host 'Truthful hardware language scan passed.'
