A Minecraft server runs at 20 ticks per second. That gives it exactly **50
milliseconds** to do *everything* a tick requires — move every mob, run every
hopper, grow every crop, simulate every falling block, and answer every player —
before it has to start the next tick. Go over budget and the server can't keep
up: the world stutters, and you get the dreaded "Can't keep up! Is the server
overloaded?" in the console.

Pathfinding has to live inside that budget, and it is not cheap. To find a route, the
bot runs **A\***, the classic shortest-path search — and since the rest of this book
leans on it, here it is in a paragraph. A\* explores outward from the start one **node**
at a time, a node being a candidate position the bot could stand in. Each node is scored
by two numbers: **`g`**, the cost of the route that reached it so far, and **`h`**, a
*heuristic* — a cheap, educated guess at the cost still ahead to the goal. A\* always
expands the node with the smallest **`f = g + h`** (its best estimate of a whole path
through that point), looks at the moves leading out of it, and repeats. That heuristic is
what makes A\* *smart*: a good guess aims the search straight at the goal, while a poor
one — or none at all — leaves it flooding outward in every direction. A hard search runs
through thousands of these nodes, one after another in a tight loop, and this chapter is
about making each one cheap to visit.

That loop can run in one of two places, and Orebit has moved between them. It once
ran inline on the server's tick thread, where it had to finish inside the 50ms
budget; today it runs on a background thread that can spread a single search across
several ticks — a story with [its own chapter](10_background_pathfinding.md). But
the *per-node* cost matters under either arrangement: on the tick thread it decides
how much of the 50ms you burn; in the background it decides how many ticks the bot
stands around thinking, and how much CPU it steals from everything else while it
does. Cheaper nodes are a win no matter where the loop lives.

So the arithmetic that governs everything is simple:

$$\text{search time} = \text{startup cost} + (\text{nodes visited} \times \text{cost per node})$$

The startup cost is the fixed price of getting a search off the ground — allocating
the scratch arrays, hash maps, and chunk caches it works in — paid once before the
first node. It's real, and it dominates the short, everyday searches. But on a
*hard* path the second term runs away with everything: if a node costs 7
microseconds and a hard search visits 10,000 of them, that's **70 milliseconds** —
more than an entire tick's worth of work for one bot deciding where to walk. We have
two levers on that term. We can visit fewer nodes — the job of a sharper heuristic
and of the [hierarchical, two-tier search](../pathfinding.md) that plans the coarse
route first — or we can make each node cheaper. This page is about the second lever,
and it turns out there was a *lot* of room.

## Measuring the damage

The first thing I did was instrument the search to print one line per path: how
many nodes it visited, how long it took, and the time-per-node that falls out of
dividing the two. Then I asked the bot to do something genuinely hard — climb and
dig its way up through terrain.

That kind of route isn't just walking. To get through a wall the bot *breaks*
blocks; to climb a face it *places* them, pillaring up. Each such move carries an
**edit** — the handful of cells it would change — which the search records so that
later moves along the same path plan against the world as it *will* be, not as it
looks now. That's the `+edits` marker in the timing line below, and those little
edit records turn out to matter a great deal for performance — so hold onto the idea.

The numbers were ugly:

```
path TIMING: 10001 nodes in 80247 us (8024 ns/node) +edits -> FAIL-budget
```

**Eight thousand nanoseconds per node.** Ten thousand nodes taking 80
milliseconds — more than a full tick's worth of work, for a single search. Worse,
the numbers jumped around from run to run: one search would clock 7,000 ns/node,
the next 8,500, the next 7,400. Inconsistent *and* slow.

Now, here's the part that should make you suspicious. We worked very hard, in the
[chapter on reading blocks](01_block_reading.md), to get the actual cost of reading a
cell's data down to single-digit nanoseconds. The data lookup at the very bottom
of a node expansion is about **7 nanoseconds**. So where did the *other ~7,993
nanoseconds* go?

The answer is the plumbing. We were wrapping a 7-nanosecond read in a thick
layer of allocation and bookkeeping, and calling it roughly a hundred times per
node. To understand why that's so expensive, we need to talk about a quiet little
performance villain that hides inside one of Java's most innocent-looking lines
of code.

## The hidden cost of a `HashMap`

That innocent-looking line is a map lookup. A textbook A\* leans on them: it
remembers the best cost found so far to reach each cell (`gScore`), and which cell
each was reached *from* so it can rebuild the route at the end (`cameFrom`) — and
every cell read *also* has to look up which chunk holds that cell's data, through
yet another map. All of them are keyed by a block position, which Minecraft hands us
as a single packed `long`. And a `HashMap<Long, …>` can't store a primitive `long`:
it silently **boxes** every key into a throwaway `Long` object on the heap just to
hash it. (The full anatomy of that tax — flexible, general-purpose, and quietly slow
in a tight loop — and the map we built to escape it got the
[previous chapter](04_custom_hash_map.md) all to itself.)

