In the [chapter on the pathfinding hot path](pathfinding_hot_path.md) we drove the
*cost of a single node* down from ~8,000 nanoseconds to ~1,300, and we ended on a
promise. Recall the equation that governs the whole search:

$$\text{search time} = \text{nodes visited} \times \text{cost per node}$$

That page attacked the right-hand factor. This page is about the left one — **visiting
fewer nodes in the first place** — because the cheapest node is the one you never
look at. It turns out that with the right heuristic and a couple of tie-breaking
tricks, a search that used to give up after 10,000 nodes can find the same path in
*fifty-three*.

## A two-minute refresher on A\*

A\* explores outward from the start, and for every position it considers it tracks
two numbers:

- **`g`** — the cost to *get here* from the start (what we've actually spent).
- **`h`** — a *heuristic* estimate of the cost still ahead, from here to the goal.

Their sum, **`f = g + h`**, is A\*'s guess at the total cost of the best path
*through* this position. The search keeps all its candidates in a priority queue and
always expands the one with the lowest `f` — the most promising — next.

The magic is entirely in `h`. If `h` never *overestimates* the true remaining cost
(a property called being **admissible**), A\* is guaranteed to find the shortest
path. Set `h = 0` and you've got Dijkstra's algorithm: no guess at all, so it
expands outward in every direction like ripples in a pond — correct, but it explores
a giant disk. A good `h` is what turns those ripples into an arrow pointing at the
goal.

So the whole game is: **make `h` a tight, honest estimate of the real remaining
cost.** Get it right and A\* barely strays from the optimal path. Get it wrong and
you're back to exploring a pond. Almost everything below is a consequence of taking
that one sentence seriously.

## What distance even means on our grid

The textbook heuristic for grid movement is **Manhattan distance** — `|Δx| + |Δz|`,
the number of steps if you can only move along the cardinal axes. It's the right
answer *if* the bot can only move N/S/E/W. But our bot can also move **diagonally**,
and a diagonal step covers one block east *and* one block north for the price of
`√2 ≈ 1.41` — cheaper than the two separate steps Manhattan assumes. So Manhattan
*over*estimates any diagonal route, which breaks admissibility and, worse, actively
lies to the search about which way is cheapest.

The honest distance for a grid *with* diagonals is the **octile distance**: travel
diagonally to cover the shorter axis, then straight for whatever's left over.

$$h = (d_{max} - d_{min}) \cdot 1 + d_{min} \cdot \sqrt{2}$$

We path in 3D, though — the bot climbs and descends — so we use the **3D octile**
distance, the same idea with one more axis. Sort the three gaps `a ≤ b ≤ c`, then:

$$h = a\sqrt{3} + (b - a)\sqrt{2} + (c - b)$$

— spend the smallest axis on *corner* diagonals (moving in all three directions at
once, cost `√3`), the next on *face* diagonals (two directions, `√2`), and the
remainder on straight steps. It's a handful of `abs`, two compares to sort, and a
little arithmetic — a rounding error next to the block reads it sits beside.

### Why vertical isn't special

You might expect climbing to cost *more* than walking — and an earlier version of
our heuristic did exactly that, weighting vertical moves heavily with some
hand-tuned magic numbers. We threw that out. The insight: **going up isn't
intrinsically expensive — *placing a block* is.** Ascending a staircase that already
exists is just "walk forward while jumping," which takes about as long as a flat step
while covering a diagonal of real distance. The only thing that genuinely costs extra
is having to *build* your way up (or *dig* your way down), and that cost lives on the
move itself — it's already counted in `g`. So the heuristic gets to be pure, honest
3D distance, with every axis weighted the same. No magic constants, and a cleaner
result: the bot now prefers to climb existing terrain and only builds when it truly
must, because the build cost shows up exactly where it belongs.

## The plateau problem

Here's where it gets interesting, and it's the part most A\* tutorials skip.

A perfectly admissible, perfectly tight heuristic has a surprising failure mode on
open ground. Picture a flat plain and a goal 1,500 blocks away — not straight along
an axis, but off at an angle, say 1,500 east and 300 north. The optimal path uses
exactly 300 diagonal steps and 1,200 straight steps. But here's the thing:
**those 300 diagonals can be placed *anywhere* along the route, and every
arrangement costs exactly the same.**

That's not a handful of equally-good paths — it's `C(1500, 300)`, an astronomical
number of them, and they fan out to fill the entire 1,500×300 rectangle between
start and goal. To A\*, every cell in that rectangle sits on *some* optimal path, so
every cell has the same `f`, so A\* has no reason to prefer any of them. It dutifully
explores the whole rectangle — a million-plus cells — and slams into our 10,000-node
budget having covered a tiny corner of it. **The bot fails to cross an empty field.**

This is a *plateau*: a vast region of tied `f`-scores the search wanders across
because nothing tells it which tie to break. And it's worth noticing what *doesn't*
fix it. We do run a slightly greedy search (more on that next), but greed scales the
whole heuristic up or down — it can't choose between paths that are *already exactly
equal*. Adding diagonal moves doesn't fix it either; it actually made it *worse*
(more ways to tie). The plateau is a tie-breaking problem, and it needs a
tie-breaking fix.

## Trick #1: break ties toward the goal

The first, cheap improvement is in the priority queue itself. When two candidates
have the same `f`, which should we expand first? The textbook answer is "doesn't
matter." But it does: among tied nodes, the one with the **larger `g`** is the one
that's already travelled further from the start — which, since `f = g + h` is equal,
means it's *closer to the goal*. Preferring it makes the search dive toward the goal
along a plateau instead of fanning out across it. It's optimality-neutral (we only
reorder ties) and on the big equal-`f` plains a follow-bot hits constantly, it pops
dramatically fewer nodes.

