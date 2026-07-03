*Implemented design — the fragment model described here is now the **only** region-tier node model; it shipped
unconditional (s36 cleanup, commit eed70b2). The `HPA_FRAGMENTS` A/B flag this doc specifies (§8 slice S6, §9.5,
§10) was **never wired for an in-game A/B**: the fragment model replaced the old center-node model directly, and
the flag/dispatch mechanics (plus the `HIERARCHICAL_CASCADE` sibling) no longer exist. The body below is accurate
to the shipped code — `RegionFragments`, `FragmentLeafComputer`/`FragmentBuilder`, and the fragment-aware
`RegionPathfinder`/`RegionPathPlan` (per-step `fragmentId` + `portalCell`). Only the S6 flag-gated rollout was not
executed as written.*

# HPA\* Fragments — connectivity-aware region tier

> **Status:** ratified design, now implemented and unconditional (see the historical note above). **Supersedes** the center-node model in
> `HPA-IMPLEMENTATION.md` §3a (store), §5 (leaf cost), §6 (defaults), §8 (region A\*), §9 (window/corridor).
> The fixed cubic-grid implicit-octree addressing (`RegionAddress`), the SoA/alloc-light house style (§14),
> and the scale-guard coarse branch (§8) are **retained**. Authored on `core`.

## 1. Why

The current region tier represents each 16³ region by **one center node with six face→center costs**
(`CostPyramid`, `LeafCostComputer`, `RegionPathfinder`). That representation has two fatal degeneracies in
carved terrain, both observed in-game:

1. **Buried/mid-air targets.** `PathPlan.windowTarget` projects the far region's geometric **center** to a
   floor. When the region is mostly solid (a cave air-pocket between two regions whose centers are in rock),
   the projection lands on an unreachable cell, and the block-A\* beelines at it.
2. **False connectivity → oscillation.** A single center assumes all six faces interconnect through it. Two
   disjoint tunnels threading one region collapse into one "crossable" node; the skeleton promises a crossing
   the block tier can't realize, the window won't commit, and the bot **bounces A→B→A→B** between partial
   replans chasing an unreachable target.

Both are the same root cause: **center-as-node is lossy and geometrically wrong.** The fix is the classic
HPA\* entrance/cluster model, adapted to our cubic grid and made cheap by a 6-connectivity approximation.

## 2. The model

**Abstract node = `(region, fragment)`**, where a *fragment* is one **6-connected component of the region's
occupiable cells**. A region contributes one node per fragment — usually **1** (open terrain or solid rock),
a **handful** in caves.

- **Same fragment → cheap-pathable** (move within it, no mining).
- **Different fragments → expensive-pathable** (mine through the wall between them).
- The graph is therefore **always fully connected** — a sealed region just routes through an expensive mine
  edge, correctly modeling "you must dig." There is no disconnected FAIL. (Concern: *all moves possible.*)

### 2.1 Edges (computed at query time, not stored)

During region A\*, a node `(N, fragA)` expands to:

- **Portal edges (cheap, inter-region).** For each face `F` that `fragA` touches, enumerate the neighbor
  region `M`'s fragments touching the opposite face `opp(F)`; if their **face footprint bboxes overlap** (2D
  interval test), emit an edge to `(M, fragB)`. Footprint overlap is what makes "two tunnels exit the same
  face" route correctly — no face-level false positive.
- **Mine edges (expensive, intra-region).** To every *other* fragment `fragC` of the same region, emit an edge
  (dig through the wall between two tunnels). Cost is **derived** from `avgSolidHardness` × a ~half-region span
  (§2.2). The hardness scale makes a dirt region dig cheaper than a stone one — reusing the existing
  `LeafCostComputer.solidTunnelTicks` model; `avgSolidHardness` (mean quantized hardness over the region's
  **solid** cells — air is 0 and excluded) is tallied **for free** in the flood scan.
- **Solid-neighbor mine edges.** A neighbor region with no fragment touching `opp(F)` (solid there) is reached
  by mining. Solid/air/water regions are not fragments at all — they are a region **`kind`** (§2.3) handled
  uniformly, folding in today's all-solid / all-air / all-water fast paths.

