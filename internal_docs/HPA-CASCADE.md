*Historical design doc — the A/B flags described here (HPA_FRAGMENTS / HIERARCHICAL_CASCADE) were deleted when the fragment+cascade model became unconditional (s36 cleanup, commit eed70b2). Kept for design rationale; the flag/dispatch mechanics no longer exist.*

# HPA\* Cascade — stateful nested per-level skeletons

> **Status:** ratified design, not yet implemented. **Supersedes** the *two-tier coarse branch* of
> `HPA-FRAGMENTS.md` §S5 (`RegionPathfinder.planCoarseRefinedFragments` — one coarse level + one L0 near
> refine). Builds directly on the shipped fragment model (HPA-FRAGMENTS §S1–S5) and the cap-safe level
> machinery (`chooseCapSafeLevel`/`maxChebAtLevel`/`verticalRegions`/`MAX_COARSE_LEVEL`, commit `06079d3`).
> Authored on `core`. The next implementation arc ("S6").

## 1. Why

The shipped coarse branch is a **two-tier shortcut**: pick one coarse level `L`, plan an `L`-skeleton, project
its lead cell to a level-0 sub-goal, run one L0 search to it, return that L0 near segment; the driver re-invokes
`RegionPathfinder.plan` on approach. Two costs, both real:

1. **No intermediate-level routing (the #4 gap).** Between the L0 horizon (~128 blocks) and the coarse cell
   (up to 1024+ blocks at `L`≥6) there is *no* `L-1…L1` guidance. A medium-scale obstacle there is invisible to
   both the coarse cell (sub-cell) and the L0 horizon (out of range), so the bot beelines into it and only
   reroutes on approach (**walk-then-reroute**) instead of routing around it up front.
2. **Full re-search every replan (the #3 cost).** Each replan re-runs the coarse search *and* the L0 search from
   scratch. The coarse search is cheap, but the work is redundant: the macro route barely changes between two
   replans 48 blocks apart.

The fix is the classic HPA\* hierarchical refinement, made stateful: **a stack of skeletons, one per level**,
each navigating within the window handed down from the level above, and **re-planning only the level whose
window the bot exited** (coarse levels re-plan rarely, the leaf level often). Same idea as the L0 block-window
sliding driver (`PathPlan`), recursed up the region pyramid.

## 2. The model

For a goal at chosen top level `L_top = chooseCapSafeLevel(start, goal)` (§HPA-FRAGMENTS S5 cap-safety), keep a
**stack of `L_top+1` levels**, index `L = L_top … 0`. Each level holds a skeleton plus where the bot is along it:

```
LevelPlan {
  int level
  RegionPathPlan skeleton     // region coords AT THIS LEVEL (see §9 RegionPathPlan.level)
  int committedIndex          // how far along this level's skeleton the bot has committed (hysteresis)
  int windowStart             // sliding-window start (== committedIndex in v1)
  long subGoalKey             // the (region,fragment) sub-goal this level was planned toward (from level+1)
}
```

- **Top (`L_top`)** is planned from `start@L_top` toward `goal@L_top`, the goal clamped to `maxCheb(L_top)`
  (HPA-FRAGMENTS S5). If clamped, the top skeleton ends at a waypoint and slides toward the goal as the bot
  advances (§7).
- **Each lower level `L`** is planned from `bot@L` toward the **window sub-goal** handed down from level `L+1`
  (§4): the far cell of `L+1`'s current window, projected to `L`. Its search is therefore tiny (a handful of
  cells), cap-safe by construction.
- **The bottom (`L=0`)** skeleton is the `RegionPathPlan` the existing `PathPlan` block-window driver consumes —
  unchanged in shape. The cascade is, in effect, a **smarter source** for that L0 skeleton: instead of
  re-running the whole coarse search, it re-derives only the levels that changed.

The window size (cells per level handed down) is `WINDOW_CELLS` (default 4 — §15). Refinement descends one level
per step (`L → L-1`); a configurable `LEVEL_STEP` (default 1) could skip levels to trade routing precision for
fewer re-plans (§15).

## 3. Building the stack (initial descent)

On a new goal (or a full invalidation), build top-down:

```
L_top = chooseCapSafeLevel(start, goal)
subGoalWorld = goal (clamped at top)
for L = L_top down to 0:
    sCell = region(bot, L); gCell = region(subGoalWorld, L) clamped to maxCheb(L) of sCell
    skeleton[L] = planLevelFragments(L, sCell→gCell, caps, blacklist if L==0)   // cap-safe, small for L<L_top
    if skeleton[L] == null: escalate (§6) or fail
    subGoalWorld = windowFarPortal(skeleton[L], WINDOW_CELLS)                    // §4 — hand down to L-1
return skeleton[0]    // the L0 segment for PathPlan
```

`O(L_top+1)` small searches once per goal. After that, movement re-plans only the necessary suffix of the stack
(§5), so the *amortized* per-tick cost is one small L0 search.

## 4. Sub-goal projection between levels

Level `L+1` hands level `L` a **world sub-goal** = the **portal cell** of its window's far cell
(`min(committedIndex + WINDOW_CELLS, size-1)`), falling back to that cell's center (the existing
`coarseSubGoal` logic — a real occupiable boundary, not a mid-cell point). Level `L` converts it to `L`-region
coords and **clamps to `maxCheb(L)`** of `bot@L` (so even a far hand-down stays cap-safe). Vertical: aim the
sub-goal Y at the goal's real elevation band (not the coarse cell center — the mid-cell artifact fixed in
`06079d3`); the leaf search resolves the true surface.

