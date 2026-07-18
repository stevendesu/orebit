# Orebit LATERAL-CLIMB repro — headless autotest wrapper.
# mc-1.21 era, Fabric, node 1.21.11, JDK 21. Mirrors scripts/run-autotest-cliff.ps1 but uses the no-
# capability bot config (scripts/autotest-climb/orebit.properties: canPlace=false, canMine=false) and the
# frozen FLAT lateral-climb master world (scripts/autotest-climb/world-master/world), built via
# scripts/repro-climb/.
#
# The scenario: the bot spawns on the -X ledge at Start; goal is the +X ledge at Goal, across a 5-block
# AIR gap. The bot cannot place (bridge) nor break (dig), and the gap is too wide to parkour, so the ONLY
# realizable crossing is to GRAB the single feet-level row of VINES on the wall face at x=8 and ease
# SIDEWAYS across (lateral Climb, Δy == 0) to x=12, then step off onto the east ledge at x=13. Geometry +
# rebuild steps: scripts/repro-climb/README.md.
#
# Validate with -BotDebug: the log's `[Orebit] exec Climb -> <cell> (air)` lines confirm the plan crosses
# via lateral Climb (Δy==0 grabs, NOT Traverse/Parkour), and the wall-clock spacing between successive
# Climb exec lines (~0.77 s/block at 20 TPS = ~15.44 t/block sneak speed, vs ~0.23 s for a Traverse block)
# confirms the follower's 30% sneak-speed enforcement is real.
#
# Exit codes: 0 = PASS (expected: arrived after the lateral climb), 1 = FAIL (result file says so),
#             2 = no result file (crash / hook never armed).
# Requires JAVA_HOME -> JDK 21. Windows PowerShell 5.1 compatible (no &&, no ternary).

param(
    [string]$McVersion  = "1.21.11",
    [string]$Start      = "3,1,0",      # west-ledge feet cell
    [string]$Goal       = "17,1,0",     # east-ledge feet cell, across the 5-block gap
    [int]$BudgetTicks   = 2400,         # ~2 min at 20 TPS — generous (lateral climb of ~6 cells at sneak speed ~90t)
    [switch]$BotDebug,
    [switch]$Trace,                     # per-search A* dumps -> run/autotest/orebit-autotest-trace-<n>.txt (huge+slow)
    [switch]$Rtrace,                    # dump the region cascade (what the region tier proposes) + halt
    [string]$MasterWorld = ""           # default: scripts/autotest-climb/world-master/world
)

$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$runDir = Join-Path $repo "run\autotest"
$templates = Join-Path $PSScriptRoot "autotest-climb"
$resultFile = Join-Path $runDir "orebit-autotest-result.properties"
if ($MasterWorld -eq "") { $MasterWorld = Join-Path $templates "world-master\world" }

# ---- 1. Deterministic run-dir state (frozen-world mode) --------------------------------------
New-Item -ItemType Directory -Force -Path (Join-Path $runDir "config") | Out-Null
$world = Join-Path $runDir "world"
if (Test-Path $world) {
    Write-Host "[climb] deleting previous run world (disposable copy)"
    Remove-Item -Recurse -Force $world
}
if (-not (Test-Path $MasterWorld)) { Write-Error "master world not found: $MasterWorld"; exit 2 }
Write-Host "[climb] FROZEN-WORLD mode: copying master '$MasterWorld' -> run world"
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
if ($Trace)             { $gradleArgs += "-Porebit.autotest.trace=true" }
if ($Rtrace)            { $gradleArgs += "-Porebit.autotest.rtrace=true" }

Push-Location $repo
try {
    Write-Host "[climb] re-asserting active Stonecutter project ($McVersion)"
    & (Join-Path $repo "gradlew.bat") "Set active project to $McVersion"
    if ($LASTEXITCODE -ne 0) { Write-Error "Set active project failed"; exit 2 }

    Write-Host "[climb] launching headless server: gradlew $($gradleArgs -join ' ')"
    & (Join-Path $repo "gradlew.bat") @gradleArgs
    Write-Host "[climb] gradle exited with code $LASTEXITCODE"
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
Write-Host "===== Orebit climb-repro result ====="
$result.GetEnumerator() | Sort-Object Name | ForEach-Object { Write-Host ("  {0} = {1}" -f $_.Name, $_.Value) }
Write-Host "====================================="
if ($result.result -eq "PASS") { Write-Host "[climb] PASS (arrived via lateral climb)"; exit 0 }
else { Write-Host "[climb] FAIL: $($result.reason)"; exit 1 }
