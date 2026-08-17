# DESIGN — Fluid-flow prediction ("will breaking this cell admit fluid?")

Status: **drafted, not implemented** (owner brief 2026-08-16). Companion to
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
decides only whether `spreadToSides` is *called at all*. A cell that passes still spreads to **no**
direction, or to only one, depending entirely on mechanism 2. Nothing in this section ever means "fluid
WILL arrive" — only mechanism 2 can say that, and only about a specific direction.

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
the world therefore disagree for a bounded window after every `FLOWS` break.** Consequences in §8.3.

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

- **`FLUID_SOURCE`** — the fluid state is a source (`amount == 8`).
- **`FLUID_MIN_LEVEL`** — `amount == 1`, i.e. one more lateral step yields amount 0.

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

**Lava never enters the search.** Its error tolerance is not water's: a wrong answer about water costs a
replan, a wrong answer about lava costs the bot. So lava is the **first** check in the funnel and it
short-circuits — a lava neighbour is an immediate DISALLOW, no eligibility test, no slope search. The
blunt keep-away behaviour is preserved exactly; only its storage location changes.

---

## §5 The evaluation funnel

Mirrors the codebase's standing resolution-layer pattern (region cost → grid flags prune → NavBlock
geometry decides). Nearly every break exits at tier 0.

**Tier 0 — free, from bits already read**

- fluid directly above the break ⇒ **flows, certain.** Vanilla falling fluid always fills the cell below.
  This is the EXISTING `PathEdits.BROKEN_WATER` rule (`EditScratch` wet-above); unchanged, and it stays a
  direct descriptor read rather than folding into this bit (see §8.1).
- `HAS_FLUID_NEIGHBOR` clear ⇒ **cannot flow.** Exact.
- target cell is not genuinely empty ⇒ **cannot receive.** Exact for full blocks and dry waterloggable
  partials (§1.2); errs dry for zero-collision décor (§8).

**Tier 1 — cheap, ~4–6 reads per fluid neighbour `W`.** Ordered cheapest-first, and **lava first of all**
(§4.1) so it never reaches the search:

```
if (isLava(W))                     return DISALLOW;   // §4.1 — short-circuit, lava is never searched
if (!genuineOpenFluidCell(W))      continue;          // waterlogged partial — see §8.2
if (FLUID_MIN_LEVEL(W))            continue;          // cannot spread at all
if (canFlowDown(W))                                   // below-W empty, READ THROUGH PathEdits
     eligible = sourceNeighborCount(W) >= 3;
else eligible = FLUID_SOURCE(W) || !isWaterHole(W);
if (!eligible)                     continue;
```

`eligible` is necessary, not sufficient (§1.1) — every survivor still has to win tier 2.

`canFlowDown` **must** read through `PathEdits`, not the raw grid — the plan's own earlier breaks are what
open that path. (In practice the ≥3 branch is near-unreachable for the bot: mining takes many ticks and
fluid settles in one or two, so the below-cell is refilled long before the next break. Model it anyway; it
is 4 reads and it is the difference between correct and phantom.)

**Tier 2 — rare, bounded: the slope-distance minimum**

Only for survivors. Mirror `getSpread` over the NavGrid: compute the distance to the target cell and to
each competing direction, and admit the target iff it ties or beats the minimum. Bounded by construction —
branching 3, depth 4, early exit on a hole or a non-passable cell (~100 reads worst case, typically far
fewer). Memoise per search, keyed on the fluid cell.

---

## §6 Output: THREE return states

The funnel is not a boolean. It returns one of:

| verdict | the break records | meaning |
|---|---|---|
| **`NO_FLOW`** | `PathEdits.BROKEN` (air) | nothing eligible, or nothing wins the direction tie |
| **`FLOWS`** | `PathEdits.BROKEN_WATER` | the cell reads as fluid for every later pop on this path |
| **`DISALLOW`** | *no candidate emitted* | a lava neighbour (§4.1) — the break is refused outright |

`FLOWS` reuses the kind and the readers that shipped for the vertical rule — no new edit kind, no new
consumer. Later pops see honest water: swim moves offered, `standable` false, submerged stance priced.

