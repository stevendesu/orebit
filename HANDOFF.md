# HANDOFF — Orebit: backward era DONE (1.17.1→1.21.11, Fabric+Forge); NEXT = forward 26.x era

> **Temporary file.** Delete/overwrite at the end of the next session.
> Written end of session 10 (2026-06-22). Supersedes the session-9 handoff.

## DONE this era (committed + pushed)

The `main` era (Loom 1.13.469 / Gradle 8.12.1) now spans **MC 1.17.1 → 1.21.11** for **common +
Fabric + Forge** (NeoForge 1.21+). All compile; runtime-verified in-game:
- **Fabric:** 1.17.1, 1.18.1, 1.19, 1.19.3, 1.20, 1.20.1 (+ remaining patches low-risk).
- **Forge:** 1.17.1 and 1.18.1 — including **1.18.1 installed on a real *recommended* Forge (39.1.0)**,
  proving build-against-latest → runs-on-recommended.

Key facts settled (also in memory `loader-matrix`, `commit-hygiene`, `overlay-rebaseline-procedure`):
- **Per-version Java toolchain**: `<1.20.5 → JDK 17`, `≥1.20.5 → JDK 21` (both Temurin installed).
  Loom honors it for compile but NOT runs → Forge run JVM pinned via `javaLauncher`.
- **Forge build pin = `latest`, NOT recommended**: Loom 1.13.469's remapper *corrupts* some older
  recommended MDKs (1.18.1 `39.1.0` → scrambled signatures, won't compile); `latest` remaps clean and
  the resulting jar still runs on the user's recommended Forge. The "Forge Beta" menu banner on
  no-recommended versions (1.18.0/1.19.1/1.20.0) is cosmetic and accepted.
- Forge-less: 1.20.5/1.21.2 (zero builds), **1.20.3** (betas need `bootstrap-dev:2.0.0`, unresolvable
  under Loom 1.13.469). 1.17.0 dropped (no fabric-api). 1.20.0 added.
- Fragile fixes landed: fabric-api transitive-loader exclude; `fabric.mod.json` depends `fabric`;
  Forge `mods.toml` loader range templated from the Forge major + dropped the bad `logoFile`; the bot's
  `FakeClientConnection` `fml:netversion="NONE"` + `"packet_handler"` handler name (old-Forge spawn).
- Backward toolchain wall = **1.13.2** (no Mojang mappings + Flattening); **≤1.16.5** deferred (partial
  WIP on local `scratch/backward-probe`).

Branches `core` + `main` are **pushed** (or being pushed this session). `scratch/backward-probe` is local.

## NEXT: the 26.x forward era — a NEW toolchain era (the whole reason Model C exists)

**Why a new era:** 26.x needs **Java 25** and a **newer Architectury Loom** (1.13.469 can't build 26.x
fabric-api), which needs **Gradle 9**. That can't coexist with legacy Forge's `≤1.13`/Gradle-8 needs in
one build (BUILD-STRATEGY §3) → it MUST be a separate era branch. Procedure: BUILD-STRATEGY §6
"Add a NEW (newer) era."

**Prereqs (do first):**
1. **Install Temurin 25** (not yet installed; we have 17 + 21 via winget — `winget install EclipseAdoptium.Temurin.25.JDK`).
2. **Research the toolchain combo**: the Architectury Loom version that builds MC 26.x fabric-api +
   NeoForge 26.x (likely Loom ≥1.14, which requires Gradle ≥9) and the matching Gradle wrapper version.

**Staged already (on `core`):** `versions/26.1`, `26.1.1`, `26.1.2`, `26.2` exist with deps
(fabric_api/neoforge_loader-beta/forge_loader). ⚠️ Their header comment says "Java 21" — **wrong, 26.x is
Java 25**; and `build.gradle.kts`'s toolchain logic is `>=1.20.5 ? 21 : 17` → would pick 21 for 26.x.
**Add a `>=26 → 25` branch to the toolchain logic (portable, on `core`).**

**Steps:**
1. On `core`: finalize 26.x portable content — fix the toolchain Java logic to add 25 for ≥26; add any
   new overlay eras for 1.21.11→26.x API divergence (the deobf/rename pass continues past 1.21.11;
   e.g. `ResourceLocation`→`Identifier` already at 1.21.11 — expect more). Probe with
   `chiseledCompileCommon` once a 26.x node is buildable.
2. `git branch mc-26 core`; on `mc-26` add ONE toolchain commit: `era.properties` (newer Loom version +
   the 26.x `mc.versions.*` matrix + `java.version=25`) and `gradle/wrapper/gradle-wrapper.properties`
   (Gradle 9). This is the era's single toolchain commit.
3. **Default-branch decision** (decide at execution, BUILD-STRATEGY §6): `main` = "newest era = GitHub
   default." Either (a) rename current `main`→`mc-1.21` and make `mc-26` the new `main`/default, or
   (b) keep `main` as the moving default and snapshot the current 1.21 era to `mc-1.21` first.
4. Verify: build `mc-26` (needs JDK 25 + the newer Loom); `sh scripts/propagate.sh` to confirm
   `git merge core` into both eras stays conflict-free; `chiseledCompileCommon` green on each era (the
   pre-release CI gate).

**Watch:** 26.x Forge (Forge 65.x — EventBus 7 already handled at `overlays-forge/1.21.6`, but 26.x may
diverge more); NeoForge 26.x is beta-only (consistent with policy). The newer Loom may also fix/break the
overlay composition — re-run the probes.

## Gotchas (carry forward)
- Loader nodes build from `chiseledSrc` → run **`Set active project to <ver>` before `runClient`** (and
  before building a single loader jar) after any src/overlay edit.
- **Commit hygiene** (memory `commit-hygiene`): accumulate runtime fixes in the working tree; commit at a
  tested-working point, not per micro-step.
- Single loader jar: `Set active project to <ver>` → `:<loader>:<ver>:buildAndCollect` → jar in
  `build/libs/<modver>/<loader>/`; verify non-hollow with `unzip -l <jar> | grep com/orebit/mod`.

## Reference
- `internal_docs/BUILD-STRATEGY.md` §3 (Loom-span constraint), §6 (add-newer-era procedure), §8.1–8.2.
- Memory: `loader-matrix`, `multiversion-build-strategy`, `overlay-rebaseline-procedure`, `commit-hygiene`.
