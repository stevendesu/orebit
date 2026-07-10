# Orebit — Multi-Version / Multi-Loader Build Strategy

> Internal doc (NOT the public `docs/` MkDocs site). Captures how the Minecraft modding
> ecosystem actually handles "every version × every loader," where Orebit diverges and why,
> the toolchain constraints we hit, and the ratified plan going forward.
> Written 2026-06-22 (session 6), after a research pass into Architectury, Stonecutter, Loom,
> and how popular wide-support mods are structured.

## TL;DR

- **Goal (unchanged):** produce shippable JARs for **every Minecraft version × every loader**
  (Fabric, Forge, NeoForge) that has a release. Single drag-and-drop JARs, **zero runtime deps**.
- **The mistake we were making:** spanning ~20 MC versions (1.20.1 → 26.x) — across a Forge
  event-system rewrite, a Java 21→25 bump, and Mojang's deobfuscation — from **one unified
  Gradle build with one Loom version**. Almost nobody does this; it forces an impossible single
  toolchain.
- **The fix:** **branch-per-version** (the ecosystem norm). Each branch pins its **own** Loom +
  Gradle + deps, so toolchain conflicts that are unsolvable in one build simply disappear.
- **Two deliberate divergences we KEEP:** (1) no Architectury *API* (native per-loader glue →
  zero runtime deps); (2) file-level `overlays/` instead of Stonecutter inline `//?` comments.

## 1. What the three tools actually do

There are **three** "Architectury" things — easy to conflate:
- **Architectury Loom** — the build tool (a Fabric Loom fork) that can emit Fabric/Forge/NeoForge
  jars. **We use this.**
- **architectury-plugin** — Gradle orchestration of the common-module → per-loader transform.
  **We use this.**
- **Architectury API** — the *runtime* library you `import dev.architectury.*` to write loader-
  agnostic code (events, registries, networking). **We deliberately DON'T use this** (see §4).

**Stonecutter** (KikuGie) abstracts *versions*. Its idiomatic mechanism is **inline preprocessor
comments in one source file** (`//? if >=1.21 {…}`), processed for the *active* version. It makes
a Gradle subproject per version×loader node; building any one configures all (inherent Gradle
multi-project behavior). The **"active version"** is the single version whose code is live in
`src/` so the IDE/compiler sees one coherent version — `Set active project to <v>` switches it.
Author's guidance: support **a few anchor versions** (latest + a couple LTS), **not** a contiguous
1.16→26 wall. Sources: <https://stonecutter.kikugie.dev/>, FAQ.

## 2. How the ecosystem actually does it (researched)

Popular wide-support mods **branch per Minecraft version** — they do **not** span the range in one
build:
- **Mouse Tweaks** (YaLTeR): git branches `minecraft-1.21.1`, `minecraft-1.20.1`, … `minecraft-1.2.5`.
  One branch ≈ one MC version; each branch carries its own Forge/NeoForge/Fabric build scripts.
  <https://github.com/YaLTeR/MouseTweaks/branches/all>
- **JEI** (mezz): branches `1.20.1`, `1.21.4`, `1.21.11`, `26.1`, `26.2`, … + a MultiLoader-style
  common/fabric/forge/neoforge split *within* a branch. <https://github.com/mezz/JustEnoughItems>
- **Jade** (Snownee): one branch per *(version × loader)* — `1.21.11-fabric`, `1.21.11-neoforge`, …
  <https://github.com/Snownee/Jade/branches/all>

**Common practice:** branch (or Stonecutter variant) **per MC version/era**; within a branch, a
shared *common* module + thin per-loader modules. Mouse Tweaks supports "so many versions"
*because* each is an isolated branch with its own toolchain — not one heroic unified build.

## 3. The Loom-version-span constraint (the crux)

