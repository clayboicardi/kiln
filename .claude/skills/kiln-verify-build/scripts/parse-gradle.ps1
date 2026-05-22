<#
.SYNOPSIS
Parse Gradle plain-console output into a structured pass/fail/error hashtable.

.DESCRIPTION
Reads an array of captured Gradle output lines and identifies:
  - Overall BUILD SUCCESSFUL / BUILD FAILED verdict
  - Per-target pass/fail (defaults to pass; flips to fail on "$target FAILED" marker)
  - Kotlin compiler errors: lines of form `e: file:///<path>:<line>:<col> <message>`
  - Test counts: scanned from `<module>/build/test-results/desktopTest/TEST-*.xml`

Returns a hashtable with keys: build, duration_ms, targets, errors.

.PARAMETER Output
Array of captured Gradle stdout/stderr lines.

.PARAMETER Targets
Array of fully-qualified Gradle target names (e.g., ':data:library:build').

.PARAMETER Duration
Wall-clock duration in milliseconds.

.PARAMETER ExitCode
Gradle process exit code.

.PARAMETER RepoRoot
Absolute path of repo root. Used to discover JUnit XML reports.
#>
[CmdletBinding()]
param(
    [AllowEmptyCollection()] [AllowNull()] [string[]]$Output = @(),
    [Parameter(Mandatory)] [string[]]$Targets,
    [Parameter(Mandatory)] [int]$Duration,
    [Parameter(Mandatory)] [int]$ExitCode,
    [string]$RepoRoot
)
if ($null -eq $Output) { $Output = @() }

$ErrorActionPreference = 'Stop'

# Overall verdict — Gradle prints "BUILD SUCCESSFUL" or "BUILD FAILED" near the tail.
$buildLine = $Output | Where-Object { $_ -match '^BUILD (SUCCESSFUL|FAILED)' } | Select-Object -Last 1
$buildVerdict = if ($buildLine -match 'BUILD SUCCESSFUL' -and $ExitCode -eq 0) { 'pass' } else { 'fail' }

# Per-target outcomes.
$targetResults = [ordered]@{}
foreach ($t in $Targets) {
    $targetResults[$t] = [ordered]@{
        name = $t
        status = 'pass'
        duration_ms = $null
    }
}

# Flip individual targets to fail on "$target FAILED" markers.
foreach ($line in $Output) {
    foreach ($t in $Targets) {
        $escaped = [regex]::Escape($t)
        if ($line -match "${escaped}\s+FAILED\b") {
            $targetResults[$t].status = 'fail'
        }
    }
}

# If overall build failed but no target was marked failed, attribute to the last
# requested target as a best-effort.
if ($buildVerdict -eq 'fail') {
    $anyFailed = @($targetResults.Values | Where-Object { $_.status -eq 'fail' })
    if ($anyFailed.Count -eq 0 -and $Targets.Count -gt 0) {
        $targetResults[$Targets[-1]].status = 'fail'
    }
}

