# DESIGN — Async region tier: moving ALL planning off the tick thread

> **STATUS: PROPOSED (owner-requested audit — nothing implemented).**
> Design audit of the lift to run 100% of PLANNING (region skeleton + cost-field builds, in addition
> to the already-async block-tier window searches) on the planner pool. Maintenance
> (`HpaMaintenance`, `ChunkNavLoader`, `NavReclaim`) stays tick-side per the owner's scoping. Every
> phase below awaits owner ratification item-by-item (§6). Extends
> `DESIGN-background-pathfinding.md` (the shipped s44 block-tier pattern); measured numbers now live in
> `docs/Optimizations/12_field_build.md` and git history.

---

## §1 What planning still runs on the tick thread (inventory, with costs)

With `pathing.async=true`, only `BlockPathfinder.findPath` itself runs on the pool
(`PlanExecutor.workerLoop`, `pathfinding/async/PlanExecutor.java:187–223`). Everything region-tier
runs on the server tick thread. The call paths:

### 1.1 Fresh plan — `BotNavigator.replan` → `new PathPlan(...)`

`BotNavigator.java:541–558` → the PathPlan ctor (`PathPlan.java:353–413`):

| Step | Where | Cost (s53, 1.21.11) |
|---|---|---|
| Cascade build: `HierarchicalRegionPlan.build` → `rederiveSuffix` → ≤(topLevel+1)× `planWithin` | `PathPlan.java:394`; `HierarchicalRegionPlan.java:265–278` | region A* is **sub-10 µs per level** (RegionPathfinderBenchmark: OPEN_CAVERN 0.95, SEALED_DIG 0.29, MULTI_FRAGMENT 0.53, LONG_CASCADE full-stack 7.4 µs) |
| Forward dig-flood: `buildDigSeeds` → `RegionGrid.goalDigSeeds` (L0, goal-region-reached only) | `RegionPathfinder.java:407–408, 563–610`; `RegionGrid.java:373–435` | µs-scale post-slab-fix (`186669c`: ≤8 label slabs per build, de-boxed BFS; cap 9 → ≤8 touched regions) |
| First `replanBlock()` (below) | `PathPlan.java:412` | dominated by the field build |

### 1.2 Window replan — `replanBlock` (commit / consumption / edit-epoch recheck / repair)

Reached from `onBotMoved` on commit (`PathPlan.java:555–561`), `refreshWindow` on plan consumption
or a changed `NavGridUpdater.editEpoch` (`BotNavigator.java:348–359` → `PathPlan.java:1030–1033`),
and `repairBlocked` (`PathPlan.java:1005`). Per call (`PathPlan.java:696–779`):

| Step | Where | Cost |
|---|---|---|
| `WindowTargeting.target` — fresh **live** `NavGridView` + footprint/snap scans | `WindowTargeting.java:83–197` (view at `:112`) | ~0.6 µs view setup (SETUP bench) + bounded ≤16³ bbox scans — µs |
| **`regionFieldFor(target)` — the region cost-field build** (goal dig-flood seed + reverse Dijkstra), rebuilt whenever the window target moves; cached by root otherwise | `PathPlan.java:1068–1091` → `RegionPathfinder.costToGoalField` (`RegionPathfinder.java:689–804`), dig-flood seed at `:712` | **~90 µs (3³) … ~1.07–1.34 ms (10³); production-minimum 7³ box ≈ 310–400 µs** (fat-skeleton B-FAT, docs/Optimizations/12_field_build.md). This is THE bill: pre-fix it was 90.8% of FullSearch wall time (s53 JFR §7a) |
| Async submit: `SearchRequest` snapshot + mailbox bookkeeping | `PathPlan.java:828–836`; `AsyncWindowSearch.java:83–90` | negligible |
| (sync mode only) `new NavGridView(level)` + the block search itself | `PathPlan.java:771–773` | already solved by s44 async |

