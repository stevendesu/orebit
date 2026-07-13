# Orebit headless REAL-WORLD REPLAY orchestrator (mc-1.21 era, Fabric).
# Location: <repo>/scripts/run-replay.ps1, templates in <repo>/scripts/replay/.
#
# Unlike run-swim.ps1 (which deletes the world for a fresh flat gen), this COPIES the owner's hand-built
# "Swims" save into run/replay/world/ and the server LOADS it. It then runs :fabric:<ver>:runReplay, which
# arms the common-src WorldReplay hook (-Dorebit.replay) to reproduce the reported-failing sequence:
#     /bot stay ; /tp Dev_bot 14 -56 1 ; /bot goto -3 -56 1
#
# Outputs:
#   run/replay/orebit-replay-result.properties  - PASS/FAIL verdict line
#   run/replay/orebit-replay-trace.txt          - per-tick trajectory + water state (MOVE/POSE/PLAN lines)
#
# Exit codes: 0 = run completed (read the result), 2 = no result file (crash / world failed to load).
# Requires JAVA_HOME -> JDK 21 (the 1.21.11 node). Windows PowerShell 5.1 compatible.
# The owner's original Swims save is NEVER mutated (we only copy FROM it).

param(
    [string]$McVersion = "1.21.11",
    [string]$SourceWorld = "",   # defaults to <repo>/run/saves/Swims
    [switch]$BotDebug,
    [string]$Bleed = ""          # forwards -Porebit.swim.bleed (directional|servo|...) for the swim-servo A/B
)

$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$runDir = Join-Path $repo "run\replay"
$templates = Join-Path $PSScriptRoot "replay"
$resultFile = Join-Path $runDir "orebit-replay-result.properties"

if ([string]::IsNullOrWhiteSpace($SourceWorld)) {
    $SourceWorld = Join-Path $repo "run\saves\Swims"
}
if (-not (Test-Path (Join-Path $SourceWorld "level.dat"))) {
    Write-Error "Source world has no level.dat: $SourceWorld"
    exit 2
}

# ---- 1. Prep run dir: copy the owner's world in FRESH (never mutate the source) ---------------
New-Item -ItemType Directory -Force -Path (Join-Path $runDir "config") | Out-Null
$world = Join-Path $runDir "world"
if (Test-Path $world) {
    Write-Host "[replay] removing previous run/replay/world"
    Remove-Item -Recurse -Force $world
}
Write-Host "[replay] copying owner's world: $SourceWorld -> $world"
# robocopy (not Copy-Item) so an open server's held session.lock in the SOURCE doesn't abort the copy — we
# skip it explicitly (the server rewrites its own lock anyway). /E recurse incl empty, /NFL/NDL/NJH/NJS quiet.
$null = robocopy $SourceWorld $world /E /XF session.lock /NFL /NDL /NJH /NJS /NP
if ($LASTEXITCODE -ge 8) { Write-Error "robocopy failed ($LASTEXITCODE)"; exit 2 }
$global:LASTEXITCODE = 0   # robocopy uses low exit codes as success; reset so nothing downstream trips

if (Test-Path $resultFile) { Remove-Item -Force $resultFile }
Get-ChildItem -Path $runDir -Filter "orebit-replay-trace*.txt" -ErrorAction SilentlyContinue | Remove-Item -Force

Copy-Item (Join-Path $templates "server.properties") (Join-Path $runDir "server.properties") -Force
Copy-Item (Join-Path $templates "eula.txt")          (Join-Path $runDir "eula.txt") -Force
Copy-Item (Join-Path $templates "orebit.properties") (Join-Path $runDir "config\orebit.properties") -Force

# ---- 2. Run -----------------------------------------------------------------------------------
$gradleArgs = @(":fabric:${McVersion}:runReplay")
if ($BotDebug) { $gradleArgs += "-Porebit.replay.debug=true" }
if ($Bleed -ne "") { $gradleArgs += "-Porebit.swim.bleed=$Bleed" }

Push-Location $repo
try {
    Write-Host "[replay] re-asserting active Stonecutter project ($McVersion)"
    & (Join-Path $repo "gradlew.bat") "Set active project to $McVersion"
    if ($LASTEXITCODE -ne 0) { Write-Error "Set active project failed"; exit 2 }

    Write-Host "[replay] launching headless server: gradlew $($gradleArgs -join ' ')"
    & (Join-Path $repo "gradlew.bat") @gradleArgs
    Write-Host "[replay] gradle exited with code $LASTEXITCODE"
} finally {
    Pop-Location
}

# ---- 3. Report --------------------------------------------------------------------------------
if (-not (Test-Path $resultFile)) {
    Write-Error ("no result file at $resultFile -- the server crashed, the world failed to load, or the hook " +
                 "never armed. Check run/replay/logs/latest.log for [Orebit/replay] lines.")
    exit 2
}

Write-Host ""
Write-Host "===== Orebit world-replay result ====="
Get-Content $resultFile | ForEach-Object { Write-Host "  $_" }
Write-Host "======================================"
Write-Host "[replay] trajectory dump: $runDir\orebit-replay-trace.txt"
exit 0
