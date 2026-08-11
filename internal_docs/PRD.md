# Orebit — Product Requirements Document

> **Status:** Foundational design, ratified 2026-06. Covers the world-model and
> pathfinding foundation. Upper layers (LLM intent, AI/task execution, behavior,
> memory, social) are scoped but deferred to later PRDs.

## 1. Vision

Orebit is a **server-side Minecraft mod** that spawns AI-driven **ally bots** —
virtual players that follow and help their owner. The goal: solo players don't feel
alone, newcomers get a capable guide, and players who enjoy only one facet of the
game (building, fighting, exploring) get help with the rest. Long-term, a **weak
local LLM** translates a chat sentence into a bounded command set; **the LLM only
recognizes intent — it never plans.** All planning is deterministic.

**Loader / version are not architectural constraints.** The *current build* covers Minecraft
1.17.1 → 26.2: **Fabric** across the whole range, **Forge** 1.17.1→1.21.11, **NeoForge**
1.21→1.21.11.

The design keeps Minecraft- and loader-specific surface deliberately
thin so this expansion is a matter of adapters, not rewrites (see §9 Portability).

This PRD covers the load-bearing foundation everything else needs: perceiving the
world efficiently (the **world model**) and moving through it intelligently (the
**pathfinding engine**).

## 2. Current state (honest baseline)

> **Updated 2026-07.** The 2026-06 baseline below described a broken pipeline; that phase is over.

The navigation stack described by this PRD is now **built and runtime-verified**: the
world-model pipeline is live (`NavSectionBuilder.classifyInto` → `ChunkNavLoader` →
`NavStore`, patched incrementally by `NavGridUpdater`), the block tier
(`pathfinding/blockpathfinder/` — **18** movements, folded edits, macro cuboids, the
goal-forced-cost premium, partial paths with the irreversibility guard) and the region
tier (`worldmodel/hpa/` fragments + `regionpathfinder/` stateful nested-skeleton
cascade) both run in-game, window searches run **off-tick** on the `pathfinding/async/`
planner pool, and the follower executes plans reactively (MovePlan/
PhaseRunner phases, timed `BotMining`, nether-portal follow, full vanilla player tick
with config-gated survival). See §10.A for the completed inventory.

The resource layer (§6.4) and persistence (§6.6) are **also built** since this note was
written: `worldmodel/resource/` (`ResourcePyramid`/`ResourceQuery`/`ResourceScan`, tallied
during nav-grid classify) and `worldmodel/persistence/` (`RegionPersistence` + the
`.mca`-style sharded cost/resource files). What remains **stub-only** is the agent brain
above navigation: `ai/`, `tasks/`, `integration/` (LLM), `memory/`, `relationships/`,
`behavior/`, `sim/`. The bot today is a capable follower/goto agent that has grown a first
set of tasked actuators — `/bot come|follow|goto|stay`, `/bot mine|gather|find|drop|report`,
plus the `craft`/`farm`/`build`/fight actuators — driven imperatively from `AllyBotEntity`,
not yet by the designed goal/task engine.

## 3. Scope

**In scope (this PRD):** the world model (NavBlock, nav grid, fixed-grid regions,
resource octree, HPA\* graph), persistence/disk budget, and the two-tier
pathfinding engine (movement vocabulary, cost model, heuristics, execution).

**Out of scope (later PRDs):** LLM intent recognition, the AI state machine / task
system, behavior profiles, memory/relationships, commands, and the bot-lifecycle
refactor (`manager/`/`agent/`/`data/`). These consume the foundation defined here.

## 4. Design principles

From `design-principles.txt` plus decisions made during design review:

- **Determinism over black boxes.** LLMs recognize intent only; all plans are
  built deterministically. No reliance on RNG in core algorithms.
- **Server-side only.** Vanilla clients can connect with no install; all AI,
  physics, and simulation run server-side.
