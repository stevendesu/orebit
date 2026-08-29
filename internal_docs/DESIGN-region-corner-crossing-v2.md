# DESIGN — Region corner crossing (optimistic emission)

**Status: IMPLEMENTED 2026-08-29** — the cross-region chain, the §4.9 L1+ merge, R30's re-merge, and the
§5.1 instrumentation are all live; `regioncorner.walkin` AND the new vertical pin `regioncornerY.walkin`
are GREEN with the full course family at baseline. **§9 is the implementation record — including ten
implementation amendments PENDING OWNER RATIFICATION (A1–A10) and the verification/perf results.**
Design ledger below unchanged from rev 3 (2026-08-28, post second code-review).
Supersedes `DESIGN-region-corner-crossing.md`, kept on disk only as the record of the rejected proof-scan
design.

**Rev-3 changes** (2026-08-28, after an independent verification pass re-read every cited line). Six
additions, five owner-ratified and one promoted to a blocking question:
**R33** the Y axis does not exist at/above `OCTREE_TOP`, so Y-bearing corners are an L0..L4 mechanism
(§2.1); **R34** an UNBUILT intermediate is rejected by precondition 3, never reached by precondition 5
(§4.1 note 1b); **R35** the corner short-circuit belongs at the TOP of `expandNode`, before `typeA`, or a
MIXED intermediate throws AIOOBE (§4.3); **R36** the box/tube containment test names `D`, never an
intermediate — the tube can reject a `B` whose endpoints it admits (§4.3); **R37 + R37a** `handDown` and
`extendL0` do NOT guard on `hasPortal`, they fall back to the region CENTRE, so a hand-down walks the
chain forward to `D`'s corner-column portal, under an enforced chain-atomicity invariant (§4.5 — this
**corrects R32's "every consumer already guards"**); **R38** the §4.9 merge requires `TYPE_S` on both
masses as a rule of its own, since §4.1 has no authority over the build sites (§4.9).
**Plus the step-consumer sweep (§4.5, §4.5.1):** **R40** — owner ruling, *no boundary ever lands on a
virtual fragment*; every index naming a skeleton position walks past a corner run (forward for a
start/target, backward for an anchor), `splice` asserts it, and §4.5.1 carries the site census.
**R41** the two consumers that do not read `hasPortal` — the cascade's commit matcher and the window's
step budget — are safe, with the arguments recorded; the one live defect there is the free-fall
extension's `ry` monotonicity scan, which a FLAT corner terminates early. **R42** a corner node's `h` is
measured from `B`, an accepted one-region approximation.

**Plus one qualification, NOT a blocker (R39, §4.6a):** `onBlocked` withholds the durable
`RegionCrossingMemory` row when the blamed hop's FROM region is the failing block search's own start
region (`startScoped`). That is a PRE-EXISTING property of every optimistic crossing, not something
corners introduce — but corners are the one crossing kind whose refutation R30 also consumes, so §4.10's
"the merge consults that same surviving L0 row" is CONDITIONAL and is now written as such.

**Rev-2 changes** (owner-ratified 2026-08-28 after a line-by-line verification pass against the code):
the crossing's COST is now specified per-hop (§4.3.1, R31) and **R20's floor bypass is RETIRED** — the
per-hop model clears `RegionCostField.MIN_CROSS` on its own; corner-cut nodes carry **`NO_PORTAL`**
(§4.5, R32), which makes them invisible to every skeleton consumer; §4.9's two merge sites are split by
risk profile and the L0 one is **gated on I4**.

**§8 is the decisions ledger — read it first.**

**Terminology.** A *diagonal* move here is **any block-tier movement that changes more than one axis in a
single step** — not merely the `Diagonal` movement class. Several members of that set do not carry
"Diagonal" in their name (`Ascend`, `Descend`, `WalkOff`, `Parkour`'s offset arm, `Swim`'s lateral arm);
§2.1 enumerates all of them.

**Line/bit facts were verified against the code on 2026-08-28 and will drift.**

---

## §0 Problem

The block tier can walk a route the region tier calls unreachable, and the two-tier driver refuses to
move at all.

Convicted on a 1-wide diagonal stone chain (the `diag1` fixture, and reproduced by hand in-game via the
`orebit-diag1` datapack). Block-tier A\* returns a clean 11-waypoint path — six plain `Diagonal` steps out
to the takeoff, then a `DiagonalParkour`. The region trace says:

```
skeleton: NONE (no coarse route — no built ground at start, or region A* FAILed)
```

The chain steps `(143,·,143) → (144,·,144)`: **both** the x and z region boundaries at once
(`RegionAddress.LEAF_SIZE == 16`, `RegionAddress.java:36`), so it moves corner-to-corner from region
`(8,13,8)` to `(9,13,9)` and enters neither orthogonal neighbour. Region adjacency is strictly 6-face, so
no edge exists for that hop; the two corner-adjacent regions are pure air and the bot cannot place, so
there is no route through them either. Region A\* FAILs, the skeleton is NONE, and the bot never leaves
its spawn block.

**It fails closed** — a total refusal, the worst failure mode — and it is invisible in open terrain, where
a face-adjacent alternative always exists. It bites only on genuinely 1-wide diagonal structures: ridges,
bridges, ledge chains, this fixture.

Pinned RED by `ParkourCourse.regionCornerPin()` (`regioncorner.walkin`).

### §0.1 Why it looked positional

For a `(+1,+1)` chain from `(bx,bz)` the x boundary is crossed at step `(16 - bx%16) % 16` and the z
boundary at `(16 - bz%16) % 16`. **They coincide exactly when `bx % 16 == bz % 16`.**

| Tile base | x crossing | z crossing | Result |
|---|---|---|---|
| `(60,138)` — passed | step 4 | step 6 | two separate FACE crossings |
| `(138,138)` — failed | step 6 | step 6 | one CORNER crossing |

**Caveat — that formula assumes a floor-mod.** Java's `%` truncates (`-18 % 16 == -2`) while region
indexing floor-divides (`-18 >> 4 == -2`), so `(16 - bx%16) % 16` is wrong for negative coordinates. The
`bx % 16 == bz % 16` conclusion survives (both sides use the same truncating mod), but `regionCornerPin()`
deliberately sits at a NEGATIVE base — re-derive an off-grid fixture from `>> 4`, not from the printed
step formula.

A trial-list edit moved the fixture from x=60 (`12 != 10`) to x=138 (`10 == 10`) and it started failing.
That is why it was first — wrongly — filed as harness fragility. `ParkourCourse.addDiagTrial` now nudges
diagonal tiles off the degeneracy so the `diag` cards test diagonal *parkour*, and `regionCornerPin()`
pins the degeneracy on purpose.

### §0.2 The gap is not an L0 phenomenon — `PyramidMerger` reproduces it at every level

`PyramidMerger.mergeUpFragments` builds each parent fragment by union-finding child items across shared
faces, and the adjacency test is (`PyramidMerger.java:398`):

```java
if (xor != 1 && xor != 2 && xor != 4) continue;   // not adjacent (differ on >1 axis or same slot)
```

**Corner-adjacent children are never unioned.** The same 6-face assumption that creates the gap in the
search graph is baked into the roll-up. Consequence: a parent whose only connection between two child
masses is a corner step gets **two fragments where the world has one connected component**. The
parent-level skeleton must then cross between two fragments of the *same* region — the intra-region
"mine-sibling" edge — which is geometrically false, hardness-priced, and unpayable by a bot that cannot
break.

**The union rule is verified; the consequence is inferred (I2).** Confirm with a region trace at L1 on
`diag1` that it materialises there rather than being rescued by transitive union through a third child.
Expected shape: a 1-wide `(+1,+1)` ridge alternates face-adjacent steps (which union) with corner steps
(which do not), so the parent's items should split exactly at the corner.

Two things follow:

1. **The coarse levels do see the gap**, so a far goal past a corner fails closed at the top and the bot
   never approaches. This is what closes the deadlock argument in §3.
2. **The corner cut needs a second form** — connecting two fragments of the same region — that §4.3's
   cross-region chain does not address. That form is not a crossing at all; it is a MERGE (R27, §4.9).

Note the §2.1 rarity arithmetic (`bx % 16 == bz % 16`, roughly 1 chain in 16) bounds only the
*cross-region* case. The parent split needs **no alignment at all**: every L0 corner crossing produces
one, at every ancestor level.

---

## §1 Why the obvious fixes are closed

**Widening region adjacency to 10 or 26 directions is not affordable.** The A\* node key is exactly 64
bits with **zero** spare (`RegionPathfinder.fragmentKey` / the entry-face fold):

| Bits | Width | Field | Occupancy | Description |
|---|---|---|---|---|
| 0–4 | 5 | `ry` | full (0..31 leaf Y-regions) | The "to" region's y value |
| 5–26 | 22 | `rz` | full | The "to" region's z value |
| 27–48 | 22 | `rx` | full | The "to" region's x value |
| 49–54 | 6 | fragment id | 0..61 real, 62 `VIRTUAL_START_FRAG`, 63 `VIRTUAL_GOAL_FRAG` | The fragment ID within the "to" region. 62 is **vacant here but not spendable** — it never occurs (you never traverse *to* the start), yet the codepoint still cannot be reused |
| 55–57 | **3** | `entryFace` | 0..5 faces, 6 `ENTRY_START`, 7 `ENTRY_INTERIOR` | To avoid writing the full 49-bit region ID (ry,rz,rx) for the "from" region, we record only how it is related to the "to" region and infer the actual coordinates when needed |
| 58–63 | 6 | `fromFrag` | 0..61 real, 62 `VIRTUAL_START_FRAG`, 63 `VIRTUAL_GOAL_FRAG` | The fragment ID within the "from" region. 63 is **vacant here but not spendable** — it never occurs (you never come *from* the goal) |

**Neither 6-bit field has a spare codepoint.** Both existing sentinels are **one-sided**:

| sentinel | occurs in | never occurs in | because |
|---|---|---|---|
| 62 `VIRTUAL_START_FRAG` | `fromFrag` | fragment id (the "to" field) | you can move FROM the start; you never traverse TO it |
| 63 `VIRTUAL_GOAL_FRAG` | fragment id (the "to" field) | `fromFrag` | you can move TO the goal; V is terminal, so nothing ever expands FROM it |

Each therefore leaves a genuine vacancy in the *other* field. That vacancy is real — and still not
spendable, for two reasons:

1. **Identity must be field-independent.** A fragment id *becomes* a from-fragment one hop later —
   expanding `B ->(±Z)-> D` stamps `D`'s `fromFrag` with `B`'s fragment id — and `isVirtualStart(int)` /
   `isVirtualGoal(int)` both take a bare `int` with nothing to say which field it came from. **A sentinel
   means the SAME thing in every field and every position.**
2. **The corner cut is two-sided, unlike either existing sentinel.** The node `(B, CORNER, ...)` puts the
   id in the "to" field, and the very next expansion stamps that same id into `D`'s `fromFrag`. It needs a
   codepoint vacant in **both** fields, and neither existing vacancy qualifies.

So §4.3 *takes* a codepoint by shrinking `MAX_FRAGMENTS` rather than finding an unclaimed one. State
vacancy and spendability separately, and never abbreviate either to the word "free" — this has been
misread twice.

**Notice why `entryFace` exists:** it compresses the "from" region's 49-bit ID into 3 bits, which works
only because its value is relative to the already-encoded "to" region. **That inference must be UNIQUE**,
and it is load-bearing in two places: the node-identity split that lets a blamed approach be retried
through a different predecessor (the two-hallways / A==G fixes, NOTES-region-tier.md §1.1), and the
approach-into-V blame key, rebuilt from the skeleton alone with no live row to read a from-region off. An
encoding that lets two different predecessors mint the same key does not fail loudly — it silently blames
the wrong approach. This is why the direct-diagonal-edge encoding is rejected (§4.7).

`entryFace` has **all eight codepoints already spent**. A seventh direction does not fit, let alone four
or more. The `ry` 6->5 narrowing that freed room in the 2026-07 repack was already spent on `fromFrag`.
Beyond the key, `6` is structural in `RegionFragments.footprint[MAX_FRAGMENTS*6]`, in `faceMask`'s 6 bits
inside a `byte`, in the persisted `CostCodec` record, and in the `f < 6` loops in `RegionPathfinder` (x5),
`FragmentBuilder` (x2), `InvalidationRollup` and `PyramidMerger`.

**A raw block search without a skeleton is rejected** (owner, 2026-08-24): without a skeleton there are no
sub-goals and the whole two-tier design collapses. Unbounded for far goals.

**A blanket "retry admitting corner steps on FAIL" is rejected** (owner, 2026-08-24): a far-goal FAIL can
span thousands of region hops, and admitting corners on all of them produces impossible bee-lines that
must then be continuously invalidated. §4.1's preconditions are what keep this design on the right side of
that line; see §5.

---

## §2 Which region KINDS can produce the gap

`Diagonal.candidates` requires **both** corner columns' body cells to be clear:

> Requiring both corners (rather than "one open side") is the conservative choice that never squeezes the
> bot through a solid corner — matching vanilla's [collision]

and it "never prices a break or a place". A 0.6-wide box sweeping the corner overlaps both orthogonal
cells, so this is real collision, not a modelling choice. Therefore:

- **`KIND_SOLID` cannot produce the gap.** Stone in an orthogonal cell blocks the diagonal outright —
  there is no move to strand. (If the rock is mineable, a dig-through `Traverse` is emitted and the region
  graph is connected through it.)
- **`KIND_WATER` cannot produce it.** Water is passable *and* always swimmable, so the intermediate region
  is traversable and an ordinary two-face route exists. Mechanically, and note this is the UNIFORM branch:
  `KIND_WATER` is a uniform kind with **no fragment records**, so the per-fragment `airGated && typeB == 0`
  gate never runs on it — the operative code is the uniform gate
  `if (!canPlace && rfM.kind() == KIND_AIR && f != 2) continue;`, which names AIR only. Water is simply not
  gated. (The `TYPE_W`-passes-the-gate argument is the MIXED path, and it is what makes a water fragment
  inside a MIXED intermediate equally safe.)
- **`KIND_AIR` + a bot with no place capability is the case.** Passable (so the diagonal exists) but not
  standable, and a one-way down chute the bot cannot pillar out of.
- **`KIND_MIXED` presents the same shape** when a region's air pocket yields no *usable* fragment adjoining
  the relevant face. The flood always produces a fragment for the pocket, but it earns `TYPE_S` only if
  some cell in it has footing (a standable floor below, or water at the cell) **and** air-only headroom. A
  pocket with no such cell is typeless — pure air — and the no-place gate skips it outright on every face
  except −Y (falling in). **This is why the fix cannot simply trust `kind`, and it is the case §4.1's
  intermediate precondition is built to admit** (see §4.1 note 1).

**So the trigger is: no-place bot, corner-connected fragments, and neither orthogonal intermediate
offering an adjoining usable fragment.**

Kind constants: `KIND_MIXED = 0`, `KIND_SOLID = 1`, `KIND_AIR = 2`, `KIND_WATER = 3`
(`RegionFragments.java:90-96`). Type bits: `TYPE_S = 1`, `TYPE_W = 2` (`:137`, `:139`).

### §2.1 The full set of DIAGONAL movements

The trigger set is every movement whose source and destination region addresses can differ on two or more
axes — the region graph is 6-face, so such a move has no edge to ride.

| Movement | Delta | Axes it can cross at once |
|---|---|---|
| `Diagonal` | `(±1, 0, ±1)` | **X+Z** — strictly same-Y (`out.accept(nx, y, nz, ...)`) |
| `Ascend` | `(±1, +1, 0)` / `(0, +1, ±1)` over `CARDINALS` | **X+Y** or **Z+Y** |
| `Descend` | `(±1, −1, 0)` / `(0, −1, ±1)` over `CARDINALS` | **X+Y** or **Z+Y** |
| `WalkOff` | `out.accept(lx, y − 1, lz, ...)` — lateral + down | **X+Y** or **Z+Y** |
| `Parkour`, rising/falling arms | lateral run + `dy` | **X+Y** or **Z+Y** |
| `Parkour`, **OFFSET arm** | knight's-move: a forward run *plus* a lateral component, same Y | **X+Z** |
| `Swim`, lateral arm | `out.accept(nx, wf − 1, nz, ...)` — `wf` is the destination column's water surface, so a lateral step also moves in Y | **X+Y** or **Z+Y** |
| `DiagonalSprintSwim` | 20 offsets: 4 same-Y horizontal diagonals, 8 cardinal + ±Y edges, **8 full corners `{±1,±1,±1}`** | **X+Z**, **X+Y**, **Z+Y**, and **X+Y+Z** |
| `DiagonalParkour` (flat-only today) | spans a gap; endpoints 2–3 cells apart, same Y | **X+Z**, but NOT corner-adjacent |
| *future* `DiagonalParkour` rising/falling | gap + `dy` (`DiagonalParkour.java:17`: "**v1 is FLAT only**; rising/falling" is planned) | **X+Y+Z** |
| *future* `DiagonalAscend` / `DiagonalDescend` | `(±1, ±1, ±1)` | **X+Y+Z** |

Single-axis movements cannot produce the gap: `Traverse`, `Fall`, `Pillar`, `MineDown`, `SprintSwim` (its
6 faces), `Climb` and `RideBubbleColumn`.

