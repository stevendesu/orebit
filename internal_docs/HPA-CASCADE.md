# HPA\* Cascade — stateful nested per-level skeletons (CONDENSED — implemented; full text in git history pre-s52)

**Status: SHIPPED (s35), unconditional since s36** (the `HIERARCHICAL_CASCADE` flag and the two-tier
shortcut were deleted).

**Model:** `HierarchicalRegionPlan` keeps a STACK of per-level region skeletons (coarse top → level-0
bottom); each level's plan is confined to a window of the level above. On movement, only the level whose
window the bot exited is re-planned; the top level slides/collapses — effectively unbounded range.

**Where the code lives now:**
- `src/main/java/com/orebit/mod/pathfinding/regionpathfinder/` — `HierarchicalRegionPlan` (the stack),
  `RegionPathfinder` (`planWithin` / `planLevelFragments`), `RegionPathPlan` (carries `level`),
  `RegionEdgeBlacklist` (per-level escalation)
- `src/main/java/com/orebit/mod/pathfinding/PathPlan.java` — cascade driver + `repairBlocked`
- Test: `src/test/java/com/orebit/mod/pathfinding/regionpathfinder/HierarchicalCascadeTest.java`

**§ map (sections cited by code Javadocs):**
- §1 why — a single-level skeleton re-plans the whole route every wobble and caps range.
- §2 the model. §3 building the stack (initial coarse→fine descent).
- §4 sub-goal projection between levels (a level plans toward the parent's hand-down cell).
- §5 THE cascade rule — re-plan ONLY the exited level.
- §6 blacklist escalation up the hierarchy (online repair of unrealizable hops).
- §7 collapse on approach + top-level sliding. §8 cap-safety.
- §9 integration per file (controller owns the stack; `PathPlan` keeps block driving).
- §10 state & data structures (house style — flat arrays, no per-tick alloc).
- §11 edge cases. §12 flag/migration/deletion (done — flags gone).
- §13 headless testing via `RegionGrid.headless` (no `ServerLevel`). §14 instrumentation.
- §15 decisions/defaults — §15.1 `WINDOW_CELLS = 4` per level (hand-down = the 4th cell; bigger =
  longer commits/fewer re-plans, looser intermediate routing).
- §16 implementation slices S6.1–S6.8; §S6.2 = `RegionPathfinder.planWithin` (a plan confined to a
  parent-level window).
