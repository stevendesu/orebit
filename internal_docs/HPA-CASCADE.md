# HPA\* Cascade — stateful nested per-level skeletons (CONDENSED — implemented; full text in git history pre-s52)

**Status: SHIPPED (s35), unconditional since s36** (the `HIERARCHICAL_CASCADE` flag and the two-tier
shortcut were deleted).

**Model:** `HierarchicalRegionPlan` keeps a STACK of per-level region skeletons (coarse top → level-0
bottom); each level's plan is confined to a window of the level above. On movement, only the level whose
window the bot exited is re-planned; the top level slides/collapses — effectively unbounded range.

**Where the code lives now:**
- `src/main/java/com/orebit/mod/pathfinding/regionpathfinder/` — `HierarchicalRegionPlan` (the stack),
  `RegionPathfinder` (`planWithin` / `planLevelFragments`), `RegionPathPlan` (carries `level`),
  `RegionEdgeBlacklist` (per-level escalation)
- `src/main/java/com/orebit/mod/pathfinding/PathPlan.java` — cascade driver + `repairBlocked` + `blameHop`
  (package-private collaborators `WindowTargeting` / `AsyncWindowSearch` / `SkeletonDump` beside it)
- `src/main/java/com/orebit/mod/worldmodel/hpa/` — `RegionCrossingMemory` + `InvalidationRollup`: the #5
  per-dimension crossing-invalidation memory the cascade SEEDS its blacklists from at construction and
  RECORDS proven-dead crossings into (`DESIGN-persisted-invalidation-memory.md`)
- Tests: `src/test/java/com/orebit/mod/pathfinding/regionpathfinder/HierarchicalCascadeTest.java`,
  `RegionTubeEscalationTest.java` (§3b escalation), `RegionFloodGuardTest.java` (§3a guard)

> **IN FLIGHT:** `HierarchicalRegionPlan` + `PathPlan` are being reworked by the **rolling skeleton**
> (increment A per `DESIGN-rolling-skeleton.md`): levels ≤ `ROLLING_MAX_LEVEL` slide their window with the
> committed cursor (the window base IS the cursor, §3.1/D1) and L0 EXTENDS by suffix-splice from its own
> tail when the window clamps there (§3.2/§4 — head-drop + tail-append, prefix identical, INV-1; a
> three-way `onBotMoved` verdict UNCHANGED/EXTENDED/SWAPPED, §4.3). This fixes the measured
> **segment-tail pinning** pathology (the window target pins to the L1 hand-down portal cell and only
> physical occupancy can move it — the "staircase-then-descend" cobble waste; full forensic trigger
> inventory in `FINDINGS-window-slide-cadence.md`, summarized in DESIGN-rolling-skeleton.md §1).
> Statements below about window/commit machinery describe the pre-rolling baseline where they conflict.

**§ map (sections cited by code Javadocs):**
- §1 why — a single-level skeleton re-plans the whole route every wobble and caps range.
- §2 the model. §3 building the stack (initial coarse→fine descent).
- §3a FLOOD escalation: every UNTUBED search carries the cap-safe flood guard
  (`RegionPathfinder.lastWasFlood`, a pop beyond `maxChebAtLevel` of the start); a flood is an AREA problem,
  not a bad hop — the cascade widens the lens (rebuild one level coarser, blacklisting nothing) up to
  `MAX_COARSE_LEVEL`. A flood still present at the coarsest level is an honest FAIL.
