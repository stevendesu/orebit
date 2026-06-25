A Minecraft server runs at 20 ticks per second. That gives it exactly **50
milliseconds** to do *everything* a tick requires — move every mob, run every
hopper, grow every crop, simulate every falling block, and answer every player —
before it has to start the next tick. Go over budget and the server can't keep
up: the world stutters, and you get the dreaded "Can't keep up! Is the server
overloaded?" in the console.

Pathfinding has to live inside that budget, and it is not cheap. To find a route,
the A\* search explores a frontier of candidate positions — thousands of them for a
hard path — examining each one's surroundings before deciding where to step next.
Each of those positions is a *node*, and the search visits them one after another
in a tight loop.

Where that loop runs is a design decision still in motion. Today the search runs
inline on the server's tick thread, so it has to finish inside the budget; longer
term we plan to move it onto a background thread that can spread a single search
across several ticks. But notice that the *per-node* cost matters under either
plan — inline, it decides how much of the 50ms you burn; in the background, it
decides how many ticks the bot stands around thinking, and how much CPU it steals
from everything else while it does. Cheaper nodes are a win no matter where the
loop ends up living.

So the arithmetic that governs everything is brutally simple:

$$\text{search time} = \text{nodes visited} \times \text{cost per node}$$

If a node costs 7 microseconds and a hard search visits 10,000 of them, that's
**70 milliseconds** — more than an entire tick's worth of work for one bot
deciding where to walk. We have two levers. We can visit fewer nodes (that's the
job of a good heuristic and, eventually, hierarchical pathfinding), or we can make
each node cheaper. This page is about the second lever, and it turns out there was
a *lot* of room.

## Measuring the damage

The first thing I did was instrument the search to print one line per path: how
many nodes it visited, how long it took, and the time-per-node that falls out of
dividing the two. Then I asked the bot to do something genuinely hard — climb and
dig its way up through terrain — and watched the numbers.

They were ugly:

```
path TIMING: 10001 nodes in 80247 us (8024 ns/node) +edits -> FAIL-budget
```

**Eight thousand nanoseconds per node.** Ten thousand nodes taking 80
milliseconds — more than a full tick's worth of work, for a single search. Worse,
the numbers jumped around from run to run: one search would clock 7,000 ns/node,
the next 8,500, the next 7,400. Inconsistent *and* slow.

Now, here's the part that should make you suspicious. We worked very hard, in the
[chapter on reading blocks](block_reading.md), to get the actual cost of reading a
cell's data down to single-digit nanoseconds. The data lookup at the very bottom
of a node expansion is about **7 nanoseconds**. So where did the *other ~7,993
nanoseconds* go?

The answer is the plumbing. We were wrapping a 7-nanosecond read in a thick
layer of allocation and bookkeeping, and calling it roughly a hundred times per
node. To understand why that's so expensive, we need to talk about a quiet little
performance villain that hides inside one of Java's most innocent-looking lines
of code.

## The hidden cost of a `HashMap`

A textbook A\* keeps a few bookkeeping structures. It needs to remember the best
cost found so far to reach each cell (`gScore`), and which cell each cell was
reached *from* so it can rebuild the path at the end (`cameFrom`). The obvious,
every-tutorial-does-it way to write that in Java is with maps:

```java
Map<Long, Float> gScore   = new HashMap<>();
Map<Long, Long>  cameFrom = new HashMap<>();
```

Our cells are identified by a `long` — Minecraft packs a block's X, Y, and Z into
a single 64-bit integer. So a key is a `long`, and looking up a cell's cost is
just `gScore.get(key)`. Clean, readable, correct.

It's also a trap, and the trap is a Java feature called **autoboxing**.

A Java `HashMap` cannot store primitives like `long` or `float`. It can only
store *objects*. So when you write `gScore.get(key)` with a `long` key, Java
quietly wraps that primitive in a `Long` *object* — it "boxes" it — so the map
has something object-shaped to hash. That wrapper is a tiny allocation on the
heap.

Java does keep a cache of pre-made `Long` objects to soften this... but only for
small values, from −128 to 127. Our keys are block positions packed into 64 bits
— gigantic numbers, nowhere near that cache. So **every single map operation
allocates a brand-new `Long` on the heap.** And `HashMap<Long, Float>` boxes the
*value* too, so a `gScore.put` mints a fresh `Long` *and* a fresh `Float`.

Remember the lesson from [reusing memory](reusing_memory.md): allocation is slow,
and the garbage it creates wakes up the Garbage Collector, which is slower still.
Now picture doing it on every cell read — about a hundred times per node — across
four separate maps, ten thousand nodes deep. We weren't reading the world. We
were feeding the Garbage Collector a firehose of tiny short-lived objects, and
paying for it twice: once to allocate them, and again, later and unpredictably,
when the collector ran to sweep them up.

That second cost is exactly why the timings *jittered*. A search that happened to
trigger a garbage-collection pause mid-flight measured slower than one that
didn't. The inconsistency wasn't noise — it was the Garbage Collector showing up
at random moments to clean up our mess.

So the plan is: **get the allocation out of the loop entirely.** No boxing, no
per-node objects, nothing for the collector to do. Let's go structure by
structure.

## Fix #1: stop re-deriving the chunk on every read

Before we even touch the maps, there's an easy win in how we locate a cell's
data. Every cell read had to find which chunk the cell lived in and pull that
chunk's nav data out of a store. That store is a map keyed by chunk —
so, naturally, *another* boxed-`long` lookup. Two of them, in fact, plus the box,
on every one of the ~100 reads per node.

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
[a page of its own](custom_hash_map.md).

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
the more if the search later moves to a background thread of its own.

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

And we're not done. The per-node cost is now low *and* steady, which means the
next lever — visiting *fewer* nodes — is where the next big wins are hiding: a
sharper heuristic now, and hierarchical pathfinding later. But that's a story for
[another page](fewer_nodes.md).
