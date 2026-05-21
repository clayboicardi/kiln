<#
.SYNOPSIS
Run Kiln's canonical 5-target session-validation Gradle build and emit a structured summary.

.DESCRIPTION
Wraps `./gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build
:audio:playback:build :data:library:desktopTest` with output parsing. Returns exit 0 on
BUILD SUCCESSFUL, 1 on BUILD FAILED, 2 on pre-flight failure (missing gradlew, not in
repo, etc.), 3 if another Gradle build is in flight.

.NOTES
Companion parser at scripts/parse-gradle.ps1. JSON output shape:
{
  "build": "pass" | "fail",
  "duration_ms": <int>,
  "targets": [{"name", "status", "duration_ms"?, "tests_run"?, "tests_passed"?}],
  "errors": [{"file", "line", "column", "severity", "message"}]
}
#>
[CmdletBinding()]
param(
    [string[]]$Targets = @(
        ':app-android:assembleDebug',
        ':app-desktop:assemble',
        ':data:library:build',
        ':audio:playback:build',
        ':data:library:desktopTest'
    ),
    [switch]$Clean,
    [switch]$Json,
    [switch]$NoTests
)

$ErrorActionPreference = 'Stop'

function Resolve-RepoRoot {
    $root = (& git rev-parse --show-toplevel 2>$null)
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($root)) {
        return $null
    }
    return $root.Trim()
}

function Test-BuildInFlight {
    param([string]$RepoRoot)
    $candidates = @(
        Join-Path $RepoRoot '.gradle\build-locks'
        Join-Path $RepoRoot '.gradle\.lock'
    )
    foreach ($c in $candidates) {
        if (Test-Path $c) {
            $children = Get-ChildItem -Path $c -ErrorAction SilentlyContinue
            if ($children) { return $true }
            if ((Get-Item $c -ErrorAction SilentlyContinue).PSIsContainer -eq $false) { return $true }
        }
    }
    return $false
}

$repoRoot = Resolve-RepoRoot
if (-not $repoRoot) {
    Write-Error 'Not inside a git repository. Run this script from the Kiln working tree.'
    exit 2
}

$gradlew = Join-Path $repoRoot 'gradlew.bat'
if (-not (Test-Path $gradlew)) {
    Write-Error "gradlew.bat not found at $gradlew. Are you in the Kiln repo root?"
    exit 2
}

if (Test-BuildInFlight -RepoRoot $repoRoot) {
    Write-Error 'A Gradle build is already in flight (lock detected in .gradle/). Run `./gradlew --stop` or wait, then retry.'
    exit 3
}

if ($NoTests) {
    $Targets = @($Targets | Where-Object { $_ -notmatch ':(desktopTest|test|testDebugUnitTest|testReleaseUnitTest)$' })
}

if ($Clean) {
    Write-Host '> Running ./gradlew clean ...'
    Push-Location $repoRoot
    try {
        & $gradlew clean --console=plain --warning-mode=none "-Dorg.gradle.welcome=never" | Out-Host
    } finally {
        Pop-Location
    }
}

$gradleArgs = @() + $Targets + @('--console=plain', '--warning-mode=none', '-Dorg.gradle.welcome=never')

Write-Host "> Running ./gradlew $($Targets -join ' ') ..."
$start = Get-Date

# Capture gradle output via System.Diagnostics.Process. Earlier attempts
# (Tee-Object/Out-Host pipelines, *> redirection, $captured = & ... 2>&1)
# behaved inconsistently under `pwsh -NonInteractive` — output reached host
# but the assignment came back empty. The explicit Process pattern is the
# most portable way to capture an external command's stdout+stderr in pwsh.
$psi = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName = $gradlew
foreach ($a in $gradleArgs) { [void]$psi.ArgumentList.Add($a) }
$psi.WorkingDirectory = $repoRoot
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.UseShellExecute = $false
$psi.CreateNoWindow = $true

$proc = [System.Diagnostics.Process]::Start($psi)
# Drain stdout first (gradle puts ~all content here). Drain stderr after
# WaitForExit (it's typically empty for our targets, so deadlock risk is nil).
$stdoutText = $proc.StandardOutput.ReadToEnd()
$proc.WaitForExit()
$stderrText = $proc.StandardError.ReadToEnd()
$exitCode = $proc.ExitCode
$combined = ($stdoutText + "`n" + $stderrText)
$captured = @(($combined -split "`r?`n"))

$duration = [int]((Get-Date) - $start).TotalMilliseconds
if ($captured.Count -eq 0) { $captured = @('') }
# Echo gradle output so the user sees what happened.
foreach ($l in $captured) { Write-Host $l }

$parserPath = Join-Path $PSScriptRoot 'parse-gradle.ps1'
$parsed = & $parserPath -Output ([string[]]$captured) -Targets $Targets -Duration $duration -ExitCode $exitCode -RepoRoot $repoRoot

if ($Json) {
    $parsed | ConvertTo-Json -Depth 8
} else {
    Write-Host ''
    Write-Host '=========================================='
    Write-Host 'Kiln Verify-Build summary'
    Write-Host '=========================================='
    $verdict = if ($parsed.build -eq 'pass') { 'PASS' } else { 'FAIL' }
    Write-Host ("Verdict:    {0}" -f $verdict)
    Write-Host ("Duration:   {0} ms" -f $parsed.duration_ms)
    Write-Host 'Targets:'
    foreach ($t in $parsed.targets) {
        $tag = if ($t.status -eq 'pass') { '  PASS ' } else { '  FAIL ' }
        $suffix = ''
        if ($null -ne $t.tests_run) {
            $suffix = "  ({0}/{1} tests)" -f $t.tests_passed, $t.tests_run
        }
        Write-Host ("{0}{1}{2}" -f $tag, $t.name, $suffix)
    }
    Write-Host ("Errors: {0}" -f @($parsed.errors).Count)
    if (@($parsed.errors).Count -gt 0) {
        Write-Host ''
        Write-Host 'First 5 errors:'
        $parsed.errors | Select-Object -First 5 | ForEach-Object {
            Write-Host ("  {0}:{1}:{2}  {3}" -f $_.file, $_.line, $_.column, $_.message)
        }
    }
    Write-Host '=========================================='
}

exit $exitCode
