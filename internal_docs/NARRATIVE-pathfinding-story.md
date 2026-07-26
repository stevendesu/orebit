# The Orebit Pathfinding Story — Narrative Source Document

> **Purpose.** This is the source-of-truth story document for a future manim-animated YouTube video
> explaining the Orebit pathfinding system. It is organized as self-contained chapters, each built
> around: the PROBLEM as observed (what the bot visibly did wrong — these become animations), WHY
> it happened (mechanism, from first principles), WHAT WAS TRIED AND FAILED (refuted approaches are
> deliberate, essential content — never cut them), THE INSIGHT, THE FIX, and THE MEASUREMENT.
> Every load-bearing number carries an inline citation to the doc it came from so a video-production
> session can verify claims before scripting.
>
> **Accuracy legend.** Each chapter ends with a STATUS line. `RESOLVED` = shipped and verified.
> `SHIPPED, verify pending` = code landed, in-game regression oracle still owed. `IN-FLIGHT` =
> designed/partially built. `OPEN` = mechanism pinned or unknown, no fix landed. Refuted approaches
> are always labeled REFUTED/REVERTED — never present one as adopted.
>
> **Citation shorthand.** `Opt/NN` = `docs/Optimizations/NN_*.md`. `internal_docs/*` as written.
> `memory/*` = the project memory distillations (historical notes; where a memory claim is
> load-bearing it has been cross-checked against the cited docs).

---

## Prologue — What Orebit is, and the bet underneath everything

Orebit is a server-side Minecraft mod that spawns AI ally bots: virtual players that follow their
owner, fetch things, and navigate the world like a competent human player. The design pillar that
shapes the whole system: **the LLM only recognizes intent — it never plans. All planning is
deterministic** (`internal_docs/PRD.md` §1). That means the pathfinding stack has to be genuinely
good, on a real server tick budget (50 ms for *everything*), in a world that is huge, mutable,
three-dimensional, and where — unlike almost every pathfinding domain in the literature — **you can
edit the map**: any block can be mined, any block can be placed. The bot doesn't route *around* the
world; it routes *through* a world it is allowed to reshape.

Two recurring characters to introduce early:

- **Baritone**, the famous Minecraft pathfinding client mod, is the reference point — both as proof
  that this is possible and as a study object. Baritone's real speed secrets are: time-boxed search
  (never node-capped), best-so-far *partial* path return ("never returns total failure on a long
  goal"), and async segmented plan-ahead with splicing (`memory/baritone-pathing-architecture.md`).
  Orebit eventually adopts all three — but arrives at them the hard way, chapter by chapter.
- **The measurement protocol**, which becomes a character in its own right: paired interleaved A/B
  benchmark runs, permanent guard scenarios, a ≥3% keep bar, and revert-without-sentiment
  (`Opt/08`). Roughly half of this story is ideas that *sounded* right and measured wrong.

---

## Chapter 1 — Reading a block in 6.7 nanoseconds

### The problem as observed

Before a bot can path anywhere it has to *look at the world* — millions of times per search. The
naive way, `world.getBlockState()` plus property reads, measured **~3,000,000 ns per block read**
(3 milliseconds per block, benchmarked as 100 random reads within a 5-chunk radius) (`Opt/01`).
At that speed, a single 10,000-node search reading ~100 cells per node would take *days*. Scanning
for diamonds, checking a wall, planning a path — all catastrophically off the table.

### Why

A single vanilla read does an astonishing amount of work: heap-allocates a `BlockPos`, does world
height checks, computes the chunk, checks the server thread, allocates a `ChunkPos`, checks
generation status, and finally defers into the `PalettedContainer` — whose `.get()` has **four
palette implementations and two storage implementations**. That last part is the killer: with 3+
possible implementations at one call site, the JIT gives up inlining — **megamorphic dispatch** —
and every read pays a real virtual call (`Opt/01`).

### What was tried and failed

- **Branch to avoid the `BlockPos` allocation.** Only three block families actually need a real
  position object (block entities, bamboo, dripstone), so: branch, and skip the allocation
  otherwise. Result: reads got **28.5% SLOWER (600 → 900 ns)** — per-read branch misprediction cost
  more than the allocation saved (`Opt/01`). Reverted in favor of one reusable `BlockPos.Mutable`
  scratch object. This is the story's first taste of a theme: *branches on hot paths are not free*.

### The insight

Stop asking Minecraft. Read the palette storage directly (reflection into `PalettedContainer`
internals), switch on the concrete palette type so every call site is monomorphic, and stay inside
one chunk section as long as possible.

### The fix and the measurement (staged, all `Opt/01`)

| Stage | ns/block | vs previous |
|---|---:|---:|
| `world.getBlockState()` baseline | 3,000,000 | — |
| Read only post-generation (chunk cache) | 1,700 | ~1,765× |
| Bypass world checks, read `ChunkSection` | 700 | 2.4× |
| Reusable `BlockPos.Mutable` | 600 | 1.17× |
| Reflect into palette + type-switch | 9.7 | 62× |
| Sequential `storage.forEach` scan | **6.7** | 1.44× |

Net: **~450,000× faster**. Per-palette detail worth animating: a SingularPalette (an all-air sky
section — ~60% of sections above Y=80) reads in **2.5 ns**; ArrayPalette 14 ns; BiMapPalette 27 ns
(`Opt/01`).

### The second half: 28,000 block states become 587 fingerprints

Fast reads aren't enough — the pathfinder asks the same few questions (standable? passable?
breakable? lava?) millions of times. So every block's answers are precomputed at chunk load into a
**packed 64-bit `long` "fingerprint"** (topY in sixteenths, shape, fluid, hardness, damaging, …),
and — the key move — deduplicated: Minecraft's **27,866 distinct block states collapse to 587
navigation-identical fingerprints ("navtypes"), a 48× collapse**; the whole descriptor table is
**~4.6 KB and lives permanently in L1 cache** (`internal_docs/PRD.md` §6.1/§12.1, `Opt/03`,
`memory/worldmodel-data-model.md`). A grid cell then stores just a 10-bit navtype index (budget:
1024).

**The refuted design decision inside this chapter** (`Opt/03`): six bits were reserved as a
"sturdy faces" mask — which faces you can build against. Measured cost of that speculative field:
**257 of the 503 navtypes existed only to carry it** (503 with faces → 246 without), because the
face mask encoded stair *facing* — 216 navtypes were stairs, distinguished by a fact the planner
never reads. All six bits reclaimed. The resulting rule is quotable: **"Raw data costs navtypes.
Derived data is free."** Derived bits (STANDABLE, BREAKABLE, …) are pure functions of existing
bits, so they add zero new navtypes and turn four-branch questions into single bit tests.

One tiny flourish for the animation: fluids encode as `00` none / `01` water / `11` lava — so "is
there fluid" is the low bit and "is it lava" is the high bit, two single-bit tests, no OR — one CPU
instruction saved on a path run millions of times (`Opt/03`).

**STATUS: RESOLVED, shipped, load-bearing under everything else.**

---

## Chapter 2 — The two-tier bet

### The problem as observed

Even a fast flat A* cannot cross a Minecraft world. Goals hundreds or thousands of blocks away
drown a block-level search in sheer volume (Chapter 4 shows this vividly). Meanwhile the bot needs
to plan across terrain it hasn't even loaded yet.

### The insight (the ratified design, `internal_docs/PRD.md` §5–§6)

Two resolutions, cleanly split:

- A **persisted coarse layer**: the world carved into **16³-block cube regions**, rolled up into an
  implicit octree (levels 0–5 are a true octree 16³→512³; levels 6+ go quadtree — horizontal-only —
  because the world is ~384 blocks tall but ~60M wide) (`PRD.md` §6.3).
- A **recomputed fine layer**: the per-block nav grid near the bot (Chapter 1's fingerprints),
  rebuilt from chunks at ~0.66 ms/chunk, never persisted (`PRD.md` §6.2).

The coarse tier plans a **region skeleton** (HPA*-style); the block tier only ever searches a small
**sliding window** a few regions ahead. "A single five-thousand-node search that would flood and
fail becomes something like a hundred short fifty-node searches" (`Opt/11`).

### What was tried / rejected (design-level refutations, all documented)

1. **Semantic regions — rejected.** The old code had flood-filled semantic regions
   (`Region`/`LeafRegion`/`CompositeRegion`/`Portal`, still in the repo as fossils). The ratified
   design replaces them with a **fixed cubic grid**: assignment is coordinate math, updates are
   O(1) (a placed block touches one cell — a semantic region model must re-partition when a wall
   splits a cavern), and the octree is *implicit* (`PRD.md` §6.3).
2. **Classic HPA* entrances — "ratified — do not reconsider."** Textbook HPA* precomputes
   border-crossing transition nodes and an abstract edge graph. Orebit rejects that outright:
   in Minecraft *any* block edit can create, destroy, split, or merge a region face's entrances.
   Instead: **cost, not connectivity** — "everything is traversable (you can always mine through),
   so the lattice is fully connected; store a crossing cost per face, never adjacency bits"
   (`PRD.md` §6.5). Leaf cost is 4-bit log-scale (spans walk→mine-obsidian, ~3 orders of
   magnitude); "leaf size is the budget lever (cubic), bit-width is fidelity (linear) — they don't
   trade" (`PRD.md` §6.5).
3. **The center model — shipped, then deleted (s36).** The first live region tier priced traversal
   via a single mid-region point. It was **connectivity-blind: it routed through solid walls**
   (`internal_docs/HPA-FRAGMENTS.md` §1). Its replacement: **fragments** — each region stores its
   passable-air connected components with per-face footprints; edges exist where adjacent regions'
   fragment footprints overlap; edge costs are *derived at query time, never stored*
   (`HPA-FRAGMENTS.md`). The center model and its A/B flag were deleted outright.
4. **The flat skeleton — replaced by the cascade (s35/s36).** A single-level skeleton "re-plans the
   whole route every wobble and caps range" (`internal_docs/HPA-CASCADE.md` §1). The replacement,
   `HierarchicalRegionPlan`, keeps a **stack of per-level skeletons** (coarse on top, level-0 at the
   bottom), each level confined to a window of the level above; on movement, **only the level whose
   window the bot exited re-plans** — effectively unbounded range (`HPA-CASCADE.md`).

Two structural niceties worth a diagram: the HPA* cost pyramid and the resource pyramid share one
coordinate key but are stored **struct-of-arrays**, because pathfinding streams costs while
resource search streams histograms — interleaved, each would drag the other's data through cache
(~21 cost entries per cache line dense vs ~4–5 interleaved) (`PRD.md` §6.3). And the Nether's 8:1
scale lives *only in the A* heuristic* (map the Nether into overworld frame), so cheaper Nether
routes surface naturally while the heuristic stays admissible (`PRD.md` §6.5).

**A quotable pairing** (`PRD.md` §6.5): "Regions stay dumb fixed cubes; intelligence lives in the
recomputed nav grid on approach. Keeping regions simple is the whole reason the block tier was made
fast — the two decisions are a pair."

**STATUS: RESOLVED (fragments + cascade unconditional since s36). The cascade's flood/tube
escalation machinery kept evolving — see Chapters 8–9.**

---

## Chapter 3 — The hot loop (and the graveyard of clever ideas)

### The problem as observed

The instrumented block A* on a hard dig-and-climb search:
`path TIMING: 10001 nodes in 80247 us (8024 ns/node) +edits -> FAIL-budget` — **80 ms for one
search, more than a full 50 ms server tick** — and *inconsistent*: 7,000 / 8,500 / 7,400 ns/node
run to run (`Opt/05`). The actual data read at the bottom costs ~7 ns (Chapter 1); the other
~7,993 ns was plumbing.

### Why

Textbook A* leans on `HashMap`s — `gScore`, `cameFrom`, chunk lookup — each keyed by a boxed
`Long`. ~100 boxed lookups per node × 10,000 nodes = a firehose of short-lived heap objects. The
GC pays twice: allocate, then sweep at unpredictable moments — the run-to-run jitter *was* the
collector (`Opt/05`, `Opt/04`).

### The fix (structure-of-arrays A*)

- Node state becomes parallel primitive arrays (`long[] key`, `float[] g`, `float[] f`,
  `int[] parent`); the open set a binary heap of `int` rows; the maps a **~40-line custom
  open-addressed hash map** — two flat arrays, linear probing, MurmurHash3 finalizer to scramble
  Minecraft's structured packed positions (neighboring cells differ only in low bits and would
  pile into a few slots) (`Opt/04`). Trick worth showing: the planned-edits map encodes
  place=1/break=2/nothing=0 so `0` *is* the empty marker and clearing the map is a single
  `Arrays.fill` (`Opt/04`).
