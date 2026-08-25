# DESIGN — Region corner crossing (the diagonal-hop connectivity gap)

**Status: DRAFT for owner review — NOT implemented.** 2026-08-24. Mechanism convicted in-game and
pinned by `ParkourCourse.regionCornerPin()` (`regioncorner.walkin`, currently RED). Line/bit facts
verified against `orebit-mc121-wt` @ HEAD 2026-08-24 and will drift.

## §0 Problem

The block tier can walk a route the region tier calls unreachable, and the two-tier driver refuses
to move at all.

Convicted on a 1-wide diagonal stone chain (the `diag1` fixture, and reproduced by hand in-game via
the `orebit-diag1` datapack). Block-tier A\* returns a clean 11-waypoint path — six plain `Diagonal`
steps out to the takeoff, then a `DiagonalParkour`. The region trace says:

```
skeleton: NONE (no coarse route — no built ground at start, or region A* FAILed)
```

The chain steps `(143,·,143) → (144,·,144)`: **both** the x and z region boundaries at once
(`RegionAddress.LEAF_SIZE == 16`), so it moves corner-to-corner from region `(8,13,8)` to
`(9,13,9)` and enters neither orthogonal neighbour. Region adjacency is strictly 6-face, so no edge
exists for that hop; the two corner-adjacent regions are pure air and the bot cannot place, so
there is no route through them either. Region A\* FAILs, the skeleton is NONE, and the bot never
leaves its spawn block.

**It fails closed** — a total refusal, the worst failure mode — and it is invisible in open terrain,
where a face-adjacent alternative always exists. It bites only on genuinely 1-wide diagonal
structures: ridges, bridges, ledge chains, this fixture.

### §0.1 Why it looked positional

For a `(+1,+1)` chain from `(bx,bz)` the x boundary is crossed at step `(16 - bx%16) % 16` and the z
boundary at `(16 - bz%16) % 16`. **They coincide exactly when `bx % 16 == bz % 16`.**

| Tile base | x crossing | z crossing | Result |
|---|---|---|---|
| `(60,138)` — passed | step 4 | step 6 | two separate FACE crossings |
| `(138,138)` — failed | step 6 | step 6 | one CORNER crossing |

A trial-list edit moved the fixture from x=60 (`12 ≠ 10`) to x=138 (`10 == 10`) and it started
failing. That is why it was first — wrongly — filed as harness fragility. `ParkourCourse.addDiagTrial`
now nudges diagonal tiles off the degeneracy so the `diag` cards test diagonal *parkour*, and
`regionCornerPin()` pins the degeneracy on purpose.

## §1 Why the obvious fixes are closed

**Widening region adjacency to 10 or 26 directions is not affordable.** The A\* node key is exactly
64 bits with **zero** spare (`RegionPathfinder.fragmentKey` / the entry-face fold):

| Bits | Width | Field | Occupancy |
|---|---|---|---|
| 0–4 | 5 | `ry` | full (0..31 leaf Y-regions) |
| 5–26 | 22 | `rz` | full |
| 27–48 | 22 | `rx` | full |
| 49–54 | 6 | fragment id | 0..61 real, 62 **free (the only one)**, 63 `VIRTUAL_GOAL_FRAG` |
| 55–57 | **3** | `entryFace` | 0..5 faces, 6 `ENTRY_START`, 7 `ENTRY_INTERIOR` |
| 58–63 | 6 | `fromFrag` | 0..61 real, 62 `VIRTUAL_START_FRAG`, 63 never-a-from |

`entryFace` is a 3-bit field with **all eight codepoints already spent**. A seventh direction does
not fit, let alone four. The `ry` 6→5 narrowing that freed room in the 2026-07 repack was already
spent on `fromFrag`. Beyond the key, `6` is structural in `RegionFragments.footprint[MAX_FRAGMENTS*6]`,
in `faceMask`'s 6 bits inside a `byte`, in the persisted `CostCodec` record, and in the `f < 6` loops
in `RegionPathfinder` (×5), `FragmentBuilder` (×2), `InvalidationRollup` and `PyramidMerger`.

**A raw block search without a skeleton is rejected** (owner, 2026-08-24): without a skeleton there
are no sub-goals, and the whole two-tier design collapses. Unbounded for far goals.

**A blanket "retry admitting corner steps on FAIL" is rejected** (owner, 2026-08-24): a far-goal FAIL
can span thousands of region hops, and admitting corners on all of them produces impossible bee-lines
that must then be continuously invalidated — and invalidation is itself constrained by the same
6-face encoding.

## §2 The case is narrower than it first looked

