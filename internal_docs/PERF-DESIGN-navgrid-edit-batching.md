# PERF DESIGN — block-edit → NavGrid batching (dirty-cell defer + dedup)

> **STATUS: PROPOSED.** Design-review input per the CLAUDE.md perf process (mechanism + invariants +
> expected win + risk). No code has been written. Honest headline up front: at the measured
> ~1.4–2.1 µs/patch, vanilla-scale play spends **≤0.2% of the 50 ms tick** on nav-grid maintenance —
> full batching is worst-case **spike insurance**, not a median win. The one component that looks
> unconditionally worth taking is **Phase 0 (§4.1): the navtype no-op early-out**, which also kills
> spurious `editEpoch` bumps (each of which currently costs every bot on the level a full window
> re-search at its next recheck boundary — milliseconds, not microseconds). Recommendation in §5.

---

## §1 Current mechanism + cost accounting

### §1.1 The hook chain

Every block change in a loaded chunk flows through one mixin:

- `overlays/1.17/java/com/orebit/mod/mixin/LevelChunkMixin.java:40-48` — `@Inject` at `RETURN` of
  `LevelChunk.setBlockState(BlockPos, BlockState, boolean)`; fires
  `BlockChangeEvents.fire(level, pos, oldState, newState)` at line 47 (null return = no-op change,
  skipped at line 46). The 1.21.5+ signature era (`boolean isMoving` → `int flags`) is
  `overlays/1.21.5/java/com/orebit/mod/mixin/LevelChunkMixin.java:35`.
- Two listeners register on that seam at init (`OrebitCommon.java:93` and `:101`):
  - `NavGridUpdater.onBlockChanged` (`worldmodel/pathing/NavGridUpdater.java:51-86`) — the block
    tier. **Synchronous, per block change**: tracked-chunk check (`:57-60`), epoch bump (`:63`),
    portal-index maintenance (`:71-77`), then one full
    `NavSectionBuilder.patchCell(section, above, below, lx, ly, lz, newState)` (`:85`).
  - `HpaMaintenance.onBlockChanged` (`worldmodel/hpa/HpaMaintenance.java:215`) — the region tier.
    **Already batched**: it only adds the containing leaf's packed key to a per-level concurrent
    dirty SET (`HpaMaintenance.java:94`, dedup rationale in its class doc lines 33-43) and recomputes
    at most `MAX_LEAVES_PER_TICK = 8` leaves (`:86`) in `HpaMaintenance.flush`, driven from
    `events.onWorldTickEnd(HpaMaintenance::flush)` (`OrebitCommon.java:107`). The block tier is the
    outlier: the same event volume, patched inline.

### §1.2 The `editEpoch` counter (s52) — what it actually guards

`NavGridUpdater.EDIT_EPOCH` (`NavGridUpdater.java:38`) is a per-`ServerLevel` `int[1]` in a
`WeakHashMap`, incremented **once per patched cell**, unconditionally, at `NavGridUpdater.java:63`:

```java
// A tracked cell is about to be patched — the world visibly changed for every plan over this level.
EDIT_EPOCH.computeIfAbsent(server, l -> new int[1])[0]++;
```

Its ONE consumer is the follower's **terrain-recheck debounce**, not async staleness detection:
`AllyBotEntity.java:1322-1326` reads `NavGridUpdater.editEpoch(level)` at the settled boundary and
skips the periodic window re-search when the epoch is unchanged since the plan's last search
(`lastRefreshEditEpoch`, set at `AllyBotEntity.java:339/:1547/:1325`). The documented contract
(`NavGridUpdater.java:27-37`): *unchanged epoch ⇒ no built nav cell changed ⇒ the re-search would be
byte-identical ⇒ skip it*. Async searches do NOT read `editEpoch`; their staleness machinery is the
`NavReclaim` epoch (section lifetime, drained at `OrebitCommon.java:79`) plus settled-boundary
adoption plus this same epoch-gated periodic re-search on the server thread. Tick-thread confined by
declaration (`NavGridUpdater.java:32-33`), no synchronization.

Two properties matter for batching:

