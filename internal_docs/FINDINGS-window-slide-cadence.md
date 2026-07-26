# FINDINGS: window-slide cadence — why the bot walks into the window target's box

> **Dated forensic snapshot (2026-07, pre-rolling-skeleton).** This is "the forensics" cited by
> `DESIGN-rolling-skeleton.md` (§1 quotes its §2/§4/§5). Line numbers below pin the PRE-rolling code
> state and WILL drift as increment A lands — treat them as snapshot coordinates, not live references.
> The fix this motivated is the rolling skeleton (slide-and-extend, option (a)+(c) hybridized with the
> owner's never-reset refinement).

Forensics on `C:\Users\steve\Repos\personal\orebit-mc121-wt` (read-only at the time). Evidence log:
session scratchpad `longrun-7-clean.log` (the 1500,1500 long-journey run, all-async, Debug on).

**Verdict up front:** the owner's boundary pivot **EXISTS post-s52 and fires** (12–14 times in the
95 s healthy phase). "The bot consumed the full plan" is not the base mechanism. The observed
walk-into-the-target-box waste is a different, narrower defect: **window-target PINNING at L0
segment tails** — near the end of a cascade L0 segment the window clamps to the skeleton tail, the
target becomes the L1 hand-down portal cell, and *no trigger can move that target* except the
cascade's `exhausted` test, which requires the bot to physically occupy the far region — for a
vertical crossing that means physically climbing into the target's ±1/±2 box. s52's removal of
`REPLAN_NEAR_TARGET` (Chebyshev-3 approach commit) narrowed the arrival radius to ±1/±2 and
re-exposed exactly the waste that constant's javadoc said it existed to prevent.

---

## 1. Complete trigger inventory — every path into a new block search

All block searches funnel through `PathPlan.replanBlock()` (PathPlan.java:747) — it re-runs
`WindowTargeting.target()` (WindowTargeting.java:91) fresh every call, but over the **same**
`[windowStart..windowLast]` unless a trigger moved `windowStart`. So "target re-derived" below
means the *cell* is re-picked (snap/dig/portal re-evaluated against the live grid) every time; the
*step* advances only when `windowStart` moves or the forward-slide fires (PathPlan.java:760-765:
slide while the bot is already within ±1/±2 of a non-goal target).

| # | Trigger | Site | Window target |
|---|---|---|---|
| T1 | Journey start (ctor) | PathPlan.java:454 | derived fresh, `windowStart=0` |
| T2 | **Commit-slide (the boundary pivot)** — bot's settled floor matches a forward skeleton step, `committed()` hysteresis passes, plan not partial | PathPlan.java:587-610 (replan at 608) | **re-derived**: `windowStart=curRegion`, target = step `min(curRegion+3, tail)` |
| T3 | Cascade L0 swap — `hier.onBotMoved` returned true (window `exhausted` or `deviated`) | PathPlan.java:629-648 (`stepCascade`, replan at 646) | **re-derived** from a fresh skeleton, window reset `[0..3]` |
| T4 | BLOCKED (null plan) at a settle boundary | PathPlan.java:617-619 | reused window, cell re-picked |
| T5 | Async RETRY — seam drift > Cheb 3 / window target moved / executor rejection | PathPlan.java:974-979 (`pollPending` → `replanBlock`) | reused window |
| T6 | Repair — one `repairBlocked` per `blockedGeneration` | PathPlan.java:1214-1242 (resetWindow+replan at 1240-1241); driver at BotNavigator.java:646, 898-912 | **re-derived** (repaired skeleton, window reset) |
| T7 | **Consumption refresh** — plan consumed is a first-class settle event | BotNavigator.java:575-592 (`consumed` at 580 → `pathPlan.refreshWindow()` at 588 → PathPlan.java:1266-1269 → `replanBlock`) | **reused window, same step** — target moves only if the forward-slide fires |
| T8 | Terrain recheck — every `TERRAIN_RECHECK_TICKS=40` (BotNavigator.java:73), gated on `NavGridUpdater.editEpoch` change | BotNavigator.java:581-592 (same `refreshWindow` call) | reused window, same step |
| T9 | P4 pre-plan at half-consumed | BotNavigator.java:610-615 → PathPlan.java:1024-1030 | **explicitly reused** (`windowTargetPos`) — predicts the end *cell*, never re-derives the target |
| T10 | Full rebuild — goal entered a new region / tolerance escalation | BotNavigator.java:528-551 (`replan`), 765-820 | everything fresh |

