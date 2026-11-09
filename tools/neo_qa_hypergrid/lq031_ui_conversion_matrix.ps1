param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [Parameter(Mandatory = $true)]
    [ValidateSet(
        "neolog-r210", "neolog-v210", "neolog-hevc",
        "xdr-r210", "xdr-v210", "xdr-hevc",
        "nivukx-r210", "nivukx-v210", "nivukx-hevc",
        "cinemadng"
    )]
    [string]$Scenario,

    [Parameter(Mandatory = $true)]
    [string]$ClipName,

    [string]$ArtifactRoot = "artifacts/device-qa/lq031-ui-matrix",

    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = "Continue"
$PackageName = "com.neoproc.neoproccamx"
$ActivityName = "$PackageName/.ui.MainActivity"
$ResolvedArtifactRoot = (Resolve-Path -LiteralPath $ArtifactRoot).Path
$ScenarioRoot = Join-Path $ResolvedArtifactRoot $Scenario
$RunRoot = Join-Path $ScenarioRoot (
    "run-{0}-{1}" -f (Get-Date -Format "yyyyMMdd-HHmmssfff"), $PID
)
$RemoteScratch = "/data/local/tmp/neo-qa-lq031-$Scenario"

if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    throw "ADB executable was not found: $AdbPath"
}
New-Item -ItemType Directory -Force -Path $ScenarioRoot | Out-Null
New-Item -ItemType Directory -Force -Path $RunRoot | Out-Null

