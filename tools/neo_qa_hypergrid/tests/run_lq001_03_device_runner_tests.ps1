param(
    [string]$RunnerPath = (
        Join-Path (Split-Path -Parent $PSScriptRoot) 'run_lq001_03_device.ps1'
    ),
    [string]$FailedEvidenceDir = '',
    [string]$Run10EvidenceDir = ''
)

$ErrorActionPreference = 'Stop'

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Import-RunnerFunction {
    param(
        [Parameter(Mandatory)]
        [System.Management.Automation.Language.ScriptBlockAst]$Ast,
        [Parameter(Mandatory)][string]$Name
    )
    $node = $Ast.Find(
        {
            param($candidate)
            $candidate -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
                $candidate.Name -eq $Name
        },
        $true
    )
    if ($null -eq $node) { throw "Runner function is missing: $Name" }
    $bodyText = $node.Body.Extent.Text
    $bodyText = $bodyText.Substring(1, $bodyText.Length - 2)
    Set-Item `
        -Path "Function:script:$Name" `
        -Value ([scriptblock]::Create($bodyText))
}

$runner = (Resolve-Path -LiteralPath $RunnerPath).Path
$source = Get-Content -LiteralPath $runner -Raw
$tokens = $null
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseInput(
    $source,
    [ref]$tokens,
    [ref]$parseErrors
)
Assert-True ($parseErrors.Count -eq 0) "Runner AST has $($parseErrors.Count) parse errors"

@(
    'Get-PackageLifecycleSignals',
    'Get-CandidateContinuityAssessment',
    'Get-ActiveCameraClientsBlock',
    'Test-ActiveCameraClient',
    'Parse-LatestMetric',
    'Get-CurrentAppOwnedLogPath',
    'Read-AppOwnedLogTail',
    'Read-AppOwnedTelemetryTail',
    'Parse-PresentationMarker',
    'Parse-TransitionTelemetrySource',
    'Wait-ForTransitionTelemetry',
    'Get-ExpectedModeEnum',
    'Test-FiniteDouble',
    'Test-ManualVisualReview',
    'Read-SelectedMode',
    'Read-UiRootPackage'
) | ForEach-Object { Import-RunnerFunction -Ast $ast -Name $_ }

$package = 'com.neoproc.neoproccamx'
$activeCamera = @'
Camera service dump
Active Camera Clients:
[
(Camera ID: 0, PID: 123, Client Package Name: com.neoproc.neoproccamx)
]
Allowed user IDs:
0
'@
$historyOnly = @'
Active Camera Clients:
[
]
Allowed user IDs:
0
Camera events log:
CONNECT device 0 client for package com.neoproc.neoproccamx
DISCONNECT device 0 client for package com.neoproc.neoproccamx
'@
Assert-True `
    (Test-ActiveCameraClient -CameraServiceText $activeCamera -ClientPackage $package) `
    'Active CameraService client was rejected'
Assert-True `
    (-not (Test-ActiveCameraClient -CameraServiceText $historyOnly -ClientPackage $package)) `
    'Historical CameraService event produced a false active-client result'

$run10ObserverReplacement = @'
         1788176505.583 20956 20956 D ActivityThread: Package [com.neoproc.neoproccamx] reported as REPLACED, but missing application info. Assuming REMOVED.
'@
$unchangedPath = '/data/app/~~proof/com.neoproc.neoproccamx/base.apk'
$observerAssessment = Get-CandidateContinuityAssessment `
    -TransitionLog $run10ObserverReplacement `
    -PackageName $package `
    -ExpectedAppPid '27512' `
    -CurrentAppPid '27512' `
    -ExpectedInstalledPath $unchangedPath `
    -CurrentInstalledPath $unchangedPath `
    -CurrentForegroundPackage $package
Assert-True $observerAssessment.continuous `
    'Run 10 ActivityThread observer notification falsely broke candidate continuity'
Assert-True (-not $observerAssessment.candidate_mutated) `
    'Run 10 ActivityThread observer notification falsely reported candidate mutation'
Assert-True (-not $observerAssessment.force_stopped) `
    'Run 10 ActivityThread observer notification falsely reported force-stop'
Assert-True $observerAssessment.observer_replacement_only `
    'Run 10 ActivityThread observer notification was not retained as diagnostic evidence'
Assert-True ($observerAssessment.observer_replacement_count -eq 1) `
    'Run 10 ActivityThread observer notification count is incorrect'

$realForceStop = @'
         1788176505.583 1234 5678 I ActivityManager: Force stopping com.neoproc.neoproccamx appid=10123 user=0: from pid 9999
