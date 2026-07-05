# PERF-DESIGN: region-tier dig-through connectivity + walk-across cost

**STATUS: PROPOSED (owner design-reviewed 2026-07-05; not yet implemented or benchmarked).**

Per the CLAUDE.md performance protocol, this records mechanism + invariants + expected win + risk BEFORE
implementation. Nothing here is merged. Validation (paired JMH A/B) is gated on a new region-tier benchmark
that only runs on the **mc-1.21 era** (JMH is unavailable on the 26.x era).

---

## 1. Problem (grounded, with in-game evidence)

`/bot gather iron` routes the bot on a long winding cavern path (15 regions: down → over → up, ~30 blocks of
digging + bridging) instead of the obvious `dig down ~12 + over ~4` (≈20 blocks) straight to buried ore.

Evidence — `orebit-region-trace1.txt` (a `/bot rtrace` from region **(5,9,-1)** to buried-ore goal region
**(5,8,-2)**, which is `kind=SOLID`):

- **E2**: region **(5,9,-2)** is expanded at **g=1.0** — one cheap walk from the start, sitting **directly
  above** the goal. It emits only **5** candidates; the missing one is the **−Y face toward the goal**. So the
  cheapest possible route (walk one region over, dig straight down into the ore) is **not in the graph**.
- The goal is instead reached at **g=100.7** via a detour through (3,9,-1)…(5,8,-3).
- Nearly every lateral `walk` edge shows **cost=1.0**, so the winding air route is almost free.

Two root causes, both in `RegionPathfinder.planLevelFragments`.

### 1a. No dig-out through a face the fragment does not touch (the connectivity hole)

`RegionPathfinder.java:404`:
```java
for (int f = 0; f < 6; f++) {
    if (!uniformN && !rfN.touchesFace(fragA, f)) continue;   // ← skips the whole face
```
For a MIXED node, a face its fragment does not touch emits **zero edges of any kind** (the neighbour at
`:409-413` is never even resolved). (5,9,-2)'s air pocket does not reach that region's floor, so no dig-down
edge exists.

**Correction to earlier analysis:** the graph is NOT "fully connected for a digging bot." It DOES already
emit dig edges into uniform-SOLID neighbours (`uniformTransitCost` KIND_SOLID, `:734`) and MIXED
`mine-fallback`/`mine-solid` (`:466-484`) — **but only across faces the fragment touches.** The precise hole is
`:404`: a MIXED region cannot dig out through a face its air pocket does not reach.

### 1b. Walk edges price the boundary crossing, not the region traversal (the "1.0" bug)

The `walk` edge (`:456`) costs `walkCost(footprintCenterWorld(A,f) → footprintCenterWorld(B,oppF))`. Both
anchors sit **on the shared face plane, ~1 block apart**, so it charges the boundary crossing but **never the
cost of traversing the region to reach that boundary**. Moving within a 16-block region is neither instant nor
free; charging ~1 makes the whole open cavern near-free to wander, which is the amplifier behind the detour.

---

## 2. Node model (confirmed)

An HPA* node is **`(region, fragment)`**, not `(region)` — each 6-connected occupiable air component of a
region is its own search node (`nodes.frag[current] = fragA`; key = `fragmentKey(rx,ry,rz,frag)` XORing the
fragment id into bits 50–55). A region contributes one node per fragment (usually 1; a handful in caves; a
synthetic fragment 0 for uniform/collapsed). This is why a **per-fragment** dig-through is correct: the dig
cost depends on how far the *source fragment* sits from the face.

---

## 3. Fix 1 — full 6-connectivity via a dig-through fallback

**Mechanism.** In the `planLevelFragments` face loop, track a **register-resident coverage bitmask** (one
`int`/`byte`): set bit `f` whenever an edge is *offered* across face `f` (i.e. `relaxFrag` is called for it —
regardless of its improve/"worse" return; a worse edge still means the face is connected). After the loop, for
a `canBreak` bot, run one ≤6-iteration pass: for every **uncovered** face (and, above `OCTREE_TOP`, skipping
the pinned ±Y faces `2`/`3` exactly as `:408` does), resolve the neighbour once and emit **one** dig-through
edge.

This guarantees **every face ends with ≥1 edge** — closing not just the untouched-face hole but also the cases
where a *touched* face emits nothing today (uniform-AIR neighbour with `!canPlace` and `f≠2` at `:431`; a
no-overlap MIXED face for a no-break bot). No-break bots see **zero** fan-out change (the pass is `canBreak`-
gated) and correctly stay disconnected where they cannot dig.

**Why the bitmask (not `continue → emit`).** Emitting inline would (a) reuse `packedA = footprint(fragA,f)` =
`NO_FACE`, which `footprintsOverlap` treats as a full face → spurious `walk` edges, and (b) risk a second
emission for a face that already got a real edge. The bitmask emits exactly one dig-through per genuinely
uncovered face. Granularity is **per-expansion (per fragment-node)**: two fragments of one region that both
fail to reach a sealed face each emit their own dig-through across it — correct, because the source-distance
cost differs.