# Extract Kotlin compiler errors:
#   "e: file:///C:/.../File.kt:42:13 Unresolved reference: foo"
# Path component may be percent-encoded on Windows; decode the drive-letter colon.
$errors = New-Object System.Collections.Generic.List[hashtable]
$errorRegex = '^e:\s+file:/+(?<path>[^:\s]+(?::[^:\s]+)?):(?<line>\d+):(?<col>\d+)\s+(?<msg>.+)$'
foreach ($line in $Output) {
    if ($line -match $errorRegex) {
        $rawPath = $matches['path']
        # Convert percent-encoded drive letter (e.g., "C%3A/...") back to "C:/..."
        $decodedPath = [System.Uri]::UnescapeDataString($rawPath) -replace '\\', '/'
        $errors.Add(@{
            file = $decodedPath
            line = [int]$matches['line']
            column = [int]$matches['col']
            severity = 'error'
            message = $matches['msg'].Trim()
        }) | Out-Null
    } elseif ($line -match '^e:\s+(?<path>[A-Za-z]:[\\/][^\s:]+\.kt):(?<line>\d+):(?<col>\d+)\s+(?<msg>.+)$') {
        # Fallback for non-URI Kotlin error format
        $fallbackPath = ($matches['path']).Replace('\', '/')
        $errors.Add(@{
            file = $fallbackPath
            line = [int]$matches['line']
            column = [int]$matches['col']
            severity = 'error'
            message = $matches['msg'].Trim()
        }) | Out-Null
    }
}

# Best-effort test counts from JUnit XML reports.
# Pattern: <repo>/<module>/build/test-results/desktopTest/TEST-*.xml
if ($RepoRoot) {
    foreach ($t in $Targets) {
        if ($t -match '^:(?<module>(?:[^:]+:)+):(?<task>desktopTest|test|testDebugUnitTest|testReleaseUnitTest)$') {
            # Won't typically match; fallback below
        }
        # The canonical Kiln target form is `:data:library:desktopTest` — convert to
        # the module path `data/library`.
        if ($t -match ':desktopTest$') {
            $modulePath = ($t -replace '^:', '' -replace ':desktopTest$', '' -replace ':', '/')
            $reportDir = Join-Path $RepoRoot (Join-Path $modulePath 'build/test-results/desktopTest')
            if (Test-Path $reportDir) {
                $xmlFiles = Get-ChildItem -Path $reportDir -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue
                if ($xmlFiles) {
                    $sumTests = 0; $sumFail = 0; $sumErr = 0
                    foreach ($xf in $xmlFiles) {
                        try {
                            [xml]$doc = Get-Content -Path $xf.FullName -Encoding UTF8
                            if ($doc.testsuite) {
                                $sumTests += [int]$doc.testsuite.tests
                                $sumFail  += [int]$doc.testsuite.failures
                                $sumErr   += [int]$doc.testsuite.errors
                            }
                        } catch {
                            # Skip malformed XML — don't blow up the parser
                        }
                    }
                    $targetResults[$t].tests_run = $sumTests
                    $targetResults[$t].tests_passed = $sumTests - $sumFail - $sumErr
                    if ($sumFail -gt 0 -or $sumErr -gt 0) {
                        $targetResults[$t].status = 'fail'
                    }
                }
            }
        }
    }
}

# Aggregate test counts across ALL modules (not just the explicitly requested
# :desktopTest target). The canonical session-validation build only lists
# `:data:library:desktopTest` as an explicit target, but `:audio:playback:build`
# transitively runs `:audio:playback:desktopTest` — those results are emitted to
# `audio/playback/build/test-results/desktopTest/` and were previously invisible
# to this parser, causing a ~40% undercount in reported test scope.
#
# Recursive glob from $RepoRoot picks up every module's desktopTest XML reports.
$totalTests = 0; $totalSkipped = 0; $totalFailures = 0; $totalErrors = 0
if ($RepoRoot) {
    $allReports = Get-ChildItem -Path $RepoRoot -Recurse -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '[\\/]build[\\/]test-results[\\/]desktopTest[\\/]' }
    foreach ($xf in $allReports) {
        try {
            [xml]$doc = Get-Content -Path $xf.FullName -Encoding UTF8
            if ($doc.testsuite) {
                $totalTests    += [int]$doc.testsuite.tests
                $totalSkipped  += [int]$doc.testsuite.skipped
                $totalFailures += [int]$doc.testsuite.failures
                $totalErrors   += [int]$doc.testsuite.errors
            }
        } catch {
            # Skip malformed XML — don't blow up the parser
        }
    }
}

# Return result as a single hashtable.
return @{
    build = $buildVerdict
    duration_ms = $Duration
    targets = @($Targets | ForEach-Object { $targetResults[$_] })
    errors = $errors.ToArray()
    total_tests = $totalTests
    total_skipped = $totalSkipped
    total_failures = $totalFailures
    total_errors = $totalErrors
}
