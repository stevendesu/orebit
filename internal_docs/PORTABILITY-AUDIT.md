# Orebit — Portability Coupling Audit (Phase 0, §9)

> **Status:** Phase 0 deliverable, 2026-06-20. Inventories every version/loader-coupled
> API call in the **real (non-stub) code** and classifies each **COLD** (route behind a
> thin platform interface — dispatch cost is noise) vs **HOT** (per-block/per-tick;
> select a **version-specific concrete class once at load**, never a per-call interface —
> see PRD §9 "cold path → interface; hot path → version-selected concrete class").
>
> Scope = the ~30 files with runnable logic. The ~83% Javadoc-only stubs are excluded
> (they have no call sites yet; they get the right structure when implemented).

## Summary

| Coupling surface | Files | Class | Skeleton destination |
|---|---|---|---|
| Loader event registration | `Orebit`, `ChunkNavLoader` | COLD | `PlatformEvents` interface (per-loader impl) |
| Fake-player network/entity stack | `FakePlayerEntity`, `FakeNetworkHandler`, `FakeClientConnection`, `BotManager`, `BotPositioning` | COLD (per-spawn) but **most version-fragile** | Version-selected factory + versioned entity subclass (Stonecutter source sets) |
| Per-tick movement actuation | `AllyBotEntity.tick()`, `FakePlayerEntity.tick()` | HOT | Lives inside the versioned entity subclass; no interface in the tick loop |
| Chunk/palette read + reflection | `NavSectionBuilder` | **HOT (hottest)** | **Version-selected concrete** `NavSectionBuilder_<ver>`; reflection by Yarn field name is the most fragile code in the repo |
| Per-cell traversal classify | `TraversalAnalyzer`, `TraversalAnalyzerMutable` | HOT | Version-selected concrete; consolidate `Blocks.X` → tags |
| Block/registry static tables | `NavBlock`, `RegionBlockIndex` | COLD (static-init) | Cold, but hardcoded `Blocks.X` lists → tag/behavioral queries + version guards |
| Mixins | `DebugLogging`, `ExampleMixin`, `ExampleClientMixin` | COLD (apply once) | Stonecutter-conditional mixins; version-fragile method names (`travel`, `loadWorld`, `run`) |

---

## COLD — route behind the platform interface

### Loader event registration → `PlatformEvents`
Fabric API is touched in exactly **2 files** (confirms §9):
- `Orebit.java:10-11,47,52,60` — `ServerLifecycleEvents.SERVER_STARTED`, `ServerPlayConnectionEvents.JOIN/DISCONNECT`.
- `ChunkNavLoader.java:3-4,19,26` — `ServerChunkEvents.CHUNK_LOAD`, `ServerTickEvents.END_WORLD_TICK`.

These are pure loader API. Extract a `PlatformEvents` interface (`onServerStarted`, `onPlayerJoin`, `onPlayerDisconnect`, `onChunkLoad`, `onWorldTickEnd`) with `fabric`/`(neo)forge` implementations. Dispatch frequency is once-per-event — interface cost is noise.

### Fake-player network/entity construction (per-spawn, version-fragile)
Not hot (runs at bot spawn / player join), but the **single most version-fragile cluster** — all tied to the post-1.20.2 login/protocol refactors:
- `FakePlayerEntity.java:13-15` — `ServerPlayerEntity(MinecraftServer, ServerWorld, GameProfile, SyncedClientOptions)` ctor; `SyncedClientOptions` param added 1.20.2; writes protected `networkHandler`.
- `FakeNetworkHandler.java:12-22` — `ServerPlayNetworkHandler` super + `new ConnectedClientData(profile, SharedConstants.getProtocolVersion(), clientOptions, false)`; `ConnectedClientData` introduced 1.20.2; `SharedConstants.getProtocolVersion()` value is per-version.
- `FakeClientConnection.java:9` — `ClientConnection(NetworkSide.SERVERBOUND)`.
- `BotManager.java:44` — `new PlayerListS2CPacket(Action.ADD_PLAYER, bot)`; packet was split add/remove in 1.19.3, ctor shape varies.
- `BotManager.java:54,63` — `bot.kill((ServerWorld) bot.getWorld())`; `kill` gained a `ServerWorld` param in 1.21.x (older = no-arg).
- Renamed accessors: `getWorld()` (`BotManager:19`, `BotPositioning:15`), `isOnGround()` (`AllyBotEntity:71`).

**Destination:** these can't hide behind one interface cleanly because the *types themselves* differ across versions. The fake-player stack becomes a **Stonecutter version source set** — a `BotFactory.spawn(...)` selection boundary with `FakePlayerEntity`/`FakeNetworkHandler` compiled per MC version. No access-widener exists today; all `protected`/inherited access is via subclassing, so a mapping/visibility shift breaks compilation directly (caught at build, good).

### Block/registry static tables (static-init; hardcoded block lists)
Run once at class load, so COLD — but stuffed with version-fragile hardcoded constants and the §9 "consolidate `Blocks.X` into tag/behavioral queries" target:
- `NavBlock.java:61-108,120-160` — `Registries.BLOCK.forEach`, `Blocks.{LADDER,VINE,LAVA,...}`, `instanceof {Door,Trapdoor,Slab,Stairs,Snow,Bamboo,PointedDripstone,BlockWithEntity,Falling}Block`, `BlockTags.*_MINEABLE`, version-fragile `BlockState` signatures (`isSideSolidFullSquare`, `getCollisionShape(null,null)`, `isToolRequired`, `contains(Properties.WATERLOGGED)`).
- `RegionBlockIndex.java:20-193` — ~hundreds of `Blocks.X` constants incl. **1.21.x-new** `PALE_OAK_*` (absent in 1.20.1 / 1.12.2), `SCULK*`, `POWDER_SNOW`.

