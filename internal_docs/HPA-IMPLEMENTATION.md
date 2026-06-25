# HPA\* region tier — implementation design (the coherence lock)

> Implementation-level companion to **PRD §6.3–6.5, §7.1, §10 Phase 3**. The PRD is the *what/why*;
> this is the *exact how* — class names, packages, constants, packing formulas, algorithms (with
> pseudocode for the subtle bits), and the external signatures to call. **Every build agent reads this
> and follows it verbatim** so the parallel work stays coherent. Where this note and the PRD agree, the
> PRD wins on intent; this note wins on signatures/constants. Author on `core`, merge to eras.

## 0. Scope & sequencing (milestone-first)

Build the whole tier, but in an order where the **"earns its keep" milestone** (PRD §10 Phase 3) is
reachable and verifiable WITHOUT persistence:

1. **In-memory pyramid over loaded terrain** (3a addressing, 3b leaf cost, 3c merge, 3d defaults) →
   **3g `RegionPathfinder`** → **3h sliding-window `PathPlan`** → wire into `AllyBotEntity`. This alone
   makes a currently-FAILing long goal succeed on loaded chunks — the clean before/after.
2. **Then** layer **3f incremental maintenance** (BlockChange hook) and **3e persistence**
   (`SavedData`) and **3i cross-dimension heuristic**. These extend reach to unloaded/1M-block paths
   but are NOT needed to prove the win.

Do not add partial-path return or a time-cap (those are the NEXT arc, after HPA\* proves itself).

## 1. Package layout & file list

**New data layer — `com.orebit.mod.worldmodel.hpa`** (sibling of `worldmodel.pathing` / `worldmodel.navblock`;
data layers live under `worldmodel`):

| File | Kind | Owns |
|---|---|---|
| `RegionAddress.java` | NEW, `final`, static-only | Addressing math: (level,x,y,z) ⇄ packed key, parent/child, octree→quadtree, world↔region coords. **No objects.** |
| `CostCodec.java` | NEW, `final`, static-only | 4-bit log-scale cost quantize/dequantize + `COST_INF`. |
| `CostPyramid.java` | NEW, `final` | The per-dimension SoA store: per-level open-addressed `long`→row map + `byte[]` face costs (6/node). Get/put/ensure, the merge driver. |
| `LeafCostComputer.java` | NEW, `final`, static-only | 3b: compute a leaf's 6 face→center costs by bounded block-A\* mini-pathfinds; all-air/all-solid defaults. |
| `PyramidMerger.java` | NEW, `final`, static-only | 3c: roll a node's 6 face costs up from its children; re-merge ancestors on leaf change. |
| `RegionGrid.java` | NEW, `final` | Per-`ServerLevel` façade: holds the dimension's `CostPyramid`, builds leaves lazily from `NavStore`, answers face-cost queries for the region A\*. The region-tier analog of `NavGridView`. |
| `HpaMaintenance.java` | NEW, `final`, static-only | 3f: `BlockChangeEvents` listener → dirty leaf → recompute faces → re-merge ancestors (debounced). |
| `HpaPersistence.java` | NEW | 3e: `SavedData` codec per dimension (deferred body; specced in §11). |

**Fill existing stubs (rewrite their Javadoc to the ratified face-to-center design; keep the file path):**

- `com.orebit.mod.pathfinding.regionpathfinder.RegionPathfinder` — 3g: A\* over the `CostPyramid`.
- `com.orebit.mod.pathfinding.regionpathfinder.RegionPathPlan` — 3g output: ordered region sequence.
- `com.orebit.mod.pathfinding.regionpathfinder.RegionHeuristic` — functional interface (admissible).
- `com.orebit.mod.pathfinding.regionpathfinder.heuristics.SimpleRegionHeuristic` — Euclidean centers ×
  min-cost/block. (Leave `PortalCount/TagAware/ExplorationBias/VerticalityPenalty` heuristic stubs as
  stubs — they lean on the superseded portal/semantic model; do NOT implement now.)
- `com.orebit.mod.pathfinding.PathPlan` — 3h: the sliding-window driver.
- `com.orebit.mod.pathfinding.PathStatus` — make it a real `enum { IDLE, RUNNING, COMPLETE, BLOCKED, FAILED }`.

