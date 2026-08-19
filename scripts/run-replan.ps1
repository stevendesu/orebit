# Orebit headless REPLAN-COURSE orchestrator (mc-1.21 era, Fabric).
# Location: <repo>/scripts/run-replan.ps1, templates in <repo>/scripts/replan/.
#
# What it does (mirrors run-trapdoor.ps1 / run-gate.ps1):
#   1. resets run/replan to a deterministic state (deletes the flat world, drops the eula/server/bot-config
#      templates, clears the stale result + trace files),
#   2. runs :fabric:<ver>:runReplan (dedicated server, headless),
#   3. prints run/replan/orebit-replan-result.properties (the per-trial PASS/FAIL table).
#
# BOTH pathing modes are first-class (DESIGN-replan-handoff.md §9.2 coverage = one run each): the default
# run is pathing.async=true (the async park/drain pump); -Sync rewrites the copied orebit.properties to
# pathing.async=false (the deterministic single-thread schedule). The course stamps the active mode into
# the result file (async=...).
#
# The trajectory dump is run/replan/orebit-replan-trace.txt (per-tick pos/spd + waypoint cursor/driveState,
# with SEAL/SWAP/CLASS/DROP markers — see the trace legend the course writes at the top).
#
# Exit codes: 0 = course completed AND every trial passed, 1 = course completed with failures (read the
# table), 2 = no result file (crash / hook never armed).
# Requires JAVA_HOME -> JDK 21 (the 1.21.11 node).  Windows PowerShell 5.1 compatible.

param(
    [string]$McVersion = "1.21.11",
    [switch]$BotDebug,
    [switch]$Sync,              # pathing.async=false in the copied orebit.properties (default run is async=true)
    [string]$GroundDrive = ""   # "" = build-default; "servo" | "legacy" forces drive()'s land branch (Stage-2 A/B)
)

$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$runDir = Join-Path $repo "run\replan"
$templates = Join-Path $PSScriptRoot "replan"
$resultFile = Join-Path $runDir "orebit-replan-result.properties"

# ---- 1. Deterministic run-dir state ----------------------------------------------------------
New-Item -ItemType Directory -Force -Path (Join-Path $runDir "config") | Out-Null
$world = Join-Path $runDir "world"
if (Test-Path $world) {
    Write-Host "[replan] deleting previous world (fresh flat gen)"
    Remove-Item -Recurse -Force $world
}
if (Test-Path $resultFile) { Remove-Item -Force $resultFile }
Get-ChildItem -Path $runDir -Filter "orebit-replan-trace*.txt" -ErrorAction SilentlyContinue | Remove-Item -Force

Copy-Item (Join-Path $templates "server.properties") (Join-Path $runDir "server.properties") -Force
Copy-Item (Join-Path $templates "eula.txt")          (Join-Path $runDir "eula.txt") -Force
$botConfig = Join-Path $runDir "config\orebit.properties"
Copy-Item (Join-Path $templates "orebit.properties") $botConfig -Force
if ($Sync) {
    Write-Host "[replan] -Sync: patching pathing.async=false into the copied orebit.properties"
    $patched = (Get-Content $botConfig) -replace '^pathing\.async=.*$', 'pathing.async=false'
    Set-Content -Path $botConfig -Value $patched -Encoding ASCII
}

# ---- 2. Run -----------------------------------------------------------------------------------
$gradleArgs = @(":fabric:${McVersion}:runReplan")
if ($BotDebug) { $gradleArgs += "-Porebit.replan.debug=true" }
if ($GroundDrive -ne "") { $gradleArgs += "-Porebit.ground.drive=$GroundDrive" }

Push-Location $repo
try {
    Write-Host "[replan] re-asserting active Stonecutter project ($McVersion)"
    & (Join-Path $repo "gradlew.bat") "Set active project to $McVersion"
    if ($LASTEXITCODE -ne 0) { Write-Error "Set active project failed"; exit 2 }

    Write-Host "[replan] launching headless server: gradlew $($gradleArgs -join ' ')"
    & (Join-Path $repo "gradlew.bat") @gradleArgs
    Write-Host "[replan] gradle exited with code $LASTEXITCODE"
} finally {
    Pop-Location
}

# ---- 3. Report --------------------------------------------------------------------------------
if (-not (Test-Path $resultFile)) {
    Write-Error ("no result file at $resultFile -- the server crashed or the hook never armed. " +
                 "Check run/replan/logs/latest.log for [Orebit/replan] lines.")
    exit 2
}

Write-Host ""
Write-Host "===== Orebit replan course result ====="
Get-Content $resultFile | ForEach-Object { Write-Host "  $_" }
Write-Host "======================================="
Write-Host "[replan] trajectory dump: $runDir\orebit-replan-trace.txt"

$failedLine = (Get-Content $resultFile | Where-Object { $_ -match "^failed=(\d+)$" } | Select-Object -First 1)
if ($failedLine -match "^failed=0$") { exit 0 }
exit 1
