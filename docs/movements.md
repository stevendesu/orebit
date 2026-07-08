# Movements

Every path Orebit plans is a chain of **movements** — small strategy objects that each
know one way of getting from a block to a neighboring block (or, for the macro and gap
moves, a run of blocks). Each movement knows its geometry (when it applies), its **cost
in game ticks** (what the search compares), and how to steer the bot through itself at
execution time. The planner has no special cases: it simply expands every movement's
candidates at every node and takes the cheapest total. That means the numbers on this
page *are* the bot's personality — every "why did it swim instead of taking the
ladder?" question is answered by the arithmetic below.

## The ruler: 4.633 ticks per block

All costs are in **game ticks** (20 ticks = 1 second), and the unit everything is
measured against is the flat walk:

$$ \text{walk} = \frac{20 \text{ ticks/s}}{4.317 \text{ blocks/s}} \approx 4.633 \text{ ticks/block} $$

4.317 m/s is vanilla ground speed. Every other movement's cost is derived the same way
— 20 divided by the real vanilla speed of that motion — so "swim across or walk
around?" is a genuine time comparison in one currency. Damage has an exchange rate
into the same currency (`pathing.costPerHitpoint`, default 100 ticks per hitpoint),
which is what lets hazards be *costs* instead of walls.

## The roster

| Movement | Cost (ticks) | Derivation |
|:---|:---|:---|
| Traverse | 4.633 / block | 20 ÷ 4.317 (vanilla walk speed) |
| Diagonal | 6.552 | 4.633 × √2 |
| Ascend | 4.633 (+ place) | jump-up ≈ one walk step |
| Descend | 4.633 | step-down ≈ one walk step |
| Fall | 4.633 + 2.5 / block | walk-off step + fast-drop average |
| Pillar | 4.633 + place ≈ 10.6 | jump-in-place + the placed footing |
| MineDown | 4.633 + mining time | one-block drop + the real dig |
| Climb (up) | 8.51 / block | 20 ÷ 2.35 (ladder ascent speed) |
| Climb (down) | 6.67 / block | 20 ÷ 3.0 (ladder descent clamp) |
| Parkour | 15.6 / 18.6 / 21.6 | run-up + airtime + commit penalty |
| DiagonalParkour | ≈ 20.1 / 24.1 / 28.6 | the Parkour table at diagonal reach |
| Swim (surface) | 9.09 / block | 20 ÷ 2.2 (surface paddle speed) |
| Sprint-swim | 3.564 / block | 20 ÷ 5.612 (sprint-swim speed) |
| Start sprint-swim / Surface | 2 each | pose transitions |

Some sanity checks fall out for free: an existing ladder (8.51/block up) beats
building a pillar (~10.6/block) but loses to a natural staircase (4.633/block) —
so the bot takes stairs over ladders over scaffolding, which is what a player would
do. Sprint-swimming (3.564) is *faster than walking*, so a long straight lake crossing
legitimately beats the shoreline path.

## Walking

**Traverse** — one block horizontally. Step-assist handles slabs and stair lips. Two
folded variants: it may **break** the block in the way (charged the real vanilla
mining time for the block against the bot's best tool, plus the `mining.breakBaseCost`
surcharge), or **bridge** — place a floor block under the far cell to cross a one-block
gap (charged the placement cost, below). Slow floors are priced honestly: soul sand
walks at ~0.4× speed, so crossing it costs 4.633 ÷ 0.4 ≈ 11.6 ticks — a
**+7.0-tick surcharge** the search weighs against detouring.

**Diagonal** — the corner-cutting variant at √2 distance (6.552 ticks), with corner
clearance checks so the bot's 0.6-wide hitbox never clips a fence post mid-cut. Folds
no edits — a blocked diagonal just isn't offered (the two cardinal steps still are).

**Ascend / Descend** — the ±1-level steps, each priced as one walk step. Ascend may
place a step block to climb where none exists; Descend may dig one out.

## Vertical, in place

**Pillar** — jump and place a block beneath yourself, gaining one block of height in
the same column. Costs 4.633 plus the placement charge — at the default
`placement.placeBaseCost = 6.0`, about **10.6 ticks per block of height**. Long climbs
are collapsed by the [macro-movement layer](Optimizations/cuboid_macro_movements.md)
into a single multi-block candidate. At execution time Pillar runs as a **phase plan**
(jump → place under yourself → land), with every phase's requirements re-checked
against the live world each tick — if the footing never took and the bot fell back
down, the phase cursor resets and it re-attempts.

**MineDown** — dig the block underfoot and drop one. One break per level of descent,
versus the ~3 breaks per level a dug diagonal staircase needs — which is why the bot
digs a clean shaft to descend and a staircase only when the route must also move
sideways. Priced at 4.633 plus the real mining time of the floor block.

## Falling

**Fall** — walk off an edge and drop to the first landing, up to the bot's maximum
fall. Costs one walk-off step plus **2.5 ticks per block dropped** (falling is fast).
Damage is a cost, not a wall: each block past the safe distance (3) is priced at one
hitpoint — `pathing.costPerHitpoint` ticks — so a mortal bot prefers the 2-block drop
into the cave over the 5-block one, and an invulnerable bot (`survival.takesDamage =
false`) drops any depth for free. Every cell the drop passes through is also priced
(falling *through* a berry bush still pricks). Finding the landing used to mean
scanning the column downward cell by cell; the nav grid now stores a per-cell
["distance to the floor below" nibble](Optimizations/depth_nibbles.md) that answers it
in one read.

