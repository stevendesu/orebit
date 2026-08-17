# Orebit headless autotest orchestrator (mc-1.21 era, Fabric).
# Intended location: <repo>/scripts/run-autotest.ps1, with templates in <repo>/scripts/autotest/
# (server.properties, eula.txt, orebit.properties -- copied from the s53 harness design).
#
# What it does:
#   1. resets run/autotest to a deterministic state (deletes the world -> same seed regenerates it,
#      drops the seed/eula/bot-config templates, clears any stale result file). -MasterWorld replaces the
#      seed-regen with a byte-identical copy of a frozen master; -KeepWorld skips the world reset entirely
#      (reuses the previous run's world, incl. its persisted orebit/ HPA + invalidation state — the
#      two-boot restart oracle) while still clearing stale result/trace files,
#   2. runs :fabric:<ver>:runAutotest (dedicated server, headless -- Loom's server() preset adds nogui),
#   3. asserts on run/autotest/orebit-autotest-result.properties.
# Exit codes: 0 = PASS, 1 = FAIL (result file says so), 2 = no result file (crash / hook never armed).
#
# Determinism convention (scripts/autotest/orebit.properties): reproducibility depends on pinning the
# non-deterministic scheduling knobs. Two pins live in that template:
#   - pathing.async=false            -> synchronous search (async replan TIMING made the route nondeterministic).
#   - HIGH drain budgets + fixed count -> the NavGrid chunk-build drain and the HPA* dirty-leaf flush are
#     wall-clock TIME-budgeted in production (pathing.chunkBuildBudgetMs / pathing.hpaFlushBudgetMs, def
#     2.0 / 1.0 ms), so the per-tick drain COUNT varies with machine timing. The template pins both budgets
#     to 100 ms (never bind) with pathing.chunkBuildsPerTick=8 (and the hardcoded MAX_LEAVES_PER_TICK=64),
#     so the FIXED COUNT backstops govern -> identical per-tick drain counts across same-seed runs. Production
#     uses the low ms budgets for adaptive scheduling; the autotest trades that for reproducibility. If a
#     future session sees run-to-run repro drift, confirm these pins survived any config-template edit.
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
    # -WarmAtGoal <ticks>: GOTO warm-up — after spawn, teleport the bot TO THE GOAL, hold it there this
    # many ticks (its player ticket loads the chunks and the nav grid builds around the goal), teleport
    # it back to the start, and only then start the normal -StartDelay countdown before the goto. Both
    # route ends are then warm; the middle stays unloaded (the long-range navigation under test). 0 = off.
    [int]$WarmAtGoal = 0,
    # -GotoTool <item_id>: the tool pre-equipped for GOTO mode (default: the historical single stone
    # pickaxe — every recorded goto baseline used it; pass iron_pickaxe etc. for faster-mining scenarios).
    # Ignored under -Barehanded. Distinct from -Tool, which is the GATHER/CRAFT-mode tool.
    [string]$GotoTool = "",
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
    # -TraceGoal <x,y,z>: TRACE mode. Headless mirror of the in-game `/bot trace` — spawn the bot at -Start,
    # wait for the same nav-readiness gate the goto uses, then call the exact entry point TraceCommand calls
    # (AllyBotEntity.traceTo) and halt. No goto. The dump lands in run/autotest/orebit-trace.txt (+ the
    # region-heuristic A/B twin orebit-trace-region.txt), directly diffable against a live /bot trace and
    # analyzable with internal_docs/trace_analysis.py. IMPORTANT: like /bot trace's goalFloor, this is the
    # cell the caller STANDS ON (the owner's feet cell minus one Y), not the feet cell. -Goal is IGNORED.
    [string]$TraceGoal = "",
    [string]$GroundDrive = "",   # "" = build-default; "servo" | "legacy" forces drive()'s land branch (Stage-2 A/B)
    # -Gather <resource>: GATHER mode. Drive /bot gather <resource> [count] instead of /bot goto — the bot
    # find→mine→returns for the named resource (tab-complete list: cobblestone, stone, iron, diamond, coal,
    # andesite, wood, ...). When set, -Goal is IGNORED; the bot must return to its -Start cell to PASS.
    # The result file gains the granular gather schema (phaseReached / collected / quota / returned /
    # finalX/Y/Z / distanceFromStart / maxDistFromStart / outcome[PASS/FAIL/TIMEOUT]).
    [string]$Gather = "",
    # -Count <n>: gather quota (target number of PICKED-UP items). Default 1.
    [int]$Count = 1,
    # -Tool <item_id>: the tool pre-equipped into the bot's real inventory BEFORE the gather command, so the
    # drop-goal tool gate doesn't refuse for lack of a correct pickaxe. Default diamond_pickaxe. Range-stable
    # ids: {wooden,stone,iron,golden,diamond,netherite}_pickaxe, {iron,diamond,netherite}_axe,
    # {iron,diamond}_shovel, shears. Ignored under -Barehanded (which pre-equips nothing).
    [string]$Tool = "diamond_pickaxe",
    # -Silk: add Silk Touch to the pre-equipped tool. Required for `gather stone` (SILK_REQUIRED); harmless
    # for NO_SILK resources like cobblestone (which then refuse — use a plain -Tool, no -Silk, for those).
    [switch]$Silk,
    # -Craft <result-name>: CRAFT mode. Drive /bot craft <result> [count] instead of /bot goto — the bot
    # crafts from an inventory injected via -Give (2x2 recipes in place; 3x3 recipes seek/place/reclaim a
    # crafting table per the crafting.* config). When set, -Goal is IGNORED. The result file gains the
    # craft schema (phaseReached / crafted / target / inventoryCount / outcome[PASS/FAIL/TIMEOUT]).
    [string]$Craft = "",
    # -Give "item:count,item:count": the inventory injected at spawn for a -Craft run (e.g.
    # "oak_planks:7,stick:2"). Plain names get the minecraft: namespace.
    [string]$Give = "",
    # -Farm: FARM mode. Build a deterministic wheat plot next to -Start (8 mature wheat, 2 hydrated
    # grass till candidates, hoe given) and drive /bot farm; PASS = harvested 8, tilled 2, planted >=1.
    [switch]$Farm,
    # -Fight: FIGHT mode. Difficulty NORMAL, iron sword given, one zombie spawned targeting the bot;
    # PASS = zombie killed with the self-defense interrupt engaged and >=1 landed strike, bot alive.
    [switch]$Fight,
    # -BuildTest: BUILD mode. Write the deterministic 3x3 cobblestone-platform .litematic fixture,
    # give 9 cobblestone, drive /bot build next to -Start; PASS = 9/9 cells placed and verified.
    [switch]$BuildTest,
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
    [string]$MasterWorld = "",
    # -KeepWorld: REUSE run/autotest/world from the PREVIOUS run instead of deleting/re-copying it — the
    # two-boot restart oracle for persisted state (DESIGN-persisted-invalidation-memory.md §5): boot 1 (with
    # -MasterWorld) learns + flushes <world>/orebit/** (HPA shards, invalidation rows); boot 2 (-KeepWorld)
    # boots the SAME mutated world dir and must benefit from what it persisted. Stale result/trace files are
    # still cleared (they belong to a RUN, not the world). Overrides -MasterWorld (no re-copy happens); errors
    # out if no previous world exists.
    [switch]$KeepWorld
)

