A large amount of time, thought, and effort has gone into making Orebit as fast
as possible at runtime with the smallest possible footprint in RAM. These pages
document some of the design decisions and explain the algorithms, data
structures, and techniques used to achieve these goals.

They read best in order — each chapter builds on the ones before it:

1. [Reading Blocks](block_reading.md) — getting a single block read from
   milliseconds down to nanoseconds.
2. [Reusing Memory](reusing_memory.md) — why allocation is the quiet enemy, and
   the pooling patterns used against it.
3. [Block Fingerprints](block_fingerprints.md) — precomputing every answer the
   pathfinder will ever ask about a block into one packed 64-bit value.
4. [A Custom Hash Map](custom_hash_map.md) — the ~40-line open-addressed map that
   underpins the allocation-free search.
5. [The Pathfinding Hot Path](pathfinding_hot_path.md) — making each search node
   ~10× cheaper and, more importantly, predictable.
6. [Fewer Nodes](fewer_nodes.md) — teaching the search to visit less: greed,
   tie-breaks, and honest distance.
7. [Cuboid Macro-Movements](cuboid_macro_movements.md) — collapsing uniform runs
   into single big jumps, and the two bugs that froze the game.
8. [Measure Everything](measure_everything.md) — the benchmark protocol, and five
   convincing optimization ideas of which four made things slower.
9. [Depth Nibbles](depth_nibbles.md) — the next campaign under that protocol: a
   perfect-sounding gate killed by p = 0.000, two nibbles that erased a third of
   cuboid-heavy search time, and a 32× fix for the first-search stall.
10. [Off the Tick Thread](background_pathfinding.md) — moving the search onto
    background workers so a big flood no longer has to fit between two frames, and
    the epoch trick that makes recycled memory safe to read concurrently.
11. [Teaching the Block Search the Map](region_heuristic.md) — the payoff Fewer
    Nodes promised: feeding the coarse region tier's cost-to-goal down into the
    block heuristic so the fine search stops flooding walls, and the virtual goal
    node that lets a bot dig straight to a buried target.
