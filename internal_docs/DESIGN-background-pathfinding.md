# DESIGN — Background-thread pathfinding + time-based cap + pre-plan/splice

> **STATUS (2026-07-03): IMPLEMENTED (P0–P4, same day) — awaiting OWNER IN-GAME VERIFY, then commit.
> Owner amendment folded in: multi-thread from day one (`pathing.maxThreads` pool, §3.1); NavStore
> guard = epoch-based reclamation, tick thread sole owner/recycler (§4.1). Gates: 26.2 compile +
> chiseledCompileCommon all 28 + 157 tests / 0 failures (20 new). A 32-agent adversarial review
> confirmed 27 findings — 8 fixed in code (drainIdle counter-pair, parked-plan invalidation,
> rejected-vs-BLOCKED result kinds, time-mode node backstop [the config node cap otherwise bound
> BEFORE the 40 ms budget], deadline-test reorder, stale-singleton config gate, wantsPreplan arg
> gate, planless tick-rate adoption), the rest accepted-by-design and documented in place. JMH A/B
> (interleaved ×3, full suite, AC power): NEUTRAL — SHORT −0.5% / MULTI −0.2% / FLOOD −0.5% /
> worst mean CLIFFS +1.4% with sign-flipping pairs; the per-pop additions measure as free.
> `pathing.async` defaults FALSE; sync path byte-identical.**
> HANDOFF "NEXT ARC (owner-chosen)". Companion reading: `DESIGN-portal-route-layer.md` §4 (the
> SpliceSeam contract this arc is consumer B of), `PERF-PROFILE-2026-07.md` (the ns/node numbers the
> time budget is sized from), the `baritone-pathing-architecture` memory (the model: time-boxed +
> best-so-far + async segmented plan-ahead/splice), and the `path-splice-primitive` memory (the
> owner-ratified PathEdits-carry rule).

---

## 1. Problem & scope

Every search today runs **synchronously on the server tick thread**: `AllyBotEntity.driveToward` →
`PathPlan.replanBlock` → `BlockPathfinder.findPath` (PathPlan.java:732). A warm window search costs
0.1–50 ms depending on shape (floods hit the 10k-node cap at ~400–950 ns/node ≈ 4–10 ms; edit-heavy
pillar shapes ~2,200 ns/node ≈ 16+ ms; a fresh long-range `PathPlan` construction adds the cascade
build on top). That is tick jank today, and it hard-caps how much search we can afford: raising the
node budget to escape a local minimum means stealing more of the 50 ms tick.

Three changes, in dependency order:

1. **Background execution**: run block-tier searches on a small planner pool
   (`pathing.maxThreads`); the tick thread submits a request and adopts the result at the boundary
   it already gates plan swaps on. Sized for the real deployment: a public server with ~20 players
   × one bot each, not just the dev single-bot case.
