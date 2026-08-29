# Orebit headless BAMBOO-COURSE orchestrator (mc-1.21 era, Fabric).
# Location: <repo>/scripts/run-bamboo.ps1, templates in <repo>/scripts/bamboo/.
#
# What it does (mirrors run-trapdoor.ps1):
#   1. resets run/bamboo to a deterministic state (deletes the flat world, drops the eula/server/bot-config
#      templates, clears the stale result + trace files),
#   2. runs :fabric:<ver>:runBamboo (dedicated server, headless),
#   3. prints run/bamboo/orebit-bamboo-result.properties (the per-trial PASS/FAIL table).
#
# The trajectory dump is run/bamboo/orebit-bamboo-trace.txt (per-tick pos/vel + onGround/hp + the live
# state of the tile's FEET-cell bamboo, with MOVE transition lines, per-tile PROBE dumps of the placed
# BlockState and the computed sub-cell post span, and a BREAK marker the tick the stalk is finally mined).
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
$runDir = Join-Path $repo "run\bamboo"
$templates = Join-Path $PSScriptRoot "bamboo"
$resultFile = Join-Path $runDir "orebit-bamboo-result.properties"

# ---- 1. Deterministic run-dir state ----------------------------------------------------------
New-Item -ItemType Directory -Force -Path (Join-Path $runDir "config") | Out-Null
$world = Join-Path $runDir "world"
if (Test-Path $world) {
    Write-Host "[bamboo] deleting previous world (fresh flat gen)"
    Remove-Item -Recurse -Force $world
}
if (Test-Path $resultFile) { Remove-Item -Force $resultFile }
Get-ChildItem -Path $runDir -Filter "orebit-bamboo-trace*.txt" -ErrorAction SilentlyContinue | Remove-Item -Force

Copy-Item (Join-Path $templates "server.properties") (Join-Path $runDir "server.properties") -Force
Copy-Item (Join-Path $templates "eula.txt")          (Join-Path $runDir "eula.txt") -Force
Copy-Item (Join-Path $templates "orebit.properties") (Join-Path $runDir "config\orebit.properties") -Force

# ---- 2. Run -----------------------------------------------------------------------------------
$gradleArgs = @(":fabric:${McVersion}:runBamboo")
if ($BotDebug) { $gradleArgs += "-Porebit.bamboo.debug=true" }
if ($GroundDrive -ne "") { $gradleArgs += "-Porebit.ground.drive=$GroundDrive" }

Push-Location $repo
try {
    Write-Host "[bamboo] re-asserting active Stonecutter project ($McVersion)"
    & (Join-Path $repo "gradlew.bat") "Set active project to $McVersion"
    if ($LASTEXITCODE -ne 0) { Write-Error "Set active project failed"; exit 2 }

    Write-Host "[bamboo] launching headless server: gradlew $($gradleArgs -join ' ')"
    & (Join-Path $repo "gradlew.bat") @gradleArgs
    Write-Host "[bamboo] gradle exited with code $LASTEXITCODE"
} finally {
    Pop-Location
}

# ---- 3. Report --------------------------------------------------------------------------------
if (-not (Test-Path $resultFile)) {
    Write-Error ("no result file at $resultFile -- the server crashed or the hook never armed. " +
                 "Check run/bamboo/logs/latest.log for [Orebit/bamboo] lines.")
    exit 2
}

Write-Host ""
Write-Host "===== Orebit bamboo course result ====="
Get-Content $resultFile | ForEach-Object { Write-Host "  $_" }
Write-Host "====================================="
Write-Host "[bamboo] trajectory dump: $runDir\orebit-bamboo-trace.txt"

$failedLine = (Get-Content $resultFile | Where-Object { $_ -match "^failed=(\d+)$" } | Select-Object -First 1)
if ($failedLine -match "^failed=0$") { exit 0 }
exit 1
