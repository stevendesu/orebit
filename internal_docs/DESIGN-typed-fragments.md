# DESIGN — Typed Fragments (keep-all connectivity components + 2-bit fragment types)

Status: DRAFT 2026-07-22, ratified piecemeal in owner discussion (this doc consolidates; the one
open gate is the §8 candidate census). Builds on the fc collapse sentinel (core `dc2182a`, codec
v6) and the invalidation evidence model (memory `invalidation-evidence-model`).

## §1 Motivation

Fragment ratification currently DISCARDS flooded components lacking a standable cell (the
occupiability filter), collapsing structure into blind uniform kinds: a wall-bisected air region
reads uniform-AIR with both sides "connected"; ocean/canopy leaves become fc==0 stripped mass
(349 of 20,184 L0 leaves on the gather footprint); isolated water pockets in MIXED leaves are
invisible to the region tier. The v5 `waterCount==0` classifier fix traded cost-truth for
gate-truth (a 1-water-in-4095-air leaf swim-prices). Typed fragments fix the class:

- **Fragment = maximal passable-connected component — keep ALL of them** (the flood already
  computes them; ratification changes from filter to annotation).
- **Type = 2 independent bits per fragment**, NOT a partition: media mixes live INSIDE one
  fragment. No medium-splitting — touching-media intra-region crossings stay deferred until the
  intra-region crossing model is rebuilt (currently dig-only between DISCONNECTED components).

## §2 The type bits (ratified definitions)

- **W — `hasWater`**: the component contains ≥1 water cell.
- **S — `surfaceable`**: ∃ component cell whose FOOTING is (standable floor below **OR** water at
  the cell) AND whose HEADROOM is **air-only** (not merely passable — verified defect: today's
  occupiability headroom uses `passable[]`, which includes water, so submerged floors count;
  FragmentBuilder:170-176). Dry land: floor + air above → S. Open-ocean surface: water cell + air
  above (tread and breathe, no land needed) → S. Submerged floor: ¬S. Sealed underwater pocket:
  ¬S. Pure air: ¬S.
- S deliberately distinguishes **surface water from deep water** (¬S·W = the future breath model's
  committed-transit corridor class) at zero extra storage.
- Initiation semantics (2-deep to start a sprint-swim, headroom to walk, etc.) stay at the block
  tier's (x,y,z,mode) space — region types are existence claims, path/state-dependence is not
  modeled here (same ruling as breath).

## §3 Gates and costs

Region-tier prices are RELATIVE units (walk=1.0/block), NOT ticks — the region-refined heuristic
multiplies cost-per-block downstream (owner option 1; option 2 = normalize the whole region tier
to real ticks is a possible future arc, bench-gated, NOT this increment). Sprint-swim is FASTER
than walking (5.61 vs 4.32 b/s) ⇒ swim ≈ **0.77**.

