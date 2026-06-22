# HANDOFF — Orebit multi-loader/version matrix (runtime-validated)

> **Temporary file.** Delete/overwrite at the end of the next session.
> Written end of session 5 (2026-06-21). Big milestone: the bot spawns & renders across
> the whole matrix; spawn is now done the vanilla way.

## State

**23-target matrix builds, and the bot RUNS (spawns + renders + follows) on every
dev-launchable target:**
- **Fabric**: all 11 versions (1.20.1 → 1.21.4) — user-confirmed in-game.
- **NeoForge**: 1.21, 1.21.1, 1.21.3, 1.21.4 — user-confirmed in-game.
- **Forge**: 1.20.1, 1.20.2 — user-confirmed in-game. **1.20.4 / 1.20.6 / 1.21 / 1.21.1 /
  1.21.3 / 1.21.4 build fine but CANNOT dev-launch** (see Forge-dev wall below).

Active version is reset to **1.21.4**. Build heap is **4G** (`gradle.properties`) — the
combined `chiseledBuild` OOMs at 2G.

### Scope (decided this session)
- **NeoForge floor = 1.21** (4 targets) — older NeoForge is rare and added metadata/tick-event
  complexity; deleted.
- **Transient 1.20.3 / 1.20.5 / 1.21.2 = Fabric-only** (no stable Forge, beta-only NeoForge).
- **Forge = every version with a Forge release**: 1.20.1, 1.20.2, 1.20.4, 1.20.6, 1.21,
  1.21.1, 1.21.3, 1.21.4 (Forge skipped 1.20.3/1.20.5/1.21.2).

## The big architecture change this session: vanilla bot spawn

The bot is now spawned via **`PlayerList.placeNewPlayer`** (the real player-join path), not the
old hand-rolled `broadcastAll(player-info) + addFreshEntity`. This fixed BOTH the
"add player prior to sending player info" render race AND the per-version construction NPEs.

- **`FakeNetworkHandler` DELETED.** `placeNewPlayer` builds the real
  `ServerGamePacketListenerImpl`; we no longer construct it.
- **`FakeClientConnection`** is now a socketless `Connection`: a dummy `EmbeddedChannel`
  (MC touches `connection.channel` in `tick()`/`isConnected()`) + no-op `send` + no-op of the
  protocol-setup method. It's a **version overlay**: `setListener` no-op (≤1.20.4) vs
  `setupInboundProtocol` no-op (≥1.20.5). (`player.connection = this` is set inside the
  listener ctor *before* the protocol call, so no-op'ing it is safe.)
- **`platform.BotSpawn`** (overlay) does `placeNewPlayer` + the per-version cookie:
  no cookie (1.20.1) / `createInitial(profile)` (1.20.2–1.20.4) / `createInitial(profile,false)`
  (1.20.5+).
- **CRITICAL guard** (`OrebitCommon`): `onPlayerJoin`/`onPlayerDisconnect` early-return for
  `FakePlayerEntity`. `placeNewPlayer` makes the bot a real PlayerList member, so the join
  event fires *for the bot* → without the guard it spawns bots infinitely (OOM). Bot removal
  uses `PlayerList.remove(bot)` (vanilla counterpart; `kill`+`discard` would leave a ghost).
- `FakePlayerEntity` overlays keep only ctor + `removeFromWorld` (kill); the connection
  assignment was removed. `removeFromWorld` is now effectively dead code (removal goes through
  `PlayerList.remove`) — fine to delete later.

### Other notable changes
- **World-model pipeline DECOUPLED from runtime.** `OrebitCommon.init` no longer touches
  `NavBlock` or registers `ChunkNavLoader`. NavBlock's byte index overflowed on non-1.21.4
  versions (`Too many blocks registered for mode: 3`) and the pipeline is unwired/discards
  output anyway. **Re-engage in Phase 1** with the PRD's short-index NavBlock.
- **Version overlays are a shared buildSrc helper** now: `applyVersionOverlays(minecraft, dir)`
  (`buildSrc/.../version-overlays.kt`), composing eras (highest ≤ active wins per file) via a
  `Sync` merge. Only `common` uses it (`overlays/`); loader modules currently need none.
- `deps.parchment` / `deps.architectury_api` are optional (skip-if-blank); architectury API is
  unused (loader glue is native events), so it's not bundled — only the loom plugin is needed.

## Known issues / caveats
- **Forge-dev wall (architectury-loom [#205]):** Forge 49.x+ (MC **1.20.4+**) dev `runClient`
  dies with a JPMS split-package — `com.orebit.mod*` is exported by both the common dev module
  AND the shadowed-into-Forge copy. Every common package splits; the resolver just reports one.
  Forge 1.21 dev also lacks `jopt.simple` on the module path (related toolchain gap). **Not our
  code — production Forge jars are single shadowed jars and are unaffected.** Fix is a dedicated
  toolchain task (likely a `loom.mods` source-set merge so common+forge load as one module,
  plus the `jopt.simple` classpath gap).
- **1.20.1 `FakeClientConnection`** skips the `send` no-op (its signature differs pre-1.20.2) —
  harmless EmbeddedChannel buffer growth; pin the exact 1.20.1 `send` signature if it matters.
- **Bot position**: set via `bot.setPos` before `placeNewPlayer`; user didn't report
  mispositioning, so it's apparently fine (Carpet does a post-spawn `teleportTo` if needed).

## Next steps (pick one)
1. **Forge-dev #205 workaround** — only if in-dev Forge testing matters; shipped jars work.
2. **Goal 2: Fabric + 26.2** — extend the version ladder above 1.21.4 (new overlay eras as
   APIs diverge; reuse `chiseledCompileCommon` to pin boundaries).
3. **Phase 1: make the world-model pipeline run** — short-index NavBlock (per-BlockState),
   fix `NavSectionBuilder`, wire `ChunkNavLoader` to store sections; re-enable what we decoupled.
4. **Docs reorg (deferred)**: move `PRD.md` + `PORTABILITY-AUDIT.md` into `internal_docs/`
   (NOT `docs/` — public MkDocs); refresh `CLAUDE.md` (still says Fabric/1.21.4-only).

## Reference
- Build all real targets: `./gradlew chiseledBuild` (4G heap). Per loader:
  `chiseledBuild{Fabric,Neoforge,Forge}`. Jars land in `build/libs/<modver>/<loader>/`.
- Cheap boundary probe (no loader deps): `./gradlew chiseledCompileCommon --continue`.
- Run a version: `./gradlew "Set active project to <ver>"` then `:<loader>:<ver>:runClient`.
  Always `./gradlew "Reset active project"` (→ 1.21.4) before committing.
- Matrix: `settings.gradle.kts`. Per-version deps: `versions/<ver>/gradle.properties`.
- Overlays: top-level `overlays/<era>/java` + `applyVersionOverlays`. See the
  `version-overlays` and `loader-matrix` memories.

[#205]: https://github.com/architectury/architectury-loom/issues/205