**Edit existing real files (each edited by exactly ONE agent — never two in parallel):**

- `worldmodel.pathing.NavGridView` — ADD a public factory so the `hpa` package can build a bounded view:
  ```java
  /** Public seam for the HPA* leaf-cost computer: a view over a synthetic, already-built section map
   *  with no live level. Delegates to the package-private test ctor. */
  public static NavGridView overSections(int minY, java.util.concurrent.ConcurrentHashMap<Long, NavSection[]> chunks) {
      return new NavGridView(minY, chunks);
  }
  ```
- `com.orebit.mod.AllyBotEntity` — replace the single `BlockPathfinder.findPath(...)` line in `replan`
  with the two-tier driver (§9).
- `com.orebit.mod.OrebitCommon` — register `HpaMaintenance` (alongside `NavGridUpdater.register()`),
  and HPA\* persistence load/save hooks (when 3e lands).

**Do NOT touch / do NOT build on:** `worldmodel.region.*` (the superseded semantic Region/Portal/
flood-fill model — PRD §6.3). Note `NavSection.candidateRegions` still references `region.Region`; leave
it, the HPA\* tier does not use it.

**Test (mc-1.21 era only — `src/test/java`):** `com.orebit.mod.worldmodel.hpa.HpaMilestoneTest`
(headless JMH/JUnit, the milestone proof — §10).

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
(ry fixed 0). Center of a node in world coords:
```java
int side = 1 << (LEAF_BITS + level);
int cx = (rx << shift) + side/2;
int cz = (rz << shift) + side/2;
int cy = (level >= OCTREE_TOP) ? (minY + PAD_HEIGHT/2) : (minY + (ry << shift) + side/2);
```

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
a coord must be recovered (planning reads center coords from the address, so store rx/ry/rz alongside the
row — see §4, the pyramid keeps parallel `int[] rx/ry/rz` so it never has to unpack).

Face indexing — **canonical order, used everywhere** (`byte` 0..5):
```
0 = -X (WEST)   1 = +X (EAST)   2 = -Y (DOWN)   3 = +Y (UP)   4 = -Z (NORTH)   5 = +Z (SOUTH)
```
`opposite(face) = face ^ 1`. Neighbor in region coords: face 0→(rx-1), 1→(rx+1), 2→(ry-1), 3→(ry+1),
4→(rz-1), 5→(rz+1).

## 3. `CostCodec` — 4-bit log-scale cost (3b/3c storage)

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
Tunable; document LOG_STEP as the fidelity knob. Storing the bucket (a nibble) is the persisted form; the
region A\* works in dequantized ticks.

## 4. `CostPyramid` — the SoA store (3a)

One instance per dimension. Per level: an open-addressed `long`→row map (copy the idiom from
`NavGridView.lookupChunk` / `BlockPathfinder.Nodes` — murmur3 finalizer, power-of-two, `-1` empty, linear
probe, grow at 3/4). Parallel arrays grown on fill:
```java
final class Level {
    long[] mapKey; int[] mapRow; int mapMask, mapSize, mapGrowAt;   // key→row
    int[] rx, ry, rz;        // region coords per row (so planning never unpacks the key)
    byte[] face;             // 6 nibbles per row, FLATTENED: face[row*6 + f] = bucket 0..15
    int count;
}
Level[] levels = new Level[RegionAddress.MAX_LEVEL + 1];
```
API:
```java
int    rowFor(int level, int rx, int ry, int rz);      // intern: create (faces=BUCKET_INF) if absent
int    rowIfPresent(int level, int rx, int ry, int rz); // -1 if absent (no create)
int    faceBucket(int level, int row, int face);        // 0..15
void   setFaceBucket(int level, int row, int face, int bucket);
float  faceCost(int level, int row, int face);          // dequantized convenience
boolean isBuilt(int level, int row);                    // a leaf/node whose faces were actually computed
                                                        // (vs an interned-but-default placeholder) — track
                                                        // with a parallel boolean[]/bitset `built`.
```
New rows initialize all 6 faces to `BUCKET_INF` and `built=false`. `favour-cpu-over-ram`: do NOT bit-pack
the 6 nibbles into 3 bytes in RAM — one `byte` per face (6 B/node) is simpler and L1-fine; the nibble
packing is only the on-disk form (3e).

