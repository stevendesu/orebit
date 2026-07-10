The [last chapter](08_measure_everything.md) was about trusting your instruments over
your own reasoning. This one puts that to work from the very first step. Before changing
a line, we pointed a profiler at the search — a CPU profile that samples the running
call stacks across the whole benchmark suite — and asked it a single question: where
does the time actually go?

## Where the time went: expanding nodes, scanning columns

The answer was node **expansion**. A\* spends its life popping a node and *expanding*
it — generating every move that leads out of it, checking each candidate's geometry —
and that per-node work, times thousands of nodes, is most of a search. So we looked
harder at what an expansion does that's expensive, and two operations stood out, both of
them **vertical column scans**:

- **Falls scan *down*.** To price a Fall — walk off an edge and drop — the move has to
  find where the bot *lands*, which means probing straight down the column, cell by cell,
  until it hits a floor. A deep drop is a long scan, and it runs every time a standing
  node so much as considers falling.
- **Cuboids scan *up*.** The [macro-movement layer](07_cuboid_macro_movements.md) proves
  its jumps with maximal uniform cuboids, and building one means scanning outward and
  *upward* to learn how far the uniform run reaches. It's a bulk read: a pillar-shaped
  search extracts a ~19×19 slab × ~32 layers ≈ **11,500 cell reads** before it expands
  its first node — the profile pinned that extraction alone at **38–45%** of a small
  edit-heavy search.

Two moves, two directions, one shape of waste: re-deriving *vertical* facts that barely
ever change — "how far down is the floor?", "how many identical cells sit above this
one?" — over and over, on cells the search revisits again and again. Those are exactly
the facts a chunk-column build pass can precompute once and a block-patch can repair
locally.

## Remember the column: the depth nibbles

The temptation was to widen the grid cell — the nav grid stores one 16-bit `short`
per cell, and 16 more bits would hold plenty. That idea failed the arithmetic before
reaching a benchmark: widening doubles the bytes of *every* grid access — including
the bulk extraction scan that is the very tax being fought, and the L1 residency the
[whole design](03_block_fingerprints.md) is built around — to serve fields read a
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

That size isn't luck; the nibble changes the *complexity* of building a box, not just
its constant. Extracting a maximal cuboid is three nested expansions: grow it along X,
then for every cell of that row grow along Z, then for every cell of the resulting
`X×Z` rectangle grow along Y. The trap is that last step — it runs once per *cell of the
rectangle*, not once per row, so the wider the box gets the more columns that vertical
scan has to climb. A box `w` wide, `d` deep and `h` tall costs on the order of `w·d·h`
reads: an **O(n³)** scan that only gets worse the taller the pillar. The runUp nibble
deletes the vertical loop wholesale — instead of climbing each column cell by cell, one
read of a column's bottom cell says how far its identical run reaches, and the box's
height falls out of a single comparison per column. Three nested expansions collapse to
two, roughly **O(n³) → O(n²)** — which is why the saving is a *fraction of the whole
bill* rather than a few percent off it.

**The maintenance price**, from the patch-storm benchmark (scattered edits, dig
columns, light toggles, seam-crossing edits): the baseline patch costs ~1.4–2.1 µs;
full nibble maintenance adds **+2.7% at worst** — noise against a budget of "block
updates must stay invisible."

Both nibbles shipped unconditionally after the measurement round, and a follow-up
identity run confirmed the wins survived the flag removal.

## A postscript the guard caught

One more idea rode along in the same round of measurements: a one-liner that pre-sized the
search's node table to its measured high-water mark — pure [favor-CPU-over-RAM](08_measure_everything.md),
obviously free. The cold-start guard caught it at **+4–7%**: the table's reset pass wipes
its *capacity*, not its live entries, so every small search paid 28 KB of pointless
clearing. Reverted on the spot. Once again the guard scenarios — not the reasoning —
decided which changes lived.