No edge costs are stored — every edge is **derived per expansion** from the two regions' fragment footprints +
universal constants + `avgSolidHardness` (§2.2), exactly as today's `faceCost(N)+faceCost(M)` is derived.
(Concern: *reading stays cheap* — O(fragments) per expansion, a few octile ops, no per-cell work, no boxing.)

### 2.2 Costs are DERIVED, not stored (the key simplification)

There are **no per-face cost buckets.** An edge cost is a pure function of geometry we already store + a few
universal constants, computed at query time. Between an entry footprint `p` and an exit footprint `q` (both on
faces of the fragment being crossed):

```
walk(p→q)  =  octile(dx,dz) × WALK         +  (Δy>0 ? Δy × PILLAR : |Δy| × FALL)   // diagonal walk allowed
mine(p→q)  =  (|dx| + |dy| + |dz|) × MINE_PER_BLOCK × (avgSolidHardness / STONE_REF) // grid-aligned breaks → MANHATTAN
```
The cost geometries differ: you may **walk** a diagonal (octile), but you **mine** one axis-aligned block at a
time, so a dig of N blocks is the **Manhattan** span, not the Euclidean one.

- **The fall-cheap / pillar-dear asymmetry is recovered from the Δy term**, not from stored directional
  buckets: going up applies the dear `PILLAR` constant, going down the cheap `FALL`. This is the
  `fillAirFaces` insight generalized — universal constants × the footprints' Y-gap. (Conservative: it charges
  `PILLAR` for an upward transit even through a fragment that has a cheap ramp; the block tier finds the ramp
  and walks it within the corridor, so over-charging at the region tier only biases *against* needless
  climbing — the safe direction, and the anti-"fly-up" fix.)
- This drops `LeafCostComputer`'s per-face bounded-A\* **and** the stored enter/exit buckets entirely —
  replaced by flood-fill (~13 µs/leaf, §4) + the formula above. (Concern: *computing stays cheap* — net
  **faster** than today's ~60 µs/leaf.)

Only **ordinal** correctness is needed (the block tier refines the real dig/ramp point), so footprint-geometry
costs suffice; they only must rank genuinely-cheaper macro-routes correctly.

### 2.3 Region kind (uniform regions store no fragments)

A region with no occupiable fragment is one of three uniform kinds, carried in 2 header bits — folding in
`LeafCostComputer`'s existing fast paths:

- **SOLID** — mine straight through; cost `MINE_PER_BLOCK × (avgSolidHardness / STONE_REF)` per block, symmetric.
- **AIR** — floorless: the one-way **down chute** (fall in/out cheap via the `FALL` term, pillar up dear via
  `PILLAR`) — the `fillAirFaces` directionality, now from the formula, no stored buckets.
- **WATER** — symmetric swim cost.

**MIXED** is the only kind that carries fragment records.

## 3. Connectivity: 6-connected flood fill + occupiability + cap

`(x+y+z)%2` air would create 2048 singleton "fragments" — but **none are navigable**. The membership rule
that makes the model robust:

1. **Flood fill** (BFS) the region's **passable** cells, 6-connected → raw components. (Flood beats
   union-find ~2.2× on the common large-component case — see §4.)
2. **Occupiability filter:** drop any component with **no standable floor that has ≥2-tall headroom** (≥1 for
   crawl/swim). The checkerboard → 0 fragments → uniform mine-through region. Principled (a real movement
   fact), not a tuned size threshold. Speckle/gravel noise collapses the same way.
3. **Hard cap** `MAX_FRAGMENTS_PER_REGION` (63, 6-bit id — sized to what the `G` bound admits, §3.1; the leaf
   rarely exceeds ~15 but coarse `G=4` regions can carry more). Over cap → collapse the region to a **passability-
   weighted coarse mass** (a uniform kind whose crossing cost = `AIR_TRANSIT·passFrac + rockMine·(1−passFrac)`
   — `LeafCostComputer.mixedDefaultTicks`, so a more-open mass is cheaper to cross; this is why `passFrac` is
   stored alongside `avgSolidHardness` — air-fraction drives the *crossing* cost, solid-hardness the *mine-edge*
   cost). Bounds storage **and** abstract-node count under any adversarial terrain. **Safe because the block
   tier is the source of truth** — a capped region only yields a looser
   corridor, never a wrong answer. (Concern: *storage must stay bounded.*)