**Three-axis crossings already exist today.** `DiagonalSprintSwim`'s `MOVES` table carries 8 offsets with
`movingMask == 7` (X|Y|Z). So the vertex case is **not** hypothetical; it is in the shipped movement set.
This is why §4.3 is specified as a chain of **one or two** corner-cut hops rather than a single one.

**But a swimmer almost certainly cannot be STRANDED by one.** `DiagonalSprintSwim`'s per-axis clearance
rule requires **every proper non-empty subset of the moving axes** to be swimmable water — the
`for (int s = 1; s < 7; s++)` subset loop. For an X+Z corner that is exactly the two orthogonal cells; for
an X+Y+Z corner it is all three single-axis and all three pair-axis intermediates. So the move is only
emitted when those intermediate cells ARE water — which means the intermediate regions hold water
fragments carrying `TYPE_W`, which the no-place gate always passes, and whose face footprints necessarily
overlap `A`'s at the shared boundary cell. **The ordinary two-face route exists whenever the diagonal
does.** (Barring a deliberately adversarial build: isolated floating water cells separated by signs or
similar non-water passables. Not worth designing for.) So `DiagonalSprintSwim` widens the ENCODING
requirement without realistically widening the trigger set — which is also why §4.1 can require `TYPE_S`
on both ends without losing real routes.

**The realistic 3-axis case is rising/falling `DiagonalParkour`, and it is nearer than it looks.** The
class exists today and its javadoc states the limit and the plan (`DiagonalParkour.java:17`, echoed at
`:286`). The moment that arm lands, a dry-land 3-axis crossing exists with none of the water clearance
rule's protection. `DiagonalAscend` / `DiagonalDescend` would be a second such case. Both are reasons to
specify the chain for two hops now rather than retrofit it.

**The vertical case fails closed exactly as the horizontal one does.** Worked example, a free-standing
1-wide staircase: floor `(15,15,z)` → floor `(16,16,z)` with the Y region boundary between them. `A` is
region `(0,0,·)`, `D` is `(1,1,·)`; the vertical intermediate `(0,1,·)` holds only the open air above the
source step (floorless => `KIND_AIR` or a typeless `MIXED` fragment => gated for a no-place bot), and the
lateral intermediate `(1,0,·)` holds the air under the step (same). Both gated, no edge, FAIL.

**CALLOUT — the Y axis does not exist above `OCTREE_TOP` (R33).** `RegionAddress.OCTREE_TOP == 5`
(`RegionAddress.java:47`): at `level >= OCTREE_TOP` the padded vertical extent is ONE slab, `ry` is pinned
to 0 (`regionY`, `:126`), and there is no ±Y neighbour region at all — `expandNode` skips faces 2/3 there
outright (`if (level >= RegionAddress.OCTREE_TOP && (f == 2 || f == 3)) continue;`, twice). The cascade
plans to `RegionAddress.MAX_COARSE_LEVEL == 6`, so **L5 and L6 are quadtree levels**. Two consequences the
corner enumerator MUST carry, or it keys phantom `ry = ±1` regions exactly as the face loop warns:

- **Any corner whose axis pair or vertex triple involves Y is emitted only while `level < OCTREE_TOP`.**
  At L5/L6 the enumeration reduces to the single X+Z corner; the 3-axis vertex chain (§4.3) does not exist
  there, because its ±Y intermediate is not addressable.
- **The vertical worked example above is an L0..L4 statement.** At L5+ a Y-axis "corner" is not a gap in
  the graph — the two cells are the same region — so it is the §4.9 intra-region MERGE form, not a
  crossing.

**How often the alignment lands.** §0.1 gives the horizontal arithmetic: for a `(+1,+1)` chain the two
crossings coincide iff `bx % 16 == bz % 16` — 1 chain in 16, and then only at that one step. The vertical
family needs the step-up (or step-down) to land on `(y − minY) % 16 == 0` *while* crossing a lateral
boundary — roughly 1 in 16 of the ~1 in 16 lateral crossings. On top of that the terrain must be a
genuinely 1-wide structure with no usable intermediate, and the bot must be no-place (§4.1). See §5 for
the full emission-rate estimate, and §0.2 for the one case that needs no alignment at all.

**Nothing at the region tier assumes a diagonal keeps its Y**, because the tier has no notion of a
diagonal at all — so adding `DiagonalAscend`/`DiagonalDescend` needs no region-tier change.

---

## §3 REJECTED — proof-gated emission

**Do not re-propose "only emit a corner when a real block-tier movement is proven over the NavGrid."** It
is the obvious design and it cannot work. Three findings close it:

1. **The NavGrid is not there to scan.** `NavGridView.packedAt` returns the `UNBUILT` sentinel
   (`NavGridView.java:53`, `:161-171`) where that chunk's nav data is not built, with **no
   live-`getBlockState` fallback** — *"a probe past the loaded radius is reported as unbuilt rather than
   read live"*, because planner-pool workers must never touch live chunks. A scan over an unresident
   region finds no standable sources, proves nothing, and emits nothing: it **fails closed**, the exact
   failure this design exists to remove. Note the two residencies are different datasets — the region
   tier's own "unbuilt" is `grid.fragmentRecord(level, rx, ry, rz) == null`
   (`RegionPathfinder.java:697`), i.e. **HPA residency** — built at chunk-load, persisted to
   `<world>/orebit/<dim>/hpa.<X>.<Z>.bin`, reloaded at `SERVER_STARTED`, and far larger than the
   NavGrid's loaded-chunk radius.
2. **It is a deadlock, not merely a blind spot.** For a far goal whose only route crosses a corner, the
   corner is outside the resident radius, the top-level search FAILs, `HierarchicalRegionPlan` sets
   FAILED, and the bot refuses to move — so it never gets near enough for the corner to become provable.
   **You cannot prove it without being near, and you cannot get near without the route.** §0.2 removes the
   escape hatch that coarse levels might not see the gap: they do, as a spurious fragment split. So
   proof-gating fixes `regioncorner.walkin` — corner at the bot's feet — and essentially nothing else.
3. **Proof availability is inverse to proof value.** The scan volume is `R x R x N` source cells, where
   `N` is the region side in blocks: 16 at L0, 512 at L5. A corner three blocks away planned at L5 has a
   512-block shared edge of which only the resident sliver is scannable. Meanwhile a wrong corner at L0
   next to the bot is discovered in one tick, while a wrong corner at L5 costs a long walk. **Proof is
   available precisely where it is cheapest to be wrong, and absent precisely where it is dearest.**

Two further costs, for the record. The **coupling is large**: `RegionPathfinder` has **zero** block-tier
reads today — no `NavGridView`, no `NavStore`, no `MovementContext`, no movement registry (verified by
grep) — and the scan would thread all of them into that tier, on the planner thread, on a hot path. And it
would be **paid twice per crossing in time**: once at `A`'s expansion to decide whether to emit, again at
the corner node's pop to decide which `D` it exits to, because the node key cannot carry `D`.

**What survives — residency-aware REFUTATION, deferred (R23).** The scan is worthless as a *gate*, not
worthless outright. A sound three-valued form remains available as a later optimisation:

> If the nav grid is resident over the **entire** candidate volume (`NavGridView.built(x,y,z)`,
> `NavGridView.java:145`, answers this per-cell) **and** no enumerated movement lands standably in `D`,
> **refute** the crossing. Otherwise admit it optimistically.

That is sound — complete information over the volume — and matches the codebase's
unbuilt-reads-optimistically contract elsewhere. It is deferred because it is a filter on top of
optimistic emission and its value cannot be measured until corners are in flight. Build it only if
instrumented corner churn (§5.1) justifies it.

---

## §4 Mechanism — optimistic emission, invalidation as the corrective

Emit the corner crossing when the region-tier preconditions hold, **without proving a block-tier
movement**, route it through a virtual chain, and let the existing blame machinery retract it when the
block tier cannot realize it.

### §4.1 Preconditions (all cheap, all from data already in RAM, checked in this order)

1. **The bot has no place capability.** With it, the air neighbour is an ordinary pillar-out route and the
   gap does not exist. This is the **hoisted loop invariant** — a single flag computed once per search,
   not a per-direction test. `placement.canPlace` defaults to `true` (`docs/configuration.md:62`,
   `internal_docs/CONFIG.md:62`), so a default-configured bot pays one branch and nothing else.
2. **The current region has a fragment whose face footprints touch a shared corner** — both face
   footprints' bounding boxes touch the same edge; for the 3-axis case, all three touch the vertex.
   `faceMask` + footprint bbox reads.
3. **No intermediate region offers an adjoining *usable* fragment** — the existing face-portal lookup.
   ("Intermediate" is the two orthogonal neighbours for a 2-axis corner; for a vertex, the three
   face-adjacent and three edge-adjacent regions the chain could route through.)
4. **The diagonal region has a `TYPE_S` fragment whose footprints touch the matching opposite corner.**
5. **Every intermediate is passable AT THE CORNER**: `KIND_AIR`, or `KIND_MIXED` with **some** fragment
   whose footprint covers the corner. (`KIND_SOLID` => no diagonal exists, §2. `KIND_WATER` => precondition
   3 already rejected, §2. **UNBUILT** — `grid.fragmentRecord(...) == null`, which has no kind at all —
   never reaches this test; see note 1b.)
6. **The crossing is not already invalidated.** Probe the level's `RegionEdgeBlacklist` for the
   `(A, fragA) → (D, fragD)` pair. **One structure, not two:** `HierarchicalRegionPlan` already folds
   `RegionCrossingMemory` into each per-level blacklist at plan construction, filtered by caps dominance —
   `if (BotCaps.sigDominates(crossingMemory.sigAt(L, i), capsSig)) blacklists[L].add(fromAt, toAt);` — and
   `repairBlocked` adds newly-proven rows to the same blacklist, so the blacklist is the superset the
   search actually sees. Probing the memory directly from the expansion loop would be redundant **and
   caps-unfiltered**. See §4.6 for why the probe is keyed diagonally and where the key is built.

   **This precondition is load-bearing, not an optimisation.** The corner blacklist key is the DIAGONAL
   pair `A → D`; `relaxFrag`'s own per-hop probe only ever sees the `A → B` and `B → D` halves, and the
   `CORNER` id carries no stable identity across different `(A, D)` pairs through the same `B`
   (`A.1 → B.61` could be the cut from `(1,1,1)` to `(2,1,2)` *or* the cut from `(1,1,1)` to `(0,1,2)`,
   both via `(1,1,2)`). Without an explicit diagonal probe at the emission site, a refuted corner is
   re-emitted forever.

All six are rejections in the common case, which is what matters: the *detection* cost is paid far more
often than the emission.