The hand-down is the *far* cell of the window, so each level commits to ~`WINDOW_CELLS` of macro route and lets
the finer level fill it in — this is what produces genuine intermediate routing (a medium obstacle shows up at
the level whose cells are that obstacle's scale, and is routed around *there*, before the bot walks into it).

## 5. Movement: re-plan only the exited level (the cascade)

Each tick, `onBotMoved(botFloor)`:

1. **Goal tolerance** → COMPLETE (unchanged).
2. Find the **highest level `L*` the bot has exited**: scan `L = L_top … 0`, compute `region(bot, L)`; `L*` is
   the coarsest level whose committed window no longer contains the bot's cell (it advanced past the window, or
   deviated off the skeleton). Usually `L* = -1` (still inside every window → just slide the block window) or
   `L* = 0`.
3. **Re-plan from `L*` down**: for `L = L*` down to `0`, re-plan `skeleton[L]` from `bot@L` toward the (updated)
   window sub-goal of `skeleton[L+1]`, re-project the hand-down, continue. Levels above `L*` are untouched
   (their windows still contain the bot).
4. If the L0 skeleton changed, hand it to `PathPlan` and reset its block window.

Re-plan frequency **halves per level up** (an `L`-cell is `16·2^L` blocks wide), and each re-plan is `O(WINDOW)`
cells, so the amortized cost is a geometric sum dominated by the frequent, tiny L0 re-plan. This is the #3 fix.

