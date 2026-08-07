# NOTES — region-tier deferred work (#1 anti-flap, #6 capability-aware region graphs)

Condensed 2026-08-07 from `DESIGN-anti-flap.md`, `DESIGN-capability-aware-region-graphs.md`,
`ROADMAP-region-nextwork.md`, `FINDINGS-reverse-reachability.md` and
`PROPOSAL-region-air-bridging-cost.md` (all deleted — deliberation, superseded, or shipped). This file
keeps ONLY what future work needs. Everything shipped from that set is documented where it lives:
`DESIGN-boxed-in-reachability.md` (#4/#5), `NOTES-region-tier.md` §1–§3 (the #2/#3 from-fragment node
key, the `VIRTUAL_START_FRAG` sentinel, the flood taxonomy), `DESIGN-persisted-invalidation-memory.md`
(the evidence model), `NOTES-region-findings.md` (rulings, measured numbers, do-not-retry list).

**All `file:line` cites below are from the July-2026 `orebit-mc121-wt` worktree and are STALE. Treat them
as "look near here", never as fact — re-verify before acting on any of them.**

---

## Where the set landed

| item | state |
|---|---|
| #2/#3 virtual-start fragment (S node, from-fragment/approach blame keys) | **SHIPPED** (`NOTES-region-tier.md` §1/§2, `VirtualStartFragmentKeyTest`) |
| #4/#5 boxed-in reachability + block-A\* hard prune | **SHIPPED** (`DESIGN-boxed-in-reachability.md` §14 is the as-built) |
| **#1 anti-flap** | **DEFERRED — cause not understood** (below) |
| **#6 capability-aware region graphs** | **DEFERRED — designed, not built** (below) |

**Two set-level rules that still bind (do not re-litigate):**

