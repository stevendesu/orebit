# DESIGN — Fluid-flow prediction ("will breaking this cell admit fluid?")

Status: **IMPLEMENTED 2026-08-17** (ratified same day after a final review folded five owner-ratified
amendments — no tier-2 memo, scratch-first reads, tier-0 diff-blindness deferred to the PathEdits-scatter
workflow, source-reading diff constants, lava in the vertical rule; the nether slope pin §5 and the §6
pricing correction were ratified/forced during implementation). Landed with 988 unit tests green; the two
post-implementation addenda (§1.2 bytecode addendum, §6 correction) record what the adversarial review
established. Companion to
`NOTES-vanilla-fluid-physics.md` (which covers the ENTITY-side integrator, pose and damage — this doc
covers fluid SPREAD, a disjoint subsystem) and to the underwater-mining arc that shipped the submerged
mining stance (core `454c28c`, `c5fe3df`).

**The problem this closes.** The planner has never known whether digging near fluid floods the cell it
digs. It routes `MineDown` through water when a dry column sits two blocks over, digs, floods, eats a
NavGrid invalidation, and replans — repeatedly. Pricing the *stance* (shipped) fixed the cost of mining
while already wet; it did nothing about *choosing* to get wet.

**Provenance.** Every vanilla claim in §1 was disassembled from the Loom-cached Mojang-mapped jar,
**MC 1.21.11** (`javap -c -p` on `net.minecraft.world.level.material.FlowingFluid`, `WaterFluid`,
`LavaFluid`, `EmptyFluid`, `SimpleWaterloggedBlock`). A 1.20.1 read (2026-08-10) agreed structurally;
names drifted (`canSpreadTo`→`canMaybePassThrough`, `SpreadContext` added). **Versions between are
unchecked.** Several claims were additionally corroborated by owner in-game experiments, noted inline —
those experiments are the reason three earlier drafts of this model were discarded.

---

## §1 Vanilla facts (bytecode-verified, 1.21.11)

### §1.1 `spread()` — TWO mechanisms, do not conflate them

```java
if (canMaybePassThrough(DOWN) && belowFluid.canBeReplacedWith(...) && canHoldSpecificFluid(...)) {
    spreadTo(below);
    if (sourceNeighborCount(pos) >= 3) spreadToSides(...);
    return;                                              // early return
}
if (state.isSource() || !isWaterHole(pos, ..., below, belowState)) spreadToSides(...);
```

**Mechanism 1 — is lateral spread ELIGIBLE?** This is a **necessary, not sufficient** condition: it
decides only whether `spreadToSides` is *called at all*. For a cell that passes, mechanism 2 then selects
the direction set — which may be **zero, one, two, three, or all four** directions. Nothing in this
section ever means "fluid WILL arrive"; only mechanism 2 can say that, and only about a specific
direction.

The down-gate is `canBeReplacedWith`, **not** "is there air below":

- `WaterFluid.canBeReplacedWith` = `direction == DOWN && !fluid.is(FluidTags.WATER)` → **false when
  water replaces water.** `EmptyFluid`'s returns `true` unconditionally.
- ⇒ the down-branch is live **only while the cell below is genuinely empty**. In a settled world that
  is never, so the else-branch governs.
- `isWaterHole(pos)` returns **true when the cell below already holds the same fluid** (an `isSame`
  check precedes the `canHoldFluid` fallback).

**Steady-state table** (what a settled NavGrid actually sees):

| fluid cell | spreads laterally? |
|---|---|
| **source** | **eligible** — `isSource()` short-circuits |
| flowing, same fluid directly below (mid-fall column) | **not eligible** |
| flowing, resting on solid | **eligible** |

A waterfall's *column* is inert; its *source* and its *pool* are eligible. "Eligible" is the whole claim —
an eligible source with a drain three cells away sends fluid **only** toward that drain and nowhere else.

`sourceNeighborCount` counts the **4 horizontal** neighbours that are **sources of this fluid**.

**Mechanism 2 — which directions get it** (`getSpread`):

```java
int minDist = 1000;
for (Direction d : horizontals) {
    if (!canMaybePassThrough(d)) continue;
    int dist = ctx.isHole(next) ? 0 : getSlopeDistance(next, 1, d.getOpposite(), ...);
    if (dist <  minDist) map.clear();
    if (dist <= minDist) { map.put(d, ...); minDist = dist; }
}
```

`getSlopeDistance` returns `depth` on finding a hole and recurses only while
`depth < getSlopeFindDistance()`. Starting at depth 1:

| fluid | slopeFind | dropOff | hole DETECTED to | physically REACHES |
|---|---|---|---|---|
| water | 4 | 1 | **5** | **7** |
| lava (overworld) | 2 | 2 | 3 | 3 |
| lava (nether/ultrawarm) | 4 | 1 | 5 | 7 |

**Water has a 2-cell blind band at 6–7**: it reaches a hole it cannot see, so every direction ties at
1000 and it spreads everywhere. *Owner-measured independently* (hole ≤5 → flows only toward it; hole at
6–7 → spreads laterally anyway).

