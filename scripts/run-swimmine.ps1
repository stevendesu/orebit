# Orebit headless SWIM-MINE-COURSE orchestrator (mc-1.21 era, Fabric).
# Location: <repo>/scripts/run-swimmine.ps1, templates in <repo>/scripts/swimmine/.
#
# What it does (mirrors run-gate.ps1):
#   1. resets run/swimmine to a deterministic state (deletes the flat world, drops the eula/server/bot-config
#      templates, clears the stale result + trace files),
#   2. runs :fabric:<ver>:runSwimMine (dedicated server, headless),
#   3. prints run/swimmine/orebit-swimmine-result.properties (the per-trial PASS/FAIL/GAP table).
#
# What it proves: SteerControl.stationKeep's medium dispatch under REAL vanilla physics. The openwaterwall
# tile floats the bot in a sealed bedrock tank with EIGHT cells of water and nothing standable beneath it,
# and makes the only route east a flat Traverse whose two body cells are stone the bot must mine from that
# float. A hold that emits no inputs sinks at 0.025/tick, leaves the Traverse's admitted foot band in ~40
# ticks, trips failWhen and resets BotMining's progress -> the ~141-tick-per-cell break never completes and
# the tile times out. The depth autopilot holds the bot inside [footY+0.3, footY+0.7] indefinitely, so both
# cells break and the bot crosses. The hangplug tile is the CLIMBABLE control (sneak hold on a ladder while
# mining a lateral plug); it is GAP-tolerant because Climb folds no breaks and the block-tier
# ladder-lateral exit is not a shipped guarantee.
#
# The trajectory dump is run/swimmine/orebit-swimmine-trace.txt (per-tick pos/vel + onGround/inWater/hp +
# the running broken-plug count, with MOVE/waypoint transition lines, per-tile PROBE dumps of the built
# geometry and the PROVEN floor gap under the from-cell, and BREAK markers for each mined cell).
#
# Exit codes: 0 = course completed AND no trial failed (GAP does not fail the run), 1 = course completed
# with failures (read the table), 2 = no result file (crash / hook never armed).
# Requires JAVA_HOME -> JDK 21 (the 1.21.11 node).  Windows PowerShell 5.1 compatible.

param(
    [string]$McVersion = "1.21.11",
    [switch]$BotDebug,
    [string]$GroundDrive = ""   # "" = build-default; "servo" | "legacy" forces drive()'s land branch (Stage-2 A/B)
)

$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$runDir = Join-Path $repo "run\swimmine"
$templates = Join-Path $PSScriptRoot "swimmine"
$resultFile = Join-Path $runDir "orebit-swimmine-result.properties"

# ---- 1. Deterministic run-dir state ----------------------------------------------------------
New-Item -ItemType Directory -Force -Path (Join-Path $runDir "config") | Out-Null
$world = Join-Path $runDir "world"
if (Test-Path $world) {
    Write-Host "[swimmine] deleting previous world (fresh flat gen)"
    Remove-Item -Recurse -Force $world
}
if (Test-Path $resultFile) { Remove-Item -Force $resultFile }
Get-ChildItem -Path $runDir -Filter "orebit-swimmine-trace*.txt" -ErrorAction SilentlyContinue | Remove-Item -Force

Copy-Item (Join-Path $templates "server.properties") (Join-Path $runDir "server.properties") -Force
Copy-Item (Join-Path $templates "eula.txt")          (Join-Path $runDir "eula.txt") -Force
Copy-Item (Join-Path $templates "orebit.properties") (Join-Path $runDir "config\orebit.properties") -Force

# ---- 2. Run -----------------------------------------------------------------------------------
$gradleArgs = @(":fabric:${McVersion}:runSwimMine")
if ($BotDebug) { $gradleArgs += "-Porebit.swimmine.debug=true" }
if ($GroundDrive -ne "") { $gradleArgs += "-Porebit.ground.drive=$GroundDrive" }

Push-Location $repo
try {
    Write-Host "[swimmine] re-asserting active Stonecutter project ($McVersion)"
    & (Join-Path $repo "gradlew.bat") "Set active project to $McVersion"
    if ($LASTEXITCODE -ne 0) { Write-Error "Set active project failed"; exit 2 }

    Write-Host "[swimmine] launching headless server: gradlew $($gradleArgs -join ' ')"
    & (Join-Path $repo "gradlew.bat") @gradleArgs
    Write-Host "[swimmine] gradle exited with code $LASTEXITCODE"
} finally {
    Pop-Location
}

# ---- 3. Report --------------------------------------------------------------------------------
if (-not (Test-Path $resultFile)) {
    Write-Error ("no result file at $resultFile -- the server crashed or the hook never armed. " +
                 "Check run/swimmine/logs/latest.log for [Orebit/swimmine] lines.")
    exit 2
}

Write-Host ""
Write-Host "===== Orebit swim-mine course result ====="
Get-Content $resultFile | ForEach-Object { Write-Host "  $_" }
Write-Host "=========================================="
Write-Host "[swimmine] trajectory dump: $runDir\orebit-swimmine-trace.txt"

$failedLine = (Get-Content $resultFile | Where-Object { $_ -match "^failed=(\d+)$" } | Select-Object -First 1)
if ($failedLine -match "^failed=0$") { exit 0 }
exit 1
