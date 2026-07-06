# PERF-DESIGN: region-tier cost accuracy + start-fragment membership

**STATUS: PROPOSED (diagnosed 2026-07-06 via `/bot rtrace` + a new per-edge cost breakdown; NOT implemented — scoped for a fresh session).** Records mechanism + evidence + risk per the CLAUDE.md perf protocol before any code.

---

## 1. Problem (grounded, in-game)

`/bot gather` with the bot standing on top of a cave: instead of walking ~20 blocks to a drop-in
entrance and dropping into the cave (≈4 digs + a fall + a short walk), the bot **digs straight down**
(~10 digs). The region skeleton genuinely priced the dig-down route cheaper — so this is a region-tier
**cost** problem, not a bug in the block tier or the new selection logic.

Root-caused with `/bot rtrace` after adding a per-edge cost breakdown to the region trace (`traceBreakdown`
in `RegionPathfinder`, TRACE-gated). Three **independent** causes; all three contribute and all three
need fixing (fixing any one alone does not restore the walk-around).

## 2. Evidence (the rtrace breakdown)

Start `(-208,68,-240)` → buried ore `(-202,53,-226)`. Selected route = fall to the region below + a
`mine-sibling` dig. The breakdown lines (all costs are in **ticks**):

```
walk -> -13,8,-16  cost=48.4
   ~ traverse[horiz=8.0 + down=3.0(FALL 1/blk)]  +  cross[horiz=1.4 + up=36.0 (6×PILLAR 6/blk)]
walk -> -13,8,-14  cost=66.7
   ~ ...  +  cross[horiz=4.4 + up=42.0 (7×PILLAR 6/blk)]
dig-through -> -12,8,-15  cost=27   ~ span=18 blk × MINE=3.0/blk × hardness=0.5   (dirt surface region)
dig-through -> -12,7,-15  cost=66   ~ span=22 blk × MINE=3.0/blk × hardness=1.0   (stone region)
walk (down) -> frag4      cost=17.5 ~ traverse[horiz=9 + down=4(FALL)] + cross[horiz=3.4 + down=1]
```

Reads:
- **Lateral walks are dominated by a phantom `PILLAR` climb** — the 48 is only ~12 of real horizontal
  walk; the other **36 is a `6×PILLAR` climb to a mid-air opening 6 blocks up**. Going *down* is `FALL`
  (1/blk, cheap). So lateral = expensive phantom climb, down = cheap fall → the search dives.
- **Digs are absurdly cheap** — 18 blocks of dirt for 27 ticks (1.35 s), 22 blocks of stone for 66 (3.3 s).

## 3. Fix 1 — standable-Δy anchor (the §6 deferral, now biting) — BIGGEST LEVER

**Cause.** The region A* cost measures the walk Δy against `footprintCenterWorld` — the **mid-air centroid**
of the fragment's face opening (`RegionPathfinder:492,508,528,…`). Because fragments flood *air* and the
opening spans a tall air column, that centroid sits well above the walkable surface, so `walkCost` bills a
`Δy × PILLAR_PER_BLOCK (6)` climb the bot never makes on a flat walk. The standable scan **already exists**
— `PathPlan.snapInFootprint` (`:1158`), which projects a portal to a real standable floor cell (centroid
only a last-resort `CENTER` fallback) — **but it runs only on the consumer side** (`windowTarget` picking
the block-tier target). The **cost path never calls it** (no `standable` reference anywhere in
`RegionPathfinder`). So the search *prices* against the floating centroid but *executes* against the floor.

**Fix.** Anchor the crossing cell's Δy to the standable floor in the **cost** — reuse the `snapInFootprint`
projection (or an equivalent leaf standable probe) so a flat surface crossing has ~0 Δy instead of a
6-block phantom pillar. Only the vertical term needs the standable anchor; the horizontal octile term is
already honest (§4 entry→exit rework).

**Risk.** Behaviour change (f-values, expansion order) → the region correctness tests re-baseline
intentionally. Must stay cheap — the cost path is hot-ish per expansion; the standable probe should be a
bounded footprint-bbox scan (as `snapInFootprint` already is), computed once per opening, not per relax.

## 4. Fix 2 — start-fragment membership via flood-from-bot — UNBLOCKS THE WALK-AROUND