2. **Time-based cap**: replace the node cap with a wall-clock deadline as the *live* budget
   (Baritone's model: `primaryTimeout`/`failureTimeout`, never node-capped). ~1,500 ns/node warm →
   a 150 ms off-tick budget buys ~100k nodes, 10× today's cap, without touching a tick. Window
   space is exhaustible, so bigger budgets turn partials into definitive answers.
3. **Pre-plan + splice**: while walking the current window plan, compute the NEXT window's plan
   from its predicted start on the planner thread, and adopt it at the settled boundary via the
   SpliceSeam contract — no boundary pause at all. This *exercises* the eager mode of the splice
   primitive (baseline seeding), which the portal route layer then consumes unchanged.

Non-goals: moving the **region tier** off-thread (its `CostPyramid` open-addressed map and lazy
`ensureLeaf` builds are unsynchronized read-triggers-write — tick-confined it stays correct with
zero changes; §4.5); more than one search thread; portal legs (own arc); any change to executor
(BotMining/applyEdits/PhaseRunner) threading.

---

## 2. What exists (the parts that make this cheap)

| Piece | Where | Why it matters here |
|---|---|---|
| ThreadLocal search state | `BlockPathfinder.SEARCH`/`EDIT_POOL` (BlockPathfinder.java:218/253), all `NavSectionBuilder`/fragment scratch | The planner thread gets fully isolated search state for free — zero sharing with the tick thread's scratch |
| Concurrent chunk store | `NavStore.BY_LEVEL` = per-level `ConcurrentHashMap<Long, NavSection[]>` (NavStore.java:37) | Structure-safe cross-thread lookup already; only the *recycling* of values is unsafe (§4.1) |
| No-live-level view precedent | `NavGridView` synthetic ctor: `level == null` → out-of-built `descriptorAt` returns AIR (NavGridView.java:171) | The search provably runs against nav data alone; the bg view reuses this exact contract (§4.2) |
| Boundary-gated commit | `driveToward` acts only at `blockPosition().below() == settledFloor` (AllyBotEntity.java:700) | The adoption point — async results and spliced plans swap in at the same gate plans swap at today |
| Best-so-far partial return | `PARTIAL_PATH` + `lastReversibleRow` truncation (BlockPathfinder.java:121/681–698) | The time cap's "budget hit" path already exists and is in-game-proven; only the trigger changes |
| SpliceSeam contract | `DESIGN-portal-route-layer.md` §4: EditSnapshot baseline, seed→accept→adopt, per-pop `addSnapshot` | The splice primitive is designed once, there; this arc builds it (P0) and is its first eager consumer |
| Boot warm-up | `NavWarmup` (synchronous, SERVER_STARTED) | Re-aimed at the planner thread (§4.6) so its ThreadLocal scratch is grown and the submit/result pipe is exercised before the first real search |
| Timing instrumentation | `LOG_TIMING` (BlockPathfinder.java:53), `LAST_EXPANSIONS`/`LAST_WAS_PARTIAL` | Same numbers, now attributable per-thread |

## 3. Threading model

### 3.1 A fixed planner pool (`pathing.maxThreads`), latest-wins handoff

A fixed pool of daemon threads (`orebit-planner-N`), owned by a small `PlanExecutor`
(new class, `pathfinding/async/`): a one-slot-per-bot **latest-wins** inbox (a newer request for the
same bot replaces an unstarted older one; at most ONE in-flight search per bot), a result queue
drained on the tick thread. Pool sizing is an admin knob — `pathing.maxThreads`, default **2**,
clamped to `[1, availableProcessors − 2]` at load: a multi-tenant server (20 players × one bot)
must not serialize every bot's search behind one core, and admins tune bot CPU like they tune
view-distance. Two threads already make queueing negligible (searches are ms-scale; bots replan at
boundaries, seconds apart — aggregate demand is a few searches/sec even at 20 bots; the knob is
for tail latency under simultaneous long searches, not throughput).

Threads are *fixed at pool construction* (no grow/shrink): each pool thread's ThreadLocal
`Nodes`/`EditPool` grows once and serves every search on that thread forever — the same
zero-steady-state-alloc regime as today, times N. Per-search state never crosses threads (a search
runs start-to-finish on one thread); the only cross-thread structures are the inbox/result queues
and the world model reads §4 makes safe. Mutation of the world model remains **single-owner on the
tick thread** — N searcher threads are N *readers*, which is exactly the shape §4.1's reclamation
guard handles.

### 3.2 The request/result protocol

```java
// pathfinding/async/SearchRequest.java — immutable snapshot, built on the tick thread at submit
final class SearchRequest {
    final ServerLevel level;          // identity only: resolves NavStore.chunksOf(level) at start
    final BlockPos startFloor;        // = SpliceSeam.predictedStartFloor
    final BlockPos target;            // windowTarget() output (computed on tick thread as today)
    final BotCaps caps;               // immutable record already
    final InventorySnapshot inventory;// COPY of the bot's live inventory (§10.1) — never the live one
    final int startMode;
    final RegionBound cuboidCap;      // value box, immutable
    final EditSnapshot baseline;      // null until P4 (eager pre-plan); splice-doc §4.3 semantics
    final long generation;            // bot's plan generation at submit; stale results are dropped
    final long deadlineNanos;         // 0 = node-cap mode (§6)
}
// Result: {BlockPathPlan plan | null, boolean partial, int expansions, long elapsedNanos, generation}
```

Submission and result-drain both happen on the tick thread; the queues give the happens-before
edge, so **the search sees the world as of no earlier than submit time**. Mid-flight world changes
may or may not be visible (§4.3) — the contract is the one the executor already enforces: plans are
hints, validated at adoption (seam acceptance) and execution (BLOCKED repair, stall hatches,
`mayBreak` backstop, `applyEdits` reading the real world).

Bot despawn / level change / `clearPlan` bumps the generation; in-flight results for old
generations are discarded on drain. A planner-thread exception is caught per-request (result =
FAILED + logged with stack); the thread never dies with the search.

## 4. Memory safety — the specific fixes (audited surface)

The full shared-state audit (this session) found exactly one crash-class hazard, one contract
change, and three document-and-accept items. Everything else the block search reads is immutable
after boot (`MovementRegistry`, `NavBlock` descriptor table modulo §4.4, `MiningModel` tables
modulo §4.4, `Config`/`BotCaps` records).

### 4.1 NavSection use-after-recycle — THE hazard (fix: retirement grace)

`NavStore.put/remove/clear` (NavStore.java:45/67/78) recycle displaced `NavSection`s **immediately**
into `NavSectionPool` (an unsynchronized `ArrayDeque`, NavSectionPool.java:10); the next chunk build
pops one, `reset()`s (zero-fills) and refills it **for a different chunk**. A planner-thread
`NavGridView` whose chunk cache still holds the old `NavSection[]` would read another chunk's cells
as this chunk's — not stale data, *garbage* data, silently insane paths.

Fix — **deferred retirement** (epoch-based reclamation), tick-side reclaim, pool untouched.
Mutation ownership doesn't change: the tick thread is *already* the sole writer/recycler of
NavStore and the pool; searcher threads are pure readers and never touch either. The guard is
purely about WHEN the owner may reclaim — the owner waits out every reader that could still hold a
retired section:

- A global `volatile long epoch`, bumped by the tick thread once per tick (or per retirement —
  either works; per-tick is simplest).
- Each pool thread owns one slot in a fixed `volatile`-published `long[maxThreads]`
  (`activeSince`): at search start it writes the current epoch, at search end it writes IDLE
  (`Long.MAX_VALUE`). Two volatile writes per search, nothing per-node, no CAS, no contention
  (each thread writes only its own slot).
- `NavStore` retires displaced sections into a grace list tagged with the retirement epoch instead
  of recycling inline. Once per tick, the tick thread computes `minActive = min(activeSince[])`
  and drains every batch with `retireEpoch < minActive` into `NavSectionPool.recycle` as today.
  No search that started before the retirement can still be running when its batch drains. With
  ms-scale searches the grace is typically a tick or two; the pool, `ArrayDeque` and all, stays
  tick-thread-confined.
- `NavStore.clear(level)` (level unload) takes the same path.

Cost: one deque append per displaced chunk (chunk churn is rare), one N-element min-scan per tick.
Nothing on any per-read or per-pop path. (The rejected alternative — routing section *access*
through an owner thread — would put a message hop on the per-read path: a search reads sections
millions of times; the read path must stay direct-array. Only *reclamation* needs coordination.)

### 4.2 No live-level fallback on the planner thread (contract change, conservative)

The ONE live-`ServerLevel` read in the whole search is `NavGridView.descriptorAt`'s out-of-built
fallback (`level.getBlockState`, NavGridView.java:174). Vanilla chunk access is not thread-safe;
the bg view must not take it. The bg `NavGridView` is constructed in the synthetic ctor's contract
(`level == null` path): **out-of-built descriptor probes return AIR**.

Behavior delta: only ungated descriptor probes just past the loaded radius (e.g. `Ascend`'s
place-footing collision check at the frontier — `packedAt`, the gated read, already reports
`UNBUILT` with no fallback). AIR is passable-not-standable, so the search stays walled inside built
nav data — the same wall the gated reads already enforce. Direction of error: the bot plans less at
the frontier, never through phantom terrain. Sync mode (`pathing.async=false`) keeps the live
fallback byte-identically.

### 4.3 In-place patches vs. a concurrent reader (document-and-accept)

`NavGridUpdater` → `patchCell` mutates grid `short[]`/`byte[]` in place on the tick thread
(NavSectionBuilder.java:445/500/540) while a planner search may be reading them. Per JLS §17.6
element reads/writes of `short[]`/`byte[]` cannot tear — a racing read sees the old or the new
value of that cell, never garbage. A mid-patch view (some of a patch's ≤15 cells old, some new) is
an *inconsistent-but-cell-wise-valid* world: strictly the stale-world class of error the pipeline
already absorbs (seam acceptance, BLOCKED repair, executor backstops). **No locks, no volatiles on
grid arrays** — any per-read synchronization is exactly what the performance model forbids.

### 4.4 Cold rebakes: drain the planner instead of touching hot reads

`NavBlock.applyProtected` (config reload) mutates `descriptors[]`/`STATE_TO_NAVTYPE` with
synchronization on the write side only; `MiningModel.buildTable` (reload) republishes tables. Rather
than make `NavBlock.descriptor()`'s array read volatile (a hot-path change), **`/bot config reload`
drains the planner pool first**: cancel the inboxes, wait until every `activeSince` slot reads IDLE
(bounded by the §6 deadline), rebake, resume. Reload is a cold owner command; the hot path is
untouched. (Same drain primitive serves server shutdown, §10.6.)

### 4.5 Region tier stays tick-confined (verified, zero changes)

The block search's read set is: NavStore sections, `NavBlock` descriptors, `MiningModel` tables,
the request's own value objects. It never touches `RegionGrid`/`CostPyramid`/`RegionFragments`.
All region-tier work — `HierarchicalRegionPlan.build` (PathPlan ctor), `stepCascade`,
`windowTarget()`, `repairBlocked`, `HpaMaintenance.flush`, lazy `ensureLeaf` — remains on the tick
thread, so the pyramid's unsynchronized `intern`/`growMap`/fragment rebuilds keep their existing
single-thread correctness. (Offloading a heavy fresh cascade build is a possible follow-on; it
requires pyramid locking and is out of scope. The cascade has measured cheap so far.)

### 4.6 Warm-up: NavWarmup stays on the tick thread; pool threads self-warm (amended at implementation)

The original plan (route `NavWarmup` through the async pipe so every pool thread runs a share) was
dropped at implementation: NavWarmup's fixture is built from the **tick-thread-confined
`NavSectionPool`**, so running it on pool threads would itself create the §4.1 race class it exists
to avoid, and refactoring it to a pool-free fixture buys almost nothing — JIT warmth is JVM-global
(the tick-thread warm-up already provides it), so the only per-thread cost is ThreadLocal scratch
growth (~1 ms, and it happens OFF-tick on a planner thread where latency is free by construction).
What shipped instead: each pool thread calls `BlockPathfinder.warmThreadScratch()` as it starts
(sizes its `Nodes`/`EditPool`/instrumentation ThreadLocals), and the pool starts AFTER the
tick-thread NavWarmup so submitted searches run against warm JIT. The submit/complete pipe needs no
separate boot exercise — its first use is observable and failure-isolated per request.

## 5. Async PathPlan — the one seam that changes

`PathPlan.replanBlock()` (PathPlan.java:702) is the single place searches are launched. Behind
`pathing.async` (default **false** until verified — the portalRoutes pattern):

- **Submit instead of compute**: `replanBlock` builds the `SearchRequest` (windowTarget, cuboidCap,
  snapshot inventory — all on the tick thread, as today) and submits. The **current `blockPlan`
  stays live** — the follower keeps walking it. A new `PENDING` niche is tracked internally
  (in-flight flag + generation), distinguishing "search in flight" from BLOCKED (which today is
  signaled by a null return).
- **Drain + adopt**: `onBotMoved`/`refreshWindow` (already called per tick at the boundary,
  AllyBotEntity.java:701/704) poll the result queue. A result is adopted only if (a) its generation
  is current and (b) `seam.accepts(actualFloor)` — the request's `startFloor` is the seam's
  predicted start, tolerance `REPLAN_NEAR_TARGET = 3`. Adoption = the existing window-swap
  mechanics verbatim (`lastBlockPlanRef` identity swap → follower resets `waypointIndex`/
  `lastEditedIndex`/`activePlanStep`, re-anchors `planStartFloor`). Rejected (bot drifted >3 cells
  since submit): drop the result, resubmit from the actual floor — the same recovery the escape
  hatches use today.
- **First plan** (PathPlan ctor): status `RUNNING` with a null window plan for 1–3 ticks; the
  follower already tolerates a null plan (holds/straight-line degrade). The bot visibly starts
  moving one or two ticks later than today — accepted (and erased by P4's pre-plan for every
  subsequent window).
- **Null result** (search exhausted budget with no progress): maps to BLOCKED exactly as today →
  `repairBlocked` cascade path, unchanged.
- `pathing.async=false` → `replanBlock` calls `findPath` inline, byte-identical to today. One flag
  test at one cold call site.

`/bot trace` stays synchronous on the tick thread by design (it is a diagnostic; it also wants the
live-level fallback).

## 6. Time-based cap

New request field `deadlineNanos`; in the A\* loop the budget check becomes:

```java
if ((expansions & 255) == 0 && deadline != 0 && System.nanoTime() > deadline) { budgetHit = true; break; }
if (++expansions > maxNodes) { budgetHit = true; break; }   // retained: memory backstop + bench mode
```

- Checked every 256 pops: at ~1 µs/pop that is ~0.25 ms granularity — far finer than needed; the
  per-pop cost is one mask+branch (predictable, the transit-bit pattern) and a `nanoTime` call
  1/256 pops. **Full JMH A/B required anyway** (per-pop loop change; expected ≈ noise, revert rule
  applies).
- `maxNodes` is **kept**, repurposed: (a) the memory backstop (a time cap alone on a fast machine
  could grow the Nodes SoA unboundedly; cap it at e.g. 256k rows ≈ ~10 MB — favor-cpu-over-ram says
  that's fine, but bounded, and it is now **per pool thread**: `maxThreads=4` ⇒ ~40 MB worst-case
  resident scratch, priced into the knob's docs), (b) the determinism mode for benchmarks and unit
  tests (deadline 0 = node-cap only = today's exact behavior, so JMH stays timing-independent and
  byte-identical).
- Defaults: `pathing.searchBudgetMs = 40` per window search (≈ 25k–100k pops warm — 4–10× today's
  10k cap; window space is exhaustible, so most window searches will complete or *prove* partial
  well under it). Async mode only — in sync mode a 40 ms budget would be worse tick jank than
  today's node cap, so sync keeps node-cap semantics untouched.
- Determinism stance (design-principles "prefer determinism"): a **completed** search under
  deadline is byte-identical to an uncapped one — the deadline only decides *where a partial
  truncates*, and partials are replanned at the next boundary by construction. The
  nondeterminism is confined to the already-nondeterministic case (budget exhaustion), and the
  deterministic node-cap mode remains for every test/bench surface.

## 7. Pre-plan + splice (the eager consumer)

Once §5's plumbing exists, pre-planning is a *submit-earlier* policy plus the splice P0 machinery:

- **Trigger** (in `driveToward`'s boundary block, cold): when the current window plan is more than
  ~half consumed (`waypointIndex > path.size()/2`) — or `blockRefreshTicks` is about to expire —
  and no request is in flight, submit the NEXT search early: start = the **predicted** boundary
  cell (the current plan's final waypoint floor — reliable, it's where the follower is steering),
  window advanced as `refreshWindow` would, `baseline` = the `EditSnapshot` of the current plan's
  **remaining unapplied** edits (splice doc §4.2's eager regime: fold remaining steps' StepEdits,
  latest-wins; tens of longs, one cold allocation).
- **Adopt**: at the settled boundary, `seam.accepts(actualFloor)` (tolerance 3) → adopt as §5;
  reject → discard, replan from actual floor (= today's path, nothing lost).
- The baseline mechanics are built exactly per splice-doc §4.3 (per-pop `addSnapshot` appended
  after the cameFrom-chain walk, `null` → one compare) so the portal route layer later consumes
  the identical primitive. Its byte-identity (null-baseline) and seeding unit tests land in P0.

Result: the boundary pause (today: a synchronous window search at every commit) disappears —
Baritone's "no boundary pause" splice, on our window/boundary machinery.

**Scope note (implementation).** The shipped P4 pre-plans the CURRENT window's next search (the
periodic refresh / plan-consumed boundary) from the plan's predicted end cell; a pre-plan result
whose seam rejects (or whose window target moved while walking) is PARKED and re-tested at each
boundary rather than resubmitted, so a mid-window early result never churns. Pre-computing across a
window SLIDE (predicting the post-commit window and its target) needs the window machinery to
expose "target as-if the bot were at X" and is deferred — the adoption guard (`windowTarget`
unchanged + seam accept) makes the shipped form safe, and the slide case simply falls back to
today's boundary search.

## 8. Perf accounting (what the protocol must measure)

- **The win**: every `replanBlock` search (0.1–50 ms) leaves the tick thread. Measure in-game
  tick-time (spark/`LOG_TIMING` per-thread) before/after on the cave-descent and long-goto repros.
- **New costs**: request/result queue hop (µs-scale, per search), inventory snapshot copy (tens of
  bytes, per search), 2 volatile epoch-slot writes per search, grace-list drain + N-element
  min-scan (per tick, trivial), ~10 MB ThreadLocal scratch per pool thread at the maxNodes
  backstop.
  Per-pop: the §6 deadline mask+branch — **full JMH suite A/B incl. SHORT/MULTI + PatchStorm**,
  expected noise, revert-without-sentiment.
- **Latency regression**: plan arrival now lags submit by 1–3 ticks (result drained at the next
  boundary poll). The follower walks the old plan meanwhile (by design); the only visible case is
  the FIRST plan after a command (~2 ticks standing still), erased for subsequent windows by P4.
- **JMH/unit surfaces unchanged**: benches call `findPath` directly, sync, node-capped,
  null-baseline — byte-identical path required and asserted.

## 9. Phasing (each phase gated on the standard 28-version + test + in-game recipe)

- **P0 — splice primitive, headless** (shared with portal-arc P1, built once here):
  `EditSnapshot`, `SpliceSeam`, `baseline` param through `findPath`, per-pop `addSnapshot`.
  Unit: null-baseline byte-identity; seeded BROKEN-reads-as-air / PLACED-as-floor; latest-wins
  shadowing. JMH A/B for the one per-pop null compare.
- **P1 — safety groundwork, no behavior change**: NavStore retirement grace (+ stamp), bg-view
  no-live-fallback mode (unused yet), drain-on-reload hook, planner-thread skeleton + warmup
  rerouting (thread exists, nothing submits real work). Unit: grace-queue retire/drain under a
  simulated in-flight stamp.
- **P2 — async replanBlock** behind `pathing.async=false`: submit/drain/adopt/generation, PENDING
  niche, first-plan latency. In-game (26.2 + one mc-1.21 spot check): follow/come/goto/mine parity
  vs sync, terrain-edit-during-search, bot despawn mid-search, `/bot config reload` drain.
- **P3 — time cap** (`pathing.searchBudgetMs`, async only): deadline plumbing + partial semantics
  verify (deep cave / long goto: partials become definitive answers, no infinite-replan loops).
- **P4 — pre-plan + eager splice**: early submit + baseline seed + seam adoption. In-game: no
  boundary pause on a long goto; mid-walk terrain edits near the seam; reject-path sanity.
- **Flip `pathing.async` default true** after P2–P4 soak; sync path retained as the fallback knob.

## 10. Risks / open questions

1. **InventoryView liveness**: the search prices placements from the bot's REAL inventory (Phase
   1b/1c) which mutates on the tick thread (pickups, mining drops). The request must carry a cheap
   snapshot (counts by placeable class). Audit `InventoryView`'s current shape at P2 — if it's
   already a per-replan snapshot, nothing to do.
2. **Frontier behavior delta** (§4.2): AIR fallback vs live read past the loaded radius. Expected
   invisible (bots anchor chunk loading around themselves); verify a frontier goto in P2.
3. **Unload during flight**: a search may read grace-held sections of a just-unloaded chunk and
   path into it; existing unloaded-radius degrade + replan handles. Accept.
4. **Region tier stays sync**: a fresh long-range cascade build still runs on-tick (measured cheap
   so far). If it ever spikes, offloading it is a separate design (pyramid locking).
5. **Pool sizing** (`pathing.maxThreads`, default 2, clamp `[1, cores − 2]`): correctness is
   thread-count-independent (N readers, one writer, per-search state confined to one thread);
   the knob trades bot search tail-latency against server CPU headroom, like view-distance.
   Multi-tenant servers (20 players × 1 bot) raise it; potato hosts drop it to 1.
6. **Shutdown**: daemon thread + drain-on-server-stop so a mid-search shutdown can't touch a
   closing level. (The bg search touches no level objects after §4.2 — the drain is belt-and-
   suspenders.)
7. **`LAST_EXPANSIONS`/`LAST_WAS_PARTIAL` statics** are read by the region tier for cost
   attribution on the tick thread; in async mode they'd race. Move them into the result record;
   keep the statics for sync/bench mode.
