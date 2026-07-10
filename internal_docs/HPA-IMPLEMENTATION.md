# HPA\* region tier — implementation design (CONDENSED — historical; full text in git history pre-s52)

**Status: SHIPPED, then evolved.** This doc specified the first region-tier implementation (including the
"center model" leaf costs). The pyramid/driver skeleton it specified is live; the center model itself was
DELETED in s36 in favor of the fragment model (`HPA-FRAGMENTS.md`) + cascade (`HPA-CASCADE.md`).

**Where the code lives now:**
- `src/main/java/com/orebit/mod/worldmodel/hpa/` — `RegionAddress`, `CostCodec`, `CostPyramid`,
  `PyramidMerger`, `RegionGrid`, `HpaMaintenance`
- `src/main/java/com/orebit/mod/pathfinding/regionpathfinder/` — `RegionPathfinder`, `RegionPathPlan`,
  `RegionHeuristic`, `heuristics/SimpleRegionHeuristic`
- `src/main/java/com/orebit/mod/pathfinding/PathPlan.java` — the sliding-window driver
- Wiring: `AllyBotEntity` owns the `PathPlan`; `ChunkNavLoader` → `HpaMaintenance.onChunkNavBuilt`
- Test: `src/test/java/com/orebit/mod/worldmodel/hpa/HpaMilestoneTest.java`

**§ map (sections cited by code Javadocs):**
- §1 package layout & file list.
- §2 `RegionAddress` — addressing math (region↔world coords, per-level shifts, packed keys).
- §3 `CostCodec` — 4-bit log-scale cost storage.
- §4 `CostPyramid` — the SoA per-level store.
- §5 leaf face→center cost — DELETED with the center model (s36).
- §6 defaults for missing/unloaded nodes (optimistic: unbuilt = FREE).
- §7 `PyramidMerger` — coarse roll-up.
- §8 (+§8.1) `RegionPathfinder` + `RegionPathPlan` — the region A\* and its plan container.
- §9 `PathPlan` — sliding-window driver + the "wiggle rule" (commit hysteresis: a window region is
  committed only once the remaining block plan no longer revisits earlier skeleton regions).
- §10 `AllyBotEntity` wiring (replaced the one-tier call).
- §11 persistence — **SHIPPED** (`worldmodel/persistence/RegionPersistence`), but as per-dimension plain
  gzip blob files (`<world>/orebit/<dim>/hpa.bin` + `res.bin`), NOT the `SavedData` this section originally
  sketched. See `DESIGN-worldmodel-persistence.md`.
- §12 incremental maintenance (dirty regions on block change → `HpaMaintenance`).
- §13 milestone test / benchmark (`HpaMilestoneTest`).
- §14 house-style constraints — no hot-path alloc, SoA, primitive keys (still binding law).
- §15 post-build refinements: §15a corridor bound (later REMOVED in s32 — block A\* searches the full
  grid toward the window target), §15b eager on-load region build (shipped via `HpaMaintenance`).