**`<=` means TIES ALL WIN — and this, not the winner, is what we actually need.** The loop keeps every
direction whose distance equals the running minimum (`map.clear()` only on a *strictly* smaller one), so
the result is a direction SET, not a single choice.

**Distance is shortest-path over passable cells — Manhattan in open terrain, never Euclidean.** The
recursion walks horizontal neighbours, excludes the backtrack (`d.getOpposite()` is passed down), and
returns the smallest depth at which a hole appears. *Owner-verified twice:*

- **Two holes, each 3 away** (opposite, or at 90°) → both tie → water runs in **two** straight lines.
- **One hole 1 south + 3 east** (Manhattan 4) → east and south both return depth 3 → both flow, and every
  cell on any shortest path fills, producing a **2×4 rectangle**. Euclidean distance cannot explain this
  shape; Manhattan predicts it exactly.

⇒ **For our question — "does the broken cell receive fluid?" — only the tie test matters.** We never need
the winning direction, the full flood shape, or the path fluid takes. The predicate is:

> Does the broken cell's direction **tie or beat** the minimum over the other candidate directions?

A tie is a YES. That is a much smaller question than "simulate the spread", and it is the only one §5's
tier 2 answers.

**Flow direction is not persisted to the blockstate.** The variables that dictate it — slope distances,
hole positions, the winning direction — are recomputed from live geometry on every evaluation and stored
nowhere. (`SpreadContext` is `new`'d inside `getSpread`, caches only block states and hole tests by
positional offset, and dies with the call.) The observed "beeline to a hole" is emergent, not remembered.

**⇒ Knowing whether fluid will flow in a given direction REQUIRES running the full decision logic.** There
is no cheap stored fact to read instead, at any level of the game's own model. This is the reason §2
concludes the search must be reimplemented rather than looked up.

### §1.2 What can RECEIVE flow

`canMaybePassThrough(dir, …)` = `!isSourceBlockOfThisType(neighbourFluid)` ∧ `canHoldAnyFluid(neighbourState)`
∧ `canPassThroughWall(…)`. Note the first clause: **a direction whose neighbour is already a source of
this fluid is excluded from the spread map entirely.**

`canHoldSpecificFluid` → for a `LiquidBlockContainer`, `SimpleWaterloggedBlock.canPlaceLiquid` is:

```java
return fluid == Fluids.WATER;      // reference identity against the SOURCE singleton
```

Lateral spread carries `getFlowing(amount - dropOff, false)`, whose type is `Fluids.FLOWING_WATER` — a
**different object**. Therefore:

> **Flowing fluid can never enter a dry waterloggable block.** Slabs, stairs, fences, walls, panes,
> trapdoors and ladders are all **walls** to lateral spread.

*Owner-verified:* water stops dead at a bottom slab in a trench; an unwaterlogged trapdoor beside a
source behaves exactly like a full block.

**Post-implementation bytecode addendum (2026-08-17, adversarial review):** the wall property holds for
the *entire slope competition*, not just entry. `getSpread`'s direction loop gates each candidate on
`canMaybePassThrough` AND `canHoldSpecificFluid(getNewLiquid(...))` *before* `isHole`/`getSlopeDistance`
ever run, and `getSlopeDistance`'s recursion gates every neighbour on `canPassThrough` =
`canMaybePassThrough ∧ canHoldSpecificFluid(getFlowing())` — reference-identity-false for every
`SimpleWaterloggedBlock`. So dry waterloggable partials never lower any direction's minimum, and the
funnel's passable-shape reduction (`NavBlock.isPassable` in `canPassInto`) matches vanilla for them
exactly. The residual divergence runs the OTHER way: `canHoldAnyFluid` excludes doors, signs, ladders,
sugar cane, bubble columns and portals, which the descriptor reads as passable — the funnel can see a
drain path vanilla cannot, predicting dry where vanilla floods. Errs dry, recoverable (already the §8
zero-collision-décor row).

### §1.3 `canPassThroughWall` — face occlusion, both sides

```java
if (toState.getCollisionShape(...) == Shapes.block()) return false;   // neighbour full
if (state.getCollisionShape(...)   == Shapes.block()) return false;   // OWN cell full
if (both == Shapes.empty()) return true;
return !Shapes.mergedFaceOccludes(ownShape, neighbourShape, dir);
```

The fluid's **own** shape can wall it in — which is how a waterlogged partial block behaves.
*Owner-verified* on a waterlogged trapdoor: the panel's orientation decides per-face, exactly as
`mergedFaceOccludes` predicts —

| trapdoor | shape | DOWN face | lateral face |
|---|---|---|---|
| `open=false` (horizontal, on the floor) | `box(0,0,0, 1, 3/16, 1)` | **fully covered** → no flow down | 3/16 strip → flows laterally |
| `open=true` (vertical panel) | 3/16 thick, full height, one side | 3/16 strip → flows down | **fully covered** on the facing side → no flow that way |

### §1.4 Fluid arrives LATE — the spread delay

`getSpreadDelay` returns `getTickDelay`, per fluid and dimension (bytecode-verified):

| fluid | ticks per spread step |
|---|---|
| water | **5** |
| lava, overworld | **30** |
| lava, nether (ultrawarm) | 10 |