**Note 1 — preconditions 3 and 5 are not in conflict, though they read like it.** Precondition 3 asks
whether the intermediate offers an *adjoining, gate-passing* fragment (i.e. an ordinary face route
exists — don't bother with a corner). Precondition 5 asks whether *any* fragment covers the corner
(i.e. the cells there are passable). A `KIND_MIXED` intermediate with a **typeless** air-pocket fragment
satisfies 5 and not 3 — and that is exactly the §2 `KIND_MIXED` case the gap lives in. Both survive, and
conflating them produces a contradiction that silently disables the whole mechanism.

**Note 1b — an UNBUILT intermediate is precondition 3's business, not precondition 5's (R34).** A region
with no fragment record has no `kind`, so it satisfies neither arm of precondition 5 and would appear to
fail closed at exactly the frontier §3 says optimism matters most. It does not, because **precondition 3
rejects first**: unbuilt optimism already asserts that all lateral connections exist, and `expandNode`
crosses an unbuilt neighbour as an ordinary uniform-mass edge into its fragment 0
(`uniformTransitCost`'s unbuilt branch, relaxed with `toUnbuilt = rfM == null`). Critically the no-place
air gate cannot suppress it — the guard is
`if (!canPlace && rfM != null && rfM.kind() == KIND_AIR && f != 2) continue;`, and **`rfM != null` means an
unbuilt neighbour is never air-gated**. So an unbuilt intermediate always offers an adjoining usable
route, precondition 3 fires, and no corner is wanted. Preconditions 4 and 5 are therefore only ever
evaluated against regions that HAVE records. (This is also why §3's deadlock is about NavGrid residency
and not HPA residency: the two datasets differ, and the corner mechanism only ever reads the HPA one.)

**Note 2 — `TYPE_S` is fragment-level, not cell-level, and that is the optimism.** A fragment that is
surfaceable *somewhere* may have no footing at the corner. It should hold on `A` as well as `D` — a
typeless `A` fragment would already have been skipped by the no-place gate, but state the requirement
rather than lean on that.

**Note 3 — requiring `TYPE_S` excludes `DiagonalSprintSwim` from the optimistic path.** That is deliberate
and harmless: §2.1 argues a swimmer cannot be stranded by a corner, because its own subset-clearance rule
forces the intermediates to be water, which carry `TYPE_W`, which the no-place gate always passes.

**Note 4 — the corner-touch test is ALREADY quantized above L0, and that is the strongest argument for
optimism.** `PyramidMerger.java:63`: *"footprints are always 16-bucket face-relative regardless of `G`"*.
A footprint bucket is therefore **1 block at L0, 2 at L1, 32 at L5**. At L5, "the footprint touches the
corner" means "the fragment reaches within 32 blocks of it along that face". Demanding a
movement-predicate proof against a test that is itself 32-block-fuzzy is incoherent. The region tier is an
approximation everywhere else; corner crossings should be an approximation too, corrected the same way
everything else is corrected — by invalidation.

**CAVEAT on precondition 1 — the region tier cannot currently see an empty inventory.**
`BotCaps.java:361` computes `effectivePlace = canPlace && !(consumesBlocks && placeableBlocks <= 0)` — the
config key is `placement.consumesBlocks` — but that value feeds the realizability SIGNATURE.
`AllyBotEntity` passes the raw `caps.canPlace()` into the region call, and inventory shapes only the
pillar COST (`RegionPlaceModel.from(inv)`). Making precondition 1 true as written requires threading the
inventory-effective flag into the region tier — a small change, arguably a correctness fix in its own
right, but **a change**.

**Scoping.** The gates that create the gap are `!canPlace && kind == KIND_AIR && f != 2` (uniform
neighbour) and `airGated = !canPlace && f != 2` (per-fragment MIXED). So as the region tier stands, the
whole mechanism is **dormant for a default-configured bot** and fires only where the owner set
`placement.canPlace = false` — which is what `regionCornerPin()` does, and which
`docs/configuration.md:298` documents as a supported configuration. Wire up the inventory-effective flag
and it additionally fires for any bot that runs out of blocks.

Two things argue for building it regardless of how rare it stays:

- It fails **closed** — total refusal, the worst failure mode — so rarity does not bound the damage.
- The rarity is in the *trigger*, not the *cost*: preconditions 1–6 are rejections, so a bot that never
  meets them never pays more than the branch.

**Precondition 3 is an economy gate, not a correctness gate** (accepted limitation). When an ordinary face
route through an intermediate exists but is far more expensive than the corner cut — say it needs a
pillar — the corner is never offered and the search takes the dear route. That is an under-emission, not a
wrong answer, and it keeps detection cost off the common path. The near-prohibitive-pillar case is exactly
what an inventory-aware precondition 1 would surface.

### §4.2 Why this optimism is sound, and why it is not frontier chasing

The obvious objection is the registered `unbuilt-optimism-frontier-chasing` finding: route divergence is
100% unbuilt-region optimism, and *re-planning cannot fix it*. Corner optimism is **different in kind**,
and the difference is what makes it safe:

| | unbuilt-region optimism | corner optimism |
|---|---|---|
| What is unknown | the terrain itself | one movement predicate at a known vertex |
| Evidence available | none — the region has no record | both regions have fragment records; footprints, kinds and types are all known |
| Behaviour on approach | the frontier **recedes** — new unbuilt space appears beyond it, so the guess is never settled | the corner is a **fixed feature**; approach it once and it resolves permanently |
| Existing containment | `UNBUILT_EXCURSION_BOUND` + `MAX_UNBUILT_SHELL_DEPTH = 2` (`RegionPathfinder.java:193`, `:204`) — optimism is admitted but bounded | preconditions 1–6, which are far more selective than "this region has no record" |

A corner crossing is a guess about **known** terrain, over a bounded feature, that settles on first
contact. That is a much better-behaved class of optimism than the one that produced frontier chasing, and
it is why no shell-depth analogue is needed. See §5 for whether a *count* bound is nonetheless wanted.

### §4.3 The encoding — two (or three) ordinary face hops, on a new fragment id

Route `A → D` as `A ->(±X)-> B [->(±Y)-> C] ->(±Z)-> D`, where `B` and `C` are the intermediate regions
and the nodes in `B` and `C` carry a *corner-cut* virtual fragment id. Every hop is an ordinary 6-face
edge, so the node key, the face tables and the persisted format are untouched. §4.4 fixes which
intermediate each hop goes through — and that choice is load-bearing, not cosmetic.

**The corner-cut id needs its own codepoint — it cannot reuse 62 or 63** (owner, 2026-08-24). Expanding
`B ->(±Z)-> D` stamps `D`'s `fromFrag` with **B's fragment id**, so the corner-cut id necessarily appears
in the from-field one hop later, where 62 already means `VIRTUAL_START_FRAG`. A search walking through a
corner cut would read the successor as the search root. §1 develops the general form: both existing
sentinels are one-sided, the corner cut is two-sided, so neither existing vacancy can host it.

So **drop `MAX_FRAGMENTS` from 62 to 61** (`RegionFragments.java:118`) and give the corner cut id 61:

| id | meaning (both fields) |
|---|---|
| `0..60` | real fragments |
| **61** | **corner-cut (virtual)** |
| 62 | `VIRTUAL_START_FRAG` |
| 63 | `VIRTUAL_GOAL_FRAG` |

**Cost of the cap reduction.** One fewer fragment per region before a region collapses to "spongy cheese"
(crossing priced from `passFrac` instead of real connectivity). Owner-assessed as acceptable
(2026-08-24): mostly an L3+ concern, it triggers rarely, and collapse does not break navigation — it
degrades the region tier to "get close, then figure it out" at the block tier. Worth re-checking if the
collapse rate is ever measured.

**The corner-cut node must not become a general waypoint in `B`.** It exists solely to sequence
`A`-fragments to `D`-fragments; treating it as an ordinary node would claim connectivity that does not
exist. Its expansion must be special-cased — skip the generic face loop entirely and emit only corner
exits.

**CALLOUT — the short-circuit goes at the TOP of `expandNode`, not at the face loop (R35).** "Skip the
generic face loop" is NOT sufficient, and getting this wrong is an immediate crash rather than a subtle
bug. `expandNode` computes, BEFORE the loop:

```java
final RegionFragments rfN = grid.fragmentRecord(level, crx, cry, crz);
final boolean uniformN = isUniformNode(rfN);
final int countN = uniformN ? 1 : rfN.fragmentCount();
final int typeA = uniformN ? (…) : rfN.typeBits(fragA);      // ← fragA == CORNER == 61
```

A corner node popped in a **MIXED** region `B` — one with a real record — reaches `rfN.typeBits(61)`, which
indexes past the kept range the moment `MAX_FRAGMENTS` is 61 (`types` is `new byte[MAX_FRAGMENTS]`).
**AIOOBE.** The corner branch must therefore return before `typeA`/`countN` are computed, i.e. immediately
after `crx/cry/crz/fragA` are read. This is the SAME trap §4.5 documents for `snapInFootprint`, and it
hides the same way: a uniform-`AIR` or unbuilt intermediate is caught by the `uniformN` arm and never
touches the array, so the common corner geometry passes in testing and only a MIXED intermediate crashes.
Note also that the intra-region MINE block (`canBreak && !uniformN && countN > 1`) sits between the two
and would emit sibling edges out of a virtual fragment — a second reason the return must precede both.

**CALLOUT — the box/tube containment test names `D`, never an intermediate (R36).** `relaxFrag` opens with
`if (bound != null && !bound.contains(mrx, mry, mrz)) { nodes.outOfBoxRejected = true; return false; }` and
`if (tube != null && !tube.contains(mrx, mry, mrz)) return false;` — applied to the node being relaxed. A
chain relaxes a node in `B`, so a naive implementation tests `B`.

- **`RegionBox` is harmless either way.** A corner's `B` is componentwise between `A` and `D`, so a bbox
  containing both contains it. Exempting it is therefore a no-op for routing — but it also keeps a virtual
  node from ever setting `outOfBoxRejected`, which disqualifies `isSealedWithin`'s closed-flood harvest
  (DESIGN-boxed-in-reachability §2 Leg 2). A node that is not a place should not be able to void a
  structural proof.
- **`RegionTube` is NOT harmless, and the geometry is the inside of a turn.** The tube tests a Chebyshev
  margin against the PARENT skeleton after `rx >> d` (`RegionPathfinder.RegionTube.contains`;
  `TUBE_MARGIN == 2`). For the corner `A = (x,z)`, `B = (x+1,z)`, `D = (x+1,z+1)` the parent coords give
  `B' = (D'.x, A'.z)` — a MIX of the two, not a point between them. On the inside corner of a turning
  skeleton `A'` and `D'` can each sit within the margin of a different skeleton cell while `B'` sits
  within the margin of neither, so a tube-rejected intermediate kills a corner whose real endpoints are
  both admitted.

**Rule: apply both containment tests to `D` only.** The intermediates are quantization artefacts, not
positions, so nothing about them should be able to reject a crossing the tube would otherwise allow.

**How much the key pins, and what it does not.** The key `(B, CORNER, entryFace, fromFrag)` pins the
source **uniquely**: `A` is `B`'s neighbour across `entryFace`, and `fromFrag` names `A`'s fragment. No
ordinary expansion can ever mint the corner id, so the node is unreachable except through a corner cut.

What it does *not* pin is **which corner** of `A` is being cut toward. From `B = (2,1,1)` entered from
`A = (1,1,1)`, both `D = (2,1,2)` and `D = (2,1,0)` are diagonally-opposite `A` and face-adjacent to `B`,
and both mint the identical key. **This is not false connectivity** — a fragment is a connected component,
so both corner columns of `A`'s fragment are genuinely connected to each other. So the corner node offers
**up to N exits, re-derived on pop** (2 for a 2-axis corner; more for a vertex). Deriving them is a
handful of bbox tests from the §4.1 preconditions, so paying it at both `A`'s expansion and the corner
node's pop is cheap and **no memoization is required**.

### §4.3.1 What the chain COSTS (owner ruling 2026-08-28 — supersedes R20)

**Price each hop with the ORDINARY edge formula and zero the virtual traversal term.** `expandNode`
prices a face crossing as

```java
float edge = transitCost(wa - ent, typeA, …)   // traverse ACROSS this region, entry opening → exit opening
           + transitCost(wb - wa, typeB, …);   // the ~1 boundary hop into the neighbour's opening
```

The *traverse* term is the dominant one (up to 16 blocks at L0, 512 at L5); the boundary hop is ~1. Apply
it unchanged to every hop of the chain, with **one rule**: `transit(across X)` returns **0 when `X`'s
fragment id is the corner-cut sentinel**, because a virtual fragment is not occupied space — it exists
only as a quirk of the 6-face quantization, and the bot neither enters nor exits it.

| hop | traverse | boundary | note |
|---|---|---|---|
| `A.1 → B.61` | `transit(across A)` — **real**, entry opening → the corner column | ~1 | the bot really does walk across `A` to reach the corner |
| `B.61 → C.61` | **0** — virtual fragment | ~1 | 3-axis vertex only |
| `C.61 → D.1` | **0** — virtual fragment | ~1 | |

**Consequences, all desirable:**

1. **The majority of the cost sits on the FIRST hop**, the one that really is a walk across `A`. So a
   corner never looks free from `A`'s expansion and never out-competes the ordinary face crossings out of
   the same node. It is only once the search has *committed* to a corner that the remaining chain nodes
   are cheap and pop promptly — which is exactly the ordering you want.
2. **The boundary hop is paid 2× (corner) or 3× (vertex)** where a single diagonal block move costs
   ~1.41–1.73. The chain is therefore very slightly DEARER than the move it stands for, which mildly
   discourages corners. Accepted; it errs in the safe direction for an optimistically-emitted edge.
3. **`relaxFrag`'s `max(edge, WALK_PER_BLOCK)` floor STAYS.** Each hop clears it on the boundary term
   alone, so the chain totals ≥ 2 (corner) or ≥ 3 (vertex) ticks for one Chebyshev region step. That
   satisfies `RegionCostField`'s documented admissibility bound —

   > *"`cheb × MIN_CROSS` — an independent absolute bound: every relaxed edge moves at most one region per
   > axis and costs ≥ `MIN_CROSS` (the `relaxFrag` per-crossing floor)"* (`RegionCostField.java:25-29`,
   > `MIN_CROSS == WALK_PER_BLOCK == 1.0`)

   — which `costAt` returns as `max(floorCost, cheb × MIN_CROSS)` for unsettled queries and which feeds the
   block-tier heuristic. **This is why R20 is retired:** its floor bypass saved ~0.59 ticks against region
   edges that run 16–512, and it put that bound at risk for nothing.

**And one note on the OTHER half of `f`, since this section is all `g` (R42).** `relaxFrag` computes
`hv = HEURISTIC.estimate(mrx, mry, mrz, grx, gry, grz) * hScale` from the region being relaxed — so a
corner-cut node's `h` is measured from **intermediate `B`'s** coordinates, while the node's real position is
the shared edge between `A` and `D`. The error is bounded by one region side and can fall either way
(pulling the search toward a corner or making it pop late). **Accepted as an approximation of the same
magnitude the tier already makes everywhere** — every region node's `h` is measured from a region, not from
the bot's actual cell, and `hScale` is a greedy weight rather than an admissibility guarantee. It costs at
most some re-expansion around a corner; it cannot change which paths exist. Recorded so a future reader does
not mistake the silence for an oversight, and so that if corner pops ever look mis-ordered in a region trace,
this is the first place to look.

**Two details the "return 0 for a virtual fragment" rule does not by itself cover, because a virtual
fragment has neither a type nor a footprint:**

- **`typeB` for a corner-cut destination.** `transitCost`'s second leg is typed by the fragment being
  entered. A corner-cut node has no type bits — price the boundary hop **dry/walk (`typeB = 0`)**. (§4.1's
  precondition 4 already requires `TYPE_S` on `D`, and §2.1 argues the swim family cannot be stranded by a
  corner, so nothing real is lost.)
- **`wa` / `wb` geometry.** The ordinary path takes both from `footprintCenterWorld(...)`, i.e. face
  footprint centroids. A corner cut is not anchored on a face centroid — **anchor both on the corner
  column** (the same cell §4.5 stamps as `D`'s portal). Face centroids here would price a walk to the
  middle of the wrong face.

### §4.4 Which intermediate carries the corner node

**Match the realized-crossing decomposition order — X, then Y, then Z.** That is exactly the order §4.3's
chain writes. This is FORCED, not a preference.

**Why the order is forced.** `BlockPathfinder.collectRealizedCrossings` already staircase-decomposes every
block edge that straddles region boundaries into single-axis region steps, in a fixed **X, then Y, then
Z** order (`while (fx != tx) ... while (fy != ty) ... while (fz != tz)`). Its javadoc calls this out for
diagonals directly: *"Diagonal edges crossing two boundaries at a corner get the same decomposition —
slight over-marking only ever defers blame to a later genuinely-unrealized hop."* Two consequences:

- The realized set **never contains a diagonal pair**, so blame detection cannot be taught to match one.
- `PathPlan.blameHop` tests each skeleton hop with
  `containsEdge(realized, rawRegionKey(sk, i, minY), rawRegionKey(sk, i + 1, minY))`. If the skeleton's
  virtual intermediates are not the same ones `collectRealizedCrossings` synthesizes, the `A → B` hop never
  matches, and **every** BLOCKED later in the window falsely blames the corner hop — blacklisting a
  crossing the block tier actually made. That is a correctness defect, not a cosmetic one.

**The SWEPT-BODY alternative is rejected.** It would route through the region the move's own swept body
occupies (`Diagonal` sweeps both, so free; `Ascend`'s feet cell is in the vertical intermediate =>
up-then-over; `Descend`'s three `requireAir` cells are in the lateral intermediate => over-then-down). It
is appealing because it derives the asymmetry from predicates rather than memory, but it **conflicts with
the decomposition order** — an `Ascend` across an `(+X,+Y)` corner is recorded by
`collectRealizedCrossings` as `A → (rx+1, ry)` then `(rx+1, ry) → D`, through the **lateral**
intermediate, while swept-body would place the corner node in the vertical one.

Its secondary motivation does not survive scrutiny either, though **not for the reason an earlier revision
of this section gave**. That revision claimed "the intermediate is a virtual routing token whose contents
nothing reads." **That is false, and the correction matters** — `WindowTargeting.target` walks every step
of the window far → near and, per step, reads `skeleton.portalCell(i)`, `isUsableTarget(...)`,
`airTargetOk(skeleton, i)` and `snapInFootprint(grid, skeleton, i, ...)`. A corner-cut step is a
first-class skeleton step to that code, and it can be SELECTED: `choice.step` becomes `committedIndex` and
`windowStart` under the forward-slide (`PathPlan.java:1441-1444`) and then `windowTargetStep` →
`blockedTargetStep` → `blameHop`'s `hi`. §4.5's `NO_PORTAL` rule is what actually makes the intermediate
inert, and the swept-body rejection rests on the decomposition-order argument above — which is sufficient
on its own.

Note also that **no** intermediate-selection rule requires the intermediate to carry a fragment — the
typical corner-cut intermediate is `KIND_AIR` with no fragment records at all, and standing in for that
missing fragment is the virtual node's whole job.

### §4.5 What the block tier aims at — a portal CELL, not a face

`RegionPathPlan` stores, per step, `(rx, ry, rz, fragmentId, portalCell, isDig)` — the portal is a stored
world **cell**, stamped by whichever edge won the relaxation (`relaxFrag`'s `px,py,pz`).
`WindowTargeting.target` walks the window far → near and returns `skeleton.portalCell(i)` **raw** as soon
as `isUsableTarget` passes. Face + footprint bbox appear ONLY in the `snapInFootprint` **fallback**, which
runs when the stored portal is unusable (buried in rock, or mid-air with no floor).

**The `D` step stamps its portal with the centre of the intersection of `D`'s corner-touching footprint
bbox with the corner column** — the same class of estimate every other crossing uses — and lets
`isUsableTarget` / `snapInFootprint` do their normal job. Two consequences to accept:

- The target is a footprint-derived estimate, exactly like an ordinary crossing's, and at L0 the footprint
  bucket is a single block (§4.1 note 4), so at the level the block driver consumes it is quite precise.
- `snapInFootprint` will run more often on corner steps than on ordinary ones. That is the designed
  fallback path, and `entranceFace` hands it the real face `D` was entered through — the §4.3 chain makes
  the `B → D` delta single-axis, so `approachEntryFaceForStep`'s dx→dy→dz first-nonzero derivation matches
  the face the search stamped, and parity holds.

**The corner-cut nodes themselves carry `RegionPathPlan.NO_PORTAL` (R32).** They are not places; they have
no occupiable identity to stand in — the same statement `HierarchicalRegionPlan.java:438` already makes
about `VIRTUAL_GOAL_FRAG`. `NO_PORTAL` is an established per-step value (the start step uses it) and
**every DISPLAY-or-TARGET consumer** already guards on `hasPortal(i)`: `WindowTargeting` at both of its
loops (`:125`, `:193`), `SkeletonDump`, `PathDebugRenderer`, and `AllyBotEntity`'s debug line. So a
portal-less corner step is invisible to all of those, with no new machinery.

**But two `HierarchicalRegionPlan` sites do NOT guard — they FALL BACK to the region CENTRE, which is
strictly worse than a portal (R37).** This corrects the "every consumer" claim above, which was too broad:

| site | code | what a corner-cut step does today |
|---|---|---|
| `handDown` (`:966`) | `BlockPos portal = sk.portalCell(far); return portal != null ? portal : sk.centerOf(far);` | the sub-goal handed DOWN to the finer level becomes the **centre of intermediate region `B`** — the pure-air region the corner exists to route around |
| `extendL0` (`:617`) | `from = sk.portalCell(tail); if (from == null) from = sk.centerOf(tail);` | the suffix search is anchored at `B`'s centre, and the join test `suffix.fragmentId(0) != sk.fragmentId(tail)` then compares against `CORNER`, which no real search result can equal ⇒ permanent `degraded` |

`windowFar` is a purely positional index, so a corner-cut step CAN be `far`. Neither site is made inert by
`NO_PORTAL`; `NO_PORTAL` makes them worse.

**Rule — hand down the CORNER COLUMN, by walking the chain to its real end.** Do not skip the step and do
not leave the level without a target: **when the step a hand-down would read is a corner-cut step, advance
FORWARD to the next step carrying a real fragment id and read ITS portal.** That step is `D`, at most two
away, and R15 already stamps `D`'s portal on the centre of `D`'s corner-touching footprint bbox ∩ the
corner column — so this rule delivers exactly the corner-column cell, with **no new storage, no parallel
array, and no weakening of R32** (the step still reports `hasPortal == false` to every consumer in the
list above). `extendL0`'s tail anchor takes the mirror walk (BACKWARD to the last real step) so its
`fragmentId(0)` join test compares real ids.

**Two more step consumers, neither of which reads `hasPortal` — both assessed SAFE, with the argument
written down because the conclusion alone is not checkable (R41).**

1. **The cascade's commit/deviation matcher** (`HierarchicalRegionPlan.onBotMoved`) matches skeleton steps
   by REGION, and is fragment-gated **only at L0** (`final boolean fragGate = L == 0 && sk.isFragmentModel();`).
   - **At L0 it is explicitly safe:** the gate `continue`s on `sk.fragmentId(i) != botFrag`, and
     `botFragmentL0` can only ever return a real kept id, so a corner-cut step is skipped and the scan runs
     on to `D`. `inWindow` still becomes true on the region match, so no false `deviated` fires either.
   - **At L≥ 1 the match is region-only — and it is still safe, but for a GEOMETRIC reason, not a coded
     one.** A diagonal block step goes from a cell in `A` straight to a cell in `D`; the bot's FLOOR cell is
     never inside intermediate `B`. So `brx/bry/brz` never equals `B` and the corner step never matches at
     any level. `committedIndex` therefore walks `A` → `D` past it, and `exhausted = committedIndex >= far`
     behaves normally.
   - **Why this is written out rather than asserted in one line:** the same method carries a named
     *RESIDUAL RE-DERIVE-PROGRESS HAZARD* — *"any trigger that can fire again from the identical state
     therefore livelocks at one re-derive per tick… the invariant to preserve is that exhausted/deviated
     imply real bot progress or displacement, never a matcher artifact."* A step the bot can NEVER occupy is
     precisely the shape that warning is about. The argument above is what says it is not one; if a future
     change ever lets a bot's floor cell resolve into a corner intermediate (a wider body, a swept-body
     re-litigation of X4), re-check this first. Asserted by §6 item 15.

2. **Window reach is counted in STEPS, so a chain shortens it.** `WINDOW_CELLS == 4`; a corner inserts one
   step and a vertex two, and those steps cover no ground. A window that reached four region-cells reaches
   two or three across a vertex. Bounded, rare by §5's own argument, and **accepted** — but note that one
   consumer does more than shorten:
   - **`WindowTargeting`'s free-fall extension scans on `skeleton.ry(i) < skeleton.ry(i - 1)`** and stops
     the instant the skeleton stops dropping. A corner-cut step in that run has a real `ry`, so a 2-axis
     (X+Z) corner holds `ry` FLAT and **terminates the scan early** — the extension gives up before reaching
     the landing it exists to find. **Rule: the free-fall scan skips corner-cut steps for the monotonicity
     test as well as for `hasPortal`,** comparing each real step against the previous REAL step.

**This rests on an atomicity invariant that must be ENFORCED, not assumed (R37a, generalized by R40 in
§4.5.1):** *a skeleton never
begins or ends on a corner-cut step, and a corner chain is never split across a splice boundary.* The
forward walk needs a real step to find. `RegionPathPlan`'s prefix/suffix concatenation (`:232-243`) and
every window truncation are the places that could violate it. Assert it (§6 item 12).

Three things this buys, and one non-issue it clears up:

1. **The window can never commit to a corner node.** Without it, `WindowTargeting` could return a corner
   step's index and `PathPlan`'s forward-slide would set `committedIndex = windowStart = <corner step>`
   (§4.4) — parking the bot's window on a node it can never occupy.
2. **`snapInFootprint` can never index a virtual fragment.** Both call sites sit behind
   `if (!skeleton.hasPortal(i)) continue;`. This matters concretely: that method guards
   `rf.footprint(fragmentId, face)` against `isVirtualGoal` **only** —

   ```java
   final int packed = (rf != null && !rf.isUniform()
           && !RegionPathfinder.isVirtualGoal(skeleton.fragmentId(step)))
           ? rf.footprint(skeleton.fragmentId(step), face) : RegionFragments.NO_FACE;
   ```

   — so a corner step over a **MIXED** intermediate with a real record would fall straight through and
   evaluate `rf.footprint(61, face)`, past the kept range once `MAX_FRAGMENTS` is 61. A uniform-`AIR` or
   `null`-record intermediate is caught by the `rf != null && !rf.isUniform()` arms, which is exactly why
   this is easy to miss in testing. **The `hasPortal` skip is the only thing keeping the corner id away
   from that expression** — if a future change gives corner nodes a portal, add the guard there.
3. **The cost model never reads a corner node's entry portal.** `expandNode` opens with
   `if (entX == NO_PORTAL) { entX = startWx; … }`, which would anchor a traverse at the search start — but
   §4.3.1 zeroes the traverse term for a virtual fragment, so `entX` is unread for exactly these nodes.
   The two rulings compose; neither works as cleanly alone.

**Residency is a non-issue here, and was never the concern.** `isUsableTarget` short-circuits an unbuilt
cell to *usable* (`if (!grid.built(x,y,z)) return true;`) and `snapInFootprint`'s scan skips unbuilt cells
and returns `null`, so a distant or non-resident portal is handed to the block tier **raw** and never
scanned — the same optimistic-frontier contract as §4.2. And the far → near walk covers the WINDOW
(`[windowStart, windowLast]`, a few steps at the bot), not the whole skeleton.

### §4.5.1 No boundary ever lands on a virtual fragment (R40, owner ruling 2026-08-28)

**One rule, applied everywhere, replacing the ad-hoc phrasing R37a started with:** *no index that names a
skeleton POSITION may name a corner-cut step. Every producer of such an index walks past the run — FORWARD
for a start / target / hand-down, BACKWARD for an end / anchor — exactly as `blockedHop`'s collapse already
does (§4.6).* Splitting a skeleton on a cell the bot can never occupy is confusing at every downstream
reader, and the walk is two lines wherever it appears.

**The site census. Most are already safe; the rule is what makes that a guarantee rather than a
coincidence.**

| boundary | producer | direction | status |
|---|---|---|---|
| `splice`'s `drop` (the head cut — `old[drop]` becomes the new index 0) | `extendL0`: `Math.min(lp.committedIndex, Math.max(0, driverWindowStart))` | forward | **safe by induction**, and now asserted — see below |
| `splice`'s JOIN (`old`'s tail ≡ `suffix[0]`) | `extendL0` | backward | safe (a chain always terminates at `D`; the search ends at the goal fragment or V) — assert |
| `handDown`'s `far` | `windowFar(L, lp)`, positional | forward | **NOT safe** — this is R37 |
| `extendL0`'s `tail` anchor | `sk.size() - 1` | backward | R37's mirror walk |
| `blameHop` / `blockedHop` | the blame walk | both | already specified (§4.6) |
| `WindowTargeting.target`'s returned step | `choice.step` | — | safe: R32's `NO_PORTAL` means a corner step is `continue`d at both loops |
| `PathPlan`'s forward-slide `committedIndex` / `windowStart` | `choice.step` | — | safe: inherits the above |
| the cascade's `lp.committedIndex` | `onBotMoved`'s advance loop | — | safe — see R41 |

**Why `drop` is safe by induction, and why it must still be asserted.** `drop` is the `min` of two indices,
so it IS one of them: `committedIndex` comes from the L0 advance loop, which is fragment-gated and
`continue`s past any step whose id is not the bot's resolved fragment (so never a corner id); and
`driverWindowStart` comes from `choice.step`, which R32 keeps off corner steps. `Math.max(0, …)` can force
index 0, which is real only because this rule forbids a skeleton beginning on a corner step. The induction
is real but it threads four separate mechanisms — exactly the kind of chain that a later edit to any one of
them breaks silently.

**So `RegionPathPlan.splice` carries the check.** It already throws `IllegalArgumentException` on a
`drop` out of range; add the same treatment for `old.fragmentId(drop)` or `old.fragmentId(old.size()-1)`
naming the corner id. A boundary landing on a virtual fragment is a caller bug, and it should fail at the
boundary rather than surface three layers away as a finer level aiming at open air.

**And note `snapshotOptimism(0, lp)` after the splice** — *"the splice RENUMBERS every step — re-cut, never
shift, the unbuilt marks"*. Corner-cut steps renumber with everything else; nothing about them is index-
stable across a splice, which is a second reason no durable structure may key one (§4.6, R28).

---

### §4.6 Invalidation machinery — the load-bearing half

**Invalidation is the only corrective for a wrongly-emitted corner**, so this section carries as much
weight as the emission rule.

**The key-space asymmetry is what makes diagonal invalidation possible.** The search node has to pack
region + fragment + entry-face + from-fragment into ONE 64-bit word, which is why the from-region is
compressed to the 3-bit `entryFace` (§1). The invalidation structures are under no such pressure: they
keep the two endpoints in **separate parallel `long[]`s**, each holding a full
`RegionPathfinder.fragmentNodeKey`.

- `RegionEdgeBlacklist` — per-navigation, in RAM, held on the bot, cleared when the goal changes.
  `private long[] from` / `private long[] to`, `add(long, long)` / `contains(long, long)`, plain linear
  scan (the set is a handful of edges per stuck episode; the scan keeps the per-expansion probe
  allocation-free).
- `RegionCrossingMemory` — created with the `RegionGrid`, persisted, survives every
  `HierarchicalRegionPlan` boundary. `long[][] from` / `long[][] to` / `long[][] sig` plus `byte[][] prov`,
  indexed per LEVEL `0..RegionAddress.MAX_COARSE_LEVEL`. Rows are tagged with the failing
  `BotCaps.realizabilitySig` and kept as a dominance ANTICHAIN by `record(...)`, so an invalidation is
  automatically capability-scoped — a stronger bot ignores a weaker bot's negative. Provenance is
  `PROV_PROOF` / `PROV_ESCALATION` (session-only, filtered at encode) / `PROV_ROLLED_UP`
  (`RegionCrossingMemory.java:55`, `:60`, `:65`). Persisted through `CostPyramidCodec`, rows bucketed by
  their FROM region's shard.

`RegionCrossingMemory.REGION_MASK = (1L << 49) - 1` (`:70`) names the split: each endpoint carries **49
bits of region + 6 bits of fragment**, independently — against the 3 bits the node key can spare. A
diagonal `A → D` pair is natively expressible; nothing in either structure requires face adjacency.

**A corner crossing is an ORDINARY crossing** — its `to` endpoint is a real fragment in a real region, not
V — so it takes `relaxFrag`'s physical-pair path and needs no change to the entry-conditioning machinery.
(The entry-conditioned rule exists on exactly one path: the approach into the virtual goal V, where
`PathPlan.blockedHop` uses `approachRowKeyForStep` and the search mirrors it in `relaxVirtualGoal`.
Verified.)

**The collapse: the skeleton says `A.1 → B.61 → C.61 → D.1`, the invalidation must say `A.1 → D.1`.**

Two sides have to agree, and both are small:

1. **Add side — `PathPlan.blockedHop(long[] out)`.** The blamed hop index comes from `blamedHopIndex()` →
   `blameHop(...)`, and it lands where you would expect: on a chain `A.1 → B.61 → C.61 → D.1` the first
   unrealized hop LOOKS like `A.1 → B.61`. Before filling `out`, walk the FROM endpoint **backward** and
   the TO endpoint **forward** past any run of corner-cut steps, then emit `(A, fragA) → (D, fragD)`:

   > blamed hop is `A.1 → B.61`. TO is `61`? that's a corner-cut — look at the next step. `C.61`? still a
   > corner-cut — next. `D.1` — a real fragment. **Emit `A.1 → D.1`.**

   Symmetrically if the blame lands on `B.61 → C.61` or `C.61 → D.1`, the FROM walk runs backward to
   `A.1`. This is a pure key-construction change; the blame *decision* is untouched, and it stays correct
   only because §4.4 pins the intermediates to the decomposition order.

   **Two implementation details that are easy to get wrong:**

   - **The FROM walk must be allowed to run backward past `windowStart`.** `blameHop`'s walk is bounded
     below by `lo` (≥ `windowStart`), but the collapse is key CONSTRUCTION, not blame selection — `A` may
     sit before the window start, and the skeleton is not truncated, so the backward walk simply indexes
     earlier steps. Do not clamp it to `lo`.
   - **`blameHop`'s `lo` anchor loop must skip corner-cut steps**, exactly as it already skips
     `isVirtualGoal`. That loop finds the LAST window step whose region equals the search-start region;
     since a corner node sits in a real region `B`, an anchor could otherwise land on a virtual step. The
     existing comment gives the reason verbatim — a virtual step "is NOT a physical bot position".
2. **Check side — the corner exit emission.** It must probe the identical key. `A` is recoverable at the
   corner node's pop by the §1 inference: `RegionAddress.neighborRX/RY/RZ(..., entryFace)` gives `A`'s
   region and `nodes.fromFrag[curRow]` gives `fragA`. So the emitter builds
   `blacklist.contains(fragmentKey(A...), fragmentKey(D...))` itself rather than relying on `relaxFrag`'s
   per-hop probe, which would only ever see the `A → B` and `B → D` halves. This is §4.1's precondition 6.

**Why the diagonal keying is essential rather than a nicety.** Left alone, the `B → D` half keys as
`(B, CORNER) → (D, fragD)`, which is shared by **every** `A` cutting a corner through `B` into `D`. Kill
one and you kill the others. Since refutation is the normal lifecycle of a wrong corner, that coarseness
would be doing real damage, not sitting idle.

**A BLOCKED result is positive proof of unsafe traversal — not a false negative** (owner ruling,
2026-08-28). The objection is that BLOCKED can arise from node/time budget exhaustion, a
`NavGridUpdater.editEpoch` bump, a moved window target, or `IRREVERSIBLE_GUARD` truncation rather than from
genuine impossibility, and that persisting such a row as `PROV_PROOF` durably kills a real crossing.
**Rejected.** A corner the block tier could not route through inside its budget, or could only route
through past an irreversible move, is one the bot cannot traverse *safely* — the uncertainty is itself the
finding, and recording it is correct. Corner refutations therefore record as ordinary `PROV_PROOF` rows at
the `onBlocked` site with no special provenance, no failure counting, and no decay. **This is what makes
optimistic emission affordable: the corrective needs no new machinery.**

**Roll-up is already safe, by accident of an existing guard.** `InvalidationRollup` derives a crossing's
parent face from the child-cell delta via `faceOf(dx, dy, dz)`, which returns `-1` for anything not
face-adjacent, and the fold bails — the code anticipates this for the intra-region fragment→fragment mine
crossing (delta 0) and says so. So a diagonal row **never rolls up** and can never cause a false parent
kill. `evictLeafTouching` masks `REGION_MASK` off both endpoints and works on diagonal rows unchanged.

**The cost of that safety** is that an L0 corner refuted by the block tier does not invalidate the
corresponding corner at L1..L6 by blame, so a coarse skeleton could keep re-emitting it. §4.10's re-merge
is the answer — coarse levels are corrected structurally rather than by propagated blame. Confirm no path
survives that (I3).

### §4.6a `startScoped` — when a refutation is journey-scoped, and why that is NOT a corner problem

**Assessed 2026-08-28 rev 3, owner-corrected. Recorded because a reviewer WILL rediscover this and file it
as a blocker; it is not one.**

§4.6 says corner refutations "record as ordinary `PROV_PROOF` rows at the `onBlocked` site". That is true
except in one case, which corners share with every other crossing.

`HierarchicalRegionPlan.onBlocked` gates the DURABLE record on the blamed row's FROM **region**:

```java
final boolean startScoped = searchStartFloor != null
        && RegionAddress.unpackRX(l0FromKey) == RegionAddress.regionX(searchStartFloor.getX(), 0)
        && …RY… && …RZ…;
if (recordToMemory && !startScoped && !virtualGoalHop) {
    crossingMemory.record(0, l0FromKey, l0ToKey, capsSig, PROV_PROOF, BotCaps::sigDominates);
    InvalidationRollup.foldFrom(…);
}
```

`searchStartFloor` is `PathPlan.blockedStartFloor` — the from-floor of the BLOCK search that failed
(`== botFloor` sync, one snapshot older async). The per-plan `blacklists[0].add` above it always fires;
only the durable row and the roll-up fold are withheld.

**Why the rule exists.** Invalidation rows for ordinary crossings are keyed PHYSICALLY — `relaxFrag`:
*"a dead crossing is unrealizable regardless of how the FROM region was entered… the online-repair probe
uses the plain physical key, NOT the entry-augmented search key."* Entry-conditioning exists in exactly two
other places: every search NODE's identity (`searchKey` folds `entryFace` + `fromFrag` — the two-hallways /
cliff fix, NOTES-region-tier.md §1.1) and the approach-into-V row alone. So the ordinary row cannot say
*"… if we arrived via `C.3`"*, and a fragment is the CAPS-BLIND 6-connected flood of passable cells
(`FragmentBuilder`): one fragment can span caps-DISCONNECTED masses — NOTES §1.1's two hallways joined by a
1×1 gap, or a cliff's top and bottom in one air pocket. A search that started INSIDE `A` proves only
*"the mass I am standing in cannot exit toward `D`"*, and nothing in the key says which mass. `startScoped`
is the blunt substitute for the conditioning the row cannot carry.

**It is a heuristic, not a proof.** A non-start hop's entry OPENING is not in the physical key either, so
two predecessors entering `fragA` at two openings in two disconnected masses have the same ambiguity. The
rule is stricter where it is cheapest to be strict. Do not read it as sound and do not "fix" a corner
around it.

**Three things a reviewer gets wrong here — all three were gotten wrong once (R39):**

1. **It does not bite `regioncorner.walkin`.** The pin's corner IS realizable at the block tier — that is
   the whole point of the card. A realizable corner never goes BLOCKED, never reaches `blameHop`, and never
   reaches the record decision at all.
2. **Near corners are not the common case.** `WINDOW_CELLS == 4`, and the block search starts at the bot.
   A corner is first blamed when the window target moves past it, which is typically 1–4 region-cells
   before the bot stands in `A`. `A != ` start region is the ORDINARY case; "bot standing in `A`" is the
   corner case of the corner case.
3. **It is not corner-specific.** *"You started at the crossing AND the crossing was invalid"* withholds the
   durable row for a plain FACE crossing identically. Every optimistically-emitted crossing has this
   property. The corner design INHERITS it; it does not introduce it, and nothing here needs new machinery.

**The one genuinely corner-specific residue — and it is a QUALIFICATION on R30, not a defect.** For a plain
crossing a journey-scoped refutation costs one wasted re-approach per goal. For a corner it ALSO leaves the
L1+ parent fused, because R30's un-merge consumes exactly the durable row that was withheld. So §4.10's
"the merge consults that same surviving L0 row" holds for corners refuted from OUTSIDE their own start
region, and for the rest the system degrades to the ordinary HPA relationship: L1 stays optimistic, L0
re-refutes per navigation, the bot still routes. Not a livelock, and strictly better than today's total
refusal — but R27a should not be read as universal self-healing.

**Instrument it rather than arguing it** — §5.1 splits the refutation counter NEAR (`A ==` start region,
journey-scoped) vs FAR (durable). If NEAR turns out to dominate in the field, revisit; the fix would be a
session-scoped second trigger for R30, or component-aware fragment keys, which is the general fix that
would retire `startScoped` entirely and is out of scope here.

**Diagonal keying also keeps the rows persistable.** A collapsed `(A, fragA) → (D, fragD)` row names **no
virtual id**, so it is durable world knowledge and survives `CostPyramidCodec` encode/decode with nothing
to filter.

Only one row shape can still name `CORNER` after the collapse, and it is **not** the shape an earlier
revision described. A corner node can never itself be a goal APPROACH: the approach lookup is
`digSeeds.indexOf(fragmentKey(crx, cry, crz, fragA))`, keyed on the physical `(region, fragment)` pair,
and the seed list only ever holds real fragments — so `relaxVirtualGoal` never fires from a corner node.
The real case is the mirror image: **`D` is the approach**, its predecessor on the skeleton is a corner-cut
step, and `approachRowKeyForStep` stamps the row's `fromFrag` with the corner id (search-side parity holds,
because `D`'s live row carries `fromFrag == CORNER` too).

**Those rows are DROPPED at encode** (R28), and the drop is **automatic**: `CostPyramidCodec` already
filters invalidation rows on the **TO** fragment id (`if ((int)((toStored >>> INVAL_FRAG_SHIFT) &
INVAL_FRAG_MASK) >= RegionFragments.MAX_FRAGMENTS) …`, `:828`), and the TO on that row is
`VIRTUAL_GOAL_FRAG` = 63. No new codec rule is needed — only the R21 checklist note that the same
`>= MAX_FRAGMENTS` test now also catches the corner id 61.

### §4.7 REJECTED ALTERNATIVE — the direct corner edge

**Rejected 2026-08-27. Do not re-litigate; the reason is structural, not a preference.**

The proposal was to skip the intermediate entirely and emit ONE edge from `(A, fragA)` straight into
`(D, fragD)`, stamping `entryFace` with whatever `RegionPathfinder.approachEntryFaceForStep` /
`WindowTargeting.entranceFace` would derive from the diagonal region delta (both test `dx`, then `dy`,
then `dz`, and return the first nonzero — so a `(+1,0,+1)` corner yields face `0`, `D`'s −X face, from
both). It looked attractive: no third sentinel, no `MAX_FRAGMENTS` reduction, no codec guard, no virtual
ids in blame keys, 2-axis and 3-axis handled identically.

**Why it cannot work: it destroys the from-region inference (§1).** A corner edge into `D` stamps face
`0`. An ordinary face edge into `D` from its −X neighbour `N` stamps face `0` as well. When
`fragA == fragN` those are the **same node row**, and the key can no longer answer "which region did I
come from" — the node degenerates to *"fragment F in a region we cannot discern"*, which for a pure corner
could be any of the 26 neighbours.

It is not a theoretical collision. `fromFrag == 0` is the overwhelmingly common value: every uniform,
collapsed, and unbuilt neighbour relaxes into fragment `0`, and plenty of MIXED regions keep exactly one
fragment. The collision is the expected case.

**And it cannot be repaired.** The fix would be a fourth `entryFace` codepoint meaning "corner — do not
infer my source from this face". There is none: 0–5 are faces, 6 is `ENTRY_START`, 7 is `ENTRY_INTERIOR`.
Reusing `ENTRY_INTERIOR` only relocates the ambiguity (intra-region mine vs. corner crossing — different
from-regions, same codepoint).

The failure is **silent**, which is the worst part. Nothing crashes and parity does not drift:
`approachRowKeyForStep` derives face `0` from the diagonal delta, matching what the search stamped. The
key is simply the ambiguous one, so a blacklist entry meant for the corner cut kills a realizable face
route into the same fragment, or vice versa.

**Why the sentinel is not bookkeeping.** A virtual from-fragment is the ONLY way to say *"my predecessor
is not the region you would infer from my face."* The 3-bit face field cannot make that statement; the
6-bit fragment field can, because a value no real fragment can hold carries the exception. Both two-hop
keys stay unambiguous:

- `(B, CORNER, entryFace, fragA)` — `entryFace` pins `A`'s region, `fragA` pins its fragment, and no
  ordinary expansion can ever mint `CORNER`.
- `(D, fragD, oppFace, CORNER)` — "entered from the corner cut in the region across `oppFace`", which no
  face route can collide with.

### §4.8 The reverse cost field

`costToGoalField` is the goal-rooted reverse Dijkstra that builds `RegionCostField`, running the **same**
`expandNode` with `dijkstra = true`. Two consumers care: the block A\* heuristic takes a region-refined
term from it, and `RegionPathfinder.isSealedWithin` runs a *closed flood* of it as the boxed-in proof that
`PathPlan` reads as a give-up. Both ways of getting this wrong are bad:

- **Corner cuts absent from the field** — the field cannot cross the corner either. The heuristic
  over-estimates (inadmissible), and the goal-side flood never reaches the bot; if that flood also closes
  inside its box we declare SEALED and refuse. **The same fail-closed refusal through a different door.**
- **A corner cut modelled as a chain in the field** — `relaxFrag` deliberately forces
  `cfrom = VIRTUAL_START_FRAG` when `dijkstra`, so the field is not split by predecessor. That erases the
  corner node's "which `A` did I come from" identity, so two different cuts through the same `B` collapse
  into one row.

**Resolution: corner cuts ARE present, as a direct `D → A` relax with no intermediate node** (R29). The
field only needs costs and never needs a face-shaped skeleton, so it does not need the chain — and with no
corner node there is **no `cfrom` identity to erase**, so the second failure disappears entirely. The
emission test is the §4.1 bbox reads, which the field can afford.

**Pricing: the `max(edge, WALK_PER_BLOCK)` floor STAYS — R20's bypass is RETIRED** (2026-08-28,
superseded by §4.3.1). An earlier revision wanted the floor bypassed on both sides so a corner cost ~1.41
rather than the ≥ 2 (corner) / ≥ 3 (vertex) the floor imposes on a chain. §4.3.1 accepts that 2×/3× as
*correct and mildly discouraging*, which removes the motive; and here, inside a Dijkstra that also feeds
`isSealedWithin`'s closed flood, the floor is precisely the property you least want to weaken.

