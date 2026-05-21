<#
.SYNOPSIS
Generate the per-plan-§11 Kiln session-handoff doc skeleton for the next session.

.DESCRIPTION
Pulls commit log, structured trailers (decision:/gotcha:/todo:), CLAUDE.md diff hints,
working-tree state, and test counts from the JUnit XML reports. Substitutes a markdown
template at scripts/handoff-template.md and writes the result to
docs/sessions/YYYY-MM-DD-session-<N>-handoff.md.

Refuses to overwrite an existing file unless -Force is passed.

.PARAMETER SessionNum
Required. Session number being CREATED (the next session number, not the closing one).

.PARAMETER PrevSession
Override auto-detected previous-session number. Default: highest numeric -handoff.md found.

.PARAMETER Summary
One-line summary to seed the doc's Goal section.

.PARAMETER InFlight
Array of in-flight item titles to seed the In-flight items section, in addition to any
`todo:` trailers pulled from commit bodies.

.PARAMETER Force
Overwrite an existing handoff file. Default refuses (exit 1).
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [int]$SessionNum,
    [int]$PrevSession,
    [string]$Summary,
    [string[]]$InFlight = @(),
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

function Resolve-RepoRoot {
    $root = (& git rev-parse --show-toplevel 2>$null)
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($root)) { return $null }
    return $root.Trim()
}

function Get-PrevSessionFile {
    param([string]$RepoRoot, [int]$ExplicitNum, [int]$TargetSessionNum = 0)
    $sessionsDir = Join-Path $RepoRoot 'docs/sessions'
    if (-not (Test-Path $sessionsDir)) {
        return @{ Num = $null; Path = $null; RelPath = $null }
    }
    $candidates = Get-ChildItem -Path $sessionsDir -Filter '*-handoff.md' -ErrorAction SilentlyContinue
    $parsed = $candidates | ForEach-Object {
        if ($_.Name -match 'session-(\d+)-handoff\.md$') {
            [pscustomobject]@{
                Num = [int]$matches[1]
                Path = $_.FullName
                RelPath = "docs/sessions/$($_.Name)"
            }
        }
    } | Where-Object { $_ -ne $null }
    if ($ExplicitNum -gt 0) {
        $match = $parsed | Where-Object { $_.Num -eq $ExplicitNum } | Select-Object -First 1
        if ($match) { return @{ Num = $match.Num; Path = $match.Path; RelPath = $match.RelPath } }
        return @{ Num = $ExplicitNum; Path = $null; RelPath = $null }
    }
    # Exclude the target session itself — re-running with -Force would otherwise
    # pick up the just-generated file as its own predecessor.
    if ($TargetSessionNum -gt 0) {
        $parsed = $parsed | Where-Object { $_.Num -lt $TargetSessionNum }
    }
    $top = $parsed | Sort-Object Num -Descending | Select-Object -First 1
    if (-not $top) { return @{ Num = $null; Path = $null; RelPath = $null } }
    return @{ Num = $top.Num; Path = $top.Path; RelPath = $top.RelPath }
}

function Get-RangeStartCommit {
    param([string]$RepoRoot, [string]$PrevHandoffRelPath)
    if (-not $PrevHandoffRelPath) {
        # No prior handoff — use root commit as floor (full log).
        $rc = (& git -C $RepoRoot rev-list --max-parents=0 HEAD 2>$null) | Select-Object -First 1
        return $rc
    }
    # Find the commit that introduced the prior handoff file.
    $introCommit = (& git -C $RepoRoot log --follow --diff-filter=A --format='%H' -- $PrevHandoffRelPath 2>$null) | Select-Object -First 1
    if (-not $introCommit) {
        # File exists in tree but uncommitted, OR `git log --follow` returned nothing.
        # Fall back to the most recent commit touching the file.
        $introCommit = (& git -C $RepoRoot log -1 --format='%H' -- $PrevHandoffRelPath 2>$null)
    }
    if (-not $introCommit) {
        return ''
    }
    return $introCommit.Trim()
}