'@
$forceStopAssessment = Get-CandidateContinuityAssessment `
    -TransitionLog $realForceStop `
    -PackageName $package `
    -ExpectedAppPid '27512' `
    -CurrentAppPid '27512' `
    -ExpectedInstalledPath $unchangedPath `
    -CurrentInstalledPath $unchangedPath `
    -CurrentForegroundPackage $package
Assert-True (-not $forceStopAssessment.continuous) `
    'Authoritative ActivityManager force-stop was ignored'
Assert-True $forceStopAssessment.authoritative_force_stop_signal `
    'Authoritative ActivityManager force-stop signal was not classified'

$realReplacement = @'
         1788176505.583 1234 5678 I PackageManagerService: Package com.neoproc.neoproccamx replaced by install session 42
'@
$replacementAssessment = Get-CandidateContinuityAssessment `
    -TransitionLog $realReplacement `
    -PackageName $package `
    -ExpectedAppPid '27512' `
    -CurrentAppPid '27512' `
    -ExpectedInstalledPath $unchangedPath `
    -CurrentInstalledPath $unchangedPath `
    -CurrentForegroundPackage $package
Assert-True (-not $replacementAssessment.continuous) `
    'Authoritative PackageManager replacement was ignored'
Assert-True $replacementAssessment.candidate_mutated `
    'Authoritative PackageManager replacement did not mark candidate mutation'

$pathMutationAssessment = Get-CandidateContinuityAssessment `
    -TransitionLog '' `
    -PackageName $package `
    -ExpectedAppPid '27512' `
    -CurrentAppPid '27512' `
    -ExpectedInstalledPath $unchangedPath `
    -CurrentInstalledPath '/data/app/~~replacement/com.neoproc.neoproccamx/base.apk' `
    -CurrentForegroundPackage $package
Assert-True (-not $pathMutationAssessment.continuous) `
    'Installed-package path mutation was ignored without a log signal'
Assert-True $pathMutationAssessment.candidate_mutated `
    'Installed-package path mutation did not mark candidate mutation'

$pidMutationAssessment = Get-CandidateContinuityAssessment `
    -TransitionLog '' `
    -PackageName $package `
    -ExpectedAppPid '27512' `
    -CurrentAppPid '30001' `
    -ExpectedInstalledPath $unchangedPath `
    -CurrentInstalledPath $unchangedPath `
    -CurrentForegroundPackage $package
Assert-True (-not $pidMutationAssessment.continuous) `
    'Application PID mutation was ignored without a log signal'
Assert-True $pidMutationAssessment.force_stopped `
    'Application PID mutation did not fail the force-stop/continuity gate'

$stale = @'
ModeSwitchTiming VIDEO->PRO total=25.0ms
MODE_SWITCH_PRESENTED from=VIDEO mode=PRO latencyMs=80.0 sequence=5 sessionGeneration=2 surfaceEpoch=3 sensorTimestampNs=100 presentationTimestampNs=101
previewFps=25.0 recording=false
'@
$freshWithoutFps = @'
ModeSwitchTiming VIDEO->PRO total=26.0ms
MODE_SWITCH_PRESENTED from=VIDEO mode=PRO latencyMs=82.0 sequence=6 sessionGeneration=3 surfaceEpoch=4 sensorTimestampNs=200 presentationTimestampNs=201
'@
$freshComplete = $freshWithoutFps + "`r`npreviewFps=25.0 recording=false"
$script:mockTelemetry = @($stale, $freshWithoutFps, $freshComplete)
$script:mockTelemetryCall = 0
$script:currentAppOwnedLogPath = ''
function Invoke-Adb {
    param([string[]]$Arguments, [switch]$AllowFailure)
    $slot = [math]::Min($script:mockTelemetryCall, $script:mockTelemetry.Count - 1)
    $script:mockTelemetryCall += 1
    return @($script:mockTelemetry[$slot] -split "\r?\n")
}
$telemetry = Wait-ForTransitionTelemetry `
    -ExpectedFrom 'VIDEO' `
    -ExpectedMode 'PRO' `
    -MinimumSequence 5L `
    -TimeoutMs 500 `
    -PollIntervalMs 1
Assert-True $telemetry.complete 'Telemetry poll did not reach a complete fresh evidence set'
Assert-True ($script:mockTelemetryCall -eq 3) 'Telemetry poll accepted stale or pre-presentation FPS evidence'
Assert-True ([long]$telemetry.presentation.Groups[4].Value -eq 6L) 'Telemetry poll selected the wrong sequence'
Assert-True ([double]$telemetry.fps.Groups[1].Value -eq 25.0) 'Telemetry poll selected the wrong FPS sample'

# A fresh logcat presentation followed textually by an old app-owned FPS sample used to form a
# fabricated cross-source proof chain. One source must contain the complete ordered chain.
$script:mockMixedLogcat = $freshWithoutFps
$script:mockMixedAppTelemetry = @($stale, $stale, $freshComplete)
$script:mockMixedAppCall = 0
$script:currentAppOwnedLogPath = '/sdcard/Android/data/com.neoproc.neoproccamx/files/neoproc_mixed.log'
function Invoke-Adb {
    param([string[]]$Arguments, [switch]$AllowFailure)
    return @($script:mockMixedLogcat -split "\r?\n")
}
function Read-AppOwnedTelemetryTail {
    param([string]$RemotePath)
    Assert-True ($RemotePath -eq $script:currentAppOwnedLogPath) 'Wrong mixed-source app log was polled'
    $slot = [math]::Min($script:mockMixedAppCall, $script:mockMixedAppTelemetry.Count - 1)
    $script:mockMixedAppCall += 1
    return $script:mockMixedAppTelemetry[$slot]
}
$mixedTelemetry = Wait-ForTransitionTelemetry `
    -ExpectedFrom 'VIDEO' `
    -ExpectedMode 'PRO' `
    -MinimumSequence 5L `
    -TimeoutMs 500 `
    -PollIntervalMs 1
