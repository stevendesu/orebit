# HANDOFF — Orebit version walk-back

> **Temporary file.** Delete (or overwrite) at the end of the next session.
> Written 2026-06-21 (end of session 2). Next task: **version walk-back.**

## State at handoff (clean checkpoint)

- **Merged to `master`** (merge `37f6036`, on top of `fa41763`). Active version 1.21.4.
  **NOT pushed** (intentional — local-only until the work is "done"; user is sole user).
- **All three combos build green** (`./gradlew chiseledBuild`): Fabric 1.21.4 + NeoForge
  1.21.4 (overlay era 1.20.2) and **Forge 1.20.1 (overlay era 1.20.1)**. Forge 1.20.1 bot
  spawns/renders/follows in-game (user-confirmed).
- The version-overlay mechanism is in place and documented — see the `version-overlays`
  memory and the block comment in `build.gradle.kts`.

## The task: version walk-back

We know WHAT diverges 1.20.1 ↔ 1.21.4 but not exactly WHICH version introduced each. The
`overlays/1.20.2` era currently **bundles ALL post-1.20.1 deltas** (correct for the only two
targets today, but imprecise). Goal: **add each MC version between 1.20.1 and 1.21.4, one at
a time, build, see what breaks, and split `overlays/1.20.2` into precise eras** named by the
true introducing version.

### Divergences to pin (suspected boundary → confirm)

| Divergence | Where it's handled now | Suspected introducing ver |
|---|---|---|
| `ServerPlayer` / packet-listener 4-arg ctors (`ClientInformation`, `CommonListenerCookie`) | `FakePlayerEntity`/`FakeNetworkHandler` overlays | **1.20.2** (known) |
| `Blocks.GRASS` → `Blocks.SHORT_GRASS` | `platform.VersionedBlocks` | **1.20.3** (suspected) |
| `BlockState.isSolidRender(BlockGetter,BlockPos)` → no-arg | `platform.BlockShapes` | ~1.20.5? (unknown) |
| `Level.getMinBuildHeight()` → `getMinY()` | `platform.LevelBounds` | ~1.21.x (unknown) |
| `LivingEntity.kill()` → `kill(ServerLevel)` | `FakePlayerEntity.removeFromWorld()` | ~1.21.x (unknown) |

Also re-confirm the spawn-order fix (broadcast player-info before `addFreshEntity`, in
`BotManager`) is still needed / harmless across all versions (it's the dedicated AddPlayer
packet pre-1.20.2; the generic AddEntity packet 1.20.2+ tolerates either order).

### Process per version

1. Add the version to the Stonecutter matrix in `settings.gradle.kts`
   (`versions("1.21.4","1.20.1", … )`; decide which `branch(...)` covers it — see below).
2. Create `versions/<ver>/gradle.properties` with that version's deps:
   `parchment`, `fabric_loader`, `architectury_api`, plus loader (`neoforge`/`forge_loader`)
   and `mc_dep*` keys. **Every `deps.*` key referenced at config time must exist for the
   active version** (read via `common.mod.dep(...)`). Java is auto-selected by
   `stonecutter.eval(minecraft, ">=1.20.5")` (17 vs 21) — already wired.
3. `./gradlew chiseledBuild<Loader>` (or set active + `:<ver>:compileJava`); read the errors.
4. When a divergence first appears at version V, create `overlays/V/java/...` with the new
   flavor and move the bundled flavor out of `overlays/1.20.2` as appropriate. The selection
   logic (highest era ≤ active, per `build.gradle.kts`) does the rest. Each era dir must be a
   COMPLETE snapshot of the divergent files (so "highest ≤ active" always resolves them).

### Lightening the load: which loader per intermediate version?

The divergences are **MC-version**, not loader, and all live in the common source. So you
only need the **common node to compile per MC version** — no need to stand up Forge/NeoForge
for every intermediate. Easiest: add intermediate versions as **Fabric branches**
(`branch("fabric"){ versions("1.20.2","1.20.3", …) }`) since Fabric supports them all, and
walk with `chiseledBuildFabric`. Keep Forge pinned to 1.20.1 and NeoForge to 1.21.4 (the real
targets). This is purely to pin boundaries; once pinned, the eras serve every loader.

**Dependency sourcing is the research-heavy part** — per MC version you need the right
Parchment date, `fabric_loader`, and `architectury_api`. Use the maven repos
(maven.parchmentmc.org, maven.fabricmc.net, maven.architectury.dev) / WebSearch. Expect this
to dominate the session's effort and build-download time.

## Gotchas (carried forward — all confirmed this session)

- Overlays MUST live in TOP-LEVEL `overlays/<era>/java` (outside `src/` — Stonecutter copies
  all of `src/` into per-version `chiseledSrc`, which would duplicate every era).
- `file(...)` in `build.gradle.kts` resolves to the node dir `versions/<ver>/`; use
  `rootProject.file("overlays")`.
- Non-active nodes get NO root `src/main/java` (only `versions/<ver>/src` + overlay), so
  direct `:node:build`/`runClient` fails to find common classes. Build others via
  `chiseledBuild*`; to RUN a version, set it active first:
  `./gradlew "Set active project to <ver>"`, then `:<loader>:<ver>:runClient`. Always
  `./gradlew "Reset active project"` (→ 1.21.4) before committing.
- `runActive*<Loader>` tasks only exist for the currently-active version.

## After the walk-back

- Then either **Goal 2 (Fabric + 26.2)** via the same one-version-at-a-time ladder
  (1.21.5 → 1.21.6 → 1.21.7–9 → 1.21.10–11 → 26.1 → 26.2), or pivot to **Phase 1** (make the
  world-model pipeline run) now that portability is proven.
- **Docs reorg (deferred):** move `PRD.md` + `PORTABILITY-AUDIT.md` into a new `internal_docs/`
  (NOT `docs/` — public MkDocs); refresh `CLAUDE.md` (still says Fabric/1.21.4-only in places)
  + its "Where to look" paths.

## Reference
- Build all: `./gradlew chiseledBuild`; per loader `chiseledBuild{Fabric,Neoforge,Forge}`.
- Overlay convention + selection: `build.gradle.kts` (`overlaysDir`/`era` block) and the
  `version-overlays` memory. Audit: `PORTABILITY-AUDIT.md`.
- Delete/overwrite this HANDOFF before ending the next session.