What we hit this session, and why:
- It is **not** that newer Loom drops old Minecraft. It's that **legacy Forge** is fragile and
  being abandoned by tooling. Architectury's *API* stopped shipping a Forge build after **1.20.6**
  (Fabric+NeoForge only). NeoForge (forked from Forge after 1.20.1) is the de-facto loader for
  1.21+. Forge itself is still maintained (releases through 26.x) but ecosystem-marginal there.
  <https://docs.architectury.dev/api/migration/neoforge>, <https://neoforged.net/>
- **You cannot mix Loom plugin versions in one Gradle build** — a plugin id resolves to a single
  version on the shared build classpath. Different Loom versions require **separate Gradle builds**.
  <https://github.com/gradle/gradle/issues/29652>
- Empirically (our repo, Gradle 8.12.1): **Loom 1.13.469** is the only version that builds the
  whole 1.20.1→1.21.11 matrix at once — ≥1.13.3 to consume fabric-api for 1.21.11, new enough for
  NeoForge 1.21.10+'s dropped `data/server.lzma` format, old enough to keep legacy Forge working
  (Loom 1.14 broke Forge remap/compile; Loom ≥1.14 also needs Gradle 9.x).
- **26.x will break this single-Loom truce** (Java 25 + likely a newer Loom for its fabric-api).
  In one build, "26.x needs Loom ≥1.14 but legacy Forge needs ≤1.13" is **unsolvable**.

**Branch-per-version dissolves it:** the `1.21.11` branch can run Loom 1.14+/Gradle 9; the `1.20.1`
branch stays on Loom 1.13/Gradle 8.12 with legacy Forge. Separate builds → no shared classpath →
no conflict. This is exactly why the ecosystem branches.

## 4. Where Orebit deliberately diverges (and why we keep it)

