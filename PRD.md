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

**Loader / version are not architectural constraints.** The *current build* targets
**Fabric on Minecraft 1.21.4**, but supporting additional loaders (NeoForge, and
optionally Forge) and additional Minecraft versions (newer and possibly older) is an
explicit goal. The design keeps Minecraft- and loader-specific surface deliberately
thin so this expansion is a matter of adapters, not rewrites (see §9 Portability).

This PRD covers the load-bearing foundation everything else needs: perceiving the
world efficiently (the **world model**) and moving through it intelligently (the
**pathfinding engine**).

## 2. Current state (honest baseline)

Orebit is today a **documentation-first codebase**: ~177 Java files, of which only
~30 contain runnable logic; the rest are Javadoc-only design stubs. Two largely
disjoint things exist:

1. **A working prototype** — the root `com.orebit.mod` package (`AllyBotEntity`, a
   faked `ServerPlayerEntity`, follows the owner via imperative `tick()` math) plus
   a **real `worldmodel/` data layer** (`NavBlock`, `TraversalClass`/`TraversalGrid`,
   the `Region`/`Portal` structures, object pools).
2. **An elaborate stubbed architecture** — `pathfinding/`, `ai/`, `tasks/`,
   `integration/` (LLM), `memory/`, etc. — that nothing running actually uses.

Critically, **the world-model pipeline is broken at its only integration point**:
`NavSectionBuilder.build()` is an inert benchmark and `ChunkNavLoader` discards the
`NavSection[]` it builds. So even a finished pathfinder would have no data. Fixing
this is Phase 1.

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
- **Note:** some existing code contradicts these (a static `BotManager`, `final`
  classes, the nav/analyzer drift). Aligning to the principles is part of Phase 0.

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
  change passability. The current code keys on `Block`/default state and must change.
- **Index → packed descriptor table (the chosen model).** Each block state maps to a
  **`short`** navtype index; the index resolves to a **single 64-bit packed `long`**
  holding all the fields above (one array read + bit-extract — no objects, no pointer
  chasing). Behavioral-equality dedup is lossless (distinct *behavior*, not lumped).
- **Measured (`profile.BlockStateDedupTest`, 2026-06):** the 27,866 block states
  collapse to **587 distinct fingerprints** (576 quantized) — a **48× collapse**. So:
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

Per-cell traversal data (the `TraversalClass` grid and NavBlock lookups for a loaded
region) is **recomputed on chunk load**, not saved. Recompute is deterministic and
sub-millisecond per chunk (measured ~6.7 ns/block read; ~0.66 ms/chunk). The biggest
win — currently disabled — is the **all-air/uniform-section bypass**: a SingularPalette
section is classified once and filled, not scanned (≈60% of sections).

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
objects (this supersedes the old semantic `Region`/`LeafRegion`/`CompositeRegion`
classes). The two per-region data layers — **HPA\* costs (§6.5)** and the **resource
histogram (§6.4)** — therefore **share one coordinate key but are stored as separate
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

- **`RegionBlockIndex`**: ~64 tracked resource *classes* (ores, logs, beds, chests,
  crops, plus "hint" classes like building-blocks/redstone that imply player
  structures). Grouped (all 16 bed colors → one class). 2 user-defined slots.
- **`RegionMetadata`**: per region, a sparse histogram where each present class
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

- **Cost, not connectivity.** In Minecraft *everything* is traversable — you can
  always mine through — so the lattice is **fully connected**; there is no
  "disconnected." We therefore store **a crossing cost per face**, never adjacency
  bits. The topology is implicit in the grid.
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

Comfortably under target, with HPA\* leaf-size/bit-width as the tunable knob. The
estimate becomes empirical once all persisted structures exist (see §11).

## 7. The pathfinding engine

Two-tier, lazy, hierarchical A\*.

### 7.1 Tiers

- **Region tier (`RegionPathfinder`)** — A\* over the persisted HPA\* graph using the
  LCA super-region of source+target, planning **one hierarchy layer at a time** and
  refining sub-region plans **lazily** as the bot descends. Produces a `RegionPathPlan`
  (an ordered region skeleton). This is what makes multi-thousand-block goals tractable.
- **Block tier (`BlockPathfinder`)** — A\* over the recomputed nav grid **within one
  region**, targeting the **shared boundary face with the next region** in the route —
  i.e. *any* traversable cell on that face, whichever is reached first/cheapest (not a
  single "portal entry" point; that `PortalShape.getNearestEntryTo` notion was the old
  semantic-region design). Dimension portals are different — those are an `EnterPortal`
  movement (§7.2), not a region-boundary face. Produces a `BlockPathPlan`.
- **`PathPlan`** unifies them: walks the region skeleton, invokes the block planner
  per region just-in-time, preloads the next region while executing the current.

### 7.2 Movement vocabulary

