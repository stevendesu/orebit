# HANDOFF — Orebit multi-version/loader work

> **Temporary file.** Delete (or overwrite) this at the end of the next session.
> Written 2026-06-21.

## Where we are

- **Phase 0 is COMPLETE and merged to `master`** (merge commit `a129dec`): foundation
  hygiene + full Yarn→Mojmap migration + Architectury (common/fabric/neoforge) +
  Stonecutter-native multi-version wrap. Fabric & NeoForge both build *and launch*
  (user-confirmed) on MC 1.21.4. `master` is green; don't disturb it casually.
- **In progress on branch `feature/forge-1.20.1`** (tip `3a8578e`): adding a 2nd MC
  version (1.20.1) + a Forge loader branch, as a portability stress-test.
  - ✅ `RegionBlockIndex` now resolves blocks by registry id, skipping absent ones
    (commit `d370916`) — handles version-divergent blocks (e.g. `pale_oak_log`).
  - ✅ Stonecutter matrix: `versions("1.21.4","1.20.1")`; fabric/neoforge pinned to
    1.21.4, `forge` branch pinned to 1.20.1. Per-version deps in
    `versions/<mcver>/gradle.properties`; Java 17-vs-21 chosen via
    `stonecutter.eval(minecraft, ">=1.20.5")`.
  - ✅ Forge adapter (`forge/…/OrebitForge` + `ForgePlatformEvents` on the
    `net.minecraftforge` event API), `mods.toml`, `pack.mcmeta`.
  - ✅ `RegionPool` pattern-switch → `instanceof` chain (Java 17 compatible).
  - ✅ **1.21.4 fabric+neoforge still green** (verified `chiseledBuildFabric`).
  - ❌ **Forge 1.20.1 does NOT yet build** — one remaining divergence (below).

## The ONE remaining divergence for 1.20.1

The 1.20.1 common compile fails *only* on the **1.20.2 login refactor**: `ClientInformation`
and `CommonListenerCookie` don't exist pre-1.20.2. Affected files (all in `src/main/java/com/orebit/mod/`):
`FakePlayerEntity`, `AllyBotEntity`, `BotManager`, `FakeNetworkHandler`.

1.20.1 fake-player API (vs current 1.21.4 code):
- `ServerPlayer(MinecraftServer, ServerLevel, GameProfile)` — **3-arg** (no `ClientInformation`).
- `ServerGamePacketListenerImpl(MinecraftServer, Connection, ServerPlayer)` — **3-arg** (no `CommonListenerCookie`).
- No `ClientInformation` type; no `ServerPlayer.clientInformation()` getter.

**Nothing else diverged** — no block-name changes (`BambooStalkBlock`/`SHORT_GRASS` are stable
in Mojmap back to 1.20.1), no method renames, nothing in worldmodel/pathing. This strongly
validates `PORTABILITY-AUDIT.md`: the only version-volatile surface is the fake-player stack.

### >>> USER DIRECTIVE on HOW to fix it (important — changes the approach)

