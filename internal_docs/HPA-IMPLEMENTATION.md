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
- §3 `CostCodec` — 4-bit log-scale cost storage. *(As specified for the center model; the class survives as
  the fragment-record bitstream codec — `packRegion`/`unpackRegion` over `RegionFragments`, costs DERIVED
  never stored. Wire framing/versioning lives in `worldmodel/persistence/CostPyramidCodec`, v7.)*
- §4 `CostPyramid` — the SoA per-level store.
- §5 leaf face→center cost — DELETED with the center model (s36).
- §6 defaults for missing/unloaded nodes (optimistic: unbuilt = FREE).
- §7 `PyramidMerger` — coarse roll-up.
- §8 (+§8.1) `RegionPathfinder` + `RegionPathPlan` — the region A\* and its plan container.
- §9 `PathPlan` — sliding-window driver + the "wiggle rule" (commit hysteresis: a window region is
  committed only once the remaining block plan no longer revisits earlier skeleton regions).
- §10 `AllyBotEntity` wiring (replaced the one-tier call).
- §11 persistence — **SHIPPED** (`worldmodel/persistence/RegionPersistence`), plain files NOT the
  `SavedData` this section originally sketched. Since re-shipped as the **`.mca`-style SHARDED format**:
  `<world>/orebit/<dim>/hpa.<X>.<Z>.bin` (cost L0–5 per level-5 512-block shard) + `hpa.coarse.bin` (L6),
  `res.*` likewise — uncompressed column-run body (v3+), coarse levels persisted DIRECTLY (no `mergeUp`
  replay on load), a per-shard invalidation section (v4, #5 memory), the "v7" typed-fragment record layout
  (the on-disk `VERSION` constant was later RESET to 1 — NOTES-region-tier.md §4). Stage-2
  bounded RAM is also live: `hpa.lazyLoad` coarse-only startup + `RegionShardLoader` budgeted atomic
  page-in, plus the opt-in `RegionEvictor` (`hpa.residentLeafCap`, 0 = off). The old `hpa.bin`/`res.bin`
  blobs are ignored on disk. See `DESIGN-worldmodel-persistence.md`.
- §12 incremental maintenance (dirty regions on block change → `HpaMaintenance`).
- §13 milestone test / benchmark (`HpaMilestoneTest`).
- §14 house-style constraints — no hot-path alloc, SoA, primitive keys (still binding law).
- §15 post-build refinements: §15a corridor bound (later REMOVED in s32 — block A\* searches the full
  grid toward the window target), §15b eager on-load region build (shipped via `HpaMaintenance`).
