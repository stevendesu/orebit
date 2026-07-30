# SUBSYSTEMS — map of the RUNNING code (refreshed 2026-07, rolling-skeleton increment A era)

One screen per subsystem: what it does, key files, entry points. Paths relative to
`src/main/java/com/orebit/mod/` unless noted. Stub-only packages (ai/, tasks/, integration/, …) are NOT
listed — see CLAUDE.md. Historical design docs referenced by code Javadocs live beside this file
(condensed, each with a §-map). NOTE: `PathPlan`/`HierarchicalRegionPlan` are under active edit for
the rolling-skeleton arc — their entries below describe role, not exact current line-level shape; the
in-flight increment A spec is `DESIGN-rolling-skeleton.md` (core-wt internal_docs).

## worldmodel/navblock — block-state interning
Classifies every `BlockState` (~28k) at static init into a few hundred behavioral navtypes: a `short`
index into a packed 64-bit `long` descriptor table (topY, shape, fluid, climbable, gravity, damaging,
transit-slow, hardness, tool, PORTAL bit 43, PROTECTED bit 44, derived STANDABLE/BREAKABLE/OPEN_PLACE/
COLLISION). Table is ~4–5 KB, L1-resident — the whole hot path reads facts objectlessly.
`ProtectedBlocks.applyProtected` splits protected states into their own navtypes post-init.
- Files: `worldmodel/navblock/NavBlock.java`, `ClimbableNavBlock.java`
- Entry: forced by `MiningModel.buildTable` (from `OrebitCommon.init`); read via
  `NavGridView.descriptorAt`, `NavFlags`, `NavSectionBuilder`.

## worldmodel/pathing — the nav grid pipeline
`TraversalGrid` = per-16³ section: packed `short[4096]` (low 10 bits navtype, high 6 `NavFlags`
neighbour bits) + parallel `byte[4096]` depth nibbles (floorGap low / runUp high; 14=saturate,
15=UNKNOWN) + an `anyDoor` prefilter. `NavSection` wraps a grid + nullable log₂ `resourceTally`;
`NavSectionPool` recycles; `NavStore` = per-level chunkKey→NavSection[] map (+ `ringBuilt` readiness).
`ChunkNavBuilder` drives the column build over `NavSectionBuilder`'s kernels — `classifyInto`/
`classifyNavtypes` (pass 1, + resource tally + portal discovery), `computeFlags` (pass 2, 3-row
vertical overscan), `computeDepth` (pass 3, the depth-nibble column sweep), `patchCell`/`patchCells`
(fixpoint repair) — the palette-reflection code here is the most version-fragile in the project (the
old `build()` benchmark entry is GONE; only a `sink` field + legacy reflection helpers remain for the
JMH reference bench). `ChunkNavLoader` defers builds per tick (`pathing.chunkBuildsPerTick`/
`chunkBuildBudgetMs`). `NavGridView` is the read seam (per-search chunk cache; `background(level)` =
planner-thread view, no live fallback). `NavGridUpdater` records block changes into `PendingPatches`
(per-level open-addressed dirty-cell queue, last-state-wins — PERF-DESIGN-navgrid-edit-batching.md
§4.2) and drains them through `patchCells` at flush barriers, bumping the per-level `editEpoch`.
`NavReclaim` = epoch-deferred section reclamation for async readers (DESIGN-background-pathfinding.md
§4.1). `NavWarmup` = boot JIT warm-up (first search 21.8→0.67 ms). `NetherPortalIndex` = per-dimension
portal-column index (fed by pass 1 + `ChunkNavLoader.record`).
- Files: `worldmodel/pathing/TraversalGrid.java`, `NavSectionBuilder.java`, `ChunkNavBuilder.java`,
  `NavStore.java`, `NavGridView.java`, `ChunkNavLoader.java`, `NavGridUpdater.java`,
  `PendingPatches.java` (+ `NavReclaim`, `NavWarmup`, `NetherPortalIndex`)
- Entry: `ChunkNavLoader.register` / `NavGridUpdater.register` from `OrebitCommon.init`.

