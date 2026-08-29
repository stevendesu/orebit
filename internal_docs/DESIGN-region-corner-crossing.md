# DESIGN — Region corner crossing (the diagonal-hop connectivity gap)

> **SUPERSEDED 2026-08-28 by `DESIGN-region-corner-crossing-v2.md`.** v1's R8 — emit a corner crossing
> ONLY when a block-tier movement is proven over the NavGrid — is unimplementable: the NavGrid is
> resident only near the bot, so proof-gating fails closed at distance and deadlocks. v2 emits
> optimistically from region-tier facts and relies on invalidation. Everything else in this document
> carries forward and is reproduced in v2. Kept as the record of the proof-scan design.

**Status: DRAFT for owner review — NOT implemented.** 2026-08-24; reviewed and revised 2026-08-27.
Mechanism convicted in-game and pinned by `ParkourCourse.regionCornerPin()` (`regioncorner.walkin`,
currently RED). Line/bit facts verified against the code at 2026-08-27 and will drift.

**Terminology.** A *diagonal* move here is **any block-tier movement that changes more than one axis in
a single step** — not merely the `Diagonal` movement class. Several members of that set do not carry
"Diagonal" in their name (`Ascend`, `Descend`, `WalkOff`, `Parkour`'s offset arm, `Swim`'s lateral arm);
§2.1 enumerates all of them.

**§7 is the decisions ledger — read it first.** It lists what is RATIFIED, what is OPEN (including the
one blocker), and what is REJECTED, so nothing below has to be inferred from prose.

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

| Bits | Width | Field | Occupancy | Description |
|---|---|---|---|---|
| 0–4 | 5 | `ry` | full (0..31 leaf Y-regions) | The "to" region's y value |
| 5–26 | 22 | `rz` | full | The "to" region's z value |
| 27–48 | 22 | `rx` | full | The "to" region's x value |
| 49–54 | 6 | fragment id | 0..61 real, 62 `VIRTUAL_START_FRAG`, 63 `VIRTUAL_GOAL_FRAG` | The fragment ID within the "to" region. 62 is **vacant here but not spendable** — it never occurs (you never traverse *to* the start), yet the codepoint still cannot be reused; see the sentinel note below |
| 55–57 | **3** | `entryFace` | 0..5 faces, 6 `ENTRY_START`, 7 `ENTRY_INTERIOR` | To avoid writing the full 49-bit region ID (ry,rz,rx) for the "from" region, we record only how it is related to the "to" region and infer the actual coordinates when needed |
| 58–63 | 6 | `fromFrag` | 0..61 real, 62 `VIRTUAL_START_FRAG`, 63 `VIRTUAL_GOAL_FRAG` | The fragment ID within the "from" region. 63 is **vacant here but not spendable** — it never occurs (you never come *from* the goal); same note |

**Neither 6-bit field has a spare codepoint — and why the corner cut differs from the two existing
sentinels is the part worth internalising.** Both existing sentinels are **one-sided**:

| sentinel | occurs in | never occurs in | because |
|---|---|---|---|
| 62 `VIRTUAL_START_FRAG` | `fromFrag` | fragment id (the "to" field) | you can move FROM the start; you never traverse TO it |
| 63 `VIRTUAL_GOAL_FRAG` | fragment id (the "to" field) | `fromFrag` | you can move TO the goal; V is terminal, so nothing ever expands FROM it |

Each therefore leaves a genuine vacancy in the *other* field. **That vacancy is real, not an oversight** —
62 truly never occurs as a to-id, and 63 truly never occurs as a from-id. It is still not spendable, for
two reasons:

1. **Identity must be field-independent.** A fragment id *becomes* a from-fragment one hop later —
   expanding `B →(±Z)→ D` stamps `D`'s `fromFrag` with `B`'s fragment id — and
   `isVirtualStart(int)` / `isVirtualGoal(int)` both take a bare `int` with nothing to say which field it
   came from. **A sentinel means the SAME thing in every field and every position.** That is the rule,
   and it holds today only because the two meanings coincide.
2. **The corner cut is two-sided, unlike either existing sentinel.** The node `(B, CORNER, …)` puts the
   id in the "to" field, and the very next expansion stamps that same id into `D`'s `fromFrag`. It needs
   a codepoint vacant in **both** fields, and neither existing vacancy qualifies. Spending one would mean
   a fragment whose id changes with position — "63 in the from field, 62 in the to field" — which is
   precisely the unmaintainable case rule 1 forbids.

So §3.3 *takes* a codepoint by shrinking `MAX_FRAGMENTS` rather than finding an unclaimed one. (An
earlier revision of this table listed 62 as an available codepoint with no qualification; that predates
`VIRTUAL_START_FRAG` claiming it, and is the staleness `RegionFragments.MAX_FRAGMENTS`'s javadoc calls
out. It has now been misread twice — once in the original draft and once during the 2026-08-27 review —
so state vacancy and spendability separately, and never abbreviate either to the word "free".)

**Notice why `entryFace` exists:** it's a way to compress the "from" region's 49-bit ID into a 3-bit
field, which works only because its value is relative to the previously-encoded "to" region.

**That inference must be UNIQUE**, and it is load-bearing in two places: the node-identity split that
lets a blamed approach be retried through a different predecessor (the two-hallways / A==G fixes,
NOTES-region-tier.md §1.1), and the approach-into-V blame key, which is rebuilt from the skeleton alone
and so has no live row to read a from-region off. An encoding that lets two different predecessors mint
the same key does not fail loudly — it silently blames the wrong approach. This is the reason the
direct-diagonal-edge encoding is rejected; the argument is developed where that alternative is
considered.

`entryFace` is a 3-bit field with **all eight codepoints already spent**. A seventh direction does
not fit, let alone four or more additional directions. The `ry` 6→5 narrowing that freed room in the
2026-07 repack was already spent on `fromFrag`. Beyond the key, `6` is structural in
`RegionFragments.footprint[MAX_FRAGMENTS*6]`, in `faceMask`'s 6 bits inside a `byte`, in the persisted
`CostCodec` record, and in the `f < 6` loops in `RegionPathfinder` (×5), `FragmentBuilder` (×2),
`InvalidationRollup` and `PyramidMerger`.

**A raw block search without a skeleton is rejected** (owner, 2026-08-24): without a skeleton there
are no sub-goals, and the whole two-tier design collapses. Unbounded for far goals.

**A blanket "retry admitting corner steps on FAIL" is rejected** (owner, 2026-08-24): a far-goal FAIL
can span thousands of region hops, and admitting corners on all of them produces impossible bee-lines
that must then be continuously invalidated.

## §2 Which region KINDS can produce the gap

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
  region is traversable and an ordinary two-face route exists. Mechanically: a water fragment carries
  `TYPE_W`, and `expandNode`'s no-place air gate (`airGated && typeB == 0`) only skips **typeless**
  fragments — a `W` fragment passes for every bot toward any face it touches.
- **`KIND_AIR` + a bot with no place capability is the case.** Passable (so the diagonal exists) but
  not standable, and a one-way down chute the bot cannot pillar out of.
- **`KIND_MIXED` can present the same shape** when a region's air pocket yields no *usable* fragment
  adjoining the relevant face. Precisely: the flood always produces a fragment for the pocket, but it
  earns `TYPE_S` only if some cell in it has footing (a standable floor below, or water at the cell)
  **and** air-only headroom. A pocket with no such cell is typeless — pure air — and the no-place gate
  skips it outright on every face except −Y (falling in). This is the reason the fix cannot simply
  trust `kind`.

**So the trigger is: no-place bot, corner-connected fragments, and neither orthogonal intermediate
offering an adjoining fragment.**

For detection of "corner-connected fragments", see §3.1

### §2.1 The full set of DIAGONAL movements

