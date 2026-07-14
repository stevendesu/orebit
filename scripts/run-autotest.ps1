# Orebit headless autotest orchestrator (mc-1.21 era, Fabric).
# Intended location: <repo>/scripts/run-autotest.ps1, with templates in <repo>/scripts/autotest/
# (server.properties, eula.txt, orebit.properties -- copied from the s53 harness design).
#
# What it does:
#   1. resets run/autotest to a deterministic state (deletes the world -> same seed regenerates it,
#      drops the seed/eula/bot-config templates, clears any stale result file),
#   2. runs :fabric:<ver>:runAutotest (dedicated server, headless -- Loom's server() preset adds nogui),
#   3. asserts on run/autotest/orebit-autotest-result.properties.
# Exit codes: 0 = PASS, 1 = FAIL (result file says so), 2 = no result file (crash / hook never armed).
#
# Requires JAVA_HOME -> JDK 21 (the 1.21.11 node; mc-1.21 era rule: >=1.20.5 -> 21).
# Windows PowerShell 5.1 compatible (no &&, no ternary).

param(
    [string]$McVersion = "1.21.11",
    # Optional scenario overrides, forwarded as gradle -P properties -> -D JVM args by the run config.
    [string]$Start = "",
    [string]$Goal = "",
    [int]$BudgetTicks = 0,
    [int]$StartDelay = 0,
    [switch]$BotDebug,
    # -Trace: dump EVERY A* search's full expansion trace to run/autotest/orebit-autotest-trace-<n>.txt
    # (one numbered file per search; analyze with internal_docs/trace_analysis.py). Trace runs are SLOW
    # and the files are HUGE (per-node file I/O on the tick thread) -- diagnostic runs only.
    [switch]$Trace,
    # -ProbeOnly: read-only worldgen dump of the start cell (column + 5x5 topSolidY + determinism signature)
    # then halt -- NO bot, NO goto. Use with -MasterWorld + -Start to confirm a frozen world's start cell is
    # a real canopy/floor before running the full descent. Output: run/autotest/orebit-autotest-startprobe.txt.
    [switch]$ProbeOnly,
    # -Barehanded: give the bot NO tools (default is one stone pickaxe). Bare-handed mining is far slower,
    # raising the region-tier mine-through cost of a ground descent — the repro knob for the owner's
    # pillar-to-the-sky (empty-air highway out-prices a dig-down descent).
    [switch]$Barehanded,
    # -Rtrace: run the full-cascade region trace (what /bot goto's region tier evaluates) into
    # run/autotest/orebit-region-trace.txt, then halt. No goto. Combine with -Barehanded to capture the
    # bare-handed pillar cascade (L1 flood level-tagged 'E <seq> L1'). Needs a master with the persisted HPA.
    [switch]$Rtrace,
    [string]$GroundDrive = "",   # "" = build-default; "servo" | "legacy" forces drive()'s land branch (Stage-2 A/B)
    # -MasterWorld <path>: FROZEN-WORLD mode. Instead of seed-regenerating the world each run (which is
    # non-deterministic for VEGETATION -- trees generate in parallel-chunk-gen order; proven by the
    # startprobe: same seed -> 3 distinct tree layouts in 5 runs), copy a pristine, pre-generated master
    # world into run/autotest/world every run. Minecraft only ever mutates the COPY, so the bot's broken
    # leaves / placed blocks and MC's own session.lock/level.dat writes are discarded next run -- the master
    # stays byte-identical, so every run starts from the exact same blocks. CAVEAT: the master must already
    # contain every chunk the bot visits; a chunk absent from the master is generated on-the-fly from the
    # world's seed -> back to non-deterministic vegetation. The start-AREA chunks (where the early tree-
    # descent bug lives) are always covered if you explored to the tree; the full start->goal corridor needs
    # pre-gen. "" = legacy seed-regen mode (backward compatible).
    [string]$MasterWorld = ""
)

$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot            # scripts/ -> repo root
$runDir = Join-Path $repo "run\autotest"
$templates = Join-Path $PSScriptRoot "autotest"
$resultFile = Join-Path $runDir "orebit-autotest-result.properties"

