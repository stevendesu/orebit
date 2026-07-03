# Orebit

A server-side Minecraft mod that spawns AI-driven "ally bots" — virtual players that follow/assist the owner, eventually driven by a weak local LLM for intent recognition. Maven group `com.orebit.mod`, base name `orebit`. **Multi-version, multi-loader:** MC **1.17.1 → 26.2**; **Fabric** everywhere, **Forge** 1.17.1→1.21.11, **NeoForge** 1.21→1.21.11. Java is per-version (17 / 21 / 25). The ally-bot prototype is **runtime-verified in-game on Fabric** across the range (incl. multiplayer LAN, 26.2).

## IMPORTANT: Read this first — design-doc vs. real code

This repo is a **documentation-first codebase**. Of ~236 Java files under `src/main`, roughly 105 contain runnable logic; the rest (~55%) are **Javadoc-only spec stubs**: a multi-section design comment followed by a bare `package` declaration or an empty class body, with **no fields, no logic**. Do not mistake these for partial implementations — entire subsystems (ai, tasks, sim, memory, relationships, integration, eventbus, clock, behavior, requirements, settings, debug, manager, agent, data, mocks, scripts — ~98 files) have **zero executable code**.

There are effectively **two architectures**: (1) the elaborate agent-brain design encoded in the stubs, and (2) the **working navigation stack** — root `com.orebit.mod` package + `commands/` + `config/` + `worldmodel/` + `pathfinding/blockpathfinder|regionpathfinder` + the `platform/` adapters + the per-loader glue. Nothing running imports from ai/tasks/integration/etc. When asked to change behavior, first determine whether the target is real code or a stub (the Repo map below marks each package).

## Build system (READ THIS before touching the build)

Orebit ships shippable JARs for **every MC version × every loader that has a release**, as single drag-and-drop JARs with **zero runtime deps** (no Architectury *API* — see below). The mechanism is **branch-per-toolchain-era** (NOT per individual version). An *era* is a span of MC versions sharing one Loom/Gradle/Java toolchain; within an era, **Stonecutter** + the `overlays/` chain cover the individual versions. Full strategy: `internal_docs/BUILD-STRATEGY.md` (Model C).

### The two eras (each is a git branch)

| Era branch | MC range | Loaders | Build tool | Gradle | JDK |
|---|---|---|---|---|---|
| **`mc-1.21`** | 1.17.1 → 1.21.11 | Fabric + Forge (+ NeoForge ≥1.21) | **Architectury Loom 1.13.469** | 8.12.1 | 17 (`<1.20.5`) / 21 (`≥1.20.5`) |
| **`main`** (newest era, GitHub default) | 26.0 → 26.2 | **Fabric only** | **pure Fabric Loom 1.17.12** | 9.5.0 | 25 |

**Why 26.x is a separate, Fabric-only era:** MC 26.1 ships **unobfuscated** (Mojang publishes no mappings; Yarn dropped). **Architectury Loom can't build 26.1+ yet** (it still hard-requires a non-empty `mappings` config — [architectury-loom#328](https://github.com/architectury/architectury-loom/issues/328), open). Orebit's source is **100% Architectury-API-free** (the loader seam is hand-written DI: `OrebitFabric` → `FabricPlatformEvents` → `OrebitCommon.init`), so the Fabric target builds on **plain `net.fabricmc.fabric-loom`** with no Architectury at all. 26.x also needs Java 25 + Gradle 9 + a newer Loom — mutually incompatible with legacy Forge's `≤1.13`/Gradle-8 needs in one build — so it MUST be its own era. Forge/NeoForge for 26.x wait until #328 lands.

### The `core` trunk (where you author most changes)

