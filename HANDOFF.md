# HANDOFF — Orebit: backward wall FOUND (1.13.2); current era extended to 1.17.1

> **Temporary file.** Delete/overwrite at the end of the next session.
> Written end of session 8 (2026-06-22). Supersedes the session-7 "prove backward" handoff.

## What this session did

1. **Found & PROVED the backward toolchain wall: it is at 1.13.2** (the current era's floor is
   **1.14.4**). Probed via `chiseledCompileCommon` on a scratch branch with older MC versions
   appended to the matrix:
   - **1.14.4 → 1.19.4 all reach `compileJava`** — the toolchain (Architectury Loom 1.13.469 /
     Gradle 8.12.1 / official Mojang mappings) resolves and sets up Minecraft cleanly that far
     back. Failures there are pure source/API divergences, not toolchain.
   - **1.13.2 and 1.12.2 fail at configuration**: `Failed to find official mojang mappings for
     1.13.2` — Mojang first published obfuscation maps for **1.14.4**. This is a fatal config-phase
     failure (not `--continue`-able). Compounded by the **1.13 "Flattening"** (block model goes
     BlockState→numeric id+metadata, PRD §9). So pre-1.14 is a genuinely **separate era** (needs
     Yarn/MCP mappings + a second port of the block layer), NOT a simple toolchain bump.
2. **Extended the current era backward to 1.17.1 (common compile) and LANDED it.** Re-baselined the
   overlay chain and added platform shims so the loader-agnostic common source compiles on
   **1.17.1, 1.18.2, 1.19.2, 1.19.4** (verified `chiseledCompileCommon`), with the existing
   1.20.1–1.21.11 era **unregressed**.
   - Portable work committed on **`core`** (`bb6fb9e`): overlay re-baseline 1.20.1→1.14.4 + new
     override eras (1.19, 1.19.3, 1.19.4, 1.20); new platform shims `Worlds` (Entity.level),
     `EntityState` (onGround), `Replaceable` (canBeReplaced/Material), `MineableTags`
     (sword_efficient), `BlockKinds` (BambooStalkBlock); `BlockLookup` registry split
     (Registry→BuiltInRegistries→Identifier) + iteration helpers; FakePlayerEntity ctor flavors
     (1.19 ProfilePublicKey / 1.19.4 3-arg); core src (NavBlock, AllyBotEntity, BotManager,
     BotPositioning, OrebitCommon, TraversalAnalyzer) routed through the shims.
   - Matrix extension committed on **`main`** (`e88816a`): added 1.17.1,1.18.2,1.19.2,1.19.4 to
     `mc.versions.common`. **Loader matrices unchanged** — per-loader builds for the backward range
     are follow-up (see below).
3. **Decided (with the user):** extend the era as far as the toolchain allows; **defer** the pre-1.14
   legacy era branch (document the wall instead of cutting a non-buildable placeholder); **stop the
   source-port grind at 1.17.1** (≤1.16.5 is deep, fragile, common-compile-only value on
   non-committed versions — see below).

## Branch state (NOT yet pushed)

- **`core`** `bb6fb9e` — portable 1.17.1 overlay/shim work. (toolless, not buildable.)
- **`main`** `e88816a` — `core` merged + the common-matrix extension. **Buildable; verified green**
  (`chiseledCompileCommon` SUCCESSFUL, 1.17.1→1.21.11).
- **`scratch/backward-probe`** `2bfed15` — LOCAL probe branch. Holds the full backward probe matrix
  AND a **WIP partial ≤1.16.5 hard-tier** commit (`DO NOT propagate`) preserved as a reference for
  the follow-up. Safe to delete once the ≤1.16.5 work is redone properly.
- Plus the doc commit this handoff is part of (on `core`, propagated to `main`).
- **Nothing pushed yet** — local commits on `core` + `main` await `git push` (personal SSH key;
  `gh` is authed as the work account, so use plain `git push`).

## NEXT (pick up here)