## pathfinding/blockpathfinder — block-tier A\* + movements + cuboids + phases + steering
`BlockPathfinder.findPath` — allocation-free A\* over floor cells (SoA node state, open-addressed
long→row map, binary heap, per-search `EditPool` arena); returns `BlockPathPlan` (waypoints + per-step
`Movement` + `StepEdits`). **17 movements** behind `MovementRegistry.TIER1` (registration order =
cost-tie priority): Traverse, Diagonal, Ascend, Descend, Fall, Pillar, MineDown, Swim, SprintSwim,
StartSprintSwim, Surface, Climb, Parkour, DiagonalParkour, **WalkOff** (no-jump gap-1/descend-1 — the
honey crosser), **DiagonalSprintSwim** (26-connected underwater diagonals/corners), and
**RideBubbleColumn** (up-column conveyor ride, 3-phase enter/ride/settle). `ParkourEnvelope` is NOT a
movement: a derived static ballistics admission table (`MAX_GAP[startTopY][gsf][occ]`) read by
Parkour/DiagonalParkour (supersedes DESIGN-parkour-envelope.md; see `parkour_envelope_params.py`).
`cuboid/` = the macro subsystem (`CuboidExtractor`, `MacroJump`, `GoalForcedCost` premium,
`NavGridCuboidsView`). Phase framework: `Movement.plan()` → `MovePlan` (guard-based phases —
`need(Need.AIR|FOOTING|OPEN)`, `drive`, `advanceWhen`, `done`; plan-level `resetWhen`/`failWhen`/
`requireDoor`) → `PhaseRunner` (per-tick self-healing cursor; `failed()`, `doneNow()` terminal-guard
probe). Steering: `BotSteering`/`SteerView` seams + `SteerControl` statics (`steerTowards`, `drive`,
`steerViaGate`, `recenterOnTarget`, the swim family `swimTowards`/`swimPitched*`/`swimServo`,
`groundServo` — `GROUND_DRIVE` defaults "servo" — `parkourAirborne`/`parkourRunupAlign`, and
**`holdDepth`** — the bang-bang water autopilot; movements OWN vertical water control). `MovementContext`
= predicate vocabulary incl. `transitOrBreak`; `MiningModel` = tool-tick table + per-search snapshot;
edits = `EditScratch`/`StepEdits`/`PathEdits`/`EditSnapshot` (break/place **+ door-set** arrays — the
DOORS thread rides the whole edit pipeline). `BotCaps` = capability record (jump/fall/damage/break/
place/`maxNodes`/`greedyWeight`/`mayToggleDoors`) + **`realizabilitySig()`/`sigDominates`** — the packed
caps+tool-tier signature that keys invalidation-memory rows (DESIGN-persisted-invalidation-memory.md
§3). Flags: `TRACE` off, `PARTIAL_PATH` on, `IRREVERSIBLE_GUARD` on; goal tolerance ±1 XZ / ±2 Y.
- Files: `pathfinding/blockpathfinder/BlockPathfinder.java`, `MovementContext.java`, `MiningModel.java`,
  `SteerControl.java`, `MovePlan.java`, `PhaseRunner.java`, `BotCaps.java`, `movements/*.java`,
  `cuboid/CuboidExtractor.java`
- Entry: `PathPlan.replanBlock` (sync) or `PlanExecutor` workers (async); steer/phase hooks driven
  per-tick by `BotNavigator.steerAlongPath`.

