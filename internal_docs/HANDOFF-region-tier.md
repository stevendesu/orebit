# HANDOFF — region-tier air/solid connectivity + gather state (2026-07-04)

> Resume pointer written mid-investigation because the working context filled up. Covers: the `/bot gather`
> arc's current state, the deep region-tier routing bug we just root-caused, and the **owner-ratified design
> direction** for the fix. Read this + the linked images, then continue at "Next step" below.

## TL;DR

`/bot gather` is built and drives the bot, but is not yet usable in-game. Chasing the failures led to a
**region-tier (HPA) connectivity flaw**: the fragment model floods **passable air only**, so an open cavern's
region-crossings are **mid-air points with no floor** (`[air-no-floor]` in the debug dump) — the region A*
literally cannot offer a "cross at this level" hop, only "drop to a floor" or "mine into a buried cell." That
makes the bot drop into caverns and grind. **The ratified fix (below) is to make the region graph fully
connected with an always-possible dig/build edge.**

## Owner-ratified design direction (the fix)

Minecraft is always fully connected — you can always build or break, at a cost. So the region graph should be
**fully connected between adjacent regions**, with two edge kinds:

1. **Fragment/portal crossing (cheap):** the existing air-opening mechanism — walk/parkour through an opening
   where fragment footprints overlap. Keep this; it's the cheap route.
2. **Default crossing (always possible, expensive):** every adjacent region pair ALSO gets a fallback edge
   priced at **`avgRegionHardness × manhattanDistanceTravelledWithinTheRegion`** (the dig/build cost). This is
   admissible (a true lower bound: you must remove at least that much material) and lets A* find a
   straight-dig route (e.g. dig down to ore directly above it) when wandering to a cave is dearer — while
   still preferring the cheap air route when one exists.

Also flagged by the owner: **"we need a much better handle of air/solid at the region tier."** The current
model's air-only flood + mid-air openings is the core weakness; the standable/height structure of an opening
needs to survive into the region tier (see "Root cause" for why a floorless centroid is the failure).

This replaces all the earlier bad ideas we explicitly REJECTED (do not resurrect):
- ❌ climb-aware / asymmetric region heuristic — **inadmissible** (ladders/staircases make "up" cheap).
- ❌ "~N seconds then give up" timeouts — unprincipled hacks.
- ❌ snap-toward-bot as a standalone fix — treats a symptom; the real gap is missing connectivity.

## Root cause (fully traced, with evidence)

The bug: bot on a bridge at Y61 with ore at Y69 (8 blocks UP, across a cavern) — region skeleton routed it
**down** into the cavern (target Y48 → fell to Y34), then it had to pillar ~30 back up, flooding the block
search. Trace:

1. **Fragments flood PASSABLE cells only.** `FragmentBuilder.java:131-132` (seed) + `:168-173` (neighbours)
   test `passable[]` exclusively; standability is only a per-component keep/drop flag (`:143,:153-157,:177`,
   needs ≥1 stand cell ANYWHERE). So an open cavern's whole air column = ONE fragment. `FragmentLeafComputer`
   fills the masks from `NavBlock.isPassable/isStandable` (`:76-90`).
2. **Region edges exist ONLY where fragment footprints overlap** — `RegionPathfinder.java:413-435`,
   `footprintsOverlap :682-698`. A SOLID/buried region (the ore's) has almost no air, so it's reachable only
   from its one air-touched face → forces detours. **No dig-through edge exists** (this is what the fix adds).
3. **A crossing cell = the opening bbox CENTER** — `footprintCenterWorld RegionPathfinder.java:715-733`. For a
   tall cavern opening that's a **mid-air** point.
4. **Snap picks the standable cell NEAREST that mid-air centroid** — `PathPlan.snapInFootprint` (scans the
   footprint bbox; `near = centroid`; `d = sq(x-nx)+sq(y-ny)+sq(z-nz)`). Nearest standable to a mid-air point
   in a cavern = the **floor below** → Y48. (My earlier "free-falls straight down" was WRONG; it's a bbox
   scan, but keyed on the mid-air centroid. The free-fall EXTENSION is a separate all-air fallback.)
5. **The proof** — the initial skeleton dump (`versions/26.2/run/logs/latest.log:275-290`): goal region
   `S12=(5,8,-2) kind=SOLID portal=[buried]`; the skeleton reaches the goal's `ry8` at `S9=(5,8,-3)` (face-
   adjacent to the goal!) then **dips to ry7 (S10,S11) and back up to ry8 (S12)**; and EVERY cavern portal is
   tagged **`[air-no-floor]`**. So there is no level crossing to offer — only floors (below) and buried cells.

Cost constants (for the new default-edge pricing to stay consistent): `LeafCostComputer.java:29-37`
`AIR_TRANSIT_TICKS=16`, `AIR_CLIMB_TICKS=96` → `RegionPathfinder.java:102-108` `WALK_PER_BLOCK=1`,
`FALL_PER_BLOCK=1`, `PILLAR_PER_BLOCK=6`; `walkCost` dy asymmetry `:589-609`; AIR directional transit
`:664-677`. Heuristic `SimpleRegionHeuristic.java:46-53` (Euclidean × 16, symmetric — leave admissible).

## Code map (region tier)

