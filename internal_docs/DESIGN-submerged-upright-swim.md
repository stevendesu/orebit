# DESIGN — Fluid as a medium: upright swim, the waterfall column

**Revision 2**, 2026-08-07 — all design questions ruled by the owner. §1 is implemented; everything
else is ready to build.
**Convicted by:** flagship course, 2026-08-06, waterfall at column `x=154 z=104`, floor `y=-14 → -8`.
Log `orebit-mc121-wt/run/logs/latest.log`, 21:38:43–47.

---

## §0 The failure, and the four independent defects behind it

| # | Defect | Where |
|---|---|---|
| 1 | `Surface.reached` had no Y term → cursor teleported to the top of the column | **FIXED**, §1 |
| 2 | A dry bot cannot enter fully-submerged fluid at all | §3 |
| 3 | Pure-vertical sprint-swim injects lateral drift with no cross-axis feedback | §4 |
| 4 | Upright swim is steered by a position-only P-controller — a current is never corrected | §6 |

Defect 4 is *why* the bot was swept off course; defect 1 is *why* that became unrecoverable.

**The organising idea of the fix (owner, 2026-08-07):** fluid is a **medium**, not a pose. `Swim`
becomes the upright-fluid analogue of `Climb` — one movement, many rungs, honest per-rung costs — and
the prone `SprintSwim` family shrinks to what only it can do: fast *lateral* travel.

---

## §1b IMPLEMENTED — the swim reach target is clamped to what geometry permits

**Flagship run, 2026-08-07.** Everything this design set out to do worked: the bot **walked into the body
of the fall** (no cobble bridge — §3.2), climbed the 1×1 column on upright rise rungs at 7–9 ticks per
cell with `SprintSwim` absent (§3.1 + §4), and the servo held the column (lateral velocity ±0.004 against
the ~0.054 sustained drift that swept it out on 2026-08-06 — §6). It then **held for 378 ticks at the
top**, one step from the exit.

Not a planner fault. The plan was right end-to-end:

```
 8 Swim d(0,1,0) ->(154,-8,103)      floor -8 → feet -7 (top water cell, head -6 air)
 9 Diagonal d(1,0,1) ->(155,-8,104)  deepslate ledge, feet -7, head -6, both air
```

The §3.1 rise emitted wp8 correctly (new feet `(154,-7,103)` water ✓, new head `(154,-6,103)` air ✓), and
the bot was physically AT it. **A pre-existing latent bug in the arrival test is what wedged it.**
`Swim.reachedSwim` centres its band on `wy + 1` — a *ride-height* convention (a floating bot rises until it
breaches, settling near the top of its feet cell). Its javadoc described `wy` as the "floor cell", which it
has not been for a long time: callers pass the WAYPOINT, and `Movement.atWaypoint` defines that as the FEET
cell. Reading the `+1` as a floor→feet conversion is what hid it.

Save data at `x=154 z=103`: water `y=-7`, air `y=-6`, **tuff `y=-5`**. A 1.8-tall body under an underside
at `-5.0` tops out at `botY = -6.800`; the test demanded `botY > -6.6`. **Short by 0.2 blocks, forever.**
Telemetry signature worth recognising again: `dm.y` pinned at exactly `-0.0050` every tick — vanilla's
in-fluid gravity with the jump impulse consumed by a vertical collision — while `holdDepth` held jump.

It is invisible mid-column (the bot rises straight through each threshold) and bites only at a surface cell
capped by a ceiling, which is exactly the top of a waterfall.

**Fix (owner-approved):** fold the ceiling into the target instead of special-casing it —

```java
double target = wy + 1.0 - bias;
if (b.solidAt(wx, wy + 2, wz)) target = Math.min(target, (wy + 2) - (b.prone() ? 0.6 : 1.8));
```

