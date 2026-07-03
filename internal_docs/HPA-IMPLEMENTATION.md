**STATUS: infrastructure reference.** The center-node cost model this doc originally specified — six
face→center cost buckets per region, computed by bounded mini-pathfinds and rolled up by a min-crossing
pyramid merge — was **deleted in the s36 cleanup**. The region-tier node model is now **fragments**
(`(region, fragment)` nodes, edges derived at query time from fragment-footprint geometry); the authoritative
spec is **`HPA-FRAGMENTS.md`**, and the stateful multi-level driver is **`HPA-CASCADE.md`**. The sections below
describe the **surviving infrastructure** that both models share — addressing, the SoA pyramid store, the cost
codec, unbuilt-node defaults, the sliding-window `PathPlan` driver, the `AllyBotEntity` wiring, incremental
maintenance, the corridor bound, and eager on-load build. Where a section described center-model machinery it is
**reduced to a one-line tombstone** pointing at what replaced it. §-numbers are preserved: `RegionBound.java`
and `RegionAddress.java` reference this doc's **§9** and **§15a** by number.

# HPA\* region tier — implementation design (the coherence lock)

> Implementation-level companion to **PRD §6.3–6.5, §7.1, §10 Phase 3**. The PRD is the *what/why*;
> this is the *exact how* — class names, packages, constants, packing formulas, and the external signatures to
> call. Where this note and the PRD agree, the PRD wins on intent; this note wins on signatures/constants.
> Author on `core`, merge to eras.

## 0. Scope & sequencing (historical — milestone shipped)

This tier was built milestone-first: an in-memory pyramid over loaded terrain (addressing → leaf cost → merge →
defaults) → `RegionPathfinder` → sliding-window `PathPlan` → `AllyBotEntity` wiring, proving a long goal that
flat block-A\* FAILs/floods. Incremental maintenance (§12), persistence (§11, still deferred), and the coarse
cascade (`HPA-CASCADE.md`) layered on after. The leaf-cost and merge internals below (§5, §7) are the
**superseded center model**; the fragment replacements live in `HPA-FRAGMENTS.md` §2–§7.

## 1. Package layout & file list

**Data layer — `com.orebit.mod.worldmodel.hpa`** (sibling of `worldmodel.pathing` / `worldmodel.navblock`):

> *Fragment-model note: the center-model leaf/merge classes below were repurposed or joined by the fragment
> classes — `RegionFragments` (the per-region footprint record), `FragmentBuilder`/`FragmentLeafComputer`
> (flood-fill component extraction + occupiability filter, replacing the bounded-A\* face costing), and
> `LeafCostComputer` now holds only the shared tick-cost **constants** (`MINE_PER_BLOCK`, `AIR_TRANSIT_TICKS`,
> `AIR_CLIMB_TICKS`, swim). The addressing, codec, SoA pyramid, and façade rows are unchanged.*

| File | Kind | Owns |
|---|---|---|
| `RegionAddress.java` | `final`, static-only | Addressing math: (level,x,y,z) ⇄ packed key, parent/child, octree→quadtree, world↔region coords. **No objects.** |
| `CostCodec.java` | `final`, static-only | 4-bit log-scale cost quantize/dequantize + `COST_INF`; the on-disk (un)packer. |
| `CostPyramid.java` | `final` | The per-dimension SoA store: per-level open-addressed `long`→row map + per-row `RegionFragments`. Get/put/ensure, the merge driver. |
| `LeafCostComputer.java` | `final`, static-only | The shared region-tier **cost constants** (post-s36; the old six-face bounded-A\* leaf costing was deleted — see §5). |
| `FragmentLeafComputer.java` / `FragmentBuilder.java` | `final`, static-only | Fragment leaf build: 6-connected flood fill + occupiability filter + `kind`/`avgSolidHardness` classification (`HPA-FRAGMENTS.md` §S1). |
| `RegionFragments.java` | `final` | Per-region record: `kind`, `avgSolidHardness`, per-`(fragment,face)` footprint bbox. Replaces the 12 face-bytes. |
| `PyramidMerger.java` | `final`, static-only | Roll a node up from its children — **UF over child fragments** (`HPA-FRAGMENTS.md` §5/§7); re-merge ancestors on leaf change (§12). |
| `RegionGrid.java` | `final` | Per-`ServerLevel` façade: holds the dimension's `CostPyramid`, builds leaves lazily from `NavStore`, answers fragment/kind/footprint queries. The region-tier analog of `NavGridView`. |
| `HpaMaintenance.java` | `final`, static-only | 3f: `BlockChangeEvents` listener → dirty leaf → recompute → re-merge ancestors (debounced). |
| `HpaPersistence.java` | (deferred) | 3e: `SavedData` codec per dimension (§11). |