**What the direct `D → A` relax must charge, so the two sides agree.** The field is a heuristic term for
the forward search, so its corner price should have the same SHAPE as the forward chain's total:
`transit(across the source fragment)` + one boundary hop per chain leg (2 for a corner, 3 for a vertex),
each floored exactly as `relaxFrag` floors an ordinary edge. Two constraints on the result:

- **Never below `MIN_CROSS` per Chebyshev region step** — automatic once the floor is kept, and required
  by `RegionCostField`'s `max(floorCost, cheb × MIN_CROSS)` bound (§4.3.1).
- **Never wildly below the forward chain**, or the heuristic over-optimistically pulls the forward search
  toward corners it will then have to pay full price for. Matching the shape is enough; exact equality is
  not required and is not achieved for ordinary crossings either.

### §4.9 The intra-region form — recognise corner cuts at MERGE time, don't model them

**Owner ruling, 2026-08-28.**

The reflex is that intra-region connectivity does not matter — a region is a messy blob of unclear space,
fragments are just bounding boxes of connectivity on the faces, and you trust yourself to teleport face to
face. That reflex is wrong here for exactly one reason: **the HPA\* graph keys FRAGMENT residency, not
region residency.** A node does not say "you can move from region A to region B"; it says "from A's
fragment 1 to B's fragment 3". Under that definition A's fragment 1 and A's fragment 2 are separate nodes
with separate connectivity, and a corner cut between them is real connectivity the graph is missing.