function Get-CommitsSince {
    param([string]$RepoRoot, [string]$SinceSha)
    $range = if ([string]::IsNullOrWhiteSpace($SinceSha)) { 'HEAD' } else { "$SinceSha..HEAD" }
    # Per-commit format using the ASCII unit separator (0x1F) wrapped 3x as a
    # commit boundary. The format placeholders %H/%h/%an/%s/%b are git's; the
    # `%x1F` placeholders insert raw 0x1F bytes that we split on.
    $boundary = ([char]0x1F).ToString() * 3 + '-COMMIT-' + ([char]0x1F).ToString() * 3
    $fmt = "%H%n%h%n%an%n%s%n%b%x1F%x1F%x1F-COMMIT-%x1F%x1F%x1F"
    $raw = & git -C $RepoRoot log $range --no-merges --reverse "--pretty=format:$fmt" 2>$null
    if (-not $raw) { return ,@() }
    # Force string regardless of git emitting array vs scalar.
    $joined = [string]::Join([Environment]::NewLine, @($raw))
    $entries = @($joined -split [regex]::Escape($boundary)) | Where-Object {
        $_ -ne $null -and "$_".Trim() -ne ''
    }
    $result = New-Object System.Collections.Generic.List[object]
    foreach ($entry in $entries) {
        # Strip leading empty lines — the newline between one entry's body and
        # the next commit's %H ends up as a blank lines[0] otherwise, shifting
        # every subsequent field by one.
        $rawLines = @("$entry" -split "`r?`n")
        $firstNonEmpty = 0
        while ($firstNonEmpty -lt $rawLines.Length -and "$($rawLines[$firstNonEmpty])".Trim() -eq '') {
            $firstNonEmpty++
        }
        if ($firstNonEmpty -ge $rawLines.Length) { continue }
        $lines = $rawLines[$firstNonEmpty..($rawLines.Length - 1)]
        if ($lines.Length -lt 4) { continue }
        $sha = "$($lines[0])".Trim()
        $shortSha = "$($lines[1])".Trim()
        $author = "$($lines[2])".Trim()
        $subject = "$($lines[3])"
        $body = if ($lines.Length -gt 4) {
            $bodyLines = $lines[4..($lines.Length - 1)]
            ([string]::Join([Environment]::NewLine, $bodyLines)).Trim()
        } else { '' }
        [void]$result.Add([pscustomobject]@{
            Sha = $sha
            ShortSha = $shortSha
            Author = $author
            Subject = $subject
            Body = $body
        })
    }
    return ,@($result.ToArray())
}

function Parse-CommitType {
    param([string]$Subject)
    if ($Subject -match '^(?<type>\w+)(?:\([^)]+\))?:') {
        return $matches['type']
    }
    return 'other'
}

function Extract-Trailers {
    param([string]$Body, [string]$Key)
    if (-not $Body) { return @() }
    $found = New-Object System.Collections.Generic.List[string]
    foreach ($line in ($Body -split "`r?`n")) {
        $trimmed = $line.Trim()
        if ($trimmed -match "^${Key}:\s*(.+)$") {
            $found.Add($matches[1].Trim()) | Out-Null
        }
    }
    return $found.ToArray()
}

function Get-WorkingTreeState {
    param([string]$RepoRoot)
    $status = (& git -C $RepoRoot status --short 2>$null) -join "`n"
    if (-not $status) { $status = '(clean)' }
    $branch = (& git -C $RepoRoot rev-parse --abbrev-ref HEAD 2>$null).Trim()
    $headSha = (& git -C $RepoRoot rev-parse HEAD 2>$null).Trim()
    $headShaShort = (& git -C $RepoRoot rev-parse --short HEAD 2>$null).Trim()
    $stash = (& git -C $RepoRoot stash list 2>$null) -join "`n"
    if (-not $stash) { $stash = '(empty)' }
    $aheadBehind = ''
    try {
        $upstream = (& git -C $RepoRoot rev-parse --abbrev-ref '@{u}' 2>$null).Trim()
        if ($upstream) {
            $rl = (& git -C $RepoRoot rev-list --left-right --count "@{u}...HEAD" 2>$null).Trim()
            if ($rl -match '(\d+)\s+(\d+)') {
                $behind = [int]$matches[1]
                $ahead = [int]$matches[2]
                $aheadBehind = "ahead $ahead / behind $behind ($upstream)"
            }
        }
    } catch {}
    if (-not $aheadBehind) { $aheadBehind = 'no upstream tracked' }
    return @{
        Status = $status
        Branch = $branch
        HeadSha = $headSha
        HeadShaShort = $headShaShort
        Stash = $stash
        Sync = $aheadBehind
    }
}