The 6-connectivity ≈ movement-connectivity gap (a vertical air shaft is one component but up≠down) is
**carried by the directional cost**, not by splitting the component — membership stays a cheap flood fill.

### 3.1 Per-level quantization `G` — the principled bound on coarse fragment count

The cap (above) is a safety net; the real bound is **resolution**. Openings-per-face scale with face **area**
(S²), so without bounding, every coarse region drowns in small-cave noise and collapses to "solid-ish" —
losing exactly the macro-routing we want (*follow the river, don't hop the ravine*: river, ravine, and flat
ground all collapse to the same mush). The fix: **flood-fill a `G(level)³` grid**, where `G` is the per-level
face/volume quantization:

- **`G = 16` at the leaf** — the real voxels, exact connectivity (where the bounce/cave bugs live and must
  stay precise). The leaf is just the `G=16` case, not a special algorithm.
- **`G` shrinks at coarse levels** (e.g. 4, then 2) — a parent floods a small downsampled `G³` grid built from
  its children; each child fragment's **bounding box maps it into the parent's super-cells** (the bbox is the
  downsampler). ≤ `G²` openings per face by construction.
- **The old face-to-center model is exactly `G = 1`** — so center-model and full-fragment are two ends of one
  `G`-continuum, and we tune `G` per level. This is what lets a large feature (a river) survive coarsening as
  **one fragment spanning the region** instead of dissolving into bank-noise → collapse.

**Caveat:** quantization adds *optimistic* false-connectivity at coarse levels (two near-but-unconnected
openings sharing a super-cell read as connected). Acceptable — coarse is approximate, the bot refines on
approach and replans (like the optimistic-default for unexplored terrain), and it does **not** reintroduce the
bounce, which is a level-0 phenomenon where `G=16` keeps connectivity exact. The `G`-schedule and whether the
coarse footprint is a quantized bbox or a small `G×G` bitmask are **S5 tuning**, set by the fragment-count
instrumentation. S1–S4 (leaf / milestone) are unaffected — the leaf is already bounded by `G=16` +
occupiability.

