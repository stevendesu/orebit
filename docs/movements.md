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
| WalkOff | 9.27 | two walk steps — cross a 1-wide gap and land one down, no jump |
| Pillar | 4.633 + place ≈ 10.6 | jump-in-place + the placed footing |
| MineDown | 4.633 + mining time | one-block drop + the real dig |
| Climb (up) | 8.51 / block | 20 ÷ 2.35 (ladder ascent speed) |
| Climb (down) | 6.67 / block | 20 ÷ 3.0 (ladder descent clamp) |
| Climb (jump-grab) | 8 | 3 rise ticks to +1.0 + 2 arrest + 3 jump commit |
| Climb (exit-top) | 10.51 | one climbed cell + 2 to settle on the deck |
| Climb (sink-in) | 8.67 | one descended cell + 2 to enter/arrest |
| Fall (hang) | 4.633 + 2.5 / block + 2 | walk-off + drop to the vine + arrest settle — **no damage term** |
| Parkour | 15.6 / 18.6 / 21.6 | run-up + airtime + commit penalty |
| DiagonalParkour | ≈ 20.1 / 24.1 | the Parkour table at diagonal reach (base cap 2) |
| DiagonalAscend | 6.552 | 4.633 × √2 — diagonal walk whose jump overlaps the motion |
| DiagonalDescend | 6.552 | 4.633 × √2 — diagonal walk with the free one-block drop |
| Swim (surface, lateral) | 9.09 / block | 20 ÷ 2.2 (head-in-air paddle speed) |
| Swim (submerged, lateral) | 10.15 / block | 20 ÷ 1.97 (fully-submerged paddle speed) |
| Swim (rise) | 7.41 / block | 1 ÷ 0.135 (hold-jump terminal rise in fluid) |
| Swim (sink) | 5.41 / block | 1 ÷ 0.185 (sink terminal rate — gravity assists) |
| Sprint-swim | 3.564 / block | 20 ÷ 5.612 (sprint-swim speed) |
| Diagonal sprint-swim | 5.04 / 6.17 | 3.564 × √2 (two axes) / × √3 (all three) |
| Start / end sprint-swim, Surface | 2 each | pose transitions |
| Ride bubble column | 4 + 1.43 / block | step in and out + the column's ~0.7 blocks/tick push |

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
It also enforces the **rise gate**: a same-level diagonal whose destination surface
sits more than the 9/16 auto-step above the start surface (a carpet-to-full-block
lip, say) is refused rather than emitted as an impossible walk — the cardinal jump
arm owns that lip (and the *diagonal* jump to a floor one level up is **DiagonalAscend**'s,
below).

**DiagonalAscend / DiagonalDescend** — the diagonal ±1-level steps (the dry 3-axis pair):
Diagonal's corner-clearance rule fused with Ascend's jump / Descend's controlled step-down,
each priced as a diagonal walk step (6.552 — the jump or drop overlaps the motion). Both fold
**no edits, ever**: grid-aligned edits are per-axis by necessity, so any edit route is
Ascend+Traverse's job — these emit only cleanly-walkable geometry. DiagonalAscend jumps only a
rise in (9, 20] sixteenths (the ascend window; the diagonal *small*-rise one-up band is owned by
nobody — a documented hole) and sweeps both corner columns three rows high for the jump arc;
DiagonalDescend keeps the flat corner sweep and adds the one-block drop.

**Ascend / Descend** — the cardinal ±1-level steps, each priced as one walk step. Ascend may
place a step block to climb where none exists; Descend may dig one out. Ascend also
carries the **same-level jump arm**: a lip between two floors at the *same* block
level that is too tall for the auto-step but within one jump's 1.25-block gain
(10..20 sixteenths — climbing out of a flush-sunk hatch pocket, or a carpet-lip
pocket) is emitted as a jump, priced like the +1 step.

**Trapdoors** ride the door machinery: a hand-toggleable trapdoor blocking a crossing
folds a cheap **open/close SET** (6 ticks, same as a door toggle, gated by
`doors.toggle`) instead of being mined — closing an open hatch makes a floor to stand
on, opening a closed plate clears headroom or drops the bot through (MineDown), and a
vertically-stacked pair is opened as two SETs in one step. An opened panel that would
swing *across* the travel direction refuses instead — no toggle chain plans a
crossing the panel itself would block. Iron trapdoors are never hand-toggled and are
routed around (or mined, where policy allows).

