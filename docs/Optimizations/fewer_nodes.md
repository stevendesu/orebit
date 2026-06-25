Our bot could not cross an open field.

Point it at a goal a few hundred blocks away over flat grass and the search would
grind through its entire budget — ten thousand candidate positions — and then give
up, leaving the bot standing there. Ten thousand positions is an absurd amount of
thinking for a walk across a meadow, and that absurdity is what this page is about:
the long, iterative hunt for *why* the search looked at so many places it didn't need
to, and how we taught it to stop.

In the [previous chapter](pathfinding_hot_path.md) we made each candidate position
cheap to examine — about 1,300 nanoseconds, down from 8,000. But the total time a
search takes is two factors multiplied together:

$$\text{search time} = \text{nodes visited} \times \text{cost per node}$$

That page hammered the right-hand factor. This one is the story of the left one,
because the cheapest position is the one you never look at. By the end, the
meadow-crossing search that died at ten thousand positions will finish in
**fifty-three**.

## How A\* decides what to look at

To shrink the count, you first have to know what makes A\* examine a position at all.
For every candidate it tracks two numbers: **`g`**, the cost to *get here* from the
start, and **`h`**, a *heuristic* guess at the cost still ahead to the goal. It always
expands the candidate with the smallest **`f = g + h`** — its best guess at the total
cost of a path through that point.

Everything lives in `h`. If `h` is `0`, you've got Dijkstra's algorithm: no guess, so
the search swells outward in every direction like ripples in a pond. A good `h` turns
those ripples into an arrow aimed at the goal. So the whole job is making `h` a sharp,
honest estimate of the real remaining cost — and every twist below is either a
sharpening of `h`, or a fix for a way that sharpening backfired.

## Fix #1: let the bot cut corners

The first suspect was the move set. The bot could only step north, south, east, and
west, so to travel diagonally it had to staircase — east, north, east, north — taking
two steps to cover what is really one diagonal move. Obvious fix: **let it move
diagonally.**

But a diagonal move needs a ruler that knows about diagonals. The textbook grid
heuristic is **Manhattan distance**, `|Δx| + |Δz|` — the number of steps if you can
*only* move along the axes. The moment the bot can cut a corner, Manhattan is wrong:
it counts a diagonal as two steps when it actually costs `√2 ≈ 1.41`. It
*over*estimates, which breaks A\*'s guarantee and, worse, actively misleads the search
about which direction is cheapest.

The honest ruler for a grid *with* diagonals is **octile distance**: go diagonally to
eat up the shorter axis, then straight for the leftover.

$$h = (d_{max} - d_{min}) \cdot 1 + d_{min} \cdot \sqrt{2}$$

We path in three dimensions, so we use the **3D octile** distance — the same idea with
one more axis. Sort the three gaps `a ≤ b ≤ c`:

$$h = a\sqrt{3} + (b - a)\sqrt{2} + (c - b)$$

Spend the smallest axis on *corner* diagonals (all three directions at once, cost
`√3`), the next on *face* diagonals (`√2`), the rest on straight steps. And — a choice
we'll come back to — we weight all three axes *equally*, with no special penalty for
going up. (Climbing isn't expensive; *placing a block* is, and that cost rides on the
move itself, not the heuristic.)

So we added the diagonal move, switched to octile, and re-ran the meadow test. The
node count went... **up**. From 209 to 604. We had handed the bot a strictly better
way to move and the search got three times *worse*.

## Fix #2: stop paying for a perfection nobody asked for

That backfire is the most important lesson in the whole story, so it's worth sitting
with. Why would a better move set make the search explore *more*?

Because an admissible heuristic doesn't just find *a* good path — it's guaranteed to
find the *shortest* one, and that guarantee has a price. To *prove* a path is
shortest, A\* has to rule out every alternative that might tie or beat it. By adding
diagonals, we'd manufactured a flood of alternatives: across open ground there are now
countless equally-short ways to weave toward the goal, and a search bent on optimality
dutifully examines every last one.

Here's the unlock: **a bot following a player does not need a *provably* shortest
path.** It needs a good one, right now. So we let the heuristic cheat, just a little —
**Weighted A\***:

$$f = g + W \cdot h, \quad W = 2$$

Multiplying `h` by `W > 1` makes the search value *progress toward the goal* over
*keeping the cost provably minimal*. It commits to a direction and stops second-
guessing. The trade is well understood: paths can come out a hair longer than optimal,
but the search visits far fewer nodes to find them.

We landed on `W = 2` by sweeping it live. At `W = 1.5` the search still wandered; at
`W = 3` it got *too* greedy and started refusing sensible detours — in one cave test a
goal it solved in a tidy 3-waypoint path at `W = 2` bloated to a clumsy 7-waypoint path
at `W = 3`. Two was the sweet spot. The meadow dropped from **604 nodes to 71.**

