Every chapter so far has ended with a bigger number: 6× here, 77% there, a
ten-thousand-node cone collapsed to forty. It would be easy to walk away thinking
optimization works like that — have idea, apply idea, collect speedup.

It doesn't. Those chapters are the survivors. This one is about what the work looks
like once the obvious wins are gone: you form a hypothesis that sounds *completely
convincing*, you build it, you measure it — and most of the time it's slower. The
skill stops being "know the tricks" and becomes "build instruments that tell you the
truth, and believe them over your own reasoning." Below are five ideas that all
sounded right. One survived.

## The mental model: two currencies

First, the frame that makes the failures make sense. A modern CPU lives in two
currencies:

- **Arithmetic is nearly free.** A core retires several simple operations per
  *nanosecond* — adds, shifts, masks, compares cost almost nothing, and the CPU
  happily runs several of them in parallel behind the scenes.
- **Memory is the tax.** Data already in the L1 cache arrives in about a nanosecond.
  Data that has to come from main memory takes on the order of a *hundred times*
  longer, and the core mostly just waits.

That ratio is why this project's data structures look the way they do: the
[fingerprint table](block_fingerprints.md) that fits in L1, the
[packed grids](../worldmodel.md) that trade a little RAM for flat array reads, the
[allocation-free search state](pathfinding_hot_path.md). Spend cheap arithmetic
(shift-and-mask) to avoid expensive memory (pointer chasing), and default to spending
RAM to save CPU — 30 MB is nothing on a Minecraft server; a stalled core is not.

But — and this is the part that bites — every optimization *buys* savings in one
currency by *spending* in another, and the exchange rate is nowhere near as obvious
as it looks:

- **Allocation vs. arithmetic.** Avoiding an allocation is usually worth quite a lot
  of arithmetic, because allocation's real price is paid later, by the garbage
  collector, at a moment you don't control.
- **Locality vs. arithmetic.** Rearranging data so related things sit together in
  memory saves cache misses — but the cleverer the arrangement, the more arithmetic
  every single access pays to compute *where things are*. If the accesses were
  mostly cache hits already, you've added cost and saved nothing.
- **Branches vs. everything.** The CPU keeps its pipelines full by *predicting*
  which way your `if`s will go. A branch that almost always goes one way is
  essentially free; an unpredictable one, or extra speculative work the CPU starts
  and throws away, quietly costs you capacity that no profiler line will ever be
  labelled with.

You cannot reason your way to which side of these trades a given change lands on.
You have to measure. Which brings us to the instruments.

## The protocol: paired, interleaved, guarded

The naive way to compare two builds is to benchmark the old one, benchmark the new
one, and compare. The numbers you get that way are quietly poisoned: the JIT warms
differently between runs, the machine's thermal and background-load state drifts,
and a 3% real effect disappears under a 5% environmental wobble.

So every candidate change here was judged by **paired, interleaved runs**: baseline
and candidate executed back-to-back, alternating, in the same session — A, B, A, B —
and compared pair by pair. Whatever the environment is doing, it's doing it to both
builds, so the *difference* stays honest even when the absolute numbers wander.

The second half of the protocol is about *what* you run. Our workhorse scenarios are
hard searches — thousands of nodes, heavy digging and building — because that's
where regressions in the inner loop show up loudest. But a search benchmark suite
made only of hard searches has two blind spots, so two guard scenarios stand
permanent watch:

- **SHORT — the cold-start guard.** A trivially easy 28-block flat walk, ~30–60
  nodes, that *includes the per-search setup* in the measurement, exactly as
  production pays it on every re-plan. A ten-thousand-node search amortizes setup
  down to nothing; the common-case tiny search is *mostly* setup. Any change that
  makes per-search construction heavier shows up here and nowhere else.
- **MULTI — the persistence guard.** Four consecutive searches (short, long, short,
  long) in one measured operation, the way a live bot re-plans every couple of
  seconds while its reusable scratch structures stay warm. A change that leaks state
  from one search into the next shows up here as a wrong-cost anomaly relative to
  the same searches measured alone.

The rule: a candidate must win somewhere and regress *nowhere* — including the
guards.

## Five convincing ideas

### The Hilbert curve: better locality, 2–3× slower

The textbook pitch writes itself. Our grid cells are stored in plain row-major
order, so two cells that are neighbors in the world can be far apart in memory. A
**Hilbert space-filling curve** stores the world so that cells close in space stay
close in memory — measurably better locality, beloved of databases and GIS systems.
The pathfinder reads tight clusters of neighboring cells all day; surely it wants
this.

Benchmarked: **2–3× slower.** Not a few percent — catastrophically worse.