But a corner cut *inside* one region needs no crossing, no edge, and no virtual node. **"Connects them"
really means "merges them"** — if a corner cut exists between two masses, they are one fragment. So the
intra-region form is not something to model in the search graph; it is something to **recognise in the
union step that builds fragments**, emitting one fragment where there are today two.

**Two sites carry the 6-face assumption. Both verified 2026-08-28:**

| site | what it unions | today's adjacency test |
|---|---|---|
| `FragmentBuilder` (L0 leaf) | passable CELLS within one leaf region | **6-connected BFS** — *"flood-fill the 6-connected components of a region's passable cells"* (`FragmentBuilder.java:7`); stepping is `±1` (X), `±G` (Z), `±G²` (Y) (`:21`) |
| `PyramidMerger.mergeUpFragments` (L1+) | child ITEMS within one parent | `xor != 1 && xor != 2 && xor != 4` over the child SLOT indices (`PyramidMerger.java:398`) — corner-adjacent children differ on ≥ 2 slot bits and are skipped |

The **L0 site needs no boundary alignment whatsoever** — a diagonal doorway wholly inside one 16³ leaf
splits that leaf's fragment today — so it is very likely the more common of the two. The L1+ site is
§0.2's parent split.

**This is NOT "switch the flood to 26-connected."** A naive widening squeezes the bot through a solid
corner, which `Diagonal.candidates` explicitly refuses (§2). A corner union has to carry the same class of
evidence its face counterpart already carries:

- **L0** — admit a diagonal cell step only when **both orthogonal cells are passable**. That is
  `Diagonal`'s own rule and the mask is already in hand. It stays optimistic with respect to headroom
  (per-cell body clearance the flood mask does not carry), which is consistent with R8.
- **L1+** — the existing union already requires face-footprint `overlap(...)` before it unions two
  children; a corner union needs the analogous test that the two children's footprints actually meet at
  the shared edge or vertex, not merely that their slot indices are diagonal.

**BOTH sites additionally require `TYPE_S` on BOTH masses, and the rule must be STATED HERE (R38).** R27a's
safety argument — "the merge can only ever fuse two `TYPE_S` fragments, so it never launders a typeless air
pocket into a surfaceable mass" — was written as if it followed from §4.1's precondition 4. **It does not.**
§4.1 is a search-time gate on the cross-region chain and has no authority over `FragmentBuilder` or
`PyramidMerger`; as first written, the merge rule above carried no type test at all, so R27a's
capability-safety case rested on nothing. Make it explicit:

> A corner union is admitted only when **both** masses carry `TYPE_S`.

The data is already in hand at the union site — `PyramidMerger` holds `itemType[k]` per item and ORs it
into `compType[comp]` immediately after the union-find, so the test is one mask read inside the existing
`for (a) for (b)` loop; at L0 the analogous per-component type is what `FragmentBuilder`'s own type pass
computes. Without it a corner union can fuse a typeless pure-air pocket to a standable mass and hand the
`airGated && typeB == 0` gate a laundered fragment — bypassing at BUILD time the exact gate this whole
document exists to work around.

**And note the evidence asymmetry between the two sites, which runs opposite to the ship order.** The L0
rule carries a REAL predicate (`Diagonal`'s own both-orthogonal-cells-passable test, over a mask holding
every cell). The L1+ rule cannot: face footprints are per-face 2D extents, so a child can have a `+X`
footprint reaching its `+Z` extreme AND a `+Z` footprint reaching its `+X` extreme **with no cell at the
corner column at all**, and there is no orthogonal-sibling passability analogue expressible in that data.
So the site shipping FIRST (L1+, per R27a) rests on the WEAKER evidence, admitted only because it is
self-healing, while the stronger-evidence site (L0) is the one gated on I4. That inversion is deliberate —
it is a risk-profile ordering, not an evidence ordering — but it should be read as such.

**Three consequences to carry into implementation:**

1. **A merge cannot be retracted, and that is a different risk profile from R8.** An optimistic *crossing*
   is keyed `(A, fragA) → (D, fragD)` and the blame machinery can kill it (§4.6). Two masses merged into
   one fragment leave **no crossing to blame** — the optimism is baked into a persisted fragment record.
   §4.10 is how a wrong merge gets corrected.
2. **It does not conflict with R18** ("search time, not build time"). R18 rejected pre-recording diagonal
   crossings because they cost region-generation time and extra bits on disk. A merge costs neither: no
   format change, no new field, and *fewer* fragments in the record — which also buys back a little of
   what R7's `MAX_FRAGMENTS` 62 → 61 reduction spends.
3. **It does not replace §4.3.** Two corner-adjacent regions at one level are usually children of a single
   parent, so the merge unions them **at the parent level**; it does not hand the child level's search
   graph an edge. A same-level skeleton still rides the cross-region chain.

**The two sites have DIFFERENT risk profiles and must be scoped separately** (2026-08-28, after review).
They were treated as one mechanism above; they are not.

**The L1+ `PyramidMerger` site is SELF-HEALING and is in scope.** A wrong parent merge produces false
connectivity at L1. But that false connectivity is *predicated on the corner crossing existing*, so when
an L1 skeleton routes through the fused fragment, **L0 finds the only realization is the corner cut** —
and when the block tier cannot walk it, §4.6 invalidates `(A, fragA) → (D, fragD)`, which is precisely the
row §4.10's re-merge consults. The parent un-fuses and L1 is repaired. Owner assessment: the cost is one
wasted walk to the corner plus the evicted L1+ rows (R30), against the status quo of an 11-waypoint path
the bot **physically cannot walk**. That trade is clearly worth taking.

Note also that the merge can only ever fuse two `TYPE_S` fragments — §4.1's precondition 4 requires
`TYPE_S` on `D` and note 2 states it for `A` — so it never launders a typeless air pocket into a
surfaceable mass, and the `airGated && typeB == 0` gate that creates this whole problem is not bypassed.
Capability-independence is likewise fine: a place-capable bot can realize the corner by building, so the
shared record is not claiming something only a no-place bot could use.

**The L0 `FragmentBuilder` site is NOT self-healing and is GATED ON I4.** A diagonal doorway wholly inside
one 16³ leaf merges two masses into **one fragment, with no crossing anywhere in the graph**. The skeleton
hop is "into fragment `F` via face X" and "out of `F` via face Y" — both real, realizable crossings. When
the block tier cannot get from X to Y through the doorway, `blameHop` blames one of *those*, and the bot
blacklists a working crossing. That is X1's silent-wrong-blame failure mode, and R30 has nothing to hang
off because there is no corner edge to invalidate. **Ship the L1+ site first; the L0 site waits on I4.**

### §4.10 Correcting a wrong merge — re-merge on invalidation

**Owner design, 2026-08-28.** When a corner crossing is invalidated, **re-run the merge upward**: the
corner that justified unioning two children is gone, so the parent's fragment splits, and the split
propagates L1 → L6. This is also what corrects §4.6's roll-up gap — coarse levels are fixed
**structurally**, by rebuilding their fragments from children that no longer union, so the fact that
diagonal rows never roll up as *blame* stops mattering.

**Why it settles instead of oscillating — the level discipline is the whole argument.** The obvious
objection is that the split renumbers fragments, which breaks the very invalidation row that would stop
the corner being re-emitted, so it re-merges and loops. It does not, because **the row that gates
re-emission and the ids that shift live at different levels**:

- `RegionCrossingMemory` is indexed **per level** — `record(int level, …)`, `from[level][i]` /
  `to[level][i]` (`RegionCrossingMemory.java:72-73`, `:108`) — so an L0 row names L0 fragment ids.
- A **cross-region** L0 corner does not touch either endpoint's own fragment record: `FragmentBuilder`
  computes `A`'s fragments from `A`'s own cells, and refuting `A → D` changes none of them. **L0 ids do
  not shift.**
- So §4.1's precondition 6 still resolves, still gates L0 re-emission, and the merge consults that same
  surviving L0 row when deciding whether to union corner-adjacent children. The refutation is durable at
  the level that produced it.

**CONDITIONAL on the row actually being durable (R39, §4.6a).** `onBlocked` withholds the
`RegionCrossingMemory` record when the blamed hop's FROM region is the failing block search's own start
region — a pre-existing property of every optimistic crossing, not a corner one. When that fires, the
per-plan `blacklists[0]` row still gates re-emission within the navigation but there is no durable row for
the merge to consult, so the parent stays fused: L1 keeps offering the corner and L0 keeps re-refuting it,
once per goal. That is the ordinary HPA optimism relationship rather than the structural correction this
section promises — degraded, not broken, and still strictly better than the §0 total refusal. **Read R27a's
self-healing claim as holding for corners refuted at distance, not universally.**

**What IS lost: every L1+ invalidation row for the affected regions** — not merely the corner ones. Those
rows name coarse fragment ids, the split renumbers them (`FragmentBuilder` assigns ids from a sequential
flood-order `kept` counter, `:188`/`:269`, so splitting fragment *i* renumbers *i+1..n*), and a row whose
key no longer resolves cannot be trusted to mean what it meant.