function Invoke-Adb {
    param([Parameter(Mandatory = $true)][string[]]$AdbArguments)
    $output = & $AdbPath -s $Serial @AdbArguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "ADB failed (exit=$LASTEXITCODE): adb -s $Serial $($AdbArguments -join ' ')`n$($output -join "`n")"
    }
    return $output
}

function Save-Lines {
    param([string]$Path, [object[]]$Lines)
    @($Lines) | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Get-FocusedPackage {
    $window = Invoke-Adb -AdbArguments @("shell", "dumpsys", "window")
    $focus = @($window | Where-Object { $_ -match "mCurrentFocus=" }) | Select-Object -First 1
    if ($null -eq $focus) { return "" }
    $match = [regex]::Match([string]$focus, "u\d+\s+([^/\s}]+)")
    if ($match.Success) { return $match.Groups[1].Value }
    return ""
}

function Assert-AppFocused {
    $focused = Get-FocusedPackage
    if ($focused -ne $PackageName) {
        throw "Foreground ownership changed: expected=$PackageName actual=$focused"
    }
}

function Dump-Ui {
    param([string]$Label)
    Assert-AppFocused
    $remote = "$RemoteScratch-$Label.xml"
    Invoke-Adb -AdbArguments @("shell", "uiautomator", "dump", "--compressed", $remote) | Out-Null
    $local = Join-Path $RunRoot "$Label.xml"
    Invoke-Adb -AdbArguments @("pull", $remote, $local) | Out-Null
    Invoke-Adb -AdbArguments @("shell", "rm", "-f", $remote) | Out-Null
    if (-not (Test-Path -LiteralPath $local -PathType Leaf)) {
        throw "UI hierarchy was not retained for $Label"
    }
    return $local
}

function Find-ExactNode {
    param([string]$UiPath, [string]$Value)
    [xml]$document = Get-Content -Raw -Encoding UTF8 -LiteralPath $UiPath
    $node = $document.SelectNodes("//node") |
        Where-Object {
            $_.GetAttribute("text") -eq $Value -or
            $_.GetAttribute("content-desc") -eq $Value
        } |
        Select-Object -First 1
    if ($null -eq $node) {
        throw "UI target '$Value' was not present in $UiPath"
    }
    return $node
}

function Get-Bounds {
    param($Node)
    $match = [regex]::Match(
        $Node.GetAttribute("bounds"),
        "\[(\d+),(\d+)\]\[(\d+),(\d+)\]"
    )
    if (-not $match.Success) { throw "Invalid UI-node bounds" }
    return @(
        [int]$match.Groups[1].Value,
        [int]$match.Groups[2].Value,
        [int]$match.Groups[3].Value,
        [int]$match.Groups[4].Value
    )
}

function Tap-ExactNode {
    param([string]$UiPath, [string]$Value)
    Assert-AppFocused
    $bounds = Get-Bounds (Find-ExactNode $UiPath $Value)
    $x = [int](($bounds[0] + $bounds[2]) / 2)
    $y = [int](($bounds[1] + $bounds[3]) / 2)
    Invoke-Adb -AdbArguments @("shell", "input", "tap", "$x", "$y") | Out-Null
    Start-Sleep -Milliseconds 650
    return "$x,$y"
}

function Swipe-ScrollableUp {
    param([string]$UiPath)
    Assert-AppFocused
    [xml]$document = Get-Content -Raw -Encoding UTF8 -LiteralPath $UiPath
    $node = $document.SelectNodes("//node") |
        Where-Object { $_.GetAttribute("scrollable") -eq "true" } |
        Select-Object -First 1
    if ($null -eq $node) { throw "No scrollable UI node in $UiPath" }
    $bounds = Get-Bounds $node
    $x = [int](($bounds[0] + $bounds[2]) / 2)
    $height = $bounds[3] - $bounds[1]
    $startY = [int]($bounds[1] + 0.82 * $height)
    $endY = [int]($bounds[1] + 0.20 * $height)
    Invoke-Adb -AdbArguments @(
        "shell", "input", "swipe", "$x", "$startY", "$x", "$endY", "550"
    ) | Out-Null
    Start-Sleep -Milliseconds 750
}

function Get-MediaInventory {
    $video = Invoke-Adb -AdbArguments @(
        "shell", "content", "query",
        "--uri", "content://media/external/video/media",
        "--projection", "_id:_display_name:_size:date_added:relative_path:mime_type:duration:is_pending"
    )
    $files = Invoke-Adb -AdbArguments @(
        "shell", "content", "query",
        "--uri", "content://media/external/file",
        "--projection", "_id:_display_name:_size:date_added:relative_path:mime_type:is_pending"
    )
    return @($video) + @($files)
}

function Get-UniqueMediaIds {
    param([object[]]$Rows)
    return @(
        $Rows |
            ForEach-Object {
                $match = [regex]::Match([string]$_, "(?:^|[, ])_id=(\d+)")
                if ($match.Success) { $match.Groups[1].Value }
            } |
            Where-Object { $_ } |
            Select-Object -Unique
    )
}

$scenarioTable = @{
    "neolog-r210" = @{
        Action = "Convert to NeoLog LOG (.MOV)"; Look = "N-Log Cinema";
        Format = "4:4:4 master (r210)"; Start = "Start conversion";
        OutputToken = "_neolog_444"; Certificate = $true; Lut = $true; Dng = $false
    }
    "neolog-v210" = @{
        Action = "Convert to NeoLog LOG (.MOV)"; Look = "N-Log Cinema";
        Format = "4:2:2 compat (v210)"; Start = "Start conversion";
        OutputToken = "_neolog_v210"; Certificate = $true; Lut = $true; Dng = $false
    }
    "neolog-hevc" = @{
        Action = "Convert to NeoLog LOG (.MOV)"; Look = "N-Log Cinema";
        Format = "HEVC Main10 10-bit 4:2:0"; Start = "Start conversion";
        OutputToken = "_neolog_hevc_main10_420"; Certificate = $true; Lut = $true; Dng = $false
    }
    "xdr-r210" = @{
        Action = "Convert to XDR10+ (PQ 10-bit)"; Look = $null;
        Format = "10-bit RGB 4:4:4 master (r210)"; Start = "Start conversion";
        OutputToken = "_ndr10_plus_444"; Certificate = $true; Lut = $false; Dng = $false
    }
    "xdr-v210" = @{
        Action = "Convert to XDR10+ (PQ 10-bit)"; Look = $null;
        Format = "10-bit 4:2:2 compat (v210)"; Start = "Start conversion";
        OutputToken = "_ndr10_plus_v210"; Certificate = $true; Lut = $false; Dng = $false
    }
    "xdr-hevc" = @{
        Action = "Convert to XDR10+ (PQ 10-bit)"; Look = $null;
        Format = "HEVC Main10 10-bit 4:2:0"; Start = "Start conversion";
        OutputToken = "_ndr10_plus_hevc_main10_420"; Certificate = $true; Lut = $false; Dng = $false
    }
    "nivukx-r210" = @{
        Action = "Convert to NIVUKX Vision (PQ 10-bit)"; Look = $null;
        Format = "10-bit RGB 4:4:4 master (r210)"; Start = "Start conversion";
        OutputToken = "_nivukx_vision_444"; Certificate = $true; Lut = $false; Dng = $false
    }
    "nivukx-v210" = @{
        Action = "Convert to NIVUKX Vision (PQ 10-bit)"; Look = $null;
        Format = "10-bit 4:2:2 compat (v210)"; Start = "Start conversion";
        OutputToken = "_nivukx_vision_v210"; Certificate = $true; Lut = $false; Dng = $false
    }
    "nivukx-hevc" = @{
        Action = "Convert to NIVUKX Vision (PQ 10-bit)"; Look = $null;
        Format = "HEVC Main10 10-bit 4:2:0"; Start = "Start conversion";
        OutputToken = "_nivukx_vision_hevc_main10_420"; Certificate = $true; Lut = $false; Dng = $false
    }
    "cinemadng" = @{
        Action = "Export as CinemaDNG sequence"; Look = $null;
        Format = $null; Start = "Start export";
        OutputToken = "_CinemaDNG"; Certificate = $false; Lut = $false; Dng = $true
    }
}
$spec = $scenarioTable[$Scenario]

$report = [ordered]@{
    schema = "neo-qa-lq031-production-ui-v1"
    scenario = $Scenario
    serial = $Serial
    clip = $ClipName
    status = "FAIL"
    taps = @()
    newMediaRows = @()
    startedAt = (Get-Date).ToString("o")
}

try {
    Invoke-Adb -AdbArguments @("shell", "input", "keyevent", "224") | Out-Null
    Invoke-Adb -AdbArguments @("shell", "wm", "dismiss-keyguard") | Out-Null
    Invoke-Adb -AdbArguments @("logcat", "-c") | Out-Null
    $before = Get-MediaInventory
    Save-Lines (Join-Path $RunRoot "media-before.txt") $before

    Invoke-Adb -AdbArguments @("shell", "am", "force-stop", $PackageName) | Out-Null
    Invoke-Adb -AdbArguments @(
        "shell", "am", "start", "-W", "-f", "0x10008000", "-n", $ActivityName
    ) | Out-Null
    $focusDeadline = [DateTime]::UtcNow.AddSeconds(12)
    do {
        Start-Sleep -Milliseconds 250
        $focused = Get-FocusedPackage
    } while ($focused -ne $PackageName -and [DateTime]::UtcNow -lt $focusDeadline)
    Assert-AppFocused
    $appPid = (@(Invoke-Adb -AdbArguments @("shell", "pidof", "-s", $PackageName))[0]).Trim()
    if ($appPid -notmatch "^\d+$") { throw "Unable to resolve the target app PID" }
    $report.appPid = [int]$appPid

    $ui = Dump-Ui "01-home"
    $report.taps += "settings=" + (Tap-ExactNode $ui "Camera settings")
    $ui = Dump-Ui "02-settings"
    $clipNode = $null
    for ($attempt = 1; $attempt -le 5; $attempt++) {
        try {
            $clipNode = Find-ExactNode $ui $ClipName
            break
        } catch {
            Swipe-ScrollableUp $ui
            $ui = Dump-Ui "02-settings-scroll-$attempt"
        }
    }
    if ($null -eq $clipNode) { throw "RAW fixture row was not reachable" }
    $bounds = Get-Bounds $clipNode
    $clipX = [int](($bounds[0] + $bounds[2]) / 2)
    $clipY = [int](($bounds[1] + $bounds[3]) / 2)
    Invoke-Adb -AdbArguments @("shell", "input", "tap", "$clipX", "$clipY") | Out-Null
    Start-Sleep -Milliseconds 650
    $report.taps += "clip=$clipX,$clipY"

    $ui = Dump-Ui "03-actions"
    $report.taps += "action=" + (Tap-ExactNode $ui $spec.Action)

    if ($null -ne $spec.Look) {
        $ui = Dump-Ui "04-look"
        $report.taps += "look=" + (Tap-ExactNode $ui $spec.Look)
    }
    if ($null -ne $spec.Format) {
        $ui = Dump-Ui "05-format"
        $report.taps += "format=" + (Tap-ExactNode $ui $spec.Format)
    }

    $ui = Dump-Ui "06-confirm"
    $report.taps += "start=" + (Tap-ExactNode $ui $spec.Start)

    $completed = $false
    for ($poll = 1; $poll -le 120; $poll++) {
        Start-Sleep -Seconds 1
        Assert-AppFocused
        $ui = Dump-Ui ("07-wait-{0:D3}" -f $poll)
        [xml]$document = Get-Content -Raw -Encoding UTF8 -LiteralPath $ui
        $texts = @($document.SelectNodes("//node") | ForEach-Object { $_.GetAttribute("text") })
        $busy = @($texts | Where-Object { $_ -match "^(Converting|Exporting|Developing)" }).Count -gt 0
        if (-not $busy -and $texts -contains "Camera Settings") {
            $completed = $true
            break
        }
    }
    if (-not $completed) { throw "Conversion did not return to Settings before timeout" }

    $after = Get-MediaInventory
    Save-Lines (Join-Path $RunRoot "media-after.txt") $after
    $newRows = @($after | Where-Object { $before -notcontains $_ })
    Save-Lines (Join-Path $RunRoot "media-new.txt") $newRows
    $report.newMediaRows = @($newRows)

    $logcat = Invoke-Adb -AdbArguments @(
        "logcat", "-d", "-v", "threadtime", "--pid", $appPid
    )
    Save-Lines (Join-Path $RunRoot "logcat.txt") $logcat
    Invoke-Adb -AdbArguments @("shell", "screencap", "-p", "$RemoteScratch-result.png") | Out-Null
    Invoke-Adb -AdbArguments @(
        "pull", "$RemoteScratch-result.png", (Join-Path $RunRoot "result.png")
    ) | Out-Null
    Invoke-Adb -AdbArguments @("shell", "rm", "-f", "$RemoteScratch-result.png") | Out-Null

    $matchingRows = @($newRows | Where-Object { $_ -like "*$($spec.OutputToken)*" })
    if ($spec.Dng) {
        $dngRows = @($matchingRows | Where-Object { $_ -match "frame_\d+\.dng" })
        if ($dngRows.Count -lt 1) { throw "No newly published CinemaDNG frame was found" }
    } else {
        $movRows = @($matchingRows | Where-Object { $_ -match "\.mov," })
        $movIds = Get-UniqueMediaIds $movRows
        if ($movIds.Count -ne 1) { throw "Expected one new MOV, found $($movIds.Count)" }
        if ($spec.Certificate) {
            $certificateRows = @($matchingRows | Where-Object { $_ -match "elite-conversion-certificate" })
            $certificateIds = Get-UniqueMediaIds $certificateRows
            if ($certificateIds.Count -ne 1) { throw "Expected one new quality certificate" }
        }
        if ($spec.Lut) {
            $lutRows = @($matchingRows | Where-Object { $_ -match "NeoLog_to_Rec709" })
            $lutIds = Get-UniqueMediaIds $lutRows
            if ($lutIds.Count -ne 1) { throw "Expected one new NeoLog viewing LUT" }
        }
    }
    $failureLines = @($logcat | Where-Object {
        $_ -match "(?i)(conversion|convert|export).*(failed|exception)" -or
        $_ -match "(?i)FATAL EXCEPTION"
    })
    if ($failureLines.Count -gt 0) {
        Save-Lines (Join-Path $RunRoot "failure-lines.txt") $failureLines
        throw "Conversion failure text was present in logcat"
    }

    $report.status = "PASS"
    $report.completedAt = (Get-Date).ToString("o")
} catch {
    $report.failure = $_.Exception.Message
    $report.completedAt = (Get-Date).ToString("o")
    try {
        Save-Lines (Join-Path $RunRoot "failure-logcat.txt") (
            Invoke-Adb -AdbArguments @("logcat", "-d", "-v", "threadtime")
        )
    } catch { }
} finally {
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $RunRoot "result.json") -Encoding UTF8
    Split-Path -Leaf $RunRoot | Set-Content -LiteralPath (Join-Path $ScenarioRoot "LATEST") -Encoding ASCII
}

$report | ConvertTo-Json -Depth 8
if ($report.status -ne "PASS") { exit 1 }