Assert-True $mixedTelemetry.complete 'Mixed-source polling never reached a valid single-source proof chain'
Assert-True ($script:mockMixedAppCall -eq 3) 'A stale FPS from another source completed the proof chain'
Assert-True ($mixedTelemetry.source -eq 'app_owned_durable_log') 'Wrong single-source proof chain selected'

# Vivo/OriginOS can omit third-party INFO records from logd. Prove that the identical durable
# app-owned stream independently completes the telemetry gate instead of silently weakening it.
$script:mockAppTelemetry = @($stale, $freshWithoutFps, $freshComplete)
$script:mockAppTelemetryCall = 0
$script:currentAppOwnedLogPath = '/sdcard/Android/data/com.neoproc.neoproccamx/files/neoproc_test.log'
function Invoke-Adb {
    param([string[]]$Arguments, [switch]$AllowFailure)
    return @()
}
function Read-AppOwnedLogTail {
    param([string]$RemotePath, [int]$MaximumBytes = 262144)
    Assert-True ($RemotePath -eq $script:currentAppOwnedLogPath) 'Wrong app-owned log was polled'
    $slot = [math]::Min($script:mockAppTelemetryCall, $script:mockAppTelemetry.Count - 1)
    $script:mockAppTelemetryCall += 1
    return $script:mockAppTelemetry[$slot]
}
function Read-AppOwnedTelemetryTail {
    param([string]$RemotePath)
    return Read-AppOwnedLogTail -RemotePath $RemotePath
}
$appOwnedTelemetry = Wait-ForTransitionTelemetry `
    -ExpectedFrom 'VIDEO' `
    -ExpectedMode 'PRO' `
    -MinimumSequence 5L `
    -TimeoutMs 500 `
    -PollIntervalMs 1
Assert-True $appOwnedTelemetry.complete 'App-owned telemetry did not complete the unchanged gate'
Assert-True ($script:mockAppTelemetryCall -eq 3) 'App-owned telemetry accepted stale evidence'
Assert-True `
    ($appOwnedTelemetry.evidence_sources -contains 'app_owned_durable_log') `
    'App-owned telemetry provenance was not recorded'

$reviewRows = [Collections.Generic.List[object]]::new()
$reviewRows.Add([pscustomobject]@{ transition = 1 })
$emptyReview = Test-ManualVisualReview `
    -TransitionRows $reviewRows `
    -CandidateHash ('A' * 64) `
    -ReviewPath ''
Assert-True (-not $emptyReview.accepted) 'An absent manual review must remain pending'
Assert-True `
    ($emptyReview.reason -eq 'Manual visual review was not supplied') `
    'An absent optional manual review returned the wrong reason'

$xmlPath = Join-Path ([IO.Path]::GetTempPath()) ("lq001-ui-{0}.xml" -f [Guid]::NewGuid())
try {
    [IO.File]::WriteAllText(
        $xmlPath,
        '<hierarchy><node package="com.neoproc.neoproccamx"><node text="Raw" selected="true" /></node></hierarchy>',
        [Text.UTF8Encoding]::new($false)
    )
    Assert-True ((Read-UiRootPackage -XmlPath $xmlPath) -eq $package) 'UI root package parser failed'
    Assert-True ((Read-SelectedMode -XmlPath $xmlPath) -eq 'Raw') 'Selected-mode parser failed'
} finally {
    Remove-Item -LiteralPath $xmlPath -Force -ErrorAction SilentlyContinue
}

