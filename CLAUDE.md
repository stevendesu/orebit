# Orebit

A server-side Minecraft mod that spawns AI-driven "ally bots" — virtual players that follow/assist the owner, eventually driven by a weak local LLM for intent recognition. Maven group `com.orebit.mod`, base name `orebit`. **Multi-version, multi-loader:** MC **1.17.1 → 26.2**; **Fabric** everywhere, **Forge** 1.17.1→1.21.11, **NeoForge** 1.21→1.21.11. Java is per-version (17 / 21 / 25). The ally-bot prototype is **runtime-verified in-game on Fabric** across the range (incl. multiplayer LAN, 26.2).

## IMPORTANT: Read this first — design-doc vs. real code

This repo is a **documentation-first codebase**. Of ~177 Java files, only ~30 contain runnable logic; the rest (~83%) are **Javadoc-only spec stubs**: a multi-section design comment followed by a bare `package` declaration, with **no class body, no fields, no imports**. Some are empty class bodies (`public class X {}`) or even 0-byte files. Do not mistake these for partial implementations — entire subsystems (ai, tasks, sim, memory, relationships, integration, eventbus, clock, behavior, requirements, settings, config, commands, debug, manager, agent, data, mocks, scripts) have **zero executable code**.

There are effectively **two architectures**: (1) the elaborate, modular design encoded in the stubs, and (2) the simpler **working prototype** in the root `com.orebit.mod` package (+ `worldmodel/` + the `platform/` adapters + the per-loader glue). They are largely disjoint — nothing running imports from pathfinding/ai/tasks/etc. When asked to change behavior, first determine whether the target is real code (root package + `worldmodel/` + `platform/`) or a stub.

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
- **JMH benchmarks / profiling** (headless, bootstrapped-MC Knot classloader, `forks=0`; this era only): `"Set active project to 1.21.4"` then `:1.21.4:jmh -Pbench=PathfinderBenchmark` (or `BlockReadBenchmark`). Profilers `-Pprof=gc,stack,jfr` — GC alloc-rate, sampled method hot-spots, and an in-process `jdk.ObjectAllocationSample` recording dumped to `build/alloc.jfr` (per-type/per-site allocation attribution, read with `jfr print`). `-Pscenario=TOWER` pins a `@Param`. `PathfinderBenchmark` drives `BlockPathfinder.findPath` over a synthetic in-memory `NavGridView` (no live level — see the package-private `NavGridView(minY, chunks)` seam). Background + rationale in `docs/Optimizations/pathfinding_hot_path.md`.

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
- **Keep CORE LOGIC in core; put only the thin divergent primitive behind an adapter.** The `platform/` package is exactly this seam: tiny version-selected static helpers the common core calls — `BlockLookup`, `VersionedBlocks`, `ChunkCoords`, `ConcretePowder`, `EntityState`, `LevelBounds`, `MineableTags`, `BlockKinds`, `BlockShapes`, `BotSpawn`, `Worlds`, `Replaceable`. **Static one-liners → JIT-inlined, no dispatch on hot paths.** Do NOT push business logic (e.g. `ChunkNavLoader`) into overlays; extract the MC-API call (e.g. `ChunkCoords.x(pos)` instead of `pos.x`) and keep the loop in core. Performance is critical (see PRD / Baritone-as-anti-example).
- **`overlays/26`** holds 26.x drift: `ChunkCoords` (`ChunkPos.x/.z` fields → `x()/z()` methods), `ConcretePowder` (16 dyed blocks → one `Blocks.CONCRETE_POWDER` `ColorCollection`).
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
- **(root package)** — REAL prototype. `Orebit`/`OrebitCommon` (entrypoint logic), `BotManager` (static UUID→AllyBotEntity map), `AllyBotEntity` (extends `FakePlayerEntity`; tick() does follow+look-at), `FakePlayerEntity` (extends `ServerPlayerEntity`; **version-divergent → in `overlays/`**), `FakeNetworkHandler` + `FakeClientConnection` (suppress I/O; `FakeClientConnection` in `overlays/`), `BotPositioning`. `ProxyNavigationEntity` + `FollowPlayerOwner` are dead experiments. The per-loader glue lives in `fabric/`, `forge/`, `neoforge/` modules (thin: a `PlatformEvents` impl + entrypoint).
- **platform/** — REAL thin version adapters (in `overlays/`, see Overlay strategy). The common↔loader seam (`PlatformEvents`) + MC-API shims.
- **worldmodel/navblock/** — REAL. `NavBlock` (interns the ~28k block STATES into ~587 behavioral fingerprints — a `short` navtype index → packed 64-bit `long` descriptor, ≤1024 capacity — at static-init).
- **worldmodel/pathing/** — MIXED. REAL: `TraversalGrid` (packed `short[4096]` = 6 flag bits + 10 navtype bits), `NavSection`/`NavSectionPool`, `NavStore`, `NavGridView` (the pathfinder's read seam — incl. a per-search open-addressed chunk cache that kills `Long`-key boxing; and a package-private synthetic ctor for headless benchmarks), `NavSectionBuilder.classifyInto`/`patchCell` (the live read+classify path), `ChunkNavBuilder`/`ChunkNavLoader`/`NavFlags`. BROKEN: `NavSectionBuilder.build()` is an inert benchmark. STUBS: `ReflectionHelper`, `WorldModel`. (`TraversalClass`/`TraversalAnalyzer*` were superseded by the packed grid + `NavFlags`.)
- **pathfinding/blockpathfinder/** — REAL. The block-tier A* (PRD §7.1), **allocation-free on its hot path** (`docs/Optimizations/pathfinding_hot_path.md`): `BlockPathfinder` (struct-of-arrays primitive search state, custom open-addressed `long`→row map, binary-heap open set, per-search `EditPool` arena), the `Movement` set (`Traverse`/`Diagonal`/`Ascend`/`Descend`/`Fall`/`Pillar`/`MineDown` behind `MovementRegistry.TIER1`), `MovementContext` (predicate vocabulary over `NavGridView`), `EditScratch` + pooled `StepEdits` (break/place folded onto moves), `PathEdits` (per-path edit diff), `BotCaps`, `BlockPathPlan`. Weighted 3D-octile heuristic + straight-line tie-break. Driven today by `AllyBotEntity.replan` (→ `applyEdits` replays the path's break/place edits). The REST of `pathfinding/` (region tier, support cast) is still stubs.
- **worldmodel/region/** — REAL data layer (`Region`/`LeafRegion`/`CompositeRegion`, `Portal`, `RegionPool`, `RegionMetadata`, `RegionBlockIndex`, `RegionBoundingBox`) — but **superseded by the ratified fixed-grid region design** (PRD §6.3: regions are a fixed cubic-grid/implicit octree, NOT semantic; the `Region`/`Portal` classes and the flood-fill `RegionBuilder` are the old semantic model). STUB: `RegionBuilder`.

STUB-ONLY (Javadoc + package decl, ZERO code): the rest of **pathfinding/** (region tier + support cast — all but `blockpathfinder/`, above), **ai/** (9), **tasks/** (7), **sim/** (5), **memory/** (9), **relationships/** (5), **integration/** (10, the LLM pipeline), **behavior/**, **requirements/**, **settings/**, **config/**, **eventbus/** + **clock/**, **commands/** (5) + **debug/** (7), **manager/**, **agent/**, **data/**, **mocks/** (4) + **scripts/** (3). **Note duplicate identities**: `manager.BotManager` (stub) vs root `BotManager` (real); `mocks/` Mock* vs root Fake*; `PathfindingSettings` in BOTH pathfinding/ and settings/.

MIXINS: minimal — the foundation does NOT lean on coremod hacks.

## Intended end-to-end flow (designed, NOT yet runnable)

player chat → `PromptBuilder` → `LLMInterface` → `LLMBackend` (local Ollama / OpenAI) → `InterpretedIntent` → `GoalDispatcher` → Requirements graph → AI `TaskExecutor`/`AIStateMachine` → two-tier pathfinding (`RegionPathfinder` over the Region tree → `BlockPathfinder` over atomic moves) → `VirtualPlayerController`. LLMs are spec'd for **intent only, never planning** (determinism pillar). **None of this runs today.** What actually runs: `OrebitCommon.init` registers join/disconnect/tick/chunk events via `PlatformEvents` → `BotManager` spawns an `AllyBotEntity` that follows the owner via manual `tick()` math.

## Key conventions

- **Spec-as-Javadoc**: write a thorough design comment as the file body before implementing; honor/maintain these specs when filling a stub.
- **Memory-conscious data structures**: bit-packing, sorted `short[]` + binary search over HashMaps, object pools for zero-GC hot paths.
- **Optimization-by-measurement**: see `docs/Optimizations/block_reading.md` (block reads optimized ~3,000,000 ns → ~6.7 ns/block).
- **Loader/MC coupling is deliberately minimal**: the common source is pure-Java/vanilla; loader specifics are the `PlatformEvents` impls; version specifics are `overlays/` + the `platform/` adapters. **Bot = `ServerPlayerEntity` subclass** (not a custom EntityType) so it passes vanilla `instanceof` player checks — at the cost of welding to the player network/protocol stack (the most version-fragile surface).

## Gotchas / known issues (in REAL code)

- `OrebitCommon`/`Orebit` ran a `benchmarkMe()` on every player JOIN in the old prototype — verify it's gone before shipping.
- `AllyBotEntity.tick()` likely **double-calls `tickMovement()`** and recomputes yaw redundantly.
- `FakePlayerEntity.tick()` **skips `ServerPlayerEntity.tick()`** — an unaudited set of player tick side-effects is dropped.
- `NavSectionBuilder` holds a **public static, non-thread-safe `BlockState[]` scratch** buffer; it also reflects into `PalettedContainer` internals — the most version-fragile code in the project.
- `ChunkNavLoader` builds NavSection[] then **discards it** — the worldmodel pipeline stores no data yet.
- `ProxyNavigationEntity` **self-spawns into the world in its constructor** — latent bug; unused.
- `TraversalAnalyzer` (`block.toString().contains("CONCRETE_POWDER")`) and `TraversalAnalyzerMutable` (proper `Set<Block>`) have **drifted**.
- README's "Smart Objects Over Managers" pillar is **contradicted** by the live static `BotManager`.

## Where to look

- **Canonical design: `internal_docs/PRD.md`** — the ratified world-model + pathfinding design, decisions log, phased build plan. Internal docs live in `internal_docs/`, NOT `docs/` (which is the PUBLIC MkDocs site, `docs_dir: .`, auto-deployed to GitHub Pages on push to `docs/**`). Other internal: `internal_docs/PORTABILITY-AUDIT.md`, `internal_docs/BUILD-STRATEGY.md` (the multi-version/multi-loader strategy + ecosystem findings). `HANDOFF.md` (root) = the temporary next-session pointer.
- Vision/architecture: `src/main/java/com/orebit/mod/README.md` (module table + end-to-end flow).
- Optimization rationale: `docs/Optimizations/block_reading.md`.
- Design specs: `docs/pathfinding.md`, `docs/worldmodel.md` (the code stubs still carry OLD drifted vocabulary until Phase 4 reconciles them).
- **Root design dumps:** `design-principles.txt` (author's coding philosophy — "no Utils/Helper classes", "smart objects over data objects", "abstract classes over enums", "statically-sized structures", "prefer determinism", "avoid `final`"; live code partially contradicts these). `dump.txt` is an auto-generated fossil of an OLDER `worldmodel/` design (`BlockMap`, `BlockTagMap`, `SubChunk`, `RegionTree`…) that predates today's code.
- Public site: MkDocs Material → GitHub Pages (`stevendesu.github.io/orebit`).
- `fabric.mod.json` metadata is still partly the Fabric example template (description/authors/contact unedited).

## Portability (Fabric vs Forge, multi-version)

Both are **shared-core + thin-adapter** problems, NOT separate codebases. The entire loader/version-volatile surface is ~2,300 lines in <10 files (root fake-player stack + worldmodel data layer + `platform/` adapters). The fragile spots are the fake-player network internals (`ConnectedClientData`, `SyncedClientOptions`, protocol version, `ADD_PLAYER`/spawn packet — handled per-version in `overlays/`) and `NavSectionBuilder`'s reflection into `PalettedContainer`. Tooling: **Architectury Loom + Stonecutter** for the ≤1.21 era; **pure Fabric Loom + Stonecutter** for the 26 era; the **Architectury *API* is deliberately unused** (native per-loader glue → zero runtime deps). See the `portability-findings`, `multiversion-build-strategy`, `mc-26-fabric-era`, and `loader-matrix` memories.
