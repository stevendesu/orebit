# PERF-AUDIT: reverse-Dijkstra region cost field — allocation / hot-path hygiene

> **STATUS: PROPOSED (audit — nothing implemented).**
> Report only. Every fix below is a proposal per the design-review-first rule (CLAUDE.md "Performance
> model"): mechanism + invariants + expected win + risk, awaiting owner ratification and paired
> interleaved A/B measurement before a single line changes.

**Scope:** `RegionPathfinder.costToGoalField` (the goal-rooted bounded Dijkstra), `RegionCostField`
(the field object + its per-A*-node `costAt` read), `PathPlan.regionFieldFor` (lifecycle/cadence),
the `BlockPathfinder` read site, and the live region-A* entries (`plan` / `planWithin` /
`planLevelFragments`).

**Headline:** the read path is **allocation-free but not branch-light** — `costAt` re-derives
fragment-centroid membership from packed footprints on **every read** (2 ThreadLocal gets + a hash
probe + up to `6·k` footprint decodes + `k` long divisions for a k-fragment region), and the block
tier calls it ~2–3× per expansion. The build path is clean in its Dijkstra loop but allocates a
dense `×63`-fragment-slot field per rebuild (~20 B/slot) and a boxed-`Integer` BFS for buried goals.

---

## §1 What the field is + lifecycle / rebuild cadence

`RegionCostField` (`src/main/java/com/orebit/mod/pathfinding/regionpathfinder/RegionCostField.java`)
is a dense per-(level-0 region, fragment) float field of goal-rooted Dijkstra `g`, plus a per-slot
goalward exit opening + onward cost for the intra-region gradient. Produced by
`RegionPathfinder.costToGoalField` (`RegionPathfinder.java:657–715`) on the dedicated
`FIELD_SEARCH` ThreadLocal `Nodes` state (`RegionPathfinder.java:221`), bounded to a `RegionBox`.

**Rebuild cadence — per window-target change, NOT per tick, NOT per search:**

- `PathPlan.regionFieldFor(target)` (`PathPlan.java:1201–1224`) is called at **every search-launch
  site** — the sync `findPath` (`PathPlan.java:876`) and the async `submit` (`PathPlan.java:920`,
  covering boundary replans and P4 pre-plans) — but is gated by one `target.equals(fieldRoot)`
  compare (`PathPlan.java:1202`). An unchanged window target is a cached-reference return; the
  Dijkstra reruns only when the window target moves (window slide / new goal). Javadoc claims
  "~6 µs per build at replan cadence" (`PathPlan.java:1192`) — see §2 for why that figure should be
  re-measured against the array-zeroing bill.
- **Thread:** both launch sites run on the **server/tick thread** (async mode submits from the tick
  thread; only the block search itself moves to the planner pool). So **field builds are always
  server-thread work**, and the built field is snapshotted immutably into `SearchRequest`
  (`SearchRequest.java:37`) for workers. Write-once-read-many: a rebuild swaps a NEW instance,
  in-flight workers keep the old one (`PathPlan.java:1196–1199`).
- A second producer: `/bot trace` builds a one-shot field for the A/B trace run
  (`AllyBotEntity.java:1677–1688`) — diagnostic, cold.
- Failure ⇒ `null` cached under the same root (octile fallback, no per-replan re-attempt)
  (`PathPlan.java:1218–1222`).

## §2 Build-path audit (per rebuild, server thread)

Per `costToGoalField` call:

1. **The field arrays — the dominant allocation.** `RegionCostField` ctor
   (`RegionCostField.java:63–69`): `slots = dimX·dimY·dimZ·MAX_FRAGMENTS` with
   `MAX_FRAGMENTS = 63` (`RegionFragments.java:66`), across 5 parallel arrays
   (`float cost` + `int exitX/exitY/exitZ` + `float onward` = **20 B/slot**), plus
   `Arrays.fill(cost, UNREACHED)` over all slots. The box is bot↔target regions **+3 pad each
   side** (`PathPlan.java:1210–1214`), so even a modest 7×7×7-region window box is
   343·63 = 21,609 slots ≈ **432 KB allocated + 86 KB zero-then-filled per rebuild**; a
   10×8×10 box is ≈ 1.0 MB. The `×63` multiplier is the waste: typical regions carry 1–3
   fragments (63 is the cap, and >cap regions collapse). This is per-replan garbage, not hot-loop
   garbage — but it is exactly the "per-search setup is itself a hot path" shape the SHORT/MULTI
   case studies warn about, and it makes the "~6 µs" Javadoc figure implausible for larger boxes.
2. **Goal dig-flood (buried goals only)** — `costToGoalField` calls `grid.goalDigSeeds` with a
   capturing lambda (`RegionPathfinder.java:680–684`, one lambda alloc/build). Inside
   `RegionGrid.goalDigSeeds` (`RegionGrid.java:306–353`): `new ArrayDeque<int[]>` +
   `new HashSet<Integer>` (`RegionGrid.java:325–326`), one **boxed `Integer`** per visited cell
   (`seen.add(relKey(...))`, `RegionGrid.java:327,334`) and one **`new int[4]`** per enqueued solid
   cell (`RegionGrid.java:328,351`). The BFS is bounded (`MAX_GOAL_DIG_CELLS = 12`,
   `RegionPathfinder.java:151`) but a radius-12 6-connected diamond is ~2,600 cells worst case →
   **~2–5k transient objects per buried-goal rebuild**, and the same BFS runs a *second* time in the
   forward plan's `buildDigSeeds` (`RegionPathfinder.java:564–611`, which also allocates
   `long[16]+float[16]+int[1]` + lambda + 2×`Arrays.copyOf` + a `DigSeedSet`). Documented as a
   deliberate cold path (`RegionGrid.java:303–304`), but it violates the no-boxing house idiom and
   is trivially scratch-able (§5 P5).
