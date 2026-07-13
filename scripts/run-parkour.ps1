# Orebit headless PARKOUR-COURSE orchestrator (mc-1.21 era, Fabric).
# Location: <repo>/scripts/run-parkour.ps1, templates in <repo>/scripts/parkour/.
#
# What it does (mirrors run-autotest.ps1):
#   1. resets run/parkour to a deterministic state (deletes the flat world, drops the
#      eula/server/bot-config templates, clears the stale result + trace files),
#   2. runs :fabric:<ver>:runParkour (dedicated server, headless),
#   3. prints run/parkour/orebit-parkour-result.properties (the per-trial PASS/FAIL table + takeoff speeds).
#
# The trajectory dump is run/parkour/orebit-parkour-trace.txt (per-tick pos/vel/onGround, with a TAKEOFF
# line marking the ground->air flip and the achieved horizontal speed — the envelope's key assumption).
#
# Exit codes: 0 = course completed (read the table for per-trial verdicts), 2 = no result file (crash).
# Requires JAVA_HOME -> JDK 21 (the 1.21.11 node).  Windows PowerShell 5.1 compatible.

param(
    [string]$McVersion = "1.21.11",
    [switch]$BotDebug,
    [string]$GroundDrive = ""   # "" = build-default; "servo" | "legacy" (parkour bypasses drive(); no-regression A/B)
)

$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$runDir = Join-Path $repo "run\parkour"
$templates = Join-Path $PSScriptRoot "parkour"
$resultFile = Join-Path $runDir "orebit-parkour-result.properties"

# ---- 1. Deterministic run-dir state ----------------------------------------------------------
New-Item -ItemType Directory -Force -Path (Join-Path $runDir "config") | Out-Null
$world = Join-Path $runDir "world"
if (Test-Path $world) {
    Write-Host "[parkour] deleting previous world (fresh flat gen)"
    Remove-Item -Recurse -Force $world
}
if (Test-Path $resultFile) { Remove-Item -Force $resultFile }
Get-ChildItem -Path $runDir -Filter "orebit-parkour-trace*.txt" -ErrorAction SilentlyContinue | Remove-Item -Force

Copy-Item (Join-Path $templates "server.properties") (Join-Path $runDir "server.properties") -Force
Copy-Item (Join-Path $templates "eula.txt")          (Join-Path $runDir "eula.txt") -Force
Copy-Item (Join-Path $templates "orebit.properties") (Join-Path $runDir "config\orebit.properties") -Force

# ---- 2. Run -----------------------------------------------------------------------------------
$gradleArgs = @(":fabric:${McVersion}:runParkour")
if ($BotDebug) { $gradleArgs += "-Porebit.parkour.debug=true" }
if ($GroundDrive -ne "") { $gradleArgs += "-Porebit.ground.drive=$GroundDrive" }

Push-Location $repo
try {
    Write-Host "[parkour] re-asserting active Stonecutter project ($McVersion)"
    & (Join-Path $repo "gradlew.bat") "Set active project to $McVersion"
    if ($LASTEXITCODE -ne 0) { Write-Error "Set active project failed"; exit 2 }

    Write-Host "[parkour] launching headless server: gradlew $($gradleArgs -join ' ')"
    & (Join-Path $repo "gradlew.bat") @gradleArgs
    Write-Host "[parkour] gradle exited with code $LASTEXITCODE"
} finally {
    Pop-Location
}

# ---- 3. Report --------------------------------------------------------------------------------
if (-not (Test-Path $resultFile)) {
    Write-Error ("no result file at $resultFile -- the server crashed or the hook never armed. " +
                 "Check run/parkour/logs/latest.log for [Orebit/parkour] lines.")
    exit 2
}

Write-Host ""
Write-Host "===== Orebit parkour course result ====="
Get-Content $resultFile | ForEach-Object { Write-Host "  $_" }
Write-Host "========================================"
Write-Host "[parkour] trajectory dump: $runDir\orebit-parkour-trace.txt"
exit 0
