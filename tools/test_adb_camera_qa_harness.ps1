param(
    [string]$HarnessPath = (Join-Path $PSScriptRoot "adb_camera_qa.ps1"),
    [string]$ReadmePath = (Join-Path $PSScriptRoot "README_ADB_CAMERA_QA.md")
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$script:PassCount = 0
$script:Failures = New-Object System.Collections.Generic.List[string]

function Assert-That {
    param(
        [Parameter(Mandatory)][bool]$Condition,
        [Parameter(Mandatory)][string]$Name
    )
    if ($Condition) {
        $script:PassCount += 1
        Write-Host "PASS $Name"
    } else {
        [void]$script:Failures.Add($Name)
        Write-Host "FAIL $Name"
    }
}

function Get-FunctionText {
    param(
        [Parameter(Mandatory)]$Ast,
        [Parameter(Mandatory)][string]$Name
    )
    $functionAst = $Ast.Find({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -eq $Name
    }, $true)
    if ($null -eq $functionAst) { throw "Function not found: $Name" }
    return $functionAst.Extent.Text
}

$resolvedHarness = (Resolve-Path -LiteralPath $HarnessPath).Path
$resolvedReadme = (Resolve-Path -LiteralPath $ReadmePath).Path
$text = [System.IO.File]::ReadAllText($resolvedHarness)
$readme = [System.IO.File]::ReadAllText($resolvedReadme)
$tokens = $null
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile(
    $resolvedHarness,
    [ref]$tokens,
    [ref]$parseErrors
)

Assert-That ($parseErrors.Count -eq 0) "PowerShell 5.1 AST parses without errors"
Assert-That ($text -notmatch '\.Kill\(\$true\)') "Unsupported Process.Kill(Boolean) overload is absent"
Assert-That ($text -match '\$proc\.Kill\(\)') "PowerShell-5-compatible Process.Kill() is present"
Assert-That ($text -match '\$proc\.WaitForExit\(5000\)') "Post-timeout process wait is bounded"
Assert-That ($text -match '\[System\.Threading\.Tasks\.Task\]::WaitAll') "Redirected-stream drain is bounded"
Assert-That ($text -notmatch '-Details\s*\(\s*if\s*\(') "PS5-incompatible inline-if Details expressions are absent"
Assert-That ($text -match '\$script:Results\.ToArray\(\)') "PS5-safe generic result-list conversion is present"
Assert-That ($text -match '\[switch\]\$RequireThermalPreflight\s*=\s*\$true') "Thermal preflight defaults enabled"
Assert-That ($text -match 'Thermal preflight is mandatory') "Thermal preflight cannot be disabled silently"
Assert-That ($text -match '\[ValidateRange\(12288,\s*1048576\)\]\s*\[int\]\$MinimumDataFreeMb\s*=\s*12288') "/data admission defaults to at least 12 GiB"
Assert-That ($text -match '\[ValidateRange\(12288,\s*1048576\)\]\s*\[int\]\$MinimumSdcardFreeMb\s*=\s*12288') "/sdcard admission defaults to at least 12 GiB"

$safeAssignments = @(
    'CaptureStressCount = 0', 'LongRunCaptureCount = 0',
    'SkipGenericIntents = $true', 'SkipExplicitAppLaunch = $true',
    'SkipBaselinePhotos = $true', 'SkipHdrCapture = $true', 'SkipRawCapture = $true',
    'SkipFocus = $true', 'SkipFacingSwitch = $true', 'SkipVideos = $true',
    'SkipPerformanceVideo = $true', 'SkipCameraWatch = $true',
    'SkipPermissionMutation = $true', 'SkipLogcatClear = $true',
    'SkipCrashRecovery = $true', 'SkipPowerAndAppSwitch = $true',
    'SkipLowStorage = $true', 'SkipLowBattery = $true', 'SkipIncomingCall = $true',
    'EnableLegacyMovVideoOracle = $false', 'EnablePerformanceVideoProbe = $false',
    'EnableLowStorageScenario = $false', 'EnableLowBatteryScenario = $false'
)
foreach ($assignment in $safeAssignments) {
    Assert-That ($text.Contains('$' + $assignment)) "SafeSmoke assignment: $assignment"
}

Assert-That ($text -match 'Invoke-PreMutationPreflight\s*\r?\n\s*\$script:DeviceReadyForCleanup\s*=\s*\$true') "Readiness preflight precedes cleanup arming and mutation"
Assert-That ($text -match 'if \(\$SkipLogcatClear\)[\s\S]*?return[\s\S]*?Run-Adb -CommandArgs @\("logcat", "-c"\)') "Logcat clear returns before mutation when skipped"
Assert-That ($text -match '\$EnablePerformanceVideoProbe') "Performance recording has an explicit opt-in"
Assert-That ($text -match '\$EnableLowStorageScenario -and -not \$SkipLowStorage') "Storage-fill scenario has an explicit opt-in"
Assert-That ($text -match '\$EnableLowBatteryScenario -and -not \$SkipLowBattery') "Battery override has an explicit opt-in"
Assert-That ($text -match 'Thermal Before Performance Video') "Thermal status is rechecked before performance recording"
Assert-That ($text -match 'Thermal Before Photo Stress') "Thermal status is rechecked before photo stress"
Assert-That ($text -match 'Thermal Before Long-Run Capture') "Thermal status is rechecked before long-run capture"
Assert-That ($text -match 'fill-file/pid removal not verified') "Fill-file cleanup verifies data and PID absence"
Assert-That ($text -match '\$script:MinimumPostFillReserveMb\s*=\s*2048L') "Storage fill preserves a 2 GiB floor"
Assert-That ($text -match 'function Stop-LowStorageFillProcess') "Storage fill records and fences its remote process"
Assert-That ($text -match 'free-space recovery not verified') "Storage cleanup verifies free-space recovery"
Assert-That ($text -match 'if \(-not \(Cleanup-LowStorageFill\)\)') "Storage fill is removed immediately after its dedicated capture"
Assert-That ($text -match 'Permission Restoration Fence') "Permission cleanup failure is a required result"
Assert-That ($text -match 'pid verification transport failed') "Process cleanup rejects transport ambiguity"
Assert-That ($text -match 'Wait-TopPackageAbsent') "Background transition has an independent absence oracle"
Assert-That ($text -notmatch 'android\.intent\.action\.CALL') "Harness cannot place an outgoing call"
Assert-That ($text -match 'exit 2') "Unresolved required coverage has a distinct nonzero exit"

Assert-That ($readme -match '12,288 MiB') "README documents mandatory storage admission"
Assert-That ($readme -match 'exits `2` when required coverage remains `SKIP`') "README documents required-SKIP exit semantics"
Assert-That ($readme -match '`-EnablePerformanceVideoProbe`') "README documents performance-recording opt-in"
Assert-That ($readme -match 'at least 2,048 MiB free') "README documents the storage-fill reserve"
Assert-That ($readme -match 'Clearing logcat is irreversible') "README discloses non-restorable mutations"

# Exercise policy functions without invoking the harness's top-level ADB workflow.
foreach ($functionName in @(
    'Add-Result',
    'Resolve-ItemsStatus',
    'Resolve-CategoryStatus',
    'Write-Reports',
    'Join-CommandArgs',
    'Run-Command'
)) {
    Invoke-Expression (Get-FunctionText -Ast $ast -Name $functionName)
}
function Log-Line { param([string]$Level, [string]$Message) }

$script:Results = New-Object System.Collections.Generic.List[object]
$SafeSmoke = $true
Add-Result -Category "Probe" -Step "Intentional" -Status "SKIP" -Details "safe profile"
Assert-That (-not $script:Results[0].Required) "SafeSmoke intentional SKIP is optional"

$temporaryReportRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("neoprocamx-adb-harness-test-" + [Guid]::NewGuid().ToString("N"))
[void](New-Item -ItemType Directory -Path $temporaryReportRoot)
try {
    $runId = "host_regression"
    $PackageName = "com.neoproc.neoproccamx"
    $reportPath = Join-Path $temporaryReportRoot "summary.md"
    $jsonPath = Join-Path $temporaryReportRoot "summary.json"
    Write-Reports
    Assert-That ((Test-Path -LiteralPath $reportPath) -and (Test-Path -LiteralPath $jsonPath)) "PS5 report writer emits Markdown and JSON"
    $jsonRows = @(Get-Content -LiteralPath $jsonPath -Raw | ConvertFrom-Json)
    Assert-That ($jsonRows.Count -eq 1 -and $jsonRows[0].Step -eq "Intentional") "PS5 report writer preserves generic-list rows"
} finally {
    Remove-Item -LiteralPath $temporaryReportRoot -Recurse -Force -ErrorAction SilentlyContinue
}

$requiredItems = @(
    [pscustomobject]@{ Required = $true; Status = "PASS" },
    [pscustomobject]@{ Required = $true; Status = "SKIP" }
)
Assert-That ((Resolve-ItemsStatus -Items $requiredItems) -eq "SKIP") "Required SKIP cannot aggregate to PASS"
$failedItems = @([pscustomobject]@{ Required = $false; Status = "FAIL" })
Assert-That ((Resolve-ItemsStatus -Items $failedItems) -eq "FAIL") "Any FAIL dominates aggregation"

# Prove the Windows PowerShell timeout path returns and never performs an unbounded wait. This launches
# only a local sleeping PowerShell process; it cannot execute adb or mutate a device.
$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
$timeoutResult = Run-Command `
    -FilePath (Get-Command powershell.exe).Source `
    -CommandArgs @('-NoProfile', '-Command', 'Start-Sleep -Seconds 30') `
    -TimeoutSec 1 `
    -IgnoreExitCode
$stopwatch.Stop()
Assert-That ($timeoutResult.TimedOut) "Local timeout probe reports TimedOut"
Assert-That ($timeoutResult.ExitCode -eq 124) "Local timeout probe returns exit 124"
Assert-That ($stopwatch.Elapsed.TotalSeconds -lt 8.0) "Local timeout probe returns within bounded deadline"

if ($script:Failures.Count -gt 0) {
    Write-Host "FAILED $($script:Failures.Count) / $($script:PassCount + $script:Failures.Count) assertions"
    $script:Failures | ForEach-Object { Write-Host " - $_" }
    exit 1
}

Write-Host "PASSED $script:PassCount / $script:PassCount assertions"
exit 0
