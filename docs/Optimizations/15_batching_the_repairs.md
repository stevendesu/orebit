# Batching the Repairs

Every chapter so far has been about *reading* the navigation grid fast, or searching over
it fast. This one is about *repairing* it — and about an optimization whose own design
document argued, honestly and with arithmetic, that it mostly wasn't worth building. It got
built anyway, for a reason that had almost nothing to do with the microseconds it saved.
And along the way, the identity test caught the design being subtly wrong — which is the
part worth reading this chapter for.

## The repair path

The [nav grid](../worldmodel.md) is a precomputed answer sheet, and the world it summarizes
keeps changing: crops grow, pistons push, creepers redecorate. Every block change in a
tracked chunk flows through one hook, which patches the changed cell — rewriting its
navtype, recomputing the neighborhood's precomputed flags, and repairing the
[depth nibbles](09_depth_nibbles.md) above and below it.

One patch costs about **1.4–2.1 microseconds**, and we know that number well because a
dedicated benchmark — the patch-storm suite from the depth-nibble campaign — stands
permanent guard over it. Run the honest arithmetic and vanilla-scale play (a few dozen
changes a tick) spends **two-tenths of a percent** of the 50 ms tick on grid maintenance.
That number matters, because it says the obvious "optimization" — batching the patches —
has almost nothing to win in the median case. The design review said exactly that, in
bold, in its own opening paragraph.

So why touch it at all? Two reasons, neither of them the median.

## Reason one: the spike

Patch cost is per *change*, and changes arrive in bursts. A piston row is a dozen cells in
one tick. A TNT blast is fifty. A chain of a hundred TNT is thousands — around **ten
milliseconds of synchronous patching landing inside a single tick**, a fifth of the budget,
right in the middle of whatever entity happened to trigger it. The steady state was fine;
the *worst tick* wasn't. This is the same lesson as [the persistence spike](14_catching_the_spike.md),
one subsystem over: an unbudgeted burst is a freeze, wherever it hides.

And bursts are exactly where per-change patching does redundant work. Each patch rebuilds a
descriptor scratch of the *whole section* — about 4,900 table reads — then recomputes a
little window of flags around the changed cell. Patch twelve cells of the same section
back-to-back and you rebuild that same scratch twelve times, each rebuild differing from
the last by one entry, and the overlapping flag windows get computed against stale
neighbors and then computed again. Correct — the later patches always repair the earlier
ones' staleness — but pure waste, growing with the burst.

## Reason two: the polluted signal

The second reason is sneakier, and it was worth more than the patch time itself.

The follower keeps a cheap "did the world change at all?" counter — bump it on every grid
edit, and a bot whose counter hasn't moved can skip its periodic re-search entirely,
because the result would be byte-identical. A stationary bot in a quiet world never
re-searches. Lovely.