Now do that roughly a hundred times per node, across several maps, ten thousand
nodes deep. We weren't so much reading the world as feeding the Garbage Collector a
firehose of tiny short-lived objects — and paying for it twice: once to allocate
them, and again, later and unpredictably, when the collector woke to sweep them
away. That second cost is exactly why the timings *jittered*: a search that happened
to trigger a collection mid-flight measured slower than one that didn't. The
inconsistency wasn't noise — it was the collector showing up at random moments to
clean up after us.

So the plan is: **get the allocation out of the loop entirely.** No boxing, no
per-node objects, nothing for the collector to do. Let's go structure by structure.

## Fix #1: stop re-deriving the chunk on every read

Before we even touch the search's own bookkeeping maps, there's an easy win in how
we locate a cell's data. Every cell read had to find which chunk the cell lived in
and pull that chunk's nav data out of a store. That store is the chunk map the
diagnosis just named — so, naturally, *another* boxed-`long` lookup on every one of
the ~100 reads per node.

But here's the thing about those hundred reads: they're not scattered across the
world. The cells a single node examines are all clustered right around that node,
which means they almost all fall in the **same one or two chunks**. We were
re-doing the full chunk lookup for every read when the answer barely changed.

The fix is a one-line idea: remember the last chunk we looked up.

```java
if (chunkKey == cacheChunkKey) {
    sections = cacheSections;          // same chunk as last time — just reuse it
} else {
    sections = chunks.get(chunkKey);   // crossed a chunk boundary — do the lookup
    cacheChunkKey = chunkKey;
    cacheSections = sections;
}
```

With that cache in place, a node's hundred reads do the expensive boxed lookup
maybe *once or twice* — when the read actually crosses into a new chunk — and the
rest become plain array accesses. About a hundred hash lookups per node collapse
to about two. This was the single highest-leverage change, and it cost almost
nothing to write.

## Fix #2: throw out the maps and the node objects

Now for the main event. We need to replace those four boxing `HashMap`s — and the
little `Node` object we were allocating for every candidate we considered — with
something that never touches the heap.

The trick is to stop thinking in *objects* and start thinking in *arrays*. Every
position the search discovers gets a **row number** — 0, 1, 2, 3 — assigned in
the order we find it. Then, instead of one `Node` object holding a position's
cost, parent, and so on, we keep a set of plain primitive arrays, one per field,
all indexed by that row number:

```java
long[]  key;      // the packed position of row i
float[] g;        // best-known cost to reach row i
float[] f;        // g plus the heuristic estimate to the goal
int[]   parent;   // the row this one was reached from (-1 at the start)
```

Row 5's cost is just `g[5]`. Its parent is `parent[5]`. There are no `Node`
objects to allocate — a "node" is now nothing more than an integer index into
these arrays. To walk the finished path backwards, we follow `parent[]` from row
to row, no map lookups required. (Programmers sometimes call this a "structure of
arrays," and it's a favorite trick in high-performance code precisely because it
sidesteps per-element object overhead.)

There's one piece left. When the search reaches a position, it needs to ask "have
I seen this position before, and if so, which row is it?" — a lookup from a packed
`long` to a row number. That's the job the boxing `HashMap` used to do, so we
still need a map... just one that doesn't box. So we wrote our own: an
**open-addressing** `long`-to-`int` map built from two flat arrays, with no
`Entry` objects and no boxing anywhere in sight. It's the linchpin of the whole
rewrite — and it has a couple of genuinely clever tricks in it — so it gets
[a page of its own](04_custom_hash_map.md).

The exact same idea handles the search's frontier — the queue of positions
waiting to be explored, ordered by most-promising-first. Java's `PriorityQueue`
would want us back in `Node`-object land. Instead we keep a binary heap as a
plain `int[]` of row numbers, ordered by a parallel `float[]` of their f-scores.
Pushing and popping is just integer swaps in an array.

When the dust settled, the entire search state was a handful of primitive arrays,
sized once at the start of a search and reused for every node within it. The
inner loop — the thing that runs millions of times — allocates **nothing**.

## The payoff

Here's the same kind of hard, dig-and-climb search that opened this page, after
the rewrite:

```
path TIMING: 10001 nodes in 12914.6 us (1291 ns/node) +edits -> FAIL-budget
path TIMING: 10001 nodes in 12838.5 us (1284 ns/node) +edits -> FAIL-budget
path TIMING: 10001 nodes in 12827.2 us (1283 ns/node) +edits -> FAIL-budget
path TIMING: 10001 nodes in 12873.2 us (1287 ns/node) +edits -> FAIL-budget
```