function Get-TestCountSummary {
    param([string]$RepoRoot)
    $modules = @('data/library', 'audio/playback')
    $summary = @()
    foreach ($m in $modules) {
        $dir = Join-Path $RepoRoot ([System.IO.Path]::Combine($m, 'build/test-results/desktopTest'))
        if (-not (Test-Path $dir)) { continue }
        $xmls = Get-ChildItem -Path $dir -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue
        if (-not $xmls) { continue }
        $tests = 0; $fails = 0; $errs = 0
        foreach ($xf in $xmls) {
            try {
                [xml]$doc = Get-Content -Path $xf.FullName -Encoding UTF8
                if ($doc.testsuite) {
                    $tests += [int]$doc.testsuite.tests
                    $fails += [int]$doc.testsuite.failures
                    $errs  += [int]$doc.testsuite.errors
                }
            } catch {}
        }
        $passed = $tests - $fails - $errs
        $gradleTarget = ':' + ($m -replace '/', ':') + ':desktopTest'
        $summary += "$passed/$tests in $gradleTarget"
    }
    if ($summary.Count -eq 0) {
        return '(no test reports on disk — run `just verify` to populate)'
    }
    return ($summary -join '; ')
}

function Get-TodoComments {
    param([string]$RepoRoot, [int]$TargetSession)
    $marker = "TODO(session-$TargetSession"
    $result = New-Object System.Collections.Generic.List[string]
    try {
        $hits = & git -C $RepoRoot grep -n -F $marker -- '*.kt' '*.kts' 2>$null
        foreach ($h in @($hits)) {
            $result.Add($h.Trim()) | Out-Null
        }
    } catch {}
    return $result.ToArray()
}

# --- main ---

$repoRoot = Resolve-RepoRoot
if (-not $repoRoot) {
    Write-Error 'Not inside a git repository. Run from the Kiln working tree.'
    exit 2
}

$prev = Get-PrevSessionFile -RepoRoot $repoRoot -ExplicitNum $PrevSession -TargetSessionNum $SessionNum
$resolvedPrevNum = if ($prev.Num) { $prev.Num } else { $SessionNum - 1 }
$prevHandoffFilename = if ($prev.Path) { (Split-Path $prev.Path -Leaf) } else { "session-$resolvedPrevNum-handoff.md (not found)" }
$prevHandoffRelPath = $prev.RelPath

$today = Get-Date -Format 'yyyy-MM-dd'
$timeOfDay = (Get-Date).ToString('HH:mm')
$timeBlock = if ((Get-Date).Hour -lt 12) { 'AM' } else { 'PM' }
$outRelPath = "docs/sessions/$today-session-$SessionNum-handoff.md"
$outAbsPath = Join-Path $repoRoot $outRelPath

if ((Test-Path $outAbsPath) -and -not $Force.IsPresent) {
    Write-Error "Handoff already exists: $outRelPath. Re-run with -Force to overwrite."
    exit 1
}

# Collect data
$startSha = Get-RangeStartCommit -RepoRoot $repoRoot -PrevHandoffRelPath $prevHandoffRelPath
$startShaShort = if ($startSha) { (& git -C $repoRoot rev-parse --short $startSha 2>$null).Trim() } else { '(repo root)' }
$commits = Get-CommitsSince -RepoRoot $repoRoot -SinceSha $startSha
$tree = Get-WorkingTreeState -RepoRoot $repoRoot
$testSummary = Get-TestCountSummary -RepoRoot $repoRoot
$todoHits = Get-TodoComments -RepoRoot $repoRoot -TargetSession $SessionNum

$commitTableLines = @('| Commit | Type | Subject |', '|---|---|---|')
$decisions = New-Object System.Collections.Generic.List[string]
$gotchas   = New-Object System.Collections.Generic.List[string]
$todos     = New-Object System.Collections.Generic.List[string]

foreach ($c in $commits) {
    $type = Parse-CommitType -Subject $c.Subject
    # Escape pipes in subject for markdown table safety
    $safeSubject = $c.Subject -replace '\|', '\|'
    $commitTableLines += "| ``$($c.ShortSha)`` | $type | $safeSubject |"
    foreach ($d in Extract-Trailers -Body $c.Body -Key 'decision') { $decisions.Add("- [$($c.ShortSha)] $d") | Out-Null }
    foreach ($g in Extract-Trailers -Body $c.Body -Key 'gotcha')   { $gotchas.Add("- [$($c.ShortSha)] $g") | Out-Null }
    foreach ($t in Extract-Trailers -Body $c.Body -Key 'todo')     { $todos.Add("- [$($c.ShortSha)] $t") | Out-Null }
}
if ($commits.Count -eq 0) {
    $commitTableLines += '| (none) | — | (no commits since prior handoff) |'
}

