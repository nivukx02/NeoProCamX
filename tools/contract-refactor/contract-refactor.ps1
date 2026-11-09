param(
    [ValidateSet("Analyze", "Checkpoint", "Verify", "Run", "SelfTest")]
    [string]$Mode = "Analyze",
    [string]$Manifest = "tools/contract-refactor/camera-contracts.json",
    [string]$Checkpoint = "",
    [string]$MutationScript = "",
    [string]$ReportDirectory = "build/contract-refactor"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-WorkspacePath {
    param([string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $script:WorkspaceRoot $Path))
}

function Get-RelativeWorkspacePath {
    param([string]$Path)
    return [System.IO.Path]::GetRelativePath(
        $script:WorkspaceRoot,
        [System.IO.Path]::GetFullPath($Path)
    ).Replace('\', '/')
}

function Assert-WorkspacePath {
    param([string]$Path, [string]$Label)
    $resolved = Resolve-WorkspacePath $Path
    $rootWithSeparator = $script:WorkspaceRoot.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if ($resolved -ne $script:WorkspaceRoot -and
        -not $resolved.StartsWith($rootWithSeparator, [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Label escapes the workspace: $Path"
    }
    return $resolved
}

function Get-OptionalProperty {
    param([object]$Object, [string]$Name, $Default = $null)
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) { return $Default }
    return $property.Value
}

function Get-NormalizedConfigPaths {
    param([string]$PropertyName)
    $values = @(Get-OptionalProperty $script:Config $PropertyName @())
    return @($values | ForEach-Object { ([string]$_).Replace('\', '/') })
}

function Get-FileHashValue {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $null }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-LineCount {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return 0 }
    $count = 0
    foreach ($line in [System.IO.File]::ReadLines($Path)) { $count++ }
    return $count
}

function Add-JournalEvent {
    param(
        [string]$Phase,
        [ValidateSet("started", "passed", "failed", "info", "restored")]
        [string]$Status,
        [object]$Details = $null,
        [long]$DurationMs = 0
    )
    [void]$script:JournalEvents.Add([pscustomobject]@{
        timestamp = [DateTimeOffset]::Now.ToString("O")
        phase = $Phase
        status = $Status
        durationMs = $DurationMs
        details = $Details
    })
}

function Write-TransactionJournal {
    param([string]$Status, [string]$ErrorMessage = "")
    $journalRoot = Join-Path (Resolve-WorkspacePath $ReportDirectory) "transactions"
    New-Item -ItemType Directory -Path $journalRoot -Force | Out-Null
    $endedAt = [DateTimeOffset]::Now
    $journal = [pscustomobject]@{
        schemaVersion = 2
        engineVersion = "2.0-contract-graph"
        invocationId = $script:InvocationId
        mode = $Mode
        manifest = Get-RelativeWorkspacePath $script:ManifestPath
        manifestSha256 = Get-FileHashValue $script:ManifestPath
        transaction = $script:Config.name
        status = $Status
        startedAt = $script:RunStartedAt.ToString("O")
        endedAt = $endedAt.ToString("O")
        durationMs = [long]($endedAt - $script:RunStartedAt).TotalMilliseconds
        checkpoint = $script:ActiveCheckpoint
        error = $ErrorMessage
        events = @($script:JournalEvents)
    }
    $journalPath = Join-Path $journalRoot "$($script:InvocationId).json"
    $journal | ConvertTo-Json -Depth 12 |
        Set-Content -LiteralPath $journalPath -Encoding UTF8
    Write-Host "Journal:   $(Get-RelativeWorkspacePath $journalPath)"
}

function Assert-Manifest {
    $schemaVersion = [int](Get-OptionalProperty $script:Config "schemaVersion" 0)
    if ($schemaVersion -lt 1 -or $schemaVersion -gt 2) {
        throw "Unsupported manifest schemaVersion=$schemaVersion; expected 1 or 2."
    }
    if ([string]::IsNullOrWhiteSpace([string]$script:Config.name)) {
        throw "Manifest name must be non-empty."
    }
    [object[]]$sourceRoots = @($script:Config.sourceRoots)
    [object[]]$sourceExtensions = @($script:Config.sourceExtensions)
    [string[]]$allowedFiles = @(Get-NormalizedConfigPaths "allowedFiles")
    if ($sourceRoots.Count -eq 0 -or $sourceExtensions.Count -eq 0 -or $allowedFiles.Count -eq 0) {
        throw "Manifest requires sourceRoots, sourceExtensions, and allowedFiles."
    }
    foreach ($path in @($sourceRoots) + @($allowedFiles) + @(Get-NormalizedConfigPaths "protectedFiles")) {
        [void](Assert-WorkspacePath ([string]$path) "Manifest path")
    }
    if (@($allowedFiles | Sort-Object -Unique).Count -ne $allowedFiles.Count) {
        throw "Manifest allowedFiles contains duplicates."
    }
    $allowed = [System.Collections.Generic.HashSet[string]]::new(
        [string[]]$allowedFiles,
        [StringComparer]::OrdinalIgnoreCase
    )
    foreach ($protected in Get-NormalizedConfigPaths "protectedFiles") {
        if ($allowed.Contains($protected)) {
            throw "A file cannot be both mutable and quality-protected: $protected"
        }
        if (-not (Test-Path -LiteralPath (Resolve-WorkspacePath $protected) -PathType Leaf)) {
            throw "Quality-protected file is missing: $protected"
        }
    }
    foreach ($contractGroup in @("requiredContracts", "forbiddenContracts")) {
        foreach ($contract in @(Get-OptionalProperty $script:Config $contractGroup @())) {
            [void](Assert-WorkspacePath ([string]$contract.file) "$contractGroup file")
            foreach ($pattern in @($contract.patterns)) {
                try { [void][regex]::new([string]$pattern) } catch {
                    throw "Invalid regex in $contractGroup for $($contract.file): $pattern"
                }
            }
        }
    }
    foreach ($gateGroup in @("preflightGates", "gates")) {
        foreach ($gate in @(Get-OptionalProperty $script:Config $gateGroup @())) {
            if ([string]::IsNullOrWhiteSpace([string]$gate.name)) {
                throw "$gateGroup contains a gate without a name."
            }
            $executable = Assert-WorkspacePath ([string]$gate.executable) "$gateGroup executable"
            if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) {
                throw "$gateGroup executable is missing: $($gate.executable)"
            }
        }
    }
    Add-JournalEvent "manifest" "passed" @{ schemaVersion = $schemaVersion }
}

function Get-SourceInventory {
    param([switch]$Refresh)
    if (-not $Refresh -and $null -ne $script:InventoryCache) {
        return @($script:InventoryCache)
    }
    $extensions = [System.Collections.Generic.HashSet[string]]::new(
        [string[]]$script:Config.sourceExtensions,
        [StringComparer]::OrdinalIgnoreCase
    )
    $excludedRegex = [string](Get-OptionalProperty $script:Config "excludedPathRegex" "")
    $rows = foreach ($root in $script:Config.sourceRoots) {
        $absoluteRoot = Resolve-WorkspacePath ([string]$root)
        if (-not (Test-Path -LiteralPath $absoluteRoot -PathType Container)) { continue }
        foreach ($file in Get-ChildItem -LiteralPath $absoluteRoot -Recurse -File) {
            if (-not $extensions.Contains($file.Extension)) { continue }
            $relative = Get-RelativeWorkspacePath $file.FullName
            if (-not [string]::IsNullOrWhiteSpace($excludedRegex) -and $relative -match $excludedRegex) {
                continue
            }
            [pscustomobject]@{
                file = $relative
                lines = Get-LineCount $file.FullName
                bytes = $file.Length
                sha256 = Get-FileHashValue $file.FullName
                generated = $relative -match '(^|/)generated/'
            }
        }
    }
    $script:InventoryCache = @($rows | Sort-Object lines -Descending)
    return @($script:InventoryCache)
}

function Get-StateInventory {
    param([switch]$Refresh)
    $inventory = @(Get-SourceInventory -Refresh:$Refresh)
    $known = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($entry in $inventory) { [void]$known.Add($entry.file) }
    $extra = foreach ($relative in Get-NormalizedConfigPaths "allowedFiles") {
        if ($known.Contains($relative)) { continue }
        $path = Resolve-WorkspacePath $relative
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            [pscustomobject]@{
                file = $relative
                lines = Get-LineCount $path
                bytes = (Get-Item -LiteralPath $path).Length
                sha256 = Get-FileHashValue $path
                generated = $false
            }
        }
    }
    return @($inventory + @($extra))
}

function Get-SourceIntelligence {
    $inventory = @(Get-SourceInventory)
    $records = [System.Collections.Generic.List[object]]::new()
    foreach ($entry in $inventory) {
        if ($entry.generated) { continue }
        $path = Resolve-WorkspacePath $entry.file
        $extension = [IO.Path]::GetExtension($entry.file).ToLowerInvariant()
        $content = [IO.File]::ReadAllText($path)
        $packageMatch = [regex]::Match($content, '(?m)^\s*package\s+([A-Za-z0-9_.]+)')
        $packageName = if ($packageMatch.Success) { $packageMatch.Groups[1].Value } else { "" }
        $symbol = [IO.Path]::GetFileNameWithoutExtension($entry.file)
        $qualifiedSymbol = if ($packageName) { "$packageName.$symbol" } else { $symbol }
        $imports = @([regex]::Matches($content, '(?m)^\s*import\s+([A-Za-z0-9_.]+)') |
            ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
        $includes = @([regex]::Matches($content, '(?m)^\s*#include\s+[<"]([^>"]+)[>"]') |
            ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
        $functionPattern = if ($extension -in @('.kt', '.java')) {
            '(?m)^\s*(?:(?:private|internal|public|protected|override|suspend|inline|operator|tailrec|open|final|static)\s+)*fun\s+|(?m)^\s*(?:(?:private|public|protected|static|final|synchronized)\s+)+[A-Za-z0-9_<>,?\[\].]+\s+[A-Za-z0-9_]+\s*\('
        } else {
            '(?m)^\s*[A-Za-z_][A-Za-z0-9_:<>,*&\s]+\s+[A-Za-z_~][A-Za-z0-9_:~]*\s*\([^;]*\)\s*(?:const\s*)?\{'
        }
        $functionCount = [regex]::Matches($content, $functionPattern).Count
        $typeCount = [regex]::Matches($content, '(?m)^\s*(?:(?:data|sealed|internal|private|public|open|abstract)\s+)*(?:class|object|interface|enum\s+class)\s+').Count
        $branchCount = [regex]::Matches($content, '\b(?:if|when|for|while|catch)\s*[({]').Count
        [void]$records.Add([pscustomobject]@{
            file = $entry.file
            lines = $entry.lines
            extension = $extension
            qualifiedSymbol = $qualifiedSymbol
            imports = $imports
            includes = $includes
            functions = $functionCount
            types = $typeCount
            branchSignals = $branchCount
            outgoingDependencies = @()
            fanIn = 0
            hotspotScore = 0
        })
    }

    $symbolIndex = @{}
    $fileNameIndex = @{}
    foreach ($record in $records) {
        $symbolIndex[$record.qualifiedSymbol] = $record.file
        $fileNameIndex[[IO.Path]::GetFileName($record.file)] = $record.file
    }
    $edges = [System.Collections.Generic.List[object]]::new()
    $fanIn = @{}
    foreach ($record in $records) {
        $targets = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        foreach ($import in $record.imports) {
            if ($symbolIndex.ContainsKey($import)) { [void]$targets.Add($symbolIndex[$import]) }
        }
        foreach ($include in $record.includes) {
            $includeName = [IO.Path]::GetFileName($include)
            if ($fileNameIndex.ContainsKey($includeName)) { [void]$targets.Add($fileNameIndex[$includeName]) }
        }
        [void]$targets.Remove($record.file)
        $record.outgoingDependencies = @($targets | Sort-Object)
        foreach ($target in $record.outgoingDependencies) {
            [void]$edges.Add([pscustomobject]@{ from = $record.file; to = $target })
            if (-not $fanIn.ContainsKey($target)) { $fanIn[$target] = 0 }
            $fanIn[$target]++
        }
    }
    foreach ($record in $records) {
        $record.fanIn = if ($fanIn.ContainsKey($record.file)) { [int]$fanIn[$record.file] } else { 0 }
        $record.hotspotScore = [math]::Round(
            $record.lines +
                ($record.functions * 18) +
                ($record.branchSignals * 2) +
                ($record.fanIn * 4) +
                ($record.outgoingDependencies.Count * 3),
            0
        )
    }
    $candidates = @($records |
        Where-Object {
            $_.file -match '/main/' -and
            $_.file -notmatch '(^|/)generated/' -and
            ($_.lines -ge 800 -or $_.functions -ge 40)
        } |
        Sort-Object hotspotScore -Descending |
        Select-Object -First 30 |
        ForEach-Object {
            $strategy = if ($_.extension -eq '.cpp' -or $_.extension -eq '.c') {
                "Split native subsystems behind the existing stable JNI/C ABI"
            } elseif ($_.functions -ge 80) {
                "Extract cohesive coordinators and pure policies; preserve the current facade"
            } elseif ($_.fanIn -ge 10) {
                "Keep the public contract stable and extract private implementation services"
            } else {
                "Extract one bounded responsibility with contract and golden-output gates"
            }
            [pscustomobject]@{
                rankScore = $_.hotspotScore
                file = $_.file
                lines = $_.lines
                functions = $_.functions
                branchSignals = $_.branchSignals
                fanIn = $_.fanIn
                outgoingDependencies = $_.outgoingDependencies.Count
                recommendedStrategy = $strategy
            }
        })
    return [pscustomobject]@{
        nodes = @($records | Sort-Object file)
        edges = @($edges | Sort-Object from, to)
        candidates = $candidates
    }
}

function Write-AnalysisReport {
    $inventory = @(Get-SourceInventory -Refresh)
    $intelligence = Get-SourceIntelligence
    $reportRoot = Resolve-WorkspacePath $ReportDirectory
    New-Item -ItemType Directory -Path $reportRoot -Force | Out-Null
    $inventoryPath = Join-Path $reportRoot "source-inventory.json"
    $graphPath = Join-Path $reportRoot "dependency-graph.json"
    $candidatesPath = Join-Path $reportRoot "extraction-candidates.json"
    $markdownPath = Join-Path $reportRoot "extraction-plan.md"
    $inventory | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $inventoryPath -Encoding UTF8
    [pscustomobject]@{ nodes = $intelligence.nodes; edges = $intelligence.edges } |
        ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $graphPath -Encoding UTF8
    $intelligence.candidates | ConvertTo-Json -Depth 6 |
        Set-Content -LiteralPath $candidatesPath -Encoding UTF8

    $markdown = [System.Collections.Generic.List[string]]::new()
    $markdown.Add("# Contract-Locked Extraction Plan")
    $markdown.Add("")
    $markdown.Add("Generated: $([DateTimeOffset]::Now.ToString('O'))")
    $markdown.Add("")
    $markdown.Add("Quality-protected files: $(@(Get-NormalizedConfigPaths 'protectedFiles').Count)")
    $markdown.Add("")
    $markdown.Add("| Rank score | Lines | Functions | Fan-in | File | Strategy |")
    $markdown.Add("| ---: | ---: | ---: | ---: | --- | --- |")
    foreach ($candidate in $intelligence.candidates) {
        $strategy = $candidate.recommendedStrategy.Replace('|', '\|')
        $markdown.Add("| $($candidate.rankScore) | $($candidate.lines) | $($candidate.functions) | $($candidate.fanIn) | ``$($candidate.file)`` | $strategy |")
    }
    $markdown | Set-Content -LiteralPath $markdownPath -Encoding UTF8
    Add-JournalEvent "analysis" "passed" @{
        sourceFiles = $inventory.Count
        dependencyEdges = $intelligence.edges.Count
        extractionCandidates = $intelligence.candidates.Count
    }
    Write-Host "Inventory:  $(Get-RelativeWorkspacePath $inventoryPath)"
    Write-Host "Graph:      $(Get-RelativeWorkspacePath $graphPath)"
    Write-Host "Candidates: $(Get-RelativeWorkspacePath $candidatesPath)"
    Write-Host "Plan:       $(Get-RelativeWorkspacePath $markdownPath)"
}

function New-Checkpoint {
    $checkpointRoot = if ([string]::IsNullOrWhiteSpace($Checkpoint)) {
        Resolve-WorkspacePath (".contract-refactor/checkpoints/" +
            [DateTimeOffset]::Now.ToString("yyyyMMdd-HHmmss-fff"))
    } else {
        Resolve-WorkspacePath $Checkpoint
    }
    New-Item -ItemType Directory -Path $checkpointRoot -Force | Out-Null
    $filesRoot = Join-Path $checkpointRoot "files"
    New-Item -ItemType Directory -Path $filesRoot -Force | Out-Null

    $inventory = @(Get-StateInventory -Refresh)
    $metadata = @($inventory | ForEach-Object {
        [pscustomobject]@{
            file = $_.file
            existed = $true
            lines = $_.lines
            bytes = $_.bytes
            sha256 = $_.sha256
        }
    })
    $inventoryByPath = @{}
    foreach ($entry in $inventory) { $inventoryByPath[$entry.file] = $entry }
    $snapshotPaths = @()
    $snapshotPaths += @(Get-NormalizedConfigPaths "allowedFiles")
    $snapshotPaths += @(Get-NormalizedConfigPaths "protectedFiles")
    $snapshotPaths = @($snapshotPaths | Sort-Object -Unique)
    foreach ($relative in $snapshotPaths) {
        $source = Resolve-WorkspacePath $relative
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { continue }
        $destination = Join-Path $filesRoot ($relative.Replace('/', [IO.Path]::DirectorySeparatorChar))
        New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
        Copy-Item -LiteralPath $source -Destination $destination -Force
    }
    $protectedMetadata = foreach ($relative in Get-NormalizedConfigPaths "protectedFiles") {
        $path = Resolve-WorkspacePath $relative
        [pscustomobject]@{
            file = $relative
            lines = Get-LineCount $path
            bytes = (Get-Item -LiteralPath $path).Length
            sha256 = Get-FileHashValue $path
        }
    }
    $state = [pscustomobject]@{
        schemaVersion = 2
        engineVersion = "2.0-contract-graph"
        transaction = $script:Config.name
        manifest = Get-RelativeWorkspacePath $script:ManifestPath
        manifestSha256 = Get-FileHashValue $script:ManifestPath
        createdAt = [DateTimeOffset]::Now.ToString("O")
        allowedFiles = Get-NormalizedConfigPaths "allowedFiles"
        protectedFiles = @($protectedMetadata)
        files = $metadata
    }
    $state | ConvertTo-Json -Depth 10 |
        Set-Content -LiteralPath (Join-Path $checkpointRoot "checkpoint.json") -Encoding UTF8
    $script:ActiveCheckpoint = Get-RelativeWorkspacePath $checkpointRoot
    Add-JournalEvent "checkpoint" "passed" @{
        path = $script:ActiveCheckpoint
        hashedFiles = $metadata.Count
        copiedTransactionFiles = @(Get-NormalizedConfigPaths "allowedFiles").Count
        copiedProtectedFiles = @(Get-NormalizedConfigPaths "protectedFiles").Count
        protectedFiles = @($protectedMetadata).Count
    }
    Write-Host "Checkpoint: $(Get-RelativeWorkspacePath $checkpointRoot)"
    return $checkpointRoot
}

function Read-CheckpointState {
    param([string]$CheckpointRoot)
    $statePath = Join-Path $CheckpointRoot "checkpoint.json"
    if (-not (Test-Path -LiteralPath $statePath -PathType Leaf)) {
        throw "Missing checkpoint metadata: $statePath"
    }
    return Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
}

function Get-AllowedPathSet {
    return [System.Collections.Generic.HashSet[string]]::new(
        [string[]](Get-NormalizedConfigPaths "allowedFiles"),
        [StringComparer]::OrdinalIgnoreCase
    )
}

function Get-TransactionDelta {
    param([string]$CheckpointRoot)
    $state = Read-CheckpointState $CheckpointRoot
    $before = @{}
    foreach ($entry in $state.files) { $before[$entry.file] = $entry }
    $after = @{}
    foreach ($entry in Get-StateInventory -Refresh) { $after[$entry.file] = $entry }
    $changed = [System.Collections.Generic.List[object]]::new()
    foreach ($path in @($before.Keys + $after.Keys | Sort-Object -Unique)) {
        $beforeEntry = $before[$path]
        $afterEntry = $after[$path]
        $beforeHash = if ($null -eq $beforeEntry) { $null } else { $beforeEntry.sha256 }
        $afterHash = if ($null -eq $afterEntry) { $null } else { $afterEntry.sha256 }
        if ($beforeHash -ne $afterHash) {
            [void]$changed.Add([pscustomobject]@{
                file = $path
                kind = if ($null -eq $beforeEntry) { "added" } elseif ($null -eq $afterEntry) { "removed" } else { "modified" }
                beforeLines = if ($null -eq $beforeEntry) { 0 } else { [int]$beforeEntry.lines }
                afterLines = if ($null -eq $afterEntry) { 0 } else { [int]$afterEntry.lines }
            })
        }
    }
    return [pscustomobject]@{
        state = $state
        changed = @($changed)
        added = @($changed | Where-Object kind -eq "added")
        removed = @($changed | Where-Object kind -eq "removed")
        modified = @($changed | Where-Object kind -eq "modified")
    }
}

function Assert-TransactionScope {
    param([string]$CheckpointRoot)
    $delta = Get-TransactionDelta $CheckpointRoot
    $allowed = Get-AllowedPathSet
    foreach ($change in $delta.changed) {
        if (-not $allowed.Contains($change.file)) {
            throw "Refactor changed undeclared source file: $($change.file)"
        }
    }
    $limits = Get-OptionalProperty $script:Config "transactionLimits" $null
    if ($null -ne $limits) {
        $maxChanged = [int](Get-OptionalProperty $limits "maximumChangedFiles" [int]::MaxValue)
        $maxAdded = [int](Get-OptionalProperty $limits "maximumAddedFiles" [int]::MaxValue)
        $maxRemoved = [int](Get-OptionalProperty $limits "maximumRemovedFiles" 0)
        if ($delta.changed.Count -gt $maxChanged) { throw "Transaction changed $($delta.changed.Count) files; maximum is $maxChanged." }
        if ($delta.added.Count -gt $maxAdded) { throw "Transaction added $($delta.added.Count) files; maximum is $maxAdded." }
        if ($delta.removed.Count -gt $maxRemoved) { throw "Transaction removed $($delta.removed.Count) files; maximum is $maxRemoved." }
    }
    Add-JournalEvent "transaction-scope" "passed" @{
        changed = $delta.changed.Count
        added = $delta.added.Count
        removed = $delta.removed.Count
    }
    Write-Host "Transaction scope: $($delta.changed.Count) declared source file(s) changed"
    return $delta
}

function Assert-ProtectedFiles {
    param([string]$CheckpointRoot)
    $state = Read-CheckpointState $CheckpointRoot
    $baseline = @{}
    foreach ($entry in @((Get-OptionalProperty $state "protectedFiles" @())) + @($state.files)) {
        if (-not $baseline.ContainsKey($entry.file)) { $baseline[$entry.file] = $entry.sha256 }
    }
    foreach ($relative in Get-NormalizedConfigPaths "protectedFiles") {
        if (-not $baseline.ContainsKey($relative)) {
            throw "Checkpoint lacks a quality fingerprint for protected file: $relative"
        }
        $currentHash = Get-FileHashValue (Resolve-WorkspacePath $relative)
        if ($currentHash -ne $baseline[$relative]) {
            throw "Quality-protected file changed during refactor: $relative"
        }
    }
    Add-JournalEvent "quality-fingerprints" "passed" @{
        protectedFiles = @(Get-NormalizedConfigPaths "protectedFiles").Count
    }
    Write-Host "Quality fingerprints: $(@(Get-NormalizedConfigPaths 'protectedFiles').Count) protected file(s) unchanged"
}

function Assert-LineRatchets {
    foreach ($ratchet in @(Get-OptionalProperty $script:Config "lineRatchets" @())) {
        $path = Resolve-WorkspacePath $ratchet.file
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Line-ratchet file is missing: $($ratchet.file)"
        }
        $lines = Get-LineCount $path
        if ($lines -gt [int]$ratchet.maximumLines) {
            throw "$($ratchet.file) has $lines lines; contract maximum is $($ratchet.maximumLines)"
        }
        Write-Host "Line ratchet: $($ratchet.file) = $lines/$($ratchet.maximumLines)"
    }
    Add-JournalEvent "line-ratchets" "passed"
}

function Assert-ReductionBudgets {
    param([object]$Delta)
    $before = @{}
    foreach ($entry in $Delta.state.files) { $before[$entry.file] = $entry }
    foreach ($budget in @(Get-OptionalProperty $script:Config "reductionBudgets" @())) {
        $hostRelativePath = ([string]$budget.hostFile).Replace('\', '/')
        if (-not $before.ContainsKey($hostRelativePath)) { throw "Reduction host missing from checkpoint: $hostRelativePath" }
        $currentPath = Resolve-WorkspacePath $hostRelativePath
        $currentLines = Get-LineCount $currentPath
        $reduction = [int]$before[$hostRelativePath].lines - $currentLines
        $minimum = [int](Get-OptionalProperty $budget "minimumHostLineReduction" 1)
        if ($reduction -lt $minimum) {
            throw "$hostRelativePath reduced by $reduction lines; required minimum is $minimum."
        }
        $maximumExtractedLines = [int](Get-OptionalProperty $budget "maximumExtractedFileLines" [int]::MaxValue)
        foreach ($extracted in @((Get-OptionalProperty $budget "extractedFiles" @()))) {
            $extractedPath = Resolve-WorkspacePath ([string]$extracted)
            if (-not (Test-Path -LiteralPath $extractedPath -PathType Leaf)) {
                throw "Required extracted file is missing: $extracted"
            }
            $extractedLines = Get-LineCount $extractedPath
            if ($extractedLines -gt $maximumExtractedLines) {
                throw "$extracted has $extractedLines lines; extracted-file maximum is $maximumExtractedLines."
            }
        }
        Write-Host "Reduction budget: $hostRelativePath reduced by $reduction line(s), minimum=$minimum"
    }
    Add-JournalEvent "reduction-budgets" "passed"
}

function Assert-SourceContracts {
    foreach ($contract in @(Get-OptionalProperty $script:Config "requiredContracts" @())) {
        $path = Resolve-WorkspacePath $contract.file
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Required contract file is missing: $($contract.file)"
        }
        $content = Get-Content -LiteralPath $path -Raw
        foreach ($pattern in @($contract.patterns)) {
            if ($content -notmatch $pattern) {
                throw "Required contract pattern missing from $($contract.file): $pattern"
            }
        }
    }
    foreach ($contract in @(Get-OptionalProperty $script:Config "forbiddenContracts" @())) {
        $path = Resolve-WorkspacePath $contract.file
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
        $content = Get-Content -LiteralPath $path -Raw
        foreach ($pattern in @($contract.patterns)) {
            if ($content -match $pattern) {
                throw "Forbidden contract pattern found in $($contract.file): $pattern"
            }
        }
    }
    Add-JournalEvent "source-contracts" "passed"
    Write-Host "Source contracts: passed"
}

function Invoke-Gate {
    param([object]$Gate, [string]$Phase = "verification-gate")
    $executable = Resolve-WorkspacePath $Gate.executable
    $arguments = @($Gate.arguments | ForEach-Object { [string]$_ })
    Write-Host "Gate: $($Gate.name)"
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $global:LASTEXITCODE = 0
    & $executable @arguments
    $exitCode = $LASTEXITCODE
    $timer.Stop()
    if ($exitCode -ne 0) {
        Add-JournalEvent $Phase "failed" @{ name = $Gate.name; exitCode = $exitCode } $timer.ElapsedMilliseconds
        throw "Gate '$($Gate.name)' failed with exit code $exitCode"
    }
    Add-JournalEvent $Phase "passed" @{ name = $Gate.name; exitCode = 0 } $timer.ElapsedMilliseconds
}

function Invoke-Preflight {
    param([string]$CheckpointRoot)
    Add-JournalEvent "preflight" "started"
    Assert-ProtectedFiles $CheckpointRoot
    Assert-SourceContracts
    foreach ($gate in @(Get-OptionalProperty $script:Config "preflightGates" @())) {
        Invoke-Gate $gate "preflight-gate"
    }
    Add-JournalEvent "preflight" "passed"
    Write-Host "Preflight baseline passed; mutation is authorized."
}

function Invoke-Verification {
    param([string]$CheckpointRoot = "")
    Add-JournalEvent "verification" "started"
    $delta = $null
    if (-not [string]::IsNullOrWhiteSpace($CheckpointRoot)) {
        $delta = Assert-TransactionScope $CheckpointRoot
        Assert-ProtectedFiles $CheckpointRoot
        Assert-ReductionBudgets $delta
    }
    Assert-LineRatchets
    Assert-SourceContracts
    foreach ($gate in @(Get-OptionalProperty $script:Config "gates" @())) { Invoke-Gate $gate }
    Add-JournalEvent "verification" "passed"
    Write-Host "Contract-locked refactor verification passed."
}

function Restore-AllowedFiles {
    param([string]$CheckpointRoot)
    $state = Read-CheckpointState $CheckpointRoot
    $before = @{}
    foreach ($entry in $state.files) { $before[$entry.file] = $entry }
    foreach ($entry in @(Get-OptionalProperty $state "protectedFiles" @())) {
        if (-not $before.ContainsKey($entry.file)) { $before[$entry.file] = $entry }
    }
    $restorePaths = @()
    $restorePaths += @(Get-NormalizedConfigPaths "allowedFiles")
    $restorePaths += @(Get-NormalizedConfigPaths "protectedFiles")
    $restorePaths = @($restorePaths | Sort-Object -Unique)
    foreach ($relative in $restorePaths) {
        $target = Resolve-WorkspacePath $relative
        if ($before.ContainsKey($relative)) {
            $backup = Join-Path (Join-Path $CheckpointRoot "files") `
                ($relative.Replace('/', [IO.Path]::DirectorySeparatorChar))
            if (-not (Test-Path -LiteralPath $backup -PathType Leaf)) {
                throw "Checkpoint cannot restore declared file; snapshot missing: $relative"
            }
            New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
            Copy-Item -LiteralPath $backup -Destination $target -Force
        } elseif (Test-Path -LiteralPath $target -PathType Leaf) {
            Remove-Item -LiteralPath $target -Force
        }
    }
    $script:InventoryCache = $null
    Add-JournalEvent "rollback" "restored" @{
        mutableFiles = @(Get-NormalizedConfigPaths "allowedFiles").Count
        protectedFiles = @(Get-NormalizedConfigPaths "protectedFiles").Count
    }
    Write-Warning "Verification failed; declared mutable and quality-protected files were restored from the checkpoint."
}

function Invoke-EngineSelfTest {
    $inventory = @(Get-SourceInventory -Refresh)
    if ($inventory.Count -lt 10) { throw "Self-test expected at least 10 source files." }
    if (@($inventory | Where-Object { $_.sha256 -notmatch '^[0-9a-f]{64}$' }).Count -ne 0) {
        throw "Self-test found a non-SHA256 source fingerprint."
    }
    $intelligence = Get-SourceIntelligence
    if ($intelligence.nodes.Count -eq 0 -or $intelligence.candidates.Count -eq 0) {
        throw "Self-test dependency graph or candidate ranking is empty."
    }
    Assert-LineRatchets
    Assert-SourceContracts
    Add-JournalEvent "engine-self-test" "passed" @{
        files = $inventory.Count
        edges = $intelligence.edges.Count
        candidates = $intelligence.candidates.Count
    }
    Write-Host "Engine self-test passed: files=$($inventory.Count) edges=$($intelligence.edges.Count) candidates=$($intelligence.candidates.Count)"
}

$script:WorkspaceRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "../.."))
$script:RunStartedAt = [DateTimeOffset]::Now
$script:InvocationId = $script:RunStartedAt.ToString("yyyyMMdd-HHmmss-fff")
$script:JournalEvents = [System.Collections.Generic.List[object]]::new()
$script:InventoryCache = $null
$script:ActiveCheckpoint = ""
$script:ManifestPath = Resolve-WorkspacePath $Manifest
if (-not (Test-Path -LiteralPath $script:ManifestPath -PathType Leaf)) {
    throw "Manifest not found: $($script:ManifestPath)"
}
$script:Config = Get-Content -LiteralPath $script:ManifestPath -Raw | ConvertFrom-Json

$runStatus = "passed"
$runError = ""
try {
    Assert-Manifest
    switch ($Mode) {
        "Analyze" { Write-AnalysisReport }
        "SelfTest" { Invoke-EngineSelfTest }
        "Checkpoint" { [void](New-Checkpoint) }
        "Verify" {
            $checkpointRoot = if ([string]::IsNullOrWhiteSpace($Checkpoint)) {
                ""
            } else {
                Resolve-WorkspacePath $Checkpoint
            }
            if ($checkpointRoot) { $script:ActiveCheckpoint = Get-RelativeWorkspacePath $checkpointRoot }
            Invoke-Verification $checkpointRoot
        }
        "Run" {
            if ([string]::IsNullOrWhiteSpace($MutationScript)) {
                throw "Run mode requires -MutationScript."
            }
            $checkpointRoot = New-Checkpoint
            $mutationStarted = $false
            try {
                Invoke-Preflight $checkpointRoot
                $mutationPath = Assert-WorkspacePath $MutationScript "Mutation script"
                if (-not (Test-Path -LiteralPath $mutationPath -PathType Leaf)) {
                    throw "Mutation script not found: $MutationScript"
                }
                $mutationStarted = $true
                Add-JournalEvent "mutation" "started" @{ script = Get-RelativeWorkspacePath $mutationPath }
                $global:LASTEXITCODE = 0
                & $mutationPath
                $exitCode = $LASTEXITCODE
                if ($exitCode -ne 0) {
                    throw "Mutation script failed with exit code $exitCode"
                }
                $script:InventoryCache = $null
                Add-JournalEvent "mutation" "passed" @{ exitCode = 0 }
                Invoke-Verification $checkpointRoot
            } catch {
                if ($mutationStarted) { Restore-AllowedFiles $checkpointRoot }
                throw
            }
        }
    }
} catch {
    $runStatus = "failed"
    $runError = $_.Exception.Message
    Add-JournalEvent "invocation" "failed" @{ message = $runError }
    throw
} finally {
    Write-TransactionJournal $runStatus $runError
}