## Trick #2: a pinch of greed

A\* with an admissible heuristic is *optimal* — but optimality is expensive, because
to *prove* a path is shortest the search must rule out all the almost-as-good
alternatives. For a bot following a player, we don't need a provably perfect path;
we need a good one, fast. So we let the heuristic cheat, just a little:

$$f = g + W \cdot h, \quad W = 2$$

Multiplying `h` by a weight `W > 1` makes the search greedier — it values "getting
closer to the goal" more than "keeping the cost low," so it commits to a direction
and stops second-guessing. This is **Weighted A\***, and the trade is well
understood: paths can come out slightly longer than optimal, but the search visits
far fewer nodes to find them.

`W = 2` is a balance point we found by sweeping it in-game. At `W = 1.5` the search
explores more; at `W = 3` it gets *too* greedy and starts taking visibly worse
detours — in one cave test, a goal it solved in a tidy 3-waypoint path at `W = 2`
ballooned to a clumsy 7-waypoint path at `W = 3` because the search refused to
backtrack properly. Two is the sweet spot: noticeably faster, paths still clean.

## Trick #3: hug the straight line

Greed helps, but remember — it **can't break the plateau**, because the tied paths
are *exactly* equal and scaling them all by `W` keeps them exactly equal. To collapse
that 1,500×300 rectangle we need to make A\* *prefer* one specific arrangement of the
diagonals: the one that tracks the straight line from start to goal.

So we add a microscopic nudge to the heuristic, proportional to how far the node has
strayed *sideways* from the direct start→goal line:

```java
// perpendicular distance from the start→goal line, via the 2D cross product
float cross = Math.abs((x - sx) * (gz - sz) - (z - sz) * (gx - sx));
return octile(...) + H_TIE * (cross * invLineLen);
```

`H_TIE` is deliberately tiny — `0.001`. It's far too small to ever override a real
cost difference, so it never talks A\* out of a genuinely cheaper route (the
dig-under-the-wall detour still wins when it's actually shorter). All it does is
settle *ties*: among the million equal-cost cells of the rectangle, the ones sitting
on the straight line score a hair lower, so the search threads a ~1-block-wide
corridor straight down the diagonal instead of flooding the whole field.

The effect on the math is dramatic. The plateau is two-dimensional — its size grows
with the *area* between start and goal, `O(D^2)`. The corridor is one-dimensional —
it grows with the *distance*, `O(D)`. For a 1,500-block path that's the difference
between a million cells and about fifteen hundred.

## The measurements

Here's a short, real test: the bot climbing a gently sloping hill toward a tree
roughly 60 blocks away, about 60 blocks of actual path. Watch the node count fall as
each refinement lands:

| Configuration                         | Nodes explored | Path length |
| ------------------------------------- | -------------: | ----------: |
| 4-connected, Manhattan heuristic      |            209 |    78 steps |
| ...add diagonal moves (admissible)    |        **604** |    59 steps |
| ...3D octile + greedy weight `W = 2`  |             71 |    53 steps |
| ...+ straight-line tie-break          |         **53** |    53 steps |

Two things to notice. First, adding diagonals made the search *worse* before it got
better — 209 nodes up to 604 — exactly the plateau effect: diagonals gave the search
more equally-good paths to get lost among, and it took the tie-break to rein them in.
Second, look at the final row: **53 nodes explored to produce a 53-step path.** That
is very nearly the theoretical floor — the search examined essentially *one cell per
step of the path it returned*, with almost nothing wasted. It walks straight at the
goal.

And the headline case — the off-angle goal across open ground, ~1,500 blocks out:

| Configuration                | Result                          |
| ---------------------------- | ------------------------------- |
| Greedy `W = 2`, no tie-break | **fails** — hits 10,000 nodes, plateau wins |
| ...+ straight-line tie-break | **1,493 nodes**, 1,490-step path, near-instant |

A search that *could not complete at all* — that left the bot standing in a field
unable to walk across it — now finishes in fifteen hundred nodes, again barely more
than one node per step of the answer.

## What we don't claim

These tricks make A\* fast and well-behaved over open and moderately complex terrain,
which is the overwhelming majority of what a follow-bot meets. They are not magic.
There's one case they deliberately don't solve: a goal reachable *only* by pillaring
straight up into open air — a player hovering 30 blocks overhead with no terrain to
climb. The heuristic measures *distance*, and the bot is already directly below the
goal (zero horizontal distance), so the straight-line trick has nothing to grip;
meanwhile the real cost is 30 expensive block-placements the heuristic can't see. The
search rationally fans out looking for a cheaper natural route — the same instinct
that finds the clever path around a wall — and there isn't one.

The honest fix for that isn't more heuristic cleverness; it's to stop demanding a
*complete* path before the bot moves at all. A search that returns its **best partial
progress** when it runs out of budget — walk as far as you got, then think again from
there — sidesteps the whole problem, and it's the same machinery that will let us
path across tens of thousands of blocks by computing the route in segments. That,
together with hierarchical (region-level) pathfinding to shrink the node count
another order of magnitude, is the next chapter. The per-node cost is low, the
heuristic is sharp — now the frontier moves up to the *shape of the search itself*.
