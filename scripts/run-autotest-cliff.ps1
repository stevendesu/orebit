# Orebit CLIFF invalid-region-crossing repro (#5 invalidation-memory) — headless autotest wrapper.
# mc-1.21 era, Fabric, node 1.21.11, JDK 21. Mirrors scripts/run-autotest.ps1 but uses the no-capability
# bot config (scripts/autotest-cliff/orebit.properties: canPlace=false, canMine=false) and the frozen
# FLAT cliff master world (scripts/autotest-cliff/world-master/world), built via scripts/repro-cliff/.
#
# The scenario: bot spawns on the -X low ground at Start; goal is the +X cliff-top at Goal, a full region
# away, across a sheer 8-block wall (x=64) spanning the whole z-range (no walk-around). The region tier
# proposes the cliff crossing (caps-unconditional walk-portal); a no-place/no-break bot cannot realize it
# at the block tier -> BLOCKED -> repairBlocked() invalidates the crossing. Geometry + rebuild steps:
# scripts/repro-cliff/README.md.
#
# Exit codes: 0 = PASS (unexpected here), 1 = FAIL (result file says so — the EXPECTED give-up outcome),
#             2 = no result file (crash / hook never armed).
# Requires JAVA_HOME -> JDK 21. Windows PowerShell 5.1 compatible (no &&, no ternary).

param(
    [string]$McVersion  = "1.21.11",
    [string]$Start      = "16,1,0",     # low-ground feet cell
    [string]$Goal       = "144,9,0",    # cliff-top feet cell, a full (level-2 / 64-block) region away
    [int]$BudgetTicks   = 6000,         # ~5 min at 20 TPS — honest exhaustion needs ~3400+ ticks now that innocent crossings are not eaten
    [switch]$BotDebug,
    [switch]$Rtrace,                    # dump the region cascade (what the region tier proposes) + halt
    [string]$MasterWorld = ""           # default: scripts/autotest-cliff/world-master/world
)

$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$runDir = Join-Path $repo "run\autotest"
$templates = Join-Path $PSScriptRoot "autotest-cliff"
$resultFile = Join-Path $runDir "orebit-autotest-result.properties"
if ($MasterWorld -eq "") { $MasterWorld = Join-Path $templates "world-master\world" }

# ---- 1. Deterministic run-dir state (frozen-world mode) --------------------------------------
New-Item -ItemType Directory -Force -Path (Join-Path $runDir "config") | Out-Null
$world = Join-Path $runDir "world"
if (Test-Path $world) {
    Write-Host "[cliff] deleting previous run world (disposable copy)"
    Remove-Item -Recurse -Force $world
}
if (-not (Test-Path $MasterWorld)) { Write-Error "master world not found: $MasterWorld"; exit 2 }
Write-Host "[cliff] FROZEN-WORLD mode: copying master '$MasterWorld' -> run world"
Copy-Item -Recurse -Force $MasterWorld $world
$lock = Join-Path $world "session.lock"
if (Test-Path $lock) { Remove-Item -Force $lock }
if (Test-Path $resultFile) { Remove-Item -Force $resultFile }
Get-ChildItem -Path $runDir -Filter "orebit-autotest-trace-*.txt" -ErrorAction SilentlyContinue | Remove-Item -Force

Copy-Item (Join-Path $templates "server.properties") (Join-Path $runDir "server.properties") -Force
Copy-Item (Join-Path $templates "eula.txt")          (Join-Path $runDir "eula.txt") -Force
Copy-Item (Join-Path $templates "orebit.properties") (Join-Path $runDir "config\orebit.properties") -Force

# ---- 2. Run --------------------------------------------------------------------------------
$gradleArgs = @(":fabric:${McVersion}:runAutotest")
if ($Start -ne "")      { $gradleArgs += "-Porebit.autotest.start=$Start" }
if ($Goal -ne "")       { $gradleArgs += "-Porebit.autotest.goal=$Goal" }
if ($BudgetTicks -gt 0) { $gradleArgs += "-Porebit.autotest.budgetTicks=$BudgetTicks" }
if ($BotDebug)          { $gradleArgs += "-Porebit.autotest.debug=true" }
if ($Rtrace)            { $gradleArgs += "-Porebit.autotest.rtrace=true" }

Push-Location $repo
try {
    Write-Host "[cliff] re-asserting active Stonecutter project ($McVersion)"
    & (Join-Path $repo "gradlew.bat") "Set active project to $McVersion"
    if ($LASTEXITCODE -ne 0) { Write-Error "Set active project failed"; exit 2 }

    Write-Host "[cliff] launching headless server: gradlew $($gradleArgs -join ' ')"
    & (Join-Path $repo "gradlew.bat") @gradleArgs
    Write-Host "[cliff] gradle exited with code $LASTEXITCODE"
} finally {
    Pop-Location
}

# ---- 3. Assert -----------------------------------------------------------------------------
if (-not (Test-Path $resultFile)) {
    Write-Error ("no result file at $resultFile -- the server crashed or the hook never armed. " +
                 "Check run/autotest/logs/latest.log for [Orebit/autotest] lines.")
    exit 2
}
$result = ConvertFrom-StringData (Get-Content $resultFile -Raw)
Write-Host ""
Write-Host "===== Orebit cliff-repro result ====="
$result.GetEnumerator() | Sort-Object Name | ForEach-Object { Write-Host ("  {0} = {1}" -f $_.Name, $_.Value) }
Write-Host "====================================="
if ($result.result -eq "PASS") { Write-Host "[cliff] PASS (unexpected)"; exit 0 }
else { Write-Host "[cliff] FAIL: $($result.reason)"; exit 1 }