Only T2, T3, T6, T10 can advance the target step. T7 (consumption) deliberately re-aims at the
same step — so **a plan that reaches consumption without ever satisfying T2 replans toward the
identical cell, again and again, until the bot physically arrives.**

## 2. Does a mid-plan region crossing pivot the window? — YES, with four gates

The s52 note is about the *approach* machinery, not the crossing pivot. `PathPlan.onBotMoved`
(PathPlan.java:545-620) runs at **every settled waypoint boundary** (BotNavigator.java:578-579;
`settledFloor` updated per completed move at BotNavigator.java:1090-1096), not just at plan
consumption. Its commit branch (PathPlan.java:587-610) slides `windowStart` and replans **mid-plan** when:

1. `forwardIndexOf` (PathPlan.java:1381-1400) matches the bot's floor to a skeleton step in
   `[committedIndex..windowLast]` — (region, fragment) with region-only fallback. A route through a
   **non-skeleton** region matches nothing → no pivot (by design: the wiggle rule, block A* ignores regions).
2. `committed(curRegion)` (PathPlan.java:692-707) — no *remaining* waypoint revisits an earlier
   window region. Boundary-hugging paths defer the commit.
3. `!lastPlanPartial` (PathPlan.java:597) — partials never slide mid-plan (see §3).
4. The new target is `min(curRegion+3, skeleton.size()-1)` — **clamped at the skeleton tail**
   (PathPlan.java:737-739). Once `windowLast` hits the tail, T2 firing again cannot move the target.

The cascade's own committed-advance (HierarchicalRegionPlan.java:336-350, nearest-first,
fragment-gated at L0) is **bookkeeping only**: it feeds the `exhausted` test
(`committedIndex >= far`, HierarchicalRegionPlan.java:365-368) and the hand-down
(HierarchicalRegionPlan.java:679-687). An L0 crossing that doesn't exhaust the level's
`WINDOW_CELLS=4` window (HierarchicalRegionPlan.java:57) changes nothing until the next natural
re-derive. So: **crossing into a forward skeleton step pivots the block window (T2) but does NOT
re-derive the L0 segment**; the segment — and therefore the far half of the window and the
segment-end target — refreshes only on `exhausted`/`deviated` (T3).

**Where the owner's model actually breaks — segment-tail pinning.** On a long journey the L0
skeleton is a cascade segment (2–10 regions in this log), aimed at the L1 window-far hand-down.
Within ~1–2 commits, `windowLast` clamps to the segment tail and the target pins to the hand-down
cell. From then on T2/T7/T8 all re-aim at that same cell. The cascade re-derives only when the bot
*occupies* the far region — and when the crossing is vertical (log: target (151,64,-31) is the
first floor cell of region (9,8,-2), whose y-range starts at 64, bot at y=61–63 in region y=7),
occupying the region ≡ arriving at the target cell. The hand-down portal cell — a cell the ratified
design says is *not* an entrance ("no entrances and no portals", PathPlan.java:33-41) — becomes a
mandatory physical waypoint, edits and all.

## 3. The partial case (owner's callout)

`cad0280` ("honest partial handling — follow-to-terminus"): the gate is the single
`&& !lastPlanPartial` at PathPlan.java:597, comment at 598-605. While the window plan is a PARTIAL,
the commit-slide is suppressed entirely — the bot rides the partial **to its terminus**; the
consumption settle event (T7) then replans toward the *same* pinned target from the terminus.
**Whether the partial's terminus is before or after a skeleton crossing makes no difference today**
— the gate is a plan-level boolean, crossing-blind. Rationale (per the comment + the
invalidation-evidence-model memory): sliding mid-partial re-searched from intermediate cells each
boundary crossing and oscillated, so the bot never settled at the terminal dead-end where the
crossing gets blamed. Note: in longrun-7 **all 11,722 searches were FOUND, zero PARTIAL**, so this
gate contributed nothing to the observed waste in this run.

## 4. Log classification (longrun-7-clean.log)

Headline: 11,722 `HPA window`/`path TIMING` pairs, but **11,646 of them (99.4%) are a stuck loop**
— from 14:46 to 15:34 the bot sits at (169,80,-38) re-searching an identical FOUND-21wp plan toward
(192,80,-25) ~4×/s for 48 min (an execution pathology, zero BLOCKED in the whole run; out of scope
here but it is why "consumption dominates" is unmeasurable from raw counts). The **healthy travel
phase is 14:44:25–14:46:00, 76 replans**:

| When | Transition | Trigger |
|---|---|---|
| 14:44:25 | sk=7 com=0 tgt=(71,64,-48) | T1 ctor |
| 14:44:26 | com 0→2, tgt→(87,64,-32) | **T2 commit-slide** (pivot fires, target re-derived +2 regions) |
| 14:44:27/29/31 | same sk/com/tgt, bot moved | T7/T8 refresh toward same target (~2 s = 40-tick cadence) |
| 14:44:31 | sk 7→5, tgt→(103,70,-48) | T3 cascade swap |
| 14:44:39 ×2 | sk 5→3→9, tgt flips (96,69,-41)→(87,64,-32) | T3 ×2 — cascade churn |
| 14:44:48 | com 0→1, tgt→(111,64,2) | **T2** |
| 14:44:55–59 ×5 | sk 6→9→4→10→8; tgt flip-flops (104,64,-16)/(112,64,-9)/(113,64,6)/(128,56,8) | T3 ×5 in 4 s — near-equal-cost L0 segments flip-flopping |
| 14:45:08–13 | com 0→1→2→3, tgt (132,60,-1)→(129,58,-17)→(144,62,-30) | **T2 ×3** — the pivot working exactly as the owner intends |
| 14:45:29 | com 3→4, **window clamps [4..5] of 5**, tgt pins (151,64,-31) SNAPPED | **T2 into the tail clamp** |
| 14:45:30–33 | sk 6→2 (degenerate [0..1] of 1), then FOUND-7wp/4wp/1wp toward the SAME pinned cell | T3 then **T7 consumption refreshes toward an immovable target** |
| 14:45:34 | place@(150,62,-32) for Ascend; next segment (sk=7, tgt (160,64,-39)) opens with Descend + place@(151,62,-32); bot then falls (152,62,-32)→(154,56,-32) | **the owner's staircase-then-descend waste**, log lines 3462-3580 |

Tally (healthy phase): ~48 same-target refreshes (T7/T8), ~14 commit-slides (T2), ~14 cascade
swaps (T3), 0 BLOCKED/repair. **Confirmed:** near segment tails, plans are short and ARE ridden to
consumption against a pinned target. **Refuted:** as a global claim — mid-window the pivot fires at
essentially every real crossing, and mid-window plans are usually superseded by T2/T3/T8 well
before consumption. The waste is target-pinning, not a missing pivot.

## 5. History: what s52 removed and why (read before ruling)

Commit `82730a1` (+ fold `ddc1875`):
- **`REPLAN_NEAR_TARGET` (Cheb-3 approach commit) — the directly implicated removal.** Its deleted
  javadoc: *"committing on APPROACH avoids forcing the bot to pillar up to an imperfect centroid
  only to drop back down."* Replaced by the forward-slide at the block tier's ±1/±2 tolerance —
  a strictly smaller box (3×3×5 vs 7³). In the 14:45:33 event the bot at (149,63,-31) was Cheb-2
  from (151,64,-31): old rule slides (no climb); new rule needs dx≤1 → one more Ascend + place.
  Removal reason: at 5 the box (11³) could slide the window through a wall from a parallel tunnel;
  it was a second, magic spatial constant beside the search's own arrival radius.
- **`COMMIT_TICKS=3`** was only a debounce for the *inconclusive* commit (null/empty plan while
  standing in a forward region). Its pathology: it could commit on 3 ticks of mere occupancy with
  no plan to vouch the path doesn't go back. Removed because its input (empty plans) no longer
  exists. It is NOT the approach machinery and reinstating boundary replans does not reintroduce it.
- **The old per-boundary / 40-tick FULL replan** (BotNavigator.java:518-527 comment): recomputing
  the skeleton as the bot moves let near-equal-cost region routes flip-flop → mid-route
  oscillation. This is the pathology any "re-derive the cascade on every crossing" scheme risks —
  and the 14:44:55-59 five-swap cluster shows the cascade still flirts with it today. Related:
  the re-derive-progress hazard (HierarchicalRegionPlan.java:357-364) — a re-derive from unchanged
  inputs returns an identical skeleton as a NEW object, resetting the block window + phase plan
  (the s54 livelock class).

## 6. Design options (not implemented — for the owner to rule)