This is also where weighting all axes *honestly* paid off. An earlier heuristic put a
big hand-tuned penalty on vertical moves, and the bot would wastefully *build a tower*
to gain height when simply walking to nearby high ground would do — the inflated
vertical estimate made climbing look urgent. Switching to honest 3D distance, with the
real build cost living on the move, fixed both the node count *and* the behavior: the
bot now climbs existing terrain for free and only builds when it genuinely must.

## Fix #3: the field that still failed

71 nodes for the meadow — lovely. So we tried the real target: a goal **1,500 blocks
away** across flat ground. It chewed through all ten thousand nodes and failed.

Greed wasn't enough, and to understand why you have to look at *exactly* what trapped
it. Say the goal is 1,500 east and 300 north. The optimal path uses exactly 300
diagonal steps and 1,200 straight ones — but **those 300 diagonals can be placed
anywhere along the route, and every arrangement costs precisely the same.** That's not
a few equal paths; it's `C(1500, 300)` of them, an astronomical number, fanned out to
fill the entire 1,500×300 rectangle between start and goal. Every cell in that
rectangle sits on *some* optimal path, so every cell has the same `f`, so A\* has no
reason to prefer any of them. It floods the whole rectangle.

This is a **plateau** — a vast region of tied scores the search wanders across because
nothing tells it which tie to break. And critically, **greed cannot fix it**: weighting
scales every score by the same `W`, and scaling a pile of equal numbers leaves them
equal. The plateau is a tie-breaking problem, so it needs a tie-breaking answer.

Our first stab was cheap: when two candidates tie on `f`, prefer the one with the
**larger `g`**. Equal `f` plus more cost-spent means it's further along — *closer to
the goal* — so this makes the search dive toward the goal along a plateau instead of
fanning across it. It helped, but the 1,500-block rectangle was far too big for it
alone.

The real fix is to pick *one* arrangement of the diagonals — the one that tracks the
straight line from start to goal — by adding a microscopic nudge for staying near that
line:

```java
// perpendicular distance from the start→goal line, via the 2D cross product
float cross = Math.abs((x - sx) * (gz - sz) - (z - sz) * (gx - sx));
return octile(...) + H_TIE * (cross * invLineLen);
```

`H_TIE` is deliberately tiny — `0.001` — far too small to ever override a real cost
difference (the dig-under-the-wall detour still wins whenever it's genuinely shorter).
All it does is settle *ties*: among the million equal-cost cells of the rectangle, the
ones on the straight line score a hair lower, so the search threads a ~1-block-wide
corridor down the diagonal instead of flooding the field.

The size difference is the whole game. The plateau is two-dimensional — it grows with
the *area* between start and goal, `O(D^2)`. The corridor is one-dimensional — it grows
with the *distance*, `O(D)`. For 1,500 blocks that's a million cells versus about
fifteen hundred.

## The scoreboard

The same meadow-and-tree test, watched across every fix (it's a ~60-block walk up a
gentle hill to climb a tree):

| Step in the journey                   | Nodes explored | Path length |
| ------------------------------------- | -------------: | ----------: |
| Where we started (4-way, Manhattan)   |            209 |    78 steps |
| Added diagonals — and it got *worse*  |        **604** |    59 steps |
| Stopped chasing optimality (`W = 2`)  |             71 |    53 steps |
| Broke ties toward the straight line   |         **53** |    53 steps |

Look at that last row: **53 nodes explored to return a 53-step path.** That's
essentially the theoretical floor — one position examined per step of the answer,
almost nothing wasted. The bot walks straight at the goal.

And the headline — the 1,500-block field that started all this:

| Configuration                | Result                                          |
| ---------------------------- | ----------------------------------------------- |
| Greedy `W = 2`, no tie-break | **fails** — 10,000 nodes, the plateau wins      |
| ...+ straight-line tie-break | **1,493 nodes**, 1,490-step path, near-instant  |

A search that could not finish at all — that left the bot stranded in a field — now
crosses it in fifteen hundred nodes, barely more than one per step of the route.

## The cliff we haven't crossed

These fixes make A\* fast and well-behaved across open and moderately complex terrain,
which is almost everything a follow-bot meets. They are not magic, and there's one
wall they deliberately leave standing: a goal reachable *only* by pillaring straight up
into open air — a player hovering 30 blocks overhead with no terrain to climb. The
heuristic measures *distance*, and the bot is already directly below the goal, so the
straight-line trick has nothing to grip; meanwhile the true cost is 30 expensive
block-placements `h` can't see. The search fans out hunting a cheaper natural route —
the very same instinct that finds the clever way around a wall — and there simply
isn't one.

The honest fix for that isn't more heuristic cleverness; it's to stop demanding a
*complete* path before the bot will move at all. A search that returns its **best
partial progress** when it runs out of budget — walk as far as you got, then think
again from there — sidesteps the problem entirely, and it's the same machinery that
will let us path across tens of thousands of blocks by planning the route in segments.
That, plus hierarchical (region-level) pathfinding to thin the node count another order
of magnitude, is the next chapter. The per-node cost is low and the heuristic is sharp;
the frontier moves up now to the *shape of the search itself*.
