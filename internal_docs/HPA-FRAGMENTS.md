# HPA\* Fragments — connectivity-aware region tier (CONDENSED — implemented; full text in git history pre-s52)

**Status: SHIPPED (s31–s34), unconditional since s36** (the `HPA_FRAGMENTS` A/B flag and the center model
were deleted). Later augmented by dig-through edges + entry-face nodes
(`PERF-DESIGN-region-dig-through.md`), flood-from-bot membership + tool-aware dig costs
(`PERF-DESIGN-region-cost-and-fragment.md`), the fc collapse sentinel (codec v6), and **typed fragments**
(2026-07, `DESIGN-typed-fragments.md` — keep-ALL flooded components + per-fragment S/W type bits, codec v7).

**Model:** each region stores its passable connected components ("fragments") with per-face footprints.
Since typed fragments, **ALL** maximal passable components are kept (the old occupiability filter became a
per-fragment type computation): each fragment carries 2 independent type bits — **S** surfaceable (∃ cell
with footing = standable floor below OR water at the cell, AND air-only headroom) and **W** hasWater (≥1
water cell) — OR-merged by the coarse roll-up (`PyramidMerger`). Region edges exist where adjacent regions'
fragment footprints overlap (plus, post-s51, always-possible dig-through edges); edge costs are DERIVED at
query time from fragment types + region kind + geometry, never stored (W ⇒ swim-priced horizontal 0.77×walk;
vertical keeps walk's dy shaping except fully-submerged ¬S·W, which prices at **0.77×walk too** — the old
2.2×walk vertical-swim rate was DELETED by the 2026-08-02 unit audit, since the block tier realizes
submerged vertical steps with SprintSwim at the same cost as lateral; ¬S·¬W pure air gets the relocated
per-fragment no-place air gate — `RegionPathfinder.relaxFrag`/`walkCost`, per-type table in
`DESIGN-typed-fragments.md` §3).

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
  not stored (the key simplification); §2.3 region kind — UNIFORM (air/solid/water) regions store no
  fragments. *Amended by typed fragments (`DESIGN-typed-fragments.md` §5.5): KIND is now a DERIVED summary,
  and the uniform fast path is narrowed to TRULY-uniform floorless leaves only (provably-dry all-air →
  `KIND_AIR`, all-water → `KIND_WATER`, no-passable → `KIND_SOLID`); every other floorless leaf (mixed media,
  pillars/walls over void, the ocean-surface slab) takes the fragment path with exact per-fragment types. The
  v5 any-water rule and the interim majority vote are gone.*
- §3 connectivity = 6-connected flood fill over passable cells + fragment cap. *Amended: the occupiability
  filter is DELETED (keep-all); the cap is `RegionFragments.MAX_FRAGMENTS = 62` (was 63) — real ids 0..61;
  id 62 = `VIRTUAL_START_FRAG` (the search root's from-fragment, since 2026-07) and id 63 = the search-only
  `RegionPathfinder.VIRTUAL_GOAL_FRAG` (see HPA-CASCADE.md "The virtual goal fragment" and
  NOTES-region-tier.md §2). The persisted 6-bit COUNT field spends 63 on the
  `FRAGMENT_COUNT_COLLAPSED` sentinel (cap-collapsed, `isCollapsed()==true`) so 0 honestly means
  zero-kept/uniform-mass — codec v6 un-conflated the two.*
  §3.1 per-level quantization `G` — the principled bound on coarse fragment count.
- §4 connectivity benchmark (decided; `ConnectivityBenchmark` fixture reuse).
- §5 storage schema (packed fragment records inside the pyramid). *Wire form = the `CostPyramidCodec`
  "v7" LAYOUT (the on-disk `VERSION` constant itself was reset to 1 — NOTES-region-tier.md §4):
  per MIXED fragment, 2 type bits (S,W) between faceMask and footprints; uniform
  records stay 6 bits (kind alone is exact under the truly-uniform fast path). In RAM the types ride a
  parallel `byte[]` beside `faceMask` (`RegionFragments.types`).*
- §6 the two bugs, fixed: buried-target (windowTarget returns the real portal cell, not a center
  projection) and the partial-vs-partial bounce limit cycle; §6.5 mutations & propagation (dynamic
  updates via `HpaMaintenance`).
- §7 integration, per file.
- §8 implementation slices: S1 fragment leaf computation, S2 store + codec, S3 region A\* over fragments,
  S4 `PathPlan` driver (portal-cell window targets + (region,fragment) commit), S5 coarse merge
  (`PyramidMerger`), S6 validation/A-B (the flag mechanism — since deleted).
- §9 decisions/defaults. §10 validation matrix.