- **Persistence split.** A flood is a *budget* artifact; only *structural* facts earn durability. The
  durable rows in this arc are **crossing invalidations** through the existing evidence model
  (from-fragment-keyed via #3). **Boxed-in is never persisted** — reachability is a `(goal, start)`
  property, so persisting would need per-start keying (storage blowup), and it is cheap to recompute.
- **Owner invariants bind both items:** find-path-iff-exists, prove-nonexistence, no baked capability
  assumptions, no per-world-feature special-casing, no arbitrary timers, no recovery machinery that
  masks movement bugs, region tier stays tick-confined, benchmark anything hot (paired A/B).

---

# #1 — ANTI-FLAP (route flap / orbit)

> **STATUS 2026-08-07 (owner): DEFERRED, and the next step is a FORENSIC, not a build.** Verbatim:
> *"there were some proposed fixes, but we really need a better understanding of the cause."*
> Section §3 below is a **menu of unratified candidates**, not a plan. An anti-flap v1 was built and
> reverted (§2) precisely because it was aimed at a mechanism that turned out not to be the one firing.
> Reproduce and diagnose first; the earlier design's confidence was not earned.
>
> Read `NOTES-region-tier.md` §3 first — it is the ratified flood taxonomy (SCOPED-PROOF vs BLOCKED)
> that any anti-flap fix must land inside, and it names the position over-scope below as "owned by the
> anti-flap arc".

## §1. The symptom, the repro, and the mechanism that IS verified

**Symptom.** The bot orbits a region, re-searching forever, never converging or honestly giving up.

**Live repro (the one fixture #1 owns — the *cliff* fixture is #2/#3's, not this one):**
no-capability bot, clean cliff master world, `syncSearchBudgetNodes = 40000`, start `(68,64,-76)`;
the bot orbits region `(4,8,-5)` (~615 searches observed, no convergence).
**Trap:** on a *reused* world, persisted crossing-memory rows seed different routes than a clean master
(the owner's world carried 4 remembered crossings, the clean master 0) — a mis-scoped persisted row is a
**permanent cage**. Always state which world the repro ran on; live-vs-headless divergence was traced to
exactly this once already.

**The blame chain (verified in code at the time; re-verify line numbers):**

1. A window search toward a far target floods. `BlockPathfinder` (~`:926-965`) detects the budget hit
   with `(startGeo − commitGeo) ≤ PARTIAL_MIN_PROGRESS`, **suppresses the partial → returns null**.
2. `PathPlan.resultStatus` (~`:972`) classifies "null with >1 expansion" as BLOCKED, bumps
   `blockedGeneration`, snapshots the blame inputs.
3. **On the very next tick** `repairBlocked` (~`:1351`) → `blockedHop` → `blameHop` →
   `HierarchicalRegionPlan.onBlocked` condemns the first unrealized window crossing and **re-derives the
   skeleton**.
4. The re-derived skeleton is a different near-equal route; the bot half-follows it, floods again from
   another too-far launch, blames again → **orbit**.

**The scoping insight (this part is solid).** A flood is a fact about the **(launch position, target)**
pair. Blame condemns a **crossing**. Two scopings were wrong:
- **approach scope** — condemning the crossing for *every* approach (the A==G collapse). **FIXED by #3**
  (from-fragment/approach keys), which is why the cliff fixture is #3's.
- **position scope** — condemning on the strength of a launch that may be many regions short of the
  target. **Still unaddressed.** This is what #1 is about, and it is still only a *hypothesis* about the
  orbit fixture.

## §2. v1: BUILT, then REVERTED byte-identical (2026-07-25) — read this before rebuilding

v1 was a **block-tier keep-guard**: in async mode, at `PathPlan.pollPending`'s RESULT case, DISCARD a
flood result and keep walking the current plan iff all six held:

```
async.resultBudgetHit()                      // a FLOOD (node/time cap) — NOT heap-exhaust
&& driverHasForwardWaypoints                 // driver's REAL waypointIndex < path.size() (channel param, not geometry)
&& blockPlan != null
&& skeletonGeneration == planSkeletonGen     // the region skeleton has not SWAPPED since this plan was built
&& !planImpacted()                           // the plan's traversed chunks are unchanged
&& botOnBlockPlan(actualFloor)               // the bot is on that plan
```
On keep: `status = RUNNING; return;` — no `blockedGeneration` bump, no durable invalidate.
It compiled, passed the suite (562/0/5), and survived red-team on its own axes.

**Why it was reverted (the load-bearing lesson):**
- **Misaligned with the actual flap.** The observed flap is a **region-crossing invalidation
  oscillation** (`region-crossing BLOCKED (gen 1,2,4,6,7) -> REROUTED` — over-condemning crossings) that
  fires at **plan-consumption boundaries, where a block-tier keep is INERT** (no forward waypoint, so the
  guard cannot fire at all). A block-tier keep structurally cannot fix a region-tier oscillation.
- **It perpetuated a wrong-way partial** — kept a plan whose terminus led *away* from the goal. Any
  re-do **needs a progress-toward-goal conjunct** (keep only if the plan's terminus is closer to the goal
  than the bot is): the v1 red-team's dropped `cond5`, now proven necessary.

**Design properties worth preserving in any re-do** (these were right):
- **Heap-exhaust must stay untouched.** `budgetHit` is false for a drained open set, so the guard is
  never consulted — the "heap-exhaust → durable proof" rule survives verbatim.
- **Give-up must stay honest.** At a real wall the bot consumes its partial to the terminus
  (`waypointIndex ≥ size`) → any keep goes inert → FAILED with **0 repeat blames**. The wall repro is the
  oracle; anti-flap must never turn a give-up into a loop.
- **SWAP scope needs a cross-tick counter**, not a per-tick flag: the swap happens on a prior tick and
  `pollPending` runs before `stepCascade`. v1 bumped `skeletonGeneration` in `resetWindow()` (whose only
  callers are the two true swaps — cascade SWAPPED and `repairBlocked`; EXTENDED never calls it) and
  stamped `planSkeletonGen` in `snapshotPlanChunks()`.

## §3. Candidate fixes (UNRATIFIED — do not build before the forensic)

- **(A) Keep-guard v2** — v1's six conjuncts **plus** the progress-toward-goal conjunct, verified on the
  **orbit** fixture (68,64,-76), not the cliff one. Cheap; but §2 argues it may be inert exactly where
  the flap fires.
- **(B) Hold-the-target / closest-launch blame defer** — latch the flooding far target `T`; keep walking
  the retained (non-flooded) plan; re-probe the **same** `T` from each successively closer launch; blame
  only when `closestTo(T)` = *adjacency* (within 1 region of `T`'s skeleton index) **OR** the
  *no-forward-progress bottom* (the near window step also floods, so no closer launch is reachable).
  Termination is by strictly-decreasing region-distance, **not a timer** — a non-terminal flood either
  clears (a closer launch FINDS) or advances one region. Reuses the shipped rolling-skeleton forward
  slide (EXTENDED keeps the live `blockPlan`; only cursors move). More machinery: one held field + one
  `forwardIndexOf` compare, all cold-path.
- **(C) Attack the region tier directly** — the oscillation is over-condemnation of crossings; the fix
  may belong in `onBlocked`/blame breadth rather than in either plan-keeping scheme. Untried.

**Forensic pointers for whoever picks this up:**
- `NOTES-region-findings.md` §8.3 records two facts that bear directly on it: (i) "prefer a known-good
  route over a partial" **does not exist in code** — both plan-install sites overwrite unconditionally,
  and `PathPlan` FOLLOW-TO-TERMINUS makes *partials* stickier than full plans; (ii) partial endpoints
  are scored by straight-line octile only, so a deep descent always reads as "progress" even while its
  g-cost climbs (695 → 1170 across successive replans). `NOTES-region-findings.md` §8.2 (skeleton portal
  cells are mostly not standable → `WindowTargeting` falls back to the raw region CENTER) is another
  plausible contributor to a bot that keeps re-aiming and never converges — rule it out before blaming
  the blame path.
- Headless seam: drive a synthetic skeleton (`RegionGrid.headless` idiom) toward a flooding target and
  assert the blame count, not the timing.
- The honest acceptance bar is **convergence + 0 repeat blames**, not speed.

---

# #6 — CAPABILITY-AWARE REGION GRAPHS (movement-verified fragments)

> **STATUS: DEFERRED (owner 2026-07-25, reaffirmed 2026-08-07). #6 is an OPTIMIZATION, not a correctness
> requirement** — search-time invalidation + re-search already deliver correctness (a no-capa bot at a
> cliff bottom searches, floods, invalidates, and honestly gives up without #6). #6 only avoids wasting
> search on the known-impossible. **Open blocker: the directional-asymmetry project (§4).** The
> *upstream* active work is the NavGrid-build performance pass (`PERF-DESIGN-navgrid-build.md`), which
> frees the compute budget more accurate region building would spend.

## §1. The defect

The L0 fragment flood connects cells on **passability**, not **traversability**: 6-connected (incl. ±Y)
`passable[c] && passable[neighbour]` (`FragmentBuilder.java` ~`:204-209`; **still true as of 2026-08-07**).
So a shelf and the cliff-top above it merge into one fragment via the open air column, even though a
no-place bot cannot ascend it. The region tier then degenerates to a one-hop skeleton and dumps a
region-scale problem on the block tier, which floods the cliff face.

Fixture: no-capability bot (`canPlace = canBreak = false`), `(70,63,-68) → (77,72,-78)`, goal atop a
cliff whose only cap-legal route is a long walk to a far-side staircase.

## §2. The model — a per-cap-axis STRUCTURAL FLOOD (not millions of A\*)

Make the flood's connectivity test the **actual movement predicates** — no heap, no cost/heuristic, no
edit tracking, none of the search-time safety machinery; just the pure geometric "can a free move go
between these cells?".

- **Node domain:** the block tier's *occupiable positions* (standable-with-headroom floor cells +
  swimmable water cells), not raw passable air. (Exact floor↔fragment cell convention to be aligned with
  the existing node↔fragment mapping at implementation.)
- **Free move set** (no-break/no-place): `Traverse`-flat, `Diagonal`, `Ascend`/`Descend` onto existing
  terrain, `Fall`, the whole swim family, `Climb`, `Parkour`, `DiagonalParkour`, `WalkOff`,
  `RideBubbleColumn`. Break-gated: `MineDown` + the dig edit-arms. Place-gated: `Pillar` + the place
  edit-arms. (Capability gating is two-layered: hard `return` for Pillar/MineDown/jump; soft edit-arms on
  the ground moves, which degrade to the free case when caps are absent.)
- **Connectivity rule, per candidate cell pair `(A,B)`, predicates run BOTH directions:**
  - both directions connect (**locally mutual**) → same fragment (union);
  - exactly one direction (**one-way**) → a **directed intra-leaf crossing** `A→B`, *not* a union;
  - neither → no edge.
- **Fragments = the locally-mutual components.** This is the shelf/cliff-top fix: `Fall` connects
  cliff-top → shelf, nothing free connects shelf → cliff-top, so it is a one-way crossing and they are
  **different fragments**. The search model already has intra-region inter-fragment directed edges (the
  break-only mine edges); #6 generalizes that set.

**No Tarjan needed.** *Every one-way free move is strictly descending* (`Fall`, falling-`Parkour`,
`WalkOff`-down), so directed cycles are impossible; mutual reachability decomposes into a flood over the
two-way moves plus directed crossings for the one-way ones.

**Soundness (the load-bearing property).** Every free move is represented as *either* a union *or* a
directed crossing — **no reachable transition is ever dropped**, so no false disconnection is possible.
The only residual error is *over*-fragmentation (a mutually-reachable pair split into two fragments
joined by two directed crossings), which is still fully routable and merely costs fragments. Error stays
on the safe side. The one way #6 could under-connect is a **buggy movement predicate** — mitigated
because the flood uses the *same* predicates the block A\* uses, so such a bug is already a search bug.

## §3. Lever 1 — the capability lattice as a union-find overlay (NOT 4 floods)

The base (free-move) fragment set is **capability-independent**, so one flood serves all four
`place × break` corners. Capabilities only *add* crossings, and where an added crossing makes a
previously one-way pair **mutual**, they *union* base fragments:

- **`+place`** adds `Pillar` + the place edit-arms → where a base one-way `Fall` cliff-top→shelf gains
  its inverse (Pillar shelf→cliff-top), the pair becomes mutual → **union** in the `+place` graph.
- **`+break`** adds `MineDown` + the dig edit-arms, same rule. **`+break+place`** = both overlays.

Run the capability predicates **only at fragment boundaries** (floors, walls, one-way-crossing
endpoints), never a full re-flood: one base flood + three cheap boundary passes. Reuses the existing
coarse union-find pattern `PyramidMerger.combineFragments` (which already unions child fragments on a
shared-face condition), with a capability-move predicate instead of footprint overlap.

**Boundary data gap (verified, must be closed).** Fragments today store only per-face **bbox footprints**
(`RegionFragments.faceMask`/`packFootprint`) — far too coarse to ask "does a `Pillar`/`MineDown` bridge X
and Y *here*?". The base flood must **retain the one-way-crossing endpoint cells** (and the boundary
cells the capability predicates need) as it runs — a small per-fragment cell list or boundary bitmask,
computed once, consumed by the three overlays. This is new state the base flood must emit.

## §4. THE BLOCKER — directional asymmetry, end to end

For a no-capa bot the top↔bottom relationship is **directional**, and the naive binary is a false choice:
**connect** (merge) lies about the ability to **ascend** (a bottom→top search thinks the top is
reachable, floods, fails); **disconnect** lies about the ability to **descend** (a top→bottom search
misses the valid `Fall`). The honest representation is the directed one-way crossing of §2 — neither lie.

**Why that is a real project, and the deferral reason:** the region tier must become **directed-edge
aware end to end**.
- The forward region A\* already is.
- The **goal-rooted reverse Dijkstra** (`costToGoalField`) must follow crossings **backward** (in-edge
  traversal) — it does not today.
- The **coarse roll-up** (`PyramidMerger` union-find) must **not merge across a one-way edge**, or a
  parent fragment re-asserts the mutual reachability the split removed.

For a **capable** bot the asymmetry mostly dissolves (the +place/+break overlay unions top+bottom), so
this is a **no-capa-specific structural cost**.

## §5. Lever 2 — mask-backed movement predicates (a perf pass that ships FIRST, on search)

A movement predicate today calls `descriptorAt(x,y,z)` per cell and different movements **re-read the
same cells**: the start-floor descriptor is read independently by Traverse/Ascend/Descend/Parkour (3+
reads/expansion of one cell); the `y+3` takeoff head by Ascend/Parkour/Pillar; Traverse's dest body pair
== Descend transit == Diagonal corner column. `MovementContext` caches none of it (only the path-edit
diff and the door edge are hoisted).

The flood *already* computes per-leaf `passable[]/standable[]/water[]` plus footing and air-headroom
tests (`FragmentLeafComputer.computeLeaf`, `FragmentBuilder`). Precompute a compact per-cell **profile**
— `{standable, passable, water, headroomRun, footing}` — and rewrite the free predicates to consume
those **arrays** (index + compare) instead of `descriptorAt`; "N headroom cells passable" collapses to
one `headroomRun ≥ N` compare (generalizing the `NavFlags.headroomProves` prefilter).

Two granularities, because search has edits:
- **Flood (static leaf):** the mask is fully static → the clean, total win.
- **Search (edits + multi-leaf):** the block A\* mutates cells via `PathEdits`, so the win is a
  **per-node envelope precompute** (compute the neighbour `{standable, feet-passable, head-passable}`
  tuple once per popped node, share it across that node's move candidates). What it cannot serve
  (Parkour's multi-cell gap, Fall's deep column, Pillar/MineDown verticals, the `y+3` takeoff) stays on
  the current read path.

**Sequencing:** lever 2 ships **on its own, on search, JFR-driven**, gated on **byte-identical search
results** + a measured expansion-time win (paired A/B). It is independently valuable and it de-risks #6
by proving the mask-backed predicates correct and fast before flooding with them. Then #6's base flood,
then the lever-1 overlays.

## §6. Cost, measurement, and the numbers NOT to trust

The connectivity test goes from "6 passability ANDs per passable cell" to "run the free move set per
occupiable cell, both directions". The node domain *shrinks* (standable+water < all passable) but the
per-pair work *grows*. **Net is unknown and must be measured — assert no number.**

- `ConnectivityBenchmark` (`worldmodel/hpa/`) already isolates a 16³ leaf and compares flood variants
  (OPEN/HALF/SPECKLE/CHECKER) — **add a movement-flood variant** beside `fragmentBuild`; paired
  interleaved A/B is the gate for the base flood and lever 2.
- `ChunkBuildBenchmark` (`worldmodel/pathing/`, FLAT/SURFACE/CAVE) is the ms/chunk region-gen harness —
  the "is #6 inside the tick budget" gate.
- JFR on `PathfinderBenchmark` (`-Pprof=jfr,cpu`) drives lever 2.
- **The "~38 ms adversarial chunk" figure is PRE-REPO memory, not a benched result in this codebase.**
  Re-establish it under current code before treating it as the budget ceiling. What is known: most of it
  was the **NavGrid build**, and most of *that* the neighbor-aware bits (depth nibbles, safe-break) — i.e.
  the dominant chunk-gen cost is *upstream* of the region flood. Likewise "~13 µs/leaf" for the current
  passable flood is a Javadoc claim, not a measurement.

## §7. Second-order consequences

- **MAX_FRAGMENTS pressure.** Movement-verified membership is **finer** than passable-adjacency (bare
  columns split off, fall-pockets become their own fragments) → more fragments per leaf → more chance of
  tripping `MAX_FRAGMENTS = 62` (`RegionFragments.java:105`) and collapsing to a uniform mass. Collapse
  is a cap-blind fallback that degrades to *today's* over-connected behavior — **safe** (over-connection,
  invalidation-absorbed), so a raised collapse rate is a measured tuning input, not a correctness risk.
  Measure the fragment-count delta on the typed-fragments census terrain.
- **Persistence.** Output is the same *shape* as today (fragments + crossings) → it deserializes on
  reload, never re-floods; cost is paid once per novel chunk + per edit. Two format questions: the
  **directed intra-leaf crossings** need a codec slot (crossings are derived from footprints today), and
  the capability overlays are persist-per-corner vs recompute. **Leaning: persist base fragments +
  one-way crossings; recompute the three capability overlays lazily on first bot-of-that-cap demand**
  (RAM-cached, tick-thread) — one set on disk, not four. Codec bump; the format is a rebuildable cache.
- **Tick confinement.** The movement flood, like every flood, runs on the tick/maintenance thread. No
  planner-pool worker may trigger it; overlays and any lazy caching are tick-thread-owned.

## §8. What #6 does and does not subsume

- **vs #4/#5 (shipped):** #6 makes a no-cap bot's cliff **structurally** two fragments, so the region A\*
  routes around it (or heap-exhausts to a true BLOCKED) without flooding → #4's harvest fires *less
  often*. It does **not** replace them: #4/#5 still own (a) **optimistic unbuilt terrain** (no movement
  flood can pre-disprove ungenerated chunks), (b) **coarse-collapse mass**, and (c) the **block-tier**
  prune itself (#6 is a region-tier construction).
- **The one residual #6 actually owns** (cited from `BoxedInCourse.java` and
  `DESIGN-boxed-in-reachability.md` §14): the **caps-general coarse boxed-in proof**. Coarse crossings
  are walk-sound for no-dig bots, but for a **dig** bot the coarse view is optimistic about dig-through —
  there is **no per-face unbreakable-seal bit** on the fragment record — so a dig bot's genuinely
  *unbreakable* tomb never seals at a coarse level. Closing it needs either that per-face seal bit or
  #6's connectivity pass.
- **vs #2/#3 (shipped):** virtual-start deliberately does **not** split the merged fragment; it
  self-corrects the merge at runtime via approach-conditioned invalidation. Because that already makes
  the merge *correct*, **#6's split is a performance/accuracy optimization, not a correctness fix** —
  which is why its sequencing is measurement-gated, not urgent.

## §9. Acceptance battery (when it is eventually built)

- **staircase fixture** — a no-cap bot ascending a real block staircase must still be ONE connected
  region / must still FIND (proves mutual-union did not over-split a real ascent).
- **bare-shaft fixture** — goal atop a sheer column, no lateral escape: shelf and top must be separate
  fragments with only a one-way `Fall` crossing, and the no-cap search must true-BLOCK **structurally**;
  the cliff fixture must PASS at the default 10k budget with **fewer invalidation cycles** than
  virtual-start alone (measure the delta).
- **place-capable regression** — `+place` unions shelf+top via the Pillar inverse → a `canPlace` bot's
  route is **byte-identical** to today.