foreach ($explicit in $InFlight) {
    $todos.Add("- (explicit) $explicit") | Out-Null
}
foreach ($hit in $todoHits) {
    $todos.Add("- (source-comment) $hit") | Out-Null
}

# Section bodies — never blank; show placeholder text if nothing was extracted.
$inFlightBody = if ($todos.Count -gt 0) { ($todos -join "`n") } else { '_(none captured — write any pickup items here before pushing the handoff)_' }
$decisionsBody = if ($decisions.Count -gt 0) { ($decisions -join "`n") } else { '_(no `decision:` trailers in commit bodies — fill any architectural calls here manually)_' }
$gotchasBody = if ($gotchas.Count -gt 0) { ($gotchas -join "`n") } else { '_(no `gotcha:` trailers in commit bodies — note any discovered surprises here manually)_' }

$commitRange = if ($startSha) { "$startShaShort..$($tree.HeadShaShort)" } else { "root..$($tree.HeadShaShort)" }
$summaryLine = if ($Summary) { $Summary } else { '_(one-line goal — fill in before sharing this handoff)_' }
$titleTail = if ($Summary) {
    if ($Summary.Length -gt 60) { $Summary.Substring(0, 57) + '...' } else { $Summary }
} else { 'session pickup' }

$treeStateOneLine = if ($tree.Status -eq '(clean)') { 'clean tree' } else { 'dirty tree (see below)' }
$originSyncState = $tree.Sync
$buildStateLine = '_(populate via `just verify` and paste the verdict here)_'

# Compose template substitutions
$templatePath = Join-Path $PSScriptRoot 'handoff-template.md'
if (-not (Test-Path $templatePath)) {
    Write-Error "Template missing at $templatePath"
    exit 2
}
$template = Get-Content -Path $templatePath -Raw -Encoding UTF8

$generatorVersion = 'v1 (kiln-session-handoff/scripts/generate-handoff.ps1)'

$substitutions = @{
    '{{session_num}}'              = "$SessionNum"
    '{{prev_session_num}}'         = "$resolvedPrevNum"
    '{{date}}'                     = $today
    '{{time_block}}'               = $timeBlock
    '{{title_tail}}'               = $titleTail
    '{{summary_line}}'              = $summaryLine
    '{{head_sha_short}}'            = $tree.HeadShaShort
    '{{branch}}'                    = $tree.Branch
    '{{origin_sync_state}}'         = $originSyncState
    '{{tree_state_one_line}}'       = $treeStateOneLine
    '{{tree_state_full}}'           = $tree.Status
    '{{stash_state}}'               = $tree.Stash
    '{{build_state_line}}'          = $buildStateLine
    '{{test_count_summary}}'        = $testSummary
    '{{commit_count}}'              = "$($commits.Count)"
    '{{commit_range}}'              = $commitRange
    '{{commit_table}}'              = ($commitTableLines -join "`n")
    '{{in_flight_section}}'         = $inFlightBody
    '{{decisions_section}}'         = $decisionsBody
    '{{gotchas_section}}'           = $gotchasBody
    '{{prev_handoff_filename}}'     = $prevHandoffFilename
    '{{generator_version}}'         = $generatorVersion
}

$output = $template
foreach ($k in $substitutions.Keys) {
    $output = $output.Replace($k, [string]$substitutions[$k])
}

# Write
$outDir = Split-Path $outAbsPath -Parent
if (-not (Test-Path $outDir)) {
    New-Item -ItemType Directory -Path $outDir -Force | Out-Null
}
Set-Content -Path $outAbsPath -Value $output -Encoding UTF8

Write-Host ''
Write-Host '=========================================='
Write-Host 'Kiln Session Handoff generated'
Write-Host '=========================================='
Write-Host "Output:        $outRelPath"
Write-Host "Session:       $resolvedPrevNum  ->  $SessionNum"
Write-Host "Commit range:  $commitRange ($($commits.Count) commits)"
Write-Host "Trailers:      $($decisions.Count) decisions, $($gotchas.Count) gotchas, $($todos.Count) todos"
Write-Host "Tests:         $testSummary"
Write-Host '=========================================='
Write-Host 'Next: review the file, fill any `_(...)_` placeholders, commit.'
exit 0