## 5. `LeafCostComputer` — leaf face→center cost (3b)

Entry: `void computeLeaf(ServerLevel level, int rx, int rz, int ry, CostPyramid pyramid)` — fills the
level-0 node's 6 face buckets + `built=true`. A leaf == one 16³ `NavSection`. Algorithm:

```
section = NavStore.get(level, NavStore.key(rx, rz)) [the column], pick sections[ry']  // ry' = (leafWorldY - minY)>>4
if section not built → return (leave node unbuilt; planner uses default §6)
origin world corner = (rx<<4, minY + (ry<<4)... )   // careful: ry here is the LEVEL-0 vertical region index

// 1) classify the leaf's occupancy from the resident grid (no live reads)
for each local cell (lx,ly,lz) in 16³: standable[lx][ly][lz] = NavBlock.isStandable(descriptor)
                                       passable[...]  = NavBlock.isPassable(descriptor)
passFrac = passable count / 4096

// 2) uniform fast-paths (no mini-pathfind)
if no standable cell:
    if passFrac high (≈ all air)  → all 6 faces = quantize(AIR_TRANSIT_TICKS = LEAF_SIZE)     // ~16, cheap fall/step-through
    else (≈ all solid)            → all 6 faces = quantize(SOLID_MINE_TICKS = LEAF_SIZE * MINE_PER_BLOCK) // high, finite
    built=true; return

// 3) representative floor cells (FLOOR cell = the block you stand ON; stand pos is floorCell.above())
centerRep = standable floor cell minimizing Manhattan dist to local (8, *, 8) [pick lowest |ly-8| on ties]
for each face f: faceRep[f] = standable floor cell in the 2-cell band nearest face f, min dist to that
                 face's center point; null if none on that face
if centerRep == null → fall to the solidity default (step 2's mixed form): faces = quantize(lerp by passFrac)

// 4) bounded mini-pathfind per face over a ONE-SECTION view (walls itself in → naturally bounded)
boundedGrid = NavGridView.overSections(minY, { NavStore.key(rx,rz) : column-with-only-this-section })
              // reuse the REAL section object; build a 1-column map whose other vertical slots are null
//   silence the block tier's per-search logging for the (many) leaf searches:
saveDbg = BlockPathfinder.DEBUG; saveTim = BlockPathfinder.LOG_TIMING;
BlockPathfinder.DEBUG = false; BlockPathfinder.LOG_TIMING = false;
try {
  for each face f with faceRep != null:
     plan = BlockPathfinder.findPath(boundedGrid, faceRep[f], centerRep, BotCaps.BREAK_PLACE);
     ticks = (plan != null) ? plan.cost() : SOLID_MINE_TICKS * (1 - passFrac) + LEAF_SIZE;  // fallback: mine-ish
     face[f] = quantize(ticks);
  for each face f with faceRep == null:   // no standable cell on that face → enter by mining/falling
     face[f] = quantize(SOLID_MINE_TICKS * (1 - passFrac) + LEAF_SIZE);
} finally { BlockPathfinder.DEBUG = saveDbg; BlockPathfinder.LOG_TIMING = saveTim; }
built = true;
```
Constants: `MINE_PER_BLOCK = 3.0f` (tick stand-in; matches the block tier's break deterrent scale),
`AIR_TRANSIT_TICKS = LEAF_SIZE`. **Symmetry note:** face cost is the cost *from* that face *to* center;
the boundary between two leaves is the implicit sum of the two facing halves (PRD §6.5) — we store the
half, never an edge. **Never emit `BUCKET_INF` from a leaf** (everything is mineable); INF is reserved for
void / out-of-world nodes the planner shouldn't enter.

The one-section bounded view keeps each search ≪ `MAX_EXPANSIONS` (4096 cells), so no change to
`BlockPathfinder` is needed. Building all 6 faces ≈ ≤6 small searches per leaf.

## 6. Defaults for missing/unloaded nodes (3d)

A planner read of a node that is interned-but-`!built` (no nav data / unloaded) returns an **optimistic
admissible default** so the region A\* heuristic stays admissible and planning over unexplored terrain
works: `defaultFaceCost = dequantize(quantize(LEAF_SIZE))` per face at level 0 (≈ free walk across), scaled
by the node's side at higher levels (`AIR_TRANSIT_TICKS * (side/LEAF_SIZE)`). Optimism is required — an
over-estimate would make the heuristic inadmissible and could refuse a real route; the live nav grid
refines on approach. `RegionGrid.faceCost(addr, face)` is the single chokepoint that returns built cost if
present else this default.

## 7. `PyramidMerger` — square-pyramid roll-up (3c)

`void mergeUp(CostPyramid p, int leafLevel0Row...)` re-computes ancestors after a leaf changes; and a bulk
`mergeLevel(p, level)` builds level+1 from level. A parent's 6 face costs from its children
(8 below the octree→quadtree transition, 4 above):

**Chosen operator (approximate square-pyramid — implement THIS; the exact tiny-shortest-path version is a
documented future refinement):**
```
for each parent face F:
  childrenOnF = the children whose subcell touches face F (4 in octree, 2 in quadtree-horizontal faces;
                for the ±Y faces above the transition there are none → F = the children's own ±Y agg)
  // cheapest crossing of that face among the children that border it, plus half-traversal to parent center
  crossing = min over childrenOnF of dequantize(child.face[F])
  halfSpan = AIR_TRANSIT_TICKS * (parentSide / LEAF_SIZE) / 2     // center-ward traversal estimate
  parent.face[F] = quantize(crossing + halfSpan)
  if all childrenOnF are unbuilt → leave parent.face[F] at default (do not fabricate)
parent.built = any child built
```
Rationale: monotone (solid children → expensive parent, open children → cheap parent), cheap, admissible
enough for skeleton selection. The exact version (solve the ≤50-node shortest path among child
face-centers + center nodes, boundary-shared face-centers joined at 0 cost) is noted in the Javadoc as
`// TODO refine`. **Re-merge on leaf change is O(levels)** — walk parent→root calling the per-node combine.

## 8. `RegionPathfinder` + `RegionPathPlan` (3g)

`RegionPathPlan` = an **ordered sequence of level-0 region addresses** (the coarse skeleton), start→goal,
plus `boolean reachedGoalRegion`. Immutable. Shape:
```java
public final class RegionPathPlan {
    // parallel arrays of the skeleton's level-0 region coords, in travel order (index 0 = start region)
    public int size();
    public int rx(int i), ry(int i), rz(int i);
    public BlockPos centerOf(int i);          // world center of skeleton region i (RegionAddress.center, level 0)
    public boolean isEmpty();
}
```
`RegionPathfinder.plan(ServerLevel level, RegionGrid grid, BlockPos startFloor, BlockPos goalFloor)`:

- Convert start/goal to level-0 region coords. If same region → trivial 1-element plan.
- **A\* over the level-0 grid** (region cells, 6 neighbors via faces). Edge cost crossing from node N out
  its face F into neighbor M = `N.faceCost(F) + M.faceCost(opposite(F))` (the implicit boundary sum,
  PRD §6.5). Use the same allocation-light SoA A\* idiom as the block tier (open-addressed map + binary
  heap; you may reuse a simplified copy — this search is far smaller, so a clean re-implementation keyed by
  `packLevelKey` is fine). Heuristic = `RegionHeuristic` (admissible — §8.1). `MAX_REGION_EXPANSIONS = 20000`.
- **Scale guard / lazy refinement:** if the start→goal region Chebyshev distance exceeds
  `LEVEL0_DIRECT_CAP = 256` regions (~4096 blocks), do NOT plan a full level-0 skeleton. Instead plan at
  the **coarsest level L where the distance ≤ 64 cells**, get the coarse skeleton, then **refine only the
  leading segment to level 0** (re-plan level-0 A\* inside the first 2–3 coarse cells toward the goal).
  Expose the plan as level-0 regions for just the near segment; the driver re-invokes `plan` when it nears
  the segment end. (For the milestone — multi-thousand-block walk — level-0-direct is enough; the lazy
  path is for 1M-block reach. Implement level-0-direct first; gate the coarse path behind the cap.)
- Build the leaves it touches lazily: before reading a node's faces, `grid.ensureLeaf(rx,ry,rz)` (computes
  via `LeafCostComputer` if the chunk is loaded and the leaf unbuilt; else default §6).
