# DESIGN — The Rolling Skeleton: slide-and-extend at every cascade level

**Status: DRAFT for ratification (2026-07-23).** Owner-directed design; mechanism ratified in
substance (see §1), the specific calls in §15 still open. Companion forensics:
`PATHOLOGY-window-slide-cadence.md` (scratchpad, 2026-07-23) — the verified trigger inventory and
log classification every claim below cites. All `file:line` cites are the `orebit-mc121-wt`
worktree at the current uncommitted stack.

This is a **BEHAVIOR change to routing cadence**, not a search change: every individual region
search and block search is the same algorithm with the same per-search semantics; what changes is
*when* they run and *what cell they aim at*. The JMH search benchmarks are unaffected by
construction (they drive `BlockPathfinder.findPath` / the region planner directly — same searches,
different scheduling); the battery and journey metrics are the oracle (§12).

---

## §1 Motivation — the owner's ratified model, and the measured pathology it fixes

The owner's model (ratified 2026-07-23, quoted in substance):

> Slide the window at every level of the cascade. When we cross from one region into another at
> the L0 level we replan at the block level — but BEFORE that, if this L0→L0 crossing also led to
> an L1→L1 crossing then we ALSO slide the L1 window — and so on up the tree. The goal: until the
> FINAL destination is within your L0 skeleton, you never reach the end of it. As we approach the
> end of the L0 skeleton it keeps EXTENDING — we move S1→S2→S3, the consumed head is dropped, the
> tail is appended, indices renumber. If we slid the window at every resolution we'd never reach
> the end of the skeleton too early.

With the critical refinement over the forensics' option (a): **SLIDE-AND-EXTEND, NEVER RESET.**
The extension is a *suffix search from the existing tail* toward the newly-derived parent
hand-down; the prefix is never re-derived. A suffix search from a *fixed* start node cannot change
the already-planned route, so extension is structurally immune to the route flip-flop that killed
every previous "re-derive as the bot moves" scheme (the s52-documented hazard —
`BotNavigator.java:518-527`; visible live as **five skeleton swaps in 4 seconds**, longrun-7
14:44:55–59, forensics §4).

**The pathology being fixed** (forensics §2, "segment-tail pinning" — verified, not hypothesized):
the post-s52 boundary pivot (T2) works mid-window — it fired 12–14 times in longrun-7's healthy
phase. But near a cascade L0 segment's end, `windowLast()` clamps to the skeleton tail
(`PathPlan.java:737-739`), the window target pins to the **L1 hand-down portal cell**
(`HierarchicalRegionPlan.handDown`, :679-687), and *no trigger can move it*: T2 re-fires into the
clamp, and the consumption/terrain refreshes (T7/T8) deliberately re-aim at the same step. The
only advance signal left is the cascade's `exhausted` test — `committedIndex >= far`
(`HierarchicalRegionPlan.java:365-368`) — which requires the bot to **physically occupy the far
region**. For a vertical crossing, occupying the region ≡ arriving inside the portal cell's ±1/±2
box: the bot climbs into a cell the ratified design says is *not* an entrance ("no entrances and
no portals", `PathPlan.java:33-41`), spending place edits to do it. Longrun-7's smoking gun: the
target pins at (151,64,-31), the bot at y=61-63 pays `place@(150,62,-32)` for an Ascend INTO the
box, the next segment opens with a Descend + `place@(151,62,-32)`, and the bot falls away down to
y=56 (log lines 3462-3580 — the owner's "staircase-then-descend" waste, one cobble pair per
vertical segment boundary).

**Owner-expected wins — stated here as measurable predictions** (checkable via the existing
place/break attribution logs, §12.3):

1. **A large share of "random cobble" disappears.** The portal-box arrival edits — the
   ascend/descend cobble pair class above — are exactly the edits spent satisfying `exhausted` by
   occupancy. Prediction: place-edits-per-journey attributed within Chebyshev 2 of a *non-goal*
   window target drop to ~0 on the healthy battery routes.
2. **Canopy-walking preference weakens.** A pinned elevated tail portal makes climb-to-portal +
   cheap canopy traversal price well; with the target receding ahead of the bot, ground routes
   compete honestly. Prediction: fewer leaf-floor waypoints / tree-top place edits per forest
   journey.
3. **Window-join route waste (walk-then-drop, staircase-then-descend) disappears.** Adjacent
   windows join at a receding target, never at a forced elevated waypoint. Prediction: zero
   ascend-then-immediately-descend edit pairs within ±2 cells of a region boundary.

---

## §2 Invariants

Stated first; everything in §3-§9 exists to uphold them. INV-1..3 are existing invariants this
design must not break; INV-4..7 are the new ones it adds.

- **INV-1 (prefix stability — the no-flip-flop rule).** A slide or extension NEVER re-derives any
  part of a skeleton at or before its level's committed cursor. Route changes behind or at the bot
  come only from the *existing* full re-derive triggers: deviation, repair/escalation, flood-widen,
  top-collapse. (Upholds the s52 lesson, `BotNavigator.java:518-527`, and the re-derive-progress
  hazard note `HierarchicalRegionPlan.java:357-364` — extension produces a strictly-longer
  skeleton with shifted-not-reset cursors, so the s54 identical-content-swap livelock class cannot
  engage.)
- **INV-2 (commit hysteresis unchanged).** The per-level committed-advance (nearest-first,
  fragment-gated at L0 — `HierarchicalRegionPlan.java:336-350`) and PathPlan's wiggle/commit test
  (`committed()`, `PathPlan.java:692-707`) are byte-identical. Rolling *consumes* their signal; it
  never adds a new commit notion.
- **INV-3 (blame keys are index-free).** Every blacklist/crossing-memory row is a
  `(fromKey, toKey)` pair of `RegionPathfinder.fragmentNodeKey(rx,ry,rz,fragId)` — content-
  addressed region+fragment keys, no skeleton index anywhere in the stored row
  (`RegionEdgeBlacklist.java:22-24` parallel `long[]`; record sites
  `HierarchicalRegionPlan.java:432,472,656-668`; PathPlan's `blockedHop` builds keys from skeleton
  *content* at the blamed index, `PathPlan.java:1078-1086`). **Verified in code** — renumbering
  skeleton indices is therefore safe for all persisted/session blame state. The only
  index-*valued* state is PathPlan's BLOCKED snapshot (`blockedWindowStart`/`blockedTargetStep`,
  `PathPlan.java:913-914`) and the live cursors — §4.4 shifts them.
