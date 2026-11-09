param(
    [string]$AdbPath = "adb",
    [string]$Serial = "",
    [string]$PackageName = "com.neoproc.neoproccamx",
    [string]$OutputDir = "qa-artifacts\v16-real-device-encoder-validation",
    [int]$DurationMs = 2600,
    [int]$PostRecordWaitSec = 34,
    [string[]]$IncludeRows = @(),
    [int]$MaxRows = 0,
    [string]$VideoDetailProfile = "",
    [switch]$SkipInstall,
    [switch]$IsolateRows,
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Normalize-IncludeRows {
    param([string[]]$Rows)
    $normalized = New-Object System.Collections.Generic.List[string]
    foreach ($row in $Rows) {
        if ([string]::IsNullOrWhiteSpace($row)) { continue }
        foreach ($part in ($row -split ",")) {
            $trimmed = $part.Trim()
            if (-not [string]::IsNullOrWhiteSpace($trimmed)) {
                [void]$normalized.Add($trimmed)
            }
        }
    }
    return @($normalized.ToArray())
}

$IncludeRows = @(Normalize-IncludeRows -Rows $IncludeRows)

function Resolve-RepoRoot {
    $dir = [System.IO.DirectoryInfo]::new($PSScriptRoot)
    while ($dir -ne $null) {
        if (Test-Path (Join-Path $dir.FullName "settings.gradle.kts")) { return $dir.FullName }
        if (Test-Path (Join-Path $dir.FullName "settings.gradle")) { return $dir.FullName }
        $dir = $dir.Parent
    }
    throw "Unable to resolve repository root from $PSScriptRoot"
}

function Resolve-ToolPath {
    param([string]$Name)
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cmd -and $cmd.Source -and (Test-Path $cmd.Source)) { return $cmd.Source }
    if ($cmd -and $cmd.Path -and (Test-Path $cmd.Path)) { return $cmd.Path }
    if ($Name -ieq "mediainfo" -and -not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        $wingetPackagesRoot = Join-Path $env:LOCALAPPDATA "Microsoft\WinGet\Packages"
        if (Test-Path $wingetPackagesRoot) {
            $wingetMediaInfo = Get-ChildItem `
                -Path $wingetPackagesRoot `
                -Directory `
                -Filter "MediaArea.MediaInfo*" `
                -ErrorAction SilentlyContinue |
                ForEach-Object { Join-Path $_.FullName "MediaInfo.exe" } |
                Where-Object { Test-Path $_ } |
                Select-Object -First 1
            if (-not [string]::IsNullOrWhiteSpace($wingetMediaInfo)) { return $wingetMediaInfo }
        }
    }
    return ""
}

function Resolve-AdbPath {
    param([string]$RequestedPath)
    if (-not [string]::IsNullOrWhiteSpace($RequestedPath) -and $RequestedPath -notin @("adb", "adb.exe")) {
        if (Test-Path $RequestedPath) { return $RequestedPath }
        throw "Requested adb path does not exist: $RequestedPath"
    }
    $candidates = New-Object System.Collections.Generic.List[string]
    foreach ($name in @("adb.exe", "adb")) {
        $cmd = Get-Command $name -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($cmd -and $cmd.Source) { [void]$candidates.Add($cmd.Source) }
    }
    foreach ($sdkRoot in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA "Android\Sdk"))) {
        if ([string]::IsNullOrWhiteSpace($sdkRoot)) { continue }
        [void]$candidates.Add((Join-Path $sdkRoot "platform-tools\adb.exe"))
        [void]$candidates.Add((Join-Path $sdkRoot "platform-tools\adb"))
    }
    foreach ($candidate in $candidates | Select-Object -Unique) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path $candidate)) { return $candidate }
    }
    throw "Unable to locate adb. Pass -AdbPath explicitly or install Android platform-tools."
}