Adopt the proven movement set from **[Baritone](https://github.com/cabaletta/baritone)**
(the prominent Minecraft pathfinding bot we studied), add a portal op, and make it
**extensible** via a `Movement` interface (Strategy pattern) so new types can be
registered later.

**Core movements (from Baritone):** Traverse (walk/sprint), Ascend, Descend,
Diagonal, Pillar (up), Downward (down), Fall (multi-block), Parkour (gap jump).
**Orebit additions:** **EnterPortal** (Nether/End/region transition).
**Planned/extensible (not Baritone):** Swim, Crawl (1-tall gaps via trapdoor/sneak),
wall-clutch, boat/minecart — added later through the same interface.

**Interactions are folded into movements, not separate ops.** Breaking, placing,
and **toggling doors/gates/trapdoors** don't change position, so they are *part of*
the move that needs them (e.g. "Traverse, breaking the block in the way"; "Ascend,
placing a block beneath"; "Traverse, opening the door"). A movement therefore carries
its positional transition **plus** the interactions it performs, each contributing
cost and validity. This matches Baritone for break/place and extends it to door-like
interactions Baritone ignores.

`Movement` contract (per candidate, given a `MovementContext`): destination,
`cost()` (ticks, inventory-aware), `isValid()`, an execution state machine, and the
interactions performed. New movement types subclass this and register; the planner
filters candidates via `PathFollower.supports(...)`.

### 7.3 Cost model

Adopt Baritone's **tick-based** model (its strongest part; ours was unspecified).

- **Unit: game ticks, `float`, everywhere** (resolves the existing `int Portal.cost`
  vs `float` op-cost mismatch — `Portal.cost` becomes `float` ticks).
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

- **Block-level: admissible** (Manhattan / Euclidean / octile `diagonal·√2 + straight`
  / directional). **Orebit can stay admissible — and thus optimal locally — because
  the region tier handles scale;** Baritone must use inadmissible weighting precisely
  because it is flat. This is a real advantage of the hierarchy.
- **Region-level:** Simple (Euclidean centers), PortalCount, VerticalityPenalty,
  TagAware, ExplorationBias.
- **Cross-dimension:** overworld-frame conversion (§6.5).

### 7.5 Execution & support cast

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

- **Reading** uses the optimized path (bypass the World API → `ChunkSection` →
  reflect into `PalettedContainer` internals, scanning sequentially). Per-palette
  results: Singular ≈ free (and should hit the **uniform-section bypass**), Array/BiMap
  a few ns/block. Choose the read strategy per palette type.
- **Benchmarking** is via the JMH harness (`./gradlew jmh`), run headless inside the
  Fabric Knot classloader (`fabric-loader-junit`, `forks=0`). Budget perf as a
  **ratio to Minecraft's own chunk-generation cost** on the same machine, not absolute
  ns, so we don't over-fit to fast hardware.
- The committed `NavSectionBuilder` STEP code is buggy benchmark scaffolding; the JMH
  benchmark holds the corrected reference implementations.

## 9. Portability (cross-cutting, plan early)

Supporting multiple **loaders** and **Minecraft versions** is a **shared-core +
thin-adapter** problem, not separate codebases — and cheapest to set up **now**, while
most code is still stubs (the coupling surface multiplies as subsystems are written).

**Concrete targets (in priority order):**

| | Loader + version | Purpose | Difficulty |
|---|---|---|---|
| **Current build** | Fabric + 1.21.4 | what exists today | — |
| **Goal 1** | **Forge + 1.20.1** | run alongside a Forge modpack (furniture/biome packs) | medium |
| **Goal 2** | **Fabric + 26.2** (latest vanilla) | remote server for vanilla clients | medium |
| **Stretch** | **1.12.2** (any loader) | the "golden-age modding" servers | **hard** |

- **Loaders:** Fabric API is touched in only ~2 files. Extract a small `PlatformEvents`
  interface; use **Architectury** (Loom + API), which targets **Fabric, Forge, and
  NeoForge** from one shared core. NeoForge is a 2023 fork of Forge and is *not*
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
  *per block* reintroduces exactly the dispatch/megamorphism overhead `block_reading.md`
  fought to eliminate. There, **select the concrete version-specific implementation once
  at load** (e.g. `NavSectionBuilder_1_21` vs `NavSectionBuilder_1_20`) so the inner loop
  calls the versioned API directly and stays monomorphic / JIT-inlinable. The thin
  platform interface lives only at the **selection boundary**, never inside a tight loop.
- **The 1.12.2 stretch is genuinely hard** and called out separately: 1.12.2 predates
  "the Flattening" (1.13), so blocks use **numeric IDs + metadata** instead of the
  blockstate/`PalettedContainer` model the whole world model is built on. That is a
  fundamental data-model divergence (different registries, NBT, networking), so 1.12.2
  is effectively a second port of the world-model's block layer behind the same port
  interface — worth it for the heavily-modded server audience, but a stretch.

This is a structural decision to make before heavy implementation, even if the
adapters are filled in over time.

## 10. Build plan (phased, dependency-ordered)

- **Phase 0 — Foundation hygiene + platform-readiness audit.** Pick the canonical
  architecture (converge the root prototype and the `manager/agent/data` design);
  resolve duplicate identities (two `BotManager`s, `Fake*` vs `Mock*`,
  `PathfindingSettings` in two packages); remove dead experiments
  (`ProxyNavigationEntity`, `FollowPlayerOwner`, `benchmarkMe()` in the join hot path);
  stand up the multi-loader/multi-version project skeleton (§9). **Audit existing code
  for version/loader-coupled API calls** (the fake-player network stack, the
  `PalettedContainer` reflection, hardcoded `Blocks.X`/`Fluids`/registry usage, the
  Fabric event registrations) and classify each: **cold** → route behind the platform
  interface; **hot** → mark for a version-selected concrete implementation (§9), not a
  per-call interface. Align to design principles where cheap.
- **Phase 1 — Make the world-model pipeline actually run.** Finalize NavBlock
  (per-BlockState, short index, palette codec); fix `NavSectionBuilder.build()` to a
  correct read+classify with the uniform-section bypass, **structured as a
  version-selected concrete class** (versioned chunk/palette APIs called directly on the
  hot path, chosen once at load — no per-block dispatch); wire `ChunkNavLoader` to store
  nav sections instead of discarding them; recompute on load. Verify via JMH + gametest.
- **Phase 2 — Fixed-grid regions + resource octree.** Implement the grid `RegionBuilder`
  (coordinate-math assignment + incremental update); populate and **persist** the
  sparse resource octree; implement the ascend/descend resource search.
- **Phase 3 — HPA\* graph.** Build face-to-center costs (4-bit log, 16³ leaf), the
  octree roll-up, local portal edges; **persist** it; implement `RegionPathfinder`
  (LCA + lazy layer A\*) and the cross-dimension heuristic.
- **Phase 4 — Block pathfinder + movements + cost model.** Implement the `Movement`
  interface and the Baritone-based movement set (+ EnterPortal, + folded
  break/place/door interactions); the tick-based cost model with inventory snapshot;
  admissible block heuristics; `BlockPathfinder`; the support cast (follower,
  replanner, budget, cache, smoother).
- **Phase 5 — Integration.** Replace `AllyBotEntity`'s imperative `tick()` follow with
  the real engine driving the bot via `VirtualPlayerController`. The bot navigates to
  a goal through the full region→block pipeline.
- **Later PRDs:** LLM intent, AI/task execution, behavior/memory/social, commands.

## 11. Testing & measurement

- **JMH** for read/classify/cost micro-benchmarks (headless, Claude-runnable).
- **Fabric GameTest** (`runGametest`, headless) for end-to-end pipeline validation on
  real generated chunks.
- **Empirical disk measurement** — deferred until *all* persisted structures (resource
  octree + HPA\*) exist, then measure true compressed bytes/chunk over the dev world
  and compare to the ~6–8% estimate.
- **Determinism tests** — same world + seed → identical nav data, regions, and paths.

## 12. Decisions log

Ratified during design review (with rationale recorded in project memory):

1. NavBlock identity is **per-BlockState**; **short** index → **packed 64-bit `long`**
   descriptor; palette-compressed on disk, expanded in memory. *Measured:* 27,866
   states → 587 distinct navtypes (48× collapse; ~4.6 KB table, L1-resident).
2. Nav grid is **recomputed on load**, never persisted.
3. Regions are a **fixed cubic grid**, not semantic.
4. Resource tracking is a **sparse log₂ octree** over ~64 classes; section-granular.
5. HPA\* stores **face-to-center cost** (not connectivity); **16³ leaf, 4-bit
   log-scale**; **portals as local edges**; **8:1 conversion only in the heuristic**.
6. Disk budget ~6–8%/dimension, under the 10–15% target.
7. Movement vocabulary = **Baritone set + EnterPortal**, **extensible interface**,
   **break/place/door folded into moves**.
8. Cost model = **tick-based**, Baritone-seeded; mining = inverse tool strength +
   falling recursion; `COST_INF` cap for the impractical. **Bot ≠ player:** food
   ignored; health, breath, and tool durability are **configurable**; **consumables
   (blocks, durability) tracked along the path** (not snapshotted once) so a route that
   would run out is rejected, not discovered mid-execution.
9. Block heuristics stay **admissible** (the hierarchy earns optimal local paths).
10. Portability = **shared core + adapters** (Architectury/NeoForge, Stonecutter),
    structured early.

### Open questions

- Exact NavBlock index bit-layout and how much orientation to inline vs table.
- HPA\* leaf cost: face-to-center everywhere vs face-to-face at the finest level.
- Whether resource memory persists per-section or per-chunk (rebuild fine octree on
  load).
- Final tuned cost constants (Baritone's are a starting point).
- NeoForge vs Forge; how aggressively to pursue multi-version in v1.