**Commit/hysteresis** (per level, mirroring today's L0 `committedIndex`/`committed()` logic): only advance a
level's `committedIndex` when the bot is genuinely into the next cell (not oscillating on a boundary), so a wiggle
doesn't thrash a coarse re-plan.

## 6. Blacklist escalation up the hierarchy (online repair)

When the block tier proves an L0 crossing unrealizable (`PathPlan.blockedHop` → `RegionEdgeBlacklist`):

1. Re-plan **L0** with the blacklisted edge excluded (today's behaviour, but now scoped to the current L1
   window). If it finds a route → done.
2. If L0 returns null/partial-only within the current L1 window (the whole L1 cell is unrealizable for these
   caps), **escalate**: blacklist the corresponding **L1 edge** and re-plan L1 (then cascade down). 
3. Continue up until a level re-plans successfully; if the top fails → BLOCKED/give-up (honest no-route).

The blacklist is keyed by `(region,fragment)` node pairs (already `RegionPathfinder.fragmentNodeKey`); it needs a
**per-level** keyspace (the level is part of the key, or one blacklist per LevelPlan). Escalation is the
hierarchical generalization of today's flat walk-around.

## 7. Collapse on approach + top-level sliding

`L_top` is recomputed (from `bot`, `goal`) on each top-level re-plan. As the bot nears the goal the remaining
distance shrinks, so `L_top` **decreases** and the stack **pops** its now-unnecessary top levels; on arrival
within `maxCheb(0)` the stack is just `L=0` — the plain direct branch. For an **ultra-long** goal beyond
`maxCheb(L_top)·sideOf(L_top)`, the top skeleton ends at the clamped waypoint and, each time the bot exits the
top cell, the top re-plans toward the (still-fixed) goal from the new position — the top level itself slides.
No taller pyramid is ever needed (`MAX_COARSE_LEVEL`, HPA-FRAGMENTS S5 / point #1).

## 8. Cap-safety with the cascade

Strictly stronger than the two-tier: every lower-level search spans only `~WINDOW_CELLS` toward its hand-down
(tiny); only the top search can be `maxCheb(L_top)`-sized, and that is the cap-safe bound already proven
(`(2·maxCheb+1)²·vert(L) ≤ MAX_REGION_EXPANSIONS`, unit-tested in `06079d3`). The best-so-far partial
(`REGION_PARTIAL_ON_BUDGET`) remains the backstop for a maze-detour flood at any level.

## 9. Integration (per file)

| File | Change |
|---|---|
| **`HierarchicalRegionPlan`** *(new)* | Owns the `LevelPlan[]` stack; `build(start,goal,caps)` (§3); `onBotMoved(botFloor)` → refreshed L0 skeleton or unchanged (§5); `onBlocked(edge)` escalation (§6); `l0Skeleton()`/`isComplete()`/`sameGoal()`. Holds the per-level blacklist(s). The region-tier brain. |
| **`PathPlan`** | Replace the single `RegionPathPlan skeleton` (planned once in the ctor) with a `HierarchicalRegionPlan hier`. `replanBlock`/`windowTarget`/`snapInFootprint`/block-A\* invocation stay; they read `hier.l0Skeleton()`. `onBotMoved` first calls `hier.onBotMoved` and, if the L0 skeleton changed, resets the block window. `blockedHop` feeds `hier.onBlocked`. Net: PathPlan stays the block-window driver; the L0 skeleton just has a smarter, self-refreshing source. |
| **`RegionPathPlan`** | Add an immutable `int level` field (default 0; upper skeletons carry their level). `centerOf` uses `RegionAddress.*(level, …)` instead of hardcoded 0. Constructors take `level`. (L0 plans behave exactly as today.) |
| **`RegionPathfinder`** | Expose a windowed entry: `planWithin(level, botCell, subGoalWorld, caps, blacklist)` = clamp the sub-goal to `maxCheb(level)` + `planLevelFragments(level, …)` (most of this exists inside `planCoarseRefinedFragments`; lift it out). Keep `chooseCapSafeLevel`/`maxChebAtLevel`. `planCoarseRefinedFragments` (two-tier) is **deleted** once the cascade is on. |
| **`AllyBotEntity`** | Where it builds a `PathPlan` today, unchanged (PathPlan owns the `HierarchicalRegionPlan` internally). The "new goal region → rebuild" decision uses `hier.sameGoal`. |

## 10. State & data structures (house style §14)

- `LevelPlan[]` is a small fixed array (`MAX_COARSE_LEVEL+1` = 7), reused across goals (reset, not realloc).
- Each `RegionPathPlan` is immutable (as today); a re-plan replaces one stack slot's reference.
- The per-level search reuses the existing `ThreadLocal<Nodes>` SEARCH state (one search at a time, sequential
  cascade) — zero per-node alloc preserved.
- No new hot-path allocation: `onBotMoved` does `region(bot,L)` int math per level (≤7) + at most a suffix of
  small searches. The only allocations are the replaced `RegionPathPlan` result arrays (unavoidable, immutable).

## 11. Edge cases

- **Teleport / knockback / off all skeletons** → `L* = L_top` → full rebuild (§3).
- **Goal changed** → `hier.sameGoal` false → owner rebuilds (full descent).
- **A level re-plan returns null** → escalate (§6); top null → BLOCKED.
- **Unloaded far field** → upper levels optimistic-beeline (cheap); lower levels near the bot use real data.
- **Trivial / same-region** → `L_top = 0`, single L0 plan (the direct branch; the cascade is a 1-deep stack).
- **Region partial + block partial** interplay: a region partial (best-so-far) is a valid hand-down; the block
  tier refines/blacklists as usual.

## 12. Flag / migration / deletion

- Build behind `RegionGrid.HIERARCHICAL_CASCADE` (or a `PathPlan` flag), **default OFF** → the shipped two-tier
  stays the path during in-game A/B, exactly the `HPA_FRAGMENTS` rollout pattern.
- Verify in-game (long-range reaches + medium-obstacle pre-routing + frame health + no oscillation), flip ON.
- Then **delete the two-tier** (`planCoarseRefinedFragments`, `coarseSubGoal`) and, in the same cleanup as
  HPA-FRAGMENTS S5's center-model deletion, the `HPA_FRAGMENTS` flag.

## 13. Testing (headless, `RegionGrid.headless` — no `ServerLevel`)

- **Stack consistency**: seed a multi-level terrain; build; assert each level's skeleton starts at `bot@L` and
  ends within `maxCheb(L)` of its hand-down, and that consecutive levels are spatially consistent (level `L`'s
  route lies within level `L+1`'s window cells).
- **Selective re-plan**: simulate `onBotMoved` along a route; assert that crossing an `L`-cell boundary re-plans
  exactly levels `≤ L` (instrument re-plan counts) — the amortization guarantee.
- **Intermediate routing**: a medium-scale wall invisible at `L_top` and beyond the L0 horizon; assert the
  cascade routes around it (the L-where-it's-cell-scale skeleton bends) instead of beelining into it.
- **Escalation**: blacklist an L0 edge whose whole L1 cell is sealed; assert it escalates to an L1 reroute.
- **Collapse**: walk toward the goal; assert `L_top` decreases and the stack pops to a single L0 plan near goal.
- **Regression**: the existing `PyramidMergerTest` + `RegionPathfinderFragmentTest` stay green.

## 14. Instrumentation / measurement (closes the #2 gap)

- Per-level counters: re-plans, nodes/search, µs/search (behind `Debug`/`LOG_TIMING`).
- **Region-A\* JMH bench** (`RegionPathfinderBenchmark`, reuse the `PathfinderBenchmark` harness over a
  synthetic seeded `CostPyramid` via `RegionGrid.headless`): measure per-node cost (built vs first-touch) and the
  full-cascade vs incremental-replan cost. We currently have **no region-A\* measurement** — every cap/`WINDOW`
  tuning number below is reasoned, not measured; this bench makes it data-driven.

## 15. Decisions — defaults (confirm before S6.1)

1. **`WINDOW_CELLS = 4`** per level (hand-down = 4th cell). Bigger = longer commits, fewer re-plans, looser
   intermediate routing.
2. **`LEVEL_STEP = 1`** (refine one level per cascade step; all levels present). Raising it skips levels (fewer
   re-plans, coarser intermediate routing) — a tuning knob, not v1.
3. **Controller owns the stack; `PathPlan` keeps block driving** (§9) — the lowest-risk split (PathPlan's
   block-window logic is in-game-tuned; don't move it).
4. **`RegionPathPlan` gains `level`** (vs. tracking level only in the controller) — small, and makes upper
   skeletons self-describing for debug viz.
5. **Per-level blacklist keyspace** (level in the key) — escalation needs to distinguish L0 vs L1 edges.
6. **Flag-gated, default OFF; delete two-tier after verify** (§12).

## 16. Implementation slices (next-session DAG)

```
S6.1 RegionPathPlan.level ──▶ S6.2 RegionPathfinder.planWithin ──▶ S6.3 HierarchicalRegionPlan (build + onBotMoved)
                                                                          │
                                                   S6.4 blacklist escalation ┘
S6.3 ──▶ S6.5 PathPlan integration (hier as the L0 source) ──▶ S6.6 in-game A/B (flag) ──▶ S6.7 delete two-tier
S6.8 region JMH bench + per-level instrumentation  (parallel, anytime after S6.2)
```

- **S6.1 — `RegionPathPlan.level`.** Add the field + level-aware `centerOf`; default 0 (L0 unchanged). Unit:
  centerOf at level L matches `RegionAddress.center*(L,…)`. *Pure, trivial, regression-guarded.*
- **S6.2 — `RegionPathfinder.planWithin(level, botCell, subGoalWorld, caps, blacklist)`.** Lift the clamp +
  `planLevelFragments` out of `planCoarseRefinedFragments`; returns a level-tagged `RegionPathPlan`. Unit: a
  windowed search stays within `maxCheb(level)` and heads at the sub-goal. *Mostly extraction.*
- **S6.3 — `HierarchicalRegionPlan`.** The stack + `build` (§3) + `onBotMoved` (§5, exit detection + suffix
  re-plan). Headless unit tests §13 (stack consistency, selective re-plan, intermediate routing, collapse).
  *The core; biggest piece.*
- **S6.4 — Blacklist escalation** (§6): per-level blacklist + the escalate-up loop. Unit: sealed-L1-cell test.
- **S6.5 — `PathPlan` integration.** Swap the single skeleton for `hier`; route `windowTarget`/`onBotMoved`/
  `blockedHop` through it; reset the block window on L0-skeleton change. *Touches in-game-tuned code — careful;
  flag-gated so the old path stays.*
- **S6.6 — In-game A/B** behind `HIERARCHICAL_CASCADE`: long-range reach, medium-obstacle pre-routing, frame
  health, no oscillation; compare to two-tier.
- **S6.7 — Delete the two-tier** (`planCoarseRefinedFragments`/`coarseSubGoal`) + (with HPA-FRAGMENTS S5) the
  center model + flags.
- **S6.8 — Region JMH bench + per-level instrumentation** (§14): measure, then tune `WINDOW_CELLS`/`maxCheb`/cap.

Build/test as usual (author on `core`; `git merge core` into each era; `:1.21.4:test` + `chiseledCompileCommon`
on mc-1.21/JDK21; `:26.2:compileJava` on main/JDK25). Keep it flag-gated until the in-game A/B passes.
