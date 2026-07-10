# SUBSYSTEMS — map of the RUNNING code (s52)

One screen per subsystem: what it does, key files, entry points. Paths relative to
`src/main/java/com/orebit/mod/` unless noted. Stub-only packages (ai/, tasks/, integration/, …) are NOT
listed — see CLAUDE.md. Historical design docs referenced by code Javadocs live beside this file
(condensed, each with a §-map).

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
neighbour bits) + parallel `byte[4096]` depth nibbles (floorGap low / runUp high). `NavSection` wraps a
grid + nullable log₂ `resourceTally`; `NavSectionPool` recycles; `NavStore` = per-level
chunkKey→NavSection[] map. `ChunkNavBuilder` does the two-pass column build (classify + tally + portal
discovery, then flags with 3-row vertical overscan); `NavSectionBuilder` holds the classify /
`patchCell` / `computeDepth` kernels (palette reflection — most version-fragile code). `ChunkNavLoader`
defers builds ≤8/tick on chunk load. `NavGridView` is the read seam (per-search chunk cache;
`background(level)` = planner-thread view, no live fallback). `NavGridUpdater` patches cells on
`BlockChangeEvents` + bumps the per-level `editEpoch` (the follower's terrain-recheck signal).
`NavReclaim` = epoch-deferred section reclamation for async readers. `NavWarmup` = boot JIT warm-up
(first search 21.8→0.67 ms). `NetherPortalIndex` = per-dimension portal-column index.
- Files: `worldmodel/pathing/TraversalGrid.java`, `NavSectionBuilder.java`, `NavStore.java`,
  `NavGridView.java`, `ChunkNavLoader.java`, `NavGridUpdater.java` (+ `NavReclaim`, `NavWarmup`,
  `NetherPortalIndex`)
- Entry: `ChunkNavLoader.register` / `NavGridUpdater.register` from `OrebitCommon.init`.

## pathfinding/blockpathfinder — block-tier A\* + movements + cuboids + phases + steering
`BlockPathfinder.findPath` — allocation-free A\* over floor cells (SoA node state, open-addressed
long→row map, binary heap, per-search `EditPool` arena); returns `BlockPathPlan` (waypoints + per-step
`Movement` + `StepEdits`). 14 movements behind `MovementRegistry.TIER1`: Traverse, Diagonal, Ascend,
Descend, Fall, Pillar, MineDown, Climb, Parkour, DiagonalParkour, Swim, SprintSwim, StartSprintSwim,
Surface. `cuboid/` = the macro subsystem (`CuboidExtractor` maximal uniform boxes, `MacroJump`,
`GoalForcedCost` heuristic premium, `NavGridCuboidsView` per-search cache). Phase framework:
`Movement.plan()` → `MovePlan` (guard-based phases, Need.AIR/FOOTING) → `PhaseRunner` (per-tick
self-healing cursor). Steering: `BotSteering`/`SteerView` seams + `SteerControl` statics
(`steerTowards`, `drive`, `swimTowards`, **`holdDepth`** — the bang-bang water autopilot; s52: the
movements OWN vertical water control, the old follower water rule is gone). `MovementContext` =
predicate vocabulary incl. `transitOrBreak`; `MiningModel` = tool-tick table + per-search snapshot;
edits = `EditScratch`/`StepEdits`/`PathEdits`/`EditSnapshot`; `BotCaps` = capability gate.
- Files: `pathfinding/blockpathfinder/BlockPathfinder.java`, `MovementContext.java`, `MiningModel.java`,
  `SteerControl.java`, `movements/*.java`, `cuboid/CuboidExtractor.java`
- Entry: `PathPlan.replanBlock` (sync) or `PlanExecutor` workers (async); steer hooks driven per-tick by
  `AllyBotEntity`.

## pathfinding/regionpathfinder + worldmodel/hpa — region tier (fragments + cascade + pyramid)
`RegionPathfinder.plan` — fragment-model HPA\* where node = (region, fragment, entry-face); edges =
portal crossings (footprint overlap), always-possible dig-through (span × tool-aware `RegionMineModel`
cost), walk-across priced entry→exit; standable-Δy anchors + flood-from-bot start membership (s51/s52).
`HierarchicalRegionPlan` = the cascade: per-level skeleton stack, re-plan only the exited level, top
slides. `RegionEdgeBlacklist` = directed crossing blacklist for event-driven repair. hpa/ data layer:
`RegionGrid` (per-dimension façade owning `CostPyramid` + `ResourcePyramid`; `headless()` for tests),
`RegionFragments`, `FragmentBuilder`/`FragmentLeafComputer`/`LeafCostComputer`, `CostCodec`,
`PyramidMerger` (coarse roll-up), `HpaMaintenance` (debounced dirty-leaf recompute on block change).
- Files: `pathfinding/regionpathfinder/RegionPathfinder.java`, `HierarchicalRegionPlan.java`,
  `RegionMineModel.java`, `worldmodel/hpa/RegionGrid.java`, `FragmentBuilder.java`, `HpaMaintenance.java`
- Entry: `PathPlan` construction/reroute; `HpaMaintenance.register` from `OrebitCommon.init`.

## pathfinding/async + splice — planner pool + plan handoff
`PlanExecutor` = fixed daemon pool (`pathing.maxThreads` def 2, clamp [1, cores−2]); tick thread
`submit`s an immutable `SearchRequest` (level = identity only; workers read NavStore sections via the
no-fallback view), polls a `PlanHandle` (volatile-done mailbox; `wasRejected` → retry, not blacklist);
per-thread epoch stamps feed `NavReclaim`. `SpliceSeam` = seed→accept→adopt handoff between two
independently computed plans at a settled boundary, with the earlier plan's unexecuted edits folded as
an `EditSnapshot` baseline. `pathing.async` default TRUE; `false` = byte-identical sync.
- Files: `pathfinding/async/PlanExecutor.java`, `PlanHandle.java`, `SearchRequest.java`,
  `pathfinding/splice/SpliceSeam.java`
- Entry: `PlanExecutor.start` from `OrebitCommon.init` (when async); submit/poll from `PathPlan`.

## pathfinding/PathPlan — the two-tier driver
Owns the region skeleton + a WINDOW of consecutive skeleton regions; runs the block tier toward
`windowTarget()` (~3 regions ahead, a real portal cell, snap-to-standable; dig-through targets are
known-buried). **Window commit = forward-slide (s52):** `committedIndex` advances when none of the
active block plan's REMAINING waypoints map back into `[committedIndex, j)` (the wiggle rule, keyed on
(region, fragment)); an already-satisfied target is committed+slid at selection time — no debounce
fallback exists. **Repair is event-driven:** `repairBlocked()`/`blockedGeneration()` — one repair per
BLOCKED search result (blacklist the hop, reroute). Async: submit/poll/seam-adopt at the settled
boundary; `pollWhenPlanless` for first-plan adoption; §7 pre-plan from the predicted end cell.
- Files: `pathfinding/PathPlan.java`, `PathStatus.java`, `PathDebugRenderer.java`
- Entry: owned by `AllyBotEntity` (`driveToward`/`onBotMoved`); statuses OK/BLOCKED/FAILED.