The user **prefers dynamic dispatch / strategy pattern over inline version conditionals.**
Do NOT fill the 4 files with Stonecutter `//?` comments / multiple constructors (a "spaghetti
mess"). Preferred shape: a common interface/abstract for the bot player, with simple
version-specific implementations (e.g. `Pre1_20_2Player` / `Post1_20_2Player`) instantiated via
a factory; the rest of the code stays version-agnostic.

**Design constraint to resolve first:** Stonecutter compiles ONE MC version per source set, and a
`Post1_20_2Player` that names `ClientInformation` will NOT compile on 1.20.1 — so two
version-incompatible classes can't both sit in `common` for a single-version compile. Options to
work out next session: (a) put the version-divergent player/handler construction in a tiny
factory that is the *only* place with a Stonecutter `//?` seam (minimal, behind a clean
interface); (b) investigate whether Stonecutter supports per-version source files/overrides so the
two classes live in version-scoped source; (c) move the fake-player entity into per-branch source
(fabric+neoforge share post-1.20.2; forge has pre-1.20.2) — but that duplicates across
fabric/neoforge. Lean toward (a) or (b): keep the seam tiny, keep the interface clean.

If `//?` is unavoidable at the seam, CONFIRM the exact Stonecutter/Stitcher comment syntax first
(docs fetches came back garbled this session) — wrong syntax breaks BOTH versions. Check the
Stonecutter IntelliJ "Stonecutter Dev" plugin docs or a real mod's source for a verified
`//? if … { } else { }` example.

After the fix: `./gradlew chiseledBuild` must be green on **both** 1.20.1 and 1.21.4; then the
user runs `:forge:1.20.1:runClient` (interactive) to confirm the bot spawns+follows on Forge.

## Then: version walk-back test plan (user's strategy)

We know *what* diverged 1.21.4↔1.20.1 but not *when* (1.20.2? .3? .5? 1.21.0?).
1. Once 1.20.1 works, do a **quick pass adding every version between 1.20.1 and 1.21.4** to pin
   down exactly which version introduced each divergence.
2. **Going forward, when targeting a NEW version, walk back ONE version at a time** so any break
   is attributed to a specific version. For the upcoming 26.2 target, the ladder is:
   1.21.5 (Spring to Life) → 1.21.6 (Chase the Skies) → 1.21.7–1.21.9 (Copper age) →
   1.21.10–1.21.11 (Mounts of Mayhem) → 26.1 (Tiny Takeover) → 26.2 (Chaos Cubed).

## Then: document per-version divergences (user's idea)

Maintain a reference of what changed per version + the code pointers/branching seams. The user
suggests a **new internal docs folder** (e.g. `internal_docs/`) — NOT `docs/` (that's the public
MkDocs site). Consider **moving `PRD.md` (and `PORTABILITY-AUDIT.md`) into `internal_docs/`** at
the same time. (Update CLAUDE.md "Where to look" paths if you move them.)

## Reference

- **Toolchain:** Stonecutter 0.6, architectury-loom 1.10.455, architectury-plugin 3.4.164,
  shadow 8.1.1, Gradle 8.12.1. MC 1.21.4 (Java 21, Mojmap+Parchment 2025.03.23,
  fabric-api 0.119.2+1.21.4, neoforge 21.4.157, architectury-api 15.0.2). MC 1.20.1 (Java 17,
  Parchment 2023.09.03, forge 47.3.0, architectury-api 9.2.14).
- **Node layout:** `:1.21.4` & `:1.20.1` (common), `:fabric:1.21.4`, `:neoforge:1.21.4`,
  `:forge:1.20.1`. Common source = root `src/`; loader source = `<loader>/src/`.
- **Stonecutter gotchas learned this session:** (1) build non-active versions via
  `chiseledBuild`/`chiseledBuild<Loader>` (direct `:node:build` is NO-SOURCE — only the active
  version's source is exposed). (2) branch scripts read version-specific deps via
  `common.mod.dep(...)` (the common sibling carries `versions/<ver>` props), root-level deps via
  `mod.dep(...)`. (3) every `deps.*` key referenced at config time must exist for the active
  version (we keep them per-version, read via `common.mod.dep`). (4) `loom.platform=<loader>` must
  be in each branch's `gradle.properties`.
- **Key commands:** build all → `./gradlew chiseledBuild`; per loader →
  `./gradlew chiseledBuildFabric|Neoforge|Forge`; run → `./gradlew :fabric:1.21.4:runClient`,
  `:neoforge:1.21.4:runClient`, `:forge:1.20.1:runClient`; benchmarks →
  `./gradlew :1.21.4:jmh [-Pbench=X]`. Set active version via the Stonecutter "Set active" task.
- **Deferred (Phase 1):** access-widener for `NavSectionBuilder` palette access (still reflection;
  JMH benchmark consumes it). Plan file: `~/.claude/plans/cheeky-squishing-summit.md`.
  Audit: `PORTABILITY-AUDIT.md`. Status memory: project-status.

## Cleanup
Delete this `HANDOFF.md` (or overwrite with the next handoff) before ending the next session.
