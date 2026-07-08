# HPA\* Fragments — connectivity-aware region tier (CONDENSED — implemented; full text in git history pre-s52)

**Status: SHIPPED (s31–s34), unconditional since s36** (the `HPA_FRAGMENTS` A/B flag and the center model
were deleted). Later augmented by dig-through edges + entry-face nodes
(`PERF-DESIGN-region-dig-through.md`) and flood-from-bot membership + tool-aware dig costs
(`PERF-DESIGN-region-cost-and-fragment.md`).

**Model:** each region stores its passable-air connected components ("fragments") with per-face footprints.
Region edges exist where adjacent regions' fragment footprints overlap (plus, post-s51, always-possible
dig-through edges); edge costs are DERIVED at query time from region kind + geometry, never stored.

**Where the code lives now:**
- `src/main/java/com/orebit/mod/worldmodel/hpa/` — `FragmentBuilder`, `FragmentLeafComputer`,
  `RegionFragments`, `LeafCostComputer`, `CostPyramid` (fragment storage), `CostCodec`, `PyramidMerger`
  (the S5 union-find coarse roll-up), `RegionGrid`
- Consumers: `pathfinding/regionpathfinder/RegionPathfinder.java`, `pathfinding/PathPlan.java`
- Tests: `src/test/java/com/orebit/mod/worldmodel/hpa/` — `FragmentBuilderTest`, `PyramidMergerTest`,
  `CostCodecTest`; `pathfinding/regionpathfinder/RegionPathfinderFragmentTest`

**§ map (sections cited by code Javadocs):**
- §1 why — the center model was connectivity-blind (routed through solid walls).
- §2 the model (fragments per region); §2.1 edges computed at query time, not stored; §2.2 costs DERIVED,
  not stored (the key simplification); §2.3 region kind — UNIFORM (all-air/all-solid/water) regions store
  no fragments.
- §3 connectivity = 6-connected flood fill over passable cells + occupiability + fragment cap;
  §3.1 per-level quantization `G` — the principled bound on coarse fragment count.
- §4 connectivity benchmark (decided; `ConnectivityBenchmark` fixture reuse).
- §5 storage schema (packed fragment records inside the pyramid).
- §6 the two bugs, fixed: buried-target (windowTarget returns the real portal cell, not a center
  projection) and the partial-vs-partial bounce limit cycle; §6.5 mutations & propagation (dynamic
  updates via `HpaMaintenance`).
- §7 integration, per file.
- §8 implementation slices: S1 fragment leaf computation, S2 store + codec, S3 region A\* over fragments,
  S4 `PathPlan` driver (portal-cell window targets + (region,fragment) commit), S5 coarse merge
  (`PyramidMerger`), S6 validation/A-B (the flag mechanism — since deleted).
- §9 decisions/defaults. §10 validation matrix.