## Root package — follower, gather, mining, lifecycle
`AllyBotEntity extends FakePlayerEntity implements BotSteering` — full vanilla player tick (forge inputs
→ `super.tick()` → `doTick()` → `MoveReport.after()`). Modes FOLLOW/STAY/COME/GATHER; plans via
`PathPlan`, steers along waypoints via the movements' steer/phase hooks, WAITS when planless (s52 — no
blind walking, no startup hop). **No motion-signature stuck recovery (s52):** `stuckTicks` etc. are
diagnostic-only (`dumpStuck`); recovery = event-driven `repairStep()` on BLOCKED + `giveUp()` on FAILED.
Portal-seek/ENTER terminal states for cross-dimension FOLLOW (`NetherPortalIndex`). GATHER:
`GatherPhase {SCAN, MINE, COLLECT, COMPASS, RETURN}` — `gatherMine` digs the eye→ore ray open via
`firstOcclusion` (mayBreak-gated); COLLECT tracks the actual dropped `ItemEntity` by lifecycle
(airborne→wait, grounded→path to it, removed→count by inventory delta; `COLLECT_TIMEOUT` deleted s52).
`BotMining` = timed survival breaking (`request(pos)`, `busy()`, vanilla `getDestroyProgress`
accumulation, drops/XP/wear, `Config.mayBreak` backstop). `BotManager` = static owner-UUID→bot map,
spawn/remove on join/disconnect, player-data persistence. `FakePlayerEntity`/`FakeNetworkHandler` live
in `overlays/` (version-fragile network internals).
- Files: `AllyBotEntity.java`, `BotManager.java`, `BotMining.java`, `BotPositioning.java`,
  `overlays/<era>/java/com/orebit/mod/FakePlayerEntity.java`
