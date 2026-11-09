param(
    [string]$AdbPath = 'C:\Users\ELCOT\AppData\Local\Android\Sdk\platform-tools\adb.exe',
    [string]$Serial = '10.28.75.194:5555',
    [string]$Package = 'com.neoproc.neoproccamx',
    [string]$ApkPath = 'app\build\outputs\apk\debug\app-debug.apk',
    [string]$OutputDir = (
        'D:\NeoProCamX_QA\lq001-live-{0}\device\mode-switch-proof' -f
            (Get-Date -Format 'yyyyMMdd-HHmmss')
    ),
    [string]$ManualVisualReviewPath = '',
    [string]$GlobalAdbLockPath = 'D:\NeoProCamX_QA\.neo-qa-exclusive-adb.lock'
)

$ErrorActionPreference = 'Stop'
$utf8 = [Text.UTF8Encoding]::new($false)
$minimumPreviewFps = 24.8
$maximumPreviewFps = 25.2
$maximumFirstFreshFrameLatencyMs = 1000.0
$requiredTransitionCount = 30
$telemetryWaitTimeoutMs = 7500
$telemetryPollIntervalMs = 250
$maximumUiSelectionAttempts = 2
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

function Invoke-Adb {
    param([Parameter(Mandatory)][string[]]$Arguments, [switch]$AllowFailure)
    $oldPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $lines = @(& $AdbPath -s $Serial @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $oldPreference
    }
    if (-not $AllowFailure -and $exitCode -ne 0) {
        throw "ADB exit=$exitCode args=$($Arguments -join ' '): $($lines -join [Environment]::NewLine)"
    }
    return @($lines)
}

function Write-Utf8 {
    param([Parameter(Mandatory)][string]$Path, [AllowEmptyString()][string]$Text = '')
    $parent = Split-Path -Parent $Path
    if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    [IO.File]::WriteAllText($Path, $Text, $utf8)
}

function Save-Adb {
    param([Parameter(Mandatory)][string]$Name, [Parameter(Mandatory)][string[]]$Arguments, [switch]$AllowFailure)
    $content = @(Invoke-Adb -Arguments $Arguments -AllowFailure:$AllowFailure) -join "`r`n"
    Write-Utf8 -Path (Join-Path $OutputDir $Name) -Text $content
    return $content
}

function Get-ThermalSnapshot {
    param([Parameter(Mandatory)][string]$Name)
    $text = Save-Adb -Name $Name -Arguments @('shell', 'dumpsys', 'thermalservice')
    $statusMatch = [regex]::Match($text, 'Thermal Status:\s*(\d+)')
    $skinMatches = [regex]::Matches($text, 'Temperature\{mValue=([0-9.]+), mType=3, mName=skin')
    if (-not $statusMatch.Success -or $skinMatches.Count -eq 0) {
        throw 'Unable to read thermal status and skin temperature'
    }
    [pscustomobject]@{
        status = [int]$statusMatch.Groups[1].Value
        skinC = [double]$skinMatches[$skinMatches.Count - 1].Groups[1].Value
    }
}

function Dump-UiTree {
    param([Parameter(Mandatory)][string]$Stem)
    $remote = "/sdcard/$Stem.xml"
    Invoke-Adb -Arguments @('shell', 'uiautomator', 'dump', $remote) | Out-Null
    $local = Join-Path $OutputDir "$Stem.xml"
    Invoke-Adb -Arguments @('pull', $remote, $local) | Out-Null
    Invoke-Adb -Arguments @('shell', 'rm', '-f', $remote) -AllowFailure | Out-Null
    return $local
}

function Capture-Screen {
    param([Parameter(Mandatory)][string]$Stem)
    $remote = "/sdcard/$Stem.png"
    $local = Join-Path $OutputDir "$Stem.png"
    Invoke-Adb -Arguments @('shell', 'screencap', '-p', $remote) | Out-Null
    Invoke-Adb -Arguments @('pull', $remote, $local) | Out-Null
    Invoke-Adb -Arguments @('shell', 'rm', '-f', $remote) -AllowFailure | Out-Null
    if (-not (Test-Path -LiteralPath $local) -or (Get-Item -LiteralPath $local).Length -le 0L) {
        throw "Screenshot capture is empty or missing: $local"
    }
    return $local
}

function Read-SelectedMode {
    param([Parameter(Mandatory)][string]$XmlPath)
    [xml]$xml = Get-Content -LiteralPath $XmlPath -Raw
    $node = @($xml.SelectNodes('//node')) | Where-Object {
        $_.selected -eq 'true' -and $_.text -in @('Video', 'Pro', 'Raw')
    } | Select-Object -First 1
    if ($node) {
        return [string]$node.text
    }
    return ''
}

function Read-UiRootPackage {
    param([Parameter(Mandatory)][string]$XmlPath)
    [xml]$xml = Get-Content -LiteralPath $XmlPath -Raw
    $rootNode = $xml.SelectSingleNode('/hierarchy/node[1]')
    if ($null -eq $rootNode) { return '' }
    return [string]$rootNode.package
}

function Read-RailBounds {
    param([Parameter(Mandatory)][string]$XmlPath)
    [xml]$xml = Get-Content -LiteralPath $XmlPath -Raw
    $node = @($xml.SelectNodes('//node')) | Where-Object {
        $_.'resource-id' -eq "$Package`:id/modeRailScroll"
    } | Select-Object -First 1
    if (-not $node) { throw 'modeRailScroll is absent from the UI tree' }
    $match = [regex]::Match([string]$node.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    if (-not $match.Success) { throw "Malformed modeRailScroll bounds: $($node.bounds)" }
    [pscustomobject]@{
        x1 = [int]$match.Groups[1].Value
        y1 = [int]$match.Groups[2].Value
        x2 = [int]$match.Groups[3].Value
        y2 = [int]$match.Groups[4].Value
        text = [string]$node.bounds
    }
}

function Parse-LatestMetric {
    param([string]$Text, [string]$Pattern)
    $matches = [regex]::Matches($Text, $Pattern)
    if ($matches.Count -eq 0) { return $null }
    return $matches[$matches.Count - 1]
}

function Get-CurrentAppOwnedLogPath {
    # OriginOS can omit third-party INFO records from logd even for a debuggable package. The app's
    # NeoLogger writes the same timestamped records to app-scoped external storage, which adb can
    # read without changing package state. Resolve the file created by this process once so a later
    # archived/stale run can never be mistaken for evidence from the admitted candidate.
    $appLogRoot = "/sdcard/Android/data/$Package/files"
    $command = "ls -1t $appLogRoot/neoproc_*.log 2>/dev/null | head -n 1"
    return (@(Invoke-Adb -Arguments @('shell', $command) -AllowFailure) -join '').Trim()
}

function Read-AppOwnedLogTail {
    param(
        [Parameter(Mandatory)][AllowEmptyString()][string]$RemotePath,
        [int]$MaximumBytes = 262144
    )
    if ([string]::IsNullOrWhiteSpace($RemotePath)) { return '' }
    return @(Invoke-Adb -Arguments @(
        'shell', 'tail', '-c', "$MaximumBytes", $RemotePath
    ) -AllowFailure) -join "`r`n"
}

function Read-AppOwnedTelemetryTail {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$RemotePath)
    if ([string]::IsNullOrWhiteSpace($RemotePath)) { return '' }
    # Filter on-device before transfer. Repeatedly copying the full verbose camera log through adb
    # heats the target and can invalidate the very thermal/FPS measurement this runner protects.
    $command = "grep -E 'ModeSwitchTiming|MODE_SWITCH_PRESENTED|previewFps=[0-9.]+ recording=false' " +
        "$RemotePath 2>/dev/null | tail -n 128"
    return @(Invoke-Adb -Arguments @('shell', $command) -AllowFailure) -join "`r`n"
}