## pathfinding/regionpathfinder + worldmodel/hpa — region tier (fragments + cascade + pyramid)
`RegionPathfinder` — fragment-model HPA\* where node = (region, fragment, entry-face, **from-fragment**)
— from-fragment = the fragment the search last hopped FROM (journey root = `VIRTUAL_START_FRAG` = 62;
stored in a cold `Nodes.fromFrag[]` SoA field), so two routes into one merged fragment stay distinct
nodes and the A==G cliff reroutes instead of collapsing. The (approach → V) blacklist row keys on the
full node key (`approachRowKey`; entry-face DERIVED, reconstructed add-side via `approachRowKeyForStep`),
so V-approach invalidation is from-fragment-keyed (blaming one dead approach keeps the others alive); the
blame anchor scan skips virtual fragments; the reverse-Dijkstra field is gated to `VIRTUAL_START_FRAG`
(byte-identical). See DESIGN-virtual-start-fragment.md §0.5 (core-wt). Edges = portal
crossings (footprint overlap), always-possible dig-through (span × tool-aware `RegionMineModel` cost;
place-side sibling `RegionPlaceModel`), walk-across priced entry→exit; standable-Δy anchors +
flood-from-bot start membership. Entry points `plan`/`planWithin` (arbitrary from-cell — the extension
seam) /`costToGoalField` → `RegionCostField` (dense per-(region,fragment) cost-to-goal field with the
s53 frontier-floor; the `/bot rtrace` A/B heuristic). `RegionHeuristic` = strategy interface;
`SimpleRegionHeuristic` (REAL) is the ratified default — the other four heuristic variants are stubs.
`HierarchicalRegionPlan` = the cascade: a stack of per-level skeletons, re-plan only the exited level.
**Rolling skeleton (increment A, in flight):** the window base IS the committed cursor (L≤1 for now);
on a committed crossing the L0 skeleton is spliced-and-extended (`RegionPathPlan.splice` — head
dropped, 1-hop suffix from the existing tail, cursors SHIFTED never reset) toward L1's advancing
hand-down, and `onBotMoved` answers a three-way verdict UNCHANGED/EXTENDED(shift)/SWAPPED — EXTENDED
keeps the live block plan and falls through to the normal commit-slide. Kills segment-tail pinning
(the portal-box cobble waste); pin-at-portal remains the explicit degraded mode. Spec + invariants
(INV-1 prefix stability, INV-4 the rolling guarantee): `DESIGN-rolling-skeleton.md` (core-wt
internal_docs) §3–§4, §13-A. `RegionEdgeBlacklist` = per-level directed crossing blacklist for
event-driven repair. hpa/ data layer: `RegionGrid` (per-dimension façade — `of`/`peek` registry,
owns `CostPyramid` + `ResourcePyramid` + `residency()` + `crossingMemory()`; `headless()` for tests),
`RegionFragments` (62-fragment cap + typed-fragment S/W bits), `FragmentBuilder`/
`FragmentLeafComputer`/`LeafCostComputer`, `CostCodec`, `PyramidMerger` (coarse roll-up +
`reconcileNode`/`mergeUpFrom` for the persistence seam), `HpaMaintenance` (debounced dirty-leaf
recompute on block change; also evicts touched invalidation rows).
- Files: `pathfinding/regionpathfinder/RegionPathfinder.java`, `HierarchicalRegionPlan.java`,
  `RegionPathPlan.java`, `RegionMineModel.java`, `RegionPlaceModel.java`, `RegionCostField.java`,
  `worldmodel/hpa/RegionGrid.java`, `FragmentBuilder.java`, `HpaMaintenance.java`
- Entry: `PathPlan` construction/reroute (`stepCascade`); `HpaMaintenance.register` from
  `OrebitCommon.init`.

## worldmodel/hpa — invalidation memory (dead-crossing evidence store)
`RegionCrossingMemory` = per-dimension long-lived store of forbidden directed region→region crossings:
parallel-long rows per level 0..MAX_COARSE_LEVEL, each tagged with the recording bot's
`realizabilitySig` + a provenance byte (`PROV_PROOF`/`PROV_ESCALATION`/`PROV_ROLLED_UP`). API:
`record`, `holdsProofDominating` (caps-dominance test), `evictLeafTouching` (block-change expiry).
`InvalidationRollup` = the roll-up fold: when a level-N crossing dies, decides whether ALL constituent
child crossings are proven dead and records a `PROV_ROLLED_UP` parent row, recursing upward; also
`containedParentFragment` (used by `RegionGrid.containedFragment`). Only realized-evidence rows
persist; escalation rows are session-only (the ratified evidence model — see the
invalidation-evidence-model memory before touching this). Blame keys are content-addressed
(region+fragment node keys), never skeleton indices — which is what makes rolling-skeleton
renumbering safe.
- Files: `worldmodel/hpa/RegionCrossingMemory.java`, `InvalidationRollup.java`
- Entry: written by `HierarchicalRegionPlan` (repair/escalation) + persistence load-merge; read by the
  region A\*; evicted by `HpaMaintenance`. Spec: `DESIGN-persisted-invalidation-memory.md` §3.5/§4/§4b.

