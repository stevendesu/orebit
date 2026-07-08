# Teaching the Block Search the Map

[Fewer Nodes](fewer_nodes.md) ended on a cliff we hadn't crossed — **the tower**, a
goal reachable only by pillaring straight up into open air — and named the fix:
"hierarchical (region-level) pathfinding to thin the count another order of magnitude."
This is that chapter. It turns out the region tier does more than shrink the search;
handed to the block planner correctly, it becomes a *map the fine search can read*, and
that changes what "hard" even means.

## The block search is topology-blind

The block A\* is guided by a heuristic — 3D octile distance to the goal, scaled by the
greed weight. That heuristic is a straight-line estimate, and a straight line knows
nothing about walls.

Picture the goal on the far side of a thin stone wall, or at the bottom of a cave whose
only entrance is twenty blocks the other way, or — the case that started this — a chunk
of buried ore with no open face at all. Octile points the bot **straight at the goal**
every time. So the search fans out toward the goal, hits the wall, and starts hunting
for a cheaper natural way through — the very same instinct that finds the clever route
around an obstacle, except here it fans across the whole open volume because the
straight-line estimate keeps insisting the goal is *right there*. On the flood scenario
that named this arc, the block search burned past **forty thousand candidate positions**
looking for a way to a goal it could have reached by walking around one corner.

The maddening part is that we already know the way around. The
[region tier](../worldmodel.md#region-level) plans over a coarse graph of the world; by
the time the block search starts, a *region skeleton* already exists that says, in
effect, "up through this pocket, over one region, down into the cave." The block search
just never asks. The skeleton bounds *where* it searches (the sliding window), but inside
that window the heuristic is still the topology-blind octile.

So the fix is not more heuristic cleverness. It's to let the fine search **read the
coarse map**.

## A cost-to-goal field

Here is the shape of the answer. The region tier can compute, for every region near the
route, *what the coarse graph thinks it costs to reach the goal from there*. Feed that
number to the block heuristic as a second, topology-aware estimate, and take the larger
of the two:

$$ h(\text{cell}) = \max\big(\underbrace{\text{octile}(\text{cell} \to \text{goal})}_{\text{straight line}},\; \underbrace{\text{region cost-to-goal}(\text{cell})}_{\text{through the map}}\big) $$

The `max` is the whole trick, and it must be a `max`, never a sum — both terms estimate
the same thing (the cost from this cell to the goal), so adding them double-counts. But
taking the larger keeps the **tighter lower bound**. A sky cell whose region loops all
the way back to the goal now reads *high* — the coarse graph knows that "straight line
short, real route long" cell for what it is — and A\* stops flooding it. Both terms are
lower bounds, so the combined heuristic stays admissible in spirit; it just stops lying
about the cells the wall hides.

We compute the field with a **goal-rooted reverse Dijkstra** over the fragment graph:
seed the goal region at zero, exhaust a heap bounded to a box around the start and goal,
and record the cheapest settled cost for each region. It reuses the *exact same edge
model* the forward region A\* uses — the only differences are that it starts at the goal
and never goal-tests (it floods the whole box), and that it runs shortest-first with the
heuristic suppressed. One coarse flood per window re-plan; then every one of the block
search's millions of heuristic reads is a single array lookup. (What that one flood
actually *costs* turned out to be a story of its own — the
[next chapter](field_build.md).)

There's a calibration subtlety worth a sentence. The forward region search prices a
pillar-up dear and a fall cheap on a *compressed* scale that's self-consistent for
coarse routing but wrong as a *block* estimate. So the reverse field re-derives its
vertical costs in block-honest ratios — falling *into* the goal is cheap, not
pillar-dear — otherwise the walk-around routes read far above the dig-down and the
heuristic would push the bot to tunnel when it should stroll.

## The field was flat, so we gave it a slope

The first version stored one cost per region — and immediately taught us something. A
region is sixteen blocks across, but every cell inside it read the *same* cost-to-goal.
So within a region the heuristic was a plateau: no pull toward the exit, and the block
search flooded each region uniformly on its way through. We'd made the sky a swamp and
then filled every region with standing water.