**Owner ruling: evict them and re-discover.** Note precisely what that costs, because it is easy to
overstate in either direction. It is **not** a correctness-preserving cleanup: connectivity only ever
**decreases** on an un-merge, and losing an edge never makes something passable, so the evicted negatives
were still **true statements about the world** — what went stale is their *keys*, not the facts. It is
therefore a **lossy re-derivation chosen for simplicity**: re-discovery re-earns those negatives the same
way it earned them the first time, and the loss can never admit something known-false, only make the
search re-learn it.

**The eviction machinery already exists and already fires on this event shape.**
`RegionCrossingMemory.evictLeafTouching(rx, ry, rz, sink)` (`:170`) is called today from
`HpaMaintenance.java:470` on the block-change flush, and it already performs containing-coarse eviction
when any leaf beneath a coarse cell changes (`:64`). A re-merge is the same event: a leaf's fragments
changed.

**On how often this runs.** Invalidations in general are rare — they require having seen two regions as
connected and then finding they are not, which comes down to flooded plains, coarse approximations meeting
a pathological reality, and (usually) no-place bots. R24's "refutation is the normal lifecycle of a wrong
corner" is about the per-corner outcome, not the absolute rate: §5 argues emission itself is very rare, so
refutations stay rare in absolute terms. Both hold.

**Two residuals.** The intra-region L0 site has no crossing to invalidate, so this mechanism has nothing
to hang off there (I4); and a re-merge mutates fragment records that planner threads read concurrently
(I5).

---

## §5 Expected emission rate, and why it is low

Owner assessment, 2026-08-28. Assume a no-place bot, so precondition 1 passes and the mechanism is live.

- **Outside caves, the world is mostly flat-ish and spreads out in both X and Z.** There is almost always
  an adjoining fragment in the intermediate region — precondition 3 rejects.
- **Inside caves there is significant stone**, so the intermediate regions probably have no fragment
  touching the corner — precondition 5 rejects.
- **High in the air most regions are `KIND_AIR`** and the bot is not up there anyway.
- **If you are in a cave with a diagonal path, adjoining fragments, and a `TYPE_S` fragment at the opposite
  corner, then ~99% of the time the walkable path is more than 1 block wide**, so it expands into the
  intermediate regions — precondition 3 rejects.
- **If you do have a thin diagonal path floating in the air, 93.75% of the time (15/16) it will cross at an
  EDGE rather than clipping a corner**, because `x % 16 != z % 16` (§0.1).

The compounded expectation is that optimistic corner emission is **very rare** — which is what makes
invalidation-as-the-only-corrective affordable, and what keeps this design clear of R4 ("blanket retry
admitting corner steps"): R4 rejected admitting corners on all hops of a thousand-hop FAIL *with no
preconditions*, whereas §4.1's six rejections should let almost none through.

**The one case this estimate does not cover** is §0.2's parent-fragment split, which needs no boundary
alignment and therefore does not get the 15/16 discount. Its rate is unknown until §0.2 is confirmed (I2).

### §5.1 Instrument it — do not bound it on speculation

Rather than fixing a cap up front, **count the emissions**. Add to the region trace / a debug counter:

| counter | what it answers |
|---|---|
| corner candidates considered (preconditions entered) | is the per-expansion cost real? |
| rejections by precondition (1..6, separately) | which gate is doing the work — and is any gate accidentally always-false, silently disabling the mechanism? |
| corner crossings emitted, per level | is the emission rate as low as §5 predicts? |
| corner crossings that reached a skeleton | how many actually cost anything |
| corner crossings blamed / blacklisted, **split NEAR vs FAR** | the refutation rate — the input to §3's deferred-refutation decision. **NEAR** = `A ==` the failing block search's start region, so `startScoped` withheld the durable row and R30's un-merge has nothing to consume (§4.6a, R39); **FAR** = recorded to `RegionCrossingMemory`. §4.6a argues FAR dominates because `WINDOW_CELLS == 4` puts the blame 1–4 cells behind `A`; this counter is what turns that argument into a measurement |

The per-precondition rejection breakdown is the most valuable of these: a mechanism that never fires
because a precondition is inverted looks exactly like a mechanism that never fires because the geometry is
rare, and §4.1 note 1 is a live way to get that wrong.

**If a bound turns out to be needed**, the natural shape is a **per-skeleton corner count**, not a
`MAX_UNBUILT_SHELL_DEPTH`-style depth bound — depth is the right instrument for a receding frontier, and a
corner is not one (§4.2).

---

## §6 Verification plan

0. **A SECOND red pin for the vertical corner** (§2.1) — a free-standing 1-wide staircase whose lateral and
   Y region boundaries coincide, beside `regionCornerPin()`. Without it the `Ascend`/`Descend` half of the
   gap has no oracle.
1. **`regioncorner.walkin`** (`ParkourCourse.regionCornerPin()`) — the RED pin. Today:
   `FAIL (nav gave up (no route offered))` with `maxProj = -8.49`, the bot never leaving its spawn block.
   Must go green.
2. **A FAR-GOAL corner card.** Same geometry, but with the goal far enough that the corner is planned at a
   coarse level with the NavGrid unresident at the corner. This is §3's deadlock, and it is the card that
   proves optimistic emission is doing the work the design exists for. Without it, the central choice is
   untested.
3. **The `diag` family must stay green** and must stay *nudged* — `addDiagTrial`'s alignment nudge should
   remain, so those cards keep testing diagonal parkour rather than silently re-testing this.
4. **No skeleton-shape regressions.** As of the 2026-08-27 ladder-descent landing the course family stood
   at: unit **1150/0**, `parkour` **106/1** (the 1 being `regioncorner.walkin`), `replan` **18/18**, `swim`
   **21/21**, `iceparkour` **23/23**, `trapdoor` **13/13**, `ice` **4/4**, `gate` **4/4**, `swimmine`
   **2/2**. **Re-measure before starting** rather than trusting that list — the point of the gate is the
   delta, and only `parkour` should move.
5. **Region-tier expansion cost must not move measurably** on the common path. **This is a HOT PATH —
   thousands of nodes, every search.** Only ONE new cost exists and it must be measured on its own:

   > the **per-expansion** cost of considering the corner directions at all and running the §4.1
   > precondition rejections — paid on *every* expansion, whether or not a corner exists.

   A 2-axis corner adds 12 candidate directions and a vertex adds 8 more, against 6 faces today — a naive
   implementation more than triples the neighbour loop. **Precondition 1 must be a hoisted loop-invariant
   flag** so a place-capable bot (the default) pays exactly one branch per expansion and never enters the
   loop. Measure with `PathfinderBenchmark` plus a region-trace expansion count on a dense-cave fixture,
   for BOTH a place-capable and a no-place bot.
6. **In-game**: the `orebit-diag1` datapack (`run/saves/New World (1)/datapacks/orebit-diag1/`) rebuilds
   the exact geometry — `/function orebit:diag1`, `orebit:start`, `orebit:markers`.
7. **The blame path needs its own test** (§4.4 / §4.6). A corner crossing the block tier fails to realize
   must blacklist the DIAGONAL pair `(A, fragA) → (D, fragD)`, not the `A → B` chain hop. Two failures are
   silent without a test, and both blacklist crossings that work: an intermediate chosen against
   `collectRealizedCrossings`'s X → Y → Z order (so `blameHop` never matches the `A → B` hop), and a
   `blockedHop` that does not collapse the corner run.
8. **A refutation-lifecycle test.** Emit an optimistic corner that the block tier cannot realize, and
   assert the bot (a) blames the diagonal pair, (b) re-plans without it, and (c) does not re-emit it on the
   next search. This is the normal lifecycle of a wrong corner and the single most important new test.
   **Scope (c) deliberately, per §4.6a/R39:** assert it against `blacklists[0]` *within one navigation*,
   and add a SECOND card whose corner is blamed from OUTSIDE its own start region (a far goal, so the
   window target moves past the corner while the bot is still cells behind it) asserting the
   `RegionCrossingMemory` row survives a plan boundary. Asserting cross-navigation durability on a corner
   at the bot's feet would pin behaviour `startScoped` deliberately does not provide — for corners or for
   any other crossing.
9. **A test for the §4.9 MERGE, which currently has no oracle anywhere in this plan.**
   `regioncorner.walkin` exercises the CROSS-REGION chain only. The L1+ `PyramidMerger` site needs its own
   assertion — see I2: `PyramidMerger` is MC-free, so it can be driven headlessly over synthetic child
   items and asserted as a unit property (one fragment where a corner-connected pair exists, two without).
   The L0 `FragmentBuilder` site is gated on I4 and gets a test if and when it ships.
10. **Assert the blame-walk guards directly** (they are silent when wrong):
    - `blameHop`'s `lo` anchor never lands on a corner-cut step (§4.6);
    - `blockedHop`'s FROM collapse walks backward **past `windowStart`** and still recovers `A`;
    - a corner-cut step's index is never returned by `WindowTargeting.target` (the `NO_PORTAL` /
      `hasPortal` contract, §4.5) — cheap to assert over a synthetic skeleton, and the thing standing
      between the corner id and `rf.footprint(61, face)`.
11. **A cost-shape assertion** (§4.3.1): on the `diag1` skeleton, the chain's total is the across-`A`
    traverse plus 2 (corner) or 3 (vertex) floored boundary hops, and every leg is ≥ `MIN_CROSS`. This is
    what keeps `RegionCostField`'s `cheb × MIN_CROSS` bound sound, and it is one arithmetic check.
12. **Chain atomicity (R37a) and the hand-down walk (R37).** Over a synthetic skeleton: a corner chain is
    never truncated so that a corner-cut step is index 0 or the tail; `handDown` on a corner-cut `far`
    returns `D`'s corner-column portal and never `centerOf(B)`; `extendL0`'s tail anchor resolves to a real
    fragment id so its join test can succeed. All three are silent when wrong — the `handDown` failure
    presents as the finer level aiming at open air, which looks like a planner bug several layers away.
13. **The corner short-circuit precedes `typeA` (R35)** and **the tube test names `D` (R36).** The first is
    an AIOOBE that only a MIXED intermediate reaches, so it needs a fixture with a real record in `B`; the
    second needs an inside-of-a-turn parent skeleton where `B' = (D'.x, A'.z)` misses a margin both
    endpoints clear. Neither shape occurs in `diag1`.
14. **The merge type test (R38)** — headless over `PyramidMerger` alongside item 9: a corner-adjacent pair
    where one mass is typeless must NOT union, where both carry `TYPE_S` must union.
15. **The boundary rule (R40), as a property test over a synthetic skeleton.** For every boundary in
    §4.5.1's census, feed a skeleton whose corner chain sits exactly at that index and assert the produced
    index names a real fragment: `splice`'s `drop` and tail (which should THROW if a caller passes one),
    `handDown`'s `far`, `extendL0`'s anchor, `blameHop`'s `lo`, `WindowTargeting`'s `choice.step`. This is
    the cheapest test in the plan and it covers R37, R37a and R40 together.
16. **The cascade matcher (R41a)** — assert `lp.committedIndex` never lands on a corner-cut step at ANY
    level, driving `onBotMoved` with a bot floor in `A` and then in `D`, and confirm no `exhausted` or
    `deviated` fires from the intermediate. The L≥ 1 case rests on a geometric argument rather than a
    coded gate, so it is the one that needs an oracle.
17. **The free-fall extension (R41b)** — a descending skeleton with a FLAT (X+Z) corner chain partway down
    must still extend to the landing. Today's `ry(i) < ry(i-1)` scan stops at the corner step; the fixed
    scan compares against the previous REAL step. Silent when wrong (it presents as a bot refusing to
    commit to a long drop), and no existing card has a corner mid-fall.

---

## §7 Related

- `internal_docs/DESIGN-region-corner-crossing.md` — the rejected proof-scan design (§3), kept for the
  record only.
- `internal_docs/HPA-FRAGMENTS.md` §2, §5 — the fragment model and its storage.
- `internal_docs/DESIGN-typed-fragments.md` §1–§2, §5.3–§5.5 — fragment types and record layout.
- `RegionEdgeBlacklist` / `RegionCrossingMemory` — online repair of unrealizable hops; §4.6 is the plan for
  keying corner blame into them.
- `HierarchicalRegionPlan` (`:184-187`) — where `RegionCrossingMemory` is folded into each per-level
  `RegionEdgeBlacklist` under `BotCaps.sigDominates`, which is why §4.1's precondition 6 probes ONE
  structure; and (`:438`) the "a virtual step has no occupiable identity to stand in" rule §4.5 extends to
  corner-cut nodes.
- `RegionCostField.MIN_CROSS` (`:25-29`, `:72`) — the `cheb × MIN_CROSS` admissibility bound that
  §4.3.1's per-hop pricing preserves and R20's retired bypass would have put at risk.
- `RegionPathPlan.NO_PORTAL` (`:75`) + every `hasPortal(i)` consumer (`WindowTargeting:125`, `:193`;
  `SkeletonDump`; `PathDebugRenderer:133`; `AllyBotEntity:1001`) — the seam that makes corner-cut nodes
  inert (§4.5, R32).
- `WindowTargeting.isUsableTarget` (`:298`) — the unbuilt-⇒-usable short-circuit that makes portal
  residency a non-issue at distance (§4.5).
- `CostPyramidCodec` (`:828`) — the `to-frag >= MAX_FRAGMENTS` invalidation-row filter that drops R28's
  rows automatically, and the second site R21's pre-release guard must cover.
- `PathPlan` (`:1441-1444`) — the forward-slide that would otherwise commit `windowStart` to a corner-cut
  step (§4.4, §4.5).
- `FragmentBuilder` (`:7`, `:21`, `:188`, `:269`) — the 6-connected leaf flood and the sequential
  flood-order id assignment (§4.9, §4.10).
- `PyramidMerger.mergeUpFragments` (`:398`) — the 6-face union-find that propagates the gap up the pyramid
  (§0.2, §4.9), and (`:63`) the 16-bucket face-relative footprint quantization (§4.1 note 4).
- `BlockPathfinder.collectRealizedCrossings` + `PathPlan.blameHop` / `blockedHop` — the realized-crossing
  decomposition and the blame walk. Read together they FORCE §4.4's X → Y → Z intermediate order.
- `RegionPathfinder.expandNode` / `relaxFrag` — the capability gates that create the gap
  (`!canPlace && kind == KIND_AIR && f != 2`, and the per-fragment `airGated && typeB == 0`), the
  physical-key blacklist probe, the `transitCost(across) + transitCost(boundary)` edge formula §4.3.1
  reuses, and the `max(edge, WALK_PER_BLOCK)` floor that R31 deliberately LEAVES IN PLACE.
- `RegionPathfinder.costToGoalField` / `isSealedWithin` — the reverse field and the boxed-in proof (§4.8).
- `RegionPathfinder.UNBUILT_EXCURSION_BOUND` / `MAX_UNBUILT_SHELL_DEPTH` (`:193`, `:204`) — the existing
  bounded-optimism precedent §4.2 contrasts against.
- `NavGridView.built` / `UNBUILT` (`:145`, `:53`) — the residency seam §3 turns on.
- `HpaMaintenance.java:470` — where `evictLeafTouching` already fires on a leaf change (§4.10).
- `WindowTargeting.target` / `snapInFootprint` — why the block-tier target is a stored portal CELL (§4.5).
- `InvalidationRollup.faceOf` — returns `-1` for a non-face-adjacent pair, which is why diagonal blame rows
  are inert at roll-up (§4.6).
- `HierarchicalRegionPlan.onBlocked` — the `recordToMemory && !startScoped && !virtualGoalHop` record gate
  (§4.6a, R39), and the `virtualGoalHop` guard that makes R28's codec filter a legacy-only cleanup.
- `NOTES-region-tier.md` §1.1 ("Why from-fragment is in the KEY, not just on the invalidation row") and
  §2 — the two-hallways/cliff origin of entry-conditioning, and why an ORDINARY crossing row is keyed
  physically while only the V-approach is conditioned (§4.6a).
- `PathPlan.java:1618` (`blockedStartFloor = searchStart`) + `HierarchicalRegionPlan.WINDOW_CELLS` (`:57`,
  = 4) — why a corner is normally blamed from 1–4 cells behind `A` rather than from inside it (§4.6a).
- `HierarchicalRegionPlan.handDown` (`:966`) / `extendL0` (`:617`) — the two `portalCell` reads that fall
  back to `centerOf` instead of guarding on `hasPortal` (§4.5, R37).
- `RegionPathfinder.RegionTube.contains` + `HierarchicalRegionPlan.TUBE_MARGIN` (`:86`) — the parent-level
  Chebyshev tube whose inside-of-a-turn geometry can reject a corner's intermediate (§4.3, R36).
- `RegionAddress.OCTREE_TOP` (`:47`) / `regionY` (`:126`) / `MAX_COARSE_LEVEL` (`:80`) — why Y-bearing
  corners stop existing at L5 (§2.1, R33).
- `RegionPathfinder.expandNode`'s opening `typeA = rfN.typeBits(fragA)` — the AIOOBE R35's short-circuit
  must precede.
