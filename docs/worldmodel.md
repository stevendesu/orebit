# World Model

The world model is how Orebit perceives Minecraft efficiently enough to plan over
millions of blocks without slowing the game. It has two resolutions: a **recomputed
fine layer** (per-block nav data, near the bot) and a **coarse region layer** (a
fixed-grid cost pyramid spanning the explored world).

## Block-Level

To rapidly evaluate what movements are possible from a block, we precompute a
**NavBlock** — a behavioral fingerprint of a block state, packed into a single
64-bit `long`.

> **Note:** an earlier design stored a fixed 8-bit bitfield per block (stand
> height / headroom / slow / hardness). That has been **superseded**: the property
> set pathfinding actually needs (height, shape, climbable, fluid, gravity,
> damaging, slowing, replaceable, hardness, best tool, tool-required, openable,
> portal) is far larger than 8–16 bits, so we pack it into a 64-bit descriptor and
> store an **index into a descriptor table** per cell instead of inlining the data.

Key decisions:

- **Identity is per-`BlockState`, not per-`Block`.** Orientation changes navigation:
  north-facing stairs can be ascended from the south but not the north; growth stages
  change whether you can walk through or stand on a block. Keying on the block alone
  loses this.
- **The packed `long` is simultaneously the data and the dedup key.** Two block states
  that pack to the same bits behave identically to the pathfinder, so they *are* the
  same **navtype**. That behavioral dedup collapses Minecraft's ~28,000 block states
  into a few hundred distinct fingerprints (measured: **~590**), so the index fits
  comfortably in a `short` and the whole descriptor table is a few kilobytes — small
  enough to live in L1 cache. Every question the planner asks ("is this damaging?",
  "how slow is walking through it?", "is it climbable?", "is it a portal?", "has the
  server owner [protected it](world_edits.md#protected-blocks)?") is one
  array read and a shift-and-mask. The full story of the fingerprint — including the
  speculative field that cost half the table — is in
  [Block Fingerprints](Optimizations/block_fingerprints.md).
- **The grid entry is one `short` per cell**, holding *both* resolutions the planner
  reads: the low **10 bits** are the navtype index (1024 possible — ~1.7× headroom
  over the measured count), and the high **6 bits** are precomputed
  *neighbour-property flags* — walkable headroom above the floor, "editing here could
  release a fluid or drop gravel on you", "there's a walk-through hazard in the body
  space", "there's a solid face to place a block against". These are the multi-cell
  facts the movement code would otherwise re-derive on every search expansion;
  computing them once at build time turns them into a single masked array access.
- **A parallel depth byte per cell** rides beside the `short` grid — two nibbles
  answering the two *vertical* questions the search asks most: how far down is the
  first standable floor (consumed by falling moves — one read instead of a downward
  scan), and how many identical cells sit directly above (consumed by the
  macro-movement layer's uniform-cuboid proofs). Keeping it a *separate* array —
  rather than widening the hot `short` — is deliberate: the millions-of-reads path
  keeps its cache density, and only depth's few consumers touch the second array.
  The measurements are in [Depth Nibbles](Optimizations/depth_nibbles.md).

This per-block nav data is **recomputed when a chunk loads** (deterministic, sub-ms
per chunk) rather than saved to disk, and patched in place when a block changes
(the depth nibbles repair with a bounded fixpoint walk — at most 15 cells up or down
one column). Uniform sections (≈60% are all-air) are classified once and filled, not
scanned. Sections are built by chunk column, and each section's build **overscans
three rows into the section above**, so the precomputed neighbour flags are exact
across vertical section seams — a hazard sitting just across the boundary is seen,
not defaulted to air.

## Region-Level

Above the block layer, the world is divided into a **fixed cubic grid** of
16×16×16-block regions (an implicit octree — parents are simply the 2×2×2 group of
their children),
not semantic flood-filled regions. A fixed grid makes block→region assignment trivial
(coordinate math), makes block place/break updates O(1), and gives a clean
merge-of-children aggregation up the pyramid.

The core design commitment: **store cost, not connectivity.** In Minecraft every
block is *technically* traversable — you can always mine through — so the region
lattice is never "disconnected"; the only honest question is *how expensive* a
crossing is. Storing costs also sidesteps the classic hierarchical-pathfinding
maintenance trap: precomputed *entrances* (border-crossing transition nodes) can be
created, split, or merged by any single block edit, while a cost is just a scalar
recomputed from the region when the region changes.

What each region stores:

- **Fragments** — the region's occupiable space, as its 6-connected components
  (usually one; a handful in cave systems), each recording which of the region's
  faces it reaches. Two cells in the same fragment are cheaply walkable; different
  fragments mean an expensive dig between them. Regions with no occupiable space at
  all collapse to one of three uniform kinds — **solid** (mine through, cost from
  average hardness), **air** (a one-way chute: falling in is cheap, pillaring out
  is dear), or **water** (a symmetric swim).
- **Costs derived, not stored — and tool-aware.** A region stores only geometry
  (fragment footprints) and a 4-bit average-hardness nibble; the actual crossing
  costs are computed from those on demand, so a single block edit never invalidates a
  stored cost table. Crucially, the dig cost is priced in the **same currency the bot
  uses**: the hardness nibble is turned into real mining ticks against the bot's
  *actual* tools at plan time, and the model is deliberately two-term — you pay to
  walk the tunnel *and* to break the two-block-tall body in the way — so **digging is
  never cheaper than walking the same distance** for any tool. (An earlier flat,
  tool-blind dig cost priced soft dirt below a walk, which had the bot happily tunnel
  straight down through a hillside instead of strolling to the cave mouth twenty
  blocks over. The [region-heuristic chapter](Optimizations/region_heuristic.md)
  follows where honest dig-versus-walk costs lead.) The recomputed block layer still
  supplies final exactness on approach.
- **A pyramid of coarser levels** — parent regions merge their children's fragments,
  so the same machinery plans at 16, 32, 64… blocks per cell, and a route across
  tens of thousands of blocks is a short search at a high level.

Regions whose chunks aren't loaded yet default to **optimistically cheap** — the
planner assumes unexplored terrain is crossable and lets the fine layer correct it
on approach. (The alternative — assuming the worst — could refuse a route that
actually exists.)

Two more pieces round out the layer:

- **Portals as local facts.** A Nether portal is indexed in the chunk that contains
  it, not in a global portal table (technical players build tens of thousands). The
  bot uses the index to follow its owner across dimensions: find the nearest known
  portal, path to it, walk in.
- **Resource octree** *(planned)* — per region, a sparse count histogram over tracked
  resource classes (ores, logs, chests, crops), rolled up the same pyramid, so "find
  the nearest diamonds" ascends until a super-region contains some, then descends
  into the densest children. This is the piece that will back commands like *mine
  diamonds*; it isn't built yet.

The fine nav grid costs no disk at all (recomputed). The coarse layer is designed to
persist within a **~6–8% per dimension** budget of save-file size; today it is
rebuilt lazily as chunks load, and persistence lands with the resource layer.