- Return the skeleton. `null`/empty if start region has no built ground at all.

### 8.1 `RegionHeuristic` + `SimpleRegionHeuristic`
```java
@FunctionalInterface public interface RegionHeuristic { float estimate(int rx,int ry,int rz, int gx,int gy,int gz); }
```
`SimpleRegionHeuristic` = Euclidean distance between region centers (in regions) × `MIN_COST_PER_REGION`,
where `MIN_COST_PER_REGION = CostCodec.dequantize(0)` (the cheapest possible crossing) so it never
over-estimates → **admissible** (PRD §7.4: the hierarchy lets us stay admissible). Returns 0 at the goal.

## 9. `PathPlan` — sliding-window driver (3h) + the wiggle rule

`PathPlan` unifies region skeleton + per-window block plans. Constructed per goal; ticked by the follower.
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
Internals & flow:
- On construct: `skeleton = RegionPathfinder.plan(...)`. `windowStart = 0`.
- `WINDOW = 3` regions. The window covers `skeleton[windowStart .. min(windowStart+WINDOW-1, last)]`.
- **Block target** = if the goal's region index ≤ window's last index (goal in window) → the real
  `goalFloor`; else → `skeleton.centerOf(window-last)` projected to a standable floor cell (search the
  region center column for ground; if none, use the raw center — block-A\* will get near it). Any
  traversable arrival is fine (no entrances).
