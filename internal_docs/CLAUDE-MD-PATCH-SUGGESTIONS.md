# CLAUDE.md repo-map corrections (proposed — owner review; do NOT auto-apply)

Originally verified against the mc-1.21 worktree (`orebit-mc121-wt`) source on 2026-07.
**Re-verified against `core` HEAD on 2026-08-07 (doc-audit session); every item below is still
UNAPPLIED to CLAUDE.md.** Each bullet is a ready-to-apply diff to CLAUDE.md's Repo map / gotchas.
Ordered by importance.

## 1. Movement roster: 14 → 18
- CLAUDE.md: "the `Movement` set (**14**: …)".
- Reality (`MovementRegistry.TIER1`, registration order = cost-tie priority): Traverse, Diagonal,
  Ascend, Descend, Fall, Pillar, MineDown, Swim, SprintSwim, StartSprintSwim, Surface, Climb,
  Parkour, DiagonalParkour, **WalkOff** (no-jump gap-1/descend-1 crossing — the honey/low-ceiling
  crosser), **DiagonalSprintSwim** (26-connected underwater diagonals + corners, extends SprintSwim),
  **RideBubbleColumn** (up-column conveyor ride, `commitsAcrossArrival`, 3-phase plan),
  **EndSprintSwim** (PRONE→STANDING in-place pose transition — the sibling of StartSprintSwim).
- **The swim vocabulary was re-cut 2026-08-07 ("fluid is a MEDIUM, not a pose" — owner ruling;
  spec `NOTES-movement-physics.md`).** CLAUDE.md's one-line gloss "the swim family
  `Swim`/`SprintSwim`/`StartSprintSwim`/`Surface`" is now wrong in every part:
  - `Swim` is the **upright six-directional medium move** (4 cardinals + straight rise + straight
    sink, all in `MODE_STANDING`), structurally analogous to `Climb` — several rungs, each priced
    from its own vanilla rate. Its head test is now "**not solid**" (air *or* fluid), not
    `passable`, so a DRY bot can walk into a submerged cell; the dry-entry and submerged rungs are
    priced apart (`COST` vs `SUBMERGED_COST`).
  - `SprintSwim` **lost its pure-up and pure-down rungs** — prone sprint-swim is now fast *lateral*
    travel only. (Rationale: a swimming look clamps near 80°, so a "vertical" prone rung always
    carries lateral drift, which in a 1×1 waterfall is ejection at speed.)
  - `Surface` **narrowed to the prone bank crawl-out** — the one exit with no headroom to stand up
    in. It is no longer the general water-to-land transition.
  - `EndSprintSwim` is new; the pose pair is now StartSprintSwim (STANDING→PRONE) /
    EndSprintSwim (PRONE→STANDING), both in place.
- Also add: `ParkourEnvelope` — NOT a movement; a derived static ballistics admission table
  (`MAX_GAP[startTopY][gsf][occ]`) read by Parkour/DiagonalParkour, replacing hand-tuned gap
  constants (from `internal_docs/parkour_envelope_params.py`; it superseded the hand-tuned envelope
  design docs, whose surviving core is now `NOTES-movement-physics.md` §1–§2).

## 2. The DOORS thread is entirely missing — **APPLIED 2026-08-09**
- New config group `doors.toggle` (default true) → `BotCaps.mayToggleDoors`; `MovePlan` has
  `Need.OPEN` + `requireDoor(x,y,z,open)`; door-set edits ride the whole edit pipeline
  (`EditScratch`/`StepEdits`/`PathEdits`/`EditSnapshot` carry door arrays); `BotSteering.setDoorOpen`;
  `TraversalGrid` has an `anyDoor` prefilter. Add to the blockpathfinder + config bullets.
- **APPLIED (trapdoor-arc doc pass, 2026-08-09):** the doors+trapdoors toggle-thread sentence now
  lives in CLAUDE.md's blockpathfinder Repo-map entry (inside the `EditScratch`/`StepEdits` clause).
  Caveat on the last clause: the `anyDoor` prefilter was since REMOVED by the trapdoor arc (measured
  useless, DESIGN-trapdoors.md §8) — do not apply that part.

## 3. Persistence bullet is a full generation stale
- CLAUDE.md: "`RegionPersistence` writes each dimension's level-0 leaves to `<world>/orebit/<dim>/hpa.bin`
  … + `res.bin` … gzip body".
