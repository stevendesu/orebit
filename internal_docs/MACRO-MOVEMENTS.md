# Macro-Movements & Uniform-Region Collapse

> **Status: RATIFIED** (Steve + Claude, 2026-06-25). The four §5 "still open" items are now decided (see
> §5); this is the fix direction for the open-air-pillar flood and its kin, ready to build. The prescriptive
> signatures/constants/algorithms the implementation authors against live in `internal_docs/MACRO-IMPLEMENTATION.md`
> (mirrors the HPA build's locked note); this file is the *why*, that one is the *what/how*.

## 1. The pathology (measured)

A goal reachable only by **building straight up** (the open-air pillar: owner on a floating block, bot must
pillar to it) makes the block-A\* exhaust its 10k-expansion budget and FAIL. Diagnosed with the `/bot trace`
+ `internal_docs/trace_analysis.py` tooling (see CLAUDE.md) on the canonical repro (start `(-48,-61,16)` →
goal `(-48,-33,16)`, a 28-block climb):

- **99.8% of expanded nodes are OFF the goal column** (23 of 10,001 on-column). The search builds a **cone of
  partial pillars** — pillaring a little at *every* floor cell — plus digs *downward* (17% of nodes below
  the start). Side-view of the explored set is a literal pyramid, widest at the floor, topping out 9 blocks
  short of the goal, which floats above it.
- The `f`-frontier creeps 56 → 94 over the whole budget, never reaching the true pillar cost (~`28×4=112`).

**Two root causes, and they compound:**
1. **The heuristic is blind to build cost.** Octile sees "28 blocks up ≈ 56"; the real cost is `28 × (1 +
   PLACE_COST 3) = 112`, because every pillar step places a block. So as the pillar climbs, its `f` *rises*
   (`g += 4`, `h -= 2` → `f += 2`/step), and A\* keeps falling back to cheaper-`f` nodes.
2. **A\* explores a symmetry.** "Pillar then traverse" and "traverse then pillar" reach the same cell for the
   same cost — at *every* `(x,z)` and *every* height. A\* has no notion of equivalent paths, so it expands
   all of them. This is the cone.

## 2. The literature (and why it doesn't port directly)

- **JPS (Jump Point Search):** breaks grid symmetry by jumping over uniform interiors to *jump points* (cells
  with forced neighbours). Assumes **uniform cost + static grid**.
- **RSR (Rectangular Symmetry Reduction):** precompute empty rectangles, expand their *perimeters*, skip
  interiors.
- **Baritone:** macro-operations ("Traverse 5", "Pillar 10") collapse repeated identical moves.

We can't use JPS/RSR off the shelf — our move costs are **non-uniform** (place/break ≠ walk) and our grid
**mutates**. But the *idea* — collapse traversal of a homogeneous region into a single step — is the fix.

## 3. The approach: macro-movements over uniform cuboids

### 3a. Cuboids — maximal, not connected, and computed lazily

- **Maximal cuboids, NOT connected components, NOT run-lengths.** Union-find gives *connected sets* which can
  be concave (L-shapes); a straight jump can exit a concave region through the notch. Run-lengths give only a
  1-D cross, not the box the movement needs. The unit is the **maximal axis-aligned cuboid** (convex → any
  axis move between two interior cells stays interior). Finding it: the histogram→2D→3D reduction (largest
  rectangle in a histogram, swept over layers); at 16³ it's ~tens of thousands of ops, free.
- **Directional tie-break — among equal-volume maximal cuboids, pick the one SHORTEST along the goal-travel
  axis (DECIDED, was §5 open).** The maximal cuboid containing a cell isn't unique (a 4×1×1 and a 1×4×1 both
  fit at a corner). The jump along the travel axis is capped by `dist_to_edge` to the *nearest* face — and a
  near *orthogonal* (side) face cuts the jump short *before* it reaches the cuboid's far end ("stopping
  partway through"). So choose the box with the **largest orthogonal cross-section** (= shortest extent along
  travel): that pushes the side faces away, so the limiting edge becomes the far travel face / goal
  projection and you jump the *whole* run. Equivalently: minimize the travel-axis extent among equal-volume
  candidates. Cheap to evaluate during the directional extraction (§ MACRO-IMPLEMENTATION).
