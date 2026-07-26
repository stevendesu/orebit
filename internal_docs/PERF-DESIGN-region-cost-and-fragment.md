# PERF-DESIGN: region-tier cost accuracy + start-fragment membership (CONDENSED)

> **STATUS: PROPOSED 2026-07-06 → IMPLEMENTED (s51/s52).** All three fixes are live; code Javadocs cite
> this doc as "PERF-DESIGN region §3/§4/§5".

**Problem (§1):** bot standing on top of a cave dug straight down (~10 digs) instead of walking ~20
blocks to a drop-in entrance. The region skeleton genuinely priced dig-down cheaper. Root-caused via
`/bot rtrace` + a per-edge cost breakdown (`traceBreakdown` in `RegionPathfinder`, TRACE-gated) — §2
evidence: lateral walks carried a phantom `6×PILLAR` climb to mid-air openings; the start fragment was
misattributed; dig cost was tool-blind and hardness-flat. Three independent causes; all three fixed.

**The fixes:**
- **§3 Fix 1 — standable-Δy anchor (biggest lever):** crossing-cell anchors snap to standable cells, so
  lateral walks stop paying phantom pillar climbs to mid-air portal centroids →
  `RegionPathfinder.footprintCenterWorld` (the standable-Δy anchor).
- **§4 Fix 2 — start-fragment membership via flood-from-bot:** the start node is the fragment that
  actually CONTAINS the bot's cell → `FragmentLeafComputer.fragmentContaining`,
  `RegionGrid.startFragmentByFlood`. Unblocks the walk-around route. *Since generalized to EVERY level as
  the containment anchor: `RegionGrid.containedFragment` walks the L0 flood membership up the pyramid via
  `InvalidationRollup.containedParentFragment`, with a faced-only centroid fallback
  (`RegionPathfinder.nearestFacedFragment`) when containment can't be proven — see HPA-CASCADE.md "The
  containment anchor".*
- **§5 Fix 3 — tool-aware region dig cost:** `RegionMineModel` — a precomputed per-block dig-cost table
  built once from the bot's REAL inventory ("honest ratios, compressed scale": WALK=1 ↔ 4.633 ticks),
  consumed by `HierarchicalRegionPlan` (built at plan construction), `PathPlan`, and `AllyBotEntity`'s
  rtrace. Input seam: `MiningModel` category index. *Amended (s53): the FORWARD skeleton searches now price
  digs with a FIXED wooden-pickaxe economy (`RegionPathfinder.FORWARD_MINE = RegionMineModel.WOODEN` —
  inventory-independent route SHAPE); the bot's real tool model feeds the REVERSE cost-to-goal field
  (`costToGoalField`) and diagnostics. The `mine` param on the forward entries is retained for API
  symmetry.*

**Code:** `pathfinding/regionpathfinder/RegionPathfinder.java` (anchor + costs + traceBreakdown),
`RegionMineModel.java`, `worldmodel/hpa/FragmentBuilder.java` / `FragmentLeafComputer.java` /
`RegionGrid.java`. Guard: `FragmentBuilderTest` (fragment-containing cases).

**§ map:** §1 problem; §2 rtrace cost-breakdown evidence; §3 Fix 1 standable-Δy anchor; §4 Fix 2
flood-from-bot start fragment; §5 Fix 3 tool-aware dig cost; §6 sequencing & validation.
