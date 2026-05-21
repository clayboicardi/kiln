<#
.SYNOPSIS
Run the Kiln FLAC golden-corpus parity check end-to-end.

.DESCRIPTION
1. Verifies flac.exe + ffmpeg on PATH.
2. Resolves the corpus directory.
3. Regenerates the corpus if requested or if absent / empty.
4. Sets KILN_GOLDEN_CORPUS in the env so the test JVM picks it up.
5. Invokes ./gradlew :audio:playback:desktopTest --tests "*GoldenCorpusTest*".
6. Parses Gradle output + JUnit XML reports.
7. Emits human or JSON summary; exits with Gradle's exit code (or non-zero on dep failure).

JSON output shape:
{
  "build": "pass" | "fail",
  "duration_ms": <int>,
  "total": <int>,
  "passed": <int>,
  "failed": <int>,
  "files": [{"name", "status", "failure_message"?}],
  "failure_messages": [<string>...]
}
#>
[CmdletBinding()]
param(
    [switch]$Regenerate,
    [string]$File,
    [string]$CorpusDir,
    [switch]$Json,
    [switch]$IncludeTiddl
)

$ErrorActionPreference = 'Stop'

function Resolve-RepoRoot {
    $root = (& git rev-parse --show-toplevel 2>$null)
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($root)) { return $null }
    return $root.Trim()
}

function Assert-Cli {
    param([string]$Name, [string]$InstallHint, [int]$ExitOnMissing, [string[]]$FallbackProbes = @())
    $found = Get-Command $Name -ErrorAction SilentlyContinue
    if ($found) { return $found.Source }
    foreach ($probe in $FallbackProbes) {
        $hits = @(Resolve-Path $probe -ErrorAction SilentlyContinue) | Sort-Object -Property Path -Descending
        if ($hits.Count -gt 0 -and (Test-Path $hits[0])) {
            return $hits[0].Path
        }
    }
    Write-Error "$Name not found on PATH. $InstallHint"
    exit $ExitOnMissing
}

$repoRoot = Resolve-RepoRoot
if (-not $repoRoot) {
    Write-Error 'Not inside a git repository. Run from the Kiln working tree.'
    exit 2
}

Assert-Cli -Name 'flac' -InstallHint 'Install via `winget install Xiph.FLAC` then re-open the shell.' -ExitOnMissing 4 -FallbackProbes @(
    "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Xiph.FLAC_*\flac-*-win\Win64\flac.exe",
    "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Xiph.FLAC_*\flac.exe",
    'C:\Program Files\FLAC\flac.exe'
) | Out-Null
Assert-Cli -Name 'ffmpeg' -InstallHint 'Install via `winget install Gyan.FFmpeg` or check Kiln tooling baseline.' -ExitOnMissing 5 -FallbackProbes @(
    "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Gyan.FFmpeg_*\ffmpeg-*\bin\ffmpeg.exe",
    'C:\ffmpeg\ffmpeg.exe'
) | Out-Null

if (-not $CorpusDir) {
    $CorpusDir = Join-Path $repoRoot 'audio/playback/build/golden-corpus'
}
if (-not (Test-Path $CorpusDir)) {
    New-Item -ItemType Directory -Path $CorpusDir -Force | Out-Null
}
$absCorpus = (Resolve-Path $CorpusDir).Path

$flacFilesInDir = @(Get-ChildItem -Path $absCorpus -Filter '*.flac' -ErrorAction SilentlyContinue)
$needRegen = $Regenerate.IsPresent -or $flacFilesInDir.Count -eq 0

if ($needRegen) {
    Write-Host '> Regenerating golden corpus from manifest ...'
    $genScript = Join-Path $PSScriptRoot 'generate-reference-pcm.ps1'
    & pwsh -File $genScript -CorpusDir $absCorpus @(if ($Regenerate) { '-Force' })
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Corpus generation failed (exit $LASTEXITCODE)."
        exit $LASTEXITCODE
    }
}

if ($IncludeTiddl) {
    $localManifest = Join-Path $PSScriptRoot '..\local-corpus.manifest'
    if (Test-Path $localManifest) {
        Write-Host "> Layering local tiddl manifest: $localManifest"
        $genScript = Join-Path $PSScriptRoot 'generate-reference-pcm.ps1'
        & pwsh -File $genScript -ManifestPath $localManifest -CorpusDir $absCorpus
    } else {
        Write-Warning "-IncludeTiddl was set but local-corpus.manifest does not exist at $localManifest"
    }
}

