Our bot could not cross an open field.

Point it at a goal a thousand-odd blocks away over flat ground and the search would
grind through its entire budget — ten thousand candidate positions — and then give up,
leaving the bot standing there. (Why ten thousand? At roughly 950 nanoseconds per
position — see the [previous chapter](05_pathfinding_hot_path.md) — that's about 9.5
milliseconds, nearly a fifth of the server's 50 ms tick. Let one bot's thinking grow
past that and it starts stealing time from everything else, so we cap it.) Ten thousand
positions is an absurd amount of thinking for a walk across a meadow, and that absurdity
is what this page is about: the long, iterative hunt for *why* the search looked at so
many places it didn't need to, and how we taught it to stop.

The previous chapter made each candidate position cheap to examine — about 950
nanoseconds, down from 8,000. But the per-node price is only part of the bill:

$$\text{search time} = \text{startup cost} + (\text{nodes visited} \times \text{cost per node})$$

That page hammered the cost *per node*. The startup cost — building the search's scratch
structures once, before the first node — is a fixed fee that matters most for short
searches. This page is about the middle factor, the **node count**, because the cheapest
position is the one you never look at. By the end, that field falls in
about **fifteen hundred** positions, and a shorter test we lean on to watch each fix
land tightens all the way to **fifty-three** — one position examined per step of the
path it returns.

## How A\* decides what to look at

Recall the rule from [the hot-path chapter](05_pathfinding_hot_path.md): A\* always
expands the node with the smallest **`f = g + h`**, where **`g`** is the cost already
spent to reach it and **`h`** is the heuristic's guess at the cost still ahead. This
chapter is the story of that **`h`**.

Almost everything lives in it. If `h` is `0` you've got Dijkstra's algorithm: no guess,
so the search swells outward in every direction like ripples in a pond. A good `h` turns
those ripples into an arrow aimed at the goal.

One more thing matters: **what a move costs.** Not every move is equal, and we measure
them all in one honest currency — **game ticks** (20 to a second), each move priced at
the real time it takes (the full table is in [Movements](../movements.md)). A plain step
to an adjacent block is the yardstick: **~4.6 ticks**. Breaking a block in the way costs
its real **mining time**. And placing a block — to bridge a gap or build a step up —
costs a base fee *plus the time you'll spend mining it back out someday*: with a stone
pickaxe, placing cobblestone runs about **20 ticks** (a 6-tick base plus ~15 to remove it
later), so it's the priciest thing the bot routinely does. Walking is cheap; rearranging
the world is dear. The heuristic is scored in the same ticks — remaining distance times
that ~4.6-tick walk cost — so `g` and `h` compare apples to apples. Hold onto the gap
between a ~4.6-tick step and a ~20-tick placement; it drives half of what follows.

## The test bench

You can't tune what you don't measure, so every change below was judged live, in-game,
against the same four scenarios. They come up by name throughout:

- **The field** — a flat world, walking from near spawn (around `x=30, z=60`) out to
  `(1000, 1000)`. Not a clean 45° diagonal, which turns out to matter enormously. This
  is the search that couldn't finish.
- **The hill** — a normal generated world: a gentle slope up to a tree the bot must
  climb, about 60 blocks away. Short and repeatable, so it's our main yardstick — most
  of the running numbers below are this test.
- **The tower** — a flat world with the goal floating 30 blocks straight up. The
  pathological case we'll end on.
- **The cave** — a little maze hand-built from bedrock, where the straight line to the
  goal runs into a wall and the bot must back off and round a corner.

That last one came with a lesson in test design: I built the cave to *force* a detour
around the wall, but the wall was bedrock and the *floor* wasn't, so the bot took one
look, tunneled straight *under* it, and popped up next to me in three moves. The test
was wrong; the bot was right.

## Fix #1: make climbing worth considering

The first trouble was going *up*. Picture a goal one block higher than the bot, with no
stairs leading to it. To reach it the bot has to build a step — place a block, jump onto
it. That step costs about **25 ticks**: the ~4.6-tick hop plus the ~20-tick placement
(remember, most of that placement fee is the time the bot will spend mining the cobble
back out later). But the heuristic only knows *distance*, and "one block up" is almost no
distance at all — the goal barely got closer. So to the search, building that step looked
absurdly expensive for the progress it bought, and it went hunting for a cheaper way up.
Twenty-five ticks buys five or six blocks of walking, so the bot would stroll five or six
blocks sideways looking for a natural ramp *before* it would agree to build the obvious
staircase right where it stood — and with a weaker pickaxe, closer to nine. It wandered
the neighborhood looking for a slope rather than build one.

The fix was to put a thumb on the scale: treat vertical distance as worth more than
horizontal. Any move that closed the gap to a goal above (or below) earned extra
heuristic credit, so climbing looked attractive enough that the bot would build when it
had to instead of wandering off. It worked — and, as we'll see, it was also a hack we'd
eventually tear out for something cleaner.

## Fix #2: break ties on purpose

