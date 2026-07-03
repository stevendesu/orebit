The [measurement chapter](measure_everything.md) ended with a scoreboard: five
convincing ideas, one survivor. This chapter is the next campaign under the same
protocol — an overnight run of five pre-registered experiments against the profiler's
two biggest numbers. Same shape of story: the idea aimed at the *bigger* number died
on a distribution with p = 0.000, an idea aimed at the smaller number paid off 30%,
and the JIT compiler — not memory, not algorithms — turned out to own the single
worst latency in the system.

## The two taxes

A CPU profile of the warm search (sampled call stacks over the full benchmark suite)
attributed the time like this:

- **The edit-diff tax — 49% of warm edit-heavy searches.** Once a candidate path
  carries one planned block edit, *every* geometry read (a hundred-plus per node)
  first probes the [edit diff](cuboid_macro_movements.md) before falling through to
  the grid. Ninety-nine percent of those probes find nothing.
- **The extraction tax — 38–45% of small edit-heavy searches.** The
  [macro-movement layer](cuboid_macro_movements.md) proves its jumps with maximal
  uniform cuboids, and the goal-premium probe re-extracts them at the start of every
  search. The proof is a bulk scan: a pillar-shaped search extracts a ~19×19 slab ×
  ~32 layers ≈ **11,500 cell reads** before expanding its first node.

Two taxes, two ideas, both design-reviewed and pre-registered with expected wins and
kill criteria before a line was written.

## Idea one: gate the edit tax — killed by p = 0.000

The edit-tax fix looked airtight. A path's planned edits trail *behind* it — you dig
where you've been, not where you're going — so at each node expansion, compute once
whether the node's read neighborhood can possibly intersect the edits' bounding box.
If not (surely the common case?), set one flag and let every read skip the diff probe
entirely: one box test per node replacing ~100 per-read gates. Expected win: the warm
edit-heavy scenario roughly halved.

Both variants — a careful 3-component envelope derived from auditing every movement's
read pattern, and a simpler single padded box — measured **flat**. Not "small win,"
flat.

A throwaway counting probe explained it, brutally: the fraction of node expansions
whose reads were disjoint from the edit box was **0.000 in every scenario**. On the
10,001-node edit-heavy flood, 9,559 expansions carried edits and *zero* were
disjoint. The intuition was simply false — the search's edit-carrying shapes are
pillar and dig fronts, and a pillaring node **stands on the block it just placed**.
Its own edit is at distance zero. The trailing-edits picture describes a bot walking
away from finished work, not a search *reasoning about* work in progress.

The single-box variant had a second, quieter lesson: it couldn't even be made sound
in Y, because Fall and the swim moves scan columns whose length is bounded by
terrain, not by a constant — so no finite vertical margin is provable, and the box
degenerated to a 4-compare horizontal test. Both variants were deleted. What
survived is the probe's number: any future gate must track per-edit or recent-edit
locality, not a whole-path bounding box. Published negative results are cheaper than
re-running the experiment next year.

## Idea two: remember the column — the depth nibbles

The extraction tax fix started from a different observation: the cuboid scans and
the deep column scans keep re-deriving *vertical* facts that barely ever change —
"how far down is the floor?", "how many identical cells sit above this one?". Those
are exactly the facts a chunk-column build pass can precompute and a block-patch can
repair locally.

The temptation was to widen the grid cell — the nav grid stores one 16-bit `short`
per cell, and 16 more bits would hold plenty. That idea failed the arithmetic before
reaching a benchmark: widening doubles the bytes of *every* grid access — including
the bulk extraction scan that is the very tax being fought, and the L1 residency the
[whole design](block_fingerprints.md) is built around — to serve fields read a
handful of times per node. Taxing a millions-of-reads path to subsidize a
dozens-of-reads path is the Hilbert-curve mistake in cache form.

So the depth data lives in a **parallel `byte[4096]`** beside each section's
`short[4096]` — the hot array stays byte-identical, and only the readers who want
depth touch the second array. One byte, two nibbles:

- **floorGap** (low nibble): distance to the first standable cell strictly below —
  0–13 exact, 14 = "proven none within the window", 15 = "no claim, fall back to
  scanning". This is Fall's landing question answered in one read instead of a
  cell-by-cell downward scan.
- **runUp** (high nibble): how many cells directly above hold the *same* block
  fingerprint — the uniformity question the cuboid extractor's vertical scans ask,
  answered as a chain: read the bottom cell, then leap the whole run, check the cell
  after it, leap again.