# After (re)generation: discover the FLACs we actually have.
$flacFiles = @(Get-ChildItem -Path $absCorpus -Filter '*.flac' | ForEach-Object { $_.Name })
if ($File) {
    if ($flacFiles -notcontains $File) {
        Write-Error "-File '$File' not present in corpus dir $absCorpus. Available: $($flacFiles -join ', ')"
        exit 2
    }
    $flacFiles = @($File)
    # Move all OTHER .flac/.pcm pairs out of the way temporarily? No — simpler:
    # the test enumerates all .flac in the corpus dir, so to restrict to one
    # file we'd need a separate corpus path. For now, treat -File as a filter
    # hint and warn the user.
    Write-Warning '-File filter narrows reporting but the test class enumerates all .flac files in the corpus dir. Move other pairs out of the dir for a true single-file run.'
}

if ($flacFiles.Count -eq 0) {
    Write-Error "Corpus is empty after generation attempt: $absCorpus"
    exit 2
}

Write-Host "> Corpus: $($flacFiles.Count) FLACs at $absCorpus"
$env:KILN_GOLDEN_CORPUS = $absCorpus

$gradlew = Join-Path $repoRoot 'gradlew.bat'
$gradleArgs = @(
    ':audio:playback:desktopTest'
    '--tests'
    'com.clayworks.kiln.audio.playback.flac.GoldenCorpusTest'
    '--rerun-tasks'
    '--console=plain'
    '--warning-mode=none'
    '-Dorg.gradle.welcome=never'
)

Write-Host '> Running ./gradlew :audio:playback:desktopTest --tests "*GoldenCorpusTest*" ...'
$start = Get-Date

# Capture via System.Diagnostics.Process (see kiln-verify-build's run-verify.ps1
# for full rationale — Tee-Object/Out-Host and `& cmd 2>&1` both behave
# inconsistently under `pwsh -NonInteractive`).
$psi = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName = $gradlew
foreach ($a in $gradleArgs) { [void]$psi.ArgumentList.Add($a) }
$psi.WorkingDirectory = $repoRoot
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.UseShellExecute = $false
$psi.CreateNoWindow = $true
# Inherit KILN_GOLDEN_CORPUS so the test JVM picks it up via System.getenv.
$psi.EnvironmentVariables['KILN_GOLDEN_CORPUS'] = $absCorpus

$proc = [System.Diagnostics.Process]::Start($psi)
$stdoutText = $proc.StandardOutput.ReadToEnd()
$proc.WaitForExit()
$stderrText = $proc.StandardError.ReadToEnd()
$exitCode = $proc.ExitCode
$captured = @((($stdoutText + "`n" + $stderrText) -split "`r?`n"))

$duration = [int]((Get-Date) - $start).TotalMilliseconds
foreach ($l in $captured) { Write-Host $l }

$parserPath = Join-Path $PSScriptRoot 'parse-gradle-output.ps1'
$parsed = & $parserPath -Output ([string[]]$captured) -Duration $duration -ExitCode $exitCode -RepoRoot $repoRoot -ExpectedFiles $flacFiles

if ($Json) {
    $parsed | ConvertTo-Json -Depth 8
} else {
    Write-Host ''
    Write-Host '=========================================='
    Write-Host 'Kiln FLAC Golden-Corpus parity check'
    Write-Host '=========================================='
    Write-Host ("Corpus dir: {0}" -f $absCorpus)
    Write-Host ("Files:      {0}" -f $flacFiles.Count)
    Write-Host '------------------------------------------'
    foreach ($f in $parsed.files) {
        $tag = if ($f.status -eq 'pass') { 'PASS' } else { 'FAIL' }
        Write-Host ("{0,-6}{1}" -f $tag, $f.name)
        if ($f.status -eq 'fail' -and $f.failure_message) {
            $msgLines = ($f.failure_message -split "(`r)?`n") | Select-Object -First 12
            foreach ($ml in $msgLines) {
                Write-Host ("       {0}" -f $ml)
            }
        }
    }
    Write-Host '=========================================='
    $verdictText = if ($parsed.build -eq 'pass') { 'PASS' } else { 'FAIL' }
    Write-Host ("Verdict:    {0} ({1}/{2})" -f $verdictText, $parsed.passed, $parsed.total)
    Write-Host ("Total:      {0} ms" -f $parsed.duration_ms)
    Write-Host '=========================================='
}

# Exit non-zero if anything failed.
if ($parsed.build -ne 'pass' -or $exitCode -ne 0) {
    if ($exitCode -ne 0) { exit $exitCode } else { exit 1 }
}
exit 0