Note the owner's "×2 per replan": within one skeleton+field build cadence the **goal dig-flood runs
twice** — once seeding the field (`costToGoalField`, `RegionPathfinder.java:709–717`) and once for
the forward plan's virtual-goal node (`buildDigSeeds`, `:407–408`). Post-`186669c` both are µs-scale;
the FIELD build (Dijkstra + its `×63`-slot array) is the millisecond-class item, and it runs **once
per window-target move**, not per search (the `fieldRoot` equals-gate, `PathPlan.java:1069`).

### 1.3 Cascade stepping + repair

- `stepCascade` → `hier.onBotMoved` per settled boundary (`PathPlan.java:580–597`;
  `HierarchicalRegionPlan.java:158–215`): integer math fast path; on a window exit,
  `rederiveSuffix` = a handful of `planWithin` calls (sub-10 µs each) + one L0 `buildDigSeeds`.
- `repairBlocked` → `hier.onBlocked` escalation (`PathPlan.java:988–1007`;
  `HierarchicalRegionPlan.java:225–243`): ≤topLevel+1 re-plans + `replanBlock` (⇒ one field rebuild
  if the target moved).

### 1.4 Adjacent tick-thread searches (context, not this doc's scope)

- **Gather challenge searches** — 4 raw sync `BlockPathfinder.findPath` calls
  (`BotGatherer.java:359,361,393,395`), caps-only, no region field, HANDOFF veto list. They bypass
  PathPlan entirely; making them async is a separate (owner-vetoed-for-now) item.
- `/bot trace` one-shot field (`AllyBotEntity.traceTo`) — cold diagnostic, stays sync.
- `NavWarmup` — deliberately tick-side (§4.6 of the s44 design).

### 1.5 The bottom line per bot

In async mode, the tick thread's remaining **planning** bill per replan is ≈
**one field build (0.1–1.4 ms, window-target-move cadence) + 10–70 µs of skeleton/cascade work +
µs of targeting**. The field build is 90–99% of it. At V1 (one bot) this is absorbable; at V2
(50+ bots) a replan wave of 50 × ~0.5 ms ≈ 25 ms is a **full tick budget on field builds alone** —
the case for this lift.

---

## §2 Shared mutable state the region tier reads (safety verdict per structure)

### 2.1 `NavStore` maps + `NavSection`s — **already worker-safe** (existing s44 machinery)

Workers read sections via `NavGridView.background` (no live-chunk fallback) under the
`activeSince` epoch stamp (`PlanExecutor.java:203`); recycling is deferred by `NavReclaim`
(`worldmodel/pathing/NavReclaim.java:51–69`, drained at `OrebitCommon.java:79`). Any region-tier
work moved **inside a pool job** (stamped before the request is read) inherits this guard — that
includes `goalDigSeeds`' direct `NavStore.get` reads (`RegionGrid.columnAt`, `RegionGrid.java:567–570`)
and `startFragmentByFlood`/`labelFragments`' `NavSection.getNavtype` reads. **Verdict: safe as-is
for in-job work; NOT safe for a hypothetical non-pool region thread (it would need its own stamp
slot).**

### 2.2 `CostPyramid` (`worldmodel/hpa/CostPyramid.java`) — **NOT worker-safe; already leaking**

Per-level plain arrays + open-addressed map, **no fences anywhere**. Mutators, all tick-thread:

- planning-path lazy builds: `RegionGrid.ensureLeaf`/`rebuildLeaf` **intern rows and set built
  flags** (`RegionGrid.java:193–228` → `Level.intern`/`newRow`/`growMap`/`growRows`,
  `CostPyramid.java:92–156`);
- maintenance: `HpaMaintenance.onChunkNavBuilt` (chunk-nav-build cadence, `HpaMaintenance.java:142–158`)
  and the debounced `flush` (≤8 leaves/level/tick, `:262–291`, wired at `OrebitCommon.java:107`),
  both via `buildLeafSafe` → `rebuildLeaf` + `PyramidMerger.mergeUpFragments`.

