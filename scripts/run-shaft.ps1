# Orebit headless SHAFT-COURSE orchestrator (mc-1.21 era, Fabric) — the trapdoor-ladder-climb arc.
# Location: <repo>/scripts/run-shaft.ps1, templates in <repo>/scripts/shaft/.
#
# What it does (mirrors run-gate.ps1):
#   1. resets run/shaft to a deterministic state (deletes the flat world, drops the eula/server/bot-config
#      templates, clears the stale result + trace files),
#   2. runs :fabric:<ver>:runShaft (dedicated server, headless),
#   3. prints run/shaft/orebit-shaft-result.properties (the per-trial verdict table).
#
# The trajectory dump is run/shaft/orebit-shaft-trace.txt (per-tick pos/vel + onGround/hp + the live OPEN
# state of the tile's mouth trapdoor, with MOVE/waypoint transition lines, per-tile PROBE dumps of the
# placed mouth + top-rung BlockStates — the facing double-check — and HATCH state-transition markers; every
# transition is a bot toggle, this course has no redstone and no external flips).
#
# Verdicts: PASS / FAIL / GAP. GAP is reserved for the two control-plain-* tiles (no trapdoor in the mouth):
# a no-route outcome there is the PRE-EXISTING plain-ladder-shaft gap (DESIGN-trapdoor-ladder-climb.md §3),
# NOT this arc's failure, so GAP does not count into failed=. The iron-closed control PASSES on an honest
# give-up (hatch untouched, zero toggles).
#
# Exit codes: 0 = course completed AND failed=0 (GAP lines are informational), 1 = course completed with
# failures (read the table), 2 = no result file (crash / hook never armed).
# Requires JAVA_HOME -> JDK 21 (the 1.21.11 node).  Windows PowerShell 5.1 compatible.

param(
    [string]$McVersion = "1.21.11",
    [switch]$BotDebug,
    [string]$GroundDrive = ""   # "" = build-default; "servo" | "legacy" forces drive()'s land branch (Stage-2 A/B)
)

$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$runDir = Join-Path $repo "run\shaft"
$templates = Join-Path $PSScriptRoot "shaft"
$resultFile = Join-Path $runDir "orebit-shaft-result.properties"

# ---- 1. Deterministic run-dir state ----------------------------------------------------------
New-Item -ItemType Directory -Force -Path (Join-Path $runDir "config") | Out-Null
$world = Join-Path $runDir "world"
if (Test-Path $world) {
    Write-Host "[shaft] deleting previous world (fresh flat gen)"
    Remove-Item -Recurse -Force $world
}
if (Test-Path $resultFile) { Remove-Item -Force $resultFile }
Get-ChildItem -Path $runDir -Filter "orebit-shaft-trace*.txt" -ErrorAction SilentlyContinue | Remove-Item -Force

Copy-Item (Join-Path $templates "server.properties") (Join-Path $runDir "server.properties") -Force
Copy-Item (Join-Path $templates "eula.txt")          (Join-Path $runDir "eula.txt") -Force
Copy-Item (Join-Path $templates "orebit.properties") (Join-Path $runDir "config\orebit.properties") -Force

# ---- 2. Run -----------------------------------------------------------------------------------
$gradleArgs = @(":fabric:${McVersion}:runShaft")
if ($BotDebug) { $gradleArgs += "-Porebit.shaft.debug=true" }
if ($GroundDrive -ne "") { $gradleArgs += "-Porebit.ground.drive=$GroundDrive" }

Push-Location $repo
try {
    Write-Host "[shaft] re-asserting active Stonecutter project ($McVersion)"
    & (Join-Path $repo "gradlew.bat") "Set active project to $McVersion"
    if ($LASTEXITCODE -ne 0) { Write-Error "Set active project failed"; exit 2 }

    Write-Host "[shaft] launching headless server: gradlew $($gradleArgs -join ' ')"
    & (Join-Path $repo "gradlew.bat") @gradleArgs
    Write-Host "[shaft] gradle exited with code $LASTEXITCODE"
} finally {
    Pop-Location
}

# ---- 3. Report --------------------------------------------------------------------------------
if (-not (Test-Path $resultFile)) {
    Write-Error ("no result file at $resultFile -- the server crashed or the hook never armed. " +
                 "Check run/shaft/logs/latest.log for [Orebit/shaft] lines.")
    exit 2
}

Write-Host ""
Write-Host "===== Orebit shaft course result ====="
Get-Content $resultFile | ForEach-Object { Write-Host "  $_" }
Write-Host "======================================"
Write-Host "[shaft] trajectory dump: $runDir\orebit-shaft-trace.txt"
Write-Host "[shaft] note: GAP lines are the control-plain-* tiles' expected pre-existing-gap outcome, not failures"

$failedLine = (Get-Content $resultFile | Where-Object { $_ -match "^failed=(\d+)$" } | Select-Object -First 1)
if ($failedLine -match "^failed=0$") { exit 0 }
exit 1