3. **The Dijkstra loop itself is clean.** Reused `FIELD_SEARCH` `Nodes` (SoA + open-addressed
   `long`→row map + primitive heap, `RegionPathfinder.java:1461–1649`), `reset()` keeps
   high-water-mark capacity; `expandNode`/`relaxFrag`/`seedField` allocate nothing (scratch is the
   `Nodes.wa/wb/wc` int[3]s, `RegionPathfinder.java:1488–1490`); all TRACE string work is
   `if (TRACE)`-guarded. `field.record` (`RegionCostField.java:78–88`) is pure array writes.
4. **Entry-face node multiplication.** Field nodes are keyed with the 3-bit entryFace fold
   (`searchKey`, `RegionPathfinder.java:209–211`), so one physical (region,fragment) can be settled
   via up to ~7 entry variants; `record` min-folds them (`RegionCostField.java:83`). The bounded
   flood therefore does a small-integer multiple of the physical node count in pops/relaxes —
   bounded by the box and the 20k expansion backstop (`RegionPathfinder.java:709`), but pure
   overhead for a min-folded guidance field (§5 P3, ranked low because the values are
   entry-dependent).

## §3 Read-path audit — the per-A*-node consult (the important part)

**Where and how often.** The block tier reads the field only inside `Relaxer.h()`
(`BlockPathfinder.java:947–970`): `regionField.costAt(x,y,z)` at `BlockPathfinder.java:956`, one
`< UNREACHED` compare at 957, then `max()` against the octile. `h()` is called:

- once for the start node (`BlockPathfinder.java:720`),
- **once per pop** for closest-approach tracking (`BlockPathfinder.java:755`), and
- **once per accepted relax** (`BlockPathfinder.java:1003`; rejected candidates bail at 996 before
  `h`).

So `costAt` runs ≈ `pops + accepts` ≈ **2–3× expansions per search** — ~20–30k calls for a sync
10k-node search, potentially several hundred thousand under the async 262k-node backstop. This is
unambiguously the hot path.

**Per-call anatomy of `costAt` (`RegionCostField.java:100–115`):**

| # | Work | Cost class |
|---|---|---|
| 1 | `regionIndex` bounds check (`:140–144`) | 6 compares — fine |
| 2 | **Two `ThreadLocal.get()`s** — `CENT.get()`, `TMP.get()` (`:104`, decl `:41–42`) | 2 ThreadLocalMap probes on EVERY read, even for uniform regions |
| 3 | `RegionPathfinder.fragmentOf` → `fragmentOfLevel` (`RegionPathfinder.java:1436–1448`) → `grid.fragmentRecord` (`RegionGrid.java:448–454`) → `CostPyramid.rowIfPresent` (`CostPyramid.java:201–205`, `Level.rowIfPresent` `:109–117`) | murmur3 + linear-probe **hash lookup per read** (unboxed — good — but a probe per read) |
| 4 | MIXED region: loop `fragmentCount()` fragments × `fragmentCentroidWorld` (`RegionPathfinder.java:1356–1376`), each a 6-face loop of `touchesFace` + `footprint` + `footprintCenterWorld` (`:1315–1349`, a ~30-op branchy switch) + a **64-bit division** (`sx/n`, ×3) per fragment | for a k-fragment region: up to **6k footprint decodes + 3k divisions per read**, recomputing values that are **immutable per built region** |
| 5 | Unreached-slot fallback: `cheapestReachedSlot` (`:108–112`, `:118–126`) | data-dependent branch + **63-slot linear scan** whenever nearest-centroid disagrees with the flood's fragment ids or the cell's region wasn't reached (in-box frontier cells pay steps 2–4 AND the 63-scan just to return UNREACHED) |
| 6 | `octileToExit` + adds (`:114`, `:133–137`) | cheap, branch-light — fine |