**Terminology.** Throughout this document a *diagonal* move means **any block-tier movement that changes
more than one axis in a single step** — not merely the `Diagonal` movement class. That is the whole
trigger set, because the region graph is 6-face: a move whose source and destination region addresses
differ on two or more axes has no edge to ride. Several members do not carry "Diagonal" in their name,
which is exactly why they are easy to forget.

| Movement | Delta | Axes it can cross at once |
|---|---|---|
| `Diagonal` | `(±1, 0, ±1)` | **X+Z** — strictly same-Y (`out.accept(nx, y, nz, …)`) |
| `Ascend` | `(±1, +1, 0)` / `(0, +1, ±1)` over `CARDINALS` | **X+Y** or **Z+Y** |
| `Descend` | `(±1, −1, 0)` / `(0, −1, ±1)` over `CARDINALS` | **X+Y** or **Z+Y** |
| `WalkOff` | `out.accept(lx, y − 1, lz, …)` — lateral + down | **X+Y** or **Z+Y** |
| `Parkour`, rising/falling arms | lateral run + `dy` | **X+Y** or **Z+Y** |
| `Parkour`, **OFFSET arm** | knight's-move: a forward run *plus* a lateral component, same Y | **X+Z** |
| `Swim`, lateral arm | `out.accept(nx, wf − 1, nz, …)` — `wf` is the destination column's water surface, so a lateral step also moves in Y | **X+Y** or **Z+Y** |
| `DiagonalSprintSwim` | 20 offsets: 4 same-Y horizontal diagonals, 8 cardinal + ±Y edges, **8 full corners `{±1,±1,±1}`** | **X+Z**, **X+Y**, **Z+Y**, and **X+Y+Z** |
| `DiagonalParkour` (v1) | spans a gap; endpoints 2–3 cells apart, same Y | **X+Z**, but NOT corner-adjacent — see §3.2 |
| *future* `DiagonalParkour` rising/falling | gap + `dy` (`DiagonalParkour.java:17`: "**v1 is FLAT only**; rising/falling" is planned) | **X+Y+Z** |
| *future* `DiagonalAscend` / `DiagonalDescend` | `(±1, ±1, ±1)` | **X+Y+Z** |

Single-axis movements, for contrast, cannot produce the gap at all: `Traverse`, `Fall`, `Pillar`,
`MineDown`, `SprintSwim` (its 6 faces), `Climb` and `RideBubbleColumn` all move along one axis.

**Three-axis crossings already exist today.** `DiagonalSprintSwim`'s `MOVES` table carries 8 offsets with
`movingMask == 7` (X|Y|Z) — the √3 corners. So the vertex case is **not** hypothetical or gated on
`DiagonalAscend` landing; it is in the shipped movement set. This is why §3.3 is specified as a chain of
**one or two** corner-cut hops rather than a single one.

**But a swimmer almost certainly cannot be STRANDED by one.** `DiagonalSprintSwim`'s per-axis clearance
rule requires **every proper non-empty subset of the moving axes** to be swimmable water — the
`for (int s = 1; s < 7; s++)` subset loop. For an X+Z corner that is exactly the two orthogonal cells;
for an X+Y+Z corner it is all three single-axis and all three pair-axis intermediates. So the move is
only ever emitted when those intermediate cells ARE water — which means the intermediate regions hold
water fragments carrying `TYPE_W`, which the no-place gate always passes, and whose face footprints
necessarily overlap `A`'s at the shared boundary cell. **The ordinary two-face route exists whenever the
diagonal does.**

Stranding a swimmer would require water on both diagonal sides of the corner but *not* on the
orthogonals — which its own clearance rule forbids. (Barring a deliberately adversarial build: isolated
floating water cells separated by signs or similar non-water passables. Not worth designing for.) So
`DiagonalSprintSwim` widens the ENCODING requirement — the chain must handle three axes — without
realistically widening the trigger set.

**The realistic 3-axis case is rising/falling `DiagonalParkour`, and it is nearer than it looks.** The
class exists today and its own javadoc states the limit and the plan: *"v1 is FLAT only; rising/falling"*
(`DiagonalParkour.java:17`, echoed at :286 — "no rising/falling detection"). The moment that arm lands, a
dry-land 3-axis crossing exists with none of the water clearance rule's protection. `DiagonalAscend` /
`DiagonalDescend` would be a second such case. Both are reasons to specify the chain for two hops now
rather than retrofit it.

**The vertical case fails closed exactly as the horizontal one does.** Worked example, a free-standing
1-wide staircase: floor `(15,15,z)` → floor `(16,16,z)` with the Y region boundary between them. `A` is
region `(0,0,·)`, `D` is `(1,1,·)`; the vertical intermediate `(0,1,·)` holds only the open air above the
source step (floorless ⇒ `KIND_AIR` or a typeless `MIXED` fragment ⇒ gated for a no-place bot), and the
lateral intermediate `(1,0,·)` holds the air under the step (same). Both gated, no edge, FAIL.

**How often the alignment lands.** §0.1 gives the horizontal arithmetic: for a `(+1,+1)` chain the two
crossings coincide iff `bx % 16 == bz % 16` — 1 chain in 16, and then only at that one step. The vertical
family needs the step-up (or step-down) to land on `(y − minY) % 16 == 0` *while* crossing a lateral
boundary — roughly 1 in 16 of the ~1 in 16 lateral crossings. On top of that the terrain must be a
genuinely 1-wide structure (ridge, bridge, ledge chain, free-standing staircase) with no usable
intermediate, and the bot must be no-place (§3.1). Vanishingly rare; a hard refusal when it lands.

**Nothing at the region tier assumes a diagonal keeps its Y**, because the tier has no notion of a
diagonal at all — so adding `DiagonalAscend`/`DiagonalDescend` needs no region-tier change. The only Y
assumption to avoid is the one this design would otherwise introduce; see §3.2.

## §3 Mechanism — prove the crossing, then route it through a corner-cut fragment

The design ratified in discussion (owner, 2026-08-24), revised 2026-08-27. Nothing about the node key's
layout, the face count, or the persisted face ARRAYS changes. One persisted-format-adjacent thing does
change: `MAX_FRAGMENTS` drops 62 → 61 to take a fragment-id codepoint (§3.3, §4).

### §3.1 Preconditions (cheap, checked in this order)

1. The bot has **no place capability** (with it, the air neighbour is an ordinary pillar-out route and
   the gap does not exist). This *should* also cover a bot with `placement.canPlace = true` but
   `placement.consumesBlocks` set and an empty inventory — see the caveat below, because the region tier
   cannot currently see that state.
2. The **current** region has a fragment whose face footprints touch a shared **corner** —
   i.e. both face footprints' bounding boxes touch the same edge. For the 3-axis case, all
   three face footprints touch the vertex.
3. **No** intermediate region offers an adjoining fragment across the relevant face. ("Intermediate"
   is the two orthogonal neighbours for a 2-axis corner; for a vertex it is the three face-adjacent and
   three edge-adjacent regions the chain could route through.)
4. The **diagonal** region has a fragment whose footprints touch the matching opposite corner.
5. The crossing is **not already invalidated** — probe both blame structures for this
   `(A, fragA) → (D, fragD)` pair before paying for the §3.2 scan: `RegionEdgeBlacklist` for this
   navigation, and `RegionCrossingMemory` for the seeded long-lived facts, sig-filtered by the bot's
   effective caps. See §3.6 for why the probe is keyed diagonally and where the key is built.

Conditions 2 and 4 are `faceMask` + footprint bbox tests, already in RAM. Condition 3 is the existing
face-portal lookup. Condition 5 is a linear scan over a set that is normally empty. All five are
rejections in the common case, which matters because the *detection* cost is paid far more often than
the fix.

**CAVEAT on precondition 1 — the region tier cannot currently see an empty inventory.**
`BotCaps.java:361` computes `effectivePlace = canPlace && !(consumesBlocks && placeableBlocks <= 0)`,
but that value feeds the realizability SIGNATURE. `AllyBotEntity` passes the raw `caps.canPlace()` into
the region call, and inventory shapes only the pillar COST (`RegionPlaceModel.from(inv)`). So making
precondition 1 true as written requires threading the inventory-effective flag into the region tier —
a small change, arguably a correctness fix in its own right, but **a change**, not something the
precondition gets for free.