**`FLOWS` does not refuse the break.** Owner ruling, standing: *"a costing function does not subsume a
correctness problem — there will always be an edge case where the high cost is worth it."* Digging while
submerged is frequently the only route; the model makes it **priced**, never forbidden.

**`DISALLOW` is the deliberate exception**, and the only one. It is a *feasibility* verdict, not a price,
because lava's failure mode is death rather than a detour (§4.1). It is exactly the blunt keep-away that
`RISKY_EDIT`'s lava term performs today — preserved, not introduced.

---

## §7 Integration points

- **`EditScratch` / `PathEdits`** — the lateral case emits the existing `BROKEN_WATER` kind; wet flags
  already thread `StepEdits` → `PathEdits` / `EditSnapshot` (splice) → `sliceStep`.
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

A `FLOWS` break writes fluid into `PathEdits` immediately, but vanilla delivers it 5 ticks later for water
(30 for overworld lava), plus 5 more per cell of travel (§1.4). Two subsystems see the disagreement.

**Replan churn — already absorbed, verify rather than build.** When the fluid lands it is a real block
change, so `NavGridUpdater` advances `editEpoch` and `BotNavigator`'s window re-search fires. But that is
the *existing, documented* behaviour for the bot's own edits — `BotNavigator:74` already records that
"the bot's own plan edits also advance the epoch — those re-searches are redundant-but-correct (PathEdits
already modelled them)", debounced by `TERRAIN_RECHECK_TICKS` (40). A predicted flood is the same case:
the re-search re-derives a plan the diff already anticipated, so it should return the same route. **No new
suppression machinery.** What to verify is that the flood does not arrive *late enough* to land outside
the debounce and cause a second re-search — 5 ticks against a 40-tick window leaves ample margin, but
lava's 30 and multi-cell travel do not, which is one more reason lava never reaches `FLOWS` (§4.1).

**Executor — the real exposure, and it is NOT new.** A move that consumes the predicted fluid as an
affordance can run while the cell is still air. Note this window **already exists in shipped code**: the
vertical `BROKEN_WATER` rule (core `454c28c`, 2026-08-16) has exactly the same lag and, as far as this doc's
author can tell, no test covers it. Grade the exposure by what the move wants the fluid *for*:

| use | during the lag | severity |
|---|---|---|
| descend into the broken cell (MineDown) | a 1-block fall through air instead of water | harmless — the bot was moving there regardless |
| swim UP / buoyancy out of the cell | the move cannot act; positional `failWhen` trips | recoverable — ratified fail→log→HOLD, then the fluid lands and the replan proceeds |
| **fluid as a soft landing for `Fall`** | real fall damage the planner priced away | **the one genuinely harmful case — review before shipping** |

**Do not fix this with a timer.** "Wait N ticks for the water" is exactly the tick-counter recovery the
`no-arbitrary-timers` rule forbids, and the ratified alternative already covers it: per-move validity
envelopes are POSITIONAL, so a move whose precondition is not yet met fails its envelope, logs once, and
HOLDS — which is the correct behaviour here, because the precondition becomes true on its own. The only
change worth considering is on the `Fall` row: whether predicted-but-unarrived fluid should be admissible
as a landing softness at all. Flagged, unratified.

---

## §9 Tests

- **Descriptor:** `FLUID_SOURCE` / `FLUID_MIN_LEVEL` set on the right states across the version range;
  navtype split does not exceed the cap; **no test asserts a navtype COUNT**.
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
- **Arrival lag (§8.3):** a `FLOWS` break followed immediately by a move that needs the fluid — assert
  fail→log→HOLD rather than a crash or a silent wrong-affordance, and assert the plan recovers once the
  fluid lands. **This test also covers the shipped vertical `BROKEN_WATER` rule, which currently has no
  coverage of its own lag window.**
- **Lava:** a lava neighbour returns `DISALLOW` at tier 1 and never reaches the slope search (assert the
  search is not entered — a counter or a fixture with a poisoned search path).
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
4. **`Fall` softness on predicted-but-unarrived fluid** (§8.3) — whether a `FLOWS` cell may count as a
   soft landing during its lag window. The one arrival-lag case that can actually hurt the bot.
   Unratified.