- `PyramidMerger`'s `itemType[k]` / `compType[comp]` fold — the type data R38's merge gate reads.
- `FragmentBuilder`'s "6-connected components of a region's PASSABLE cells" (`:7`) — the caps-blind flood
  that makes a fragment span caps-disconnected masses, which is the ravine-component argument behind
  `startScoped` (§4.6a).
- `RegionPathPlan.splice` (`:196`) + `HierarchicalRegionPlan.extendL0`'s
  `drop = Math.min(committedIndex, max(0, driverWindowStart))` (`:647`) — the head cut and the join, and
  the place R40's assertion belongs; `snapshotOptimism`'s "the splice RENUMBERS every step" note beside it.
- `HierarchicalRegionPlan.onBotMoved`'s advance loop (`fragGate = L == 0 && sk.isFragmentModel()`) and its
  RESIDUAL RE-DERIVE-PROGRESS HAZARD comment — the consumer R41 argues safe, and the warning that says why
  the argument is written out (§4.5).
- `WindowTargeting`'s free-fall extension loop (`skeleton.ry(i) < skeleton.ry(i - 1)`) — the one step scan
  a FLAT corner terminates early (§4.5, R41).
- The registered `capability-aware-flood-intent` item (connectivity as the actual movement predicates) is
  the general form of this problem.

---

## §8 Decisions ledger

### RATIFIED

| # | Decision | Where | Date |
|---|---|---|---|
| R1 | The gap is real, fails CLOSED, and is worth machinery despite its rarity | §0 | 2026-08-24 |
| R2 | Widening region adjacency to 10/26 directions is not affordable — the node key has zero spare bits | §1 | 2026-08-24 |
| R3 | A raw block search with no skeleton is rejected — the two-tier design collapses without sub-goals | §1 | 2026-08-24 |
| R4 | A blanket "retry admitting corner steps on FAIL" is rejected — impossible bee-lines over thousands of hops. §4.1's six preconditions are what keep this design clear of it | §1, §5 | 2026-08-24 |
| R5 | Encode the crossing as a CHAIN of ordinary single-axis face hops through virtual corner-cut nodes (one intermediate for a 2-axis corner, two for a 3-axis vertex) | §4.3 | 2026-08-24 |
| R6 | The corner-cut id needs its OWN codepoint; drop `MAX_FRAGMENTS` 62 → 61 and give the corner cut 61. A sentinel means the SAME thing in every field and position | §1, §4.3 | 2026-08-24 |
| R7 | The cap reduction's cost (one fewer fragment before collapse) is accepted | §4.3 | 2026-08-24 |
| R8 | **Connectivity is GUESSED AND RETRACTED.** A corner crossing is emitted **optimistically** from region-tier facts whenever the §4.1 preconditions hold, and the invalidation machinery retracts it when the block tier cannot realize it (§4.6). Proving it first is **not possible** — the NavGrid is unbuilt at distance, so proof-gating fails closed exactly where the gap hurts and deadlocks (§3) — and even where it is possible it is **not worth it**: a per-crossing scan needs careful benchmarking and risks blowing the region tier's budget on approximation before block-tier A\* is ever reached | §3, §4.1 | 2026-08-28, owner |
| R9 | The gap is NOT limited to the `Diagonal` class — it is any move crossing ≥ 2 axes. Three-axis crossings exist TODAY (`DiagonalSprintSwim`'s 8 corners) | §2.1 | 2026-08-27 |
| R14 | The corner node's expansion is special-cased: skip the generic face loop, emit only corner exits | §4.3 | 2026-08-27 |
| R15 | The block-tier target is the stored portal CELL, stamped with the centre of `D`'s corner-touching footprint ∩ the corner column; `isUsableTarget` / `snapInFootprint` then do their normal job | §4.5 | 2026-08-28 |
| R16 | The intermediate order is **X → Y → Z**, forced by `collectRealizedCrossings`'s decomposition (not a free choice) | §4.4 | 2026-08-27 |
| R17 | Blame is keyed DIAGONALLY: collapse corner runs in `blockedHop` and emit `(A,fragA) → (D,fragD)`. The invalidation graph stores from/to as two separate `long`s, so it can express that | §4.6 | 2026-08-27 |
| R18 | **Search time, not build time** — pre-recording diagonal crossings is region-generation overhead and extra bits on disk we do not have | §4 | 2026-08-27 |
| R19 | **`DiagonalParkour` IS in scope** — excluding gap-spanning moves creates a false FAIL. This is automatic: nothing enumerates movements, so nothing can exclude one | §2.1 | 2026-08-27 |
| ~~R20~~ | **SUPERSEDED BY R31 (2026-08-28).** Was: the chain's hops sum to ONE real move and must bypass `relaxFrag`'s `max(edge, WALK_PER_BLOCK)` floor. R31's per-hop model makes the chain naturally ≥ 2 (corner) / ≥ 3 (vertex) ticks with the floor left ALONE, which is accepted as correct-and-mildly-discouraging — so the bypass buys ~0.59 ticks against region edges of 16–512 while risking `RegionCostField`'s `cheb × MIN_CROSS` admissibility bound. **Do not implement the bypass** | §4.3.1, §4.8 | 2026-08-27, retired 2026-08-28 |
| R21 | The `MAX_FRAGMENTS` 62 → 61 decode guard is DEFERRED to a pre-release checklist item — no persisted region graph exists in any live world. This is format-**adjacent**, not a format change: the count field is 6 bits with 63 as `FRAGMENT_COUNT_COLLAPSED`, so the field WIDTH does not move — only a region already persisted with exactly 62 fragments would need to decode to something sane. (`CostCodec.unpackRegion` reads that 6-bit count and loops into `MAX_FRAGMENTS`-sized arrays; a shard with `count == 62` would throw AIOOBE instead of falling back to "bad shard => rebuild". One-line fix: `count >= MAX_FRAGMENTS` => treat as collapsed, before the loop. Persistence version constants stay PINNED AT 1.) **Second site (found 2026-08-28):** `CostPyramidCodec:828` drops invalidation rows whose TO fragment id is `>= MAX_FRAGMENTS`, so the reduction also makes it discard previously-persisted rows naming the then-real fragment 61. Same disposition — moot pre-release, but it belongs on the same checklist item | §4.3 | 2026-08-27 |
| R22 | An already-invalidated crossing is a precondition, probed before anything else is paid for | §4.1, §4.6 | 2026-08-27 |
| R23 | Residency-aware REFUTATION (three-valued: refute only when the nav grid is resident over the ENTIRE candidate volume and no movement lands in `D`) is **DEFERRED** — a filter on top of optimistic emission whose value cannot be measured until corners are in flight. If it is ever built, its scan must be **MOVEMENT-DERIVED**: any offset or reach pre-filter is computed from the registered movement set, never hardcoded beside it, or registering a new movement silently fails to widen the scan and the failure looks exactly like the original bug | §3 | 2026-08-28 |
| R24 | A block-tier BLOCKED on a corner is **positive proof that the crossing is not SAFELY traversable**, not a false negative — a corner that cannot be routed inside the search budget, or only past an irreversible move, carries enough uncertainty to risk stranding the bot. Corner refutations record as ordinary `PROV_PROOF` with no special provenance, no failure counting, no decay | §4.6 | 2026-08-28, owner |
| R25 | Emission rate is **instrumented, not bounded on speculation** (§5.1). If a bound proves necessary, it is a per-skeleton corner COUNT, not a shell-depth analogue — depth is the instrument for a receding frontier, and a corner is a fixed feature | §5.1, §4.2 | 2026-08-28 |
| R26 | `TYPE_S` is required on the destination fragment (and stated for the source). This is fragment-level and therefore optimistic; it deliberately excludes `DiagonalSprintSwim`, which §2.1 shows cannot strand a swimmer | §4.1 | 2026-08-28 |
| R27a | **The two merge sites are scoped separately (2026-08-28).** The **L1+ `PyramidMerger`** site is SELF-HEALING and IN SCOPE: false parent connectivity is predicated on the corner crossing, so L0 can only realize it via the cut, and the block-tier refutation feeds R30's re-merge. It fuses only `TYPE_S`+`TYPE_S` (precondition 4 + note 2), so no typeless pocket is laundered into a surfaceable mass; capability-independence is fine because a place-capable bot can build the corner. The **L0 `FragmentBuilder`** site is NOT self-healing — a wrong merge leaves no crossing to blame, so `blameHop` blames a REAL crossing (X1's failure mode) — and is **GATED ON I4** | §4.9 | 2026-08-28, owner |
| R27 | The INTRA-REGION corner cut is a **MERGE, not a crossing** — the HPA\* graph keys fragment residency, so two corner-connected masses in one region are not two nodes needing an edge, they are **one fragment**. Recognise it in the union step (`FragmentBuilder`'s 6-connected L0 flood; `PyramidMerger`'s child-slot `xor` test) with the same evidence the face union already demands — never by widening the flood to 26-connected | §4.9 | 2026-08-28, owner |
| R28 | A blame row that still names the corner-cut id after the §4.6 collapse is **DROPPED at encode**. **Mechanism corrected 2026-08-28:** a corner node can never itself be a goal approach (the approach lookup keys on the physical `fragmentKey(region, fragment)` and the seed list holds only real fragments), so the row shape is the mirror one — `D` is the approach and `approachRowKeyForStep` stamps `fromFrag = CORNER`. The drop is **automatic**, though **rev 3 corrects the mechanism twice**: `CostPyramidCodec:828` is a **DECODE** filter, not encode, and its own comment records that the write site already refuses these rows — `HierarchicalRegionPlan.onBlocked` carries a `virtualGoalHop` guard, so `:828` is "purely a LEGACY cleanup" for files written before that guard. The OUTCOME R28 wants (no corner id survives a round-trip) holds; the stated route to it did not | §4.6 | 2026-08-28, owner; mechanism corrected 2026-08-28 rev 3 |
| R29 | The reverse cost field **does** carry corner cuts, via a direct `D → A` relax with **no intermediate node**: the field needs costs, never a face-shaped skeleton, so there is no corner node and therefore no `cfrom` identity for `dijkstra` mode to erase. `isSealedWithin` can no longer declare a corner-reachable goal SEALED | §4.8 | 2026-08-28 |
| R31 | **The chain is priced per-hop with the ORDINARY edge formula, zeroing the virtual traversal term.** Each hop is `transit(across X) + boundary hop`, where `transit(across X) == 0` iff `X`'s fragment id is the corner-cut sentinel — a virtual fragment is not occupied space. So `A.1 → B.61` carries the REAL across-`A` walk (the bulk of the cost, which is what stops corners out-competing face crossings at `A`'s expansion) and the remaining hops carry only their boundary term. The floor STAYS, so a corner totals ≥ 2 ticks and a vertex ≥ 3 against a diagonal's ~1.41–1.73 — slightly dearer than the move it stands for, which errs safe for an optimistic edge, and which clears `RegionCostField`'s `cheb × MIN_CROSS` bound. Also fixes `typeB = 0` (dry/walk) for a virtual destination and anchors `wa`/`wb` on the CORNER COLUMN, not face centroids | §4.3.1, §4.8 | 2026-08-28, owner |
| R32 | **Corner-cut nodes carry `RegionPathPlan.NO_PORTAL`.** They have no occupiable identity, exactly as `HierarchicalRegionPlan:438` already says of `VIRTUAL_GOAL_FRAG`. Every DISPLAY-or-TARGET consumer already guards on `hasPortal(i)` (**corrected in rev 3 — `handDown` and `extendL0` do NOT; see R37**), so for those it needs NO new machinery and it (a) stops `WindowTargeting` selecting a corner step and `PathPlan`'s forward-slide committing `windowStart` to it, (b) keeps the corner id out of `snapInFootprint`'s `rf.footprint(frag, face)` — whose only existing guard is `isVirtualGoal`, so a MIXED intermediate would otherwise index past the kept range — and (c) composes with R31, which never reads a virtual node's entry portal | §4.5 | 2026-08-28, owner |
| R33 | **Y-bearing corners are an L0..L4 mechanism.** At `level >= RegionAddress.OCTREE_TOP` (5) the vertical extent is one slab, `ry` is pinned to 0, and there is no ±Y neighbour — `expandNode` already skips faces 2/3 there. The cascade plans to `MAX_COARSE_LEVEL` = 6, so L5/L6 are quadtree: the enumerator emits only the X+Z corner there, and the 3-axis vertex chain does not exist. §2.1's vertical worked example is an L0..L4 statement; at L5+ the same geometry is the §4.9 intra-region MERGE, not a crossing | §2.1 | 2026-08-28 rev 3 |
| R34 | **An UNBUILT intermediate is precondition 3's business, not precondition 5's.** A null fragment record has no `kind` and would fail precondition 5, but never reaches it: unbuilt optimism asserts all lateral connections exist, `expandNode` crosses an unbuilt neighbour as an ordinary uniform edge into fragment 0, and the no-place air gate cannot suppress it because the guard reads `rfM != null && rfM.kind() == KIND_AIR`. Precondition 3 therefore always rejects first, and preconditions 4/5 only ever see regions that HAVE records | §4.1 note 1b | 2026-08-28 rev 3, owner |
| R35 | **The corner short-circuit goes at the TOP of `expandNode`, before `typeA`.** R14's "skip the generic face loop" is insufficient: `typeA = rfN.typeBits(fragA)` and `countN` are computed above the loop, so a corner node in a MIXED region evaluates `typeBits(61)` and throws AIOOBE once `MAX_FRAGMENTS` is 61. The intra-region MINE block sits between them and would also emit sibling edges out of a virtual fragment. Hides in testing because a uniform-AIR or unbuilt intermediate takes the `uniformN` arm and never indexes the array | §4.3 | 2026-08-28 rev 3, owner |
| R36 | **The `RegionBox`/`RegionTube` containment test names `D`, never an intermediate.** The box is harmless either way (`B` is componentwise between `A` and `D`) but exempting it stops a virtual node setting `outOfBoxRejected` and voiding `isSealedWithin`'s closed-flood harvest. The TUBE is not harmless: it tests parent coords after `rx >> d`, where a corner's `B' = (D'.x, A'.z)` is a MIX rather than a midpoint, so on the inside of a turn `B'` can miss the margin that both `A'` and `D'` clear — killing a corner whose real endpoints are admitted | §4.3 | 2026-08-28 rev 3, owner |
| R37 | **`handDown` and `extendL0` do NOT guard on `hasPortal` — they fall back to `centerOf`, which for a corner-cut step is the centre of the pure-air intermediate.** `handDown:966` hands that centre DOWN as the finer level's sub-goal; `extendL0:617` anchors the suffix search there and then fails its `fragmentId(0) != sk.fragmentId(tail)` join against `CORNER` forever. **Fix: hand down the CORNER COLUMN by walking the chain FORWARD to the next real step and reading its portal** — that is `D`, ≤ 2 steps away, whose portal R15 already stamps on the corner column. No new storage, no parallel array, and R32 is untouched (the step still reports `hasPortal == false`). `extendL0`'s tail anchor takes the mirror BACKWARD walk. **This corrects R32's "every consumer already guards"** | §4.5 | 2026-08-28 rev 3, owner |
| R37a | **GENERALIZED BY R40.** Chain atomicity is an ENFORCED invariant, not an assumption: a skeleton never begins or ends on a corner-cut step, and a chain is never split across a splice boundary. R37's forward walk needs a real step to find. The at-risk sites are `RegionPathPlan`'s prefix/suffix concatenation (`:232-243`) and every window truncation. Asserted by §6 item 12 | §4.5, §4.6 | 2026-08-28 rev 3, owner |
| R38 | **The §4.9 merge requires `TYPE_S` on BOTH masses, as a rule of its own.** R27a's "it can only ever fuse two `TYPE_S` fragments" was written as if it followed from §4.1 precondition 4 — it does not: §4.1 is a search-time gate with no authority over `FragmentBuilder`/`PyramidMerger`, and the merge rule as first written carried no type test, leaving R27a's capability-safety case resting on nothing. The data is in hand (`itemType[k]`, ORed into `compType[comp]` right after the union-find), so it is one mask read in the existing loop. Without it a corner union launders a typeless air pocket into a surfaceable mass and defeats the `airGated && typeB == 0` gate at BUILD time. Note also the evidence asymmetry: L0's rule is a real predicate (`Diagonal`'s both-orthogonals-passable), while L1+'s footprint test cannot express orthogonal-sibling passability at all — so the site shipping first has the weaker evidence, admitted purely on the self-healing argument | §4.9 | 2026-08-28 rev 3, owner |
| R39 | **`startScoped` withholding a durable row is a PRE-EXISTING property of every optimistic crossing, not a corner defect — do not re-file it as a blocker.** `onBlocked` skips `crossingMemory.record` (and the roll-up fold) when the blamed hop's FROM region is the failing block search's own start region, because an ordinary crossing row is keyed PHYSICALLY and cannot carry the entry-conditioning that would name WHICH caps-disconnected mass of `fragA` was explored (NOTES-region-tier.md §1.1's two hallways; the same fact as the cliff). Three corrections to the first review that filed this: (a) it does NOT bite `regioncorner.walkin` — that corner is realizable, so it never reaches the blame path; (b) near corners are not typical — `WINDOW_CELLS == 4` and the search starts at the bot, so a corner is normally blamed 1–4 cells before the bot stands in `A`; (c) a plain FACE crossing behaves identically. The per-plan `blacklists[0]` row always fires either way. **The one corner-specific residue is a QUALIFICATION on R30/R27a**: an un-merge consumes the withheld row, so §4.10's "the merge consults that same surviving L0 row" holds only for corners refuted from outside their own start region; otherwise L1 stays optimistic and L0 re-refutes per navigation — the ordinary HPA relationship, still strictly better than today's total refusal. Measured by §5.1's NEAR/FAR split rather than argued | §4.6a, §4.10 | 2026-08-28 rev 3, owner |
| R40 | **No boundary ever lands on a virtual fragment** (owner ruling). No index naming a skeleton POSITION may name a corner-cut step; every producer walks past the run — FORWARD for a start/target/hand-down, BACKWARD for an end/anchor — the same collapse `blockedHop` already does. Generalizes R37a. §4.5.1 carries the site census: `splice`'s `drop` and JOIN are safe by induction (both cursors feeding `drop` are corner-free, and index 0 is real only because this rule says so), `handDown`'s `far` is NOT (that is R37), and the rest inherit R32. **`RegionPathPlan.splice` asserts it** — it already throws on an out-of-range `drop`, so a `drop`/tail naming the corner id throws the same way rather than surfacing three layers later as a finer level aiming at open air. Note `splice` RENUMBERS every step, so no corner index is stable across one | §4.5.1 | 2026-08-28 rev 3, owner |
| R41 | **The two step consumers that do NOT read `hasPortal` are both SAFE, and the ARGUMENT is recorded rather than the conclusion.** (a) The cascade's commit/deviation matcher is fragment-gated at L0 (so a corner step is `continue`d) and region-only at L≥ 1 — safe there for a GEOMETRIC reason: a diagonal block step goes `A`-cell → `D`-cell, so the bot's floor cell is never inside intermediate `B` and the step never matches at any level. Written out, not asserted in one line, because `onBotMoved` carries a named RESIDUAL RE-DERIVE-PROGRESS HAZARD about triggers that fire from an identical state, and a never-occupiable step is exactly that shape — re-check it first if anything ever lets a floor cell resolve into a corner intermediate. (b) Window reach is counted in STEPS, so a corner costs 1 and a vertex 2 of `WINDOW_CELLS == 4` — accepted; but `WindowTargeting`'s free-fall extension scans `ry(i) < ry(i-1)` and a FLAT (X+Z) corner step terminates that scan early, so **it must compare each real step against the previous REAL step** | §4.5 | 2026-08-28 rev 3, owner |
| R42 | **A corner-cut node's `h` is measured from intermediate `B`, and that is an accepted approximation.** `relaxFrag`'s `HEURISTIC.estimate(mrx,mry,mrz,…) * hScale` reads the region being relaxed, while the node's real position is the `A`/`D` shared edge — an error bounded by one region side, in either direction. Same magnitude the tier makes everywhere (every region node's `h` is region-measured, and `hScale` is a greedy weight, not an admissibility guarantee); it costs re-expansion around a corner, never a path. Recorded so the silence is not read as an oversight, and as the first place to look if corner pops ever appear mis-ordered in a region trace | §4.3.1 | 2026-08-28 rev 3, owner |
| R30 | A wrong merge is corrected by **RE-MERGING UPWARD on invalidation**: refuting a corner splits the parent fragment that corner justified, L1 → L6. This settles rather than oscillates because `RegionCrossingMemory` is per-level and a cross-region L0 corner never shifts L0 ids, so the gating row survives. It costs **every L1+ invalidation row for the affected regions** — **evict and re-discover** (`evictLeafTouching` already does this from `HpaMaintenance:470`). Those negatives stay TRUE (connectivity only decreases; losing an edge never makes a thing passable) — only their keys stop resolving, so this is a lossy re-derivation chosen for simplicity, not a correctness cleanup. Coarse levels are corrected structurally, not by blame (see I3) | §4.10 | 2026-08-28, owner |

