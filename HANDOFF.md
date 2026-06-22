# HANDOFF — Orebit: 1.21.11 matrix shipped; branch-per-era migration (Model C) is next

> **Temporary file.** Delete/overwrite at the end of the next session.
> Written end of session 6 (2026-06-22).

## State: stable & committed

- On **`master`**, working tree clean. All work committed as **`829e57b`** ("Extend build matrix
  to MC 1.21.11 across Fabric/NeoForge/Forge"). **16 commits ahead of `origin/master`, NOT pushed**
  (push is the user's call).
- **45 JARs build green** (`chiseledBuild{Fabric,Neoforge,Forge}`): Fabric 1.20.1–1.21.11 (18),
  NeoForge 1.21–1.21.11 incl. 1.21.2 backfill (12), Forge 1.20.1–1.21.11 minus 1.20.3/1.20.5/1.21.2
  (15). Toolchain: **Architectury Loom 1.13.469 / Gradle 8.12.1 / JDK 21**, **fabric-loader 0.17.3**.
- Active version reset to 1.21.4.
- **Runtime-validated** (user, in-game): Forge 1.20.1; Fabric 1.20.1/1.20.6/1.21/1.21.11; NeoForge
  1.21/1.21.11. (Forge 1.20.6 can't dev-launch — the old architectury-loom #205 wall; ships fine.)
  **1.21.5–1.21.10 not yet runtime-tested** (assumed OK).

## THE decision this session: migrate to branch-per-era ("Model C")

Full plan + rationale: **`internal_docs/BUILD-STRATEGY.md`** (read it first). `CLAUDE.md` now encodes
the authoring rules. Memory: `multiversion-build-strategy`. In brief:

- **Why:** one unified Stonecutter build can't span a huge MC range — toolchains conflict (Loom 1.14
  breaks legacy Forge; 26.x needs Loom 1.14+/Gradle 9/JDK 25). The ecosystem branches; so will we.
- **Model C:** `core` branch = all version-portable content (common logic, the `overlays/` chain,
  `versions/*/gradle.properties`, build-script *logic*) — **NO toolchain values, not buildable.**
  **Era branches** = `core` + one toolchain-values commit; `main` = newest era + GitHub default.
  A common fix is authored **once on `core`** and `git merge core`'d into each era branch
  **conflict-free** (core has no toolchain to drag). Overlays are central (full chain on every
  branch; composition selects the applicable subset; extra/higher eras are inert).
- **Authoring rules (now in CLAUDE.md):** common/overlay/version-file changes → `core`; toolchain
  values → era branch only. Never mix. Pre-release gate: `chiseledCompileCommon` on every era.
- **No Architectury API** (zero runtime deps / single drag-drop JAR — user decision); keep overlays;
  keep Forge everywhere (per-era toolchain makes it viable).

## Next steps (in order — do NOT branch prematurely)

1. **Execute the `core` split** (one-time restructuring): cut `core` from `master`; rename
   `master`→`main` as the newest era; externalize the per-era toolchain values (Loom version, Gradle
   wrapper version, Java version, Stonecutter matrix) into an era-owned file (candidate:
   `era.properties` read by build scripts, + `gradle-wrapper.properties`) so `core` holds none.
   Add `propagate`/`build-all`/`dev`/`reset` convenience scripts (worktree-based; see BUILD-STRATEGY §7).
2. **Prove the model backward FIRST** (going forward redefines `main`; backward leaves it stable):
   probe the first backward toolchain wall — add a few ≤1.20 versions as common nodes + run
   `chiseledCompileCommon`; likely a Java drop (~1.17 Java 16 / ~1.16 Java 8) or the Fabric floor
   (~1.14). Cut `core` + the first older-era branch; verify build + the `merge core` propagation.
3. **Forward to 26.x** once **Temurin JDK 25** is installed (user is doing this): new era branch
   (Loom 1.14+/Gradle 9/JDK 25), becomes the default. 26.x version files are already staged under
   `versions/26.*` (commented out of `settings.gradle.kts`; search "26.x"). Expect new overlay eras
   (26.x is newer than 1.21.11 — same walk-forward pattern).

## Known deferred issue (does NOT block anything shipping)

The common-node **test/JMH harness** (`src/test/java/profile/{McBootstrapTest,BlockReadBenchmark}`)
fails to compile when the active version is **≥1.21.11**: MC 1.21.11 moved `PalettedContainer.Strategy`
(nested) → top-level `net.minecraft.world.level.chunk.Strategy` and changed the ctor (3-arg→2-arg).
This is **dev-tooling only** — `chiseledBuild` never compiles the common node's tests, so all 45 JARs
ship green, and it does NOT affect `runClient`. The harness has always been effectively single-version
(compiled only for the active version, historically 1.21.4). Fix it per-era during the migration (or
via a `platform`-overlaid section-palette helper if Phase 1 needs it sooner).

## What changed this session (for context)

- Extended matrix 1.21.4 → 1.21.11; Loom 1.10→1.11→**1.13.469**; fabric-loader 0.16.13→**0.17.3**;
  removed unused `deps.architectury_api` from all version files.
- Overlays for real MC API changes: `overlays/1.21.6` FakeClientConnection (`send` arg type),
  `overlays/{1.20.1,1.21.11}` `platform.BlockLookup` (ResourceLocation→Identifier), version-agnostic
  `getServer()` fix in common src; **forge module now uses overlays** (`overlays-forge/{1.20.1,1.21.6,1.21.9}`
  ForgePlatformEvents — Forge EventBus 7 at 1.21.6, LevelTickEvent record-ification at 1.21.9).
- Runtime fix: reverted a 1.21.11 fabric-api module-subset workaround (was leaving "fabric-api
  missing" at dev-launch) — full fabric-api builds clean on Loom 1.13.
- Docs: `PRD.md` + `PORTABILITY-AUDIT.md` moved to `internal_docs/`; added `internal_docs/BUILD-STRATEGY.md`.

## Reference

- Build: `./gradlew chiseledBuild{Fabric,Neoforge,Forge}` (per-loader). Probe: `chiseledCompileCommon --continue`.
- Run a version: `"Set active project to <ver>"` → `:<loader>:<ver>:runClient`; `"Reset active project"`.
  Verify a jar isn't hollow: `unzip -l <jar> | grep com/orebit/mod` (real ≈ 40 classes).
- Matrix: `settings.gradle.kts`. Per-version deps: `versions/<ver>/gradle.properties`. Loom pin:
  `stonecutter.gradle.kts`. Overlays: `overlays/<era>/java` (common), `overlays-forge/<era>/java`.
- `javap` against the named MC jar under `~/.gradle/caches/fabric-loom/minecraftMaven/.../minecraft-merged-<ver>...jar`
  is the way to confirm an API change (used throughout this session).
