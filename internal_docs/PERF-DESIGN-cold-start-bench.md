# PERF DESIGN — Direct per-search-setup measurement (SETUP / SETUP_MACRO scenarios)

> **STATUS: PROPOSED.** Bench code IS added (measurement tooling, not a perf change):
> `PathfinderBenchmark` gains two scenarios, `SETUP` and `SETUP_MACRO`. **No production code touched.**
> The scenarios have NOT been executed yet — `src/test` is not compiled by the 26-era build on this
> branch, so the addition was verified by eye against the existing benchmark style and must get its
> first run on the mc-1.21 era branch (`Set active project to 1.21.4` →
> `:1.21.4:jmh -Pbench=PathfinderBenchmark -Pscenario=SETUP` / `SETUP_MACRO`).

## §1 Two different "cold starts" — keep the vocabulary straight

The repo already has a cold-start instrument, and it measures a DIFFERENT thing:

| Name | Instrument | What it measures | Regime |
|---|---|---|---|
| **JIT-cold first search** | `ColdStartHarnessTest` + the bench-worktree `:coldstart` task (`internal_docs/bench-harness-mc121-buildscript.patch`) | The first `findPath` wall-clock in a FRESH JVM (21.8 → 0.67 ms with `NavWarmup`) — ~61% JIT warm-up, by design invisible to the forks=0 JMH suite | once per server boot |
| **Per-search setup** (this doc) | new `SETUP` / `SETUP_MACRO` scenarios | The steady-state FIXED cost every replan pays before the first node relaxes: fresh view + scratch reset + context/relaxer construction (+ macro view + goal probe) | every replan, ~every 2 s per bot, forever |

The owner's ask ("measure cold-start directly instead of FLOOD-minus-SHORT arithmetic") is the second
row. Everything below is about steady-state per-search setup; JIT warm-up is `NavWarmup`'s solved
problem and stays with the fresh-JVM harness.

## §2 What SHORT actually measures (the analysis)

Read of `PathfinderBenchmark` (post-s50 file, `src/test/java/com/orebit/mod/worldmodel/pathing/PathfinderBenchmark.java`):

- **`@State(Scope.Benchmark)` + `@Setup(Level.Trial)`**: fixtures (`freshChunks`) are built ONCE per
  scenario trial. There is no `@Setup(Level.Invocation)` anywhere — nothing per-op happens outside the
  measured method.
- **SHORT's measured op DOES include fresh-view construction**: `findPath(new NavGridView(0, freshChunks), …)`
  is inside `findPath(Blackhole)` (the `KIND_SHORT` case). So SHORT = per-search setup **plus** a
  ~30–60-pop trivial walk **plus** reconstruct. It bundles; it does not isolate.
- **SHORT does NOT pay the macro setup at all.** It calls the 5-arg overload with `bound == null`,
  which fans out to `confineBound == cuboidBound == null` (`BlockPathfinder.java:529-548`). Down in the
  deepest overload that means:
  - no `NavGridCuboidsView` is constructed (`BlockPathfinder.java:690-691` — gated on
    `cuboidBound != null`);
  - `GoalForcedCost.probe` **early-returns** on `cuboids == null` (`GoalForcedCost.java:218-220`).

  Production window replans are the OPPOSITE shape: `PathPlan` passes `confineBound == null` with a
  non-null window `cuboidBound`, so every live replan pays the cuboid-view construction **and** the
  one-shot goal-face probe — the item `PERF-PROFILE-2026-07.md` attributed **38–45% of small-search
  time** to (probe cuboid extraction). **No existing scenario measured that setup component in
  isolation, and SHORT — the designated "cold-start guard" — excludes it entirely.**

**Why FLOOD − SHORT arithmetic is the wrong derivation** (three independent reasons):

1. FLOOD (no corridor) and SHORT (no corridor) BOTH skip the macro setup, so the subtraction can never
   surface the goal-probe/cuboid-view bill at all — the biggest known setup item cancels out of the
   arithmetic because neither term contains it.
2. The subtraction assumes per-node marginal cost is scenario-independent. It isn't: FLOOD nodes are
   edit-bearing pillar pops paying the per-pop `PathEdits` chain rebuild (`BlockPathfinder.java:777-788`);
   SHORT nodes are edit-free walks. Dividing the difference by an expansion delta mixes two different
   per-node currencies.
3. forks=0 JIT cross-pollution (the CLAUDE.md "Performance model" trap): FLOOD and SHORT run in the same
   JVM with different profile shapes; a derived number inherits both scenarios' layout noise, doubled.

## §3 The per-search setup bill, itemized (what SETUP/SETUP_MACRO actually time)

From the deepest `findPath` overload (`BlockPathfinder.java:645-721`), in execution order:

