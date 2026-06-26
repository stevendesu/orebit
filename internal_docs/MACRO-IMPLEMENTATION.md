# Macro-Movements & Cuboid Collapse — implementation design (the coherence lock)

> Implementation-level companion to **`internal_docs/MACRO-MOVEMENTS.md`** (the ratified *what/why*) and
> **PRD §7.1** (block tier). MACRO-MOVEMENTS is intent; **this is the exact how** — packages, class names,
> constants, signatures, algorithms (pseudocode for the subtle bits), and the wiring. **Every build agent
> reads this and follows it verbatim** so parallel work stays coherent. Where this note and MACRO-MOVEMENTS
> agree, MACRO-MOVEMENTS wins on intent; this note wins on signatures/constants. Author on `core`, merge to
> eras. Agents CANNOT compile (`core` isn't buildable) — they author against these signatures; the
> integrator merges, compiles on the mc-1.21 worktree, fixes, and verifies.

---

## 0. TWO NON-NEGOTIABLES — read these before writing a single line

These two points were litigated for *hours* across prior sessions. Every time someone "simplified" them the
design broke. **They are settled. Do not re-derive them, do not optimize them away, do not get clever.** If
you think you have a cleverer idea that shrinks the search or the compute further — **it is almost certainly
one of the wrong turns we already took and rejected.** Implement exactly what is written.

### NON-NEGOTIABLE 1 — You MUST compute the full cuboid. A 1-D walk is WRONG.

The tempting shortcut: "to collapse a pillar, just walk up the air column counting cells (a 1-D line) and
jump that far." **This is incorrect and will ship overshoot bugs.** Worked counter-example (the canonical
one we keep returning to):

- A `1×30×1` air column. Beside it, at `Y+3`, the bottom of a floating staircase that leads straight to the
  goal — stepping off onto it (no more block-placing) is the **optimal** path.
- A 1-D walk sees "air all the way up = 30" and jumps 30 → it **sails past the staircase, overshoots, and
  misses the optimal exit entirely.**

The fix is not a patched 1-D walk. The reason is structural: **a jump is only safe if the orthogonal
cross-section is uniform for the WHOLE jump.** You cannot certify that from a 1-D line — the line knows
nothing about its sides. The moment you add "check the sides as I go so I don't skip an exit," **you are
computing the cuboid** — the maximal box whose cross-section is constant along travel. There is no cheaper
object that gives the guarantee. The two equivalent framings:

- **Tall-thin box** `1×30×1` (the 1-D walk's view) → travel extent 30 → overshoot.
- **Short-wide box** (wide in the orthogonal plane, only as tall as the cross-section stays uniform — here
  `0..2`, because the staircase breaks uniformity at `Y+3`) → travel extent 3 → you land exactly at the
  exit. **Optimal.**

Rule (a) from MACRO-MOVEMENTS §3a — *"among the maximal boxes containing the cell, pick the one with the
largest orthogonal cross-section (= shortest along travel)"* — is precisely the mechanism that selects the
short-wide box, so the box's own travel extent caps the jump at the exit. **The cuboid is load-bearing for
correctness AND optimality. It is not an optimization you may skip or approximate away.** Skipping
intermediate cells is sound *only because* the cuboid proves their cross-section is uniform (no exit hidden
in them). That proof is the whole point of building it.

**If your design ever computes a travel run without simultaneously establishing the orthogonal extent that
holds over that run, stop — you are building the 1-D walk we rejected.**

### NON-NEGOTIABLE 2 — The escape-hedge bound MUST divide by the movement's per-step cost.

The jump distance is bounded (among other things) by the **cost-normalized** distance to the nearest
orthogonal cuboid face:

```
escapeBound = ceil( nearestOrthogonalFaceDistance / moveCost )
```

The `/ moveCost` is **not optional** and is **not** "a tuning factor we can drop." It is the JPS uniform-cost
requirement recovered for our *non-uniform* costs. The reasoning, which we keep having to re-establish:

- After jumping, the cheapest way *out* of the uniform region toward something better lies in the
  **unscanned** terrain just past the cuboid's orthogonal face (everything *inside* the cuboid is the same
  substrate, so the better move isn't in here). We can't see that terrain, so we **assume the cheapest
  possible alternative move there: cost 1.**
- We must not spend more cost *jumping* than it would cost to simply *walk to that potential exit*. Walking
  to the orthogonal face is `nearestOrthogonalFaceDistance` blocks at assumed cost 1. Jumping `j` steps of
  the current movement costs `j × moveCost`. Bounding regret to ~one step means `j × moveCost ≤
  nearestOrthogonalFaceDistance`, i.e. `j ≤ faceDistance / moveCost`.
- **Concrete:** a pillar step costs `moveCost = 5` (1 for the upward move + 4 to place the block — use the
  movement's REAL per-step cost from the constants in §5, not a literal 5). If the nearest orthogonal air
  face is 10 blocks away, `escapeBound = ceil(10 / 5) = 2`. You may jump 2, not 10. Cheap moves (walk,
  `moveCost ≈ 1`) get large jumps; expensive moves (pillar/mine) get small ones. **That asymmetry is the
  feature.** Drop the division and an expensive movement will over-jump past a cheaper exit — the exact bug.

This is a bound on **sub-optimality, not validity** (an over-jump is still a *valid* path, just slightly
long), so rounding the escape term **UP** (`ceil`) is fine — at most ~1 block of avoidable detour. But the
**other** two bounds in §6 (the box's travel extent, and the goal projection) are HARD — never round past
them.

---

## 1. Package layout & file list

**New package — `com.orebit.mod.pathfinding.blockpathfinder.cuboid`** (sibling of `…blockpathfinder.movements`;
the cuboid machinery is a block-tier concept, so it lives under the block pathfinder, not under `worldmodel`):

| File | Kind | Owns |
|---|---|---|
| `Axes.java` | NEW, `final`, static-only | Axis/direction constants + tiny helpers (`AXIS_X/Y/Z`, step vectors, orthogonal-axis pairs). **No objects, no enums** (house style: ints on the hot path). |
| `Cuboid.java` | NEW, `final`, **reusable/mutable** | One maximal axis-aligned box + its navtype. Pooled out-param (no per-query alloc). Geometry queries: contains, travel extent toward a direction, nearest orthogonal face distance. |
| `CuboidExtractor.java` | NEW, `final`, static-only | The core (§4): extract the **directional maximal cuboid** containing a cell, over COMMITTED navtypes, corridor-bounded, honoring rule (a). The load-bearing algorithm. |
| `NavGridCuboidsView.java` | NEW, `final` | The per-search query seam (§5): wraps a `NavGridView` + the search's `PathEdits`, owns the per-search base-cuboid cache, applies the `PathEdits` shrink overlay. The cuboid analog of `NavGridView`. |
| `MacroJump.java` | NEW, `final`, static-only | The jump formula (§6): given a `Cuboid`, the movement direction, `moveCost`, and the goal, returns the jump length. **The single home of NON-NEGOTIABLE 2** — every macro calls it, so the bound is written once. |
| `GoalForcedCost.java` | NEW, `final`, static-only | The admissible goal-cuboid heuristic correction (§7): compute the forced (axis, extent, cost) at search start; per-node premium. |

**Edit existing real files (each edited by exactly ONE agent — never two in parallel):**

- `…blockpathfinder.RegionBound` — ADD six accessors (`minX()…maxZ()`). The extractor needs the corridor
  bounds to clamp its scan; today they're `private` with no getters. Pure additive, no behavior change.
- `…blockpathfinder.MovementContext` — ADD a `NavGridCuboidsView cuboids()` accessor + the field, set via a
  new ctor param (the search passes the view). Keep the existing `NavGridView grid` ctor for callers that
  don't use macros (pass `null` cuboids → movements fall back to micro, see §8).
- `…blockpathfinder.EditScratch` — make the `breaks`/`places` buffers **grow** (a macro folds up to N
  placements; today the buffers are fixed `long[6]`/`long[3]`). Verify `push(...)` already grows; if not,
  make it grow. No semantic change for the 1-edit micro case.
- `…blockpathfinder.movements.{Pillar, MineDown, Traverse}` — make each **macro-aware** (§8): emit ONE
  candidate at the jump distance instead of always 1. When `cuboids()==null` or the computed jump is 1, this
  is byte-for-byte the old micro behavior.
- `…blockpathfinder.BlockPathfinder` — (i) build a `NavGridCuboidsView` per search and pass it into
  `MovementContext`; (ii) compute the goal-forced premium once at search start and add it in `Relaxer.h`
  (§7); (iii) expand macro edges to N waypoints in `reconstruct` (§9); (iv) add the `MACRO_MOVES` flag (§8).
- `…blockpathfinder.movements.{Diagonal, Ascend}` — **fast-follow (Phase E2), same primitive.** Specced in
  §8.4; build AFTER the three axis-aligned macros are green. Their step vector is diagonal, so the
  orthogonal projection differs — the `Cuboid`/`MacroJump` API is designed to take a step vector so these
  need no new core, only their own emission. **Do NOT hardcode axis-aligned-only assumptions into the core.**

**Do NOT touch:** `Fall`, `Descend` (micro-only for v1 — Fall is gravity, not a uniform place/break run;
Descend is a 1-step stair). The block-tier hot-path machinery (`Nodes`, the heap, `EditPool`) is unchanged —
a macro is still exactly one `accept` (MACRO-MOVEMENTS §6).

**Test (mc-1.21 era only — `src/test/java`):** extend the existing pathfinder tests; add a `MacroPillarTest`
(headless, synthetic `NavGridView.overSections`) asserting the canonical pillar collapses (§10).

---

## 2. `Axes` — direction vocabulary

```java
public final class Axes {
    private Axes() {}
    public static final int AXIS_X = 0, AXIS_Y = 1, AXIS_Z = 2;
    // A movement's travel direction = (axis, sign). sign ∈ {-1,+1}.
    // Step vector for an axis-aligned direction:
    public static int stepX(int axis, int sign) { return axis == AXIS_X ? sign : 0; }
    public static int stepY(int axis, int sign) { return axis == AXIS_Y ? sign : 0; }
    public static int stepZ(int axis, int sign) { return axis == AXIS_Z ? sign : 0; }
    // The two axes orthogonal to `axis` (for cross-section measurement):
    public static int orthA(int axis) { return axis == AXIS_X ? AXIS_Y : AXIS_X; }
    public static int orthB(int axis) { return axis == AXIS_Z ? AXIS_Y : AXIS_Z; }
}
```

Diagonal/Ascend (Phase E2) carry a full integer step vector `(dx,dy,dz)` rather than an `(axis,sign)`; the
`Cuboid`/`MacroJump` API takes the explicit vector form so both worlds share one code path (§8.4).

---

## 3. `Cuboid` — the reusable box

A **mutable, pooled** value object (one per cache slot; never allocated per query). Holds the box and the
navtype it's uniform in. Coordinates are absolute world block coords (the box is small — corridor-bounded —
but store ints, no packing games; clarity over a few bytes, and it's cache-resident).

```java
public final class Cuboid {
    int minX, minY, minZ, maxX, maxY, maxZ;   // inclusive
    int navtype;                              // the uniform navtype id (TraversalGrid navtype, 10-bit); -1 = invalid/empty
    boolean valid;                            // false = "no cuboid here" (unbuilt/out-of-corridor start cell)

    void set(int minX,int minY,int minZ,int maxX,int maxY,int maxZ,int navtype) { … this.valid=true; }
    void invalidate() { valid = false; }

    boolean contains(int x,int y,int z) { … }

    /** Distance (in cells) from `(x,y,z)` to the box's far face in direction (axis,sign), inclusive of the
     *  current cell as 0. This is the HARD travel-extent bound (you cannot jump past the box). */
    int extentToward(int x,int y,int z, int axis,int sign) {
        // e.g. axis=Y, sign=+1: return maxY - y;   axis=X, sign=-1: return x - minX;  etc.
    }

    /** Min distance (in cells) from `(x,y,z)` to the NEAREST face orthogonal to `axis` — i.e. how far the
     *  uniform cross-section clears sideways. Constant along the travel axis BY CONSTRUCTION (that's why we
     *  built a cuboid). Feeds NON-NEGOTIABLE 2's escape-hedge. */
    int nearestOrthogonalFace(int x,int y,int z, int axis) {
        int a = Axes.orthA(axis), b = Axes.orthB(axis);
        // min over the four orthogonal faces of (coord - minCoord, maxCoord - coord) on axes a and b.
    }
}
```

**Why ints, not a packed long:** the box is read several times per jump (extent + orthogonal face + the
PathEdits overlay scan); branch-free int field reads JIT-inline. Packing would buy nothing (it lives in a
small per-search cache, not persisted) and cost shifts on the hot path. (Consistent with FAVOR-CPU-OVER-RAM.)

---

## 4. `CuboidExtractor` — the directional maximal cuboid (THE CORE)

> This is the one genuinely hard algorithm and the one NON-NEGOTIABLE 1 is about. Implement it carefully;
> the workflow adversarially verifies it (§10). Read NON-NEGOTIABLE 1 again before starting.

**Contract.**
```java
public final class CuboidExtractor {
    private CuboidExtractor() {}
    /**
     * Fill `out` with the maximal axis-aligned cuboid that:
     *   (1) CONTAINS cell (sx,sy,sz);
     *   (2) is UNIFORM — every cell shares the start cell's navtype id (TraversalGrid.navtypeOf);
     *   (3) lies entirely inside the corridor `bound`;
     *   (4) among all boxes satisfying (1)-(3), MAXIMIZES the orthogonal cross-section relative to
     *       `travelAxis` (equivalently: is shortest along `travelAxis`) — RULE (a), MACRO-MOVEMENTS §3a.
     * Reads COMMITTED navtypes only (via `grid.packedAt` → `TraversalGrid.navtypeOf`); the PathEdits
     * overlay is applied later by NavGridCuboidsView (§5), NOT here.
     * If the start cell is unbuilt (packedAt == UNBUILT) or outside `bound`, sets out.invalidate().
     */
    public static void extract(NavGridView grid, int sx,int sy,int sz, int travelAxis,
                               RegionBound bound, Cuboid out) { … }
}
```

**Navtype read (the uniformity key).** `int nav = TraversalGrid.navtypeOf(grid.packedAt(x,y,z));` Two cells
are "same substrate" iff equal `nav`. `grid.packedAt` returns `NavGridView.UNBUILT (-1)` for unloaded cells —
treat UNBUILT (and any cell outside `bound`) as a **different** navtype, i.e. a hard wall the box cannot
cross. (Air dedups to one navtype, same-hardness stone to one; mixed hardness correctly fragments — that's
desired, MACRO-MOVEMENTS §3a.)

**Algorithm (recommended; the objective in (4) is what makes it tractable).** Because rule (a) FIXES the
objective to "largest orthogonal cross-section for `travelAxis`," you do NOT need a general 3-D maximal-volume
box search. Do it in two stages:

1. **Maximal orthogonal slab at the start cell.** In the 2-D plane orthogonal to `travelAxis` (axes
   `orthA`,`orthB`), grow the largest rectangle of same-navtype cells that contains `(sx,sy,sz)` and stays in
   `bound`. Use the standard "largest rectangle containing a given point in a binary grid" expansion, but the
   objective is to **maximize the nearest-face clearance** (Chebyshev-style: maximize `min(distance to the 4
   sides)`), because that clearance is exactly what NON-NEGOTIABLE 2 divides by. (A wide-but-thin rectangle
   with a face 1 away is worse than a smaller square with all faces 3 away — the square permits a bigger
   escape-bounded jump.) Bound every grow by `bound`. Cost: orthogonal plane is corridor-capped (~`2·margin`
   per side), so tens–hundreds of reads.
2. **Extend along `travelAxis` while the whole slab stays uniform.** Step outward along `travelAxis` (both
   signs from the start layer); at each new layer, the slab cross-section must be entirely the same navtype
   and in-bound — stop the moment any cell breaks. The extent you reach is the box's travel span. (This is
   where the staircase at `Y+3` halts a tall box: the wide slab fails at `Y+3`, so `maxY = Y+2`.)

The result is the short-along-travel / wide-orthogonal box rule (a) prescribes. **Do not substitute a 1-D
column walk for stage 1+2 (NON-NEGOTIABLE 1).** Stage 1 IS the orthogonal cross-section; stage 2 without
stage 1 is the rejected 1-D walk.

**Conservative-only errors (MACRO-MOVEMENTS §3b).** Every approximation must SHRINK the box, never grow it.
If a read is ambiguous or a grow is uncertain, stop growing. An under-sized box → a shorter jump → plain A\*
fills the gap (always safe). An over-sized box → a jump through a non-uniform cell → an INVALID path (never
acceptable). When in doubt, shrink.

**Section vs cross-section scope.** v1 target is **corridor-bounded** (the box may cross section boundaries,
clipped to `bound` — MACRO-MOVEMENTS §3b "Section scope"). A section-confined first cut (clip also to the
start cell's 16³ section) is an acceptable intermediate if it simplifies bring-up — but the ratified target
is corridor-bounded, and `bound` already provides the finite cap, so prefer going straight to corridor-bounded.

---

## 5. `NavGridCuboidsView` — the per-search query seam (cache + PathEdits overlay)

Mirrors `NavGridView`: **one instance per search**, holds the read seam and a per-search cache. Decided
lifecycle (MACRO-MOVEMENTS §5 item 4): **per-search `PathEdits` overlay over a base-cuboid cache.**

```java
public final class NavGridCuboidsView {
    private final NavGridView grid;
    private final PathEdits pathEdits;     // the SAME instance MovementContext walks each expansion
    private final RegionBound bound;       // corridor (may be null = unbounded legacy; then no macros, see §8)

    // Per-search base-cuboid cache, keyed by (cell, travelAxis). Open-addressed, primitive,
    // no boxing (HOT-PATH-NO-ALLOC). Value slots are pooled Cuboid instances.
    //   key = mix(BlockPos.asLong(x,y,z), travelAxis)
    // On miss: CuboidExtractor.extract(grid, …) into a pooled Cuboid, store, return.
    // On hit: return the cached base Cuboid.

    public NavGridCuboidsView(NavGridView grid, PathEdits pathEdits, RegionBound bound) { … }

    /** The base cuboid for (cell, axis) over COMMITTED state (cached). Caller must NOT mutate it. */
    private Cuboid baseCuboid(int x,int y,int z, int travelAxis) { … }

    /**
     * The cuboid for (cell, axis) WITH the search's speculative edits applied: returns the base cuboid
     * shrunk so it excludes any cell the current path has PLACED/BROKEN inside it (an edit changes the
     * navtype, so the box is no longer uniform there). Writes the (possibly shrunk) result into `out`.
     * This is the on-query collapse — base stays cached & untouched.
     */
    public void cuboidAt(int x,int y,int z, int travelAxis, Cuboid out) {
        Cuboid base = baseCuboid(x,y,z,travelAxis);
        // copy base → out, then if !pathEdits.isEmpty() and pathEdits has any edit inside `out`,
        // trim the face nearest the offending edit past it (the "shortest axis" cheap proxy,
        // MACRO-MOVEMENTS §3b). Point-in-box test per current-path edit; shrink if any hit.
    }
}
```

**Why the overlay almost never fires (MACRO-MOVEMENTS §3b — keep, don't "optimize out" the check):** a
greedy near-optimal path doesn't re-enter its own edited cells, and a goal-ward jump is *ahead* of the edits
made *behind* it (the pillar's support blocks are below the bot; the jump is the air above — disjoint). It's
a cheap correctness guard (a handful of point-in-box tests), not a hot cost. **Keep it** — it's the
conservative-only guarantee for the speculative-edit case. Do not drop it because "it never fires."

**Cache scope.** Per-search (the view is per-search, like `NavGridView`). Cross-search base persistence +
`patchCell` invalidation is a DEFERRED optimization (MACRO-MOVEMENTS §5 "deferred") — the base cuboids
recompute cheaply, so v1 does not persist them.

---

## 6. `MacroJump` — the jump length (the single home of both non-negotiables' arithmetic)

Every macro movement calls this — so the bound is written ONCE and no movement can re-derive it wrong.

```java
public final class MacroJump {
    private MacroJump() {}
    /**
     * The number of steps to jump (≥1) for a movement traveling (axis,sign) from `(x,y,z)`, given its
     * already-resolved `cuboid` (from NavGridCuboidsView.cuboidAt), the per-step `moveCost`, and the goal.
     * jump = max(1, min(travelExtent, goalBound, escapeBound)) where:
     *   travelExtent : cuboid.extentToward(x,y,z,axis,sign)              — HARD (box edge / validity)
     *   goalBound    : forward distance to the goal's coord on `axis`,   — HARD (don't overshoot the goal);
     *                  = max(0, sign*(goalCoord - cellCoord));             if goal not ahead on this axis,
     *                  this is 0 and forces jump=1 (micro) on this axis — correct (don't pillar away from goal)
     *   escapeBound  : ceil(cuboid.nearestOrthogonalFace(x,y,z,axis) / moveCost)   — NON-NEGOTIABLE 2, soft (ceil OK)
     * Returns 1 when the cuboid is invalid or any hard bound is ≤1 → caller emits the plain micro step.
     */
    public static int steps(Cuboid cuboid, int x,int y,int z, int axis,int sign,
                            float moveCost, int goalX,int goalY,int goalZ) {
        if (cuboid == null || !cuboid.valid) return 1;
        int travelExtent = cuboid.extentToward(x,y,z,axis,sign);
        int goalCoord = (axis==Axes.AXIS_X?goalX : axis==Axes.AXIS_Y?goalY : goalZ);
        int cellCoord = (axis==Axes.AXIS_X?x     : axis==Axes.AXIS_Y?y     : z);
        int goalBound = Math.max(0, sign*(goalCoord - cellCoord));
        int hard = Math.min(travelExtent, goalBound);
        if (hard <= 1) return 1;
        int orth = cuboid.nearestOrthogonalFace(x,y,z,axis);
        int escapeBound = Math.max(1, (int)Math.ceil(orth / moveCost));  // NON-NEGOTIABLE 2 — never drop /moveCost
        return Math.max(1, Math.min(hard, escapeBound));
    }
}
```

`moveCost` is the movement's **real per-step cost** — base move + folded edit (e.g. a pillar step = `Pillar.COST
(1.0) + MovementContext.PLACE_COST (3.0)` = 4.0; a mine-down step = `MineDown.COST + a break cost`). Pass the
true value, never a literal. Cheap moves (`moveCost≈1`) → large jumps; expensive → small. (NON-NEGOTIABLE 2.)

---

## 7. `GoalForcedCost` — the admissible goal-cuboid heuristic (MACRO-MOVEMENTS §4)

The principled form of the rejected "×4 vertical" hack. Provably admissible (adds only cost it can prove
forced; **min over the goal's faces**). Computed once at search start; folded per-node into `Relaxer.h`.

```java
public final class GoalForcedCost {
    private GoalForcedCost() {}
    // Result of the once-per-search probe. forcedExtent==0 ⇒ no correction (some face offers a cheap approach).
    public static final class Forced {
        public int axis; public int sign;   // the forced approach axis & direction toward the goal
        public int extent;                  // # of forced (expensive) blocks between goal and the open region
        public float perBlockPremium;       // (forcedCost - 1): the EXTRA cost/block octile under-counts
    }
    /**
     * Probe the goal's 6 faces via NavGridCuboidsView; for each face that is FORCED (a wide air cuboid below
     * ⇒ must build ⇒ cost = PLACE step; a solid cuboid to the side ⇒ must dig ⇒ cost = BREAK step), compute
     * (extent, perBlockPremium). Take the face MINIMIZING the premium (the cheapest entry — admissibility,
     * MACRO-MOVEMENTS §4). If ANY face is cheap (a standable adjacent cell ⇒ extent 0), result.extent = 0.
     * For an off-axis (up-and-over) goal this min-over-faces naturally credits only the cheaper single axis
     * (the CONSERVATIVE rule, MACRO-MOVEMENTS §4 / §5 item 3) — never sum two axes.
     */
    public static void probe(NavGridCuboidsView cuboids, int gx,int gy,int gz, BotCaps caps, Forced out) { … }

    /** Per-node premium added to h: the forced cost still BETWEEN the node and the goal on the forced axis,
     *  that octile under-counts. remainingForced = clamp(forced blocks still ahead of this node, 0, extent).
     *  premium = remainingForced * perBlockPremium. Admissible (a true lower bound on unavoidable cost). */
    public static float premium(Forced f, int x,int y,int z, int gx,int gy,int gz) {
        if (f.extent == 0) return 0f;
        // remaining forced extent between (x,y,z) and goal along f.axis, clamped to [0, f.extent]
        … 
        return remaining * f.perBlockPremium;
    }
}
```

**Wire into `Relaxer.h`** (BlockPathfinder): after the existing octile + 3-D tie-break, add
`+ GoalForcedCost.premium(forced, x,y,z, gx,gy,gz)`. `forced` is computed once in `findPath` before the loop
and held on the `Relaxer`. This is what kills the residual horizontal ground-flood that macro-Pillar alone
leaves (MACRO-MOVEMENTS §3c): a floor cell carries the FULL remaining build premium, so it stops looking as
cheap as climbing. **Macro-ops collapse the vertical axis; this collapses the orthogonal one. Partners.**

**Admissibility is mandatory.** If `perBlockPremium` ever over-states forced cost, the search can refuse the
optimal path (the old hack's failure). Keep the `(forcedCost − 1)` form and the min-over-faces. When unsure,
under-credit (smaller premium) — that only costs some extra exploration, never correctness.

---

## 8. Macro-aware movements

### 8.1 The shape (all three axis-aligned macros)

Each of `Pillar`, `MineDown`, `Traverse` keeps its existing validity checks, then — instead of emitting a
1-step candidate — computes a jump and emits ONE candidate at the jump distance, folding the per-step edits.

```
// inside candidates(ctx, x, y, z, out), for a movement traveling (axis, sign):
NavGridCuboidsView cuboids = ctx.cuboids();
if (cuboids == null || !MACRO enabled) { …emit the ORIGINAL single micro step…; return; }   // exact legacy path
Cuboid box = ctx.cuboidScratch();                       // a per-context reusable Cuboid (no alloc)
cuboids.cuboidAt(x, y, z, axis, box);
int J = MacroJump.steps(box, x,y,z, axis,sign, MOVE_COST, ctx.goalX(),ctx.goalY(),ctx.goalZ());
EditScratch e = ctx.edits().reset(!risksEdit(flagsAtStart));
for (int k = 1; k <= J; k++) {
    // the SAME per-step requirements the micro move checks, at step k:
    //   Pillar:   e.requireFloor(x, y+k, z);   (places support under each rise)   + headroom at top
    //   MineDown: e.requireAir(x, y-(k-1), z); (breaks the block at each level)
    //   Traverse: e.requireAir(body cells at the k-th cell);  e.requireFloor(under the k-th cell);
    if (!e.valid()) { J = k - 1; break; }       // conservative: clamp to the last valid step
}
if (J < 1) return;                               // nothing valid
int dx=Axes.stepX(axis,sign)*J, dy=Axes.stepY(axis,sign)*J, dz=Axes.stepZ(axis,sign)*J;
out.accept(x+dx, y+dy, z+dz, J*MOVE_COST + e.extraCost(), e);
```

`MOVE_COST` is the base per-step move cost (`Pillar.COST`, etc.); `e.extraCost()` adds the folded place/break
costs, so total = `J × (base + edit)` = the exact `N × per-step` of MACRO-MOVEMENTS §3b. The `goalX/Y/Z` and
`cuboidScratch()` are small additions to `MovementContext` (§1). **The cuboid bounds J so skipping the
intermediate cells is sound (NON-NEGOTIABLE 1).**

### 8.2 EditScratch must fold N edits

A jump of `J` places/breaks `J` blocks → `EditScratch.places/breaks` must hold up to `J`. Today they're
`long[6]`/`long[3]` fixed. Make `push(...)` grow (double on overflow). The `copyInto(StepEdits)` →
`StepEdits.load(...)` path already grows on the receiving side (code map confirms `load` grows). No change to
the 1-edit micro case.

### 8.3 The `MACRO_MOVES` flag

```java
/** Master switch for macro-movement collapse. OFF reproduces the pre-macro micro search exactly (every
 *  movement emits its single 1-step candidate). For A/B and for isolating regressions. */
public static boolean MACRO_MOVES = true;
```

When OFF (or `cuboids()==null`), every macro-aware movement takes the legacy single-step path — so the macro
work is a clean, revertible layer over the verified micro search.

### 8.4 Diagonal & Ascend (Phase E2 — same primitive, build after the three are green)

The user is right (and MACRO-MOVEMENTS §3b agrees): these collapse too — "only axis-aligned" was an
over-claim. They differ ONLY in: (1) the step vector is diagonal `(dx,dy,dz)` not `(axis,sign)`; (2) the
"orthogonal cross-section" is measured perpendicular to that diagonal line. The `Cuboid`/`MacroJump` API
already takes a direction; add a step-vector overload (`extentToward(x,y,z, dx,dy,dz)` /
`nearestOrthogonalFace(... perpendicular to the diagonal)`) rather than forking the core. **Build these only
after Pillar/MineDown/Traverse pass §10**, so the diagonal orthogonal-projection subtlety lands on a verified
base. Do NOT bake axis-aligned-only assumptions into `Cuboid`/`CuboidExtractor` that would block this.

---

## 9. `reconstruct` — expand a macro edge to N waypoints (the follower is unchanged)

The search collapses a jump to one node; the PLAN must still be N stand-positions so `steerAlongPath` /
`applyEdits` are untouched (MACRO-MOVEMENTS §3b "Execution is transparent").

In `BlockPathfinder.reconstruct`, when walking an edge parent `P` → node `Q` whose movement is a macro and
whose coordinate delta along the move axis is `J > 1`:

- Emit the `J` intermediate stand positions `P + 1·u, P + 2·u, …, P + J·u` (`u` = unit step vector), in
  order, each tagged with the base movement.
- The edge's folded `StepEdits` holds the `J` placements/breaks in step order. **Slice them per waypoint:**
  waypoint `k` carries the edit for step `k` (the place/break at that level). So the follower mines/places
  exactly as a micro path would, one block per step. (A single 1-place/1-break `StepEdits` per emitted
  waypoint — reuse the existing per-waypoint edit plumbing.)
- `J` is derived from the coords (`|Q−P|` along the axis) — no new node field needed.

Net: a macro edge becomes `J` ordinary plan entries. Everything downstream of the planner is identical to
today.

---

## 10. Verify (the milestone)

1. **Compile green** across the matrix: `chiseledCompileCommon --continue` (mc-1.21) and `chiseledCompile
   --continue` (26.x). (`:ver:compileJava` compiles STALE chiseledSrc — do not trust it, per CLAUDE.md.)
2. **`MacroPillarTest`** (headless, `NavGridView.overSections` synthetic grid): the canonical repro (start
   `(-48,-61,16)` → goal `(-48,-33,16)`, the 28-block open-air climb). Assert: a path is FOUND, and
   expansions drop by ~an order of magnitude vs `MACRO_MOVES=false` on the same grid. Assert the returned
   plan, when expanded, is a valid block-by-block pillar (the reconstruct slice is correct).
3. **In-game trace** (manual, the real confirmation): `/bot trace` then `python
   internal_docs/trace_analysis.py`. The off-column % (99.8% before) should crater; the vertical cone should
   be gone (macro-Pillar) and the ground flood thinned (the §7 premium). Re-run the canonical pillar +
   a dense cave.
4. **Conservative-error audit** (adversarial, §10 workflow stage): construct grids where a naive
   implementation would over-claim — the `1×30×1`-with-side-staircase grid (NON-NEGOTIABLE 1); a column with
   a single non-uniform cell mid-run (must clamp `J`); a goal one block past a navtype change (must not jump
   through it). Assert no jump ever crosses a non-uniform or out-of-corridor cell.

---

## 11. Build order (for the workflow — dependency-ordered phases)

```
A  Foundation      Axes, Cuboid, RegionBound getters                         (no deps)
B  Extraction      CuboidExtractor  (THE core; §4; adversarially verified)   (A)
C  Query seam      NavGridCuboidsView (cache + PathEdits overlay)            (A,B)
D  Jump            MacroJump  +  EditScratch growth                          (A,C)
E1 Movements       macro-aware Pillar, MineDown, Traverse  + MovementContext (D)
                   cuboids()/goal/scratch + MACRO_MOVES flag
G  Heuristic       GoalForcedCost  +  Relaxer.h hook                         (C)   ‖ with E1
F  Reconstruct     macro-edge → N waypoints in BlockPathfinder.reconstruct   (E1)
H  Wiring          BlockPathfinder builds the view, passes it, sets goal     (E1,F,G)
I  Verify          MacroPillarTest + conservative-error grids                (H)
E2 Diagonal/Ascend macro (same primitive; §8.4)                             (after I green)
```

B is the critical path and the riskiest — it gets the most verification. E1 and G are independent given C
and can run in parallel. **No agent invents an algorithm not specced here; if the spec is ambiguous, the
integrator decides — do not "improve" the cuboid or the jump bound (NON-NEGOTIABLES 1 & 2).**