But the counter bumped on every *block state* change — and most block state changes don't
change the *grid*. The [fingerprint table](03_block_fingerprints.md) collapses ~28,000
block states into a few hundred navtypes precisely because most distinctions (a repeater's
delay, a crop's age, which way a fence connects) are navigationally meaningless. A single
redstone clock ticking anywhere in the level was bumping the counter every tick — arming
every bot's periodic re-search, forever. Each of those re-searches costs *milliseconds*.
The debounce existed and was permanently defeated by one blinking lamp.

The fix is one comparison: intern the new state's navtype, and if it equals what the grid
already holds, **stop** — no patch, no counter bump. Equal navtype means equal descriptor
means every derived flag and nibble recomputes to the same value; the change is provably
invisible to planning. Grid-invisible churn now costs one lookup and one compare, and the
quiet-world debounce actually holds in a lived-in base.

## Defer, dedup, drain

For the burst problem, the shape of the fix is the one every neighboring subsystem already
uses: stop doing the work at the moment of the event, queue it, and drain the queue at a
well-defined point. The queue is the house pattern — an
[open-addressed map](04_custom_hash_map.md) from packed position to pending navtype, flat
primitive arrays, no boxing, cleared not freed.

Queueing buys deduplication for free: a cell changed twice before the drain just overwrites
its pending entry, because a navtype is a pure function of the final state — the deferred
patch doesn't need the history, only where it ended up. A piston that extends *and
retracts* in the same tick nets out to a pending value equal to what the grid already
holds, and the drain skips it outright: two patches become zero. At the drain, pending
cells get grouped by section, and each section pays **one** scratch rebuild for its whole
group instead of one per cell — the burst redundancy, gone.

The dangerous part is *when* to drain. The old inline patch gave every reader
read-your-writes at any instant; a queue must not quietly weaken that, or a search planned
this tick sees last tick's walls — a silent, miserable class of bug. And there is no single
point in a server tick that sits after every producer of block changes and before every
consumer of the grid: changes fire in several phases, interleaved with the bots' own ticks.
So instead of one drain point there are **flush barriers**: every entry to a server-thread
read path — constructing a live grid view for a search, the start of a bot's tick, and a
tick-end catch-all so nothing pends across ticks — drains the queue first. When the queue
is empty, which is nearly always, a barrier costs a single integer test.

## The test that overruled the design

The design document specified the drain in tidy phases: write all the pending navtypes,
then recompute all the flags, then run all the depth-nibble repairs. Flags are a pure
function of the final navtype field, so phasing them is provably safe. The depth repairs
looked like they'd phase just as cleanly.

They don't. The randomized identity harness — apply the same change sequence patched
one-at-a-time and batched, demand byte-identical grids — failed on a shape no one had
reasoned about: **two changes in the same column, both already written**. A depth repair
walks away from its cell until the stored values stop changing, capped at fifteen cells —
and that early-out is only sound when there's exactly *one* un-repaired change in the
column. With both changes resident, the first cell's repair sweep carries both changes'
influence but truncates at the cap before reaching a fixpoint, and the second cell's
repair then early-outs on the freshly-written prefix — never reaching the stale tail.
Each rule was correct alone; phased together they left wrong bytes fifteen cells up.

The fix was to *not* phase the depth repairs: each drained cell writes its navtype and
repairs its column immediately, one at a time — exactly the sequential regime the cap's
soundness argument assumes — while the expensive part, the scratch and flag windows, stays
batched. Nothing of value was lost; the scratch was always where the waste lived, and the
depth fixpoints never double-worked laterally anyway. But it's worth being plain about
what happened: the design was reviewed, the argument read soundly, and it was **wrong in a
way only the identity test caught**. "Byte-identical or it doesn't ship" isn't a
formality; it's the thing that finds the case your reasoning didn't.

## The scoreboard

Paired interleaved A/B against the sequential baseline, per the
[house protocol](08_measure_everything.md) — one op is one drained tick of the named shape:

| Shape | before | after | Δ |
| --- | ---: | ---: | --- |
| Piston row (26 cells, one section) | 37,982 ns | 16,541 ns | **−56.5%** (1,461 → 636 ns/cell) |
| TNT sphere (57 cells, across a seam) | 117,155 ns | 38,126 ns | **−67.5%** (2,055 → 669 ns/cell) |
| Extend + retract pulse (32 events, net zero) | 47,163 ns | 84 ns | **−99.8%** — dedups to nothing |

And the guards, which is where a change like this usually dies: the single-change
patch-storm scenarios all flat (−0.2% to −2.5% — the unit patch is untouched), and the
pathfinder's cold-start and persistence guards flat (−0.6%, −0.4%). The common case — one
change, one patch — pays a queue insert and a barrier test it didn't pay before, and the
benchmarks can't see it.

Honesty about the headline, though, because the design document's arithmetic still stands:
halving a burst that was already a fraction of a percent of the tick is *spike insurance*,
not a median win. The change that mattered most in an ordinary world is the one-line
navtype compare — the piece that made a "did anything change?" signal actually mean
something again. The rest is armor for the tick that ships a hundred TNT.