Both nibbles are maintained exactly: computed in one extra pass over the chunk column
at build time, and repaired on every block change by a fixpoint walk that touches at
most 15 cells up (a floor change can shorten the gaps above it) or down (a fingerprint
change can split the runs below it), crossing at most one section seam. The
correctness contract is strict — a consumer may trust a nibble only where maintenance
provably ran; anything else reads 15 and takes the old scan path — and an identity
harness verifies the accelerated searches return **bit-exact** the same paths, costs,
and edits as the scanning versions, per the standing rule that a mechanical
optimization must not change behavior.

## What it measured

Paired, interleaved A/B runs (three per arm, fresh JVM per run), on a suite that
this campaign also widened — a warm edit-heavy **FLOOD**, a cliff-descent **CLIFFS**,
a gap-bridging **BRIDGE** and a **SPIRAL** climb joined the existing scenarios, plus
a dedicated patch-storm benchmark to price the maintenance itself.

**floorGap** (consumed by Fall):

| Scenario | Δ time |
| --- | ---: |
| FLOOD (warm, edit-heavy) | **−5.1%** |
| CLIFFS (fall-heavy descent) | **−4.3%** |
| TOWER (pillar-up) | −3.4% |
| SHORT (cold-start guard) | **+1.2%** |

That +1.2% is a real, pinned-and-reproduced cost, and it was *accepted with its
mechanism understood*: the guard scenario walks along the world floor, where the old
scan terminated almost immediately on an unbuilt read — so the nibble's extra array
touch is at its worst-case relative price. The guards exist to surface exactly this
trade; this time the answer was "worth it."

**runUp** (consumed by the cuboid extractor — the headline):

| Scenario | Δ time |
| --- | ---: |
| TOWER | **−33.7%** |
| UPOVER (open) | **−35.5%** |
| UPOVER (wall) | **−30.4%** |
| MULTI (persistence guard) | **−32.3%** |

The run-chains removed **~75–80% of the entire extraction bill** on cuboid-bearing
shapes. The profiler's 38–45% attribution — and the hypothesis that cuboid
construction was the small-search startup tax — confirmed almost exactly.

**The maintenance price**, from the patch-storm benchmark (scattered edits, dig
columns, light toggles, seam-crossing edits): the baseline patch costs ~1.4–2.1 µs;
full nibble maintenance adds **+2.7% at worst** — noise against a budget of "block
updates must stay invisible."

Both nibbles shipped unconditionally after the measurement round, and a follow-up
identity run confirmed the wins survived the flag removal.

## Idea three: the 22-millisecond first search

One latency no steady-state benchmark ever showed: the *first* search after server
boot took **21.8 ms at the median** (p90 30 ms) — against ~1.3 ms for the second and
under a millisecond warm. The allocation audit said the search scratch is ~41 KB at first
touch; pre-allocating it would save *microseconds*. The 22 ms is the JVM itself:
classloading plus interpreted first execution of the whole pathfinder, on a live
player's tick.

Only executing the real code warms it, so that became the fix: at server start —
before any player can join — the pathfinder runs a few hundred synthetic searches
over a private in-memory fixture (~50 KB of hand-built sections; production shapes:
short walks, budget floods, wall climbs, water crossings, chosen for *branch
coverage* rather than speed). Rounds repeat until search times plateau, capped at
1.5 s of wall clock. Measured on a fresh-JVM harness, ten runs per arm:

| | first search p50 | p90 | boot cost |
| --- | ---: | ---: | ---: |
| without warm-up | 21.8 ms | 30.0 ms | — |
| with warm-up | **0.67 ms** | 0.81 ms | 475 ms median |

A **32× improvement** in the one latency a player actually feels first, honestly
measured: the timed first search is a terrain shape the warm-up never literally ran.
It ships on by default (`pathing.warmup`, budget `pathing.warmupBudgetMs`).

The postscript keeps the chapter honest. Riding along was a one-liner that
pre-sized the search's node table to its measured high-water — pure
[favor-CPU-over-RAM](measure_everything.md), obviously free. The cold-start guard
caught it at **+4–7%**: the table's reset pass wipes its *capacity*, not its live
entries, so every small search paid 28 KB of pointless clearing. Reverted the same
night. Six experiments, three survivors, and once again the guard scenarios — not
the reasoning — decided which was which.