`Diagonal.candidates` requires **both** corner columns' body cells to be clear:

> Requiring both corners (rather than "one open side") is the conservative choice that never squeezes
> the bot through a solid corner — matching vanilla's [collision]

and it "never prices a break or a place". A 0.6-wide box sweeping the corner overlaps both orthogonal
cells, so this is real collision, not a modelling choice. Therefore:

- **`KIND_SOLID` cannot produce the gap.** Stone in an orthogonal cell blocks the diagonal outright —
  there is no move to strand. (An earlier draft of this doc claimed a "diagonal doorway through rock";
  that is impossible. If the rock is mineable, a dig-through `Traverse` is emitted and the region
  graph is connected through it.)
- **`KIND_WATER` cannot produce it.** Water is passable *and* always swimmable, so the intermediate
  region is traversable and an ordinary two-face route exists.
- **`KIND_AIR` + a bot with no place capability is the case.** Passable (so the diagonal exists) but
  not standable, and a one-way down chute the bot cannot pillar out of.
- **`KIND_MIXED` can present the same shape** when a region's air pocket yields no fragment adjoining
  the relevant face — passable, unstandable, unusable. This is the reason the fix cannot simply
  trust `kind`.

**So the trigger is: no-place bot, corner-connected fragments, and neither orthogonal intermediate
offering an adjoining fragment.**

## §3 Mechanism — prove the crossing, then route it through a corner-cut fragment

The design ratified in discussion (owner, 2026-08-24). Nothing about the key, the face count, or the
persisted face arrays changes.

### §3.1 Preconditions (cheap, checked in this order)

1. The bot has **no place capability** (with it, the air neighbour is an ordinary pillar-out route and
   the gap does not exist).
2. The **current** region has a fragment whose face footprints touch a shared **corner column** —
   i.e. it touches both faces meeting at that corner.
3. **Neither** orthogonal neighbour offers an adjoining fragment across the relevant face.
4. The **diagonal** region has a fragment whose footprints touch the matching opposite corner.

Conditions 2 and 4 are `faceMask` + footprint bbox tests, already in RAM. Condition 3 is the existing
face-portal lookup. All four are rejections in the common case, which matters because the *detection*
cost is paid far more often than the fix.

### §3.2 The proof scan

Only on all four preconditions: scan the two corner columns (the `2 × 16 × 2` cell block spanning the
corner) for standable cells whose Y values are within the reach of a **single** block-tier diagonal
move. Then enumerate the actual diagonal movements from the source standable cell and check whether
any lands on the destination standable cell.

Connectivity is emitted **only** when a real movement is proven — never optimistically. Because it is
proven rather than assumed, it needs no speculative-invalidation machinery.

### §3.3 The encoding — two ordinary face hops, on a NEW fragment id

Route `A → D` as `A →(±X)→ B →(±Z)→ D`, where `B` is one of the orthogonal intermediates and the node
in `B` carries a *corner-cut* fragment id. Both hops are ordinary 6-face edges, so the node key, the
face tables and the persisted format are untouched.

**The corner-cut id needs its own codepoint — it cannot reuse 62** (owner, 2026-08-24). An earlier
draft of this section proposed 62 on the reasoning that the fragment-id field (bits 49–54) and the
from-fragment field (bits 58–63) are independent spaces. That reasoning is wrong for this design
specifically: expanding `B →(±Z)→ D` stamps D's `fromFrag` with **B's fragment id**, so the corner-cut
id necessarily appears in the from-field one hop later, where 62 already means
`VIRTUAL_START_FRAG`. A search walking through a corner cut would read the successor as the search
root.

**The invariant that keeps this safe: a sentinel means the SAME thing in both fields.** A fragment id
becomes a from-fragment one hop later, so per-field meanings are unmaintainable —
`isVirtualStart(int)` / `isVirtualGoal(int)` both take a bare `int` with nothing to say which field it
came from, and that is only sound because the meanings coincide.

So **drop `MAX_FRAGMENTS` from 62 to 61** and give the corner cut id 61:

| id | meaning (both fields) |
|---|---|
| `0..60` | real fragments |
| **61** | **corner-cut (virtual)** — new |
| 62 | `VIRTUAL_START_FRAG` |
| 63 | `VIRTUAL_GOAL_FRAG` |

**Cost of the cap reduction.** One fewer fragment per region before a region collapses to
"spongy cheese" (crossing priced from `passFrac` instead of real connectivity). Owner-assessed as
acceptable (2026-08-24): it is mostly an L3+ concern, it triggers rarely, and collapse does not break
navigation — it degrades the region tier to "get close, then figure it out" at the block tier. Worth
re-checking if the collapse rate is ever measured, since every reduction makes it fire slightly more
often.