- Result: **~8,000 → ~1,290 ns/node (6×)**, and the four post-rewrite runs landed within **8 ns of
  each other** — the predictability, not just the speed, is the point (`Opt/05`).

### The allocation loose end the wall clock couldn't see

The TIMING line said "fast." The JMH **allocation profiler** said: **5,791,820 bytes of garbage
per single TOWER search** — nearly 6 MB, almost all from one residual `ConcurrentHashMap` chunk
lookup minting a boxed `Long` per chunk-boundary crossing (`Opt/05`). Fix (open-addressed chunk
cache): allocation −77%, time −29% (12,067 → 8,568 µs). The remaining ~1.3 MB was **97% one
method** — `EditScratch.snapshot()` — pooled away: **5.79 MB → ~900 bytes per search, GC
collections from dozens to none**, steady ~950 ns/node (`Opt/05`). Then a CPU profile found the
last villain: once a path placed even one block, `PathEdits.kindAt` consumed **25% of the whole
search** (every read paid a hash probe to be told "nothing here"); a six-int bounding-box reject
took build-heavy searches **8,470 → 6,997 µs (~700 ns/node)** (`Opt/05`).

### What was tried and failed — the graveyard (`Opt/08`, the chapter to animate as a scoreboard)

The mental model first: one arithmetic op is nearly free; an L1 hit is ~1 ns; main memory ~100×
that. A node expansion does hundreds of ALU ops *and* ~100 reads — the search sits **on the line
between memory-bound and CPU-bound**, so "obvious wins" routinely flip it the wrong way. The
protocol: paired interleaved A/B runs plus two permanent guards — **SHORT** (a trivial 28-block
walk that includes per-search setup; the cold-start guard) and **MULTI** (four alternating
short/long searches; the persistence guard). A candidate must win somewhere and regress *nowhere*.

| Candidate | Sounded like | Measured | Verdict (`Opt/08`) |
|---|---|---:|---|
| **Hilbert-curve cell layout** | better cache locality | **2–3× SLOWER** | reverted — "paid real arithmetic to solve an imaginary memory problem" (reads already cluster inside one L1-resident 16³ section) |
| Eager neighbor prefetch | hide memory latency | +7% to +26% | reverted — movement code is full of early exits; the eager version faithfully did reads the lazy one skips |
| Heap key packing | halve heap traffic | flat | reverted — simpler code wins |
| Hybrid chunk cache | faster hot lookups | **+3.6% on SHORT** | reverted — guard veto; per-search setup is itself a hot path |
| Edit-diff probe gate | skip a redundant hash probe | flat, **p = 0.000** | reverted — a counting probe showed **zero** of 9,559 edit-carrying expansions were disjoint from the edit bbox: *a pillaring node stands on the block it just placed* |
| Adaptive edit scanning | fix a measured hot spot | **−40% edit-heavy** | **KEPT** — the one survivor of six |

Kicker quote: "Without the paired protocol and guards, we would have shipped at least one of those
regressions — with a confident comment above it explaining why it was faster" (`Opt/08`).

Two more guard-caught reverts from adjacent work: pre-sizing the node table to its high-water mark
("obviously free") cost **+4–7% on SHORT** — every small search paid 28 KB of pointless clearing
(`Opt/09` postscript); caching the per-pop heuristic value measured flat and was "reverted without
sentiment" (`Opt/12`; the region-tier twin of this idea, P2, was also implemented, measured
invisible, and reverted per protocol — `internal_docs/PERF-AUDIT-region-field.md`).

**STATUS: RESOLVED. Baselines (2026-07): clean searches 400–700 ns/node, edit-heavy FLOOD
~1,926 ns/node (`memory/pathfinding-baselines-2026-07.md`).**

---

## Chapter 4 — Making the search see

This is the chapter about *search shape* — the single most animatable material in the project,
because the failures are literal geometric shapes.

### 4.1 The open field it couldn't cross

**Problem:** point the bot ~1000 blocks across flat ground; the search burns its whole 10,000-node
budget and gives up (`Opt/06`). **Why:** with a plain admissible heuristic, an almost-45° diagonal
route has an astronomical number of exactly-equal-cost paths — the test field (970 east, 940
north) has **C(970,30) ≈ 9.6×10⁵⁶** equal routes, "within spitting distance of the number of atoms
in the solar system" — and A* dutifully explores the tie space (`Opt/06`). The trigger obstacle
turned out to be a village the "flat" test world had generated on the route.

**The staged fixes and their honest regressions** (`Opt/06`, hill-scenario scoreboard):

| Step | Positions expanded |
|---|---:|
| Break f-ties toward larger g | 209 |
| + Diagonals + octile ruler | **604** (worse! shorter path, tripled work — the tied-routes tax) |
| + Weighted A* (W=2), remove vertical hack | 71 |
| + Straight-line tie-break (H_TIE=0.001 × cross-track) | **53** |

The field: from FAIL at 10,000 nodes → **crossed in 1,493 expansions, near-instant** (`Opt/06`).
W was swept live: 1.5 still wandered, 3 was too greedy (turned a tidy 3-move cave path into 7
moves). And the **vertical-weighting hack (Fix #1) was deliberately torn out**: "going up isn't
expensive; placing the block is" — the ~20-tick cost already lives on the move. This matters
later: the removed hack was the one thing coaxing the bot up pillars. (The interim per-axis
weights up=4/down=2/horiz=1 are likewise REMOVED history — the shipped heuristic is symmetric 3D
octile × greedyWeight; do not cite the per-axis numbers as current, `memory/block-heuristic-current.md`,
`PRD.md` §7.4. Note for accuracy: PRD's decisions log §12.9 still says "block heuristics stay
admissible" while §7.4 records that the admissible bet "did not survive contact" — the decision
log and the shipped reality diverged, which is itself an honest beat.)

A test-design gem from the same doc: the cave scenario's wall didn't reach bedrock, so the bot
tunneled *under* it in three moves — "The test was wrong; the bot was right" (`Opt/06`).

### 4.2 The pillar cone — 99.8% of the search off the goal column

**Problem as observed:** goal 28 blocks straight up; the only route is to pillar. The search
explores ten thousand nodes and gives up. The trace tool showed the shape: **99.8% of expanded
nodes were off the goal column** — "It was building a pyramid" (`Opt/07`; the in-game `/bot trace`
+ `internal_docs/trace_analysis.py` pipeline exists precisely to draw this cone). **Why:** a
pillar step costs ~25 ticks (jump + place) versus ~4.6 for a walk; each pillar step raises g by
~25 while h falls by ~4.6, so f *rises* as you climb — A* abandons every half-built pillar and
restarts from a neighbor, producing a widening cone of stubs (`Opt/06` §the-unsolved-cliff,
`Opt/07`).

**What was tried and failed:** the tempting 1-D shortcut — count air up the 1×30×1 column and jump
that far. **Wrong:** a floating staircase beside the column at Y+3 (the optimal exit) is sailed
straight past; a 1-D line knows nothing about its sides (`Opt/07`). This refutation is now a
standing non-negotiable: **compute the full cuboid** (`memory/macro-movement-non-negotiables.md`).

**The fix — cuboid macro moves + the forced-cost premium** (`Opt/07`,
`internal_docs/MACRO-MOVEMENTS.md`, `MACRO-IMPLEMENTATION.md`):

- Extract the **maximal cuboid** of uniform navtype around the cell — deliberately *widest across,
  shortest along* the direction of travel, so the box's own extent stops exactly at the staircase.
- Jump the run in **one macro edge**, bounded by the escape-hedge rule:
  `jump ≤ clearance_to_nearest_side_wall / move_cost` — a ~25-tick pillar step gets ~1/5 the
  jump a ~4.6-tick walk gets. **Every draft that "simplified away" the division reintroduced a
  20-block overshoot past a valid exit** (`Opt/07`) — the second standing non-negotiable.
- The partner heuristic, `GoalForcedCost`: probe the goal's six abutting cuboids; a wide air slab
  *below* the goal proves any path must pay pillar-cost per block; a solid slab beside it proves
  dig-cost. Charge only the provable extra over octile, and — the admissibility clincher — **keep
  the smallest premium across faces** ("when in doubt, credit less"); since s42 the far face
  relative to the approach is excluded, the vertical build face always kept (`Opt/07`,
  `MACRO-IMPLEMENTATION.md` §7). Third non-negotiable: never *sum* premiums across stacked cuboids.

**Measurement:** the ten-thousand-node cone became **about forty nodes going straight up** —
~100× fewer expansions (`Opt/07`).

### 4.3 Depth nibbles — deleting an O(n³) loop

The macro layer's cost then showed up in profiles: cuboid extraction (a ~19×19×32 ≈ 11,500-cell
sweep before the first node expands) was **38–45% of a small edit-heavy search** (`Opt/09`).
Rejected on arithmetic (no benchmark needed): widening the 16-bit grid cell — "taxing a
millions-of-reads path to subsidize a dozens-of-reads path is the Hilbert-curve mistake in cache
form" (`Opt/09`). Adopted instead: a **parallel `byte[4096]`** beside each section — two nibbles
per cell: `floorGap` (distance to first standable below: 0–13 exact / 14 proven-none / 15
no-claim-scan) for `Fall`, and `runUp` (uniform run length above) for the extractor, which
collapses the box scan from three nested loops to two — **O(n³) → O(n²)**. Measured: TOWER
−33.7%, UPOVER −30…−36%, MULTI −32.3% — **~75–80% of the whole extraction bill gone**; floorGap
took FLOOD −5.1%, CLIFFS −4.3%, at the price of an *accepted, pinned* +1.2% on SHORT; nibble
maintenance under patch storms cost +2.7% at worst — noise (`Opt/09`).