Byte-identical wherever there is headroom (the `min` does nothing), so the tuned open-water behaviour and
the working climb cadence are untouched; it only ever LOWERS the bar, and only to a height the bot has
already proven it can hold. Fixes the cursor `reached` and the phase `done` in one place (both route through
this helper). A TOP-slab ceiling clamps conservatively low, which stays inside `REACHED_Y`. Regression:
`SwimCeilingReachTest` pins the real flagship geometry plus the no-op, still-rejects, wrong-column, and
prone-height cases.

---

## §1 IMPLEMENTED — `Surface.reached` tests Y

Was `!prone() && footX == wx && footZ == wz` — no Y term, no settle gate. A fluid column is a stack of
same-XZ waypoints, and `BotNavigator.steerAlongPath` scans END→START taking the **furthest** match, so
entering the shaft at the bottom matched the `Surface` node at the top: `advance SKIPPED 10 step(s):
11→22` at `(154,-13,104)`; step 22 was framed from `expectTakeoffFoot.y=-7`, six blocks above the bot,
failed its envelope, and the bot held while the current carried it out. Same fly-through class the
default `Movement.reached` gained its `settled()` gate for on 2026-08-01; `Surface` never got it.

```java
if (b.prone()) return false;
return b.inWater() ? Swim.reachedSwim(b, wx, wy, wz)
                   : Movement.super.reached(b, wx, wy, wz);
```

**Trap:** a naive "mirror `Swim`" one-liner wedges bank exits — `reachedSwim` tests
`|b.y() − (wy+1)| < 0.6`, but a grounded bank arrival sits at `b.y() ≈ wy`.

**Will simplify.** Once §5 lands, `Surface` keeps only its bank exit (§7), the `inWater()` branch goes
dead, and this collapses to `Movement.super.reached`. Suite at time of fix: 739 tests, 0 failures.

---

## §2 The retired invariant — submerged STANDING nodes are legal

`StartSprintSwim` states today: *"an ordinary STANDING node can't be fully submerged."* That held
because every `MODE_STANDING` move clears its body cells with `MovementContext.passable`, which
**excludes fluid by construction**. Consequence: `SprintSwim`/`DiagonalSprintSwim` were the only
movements in `TIER1` permitting a submerged head.

**This design retires that invariant deliberately.** §3 creates submerged STANDING nodes; §4 needs them.

| Site | Leaned on it how | Resolution |
|---|---|---|
| `StartSprintSwim` case (1) | *"mostly the start cell when a replan fires mid-dive"* | **Becomes live, no code change** — the "go prone mid-column to thread a 1-tall gap" rung |
| `Surface` case (a) | requires an air head | **Subsumed** by `EndSprintSwim` (§5) |
| Ground moves | `passable` head excludes fluid | **Unchanged** — a submerged bot must rise or stand up, not walk |
| `Traverse` out of fluid | — | **Already works**, see §2.1 |
| `RideBubbleColumn` | *"a submerged bot is MODE_PRONE, out of scope"* | **Unchanged** — bubbles stay excluded |
| `Swim`'s domination prune | forces the dive wherever sprint-swim can initiate | **Re-scoped**, §5.3 |

### §2.1 Exit sideways already works — the asymmetry is one-sided

`Traverse.candidates` opens by noting, verbatim: *"a surface-swim node's water 'floor' reads as 16 …
so **walking OUT of water onto the bank** keeps its historical zero-lip geometry."* It gates only on
`mode() == MODE_STANDING` and on the **destination**'s standable floor + `requireBodyClearToward`; it
never inspects the source cell's medium or the bot's head.

So the moment §3 makes submerged STANDING nodes reachable, **walking sideways out of a waterfall onto
a bank works with no new code.** `ExitWaterSideways` should not exist — entry was walled, exit never
was. *Conditional on §6:* `drive()`'s water branch does call `holdDepth` (so it holds station
vertically), but steers with the position-only `swimTowards`, which §6 replaces.

---

## §3 `Swim` becomes the fluid-medium movement

### §3.1 Six-directional (owner ruling)

