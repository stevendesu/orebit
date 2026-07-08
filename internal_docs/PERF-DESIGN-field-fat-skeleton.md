# PERF DESIGN — region cost-field fat-skeleton early exit + frontier floor

> **STATUS: IMPLEMENTED (s53), owner-ratified.** The Part-C remedy of the dig-flood/field analysis memo,
> ratified by the owner including a mid-implementation amendment (the goal-anchored `cheb × MIN_CROSS`
> gradient under the floor). Measured under the paired A/B protocol (§5): field builds drop
> **−17…−32% at production box sizes** (7³/10³, both goal kinds), the EXHAUST arm is flat vs the old
> code (bookkeeping is free), `FullSearchBenchmark` and the pinned `PathfinderBenchmark SHORT` guard
> are flat, and the full-pipeline fixture results are **byte-identical** old-vs-new (same expansions,
> same waypoints, same plan hashes). Unit-pinned by `RegionFieldFatSkeletonTest`.

## §1 Problem

`RegionPathfinder.costToGoalField` — the goal-rooted reverse Dijkstra behind the block tier's
region-informed heuristic (`BlockPathfinder.h`'s `max(octile, hWeight·rc·H_STRAIGHT)` term) — ran to
**box exhaustion**: every reachable `(region, fragment, entryFace)` row in the bot↔target box (+3 pad)
was settled, at window-target-move cadence. For a bot a few regions from its target, the useful lens
(cost-to-goal ≤ the bot's own) is a goal-centred ball; the rest of the box — typically **60–75% of
settles** in principle, 20–40% measured on the flat fixtures — is work the corridor-confined block
search never benefits from.

Separately, the field had a **latent hole pathology**: `costAt` returned `UNREACHED` for out-of-box
regions and unsettled in-box slots, and `BlockPathfinder.h` then silently dropped the field term — so a
block search forced into a wide detour lost ALL field guidance exactly where it needed the pruning
signal most (the canopy/lattice flood class).

## §2 Ratified semantics

1. **Termination (fat skeleton).** With the bot's floor cell supplied (new `startFloor` parameter;
   `PathPlan.regionFieldFor` passes `botFloor`), the Dijkstra stops once
   (a) the start `(region, fragment)` settles — any entry-face row; Dijkstra settles the cheapest
   first — and (b) every region within **Chebyshev 1 of the reconstructed optimal goal→start region
   chain** (parent-link walk from the start row — the chain the block tier will actually ride) has all
   its REACHED work finished: every enqueued row in a marked region is settled. Slots never enqueued by
   then wait for nothing — they read the floor. If the start region is outside the box, no start is
   given, or the start never settles (walled off for these caps / expansion backstop), the build falls
   back to today's exhaustion — never a hole without a floor.
2. **Frontier floor.** At termination, `floorCost` = the **maximum settled cost**. Lower-bound proof:
   the search is a plain Dijkstra (`f == g`, non-negative edges), so settles occur in nondecreasing
   `g`; any slot unsettled at termination would have settled later at `g ≥ floorCost`. ∎
3. **Goal-anchored gradient (owner amendment).** Unsettled in-box slots AND out-of-box queries return
   `max(floorCost, cheb(R, goalRegion) × MIN_CROSS)`, not the bare floor. `MIN_CROSS = WALK_PER_BLOCK
   = 1.0` region units is the TRUE per-crossing minimum: **every** edge is relaxed through
   `relaxFrag`, which floors it at `Math.max(edge, WALK_PER_BLOCK)` — including the otherwise-cheaper
   faces (the free unbuilt transit, near-aligned portals, and the all-air fall chute whose raw
   field-mode rate is `FALL_PER_BLOCK_FIELD ≈ 0.54/block`). The old s30 directional ENTER/EXIT face
   costs (`LeafCostComputer`/`CostPyramid`) do not participate in this level-0 fragment-edge field, so
   no walk-speed assumption is smuggled in. Lower-bound proof: every relaxed edge moves ≤ 1 region per
   axis and costs ≥ `MIN_CROSS`; every seed sits within Chebyshev 1 of the goal region with
   `g ≥ cheb(seed) × MIN_CROSS` (the goal's own pocket seeds at 0 in the goal region; a dig-pocket
   seed's `digCost` carries ≥ one walk unit and dig pockets lie ≤ 11 < 16 blocks from the goal cell,
   i.e. Chebyshev ≤ 1 regions); induction along Dijkstra parents gives
   `g(R) ≥ cheb(R, goalRegion) × MIN_CROSS` for every reachable slot. Max of two lower bounds is a
   lower bound. ∎ The units are the field's native region units (`≈` one block-walk tick), the same
   ones `onward` carries and `BlockPathfinder` scales by `H_STRAIGHT`.
4. **Floor semantics are guidance, not exclusion.** `costAt` never returns `UNREACHED` (or infinity)
   any more. This is deliberately inadmissibility-tolerant: for an OUT-OF-BOX region the floor term is
   not a true bound on the unbounded-world metric (a path through un-modelled space could be cheaper);
   the `cheb × MIN_CROSS` term IS valid everywhere under the fragment edge model. The field was always
   "admissible-ish" guidance (the class Javadoc's caveat) — consumed via `max()` against the octile.

## §3 Mechanism (what was built)

- `RegionPathfinder.costToGoalField(grid, minY, goalFloor, startFloor, …)` — new start-aware overload;
  the old signature delegates with `null` (exhaust + floor). Callers updated: `PathPlan.regionFieldFor`
  (passes `botFloor` — the one-argument call-site change; no new PathPlan state/orchestration),
  `AllyBotEntity.traceTo`, `FullSearchScenarios.search()`.
- **Arming (cold, once per build):** resolve the start region + fragment (`startFragment`, the same
  flood-based resolver the forward A* uses; in-box check first).
- **Phase 1:** identical Dijkstra; each non-stale pop additionally sets `closed[row]` and tracks
  `maxSettled` (one compare/store — the EXHAUST arm measured flat, §5).
- **Transition (once per build):** when the start `(region, fragment)` settles, walk `parent[]` from
  the start row (the links already existed — no new per-slot data), stamp-mark the chain's Chebyshev-1
  box regions in a pooled `markScratch` int-stamp array on the ThreadLocal `Nodes` (grown
  geometrically, stamp-cleared — no per-build clearing/allocation at steady state), and count the
  reached-but-unsettled rows in marked regions into `pendingMarked` (ONE scan of the node table, not
  per pop).
- **Phase 2:** each settle in a marked region decrements `pendingMarked`; each row newly interned by an
  expansion into a marked region increments it (rows are interned exactly once — the counter counts
  rows, not heap entries, so stale duplicates are immune). `pendingMarked == 0` ⇒ break.
- `RegionCostField` gains `floorCost` (+ `setFloor`, written once at termination), the goal region
  coords, `MIN_CROSS`, the `floorAt` read on `costAt`'s three miss paths, a package-private `rawCost`
  test seam, and a `chainRegions` diagnostic (the reconstructed chain; `null` on exhaustion — also
  printed by `dump()` for `/bot trace`).
- Diagnostics: `RegionPathfinder.lastFieldSettles()` / `lastFieldEarlyExit()` (ThreadLocal, package-
  private) feed the bench sanity prints and the unit tests.
- **Prefix property** (load-bearing for tests): the early-exit run is an exact prefix of the exhaustive
  run (same seeds, same deterministic relax order), so every slot it settles carries its exhaustive
  value byte-for-byte.

Untouched: the exposed/no-dig seed fast paths, the zero-seed fallback, the dig-flood, `expandNode`/
`relaxFrag`, the forward A* (its `Nodes` gains an unused `closed[]` write in `newRow` only), and
`BlockPathfinder` (its `rc < UNREACHED` check now always passes — the field term is simply always
live, which is the point).

## §4 Verification

- **New unit pins** (`RegionFieldFatSkeletonTest`, over the field-bench world, SURFACE+BURIED ×
  box 3/5/7/10): (a) floor invariant — every slot unsettled in the fat build has exhaustive value
  ≥ `max(floor, cheb × MIN_CROSS)`, and every slot it settled is identical to the exhaustive build;
  (b) fat-skeleton coverage — every region on/±1 of the chain is slot-identical in both builds;
  (c) out-of-box + unsettled-in-box `costAt` = `max(floor, cheb × MIN_CROSS)`, never `UNREACHED`.
  All green — no late-reach hole (a marked-region slot first reached from outside the skeleton after
  termination is possible in principle; it did not occur on any fixture).
- **Existing suite**: `:1.21.11:test` fully green. The fixture guards' `costAt < UNREACHED` probes
  were rewritten to `rawCost` (they became vacuous by design — costAt never returns UNREACHED now).
- **Fixture behaviour**: full-pipeline pin (expansions, waypoint count, endpoints, FNV hash of the
  waypoint stream) run on old and new code — **byte-identical** for GOAL_IN_WINDOW (15 expansions,
  15 waypoints, hash 23b7f493cdad6668) and GOAL_NOT_IN_WINDOW (245 expansions, 39 waypoints, hash
  21881fa53b48f08e).
- `chiseledCompileCommon --continue`: all 28 versions compile.

## §5 Measured numbers (paired interleaved A/B — A₁B₁A₂B₂, `:1.21.11:jmh`, forks=0 harness)

`RegionFieldBuildBenchmark` (µs/op; A = pre-change code, B-EXH = new code start-less arm,
B-FAT = new code with the production-shaped start):

| scenario/box | A₁ | A₂ | B-EXH₁ | B-EXH₂ | B-FAT₁ | B-FAT₂ | FAT vs A |
|---|---|---|---|---|---|---|---|
| SURFACE/3³  | 88.0 | 87.0 | 87.2 | 87.9 | 90.7 | 92.4 | **+4.6%** (arming cost; sub-production anchor) |
| BURIED/3³   | 49.6 | 49.5 | 49.9 | 49.1 | 53.3 | 52.8 | **+7.1%** (") |
| SURFACE/5³  | 175.9 | 174.9 | 179.7 | 189.8 | 188.2 | 184.4 | ~flat (±27–46 error bars) |
| BURIED/5³   | 135.4 | 152.7 | 147.3 | 143.8 | 120.1 | 116.0 | **−18%** |
| SURFACE/7³  | 514.6 | 484.9 | 495.9 | 484.1 | 396.5 | 384.1 | **−22%** |
| BURIED/7³   | 458.4 | 466.4 | 462.7 | 457.3 | 312.8 | 311.4 | **−32%** |
| SURFACE/10³ | 1637 | 1577 | 1609 | 1574 | 1340 | 1319 | **−17%** |
| BURIED/10³  | 1636 | 1530 | 1611 | 1539 | 1065 | 1074 | **−32%** |

Settles saved per fixture (the early exit's direct mechanism; from the unit-test print):

| fixture | exhaustive settles | fat settles | saved |
|---|---|---|---|
| SURFACE/3³ | 111 | 111 | 0 (0%) |
| BURIED/3³ | 109 | 108 | 1 (1%) |
| SURFACE/5³ | 603 | 541 | 62 (10%) |
| BURIED/5³ | 601 | 417 | 184 (31%) |
| SURFACE/7³ | 1767 | 1334 | 433 (25%) |
| BURIED/7³ | 1765 | 1065 | 700 (40%) |
| SURFACE/10³ | 5403 | 4337 | 1066 (20%) |
| BURIED/10³ | 5401 | 3776 | 1625 (30%) |

Guards: B-EXH vs A flat everywhere (the per-settle bookkeeping is free). `FullSearchBenchmark`:
GOAL_IN_WINDOW A 948.6/940.2 vs B 933.4/951.3; GOAL_NOT_IN_WINDOW A 802.8/802.9 vs B 808.0/857.5
(±320 on the last — one noisy iteration) — flat within noise. `PathfinderBenchmark SHORT` (pinned,
no field on that path): A 13.66/13.67 vs B 15.59(±3.0)/13.72/13.35 — flat (the 15.6 was a noisy run,
confirmed by two clean B runs; the SHORT path shares no touched code).

Production notes: 7³ is the production MINIMUM box (bot & target in one region + the ±3 pad); the
3³/5³ sub-production anchors exist to expose fixed overheads, and 3³ shows the honest arming bill
(~+3.5 µs: the `startFragment` flood + the mark/pending scans) with nothing to skip — a box the live
`regionFieldFor` never builds. In-game the win is larger than the bench shows for buried/dug targets
(the bench's flat world settles fewer wasted rows than real terrain's fragment/entry-face fan-out).

## §6 Risks / accepted trade-offs

- **Weaker off-skeleton repulsion vs exhaustive.** A floored slot reads `max(floor, cheb×MIN_CROSS)` —
  a value ≤ its true cost. Off-chain detour cells that previously read a LARGE exact cost now read the
  (smaller) floor, so the block tier's repulsion from expensive detours is bounded rather than exact.
  Mitigating structure: everything on and adjacent to the optimal chain — where the corridor-confined
  block search actually probes — is exact (test b), and the floor ≈ the bot's own cost-to-goal, which
  already reads "worse than walking the chain" everywhere it applies.
- **Out-of-box floor is inadmissible in the unbounded metric** (deliberate, ratified): guidance may
  over-price a genuinely cheap out-of-box shortcut. Previously those cells had NO field term at all;
  net effect is strictly more information on the common shapes.
- **Marked-region completeness is per the ratified rule, not absolute**: a marked-region slot first
  reached from OUTSIDE the fat skeleton strictly after termination stays floored (did not occur on any
  fixture — test b is the tripwire if terrain shapes ever produce it).
- **3³ sub-production regression** (+4–7% ≈ +3.5 µs arming): accepted; production boxes start at 7³.
  No size-guard special case was added — not ratified, and the bill is one cold flood per build.