**Region-tier search — `com.orebit.mod.pathfinding.regionpathfinder`:** `RegionPathfinder` (A\* over the pyramid,
fragment edges derived at query time), `RegionPathPlan` (ordered region sequence + per-step `fragmentId` +
`portalCell`), `RegionHeuristic` + `heuristics.SimpleRegionHeuristic` (admissible Euclidean-centers heuristic);
`HierarchicalRegionPlan` (the cascade stack, `HPA-CASCADE.md`). The `PortalCount/TagAware/ExplorationBias/
VerticalityPenalty` heuristics remain stubs (they lean on the superseded semantic/portal model — do NOT
implement).

**Edited real files:** `worldmodel.pathing.NavGridView` (a public `overSections` factory so `hpa` can build a
bounded synthetic view); `com.orebit.mod.AllyBotEntity` (§10 — the two-tier driver replaces the single
`BlockPathfinder.findPath` in `replan`); `com.orebit.mod.OrebitCommon` (register `HpaMaintenance`; persistence
hooks when 3e lands).

**Do NOT build on:** `worldmodel.region.*` (the superseded semantic Region/Portal/flood-fill model — PRD §6.3).
`NavSection.candidateRegions` still references `region.Region`; leave it, the HPA\* tier does not use it.

## 2. `RegionAddress` — addressing math (3a)

Constants:
```java
public static final int LEAF_BITS   = 4;           // 16³ leaf = 2^4 blocks per side
public static final int LEAF_SIZE   = 1 << LEAF_BITS; // 16
public static final int PAD_HEIGHT_BITS = 9;        // padded vertical extent = 512 = 2^9
public static final int PAD_HEIGHT  = 1 << PAD_HEIGHT_BITS; // 512
public static final int OCTREE_TOP  = 5;            // levels 0..5 octree (cell side 16..512);
                                                    // at level 5 the cell spans the full padded height
public static final int MAX_LEVEL   = 22;           // dimension root (~6 octree + ~17 quadtree); clamp here
```
A node is `(level, rx, ry, rz)` — region coords at that level. **`paddedMinY`** for a level = the level's
`minY` floor; use the live `minY = LevelBounds.minY(level)` from the world and treat the vertical origin
as `minY` (overworld −64). Region coords:
```java
// world block (wx,wy,wz) → region coords at `level`
int shift = LEAF_BITS + level;                 // cell side = 1<<shift blocks
int rx = wx >> shift;
int rz = wz >> shift;
int ry = ((wy - minY) >> shift);               // vertical, from the dimension floor
if (level >= OCTREE_TOP) ry = 0;               // quadtree (and the level-5 single slab): one vertical cell
```
Parent: `level+1`, `rx>>1, rz>>1`, and `ry>>1` **only while `level+1 <= OCTREE_TOP`** (octree); at/above
the transition `ry` stays 0. Children: the inverse — 8 children below the transition (ry∈{0,1}), 4 above
(ry fixed 0). Node center in world coords: `cx = (rx<<shift) + side/2` (and `cz` likewise), `cy = minY +
(level >= OCTREE_TOP ? PAD_HEIGHT/2 : (ry<<shift) + side/2)`, with `side = 1 << (LEAF_BITS + level)`.

