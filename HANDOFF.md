# HANDOFF — Orebit multi-version/loader work

> **Temporary file.** Delete (or overwrite) this at the end of the next session.
> Updated 2026-06-21 (session 2).

## Where we are

- **Phase 0 complete + merged to `master`** (merge `a129dec`): foundation hygiene,
  Yarn→Mojmap, Architectury (common/fabric/neoforge), Stonecutter multi-version.
- **On branch `feature/forge-1.20.1`:** **ALL THREE loader/version combos now BUILD.**
  `./gradlew chiseledBuildFabric chiseledBuildNeoforge chiseledBuildForge` → green.
  Produces `orebit-fabric-…+1.21.4.jar`, `orebit-neoforge-…+1.21.4.jar`, and
  **`orebit-forge-…+1.20.1.jar`**. Changes are uncommitted (working tree).

## What was done this session — the version-overlay mechanism

The user prefers file-level version adapters over Stonecutter `//?` comments. We built a
**forward-looking overlay system**:

- **Overlays live in a TOP-LEVEL `overlays/<era>/java/…` dir** (mirrors the package tree).
  `<era>` = the FIRST MC version that flavor applies to. Add a new era dir only when a
  newer version breaks something; never rename old ones.
- **Must be OUTSIDE `src/`.** Stonecutter copies the *entire* `src/` tree into its
  per-version `chiseledSrc`, so any overlay under `src/` gets every era swept in at once →
  duplicate-class. (Tried `src/main/overlays` and `src/overlays`; both failed for this
  reason. Top-level `overlays/` is the only location that works.)
- **Selection logic in `build.gradle.kts`** (the common script, applied to common nodes):
  for the active MC version it adds the single highest `overlays/<era>` whose version ≤
  active, as an extra `sourceSets.main.java.srcDir`. Logs `[orebit] MC <v> -> overlay era
  '<era>'`. Confirmed: `1.20.1 → era 1.20.1`, `1.21.4 → era 1.20.2`.
- **Each era dir is a complete snapshot** of the version-divergent files for that era (so
  "highest era ≤ active" always provides every divergent class with no core duplicate).
  Divergent files do NOT live in `src/main/java`.

### Divergences found and fixed (the HANDOFF in session 1 was WRONG)

Session 1 reported "only the login refactor diverges" — but compilation had stopped at the
first error. With that fixed, 4 more surfaced, **exactly as `PORTABILITY-AUDIT.md`
predicted**. All now fixed:

| Divergence | 1.20.1 API | Fix |
|---|---|---|
| `ServerPlayer` 4-arg ctor (`ClientInformation`, 1.20.2) | 3-arg | `FakePlayerEntity` overlay (1.20.1 vs 1.20.2 era); ctor now 3-arg `(server,world,profile)`, 1.20.2 era supplies `ClientInformation.createDefault()` internally |
| `ServerGamePacketListenerImpl` 4-arg ctor (`CommonListenerCookie`, 1.20.2) | 3-arg | `FakeNetworkHandler` overlay |
| `LivingEntity.kill(ServerLevel)` (1.21) | `kill()` | `FakePlayerEntity.removeFromWorld()` (smart object); `BotManager` just calls `bot.removeFromWorld()` |
| `BlockState.isSolidRender()` | `isSolidRender(BlockGetter,BlockPos)` | `platform.BlockShapes.isSolidRender(state,level,pos)` accessor (overlay) |
| `Level.getMinY()` (1.21) | `getMinBuildHeight()` | `platform.LevelBounds.minY(world)` accessor (overlay) |
| `Blocks.SHORT_GRASS` (1.20.3) | `Blocks.GRASS` | `platform.VersionedBlocks.SHORT_GRASS` constant (overlay) |

The `platform.*` accessors are **narrow, single-responsibility, version-selected concrete
classes** compiled one-flavor-per-version (compile-time selection = zero runtime dispatch —
satisfies the hot-path requirement). `design-principles.txt` was updated to clarify that
narrow well-named static-accessor classes are fine (the anti-pattern is broad `*Utils`).

### Known imprecision (intentional, to be resolved by the walk-back)

The `1.20.2` era currently bundles ALL post-1.20.1 deltas, even ones that actually changed
later (`kill`/`getMinY` at 1.21, `SHORT_GRASS` at 1.20.3, `isSolidRender` sig later). This
is correct for the only two targets today (1.20.1, 1.21.4) but would be wrong for an
untargeted 1.20.2–1.20.6. The version walk-back (below) will split `1.20.2` into finer eras
and the bundled flavors carry `// this era currently targets 1.21.4` notes pointing at it.

## Next steps

1. **Interactive verify (USER runs):** `./gradlew :forge:1.20.1:runClient` — confirm the bot
   spawns + follows on Forge 1.20.1. (Fabric/NeoForge 1.21.4 already user-confirmed.)
2. **Commit** the working tree (overlay mechanism + fixes + principle doc).
3. **Version walk-back:** add every MC version between 1.20.1 and 1.21.4 to pin exactly
   which version introduced each divergence, then split the `1.20.2` era accordingly.
4. **Going forward (new targets):** walk back ONE version at a time so each break is
   attributed precisely. Upcoming 26.2 ladder: 1.21.5 → 1.21.6 → 1.21.7–9 → 1.21.10–11 →
   26.1 → 26.2.
5. **Docs:** move `PRD.md` + `PORTABILITY-AUDIT.md` into a new `internal_docs/` (NOT `docs/`,
   that's the public MkDocs site); update CLAUDE.md "Where to look" paths. CLAUDE.md still
   describes the repo as Fabric/1.21.4-only in places — refresh for multi-loader/version.

## Reference

- **Overlay convention:** top-level `overlays/<introducing-version>/java/<package>/…`;
  selection in `build.gradle.kts` (`overlaysDir`/`era` block). Outside `src/` is mandatory.
- **Toolchain:** Stonecutter 0.6, architectury-loom 1.10, Gradle 8.12.1. MC 1.21.4 (Java 21,
  Mojmap+Parchment, fabric-api 0.119.2, neoforge 21.4.157, arch-api 15.0.2). MC 1.20.1
  (Java 17, forge 47.3.0, arch-api 9.2.14).
- **Build all:** `./gradlew chiseledBuild`; per loader `chiseledBuild{Fabric,Neoforge,Forge}`.
  Run: `:fabric:1.21.4:runClient`, `:neoforge:1.21.4:runClient`, `:forge:1.20.1:runClient`.
- Audit: `PORTABILITY-AUDIT.md` (validated again this session). Status memory: project-status.

## Cleanup
Delete/overwrite this `HANDOFF.md` before ending the next session.