Classic A\* tells you to expand the lowest-`f` candidate, but it says *nothing* about
what to do when several candidates tie. Left to chance, the search picks among them
arbitrarily — jittering around the frontier instead of flowing anywhere.

So we gave it a rule: when `f` ties, prefer the candidate with the larger **`g`**. Since
`f = g + h` is equal, more cost-spent means more progress made — it's the position
*further along the path we've already started*. In plain terms: "these all look equally
good, but we've already committed to this one, so keep going and see where it leads." The
search now flows smoothly toward the goal along a single line, and only doubles back to
reconsider the alternatives when it hits a position that costs *more* than the heuristic
promised. Hold that last clause — it's the hinge the next two fixes turn on.

## Fix #3: let it cut corners

The bot could only step north, south, east, and west, and the real cost of that is
subtle. To travel northeast it had to go north-then-east — but north-then-east lands on
the *exact same block, for the exact same cost*, as east-then-north. The two routes are
identical in value, so A\* has no grounds to prefer either, and has to keep both alive.

On flat ground the tie-breaker from Fix #2 papers over this — it just barrels along
whichever route it started. But the instant the path meets something expensive — say,
the tree at the end of the hill, which the bot has to climb — that one pricey move costs
more than the heuristic predicted, and suddenly every one of those tied, set-aside
routes looks like it *might* have been cheaper. *Maybe one of them led to a ramp that
skips the tree entirely.* So A\* goes back and checks them. The more tied routes there
are, the more checking.

Now, the reason we expected diagonals to *help* isn't just "a diagonal move is cheaper."
It's that it takes **fewer moves to arrive**. Our hill path was 78 steps: roughly 50
forward, 25 to the side, and 3 up the tree. Every one of those 78 is a position even a
perfect search must touch. Let the bot move diagonally and 25 of those
forward-and-sideways pairs collapse into 25 single diagonal steps — about 25 diagonal +
25 forward + 3 climb ≈ **53 steps**. A shorter path is fewer positions to visit, full
stop.

But a diagonal move needs a ruler that counts it correctly. **Manhattan distance**
(`|Δx| + |Δz|`) assumes you can only move along the axes, so it scores a diagonal as 2
when it really costs `√2 ≈ 1.41`. Overcounting both breaks A\*'s guarantee and actively
misleads the search. The honest ruler is **octile distance** — go diagonally to eat the
shorter axis, then straight for the rest:

$$h = (d_{max} - d_{min}) \cdot 1 + d_{min} \cdot \sqrt{2}$$

We path in 3D, so we use the **3D octile** distance, the same idea with one more axis.
Sort the three gaps `a ≤ b ≤ c`:

$$h = a\sqrt{3} + (b - a)\sqrt{2} + (c - b)$$

So: diagonals in, octile ruler in. And the hill went from 209 positions to **604**. We
had shortened the ideal path and *tripled* the work. That's the tied-routes mechanism
biting exactly as described — diagonals handed the search far more equally-good ways to
approach, and the tree-climb at the end sent it backtracking through all of them.

## Fix #4: stop proving, and stop lying about "up"

That 604 is the price of *optimality*. To guarantee the shortest path, A\* has to rule
out every alternative that might tie or beat it — and we'd just manufactured a glut of
ties. But a bot following a player doesn't need a *provably* shortest path; it needs a
good one, now. So we let the heuristic cheat — **Weighted A\***:

$$f = g + W \cdot h, \quad W = 2$$

Multiplying `h` by `W > 1` makes the search value *getting closer* over *staying
provably cheapest*. It commits to its trajectory and stops fanning back through the
set-aside routes every time it hits a bump. We landed on `W = 2` by sweeping it live: at
`W = 1.5` it still wandered; at `W = 3` it got *too* greedy and refused sensible
detours — on the cave, a route it solved in a tidy 3-move path at `W = 2` bloated to a
clumsy 7-move path at `W = 3`. Two was the sweet spot.

This is also where we tore out Fix #1. We'd been inflating vertical distance to coax the
bot into building — but that was medicating a symptom. The real insight: **going up
isn't expensive; placing the block is** — and that ~20-tick cost already lives on
the move itself. Vertical distance is just distance. So we dropped the vertical weighting
for honest, equal-axis 3D octile and let the place cost do the discouraging. Two payoffs
at once: the bot stopped wandering for ramps *and* stopped over-building, because a built
step now costs exactly what it costs — no thumb on the scale.

The hill dropped from 604 to **71**.

## Fix #5: the field that still failed

71 on the hill, paths clean. So we aimed at the field — `(1000, 1000)` from near spawn.
It chewed through all ten thousand positions and failed.

This one genuinely puzzled us, because the tie-breaker from Fix #2 *should* have just
marched the bot straight across: commit to the diagonal, ride it to the goal, done in
about a thousand positions. And on a truly empty field, it does exactly that. The catch
was that our "flat" test world had structures left enabled — and a **village** sat
squarely on the route. An obstacle is precisely the trigger from Fix #3: to get around
the village the bot meets the same kind of expensive bump as the tree-climb, and starts
asking whether one of its tied alternatives slips past on the *other* side.