**Cost (distance-based).** Mirror the existing sibling `mineCost` but aim at the neighbour's face:
```
cost = manhattan( fragmentCentroidWorld(fragA) , footprintCenterWorld(N, oppF, NO_FACE) )
       × MINE_PER_BLOCK × hardnessFactor(rfN.avgSolidHardness())
```
Uses **our** region's hardness (the rock we tunnel through to reach the sealed face); the neighbour's own
material is charged by the neighbour's edges once we're in it. Distance-based, so a wall-hugging fragment pays
little and a far one pays more (owner's "close vs far"). Level-aware for free (all terms scale with `level` via
`sideOf`/`hScale`/`fragmentCentroidWorld`); pyramid roll-up preserves `avgSolidHardness` (`PyramidMerger:264`),
so it prices correctly at every cascade level.

**Trace check.** Stone → the (5,9,-2)→(5,8,-2) dig-down costs ≈ `~12 × 3 × 1 ≈ 36`; total `1.0 + 36 ≈ 37 ≪
100.7`. The dig route wins. Deepslate (nibble 8) ≈ 72 → still < 100.7. Genuinely hard rock legitimately loses
to wandering — ordinally right.

**Target side.** One edge into the neighbour's fragment 0 (or nearest touching `oppF`), never one-per-neighbour-
fragment. Crossing cell = `footprintCenterWorld(N, oppF, NO_FACE)` (buried when N is solid — handled in §5).

**Unbreakable rock.** `avgSolidHardness` folds in unbreakable cells (bedrock/obsidian sum 255 —
`FragmentLeafComputer:89`), so a bedrock-bearing region inflates toward "hard" but not "undiggable." Rather
than a coarse region-level unbreakable test, lean on the existing **`RegionEdgeBlacklist`** online repair
(`relaxFrag:524-528`): if the block tier cannot realize a dig, it blacklists that crossing and the region A*
reroutes. Block tier is the source of truth.

---

## 4. Fix 2 — walk-across cost (kill the 1.0)

**Why the walk edge is ~1 today (the model gap).** The `walk` edge measures
`footprintCenterWorld(A, f) → footprintCenterWorld(B, oppF)`. `footprintCenterWorld` places its point **on the
face plane** (`:803-808`, e.g. +X → `x = ox + sideH - 1`), so A's exit opening and B's entry opening sit **one
block apart across the shared boundary**, aligned (the overlap check guarantees it). So the edge charges the
**boundary crossing only** — never the traversal across the region to reach that boundary.

This is a **simplification of textbook HPA\***. Standard HPA* has two edge kinds: *intra-region* edges (walk
between two borders of the SAME region — where the traversal cost lives) and *inter-region* edges (the ~1 hop
across a shared boundary). Because an Orebit node is `(region, fragment)` — **one node per fragment, not one
per border** — there is nowhere to hang the intra-region traversal, so only the ~1 inter-region hop is charged.
Moving within a 16-block region is treated as free. That is the ~1 bug at its root; it is a real corner cut in
the model, not a tuning value.

**Three ways to re-introduce the traversal cost** (all keep the crossing CELL as the opening — the block tier
needs a real passable cell to aim at):

| Option | Cost expression | Accuracy | A\* cleanliness | Effort |
|---|---|---|---|---|
| **A. Centroid** (proposed default) | `walkCost(fragmentCentroidWorld(fragA) → fragmentCentroidWorld(fragB))` | coarse: ~one region pitch (~16) per hop regardless of entry/exit geometry | clean — edge cost is fixed (path-independent) | tiny, reuses `fragmentCentroidWorld`, no new field |
| **B. Entry→exit** (the faithful HPA* traversal) | `octile(entryOpening_A → exitOpening_A on face f) + ~1 boundary hop`, where `entryOpening_A = nodes.portalX/Y/Z[current]` (the cell we crossed INTO A through) | accurate: a corner-to-corner cross really costs ~48, a clipped corner ~2 | **mildly path-dependent** — the edge cost depends on the parent's entry opening, softly violating A*'s fixed-edge-cost assumption (the exact thing border-nodes exist to avoid). Bounded by region size; acceptable for an ordinal tier | tiny, reuses the already-stored portal cell, no new field |
| **C. Border nodes** (textbook HPA*) | true intra-region mini-search between per-border nodes | exact | clean | **large restructure** — many more nodes + intra-region pathfinds; not worth it for an ordinal tier |

**Recommendation: A (centroid) for the first pass** — smallest change, keeps the search well-behaved
(path-independent), and turns ~1 into ~16, which is all the observed bug needs. **B (entry→exit) is the more
faithful fix** and the natural upgrade *if* the centroid coarseness bites once measurable on the region JMH
bench (§7) — it needs the start node's missing entry handled (use `startFloor`/the fragment centroid for hop 0)
and accepts the path-dependence caveat. **C is out of scope.**