So a predicted flood is **not** present the instant the block breaks: the cell is genuinely air for at
least 5 ticks (water), and longer per cell of travel — fluid three cells away takes ~15. **The diff and
the world therefore disagree for a bounded window after every fluid-verdict break.** Consequences in §8.3.

### §1.5 Fluid is event-driven (the BUD rule)

A fluid cell re-evaluates **only** on a scheduled tick or a neighbour block update, and block updates
reach the **6 orthogonal neighbours** of the changed cell. *Owner-verified:* digging a hole beside a
draining source changed nothing until an unrelated block placement delivered an update, at which point
the source immediately re-targeted the nearer hole.

**Two consequences, and they are the load-bearing ones for this whole design:**

1. **The fluid in the NavGrid is stable by construction.** A settled world stays settled. No fluid
   simulation is needed to keep the grid honest.
2. **The only flooding the planner must predict is flooding the bot itself causes** — its break *is* the
   update. Nothing else spontaneously changes its mind.

A corollary that killed an earlier draft: the flood does **not** trail a descending dig. Breaking `E-1`
notifies `E`, `E-2` and `E-1`'s laterals — **not** the fluid beside `E`. So each level's flood decision is
made once, when that level is broken, and at that instant the cell below is still solid ⇒ **`E` is never a
hole at the moment of its own break.** It always competes on slope distance; it never wins at 0.

---

## §2 What is storable, and what is not

| fact | nature | home |
|---|---|---|
| is this fluid a source | per-blockstate | **descriptor bit** |
| is this fluid at minimum level | per-blockstate | **descriptor bit** |
| is any fluid orthogonally adjacent | per-location, 6-neighbour dilation | **flag bit** (scatter, like the lava term) |
| **drain distance / "already draining"** | 5-deep search over live geometry | **NOT STORABLE** |
| **break ORDER** | a property of the plan, not the world | **NOT KNOWABLE at classify time** |

Why drain distance cannot be a bit: it is a property of the *neighbourhood*, not the block, so it cannot
be a navtype (two byte-identical water blocks differ ten metres apart). As a per-location flag it is
expressible but its invalidation radius is the slope search — **a single edit dirties ~500 cells**, against
the ~10 the scattered-flags design is budgeted for. That is a two-orders-of-magnitude blowout landing on
the `PatchStorm` path.

⇒ **The search must be lazy: computed at candidate-emission time, never at classify time.** Build-time
would pay for every water cell in every loaded chunk (an ocean section is ~4096 searches); lazy pays only
for breaks actually folded next to fluid. See §5.

---

## §3 Descriptor bits (2 new)

> **SUPERSEDED IN PART, 2026-08-20 (owner-ratified).** `FLUID_MIN_LEVEL` was widened from one bit into the
> 3-bit **`FLUID_LEVEL`** field (descriptor bits 53–55) holding the fluid's exact `amount` (1–8, stored as
> `amount - 1`); `NavBlock.isFluidMinLevel` survives unchanged as the derived predicate `fluidLevel(d) == 1`,
> so every consumer in this document still reads correctly and the funnel below needed no edit. `FLUID_SOURCE`
> at bit 52 is untouched. The driver was the eye-submersion gate on `StartSprintSwim`: vanilla grants the
> prone swim pose only when the fluid SURFACE clears `getEyeY()`, and a flowing block's surface is `amount/9`
> of its cell, so the *exact* amount — not merely "is it minimal" — is what the swim moves need. Keeping the
> whole amount rather than adding a second threshold bit means the next movement that needs a level costs no
> further descriptor bits. Cost: water and lava each fan from 3 navtype groups to 9. Measured **+12 navtypes
> on both a live 26.2 server (519 → 531) and headless 1.21.11 (483 → 495)** — the delta is version-independent
> because every version has the same eight fluid amounts. See `NavBlock`'s bit-layout table for the encoding
> and `MovementContext.eyesSubmergedWithHeadIn` for the physics derivation.

- **`FLUID_SOURCE`** — the fluid state is a source (`amount == 8`).
- **`FLUID_MIN_LEVEL`** — `amount == 1`, i.e. one more lateral step yields amount 0. *(Now derived from
  `FLUID_LEVEL`; see the note above. Note that `amount == 8` alone does NOT mean source — falling water is
  amount 8 and not a source, which is why sourcehood keeps its own bit.)*

**Deliberate choice: `FLUID_MIN_LEVEL` is `amount == 1`, fluid-agnostic.** Overworld lava (`dropOff=2`)
also cannot spread at amount 2, but `getDropOff` is **dimension-dependent** (`isFastLava` → nether lava is
`dropOff=1`), and the descriptor is interned globally, not per-dimension. Defining the bit on the true
minimum is exact for water and for nether lava, and merely **conservative** for overworld lava at amount 2
(we assume it can spread when it cannot) — which is the safe direction for lava. Do not "fix" this by
making the bit dimension-aware; that breaks navtype interning.

