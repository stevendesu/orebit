# FINDINGS — Reverse-reachability harvest from `RegionPathfinder.costToGoalField`

Forensic, read-only. Source read on branch `mc-1.21` in worktree
`C:\Users\steve\Repos\personal\orebit-mc121-wt`.

> **Editor's note (2026-07-25):** §4's table below quotes `DESIGN-virtual-start-fragment.md §8`
> in its PRE-rewrite ("budget-unproven") form. §8 has since been rewritten (owner ruling): a
> no-progress flood IS a valid, persistable proof; the fixes are scoping (anti-flap + from-fragment
> keys), not persist-vs-not. Read this findings doc for the `costToGoalField` mechanism; read the
> current §8 + `ROADMAP-region-nextwork.md` for the taxonomy.

**Central question:** does `costToGoalField` define an explored RANGE within which
"a BUILT fragment that received NO cost label ⇒ infinite cost-to-goal ⇒ provably cannot
reach the goal" is a valid, harvestable negative-reachability proof?

**One-line answer:** No — not as-is. The field IS confined to a bounding box (a swept
range exists), but exploration inside it is *pure edge-expansion*, the field records no
"in-box AND built AND unreached" state, and the consumer deliberately floors unlabeled
slots to an optimistic lower bound (it never even represents "infinite"). Harvesting
negative reachability requires adding a considered/built-membership record; it cannot be
read out of the current field.

---

## 1. Verdict on (A) vs (B): EDGE-EXPANSION confined by a bounding box (a hybrid, closer to A)

The producing Dijkstra is `RegionPathfinder.costToGoalField` (`RegionPathfinder.java:999-1127`):

- **Data structures.** A dedicated per-thread search state `FIELD_SEARCH.get()` (`:1014-1015`,
  `:321`) — struct-of-arrays (`g[]`, `f[]`, `parent[]`, `frag[]`, `portalX/Y/Z[]`, `closed[]`)
  with an open-addressed key→row table + binary heap. Output is a dense `RegionCostField` (`:1062`).
- **Seeding (multi-source).** Break-capable bot: `grid.goalDigSeeds(...)` seeds every occupiable
  pocket the buried goal can be dug into, each at its dig cost (`:1025-1033`); else a single
  nearest-centroid goal fragment at `g=0` (`:1034-1039`). `seedField` (`:1188-1197`).
- **Relaxation loop.** `while (heapSize > 0) { pop; record; closed=true; expandNode(...) }`
  (`:1067-1115`). `expandNode` (`:1272`) emits the region-A* edge model: intra-region mine edges
  (`:1306`), six-face inter-region edges (`:1323`), dig-through edges (`:1334`), each ending in
  `relaxFrag` (`:1487`).
- **Termination — three ways, none a swept census:** heap exhaustion (`:1067`); a fat-skeleton
  early exit once the start settles and the goal→start Chebyshev-1 chain drains (`:1096-1114`,
  `markFatSkeleton:1149`) — a *prefix* of the full run (`:986-988`); an expansion backstop
  `MAX_REGION_EXPANSIONS = 20000` (`:67`, `:1085`).

**Why edge-expansion, not a swept census.** `expandNode` `ensureNode`s (→ `grid.ensureLeaf`,
`:2111-2117`) **only the six face-neighbours of a popped/settled node** (`:1338`, `:1371`).
Nothing enumerates the box. An in-box region not graph-adjacent to a settled region is never
`ensureNode`'d, never considered, never labeled — its `cost[]` slot stays `UNREACHED`
(`RegionCostField.java:148`). No "considered set minus reached set" is materialized.

**The box exists but only STOPS the flood.** `RegionBox bound` (`:1205-1226`), `contains` is the
per-relax admission gate — `relaxFrag` rejects any target outside the box before interning
(`:1494`: `if (bound != null && !bound.contains(mrx,mry,mrz)) return false;`).
`RegionBox.around(startRegion, goalRegion, pad=3)` (`:1221-1225`) is the swept range. The raw
ingredient for a swept-area harvest — a finite, reachability-independent considered volume — is
present. Missing: enumeration of, and built-membership over, the in-box fragments the flood
didn't reach.

---

## 2. Harvest-existing vs add-a-swept-range — you must ADD

Three independent blockers, each verified:

1. **The field never returns/records "infinite".** `RegionCostField.costAt` **never returns
   `UNREACHED`** — every unsettled/out-of-box query returns `max(floorCost, cheb × MIN_CROSS)`
   (`RegionCostField.java:224-247`, `floorAt:274-279`; class Javadoc `:14-36`). The s53
   frontier-floor design is explicitly *"guidance, never exclusion"* (`:33-36`).
2. **No built-membership over unreached in-box slots.** `cost[i]` is `UNREACHED` for all three
   kinds of unlabeled. Baked metadata (`cheapSlot[]`, `reachedFrags[]`, `slabs[]`, `:88-103`,
   `bakeSlabs:195-204`) is all keyed off *reached* slots.
3. **The exploration never touches disconnected in-box regions** (§1).

**To make "built-but-unlabeled = disconnected" meaningful:**
- Enumerate in-box regions actually **built/resident** (`grid.fragmentRecord(0,r) != null`,
  `RegionGrid.java:904-910`) at build termination, over `bound`'s cells.
- Cross with "not settled" (`reachedFrags[ri] == 0`).
- **Add the missing correctness guard: the box must fully contain every goal-reaching route.**
  `bound` is a search-confinement box, not a reachability boundary. An in-box unreached region
  could still be reachable via a corridor that exits and re-enters the box (`relaxFrag` clips
  such corridors at `:1494`). "Unreached in-box" only proves "disconnected *within this box*".
  `pad=3` (`:1221`, `PathPlan.java:1450-1454`) is heuristic slack, not a containment guarantee.

