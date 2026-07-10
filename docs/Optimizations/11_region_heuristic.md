# Teaching the Block Search the Map

Everything up to here has been about A\*: making each node cheap, visiting fewer of
them, jumping whole runs at once, moving the search off the tick. Put together, they took
the block search about as far as a block search can go. But "as far as A\* can go" still
isn't far enough. Point the bot at a goal a few hundred blocks off, or one tucked behind
the wrong kind of terrain, and even a fast, lean, greedy A\* fans out into tens of
thousands of positions and quits. To go further we need a different *kind* of help — not a
faster search, but a **map**.

## The standard fix: plan the route before you walk it

The way out is older than this project and isn't ours: **hierarchical pathfinding**, HPA\*
in the literature. Solve the problem twice, at two zoom levels.

First, zoom out. Carve the world into a coarse grid of **regions** — Orebit's are 16-block
cubes — and build a small graph whose nodes are regions and whose edges say "you can get
from this region to that neighbour, and roughly what it costs." A search over *that* graph
is tiny: a route across five hundred blocks is a few dozen region-hops, not thousands of
block-steps. It can't place a single footstep, but it knows the shape of the answer — "up
through this pocket, across three regions, down into that cave." Call that rough route the
**skeleton**.

Then zoom back in. The block A\* never solves the whole journey at once; it only ever
solves the next little hop — from where the bot stands to a **window target** a few
regions along the skeleton. Reach it, slide the window forward, search again. A single
five-thousand-node search that would flood and fail becomes something like a *hundred*
short fifty-node searches, each one easy, because each is aimed at a target that is close
and — thanks to the skeleton — guaranteed to lie in the right direction.

None of that is novel; it's the textbook hierarchical trick, and Orebit's
[region tier](../worldmodel.md#region-level) and [two-tier driver](../pathfinding.md) are
an ordinary implementation of it. What *this* chapter is about is the part the textbook
doesn't hand you — the cases where, even with a skeleton in place, the block search still
drowned.

## Where it still drowned: the swamps

The skeleton fixed the problem of *scale*. It did not fix a subtler one, and two cases
made it vivid.

**A pillar with a splinter in it.** Send the bot to a goal hanging in open air and it
should just pillar straight up — and [the macro layer](07_cuboid_macro_movements.md) makes
it do exactly that, *as long as the air column is uniform*, because a clean cuboid lets
the forced-cost heuristic see the whole expensive climb at once. Drop a single floating
oak log into that column, though, and the cuboid can't span it. The forced-cost proof
shatters into scraps too short to matter, the heuristic goes back to reading "goal is
straight up, that's cheap," and the cone of half-built pillars floods right back — undone
by one stray block.

**A cave it won't go down.** Point the bot at a goal at the bottom of a cave whose mouth
is off to one side. Octile points it *straight* at the goal — through the ceiling — so it
fans out across the whole open sky above the cave, which is vast and costs almost nothing
to wade through, hunting for a way down that isn't there. It will happily explore an
entire above-ground region before it tries the entrance twenty blocks over.

Both are the same disease. The skeleton hands the block search a window target — a
short-term goal, guaranteed to lie in the right direction — but says nothing about the
most efficient *way* to reach it. So inside the window the search is back to
topology-blind octile, and it pours itself into whatever is cheapest to flood: the open
sky, the wrong side of a wall, the flat plain above a cave. Those are **swamps** — big
volumes that cost nothing to wade into and get you no closer to the goal. On the flood
scenario that named this arc, the block search burned past **forty thousand candidate
positions** filling a swamp it could have skipped by reading a single number.

Because we already *have* that number. The [region tier](../worldmodel.md#region-level)
that drew the skeleton can price any region's true cost-to-goal — it knows the sky loops
the long way around and the cave mouth is the short way in. The fine search just never
asks. The skeleton bounds *where* it searches; inside that window the heuristic stays
blind. So the fix isn't more heuristic cleverness — it's to let the fine search **read the
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
[next chapter](12_field_build.md).)

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

One honest note, because [Measure Everything](08_measure_everything.md) is the house
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