Both bits SPLIT navtypes (they are base fields, not derived — see the `worldmodel-data-model` memory):
water and lava each gain source/flowing/min variants. Within the ≤1024 cap with room to spare.
**Never record the resulting count as fact** (`no-hardcoded-navtype-counts`).

---

## §4 Flag bit: `HAS_FLUID_NEIGHBOR` (repurposes bit 4)

Takes `PLACEABLE_NEIGHBOR`'s slot — maintained since s17, read only by `/bot probe`, and computing a
predicate (`!isPassable ∧ fluid==0`) that disagrees in both directions with the `supportsPlacement`
(`!isReplaceable`) fan-out that ignores it. Retire it; nothing in `src/main` regresses.

Computed by **scatter**, mirroring the existing lava term in `NavSectionBuilder.computeDepth`
(PERF-DESIGN-navgrid-build §C1) — `NavFlags.compute` reads an upward-only scratch and structurally cannot
see below or laterally across a section seam, which is why the lava term is already scattered rather than
gathered. Same shape, same seam handling, same patch-path re-derivation.

**Frame: cell-centred** — "breaking THIS cell may admit fluid." Every consumer must read it **at the cell
it actually breaks**: `MineDown`/`Pillar` at their own target, `Traverse`'s `breakThrough` at `F+1`/`F+2`,
**not** at `F`. This is the explicit lesson of the `RISKY_EDIT` frame mismatch (underwater-mining backlog
OPEN 2) — do not create a fourth frame.

### §4.1 The lava term migrates — RATIFIED (owner, 2026-08-17)

`RISKY_EDIT` (bit 0) currently carries **gravity ∪ lava**, OR-composed. The lava half moves here, leaving
**bit 0 strictly gravity**. This does not free a bit; the gain is that it **resolves half the frame
mismatch** (underwater-mining backlog OPEN 2) — the gravity term is body-space-framed and the lava term
cell-centred, and sharing one bit is what made the three-frame problem unfixable. Each now has one
coherent frame.

### §4.2 Lava is PRICED, not forbidden — RATIFIED (owner, 2026-08-17), reversing §6's earlier DISALLOW

An earlier draft made a lava neighbour an unconditional refusal, on the grounds that a wrong answer about
lava costs the bot rather than a detour. **Owner overruled it, and the objection is decisive: the codebase
already prices lava.** `NavBlock`'s damaging bit, `BotCaps.takesDamage`, and the one
`pathing.costPerHitpoint` currency exist precisely so hazards are costed rather than special-cased; a
damage-immune bot is already permitted to swim in lava. A blanket DISALLOW here would contradict both
`physically-derived-costs` and the standing ruling that *a costing function does not subsume a correctness
problem*.

So lava runs the **same funnel** as water, and the verdict set gains a third fluid outcome
(§6): predicted lava is written as lava, and the existing damage pricing makes it ruinously expensive for
a mortal bot and merely slow for an immune one. That is the honest model, and it is strictly more capable
than the keep-away it replaces (which could not express "cross the lava, you are fireproof").

Two consequences to respect:

- **A new `PathEdits` kind is needed** — `BROKEN_LAVA` beside `BROKEN_WATER`. It reads as lava, not water:
  different damage, different transit slow (`NavBlock.TRANSIT_FLUID`), no prone pose, `slopeFind`/`dropOff`
  of 2/2 overworld.
- **Lava's arrival lag is 30 ticks overworld** (§1.4), six times water's, which makes §8.3's diff-leads-world
  window far wider. This is the one place where lava genuinely does need different treatment from water —
  not in the verdict, but in how long the disagreement lasts.

---

## §5 The evaluation funnel

Mirrors the codebase's standing resolution-layer pattern (region cost → grid flags prune → NavBlock
geometry decides). Nearly every break exits at tier 0.

**Tier 0 — free, from bits already read**

- fluid directly above the break ⇒ **flows, certain — and per-fluid** (owner-ratified 2026-08-17).
  Vanilla falling fluid always fills the cell below, water and lava alike. This generalizes the EXISTING
  `PathEdits.BROKEN_WATER` rule (`EditScratch` wet-above): water above ⇒ `BROKEN_WATER`, **lava above ⇒
  `BROKEN_LAVA`**. The shipped `wetFromAbove` tests `ctx.water` ONLY, so digging directly under lava
  today folds plain `BROKEN` (air) — a real hole this design closes; lava-above is the most certain
  flood there is. The rule stays a direct descriptor read rather than folding into this bit (see §8.1).
- `HAS_FLUID_NEIGHBOR` clear ⇒ **cannot flow.** Exact against COMMITTED state — blind to fluid the
  plan itself created (a folded `BROKEN_WATER`/`BROKEN_LAVA` neighbour never sets the flag; see the
  §8 table row and its deferral).
- target cell is not genuinely empty ⇒ **cannot receive.** Exact for full blocks and dry waterloggable
  partials (§1.2); errs dry for zero-collision décor (§8).

**Tier 1 — cheap, ~4–6 reads per fluid neighbour `W`.** Ordered cheapest-first. Lava runs the same path
as water (§4.2); only the fluid CONSTANTS differ (`slopeFind`/`dropOff` 2/2 overworld vs 4/1) and the
verdict it produces:

