# Cuboid extraction — per-node cost: profile + options

> Session 2026-06-26 (post macro-movements). Steve asked: **profile first, review the code, list
> options — don't implement.** He also pre-empted HANDOFF item 1 ("extract only as much box as the
> jump needs"): **we need the whole box** (the jump length is a function of move type / cost / direction
> / the other cuboid dims — unknowable a priori; and `GoalForcedCost` needs the full forced-column depth).
> The ask is instead: build the **same** box **faster** — a single linear scan over a random-access flood
> fill, better memory layout for the cache — with everything profiled, alloc-free, cache-friendly.

## STATUS (updated 2026-06-26, later same session)

**A and B implemented, measured, committed + merged to all branches.** TOWER bench **161 → 57 µs/op (~2.8×)**,
box byte-identical, node count preserved.
- **A** (bulk section-local memory-order scan + `NavGridView.sectionRawAt` seam): −62%. A review pass caught
  a real over-claim bug (section-stepped scan skipped non-corner rows — invisible on uniform-air fixtures);
  fixed + guarded by `CuboidExtractorScanTest`.
- **B** (macro only the primary axis): a further −6% on the (cache-hot) bench; profile confirms the off-axis
  per-node extracts vanished.

**Then we diagnosed the in-game ns/node variance** (pillar 1733 ns/node vs up-and-over 3891) with two new
benches (`UPOVER_OPEN`, `UPOVER_WALL` — committed) + throwaway extraction counters. **Result: re-extraction
is NOT the up-and-over cost** — `cacheMissRate` ~0 and `cellsScanned/node` *falls* as the search hardens
(A/B solved it). The cost is the **speculative edit-shrink**: on `UPOVER_WALL`, `PathEdits.kindAt` is 70% of
search CPU and **stack attribution shows 81% of it is the shrink** (`applyEditShrink`→`findEditInside`),
only 13% movement edit-folding → **edit-shrink alone is ~57% of search CPU**. It fires on ~1.7 of every
`cuboidAt` because the path's own placed footings/bridge blocks land inside the goal-ward cuboid and
`findEditInside` scans the whole `box ∩ editsAABB` (which an L-path spreads wide). **This re-sizes Option D
from "single-digit %" (TOWER-sized) to the dominant up-and-over lever — see Option D below.** The node-count
explosion from B's off-`P` micro (238→4674 in-game) then *multiplies* that per-node cost.

**Next:** D (forward-only edit-shrink). After D, reconsider B's axis policy (the ~20× node explosion is the
bigger multiplier; A made extraction cheap, so macro-ing the top-2 axes for a balanced multi-axis goal may
now be affordable — must preserve TOWER). C still deferred.

## What the profiler actually says

JMH `PathfinderBenchmark` TOWER (44-node open-air pillar; corridor `[-1..17 × 0..33 × -1..17]`),
JDK 21, `-Pprof=cpu` (JFR `jdk.ExecutionSample`) + `-Pprof=gc`:

| metric | value |
|---|---|
| total | **159 µs/op** (~3.6 µs/node — short search, can't amortize the one-time extract) |
| alloc | **6473 B/op** for the *whole* search (~150 B/node), per-search not per-cell |

CPU leaf-method histogram (1118 in-search samples):

| samples | % | method |
|---:|---:|---|
| **787** | **70%** | `CuboidExtractor.slabUniform` ← stage-2 extend-along-travel |
| 94 | 8% | `PathEdits.kindAt` (edit-shrink scan **+** movement edit-folds — mixed) |
| 58 | 5% | `NavGridView.sectionAt` ← driven by the extractor's `packedAt` |
| 46 | 4% | `NavGridView.lookupChunk` ← same |
| 26 | 2% | `CuboidExtractor.edgeUniformA/B` ← stage-1 slab grow |
| 6 | <1% | `CuboidExtractor.extract` |
| rest | — | movements / heap / octile, all in the noise |

**Headline: the cuboid build is ~82% of search CPU** (slab + edge probes + the `sectionAt`/`lookupChunk`
those probes drive). Steve's instinct was exactly right. Two structural facts:

1. **Stage 2 dominates stage 1 by 30:1** (787 vs 26). Stage 2 (`slabUniform`) re-scans the *entire*
   W×W orthogonal slab cross-section for *every* layer of travel. For the TOWER box that's
   ~19×19 = 361 cells × ~33 layers ≈ **12 000 cell-reads for one extract** — to justify a jump the
   escape-hedge then caps at ~2–3. (We still want the whole box; this is just where the cells go.)
2. **The per-cell cost is the lever, not the cell count.** Each cell in the uniformity probes pays:
   `comp()`×3 (≈9 branch-compares to map the (t,a,b) basis → world x,y,z) + `bound.allows` (6 compares)
   + `packedAt`→`sectionAt` (shifts, chunk-key compare, array index, bounds) + `getPacked`→
   `getLinearIndex` (shifts) + mask + navtype compare. ~30–40 ops + an object indirection, **per cell**,
   ~12 000 times. At ~9 ns/cell that's the 111 µs that *is* the 70%.

### Allocation is fine (but not literally zero)
6473 B/op is **per search, not per cell** — the inner scan is alloc-free (12k reads, ~0 churn). The
6.5 KB is setup: `pending[axis] = new Cuboid()` on each newly-cached region (Option C removes it), the
`new Cuboid[16]` lists, and the result path. No hot-path violation. Noted only because the rule is sacred.

### Measurement caveat (important before trusting a "win")
The bench fixture **shares one air `NavSection` across all chunks** (`buildFlatWorld`, line 118). So every
`packedAt` hits the same 8 KB array, cache-hot, and `sectionAt` always hits the last-chunk cache after the
first. **The bench therefore measures instruction-count, and *understates* the cache-locality half.**
In-game ns/node (10–16k) ≫ bench (~3.6k/node); a big chunk of that gap is real section fragmentation the
bench can't see. To measure the locality win specifically: add a fragmented-world TOWER variant (a distinct
`NavSection` per chunk) or profile in-game.

---

## Options (ranked by profile-backed expected impact)

### A — Bulk, section-local, memory-order uniformity scan  ★ recommended first
**The "single linear scan vs random-access flood fill" Steve asked for.** Keep the *exact same maximal
box*; only change how the uniformity probes read it.

- Resolve the `NavSection` **once** per (chunk, y-section) span and grab `grid.raw()` (`short[]`) directly,
  instead of calling `packedAt` per cell (which re-resolves the section + re-derives the linear index every
  time).
- Iterate cells in **backing-array index order**: the grid index is `(y<<8)|(z<<4)|x`, so **x is
  contiguous** (+1), z is +16, y is +256. Walk x innermost → sequential memory, half-cache-line rows.
  (Today `slabUniform` loops `aOff` outer / `bOff` inner; for the dominant travel=Y case that's X-outer/
  Z-inner → **strided by 16**. Even inside the current abstraction, swapping to x-inner helps.)
- Compare `(raw[idx] & NAVTYPE_MASK) == nav` directly — no packed-int round-trip, no descriptor.
- Hoist the corridor + section-boundary clip to **integer loop bounds** computed once, replacing the
  per-cell `bound.allows` (6 compares × 12k).
- **Cross-section spans are first-class, not an edge case** (Steve: the box deliberately crosses
  16-boundaries — bigger box → bigger jump → fewer nodes; that complexity was accepted on purpose). Today
  `grid.packedAt` hides this by re-resolving the section *every cell*; that re-resolution is exactly the
  cost we're removing, so the bulk scan must handle the split itself.

#### The cross-section design (the crux of A)
Keep the two-stage growth algorithm; replace only the per-cell `cellOk` loop inside `edgeUniformA/B` and
`slabUniform` with **one bulk primitive: "is this world-space rectangle uniform in navtype `nav`?"** It:
1. Decomposes the rectangle into **section-aligned sub-rectangles** — split at each 16-boundary on X, Y, Z.
   Corridor-bounded, so this is a handful of sections (e.g. the TOWER slab touches ~2×3×2).
