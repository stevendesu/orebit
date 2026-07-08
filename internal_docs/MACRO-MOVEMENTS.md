# Macro-Movements & Uniform-Region Collapse (CONDENSED — implemented; full text in git history pre-s52)

**Status: RATIFIED + SHIPPED** — the cuboid macro subsystem. `MACRO-IMPLEMENTATION.md` is the
implementation-level companion; both filenames survive because code Javadocs cite them.

**What it is:** collapse long uniform runs (open air, uniform solid) into single macro jumps instead of
per-cell A\* expansion, killing the open-air pillar-cone flood (~99% of expansions off the goal column).
Plus a bounded heuristic correction: the goal-cuboid perimeter probe (`GoalForcedCost`) prices the forced
break/place work any path must pay to reach a goal buried in / walled behind a uniform cuboid.

**Where the code lives now:**
- `src/main/java/com/orebit/mod/pathfinding/blockpathfinder/cuboid/` — `Cuboid`, `CuboidExtractor`,
  `NavGridCuboidsView`, `MacroJump`, `GoalForcedCost`, `Axes`
- Macro emission inside `movements/Pillar.java`, `movements/MineDown.java`, `movements/Traverse.java`

**§ map (sections cited by code Javadocs):**
- §1 the pathology (measured pillar-cone flood).
- §2 the literature (JPS etc.) and why it doesn't port (3D, mid-search edits, nonuniform costs).
- §3 the approach; **§3a cuboids are MAXIMAL (not connected-components) and computed LAZILY per search**
  (cached in `NavGridCuboidsView`); §3b macro-operations (the collapse); §3c what does NOT collapse.
- §4 bounded heuristic correction — the goal-cuboid perimeter probe (→ `GoalForcedCost`).
- **§5 DECIDED — the ratified decision list.** The two survivors that sessions keep wrongly
  re-simplifying (see the `macro-movement-non-negotiables` memory): (1) compute the FULL cuboid, a 1-D
  walk is wrong; (2) the escape-hedge bound MUST divide by the movement's per-step cost.
- §6 relationship to the rest of the stack.