- Reality: **sharded .mca-style, uncompressed** — `hpa.<X>.<Z>.bin` (levels 0–5 per level-5 shard,
  magic OBHS) + `hpa.coarse.bin` (level 6) + `res.<X>.<Z>.bin` / `res.coarse.bin` (levels 6–21);
  old blobs ignored. Cost files carry an **invalidation section** (sig-tagged 24-byte
  `{fromKey,toKey,capsSig}` rows, assign-to-FROM, merged into `RegionCrossingMemory` on load;
  `PROV_ESCALATION` never persisted).
  **Version correction (2026-08-07):** an earlier draft of this item said "`CostPyramidCodec` file
  VERSION 7 / v4 invalidation section". Both were **RESET to 1** by the 2026-07 `packLevelKey`
  repack. **Do not "restore" 7/4 and do not bump to v8** (see the code comment in
  `CostPyramidCodec` and `NOTES-region-tier.md` §4). *(Update 2026-08-09: ALL persistence versions
  PINNED at 1 pre-release — owner ruling, zero wild installs; the interim v2/v4 bumps were reverted
  and semantic changes now append to the codec Javadoc history instead. Live values:
  `CostPyramidCodec.VERSION = 1`, `INVAL_SIG_SCHEMA_VERSION = 1`, `ResourcePyramidCodec.VERSION = 1`.)*
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

## 6. Root package missing ELEVEN REAL files (harness suite + the abilities arc)
- Add: `HeadlessAutotest.java` (~830 lines — the `-Dorebit.autotest` end-to-end harness itself,
  currently only described via `scripts/run-autotest.ps1`), `WorldReplay.java` (`-Dorebit.replay`
  real-world failure replay), `ParkourCourse`/`SwimCourse`/`IceCourse`/`IceParkourCourse`/
  `BoxedInCourse` (self-armed synthetic single-pathology course harnesses), `NavJourneyStats.java`
  (per-journey search-health accumulator feeding `/bot stats` + the autotest result file),
  `SlowTickMonitor.java` (slow-tick attribution ourOps/gc/other + per-phase buckets).
- **The whole BOT-ABILITIES arc is absent from CLAUDE.md's root-package bullet** even though the
  memory index records it as "ALL FOUR SHIPPED + ADOPTED on every branch 2026-07-29": add
  `BotFarmer`, `BotCrafter`, `BotFighter`, `BotBuilder` (the four ability machines, siblings of the
  listed `BotGatherer`/`BotMining`), plus the combat strategy family `MobStrategy` (abstract) with
  `MeleeStrategy`/`CreeperStrategy`/`SkeletonStrategy` impls — a textbook instance of the house
  "strategy over conditionals" rule and worth naming as such.

## 7. commands/: add Stats/Farm/Craft/Build; roster is 20
- `/bot stats` (`StatsCommand`) exists — prints the NAVSTATS current + last-completed journey tables.
  `/bot farm`, `/bot craft`, `/bot build` (`FarmCommand`/`CraftCommand`/`BuildCommand`) are the
  abilities-arc surface and are likewise unlisted.
- Live roster (verified from `OrebitCommands`' registration list, `core` HEAD 2026-08-07): spawn,
  follow, stay, come, goto, mine, find, gather, drop, report, stats, here, trace, rtrace, probe,
  config, debug, **farm, craft, build** (20).

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

## 10. Rolling skeleton — LANDED on `core`; PathPlan/HierarchicalRegionPlan description drift
- **Status changed 2026-08-07: this arc is no longer "in flight" — it is committed on `core`**
  (`RegionPathPlan.splice` and `HierarchicalRegionPlan.Verdict{UNCHANGED,EXTENDED,SWAPPED}` both
  exist in the tree). The "update these bullets once the arc lands" deferral is therefore SPENT —
  apply the correction now.
- The cascade/driver bullets predate the arc: increment A makes the window base the committed
  cursor (L≤1), splices-and-extends the L0 skeleton (`RegionPathPlan.splice` — head drop + 1-hop
  tail suffix, cursors shifted never reset) toward L1's advancing hand-down, and turns
  `HierarchicalRegionPlan.onBotMoved` into a three-way verdict UNCHANGED/EXTENDED(shift)/SWAPPED
  (EXTENDED keeps the live block plan and falls through to the commit-slide; the head-drop is read
  back via `lastExtensionShift()`). Spec: `DESIGN-rolling-skeleton.md`.

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

## 14. NavBlock transit-slow field: 3 classes → 4 (lava)
- CLAUDE.md: "a 2-bit transit-slow class (moving THROUGH a cell — berry bush/powder snow light,
  cobweb heavy — distinct from the slow-FLOOR surface field)".
- Reality (`NavBlock.transitSlow`, 2026-08-07): the 2-bit field now has FOUR values —
  `TRANSIT_NONE`/`TRANSIT_LIGHT` (sweet berry bush, powder snow) / `TRANSIT_HEAVY` (cobweb) /
  **`TRANSIT_FLUID`** (~0.4×, LAVA only). The non-obvious part worth carrying into CLAUDE.md:
  **water is deliberately `TRANSIT_NONE`** — the swim rungs' costs already ARE the water rates, so
  a transit surcharge would double-charge; lava gets the class because nothing prices lava travel
  otherwise.

## 15. worldmodel/resource: two unlisted REAL classes
- `DropModel` (phase-2 output-item → source resource + tool condition SILK_REQUIRED/NO_SILK/EITHER —
  the stone-vs-cobblestone gather seam) and `ItemClasses` is listed only parenthetically under
  `/bot drop`; both belong in the resource-layer bullet proper.