- **INV-4 (the rolling guarantee).** At every settle boundary, for every level whose skeleton has
  NOT reached the goal region ("non-final") and which is not in *degraded mode* (§7), the level's
  window-far index is strictly less than its skeleton's tail index — equivalently: **the hand-down
  portal is never the skeleton's terminal cell, and the cascade `exhausted` test never fires.** At
  L0 this specializes to the assertable driver form: `windowLast() < skeleton.size()-1` whenever
  `!skeleton.reachedGoalRegion()` — the window target never pins at a tail. This is the exact
  guarantee the implementation asserts (§12.1); "the bot never occupies the last hop of a
  non-final skeleton" follows from it (the bot walks toward a target that recedes before it can be
  occupied).
- **INV-5 (degraded mode = today's behavior).** When an extension search fails (§7), the level
  falls back to *exactly* today's machinery — exhausted-on-occupancy, `rederiveWithTubeEscalation`,
  blame, honest give-up — with no new states. Pin-at-portal is the explicit, documented degraded
  mode, not a failure.
- **INV-6 (region tier stays tick-confined).** DESIGN-background-pathfinding.md §4.5 stands:
  extension searches run on the tick thread (§6). No planner-pool thread ever touches
  `RegionGrid`/`RegionCrossingMemory`/the fragment flood thread-locals.
- **INV-7 (termination unchanged).** Once a level's skeleton reaches the goal region, that level
  stops extending and its window rides to the end exactly as today — the existing `reachedEnd`
  guard (`HierarchicalRegionPlan.java:365`) already exempts the true goal end from `exhausted`,
  and PathPlan's goal-in-window branch (`WindowTargeting.java:96-110`) and goal-tolerance COMPLETE
  (`PathPlan.java:562-565`) are untouched.

---

## §3 The model — the window base IS the committed cursor

Today every level's window is the **static head** of its skeleton: `far = min(WINDOW_CELLS,
size-1)` (`HierarchicalRegionPlan.java:298`, `handDown` :681), and the *only* way the window moves
is replacing the whole skeleton (`exhausted`/`deviated` → re-derive). The rolling model makes one
conceptual change:

> **A level's window is `[committedIndex .. committedIndex + WINDOW_CELLS]`.** The committed
> cursor — which already advances per crossing, with the ratified hysteresis (INV-2) — *is* the
> window base. "Slide the window at level L" is not a new mechanism; it is the committed-advance
> the cascade already performs, now made load-bearing.

### §3.1 The per-level slide rule (design question 1 — decided)

- **Trigger.** A **committed L(k) crossing** = `levels[k].committedIndex` advanced during
  `onBotMoved`'s existing per-level occupancy pass (`HierarchicalRegionPlan.java:336-350`:
  forward-only, nearest-first, fragment-gated at L0, region-only at coarse levels — untouched).
  Because every L(k+1) cell boundary is also an L(k) boundary, "an L0→L0 crossing that is also an
  L1→L1 crossing" is detected for free: the same pass advances *both* cursors on the same settle.
  No new detector runs; the slide adds one int compare per level to a loop that already executes.
- **Recursion up the tree.** Nothing to add: the pass already visits every level top-down. Each
  level whose cursor advanced has slid, independently, that settle.
- **What "slide" changes.** Three things, all derived: (1) **window bounds** — `far` becomes
  `min(committedIndex + WINDOW_CELLS, size-1)`; (2) **the hand-down portal** — `handDown` reads
  the new `far` cell's portal, so the child's sub-goal advances one hop per parent commit instead
  of jumping `WINDOW_CELLS` hops per exhaustion; (3) **the tube** — nothing: the child's
  `RegionTube` is constructed from the parent's *current skeleton object* at each child search
  (`HierarchicalRegionPlan.java:580-582`), so a slid/extended parent skeleton confines the child
  automatically. There is no per-slide work beyond recomputing one `min()`.
- **`exhausted` is retained verbatim** (`:366`) but becomes the degraded-mode safety net: while
  extension succeeds, `far` recedes ahead of `committedIndex` and the test is structurally
  unreachable (INV-4). It fires only when extension has failed (§7) — exactly today's semantics.

### §3.2 The extension pass

After the commit pass, one new top-down pass, `topLevel → 0`, per settle boundary (cold — it does
work only on the settle where a cursor actually advanced):

For each level L, **extend when** `committedIndex + WINDOW_CELLS > size-1` **and**
`!reachedGoalRegion` (a final skeleton never extends — INV-7). The extension (§4) appends enough
hops that INV-4 holds again (normally exactly one hop per crossing, since cursors advance one step
at a time). Processing top-down means a parent's hand-down has already advanced before its child's
extension aims at it, so each child extension runs toward the *current* sub-goal.

**Top level.** The top has no parent: its extension search aims at the real goal, untubed,
flood-guarded, with the existing cap-safe span discipline — this *is* today's "top level slides"
(HPA-CASCADE.md §7) made incremental. Additionally, on each top-level committed advance, re-check
`capSafeTop(botFloor)` (integer math, `HierarchicalRegionPlan.java:733-741`); if it changed,
full-`rebuild` exactly as today's `exited == topLevel` branch (:378-385) — **collapse-on-approach
is preserved**, it just triggers on commit cadence instead of exhaustion cadence.

**L0.** L0's cascade window drives nothing downward (there is no L−1); its extension exists so
*PathPlan's* block window never clamps: with the L0 skeleton always extending past
`windowStart + WINDOW - 1`, `windowLast()` (`PathPlan.java:737-739`) stops clamping, the T2
commit-slide keeps advancing the target step, and segment-tail pinning dissolves. PathPlan's own
window machinery — T2's wiggle-gated commit, the forward-slide, T7/T8 refresh semantics — is
**untouched** (INV-2); rolling only guarantees the skeleton under it is long enough.

---

## §4 The extension contract (design question 2 — decided)

### §4.1 The suffix search

- **Start:** the level's **existing tail step**, anchored at its portal cell
  (`skeleton.portalCell(size-1)`; fallback `centerOf(size-1)` when `NO_PORTAL`). The portal cell
  is a real on-face cell of the tail's `(region, fragment)` node, so the existing
  start-anchoring (`RegionPathfinder.startFragment`) resolves the search's start node to the tail
  node — the append point. **Never the bot** — starting from the bot would re-derive the prefix
  and reintroduce the flip-flop (INV-1). Degenerate cases: a tail already AT the new hand-down
  region+fragment ⇒ zero-length extension, no-op; a tail that is somehow behind the bot's
  committed region (can only happen after external skeleton surgery, i.e. never in this design)
  ⇒ fall back to the full re-derive path (§7) rather than guess.
- **Goal:** the new hand-down cell — `handDown(levels[L+1])` under the slid window (the parent's
  new `far` portal), or the real goal at the top.
- **Confinement:** the **slid parent tube** — `RegionTube(levels[L+1].skeleton, L+1, L,
  TUBE_MARGIN)`, built from the parent's current (post-slide/post-extension) skeleton, exactly the
  existing constructor `rederiveSuffix` uses (:580-582). Top level: untubed + flood guard, as
  today.
- **Budget:** the existing per-level windowed-search budget — the search spans ~1 hop toward a
  sub-goal ≤ `WINDOW_CELLS` parent cells away, cap-safe by construction (HPA-CASCADE.md §8); no
  new budget knob. It reuses `RegionPathfinder.planWithin` verbatim with the tail cell as the
  from-cell (the signature already takes an arbitrary from-cell, :583).

### §4.2 Splice + renumber

`RegionPathPlan` is immutable flat parallel arrays (`rxs/rys/rzs/frags/portalX/Y/Z/digs`,
`RegionPathPlan.java:38-72`) — a splice is one array copy at crossing cadence (cold):

```
newSkeleton = old[drop .. size-1] ++ extension[1 .. extSize-1]     // extension[0] == old tail, deduped
shift       = drop
```

implemented as a static `RegionPathPlan.splice(old, drop, suffix)` producing a fresh immutable
plan (same `level`, `reachedGoalRegion` = the suffix's flag). The **consumed head is dropped** at
splice time: `drop = committedIndex` at coarse levels; at L0, `drop = min(cascade L0
committedIndex, driver windowStart)` — the driver's wiggle-gated cursor bounds the drop so a
boundary-hugging plan whose commit is deliberately deferred (INV-2) never has its anchor forced
forward by an occupancy-only signal. (PathPlan passes its `windowStart` into the cascade hook as a
bound parameter — a bound, not a behavior; the cascade still never reads driver state.)

All cursors shift, never reset: `committedIndex -= shift` (level-local), and at L0 PathPlan
applies the same shift to `windowStart`, `committedIndex`, `windowTargetStep`, and the BLOCKED
snapshot indices `blockedWindowStart`/`blockedTargetStep` (§4.4). No `resetWindow()` on the
extension path — that call (`PathPlan.java:673-676`) remains only on the true swap paths (cascade
SWAP, repair).

### §4.3 Driver integration — a three-way cascade result

`HierarchicalRegionPlan.onBotMoved` currently answers a boolean ("L0 changed?") that
`PathPlan.stepCascade` (:629-648) interprets as swap+reset+replan. Rolling makes it a three-way
verdict:

- **UNCHANGED** — as today's `false`: fall through to the normal block-window slide.
- **EXTENDED(shift)** — the L0 skeleton was spliced (head-dropped `shift`, tail appended), same
  route prefix. PathPlan: swap the skeleton *reference*, apply the shift to its cursors and the
  BLOCKED snapshot, **keep the live block plan** (its waypoints and target cell are unchanged —
  the plan is still exactly as valid), and **fall through** to the normal `onBotMoved` commit
  logic over the now-longer skeleton. The block replan for the crossing then fires through **T2
  itself** — the same wiggle-gated commit-slide as today, now over an un-clamped window. This is
  the minimal-delta integration: rolling never adds a replan trigger, it un-clamps the existing
  one.
- **SWAPPED** — as today's `true` for genuine re-derives (deviation, flood-widen, top-collapse,
  repair): swap + `resetWindow()` + `replanBlock()`.

### §4.4 Blame/`blockedGeneration` interaction — why renumbering is safe

Per INV-3 (verified): every stored blame row is content-keyed; nothing persisted or session-held
indexes a skeleton. The two index-valued pieces of live state are handled explicitly:

- `blockedWindowStart`/`blockedTargetStep` (the BLOCKED snapshot, `PathPlan.java:913-914`,
  consumed by `blamedHopIndex` walking the *current* skeleton :1090-1098): shifted by the splice
  delta. Because extension preserves the prefix content (INV-1), a shifted index addresses the
  *same skeleton step* — the blame walk's inputs are unchanged in meaning. (This is strictly safer
  than today's SWAP path, where the snapshot survives only because a swap immediately replans and
  repair consumes the snapshot first.)
- `blockedGeneration` (:876-885) is untouched: one repair per BLOCKED result, exactly as today.

---

## §5 What happens at the moment of a crossing (the assembled sequence)

At a settled boundary where the bot's committed cursors advanced at levels 0..K (K = the coarsest
level whose cell boundary this crossing was):

1. Commit pass (existing, byte-identical): cursors 0..K advance; levels above K untouched.
2. Extension pass (new, top-down K→0): each level whose window-far now clamps splices a 1-hop
   suffix toward its parent's advanced hand-down (µs-scale region searches, §10). Top-level
   commit additionally re-checks `capSafeTop`.
3. PathPlan receives EXTENDED(shift), shifts cursors, falls through; **T2** commits the crossing
   under the wiggle rule and replans the block window toward a target that is now up to `WINDOW-1`
   steps ahead again — never the old tail.
4. Async (§6): the block search rides the planner pool as every T2 replan does today; the bot
   keeps walking its live plan until seam-gated adoption.

Until the goal's region enters the L0 skeleton's window, step 2 keeps the tail ahead of the
window; the goal-in-window endgame then rides to COMPLETE exactly as today (INV-7).

---

## §6 Async integration (design question 3 — decided: extensions are synchronous; the block tier rides the pool)

**Decision: extension searches run synchronously on the tick thread.** This diverges from the
owner's sketch ("extensions should ride the existing background planner") and is flagged in §15 —
the reasoning:

- **The numbers.** An extension is a 1-hop, tube-confined, cap-safe region search — the same class
  as the cascade's existing per-level windowed searches, measured at **microseconds** (the
  region-tier perf gate accepted +0.07–0.33 µs/plan for the swim-blindness stack; the forensics
  prices a full L0 re-derive at "µs" against the block search's ~200–650 µs live). Moving µs of
  work off-thread saves nothing measurable.
- **The constraint.** DESIGN-background-pathfinding.md **§4.5 — the region tier stays
  tick-confined** — is a ratified memory-safety boundary: `RegionGrid.ensureLeaf` mutates, the
  fragment floods use thread-local masks, `RegionCrossingMemory` is tick-thread-owned. Putting
  region searches on the pool opens a whole new hazard class (the §4.1-equivalent for region
  state) to save microseconds. INV-6 keeps the boundary.
- **What actually needs async already has it.** The expensive follow-on to an extension is the
  *block* search toward the post-slide window target — and that is a T2 replan, which submits to
  the planner pool through the existing `replanBlock` async branch (`PathPlan.java:800-815`)
  unchanged.

**The P4 pre-plan hook is repurposed by doing nothing to it.** Today `preplan`
(`PathPlan.java:1024-1030`) predicts the current plan's end cell and explicitly reuses
`windowTargetPos` — under pinning, that meant precomputing *toward the pinned tail*. Under
rolling, the window target advances at T2 cadence, so the half-consumed pre-plan naturally
precomputes toward the *current, post-slide* target; the parked-seam adoption, the
one-attempt-per-target churn guard, and the target-moved drop (`AsyncWindowSearch.pollParked`
:155-174) all apply verbatim. No parallel mechanism is added.

**Synchronous fallback when a block search hasn't landed as the bot nears the (old) tail:** the
existing async contract *is* the fallback — the current plan stays live, the bot keeps walking,
adoption happens at the next settled boundary, and a seam reject resubmits from the actual floor.
In the worst case (adoption starved all the way to the old tail), the bot arrives at the tail
portal and today's occupancy machinery takes over: **pin-at-portal is the explicit safe degraded
mode, not a failure** (INV-5). No new waiting state, no timer (the no-arbitrary-timers rule).

---

## §7 Empty/blocked extension (design question 4 — decided: fold into the existing escalation, zero new states)

An extension search can return `null`. The disposition maps onto machinery that already exists:

- **Tube-confined null** (`RegionPathfinder.lastWasTubeConfined()`): the corridor — the parent's
  slid window — cannot be refined past the tail. This is *the same deterministic search* the
  exhausted-time re-derive would run later with the same inputs, so deferring it only buys the
  walk-into-the-box waste back. Act now: invoke **`rederiveWithTubeEscalation(L, botFloor)`**
  (`HierarchicalRegionPlan.java:608-624`) — blame the first untouched parent-window hop
  (`blameTubeConfined` :640, `PROV_ESCALATION` session-only rows), re-plan the parent, recurse
  upward, `MAX_TUBE_ESCALATIONS` backstop. Its outcome is a **SWAPPED** verdict to the driver
  (reset+replan — a genuine route change, INV-1's sanctioned path) or the honest FAIL.
- **Untubed null at the top:** flood ⇒ widen the lens (`rebuild` one level coarser, §3a, :522-538);
  genuine drain ⇒ honest FAIL with the post-mortem — byte-identical to today's top-level
  semantics.
- **Degraded mode** (the escalation backstop tripped, or the null arrived in a state where
  escalation declined to blame): the level simply *stops extending* — skeleton, cursors, window
  all keep their current values, the bot walks on toward the existing tail, and the retained
  `exhausted`-on-occupancy test (§3.1) plus the one-repair-per-`blockedGeneration` path
  (`repairBlocked`, `PathPlan.java:1214-1242`) own recovery exactly as today. A per-level
  `degraded` flag is set for the invariant assertion/telemetry (§12) and cleared by any successful
  extension or re-derive at that level. **No new failure state, no new recovery machinery** — the
  blame/evidence model (invalidation-evidence-model memory: realized-evidence rows only,
  escalations session-scoped) is consumed as-is, and extension-driven blames add no new provenance
  kind.

---

## §8 The partial fold-in (design question 5 — decided: same trigger, evaluated on the partial's path; one gate expression)

Today's gate is the crossing-blind plan-level boolean `&& !lastPlanPartial` at
`PathPlan.java:597`: while the window plan is a PARTIAL, the commit-slide is suppressed entirely
and the bot rides to the terminus (`cad0280` follow-to-terminus; rationale in the :598-605
comment — sliding mid-partial re-searched from intermediate cells and oscillated, so the bot never
settled at the terminal dead-end where the crossing gets blamed).

**Decision: fold into the rolling mechanism as the same trigger evaluated on the partial's path —
not a separate weakening.** The commit test the slide already uses, `committed(j)` (:692-707),
asks the plan's *remaining suffix* whether it revisits `[committedIndex, j)` — it is exactly the
"does the partial's terminus lie past the crossing" question when run over a partial's waypoints.
The gate becomes:

```
(sameRegionDig || committed(curRegion)) && (!lastPlanPartial || partialCrossesBeyond(curRegion))
```

where `partialCrossesBeyond(j)` = the partial's terminus waypoint maps to a skeleton index ≥ j (or
off-skeleton *past* the window per `forwardIndexOf`'s convention). Both branches of the original
rationale survive: a partial that FAILED to cross (terminus at/before the crossing) still
suppresses the slide and rides to its dead-end terminus so the blame converges there
(invalidation-evidence-model governs — **re-read that memory before implementing this gate**); a
partial that already crossed and continues onward slides at the crossing like any complete plan,
and the re-search heads onward, not back. Zero effect on longrun-7 (all 11,722 searches FOUND,
zero PARTIAL — forensics §3), so this ships as its own increment (§13 D) with its own repro.

---

## §9 Termination + the invariant statement (design question 6 — decided)

Endgame is unchanged (INV-7): a final skeleton (goal region reached at the tail) never extends;
`reachedEnd` already exempts the goal end from `exhausted`; the goal-in-window target and the
goal-tolerance COMPLETE are untouched; the FINAL plan's window legitimately rides to the tail.

**The asserted guarantee is INV-4** in its driver form — after each settle boundary's
slide/extend pass:

```
for every non-final, non-degraded level L:   levels[L].committedIndex < far(L) < tail(L)
at L0 (PathPlan):                            windowLast() < skeleton.size() - 1
```

with the companion journey counters `exhaustedFires` and `degradedSettles` (pure observation,
`NavJourneyStats` idiom). On the healthy battery both must be 0; the wall/blocked repros are
allowed nonzero (degraded mode is *supposed* to engage there). The assertion is a Debug-gated
check + telemetry, not a throw — a violated invariant on a live server logs and degrades (INV-5)
rather than crashing the tick thread.

---

## §10 Perf accounting (design question 7)

The CLAUDE.md perf model applies: region-tier work is cold-path but budgeted; no per-tick
allocation; per-search setup is itself hot for the block tier (unchanged here).

- **Crossing detector: zero new per-tick cost.** The committed-advance pass already runs every
  `onBotMoved` (`HierarchicalRegionPlan.java:295-371`); the slide adds one int compare per level
  (≤7) on that pass and the extension condition is only evaluated on the settle where a cursor
  advanced. The per-tick fast path (no commit) is byte-identical.
- **Extensions per journey ≈ parent crossings per journey.** Per level: one 1-hop tube-confined
  search per committed crossing at that level; crossings thin geometrically with level (a level-k
  cell spans 2^k leaves per axis), so total ≈ 2× the L0 crossing count. Compare today: one
  full-suffix re-derive (up to `topLevel+1` searches, each spanning a `WINDOW_CELLS` window) per
  `WINDOW_CELLS` crossings per level. **Same order of total search volume, spread evenly** — the
  per-event cost drops (1-hop vs 4-cell window, no multi-level suffix), the event count rises ~4×,
  and every search is the already-proven cap-safe µs class. Net per-crossing tick cost: one µs-
  scale region search + one array splice — against the ~200-650 µs block search T2 already spends
  at that same boundary. Budget: within the accepted region-tier envelope (the +0.07-0.33 µs/plan
  class the swim-blindness gate ratified); measured, not assumed (§12.2).
- **Block searches: same count, aimed better, earlier.** T2/T7/T8 cadence is unchanged; what
  changes is that tail-clamped same-target refreshes (48 of the healthy phase's 76 replans in
  longrun-7 re-aimed at an immovable cell) become forward-aimed searches. The pre-plan hook makes
  them land off-thread before arrival (§6) — the "same total search count, moved earlier +
  off-thread" framing is exact.
- **Memory: no ring/deque — decided.** The skeleton stays a flat immutable `RegionPathPlan`,
  copy-on-splice; head-dropping bounds it to ~(window + extension margin) entries — a few dozen
  ints per level. One splice allocation per crossing (cold, replan cadence) matches the existing
  house-style budget ("allocates only the replaced immutable RegionPathPlan arrays",
  `HierarchicalRegionPlan.java:44-48`). A ring buys nothing at this size and breaks the immutable-
  swap identity the driver's change-detection relies on.
- **JMH: unaffected by construction** (§ preamble). `PatchStormBenchmark`/`PathfinderBenchmark`
  touch nothing here. The region-tier headless suite is the perf gate that must be run (paired
  A/B, per the process rule).

---

## §11 What changes byte-behavior (honesty section)

- **Routing cadence changes for every multi-segment journey**: window targets advance ~per
  crossing instead of pinning at segment tails; cascade levels re-plan incrementally instead of
  in `WINDOW_CELLS` batches. Paths taken in-game WILL differ. This is the point.
- **Byte-identical**: the block search algorithm and its per-search results (same inputs ⇒ same
  expansions/paths), the commit hysteresis (INV-2), the blame/evidence model and its provenance
  kinds, sync-mode `pathing.async=false` semantics per search, the goal endgame, honest give-up
  on the wall repro (degraded mode reduces to today's machinery — same FAIL, same t≈3416 class
  budget semantics).
- **Not byte-identical but semantically equivalent**: the BLOCKED-snapshot indices are shifted
  (same steps addressed); `describeSkeleton` dumps show head-dropped indices.
- The headless cascade tests that assert static-head window geometry (`HierarchicalCascadeTest`
  exhaustion cases) will need updating to the rolling geometry — per the
  dont-weaken-model-for-outdated-test rule, the *tests* move, not the model.

---

## §12 Test / validation plan (design question 8)

**§12.1 Unit seams (headless, `RegionGrid.headless` idiom):**
- `RegionPathPlan.splice`: pure array op — prefix preserved verbatim, dedup at the join, shift
  arithmetic, `reachedGoalRegion` propagation, `NO_PORTAL` tails.
- Slide trigger: drive a synthetic bot along a known multi-level skeleton; assert per-level `far`
  recedes ahead of the cursor, hand-down advances one hop per parent commit, `exhaustedFires==0`,
  and INV-4 holds at every settle (the assertion itself under test).
- Extension-null degradation: goal-side blocked + tube-confined null ⇒ escalation fires with the
  SAME blame rows as today's exhausted-time path (edge-key equality, not index equality);
  backstop ⇒ degraded flag + occupancy fallback.
- PathPlan shift: BLOCKED snapshot before a splice, repair after — blamed hop identical pre/post
  renumber (INV-3's checkable form).
- The §8 gate: a partial crossing beyond j slides; a partial dying before j rides to terminus.

**§12.2 The battery gates (mc-1.21 worktree, `-MasterWorld` frozen world):**
- up-cliff repro **PASS** (arrival, no repeat blames — the region-tier-swim-up memory's oracle).
- `-KeepWorld` restart oracle **PASS** — no-repeat + convergence, not speed
  (invalidation-evidence-model).
- wall repro: budget-FAIL with **unchanged semantics** (honest give-up, 0 repeat blames —
  degraded mode must not turn a give-up into a loop).
- Region-tier perf: paired A/B on the headless region suite; accept within the ratified envelope.

**§12.3 Journey metrics (from the existing place/break attribution + Debug logs — the §1
predictions made operational):**
- placements-per-journey attributed within Cheb-2 of a non-goal window target → ~0 healthy.
- best-dist progress monotone; zero envelope-fail stalls at portal boxes.
- `exhaustedFires == 0` and `degradedSettles == 0` on healthy routes; skeleton-swap count per
  journey drops (the T3 churn cluster class).
- the longrun-7 route replayed: the (150,62,-32)/(151,62,-32) cobble pair absent.

---

## §13 Increment plan (design question 9 — smallest shippable, each independently testable)

- **A — L1 slide + L0 extend, sync** (the waste-killer): committed-cursor-as-window-base at L1
  only (`handDown`'s `far` formula), L0 splice/extend toward the advancing L1 hand-down, the
  EXTENDED(shift) driver verdict + index shifts, INV-4 assertion at L0. Levels ≥2 keep today's
  exhausted machinery (their windows still hand down correctly — L1's *own* skeleton still
  re-derives on ITS exhaustion). Segment-tail pinning is an L0/L1 phenomenon in the log, so A
  alone should land predictions 1-3. Gate: §12.1 splice/shift/slide units + §12.2 battery +
  §12.3 metrics.
- **B — recursion to all levels**: the top-down extension pass generalized, top-level incremental
  slide + `capSafeTop`-on-commit collapse, per-level degraded flags. Gate: multi-level headless
  cascade tests + a long (multi-L2) journey run.
- **C — extension-failure composition hardening**: the §7 escalate-on-extension-null path +
  degraded-mode telemetry, exercised by the wall/blocked repros. (A/B ship with the conservative
  interim: extension-null ⇒ degraded immediately, occupancy fallback — strictly today's behavior.)
- **D — the partial fold-in** (§8): the one-expression gate change at :597, behind its own repro
  (a partial that crosses a skeleton step), after re-reading the invalidation-evidence-model
  memory. Independent of A-C.

(The prompt-era "B = async extension" increment is dissolved by the §6 decision — there is no
async extension to build; the async work the design needs already exists in the block tier.)

---

## §14 Decisions log

| # | Decision | Rationale (§) |
|---|---|---|
| D1 | The window base IS the committed cursor; slide = the existing committed-advance made load-bearing; no new commit notion | §3, INV-2 |
| D2 | Extend by suffix search from the existing tail node; prefix never re-derived | §4.1, INV-1 (the flip-flop lesson) |
| D3 | Head-drop at splice time; L0 drop bounded by the driver's wiggle-gated `windowStart` | §4.2 |
| D4 | Three-way cascade verdict UNCHANGED/EXTENDED(shift)/SWAPPED; EXTENDED keeps the live block plan and falls through to T2 | §4.3 |
| D5 | Renumbering is safe: blame rows are content-keyed (verified); only the BLOCKED snapshot + live cursors shift | §4.4, INV-3 |
| D6 | Extensions run SYNC on the tick thread (µs-scale; §4.5 tick-confinement stands); async rider = the block tier's existing T2/P4 machinery, untouched | §6, INV-6 |
| D7 | Pin-at-portal is the explicit degraded mode; `exhausted` + repair retained verbatim as the safety net | §7, INV-5 |
| D8 | Extension tube-null escalates immediately via `rederiveWithTubeEscalation` (same deterministic search as exhausted-time; deferring re-buys the waste) — increment C; A/B interim = degrade immediately | §7, §13 |
| D9 | Partial refinement folds into the same trigger via `committed()` over the partial's suffix; one gate expression, follow-to-terminus preserved for the failed-to-cross case | §8 |
| D10 | No ring/deque; immutable copy-on-splice at crossing cadence | §10 |
| D11 | INV-4 (`windowLast() < tail` on non-final, non-degraded skeletons) is THE asserted invariant; counters `exhaustedFires`/`degradedSettles` must be 0 healthy | §9, §12 |

## §15 For ratification (genuinely owner-level calls)

1. **D6 — synchronous extensions** diverges from the stated model ("extensions should ride the
   existing background planner"). Evidence says the searches are µs-scale and the ratified §4.5
   tick-confinement boundary is worth more than the µs; the async benefit the model wanted arrives
   via the block tier's existing pre-plan. Confirm or direct a region-tier-async arc (which would
   be its own design, DESIGN-async-region-tier.md territory).
2. **D8 — escalate-on-extension-null immediately** (vs defer to occupancy). Immediate escalation
   acts on the same deterministic evidence earlier but fires blame from a mid-window bot; the
   conservative interim in increments A/B (degrade, let occupancy decide) is today's exact
   behavior. Confirm the increment-C promotion.
3. **D3 — the L0 head-drop bound** couples one driver value (`windowStart`) into the cascade hook
   as a parameter. Alternative: never head-drop at L0 (all live scans are cursor-bounded, so an
   ever-growing skeleton costs only memory) — cleaner layering, unbounded-ish array. Owner
   preference on hygiene-vs-layering.
4. **§11 test updates** — the exhaustion-geometry cascade tests get rewritten to rolling geometry
   (the dont-weaken-model rule cuts both ways; confirming scope avoids silently weakening the
   old guards).

---

## §13-A implementation notes (shipped 2026-07-24, mc-1.21 worktree; suite 560/0/5 green)

**Files.** `HierarchicalRegionPlan.java` (the slide, the extension pass, the verdict, the counters),
`RegionPathPlan.java` (`splice`), `PathPlan.java` (the EXTENDED seam + INV-4 driver check + counter
delegates), tests `RollingSkeletonTest.java` (new, 5 tests) + `HierarchicalCascadeTest.
onBotMoved_selectiveReplan_fineBeforeCoarse` (rewritten to rolling geometry per §11/§15-4).

**The verdict seam (§4.3) as built.** `HierarchicalRegionPlan.onBotMoved(botFloor, onRoute,
driverWindowStart)` returns `enum Verdict { UNCHANGED, EXTENDED, SWAPPED }` with the companion accessor
`lastExtensionShift()`; the legacy 1/2-arg boolean forms delegate with a zero drop bound and report
`true` only on SWAPPED (EXTENDED ⇒ `false` — nothing to reset; callers re-read `l0Skeleton()`).
`PathPlan.stepCascade` handles EXTENDED exactly per §4.3/§4.4: swap the reference, shift
`windowStart`/`committedIndex`/`windowTargetStep`/`blockedWindowStart`/`blockedTargetStep` by the drop
(clamped at 0 defensively), keep the live block plan, fall through to T2. `resetWindow()` remains only on
SWAPPED + repair.

**Where implementation refined the letter of the design (no behavioral conflicts — all upheld its
invariants):**

1. **`blameTubeConfined` also uses the slid window.** The design named only `handDown`/`exhausted` for
   the `far` formula change, but with an L1 cursor free to pass the static head, the blame walk's
   `[committedIndex+1 .. far]` range would go empty and the all-touched fallback would blame a hop
   *behind* the commit. All three `far` readers now share one `windowFar(L, lp)` (rolling base at
   `L ≤ ROLLING_MAX_LEVEL = 1`, static head above — the §13-A scope gate; increment B lifts the ceiling).
2. **Extension cadence = a one-attempt latch, not a per-settle pass.** `l0ExtAttempted` is re-armed by
   any L0/L1 committed advance or a fresh L0 skeleton and consumed by one attempt — implements §3.2's
   "does work only on the settle where a cursor actually advanced" while still covering a freshly-derived
   short skeleton (first settle attempts even without an advance). A rolling advance also clears the §7
   `degraded` flag so changed inputs retry. No timers.
3. **Zero-length suffix = the parent-clamped no-op**, distinct from degraded: the tail already sits at
   the L1 hand-down because L1's own window is clamped at ITS tail (increment B territory). Exempted from
   INV-4 via `l0RollingExempt()` (which also exempts FAILED / `topLevel==0` / final / degraded); observed
   routinely on live-shaped walks near L1 segment ends.
4. **`exhaustedFires` counts L0-as-the-COARSEST-exit only.** The L0 tail is the parent's window-far
   portal, so occupying it usually exhausts the parent on the same settle and the coarsest-exit rule
   attributes the re-derive there (test-verified). Parent-level exhaustion is legitimate increment-A
   machinery, so the healthy-battery `exhaustedFires == 0` oracle stays exact under A.
5. **INV-4 driver check** (`PathPlan.onBotMoved`, Debug-gated log-only per §9) carries a cascade-clamp
   conjunct (`hier.committedAt(0) + WINDOW_CELLS > size-1`) so the transient settle where the driver's
   forward-slide runs ahead of cascade occupancy cannot false-alarm.

**Empirical finding worth keeping (from building the §12.1 fallback test):** over BUILT children the
coarse roll-up is honest and over UNBUILT cells both tiers are equally optimistic, so terrain alone
(solid walls, even full-tube-cross-section ones; the W=30 escalation wall, open OR sealed connectors)
does NOT produce an L0 extension null — the extension legitimately refines every honest hand-down
(the open-connector wall walk is pure EXTENDED/UNCHANGED end to end). The real extension-null source is
the **L0/L1 blacklist asymmetry** (per-level blacklists the parent doesn't read — e.g. #5
crossing-memory seeds), which is exactly increment C's trigger class; the shipped fallback test forces
it that way (remembered dead crossings walling off the future hand-down face). Prediction for §12.2:
healthy battery routes should show `degradedSettles == 0` for terrain reasons alone.

**Tests** (all in `regionpathfinder`): `RollingSkeletonTest.splice_preservesPrefixVerbatim_dedupsJoin_
propagatesGoalFlag`, `splice_zeroDrop_keepsWholePrefix_nonFinalSuffixStaysNonFinal`,
`slide_extendsAheadOfCommit_neverRederives_inv4Holds` (INV-4 + both counters 0 across a driven healthy
walk; L1 object survives), `extension_shiftsCursor_prefixUntouched_shiftedIndexAddressesSameStep`
(INV-3's checkable form), `extensionNull_degradesImmediately_todaysMachineryRecovers` (§7 interim:
degrade → object stability → exhausted-time recovery, no FAIL); rewritten
`HierarchicalCascadeTest.onBotMoved_selectiveReplan_fineBeforeCoarse` (phase 1: no SWAPPED across 15 L0
cells, L1/L2 untouched, ≥1 EXTENDED; phase 2: coarse consumption still re-derives). Suite: 560 tests,
0 failures, 5 skipped (baseline 555/0/5 + these 5).

**Deferred exactly per §13:** recursion above L1 + top-level incremental slide/`capSafeTop`-on-commit
(B), extension-null escalation + degraded telemetry hardening (C), the §8 partial gate (D). The §12.2
battery/journey gates and the region-tier paired A/B perf run remain open for this increment's field
verification.