```
if (!genuineOpenFluidCell(W))      continue;   // waterlogged partial — see §8.2
if (FLUID_MIN_LEVEL(W))            continue;   // cannot spread at all
if (canFlowDown(W))                            // below-W empty, READ THROUGH PathEdits
     eligible = sourceNeighborCount(W) >= 3;
else eligible = FLUID_SOURCE(W) || !isWaterHole(W);
if (!eligible)                     continue;
```

`eligible` is necessary, not sufficient (§1.1) — every survivor still has to tie or beat the minimum in
tier 2.

`canFlowDown` **must** read through `PathEdits`, not the raw grid — the plan's own earlier breaks are what
open that path. (In practice the ≥3 branch is near-unreachable for the bot: mining takes many ticks and
fluid settles in one or two, so the below-cell is refilled long before the next break. Model it anyway; it
is 4 reads and it is the difference between correct and phantom.)

**Tier 2 — rare, bounded: the slope-distance minimum**

Only for survivors, and it answers **one** question: does the broken cell **tie or beat** the minimum over
the other candidate directions (§1.1)? Ties win, so the test is `<=`, and no direction needs to be
selected — we never compute the flood shape or the path fluid takes.

Mirror `getSlopeDistance` over the NavGrid: shortest path length through passable cells, backtrack
excluded, depth-capped at **one unified `slopeFind = 4` for BOTH fluids** (owner-ratified 2026-08-17;
see the pin below). Bounded by construction — branching 3, depth 4, early exit on a hole or a
non-passable cell (~100 reads worst case, typically far fewer).

**The lava slope pin: NETHER's 4, not overworld's 2** (owner-ratified 2026-08-17). Dimension identity
is unreachable from candidate emission (the planner-thread `NavGridView` nulls its level; threading a
dimension fact would ripple through the `findPath` ladder or pollute the caps realizability sig), so
one constant must serve both dimensions — and the tie arithmetic decides which. Ties win, so a SMALLER
`slopeFind` can only remove drain detections, which can only flip verdicts toward WET: pinning
overworld's 2 would predict floods in the nether that real nether lava (slopeFind 4) drains away —
**phantom fluid, the §8-unrecoverable error class**. Pinning 4 instead errs DRY in the overworld
(a drain at slope distance 4–5 is visible to the model but not to real overworld lava, so the dig is
predicted dry and real lava floods in) — recoverable by §8's principle: the arrival is a real block
change → invalidation → replan, with the 30-tick arrival lag as escape margin. Nether lava and all
water are exact. Bonus: water and lava share one constant, so tier 2 has no per-fluid depth branch.