**(a) Replan/re-derive on first skeleton-crossing commit (the owner's stated model).**
The *block-window* half already exists (T2). The missing half is *cascade-level*: make an L0
commit-advance also refresh the L0 segment (hand-down target) instead of waiting for `exhausted`.
Risk = exactly the s52-documented flip-flop (§5, third bullet) plus the re-derive-progress hazard:
each re-derive resets `committedIndex`, the block window, and the follower's phase plan. Cost:
+1 region re-derive (µs) + 1 block search (~200-650 µs live) per L0 crossing — CPU-cheap; the risk
is behavioral, not perf. Would need the nearest-first/fragment-gate discipline extended so a
re-derive from mid-segment provably preserves progress.

**(b) Cap window plans at the FIRST skeleton crossing (plan-to-crossing).**
Contradicts the ratified wiggle rule (block A* legitimately crosses boundaries many times;
PathPlan.java:43-55): aiming AT a boundary portal makes the "imperfect centroid" cell the goal of
*every* search, which *worsens* the climb-to-portal waste unless the tolerance is widened — and it
multiplies search count ~3-4× while shrinking each search into the setup-dominated SHORT regime
(per-search NavGridView build + cuboid extraction is the hot part of small searches — perf model).
Weakest option; effectively re-derives the boundary thrash s52's predecessors fought.

**(c) Keep consumption-slide, re-derive the target at half-consumed.**
The P4 hook (BotNavigator.java:610-615) already predicts the end cell but explicitly reuses
`windowTargetPos` (PathPlan.java:1029). A principled variant that needs no timer and no occupancy:
at half-consumed, run the `committed()` scan *forward-looking* — the plan already knows which
skeleton steps its remaining suffix traverses; if the suffix provably never revisits below step j,
commit/slide to j **from the plan's own shape** and pre-plan toward the *new* window target.
Event-driven (plan shape, not ticks), satisfies no-arbitrary-timers, reuses the existing parked-seam
adoption. This fixes the mid-window staleness the owner describes ("target only right for the first
few movements") but does NOT by itself fix segment-tail pinning — the slid window still clamps at
the tail; it must be paired with a cascade rule (e.g. treat the L0 level as `exhausted` at
`far-1` — a 1-hop-remaining skeleton has no lookahead left — so the next segment is derived while
the bot is still a region away from the hand-down cell, letting the fresh segment route *around*
it instead of through it). That pairing is the smallest change that kills the observed waste.

**(d) Owner's partial refinement: partials that contain a skeleton crossing replan after crossing.**
Concretely: weaken PathPlan.java:597 from a plan-level boolean to "partial AND the bot has not yet
crossed a skeleton step the partial's *remaining* suffix stays beyond" — i.e. allow T2 when the
partial's terminus lies past the crossing just made (the re-search then heads onward, not back).
Preserves the follow-to-terminus rationale for the case it was built for (a partial that FAILS to
cross — the bot must still reach the dead-end terminus for the crossing blame to converge; the
invalidation-evidence-model memory governs and must be re-read before touching this). Zero effect
on longrun-7 (no partials), but correct-by-construction for the case the owner named.

**Also worth a separate ticket:** the ±1/±2 forward-slide radius vs Cheb-3 (§5 first bullet) — the
last 1-2 blocks of climb-to-portal waste exists independently of cadence; and the 14:46+ stuck loop
(4 replans/s at a bot that never moves, 48 min) is an execution-layer bug this log was actually
dominated by.

## File index
- `C:\Users\steve\Repos\personal\orebit-mc121-wt\src\main\java\com\orebit\mod\pathfinding\PathPlan.java` — ctor:454, onBotMoved:545, commit:587-610, partial gate:597, stepCascade:629-648, committed():692-707, windowLast clamp:737-739, replanBlock:747, forward-slide:760-765, pollPending RETRY:974-979, preplan:1024-1030, repairBlocked:1214-1242, refreshWindow:1266-1269, forwardIndexOf:1381
- `...\pathfinding\regionpathfinder\HierarchicalRegionPlan.java` — WINDOW_CELLS:57, onBotMoved commit/exhausted/deviated:289-402 (advance 336-350, exit test 365-370), re-derive hazard note:357-364, rederiveSuffix:577, handDown:679-687
- `...\com\orebit\mod\BotNavigator.java` — TERRAIN_RECHECK_TICKS:73, skeleton-commit comment:518-527, settle/consumed/refresh:574-592, plan swap:593-603, preplan call:610-615, planless poll:626-641, repairStep:646/898-912, settledFloor update:1090-1096
- `...\pathfinding\WindowTargeting.java` — target():91-208
- Log: `scratchpad\longrun-7-clean.log` — evidence cluster lines 3293-3394 (pinned target), 3487/3580 (the two places), stuck loop from ~line 4400 to 995193
- History: `git show 82730a1` (s52 phase 1), `cad0280` (follow-to-terminus)
