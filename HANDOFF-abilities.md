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

## NEXT — Fighting, then Building

Fighting plan (DESIGN-bot-abilities.md §5 + javap): BotFighter = PRE-DISPATCH consumed-tick
interrupt (followThroughPortal precedent, NOT a Mode; un-ticked mode components pause/resume
free). Combat surface javap-pinned BYTE-STABLE 1.17.1→26.2: Player#attack(Entity),
getAttackStrengthScale(float) (read the REAL charge — no derived clock), Mob#getTarget/setTarget.
Target rule: nearest Mob whose getTarget()==bot within combat.scanRadius. Strategies by
instanceof (Creeper / AbstractSkeleton / default melee), one class per archetype. Weapon pick by
id ranking (sword > axe, tier by prefix). NOTE: mobs only target the bot when
survival.takesDamage=true (abilities.invulnerable exempts via canBeSeenAsEnemy).
