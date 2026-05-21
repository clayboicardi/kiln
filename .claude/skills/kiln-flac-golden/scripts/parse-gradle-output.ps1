<#
.SYNOPSIS
Parse Gradle output + JUnit XML reports into a per-file pass/fail JSON shape for kiln-flac-golden.

.DESCRIPTION
Examines:
  - Gradle stdout/stderr for BUILD SUCCESSFUL / BUILD FAILED + the test target's FAILED marker.
  - JUnit XML reports under <repo>/audio/playback/build/test-results/desktopTest/ for
    individual test method outcomes + assertion failure messages.

Returns a hashtable with: build, duration_ms, total, passed, failed, files[].
Each file entry includes: name, status (pass|fail), failure_message (when failed).

.PARAMETER Output
Array of captured Gradle output lines.

.PARAMETER Duration
Wall-clock duration in milliseconds.

.PARAMETER ExitCode
Gradle process exit code.

.PARAMETER RepoRoot
Absolute path of repo root (used to locate the JUnit XML reports).

.PARAMETER ExpectedFiles
Array of corpus filename strings (e.g., "16bit-44100-sine-440hz.flac"). Used to
attribute parsed failure messages to individual corpus files.
#>
[CmdletBinding()]
param(
    [AllowEmptyCollection()] [AllowNull()] [string[]]$Output = @(),
    [Parameter(Mandatory)] [int]$Duration,
    [Parameter(Mandatory)] [int]$ExitCode,
    [Parameter(Mandatory)] [string]$RepoRoot,
    [string[]]$ExpectedFiles = @()
)
if ($null -eq $Output) { $Output = @() }

$ErrorActionPreference = 'Stop'

$buildLine = $Output | Where-Object { $_ -match '^BUILD (SUCCESSFUL|FAILED)' } | Select-Object -Last 1
$buildVerdict = if ($buildLine -match 'BUILD SUCCESSFUL' -and $ExitCode -eq 0) { 'pass' } else { 'fail' }

# JUnit XML location for :audio:playback:desktopTest
$reportDir = Join-Path $RepoRoot 'audio/playback/build/test-results/desktopTest'

$fileEntries = New-Object System.Collections.Generic.List[object]
$total = 0; $passed = 0; $failed = 0; $failureMessages = @()

if (Test-Path $reportDir) {
    foreach ($xf in Get-ChildItem -Path $reportDir -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue) {
        if ($xf.Name -notmatch 'GoldenCorpus') { continue }
        try {
            [xml]$doc = Get-Content -Path $xf.FullName -Encoding UTF8
            $ts = $doc.testsuite
            if (-not $ts) { continue }
            $total  += [int]$ts.tests
            $failed += [int]$ts.failures + [int]$ts.errors
            $passed += [int]$ts.tests - ([int]$ts.failures + [int]$ts.errors)
            foreach ($tc in @($ts.testcase)) {
                $tcFail = $tc.failure
                $tcErr  = $tc.error
                if ($tcFail -or $tcErr) {
                    $msg = if ($tcFail) { "$($tcFail.'#text')$($tcFail.message)" } else { "$($tcErr.'#text')$($tcErr.message)" }
                    $failureMessages += $msg
                }
            }
        } catch {
            Write-Verbose "Could not parse $($xf.FullName): $_"
        }
    }
}

# Attribute parsed failure messages to expected corpus files when their name appears.
$failuresByFile = @{}
foreach ($msg in $failureMessages) {
    foreach ($fname in $ExpectedFiles) {
        if ($msg -match [regex]::Escape($fname)) {
            $failuresByFile[$fname] = $msg
        }
    }
}

foreach ($fname in $ExpectedFiles) {
    if ($failuresByFile.ContainsKey($fname)) {
        $fileEntries.Add(@{
            name = $fname
            status = 'fail'
            failure_message = $failuresByFile[$fname]
        }) | Out-Null
    } else {
        $fileEntries.Add(@{
            name = $fname
            status = if ($buildVerdict -eq 'pass') { 'pass' } else { 'unknown' }
        }) | Out-Null
    }
}

# Final verdict: a single Gradle test run with the GoldenCorpusTest may aggregate
# failures into one method (failure-list pattern). If ANY failure was parsed, the
# verdict flips to fail regardless of the overall Gradle exit code.
if ($failed -gt 0) { $buildVerdict = 'fail' }

return @{
    build = $buildVerdict
    duration_ms = $Duration
    total = $total
    passed = $passed
    failed = $failed
    files = $fileEntries.ToArray()
    failure_messages = $failureMessages
}