2. For each sub-rectangle, resolves that section's backing `short[]` **once** (a new seam — see below),
   then scans the clipped sub-range in **memory order, x innermost**: local index
   `((y&15)<<8) | ((z&15)<<4) | (x&15)`, `+1` per x. Compare `(raw[idx] & NAVTYPE_MASK) == nav` directly.
3. A sub-rectangle whose section is **unbuilt** (seam returns `null`) → the rectangle is **not** uniform
   (hard wall) — identical to today's `packedAt == UNBUILT`. The box never grows across it.

**New seam needed** (the "get pointers directly to the bits" Steve called for): `CuboidExtractor` is in
`pathfinding.blockpathfinder.cuboid`, `NavGridView` in `worldmodel.pathing`, so add a `public`
`NavGridView.sectionRawAt(int x,int y,int z) -> short[]` (or `null`) that resolves the section **through
the existing per-search `lookupChunk` cache** (keep the boxing-avoidance) and hands back
`section.getTraversalGrid().raw()`. The extractor computes local indices itself. (`TraversalGrid.raw()`
and `NavSection.getTraversalGrid()` are already public — the only missing piece is the cached resolve +
expose on the view.)

**Behavior:** identical box (regression-guarded by `MacroPillarTest` + determinism). **Impact:** hits the
80% hotspot directly; per-cell cost should drop several-fold (fewer instructions *and* sequential access),
*and* the section is resolved once per sub-rectangle instead of once per cell (kills the `sectionAt` +
`lookupChunk` 9%). **Risk:** medium — the section-boundary split + `UNBUILT`/corridor semantics must be
exact; contained to one helper. **Caveat:** bench shares one section so it shows the instruction-count win
but understates the once-per-section-resolve and locality wins — measure those on a fragmented bench / in-game.

### B — Macro only the primary axis (Steve's fix)  ★ confirmed 3×, gated on node-count check
**Confirmed structurally:** `NavGridCuboidsView` holds `Cuboid[][] boxes = new Cuboid[3][]` — one cache
*per travel axis*. Pillar+MineDown fill the **Y** cache; the Traverse cardinals fill **X** and **Z**. So a
uniform open region is extracted **up to 3× — once per axis** (the boxes differ per axis because the box is
directional-maximal: widest-orthogonal-to-travel / shortest-along-travel). This is real, repeated work over
the same air.

My earlier "share one box across all three axes" idea is the hard way (the directional-maximal box isn't
axis-independent in non-convex regions → needs a conservative intersection). **Steve's approach sidesteps it
entirely:** pick **one primary axis** per search and macro *only* that one; the other axes fall back to micro.

- **Primary axis** `P = argmax(|gx−sx|, |gy−sy|, |gz−sz|)` — the dominant approach direction, the axis whose
  long uniform runs actually need collapsing. **Tie-break `X > Z > Y`** (so the kept axis also has the
  best linear-scan locality — X is the contiguous grid axis, Y the worst).
- Per-node movement macro queries are restricted to `P`: a movement travelling a non-`P` axis emits the
  plain micro step (skip `cuboidAt`). Extraction drops from 3 axes to 1, and `boxes` collapses to a single
  list. Multiplies directly on top of A.
- **Exempt: the once-per-search `GoalForcedCost.probe`** (6 `cuboidAt` calls over all axes/signs). It must
  examine the goal's *forced* approach axis, which can differ from `P` (e.g. a horizontally-approached buried
  goal: `P=X` but the forced dig may be on Z). It's once per search, not the hot path, so let it extract on
  any axis; only the *per-node* movement queries are pinned to `P`.

**Behavior tradeoff to verify:** off-`P` movements lose macro collapse. For the canonical cases this is
exactly right — the primary axis is the one that floods, and the *orthogonal* flood is already held by
`GoalForcedCost` + the corridor, not by orthogonal macro-jumps. The one case that pays is an **up-and-over**
goal (climb *then* traverse): one leg goes micro → more nodes on that leg. **Gate:** confirm with the
node-count check (HANDOFF recipe — `LOG_TIMING`) that picking one axis doesn't trade the node win back. Low
risk, simple change, ~3× on extraction.