**The corner-cut node must not become a general waypoint in `B`.** It exists solely to sequence one
specific `A`-fragment to one specific `D`-fragment; treating it as an ordinary node would claim
connectivity that does not exist. The key already carries what is needed: `fromFrag` records the
`A`-fragment, so a corner-cut node is distinguishable by where it came from, and its expansion can
offer exactly one exit.

## §4 Open questions for the owner

1. **Build time or search time?** Build time (a stored corner fragment) needs a persisted
   representation and a build-order guarantee that the diagonal neighbour's fragments already exist;
   it also means a `KIND_AIR` region can no longer be a *uniform* record, since uniform kinds carry no
   fragment records and stop after 6 bits. Search time (synthesised in RAM on expansion) needs no
   format change and no build ordering, but re-proves per search. **Recommend search time for the
   first cut** — it keeps the persisted format and the codec history untouched, which is the
   expensive surface.
2. **Which movements are in scope?** `Diagonal` / `DiagonalAscend` / `DiagonalDescend` /
   `DiagonalSprintSwim` are corner-adjacent: source and destination cells share the corner, and the
   §3.2 scan finds them. **`DiagonalParkour` is not** — it spans a gap, so its endpoints are 2–3 cells
   apart and the arc merely passes over the corner. Recommend scoping the first cut to the
   corner-adjacent moves and registering `DiagonalParkour` separately.
3. **Which intermediate carries the fragment**, `+X`-first or `+Z`-first? Pick deterministically so
   the graph is stable across searches; emitting both doubles the branching for no gain.
4. **Cost of the two hops.** They must sum to one diagonal step, not two region crossings, or the
   skeleton will systematically over-price corner routes and detour around them.
5. **Y-window width.** §3.2's "within reach of one diagonal move" needs a concrete bound —
   `±1` covers `Diagonal`/`DiagonalAscend`/`DiagonalDescend`; a falling diagonal reaches further.
6. **`MAX_FRAGMENTS` 62 → 61 is a persisted-format-adjacent change.** The count field is 6 bits with
   63 as `FRAGMENT_COUNT_COLLAPSED`, so the width does not move — but any region already persisted with
   exactly 62 fragments must decode to something sane (collapse it). Check `CostCodec.unpackRegion` and
   the codec history note before landing. Persistence version constants stay PINNED AT 1 per the
   pre-release ruling; append to the sig-schema history instead.
7. **Audit every `isVirtualStart` / `isVirtualGoal` call site** anyway. They take a bare `int`, and the
   safety of that rests entirely on the §3.3 same-meaning-in-both-fields invariant. Adding a third
   sentinel is the moment to confirm that invariant actually holds everywhere rather than by custom.

## §5 Verification plan

1. **`regioncorner.walkin`** (`ParkourCourse.regionCornerPin()`) — the RED pin. Today:
   `FAIL (nav gave up (no route offered))` with `maxProj = -8.49`, the bot never leaving its spawn
   block. Must go green.
2. **The `diag` family must stay green** and must stay *nudged* — `addDiagTrial`'s alignment nudge
   should remain, so those cards keep testing diagonal parkour rather than silently re-testing this.
3. **No skeleton-shape regressions**: `replan` 19/19, `swim` 20/1 (`rise` pre-existing),
   `iceparkour` 23/0, `parkour` 80/1 (the pin) — the 2026-08-24 baselines.
4. **Region-tier expansion cost must not move measurably** on the common path; the §3.1 preconditions
   are rejections, and that needs to be shown, not assumed. `PathfinderBenchmark` plus a region-trace
   expansion count on a dense-cave fixture.
5. **In-game**: the `orebit-diag1` datapack
   (`run/saves/New World (1)/datapacks/orebit-diag1/`) rebuilds the exact geometry —
   `/function orebit:diag1`, `orebit:start`, `orebit:markers`.

## §6 Related

- `internal_docs/HPA-FRAGMENTS.md` §2, §5 — the fragment model and its storage.
- `internal_docs/DESIGN-typed-fragments.md` §1–§2, §5.3–§5.5 — fragment types and record layout.
- `RegionEdgeBlacklist` — online repair of *unrealizable* hops. Deliberately **not** used here: this
  design proves the crossing before emitting it, so there is nothing to blacklist.
- The registered `capability-aware-flood-intent` item (connectivity as the actual movement
  predicates) is the general form of this problem; this is one proven, bounded instance of it.