This is the locality-vs-arithmetic trade landing on the wrong side, hard. Computing
a Hilbert index is a genuine little computation (rounds of bit manipulation per
lookup), replacing row-major's shift-and-mask, on *every one of the ~hundred reads
per node*. And the misses it was supposed to save largely weren't there: the reads
cluster inside one 16×16×16 section, whose data already fits in a few cache lines —
the plain layout was already local *enough*. We paid real arithmetic to solve an
imaginary memory problem. The better locality was real; it just wasn't worth
anything.

### Eager neighbor prefetch: +7% to +26%

Second idea: we *know* roughly which cells a node expansion is going to read — the
neighborhood right around the node — so gather them up front, in one tidy pass, and
let the movement code read from a warm local buffer instead of scattering lookups
through the loop. Batching reads is a classic; this is exactly the shape of loop
it's advertised for.

Benchmarked: **7–26% slower**, depending on scenario.

The problem is that "roughly which cells" isn't "which cells." The movement code is
full of early exits — a candidate that fails its first check never asks about the
rest of its geometry — so the lazy version skips a large fraction of the reads the
eager version faithfully performs. We were doing *more* memory work, not the same
work sooner. And the latency we hoped to hide was already being hidden: a modern
out-of-order core runs far ahead of the "current" instruction and overlaps those
scattered lazy reads on its own, provided (as [earlier chapters](block_reading.md)
arranged) they're simple array loads it can see through. The CPU's own speculation
was already extracting the win; ours just added the waste.

### Heap key packing: perfectly flat

Third idea, smaller: the open set's binary heap keeps two parallel arrays — an
`int` row and a `float` score — so every sift touches two cache lines where one
could do. Pack both into a single `long` per entry and the heap's memory traffic
halves. Cheap to build, obviously sound.

Benchmarked: **no measurable difference.** Not better, not worse, run after paired
run.

The memory traffic we halved simply wasn't a bottleneck — the heap is small and hot,
and it was hitting cache both ways. A flat result is still information: it tells you
where the wall *isn't*, and it settles the argument in favor of whichever version is
simpler to read. We kept the two plain arrays.

### The hybrid chunk cache: killed by the guard, +3.6%

Fourth: a redesign of the per-search chunk cache (the one from
[the hot-path chapter](pathfinding_hot_path.md)) that layered a faster front cache
over the existing table. On the big, hard scenarios it measured fine — maybe a hair
ahead, within noise.

**SHORT caught it: +3.6% on the cold-start scenario.** The redesign made each
search's setup slightly heavier, and only the guard scenario — the one where setup
*is* the workload — could see it. The hard searches amortized the setup cost into
invisibility, which is precisely why they can't be trusted alone: production is
mostly *small* searches. A benchmark suite is a set of claims about what your real
workload looks like, and the least glamorous scenario in ours is the one that
vetoed the change. Reverted.

### The survivor: adaptive edit scanning, −40%

One idea made it. It came straight out of profiler data rather than first
principles — which is probably not a coincidence.

When a macro move validates its jump, it has to check that no planned block edit
sits inside a box of cells (see [the macro chapter](cuboid_macro_movements.md)).
The original code swept the box cell by cell, probing the edit table for each — one
hash probe per cell. Fine when the box is a few cells. But on dig-heavy searches the
profiler showed these sweeps eating a serious share of the whole search: a wide box
is *thousands* of probes, to find at most a handful of edits.

The fix: keep the edits in a dense list as well as a hash table, and make the scan
**adaptive** — if the box's volume is smaller than the edit count, sweep the box; if
the edit list is shorter, walk the list and test each edit against the box. Same
answer either way, provably, byte-for-byte identical paths out — the choice is pure
cost model, never behavior. Small-box checks stay exactly as fast as before; the
pathological wide-box case collapses from O(volume) to O(edits).

Benchmarked: **−40% on the edit-heavy scenario**, nothing lost anywhere else,
guards clean. Kept.

## The scoreboard

| Candidate | Sounded like | Measured | Verdict |
| --- | --- | ---: | --- |
| Hilbert-curve layout | better cache locality | **2–3× slower** | reverted |
| Eager neighbor prefetch | hide memory latency | **+7% to +26%** | reverted |
| Heap key packing | halve heap traffic | flat | reverted (simpler code wins) |
| Hybrid chunk cache | faster hot lookups | **+3.6% on SHORT** | reverted — guard veto |
| Adaptive edit scanning | fix a measured hot spot | **−40% edit-heavy** | **kept** |

One survivor in five, and the one that survived was the one that started from a
profiler reading instead of a plausible theory. That's the honest shape of
performance work past the easy wins. The failures weren't dumb ideas — every one of
them is a real technique that genuinely works *somewhere*. They just didn't work
*here*, and the only way to know was to build them and let the instruments vote.

The uncomfortable corollary is worth saying plainly: without the paired protocol and
the guard scenarios, we would have shipped at least one of those regressions — with
a confident comment above it explaining why it was faster.