- **Performance and memory are features.** Bit-packing, object pools, statically
  sized structures, cache-friendly layouts. Target: no perceptible FPS impact on
  modest hardware (originally validated on a 2021 M1; do **not** lean on fast
  hardware — budget against Minecraft's own per-chunk cost, not absolute ns).
- **Recompute cheap data; persist only expensive/global data.** (See §7.)
- **Smart objects, pluggable strategies.** Movement types, heuristics, and decay
  strategies are extensible via interfaces, not branched enums.
- **Note:** some existing code contradicts these. The nav/analyzer drift is resolved
  (`TraversalClass`/`TraversalAnalyzer*` deleted), but the static `BotManager` and the
  `final` classes are still there — and `BotManager` is now load-bearing (world-save bot
  registry, cross-dimension restore), so this is a standing, accepted deviation rather than
  a pending cleanup.

## 5. Architecture overview

```
chat ─(LLM intent, later)─▶ goal ─▶ task ─▶ PATHFINDING ─▶ VirtualPlayerController
                                              │
                            ┌─────────────────┴───────────────────┐
                            ▼                                     ▼
                   RegionPathfinder (coarse,                BlockPathfinder (fine,
                   HPA* over persisted graph)               per-region A* on nav grid)
                            │                                     │
                            └──────────────  WORLD MODEL  ────────┘
        NavBlock (per-BlockState behavior table) · nav grid (recomputed) ·
        fixed-grid regions · resource octree · HPA* graph (persisted)
```

Two resolutions, each doing its job: a **persisted coarse layer** (HPA\* + resource
octree) for whole-world planning, and a **recomputed fine layer** (per-block nav
grid) for precise movement near the bot.

## 6. The world model

### 6.1 NavBlock — per-BlockState behavioral fingerprint

A `NavBlock` captures everything pathfinding needs about a block: standing height,
solid faces, climbable, fluid, gravity, damaging, slowing, replaceable, hardness,
best tool, tool-required, directional, waterloggable, and the door/openable
affordances.

- **Identity is per-`BlockState`, not per-`Block`.** Orientation changes navigation
  (north-facing stairs are ascendable from the south, not the north); growth stages
  change passability. **Built as specified:** `NavBlock` walks every block's
  `getStateDefinition().getPossibleStates()` at static init and interns each state into
  `STATE_TO_NAVTYPE`, so facing and growth stage fall out of the fingerprint for free.
- **Index → packed descriptor table (the chosen model).** Each block state maps to a
  **`short`** navtype index; the index resolves to a **single 64-bit packed `long`**
  holding all the fields above (one array read + bit-extract — no objects, no pointer
  chasing). Behavioral-equality dedup is lossless (distinct *behavior*, not lumped).
- **Measured (`profile.BlockStateDedupTest`, 2026-06):** the 27,866 block states
  collapse to **587 distinct fingerprints** (576 quantized) — a **48× collapse**.
  *(A DATED READING, deliberately preserved as the record behind the sizing decision —
  NOT a live figure. The count moves with the MC version and at runtime with
  `mining.protectedBlocks`; never propagate this number forward. The durable output of
  this measurement is the conclusion below, which does not depend on the exact value.)*
  So:
  the index needs only ~10 bits (a `short` has ~100× headroom; the old 256/byte cap
  was genuinely too tight, which forced the previous lossy lumping); the descriptor
  table is **~4.6 KB — smaller than L1 cache**, so there is no cache-eviction concern
  and plenty of room to add fields. Exact vs quantized differ by only 11 types, so
  **exact hardness is essentially free** — no log-compression needed. (The measurement
  excludes tool-type/harvest-tier, which need datapack tags unavailable in the headless
  test; including them raises the count modestly to ~1–2k — still trivially L1-resident.)
- **On disk: palette + packed indices** (the same technique Minecraft uses for block
  storage) — a small per-section palette of distinct navtype indices + packed indices;
  **expanded in memory**, with the shared `long[]` descriptor table resolving indices
  to fields.

### 6.2 Nav grid — recomputed, never persisted

Per-cell traversal data (the `TraversalGrid` — a packed `short` per cell, 6 `NavFlags`
neighbour bits + a 10-bit navtype index, plus a parallel depth `byte[]`; the old 4-value
`TraversalClass` was superseded and deleted) is **recomputed on chunk load**, not saved.
Recompute is deterministic and sub-millisecond per chunk (measured ~6.7 ns/block read;
~0.66 ms/chunk). The biggest win — **now live** in `NavSectionBuilder.computeFlags` — is
the **all-air/uniform-section bypass**: a uniform section is classified once and filled,
not scanned (≈60% of sections; only the top overscan rows are computed individually when
the section above has content).

### 6.3 Regions — fixed grid (not semantic)

Regions are a **fixed cubic grid** (octree), not semantic flood-filled blobs.
Rationale:

- **Assignment is trivial** (block → region by coordinate math; no flood-fill).
- **Updates are O(1)** — placing/breaking a block touches one cell + its boundary
  links. (Semantic regions require re-partition when a wall splits a cavern.)
- **Clean octree aggregation** — parent = merge of 8 children, which the resource
  histogram and HPA\* cost roll-up both want.
- Long-distance planning still works via HPA\* on the grid (§6.5). Semantic naming
  ("the cavern") can be recovered later by *labeling* grid regions, not partitioning
  by them.

Because the grid is fixed, **the octree is implicit** — a region's identity is its
coordinate and parent/child is coordinate math, so there are no explicit tree-node
objects (this superseded the old semantic `Region`/`LeafRegion`/`CompositeRegion`/`Portal`/
`RegionPool`/`RegionBuilder` classes, and the whole `worldmodel/region/` package has since
been **deleted from disk** — the live region tier is `worldmodel/hpa/` +
`pathfinding/regionpathfinder/`). The two per-region data layers — **HPA\* costs (§6.5)**
and the **resource histogram (§6.4)** — therefore **share one coordinate key but are stored as separate
parallel arrays (struct-of-arrays), not one combined record.** Reason: pathfinding
streams cost data across a route while resource search streams histograms through a
subtree — they never run together, so interleaving them would drag each operation's
data through the other's cache lines (HPA\* costs are ~3 B/node → ~21 per cache line
when dense, vs ~4–5 when interleaved with a histogram). Separate arrays also let the
two layers compress and granularize independently.

**Super-regions / rollups live in a per-level pyramid.** The implicit grid extends to
*every* level: a node at level *L* is addressed by `(level, blockPos / (16·2^L))`, and
its parent is `(level+1, coord >> 1)`. So the hierarchy is a **stack of implicit grids
(a mipmap)** — the merged value for a node's children lives in the next level's grid at
the coarse coordinate. The pyramid is **persisted** (you can't rebuild a super-region's
rollup from unloaded leaves, and planning over unexplored terrain is the point) as a
**per-dimension hierarchical index, separate from the chunk-local leaf data** (a level-3
node spans 64 chunks, so it can't live in a chunk file). A leaf change re-merges its
ancestors in O(levels). Both the HPA\* cost pyramid and the resource histogram pyramid
use this, each as its own SoA layer.

**Octree base → quadtree crown.** The world is ~384 tall (−64..320) but ~60M wide, so
cubic regions can't keep doubling vertically. Pad the vertical extent to **512** (a power
of two; the above-build-limit portion is all-void and skipped by sparse encoding, so it
is free), and the levels align cleanly: **levels 0–5 are an octree** (16³→512³, 8
children); at level 5 a cell spans the full padded height. **Levels 6+ are a quadtree**
(1024²×384, 2048²×384, …; 4 children, horizontal-only) until the top node covers the
whole dimension (~6 octree + ~17 quadtree levels). The merge combines 8 children below
the transition and 4 above — identical for the log₂ resource roll-up and the HPA\*
face-to-center pyramid. High quadtree levels have tiny node counts, so the budget is
unaffected.

### 6.4 Resource octree — "where is the nearest X"

> **Built (2026-07/08).** The design below shipped as `worldmodel/resource/`, on the same
> implicit-grid addressing as the cost layer and as its own struct-of-arrays pyramid — NOT
> inside the deleted `worldmodel/region/` classes this section originally named. The mapping:
> `RegionBlockIndex` → `ResourceClasses` (+ `ItemClasses` for the item-side taxonomy),
> `RegionMetadata` → `ResourcePyramid` + `Log2Codec` (the log₂ bucketing was lifted verbatim),
> `merge` → `ResourceMerger`, search → `ResourceQuery` (`near`/`mid`/`far` box sums, the 3×3
> neighbourhood ascend, `RESOURCE_TOP_LEVEL = 21`), tally → `ResourceScan` (folded into the
> nav-grid classify pass, not a second scan). Persistence is `ResourcePyramidCodec` +
> `RegionPersistence`'s sharded `res.<X>.<Z>.bin` / `res.coarse.bin`. Surfaced in-game as
> `/bot find` and `/bot report`. Light min/max was NOT built.

- **Resource classes**: ~64 tracked resource *classes* (ores, logs, beds, chests,
  crops, plus "hint" classes like building-blocks/redstone that imply player
  structures). Grouped (all 16 bed colors → one class). 2 user-defined slots.
- **Per-region histogram**: a sparse histogram where each present class
  stores **log₂(count)**; plus light min/max. `merge(child)` rolls counts up the
  octree (`log₂(x)+log₂(x)=log₂(2x)`). Approximate by design — a "where is it
  densest" signal, not a census.
- **Search:** at the current region ask "any diamonds?"; if not, ascend until a
  super-region has some; descend into the densest children; once localized to a
  section, **load it and scan the nav grid** for the exact vein.
- **Encoding:** sparse (store only present classes as `(classId, log₂count)` pairs;
  skip empty regions), section-granular leaves, full octree. This is **persisted**
  (it's the bot's memory of unloaded terrain).

