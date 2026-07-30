# HANDOFF — bot-abilities arc (crafting/farming/fighting/building), 2026-07-29

Arc design: `internal_docs/DESIGN-bot-abilities.md` (ratified order: Crafting → Farming →
Fighting → Building; component template + decision log §10). This file tracks arc state only —
the owner's region-tier arc handoff stays in `HANDOFF.md`.

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

## NEXT — Farming (task in progress), then Fighting, then Building

Farming plan (DESIGN-bot-abilities.md §4): `platform/ItemUse` overlay seam
(ItemStack#useOn(UseOnContext), UP-face BlockHitResult — the server-guarded vanilla till/plant
path; ctor drift to javap-pin), BotFarmer (Mode.FARM: harvest mature [age tables: wheat/
carrot/potato 7, beetroot 3, wart 3, cocoa 2], replant, till near water [Chebyshev-4 box, same
Y and Y+1], plant), per-crop Strategy classes, farming.* config, seed-exclusion fix in
consumeOnePlaceable (softest-first would eat seeds), no-jump-on-farmland (trample =
fallDistance > 0.5). Tests: pure-JVM crop tables + a FARM autotest/course with pre-grown
crop states (growth is random-tick — never wait on it).