`Swim` today emits four cardinals plus a lava-only rise. It becomes **six-directional**: 4 lateral +
**up** + **down**. Diagonals (→10-directional) are a possible later completion, deferred.

- **Up** — the jump-only rise, `20 / 2.7 ≈ ` **7.41 t/block**.
- **Down** — the `sinkInWater` press, `20 / 3.7 ≈ ` **5.41 t/block**; cheaper than the rise because gravity
  assists it, which is the qualitative claim this section always made.

  > **Correction, 2026-08-07 (implementation).** Revision 2 estimated the rise at ~5 t/block from
  > `+0.04`/t against drag `0.8`. That derivation dropped vanilla's **in-fluid gravity term**. Adjudicated
  > against the 1.21.11 bytecode: `LivingEntity.jumpInLiquid` = `+0.04`/t (and `AllyBotEntity.sinkInWater`
  > mirrors it at `−0.04`), `getWaterSlowDown` = `0.8` (`travelInWater` multiplies **Y** by a hardcoded
  > `0.8`), and `getFluidFallingAdjustedMovement` subtracts `gravity/16` = `0.08/16` = **`0.005`/t**
  > (`Attributes.GRAVITY` default `0.08`). The impulse lands in `aiStep` *before* `travel`, so one tick is
  > `v' = drag·(v ± impulse) − gravity` and terminal speed is `(drag·impulse ∓ gravity)/(1 − drag)` →
  > `0.135` b/t rising, `0.185` b/t sinking. The constants are written as that expression in `Swim`, not as
  > literals. **Still not measured in-game** — the derivation is sound but the servo/tick-timing round trip
  > is not, so this stays the arc's softest number.
  >
  > Note `getFluidFallingAdjustedMovement` skips the gravity term entirely **when sprinting** — one more
  > reason the upright rungs explicitly clear the sprint flag.
- Both are **upright**, `MODE_STANDING`, one cell per step, **no pitch coupling** (an upright bot does
  not travel along its look vector) and **no `SERVO_FORWARD_MIN` floor** (that floor exists solely to
  hold the prone pose).

### §3.2 Entry — relax the head-air requirement (owner ruling)

`Swim.candidates` currently does `if (!ctx.passable(nx, wf + 1, nz)) break;` and `passable` excludes
fluid — so a submerged destination is rejected, and it is a `break`, so the down-scan stops too.
Combined with `StartSprintSwim` needing the bot's own feet already wet and `SprintSwim` needing
`MODE_PRONE`, **`Swim` was the only dry→wet rung and it demanded a surface cell.** A dry bot could not
walk into the body of a waterfall. That is why the planner placed cobble at `(153,-14,104)` — the one
cell where the fall spread over a stone block and happened to have air above it. There was never a pool.

The head test becomes "**not solid**":

| destination head | rung | cost |
|---|---|---|
| air | surface entry / cross | `COST` = 20/2.2 ≈ **9.09** t/block |
| fluid | submerged entry / cross | `SUBMERGED_COST` = 20/1.97 ≈ **10.15** t/block |
| solid | — | `break`, unchanged |

`SUBMERGED_COST` is not invented: `Swim`'s class doc already reserves 1.97 b/s as *"the submerged
normal-swim case … deferred to the stateful refinement."* This is that refinement, and it keeps costs
physically derived.

### §3.3 Lava is just another fluid (owner ruling)

Delete the lava special-casing. Every `Swim` rung works in any fluid; **damage and slow factors carry
the cost** and A* sorts it out — free for a damage-immune bot, expensive otherwise.