### REJECTED — do not re-litigate

| # | Rejected | Reason | Where |
|---|---|---|---|
| X1 | The DIRECT corner edge (`A → D` in one hop, no intermediate) | It destroys the from-region inference. `entryFace` is the only affordable way to name the region a region-scoped `fromFrag` belongs to; a corner edge lets two different predecessors mint the same key, and `fromFrag == 0` is the common case, so the collision is expected rather than theoretical. No spare `entryFace` codepoint exists. The failure is SILENT — it blames the wrong approach | §4.7 |
| X2 | Reusing fragment id 62 or 63 for the corner cut | Both existing sentinels are ONE-SIDED, so each leaves a real vacancy — but the corner cut is TWO-SIDED and needs a codepoint vacant in both fields | §1, §4.3 |
| X3 | A hardcoded offset/reach bound on a corner scan | It hard-codes today's movement set into the region tier, so registering a new movement silently fails to widen the scan and the failure looks exactly like the original bug. Dormant while nothing enumerates movements; binding again the moment R23's refutation is built | §3, R23 |
| X4 | Choosing the intermediate by the move's SWEPT BODY | Conflicts with `collectRealizedCrossings`'s fixed X → Y → Z order, which would make `blameHop` falsely blame the corner hop. **That argument alone is sufficient** — the secondary "nothing reads the intermediate's contents" rationale was FALSE (`WindowTargeting` reads every window step) and is withdrawn; R32's `NO_PORTAL` is what actually makes the intermediate inert | §4.4, §4.5 |
| X5 | NavGrid-proven corner crossings as the emission gate | The NavGrid is resident only near the bot and has no live-block fallback; proof-gating therefore fails closed at distance and deadlocks (you cannot prove it without being near, cannot get near without the route), the scan's availability is inverse to its value, and it would thread the whole block-tier read stack into a tier that has none | §3 |

### REQUIRES INVESTIGATION

**These need agent research, not owner ratification.** Every decision in this document is made; what is
left below is empirical work whose *result* may raise a new decision.

| # | Question | Where | Notes |
|---|---|---|---|
| I1 | Audit every `isVirtualStart` / `isVirtualGoal` call site | §1, §4.3 | They take a bare `int`; a third sentinel is the moment to confirm the same-meaning invariant holds everywhere rather than by custom. Approved by owner |
| I2 | **Confirm §0.2.** Does an L0 corner gap actually produce a spurious parent fragment split, or is it rescued by transitive union through a third child? | §0.2 | The union rule (`PyramidMerger.java:398`) is VERIFIED; the consequence is INFERRED. **Cheaper than the in-game trace an earlier revision proposed:** `PyramidMerger` is MC-free (same as `FragmentBuilder`), so this is a headless UNIT property over synthetic child items — deterministic, and it doubles as §6 item 9's oracle. It changes the scope estimate materially — the parent-split case needs no boundary alignment, so it does not get §5's 15/16 discount |
| I3 | **Does re-merge (R30) fully replace escalation for coarse corners?** | §4.6, §4.10 | R30 corrects coarse levels structurally, which should make the never-rolls-up property (`InvalidationRollup.faceOf` → `-1`) irrelevant rather than hazardous. Confirm that: with re-merge in place, is there any path by which a coarse skeleton keeps re-emitting a corner the block tier refuted at L0? If yes, tube-drain escalation (`PROV_ESCALATION`) is still needed and must be shown to fire; if no, the escalation concern closes with R30 |
| I4 | **GATE on §4.9's L0 site (promoted 2026-08-28) — the INTRA-REGION merge has no crossing to invalidate, so what triggers its split?** | §4.9, §4.10 | R30 hangs off refuting a cross-region corner. The L0 `FragmentBuilder` site has no crossing at all: the failure happens INSIDE one fragment, between its portals, so `blameHop` blames the hop INTO or OUT OF that fragment — both real, realizable crossings. That is X1's failure mode: silently blacklisting a working crossing. **This is no longer just an open question — per R27a it BLOCKS the L0 site**, which ships only once someone establishes that the both-orthogonals-passable test (§4.9) is tight enough to make a wrong L0 merge unreachable, or gives that site its own trigger. The L1+ site is unaffected and ships first |
| I5 | **Planner-thread safety of a re-merge.** | §4.10 | `RegionPathfinder` reads `grid.fragmentRecord(level, …)` (`:697`, `:956`, `:970`) and runs on the `PlanExecutor` pool. R30 MUTATES those records. This is the hazard `NavReclaim` exists to prevent for NavSections — a planner-thread search must never read a record being rebuilt under it. Determine whether re-merge needs the same epoch-deferred discipline (drained against `PlanExecutor.minActiveStamp()`) or can be confined to a safe point |

### Scoping fact that should inform all of the above

`placement.canPlace` **defaults to `true`** (`docs/configuration.md:62`, `internal_docs/CONFIG.md:62`), and
the gap only exists for a no-place bot — so as the region tier stands, **this whole mechanism is dormant
for a default-configured bot** (§4.1). It fires for `placement.canPlace = false`, a documented supported
configuration (`docs/configuration.md:298`) and the one `regionCornerPin()` uses. It would become common if
the region tier were made inventory-aware: `BotCaps.java:361` already computes an inventory-effective place
flag, but that feeds the realizability signature only — `AllyBotEntity` passes the raw `caps.canPlace()`
into the region call. Threading the effective flag through is a small change the owner expects to make
eventually (§4.1). **The gap fails closed, which is why rarity does not bound the damage.**

---

## §9 Implementation record (2026-08-29)

Implemented on `core` in one arc (26 files: 16 main + 10 test), verified on the mc-1.21 era, adversarially
reviewed (5 reviewers over the full diff), fix-passed, and gate-swept. The far-goal card (§6 item 2) is
DEFERRED — ParkourCourse's `navBuildFootprint`/`navReadyAround` build nav over the whole start→goal
corridor, defeating that card's unresidency premise; it needs a harness flag or a ReplanCourse/autotest
host (owner decision). I3 and I4 remain open as written; I5 was RESOLVED by code evidence: `RegionPathfinder`
is tick-confined (the planner pool runs only `BlockPathfinder` over `bakeSlabs`-cloned field arrays), so
R30's re-merge is a plain tick-thread call and needs no epoch machinery.

### Verification results

- `regioncorner.walkin` RED→**GREEN** (`maxProj` −8.49 → +5.21); NEW vertical pin `regioncornerY.walkin`
  (§6 item 0: stairRunway, base (−23,−44), the X and Y region crossings coinciding on the final Ascend)
  **GREEN**. Parkour **108/108**.
- Course family at baseline, zero regressions: replan 18/18, swim 21/21, trapdoor 13/13, gate 4/4, ice 4/4,
  iceparkour 23/23, swimmine 2/2, shaft 7/7. Unit suite 1150/0 → **1175/0** (25 new tests, incl. the §6
  item 8 refutation lifecycle driven headlessly through the real `onBlocked`, the R35 MIXED-intermediate
  crash pin, the §4.10 consult/split pins, and I2's confirmation). `chiseledCompileCommon` green.
- **I2 CONFIRMED** (headless `PyramidMerger` property): the corner parent-split is real under the 6-face
  union; the corner union fuses exactly the S+S case and the ¬S pin (`disjointAirColumns_twoFragments`)
  stays split.
- **Perf (§6 item 5; paired order-counterbalanced A/B, A1→B1→B2→A2):** block tier SHORT −1.5% / MULTI −1.2%
  (noise); region plan OPEN_CAVERN/MULTI_FRAGMENT/LONG_CASCADE ±0.4% (noise); SEALED_DIG (a canPlace=true
  0.365 µs micro-plan) +6.0% = +22 ns/plan ≈ the two design-mandated always-false branches (R35 +
  precondition 1) at ~0.7 ns/expansion; ZERO_CAP (the no-place control) +99% (0.79 → 1.57 µs) — the
  sanctioned §4.1 detection cost, dominated by precondition 3 (`faceRouteExists`) running whenever the
  synthetic full-face footprints pass precondition 2; rollup fold +7–9% (cold, a few per goal). No
  common-path regression. If ZERO_CAP's 2× ever matters live, the lever is precondition ORDER (evaluating
  the cheap D-side test 4 before 3) — an owner call, since §4.1 ratifies the order.

### Implementation amendments — PENDING OWNER RATIFICATION (A1–A10)

| # | Amendment | Why |
|---|---|---|
| A1 | **Y-axis ONE-BUCKET tolerance** in the corner-touch tests (`footprintReachesExtreme`, search + merge sides), and `cornerAnchor` clamps the pinned Y into the fragment's vertical footprint (R15's "∩" made literal) | Footprints are built from PASSABLE cells and floors are not passable, so both sides of a Y-bearing corner stop one bucket short of the vertical extreme — preconditions 2/4 as literally written reject §2.1's own vertical example (and the §6 item 0 pin). Horizontal extremes stay exact |
| A2 | **Chain boundary hops priced flat `WALK_PER_BLOCK`**, not `transitCost(wb−wa, typeB=0)` — the Δy between A's and D's corner anchors is unpriced across the chain | The exit hop has no real B anchor to difference from. Errs optimistic (more emission, refutation-corrected; never a wrong accepted path); `cheb × MIN_CROSS` unaffected. R31's formula would charge the Δy on the boundary leg — ratify flat, or specify a follow-up pricing the Δy on the exit hop |
| A3 | **`InvalidationRollup.foldOnce` bails when a corner-capable child pair exists** between the two parent fragments (corner-adjacent across the fold face, both items TYPE_S, footprints meeting — preconditions 3/5/6 deliberately NOT evaluated; conservative over-bail = the pre-existing safe degrade) | NEW RULE (review dim3 F1): the fold's kill-set enumerates face openings only, but the child A\* can now realize a parent crossing via a §4.3 chain — an un-bailed fold records a durable, persisted FALSE parent negative that R30 can never repair (the corner was never refuted, so no diagonal row exists) |
| A4 | **`blameTubeConfined` collapses corner runs** (shared `RegionPathPlan.collapsedHopKeys` with `blockedHop`) — this site joins §4.5.1's census, which missed it | Uncollapsed, its `PROV_ESCALATION` row names `(A) → (B, CORNER)`, which nothing probes; the parent re-emits the identical corner, the second blame hits the dedup bail, and `onBlocked` sets `failed = true` — the §0 fail-closed refusal resurfacing on the escalation path (also the only corrective for a NEAR/startScoped corner) |
| A5 | **`WindowTargeting`'s CENTER fallback walks `last` FORWARD past a corner run** before building the Result — the census's `choice.step` row assumed the two `hasPortal` loops covered every arm; the CENTER arm bypasses both | Un-walked, the block search is aimed at `centerOf(B)` (pure air, R37's "strictly worse"), and via the forward-slide → `maybeExtendL0` → `splice` the R40 assert becomes a reachable tick-thread crash |
| A6 | **`CostCodec.unpackRegion` decodes a legacy over-cap count (62) as CAP-COLLAPSED**, shipped NOW (R21's own one-liner; R21's "defer to pre-release checklist" is retired) | The audit found dev caches and the archived autotest worlds hit the AIOOBE the moment `MAX_FRAGMENTS` drops. Framing verified safe (`CostPyramidCodec.decode` frames records in length-prefixed run buffers and discards the bit offset); one region degrades to passFrac pricing instead of losing a shard |
| A7 | **`RegionCrossingMemory.evictCoarseTouching` (L≥1 only)** is R30's eviction — NOT the existing `evictLeafTouching` | §4.10 says "every L1+ row"; the existing all-levels evictor would erase the just-recorded L0 diagonal row that gates re-emission and feeds the merge consult — a subtlety the design did not call out |
| A8 | **`reconstructFragments` trims a trailing corner run** on the partial-best path — R40 enforced at the source; `maybeExtendL0`'s tail walk is thereby dead code (assert-backed) | A budget-drained search can select a mid-chain corner row as `bestRow`; a skeleton must never end on one |
| A9 | **Vertex C-node exit derivation walks `nodes.parent[]`** to recover A (C's key carries `fromFrag = CORNER`, which does not pin A) — two vertex chains sharing (B,C) collide and keep the cheaper parent | Accepted approximation; re-parenting cannot corrupt it (a `cfrom=CORNER` key is only ever relaxed from a corner row). Documented at the walk |
| A10 | **§4.10's consult**: exact `(leaf, fragment)`-keyed at parentLevel 1, region-masked over the corner leaf pairs at ≥2 (ANY surviving row in either direction refuses), sig-blind | The design specified only the L1 form; region-masked over-refusal at coarse levels degrades to get-close-then-figure-it-out. The I3 residual (capability-scoped consult) is documented in code |

Minor recorded (no ratification needed): vertex precondition 3's edge-adjacent arm is a conservative
economy gate (may under-emit vertices; no shipped dry-land 3-axis move exists — revisit with
rising/falling DiagonalParkour); §5.1's [0]/[7] counters are per-row-pop and the field's reset stomps the
forward search's stats (granularity documented at the accessor); §6 items 16/17 are enforced by the
pre-existing `fragGate` pin + the R41b restructure's review rather than dedicated unit tests; corner
re-merge failures count in their own `cornerRemergeFailures`, not `buildFailures`; the codec's legacy
filter stays TO-side only (FROM-side corner ids are unreachable post-collapse — recorded, not guarded).