### 4.4 Teaching the block search the map (the region-informed heuristic)

Even with all that, two "swamps" remained (`Opt/11`): a single floating log splinters the cuboid
proof and the cone floods back ("undone by one stray block"); and a cave goal makes octile point
*through the ceiling* — the bot fans across the whole sky hunting a way down that isn't there
(one flood burned **40,000+ expansions**). Fix: feed the region tier's cost-to-goal into the block
heuristic — `h = max(octile, region cost-to-goal)` (**a max, never a sum** — both estimate the
same journey; summing double-counts), computed once per window by a goal-rooted reverse Dijkstra
over the fragment graph (`Opt/11`).

- **First version failed usefully:** one cost per region = a **plateau** — every cell in a 16³
  region read the same number, no pull toward the exit; the search "made the sky a swamp and then
  filled every region with standing water." Fix: store the goalward exit opening + onward cost so
  the field *slopes* within each region (`Opt/11`).
- **Honest caveat, kept honest in the doc:** the gradient found a genuinely cheaper path (flood
  scenario cost 888 → 825, digging flipped to climbing) but did **not** cut expansions — weighted
  A* re-opens closed nodes when cheaper g appears; "the gradient improved the *answer*; the
  re-expansion is a separate, known tax" (`Opt/11`).
- **The buried-goal bug:** a solid ore belongs to no fragment; the fallback anchored to the
  *nearest-centre air pocket* — sometimes on the wrong side of the rock, pricing a two-block dig
  at **1736** (a phantom 49-block tunnel) instead of **~141**. Fixes: a dig-flood BFS from the goal
  seeds the field in every pocket it breaks into at its true dig cost; and a **virtual goal node V**
  (fragment id 63 — reserved above the real cap of 62) with virtual edges from every dig-reachable
  pocket, so the skeleton itself terminates through whichever pocket minimizes walk+dig
  (`Opt/11`). V returns as a load-bearing concept in Chapter 9.
- **What honest costs buy — the "bug" that was correct:** the bot walked around a wall it
  "obviously" should have dug through. The trace showed the goal was three blocks deep, not two —
  and the bot was **bare-handed** (hand-mining stone: ~8 seconds per block), so three hand-dug
  blocks cost a hair *more* than the walk-around-plus-two-digs. A genuine near-tie, broken
  correctly (`Opt/11`).

### 4.5 The map's price tag (paying for the heuristic)

