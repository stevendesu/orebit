# Paying for the Map

[The last chapter](11_region_heuristic.md) ended in a good place: the block search reads a
cost-to-goal field computed from the region map, and stops flooding walls it can't see
around. This chapter is about the bill. The field is rebuilt every time the search's
window target moves, and a per-re-plan cost is a hot path by another name — the
[SHORT-search lesson](08_measure_everything.md) all over again, one level up: work that
amortizes beautifully over a big search dominates the common small one.

We assumed the field build was cheap. It was not. This is the chapter where the house
religion — attribute first, then optimize — earned its keep three times in a row.

## Ninety percent of the search wasn't the search

The first honest measurement came from a new benchmark that runs the *whole* live
pipeline — region plan, field build, block search — instead of the block A\* in
isolation. A representative two-tier search: about **5.6 milliseconds**. The block A\*
we'd spent five chapters sharpening: **6.6%** of the samples. The field build:
**90.8%**.

Read that again the way we had to. The heuristic that existed to make the block search
cheaper had become **ten times the cost of the block search** — and none of the earlier
chapters' benchmarks could see it, because they all measured the block tier alone. The
sharper your knife, the more the handle weighs.

Profiling *inside* the build split the bill in two. About half was the reverse Dijkstra
itself — honest work. The other half traced to a single question asked badly:
*"which air pocket does this cell belong to?"*

## The question we kept re-answering

The [dig-flood](11_region_heuristic.md#the-goal-that-belongs-to-no-region) walks outward
from a buried goal through breakable rock, and every time it breaks into open space it
must name the pocket it entered — that pocket becomes a seed of the field. The code
answered by **re-deriving the pocket from scratch on every touch**: scan the region's
4,096 cells, flood its connected components, report which one contains the cell, throw
all of it away. On the benchmark fixtures the dig-flood touched a passable cell 145–265
times per build. Same region, same flood, hundreds of times.

The allocation profile told the same story from another angle: ~78% of everything the
build allocated was the bookkeeping of asking — a boxed hash-set of visited cells, a
queue of little coordinate arrays, and (a fun one) the boxed-key map lookups behind the
per-cell reads, whose collision bins allocated a *reflection* object per comparison.
The build wasn't computing too much; it was **remembering too little**.

The fix is the obvious one once the profiler has rubbed your nose in it: answer the
question **once per region, not once per cell**. On the first touch of a region, one
flood labels *every* cell with its pocket id into a pooled 4 KB slab; every later touch
in that region is a single array read. A unit test pins the slab cell-for-cell against
the old per-cell answers, so the rewrite is provably the same function — just memoized.
The BFS itself moved onto the same primitive scratch every other hot structure in this
series uses: a flat visited array and a packed-int queue, allocation-free after warm-up.

Paired interleaved A/B on the field-build benchmark: small windows dropped from
**5.6 ms to ~0.14 ms (−97%)**; the largest went **7.1 → 1.7 ms (−76%)**. Allocation
collapsed to the field's own output arrays — the flood machinery now allocates nothing.
And end-to-end, the representative two-tier search fell from **5.6 ms to 1.1 ms:
−81%**, by fixing code no block-tier benchmark had ever executed.

A small geometric epilogue tightened the worst case further. The dig-flood's radius had
been 12; shrinking it to 9 costs essentially no useful dig reach (the outer shell was
lateral crawl under the floor, not depth), but it buys a guarantee: entering a
neighbouring region costs at least 17 blocks along any axis pair, so a radius-9 flood
can touch **at most 8 regions** — at most 8 slab floods per build — where radius 12
allowed 27.

## Stop flooding when you know the way

The second half of the bill was the Dijkstra flooding the *entire* bounding box —
every region, every pocket, out to the corners — when the block search would only ever
walk a corridor through the middle of it.

But you can't just stop early. The field's contract is a **lower bound**: the block
heuristic takes `max(octile, field)`, and a missing value silently read as "free",
which is exactly the lie the field exists to correct. Truncating the flood risks
punching holes in the map precisely where a detouring search wanders.

The answer has two parts, and the second is what makes the first safe:

- **The fat skeleton.** The flood is goal-rooted, so the moment the *bot's* region
  settles, the Dijkstra's parent links already spell out the optimal goal-to-start
  chain through the coarse graph. We keep flooding only until every region within one
  step of that chain — the chain, made fat — has finished its queued work, then stop.
  The search gets exact values along the corridor it will actually explore, plus a
  one-region shoulder for local wobble. And because Dijkstra settles cheapest-first,
  the early-exit run is an **exact prefix** of the exhaustive one — every value it does
  record is *identical* to the value the full flood would have recorded, a property the
  tests pin directly.
- **The frontier floor.** Everything unsettled reads a *provable* bound instead of a
  blank. Dijkstra settles costs in non-decreasing order, so the last settled cost is a
  floor under every cost it never reached. We return `max` of that floor and a second,
  goal-anchored bound — region distance to the goal times the cheapest possible
  region crossing — so even far outside the flooded corridor the field still *slopes
  toward the goal* rather than reading one flat value. A query on this field never
  answers "unknown"; a search forced into a wide detour keeps its guidance all the way
  around.

Measured under the same protocol: another **−17…−32%** off field builds at production
window sizes, with 20–40% fewer regions settled, the block-tier guard scenarios flat,
and one honest caveat in the ledger — on tiny sub-production boxes the arming overhead
costs a few microseconds, accepted and documented.

## The one that didn't survive

The campaign ended the way [Measure Everything](08_measure_everything.md) says campaigns
end. With the field now consulted on every node pop, the per-pop heuristic recompute
looked like the next obvious target — cache the value computed at relax time, read it
back at pop time, save a whole field lookup per expansion. Mechanically sound,
byte-identical output, implemented cleanly. Measured: **flat on every scenario**, well
under the keep bar. The recompute it eliminated was one call amid fourteen movements'
worth of candidate generation per pop — too thin a slice to surface. Reverted without
sentiment.

Three fixes kept, one reverted, and the map's price tag went from ten times the search
to a fraction of it — every step of which was invisible until the benchmark measured
the *whole* pipeline instead of the part we'd already made fast. The handle, it turns
out, deserves the same scale as the knife.