**Precondition 1 is also the one that decides how often ANY of this runs, and it should drive scoping.**
`placement.canPlace` **defaults to `true`** (`docs/configuration.md:62`, `internal_docs/CONFIG.md:62`).
The gates that create the gap are `!canPlace && kind == KIND_AIR && f != 2` (uniform neighbour) and
`airGated = !canPlace && f != 2` (per-fragment MIXED) — a place-capable bot already gets a lateral edge
into pure air, priced with the pillar term. **So with the region tier as it stands, the whole mechanism
is dormant for a default-configured bot**, and fires only where the owner set `placement.canPlace =
false` — which is what `regionCornerPin()` does, and which `docs/configuration.md:298` documents as a
supported configuration. Wire up the inventory-effective flag above and it additionally fires for any
bot that runs out of blocks, which is a great deal more common.

Two things argue for building it regardless of how rare it stays:

- It fails **closed** — total refusal, the worst failure mode — so rarity does not bound the damage.
- The rarity is in the *trigger*, not the *cost*: preconditions 1–5 are rejections, so a bot that never
  meets them never pays more than the branch.

**Precondition 3 is an economy gate, not a correctness gate** (accepted limitation). When an ordinary
face route through an intermediate exists but is far more expensive than the corner cut — say it needs a
pillar — the corner is never offered and the search takes the dear route. That is an under-emission, not
a wrong answer, and it keeps the *detection* cost off the common path, which is what matters. Note the
near-prohibitive-pillar case is exactly what an inventory-aware precondition 1 would surface.

### §3.2 The proof scan

Only on all preconditions, and the scan is **DIRECTIONAL — one-sided**. We are proving `A → D`, so:

1. **Scan the SOURCE region `A` only**, for standable cells that could originate a crossing.
2. **Enumerate the real movement candidates** from each such cell.
3. **Probe each candidate's landing cell** — a single `standable()` descriptor read at a cell the
   movement itself names — and keep the candidate if the landing is standable *and* lies in `D`.

There is no second scan of `D`, and no pairing step. Pairing two independently-scanned cell sets is
worst-case O(N²) in candidate pairs; enumerating sources and probing landings is O(N × movements), and
the movement is the thing that knows where it can land. (An earlier revision of this section described a
two-sided scan looking for cells "within the reach of a single diagonal move" and then pairing them.
That framing is what the offset arithmetic below belongs to; it survives only as a cheap pre-filter.)

**The scan volume.** The shared corner between `A` and `D` is a line — the EDGE — running along the one
axis the two regions agree on, of length `N` = the region side at this level (L0 → 16, L1 → 32, …). For
a **VERTEX** (3 footprint bboxes meet) the shared feature is a point, and the scan collapses to its
immediate neighbourhood.

Source cells are **not** limited to `A`'s boundary column: a gap-2 `DiagonalParkour` takes off up to
3 cells back from where it lands, so a source can sit up to `R` cells deep into `A`, where `R` is the
maximum lateral reach over the registered movement set. So the source volume is `R x R x N` for an
edge (the two cross-section axes by the edge length), and `R x R x R` for a vertex. **`R` must be
derived from the movement set**, for exactly the reason the offset pre-filter must be — see below.

**The offset arithmetic, as a pre-filter.** Once a standable source cell is in hand, the candidate
landings sit at a small set of offsets, and rejecting on those before running the full movement
predicates is worth doing. For example: if the edge runs in the +/- X direction (all blocks along this
edge share Y and Z values) then we are looking for two blocks whose X values are either:
 * equal (`x1 === x2`): this would permit an Ascend or Descend between them
 * different by 2 or 3 (`x1 === x2 +/- 2`, `+/- 3`): this would permit a DiagonalParkour between them
   (gap-1 reaches 2, gap-2 reaches 3 — the full gap range is in scope, not capped at gap-1)