- **Uniformity = identical navtype descriptor (Steve), NOT a per-movement predicate.** A single shared cuboid
  definition is reusable across all macros (so memoization actually pays off), and identical navtype also
  gives the **cost-uniformity JPS needs** (every collapsed step is the same cost). Per-movement predicates
  would either fragment differently per macro (no reuse) or lump different-cost cells (e.g. break cost varies
  with hardness → an invalid macro-MineDown). The dominant jump cases are naturally one navtype: **air**
  (pillar up, bridge across lava/voids — air/cave-air dedup to one navtype) and same-hardness **stone**
  (mine down). "Stone-like" of mixed hardness *correctly* fragments into per-type cuboids, because the
  per-block break cost genuinely differs there.
- **Don't decompose the whole section — march toward the goal (Steve).** Draw the line current→goal; at each
  cell compute its maximal cuboid; if a cell is already inside the previous cuboid, skip to where the line
  exits it (the jump). Work ∝ distinct cuboids the line crosses, not line length — sphere-tracing with boxes.
  Do it **per expanding node toward the goal**, so every column's pillar collapses (not just the start line),
  and **memoize** cell→cuboid per section so each region is computed once and reused across nodes/searches.
- **Bounded scan (Steve).** A cuboid can't be infinite; the **HPA\* corridor box** is the natural finite bound.
  A goal at a section *corner* legitimately hits its cuboid edge at the corridor margin — the margin *is* the
  distance-to-edge there; a centred goal has more room.
- **`NavGridCuboidsView` (Steve).** Mirror `NavGridView`: a per-search view that, given a cell, returns the
  cuboid it belongs to, factoring in (a) the memoized per-section base cuboids and (b) the current path's
  speculative `PathEdits`. Base cuboids are stable until a *committed* block change rebuilds the section (the
  `patchCell` cadence); the per-search `PathEdits` are applied as an on-query collapse (§3b).

### 3b. Macro-operations — the collapse

- A `Movement` emits **one** successor that jumps across the uniform region, instead of one per block. It
  stays **exact**: macro cost = `N × per-step`, and it carries `N` folded `StepEdits` (the N placements/breaks).