---

## 3. How a #5 block-A* prune would consume the infinite-cost set

Today the region field feeds the block heuristic as a `max()` lower bound only — **no prune**.
`BlockPathfinder.h(x,y,z)` (`:1055-1078`):
```
if (regionField != null) {
    float rc = regionField.costAt(x, y, z);
    if (rc < RegionCostField.UNREACHED) {   // DEAD — costAt never returns UNREACHED
        float hr = hWeight * rc * H_STRAIGHT;
        if (hr > base) base = hr;            // tighten h; never reject the node
    }
}
```
A cell in a provably-unreachable region is merely *deprioritised*, then still expanded.

**Where a prune lives:** the `Relaxer.accept`/`relax` path (`:1080-1083`) or folded into `h` as a
hard reject — same shape as the existing `RegionBound confineBound` corridor reject, keyed on a
reachability set instead of a geometric box.
**Carrier:** `RegionCostField` extended with a per-region `INFINITE` tri-state (settled /
provably-unreached-in-box / optimistic-unknown), populated only for in-box + built + box-contained
+ unreached regions. The field already rides read-only onto the async `SearchRequest`
(`BlockPathfinder.java:680-687`, `PathPlan.java:1436-1440`) — a tri-state rides with no new plumbing.

---

## 4. The OPTIMISM boundary — what state is missing

| kind | must | field state today | distinguishable? |
|---|---|---|---|
| out-of-box (never considered) | stay optimistic | `regionIndex(...) < 0` (`RegionCostField.java:227,314-318`) | **YES** — `ri < 0` |
| in-box, built, walled off | **invalidate (harvest)** | `cost[i]=UNREACHED`, `reachedFrags[ri]=0` | **NO** |
| in-box, unbuilt/unresident | stay optimistic | `cost[i]=UNREACHED`, `reachedFrags[ri]=0` | **NO** |

The two in-box cases are indistinguishable today. Missing state: (1) per-in-box-region built
signal (`grid.fragmentRecord(0,...) != null`, sampled tick-thread at build termination like
`bakeSlabs`); (2) a "was actually considered" mark (edge-expansion drops rejected in-box
neighbours silently at `relaxFrag:1494-1506`); (3) a `bound` containment guarantee (§2). Until
(1)-(3) exist, every unlabeled slot must stay optimistic — the ratified s53 posture.

---

## 5. Level question — L0 ONLY

`costToGoalField` addresses the goal at level 0 (`:1009-1011`) and calls `expandNode(...,0,...)`
(`:1087`). Call sites: `PathPlan.regionFieldFor` (`:1441-1464`, box `RegionBox.around(botRegion0,
targetRegion0, 3)`) and `AllyBotEntity.traceTo` (`:534-542`) — both level-0; else tests. **No
coarse-level cost-to-goal field.** Coarse A* uses `SimpleRegionHeuristic` (Euclidean-centres ×
min-crossing), which runs no reverse Dijkstra and consults no connectivity.

---

## 6. Coarse boxed-in feasibility — walk-only trivial; caps-general needs a pass

Per-fragment, per-face **opening** primitive exists at every level: `RegionFragments.touchesFace(
frag, f)` (`:253`); coarse `faceMask` built on roll-up (`PyramidMerger.java:461-471`).
`grid.fragmentRecord(L,...)` (`:904-910`, null ⇒ unbuilt/optimistic-open),
`CostPyramid.faceFootprint(level,row,frag,face)` (`:378-381`), `kind` (`:350-353`).

- **WALK-only "does the goal's L3 fragment touch any shared open face?"** — trivial: one record
  fetch + ≤6 `touchesFace` reads (+ the neighbour's opposite-face touch, ≤6 more). No search.
- **Caps-GENERAL boxed-in is NOT a single accessor:** (1) dig-out is always emitted for a
  break-capable bot (`:1334-1360`) and there is **no per-face unbreakable-barrier accessor**;
  (2) coarse collapse (`fc==63`) reads as optimistic passable mass (`DESIGN-typed-fragments.md
  §4,§8`, 6-46% collapse) → a coarse "no crossing" would be unsound where collapse happened;
  (3) two facing halves + neighbour built-state must be joined; an unbuilt neighbour is
  optimistically open.

**Verdict:** a high-altitude walk-only, no-break boxed-in test is trivial today. A caps-general
boxed-in proof needs either a per-face "unbreakable seal" bit on the fragment record, or the
dedicated region-tier flood-fill connectivity pass earmarked for the boxed-in-goals arc.

---

## Appendix — key file:line index

- `costToGoalField` — `RegionPathfinder.java:999-1127` (seed `:1025-1039`, loop `:1067-1115`,
  backstop `:1085`, early exit `:1096-1114`, floor `:1116`, slabs `:1119`).
- `RegionBox` — `:1205-1226`; per-relax admission `relaxFrag:1494`.
- `expandNode` + `ensureNode`-of-neighbours-only — `:1272-1461`, `ensureNode:2111`.
- `RegionCostField` — `UNREACHED` init `:148`; `costAt` (never returns UNREACHED) `:224-247`;
  `floorAt:274-279`; `record`/`bakeSlabs:168-204`; `resolveSlot:254-266`.
- Block-A* consumption (max-only, no prune) — `BlockPathfinder.java:1055-1078`; plumbing `:683-745`.
- Call sites — `PathPlan.regionFieldFor:1441-1464`; `AllyBotEntity.traceTo:534-542`.
- Coarse accessors — `RegionFragments.touchesFace:253`, `footprint:269`, `kind:218`;
  `CostPyramid.faceFootprint:378`, `kind:350`, `isBuilt:263`; `RegionGrid.fragmentRecord:904`;
  `PyramidMerger` coarse faceMask `:461-471`.
