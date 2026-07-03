# PERF DESIGN REVIEW — Boot-time warm-up searches + eager-size scratch (perf item b)

> **STATUS (2026-07-03): half ADOPTED, half REVERTED.**
> **Warm-up: ADOPTED, LIVE, default true** — `worldmodel/pathing/NavWarmup` (~250 lines), synchronous in
> the third `onServerStarted` handler (after ConfigLoader + MiningModel), plateau-detected (min 4 / max 8
> rounds), `pathing.warmup` + `pathing.warmupBudgetMs` (1500) config keys. Measured on the fresh-JVM
> cold-start harness (10 runs/arm): first search **21.8 → 0.67 ms p50 (32×)**, p90 30.0 → 0.81 ms; boot
> cost 475 ms median (never hit the cap). Post-adoption it also sweeps `computeDepth` (depth-nibble
> parity with live grids).
> **Eager-size scratch: FALSIFIED + REVERTED** — the `Nodes(8192, 8192)` one-liner cost pinned SHORT
> **+4–7%**: `Nodes.reset()` does `Arrays.fill(mapRow, -1)` over CAPACITY, so every flood-free search
> paid +28 KB of fill. Any future eager sizing needs lazy clearing / epoch-stamped slots — a design pass,
> not a one-liner. (The EditPool prefill was already dropped at design time.)
> **Full outcomes: `PERF-RESULTS-2026-07-03.md` §E5 (warm-up) / §E5b (eager-size).** The mechanism as
> shipped is authoritative in `NavWarmup.java` — this doc is retained for the cold-start taxonomy (§1–§2)
> and the design rationale for the seam (§3); the deep mechanism / risk / measurement sections were
> dropped post-adoption.

**Scope:** the two halves of HANDOFF next-menu item 6(b):

1. **Boot-time warm-up searches** — the only fix for the 16 ms JIT-cold first search
   (`PERF-PROFILE-2026-07.md` S1, and ~61% of S2).
2. **Eager-size the ThreadLocal search scratch** (~2.3 MB/search-thread) — smoothing only, per the
   same profile. (Refuted — see STATUS + `PERF-RESULTS-2026-07-03.md` §E5b.)

---

## 1. Problem statement — what the profile actually established

Three in-game samples (26.2, `PERF-PROFILE-2026-07.md` header table + §5):

| Sample | Observation | Attribution (profile §5) |
|---|---|---|
| S1 | `2 nodes in 16275.5 us` — first search after boot | **Classloading + interpreted/C1 first execution** of the whole pathfinder class graph. The lazily-allocated scratch is ~41 KB; eager allocation "saves microseconds of the 16 ms." |
| S2 | `8357 nodes / 5680 ns/node` — first big search | Growth-reallocation + first-touch EditPool fill ≈ 2.2 MB ≈ **≲1 ms ≈ 1–2%**. The warm analog runs 2226 ns/node, so (5680−2226)×8357 ≈ **28.9 ms ≈ 61% of the search is JIT warm-up** — "C2 still compiling the hot loop, cold i-cache/branch predictors." |
| S3 | `7221 nodes / 2226 ns/node` warm | Algorithmic (`kindAt` 49% — a separate lever, not this doc). |

Profile verdict, quoted: *"The one boot-time change that actually attacks S1/S2 is a **synthetic
warm-up search at server start** (pre-JIT), not pre-sized arrays"* (§ verdict) and *"JMH cannot see
this case at all (forks=0 reuses a warmed JVM; warm-up iterations exist precisely to exclude it)"*
(§5-S1, §6 honesty box). So: the target is unmeasurable by our steady-state bench **by construction**,
and the fix is behavioral only at boot, never on a hot path.

Why it matters in practice: the bot spawns on **player join** with default `Mode.FOLLOW`
(`AllyBotEntity.java:222`, `OrebitCommon.init` join hook → `BotManager.spawnBotFor`), so the first
search fires seconds after the first player joins — not when someone types `/bot come`. The cold cost
lands on a live player tick. Ratio framing: the first search runs ~**8000×** worse per node than a
warm flood (S1: 8.1 ms/node vs 400–700 ns/node floors) and the first *big* search ~**2.6×** worse
than its warm analog (5680 vs 2226 ns/node). A 16 ms stall is ~⅓ of a 50 ms tick budget spent on a
2-node search.

---

## 2. What exactly is cold (decomposition of the 16 ms)

Four distinct layers, in the order they thaw:

1. **Class loading + static init.** `BlockPathfinder` + 13 `MovementRegistry.TIER1` movements +
   `MovementContext`/`Relaxer`/`GoalForcedCost` + the `cuboid/` package + `PathEdits`/`EditScratch`.
   Note `NavBlock`'s ~28k-state interning is **already forced at server start** —
   `OrebitCommon.init` registers `MiningModel.buildTable` on `onServerStarted` explicitly to trigger
   NavBlock static-init (OrebitCommon.java:44). But the *pathfinder* class graph loads lazily at
   first `findPath` — S1's 16 ms starts at `findPath`'s own `t0` (BlockPathfinder.java:530), so all
   of it is inside the observed number.