**Verdict:** `costAt` is **zero-allocation** (no boxing, no `new`, ThreadLocals allocate only at
first touch) and free of megamorphic dispatch (all call sites monomorphic, `RegionCostField` and
`RegionFragments` final/effectively-final). But it is **heavily branchy and redundantly
compute-bound**: roughly 40–100+ branches per read for MIXED regions versus the plain octile's
~15 flops. Against the project's ~400–700 ns/node flood baseline, an extra ~30–80 ns × 2–3 calls
per node is a real per-node tax paid only in region-field mode.

Two adjacent observations (not allocations, flag for owner):

- **Redundant per-pop `h` recompute** (`BlockPathfinder.java:755`): `f[current] − g[current]`
  already equals the relax-time `h` (modulo float rounding). Predates the field; the field made it
  ~3× dearer. See §5 P2.
- **Latent cross-thread read** (correctness-adjacent, out of audit scope but load-bearing for P1):
  async workers' `costAt` → `fragmentOf` → `CostPyramid.Level` arrays that the **server thread
  concurrently mutates** (`ensureLeaf` → `intern`/`growMap`, `CostPyramid.java:92–106,138–156` —
  plain arrays, no fences). The field object itself is safely write-once, but its read path escapes
  into the mutable pyramid. P1 incidentally closes this by making the field self-contained.

## §4 `plan` / `planWithin` / `planLevelFragments` audit (the forward A*)

Live entries: `plan` (`RegionPathfinder.java:235–260`) → `planFragments` (`:280–308`) → the shared
`planLevelFragments` (`:436–524`); the cascade calls `planWithin` (`:371–417`) per level.

- **Search state:** reused ThreadLocal `Nodes` (`SEARCH`, `:214`), `reset()` per search
  (`:1517–1522`). Open-addressed primitive `long`→row map, no boxed keys anywhere; binary heap over
  primitive arrays with amortized `Arrays.copyOf` growth to high-water mark — no `PriorityQueue` /
  `ArrayDeque` churn. **Clean; matches the BlockPathfinder idiom.**
- **Per-pop loop** (`:468–501`): no allocation. `expandNode` (`:775–959`) uses `nodes.wa/wb/wc`
  scratch; TRACE strings all gated. `DigSeedSet.costFor` is a deliberate ≤16-entry linear scan per
  pop (`:539–554`) — documented, fine.