## worldmodel/persistence — sharded region persistence (cost + resource + invalidation)
The region tier's disk cache — plain `Files` I/O (the `BotManager` pattern, no NBT/SavedData), treated
as a cache (corrupt → rebuilt from live). **Format is .mca-style SHARDED, uncompressed** (the old
per-dim `hpa.bin`/`res.bin` gzip blobs are dead — ignored on disk): per dimension dir
`<world>/orebit/<dim>/` holds `hpa.<X>.<Z>.bin` (cost levels 0–5 per level-5 shard = 32×32 chunks,
magic OBHS), `hpa.coarse.bin` (level 6, OBHC), `res.<X>.<Z>.bin` (OBRS), `res.coarse.bin` (levels
6–21, OBRC). `CostPyramidCodec` (file VERSION 7) / `ResourcePyramidCodec` encode/decode; the cost
files carry the **invalidation section** (v4 schema): sig-tagged 24-byte `{fromKey,toKey,capsSig}`
rows bucketed assign-to-FROM, merged into `RegionCrossingMemory` on load. `RegionPersistence` = the
driver: eager `loadAll` at SERVER_STARTED (or `loadCoarseOnly` when `hpa.lazyLoad` — decode coarse
only + build the persisted-shard index), authoritative `flushAll` at SERVER_STOPPING, budgeted dirty
flush per `onWorldTickEnd` (`hpa.persistIntervalTicks`/`persistFlushBudgetMs`; `ShardRowIndex` makes a
shard flush O(shard rows)). Stage-2 bounded-RAM machinery: `RegionShardResidency` (per-dim
resident/persisted/pending sets + LRU touch clock — the clobber-guard's "persisted-but-not-resident"
discriminator), `RegionShardLoader` (budgeted on-demand page-in, `pathing.regionShardLoadBudgetMs`),
`RegionReconciler` (re-derives the `StraddleSet` of live-wins-skipped coarse cells after a lazy load),
`RegionEvictor` (cold-shard eviction, OFF by default — `hpa.residentLeafCap=0`). All I/O tick-thread.
- Files: `worldmodel/persistence/RegionPersistence.java`, `CostPyramidCodec.java`,
  `ResourcePyramidCodec.java`, `RegionShardLoader.java`, `RegionReconciler.java`, `RegionEvictor.java`,
  `worldmodel/hpa/RegionShardResidency.java`, `ShardRowIndex.java`, `StraddleSet.java`
- Entry: all hooks wired in `OrebitCommon.init`. Spec: `DESIGN-worldmodel-persistence.md` §2/§2b/§3/
  §5/§7/§11; invalidation rows `DESIGN-persisted-invalidation-memory.md` §4.

## pathfinding/async + splice — planner pool + plan handoff
`PlanExecutor` = fixed daemon pool (`pathing.maxThreads`, clamp [1, cores−2]); tick thread `submit`s an
immutable `SearchRequest` record (level = identity only; workers read NavStore sections via the
no-fallback view; now carries per-request `budgetNanos` + goal tolerances `goalTolXZ`/`goalTolY`),
polls a `PlanHandle` (volatile-done mailbox; `wasRejected` → retry, not blacklist; also `wasPartial`,
`wasBudgetHit`, `realizedCrossings` — the blame input); per-thread epoch stamps feed `NavReclaim`;
bounded queue (256), full → completeRejected, never blocks. `SpliceSeam` = seed→accept→adopt handoff
between two independently computed plans at a settled boundary (Chebyshev tol 3), with the earlier
plan's unexecuted edits folded as an `EditSnapshot` baseline. `pathing.async` default TRUE; `false` =
byte-identical sync. The region tier stays TICK-CONFINED (§4.5) — workers never touch RegionGrid.
- Files: `pathfinding/async/PlanExecutor.java`, `PlanHandle.java`, `SearchRequest.java`,
  `pathfinding/splice/SpliceSeam.java`
- Entry: `PlanExecutor.start` from `OrebitCommon.init` (when async); submit/poll from `PathPlan`.
  Spec: `DESIGN-background-pathfinding.md` §3–§5, §7.

## pathfinding/PathPlan — the two-tier driver (+ its extracted collaborators)
Owns the region skeleton + a WINDOW of consecutive skeleton regions; runs the block tier toward
`windowTarget()`. Window commit = forward-slide: `committedIndex` advances when none of the active
block plan's REMAINING waypoints map back into the committed span (the wiggle rule, keyed on
(region, fragment)); an already-satisfied target is committed+slid at selection time — no debounce.
Repair is event-driven: one repair per BLOCKED search result (`blockedGeneration` — blacklist the hop
by content key, reroute). Async: submit/poll/seam-adopt at the settled boundary; `pollWhenPlanless`
first-plan adoption; P4 pre-plan from the predicted end cell. Under the rolling skeleton it consumes
the cascade's EXTENDED verdict by shifting its cursors/BLOCKED snapshot and keeping the live block
plan (`DESIGN-rolling-skeleton.md`, core-wt). Package-private collaborators: **`WindowTargeting`**
(target-selection policy — goal-in-window, farthest-usable-portal, buried DIG centroids, footprint
snap, swim-Y re-derivation; returns target + step + `TargetKind`), **`AsyncWindowSearch`** (the async
mailbox — in-flight handle, parked pre-plan, one-attempt-per-target churn guard; `drainPending` →
NONE/RETRY/RESULT), **`SkeletonDump`** (cold diagnostics formatter behind `describeSkeleton`).
- Files: `pathfinding/PathPlan.java`, `WindowTargeting.java`, `AsyncWindowSearch.java`,
  `SkeletonDump.java`, `PathStatus.java`, `PathDebugRenderer.java`
