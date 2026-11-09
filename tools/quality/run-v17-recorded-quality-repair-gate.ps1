param(
    [string]$AdbPath = "adb",
    [string]$Serial = "",
    [string]$PackageName = "com.neoproc.neoproccamx",
    [string]$OutputDir = "qa-artifacts\v17-recorded-quality-repair",
    [int]$DurationMs = 2600,
    [int]$PostRecordWaitSec = 34,
    [string[]]$IncludeRows = @(),
    [int]$MaxRows = 0,
    [string]$VideoDetailProfile = "EXTREME_DETAIL",
    [switch]$SkipInstall,
    [switch]$IsolateRows,
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$v16Script = Join-Path $PSScriptRoot "run-v16-real-device-encoder-validation.ps1"
$v16Output = Join-Path $OutputDir "v16"
$v16JsonPath = Join-Path $v16Output "v16-real-device-encoder-report.json"
$modesUnderAudit = @("Normal", "Pro", "N-Log", "NDR10+", "NIVUKX Vision", "NPV10")
$requiredEvidence = @(
    "actualResolution",
    "gpuProcessingPath",
    "processedRecordingFramesValidated",
    "syntheticGrainEnabled",
    "sourceBitDepth",
    "storageBitDepth",
    "rotationDegrees",
    "mirrored"
)
$probeContract = "ffprobe and MediaInfo recorded-file validation inherited from V16"

if (-not (Test-Path $v16Script)) {
    throw "Missing V16 real-device encoder gate: $v16Script"
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$v16Args = @(
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", $v16Script,
    "-AdbPath", $AdbPath,
    "-PackageName", $PackageName,
    "-OutputDir", $v16Output,
    "-DurationMs", $DurationMs.ToString(),
    "-PostRecordWaitSec", $PostRecordWaitSec.ToString()
)
if (-not [string]::IsNullOrWhiteSpace($VideoDetailProfile)) {
    $v16Args += @("-VideoDetailProfile", $VideoDetailProfile)
}
if (-not [string]::IsNullOrWhiteSpace($Serial)) {
    $v16Args += @("-Serial", $Serial)
}
if ($SkipInstall) {
    $v16Args += "-SkipInstall"
}
if ($IsolateRows) {
    $v16Args += "-IsolateRows"
}
if ($DryRun) {
    $v16Args += "-DryRun"
}
if ($MaxRows -gt 0) {
    $v16Args += @("-MaxRows", $MaxRows.ToString())
}
if ($IncludeRows.Count -gt 0) {
    $v16Args += @("-IncludeRows", ($IncludeRows -join ","))
}

& powershell @v16Args
$v16Exit = $LASTEXITCODE
if ($DryRun) {
    exit $v16Exit
}

if (-not (Test-Path $v16JsonPath)) {
    throw "V16 recorded-file validation report not found: $v16JsonPath"
}

$v16Data = Get-Content $v16JsonPath -Raw | ConvertFrom-Json
$v16Rows = New-Object System.Collections.Generic.List[object]
foreach ($item in $v16Data) {
    [void]$v16Rows.Add($item)
}
$summary = foreach ($row in $v16Rows) {
    $certificateText = if (
        -not [string]::IsNullOrWhiteSpace([string]$row.CertificatePath) -and
        (Test-Path ([string]$row.CertificatePath))
    ) {
        Get-Content ([string]$row.CertificatePath) -Raw
    } else {
        ""
    }
    $logText = if (
        -not [string]::IsNullOrWhiteSpace([string]$row.LogPath) -and
        (Test-Path ([string]$row.LogPath))
    ) {
        Get-Content ([string]$row.LogPath) -Raw
    } else {
        ""
    }
    $evidenceText = "$certificateText`n$logText"
    $rowBitDepth = if ($row.PSObject.Properties["BitDepth"] -and $row.BitDepth) { [int]$row.BitDepth } else { 0 }
    $byteBufferP010Proof = if ($row.PSObject.Properties["ByteBufferP010Proof"]) {
        ([string]$row.ByteBufferP010Proof) -match "^(true|1)$"
    } else {
        $false
    }
    $actualResolution = if ($row.PSObject.Properties["Resolution"]) { [string]$row.Resolution } else { "" }
    $gpuProcessingPath = if ($row.PSObject.Properties["GpuProcessingPath"]) { [string]$row.GpuProcessingPath } else { "" }
    $sourceBitDepth = if ($byteBufferP010Proof -and $rowBitDepth -ge 10) { $rowBitDepth } else { 0 }
    $storageBitDepth = if ($rowBitDepth -ge 10) { $rowBitDepth } else { 0 }
    $rotationDegrees = if ($row.PSObject.Properties["RotationDegrees"]) { [string]$row.RotationDegrees } else { "" }
    $mirrored = if ($row.PSObject.Properties["Mirrored"]) { [string]$row.Mirrored } else { "" }
    $syntheticGrainProofPresent = $evidenceText -match "syntheticGrainEnabled=(true|false)"
    $syntheticGrainDetected = $evidenceText -match "syntheticGrainEnabled=true"
    $processedFramesValidated =
        $byteBufferP010Proof -or
        $evidenceText -match "processedRecordingFramesValidated=true"
    $missingEvidence = @()
    if ([string]::IsNullOrWhiteSpace($actualResolution)) { $missingEvidence += "actualResolution" }
    if ([string]::IsNullOrWhiteSpace($gpuProcessingPath)) { $missingEvidence += "gpuProcessingPath" }
    if (-not $processedFramesValidated) { $missingEvidence += "processedRecordingFramesValidated" }
    if ($sourceBitDepth -lt 10) { $missingEvidence += "sourceBitDepth" }
    if ($storageBitDepth -lt 10) { $missingEvidence += "storageBitDepth" }
    if ([string]::IsNullOrWhiteSpace($rotationDegrees)) { $missingEvidence += "rotationDegrees" }
    if ([string]::IsNullOrWhiteSpace($mirrored)) { $missingEvidence += "mirrored" }
    if (-not $syntheticGrainProofPresent) { $missingEvidence += "syntheticGrainEnabled" }
    $requiresProcessedFrames = ([string]$row.Mode) -notmatch "NPV10|RAW"
    $unprovenReasons = New-Object System.Collections.Generic.List[string]
    $failureReasons = New-Object System.Collections.Generic.List[string]
    if ($missingEvidence.Count -gt 0) {
        [void]$unprovenReasons.Add("missing evidence: $($missingEvidence -join ', ')")
    }
    if ($requiresProcessedFrames -and -not $processedFramesValidated) {
        [void]$unprovenReasons.Add("processed recording frames were not validated")
    }
    $proprietaryMode = ([string]$row.Mode) -in @("HDR", "LOG")
    if ($proprietaryMode) {
        foreach ($field in @(
            "vendorHdrCertification=false",
            "officialHdr10PlusMetadataWritten=false",
            "vendorCertifiedHdr10Plus=false",
            "vendorCertifiedDolbyVision=false",
            "dolbyRpuWritten=false",
            "externalHdrStandardSubstitution=false"
        )) {
            $key = $field.Substring(0, $field.IndexOf("="))
            $expected = $field.Substring($field.IndexOf("=") + 1)
            $property = $row.PSObject.Properties[$key]
            $value = if ($property -and $property.Value -ne $null) { [string]$property.Value } else { "" }
            if ([string]::IsNullOrWhiteSpace($value)) {
                if ($evidenceText -notmatch [regex]::Escape($field)) {
                    [void]$unprovenReasons.Add("missing proprietary metadata proof: $field")
                }
            } elseif ($value -ne $expected) {
                [void]$failureReasons.Add("$key must be $expected but was $value")
            }
        }
        if ($evidenceText -notmatch [regex]::Escape("proprietaryProfilePreserved=true")) {
            [void]$unprovenReasons.Add("missing proprietaryProfilePreserved=true proof")
        }
    }
    $status = if (([string]$row.Status) -eq "FAIL" -or $syntheticGrainDetected -or $failureReasons.Count -gt 0) {
        "FAIL"
    } elseif ($unprovenReasons.Count -gt 0 -or ([string]$row.Status) -eq "NOT_PROVEN") {
        "NOT_PROVEN"
    } else {
        "PASS"
    }
    [pscustomobject]@{
        Status = $status
        Mode = $row.Mode
        RowId = $row.RowId
        Resolution = $actualResolution
        Codec = $row.Codec
        BitDepth = $row.BitDepth
        Transfer = $row.Transfer
        Primaries = $row.Primaries
        Matrix = $row.Matrix
        GpuProcessingPath = $gpuProcessingPath
        SourceBitDepth = $sourceBitDepth
        StorageBitDepth = $storageBitDepth
        RotationDegrees = $rotationDegrees
        Mirrored = $mirrored
        ProcessedRecordingFramesValidated = $processedFramesValidated
        SyntheticGrainEnabled = $syntheticGrainDetected
        MissingEvidence = ($missingEvidence -join ", ")
        NotProven = ($unprovenReasons -join " | ")
        Failures = (@(
            $(if ($syntheticGrainDetected) { "synthetic grain was active in recorded delivery" } else { "" }),
            $row.Failures,
            ($failureReasons -join " | ")
        ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join " | "
        CertificatePath = $row.CertificatePath
        PremiumComparison = "NOT_PROVEN"
    }
}
if ($IncludeRows.Count -eq 0 -or @($IncludeRows | Where-Object { $_ -match "npv10" }).Count -gt 0) {
    $summary = @($summary) + @(
        [pscustomobject]@{
            Status = "NOT_PROVEN"
            Mode = "NPV10"
            RowId = "npv10_private_master_device_trigger_unavailable"
            Resolution = "DEVICE_SELECTED"
            Codec = "npv10"
            BitDepth = ""
            Transfer = ""
            Primaries = ""
            Matrix = ""
            GpuProcessingPath = "PRIVATE_MASTER_NOT_CAPTURED_BY_V16"
            SourceBitDepth = 0
            StorageBitDepth = 0
            RotationDegrees = ""
            Mirrored = ""
            ProcessedRecordingFramesValidated = $false
            SyntheticGrainEnabled = $false
            MissingEvidence = "automated NPV10 device trigger, private master retrieval, sourceBitDepth, storageBitDepth"
            NotProven = "V16 does not trigger the private NPV10 workflow or retrieve its private master evidence"
            Failures = ""
            CertificatePath = ""
            PremiumComparison = "NOT_PROVEN"
        }
    )
}

$jsonPath = Join-Path $OutputDir "v17-recorded-quality-repair-report.json"
$csvPath = Join-Path $OutputDir "v17-recorded-quality-repair-report.csv"
$mdPath = Join-Path $OutputDir "v17-recorded-quality-repair-report.md"
$summary | ConvertTo-Json -Depth 8 | Set-Content $jsonPath -Encoding UTF8
$summary | Export-Csv -NoTypeInformation $csvPath

$lines = New-Object System.Collections.Generic.List[string]
[void]$lines.Add("# NeoProCamX V17 Recorded Quality Repair Gate")
[void]$lines.Add("")
[void]$lines.Add("- GeneratedAt: $((Get-Date).ToString('o'))")
[void]$lines.Add("- Modes under audit: $($modesUnderAudit -join ', ')")
[void]$lines.Add("- Probe contract: $probeContract")
[void]$lines.Add("- PremiumComparison: NOT_PROVEN until controlled reference captures and objective measurements exist")
[void]$lines.Add("- V16 exit code: $v16Exit")
[void]$lines.Add("")
[void]$lines.Add("| Status | Mode | Resolution | GPU Path | Processed Frames | Synthetic Grain | Missing Evidence | Premium Comparison |")
[void]$lines.Add("| --- | --- | --- | --- | --- | --- | --- | --- |")
foreach ($item in $summary) {
    [void]$lines.Add(
        "| $($item.Status) | $($item.Mode) | $($item.Resolution) | $($item.GpuProcessingPath) | " +
        "$($item.ProcessedRecordingFramesValidated) | $($item.SyntheticGrainEnabled) | " +
        "$(($item.MissingEvidence -replace '\|', '/')) | $($item.PremiumComparison) |"
    )
}
Set-Content $mdPath -Value $lines -Encoding UTF8

$failCount = @($summary | Where-Object { $_.Status -eq "FAIL" }).Count
$notProvenCount = @($summary | Where-Object { $_.Status -eq "NOT_PROVEN" }).Count
$passCount = @($summary | Where-Object { $_.Status -eq "PASS" }).Count
Write-Host "[V17] Summary PASS=$passCount NOT_PROVEN=$notProvenCount FAIL=$failCount"
Write-Host "[V17] Report: $mdPath"
if ($failCount -gt 0 -or $v16Exit -eq 1) { exit 1 }
if ($notProvenCount -gt 0 -or $v16Exit -ne 0) { exit 2 }
exit 0