### 6.5 HPA\* graph — the persisted coarse navigation layer

The piece Baritone lacks (and the reason Orebit scales to long distances).

> **Evolution note (2026-07 — reading guide; the built system diverges from three bullets below).**
> The **face-to-center cost model was deleted (s36)** in favor of per-region **fragments**
> (HPA-FRAGMENTS.md): a region stores its passable connected components with per-face footprints and
> per-fragment S/W type bits (DESIGN-typed-fragments.md); **no costs are stored at all** — every edge cost
> is DERIVED at query time. So "cost, not connectivity" inverted: the layer stores *connectivity facts* and
> derives costs. The "fully connected / no disconnected" claim is now capability-qualified: a **no-break**
> bot's graph drops every mining edge and can honestly FAIL. The **no-entrances ruling stands** (footprints
> are per-fragment face *facts* recomputed with the leaf, not crossing points or stored edges).
> Persistence is the `.mca`-style sharded format (`hpa.<X>.<Z>.bin` L0–5 + `hpa.coarse.bin`, codec v7,
> incl. the #5 crossing-invalidation section — NOTES-perf-and-persistence.md,
> DESIGN-persisted-invalidation-memory.md). §7.1's evolution note covers the search-side arcs.

- **Cost, not connectivity.** In Minecraft *everything* is traversable — you can
  always mine through — so the lattice is **fully connected**; there is no
  "disconnected." We therefore store **a crossing cost per face**, never adjacency
  bits. The topology is implicit in the grid.
- **No entrances / no transition graph (ratified — do not reconsider).** Classic HPA\*
  precomputes *entrances* (border-crossing transition nodes) and an abstract edge graph
  between them. **Orebit rejects that outright**, because in Minecraft a block can be
  placed or broken at *any* coordinate at any time, and every such edit can create,
  destroy, **split, or merge** the entrances on a region face arbitrarily. Maintaining a
  correct entrance/edge graph under that churn is (a) an incremental-update nightmare (one
  block change can re-partition a face's openings), (b) a storage cost (the openings, the
  abstract edges), and (c) a read-time cost (finding/walking the entrances). The
  face-to-center cost model has **none** of these: a face's cost is a single scalar
  recomputed from the leaf when the leaf changes — no openings to track, no graph to
  repair, no per-edit re-partition. **Regions stay dumb fixed cubes; intelligence lives in
  the recomputed nav grid on approach.** Keeping regions simple is the whole reason the
  block tier was made fast — the two decisions are a pair.
- **Face-to-center cost model.** Each node stores **6 `face→center` costs**; a
  traversal = `entry-face→center + center→exit-face`, and the inter-node boundary is
  the implicit sum of the two facing halves (no separate edge storage). These
  compose hierarchically (a coarse node's costs built from its 8 children — the
  "square pyramid" roll-up), enabling multi-level planning.
- **Leaf = 16³, cost = 4-bit log-scale.** Leaf size is the budget lever (scales
  cubically); bit-width is fidelity (scales linearly) — they don't trade. 16³ keeps
  the whole layer ~2% of save; 4 log-scale bits span the walk→mine-obsidian range
  (~3 orders of magnitude). Costs are computed exactly at build time (a mini-pathfind
  in the leaf) and quantized to the bucket. Sub-16³ fidelity comes from the
  recomputed nav grid on approach, not a finer persisted leaf.
- **Portals are local edges.** A portal (Nether/End/region transition) is just
  another movement edge **stored in the section that contains it** — bounded by the
  portals actually present, consulted only when A\* expands that section. **No global
  portal table** (technical players build tens of thousands).
- **Cross-dimension.** Each dimension has its own grid; the per-dimension budget is
  unchanged because vanilla also stores dimensions separately. The Nether **8:1**
  scale lives **only in the A\* heuristic** (map a Nether node `(nx,ny,nz)` → overworld
  frame `(8nx, ny, 8nz)` to estimate distance-to-goal); edge **costs** are real
  effort, so the cheaper Nether route surfaces naturally. The heuristic stays
  admissible if weighted at the minimum cost/block.

### 6.6 Persistence & disk budget

Target: persisted data adds **< 10–15%** to save size. Measured baseline on the dev
world: **2,557 B/chunk** compressed block data.

| Component | Persisted? | ~% of baseline |
|---|---|---|
| Nav grid (per-cell) | No — recomputed on load | 0% |
| Resource octree (sparse) | Yes | ~4–5% |
| HPA\* graph (face-to-center, 16³, 4-bit) | Yes | ~2% |
| **Total (per dimension)** | | **~6–8%** ✅ |

Comfortably under target, with HPA\* leaf-size/bit-width as the tunable knob.