> **Clarification, 2026-08-07 (implementation).** Revision 2 said this "removes `lavaSwimCellCost` as a
> special path in favour of the general hazard pricing." Taken literally that is a silent behaviour change,
> not a simplification: `lavaSwimCellCost` **is** the damage-and-slow pricing this section asks for
> (`2.5×` slow factor + `LAVA_HP_PER_CELL = 10` in the one damage currency ≈ **1023 ticks/cell** at the
> default profile), and substituting `cellTransitCost(feetDesc)` for it yields ~100 — a ~10× cut that
> would make the bot willing to swim lava. What is deleted is the **structural** special-casing — the
> lava-only rise rung and the water-only submerged assumption; the **price** stays medium-dependent, which
> is precisely "let the damage factor and slow factor affect the costs". Implemented as a one-line
> `cellCost` dispatch shared by all six rungs.

**Why the descriptor cannot carry it today — and the follow-up that would fix that** (owner question,
2026-08-07: *"does the NavType descriptor for lava not set the slow bit? Feels like we already have all the
data we need — damage bit + slow bit"*). Half right, and the right half is the interesting one:

- **Damage bit: already set for lava** (`NavBlock.isDamaging(Block)` lists `Blocks.LAVA` first).
- **Slow field: was not set** — `NavBlock.transitSlow(Block)` was a hardcoded three-block list (cobweb
  `HEAVY`, berry bush / powder snow `LIGHT`). **Fixed 2026-08-07 by owner ruling**: lava is now
  `TRANSIT_FLUID` (the field's spare 4th encoding), factor `0.4` = `1/LAVA_SWIM_COST_FACTOR`, priced by
  `MovementContext.FLUID_TRANSIT_COST ≈ 6.95` ticks. Water is deliberately left `TRANSIT_NONE` — the swim
  rungs' costs *are* the water rates, so a surcharge would double-charge. **Immediate effect is on GROUND
  moves only** (a `Fall`'s drop column / `Diagonal`'s corners through lava previously charged damage but
  treated lava as no slower than air); the swim path still uses `lavaSwimCellCost`, because a flat
  walk-derived surcharge cannot express a multiplier on a varying swim rate. So this is step 1 of 2.

The blocker is not missing data, it is that **both generic constants are "brush-past" shaped while lava is
a dwell problem**:

| | generic | lava (s52b) |
|---|---|---|
| slow | flat tick surcharge, walk-derived — `FLAT_COST × (1/f − 1)` = 88 ticks for cobweb | a **multiplier**, `2.5×` on whichever swim rate applies (9.09 / 10.15 / 7.41 / 5.41) |
| damage | flat `1 HP` — "I brushed through this cell" | `10 HP`, derived from ~23 ticks of immersion across 10-tick i-frame windows |

An 88-tick flat surcharge on a 9.09-tick swim rung is wrong by *shape*, not merely magnitude, and a flat
1 HP cannot know the bot is immersed for 23 ticks rather than passing through in 4.6.

**The unification that would work** — and it is attractive enough to be worth its own arc: make the damage
charge **dwell-scaled**, `HP ≈ (moveTicks / iframeWindow) × HP_per_hit`. Lava swim then falls out at
`23/10 × 4 ≈ 9.2 HP` (≈ the ratified 10) and a fire walk at `4.6/10 × 4 ≈ 1.8` (≈ the current flat 1) —
from ONE rule with no lava branch, which is exactly the physically-derived-costs principle. Give the slow
field multiplier semantics for fluids at the same time and `lavaSwimCellCost` genuinely disappears.
Deferred: it touches every hazard-pricing site, so it is not a rider on this arc.

**Consequence, discovered in test — needs a ruling.** Relaxing the head test opens fluid **interiors**, not
just surfaces, and that applies to lava too. `DiagonalHazardJumpTest.twoTallLavaColumn_rejectsTheDiagonalJump`
asserted `assertNull(plan)` on the premise *"no other route → NO PATH"*; a route now exists — `Swim → Swim`
straight through the 2-tall lava column, ~2045 ticks — because that scene is sealed and there is literally
nothing else. The **arc gate is unaffected** (no `DiagonalParkour` in the plan), so the test was rewritten to
assert the invariant it actually owns (the column is not *jumped*) rather than the expired premise. Two
things for the owner:

1. This is the ratified s52b model ("A* crosses lava only when nothing else exists") reaching further than
   before: previously only lava *surfaces* were enterable; now interiors are.
2. **Pricing is not feasibility.** A mortal bot swimming two lava cells pays ~2045 ticks of *cost* but would
   physically **die**, and the goal tolerance will happily end a plan inside lava. That gap is pre-existing,
   but this widening makes it materially more reachable. Whether lava immersion should become a caps-level
   *refusal* for a mortal bot rather than a price is **an open question, deliberately not answered here.**

The one thing that does **not** unify is the prone family — see §9.1: vanilla structurally forbids the
swimming pose outside water. So in lava, emit `Swim` rungs only; emit no `SprintSwim` /
`StartSprintSwim` / `EndSprintSwim` edges. That is not a special case we invent — it is a vanilla
constraint we decline to model as reachable.

---

## §4 `SprintSwim` loses its verticals (owner ruling)

Delete the pure-up and pure-down `SprintSwim` candidates. **They were never real** — a swimming look
clamps around 80°, so the last ~10° is always lateral drift, and `swimServo`'s degenerate branch then
forces `vfwd ≥ SERVO_FORWARD_MIN` (0.08) *every tick* to hold the prone pose. That is a continuous
lateral injection opposed only by a position-P controller that commands exactly zero inside
`COLUMN_DEADBAND`. In open water it is recoverable drift; in a 1×1 shaft it is ejection at speed
followed by a fall.

§3.1's upright rise/sink replaces them, so this **deletes defect 3 at the source** rather than
servoing around it.

**Deferred:** a wall-braced vertical sprint (cancelling horizontal momentum against a wall) genuinely
could beat the upright rise, but it is situational and would re-open the branch being removed.

---

## §5 `EndSprintSwim` — leaving the prone pose (owner ruling)

New movement, `MODE_PRONE` → `MODE_STANDING`, **in place**, the exact mirror of `StartSprintSwim`
case (1). Cost symmetric with `StartSprintSwim.COST`. Completes the cycle the owner named:
sprint-swim → stand up underwater → rise vertically → go prone again → resume sprint-swimming. The
"go prone again" half already exists (§2).

### §5.1 It requires headroom — a fit gate, not a movement

Verified against vanilla (§9.2): dropping sprint clears the `isSwimming()` **flag**, so
`getDesiredPose()` returns `STANDING` — but `Player.updatePlayerPose` then **fit-tests** it. In a
1-tall gap `STANDING` (1.8) does not fit, `CROUCHING` (1.5) does not fit, and the pose falls back to
`Pose.SWIMMING` (vanilla crawling). `EndSprintSwim`'s `done` (`!b.prone()`) would never fire.

**Therefore:** emit `EndSprintSwim` only where the upright pose fits — two non-solid body cells above
the floor. That is `Surface` case (a)'s existing geometry test minus its `!water` clause.

**Implementation note:** `BotSteering.prone()` must read the **pose**, not the swimming flag — in the
crawl state the flag is false while the pose is `SWIMMING`, and reading the flag would make the gate
moot.

### §5.2 It subsumes `Surface` case (a)

`Surface` narrows to its bank exit alone (§7).

### §5.3 The dominance gate — keep the prune, change its question

**The greedy problem, measured.** Octile is tick-scaled at `Traverse.FLAT_COST` = 4.633/block;
`greedyWeight` default is **2.0** (`BotCaps.DEFAULT_GREEDY_WEIGHT`). For a distant lateral goal:

| move | Δg | Δh (×2) | **Δf** |
|---|---|---|---|
| surface `Swim`, 1 block toward goal | +9.09 | −9.27 | **−0.18** |
| `StartSprintSwim` (fused dive, case 2) | +2.00 | ≈ −3.8 … +3.8 | ≈ **+5.8** worst case |
| `SprintSwim`, 1 block prone | +3.56 | −9.27 | **−5.71** |

The surface crawl is **f-decreasing**, so the surface chain lowers `f` at every step and the dive node
is **never popped** unless that branch dead-ends. Not a tie-break sensitivity — structural at w=2.
There is **no de-greeding step anywhere** in the search: `greedyWeight`, the goal-forced-cost premium
(which *adds* to h), the region field (`max`'d against octile), and a sqrt tie-break that only orders
ties. The prune is the only mechanism we have.

**But the prune is provably correct, not a hack.** Crossing N fluid blocks: surface `9.09N` versus
dive `2 + 3.56N + 2`. The dive wins at **N = 1** already (7.56 vs 9.09). "Where sprint-swim can
continue, surface swim is never on an optimal path" is a dominance statement.

What is wrong is its **predicate**. Today it asks *"can sprint-swim INITIATE here?"*
(`feet wet && (twoDeep || deepBelow)`). It must ask *"can sprint-swim make **PROGRESS** from here?"*:

- **Scope** → lateral `Swim` rungs **only**. Never up/down: once §4 removes `SprintSwim`'s verticals it
  can never dominate on that axis, so a 1×1 column must keep its upright rise.
- **Condition** → a lateral fluid neighbour exists at prone depth (a real sprint-swim continuation).

The dry-bank entry is untouched — the `feet in water` guard never fires for a dry bot.

### §5.4 `StartSprintSwim` keeps its fused case (2) (owner ruling)

Case (2) — tread at the surface, **descend one cell AND go prone in one edge**, cost 2 — is
aesthetically the "moves beyond the planned cell" wart, and the owner does not like keeping it.
It stays anyway, because **fusion is what makes the dive findable**: splitting it into
`Swim(down)` → `StartSprintSwim` roughly doubles the f-hill (≈ +5.8 → ≈ +10.8) and parks an
unattractive node in the middle of it. §5.3's re-scoped prune is the dominance gate that guarantees
the fused edge is considered.

The same principle appears twice: `Surface` case (b) is likewise a cheaper fused edge than
`EndSprintSwim + Traverse` (§7).

---

## §6 The upright-swim servo

### §6.1 Why the current one cannot work

Upright `Swim`, `Surface`, and `drive()`'s in-water branch all steer via `recenterOn`:

```java
b.faceHorizontally(cx, cz);              // face the POSITION error
b.setForward((float) Math.min(1.0, d));  // no velocity term at all
```

A pure P-controller: under a constant disturbance it settles at a steady offset, and inside
`COLUMN_DEADBAND` (0.15) it commands exactly zero. `arriveOnTarget`'s javadoc already convicts the
same controller for parkour — *"no velocity term … always settles with standing overshoot."*

The log shows it: `str=0.00` on every water tick, and +Z velocity holding at ~0.054 b/t across eight
ticks while drag (0.8/t) should decay it and the servo faces partly −Z. Steady state for vanilla's
~0.014/t flow push under 0.8 drag is 0.07 b/t — right order of magnitude. Something external was
holding the bot and nothing in the loop could see it.

### §6.2 Why `swimServo` is the wrong reuse (owner ruling)

> *"Sprint swimming uses functionally different movements (can look down and hold forward to move
> down). Normal swim if you look down and hold forward you only move forward. It's more like grounded
> movement."*

`swimServo` folds the depth **pitch** into its facing because a prone swimmer travels along its look
vector. An upright swimmer does not — pitch is horizontally inert.

### §6.3 Shape

A **yaw-only horizontal velocity servo** in the `groundServo` mould: resolve horizontal velocity error
`desired − current`, face **along the error**, forward proportional to `|error|` — drag fought with
forward thrust, overshoot flipping the yaw into a reverse-thrust brake. **No depth pitch** (vertical is
`holdDepth`'s jump/sink); water drag constant 0.8; **no `SERVO_FORWARD_MIN`** floor.

This is what actually counteracts the flow, and it is what makes leaving flow unmodelled in the
planner (§8) survivable.

---

## §7 `Surface` narrows to one job

With case (a) subsumed by `EndSprintSwim`, `Surface` keeps only case (b), whose real geometry is
easy to misread: **it is the SOURCE that lacks headroom, not the bank.** Case (b) never checks
`(x, y+2, z)`. It covers a bot prone in a **1-tall submerged tunnel** whose exit opens onto a
standable floor at the same level with two clear cells above — it crawls out and stands up as it
emerges, because standing up where it is, is physically impossible (§5.1).

That is the only rung for "prone in fluid → dry standable neighbour": `SprintSwim` cannot go there
(it needs fluid at the destination feet), and `EndSprintSwim` cannot fire (no headroom). It should
probably be renamed to match its single purpose. `Surface.reached` collapses to
`Movement.super.reached`.

**Not covered by anything:** submerged-1-tall → **dry**-1-tall (crawl on land). The mode model
explicitly defers it (*"on land in a 1-tall gap it's crawl … StartCrawl (low ceiling)"*).

---

## §8 Recorded, not fixed — no flow model exists

`NavBlock.fingerprint` collapses `FLOWING_WATER` into `FLUID_WATER`: no direction, no level, no
falling bit, no cost. A* prices a waterfall identically to still water. The caution that *feels* like
flow-awareness is two other things — `NavFlags.RISKY_EDIT` (an edit might **release** fluid) and the
bubble-column exclusion. **Owner ruling: ignore, provided §6 lands.** A servo counteracting measured
velocity is strictly more robust than a planner predicting a per-cell push.

---

## §9 Vanilla facts, verified against 1.21.11 bytecode

Disassembled from `minecraft-merged-1.21.11-…jar` (official mappings) — not inferred.

### §9.1 You cannot sprint-swim in lava

`Entity.updateSwimming()`:

```java
if (this.isSwimming())            // STAY
    setSwimming(isSprinting() && isInWater() && !isPassenger());
else                              // ENTER
    setSwimming(isSprinting() && isUnderWater() && !isPassenger()
             && level().getFluidState(blockPosition).is(FluidTags.WATER));
```

The entry branch carries an explicit `FluidTags.WATER` test; the stay branch's `isInWater()` is
water-only. Lava fails both. The prone family is therefore water-only **by vanilla**, which is why §3.3
unifies the *upright* rungs across fluids while emitting no prone edges in lava.

### §9.2 You cannot stand up in a 1-tall gap

`Player.updatePlayerPose()`:

```java
if (!canPlayerFitWithinBlocksAndEntitiesWhen(SWIMMING)) return;
desired = getDesiredPose();                        // → STANDING once isSwimming() is false
if (spectator || passenger || canFit(desired)) pose = desired;
else if (canFit(CROUCHING)) pose = CROUCHING;
else pose = SWIMMING;
```

Dropping sprint clears the flag and `getDesiredPose()` returns `STANDING`, but the fit test then falls
back through `CROUCHING` (1.5) to `SWIMMING` (0.6). Hence §5.1's headroom gate and §7's residue.

---

## §10 Build order and verification

1. **§3** — `Swim` six-directional, head-air relaxation, `SUBMERGED_COST`, lava unification.
2. **§4** — delete `SprintSwim`'s verticals.
3. **§5** — `EndSprintSwim` + headroom gate; re-scope the prune (§5.3); keep case (2) fused.
4. **§7** — narrow `Surface`; simplify §1's `reached`.
5. **§6** — the upright-swim velocity servo.

Unit coverage per rung in the style of `StatefulSwimTest`; full suite green (baseline 739/0). Then
**one** flagship run on the frozen master world with `-BotDebug`, checking in order:

- the bot **walks** into the fall — no cobble at the entry (§3.2)
- the column rise holds the column without lateral ejection (§3.1 + §4 + §6)
- the cursor does not skip the column (§1 regression guard)
- the end-of-course region-tier wander seen in the debug particles — **separate question**, deferred
  until the waterfall clears
