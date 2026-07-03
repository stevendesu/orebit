# DESIGN — Portal Route Layer (multi-leg routing above PathPlan + the splice primitive)

> **STATUS (2026-07-03): DESIGN RATIFIED-PENDING — route layer NOT implemented; the splice primitive it
> shares HAS shipped (s44).** Still the plan of record for HANDOFF item #9; P0 remains the unverified
> 8-item fake-player portal checklist (§8.4).

## STATUS — what shipped vs. what's pending (2026-07-03)

**Shipped (s44 — the background-pathfinding arc landed the shared infrastructure this doc depends on):**
- **The splice primitive** (`§4`) is **live** in `pathfinding/splice/`: `SpliceSeam` (seed → accept → adopt,
  Chebyshev tolerance) + `EditSnapshot` (latest-wins fold of a plan's unexecuted suffix) + the
  `findPath(…, baseline, budgetNanos)` params + `PathEdits.addSnapshot` (appended AFTER the cameFrom walk so
  path edits shadow the baseline). It shipped as **shared infrastructure** for the async planner (Consumer B),
  exactly as `§4` intended — so the portal route layer (Consumer A) now consumes an existing, tested contract
  rather than building it. The per-dimension `EditLedger` rule (`§4.4`) remains route-layer work.
- **`NetherPortalIndex`** (`§5.1` discovery layer) — live, per-dimension, fed by classify + patch + evict.
- **Portal-seek / ENTER terminal states** in `AllyBotEntity` (`§2`, `§3.2`) — live as FOLLOW infrastructure
  (the `EnterPortalAction` *extraction* is still pending; today the logic sits in `followThroughPortal`).

**Pending (the route layer proper — none implemented):**
- `RouteDriver` / `RouteLeg` / `Route` (`§3`, `§8.1`), the multi-leg driver above `PathPlan`.
- `PortalPairings` observation + canonical keys (`§5.2`).
- The break-even estimator + margin gate (`§6`).
- `EnterPortalAction` extraction from `AllyBotEntity` (`§3.2`).
- The lazy-vs-eager leg-splicing policy (`§4.6`) and the `EditLedger` per-dimension carry (`§4.4`).

**⚠ DESIGN BLOCKER — descriptor-bit conflict (`§7` END_PORTAL widening).** `§7` proposes widening
`PORTAL_BIT` (bit 43) into a 2-bit `PORTAL_KIND` field spanning **bits 43–44** — but **bit 44 was taken by
the s43 protected-blocks feature** (`NavBlock.PROTECTED_BIT = 1L << 44`), so that span now collides. Verified
current `NavBlock` descriptor bit map (read from source):

| bits | field | | bits | field |
|---|---|---|---|---|
| 0–4 | topY | | 24–31 | hardness |
| 5–7 | shape | | 32–34 | tool |
| **8–13** | **FREE** (reclaimed sturdy-faces mask) | | 35 | toolRequired |
| 14–15 | openable | | 36 | waterloggable |
| 16–17 | fluid | | 37 | STANDABLE (derived) |
| 18–19 | surface | | 38 | BREAKABLE (derived) |
| 20 | climbable | | 39 | OPEN_PLACE (derived) |
| 21 | gravity | | 40 | COLLISION (derived) |
| 22 | damaging | | 41–42 | transit-slow |
| 23 | replaceable | | 43 | PORTAL (nether only) |
| | | | 44 | PROTECTED |

**Free bits: 8–13 (a 6-bit hole) and 45–63 (19 contiguous high bits).** Recommended relocation:
define `PORTAL_KIND` as a fresh **contiguous 2-bit field at bits 45–46** (0 none / 1 nether / 2 end /
3 end-gateway) and **migrate** the existing bit-43 nether flag into it, so `isPortal(d)` becomes
`portalKind(d) == 1` (still one mask+compare) and bit 43 is freed. A 2-bit field *cannot* straddle the
PROTECTED bit, so the original "widen 43–44 in place" plan is dead. Lower-churn alternative: keep bit 43 as
the nether flag and add a single standalone **END_PORTAL bit at 45** (no contiguous KIND field) — adequate if
end-gateway (kind 3) is never needed. Either way the `§7` prose that says "bits 44–63 are free" / "widen bit
43 into bits 43–44" is stale; see the annotations there.

> HANDOFF menu item **#9**, drafted 2026-07-02. Design only — no code in this document is implemented.
> Companion reading: `internal_docs/PRD.md` §6.5 (portals as local edges, 8:1 only in the heuristic),
> §7.1 (two-tier model), `HPA-CASCADE.md` (the region cascade PathPlan drives), and the s42-arc1 commit
> `21f68d3` (NetherPortalIndex + portal-seek/ENTER). The **splice primitive** here is the owner-ratified
> first-class reusable function (memory `path-splice-primitive`): it must serve BOTH the portal route
> layer and the future background-thread pathfinder (HANDOFF item 9-bg: "pre-compute the next segment
> while walking the current one, then splice").

---

## 1. Problem & scope

Today the bot handles exactly ONE cross-dimension situation: the owner left the bot's dimension while
in FOLLOW/COME. `AllyBotEntity.followThroughPortal()` then seeks the **nearest known** nether portal
(`NetherPortalIndex.nearest`), walks in via the ENTER terminal state, and after the teleport
re-anchors and resumes normal behaviour in the new level. That machinery is reactive and target-blind:
it cannot (a) route to a goal that *lives* in another dimension (`/bot goto` a nether cell), (b) use
the nether as a **highway** for a far same-dimension goal (nether horizontal distance = overworld/8),
or (c) choose *which* portal to use, because it has no notion of where a portal *comes out*.

This design adds a **multi-leg route planner ABOVE `PathPlan`**:

- A **route** = ordered **legs**, each leg a (dimension, start anchor, goal, lazily-built PathPlan),
  separated by **transit actions** (generalized portal-ENTER + await-teleport + re-anchor).
- A **break-even gate**: a closed-form tick estimator decides direct-vs-portal BEFORE any planning.
- **Portal pairing knowledge**: NetherPortalIndex on both sides + observed pairings + the vanilla
  1:8 prediction for unpaired portals.
- The **splice primitive**: the reusable contract for stitching two independently-computed plans into
  one followable sequence — including the owner-ratified **PathEdits-across-dimension rule**.

Non-goals here: persistence of portal knowledge (memory-lifetime only, matching NetherPortalIndex),
long-range portal discovery in unloaded chunks (the POI-backed world-search arc), End routing beyond
classification + ENTER notes (§7), and the background-thread executor itself (we only make the seam
it needs).

Nothing in this design touches the block-A\* hot loop except one per-**pop** null-check (§4.3), which
per the CLAUDE.md performance model gets the full design-review + paired-A/B treatment.

---

## 2. What exists today (the parts we compose)

| Piece | Where | Reused as |
|---|---|---|
| Per-level portal index | `worldmodel/pathing/NetherPortalIndex` (`ServerLevel → chunkKey → packed long[]`, `nearest()` linear scan, cold) | The discovery layer on BOTH sides; gains a `nearestTo`/iteration surface for the estimator (§6) |
| Portal-seek + ENTER terminal state | `AllyBotEntity.portalSeekTick`/`enterPortalTick` (+ `PORTAL_ENTER_DIST=2.0`, `PORTAL_ENTER_TIMEOUT_TICKS=200`, `PORTAL_BACKOFF_TICKS=15`, one retry) | The **transit action** between legs, extracted/parameterized (§3.2) |
| Completed-teleport detection + re-anchor | `AllyBotEntity.tick` (`Worlds.of(this) != lastLevel` post-`doTick`) → `onLevelChanged()` (clearPlan, drop anchors, `ClientLoad.markLoaded`) | The leg-boundary event: it is what *advances the route cursor* (§8.1) |
| Two-tier driver | `PathPlan` (region cascade + sliding window + boundary-gated commit; already fully parameterized by `ServerLevel` + `RegionGrid.of(level)`) | One instance **per leg**, unchanged internally |
| Edit diff over the grid | `PathEdits` (per-pop rebuild from the `cameFrom` chain, first-seen-wins = latest-edit-wins, bbox-gated `kindAt`) + pooled `StepEdits` per step | The mechanism the splice's **baseline seeding** rides (§4.3) |
| Boundary-gated replan | `driveToward` acts only when `blockPosition().below() == settledFloor` | The **adoption point** for a spliced plan (§4.5) |
| Damage/pricing currency | `Traverse.FLAT_COST = 4.633` t/block, `BotCaps.costPerHitpoint`, `getPortalWaitTime()` ≈ 80 t survival / ~1 t abilities-invulnerable | The estimator's constants (§6) |

`NavBlock` descriptor: `PORTAL_BIT = 1L << 43` (nether only). Bits **44–63 are free**; navtype count
~587 of the 1024 cap — huge headroom for §7's END_PORTAL widening.
*(see STATUS: bit conflict — bit 44 is now PROTECTED; free bits are 8–13 and 45–63.)*

---

## 3. The leg model

### 3.1 Route = legs + transits

```java
// pathfinding/route/ (new package, cold code — polymorphism fine per polymorphism-off-hot-path)
final class RouteLeg {
    final ServerLevel level;        // the dimension this leg walks in
    BlockPos startAnchor;           // floor cell: leg 0 = bot's floor; leg N>0 = PREDICTED portal exit
    final BlockPos goalFloor;       // this leg's goal: a portal's standable base cell, or the real goal
    final LegGoalKind kind;         // PORTAL_ENTRY (goal is a portal to walk into) | FINAL
    final BlockPos portalCell;      // kind==PORTAL_ENTRY: bottom portal cell of the target column
    PathPlan plan;                  // built LAZILY when the leg becomes active (see §4.6)
}

final class Route {
    final RouteLeg[] legs;          // 1..3 legs for everything in scope (direct / one-way / highway)
    int cursor;                     // active leg index
    final EditLedger ledger;        // per-dimension planned-edit snapshots (§4.4) — EMPTY in lazy mode
    final float estimatedTicks;     // the §6 estimate that won route selection (debug surface)
}
```

A leg's goal for a `PORTAL_ENTRY` kind is `portalCell.below()`-anchored exactly the way
`portalSeekTick` targets portals today (bottom portal cell so the pathing goal floor is the obsidian
base — reuse the existing descend-to-bottom-cell loop). `FINAL` legs use the real goal floor.

Route shapes in scope:

- **1 leg**: direct (the estimator said portals don't pay) — degenerates to today's behaviour exactly.
- **2 legs**: cross-dimension goal (overworld → nether goal, or reverse). Leg 1 `PORTAL_ENTRY`,
  leg 2 `FINAL` in the destination dimension.
- **3 legs**: nether highway for a far same-dimension goal. Leg 1 `PORTAL_ENTRY` (overworld),
  leg 2 `PORTAL_ENTRY` (nether, to the exit portal), leg 3 `FINAL` (overworld again). This is the
  round-trip case the PathEdits dimension rule must survive (§4.4).

### 3.2 The transit action — what generalizes from portal-seek/ENTER, what doesn't

Reading `AllyBotEntity` lines 494–640, the machinery splits cleanly:

**Generalizes as-is (extract into the transit action):**
- The ENTER state machine: face column → `zza=1` walk-in → stand still once `footX/footZ` match →
  vanilla portal process ticks in `baseTick` → timeout 200 t → one backoff(15 t)+retry → give up.
  Nothing in it references the owner.
- The bottom-cell descent (`while (NavBlock.isPortal(descriptorFor(below)))`).
- The teleport-completion detection + `onLevelChanged()` re-anchor (clearPlan, null anchors,
  `ClientLoad.markLoaded`, `MoveReport` teleport-tick skip). This is level-keyed, not owner-keyed.
- The handoff trigger: `arrived || (distXZ ≤ PORTAL_ENTER_DIST && |dy| ≤ ARRIVE_Y)`.

**FOLLOW-specific (stays in `followThroughPortal`, does NOT move into the route layer):**
- The *trigger* (`Worlds.of(owner) != Worlds.of(this)`, re-evaluated per tick) and the
  *abort-on-owner-return* (`resetPortalSeek()` when levels re-equalize).
- The *nearest-at-seek-time* portal selection. The route layer selects a **specific** portal chosen
  by the estimator; "nearest" is only its fallback when no pairing knowledge exists.
- The give-up chat lines ("I don't know a portal…" / "I couldn't get through…") — the route layer
  gets its own failure surface (§8.3).

**Proposed extraction**: an `EnterPortalAction` owning the fields
`{portalTarget, enteringPortal, portalEnterTicks, portalBackoffTicks, portalEnterRetries}` and the
two tick methods, parameterized by a target portal cell + a `BotSteering`-ish facade (it needs
`faceHorizontally`, `zza`, `footX/footZ`, position). `followThroughPortal` keeps its trigger logic
and delegates to the same action — one implementation, two callers. The retry's "re-query nearest"
becomes a callback so the route layer can instead re-select from its pairing table (or blacklist the
portal and re-route, §8.3).

### 3.3 One PathPlan per leg — why nothing inside PathPlan changes

`PathPlan` is already fully dimension-parameterized (`level`, `RegionGrid.of(level)`, per-level
NavStore reads). A leg builds `new PathPlan(leg.level, RegionGrid.of(leg.level), startFloor,
leg.goalFloor, caps, inventory, startMode)` exactly as `replan()` does today. The region cascade,
window sliding, BLOCKED repair, and blacklists all stay per-leg/per-dimension. The route layer never
reaches inside PathPlan; it only consumes `status()` / `currentBlockPlan()` / `onBotMoved` through
the existing `driveToward` plumbing (§8.1).

---

## 4. THE SPLICE PRIMITIVE

> Owner-ratified as a first-class, reusable function. Consumer A: portal legs. Consumer B: the
> background-thread planner (pre-compute the next window/segment from a predicted cell, adopt at the
> settled boundary). Design it once; both consume the same contract.

### 4.1 What is spliced — the level decision

Three candidate levels; the recommendation is driven by **what the follower actually consumes**:

- **BlockPathPlan-level concatenation** (append waypoint lists): WRONG. The follower's `path` is a
  *windowed* `BlockPathPlan` that `PathPlan` swaps by reference identity on every window advance /
  refresh (`lastBlockPlanRef`). A concatenated monolith would be discarded at the first
  `refreshWindow()`, and its second half was planned against terrain ~48+ blocks ahead that the
  window model deliberately refuses to trust.
- **PathPlan-level merge** (one PathPlan spanning both segments): WRONG for dimensions (a PathPlan
  is welded to one `ServerLevel`/`RegionGrid`) and needless intra-dimension (the cascade already
  re-derives its own skeleton suffixes).
- **Seam-level handoff** — RECOMMENDED. The splice is not a data-structure merge at all; it is a
  **guarded handoff between two independently-computed plans at a settled boundary**, plus the
  **edit-context transfer** that makes the second plan's world-model honest. This matches what the
  follower already does at every window swap (reset `waypointIndex`/`lastEditedIndex`/
  `activePlanStep`, re-anchor `planStartFloor`), and it is the exact switch the boundary-gated-replan
  comment in `driveToward` promises the background planner ("generates the NEXT segment from the
  PREDICTED post-move cell … switches at this same boundary; that needs modelling the in-flight
  PathEdits").

So the primitive is a small object, not a function on paths:

```java
// pathfinding/splice/SpliceSeam.java (MC-free except BlockPos; cold)
public final class SpliceSeam {
    final BlockPos predictedStartFloor; // where the later plan believes it starts
    final int      predictedStartMode;  // MODE_STANDING/PRONE/MODE_AUTO seed for the later search
    final EditSnapshot baseline;        // edits the later search must see (possibly EMPTY) — §4.3
    final int      toleranceCheb;       // acceptance radius (blocks), default 3 (= REPLAN_NEAR_TARGET)

    /** Acceptance predicate: may the precomputed later plan be adopted from where the bot actually is? */
    public boolean accepts(BlockPos actualFloor) {
        return chebyshev(actualFloor, predictedStartFloor) <= toleranceCheb;
    }
}
```

plus one new **search input** (§4.3) and one **adoption rule** (§4.5). "Splicing" leg N to leg N+1
and splicing window K to a background-precomputed window K+1 are the same three steps:
**seed → accept → adopt**.

### 4.2 Why the later path needs the earlier path's edits at all

The later plan is computed against `NavGridView(level)` = the world **as it is at plan time**. If the
earlier plan (still being walked) will break/place cells the later plan's search reads, the later
search prices phantom walls / misses real footings. Two timing regimes:

- **Lazy** (plan leg/segment N+1 only after N completes): every one of N's edits is already applied
  (`applyEdits`/`PhaseRunner` executed them) and the live grid was patched by `NavGridUpdater` on the
  `setBlockState` seam. **The grid already contains the edits — no carry needed.** This is why the
  lazy portal route (§4.6) is trivially safe.
- **Eager** (plan N+1 while N is in flight — the bg-thread case, and optional leg-ahead precompute):
  the earlier plan's **not-yet-applied suffix** is invisible to the grid. THAT suffix is the baseline
  the splice must seed.

Edits applied between plan time and adoption time are harmless double-counts: a baseline `PLACED` over
a cell the grid now also reports solid is consistent (same answer either way). The only real hazard is
an edit that was planned but **never happens** (the earlier plan was abandoned mid-move) — and that is
exactly what the acceptance predicate + the existing BLOCKED/refresh machinery absorb: a rejected seam
throws the precomputed plan away wholesale.

### 4.3 Baseline seeding — the mechanism (and its hot-path bill)

New value type + one threaded parameter:

```java
// EditSnapshot: an UNPOOLED flat copy — long[] breaks, long[] places (packed BlockPos.asLong).
// StepEdits live in the per-search EditPool arena and are reset between searches, so the snapshot
// MUST copy out. Built by folding the earlier plan's remaining steps' StepEdits, latest-wins
// (walk the remaining steps in order; first-seen-wins with reversed iteration, same trick PathEdits
// uses). Size: a window path has ≤ ~48 steps × a few cells — tens of longs, one small allocation
// per splice (cold).
```

`BlockPathfinder.findPath(...)` gains an optional `EditSnapshot baseline` (default null → today's
behaviour byte-identical). Integration point: the **per-pop PathEdits rebuild**. Today the search
rebuilds `PathEdits` per pop by walking the popped node's `cameFrom` chain and `add()`-ing each edge's
`StepEdits`. The seed appends **after** the chain walk:

```
pathEdits.reset();
walk cameFrom chain: pathEdits.add(edge.stepEdits);   // unchanged
if (baseline != null) pathEdits.addSnapshot(baseline); // NEW — one call, no-op body when null
```

Because `PathEdits` is **first-seen-wins walking node→start = latest-edit-wins**, adding the baseline
LAST makes the in-search path's own edits correctly shadow the baseline (the baseline is the earliest
history). No change to `kindAt`, no change to any per-READ path, no new branch in the descriptor read.

**Perf accounting (per the performance model — this is the part that needs the A/B protocol):**
- Baseline **absent** (every search today, every lazy-leg search): one `null` compare per pop.
  ~10k pops worst case → ~10k perfectly-predicted branches ≈ noise, but MUST be verified on the full
  JMH suite incl. `SHORT`/`MULTI` (per-search setup is a hot path; the chunk-cache precedent says
  measure, don't assume).
- Baseline **present**: O(|baseline|) `markIfAbsent` per pop. A bg-thread window search is short
  (~30–300 pops) and a baseline is tens of cells → ~10³–10⁴ extra probes per seeded search, only on
  seeded searches. If a future consumer wants big baselines over big searches, the fallback design is
  a second persistent `PathEdits` consulted on primary miss (bbox-gated, so far baselines are ~free)
  — deferred until measured, because it DOES add a per-read branch.

Byte-identical guarantee: with `baseline == null` the code path is today's plus one compare — same
expansion order, same f-values, same paths. A unit test asserts a null-baseline search returns
byte-identical plans; a second asserts a seeded search sees a baseline `BROKEN` cell as air.

`PathPlan` gains the same optional constructor arg and threads it into every `replanBlock()` search
**for the window(s) that can intersect the baseline** — simplest correct form: thread it into all of
the leg's searches; the bbox/latest-wins semantics make far windows pay only the per-pop no-op.

### 4.4 The PathEdits-across-dimensions rule (owner-ratified quirk, spec'd)

**Rule.** When splicing plan B after plan A:

1. **Same dimension** (`A.level == B.level`): B's search MUST be seeded with A's outstanding
   `EditSnapshot` (§4.3). No exceptions — this is the correctness half of the ratification.
2. **Dimension boundary** (`A.level != B.level`): A's edits MAY be dropped from B's seed — cells in
   dimension X do not exist in dimension Y's coordinate space. Dropping is not merely allowed, it is
   *required* (seeding them would poison B with junk cells that happen to share packed coordinates).
3. **Return legs** (leg N in a dimension some earlier leg M < N−1 also edited — the 3-leg highway's
   leg 3 vs leg 1): the seed for leg N is the union of **all earlier legs' snapshots in N's own
   dimension** — i.e. the rule is *per-dimension carry*, not *adjacent-leg carry*. Implemented as the
   route's `EditLedger: Map<ServerLevel, EditSnapshot-accumulator>`; leg completion folds the leg's
   outstanding snapshot into its dimension's ledger entry; leg start seeds from
   `ledger.get(leg.level)`.

**Correctness argument.** (2) is sound because a `PathEdits` entry is a bare packed coordinate with no
dimension tag; across a level change every read in the new level is of a different world, so any carry
is a category error, not a conservatism. (1)+(3) are sound because the ledger reconstructs exactly the
"world as it will be" that a single-dimension chain of splices would have seen — the union of all
edits committed-but-possibly-unapplied in that dimension, shadowed latest-wins by the active search's
own path edits.

**The "far away on return" trap (stated as required).** The tempting simplification — "drop
*everything* at a dimension boundary because a return leg lands far from the outbound edits" — is
**unsound**. A nether shortcut can return the bot arbitrarily close to leg 1's territory: two
overworld portals 40 blocks apart pair to nether portals 5 blocks apart; leg 1 might have bridged a
ravine (placed blocks) or holed a wall (broken blocks) within 20 blocks of leg 3's start.
Failure mode if dropped: leg 3, planned eagerly during leg 2, prices leg-1's broken wall as solid
(detour; merely suboptimal) or — worse — plans across leg-1's *planned-but-not-yet-applied* bridge as
if it will not exist, when in the interleaved-execution future it will (or vice versa: relies on
terrain leg 1 is about to remove; the bot walks to a hole).
Guards, cheapest adequate first:
- **Lazy mode (Phase 1–2, §9): no guard needed** — the ledger is only consulted by eager planning,
  and lazy legs plan after all prior edits are physically applied + grid-patched.
- **Eager mode: carry-within-same-dimension unconditionally** (the ledger as spec'd above). Runtime
  cost is the per-pop `addSnapshot` of a few dozen cells; there is no distance heuristic to get wrong.
  This is the recommended default.
- If a measured workload ever makes same-dimension carry expensive (it shouldn't — snapshots are
  tiny), the fallback is a **bbox-distance gate**: skip the seed when
  `chebyshevDist(snapshotBbox, bbox(legStart, legGoal) + CUBOID_CAP_MARGIN) > 0` — i.e. carry unless
  the search's maximal read region can intersect the edits. That gate is *conservative* (bbox
  containment over-approximates reads) and therefore safe; but it is bookkeeping we should not buy
  until forced.

### 4.5 Seam validity — acceptance predicate + fallback

The later plan's start is a **prediction**: for the bg-thread case, the end cell of the current
window (reliable); for a portal transit, the predicted exit anchor (unreliable — vanilla may pick a
different portal within its search radius, create a fresh one, or the pairing may have changed).

- **Predicate**: `seam.accepts(actualFloor)` = Chebyshev ≤ `toleranceCheb`. Default **3**, matching
  `PathPlan.REPLAN_NEAR_TARGET` (the same "close enough that the window machinery self-corrects"
  radius, deliberately smaller than a region so a parallel-tunnel arrival is rejected). Portal
  transits may widen this to the vanilla exit-portal spread (§5.3) — the exit *column* is what we
  predicted; landing anywhere on the frame's 2–3 standable cells is fine, so **portal seams use
  tolerance = 4** (portal frames are ≤ 23 blocks; observed pairings collapse this to exactness).
- **On accept**: adopt the precomputed plan (below) and, for portal seams, **record/refresh the
  observed pairing** (§5.2) — acceptance is itself the pairing sensor.
- **On reject**: discard the precomputed leg plan entirely and **replan the leg from the actual
  cell** (`new PathPlan(level, …, actualFloor, leg.goalFloor, …)`) — which is precisely what the lazy
  path does anyway, so the fallback is the primary path of the simpler mode; also record the observed
  pairing (the *reject* case is the informative one — it means our prediction was wrong and we now
  know the truth). Never try to "repair" a rejected plan; PathPlan construction is the already-priced
  recovery unit (one region A\* + one windowed block search).

**Adoption** (the third half of the primitive) reuses the follower's window-swap mechanics verbatim:
set `path`/`lastBlockPlanRef` to the adopted plan's current block plan, zero `waypointIndex`, reset
`lastEditedIndex`/`activePlanStep`, `planStartFloor = settledFloor = actualFloor`, clear
`stuckTicks/stallTicks/offTrackTicks` (the startup-hop lesson: fresh plan ⇒ fresh recovery counters).
Adoption may only happen at a **settled boundary**: post-teleport (the bot is standing in the exit
portal — settled by construction, `onLevelChanged` just ran) or at `blockPosition().below() ==
settledFloor` for intra-dimension splices. This preserves the boundary-gated invariant: we never
switch plans from a cell the bot is transiently passing through.

### 4.6 Lazy vs eager legs — the recommendation

**Phase 1–3 run legs LAZILY**: build leg N+1's PathPlan in `onLevelChanged` (or on leg completion),
from the bot's **actual** floor. Then: no ledger, no baseline, no acceptance predicate beyond "did we
end up somewhere sane" — the splice degenerates to seed=EMPTY + accept=always + adopt. The full
primitive (ledger + seeding + acceptance) is built and unit-tested in Phase 1 but first *exercised*
by the bg-thread arc and the optional leg-ahead precompute (Phase 5). This sequencing is deliberate:
the portal route ships without waiting on the eager machinery's in-game verification, yet the
primitive exists from day one with its contract locked.

---

## 5. Portal knowledge & pairing

### 5.1 Discovery (exists)

`NetherPortalIndex` already records every NETHER_PORTAL cell per level, fed by full-section classify +
incremental patch + unload evict. Two additions, both cold:

- An **iteration surface** for the estimator: `forEach(level, LongConsumer)` or
  `nearestN(level, from, n, out)` — the estimator needs "all candidate entry portals", not just the
  single nearest. Portals are rare; the linear scan stays.
- **Column canonicalization**: pairing keys must be stable across the 6-cell interior of one portal.
  Canonical key = the portal column's **bottom cell** (the existing descend-to-bottom loop), further
  canonicalized to the minimum-(x,z) bottom cell of the connected portal-cell cluster in that chunk
  (a 4×3 frame has 2–3 columns; walk the recorded cells — they're in one `long[]` already). One small
  cold function, `PortalKey canonical(level, cell)`.

### 5.2 Observed pairing — the linkage

```java
// worldmodel/pathing/PortalPairings.java — memory-lifetime, like NetherPortalIndex
Map<ServerLevel, Map<Long /*canonical entry cell*/, Exit>> pairs;
record Exit(ResourceKey<Level> destDim, long canonicalExitCell, long observedGameTime) {}
```

- **Feed**: when a transit completes (`onLevelChanged` during an ENTER — the route layer's or
  FOLLOW's), we know (entry level, `portalTarget`) and can read the arrival portal as the nearest
  `NetherPortalIndex` cell to the bot's landing position (the bot is standing in it; radius ≤ 2).
  Record **both directions** (entry→exit and exit→entry) — vanilla pairing is symmetric in practice
  even though not guaranteed, and the reverse record is exactly what the return leg wants; a later
  observation that contradicts it simply overwrites (`observedGameTime` newest-wins).
  - Arrival-chunk race: the exit chunk's nav data may not be built yet at the landing tick (the index
    is fed by the nav build). Defer the record up to ~5 s (a tiny pending record retried per tick
    until the index resolves a portal cell near the landing point, then dropped).
- **Invalidation**: piggyback the index's eviction — when `NetherPortalIndex.removeCell/remove/clear`
  drops a portal, drop pairings whose entry or exit references it (same call sites; the pairing table
  is small, linear sweep fine).
- **Unpaired prediction** (no observation yet): the vanilla 1:8 mapping.
  Overworld→nether: `(⌊x/8⌋, clamp(y, netherBounds), ⌊z/8⌋)`; nether→overworld: `(8x, y, 8z)`.
  Vanilla then snaps to the nearest existing portal POI within a **16-block horizontal radius in the
  nether / 128 in the overworld** (verify per-version at implementation — this constant only shapes
  the *estimator's* error bar and the seam tolerance, never correctness: the acceptance predicate +
  observed-pairing recording absorb whatever vanilla actually does, including creating a brand-new
  portal). Predicted exits get seam tolerance = the search radius they were predicted under, capped
  sanely (a predicted overworld exit uses a wide-accept: anywhere within ~128 of the mapped point is
  "the pairing worked"; the *next* trip uses the now-observed exact pairing with tolerance 4).
- **Persistence**: out of scope (not trivial: it needs a save-lifecycle seam neither NavStore nor the
  index has — both are rebuild-on-load by design; pairings likewise rebuild by observation).

### 5.3 What the route layer does with no known portal

Same as today: FOLLOW's fallback ("I don't know a portal…") — hold + tell the owner. Building a
portal (place obsidian + light) and POI-querying unloaded chunks are explicitly future arcs. The
estimator simply produces no portal candidate and the gate picks direct (or gives up for a
cross-dimension goal).

---

## 6. The break-even gate

### 6.1 Placement

The gate runs **above the region tier**, once per route selection (new goal, or route failure
re-selection) — never per tick, never inside any search. It is pure closed-form arithmetic over
portal positions (O(#known portals), and portals are rare). The region cascade's own long-range
estimate (`HierarchicalRegionPlan`'s coarse levels) is *within one dimension* and terrain-aware;
the gate is *across dimensions* and terrain-blind. They compose, they don't compete: the gate picks
the leg decomposition; each leg's PathPlan then does its normal cascade, and a leg that proves
unreachable (FAILED) feeds back as a route failure (§8.3), the terrain-honest correction to the
gate's geometric optimism.

### 6.2 The estimator (real constants)

Let `w = Traverse.FLAT_COST = 4.633` t/block (the bot never sprint-walks — the same conservatism the
octile heuristic uses, so estimates are consistent with what searches will report). Distances are
straight-line horizontal (`dist`), computed in each leg's own frame.

**Portal transit overhead `T`** (one crossing):
```
T = enter maneuvering (~30 t: PORTAL_ENTER_DIST approach + face + walk-in)
  + portal wait        (80 t survival — Player.getPortalWaitTime; ~1 t if abilities-invulnerable,
                        i.e. when survival.takesDamage=false — read the live config, don't hardcode)
  + arrival re-anchor  (~40–90 t observed budget: onLevelChanged clearPlan + nav build of the exit
                        chunks at MAX_BUILDS_PER_TICK=8 + first replan)
→ default constant  T ≈ 200 ticks  (10 s), config knob pathing.portalTransitTicks.
   (For an invulnerable bot T ≈ 120; derive as  T_base(120) + (takesDamage ? 80 : 0).)
```

**Candidate costs** for goal G, bot at B:

- Direct (same dimension only): `C_direct = w · dist(B, G)`.
- One-way (G in the other dimension), via entry portal `e` with (observed or predicted) exit `x`:
  `C_2leg(e) = w · dist(B, e) + T + w · dist(x, G)`.
- Highway (G in B's dimension), via entry `e1`→exit `n1` (nether) and nether entry `n2`→exit `x2`:
  `C_3leg(e1, n2) = w·dist(B, e1) + T + w·dist(n1, n2) + T + w·dist(x2, G)`.
  With well-paired portals `dist(n1, n2) ≈ dist(e1, x2)/8` — the 8× discount is *in the coordinates*,
  never a scale factor in the formula (matching PRD §6.5: real costs, 8:1 only via frame mapping).

**Selection**: enumerate known entry portals in B's dimension (linear index scan); for the highway
case also enumerate nether-side exits (known portals in the nether + predicted exits of overworld
portals near G/8). Portal counts are small; if a pathological world makes this quadratic-expensive,
cap each side at the K=4 nearest candidates (nearest-to-B for entries, nearest-to-G/8 for exits).

**Gate**:
```
choose the portal route iff   min C_portal + M < C_direct
where M = pathing.portalRouteMargin (ticks), default 600 (30 s).
Cross-dimension goals skip the gate (C_direct = ∞): pick min C_portal or give up.
```
The margin is **absolute ticks, not a ratio**: the estimator's error sources (terrain detours around
the geometric line, portal wait variance, nav-build pauses, a mis-paired exit) are roughly
per-transit-absolute, and a ratio margin misbehaves on short routes.

### 6.3 Break-even arithmetic (the numbers the knob defaults come from)

Ideal case (entry portal at hand, exit at the goal, perfect 8:1 pairing), portal-to-portal overworld
span `S`:
```
C_direct − C_3leg = w·S − (w·S/8 + 2T) = (7/8)·w·S − 2T = 4.054·S − 2T
break-even (M=0):  S = 2T / 4.054 ≈ 98.7 blocks     (T = 200)
with margin M=600: S = (2T + M) / 4.054 ≈ 247 blocks
```
So the highway engages for trips whose portal-to-portal span exceeds ~250 blocks — comfortably past
"walk it" territory and safely inside "obviously take the nether" territory. Worked example: goal
2,000 blocks away, entry portal 150 from the bot, exit 60 from the goal, ideal pairing
(`dist(n1,n2) ≈ (2000−150−60)/8 ≈ 224`):
```
C_direct = 2000 · 4.633                          ≈ 9,266 t  (~7.7 min)
C_3leg   = (150 + 224 + 60) · 4.633 + 2·200      ≈ 2,411 t  (~2.0 min)   → wins by 6,855 t ≫ M
```
One-way example (owner in the nether, 300 blocks nether-frame from the predicted exit; entry portal
120 from the bot): `C_2leg = (120 + 300)·4.633 + 200 ≈ 2,146 t` — vs today's behaviour which walks
to the *nearest* portal regardless of where it comes out; the estimator picking min over entries is
the entire upgrade.

Damage pricing: legs inherit `caps.costPerHitpoint` inside their searches as usual; the estimator
itself stays damage-blind (it can't see hazards it hasn't searched). Fine — the margin absorbs it,
and a hazard-priced leg that blows the estimate feeds back only if it outright FAILs.

---

## 7. END_PORTAL (short — a follow-on, but reserve the bits now)

- **Classification**: today `NavBlock` has NO end-portal identity (only `PORTAL_BIT` = bit 43, tested
  as `block == Blocks.NETHER_PORTAL`). *(see STATUS: bit conflict — bit 44 is now PROTECTED; put PORTAL_KIND
  at bits 45–46, not 43–44.)* Bits 44–63 are free and navtypes sit at ~587/1024, so widen
  bit 43 into a **2-bit PORTAL_KIND field (bits 43–44)**: 0 none / 1 nether / 2 end
  (`Blocks.END_PORTAL`) / 3 end-gateway (`Blocks.END_GATEWAY`, future). `isPortal(d)` (the nether
  test every current consumer means) becomes `kind == 1` — still one mask+compare; add
  `portalKind(d)`. Adds ~2 navtypes. The `NetherPortalIndex` collector's palette gate tests
  `kind != 0` and stores the kind alongside (or a parallel `EndPortalIndex`; decide at
  implementation — the index value arrays are packed positions with 12 spare high bits… no: y uses
  bits 0–11; the kind can ride bits 62–63 of the packed cell if we want one index).
- **Pairing is degenerate**: overworld→End always lands on the obsidian platform (fixed ~(100, 49, 0)
  in the End); End→overworld (the central exit portal at (0, ~63, 0)) lands at the bot's
  spawn point. Both are *constants per world*, not observations — the pairing table just hardcodes
  the two exits (still recorded as observations for uniformity).
- **ENTER behaviour**: end portals teleport **instantly on contact** (no 80-tick wait, no
  `portalWaitTime`) and the portal block sits *below* the feet — the bot walks/falls onto it. The
  existing ENTER state works with target = the portal cell and the stand-in-column branch never
  reached (teleport fires first); set `T_end ≈ 120` (no wait component). The Dragon fight, platform
  dig-out (you can arrive encased), and End-city routing are explicitly out of scope.

---

## 8. Execution & failure handling

### 8.1 The route driver

A small `RouteDriver` owned by `AllyBotEntity` (cold, tick-level). It does NOT replace `driveToward`;
it sits where `followThroughPortal()` sits today — consulted first in COME/FOLLOW/GOTO handling:

```
tick():
  if route == null or goal moved materially → select route (§6; rate-limited, see below)
  switch (route.state):
    LEG_PATHING:  driveToward(leg.goal…) exactly as today (same PathPlan plumbing, same debug chat);
                  on arrival/proximity to a PORTAL_ENTRY goal → state = TRANSIT (EnterPortalAction)
    TRANSIT:      enterAction.tick(); onLevelChanged (detected post-doTick as today) →
                  record pairing; cursor++; splice-adopt or lazily build next leg (§4.5/§4.6);
                  state = LEG_PATHING.  ENTER give-up → §8.3.
    DONE:         (FINAL leg complete) — COME settles to STAY, FOLLOW keeps re-selecting.
```

**Goal-motion rule (FOLLOW)**: a route is built for a goal *snapshot*. Rebuild the route only when
(a) the owner changed dimension, or (b) the owner moved out of the final leg's goal *region*
(`sameGoalRegion` — the exact hysteresis `driveToward` already uses to avoid skeleton flip-flop),
or (c) a route failure. Within-region owner motion is tracked by the final leg's PathPlan as today.
Rate-limit re-selection to ≥ 40 ticks (mirroring `REPLAN_TICKS`).

### 8.2 Replan semantics per leg, and what "boundary-gated" means across legs

*Within* a leg nothing changes: the leg's PathPlan does its boundary-gated window commits, 40-tick
refreshes, BLOCKED blacklist-repair, and the stall/off-track escape hatches, all anchored on
`settledFloor` in that leg's level.

*Across* legs, the transit **is** the boundary: the bot stands settled inside a portal column
(zza=0), vanilla teleports it, `onLevelChanged` runs — so the next leg's plan is always built (lazy)
or adopted (eager, acceptance-gated) from a **realized, settled position**, never mid-move. This is
the cross-dimension instance of the same invariant the boundary-gated replan enforces
intra-dimension, and it is why the route layer needs no new anti-transient machinery: `settledFloor`
is re-seeded by leg-plan construction exactly as `replan()` re-seeds it today.

One asymmetry to preserve: `onLevelChanged` currently drops ALL plan state unconditionally — correct
for legs too (the dropped state belonged to the old level), but the route cursor and ledger live on
`RouteDriver`, *outside* the per-level state, and must survive it. An **unexpected** level change
(the bot grazed a stray portal mid-leg — NavBlock v1 deliberately leaves portal cells passable) is
detected as `levelChanged && route.state != TRANSIT`: treat as a route failure → re-select from
wherever we are (which may well decide "walk back through"). This also finally gives the
accidental-graze case a recovery story (flagged as a known gap in `NavBlock.isPortal`'s Javadoc).

### 8.3 Failure ladder

| Failure | Detector | Response |
|---|---|---|
| Leg PathPlan BLOCKED | existing `repairStep` | existing cascade blacklist-repair (unchanged) |
| Leg PathPlan FAILED / gave up | existing `giveUp` path | **route-level**: blacklist this route's entry portal choice (in-memory `RouteBlacklist`, cleared on new goal — the route analog of `RegionEdgeBlacklist`), re-run selection; if no candidate remains → direct if same-dimension else hold + one chat line |
| ENTER timeout ×2 | existing retry/backoff exhausts | blacklist the portal (+ drop its pairing), re-select |
| Seam rejected (wrong exit) | `SpliceSeam.accepts` false | record TRUE pairing, discard precomputed plan, replan leg from actual floor (lazy path; §4.5) |
| Unexpected dimension change | `levelChanged && state != TRANSIT` | route re-selection from current position |
| Estimate badly wrong (leg cost ≫ estimate) | none (deliberate) | absorbed by margin M; do NOT build a mid-route re-estimator — the failure ladder above catches the hard cases and estimate-chasing causes oscillation (the same reasoning as commit-to-the-skeleton) |

Owner-visible surfaces: one chat line on route selection under `Debug.ENABLED`
(`route: 3 legs via portal (x,y,z) — est 2411t vs direct 9266t`), the existing per-leg progress
chatter unchanged, and a `/bot route` dump (legs, pairings used, estimates) alongside `/bot probe`.

### 8.4 The unverified fake-player portal runtime checklist — the gating dependency

The s42-arc1 commit (`21f68d3`) and HANDOFF both flag an **"8-item fake-player runtime checklist,
never exercised in-game."** The checklist text itself lived in the previous session's HANDOFF.md
(gitignored, since overwritten) and is not recoverable from git; reconstructed here from the code's
own commentary (`AllyBotEntity` portal-seek/ENTER Javadoc, `onLevelChanged`, `MoveReport`/`ClientLoad`
seams, BotManager spawn notes) — re-derive against the original author's intent if he remembers it:

1. **Portal marking**: `NetherPortalBlock.entityInside` marks a server-side-only `ServerPlayer` and
   the portal process ticks inside its `baseTick` (no client input required).
2. **Wait time**: survival bot stands the full ~80-tick `getPortalWaitTime`; an
   abilities-invulnerable bot (~1 tick) doesn't out-race the ENTER state's bookkeeping.
3. **Packet burst**: the dimension-change path (respawn-like packet storm: level info, chunk center,
   player abilities…) is fully absorbed by `FakeClientConnection` without NPE/hang.
4. **Instance identity**: vanilla dimension change keeps the SAME `ServerPlayer` instance for players
   — `BotManager`'s UUID→entity map stays valid; no ghost/duplicate entity remains in the old level.
5. **PlayerList/level bookkeeping**: bot leaves the old level's player list, joins the new one, and
   **anchors chunk loading around itself** in the new dimension (without a real client the chunk
   tracking may differ — this feeds nav-data availability for leg N+1).
6. **ClientLoad re-arm**: 1.21.11+ resets `hasClientLoaded()` on teleport → without
   `ClientLoad.markLoaded` in `onLevelChanged` the bot goes permanently invulnerable (coded, unverified).
7. **MoveReport teleport-tick guard**: the forged move report is skipped on the teleport tick so a
   cross-dimension position delta can't fake lethal fall damage (coded, unverified).
8. **Destination portal creation**: when no exit portal exists, vanilla's PortalForcer creates one
   for the fake player; the new portal's chunk nav-builds and lands in `NetherPortalIndex`.

**Dependency statement**: every phase below except Phase 1 (the splice primitive, headless) is gated
on this checklist passing. It is therefore **Phase 0**, and it needs zero new code — the existing
FOLLOW portal-seek exercises items 1–8 (walk the owner into the nether and back on 26.2, once with
`survival.takesDamage=true` and once false, plus one no-exit-portal run for item 8). Items 4–5 are
the likeliest to surface real work (some versions recreate the ServerPlayer on dimension change or
stall chunk tracking for clientless players); if item 4 fails, `BotManager` needs a re-registration
hook off the level-change event — scope that only if observed.

### 8.5 Config

`config/orebit.properties`, pathing group (all read at route selection, never hot):

| Key | Default | Meaning |
|---|---|---|
| `pathing.portalRoutes` | `false` until Phase 0+3 verify, then `true` | master switch; off = today's behaviour exactly |
| `pathing.portalTransitTicks` | `120` (+80 added when `survival.takesDamage`) | the §6.2 `T` |
| `pathing.portalRouteMarginTicks` | `600` | the §6.2 `M` |

---

## 9. Phasing (smallest shippable slice first)

**P0 — Verify the fake-player portal checklist (no new code).** In-game on 26.2 (+ one mc-1.21-era
spot check): FOLLOW through a portal both directions, damage on/off, no-exit-portal case. Fix
fallout. *Verification story: the checklist itself, checked off one by one; this is also the first
real exercise of the s42 ENTER code.*

**P1 — The splice primitive, headless.** `EditSnapshot`, `SpliceSeam`, the `baseline` parameter
through `findPath` + `PathPlan`, ledger type. Unit tests: null-baseline byte-identity; seeded search
reads baseline BROKEN as air / PLACED as floor; latest-wins shadowing (path edit over baseline);
ledger per-dimension isolation. JMH: full suite incl. `SHORT`/`MULTI` paired A/B for the one
per-pop branch (expected ≈0; revert-without-sentiment applies). *No in-game story needed — nothing
consumes it yet.* Design-review the mechanism with the owner first (standing rule).

**P2 — Pairing observation.** `PortalPairings` + canonical keys + record-on-teleport (wired into the
existing FOLLOW transit), index-eviction invalidation, deferred-record for the arrival-chunk race.
*Verification: walk a portal round trip; `/bot route`-style dump (or log) shows the pairing both
directions; break a portal, see the pairing evicted.*

**P3 — 2-leg one-way route (the first shippable behaviour).** `RouteDriver` + `EnterPortalAction`
extraction + lazy leg handoff (splice in degenerate lazy mode) + estimator restricted to
cross-dimension goals (min over entries — no gate needed). Subsumes `followThroughPortal` for the
owner-elsewhere case (better: it now picks the portal whose *exit* is near the owner). `/bot goto`
works cross-dimension. *Verification: owner in the nether 300+ blocks from the exit; `/bot come`
routes via the estimator-chosen portal, replans leg 2 on arrival, reaches the owner. Regression: the
P0 scenarios still pass through the new driver.*

**P4 — Break-even gate + 3-leg highway + failure ladder.** Full estimator, margin knob, route
blacklist, unexpected-dimension-change recovery, `/bot route` diagnostic. *Verification: A/B a
~2,000-block overworld trip with paired portals against `pathing.portalRoutes=false` — expect the
§6.3 ≈4× wall-clock win; sabotage the exit portal mid-route and watch the ladder re-route; goal 100
blocks away must still choose direct.*

**P5 — Eager splice consumers (follow-on arcs).** Leg-ahead precompute using the ledger + acceptance
predicate (the portal-route proof of the eager mode), then the background-thread pathfinder consuming
the identical seam at window boundaries. END_PORTAL classification (bit widening can land any time;
it's additive) + End ENTER. *Verification per its own arc.*

---

## 10. Risks / open questions

1. **Checklist item 4/5 (instance identity, chunk anchoring)** is the one place vanilla behaviour for
   clientless players is genuinely uncertain across 1.17→26.x; it can invalidate the transit design's
   "same entity, keep driving it" assumption. P0 exists to surface this before any route code is written.
2. **Nav-data latency in the destination dimension**: leg N+1's first window may sit on unbuilt
   chunks for a few ticks (`MAX_BUILDS_PER_TICK=8`). Today's machinery already degrades gracefully
   (plan: NONE → straight-line fallback → replan); budgeted inside `T`. If it proves worse in-game,
   pre-warm by ticking `ChunkNavLoader` for the exit chunks during the portal wait (deferred).
3. **Estimator blindness to terrain** (an ocean/ravine on the geometric line): absorbed by M and the
   failure ladder; do not add terrain probes to the estimator (that's what legs are for).
4. **Predicted-exit Y**: the 1:8 map gives no useful Y. Estimator uses the entry's Y clamped to the
   destination's build range; error is bounded by world height and only skews `dist(x, G)` mildly.
   Observed pairings erase it.
5. **`Level`/`ServerLevel` keying of the ledger and pairings** must use the same identity discipline
   as `NetherPortalIndex` (ServerLevel identity map; entries dropped with the level) — watch the
   already-noted unwired level-unload seam (`NavStore.clear` counterpart).
6. **Portal-graze teleports** (passable portal cells in paths): §8.2 gives recovery; the eventual
   fix (subtract portal cells from walker passability, or price them) is a separate small design —
   note it interacts with routing (a portal cell ON the planned route is sometimes exactly what we
   want the bot to touch).