- **Per-movement, and general (Steve's observation):** macro-Pillar (jump up an air column), macro-Traverse/
  bridge (jump across flat ground), macro-MineDown (down through uniform stone), … **Any movement with a
  uniform-region symmetry is collapsible, and each defeats its own pathology** — the vertical pillar flood and
  a horizontal bridge flood are the same problem on different axes.
- **Jump bound = `min(goal_projection, dist_to_edge / move_cost)`** — two AND-ed bounds (Steve; Claude
  conceded after first arguing geometry alone):
  - **`goal_projection`** (jump to the goal's coordinate on the travel axis) bounds overshooting **the goal**.
  - **`dist_to_edge / move_cost`** is the **escape-hedge**: it bounds overshooting **a cheaper movement out
    of the cuboid**. The escape isn't *inside* the uniform region (everything there is the same substrate) —
    it's in the *unscanned* terrain just past the edge (and the scan is corridor-bounded, so the edge is
    near). You can't know that terrain without scanning it, so you assume the cheapest possible alternative
    (cost `1`) and don't jump so far past a potential escape that the regret exceeds ~one step. Normalizing
    distance by the current move's cost ("pillaring is expensive → smaller jumps; cheap moves → larger
    jumps") is JPS's uniform-cost requirement recovered by dividing the units out.
  - **Why the division and not "emit every movement's macro bounded by its own goal-projection":** that exact
    alternative needs per-movement optimal-turn analysis and breaks when a new movement (Parkour, …) is added.
    The division is **movement-agnostic** — it needs nothing about the alternatives but a conservative cost
    floor — so it stays correct-and-bounded as the movement set grows. That robustness is the whole point.
  - It's a bound on **sub-optimality, not validity**: over-jumping yields a *valid* path, just a slightly
    longer one — so **rounding UP is fine** (≤1-block overshoot, vs Baritone's whole-chunk doubling-back).
  - For the flat-world pillar the corner/corridor makes `dist_to_edge` small (≈9), so jumps are ~2–3 blocks
    and the cone collapses to ≈4,000 nodes (under budget); the §4 heuristic then removes the residual ground
    flood. (Optional later refinement: once cross-section cuboids reveal the terrain past the edge, swap the
    assumed `1` for that region's real cheapest move cost → bigger jumps.)
- **The cuboid is a PERF optimization, but err in ONE direction only (Steve, sharpened).** Under-approximating
  (smaller cuboid → shorter jump → fall back to plain A\*) is always safe. *Over*-claiming (a stale cuboid
  that calls a now-solid cell "air") emits a jump through a block = an **invalid path**. So every error must
  shrink, never grow: when in doubt, collapse or invalidate. With that, "it's just an optimization" holds.
- **Optimality vs validity:** the geometric jump is always *valid*; over-jumping past the goal-Y would be
  *suboptimal* (handled by bounding to the goal projection). Where you don't jump, plain A\* restores local
  optimality. So ship the crude version; the only thing relaxed is mild suboptimality across jumps — fine for
  a follow-bot.
- **Edit handling, and why it's almost never hit (Steve).** A speculative `PathEdit` *inside* a cuboid you're
  jumping through must shrink that cuboid to exclude the edited cell (cheapest: trim the face nearest the edit
  past it; "shortest axis" is a fine cheap proxy). But this **almost never fires**: a near-optimal (greedy)
  path is simple — it never routes A→…→C→…→C→…→B (the C→C subchain could be excised for a shorter route) — so
  the path doesn't re-enter its own edited cells, and a goal-ward jump is *ahead* of the edits the path made
  *behind* it. For the pillar specifically the edits are the support blocks *below* the bot and the jump is
  the air *above* — disjoint. So the collapse is a correctness guard that costs essentially nothing in
  practice; check the handful of current-path edits against the one box (point-in-box), shrink if any hit.
- **Do NOT precompute perimeter connectivity** (the O(n²)/O(n³) trap Steve flagged). Jump *to* the perimeter
  and let normal per-cell expansion read "wall vs opening" lazily, O(1) when you arrive. Precompute stays O(n).
- **Section scope — cross-section, bounded by the corridor (DECIDED, was §5 open).** Cuboids extend *across*
  section boundaries into the neighbour, but **only as far as the HPA\* corridor box** (`RegionBound`) — never
  the whole section grid (you can't scan a flat-world into a 30M×30M air cuboid). The trade-off is explicit:
  farther extension → larger orthogonal `dist_to_edge` → bigger jumps → fewer nodes; the corridor is the
  bound *most tightly tied to the path itself* (it's exactly the terrain we're allowed to traverse), so it's
  the natural cap. A section-confined first cut (jump ≤16) already collapses the pure vertical; the
  corridor-bounded cross-section is what fixes corner/boundary goals (the corner pillar whose section edge
  *is* the corridor margin). Implementation may land section-confined first, then widen to corridor — but the
  ratified target is corridor-bounded.
- **Execution is transparent:** the macro collapses the *search* (one node); `reconstruct` expands that edge
  back into `N` waypoints + their `StepEdits`, so `steerAlongPath`/`applyEdits` never change.

### 3c. What collapses, and what does NOT (important)

Macro-Pillar collapses the **vertical** cone (28 heights → 1 per column). The **orthogonal horizontal ground
flood survives** — the ~1,156 floor cells are cheap-`g`, and the heuristic still rates them as good as
pillaring, so A\* still pops them first. Net ≈ **1,200 nodes → SUCCEEDS** (well under budget), but **not
optimal** (~30). So:

- For **"make the pillar reach the goal"** → macro-Pillar alone is enough.
- For **"A\* doesn't flood at all"** → you also need to stop the search wanting the floor, which is the
  heuristic correction (§4) or a macro-Traverse that collapses the floor the same way. **Macro-ops bound one
  axis; the heuristic bounds the orthogonal one. They are partners, not competitors.**

## 4. Bounded heuristic correction — the goal-cuboid perimeter probe

**This is the CORRECT version of the "multiply vertical cost by 4" hack** Claude kept reaching for (Steve's
framing). That blanket premium was *inadmissible* — it over-estimated terrain stairs, so it could refuse the
optimal route, which is why it was removed in session 23. This does the same thing — credit the forced build
cost — but **provably without over-estimating** (it adds only cost it can prove necessary, min over the goal's
faces), so it's general across all maps and explainable in math. The heuristic is finicky/powerful/per-node,
so still **no blanket multiplier**; this is a cheap bounded correction near the goal only:

- Look at the goal's **6 faces** and the cuboids they touch.
- A wide flat **air** cuboid *below* the goal ⇒ you MUST build to reach it from below ⇒ add a vertical
  premium. **Solid** cuboids on the goal's *sides* ⇒ you MUST dig to reach it sideways ⇒ add a horizontal
  premium.
- **Bounded per axis, not a blanket multiplier:** `forced_extent × forced_cost + (total − forced_extent) × 1`
  — "I know at least `forced_extent` of these blocks are expensive; I don't know about the rest." The premium
  applies only over the cuboid's extent, then reverts to the base cost.
- **Admissibility:** take the **MIN over the goal's faces** (the cheapest entry). If any face offers a cheap
  approach (an adjacent standable cell), no premium — over-estimating would be inadmissible and could refuse
  the optimal route.
- **Cost:** cheap. The goal cuboids are precomputed; the heuristic reads a per-axis `(forced_extent,
  forced_cost)` once per search and folds it in with a couple of adds — negligible ns/node, and only near the
  goal.

This subsumes the deferred "perimeter / backward-probe" heuristic (HANDOFF) — computed analytically from the
goal's cuboids instead of an actual backward search.

- **Off-axis (up-and-over) goal — credit only the cheaper single-axis premium (DECIDED, was §5 open).** When
  the goal is forced expensive on *more than one* axis (build up AND dig over, the diagonal mix), the true
  cost is a continuous staircase trade — every block walked before building is a block not pillared past, so
  the vertical climb is paid *as part of* the horizontal travel, not on top of it. Charging both full premiums
  double-counts that shared work → **over-estimate → inadmissible → can refuse the optimal staircase** (the
  exact failure of the old "×4" hack). The ratified rule is **conservative**: credit only the **minimum**
  single-axis premium (equivalently: take the min over faces as already specified, which on a multi-axis-forced
  goal naturally returns the cheaper axis). This **under**-credits the diagonal — accepting some residual flood
  on the pure up-and-over case — but it is provably admissible and **degenerates to plain A\*** where it adds
  nothing, so it breaks nothing. A tighter admissible lower bound on the staircase cost is a possible later
  refinement; v1 ships the conservative single-axis credit.

## 5. Decided (all of it — this design is ratified)

**Core (settled the first pass):** maximal cuboids (not connected components, not run-lengths); uniformity =
identical navtype descriptor; lazy line-march toward the goal + memoize per section; jump bound =
`min(goal_projection, dist_to_edge / move_cost)` — the `/move_cost` escape-hedge is **movement-agnostic
robustness** (future-proof for new movements), and over-jump is valid-but-suboptimal so rounding UP is fine;
err only conservative (shrink, never over-claim); `NavGridCuboidsView` as the query seam; the goal-cuboid
heuristic correction is admissible (min-over-faces) and IS the principled form of a vertical premium.

**The four formerly-open items — now decided (this pass):**
1. **Directional maximal-cuboid query** → among equal-volume maximal cuboids at a cell, pick the one
   **shortest along the goal-travel axis** (largest orthogonal cross-section → side faces pushed away → the
   jump is capped by the far/goal face, not a near side wall → jump the whole run). See §3a.
2. **Cross-section scope** → cuboids **extend across section boundaries, bounded by the HPA\* corridor box**
   (not the whole grid). Farther = bigger jumps, but the corridor is the bound tied to the path. May land
   section-confined first then widen, but corridor-bounded is the target. See §3b "Section scope".
3. **Off-axis (up-and-over) goal heuristic** → **conservative single-axis credit** (min over faces; credit
   only the cheaper forced axis, never sum both → admissible, degenerates to plain A\*; accepts some flood on
   the pure diagonal). A tighter staircase lower bound is a later refinement. See §4.
4. **Lifecycle** → **per-search `PathEdits` overlay over a per-section base cuboid cache.** The base cuboids
   are memoized per section and invalidated only on a *committed* block change (the `patchCell` cadence,
   matching `NavGridView`); a *search's* speculative edits are an on-query collapse layered on top
   (`NavGridCuboidsView`, mirroring how `MovementContext.descriptorAt` layers `PathEdits` over the grid), so
   backtracking to a different candidate path never rebuilds the base cache. See §3a/§3b.

**Genuinely deferred (not blocking v1, explicitly later):** the optional escape-hedge refinement (swap the
assumed cost-`1` floor for a scanned neighbour's real cheapest move once cross-section cuboids reveal it,
§3b); a tighter admissible staircase lower bound for the diagonal goal (§4); and whether the base-cuboid
cache is worth persisting (it is recomputed cheaply, so likely not — unlike the HPA\* graph).

## 6. Relationship to the rest of the stack

- **HPA\*:** cuboid macro-ops are **intra-section** (16³); the region tier is **inter-section**.
  Complementary — macro-ops make the *windowed* block search fast over uniform terrain, which is exactly what
  the sliding window needs. This slots BELOW the region tier and doesn't disturb it.
- **Partial-path:** gated off. This approach **solves** the pillar at the search level (exact), so partial-
  path stays a separate, later arc rather than the pillar's fix.
- **The block tier's allocation-free hot path:** macro-ops *reduce* node count, so they help the per-search
  budget directly; the per-node machinery is unchanged (a macro is still one `accept`).
