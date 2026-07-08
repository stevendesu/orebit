# Off the Tick Thread

Every optimization so far has been about making the search *cheap* — fewer nodes, fewer
nanoseconds per node, no allocation. This chapter is about a different lever: making the
search *not the server's problem*. Because no matter how cheap a node gets, a big enough
search still doesn't fit in a Minecraft tick.

## The tick is a hard 50 ms, and search was spending it

A Minecraft server runs at twenty ticks a second: **50 ms per tick**, shared by every
mob, block update, and player in the world. Whatever the pathfinder spends, everything
else does without. [The hot-path chapter](pathfinding_hot_path.md) is the story of getting
a search node down to ~400–700 ns so a flood *fits* — but "fits" was always a compromise.
A warm window search runs 0.1 ms on a short hop and 4–16 ms on a flood or an edit-heavy
pillar. Sixteen milliseconds is a *third of the tick*, gone, on one bot deciding where to
put its feet — and a server owner wants twenty bots.

Worse, the tick budget was a leash on the bot's *intelligence*. The way you let A\* escape
a hard local minimum — the tower, the maze — is to let it look at more positions. But
"look at more" meant "spend more tick," so the node cap that stopped the jank also capped
how clever the bot could be. A goal that needed twelve thousand positions to solve was
simply unsolvable, not because the search couldn't do it but because it wasn't allowed to
do it *between two frames*.

[Fewer Nodes](fewer_nodes.md) named the way out: return your best partial progress and
plan the route in segments. Both of those want the same thing underneath — a search that
runs on **its own time**, not the server's.

## Move it to a worker, almost for free

The plan is the obvious one: run the search on a background thread and adopt its result
when it's done. What makes it cheap is that the search was *already* built for it, years
before we needed it.

Two properties fell into place. The search's scratch — its node table, its heap, its edit
pool — was already [thread-local](reusing_memory.md): each thread that runs a search gets
its own isolated arena that grows once and serves forever. And the nav grid the search
reads, the `NavStore`, was already a per-level concurrent map. So a pool of worker threads
needs no new locking on the hot path; each worker gets a private search arena for free and
reads the shared grid without contention.

The tick thread's job shrinks to a clean handoff. It packages an **immutable request** —
start, target, the bot's capability gate, a *snapshot copy* of the inventory (never the
live one), the search budget — and submits it to the pool. It gets back a small mailbox it
polls. When a result lands, it's adopted at the exact same moment the follower already
swaps plans: a settled window boundary. One search in flight per bot, newest-wins: a fresh
request supersedes an older one that hasn't started. A worker that throws is caught
per-request and the bot falls back to its normal "blocked, repair" path — a thread never
dies with a search in its hands.

The pool is sized for the real target, not the demo. The default is two threads, clamped to
*cores − 2*, because the deployment that matters is a public server with twenty players and
twenty bots, not one developer with one bot. Admins tune bot CPU the way they tune
view-distance.

## The one genuinely dangerous part

Concurrency has exactly one place it can hurt you here, and it's worth telling in full
because the fix is prettier than the bug.

The nav grid recycles memory aggressively — a [pooling pattern](reusing_memory.md) from
the single-threaded days. When a chunk unloads, its `NavSection` objects go straight back
into a pool; the next chunk to load pops one, zero-fills it, and refills it with *its own*
blocks. Perfectly safe when one thread does everything in order. Lethal the moment a worker
thread is still reading the old section: it wouldn't read *stale* data (last tick's blocks,
harmless — it replans anyway), it would read *another chunk's* data reinterpreted as this
one's. Not a wrong path. An insane one.

The reflex is to put every grid read behind a lock or an owner thread. That reflex is
wrong: the search reads the grid *millions of times*, and a message hop or a lock on the
per-read path would undo the entire hot-path chapter. The insight is that **reads are
fine** — only *reclamation* is dangerous. So coordinate only the reclamation.