The fix restores the gradient the constant threw away. The reverse Dijkstra already knows,
for each region, the neighbour it should leave toward — its own parent in the goal-rooted
flood. So we record two things per region: the **goalward exit opening** (the crossing
cell toward that parent) and the **onward cost** (the parent's cost-to-goal). Then a cell
reads:

$$ \text{cost-to-goal}(\text{cell}) = \text{octile}(\text{cell} \to \text{exit opening}) + \text{onward} $$

Now the number *slopes*. At the goal's own region it collapses to plain octile-to-goal
(exit is the goal, onward is zero — no double-count, the `max` is a no-op there). At a
region's goalward exit it reads the onward cost. And out at the far corner of a region it
reads high, because from there you really do have to cross to the exit first. The block
search feels the slope and threads toward the opening instead of pooling.

One honest note, because [Measure Everything](measure_everything.md) is the house
religion: the gradient found a genuinely *cheaper* path on the flood scenario (cost 825
versus the flat field's 888, and the move mix flipped from digging down to climbing up
and over) — but it did **not** cut the expansion count. Traced honestly, that's
weighted-A\*'s doing, not the gradient's: at greed weight 2 the search re-opens closed
nodes when a cheaper route to them appears, and the flood zone is exactly where that
churns. Every re-popped cell had a perfectly stable heuristic; only its `g` fell. The
gradient improved the *answer*; the re-expansion is a separate, known tax of weighted
search. We wrote that down and moved on rather than pretend the gradient did something it
didn't.

## Pricing the climb like the bot actually climbs

The field's "pillar up a block" cost started as a hardcoded stand-in. But whether the bot
should climb or walk around depends on *its* economy — how reluctant it is to place
blocks, how expensive the blocks it carries are to mine back out. So the field grew a
`RegionPlaceModel`, the place-side sibling of the tool-aware dig cost the coarse tier
[already uses](../worldmodel.md#region-level): per-block pillar cost derived once per plan
from the bot's real placement settings, in the same currency the block tier prices a
pillar. A build-reluctant bot, or one bridging with obsidian instead of dirt, now sees
climbing priced dear in the *heuristic*, so the search leans toward the walk-around before
it ever expands the pillar. For a default bot the number barely moves — it's an accuracy
lever for tuned bots, not a headline — but it means the heuristic and the real cost model
agree about what building costs, which is the kind of consistency that keeps a weighted
search honest.

## The goal that belongs to no region

Then we pointed the whole thing at a buried ore, and it broke in an instructive way.

A region's nodes are its *fragments* — the [connected air pockets](../worldmodel.md#region-level)
a bot can stand in. A block of buried ore is solid. It belongs to **no fragment**. So
"which region-node encloses the goal?" has no honest answer, and the old code fell back to
"the pocket whose centre is nearest" — which picked a pocket on the *wrong side* of the
rock. The reverse field then rooted itself at the wrong pocket, and the block search
faithfully walked all the way around to reach it, when the goal was two blocks through the
wall right in front of it. In the region trace, that direct two-block dig was priced
**1736** — a forty-nine-block tunnel, because the cost was measured centre-of-pocket to
centre-of-pocket — against the ~141 the real two blocks should cost.

The buried goal isn't *in* a fragment; it's a cell you reach by **digging in from one of
the pockets around it**. So the right question isn't "which pocket owns it" but "which
pockets can dig to it, and how far." We answer it with a small flood of its own: BFS
outward from the goal cell through breakable rock, and every time the front breaks into a
pocket, record that pocket and the number of blocks dug to reach it. Then the reverse
field is **multi-source seeded** — every reachable pocket starts the Dijkstra at its own
dig cost. Multi-source shortest-path needs no arbitrary "pick the goal pocket" choice at
all: every entry competes, and the flood works out which one is cheapest to reach *from
the bot's side* as a natural consequence of computing the field. The equidistant-pockets
question that would have forced a coin-flip simply dissolves.

## The map has to route there too

Seeding the field fixed the *heuristic*. The bot still walked around.

That took a second look at the traces to understand, and it's the sharp lesson of the
whole arc: **the heuristic guides the block search, but the region skeleton chooses the
route.** The block search only ever heads for the window target the skeleton hands it, and
the skeleton was *still* picking the goal's fragment by nearest-centroid and pricing the
direct dig at that same inflated 1736 — so it routed the loop, and the block search
dutifully followed. We'd taught the fine search to read the map, but the map still had the
wrong road drawn on it.

The dig-flood already knew the right roads; the skeleton just wasn't consulting it. The
cure is a **virtual goal node**. We reserve one fragment id (real fragments cap at 62, so
63 is free), stand up a synthetic node *V* at the goal region, and give every dig-reachable
pocket a virtual edge into *V* priced at its dig cost. The forward region A\* now terminates
at *V* having routed through whichever pocket minimises *walk-to-pocket + dig-cost* —
cheapest to reach, not merely closest — which is the same multi-source idea as the field,
encoded as a single goal node so the search's goal *test* never changes. *V* is a
search-only fiction: it never enters a stored region record, and the handful of consumers
that read a skeleton step's fragment learn to skip the sentinel. The bot walks to the near
pocket and digs the last blocks; the loop is gone.

## The bug that wasn't: it was never turned on

The most humbling discovery came late. We'd built the field, the gradient, the place model,
the dig-flood — measured them all in the `/bot trace` diagnostic — and the live bot's
behaviour *hadn't changed at all*. Following it, it took the same path as before every
time.

The heuristic was **trace-only**. The field was handed to the search through a static hook
that only the trace command set; the real sync and background searches ran with it null,
which is byte-identical to no region heuristic whatsoever. Every improvement in this
chapter had been landing in a diagnostic and nowhere else.

Wiring it live meant carrying the field as data instead of a static: the search gained a
field parameter (null stays byte-identical), the [background search](background_pathfinding.md)'s
request record carries it — built on the tick thread where it can read the world, then
read-only on the worker, which is safe because each field is written once and never touched
again — and the two-tier driver builds one per **window target**, rooted at the cell that
search actually aims for and cached until the target moves. That root matters more than it
sounds: the first live wiring built the field once per plan, rooted at the *final* goal, and
handed that same field to every window search — so a window search deep in a cave was
gradient-guided toward a goal hundreds of blocks past its own target, read a start heuristic
sixty times its honest octile, and flooded tens of thousands of nodes chasing the wrong
attractor. A gradient is only as good as its root; each search gets one rooted at its own
goal. It's a small change that only exists because we'd been grading our homework against
an answer key the student never saw.

## What honest costs buy you

The payoff isn't a number; it's that the bot now *weighs* digging against walking the way
you'd want it to, and the weighing is legible.

Point it at a goal a few blocks through a wall and watch the region trace: the virtual
goal engages, a pocket beside the goal offers to dig straight in, and the search either
takes it or doesn't — based on arithmetic you can read. In one session that "doesn't"
looked like a bug: the bot walked around a wall it "obviously" should have dug through.
The trace told the truth. The goal was three blocks deep, not two; the bot was
**bare-handed** — and mining stone by hand is punishingly slow (a full eight seconds a
block), so three blocks of hand-digging came to a hair *more* than walking around and
digging two — a genuine near-tie the tier broke toward walking, correctly. Hand the bot a pickaxe and digging got cheap
enough that it tunnelled straight down through everything; add a `mining.breakBaseCost`
reluctance and it settled into exactly the behaviour we wanted — avoid the long detour,
but don't tunnel ten blocks when three will do.

That's the lesson worth keeping. The coarse tier isn't just a way to path far; it's a map
the fine search can read, and once the fine search can read it, the interesting knob stops
being the algorithm and becomes the **cost model** — because a bot that prices dig-versus-
walk honestly will, given the right numbers, do the sensible thing on its own. The numbers
are the bot's judgement, and now both tiers agree on them.