# ---- 1. Deterministic run-dir state ----------------------------------------------------------
New-Item -ItemType Directory -Force -Path (Join-Path $runDir "config") | Out-Null
$world = Join-Path $runDir "world"
if (Test-Path $world) {
    Write-Host "[autotest] deleting previous run world (disposable copy)"
    Remove-Item -Recurse -Force $world
}
if ($MasterWorld -ne "") {
    # FROZEN-WORLD mode: copy the pristine master in. The master itself is NEVER launched, so it stays
    # byte-identical across runs -> deterministic blocks (no parallel-gen vegetation variance), and the
    # bot's edits land only in this disposable copy.
    if (-not (Test-Path $MasterWorld)) { Write-Error "master world not found: $MasterWorld"; exit 2 }
    Write-Host "[autotest] FROZEN-WORLD mode: copying master '$MasterWorld' -> run world"
    Copy-Item -Recurse -Force $MasterWorld $world
    # A stale session.lock copied from the master (if it was ever launched) would block the server; drop it.
    $lock = Join-Path $world "session.lock"
    if (Test-Path $lock) { Remove-Item -Force $lock }
} else {
    Write-Host "[autotest] seed-regen mode: MC will freshly generate the world from the pinned seed"
}
if (Test-Path $resultFile) { Remove-Item -Force $resultFile }
# Stale per-search trace files from a previous -Trace run would mix into this run's numbering.
Get-ChildItem -Path $runDir -Filter "orebit-autotest-trace-*.txt" -ErrorAction SilentlyContinue | Remove-Item -Force

Copy-Item (Join-Path $templates "server.properties") (Join-Path $runDir "server.properties") -Force
Copy-Item (Join-Path $templates "eula.txt")          (Join-Path $runDir "eula.txt") -Force
Copy-Item (Join-Path $templates "orebit.properties") (Join-Path $runDir "config\orebit.properties") -Force

# ---- 2. Run -----------------------------------------------------------------------------------
$gradleArgs = @(":fabric:${McVersion}:runAutotest")
if ($Start -ne "")    { $gradleArgs += "-Porebit.autotest.start=$Start" }
if ($Goal -ne "")     { $gradleArgs += "-Porebit.autotest.goal=$Goal" }
if ($BudgetTicks -gt 0) { $gradleArgs += "-Porebit.autotest.budgetTicks=$BudgetTicks" }
if ($StartDelay -gt 0) { $gradleArgs += "-Porebit.autotest.startDelayTicks=$StartDelay" }
if ($BotDebug)        { $gradleArgs += "-Porebit.autotest.debug=true" }
if ($Trace)           { $gradleArgs += "-Porebit.autotest.trace=true" }
if ($ProbeOnly)       { $gradleArgs += "-Porebit.autotest.probeOnly=true" }
if ($Barehanded)      { $gradleArgs += "-Porebit.autotest.barehanded=true" }
if ($Rtrace)          { $gradleArgs += "-Porebit.autotest.rtrace=true" }
if ($GroundDrive -ne "") { $gradleArgs += "-Porebit.ground.drive=$GroundDrive" }

# gradlew.bat resolves the PROJECT from the current working directory, not from its own location --
# invoked from elsewhere it would run this wrapper against a different repo's build. Pin the cwd.
Push-Location $repo
try {
    Write-Host "[autotest] re-asserting active Stonecutter project ($McVersion)"
    & (Join-Path $repo "gradlew.bat") "Set active project to $McVersion"
    if ($LASTEXITCODE -ne 0) { Write-Error "Set active project failed"; exit 2 }

    Write-Host "[autotest] launching headless server: gradlew $($gradleArgs -join ' ')"
    & (Join-Path $repo "gradlew.bat") @gradleArgs
    $gradleExit = $LASTEXITCODE
    Write-Host "[autotest] gradle exited with code $gradleExit"
} finally {
    Pop-Location
}

# ---- 3. Assert --------------------------------------------------------------------------------
if (-not (Test-Path $resultFile)) {
    Write-Error ("no result file at $resultFile -- the server crashed or the hook never armed. " +
                 "Check run/autotest/logs/latest.log for [Orebit/autotest] lines.")
    exit 2
}

$result = ConvertFrom-StringData (Get-Content $resultFile -Raw)
Write-Host ""
Write-Host "===== Orebit autotest result ====="
$result.GetEnumerator() | Sort-Object Name | ForEach-Object { Write-Host ("  {0} = {1}" -f $_.Name, $_.Value) }
Write-Host "=================================="

if ($result.result -eq "PASS") {
    Write-Host "[autotest] PASS"
    exit 0
} else {
    Write-Host "[autotest] FAIL: $($result.reason)  (triage table: DESIGN.md section 7)"
    exit 1
}