**A planner worker CAN read it mid-mutation TODAY**: `RegionCostField` carries a live `grid`
reference (`RegionCostField.java:87`), and `costAt` → `RegionPathfinder.fragmentOf` →
`grid.fragmentRecord` → `CostPyramid.rowIfPresent` runs 2–3× per block-A*-expansion **on the
worker** (the latent cross-thread read already flagged in `PERF-AUDIT-region-field.md` §3). Failure
modes: a reader that sees a new `mapMask` with a stale (smaller) `mapRow` array during `growMap`
(`CostPyramid.java:138–156` assigns the fields sequentially, no ordering) throws AIOOBE — caught
per-request by the worker (`completeRejected` → retry, `PlanExecutor.java:213–217`), so it degrades
rather than crashes — or silently reads wrong guidance (tolerated: the field is `max()`-combined
guidance). Low probability (pyramid mutation is chunk-load/edit cadence), but it is a standing
correctness hole and it **bounds how much more region reading can move to workers**.

**Safe pattern (proposed): (d)-style publication, not epochs.** Nothing in the pyramid is pooled
(rows/records are GC-managed), so NavReclaim-style deferred reclamation buys nothing; the need is
safe publication + torn-read immunity:

- wrap each `Level`'s `(mapKey, mapRow, mapMask)` in one immutable holder object swapped through a
  `volatile` field on grow (readers: ONE volatile read per lookup; grow is cold). Row arrays are
  append-only; publish `count` with a release write after the row data.
- Cost check: `rowIfPresent` is on `costAt`'s per-read path today — but **Phase 0 (§5) removes
  `costAt`'s pyramid escape entirely**, after which pyramid reads happen only at build/plan cadence
  (cold) and the volatile is free. Ordering matters: land Phase 0 first, then the publication fix
  never touches a hot path.

### 2.3 `RegionFragments` records — **NOT worker-safe: refilled IN PLACE**

`rebuildLeaf` gets the row's **existing** record (`CostPyramid.ensureFragments`,
`CostPyramid.java:257–265`) and `FragmentLeafComputer.computeLeaf` refills it in place; ancestor
merges do the same (`PyramidMerger.mergeUpFragments`). Any concurrent reader (worker `costAt`
today; worker field/skeleton builds tomorrow) can see a half-refilled record — torn
`fragmentCount`/footprints. **Safe pattern: swap-not-refill** — build into a fresh
`RegionFragments` (or a per-thread scratch record copied out) and publish the new instance into
`frags[row]` with a release write; old instances die by GC (no pool ⇒ no use-after-recycle, only
bounded staleness, which the optimistic-default semantics already tolerate). Cold path (leaf-build
cadence) — the extra allocation is one small record per rebuilt leaf.

### 2.4 `RegionGrid` itself — safe once 2.2/2.3 land, with one API split