**Per-level packed key** (the `CostPyramid` map key — one `long`, distinct keyspace per level so each
level has its own map): pack the (possibly negative) region coords. Region coords fit: at level 0,
`|rx| ≤ 30_000_000 >> 4 ≈ 1.875M` → 22 bits signed; `ry` ≤ 32 → 6 bits. Use:
```java
// 22 bits rx | 22 bits rz | 6 bits ry  (mask, no sign-extend needed — keys only ever compared for equality)
static long packLevelKey(int rx, int ry, int rz) {
    return ((rx & 0x3FFFFFL) << 28) | ((rz & 0x3FFFFFL) << 6) | (ry & 0x3FL);
}
```
(Equality-only keys, so masking-without-sign-extend is fine: two distinct coords never collide within a
level because each component is within its bit width.) Provide `unpack` helpers that sign-extend for when
a coord must be recovered (the pyramid keeps parallel `int[] rx/ry/rz` alongside the row so it never has to
unpack — see §4).

Face indexing — **canonical order, used everywhere** (`byte` 0..5):
```
0 = -X (WEST)   1 = +X (EAST)   2 = -Y (DOWN)   3 = +Y (UP)   4 = -Z (NORTH)   5 = +Z (SOUTH)
```
`opposite(face) = face ^ 1`. Neighbor in region coords: face 0→(rx-1), 1→(rx+1), 2→(ry-1), 3→(ry+1),
4→(rz-1), 5→(rz+1).

## 3. `CostCodec` — 4-bit log-scale cost (storage)

15 real buckets + an INF sentinel:
```java
public static final int  BUCKET_INF = 15;          // "effectively impassable" (void / out of world)
public static final float BASE_TICKS = 1.0f;       // bucket 0 ≈ 1 tick
public static final float LOG_STEP   = 0.7f;       // ×2^0.7 per bucket → buckets 0..14 span ~2^9.8 ≈ 900×
public static final float COST_INF   = 1.0e6f;     // dequantized INF (a large cap, NOT Float.INF — keeps A* arithmetic finite)

static int quantize(float ticks) {                 // ticks>0 → bucket 0..14 (clamp); ≤0 → 0
    if (ticks <= BASE_TICKS) return 0;
    int b = Math.round((float)(Math.log(ticks / BASE_TICKS) / Math.log(2)) / LOG_STEP);
    return Math.max(0, Math.min(14, b));
}
static float dequantize(int bucket) {              // bucket → representative ticks; INF → COST_INF
    if (bucket >= BUCKET_INF) return COST_INF;
    return (float)(BASE_TICKS * Math.pow(2, bucket * LOG_STEP));
}
```
Tunable; document LOG_STEP as the fidelity knob. The bucket (a nibble) is the persisted form; the region A\*
works in dequantized ticks. Under the fragment model the codec still owns the on-disk (un)packing — now the
`avgSolidHardness` nibble and the per-face footprint nibbles (`HPA-FRAGMENTS.md` §5), not per-face cost buckets.

## 4. `CostPyramid` — the SoA store