| S | W | class            | gate (no-place/no-break)                   | transit  | breath (future) |
|---|---|------------------|--------------------------------------------|----------|-----------------|
| 1 | 0 | dry terrain      | pass                                       | 1.0 walk | surfaceable     |
| 0 | 1 | submerged water  | pass (swim, any touched face incl. +Y)     | 0.77 swim| NO surface      |
| 1 | 1 | surface water    | pass                                       | 0.77 swim| surfaceable     |
| 0 | 0 | pure air         | −Y in, −Y out only; place-priced for canPlace (pillar/bridge — today's uniform-AIR gate relocated per-fragment) | fall/place | n/a |

- PRICING AMENDMENT (2026-07-23, regression-verified): `hasWater ⇒ swim-price` applies to the
  HORIZONTAL component only (0.77; sprint-swim is faster than walk LATERALLY). VERTICAL (|dy|)
  legs keep walkCost's full dy shaping — PILLAR_PER_BLOCK + the no-place UNSAFE_VERTICAL_PENALTY —
  for every fragment EXCEPT `¬S·W` (fully submerged), where vertical swim is provably honest and
  prices at its real ratio ≈ 2.2 × walk (vertical swim ~2 b/s vs walk 4.32 — SLOWER than lateral
  walk, cheaper than pillar). Rationale: W is an existence bit; discounting |dy| through a fragment
  that merely CONTAINS water deleted the vertical capability shaping and manufactured ~8×-discounted
  phantom ascents through wet caves (the cliff-repro regression — topology/gates/types verified
  identical via decoded v5-vs-v7 records; pricing was the only delta). The §3 escape hatch
  (per-fragment water-fraction nibble → weighted pricing) remains the fuller future option.
- Break-capable: sealed-face dig pricing unchanged. takesDamage: unchanged (caps fields).
- **Face footprints already answer "can we swim out face F"** for water fragments (this replaces
  the dead `waterTop` idea — fragment interiors aren't stored, so a height scalar was ill-defined
  for bowed/arched components). Residual optimism: a mixed air+water ¬S·W fragment may touch a
  face with its AIR cells only — the gate optimistically allows, the block tier refuses, the
  invalidation layer absorbs (ratified fallback). Escape hatch if it ever profiles as a real
  pathology: per-face MEDIA footprints (a later format bump).

## §4 What fc==0 means under keep-all

Stripped mass disappears (those components become real typed fragments). Remaining fc==0 =
genuinely nothing passable kept (coarse mine-through mass); fc==63 stays the CAP-COLLAPSED
sentinel (v6 rule). MAX_FRAGMENTS=62 unchanged — cap safety gated on the §8 census.

## §5 Implementation map

1. **FragmentLeafComputer**: emit a third thread-local mask `water[]` (same pattern as
   passable/standable), filled from the descriptor's fluid field; keep the existing tallies.
2. **FragmentBuilder**: occupiability filter → type computation. Per component during the flood
   (the loop already visits every cell with the right neighbors): W |= water[c];
   S |= (floorBelow(standable) OR water[c]) && airOnlyHeadroom (= passable[above] && !water[above];
   grid-top stays optimistic-open). KEEP every component (no discard); labels/footprints as today.
3. **RegionFragments**: +2 bits per fragment (storage: alongside faceMask — check packing; a
   per-fragment byte already exists for mask6 → widen or parallel array). Accessors typeS/typeW.
   KIND becomes a derived summary for uniform records (unchanged encoding); floorless
   single-component full-coverage leaves STILL emit uniform records (storage preserved for
   oceans) — derivation rule: uniform iff one component covering all passable cells AND uniform
   medium; else MIXED with typed fragments.
4. **CostCodec/CostPyramidCodec**: +2 bits per fragment on the wire, VERSION 6→7 (cache-miss
   rebuild, free while unpushed).
5. **PyramidMerger**: union-merge ORs the type bits (parent component S/W = OR of merged
   children's bits — matches the owner's merge dynamics; internal-face-only components pruned as
   today). The v5→v6-era `waterCount==0` uniform-leaf classification REVERTS to majority vote for
   the KIND label (cost fidelity restored) — gating no longer reads KIND, it reads types; uniform
   AMENDED (owner challenge, 2026-07-23 — this SUPERSEDES the earlier majority+hasWater scheme):
   the uniform fast-path is narrowed to TRULY-UNIFORM floorless leaves only — `standCount == 0 &&
   solidCount == 0 && (waterCount == 0 || waterCount == passCount)` → uniform KIND_AIR / KIND_WATER,
   6-bit record, NO vote (kind is exact by construction and IMPLIES W; S=false is genuinely correct
   for an all-water cube — its surface lives in the leaf above). Every OTHER floorless leaf (mixed
   media, or solid content like pillars/walls over void) takes the fragment path and gets EXACT
   per-fragment types: the ocean-surface leaf becomes one fragment {S=1,W=1} (water cells with
   air-only headroom = surfaceable — the breath signal is exact where it matters), and the
   pillar/wall-in-air case gets honest disjoint footprints (previously blind uniform-AIR). The
   majority vote is DELETED from the codebase; no uniform hasWater bit (header stays 6 bits).
   Storage delta: the sea-SURFACE slab + floorless-with-solid leaves fragment (typically 1-2
   components each — bounded; deep ocean and sky stay uniform). Cap impact ≈ nil (uniform children
   already contribute synthetic single items to coarse merges).
6. **RegionPathfinder**: relaxFrag/uniformTransitCost gates read fragment types instead of region
   KIND for MIXED records (per-type gate table §3); the no-place air gate becomes per-fragment
   (¬S·¬W); uniform records keep the kind-based gate. HOT PATH — type reads must be one mask test;
   bench-gated (§9). Approach enumeration/virtual goal: approaches gain type-awareness only via
   the same gates (no new special cases). describeStartEdges post-mortem prints types.
7. **InvalidationRollup**: constituents unchanged (footprint overlap); no type interaction in v1.

## §6 Interactions

- Invalidation evidence model unchanged — typed gates REDUCE false optimism (fewer wrong
  crossings offered ⇒ fewer invalidation cycles), never expand it.
- Entry-face invalidation keys (ratified, sequenced AFTER this): benefits from water fragments
  behaving correctly for the ravine fixture; both touch relaxFrag/blacklist sites — sequencing
  avoids double churn.
- Capability-aware flood graphs (future): typed fragments are the annotation-level convergence
  toward them; symmetric flood stays ratified.

## §7 Tests

FragmentBuilder: keep-all (wall-bisected air leaf → 2 typed ¬S·¬W fragments with disjoint
footprints); S-bit truth table (dry floor+air=S; water+air above=S; submerged floor=¬S; sealed
water pocket=¬S·W; canopy air=¬S·¬W); water mask seam; type OR-merge at coarse; codec v7
round-trip (types survive); gate tests per row of the §3 table (no-place refused through ¬S·¬W
but passed through ¬S·W laterally AND upward via touched face; place-bot passes ¬S·¬W at place
pricing); census harness re-run for the new fragment-count distribution; existing suites (the
RegionWaterGateTest v5 tests updated to the typed model — fixtures, not weakened assertions).
End-to-end: cliff PASS + restart oracle PASS + wall blame-targets unchanged.

## §8 Cap-safety gate (census) — MEASURED 2026-07-23, AWAITING OWNER RULING

Candidate-level census (temporary instrumentation, ~10-11k leaf builds + full merge counts per
run, two terrains, baseline + keep-all-simulation runs; behavioral sanity: gather cliff PASSed
under keep-all, jungle canopy stall unchanged). Over-cap% = merges whose PRE-cap candidate count
exceeded 62 / max = largest candidate count seen:

| tier | gather base | gather KEEP-ALL | jungle base | jungle KEEP-ALL | verdict |
|------|-------------|-----------------|-------------|-----------------|---------|
| L0   | 0% / 16     | 0% / 16         | 0% / 24     | 0% / 24         | SAFE    |
| L1   | 0% / 16     | 0% / 25         | 0% / 37     | 0% / 37         | SAFE    |
| L2   | 0% / 46     | 0.09% / 72      | 0% / 31     | 0% / 51         | MARGINAL (rare brushes) |
| L3   | 6.2% / 106  | 22% / 187       | 2.6% / 91   | 12.4% / 132     | collapse-prone TODAY |
| L4   | 5.4% / 101  | 19% / 117       | 32% / 178   | 46% / 187       | collapse-prone TODAY |
| L5   | 5.9% / 104  | 7.3% / 106      | 18.6% / 97  | 22% / 109       | collapse-prone TODAY |
| L6   | (inputs suppressed by upstream L5 collapses — max pinned at 62; not independently meaningful) |

KEY FINDINGS: (1) the tiers the block tier actually leans on for precision (L0-L2, the
window/near-field levels) are SAFE-to-marginal under keep-all; (2) spongey-cheese collapse at
L3-L5 is NOT a rare pathology — it happens at 3-32% of merges TODAY, before keep-all, and
keep-all roughly doubles-to-quadruples it; (3) the collapse cap therefore functions as the de
facto coarse abstraction policy already.

RULING (owner, 2026-07-23): proceed with typed fragments; collapse semantics UNCHANGED — the cap
is not merely a count-field limit (more fragments = linear storage growth + more region-A* work
per relax), and coarse collapse is the deliberate "at a coarse enough level, Minecraft is always
pathable" optimism. Max observed candidates (~187) fitting 8 bits is noted but a widening would be
reconsidered only with the face-bitmap arc. Original recommendation retained below for context:
proceed with typed fragments; L3+ collapse semantics
UNCHANGED (it is already the norm there; collapsed = optimistic mass, block tier remains the
source of truth); the real coarse-tier fix is the owner's face-connectivity-bitmap idea
(future-work "collapsed-MIXED" item) as its OWN follow-on arc — typed fragments neither depend
on it nor make it harder. Alternatives considered: field-width increase (rejected-by-default:
fragment ids are 6-bit in every key — deep surgery); merge-degradation heuristics (component
merging under pressure — lossy, unprincipled without the bitmap model).

## §9 Bench gates

ConnectivityBenchmark (flood cost — keep-all adds footprint extraction for previously-discarded
components), ChunkBuildBenchmark shapes (leaf+rollup), RegionPathfinderBenchmark full set,
PatchStormBenchmark (grid-maintenance regression guard), paired mode-matched A/B per protocol.
Region A* per-relax budget: type read = one mask op; no allocation.
