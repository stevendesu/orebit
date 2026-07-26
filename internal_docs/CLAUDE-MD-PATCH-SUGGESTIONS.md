# CLAUDE.md repo-map corrections (proposed — owner review; do NOT auto-apply)

Verified against the mc-1.21 worktree (`orebit-mc121-wt`) source on 2026-07. Each bullet is a
ready-to-apply diff to CLAUDE.md's Repo map / gotchas. Ordered by importance.

## 1. Movement roster: 14 → 17
- CLAUDE.md: "the `Movement` set (**14**: …)".
- Reality (`MovementRegistry.TIER1`, registration order = cost-tie priority): Traverse, Diagonal,
  Ascend, Descend, Fall, Pillar, MineDown, Swim, SprintSwim, StartSprintSwim, Surface, Climb,
  Parkour, DiagonalParkour, **WalkOff** (no-jump gap-1/descend-1 crossing — the honey/low-ceiling
  crosser), **DiagonalSprintSwim** (26-connected underwater diagonals + corners, extends SprintSwim),
  **RideBubbleColumn** (up-column conveyor ride, `commitsAcrossArrival`, 3-phase plan).
- Also add: `ParkourEnvelope` — NOT a movement; a derived static ballistics admission table
  (`MAX_GAP[startTopY][gsf][occ]`) read by Parkour/DiagonalParkour, replacing hand-tuned gap
  constants (from `internal_docs/parkour_envelope_params.py`; supersedes DESIGN-parkour-envelope.md).

## 2. The DOORS thread is entirely missing
- New config group `doors.toggle` (default true) → `BotCaps.mayToggleDoors`; `MovePlan` has
  `Need.OPEN` + `requireDoor(x,y,z,open)`; door-set edits ride the whole edit pipeline
  (`EditScratch`/`StepEdits`/`PathEdits`/`EditSnapshot` carry door arrays); `BotSteering.setDoorOpen`;
  `TraversalGrid` has an `anyDoor` prefilter. Add to the blockpathfinder + config bullets.

## 3. Persistence bullet is a full generation stale
- CLAUDE.md: "`RegionPersistence` writes each dimension's level-0 leaves to `<world>/orebit/<dim>/hpa.bin`
  … + `res.bin` … gzip body".
- Reality: **sharded .mca-style, uncompressed** — `hpa.<X>.<Z>.bin` (levels 0–5 per level-5 shard,
  magic OBHS) + `hpa.coarse.bin` (level 6) + `res.<X>.<Z>.bin` / `res.coarse.bin` (levels 6–21);
  old blobs ignored. `CostPyramidCodec` file VERSION 7; cost files carry the v4 **invalidation
  section** (sig-tagged 24-byte `{fromKey,toKey,capsSig}` rows, assign-to-FROM, merged into
  `RegionCrossingMemory` on load; `PROV_ESCALATION` never persisted).
- New REAL classes to list: `worldmodel/persistence/CostPyramidCodec`, `ResourcePyramidCodec`,
  `RegionShardLoader` (lazy page-in, `pathing.regionShardLoadBudgetMs`), `RegionReconciler`
  (straddle-cell re-derive after lazy load), `RegionEvictor` (cold-shard LRU eviction, OFF at
  `hpa.residentLeafCap=0`); `worldmodel/hpa/RegionShardResidency`, `ShardRowIndex`, `StraddleSet`.
- Note the resource pyramid IS now persisted — contradicts the commands/ bullet's "not persisted
  across restart yet" claim for `/bot report`.

## 4. `worldmodel/region/` no longer exists
- CLAUDE.md still lists "**worldmodel/region/** — REAL data layer (`Region`/`LeafRegion`/
  `CompositeRegion`, `Portal`, `RegionPool`, …) … STUB: `RegionBuilder`".
- Reality: the package was deleted; none of those classes exist anywhere under `worldmodel/`. The
  only remaining reference is dead Javadoc in the `worldmodel/WorldModel.java` stub. Delete the
  whole bullet.

## 5. Invalidation memory classes missing from the hpa bullet
- Add REAL `worldmodel/hpa/RegionCrossingMemory` (per-dim dead-crossing store: per-level rows tagged
  with a caps `realizabilitySig` + provenance PROOF/ESCALATION/ROLLED_UP; `holdsProofDominating`,
  `evictLeafTouching`) and `InvalidationRollup` (the §4b parent-fold; also
  `containedParentFragment` behind `RegionGrid.containedFragment`). Spec:
  `DESIGN-persisted-invalidation-memory.md`. Correspondingly, `BotCaps` gained
  `realizabilitySig()`/`sigDominates` (packed caps + tool tiers) plus fields
  `maxNodes`/`greedyWeight`/`mayToggleDoors` (the old `MAX_EXPANSIONS`/`H_WEIGHT` statics migrated
  onto caps).

