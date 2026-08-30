# Orebit headless VINE-BRIDGE / VINE-GRAB orchestrator (mc-1.21 era, Fabric).
# Location: <repo>/scripts/run-vinebridge.ps1 — piggy-backs on the replan run task + templates
# (VineBridgeCourse arms on -Dorebit.vinebridge and suppresses ReplanCourse; see fabric/build.gradle.kts).
#
# Variants (-Variant):
#   feet  (default) — the original curtain crossing: SPAN vines at feet level on a north wall.
#   floor           — vines as the FLOOR cell (Traverse-owned shape).
#   grab            — the flagship (431,66,606) Climb-overshoot wedge replayed deterministically:
#                     up-step takeoff → cocoa-pod floor (top 12/16) + vine curtain on a jungle trunk →
#                     down-step. Pre-servo-fix EXPECTED result: FAIL budget exhausted, finalPos
#                     z ≈ +6.03 / y ≈ Y0+1.75, with `recenter:dead` → 180° flip → `step FAILED
#                     (validity envelope) Climb` in the log — the flagship signature 1:1.
#   pathdiag        — the flagship (1215,65,1223) Diagonal cell-quantization wedge: a village dirt path
#                     (top 15/16) crossing a grass field diagonally, full grass at every crossing
#                     corner. Pre-envelope-fix EXPECTED: FAIL budget exhausted on the diagonal with
#                     botY = Y0+1.000 exactly and `step FAILED (validity envelope) Diagonal` in the log.
#
# What it does (mirrors run-replan.ps1):
#   1. resets run/replan to a deterministic state (fresh flat world, replan templates, stale results cleared),
#   2. runs :fabric:<ver>:runReplan with -Porebit.vinebridge=<variant>,
#   3. prints run/replan/orebit-vinebridge-result.properties.
#
# Trajectory dump: run/replan/orebit-vinebridge-trace.txt. Full exec/PLAN detail: run/replan/logs/latest.log.
# Exit codes: 0 = PASS, 1 = completed with FAIL (read the reason), 2 = no result file (crash / never armed).
# Requires JAVA_HOME -> JDK 21 (the 1.21.11 node).  Windows PowerShell 5.1 compatible.

param(
    [string]$McVersion = "1.21.11",
    [string]$Variant = "feet",   # feet | floor | grab
    [switch]$Sync                # pathing.async=false in the copied orebit.properties
)

$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$runDir = Join-Path $repo "run\replan"
$templates = Join-Path $PSScriptRoot "replan"
$resultFile = Join-Path $runDir "orebit-vinebridge-result.properties"

# ---- 1. Deterministic run-dir state ----------------------------------------------------------
New-Item -ItemType Directory -Force -Path (Join-Path $runDir "config") | Out-Null
$world = Join-Path $runDir "world"
if (Test-Path $world) {
    Write-Host "[vinebridge] deleting previous world (fresh flat gen)"
    Remove-Item -Recurse -Force $world
}
if (Test-Path $resultFile) { Remove-Item -Force $resultFile }
Get-ChildItem -Path $runDir -Filter "orebit-vinebridge-trace*.txt" -ErrorAction SilentlyContinue | Remove-Item -Force

Copy-Item (Join-Path $templates "server.properties") (Join-Path $runDir "server.properties") -Force
Copy-Item (Join-Path $templates "eula.txt")          (Join-Path $runDir "eula.txt") -Force
$botConfig = Join-Path $runDir "config\orebit.properties"
Copy-Item (Join-Path $templates "orebit.properties") $botConfig -Force
if ($Sync) {
    Write-Host "[vinebridge] -Sync: patching pathing.async=false into the copied orebit.properties"
    $patched = (Get-Content $botConfig) -replace '^pathing\.async=.*$', 'pathing.async=false'
    Set-Content -Path $botConfig -Value $patched -Encoding ASCII
}

# ---- 2. Run -----------------------------------------------------------------------------------
$gradleArgs = @(":fabric:${McVersion}:runReplan", "-Porebit.vinebridge=$Variant")

Push-Location $repo
try {
    Write-Host "[vinebridge] re-asserting active Stonecutter project ($McVersion)"
    & (Join-Path $repo "gradlew.bat") "Set active project to $McVersion"
    if ($LASTEXITCODE -ne 0) { Write-Error "Set active project failed"; exit 2 }

    Write-Host "[vinebridge] launching headless server: gradlew $($gradleArgs -join ' ')"
    & (Join-Path $repo "gradlew.bat") @gradleArgs
    Write-Host "[vinebridge] gradle exited with code $LASTEXITCODE"
} finally {
    Pop-Location
}

# ---- 3. Report --------------------------------------------------------------------------------
if (-not (Test-Path $resultFile)) {
    Write-Error ("no result file at $resultFile -- the server crashed or the hook never armed. " +
                 "Check run/replan/logs/latest.log for [Orebit/vinebridge] lines.")
    exit 2
}
Write-Host "===== Orebit vinebridge result ($Variant) ====="
Get-Content $resultFile | ForEach-Object { Write-Host "  $_" }
Write-Host "==============================================="
$reason = (Get-Content $resultFile | Where-Object { $_ -match '^reason=' }) -replace '^reason=', ''
if ($reason -like 'PASS*') { exit 0 } else { exit 1 }