Assert-True ($source -match '\$minimumPreviewFps\s*=\s*24\.8') 'Minimum FPS threshold changed'
Assert-True ($source -match '\$maximumPreviewFps\s*=\s*25\.2') 'Maximum FPS threshold changed'
Assert-True ($source -match '\$maximumFirstFreshFrameLatencyMs\s*=\s*1000\.0') 'Latency threshold changed'
Assert-True ($source -match '\$requiredTransitionCount\s*=\s*30') 'Transition count changed'
Assert-True ($source -match '\$telemetryWaitTimeoutMs\s*=\s*7500') 'Telemetry collection timeout changed'
Assert-True ($source -match '\$timing\s*=\s*\$telemetry\.timing') `
    'Final validation does not reuse the accepted single-source timing proof'
Assert-True ($source -match 'APP-OWNED DURABLE LOG') `
    'OEM-safe app-owned telemetry fallback is missing'
Assert-True ($source -match 'telemetry-app-log\.txt') `
    'Per-transition app-owned evidence artifact is missing'
Assert-True ($source -match '34-final-app-owned-log\.txt') `
    'Final app-owned evidence artifact is missing'
Assert-True ($source -notmatch "'shell',\s*'sh',\s*'-c',\s*'getprop") 'Device identity still uses the broken sh -c form'
Assert-True ($source -match '33-final-candidate-identity\.json') `
    'Final installed-candidate identity artifact is missing'
Assert-True ($source -match 'final_installed_apk_sha256') `
    'Final installed APK SHA-256 is missing from the summary'
Assert-True ($source -match '\$finalInstalledIdentity\.sha256\s+-eq\s+\$candidateHash') `
    'Final installed APK hash is not compared with the admitted candidate'
Assert-True (-not $source.Contains('reported as REPLACED|PACKAGE_REPLACED.*$escapedPackage|')) `
    'Legacy text-only candidate replacement detector is still active'

if (-not [string]::IsNullOrWhiteSpace($FailedEvidenceDir)) {
    $failedEvidence = (Resolve-Path -LiteralPath $FailedEvidenceDir).Path
    $rows = @(Import-Csv -LiteralPath (Join-Path $failedEvidence '24-mode-switch-results.csv'))
    Assert-True ($rows.Count -eq 12) 'Failed-run fixture no longer has the expected 12 preserved rows'
    Assert-True `
        (@($rows | Where-Object { [string]::IsNullOrWhiteSpace($_.first_fresh_frame_latency_ms) }).Count -eq 12) `
        'Failed-run fixture no longer demonstrates missing first-frame evidence'
    $lastUi = Get-Content -LiteralPath (Join-Path $failedEvidence 'transition-12-c4-Video.xml') -Raw
    $lastLog = Get-Content -LiteralPath (Join-Path $failedEvidence 'transition-12-c4-Video-logcat.txt') -Raw
    Assert-True ($lastUi -match 'package="com\.android\.launcher3"') 'Failed-run fixture does not show launcher takeover'
    Assert-True `
        ($lastLog -match 'Package \[com\.neoproc\.neoproccamx\] reported as REPLACED') `
        'Failed-run fixture does not show package replacement'
}

if (-not [string]::IsNullOrWhiteSpace($Run10EvidenceDir)) {
    $run10Evidence = (Resolve-Path -LiteralPath $Run10EvidenceDir).Path
    $run10Rows = @(Import-Csv -LiteralPath (Join-Path $run10Evidence '24-mode-switch-results.csv'))
    Assert-True ($run10Rows.Count -eq 13) 'Run 10 fixture no longer has 13 preserved rows'
    $run10Row = $run10Rows[-1]
    $run10Log = Get-Content `
        -LiteralPath (Join-Path $run10Evidence 'transition-13-c5-Pro-logcat.txt') `
        -Raw
    $run10Assessment = Get-CandidateContinuityAssessment `
        -TransitionLog $run10Log `
        -PackageName $package `
        -ExpectedAppPid ([string]$run10Row.initial_app_pid) `
        -CurrentAppPid ([string]$run10Row.app_pid) `
        -ExpectedInstalledPath ([string]$run10Row.initial_installed_path) `
        -CurrentInstalledPath ([string]$run10Row.installed_path) `
        -CurrentForegroundPackage ([string]$run10Row.foreground_package)
    Assert-True $run10Assessment.continuous `
        'Run 10 exact evidence still produces a false candidate-continuity failure'
    Assert-True $run10Assessment.observer_replacement_only `
        'Run 10 exact evidence did not retain its observer-replacement diagnostic'
}

'LQ001_03_RUNNER_TESTS=PASS'