function Join-CommandArgs {
    param([string[]]$CommandArgs)
    return (($CommandArgs | ForEach-Object {
        if ($_ -match '[\s"]') {
            '"' + (($_ -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"'
        } else {
            $_
        }
    }) -join ' ')
}

function Invoke-ProcessChecked {
    param(
        [string]$FilePath,
        [string[]]$CommandArgs,
        [int]$TimeoutSec = 60,
        [switch]$IgnoreExitCode
    )
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $FilePath
    $psi.Arguments = Join-CommandArgs -CommandArgs $CommandArgs
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    if (-not [string]::IsNullOrWhiteSpace($script:Serial)) {
        $psi.Environment["ANDROID_SERIAL"] = $script:Serial
    }
    $proc = [System.Diagnostics.Process]::new()
    $proc.StartInfo = $psi
    [void]$proc.Start()
    $stdoutTask = $proc.StandardOutput.ReadToEndAsync()
    $stderrTask = $proc.StandardError.ReadToEndAsync()
    $timedOut = -not $proc.WaitForExit($TimeoutSec * 1000)
    if ($timedOut) {
        try { $proc.Kill($true) } catch {}
        $null = $proc.WaitForExit()
    }
    $stdout = $stdoutTask.GetAwaiter().GetResult()
    $stderr = $stderrTask.GetAwaiter().GetResult()
    $exitCode = if ($timedOut) { 124 } else { $proc.ExitCode }
    $result = [pscustomobject]@{
        ExitCode = $exitCode
        TimedOut = $timedOut
        StdOut = $stdout
        StdErr = $stderr
        Output = (@($stdout, $stderr) -join [Environment]::NewLine).Trim()
    }
    if (-not $IgnoreExitCode -and $exitCode -ne 0) {
        throw "Command failed: $FilePath $($CommandArgs -join ' ')`n$($result.Output)"
    }
    return $result
}

function Get-AdbArgs {
    param([string[]]$CommandArgs)
    if ([string]::IsNullOrWhiteSpace($script:Serial)) { return $CommandArgs }
    return @("-s", $script:Serial) + $CommandArgs
}

function Invoke-Adb {
    param([string[]]$CommandArgs, [int]$TimeoutSec = 60, [switch]$IgnoreExitCode)
    Invoke-ProcessChecked -FilePath $script:ResolvedAdbPath -CommandArgs (Get-AdbArgs -CommandArgs $CommandArgs) -TimeoutSec $TimeoutSec -IgnoreExitCode:$IgnoreExitCode
}

function Get-AppProcessId {
    param([string]$PackageName)
    $result = Invoke-Adb -CommandArgs @("shell", "pidof", "-s", $PackageName) -TimeoutSec 20 -IgnoreExitCode
    $appPid = $result.Output.Trim()
    if ($appPid -match "^\d+$") { return $appPid }
    return ""
}

function Resolve-CpuUsagePercent {
    param([string]$TopText, [string]$PackageName)
    foreach ($line in ($TopText -split "`r?`n")) {
        if ($line -notmatch [regex]::Escape($PackageName)) { continue }
        $percentMatch = [regex]::Match($line, "([0-9]+(?:\.[0-9]+)?)%")
        if ($percentMatch.Success) { return [double]$percentMatch.Groups[1].Value }
        $columns = @($line.Trim() -split "\s+" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        foreach ($column in $columns) {
            $value = 0.0
            if ([double]::TryParse(($column -replace "%", ""), [ref]$value) -and $value -ge 0.0 -and $value -le 1000.0) {
                return $value
            }
        }
    }
    return $null
}

function Resolve-MaxFrameTimeMs {
    param([string]$GfxInfoText)
    $maxMs = 0.0
    foreach ($line in ($GfxInfoText -split "`r?`n")) {
        $trimmed = $line.Trim()
        if ($trimmed -notmatch "^\d+,") { continue }
        $fields = @($trimmed -split ",")
        if ($fields.Count -lt 14) { continue }
        $flags = 0L
        $intendedVsync = 0L
        $frameCompleted = 0L
        if (-not [long]::TryParse($fields[0], [ref]$flags) -or $flags -ne 0L) { continue }
        if (-not [long]::TryParse($fields[1], [ref]$intendedVsync)) { continue }
        if (-not [long]::TryParse($fields[13], [ref]$frameCompleted)) { continue }
        if ($frameCompleted -le $intendedVsync) { continue }
        $frameMs = ($frameCompleted - $intendedVsync) / 1000000.0
        if ($frameMs -gt $maxMs) { $maxMs = $frameMs }
    }
    return $maxMs
}

function Get-PerformanceEvidence {
    param([string]$PackageName, [string]$OutputDir)
    $appPid = Get-AppProcessId -PackageName $PackageName
    $topText = ""
    if ([string]::IsNullOrWhiteSpace($appPid)) {
        $topText = (Invoke-Adb -CommandArgs @("shell", "top -b -n 1") -TimeoutSec 30 -IgnoreExitCode).Output
    } else {
        $topText = (Invoke-Adb -CommandArgs @("shell", "top -b -n 1 -p $appPid") -TimeoutSec 30 -IgnoreExitCode).Output
    }
    $gfxInfoText = (Invoke-Adb -CommandArgs @("shell", "dumpsys gfxinfo $PackageName framestats") -TimeoutSec 60 -IgnoreExitCode).Output
    $topPath = Join-Path $OutputDir "performance-top.txt"
    $gfxPath = Join-Path $OutputDir "performance-gfxinfo-framestats.txt"
    Set-Content -Path $topPath -Value $topText -Encoding UTF8
    Set-Content -Path $gfxPath -Value $gfxInfoText -Encoding UTF8
    $cpu = Resolve-CpuUsagePercent -TopText $topText -PackageName $PackageName
    $maxFrame = Resolve-MaxFrameTimeMs -GfxInfoText $gfxInfoText
    return [pscustomobject]@{
        PerformanceTelemetryValidated = ($cpu -ne $null -and $maxFrame -gt 0.0)
        CpuUsagePercent = $(if ($cpu -ne $null) { $cpu } else { "" })
        MaxFrameTimeMs = $maxFrame
        TopPath = $topPath
        GfxInfoPath = $gfxPath
    }
}

function Resolve-GpuProcessingPath {
    param([string]$LogText)
    $match = [regex]::Match($LogText, "gpuProcessingPath=([^,\s]+)")
    if ($match.Success) { return $match.Groups[1].Value }
    $match = [regex]::Match($LogText, "exportProcessingPath=([^,\s]+)")
    if ($match.Success) { return $match.Groups[1].Value }
    if ($LogText -match "VULKAN_P010_COMPUTE_STAGING|zeroCopyLiveImport=supported") { return "VULKAN_RUNTIME_EVIDENCE_LOG" }
    return "NOT_REPORTED"
}

function Resolve-ZeroCopyEvidencePath {
    param([string]$LogText, [string]$LogPath)
    if ($LogText -match "zeroCopyValidated=true|zeroCopyLiveImport=supported|zeroCopy=true") { return $LogPath }
    return ""
}

function ConvertFrom-KeyValueText {
    param([string]$Text)
    $fields = @{}
    foreach ($line in ($Text -split "`r?`n")) {
        $trimmed = $line.Trim()
        $separator = $trimmed.IndexOf("=")
        if ($separator -le 0) { continue }
        $fields[$trimmed.Substring(0, $separator)] = $trimmed.Substring($separator + 1)
    }
    return $fields
}

function Get-ObjectPropertyValue {
    param([object]$Object, [string]$Name)
    if ($null -eq $Object -or [string]::IsNullOrWhiteSpace($Name)) { return "" }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) { return "" }
    return [string]$property.Value
}

function Get-EvidenceValue {
    param(
        [object]$Row,
        [hashtable]$Certificate,
        [string]$RowProperty,
        [string]$CertificateKey
    )
    $rowValue = Get-ObjectPropertyValue -Object $Row -Name $RowProperty
    if (-not [string]::IsNullOrWhiteSpace($rowValue)) { return $rowValue }
    if ($Certificate.ContainsKey($CertificateKey)) { return [string]$Certificate[$CertificateKey] }
    return ""
}

function Get-DeviceSerials {
    $devices = Invoke-ProcessChecked -FilePath $script:ResolvedAdbPath -CommandArgs @("devices") -TimeoutSec 20
    return ($devices.StdOut -split "`r?`n") |
        Where-Object { $_ -match "^\S+\s+device(\s|$)" } |
        ForEach-Object { ($_ -split "\s+")[0] }
}

function Resolve-PowershellHost {
    $pwsh = Resolve-ToolPath -Name "pwsh"
    if (-not [string]::IsNullOrWhiteSpace($pwsh)) { return $pwsh }
    $powershell = Resolve-ToolPath -Name "powershell"
    if (-not [string]::IsNullOrWhiteSpace($powershell)) { return $powershell }
    throw "Unable to locate pwsh or powershell."
}

function Test-EncoderEvidence {
    param([object]$Row, [object]$PerformanceEvidence)
    $failures = New-Object System.Collections.Generic.List[string]
    $notProven = New-Object System.Collections.Generic.List[string]
    $warnings = New-Object System.Collections.Generic.List[string]
    $logText = ""
    if ($Row.PSObject.Properties.Name -contains "LogPath" -and (Test-Path $Row.LogPath)) {
        $logText = Get-Content -Path $Row.LogPath -Raw
    }
    if ([string]::IsNullOrWhiteSpace($logText)) {
        [void]$failures.Add("missing per-row logcat artifact")
    } else {
        if ($logText -notmatch "Encoder quality decision") {
            [void]$failures.Add("missing encoder quality decision log")
        }
        if ($logText -notmatch "capabilityCheck=") {
            [void]$failures.Add("missing encoder capability check log")
        }
        if ($logText -notmatch "thermalDowngrade=(true|false)") {
            [void]$failures.Add("missing thermal downgrade state log")
        }
        if ($logText -match "Dropping oldest queued muxer sample|dropped=\d*[1-9]\d*") {
            [void]$failures.Add("dropped-frame evidence found in logcat")
        }
        if ($logText -match "thermalDowngrade=true") {
            [void]$warnings.Add("thermal downgrade active during row")
        }
    }
    $certificateText = ""
    if ($Row.PSObject.Properties.Name -contains "CertificatePath" -and (Test-Path $Row.CertificatePath)) {
        $certificateText = Get-Content -Path $Row.CertificatePath -Raw
    }
    $certificate = ConvertFrom-KeyValueText -Text $certificateText
    $vendorHdrCertification = Get-EvidenceValue -Row $Row -Certificate $certificate -RowProperty "VendorHdrCertification" -CertificateKey "vendorHdrCertification"
    $officialHdr10PlusMetadataWritten = Get-EvidenceValue -Row $Row -Certificate $certificate -RowProperty "OfficialHdr10PlusMetadataWritten" -CertificateKey "officialHdr10PlusMetadataWritten"
    $vendorCertifiedHdr10Plus = Get-EvidenceValue -Row $Row -Certificate $certificate -RowProperty "VendorCertifiedHdr10Plus" -CertificateKey "vendorCertifiedHdr10Plus"
    $vendorCertifiedDolbyVision = Get-EvidenceValue -Row $Row -Certificate $certificate -RowProperty "VendorCertifiedDolbyVision" -CertificateKey "vendorCertifiedDolbyVision"
    $dolbyRpuWritten = Get-EvidenceValue -Row $Row -Certificate $certificate -RowProperty "DolbyRpuWritten" -CertificateKey "dolbyRpuWritten"
    $externalHdrStandardSubstitution = Get-EvidenceValue -Row $Row -Certificate $certificate -RowProperty "ExternalHdrStandardSubstitution" -CertificateKey "externalHdrStandardSubstitution"
    $proprietaryProfilePreserved = Get-EvidenceValue -Row $Row -Certificate $certificate -RowProperty "ProprietaryProfilePreserved" -CertificateKey "proprietaryProfilePreserved"
    $androidHdrTransportOnly = Get-EvidenceValue -Row $Row -Certificate $certificate -RowProperty "AndroidHdrTransportOnly" -CertificateKey "androidHdrTransportOnly"
    $neoHdrNoVendorDependency = Get-EvidenceValue -Row $Row -Certificate $certificate -RowProperty "NeoHdrNoVendorDependency" -CertificateKey "neoHdrNoVendorDependency"
    $nLogVendorDependency = Get-EvidenceValue -Row $Row -Certificate $certificate -RowProperty "NLogVendorDependency" -CertificateKey "nLogVendorDependency"
    $proprietaryRow = [string]$Row.Mode -in @("HDR", "LOG")
    $vendorHdrCertificationFlagsFalse = $true
    foreach ($flag in @(
        @{ Key = "vendorHdrCertification=false"; Value = $vendorHdrCertification },
        @{ Key = "officialHdr10PlusMetadataWritten=false"; Value = $officialHdr10PlusMetadataWritten },
        @{ Key = "vendorCertifiedHdr10Plus=false"; Value = $vendorCertifiedHdr10Plus },
        @{ Key = "vendorCertifiedDolbyVision=false"; Value = $vendorCertifiedDolbyVision },
        @{ Key = "dolbyRpuWritten=false"; Value = $dolbyRpuWritten },
        @{ Key = "externalHdrStandardSubstitution=false"; Value = $externalHdrStandardSubstitution }
    )) {
        if ([string]::IsNullOrWhiteSpace($flag.Value)) {
            if ($proprietaryRow) { [void]$notProven.Add("missing $($flag.Key) proof") }
            $vendorHdrCertificationFlagsFalse = $false
        } elseif ($flag.Value -ne "false") {
            [void]$failures.Add("$($flag.Key) required but observed $($flag.Value)")
            $vendorHdrCertificationFlagsFalse = $false
        }
    }
    if ($proprietaryRow) {
        if ([string]::IsNullOrWhiteSpace($proprietaryProfilePreserved)) {
            [void]$notProven.Add("missing proprietaryProfilePreserved=true proof")
        } elseif ($proprietaryProfilePreserved -ne "true") {
            [void]$failures.Add("proprietaryProfilePreserved=true required but observed $proprietaryProfilePreserved")
        }
        if ([string]$Row.Mode -eq "HDR") {
            if ([string]::IsNullOrWhiteSpace($androidHdrTransportOnly)) {
                [void]$notProven.Add("missing androidHdrTransportOnly=true proof")
            } elseif ($androidHdrTransportOnly -ne "true") {
                [void]$failures.Add("androidHdrTransportOnly=true required but observed $androidHdrTransportOnly")
            }
            if ([string]::IsNullOrWhiteSpace($neoHdrNoVendorDependency)) {
                [void]$notProven.Add("missing neoHdrNoVendorDependency=true proof")
            } elseif ($neoHdrNoVendorDependency -ne "true") {
                [void]$failures.Add("neoHdrNoVendorDependency=true required but observed $neoHdrNoVendorDependency")
            }
        }
        if ([string]$Row.Mode -eq "LOG") {
            if ([string]::IsNullOrWhiteSpace($nLogVendorDependency)) {
                [void]$notProven.Add("missing nLogVendorDependency=false proof")
            } elseif ($nLogVendorDependency -ne "false") {
                [void]$failures.Add("nLogVendorDependency=false required but observed $nLogVendorDependency")
            }
        }
    }
    $targetFrameTimeMs = if ([double]$Row.Fps -gt 0.0) { 1000.0 / [double]$Row.Fps } else { 0.0 }
    if ([string]$Row.Resolution -eq "3840x2160" -and [double]$Row.Fps -ge 60.0) {
        if (-not $PerformanceEvidence.PerformanceTelemetryValidated) {
            [void]$failures.Add("4K60 row missing CPU/frame-time performance telemetry")
        }
        if ($PerformanceEvidence.CpuUsagePercent -ne "" -and [double]$PerformanceEvidence.CpuUsagePercent -gt 10.0) {
            [void]$failures.Add("CPU usage $($PerformanceEvidence.CpuUsagePercent)% exceeds 10% target")
        }
        if ([double]$PerformanceEvidence.MaxFrameTimeMs -gt $targetFrameTimeMs) {
            [void]$failures.Add("max frame time $($PerformanceEvidence.MaxFrameTimeMs) ms exceeds $targetFrameTimeMs ms target")
        }
    }
    $gpuProcessingPath = Resolve-GpuProcessingPath -LogText $logText
    $byteBufferP010Proof = Get-ObjectPropertyValue -Object $Row -Name "ByteBufferP010Proof"
    $byteBufferP010FramesQueuedText = Get-ObjectPropertyValue -Object $Row -Name "ByteBufferP010FramesQueued"
    $byteBufferP010FrameDropsText = Get-ObjectPropertyValue -Object $Row -Name "ByteBufferP010FrameDrops"
    [int64]$byteBufferP010FramesQueued = -1
    [int64]$byteBufferP010FrameDrops = -1
    [int64]::TryParse($byteBufferP010FramesQueuedText, [ref]$byteBufferP010FramesQueued) | Out-Null
    [int64]::TryParse($byteBufferP010FrameDropsText, [ref]$byteBufferP010FrameDrops) | Out-Null
    if ($gpuProcessingPath -eq "APP_P010_BYTEBUFFER_ENCODER") {
        if ($byteBufferP010Proof -ne "true") {
            [void]$notProven.Add("APP_P010_BYTEBUFFER_ENCODER requires ByteBufferP010Proof=true")
        }
        if ($byteBufferP010FramesQueued -le 0) {
            [void]$notProven.Add("APP_P010_BYTEBUFFER_ENCODER requires ByteBufferP010FramesQueued > 0")
        }
        if ($byteBufferP010FrameDrops -ne 0) {
            [void]$notProven.Add("APP_P010_BYTEBUFFER_ENCODER requires ByteBufferP010FrameDrops = 0")
        }
        if ($logText -notmatch "P010 bytebuffer EOS") {
            [void]$notProven.Add("APP_P010_BYTEBUFFER_ENCODER missing P010 bytebuffer EOS evidence")
        }
        if ($logText -notmatch "P010 bytebuffer evidence ->") {
            [void]$notProven.Add("APP_P010_BYTEBUFFER_ENCODER missing final P010 bytebuffer evidence summary")
        }
    }
    $baseStatus = [string]$Row.Status
    $status = if ($failures.Count -gt 0) {
        "FAIL"
    } elseif ($baseStatus -eq "PASS" -and $notProven.Count -gt 0) {
        "NOT_PROVEN"
    } elseif ($baseStatus -eq "PASS") {
        "PASS"
    } else {
        $baseStatus
    }
    return [pscustomobject]@{
        RowId = $Row.RowId
        Status = $status
        BaseStatus = $baseStatus
        Mode = $Row.Mode
        Codec = $Row.Codec
        PixelFormat = Get-ObjectPropertyValue -Object $Row -Name "PixelFormat"
        Resolution = $Row.Resolution
        Fps = $Row.Fps
        BitDepth = $Row.BitDepth
        Transfer = $Row.Transfer
        Primaries = $Row.Primaries
        Matrix = $Row.Matrix
        RotationDegrees = $Row.RotationDegrees
        Mirrored = $Row.Mirrored
        BitrateBps = $Row.BitrateBps
        FrameCount = $Row.FrameCount
        RecordedFileBytes = $Row.RecordedFileBytes
        VendorHdrCertificationFlagsFalse = $vendorHdrCertificationFlagsFalse
        VendorHdrCertification = $vendorHdrCertification
        OfficialHdr10PlusMetadataWritten = $officialHdr10PlusMetadataWritten
        VendorCertifiedHdr10Plus = $vendorCertifiedHdr10Plus
        VendorCertifiedDolbyVision = $vendorCertifiedDolbyVision
        DolbyRpuWritten = $dolbyRpuWritten
        ExternalHdrStandardSubstitution = $externalHdrStandardSubstitution
        ProprietaryProfilePreserved = $proprietaryProfilePreserved
        AndroidHdrTransportOnly = $androidHdrTransportOnly
        NeoHdrNoVendorDependency = $neoHdrNoVendorDependency
        NLogVendorDependency = $nLogVendorDependency
        ByteBufferP010Proof = $byteBufferP010Proof
        ByteBufferP010FramesQueued = $byteBufferP010FramesQueuedText
        ByteBufferP010FrameDrops = $byteBufferP010FrameDropsText
        LogPath = $Row.LogPath
        FfprobePath = $Row.FfprobePath
        MediaInfoPath = $Row.MediaInfoPath
        CertificatePath = $Row.CertificatePath
        GpuProcessingPath = $gpuProcessingPath
        ZeroCopyEvidencePath = Resolve-ZeroCopyEvidencePath -LogText $logText -LogPath $Row.LogPath
        PerformanceTelemetryValidated = $PerformanceEvidence.PerformanceTelemetryValidated
        CpuUsagePercent = $PerformanceEvidence.CpuUsagePercent
        MaxFrameTimeMs = $PerformanceEvidence.MaxFrameTimeMs
        TargetFrameTimeMs = $targetFrameTimeMs
        PerformanceTopPath = $PerformanceEvidence.TopPath
        PerformanceGfxInfoPath = $PerformanceEvidence.GfxInfoPath
        Failures = ((@($Row.Failures) + @($failures)) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join " | "
        NotProven = ((@($Row.NotProven) + @($notProven)) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join " | "
        Warnings = ((@($Row.Warnings) + @($warnings)) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join " | "
        Strict10QualityProven = ($status -eq "PASS")
    }
}

$script:Serial = $Serial
$repoRoot = Resolve-RepoRoot
$script:ResolvedAdbPath = Resolve-AdbPath -RequestedPath $AdbPath
$ffprobe = Resolve-ToolPath -Name "ffprobe"
$mediainfo = Resolve-ToolPath -Name "mediainfo"
$powershellHost = Resolve-PowershellHost
$v15Script = Join-Path $repoRoot "tools\quality\run-v15-quality-evidence-gate.ps1"
$gradlew = Join-Path $repoRoot "gradlew.bat"

if (-not (Test-Path $v15Script)) { throw "Missing V15 gate script: $v15Script" }

if ($DryRun) {
    $dryRunArgs = @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", $v15Script,
        "-AdbPath", $script:ResolvedAdbPath,
        "-PackageName", $PackageName,
        "-OutputDir", (Join-Path $OutputDir "v15"),
        "-DurationMs", $DurationMs.ToString(),
        "-PostRecordWaitSec", $PostRecordWaitSec.ToString(),
        "-DryRun"
    )
    if (-not [string]::IsNullOrWhiteSpace($VideoDetailProfile)) {
        $dryRunArgs += @("-VideoDetailProfile", $VideoDetailProfile)
    }
    if ($MaxRows -gt 0) {
        $dryRunArgs += @("-MaxRows", $MaxRows.ToString())
    }
    if ($IncludeRows.Count -gt 0) {
        $dryRunArgs += @("-IncludeRows", ($IncludeRows -join ","))
    }
    $dryRunOutput = Invoke-ProcessChecked -FilePath $powershellHost -CommandArgs $dryRunArgs -TimeoutSec 120
    Write-Output $dryRunOutput.Output
    return
}

if ([string]::IsNullOrWhiteSpace($ffprobe)) { throw "ffprobe is required for V16 real-device encoder validation." }
if ([string]::IsNullOrWhiteSpace($mediainfo)) { throw "MediaInfo is required for V16 real-device encoder validation." }

if ([string]::IsNullOrWhiteSpace($script:Serial)) {
    $serials = @(Get-DeviceSerials)
    if ($serials.Count -ne 1) { throw "Expected exactly one adb device, found $($serials.Count). Pass -Serial." }
    $script:Serial = $serials[0]
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

if (-not $SkipInstall) {
    if (-not (Test-Path $gradlew)) { throw "Missing Gradle wrapper: $gradlew" }
    Invoke-ProcessChecked -FilePath $gradlew -CommandArgs @(
        ":app:assembleDebug",
        ":app:installDebug",
        "--warning-mode=fail",
        "--no-daemon",
        "--no-configuration-cache",
        "--console=plain"
    ) -TimeoutSec 900 | Out-File -FilePath (Join-Path $OutputDir "gradle-install.txt") -Encoding UTF8
}

Invoke-Adb -CommandArgs @("shell", "am", "start", "-W", "-n", "$PackageName/.ui.MainActivity") -TimeoutSec 60 -IgnoreExitCode |
    Out-File -FilePath (Join-Path $OutputDir "launch.txt") -Encoding UTF8

$v15Output = Join-Path $OutputDir "v15"
$combinedV15Rows = New-Object System.Collections.Generic.List[object]
$v15ExitCode = 0
if ($IsolateRows) {
    if ($IncludeRows.Count -eq 0) {
        throw "-IsolateRows requires -IncludeRows so V16 can cold-start one selected row per V15 invocation."
    }
    New-Item -ItemType Directory -Force -Path $v15Output | Out-Null
    foreach ($rowId in $IncludeRows) {
        Invoke-Adb -CommandArgs @("shell", "am", "force-stop", $PackageName) -TimeoutSec 30 -IgnoreExitCode | Out-Null
        Start-Sleep -Seconds 2
        $rowOutput = Join-Path $v15Output $rowId
        $rowV15Args = @(
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-File", $v15Script,
            "-AdbPath", $script:ResolvedAdbPath,
            "-Serial", $script:Serial,
            "-PackageName", $PackageName,
            "-OutputDir", $rowOutput,
            "-DurationMs", $DurationMs.ToString(),
            "-PostRecordWaitSec", $PostRecordWaitSec.ToString()
        )
        if (-not [string]::IsNullOrWhiteSpace($VideoDetailProfile)) {
            $rowV15Args += @("-VideoDetailProfile", $VideoDetailProfile)
        }
        $rowV15Args += @("-IncludeRows", $rowId)
        $rowRun = Invoke-ProcessChecked -FilePath $powershellHost -CommandArgs $rowV15Args -TimeoutSec 3600 -IgnoreExitCode
        Set-Content -Path (Join-Path $OutputDir "v15-run-$rowId.stdout.txt") -Value $rowRun.StdOut -Encoding UTF8
        Set-Content -Path (Join-Path $OutputDir "v15-run-$rowId.stderr.txt") -Value $rowRun.StdErr -Encoding UTF8
        if ($rowRun.ExitCode -eq 1) {
            $v15ExitCode = 1
        } elseif ($rowRun.ExitCode -ne 0 -and $v15ExitCode -eq 0) {
            $v15ExitCode = $rowRun.ExitCode
        }
        $rowJsonPath = Join-Path $rowOutput "v15-quality-report.json"
        if (-not (Test-Path $rowJsonPath)) {
            throw "V15 isolated row report not found: $rowJsonPath"
        }
        foreach ($row in @(Get-Content -Path $rowJsonPath -Raw | ConvertFrom-Json)) {
            [void]$combinedV15Rows.Add($row)
        }
    }
} else {
    $v15Args = @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", $v15Script,
        "-AdbPath", $script:ResolvedAdbPath,
        "-Serial", $script:Serial,
        "-PackageName", $PackageName,
        "-OutputDir", $v15Output,
        "-DurationMs", $DurationMs.ToString(),
        "-PostRecordWaitSec", $PostRecordWaitSec.ToString()
    )
    if (-not [string]::IsNullOrWhiteSpace($VideoDetailProfile)) {
        $v15Args += @("-VideoDetailProfile", $VideoDetailProfile)
    }
    if ($MaxRows -gt 0) {
        $v15Args += @("-MaxRows", $MaxRows.ToString())
    }
    if ($IncludeRows.Count -gt 0) {
        $v15Args += @("-IncludeRows", ($IncludeRows -join ","))
    }
    $v15Run = Invoke-ProcessChecked -FilePath $powershellHost -CommandArgs $v15Args -TimeoutSec 3600 -IgnoreExitCode
    Set-Content -Path (Join-Path $OutputDir "v15-run.stdout.txt") -Value $v15Run.StdOut -Encoding UTF8
    Set-Content -Path (Join-Path $OutputDir "v15-run.stderr.txt") -Value $v15Run.StdErr -Encoding UTF8
    $v15ExitCode = $v15Run.ExitCode
    $v15JsonPath = Join-Path $v15Output "v15-quality-report.json"
    if (-not (Test-Path $v15JsonPath)) {
        throw "V15 report not found: $v15JsonPath"
    }
    foreach ($row in @(Get-Content -Path $v15JsonPath -Raw | ConvertFrom-Json)) {
        [void]$combinedV15Rows.Add($row)
    }
}

Invoke-Adb -CommandArgs @("logcat", "-d", "-v", "time") -TimeoutSec 90 -IgnoreExitCode |
    ForEach-Object { $_.Output } |
    Set-Content -Path (Join-Path $OutputDir "full-session.logcat.txt") -Encoding UTF8
Invoke-Adb -CommandArgs @("shell", "dumpsys", "thermalservice") -TimeoutSec 30 -IgnoreExitCode |
    ForEach-Object { $_.Output } |
    Set-Content -Path (Join-Path $OutputDir "thermalservice.txt") -Encoding UTF8
$performanceEvidence = Get-PerformanceEvidence -PackageName $PackageName -OutputDir $OutputDir

$summary = New-Object System.Collections.Generic.List[object]
foreach ($row in $combinedV15Rows) {
    [void]$summary.Add((Test-EncoderEvidence -Row $row -PerformanceEvidence $performanceEvidence))
}

$jsonPath = Join-Path $OutputDir "v16-real-device-encoder-report.json"
$csvPath = Join-Path $OutputDir "v16-real-device-encoder-report.csv"
$mdPath = Join-Path $OutputDir "v16-real-device-encoder-report.md"
$summary | ConvertTo-Json -Depth 8 | Set-Content -Path $jsonPath -Encoding UTF8
$summary | Export-Csv -NoTypeInformation -Path $csvPath

$lines = New-Object System.Collections.Generic.List[string]
[void]$lines.Add("# NeoProCamX V16 Real-Device Encoder Validation")
[void]$lines.Add("")
[void]$lines.Add("- GeneratedAt: $((Get-Date).ToString('o'))")
[void]$lines.Add("- Device: $script:Serial")
[void]$lines.Add("- Package: $PackageName")
[void]$lines.Add("- ffprobe: $ffprobe")
[void]$lines.Add("- MediaInfo: $mediainfo")
[void]$lines.Add("- V15 exit code: $v15ExitCode")
[void]$lines.Add("- IsolatedRows: $IsolateRows")
[void]$lines.Add("")
[void]$lines.Add("| Status | Mode | Row | Codec | Pixel Format | Bit Depth | Transfer | Primaries | Matrix | Vendor HDR Cert False | Android HDR Transport Only | Rotation | Mirrored | Resolution | FPS | CPU % | Max Frame ms | GPU Path | Failures |")
[void]$lines.Add("| --- | --- | --- | --- | --- | ---: | --- | --- | --- | --- | --- | ---: | --- | --- | ---: | ---: | ---: | --- | --- |")
foreach ($item in $summary) {
    $failureText = ($item.Failures -replace "\|", "/")
[void]$lines.Add("| $($item.Status) | $($item.Mode) | $($item.RowId) | $($item.Codec) | $($item.PixelFormat) | $($item.BitDepth) | $($item.Transfer) | $($item.Primaries) | $($item.Matrix) | $($item.VendorHdrCertificationFlagsFalse) | $($item.AndroidHdrTransportOnly) | $($item.RotationDegrees) | $($item.Mirrored) | $($item.Resolution) | $($item.Fps) | $($item.CpuUsagePercent) | $($item.MaxFrameTimeMs) | $($item.GpuProcessingPath) | $failureText |")
}
Set-Content -Path $mdPath -Value $lines -Encoding UTF8

$failCount = @($summary | Where-Object { $_.Status -eq "FAIL" }).Count
$notProvenCount = @($summary | Where-Object { $_.Status -eq "NOT_PROVEN" }).Count
$passCount = @($summary | Where-Object { $_.Status -eq "PASS" }).Count
Write-Host "[V16] Summary PASS=$passCount NOT_PROVEN=$notProvenCount FAIL=$failCount"
Write-Host "[V16] Report: $mdPath"
if ($failCount -gt 0 -or $v15ExitCode -eq 1) { exit 1 }
if ($notProvenCount -gt 0 -or $v15ExitCode -ne 0) { exit 2 }
exit 0