- §3b the TUBE: every sub-top level plans PERMANENTLY confined to a `RegionPathfinder.RegionTube` corridor
  (±`TUBE_MARGIN`=2 parent cells around the level-above skeleton); the §3a flood guard is DISABLED inside it
  (the tube itself is the area bound). A tubed `null` is therefore NEVER a no-route: it raises
  `lastWasTubeConfined()` + a parent-cell TOUCH mask (`lastTubeTouchedMask()` — bit i = the failed search
  explored ≥1 node inside tube-skeleton cell i; touch-membership suffices inside a tube because `relaxFrag`
  rejects out-of-tube targets before interning, so every explored node's ancestry is corridor-internal), and
  `rederiveWithTubeEscalation` blames the first parent-window hop the search did NOT touch
  (`blameTubeConfined`, all-touched fallback = the hop into the window-far cell), re-planning from the
  parent and recursing upward (`MAX_TUBE_ESCALATIONS`=32 backstop). Blames are recorded as
  `RegionCrossingMemory.PROV_ESCALATION` — realized-blame INFERENCES that only need to converge, so they are
  SESSION-ONLY: `CostPyramidCodec` filters them out of every encode (never persisted; PROOF/ROLLED_UP rows
  persist). `failed=true` is reachable ONLY from an untubed top-level heap drain, a flood at the coarsest
  level, or the escalation backstop.
- §4 sub-goal projection between levels (a level plans toward the parent's hand-down cell).
- §5 THE cascade rule — re-plan ONLY the exited level. *(Amended in flight by the rolling skeleton —
  slide-and-extend at levels ≤ `ROLLING_MAX_LEVEL`; see the IN FLIGHT note above.)*
- §6 blacklist escalation up the hierarchy (online repair of unrealizable hops; the coarse-level blame is
  the §3b realized blame, not the old committed-hop guess). **Level-0 blame is edge-REALIZATION blame**
  (the up-cliff arc): every non-trivial block search records the region crossings its surviving cameFrom
  tree actually realized (`BlockPathfinder.collectRealizedCrossings` — moves whose endpoints straddle
  region boundaries are staircase-decomposed one region step at a time; a start-dead search of ≤1
  expansion proves nothing and skips the scan), and on a BLOCKED result `PathPlan.blameHop` condemns the
  **first window hop the failed search did NOT realize** — not membership, not position. Two refinements:
  the **start-position blind spot** (the treadmill fix) — realized crossings grow OUTWARD from the search
  start, so hops ending at-or-before the LAST window step in the start's region are unrealizable by
  construction and never blamed (start region == target region ⇒ -1, give-up semantics); and an (approach
  → V) hop is unrealized BY DEFINITION on a BLOCKED result (reaching V would have been a FOUND). All hops
  realized but the target cell unreached ⇒ blame the hop INTO the target step (closes the give-up loop),
  except behind the start-region rule. The blamed
  crossing goes to `HierarchicalRegionPlan.onBlocked`: per-plan `blacklists[0].add`, then (subject to the
  journey-scoping rules below) a `RegionCrossingMemory` `PROV_PROOF` record under the plan's effective
  `capsSig` + the `InvalidationRollup` record-time fold (a completed parent kill-set records a
  `PROV_ROLLED_UP` parent row, recursing upward). **Journey scoping** (recorded to the per-plan blacklist
  but NEVER to world memory): (a) a blame whose FROM is the failing search's OWN start region — proven only
  for the caps-connected component the bot stands in (the ravine rim-vs-floor problem); (b) a blame whose
  TO is the virtual goal V — see the V-row rule in the section below. Null-inventory plans (headless,
  `/bot trace`, tests) never record at all (`recordToMemory` — §3.3 of the persisted-invalidation design).
  The cascade's committed-advance is **fragment-aware nearest-first** (INV-2, byte-identical under
  rolling): the bot's floor is matched to the nearest forward skeleton step, (region, fragment)-gated at
  L0 with a region-only fallback, feeding the `exhausted` test and the hand-down.
- §7 collapse on approach + top-level sliding. §8 cap-safety.
- §9 integration per file (controller owns the stack; `PathPlan` keeps block driving).
- §10 state & data structures (house style — flat arrays, no per-tick alloc).
- §11 edge cases. §12 flag/migration/deletion (done — flags gone).
- §13 headless testing via `RegionGrid.headless` (no `ServerLevel`). §14 instrumentation.
- §15 decisions/defaults — §15.1 `WINDOW_CELLS = 4` per level (hand-down = the 4th cell; bigger =
  longer commits/fewer re-plans, looser intermediate routing).
- §16 implementation slices S6.1–S6.8; §S6.2 = `RegionPathfinder.planWithin` (a plan confined to a
  parent-level window).

## The virtual goal fragment (V) — per-approach goal seeding (level 0)

The skeleton no longer terminates on a nearest-centroid goal fragment when the goal has genuinely distinct
ways in. `RegionPathfinder.buildGoalApproaches` enumerates the goal's APPROACHES and, when at least one
approach OTHER than the goal's own fragment exists, seeds a synthetic node
**V = (goalRegion, `VIRTUAL_GOAL_FRAG` = 63)** — id 63 lives in the 6-bit fragment-ID space (real ids top
out at `MAX_FRAGMENTS`−1 = 61, id 62 reserved; NOT the count-field's `FRAGMENT_COUNT_COLLAPSED` 63, a
different value space). The level-0 A* then terminates on V's pop: as each approach node is expanded,
`relaxVirtualGoal` offers a virtual edge into V at that approach's cost, so the search routes through
whichever approach minimises path-to-approach + approach-cost. V is SEARCH-ONLY — it never enters a
`RegionFragments` record or a `RegionCostField`; consumers reading a fragment record by a skeleton step's
id must guard via `RegionPathfinder.isVirtualGoal`. When V engages, even a start-adjacent goal skips the
trivial short-circuit and routes through an approach edge.

**Two approach kinds** (`enumerateGoalApproaches`, deduped-cheapest into a `DigSeedSet` — parallel
`keys[]/costs[]/dig[]` arrays, linear-scan `indexOf`, cap `MAX_APPROACHES` = 40):
- **Walkable** (MIXED goal region only — a uniform goal keeps nearest-centroid): the goal cell's own
  fragment as a cost-0 self-approach, plus every neighbor-region fragment whose face footprint overlaps the
  goal fragment's on the shared face (uniform-SOLID neighbors skipped; AIR/WATER/unbuilt contribute
  fragment 0). Emitted OPTIMISTICALLY — the A*'s own caps gates decide reachability. At relax time a
  walkable approach's cost is the entry-aware octile `walkCost(node's entry portal → goal cell)` (charging
  the goal region's own within-region traverse; pillar-priced vertical steers a no-place bot away from low
  entries before the blacklist even engages).
- **Dig** (break-capable bots): the pockets of `RegionGrid.goalDigSeeds` — a BFS from the buried goal cell
  outward through breakable solid, budget `MAX_GOAL_DIG_CELLS` = **9** (owner-ratified, s53; was 12: the
  {−1,0,+1}³ entry-cost analysis bounds touched regions to ≤8 — the corner-goal 2×2×2 octant — instead of
  up to 27 at cap 12, and must stay ≤15 for the 5-bit packed BFS offsets). Each pocket seeds at its dig
  cost; the digCells==0 exposed-goal seed is skipped (already the self-approach). The same flood
  multi-source-seeds the reverse cost-to-goal field (`costToGoalField`).

**Why per-approach:** a MIXED goal fragment over-connects vertically (a bot swims/falls into its low cell
but cannot ascend to the goal cell), so each entry is an INDEPENDENTLY-blacklistable hop:
`relaxVirtualGoal` consults the level-0 `RegionEdgeBlacklist` on the (approach → V) crossing (keyed on the
full node key — see the from-fragment note below; formerly the entry-stripped physical key like
`relaxFrag`), so a blamed approach forces the region A* to a DIFFERENT side of the goal instead of
re-offering the same dead entry every replan (the up-cliff fix).

**From-fragment in the node identity (2026-07-26).** The search node is now
`(region, fragment, entry-face, from-fragment)` — from-fragment = the fragment the search last hopped FROM,
with the journey root stamped `VIRTUAL_START_FRAG` = 62 (V = 63). The (approach → V) blacklist row keys on
the **full node key** (`approachRowKey` — entry-face + from-fragment), reconstructed on the blame add-side
(`PathPlan.blockedHop`) via `approachRowKeyForStep` (entry-face is DERIVED geometrically, not stored on the
skeleton). So `(A|from S → V)` and `(A|from staircase → V)` are DISTINCT rows even when A==G: blaming the
dead direct approach leaves the go-around approach alive, and the cliff reroutes at the default budget
instead of the whole of V collapsing to one entry-stripped row (the old A==G false give-up). The blame
anchor scan (`PathPlan.blameHop`) now **skips VIRTUAL fragments** (S=62 / V=63) so an unreached V is never
mistaken for the bot's position when A==G. `from=S` rows name a journey-local sentinel → never persisted
(structural, no positional carve-out); regular crossing rows stay entry-independent physical pairs. The
reverse-Dijkstra cost field is gated to `VIRTUAL_START_FRAG` so its nodes stay byte-identical. Full design
of record: **DESIGN-virtual-start-fragment.md §0.5** (supersedes the increment-1/2 split in that doc's
§3/§4/§7).

**V-row journey scoping (never persisted):** an (approach → V) blame is condemned WITHOUT realized-crossing
evidence (reaching V IS reaching the goal — a FOUND), and V's key names only the goal REGION, not the goal
cell, so a persisted V-row proven against goal A would structurally poison goal B's approaches forever.
Hence V-blames stay per-plan (the blacklist), are never recorded to `RegionCrossingMemory`, and
`CostPyramidCodec.decode` drops any legacy row whose TO fragment id ≥ `MAX_FRAGMENTS` so old files
self-clean — DESIGN-persisted-invalidation-memory.md, the evidence model.

## The containment anchor (start/goal fragment resolution)

`RegionPathfinder.anchorFragment` resolves "which fragment is the bot/goal actually IN" by **containment
proof**, falling back to centroid guessing only when proof is impossible: (1) `RegionGrid.containedFragment`
— a level-0 flood over the resident `NavSection` (`startFragmentByFlood` / `FragmentLeafComputer
.fragmentContaining`, seeded at the FEET cell wy+1 when in-region, else the floor cell — the swimming-bot
convention), walked UP the pyramid via `InvalidationRollup.containedParentFragment` (which parent fragment
each child fragment unioned into — re-derived by the merge's own partition-invariant union-find), with an
ancestor-mismatch guard for clamped goal regions; (2) on -1, `nearestFacedFragment` — nearest-centroid
restricted to fragments that TOUCH ≥1 face (a faceless sealed-pocket fragment's centroid defaults to the
region center and out-attracts every real fragment for a mid-region bot — the search then anchors sealed
and drains instantly); (3) all-faceless degenerate records keep the unrestricted nearest-centroid. The
region-FAIL post-mortem (`logFailPostMortem` → `containedFragment(trace)`) prints the per-level anchor
walk + flood-precondition probes so a mis-anchored FAIL is attributable per level.
