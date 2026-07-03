# Cuboid extraction — per-node cost: profile + options (status summary)

> Session 2026-06-26 (post macro-movements). Steve's framing: **we need the whole box** (jump length is a
> function of move type / cost / direction / the other cuboid dims — unknowable a priori; `GoalForcedCost`
> needs the full forced-column depth). The ask was never "extract less" but "build the **same** box
> **faster**" — single linear scan, better cache layout, alloc-free.

## STATUS (2026-07-03)

- **A — bulk section-local scan: DONE.** The `NavGridView.sectionRawAt` seam + memory-order scan landed
  (TOWER 161 → 57 µs/op, ~2.8×, box byte-identical, guarded by `CuboidExtractorScanTest`). The remaining
  extraction bill was then cut a further **~75–80% by the E4 runUp depth nibble** — a section-local bulk
  primitive by another name — TOWER −33.7%, UPOVER −30..−36%, MULTI −32.3%, no cross-search persistence
  needed. See `PERF-DESIGN-runup-nibble.md` + `PERF-RESULTS-2026-07-03.md` §E4.
- **D — forward-only edit-shrink: DONE.** `NavGridCuboidsView.applyEditShrink` clips the shrink scan's
  travel-axis range to the forward half (was ~57% of up-and-over search CPU — the placed pillar-below /
  bridge-behind cells the forward jump never traverses). Kept the full orthogonal range (lateral edits
  still clamp clearance).
- **B — primary-axis-only extraction: OPEN.** An early version shipped and was reverted; the node-count
  tradeoff below (the ~20× up-and-over explosion risk) is the reason it's parked. Now that A/E4 made
  extraction cheap, macro-ing the *top-2* axes for a balanced multi-axis goal may be the better shape —
  but it must preserve TOWER and be gated on a node-count check.
- **C — SoA cuboid-cache layout: DEFERRED.** Small now (`contains` ~0% on short searches); grows as
  regions multiply on bigger maps, and it's the only per-search cuboid alloc. Cheap low-risk follow-on.

---

## The profiler breakdown that set the priority

JMH `PathfinderBenchmark` TOWER (44-node open-air pillar; corridor `[-1..17 × 0..33 × -1..17]`),
JDK 21, `-Pprof=cpu` (JFR `jdk.ExecutionSample`) + `-Pprof=gc`. Total **159 µs/op** (~3.6 µs/node),
alloc **6473 B/op** for the whole search (per-search, not per-cell). CPU leaf histogram (1118 in-search
samples):

| samples | % | method |
|---:|---:|---|
| **787** | **70%** | `CuboidExtractor.slabUniform` ← stage-2 extend-along-travel |
| 94 | 8% | `PathEdits.kindAt` (edit-shrink scan **+** movement edit-folds — mixed) |
| 58 | 5% | `NavGridView.sectionAt` ← driven by the extractor's `packedAt` |
| 46 | 4% | `NavGridView.lookupChunk` ← same |
| 26 | 2% | `CuboidExtractor.edgeUniformA/B` ← stage-1 slab grow |
| 6 | <1% | `CuboidExtractor.extract` |

**Headline: the cuboid build was ~82% of search CPU** (slab + edge probes + the `sectionAt`/`lookupChunk`
they drive) — extraction 38–45% of small-search CPU across scenarios. Two structural facts drove A/E4:
(1) **stage 2 dominates stage 1 by 30:1** — `slabUniform` re-scans the entire W×W orthogonal slab
cross-section for every travel layer (~12 000 cell-reads for one TOWER extract); (2) **the per-cell cost
is the lever, not the cell count** — each probed cell paid ~30–40 ops + an object indirection (basis map,
`bound.allows` 6-compare, `packedAt`→`sectionAt`, mask, navtype compare), ~12 000 times.

**Measurement caveat:** the bench fixture **shares one air `NavSection` across all chunks**
(`buildFlatWorld`), so every `packedAt` hits the same cache-hot 8 KB array — the bench measures
instruction-count and **understates the cache-locality half** (in-game ns/node 10–16k ≫ bench ~3.6k/node,
the gap is real section fragmentation). Locality wins must be confirmed on a fragmented bench / in-game.
The **UPOVER_WALL diagnosis** later re-sized D: there `PathEdits.kindAt` was 70% of CPU and **81% of that
was the shrink** (`applyEditShrink`→`findEditInside`, triple-looping the whole `box ∩ editsAABB`), which
is what D forward-clips.

---

## Option B — the open node-count tradeoff (kept for when B is revisited)

`NavGridCuboidsView` holds `Cuboid[3][]` — one cache **per travel axis** (Pillar/MineDown fill Y; the
Traverse cardinals fill X and Z), because the box is *directional-maximal* (widest-orthogonal-to-travel /
shortest-along-travel), so a uniform open region is extracted **up to 3× — once per axis**. B macros only
the **primary axis** `P = argmax(|Δx|,|Δy|,|Δz|)` (tie-break `X > Z > Y` for linear-scan locality);
off-`P` movements emit the plain micro step.

**The tradeoff (why B is parked, not shipped):** off-`P` movements lose macro collapse. For canonical
cases that's right — the primary axis is the one that floods, and the orthogonal flood is held by
`GoalForcedCost` + the corridor, not by orthogonal macro-jumps. The one case that pays is an
**up-and-over** goal (climb *then* traverse): one leg goes micro → **the measured ~20× node explosion
(238 → 4674 in-game)**, which then *multiplies* the per-node cost. That multiplier is the bigger lever
than D was, but it's the reason B needs a node-count gate (`LOG_TIMING` recipe) and probably a top-2-axis
policy rather than strict primary-only. **Exempt regardless:** the once-per-search `GoalForcedCost.probe`
(6 `cuboidAt` over all axes/signs) — it must examine the goal's *forced* approach axis, which can differ
from `P`; it's not the per-node hot path.

**Not doing:** shrinking the box / capping the extract to the jump length — Steve: we need the whole box;
the jump bound is unknowable a priori and `GoalForcedCost` needs the full forced-column depth.