### C — Cuboid cache layout: SoA, kill the pointer-chase (and the per-search alloc)
`NavGridCuboidsView.boxes` is `Cuboid[][]` — `contains()` chases pointers to separate ~48 B heap objects.
Flatten each per-axis list to parallel primitive arrays (`int[] minX/maxX/minY/maxY/minZ/maxZ`, `int[]
navtype`): the MRU/linear `contains` scan becomes sequential int reads (~4 boxes per cache line), and the
`pending`/`new Cuboid()` per region disappears (removes the 6.5 KB/op). **This is the "memory layout for
the cuboid cache" Steve named.** **Impact now:** small — `contains` is ~0% in this short search; **grows**
as regions multiply on bigger maps, and it's the only per-search alloc. **Risk:** low. Good cheap follow-on
to A.

### D — Forward-only edit-shrink  ★ NEXT — the dominant up-and-over lever (re-sized by measurement)
**Original sizing was wrong** (it was sized on TOWER, where the path's edits are a single column → tiny
`box ∩ editsAABB` → the shrink "almost never fires"). The `UPOVER_WALL` diagnosis re-sizes it: the
speculative edit-shrink is **~57% of search CPU** on an up-and-over (`PathEdits.kindAt` 70% of samples, 81%
of that via `findEditInside`). `applyEditShrink` runs on ~1.7 of every `cuboidAt`, and `findEditInside`
triple-loops over the *entire* `box ∩ editsAABB` — but the path's edits the bot placed sit *behind/below*
the node (the pillar under an upward jump; the bridge trail behind a forward walk), and **an edit behind the
node along travel can never affect a forward jump.**

**Fix:** thread the travel **sign** into `cuboidAt` → `applyEditShrink`, and clip the shrink scan's
*travel-axis* range to the forward half (`[start .. forward]` for `+sign`, `[.. start]` for `−sign`),
keeping the **full orthogonal range** (lateral edits within the forward run still reduce clearance and must
be caught). This skips the placed-pillar-below / bridge-behind cells — the bulk of the scanned intersection
on an up-and-over — while staying conservative (skipping only cells the forward jump never traverses; it can
only *under*-shrink the unused backward extent, which `MacroJump` ignores).

**Wiring:** `cuboidAt(x,y,z,travelAxis,sign,out)` — callers `Pillar` (`+1,Y`), `MineDown` (`−1,Y`),
`Traverse` (cardinal sign), and `GoalForcedCost.probe` (its approach sign; once/search, perf-irrelevant but
must pass *a* sign). **Risk:** medium — correctness-adjacent: must NOT skip a forward or orthogonal edit
(that would let a macro jump through a placed/broken cell → invalid path). Needs a guard test: a forward
speculative edit must still clamp the jump; verify forward-only doesn't skip it. **Impact:** targets the
~57%; expect a large per-node drop on up-and-over, neutral on TOWER (where the shrink barely fires).

---

## Recommended order
1. ~~**A**~~ — DONE (−62%, byte-identical, guard test added).
2. ~~**B**~~ — DONE (primary-axis; off-axis extracts gone). Node-count tradeoff is real on up-and-over but
   tractable (<10k); revisit its axis policy after D.
3. ~~Up-and-over + fragmented benches~~ — DONE (`UPOVER_OPEN`/`UPOVER_WALL`), and they pinned the diagnosis.
4. **D** — NEXT. The dominant up-and-over lever (~57% of CPU), re-sized by measurement. Forward-only shrink.
5. **Reconsider B's axis policy** (post-D): macro the top-2 axes for a balanced multi-axis goal to cut the
   ~20× node explosion — now that A made extraction cheap. Must preserve TOWER. Bigger lever than D for
   up-and-over, but more design surface + interacts with D (more macros → more shrink), so D first.
6. **C** — deferred (SoA cache layout; small now, removes the only per-search alloc).

**Not doing:** shrinking the box / capping the extract to the jump length (Steve: we need the whole box;
the jump bound is unknowable a priori and `GoalForcedCost` needs the full forced-column depth).