**No memoisation** (owner-ratified 2026-08-17, reversing an earlier draft's per-search memo). Tier 2 is
rare and bounded, so the memo bought little — and a memo keyed on the fluid cell is UNSOUND: the slope
search reads through the path's own edits (below), which differ per *branch* of the search, not per
search. Two pops on different branches with different folded breaks near the same fluid cell would share
one cached verdict, and the stale answer can err WET — the unrecoverable direction by §8's own principle.

**Read discipline — the funnel sees the diff AND the candidate's own scratch, at every tier**
(owner-ratified 2026-08-17):

- All reads go through `descriptorAt` (which layers `PathEdits`), never the raw grid — the plan's own
  earlier breaks/places are what open and seal flow paths.
- The candidate's own **in-scratch folds** must be consulted FIRST, before `descriptorAt` — in-scratch
  edits are invisible to `descriptorAt` (the exact lesson that forced the MineDown macro wet-column
  latch). A `Traverse` folds two breaks per step; the second cell's verdict must see the first's fold.
  `wetFromAbove` already does scratch-first-then-descriptor reads; the lateral funnel follows the same
  discipline.
- The **broken cell itself reads as open** in its own slope competition (it is the candidate direction
  being evaluated), while per §1.5 it is never a hole at the moment of its own break — the cell below it
  is still solid when the verdict is computed.

---

## §6 Output: THREE return states

The funnel is not a boolean. It returns one of:

| verdict | the break records | meaning |
|---|---|---|
| **`AIR`** | `PathEdits.BROKEN` | nothing eligible, **or** the cell's direction loses the minimum outright |
| **`WATER`** | `PathEdits.BROKEN_WATER` | the cell reads as water for every later pop on this path |
| **`LAVA`** | `PathEdits.BROKEN_LAVA` *(new kind)* | the cell reads as lava — priced, not forbidden (§4.2) |

Note the `AIR` condition precisely: **losing outright**, not "failing to win". A tie is a flow (§1.1), so
the tier-2 test is `<=` and any tied direction returns fluid.

`WATER` reuses the kind and readers that shipped for the vertical rule. `LAVA` needs a new kind, because
lava is not water to any consumer — different damage, different transit slow, no prone pose, different
spread constants.

**The diff constants read as SOURCES, deliberately** (owner-ratified 2026-08-17). `WATER_DESC` (and the
new `LAVA_DESC`) are built from `defaultBlockState()`, which is the source state — so once
`FLUID_SOURCE` exists, every diff-flooded cell claims sourcehood to later tier-1 evaluations on the same
path. This errs wet (a source is always spread-eligible), which is the conservative direction — and it
is often literally CORRECT, not merely conservative: a broken cell with two adjacent source neighbours
genuinely becomes a new source (vanilla's 2-adjacent-sources infinite-water rule, `canConvertToSource` —
§11.3). Do not "fix" this by swapping in a flowing-state descriptor.

**No verdict refuses the break — there is no feasibility case here at all.** Owner ruling, standing: *"a
costing function does not subsume a correctness problem — there will always be an edge case where the high
cost is worth it."* Digging while submerged is frequently the only route, and crossing lava is legitimate
for a fireproof bot. Both are **priced**; neither is forbidden.

**Correction (2026-08-17, adversarial review): the existing pricing machinery does NOT cover the dig
path — one extension is required.** The earlier claim that `costPerHitpoint`/`takesDamage`/the damaging
bit "need no extension" is true only for *transit through committed lava* (the swim family's
`lavaSwimCellCost`). Diff-created lava is invisible to the flags-based transit prefilters (`flagsAt`
never layers `PathEdits` — the §8 deferred row), and `MineDown`/`Pillar` call no transit pricing at all
— so without an explicit term, a `BROKEN_LAVA` verdict would choose the *kind* while adding **zero
cost**, and a mortal bot would price a lava-flooding shaft identically to a dry one: a free lethal
offer, strictly worse than the keep-away it replaced. The required extension is `MineDown`'s per-level
**lava-exposure term**: for each dig level whose occupied cells read lava through scratch+diff, charge
the level's mining ticks × the vanilla lava damage rate × `costPerHitpoint`, gated on
`BotCaps.takesDamage` (an immune bot charges nothing — the §4.2 split preserved). Physically derived,
macro==micro, and confined to the one move whose own dig creates the lava it then stands in; other
moves' diff-fluid blindness stays with the deferred PathEdits-scatter row.

---

## §7 Integration points

- **`EditScratch` / `PathEdits`** — the lateral case emits the existing `BROKEN_WATER` kind (wet flags
  already thread `StepEdits` → `PathEdits` / `EditSnapshot` (splice) → `sliceStep`), **plus a new
  `BROKEN_LAVA` kind** (§4.2) which needs the same threading and a `LAVA_DESC` resolution beside
  `WATER_DESC` in `descriptorAt`/`descriptorOf`.
- **`NavGridUpdater` / `PathPlan`** — the per-cell edit-expectation set (§8.3, §11.4). The prerequisite,
  and the only part of this design that reaches outside the block tier.
- **`MineDown` wet-column latch** (`MineDown.java:110`) — currently `ctx.water(descriptorAt(x, y+1, z))`,
  vertical only. It should latch on the funnel's verdict per level, not just on fluid above.
- **`MovementContext.breakCost`** — no change; the stance multiplier already keys off the node's wet
  state, which the diff now reports honestly.
- **Region tier** — nothing. Fluid membership is unchanged; only per-break cost moves.
- **Persistence** — new descriptor bits change classification, which changes the realizability signature
  schema. Per the standing owner ruling (`pre-release-no-version-bumps`) do **not** bump the constant;
  append an entry to `CostPyramidCodec`'s sig-schema HISTORY Javadoc.

---

## §8 Deliberate inexactness, and the principle that licenses it

> **The only unrecoverable prediction error is one where reality produces NO block change.**

Every other error emits a block update, which reaches `NavGridUpdater`, which invalidates and replans.
Phantom fluid is uniquely dangerous precisely because *nothing happens*: no block changes, so no update
fires, and the stale belief persists until the bot physically fails (planning a swim through air).

| accepted inexactness | direction | recoverable? |
|---|---|---|
| **§8.2** waterlogged partials ignored as spread SOURCES | errs dry | ✅ real block change → invalidation |
| zero-collision décor (torch/flower/rail) ignored as spread TARGETS | errs dry | ✅ |
| cascades past the first cell not modelled | errs dry | ✅ |
| water→lava conversion (stone/cobble/obsidian) not modelled | errs *solid*, not dry | ✅ a real block appears |
| overworld lava at amount 2 treated as able to spread | errs wet, **lava only** | conservative by intent |
| overworld lava beside a drain at slope distance 4–5 (the unified `slopeFind=4` nether pin, §5) sees a drain real overworld lava cannot | errs dry, **lava only** | ✅ the real flood is a block change → invalidation; 30-tick lag as escape margin |
| tier-0 flag early-out blind to PLAN-created fluid (`flagsAt` never layers `PathEdits`, so a folded `BROKEN_WATER`/`BROKEN_LAVA` neighbour never sets `HAS_FLUID_NEIGHBOR`) | errs dry | ✅ the real flood is a block change → invalidation. **KNOWN AND DEFERRED to the PathEdits-scatter workflow** (underwater-mining backlog OPEN 3, still unimplemented) — do NOT fix ad-hoc here by scattering flags through the diff |

### §8.1 Keep the certain and the speculative in different mechanisms

The "fluid above" rule is the one **certain** clause and it is licensed to write geometry. The lateral
funnel is a prediction. Do **not** merge them into one bit or one predicate: a consumer that cannot tell
which fired must treat all cases alike, and either bakes speculative fluid (phantom water) or loses the
vertical rule's legitimate swim edges.

### §8.2 Waterlogged sources — owner-ratified inexactness (2026-08-16)

Correctly modelling a waterlogged block as a spread source requires per-face occlusion (§1.3) — facing,
half, and open state, per direction. **Owner ruling: not worth the complexity.** Naturally-generated
waterlogged blocks are essentially confined to sunken shipwrecks and ocean ruins, and in those the
surrounding open water dominates the prediction anyway. Excluding them errs dry, which self-corrects.

Accepted cost: a bot may mine beside a waterlogged stair and take an unexpected flood plus a replan. That
is the cost of doing business, explicitly.

### §8.3 The arrival lag — the diff leads the world by ≥5 ticks

A fluid-verdict break writes fluid into `PathEdits` immediately, but vanilla delivers it 5 ticks later for water
(30 for overworld lava), plus 5 more per cell of travel (§1.4). Two subsystems see the disagreement.

**Replan churn — NEW MACHINERY IS REQUIRED.** (An earlier draft claimed the existing epoch model absorbed
this. **Owner overruled it and the analysis was wrong** — recorded here because the failure is subtle and
the wrong conclusion is the intuitive one.)

The existing model is only benign when the world ends up matching the diff. Here it does not, *twice*:

1. The bot breaks the block. The world writes **air**; the diff said **water**. That is an
   **unexpected edit** — the observed state does not match what the plan folded — so the plan invalidates
   and re-searches.
2. That re-search plans with **air** in the cell — so the new plan may commit to a route straight through a
   cell that is about to change, offering a different set of affordances than the ones it was planned on
   (walk/parkour/fall through air, versus swim rungs, no sprint, and 5× mining once the water arrives).
3. Five ticks later the water lands. Another unexpected edit → **a second invalidation**, discarding the
   plan built in step 2.

Net: **two invalidations and one plan built on a state that was known in advance to be wrong.** The
`BotNavigator:74` "redundant-but-correct" note does not cover this, because that note assumes the
re-search re-derives *the same* route the diff already anticipated. Here step 2 deliberately derives a
different one.

**The fix (owner-ratified): a fluid-folded break expects EITHER outcome.** When the plan folds
`BROKEN_WATER`/`BROKEN_LAVA` at a cell, the expectation registered for that cell is the **set**
`{air, that fluid}` — and neither observation invalidates. The air phase is then not an unexpected edit at
all; it is the documented first half of a two-phase edit the planner already predicted.

Properties that make this the right shape:

- **State-based, not a timer.** It is an expectation set keyed on a cell, released when the cell leaves the
  plan's unexecuted suffix — never a tick countdown, so `no-arbitrary-timers` is satisfied. "Wait 5 ticks
  for the water" would violate it.
- **It only widens what is already expected.** A break the plan did not fold still invalidates normally, so
  genuine world changes (another player, a mob, lava conversion) are unaffected.
- **It is honest about the lag rather than hiding it.** The plan's route is the one computed *with* fluid,
  which is the state that persists; the transient air state never gets a plan of its own.

Where it lives: the same seam that already distinguishes own-edits from foreign ones. Today that
distinction is level-global (`editEpoch`), so a per-cell expectation set is the new part — and it is the
prerequisite for this design, not an optimisation. **Lava makes it mandatory rather than merely correct:**
30 ticks overworld (§1.4) plus 5 per cell of travel puts the arrival comfortably outside
`TERRAIN_RECHECK_TICKS` (40), so the two invalidations are guaranteed to be distinct re-searches.

**Executor — the real exposure, and it is NOT new.** A move that consumes the predicted fluid as an
affordance can run while the cell is still air. Note this window **already exists in shipped code**: the
vertical `BROKEN_WATER` rule (core `454c28c`, 2026-08-16) has exactly the same lag and, as far as this doc's
author can tell, no test covers it. Grade the exposure by what the move wants the fluid *for*:

| use | during the lag | severity |
|---|---|---|
| descend into the broken cell (MineDown) | a 1-block fall through air instead of water | harmless — the bot was moving there regardless |
| swim UP / buoyancy out of the cell | the move cannot act; positional `failWhen` trips | recoverable — ratified fail→log→HOLD, then the fluid lands and the replan proceeds |
| fluid as a soft landing for `Fall` | in principle, fall damage the planner priced away | **CLOSED — geometrically unreachable, see below** |

**`Fall` softness is not exposed** (owner ruling, 2026-08-17). For predicted fluid to serve as a soft
landing the bot would have to break a cell far enough below itself that the drop hurts (>3 blocks) *and*
still be able to reach it — but survival reach (~4.5 blocks from the eye) is approximately the same
distance at which fall damage begins, so **the bot essentially cannot create its own water landing.** The
window exists on paper and not in reachable geometry. No guard needed.

**Do not fix the remaining rows with a timer.** "Wait N ticks for the water" is exactly the tick-counter
recovery `no-arbitrary-timers` forbids, and the ratified alternative already covers it: per-move validity
envelopes are POSITIONAL, so a move whose precondition is not yet met fails its envelope, logs once, and
HOLDS — the correct behaviour here, because the precondition becomes true on its own.

---

## §9 Tests

- **Descriptor:** `FLUID_SOURCE` / `FLUID_MIN_LEVEL` set on the right states across the version range;
  navtype split does not exceed the cap; **no test asserts a navtype COUNT**. (2026-08-20: the same
  assertions now cover the derived `FLUID_LEVEL` field, plus exact per-amount round-trips — the fact that
  the pre-existing min-level pins pass unchanged against the derived accessor is itself the regression
  evidence for the widening.)
- **Flag:** `HAS_FLUID_NEIGHBOR` scatter matches the lava term's seam behaviour in both vertical
  directions; patch-path re-derivation agrees with a full rebuild (the `PatchStorm` identity property).
- **Funnel, tier 1:** the steady-state table (§1.1) — source spreads; mid-column flowing does not;
  flowing-on-solid does; min-level never does.
- **Funnel, tier 2:** the owner's two fixtures, as unit scenes over a synthetic grid —
  (a) **beeline**: source with a hole at distance 3 admits only the hole direction, refuses a lateral
  break; (b) **blind band**: hole at distance 6 is NOT found ⇒ all directions tie ⇒ the break floods.
  These two pin the `slopeFind=4 ⇒ detect-5` arithmetic, which is the single easiest thing to get wrong.
- **Receiving side:** a dry slab/stair/trapdoor adjacent to a source refuses lateral flow (§1.2).
- **Integration:** a `MineDown` column beside pooled water prices every level submerged; beside a
  *draining* stream it does not.
- **Read discipline:** a verdict that depends on the plan's own earlier edits — e.g. an earlier folded
  break opens the drain that flips a later break's verdict from `WATER` to `AIR` — resolves against the
  diff, and against the SAME candidate's in-scratch folds (the two-break `Traverse` step). Pins the
  no-memo ruling and the scratch-first rule (§5).
- **Vertical rule, per-fluid:** digging directly under lava folds `BROKEN_LAVA` (today it folds plain
  `BROKEN` — the shipped water-only `wetFromAbove` hole this design closes).
- **Arrival lag (§8.3):** a fluid-verdict break followed immediately by a move that needs the fluid — assert
  fail→log→HOLD rather than a crash or a silent wrong-affordance, and assert the plan recovers once the
  fluid lands. **This test also covers the shipped vertical `BROKEN_WATER` rule, which currently has no
  coverage of its own lag window.**
- **Lava:** a lava neighbour runs the same funnel as water and yields `LAVA` → `BROKEN_LAVA` (§4.2);
  the slope search uses the unified `slopeFind=4` (the nether pin, §5), so a drain at 4 saves a lava
  dig exactly as it saves a water dig — and the overworld errs-dry consequence of that pin is a
  documented accepted inexactness, not a bug. Assert a mortal bot's route avoids an undrained lava dig
  on price alone and a `takesDamage=false` bot accepts it — the behaviour a blanket refusal could not
  express.
- **Regression:** `PatchStorm` for grid-maintenance cost; the JMH suite under the paired-interleaved
  A/B protocol for the search itself, with `SHORT`/`MULTI` as the setup guards.

---

## §10 Deferred / out of scope

- Modelling waterlogged sources exactly (§8.2).
- Flow cascades beyond the directly-broken cell.
- Water/lava block conversion.
- Any attempt to precompute drain distance (§2 — it is the wrong shape for both a navtype and a flag).
- Modelling BUD itself. BUD is *why the snapshot is valid*, not a phenomenon to encode; encoding it means
  tracking a counterfactual world, which is a second world model and was rejected for the same reason the
  "certainty" concept was.

---

## §11 Open / unverified — read before implementing

1. **`SpreadContext.isHole` vs `isWaterHole`.** Tier 2 mirrors `getSpread`, which calls `ctx.isHole`; only
   `isWaterHole` was disassembled. Confirm they agree (particularly on below-is-same-fluid) before
   reimplementing the minimum.
2. **Version drift.** 1.21.11 verified, 1.20.1 structurally agreed, everything between unchecked. The
   `spread()` shape is the thing to re-confirm at the era boundaries.
3. **`getNewLiquid`'s full body** was only partially read (the `amount - dropOff` arithmetic). Its
   source-conversion branch (`canConvertToSource`, the 2-adjacent-sources infinite-water rule) is
   unexamined and may matter for whether a broken cell becomes a *source* rather than flowing fluid.
   Note the stakes are now low: §6's diff constants already read as sources (owner-ratified), which is
   the direction `canConvertToSource` would produce — reading the branch can refine tier-1 exactness
   but cannot break the model.
4. **The per-cell edit-expectation set** (§8.3) is new machinery this design REQUIRES, not an optimisation.
   Today own-vs-foreign edit distinction is level-global (`editEpoch`); a fluid-folded break needs a
   per-cell expectation of `{air, fluid}` so neither phase invalidates. Scope it before implementing —
   it touches `NavGridUpdater`/`PathPlan`, not just this funnel.
