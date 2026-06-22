# HANDOFF — Orebit: Model C live & pushed; NEXT = prove the model BACKWARD

> **Temporary file.** Delete/overwrite at the end of the next session.
> Written end of session 7 (2026-06-22).

## State: split DONE, pushed, remote clean — branch model is live

- **`core`** @ `74fda1f` — version-portable trunk (common logic, `overlays/` chain,
  `versions/*/gradle.properties`, build-script logic). **No toolchain values; not buildable** (missing
  `era.properties` → Gradle fail-fast). Author ALL common/overlay/version-file changes here.
- **`main`** @ `a920af4` — newest era = `core` + the one `era.properties` commit (≤1.21.11 era:
  Loom 1.13.469 / Gradle 8.12.1 / JDK 21, MC 1.20.1→1.21.11). Buildable. **GitHub default.**
- **Pushed to the PERSONAL repo** `git@github-personal:stevendesu/orebit.git` (repo was renamed
  `orebit-1.21.4`→`orebit`; remote URL already updated). Remote heads: `core`, `main` (default),
  `gh-pages`. Old `master` deleted. `core`↔`origin/core`, `main`↔`origin/main`; `origin/HEAD`→`main`.
  NOTE: `gh` CLI is authed as the WORK account (`UBTCodeNinja`); `git push` is fine (personal SSH key
  `stevendesu`), but API ops (default branch, etc.) need personal `gh auth` or the web UI.
- Local working dir is still named `orebit-1.21.4/` (cosmetic; not renamed).

## How the era model works now (so the backward work respects it)

- Toolchain values live in root **`era.properties`** (era-owned; ABSENT on `core`): `loom.version`
  (injected via `settings.gradle.kts` pluginManagement `resolutionStrategy.eachPlugin.useVersion`),
  `mc.versions.{common,fabric,neoforge,forge}` (ascending; fed to `versions()`/`branch()` via
  `eraList`), `java.version` (reference only). Gradle wrapper version is era-owned in
  `gradle/wrapper/gradle-wrapper.properties`. Active marker = newest in-era (1.21.11). 1.21.4 is NOT
  special anywhere — see memory `version-1214-not-special`.
- **Authoring rules:** common/overlay/`versions/*`/build-logic → `core`, then propagate
  (`sh scripts/propagate.sh` = `git merge core` into every era branch, conflict-free). Toolchain
  values (`era.properties` + wrapper) → the era branch ONLY, never `core`.

## NEXT: prove the model BACKWARD (find the first older toolchain wall, cut the era)

Rationale: going backward leaves `main` (=newest) stable, vs. forward which redefines it. Full plan:
`internal_docs/BUILD-STRATEGY.md` §6 ("Add an OLDER era") + §8.

**The matrix is now era-owned, so DON'T add backward versions to `main`'s era.properties** (that would
pollute the ≤1.21.11 era). Probe on a **scratch branch off `core`** (or the prospective older-era
branch), not on `core` itself and not on `main`.

Suggested sequence for the next session:
1. **Probe how far back the CURRENT toolchain reaches.** Cut a scratch branch off `core`, give it a
   copy of `main`'s `era.properties`, and append older MC versions to `mc.versions.common` in
   descending batches: e.g. `1.19.4, 1.19.2, 1.18.2, 1.17.1, 1.16.5, 1.15.2, 1.14.4`. Run
   `./gradlew chiseledCompileCommon --continue`. This needs only the MC jar + mappings (pure common
   source), so it sidesteps per-loader/fabric-api gaps and pins exactly which version first fails.
   - Likely walls: Loom 1.13.469 / Gradle 8.12.1 can no longer resolve old MC mappings/intermediary;
     the **Fabric floor (~1.14** — no Fabric before it). The Java *level* is NOT a compile wall (JDK 21
     compiles older bytecode via `--release`), but old-MC **dev/runClient** may need an older JDK
     (1.18–1.20.4 → JDK 17; 1.17 → JDK 16; ≤1.16 → JDK 8) — relevant when building loader jars / running,
     not for the `chiseledCompileCommon` probe.
2. **Discover the boundary.** The last version that compiles extends the *current* era's lower bound;
   the first that fails marks where the *older* era begins (a new Loom/Gradle/JDK combo). Research the
   older era's toolchain (older Architectury Loom + matching Gradle that still build that MC range).
3. **Author the portable parts on `core`:** add `versions/<ver>/gradle.properties` (parchment, loader
   versions, mc_dep ranges) for each new backward version; any overlay-chain extension (re-baselining
   the oldest overlay era — see BUILD-STRATEGY §6 "Add an OLDER era"). Commit on `core`.
4. **Cut the older-era branch off `core`** (name it for the era, e.g. `mc-1.19`/`mc-1.16` per the wall):
   add ITS `era.properties` (older Loom/Gradle/Java + its own ascending matrix) and ITS own
   `gradle/wrapper/gradle-wrapper.properties`. This is the era's single toolchain commit.
5. **Verify end-to-end:** build the older era; `sh scripts/propagate.sh` (merge `core`) and confirm it's
   conflict-free; `chiseledCompileCommon` green on both eras (the pre-release gate). That proves the
   whole Model C loop (author-on-core → propagate → per-era build) backward.

## Known deferred issue (does NOT block shipping)

Active version is now **1.21.11**, so a bare `./gradlew test`/`jmh` on the active node hits the
`PalettedContainer.Strategy` test-harness break (1.21.11 moved it nested→top-level
`net.minecraft.world.level.chunk.Strategy`, ctor 3-arg→2-arg). Dev-tooling only — `chiseledBuild`
never compiles common tests, so all JARs ship green and `runClient` is unaffected. Fix per-era (or via
a `platform`-overlaid section-palette helper) if/when Phase 1 needs it.

## Reference

- Build: `./gradlew chiseledBuild{Fabric,Neoforge,Forge}`. Probe: `chiseledCompileCommon --continue`.
- Run a version: `"Set active project to <ver>"` → `:<loader>:<ver>:runClient`; `"Reset active project"`.
  Verify a jar isn't hollow: `unzip -l <jar> | grep com/orebit/mod` (real ≈ 50 classes).
- Era values: `era.properties` (+ `gradle/wrapper/gradle-wrapper.properties`). Wiring: `settings.gradle.kts`.
  Overlays: `overlays/<era>/java` (common), `overlays-forge/<era>/java`. Propagate: `sh scripts/propagate.sh`.
- Full plan: `internal_docs/BUILD-STRATEGY.md`. Memory: `multiversion-build-strategy`, `version-1214-not-special`,
  `loader-matrix`, `portability-findings`.