**Schedule guidance (S5).** Prefer a **constant** coarse `G` (a clean 2× octree mipmap, where each parent
super-voxel = a 2×2×2 of child cells — a *decreasing* `G` means steeper 4×/axis downsample steps) over a steep
taper. The intuition "more resolution near the bot" is a red herring: near = level 0 (`G=16`), refined by the
sliding window regardless of schedule; `G` only sets *far/medium*-field resolution. And **don't splurge `G` at
low levels**: storage is dominated by the lowest stored level (L1 has ~1/8 the regions of leaves while `G³`
costs 8× per step, so `G1=8` ≈ 8 bytes/leaf-equiv vs `G1=4` ≈ 1), *and* L1 is merge-only in the current coarse
branch (the direct branch covers ≤4096 blocks at level 0; the coarse branch's minimum chosen level is L2). So a
reasonable default is `16 → 4 → 4 → 4 → 4 → 2 → 2…`; if medium-range routing tests poorly, raise L2–L3 (cheap —
few regions), never L1. Final values are S5 tuning.

## 4. Connectivity benchmark (decided)

`ConnectivityBenchmark` (JMH, mc-1.21, `src/test/.../worldmodel/hpa/`), µs/op over a 16³ grid:

| scenario | flood | union-find |
|---|---|---|
| OPEN (1 comp) | **13.3** | 29.2 |
| HALF (2 comps) | **12.2** | 26.4 |
| SPECKLE (noise) | 43.6 | **22.9** |
| CHECKER (2048 singletons) | **9.0** | 11.5 |

**Decision: flood fill at the leaf.** Real terrain is OPEN/HALF-shaped (flood 2.2× faster); the case flood
loses (random noise) is exactly what the occupiability filter strips. Union-find is retained for the **pyramid
merge** (§7, Slice 5), where it operates over ~tens of fragments and is the natural primitive. (If in-game
profiling later shows fragmented terrain dominating leaf build, UF is a drop-in swap — same interface.)

## 5. Storage schema

A region record is **variable-length and NOT byte-aligned** — on disk it is a `CostCodec` bitstream (a
sub-byte packer already, like today's nibble-packed buckets), so a 10-bit header just flows into the fragment
bits with no padding; in RAM it is unpacked convenient fields (favour-cpu-over-ram, like `CostPyramid`'s
not-bit-packed 12-byte rows). The bit counts below are **information content / disk size**, not a byte layout —
exact bit-vs-nibble granularity is a `CostCodec` detail settled in S2. Replacing today's 12 face-bytes:

```
region {
  kind             : 2 bits     // MIXED | SOLID | AIR | WATER  (single source of truth; region-level)
  avgSolidHardness : 4 bits     // mean hardness bucket over SOLID cells — the MINE-EDGE cost (dirt<stone)
  passFrac         : 4 bits     // passable-cell fraction — the COLLAPSED/uniform CROSSING cost (more air = cheaper)

  // --- uniform kinds (SOLID/AIR/WATER) STOP HERE: kind ⇒ zero fragments, so NO fragmentCount, NO fragments.
  //     A uniform region is just kind(2)|hardness(4) = 6 bits. ---

  fragmentCount : 6 bits        // MIXED only — present iff kind == MIXED. 1..63 = real fragments;
                                //   0 = COLLAPSED (over-cap): no fragment records, cross-cost from passFrac mass.
                                //   6 bits (not 4) so the cap matches what G admits: a G=4 coarse level can hold
                                //   ~32 quantized components, which a 4-bit cap (15) would collapse prematurely.
                                //   Leaf rarely exceeds 15; the extra range is ~free (bit-packed) and helps caves.
  fragment[fragmentCount] {
    faceMask : 6 bits           // which of the 6 faces this component reaches
    per set face f {            // iterate faceMask; one footprint per set bit
      footprint : 2 bytes       // 2D bbox on f's two in-face axes (min/max × 2, 4 bits each):
                                //   • portal connectivity test (interval overlap with the neighbor's footprint)
                                //   • derives the transit cost (footprint→footprint geometry, §2.2)
                                //   • derives the window-target portal cell (overlap center — an on-face 8-bit point)
    }
  }
}
```

**No stored costs** (derived, §2.2), **no interior rep**, **no per-fragment kind** (it is a region property —
duplicating it would be a second source of truth admitting invalid mixed/uniform regions). A fragment is
`faceMask` (6 bits) + 2 B per touched face.

Per-region size: uniform (solid/air/water) = **6 bits**; open MIXED (1 fragment, ~5 faces) ≈ ~13 B; typical
cave (2–4 fragments, ~2 faces each) ≈ 12–25 B; capped pathological ≈ ~40 B. Per chunk (~24 leaves) ≈
0.3–0.6 KB — vs the naïve per-cell 36.8 KB.

**Roll-up (levels ≥1) uses the IDENTICAL schema.** The implicit octree is uniform — a level-L node stores the
same `kind | avgSolidHardness | fragment[]{faceMask, footprint}` a leaf does, so the region A\* reads every
level through one set of accessors. The only differences are semantic + computational: a level-L fragment is a
**connected group of child fragments** (not cells); its footprints are on the coarse outer faces in
**face-relative units** (always 16 buckets/axis → `2^L` blocks/bucket), so the record size is level-independent;
and it is produced by the merge (S5) — **union-find over the 8 children's fragments** (connect two when adjacent
children's footprints overlap on the shared internal face), projecting each component's outer-flush child
footprints onto the parent face. No child-fragment pointers or connectivity graph are stored (child membership
is transient; refinement re-plans at the finer level — §6.5). This replaces today's `combineNode` min-crossing
operator, which is lossy in the same way the leaf center-model is (it assumes every face connects through the
parent center — the coarse-scale bounce).

**Persistence:** leaf fragments are **recomputed on load** from the resident nav grid (flood fill is cheap →
zero leaf disk, matching the "nav grid recomputed = 0 disk" budget). Only merged levels ≥1 persist.

## 6. The two bugs, fixed

- **Buried target →** `windowTarget` returns the **portal cell** between the window's committed fragment and
  the next skeleton fragment (a real occupiable boundary cell), not a center projection. Reachable by
  construction.
- **Bounce →** consecutive skeleton nodes are genuinely-connected fragments (cheap portal) or a known mine
  edge (expensive but real), so each replan's target corresponds to a reachable cell; the partial-vs-partial
  limit cycle has no source.

## 6.5 Mutations & propagation (dynamic updates)

The maintenance machinery **already exists** (`HpaMaintenance` + `PyramidMerger`) and is unchanged — fragments
only change what `computeLeaf`/`mergeUp` *produce*, not the dirty→flush→propagate flow.

- **Leaf: recompute-total, never incremental.** A passability-changing edit marks its containing leaf dirty
  (`HpaMaintenance.onBlockChanged` — deduped per-tick, thread-safe, budgeted to `MAX_LEAVES_PER_TICK`); the
  flush re-runs the leaf's **flood fill from scratch**. Flood is **symmetric** — a merge (break the last
  separating block) and a split (place a wall) are the *same* op, "recompute the components" — so there is no
  per-cell fragment label, no merge-vs-split detection, no incremental union/split. ~13 µs, one reflood per
  dirty leaf per tick regardless of how many blocks changed in it.
- **Propagation: recompute-from-children, never mutate-in-place.** `PyramidMerger.mergeUp` recomputes each
  ancestor as a **pure function of its current children** (a *fresh* small union-find over the 8 children's
  fragments), bottom-up, **stopping the moment a level's output is unchanged** (the damping that keeps most
  edits off the top). This is why "union-find can't split" is a non-problem: union-find is a transient batch
  tool *inside each `mergeUp` call*, rebuilt from children every time — never a persistent structure to split.
  A leaf going 4→3 fragments isn't diffed; the parent is rebuilt from children that now show 3.