- `worldmodel/hpa/FragmentBuilder.java` — passable flood + occupiability + face footprints (the model to change).
- `worldmodel/hpa/FragmentLeafComputer.java` — passable/standable masks from NavBlock.
- `worldmodel/hpa/RegionFragments.java` — fragment record + footprint bbox (`NO_FACE`).
- `pathfinding/regionpathfinder/RegionPathfinder.java` — edge build (`:413-435`), `footprintsOverlap`,
  `walkCost`/`uniformTransitCost`, `footprintCenterWorld`, `relaxFrag :484-507`, `SimpleRegionHeuristic`.
- `worldmodel/hpa/LeafCostComputer.java` — face→center mini-pathfinds + rate constants.
- `worldmodel/hpa/PyramidMerger.java` — coarse roll-up (synthetic full-face for stacked air `:224-231`).
- `pathfinding/PathPlan.java` — `windowTarget()` (farthest usable portal), `snapInFootprint`,
  `TargetKind :250-253`, `onBotMoved` (commit-on-approach window slide), `refreshWindow`→`replanBlock :778`,
  `confineBound=null :834` + unused `corridorBound :1121`.

## Sliding-window mechanics (confirmed correct)

Window DOES slide + look ~N+2 ahead: `onBotMoved` commits-on-approach (within `REPLAN_NEAR_TARGET` of the
window target → `committedIndex = windowStart = windowTargetStep`), `refreshWindow`→`replanBlock` re-searches
the block window on plan-consume or every `REPLAN_TICKS` (40t). Not the bug.

## Gather feature state (`AllyBotEntity.java`)

Rebuilt live-scan-first: phases SCAN/MINE/COMPASS/RETURN. SCAN = nearest-first live sweep of loaded sections
(`SCAN_OFFSETS`, `ResourceScan.exactCells`); MINE drives to the ore cell + timed-mines in reach; COMPASS =
pyramid hint only when nothing loaded nearby. **Known gap:** MINE does NOT re-scan during approach (only on
queue-drain/`navGaveUp`), so it can walk past closer ore — principled fix is "always pursue nearest reachable
ore," NOT a timeout. Quota = picked-up items, accrued on standing-mine ticks.

## Pathfinding config (changed this session)

`pathing.async` now DEFAULT TRUE (searches off the tick thread → no 10k-node flood in play). Renamed keys:
`pathing.maxNodes`→`pathing.syncSearchBudgetNodes` (10k, sync-only node cap, keeps tick bounded);
`pathing.searchBudgetMs`→`pathing.asyncSearchBudgetMs` (40→250). Sync kept for benchmarks/determinism/
fallback. NOTE: an EXISTING `orebit.properties` must be deleted/regenerated (async key unchanged, old file
has async=false) — already deleted the run's copy.

## Movement plan() migration (done, verified in-game)

Traverse/Ascend/Descend/MineDown/Fall converted to the `plan()`/`PhaseRunner` reactive pattern (were legacy
`applyEdits` = instant/drop-less). All breaking now goes through timed `BotMining` (one block/tick, real
drops). Only Swim family + Climb remain legacy (they fold no breaks). Verified: bot navigated a cave network
mining through walls, no issues.

## Known in-game failures (from testing)

1. **Region down-routing** (this doc's subject) — bot drops into caverns / grinds up walls.
2. **Resource-tally phantoms** (sidestepped, not fixed): `/bot find` reports iron where none is live AND misses
   iron that's adjacent. Likely a mis-bucket in the tally WRITE path (`NavSectionBuilder` /
   `ChunkNavBuilder.attachResourceTally`); `Log2Codec`+`ResourceMerger` roll-up read clean. Gather sidesteps
   it via live scan. `/bot find <res>` now has a `[LIVE: N exact, nearest x y z]` diagnostic suffix.
3. **Stuck-bounce on a 2-up ledge** — a `plan()` move (Pillar/Ascend?) jumping but never completing; not yet
   captured in a log (may become moot once region routing keeps the bot out of the pillar-up situation).

## Images (owner-provided, in ~/Downloads)

- `Screenshot 2026-07-04 at 8.02.37 AM.png` — bot next to exposed iron saying "no iron nearby" (tally phantom).
- `Screenshot 2026-07-04 at 10.08.28 AM.png` — the cavern region BEFORE bridging (mostly-air, grid overlay).
- `Screenshot 2026-07-04 at 10.10.54 AM.png` — same region AFTER a bridge (added standable, same face bboxes).

## Build / commits

Builds on 26.2 (JDK 25, `main` era). **8 unpushed commits on `core`→`main`** (gather phase 6, find live-scan
diag, 5-move plan() conversion, gather center-approach, ResourceScan refactor, gather live-scan rebuild,
window-swap diag, async-default config). Nothing pushed pending a good in-game run. Diagnostic: `/bot debug`
on → server log gets `window-swap` lines + full `skeleton` dumps (`describeSkeleton`).

## Next step

Design + implement the **fully-connected region graph with the `avgHardness × manhattan` default dig/build
edge** (owner-ratified above). Start in `RegionPathfinder` edge construction (`:413-435`) — add a fallback
edge for every adjacent region pair that lacks a fragment-overlap portal, priced by the region's average
hardness (available via `LeafCostComputer` / the cost pyramid) × the within-region manhattan span. Then the
crossing-cell/opening representation so a level crossing can be offered (the floorless-centroid problem).
Design-review with the owner BEFORE implementing (perf-sensitive tier; see the CLAUDE.md performance protocol).
