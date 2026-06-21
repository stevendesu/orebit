# World Model

The world model is how Orebit perceives Minecraft efficiently enough to plan over
millions of blocks without slowing the game. It has two resolutions: a **recomputed
fine layer** (per-block nav data, near the bot) and a **persisted coarse layer**
(regions + navigation graph, the whole explored world).

## Block-Level

To rapidly evaluate what movements are possible from a block, we precompute a
**NavBlock** — a behavioral fingerprint of a block state.

> **Note:** an earlier design stored a fixed 8-bit bitfield per block (stand
> height / headroom / slow / hardness). That has been **superseded**: the property
> set pathfinding actually needs (height, solid faces, climbable, fluid, gravity,
> damaging, slowing, replaceable, hardness, best tool, tool-required, directional,
> waterloggable, openable) is far larger than 8–16 bits, so we use an **index into a
> property table** instead of inlining the data.

Key decisions:

- **Identity is per-`BlockState`, not per-`Block`.** Orientation changes navigation:
  north-facing stairs can be ascended from the south but not the north; growth stages
  change whether you can walk through or stand on a block. Keying on the block alone
  loses this.
- **A NavBlock is a compact index into a table of full property objects.** Behavioral
  dedup collapses Minecraft's ~28,000 block states into a much smaller set of distinct
  fingerprints (most cube orientations are identical), so the index fits in a `short`.
  The most pathing-relevant structural bits (directional / waterlogged / orientation)
  may be encoded inline in the index for branch-free access.
- **Storage: palette + packed indices** (the same scheme Minecraft uses for blocks) —
  a small per-section palette of distinct NavBlocks plus packed indices on disk,
  **expanded to a flat array in memory** for O(1) lookup.

This per-block nav data is **recomputed when a chunk loads** (deterministic, sub-ms
per chunk) rather than saved to disk. Uniform sections (≈60% are all-air) are
classified once and filled, not scanned.

## Region-Level

Above the block layer, the world is divided into a **fixed cubic grid** (an octree),
not semantic flood-filled regions. A fixed grid makes block→region assignment trivial
(coordinate math), makes block place/break updates O(1), and gives clean parent =
merge-of-8-children aggregation. Two persisted structures live on this grid:

- **Resource octree** — per region, a sparse log₂-count histogram over ~64 tracked
  resource classes (ores, logs, beds, chests, crops, plus "hint" classes). Counts roll
  up the octree, so "find the nearest diamonds" ascends until a super-region contains
  them, then descends into the densest children, then scans the nav grid for the exact
  vein.
- **HPA\* graph** — per region, a **face-to-center traversal cost** (4-bit log scale)
  used for long-distance hierarchical pathfinding. Because every block in Minecraft is
  *technically* traversable (you can always mine through), this stores **cost, not
  connectivity**. Portals (Nether/End/region) are stored as **local edges** on the
  sections that contain them.

Together these add an estimated **~6–8% per dimension** to save-file size — within the
target. The fine nav grid is free (recomputed); only the coarse layer is persisted.
