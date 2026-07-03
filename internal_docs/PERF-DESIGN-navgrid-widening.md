# PERF DESIGN REVIEW — NavGrid cell widening (curated): depth-to-floor-below nibble + cardinal neighbor class

> **STATUS (2026-07-03): ADOPTED (headline 1+2) — unconditional in the tree as of the E3/E4 cleanup.**
> Built exactly as headlines 1–2 prescribe: parallel `byte[4096]` per section (`TraversalGrid.depth`;
> the hot `short[]` untouched), chunk-column-exact (`NavSectionBuilder.computeDepth` pass 3), upward
> floorGap / downward runUp fixpoint patch propagation across ≤1 vertical seam. Measured (E3): FLOOD
> −5.1% / CLIFFS −4.3% / TOWER −3.4%, SHORT +1.2% pinned-real (accepted; mechanism = the y==minY
> nearly-free legacy termination — the doc's predicted worst case). The `PatchStormBenchmark` this doc
> spec'd was built and gates at worst +2.7% vs no-maintenance. **Deviations from the design as-written:**
> (a) the byte's high nibble was given to the E4 runUp field (`PERF-DESIGN-runup-nibble.md`) rather than
> reserved; (b) encoding shipped as 0–13 exact / 14 = proven-none-in-window / 15 = UNKNOWN→legacy
> (single-section grids stay UNKNOWN and legacy-scan); (c) all four flags were removed after measurement
> — maintenance + consumers are unconditional. **Headline 3 (cardinal neighbor class, phase 2) is DEAD
> as-designed**: it required the edit-bbox gate, which was refuted at p = 0.000 (design doc deleted
> post-refutation; rationale recorded in `PERF-RESULTS-2026-07-03.md` §E1/E2). Results:
> `PERF-RESULTS-2026-07-03.md` §E3 + ADDENDUM.

**Original status:** DESIGN ONLY — for owner review before any implementation (standing rule 1).
**Subject:** HANDOFF perf item (d): "NavGrid 16→32 widening — curated ONLY: depth-to-floor-below
nibble first (kills Fall's 16-deep scans), 4×2-bit cardinal neighbor class as edits-bbox-gated
prefilter second; full 'all decisions from one cell' needs 100+ bits and is blind to PathEdits —
rejected. Needs a NEW patch-storm bench (invalidation cost) before keep."

**Verdict up front (3 headlines the rest of the doc defends):**

1. **The depth-below nibble is worth building — but NOT by widening `short[]` → `int[]`.** Store it
   in a **parallel `byte[4096]` per section** instead. Widening doubles the bytes of *every* grid
   access — including the cuboid extractor's bulk `short[]` scan, which is **38–46% of small-search
   CPU** — to serve a field that is read **≤ 4–8 times per pop**. That trade is exactly the
   Hilbert-curve mistake in cache form: taxing the millions-of-reads path to subsidize a
   dozens-of-reads path. The parallel array keeps the hot `short[]` byte-for-byte identical (the
   extractor, `packedAt`, and L1 residency untouched) and costs the nibble's few consumers one extra
   L1 line. §5 has the math.
2. **The nibble must be chunk-column-exact (vertical-seam-crossing), not section-local-saturating.**
   Section-local saturation destroys the one case the nibble exists for — the deep open-air scan —
   because a "no floor within 14" answer needs a 14-cell window and only the *top row* of a section
   has that section-locally. The column build already does two passes over the whole chunk column
   (s42 overscan), and vertical neighbors are same-chunk by construction, so column-exact costs no
   new ordering machinery. It does add ONE new thing: **upward** patch propagation across ≤ 1
   vertical seam (today's `patchCell` propagates only downward). §6.
3. **The cardinal neighbor class is phase 2, conditional, and probably not this arc.** It is
   lateral (cross-CHUNK seams — the exact problem s42 deferred by design), edits-blind (needs the
   per-pop edit-bbox gate, perf item (a), landed first), and its target reads (Traverse/Diagonal
   prologues) are cheap single-cell reads, not 15-deep scans. Reserve the bits, build it only after
   (a) lands and only interior-scoped. §8.

---

## 1. Current state (what the code actually does)

### 1.1 The cell

`TraversalGrid` (`worldmodel/pathing/TraversalGrid.java:43`): `short[4096]` per 16³ section,
`[15..10]` = 6 `NavFlags` bits (RISKY_EDIT, CLEARABLE_HAZARD, 2-bit HEADROOM, PLACEABLE_NEIGHBOR,
SLOW_TRANSIT), `[9..0]` = navtype index (~590 used of 1024). 8 KB/section. The file's own Javadoc
(line 29) says "Keeping the cell a `short` (not widening to `int`) is itself the speed win — cache
residency + load time." This review is the formal challenge to that sentence; the answer below is
"the sentence stays true, and the nibble ships anyway — beside the short, not inside it."

### 1.2 The read paths that matter

- `NavGridView.packedAt` (`NavGridView.java:146`): section resolve (single-slot chunk cache →
  open-addressed 512-slot cache → CHM) + one `short[]` index. ~4–5 ns warm.
- `NavGridView.sectionRawAt` (`NavGridView.java:199`): the **bulk-scan seam** — `CuboidExtractor`
  resolves a section once and walks the raw `short[]` sequentially. Per PERF-PROFILE-2026-07 §3
  this chain is **~45% of TOWER and ~38% of UPOVER_WALL** search CPU (mostly the once-per-search
  `GoalForcedCost.probe`). *This is the path widening would tax hardest.*
- `MovementContext.descriptorOf/At` (`MovementContext.java:299,334`): the per-cell edits diff
  (`PathEdits.kindAt`) rides in front of the grid read — **49% of warm edit-heavy search CPU**
  (the S3 shape). Perf item (a), the per-pop edit-bbox gate, attacks that; this design must compose
  with it, not race it.

### 1.3 The scans the nibble replaces (counted from the code)

**`Fall.candidates` (`movements/Fall.java:66–123`)** — per standing pop, per cardinal that passes
the step-off headroom check:

```java
for (int fy = y - 2; fy >= y - maxDrop; fy--) {   // maxDrop default 16 → up to 15 iterations
    int packed = ctx.packedAt(nx, fy, nz);        // 1 grid read per cell
    if (packed == UNBUILT) break;
    if (!ctx.standable(ctx.descriptorOf(...))) continue;  // + kindAt gate when edits present
    ... verify column fy+1..y (descriptorAt each) ...     // pricing reads — NOT removed by this design
    break;
}
```

Per-pop read cost of the *scan* (verification reads excluded — they carry hazard pricing and stay):

| Node shape | today | with nibble |
|---|---|---|
| **Open-air node** (flood cone / TOWER pathology — no floor within window, all 4 cardinals open) | 4 × 15 = **60** `packedAt` (+ each paying the `kindAt` gate on edit-bearing searches via `descriptorOf`) | 4 × 1 nibble read + 4 × 1 legacy tail read (depth 16, past the 14-cell window) = **8** ⇒ **−52 reads/pop (−87%)** |
| **Flat-ground node** (ordinary terrain) | 4 × (1 scan read + 1 failed-verify `descriptorAt`) = **8** | 4 nibble reads (floorGap = 0 ⇒ "never a Fall", proof §7.2) = **0 grid reads** ⇒ −8/pop |
| **Cliff edge, landing at depth D** | (D−1) scan reads + D verify reads per emitting cardinal | 0 scan + D verify (verify unchanged) |
| Immune bot (`maxFallDistance = 4096`) over deep air | scan runs until UNBUILT/terrain — can be **hundreds** of reads/pop | nibble covers the first 14; legacy tail beyond. Still bounded-large savings; the deep-void case keeps its tail scan. |

**Other column scanners, audited and NOT converted in v1:**

- `Parkour` falling detection (`Parkour.java:596–624`): down-scan per gap column, `dr = 1..capsDrop`
  — but `capsDrop = min(fallMax.length−1, maxFall)` = **1** at defaults (3 aggressive). 0–2 reads
  saved per column; not worth widening the byte-identity surface in v1. Natural second consumer
  later (same field, same gate).
- `Pillar`/`MineDown` (`Pillar.java:84`, `MineDown.java:69`): single-cell probes; their column work
  is the cuboid macro (bulk `short[]` scan) — the nibble is irrelevant there.
- `Climb`: walks actual rungs (needs per-cell climbability, not floor distance). Not a consumer.
- `Swim`/`Traverse`/`Ascend`/`Descend`/`Diagonal`: 1–5 cell reads each, no deep columns.

So the honest statement is: **this field is a Fall accelerator**, and Fall's scan volume is
concentrated in exactly the air-heavy regime the performance model calls the canonical pathology
(the pillar-cone flood: ~99% of expansions in open air, every cardinal an open column).

### 1.4 How big is that in time? (calibration, not hope)

JFR buckets say "grid reads outside extraction" are only 2.5–5.7% — but that bucket under-counts
inlined reads (they attribute to movement frames), and the honest calibration is the s42 Parkour
A/B: the **eager prism scan (~36–48 extra reads/pop) measured +17–27% total search time on
air-heavy scenarios**, and its lazy replacement's ~9 residual reads/direction still cost +4–8% on
OPEN/SHORT. That is ~0.4–0.6% of air-heavy search time per read-per-pop — because each "read" is
really read + `standable` derivation + (when edits) the `kindAt` gate + loop control.

Applying that ruler to Fall's −50-ish reads/pop in air-heavy shapes: **expected −10…−25% on
flood/air-heavy scenarios pre-(a)**, shrinking to perhaps **−5…−15% post-(a)** (once the edit-bbox
gate removes the `kindAt` component of each saved read, the residue is the ~4–5 ns raw read + loop
work). OPEN (flat walk): low single digits (the −8/pop flat-ground row). SHORT/MULTI: expected
**neutral** — the nibble adds zero per-search setup (it is built at chunk-build time, not view
construction; `NavGridView` is unchanged in size and init). These are the numbers the A/B must
reproduce; the keep bar is the standing ≥3% targeted win with no scenario regressing beyond noise.

**Ordering note:** HANDOFF lists (a) edit-bbox gate → (b) warm-up → (c) cuboid persistence → (d)
this. That order stands. (d)'s win estimate above is smaller *after* (a), and that is fine — bench
(d) against the post-(a) baseline so the measured delta is the real marginal value.

---

## 2. Field semantics (exact, so byte-identity is arguable)

**`floorGap(x,y,z)`** — the number of consecutive **non-standable** cells strictly below cell
`(x,y,z)` in its world column, saturated:

| value | meaning |
|---|---|
| `0..13` | exact: the first standable cell strictly below is at `y − floorGap − 1` (so `0` = the cell immediately below is standable) |
| `14` (`SAT`) | **proven**: no standable cell in `y−1 .. y−14` (the window is exhausted, honestly) |
| `15` (`UNKNOWN`) | no claim — maintenance disabled, single-section build with no column context, or any producer that cannot see the column. Readers must legacy-scan. |

"Standable" is **exactly** `NavBlock.isStandable(descriptor(navtype))` — the identical predicate
`Fall`'s scan applies (`MovementContext.standable`), computed from the same resident navtypes. No
new classification, no caps dependence, no edits awareness (edits are the *reader's* problem, §7.1).

Recurrence (what makes build and patch O(1)/cell):

```
floorGap(y) = standable(y−1) ? 0 : min(floorGap(y−1) + 1, 14)
seed at world bottom: floorGap(minY) = 14      // nothing below minY is standable — and the legacy
                                               // tail scan below minY reads UNBUILT and breaks,
                                               // reproducing today's exact behaviour (§7.3)
```

Why the window is 14 and not 15: value 15 is reserved for UNKNOWN so a saturated-but-proven answer
("no floor within the window") is distinguishable from "no claim". Proven-none lets Fall **skip 14
cells and resume the legacy scan at `y−15`** — the flood win; UNKNOWN falls back to the full legacy
scan — the safety valve. Collapsing them (as a section-local design would) forfeits the flood win.

### 2.1 Bit budget (the full 16-bit plan, for the record)

Whether or not the storage is a widened int (§5 argues not), the *logical* budget the owner asked
for:

| bits | field | phase |
|---|---|---|
| 4 | `floorGap` nibble (this design) | 1 |
| 8 | 4 × 2-bit cardinal neighbor class (§8) | 2, conditional on (a) + lateral machinery |
| 4 | spare — reserved (candidates: 2-bit "climb-run length class" for Climb, a soft-landing bit for Fall's water/hay window, a second HEADROOM bit) | — |

Rejected (re-affirming HANDOFF): the full "all decisions from one cell" — every movement's
candidate facts precomputed per cell — needs 100+ bits, its inverse invalidation footprint is a
~7×7×9 neighborhood per block change, and it is blind to `PathEdits`, i.e. useless precisely on the
edit-heavy searches where per-read cost peaks (the 49% `kindAt` regime). It also adds per-access
decode math to the hottest read — the Hilbert lesson verbatim.

---

## 3. Storage: widen `short[]`→`int[]` vs parallel `byte[4096]` — THE core decision

### 3.1 What widening costs (the honest cache assessment the prompt demands)

- **Bulk scans double.** `CuboidExtractor` walks `sectionRawAt` rows sequentially; `short[]` puts a
  16-cell Z-row in 32 B (half a cache line), a 256-cell X-Z layer in 8 lines. `int[]` doubles both.
  Extraction is 38–46% of TOWER/UPOVER_WALL — the biggest single block of small-search CPU — and it
  is **pure memory streaming**, the workload most sensitive to bytes/cell. Predicted: a direct,
  measurable regression on TOWER/UPOVER/SHORT, the scenarios the suite guards hardest.
- **L1 residency halves.** A section grid goes 8 KB → 16 KB (¼ → ½ of a 32 KB L1D). A search
  touches several sections + the ~4.6 KB descriptor table + `Nodes` SoA + the open sets; the grid's
  L1 share doubling evicts the descriptor table the per-cell `standable`/`passable` derivations hit
  on *every* read. The performance model's whole premise ("the data layer is hyper-optimized for
  memory; the system sits at the CPU↔memory balance point") says do not do this casually.
- **Single random reads are ~free either way.** One cell = one line touched regardless of element
  width. The cost of widening is *density* (scans, residency), not per-access latency.
- **Pooled/live memory doubles** (§6.4) — affordable per favor-CPU-over-RAM, so RAM is *not* the
  objection. Cache is.

### 3.2 What the parallel `byte[4096]` costs

- One extra 4 KB array per section (+50% section memory, §6.4). The hot `short[]` is untouched:
  extractor, `packedAt`, flags reads, L1 behaviour **byte-for-byte identical** — the no-regression
  half of the A/B is structurally protected instead of hoped for.
- Consumers pay one extra array read on an already-resolved section. `Fall` reads the nibble for
  the 4 step-off cells at the node's own y; linear index `(y<<8)|(z<<4)|x` puts `x±1` one byte
  apart and `z±1` 16 bytes apart, so all four nibble reads land in 1–2 cache lines of the byte
  array. The section resolve is shared with the flags read the cardinal already does
  (`Fall.java:81` — upgraded from `flagsAt` to `packedAt` + `depthAt` through the same single-slot
  chunk cache; the second call is a cache-key compare + array index).
- Asymmetry check: the nibble is read ≤ 4–8 times/pop (Fall cardinals; later Parkour), vs ~100+
  packed reads/pop for everything else. Co-locating a rarely-read field with the constantly-read
  short is the wrong locality bet even before the extractor argument.

### 3.3 Recommendation

**Parallel `byte[4096]` in `TraversalGrid` (`byte[] depth`), exposed as
`NavSection.getDepth(x,y,z)` → `NavGridView.depthAt(x,y,z)` → `MovementContext.floorGapAt`.**
Widening to `int[]` is REJECTED for this phase — not on RAM (favor-CPU-over-RAM tolerates it) but
on cache density, with the extractor as the named, measured victim-in-waiting. If phase 2 (8 more
bits) ever proves in, revisit: either a second parallel array (`short[]` — nibble + class together,
still leaving the hot short untouched) or widening *measured against the then-current baseline*.
This deviates from the owner's 16→32 framing deliberately; the logical design (curated precomputed
fields) is preserved, only the physical layout differs.

---

## 4. Read-path mechanics (Fall, branch-shaped for the predictor)

Composed gate: the nibble is trusted only when (i) it is not UNKNOWN and (ii) **no path edit can
intersect the scanned column**. Pre-(a), condition (ii) is a per-cardinal 6-compare AABB test
against `PathEdits`' existing bbox (`PathEdits.java:87–92`); post-(a), it is the per-pop
`editsRelevant` boolean — **provided (a)'s per-pop bbox is extended downward by `maxDrop`**
(Fall's reach is `y − maxDrop`, far below the generic ±5 movement reach; one constant in the gate's
bbox construction, decided jointly with (a)'s design). Either form is over-conservative only —
a false "relevant" costs a legacy scan, never a wrong answer.

```java
int p = ctx.packedAt(nx, y, nz);                     // was flagsAt — same resolve, full slot
if (p == UNBUILT) continue;
int flags = MovementContext.flagsOf(p);
... step-off headroom checks unchanged (Fall.java:82-86) ...

int fg = ctx.floorGapAt(nx, y, nz);                  // parallel byte[]; 15 = UNKNOWN
int scanFrom = y - 2;                                // legacy default
if (fg != UNKNOWN && editsDisjointFromColumn(nx, y - maxDrop, y, nz)) {
    if (fg == 0) continue;                           // floor at y−1 ⇒ provably never a Fall (§7.2)
    if (fg < SAT) {                                  // exact landing: fy = y − 1 − fg
        int fy = y - 1 - fg;
        if (fy < y - maxDrop) continue;              // beyond the cap — reject, as the scan would
        ... verification loop fy+1..y UNCHANGED (passable + cellTransitCost pricing) ...
        continue;                                    // this cardinal done — zero scan reads
    }
    scanFrom = y - (SAT + 1);                        // proven none in y−2..y−14 ⇒ resume at y−15
}
... legacy scan loop from scanFrom (unchanged code) ...
```

Branch shape: the common cases are "fg exact, verify" (terrain) and "fg == SAT, 1–2 tail reads"
(open air) — both a single well-predicted compare chain replacing a 15-iteration data-dependent
loop. No new branch is added to any *per-read* path (the performance model's branch rule): the
nibble read happens once per cardinal, not per cell. The verification loop — which carries all
hazard/slow pricing — is untouched, so all costs and all emitted candidates are computed by the
same code as today.

Kill switch: `TraversalGrid.DEPTH_MAINTENANCE` (static boolean, the `OFFSET_FALLBACK` pattern).
Off ⇒ builders fill `UNKNOWN` ⇒ every reader legacy-scans ⇒ bit-for-bit today's behaviour. This is
the A/B lever and the field escape hatch.

---

## 5. Byte-identity argument (results must be identical, not "equivalent")

The nibble is a **memoization of a pure function the scan already computes**: "index of the first
standable cell strictly below, from resident navtypes." Same predicate (`NavBlock.isStandable` on
the same descriptor table), same data (resident navtypes), so when it is *fresh* and *edits are
provably absent from the column*, `fy_nibble ≡ fy_scan`. The remaining cases:

1. **Path edits in the column** (a placed block is standable cobble; a broken cell is air): the
   gate routes to the legacy scan, which consults `kindAt` per cell exactly as today. Sound because
   the gate over-approximates (bbox ⊇ edits).
2. **UNBUILT below**: within a built chunk the whole 24-section column exists
   (`ChunkNavBuilder.buildAllSections` fills every slot), so the scan's UNBUILT break fires only
   below `minY` — and the seed `floorGap(minY)=14` + legacy tail reproduces it: the tail scan reads
   below `minY`, gets UNBUILT, breaks, no landing. Identical. Laterally, an unbuilt neighbor chunk
   is rejected before any column work (the step-off `packedAt` returns UNBUILT).
3. **`fg == 0` ⇒ skip**: a standable cell at `y−1` has collision, so it fails `passable` in the
   verification range `fy+1..y ∋ y−1` of ANY deeper landing — today's scan finds a landing and
   always rejects it in verify. Skipping emits the same nothing. (Fences at `y−1` are
   non-standable, so they don't set `fg=0`; the scan skips them too and verify rejects both ways.)
4. **Staleness mid-search**: none possible today — searches, block changes, and `NavGridUpdater`
   all run on the tick thread; `NavGridView`'s own contract already states "the store doesn't
   mutate mid-search." The nibble inherits exactly the grid's freshness class (patched in the same
   `patchCell` call, §6.2), adding no new staleness window. When background pathfinding lands
   (menu item 9), the nibble is in the same boat as every other grid read — a shared, already-open
   design question, not a new one.
5. **Candidate order / f-values**: Fall emits per-cardinal in the same `CARDINALS` order, one
   candidate per cardinal, costs computed by the unchanged verification loop. Expansion order and
   returned paths are therefore identical. Verification: assert equal `LAST_EXPANSIONS` + identical
   waypoint lists across the A/B on every scenario, plus a `/bot trace` E-line byte-diff on one
   edit-heavy and one open-air search, plus a property test (random columns, Fall candidates with
   maintenance on vs off).

---

## 6. Write path: build, patch, invalidation bounds, memory

### 6.1 Build (chunk column)

Add a **column sweep** to `ChunkNavBuilder.buildAllSections` (a pass 3, or folded into pass 2's
loop): per chunk, for each of 256 (x,z) columns, walk y ascending from `minY` with the §2
recurrence — one `short[]` navtype read + one descriptor-table read + compare + `byte` store per
cell, carrying a 256-byte per-column counter scratch (ThreadLocal) across sections. Cost:
**98,304 cells × ~4–6 ops ≈ low tens of µs per chunk**, against an existing pass-2 bill of roughly
250k+ ops per non-air section (4096 × `NavFlags.compute` at ~15–25 reads each). Ratio: **≈ +5–10%
on a typical terrain chunk build; the all-air column (ocean sky) is the worst *relative* case**
because those sections today take the uniform-air bypass — but its absolute cost is the same tens
of µs, at chunk-load frequency. Note honestly: the nibble has **no uniform-air fast path** — an air
section's depths vary per cell (they count down to the terrain below the section), so the sweep
always runs. The flags bypass itself is untouched.

Single-section producers without column context (`classifyInto`, headless tests) fill
`UNKNOWN` — readers legacy-scan, correctness by fallback (this mirrors the s42
"single-section producers remain air-optimistic; the guard stays" pattern,
`MovementContext.headroomProves`' doc).

### 6.2 Patch (`patchCell`) — the new upward propagation

A block change at section-local `(lx,ly,lz)` can change `standable` there, which dirties the
`floorGap` of cells **above** it in the same world column. Inverse footprint, exactly bounded:

- Dirty set = `(lx, ly+1 .. ly+k, lz)` with `k ≤ 14` (the window), and tighter: propagation stops
  at the first cell whose recomputed value equals its stored value (fixpoint — equivalently, at the
  first standable cell above the change, whose own floorGap and everything above it are unaffected).
- Implementation: after the navtype write, one ascending sweep of ≤ 14 cells applying the §2
  recurrence (seeded by one read of the cell below the change), early-out on fixpoint. **≈ 30–60
  ops** against a `patchCell` baseline of one-to-two `fillScratch`es (≈ 4.8k descriptor reads each)
  + 45–90 `NavFlags.compute` calls — **≈ +0.5–1% per patch**, i.e. expected noise.
- **Seams:** the sweep crosses at most ONE vertical seam upward (14 cells from `ly=15` reaches rows
  0–13 of the section above; from any `ly`, self + above covers it). The above section is
  same-chunk and `NavGridUpdater` already resolves it (`NavGridUpdater.java:61`) — but note the
  invariant change: today "an ly=15 change can't change the above section's stored data"
  (`NavSectionBuilder.patchCell` doc). The nibble breaks that: **`patchCell` gains a write into
  `above`**, the mirror of its existing write into `below`. Total sections touchable per patch:
  3 (self + below for flags, above for depth). No lateral seam, no cross-chunk write — the nibble
  is strictly column-local, which is precisely why it is the right first field (it rides the s42
  vertical machinery and dodges the deferred lateral-ordering problem entirely).
- The below-seam flags pass (`ly < 3`) is orthogonal and unchanged.

### 6.3 Patch-storm concern + the NEW bench (the HANDOFF precondition for keep)

`patchCell` runs on **every server-side block change in a tracked chunk** — not just the bot's
edits: crop growth, fluid spread, pistons, other players, TNT. The nibble's marginal cost per patch
is estimated noise (§6.2), but the baseline ns/patch has *never been measured*, and "estimated
noise" is not the protocol. Spec:

**`PatchStormBenchmark`** (JMH, mc-1.21 era harness, `-Pbench=PatchStormBenchmark`):
- **Fixture:** synthetic chunk columns built the *column* way (classifyNavtypes per section →
  computeFlags with overscan → depth sweep) — realistic strata: bedrock floor, ~40 solid rows,
  caves (air pockets), surface, air above. NOT `buildFlatChunks` as-is — it uses single-section
  `classifyInto` (`PathfinderBenchmark.java:237`), which would leave depth UNKNOWN and bench
  nothing; the fixture builder grows a column-form variant (also needed by the A/B search bench,
  §7 measurement plan).
- **Op:** `NavSectionBuilder.patchCell(section, above, below, lx, ly, lz, newState)` — exactly
  `NavGridUpdater`'s call shape — over a pre-generated cycle of (cell, state) pairs (no per-op
  allocation, states alternating stone/air so every patch actually toggles standability, the
  nibble's worst case).
- **Scenarios (`@Param`):**
  `SCATTER` — uniform-random cells across the column (cache-miss upper bound);
  `DIG` — a descending shaft sequence (the bot's own `applyEdits` storm; clustered,
  seam-crossing every 16 cells);
  `TOGGLE` — one cell alternating (fixed-overhead isolation, branch-predictor best case);
  `SEAM` — cells restricted to `ly ∈ {0,1,2,13,14,15}` (maximum cross-section work: below-seam
  flags pass + above-seam depth pass together).
- **Metric:** ns/patch, paired interleaved A/B (`DEPTH_MAINTENANCE` on/off).
  **Keep gate:** nibble maintenance ≤ +10% ns/patch on every scenario (expected: ≤ +2%), and the
  measured absolute ns/patch × a hostile storm rate (e.g. 10k changes/s — a big TNT event) stays
  comfortably sub-millisecond-per-tick. The bench also, for the first time, puts a number on
  today's patch cost — useful independent of this design (it prices the "window the scratch
  rebuild later" TODO in `patchCell`'s doc).

### 6.4 Memory math

Per section: today `short[4096]` = 8 KB (+ object headers). Nibble: +`byte[4096]` = **+4 KB
(+50%)**. Widening (rejected): +8 KB (+100%).

Live count: 24 sections/chunk (384-tall worlds; shorter pre-1.18 worlds fewer). At view distance
10 ⇒ ~441 tracked chunks ⇒ ~10.6k sections ⇒ grids ≈ **83 MB today; +41 MB nibble; +83 MB if
widened** — per level with built nav, scaling with tracked chunks (players × view distance).
Favor-CPU-over-RAM: +41 MB is acceptable without ceremony; note it in the config docs anyway.
`NavSectionPool` (`NavSectionPool.java`): `POOL_SIZE=256` is only the deque's *initial capacity* —
`recycle` pushes unbounded, so pooled retention already floats with the high-water chunk count;
the +4 KB rides each pooled instance. No pool change needed; flag the unbounded pool as a
pre-existing observation.

---

## 7. Measurement plan (protocol per CLAUDE.md "Performance model")

1. **Design review (this doc) → owner sign-off before code.**
2. **Byte-identity first**: unit property test (Fall candidates, maintenance on/off, random
   columns incl. fences, edits-in-column, world-bottom, seam-straddling floors); full 119-test
   suite; `/bot trace` E-line byte-diff on an open-air and an edit-heavy search;
   `LAST_EXPANSIONS` equality asserted inside the A/B harness.
3. **Search benching**: paired interleaved A/B (`DEPTH_MAINTENANCE` on/off) on the FULL suite —
   TOWER, OPEN, UPOVER_OPEN, UPOVER_WALL, **SHORT** (setup guard — expected exactly neutral: no
   per-search work added), **MULTI** (cross-search reuse guard) — plus the 15-s WARM FLOOD probe
   loop (the S3 shape, where Fall's open-air scans actually live; no standard scenario reproduces
   it). Fixtures upgraded to column-form builds (§6.3) or the nibble never engages. Suspicious
   single-scenario deltas re-checked with pinned `-Pscenario=` fresh-JVM pairs (forks=0 JIT
   pollution rule). If no scenario has meaningful Fall down-scan volume (TOWER macro-collapses to
   ~28 pops), add a `CLIFFS` scenario — a terraced/void-edge fixture where standing nodes border
   deep open columns — as the *targeted* scenario; a change like this must not be judged only on
   scenarios structurally blind to it.
4. **`PatchStormBenchmark`** (§6.3) — the HANDOFF precondition. Runs in the same session.
5. **Keep/revert**: keep only on a reproducible **≥3% win on the targeted scenarios**
   (WARM FLOOD / CLIFFS / UPOVER_OPEN) with no scenario — SHORT and MULTI included — regressing
   beyond noise, and the patch-storm gate green. Revert without sentiment otherwise; the kill
   switch makes revert a one-line default flip while keeping the code for a later re-measure
   post-(a) if it lands pre-(a), or vice versa.
6. **In-game smoke** (26.2): `/bot come` across a cliffed area + a pillar flood; confirm identical
   routes vs a maintenance-off run and no chunk-load frame hitching (build-cost check).

---

## 8. Phase 2 — 4×2-bit cardinal neighbor class (design sketch; deliberately deferred)

**Intent:** per cardinal, a 2-bit class of the neighbor column at node level —
`0 UNKNOWN` (cross-seam/unbuilt/complex), `1 FLAT_WALK` (standable same-level floor, HEADROOM ≥
WALK, no hazard/slow prefilter bits), `2 BLOCKED`, `3 OPEN` (passable at node level — gap/drop).
Consumers: `Traverse`/`Diagonal` prologues (skip the neighbor `packedAt`+derivations when
`FLAT_WALK`/`BLOCKED`), Parkour's column-1 read, Fall's step-off reject. Saves ~1–2 reads per
cardinal per pop — real but an order smaller than the nibble's flood case, and the reads it removes
are the cheapest kind (single cells, cache-hot).

**Why it must wait:**
- **Edits-blind by construction** — a neighbor class cannot see the path diff, so it is usable only
  under (a)'s per-pop `editsRelevant == false`; without (a) landed there is no gate to compose with
  (per-read bbox tests would re-add the cost being removed).
- **Lateral invalidation crosses CHUNKS** — a change at a chunk-edge cell dirties the class bits of
  cells in the *neighboring chunk*, and at build time that neighbor may not exist yet, requiring
  the seam re-patch-on-neighbor-load machinery that s42 explicitly deferred (HANDOFF item 7). The
  honest v2 scoping is **interior-only**: perimeter columns (60/256 ≈ 23% of a section's footprint)
  carry permanent `UNKNOWN` and fall back to reads, so no cross-chunk write and no build-ordering
  problem — at the price of a quarter of cells not benefiting.
- **Storage**: 8 bits don't fit beside the nibble in a byte. Options when the time comes: grow the
  parallel array to `short[]` (nibble + classes together, hot `short[]` still untouched) or widen
  the main cell — decided then, against measured post-(a)/(d) baselines, with the §3 extractor
  argument re-run.

Decision requested from owner: bless the nibble (phase 1) now; park phase 2 until (a) has landed
and its measured residue tells us whether the remaining per-read cost even justifies it.

---

## 9. Alternatives considered

| Alternative | Verdict |
|---|---|
| Widen `short[4096]` → `int[4096]`, nibble in bits 16–19 | REJECTED phase 1 — doubles extractor scan bytes (38–46% of small-search CPU) and halves grid L1 density to serve ≤8 reads/pop (§3). Re-evaluable at phase 2 with data. |
| Parallel `byte[4096]` | **RECOMMENDED** (§3.3). |
| Section-local saturating nibble (no seam work) | REJECTED — cells needing window depth `d` are exact only when `(y&15) ≥ d+1`; the flood case (`d = 14`) would work on one row in sixteen, killing the headline win; and the "truncated vs proven-none" conflation forces full legacy scans exactly where the field matters (§ verdict 2). |
| Downward overscan copy (mirror s42's scratch rows) instead of the running-counter sweep | REJECTED — a 14-row scratch copy per build/patch costs ~3.5k extra reads where the recurrence costs ~1 op/cell; the recurrence is also what makes the patch fixpoint early-out possible. |
| Fold the answer into HEADROOM-style flags (e.g. a "no floor within N" bit) | REJECTED — a boolean can't carry the landing depth, so found-landing cardinals keep their scans; the 4-bit exact depth is the minimum useful width. |
| Full "all decisions from one cell" | REJECTED (HANDOFF, re-affirmed §2.1). |
| Lazy per-search column cache instead of stored bits | REJECTED — per-search setup is itself hot (SHORT guard exists because of exactly this); a store-side field amortizes across all searches and all bots and is invalidated for ~free at the existing patch seam. |

---

## 10. Risks (named, with mitigations)

| Risk | Exposure | Mitigation |
|---|---|---|
| Cache-density regression | ~zero by construction (hot `short[]` untouched; +1–2 byte-array lines per pop) | The A/B suite (SHORT/OPEN/extraction-heavy TOWER) would still catch a surprise; that is what it is for. |
| Patch-storm cost | Estimated +0.5–1%/patch; upward cross-seam write is NEW machinery | `PatchStormBenchmark` gate before keep; kill switch. |
| Byte-identity slip (edits, UNBUILT, fences, world bottom) | The four cases in §5, each argued | Property test + trace byte-diff + `LAST_EXPANSIONS` assert; any mismatch is a bug, not a tuning question. |
| Win doesn't reproduce post-(a) | Real — (a) removes the `kindAt` share of each saved read | Bench against the post-(a) baseline; revert (flip default) without sentiment if <3% targeted. |
| Bench blindness (no scenario exercises Fall's deep scans) | TOWER macro-collapses; OPEN is flat | WARM FLOOD loop + the new CLIFFS scenario are mandatory parts of the A/B, not optional extras. |
| Uniform-air build-bypass erosion | Air sections pay their first per-cell pass (~µs) | Quantified §6.1; chunk-build timing spot-check in the in-game smoke. |