## Climbing

**Climb** — move up or down an existing ladder, vine, or scaffolding column, plus the
sideways "grab" step into the column (priced as the plain walk step it is). Ascent is
20 ÷ 2.35 ≈ **8.51 ticks/block** (vanilla's +0.2/tick climb velocity); descent is
20 ÷ 3.0 ≈ **6.67** (the −0.15/tick fall clamp — you just hold on). Edit-free by
design: Climb never places ladders, and mining one *out of the way* belongs to the
break-folding moves. Execution is pure vanilla physics — on a climbable, holding jump
climbs and doing nothing slides down, so the steering just re-centres on the column
and holds jump when the target is above.

## Gap jumps

**Parkour** — a running jump across open columns to a landing at, above, or below the
takeoff level. The envelope is one unconditional table, following the sprint-jump
parabola with the reachable maxima verified by a human at the controls (the flat
3-gap, a rising landing across gaps up to 3, the falling (−1) 4-gap; the deeper-drop
rows are derived from the same parabola — a drop gains range):

| Landing | Gaps |
|:---|:---|
| flat (same level) | 1–3 |
| rising (+1) | 1–3 |
| falling (−1, −2, −3) | 1–4 |

The edges of the table are deliberate. There is no flat 4-gap anywhere — the
~3.4-block flat reach doesn't cover it; the 4-range belongs to the falling arcs alone.
And drops deeper than −3 aren't parkour at all: past that, each extra 3–4 blocks of
drop buys only ~1 block of range while landing precision degrades, and
[Fall](#falling) already owns deep descents off an edge.

Cost = one run-up step (4.633) + airtime (8 / 11 / 14 / 16 ticks for gaps 1/2/3/4) + a
3-tick commit penalty — flat totals **15.6 / 18.6 / 21.6** — always at least the
4.633-per-block ruler, so a jump never beats safe walking unless it actually saves
distance. A rising landing is 2 ticks *cheaper* (the higher floor intercepts the arc
early); a falling one adds 2/3/5 ticks by drop depth, plus fall damage pricing past the
safe window. When no straight landing exists, an **offset tier** probes flat landings
one cell off the cardinal line — a (2, ±1) landing is √5 ≈ 2.24 blocks of reach and a
(3, ±1) is √10 ≈ 3.16, both inside the 4-block flat reach. Parkour is **edit-free by
rule** (you can't mine or place mid-flight — a gap that would need it simply isn't a
candidate) and executes as a four-phase plan: run-up → take-off (triggered ~0.35 blocks
past the takeoff cell's centre — jumping as late as the block still supports) →
airborne → land, with a balk guard that resets the attempt if the jump never left the
ground.

**DiagonalParkour** — the same idea along a diagonal, mirroring how Diagonal
accompanies Traverse. A diagonal gap of *g* cells spans g·√2 blocks of air, so the
envelope is gaps 1–3: the 2-gap's 2.83-block span sits inside the verified ~3-block
flat reach, and the 3-gap's 4.24-block span is at the physics limit, matching the
cardinal table's outer rows. Airtime interpolates the cardinal table at the diagonal
displacement — ≈ 10.5 / 14.5 / 19 ticks — on top of a diagonal run-up step (6.552) and
the shared commit penalty: **≈ 20.1 / 24.1 / 28.6 total**. Every cell-to-cell
transition additionally sweeps both corner columns, one row taller than walking
Diagonal checks, because the jump arc carries the hitbox higher.

## Swimming

**Swim** — the slow surface paddle, 20 ÷ 2.2 ≈ **9.09 ticks/block**. It's what
un-walls water: without it every river is a wall to be bridged.

**Sprint-swim** — prone underwater swimming at 20 ÷ 5.612 ≈ **3.564 ticks/block**, the
3D underwater workhorse and the only movement faster than walking.

**Start sprint-swim / Surface** — the transitions into and out of the prone pose,
2 ticks each. Sprint-swimming is *stateful*: you need deep water to go prone but can
continue through shallows, so the search's node identity includes the bot's pose —
going prone is a real search edge with a real cost, not bookkeeping.

## What the config changes

| Key | Movements affected |
|:---|:---|
| `placement.canPlace` | Pillar and the bridging/step-placing variants exist at all |
| `placement.placeBaseCost` | how readily the bot scaffolds (Pillar ≈ 4.633 + this) |
| `placement.removalCostWeight` | which carried block it bridges with |
| `mining.canMine` | MineDown and every folded break |
| `mining.maxHardness` | which blocks the folded breaks may touch |
| `mining.breakBaseCost` | reluctance surcharge on every folded break |
| `survival.takesDamage` + `pathing.costPerHitpoint` | Fall/Parkour damage pricing, hazard pass-through |

See [Configuring Orebit](configuration.md) for the full reference, and
[Breaking & Placing](world_edits.md) for how the folded edits actually execute.
