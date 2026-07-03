# PERF DESIGN REVIEW — Boot-time warm-up searches + eager-size scratch (perf item b)

> **STATUS (2026-07-03): half ADOPTED, half REVERTED.**
> **Warm-up: ADOPTED, LIVE, default true** — `worldmodel/pathing/NavWarmup` (~250 lines), synchronous in
> the third `onServerStarted` handler (after ConfigLoader + MiningModel), plateau-detected (min 4 / max 8
> rounds), `pathing.warmup` + `pathing.warmupBudgetMs` (1500) config keys. Measured on the fresh-JVM
> cold-start harness (10 runs/arm): first search **21.8 → 0.67 ms p50 (32×)**, p90 30.0 → 0.81 ms; boot
> cost 475 ms median (never hit the cap). Post-adoption it also sweeps `computeDepth` (depth-nibble
> parity with live grids). Remaining pre-ship check: the in-game world-open UX delta (§7).
> **Eager-size scratch: FALSIFIED + REVERTED** — the `Nodes(8192, 8192)` one-liner cost pinned SHORT
> **+4–7%**: `Nodes.reset()` does `Arrays.fill(mapRow, -1)` over CAPACITY, so every flood-free search
> paid +28 KB of fill. Any future eager sizing needs lazy clearing / epoch-stamped slots — a design pass,
> not a one-liner. (The EditPool prefill was already dropped at design time, as §5 recommends.)
> Results: `PERF-RESULTS-2026-07-03.md` §E5/E5b.

**Original status:** DESIGN ONLY — nothing implemented. Per the standing rule (CLAUDE.md "Performance model",
HANDOFF.md rule 1), this goes to the owner before a line of code is written.

**Scope:** the two halves of HANDOFF next-menu item 6(b):

1. **Boot-time warm-up searches** — the only fix for the 16 ms JIT-cold first search
   (`PERF-PROFILE-2026-07.md` S1, and ~61% of S2).
2. **Eager-size the ThreadLocal search scratch** (~2.3 MB/search-thread) — smoothing only, per the
   same profile.

**Non-goals:** the per-pop edit-bbox gate (item 6a), cuboid persistence (6c), NavGrid widening (6d),
and the region tier's own first-plan cold cost (see §4.7).

---

## 1. Problem statement — what the profile actually established

Three in-game samples (26.2, `PERF-PROFILE-2026-07.md` header table + §5):

