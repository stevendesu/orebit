In the [chapter on fewer nodes](06_fewer_nodes.md) we taught the search to stop
wandering — a greedy heuristic and a straight-line tie-break that beeline it
toward the goal. It works beautifully on open ground. And then you ask the bot to
do the one thing it's worst at, and it falls apart completely: **build a pillar
straight up to a goal floating in the air.**

This is the page about teaching a grid search to take *big steps*: why that turned
out to need a surprisingly specific geometric object, and the careful piece of
arithmetic that keeps a search full of shortcuts honest — never jumping past a better
route, never talking itself out of the optimal path.

## The pathology: a cone of half-built pillars

Picture the bot standing on flat ground, the goal hovering 28 blocks straight up.
The only way there is to pillar: place a block under your feet, jump onto it,
repeat. There is exactly one sensible path, and it's a straight vertical line.

A* explores **ten thousand** nodes and gives up.

The reason is subtle and worth sitting with. Pillaring is expensive — every step
*places a block*, and placing costs a base fee plus the time you'll later spend
mining that block back out, so a single pillar step runs about **25 ticks** against
a plain walk's ~4.6. The heuristic, though, only sees *distance*: "the goal is 28 up,
that's about 28 blocks." So as the bot pillars upward, the cost it has actually paid
(`g`) climbs by ~25 a step while the estimated remaining (`h`) drops by only one
block's worth (~4.6) — the total `f` *rises* as it climbs. And A* always expands the lowest-`f` node it has. So the moment the
partial pillar gets expensive enough, A* abandons it and goes to try pillaring
from the cell *next door* instead, which looks cheaper because it hasn't started
yet. It does this everywhere. The explored set is a literal **cone**: a little
stub of pillar at every floor cell, none of them finished, fanning out across the
whole region until the budget runs out.

We confirmed the shape with a tracing tool — **99.8% of the expanded nodes were
off the goal column.** The search wasn't a little inefficient. It was building a
pyramid.

## The idea: jump the whole run at once

The fix comes from an old observation in grid pathfinding (Jump Point Search, if
you want the literature): when a long stretch of the world is *uniform*, expanding
it one cell at a time is pure waste. Every cell looks like the last; nothing
interesting can happen in the middle. So don't expand them one at a time — **emit
a single move that jumps the entire run.** One pillar-up node that climbs 8 blocks
instead of 8 nodes that climb 1 each. The search shrinks by the length of every
uniform run it would otherwise have crawled.

Simple enough. The trouble is in the word *uniform*, and in knowing how far is
safe to jump.

## Why you cannot just walk up the column

Here is the tempting shortcut, and it is **wrong**: to collapse a pillar, walk up
the column of air counting cells, and jump however far the air goes.

It's wrong because a 1-D line knows nothing about its sides. Picture a `1×30×1`
column of air — but at `Y+3`, beside the column, the bottom of a floating
staircase that leads straight to the goal with no more building required. Stepping
off onto it is the *optimal* path. The 1-D walk sees "air all the way up, 30
blocks" and jumps clean past the exit, missing it entirely.

The only way to know a jump is safe is to know that the whole *cross-section*
around it stays uniform for the length of the jump — that no exit hides alongside.
And the moment you start checking the sides as you climb, you are no longer walking
a line. **You are computing a box.** There is no cheaper object that gives the
guarantee. So that's what we build: the maximal **cuboid** of one navtype that
contains the cell — and crucially, among the boxes that fit, the one that's
*widest* across and *shortest* along the direction of travel. Wide-and-short is
what makes the box stop exactly at the staircase instead of sailing past it: the
box's own height becomes the jump limit, and a box that can't grow past `Y+3`
can't let the jump grow past it either.

## How far is safe: divide by the cost