- Entry: owned by `BotNavigator` (`driveToward`/`onBotMoved`); statuses OK/BLOCKED/FAILED.
  Spec: HPA-IMPLEMENTATION.md §9, HPA-CASCADE.md, DESIGN-background-pathfinding.md §5/§7.

## Root package — orchestrator + navigator + gather + portal + mining + lifecycle
`AllyBotEntity extends FakePlayerEntity implements BotSteering` (~1000 lines) — the ORCHESTRATOR:
entity identity, full vanilla player tick (forge inputs → `super.tick()` → `doTick()` →
`MoveReport.after()`), runtime survival gating (invuln/air/hunger per config), mode dispatch
`Mode {FOLLOW, STAY, COME, GATHER}`, the `BotSteering` seam (incl. `setDoorOpen`), and the `/bot
trace`/`rtrace` one-shots. Behavior lives on components it constructs and ticks:
- **`BotNavigator`** — the two-tier drive/follow: `driveToward` (arrival test → skeleton commit →
  readiness gate → replan-or-slide; emits a `driveState` label), `replan` (fresh `PathPlan`,
  degrade-to-no-plan on bugs), `steerAlongPath` (waypoint cursor by occupancy; per-step `MovePlan` via
  `PhaseRunner`, legacy one-shot steer for unconverted moves; validity-envelope failure → log-once +
  HOLD, no auto-replan while movement bugs are hunted), `repairStep` (BLOCKED → one blacklist+reroute
  per generation; FAILED → `giveUp()`), `applyEdits` (server-side break/place/door execution,
  live-revalidated). A planless bot WAITS — no straight-line fallback, no motion-signature stuck
  recovery (s52); `dumpStuck` is diagnostic-only. Owns the `NavJourneyStats` pair (live +
  last-completed).
- **`BotGatherer`** — the `/bot gather` machine: `GatherPhase {SCAN, MINE, COLLECT, COMPASS, RETURN}`;
  nearest-first readiness-gated scan, two-A\* route-cost target selection, LOS-occluder dig, timed
  mine with drop-goal tool gating, COLLECT tracks the real `ItemEntity` by lifecycle (no timers),
  COMPASS walks toward a `ResourcePyramid` hint, RETURN to the issue cell.
- **`BotPortalFollower`** — cross-dimension FOLLOW/COME: seek nearest known portal
  (`NetherPortalIndex`), path to its bottom cell, ENTER terminal state (face, walk in, stand still);
  success = event-detected level change, failure = state-detected (portal broke / vanilla-derived
  100-tick in-column bound → one retry, then give up).
- **`BotCrafter`** — the `/bot craft` machine (DESIGN-bot-abilities.md §3): `CraftPhase {PLAN,
  SEEK_TABLE, PLACE_TABLE, CRAFT, RECLAIM}`; one craft op per tick, each re-planned against the
  LIVE inventory; 2x2 recipes anywhere, 3x3 within reach (4.5) of a crafting table — seeks the
  nearest via `ResourceScan.nearestLoadedCell` (`crafting.tableSearchRadius`), else places a
  temporary one from inventory (crafting it first from planks when needed, `crafting.placeTable`)
  and reclaims it after (`crafting.reclaimTable` — the ONE narrow `mining.protectedBlocks` waiver:
  `BotMining.requestReclaim`, exact bot-placed cell only, state re-verified). Headless server-side
  crafting (no menus/recipe book — the vanilla-Crafter/Carpet precedent) via `crafting/RecipeIndex`
  (result-name → `KnownRecipe` index baked at SERVER_STARTED + on `/bot config reload`; recipes are
  datapack-loaded, NOT available under Bootstrap) + `KnownRecipe.planFrom` (deterministic
  index-order slot assignment, storage slots 0-35 only) + `CraftAssignment.execute` (vanilla
  matches/assemble/remainders through the `platform/CraftingOps` seam — 10 overlay flavors,
  anchored on the byte-stable `MinecraftServer#getRecipeManager`).
