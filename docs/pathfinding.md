# Pathfinding

Orebit navigates with a **two-tier hierarchical A\***: a coarse **region** planner
routes across the [region grid](./worldmodel.md#region-level) — out to goals
thousands of blocks away — and a fine **block** planner computes the exact movements
for the stretch the bot is currently crossing. This page documents how the tiers
cooperate, the movement vocabulary, the cost model, and how a plan actually gets
executed.

## Region-level

The region planner runs A\* over the fixed-grid cost pyramid, and it is **stateful**:
a goal gets a *stack* of plans, one per pyramid level, each level navigating within a
window handed down from the level above. The top level routes coarsely toward the
goal; each finer level plans from the bot toward a sub-goal a few cells along its
parent's route; the bottom level's output — an ordered list of regions, the *region
skeleton* — is what the block planner follows.

The point of the stack is **re-planning only at the scale that changed**. As the bot
walks, each level checks a cheap question — "am I still inside my window?" — and only
the level whose window the bot exited re-plans (the leaf level often, the coarse
levels rarely). A medium-sized obstacle is re-routed at the level whose cells match
its size, and the thousand-block macro route isn't re-derived twenty times a minute
because the bot stepped around a tree. The top level *slides* toward far goals and
*collapses* as the bot nears them, which is what lets one mechanism cover everything
from "come here" to a cross-continent trek.

Four robustness rules matter in practice:

- **Commit to the skeleton.** Once the bot is executing a region route, near-equal
  alternatives are not allowed to flip-flop it mid-route; the plan re-routes when
  reality disagrees, not when a fresh search would score 2% cheaper.
- **Repair by blacklist — and remember it.** If a promised region crossing turns out
  unrealizable at the block level (the coarse tier is optimistic about unexplored
  terrain — see the world-model page), that specific crossing is blacklisted and the
  level re-plans around it, escalating to coarser levels only if the local repair
  fails. These learned dead ends aren't scratch notes for one search: they're kept for
  the capability profile that proved them (a crossing impossible for a no-dig bot may
  be fine for one that mines) and **saved with the [region map](persistence.md)**, so
  a restarted server doesn't march the bot back into a dead end it already paid to
  discover.
- **A dead approach isn't a dead destination.** When the planner rules a way in
  unrealizable, it remembers *which direction the bot was coming from* — so ruling out
  one approach to a place leaves every other approach to it open. This matters most
  when the goal shares a region with the obstacle guarding it: a goal on top of a cliff
  the bot is standing beneath is, to the coarse planner, "right here", so the direct
  climb straight up looks trivial and the fine search floods against the cliff face.
  Because approaches are tracked separately, the planner blames only *that* approach —
  the straight-up one — and reroutes the long way around to the staircase, instead of
  concluding the whole destination is unreachable and giving up.
- **Buried goals get dug to.** A goal with no open space around it at all — an ore
  seam, a sealed room — is still a real goal: the region planner enumerates the
  reachable pockets *around* it, prices the dig from each (for a bot allowed to
  mine), and routes to the cheapest approach. `/bot goto` at an underground
  coordinate tunnels there; it doesn't refuse because no corridor happens to exist
  yet.

## Block-level

Within the current window of the skeleton, A\* runs over the recomputed nav grid,
expanding **movements** (below). This search is the hot path, and it's been through
several rounds of measured optimization — the
[hot-path](Optimizations/05_pathfinding_hot_path.md),
[fewer-nodes](Optimizations/06_fewer_nodes.md),
[macro-movement](Optimizations/07_cuboid_macro_movements.md), and
[depth-nibble](Optimizations/09_depth_nibbles.md) chapters tell that story in full.
(Even the *first* search after boot is covered: the server warms the pathfinder up on
synthetic terrain at startup — `pathing.warmup` — so no player pays the JIT compiler's
one-time ~22 ms bill.) By default it runs **[off the server tick entirely](Optimizations/10_background_pathfinding.md)**,
on a pool of background worker threads with a wall-clock time budget instead of a node
cap — so a big flood no longer has to fit between two frames (`pathing.async`, on by
default; set it `false` for the old synchronous tick-thread search). The
load-bearing pieces:

- **Weighted search, not provably-optimal search.** The heuristic is 3D octile
  distance scaled by a configurable greed weight (default `2.0`), plus a microscopic
  straight-line tie-break so the search threads obstacle fields instead of drowning
  in equal-cost alternatives. A bot needs a good path *now*, not a certificate of
  optimality.
- **A heuristic that can read the map.** Octile distance is topology-blind — it
  points straight at a goal behind a wall and floods the open volume hunting a way
  through. So the block heuristic also consults the region tier: a goal-rooted flood
  over the coarse graph yields a **cost-to-goal estimate per region**, and the search
  takes the *larger* of it and the straight-line distance. A cell whose region loops
  back to the goal now reads high and is deprioritised instead of flooded — and a
  *buried* goal (an ore in no air pocket at all) is reached by seeding that flood from
  the pockets that can dig to it. The flood itself stops as soon as it has priced the
  neighbourhood of the route it found, and any region it never settled reads a provable
  lower bound anchored on the goal rather than a blank — so the guidance never simply
  runs out at the edge of the map, even when the search is forced into a wide detour.
  The [region-heuristic chapter](Optimizations/11_region_heuristic.md) tells the whole
  story; [what building that field costs](Optimizations/12_field_build.md) got its own
  chapter.
- **A goal premium that respects building.** Distance heuristics are blind to the
  fact that a goal floating over open air must be *built to* — so the search adds the
  provable placement cost of the forced climb below the goal into the estimate,
  taking the cheapest of the goal's approachable faces (faces on the far side of the
  approach are excluded, since reaching them means paying to pass the goal first —
  with the vertical build face always kept, because a floating goal forces the climb
  no matter where you come from).
