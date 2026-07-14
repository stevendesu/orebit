# Orebit START-CELL worldgen determinism probe driver (mc-1.21 era, node 1.21.11).
# Runs the HeadlessAutotest probeOnly seam N times, each on a FRESHLY regenerated world from the pinned
# seed (scripts/autotest/server.properties), and collects each run's orebit-autotest-startprobe.txt so the
# `signature=` lines can be compared. Identical signatures across all runs => worldgen is deterministic
# here under a fixed load pattern. Differing => genuine same-seed worldgen non-determinism.
#
# NO bot spawn, NO goto: each run is just worldgen + a handful of block reads, then halt. Fast.
# Requires the JDK-21 toolchain (1.21.11 node) -- this script pins JAVA_HOME to it.
# Windows PowerShell 5.1 compatible (no &&, no ternary).

param(
    [int]$Runs = 5,
    [string]$McVersion = "1.21.11",
    [string]$Jdk21 = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
)

$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot            # scripts/ -> repo root (worktree)
$runDir = Join-Path $repo "run\autotest"
$templates = Join-Path $PSScriptRoot "autotest"
$probeFile = Join-Path $runDir "orebit-autotest-startprobe.txt"
$outDir = Join-Path $PSScriptRoot "startprobe-out"

if (-not (Test-Path $Jdk21)) { Write-Error "JDK 21 not found at $Jdk21"; exit 2 }
$env:JAVA_HOME = $Jdk21
Write-Host "[startprobe] JAVA_HOME = $env:JAVA_HOME"

New-Item -ItemType Directory -Force -Path $outDir | Out-Null
Get-ChildItem -Path $outDir -Filter "run-*.txt" -ErrorAction SilentlyContinue | Remove-Item -Force

Push-Location $repo
try {
    Write-Host "[startprobe] re-asserting active Stonecutter project ($McVersion)"
    & (Join-Path $repo "gradlew.bat") "Set active project to $McVersion"
    if ($LASTEXITCODE -ne 0) { Write-Error "Set active project failed"; exit 2 }

    for ($i = 1; $i -le $Runs; $i++) {
        Write-Host ""
        Write-Host "===== startprobe run $i / $Runs ====="

        # Fresh world every run (deterministic regen from the pinned seed) + fresh config templates.
        New-Item -ItemType Directory -Force -Path (Join-Path $runDir "config") | Out-Null
        $world = Join-Path $runDir "world"
        if (Test-Path $world) { Remove-Item -Recurse -Force $world }
        if (Test-Path $probeFile) { Remove-Item -Force $probeFile }
        Copy-Item (Join-Path $templates "server.properties") (Join-Path $runDir "server.properties") -Force
        Copy-Item (Join-Path $templates "eula.txt")          (Join-Path $runDir "eula.txt") -Force
        Copy-Item (Join-Path $templates "orebit.properties") (Join-Path $runDir "config\orebit.properties") -Force

        & (Join-Path $repo "gradlew.bat") ":fabric:${McVersion}:runAutotest" "-Porebit.autotest.probeOnly=true"
        Write-Host "[startprobe] run $i gradle exit = $LASTEXITCODE"

        if (Test-Path $probeFile) {
            Copy-Item $probeFile (Join-Path $outDir ("run-{0:D2}.txt" -f $i)) -Force
        } else {
            Write-Warning "[startprobe] run $i produced NO probe file (crash? see run/autotest/logs/latest.log)"
        }
    }
} finally {
    Pop-Location
}

# ---- Compare signatures ----------------------------------------------------------------------
Write-Host ""
Write-Host "===== signatures ====="
$sigs = @()
Get-ChildItem -Path $outDir -Filter "run-*.txt" | Sort-Object Name | ForEach-Object {
    $line = Select-String -Path $_.FullName -Pattern '^signature=' | Select-Object -First 1
    $sig = if ($line) { $line.Line } else { "(no signature line)" }
    $sigs += $sig
    Write-Host ("  {0}: {1}" -f $_.Name, $sig)
}
$distinct = $sigs | Sort-Object -Unique
Write-Host "======================"
if ($distinct.Count -le 1 -and $sigs.Count -gt 0) {
    Write-Host "[startprobe] DETERMINISTIC: all $($sigs.Count) runs share one signature."
} else {
    Write-Host "[startprobe] NON-DETERMINISTIC or incomplete: $($distinct.Count) distinct signatures across $($sigs.Count) runs."
}
Write-Host "[startprobe] per-run dumps: $outDir\run-*.txt (diff them for the actual block differences)"