- **`BotFarmer`** — the `/bot farm` machine (DESIGN-bot-abilities.md §4): one tending PASS —
  `FarmPhase {SURVEY, WORK, SWEEPUP}` over `farming.workRadius` around the issue cell. Harvests
  mature crops (`BotMining.requestHarvest`, the crop-side protected waiver), replants/plants bare
  farmland, tills hydrated ground with a carried hoe (`farming.till`; the EXACT vanilla water
  rule — any water fluid in the 9×9×2 Chebyshev-4 box via the `platform/FluidRead` seam), all
  through the REAL vanilla use path (`platform/ItemUse.useOnTop` → `ItemStack#useOn`) and
  VERIFIED by re-reading the world (never the drifting InteractionResult). SWEEPUP collects the
  pass's own drops by item lifecycle (yield + replant seeds ride vanilla's pickup delay) before
  the final re-survey ends the pass. Crop facts = `farming/CropKinds` Strategy classes
  (wheat/carrots/potatoes/beetroots v1; age read generically off the `age` property; kinds baked
  at SERVER_STARTED). Seeds are EXCLUDED from bridging (`consumeOnePlaceable`/premium skip
  `CropKinds.isSeedItem`).
- **`BotFighter`** — the SELF-DEFENSE INTERRUPT (DESIGN-bot-abilities.md §2.3/§5; NOT a Mode):
  checked BEFORE the mode dispatch each tick, and while a threat is engaged it CONSUMES the tick —
  the current mode's machine freezes in place and resumes when combat ends (the
  `followThroughPortal` consumed-tick precedent; the live analogue of the Phase-7 StateStack).
  Threat = the nearest live `Mob` whose `getTarget() == bot` within `combat.scanRadius` (the mob's
  own declared state, never proximity heuristics; quiescent under the invulnerable default).
  Per-archetype `MobStrategy` classes (first-match order): `CreeperStrategy` (standoff ≥4.0 while
  recharging, full-charge SPRINT knockback hits at ≤2.9, disengage past the 7-block keep-swelling
  bound — all constants source-verified, none tuned), `SkeletonStrategy` (sprint in — arrows lead
  nothing; family classed via the `platform/MobKinds` seam, the class moved packages at 1.21.11),
  `MeleeStrategy` (fallback: face, close, full-charge hits only — `getAttackStrengthScale` reads
  the REAL vanilla ticker, no derived clock). Hits ride the inherited `Player#attack` (crit/sweep/
  knockback/enchants all vanilla); `BotInventory.equipBestWeapon` ranks sword>axe by id tier.
- **`BotMining`** — the per-tick timed-break actuator: callers `request(pos)` every tick; equips
  fastest/goal tool, accumulates vanilla `getDestroyProgress`, crack overlay, real survival break
  (drops/XP/wear), `Config.mayBreak` backstop (waived only for `requestReclaim` of a bot-placed
  crafting table and `requestHarvest` of a known MATURE crop); `busy()` gates forward motion.
- **`BotManager`** — static owner-UUID→bot registry: production spawn (deterministic UUID,
  `orebit-bots.properties` orphan adoption, `BotSpawn.place`, revive, forced SURVIVAL, cross-dimension
  teleport-back), remove on disconnect. `BotPositioning` = safe-spot/face helpers. `Debug` = the two
  static toggles (`/bot debug`). `SlowTickMonitor` = slow-tick attribution (ourOps/gc/other +
  per-phase buckets, logs when `Debug.VERBOSE`). `FakePlayerEntity`/`FakeClientConnection` live in
  `overlays/` (version-fragile network internals; `FakeNetworkHandler` is gone).
- Files: `AllyBotEntity.java`, `BotNavigator.java`, `BotGatherer.java`, `BotCrafter.java`,
  `BotPortalFollower.java`, `BotMining.java`, `BotManager.java`, `BotPositioning.java`,
  `NavJourneyStats.java`, `SlowTickMonitor.java`, `Debug.java`,
  `crafting/{RecipeIndex,KnownRecipe,IngredientSlot,CraftAssignment}.java`,
  `overlays/<era>/java/com/orebit/mod/FakePlayerEntity.java`,
  `overlays/<era>/java/com/orebit/mod/platform/CraftingOps.java`
- Entry: `OrebitCommon.init` join/disconnect/tick events → `BotManager`; the entity's vanilla `tick()`
  drives everything else.

## Test harnesses (root package) — headless end-to-end + synthetic courses + replay
All registered LAST in `OrebitCommon.init`, each inert unless its own `-Dorebit.<x>` system property
arms it; each writes an `orebit-<x>-result.properties` + traces, then halts the server + `System.exit`.
- **`HeadlessAutotest`** (`-Dorebit.autotest`, driven by `scripts/run-autotest.ps1`): the end-to-end
  scenario harness — adds NO behavior, drives the REAL bot through the production command API on a
  scenario world (GOTO/GATHER/TRACE/RTRACE/PROBE-ONLY modes via `orebit.autotest.*` properties).
  Spawns a synthetic never-placed owner + production `BotManager.spawnBotFor`; defers the command
  until start-delay AND nav-grid residency (`NavStore.ringBuilt`). PASS/FAIL = the bot's OWN signals
  (mode→STAY + arrival re-verify; `navGaveUp()`/death = fast FAIL; tick budget = slow FAIL). The
  probe box (`-ProbeOnly`) dumps the start column, a 5×5 silhouette, an FNV neighborhood signature,
  and a radius-3 block box → `orebit-autotest-startprobe.txt` (worldgen-determinism forensics).
  Result file carries granular gather attribution (phaseReached/collected/quota/outcome) +
  `navstat_*` NAVSTATS aggregates. Always run `-MasterWorld` (frozen world — see CLAUDE.md).