**Destination:** static-init stays, but (a) prefer `BlockTags` / behavioral predicates over identity lists where a tag exists; (b) blocks that don't exist in a target version go behind Stonecutter guards; (c) `NavBlock` already keys per-`Block` and must move to per-`BlockState` (Phase 1) — fold the version guards in then.

### Mixins (apply once; version-fragile method names)
- `DebugLogging.java:14,18` — `@Mixin(LivingEntity.class)` `@Inject(method="travel")`; `travel(Vec3d)` signature historically varied. (Debug-only; body runs per-tick but is removable.)
- `ExampleMixin.java:9,11` — `MinecraftServer.loadWorld` (no-op).
- `ExampleClientMixin` (`src/client/.../mixin/client/`) — `MinecraftClient.run` (no-op).

**Destination:** Stonecutter-conditional mixin source where method names diverge. The two `Example*` mixins are no-ops — candidates for deletion in Phase 1.

---

## HOT — select a version-specific concrete class once at load

### `NavSectionBuilder` — the hottest path and the most fragile code
- Reflection by **Yarn field name** into palette internals (static-init, `:38-63`): `PalettedContainer.data`, `PalettedContainer$Data.{storage,palette}`, `ArrayPalette.array`, `BiMapPalette.map`, `IdListPalette.idList`, all via `getDeclaredField`/`Class.forName`. These names are mapping- and version-specific; **this is the most version-fragile code in the project.**
- Intended hottest loop (currently commented): per-cell `chunkSection.getBlockState(x,y,z)` over 16³, palette-type `switch`, `storage.forEach`. Live STEP-1 loop today is the throwaway `world.getBlockState(pos).isSolidBlock(world,pos)` benchmark at `:146`.

**Destination:** `NavSectionBuilder_1_21` etc., **chosen once at load**; the read+classify inner loop calls the versioned palette API directly so it stays monomorphic/JIT-inlinable. The `PalettedContainer` access is the prime candidate to move from reflection to either an access-widener or a versioned mixin accessor (kills the by-name reflection fragility). 1.12.2 has no `PalettedContainer` at all → a separate port of this class behind the same selection boundary (the §9 "hard stretch").

### `TraversalAnalyzer` / `TraversalAnalyzerMutable` — per-cell classify
Every `classify()`-reachable call is HOT (per cell × 4096/section): `getBlockState`, `getCollisionShape(world,pos).getMax(Axis.Y)`, `isAir`, `getFluidState`, `isReplaceable`, plus **per-cell hardcoded `Blocks.X` identity tests** and — in `TraversalAnalyzer` — `block.toString().contains("CONCRETE_POWDER")` (`:156`, the most fragile single line; the Mutable variant uses a proper `Set`, confirming the documented drift). `SHORT_GRASS` (`Mutable:120`) was `GRASS` pre-1.20.3.

**Destination:** version-selected concrete classifier. The per-cell `Blocks.X` comparisons should be **precomputed into the NavBlock table** (lookup, not per-cell branching) — a Phase-1/4 perf win that *also* removes most of the per-cell version coupling. Reconcile the two analyzers (Phase 4) onto the tag/table approach.

### Per-tick movement actuation (inside the versioned entity)
- `AllyBotEntity.java:35-76` / `FakePlayerEntity.java:20-25` — `tick()` overrides; raw writes to inherited `forwardSpeed`/`sidewaysSpeed`/`upwardSpeed` (`:51,56,58,62,64`), `jump()` (`:72`), `setYaw/setHeadYaw/setBodyYaw/setPitch`, `tickMovement()`/`baseTick()`.

These already live inside the version-coupled entity subclass, so there's no extra interface to remove — the actuation loop calls inherited MC fields directly. They ride along with the versioned fake-player source set. (Known bug, not portability: `tickMovement()` double-invoked — `FakePlayerEntity:24` + `AllyBotEntity:75`; fix in Phase 5 integration.)

### `BotPositioning.findSafeSpotNear` — warm, bounded
- `:23-31` — nested candidate scan, `getBlockState` ×3/candidate + `isOpaqueFullCube`/`getFluidState`. Runs only at spawn over a bounded radius → **warm, not hot**. Behind the platform/world-access interface is acceptable; not worth a versioned concrete class. (Unused imports `Blocks`, `Direction` at `:4,7` — drop.)

---

## Actionable carry-forward into Stage C (skeleton) and later phases

1. **`PlatformEvents` interface** at the loader boundary — the only thing Fabric API touches. (Stage C)
2. **Stonecutter version source sets** for: the fake-player network/entity stack, `NavSectionBuilder`, the traversal classifiers, and version-divergent mixin method names. (Stage C structure; impls fill in over phases)
3. **Replace `PalettedContainer` reflection** with an access-widener or versioned accessor mixin — removes the by-Yarn-name fragility. (Phase 1)
4. **Consolidate hardcoded `Blocks.X`** in `NavBlock`/`TraversalAnalyzer*`/`RegionBlockIndex` into `BlockTags`/behavioral predicates; gate version-absent blocks (`PALE_OAK_*`, `SCULK*`, `SHORT_GRASS` rename) behind Stonecutter. (Phase 1/2/4)
5. **No access widener exists** — decide deliberately whether to introduce one (for palette internals + entity fields) vs. keep subclass/mixin access. (Stage C)
6. **1.12.2** is a separate block-layer port (no `PalettedContainer`, numeric IDs+metadata) behind the same selection boundary — the acknowledged hard stretch.
