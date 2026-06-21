# Pathfinding

Orebit navigates with a **two-tier, lazy, hierarchical A\***: a coarse **region**
planner gets the bot across thousands of blocks using the persisted HPA\* graph, and a
fine **block** planner computes the exact movements within the region the bot is
currently crossing. This page documents the movement vocabulary and cost model.

## Region-level

The region planner runs A\* over the [region graph](./worldmodel.md#region-level). It
finds the lowest-common-ancestor super-region containing both the start and the goal,
plans across sibling regions one hierarchy layer at a time, and refines each
sub-region's plan **lazily** as the bot enters it — so we never compute the fine path
for distant terrain we may never reach. The output is a *region skeleton* (an ordered
list of regions to pass through); the block planner fills in the moves for each.

## Block-level

Within a single region, A\* runs over the recomputed nav grid, expanding **movements**
(below). The block-level heuristic is **admissible** (so local paths are optimal) —
the region tier already handles long-distance scale, so we don't need the inadmissible
"greedy" weighting that flat pathfinders like Baritone rely on.

### Movements

Orebit adopts Baritone's battle-tested movement set, adds portal travel, and exposes a
`Movement` interface so new movement types can be added later. Each movement is a small
state machine that computes its cost (in game ticks, inventory-aware) for the search
and then executes.

| Movement    | Meaning                                                                 |
|:------------|:------------------------------------------------------------------------|
| Traverse    | Walk/sprint one block horizontally (may break or place to pass)         |
| Diagonal    | Move diagonally across a corner (cheaper than two cardinal moves)        |
| Ascend      | Step up one block (jump, possibly placing a block beneath)              |
| Descend     | Step down one block (gravity handles the drop)                          |
| Pillar      | Go straight up (jump-place beneath self, or climb a ladder/vine)        |
| Downward    | Go straight down (mine the block below, or descend a ladder)            |
| Fall        | Fall multiple blocks (fall-cost table; water bucket can negate damage)  |
| Parkour     | Jump a 2–4 block horizontal gap over air                                |
| EnterPortal | Traverse a Nether/End/region portal to a linked section                 |

**Planned, extensible** (added later through the same interface): **Swim**, **Crawl**
(1-tall gaps via trapdoor/sneak), wall-clutch, boat/minecart. These are valid ways to
move that flat pathfinders typically ignore; modelling them lets bots move more
intelligently.

### Folded interactions

Breaking blocks, placing blocks, and **toggling doors / fence gates / trapdoors** are
**not separate moves** — they don't change your position. Instead they are folded into
the movement that needs them, as interactions that add cost and validity requirements:

- *Traverse, breaking the block in the way* — costs the mining time (a function of
  block hardness and the best tool in the bot's inventory).
- *Ascend / Pillar, placing a block beneath* — requires a throwaway block in inventory.
- *Traverse, opening the door* — a near-free interaction Baritone doesn't model.

### Cost model

Costs are in **game ticks** and depend on the bot's inventory snapshot (tool tier,
throwaway blocks, water bucket, food). Walking is cheapest; swimming and soul sand are
roughly 2×; mining cost scales inversely with tool strength; truly impractical actions
(mining bedrock, mining without a usable tool) take an effectively-infinite cost so the
planner routes around them. Because every block is *technically* mineable, the engine
reasons in **cost**, never hard "blocked/clear" — it just prefers walking down the hall
to tunnelling through the wall. A persisted plan is valid only for the inventory it was
planned with; changing tools or running out of blocks triggers a replan.