- **Course builders** — synthetic single-pathology worlds, one trial grid each, fatal-miss verdicts:
  `ParkourCourse` (`-Dorebit.parkour`, isolated jump shapes), `SwimCourse` (`-Dorebit.swim`, walled
  water tanks incl. bubble columns/kelp), `IceCourse` (`-Dorebit.ice`, 1-wide blue-ice lanes flanked
  by lava — turn-overshoot detector), `IceParkourCourse` (`-Dorebit.iceparkour`, sprint-parkour onto
  ice landings, slide-overshoot measurement; brake prototypes behind `-Dorebit.iceparkour.brake`).
- **`WorldReplay`** (`-Dorebit.replay`, `run-replay.ps1`): replays a recorded failure route in the
  owner's hand-built real world (loads, never builds), per-tick position/velocity/water-state + plan
  trace, EJECTION guard.
- Files: `HeadlessAutotest.java`, `ParkourCourse.java`, `SwimCourse.java`, `IceCourse.java`,
  `IceParkourCourse.java`, `WorldReplay.java`, `scripts/run-autotest.ps1`
- Entry: `*.register()` from `OrebitCommon.init` (no-op unless armed).

## commands/ — the /bot surface
`OrebitCommands.register` builds the Brigadier `/bot` root at the `PlatformEvents.onRegisterCommands`
seam; each subcommand is a stateless `BotCommand` Strategy. Present (**19**): Spawn, Follow, Stay,
Come, Goto, Mine, Find, Gather, **Craft** (`/bot craft <item> [count]` — result names tab-completed
from `crafting/RecipeIndex`; see `BotCrafter`), **Farm** (`/bot farm` — one tending pass; see
`BotFarmer`), **Drop** (`/bot drop <all|resources|tools|trash|name>` — tosses
matching inventory via `BotInventory.dropMatching` + the `ItemClasses` taxonomy), **Report** (`/bot
report` — the resource-compass abundance table: near/mid/far player-centered box sums + true-global,
from `ResourcePyramid`), **Stats** (`/bot stats` — the NAVSTATS current + last-journey tables), Here,
Trace, RegionTrace (`/bot rtrace`), Probe, Config, Debug. The `ChatCommandParser`/`CommandDispatcher`/
`CommandHandler`/`CommandRegistry`/`ParsedCommand` files in the same package are legacy design stubs.
- Files: `commands/OrebitCommands.java`, `BotCommand.java`, `GatherCommand.java`, `ProbeCommand.java`,
  `StatsCommand.java`
- Entry: `OrebitCommands.register(events)` from `OrebitCommon.init`.

## config/ — owner knobs
`Config` = validated immutable record; groups now: `survival.*`, `placement.*` (incl.
`removalCostWeight`, `placeBaseCost`), `mining.*` (incl. `breakBaseCost`, `protectedBlocks`,
`allowUnbreakable`, `unbreakableHardness`), `pathing.*` (incl. `greedyWeight`, `costPerHitpoint`,
`warmup*`, the async trio, `chunkBuildsPerTick`/`chunkBuildBudgetMs`, `navReadyRadiusChunks`/
`navReadyTimeoutTicks`, `hpaFlushBudgetMs`, `regionShardLoadBudgetMs`), `hpa.*`
(`persistIntervalTicks`, `persistFlushBudgetMs`, `lazyLoad`, `residentLeafCap`), **`doors.*`**
(`doors.toggle`, default true → `BotCaps.mayToggleDoors`), **`crafting.*`**
(`placeTable`/`reclaimTable`/`tableSearchRadius`), **`farming.*`** (`workRadius`/`till`), and
**`combat.*`** (`defend`/`scanRadius`) — the ability namespaces are executor-read and fully hot.
`toBotCaps()` folds knobs into the
pathfinder's `BotCaps`; `mayBreak()` = executor-side break-policy backstop; `conjuredBlockState()`.
`ConfigLoader.load` reads `config/orebit.properties` at SERVER_STARTED (writes commented defaults
first run); reload re-bakes `MiningModel` and drains the planner pool first (and re-bakes the
`/bot craft` `RecipeIndex` as a courtesy). `ConfigValidator`
clamps-and-warns, never fatal. `ProtectedBlocks` parses ids + `#tags` → NavBlock PROTECTED bit
(planner) + `mayBreak` (executor). Key reference: `internal_docs/CONFIG.md`.
- Files: `config/Config.java`, `ConfigLoader.java`, `ConfigValidator.java`, `ConfigKeys.java`,
  `ProtectedBlocks.java` (stubs: `GlobalSettingLimits`, `HotReloadManager`, `PolicyOverrideSource`)
