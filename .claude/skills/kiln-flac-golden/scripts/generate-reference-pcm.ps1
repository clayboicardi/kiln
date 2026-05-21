<#
.SYNOPSIS
Synthesize the golden FLAC corpus + matching reference PCM files from corpus.manifest.

.DESCRIPTION
Reads corpus.manifest line-by-line. For each non-comment/non-blank row:
  1. Invokes ffmpeg with the recipe args → produces <corpus>/<filename>.flac
  2. Invokes flac.exe -d --force-raw-format → produces <corpus>/<filename>.pcm

Both outputs land in the target corpus directory (default:
<repo>/audio/playback/build/golden-corpus/). Idempotent — re-running overwrites
existing files cleanly.

.PARAMETER ManifestPath
Path to corpus.manifest. Defaults to ../corpus.manifest relative to this script.

.PARAMETER CorpusDir
Output directory for .flac + .pcm pairs. Defaults to
<repo>/audio/playback/build/golden-corpus/.

.PARAMETER Force
Re-synthesize every entry, even if both .flac and .pcm already exist.
#>
[CmdletBinding()]
param(
    [string]$ManifestPath = (Join-Path $PSScriptRoot '..\corpus.manifest'),
    [string]$CorpusDir,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

function Resolve-RepoRoot {
    $root = (& git rev-parse --show-toplevel 2>$null)
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($root)) {
        return $null
    }
    return $root.Trim()
}

function Assert-Cli {
    param([string]$Name, [string]$InstallHint, [string[]]$FallbackProbes = @())
    $found = Get-Command $Name -ErrorAction SilentlyContinue
    if ($found) { return $found.Source }
    # Probe well-known install paths so the skill survives a winget-install
    # that hasn't yet been picked up by PATH (the parent shell of `bash`
    # does not auto-refresh after winget mutates Path).
    foreach ($probe in $FallbackProbes) {
        $hits = @(Resolve-Path $probe -ErrorAction SilentlyContinue) | Sort-Object -Property Path -Descending
        if ($hits.Count -gt 0 -and (Test-Path $hits[0])) {
            return $hits[0].Path
        }
    }
    Write-Error "$Name not found on PATH. $InstallHint"
    exit 4
}

$ffmpeg = Assert-Cli -Name 'ffmpeg' -InstallHint 'Install via winget Gyan.FFmpeg or check Kiln tooling baseline.' -FallbackProbes @(
    "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Gyan.FFmpeg_*\ffmpeg-*\bin\ffmpeg.exe",
    'C:\ffmpeg\ffmpeg.exe'
)
$flac = Assert-Cli -Name 'flac' -InstallHint 'Install via `winget install Xiph.FLAC` then re-open the shell.' -FallbackProbes @(
    "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Xiph.FLAC_*\flac-*-win\Win64\flac.exe",
    "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Xiph.FLAC_*\flac.exe",
    'C:\Program Files\FLAC\flac.exe'
)

if (-not (Test-Path $ManifestPath)) {
    Write-Error "Corpus manifest not found at $ManifestPath"
    exit 2
}

if (-not $CorpusDir) {
    $repoRoot = Resolve-RepoRoot
    if (-not $repoRoot) {
        Write-Error 'Could not resolve repo root via git. Pass -CorpusDir explicitly.'
        exit 2
    }
    $CorpusDir = Join-Path $repoRoot 'audio/playback/build/golden-corpus'
}

if (-not (Test-Path $CorpusDir)) {
    New-Item -ItemType Directory -Path $CorpusDir -Force | Out-Null
}

$absCorpus = (Resolve-Path $CorpusDir).Path
Write-Host "> Corpus dir: $absCorpus"
Write-Host "> ffmpeg:     $ffmpeg"
Write-Host "> flac:       $flac"

$rows = @()
foreach ($raw in Get-Content -Path $ManifestPath -Encoding UTF8) {
    $line = $raw.Trim()
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    if ($line.StartsWith('#')) { continue }
    $parts = $line -split '\s\|\s', 3
    if ($parts.Count -lt 2) {
        Write-Warning "Skipping malformed manifest line: $line"
        continue
    }
    $rows += [pscustomobject]@{
        Filename = $parts[0].Trim()
        Recipe   = $parts[1].Trim()
        Description = if ($parts.Count -ge 3) { $parts[2].Trim() } else { '' }
    }
}

if ($rows.Count -eq 0) {
    Write-Error "No entries found in $ManifestPath"
    exit 2
}

Write-Host "> Parsed $($rows.Count) manifest rows"
Write-Host ''

$generated = 0
$skipped = 0
foreach ($row in $rows) {
    $flacOut = Join-Path $absCorpus $row.Filename
    $pcmOut = Join-Path $absCorpus ([System.IO.Path]::ChangeExtension($row.Filename, '.pcm'))

    $needSynth = $Force.IsPresent -or -not (Test-Path $flacOut) -or -not (Test-Path $pcmOut)
    if (-not $needSynth) {
        Write-Host "[skip] $($row.Filename) — both .flac and .pcm present"
        $skipped++
        continue
    }

    Write-Host "[synth] $($row.Filename)  — $($row.Description)"

    # Synthesize FLAC via ffmpeg. Pass the recipe through cmd.exe so its
    # native shell parser handles the lavfi expression escaping (backslashed
    # commas, embedded equals signs) faithfully.
    $ffmpegCmd = "`"$ffmpeg`" -hide_banner -loglevel error -y $($row.Recipe) `"$flacOut`""
    & cmd.exe /c $ffmpegCmd
    if ($LASTEXITCODE -ne 0) {
        Write-Error "ffmpeg failed (exit $LASTEXITCODE) for $($row.Filename): $ffmpegCmd"
        exit 6
    }
    if (-not (Test-Path $flacOut) -or (Get-Item $flacOut).Length -eq 0) {
        Write-Error "ffmpeg produced no output for $($row.Filename) (cmd: $ffmpegCmd)"
        exit 6
    }

    # Decode FLAC to raw PCM via flac.exe — the canonical reference.
    # --force-raw-format produces headerless interleaved PCM in the format
    # specified by --endian + --sign + the source bit depth.
    $flacArgs = @(
        '-d'
        '--silent'
        '--totally-silent'
        '--force-raw-format'
        '--endian=little'
        '--sign=signed'
        '-f'                  # overwrite output if exists
        '-o'
        $pcmOut
        $flacOut
    )
    & $flac @flacArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Error "flac -d failed (exit $LASTEXITCODE) for $($row.Filename)"
        exit 7
    }
    if (-not (Test-Path $pcmOut) -or (Get-Item $pcmOut).Length -eq 0) {
        Write-Error "flac -d produced no PCM output for $($row.Filename)"
        exit 7
    }

    $generated++
}

Write-Host ''
Write-Host "> Done. Generated=$generated, Skipped=$skipped, Total=$($rows.Count)"
Write-Host "> Corpus ready at: $absCorpus"