- **Per-pop recompute (CPU, not alloc):** `expandNode` re-derives footprint centers / centroids per
  expansion (`footprintCenterWorld` ~7–20×/pop, `fragmentCentroidWorld` per sibling mine edge via
  `mineCost` `:1213–1219`). Since entry-face keying pops the same physical region up to ~7×, the
  same immutable centroids are recomputed each time. Same root cause as §3 item 4; a per-record
  centroid cache (§5 P1's storage, keyed off `RegionFragments`) would serve both. Region searches
  are 10²–10³ pops, so this is second-order next to §3.
- **Per-plan (cold, acceptable):** `nearestFragment` allocates 2×`new int[3]` (`:1383–1386`,
  self-documented as cold); `buildDigSeeds` as per §2 item 2; `reconstructFragments` allocates the
  8 result arrays + `RegionPathPlan` (`:1069–1093`) — the result object, fine; a `Debug.ENABLED`
  log line on FAIL only (`:503–514`).
- **`planWithin` itself** adds no allocations beyond the above (integer clamps + `ensureNode`
  probes; `BlockPos` params come from the caller).

No boxed `Long`/`Integer`, no `Map<Long,X>`, no per-node objects found anywhere in the forward A*.

## §5 Proposed fixes, ranked by expected win

All PROPOSED; each needs owner design review, then the §6 protocol.

### P1 — Bake fragment centroids into the field at build time; make `costAt` self-contained (biggest win)

- **Mechanism:** at build time (in `record`, or one pass over reached rows at the end of
  `costToGoalField`), compute each reached (region,fragment)'s centroid once via the existing
  `fragmentCentroidWorld`, storing `centX/centY/centZ` (+ a per-row fragment count byte) parallel
  to `cost[]`. `costAt` becomes: `regionIndex` → scan the row's ≤k cached centroids for nearest
  (Manhattan, exactly the current metric) → existing gradient math. Deletes from the read path: both
  ThreadLocal gets, the `fragmentRecord` hash probe, all footprint decoding, all divisions.
- **Invariants:** centroids are pure functions of the built `RegionFragments` record, so the
  selected fragment — hence every `costAt` value — is **identical** as long as the record doesn't
  change between build and read; today a mid-flight leaf rebuild could change live reads, so baking
  actually makes reads *more* consistent with the recorded costs (and closes the §3 cross-thread
  read). One semantic delta to ratify: current code resolves nearest-centroid over ALL fragments
  then falls back if that slot is unreached; a cached variant most naturally scans reached slots
  only — the fallback branch (`:108–112`) collapses into the same scan. Owner should confirm that
  folding is acceptable (it changes `costAt` values only in the fallback cases the Javadoc already
  calls "robustness", i.e. a behavior change to review, not sneak in).
- **Expected win:** read cost drops an estimated 60–80% in region-field mode; end-to-end, low
  single-digit % on field-guided searches (h is one term of ~500 ns/node). Build cost rises by one
  centroid computation per reached slot (hundreds, µs-scale, replan cadence).
- **Risk:** medium — more build-time work on the tick thread (measure vs §2 item 1); the
  fallback-folding delta above; +12–13 B/slot memory unless combined with P4.

### P2 — Stop recomputing `h` per pop in `BlockPathfinder`

- **Mechanism:** replace `relaxer.h(cx,cy,cz)` at `BlockPathfinder.java:755` with the `h` computed
  at relax time. Exact variant: a parallel `float hOf[]` on `Nodes` written at relax (4 B/node,
  byte-identical `bestH` by construction). Cheap variant: `nodes.f[current] − nodes.g[current]` —
  zero memory but differs in last-ulp from the stored `h`, so closest-approach ties (the PARTIAL
  commit target) could flip: that variant is a **behavior change** and must be treated as one.
- **Expected win:** removes one full `h` per pop — in field mode ~40–50% of all `costAt` calls; in
  baseline (null-field) mode it still removes a `Math.sqrt` cross-product per pop. Estimated 1–4%
  on flood-shaped scenarios; guards must stay flat.
- **Risk:** low (exact variant) — but it touches the block A* hot loop, so full-suite A/B including
  SHORT/MULTI per the standing protocol; the `f−g` variant needs byte-identical verification and
  will likely fail it.

#### P2 measured (s53, 2026-07-08) — **STATUS: REFUTED, reverted**

Exact variant implemented as specced (parallel `float hOf[]` on `Nodes`, written at the start seed
and in `relax` wherever `f` is written; pop site reads `nodes.hOf[current]`; the `f−g` variant NOT
attempted). Byte-identity held: full unit suite 211/0 with zero expectation changes, incl. the
`FullSearchHeadlessTest` full-pipeline pins (expansions/waypoints/hash).

Paired interleaved A/B (A,B,A,B), full `PathfinderBenchmark` sweep + `FullSearchBenchmark` +
`RegionPathfinderBenchmark` each sweep, JDK 21, active node 1.21.11, forks=0 per the harness.
µs/op (avgt, cnt 6, ± = 99.9% CI):

| Scenario | A1 | B1 | A2 | B2 | A mean | B mean | Δ B vs A |
|---|---|---|---|---|---|---|---|
| TOWER | 55.92 ±4.13 | 58.19 ±6.37 | 55.61 ±0.48 | 54.88 ±0.56 | 55.77 | 56.53 | +1.4% |
| OPEN | 21.15 ±0.23 | 21.60 ±1.32 | 21.75 ±0.37 | 21.05 ±0.11 | 21.45 | 21.32 | −0.6% |
| UPOVER_OPEN | 113.71 ±1.68 | 113.15 ±2.27 | 112.52 ±1.07 | 111.19 ±1.56 | 113.12 | 112.17 | −0.8% |
| UPOVER_WALL | 90.81 ±2.40 | 89.43 ±3.28 | 89.91 ±2.03 | 88.89 ±1.76 | 90.36 | 89.16 | −1.3% |
| SHORT | 14.02 ±0.18 | 13.57 ±0.09 | 14.04 ±0.10 | 13.93 ±0.20 | 14.03 | 13.75 | −2.0% |
| MULTI | 260.70 ±7.39 | 257.30 ±7.30 | 255.11 ±2.94 | 254.33 ±4.42 | 257.91 | 255.81 | −0.8% |
| FLOOD | 16235 ±355 | 15467 ±582 | 15507 ±256 | 15525 ±117 | 15871 | 15496 | −2.4%* |
| CLIFFS | 25.35 ±0.29 | 25.82 ±0.27 | 25.70 ±0.10 | 25.69 ±0.54 | 25.53 | 25.75 | +0.9% |
| BRIDGE | 34.49 ±0.84 | 32.47 ±0.49 | 33.70 ±0.68 | 33.34 ±0.53 | 34.09 | 32.90 | −3.5% |
| SPIRAL | 188.80 ±7.91 | 180.18 ±4.77 | 182.09 ±3.82 | 183.15 ±3.33 | 185.45 | 181.67 | −2.0% |
| SETUP | 0.748 ±0.189 | 0.611 ±0.021 | 0.676 ±0.016 | 0.610 ±0.009 | 0.712 | 0.611 | −14.2%† |
| SETUP_MACRO | 3.062 ±0.254 | 2.713 ±0.023 | 3.034 ±0.539 | 2.620 ±0.036 | 3.048 | 2.667 | −12.5%† |
| FullSearch GOAL_IN_WINDOW | 969.7 ±15.2 | 1023.8 ±83.6 | 1024.5 ±206.6 | 1001.2 ±86.6 | 997.1 | 1012.5 | +1.5% |
| FullSearch GOAL_NOT_IN_WINDOW | 851.9 ±41.3 | 863.0 ±45.2 | 842.2 ±9.9 | 847.7 ±27.9 | 847.0 | 855.3 | +1.0% |
| Region OPEN_CAVERN | 0.811 | 0.813 | 0.807 | 0.811 | 0.809 | 0.812 | +0.4% |
| Region LONG_CASCADE | 5.999 | 6.066 | 6.072 | 5.970 | 6.036 | 6.018 | −0.3% |

\* The FLOOD "win" is entirely A1 (16235), the session's first sweep — A2/B1/B2 agree within 0.4%
(15467–15525), so the honest interleaved read is FLAT. † SETUP/SETUP_MACRO's apparent −12–14% is a
sub-µs op under the known forks=0 JIT-layout sensitivity (and A1's SETUP error is ±0.189 on 0.748);
not targeted scenarios, noted as an anomaly only.

**Verdict against the decision rule (≥3% on the targeted pop-heavy scenarios — FLOOD/TOWER/MULTI —
with nothing regressing beyond noise):** FLOOD flat (see \*), TOWER +1.4% (inside B1's ±6.37),
MULTI −0.8%. No targeted scenario clears the bar; nothing regressed beyond noise either — the
change is simply invisible. Mechanism read: the per-pop `h` recompute this eliminates is one call
per pop against ~14 movements' worth of candidate generation + relax work per pop, and in
baseline (null-field) mode `h` is a handful of FLOPs + one `Math.sqrt` — too small a slice to
surface. The estimate's 1–4% upper half assumed field-mode `costAt` weight; FullSearchBenchmark
(the field-guided path) read +1.0–1.5%, i.e. flat-to-noise there too. **Reverted per protocol; do
not re-propose the exact variant. The `f−g` cheap variant remains rejected a priori (behavior
change).** If P1 lands and `costAt` becomes self-contained-but-still-hot, the pop-site recompute
share shrinks further, not grows — P2 stays dead.

### P4 — Shrink the field's `×63` dense layout (memory / setup-cost fix; pairs with P1)

- **Mechanism:** the dense `regionIndex·63 + frag` layout sizes every region for the 63-fragment
  worst case; real regions carry 1–3. Option (a): clamp field slots to `MAX_FIELD_FRAGS = 8`
  (power-of-two keeps the index math one shift), folding frag ≥ 8 into the cheapest-slot semantics
  — flat math preserved, `cheapestReachedSlot` scan 63→8. Option (b): CSR-style per-row offsets
  from `fragmentCount` — tighter but adds an indirection per read (weigh against the branch-budget
  culture). Prefer (a).
- **Expected win:** ~8× less allocation + zeroing per rebuild (432 KB → ~55 KB for a 7³ box);
  faster fallback scans; re-anchors the "~6 µs" claim.
- **Risk:** low-medium — >8-fragment regions (rare cave honeycombs) get folded fragment identity in
  the *field only* (guidance surface, already approximate); still a reviewable behavior delta.
  `record`'s clamp (`RegionCostField.java:81`) must match.

### P5 — De-box the goal dig-flood BFS scratch

- **Mechanism:** replace `ArrayDeque<int[]>` + `HashSet<Integer>` (`RegionGrid.java:325–328`) with
  ThreadLocal scratch: a generation-stamped visited array over the bounded relative cube
  (radius ≤ `MAX_GOAL_DIG_CELLS+1` ⇒ 27³ shorts ≈ 39 KB, stamped not cleared) + an `int[]` ring
  queue packing (relX,relY,relZ,dist) into one int. Direct application of the "Hot Path: No Heap
  Alloc" house rule to a per-replan path that currently makes ~2–5k boxed objects per buried-goal
  build, ×2 (field + forward plan).
- **Expected win:** eliminates that garbage + hash overhead; µs-scale per replan, zero GC pressure
  from the path. Not a benchmark mover — a hygiene fix.
- **Risk:** low, mechanical; BFS order/results must be provably identical (same FIFO, same visited
  semantics). Runs on the tick thread only, so a plain static scratch would even do — keep
  ThreadLocal for safety with `/bot trace`.

### P6 — (Only if P1 is deferred) hoist the ThreadLocal scratch out of `costAt`

- **Mechanism:** `costAt(x,y,z,int[] cent,int[] tmp)` overload; `Relaxer` owns the two int[3]s
  (it already exists per search). Saves 2 ThreadLocalMap probes per read (~5–10 ns).
- **Win/risk:** small/none — strictly dominated by P1; listed as the minimal fallback.

### P3 — Collapse entry-face keys in the field Dijkstra (ranked last)

- **Mechanism:** in `dijkstra=true` mode, intern physical keys only — `record` min-folds entry
  variants anyway, so the flood would settle ~2–6× fewer nodes.
- **Risk / why last:** edge costs are entry-dependent (the §4 entry→exit walk term), so collapsed
  values differ from today's min-fold — a guidance-quality change on a per-replan path whose
  absolute cost is already small. Not worth the behavior review unless field builds show up in
  tick profiles after P4.

## §6 What to measure

Per the standing protocol (paired interleaved A/B, ≥3% targeted win, no scenario beyond noise):

- **`FullSearchBenchmark`** (`src/test/java/com/orebit/mod/worldmodel/pathing/FullSearchBenchmark.java`,
  scenarios in `FullSearchScenarios.java` — builds the real field at `:135`): the only bench that
  exercises the **whole** live path (dig-flood → reverse Dijkstra → field-guided block search).
  Primary for P1/P2/P4; baselines GOAL_IN_WINDOW ≈ 5648 µs / GOAL_NOT_IN_WINDOW ≈ 804 µs (s50).
- **`PathfinderBenchmark`** SHORT + MULTI: the per-search-setup guards — field is null there, so
  P1/P4 must read FLAT; P2 touches the shared pop loop and needs the full scenario suite
  (FLOOD/TOWER/etc.) with byte-identical expansion counts for the exact variant.
- **`RegionPathfinderBenchmark`**
  (`src/test/java/com/orebit/mod/pathfinding/regionpathfinder/RegionPathfinderBenchmark.java`):
  guards `planWithin`/`planLevelFragments` against any P1-storage or P5 side effects.
- **Field-build microbench (new, or a FullSearch sub-measure):** time `regionFieldFor` alone across
  box sizes (3³ → 10³ regions) to replace the "~6 µs" Javadoc figure with a measured curve — the
  P4 before/after metric — plus alloc-rate via the existing `-Pprof=gc` harness.
- **Read-path attribution:** `-Pprof=cpu` (`jdk.ExecutionSample`, filter stacks containing
  `costAt`) on a field-guided FullSearch run, before claiming the §3 percentages — profiler
  hypotheses are hypotheses.