- **Macro moves.** Long uniform runs (a pillar up open air, a fall down a shaft, a
  straight dig) are collapsed into single multi-block jumps, validated by a maximal
  uniform cuboid rather than a blind 1-D scan.
- **A mutable-world overlay.** Moves that break or place blocks change the world the
  *rest of the path* must be planned against. Each candidate path carries a compact
  diff of its planned edits, and every geometry read consults the diff before the
  grid — so a later move correctly sees the block an earlier move plans to place.
- **Honest partial paths.** When the search exhausts its budget, it returns its best
  progress instead of failing — but only if that progress *genuinely gets closer to
  the goal*, measured geometrically, so a cheap-but-sideways wander doesn't count —
  and truncated to the last *reversible* point, so a bot that can't place blocks is
  never marched off a ledge it can't climb back from on the strength of an unfinished
  plan. The bot walks a partial to its end and replans from there, converging on far
  goals in strides; a search whose best effort got no closer reports failure instead
  of walking it.

### Movements

Each movement is a small strategy object that knows its geometry, computes its cost
(in game ticks) for the search, and knows how to steer the bot through itself at
execution time. The current vocabulary — see [Movements](movements.md) for the full
per-movement reference, with every cost constant and its derivation:

| Movement        | Meaning                                                                    |
|:----------------|:---------------------------------------------------------------------------|
| Traverse        | Walk one block horizontally (step-assist onto slabs/stairs; may break or place to pass) |
| Diagonal        | Move diagonally across a corner (with corner-clearance checks)             |
| Ascend          | Step up one block (jump, possibly placing a step)                          |
| Descend         | Step down one block                                                        |
| Fall            | Drop multiple blocks off an edge (damage priced as cost, capped at lethal) |
| WalkOff         | Walk a one-block gap and land one down — the no-jump gap cross for spots where a jump is refused |
| Pillar          | Jump and place beneath yourself — straight up in the same column           |
| MineDown        | Dig the block underfoot and drop one — straight down in the same column    |
| Climb           | Ascend or descend a ladder, vine, or scaffolding                           |
| Parkour         | Sprint-jump a gap to a flat (1–3), rising (1–2), or falling (1–4) landing — plus offset landings one cell off the line |
| DiagonalParkour | The same running jump along a diagonal (gaps 1–2)                          |
| Swim            | Upright paddling through fluid — four lateral steps plus a straight rise and a straight sink (slow) |
| Sprint-swim     | Fast prone swimming — lateral travel only, and water only                  |
| Diagonal sprint-swim | The same stroke along 2- and 3-axis diagonals — true 3D corner-cutting underwater |
| Start / end sprint-swim | The in-place transitions into and out of the prone swimming pose  |
| Surface         | Crawl out of a submerged tunnel onto a bank and stand up as you emerge     |
| Ride bubble column | Step into an upward bubble column and let it carry you — the free elevator |

The pose-transition rows are worth a note: sprint-swimming is *stateful* (you must be in
deep water to start it, but can continue through shallows), so the search's node identity
includes the bot's pose — going prone is a real search edge, not a bookkeeping trick.
The division of labour between the two swim families follows one rule: **fluid is a
medium, not a pose.** The prone sprint-swim is fast *lateral* travel and nothing else;
everything a bot does in fluid upright — entering it from dry land, rising, sinking —
belongs to the plain Swim rungs. That is why the vertical axis and the pose transitions
both stand alone: a bot rises up a one-block waterfall upright, and stands up again
underwater rather than having to reach the surface first.

Lava rides the *same* vocabulary — there is no lava-only movement. The upright rungs work
in either fluid; only the prone sprint-swim is water-only, because vanilla will not let a
player enter the swimming pose in lava at all. What separates the two fluids is price, not
geometry: a lava cell is charged the same swim rung scaled by lava's much slower
locomotion, plus the damage it deals in the shared health currency — so a mortal bot
routes the long way around a lava lake, and a bot that can't be hurt simply swims it.

