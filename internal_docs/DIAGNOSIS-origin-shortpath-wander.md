# Diagnosis — short-path "wander to the region centre" (origin REPRO 1 + sand-dune REPRO 2)

Session of 2026-07-09/10. All work is **headless on the mc-1.21 era, node `1.21.11`, JDK 21**, via the
`HeadlessAutotest` full-game harness (`scripts/run-autotest*.ps1`) + the block-A* per-search trace dump
(`-Trace`). Nothing is committed or pushed. The changes live in the `orebit-mc121-wt` worktree only and must
still be moved to `core` and propagated (see "Propagation" below).

## TL;DR

There are **two independent bugs**, both making a short path detour "to the region centre" before doubling back:

1. **Field portal-anchor** (REPRO 1, flat world, goal near a region edge) — a **block-heuristic** bug.
2. **Window index-vs-Chebyshev** (REPRO 2, sand dune, a 3-axis-diagonal goal) — a **window-target** bug.

Plus a **third, separate** robustness gap surfaced by the harness: the **first plan after spawn runs on an
UNBUILT nav grid** → both tiers read optimistic-AIR → the block search floods pillaring through phantom air.
This is a timing issue, not a heuristic issue; the harness now has a `startDelayTicks` seam to isolate it.

## Bug 1 — field portal-anchor (REPRO 1)

Flat world, bot `(-2,-60,9)` → goal `(-2,-60,-1)`. Expected: straight 10-block walk. Observed: detour to
`(-9,-60,-1)` (the region centre) then back to the goal.

**Mechanism (verified via live trace + code).** `RegionCostField.costAt` returned
`octile(cell → exitOpening) + onward`, where `exitOpening` is the region's goalward inter-region crossing =
the **shared-face centre** (region-centre on the transverse axis). That is the *via-the-portal* path length,
which **overestimates** the straight cost, so the block heuristic's `max(octile, field)` picked the inflated
value and its gradient pointed at the portal. Greedy-weighted A* then bent the *path* to the region centre.
The trace showed `f` dropping monotonically as the search stepped toward x=-9. Goal `x=-2` sits at the **+X
edge** of region `[-16,-1]`, so the portal centre (x=-8/-9) is ~7 blocks off — hence the visible wander. Not
sign/origin-specific: any goal near a region boundary that a search crosses would do it.

**Fix (owner-ratified merged formula).** `costAt = octile(cell→goal) + [ onward − octile(exit→goal) ]`:
octile-to-goal supplies the *direction* (toward the goal, never the portal); the bracket is a per-region
**detour penalty** (≥0) that keeps cave/dead-end regions reading HIGH (flood-prevention). **Admissible** by the
triangle inequality (`octile(cell→goal) ≤ octile(cell→exit)+octile(exit→goal)` ⇒ `costAt ≤` the true via-exit
cost) — the old form was that upper bound, i.e. inadmissible. Needs the goal *cell* threaded into the field
(it only stored the goal *region*). Files: `RegionCostField.java`, `RegionPathfinder.java` (one call site).

**Verified:** REPRO 1 async trace 74→**9** expansions, dead-straight, PASS. All **30** headless region/field
tests green (fat-skeleton invariants, membership, dig-through, full pipeline).

## Bug 2 — window index-vs-Chebyshev (REPRO 2, sand dune)

Seed `2607751572071936070`, bot `(540,80,-746)` → goal `(544,82,-758)` (gentle sand dune). Expected: ~direct
diagonal. Observed: detour to `(533,80,-753)` — 7 blocks the wrong way in X — then back.

**Mechanism.** The region graph is **6-connected**, so a goal that is a 3-axis diagonal (Δx=Δy=Δz=1,
Chebyshev **1**) is a **4-region L-shaped skeleton** (up → south → east). `WINDOW=3` ⇒ `windowLast=2`, so the
goal (skeleton index 3) is **one hop past the window horizon** ⇒ `goalInWindow=false`, and the window targets
**S2's corner portal `(535,80,-753)`** — west of both start and goal. The block tier walks there faithfully
(clean, 18 nodes — the heuristic is innocent here), commits, then heads east to the goal. The built-nav
skeleton dump made this unambiguous.

