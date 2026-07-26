# PERF-DESIGN: region-tier dig-through connectivity + walk-across cost (CONDENSED)

> **STATUS: RATIFIED (owner-locked 2026-07-05) → IMPLEMENTED (s50–s51).** The entry-face node model,
> dig-through edges, and entry→exit walk costs are live in `RegionPathfinder`; tests + the region JMH
> bench guard them. Supersedes the "centroid" walk-cost framing; behavior was deliberately NOT preserved
> (the old behavior was known-bad).

**Problem (§1):** `/bot gather` took a 15-region winding cavern route instead of "dig down ~12 + over
~4". rtrace proof: the region directly above the buried goal expanded at g=1.0 but emitted no −Y face
candidate (the fragment touched no face toward the goal → no edge existed), and every lateral walk cost
1.0 (boundary-crossing priced, not traversal). Two root causes: **1a** no dig-out through an
untouched face (connectivity hole); **1b** walk edges priced the crossing, not the region traversal.

**The fixes:**
- **§2 entry-face node identity** — search node = (region, fragment, entry face) so edge costs stay
  fixed per node (`RegionPathfinder.searchKey` — 3-bit entryFace folded into key bits 56..58, sentinels
  `ENTRY_START`/`ENTRY_INTERIOR`; consumers stay physical, entryFace never leaks out).
- **§3 Fix 1: the dig-through edge** — every adjacent region pair gets an always-possible mine edge
  priced by material span × per-block dig cost (`RegionMineModel`; since s53 the FORWARD skeleton uses the
  fixed `FORWARD_MINE` wooden economy — see the amendment in `PERF-DESIGN-region-cost-and-fragment.md` §5).
  When `canBreak` is false every mining-based edge is DROPPED — the graph is no longer guaranteed
  connected and a no-break search can honestly FAIL (the `noBreakCap` dead-end fix).
- **§4 Fix 2: walk-across cost** — entry→exit traversal pricing (two-term walk + dig,
  `RegionPathfinder.walkCost` consumed by `relaxFrag`), killing the flat 1.0. *Since typed fragments the
  same walkCost is type-decomposed: W fragments swim-price the horizontal (0.77×walk), vertical keeps walk's
  dy shaping except fully-submerged ¬S·W (≈2.2×walk) — `DESIGN-typed-fragments.md` §3.*
- **§5 consumer handling** — the block tier realizes the dig: per-step `digThrough` flag on
  `RegionPathPlan` steps; a dig-through window target is known-buried, so the block tier mines to it.

**Code:** `pathfinding/regionpathfinder/RegionPathfinder.java`, `RegionPathPlan.java` (digThrough flag),
`RegionMineModel.java`. **Tests/bench (§7–§8):** `RegionDigThroughImprovementTest`
(expected-IMPROVEMENT tests), `RegionScenarios` (§8.2 headless fixtures incl. SEALED_DIG = the §1
repro), `RegionPathfinderBenchmark` (the region-tier JMH perf guard; mc-1.21 era only),
`RegionGrid.headless(minY, sections)` NavSection-backed enabler. Measured (s50): GOAL_IN_WINDOW
5648 µs / GOAL_NOT_IN_WINDOW 804 µs baselines.

**§ map:** §1 problem + rtrace evidence; §2 node model + entry-face augmentation; §3 Fix 1 dig-through
edge; §4 Fix 2 walk-across cost; §5 consumer handling (block tier realizes the dig); §6 deferred;
§7 validation & risk (perf-guard + improvement tests); §8 implementation order — step 1 headless
scenario fixtures (§8.2 list), step 2 the benchmark, then fixes.