- **No stored connectivity graph.** The only persisted state is the per-region fragment footprints (§5); edges
  are derived at query time (§2.2). A mutation touches *only* fragment rows — no edge structure to keep
  coherent, no costs to invalidate.
- **Cross-leaf edits need only one leaf.** A boundary block changes only *its* leaf's footprint; the inter-leaf
  portal is re-derived next query from this leaf's new footprint vs the neighbor's existing one.
- **Level-0 (the milestone) needs no propagation.** The direct branch reads only leaves, so a mutation there is
  just the reflood — ancestor propagation rides with the deferred S5.

## 7. Integration (per file)

| File | Change |
|---|---|
| **`LeafCostComputer`** → `FragmentLeafComputer` | flood-fill components, occupiability filter, cap/degrade; classify region `kind` + `avgSolidHardness`; for MIXED, extract per-(fragment,face) footprint bbox. Drops the bounded-A\* per face **and** all stored face costs. |
| **`CostPyramid`** | row = `kind` + `avgSolidHardness` + (MIXED) variable fragment list (§5), instead of 12 face-bytes. New read API: `kind(level,r)`, `avgHardness(level,r)`, `fragments(level,r)`, `faceFootprint(level,r,frag,face)`. |
| **`CostCodec`** | extend on-disk (un)packing for the region header (kind+hardness+count) + per-face footprint nibbles. |
| **`RegionGrid`** | `ensureLeaf` builds fragments; expose kind/hardness/fragment/footprint accessors + default-on-miss (optimistic uniform kind). |
| **`RegionPathfinder`** | node key = `packLevelKey(region) ⊕ fragmentId` (4 bits); edge costs **derived** (§2.2): portal edges (footprint overlap on shared face) + sibling mine edges + uniform-kind transit. `RegionPathPlan` carries fragmentId + portal cell per step. |
| **`RegionPathPlan`** | add per-step `fragmentId` + `portalCell` (derived overlap center). |
| **`PathPlan`** | `windowTarget` = committed edge's portal cell; commit/wiggle keyed on `(region,fragment)`; corridor AABB may stay region-cube (fragments live inside it). |
| **`PyramidMerger`** | parent fragments = UF over child fragments unioned across shared internal faces; aggregate child footprints onto parent outer faces; recompute-from-children (pure), early-out when unchanged. **(Slice 5, deferred — only the coarse branch needs it.)** |
| **`HpaMaintenance`** | **unchanged** — the dirty-mark / debounced-flush / recompute-leaf-then-merge-ancestors scaffold already implements the mutation model (§6.5); it just calls the new `computeLeaf`/`mergeUp`. |

