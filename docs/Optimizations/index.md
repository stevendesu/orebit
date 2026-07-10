A large amount of time, thought, and effort has gone into making Orebit as fast
as possible at runtime with the smallest possible footprint in RAM. These pages
document some of the design decisions and explain the algorithms, data
structures, and techniques used to achieve these goals.

They read best in order — each chapter builds on the ones before it:

1. [Reading Blocks](01_block_reading.md) — getting a single block read from
   milliseconds down to nanoseconds.
2. [Reusing Memory](02_reusing_memory.md) — why allocation is the quiet enemy, and
   the pooling patterns used against it.
3. [Block Fingerprints](03_block_fingerprints.md) — precomputing every answer the
   pathfinder will ever ask about a block into one packed 64-bit value.
4. [A Custom Hash Map](04_custom_hash_map.md) — the ~40-line open-addressed map that
   underpins the allocation-free search.
5. [The Pathfinding Hot Path](05_pathfinding_hot_path.md) — making each search node
   ~10× cheaper and, more importantly, predictable.
6. [Fewer Nodes](06_fewer_nodes.md) — teaching the search to visit less: greed,
   tie-breaks, and honest distance.
7. [Cuboid Macro-Movements](07_cuboid_macro_movements.md) — collapsing uniform runs
   into single big jumps, and the two bugs that froze the game.
8. [Measure Everything](08_measure_everything.md) — the benchmark protocol, and six
   convincing optimization ideas, only one of which survived the benchmark.
9. [Depth Nibbles](09_depth_nibbles.md) — profiling first: the search's time went into
   vertical column scans (falls probing down for a landing, cuboids probing up for a box),
   so two nibbles cache those facts — turning an O(n³) box scan into O(n²) and erasing
   three-quarters of the extraction bill.
10. [Off the Tick Thread](10_background_pathfinding.md) — the pivot from the search's
    algorithm to its surroundings: warming the cold JVM before the first player's tick,
    then moving the search onto background workers so a big flood no longer has to fit
    between two frames — with the epoch trick that makes recycled memory safe to read
    concurrently.
11. [Teaching the Block Search the Map](11_region_heuristic.md) — a primer on HPA\*
    (plan a coarse skeleton, solve short hops), then the real problem: the block search
    still floods "swamps" that are cheap to wade into, so feed the region tier's
    cost-to-goal into the block heuristic — plus the virtual goal node that lets a bot
    dig straight to a buried target.
12. [Paying for the Map](12_field_build.md) — the bill for that heuristic: the profiler
    finds 90% of a full search in the map build, a question answered once per region
    instead of once per cell, and a flood that learns to stop when it knows the way.
