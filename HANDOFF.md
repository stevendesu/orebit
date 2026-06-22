# HANDOFF — Orebit version ladder extended to 1.21.11 (Goal 2 in progress)

> **Temporary file.** Delete/overwrite at the end of the next session.
> Written end of session 6 (2026-06-22). Milestone: the build matrix now spans
> **MC 1.20.1 → 1.21.11** across Fabric + NeoForge + Forge. 26.x is staged, gated on Java 25.

## State — the matrix BUILDS clean (JDK 21, Loom 1.13.469)

`./gradlew chiseledBuild{Fabric,Neoforge,Forge}` → **45 primary jars**, zero failures:
- **Fabric**: 1.20.1–1.21.11 — all 18 versions.
- **NeoForge**: 1.21, 1.21.1, **1.21.2**, 1.21.3, 1.21.4, 1.21.5–1.21.11 — 12 (1.21.2 back-filled this session).
- **Forge**: 1.20.1, 1.20.2, 1.20.4, 1.20.6, 1.21, 1.21.1, 1.21.3, 1.21.4, 1.21.5–1.21.11 — 15
  (Forge still skips 1.20.3/1.20.5/1.21.2 — no Forge release exists for those).

Active version reset to **1.21.4**. Build heap 4G. These are **build-verified only** — runtime
testing (the user's pass) is still pending for the new versions.

### Loader coverage policy (decided this session)
- **Fabric** every version. **NeoForge** every 1.21+ version with ANY release — uses the latest
  **stable**, or the latest **beta** when NeoForge never promoted a stable (the beta is then the
  de-facto/only NeoForge for that MC: 1.21.2, 1.21.6/.7/.9). **Forge** every version with a Forge
  release. (Earlier belief that "we never use beta NeoForge" was wrong — we now do, deliberately.)

## The toolchain saga (the session's hard part)

Each new MC version exposed a different tooling wall. Net result: **Architectury Loom 1.10 →
1.13.469** (pinned in `stonecutter.gradle.kts`). Why 1.13.469 specifically:
- **≥1.13.3** is required to consume **fabric-api for 1.21.11** (remapped with Fabric Loom 1.13.3;
  Loom refuses artifacts built by a *newer* Loom).
- New enough to provide **NeoForge 1.21.10+**, which **dropped the `data/server.lzma`** patch
  format that pre-1.13 Loom can't unpack.
- Still has **working legacy-Forge** support: **Loom 1.14 broke Forge** (remap "Unfixable
  conflicts" for MC ≥1.20.6; compile failures ≥1.21.6), and **Loom 1.17 needs a newer Gradle**
  than our 8.12.1 (`Configuration.extendsFrom(Provider[])`). 1.13.469 is the single sweet spot.
- A single Gradle plugin version applies to the whole build, so per-loader Loom versions are not
  possible — one version must serve all three loaders.

## MC/loader API changes handled (verified via `javap` on the named jars)

Common source (validated by `chiseledCompileCommon`, which compiles common against every MC):
- **1.21.6** — `Connection.send` 2nd arg `PacketSendListener` → `io.netty.channel.ChannelFutureListener`.
  New overlay `overlays/1.21.6/.../FakeClientConnection.java`.
- **1.21.9** — `Entity.getServer()` **removed**. Fixed version-agnostically in common src: get the
  server via `((ServerLevel) x.level()).getServer()` (BotManager, OrebitCommon).
- **1.21.11** — `net.minecraft.resources.ResourceLocation` **renamed to `Identifier`** (Mojang
  deobfuscation). Hidden behind a new `platform.BlockLookup` shim: baseline
  `overlays/1.20.1` (ResourceLocation) + `overlays/1.21.11` (Identifier); `RegionBlockIndex`
  now calls `BlockLookup.byId(...)` and names neither type.

Fabric loader (`fabric/build.gradle.kts`):
- **1.21.11+** the full `fabric-api` bundle trips a Loom source-namespace assertion on
  `fabric-content-registries-v0` (a module we don't use). From 1.21.11 we depend on only the two
  modules the glue needs (`fabric-networking-api-v1`, `fabric-lifecycle-events-v1`) via
  `fabricApi.module(...)`. Older versions keep the full bundle.

Forge loader — **the forge module now uses the overlay mechanism** (new this session):
`forge/build.gradle.kts` calls `applyVersionOverlays(minecraft, rootProject.file("overlays-forge"))`,
and `ForgePlatformEvents` moved out of `forge/src` into eras (OrebitForge stays in src):
- `overlays-forge/1.20.1` — legacy `MinecraftForge.EVENT_BUS.addListener` + phase-based
  `TickEvent.LevelTickEvent` (Forge MC 1.20.1–1.21.5).
- **1.21.6** — Forge migrated to **EventBus 7**: `MinecraftForge.EVENT_BUS` became an
  `EventBusMigrationHelper` (no `addListener`); register per-event via
  `SomeEvent.BUS.addListener(Consumer)`. Tick end is `TickEvent.LevelTickEvent.Post` (no phase
  check). Level read via the public `event.level` field.
- **1.21.9** — `TickEvent.LevelTickEvent` became an interface/record: `event.level` field →
  `event.level()` accessor. (Only that one line differs from the 1.21.6 flavor.)

## Other changes
- **`deps.architectury_api` removed** from all version files — the Architectury *API* is unused
  (loader glue is native events); only the Architectury Loom *plugin* is needed. The
  `if (!architecturyVer.isNullOrBlank())` guards in the build files are now permanently dead but
  harmless (left in place).

## 26.x — staged, BLOCKED on Java 25 (next step)
`versions/26.1`, `26.1.1`, `26.1.2`, `26.2` gradle.properties exist (fabric_api / neoforge_loader
[betas for 26.1/26.1.1/26.2, stable 26.1.2] / forge_loader filled in). They are **commented out of
`settings.gradle.kts`** (search "26.x"). MC 26.x **requires Java 25**; only JDK 21 is installed —
config aborts globally with "Minecraft 26.1 requires Java 25 but Gradle is using 21".
- **User is installing Temurin 25.** Once present: point the Gradle daemon at JDK 25 (set
  `org.gradle.java.home` or `JAVA_HOME`; a JDK-25 daemon still builds the 1.21.x targets since
  25 ≥ 21), re-add the four 26.x versions to all three branch lists + the root common list, then
  build. **Expect further toolchain bumps** — 26.x is newer than 1.21.11, so its fabric-api may
  need Loom >1.13.469 (→ which needs Gradle >8.12.1), and its NeoForge/Forge may have new API
  drift needing more overlay eras (same pattern as 1.21.6–1.21.11). The walk-forward
  (`chiseledCompileCommon`) + `javap` loop is the tool.

## Next steps (pick one)
1. **26.x** once JDK 25 is in (above). The likely first wall is Loom-vs-Gradle for 26.x fabric-api.
2. **Runtime-test the new 1.21.5–1.21.11 jars** (user's manual pass) — catch issues compile can't.
3. **Phase 1: world-model pipeline** — short-index NavBlock, fix NavSectionBuilder, wire ChunkNavLoader.
4. **Docs/CLAUDE.md** still say Fabric/1.21.4-only — refresh.

## Reference
- Build all: `./gradlew chiseledBuild{Fabric,Neoforge,Forge}` (per-loader; combined `chiseledBuild`
  is heavier). Cheap common probe: `./gradlew chiseledCompileCommon --continue`.
- Run a version: `./gradlew "Set active project to <ver>"` then `:<loader>:<ver>:runClient`;
  always `"Reset active project"` (→1.21.4) before committing.
  **Caution:** direct `:<loader>:<ver>:buildAndCollect` can report success with a **NO-SOURCE**
  (hollow, no compiled glue) jar — Stonecutter only populates `chiseledSrc` via
  `setupChiseledBuild`, which runs in `chiseledBuild<Loader>`. Trust the chiseled tasks; verify a
  jar with `unzip -l <jar> | grep com/orebit/mod`.
- Matrix: `settings.gradle.kts`. Per-version deps: `versions/<ver>/gradle.properties`.
- Overlays: common `overlays/<era>/java`; forge `overlays-forge/<era>/java`; both composed by
  `applyVersionOverlays` (buildSrc). Loom pin: `stonecutter.gradle.kts`.