> **Both layers are now built and persisted, but the numbers above remain an ESTIMATE — no
> empirical measurement has been taken.** Two things changed under the table: the region row
> no longer stores face-to-center costs at all (per-region *fragments*, costs derived at
> query time — §6.5's evolution note), and the on-disk layout is `.mca`-style **sharded**
> (`hpa.<X>.<Z>.bin` L0–5 + `hpa.coarse.bin`, and the same for `res.*`; codec v7, gzip body,
> treated as a rebuildable cache). Re-deriving the ~6–8% figure against the real format is
> the outstanding §11 measurement.

## 7. The pathfinding engine

Two-tier, lazy, hierarchical A\*.

### 7.1 Tiers

- **Region tier (`RegionPathfinder`)** — A\* over the persisted HPA\* **face-to-center
  cost** graph (§6.5), using the LCA super-region of source+target and planning **one
  hierarchy layer at a time**, refining sub-region plans **lazily** as the bot descends.
  Because the graph stores costs (not entrances), a region-A\* node is just a grid cell at
  some level and its edges are the implicit 6 face-to-center sums — there is nothing to
  maintain. Produces a `RegionPathPlan`: an **ordered sequence of regions** (the coarse
  skeleton), NOT a list of crossing points. This is what makes multi-thousand-block goals
  tractable.
- **Block tier (`BlockPathfinder`)** — the existing fast, allocation-free A\* over the
  recomputed nav grid, run **inside a bounded window** of the region skeleton (below).
  Dimension portals are an `EnterPortal` movement (§7.2), not a region-boundary face.
  Produces a `BlockPathPlan`.

> **Evolution note (2026-07):** the sliding-window model below was built, then refined in place.
> The single-center-node representation became per-region **fragments** (HPA-FRAGMENTS.md), the flat
> skeleton became the **stateful nested-skeleton cascade** (HPA-CASCADE.md — `HierarchicalRegionPlan`,
> one windowed plan per pyramid level, re-planned only at the level whose window the bot exited, with
> per-level blacklist repair), and the window target is a **standable portal/representative cell**, not
> the raw region center (the mid-air-center bug). The description below remains correct as the model's
> rationale and its level-0 behavior. Subsequent region-tier arcs (each with its own design card):
> **entry-face nodes + dig-through edges** (PERF-DESIGN-region-dig-through.md), **flood-from-bot /
> containment anchoring + tool-aware dig costs** (PERF-DESIGN-region-cost-and-fragment.md, HPA-CASCADE.md
> "The containment anchor"), the **virtual goal fragment** (per-approach goal seeding + dig-seed pockets —
> HPA-CASCADE.md "The virtual goal fragment"), **typed fragments** (DESIGN-typed-fragments.md), the
> **#5 persisted crossing-invalidation memory** (DESIGN-persisted-invalidation-memory.md), **persistence
> sharding + lazy load** (NOTES-perf-and-persistence.md), and the **rolling skeleton**
> (DESIGN-rolling-skeleton.md — increment A in flight).

**The ratified region→block execution model: a sliding window (no portal/entry points).**
The region skeleton gives the *sequence* of regions to pass through; the block tier does
the actual moving, but never over the whole route at once. Instead:

1. Take a **window of the next few regions** along the skeleton (target ~3 regions ≈ 48
   blocks; the window size is a tuning knob, bounded so block-A\* stays cheap — a 3-region
   window is ~12,288 cells, well inside budget even at pessimistic ns/node).
2. **If the goal is inside the window**, run block-A\* straight to it — done.
3. **Otherwise run block-A\* to the *center* of the farthest region in the window** (the
   one nearest the goal along the skeleton). Any traversable arrival in that region is
   fine — there is no designated entry cell, because there are no entrances. The region
   *center* is just a waypoint that pulls the bot the right way; the block-A\* finds the
   real, optimal micro-path to it over live geometry.
4. **Walk that block path; when the bot crosses into the next region, replan** — slide the
   window forward one region and go to step 1.

This yields a near-optimal region-N→region-N+1 crossover *for free* (block-A\* optimizes
the actual crossing over real terrain each time), depends ONLY on block-A\* solving
arbitrary ≤window-sized paths (now fast + allocation-free), and stores **nothing** about
how regions connect. Replanning every region boundary is the price; it's cheap because each
block-A\* is windowed, and it's also what makes the bot robust to the world changing under
it (a freshly-placed wall just changes the next window's micro-path).

- **`PathPlan`** unifies them: holds the region skeleton, drives the sliding window, invokes
  the block planner per window just-in-time, and can preload the next window's chunks while
  executing the current.

### 7.2 Movement vocabulary

Adopt the proven movement set from **[Baritone](https://github.com/cabaletta/baritone)**
(the prominent Minecraft pathfinding bot we studied), add a portal op, and make it
**extensible** via a `Movement` interface (Strategy pattern) so new types can be
registered later.

**Built (18, `MovementRegistry.TIER1`, in registry order — order matters on cost ties):**
Traverse, Diagonal, Ascend, Descend, Fall, Pillar, MineDown, Swim, SprintSwim,
StartSprintSwim, Surface, Climb (ladder/vine/scaffold, both directions), Parkour,
DiagonalParkour, WalkOff, DiagonalSprintSwim, RideBubbleColumn, EndSprintSwim.

*The fluid family, as it stands after the "fluid is a MEDIUM, not a pose" ruling
(2026-08-07; NOTES-movement-physics.md):* **Swim** is the upright
(`MODE_STANDING`) medium move — six-directional (four cardinals + a straight rise + a
straight sink), priced per rung from real vanilla rates (surface 9.09 / submerged 10.15
ticks per block), and it is what un-walls fluid for the whole search. It works in **water
and lava alike** — there is no lava-only rung; the damage and slow factors carry the cost,
and `NavBlock` states lava's slowness as data via the `TRANSIT_FLUID` transit class rather
than a movement-side special case. **SprintSwim** is fast *lateral* prone travel and
nothing else (3.56 ticks/block) — its pure-up/pure-down rungs were deleted as unreal (a
swimming look clamps near 80°) — and it is **water-only**, because vanilla's
`Entity.updateSwimming` gates `Pose.SWIMMING` on `FluidTags.WATER`. The two pose
transitions are now a symmetric pair: **StartSprintSwim** (STANDING→PRONE in place in
2-deep water, plus the fused surface dive) and **EndSprintSwim** (PRONE→STANDING in place,
gated on a pose *fit* test — two non-solid body cells — so a bot can stand up while still
submerged). **Surface** is no longer a surfacing move at all: it is only the prone
**crawl-out onto a bank**, the one exit for a bot in a 1-tall submerged tunnel that cannot
stand up where it is. **RideBubbleColumn** is the UP-only soul-sand conveyor as a single
macro candidate (bubble cells are walled off from every other move).

Nether-portal travel is handled above the vocabulary today (`NetherPortalIndex` + the
follower's portal-seek/ENTER states), not as an `EnterPortal` movement; cross-dimension
*routing* (region-tier portal edges) is still ahead.
**Planned/extensible:** Crawl (1-tall gaps via trapdoor/sneak), wall-clutch,
boat/minecart, a DOWN bubble column — added later through the same interface.

**Interactions are folded into movements, not separate ops.** Breaking, placing,
and **toggling doors/gates/trapdoors** don't change position, so they are *part of*
the move that needs them (e.g. "Traverse, breaking the block in the way"; "Ascend,
placing a block beneath"; "Traverse, opening the door"). A movement therefore carries
its positional transition **plus** the interactions it performs, each contributing
cost and validity. This matches Baritone for break/place and extends it to door-like
interactions Baritone ignores.

`Movement` contract (per candidate, given a `MovementContext`): destination,
`cost()` (ticks, inventory-aware), `isValid()`, an execution state machine, and the
interactions performed. New movement types subclass this and register. **As built** the
contract is `candidates(ctx, x, y, z, CandidateSink)` (emitting destination + tick cost +
destination pose mode) plus `plan()` / `steer()` for execution, and there is no external
capability filter: each movement **self-gates** inside `candidates` on `BotCaps`, on the
node's pose mode, and on the local descriptor facts. (`PathFollower` remains a spec stub —
see §7.5.)

### 7.3 Cost model

Adopt Baritone's **tick-based** model (its strongest part; ours was unspecified).

- **Unit: game ticks, `float`, everywhere.** (This also resolved the old `int Portal.cost`
  vs `float` op-cost mismatch — moot now that the semantic `Portal` class is deleted along
  with the rest of `worldmodel/region/`; every cost in the live search is `float` ticks.)
- **Base constants** seeded from Baritone's `ActionCosts` (walk ≈ 4.633, sprint ≈
  3.564, water/soul-sand ≈ 9.1 ≈ 2×, ladder ≈ 8.5, a fall-cost table), then tuned
  against our own measurements.
- **Mining cost** = inverse of best-tool-vs-block strength (`hardness`/tool tier) +
  a fixed break overhead, **+ recursive cost of any falling block above**. Bedrock
  and unharvestable-without-tool resolve to a `COST_INF` sentinel (a very large cap
  — "effectively impossible," consistent with "everything is *technically*
  traversable but some things are impractical").
- **Placing/bridging** requires a throwaway block in inventory; cost = place delay +
  block consumption; unavailable with no blocks.
- **Hazards/fluids/surfaces:** lava avoided (huge cost), water/soul-sand slow,
  ice slippery; fall beyond safe height pruned unless a water bucket is available. The
  *damage* cost of hazards depends on the health setting (below).
  > **STATUS NOTE 2026-08-10 — "ice slippery" was never built into the planner.** The other
  > items here shipped; slipperiness did not. `NavBlock` carried a `SURFACE_SLIPPERY` value in
  > its 2-bit `surface` field that **no cost function ever read** (the field's only live consumer
  > is `MovementContext.isSlow`, testing `SURFACE_SLOW`), so the value was removed. Slipperiness
  > is handled instead at EXECUTION time, where it belongs: the follower reads the live block's
  > vanilla friction (`BotSteering.slipperinessAt` → `getFriction()`) to shape the parkour
  > air-brake and the ground cross-track gain. Pricing ice in the search remains open design
  > intent, not a described-but-broken feature.
- **A bot is not a player — several player constraints are configurable** (per server
  and/or per bot), and the cost model is parameterized on them:
  - **Food/hunger: ignored.** Bots don't eat, so sprint is always available — drop
    Baritone's `foodLevel > 6` gate entirely.
  - **Health: configurable.** Immortal bots ignore damage (lava/fire/cactus cost only
    their time/slowing, not health); mortal bots treat damage as high cost / avoidance.
  - **Breath/oxygen: configurable.** If bots don't drown, underwater paths cost only the
    swim penalty; if they do, an underwater route carries a breath budget (drowning
    pruned/penalized past it).
  - **Tool durability: configurable.** Off → mining never "wears out." On → durability is
    a consumable tracked along the path (next point).
- **Consumables are tracked *along the path*, not snapshotted once.** Baritone captures a
  `CalculationContext` at plan start and replans on change; that can validate a path it
  can't actually complete. Instead the search carries the **remaining consumables**
  (throwaway blocks, and — if enabled — tool durability) accumulated along each node's
  best path, and a move that would exceed the budget is **invalid**. This correctly
  handles e.g. "break two obsidian, then mine the diamond" when the pickaxe only has
  durability for one break — that route is rejected rather than discovered mid-execution.
  A coarse snapshot (tool set, water bucket) still seeds the search; the per-path tally is
  the refinement.
- **Persisted HPA\* costs are a terrain baseline** computed with default capability and
  default survival settings; the block tier adjusts via the live inventory + config and
  does the per-path consumable accounting. (The baseline-plus-capability split we
  committed to for HPA\* storage.)

### 7.4 Heuristics

- **Block-level (as built): weighted, not admissible.** The "stay admissible because
  the hierarchy handles scale" bet did not survive contact — even windowed searches
  drowned in equal-cost route ties (see `docs/Optimizations/06_fewer_nodes.md`). The
  shipped heuristic is symmetric 3D octile × `greedyWeight` (config, default 2.0),
  plus a tiny straight-line tie-break, plus the **admissible `GoalForcedCost`
  premium** (cheapest-goal-face forced build/dig cost, far-face excluded with the
  vertical build face exempt). Partial-path + the irreversibility guard are the
  safety net weighting requires.
- **Region-level:** Simple (Euclidean centers), PortalCount, VerticalityPenalty,
  TagAware, ExplorationBias. *Only `SimpleRegionHeuristic` is built; the other four remain
  Javadoc-only spec stubs.*
- **Cross-dimension:** overworld-frame conversion (§6.5).

### 7.5 Execution & support cast

> **Status (2026-08): every class in this section is still a Javadoc-only spec stub.** The
> live execution path went a different way and is described in §10.A: `PathPlan` (two-tier
> driver: skeleton + sliding window + boundary-gated commit/replan) → `BlockPathPlan` →
> `BotNavigator` (drive/replan/steer/repair/applyEdits) → per-move `MovePlan`/`PhaseRunner`
> phases + the `BotSteering`/`SteerControl` seam, with `BotMining` as the break actuator.
> Of the cast below only `PathStatus` exists as code. The budget/replan/cache concerns did
> get solved, just not here: budgeting is the async wall-clock cap
> (`pathing.asyncSearchBudgetMs`) + partial paths; replanning is `PathPlan`'s settle-event
> model plus the `pathfinding/splice/` seam; background execution is `pathfinding/async/`.
> The entries below are kept as the ratified *design*, not as a status report.

- **`PathFollower`** (interface) — ticked once/tick, applies movements one at a time,
  tracks progress, reports `PathStatus` (IDLE/RUNNING/COMPLETE/BLOCKED/FAILED),
  filters by capability. Backs both `VirtualPlayerController` bots and (potentially)
  native mobs.
- **`PathReplanner`** — detects invalidation (block failure, region/portal change,
  inventory change), re-invokes the planners, swaps in a new `PathPlan`. May run on a
  background thread.
- **`PathBudgetManager`** — per-bot/per-tick/global node + time quotas (Baritone-style
  time budget with periodic clock checks; partial-path "best so far" progress so the
  bot always moves forward). No-op acceptable initially.
- **`PathCache`** — short-lived (start,goal,region,config)→plan memo; LRU + expiry +
  region-scoped invalidation on world change.
- **`PathSmoother`** — collapses aligned movements for compact execution.

## 8. Block reading & performance

- **Reading** uses the optimized path (bypass the World API → `ChunkSection` → read
  `PalettedContainer` internals, scanning sequentially). Per-palette
  results: Singular ≈ free (and hits the **uniform-section bypass**), Array/BiMap
  a few ns/block. Choose the read strategy per palette type. **As built** the internals
  access lives behind `platform/SectionPalette` (self-degrading, version-stable), not in
  `NavSectionBuilder` — its reflection fields survive only as inputs to the JMH reference
  benchmark.
- **Benchmarking** is via the JMH harness (`./gradlew jmh`), run headless inside the
  Fabric Knot classloader (`fabric-loader-junit`, `forks=0`). Budget perf as a
  **ratio to Minecraft's own chunk-generation cost** on the same machine, not absolute
  ns, so we don't over-fit to fast hardware.
- *(Historical: the committed `NavSectionBuilder` STEP code was buggy benchmark
  scaffolding, with the JMH benchmark holding the corrected reference implementations. The
  STEP methods are gone; the live path is `classifyInto`/`computeFlags`/`computeDepth`.)*

## 9. Portability (cross-cutting, plan early)

Supporting multiple **loaders** and **Minecraft versions** is a **shared-core +
thin-adapter** problem, not separate codebases — and cheapest to set up **now**, while
most code is still stubs (the coupling surface multiplies as subsystems are written).

**Concrete targets (in priority order) — Goals 1 and 2 are now SHIPPED:**

| | Loader + version | Purpose | Difficulty | Status |
|---|---|---|---|---|
| **Original build** | Fabric + 1.21.4 | what existed when this was written | — | superseded |
| **Goal 1** | **Forge + 1.20.1** | run alongside a Forge modpack (furniture/biome packs) | medium | ✅ shipped |
| **Goal 2** | **Fabric + 26.2** (latest vanilla) | remote server for vanilla clients | medium | ✅ shipped |
| **Stretch** | **1.12.2** (any loader) | the "golden-age modding" servers | **hard** | not attempted |

The built matrix is far wider than the table's two goals: **MC 1.17.1 → 26.2**, with
**Fabric** across the whole range, **Forge** 1.17.1→1.21.11 and **NeoForge** 1.21→1.21.11.
The mechanism is branch-per-toolchain-era + Stonecutter + the composing `overlays/` chain
(`internal_docs/BUILD-STRATEGY.md`).

- **Loaders:** Fabric API is touched in only ~2 files. Extract a small `PlatformEvents`
  interface (**built** — it is the one loader seam, with a thin impl per loader module);
  build with **Architectury Loom**, which targets **Fabric, Forge, and
  NeoForge** from one shared core. **The Architectury *API* is deliberately NOT used** —
  the loader seam is hand-written DI, so the shipped jars carry zero runtime deps, and the
  26.x era builds on plain Fabric Loom (Architectury Loom cannot yet build unobfuscated
  26.1+). NeoForge is a 2023 fork of Forge and is *not*
  cross-compatible with it; for **1.20.1 we target Forge specifically** (the son's
  modpack), while newer versions would lean NeoForge.
- **Versions:** the volatile surface is the fake-player network stack + the
  `PalettedContainer` reflection. Use **Stonecutter** (multi-version source sets); put
  block-state access behind a port interface; consolidate hardcoded `Blocks.X` lists
  into tag/behavioral queries.
- **Cold path → interface; hot path → version-selected concrete class.** Abstracting a
  version/loader API behind an interface is fine for **cold/setup** code (event
  registration, planning-boundary world access) where dispatch cost is noise. But on the
  **per-block hot path** (e.g. `NavSectionBuilder`'s read+classify loop) a virtual call
  *per block* reintroduces exactly the dispatch/megamorphism overhead `docs/Optimizations/01_block_reading.md`
  fought to eliminate. There, **select the concrete version-specific implementation once
  at load** so the inner loop calls the versioned API directly and stays monomorphic /
  JIT-inlinable. The thin platform interface lives only at the **selection boundary**,
  never inside a tight loop. **As built** the selection is done at *compile* time rather
  than by suffixed sibling classes: the divergent primitive is a tiny static helper in
  `platform/` (`BlockLookup`, `ChunkCoords`, `Sections`, `TagLookup`, …) whose body is
  supplied by the highest applicable `overlays/<era>/` directory — one class name, one
  implementation per build, so the call site is a static and JIT-inlines to nothing. Core
  logic (e.g. `ChunkNavLoader`, `NavSectionBuilder`) stays in `src/` and never forks.
- **The 1.12.2 stretch is genuinely hard** and called out separately: 1.12.2 predates
  "the Flattening" (1.13), so blocks use **numeric IDs + metadata** instead of the
  blockstate/`PalettedContainer` model the whole world model is built on. That is a
  fundamental data-model divergence (different registries, NBT, networking), so 1.12.2
  is effectively a second port of the world-model's block layer behind the same port
  interface — worth it for the heavily-modded server audience, but a stretch.

This is a structural decision to make before heavy implementation, even if the
adapters are filled in over time.

## 10. Build plan

> **Rewritten 2026-06-26.** The original Phase 0–5 numbering tracked the world-model + pathfinding BUILD,
> which is now mostly complete. This section is restructured into **(A) the completed navigation foundation**
> (recapped, with the impl specs that own each piece) and **(B) the current ordered forward plan** (Phases
> 1–7, dependency-ordered). Older notes that cite "PRD Phase 0–5" refer to part A; going forward "Phase N"
> means the part-B forward plan.

### A. Completed — the navigation foundation (built + runtime-verified, both eras)

The bot navigates the world robustly: it routes around terrain, builds staircases/pillars, digs, and now
ascends the pathological open-air pillar (incl. the floating-block case). Built:

- **Foundation + multi-version/multi-loader** (was Phase 0) — the branch-per-era + Stonecutter/overlay build
  (§9), the hand-written platform DI seam, dead-experiment cleanup.
- **World-model nav pipeline** (was Phase 1) — `NavBlock` / `TraversalGrid` / `NavSection`, build-on-chunk-load
  + `setBlockState`-mixin freshness, the version-selected `platform/` adapters.
- **HPA\* region tier** (was Phase 3) — implicit fixed-grid octree→quadtree cost pyramid (face-to-center cost,
  **NO entrances** — §6.5), `RegionPathfinder` + the sliding-window `PathPlan` driver + corridor `RegionBound`.
  Spec: `internal_docs/HPA-IMPLEMENTATION.md`. Superseded the semantic `region/` classes, which
  have since been deleted outright.
- **Block tier + movements + macro-ops + partial-path** (was Phase 4's block half) — alloc-free
  `BlockPathfinder`, the Tier-1 `Movement` set, cuboid macro-collapse + the `GoalForcedCost` premium, and
  `PARTIAL_PATH` (ON) as the structural net for heuristic terrain blind spots. Specs:
  `internal_docs/MACRO-IMPLEMENTATION.md`.

**Carried forward (designed in the old plan, NOT built — mapped into part B):** the tick-based cost model +
inventory snapshot (old Phase 4 cost half) → **Phase 1**; the resource octree + resource search (old Phase 2)
→ **Phase 4**; the `VirtualPlayerController` integration (old Phase 5) → **Phase 7**.

### B. Forward build order — the current plan (dependency-ordered)

**Phase 1 — Agency layer.** ✅ **DONE** (config + `BotCaps` + real-inventory feasibility snapshot +
mining tick model; mining is now executed timed-and-vanilla by `BotMining`). Capability config → inventory → tool use (+ block placement from
inventory) → **tick-based costs** (Baritone-style: break time derived from the tool, place cost incl. the
consumed block). Makes the pathfinder's costs real (today they're arbitrary magic numbers) and is the keystone
the useful commands depend on. The hard design decision is **consumables-along-path** — a finite, depleting
block/tool budget vs the alloc-free hot path. The per-rung seams are detailed in the phased build plan below.

**Phase 2 — Far-goal / exploration robustness + HPA\* driver bugs.** ← the next arc. Several distinct
HPA*-tier issues surfaced during the Phase-1 cross-version smoke test (2026-06-26), in rough priority:

1. ✅ **FIXED** (leaf-cost recompute was the culprit; walk-only baseline + uniform solid/water fast-paths
   took it ~2500→60 µs/leaf). Kept for the record: **First-load / mass-chunk-gen tick stall (DO FIRST — perf).** On a fresh-world join the server ticks
   **~1 tick/sec for ~the first minute** while chunks generate. **Prime suspect: HPA\* leaf-cost recompute.**
   `LeafCostComputer` computes a leaf's 6 face→center costs by running **up to 6 full `BlockPathfinder.findPath`
   searches per level-0 leaf** (face-rep → center, `BotCaps.BREAK_PLACE`); `HpaMaintenance.flush` drains
   `MAX_LEAVES_PER_TICK = 8` dirty leaves/tick → **up to ~48 block-A\* searches per tick**, on top of
   `ChunkNavLoader`'s `MAX_BUILDS_PER_TICK = 8` nav-grid builds/tick (PalettedContainer reflection). The
   per-tick budgets exist, so if it still stalls ~20×, the cause is one of: per-leaf cost too high (the 6
   searches over fresh open terrain), an **unbudgeted trigger** (a block-change/dirty-marking storm during
   world-gen, or a full ancestor re-merge in `PyramidMerger`), or pathological searches. **Will also bite
   Elytra full-speed flight and 8+ players exploring** (both spike the chunk-gen rate). Repros only during live
   terrain gen, which the headless JMH harness can't stage → **diagnose by profiling a live `runClient`** (JFR
   `jcmd` attach → `jfr print --events jdk.ExecutionSample | grep com.orebit`, or the Spark mod; recipe in
   `HANDOFF.md`).

2. ✅ **FIXED** (window targets are now standable portal/representative cells — the raw-center fallback is
   gone; swim additionally un-walls water). Kept for the record: **Window-target lands in mid-air (the "random pillaring" bug — diagnosed this session).** The sliding-window
   driver aims block-A* at the far region's **center**, projected to ground by `PathPlan.projectToStandableFloor`
   — but that projection scans only the **single center column**, confined to that region's **own 16-tall band**,
   and on miss **falls back to the raw geometric center** (a mid-air point, `PathPlan.windowTarget()` line ~451).
   So over **water** (not `standable` — no swim in Tier 1) and over **ravines/cliffs** (the real floor is below
   the region's band) it targets an airborne point; the bot pillars/bridges toward it, then re-aims as the window
   slides → "pillar at a random angle, turn around, come back" (river crossing) and "pillar 40 up then bridge
   overhead" (ravine drop). **Fix direction — targeted, not structural:** widen the projection (small (x,z)
   neighbourhood + search *past* the region's own band to real ground) and replace the raw-center fallback with a
   sane one (clamp toward the bot's Y / nearest navigable cell). Confirm first via `PathPlan.DEBUG` (logs the
   per-replan `target=`); add a headless guard test (synthetic river/ravine → assert sane target, not raw center).

3. **Far-goal / unloaded-chunk `path=NONE` flood** (the original Phase 2 item; flat world, 0,0,0 → 1000,1000):
   an **exploration mode** — head toward the goal through loaded terrain and re-plan as chunks stream in
   (optimistic-default-over-unexplored is the planning side; the driver side is new) — plus graceful degradation
   instead of log spam.

4. ✅ **DONE** (s44, with the async pass). Kept for the record: **Time-based search cap** (replace the 10k-node
   cap; more robust as the per-node tick costs shift). Cheap,
   independent. Underpins the partial-path safety story (HPA*'s global vision keeps partials off the cave-trap).
   *As shipped:* in async mode the wall clock is the cap (`pathing.asyncSearchBudgetMs`, default 250, checked
   every 256 pops) and the node cap degrades to a memory-only backstop (`TIME_MODE_NODE_BACKSTOP` = 262k); the
   old 10k node cap survives only in the byte-identical sync mode as `pathing.syncSearchBudgetNodes`.

**On growing the sliding `WINDOW` (considered, deprioritized).** Because the regions form an implicit octree and
a path never doubles back through a visited region, the count of regions spiralled through before the goal comes
into range is bounded (≤ the 3-D neighbour count, ~26), so a large-enough window *would* guarantee the real goal
becomes the target and sidestep the mid-air-center bug (#2). **But** larger window → longer block path → more
weight on a greedy A* that already shows pathologies past ~3 regions (30–40 blocks), so pushing toward 26 invites
worse search blow-ups. **Prefer fixing the projection (#2) over growing the window**; revisit window size only if
the projection fix proves insufficient.

**Phase 3 — Pathfinding completeness.** PARTIALLY DONE: the full fluid family (upright Swim incl. lava, the
prone sprint-swim pair, the StartSprintSwim/EndSprintSwim pose transitions, Surface's bank crawl-out,
RideBubbleColumn), Climb, Parkour/DiagonalParkour, and WalkOff are built — 18 movements (§7.2) — and
**owner-portal following** works (nether portal index + portal-seek/ENTER +
cross-dimension FOLLOW). **Background-threading is DONE** (s44: the `pathfinding/async/` planner pool +
the `pathfinding/splice/` seam, behind `pathing.async`, default ON; complex-path tick time ~8–16 ms → ~3 ms).
Still open: Crawl / DiagonalAscend / wall-clutch; a DOWN bubble column; true cross-dimension *routing*
(cross-dimension skeleton stitching + the §6.5 8:1 Nether frame in the region heuristic). Incremental and
independent.

**Phase 4 — Resource layer + useful commands.** ✅ **LARGELY DONE.** The sparse **resource pyramid**
(§6.4, persisted — §6.6) shipped as `worldmodel/resource/` (`ResourceScan` tallies during the nav-grid
classify pass; `ResourcePyramid`/`ResourceMerger` roll up; `ResourceQuery` does the ascend/descend
**search-by-resource-location**, including the 3×3 neighbourhood ascend across region/quadrant boundaries) →
commands `/bot find`, `/bot gather`, `/bot report`, and the **inventory-drop** mechanism `/bot drop`. Depended
on **Phase 1** (inventory). This is the payoff that makes Orebit a genuine in-game helper, not just a follower.
Still open here: the locate-vs-gather taxonomy split's second half (see the resource-taxonomy notes) and
persisting the abundance tally across restart at every level.

**Phase 5 — Debug / UX polish.** A debug-view enable/disable command; split debug particles (HPA*-center
region path vs A*-exact block path, so a region-vs-block disagreement is visible). Small; slot anytime.

**Phase 6 — Deferred pathfinding-quality** (opportunistic; composes WITH partial-path, doesn't replace it).
A stronger forced-cost premium; **dominance/symmetry pruning** (the real anti-flood endgame — a *pruning rule*,
so it has no admissibility constraint, unlike the heuristic); the high-weight "dig out of the trap" escalation
(reads `BotCaps`, so it lands after Phase 1's config/tools); the SoA cuboid cache (Option C). None blocking — each makes floods rarer and partial paths better.

**Phase 7 — The agent brain + integration** (the original end-to-end vision; all stubs today; only sensible
once the bot is a capable, configurable agent — Phases 1 + 4). In dependency order:
- **AI / task execution** — `tasks/`, `ai/`, `behavior/`, `requirements/`: `GoalDispatcher` → a Requirements
  graph → `TaskExecutor` / `AIStateMachine` (multi-step goals: "build a wall", "strip-mine here").
- **`VirtualPlayerController` integration** (old Phase 5) — retire `AllyBotEntity`'s imperative `tick()`
  follow; the task engine drives the bot through the full region→block pipeline.
- **LLM intent** — `integration/`: `PromptBuilder` → `LLMInterface` → `LLMBackend` → `InterpretedIntent`. Chat
  → intent ONLY (never planning — the determinism pillar), feeding `GoalDispatcher`.
- **Behavior / memory / social** — `memory/`, `relationships/`, `sim/`: the personality, relationship, and
  long-term-memory layers.

### First-milestone discipline (kept from the old plan)
Every phase lands behind a measurable proof or a runtime smoke test, not just a compile: JMH for hot-path
micro-benchmarks; the `PathfinderBenchmark` scenarios + the now-faithful `/bot trace` for search shape;
in-game smoke tests for the live stack (the headless harness can't stand up a `ServerLevel`). For a search
change, the proof is **node-count / wall-clock before-vs-after on the case it targets** — the "earns its keep"
bar HPA\* and the macro-ops were held to.

## 11. Testing & measurement

- **JMH** for read/classify/cost micro-benchmarks (headless, Claude-runnable). Built:
  `PathfinderBenchmark` / `BlockReadBenchmark` / `PatchStormBenchmark`, run inside the
  Fabric Knot classloader (`fabric-loader-junit`, `forks=0`), mc-1.21 era only.
- **End-to-end pipeline validation on real generated chunks.** Fabric GameTest
  (`runGametest`) was the original plan and was **never built**. What shipped instead is
  the headless dedicated-server autotest (`scripts/run-autotest.ps1` → `:fabric:<ver>:runAutotest`
  → `HeadlessAutotest`), which boots a real server, spawns a bot, drives a `/bot` command
  and asserts a result file — plus a plain JUnit suite over the pathfinder/movement/region
  layers. **The autotest must run against a frozen `-MasterWorld`:** MC worldgen is not
  deterministic for vegetation (trees generate in parallel chunk order), so seed regen is
  not a stable regression oracle.
- **Empirical disk measurement** — was deferred until *all* persisted structures (resource
  pyramid + HPA\*) existed. **They now do, so this is unblocked and still outstanding:**
  measure true compressed bytes/chunk over the dev world against the sharded
  `hpa.*.bin`/`res.*.bin` files and compare to the ~6–8% estimate (§6.6).
- **Determinism tests** — same world + seed → identical nav data, regions, and paths.

## 12. Decisions log

Ratified during design review (with rationale recorded in project memory):

1. NavBlock identity is **per-BlockState**; **short** index → **packed 64-bit `long`**
   descriptor; palette-compressed on disk, expanded in memory. Tens of thousands of block
   states collapse to a few hundred navtypes — a large (tens-fold) collapse into a small
   L1-resident table. **Do not record the exact count here**: it moves with the MC version
   *and* at runtime with `mining.protectedBlocks` (`NavBlock.applyProtected` splits matching
   states into fresh navtypes after static-init), so any figure is stale for someone else's
   config. Read the boot line `[Orebit] NavBlock: … states -> … navtypes` in the configuration
   you care about. The durable constant is the **1024 cap** (the 10-bit navtype field).
2. Nav grid is **recomputed on load**, never persisted.
3. Regions are a **fixed cubic grid**, not semantic.
4. Resource tracking is a **sparse log₂ octree** over ~64 classes; section-granular.
5. HPA\* stores **face-to-center cost** (not connectivity); **16³ leaf, 4-bit
   log-scale**; **portals as local edges**; **8:1 conversion only in the heuristic**.
   **NO entrances / transition graph** (§6.5): block edits anywhere would create/destroy/
   split/merge entrances arbitrarily — unmaintainable; a scalar per face has none of that
   cost. Region→block planning is a **sliding window** (block-A\* to the goal-or-farthest-
   region-center, replan per boundary crossing — §7.1), not stored crossing points.
   Regions stay simple by design; that pairs with keeping the block tier fast.
   *(Evolved s36+: the stored quantity flipped to per-region fragment CONNECTIVITY records with costs
   DERIVED at query time — see the §6.5 evolution note. The no-entrances ruling, the 16³ leaf, portals
   local, and the 8:1-heuristic-only rule all stand.)*
6. Disk budget ~6–8%/dimension, under the 10–15% target.
7. Movement vocabulary = **Baritone set + EnterPortal**, **extensible interface**,
   **break/place/door folded into moves**. *(As built: 18 movements, §7.2. `EnterPortal` was
   NOT built — nether-portal travel sits above the vocabulary in `NetherPortalIndex` +
   `BotPortalFollower`. The extensible interface and the folded interactions both stand.)*
8. Cost model = **tick-based**, Baritone-seeded; mining = inverse tool strength +
   falling recursion; `COST_INF` cap for the impractical. **Bot ≠ player:** food
   ignored; health, breath, and tool durability are **configurable**; **consumables
   (blocks, durability) tracked along the path** (not snapshotted once) so a route that
   would run out is rejected, not discovered mid-execution.
9. Block heuristics stay **admissible** (the hierarchy earns optimal local paths).
   *(**Reversed** — see §7.4. The bet did not survive contact: even windowed searches drowned
   in equal-cost route ties, so the shipped block heuristic is symmetric octile ×
   `greedyWeight` (default 2.0) — deliberately inadmissible — with partial paths + the
   irreversibility guard as the safety net. The **admissible `GoalForcedCost` premium** and
   the region-tier heuristic are the parts that stayed admissible.)*
10. Portability = **shared core + adapters** (Architectury Loom / Stonecutter / composing
    `overlays/`; Fabric + Forge + NeoForge), structured early. *(The Architectury **API** is
    deliberately unused — hand-written loader DI, zero runtime deps — §9.)*

### Open questions

- Exact NavBlock index bit-layout and how much orientation to inline vs table.
- HPA\* leaf cost: face-to-center everywhere vs face-to-face at the finest level.
- Whether resource memory persists per-section or per-chunk (rebuild fine octree on
  load).
- Final tuned cost constants (Baritone's are a starting point).
- NeoForge vs Forge; how aggressively to pursue multi-version in v1.
