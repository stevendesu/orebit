# Orebit headless ICE-PARKOUR-COURSE orchestrator (mc-1.21 era, Fabric).
# Location: <repo>/scripts/run-iceparkour.ps1, templates in <repo>/scripts/iceparkour/.
#
# What it does (mirrors run-ice.ps1 / run-parkour.ps1):
#   1. resets run/iceparkour to a deterministic state (deletes the flat world, drops the eula/server/bot-config
#      templates, clears the stale result + trace files),
#   2. runs :fabric:<ver>:runIceParkour (dedicated server, headless),
#   3. prints run/iceparkour/orebit-iceparkour-result.properties (the per-trial PASS/FAIL + overshoot table).
#
# The trajectory dump is run/iceparkour/orebit-iceparkour-trace.txt (per-tick pos/vel + onGround, with TAKEOFF/
# LAND markers) — the record for diagnosing the momentum-overshoot-onto-ice pathology.
#
# -Brake routes the parkour LAND phase through a follower brake PROTOTYPE (measurement only):
#   "servo" = the existing groundServo reverse-thrust; "brake" = a ramp-to-zero velocity brake.
#
# Exit codes: 0 = course completed (read the table), 2 = no result file (crash).
# Requires JAVA_HOME -> JDK 21 (the 1.21.11 node).  Windows PowerShell 5.1 compatible.

param(
    [string]$McVersion = "1.21.11",
    [switch]$BotDebug,
    [string]$Brake = ""         # "" = baseline; "servo" | "brake" arms the LAND-phase follower prototype
)

$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$runDir = Join-Path $repo "run\iceparkour"
$templates = Join-Path $PSScriptRoot "iceparkour"
$resultFile = Join-Path $runDir "orebit-iceparkour-result.properties"

# ---- 1. Deterministic run-dir state ----------------------------------------------------------
New-Item -ItemType Directory -Force -Path (Join-Path $runDir "config") | Out-Null
$world = Join-Path $runDir "world"
if (Test-Path $world) {
    Write-Host "[iceparkour] deleting previous world (fresh flat gen)"
    Remove-Item -Recurse -Force $world
}
if (Test-Path $resultFile) { Remove-Item -Force $resultFile }
Get-ChildItem -Path $runDir -Filter "orebit-iceparkour-trace*.txt" -ErrorAction SilentlyContinue | Remove-Item -Force

Copy-Item (Join-Path $templates "server.properties") (Join-Path $runDir "server.properties") -Force
Copy-Item (Join-Path $templates "eula.txt")          (Join-Path $runDir "eula.txt") -Force
Copy-Item (Join-Path $templates "orebit.properties") (Join-Path $runDir "config\orebit.properties") -Force

# ---- 2. Run -----------------------------------------------------------------------------------
$gradleArgs = @(":fabric:${McVersion}:runIceParkour")
if ($BotDebug) { $gradleArgs += "-Porebit.iceparkour.debug=true" }
if ($Brake -ne "") { $gradleArgs += "-Porebit.iceparkour.brake=$Brake" }

Push-Location $repo
try {
    Write-Host "[iceparkour] re-asserting active Stonecutter project ($McVersion)"
    & (Join-Path $repo "gradlew.bat") "Set active project to $McVersion"
    if ($LASTEXITCODE -ne 0) { Write-Error "Set active project failed"; exit 2 }

    Write-Host "[iceparkour] launching headless server: gradlew $($gradleArgs -join ' ')"
    & (Join-Path $repo "gradlew.bat") @gradleArgs
    Write-Host "[iceparkour] gradle exited with code $LASTEXITCODE"
} finally {
    Pop-Location
}

# ---- 3. Report --------------------------------------------------------------------------------
if (-not (Test-Path $resultFile)) {
    Write-Error ("no result file at $resultFile -- the server crashed or the hook never armed. " +
                 "Check run/iceparkour/logs/latest.log for [Orebit/iceparkour] lines.")
    exit 2
}

Write-Host ""
Write-Host "===== Orebit ice-parkour course result ====="
Get-Content $resultFile | ForEach-Object { Write-Host "  $_" }
Write-Host "============================================"
Write-Host "[iceparkour] trajectory dump: $runDir\orebit-iceparkour-trace.txt"
exit 0
