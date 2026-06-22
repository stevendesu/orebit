# Orebit

A server-side Minecraft Fabric mod (MC 1.21.4, Java 21) that spawns AI-driven "ally bots" — virtual players that follow/assist the owner, eventually driven by a weak local LLM for intent recognition. Maven group `com.orebit.mod`, base name `orebit`.

## IMPORTANT: Read this first — design-doc vs. real code

This repo is a **documentation-first codebase**. Of ~177 Java files, only ~30 contain runnable logic; the rest (~83%) are **Javadoc-only spec stubs**: a multi-section design comment followed by a bare `package` declaration, with **no class body, no fields, no imports**. Some are empty class bodies (`public class X {}`) or even 0-byte files. Do not mistake these for partial implementations — entire subsystems (ai, tasks, sim, memory, relationships, integration, eventbus, clock, behavior, requirements, settings, config, commands, debug, manager, agent, data, mocks, scripts) have **zero executable code**.

There are effectively **two architectures**: (1) the elaborate, modular design encoded in the stubs, and (2) the simpler **working prototype** in the root `com.orebit.mod` package. They are largely disjoint — the only cross-package import in real code is `AllyBotEntity` using `FakePlayerEntity`. Nothing running imports from pathfinding/ai/tasks/etc. When asked to change behavior, first determine whether the target is real code (root package + `worldmodel/`) or a stub.

## Build / Run

- JDK 21 (Temurin). **Architectury Loom 1.13.469**, Gradle 8.12.1, official Mojang mappings (+ optional Parchment). This is now a **Stonecutter + Architectury multi-version, multi-loader** build (NOT a single hard-pinned version) — see the branching section below.
- `./gradlew chiseledBuild{Fabric,Neoforge,Forge}` — build all versions for a loader; JARs land in `build/libs/<modver>/<loader>/`. (`chiseledBuild` = all loaders; heavier.)
- `./gradlew chiseledCompileCommon --continue` — cheap probe: compile the common source against every version (pins which MC version introduced an API divergence).
- Run/dev a specific version: `./gradlew "Set active project to <ver>"` then `:<loader>:<ver>:runClient`; `"Reset active project"` returns active to the era's default. (Per-version deps live in `versions/<ver>/gradle.properties`; the matrix is in `settings.gradle.kts`.)
- **GOTCHA:** a direct `:<loader>:<ver>:buildAndCollect` can succeed with a hollow **NO-SOURCE** jar (Stonecutter only fills `chiseledSrc` via `setupChiseledBuild`, which runs in `chiseledBuild<Loader>`). Trust the chiseled tasks; verify a jar with `unzip -l <jar> | grep com/orebit/mod`.

## Branching & multi-version strategy (Model C — full plan in `internal_docs/BUILD-STRATEGY.md`)

Orebit supports many MC versions × loaders via **branch-per-toolchain-era** (NOT per individual version). An *era* is a span of versions sharing one Loom/Gradle/Java toolchain; within an era, Stonecutter + the `overlays/` chain cover the individual versions.

**CRITICAL authoring rules** (these keep cross-era bugfix propagation clean — violating them is the main way to break the model):
- **`core` branch** = everything version-portable: common logic, the `overlays/` chain, `versions/*/gradle.properties`, and build-script *logic*. It holds **NO toolchain version values** and is **not directly buildable**. **Author ALL common/overlay/version-file changes here**, then `git merge core` into each era branch (conflict-free, because `core` has no toolchain to drag).
- **Era branches** — `main` (newest era; GitHub default = "builds latest") and `mc-1.20` / `mc-1.16` / … (older). Each = `core` + **one** commit of era toolchain values (Loom/Gradle/Java versions + the Stonecutter version-matrix). Each is buildable; `runClient`/dev happens **here** (or in a git worktree).
- **NEVER author common-logic/overlay changes directly on an era branch (including `main`)**, and **NEVER edit toolchain values on `core`**. Either one breaks clean propagation. Common change → `core`; toolchain change → the era branch.
- Before release, run `chiseledCompileCommon` on **every** era branch (CI gate) to catch common code that accidentally depends on an API absent from an older era.

**CURRENT STATUS: not yet split.** The repo is still a single branch (`master`) that *is* the ≤1.21.11 era (Loom 1.13.469 / Gradle 8.12.1 / JDK 21). The `core` split + first older-era branch is the next migration step (backward first, then 26.x forward). Until the split lands, `master` is both trunk and current era — but write code as if these rules already apply.

## Repo map (package-by-package, with completeness)

All under `src/main/java/com/orebit/mod/`.

