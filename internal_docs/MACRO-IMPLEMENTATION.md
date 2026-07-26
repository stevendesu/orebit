# Macro-Movements & Cuboid Collapse — implementation design (CONDENSED — implemented; full text in git history pre-s52)

**Status: SHIPPED.** The TWO NON-NEGOTIABLES remain binding law (also in the
`macro-movement-non-negotiables` memory):
1. **Compute the FULL cuboid — a 1-D walk is WRONG** (jump length is a function of move type, cost,
   direction, and the other box dimensions; unknowable from a 1-D probe).
2. **The escape-hedge bound MUST divide by the movement's per-step cost** (otherwise cheap moves are
   under-hedged and expensive moves over-hedged).

**Where the code lives now:**
- `src/main/java/com/orebit/mod/pathfinding/blockpathfinder/cuboid/` — `Axes`, `Cuboid`,
  `CuboidExtractor`, `NavGridCuboidsView`, `MacroJump`, `GoalForcedCost`
- Macro emission: `movements/Pillar.java`, `movements/MineDown.java`, `movements/Traverse.java`;
  N-edit folds in `EditScratch.java`; macro-edge expansion in `BlockPathfinder.reconstruct`
- Test: `src/test/java/com/orebit/mod/worldmodel/pathing/MacroPillarTest.java`

**§ map (sections cited by code Javadocs):**
- §0 the two non-negotiables (above). §1 package layout & file list.
- §2 `Axes` — direction vocabulary. §3 `Cuboid` — the reusable box.
- §4 `CuboidExtractor` — THE core: directional maximal cuboid, 2 stages (grow a 2-D slab orthogonal to
  travel, then extend along the travel axis); since accelerated by the E4 runUp nibble
  (`docs/Optimizations/09_depth_nibbles.md`).
- §5 `NavGridCuboidsView` — per-search query seam (cuboid cache + PathEdits overlay + edit-shrink).
- §6 `MacroJump` — the jump-length arithmetic (the single home of both non-negotiables).
- §7 `GoalForcedCost` — the admissible goal-cuboid forced-cost heuristic (MACRO-MOVEMENTS §4); since s42
  it excludes the far face relative to the start→goal approach, +Y build face exempt.
- §8 macro-aware movements; §8.1 the shape of the three axis-aligned macros (Pillar up / MineDown down /
  Traverse lateral); §8.2 `EditScratch` folds N edits; §8.3 the `MACRO_MOVES` flag (since removed —
  unconditional); §8.4 Diagonal/Ascend macros (never built).
- §9 `reconstruct` — expand a macro edge to N waypoints (the follower is unchanged). **Update
  (2026-07-23):** `reconstruct` now ALSO fills the per-step `BlockPathPlan.floorYs` carry in both
  the single-waypoint and the macro re-expansion branches (search-native floors, so the follower
  never re-derives a floor from a feet waypoint — `DESIGN-validity-envelopes.md` §6); and the
  expanded macro steps execute via the phase framework (a Traverse macro run = one phase per run
  cell with FOOTING/AIR needs under the case-A run-line validity envelope; Pillar's macro rises
  execute its JUMP→PLACE→LAND plan, no envelope yet).
- §10 verify (the milestone). §11 build order.