**Cause.** The start fragment is resolved by **nearest-centroid** (`nearestFragment`/`fragmentOfLevel`).
A bot at the *bottom* of a tall fragment is closer to a nearby small pocket's centroid than to the giant
mass's (high) centroid, so it is mis-assigned. Confirmed in-game: the bot was placed in a **2-block-tall
skinny air tube** that extends ±Z but is **walled in ±X** — which is exactly why the trace shows `+X` as a
`dig-through` (the tube genuinely doesn't touch the +X face), when the bot is physically in the big open-air
mass that walks +X trivially. `RegionFragments` stores only per-face footprints, not per-cell membership, so
there is no direct "which fragment is this cell in".

**Fix (owner-ratified).** At **search start** (cold), **flood the bot's local cell** in the region's
`NavSection` (a few µs — the same flood `FragmentBuilder` does), take that one component's face signature
(`faceMask` + footprint bboxes), and **match it to the stored fragment with the identical signature** (the
stored fragments came from the same flood, so exactly one matches). Cold-start only.

**Rejected: per-cell fragment labels.** A `byte[4096]`/leaf would give O(1) membership but spends real RAM
on a value read ~once per search — not worth it. The flood is cheap and exact; pay the few µs at cold start.

**Risk.** Requires the leaf `NavSection` (or its passable mask) reachable from the plan entry — verify the
seam. Match must be unambiguous (two occupiable fragments with identical face signatures is essentially
impossible from the same flood; fall back to nearest-centroid if no unique match). The per-tick membership
probe (`fragmentOf`, the driver's commit key) is a *separate* call — decide whether it needs the same
treatment or tolerates the centroid (it likely does, since it only tracks progress along a committed hop).

## 5. Fix 3 — region dig cost is tool-blind and flat

**Cause.** The region tier prices every dig as `span × MINE_PER_BLOCK (3.0) × hardnessFactor` — a **flat
constant**, ignoring the equipped tool. The **block tier already models this correctly**: `MiningModel`
computes real ticks = `ceil(destroyTimeSeconds × 20 × harvestMultiplier / toolSpeed)` from the bot's real
inventory (harvest 1.5 correct / 5.0 wrong; tool speed bare 1 / wood 2 / stone 4 / iron 6 / **diamond 8** /
netherite 9 / gold 12). So a diamond-pick bot's *estimate* at the region tier is wildly off from what it
will actually pay. The `hardness=0.5` seen in the trace is NOT a bug — air is already excluded from the
average (`FragmentBuilder:86-90` sums only solid cells / `solidCount`); 0.5 is the genuine dirt-surface
region, and the stone regions correctly report 1.0.

**Fix.** Make the region dig estimate **tool-aware / consistent with `MiningModel`** — e.g. price a region
dig off the same per-(hardness × tool-tier) tick model (using the region's `avgSolidHardness` and the bot's
tool snapshot) rather than a flat 3. Keep it a cheap closed-form (no per-block table walk at the region
tier), just calibrated to the block tier's real ticks.

**Risk.** Recalibration changes every dig-vs-walk decision → re-baseline region tests. Note the whole region
tick scale is **compressed** (`WALK_PER_BLOCK=1` ≈ 1 tick/block vs ~4.6 real; `AIR_TRANSIT_TICKS=16` for a
16-block region). If we make digs honest we must sanity-check walk/pillar/fall against real ticks too, so
the **ratios** stay right — an isolated dig recalibration on top of too-cheap walks would over-correct.

## 6. Sequencing & validation

1. **Fix 2 (fragment flood)** first — cheap, isolated, and it is a plain correctness bug (wrong start node).
2. **Fix 1 (standable-Δy)** — the biggest cost lever; de-inflates lateral walks.
3. **Fix 3 (dig cost)** — tune the dig/walk ratio last, with a whole-scale sanity pass.

Validate each with the region-tier JMH bench (`RegionPathfinderBenchmark`, mc-1.21 era) for perf and with
`/bot rtrace` before/after on the cave repro for behaviour (the breakdown lines make the deltas legible).
All three are region-tier cost/graph changes → the region correctness tests (`RegionPathfinderFragmentTest`,
`HierarchicalCascadeTest`, `HpaMilestoneTest`, …) get updated expectations intentionally, not silently.