**Fence gates** ride the same machinery: a closed gate is a whole-cell blocker — a
tall narrow post the bot can never stand on, and one that cannot be waterlogged —
so a crossing folds the same 6-tick open SET, gated by the same `doors.toggle`.
Unlike a door or trapdoor the fold is *face-agnostic*: the closed plate blocks every
crossing and the open gate is simply air, so which way it faces never matters and no
swing-across refusal applies. Every vanilla fence gate is wooden and hand-openable
(there is no iron gate), and the default config protects `#minecraft:fence_gates`
from breaking — so under defaults the toggle is the only way through a closed gate.

## Vertical, in place

**Pillar** — jump and place a block beneath yourself, gaining one block of height in
the same column. Costs 4.633 plus the placement charge — at the default
`placement.placeBaseCost = 6.0`, about **10.6 ticks per block of height**. Long climbs
are collapsed by the [macro-movement layer](Optimizations/07_cuboid_macro_movements.md)
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
false`) drops any depth for free. Soft landings are priced like vanilla treats them: a
bed halves the damage term, hay and honey cut it to a fifth, and a slime block zeroes
it — so a slime landing legitimizes a drop of any depth. Every cell the drop passes
through is also priced (falling *through* a berry bush still pricks). Finding the
landing used to mean scanning the column downward cell by cell; the nav grid now
stores a per-cell
["distance to the floor below" nibble](Optimizations/09_depth_nibbles.md) that answers it
in one read.

**Falls arrest in vines.** Minecraft stops a faller the moment their feet occupy a
climbable cell (velocity clamped to −0.15, fall distance reset) — so a fall never
passes *through* a vine, and the planner doesn't pretend it does. A vine (or any
no-collision climbable) in the drop column becomes the landing itself: a **hang**, feet
in the vine, taken at the bottom cell of the vine run — completely **damage-free**,
because the arrest resets fall distance before anything hits. From a hang the bot can
climb, or let go and drop the next stretch — which is how an alternating vine/air/vine
column descends *any* total depth, one arrested hop at a time. One physical honesty
bound applies: feet are sampled once per game tick, so a fast-enough fall can skip a
vine without arresting. A hang is only offered within **7 blocks** of prior fall — the
regime where fall speed provably stays under one block per tick and the catch is
guaranteed. Deeper falls onto climbables are deliberately not planned (they're rare,
and honestly detecting them taxes every deep-drop scan); past the bound the column is
refused outright — whether the bot would catch or tunnel is a coin flip, and the
planner doesn't plan coin flips. Ladders and scaffolding never become
hang landings — falling onto scaffolding lands *on top* (its stand-on shape catches
you), and dropping into a ladder cell from above is a knife-edge between hanging inside
and catching the 3/16 plate, so those columns are descended by climbing instead.

**Water catches you, and it needs no bound.** A single block of water in the landing's
feet cell makes that drop damage-free *from any height* — the old "one block of water on
the ground" trick. It works for a different reason than the vine arrest: vanilla resets
fall distance from where the bot **ends up**, not from cells it passed through, so there
is no sampling to miss and no tunneling bound to respect.

Deeper water is a question of momentum rather than damage. A fall entering water carries
its speed a little way down and then decays to a 0.025 blocks/tick crawl — about 40 ticks
per block — so a planner that promised the seabed would be promising a node the bot takes
a minute to reach. Instead the fall simply **ends where the momentum runs out**, floating
mid-column, and the swim moves take it from there at their own honest rates. The numbers
are less generous than they look: terminal velocity (3.92 b/t) is approached so slowly
that a 60-block drop enters at only 2.34 b/t and carries about **11 blocks** down — and
crossing 16 blocks of water would need a *176-block* fall. Before this, a deep pool was
simply unpathable: the bot would refuse the drop and pillar down a staircase beside it.

**Clutches — the bot brings its own landing.** If it is carrying one, the bot can *place*
the soft landing on the way down, exactly like an MLG water bucket. Three work today:

| Carried | Result | Afterwards |
|---|---|---|
| Water bucket | damage-free, any height | scooped back up |
| Powder snow bucket | damage-free, any height | scooped back up |
| Hay bale | damage cut to a fifth | left behind as a step |

The block goes in one tick before impact, and that timing is *guaranteed* rather than
lucky: block reach is 4.5 blocks and a falling bot never exceeds 3.92 blocks/tick, so the
target cell is always within reach for at least one tick, from any height. The bot simply
attempts the placement every tick on the way down — the same thing a player does by
spamming the button.

Water and powder snow are picked straight back up (a water source left behind would spread
within five ticks, so the reclaim is prompt by design). Hay stays: it becomes the floor the
bot is standing on, so taking it back would pull the ground out from under it — and leaving
it is no different from the blocks the bot already leaves when it pillars or bridges. It
even pays for itself, since the next search reads that hay as an ordinary soft landing and
takes the same drop for free.

A clutch is only offered when the bot **is carrying the item** and the drop would otherwise
be refused; it never re-prices a fall the bot could already survive. Water is skipped in the
Nether, where it evaporates on placement — powder snow is the answer there, and works in
every dimension.

Clutches are planned, not reflexive: the bot clutches a drop *it chose to take*. Being
shoved off a ledge by a creeper is not yet something it saves itself from.

**Fall can be switched off entirely**, and one order does: [`/bot roam`](commands.md). An
exploring bot that wanders off a cliff or the rim of a floating island is a bot you have to
go dig out of a hole, so while roaming the planner emits no Fall candidate at all. This is a
separate switch from the numbers above rather than a very small `maxFall`, because the fall
window *prices* a drop — it can't forbid one. Every branch on this page routes around it: a
drop inside the safe distance has no damage term to raise, an invulnerable bot's terms are
already zero, and a soft or clutched landing is deliberately uncapped. Only turning the
movement off is actually total. Nothing else changes: a roaming bot still jumps, swims,
climbs, bridges, and steps down single blocks — including a Parkour jump that lands lower
than it took off.

**WalkOff** — the no-jump gap cross: walk straight over a one-block gap and land one
level down on the far side, letting momentum carry the bot across the lip. Two walk
steps (**9.27 ticks** — the drop itself is the free one-block drop). It exists for
exactly the spots where a jump is *refused* — a honey takeoff, a cobweb in the body
space, or a ceiling too low to jump under — so Parkour stays the owner of every gap a
jump can legally cross, and WalkOff quietly covers the rest.

## Climbing

**Climb** — move up or down an existing ladder, vine, or scaffolding column, plus a
sideways "grab" step into an open-air column — priced as a *sneak-speed* step
(4.633 ÷ 0.3 ≈ **15.4 ticks**, since edging out to grab a ladder over a drop is done
carefully), and only offered where plain walking can't already reach the spot. Ascent is
20 ÷ 2.35 ≈ **8.51 ticks/block** (vanilla's +0.2/tick climb velocity); descent is
20 ÷ 3.0 ≈ **6.67** (the −0.15/tick fall clamp — you just hold on). Edit-free by
design: Climb never places ladders, and mining one *out of the way* belongs to the
break-folding moves. Execution is pure vanilla physics — on a climbable, holding jump
climbs and doing nothing slides down, so the steering just re-centres on the column
and holds jump when the target is above.

Three more edges connect what a player does around climbables without thinking:

- **Jump-grab** — standing on real ground with a vine or ladder bottom one air cell
  overhead, jump and grab it (feet cross +1.0 during the jump's third tick and vanilla
  arrests them in the cell). Two cells overhead is genuinely out of reach — the jump
  apex is 1.25 blocks — and the launch must come from *solid, non-climbable* footing:
  a jump started with feet inside a climbable gets truncated to the slow climb, so
  there is no jumping off ladders, plates, or decks. That's also why a gapped column
  never connects **upward** — vines give you nothing to stand on, and a ladder's
  plate stance can be blocked outright by a same-side ladder above it.
- **Sink-in** — standing *on top of* a ladder plate or scaffold deck, enter the column
  below (the classic climb-down-into-the-underground-base move). On scaffolding the
  bot sneaks — sneaking removes the deck's stand-on shape and it descends inside; on a
  ladder it walks off the 3/16 plate toward the cell centre and the climbable arrest
  catches it.
- **Exit-top** — from inside a scaffold column, climb out the top and stand on the
  deck (the climb-out pop clears the top face by ~0.15 and the deck catches the
  landing). Ladder plates are deliberately excluded — a 3/16 strip is not a landing
  the planner can promise.

One scaffolding footnote: the *sideways* grab into a scaffold column is refused. The
lateral hold is a sneak, and scaffolding is the one climbable vanilla exempts from the
sneak-hold — a sneaking bot sinks while crossing. Scaffold columns are entered from
above (sink-in) or below (climb up inside), which is how they're built to be used.

## Gap jumps

**Parkour** — a running jump across open columns to a landing at, above, or below the
takeoff level. Which gaps are offered is no longer a hand-tuned table: it is **derived
from closed-form Minecraft ballistics** — the jump arc computed from vanilla's actual
gravity, drag, and sprint-jump constants. A jump for gap *g* is admitted when the
horizontal reach the bot can build within the airtime that keeps its feet at or above
the landing surface covers the required travel — subject to a policy cap of **≤ 3.0
blocks of cleared air** for a level-or-rising jump (plus the drop for a falling one).

The **base envelope** — a full-block takeoff, normal floor, no slow block in the body:

| Landing | Gaps |
|:---|:---|
| flat (same level) | 1–3 |
| rising (+1) | 1–**2** |
| falling (−1 / −2 / −3) | to 4 / 4 / 4 |
| diagonal (same level) | 1–**2** |

The edges are physics, not taste. **Rising caps at 2** and there is **no flat 4-gap** and
**no diagonal 3-gap** — the sprint-jump reach (~3.4 blocks flat) plus the cleared-air cap
simply don't cover them. (These three were the bug in the old hardcoded envelope: it
offered rising-3, flat-4 and diagonal-3, jumps the bot then *attempted and fell short
of*.) Drops deeper than −3 aren't parkour at all — [Fall](#falling) owns deep descents.

**The envelope tightens with the takeoff conditions.** A lower takeoff surface folds into
an effective Δy that shrinks every reach; a slow floor or a slow body cell only ever
*reduces* reach, never fabricates it:

| Takeoff condition | flat | rising | falling −1/−2/−3 | diagonal |
|:---|:--:|:--:|:--:|:--:|
| full block, normal floor (base) | 3 | 2 | 4 / 4 / 4 | 2 |
| bottom slab (surface +0.5) | 2 | 0 | 3 / 4 / 4 | 2 |
| soul sand (speed factor 0.4) | 2 | 1 | 2 / 2 / 3 | 1 |
| sweet-berry body cell | 2 | 0 | 2 / 3 / 3 | 1 |

Honey floors (reduced jump) and cobweb body cells (killed take-off velocity) never reach
the table — they are refused before the scan. So is anything climbable, on both ends of
the arc: a takeoff from a climbable floor **or with feet inside a climbable cell** is
impossible (vanilla truncates the jump to the 0.2 climb), and an arc whose flight path
crosses a vine or ladder cell is arrested mid-air by the ±0.15 climbable clamp — the
jump the planner drew would end dangling in the vine, so it is never offered.

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
accompanies Traverse. A diagonal gap of *g* cells spans g·√2 blocks of air. Its cap is
the `diag` column of the same derived envelope (keyed on the same takeoff conditions):
the **base cap is 2** — the 2-gap's 2.83-block span sits inside the ~3-block flat
sprint-jump reach, while the 3-gap's 4.24-block span exceeds it and is **no longer
offered** (the old hardcoded cap of 3 was the corner-cut the bot fell on when routing a
90° turn). Airtime interpolates the cardinal table at the diagonal displacement — ≈ 10.5
/ 14.5 ticks — on top of a diagonal run-up step (6.552) and the shared commit penalty:
**≈ 20.1 / 24.1 total**. Every cell-to-cell transition additionally sweeps both corner
columns, one row taller than walking Diagonal checks, because the jump arc carries the
hitbox higher.

## Swimming

Fluid is a **medium**, not a pose. Everything a bot does in fluid *upright* lives on one
move (**Swim**); the prone sprint-swim family is fast lateral travel and nothing else.

**Swim** — the upright paddle, and the move that un-walls fluid: without it every river
is a wall to be bridged. It is **six-directional** — the four cardinals plus a straight
**rise** and a straight **sink** — which is the structural analogue of Climb: one
movement, several rungs, each priced from its own real vanilla rate.

- **Lateral** is priced by whether the bot's head is in air or under it: 20 ÷ 2.2 ≈
  **9.09 ticks/block** at the surface, 20 ÷ 1.97 ≈ **10.15** fully submerged.
- **Rise** is **7.41 ticks/block** and **sink** is **5.41**, derived from vanilla's
  in-fluid integrator rather than a published blocks-per-second figure (the ±0.04/tick
  swim impulse against 0.8 fluid drag and the −0.005/tick in-fluid gravity). Sinking is
  the cheaper of the two because gravity pulls the same way as the impulse.

The destination *head* cell only has to be **not solid** — air **or** fluid. That is the
real upright-fit requirement, and it is why a dry bot on the bank can walk straight into
the **body** of a waterfall instead of needing an open-air surface cell to step onto.
The lateral rungs also scan a little way **down** the neighbouring column (up to 4 cells
below the bot's feet) to find the fluid surface, so stepping off a low ledge into water
is one move rather than a fall.

**Sprint-swim** — prone swimming at 20 ÷ 5.612 ≈ **3.564 ticks/block**, the fastest way
the bot covers ground under its own power, faster even than walking. It is **lateral
travel only**: it has no straight-up or straight-down rung, because a swimming look
clamps at about 80° from horizontal, so a "vertical" heading always leaks the last ~10°
as sideways drift — recoverable in open water, but ejection at speed inside a 1×1
column. The vertical axis belongs to upright Swim, whose rise just holds jump and needs
no heading at all. Where a prone swim can actually make progress it strictly dominates
the slow upright paddle (crossing *N* cells costs 9.09*N* upright against 2 + 3.56*N* + 2
prone, and the dive already wins at *N* = 1), so the planner suppresses the slow lateral
rung there — that suppression is what makes the dive findable at all.

**Diagonal sprint-swim** — the multi-axis set of the same stroke: two-axis diagonals
(horizontal *or* rising/sinking) at 3.564 × √2 ≈ **5.04**, and true three-axis corner
moves at 3.564 × √3 ≈ **6.17** — so an underwater path cuts corners in all three
dimensions instead of staircasing. The 80° clamp doesn't reach these: an edge is 45° off
horizontal and a corner ~35°, and both want lateral velocity anyway.

**Start sprint-swim / end sprint-swim / Surface** — the pose transitions, 2 ticks each.
Sprint-swimming is *stateful*: you need 2-deep water to go prone but can continue through
shallows and 1-tall gaps once you are, so the search's node identity includes the bot's
pose — changing pose is a real search edge with a real cost, not bookkeeping.
**Start** goes prone (in place when already submerged, or fused with a one-cell dive from
the surface). **End** stands back up **in place**, and needs only two non-solid body cells
to grow into — it does *not* need open air overhead, which is what lets a submerged bot
stand up mid-water and take the upright rise. **Surface** is the remaining exit for a bot
that *can't* stand where it is — prone in a 1-tall submerged tunnel — and crawls one cell
sideways onto a standable bank, coming upright as it emerges. Together they close the
cycle the whole model turns on: sprint-swim across → stand up underwater → rise or sink
upright → go prone again → resume sprint-swimming.

**Ride bubble column** — an upward bubble column is a free elevator, and the planner
treats it as one: step in, let the column push (~0.7 blocks *per tick* — several times
faster than any climb), step out at the top. Priced at a flat 4 ticks for the step-in
and step-out plus ≈ **1.43 ticks per block of lift**, it handily beats ladders and
pillaring wherever one exists.

**Lava is the same vocabulary, at a different price.** There is no lava-only movement:
every upright Swim rung works in lava exactly as it does in water, and the *cost* does
the sorting. A lava cell is charged 2.5× the water stroke *plus* ten hitpoints of damage
for a mortal bot (~1,000+ ticks per block at defaults), so the bot crosses lava only when
every alternative is catastrophically worse — which is how a player treats it too. An
invulnerable bot (`survival.takesDamage = false`) pays only the 2.5× slow factor and
swims through. Lava also now carries a **through-slow** class for the ground moves: a
fall or a diagonal whose body passes through a lava cell is charged ≈ 6.95 ticks for it,
where it used to be charged the burn but treated as no slower than air.

The prone family is the one thing that does **not** unify, and that is vanilla's ruling
rather than ours: Minecraft's swimming pose can only be entered or held in *water*, so
sprint-swim and its diagonal set gate on water — in lava the upright rungs are the whole
vocabulary.

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