2. **Interpreted → C1 → C2 tiers.** Default HotSpot tiered thresholds (JDK 21, ballpark — scaled at
   runtime by queue feedback): C1-with-profiling around ~200 invocations / ~few-k loop back-edges
   (OSR); C2 around ~15k invocations / ~40k back-edges. First execution is interpreted:
   ~10–50× slower than C2 for this kind of ALU-dense bit-math code.
3. **Profile-driven recompilation settling** — C2 compiles on background compiler threads; even
   after thresholds trip, compiled code lands milliseconds later. S2 shows this: the *second-ish*
   search is still 61% warm-up.
4. **ThreadLocal scratch growth** (the eager-size half): `SEARCH = new Nodes(512, 1024)` +
   heap 512 + `EditPool` 256 slots/0 instances (BlockPathfinder.java:206, 222–241), growing ×2 to
   the measured high-water 8192 rows / 8192 map / 4096 heap / 16384 EditPool slots ≈ **2.2 MB**,
   worth **≲1 ms** on the first flood (profile §1). This is the 1–2% tail, not the headline.

Only a real execution of the real code warms layers 1–3. Hence: run searches at boot.

---

## 3. The mechanism — `NavWarmup` (as shipped)

### 3.1 The synthetic-grid seam is already production-grade

`NavGridView` has **two** no-live-level constructors (NavGridView.java:98, 113):

- the package-private `NavGridView(int minY, ConcurrentHashMap<Long, NavSection[]> chunks)` — the
  test/benchmark seam;
- the **public** `NavGridView.overSections(minY, chunks)` — added for `LeafCostComputer`, i.e. the
  production HPA leaf-cost mini-pathfinds already run block-A* over synthetic views today. Using it
  for warm-up is not a new category of use.

Contract (documented on the ctor): `level == null` ⇒ the live `getBlockState` fallback must never
fire ⇒ every cell the search can probe must be inside the built map. The benchmark satisfies this
with a 9×9-chunk span (±64 blocks) around a flood of radius ~32; warm-up copies that discipline.

**Placement:** `com.orebit.mod.worldmodel.pathing.NavWarmup` (same package as the seam — can use
either ctor, `NavStore.key`, and bypass `NavSectionPool` if ever needed). All-cold code; it's a smart
object that owns its fixture, not a Utils bag.

### 3.2 Fixture construction — do NOT copy the benchmark's `PalettedContainer` path