## 8. Implementation slices (workflow DAG)

```
S1 FragmentLeafComputer ──▶ S2 CostPyramid/Codec store ──▶ S3 RegionPathfinder ──▶ S4 PathPlan driver
                                     │
                                     └──▶ S5 PyramidMerger (deferred; parallel after S2)
S6 Validation/A-B  (cross-cutting, flag-gated)
```

- **S1 — Fragment leaf computation.** Pure, unit-testable on synthetic grids (reuse `ConnectivityBenchmark`
  fixtures + cave/canopy/checkerboard). **In:** a `NavSection`/occupancy mask. **Out:** a `RegionFragments`
  value (list of fragments with face footprints/costs + reps). **Accept:** correct component count post-filter
  (checkerboard→0/solid, 2-tunnel→2, open→1); ≤ cap; ~13 µs/leaf (JMH).
- **S2 — Store + codec.** Fragment list in `CostPyramid`, (un)pack in `CostCodec`, `RegionGrid` accessors +
  default. **In:** S1 output. **Accept:** round-trip pack/unpack identity test; default-on-miss returns an
  optimistic uniform kind; alloc-free reads.
- **S3 — Region A\* over fragments.** **In:** S2 grid. **Out:** fragment-aware skeleton. **Accept:** on the
  sealed cave, skeleton uses a mine edge (no FAIL); on a cave-with-exit, skeleton routes through the open
  portal (not straight up); trials-chamber skeleton unchanged in quality.
- **S4 — Driver.** Portal-cell window targets + (region,fragment) commit. **Accept:** in-game cave mine-up
  reaches goal; **no A→B bounce**; trials-chamber unchanged (the regression guard).
- **S5 — Merge (deferred).** UF child→parent fragments. **Accept:** coarse-branch long walk (> `LEVEL0_DIRECT_CAP`)
  still reaches goal; merged costs admissible.
- **S6 — Validation/A-B** *(design intent, NOT executed as written).* This slice specified a `HPA_FRAGMENTS`
  flag toggling fragment vs center model for an in-game A/B (cave mine-up + bounce, trials-chamber, canopy; JMH
  leaf compute flood vs bounded-A\*). In practice the fragment model was cut over **directly** and the center
  model + flag were deleted (s36); no flag-gated A/B ran. The validation *scenarios* (§10 matrix) remain the
  behavioural acceptance criteria — they were checked against the unconditional fragment model, not across a flag.

## 9. Decisions — recommended defaults (confirm before S1)

1. **Footprint precision:** 2D bbox per (fragment,face) (2 B, overlap test may false-positive on an L-shaped
   opening). *Default: bbox.* Alternative: run-length spans (precise, variable size) only if the block tier
   shows entry-point churn.
2. **Cap policy when exceeded:** merge-smallest-first vs collapse-to-one-coarse-node. *Default:
   collapse-to-one* (simplest; rare; block tier covers it).
3. **Mine-edge cost shape:** derived (§2.2) — ~half-region span × minePerBlock × **region-average solid
   hardness** (dirt digs cheaper than stone; the `avgSolidHardness` nibble is tallied free in the flood scan,
   reusing `solidTunnelTicks`). No interior rep stored. *Default: region-average.* Upgrade to line-sampled
   per-pair hardness only if the block
   tier is seen digging through a misjudged-cheap wall.
4. **Leaf persistence:** recompute on load (zero leaf disk). *Default: recompute.*
5. **Ship S1–S4 first; defer S5.** The milestone uses the direct level-0 branch; the coarse branch (and thus
   the fragment merge) is only needed for >4096-block reach. *Default: yes, defer S5.*

## 10. Validation matrix

| case | current model | target (fragments) |
|---|---|---|
| sealed cave, owner above | mine-up works (post tool-fix) but center target buried | reaches goal, target on portal |
| cave with a natural exit | beelines up | routes through the open portal |
| skinny connected cave | **A→B bounce** | smooth traversal, no oscillation |
| forest canopy (standable leaves) | mid-air pillar (canopy bug) | portal target on real ground |
| trials chamber (open maze) | **excellent** (must not regress) | unchanged |
| checkerboard / speckle | n/a | 0 fragments → uniform mine-through, bounded |