$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot            # scripts/ -> repo root
$runDir = Join-Path $repo "run\autotest"
$templates = Join-Path $PSScriptRoot "autotest"
$resultFile = Join-Path $runDir "orebit-autotest-result.properties"

# ---- 1. Deterministic run-dir state ----------------------------------------------------------
New-Item -ItemType Directory -Force -Path (Join-Path $runDir "config") | Out-Null
$world = Join-Path $runDir "world"
if ($KeepWorld) {
    # KEEP-WORLD mode (restart oracle): boot the previous run's world dir as-is — its orebit/ persisted state
    # (HPA shards + invalidation sections) is exactly what the second boot is asserting against. Only the
    # stale session.lock is dropped (the previous server wrote one into the run world).
    if (-not (Test-Path $world)) { Write-Error "-KeepWorld: no previous run world at $world"; exit 2 }
    Write-Host "[autotest] KEEP-WORLD mode: reusing previous run world (persisted orebit/ state intact)"
    $lock = Join-Path $world "session.lock"
    if (Test-Path $lock) { Remove-Item -Force $lock }
} else {
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
if ($WarmAtGoal -gt 0) { $gradleArgs += "-Porebit.autotest.warmAtGoalTicks=$WarmAtGoal" }
if ($GotoTool -ne "")  { $gradleArgs += "-Porebit.autotest.gotoTool=$GotoTool" }
if ($BotDebug)        { $gradleArgs += "-Porebit.autotest.debug=true" }
if ($Trace)           { $gradleArgs += "-Porebit.autotest.trace=true" }
if ($ProbeOnly)       { $gradleArgs += "-Porebit.autotest.probeOnly=true" }
if ($Barehanded)      { $gradleArgs += "-Porebit.autotest.barehanded=true" }
if ($Rtrace)          { $gradleArgs += "-Porebit.autotest.rtrace=true" }
if ($TraceGoal -ne "") { $gradleArgs += "-Porebit.autotest.traceGoal=$TraceGoal" }
if ($Gather -ne "")   { $gradleArgs += "-Porebit.autotest.gather=$Gather" }
if ($Craft -ne "")    { $gradleArgs += "-Porebit.autotest.craft=$Craft" }
if ($Give -ne "")     { $gradleArgs += "-Porebit.autotest.give=$Give" }
if ($Farm)            { $gradleArgs += "-Porebit.autotest.farm=true" }
if ($Fight)           { $gradleArgs += "-Porebit.autotest.fight=true" }
if ($BuildTest)       { $gradleArgs += "-Porebit.autotest.build=true" }
if ($Count -gt 1)     { $gradleArgs += "-Porebit.autotest.count=$Count" }
if ($Tool -ne "")     { $gradleArgs += "-Porebit.autotest.tool=$Tool" }
if ($Silk)            { $gradleArgs += "-Porebit.autotest.silk=true" }
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