**Fix SHIPPED: `WINDOW` 3 → 4 (`PathPlan.WINDOW`).** A 4-region window fully contains a 4-region L-skeleton,
so the goal falls inside the window and is targeted directly by the normal index test. Cost = a longer
worst-case block search (owner's bound: ~135 blocks = 3 regions × ~45 cornerwise waypoints), affordable now
that the region-refined heuristic + forced-cost premium + macro cuboids hold the flood pathologies a tight
window guarded. Treat as a **stopgap** until the movement executor is reliable, then revisit (parked idea:
snap the final window target to the cell nearest the goal rather than mid-air/ground).

**Rejected alternative — a Chebyshev-distance goal-in-window test** (`1 ≤ cheb(windowStart, goalRegion) ≤
WINDOW` → target the goal directly). Owner vetoed it as unsafe: a Chebyshev-1 goal **behind a barrier** that
needs a long detour would wrongly target the goal directly and flood — the `cheb ≥ 1` guard only excludes the
same-region loop, not the barrier case. `WINDOW=4` has no such risk (the index test still respects the real
skeleton). `WindowTargeting.java` is therefore **unchanged**.

**Verified (clean rebuild):** first plan targets the GOAL, `goalInWindow=true`, bot goes `540→543→544`
monotonically, no detour, all searches ≤9 nodes (no flood), PASS; full `:1.21.11:test` 233/0.

## Bug 3 — first plan on UNBUILT nav (harness-exposed, separate)

The harness issued the goto at `SERVER_STARTED` (tick 1), before the nav grid around the freshly-spawned bot
built. The skeleton dump showed **every region `navSection=UNBUILT`** (optimistic-AIR default) with portals
floating at y=64/y=80; the block search read AIR everywhere and **pillared up through phantom air to y=118**,
hitting the 10k node cap as a PARTIAL (a 6.7 MB trace). The bot walked that partial (the west wander), then by
~tick 27 the nav had built and later searches went clean. **Isolation seam added:** `startDelayTicks`
(`-StartDelay N`) defers the goto N ticks so the nav settles first — REPRO 2 with `-StartDelay 60` removed the
floods entirely and exposed Bug 2 cleanly. Whether this also bites real gameplay (spawn-then-immediately-goto)
is an open question worth a look.

## Canopy is an UNRELIABLE regression oracle — do not trust it

I initially thought the fixes regressed the canopy (`t=0 (-3,125,-28)` → goal `(201,-28,90)`), because a
built-nav run stuck at spawn `(-2,114,-28)` (`wp=0`). **But full pathfinding baseline + a clean rebuild is
stuck at the *identical* cell.** The built-nav canopy stalls at spawn on the **pre-existing s52 parkour-
undershoot follower bug** (`wp=0`, no stuck recovery — the s53 pathology), independent of these changes. The
one "baseline progressed to (69,104)" run was almost certainly **stale merged-heuristic classes** (no clean
before it) — which, if anything, suggests the merged heuristic *helped*. **Lesson: force-clean
`versions/1.21.11/build/{classes,chiseledSrc}` before A/B canopy runs (build staleness is real here), and use
a long-path scenario that does NOT hit the parkour pit to judge the heuristic on long paths.** The clean
regression oracle for Bug 1 is the 30 headless region/field tests (all green).

## Open questions / next steps

- **Long-path heuristic validation.** The merged formula is admissible and passes the headless tests, but I
  could NOT cleanly measure it on a long complex path (canopy stalls at spawn on the parkour bug). Want a
  non-parkour long-path autotest scenario to confirm it still eliminates the 60–70k floods.
- **Perf.** `costAt` now does 2 `octileToExit` calls (was 1) per node *when the field is active* — marginal,
  correctness-justified, not covered by the block-only JMH.
- **Propagation.** `RegionCostField.java`, `RegionPathfinder.java`, `WindowTargeting.java` are **common source
  → belong on `core`**, then `git merge core` into each era. `HeadlessAutotest.java` is common too. The build
  key (`fabric/build.gradle.kts`) + `scripts/*` are era/tooling; the `fabric/build.gradle.kts` autotest key is
  era-owned.
- **Bug 3 in real gameplay** — does spawn-then-goto flood there too? If so, gate the first plan on nav-built.

## Artifacts (all in the worktree, uncommitted)

Fixes: `RegionCostField.java` + `RegionPathfinder.java` (Bug 1), `WindowTargeting.java` (Bug 2).
Harness: `HeadlessAutotest.java` (+`startDelayTicks`), `fabric/build.gradle.kts` (key), `scripts/run-autotest.ps1`
(`-StartDelay`), `scripts/autotest-repro1/` + `run-autotest-repro1.ps1` (flat-world REPRO 1),
`scripts/autotest-repro2/` + `run-autotest-repro2.ps1` (sand-dune REPRO 2, seed `2607751572071936070`).
Investigative unit tests (did NOT reproduce the dynamic bug — the driver is `ServerLevel`-welded; keep or
delete): `OriginWanderReproTest.java`, `OriginBlockWanderReproTest.java`.
