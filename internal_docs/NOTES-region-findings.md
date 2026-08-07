# NOTES — region tier: rulings, measured numbers, and do-not-retry list

Survivor file. It distills eight investigation docs (FINDINGS/DIAGNOSIS/PERF-AUDIT/DESIGN, 2026-07…08)
that were deleted once their fixes landed. Kept here is ONLY what is expensive to re-derive: owner
**rulings**, **measured numbers**, and **refuted ideas that must not be re-proposed**. The mechanisms
themselves live in the code Javadoc that cites this file.

Deleted sources (recoverable via `git log --diff-filter=D -- internal_docs/`): `FINDINGS-region-pillar-flood.md`,
`FINDINGS-region-cost-audit.md`, `FINDINGS-window-slide-cadence.md`, `DIAGNOSIS-origin-shortpath-wander.md`,
`DIAGNOSIS-region-pillar-to-sky.md`, `DIAGNOSIS-worldgen-nondeterminism.md`, `PERF-AUDIT-region-field.md`,
`DESIGN-async-region-tier.md`.

---

## §0 The governing cost principle (owner ruling, 2026-08-02)

> **Under-estimating is admissible. Over-estimating is not.**

A region cost that is too LOW is optimism the block tier corrects on contact (the "§6 online optimism"
contract). A region cost that is too HIGH silently deletes a route that was never explored —
unrecoverable, and invisible in any log.

Standing three-way tension: **admissible** (find optimal routes) vs **conservative** (don't burn budget
on routes that may not realize) vs **performant** (bounded time, no floods/partials).

**The flooding definition** (the one generalization worth carrying): *flooding = the real path has a
required expensive move, and there is a huge cheap field.* "Expensive" is only ever relative to the
cheap field — which is why any cost that is free-in-all-directions is fatal (see §2).

---

## §1 Forward pass prices digging with a WOODEN PICKAXE (deliberate reversal)

The forward region A* prices mining off a fixed **wooden-pickaxe** economy, NOT the bot's real inventory.
The reverse (Dijkstra cost-to-goal) pass keeps the tool-honest model, since its only job is to feed the
block tier accurate guidance.

This **intentionally reverted** an earlier decision (PERF-DESIGN-region §5) that made the forward pass
tool-aware in order to ENCOURAGE digging when a pickaxe was held. Consequence accepted knowingly: a
bare-handed bot may plan a skeleton that assumes it can mine through stone. Mitigations relied on:

1. the block tier IS tool-aware and routes around such walls;
2. a wooden pickaxe is weak enough that near-free fragment walks still win;
3. intra-region fragment-to-fragment dig cost massively over-estimates today (§8.1), which already
   discourages most digging.

**Re-evaluate condition (owner):** if bare-handed bots are observed getting stuck punching through stone
often, revisit. Absent that observation, leave it.

---

## §2 Unbuilt regions are NOT free (worldgen-prior directional pricing)

`uniformTransitCost`'s `null`/unbuilt record once returned `0f` in all six directions ("assume the best" —
the "teleporter through the unknown"). That single value **defeated the cap-safe area bound**: a free field
is cheaper than every real move, so the search floods the entire unloaded void.

**Measured, on the flagship bare-handed repro** (start `(60,180,253)` jungle treetop → goal `(201,-28,90)`
trial chamber, frozen master world + persisted HPA):

- L1 expanded **20,000 nodes — the full `MAX_REGION_EXPANSIONS` backstop**; the "cap-safe ≤ 8192 by area"
  invariant was VIOLATED.
- **16,511 / 20,000 (83%) of L1 expansions were `[unbuilt]` regions.** Candidate edges: **95,141 unbuilt
  transits** vs 9,951 air-pillar vs 8,682 walk.
- L1 explored region-Y **−7 … 15** — it floods the whole vertical range, DOWN as well as up. It is **not
  up-biased**; it returns a best-f PARTIAL that merely happens to point up, and the bot executes it by
  pillaring.

