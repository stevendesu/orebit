In the [chapter on fewer nodes](fewer_nodes.md) we taught the search to stop
wandering — a greedy heuristic and a straight-line tie-break that beeline it
toward the goal. It works beautifully on open ground. And then you ask the bot to
do the one thing it's worst at, and it falls apart completely: **build a pillar
straight up to a goal floating in the air.**

This is the page about teaching a grid search to take *big steps*, why that turned
out to need a surprisingly specific geometric object, and the two bugs that — on
the very first in-game test — froze the entire game for three seconds while
*still* failing to find the path.

## The pathology: a cone of half-built pillars

Picture the bot standing on flat ground, the goal hovering 28 blocks straight up.
The only way there is to pillar: place a block under your feet, jump onto it,
repeat. There is exactly one sensible path, and it's a straight vertical line.

A* explores **ten thousand** nodes and gives up.

The reason is subtle and worth sitting with. Pillaring is expensive — every step
*places a block*, which our cost model prices at around 4× a normal walk. The
heuristic, though, only sees *distance*: "the goal is 28 up, that's about 28."
So as the bot pillars upward, the cost it has actually paid (`g`) climbs by 4 a
step while the estimated remaining (`h`) drops by only 1 — the total `f` *rises*
as it climbs. And A* always expands the lowest-`f` node it has. So the moment the
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
that a cheap escape (cost 1) sits right past the nearest wall, and we refuse to
spend more *jumping* than it would have cost to simply *walk* to that wall.

That gives the rule: jump no farther than `distance_to_the_nearest_wall / move_cost`.
The division is the whole point. A cheap move (walking, cost ~1) gets to jump
nearly the full clearance; an expensive one (pillaring, cost ~4) gets a short
jump, because over-committing to something expensive is exactly how you sail past
a cheaper option you couldn't see. Drop the `/ move_cost` and the bot will happily
pillar twenty blocks past an exit it should have taken. We know, because every
draft that "simplified" it away reintroduced the bug.

## The partner: a heuristic that respects the climb

Collapsing the pillar into a few big jumps fixes the *vertical* waste. But the
cone had a horizontal half too — all those stub pillars at neighboring floor cells
— and macro-jumps alone don't stop the search from *wanting* the floor, because
the heuristic still rates a floor cell as just as good as a half-built pillar.

So macro-movements get a partner: a small, **provably admissible** correction to
the heuristic that finally tells it the truth about building. Near the goal we
look at the cuboid below it — the air column the bot is forced to build through —
and add its real placement cost into the estimate, but *only* the part we can
prove is forced, and only the cheapest of the goal's faces (so we never
over-estimate and refuse a genuinely better route). Now a floor cell far below the
goal carries the full weight of the climb still ahead of it, and stops looking
cheap. Macro-jumps collapse the vertical axis; the heuristic collapses the
horizontal one. Neither works alone. Together they turn that ten-thousand-node
cone into about **forty nodes** going straight up.

## The day it froze the game

That's the design. Here's what actually happened the first time we ran it in a
live game.

The bot found the pillar — a genuine first. It also **froze the entire server for
3.2 seconds**, on the main game thread, while doing it: no mobs moving, no blocks
breaking, a hard stall. The per-node cost had gone from ~700 nanoseconds to
**319,000**. And in a slightly larger area it still ran out of budget and failed.

Two bugs, and the profiler found the first one instantly — one method, 87% of the
entire search. Building the cuboid.

**Bug one: we were rebuilding the box for every cell.** Computing a cuboid means
scanning its volume, which for a wide column is thousands of reads. That's fine if
you do it *once* per region. We were doing it once per *cell* — the cache was
keyed by exact position, so two neighboring air cells, both sitting in the same
giant box, each computed that same giant box from scratch. Worse, the cache was
small enough that a big search overflowed it and fell back to recomputing on
*every single call*. The fix was to cache the **boxes themselves** and answer a
query by finding the box that contains the cell: a region gets built once, and
every cell inside it is a one-line containment check forever after. **627,000
microseconds per search dropped to 158** — about four thousand times faster.

**Bug two was quieter and meaner.** Even fast, the search still flooded in a big
enough area. The admissible heuristic — the partner that's supposed to credit the
forced climb — was measuring the climb *in the wrong direction*. It looked at the
air column and measured it *upward from the goal* (a few blocks, to the ceiling)
instead of *downward* (the 28 blocks the bot actually has to build). So the
"forced cost" it added was about a sixth of the real thing — present, but far too
weak to hold back the flood. One sign flip, and the 28-up pillar that explored
seven thousand nodes now explores **thirty-five**.

## The lessons

Three of them, and they generalize past pillars:

- **The expensive object was load-bearing, not optional.** Every instinct said
  "surely we can avoid building a whole box" — a 1-D walk, a cheaper approximation.
  Every one of them was the wrong turn we'd already taken. The box is what *proves*
  the jump is safe; without it you're guessing, and guessing wrong means walking
  through walls. When a thing is correctness-critical, "simpler" is usually
  "broken."
- **Memoize the region, not the cell.** A per-cell cache of a per-region answer
  isn't a cache — it's the same expensive computation wearing a disguise, run once
  for every cell that shares the answer. The unit of caching has to match the unit
  of the work.
- **A correction is only as good as its sign.** An admissible heuristic that
  credits the right cost in the wrong direction isn't subtly off — it's six times
  too weak, and it lets the very flood it exists to prevent right back in.

## What's still on the table

The search now expands ~100× fewer nodes — a decisive win — but each surviving
node is still pricier than the old ~700 ns, because a short search can't amortize
the one-time cost of building its cuboids. The next chapter is clawing that back:
extracting only as much box as the jump actually needs (the escape rule already
caps the jump short, so most of the tall box we build goes unused), and skipping
the bookkeeping for edits that sit *behind* the bot where they can't affect a
forward jump. The cone is gone. Now we make what's left cheap.