The mechanism is a global epoch counter, bumped once per tick, and one published slot per
worker thread. A worker stamps the current epoch when it starts a search and clears it when
it finishes — two uncontended writes per search, nothing per node. When a chunk unloads,
its displaced sections aren't recycled immediately; they're **retired** into a grace list
tagged with the epoch they were retired in. Once a tick, the tick thread finds the oldest
epoch any worker is still stamped with, and recycles only the batches retired strictly
before it. A section can't be handed to a new chunk until every search that could still be
holding it has finished. Mutation ownership never moves — the tick thread stays the sole
writer and recycler; workers are pure readers who never touch a section that might be
pulled out from under them.

A companion rule closes the same door from the other side: the background grid view takes
*no* live-world fallback. A vanilla chunk read isn't thread-safe, so a worker that probes
outside the built nav data just sees air rather than reaching into the live world. The
direction of that error is "the search plans a little less at the unexplored frontier,"
never "the search plans through phantom terrain." When you can't be exact off-thread, be
*optimistically bounded* — and let the replan on approach fix it.

## Once it's off-tick, the honest budget is time

The node cap — "stop after ten thousand positions" — was always a *proxy*. What we
actually meant was "stop before you've spent too much tick." On a background thread there's
no tick to spend, so we can say the real thing: **stop after this many milliseconds**.

The search loop checks a deadline every 256 pops — one predictable branch, a clock read a
fraction of a percent of the time — and returns its best partial when the clock runs out.
The node cap doesn't disappear; it changes jobs. It becomes a pure *memory* backstop so a
fast machine can't grow the node table without bound, and — importantly — a **determinism
switch**: a deadline of zero means node-cap-only, byte-for-byte the old behaviour, which is
what keeps the benchmarks and unit tests timing-independent. A search that *completes* under
its deadline is identical to one that never had a deadline; the clock only decides where a
*partial* truncates, and partials get replanned at the next boundary anyway. The
nondeterminism is confined to the budget-exhaustion case that was already nondeterministic.

The budget buys room the tick never could. At ~1500 ns a node, the default 250 ms budget
is on the order of a hundred and fifty thousand positions — fifteen times the old cap —
*without touching a single tick*. The floods that used to be unsolvable-between-frames now finish;
in testing, a search that needed **13,600 positions found its path**, well past the old
ten-thousand wall it would previously have died at.

## No pause at the seam

The last piece is latency, not throughput. Adopting a finished search at a window boundary
is great, but there's a beat where the bot reaches the boundary and waits for the next
window's search — a stutter every few dozen blocks.

So we plan ahead. Once the bot is more than halfway through the current window, the tick
thread submits the *next* window's search early, starting from the boundary cell it can
already predict, with the current plan's not-yet-applied edits folded in as a baseline so
the new search prices the world as it will *be* when the bot gets there. When the bot
actually reaches the boundary, if its real position agrees with the prediction (within a
small tolerance), the pre-computed plan is adopted with **no pause at all** — the bot flows
through the seam. A prediction that turns out wrong is simply parked and retested at each
boundary; it never churns mid-window. This is Baritone's no-boundary-pause splicing, sitting
on the window-and-boundary machinery the [region tier](region_heuristic.md) already gave us
— and the same splice primitive is what a future cross-dimension portal router will reuse
unchanged.

## What it cost, and what it bought

The headline: on the complex paths that used to hitch, tick time dropped from **8–16 ms to
about 3 ms** — the search left the tick, so the tick stopped paying for it. And the ceiling
lifted: searches that couldn't finish in a frame now finish on their own time.

Held to the [house benchmark protocol](measure_everything.md), the change measured
**neutral** on the synchronous path — the per-node deadline check and the request plumbing
are free, sign-flipping across interleaved pairs at well under a percent. That's the result
you want from an architectural change: it moves the work, it doesn't tax the work.

It shipped **off by default** while it soaked, and now ships **on** (`pathing.async`) —
the synchronous path is still there (`pathing.async = false`) and still byte-identical,
which is exactly what made flipping the default low-risk. `pathing.maxThreads` sizes the
pool; `pathing.asyncSearchBudgetMs` sets the wall-clock budget. See
[Configuring Orebit](../configuration.md).

The lesson is the inversion. Every prior chapter asked "how do we make this fit in the
tick?" This one asked "why is this in the tick at all?" — and the answer was "no reason
we couldn't fix." The tick budget was a constraint we'd been treating as a law of physics.
It was just an address, and the search moved.