**Planned, extensible** (added later through the same interface): Crawl (1-tall gaps),
wall-clutch, boat/minecart. Profiling shows the movement-geometry logic is only ~4% of
a node expansion, so the vocabulary can keep growing without the search getting fatter.

Portals are handled above this vocabulary: known Nether portals are indexed per
chunk, and when its owner changes dimension the bot paths to the nearest known portal
and walks in.

### Folded interactions

Breaking blocks, placing blocks, and toggling doors / fence gates / trapdoors are
**not separate moves** — they don't change your position. Instead they are folded
into the movement that needs them, as edits that add cost and validity requirements:

- *Traverse, breaking the block in the way* — costs the real mining time (block
  hardness against the best tool in the bot's inventory), plus a configurable flat
  surcharge per break (`mining.breakBaseCost` — a "reluctance to edit the world").
- *Ascend / Pillar, placing a block beneath* — requires a block to place (from the
  bot's real inventory, if the server configures it that way).
- *Walking through a hazard, or punching it out first* — where a movement's body would
  pass through a berry bush, cobweb, or fire cell, the planner prices both options at
  the same node — the pass-through surcharge versus the real break time — and folds
  whichever is cheaper. A sword-carrying bot cuts the web; a bare-handed one wades.
- Parkour and Fall, by contrast, are **edit-free by rule**: you can't mine or place
  mid-jump, so a gap that would need it simply isn't a parkour candidate.

The edits themselves execute like a player's — real mining time with the crack
overlay, real drops and tool wear, and hard refusals for owner-protected blocks; see
[Breaking & Placing](world_edits.md).

### Cost model

Costs are in **game ticks** and parameterized on the bot's configured capabilities
and inventory. Every movement is priced from vanilla's own rates: a flat walk is the
ruler at ~4.6 ticks per block; upright swimming is appreciably slower than that, and
its rise/sink rungs are derived from vanilla's in-fluid physics rather than any
published speed; prone sprint-swimming is the one move *cheaper* than walking, since it
travels at a land sprint's speed — so a bot will happily use a river as a highway.
Mining cost is the real vanilla mining time for the block and
tool; hazards are *costs, not walls* — a mortal bot pays a stiff surcharge per
fire or berry-bush cell it would pass through (an invulnerable one doesn't), cobwebs
charge everyone the slowdown physics imposes, and a fall past the safe distance is
priced by its damage rather than forbidden, up to the lethal cap. All damage shares
one exchange rate — `pathing.costPerHitpoint`, ticks per hitpoint — so "how much is
the bot's health worth" is a single number the server owner sets. Because every block
is *technically* mineable, the engine reasons in cost, never hard "blocked/clear" —
it just prefers walking down the hall to tunnelling through the wall. (The exceptions
are deliberate: owner-[protected blocks](world_edits.md#protected-blocks) are never
broken at any price, and vanilla-unbreakable blocks are walls unless the server opts
the bot into a two-minute-per-block grind.)

Notably, the **cost of placing a block is behavioral, not physical**: placing is
near-instant in-game, but a bot that scaffolds at every excuse is obnoxious, so
placement carries a configurable reluctance (plus a premium scaled by how hard the
placed block would be to mine back out — a bot carrying dirt and obsidian bridges
with the dirt). Server owners can tune all of this; see
[Configuring Orebit](configuration.md).

## Execution

A plan is worthless if the bot can't walk it, and Minecraft movement is too messy for
"teleport along the waypoints." The bot is a real server-side player entity running
the full vanilla player tick, and execution is deliberately **reactive**: every tick,
each subsystem looks at what is *currently true* and drives toward what the plan
wants, rather than trusting a remembered checklist.

- **Multi-step movements are phase plans.** A pillar is "jump, then place under
  yourself, then land"; a parkour jump is "run, take off, land." Movements that need
  this declare an ordered list of phases, each with the geometry it must establish
  (a cell that must be air, a cell that must be footing) and the inputs to hold. The
  runner re-checks every requirement against the live world each tick — if a block
  the plan believed broken is still there, it mines it again; if a pillar's footing
  never took and the bot fell back down, the phase cursor resets and it re-attempts.
- **Mining takes real time.** The bot digs like a player: equips its fastest tool,
  faces the block, shows the crack overlay, and breaks the block on the exact tick
  vanilla mining would — with proper drops, XP, and tool wear. A break continues
  only while the mover keeps requesting it, exactly like holding the mouse button.
- **Re-planning is boundary-gated.** The bot re-plans when it commits past a window
  boundary or when the world genuinely invalidates the plan — not on a timer, and
  not because a transient mid-jump position looked wrong.