- Entry: `ConfigLoader::load` from `OrebitCommon.init`; `/bot config` for get/set/reload.

## platform/ + overlays — the version/loader seam
`PlatformEvents` = the ONE loader interface (8 hooks: onServerStarted, onServerStopping,
onPlayerJoin, onPlayerDisconnect, onChunkLoad, onChunkUnload, onWorldTickEnd, onRegisterCommands);
per-loader impls in the thin `fabric/`, `forge/`, `neoforge/` modules call `OrebitCommon.init`
(`ForgePlatformEvents` lives in `overlays-forge/<era>/`; `overlays-fabric/<era>/` carries
`FabricCommandRegistrar` flavors; `FabricPlatformEvents` is era-owned per branch). Version-STABLE
adapters in `src/.../platform/`: `PlatformEvents`, `BlockChangeEvents`, `BotInventory`,
`SectionPalette`, `WorldEdits`. Version-DIVERGENT adapters live in
`overlays/<era>/java/com/orebit/mod/platform/` (eras compose, highest ≤ active wins; 17 era dirs,
1.17 = baseline): BlockKinds, BlockLookup, BlockShapes, BotSpawn, BotTeleport, ChunkCoords,
ClientLoad, CommandFeedback, ConcretePowder, ConfigDir, **DimensionId**, EntityState, ItemDamage,
**ItemLookup**, LevelBounds, MineableTags, MoveReport, Replaceable, Sections, TagLookup,
**ToolEnchants**, VersionedBlocks, Worlds — static one-liners so the JIT inlines them. Non-platform
overlay classes: `FakePlayerEntity`, `FakeClientConnection`, and the `LevelChunkMixin` (1.17/1.21.5).
Keep core logic in `src/`; only the thin MC-API call goes in an overlay.
- Files: `platform/PlatformEvents.java`, `platform/BlockChangeEvents.java`,
  `overlays/*/java/com/orebit/mod/platform/*`
- Entry: loader entrypoints (`OrebitFabric`/`OrebitForge`/`OrebitNeoForge`) →
  `OrebitCommon.init(PlatformEvents)`.

## worldmodel/resource — the resource layer
Parallel to the cost pyramid on the same `RegionAddress` octree. `ChunkNavBuilder` tallies indexed
resources during the SINGLE classify pass into `NavSection.resourceTally` (`Log2Codec` byte per
`ResourceClasses` column, nullable = sparse). `ResourcePyramid` (per-dimension SoA, owned by
`RegionGrid`) interns rows only for sections with ≥1 indexed block; `ResourceMerger` rolls ancestors
up from `HpaMaintenance.onChunkNavBuilt` (+ `reconcileNode` for the persistence seam); `ResourceQuery`
= best-first drill-down `find(level, column, anchor, minCount, maxResults)` → nearest-first
`ResourceHit`s, plus the `windowLog2`/`globalLog2` box-sum helpers behind `/bot report`;
`ResourceScan.exactCells` scans a section live for exact positions. **`DropModel`** = the phase-2
output-item → source-resource + tool-condition (SILK_REQUIRED/NO_SILK/EITHER) resolution table (the
stone-vs-cobblestone seam for `/bot gather`). **`ItemClasses`** = carried-item taxonomy
(resource/tool/armor/trash, id-suffix matched via `ItemLookup`) behind `/bot drop`. The pyramid is
now PERSISTED via the res shard files (see worldmodel/persistence).
- Files: `worldmodel/resource/ResourcePyramid.java`, `ResourceQuery.java`, `ResourceScan.java`,
  `ResourceClasses.java`, `ResourceMerger.java`, `Log2Codec.java`, `DropModel.java`, `ItemClasses.java`
- Entry: produced by `ChunkNavBuilder` → `HpaMaintenance`; consumed by `/bot find`, `/bot report`,
  `/bot drop`, and the gather COMPASS loop. Spec: `DESIGN-find-mine-resources.md` §6/§8.5.