Then the pipeline benchmark delivered the punchline: in a representative two-tier search of
~5.6 ms, the block A* was **6.6%** of samples and **the field build was 90.8%** — "the heuristic
that existed to make the block search cheaper had become ten times the cost of the block search"
(`Opt/12`; also confirmed independently by JFR: 92% field build / 5% block A*,
`memory/pathfinding-baselines-2026-07.md`). Fixes: memoize pocket labeling into a pooled 4 KB
per-region slab (the dig-flood had been re-deriving a cell's pocket 145–265 times per build; ~78%
of the build's allocation was the bookkeeping of *asking*) — small windows **5.6 → 0.14 ms
(−97%)**, end-to-end **5.6 → 1.1 ms (−81%)**; then early-exit the Dijkstra along a "fat skeleton"
corridor with a provable frontier floor (exact prefix of the full flood — pinned by tests) for
another −17…−32% (`Opt/12`). One refuted rider in the same arc: caching the per-pop heuristic
value — flat, reverted (`Opt/12`).

**STATUS: RESOLVED throughout this chapter (heuristic shape, macros, nibbles, field). The
weighted-A* re-expansion tax is a documented, accepted property.**

---

## Chapter 5 — Movements as physics contracts

### The frame

A path is not a list of cells — it's a chain of **movement strategy objects**, each a small
contract: "from a state like this, I can get you to a cell like that, and it will cost this many
ticks." There are **14** in the live registry: Traverse, Diagonal, Ascend, Descend, Fall, Pillar,
MineDown, Climb, Parkour, DiagonalParkour, Swim, SprintSwim, StartSprintSwim, Surface
(`internal_docs/MOVEMENT-DESIGN.md`, `internal_docs/SUBSYSTEMS.md`; note `PRD.md` §7.2 says "13"
in one spot — doc drift, 14 is current). Everything is priced in **real game ticks**, derived
from vanilla physics, not tuned by hand (`memory/physically-derived-costs.md`):

- The base unit: **walk = 20 ticks/s ÷ 4.317 blocks/s ≈ 4.633 ticks/block** (`docs/movements.md`).
- Break = the block's *real vanilla mining time* against the bot's best tool. Place = base 6 +
  removal premium + inventory feasibility. Diagonal = 4.633×√2 ≈ 6.552. Ladder up 8.51. Sprint-swim
  3.564. Damage converts at **`pathing.costPerHitpoint` = 100 ticks/HP** — "what lets hazards be
  *costs* instead of walls" (`docs/movements.md`).
- Quotable: "The numbers on this page *are* the bot's personality — every 'why did it swim instead
  of taking the ladder?' question is answered by the arithmetic" (`docs/movements.md`). Sanity
  checks fall out for free: stairs (4.633) beat ladders (8.51) beat scaffolding (~10.6) — what a
  player would do.

Two structural ideas to animate:

- **Pose is part of the node.** Sprint-swimming is stateful (initiate needs 2-deep water, continue
  is legal in 1-deep), so the A* node key is **(x, y, z, mode)** — standing vs prone — with
  StartSprintSwim/Surface as real, costed transition edges (2 ticks each)
  (`memory/movement-state-model.md`, `docs/movements.md`).
- **Cost-model changes couple non-obviously.** Lowering placeBaseCost 20 → 6 (a behavioral knob)
  made pillar search *faster*, not floodier — TOWER −23%, UPOVER_WALL **~263× faster** (27 ms →
  100 µs) — because the cuboid escape-hedge takes bigger jumps when the per-step cost is smaller
  (`memory/place-cost-model.md`). "Always measure cost-model changes."

### 5.1 The parkour envelope — a refutation in three acts

**Act 1, the observed bug:** the bot "runs along, fails a jump, gets stuck." Instrumented: the
greedy search corner-cuts with a **diagonal 3-gap** jump (routed edge `seg=(86,139)→(90,143)`,
span 4·√2 = 4.24 blocks) that head-on testing **fails 2/2 at full sprint**
(`internal_docs/DESIGN-parkour-envelope-heightaware.md` §0).

**Act 2, the wrong diagnosis:** the in-game *rising-3* undershoot had been blamed on takeoff speed
("a one-block runup hasn't reached sprint speed — fix is takeoff tuning"). The **closed-form
derivation of vanilla jump ballistics refuted it**: from the decompiled recurrences (vy₀ = 0.42,
gravity 0.08, drag ×0.98 vertical / ×0.91 air horizontal, sprint impulse +0.2; the derived steady
sprint speed 0.280617 b/t = exactly the community's 5.612 m/s), the rising-gap-3 airtime budget is
**2.488 blocks of reach against a required 2.85**. "The bot wasn't slow — the arc doesn't have 3
gaps of airtime above +1" (`internal_docs/DESIGN-parkour-envelope.md` §5–6). The takeoff-tuning
work item died with the row; the old AGGRESSIVE flag and its unmakeable rows were deleted. Two
executor experiments that treated it as an alignment bug (an ALIGN phase; a two-axis takeoff
trigger) were built, didn't fix the failing courses, and were **reverted — "do not resurrect"**
(`DESIGN-parkour-envelope-heightaware.md` §7).

**Act 3, the model refutes itself (partially):** an in-game measurement harness
(`run-parkour.ps1`, a floating-tile course where a missed jump falls ~200 blocks and dies) showed
the closed form was **~1 block too conservative** — the executor holds jump for ~2–3 ticks after
the trigger, so real takeoff happens ~1 block later than modeled. Rising-3 *does* land in reality —
but only 0.16 blocks into the cell, so it stays excluded on the *wiggle-room* bar, not the
impossibility bar: "The old model got the right answer for the wrong reason"
(`DESIGN-parkour-envelope-heightaware.md` §2–3). The shipped envelope: **flat 3, rise 2, fall 4,
diag 2**, and — the new dimension — **height-aware takeoff**: a bottom-slab takeoff drops flat to
2 and rising to 0; soul sand and berry bushes shrink the whole row; honey floors and cobweb cells
are refused before the scan (`docs/movements.md` envelope table). Per `docs/movements.md`, the
derived envelope (generated by `parkour_envelope_params.py`) is the shipped implementation; the
internal design docs predate that landing — flag when scripting.

### 5.2 The swim arc — fighting vanilla physics until you read the source

**Observed:** bots catatonic or spinning in water; floating at the surface spinning their head
instead of diving; rammed upright into 2-tall surface gaps; ejected from bubble columns
(`memory/swim-pathology-investigation.md`).

**The mechanism that unlocked it** (source-verified, `memory/mc-swim-physics-model.md`): vanilla
prone sprint-swim's *vertical* motion is driven by **look pitch, server-side** — the game nudges
vertical velocity toward where you're looking. A yaw-only driver holding jump/sink literally
*fights the game every tick*. And **jump is what breaches**: a prone cruiser must never press
jump, or it pops out of the water and loses the pose.

**Adopted through a long measured ladder** (each rung a harness pass-count,
`memory/swim-pathology-investigation.md`): parameterized arrival tolerance (the "climb-out bug"
was the generic 2.5-block arrival radius firing 2 blocks short in water) 4/11 → 8/11; a
physics-derived submerge bias of **0.8 = (1+H)/2** (body centering); pose-gated reach predicates;
pitch-aimed depth control; reusing the parkour landing servo for dive initiation — **15/15**; then
a full input-only, velocity-aware swim servo, in-game verified. **Refuted en route:** pure-pitch
control (2/11 — broke submersion); centroid-facing + no-jump (5/14, reverted); yaw-only initiation
(6/15, refuted twice); a pose-only reached bandaid (reverted — position is load-bearing). Standing
lesson: "two independent vertical controllers must share one target depth, or they oscillate."

**Bubble columns** got their own ruling: they overwrite vertical velocity every tick — a conveyor,
not water. Modeling them as swimmable was "an accident waiting to happen"; they're marked
IMPASSABLE via a 2-bit up/down descriptor field, with a future `RideBubbleColumn` move for riding
the push direction (`memory/bubble-column-design.md`). The owner hand-built a serpentine
bubble-column maze world to make the momentum-overshoot failure reproducible
(`memory/swim-maze-test.md`) — the harness alone couldn't make it lethal.

**Still OPEN:** the swim **pose livelock** — a bot observed standing at the surface at
(86,62,−41) attempting DiagonalSprintSwim, a prone-keyed node, forever; the plan expected prone,
execution lost the pose, and 1-deep water can *continue* but not *initiate* a sprint-swim. Parked
until a consistent repro; candidate fixes exist but none ratified (`memory/swim-pose-livelock.md`).

### 5.3 Hazards are properties, not special cases

When lava came up, the owner's ruling became a design law
(`internal_docs/DESIGN-hazard-media.md`): lava is already FLUID + DAMAGING + SLOWING in the
descriptor — fluid ⇒ swim moves exist, damaging ⇒ priced via costPerHitpoint, slowing ⇒ a
multiplier. "A* then avoids lava naturally BY COST; it must NOT be special-cased as a solid."
Magma is a floor that hurts: add the contact damage to the cost, done. The same session's
assumption-driven dig-yourself-out and swim-out-of-lava rescue subroutines were **reverted as
bandaids**; an entombed bot now *reports what its feet and head contain, and holds*
(`DESIGN-hazard-media.md`). This fail-and-report posture is Chapter 11's whole philosophy.

**STATUS: movements + costs RESOLVED; parkour envelope RESOLVED (height-aware, derived); swim
servo RESOLVED in-harness and in-game; swim pose livelock OPEN/parked; bubble-column ride move
deferred.**

---

## Chapter 6 — When the map lies (partials, budgets, and the background thread)

### 6.1 Honest partial paths

A search that runs out of budget used to return *nothing* — the bot sat down. Baritone's core
trick is the opposite: always return the best partial and keep moving
(`memory/baritone-pathing-architecture.md`). Orebit adopted it: `PARTIAL_PATH` now defaults ON —
a budget-exhausted search returns its best partial, and the bot walks it (repo `CLAUDE.md`,
`Opt/10`). Two safety rails make partials honest:

- **The irreversibility guard** (default ON): truncate the partial at the last cell before the
  first irreversible move — never end a guessed path just past a ledge you can't climb back up
  (repo `CLAUDE.md`; the guard also prices drop-onto-slab edge cases in the movement set).
- **The geometric partial endpoint** (Fix B of the up-cliff arc): the partial's endpoint is the
  explored node geometrically closest to the target by *unweighted* octile — NOT best-by-h,
  because the region field, forced-cost premium, and tie-break all contaminate h
  (`PLAN-fixAB.md` in the session scratchpad; shipped as core c49ae59 per
  `memory/region-tier-swim-up-blindness.md`). Without this, a flood's "best" node could be a
  point deep in an ocean below the start (Chapter 9's repro).

### 6.2 The first search after boot took 21.8 ms

Not memory — the JVM was *interpreting* the pathfinder before JIT compilation, and the bill landed
on a live player's first command. Fix: `NavWarmup` — a few hundred synthetic searches over a ~50 KB
hand-built fixture at SERVER_STARTED, rounds until times plateau, capped at 1.5 s. First-search
p50 **21.8 → 0.67 ms (32×)**, boot cost 475 ms median (`Opt/10`).

### 6.3 Off the tick thread

Even warmed, a flood-shaped search runs 4–16 ms — a third of the 50 ms tick for *one* bot, and the
10k node cap "leashed intelligence": a goal needing 12,000 positions was unsolvable "not because
the search couldn't, but because it wasn't allowed to do it between two frames" (`Opt/10`). The
async design (`internal_docs/DESIGN-background-pathfinding.md`, shipped s44):

- Tick thread packages an **immutable request** (start, target, caps, inventory *snapshot* —
  never live) and polls a mailbox; a fixed pool (default 2 threads, clamp [1, cores−2]) searches.
- **The one dangerous part:** the nav grid recycles sections on chunk unload — a worker still
  reading one would see *another chunk's data reinterpreted as this one's*: "Not a wrong path. An
  insane one" (`Opt/10`). The reflex (lock every read) dies on arithmetic — millions of reads. The
  insight: **reads are fine; only reclamation is dangerous.** Mechanism: a global epoch counter,
  one published stamp per worker (two uncontended writes per *search*, nothing per node), and
  retired sections wait in a grace list until every worker's epoch has passed them (`NavReclaim`).
- Companion rule: the background grid view has **no live-world fallback** — outside built nav data
  a worker sees air. Optimistic, bounded: "plans a little less at the frontier," never through
  phantom terrain it invented from racing vanilla state (`Opt/10`,
  `DESIGN-background-pathfinding.md` §4.2). (Chapter 12's Bug 3 shows what happens when a search
  runs before *anything* is built.)
- **The budget becomes time**: a deadline checked every 256 pops; the node cap becomes a 262k
  memory backstop and a determinism switch (deadline 0 = byte-identical old behavior — the
  benchmarks depend on this) (`Opt/10`). At ~1500 ns/node the default 250 ms budget ≈ ~150,000
  positions — 15× the old cap, off-tick.

**Measured:** complex-path tick time **8–16 ms → ~3 ms**; a **13,600-node search FOUND** past the
old 10k wall; the synchronous path measured neutral under the house protocol
(`DESIGN-background-pathfinding.md`, `Opt/10`). Default is now ON (`pathing.async=true`;
`SUBSYSTEMS.md` — the design doc's "default false" line predates the flip).

### 6.4 The splice primitive

To avoid a stutter at every window boundary, the next window's search is submitted early from the
*predicted* boundary cell — with the current plan's not-yet-executed edits folded in as a baseline
(`EditSnapshot`, latest-wins; appended after the cameFrom walk so the new path's own edits shadow
it). If the bot arrives within tolerance (Chebyshev 3), the plan is adopted seam-lessly — no pause
(`Opt/10`, `internal_docs/DESIGN-portal-route-layer.md` §4). The splice was deliberately built as
a **first-class shared primitive**: the same seed→accept→adopt machinery is the foundation for the
future multi-leg portal route layer (nether 8:1 break-even routing) — which itself remains
**PENDING**, gated on verifying a fake player can actually traverse a portal at runtime
(`DESIGN-portal-route-layer.md` §8.4; `memory/path-splice-primitive.md` — edits carry across
splices EXCEPT across dimension changes).

**STATUS: partials + guard + async + splice RESOLVED/shipped. Portal route layer IN-FLIGHT
(designed, unbuilt).**

---

## Chapter 7 — Remembering the world (persistence, and the ghost freeze)

### 7.1 Don't rebuild what you learned

The region tier's map is expensive to build (load chunks, flood connectivity), so it persists:
per-dimension plain files in the world save (`hpa.bin`/`res.bin`) — **deliberately NOT vanilla
`SavedData`**, whose API "has drifted hard across the matrix" (four incompatible signatures across
the supported MC range); Orebit's own `CostCodec` bitstream + plain files won decisively
(`internal_docs/DESIGN-worldmodel-persistence.md` §3). Only level-0 leaves persist; every coarse
level is a pure function of them, replayed on load. Budget: ~3–5% of save size against a 10–15%
target (`DESIGN-worldmodel-persistence.md` §10). A typical uniform region encodes in **6 bits**.

### 7.2 Four format decisions, one killed by the benchmark (`Opt/13`)

1. **Shard it**: one file per 32×32-chunk area (matching Minecraft's own `.mca` regions), so a
   save rewrites only changed shards.
2. **Store the answer vs recompute on load**: measured — a dense cave shard was a **1,355 ms stall
   to recompute vs 88.7 ms to read (15.3×)**, for 8–14% more file. Store the answer.
3. **Object-pool the loader — REFUTED**: pooling cut measured allocation **51 → 5 MB** per load
   and changed wall-clock time by **zero** ("if anything a hair slower"). Young-gen allocation is
   a bump pointer; the time was in the *decode*. "Reverted, seams and all" (`Opt/13`).
4. **Drop gzip**: the decoder was **62–71% of load time**, and gzip's benchmark-claimed 6–22×
   compression was **a lie** — a synthetic fixture artifact; real shards compressed 2.15–2.79×.
   Dropping it made cave loads 34 ms (< one tick, "always safe"); then a hand-rolled column-run
   codec recovered **97% of gzip's space at raw decode speed** — the repetition was never in the
   block data (an air region is 6 bits), it was in per-row coordinate headers (63% of the raw
   file) (`Opt/13`).

### 7.3 The ghost freeze (`Opt/14`, `memory/region-persistence-sharding.md`)

**Observed:** on a 3,000-block walk, two full **~2-second freezes** per run — and they *moved*:
rerun, and the freeze happened somewhere else. **Refuted theories, in order:** a pathological
chunk (location-changing rules it out); eviction churn (cranking the memory cap changed nothing);
then the prime suspect, **a GC pause** — "'plausible' is where the house rule starts, not where it
ends." The per-operation profiler couldn't catch it, so they built a different instrument: a
whole-tick monitor that attributes any slow tick across every subsystem phase, GC counters, and
vanilla leftover. It named the culprit in one self-explaining line:

```
tick=1971ms  ourOps=1888ms  gc=0ms  other=83ms  (top: ourOps)  | persist=1883
```

GC theory dead on arrival (`gc=0`). It was the periodic crash-insurance save — firing on a tick
counter (~every 5 minutes), which is why it landed wherever the bot happened to be: **"firing on a
clock, not a place."** Under the hood: an unbudgeted flush writing every dirty shard in one tick,
a quadratic encode (each dirty shard re-scanned the whole dimension), and — found on deeper
diagnosis — unbuffered I/O issuing tens of thousands of 1-byte syscalls (~340 ms/shard)
(`Opt/14`, `memory/region-persistence-sharding.md`). Fix: a small per-tick write budget with
resume-next-tick, clear-dirty-only-after-write, scan-once bucketing, buffered streams (shard
encode 340 → 13–17 ms). **The 2-second spike became a sub-millisecond trickle** (`Opt/14`).

### 7.4 The world that wouldn't hold still

A companion discovery that reshaped all testing
(`internal_docs/DIAGNOSIS-worldgen-nondeterminism.md`): regenerating the *same seed* five times
produced a byte-identical stone/dirt column (one md5) but **three different tree layouts** —
feature decoration runs on the parallel chunk-gen worker pool, and trees whose canopies cross
chunk borders commit in thread-scheduling order. The supposedly owner-scouted "jungle treetop"
start cell was mid-air in every regen. Consequence: seed-regeneration is not a regression oracle.
Resolution: a **frozen master world** — a pristine pre-generated world copied into the run
directory each run, so Minecraft only ever mutates the disposable copy
(`memory/worldgen-nondeterminism.md`). One more determinism ghost fell later: two "identical" runs
forked because the HPA dirty-leaf drain used an *unordered* concurrent set — swapped for a sorted
set drained deterministically (`memory/gather-nav-open-bugs.md`).

**STATUS: RESOLVED (sharding stages 1–2 shipped + in-game verified; frozen-world testing is
standard practice).**

---

## Chapter 8 — The sky highway (when "unknown" is priced as free)

### The problem as observed — the single best visual in the project

Frozen world. The bot starts at (60,180,253), **on top of the tallest jungle tree in the forest**;
the goal is a trial chamber at (201,−28,90) — ~240 blocks away and ~208 blocks *down*. The bot is
bare-handed. Instead of descending, it **pillars straight up to y=319 — the world ceiling — then
bridges across the top of the sky**, overshoots the goal in XZ, and oscillates. It never descends
(`internal_docs/FINDINGS-region-pillar-flood.md`, `internal_docs/DIAGNOSIS-region-pillar-to-sky.md`).

### The two-ingredient A/B that cornered it (`DIAGNOSIS-region-pillar-to-sky.md`)

| Setup | Result |
|---|---|
| pickaxe + clean world | descends |
| pickaxe + persisted HPA | descends |
| bare hands + clean world | descends |
| **bare hands + persisted HPA** | **pillars to the ceiling** |

The persisted HPA — built during the owner's high-altitude spectator flight — carried the sky
regions pre-built as cheap air; bare hands made the honest dig-down cost (~215/region for
hand-mined stone) dwarf everything. Every pickaxe-carrying test run had been *masking* the bug.

### Why — the unifying theory of every flood

The region trace showed L1 expanded **20,000 nodes (the full backstop); 83% of them were
`unbuilt` regions**, with 95,141 unbuilt-transit candidate edges vs ~10k pillar and ~8.7k walk
(`FINDINGS-region-pillar-flood.md`). Three verified defects: (1) the forward pass priced mining
tool-honestly but pillaring with a flat tool-blind 6.0 — inconsistent models; (2) **`unbuilt =
free`** — an unknown region returned cost 0 in all directions ("assume the best"), making the
unexplored void the cheapest medium in the universe; (3) the top cascade level had **none** of the
block tier's flood-shaping and no flood guard at all. The doc distills the general law, worth
putting on screen verbatim:

> **"Flooding = the real path has a required expensive move, and there's a huge cheap field."**
> (`FINDINGS-region-pillar-flood.md`)

The bot didn't prefer up — the flood was 67% lateral / 21% down / 10% up
(`internal_docs/PROPOSAL-region-air-bridging-cost.md`); it exhausted its budget in the cheap
field, returned a high-altitude *partial*, and faithfully executed it by pillaring.

### What was tried and failed

- **"MIXED-region bridging"** (charge air-walking as bridge-building in mixed regions) — WRONG:
  mixed regions have standable fragments and must not be charged bridging; owner-corrected and
  superseded (`PROPOSAL-region-air-bridging-cost.md` header).
- The tool-aware forward pillar cost alone — admitted insufficient in its own proposal: it never
  touched the unbuilt=free highway (`PROPOSAL-region-air-bridging-cost.md`).
- And a **deliberate, documented reversal**: the s51/s52 decision to make the forward dig cost
  tool-aware (adopted to *encourage* digging when a pickaxe existed —
  `internal_docs/PERF-DESIGN-region-cost-and-fragment.md` §5) was intentionally reverted to a
  fixed wooden-pickaxe forward model, with the block tier carrying tool honesty. "We are
  intentionally reverting this decision" (`FINDINGS-region-pillar-flood.md`). The doc frames the
  underlying tension as "we simultaneously both want to NOT mine through walls and want to mine
  through walls. A dilemma."

### The fix (landed as one unit, `memory/region-air-transit-pillar-bug.md`)

Fixed wooden-pickaxe forward dig model; **unbuilt no longer free** — a directional, Y-banded prior
built from Minecraft facts (sea level 63, terrain ceiling ~128: rising into unknown sky is dear,
descending is cheap, "rise to surface to traverse unknown terrain is a reasonable prior"); a
per-search cap-safe **flood guard** (a pop beyond the level's `maxCheb` radius triggers cascade
escalation — restart one level coarser, **blacklisting nothing**, because "a flood is an AREA
problem, not a bad hop"); and a **tube envelope** confining every sub-top level to ±2 parent cells
around the level above's skeleton (`FINDINGS-region-pillar-flood.md`,
`internal_docs/HPA-CASCADE.md`). The hard acceptance gate: *the bare-handed bot with persisted HPA
must not pillar.* It passed; committed (core 6096ccb → both eras). A bench-day footnote the video
should keep: an apparent +20% regression during this arc turned out to be **the owner's son
playing Minecraft on the same machine** — "always bench COLD"
(`memory/region-air-transit-pillar-bug.md`).

**STATUS: RESOLVED (committed, in-game verified descent). OPEN follow-up: collapsed-MIXED regions
still charge dig terms to no-break bots (capability gate missing).**

---

## Chapter 9 — Learning what's impossible (the evidence model)

This chapter is the intellectual climax: how the bot learns that something *can't* be done —
without ever poisoning itself with a false theorem.

### The problem as observed — the no-capa bot and the ocean flood

Give a bot **no place, no break** capability, stand it at the base of a cliff one block from the
ocean, and ask it to climb. No move can ascend; the only cheap, abundant expansions are swims — so
A* pours its entire budget into the ocean: **97% of expansions were swim moves; 99% of explored
nodes were BELOW the start**; the search fans ±31 blocks *into the sea while descending*, returns
a swim-shaped partial, the bot swims it, replans, floods again — the swim loop
(`memory/gather-nav-open-bugs.md`; headless trace byte-for-byte identical to the owner's live
trace, MD5-matched at 10,076,766 bytes). In the worst incarnation the bot **never moved at all**:
distanceTraveled = 0 across **23,894 searches, all budget-capped, 11,800 repairs — the same region
hop blamed 11,800 times** (`memory/region-tier-swim-up-blindness.md`).

### Why — blame was being assigned to the wrong thing

The repair loop's diagnosis of a failed window was wrong twice over. The s57 bug: the start region
was itself a face-neighbor of the goal, and the virtual-goal edge emitter never consulted the
blacklist — so the system blacklisted the same edge every tick and the next skeleton re-emitted it
verbatim. Deeper: **membership-based blame** ("which regions did the search enter?") was tried
twice and is **inert** — floods swim into regions from anywhere; being *in* a region proves
nothing about the route (`memory/region-tier-swim-up-blindness.md`).

### The insight — blame the hop you failed to REALIZE

A failed search is evidence about exactly one thing: the first skeleton hop the search **could not
realize** — where "realized" means an actual parent→child crossing survives in the search's
cameFrom forest. Collect the directed region crossings the failed search realized; blame the first
window hop that is *not* among them (`PLAN-fixAB.md`; shipped as core c49ae59). Paired with the
per-approach virtual goal and the geometric partial endpoint (Chapter 6.1), the up-cliff repro
went from an infinite 11,800-blame loop to **PASS at tick 1410, three byte-identical runs** — and,
just as important, the honest negative held: a truly-walled variant *gives up* at t=3416 with 20
distinct blames and **zero repeats** — no repeat-blame loop, convergence, not speed, is the oracle
(`memory/region-tier-swim-up-blindness.md`, `memory/invalidation-evidence-model.md`).

### Making it survive restarts — theorems with hypotheses

The blacklists used to live and die with the plan object — a full skeleton rebuild silently
discarded everything learned; `clear()` had zero callers (`memory/phase1-correctness-designs.md`).
The owner's key semantic ruling: an invalidation is NOT "this connection doesn't exist" (a
full-capability bot's world is 6-connected) — it is "this crossing can't be safely followed *by a
bot like this*." Hence the persisted form (`internal_docs/DESIGN-persisted-invalidation-memory.md`,
shipped as increment C, 2026-07-22 per `memory/project-status.md`):

> "A persisted invalidation is a theorem; its **sig** is the hypothesis (the failing search's
> effective capabilities — inventory-aware), and it may persist ONLY with realized-evidence
> backing." (`memory/invalidation-evidence-model.md`)

- A **24-byte record**: fromKey + toKey + a 64-bit **capability signature** (can-place as
  *effective* place — caps AND blocks-in-inventory; 3-bit ordered tool-tier fields per category;
  a breath bit reserved-but-unset because the search doesn't model breath yet — "a sig bit for an
  unmodeled constraint is a false hypothesis on every recorded theorem")
  (`DESIGN-persisted-invalidation-memory.md` §3–4).
- **Four poison classes excluded on principle** (`memory/invalidation-evidence-model.md`):
  behind-start blames (a search can't realize hops behind its own start — recording them writes
  lies about crossings the bot just walked); start-region rows; virtual-goal rows (unrealized by
  definition, and keyed without goal identity — structural cross-goal poisoning); and
  cascade-inferred escalation blames (session-only inferences, filtered out of every encode).
- Roll-up correctness (owner overturn): a coarse crossing is dead iff ALL its constituent fine
  crossings are dead — persisted at all levels, because deriving on load is the same recompute
  cost the 15.3× persistence fork already rejected (`DESIGN-persisted-invalidation-memory.md` §4b/§6).
- The **restart oracle**: boot 1 learns the cliff and flushes; boot 2 on the same world must skip
  straight to the detour with materially fewer searches. Verified: searches 57 → 26, 10 crossings
  pre-dead at boot (`DESIGN-persisted-invalidation-memory.md` §5, scratchpad `STATE.md`).

### The failure that defined the boundary of the system

Validation run 3 produced the arc's darkest, best animation: on an infinite superflat plain, a
no-capa bot **escaped the test arena via a planned invulnerable 60-block drop, then treadmill-
marched east away from the goal forever**, blaming one fresh crossing every ~4 seconds — 84
invalidations, all distinct. Forensics showed this is *honest optimism on an infinite frontier*:
unbuilt terrain is priced finitely and capability-blind, so a phantom ascent always exists one
region past the build frontier, receding at walk speed (scratchpad `STATE.md`). The owner's ruling
drew the line: a give-up horizon is REJECTED (the bot must be able to path a million blocks);
the real fix is **boxed-in-goal detection — a region-tier flood-fill connectivity proof — deferred
as its own next arc** (`STATE.md`). Knowing what you cannot yet prove, and refusing to fake it,
is the chapter's closing note.

**STATUS: evidence model + persisted memory RESOLVED/shipped (increment C banked). OPEN/next-arc:
boxed-in-goal flood-fill proof; entry-face invalidation keys (ratified, unbuilt); budget-capped
row epistemics.**

---

## Chapter 10 — Fragments get types (the ocean that "wasn't there")

### The problem as observed

A bot at a shoreline gives up on a trivially swimmable route: the coarse tier reports **false
disconnection** across open ocean surface (`memory/invalidation-evidence-model.md`, scratchpad
`STATE.md`).

### Why

Uniform floorless leaves were being classified air-vs-water by **majority vote**. An
ocean-*surface* region is mostly air with a meter of water at the bottom — majority vote stamped
it `KIND_AIR`, and its swimmable connectivity was discarded. Coarse false-disconnection is the one
fatal error class in this system: the whole invalidation machinery is built on the premise that
over-connection is safe (it converges by invalidation) while **false disconnection is poison**
(nothing ever corrects "impossible") (`memory/invalidation-evidence-model.md`).

### The insight and the fix

The classifier honesty rule, now ratified: **`KIND_AIR` means provably dry** — a floorless leaf
with waterCount == 0; *any* water makes it `KIND_WATER`. Err toward water, the optimistic, safe
direction (`memory/invalidation-evidence-model.md`). Two regressions caught during the same arc
show why this layer needs care:

- Applying swim pricing unconditionally wherever water existed (hasWater ⇒ 0.77/block, including
  vertical) deleted the capability shaping — a wet cave read as an **~8× phantom ascent** (a
  south "dive" priced ~52 vs the honest northern route ~153), so "the bot chased water illusions
  and never offered the northern route" (scratchpad `STATE.md`).
- A cliff repro "failing at t=35 with zero searches" looked like a catastrophic regression and
  turned out to be a **wrong-world artifact** — the bot had spawned entombed in stone because the
  test used the wrong master world. Twice in this project a wrong-world run nearly sent a session
  chasing ghosts ("always verify world identity," `memory/gather-nav-open-bugs.md`,
  `memory/project-status.md`).

Related anchor fixes in the same family (the "where is a region, really?" problem): the s51/s52
cost-accuracy arc — lateral walks had carried a phantom `6×PILLAR` climb to *mid-air portal
centroids* (fixed by snapping crossing anchors to standable cells), and the start node was
sometimes attributed to a fragment that didn't actually **contain the bot** (fixed by
flood-from-bot membership) — both root-caused from a bot that dug straight down ~10 blocks instead
of walking 20 to a cave entrance (`internal_docs/PERF-DESIGN-region-cost-and-fragment.md`); plus
the dig-through arc, where a buried goal's region emitted *no* candidate toward it (fragment
touched no face → no edge existed) and every lateral walk cost a flat 1.0 — fixed by
(region, fragment, entry-face) node identity, always-possible dig-through edges, and real
entry→exit traversal pricing (`internal_docs/PERF-DESIGN-region-dig-through.md`).

**STATUS: KIND_AIR rule RATIFIED and shipped with increment C; anchor/membership/dig-through fixes
RESOLVED (s50–s52). Water-pricing capability shaping corrected during the typed-fragments arc —
consult `memory/project-status.md` for the final banked state of that stack when scripting.**

---

## Chapter 11 — The follower is half the system

The planner can be perfect and the bot still fails: something has to *press the keys*. Orebit's
bot is a real `ServerPlayerEntity` running real vanilla physics — momentum, drag, step-assist,
water pitch-nudges, honey walls. This chapter is the discovery that path *execution* is as deep a
problem as path *search* — and the philosophy that emerged from it.

### 11.1 The silent latch — 27,400 ticks pressing against a step it could climb

**Observed** (`PLAN-movement-pathologies.md`, `PATHOLOGY-P1-diagonalparkour-wedge.md`, session
scratchpad): on one long journey the bot progressed ~230 blocks in 2,600 ticks — then froze at
(272,80,65) for **27,400 ticks — 91% of the entire run** — bouncing 0.42 of a block against a +1
diagonal step it was legally allowed to climb. During the entire wedge: **0 searches, 0 replans,
0 executor transitions, 0 stuck dumps** — "a never-completing, never-invalid phase = silent
livelock."

**Why (tick-by-tick, a superb animation):** during a previous *falling* parkour, the follower's
cursor-advance matched a waypoint the bot's feet merely *fell through mid-air* (the reached test
had no grounded gate) — advancing the plan onto a DiagonalParkour the bot never stood at the start
of. On touchdown at the wrong cell, the takeoff trigger passed on raw projection; the bot jumped —
and the airborne servo, whose job is to null cross-track error toward the *landing line*, computed
the bot's displacement as exactly perpendicular to the jump axis and **cancelled the jump's entire
horizontal component**. It landed where it took off. The terminal LAND phase's done-condition
demanded feet at a cell the bot wasn't at — false forever — and no phase in LAND can ever jump.
The framework "had no abort concept at all" (`PATHOLOGY-P1-diagonalparkour-wedge.md`). This exact
risk had been recorded in code as a DEFERRED KNOWN RISK — "It has now been seen in-game."

### 11.2 Why there was no stuck timer to save it — on purpose

Two owner laws frame everything here:

- **No arbitrary timers** (`memory/no-arbitrary-timers.md`): tick-counter stuck detectors "don't
  sound principled, they sound like nonsense made up to paper over real problems without
  understanding them." Counter-example that killed the last watchdog: an obsidian dig legitimately
  takes 10+ seconds before any progress.
- **No recovery that masks pathologies** (`memory/no-recovery-understand-path-following.md`): the
  goal is a driver that follows *any* computed path perfectly in a vacuum; when it falls off,
  understand why — never patch it back on. The cautionary tale: an early follower re-searched
  whenever it wasn't standing on a waypoint every 2 seconds — "which halted the bot MID-AIR during
  parkour jumps, dropping it into pits and trapping it."

So s52 had deliberately deleted all motion-signature stuck recovery — and Chapter 11's latches are
the honest price of that deletion, paid while building the principled replacement. (The vine bug
made the debt explicit: `Climb`'s own Javadoc had designated "follower stall recovery" as its
self-heal — a mechanism s52 had removed. "Climb shipped depending on a recovery that no longer
exists; other moves may too" — `memory/phase1-correctness-designs.md`.)

### 11.3 The principled replacement — validity envelopes

The sanctioned shape of recovery: each movement declares a **boolean, per-tick, exact** failure
predicate — `failWhen` — describing the physical states from which this move can no longer
succeed (grounded somewhere that is neither takeoff nor landing column; in fluid mid-Ascend; off
the run line mid-Traverse). Fail → clear plan → replan from the live floor next tick. No
durations, no counters (`PATHOLOGY-P1-diagonalparkour-wedge.md` §6,
`memory/movement-follower-envelopes.md`). Alongside it, the reached predicate gained the missing
grounded gate. Envelopes now cover Parkour, DiagonalParkour, Ascend, Traverse, Descend; the swim
family, Pillar, WalkOff and Fall still lack them (known gap).

### 11.4 The floor-frame drift — mining the wrong block, then climbing a wall that isn't there

**Observed** (`PATHOLOGY-P1B-break-not-executed.md`): the next latch specimen — 15,000 ticks
jump-looping into a shore wall the plan said to *mine through* (1,324 regress/re-attempt cycles).
**Why:** the follower re-derived each step's floor from the waypoint — and its inversion rule
("if the feet cell is standable, the feet cell is the floor," written for slabs/carpet) fired
exactly when the feet cell was **solid stone**, i.e. exactly when the step carried a
break-at-feet edit. The whole movement frame shifted up by one: the bot mined one wrong block (the
forensic tell: its "[Stone Age]" advancement — its single successful mine — plus the plan cost
dropping by exactly one break) and then commanded a physically impossible 2-block climb forever.
**Fix:** *carry the floor, don't re-derive it* — the plan now transports per-waypoint floor Ys
from path reconstruction (`floorYs` SoA), and the next run climbed the wall that had latched every
earlier attempt ("break executed at (87,64,-32)" — the arc's most satisfying log line).

### 11.5 The one-side diagonal — shipped, then refuted by the servo it forgot

**Observed:** the bot refuses diagonal steps unless BOTH orthogonal side columns are clear —
zig-zag stutter on diagonal hills, hard-stuck on diagonal ravine lips
(`PATHOLOGY-P5-diagonals.md`). The geometry says one clear side is passable (slide around the
corner via a gate point; path ≈1.65 blocks vs √2, +17%), so a hug diagonal was added at a derived
cost HUG_COST ≈ 8.648 ticks (above √2×walk so octile stays admissible, below the 2-step zigzag).
Unit-tested, shipped. **In-game: the first real hug diagonal froze the bot at the blocked corner
for 15,000 ticks.** The ground servo's line-tracking recenters the bot onto the straight
takeoff→target line — *exactly cancelling* the wall-slide's cross-axis progress; servo and physics
reach a fixed point at the corner. **REVERTED** (patch preserved); the refuted sub-assumption is
named: "V1 can ship with no follower change" (`PATHOLOGY-P5-diagonals.md` §7).

**The insight that follows:** a diagonal hug past a blocked corner and a diagonal-parkour takeoff
are *the same geometry* — pass near a corner point. So the servo grew a first-class primitive:
**gate-point steering** — aim at a gate point (corner + body radius + margin toward the owned
side) while short of it, the real target once past, and fire parkour takeoffs at the gate crossing
(`PLAN-movement-pathologies.md`, owner-ratified). The machinery is SHIPPED; re-landing the hug
diagonal on top of it is the explicit next step.

### 11.6 The servos — making a physics body track a line

The execution layer's own physics discoveries, each with a refutation inside:

- **Falls are not parabolas to model — they're arcs to cancel.** Planner-side parabola modeling
  was REJECTED by the owner; since Minecraft grants aerial control, the follower drop-controls a
  Fall straight down the planned column ("make reality match the model")
  (`memory/fall-not-vertical.md`).
- **"Air control is weak" was wrong.** The earlier ballistic-jump model missed
  `Player.getFlyingSpeed()`'s sprint term; real air control shaves 2.3 blocks over a 12-tick jump.
  The fix was a **full predictive airborne servo** with a hard invariant: only reverse-brake when
  the predictor *under full reverse* still lands at/beyond the near edge — never brake short into
  the gap. Ice course 23/23, parkour 64/64 (`memory/parkour-follower-servo.md`).
- **The honey void-death, 100% reproducible:** a bot flying over a honey block on a descent jump
  died in the void every time. Root cause: `HoneyBlock.doSlideMovement` — the *wall-slide* —
  steals ~88% of horizontal momentum during a fast descent beside honey; the bot's own jump
  created the fast descent that triggered it (a human's slow walk-off stays in the harmless
  branch). Fix: never jump over slow blocks; a new WalkOff move descends without jumping, keeping
  vy in the harmless regime (`memory/parkour-follower-servo.md`). A refuted rider: no uniform
  early-takeoff constant fixes honey without breaking 15–18 other tiers — measured, kept the
  hazard-aware special-cased takeoff.
- **The ABANDONED chat flood:** 258 warning lines per water crossing. Not churn at all — a
  structural one-tick-stale race between fresh `reached` and stale `done` that fired on 100% of
  *normal* step completions for any move whose done-predicate equals its reached-predicate. Fix:
  evaluate done fresh at the advance boundary. **258 → 18** (`PATHOLOGY-P4-abandoned.md`).

### 11.7 Fail → hold, as debugging philosophy

The through-line: when the bot cannot proceed, it **reports and holds** — the entombed bot says
what its feet and head contain and stands still (`internal_docs/DESIGN-hazard-media.md`); a
planless driver *waits* (s52); a failed envelope clears the plan and replans from reality. Every
"just make it wiggle out" branch that was ever written got reverted, because each one would have
buried one of this chapter's mechanisms before it was understood. The final long-run tally after
the envelope arc: a 60k-tick run with **no silent latch anywhere**, 46 attributed break/place
edits — and the honest admission that a *luckier* earlier route had better raw distance: "the wins
are latch elimination + mining execution, not distance yet" (`PLAN-movement-pathologies.md`).

**STATUS: envelopes + floor-frame carry + gate-point steering + servos SHIPPED (suite 554/0/5 at
arc end; some in-game oracles pending). One-side diagonals REVERTED, awaiting hug re-landing on
gate-point steering. P3 (footing-place race), P7 (lone cobble — mechanism pinned to mid-air window
targets), P8 (canopy walking — 3 candidate mechanisms) OPEN. The Traverse↔DiagonalParkour churn
specimen: mechanism pinned (runup overshoots into the diagonal spill cell, which is ground and
outside the envelope's exempt set), parked as safe-but-noisy.**

---

## Chapter 12 — The cadence of replanning

The quietest subsystem — *when* to think again — produced some of the subtlest bugs.

### 12.1 Three bugs, one symptom: the detour to nowhere

**Observed** (`internal_docs/DIAGNOSIS-origin-shortpath-wander.md`): a flat-world bot asked to
walk 10 blocks in a straight line instead veers 7 blocks sideways to (−9,−60,−1) — *the region
centre* — then doubles back. Three independent mechanisms, untangled in one diagnosis:

1. **The field pointed at the door, not the destination.** The region cost field's intra-region
   gradient measured cost *via the region's exit portal centre* — an inadmissible overestimate
   whose gradient literally pointed at the portal. Fix (owner-ratified):
   `costAt = octile(cell→goal) + [onward − octile(exit→goal)]` — octile supplies direction, the
   bracket is a per-region detour *penalty* (≥0, admissible by the triangle inequality).
   Measured: the repro search dropped **74 → 9 expansions, dead straight**.
2. **The window horizon clipped a 4-region L.** The region graph is 6-connected, so a
   corner-to-corner neighbor is a 4-region L-shaped skeleton; a 3-region window put the goal one
   hop past the horizon and targeted a corner portal *west of both start and goal* — the block
   tier walked there faithfully (18 nodes; "the heuristic is innocent here"). Fix: WINDOW 3 → 4.
   **Refuted alternative, owner-vetoed:** a Chebyshev goal-in-window shortcut — a Chebyshev-1 goal
   behind a barrier would wrongly target the goal directly and flood.
3. **The first plan ran before the map existed.** The test issued its goto at server tick 1;
   every region read UNBUILT (optimistic air), portals floated in the sky, and the block search
   **pillared through phantom air to y=118**, capping out with a 6.7 MB trace. Fix: a start-delay
   seam in the harness — and later, properly, a **nav-readiness gate** (build radius + bot-vicinity
   prioritization) shipped in the gather arc (`memory/gather-nav-open-bugs.md`).

### 12.2 Tail pinning — climbing a staircase just to fall off it

**Observed** (`PATHOLOGY-window-slide-cadence.md`): at 14:45:34 in the long-run log, the bot
places a block to build a stair up — and the very next segment opens with a Descend and a fall:
it climbed *into* the window target's arrival box, then dropped out the other side. **Why:** near
the end of a cascade segment the window clamps to the skeleton tail; the target becomes the
hand-down portal cell, and no trigger can move it except the cascade's `exhausted` test — which
requires the bot to *physically occupy* the far region. s52's cleanup had removed the
approach-radius commit (REPLAN_NEAR_TARGET) whose javadoc had warned about exactly this waste
("avoids forcing the bot to pillar up to an imperfect centroid only to drop back down"). The
same log carries a sobering denominator: of 11,722 search cycles, 11,646 (99.4%) belonged to the
Chapter 11 churn specimen — execution bugs dominate cadence bugs by two orders of magnitude.

**The design direction (the "rolling skeleton"):** re-derive the target at half-consumed from the
plan's own shape, paired with treating the segment as exhausted at far−1 so the next segment
routes *around* the hand-down cell — "the smallest change that kills the observed waste"
(`PATHOLOGY-window-slide-cadence.md` §6). **STATUS: IN-FLIGHT — increment A of the rolling
skeleton; design options awaiting owner ruling at time of writing. Do not present as shipped.**

### 12.3 The deferred rewrite

The standing owner position on replan triggers (`memory/replan-trigger-rewrite.md`): today's
periodic full replans are "a BUG SOURCE, not just overhead" — every replan can reset executor
state (a mining grind, a startup counter), and recomputed greedy/partial paths aren't always
identical, so replanning *causes* oscillation. The honest trigger set is enumerated (segment
exhausted / stalled-off-path / goal moved / local validity of the next ~3 waypoints /
region-tier events) and **DEFERRED** — validity envelopes had to exist first. Chapter 11 built
them; this rewrite is the natural next movement of the piece.

**STATUS: origin-wander bugs RESOLVED (verified headless; 74→9, monotonic walk, tests green).
Rolling skeleton IN-FLIGHT. Replan-trigger rewrite DEFERRED by design.**

---

## The Principles

Each principle below is not an aspiration — it is scar tissue, grounded in a named incident.

1. **Evidence first; never assume the mechanism.** The GC theory of the 2-second freeze was
   "plausible" — and `gc=0ms` (Ch.7). The rising-3 undershoot was "obviously" takeoff speed — the
   closed form said the arc doesn't contain that jump (Ch.5). The pillar-to-sky bug hid behind
   every pickaxe-carrying test until a 2×2 A/B isolated *bare hands × persisted HPA* (Ch.8). The
   house sequence — collect evidence, state the verified mechanism, then fix — is written into the
   project's top-level instructions (`CLAUDE.md`, `memory/verify-dont-assume.md`).

2. **Measure everything; keep only what reproduces.** Six convincing optimizations, one survivor;
   Hilbert curves 2–3× slower; a "free" table pre-size +4–7% on the guard; pooling that erased 90%
   of allocation and 0% of time; a gzip ratio that was a fixture artifact (Ch.3, Ch.7). Paired
   interleaved A/B, permanent SHORT/MULTI guards, ≥3% bar, revert without sentiment — including
   your own favorite idea (`Opt/08`, `Opt/12`, `PERF-AUDIT-region-field.md` P2).

3. **No arbitrary timers; recovery is a per-move validity envelope.** The obsidian dig that
   legitimately shows no progress for 10 seconds kills every watchdog design (Ch.11). The
   sanctioned shape: boolean, per-tick, exact, derived from the move's physics
   (`memory/no-arbitrary-timers.md`).

4. **No recovery that masks pathologies; fail → report → hold.** The follower that re-searched
   off-waypoint halted bots mid-jump into pits; the dig-out-of-lava bandaids were reverted the
   day they were written; the entombed bot reports its cell contents and stands still (Ch.5,
   Ch.11; `memory/no-recovery-understand-path-following.md`).

5. **Optimism with invalidation — and only realized evidence may persist.** Unknown terrain is
   priced, not free (the sky highway, Ch.8); connectivity is over-approximated because
   invalidation converges while false disconnection is fatal (KIND_AIR, Ch.10); a persisted
   invalidation is a theorem whose hypothesis is the failing bot's capability signature, backed
   only by crossings the search provably failed to realize (Ch.9).

6. **Costs are physics, not tuning.** Walk = 4.633 ticks/block because vanilla walks at 4.317 b/s;
   break = real mining time; damage at 100 ticks/HP; HUG_COST derived from a 1.65-block gate-path;
   the parkour envelope generated from decompiled recurrences. When the bot walked around a wall
   instead of digging, the arithmetic was right and the intuition was wrong (Ch.4.4, Ch.5;
   `memory/physically-derived-costs.md`).

7. **Flooding = a required expensive move + a huge cheap field.** The unifying theory of every
   search pathology in this story: the pillar cone, the cave-ceiling swamp, the ocean swim loop,
   the unbuilt sky highway, the superflat treadmill (Ch.4, 8, 9;
   `FINDINGS-region-pillar-flood.md`). Corollary: **widen the lens, don't blacklist the hop** —
   a flood is an area problem; a blocked crossing is a hop problem; confusing them poisons memory.

8. **The test is part of the system.** Trees generate non-deterministically → frozen master
   worlds; a wall that didn't reach bedrock → "the test was wrong; the bot was right"; two
   wrong-world runs nearly derailed whole arcs; an unordered dirty-set drain forked "identical"
   runs; a +20% regression was someone else playing Minecraft (Ch.7, Ch.10;
   `memory/dont-weaken-model-for-outdated-test.md` — when a model improvement breaks a test built
   on old behavior, fix the test, not the model).

---

## Appendix — VISUAL CANDIDATES (animatable moments per chapter, with data sources)

**Ch.1 — Reading blocks**
- The per-read "work stack" collapsing: a tower of boxes (alloc BlockPos, height check, chunk
  lookup, palette dispatch…) being knocked away stage by stage as the counter falls 3,000,000 →
  6.7 ns (`Opt/01` table).
- 27,866 block states raining into 587 buckets (48×), the whole table shrinking until it fits
  inside a drawn L1 cache (`PRD.md` §12.1, `Opt/03`).
- The sturdy-faces refutation: 216 stair navtypes merging into a handful the instant the 6 face
  bits are stripped — 503 → 246 (`Opt/03` measured output).

**Ch.2 — Two tiers**
- A wall being built across a semantic blob-region (must re-partition) vs the fixed grid (one cell
  flips) (`PRD.md` §6.3).
- The center model routing a line straight through solid rock; fragments lighting up as colored
  connected components with face footprints (`HPA-FRAGMENTS.md` §1).
- The cascade stack: coarse skeleton on top, finer skeletons nested in windows below; the bot
  exits one window and only that layer re-plans (`HPA-CASCADE.md`).

**Ch.3 — The hot loop**
- The GC "firehose": boxed Longs streaming off a HashMap-based A*, then the SoA rewrite and four
  timing bars landing within 8 ns of each other (`Opt/05`).
- The graveyard scoreboard: six candidate ideas walking in, five tombstones (Hilbert 2–3×,
  prefetch +7–26%, packing flat, cache +3.6% SHORT, gate p=0.000), one survivor (−40%)
  (`Opt/08` table).
- The p=0.000 punchline frame: a pillaring node literally standing on the block it just placed —
  why the edit-bbox gate could never fire (`Opt/08`).

**Ch.4 — Making the search see**
- ★ **The pillar cone**: A* expansions flooding upward and outward around a goal 28 blocks up —
  99.8% of nodes off the goal column, a pyramid of abandoned stub pillars — then the macro-move
  version: ~40 nodes in a straight vertical line (`Opt/07`; drivable from a real
  `/bot trace` dump via `internal_docs/trace_analysis.py`, which already renders 4-panel
  expansion scatters).
- C(970,30) ≈ 9.6×10⁵⁶ equal diagonal routes vs "atoms in the solar system"; the 0.001 cross-track
  nudge threading one line through the tie space (`Opt/06`).
- The cave-ceiling swamp: octile's arrow pointing through rock while 40,000 expansions fill the
  sky; then the region field's gradient flowing down through the actual entrance — plateau
  (flat-shaded regions) vs slope (`Opt/11`).
- The 1-D column probe sailing past the floating staircase at Y+3; the wide-short cuboid stopping
  exactly at it (`Opt/07`, `memory/macro-movement-non-negotiables.md`).

**Ch.5 — Movements**
- The tick-cost bake-off: stairs 4.633 vs ladder 8.51 vs pillar ~10.6 racing up the same wall —
  "the numbers are the bot's personality" (`docs/movements.md`).
- ★ **The parkour refutation**: the sprint-jump arc drawn from the closed form (apex 1.25 at t6),
  overlaid on the rising-3 gap — the arc simply doesn't contain the jump (2.488 reach vs 2.85
  required); then the height-aware harness correction sliding the takeoff 1 block later
  (`DESIGN-parkour-envelope.md` §5–6, `-heightaware.md` §3).
- The corner-cut death: greedy A* routing a diag-3 (span 4.24) off a turn; the bot falls ~200
  blocks on the floating-tile course (`DESIGN-parkour-envelope-heightaware.md` §0, §8).
- The swim pitch-nudge: a yaw-only driver fighting the server's look-pitch vertical nudge every
  tick vs the pitch-aimed servo diving smoothly (`memory/mc-swim-physics-model.md`).

**Ch.6 — Partials & async**
- The irreversibility guard trimming a partial path back to the last cell before a
  can't-climb-back drop (repo `CLAUDE.md` trace-command description).
- The epoch/grace-list dance: worker threads stamped with epochs; retired nav sections waiting in
  a queue until the slowest worker's epoch passes (`Opt/10`).
- The seam splice: next window's search launched from a predicted cell while the bot still walks
  the current one; adoption with no pause (`Opt/10`, `DESIGN-portal-route-layer.md` §4).

**Ch.7 — Persistence**
- ★ **The wandering ghost**: the same 3,000-block walk run twice, the 2-second freeze striking at
  two different places — then the one log line
  (`tick=1971ms … gc=0 … persist=1883`) that named it (`Opt/14`).
- The gzip lie: the synthetic fixture's 6–22× bar collapsing to the real 2.15× when measured on
  actual shards; 63% of the raw file revealed as coordinate headers (`Opt/13`).
- Same seed, five regenerations: identical stone columns, three different trees flickering —
  then the frozen master world being copied fresh each run
  (`DIAGNOSIS-worldgen-nondeterminism.md`).

**Ch.8 — The sky highway**
- ★ **Pillar to the world ceiling**: the bare-handed bot on the tallest jungle tree building to
  y=319 and bridging across the sky toward a goal 208 blocks *down*; the 2×2 ingredient matrix
  resolving to one red cell (bare hands × persisted HPA)
  (`DIAGNOSIS-region-pillar-to-sky.md` — the 22 MB region trace is literally the flood's shape).
- The inverted cost landscape: sky as a 1-tick/block highway (green), the ground descent as a
  215/region wall (red) (`DIAGNOSIS-region-pillar-to-sky.md`).
- The imagined worst case: the 2000×2000 obsidian slab and the 62×62-region flood square spreading
  across its surface before the flood guard escalates the lens
  (`FINDINGS-region-pillar-flood.md` fix 3).

**Ch.9 — Learning what's impossible**
- ★ **The ocean flood**: 97% swim expansions, 99% of nodes below the start, the search fanning
  ±31 blocks into the sea while the cliff goal sits 22 blocks away at expansion #6
  (`memory/gather-nav-open-bugs.md`; headless trace MD5-identical to the live one — a real trace
  file can drive this).
- The blame flip: membership blame painting every region the flood touched (11,800 identical
  blames) vs realization blame following the cameFrom forest and pointing at the one unrealized
  hop (`memory/region-tier-swim-up-blindness.md`, `PLAN-fixAB.md`).
- The restart oracle: boot 1 learns the cliff (13 invalidations); boot 2 skips straight to the
  detour (57 → 26 searches) (`DESIGN-persisted-invalidation-memory.md` §5, `STATE.md`).
- The superflat treadmill: the bot marching east forever, a phantom ascent always one region past
  the build frontier, receding at walk speed (`STATE.md` run 3).

**Ch.10 — Fragments get types**
- The ocean-surface region: mostly air, a meter of water — majority vote stamps it AIR and the
  swim route vanishes from the coarse map; the KIND rule re-lighting it
  (`memory/invalidation-evidence-model.md`).
- The mid-air portal centroid: a lateral walk charged a phantom 6×PILLAR climb to a floating
  anchor; the anchor snapping down to a standable cell
  (`PERF-DESIGN-region-cost-and-fragment.md`).
- The buried goal: reverse-field seeding via dig-flood breaking into pockets; the virtual node V
  (fragment 63) collapsing a 1736-cost phantom tunnel to the honest 141 two-block dig (`Opt/11`).

**Ch.11 — The follower**
- ★ **The 27,400-tick wedge**: the timeline bar (9% moving, 91% frozen); the mid-air waypoint
  match during a fall; the servo's cross-track correction vector exactly cancelling the jump; the
  0.42-block bounce loop (`PATHOLOGY-P1-diagonalparkour-wedge.md` §2 — fully reconstructed
  tick-by-tick, ready to animate).
- The floor-frame drift: the movement frame sliding up one block because the feet cell was the
  very stone to be mined; the bot mining the wrong block and then trying a 2-block climb
  (`PATHOLOGY-P1B-break-not-executed.md`).
- ★ **The hug-diagonal fixed point**: the wall-slide pushing the bot around the corner while the
  line-tracking servo pulls it back — velocity vectors summing to zero at the corner, 15k ticks
  (`PATHOLOGY-P5-diagonals.md` §7); then gate-point steering bending the aim around the corner.
- The honey void-death: side-by-side of a human's slow walk-off (harmless branch) vs the bot's
  jump-descent triggering the wall-slide's 88% momentum theft into the void
  (`memory/parkour-follower-servo.md`).

**Ch.12 — Replanning cadence**
- The 10-block walk with a 7-block detour: the inadmissible field gradient pointing at the portal
  centre; after the fix, 74 → 9 expansions in a dead-straight line
  (`DIAGNOSIS-origin-shortpath-wander.md`).
- The phantom-air pillar: a bot at server tick 1 climbing to y=118 through terrain that hasn't
  been classified yet — floating portals in an all-UNBUILT skeleton
  (`DIAGNOSIS-origin-shortpath-wander.md` bug 3).
- The staircase built then abandoned: place → climb into the arrival box → Descend + fall out the
  other side — tail pinning in one shot (`PATHOLOGY-window-slide-cadence.md`).

---

## Scripting checklist (accuracy traps, condensed)

- Rolling skeleton / tail-pinning fix: **IN-FLIGHT (increment A)** — analysis complete, design
  options pending owner ruling. Not shipped.
- One-side (hug) diagonals: search-side emission SOUND but **REVERTED**; gate-point steering
  shipped as the prerequisite; hug re-landing is next. Do not show the hug as live.
- P3 (footing place race), P7 (lone cobble; mechanism pinned to mid-air window targets, fix
  pending), P8 (canopy walking; 3 candidate mechanisms, undiscriminated): **OPEN**.
- Swim pose livelock: **OPEN/parked** (no repro). Bubble-column ride move: deferred.
- Portal route layer: splice primitive **SHIPPED**; the multi-leg route layer **PENDING**, gated
  on fake-player portal runtime verification.
- Per-axis heuristic weights (4/2/1) and the vertical-weighting hack: **REMOVED history** — never
  cite as current. Current h = symmetric octile × greedyWeight(2.0) + tie-break + GoalForcedCost.
- The PRD decisions log still says "block heuristics stay admissible" while §7.4 records the
  weighted reality — cite §7.4.
- Parkour envelope: internal design docs predate the landing; `docs/movements.md` describes the
  shipped derived, height-aware envelope. The rising-3 story has TWO refutation layers (takeoff
  theory wrong; then the first model right-for-the-wrong-reason) — keep both.
- The pillar-to-sky fixes: FINDINGS says "planned"; the later memory records them **committed and
  verified** (core 6096ccb). Use the later status; keep the FINDINGS citation for the analysis.
- `pathing.async` default is now TRUE (the design doc's "default false" predates the flip).
- Movement count is **14** (PRD's "13" in §7.2 is drift).
- Anything sourced only from `memory/*` is a historical distillation — spot-check against code
  before putting a number on screen.