| Sample | Observation | Attribution (profile §5) |
|---|---|---|
| S1 | `2 nodes in 16275.5 us` — first search after boot | **Classloading + interpreted/C1 first execution** of the whole pathfinder class graph. The lazily-allocated scratch is ~41 KB; eager allocation "saves microseconds of the 16 ms." |
| S2 | `8357 nodes / 5680 ns/node` — first big search | Growth-reallocation + first-touch EditPool fill ≈ 2.2 MB ≈ **≲1 ms ≈ 1–2%**. The warm analog runs 2226 ns/node, so (5680−2226)×8357 ≈ **28.9 ms ≈ 61% of the search is JIT warm-up** — "C2 still compiling the hot loop, cold i-cache/branch predictors." |
| S3 | `7221 nodes / 2226 ns/node` warm | Algorithmic (`kindAt` 49% — that's item 6a, not this doc). |

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

## 3. Proposed mechanism — `NavWarmup`

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

**Placement:** a new class `com.orebit.mod.worldmodel.pathing.NavWarmup` (same package as the seam —
can use either ctor, `NavStore.key`, and bypass `NavSectionPool` if ever needed). All-cold code; no
design-principles tension (it's a smart object that owns its fixture, not a Utils bag).

### 3.2 Fixture construction — do NOT copy the benchmark's `PalettedContainer` path

`PathfinderBenchmark.buildFlatChunks` builds sections via
`new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, air, Strategy.SECTION_STATES)` +
`NavSectionBuilder.classifyInto`. That ctor is 1.18+-shaped and the benchmark only compiles on the
1.21-era **test** source set. Putting it in common `src/` would drag the most version-fragile code in
the project (`NavSectionBuilder`'s PalettedContainer reflection) into a new call path across
1.17→26.x for no reason.

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
`PalettedContainer`, zero reflection, zero overlay work. MC surface = `Blocks.*.defaultBlockState()`
+ `BlockPos` — already used throughout common src.

Fixture memory: a `TraversalGrid` is `short[4096]` = 8 KB; ~8–10 distinct sections ≈ **<100 KB**,
plus one small chunk map. All released after warm-up (§3.6).

### 3.3 Scenario set — what warms what

Reuse the benchmark geometries (same package, so the constants can be shared or duplicated) plus two
additions chosen for **branch-profile coverage**, not speed measurement:

| Scenario | Geometry | Code it warms |
|---|---|---|
| W-SHORT ×~400 | 28-block flat walk, fresh `NavGridView` per search | Per-search construction: `NavGridView` ctor, `MovementContext`+`PathEdits` init, `GoalForcedCost.probe`, `reconstruct`. These run **once per search**, so only repetition count warms them (400 ≈ 2× the ~200-invocation C1 threshold; ~400 × 50 µs ≈ 20 ms total). |
| W-FLOOD ×~16 | Floating goal 30 up over flat ground, `BREAK_PLACE`, corridor, budget-exhausted at `maxNodes` (10 001 pops) | The A* inner loop (10k back-edges/search → OSR C2 within ~4 searches at ~40k back-edge threshold); per-read chain `descriptorAt/kindAt/sectionAt` at ~1M invocations **per flood** (~66× the C2 invocation threshold in ONE search); `intern`/heap at 10k+/search; edit-bearing Pillar/MineDown relax path; `EditPool` fill to high-water (§5). |
| W-WALL ×~50 | UPOVER_WALL clone (thin wall fragments Y-cuboids) | `CuboidExtractor` + `NavGridCuboidsView` edit-shrink under cache misses, Parkour/Climb candidate bodies over real obstacles, partial reconstruct. |
| W-WATER ×~50 | 2-deep pond crossing (water navtypes + `MODE_PRONE` start) | The swim family (`Swim`/`SprintSwim`/`StartSprintSwim`/`Surface`) *bodies*, the PRONE mode branch — otherwise profiled never-taken (§4.4). |
| W-EDGE (folded into W-FLOOD) | Flood allowed to reach the built-map boundary | The `section == null` / UNBUILT branches — taken in-game at the loaded radius, never taken on an all-built fixture. |

Invocation math, to justify the counts: per-node methods clear C2 thresholds inside the first flood
(1M reads ≫ 15k); the loop C2-OSRs by flood ~4 (40k back-edges); movement `candidates()` sees
13 × 10k = 130k calls per flood. The binding constraint is the **per-search** code (1 invocation per
search) — hence ~500 total searches so setup/reconstruct at least reach C1 (~200) with headroom.
`findPath` the method never reaches C2 by invocation count — irrelevant, its body is the OSR-compiled
loop. Wall-clock estimate: 16 floods × ~1–5 ms (falling as tiers kick in) + ~500 small × ~15–100 µs
≈ **0.1–0.3 s**, then idle-spin until the plateau check passes (C2 queue drain) — call it
**0.3–1 s total**, hard-capped.

**Termination:** run rounds until a plateau — e.g. the last round's mean W-SHORT time within 15% of
the best round — with a **hard budget cap** (`pathing.warmupBudgetMs`, default 1500). The plateau
check is what waits out the asynchronous C2 queue without guessing compile latency.

### 3.4 Hook point and ordering

Register in `OrebitCommon.init` via `events.onServerStarted(NavWarmup::run)`, **after** the existing
two registrations (Fabric lifecycle callbacks fire in registration order):

1. `ConfigLoader::load` — Config + BotCaps installed (warm-up reads the warmup keys, and uses
   realistic caps);
2. `MiningModel.buildTable` — forces NavBlock static-init and fills the mining tables the movements
   price breaks from (guard: `assert MiningModel.ready()` / early-out with a WARN);
3. **`NavWarmup.run`** (new).

`SERVER_STARTED` fires on the **server main thread**, before the tick loop serves players — on a
dedicated server, before "Done (…s)!"; no player can have joined, so no real search can race it.

### 3.5 Thread choice — synchronous on the server thread (recommended)

The tension: JIT-compiled code and profiles are **JVM-global** (any thread warms them), but the
`Nodes`/`EditPool` scratch is **ThreadLocal** (only the searching thread's copy grows), and searches
run on the tick thread today.

**Recommended: synchronous, on the server thread, inside `onServerStarted`.** Rationale:

- It warms the *exact* thread that will search: the tick thread's ThreadLocal scratch reaches its
  8192/8192/4096/16384 high-water as a side effect of W-FLOOD — which is what makes §5's eager-size
  question mostly moot (the profile itself notes warm-up "touches/grows the ThreadLocal scratch to
  high-water as a side effect (making item 2 nearly free)", §7.1).
- Zero concurrency questions: `LAST_EXPANSIONS`/`LAST_WAS_PARTIAL`/`LOG_TIMING` statics, and —
  decisive — **`NavSectionPool` is a bare `ArrayDeque` with no synchronization**
  (NavSectionPool.java:10), thread-confined to the tick thread. A background-thread warm-up that
  touched `NavSection.create()` would race live chunk loads. On-thread, the fixture can use the pool
  and `recycle()` cleanly.
- The cost is a one-time **0.3–1.5 s** added to server start (dedicated: invisible against a 5–20 s
  start; integrated/LAN: added to the world-open loading screen — the one real UX cost, see risks).

**Rejected alternative — background thread at init:** gets ~all of the JIT win, none of the scratch
win (its ThreadLocal dies with the thread and is GC'd), must bypass `NavSectionPool` via a
package-private `new NavSection()`, must not save/restore `LOG_TIMING` (racy), and can still be
mid-warm-up when a fast joiner's bot first searches (benign — searches are ThreadLocal-isolated — but
the first search is then only *partially* warm). Keep as the documented fallback if integrated-server
open-time measures objectionable.

**Rejected alternative — per-tick budgeted after start:** spreads the same work across the first
~20–60 ticks; more machinery (a ticking drainer), and the warm-up now *races the player joining* —
the exact window we're trying to protect. Only worth it if the synchronous cost measures too high.

### 3.6 Cleanup and repeat-start behavior

- Fixture sections: `recycle()` back to `NavSectionPool` (they came from it; `reset(origin)` wipes
  them on next `get`, so no stale-grid contamination — the pool stores clean-on-reuse objects).
  Chunk maps + views: drop references; ~8 KB/view of per-search garbage is the documented steady
  state anyway (profile §4).
- `LOG_TIMING`: save → set `false` → restore around the loop (single-threaded at that point;
  otherwise ~500 INFO lines at boot). `TRACE` asserted off. `LAST_*` clobbered — no consumer exists
  before the first real search.
- Integrated server, world re-open in the same JVM: `SERVER_STARTED` fires again on a **new** server
  thread. JIT is already warm ⇒ the plateau check exits in one or two rounds (~50–100 ms), which
  conveniently also touches the new thread's ThreadLocal scratch. A static `warmedThisJvm` flag can
  additionally shrink the repeat pass to a fixed small round (e.g. 1 flood + 50 shorts).
- Config: `pathing.warmup=true` (default), `pathing.warmupBudgetMs=1500`. The off-switch is the
  escape hatch for any surprise and the A/B lever for measurement.

### 3.7 What this deliberately does not warm

- **The region tier** (`PathPlan`/`RegionPathfinder`/fragment cascade/`LeafCostComputer`): its
  first-plan cold cost is real but unprofiled and smaller (leaf costs mostly take the s30 walk-only
  fast-paths). S1's 16 ms is measured *inside* `findPath`. If first-plan latency still shows after
  this ships, extend warm-up with a synthetic region fixture as a separate, measured follow-up.
- The classify pipeline (`NavSectionBuilder.classifyInto`) — warmed by real chunk loads at world
  start, well before any search.
- Ladder/hazard/portal descriptor branches (no fixture cells): accepted as bounded
  first-encounter deopts (§4.4). Add fixture strips later only if measurement shows it matters.

---

## 4. The subtle risk — can a synthetic grid MIS-train the JIT?

Honest answer: **yes, mildly, and the failure mode is bounded and one-time, not persistent.**

1. **Mechanism.** C2 compiles against gathered profiles: never-taken branches become uncommon traps;
   type profiles drive inlining/devirtualization; loop trip counts drive unrolling. A branch the
   synthetic world never takes but the live world does ⇒ first live hit fires the trap ⇒
   **deoptimize → re-profile → recompile with merged profiles**. Cost per event: microseconds of
   deopt + a re-interpreted stretch + a background recompile — a few ms of one-time jitter spread
   over the first live searches, after which the compiled code is *better* trained than either
   profile alone. It cannot get stuck wrong: HotSpot recompiles on trap-count, it does not pin the
   synthetic profile forever.
2. **Where the profiles genuinely differ.** Synthetic: 2–4 navtypes, all-built cells, 2 shared
   section instances (near-perfect chunk-cache hits), no water/hazard/climb/portal bits, `level ==
   null` (live `getBlockState` fallback never taken — also nearly-never taken in-game). Live: ~587
   navtypes (data, not branches — descriptor decode is branch-free bit math, immune by design), many
   section instances (cache-miss branch), occasional unbuilt/water/hazard cells.
3. **Why the blast radius is small here specifically.** The per-read hot path was *deliberately
   built branch-free/predictable* (CLAUDE.md performance model: "prefer branch-free bit math …
   prefilter bits"): descriptor field extraction is shifts/masks; navtype variety changes *data*,
   not control flow. The movement dispatch (`TIER1` loop) is megamorphic in both worlds — no false
   monomorphization to lose. Section/grid types are monomorphic in both worlds. The remaining
   exposure is exactly the enumerable branch classes in (2).
4. **Mitigation = fixture diversity, not cleverness.** W-WATER covers the swim family + PRONE mode;
   W-EDGE covers UNBUILT; W-WALL covers cuboid fragmentation + Parkour/Climb bodies; W-FLOOD covers
   the edit-bearing relax path so `anyEdits=true` code isn't trap-pruned. Residual (ladder, hazard
   cells, portal bit, live-fallback): accepted first-encounter deopts, each worth ≪1 ms once.
5. **Net verdict:** worst case, the first live search in an unseen regime pays a few one-time
   deopt/recompile events — orders of magnitude cheaper than the 16 ms fully-cold baseline it
   replaces, and self-healing. Not a blocker. The measurement plan (§7) checks it empirically: the
   first *live* search after warm-up must land near warm steady-state, which it cannot do if
   mis-training were material.

---

## 5. Eager-size scratch — analysis and recommendation

Current lazy state vs measured high-water (profile §1, `maxNodes=10000`):

| Structure | Initial | High-water | Fully-grown bytes |
|---|---|---|---|
| `Nodes` table (10 arrays × 44 B/row) | 512 rows | 8192 | 360 KB |
| `Nodes` map (12 B/slot) | 1024 | 8192 | 96 KB |
| `Nodes` heap (12 B/entry) | 512 | 4096 | 48 KB |
| `EditPool` (slots + first-touch `StepEdits` ≈112 B) | 256 / 0 | 16384 / ~14k | ~1.72 MB |
| **Total per search thread** | ~41 KB | | **≈2.2–2.3 MB** |

First-search growth bill: ≈2.2 MB of alloc/copy/zero ≈ **≲1 ms ≈ 1–2% of S2** (profile §1). That is
the *entire* addressable win of eager sizing — the profile's own words: "book it as smoothing, not as
the S2 fix."

**Interaction with warm-up:** a synchronous on-thread W-FLOOD grows the tick thread's scratch to
high-water *organically* during boot. After warm-up ships, eager sizing buys the tick thread
**nothing**; its only remaining value is (a) configs with `pathing.warmup=false`, and (b) future
background search threads, each of which would otherwise re-pay ~1 ms of growth once, mid-first-search.

**Recommendation — split the two halves:**

- **`Nodes` initial sizing → TAKE as a one-liner** *(`new Nodes(512, 1024)` → `new Nodes(8192, 8192)`,
  BlockPathfinder.java:206)*: +~0.5 MB/thread resident, zero steady-state cost (first-touch only),
  kills every table/map/heap growth hitch on any thread regardless of the warmup config. Booked
  expected win: **~0** (smoothing); justification is favor-CPU-over-RAM + robustness, not a number.
  Behavior note: map capacity changes internal probe layout only — row append order, heap order,
  f-values, and returned paths are capacity-independent ⇒ **byte-identical results**. Guard:
  SHORT/MULTI must stay flat (they will — nothing per-search changes).
  Scaling note (profile §7.2): if `pathing.maxNodes` is ever raised, size as
  nodeHint = next pow2 ≥ 0.8×maxNodes, map = same, heap = nodeHint/2 — derive from the loaded
  Config rather than hardcoding, or accept one growth cycle on exotic configs.
- **`EditPool` prefill → DROP.** Eagerly constructing ~16k `StepEdits` (~1.7 MB + a ctor loop) at
  init duplicates exactly what W-FLOOD does on the right thread, and unlike array sizing it is not
  one line — it front-loads the single largest allocation burst onto every startup including
  warmup-off ones that may never flood. Lazy first-touch + warm-up covers it.

If the owner rejects warm-up entirely, revisit: then both halves together are worth their ~1 ms
smoothing at 2.3 MB/thread and the profile's §7.2 says just do it.

**Residency/lifecycle:** ThreadLocal on the server thread ⇒ lives for the server instance's
lifetime; ~2.3 MB × (1 + future background search threads). The profile flags the only caveat: if
searches ever fan out per-bot across a pool, cap the pool size. On integrated-server world close the
thread dies and the scratch is GC'd with it; the next world's thread re-grows (or re-warms, §3.6).

---

## 6. Invariants (what reviewers should hold me to)

1. **Zero hot-path code change for warm-up.** `NavWarmup` is additive init-only code; `findPath` and
   everything under it is untouched ⇒ real searches are byte-identical by construction. The `Nodes`
   sizing one-liner is argued byte-identical in §5 and guarded by the full JMH suite.
2. **Zero live-state contact.** The fixture is a private `ConcurrentHashMap` + pooled sections
   recycled afterward; `NavStore`, `RegionGrid`, `HpaMaintenance`, `BotManager` are never touched.
   `overSections`' no-live-fallback contract is honored by the ±64-block built span vs the ~32 flood
   radius (same discipline as the bench fixture).
3. **Static seams restored.** `LOG_TIMING` save/restore; `TRACE`/`PARTIAL_PATH`/`IRREVERSIBLE_GUARD`
   read-only; `LAST_*` clobber is pre-consumer.
4. **Thread discipline.** Warm-up runs on the server thread (or, in the fallback design, provably
   never touches `NavSectionPool`/`LOG_TIMING`).
5. **Ordering.** Registered after `ConfigLoader::load` + `MiningModel.buildTable`; refuses to run
   (WARN + return) if `MiningModel.ready()` is false.
6. **Bounded.** Hard wall-clock cap; config off-switch; no allocation beyond the ~100 KB fixture +
   normal per-search garbage.

---

## 7. Measurement plan

JMH cannot see the target (profile §6) — the credible instrument is **fresh-JVM, in-game timing via
the existing LOG_TIMING line**, config-toggled so both arms are the *same build*:

1. **Build once** with warm-up + the sizing one-liner. Arms: A = `pathing.warmup=false`,
   B = `true`. Interleave launches A,B,A,B… (JIT/OS state can't skew a fresh JVM, but interleaving
   is free and kills environmental drift).
2. **Protocol per launch** (26.2 `runClient`, fixed test world, fixed spawn): join → stand at a
   marked spot → `/bot come` from ~20 blocks (S1 shape) → walk ~150 blocks and `/bot come` again
   (S2 shape) → quit. Harvest from `versions/26.2/run/logs/latest.log`: the first
   `N nodes in X us (Y ns/node)` line (S1 metric), the first ≥1000-node line (S2 metric), and
   NavWarmup's own summary line (searches run, wall time, plateau round).
3. **N = 10 launches per arm** (first-search timings are noisy — classload I/O, page cache); compare
   p50 and p90.
4. **Success criteria:**
   - S1-shape first search p50 **< 1 ms** (from ~16.3 ms — ≥16×; stretch: <0.5 ms). Residual over
     warm steady-state = live chunk-cache cold misses + any §4 deopts; p90 < 2 ms guards deopt
     storms.
   - S2-shape first big search **< 3000 ns/node** (from 5680; warm analog floor is 2226 — i.e.
     recover ≥ ~78% of the 61% warm-up share).
   - Warm-up wall time ≤ 1.5 s dedicated; integrated-server world-open delta recorded and shown to
     the owner before ship (the one UX number).
   - **Guard:** full JMH suite (TOWER/OPEN/UPOVER_OPEN/UPOVER_WALL/SHORT/MULTI), paired interleaved
     A/B against the pre-change baseline — expected **all flat within noise** (warm-up code is not
     on any bench path; the sizing one-liner is first-touch-only). Any scenario delta ⇒ pinned
     `-Pscenario` fresh-JVM re-check per the forks=0 rule.
   - Unit tests green (119/29 baseline), chiseledCompileCommon 1.17.1→1.21.11 + `:26.2:compileJava`.
5. **Revert rule:** standing protocol — if the S1/S2 wins don't reproduce or any guard regresses
   beyond noise, revert without sentiment. (Given §1's attribution the win should be robust; the
   revert-risk sits almost entirely in the integrated-server open-time UX.)

---

## 8. Risk register

| # | Risk | Sev | Mitigation |
|---|---|---|---|
| 1 | JIT mis-training → deopt jitter on first live searches | Low | §4: fixture diversity (water/wall/edge/edits); bounded one-time cost; p90 criterion catches it empirically |
| 2 | Integrated-server world-open delay (+0.3–1.5 s) | Med (UX) | Hard cap + plateau early-exit + `warmedThisJvm` shrink on re-open + config off-switch; measure and show the owner the number |
| 3 | `NavSectionPool` contamination / thread race | Low | On-thread execution; `recycle()` + `reset(origin)`-on-reuse; fallback design bypasses the pool |
| 4 | Fixture version drift (1.17→26.x) | Low | No `PalettedContainer`/reflection — only `NavBlock.navtypeFor` + `TraversalGrid.set` + `computeFlags` + stable `Blocks` constants; gate = chiseledCompileCommon |
| 5 | Ordering bug (warm-up before MiningModel/Config) | Low | Registration order + `MiningModel.ready()` guard |
| 6 | Plateau never converges (compiler busy with server code) | Low | Hard wall-clock cap governs; plateau is only an early-exit |
| 7 | Warm-up itself throws (fixture bug) on some version | Low | Wrap in try/catch → WARN + continue boot (same degrade-don't-crash posture as `replan`) |
| 8 | False confidence: JMH “can’t regress” | Low | It also can't confirm — §7's fresh-JVM in-game protocol is the primary instrument, JMH is only the guard |
| 9 | Future background-path thread starts cold-scratch | Nil | JIT already global-warm; `Nodes` one-liner covers sizing; EditPool re-pays ~1 ms once — acceptable |

---

## 9. Effort estimate

- `NavWarmup` (fixture builders + scenario loop + plateau/budget + logging): ~200–300 lines, cold
  code, same package as `NavGridView` — **~half a session**.
- `OrebitCommon.init` registration + 2 Config keys (`pathing.warmup`, `pathing.warmupBudgetMs`) +
  the `Nodes(8192, 8192)` one-liner: trivial.
- Measurement: 20 interleaved fresh-JVM launches + one JMH guard pair — **~half a session**
  (dominated by launch time; the log-grep is scriptable).
- Cross-version gate + commit dance per HANDOFF: standard.

Total: **~1 session** including measurement, assuming no surprises in the integrated-server number.

---

## 10. Alternatives considered and rejected

- **AppCDS / Project-Leyden-style AOT:** attacks classloading only, not C2 profile warm-up (the 61%
  of S2); not deployable inside a mod jar across three loaders. Out of scope.
- **Raise the first search's node budget tolerance / hide latency behind the follower:** treats the
  symptom; the tick still stalls.
- **Warm by replaying a real-level search at bot spawn:** uses live NavStore state (violates the
  zero-live-state invariant), races chunk builds, runs *after* a player is present — exactly the
  window to protect — and warms no earlier than the problem it fixes.
- **Eager scratch alone (the original owner question):** already answered by the profile — buys
  1–2% of S2 and none of S1/S3. Subsumed here as §5's one-liner + drop-prefill split.
