# HANDOFF — Orebit: 26.2 builds on Fabric (new era); Architectury 26.x blocked upstream

> **Temporary file.** Delete/overwrite at the end of the next session.
> Written end of session 11 (2026-06-22). Supersedes the session-10 handoff.

## DONE this session — a buildable MC 26.2 Fabric jar

`main` is now the **26.x era** and produces a real, non-hollow **Fabric** jar for **MC 26.2**
(`build/libs/<modver>/orebit-1.0.0+26.2.jar`). **RUNTIME-VERIFIED IN-GAME:** installed into a real
Fabric Loader 0.19.3 + Fabric API 26.2 client, the ally bot spawns, follows the owner, and works in
multiplayer (LAN) — each player gets their own bot; all are real shared entities. **Runtime fix:**
`fabric.mod.json` must depend on `fabric-api` (the 26.x umbrella mod-id), not `fabric` (the ≤1.21 id).
**Docs updated** (CLAUDE.md fully rewritten for the multi-era build; 1.21.4-special refs dropped;
repo renamed `orebit-1.21.4`→`orebit`) — authored on `core`, merged to `main`.

### The big finding: Architectury Loom can't do 26.x yet → 26 era is Fabric-only via PURE Fabric Loom
- **Architectury Loom has no MC 26.1+ support** ([architectury/architectury-loom#328](https://github.com/architectury/architectury-loom/issues/328), open since Feb 2026, no fix/ETA). 26.1 is **unobfuscated** → Mojang ships **no mappings artifact**, but Architectury Loom still hard-requires a non-empty `mappings` config (`Configuration 'mappings' has no dependencies`).
- **Orebit's source is 100% Architectury-API-free** (verified: zero `dev.architectury` imports; the loader seam is hand-written DI — `OrebitFabric` → `FabricPlatformEvents` → `OrebitCommon.init`). So the **Fabric** target builds on **plain Fabric Loom** with no Architectury at all.
- **Decision (user-approved):** keep Architectury + all 3 loaders for the **mc-1.21** era; build **26.x as a Fabric-only era** on `main` with pure `net.fabricmc.fabric-loom`. Forge/NeoForge 26.x wait until #328 lands.

### Toolchain (mc-26 era, in `era.properties` + wrapper)
- **JDK 25** (Temurin 25 installed: `jdk-25.0.3.9`). Build logic selects 25 for `>=26` (on `core`).
- **Gradle 9.5.0** (Loom 1.17.12 requires it — 9.4.0 failed the `org.gradle.plugin.api-version` match).
- **Fabric Loom `1.17.12`** (`net.fabricmc.fabric-loom`, the non-remapping plugin). `era.properties loom.version=1.17.12`.
- Unobf build idioms: **no `mappings()`**, plain `implementation`/`jar` (not `modImplementation`/`remapJar`).

### Build structure (Model C refinement)
- `main`'s build is now **single-module, Fabric-only**: `settings.gradle.kts` (one project per version, no loader branches), `stonecutter.gradle.kts`, and `build.gradle.kts` compile `src/` (common) + `fabric/src/` (glue) + composed overlays into one jar.
- **Refinement:** when an era's **loader toolchain** differs (Architectury vs Fabric Loom), the **build SCRIPTS** join the era-owned set (alongside `era.properties` + wrapper). Common **source + overlays still merge cleanly from `core`** (build-tool-agnostic). Because `core` never touches these build scripts, `git merge core` into `main` keeps `main`'s Fabric-only scripts (no conflict).

### 26.x API drift fixed (thin `platform/` adapters — core logic stays in core, per user direction)
Authored on `core` (portable; baseline keeps mc-1.21 green, `overlays/26` applies only to 26.x):
- **`platform/ChunkCoords.x/z(ChunkPos)`** — 26.1 made `ChunkPos.x/.z` private; accessors are `x()/z()`. Baseline returns the fields, `overlays/26` the methods. `ChunkNavBuilder`/`ChunkNavLoader` call the accessor. Static one-liners → JIT-inlined (no hot-path dispatch).
- **`platform/ConcretePowder.all()`** — 26.1 collapsed the 16 dyed concrete-powder blocks into one `Blocks.CONCRETE_POWDER` `ColorCollection`. Baseline returns the 16 constants; `overlays/26` returns `CONCRETE_POWDER.asList()`. `TraversalAnalyzerMutable` folds it into its gravity set (built once).
- **`FabricPlatformEvents`** (loader glue, edited in place on `main`): `ServerTickEvents.END_WORLD_TICK` → `END_LEVEL_TICK`; `ServerChunkEvents.Load` gained a 3rd arg (`newlyGenerated`).

### Branches (all LOCAL except mc-1.21)
- **`mc-1.21`** (pushed) — snapshot of the previous era (Architectury, all 3 loaders, MC 1.17.1→1.21.11). Untouched, still green.
- **`core`** (local) — + `>=26→JDK25` toolchain logic, version headers, and the `overlays/26` + accessor commit. **Build scripts still Architectury** (unchanged; that's correct — `core` stays loader-agnostic).
- **`main`** (local) — the 26.x Fabric era (toolchain + Fabric-only build + the FabricPlatformEvents fix). **Nothing pushed yet.**

**Pivot point reached:** both eras in good shape (1.17.1–1.21.11 Fabric+Forge; 26.0–26.2 Fabric-only),
runtime-verified, docs current. Ready to start **actual mod logic** (PRD Phase 0/1).

## NEXT
1. **Push** `core` + `main` (nothing pushed yet this session except the `mc-1.21` snapshot). Runtime is verified, so this is safe — `main` is the intended GitHub default. *(Held pending the user's go-ahead.)*
2. **Propagate `core` → `mc-1.21`** (Model C): `git merge core` into `mc-1.21` brings the portable accessor refactor (ChunkCoords/ConcretePowder — behavior-neutral for ≤1.21 via the baseline overlays) + the updated docs, then run `chiseledCompileCommon` (the CI gate) to confirm the baseline overlays compile across 1.17.1→1.21.11. Currently `mc-1.21` is still at the pre-26 commit (not broken — just an older `core` state).
3. **`installation.md` loader nuance** (public doc): the user's edit says ">1.21 → NeoForge or Fabric", but the real matrix is Forge+Fabric through 1.21.11 and **Fabric-only at 26.x** (no NeoForge shipped for 26). Confirm wording with the user.
4. **Reconcile FabricPlatformEvents divergence** — edited in place on `main`; if `core` ever changes it, merges conflict. Consider an `overlays-fabric/` (baseline + 26), or accept the contained divergence (rare changes).
5. **Forge/NeoForge 26.x** — blocked on architectury-loom#328; revisit when it ships (watch the issue).
6. **Restore the JMH/test harness for 26.x** (deferred — test source set emptied on `main`): needs fabric-loader-junit headless bootstrap verified on unobfuscated 26.x.
7. **Block-registry follow-up (Phase 1, user-flagged):** the concrete-powder `ColorCollection` is the first sign of 26.x block reorg; 1.21.5→26.x likely added blocks/wood our nav registries (`RegionMetadata` counters, NavBlock) don't yet handle. Audit when nav work begins.

## Gotchas (carry forward)
- **Run from the ACTIVE node:** `./gradlew :26.2:compileJava` (active marker = 26.2). `compileJava` across ALL nodes fails because Stonecutter only fills live `src/` for the active version; non-active nodes lack the common source. `Set active project to <ver>` before building another 26.x patch.
- Use `JAVA_HOME=…/jdk-25.0.3.9-hotspot` for all 26.x Gradle invocations.
- `core` is not buildable (no `era.properties`) — use a **worktree** to author on `core` without disturbing `main`'s working tree (used this session for the overlay/accessor commit).
- The `build/libs/1.0.0/{fabric,forge,neoforge}/…` jars are STALE leftovers from prior mc-1.21-era builds; the 26 era's jar is `build/libs/1.0.0/orebit-1.0.0+26.2.jar`.

## Reference
- `internal_docs/BUILD-STRATEGY.md` §3 (Loom-span), §6 (add-newer-era). Memory: `loader-matrix`, `multiversion-build-strategy`, `overlay-rebaseline-procedure`, `commit-hygiene`, `prefers-strategy-over-conditionals`.