From roughly **8,000 nanoseconds per node down to about 1,290** — a little better
than a **6× speedup**. The worst-case search that used to chew through 80
milliseconds — more than a full tick's worth of work — now finishes in under 13.

But look closely at those four lines, because they're telling you something the
average doesn't. They're *all within eight nanoseconds of each other*. Before, the
timings scattered across a thousand-nanosecond range as the Garbage Collector
came and went. Now they're rock-steady, run after run.

That consistency isn't a happy accident — it's the *whole point*. The numbers
agree because there's no longer any garbage being created mid-search, so the
collector has no reason to interrupt us. We didn't just make the pathfinder
faster on average; we made it **predictable**, and on a server where every tick
has a hard 50ms deadline, a cost you can plan around is worth as much as a low
one. There's a bonus here, too: a garbage-collection pause in Java stops *every*
thread, not just the one that made the garbage. So keeping the search
allocation-free protects the rest of the server from its mess — which matters all
the more now that the search runs on a background thread of its own.

## A loose end the benchmark caught

That "allocates nothing" deserves an asterisk — and finding it is a small lesson
in *what you measure*.

The four maps were gone, but look again at Fix #1, the last-chunk cache. When a
read *does* cross into a new chunk, it still calls `chunks.get(chunkKey)` on the
nav store — and that store was an ordinary `ConcurrentHashMap<Long, …>`. The same
boxing villain, sitting in the one lookup we'd waved away as happening "maybe once
or twice per node." Once or twice per node *sounds* free, until you remember a hard
search visits ten thousand nodes. A couple of boxed `Long`s apiece is tens of
thousands of throwaway objects per search, hiding in plain sight behind the very
cache we'd congratulated ourselves on.