`PathfinderBenchmark.buildFlatChunks` builds sections via
`new PalettedContainer<>(…)` + `NavSectionBuilder.classifyInto`. That ctor is 1.18+-shaped and the
benchmark only compiles on the 1.21-era **test** source set. Putting it in common `src/` would drag
the most version-fragile code in the project (`NavSectionBuilder`'s PalettedContainer reflection) into
a new call path across 1.17→26.x for no reason.

Instead, fill the grid **directly** — every ingredient is public, common-src, and version-stable:

```
short stone = NavBlock.navtypeFor(Blocks.STONE.defaultBlockState());   // NavBlock.java:388
short water = NavBlock.navtypeFor(Blocks.WATER.defaultBlockState());
NavSection s = NavSection.create(origin);                              // pooled; see §3.6
s.getTraversalGrid().set(x, y, z, navtype, flags=0);                   // TraversalGrid.java:80
NavSectionBuilder.computeFlags(grid, selfAllAir, aboveGrid);           // NavSectionBuilder.java:245
```

`computeFlags` is the same public flag pass the live pipeline uses (including the s42 vertical seam
overscan via the `above` grid), so the fixture exercises the *real* NavFlags-consuming branches. Zero
`PalettedContainer`, zero reflection, zero overlay work. Fixture memory: a `TraversalGrid` is
`short[4096]` = 8 KB; ~8–10 distinct sections ≈ **<100 KB**, plus one small chunk map. All released
after warm-up (§3.6).

### 3.3 Scenario set — what warms what

Reuse the benchmark geometries plus additions chosen for **branch-profile coverage**, not speed
measurement:

| Scenario | Geometry | Code it warms |
|---|---|---|
| W-SHORT ×~400 | 28-block flat walk, fresh `NavGridView` per search | Per-search construction: `NavGridView` ctor, `MovementContext`+`PathEdits` init, `GoalForcedCost.probe`, `reconstruct` — run once per search, so only repetition count warms them. |
| W-FLOOD ×~16 | Floating goal 30 up over flat ground, `BREAK_PLACE`, corridor, budget-exhausted at `maxNodes` | The A* inner loop (OSR C2); per-read chain `descriptorAt/kindAt/sectionAt` at ~1M invocations per flood; `intern`/heap; edit-bearing Pillar/MineDown relax path; `EditPool` fill to high-water. |
| W-WALL ×~50 | UPOVER_WALL clone (thin wall fragments Y-cuboids) | `CuboidExtractor` + `NavGridCuboidsView` edit-shrink under cache misses, Parkour/Climb candidate bodies, partial reconstruct. |
| W-WATER ×~50 | 2-deep pond crossing (water navtypes + `MODE_PRONE` start) | The swim family (`Swim`/`SprintSwim`/`StartSprintSwim`/`Surface`) bodies, the PRONE mode branch — otherwise profiled never-taken. |
| W-EDGE (folded into W-FLOOD) | Flood reaches the built-map boundary | The `section == null` / UNBUILT branches — taken in-game at the loaded radius, never on an all-built fixture. |

The binding constraint is the **per-search** code (1 invocation per search), so ~500 total searches
puts setup/reconstruct past C1 with headroom; per-node methods clear C2 inside the first flood.
**Termination:** run rounds until a plateau (last round's mean W-SHORT time within 15% of the best)
with a **hard budget cap** (`pathing.warmupBudgetMs`, default 1500). The plateau check waits out the
asynchronous C2 queue without guessing compile latency.

### 3.4 Hook point and ordering

Register in `OrebitCommon.init` via `events.onServerStarted(NavWarmup::run)`, **after** the existing
two registrations (Fabric lifecycle callbacks fire in registration order):

1. `ConfigLoader::load` — Config + BotCaps installed (warm-up reads the warmup keys, uses realistic caps);
2. `MiningModel.buildTable` — forces NavBlock static-init and fills the mining tables the movements
   price breaks from (guard: `MiningModel.ready()` / early-out with a WARN);
3. **`NavWarmup.run`** (new).

`SERVER_STARTED` fires on the **server main thread**, before the tick loop serves players — no player
can have joined, so no real search can race it.

### 3.5 Thread choice — synchronous on the server thread

The tension: JIT-compiled code and profiles are **JVM-global** (any thread warms them), but the
`Nodes`/`EditPool` scratch is **ThreadLocal** (only the searching thread's copy grows), and searches
run on the tick thread today.

**Chosen: synchronous, on the server thread, inside `onServerStarted`.** Rationale:

- It warms the *exact* thread that will search: the tick thread's ThreadLocal scratch reaches its
  high-water as a side effect of W-FLOOD — which is what makes eager sizing mostly moot for the tick
  thread.
- Zero concurrency questions: `LAST_*`/`LOG_TIMING` statics, and — decisive — **`NavSectionPool` is a
  bare `ArrayDeque` with no synchronization** (NavSectionPool.java:10), thread-confined to the tick
  thread. On-thread, the fixture can use the pool and `recycle()` cleanly.
- Cost: a one-time **0.3–1.5 s** at server start (dedicated: invisible; integrated/LAN: added to the
  world-open loading screen — the one real UX cost).

**Rejected — background thread at init:** gets the JIT win but not the scratch win (its ThreadLocal
dies with the thread), must bypass `NavSectionPool`, and can be mid-warm-up when a fast joiner's bot
first searches. Kept as the documented fallback if integrated-server open-time measures objectionable.
**Rejected — per-tick budgeted after start:** more machinery, and it *races the player joining* — the
exact window we're protecting.

### 3.6 Cleanup and repeat-start behavior

- Fixture sections: `recycle()` back to `NavSectionPool` (`reset(origin)` wipes them on next `get`, so
  no stale-grid contamination). Chunk maps + views: drop references.
- `LOG_TIMING`: save → set `false` → restore around the loop (single-threaded at that point). `TRACE`
  asserted off. `LAST_*` clobber is pre-consumer.
- Integrated server, world re-open in the same JVM: `SERVER_STARTED` fires again on a **new** server
  thread. JIT is already warm ⇒ the plateau check exits in one or two rounds (~50–100 ms), which also
  touches the new thread's ThreadLocal scratch.
- Config: `pathing.warmup=true` (default), `pathing.warmupBudgetMs=1500`. The off-switch is the escape
  hatch and the A/B lever for measurement.

---

## 4. Outcome and pointers

- **Warm-up: ADOPTED.** Measured on the fresh-JVM cold-start harness — first search **21.8 → 0.67 ms
  p50 (32×)**, p90 30.0 → 0.81 ms, boot cost 475 ms median (never hit the 1500 ms cap). The JIT
  mis-training risk that motivated the W-WATER/W-WALL/W-EDGE fixture diversity did **not** materialize
  (no deopt storm; p90 stayed near warm steady-state). Full record: `PERF-RESULTS-2026-07-03.md` §E5.
- **Eager-size scratch: FALSIFIED + REVERTED.** `Nodes(8192, 8192)` pinned SHORT **+4–7%** because
  `Nodes.reset()` fills `mapRow` over CAPACITY per search. Needs lazy-clear / epoch-stamped slots
  before retrying; the EditPool prefill was dropped at design time. Full record:
  `PERF-RESULTS-2026-07-03.md` §E5b.
- The shipped mechanism (fixture builders, scenario loop, plateau/budget, cleanup) is authoritative in
  `worldmodel/pathing/NavWarmup.java`; the original deep mechanism / JIT-mis-training-risk /
  measurement-plan / risk-register / effort / alternatives sections were dropped once the code landed.