**Scope split (both A and B).** Only the *horizontal* term becomes honest here. The *vertical* (Δy) stays
opening/centroid-based (mid-air) — that is the deferred standable-anchor refinement (§6), not this change.

**Perf note (A).** `fragmentCentroidWorld` averages up-to-6 face centers, so it is more per-edge ALU than the
current single `footprintCenterWorld`. `fragA`'s centroid is fixed per expansion → **hoist it once** at the top
of the node expansion; only `fragB`'s centroid is per-edge. (Option B avoids this — it reads the stored entry
cell and computes one `octile`.) The JMH bench (§7) must confirm neither regresses the setup-bound
short-search scenarios.

---

## 5. Consumer handling (block tier realizes the dig)

A dig-through crossing cell is **buried in solid**. Today `PathPlan.windowTarget` would reject/relocate it:
`isUsableTarget` returns false for solid (`:1285`), and `snapInFootprint(requireStandable=true)` either returns
null or snaps to a floor on the **near** side of the wall (`:1327-1334`), defeating the dig. The block A*
*itself* can dig to a buried target (reaches within the `isGoal` ±1/±2 tolerance, prices break-through under
`canBreak` — `BlockPathfinder.java:713,938`); the gate is purely in `windowTarget`.

- **Observed bug (dig into the GOAL region):** handled with **no consumer change** — `windowTarget`'s GOAL
  branch (`:1167-1174`) already passes a buried `goalFloor` through unfiltered. A short skeleton
  `(5,9,-1)→(5,9,-2)→(5,8,-2)` digs down correctly with Fix 1 alone.
- **Intermediate dig-through (through a non-goal region):** needs a dig-tag passthrough — tag dig edges on the
  skeleton (a per-step flag threaded `relaxFrag → Nodes → reconstructFragments → RegionPathPlan →
  windowTarget`) and have `windowTarget` return a tagged dig crossing directly, like the GOAL branch. Include
  it, but it is only *exercised* once we have an intermediate-dig repro (deferred verification).

---

## 6. Deferred (not in this change)

- **Honest Δy / standable crossing anchor.** No new stored field required (correction to earlier claim): project
  the per-face footprint bbox one block into the from/to region and run the **existing** block-side standable
  scan (`snapInFootprint` already projects+scans). The deferred work is the honest-Δy *cost* term plus the
  known-hard "mid-air on the underside of a block (only reachable by falling through to the floor past it)"
  case. Chase with repros later.

---

## 7. Validation & risk

- **No region-tier JMH benchmark exists** (`PathfinderBenchmark` is block-only; `ConnectivityBenchmark` is the
  flood-fill primitive). **Prerequisite:** a new region-tier bench driving `RegionPathfinder.plan`/`planWithin`
  over representative scenarios — open-cavern, walled/sealed (dig-heavy), multi-fragment cave, long-range
  cascade — ideally via a synthetic `RegionGrid`/pre-populated `RegionFragments` seam (analogous to
  `PathfinderBenchmark`'s synthetic `NavGridView(minY, chunks)` ctor) so it runs headless. **JMH runs only on
  the mc-1.21 era**, so authoring is on `core`, running is at-home on `mc-1.21`.
- **Behavior change, not mechanical.** New edges + new walk costs change f-values and expansion order → the
  region correctness tests (`RegionPathfinderFragmentTest`, `HierarchicalCascadeTest`, `PyramidMergerTest`,
  `FragmentBuilderTest`, `HpaMilestoneTest`) will need updated expectations; update them intentionally.
- **Fan-out.** Fix 1 adds ≤6 dig edges per MIXED node (≈ doubling candidate emissions), but dig edges are
  expensive → mostly heap-resident, rarely popped; the added cost is relax/heap-push, not extra expansions.
  Bounded further by the cap-safe level selection and the fully-connected geometry making most searches
  trivial. Allocation-free (reuses `wa/wb/wc` scratch + `relaxFrag`). Must still be measured.
- **Heuristic.** Region A* is `f = g + h·hScale` at weight 1.0 (no greedy multiplier — that is block-tier-only),
  `h` already mildly inadmissible/ordinal (COST_PER_REGION=16). Expensive dig edges never make `h` over-
  estimate; the higher walk costs move `g` closer to `h`'s scale (fewer floods). Admissibility not weakened.
- **Kept only on** a targeted win (correct skeletons on the repro scenarios) with no JMH scenario regressing
  beyond noise and tests green; reverted without sentiment otherwise.

---

## 8. Implementation order (at home, on mc-1.21)

1. Region-tier JMH benchmark (instrument first; also profiles current cost sources).
2. Fix 2 (walk-across centroid cost) — measure in isolation.
3. Fix 1 (bitmask + dig-through) — measure.
4. Consumer dig-tag passthrough (§5) for intermediate digs.
5. Update region correctness-test expectations; in-game `/bot rtrace` before/after on the gather repro.