WORKING CODE:
- **(root package)** — REAL prototype. `Orebit` (ModInitializer entrypoint), `BotManager` (static UUID→AllyBotEntity map, spawn/remove), `AllyBotEntity` (extends FakePlayerEntity; tick() does follow+look-at via raw forwardSpeed/jump), `FakePlayerEntity` (extends ServerPlayerEntity), `FakeNetworkHandler` + `FakeClientConnection` (suppress all I/O), `BotPositioning` (safe-spot scan). `ProxyNavigationEntity` + `FollowPlayerOwner` exist but are **dead/disconnected** experiments. `OrebitClient` is an empty ClientModInitializer.
- **worldmodel/navblock/** — REAL. `NavBlock` (the gem: at static-init iterates `Registries.BLOCK`, compresses ~900 blocks into ≤256 canonical behavioral fingerprints; `dumpBlockCsv` debug). `ClimbableNavBlock` is an empty stub.
- **worldmodel/pathing/** — MIXED. REAL: `TraversalClass` (CLEAR/EASY/SLOW/BLOCKED enum w/ cost), `TraversalGrid` (2-bit-packed 16³, 1KB), `TraversalAnalyzer` + `TraversalAnalyzerMutable` (block classification), `NavSection` + `NavSectionPool`, `ChunkNavBuilder`. BROKEN: `NavSectionBuilder.build()` is an inert benchmark — all classification logic commented out across 5 "STEP" experiments; `ChunkNavLoader` registers Fabric chunk hooks but **discards** the NavSection[] it builds (no chunk→section map exists). STUBS: `ReflectionHelper` ("TBD"), `WorldModel`.
- **worldmodel/region/** — REAL data layer. `Region`/`LeafRegion`/`CompositeRegion`, `Portal`/`PortalShape` (isNavigableBy commented out), `RegionPool`, `RegionMetadata`, `RegionBlockIndex`, `RegionBoundingBox`. STUB: `RegionBuilder` (the flood-fill that would populate the tree — empty).

STUB-ONLY (Javadoc + package decl, ZERO code unless noted):
- **pathfinding/** — 28 files, 100% stubs (BlockPathfinder, RegionPathfinder, all heuristics, PathPlan/PathFollower/PathBudgetManager/PathCache/PathStatus, all Operation types). The "interfaces" exist only in comments — nothing to implement against.
- **ai/** (9), **tasks/** (7), **sim/** (5) — AI execution core (AIStateMachine, StateStack, tick scheduling, behavior-tree Task/Action/Condition, simulators). All stubs.
- **memory/** (9), **relationships/** (5) — episodic memory + decay strategies, social graph. All stubs.
- **integration/** (10) — LLM pipeline (LLMBackend, Local/OpenAI backends, PromptBuilder, GoalDispatcher). All stubs. Most platform-agnostic part (zero MC/Fabric refs).
- **behavior/**, **requirements/**, **settings/**, **config/** — personality, crafting-plan graph, settings, config pipeline. All stubs; `BuilderProfile/FighterProfile/WandererProfile` are empty class bodies; `MaterialRegistry/RequirementGraph/RequirementNode/Satisfier` are 0-byte files.
- **eventbus/** + **clock/** — custom pub/sub bus, SimulationClock, PerformanceMonitor. All stubs, unwired.
- **commands/** (5) + **debug/** (7) — chat command pipeline, dev overlays/profiler/replay. All stubs.
- **manager/**, **agent/**, **data/** — the *intended* modular refactor of the root prototype (BotManager, VirtualPlayerContext, persistence). All stubs. **Note duplicate identities**: `manager.BotManager` (stub) vs root `BotManager` (real); `mocks/` Mock* vs root Fake*; `PathfindingSettings` exists in BOTH pathfinding/ and settings/.
- **mocks/** (4) + **scripts/** (3) — test mocks, scripting hooks. All stubs.

MIXINS (3, in mixin/): `DebugLogging` (REAL — injects LivingEntity.travel HEAD, logs FakePlayerEntity movement), `ExampleMixin` (no-op on MinecraftServer.loadWorld), `ExampleClientMixin` (no-op on MinecraftClient.run). Mixin usage is minimal — the foundation does NOT lean on coremod hacks.

## Intended end-to-end flow (designed, NOT yet runnable)

player chat → `PromptBuilder` → `LLMInterface` → `LLMBackend` (local Ollama / OpenAI) → `LLMResponseHandler` → `InterpretedIntent` → `GoalDispatcher` → Requirements graph (recursive crafting/goal plan) → AI `TaskExecutor`/`AIStateMachine` → two-tier pathfinding (`RegionPathfinder` over Region tree → `BlockPathfinder` over atomic moves) → `VirtualPlayerController` actuates the bot. LLMs are spec'd for **intent only, never planning** (determinism pillar). **None of this runs today** — every stage is a stub.

What actually runs: `Orebit.onInitialize` registers Fabric `ServerPlayConnectionEvents.JOIN/DISCONNECT` → `BotManager.spawnBotFor` creates an `AllyBotEntity` that follows the owner via manual `tick()` math.

## Key conventions

- **Spec-as-Javadoc**: write a thorough design comment as the file body before implementing. Honor/maintain these specs when filling in a stub.
- **Memory-conscious data structures**: bit-packing (2-bit TraversalGrid), sorted `short[]` + binary search (LeafRegion) over HashMaps, object pools (NavSectionPool, RegionPool) for zero-GC hot paths.
- **Optimization-by-measurement**: see `docs/Optimizations/block_reading.md` (the canonical rationale for NavSectionBuilder's STEP experiments — block reads optimized from ~3,000,000 ns to ~6.7 ns/block).
- **Fabric coupling is deliberately minimal**: only `Orebit.java` and `ChunkNavLoader.java` touch Fabric API (~7 event registrations). Logic layers are intended to be pure-Java/vanilla and loader-agnostic.
- **Bot = ServerPlayerEntity subclass** (not a custom EntityType) so it passes vanilla `instanceof` player checks — at the cost of welding to the player network/protocol stack.

## Gotchas / known issues (in REAL code)

- `Orebit.benchmarkMe()` runs on **every player JOIN** (100 block-state reads + stdout print) — leftover prototype/debug code in a hot path. Remove before shipping.
- `AllyBotEntity.tick()` likely **double-calls `tickMovement()`** (once via super.tick path, once explicitly ~line 75) and recomputes yaw inline after `lookAtPlayer()` (redundant double-write).
- `FakePlayerEntity.tick()` calls `baseTick()`+`tickMovement()` but **skips `ServerPlayerEntity.tick()`** — an unaudited set of player tick side-effects is dropped.
- `NavSectionBuilder` holds a **public static, non-thread-safe `BlockState[]` scratch** buffer — concurrent chunk loads would corrupt it. It also reflects into private `PalettedContainer$Data` internals by Yarn field name (most version-fragile code in the project).
- `ChunkNavLoader` builds NavSection[] then **discards it** — the worldmodel pipeline produces no stored data, so a pathfinder would have nothing to run on even if implemented.
- `ProxyNavigationEntity` **self-spawns into the world in its constructor** — side-effect-heavy latent bug; it and `FollowPlayerOwner` are unused.
- `TraversalAnalyzer` (uses `block.toString().contains("CONCRETE_POWDER")`) and `TraversalAnalyzerMutable` (uses a proper `Set<Block>`) have **drifted** — same concern, divergent logic.
- README's "Smart Objects Over Managers" pillar is **contradicted** by the live static `BotManager`.

## Where to look

- **Canonical design: `internal_docs/PRD.md`** — the ratified foundational design (world model + pathfinding), decisions log, and phased build plan. Start here for "what are we building and why." **Internal docs live in `internal_docs/` deliberately, NOT `docs/`: `docs/` is the PUBLIC MkDocs site** (`docs_dir: .`, auto-deployed to GitHub Pages by `.github/workflows/docs.yml` on any push to `docs/**`), so internal docs must NOT live there. Other internal docs: `internal_docs/PORTABILITY-AUDIT.md`, `internal_docs/BUILD-STRATEGY.md` (multi-version/multi-loader strategy + ecosystem findings).
- Vision/architecture: `src/main/java/com/orebit/mod/README.md` (far more complete than the code; module table + end-to-end flow + open design questions).
- Optimization rationale: `docs/Optimizations/block_reading.md` (most complete doc), `object_pooling.md` (TBD).
- Design specs (updated 2026-06 to match the PRD; the old movement-table/bitfield drift is fixed): `docs/pathfinding.md` (adopted movement vocabulary + cost model), `docs/worldmodel.md` (NavBlock index-table model + region/HPA* layers). Note: the *code* stubs still carry the OLD drifted vocabulary until Phase 4 reconciles them.
- **Root design dumps (easy to miss, not under src/ or docs/):** `design-principles.txt` (the author's coding philosophy — "no Utils/Helper classes", "smart objects over data objects", "abstract classes over enums", "statically-sized structures", "prefer determinism", "avoid `final`"; note the live code partially contradicts these). `dump.txt` is an auto-generated (via `chatgpt-dump.js`) concatenation of an OLDER `worldmodel/` design — classes that no longer exist (`BlockMap`, `BlockTagMap` with a `PASSABLE/BREAKABLE/CLIMBABLE/PORTAL/HAZARDOUS/SUPPORT_BLOCK` tag bitmask, `SubChunk`, `RegionTree`, `RegionKnowledge`); useful as a fossil of design intent that predates today's NavBlock/TraversalClass/Region code.
- Public site: MkDocs Material → GitHub Pages (`stevendesu.github.io/orebit-1.21.4`).
- `fabric.mod.json` metadata is still the Fabric example template (description/authors/contact unedited).

## Portability (Fabric→Forge, 1.21.4→multi-version)

Assessed in depth. Both are **shared-core + thin-adapter** problems, NOT separate codebases — and cheapest to set up NOW while subsystems are still stubs. The entire loader/version-volatile surface is ~2,300 lines in <10 files (root fake-player stack + worldmodel data layer). Fabric API is touched in only 2 files. The fragile spots are the fake-player network internals (`ConnectedClientData`, `SyncedClientOptions`, protocol version, `ADD_PLAYER` packet) and `NavSectionBuilder`'s reflection into `PalettedContainer` internals by Yarn field name. Recommended tooling: **Architectury** (Loom + API) for multi-loader incl. **NeoForge** over legacy Forge; **Stonecutter** for multi-version source sets. See the `portability-findings` memory for the full verdict.