- **`core`** holds **everything version-portable**: common logic (`src/`), the full `overlays/` + `overlays-forge/` chain, per-version `versions/<ver>/gradle.properties`, and the **mc-1.21-era (Architectury) build-script logic** (`build.gradle.kts`, `settings.gradle.kts`, `stonecutter.gradle.kts`, loader modules).
- **`core` holds NO toolchain values and is NOT directly buildable** — a missing `era.properties` makes Gradle fail fast with a clear message. It exists to be the merge source.
- **Authoring rules** (these keep cross-era propagation clean — violating them breaks the model):
  - Common logic, overlays, `versions/*/gradle.properties` → **`core`**, then `git merge core` into each era branch (conflict-free).
  - **Toolchain values** (Loom version, Gradle wrapper, Java, the version matrix) → **the era branch only**, in `era.properties` + `gradle/wrapper/gradle-wrapper.properties`. NEVER on `core`.
  - **Build-script refinement (26.x):** because the 26 era uses a *different build tool* (Fabric Loom vs Architectury), its **build scripts** (`settings`/`stonecutter`/root `build.gradle.kts`) and a few loader-glue files (`FabricPlatformEvents`, `fabric.mod.json` deps) are **era-owned on `main`**, not on `core`. `core` keeps the Architectury scripts. `git merge core → main` stays clean because `core` never edits those files. (`mc-1.21`'s scripts are the `core` ones.)
- Build/dev/`runClient` happens **on an era branch** (or a git worktree), never on `core`. To author common/overlay changes without disturbing an era's working tree, use a **worktree**: `git worktree add ../orebit-core-wt core`.

### `era.properties` (era-owned, the one file an era branch adds)

Read by `settings.gradle.kts`: `loom.version` (injected into the Loom plugin via `pluginManagement` resolutionStrategy), `mc.versions.{common,fabric,neoforge,forge}` (the Stonecutter matrix), `java.version` (reference; the per-version source/target LEVEL is derived in the build scripts), `era.name`. The Gradle wrapper version is era-owned by nature.

## Build / Run commands

**Always set JDK per era** (`JAVA_HOME`): the 1.21 era uses 17/21, the 26 era uses 25. All three (17/21/25) are installed (Temurin).

### mc-1.21 era (on the `mc-1.21` branch — Architectury, multi-loader)
- `./gradlew chiseledBuild{Fabric,Neoforge,Forge}` — build all versions for a loader → `build/libs/<modver>/<loader>/`. (`chiseledBuild` = all loaders; heavier, needs `-Xmx4G`.)
- `./gradlew chiseledCompileCommon --continue` — cheap probe: compile the common source against every version (pins which MC version introduced an API divergence). The standing pre-release CI gate.
- `./gradlew chiseledCompile{Fabric,Forge,Neoforge} --continue` — per-loader compile probe.
- Dev a version: `./gradlew "Set active project to <ver>"` then `:<loader>:<ver>:runClient`; `"Reset active project"` returns active to the era default.
- **GOTCHA:** a direct `:<loader>:<ver>:buildAndCollect` can succeed with a **hollow NO-SOURCE** jar (Stonecutter only fills `chiseledSrc` via `chiseledBuild<Loader>`). Trust the chiseled tasks; verify with `unzip -l <jar> | grep com/orebit/mod`.
- **JMH benchmarks / profiling** (headless, bootstrapped-MC Knot classloader, `forks=0`; this era only): `"Set active project to 1.21.4"` then `:1.21.4:jmh -Pbench=PathfinderBenchmark` (or `BlockReadBenchmark`, or `PatchStormBenchmark` — per-patch nav-grid maintenance cost: SCATTER/DIG/TOGGLE/SEAM, ~1.4–2.1 µs/patch baseline). `PathfinderBenchmark` scenarios (`@Param`): `TOWER OPEN UPOVER_OPEN UPOVER_WALL SHORT MULTI FLOOD CLIFFS BRIDGE SPIRAL` — `SHORT`/`MULTI` are the setup/persistence guards, `FLOOD` the warm edit-heavy S3 shape, `CLIFFS` fall/walk-only. A fresh-JVM cold-start harness (`ColdStartHarnessTest` + a bench-worktree `:1.21.4:coldstart` task) exists for first-search latency; its build-script hooks are NOT on the branch — apply `internal_docs/bench-harness-mc121-buildscript.patch`. Profilers `-Pprof=gc,stack,jfr,cpu` — GC alloc-rate, sampled method hot-spots, an in-process `jdk.ObjectAllocationSample` recording dumped to `build/alloc.jfr` (per-type/per-site allocation attribution), and an in-process `jdk.ExecutionSample` CPU recording dumped to `build/cpu.jfr` (on-CPU self-time by method/stack — the on-CPU counterpart to `jfr`; needed because the JMH `StackProfiler` samples every thread and drowns the bench thread in idle Gradle frames under `forks=0`). Both `.jfr` read with `jfr print --events <jdk.ObjectAllocationSample|jdk.ExecutionSample>`; filter the CPU dump to in-search samples (stack contains `findPath`). `-Pscenario=TOWER` pins a `@Param`. `PathfinderBenchmark` drives `BlockPathfinder.findPath` over a synthetic in-memory `NavGridView` (no live level — see the package-private `NavGridView(minY, chunks)` seam). Background + rationale in `docs/Optimizations/pathfinding_hot_path.md`.
- **In-game A\* trace + offline analysis** (a runtime diagnostic for *why* a search explores what it does — the JMH profilers above measure SPEED; this captures the search SHAPE). The command **`/bot trace`** (`commands/TraceCommand` → `AllyBotEntity.traceTo`) stops the bot and runs ONE *raw* block-A\* (no corridor) from the bot to the caller with `BlockPathfinder.TRACE` on, dumping **every expansion + candidate** to `<run dir>/orebit-trace.txt`. Run it where a failed `/bot come` left the bot (note `BlockPathfinder.PARTIAL_PATH` now defaults TRUE, so a budget-hit search returns a best partial and the bot walks it — set `PARTIAL_PATH=false` first if you need the bot to sit at the start on failure). Line format (greppable): `E <seq> <x> <y> <z> g=<g> f=<f> via=<move|start>` per pop (in expansion order), indented `C <move> <x> <y> <z> cost=<c> <OK|worse|corridor>` per emitted candidate. Analyze with **`internal_docs/trace_analysis.py`**: `python internal_docs/trace_analysis.py <trace.txt> [out.png]` (needs `matplotlib`+`numpy` — `pip install` them). It prints the on-column/off-column split, per-move + per-Y-level expansion counts, the `f`-frontier progression, and the explored bounding box, and writes a **4-panel PNG** (top-down X-Z + side X-Y scatters coloured by min `f`, a per-Y histogram, and `f`-vs-expansion). This is how the open-air-pillar flood was diagnosed (the search builds a *cone* of partial pillars — ~99% of expanded nodes off the goal column — because the octile heuristic is blind to the per-block place cost of building straight up). `BlockPathfinder.TRACE` is OFF by default (trace is huge + slow); `PARTIAL_PATH` now defaults ON — budget-exhausted searches return the best partial, truncated by the `IRREVERSIBLE_GUARD` (also default ON) to the last cell before the first irreversible move.

### mc-26 era (on `main` — Fabric only, single module)
- `./gradlew chiseledBuild` — build every 26.x Fabric jar → `build/libs/<modver>/orebit-<modver>+<ver>.jar`.
- `./gradlew chiseledCompile --continue` — compile probe across every 26.x version.
- `./gradlew "Set active project to <ver>"` then `:<ver>:runClient` (JDK 25) / `:<ver>:build` / `:<ver>:buildAndCollect`.
- The JMH/headless-MC **test harness is deferred** for this era (empty test source set; unverified on unobf 26.x). Profiling runs on the mc-1.21 era.

### Shared gotchas
- **Build/compile only from the ACTIVE node** (`:<ver>:compileJava`, marker set by `Set active project`). `compileJava` across ALL nodes fails — Stonecutter fills live `src/` only for the active version; non-active nodes lack the common source.
- After any `src/`/overlay edit, `Set active project to <ver>` before `runClient` or a single-jar build (loader/version nodes build from `chiseledSrc`).

## Overlay strategy (MC-version-divergent code)

MC-version-divergent files do **NOT** live in `src/main/java`. They live in a **TOP-LEVEL `overlays/<era>/java/<package>/…`** (and `overlays-forge/<era>/…` for Forge event-API drift) — outside any `src/`, because Stonecutter copies whole `src/` trees into per-version `chiseledSrc`. `<era>` is the MC version that FIRST needs that flavor.

- **Eras COMPOSE:** every era whose version `≤` the active MC version is stacked ascending; a higher era's file OVERRIDES a lower era's file of the same path. Each era holds ONLY the files that changed at that boundary (a `Sync` merges them, last-wins — see `buildSrc/.../version-overlays.kt`, `applyVersionOverlays`). Both the common module and each loader module compose their own overlay dir.
- **A version-divergent class lives ONLY in overlays** (removed from the `src/` baseline → no duplicate-class). The baseline era (currently `overlays/1.17`) holds the oldest flavor.
- **Keep CORE LOGIC in core; put only the thin divergent primitive behind an adapter.** The `platform/` package is exactly this seam: tiny version-selected static helpers the common core calls — `BlockLookup`, `VersionedBlocks`, `ChunkCoords`, `ConcretePowder`, `EntityState`, `LevelBounds`, `MineableTags` (static mineable tags), `TagLookup` (DYNAMIC tag membership, e.g. `#minecraft:beds` — 4 flavors: 1.17 / **1.18.2** (`TagKey.create(Registry.BLOCK_REGISTRY,…)`) / 1.19.3 / 1.21.11), `BlockKinds`, `BlockShapes`, `BotSpawn`, `Worlds`, `Replaceable`, `Sections`, `ConfigDir`, `ItemDamage`, `CommandFeedback`, `ClientLoad`, `MoveReport` (version-STABLE platform classes — `BotInventory`, `WorldEdits` — live in `src/.../platform/` instead). **Static one-liners → JIT-inlined, no dispatch on hot paths.** Do NOT push business logic (e.g. `ChunkNavLoader`) into overlays; extract the MC-API call (e.g. `ChunkCoords.x(pos)` instead of `pos.x`) and keep the loop in core. Performance is critical (see PRD / Baritone-as-anti-example).
- **`overlays/26`** holds 26.x drift: `ChunkCoords` (`ChunkPos.x/.z` fields → `x()/z()` methods), `ConcretePowder` (16 dyed blocks → one `Blocks.CONCRETE_POWDER` `ColorCollection`). Era dirs are discovered dynamically by buildSrc (no build change to add one — `overlays/1.18.2` was added for `TagLookup` with zero Gradle edits).
- **Re-baseline procedure** (to make a currently-stable `src/` file divergent): RENAME it down into the baseline overlay dir, then add an override dir at the introducing version. See the `overlay-rebaseline-procedure` memory.

## Version-pin reasoning (non-obvious; do not "upgrade" blindly)

- **Architectury Loom `1.13.469`** is the ONE version that builds the whole 1.20.1→1.21.11 matrix on Gradle 8.12.1 across Fabric + NeoForge + legacy Forge: ≥1.13.3 to consume fabric-api for 1.21.11; new enough for NeoForge 1.21.10+'s dropped `data/server.lzma` format; old enough to keep legacy Forge working (Loom 1.14 broke Forge remap and needs Gradle 9).
- **Forge build pin = `latest`, NOT recommended.** Loom 1.13.469's remapper *corrupts* some older recommended MDKs (scrambled signatures, won't compile); `latest` remaps clean and **the resulting jar still runs on the user's recommended Forge** (build-against-latest → runs-on-recommended). The "Forge Beta" menu banner on no-recommended versions is cosmetic and accepted.
- **NeoForge = beta-where-no-stable** (uses the latest stable, or the latest beta when NeoForge never promoted one). Floor 1.21.
- **Per-version JDK toolchain** (NOT per-era): `<1.20.5 → JDK 17`, `≥1.20.5 → JDK 21`, `≥26 → JDK 25`. Loom honors it for compile but launches runs on the daemon JDK, so old-Forge runs pin the run JVM via `javaLauncher` (its modlauncher ASM can't read Java 21 → "major version 65").
- **26.x:** Fabric Loom `1.17.12` (needs Gradle `9.5.0` — 9.4.0 fails the `org.gradle.plugin.api-version` match), JDK 25. Unobf build idioms: **no `mappings()`**, plain `implementation`/`jar` (not `modImplementation`/`remapJar`). `fabric.mod.json` depends on **`fabric-api`** (the umbrella mod-id at 26.x; it was `fabric` on ≤1.21 — wrong id → "requires any version of fabric" at launch).

## Repo map (package-by-package, with completeness)

All under `src/main/java/com/orebit/mod/` unless noted.

WORKING CODE:
- **(root package)** — REAL prototype. `Orebit`/`OrebitCommon` (entrypoint logic), `BotManager` (static UUID→AllyBotEntity map), `AllyBotEntity` (extends `FakePlayerEntity`; tick() does follow+look-at), `FakePlayerEntity` (extends `ServerPlayerEntity`; **version-divergent → in `overlays/`**), `FakeNetworkHandler` + `FakeClientConnection` (suppress I/O; `FakeClientConnection` in `overlays/`), `BotPositioning`, `BotMining` (per-tick timed breaking: vanilla `getDestroyProgress` accumulation, tool select, crack overlay, real survival break — drops/XP/wear; refuses via `Config.mayBreak`; grinds an opted-in unbreakable at `MiningModel.UNBREAKABLE_STANDIN_TICKS = 2400` then force-breaks), `Debug`. `AllyBotEntity` also carries a grounded-stall recovery arm (rides the stuck counter, gated on `!mining.busy()` so timed breaks aren't misread as stalls) and portal-seek/ENTER terminal states (cross-dimension FOLLOW — see `worldmodel/pathing/NetherPortalIndex`). The per-loader glue lives in `fabric/`, `forge/`, `neoforge/` modules (thin: a `PlatformEvents` impl + entrypoint).
- **platform/** — REAL thin version adapters (in `overlays/`, see Overlay strategy). The common↔loader seam (`PlatformEvents`) + MC-API shims.
- **worldmodel/navblock/** — REAL. `NavBlock` (interns the ~28k block STATES into ~587 behavioral fingerprints — a `short` navtype index → packed 64-bit `long` descriptor, ≤1024 capacity — at static-init). The descriptor now also packs a damaging bit (incl. powder snow), a 2-bit transit-slow class (moving THROUGH a cell — berry bush/powder snow light, cobweb heavy — distinct from the slow-FLOOR surface field), a NETHER_PORTAL bit (43), a **PROTECTED bit (44)** (owner's `mining.protectedBlocks`, applied post-init via `NavBlock.applyProtected` — splits matching states into new navtypes; grids built before a list change stay stale until rebuild), and derived BREAKABLE (38) / OPEN_PLACE (39) / COLLISION (40) bits — BREAKABLE and OPEN_PLACE both exclude protected (placing REPLACES the occupant). Vanilla-unbreakable (hardness<0) is detected at classification, no hardcoded list; `ProtectedBlocks` (config/) is the executor-side predicate.
- **worldmodel/pathing/** — MIXED. REAL: `TraversalGrid` (packed `short[4096]` = 6 flag bits + 10 navtype bits, **plus a parallel `byte[4096]` depth array** — low nibble floorGap = distance-to-first-standable-below (0–13 exact / 14 proven-none / 15 UNKNOWN→legacy-scan; consumed by `Fall`), high nibble runUp = upward same-navtype run (consumed by `CuboidExtractor` run-chains; measured −30…−36% on cuboid-bearing scenarios)), `NavSection`/`NavSectionPool`, `NavStore`, `NavGridView` (the pathfinder's read seam — incl. a per-search open-addressed chunk cache that kills `Long`-key boxing; and a package-private synthetic ctor for headless benchmarks), `NavSectionBuilder.classifyInto`/`patchCell`/`computeDepth` (the live read+classify path + the pass-3 depth-nibble column sweep; `patchCell` repairs both nibbles by fixpoint, ≤15 cells, ≤1 vertical seam; flag builds overscan 3 rows into the section above so seam-row hazard/headroom flags are exact), `ChunkNavBuilder`/`ChunkNavLoader`/`NavFlags`, `NavGridUpdater`, `NetherPortalIndex` (per-dimension index of known portal columns — feeds `AllyBotEntity`'s portal-seek/ENTER for cross-dimension FOLLOW), and `NavWarmup` (boot-time synthetic JIT warm-up at SERVER_STARTED, synchronous, plateau-detected — first search 21.8→0.67 ms; gated by `pathing.warmup`/`warmupBudgetMs`). BROKEN: `NavSectionBuilder.build()` is an inert benchmark. STUBS: `ReflectionHelper`, `WorldModel`. (`TraversalClass`/`TraversalAnalyzer*` were superseded by the packed grid + `NavFlags`.)
- **pathfinding/blockpathfinder/** — REAL. The block-tier A* (PRD §7.1), **allocation-free on its hot path** (`docs/Optimizations/pathfinding_hot_path.md`): `BlockPathfinder` (struct-of-arrays primitive search state, custom open-addressed `long`→row map, binary-heap open set, per-search `EditPool` arena), the `Movement` set (**14**: `Traverse`/`Diagonal`/`Ascend`/`Descend`/`Fall`/`Pillar`/`MineDown`, the swim family `Swim`/`SprintSwim`/`StartSprintSwim`/`Surface`, plus `Climb`, `Parkour` (flat 1–3 / rising 1–2 / falling 1–4 + offset-jump tier; deeper rows behind `Parkour.AGGRESSIVE`), and `DiagonalParkour` (gaps 1–2) — all behind `MovementRegistry.TIER1`), the **cuboid macro subsystem** (`cuboid/`: `Cuboid`, `CuboidExtractor`, `NavGridCuboidsView`, `GoalForcedCost` — which excludes the far face relative to the start→goal approach, build face below exempt — `MacroJump`, `Axes`), the **phase-execution framework** (`Movement`/`MovePlan`/`PhaseRunner` — `Pillar` and `Parkour` execute via `plan()`; the others still use the legacy `steer` hook), the steering seam (`BotSteering`/`SteerControl`/`SteerView`), `MovementContext` (predicate vocabulary over `NavGridView`), `MiningModel`/`RegionBound`/`CandidateSink`, `EditScratch` + pooled `StepEdits` (break/place folded onto moves), `PathEdits` (per-path edit diff), `BotCaps`, `BlockPathPlan`. Movements price pass-through hazards (caps-gated on `BotCaps.takesDamage`, all damage in the one `costPerHitpoint` currency) and slow floors / through-slow cells — and the ground moves (Traverse/Ascend/Descend) arbitrate **transit-vs-punch-through per body cell** (`MovementContext.transitOrBreak`: `min(cellTransitCost, miningTicks + mining.breakBaseCost)`, folded via `EditScratch.breakThrough`; Diagonal/Fall/the airborne family deliberately keep intact-transit pricing). Symmetric tick-scaled octile heuristic × greedy weight. Driven today by `AllyBotEntity.replan` (→ `applyEdits` replays the path's break/place edits). STUBS: the `operations/` and `heuristics/` subpackages plus `BlockHeuristic`/`BlockPathOperation` (Javadoc-only).
- **pathfinding/regionpathfinder/ + pathfinding/PathPlan + worldmodel/hpa/** — REAL region tier. `RegionPathfinder` (fragment region A*, level-parameterized), `RegionPathPlan`, `HierarchicalRegionPlan` (the cascade: a stack of per-level skeletons, re-plan only the level whose window the bot exited), `RegionEdgeBlacklist` (online repair of unrealizable hops); `PathPlan` is the two-tier driver (region skeleton + sliding window target + boundary-gated commit/replan). `worldmodel/hpa/` is the fragment data layer: `RegionGrid`, `RegionAddress`, `RegionFragments`, `FragmentBuilder`/`FragmentLeafComputer`, `LeafCostComputer`, `CostPyramid`/`CostCodec`, `PyramidMerger` (coarse roll-up), `HpaMaintenance`. The single **fragment+cascade model is unconditional** — the old `HPA_FRAGMENTS`/`HIERARCHICAL_CASCADE` A/B flags and the center model were deleted (s36). Also REAL: `pathfinding/PathDebugRenderer`, `PathStatus`. STUBS: the rest of `pathfinding/`'s support cast (`PathBudgetManager`, `PathCache`, `PathFollower`, `PathReplanner`, `PathSmoother`) + the `regionpathfinder/heuristics/` specs.
- **commands/** — MIXED. REAL: the Brigadier `/bot` command surface — `OrebitCommands` builds the `/bot` root at the `onRegisterCommands` seam hook, and each subcommand is a small Strategy class implementing the `BotCommand` interface (`SpawnCommand` — respawn a dead/missing bot via the rejoin-equivalent remove+spawn; refuses while alive — `FollowCommand`, `StayCommand`, `ComeCommand`, `GotoCommand`, `MineCommand`, `HereCommand`, `TraceCommand`, `ConfigCommand`, `DebugCommand`, `ProbeCommand` — `/bot probe <x y z>` dumps what the planner sees at one cell: navtype, decoded flags, per-cell transit surcharge + `breakThrough` price, caps in force; the stale-grid-vs-stale-flags-vs-caps discriminator). STUBS: the old chat-parsing design (`ChatCommandParser`, `CommandDispatcher`, `CommandHandler`, `CommandRegistry`, `ParsedCommand`).
- **config/** — MIXED. REAL: `Config` (immutable record of survival/placement/mining/pathing settings; `Config.DEFAULT`; `toBotCaps()` folds the knobs into the pathfinder's capability gate; `mayBreak()` is the executor-side break-policy backstop), `ConfigLoader`, `ConfigValidator`, `ConfigKeys` (now incl. `mining.breakBaseCost`, `mining.protectedBlocks`, `mining.allowUnbreakable`, `pathing.costPerHitpoint`, `pathing.warmup`, `pathing.warmupBudgetMs`), `ProtectedBlocks` (parsed id+`#tag` predicate; feeds `NavBlock.applyProtected` planner-side + `mayBreak` executor-side). Key reference: `internal_docs/CONFIG.md` (internal) / `docs/configuration.md` (public). STUBS: `GlobalSettingLimits`, `HotReloadManager`, `PolicyOverrideSource`.
- **worldmodel/region/** — REAL data layer (`Region`/`LeafRegion`/`CompositeRegion`, `Portal`, `RegionPool`, `RegionMetadata`, `RegionBlockIndex`, `RegionBoundingBox`) — but **superseded by the ratified fixed-grid region design** (PRD §6.3: regions are a fixed cubic-grid/implicit octree, NOT semantic; the `Region`/`Portal` classes and the flood-fill `RegionBuilder` are the old semantic model). STUB: `RegionBuilder`.

STUB-ONLY (Javadoc + package decl, ZERO code): **ai/** (9), **tasks/** (7), **sim/** (5), **memory/** (9), **relationships/** (5), **integration/** (10, the LLM pipeline), **behavior/**, **requirements/**, **settings/**, **eventbus/** + **clock/**, **debug/** (7), **manager/**, **agent/**, **data/** + **scripts/** (3) — plus the per-package stubs called out in the MIXED entries above (the old `mocks/` package is gone). **Note duplicate identities**: `manager.BotManager` (stub) vs root `BotManager` (real); `PathfindingSettings` in BOTH pathfinding/ and settings/.

MIXINS: minimal — the foundation does NOT lean on coremod hacks.

## Intended end-to-end flow (designed, NOT yet runnable)

player chat → `PromptBuilder` → `LLMInterface` → `LLMBackend` (local Ollama / OpenAI) → `InterpretedIntent` → `GoalDispatcher` → Requirements graph → AI `TaskExecutor`/`AIStateMachine` → two-tier pathfinding (`RegionPathfinder` over the Region tree → `BlockPathfinder` over atomic moves) → `VirtualPlayerController`. LLMs are spec'd for **intent only, never planning** (determinism pillar). **None of the LLM/tasks layer runs today.** What actually runs: `OrebitCommon.init` registers join/disconnect/tick/chunk events via `PlatformEvents` (+ config load, `MiningModel` bake, `NavWarmup` at SERVER_STARTED) → `BotManager` spawns an `AllyBotEntity` that follows/goes-to via the REAL two-tier pathfinding (region cascade → block A\*) and executes plans reactively (steer hooks + `MovePlan` phases + `BotMining`).

## Key conventions

- **Spec-as-Javadoc**: write a thorough design comment as the file body before implementing; honor/maintain these specs when filling a stub.
- **Memory-conscious data structures**: bit-packing, sorted `short[]` + binary search over HashMaps, object pools for zero-GC hot paths.
- **Optimization-by-measurement**: see `docs/Optimizations/block_reading.md` (block reads optimized ~3,000,000 ns → ~6.7 ns/block).
- **Loader/MC coupling is deliberately minimal**: the common source is pure-Java/vanilla; loader specifics are the `PlatformEvents` impls; version specifics are `overlays/` + the `platform/` adapters. **Bot = `ServerPlayerEntity` subclass** (not a custom EntityType) so it passes vanilla `instanceof` player checks — at the cost of welding to the player network/protocol stack (the most version-fragile surface).

## Performance model (READ before proposing any optimization)

The pathfinder sits **near the CPU↔memory balance point — neither resource is "free", and "obvious wins"
routinely regress.** Understand why before touching anything hot:

- **The data layer is already hyper-optimized for memory**: a NavGrid cell is a `short` (a section fits in
  a handful of cache lines), neighbor facts are precomputed into the high bits, and the low bits index a
  ~4–5 KB descriptor array that lives in L1. Meanwhile the search does an enormous VOLUME of ALU work per
  node (bit shifts/masks, murmur probes, float compares, XORs). Consequence: **adding even a little math
  to a per-access path can flip the system from memory-bound to CPU-bound.** Case study: an early
  Hilbert-curve cell indexing ("nearby blocks nearby in memory") was **2–3× SLOWER** than linear indexing
  — the per-access index math cost more than the cache misses it saved.
- **Allocation is the historical enemy** (pools, arenas, ThreadLocal scratch everywhere), so searches pay a
  deliberate **up-front construction cost** (per-search `NavGridView`, open-addressed maps, cuboid
  extraction, goal-premium probe). That cost amortizes over a 10k-node flood (~400–700 ns/node) but
  dominates the COMMON case — a 30-node hop can pay 6k–40k ns/node. **Per-search setup is therefore itself
  a hot path**: a "cheap" per-view allocation or init loop is a real regression. The JMH `SHORT` scenario
  exists to catch exactly this; `MULTI` (alternating short/long, fresh view each) guards cross-search
  reuse/persistence. Case study: a 16-slot direct-mapped chunk cache fixing a real, profiler-proven
  A,B,A,B miss pattern in cuboid construction looked like a clear win — paired A/B benching showed its
  headline wins were noise and its per-view init regressed `SHORT` by ~4%. **Reverted.** (The extraction
  bill itself was later cut ~75–80% by the runUp depth nibble — `docs/Optimizations/depth_nibbles.md`,
  `PERF-RESULTS-2026-07-03.md`; the same run REFUTED the per-pop edit-bbox gate at p=0.000 — pillar pops
  stand ON their own edits, so never gate on a whole-chain edit bbox.)
- **The search is pathologically hard** (huge mutable 3D world, 14 movement kinds, map edits mid-search),
  so it leans on behavior-shaping optimizations — forced-cost premium, cuboid macro moves, greedy weight,
  irreversibility guard — that (a) make behavior non-obvious to reason about and (b) carry their own
  one-time analysis costs. Mechanical "equivalent" rewrites must preserve **byte-identical search results**
  (same expansion order, same returned paths); anything that changes f-values or candidate order is a
  BEHAVIOR change and gets treated as one.
- **Branch prediction bites here too.** The per-node read paths are already branch-dense (bbox rejects,
  mode gates, UNBUILT checks), and at millions of reads per search a new DATA-DEPENDENT branch on a
  per-read path costs real time even when it "usually" predicts — and wrecks you when it alternates.
  Prefer branch-free bit math, hoisted/loop-invariant checks, and prefilter bits that make the common case
  a single predictable test (the NavFlags/transit-bit pattern). Case study: the reverted neighbor-prefetch
  stencil added one stencil-bounds branch to EVERY coordinate read (hit or miss alike) — part of why it
  regressed +7…+26% across ALL scenarios despite "saving" redundant section resolves.
- **Process (non-negotiable):** every perf change is (1) **design-reviewed by the project owner BEFORE
  implementation** (mechanism + invariants + expected win + risk), (2) measured with **paired interleaved
  A/B runs** on the full JMH scenario suite (incl. the `SHORT`/`MULTI` guards and, for anything touching grid maintenance, `PatchStormBenchmark`), kept only on a ≥3% targeted win with
  no scenario regressing beyond noise and tests green, (3) reverted without sentiment when the numbers
  don't reproduce. Profiler hypotheses are hypotheses: the sqrt-tie-break "hot spot" turned out to be 1.7%
  of leaf time. Benchmark EVERYTHING; trust nothing that wasn't measured under this protocol. Known
  measurement trap: the bench runs `forks=0` (one shared JVM), so a change can perturb JIT profiles/code
  layout ACROSS scenarios — a suspicious single-scenario delta must be confirmed with pinned
  `-Pscenario=<X>` fresh-JVM interleaved pairs before believing it (a phantom +5% OPEN "regression" was
  cleared exactly this way).

## Gotchas / known issues (in REAL code)

- **(RESOLVED s38)** The bot now runs the **full vanilla player tick**: `FakePlayerEntity.tick()` is a `super.tick()` pass-through, and `AllyBotEntity.tick()` = forge inputs → `super.tick()` (ServerPlayer housekeeping) → `doTick()` (Player physics + pose + survival) → `MoveReport.after()`. No more skipped-`ServerPlayer.tick` whack-a-mole or double-aiStep. Survival flags (`survival.takesDamage`/`hunger`/`needsBreath`) enforced at runtime; `platform/ClientLoad` (1.21.11+ permanent-invuln fix) + `platform/MoveReport` (26+ fall/movement-damage) are overlay seams from that pass (committed s38).
- `NavSectionBuilder` holds a **public static, non-thread-safe `BlockState[]` scratch** buffer; it also reflects into `PalettedContainer` internals — the most version-fragile code in the project.
- ~~`ChunkNavLoader` discards its NavSection[]~~ — RESOLVED long since: it stores into `NavStore` (per-level chunk-key map) and notifies `HpaMaintenance`; unload drops + recycles.
- README's "Smart Objects Over Managers" pillar is **contradicted** by the live static `BotManager`.

## Where to look

- **Canonical design: `internal_docs/PRD.md`** — the ratified world-model + pathfinding design, decisions log, phased build plan. Internal docs live in `internal_docs/`, NOT `docs/` (which is the PUBLIC MkDocs site, `docs_dir: .`, auto-deployed to GitHub Pages on push to `docs/**`). Other internal: `internal_docs/PORTABILITY-AUDIT.md`, `internal_docs/BUILD-STRATEGY.md` (the multi-version/multi-loader strategy + ecosystem findings). `HANDOFF.md` (root) = the temporary next-session pointer.
- Vision/architecture: `src/main/java/com/orebit/mod/README.md` (module table + end-to-end flow).
- Optimization rationale: `docs/Optimizations/block_reading.md`.
- Design specs / public feature docs: `docs/pathfinding.md`, `docs/worldmodel.md`, `docs/movements.md` (per-movement costs + derivations), `docs/world_edits.md` (mining/placing/protected/unbreakable), `docs/configuration.md` (public key reference; internal = `internal_docs/CONFIG.md`). The code stubs still carry OLD drifted vocabulary until Phase 4 reconciles them. Perf measurement record: `internal_docs/PERF-RESULTS-2026-07-03.md` (+ the `PERF-DESIGN-*.md` docs, each carrying an adopted/refuted STATUS header).
- **Root design dumps:** `design-principles.txt` (author's coding philosophy — "no Utils/Helper classes", "smart objects over data objects", "abstract classes over enums", "statically-sized structures", "prefer determinism", "avoid `final`"; live code partially contradicts these). `dump.txt` is an auto-generated fossil of an OLDER `worldmodel/` design (`BlockMap`, `BlockTagMap`, `SubChunk`, `RegionTree`…) that predates today's code.
- Public site: MkDocs Material → GitHub Pages (`stevendesu.github.io/orebit`).
- `fabric.mod.json` metadata is still partly the Fabric example template (description/authors/contact unedited).

## Portability (Fabric vs Forge, multi-version)

Both are **shared-core + thin-adapter** problems, NOT separate codebases. The entire loader/version-volatile surface is ~2,300 lines in <10 files (root fake-player stack + worldmodel data layer + `platform/` adapters). The fragile spots are the fake-player network internals (`ConnectedClientData`, `SyncedClientOptions`, protocol version, `ADD_PLAYER`/spawn packet — handled per-version in `overlays/`) and `NavSectionBuilder`'s reflection into `PalettedContainer`. Tooling: **Architectury Loom + Stonecutter** for the ≤1.21 era; **pure Fabric Loom + Stonecutter** for the 26 era; the **Architectury *API* is deliberately unused** (native per-loader glue → zero runtime deps). See the `portability-findings`, `multiversion-build-strategy`, `mc-26-fabric-era`, and `loader-matrix` memories.