- `replanBlock()` = `BlockPathfinder.findPath(new NavGridView(level), botFloor, blockTarget, caps)` over
  the **full loaded grid** (NOT bounded — the window is bounded by the target being only ~3 regions away,
  so the search is short). Sets status FOUND/BLOCKED.

**The wiggle rule (the user's concern — only the FINAL committed crossing advances the window):**
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
transient wiggle: **the bot has committed to skeleton[j] iff none of the block path's REMAINING waypoints
(from the bot's current waypoint to the window target) lie in any skeleton region with index < j.** I.e.
the path never goes back. Implement by scanning the active `BlockPathPlan`'s remaining waypoints, mapping
each to its level-0 region, and checking none maps to `skeleton[committedIndex .. j-1]`. (Cheap: ≤ window
of waypoints; the window is ~48 blocks.) This makes A→B→A→B→A→B→C advance the window to B exactly once —
on the last B-entry after which the remaining path stays in B/C — and to C when the path stays in C.
Fallback if waypoint scan is inconclusive (e.g. path null): advance only on `botFloor` actually being
inside `skeleton[j]` for `COMMIT_TICKS = 3` consecutive ticks (debounce).

- Replan also when `currentBlockPlan == null` (BLOCKED — terrain changed) or the bot exhausts the current
  block plan before reaching the window target (advance via the same commit logic).
- `isComplete()` when the bot's floor is within block-A\*'s goal tolerance of `goalFloor`.

## 10. `AllyBotEntity` wiring (replace the one-tier call)

Today `replan(goalFloor)` calls `BlockPathfinder.findPath(grid, startFloor, goalFloor, CAPS)` directly.
Replace with: hold a `PathPlan pathPlan` and a `RegionGrid` (one per level, cached). `replan(goalFloor)`:
```
RegionGrid rg = RegionGrid.of(level);                 // cached per ServerLevel
this.pathPlan = new PathPlan(level, rg, startFloor, goalFloor, CAPS);
this.path = pathPlan.currentBlockPlan();              // keep the existing follower working off `path`
```
Each tick in the follower: after moving, call `pathPlan.onBotMoved(this.blockPosition().below())`, then
`this.path = pathPlan.currentBlockPlan()` (and reset `waypointIndex` to 0 when the block plan instance
changes — track by identity). The existing `steerAlongPath` / `applyEdits` machinery is **unchanged** —
it just now follows windowed block plans fed by the region tier. Keep `CAPS = BotCaps.BREAK_PLACE`.
Preserve the existing FAIL-visible behavior: when `currentBlockPlan()` is null, log and fall back to
straight-line steer exactly as today (so pathological failures stay visible — the milestone wants that).

## 11. Persistence (3e) — `SavedData`, deferred body

Per-dimension `net.minecraft.world.level.saveddata.SavedData` named e.g. `"orebit_hpa"`, stored via
`ServerLevel.getDataStorage().computeIfAbsent(...)`. Serialize the `CostPyramid`: per level, the sparse
list of `(rx,ry,rz, 6 nibbles packed into 3 bytes, built bit)`. On-disk = palette-free (the nibbles ARE
the compressed form). Load on level load → seed the pyramid; `built` leaves load as built. Recompute the
nav grid (never persisted). Target ~2% of save (PRD §6.6) — measure with the disk-budget check (PRD §11)
once both this and the resource octree exist. **NOTE version coupling:** `SavedData`'s save signature
drifts across MC versions (`save(CompoundTag)` ⇄ `save(CompoundTag, HolderLookup.Provider)`); if it
diverges across the 1.17→26.x range, put the thin `SavedData` subclass behind the `platform/` seam or an
overlay, keeping the codec logic in core. Implement AFTER the milestone; stub `HpaPersistence` with the
codec methods + a `// TODO wire SavedData` until then.

## 12. Incremental maintenance (3f)

`HpaMaintenance.register()` adds a `BlockChangeEvents.Listener` (thread-safe — listeners can fire off the
tick thread during worldgen; mirror `NavGridUpdater`'s server-only guard). On a change at `pos`: mark the
containing level-0 leaf **dirty** (add its address to a per-level `LongSet`/concurrent set). A debounced
pass on world-tick-end (reuse the `onWorldTickEnd` cadence, ≤N leaves/tick) recomputes each dirty leaf's 6
faces (`LeafCostComputer`) and `PyramidMerger.mergeUp` its ancestors. Debounce so a bulk edit (TNT, fill)
re-merges each touched leaf once per batch, not per block. Register in `OrebitCommon.init` next to
`NavGridUpdater.register()`.

## 13. Milestone test / benchmark (PRD §10 — the "earns its keep" proof)

`HpaMilestoneTest` (mc-1.21 era, headless). Build synthetic in-memory terrain (reuse
`PathfinderBenchmark.buildFlatWorld` patterns) for two scenarios that flat block-A\* FAILs or floods on:
1. **Long flat walk** (e.g. 3000+ blocks straight) — flat block-A\* hits the 10k cap; show the region tier
   produces a skeleton and the windowed block-A\* solves each ~48-block window in ≪ MAX_EXPANSIONS.
2. **30-up open-air pillar** — the known flood case.
Report: region-tier node count + per-window block-A\* node counts vs. the flat search's 10k cap, and that
the goal is now reached. Keep `BlockPathfinder.LOG_TIMING` on for the windows so the counts print. This is
the clean before/after with partial-path still absent (nothing masks the signal).

## 14. House-style constraints (do NOT violate — these are why the block tier is fast)

- **No heap allocation on hot paths.** The region A\* and the per-window block-A\* run during a replan;
  reuse SoA arrays / open-addressed maps (copy the `NavGridView`/`Nodes` idioms), no `Map<Long,X>` boxing,
  no `new` in loops. The pyramid's per-level arrays grow-and-reuse.
- **Favour CPU over RAM** in memory (6 B/node faces, not bit-packed); bit-pack only on disk.
- **Statically-sized / primitive structures**, murmur3-finalized open addressing, `-1` empty markers —
  match the existing files verbatim in idiom.
- **Platform seam:** any version-divergent MC call (`LevelBounds`, `ChunkCoords`, `SavedData`,
  `getDataStorage`) goes through `platform/` or an overlay, never inlined into core hot loops.
- **Strategy over conditionals:** `RegionHeuristic` is an interface; new heuristics register, no enum
  switch.
- Keep `final` off classes that the design-principles file says to (match surrounding code; the existing
  pathfinder classes are `final` static-utility — mirror that for the static-only helpers).
```

## 15. Post-build refinements (design review — corridor bound + eager build)

Two gaps surfaced in review after the initial build; both are now implemented.

### 15a. Corridor bound — the spatial confinement that actually fixes the pillar (extends §9)

The sliding window alone does **not** fix the open-air pillar. `replanBlock` runs block-A\* toward a target
only ~3 regions away, but the pillar is a *heuristic* problem (h under-estimates the forced `PLACE` cost of
going up), and a nearer target doesn't fix an under-estimate — the search still floods horizontally over the
**unbounded** grid to the 10k cap, exactly as a flat search does. (For a ≤window-height pillar the goal is
even *inside* the window, so the windowed search degenerates to the flat one.) The fix is a **spatial
corridor bound** (a standard hierarchical-pathfinding technique):

- **`RegionBound`** (`pathfinding/blockpathfinder/`) — a pure world-space AABB, `allows(x,y,z)` = six int
  compares. No `hpa` dependency (keeps the block tier independent of the region tier).
- **`BlockPathfinder.findPath(grid, start, goal, caps, RegionBound bound)`** — new overload; the 3/4-arg
  forms delegate with `bound = null` (historical unbounded behaviour preserved). The bound is enforced at
  the **single choke point** `Relaxer.accept`: `if (bound != null && !bound.allows(nx,ny,nz)) return;` — one
  branch per discovered candidate, no movement touched. The start cell is interned directly, so it is always
  admitted; the caller must keep the goal/target inside the box.
- **`PathPlan.corridorBound(target)`** — the window's world-space AABB enclosing its skeleton regions (+ the
  start/target cells), expanded by `CORRIDOR_MARGIN = 16` (one region) horizontally and `CORRIDOR_VMARGIN = 8`
  vertically. The one-region margin is the knob: it **admits a beneficial one-region dip** into a neighbour
  the coarse face-to-center cost couldn't see, while **forbidding 2+-region wandering** — so the pillar's
  horizontal flood is capped at the corridor and the search is forced to ascend. `replanBlock` passes the
  bound; on `null` it **widens once** (`bound.widened(...)`) before declaring BLOCKED, so a too-tight corridor
  (e.g. a skeleton that bent more than the margin) never permanently traps a solvable path.
- A single AABB per window (not the exact region union) is intentional: cheapest per-candidate test, at worst
  slightly permissive on a short bent skeleton (harmless). For the straight pillar it is exactly the 3×3
  region column.

### 15b. Eager on-load region build — fill the pyramid as terrain is generated (extends §12)

Lazy plan-driven leaf building (`RegionGrid.ensureLeaf`) leaves a gap: explore a large world, let the chunks
unload, then path — the nav grid for those chunks is gone (`ChunkNavLoader` drops it on unload), so leaves
can't be built on demand. Fix: build region leaves **when a chunk's nav data is built**, while it is resident.

- **`HpaMaintenance.onChunkNavBuilt(level, chunkX, chunkZ)`** — for each resident 16³ section in the column,
  `LeafCostComputer.computeLeaf` + `PyramidMerger.mergeUp`. Ensures the dimension's `RegionGrid` exists, so
  the pyramid fills as the world is explored even before any plan. Toggle `HpaMaintenance.EAGER_BUILD`.
- **Called from `ChunkNavLoader`** right after `NavStore.put`, so it runs on the tick thread bounded by that
  loader's existing `MAX_BUILDS_PER_TICK` chunk budget. (Introduces a `pathing → hpa` import; acceptable — a
  notification from the nav pipeline to the region tier built on top of it. Could be decoupled via an event
  seam like `BlockChangeEvents` later.)
- **Why this closes the travel-then-path gap:** the `CostPyramid` lives per-`ServerLevel` and is **not**
  dropped on chunk unload (only on level unload), so leaves built while a chunk is resident **persist in RAM**
  after it unloads. Surviving a server *restart* still needs disk persistence (§11, deferred) — but within a
  session the in-RAM pyramid is the bot's memory of explored terrain.
- **Perf notes (deferred, pure optimization):** most sections are uniform air/solid → `LeafCostComputer`
  fast-paths (no mini-pathfind); only mixed surface/cave sections pay the six bounded searches. Two known
  optimizations: skip enqueuing uniform-air sections, and batch the per-column ancestor merge (today
  `mergeUp` re-derives shared ancestors once per leaf — redundant but correct). Fast travel can still outrun
  the per-tick build budget, leaving best-effort gaps the optimistic §6 default covers until revisited;
  guaranteed coverage of fast-traveled terrain ultimately wants disk persistence (§11).
```