NOTE: Currently the block tier does **NOT** have a "DiagonalAscend" or "DiagonalDescend" move. Such
a move would occupy the `x1 === x2 +/- 1` case. Depending on how we implement this scan, we may already
wish to check for this case (to cover DiagonalAscend and DiagonalDescend when they're added) or we
may be able to not worry about these if we run the actual movement checks - and thus adding a new
Diagonal movement would automatically expand the scan conditions.

Our goal is to enumerate the actual diagonal movements from the source standable cell and check whether
any lands on the destination standable cell.

**If an offset pre-filter is used, DERIVE it from the registered movement set.** The offsets above
(`0`, `±2`, and `±1` when DiagonalAscend lands) are a cheap way to reject most cell pairs before the
movement enumeration runs, and that is worth having. But a **hardcoded** offset table is the one place
where registering a new movement would silently fail to widen the scan — the region tier would keep
rejecting pairs the block tier can now traverse, and the failure looks exactly like the original bug.
The movement enumeration is the authority; any pre-filter must be computed from it, not written beside
it. (This is also why the scan carries no Y-window bound: an earlier revision proposed `±1` "the reach of
a single diagonal move", which hard-codes today's movement set into the region tier.)

**Scope note — `DiagonalParkour`.** The `±2` case above deliberately brings flat `DiagonalParkour` INTO
the proof, even though its endpoints are 2–3 cells apart rather than corner-adjacent, so the arc merely
passes over the corner rather than touching it. That is a wider scope than the corner-adjacent moves
need, and it is the right call if the scan enumerates real movements — the movement's own predicates
decide, and an arc that clears is as real a crossing as a step that clears. It does mean the scan's cell
pairs are not limited to the corner columns; the `2 x 2 x N` edge cuboid already accommodates this.
A gap-2 `DiagonalParkour` reaches `±3`, so the offset set is `{0, ±2, ±3}` today.

Depending on how we do the scan, connectivity may be emitted **only** when a real movement is proven.

**Cost of the scan.** With `R = 3` (today's maximum lateral reach), an edge scan is `3 x 3 x N` source
cells = 144 at L0 and 4,608 at L5, each surviving cell paying its movement enumeration plus a landing
probe. Larger than the `2 x 2 x N` figure an earlier revision quoted, because that figure assumed the
two-sided pairing scan and only the four corner columns. Still accepted as-is (owner ruling 2026-08-27):
it runs only after the §3.1 preconditions fail to reject, and those reject on the overwhelming majority
of expansions. Optimize later if it ever shows up in a region-trace expansion count.

**It is paid TWICE per crossing in TIME** (not twice in space — the scan itself is one-sided): once at
`A`'s expansion, to decide whether to emit the cut at all, and again at the corner node's pop, to decide
which `D` it exits to — because the node key cannot carry `D` (§3.3). Either budget both, or memoize the
first result for the life of the search, keyed by the source `(region, fragment, corner direction)`.

**On "proven, therefore un-invalidatable".** If connectivity is proven rather than assumed, we need no
*speculative*-invalidation machinery — nothing is ever emitted on a guess that must later be retracted.
But proof does not make invalidation impossible, and §3.6 explains why: the proof establishes the move
exists under the movement predicates, over the nav grid, **at plan time**. It does not establish that the
executor realizes it, and it says nothing about later ticks. So a corner crossing can still go BLOCKED —
rarely — and must remain blameable.

Conversely, if we ever relax this and rely on heuristics or approximations for connectivity, invalidation
stops being a rare backstop and becomes load-bearing. Either way the machinery is described in §3.6.

### §3.3 The encoding — two (or three) ordinary face hops, on a NEW fragment id

Route `A → D` as `A →(±X)→ B [→(±Y)→ C] →(±Z)→ D`, where `B` and `C` are the intermediate regions and
the nodes in `B` and `C` carry a *corner-cut* virtual fragment id. Every hop is an ordinary 6-face edge,
so the node key, the face tables and the persisted format are untouched. §3.4 fixes which intermediate
each hop goes through — and that choice is load-bearing, not cosmetic.

**The corner-cut id needs its own codepoint — it cannot reuse 62 or 63** (owner, 2026-08-24). An earlier
draft of this section proposed 62 on the reasoning that the fragment-id field (bits 49–54) and the
from-fragment field (bits 58–63) are independent spaces. That reasoning is wrong for this design
specifically: expanding `B →(±Z)→ D` stamps D's `fromFrag` with **B's fragment id**, so the corner-cut
id necessarily appears in the from-field one hop later, where 62 already means `VIRTUAL_START_FRAG`.
A search walking through a corner cut would read the successor as the search root. §1 develops the
general form of this: both existing sentinels are one-sided, the corner cut is two-sided, so neither
existing vacancy can host it.

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

**The corner-cut node must not become a general waypoint in `B`.** It exists solely to sequence
`A`-fragments to `D`-fragments; treating it as an ordinary node would claim connectivity that does not
exist. Its expansion must therefore be special-cased — skip the generic face loop entirely and emit only
proven corner exits.

**How much the key pins, and what it does not.** The key `(B, CORNER, entryFace, fromFrag)` pins the
source **uniquely**: `A` is `B`'s neighbour across `entryFace`, and `fromFrag` names `A`'s fragment. No
ordinary expansion can ever mint the corner id, so the node is unreachable except through a proven cut.

What it does *not* pin is **which corner** of `A` is being cut toward. From `B = (2,1,1)` entered from
`A = (1,1,1)`, both `D = (2,1,2)` and `D = (2,1,0)` are diagonally-opposite `A` and face-adjacent to `B`,
and both mint the identical key. **This is not false connectivity** — a fragment is a connected component,
so both corner columns of `A`'s fragment are genuinely connected to each other, and both exits are real.
So the corner node offers **up to N proven exits, re-proved on pop** (2 for a 2-axis corner; more for a
vertex) — not "exactly one", as an earlier revision of this paragraph claimed.

This is also exactly why §3.2's proof is paid twice in time: once at `A`'s expansion to decide whether to
emit the cut, and again here to decide which `D` the corner node exits to. Memoizing the first result for
the life of the search — keyed by the source `(region, fragment, corner direction)` — collapses it back
to once.

### §3.4 Which intermediate carries the corner node

**RESOLVED: match the realized-crossing decomposition order — X, then Y, then Z.** That is exactly the
order §3.3's chain already writes (`A →(±X)→ B [→(±Y)→ C] →(±Z)→ D`), and it is load-bearing rather than
notational. Do not reorder the intermediates on physical grounds.

**Why the order is forced.** `BlockPathfinder.collectRealizedCrossings` already staircase-decomposes
every block edge that straddles region boundaries into single-axis region steps, in a fixed **X, then Y,
then Z** order (`while (fx != tx) … while (fy != ty) … while (fz != tz)`). Its javadoc calls this out for
diagonals directly: *"Diagonal edges crossing two boundaries at a corner get the same decomposition —
slight over-marking only ever defers blame to a later genuinely-unrealized hop."* Two consequences:

- The realized set **never contains a diagonal pair**, so blame detection cannot be taught to match one.
- `PathPlan.blameHop` tests each skeleton hop with
  `containsEdge(realized, rawRegionKey(sk, i, minY), rawRegionKey(sk, i + 1, minY))`. If the skeleton's
  virtual intermediates are not the same ones `collectRealizedCrossings` synthesizes, the `A → B` hop
  never matches, and **every** BLOCKED later in the window falsely blames the corner hop — blacklisting
  a crossing the block tier actually made. That is a correctness defect, not a cosmetic one.

**The alternative that was considered and rejected: a SWEPT-BODY rule.** The proposal was to route
through the region the proven move's own swept body occupies, on the reasoning that each movement's
clearance predicates already name those cells, so the proof reports the answer for free:

- **`Diagonal`** requires all four corner body cells clear — `(nx,y+1,z)`, `(nx,y+2,z)`, `(x,y+1,nz)`,
  `(x,y+2,nz)` — so **both** intermediates are physically swept and the choice is free.
- **`Ascend`** (`(nx, y+1, nz)`): at a Y-boundary corner the source floor sits at local `y = 15`, so the
  bot's FEET cell `(x,y+1,z)` is already inside the **vertical** intermediate, while the lateral
  intermediate holds `(nx,y,nz)` — the block *under* the destination floor, which the bot never enters.
  ⇒ vertical first.
- **`Descend`** (`(nx, y-1, nz)`): its three `requireAir` cells are `(nx,y+2,nz)`, `(nx,y+1,nz)`,
  `(nx,y,nz)`, all inside the **lateral** intermediate. ⇒ lateral first.

That produces a genuinely appealing up-then-over vs. over-then-down asymmetry, derived from predicates
rather than remembered. **It is rejected because it conflicts with the decomposition order above** — an
`Ascend` across an `(+X,+Y)` corner is recorded by `collectRealizedCrossings` as `A → (rx+1, ry)` then
`(rx+1, ry) → D`, through the **lateral** intermediate, while swept-body would place the corner node in
the vertical one.

**And the motivation for swept-body does not survive scrutiny anyway.** The concern it answered was that
the "wrong" order steps into stone on the orthogonal — an `Ascend`'s lateral intermediate is typically
solid rock. But the intermediate is a **virtual routing token whose contents nothing reads**: the
block-tier target is the proven portal cell (§3.5), the §3.1 preconditions look at `A`, `D` and the
neighbours' adjoining fragments, and no consumer probes `B`'s geometry. There is no one to step into
stone. Note also that **no** intermediate-selection rule requires the intermediate to carry a fragment —
the typical corner-cut intermediate is `KIND_AIR` with no fragment records at all, and standing in for
that missing fragment is the virtual node's whole job.

### §3.5 What the block tier aims at — a portal CELL, not a face

Worth stating plainly, because "a crossing is a face plus a bounding box, and that is what gives the
block tier something to target" is the natural assumption and it is **not** how the normal path works.

`RegionPathPlan` stores, per step, `(rx, ry, rz, fragmentId, portalCell, isDig)` — the portal is a stored
world **cell**, stamped by whichever edge won the relaxation (`relaxFrag`'s `px,py,pz`).
`WindowTargeting.target` walks the window far → near and returns `skeleton.portalCell(i)` **raw** as soon
as `isUsableTarget` passes. Face + footprint bbox appear ONLY in the `snapInFootprint` **fallback**, which
runs when the stored portal is unusable (buried in rock, or mid-air with no floor) — that fallback exists
precisely because a face centroid is a lossy guess at the real opening.

So a corner step stamps its portal with **the proven destination floor cell from the §3.2 scan**:
measured, standable, and guaranteed to pass `isUsableTarget`. Strictly better input than a centroid. If
the corner crossing is the fourth step of the window, the block tier is handed that cell and its own A\*
emits the diagonal.

The only degraded case is a stale portal — the world edits and the proven cell stops being usable. Then
`snapInFootprint` runs, `entranceFace` hands it the real face `D` was entered through (the chain in §3.3
keeps that honest), and the scan clamps to a real footprint bbox as it does for any other step.

### §3.6 Invalidation machinery

**The key-space asymmetry is real, and it is what makes diagonal invalidation possible.** The search
node has to pack region + fragment + entry-face + from-fragment into ONE 64-bit word, which is why the
from-region is compressed to the 3-bit `entryFace` and recovered by inference (§1). The invalidation
structures are under no such pressure: they keep the two endpoints in **separate parallel `long[]`s**,
each holding a full `RegionPathfinder.fragmentNodeKey`.

- `RegionEdgeBlacklist` — per-navigation, in RAM, held on the bot, cleared when the goal changes.
  `private long[] from` / `private long[] to`, `add(long fromKey, long toKey)` /
  `contains(long fromKey, long toKey)`, plain linear scan (the set is a handful of edges per stuck
  episode, and the scan keeps the per-expansion probe allocation-free).
- `RegionCrossingMemory` — created with the `RegionGrid`, persisted, survives every
  `HierarchicalRegionPlan` boundary. `long[][] from` / `long[][] to` / `long[][] sig` plus
  `byte[][] prov`, indexed per LEVEL `0..RegionAddress.MAX_COARSE_LEVEL` (the physical keys do not encode
  the level). Rows are tagged with the failing `BotCaps.realizabilitySig` and kept as a dominance
  ANTICHAIN by `record(...)`, so an invalidation is automatically capability-scoped — a stronger bot
  ignores a weaker bot's negative. Provenance is `PROV_PROOF` / `PROV_ESCALATION` (session-only, filtered
  at encode) / `PROV_ROLLED_UP`. Persisted through `CostPyramidCodec`, rows bucketed by their FROM
  region's shard.

`RegionCrossingMemory.REGION_MASK = (1L << 49) - 1` names the split exactly, and its comment spells it
out: *"The region part of a `RegionPathfinder.fragmentNodeKey` — `packLevelKey` bits 0..48 (the 6-bit
fragment id sits at bits 49..54)."* So each endpoint carries **49 bits of region + 6 bits of fragment**,
independently — against the 3 bits the node key can spare. A diagonal `A → D` pair is natively
expressible; nothing in either structure requires face adjacency.

`relaxFrag` already states the intent for ordinary crossings:

> The blacklist keys crossings PHYSICALLY (region, fragment) — a dead crossing is unrealizable regardless
> of how the FROM region was entered (§2), so the online-repair probe uses the plain physical key, NOT the
> entry-augmented search key.

**The entry-conditioned rule is real, but narrower than it is usually remembered — and it does not touch
us.** It exists on exactly one path: the **approach into the virtual goal V**. `PathPlan.blockedHop` has
two branches:

- *virtual-goal branch* (`isVirtualGoal(skeleton.fragmentId(hop + 1))`):
  `out[0] = RegionPathfinder.approachRowKeyForStep(skeleton, hop)` — the FULL entry-augmented word
  `(region, fragment, entryFace, fromFrag)` — and `out[1] = fragmentNodeKey(V)`.
- *ordinary branch*: two plain `fragmentNodeKey`s, no entry-face, no from-fragment.

The search side mirrors that split: `relaxVirtualGoal` probes with
`approachRowKey(nodes.x[curRow], nodes.y[curRow], nodes.z[curRow], nodes.frag[curRow],
nodes.entryFace[curRow], nodes.fromFrag[curRow])`, while `relaxFrag` probes with the physical pair. Both
javadocs name the parity requirement between add-side and check-side explicitly. V has no region of its
own and the skeleton stores only `(region, fragment)` per step, which is why all the discrimination has
to live on V's FROM side and be re-derived by inference — that is the *only* place the from-region
inference is load-bearing for invalidation.

**A corner crossing is an ORDINARY crossing** — its `to` endpoint is a real fragment in a real region, not
V — so it takes the physical-pair path and needs no change to the entry-conditioning machinery.
**Verified.**

> **One caveat to rule on.** If a corner cut is the last hop before V, then `approachRowKeyForStep`
> computes `fromFrag = sk.fragmentId(hop - 1)`, which is the corner-cut id, and derives `entryFace`
> geometrically from the `C → D` delta. That is self-consistent (it reproduces what the search stamped),
> but it puts a **virtual id inside a persisted-adjacent approach key**. Decide whether such a row is
> allowed to persist or must be dropped at encode, as `CostPyramidCodec` already does for virtual-goal
> rows.

**The residual this fixes — why diagonal keying is needed at all.** Left alone, the natural blame for a
failed corner is whichever chain hop `blameHop` lands on, and the `B → D` half keys as
`(B, CORNER) → (D, fragD)` — which is shared by **every** `A` cutting a corner through `B` into `D`. Kill
one and you kill the others. It fails safe (a surviving `A → B` edge just reaches a corner node whose
exits are all blacklisted and relaxes nothing), but it is far coarser than the per-approach model
everywhere else in this tier.

**The collapse: the skeleton says `A.1 → B.61 → C.61 → D.1`, the invalidation must say `A.1 → D.1`.**

Two sides have to agree, and both are small:

1. **Add side — `PathPlan.blockedHop(long[] out)`.** The blamed hop index comes from `blamedHopIndex()`
   → `blameHop(sk, windowStart, targetStep, realized, minY, startRegionRawKey)`. Before filling `out`,
   walk the FROM endpoint **backward** and the TO endpoint **forward** past any run of corner-cut steps
   (one step for a 2-axis edge, two for a 3-axis vertex), then emit `(A, fragA) → (D, fragD)`. This is a
   pure key-construction change; the blame *decision* is untouched, and it stays correct only because
   §3.4 pins the intermediates to the decomposition order.
2. **Check side — the corner exit emission.** It must probe the identical key. `A` is recoverable at the
   corner node's pop by exactly the §1 inference: `RegionAddress.neighborRX/RY/RZ(…, entryFace)` gives
   `A`'s region and `nodes.fromFrag[curRow]` gives `fragA`. So the emitter can build
   `blacklist.contains(fragmentKey(A…), fragmentKey(D…))` itself rather than relying on `relaxFrag`'s
   per-hop probe, which would only ever see the `A → B` and `B → D` halves. This is §3.1's precondition 5.

**Roll-up is already safe, by accident of an existing guard.** `InvalidationRollup` derives a crossing's
parent face from the child-cell delta via `faceOf(dx, dy, dz)`, which returns `-1` for anything not
face-adjacent, and the fold bails — the code anticipates this for the intra-region fragment→fragment mine
crossing (delta 0) and the comment says so: *"A non-face-adjacent pair … maps to no parent face — nothing
to fold."* So a diagonal row **never rolls up** and can never cause a false parent kill. The cost is that
corner crossings contribute nothing to coarse-level invalidation derivation; acceptable, since a coarse
level has its own corner crossings to blame directly. `evictLeafTouching` masks `REGION_MASK` off both
endpoints and works on diagonal rows unchanged.

**Diagonal keying also keeps the rows persistable.** A collapsed `(A, fragA) → (D, fragD)` row names **no
virtual id**, so it is durable world knowledge and survives `CostPyramidCodec` encode/decode with nothing
to filter. Only rows that still name `CORNER` after the collapse — the V-approach caveat above is the one
known way to produce one — would need the drop treatment.

**On "if the connection is proven, invalidation shouldn't be possible".** Nearly, but not quite — and the
gap is worth keeping the machinery for. The §3.2 proof establishes that the move exists *under the
movement predicates, over the nav grid, at plan time*. It does not establish that the executor realizes
it, and it says nothing about later ticks. A `BLOCKED` result comes from a block-tier search failure, and
that search runs the same predicates, so a proven corner cut should normally be found — unless the grid
changed between plan and search (an edit, a chunk load, a `NavGridUpdater.editEpoch` bump), the window
target moved, or the search hit its node/time budget or the `IRREVERSIBLE_GUARD` before reaching the
corner. So corner invalidation should be **rare rather than impossible**, and the cost of supporting it
is one extra linear-scan probe over a set that is normally empty.

### §3.7 REJECTED ALTERNATIVE — the direct corner edge

**Rejected 2026-08-27. Do not re-litigate; the reason is structural, not a preference.**

The proposal was to skip the intermediate entirely and emit ONE edge from `(A, fragA)` straight into
`(D, fragD)`, stamping `entryFace` with whatever `RegionPathfinder.approachEntryFaceForStep` /
`WindowTargeting.entranceFace` would derive from the diagonal region delta (both test `dx`, then `dy`,
then `dz`, and return the first nonzero — so a `(+1,0,+1)` corner yields face `0`, `D`'s -X face, from
both). It looked attractive: no third sentinel, no `MAX_FRAGMENTS` reduction, no codec guard, no virtual
ids in blame keys, 2-axis and 3-axis handled identically, and no intermediate to be rejected by a
`RegionBox`/`RegionTube` containment test.

**Why it cannot work: it destroys the from-region inference (§1).** A corner edge into `D` stamps face
`0`. An ordinary face edge into `D` from its -X neighbour `N` stamps face `0` as well. When
`fragA == fragN` those are the **same node row**, and the key can no longer answer "which region did I
come from" — the node degenerates to *"fragment F in a region we cannot discern"*, which for a pure
corner could be any of the 26 neighbours.

It is not a theoretical collision. `fromFrag == 0` is the overwhelmingly common value: every uniform,
collapsed, and unbuilt neighbour relaxes into fragment `0`, and plenty of MIXED regions keep exactly one
fragment. The collision is the expected case.

**And it cannot be repaired.** The fix would be a fourth `entryFace` codepoint meaning "corner — do not
infer my source from this face". There is none: 0-5 are faces, 6 is `ENTRY_START`, 7 is `ENTRY_INTERIOR`.
That is the same wall §1 hits for widening adjacency. Reusing `ENTRY_INTERIOR` only relocates the
ambiguity (intra-region mine vs. corner crossing — different from-regions, same codepoint).

The failure is **silent**, which is the worst part. Nothing crashes and parity does not drift:
`approachRowKeyForStep` derives face `0` from the diagonal delta, matching what the search stamped. The
key is simply the ambiguous one, so a blacklist entry meant for the corner cut kills a realizable face
route into the same fragment, or vice versa.

**The consequence for §3.3, and why the sentinel is not bookkeeping.** A virtual from-fragment is the
ONLY way to say *"my predecessor is not the region you would infer from my face."* The 3-bit face field
cannot make that statement; the 6-bit fragment field can, because a value no real fragment can hold
carries the exception. Both two-hop keys stay unambiguous:

- `(B, CORNER, entryFace, fragA)` — `entryFace` pins `A`'s region, `fragA` pins its fragment, and no
  ordinary expansion can ever mint `CORNER`.
- `(D, fragD, oppFace, CORNER)` — "entered from the corner cut in the region across `oppFace`", which no
  face route can collide with.

**Salvaged from the analysis** (these findings stand on their own and are used elsewhere in this doc):
the block-tier target is a stored portal CELL, not a face+bbox, so a corner step aims the block search at
the proven destination floor cell (§3.5); and the invalidation graph keys `from` and `to` as two
SEPARATE `long`s, so it can express a diagonal crossing even though the node key cannot (§3.6).

### §3.8 UNRESOLVED — the reverse cost field

**To be decided.** `costToGoalField` is the goal-rooted reverse Dijkstra that builds `RegionCostField`,
and it runs the **same** `expandNode` with `dijkstra = true`. Two consumers care: the block A* heuristic
takes a region-refined term from it, and `RegionPathfinder.isSealedWithin` runs a *closed flood* of it as
the boxed-in proof that `PathPlan` reads as a give-up. Both branches are uncomfortable:

- **Corner cuts absent from the field** — the field cannot cross the corner either. The heuristic
  over-estimates (inadmissible), and the goal-side flood never reaches the bot; if that flood also closes
  inside its box we declare SEALED and refuse. That is **the same fail-closed refusal through a different
  door**, which is the whole thing this design exists to remove.
- **Corner cuts present in the field** — `relaxFrag` deliberately forces `cfrom = VIRTUAL_START_FRAG` when
  `dijkstra`, so the field is not split by predecessor. That erases the corner node's "which `A` did I come
  from" identity, so two different cuts through the same `B` collapse into one row: enter on one proof,
  exit on another.

Two candidate resolutions to weigh: **(i)** exempt corner-cut nodes from the `cfrom` flattening — they
become the one node class that keeps its from-fragment in the field; or **(ii)** in the reverse field
create no intermediate node at all and relax `D -> A` directly, since the field only needs costs and never
needs a face-shaped skeleton. Note (ii) means the field could use §3.4's direct-edge form even if the
forward search keeps §3.3's chain.

## §4 Open questions for the owner

Questions 1–6 are ANSWERED (owner, 2026-08-27) and are mirrored in §7's ledger. Question 7 is a standing
task; question 8 is the one live blocker.

---

**1. Build time or search time?** Build time (a stored corner fragment) needs a persisted representation
and a build-order guarantee that the diagonal neighbour's fragments already exist; it also means a
`KIND_AIR` region can no longer be a *uniform* record, since uniform kinds carry no fragment records and
stop after 6 bits. Search time (synthesised in RAM on expansion) needs no format change and no build
ordering, but re-proves per search.

> **ANSWER: search time.** Diagonal crossings are expected to be very rare, so spending time to discover
> and record them before they're crossed in a real search is just region generation overhead — and we
> don't want regular gameplay to be impacted due to a once-in-a-month search event. Furthermore, our bits
> are already very limited: we operate on shoestring budgets. Adding extra bits to our region
> representation to allow us to encode "this region connects to that diagonal region" is just bloating
> our files on disk.

**2. Which movements are in scope?**

> **ANSWER: `DiagonalParkour` IS in scope.** That's the whole reason our scan (§3.2) looks at a larger
> envelope instead of a `1 x 1 x N` cuboid. If we only cared about moves that progress the bot a single
> block, we would only need to look at a single block. We look at a larger envelope in order to detect
> these gaps.

*Note.* The original question named `DiagonalAscend`/`DiagonalDescend` as existing movements; they do
not exist yet (§2.1). The in-scope set today is `Diagonal`, `DiagonalSprintSwim`, `Ascend`, `Descend`,
`WalkOff`, `Parkour`'s offset and rising/falling arms, `Swim`'s lateral arm, and now `DiagonalParkour`.
§3.2's scan geometry was subsequently reworked to a one-sided `R x R x N` source scan, which serves this
answer directly: `R` is the maximum lateral reach over the movement set, so admitting a longer move
widens the envelope automatically.

**3. Which intermediate carries the fragment?** Pick deterministically so the graph is stable across
searches; emitting both doubles the branching for no gain.

> **ANSWER:** So long as we're consistent, it doesn't matter. I'm tempted to favor `x > y > z` ordering
> just because it's alphabetical. Although I could also see an argument for `y > z > x` since it matches
> how we encode the region IDs (which matches how Minecraft stores block locations on disk).

*Note — this turned out NOT to be a free choice, and the instinct toward `x > y > z` was the correct one.*
`BlockPathfinder.collectRealizedCrossings` decomposes every boundary-straddling block edge into
single-axis region steps in a fixed **X, then Y, then Z** order, and `PathPlan.blameHop` matches skeleton
hops against that realized set. Choosing `y > z > x` would make the `A → B` hop never match, so **every**
BLOCKED later in the window would falsely blame the corner hop and blacklist a crossing the block tier
actually made. The order is forced to X → Y → Z. See §3.4.

**4. Cost of the hops.** They must sum to one diagonal step, not two (or three) region crossings, or the
skeleton will systematically over-price corner routes and detour around them.

> **ANSWER:** When pricing the region tier (e.g. for the region-refined heuristic) we currently use a
> rough estimate based on bbox locations ("portal" cells) and Manhattan distances. I don't believe it's
> impossible to add a branch to this cost calculation logic to say "if the fragment has ID 61, it has no
> cost to enter". This will collapse all of the intermediary hops and we only pay for the last one.

*Note — one implementation detail stands in the way, and it is easy to miss.* `relaxFrag` does not use
the edge cost directly; it computes `tentative = gCur + Math.max(edge, WALK_PER_BLOCK)`. That floor
exists so g grows monotonically even for perfectly-aligned portals, keeping the search well-ordered. A
zero-cost corner edge would still be charged `WALK_PER_BLOCK`, so a 2-hop chain costs ≥ 2 ticks and a
vertex chain ≥ 3, against a diagonal's ~1.41. The "no cost to enter 61" branch therefore has to bypass
the floor, not just pass `edge = 0`. Bounded chains (≤ 2 intermediates, and a corner node's only exits
are proven corner exits) mean zero-weight edges cannot form a cycle, so the well-ordering argument the
floor protects is not lost here.

**5. Scan-window width.** §3.2 needs a concrete bound on how far apart a source and landing cell may be.

> **ANSWER:** As mentioned, this depends on what moves we want to admit — and I strongly propose
> admitting ALL diagonal moves. Otherwise we create a false FAIL (e.g. the only connectivity is a
> DiagonalParkour and we refuse to emit the skeleton, thus preventing a possible block-tier path).

*Note.* This is the same principle §3.2 states as a rule: the movement enumeration is the authority, and
any offset or reach pre-filter must be **derived** from the registered movement set rather than written
beside it. A hardcoded bound is the one place where adding a movement would silently fail to widen the
scan — and the resulting failure looks exactly like the original bug.

**6. `MAX_FRAGMENTS` 62 → 61 is a persisted-format-adjacent change.** The count field is 6 bits with 63
as `FRAGMENT_COUNT_COLLAPSED`, so the field WIDTH does not move — but any region already persisted with
exactly 62 fragments would have to decode to something sane.

> **ANSWER:** There are ZERO users of this mod in the wild. The ONLY people running it are Claude and
> myself, and only on this computer, and only as part of test runs. Claude does not copy the `orebit/`
> directory when performing autotest runs, and I always delete the `orebit/` directory during manual
> runs. So there is never a "persisted" HPA\* region graph to load. It's always fresh when the game is
> launched.

*Note — register the guard as a PRE-RELEASE checklist item rather than dropping it.*
`CostCodec.unpackRegion` reads a 6-bit count and loops `for (f < count)` calling `out.setFragment(f, …)`
into arrays sized `MAX_FRAGMENTS`. A shard carrying `count == 62` would throw AIOOBE rather than fall
back to the "bad shard ⇒ rebuild from live" contract that makes shards safe to treat as a cache. The fix
is one line — `count >= MAX_FRAGMENTS` ⇒ treat as collapsed, before the loop. Persistence version
constants stay PINNED AT 1 per the pre-release ruling.

**7. Audit every `isVirtualStart` / `isVirtualGoal` call site.** They take a bare `int`, and the safety of
that rests entirely on the §1 / §3.3 same-meaning-in-both-fields invariant. Adding a third sentinel is the
moment to confirm that invariant actually holds everywhere rather than by custom.

> **ANSWER:** Fair. I don't mind auditing things to be safe.

**8. Does the corner cut apply to the REVERSE cost field? — THE ONE LIVE BLOCKER.** Unresolved; see §3.8.
Getting it wrong reintroduces the fail-closed refusal through `isSealedWithin`, which is the exact failure
this whole design exists to remove. Absent from the reverse field, the goal-rooted flood cannot cross the
corner and can declare a reachable goal SEALED. Present in it, `relaxFrag` forces
`cfrom = VIRTUAL_START_FRAG` in dijkstra mode, erasing the corner node's source identity so two different
cuts through the same `B` collapse into one row. Two candidate resolutions are recorded in §3.8.

## §5 Verification plan

0. **A SECOND red pin for the vertical corner** (§2.1) — a free-standing 1-wide staircase whose lateral
   and Y region boundaries coincide, beside `regionCornerPin()`. Without it the `Ascend`/`Descend` half of
   the gap has no oracle, and it is the half that is currently undocumented anywhere but here.
1. **`regioncorner.walkin`** (`ParkourCourse.regionCornerPin()`) — the RED pin. Today:
   `FAIL (nav gave up (no route offered))` with `maxProj = -8.49`, the bot never leaving its spawn
   block. Must go green.
2. **The `diag` family must stay green** and must stay *nudged* — `addDiagTrial`'s alignment nudge
   should remain, so those cards keep testing diagonal parkour rather than silently re-testing this.
3. **No skeleton-shape regressions.** The 2026-08-24 numbers this section used to quote
   (`replan` 19/19, `swim` 20/1, `iceparkour` 23/0, `parkour` 80/1) are STALE. As of the 2026-08-27
   ladder-descent landing the course family stood at: unit **1150/0**, `parkour` **106/1** (the 1 being
   `regioncorner.walkin`, this pin), `replan` **18/18**, `swim` **21/21**, `iceparkour` **23/23**,
   `trapdoor` **13/13**, `ice` **4/4**, `gate` **4/4**, `swimmine` **2/2**. **Re-measure before starting**
   rather than trusting either list — the point of the gate is the delta, and only `parkour` should move
   (106/1 → 107/0, plus the new item 0 vertical pin).
4. **Region-tier expansion cost must not move measurably** on the common path; the §3.1 preconditions
   are rejections, and that needs to be shown, not assumed. `PathfinderBenchmark` plus a region-trace
   expansion count on a dense-cave fixture.

   **This is a HOT PATH — thousands of nodes, every search.** We need to carefully benchmark and ensure
   that the presence of diagonal connections does not appreciably hurt performance. Two costs are new and
   must be measured separately, because they are paid at very different rates:
   - the **per-expansion** cost of considering the diagonal neighbours at all and running the §3.1
     precondition rejections — paid on *every* expansion, whether or not a corner ever exists;
   - the **per-crossing** cost of the §3.2 scan (`R x R x N` source cells, each surviving one paying a
     movement enumeration plus a landing probe) — paid only where the preconditions all pass.

   The first is the one that can quietly tax every search in the game; the second is bounded by how rare
   the trigger is. A regression in the first is a reason to abandon or restructure, not to tune.
5. **In-game**: the `orebit-diag1` datapack
   (`run/saves/New World (1)/datapacks/orebit-diag1/`) rebuilds the exact geometry —
   `/function orebit:diag1`, `orebit:start`, `orebit:markers`.
6. **The blame path needs its own test** (new, 2026-08-27 — §3.4 / §3.6). A corner crossing that the
   block tier fails to realize must blacklist the DIAGONAL pair `(A, fragA) → (D, fragD)`, not the
   `A → B` chain hop. Two failures are silent without a test, and both blacklist crossings that work:
   an intermediate chosen against `collectRealizedCrossings`'s X → Y → Z order (so `blameHop` never
   matches the `A → B` hop), and a `blockedHop` that does not collapse the corner run. A fixture that
   forces a corner crossing and then makes it unrealizable at the block tier is the oracle.

## §6 Related

- `internal_docs/HPA-FRAGMENTS.md` §2, §5 — the fragment model and its storage.
- `internal_docs/DESIGN-typed-fragments.md` §1–§2, §5.3–§5.5 — fragment types and record layout.
- `RegionEdgeBlacklist` / `RegionCrossingMemory` — online repair of *unrealizable* hops. An earlier
  revision said these were deliberately unused here ("the crossing is proven, so there is nothing to
  blacklist"). **That was wrong and §3.6 supersedes it:** the proof establishes that the PLANNER's move
  exists, not that the EXECUTOR can realize it, so a corner crossing can still go BLOCKED and must be
  blameable. §3.6 is the plan for keying that blame.
- `BlockPathfinder.collectRealizedCrossings` + `PathPlan.blameHop` / `blockedHop` — the realized-crossing
  decomposition and the blame walk. Read together they FORCE §3.4's X → Y → Z intermediate order; a
  mismatch there is a silent blacklisting of working crossings.
- `RegionPathfinder.expandNode` / `relaxFrag` — the capability gates that create the gap
  (`!canPlace && kind == KIND_AIR && f != 2`, and the per-fragment `airGated && typeB == 0`), the
  physical-key blacklist probe, and the `max(edge, WALK_PER_BLOCK)` floor §4 Q4 has to bypass.
- `RegionPathfinder.costToGoalField` / `isSealedWithin` — the reverse field and the boxed-in proof that
  make §3.8 load-bearing rather than cosmetic.
- `WindowTargeting.target` / `snapInFootprint` — why the block-tier target is a stored portal CELL and
  the face + bbox is only the fallback (§3.5).
- `InvalidationRollup.faceOf` — returns `-1` for a non-face-adjacent pair, which is why diagonal blame
  rows are inert at roll-up rather than dangerous (§3.6).
- The registered `capability-aware-flood-intent` item (connectivity as the actual movement
  predicates) is the general form of this problem; this is one proven, bounded instance of it.

## §7 Decisions ledger

Everything decided so far, in one place, so nothing has to be inferred from prose. "Owner" means ratified
by the project owner in discussion.

### RATIFIED

| # | Decision | Where | Date |
|---|---|---|---|
| R1 | The gap is real, fails CLOSED, and is worth machinery despite its rarity | §0 | 2026-08-24 |
| R2 | Widening region adjacency to 10/26 directions is not affordable — the node key has zero spare bits | §1 | 2026-08-24 |
| R3 | A raw block search with no skeleton is rejected — the two-tier design collapses without sub-goals | §1 | 2026-08-24 |
| R4 | A blanket "retry admitting corner steps on FAIL" is rejected — impossible bee-lines over thousands of hops | §1 | 2026-08-24 |
| R5 | Encode the crossing as a CHAIN of ordinary single-axis face hops through virtual corner-cut nodes (one intermediate for a 2-axis corner, two for a 3-axis vertex) | §3.3 | 2026-08-24, chain-generalized 2026-08-27 |
| R6 | The corner-cut id needs its OWN codepoint; drop `MAX_FRAGMENTS` 62 → 61 and give the corner cut 61. A sentinel means the SAME thing in every field and position | §1, §3.3 | 2026-08-24 |
| R7 | The cap reduction's cost (one fewer fragment before collapse) is accepted | §3.3 | 2026-08-24 |
| R8 | Connectivity is emitted ONLY when a real movement is proven — never optimistically | §3.2 | 2026-08-24 |
| R9 | The gap is NOT limited to the `Diagonal` class — it is any move crossing ≥ 2 axes. Three-axis crossings exist TODAY (`DiagonalSprintSwim`'s 8 corners) | §2.1 | 2026-08-27 |
| R10 | The proof scan is MOVEMENT-driven. Any offset or reach pre-filter must be DERIVED from the registered movement set, never hardcoded | §3.2, §4 Q5 | 2026-08-27 |
| R11 | The proof scan is ONE-SIDED: scan `A` for standable sources, enumerate real movements, probe each landing for `standable() && in D`. No second scan, no pairing | §3.2 | 2026-08-27 |
| R12 | The scan cost is accepted as-is: `R x R x N` source cells (144 at L0, 4,608 at L5 with `R = 3`), gated behind the §3.1 rejections | §3.2 | 2026-08-27 |
| R13 | The corner node offers UP TO N proven exits, re-proved on pop — not exactly one | §3.3 | 2026-08-27 |
| R14 | The corner node's expansion is special-cased: skip the generic face loop, emit only proven corner exits | §3.3 | 2026-08-27 |
| R15 | The block-tier target is the stored portal CELL — stamp it with the proven destination floor cell | §3.5 | 2026-08-27 |
| R16 | The intermediate order is **X → Y → Z**, forced by `collectRealizedCrossings`'s decomposition (not a free choice) | §3.4, §4 Q3 | 2026-08-27 |
| R17 | Blame is keyed DIAGONALLY: collapse corner runs in `blockedHop` and emit `(A,fragA) → (D,fragD)`. The invalidation graph stores from/to as two separate `long`s, so it can express that | §3.6 | 2026-08-27 |
| R18 | **Search time, not build time** — pre-recording diagonal crossings is region-generation overhead for a once-in-a-month event, and extra bits on disk we do not have | §4 Q1 | 2026-08-27 |
| R19 | **`DiagonalParkour` IS in scope** — the larger scan envelope exists precisely to catch gap-spanning moves; excluding them creates a false FAIL | §4 Q2 | 2026-08-27 |
| R20 | The chain's hops must sum to ONE real move — entering a corner-cut fragment costs nothing. NB this must bypass `relaxFrag`'s `max(edge, WALK_PER_BLOCK)` floor, not merely pass `edge = 0` | §4 Q4 | 2026-08-27 |
| R21 | The `MAX_FRAGMENTS` 62 → 61 decode guard is DEFERRED to a pre-release checklist item — no persisted region graph exists in any live world | §4 Q6 | 2026-08-27 |
| R22 | An already-invalidated crossing is precondition 5, probed before the §3.2 scan is paid for | §3.1, §3.6 | 2026-08-27 |

### REJECTED — do not re-litigate

| # | Rejected | Reason | Where |
|---|---|---|---|
| X1 | The DIRECT corner edge (`A → D` in one hop, no intermediate) | It destroys the from-region inference. `entryFace` is the only affordable way to name the region a region-scoped `fromFrag` belongs to; a corner edge lets two different predecessors mint the same key, and `fromFrag == 0` is the common case, so the collision is expected rather than theoretical. No spare `entryFace` codepoint exists to mark the exception. The failure is SILENT — it blames the wrong approach | §3.7 |
| X2 | Reusing fragment id 62 or 63 for the corner cut | Both existing sentinels are ONE-SIDED (62 never occurs as a to-id, 63 never as a from-id), so each leaves a real vacancy — but the corner cut is TWO-SIDED and needs a codepoint vacant in both fields. Spending one would mean an id that changes with position | §1, §3.3 |
| X3 | A Y-window (or any hardcoded) bound on the proof scan | Hard-codes today's movement set into the region tier; adding a movement would silently fail to widen the scan, and the failure looks exactly like the original bug | §3.2, §4 Q5 |
| X4 | Choosing the intermediate by the move's SWEPT BODY | Appealing (it derives the `Ascend` up-then-over / `Descend` over-then-down asymmetry from the movement predicates) but it conflicts with `collectRealizedCrossings`'s fixed X → Y → Z order, which would make `blameHop` falsely blame the corner hop. Its motivation also does not survive: nothing reads the intermediate's contents | §3.4 |

### OPEN — must be resolved before implementation

| # | Question | Where | Notes |
|---|---|---|---|
| O1 | **Does the corner cut apply to the REVERSE cost field?** | §3.8, §4 Q8 | **THE BLOCKER.** Absent → `isSealedWithin` can declare a reachable goal SEALED, reintroducing the fail-closed refusal this design exists to remove. Present → `relaxFrag` forces `cfrom = VIRTUAL_START_FRAG` in dijkstra mode, erasing the corner node's source identity. Two candidate fixes recorded |
| O2 | Audit every `isVirtualStart` / `isVirtualGoal` call site | §4 Q7 | They take a bare `int`; a third sentinel is the moment to confirm the same-meaning invariant holds everywhere rather than by custom. Owner: "Fair. I don't mind auditing things to be safe" |
| O3 | May a corner-cut id appear in a persisted-adjacent approach key? | §3.6 | If a corner cut is the last hop before V, `approachRowKeyForStep` stamps `fromFrag` with the corner id. Self-consistent, but decide whether such a row persists or is dropped at encode as virtual-goal rows already are |

### Scoping fact that should inform all of the above

`placement.canPlace` **defaults to `true`**, and the gap only exists for a no-place bot — so as the region
tier stands, this whole mechanism is dormant for a default-configured bot (§3.1). It fires for
`placement.canPlace = false`, a documented supported configuration and the one `regionCornerPin()` uses.
It would become common if the region tier were made inventory-aware: `BotCaps.java:361` already computes
an inventory-effective place flag, but that feeds the realizability signature only — `AllyBotEntity`
passes the raw `caps.canPlace()` into the region call. Threading the effective flag through is a small
change the owner expects to make eventually (§3.1, §4 Q1). The gap fails closed, which is why rarity does
not bound the damage.