## 6. Root package missing six REAL files (and the harness suite)
- Add: `HeadlessAutotest.java` (~830 lines — the `-Dorebit.autotest` end-to-end harness itself,
  currently only described via `scripts/run-autotest.ps1`), `WorldReplay.java` (`-Dorebit.replay`
  real-world failure replay), `ParkourCourse`/`SwimCourse`/`IceCourse`/`IceParkourCourse` (self-armed
  synthetic single-pathology course harnesses), `NavJourneyStats.java` (per-journey search-health
  accumulator feeding `/bot stats` + the autotest result file), `SlowTickMonitor.java` (slow-tick
  attribution ourOps/gc/other + per-phase buckets).

## 7. commands/: add StatsCommand; roster is 17
- `/bot stats` (`StatsCommand`) exists — prints the NAVSTATS current + last-completed journey tables.
  Live roster: spawn, follow, stay, come, goto, mine, find, gather, drop, report, stats, here, trace,
  rtrace, probe, config, debug (17).

## 8. regionpathfinder: three unlisted REAL classes + a wrong stub claim
- Add REAL: `RegionCostField` (dense per-(region,fragment) cost-to-goal field from
  `RegionPathfinder.costToGoalField`, s53 frontier-floor — the `/bot rtrace` A/B heuristic field),
  `RegionPlaceModel` (place-side sibling of `RegionMineModel` — capability-aware pillar/bridge
  per-block cost), `RegionHeuristic` (strategy interface).
- "STUBS: … the `regionpathfinder/heuristics/` specs" is now wrong for one file:
  `SimpleRegionHeuristic` is REAL (the ratified default); the other four variants remain stubs.

## 9. Gotcha "BROKEN: `NavSectionBuilder.build()` is an inert benchmark" is stale
- No `build()` method exists. The live API is `classifyInto`/`classifyNavtypes` + `tallyResources` +
  `computeFlags` + `computeDepth` + `patchCell(s)`, driven by `ChunkNavBuilder`; only a `sink` field
  and legacy reflection helpers remain for the JMH reference bench. Also add REAL
  `worldmodel/pathing/PendingPatches` (per-level open-addressed dirty-cell queue behind
  `NavGridUpdater` → `patchCells` flush barriers; PERF-DESIGN-navgrid-edit-batching.md §4.2).

## 10. Rolling skeleton (in flight) — PathPlan/HierarchicalRegionPlan description drift
- The cascade/driver bullets predate the rolling-skeleton arc: increment A (uncommitted, mc-1.21
  worktree) makes the window base the committed cursor (L≤1), splices-and-extends the L0 skeleton
  (`RegionPathPlan.splice` — head drop + 1-hop tail suffix, cursors shifted never reset) toward L1's
  advancing hand-down, and turns `HierarchicalRegionPlan.onBotMoved` into a three-way verdict
  UNCHANGED/EXTENDED(shift)/SWAPPED (EXTENDED keeps the live block plan and falls through to the
  commit-slide). Spec: `DESIGN-rolling-skeleton.md` (core-wt internal_docs; not yet merged to the
  era branches). Update these bullets once the arc lands rather than now.

## 11. Config surface: many unlisted keys
- Beyond the listed set, `ConfigKeys` now has: `placement.removalCostWeight`, `placement.placeBaseCost`,
  `mining.unbreakableHardness` (listed in prose but not in the key list), `pathing.greedyWeight`,
  `pathing.chunkBuildsPerTick`, `pathing.chunkBuildBudgetMs`, `pathing.navReadyRadiusChunks`,
  `pathing.navReadyTimeoutTicks`, `pathing.hpaFlushBudgetMs`, `pathing.regionShardLoadBudgetMs`,
  `hpa.persistFlushBudgetMs`, `hpa.lazyLoad`, `hpa.residentLeafCap`, `doors.toggle`.

## 12. platform/overlays roster drift
- Add version-DIVERGENT adapters: `DimensionId`, `ItemLookup` (it IS a platform overlay class — the
  commands/ bullet's "`platform/ItemLookup` id seam" is right, the adapter list omits it),
  `ToolEnchants` (silk-touch seam).
- `FakeNetworkHandler` no longer exists anywhere — the root-package bullet should say
  `FakePlayerEntity` + `FakeClientConnection` only (both overlay-resident).
- `overlays-fabric/<era>/` exists (1.17, 1.19: `FabricCommandRegistrar`) alongside `overlays/` and
  `overlays-forge/` — the Overlay-strategy section mentions only the other two.
- "MIXINS: minimal" holds but is no longer zero-detail: `LevelChunkMixin` ships in overlays
  (1.17 + 1.21.5 flavors).

## 13. Async/search API drift (minor)
- `SearchRequest` now carries per-request goal tolerances (`goalTolXZ`/`goalTolY`) + `budgetNanos`;
  `BlockPathfinder` has `DEFAULT_GOAL_TOL_XZ=1`/`DEFAULT_GOAL_TOL_Y=2`; `PlanHandle` also reports
  `wasPartial`/`wasBudgetHit`/`expansions`/`realizedCrossings` (the blame input).

## 14. worldmodel/resource: two unlisted REAL classes
- `DropModel` (phase-2 output-item → source resource + tool condition SILK_REQUIRED/NO_SILK/EITHER —
  the stone-vs-cobblestone gather seam) and `ItemClasses` is listed only parenthetically under
  `/bot drop`; both belong in the resource-layer bullet proper.
