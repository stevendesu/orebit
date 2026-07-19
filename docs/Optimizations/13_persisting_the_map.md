# Persisting the Map

The [region tier](11_region_heuristic.md) gives the bot a coarse map of the whole world it has
explored — cheap to plan across, expensive to *build*, because building it means loading chunks
and flooding their connectivity. So we save it. A restart, or a server that idles down and comes
back, shouldn't cost the bot everything it learned about the lay of the land.

The first version of that save was the obvious one: one file per dimension, gzip-compressed, holding
the finest level of the map; the coarser zoom levels rebuilt from it on load. It worked, and it had
two problems that only show up at scale. Every periodic save rewrote the *entire* dimension, however
little had changed. And rebuilding the coarse levels on load meant re-running the map's roll-up math
for everything at once. Both costs grow without bound as the explored world grows. This chapter is
four measured decisions that fixed that — and one tempting idea the measurement killed.

## Shard it like Minecraft does

The fix for "rewrite the whole dimension" is to stop having *a* file. Minecraft already shards its own
world data into `.mca` region files, each covering a 32×32-chunk square. Orebit's map divides into the
same square exactly — one of its coarse grid cells is precisely that footprint — so the map splits into
one **shard file per 32×32-chunk area**, `hpa.<X>.<Z>.bin`, named by region coordinate. Now a save
rewrites only the shards that actually changed, and an area no bot has visited has no file at all.
A small per-dimension `hpa.coarse.bin` holds the top-level summary — the whole explored world at its
coarsest zoom, tiny, enough to plan a rough long-distance route the instant the server starts.

## The format fork: recompute on load, or store the answer?

Sharding raised a question the single-blob format let us dodge. Each shard holds the finest level of the
map. The coarser levels inside it can either be **stored too**, or **recomputed** on load by re-running
the roll-up — the same merge-of-children math the [region tier](11_region_heuristic.md) uses. Storing
them costs disk; recomputing them costs load time. This is the kind of fork you measure before you commit,
because the on-disk format is the expensive thing to change later.

So we built a benchmark that loaded a real shard both ways and timed it. Per 32×32-chunk shard:

| terrain | recompute on load | store the answer | 
| --- | ---: | ---: |
| open surface | 43.0 ms | 37.1 ms |
| dense cave | **1355 ms** | **88.7 ms** |

Surface terrain barely cared — its coarse cells are uniform, so rolling them up is nearly free. But a
dense cave shard was a **1.35-second** stall to recompute, because the roll-up's connectivity merge is
super-linear in how many disconnected pockets each coarse cell unions, and cave cells are full of them.
Storing the coarse levels instead made that load a pure read — **15× faster** on the case that hurt —
for about **8–14% more file size**, all of it small uniform records that barely compress. On a map that
now streams shards in *as the bot approaches* (below), load time is a tick-budget cost, not a background
one. We store the answer.

## The pooling that didn't matter

Loading a cave shard allocates a lot — tens of megabytes of the map's per-region objects. The natural
next thought: pool them. Recycle the objects an eviction frees back into the loader, and the load stops
allocating. We'd spent [a whole chapter](02_reusing_memory.md) on exactly this reflex, so we measured it
before believing it.

The pool did what it promised to the allocation counter — **51 MB down to 5 MB** per surface load,
**57 down to 10** for a cave — and **nothing** to the wall clock. Zero. If anything a hair slower, from
the indirection of asking the pool for each object. Because young-generation allocation on the JVM is
genuinely *free*: a bump pointer into a thread-local buffer, and a collector whose cost scales with what
survives, not with what you threw away. The load's time was never in the allocation. It was in the
**decode** — inflating the bytes and unpacking the bit-fields. Pooling was a real optimization aimed at a
cost that wasn't there. Reverted, seams and all.

## Drop the gzip

If the cost is decode, look at decode. Splitting the load timer showed **62–71% of it was gzip inflate** —
the CAVE shard was 88 ms, and 54 of those were the decompressor. Which raised a heretical question: what
is the compression actually *buying*?

The benchmark had an answer, and it was a lie. It claimed gzip shrank the data 6–22×. But the benchmark
built its fixture by interning one identical column at every coordinate, and gzip's back-references
demolish that kind of repetition — an artifact of the synthetic shape, not the real terrain. So we measured
the ratio on **real shard files from a real explored world**: gzip bought **2.15×** on routing shards,
**2.79×** on resource shards. A little over 2×, for two-thirds of the load time.

That's a bad trade once the bot loads shards on a tick budget. Dropping gzip entirely made the raw CAVE
load **34 ms** — under a single 50 ms tick, which is what turned shard streaming from "might hitch" into
"always safe" — at the cost of roughly 2× the disk, which is a few percent of the vanilla region files
either way. We dropped it everywhere.

## Get the 2× back for free

Losing 2× on disk stung a little, and there was a cleaner way to get most of it back. gzip's whole win
here was crushing *repetition* — and once we looked, the repetition wasn't in the block data at all. The
per-block records were already tiny (an all-air cell packs to six bits). The bulk that compressed was the
**per-row coordinate headers** — position and length stamped in front of every record — which made up
**63% of the raw file**, most of it identical air columns stacked one atop the next.

So encode *that* structure directly instead of leaning on a general compressor to rediscover it. Group the
records by column and run-length the identical stacked cells: one **column-run** encoding, no Huffman, no
back-reference window. It recovered **97% of gzip's space** (a 2.08× ratio against raw) at essentially
**raw decode speed** — because decoding a run is just repeating a value, not inflating a stream. Best of
both: nearly gzip's size, none of gzip's time. A pure codec swap behind a version bump, low blast radius,
and it left the load fast.

## The lesson

Four decisions, and every one turned on a number that contradicted the intuition: store the coarse levels
(recompute looked cheap, was a 1.3-second stall); don't pool (allocation looked expensive, was free); drop
gzip (compression looked like a win, was two-thirds of the load for a 2× that a synthetic benchmark had
inflated to 22×); hand-encode the structure gzip was compressing (and get the 2× back at no time cost). The
on-disk format is the one thing here that's costly to change after the fact — which is exactly why not one
byte of it was chosen without measuring the alternative first.