The per-path `TIMING` line couldn't see it. It measures wall-clock, and a boxed
allocation mostly costs you *later*, when the collector runs — so the bytes don't
show up next to the nanoseconds. To actually see them we built a proper
[JMH](https://github.com/openjdk/jmh) benchmark: drive a search over a synthetic,
in-memory world, headless and repeatable, with an *allocation* profiler that
reports bytes-per-search instead of time. We ran it on the scenario we lean on most,
**TOWER** — the bot building a thirty-block pillar straight up through open air.
It's the build-heavy case by design: every step up *places* a block, so it works the
edit-tracking machinery harder than any plain walk, which makes it the sharpest lens
for the allocation — and, later, the CPU — we're hunting. The number it reported was
damning, for a loop we'd declared allocation-free:

```
TOWER  gc.alloc.rate.norm   5,791,820 B/op
```

**Nearly six megabytes of garbage for a single search.** Almost all of it that one
residual chunk lookup, minting a `Long` on every chunk-boundary crossing.

The fix is the same trick the search maps already used, applied one level out. The
single-slot cache grows into a small **open-addressed `long`-keyed cache** — a few
hundred slots, comfortably more than the distinct chunks any one bounded search
visits — holding *every* chunk the search has touched, not just the last one. A
chunk key is now boxed at most *once*, on its first visit, and every later crossing
back into it is a plain array read. (It's the
[custom hash map](04_custom_hash_map.md) again, this time mapping chunk → sections.)
The benchmark confirmed it immediately:

| per single TOWER search | before | after |
| --- | ---: | ---: |
| allocation | 5,791,820 B | 1,354,470 B |
| time | 12,067 µs | 8,568 µs |

A **77% cut in garbage** and a **29% cut in time**, from a change that — like Fix
#1 before it — is a few lines and a cache. And the lesson isn't subtle: a
wall-clock timer will cheerfully report a loop as fast while it quietly buries the
collector. Only measuring *allocation* showed where the bytes were really going.

## The last thread: pooling the edit-sets

The chunk cache left about **1.3 MB** still allocating per search, and the same
allocation profiler pointed straight at it: **97% was a single method** —
`EditScratch.snapshot()`. Every time the search accepts a move that breaks or places
a block, it had been minting a fresh little edit record (a `StepEdits` object plus
two `long[]` arrays inside it) listing the affected cells. A build-heavy search
accepts *thousands* of those edges, so that was thousands of throwaway objects — the
entire remaining budget, concentrated in one spot.

The catch is these edit-sets can't simply be reused in place: the handful that land
on the *winning* path ride home inside the returned plan and are replayed by the bot
over many ticks, long after the search ends. So we split their lifetime. During a
search, every accepted edge draws a `StepEdits` from a **per-search pool** — a flat
array of reusable instances, rewound to the start on each new search, that grows once
to its high-water mark and then never allocates again. Only the few edits on the
*final* path are copied out into standalone objects that outlive the pool. The
thousands of edit-sets the search considers and discards now share zero allocations
between them.

With that, the benchmark's hard search drops from 1.35 MB to **about 900 bytes** —
essentially nothing, a 10,000-node search that no longer troubles the collector at
all:

| per single TOWER search | start | + chunk cache | + edit pool |
| --- | ---: | ---: | ---: |
| allocation | 5,791,820 B | 1,354,470 B | ~900 B |
| GC collections | dozens | a handful | none |

In the live game, the dig-and-climb search now holds a **steady ~950 nanoseconds per
node**, with only the occasional stray spike — the kind that comes from the operating
system or other work sharing the thread, not from garbage we made. The search is, at
last, genuinely allocation-free on its hot path.

## A different question needs a different profiler

With the allocation gone, the remaining question changed shape. It was no longer
"where are the *bytes* coming from" — there were barely any left — but "where do the
*nanoseconds* go." Those are different questions, and they need different instruments.
The allocation profiler we'd leaned on is blind to a loop that's merely *busy*; for
that we want a **CPU profiler**, one that periodically interrupts the search and writes
down which method it caught running. Sample often enough and the methods that show up
most are the ones eating the time.

So we pointed one at the dig-and-climb search. The striking thing about the result was
how *flat* it was — no single villain. The cost was spread thinly across the genuine
work of A\*: shuffling the priority queue, hashing positions into the row table,
generating each move's candidates. That flatness is itself the headline. It says the
machinery is honestly tuned; the per-node cost is the algorithm, not a wart. (It also
quietly settled a worry: the actual movement logic — read the geometry, decide if you
can step there — was a rounding error, **about 4%** of an ordinary walk. We can keep
*adding* movement types — swim, climb, parkour, portals — without fear of the node
getting fatter.)

But one method did stand out, and only on the build-heavy searches: `PathEdits.kindAt`,
at a quarter of the whole search. Recall the per-path edit diff — the little table that
remembers "this move plans to place a block here, break one there," so a later move
reads the world as it *will* be. Every geometry read consults it first. And once the
path has placed even a single block, *every* read — about a hundred per node — was
paying a full hash-and-probe of that table, almost always to be told "nothing here."

The waste is geometric. A search's edits are *clustered*: a pillar is one vertical
column, a dug staircase a short diagonal smear. But the cells a node reads fan out all
*around* it, and the overwhelming majority are nowhere near any planned edit. We were
hashing each one to discover what a glance at the map would have told us.

So we gave the diff a glance. The table now tracks the **bounding box** of every edit
it holds — six integers, updated as edits go in. A read outside that box can't possibly
be an edit, so it's rejected with six integer comparisons instead of a hash and a probe:

```java
public int kindAt(int x, int y, int z) {
    if (size == 0) return NONE;
    if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) return NONE;
    return kindAt(asLong(x, y, z));   // in the box — now it's worth hashing
}
```

It's the same idea as a spatial index's cheap reject, shrunk to fit one hot method. The
answer is identical to before — a cell outside the box was always going to come back
empty — but the *far* reads, which are most of them, now cost a handful of compares:

| per single TOWER search | + edit pool | + box-reject |
| --- | ---: | ---: |
| time | 8,470 µs | **6,997 µs** |
| per node | ~847 ns | **~700 ns** |

A **17% cut** on the build-heavy search, and — because an edit-free walk never enters
the branch at all — *nothing* lost on an ordinary follow. The lesson rhymes with the
allocation one: a hidden cost was hiding in a structure we'd written off as cheap, and
it took the *right* profiler — one watching CPU, not bytes — to see it.

## Why this is the difference

It's tempting to look at all this and call it micro-optimization — fussing over
nanoseconds in a hash map. But the standard library's conveniences are priced for
code that runs occasionally, not code that runs *millions of times in a row*. A
`HashMap<Long, Float>` is a perfectly good tool right up until you call it a
million times in a single search, at which point its hidden boxing quietly becomes
the most expensive thing your program does.

This is a big part of *why* Orebit can pathfind over longer distances, more
often, with less impact on your server than the alternatives. Other pathfinding
mods lean on the same standard-library structures we started with — and pay the
same tax. By keeping the Garbage Collector out of the hot path entirely, that
budget goes to actually finding paths instead of cleaning up after ourselves.

And we're not done. The per-node cost is now low *and* steady, which means the next
lever — visiting *fewer* nodes — is where the next big wins are hiding: a sharper
heuristic, and a [hierarchical search](../pathfinding.md) that plans the coarse route
first so the fine search never floods a wall it can't see around. That's the story
the [next page](06_fewer_nodes.md) picks up.
