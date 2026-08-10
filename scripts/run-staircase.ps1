# Orebit headless STAIRCASE-COURSE orchestrator (mc-1.21 era, Fabric).
# Location: <repo>/scripts/run-staircase.ps1, templates in <repo>/scripts/staircase/.
#
# What it does (mirrors run-gate.ps1):
#   1. resets run/staircase to a deterministic state (deletes the flat world, drops the eula/server/bot-config
#      templates, clears the stale result + trace files),
#   2. runs :fabric:<ver>:runStaircase (dedicated server, headless),
#   3. prints run/staircase/orebit-staircase-result.properties (the per-trial PASS/FAIL table).
#
# WHAT IT IS FOR: reproducing the 2026-08-10 iterated-Ascend wedge -- after a couple of one-block hops the
# bot ends up GROUNDED ONE BLOCK ABOVE the Ascend's intended landing and its failWhen envelope holds it
# there forever. Tiles: step1 (single-Ascend control), step2/step4/step8 (back-to-back rises), snow4 (the
# field's exact grass+snow[layers=1] surface), spaced4 (flat runs between rises -- the adjacency
# discriminator).
#
# The trajectory dump is run/staircase/orebit-staircase-trace.txt (per-tick pos/vel + onGround, the feet
# height the bot's own column SHOULD stand at, and the signed difference while grounded). The pathology
# reads as `above` climbing to ~+1.0; a clean climb stays ~0. ABOVE lines mark each new worst overshoot,
# and every result line carries maxAbove=<n>@(x,y,expect) whether the tile passed or failed.
#
# Exit codes: 0 = course completed AND every trial passed, 1 = course completed with failures (read the
# table), 2 = no result file (crash / hook never armed).
# Requires JAVA_HOME -> JDK 21 (the 1.21.11 node).  Windows PowerShell 5.1 compatible.

param(
    [string]$McVersion = "1.21.11",
    [switch]$BotDebug,
    [string]$GroundDrive = ""   # "" = build-default; "servo" | "legacy" forces drive()'s land branch (Stage-2 A/B)
)

$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$runDir = Join-Path $repo "run\staircase"
$templates = Join-Path $PSScriptRoot "staircase"
$resultFile = Join-Path $runDir "orebit-staircase-result.properties"

# ---- 1. Deterministic run-dir state ----------------------------------------------------------
New-Item -ItemType Directory -Force -Path (Join-Path $runDir "config") | Out-Null
$world = Join-Path $runDir "world"
if (Test-Path $world) {
    Write-Host "[staircase] deleting previous world (fresh flat gen)"
    Remove-Item -Recurse -Force $world
}
if (Test-Path $resultFile) { Remove-Item -Force $resultFile }
Get-ChildItem -Path $runDir -Filter "orebit-staircase-trace*.txt" -ErrorAction SilentlyContinue | Remove-Item -Force

Copy-Item (Join-Path $templates "server.properties") (Join-Path $runDir "server.properties") -Force
Copy-Item (Join-Path $templates "eula.txt")          (Join-Path $runDir "eula.txt") -Force
Copy-Item (Join-Path $templates "orebit.properties") (Join-Path $runDir "config\orebit.properties") -Force

# ---- 2. Run -----------------------------------------------------------------------------------
$gradleArgs = @(":fabric:${McVersion}:runStaircase")
if ($BotDebug) { $gradleArgs += "-Porebit.staircase.debug=true" }
if ($GroundDrive -ne "") { $gradleArgs += "-Porebit.ground.drive=$GroundDrive" }

Push-Location $repo
try {
    Write-Host "[staircase] re-asserting active Stonecutter project ($McVersion)"
    & (Join-Path $repo "gradlew.bat") "Set active project to $McVersion"
    if ($LASTEXITCODE -ne 0) { Write-Error "Set active project failed"; exit 2 }

    Write-Host "[staircase] launching headless server: gradlew $($gradleArgs -join ' ')"
    & (Join-Path $repo "gradlew.bat") @gradleArgs
    Write-Host "[staircase] gradle exited with code $LASTEXITCODE"
} finally {
    Pop-Location
}

# ---- 3. Report --------------------------------------------------------------------------------
if (-not (Test-Path $resultFile)) {
    Write-Error ("no result file at $resultFile -- the server crashed or the hook never armed. " +
                 "Check run/staircase/logs/latest.log for [Orebit/staircase] lines.")
    exit 2
}

Write-Host ""
Write-Host "===== Orebit staircase course result ====="
Get-Content $resultFile | ForEach-Object { Write-Host "  $_" }
Write-Host "=========================================="
Write-Host "[staircase] trajectory dump: $runDir\orebit-staircase-trace.txt"

$failedLine = (Get-Content $resultFile | Where-Object { $_ -match "^failed=(\d+)$" } | Select-Object -First 1)
if ($failedLine -match "^failed=0$") { exit 0 }
exit 1
