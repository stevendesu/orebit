# Orebit headless ICE-COURSE orchestrator (mc-1.21 era, Fabric).
# Location: <repo>/scripts/run-ice.ps1, templates in <repo>/scripts/ice/.
#
# What it does (mirrors run-parkour.ps1 / run-swim.ps1):
#   1. resets run/ice to a deterministic state (deletes the flat world, drops the eula/server/bot-config
#      templates, clears the stale result + trace files),
#   2. runs :fabric:<ver>:runIce (dedicated server, headless),
#   3. prints run/ice/orebit-ice-result.properties (the per-trial PASS/FAIL table).
#
# The trajectory dump is run/ice/orebit-ice-trace.txt (per-tick pos/vel + onGround/inLava/hp, with MOVE/
# waypoint transition lines) — the record for diagnosing the near-frictionless-turn OVERSHOOT (the bot sliding
# off a 1-wide blue-ice path into the flank lava at a corner).
#
# Exit codes: 0 = course completed (read the table for per-trial verdicts), 2 = no result file (crash).
# Requires JAVA_HOME -> JDK 21 (the 1.21.11 node).  Windows PowerShell 5.1 compatible.

param(
    [string]$McVersion = "1.21.11",
    [switch]$BotDebug
)

$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$runDir = Join-Path $repo "run\ice"
$templates = Join-Path $PSScriptRoot "ice"
$resultFile = Join-Path $runDir "orebit-ice-result.properties"

# ---- 1. Deterministic run-dir state ----------------------------------------------------------
New-Item -ItemType Directory -Force -Path (Join-Path $runDir "config") | Out-Null
$world = Join-Path $runDir "world"
if (Test-Path $world) {
    Write-Host "[ice] deleting previous world (fresh flat gen)"
    Remove-Item -Recurse -Force $world
}
if (Test-Path $resultFile) { Remove-Item -Force $resultFile }
Get-ChildItem -Path $runDir -Filter "orebit-ice-trace*.txt" -ErrorAction SilentlyContinue | Remove-Item -Force

Copy-Item (Join-Path $templates "server.properties") (Join-Path $runDir "server.properties") -Force
Copy-Item (Join-Path $templates "eula.txt")          (Join-Path $runDir "eula.txt") -Force
Copy-Item (Join-Path $templates "orebit.properties") (Join-Path $runDir "config\orebit.properties") -Force

# ---- 2. Run -----------------------------------------------------------------------------------
$gradleArgs = @(":fabric:${McVersion}:runIce")
if ($BotDebug) { $gradleArgs += "-Porebit.ice.debug=true" }

Push-Location $repo
try {
    Write-Host "[ice] re-asserting active Stonecutter project ($McVersion)"
    & (Join-Path $repo "gradlew.bat") "Set active project to $McVersion"
    if ($LASTEXITCODE -ne 0) { Write-Error "Set active project failed"; exit 2 }

    Write-Host "[ice] launching headless server: gradlew $($gradleArgs -join ' ')"
    & (Join-Path $repo "gradlew.bat") @gradleArgs
    Write-Host "[ice] gradle exited with code $LASTEXITCODE"
} finally {
    Pop-Location
}

# ---- 3. Report --------------------------------------------------------------------------------
if (-not (Test-Path $resultFile)) {
    Write-Error ("no result file at $resultFile -- the server crashed or the hook never armed. " +
                 "Check run/ice/logs/latest.log for [Orebit/ice] lines.")
    exit 2
}

Write-Host ""
Write-Host "===== Orebit ice course result ====="
Get-Content $resultFile | ForEach-Object { Write-Host "  $_" }
Write-Host "===================================="
Write-Host "[ice] trajectory dump: $runDir\orebit-ice-trace.txt"
exit 0