**A. Land the loader builds for 1.17.1–1.19.4** (the matrix extension was common-only):
   - Add `versions/<ver>/gradle.properties` for 1.17.1/1.18.2/1.19.2/1.19.4 (parchment, fabric_api,
     forge_loader; NeoForge floor is 1.21 so none there). Add them to `mc.versions.fabric` /
     `mc.versions.forge` on `main`.
   - Build with `chiseledBuild{Fabric,Forge}`; expect loader-event overlay drift (Fabric/Forge event
     APIs across 1.17–1.19) — handle in `overlays`/`overlays-forge` like the 1.21.6 EventBus-7 case.
   - Densify the common matrix to every patch in 1.17–1.19 if desired (each new patch may add a
     boundary; re-run `chiseledCompileCommon`).

**B. (Optional) Resume the ≤1.16.5 hard tier** — only if 1.14–1.16 become worth it. It is a deep
   cascade of 1.14–1.16 entity/block API shims and is **common-compile-only** (a runnable ≤1.16.5
   jar also needs a log4j-flavored logging shim — 1.16.5 has no slf4j — plus the loader work in A).
   The WIP on `scratch/backward-probe` already prototypes: FakePlayerEntity 4-arg
   `ServerPlayerGameMode` baseline + 1.17 3-arg override; `LevelBounds` baseline `return 0` + 1.17
   `getMinBuildHeight`; `MineableTags` Material-based baseline + 1.17 mineable-tags flavor; NavBlock
   tag checks via the shim + `getBlock()==`; `compileOnly` slf4j for <1.17. **Remaining shims** (the
   1.17 boundary, all field→method or class-rename changes):
   - rotation setters: `setYRot`/`setXRot` are public fields `yRot`/`xRot` pre-1.17 (AllyBotEntity,
     BotPositioning) — add `EntityState.setYRot/setXRot(Entity,float)` (baseline field, 1.17 method).
   - `Entity.discard()` → pre-1.17 `remove()` (FakePlayerEntity baseline).
   - `Block.defaultDestroyTime()` absent pre-1.17 — used in NavBlock + both TraversalAnalyzers; needs
     a `BlockHardness` shim (verify the pre-1.17 base-hardness accessor).
   - `PointedDripstoneBlock`, `Blocks.CAVE_VINES` are 1.17 — add `BlockKinds.isPointedDripstone` /
     `isCaveVines` (baseline `false`, 1.17 real); a 1.17 BlockKinds flavor is then needed.
   - verify the baseline `Material` constant names (STONE/METAL/WOOD/DIRT/SAND/…); 1.14.4 still had
     ~82 errors so expect more 1.15.2/1.14.4 surface after the above.

**C. (Future) The pre-1.14 legacy era** — when the 1.12.2 stretch is actually pursued: new era branch
   off `core` with its own `era.properties` (older Loom/Gradle + **Yarn or MCP mappings**, since no
   official Mojang maps) and the Flattening block-layer port. Big; deliberately deferred.

## How to work this model (reminders)

- Author common/overlay/version-file changes on **`core`**; toolchain values (`era.properties`,
  wrapper) on the era branch only. Propagate with `sh scripts/propagate.sh` (= `git merge core`).
- Truth tool: **`./gradlew chiseledCompileCommon --continue`** (compiles every common node; the
  standing pre-release gate). A single `:<ver>:compileJava` is **hollow** — it compiles only the
  overlay-merged files, not the chiseledSrc core source (confirmed this session). Trust the chiseled
  task.
- Overlay re-baseline procedure: RENAME the baseline dir down to the new oldest version; fix it to the
  older API; add an override dir at the introducing version that restores newer behavior. (Memory:
  `overlay-rebaseline-procedure`.)
- Verify a real jar isn't hollow: `unzip -l <jar> | grep com/orebit/mod` (~50 classes).

## Reference

- Full plan: `internal_docs/BUILD-STRATEGY.md` (§8 now records this backward result).
- Memory: `multiversion-build-strategy`, `overlay-rebaseline-procedure`, `version-overlays`,
  `loader-matrix`, `portability-findings`.