And here's the scale of "its tied alternatives." The field runs 970 east and 940 north —
so nearly a 45° diagonal you'd think there's only one route. There isn't. The path is
940 diagonal steps plus 30 plain east steps, and those 30 easts can be slotted in
*anywhere* among the 970. The number of equally-cheap routes is

$$\binom{970}{30} \approx 9.6 \times 10^{56}$$

— within spitting distance of the number of atoms in the solar system. The tie-breaker
threads that incomprehensible space without complaint on open ground. But drop a village
in the middle and the search has near-infinitely many equally-good ways to weave around
it, and it drowns trying to weigh them.

The fix is to hand the search a single *canonical* thread to prefer: among tied routes,
favor the one that hugs the straight line from start to goal. We add a microscopic nudge
to `h`, proportional to how far the position has strayed sideways from that line:

```java
// perpendicular distance from the start→goal line, via the 2D cross product
float cross = Math.abs((x - sx) * (gz - sz) - (z - sz) * (gx - sx));
return octile(...) + H_TIE * (cross * invLineLen);
```

`H_TIE` is deliberately tiny — `0.001` — far too small to ever override a real cost
difference, so it never argues the bot out of a genuinely cheaper route (the
dig-under-the-wall detour still wins when it's actually shorter). All it does is settle
*ties*: of those `9.6 × 10⁵⁶` equal routes, the ones on the straight line score a hair
lower. Now there's always one preferred way to slip past an obstacle — the on-line one —
and the search stops exploring both sides of every village.

The hill dropped one last notch, 71 → **53**: one position examined per step of the
path. And the field that could not be crossed now crosses in **1,493**.

## The scoreboard

The hill test (the ~60-block climb to the tree), watched across every fix. The earliest
numbers predate our instrumentation, so the first row is an estimate — but the path
length is exact, and that's the number that tells the real story:

| Step in the journey                          | Positions visited | Path length |
| -------------------------------------------- | ----------------: | ----------: |
| Plain A\*, no tie-break                      | ~thousands (est.) |    78 steps |
| Break ties toward the goal (Fix #2)          |               209 |    78 steps |
| Add diagonals + the octile ruler (Fix #3)    |           **604** |    59 steps |
| Greed + honest 3D distance (Fix #4)          |                71 |    53 steps |
| Hug the straight line (Fix #5)               |            **53** |    53 steps |

Two things stand out. First, Fix #3 made the search *worse* (209 → 604) even as it made
the *path* shorter — the tied-routes tax, paid in full. Second, the final row: **53
positions visited to return a 53-step path.** That's the theoretical floor — one
position per step, essentially nothing wasted. The bot walks straight at the goal.

And the headline — the field that started all this:

| Configuration                | Result                                          |
| ---------------------------- | ----------------------------------------------- |
| Greedy `W = 2`, no tie-break | **fails** — 10,000 positions, drowns at the village |
| ...+ straight-line tie-break | **1,493 positions**, 1,490-step path, near-instant  |

## The cliff we haven't crossed

These fixes make A\* fast and well-behaved across open and moderately complex terrain —
almost everything a follow-bot meets. They are not magic, and there's one wall they
leave standing: **the tower** — a goal reachable *only* by pillaring straight up into
open air, a player hovering 30 blocks overhead with no terrain to climb.

It's a pointed irony. The hand-tuned vertical weighting from Fix #1, for all its faults,
*did* coax the bot up a pillar — and we deliberately tore it out in Fix #4 for honest
distance. So the tower is the one case our cleanup made worse. But the answer isn't to
bring the hack back. Look at what the honest heuristic sees: the bot is directly below the
goal — zero horizontal distance, so the straight-line trick has nothing to grip — while
the true cost is thirty ~25-tick placements `h` cannot see. Each pillar step raises `g` by
~25 while dropping `h` by barely one block's worth, so the *total* `f` climbs as the bot
builds. And A\* always expands its lowest-`f` node — so the instant a half-built pillar
gets pricey, the search abandons it and starts a fresh one from the cell next door, which
looks cheaper only because it hasn't started paying yet.

And here's the compounding cruelty. We just measured that the bot will wander five to nine
blocks before it agrees to *one* placement — so it would genuinely rather wander three
cells and pillar once than stand still and pillar twice. Faced with a 30-tall climb it
spreads the cost outward instead of upward, stubbing a partial pillar at every floor cell
in a widening **cone**. The search doesn't fail for lack of speed; it drowns in half-built
pillars.

The honest fix *is* more heuristic cleverness — the very opposite of a hand-tuned hack. If
the only way to the goal is to build straight up, that build cost isn't optional; it's
*forced* — and a heuristic taught to charge for it up front would stop treating the
vertical as free distance and stop fanning out. Teaching `h` to see that **forced cost**,
and collapsing the whole uniform pillar into a single big move so the search never crawls
it cell by cell, is the [next chapter](07_cuboid_macro_movements.md). The per-position cost
is low and the heuristic is sharp; the frontier moves up now to the *shape of the search
itself*.
