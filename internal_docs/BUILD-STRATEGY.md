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

## 5. Ratified decisions (session 6)

1. **Move to branch-per-version.** A git branch per MC version (naming TBD, e.g. `mc/1.20.1`).
   Each branch builds all its version's loader JARs with its own pinned Loom/Gradle/deps. `master`
   stays the latest/primary dev branch. (`git checkout 1.20.1` ≈ today's `Set active project`.)
2. **Keep Forge for every version with a Forge release** — branch-per-version makes it viable
   (per-branch toolchain), and Forge still ships through 26.x for die-hard users.
3. **No Architectury API.** Native glue, zero runtime deps (§4.1).
4. **Keep the `overlays/` mechanism** for genuine MC API drift within a branch (§4.2).
5. **Loader coverage:** Fabric every version; NeoForge every 1.21+ version with any release (beta
   where no stable exists); Forge every version with a Forge release (skips 1.20.3/1.20.5/1.21.2).

## 6. Open questions for the migration (to settle before executing)

- **Branch granularity:** one branch per MC version (Mouse Tweaks/JEI style) vs. per MC "era" (a
  few anchors with a small Stonecutter span each). Per-version is simplest and matches our goal of
  *every* version; per-era is less branch sprawl but reintroduces mini-span toolchain risk.
- **Shared-code propagation:** with N branches, how do common-code changes (BotManager, world model,
  AI) propagate? Options: forward-merge from an oldest base, cherry-pick, or a shared `common`
  submodule/branch. (JEI/Mouse Tweaks forward-port; this is the main ongoing cost of branching.)
- **Release/CI:** a workflow that checks out each branch, builds, and collects JARs into one
  release. (Replaces today's `chiseledBuild`.)
- **What happens to Stonecutter:** likely retire it once branched (each branch is single-version),
  OR keep a tiny Stonecutter span per branch for adjacent point releases. TBD.
- **Within-branch loader layout:** keep the current common + fabric/neoforge/forge module split
  (it's the idiomatic Architectury multiloader template).

## 7. Current state (pre-migration, on `master`)

`master` is a single Stonecutter+Architectury build spanning MC 1.20.1→1.21.11 on Architectury
Loom 1.13.469 / Gradle 8.12.1 — **45 JARs build green** (Fabric 18, NeoForge 12, Forge 15). 26.x is
staged (version files written) but blocked on Java 25 + the single-build Loom ceiling — i.e. 26.x is
the trigger to actually do the branch split. See `HANDOFF.md`.
