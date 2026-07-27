# Orebit headless BOXED-IN orchestrator (mc-1.21 era, Fabric).
# Location: <repo>/scripts/run-boxedin.ps1, templates in <repo>/scripts/boxedin/.
#
# What it does (mirrors run-parkour.ps1):
#   1. resets run/boxedin to a deterministic state (deletes the flat world, drops the
#      eula/server/bot-config templates, clears the stale result file),
#   2. runs :fabric:<ver>:runBoxedin (dedicated server, headless),
#   3. prints run/boxedin/orebit-boxedin-result.properties and asserts overall result=PASS.
#
# The harness builds a SEALED bedrock tomb + an OPEN stone platform and drives one goto to each,
# proving the multi-level proactive boxed-in scan gives up honestly on the sealed goal
# (tomb_result=PASS = navGaveUp AND boxedInProven) and reaches the open goal (open_result=PASS).
#
# Exit codes: 0 = overall result=PASS, 1 = completed but result=FAIL (read the per-scenario fields),
#             2 = no result file (crash or the hook never armed).
# Requires JAVA_HOME -> JDK 21 (the 1.21.11 node).  Windows PowerShell 5.1 compatible.

param(
    [string]$McVersion = "1.21.11",
    [switch]$BotDebug
)

$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$runDir = Join-Path $repo "run\boxedin"
$templates = Join-Path $PSScriptRoot "boxedin"
$resultFile = Join-Path $runDir "orebit-boxedin-result.properties"

# ---- 1. Deterministic run-dir state ----------------------------------------------------------
New-Item -ItemType Directory -Force -Path (Join-Path $runDir "config") | Out-Null
$world = Join-Path $runDir "world"
if (Test-Path $world) {
    Write-Host "[boxedin] deleting previous world (fresh flat gen)"
    Remove-Item -Recurse -Force $world
}
if (Test-Path $resultFile) { Remove-Item -Force $resultFile }

Copy-Item (Join-Path $templates "server.properties") (Join-Path $runDir "server.properties") -Force
Copy-Item (Join-Path $templates "eula.txt")          (Join-Path $runDir "eula.txt") -Force
Copy-Item (Join-Path $templates "orebit.properties") (Join-Path $runDir "config\orebit.properties") -Force

# ---- 2. Run -----------------------------------------------------------------------------------
$gradleArgs = @(":fabric:${McVersion}:runBoxedin")
if ($BotDebug) { $gradleArgs += "-Porebit.boxedin.debug=true" }

Push-Location $repo
try {
    Write-Host "[boxedin] re-asserting active Stonecutter project ($McVersion)"
    & (Join-Path $repo "gradlew.bat") "Set active project to $McVersion"
    if ($LASTEXITCODE -ne 0) { Write-Error "Set active project failed"; exit 2 }

    Write-Host "[boxedin] launching headless server: gradlew $($gradleArgs -join ' ')"
    & (Join-Path $repo "gradlew.bat") @gradleArgs
    Write-Host "[boxedin] gradle exited with code $LASTEXITCODE"
} finally {
    Pop-Location
}

# ---- 3. Report + assert -----------------------------------------------------------------------
if (-not (Test-Path $resultFile)) {
    Write-Error ("no result file at $resultFile -- the server crashed or the hook never armed. " +
                 "Check run/boxedin/logs/latest.log for [Orebit/boxedin] lines.")
    exit 2
}

Write-Host ""
Write-Host "===== Orebit boxed-in result ====="
Get-Content $resultFile | ForEach-Object { Write-Host "  $_" }
Write-Host "=================================="

$props = @{}
Get-Content $resultFile | ForEach-Object {
    if ($_ -match '^\s*([^=]+?)\s*=\s*(.*)$') { $props[$Matches[1]] = $Matches[2] }
}
$overall = $props["result"]
Write-Host "[boxedin] tomb=$($props['tomb_result']) (gaveUp=$($props['tomb_gaveUp']) boxedIn=$($props['tomb_boxedIn'])) : $($props['tomb_reason'])"
Write-Host "[boxedin] open=$($props['open_result']) : $($props['open_reason'])"
Write-Host "[boxedin] overall result=$overall"

if ($overall -eq "PASS") { exit 0 } else { exit 1 }