A box tells you the run is uniform. It does *not* tell you how far to jump — and
here's the part we argued about for the longest. Inside the box everything is the
same substrate, so the better move, if one exists, is *just past the box's side
wall*, in terrain we haven't looked at. We can't see it. So we assume the worst:
that a cheap escape sits right past the nearest wall, and we refuse to spend more
*jumping* than reaching that wall would cost.

That gives the rule: jump no farther than `clearance_to_the_nearest_side_wall /
move_cost`, with the move's cost measured in real ticks. The division is the whole
point. A cheap move gets a long jump; an expensive one gets a short jump — a ~25-tick
pillar step is reined in to roughly a fifth of what a ~4.6-tick walk would get over
the same clearance, because over-committing to something expensive is exactly how you
sail past a cheaper option you couldn't see. Drop the `/ move_cost` and the bot will happily
pillar twenty blocks past an exit it should have taken. We know, because every
draft that "simplified" it away reintroduced the bug.

## The partner: a heuristic that respects the climb

Collapsing the pillar into a few big jumps fixes the *vertical* waste. But the
cone had a horizontal half too — all those stub pillars at neighboring floor cells
— and macro-jumps alone don't stop the search from *wanting* the floor, because
the plain octile heuristic still rates a floor cell as just as good as a half-built
pillar. Distance is all it sees, and a goal floating in the air is no farther, as the
crow flies, from the ground than from a cell partway up. It cannot see that reaching
that goal *forces* an expensive climb.

So macro-movements get a partner: a correction to the heuristic that finally tells it
the truth about building. The whole trick is making that correction **provably
admissible** — never an over-estimate. This matters more than it sounds: an admissible
heuristic can never talk the search out of the best route, because the moment you
over-charge *some* path, A\* is free to reject the very one that was optimal. So the
entire design bends toward one rule — **when in doubt, credit less.**

Here is how it earns that. Once, at the start of the search, we probe the goal's
**six faces** — the six cuboids that butt up against it — and ask each what it would
cost to *enter the goal from that side*:

- A wide flat **air** cuboid *below* the goal means the only way in is to **build
  up**, so the forced per-block cost is a pillar step: the move plus its placement
  (base cost plus the placed block's mine-out premium), read in real ticks.
- A **solid** cuboid to the *side* means the only way in is to **dig**, so the forced
  cost is a break step: the move plus the mining time of the *best possible* tool (the
  probe doesn't know the bot's inventory, and a lower bound must assume the fastest
  dig any tool could manage).

For each face we charge only the *extra* over what the octile already credits —
`forced_cost − one_walk_tick` per block — and only over the cuboid's proven **extent**:
we can show that at least *this many* blocks are expensive; past the box we know
nothing, so we charge nothing.

Then the admissibility clincher: **we compare all six faces and keep the smallest
premium.** If even one face offers a cheap way in — an adjacent cell you could simply
stand on — its premium is zero and the whole correction collapses to zero. Taking the
minimum guarantees we never charge more than the cheapest real approach costs, so we
can never refuse a route that approach would have found. (It also means an up-and-over
goal is credited for only its *cheaper* axis, never the sum of both: charging for two
would double-count the staircase they share, and over-estimate.)

Now a floor cell far below the goal carries the full weight of the climb still ahead of
it, and stops looking as cheap as a cell partway up. Macro-jumps collapse the vertical
axis; this collapses the horizontal one. Neither works alone. Together they turn that
ten-thousand-node cone into about **forty nodes** going straight up.

## What's still on the table

The search now expands ~100× fewer nodes — a decisive win — but each surviving
node is still pricier than the old ~700 ns, because a short search can't amortize
the one-time cost of building its cuboids. The next chapter is clawing that back:
extracting only as much box as the jump actually needs (the escape rule already
caps the jump short, so most of the tall box we build goes unused), and skipping
the bookkeeping for edits that sit *behind* the bot where they can't affect a
forward jump. The cone is gone. Now we make what's left cheap — and that hunt,
where the ideas stop being obviously right and start needing a referee, is
[the next chapter](08_measure_everything.md).