1. **No Architectury API (native per-loader glue).** Rationale: **single drag-and-drop JAR, zero
   runtime deps.** Bundling/depending on a shared library (Architectury API) reintroduces the
   classic "two mods need different versions of the same lib" install-time incompatibility — which
   we refuse to inflict on users. **Cost:** we hand-write `FabricPlatformEvents` /
   `NeoForgePlatformEvents` / `ForgePlatformEvents` and absorb per-loader API churn ourselves (e.g.
   Forge's EventBus 7 migration at 1.21.6 → a forge overlay). With branch-per-version, that churn
   is **localized to the affected branch**, which makes the cost acceptable. (If we had used the
   API, that migration would likely have been absorbed internally — noted, but the dependency-
   freedom trade is the priority.)
2. **File-level `overlays/` instead of Stonecutter inline `//?`.** Rationale: preference for whole-
   file version adapters over inline preprocessor comments. Non-idiomatic but legitimate; it's
   extra machinery we maintain (`applyVersionOverlays` in buildSrc). KEEP — it's working and matches
   the "smart objects / file-level adapters" house style.

These are **choices, not mistakes.** The actual mistake was the unified-range build (§3), which
branch-per-version fixes without disturbing either choice above.

## 5. Ratified architecture — branch-per-ERA, "Model C" (session 6)

Branches map to **toolchain eras**, not individual MC versions. An era = a span of MC versions
that share one Loom/Gradle/Java toolchain. Within an era, Stonecutter + overlays cover the
individual versions (single source, many versions). Branches exist only where the toolchain is
*mutually incompatible* (Java/Loom/Gradle walls) — because that's the only thing that can't share
one Gradle build.

**The branches:**
- **`core`** — the common trunk. Holds **everything version-portable**: common logic, the full
  overlay chain, per-version `versions/<ver>/gradle.properties`, and the build-script *logic*
  (`build.gradle.kts`, `settings.gradle.kts`, `stonecutter.gradle.kts`, loader `build.gradle.kts`).
  **`core` holds NO toolchain version values and is not directly buildable.** It is where all
  common/overlay work is authored.
- **Era branches** — `main` (the newest era, GitHub default = "builds latest"), plus `mc-1.20`,
  `mc-1.16`, `mc-1.12`, … going back. Each = `core` **+ one small commit** holding that era's
  toolchain values. Each is independently buildable.

**Why this is the dedup answer:** a common bug is fixed **once on `core`**; `git merge core` into
each era branch propagates it **conflict-free** — because `core` contains no toolchain values,
the merge can never drag a toolchain change. Within an era, Stonecutter means that one fix already
covers all that era's MC versions. So a fix reaches every build via **one edit + a handful of clean
merges (one per era)** — never N hand-applied copies. (`git merge` carries whole commits; isolating
toolchain values onto era branches, off `core`, is precisely what keeps the merge path clean.)

**Toolchain isolation (the load-bearing rule):** the per-era values — Architectury Loom version,
Gradle (wrapper) version, Java version, and the Stonecutter version-matrix — live **only on era
branches**. `core` must **never edit a toolchain file**; era branches own them. Implementation
target (settle at first split): collapse these into a single, well-known era-owned file (e.g.
`era.properties` read by the build scripts) plus `gradle/wrapper/gradle-wrapper.properties` (the
Gradle version, which the wrapper reads at bootstrap and so is inherently era-owned). The crisper
that boundary, the harder it is to violate the rule. Floor (if not yet externalized): pure
discipline — toolchain values are edited on era branches, never on `core`.

**Authoring rules (also encoded in CLAUDE.md):**
- Common logic, overlays, `versions/*/gradle.properties` → **`core`** (then merge into era branches).
- Toolchain values (Loom/Gradle/Java/matrix) → **the era branch only**, never `core`.
- Build/dev/`runClient` happens **on an era branch** (or a worktree), never on `core` (it can't build).

**Kept from before:** no Architectury API (native glue, zero runtime deps — §4.1); the `overlays/`
mechanism (§4.2); the common + fabric/neoforge/forge module layout (idiomatic Architectury
multiloader); Forge for every version with a Forge release; NeoForge for every 1.21+ version with
any release (beta where no stable exists); Fabric everywhere.

## 6. Procedures (no "revert commits" anywhere)

**Add a NEW (newer) era — e.g. 26.x:**
1. Add the era's portable content to `core`: new overlays + `versions/26.*/gradle.properties`.
2. `git branch mc-26 core`; on `mc-26` add **one** toolchain commit (Loom 1.14+/Gradle 9/JDK 25 +
   the 26.x matrix).
3. Make `mc-26` the GitHub **default** (it's the new "latest"). The previous newest era branch just
   stays as a named branch (e.g. rename old `main`→`mc-1.21` *before* repointing default, or keep
   `main` as the moving default and snapshot the old era first — decide at execution).

**Add an OLDER era — e.g. down to a 1.16 toolchain:**
1. On `core`: extend the overlay chain backward (this means **re-baselining** — the oldest era's
   overlay dir is the baseline holding *all* files; adding 1.16 splits today's `1.20.1` baseline into
   a `1.16` baseline + a `1.20.1` override era, the same "walk-back" already done 1.21.4→1.20.1) and
   add the older `versions/*/gradle.properties`.
2. `git branch mc-1.16 core`; add its toolchain commit (older Loom/Gradle, Java 8/16/17, its matrix).
3. `core` stays the default? No — default stays `main` (newest). The older branch is just added.

**Propagate a common fix:** commit on `core` → `git merge core` into each era branch. Conflict-free
for common changes (toolchain files untouched). A `propagate` script automates the fan-out.

**Guard against accidental new-API dependence (§4.1 / Q1):** before release, **compile common on
every era** (`chiseledCompileCommon --continue` on each era branch, ideally in CI). That is the tool
that has caught every API divergence in this project; run it as a standing pre-release gate so a
newest-era-authored call that doesn't exist on an older era fails loudly, not silently at runtime.

## 7. Tooling, scripts, CI

- **Convenience scripts** (hide the branch-juggling): `dev <version>` (resolve era → checkout era
  branch → `Set active project` → `runClient`); `build-all` (build every era, collect JARs);
  `reset` (return to `main`, reset active). Scripts MUST guard a clean working tree before any
  checkout and restore state on exit.
- **git worktrees** for `build-all`/CI: check each era branch out into its own directory and build
  independently (even in parallel) without disturbing the primary checkout. Each era needs its own
  JDK (e.g. JDK 25 for `mc-26`), so CI/scripts set `JAVA_HOME` per era.
- **Release**: tag the commit that produced each published build; aggregate all eras' JARs into one
  release. (Replaces the single `chiseledBuild`.)

## 8. Current state (settled)

**The `core` split is DONE.** The former single `master` (Stonecutter + Architectury, MC 1.20.1→1.21.11
on Architectury Loom 1.13.469 / Gradle 8.12.1 — 45 JARs green) is now the toolless **`core`** trunk plus
two era branches:

- **`mc-1.21`** — `core` + `era.properties` for the 1.17.1→1.21.11 Architectury era (Fabric + Forge +
  NeoForge ≥1.21).
- **`main`** — `core` + `era.properties` for the 26.x Fabric-only era (pure Fabric Loom 1.17.12 /
  Gradle 9.5 / JDK 25); the GitHub default. **Shipped** — the 26.2 jar builds and is runtime-verified.

**Toolchain externalization (settled at the split):** the per-era values live in a root **`era.properties`**
(era-owned, absent on `core`): `loom.version` (injected via `settings.gradle.kts`'s pluginManagement
`resolutionStrategy`), `mc.versions.{common,fabric,neoforge,forge}` (the Stonecutter matrix, sorted
ascending), `java.version` (reference only — the real source/target LEVEL is derived per-MC-version in the
build scripts, which stay on `core`). The Gradle wrapper version and the Stonecutter active marker are
era-owned by nature, so `core` merges never conflict on them. **1.21.4 is no longer special** anywhere.

**Version-floor findings (proved by `chiseledCompileCommon` probes):**

- **Compile floor = the Mojang-mappings floor, 1.14.4.** Loom 1.13.469 + official Mojang maps resolve and
  reach `compileJava` for every version 1.14.4→1.21.11 (the Java *level* is not a compile wall — JDK 21
  emits older bytecode via `--release`; only old-MC *runClient* needs an older run JDK).
- **The wall is 1.13.2 and below** — `officialMojangMappings()` has no maps before 1.14.4, compounded by
  the 1.13 Flattening (block id+metadata, not BlockState/PalettedContainer — PRD §9 "second port"). So
  pre-1.14 is a separate era needing a different mappings source (Yarn/MCP) *and* a block-layer port —
  deferred (the 1.12.2 stretch).
- **Shipped floor = 1.17.1**, runtime-verified in-game (Fabric 1.17.1 / 1.18.1 / 1.19 / 1.19.3 / 1.20 /
  1.20.1; Forge 1.17.1; the rest of the Forge matrix is a rolling runtime pass). **Java is per-version,
  NOT per-era** (`<1.20.5 → JDK 17`, `≥1.20.5 → JDK 21`, `≥26 → JDK 25`); a different run JDK is a
  per-version toolchain concern inside one era, not grounds for a new era branch.
- **≤1.16.5 deferred** — a deep 1.14–1.16 entity/block API shim cascade (rotation setters → methods at
  1.17, `Entity.discard`, `Block.defaultDestroyTime`, dripstone/cave-vine classes, `Material`-based tool
  detection) plus a log4j logging shim (1.16.5 predates slf4j); common-compile-only value. Partial
  prototype on the local `scratch/backward-probe` branch.

This validated the Model C loop in both directions: author on `core` → `git merge core` into each era
branch → per-era build/compile green, with toolchain values never touched on `core`.
