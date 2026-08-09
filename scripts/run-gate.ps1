# Orebit headless GATE-COURSE orchestrator (mc-1.21 era, Fabric).
# Location: <repo>/scripts/run-gate.ps1, templates in <repo>/scripts/gate/.
#
# What it does (mirrors run-trapdoor.ps1):
#   1. resets run/gate to a deterministic state (deletes the flat world, drops the eula/server/bot-config
#      templates, clears the stale result + trace files),
#   2. runs :fabric:<ver>:runGate (dedicated server, headless),
#   3. prints run/gate/orebit-gate-result.properties (the per-trial PASS/FAIL table).
#
# The trajectory dump is run/gate/orebit-gate-trace.txt (per-tick pos/vel + onGround/hp + the live OPEN
# state of the tile's fence gate, with MOVE/waypoint transition lines, per-tile PROBE dumps of the placed
# BlockState, GATE state-transition markers, the exttoggle tile's EXTFLIP/REOPEN markers, and the
# toggleoff tile's CONFIG marker for the doors.toggle=false runtime install).
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
$runDir = Join-Path $repo "run\gate"
$templates = Join-Path $PSScriptRoot "gate"
$resultFile = Join-Path $runDir "orebit-gate-result.properties"

# ---- 1. Deterministic run-dir state ----------------------------------------------------------
New-Item -ItemType Directory -Force -Path (Join-Path $runDir "config") | Out-Null
$world = Join-Path $runDir "world"
if (Test-Path $world) {
    Write-Host "[gate] deleting previous world (fresh flat gen)"
    Remove-Item -Recurse -Force $world
}
if (Test-Path $resultFile) { Remove-Item -Force $resultFile }
Get-ChildItem -Path $runDir -Filter "orebit-gate-trace*.txt" -ErrorAction SilentlyContinue | Remove-Item -Force

Copy-Item (Join-Path $templates "server.properties") (Join-Path $runDir "server.properties") -Force
Copy-Item (Join-Path $templates "eula.txt")          (Join-Path $runDir "eula.txt") -Force
Copy-Item (Join-Path $templates "orebit.properties") (Join-Path $runDir "config\orebit.properties") -Force

# ---- 2. Run -----------------------------------------------------------------------------------
$gradleArgs = @(":fabric:${McVersion}:runGate")
if ($BotDebug) { $gradleArgs += "-Porebit.gate.debug=true" }
if ($GroundDrive -ne "") { $gradleArgs += "-Porebit.ground.drive=$GroundDrive" }

Push-Location $repo
try {
    Write-Host "[gate] re-asserting active Stonecutter project ($McVersion)"
    & (Join-Path $repo "gradlew.bat") "Set active project to $McVersion"
    if ($LASTEXITCODE -ne 0) { Write-Error "Set active project failed"; exit 2 }

    Write-Host "[gate] launching headless server: gradlew $($gradleArgs -join ' ')"
    & (Join-Path $repo "gradlew.bat") @gradleArgs
    Write-Host "[gate] gradle exited with code $LASTEXITCODE"
} finally {
    Pop-Location
}

# ---- 3. Report --------------------------------------------------------------------------------
if (-not (Test-Path $resultFile)) {
    Write-Error ("no result file at $resultFile -- the server crashed or the hook never armed. " +
                 "Check run/gate/logs/latest.log for [Orebit/gate] lines.")
    exit 2
}

Write-Host ""
Write-Host "===== Orebit gate course result ====="
Get-Content $resultFile | ForEach-Object { Write-Host "  $_" }
Write-Host "====================================="
Write-Host "[gate] trajectory dump: $runDir\orebit-gate-trace.txt"

$failedLine = (Get-Content $resultFile | Where-Object { $_ -match "^failed=(\d+)$" } | Select-Object -First 1)
if ($failedLine -match "^failed=0$") { exit 0 }
exit 1