- Entry: `OrebitCommon.init` join/disconnect/tick events → `BotManager`.

## commands/ — the /bot surface
`OrebitCommands.register` builds the Brigadier `/bot` root at the `PlatformEvents.onRegisterCommands`
seam; each subcommand is a stateless `BotCommand` Strategy. Present: Spawn, Follow, Stay, Come, Goto,
Mine, Find, Gather, Here, Trace, RegionTrace (`/bot rtrace`), Probe, Config, Debug (14). The
`ChatCommandParser`/`CommandDispatcher`/… classes in the same package are legacy design stubs.
- Files: `commands/OrebitCommands.java`, `BotCommand.java`, `GatherCommand.java`, `ProbeCommand.java`
- Entry: `OrebitCommands.register(events)` from `OrebitCommon.init`.

## config/ — owner knobs
`Config` = validated immutable record (survival/placement/mining/pathing groups mirroring `ConfigKeys`);
`toBotCaps()` folds knobs into the pathfinder's `BotCaps`; `mayBreak()` = executor-side break-policy
backstop. `ConfigLoader.load` reads `config/orebit.properties` at SERVER_STARTED (writes commented
defaults first run); reload re-bakes `MiningModel` and drains the planner pool first.
`ConfigValidator` clamps-and-warns, never fatal. `ProtectedBlocks` parses ids + `#tags` → NavBlock
PROTECTED bit (planner) + `mayBreak` (executor).
- Files: `config/Config.java`, `ConfigLoader.java`, `ConfigValidator.java`, `ConfigKeys.java`,
  `ProtectedBlocks.java` (stubs: `GlobalSettingLimits`, `HotReloadManager`, `PolicyOverrideSource`)
- Entry: `ConfigLoader::load` from `OrebitCommon.init`; `/bot config` for get/set/reload.

## platform/ + overlays — the version/loader seam
`PlatformEvents` = the ONE loader interface (cold hooks: serverStarted, join/disconnect,
chunkLoad/Unload, worldTickEnd, registerCommands); per-loader impls in the thin `fabric/`, `forge/`,
`neoforge/` modules call `OrebitCommon.init`. Version-STABLE adapters in `platform/`: `PlatformEvents`,
`BlockChangeEvents`, `BotInventory`, `SectionPalette`, `WorldEdits`. Version-DIVERGENT adapters live in
`overlays/<era>/java/com/orebit/mod/platform/` (eras compose, highest ≤ active wins): BlockKinds,
BlockLookup, BlockShapes, BotSpawn, BotTeleport, ChunkCoords, ClientLoad, CommandFeedback,
ConcretePowder, ConfigDir, EntityState, ItemDamage, LevelBounds, MineableTags, MoveReport, Replaceable,
Sections, TagLookup, VersionedBlocks, Worlds — static one-liners so the JIT inlines them (no dispatch
on hot paths). Keep core logic in `src/`; only the thin MC-API call goes in an overlay.
- Files: `platform/PlatformEvents.java`, `platform/BlockChangeEvents.java`, `overlays/*/java/com/orebit/mod/platform/*`
- Entry: loader entrypoints → `OrebitCommon.init(PlatformEvents)`.

## worldmodel/resource — the resource layer (REAL)
Parallel to the cost pyramid on the same `RegionAddress` octree. `ChunkNavBuilder` tallies indexed
resources during the SINGLE classify pass into `NavSection.resourceTally` (`Log2Codec` byte per
`ResourceClasses` column, nullable = sparse). `ResourcePyramid` (per-dimension SoA, owned by
`RegionGrid`) interns rows only for sections with ≥1 indexed block; `ResourceMerger` rolls ancestors up
from `HpaMaintenance.onChunkNavBuilt`; `ResourceQuery` best-first drill-down to a level-0 region;
`ResourceScan.exactCells` scans that section live for exact positions. Pyramid is chunk-load-driven
(per-block patches keep only the COST pyramid live — `DESIGN-find-mine-resources.md` §8.5).
- Files: `worldmodel/resource/ResourcePyramid.java`, `ResourceQuery.java`, `ResourceScan.java`,
  `ResourceClasses.java`, `ResourceMerger.java`, `Log2Codec.java`
- Entry: produced by `ChunkNavBuilder` → `HpaMaintenance`; consumed by `/bot find` + the gather loop.
