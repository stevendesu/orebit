# HANDOFF — Orebit: backward era extended to 1.17.1 (Fabric+Forge); runtime pass in progress

> **Temporary file.** Delete/overwrite at the end of the next session.
> Written end of session 9 (2026-06-22). Supersedes the session-8 "wall found" handoff.

## TL;DR

The current toolchain era (Loom 1.13.469 / Gradle 8.12.1) now spans **MC 1.17.1 → 1.21.11**.
Common + Fabric + Forge all **compile** across that whole range. **Runtime-verified:** Fabric on
1.17.1/1.18.1/1.19/1.19.3/1.20/1.20.1 and **Forge on 1.17.1** (in-world, bot spawns). The user is
working through the remaining Forge versions at runtime. The backward **toolchain wall stays 1.13.2**
(no official Mojang mappings; the 1.13 Flattening) — a separate, deferred era. **≤1.16.5** is still
deferred (WIP on the local `scratch/backward-probe` branch).

## Branches / build (unchanged model)

- **`core`** — portable trunk (no toolchain values; not buildable). Author common/overlay/version
  files here, then `git merge core` into era branches.
- **`main`** — the ≤1.21.11 era (= `core` + `era.properties`); buildable; GitHub default.
- **`scratch/backward-probe`** — LOCAL only; partial ≤1.16.5 WIP (`DO NOT propagate`).
- **NOT pushed** — local commits well ahead of origin (core ~15, main ~34). Push when the user asks
  (personal SSH key; `gh` is the work account so use plain `git push`).
- Toolchain: **per-version Java toolchain** — `<1.20.5 → JDK 17`, `≥1.20.5 → JDK 21`. **Both Temurin
  17.0.19 and 21.0.11 are installed** (winget); Gradle auto-detects them.

## What this era covers now

- `era.properties` matrices: **Fabric = 1.17.1→1.21.11 contiguous**; **Forge = same minus its skips**
  (no 1.17.0/1.20.3/1.20.5/1.21.2); **NeoForge floor 1.21**. `versions/<ver>/gradle.properties` exist
  for all (fabric_api/forge_loader from each repo's maven-metadata). **1.17.0 dropped** (no viable
  fabric-api; Forge skipped it). **1.20.0 added**.
- Overlay chain (common): baseline **`overlays/1.17`** + override eras `1.19`, `1.19.3`, `1.20`,
  `1.20.2`, `1.20.3`, `1.20.5`, `1.21.2`, `1.21.6`, `1.21.11`. Key backward boundaries:
  - **1.19.3**: 3-arg ServerPlayer ctor returns (ProfilePublicKey was 1.19–1.19.2);
    BambooBlock→BambooStalkBlock; Registry→BuiltInRegistries.
  - **1.20**: Entity.level/onGround field→method; Material→canBeReplaced; sword_efficient tag.
  - Platform shims (route core src through these): `Worlds`, `EntityState`, `Replaceable`,
    `MineableTags`, `BlockKinds`, `BlockLookup`.
- Overlay chain (forge): baseline **`overlays-forge/1.17.1`** + `1.18`, `1.19`, `1.21.6`, `1.21.9`.
  Forge backward boundaries: **1.18** (`fmlserverevents.FMLServerStartedEvent` →
  `event.server.ServerStartedEvent`), **1.19** (world→level rename).

## Runtime fixes landed this session (the fragile loader-specific surface)

- **Java run JVM:** Loom honors the `java{}` toolchain for COMPILE but launches runs on the Gradle
  daemon JDK. Old-Forge's modlauncher ASM can't read Java 21 → `Unsupported class file major version
  65`. Fixed by pinning the run JVM to the toolchain in `forge/build.gradle.kts`
  (`tasks.withType<JavaExec>{ javaLauncher = … }`). (Fabric runs fine on 21, untouched.)
- **Fabric:** exclude fabric-api's transitive fabric-loader (old fabric-api pins 0.12.x →
  "duplicate fabric loader classes"); `fabric.mod.json` depends on mod-id **`fabric`** not
  `fabric-api` (old umbrella id is `fabric`; modern provides it).
- **Forge `mods.toml`:** `loaderVersion`/forge `versionRange` were hardcoded `[47,)`; now templated
  `[${loader_major},)` from `deps.forge_loader` (new processResources prop) — FML's javafml version ==
  the Forge MAJOR (37 on 1.17.1).
- **Forge bot spawn (`FakeClientConnection`, common baseline `overlays/1.17`):** the socketless bot
  connection broke Forge's network path in `placeNewPlayer` twice — (a) `NetworkHooks.getConnectionType`
  NPE on a null `fml:netversion` channel attr → set it to `"NONE"` (vanilla marker); (b) the vanilla
  network filter does `pipeline.addBefore("packet_handler", …)` → name the Connection handler
  `"packet_handler"` via a ChannelInitializer (added before channelActive so `Connection.channel` is
  still set). Both set by netty attr/name → no Forge import, harmless on Fabric.

## GOTCHAS (read before debugging next session)

- **Loader nodes build from `chiseledSrc`**, which only re-syncs from `src/`+overlays on
  **`Set active project to <ver>`** (or a chiseled task). After ANY common/overlay/loader-src edit,
  run `Set active project to <ver>` BEFORE `:<loader>:<ver>:runClient`, or you test a stale copy.
- A single `:<ver>:compileJava` is HOLLOW for *core* src (compiles only overlay-merged), but it DOES
  compile overlay files — handy for syntax-checking an overlay edit. Trust `chiseledCompile*` for the
  full picture. New probe tasks: **`chiseledCompileForge` / `chiseledCompileFabric`** (compileJava-only,
  like `chiseledCompileCommon`).
- **Loom run JVM = Gradle daemon JDK unless `javaLauncher` is pinned** (done for forge; do the same if
  fabric/neoforge ever need a non-daemon JDK).
- **Commit hygiene** (user pref, memory `commit-hygiene`): don't commit half-working runtime builds —
  accumulate fixes in the working tree, commit at a confirmed-working point. (Compile-only milestones
  may commit when green.)

## NEXT

1. **Finish the Forge runtime pass** (user driving): 1.18.x, 1.19.x, 1.20.x. Same `Set active` →
   `runClient` loop. **Watch:** Forge 1.20+ uses `net.minecraftforge.network` (not `fmllegacy`) — the
   `fml:netversion`/`packet_handler` fix may behave differently or be unneeded there; a newer Forge may
   surface a different bot-spawn issue (it's an `overlays-forge`/`FakeClientConnection`-flavor fix if so).
   Forge 1.20.1 bot-spawn was runtime-confirmed in earlier sessions, so the modern path likely already works.
2. **Fabric runtime spot-checks** for the untested patches (1.18, 1.18.2, 1.19.1, 1.19.2, 1.19.4) — low
   risk (shared overlay composition with confirmed versions).
3. **≤1.16.5 hard tier** — deferred; partial WIP on `scratch/backward-probe` (rotation setters, Entity
   discard/remove, Block.defaultDestroyTime, dripstone/cave-vines, Material tooling, slf4j→log4j).
4. **26.x forward era** — needs JDK 25 (Temurin 25 not yet installed) + a newer-era branch.
5. **Push** `core` + `main` when the user is ready.

## Reference

- Full plan: `internal_docs/BUILD-STRATEGY.md` (§8 / §8.1). Memory: `loader-matrix`,
  `overlay-rebaseline-procedure`, `commit-hygiene`, `multiversion-build-strategy`, `version-overlays`.