function Get-Sha256 {
    param([Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { throw "Required file is missing: $Path" }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToUpperInvariant()
}

function Test-FiniteDouble {
    param([AllowNull()][object]$Value)
    if ($null -eq $Value) { return $false }
    $number = [double]$Value
    return -not [double]::IsNaN($number) -and -not [double]::IsInfinity($number)
}

function Get-InstalledApkIdentity {
    $packagePaths = @(Invoke-Adb -Arguments @('shell', 'pm', 'path', $Package) | ForEach-Object {
        ([string]$_).Trim()
    } | Where-Object { $_ -match '^package:' })
    if ($packagePaths.Count -eq 0) { throw "Installed package is missing: $Package" }
    $baseLine = @($packagePaths | Where-Object { $_ -match '/base\.apk$' } | Select-Object -First 1)
    $selectedLine = if ($baseLine.Count -gt 0) { [string]$baseLine[0] } else { [string]$packagePaths[0] }
    $remotePath = $selectedLine.Substring('package:'.Length).Trim()
    $hashLine = (@(Invoke-Adb -Arguments @('shell', 'sha256sum', $remotePath)) -join '').Trim()
    $hash = [regex]::Match($hashLine, '^[0-9A-Fa-f]{64}').Value.ToUpperInvariant()
    if (-not $hash) { throw "Installed APK SHA-256 is unavailable for $remotePath" }
    return [pscustomobject]@{
        path = $remotePath
        sha256 = $hash
        packagePaths = $packagePaths
    }
}

function Get-InstalledBaseApkPath {
    $packagePaths = @(Invoke-Adb -Arguments @('shell', 'pm', 'path', $Package) -AllowFailure | ForEach-Object {
        ([string]$_).Trim()
    } | Where-Object { $_ -match '^package:' })
    if ($packagePaths.Count -eq 0) { return '' }
    $baseLine = @($packagePaths | Where-Object { $_ -match '/base\.apk$' } | Select-Object -First 1)
    $selectedLine = if ($baseLine.Count -gt 0) { [string]$baseLine[0] } else { [string]$packagePaths[0] }
    return $selectedLine.Substring('package:'.Length).Trim()
}

function Get-AppPid {
    $pidText = (@(Invoke-Adb -Arguments @('shell', 'pidof', '-s', $Package) -AllowFailure) -join '').Trim()
    if ($pidText -notmatch '^\d+$') { return '' }
    return $pidText
}

function Get-PackageLifecycleSignals {
    param(
        [AllowEmptyString()][string]$Text = '',
        [Parameter(Mandatory)][string]$PackageName
    )

    # ActivityThread reports package-observer notifications inside arbitrary application
    # processes. On OriginOS an observer can emit "reported as REPLACED" even while the tested
    # package, PID, code path, and foreground activity remain unchanged. Those observer lines are
    # retained as diagnostics, but only system lifecycle authorities can independently fail the
    # run. Direct PID/path checks and the final installed-APK hash remain the primary identity
    # proof.
    $authoritativeActivityTags = @(
        'ActivityManager',
        'ActivityManagerService',
        'ActivityTaskManager'
    )
    $authoritativePackageTags = @(
        'PackageManager',
        'PackageManagerService',
        'PackageInstaller',
        'PackageInstallerSession'
    )
    $forceStopLines = [Collections.Generic.List[string]]::new()
    $replacementLines = [Collections.Generic.List[string]]::new()
    $observerReplacementLines = [Collections.Generic.List[string]]::new()
    $escapedPackage = [regex]::Escape($PackageName)
    $exactPackagePattern = "(?<![A-Za-z0-9_.])$escapedPackage(?![A-Za-z0-9_.])"
    $epochLogLinePattern = '^\s*\d+(?:\.\d+)?\s+\d+\s+\d+\s+[VDIWEF]\s+' +
        '([A-Za-z0-9_.-]+)\s*:\s*(.*)$'

    foreach ($line in @($Text -split "\r?\n")) {
        $parsed = [regex]::Match($line, $epochLogLinePattern)
        if (-not $parsed.Success) { continue }
        $tag = $parsed.Groups[1].Value
        $message = $parsed.Groups[2].Value
        if (-not [regex]::IsMatch($message, $exactPackagePattern)) { continue }

        if ($tag -eq 'ActivityThread' -and
            [regex]::IsMatch($message, '(?i)\breported\s+as\s+REPLACED\b')) {
            $observerReplacementLines.Add($line)
            continue
        }
        if ($tag -in $authoritativeActivityTags -and
            [regex]::IsMatch(
                $message,
                "(?i)\bforce(?:-|\s+)stopping\s+$escapedPackage(?![A-Za-z0-9_.])"
            )) {
            $forceStopLines.Add($line)
        }
        if (($tag -in $authoritativeActivityTags -or $tag -in $authoritativePackageTags) -and
            [regex]::IsMatch(
                $message,
                '(?i)\b(?:PACKAGE_REPLACED|reported\s+as\s+REPLACED|' +
                    'replac(?:e|ed|ing|ement))\b'
            )) {
            $replacementLines.Add($line)
        }
    }

    return [pscustomobject]@{
        authoritative_force_stop = $forceStopLines.Count -gt 0
        authoritative_replacement = $replacementLines.Count -gt 0
        observer_replacement_only = (
            $observerReplacementLines.Count -gt 0 -and
                $forceStopLines.Count -eq 0 -and
                $replacementLines.Count -eq 0
        )
        authoritative_force_stop_lines = @($forceStopLines)
        authoritative_replacement_lines = @($replacementLines)
        observer_replacement_lines = @($observerReplacementLines)
    }
}

function Get-CandidateContinuityAssessment {
    param(
        [Parameter(Mandatory)][AllowEmptyString()][string]$TransitionLog,
        [Parameter(Mandatory)][string]$PackageName,
        [Parameter(Mandatory)][string]$ExpectedAppPid,
        [Parameter(Mandatory)][AllowEmptyString()][string]$CurrentAppPid,
        [Parameter(Mandatory)][string]$ExpectedInstalledPath,
        [Parameter(Mandatory)][AllowEmptyString()][string]$CurrentInstalledPath,
        [Parameter(Mandatory)][AllowEmptyString()][string]$CurrentForegroundPackage
    )
    $signals = Get-PackageLifecycleSignals -Text $TransitionLog -PackageName $PackageName
    $pidMatches = -not [string]::IsNullOrWhiteSpace($ExpectedAppPid) -and
        $CurrentAppPid -eq $ExpectedAppPid
    $pathMatches = -not [string]::IsNullOrWhiteSpace($ExpectedInstalledPath) -and
        $CurrentInstalledPath -eq $ExpectedInstalledPath
    $foregroundMatches = $CurrentForegroundPackage -eq $PackageName
    $candidateMutated = -not $pathMatches -or $signals.authoritative_replacement
    $forceStopped = -not $pidMatches -or $signals.authoritative_force_stop

    return [pscustomobject]@{
        continuous = (
            $pidMatches -and
                $pathMatches -and
                $foregroundMatches -and
                -not $signals.authoritative_force_stop -and
                -not $signals.authoritative_replacement
        )
        pid_matches = $pidMatches
        installed_path_matches = $pathMatches
        foreground_matches = $foregroundMatches
        candidate_mutated = $candidateMutated
        force_stopped = $forceStopped
        authoritative_force_stop_signal = $signals.authoritative_force_stop
        authoritative_replacement_signal = $signals.authoritative_replacement
        observer_replacement_only = $signals.observer_replacement_only
        observer_replacement_count = @($signals.observer_replacement_lines).Count
    }
}

function Get-ActiveCameraClientsBlock {
    param([Parameter(Mandatory)][string]$Text)

    $lines = @($Text -split "\r?\n")
    $headerIndex = -1
    for ($index = 0; $index -lt $lines.Count; $index += 1) {
        if ($lines[$index].Trim() -eq 'Active Camera Clients:') {
            $headerIndex = $index
            break
        }
    }
    if ($headerIndex -lt 0) {
        throw 'CameraService output is missing the Active Camera Clients section'
    }

    $clients = [Collections.Generic.List[string]]::new()
    $opened = $false
    for ($index = $headerIndex + 1; $index -lt $lines.Count; $index += 1) {
        $trimmed = $lines[$index].Trim()
        if (-not $opened) {
            if ([string]::IsNullOrWhiteSpace($trimmed)) { continue }
            if ($trimmed -eq '[]') { return '' }
            if ($trimmed -eq '[') {
                $opened = $true
                continue
            }
            throw "Malformed Active Camera Clients section opening: $trimmed"
        }
        if ($trimmed -eq ']') {
            return $clients -join "`r`n"
        }
        $clients.Add($lines[$index])
    }
    throw 'CameraService output has an unterminated Active Camera Clients section'
}

function Test-ActiveCameraClient {
    param(
        [Parameter(Mandatory)][string]$CameraServiceText,
        [Parameter(Mandatory)][string]$ClientPackage
    )
    $activeClients = Get-ActiveCameraClientsBlock -Text $CameraServiceText
    return [regex]::IsMatch($activeClients, [regex]::Escape($ClientPackage))
}

function Parse-PresentationMarker {
    param(
        [Parameter(Mandatory)][string]$Text,
        [Parameter(Mandatory)][string]$ExpectedMode,
        [string]$ExpectedFrom = '',
        [long]$MinimumSequence = -1L
    )
    $pattern = 'MODE_SWITCH_PRESENTED\s+from=([A-Z_]+)\s+mode=([A-Z_]+)\s+' +
        'latencyMs=([0-9.]+)\s+sequence=(\d+)\s+sessionGeneration=(-?\d+)\s+' +
        'surfaceEpoch=(\d+)\s+sensorTimestampNs=(\d+)\s+presentationTimestampNs=(\d+)'
    $matches = [regex]::Matches($Text, $pattern)
    if ($matches.Count -eq 0) { return $null }
    $matching = @($matches | Where-Object {
        $_.Groups[2].Value -eq $ExpectedMode -and
            ([string]::IsNullOrWhiteSpace($ExpectedFrom) -or $_.Groups[1].Value -eq $ExpectedFrom) -and
            [long]$_.Groups[4].Value -gt $MinimumSequence
    })
    if ($matching.Count -eq 0) { return $null }
    return $matching[$matching.Count - 1]
}

function Parse-TransitionTelemetrySource {
    param(
        [Parameter(Mandatory)][AllowEmptyString()][string]$Text,
        [Parameter(Mandatory)][string]$ExpectedFrom,
        [Parameter(Mandatory)][string]$ExpectedMode,
        [Parameter(Mandatory)][string]$Source,
        [long]$MinimumSequence = -1L
    )

    if ([string]::IsNullOrWhiteSpace($Text)) {
        return [pscustomobject]@{
            source = $Source
            complete = $false
            timing = $null
            presentation = $null
            fps = $null
        }
    }

    $presentation = Parse-PresentationMarker `
        -Text $Text `
        -ExpectedMode $ExpectedMode `
        -ExpectedFrom $ExpectedFrom `
        -MinimumSequence $MinimumSequence
    if (-not $presentation) {
        return [pscustomobject]@{
            source = $Source
            complete = $false
            timing = $null
            presentation = $null
            fps = $null
        }
    }

    # A proof chain must be ordered inside one physical log stream: route timing, then the
    # current-generation presentation, then its FPS sample. Concatenating logcat before the durable
    # app log could place a stale durable FPS row after a fresh logcat presentation marker.
    $beforePresentation = $Text.Substring(0, $presentation.Index)
    $timingMatches = [regex]::Matches(
        $beforePresentation,
        'ModeSwitchTiming\s+([^\s]+)\s+total=([0-9.]+)ms'
    )
    $expectedRoute = "$ExpectedFrom->$ExpectedMode"
    $matchingTimings = @($timingMatches | Where-Object {
        $_.Groups[1].Value -eq $expectedRoute
    })
    $timing = if ($matchingTimings.Count -gt 0) {
        $matchingTimings[$matchingTimings.Count - 1]
    } else {
        $null
    }
    $afterPresentation = $Text.Substring($presentation.Index + $presentation.Length)
    $fps = Parse-LatestMetric `
        -Text $afterPresentation `
        -Pattern 'previewFps=([0-9.]+)\s+recording=false'

    return [pscustomobject]@{
        source = $Source
        complete = [bool]($timing -and $presentation -and $fps)
        timing = $timing
        presentation = $presentation
        fps = $fps
    }
}

function Wait-ForTransitionTelemetry {
    param(
        [Parameter(Mandatory)][string]$ExpectedFrom,
        [Parameter(Mandatory)][string]$ExpectedMode,
        [long]$MinimumSequence,
        [int]$TimeoutMs = $telemetryWaitTimeoutMs,
        [int]$PollIntervalMs = $telemetryPollIntervalMs
    )
    if ($TimeoutMs -lt 0) { throw 'Telemetry timeout must be non-negative' }
    if ($PollIntervalMs -lt 1) { throw 'Telemetry poll interval must be positive' }

    $watch = [Diagnostics.Stopwatch]::StartNew()
    $latestText = ''
    $latestLogcatText = ''
    $latestAppLogText = ''
    $timing = $null
    $presentation = $null
    $fps = $null
    $pollIteration = 0
    $source = ''
    do {
        # logd is sampled once per second; the compact app-owned stream is sampled every poll.
        # This preserves immediate logcat evidence where OEMs expose it without continuously
        # transferring a noisy system buffer on devices that suppress third-party INFO records.
        if (($pollIteration % 4) -eq 0) {
            $latestLogcatText = @(Invoke-Adb -Arguments @('logcat', '-d', '-v', 'epoch')) -join "`r`n"
        }
        $latestAppLogText = Read-AppOwnedTelemetryTail -RemotePath $script:currentAppOwnedLogPath
        $latestText = $latestLogcatText +
            "`r`n--- NEO-QA APP-OWNED DURABLE LOG ---`r`n" +
            $latestAppLogText
        $logcatTelemetry = Parse-TransitionTelemetrySource `
            -Text $latestLogcatText `
            -ExpectedFrom $ExpectedFrom `
            -ExpectedMode $ExpectedMode `
            -Source 'logcat' `
            -MinimumSequence $MinimumSequence
        $appLogTelemetry = Parse-TransitionTelemetrySource `
            -Text $latestAppLogText `
            -ExpectedFrom $ExpectedFrom `
            -ExpectedMode $ExpectedMode `
            -Source 'app_owned_durable_log' `
            -MinimumSequence $MinimumSequence
        $selectedTelemetry = if ($appLogTelemetry.complete -and
            (-not $logcatTelemetry.complete -or
                [long]$appLogTelemetry.presentation.Groups[4].Value -ge
                    [long]$logcatTelemetry.presentation.Groups[4].Value)
        ) {
            $appLogTelemetry
        } elseif ($logcatTelemetry.complete) {
            $logcatTelemetry
        } else {
            $null
        }
        if ($selectedTelemetry) {
            $timing = $selectedTelemetry.timing
            $presentation = $selectedTelemetry.presentation
            $fps = $selectedTelemetry.fps
            $source = $selectedTelemetry.source
        }
        if ($timing -and $presentation -and $fps) { break }
        if ($watch.ElapsedMilliseconds -ge $TimeoutMs) { break }
        $pollIteration += 1
        Start-Sleep -Milliseconds $PollIntervalMs
    } while ($true)

    return [pscustomobject]@{
        text = $latestText
        logcat_text = $latestLogcatText
        app_log_text = $latestAppLogText
        evidence_sources = if ([string]::IsNullOrWhiteSpace($latestAppLogText)) {
            @('logcat')
        } else {
            @('logcat', 'app_owned_durable_log')
        }
        elapsed_ms = [math]::Round($watch.Elapsed.TotalMilliseconds, 3)
        complete = [bool]($timing -and $presentation -and $fps)
        source = $source
        timing = $timing
        presentation = $presentation
        fps = $fps
    }
}

function Get-ExpectedModeEnum {
    param([Parameter(Mandatory)][string]$UiMode)
    switch ($UiMode) {
        'Video' { return 'VIDEO' }
        'Pro' { return 'PRO' }
        'Raw' { return 'RAW_MODE' }
        default { throw "Unsupported UI mode label: $UiMode" }
    }
}

function Test-ManualVisualReview {
    param(
        [Parameter(Mandatory)][Collections.Generic.List[object]]$TransitionRows,
        [Parameter(Mandatory)][string]$CandidateHash,
        [Parameter(Mandatory)][AllowEmptyString()][string]$ReviewPath
    )
    if ([string]::IsNullOrWhiteSpace($ReviewPath)) {
        return [pscustomobject]@{
            accepted = $false
            reason = 'Manual visual review was not supplied'
        }
    }
    if (-not (Test-Path -LiteralPath $ReviewPath)) {
        return [pscustomobject]@{
            accepted = $false
            reason = "Manual visual review file is missing: $ReviewPath"
        }
    }
    try {
        $review = Get-Content -LiteralPath $ReviewPath -Raw | ConvertFrom-Json
    } catch {
        return [pscustomobject]@{
            accepted = $false
            reason = "Manual visual review JSON is invalid: $($_.Exception.Message)"
        }
    }
    if ([string]$review.checkpoint -ne 'LQ-001.03' -or
        ([string]$review.candidate_sha256).ToUpperInvariant() -ne $CandidateHash) {
        return [pscustomobject]@{
            accepted = $false
            reason = 'Manual review checkpoint or candidate hash does not match this run'
        }
    }
    if ([string]::IsNullOrWhiteSpace([string]$review.reviewer) -or
        [string]::IsNullOrWhiteSpace([string]$review.reviewed_utc)) {
        return [pscustomobject]@{
            accepted = $false
            reason = 'Manual review must identify the reviewer and reviewed_utc time'
        }
    }
    $reviews = @($review.transitions)
    if ($reviews.Count -ne $requiredTransitionCount) {
        return [pscustomobject]@{
            accepted = $false
            reason = "Manual review contains $($reviews.Count) transitions; expected $requiredTransitionCount"
        }
    }
    foreach ($row in $TransitionRows) {
        $entry = @($reviews | Where-Object { [int]$_.transition -eq [int]$row.transition })
        if ($entry.Count -ne 1) {
            return [pscustomobject]@{
                accepted = $false
                reason = "Manual review must contain exactly one row for transition $($row.transition)"
            }
        }
        $entry = $entry[0]
        if (([string]$entry.screenshot_sha256).ToUpperInvariant() -ne [string]$row.screenshot_sha256) {
            return [pscustomobject]@{
                accepted = $false
                reason = "Manual review screenshot hash mismatch at transition $($row.transition)"
            }
        }
        $requiredFlags = @(
            'new_selected_mode_frame_visible',
            'no_black_or_frozen_frame',
            'no_wrong_transform',
            'no_stretch_or_squeeze',
            'no_dock_displacement'
        )
        foreach ($flag in $requiredFlags) {
            if ($entry.PSObject.Properties.Name -notcontains $flag -or $entry.$flag -ne $true) {
                return [pscustomobject]@{
                    accepted = $false
                    reason = "Manual visual criterion '$flag' is not true at transition $($row.transition)"
                }
            }
        }
    }
    return [pscustomobject]@{ accepted = $true; reason = '' }
}

function Write-ManualVisualReviewTemplate {
    param(
        [Parameter(Mandatory)][Collections.Generic.List[object]]$TransitionRows,
        [Parameter(Mandatory)][string]$CandidateHash
    )
    $template = [ordered]@{
        checkpoint = 'LQ-001.03'
        candidate_sha256 = $CandidateHash
        reviewer = ''
        reviewed_utc = ''
        transitions = @($TransitionRows | ForEach-Object {
            [ordered]@{
                transition = $_.transition
                expected_mode = $_.expected
                screenshot = $_.screenshot_path
                screenshot_sha256 = $_.screenshot_sha256
                new_selected_mode_frame_visible = $null
                no_black_or_frozen_frame = $null
                no_wrong_transform = $null
                no_stretch_or_squeeze = $null
                no_dock_displacement = $null
            }
        })
    }
    Write-Utf8 -Path (Join-Path $OutputDir '31-manual-visual-review-template.json') `
        -Text ($template | ConvertTo-Json -Depth 6)
}

$logProcess = $null
$adbLease = $null
$adbUsable = $false
$rows = [Collections.Generic.List[object]]::new()
$machineFailures = [Collections.Generic.List[string]]::new()
$failure = ''
$originalBrightnessMode = $null
$originalBrightness = $null
$candidateHash = ''
$installedIdentity = $null
$finalInstalledIdentity = $null
$finalInstalledIdentityError = ''
$finalAppPid = ''
$finalIdentityVerified = $false
$initialInstalledPath = ''
$initialAppPid = ''
$previousSequence = 0L
$previousSensorTimestampNs = 0L
$previousPresentationTimestampNs = 0L
$previousSessionGeneration = $null
$script:currentAppOwnedLogPath = ''
$visualReview = [pscustomobject]@{
    accepted = $false
    reason = 'Device execution did not reach visual review'
}
try {
    if (-not (Test-Path -LiteralPath $AdbPath)) { throw "ADB executable is missing: $AdbPath" }
    $resolvedApkPath = (Resolve-Path -LiteralPath $ApkPath).Path
    $candidateHash = Get-Sha256 -Path $resolvedApkPath

    $lockParent = Split-Path -Parent $GlobalAdbLockPath
    if ($lockParent) { New-Item -ItemType Directory -Force -Path $lockParent | Out-Null }
    try {
        $adbLease = [IO.File]::Open(
            $GlobalAdbLockPath,
            [IO.FileMode]::OpenOrCreate,
            [IO.FileAccess]::ReadWrite,
            [IO.FileShare]::None
        )
    } catch {
        throw "Exclusive ADB lease is already held: $GlobalAdbLockPath"
    }
    $leasePayload = [Text.Encoding]::UTF8.GetBytes(
        "checkpoint=LQ-001.03`r`nserial=$Serial`r`npid=$PID`r`nstarted_utc=$([DateTime]::UtcNow.ToString('o'))"
    )
    $adbLease.SetLength(0L)
    $adbLease.Write($leasePayload, 0, $leasePayload.Length)
    $adbLease.Flush($true)

    $devices = @(Invoke-Adb -Arguments @('devices') | Where-Object { $_ -match '\tdevice$' })
    $selectedDevicePattern = '^' + [regex]::Escape($Serial) + '\tdevice$'
    $selectedDeviceRows = @($devices | Where-Object { $_ -match $selectedDevicePattern })
    $selectedDeviceState = (@(Invoke-Adb -Arguments @('get-state')) -join '').Trim()
    if ($selectedDeviceRows.Count -ne 1 -or $selectedDeviceState -ne 'device') {
        throw (
            "Selected ADB transport is not uniquely online serial=$Serial " +
                "state=$selectedDeviceState transports=$($devices -join '; ')"
        )
    }
    # The exclusive file lease protects the physical-device run. A handset can legitimately expose
    # USB and wireless transports at the same time; every command remains serial-qualified so an
    # additional transport cannot receive input or contaminate this evidence batch.
    Write-Utf8 -Path (Join-Path $OutputDir '00-adb-transports.txt') -Text (
        "selected=$Serial`r`nstate=$selectedDeviceState`r`nonline=$($devices -join '; ')"
    )
    $adbUsable = $true
    $installedIdentity = Get-InstalledApkIdentity
    if ($installedIdentity.sha256 -ne $candidateHash) {
        throw "Installed APK hash $($installedIdentity.sha256) does not match candidate $candidateHash"
    }
    $initialInstalledPath = $installedIdentity.path
    $identityProperties = @(
        'ro.product.manufacturer',
        'ro.product.model',
        'ro.build.fingerprint',
        'ro.build.version.release',
        'ro.build.version.sdk'
    )
    $deviceIdentityLines = foreach ($propertyName in $identityProperties) {
        $propertyValue = (@(Invoke-Adb -Arguments @('shell', 'getprop', $propertyName)) -join '').Trim()
        "$propertyName=$propertyValue"
    }
    Write-Utf8 -Path (Join-Path $OutputDir '00-device-identity.txt') `
        -Text ($deviceIdentityLines -join "`r`n")
    Save-Adb -Name '01-package-identity.txt' -Arguments @('shell', 'dumpsys', 'package', $Package) | Out-Null
    Write-Utf8 -Path (Join-Path $OutputDir '02-candidate-identity.json') -Text (
        [ordered]@{
            checkpoint = 'LQ-001.03'
            candidate_path = $resolvedApkPath
            candidate_sha256 = $candidateHash
            installed_path = $installedIdentity.path
            installed_sha256 = $installedIdentity.sha256
            serial = $Serial
        } | ConvertTo-Json -Depth 4
    )

    $originalBrightnessMode = (@(Invoke-Adb -Arguments @('shell', 'settings', 'get', 'system', 'screen_brightness_mode')) -join '').Trim()
    $originalBrightness = (@(Invoke-Adb -Arguments @('shell', 'settings', 'get', 'system', 'screen_brightness')) -join '').Trim()
    Invoke-Adb -Arguments @('shell', 'settings', 'put', 'system', 'screen_brightness_mode', '0') -AllowFailure | Out-Null
    Invoke-Adb -Arguments @('shell', 'settings', 'put', 'system', 'screen_brightness', '20') -AllowFailure | Out-Null
    Invoke-Adb -Arguments @('shell', 'svc', 'power', 'stayon', 'true') | Out-Null
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') | Out-Null
    Invoke-Adb -Arguments @('shell', 'wm', 'dismiss-keyguard') -AllowFailure | Out-Null
    Invoke-Adb -Arguments @('shell', 'am', 'force-stop', $Package) -AllowFailure | Out-Null
    Invoke-Adb -Arguments @('logcat', '-b', 'all', '-c') | Out-Null
    Invoke-Adb -Arguments @('shell', 'am', 'start', '-W', '-n', "$Package/.ui.MainActivity") | Out-Null
    Start-Sleep -Seconds 3

    $presetBroadcast = @(Invoke-Adb -Arguments @(
        'shell', 'am', 'broadcast', '-a', "$Package.DEBUG_COMMAND", '-n',
        "$Package/.ui.DebugCommandReceiver", '--es', 'debug_preset', 'VIDEO',
        '--es', 'debug_video_resolution', '3840x2160', '--el', 'debug_video_fps', '25'
    )) -join "`r`n"
    Write-Utf8 -Path (Join-Path $OutputDir '03-initial-preset-broadcast.txt') -Text $presetBroadcast
    if ($presetBroadcast -notmatch 'result=0') {
        throw "Exact VIDEO/4K/25 preset broadcast was not accepted: $presetBroadcast"
    }
    Start-Sleep -Seconds 4

    $script:currentAppOwnedLogPath = Get-CurrentAppOwnedLogPath
    if ([string]::IsNullOrWhiteSpace($script:currentAppOwnedLogPath)) {
        throw 'Current app-owned NeoLogger file is unavailable after application launch'
    }
    Write-Utf8 -Path (Join-Path $OutputDir '04-app-owned-log-source.txt') `
        -Text $script:currentAppOwnedLogPath

    $initialXml = Dump-UiTree -Stem '20-cycle-initial'
    $initialScreenshot = Capture-Screen -Stem '20-cycle-initial'
    if ((Read-SelectedMode -XmlPath $initialXml) -ne 'Video') {
        throw 'Initial mode is not Video after the exact VIDEO/4K/25 preset was requested'
    }
    $initialCameraState = Save-Adb -Name '20-cycle-initial-camera-service.txt' `
        -Arguments @('shell', 'dumpsys', 'media.camera')
    if (-not (Test-ActiveCameraClient -CameraServiceText $initialCameraState -ClientPackage $Package)) {
        throw 'CameraService does not show the app as an active camera client before mode cycling'
    }
    $initialAppPid = Get-AppPid
    if ([string]::IsNullOrWhiteSpace($initialAppPid)) {
        throw 'Application process is absent before mode cycling'
    }
    $bounds = Read-RailBounds -XmlPath $initialXml
    $y = [int](($bounds.y1 + $bounds.y2) / 2)
    $startX = [int]($bounds.x1 + 0.84 * ($bounds.x2 - $bounds.x1))
    $endX = [int]($bounds.x1 + 0.16 * ($bounds.x2 - $bounds.x1))
    Write-Utf8 -Path (Join-Path $OutputDir '21-ui-derived-control.txt') -Text (
        "mode_bounds=$($bounds.text)`r`nswipe=$startX,$y->$endX,$y`r`nsource=UI_TREE"
    )

    $thermal = Get-ThermalSnapshot -Name '22-thermal-before-cycles.txt'
    if ($thermal.status -gt 1 -or $thermal.skinC -gt 40.0) {
        throw "Performance thermal admission failed status=$($thermal.status) skinC=$($thermal.skinC)"
    }

    Invoke-Adb -Arguments @('logcat', '-c') | Out-Null
    Invoke-Adb -Arguments @('shell', 'dumpsys', 'gfxinfo', $Package, 'reset') -AllowFailure | Out-Null
    $liveLog = Join-Path $OutputDir '23-continuous-logcat.txt'
    $liveErr = Join-Path $OutputDir '23-continuous-logcat.stderr.txt'
    $logProcess = Start-Process -FilePath $AdbPath `
        -ArgumentList @('-s', $Serial, 'logcat', '-v', 'epoch') `
        -WindowStyle Hidden -RedirectStandardOutput $liveLog -RedirectStandardError $liveErr -PassThru

    $expectedModes = foreach ($cycle in 1..10) {
        [pscustomobject]@{ cycle = $cycle; from = 'Video'; expected = 'Pro' }
        [pscustomobject]@{ cycle = $cycle; from = 'Pro'; expected = 'Raw' }
        [pscustomobject]@{ cycle = $cycle; from = 'Raw'; expected = 'Video' }
    }

    $index = 0
    foreach ($step in $expectedModes) {
        $index += 1
        $thermal = Get-ThermalSnapshot -Name ("thermal-{0:D2}.txt" -f $index)
        # The performance batch is admitted at <=40 C before it begins. During the batch the
        # canonical LQ gate permits Android thermal status 0-1; status 2+ invalidates the run.
        if ($thermal.status -gt 1) {
            throw "Thermal stop before transition $index status=$($thermal.status) skinC=$($thermal.skinC)"
        }

        Invoke-Adb -Arguments @('logcat', '-b', 'all', '-c') | Out-Null
        $watch = [Diagnostics.Stopwatch]::StartNew()
        $expectedModeEnum = Get-ExpectedModeEnum -UiMode $step.expected
        $expectedFromEnum = Get-ExpectedModeEnum -UiMode $step.from
        Invoke-Adb -Arguments @('shell', 'input', 'swipe', "$startX", "$y", "$endX", "$y", '220') | Out-Null
        # Poll before the slower UI/screenshot/CameraService evidence calls. This preserves early
        # app telemetry on OEMs with noisy or aggressively rate-limited log buffers, while the
        # marker's own monotonic timestamps remain the acceptance clock (never host wait time).
        $telemetry = Wait-ForTransitionTelemetry `
            -ExpectedFrom $expectedFromEnum `
            -ExpectedMode $expectedModeEnum `
            -MinimumSequence $previousSequence
        $stem = "transition-{0:D2}-c{1}-{2}" -f $index, $step.cycle, $step.expected
        Write-Utf8 -Path (Join-Path $OutputDir "$stem-telemetry-logcat.txt") -Text $telemetry.logcat_text
        Write-Utf8 -Path (Join-Path $OutputDir "$stem-telemetry-app-log.txt") -Text $telemetry.app_log_text
        Write-Utf8 -Path (Join-Path $OutputDir "$stem-telemetry-combined.txt") -Text $telemetry.text

        $uiSelectionAttempts = 0
        $actual = ''
        $foregroundPackage = ''
        do {
            $uiSelectionAttempts += 1
            $uiStem = if ($uiSelectionAttempts -eq 1) {
                $stem
            } else {
                "$stem-ui-retry-{0:D2}" -f $uiSelectionAttempts
            }
            $xmlPath = Dump-UiTree -Stem $uiStem
            $actual = Read-SelectedMode -XmlPath $xmlPath
            $foregroundPackage = Read-UiRootPackage -XmlPath $xmlPath
            if ($actual -or $foregroundPackage -ne $Package) { break }
            if ($uiSelectionAttempts -lt $maximumUiSelectionAttempts) {
                Start-Sleep -Milliseconds 250
            }
        } while ($uiSelectionAttempts -lt $maximumUiSelectionAttempts)
        $evidenceCollectionElapsedMs = [math]::Round($watch.Elapsed.TotalMilliseconds, 3)
        $screenshotPath = Capture-Screen -Stem $stem
        $screenshotHash = Get-Sha256 -Path $screenshotPath
        $uiTreeHash = Get-Sha256 -Path $xmlPath
        $cameraState = Save-Adb -Name "$stem-camera-service.txt" -Arguments @('shell', 'dumpsys', 'media.camera')
        $lateTransitionLog = @(Invoke-Adb -Arguments @('logcat', '-d', '-v', 'epoch')) -join "`r`n"
        $lateAppLog = Read-AppOwnedLogTail -RemotePath $script:currentAppOwnedLogPath
        $transitionLog = $telemetry.text +
            "`r`n--- NEO-QA LATE LOGCAT SNAPSHOT ---`r`n" + $lateTransitionLog +
            "`r`n--- NEO-QA LATE APP-OWNED LOG SNAPSHOT ---`r`n" + $lateAppLog
        Write-Utf8 -Path (Join-Path $OutputDir "$stem-logcat.txt") -Text $transitionLog
        $thermalAfter = Get-ThermalSnapshot -Name "$stem-thermal-after.txt"
        $currentAppPid = Get-AppPid
        $currentInstalledPath = Get-InstalledBaseApkPath

        # Reuse the accepted single-source proof chain. Re-parsing the combined late snapshots would
        # reintroduce cross-source ordering ambiguity and could select a different transition.
        $timing = $telemetry.timing
        $presentation = $telemetry.presentation
        $fps = $telemetry.fps
        $session = Parse-LatestMetric -Text $transitionLog -Pattern 'Camera session CONFIGURED ok.*?latencyMs=(\d+)'
        $frameGaps = [regex]::Matches(
            $transitionLog,
            'PreviewFrameGap\s+callbackGapMs=([0-9.]+)\s+sensorGapMs=([0-9.]+)'
        )
        $fatal = [regex]::IsMatch(
            $transitionLog,
            'FATAL EXCEPTION|ANR in com\.neoproc\.neoproccamx|OutOfMemoryError|' +
                'CameraAccessException|CAMERA_DISCONNECTED|DEVICE_ERROR|ERROR_CAMERA_DEVICE|' +
                'ERROR_CAMERA_SERVICE|eglSwapBuffers failed|Strict preview runtime-failure'
        )
        $continuity = Get-CandidateContinuityAssessment `
            -TransitionLog $transitionLog `
            -PackageName $Package `
            -ExpectedAppPid $initialAppPid `
            -CurrentAppPid $currentAppPid `
            -ExpectedInstalledPath $initialInstalledPath `
            -CurrentInstalledPath $currentInstalledPath `
            -CurrentForegroundPackage $foregroundPackage
        $candidateReplaced = $continuity.candidate_mutated
        $forceStopDetected = $continuity.force_stopped
        $activeClient = Test-ActiveCameraClient -CameraServiceText $cameraState -ClientPackage $Package

        $modeSwitchMs = if ($timing) { [double]$timing.Groups[2].Value } else { $null }
        $modeSwitchRoute = if ($timing) { [string]$timing.Groups[1].Value } else { '' }
        $previewFps = if ($fps) { [double]$fps.Groups[1].Value } else { $null }
        $presentFrom = if ($presentation) { [string]$presentation.Groups[1].Value } else { '' }
        $presentMode = if ($presentation) { [string]$presentation.Groups[2].Value } else { '' }
        $firstFreshFrameLatencyMs = if ($presentation) { [double]$presentation.Groups[3].Value } else { $null }
        $presentationSequence = if ($presentation) { [long]$presentation.Groups[4].Value } else { $null }
        $sessionGeneration = if ($presentation) { [long]$presentation.Groups[5].Value } else { $null }
        $surfaceEpoch = if ($presentation) { [long]$presentation.Groups[6].Value } else { $null }
        $sensorTimestampNs = if ($presentation) { [long]$presentation.Groups[7].Value } else { $null }
        $presentationTimestampNs = if ($presentation) { [long]$presentation.Groups[8].Value } else { $null }
        $maximumCallbackGapMs = if ($frameGaps.Count -gt 0) {
            @($frameGaps | ForEach-Object { [double]$_.Groups[1].Value } | Measure-Object -Maximum)[0].Maximum
        } else { $null }
        $maximumSensorGapMs = if ($frameGaps.Count -gt 0) {
            @($frameGaps | ForEach-Object { [double]$_.Groups[2].Value } | Measure-Object -Maximum)[0].Maximum
        } else { $null }
        $transitionFailures = [Collections.Generic.List[string]]::new()

        if ($actual -ne $step.expected) {
            $transitionFailures.Add("selected mode expected=$($step.expected) actual=$actual")
        }
        if (-not $continuity.foreground_matches) {
            $transitionFailures.Add(
                "foreground UI package changed expected=$Package actual=$foregroundPackage"
            )
        }
        if (-not $continuity.pid_matches) {
            $transitionFailures.Add(
                "application process identity changed expectedPid=$initialAppPid actualPid=$currentAppPid"
            )
        }
        if (-not $continuity.installed_path_matches) {
            $transitionFailures.Add(
                "installed candidate path changed expected=$initialInstalledPath actual=$currentInstalledPath"
            )
        }
        if ($continuity.authoritative_replacement_signal) {
            $transitionFailures.Add('authoritative package replacement was detected during the transition')
        }
        if ($continuity.authoritative_force_stop_signal) {
            $transitionFailures.Add('authoritative package force-stop was detected during the transition')
        }
        if (-not $timing) {
            $transitionFailures.Add('ModeSwitchTiming evidence is missing')
        } else {
            $expectedRoute = "$expectedFromEnum->$expectedModeEnum"
            if ($modeSwitchRoute -ne $expectedRoute) {
                $transitionFailures.Add("mode-switch route expected=$expectedRoute actual=$modeSwitchRoute")
            }
            if (-not (Test-FiniteDouble $modeSwitchMs) -or $modeSwitchMs -gt $maximumFirstFreshFrameLatencyMs) {
                $transitionFailures.Add("mode-switch batch latency is $modeSwitchMs ms; required <=$maximumFirstFreshFrameLatencyMs ms")
            }
        }
        if (-not $presentation) {
            $transitionFailures.Add("MODE_SWITCH_PRESENTED evidence is missing for $expectedModeEnum")
        } else {
            if ($presentFrom -ne $expectedFromEnum -or $presentMode -ne $expectedModeEnum) {
                $transitionFailures.Add(
                    "fresh-frame route expected=$expectedFromEnum->$expectedModeEnum actual=$presentFrom->$presentMode"
                )
            }
            if (-not (Test-FiniteDouble $firstFreshFrameLatencyMs) -or
                $firstFreshFrameLatencyMs -gt $maximumFirstFreshFrameLatencyMs) {
                $transitionFailures.Add(
                    "first fresh visible frame latency is $firstFreshFrameLatencyMs ms; " +
                        "required <=$maximumFirstFreshFrameLatencyMs ms"
                )
            }
            if ($presentationSequence -le $previousSequence) {
                $transitionFailures.Add(
                    "presentation sequence did not advance: previous=$previousSequence actual=$presentationSequence"
                )
            }
            if ($sensorTimestampNs -le $previousSensorTimestampNs) {
                $transitionFailures.Add(
                    "sensor timestamp did not advance: previous=$previousSensorTimestampNs actual=$sensorTimestampNs"
                )
            }
            if ($presentationTimestampNs -le $previousPresentationTimestampNs) {
                $transitionFailures.Add(
                    "presentation timestamp did not advance: previous=$previousPresentationTimestampNs " +
                        "actual=$presentationTimestampNs"
                )
            }
            if ($sessionGeneration -lt 0L -or $surfaceEpoch -lt 0L) {
                $transitionFailures.Add(
                    "invalid presentation identity sessionGeneration=$sessionGeneration surfaceEpoch=$surfaceEpoch"
                )
            }
            if ($null -ne $previousSessionGeneration -and
                (($step.from -eq 'Pro' -and $step.expected -eq 'Raw') -or
                    ($step.from -eq 'Raw' -and $step.expected -eq 'Video')) -and
                $sessionGeneration -le [long]$previousSessionGeneration) {
                $transitionFailures.Add(
                    "RAW-family boundary did not advance Camera2 generation: " +
                        "previous=$previousSessionGeneration actual=$sessionGeneration"
                )
            }
            $previousSequence = [long]$presentationSequence
            $previousSensorTimestampNs = [long]$sensorTimestampNs
            $previousPresentationTimestampNs = [long]$presentationTimestampNs
            $previousSessionGeneration = [long]$sessionGeneration
        }
        if ($null -eq $previewFps) {
            $transitionFailures.Add('final-display preview FPS evidence is missing')
        } elseif (-not (Test-FiniteDouble $previewFps) -or
            $previewFps -lt $minimumPreviewFps -or $previewFps -gt $maximumPreviewFps) {
            $transitionFailures.Add(
                "final-display preview FPS is $previewFps; required $minimumPreviewFps-$maximumPreviewFps"
            )
        }
        if (-not $activeClient) { $transitionFailures.Add('CameraService does not show an active app client') }
        if ($thermalAfter.status -gt 1) {
            $transitionFailures.Add("thermal status is $($thermalAfter.status); required 0-1")
        }
        if ($fatal) { $transitionFailures.Add('fatal camera/application/runtime signal detected') }
        foreach ($reason in $transitionFailures) {
            $machineFailures.Add("transition ${index}: $reason")
        }

        $rows.Add([pscustomobject]@{
            transition = $index
            cycle = $step.cycle
            from = $step.from
            expected = $step.expected
            actual = $actual
            evidence_collection_elapsed_ms = $evidenceCollectionElapsedMs
            telemetry_wait_ms = $telemetry.elapsed_ms
            telemetry_poll_complete = $telemetry.complete
            telemetry_evidence_sources = $telemetry.evidence_sources -join ','
            mode_switch_total_ms = $modeSwitchMs
            mode_switch_route = $modeSwitchRoute
            first_fresh_frame_latency_ms = $firstFreshFrameLatencyMs
            presentation_sequence = $presentationSequence
            session_generation = $sessionGeneration
            surface_epoch = $surfaceEpoch
            sensor_timestamp_ns = $sensorTimestampNs
            presentation_timestamp_ns = $presentationTimestampNs
            preview_fps = $previewFps
            session_config_latency_ms = if ($session) { [int]$session.Groups[1].Value } else { $null }
            maximum_reported_callback_gap_ms = $maximumCallbackGapMs
            maximum_reported_sensor_gap_ms = $maximumSensorGapMs
            thermal_status = $thermalAfter.status
            skin_c = $thermalAfter.skinC
            camera_client_present = $activeClient
            foreground_package = $foregroundPackage
            ui_selection_attempts = $uiSelectionAttempts
            app_pid = $currentAppPid
            initial_app_pid = $initialAppPid
            installed_path = $currentInstalledPath
            initial_installed_path = $initialInstalledPath
            candidate_replaced = $candidateReplaced
            force_stop_detected = $forceStopDetected
            authoritative_replacement_signal = $continuity.authoritative_replacement_signal
            authoritative_force_stop_signal = $continuity.authoritative_force_stop_signal
            observer_replacement_only = $continuity.observer_replacement_only
            observer_replacement_count = $continuity.observer_replacement_count
            fatal_signal = $fatal
            ui_tree_path = $xmlPath
            ui_tree_sha256 = $uiTreeHash
            screenshot_path = $screenshotPath
            screenshot_sha256 = $screenshotHash
            machine_status = if ($transitionFailures.Count -eq 0) { 'PASS' } else { 'FAIL' }
            failure_reasons = $transitionFailures -join '; '
        })

        if ($actual -ne $step.expected) {
            throw "Transition $index expected=$($step.expected) actual=$actual"
        }
        if (-not $continuity.continuous) {
            throw "Candidate continuity was lost after transition $index"
        }
        if ($fatal) { throw "Fatal camera/application signal detected after transition $index" }
        Write-Output (
            ("LQ-001.03 {0:D2}/30 expected={1} actual={2} firstFrameMs={3} fps={4} " +
                "thermal={5} machine={6}") -f $index, $step.expected, $actual,
                $firstFreshFrameLatencyMs, $previewFps, $thermalAfter.status,
                $rows[$rows.Count - 1].machine_status
        )
    }
} catch {
    $failure = $_.Exception.Message
    [Console]::Error.WriteLine($failure)
} finally {
    if ($logProcess -and -not $logProcess.HasExited) {
        Stop-Process -Id $logProcess.Id -ErrorAction SilentlyContinue
        $logProcess.WaitForExit()
    }
    if ($adbUsable) {
        # Seal candidate identity before any runner cleanup force-stop. Hashing once here avoids
        # contaminating per-transition thermal/performance measurements while proving that the
        # exact APK admitted at startup remained installed throughout the cycle batch.
        try {
            $finalAppOwnedLogText = Read-AppOwnedLogTail `
                -RemotePath $script:currentAppOwnedLogPath `
                -MaximumBytes 4194304
            Write-Utf8 -Path (Join-Path $OutputDir '34-final-app-owned-log.txt') `
                -Text $finalAppOwnedLogText
            $finalInstalledIdentity = Get-InstalledApkIdentity
            $finalAppPid = Get-AppPid
            $finalPathMatches = -not [string]::IsNullOrWhiteSpace($initialInstalledPath) -and
                $finalInstalledIdentity.path -eq $initialInstalledPath
            $finalHashMatches = -not [string]::IsNullOrWhiteSpace($candidateHash) -and
                $finalInstalledIdentity.sha256 -eq $candidateHash
            $finalPidMatches = -not [string]::IsNullOrWhiteSpace($initialAppPid) -and
                $finalAppPid -eq $initialAppPid
            $finalIdentityVerified = $finalPathMatches -and $finalHashMatches -and $finalPidMatches
            if (-not $finalPathMatches) {
                $machineFailures.Add(
                    "final installed candidate path changed expected=$initialInstalledPath " +
                        "actual=$($finalInstalledIdentity.path)"
                )
            }
            if (-not $finalHashMatches) {
                $machineFailures.Add(
                    "final installed APK hash changed expected=$candidateHash " +
                        "actual=$($finalInstalledIdentity.sha256)"
                )
            }
            if (-not $finalPidMatches) {
                $machineFailures.Add(
                    "final application process identity changed expectedPid=$initialAppPid " +
                        "actualPid=$finalAppPid"
                )
            }
        } catch {
            $finalInstalledIdentityError = $_.Exception.Message
            $machineFailures.Add(
                "final installed APK identity capture failed: $finalInstalledIdentityError"
            )
        }
        $finalIdentityProof = [ordered]@{
            checkpoint = 'LQ-001.03'
            captured_utc = [DateTime]::UtcNow.ToString('o')
            candidate_sha256 = $candidateHash
            initial_installed_path = $initialInstalledPath
            initial_installed_sha256 = if ($installedIdentity) { $installedIdentity.sha256 } else { '' }
            final_installed_path = if ($finalInstalledIdentity) { $finalInstalledIdentity.path } else { '' }
            final_installed_sha256 = if ($finalInstalledIdentity) { $finalInstalledIdentity.sha256 } else { '' }
            initial_app_pid = $initialAppPid
            final_app_pid = $finalAppPid
            path_matches_initial = if ($finalInstalledIdentity) {
                $finalInstalledIdentity.path -eq $initialInstalledPath
            } else { $false }
            hash_matches_candidate = if ($finalInstalledIdentity) {
                $finalInstalledIdentity.sha256 -eq $candidateHash
            } else { $false }
            pid_matches_initial = $finalAppPid -eq $initialAppPid -and
                -not [string]::IsNullOrWhiteSpace($initialAppPid)
            verified = $finalIdentityVerified
            capture_error = $finalInstalledIdentityError
        }
        Write-Utf8 -Path (Join-Path $OutputDir '33-final-candidate-identity.json') `
            -Text ($finalIdentityProof | ConvertTo-Json -Depth 5)

        try { Save-Adb -Name '25-final-logcat.txt' -Arguments @('logcat', '-d', '-v', 'epoch') -AllowFailure | Out-Null } catch {
            $machineFailures.Add("final logcat capture failed: $($_.Exception.Message)")
        }
        try { Save-Adb -Name '26-final-gfxinfo.txt' -Arguments @('shell', 'dumpsys', 'gfxinfo', $Package, 'framestats') -AllowFailure | Out-Null } catch {
            $machineFailures.Add("final gfxinfo capture failed: $($_.Exception.Message)")
        }
        try { Save-Adb -Name '27-final-camera-service.txt' -Arguments @('shell', 'dumpsys', 'media.camera') -AllowFailure | Out-Null } catch {
            $machineFailures.Add("final CameraService capture failed: $($_.Exception.Message)")
        }
        try { Save-Adb -Name '28-final-crash.txt' -Arguments @('logcat', '-b', 'crash', '-d', '-v', 'epoch') -AllowFailure | Out-Null } catch {
            $machineFailures.Add("crash-buffer capture failed: $($_.Exception.Message)")
        }
        try { Save-Adb -Name '29-final-anr.txt' -Arguments @('shell', 'dumpsys', 'activity', 'lastanr') -AllowFailure | Out-Null } catch {
            $machineFailures.Add("ANR-state capture failed: $($_.Exception.Message)")
        }

        Invoke-Adb -Arguments @('shell', 'am', 'force-stop', $Package) -AllowFailure | Out-Null
        Start-Sleep -Milliseconds 500
        try {
            $postStopCamera = Save-Adb -Name '30-post-stop-camera-service.txt' `
                -Arguments @('shell', 'dumpsys', 'media.camera') -AllowFailure
            if (Test-ActiveCameraClient -CameraServiceText $postStopCamera -ClientPackage $Package) {
                $machineFailures.Add('CameraService still reports the app after force-stop cleanup')
            }
        } catch {
            $machineFailures.Add("post-stop CameraService capture failed: $($_.Exception.Message)")
        }
        if ($originalBrightnessMode -match '^\d+$') {
            Invoke-Adb -Arguments @('shell', 'settings', 'put', 'system', 'screen_brightness_mode', $originalBrightnessMode) -AllowFailure | Out-Null
        }
        if ($originalBrightness -match '^\d+$') {
            Invoke-Adb -Arguments @('shell', 'settings', 'put', 'system', 'screen_brightness', $originalBrightness) -AllowFailure | Out-Null
        }
        Invoke-Adb -Arguments @('shell', 'svc', 'power', 'stayon', 'false') -AllowFailure | Out-Null
        Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_SLEEP') -AllowFailure | Out-Null
    }

    # Device control is complete. Release the global lease before report generation so a report
    # serialization or filesystem failure cannot unnecessarily retain exclusive ADB ownership.
    if ($adbLease) {
        $adbLease.Dispose()
        $adbLease = $null
    }

    $rows | Export-Csv -NoTypeInformation -LiteralPath (Join-Path $OutputDir '24-mode-switch-results.csv')

    if ($rows.Count -ne $requiredTransitionCount) {
        $machineFailures.Add("executed $($rows.Count) transitions; required $requiredTransitionCount")
    }
    if ($failure) { $machineFailures.Add("operational failure: $failure") }
    if ($rows.Count -gt 0 -and $candidateHash) {
        Write-ManualVisualReviewTemplate -TransitionRows $rows -CandidateHash $candidateHash
        $visualReview = Test-ManualVisualReview `
            -TransitionRows $rows `
            -CandidateHash $candidateHash `
            -ReviewPath $ManualVisualReviewPath
    }
    $machineStatus = if ($machineFailures.Count -eq 0) { 'PASS' } else { 'FAIL' }
    $checkpointStatus = if ($machineStatus -ne 'PASS') {
        'FAIL'
    } elseif ($visualReview.accepted) {
        'PASS'
    } else {
        'PENDING_MANUAL_VISUAL_REVIEW'
    }
    $summary = [ordered]@{
        checkpoint = 'LQ-001.03'
        status = $checkpointStatus
        machine_status = $machineStatus
        candidate_sha256 = $candidateHash
        installed_apk_sha256 = if ($installedIdentity) { $installedIdentity.sha256 } else { '' }
        initial_installed_apk_sha256 = if ($installedIdentity) { $installedIdentity.sha256 } else { '' }
        final_installed_apk_sha256 = if ($finalInstalledIdentity) {
            $finalInstalledIdentity.sha256
        } else { '' }
        final_installed_apk_path = if ($finalInstalledIdentity) {
            $finalInstalledIdentity.path
        } else { '' }
        final_app_pid = $finalAppPid
        final_candidate_identity_verified = $finalIdentityVerified
        final_candidate_identity_error = $finalInstalledIdentityError
        final_candidate_identity_evidence = Join-Path $OutputDir '33-final-candidate-identity.json'
        serial = $Serial
        thresholds = [ordered]@{
            transitions = $requiredTransitionCount
            maximum_first_fresh_frame_latency_ms = $maximumFirstFreshFrameLatencyMs
            minimum_final_display_fps = $minimumPreviewFps
            maximum_final_display_fps = $maximumPreviewFps
            maximum_thermal_status = 1
        }
        evidence_collection = [ordered]@{
            telemetry_poll_timeout_ms = $telemetryWaitTimeoutMs
            telemetry_poll_interval_ms = $telemetryPollIntervalMs
            app_owned_log_path = $script:currentAppOwnedLogPath
            final_app_owned_log_evidence = Join-Path $OutputDir '34-final-app-owned-log.txt'
            maximum_ui_selection_attempts = $maximumUiSelectionAttempts
            initial_app_pid = $initialAppPid
            initial_installed_path = $initialInstalledPath
        }
        transitions_expected = $requiredTransitionCount
        transitions_executed = $rows.Count
        selected_mode_matches = @($rows | Where-Object { $_.expected -eq $_.actual }).Count
        telemetry_complete_transitions = @($rows | Where-Object telemetry_poll_complete).Count
        foreground_package_matches = @($rows | Where-Object { $_.foreground_package -eq $Package }).Count
        candidate_continuity_failures = @(
            $rows | Where-Object {
                $_.candidate_replaced -or
                    $_.force_stop_detected -or
                    $_.app_pid -ne $_.initial_app_pid -or
                    $_.installed_path -ne $_.initial_installed_path
            }
        ).Count
        activity_thread_replacement_observations = @(
            $rows | Where-Object observer_replacement_only
        ).Count
        maximum_mode_switch_ms = if ($rows.Count) { ($rows | Measure-Object mode_switch_total_ms -Maximum).Maximum } else { $null }
        maximum_first_fresh_frame_latency_ms = if ($rows.Count) {
            ($rows | Where-Object { $null -ne $_.first_fresh_frame_latency_ms } |
                Measure-Object first_fresh_frame_latency_ms -Maximum).Maximum
        } else { $null }
        minimum_preview_fps = if ($rows.Count) { ($rows | Where-Object preview_fps | Measure-Object preview_fps -Minimum).Minimum } else { $null }
        maximum_preview_fps = if ($rows.Count) { ($rows | Where-Object preview_fps | Measure-Object preview_fps -Maximum).Maximum } else { $null }
        maximum_thermal_status = if ($rows.Count) { ($rows | Measure-Object thermal_status -Maximum).Maximum } else { $null }
        maximum_skin_c = if ($rows.Count) { ($rows | Measure-Object skin_c -Maximum).Maximum } else { $null }
        fatal_signals = @($rows | Where-Object fatal_signal).Count
        machine_failure_count = $machineFailures.Count
        machine_failures = @($machineFailures)
        visual_review_accepted = $visualReview.accepted
        visual_review_reason = $visualReview.reason
        manual_visual_review_template = Join-Path $OutputDir '31-manual-visual-review-template.json'
        operational_failure = $failure
    }
    $summaryJson = $summary | ConvertTo-Json -Depth 7
    Write-Utf8 -Path (Join-Path $OutputDir '30-summary.json') -Text $summaryJson
    Write-Utf8 -Path (Join-Path $OutputDir '32-summary.json') -Text $summaryJson
}

if ($machineStatus -ne 'PASS') { exit 1 }
if (-not $visualReview.accepted) { exit 2 }
exit 0
