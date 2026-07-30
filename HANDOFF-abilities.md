# HANDOFF — bot-abilities arc (crafting/farming/fighting/building), 2026-07-29

Arc design: `internal_docs/DESIGN-bot-abilities.md` (ratified order: Crafting → Farming →
Fighting → Building; component template + decision log §10). This file tracks arc state only —
the owner's region-tier arc handoff stays in `HANDOFF.md`.

## ✅ ADOPTION COMPLETE (2026-07-29, evening) — the wrinkle below is HISTORY

Everything is landed and verified on all three branches; the verify worktree/branch are gone.

- **Follower-envelope arc COMMITTED**: core `1c6f539` (topY-aware fromFootY/toFootY on plan();
  BotSteering.movementBlockedAt direction-keyed body-corridor obstruction) + `2d79ef1`
  (NavFlagsTest follows the fluid-scatter move — the suite's one standing failure is FIXED).
- **The interrupted session's owner-ruling increment COMMITTED**: core `64e132c` —
  mining.protectedBlocks is a PATHING policy (planner + route executors), deliberate hands
  exempt (D3 waiver machinery removed); gather-MINE protected-sight-line ESCALATES to an exact
  approach instead of dropping the vein; /bot farm is a PERSISTENT state (WATCH re-surveys).
- **mc-1.21 adopted everything**: FF onto `mc121-abilities-verify` (exact tested lineage) then
  `git merge core` (`2a6fefd`); era-owned follow-ups `c6074db` (counts-based FARM verdict),
  `119e0dc` (.gitignore carpet master), `4522433` (workRadius pin dropped). **main merged core**
  (`85a3ee6`). `core` also gained `56dfed5` (owner's future-work.txt notepad).
- **Verified post-adoption (mc-1.21 node, 1.21.11)**: unit suite 115 classes ZERO failures
  (first fully-green suite); FARM autotest PASS ×2 (pinned 126t; default-radius 106t —
  harvested 8, tilled 2); CRAFT scenario B PASS 118t (incl. the protected crafting-table
  reclaim under the NEW policy — the deliberate-hands exemption in action); FIGHT PASS 79t;
  BUILD PASS 1157t (9 cleared + 9/9 placed). 26-era chiseledCompile green ×4.
- **⚠ Flagship GOTO still FAILS — and its old attribution was WRONG.** Evidence (BotDebug run +
  pristine-world probe): the route descends a JUNGLE VINE CURTAIN at (55,173..177,256); the
  search correctly plans `Climb` down it, but Climb is an UNCONVERTED move and its legacy steer
  never initiates a grounded climb-DOWN (`exec Climb … targetY == current feetY` → zero drive);
  the bot perches on its own placed cobble lip forever while jungle-leaf decay keeps bumping
  plan-impacted re-searches. NOT an envelope failure (zero step-FAILED lines) and NOT a
  regression (Climb.java untouched by every commit above; the pre-merge A/B froze at the same
  cell). This is exactly future-work.txt's "can we go DOWN ladders/vines?" item — fixing it (or
  re-picking the flagship route) is an owner decision.
- Observation for the owner: the autotest conjures COBBLESTONE bridges while the default
  protected list contains cobblestone — the planner/route executors can therefore never re-break
  the bot's own placed bridge blocks (pathing-only consequence; deliberate hands unaffected).

## DONE — Crafting (SHIPPED)

- core `89e856e` feat(crafting), merged → main `b03b1af`. NOT yet on `mc-1.21` (see below).
- `/bot craft <item> [count]`: BotCrafter (PLAN/SEEK_TABLE/PLACE_TABLE/CRAFT/RECLAIM),
  `crafting/` package (RecipeIndex/KnownRecipe/IngredientSlot/CraftAssignment),
  `platform/CraftingOps` ×10 flavors (every signature javap-pinned; boundaries 1.17 / 1.19.4 /
  1.20 / 1.20.2 / 1.20.5 / 1.21 / 1.21.2 / 1.21.4 [Ingredient#items List→Stream] / 1.21.11
  [ResourceLocation→Identifier] / 26 [assemble one-arg]), `ItemLookup.byId` (+1.21.11 flavor),
  `crafting.*` config (CONFIG.md + docs/configuration.md), `BotMining.requestReclaim` (the one
  narrow protectedBlocks waiver, §10-D3).
- Verified: chiseledCompileCommon green ×28 (mc-1.21 era) + chiseledCompile green ×4 (26 era);
  unit suite 592 (6 new; only the pre-existing NavFlagsTest failure); headless CRAFT autotest
  PASS ×2 in-world on 1.21.11 (2x2 in place 46t; craft-table→place→craft-pickaxe→reclaim 136t).

## BRANCH STATE (the temporary wrinkle)

The owner's follower-envelope arc sits UNCOMMITTED in `orebit-mc121-wt` (AllyBotEntity et al.
dirty) → `git merge core` into `mc-1.21` is blocked there. Until that lands:

- mc-1.21-era work is verified in the THROWAWAY worktree `../orebit-mc121-verify`, branch
  **`mc121-abilities-verify`** = mc-1.21 HEAD (3dca500) + `53fb13e` test(autotest) CRAFT mode
  (era-owned: HeadlessAutotest + runAutotest whitelist + run-autotest.ps1 -Craft/-Give) +
  merge of core. **When the follower arc lands on `mc-1.21`: merge/cherry-pick `53fb13e`
  into `mc-1.21`, then the normal `git merge core`.** Then delete the verify worktree.
- 26-era in-game verify of `/bot craft` (runClient on main) still pending — headless coverage
  is mc-1.21-only (26 era has no harness).

## DONE — Farming (SHIPPED)

- core `b9b24f4` feat(farming), merged → main `7baaafa`; verify branch `ae26a17` (FARM harness) +
  `23a7fb6` (core merge).
- `/bot farm`: BotFarmer (SURVEY/WORK/SWEEPUP), `farming/CropKinds` Strategy classes
  (wheat/carrots/potatoes/beetroots), `platform/ItemUse` (version-STABLE src seam —
  ItemStack#useOn, javap-pinned), `platform/FluidRead` (1.17 + 26 flavors — 26.x removed
  FluidState#is), `BotMining.requestHarvest` (mature-crop protected waiver), seed exclusion in
  bridging selection, `farming.*` config, BotInventory findSlotMatching/equipSlot.
- **NAVIGATOR FIX shipped with it** (owner-review-worthy): `driveToward`'s `newRegionGoal` also
  fires on (exact tolerance && plan COMPLETE && stored goal cell != live goal cell) — the
  moved-goal freeze diagnosed live (bot nudges a chased drop one cell → frozen forever); also
  latent in gather-COLLECT. Non-exact callers byte-identical. PathPlan gains `goalFloor()`.
- Verified: 28+4 compile matrices green; suite 597 (only the pre-existing NavFlags failure);
  all farm mechanics observed working in-world (8/8 harvest, 10 plants, 2 tills).

## ⚠ KNOWN-BLOCKED e2e (re-verify after the follower arc lands)

The COMMITTED mc-1.21 state's envelope increment uses a full-block footY test — any converted
Traverse STARTING FROM a partial floor (farmland 15/16) trips fail→HOLD. A/B-verified
pre-existing: the flagship GOTO autotest freezes at (55,177,256) wp0/47 on the UNMODIFIED
committed branch too. The owner's in-flight follower-envelope arc (topY-aware fromFootY/toFootY)
fixes it. After it lands on mc-1.21: cherry-pick `53fb13e`+`ae26a17`, merge core, then re-run:
- `powershell scripts/run-autotest.ps1 -MasterWorld ../orebit-autotest-world/scripts/autotest-world-master/world` (GOTO, expect PASS)
- `... -MasterWorld ../orebit-autotest-world/scripts/gather-issues-repro-master/world -Start 70,63,-68 -Farm -BudgetTicks 6000` (FARM, expect PASS; then re-widen the template's farming.workRadius=5 pin)
- the two CRAFT scenarios (already PASS on the committed base).

## DONE — Fighting (SHIPPED)

- core `171ae95` feat(combat), merged → main `f239c82`; verify branch `69eec46` (FIGHT harness) +
  `5334adb` (core merge).
- BotFighter = pre-dispatch consumed-tick interrupt (NOT a Mode; paused mode machines resume
  free). Strategies: CreeperStrategy (source-verified swell constants), SkeletonStrategy (family
  classed via the new `platform/MobKinds` seam — the skeleton/zombie CLASSES moved to
  monster.skeleton / monster.zombie subpackages at exactly 1.21.11), MeleeStrategy fallback.
  Cadence reads the REAL getAttackStrengthScale ticker. `combat.*` config;
  BotInventory.equipBestWeapon (id-string ranking, WeaponRankTest).
- Verified: 28+4 matrices green; suite 601 (only the pre-existing NavFlags failure); headless
  FIGHT autotest PASS on 1.21.11 (zombie targets bot → interrupt preempts STAY → 4 full-charge
  strikes → dead in 79t, bot 20hp — the zombie WALKS to the bot, so the scenario dodges the
  committed follower gap entirely).

## DONE — Building (SHIPPED) — THE ARC'S FOUR FEATURES ARE COMPLETE

- core `b3c8b29` feat(building), merged → main `70c0da9`; verify branch `7607841` (BUILD harness)
  + `c56ad87` (core merge).
- `building/NbtReader` (dependency-free NBT wire reader) + `building/Schematic` (.litematic,
  byte-faithful: negative-Size corners, LSB-first SPANNING bit reads, Version<5 rejected) +
  `building/PaletteResolver` (exact BlockState via BlockLookup + generic Property-by-name — NO
  version flavors needed) + `BotBuilder` (SCAN/WORK diff sweeps, bottom-up, self-healing props
  diff-ignored, partner cells deduped, exact-state place + timed clears, sweep-progress
  convergence, honest missing-materials report) + `/bot build` (files from
  `<server dir>/orebit-schematics/`) + `building.clearMismatches`.
- Verified: 28+4 matrices green; suite 604; BUILD autotest PASS (9 cleared + 9/9 placed, 1155t).
- FINAL integrated regression sweep on the verify branch head `c56ad87`: CRAFT scenario B PASS,
  FIGHT PASS, BUILD PASS (FARM + flagship GOTO remain known-blocked on the committed follower
  gap — see the ⚠ section; re-verify commands there).

## NEXT (future arcs, in the owner's order)

1. **Work trees** — the requirements/planner layer composing gather/craft/farm/build (the stub
   `requirements/` spec's hand-authored recipes govern THIS layer; `RecipeIndex` stays the
   execution truth). Components already expose the seams: `startX()` entries, observation
   getters, BotBuilder's missing-materials map = the shopping list.
2. Skeleton-strategy refinement (strafe/LOS-break needs a driver strafe primitive), shield/bow
   use, eating (mandatory before `survival.hunger` defaults on).
3. TileEntities/Entities in schematics; Sponge `.schem` fallback; farmland-landing avoidance in
   the planner (a jump onto farmland tramples — plan-cost work, owner-gated).
4. LLM integration (intent only, per the PRD).

Building plan (DESIGN-bot-abilities.md §6 + the building-formats recon): (1) `.litematic` parser
in core — gzip NBT (consider a dependency-free mini-NBT reader for full version independence);
per-region minCorner = per-axis `min(pos, pos + (size>=0 ? size-1 : size+1))`, dims=abs(size);
palette = list of {Name, Properties(strings)}; BlockStates long[] with
bits = max(2, ceil(log2(paletteSize))), LSB-first and SPANNING long boundaries (LitematicaBitArray
semantics — vanilla SimpleBitStorage PADS and mis-decodes); index = x + z*dimX + y*dimX*dimZ;
palette→BlockState behind a platform seam (BlockLookup.byId + property-by-name application).
(2) BotBuilder (Mode.BUILD): diff-vs-world reactive loop, bottom-up support-aware, SKIP
self-healing connection props (stair SHAPE, fence/pane/wall sides — updateShape converges),
dedupe multi-block roots (door lower, bed foot), direct exact-state placement
`setBlock(pos, state, 0x12)` + real-inventory consumption + reach gating, missing-materials
chat, unreachable-cell terminal state (no timers). Schematics in `<world>/orebit/schematics/`
(ConfigDir pattern); `/bot build <name> <x y z>`; `building.*` config. Tests: pure-JVM parser
round-trip with a fixture file + a superflat-plot autotest (movement-light so the follower gap
doesn't block: build a small flat 5×5 platform schematic within reach). NOTE walking-dependent
scenarios stay blocked until the follower arc lands.
