# Macro-Movements & Uniform-Region Collapse

> **Status: exploratory design notes** (Steve + Claude, 2026-06-25). NOT ratified, NOT yet built. This is
> the fix direction for the open-air-pillar flood and its kin. Steve is still researching; capture the
> reasoning here so the next session starts from it.

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

### 3a. Precompute — uniform regions per nav section

- For each 16³ nav section, decompose cells into large **uniform-navtype regions** in one pass (union-find,
  or simpler per-axis run-lengths). A min-size threshold (e.g. 6³, or just "run length > a few") filters out
  regions too small to be worth collapsing.
- **Bounded scan (Steve's point).** Cuboids can't be computed infinitely; bound the scan to a finite window —
  the **HPA\* corridor box** is the natural bound (it's the relevant search region anyway). A goal at a
  section *corner* therefore legitimately hits its cuboid edge at the corridor margin — the margin *is* the
  distance-to-edge there. A centred goal has more room.
- **Lighter sufficient form:** per-cell, per-direction **run-lengths** (JPS+ style — "uniform run of N in
  direction D") rather than full 3D cuboids. Promote to true cuboids only where a movement needs 3D extent.
  For the pillar, "air-run-height above each cell" is all you need.
- **Mutable grid:** this is a per-section *derived* layer, rebuilt with the nav section. `patchCell` already
  rebuilds a section cheaply on block change, so the run-lengths/cuboids ride along.

### 3b. Macro-operations — the collapse

- A `Movement` emits **one** successor that jumps across the uniform region, instead of one per block. It
  stays **exact**: macro cost = `N × per-step`, and it carries `N` folded `StepEdits` (the N placements/breaks).
- **Per-movement, and general (Steve's observation):** macro-Pillar (jump up an air column), macro-Traverse/
  bridge (jump across flat ground), macro-MineDown (down through uniform stone), … **Any movement with a
  uniform-region symmetry is collapsible, and each defeats its own pathology** — the vertical pillar flood and
  a horizontal bridge flood are the same problem on different axes.
- **Jump bound** = a function of (cuboid geometry, distance to the relevant edge, **goal direction**, move
  cost). In a large uniform region with the goal in-line → jump to the goal / far edge. Near an edge → stop
  where exiting sideways could become optimal. Steve's `min(height, dist_to_edge / move_cost)` is the right
  *shape*; it still needs the goal-direction term (a straight-up goal in a wide region has no useful side
  branch → jump full height regardless of horizontal room). **Exact formula TBD.**
- **Round DOWN, or emit a node at every potential branch height** (JPS-style), to keep optimality —
  over-jumping can skip the height where the optimal path *turns*. Rounding *up* (fewer nodes, slightly
  suboptimal) is an acceptable speed trade for a follow-bot; make it a conscious knob.
- **Do NOT precompute perimeter connectivity** (the O(n²)/O(n³) trap Steve flagged). Jump *to* the perimeter
  and let normal per-cell expansion read "wall vs opening" lazily, O(1) when you arrive. Precompute stays O(n).
- **Section scope:** section-confined regions (jump ≤16) are a fine first cut and already collapse the
  vertical. **Cross-section** regions (capped at corridor width) are a later refinement that helps
  corner/boundary goals.
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

Steve's hesitation is right: the heuristic is finicky, powerful, and runs per-node (ns/node), so **no blanket
vertical premium** (that was tried and removed in session 23 — it hurt terrain stair-climbing). But a
**cheap, bounded** correction near the goal is worth it, and it's the analytic form of the deferred
bidirectional/perimeter probe:

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

## 5. Open questions / TBD

- Exact macro jump-bound formula (cuboid geometry + goal direction + cost; round up vs down).
- Run-lengths vs full cuboids (start with run-lengths; cuboids only where 3D extent is needed).
- Cross-section regions for corner/boundary goals (scope + corridor-width cap).
- Heuristic-correction details (min-over-faces; the per-axis forced-extent formula; staying admissible).
- Interaction with the `PathEdits` diff (a macro-op crossing the path's *own* placed/broken blocks).
- Where the precompute lives and its lifecycle (per-section, rebuilt with the nav grid).

## 6. Relationship to the rest of the stack

- **HPA\*:** cuboid macro-ops are **intra-section** (16³); the region tier is **inter-section**.
  Complementary — macro-ops make the *windowed* block search fast over uniform terrain, which is exactly what
  the sliding window needs. This slots BELOW the region tier and doesn't disturb it.
- **Partial-path:** gated off. This approach **solves** the pillar at the search level (exact), so partial-
  path stays a separate, later arc rather than the pillar's fix.
- **The block tier's allocation-free hot path:** macro-ops *reduce* node count, so they help the per-search
  budget directly; the per-node machinery is unchanged (a macro is still one `accept`).