**The original justification for free-unbuilt was itself unsound** (worth recording so it isn't revived):
the idea was to let a bot zip across an unknown gap between two known areas (e.g. overworld ↔ nether
portal pairs). But the moment such a search reaches the loaded region on the goal side, "enter the known
world" costs more than "keep spreading through the free void" — so the intended behavior never even
occurred.

**Rejected framings** (all wrong, do not re-propose): treat unbuilt as AIR (implies a cheap Fall, wrong for
solid), as SOLID (implies expensive MineDown, wrong for air), or as flat 6-axis walkable (ignores the real
asymmetry of up vs down).

**The shipped answer** — unbuilt regions contain *only worldgen* content, so price them off admissible
worldgen priors, directionally: **UP dear** (no player staircases exist in ungenerated terrain), **DOWN
cheap** (fall / half-cost mine-down: a descent needs 1 block broken, a lateral dig needs 2), **LATERAL a
walk**. Three options were designed; **Option B shipped** (`UNBUILT_Y_BANDED`, the default):

| Y band | UP | DOWN | LATERAL | rationale |
|---|---|---|---|---|
| `Y > 128` | double | quarter | double | above terrain ceiling — almost all regions are AIR |
| `63 < Y ≤ 128` | double | half | base | ordinary surface band |
| `Y < 63` | double | base | double | below sea level — lateral means wiggling through caves |

(Option A = the direction switch with no Y band. **Option C — running just MC's heightmap generation phase
to derive a per-column band — was designed but NOT built**; if unbuilt pricing ever needs more accuracy,
that is the next rung, and its per-query cost is the unknown to measure first.)

Still admissibly optimistic (a natural hill/cave might be cheaper), which §0 permits; chunks load on
approach and a replan corrects to real terrain.

---

## §3 Region-tier flood guard + escalation

The region tier has none of the block tier's flood shaping, and — per owner — **cannot** get it: no
"contiguous same nav-type" concept exists at region granularity (no cuboids), there is no level above the
top to supply a region-refined heuristic, and there is no known bound on the top-level search (no proof the
optimal path isn't a long roundabout). So the guard is a **cap-safe violation detector + escalation**, not
a heuristic.

**Widen the lens, don't blacklist the hop (ruling).** A flood escalation is fundamentally different from
the `onBlocked` escalation. `onBlocked` fires because a specific crossing was proven *unrealizable* → it
blacklists that hop. A flood escalation fires because the *search area* got too big — **no hop is bad** →
restart at a coarser level and **blacklist NOTHING**. The trigger cell is a perfectly valid region reached
while flooding; forbidding it would be wrong. Keep the two paths separate.

**Why the guard can never cause a bare FAIL:** the Minecraft world is **fully 6-connected** — you can
always mine or build between two adjacent regions, so restricting the search to a skeleton envelope
changes only cost, never feasibility. The ONLY way region-tier A* returns FAIL is hitting the 20k
expansion cap, i.e. flooding. Prevent the flood → prevent the FAIL.

**Level/bound reference** (`RegionAddress.LEAF_BITS=4` ⇒ L0 = 16³ blocks, `sideOf(L)=16<<L`; octree
(8 children) for L0–L4, quadtree for L5+ where `ry` pins to 0; `verticalRegions(L)=32>>L`;
`maxChebAtLevel(L)=½√(CAP_SAFE_NODES/verticalRegions(L))` with `CAP_SAFE_NODES=8192`, a chosen node budget
not a geometry — `(2·maxCheb)²·verticalRegions ≈ 8192`):

| Level | side | children | verticalRegions | maxChebAtLevel |
|---|---|---|---|---|
| L0 | 16 blk | 8 | 32 | 8 |
| L1 | 32 blk | 8 | 16 | 11 |
| L2 | 64 blk | 8 | 8 | 16 |
| L3 | 128 blk | 8 | 4 | 22 |
| L4 | 256 blk | 8 | 2 | 32 |
| L5 | 512 blk | 4 | 1 | 45 |

Note the invariant this table encodes is only "cap-safe **if the path is direct**". A real route that must
first move AWAY from the goal can exceed the cheb-bounded cube — which is exactly why the runtime guard
exists rather than trusting `chooseCapSafeLevel` alone.

**The regression vehicle is `RegionFloodGuardTest`** (the contrived built-obstacle case: a bot above a
wide, thick, prohibitively-expensive slab with the goal directly beneath it, so an honest-cost search still
wants to flood laterally around it). Keep it green.

**RULING — do not re-diagnose flat-world pillaring, and do not re-pitch a "vertical-aware heuristic."**
Heuristic blind spots are an infinite class; the ratified answer is the *structural net* (macro pillar-N +
a correctly-directed `GoalForcedCost` premium at the block tier, guarded by `MacroPillarTest`; the flood
guard + escalation at the region tier). Adding Y-awareness to a heuristic to patch one observed pillar is
the exact anti-pattern this ruling forbids.

---

## §4 Stairs-optimism: `UNSAFE_VERTICAL_PENALTY` belongs to the AIR chute only

**Owner ruling (2026-08-02):** the cliff penalty applies **only** to a provably floorless uniform
`KIND_AIR` region (`uniformTransitCost`'s chute). It used to be charged on every DRY fragment transit via
`dyCost`, over-estimating the one region class whose interior the tier cannot see (MIXED).

**Measured, from the flagship trace:**

```
cave descent   traverse[horiz=16.24 + down=19.0 (19×FALL 1.0/blk) +cliff=256.0]   = 291.2 ticks
intra-region   mine-sibling: walk=16.0 (16 blk) + dig=83.48 ((2·16+0) blk × 2.61) =  99.5 ticks
```

`(19−3)×16 = 256` — the penalty was **88% of the edge** and **8.3× the honest cost (35.2)**. Digging beat
caving by ~3×. Removing it makes the same edge 35.2 vs 99.5, i.e. the cave wins by 2.8× — **this single
change flips the preference.** It scaled exactly as the formula predicts (drop 19 → 291.2, 27 → 425.0,
31 → 496.0), and fired on **30,548 of 31,093 expansions** — roughly one per expansion.

Both vertical terms now take the best case in MIXED: **up = walk rate, not pillar rate** (an optimistic
staircase may exist, and walking up stairs is as easy as walking flat), and **no cliff term in either
direction** (a net rise is a staircase, a net drop a ramp).

**Accepted risk, ruled on explicitly:** `TYPE_S` (surfaceable) is an EXISTENCE bit — one standable cell, not
proof of a continuous ramp — and the analogous `W` existence-bit discount previously manufactured ~8×-cheap
phantom ascents (the *cliff-repro regression*). Owner accepted the asymmetry: the W regression was about
**ascents** (often genuinely unrealizable → re-attempt churn), this is a **descent**, and a drop is nearly
always realizable — worst case a `Fall` with damage cost, not a dead end. Optimism degrades to "costlier
than estimated", never to "impossible".

### Already ruled OUT here — do not "fix" these

- **`dy` treated exactly like `dx`/`dz` in the region heuristic is CORRECT.** It is a 3D world; walking up
  stairs is as easy as walking flat. Special-casing Y previously caused a large bug class.
- **A vertical break being cheaper than a horizontal one is CORRECT** — it is literally half the blocks
  (`digCost` charges `2·horizSpan + vertSpan`).
- **No arbitrary timers.** Replans are EVENT-driven. The 40-tick constant is a DEBOUNCE on world-edit
  detection, not a replan trigger.

---

## §5 Region cost-field read path (`RegionCostField.costAt`)

**What the audit convicted, and what replaced it.** `costAt` used to re-derive fragment-centroid membership
from packed footprints on **every read**: 2 `ThreadLocal.get()`s + a `CostPyramid` hash probe + up to
`6·k` footprint decodes + `k` 64-bit divisions for a k-fragment region, then a **63-slot linear fallback
scan** (`cheapestReachedSlot`) whenever nearest-centroid disagreed with the flood's fragment ids. It was
allocation-free and monomorphic but ~40–100+ branches per read against the plain octile's ~15 flops.

**Call frequency (still true):** the block tier reads the field only in `Relaxer.h()` — once for the start
node, **once per pop** (closest-approach tracking) and **once per accepted relax** ⇒ ≈ `pops + accepts` ≈
**2–3× expansions per search**; ~20–30k calls for a 10k-node sync search, several hundred thousand under
the 262k async backstop. Unambiguously hot.

**The fix (audit P1) SHIPPED, in evolved form:** membership is baked at build time as per-region **label
slabs** + a precomputed per-region cheapest reached slot, so `costAt` reads only field-owned arrays — no
ThreadLocals, no pyramid probe, no centroid math, no 63-slot scan. This also closed a latent cross-thread
read (workers were dereferencing the tick-mutated `CostPyramid` — see §9).

**`HONEYCOMB` is the microscope.** `FullSearchScenarios.HONEYCOMB` (a 4-fragment-per-region cave belt whose
sealed side pockets steal the membership probe) is the ONLY benchmark scenario whose `costAt` reads
exercise multi-fragment slot resolution — GOAL_IN_WINDOW / GOAL_NOT_IN_WINDOW route through
single-fragment regions and never touch it (counter-verified, s54). Keep it as the primary guard for any
change to `RegionCostField` slot resolution. **NOTE:** its Javadoc (and `FullSearchBenchmark`'s) still
describes the *pre-P1* centroid-loop + 63-slot-scan pathology; the scenario is still the right microscope,
but that wording needs refreshing to the slab resolver.

**Also settled here:** the merged gradient formula `costAt = octile(cell→goal) + [onward − octile(exit→goal)]`
(the origin short-path wander fix) is derived in full inside `RegionCostField.costAt`'s Javadoc — the
old form `octile(cell→exit) + onward` was the triangle-inequality UPPER bound, i.e. inadmissible, and it
anchored the gradient on the portal (the observed detour to the region centre and the spurious 2-block
pillar). Do not "simplify" it back.

---

## §6 Field-build bill and the `×63` dense layout

`RegionCostField` allocates `dimX·dimY·dimZ·MAX_FRAGMENTS` slots with `MAX_FRAGMENTS = 63`, across 5
parallel arrays (`float cost` + `int exitX/exitY/exitZ` + `float onward` = **20 B/slot**), plus an
`Arrays.fill(cost, UNREACHED)`. The box is bot↔target regions **+3 pad each side**, so:

- a 7×7×7-region box = 343·63 = **21,609 slots ≈ 432 KB allocated + 86 KB zero-then-filled per rebuild**;
- a 10×8×10 box ≈ **1.0 MB**.

The `×63` is the waste: typical regions carry **1–3** fragments (63 is the cap; over-cap regions collapse).
This invalidated an old "~6 µs per build" Javadoc claim; `RegionFieldBuildBenchmark` exists precisely to
replace it with a measured size curve (3³ → 10³ boxes over `FullSearchScenarios.fieldWorld()`).

**Measured curve (post-fix, see `docs/Optimizations/12_field_build.md` for the full writeup):**
≈ **90 µs at 3³**, **1.07–1.34 ms at 10³**, and the **production-minimum 7³ box ≈ 310–400 µs**. Before that
fix the field build was **90.8% of FullSearch wall time** (s53 JFR). Rebuild cadence is **per window-target
move**, not per tick and not per search (gated by one `fieldRoot.equals(target)` compare).

**Still-open proposal (P4): shrink the `×63` layout.** Preferred option (a): clamp field slots to
`MAX_FIELD_FRAGS = 8` (power-of-two keeps the index math one shift), folding frag ≥ 8 into the
cheapest-slot semantics — ~8× less allocation and zeroing (432 KB → ~55 KB at 7³). Option (b), CSR-style
per-row offsets, is tighter but adds an indirection per read — weigh against the branch budget. Behavior
delta to review: >8-fragment regions (rare cave honeycombs) get folded fragment identity **in the field
only** (a guidance surface, already approximate). `record`'s clamp must match.

**Also still open (P5): de-box the goal dig-flood BFS scratch.** `RegionGrid.goalDigSeeds` used
`ArrayDeque<int[]>` + `HashSet<Integer>` — one boxed `Integer` per visited cell and one `new int[4]` per
enqueued solid cell, ~2–5k transient objects per buried-goal rebuild, and it ran twice per plan (field seed
+ forward `buildDigSeeds`). A slab fix (`186669c`) already made both µs-scale; the remaining item is a
generation-stamped ThreadLocal visited array + packed int ring queue. Hygiene, not a benchmark mover.

**P3 (collapse entry-face keys in the field Dijkstra) is ranked LAST and probably not worth it:** edge costs
are entry-dependent, so collapsing changes values `record` currently min-folds — a guidance-quality change
on a path whose absolute cost is already small.

---

## §7 REFUTED / do-not-retry

- **P2 — "stop recomputing `h` per pop in `BlockPathfinder`": IMPLEMENTED, MEASURED, REVERTED (s53,
  2026-07-08). Do not re-propose the exact variant.** Exact variant = a parallel `float hOf[]` on `Nodes`
  written wherever `f` is written; pop site reads it. Byte-identity held (full suite 211/0, zero expectation
  changes, incl. `FullSearchHeadlessTest` pins). Paired interleaved A/B (A,B,A,B), full sweep, JDK 21,
  1.21.11, forks=0, µs/op avgt cnt 6:

  | Scenario | A mean | B mean | Δ |
  |---|---|---|---|
  | TOWER | 55.77 | 56.53 | +1.4% |
  | OPEN | 21.45 | 21.32 | −0.6% |
  | UPOVER_OPEN | 113.12 | 112.17 | −0.8% |
  | UPOVER_WALL | 90.36 | 89.16 | −1.3% |
  | SHORT | 14.03 | 13.75 | −2.0% |
  | MULTI | 257.91 | 255.81 | −0.8% |
  | FLOOD | 15871 | 15496 | −2.4% \* |
  | CLIFFS | 25.53 | 25.75 | +0.9% |
  | BRIDGE | 34.09 | 32.90 | −3.5% |
  | SPIRAL | 185.45 | 181.67 | −2.0% |
  | SETUP | 0.712 | 0.611 | −14.2% † |
  | SETUP_MACRO | 3.048 | 2.667 | −12.5% † |
  | FullSearch GOAL_IN_WINDOW | 997.1 | 1012.5 | +1.5% |
  | FullSearch GOAL_NOT_IN_WINDOW | 847.0 | 855.3 | +1.0% |
  | Region OPEN_CAVERN | 0.809 | 0.812 | +0.4% |
  | Region LONG_CASCADE | 6.036 | 6.018 | −0.3% |

  \* The FLOOD "win" is entirely the session's first sweep (A1=16235); A2/B1/B2 agree within 0.4%
  (15467–15525) — the honest interleaved read is FLAT. † Sub-µs ops under the known `forks=0` JIT-layout
  sensitivity; not targeted scenarios, anomaly only.

  **Verdict:** no targeted scenario clears the ≥3% bar and nothing regressed beyond noise — the change is
  simply invisible. Mechanism read: the eliminated per-pop `h` is one call against ~14 movements' worth of
  candidate generation + relax per pop, and in null-field mode `h` is a handful of FLOPs + one `sqrt`. The
  cheap `f−g` variant remains rejected *a priori* (it differs in last-ulp, so closest-approach ties — the
  PARTIAL commit target — can flip: a behavior change). Now that the field read is self-contained (§5), the
  pop-site share only shrinks. **P2 stays dead.**

- **Chebyshev goal-in-window test — owner VETOED.** Proposal was `1 ≤ cheb(windowStart, goalRegion) ≤
  WINDOW` ⇒ target the goal directly. Unsafe: a Chebyshev-1 goal *behind a barrier* needing a long detour
  would be targeted directly and flood; the `cheb ≥ 1` guard only excludes the same-region loop, not the
  barrier case. The shipped fix was `PathPlan.WINDOW` 3 → 4 instead (a 4-region window fully contains the
  4-region L-shaped skeleton a 3-axis-diagonal goal produces in a 6-connected region graph), which keeps
  the index test respecting the real skeleton. `WINDOW=4` was explicitly labelled a **stopgap** — revisit
  once the movement executor is reliable (parked idea: snap the final window target to the cell nearest the
  goal rather than mid-air/ground).

- **The jungle-canopy route is an UNRELIABLE regression oracle.** It stalls at spawn on an unrelated
  follower bug, and stale build artifacts made one A/B look like a regression that wasn't. Use the headless
  region/field unit suite as the oracle, and force-clean `versions/1.21.11/build/{classes,chiseledSrc}`
  before any A/B on a live route.

- **"Re-derive the cascade on every crossing" flip-flops.** Recomputing the skeleton as the bot moves lets
  near-equal-cost region routes ping-pong (observed: five skeleton swaps in 4 seconds). This is why the
  shipped answer is the **rolling skeleton** (slide-and-EXTEND from the existing tail, never reset — a
  suffix search from a fixed start node cannot change the already-planned prefix). See
  `DESIGN-rolling-skeleton.md`. Do not re-propose plain re-derive-on-crossing.

---

## §8 Open, still-unfixed region-tier defects

### 8.1 `mineSpans` over-estimates AND destroys the axis (convicted, unfixed)

```java
final int floor = RegionAddress.sideOf(level) / 2;   // 16 at L1, 8 at L0
if (hs + vs < floor) { hs = floor; vs = 0; }         // rounds UP and converts vertical -> horizontal
```

Two faults: (1) the floor over-estimates — half a region for fragments that may be 2 blocks apart, which
violates §0 ("always a bug" per owner); (2) `vs = 0` destroys the axis — a purely VERTICAL separation is
re-charged as horizontal, and `digCost` charges `2h + v`, so the cheap axis becomes the expensive one (a
4-block drop between stacked fragments, honestly `4 + 4m ≈ 22`, is charged `8 + 16m ≈ 82`). Visible in
traces as distinct fragment pairs with byte-identical costs.

Why it is hard: full fragment geometry is not stored — only the **bounding boxes of the 6 face
intersections** plus `passFrac` and `avgSolidHardness`; `fragmentCentroidWorld` falls back to the REGION
CENTRE for a fragment touching no face, which is the degenerate case the floor was guarding.

Unratified direction: two fragments in one region are distinct passable components, so they are separated
by ≥1 solid cell ⇒ an admissible LOWER bound of ~2 blocks, not half a region; use the **minimum separation
between the two fragments' face-footprint bboxes** (closer to truth AND it preserves which axis the
separation is on); keep a floor only for the genuinely-degenerate no-face fragment, as a lower bound.

**Caveat before fixing:** §1 currently *leans on* this over-estimate as the thing that "discourages most
digging already". Fixing 8.1 without re-checking bare-handed dig behavior may unmask §1's accepted risk.

### 8.2 Skeleton portal cells are mostly not standable (convicted, root cause unconfirmed)

Of 15 L2 skeleton steps in the flagship trace, only **two** portals tagged `[stand]`; the rest were
`[air-no-floor]` / `[buried]`. Fragments are passable components with real footing somewhere, so their
boundary crossings should yield standable cells far more often than 2-in-15. This is the upstream cause of
`WindowTargeting`'s fallback cascade — an unusable portal forces `snapInFootprint`, and a window where
nothing snaps falls back to the raw region **CENTER** (confirmed instance: target `(72,152,200)` is exactly
`RegionAddress.center*(0,4,13,12)`, which sent a bot standing at y=144 climbing to y=152 to chase a target
the next skeleton immediately re-aimed 24 blocks down).

**Where to look first:** `portalCell(i)` is `footprintCenterWorld(...)` — the centroid of the entrance-face
footprint **bbox**. A bbox centroid is not a member of the set it bounds: for an L-shaped or annular face
intersection it can land in rock or mid-air. Strong root-cause candidate; check it before anything else.

### 8.3 Smaller open items

- **First plan after spawn runs on an UNBUILT nav grid** → both tiers read optimistic-AIR and the block
  search pillars through phantom air. Isolated headlessly by the `-StartDelay` seam. Whether it bites real
  gameplay (spawn-then-immediately-goto) was never answered; if it does, gate the first plan on nav-built.
- **Partial endpoints are chosen by straight-line octile only**, so a deep descent always scores as
  "progress" even as its g-cost climbs (measured 695 → 1170 across successive replans).
- **"Prefer a known-good route over a partial" does not exist in code.** It was built and reverted
  byte-identical (2026-07-25). Both plan-install sites overwrite unconditionally, and FOLLOW-TO-TERMINUS
  makes partials *stickier* than full plans. Not a bug report — a note so it isn't rediscovered as one.

---

## §9 Async region tier (design deleted; verdicts kept)

Only `BlockPathfinder.findPath` runs on the planner pool today. All region-tier planning — cascade build,
`WindowTargeting`, and the cost-field build — runs on the **server tick thread**. A design to move it
(`DESIGN-async-region-tier.md`) was written, never ratified, never implemented; its Phase 0 (make
`RegionCostField` self-contained) landed anyway as §5's slab bake. What is worth not re-deriving:

**Remaining tick-thread planning bill per replan:** ≈ **one field build (0.1–1.4 ms, at window-target-move
cadence) + 10–70 µs of skeleton/cascade work + µs of targeting.** The field build is **90–99%** of it. At V1
(one bot) that is absorbable; at V2 (50+ bots) a replan wave of 50 × ~0.5 ms ≈ **25 ms — a full tick budget
on field builds alone**. That, and only that, is the case for the lift.
Region A* itself is **sub-10 µs per level** (RegionPathfinderBenchmark: OPEN_CAVERN 0.95, SEALED_DIG 0.29,
MULTI_FRAGMENT 0.53, LONG_CASCADE full-stack 7.4 µs) — i.e. moving the *skeleton* off-thread saves less
than the machinery costs. **Recommendation on record: defer skeleton/cascade async indefinitely; if
anything is moved, move only the field build, inside the EXISTING window-search job (no new adoption seam
— the field is consumed by the same request that builds it).**

**Thread-safety verdicts (audited; these are the expensive part to re-derive):**

| Structure | Verdict |
|---|---|
| `NavStore` maps + `NavSection`s | **Worker-safe as-is** for work done *inside a pool job* (`NavGridView.background` + the `activeSince` epoch stamp + `NavReclaim` deferred recycling). NOT safe for a hypothetical separate region thread — that would need its own stamp slot. |
| `CostPyramid` | **NOT worker-safe — plain arrays, no fences anywhere.** Mutated on the tick thread by lazy `ensureLeaf`/`rebuildLeaf` interning and by `HpaMaintenance`. A reader seeing a new `mapMask` with a stale `mapRow` during `growMap` throws AIOOBE. **Still true today.** Proposed fix: wrap each `Level`'s `(mapKey,mapRow,mapMask)` in one immutable holder swapped through a `volatile`; row arrays are append-only, publish `count` with a release write. |
| `RegionFragments` records | **NOT worker-safe — refilled IN PLACE** by `rebuildLeaf`/`PyramidMerger`. A concurrent reader can see a torn `fragmentCount`/footprints. **Still true today.** Proposed fix: **swap-not-refill** — build a fresh record and release-publish it into `frags[row]`; nothing is pooled, so old instances just die by GC (bounded staleness, which the optimistic-default semantics already tolerate). Cold path. |
| `RegionGrid` | Safe once the two above land, but worker-side region work must use a **read-only probe** (`rowIfPresent` + `isBuilt`; unbuilt ⇒ optimistic default) — never `ensureLeaf`, which mutates. Near-invisible in production because `EAGER_BUILD=true` keeps loaded chunks' leaves built. |
| `RegionEdgeBlacklist`, `HierarchicalRegionPlan` | Tick-confined per-bot mutable state. If ever moved: **snapshot in, whole-object adopt out** — never share the live object with a worker while `repairBlocked` mutates it. |
| ThreadLocal scratch (`RegionPathfinder.SEARCH`/`FIELD_SEARCH`, `RegionGrid.DIG_SCRATCH`, `FragmentBuilder.*`, `FragmentLeafComputer.*`, `PyramidMerger.*`) | Pool-friendly, per-thread, grow-once. No hazards found. Worst-case ~290 KB/thread for `DIG_SCRATCH`. |
| `NavSectionBuilder`'s public static `BlockState[]` | The one genuinely unsafe static nearby — but on **no** path this would move (fragment floods read `NavSection` navtypes, never BlockStates). Confirmed no exposure. |

**Live correctness note:** the `CostPyramid` / `RegionFragments` publication holes above are a real (if
low-probability) hole **today**, independent of any new async work — they are what Phase 0 partially
mitigated by removing `costAt`'s pyramid escape. If region reads are ever widened to workers, those two
publication fixes are **prerequisites, not nice-to-haves**.

**Out of scope by owner veto:** `BotGatherer`'s 4 raw sync `BlockPathfinder.findPath` challenge searches
stay on the tick thread; maintenance (`HpaMaintenance`, `ChunkNavLoader`, `NavReclaim`) stays tick-side.

---

## §10 Trace-query recipes (region tier)

```bash
grep -c '+cliff='            run/orebit-region-trace.txt    # cliff-penalised edges
grep -m5 '+cliff='           run/orebit-region-trace.txt    # breakdown incl. the honest cost
grep -m5 'dig='              run/orebit-region-trace.txt    # mine-sibling / dig-through pricing
grep -o '^E [0-9]* L[0-9]'   run/orebit-region-trace.txt | grep -o 'L[0-9]' | sort | uniq -c
head -40                     run/orebit-region-trace.txt    # caps + full cascade skeleton with portal tags
```

Region traces are **huge** (a flagship flood produced 125 MB / 1.33 M lines) — QUERY them, never read one
whole. Portal tags in the skeleton dump (`[stand]` / `[air-no-floor]` / `[buried]`) are the fastest read on
§8.2. Analyzer: `internal_docs/region_trace_analysis.py`. Raw geometry from a frozen master:
`internal_docs/tools/peek.py` (Anvil reader) and `slices.py` (per-X slice renderer) — both take a world
DIRECTORY and a box.