| Item | Where | Cost character |
|---|---|---|
| `new NavGridView` (live: `NavGridView.background`) | measured op / `PathPlan` | **~8 KB zeroed per view**: `long[512] ccKeys` + `NavSection[512][] ccVals` (`NavGridView.java:78-82`) plus the single-slot fields. This is a deliberate, known cost (the boxing-killer cache) — but it is paid per REPLAN, not per search-second. |
| `LAST_*` ThreadLocal touches | `BlockPathfinder.java:651-652` | 2 TL gets |
| `grid.built(start)` gate | `:657` | one chunk resolve (cold miss → CHM get + boxing, once) |
| `new MovementContext(grid, caps)` | `:672` | ctor + whatever its fields allocate (incl. its `PathEdits`) |
| start-mode geometry probe | `:678-681` | ≤4 grid reads |
| `new NavGridCuboidsView` | `:690-691` — **cuboidBound != null only** | small: 3 refs + `Cuboid[3][]`/`int[3]`×2 + 4 `Cuboid` objects up front (`NavGridCuboidsView.java:84-88`); per-axis `Cuboid[16]` lazily on first extraction |
| `GoalForcedCost.probe` | `:699-706` — no-ops when cuboids null | **the CPU item**: up to 5 goal-face evaluations, each potentially a maximal-cuboid extraction (the 38–45% profile item) |
| `SEARCH.get().reset()` / `EDIT_POOL.get().reset()` | `:710-713` | high-water-mark sized fills — exactly what E5b ("eager-size scratch") regressed by inflating; a direct SETUP number would have caught E5b in one pinned run |
| `new Relaxer(…)` | `:715-716` | ctor |
| intern + push + first pop + `reconstruct` | `:718-721`, `:1297` | trivial (empty plan) |

## §4 The new scenarios

**Design goal: a measured op that pays ALL of §3 and NOTHING else — zero expansions, zero relaxations.**

The mechanism is the arrival tolerance: `isGoal` accepts within 1 block horizontally / 2 vertically
(`BlockPathfinder.java:1028-1030`), and the goal check runs at pop time BEFORE the expansion counter
increments (`:750-753` vs `:771`). A start already inside the tolerance therefore terminates on the
first pop with `lastExpansions() == 0` and an empty (non-null) plan via `reconstruct(start, start)`.

- **`SETUP`** — fresh `NavGridView` per op over trial-prebuilt flat chunks (same `freshChunks`
  discipline as SHORT), start `(8,0,8)` → goal `(9,0,9)`, `bound == null`. Measures the non-macro
  setup: view + scratch resets + context/relaxer + terminal pop.
- **`SETUP_MACRO`** — identical but the 7-arg overload with `confineBound == null`,
  `cuboidBound == SETUP_CORRIDOR` (`RegionBound(-8,24, 0,33, -8,24)` — a 32×32×34 window-scale cap
  around the start, inside built chunks): the live window replan's exact parameter shape. Adds the
  `NavGridCuboidsView` ctor + the real `GoalForcedCost.probe` with flat-world goal-face extractions.

**Why the goal is OFFSET (9,0,9), not equal to the start:** `start == goal` makes every delta 0, which
degenerates `primaryAxis` and the probe's far-face exclusion (`domDelta == 0` ⇒ exclude nothing,
`GoalForcedCost.java:231`) — a code path production never runs. The 1,0,1 offset keeps both on the
production argmax/tie-break path (dominant axis X) while still landing inside the tolerance.

**Guard rails** (`setupSanityDryRun`, setup-time, unmeasured): asserts found, `lastExpansions() == 0`,
`plan.size() == 0`. If a future `isGoal`-tolerance or geometry change breaks the zero-expansion
premise, the trial aborts loudly instead of silently changing what the number means.

**Valid derivations once numbers exist:**
- `SETUP` alone = the non-macro per-search fixed cost (the number E5b needed).
- `SETUP_MACRO − SETUP` ≈ macro-context construction + goal probe (the profile's 38–45% claim gets a
  direct steady-state number for the first time).
- `SHORT − SETUP` ≈ the pure micro-walk cost of a ~30–60-pop search (per-node marginal cost of the
  trivial case, cleanly).
- Regression triage: a change that moves SHORT but not SETUP is per-node; SETUP but not SETUP_MACRO−SETUP
  is view/scratch; SETUP_MACRO−SETUP is cuboid/probe.

## §5 Caveats + protocol

- **Not run yet** (26-era doesn't compile `src/test`); first execution on the mc-1.21 era branch per the
  standard protocol. Expect SETUP in the low single-digit µs and SETUP_MACRO a few µs above it (the
  probe's extractions are over wide flat-air cuboids — cheap per box, few boxes); if SETUP_MACRO−SETUP
  comes out at tens of µs, the profile's 38–45% attribution was under-, not over-stated.
- The op still contains ONE pop + `reconstruct` of an empty plan — an intentional floor (~tens of ns),
  not zero. Documented rather than fought; anything that shaves it would distort the production shape.
- Suite growth: the `@Param` list gains two entries, so full-suite A/B runs get ~2 × (3×2 + 6×2) s
  longer per fork. Pin with `-Pscenario=SETUP` for targeted runs.
- SHORT **stays** — it remains the "setup + trivial walk" composite guard (the shape production
  actually pays on a short hop). SETUP does not replace it; it decomposes it.
- **One production setup component is still absent from both arms**: the live driver also threads a
  non-null region cost field (`PathPlan.java:875-876` — `findPath(grid, botFloor, target, caps, null,
  cuboidCap, inventory, startMode, baseline, regionFieldFor(target))`); the scenarios pass the
  byte-identical-null path. The field's BUILD cost is upstream of `findPath` (not per-search-setup
  inside it) and is the subject of the separate region-field audit (`PERF-AUDIT-region-field.md`);
  if a future headless `RegionCostField` fixture becomes available, a third arm (`SETUP_FIELD`)
  would complete the decomposition.
- Per the measurement-trap note in CLAUDE.md: SETUP is the smallest op in the suite and the most
  vulnerable to forks=0 JIT layout perturbation — confirm any suspicious SETUP delta with pinned
  fresh-JVM interleaved pairs before believing it.