1. The epoch is a **may-have-changed** signal, deliberately coarse (level-global, includes the bot's
   own edits — documented at `:33-36`). Over-bumping is correct but expensive (a wasted re-search per
   bot); under-bumping (missing a real grid change) breaks the debounce contract.
2. It bumps **even for navtype-invariant state changes** (a repeater ticking, a crop growing a
   stage): the only no-op filter upstream is the interned-reference check `oldState == newState` at
   `NavGridUpdater.java:53`. NavBlock collapses ~28k states into ~587 navtypes (~48×), so a large
   class of real-world block changes — redstone power levels, crop ages, fence connections —
   changes the BlockState but NOT the navtype, yet today pays a full patch AND arms every bot's
   re-search.

### §1.3 What ONE `patchCell` costs

`NavSectionBuilder.patchCell` (`NavSectionBuilder.java:480-519`), per call:

| Step | Work | Where |
|---|---|---|
| navtype write | 1 `grid.set` | `:489` |
| **descriptor scratch rebuild** | **≈4,864 table reads** (4096 cells + 3×256 overscan rows) | `fillScratch`, `:491`, cost note `:342-348` ("≈4.8k descriptor-table reads") |
| flags window recompute | ≤45 cells (x±1 × y−3..y+1 × z±1) × `NavFlags.compute` | `recomputeWindow`, `:498`, window bounds `:592-600` |
| below-seam pass (only `ly < 3`) | a SECOND full `fillScratch` + a second window | `:502-506` |
| floorGap fixpoint | ≤15 cells up, ≤1 seam, early-out | `patchFloorGap`, `:516`, `:532-548` |
| runUp fixpoint | own cell + ≤15 down, ≤1 seam, early-out | `patchRunUp`, `:517-518`, `:556-588` |

Measured total: **~1.4–2.1 µs/patch** (`PatchStormBenchmark.java:37`; TOGGLE ≈ the fixed-overhead
floor, SEAM ≈ the double-scratch ceiling). The javadoc at `:475-478` already flags the scratch
rebuild as the windowable-later item ("It can be windowed to the affected neighbourhood later if it
ever profiles hot"). The TOGGLE→SEAM delta (~0.7 µs, dominated by the second `fillScratch` + second
window on seam rows) suggests one scratch fill is on the order of **0.4–0.8 µs — i.e. roughly a
third to a half of a typical patch**. That share is a *hypothesis* (per the perf-model rule) — the
§6 BATCH benchmark is what would confirm it.

### §1.4 Where the drain infrastructure already exists

A per-level-tick drain cadence is already wired three ways at `OrebitCommon.init`:
`events.onWorldTickEnd(level -> NavReclaim.tick(...))` (`OrebitCommon.java:79`),
`ChunkNavLoader`'s budgeted build queue (`:87`), and `events.onWorldTickEnd(HpaMaintenance::flush)`
(`:107`). A block-tier drain slots into the same seam with zero new platform surface.

---

## §2 The double-work analysis (adjacent cells, same tick)

**Yes — the double work is real, and it is the scratch rebuild, not just the window overlap.**

Concrete case: a piston pushes a row, changing A=(8,8,8) and B=(9,8,8) in the same section in the
same tick. Two `patchCell` calls run back-to-back:

1. `patchCell(A)`: full `fillScratch` (≈4.9k reads, `:491`) + `recomputeWindow` over x∈7..9,
   y∈5..9, z∈7..9 (45 cells, `:592-600`) — **including B's column**, computed from B's OLD navtype
   (B hasn't fired yet).
2. `patchCell(B)`: rebuilds the ENTIRE scratch again (same 4.9k reads, now differing in one entry)
   + recomputes x∈8..10 — of its 45 cells, the 30 in x∈8..9 were just written by step 1 and are
   recomputed here (correctly this time, with B's new navtype).

So per adjacent pair: **one fully redundant 4.9k-read scratch fill + ~30 redundant
`NavFlags.compute` calls**, and the first patch's work on the shared cells was computed against
stale neighbor state and thrown away. This is pure waste, not a bug — the later patch always
repairs the earlier one's stale-neighbor flags, so the sequential end state is correct (each
`patchCell` leaves the grid fully consistent; see the seam/fixpoint contracts at `:454-478` and
`:508-515`).

Scaling it: a 12-block piston row within one section pays **12 scratch fills where 1 would do**
(~59k scratch reads vs ~4.9k) plus ~330 window-cell recomputes where ~150 unique cells suffice. The
depth fixpoints do NOT meaningfully double-work laterally (they are per-column, ≤31 cells, with
early-out — `:516-518`); only same-column multi-change ticks (a dug shaft) re-walk them, and the
early-out caps that.

---

## §3 Event-volume estimates (and the honest denominator)

Per-patch cost 1.4–2.1 µs; tick budget 50 ms. Using the 2.1 µs ceiling:

| Scenario | fired changes/tick | patch cost/tick | % of 50 ms |
|---|---|---|---|
| Vanilla ambient (crops, fluids, mob griefing, a player mining) | ~5–50 | 10–105 µs | **≤0.2%** |
| One piston extension (12 pushed blocks × ~2 cells + head/base, B36 intermediates) | ~25–50 | ~50–105 µs | ~0.2% |
| 20-piston contraption (a big door) firing at once | ~500–1,000 | ~1–2 ms | 2–4% |
| Single TNT (power 4, ~30–70 blocks) | ~30–70 | ~60–150 µs | ~0.3% |
| 100-TNT chain detonating in one tick | ~5,000 | ~10.5 ms | **~21% (one-tick spike)** |
| TNT-duper quarry / industrial redstone (the `PatchStormBenchmark` "hostile storm" ×2–4) | 10k–20k | 21–42 ms | tick-breaking |

Being honest against the ratio-not-absolute rule: **everything above the TNT-chain row is outside
V1's stated scale** (one ally bot per player, vanilla-ish survival). The steady-state cost of the
current per-change design is a fraction of a percent; the current design is *fine* for the product
as specced. The cases that motivate batching are (a) one-tick TNT/piston spikes where 10–40 ms of
synchronous patching lands inside a single tick, and (b) the `editEpoch` side effect: a redstone
clock anywhere on the level bumps the epoch every tick, permanently defeating the s52 debounce and
re-arming a full window re-search per bot per `TERRAIN_RECHECK_TICKS` — that re-search costs
milliseconds, i.e. **the epoch pollution is worth more than the patch time itself**.

---

## §4 Proposed design

Three graduated pieces. Phase 0 is independent of batching and is where most of the practical value
is; Phases 1–2 are the actual defer/batch machinery.

### §4.1 Phase 0 — navtype no-op early-out (standalone, recommended regardless)

In `NavGridUpdater.onBlockChanged`, after the tracked-section lookup and BEFORE the epoch bump
(`:63`):

```java
final short newNavtype = NavBlock.navtypeFor(newState);
if ((newNavtype & 0xFFFF) == section.getTraversalGrid().navtype(lx, ly, lz)) return; // grid-invisible
```

Correctness: the grid is patched synchronously today, so the resident navtype IS the navtype of the
previous state; equal navtype ⇒ equal descriptor ⇒ identical flags/depth/portal-bit inputs ⇒
`patchCell` would recompute byte-identical values, and the epoch contract (*unchanged epoch ⇒
re-search byte-identical*, `NavGridUpdater.java:29-31`) is exactly satisfied by NOT bumping. Cost:
one `navtypeFor` map lookup (already paid inside `patchCell` at `:484` — it moves, it doesn't add)
plus one grid read + compare. Wins: redstone-state churn, crop ticks, and fence-connection updates
stop paying patches entirely AND stop defeating the s52 debounce. The portal check at `:71-77` is
subsumed (same navtype ⇒ same portal bit). This piece is measurable on a new REDSTONE bench shape
(§6) and carries essentially zero risk.

### §4.2 Phase 1 — the dirty-cell queue (defer to a drain point, dedup per cell)

**Data structure** (per the Hot-Path-No-Heap-Alloc rule — no `Map<Long,Short>` boxing; contrast
`HpaMaintenance`'s boxed `Set<Long>`, acceptable there at ≤ leaf granularity, not at per-cell storm
volume):

- Per-`ServerLevel` `PendingPatches` struct, held in a `WeakHashMap<ServerLevel, PendingPatches>`
  exactly like `EDIT_EPOCH` (`NavGridUpdater.java:38`), tick-thread confined by the same argument
  (`:32-33`; off-thread worldgen fires hit the untracked-chunk early-out at `:57-60` before ever
  touching the queue — same de-facto confinement as today's direct `patchCell`).
- Open-addressed `long[] keys` / `short[] pendingNavtype` (power-of-two, linear probe, grow ×2,
  start 1024 ≈ 12 KB/level), key = `BlockPos.asLong()` (26+26+12 bits — one long, no chunk+cell
  packing problem). Plus an append-order `long[] insertOrder` for drain iteration and O(1)
  "is the queue empty" (`int pendingCount`). Cleared (not freed) at drain.
- Insert-or-overwrite: a re-fired cell overwrites its pending navtype in place — **last-state-wins
  dedup for free**.

**Enqueue path** (replaces `NavGridUpdater.java:63-85`):

1. Tracked checks unchanged (`:52-60`).
2. Compute `newNavtype = NavBlock.navtypeFor(newState)`. Let *effective* = pending value if the
   cell is already dirty, else the resident grid navtype. **If `newNavtype == effective`, return**
   (Phase 0 generalized: an extend-then-retract piston pair in one tick collapses to zero pending
   work — see §4.5 dedup semantics).
3. Portal-index maintenance moves from grid-reads to **event params**:
   `wasPortal = NavBlock.isPortal(NavBlock.descriptorFor(oldState))` instead of reading the (now
   possibly stale-by-one-pending-write) grid at `:71-72`; `nowPortal` already uses
   `descriptorFor(newState)` (`:73`). Event-accurate under deferral, identical today. Stays
   synchronous at enqueue — it is two bit tests, and `NetherPortalIndex` has its own consumers.
4. Store/overwrite `pendingNavtype`, bump `pendingCount` if new. **Do NOT patch.**

**Why store the navtype and not the `BlockState`?** `patchCell`'s signature takes the new state
explicitly and reads NOTHING from the world — `NavSectionBuilder.java:480-484` immediately reduces
it to `NavBlock.navtypeFor(newState)`. So the deferred entry needs only the `short`, keeps the
queue primitive-pure, and avoids pinning `BlockState` refs. This also *verifies the task's dedup
claim with a correction*: a deferred patch does NOT "naturally read the final block state" (it
never reads the world at all) — final-state correctness comes from last-wins overwrite of the
stored navtype, which is equivalent because the navtype is a pure function of the state.

**Drain algorithm — v1 (recommended): dedup-only, reuse `patchCell` verbatim.** For each pending
entry, resolve the section fresh from `NavStore` (chunk unloaded since enqueue ⇒ drop the entry —
never hold `NavSection` refs in the queue, which would fight `NavReclaim` retirement,
`NavStore.java:45/:67/:80`), skip if `pendingNavtype == resident navtype` (the toggle no-op), else
call today's `patchCell(section, above, below, lx, ly, lz-derived, pendingNavtype)` (a trivial
navtype-taking overload of `:480` — the state param's only use is `:484`). Correctness is inherited:
each `patchCell` starts from a fully-consistent grid and leaves one (the existing invariant, §2),
so **any drain order is correct** — this is exactly N real changes arriving in sequence, just later.
No new equivalence proof needed. What v1 buys: per-cell dedup, toggle elimination, no-op
elimination, and the spike moved to one well-defined drain point. What it does NOT buy: the §2
scratch amortization.

### §4.3 Phase 2 (measured-gated) — per-section phased drain (the scratch amortization)

Only if the §6 BATCH benchmark shows the scratch share matters (≥3% on the storm shapes, per the
keep-bar). Group pending cells by section; per section:

- **P1**: write all pending navtypes (`grid.set` with stale flags — the `:489` idiom, extended).
- **P2**: ONE `fillScratch` (`:491`) + `recomputeWindow` per changed cell (or over the union bbox
  if cells are dense); ONE below-seam pass per section-pair when any `ly < 3` cell is pending
  (`:502-506`).
- **P3**: depth repairs per changed cell with two ordering invariants: within a column,
  `patchFloorGap` bottom-up (it propagates upward and seeds from the changed cell's own stored gap,
  which "depends only on cells below" — `:529-530`; a lower pending cell must have propagated first
  or the seed is stale and the fixpoint early-out at `:543` could stop at a coincidental match) and
  `patchRunUp` top-down (mirror argument: runs seed from the cell above, `:559-570`, early-out
  `:583`).

Equivalence argument: flags are a pure function of the final navtype field (`NavFlags.compute` over
the descriptor scratch), and §2 showed sequential patching ends at flags = f(final navtypes) on the
union of windows; P1+P2 computes the same function directly. The depth phases converge to the same
per-column recurrences given the ordering invariants. **Final grid state byte-identical to
sequential; intermediate states differ — made unobservable by §4.4.** This is real new surface with
real proof obligations (the §6 identity guard is mandatory), which is why it is gated behind
measurement rather than bundled.

### §4.4 Sync-read safety — flush barriers, not a fixed drain phase

**The tick-ordering fact that forces the design:** block changes originate in MULTIPLE phases of
`ServerLevel.tick` — scheduled block/fluid ticks and random ticks run before entity ticking; TNT
entities explode and mobs grief DURING entity ticking, interleaved with `AllyBotEntity.tick` in
unspecified entity order; piston block-events and block entities run in yet other phases. The sync
search runs inside the bot's entity tick (`AllyBotEntity.replan` → `new PathPlan(...)`,
`AllyBotEntity.java:1512-1528`; direct `findPath` calls at `:1735-1737`; window searches in
`PathPlan.java:475/:874/:1310/:1580`). So **no fixed drain point can both precede all same-tick
producers and follow them** — a tick-end-only drain would hand this tick's bot searches last tick's
grid, violating the property the whole updater exists for (`AllyBotEntity.java:1507-1509`: "each
window's block search sees current terrain — including the bot's own break/place edits").

Today's inline patch gives every reader *read-your-writes at any instant*. The batching design
preserves exactly that for server-thread readers via **flush-on-read barriers** — `flush(level)`
= drain the level's queue, a no-op costing one `pendingCount != 0` test when empty:

| Barrier | Covers | Cost when clean |
|---|---|---|
| `NavGridView(ServerLevel)` ctor (`NavGridView.java:84`) | every sync block search (all `new NavGridView(level)` sites: `PathPlan.java:475/874/1310/1580`, `AllyBotEntity.java:825/859/1561/1571/1735-1737`, `ProbeCommand.java:66`) | one predicted branch per SEARCH (per-search setup is a guarded hot path — SHORT/MULTI — but this is one static int test against a 6k–40k ns setup bill; the synthetic ctor `NavGridView.java:112` and `overSections` `:127` used by JMH/tests bypass it, so benchmarks are untouched) |
| start of `AllyBotEntity`'s server tick | region-tier reads that do NOT go through `NavGridView` — lazy `LeafCostComputer`/`FragmentLeafComputer` mini-pathfinds triggered during region planning read `NavStore` sections directly | one test per bot per tick |
| `onWorldTickEnd`, registered BEFORE `HpaMaintenance::flush` (i.e. above `OrebitCommon.java:107`; listener order = registration order) | catch-all: `HpaMaintenance.flush`'s leaf recomputes read patched grids; guarantees the queue is empty across tick boundaries (bounded memory; next tick starts clean) | one test per level per tick |

Async workers (`PlanExecutor.java:206`, `NavGridView.background`) get **no weaker guarantee than
today**: they already race fire-time `patchCell` writes with no ordering (tolerated by design —
settled-boundary adoption + the epoch-gated re-search repair staleness; `NavReclaim` guards section
lifetime, `OrebitCommon.java:79`). The drain issues the identical `grid.set` writes on the same
(server) thread, just later within the tick; if anything the visibility story gets *cleaner*,
because submissions happen inside bot ticks, behind a barrier.

### §4.5 `editEpoch` interaction — bump at enqueue (recommended), not at drain

Analyzed both ways:

- **Enqueue-time bump** (keep line `:63`'s position, before the queue insert): semantics are
  today's, verbatim — the epoch says "the world may have changed", immediately. The one consumer
  reads it inside the bot's tick (`AllyBotEntity.java:1322`), which sits BEHIND the bot-tick-start
  barrier, so by the time an advanced epoch triggers `refreshWindow`, the grid is already drained —
  the re-search sees patched terrain. No staleness hole, no new spurious replans relative to today.
  **Recommended: it is unconditionally safe regardless of barrier-set completeness.**
- **Drain-time bump** (per actually-patched cell): strictly fewer spurious bumps (toggles and
  no-ops stop arming re-searches — attractive, given §3's observation that epoch pollution
  outweighs patch time). But its correctness leans on the invariant *every epoch read is behind a
  flush barrier*: any future epoch consumer added outside a barrier would see "unchanged" while
  changes sit queued, silently skipping a needed re-search — the exact failure the task flags.
  Defer this refinement; **Phase 0 already removes the worst epoch pollution** (navtype-invariant
  churn) at enqueue with no such coupling.

Dedup nuance for the record: with enqueue-time bumps, a piston extend+retract still bumps twice for
net-zero grid change — identical to today (which bumps twice AND patches twice), so the debounce
behavior is unchanged while the patch work disappears.

### §4.6 Invariants (the review checklist)

1. **Read-your-writes on the server thread**: any server-thread nav-grid read observes every change
   fired before it (guaranteed by the §4.4 barrier set; equivalent to today's inline patch).
2. **Queue empty at tick end** (the `onWorldTickEnd` catch-all): pending state never crosses a tick.
3. **Final-state equivalence**: after any drain, the grid equals what sequential fire-time
   `patchCell` calls would have produced from the same event stream (v1: by inheritance, any order;
   Phase 2: by the §4.3 argument + identity guard).
4. **No `NavSection`/`BlockState` refs in the queue**: keys are packed positions, values are
   navtype shorts; sections resolve fresh at drain (unload ⇒ drop) — no interaction with
   `NavReclaim` retirement.
5. **Epoch never under-reports**: every enqueue that could change the grid bumps (Phase 0's skip is
   provably grid-invisible, so skipping its bump satisfies the `:29-31` contract exactly).
6. **`NetherPortalIndex` accuracy**: portal add/remove decided from event params
   (`descriptorFor(oldState)`/`(newState)`) at fire time — order-exact under deferral.
7. **No new per-A*-node or per-grid-READ cost**: the barriers sit at search/tick granularity, never
   on `NavGridView.navtypeAt`-class paths (the branch-prediction rule).

---

## §5 Expected win + honest assessment

- **Phase 0 (no-op filter)**: eliminates ~100% of patch cost AND epoch bumps for navtype-invariant
  changes — plausibly the *majority* of block-change events near any redstone or farmland (the 48×
  state→navtype collapse is the whole point of NavBlock). Expected practical effect: the s52
  debounce actually holds in lived-in bases (today one redstone clock permanently defeats it,
  costing each bot a multi-ms re-search per recheck period). Risk ≈ zero, cost ≈ one lookup+compare
  moved earlier. **This is the piece I'd take even if batching is rejected.**
- **Phase 1 (defer+dedup)**: wins only on same-cell-multi-change ticks (piston extend/retract,
  fluid flicker) and on moving spike work to a defined point. For vanilla ambient volume the
  arithmetic is brutal and should be said plainly: saving even half of 105 µs/tick is **~0.1% of
  the tick** — below the care threshold on its own. Its value is (a) enabling Phase 2, (b) toggle
  collapse, (c) turning a mid-explosion 10 ms patch burst inside one entity's tick into a drain we
  control the location of (though NOT budget-spreadable across ticks — invariant 2 forbids it,
  unlike `HpaMaintenance`'s 8-leaf budget, and that's a real structural difference worth noting:
  the block tier can never amortize a storm across ticks without giving up read-your-writes).
- **Phase 2 (scratch amortization)**: bounded above by the scratch share of a patch (~⅓–½,
  unconfirmed hypothesis, §1.3) on CLUSTERED storms only — realistic ceiling ~2–2.5× on a
  100-TNT-chain tick (~10.5 ms → ~4–5 ms). Real but only visible in the same worst cases that are
  already outside V1 scale.
- **Net recommendation**: adopt **Phase 0 now**; hold **Phases 1–2 PARKED** unless (a) a real
  server profile shows patch storms or epoch-driven re-search churn above ~1% of tick time, or (b)
  the product scope grows toward industrial-redstone/multi-bot servers (the V2 colony-sim memory).
  This doc then serves as the pre-approved design. Betting the complexity budget on the flush-barrier
  surface (three barriers, one ordering constraint, one equivalence proof) to win a fraction of a
  percent in the median case fails the project's own ratio test — the honest framing is *spike
  insurance whose premium is correctness surface*.

**Risks** (all Phases 1–2): a missed barrier = a sync search over a stale grid — the failure is
silent (a slightly wrong path, a phantom BLOCKED) and would be miserable to diagnose; listener/drain
ordering vs `HpaMaintenance.flush` is load-bearing and only enforced by registration order;
`patchCell`'s "grid is always consistent" invariant currently holds trivially and would become
conditional on barrier discipline. Phase 0 shares none of these.

---

## §6 Measurement plan

`PatchStormBenchmark` as it stands (`PatchStormBenchmark.java:65`: SCATTER / DIG / TOGGLE / SEAM,
one isolated `patchCell` per op at `:204-211`) **cannot see a batching win** — it measures exactly
the per-patch unit cost. It stays as the regression gate: the Phase-1 enqueue path (map
insert/overwrite) plus drain dispatch must keep the effective single-change cost within noise of
today's direct call (single change per tick IS the common case; regression bar ±3%, mirroring the
depth-nibble bar at `:36-37`).

New shapes (paired interleaved A/B, per the process rule):

1. **BATCH_PISTON** — 26 clustered same-section changes drained as one batch vs 26 sequential
   `patchCell`; the §2 double-work directly. Metric: ns/changed-cell.
2. **BATCH_BLAST** — a ~50-cell sphere spanning a section seam (both scratch amortization and the
   below-seam pass dedup); the TNT shape.
3. **TOGGLE_PAIR** — extend+retract per cell per drain (Phase-1 dedup: expect ~2× regardless of
   Phase 2, the cleanest headline for defer+dedup alone).
4. **REDSTONE** — state changes with unchanged navtype (repeater/power flavors of one block):
   proves Phase 0 (expect patch cost → ~a compare; also assert zero epoch bumps).

Correctness guards (mandatory before any Phase-2 keep):

- **Identity test** (the `DepthIdentityTest` pattern): randomized change sequences (including
  same-cell toggles, same-column stacks, seam rows) applied (a) sequentially via `patchCell` and
  (b) via enqueue+drain — final `short[]` grids AND depth `byte[]`s must be byte-equal, both
  drain-order-randomized (v1) and phased (Phase 2).
- **Barrier test**: enqueue N changes, construct a `NavGridView(level)`, assert reads see final
  state and `pendingCount == 0`; same through a simulated bot-tick entry.
- **Epoch test**: Phase-0 skips don't bump; real changes bump exactly as today; the
  `AllyBotEntity.java:1322` debounce read after a barrier never observes queued-but-unbumped state.

Refutation criteria (pre-registered): Phase 1 is refuted if BATCH_PISTON/TOGGLE_PAIR show <3%
per-cell win or SCATTER/SEAM single-change cost regresses >3%; Phase 2 is refuted if its marginal
win over Phase 1 on BATCH_* is <3% (i.e. the §1.3 scratch-share hypothesis fails). Phase 0 has no
perf refutation criterion — it is kept on correctness (identity + epoch tests) since its cost is
structurally ≤ today's.
