# Orebit headless SWIM-COURSE orchestrator (mc-1.21 era, Fabric).
# Location: <repo>/scripts/run-swim.ps1, templates in <repo>/scripts/swim/.
#
# What it does (mirrors run-parkour.ps1):
#   1. resets run/swim to a deterministic state (deletes the flat world, drops the eula/server/bot-config
#      templates, clears the stale result + trace files),
#   2. runs :fabric:<ver>:runSwim (dedicated server, headless),
#   3. prints run/swim/orebit-swim-result.properties (the per-trial PASS/FAIL table).
#
# The trajectory dump is run/swim/orebit-swim-trace.txt (per-tick pos/vel + water state: onGround/inWater/
# underWater/prone-swimming/sprinting, with MOVE and POSE (STAND<->PRONE) transition lines) — the record for
# diagnosing the catatonic / bottom-oscillation / can't-exit / wrong-pose-in-gap pathologies.
#
# Exit codes: 0 = course completed (read the table for per-trial verdicts), 2 = no result file (crash).
# Requires JAVA_HOME -> JDK 21 (the 1.21.11 node).  Windows PowerShell 5.1 compatible.

param(
    [string]$McVersion = "1.21.11",
    [switch]$BotDebug,
    [string]$Bleed = "",
    [string]$GroundDrive = ""   # "" = build-default; "servo" | "legacy" forces drive()'s land branch (Stage-2 A/B)
)

$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$runDir = Join-Path $repo "run\swim"
$templates = Join-Path $PSScriptRoot "swim"
$resultFile = Join-Path $runDir "orebit-swim-result.properties"

# ---- 1. Deterministic run-dir state ----------------------------------------------------------
New-Item -ItemType Directory -Force -Path (Join-Path $runDir "config") | Out-Null
$world = Join-Path $runDir "world"
if (Test-Path $world) {
    Write-Host "[swim] deleting previous world (fresh flat gen)"
    Remove-Item -Recurse -Force $world
}
if (Test-Path $resultFile) { Remove-Item -Force $resultFile }
Get-ChildItem -Path $runDir -Filter "orebit-swim-trace*.txt" -ErrorAction SilentlyContinue | Remove-Item -Force

Copy-Item (Join-Path $templates "server.properties") (Join-Path $runDir "server.properties") -Force
Copy-Item (Join-Path $templates "eula.txt")          (Join-Path $runDir "eula.txt") -Force
Copy-Item (Join-Path $templates "orebit.properties") (Join-Path $runDir "config\orebit.properties") -Force

# ---- 2. Run -----------------------------------------------------------------------------------
$gradleArgs = @(":fabric:${McVersion}:runSwim")
if ($BotDebug) { $gradleArgs += "-Porebit.swim.debug=true" }
if ($Bleed -ne "") { $gradleArgs += "-Porebit.swim.bleed=$Bleed" }
if ($GroundDrive -ne "") { $gradleArgs += "-Porebit.ground.drive=$GroundDrive" }

Push-Location $repo
try {
    Write-Host "[swim] re-asserting active Stonecutter project ($McVersion)"
    & (Join-Path $repo "gradlew.bat") "Set active project to $McVersion"
    if ($LASTEXITCODE -ne 0) { Write-Error "Set active project failed"; exit 2 }

    Write-Host "[swim] launching headless server: gradlew $($gradleArgs -join ' ')"
    & (Join-Path $repo "gradlew.bat") @gradleArgs
    Write-Host "[swim] gradle exited with code $LASTEXITCODE"
} finally {
    Pop-Location
}

# ---- 3. Report --------------------------------------------------------------------------------
if (-not (Test-Path $resultFile)) {
    Write-Error ("no result file at $resultFile -- the server crashed or the hook never armed. " +
                 "Check run/swim/logs/latest.log for [Orebit/swim] lines.")
    exit 2
}

Write-Host ""
Write-Host "===== Orebit swim course result ====="
Get-Content $resultFile | ForEach-Object { Write-Host "  $_" }
Write-Host "====================================="
Write-Host "[swim] trajectory dump: $runDir\orebit-swim-trace.txt"
exit 0