One instance per dimension. Per level: an open-addressed `long`→row map (copy the idiom from
`NavGridView.lookupChunk` / `BlockPathfinder.Nodes` — murmur3 finalizer, power-of-two, `-1` empty, linear
probe, grow at 3/4). Parallel arrays grown on fill; each row carries a lazily-materialized `RegionFragments`
record (the fragment model's replacement for the old flattened `byte[] face`):
```java
final class Level {
    long[] mapKey; int[] mapRow; int mapMask, mapSize, mapGrowAt;   // key→row
    int[] rx, ry, rz;               // region coords per row (so planning never unpacks the key)
    RegionFragments[] frags;        // per-row fragment record (kind, avgSolidHardness, per-(frag,face) footprint)
    int count;
}
Level[] levels = new Level[RegionAddress.MAX_LEVEL + 1];
```
API: `rowFor` (intern; create if absent), `rowIfPresent` (−1 if absent, no create), `ensureFragments`
(materialize the row's `RegionFragments` in place so the leaf builder fills it with no throwaway copy), and
`isBuilt` (a leaf/node whose fragments were actually computed vs an interned-but-default placeholder — a
parallel `built` bitset). New rows initialize to an **optimistic uniform default** and `built=false`.
`favour-cpu-over-ram`: fragment rows are unpacked convenient fields in RAM (like the old 12-byte rows), packed
only on disk (§11).

## 5. Leaf face→center cost — DELETED (center model)

> **Tombstone.** This section specified `LeafCostComputer.computeLeaf` running up to **six bounded block-A\*
> mini-pathfinds** per leaf (one per face, over a one-section `NavGridView.overSections` view) to fill six
> face→center cost buckets, with all-air/all-solid fast paths. **Deleted in s36.** Replaced by
> `FragmentLeafComputer`/`FragmentBuilder`: a single 6-connected **flood fill** of the leaf's passable cells +
> an occupiability filter → `RegionFragments` (per-`(fragment,face)` footprint bbox + `kind` +
> `avgSolidHardness`), ~13 µs/leaf vs the old ~60 µs. Edge costs are then **derived at query time** from
> footprint geometry (see §8 and `HPA-FRAGMENTS.md` §2.2). `LeafCostComputer` survives only as the shared cost
> **constants** (`MINE_PER_BLOCK`, `AIR_TRANSIT_TICKS`, `AIR_CLIMB_TICKS`, swim) that derivation divides by
> `LEAF_SIZE`.

## 6. Defaults for missing/unloaded nodes (3d)

A planner read of a node that is interned-but-`!built` (no nav data / unloaded) returns an **optimistic
admissible default** so the region A\* heuristic stays admissible and planning over unexplored terrain
works: the node reads as an **optimistic uniform kind** (≈ free-walk crossing, `AIR_TRANSIT_TICKS` scaled by
the node's side at higher levels). Optimism is required — an over-estimate would make the heuristic
inadmissible and could refuse a real route; the live nav grid refines on approach. `RegionGrid.faceCost`/the
fragment accessors are the single chokepoint that returns built data if present else this default.

## 7. `PyramidMerger` — roll-up (3c)

> **Tombstone (operator replaced, driver kept).** The original operator here was an approximate
> **square-pyramid min-crossing** over the children's six face-cost buckets (`crossing = min over children
> on face F of dequantize(child.face[F])` + a half-traversal term). **Deleted in s36** — it was lossy in the
> same way the leaf center-model was (it assumed every face connects through the parent center → the coarse
> bounce). Replaced by **union-find over the 8 children's fragments** (connect two when adjacent children's
> footprints overlap on the shared internal face; project each component's outer-flush footprints onto the
> parent face) — `HPA-FRAGMENTS.md` §5/§7. What **survives** is the *driver* shape: `mergeUp` walks
> parent→root recomputing each ancestor from its children, **O(levels)** per leaf change, early-out the moment
> a level's recompute is unchanged (§12). Coarse merge is the deferred slice; the level-0 direct branch reads
> only leaves.

## 8. `RegionPathfinder` + `RegionPathPlan`

`RegionPathPlan` = an **ordered sequence of level-0 region addresses** (the coarse skeleton), start→goal, plus,
per step, the committed `fragmentId` and the `portalCell` it is entered through (the fragment model's real
occupiable boundary cell, replacing the old geometric center projection). Immutable. Shape: `size()`,
`rx/ry/rz(i)`, `centerOf(i)`, `fragmentId(i)`, `portalCell(i)`, `isFragmentPlan()`.

`RegionPathfinder.plan(ServerLevel, RegionGrid, startFloor, goalFloor)` runs an allocation-light SoA A\* over
the region grid (open-addressed map keyed by `packLevelKey ⊕ fragmentId`, binary heap), heuristic
`SimpleRegionHeuristic` (Euclidean region centers × min-cost/region → **admissible**), `MAX_REGION_EXPANSIONS`
cap with a best-so-far partial backstop. Leaves are built lazily via `RegionGrid.ensureLeaf` before their
fragments are read (else the §6 default). The scale guard / lazy coarse refinement (`LEVEL0_DIRECT_CAP`) and
the stateful multi-level version are `HPA-CASCADE.md`.

> **Tombstone (edge model).** The original §8 gave center-model A\* pseudocode whose edge crossing from node
> `N` out face `F` into neighbor `M` was `N.faceCost(F) + M.faceCost(opposite(F))` (the implicit boundary sum
> of two stored face-cost halves). **Deleted in s36.** Edges are now **derived per expansion from fragment
> geometry**: a node is `(region, fragment)`; from `(N, fragA)` we emit **portal edges** (cheap, to a
> neighbor fragment whose face footprint bbox overlaps `fragA`'s on the shared face), **sibling mine edges**
> (expensive, to the region's other fragments), and **uniform-kind transit** — all costed by the footprint→
> footprint walk/pillar/fall/mine formula in `HPA-FRAGMENTS.md` §2.2. No per-face cost is stored.

## 9. `PathPlan` — sliding-window driver + the wiggle rule

`PathPlan` unifies the region skeleton + per-window block plans. Constructed per goal; ticked by the follower.
```java
public final class PathPlan {
    PathPlan(ServerLevel level, RegionGrid regionGrid, BlockPos startFloor, BlockPos goalFloor, BotCaps caps);
    BlockPathPlan currentBlockPlan();    // the active windowed block path (may be null → BLOCKED/FAILED)
    PathStatus status();
    void onBotMoved(BlockPos botFloor);  // called each tick with the bot's current floor cell; advances the
                                         // window + replans when the bot COMMITS into the next region
    boolean isComplete();
}
```
Internals & flow: on construct, `skeleton = RegionPathfinder.plan(...)` (today via the
`HierarchicalRegionPlan hier` source — `HPA-CASCADE.md` §9), `windowStart = 0`, `WINDOW = 3` regions covering
`skeleton[windowStart .. min(windowStart+WINDOW-1, last)]`. The **block target** is the real `goalFloor` when
the goal's region index ≤ the window's last index, else the window-last step's **`portalCell`** (the fragment
model's occupiable boundary; the old center-projected-to-floor was the center-model form). `replanBlock()` =
`BlockPathfinder.findPath(new NavGridView(level), botFloor, blockTarget, caps, bound)` over the loaded grid
confined to the corridor (§15a), setting status FOUND/BLOCKED.

**The wiggle rule (only the FINAL committed crossing advances the window):**
The block path may weave across region boundaries many times (block-A\* ignores regions). Track
`committedIndex` = the furthest skeleton index the bot has *committed* to (init 0). On `onBotMoved(botFloor)`:
```
curRegion = level-0 region of botFloor
j = index in skeleton[committedIndex .. window-last] whose coords == curRegion   // search forward only
if j > committedIndex AND committed(j):                                          // a real forward step
    committedIndex = j
    windowStart = j
    rebuild window target; replanBlock()
// transient dips back to skeleton[<committedIndex] do NOT lower committedIndex and do NOT replan
```
where `committed(j)` is the hysteresis test that distinguishes the FINAL crossing into a region from a
transient wiggle: **the bot has committed to skeleton[j] iff none of the block path's REMAINING waypoints lie
in any skeleton region with index < j** (the path never goes back). Implement by scanning the active
`BlockPathPlan`'s remaining waypoints, mapping each to its level-0 region, and checking none maps to
`skeleton[committedIndex .. j-1]` (cheap: ~48 blocks of waypoints). This makes A→B→A→B→A→B→C advance the
window to B exactly once and to C when the path stays in C. Fallback if the waypoint scan is inconclusive
(e.g. path null): advance only on `botFloor` inside `skeleton[j]` for `COMMIT_TICKS = 3` consecutive ticks.
- Replan also when `currentBlockPlan == null` (BLOCKED — terrain changed) or the bot exhausts the current
  block plan before reaching the window target.
- `isComplete()` when the bot's floor is within block-A\*'s goal tolerance of `goalFloor`.

## 10. `AllyBotEntity` wiring (replace the one-tier call)

Today `replan(goalFloor)` holds a `PathPlan pathPlan` and a cached-per-level `RegionGrid`:
```
RegionGrid rg = RegionGrid.of(level);                 // cached per ServerLevel
this.pathPlan = new PathPlan(level, rg, startFloor, goalFloor, CAPS);
this.path = pathPlan.currentBlockPlan();              // keep the existing follower working off `path`
```
Each tick in the follower: after moving, call `pathPlan.onBotMoved(this.blockPosition().below())`, then
`this.path = pathPlan.currentBlockPlan()` (reset `waypointIndex` to 0 when the block plan instance changes —
track by identity). The existing `steerAlongPath` / `applyEdits` machinery is **unchanged** — it just now
follows windowed block plans fed by the region tier. `CAPS = BotCaps.BREAK_PLACE`. When `currentBlockPlan()`
is null, log and fall back to straight-line steer (pathological failures stay visible).

## 11. Persistence (3e) — `SavedData`, deferred body

Per-dimension `net.minecraft.world.level.saveddata.SavedData` named e.g. `"orebit_hpa"`, stored via
`ServerLevel.getDataStorage().computeIfAbsent(...)`. Serialize the `CostPyramid`: per level, the sparse list of
`(rx,ry,rz, RegionFragments, built bit)` — under the fragment model the persisted body is the **region
fragment footprints** (`kind` + `avgSolidHardness` + per-`(fragment,face)` footprint nibbles, the `CostCodec`
bitstream of `HPA-FRAGMENTS.md` §5), **not** the old six face-cost buckets. **Leaves are recomputed on load**
from the resident nav grid (flood fill is cheap → zero leaf disk); only merged levels ≥1 persist. Target ~2%
of save (PRD §6.6). **NOTE version coupling:** `SavedData`'s save signature drifts across MC versions
(`save(CompoundTag)` ⇄ `save(CompoundTag, HolderLookup.Provider)`); if it diverges across 1.17→26.x, put the
thin `SavedData` subclass behind the `platform/` seam or an overlay, keeping the codec logic in core. Implement
AFTER the milestone; `HpaPersistence` is not yet a file.

## 12. Incremental maintenance (3f)

`HpaMaintenance.register()` adds a `BlockChangeEvents.Listener` (thread-safe — listeners can fire off the
tick thread during worldgen; mirror `NavGridUpdater`'s server-only guard). On a change at `pos`: mark the
containing level-0 leaf **dirty** (a per-level concurrent set). A debounced pass on world-tick-end (reuse the
`onWorldTickEnd` cadence, ≤N leaves/tick) **refloods** each dirty leaf's fragments (`FragmentLeafComputer`) and
`PyramidMerger.mergeUp`s its ancestors. Flood is symmetric — break and place are the same "recompute the
components" op, so there is no per-cell fragment label and no merge-vs-split detection (`HPA-FRAGMENTS.md`
§6.5). Debounce so a bulk edit (TNT, fill) refloods each touched leaf once per batch. Register in
`OrebitCommon.init` next to `NavGridUpdater.register()`.

## 13. Milestone test / benchmark (the "earns its keep" proof)

`HpaMilestoneTest` (mc-1.21 era, headless). Build synthetic in-memory terrain (reuse `PathfinderBenchmark`
patterns) for two scenarios flat block-A\* FAILs/floods on — a long flat walk (3000+ blocks, past the 10k cap)
and a 30-up open-air pillar — and assert the region tier produces a skeleton and each windowed block-A\* solves
its ~48-block window in ≪ `MAX_EXPANSIONS`, reaching the goal. The clean before/after (partial-path still
absent, so nothing masks the signal).

## 14. House-style constraints (do NOT violate — these are why the block tier is fast)

- **No heap allocation on hot paths.** The region A\* and the per-window block-A\* run during a replan;
  reuse SoA arrays / open-addressed maps (copy the `NavGridView`/`Nodes` idioms), no `Map<Long,X>` boxing,
  no `new` in loops. The pyramid's per-level arrays grow-and-reuse.
- **Favour CPU over RAM** in memory (unpacked fragment rows, not bit-packed); bit-pack only on disk.
- **Statically-sized / primitive structures**, murmur3-finalized open addressing, `-1` empty markers —
  match the existing files verbatim in idiom.
- **Platform seam:** any version-divergent MC call (`LevelBounds`, `ChunkCoords`, `SavedData`,
  `getDataStorage`) goes through `platform/` or an overlay, never inlined into core hot loops.
- **Strategy over conditionals:** `RegionHeuristic` is an interface; new heuristics register, no enum switch.
- Keep `final` off classes the design-principles file says to (match surrounding code; the existing pathfinder
  classes are `final` static-utility — mirror that for the static-only helpers).

## 15. Post-build refinements (design review — corridor bound + eager build)

Two gaps surfaced in review after the initial build; both are now implemented.

### 15a. Corridor bound — the spatial confinement that actually fixes the pillar (extends §9)

The sliding window alone does **not** fix the open-air pillar. `replanBlock` runs block-A\* toward a target
only ~3 regions away, but the pillar is a *heuristic* problem (h under-estimates the forced `PLACE` cost of
going up), and a nearer target doesn't fix an under-estimate — the search still floods horizontally over the
**unbounded** grid to the cap. The fix is a **spatial corridor bound** (a standard hierarchical-pathfinding
technique):

- **`RegionBound`** (`pathfinding/blockpathfinder/`) — a pure world-space AABB, `allows(x,y,z)` = six int
  compares. No `hpa` dependency (keeps the block tier independent of the region tier).
- **`BlockPathfinder.findPath(grid, start, goal, caps, RegionBound bound)`** — the bound is enforced at the
  **single choke point** `Relaxer.accept`: `if (bound != null && !bound.allows(nx,ny,nz)) return;` — one
  branch per discovered candidate, no movement touched. The 3/4-arg forms delegate with `bound = null`
  (historical unbounded behaviour preserved). The start cell is interned directly, so it is always admitted;
  the caller must keep the goal/target inside the box.
- **`PathPlan.corridorBound(target)`** — the window's world-space AABB enclosing its skeleton regions (+ the
  start/target cells), expanded by `CORRIDOR_MARGIN = 16` (one region) horizontally and `CORRIDOR_VMARGIN = 8`
  vertically. The one-region margin is the knob: it **admits a beneficial one-region dip** into a neighbour
  the coarse cost couldn't see, while **forbidding 2+-region wandering** — so the pillar's horizontal flood is
  capped and the search is forced to ascend. `replanBlock` passes the bound; on `null` it **widens once**
  before declaring BLOCKED, so a too-tight corridor never permanently traps a solvable path.
- A single AABB per window (not the exact region union) is intentional: cheapest per-candidate test, at worst
  slightly permissive on a short bent skeleton (harmless).

### 15b. Eager on-load region build — fill the pyramid as terrain is generated (extends §12)

Lazy plan-driven leaf building (`RegionGrid.ensureLeaf`) leaves a gap: explore a large world, let the chunks
unload, then path — the nav grid for those chunks is gone (`ChunkNavLoader` drops it on unload), so leaves
can't be built on demand. Fix: build region leaves **when a chunk's nav data is built**, while it is resident.

- **`HpaMaintenance.onChunkNavBuilt(level, chunkX, chunkZ)`** — for each resident 16³ section in the column,
  reflood the fragment leaf + `PyramidMerger.mergeUp`. Ensures the dimension's `RegionGrid` exists, so the
  pyramid fills as the world is explored even before any plan.
- **Called from `ChunkNavLoader`** right after `NavStore.put`, on the tick thread bounded by that loader's
  existing `MAX_BUILDS_PER_TICK` budget. (Introduces a `pathing → hpa` import; acceptable — a notification from
  the nav pipeline to the region tier built on top of it.)
- **Why this closes the travel-then-path gap:** the `CostPyramid` lives per-`ServerLevel` and is **not**
  dropped on chunk unload (only on level unload), so leaves built while a chunk is resident **persist in RAM**
  after it unloads — the bot's in-session memory of explored terrain. Surviving a server *restart* still needs
  disk persistence (§11, deferred).
- **Perf notes (deferred):** most sections are uniform air/solid → the fragment builder fast-paths (no flood
  needed); batch the per-column ancestor merge. Fast travel can still outrun the per-tick build budget, leaving
  best-effort gaps the optimistic §6 default covers until disk persistence lands.
