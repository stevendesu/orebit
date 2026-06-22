# HANDOFF — Orebit: `core` split DONE (Model C step 1); prove-backward is next

> **Temporary file.** Delete/overwrite at the end of the next session.
> Written end of session 7 (2026-06-22).

## State: stable & committed — branch model is now LIVE

The repo has been split into the Model C branch structure (was a single `master`):

- **`core`** @ `1b46068` — version-portable trunk: common logic, the `overlays/` chain,
  `versions/*/gradle.properties`, and build-script *logic*. Holds **NO toolchain values** and is
  **not buildable** (a missing `era.properties` makes Gradle fail fast with a clear message). All
  common/overlay/version-file work is authored HERE.
- **`main`** @ `5c22fc9` — newest era = `core` + **one** commit adding `era.properties` (the ≤1.21.11
  era: Loom 1.13.469 / Gradle 8.12.1 / JDK 21, MC 1.20.1→1.21.11). Buildable. Intended GitHub default.
  `runClient`/dev/builds happen here (or in a worktree).
- Stale merged branches `feature/forge-1.20.1` and `migrate/architectury-stonecutter` were deleted.

**NOT pushed — all local (user's call).** `core` still shows `[origin/master: ahead 18]` (it inherited
`master`'s upstream on rename); `main` has no upstream yet. When pushing: push both branches, set
`main` as the GitHub default, and fix the upstreams (`git branch -u origin/core core`, etc.).

**Build verified after the split:** `chiseledBuildFabric` on `main` → 18 real Fabric jars (50
`com/orebit/mod` classes each, spot-checked 1.20.1 + 1.21.11). The matrix/values are unchanged from
session 6 — only their *location* moved — so the 45-JAR matrix is still expected green. `core` was
confirmed inert (fresh checkout has no `era.properties`; Gradle fails fast).

## How toolchain values are externalized (the mechanism settled this session)

Root-level **`era.properties`** (era-owned; absent on `core`) holds:
- `loom.version=1.13.469` — injected by `settings.gradle.kts` `pluginManagement { resolutionStrategy
  { eachPlugin { useVersion(...) } } }` (reads the file inline via `settingsDir` — the pluginManagement
  preamble can't see script-body helpers). `stonecutter.gradle.kts` now declares
  `id("dev.architectury.loom") apply false` with no literal.
- `mc.versions.{common,fabric,neoforge,forge}` — comma-separated, **sorted ascending**; fed to
  `versions()`/`branch()` via the `eraList(key)` helper. Loader-coverage policy comments stay on `core`.
- `java.version=21` — recorded for reference; the per-version Java LEVEL is still derived from MC
  version in the build scripts (portable).
- Gradle wrapper version stays in `gradle/wrapper/gradle-wrapper.properties` (era-owned by nature).
- Stonecutter active marker is now `1.21.11` (newest in-era). **1.21.4 is no longer special anywhere.**

## Authoring rules (now enforceable — CLAUDE.md + BUILD-STRATEGY.md encode them)

- Common logic / overlays / `versions/*/gradle.properties` / build-script logic → **`core`**, then
  propagate: commit on `core`, then `sh scripts/propagate.sh` (merges `core` into every era branch;
  conflict-free because `core` has no toolchain values). With one era today this is just `core`→`main`.
- Toolchain values (Loom/Gradle/Java/matrix = `era.properties` + the wrapper) → **the era branch only**,
  never `core`.
- Pre-release gate: `chiseledCompileCommon` on every era branch.

## Next steps (in order)

1. **Prove the model backward FIRST** (going forward redefines `main`; backward leaves it stable).
   NOTE the matrix now lives in `era.properties` (era-owned), so the backward probe happens on an era
   branch, not `core`: cut a scratch/older-era branch off `core`, add a few ≤1.20 versions to ITS
   `mc.versions.common`, and run `chiseledCompileCommon` there to find the first backward toolchain
   wall (likely a Java drop ~1.17/1.16 or the Fabric floor ~1.14). Then finalize that older-era branch
   with its own `era.properties` (older Loom/Gradle/Java + its matrix) and its own
   `gradle-wrapper.properties`; verify build + the `propagate` (merge `core`) flow.
2. **Forward to 26.x** once **Temurin JDK 25** is installed: new era branch (Loom 1.14+/Gradle 9/JDK 25),
   becomes the default. 26.x version files are staged under `versions/26.*`; add them to that era's
   `era.properties` (not to `core`/`main`). Expect new overlay eras.

## Known deferred issue (does NOT block anything shipping)

The common-node **test/JMH harness** (`src/test/java/profile/{McBootstrapTest,BlockReadBenchmark}`)
fails to compile when the active version is **≥1.21.11**: MC 1.21.11 moved `PalettedContainer.Strategy`
(nested) → top-level `net.minecraft.world.level.chunk.Strategy` and changed the ctor (3-arg→2-arg).
**Dev-tooling only** — `chiseledBuild` never compiles the common node's tests, so all 45 JARs ship
green and `runClient` is unaffected. NOTE: the active version is now **1.21.11**, so a bare
`./gradlew test`/`jmh` on the active node will hit this until fixed (overlay the section-palette helper,
or fix per-era during the migration).

## Reference

- Build: `./gradlew chiseledBuild{Fabric,Neoforge,Forge}` (per-loader). Probe: `chiseledCompileCommon --continue`.
- Run a version: `"Set active project to <ver>"` → `:<loader>:<ver>:runClient`; `"Reset active project"`.
  Verify a jar isn't hollow: `unzip -l <jar> | grep com/orebit/mod` (real ≈ 50 classes).
- Era toolchain values: `era.properties` (+ `gradle/wrapper/gradle-wrapper.properties`). Matrix/Loom
  wiring: `settings.gradle.kts`. Overlays: `overlays/<era>/java` (common), `overlays-forge/<era>/java`.
- Propagate a `core` fix to eras: `sh scripts/propagate.sh`.
- Full plan + rationale: `internal_docs/BUILD-STRATEGY.md`. Memory: `multiversion-build-strategy`,
  `version-1214-not-special`.