`BY_LEVEL` is a `ConcurrentHashMap` (`RegionGrid.java:53`); the grid object is stateless beyond the
pyramid. The problem is that the planning read path **mutates**: `ensureLeaf` interns + builds
(`:193–199`). Worker-side region work must use a **read-only probe** (`rowIfPresent` + `isBuilt`,
unbuilt ⇒ the §6 optimistic default / nearest-centroid fallback — exactly the semantics an unloaded
chunk already gets). This is behaviorally near-invisible in production because `EAGER_BUILD=true`
(`HpaMaintenance.java:127`) keeps every loaded chunk's leaves built at chunk-nav-build time — the
lazy `ensureLeaf` on the planning path is a no-op fast path today. The delta (a leaf whose eager
build hasn't run yet reads optimistic-AIR on a worker where the tick path would have built it) is a
one-window-search staleness, absorbed by the next replan. **Owner decision §6.3.**

### 2.5 Blacklists + per-bot cascade state — tick-confined; keep them that way

- `RegionEdgeBlacklist` (`regionpathfinder/RegionEdgeBlacklist.java`): plain growable arrays,
  per-bot, mutated by `repairBlocked`/`onBlocked` on the tick thread. If skeleton planning moves to
  a worker, **snapshot** (copy the ≤handful of long pairs into the request) — pattern (b); never
  share the live object.
- `HierarchicalRegionPlan` (`regionpathfinder/HierarchicalRegionPlan.java`): per-bot mutable stack
  (`levels[].skeleton/committedIndex`, `failed`). Same rule: a worker builds a **fresh** cascade
  from an immutable snapshot and the tick thread adopts it whole-object at a boundary — pattern
  (b)/(c) hybrid. Sharing the live cascade with a worker while `repairStep`
  (`BotNavigator.java:629–667`) mutates it is a guaranteed race.
- `PathPlan.regionField`/`fieldRoot` cache (`PathPlan.java:148–152`): tick-confined; the field
  object itself is write-once (`RegionCostField.record` only from the producing Dijkstra) — already
  snapshot-safe into `SearchRequest` (`SearchRequest.java:32–33,40`).

### 2.6 ThreadLocal scratch — pool-friendly, no per-thread-state hazards found

All of it is per-thread and grow-once, matching the fixed-pool design (`PlanExecutor` class doc):

- `RegionPathfinder.SEARCH` / `FIELD_SEARCH` `Nodes` (`RegionPathfinder.java:213,220`) — the two
  are already split precisely so a field build can coexist with a region A* on one thread;
  `LAST_FIELD_STATS` (`:807`) is per-thread diagnostics.
- `RegionGrid.DIG_SCRATCH` (`RegionGrid.java:351`) — its own Javadoc already anticipates planner
  threads. Worst-case footprint ~290 KB/thread (64-slot slab pool × 4 KB + queue/visited).
- `FragmentBuilder.LABEL/QUEUE/FACE_*` (`FragmentBuilder.java:65–72`),
  `FragmentLeafComputer.STANDABLE/PASSABLE` (`FragmentLeafComputer.java:49–50`),
  `PyramidMerger.ITEM_*/ACC_*` (`PyramidMerger.java:76–86`), `RegionCostField.CENT/TMP`
  (`RegionCostField.java:81–82`).

One true non-thread-safe static in the neighborhood: `NavSectionBuilder`'s public `BlockState[]`
scratch — **not on any path this design moves** (fragment floods read `NavSection` navtypes, never
BlockStates). Confirmed no exposure.

---

## §3 Adoption seams

### 3.1 Phase 1 (field-on-worker) needs NO new adoption seam

The field is consumed **inside the same request** that builds it: the worker builds the field, then
runs `findPath` with it — the result flows back through the existing `PlanHandle` mailbox and the
boundary-gated `pollPending`/`pollWhenPlanless` drains (`PathPlan.java:869–893,935–940`;
`AsyncWindowSearch.drainPending`). What changes:

- `SearchRequest` carries **field-build inputs** (target root, botFloor, `RegionBox`, `regionMine`,
  `regionPlace`, the caps triple) instead of a pre-built `field` — all immutable value objects.
- `PlanHandle.complete` additionally returns the **built field**, so the tick thread can adopt it
  into the `regionField`/`fieldRoot` cache — preserving today's one-field-per-root reuse (the trace
  path and subsequent same-root submits stay cheap, and same-root searches keep reading ONE field
  instance as they do now). A request whose root matches the cached field skips the worker-side
  build entirely (the snapshot carries the cached field, exactly today's shape) — so the worker
  build runs only where the tick thread pays the Dijkstra today. **Owner decision §6.4.**
- Sync mode (`executor == null`) is untouched — field still built tick-side in `regionFieldFor` —
  preserving the byte-identical-sync contract.

### 3.2 Staleness gating

- Existing gates carry over unchanged: seam-accept on the start cell + window-target equality
  (`AsyncWindowSearch.java:133–135`), `blockedGeneration` one-repair-per-result
  (`PathPlan.java:796–824`; `BotNavigator.java:653–666`), the terrain-recheck
  `NavGridUpdater.editEpoch` debounce (`BotNavigator.java:353–357`; `NavGridUpdater.java:38–54`).
- The field has no epoch today — a cached field can be stale across edits until the root moves.
  Phase 1 does not change that (documented, guidance-only). If wanted later: invalidate `fieldRoot`
  when `editEpoch` advances — one int compare at submit; **not** proposed now (it would rebuild the
  field on every edit anywhere in the level, the coarseness documented at `NavGridUpdater.java:33–37`).

### 3.3 Late SKELETON adoption (Phase 2 only — what actually gets hard)

If the cascade/skeleton build moves off-thread, the PathPlan ctor can no longer produce
`skeleton`+window target synchronously. Consequences:

- A new **SKELETON-PENDING** state: the driver waits planless (consistent with the s52 rule — a
  planless driver WAITS; `pollWhenPlanless` already covers plan adoption for never-settling bots).
  `WindowTargeting` cannot run until the skeleton arrives, so the first window search is serialized
  behind the skeleton round-trip (+1 boundary of latency on every fresh goal).
- Adoption gate: bot-floor seam (Chebyshev, reuse `SpliceSeam`) + goal unchanged + **the plan's
  `blockedGeneration` unchanged since submit** (a repair that fired mid-flight invalidates the
  in-flight skeleton — see §4.2).
- The worker builds a **fresh** `HierarchicalRegionPlan` from a snapshot (goal, caps, mine model,
  copied blacklists); the tick thread swaps it whole-object. `repairBlocked`'s escalation would need
  the same submit/adopt round-trip (today it re-plans inline at `PathPlan.java:988–1007`).
- `WindowTargeting` stays tick-side (it is µs-cheap and reads the LIVE `NavGridView` at
  `WindowTargeting.java:112` — moving it to a worker forces `background()` semantics, changing
  snap/dig decisions in the unbuilt fringe). No reason to move it.

### 3.4 The `/bot config reload` drain

`ConfigLoader.install` drains the pool before rebaking `NavBlock`/`MiningModel`
(`ConfigLoader.java:84–107`). Region work moved **inside pool jobs** is covered by that same
`drainIdle` automatically — no new drain needed. Two follow-ups:

- worker-side field builds read `RegionMineModel`/`RegionPlaceModel` — immutable per-plan
  snapshots, no reload coupling.
- if any variant introduces a **dedicated region thread** (§2.1's caveat, pattern (c)), it must
  join `drainIdle` AND get an `activeSince` slot for NavReclaim. This is a standing argument for
  keeping everything on the ONE pool.

---

## §4 Ordering / consistency hazards

1. **Field built from an older pyramid than the NavGrid the block search reads.** Exists TODAY
   (field built at tick-side submit; the worker search runs later against live `NavStore`).
   Tolerated by design — the field is `max()`-combined guidance and "plans are hints". Phase 1
   *shrinks* the skew (build and search run back-to-back on the same thread against the same
   store). Document-and-accept, same class as s44 §4.3.
2. **Cascade repair racing a late skeleton (Phase 2 only).** `repairBlocked` blacklists + re-plans
   inline while a stale skeleton result is in flight; adopting the stale skeleton would resurrect
   the blacklisted hop. Gate adoption on `blockedGeneration` (and on goal identity), mirroring the
   window-target-moved drop in `AsyncWindowSearch.pollParked` (`:149–158`).
3. **Maintenance flush racing a worker field/skeleton build.** `HpaMaintenance.flush` refills
   `RegionFragments` in place and grows pyramid tables while a worker reads them — the §2.2/§2.3
   torn-read class. TODAY's exposure is only `costAt`; Phase 1 **widens** it to
   `rowIfPresent`/`fragmentRecord` loops, `goalDigSeeds`, and `startFragment` floods — so the
   publication fixes (§2.2 volatile holder, §2.3 swap-not-refill) are **prerequisites**, not
   nice-to-haves. NavSection reads inside jobs are already epoch-safe (§2.1).
4. **Gather's 4 sync challenge searches** stay on the tick thread (HANDOFF veto). They carry no
   field build, so this design neither helps nor hurts them; noting so the "100% planning
   off-thread" claim is honest — they are the remaining known sync searches.
5. **Multi-bot contention (V2, 50+ bots).** Per-bot latest-wins bounds queue depth ≈ bot count
   (`PlanExecutor.QUEUE_CAPACITY = 256`, `PlanExecutor.java:48`). Folding the field build into the
   search job adds 0.1–1.4 ms to worker occupancy per window-target move — at 50 bots × 2 threads
   that is latency, not correctness (results adopt a boundary or two later; the driver already
   tolerates that). Sizing guidance: keep `pathing.maxThreads` default 2 for V1; V2 revisits the
   default (cores−2 clamp already exists). No new fairness machinery proposed — one FIFO pool, the
   field build riding inside the job it serves, keeps a single starvation surface. Pre-plans
   already yield to boundary replans via the existing supersede rules.

---

## §5 The lift, phased (smallest safe first)

### Phase 0 — make `RegionCostField` self-contained (enabler + standalone correctness fix)

- **What moves:** nothing off-thread. Bake per-slot fragment centroids (+count) into the field at
  build time so `costAt` never dereferences `grid`/`CostPyramid` (PERF-AUDIT-region-field §5 P1;
  drop the `grid` field at `RegionCostField.java:87`).
- **Why first:** closes TODAY's cross-thread read (§2.2) — the one place planner workers already
  touch the mutable pyramid — and removes the only pyramid read on a hot path, so §2.2's volatile
  holder never costs anything measurable.
- **State pattern:** (b) immutable snapshot — the field becomes fully self-contained.
- **Savings:** none on the tick thread (it is a read-path fix); est. 60–80% off `costAt` per the
  audit.
- **Risk:** medium — the audit's fallback-folding semantic delta needs ratification.
- **Gates:** FullSearchBenchmark + pinned SHORT/MULTI flat; the full-pipeline byte-identity pins
  (`FullSearchHeadlessTest`); `RegionFieldFatSkeletonTest`; a new unit asserting `costAt` answers
  match the pre-bake resolver on the fixtures.

### Phase 1 — field build inside the EXISTING async window-search job (the recommended lift)

- **What moves:** `costToGoalField` + its dig-flood seed, from `PathPlan.regionFieldFor` (tick) to
  the worker, inside the same `SearchRequest` execution (§3.1). The forward-plan dig-flood
  (`buildDigSeeds`) and all skeleton work stay tick-side.
- **State patterns required (in order):**
  1. §2.3 swap-not-refill for `RegionFragments` (release-publish fresh records);
  2. §2.2 volatile-holder publication for `CostPyramid.Level` map tables + released `count`;
  3. §2.4 read-only leaf probe for workers (no `ensureLeaf` intern on the worker; unbuilt ⇒
     optimistic default — leaning on `EAGER_BUILD`);
  4. request/handle plumbing per §3.1 (field inputs in, built field out, root-keyed cache kept).
- **What stays:** `WindowTargeting`, cascade build/step/repair, maintenance, sync mode
  (byte-identical), the trace path.
- **Tick-thread savings:** **0.1–1.4 ms per window-target move per bot** — 90–99% of the remaining
  region-tier planning bill (§1.5). This is the entire practical win of the owner's goal.
- **Risk:** medium. New failure surface = worker-side pyramid reads racing maintenance (bounded by
  the publication work; a torn read after it is impossible, a stale read is one window of
  staleness); behavior delta = the read-only-probe optimism (§2.4) and the field being built a few
  ms later than the tick-side build would have been (same class as the existing submit-time skew).
- **Gates:** sync-mode byte-identity (full suite incl. `FullSearchHeadlessTest` pins);
  a NEW concurrency stress test (a flush/rebuild loop racing worker field builds over a shared
  headless grid — assert no exception, values sane); `RegionFieldBuildBenchmark` A/B flat (the
  publication overhead must not move it); pinned SHORT/MULTI flat (nothing on the block hot path
  changes); in-game: tick-time capture on the s44 complex-path scenario (expect the residual
  8→3 ms-class replan spikes to flatten further).

### Phase 2 — skeleton/cascade planning off-thread (recommend: DEFER)

- **What would move:** `HierarchicalRegionPlan` build + `rederiveSuffix` + `onBlocked` escalation,
  as fresh-cascade-per-request jobs with whole-object adoption (§3.3), `blockedGeneration`-gated.
- **Savings:** ~10–70 µs per replan (§1.1/§1.3 measured) — under the measurement floor of a tick.
- **Cost:** a new SKELETON-PENDING driver state, +1 boundary of latency on every fresh goal and
  every repair, blacklist snapshotting, and a second adoption seam to keep correct forever.
- **Verdict to ratify:** after Phases 0–1 the tick thread's remaining planning is tens of µs —
  cheaper than the machinery required to move it. Recommend deferring until a profile shows
  skeleton work on a tick flame graph (the one plausible spike: a cold-terrain `planWithin` paying
  many lazy `ensureLeaf` leaf floods — mitigated already by `EAGER_BUILD`). The owner's "100% of
  planning" goal is then met in effect, not in letter — flagging that honestly. **Owner decision §6.5.**

### Phase 3 — V2 multi-bot sizing (with the multibot arc, not now)

Revisit `pathing.maxThreads` default; measure a 50-bot replan wave before adding any fairness
machinery (§4.5). No design change proposed.

---

## §6 Owner decisions (ratify item-by-item)

1. **Phase 0** (self-contained field / centroid bake) — includes the audit's fallback-folding
   semantic delta. Also independently a perf item (P1 of PERF-AUDIT-region-field).
2. **Publication mechanism** for `CostPyramid.Level`: volatile tables-holder (proposed) vs
   VarHandle acquire/release on the existing fields vs "single-writer + drain-around-mutation".
   The holder is the smallest reviewable diff and is provably torn-read-free.
3. **Read-only leaf probe on workers** (§2.4): accept the unbuilt-leaf optimism delta (one window
   of staleness on not-yet-eager-built leaves)?
4. **Field cache policy** (§3.1): keep root-keyed reuse with the worker-built field adopted back
   (proposed — preserves one-field-per-root semantics), vs rebuild-per-request (simpler plumbing,
   but same-root searches could read different field instances across maintenance mutations — a
   behavior change).
5. **Phase 2 deferral**: ratify "skeleton stays tick-side for now" or override (then §3.3's
   SKELETON-PENDING design gets its own doc before any code).
6. **Scope confirmation**: maintenance (`HpaMaintenance`, `ChunkNavLoader`, `NavReclaim` drains)
   stays tick-side; gather's 4 sync challenge searches stay veto'd-sync (tracked separately).

## §7 Recommendation

Phase 0 → Phase 1, in that order, as two separately-gated arcs; defer Phase 2. Phase 1 removes the
only millisecond-class planning item left on the tick thread (the field build) using the pool,
snapshot, and drain machinery s44 already proved, at the price of two cold-path publication fixes
the codebase arguably owes itself anyway — §2.2/§2.3 are a live (if low-probability) cross-thread
correctness hole TODAY via `RegionCostField.costAt`, independent of any new async work.
