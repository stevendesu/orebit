package com.orebit.mod;

import com.orebit.mod.config.Config;
import com.orebit.mod.config.ConfigLoader;
import com.orebit.mod.pathfinding.blockpathfinder.MiningModel;
import com.orebit.mod.platform.BotInventory;
import com.orebit.mod.platform.WorldEdits;
import com.orebit.mod.worldmodel.resource.DropModel;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The bot's "hands" for breaking blocks — a per-tick timed mining actuator that makes the bot dig a block the
 * way a real player does: equip the fastest held tool, face the block, swing, watch the crack overlay build,
 * and break it (with proper drops / XP / tool wear) only after the REAL number of ticks vanilla mining takes.
 * This replaces the old instant, drop-less {@link com.orebit.mod.platform.WorldEdits#breakBlock} so the tick
 * costs the planner already charges (mining time, via {@code MiningModel}) are actually spent, and so the bot
 * picks up what it mines (its real {@code ServerPlayer} inventory already auto-collects drops).
 *
 * <h2>Reactive by construction (the north star: a keyboard player, driven by code)</h2>
 * There is no "mine this, then I'm done" latch. Each tick the follower {@link #request(BlockPos)}s the cell it
 * currently needs gone; {@link #tick(ServerLevel)} (run once per tick, after the move's steer) drives the break
 * toward whatever was requested and <b>clears its state the moment nothing is requested</b>. So a break only
 * continues while the mover keeps asking for the SAME cell — exactly like holding the mouse button. A missed or
 * mistimed request just means the block is re-checked and re-mined next tick; releasing (no request) aborts and
 * clears the overlay, matching vanilla's "let go and progress resets."
 *
 * <h2>Timing model</h2>
 * Progress accumulates {@link BlockState#getDestroyProgress} per tick — vanilla's own per-tick fraction, which
 * already folds tool tier + Efficiency, on-ground, in-water, and Haste against the block's hardness — so the
 * break lands on the exact tick vanilla would. The actual break is delegated to {@code gameMode.destroyBlock},
 * the survival break path, so drops / XP / tool durability are all vanilla-correct with no re-implementation.
 * Hand-rolling the accumulation (rather than {@code handleBlockBreakAction} + the server tick) keeps precise
 * per-tick control for the timing-sensitive moves to come (parkour landing-place, wall-clutch) and leans only
 * on API stable across 1.17 &rarr; 26.x ({@code getDestroyProgress} / {@code destroyBlockProgress} /
 * {@code destroyBlock}), where the packet handler's signature has drifted.
 */
public final class BotMining {

    private final ServerPlayer bot;
    private final BotInventory inv;

    private BlockPos requested;   // cell requested THIS tick (set by request(), consumed by tick())
    // Tool condition requested for THIS tick's break (Phase 2). Mirrors `requested`'s consume-each-tick
    // lifecycle: EITHER (the default) equips the fastest correct tool as before; a silk condition (from a
    // gather with a drop goal) equips the goal tool so the fastest-tool re-select does NOT override it.
    private DropModel.ToolCondition requestedCondition = DropModel.ToolCondition.EITHER;
    // THIS tick's request is a bot-placed-table reclaim (see requestReclaim) — consumed like `requested`.
    private boolean requestedReclaim;
    // THIS tick's request is a mature-crop harvest (see requestHarvest) — consumed like `requested`.
    private boolean requestedHarvest;
    private BlockPos target;      // cell currently being mined across ticks (null = idle)
    private float progress;       // accumulated destroy progress in [0,1) toward breaking `target`
    private int lastStage = -1;   // last crack-overlay stage 0..9 pushed for `target` (-1 = none shown)

    public BotMining(ServerPlayer bot) {
        this.bot = bot;
        this.inv = new BotInventory(bot);
    }

    /**
     * Ask to mine {@code pos} this tick (idempotent; call every tick you want it gone). Reactive: the break
     * only advances while the same cell keeps being requested, and aborts the tick nothing is.
     */
    public void request(BlockPos pos) {
        request(pos, DropModel.ToolCondition.EITHER);
    }

    /**
     * Ask to mine {@code pos} this tick with a specific drop-goal tool {@code condition} (Phase 2). A silk
     * condition makes {@link #tick} equip the goal tool (silk / no-silk) instead of the fastest correct tool,
     * so a {@code /bot gather stone} silk pickaxe isn't overridden by a faster plain one. The condition, like
     * the requested cell, is consumed each tick (re-request every tick to keep the goal tool held).
     */
    public void request(BlockPos pos, DropModel.ToolCondition condition) {
        this.requested = pos;
        this.requestedCondition = condition == null ? DropModel.ToolCondition.EITHER : condition;
    }

    /**
     * Ask to mine {@code pos} this tick as a RECLAIM of a bot-placed crafting table
     * (DESIGN-bot-abilities.md §10-D3): identical to {@link #request(BlockPos)} except the
     * {@code mining.protectedBlocks} refusal is waived — narrowly: only while the LIVE state at the
     * cell is still a crafting table. The shipped protected-list default includes
     * {@code minecraft:crafting_table} to keep the bot off the OWNER's tables; a temporary table
     * the bot itself just placed for a craft is not the owner's build, so {@code BotCrafter} may
     * take it back. Everything else (timing, drops, overlay, tool) is the normal timed break.
     */
    public void requestReclaim(BlockPos pos) {
        this.requested = pos;
        this.requestedCondition = DropModel.ToolCondition.EITHER;
        this.requestedReclaim = true;
    }

    /**
     * Ask to mine {@code pos} this tick as a HARVEST of a fully-grown crop (DESIGN-bot-abilities.md
     * §4): identical to {@link #request(BlockPos)} except the {@code mining.protectedBlocks}
     * refusal is waived — narrowly: only while the LIVE state at the cell is a known crop at full
     * maturity ({@code CropKinds.byState(...).isMature}). The shipped protected default shields
     * cultivated plants so the PATHFINDER never chews through the owner's farm; harvesting a
     * mature crop (and replanting it — {@code BotFarmer}'s contract) is the stewardship the farm
     * exists for, not wrecking it. An immature or unknown plant still refuses.
     */
    public void requestHarvest(BlockPos pos) {
        this.requested = pos;
        this.requestedCondition = DropModel.ToolCondition.EITHER;
        this.requestedHarvest = true;
    }

    /** Whether a break is currently in progress — for the follower to gate forward motion while digging. */
    public boolean busy() {
        return target != null;
    }

    /**
     * Drive the current break one tick. Call once per tick, AFTER the mover has set its {@link #request} (if
     * any). Equips + faces + swings, accumulates vanilla destroy progress, updates the crack overlay, and does
     * the real survival break when progress completes. With no request pending it aborts and clears any overlay.
     */
    public void tick(ServerLevel level) {
        BlockPos want = this.requested;
        DropModel.ToolCondition cond = this.requestedCondition;
        boolean reclaim = this.requestedReclaim;
        boolean harvest = this.requestedHarvest;
        this.requested = null; // consume; the mover must re-request next tick to keep digging
        this.requestedCondition = DropModel.ToolCondition.EITHER;
        this.requestedReclaim = false;
        this.requestedHarvest = false;

        if (want == null) {                 // nothing requested → release (matches vanilla progress reset)
            stop(level);
            return;
        }
        if (!want.equals(target)) {         // aimed at a new cell → reset progress + move the overlay
            switchTarget(level, want);
        }

        BlockState state = level.getBlockState(target);
        if (state.isAir()) {                // already gone (someone/something cleared it) → done
            stop(level);
            return;
        }

        // Execution-side break policy backstop (planner/executor parity, Config.mayBreak): refuse an
        // owner-protected block (mining.protectedBlocks) outright, and a vanilla-unbreakable one unless
        // mining.allowUnbreakable opted in. The planner's descriptor-bit gates should never request such a
        // break; re-checking the LIVE state here also covers a stale nav grid. Refusal releases the break
        // (like an un-request), so the follower's stall/replan loop routes around it.
        Config cfg = ConfigLoader.config();
        // The two narrow waivers of the protected-block refusal: a RECLAIM may break a crafting
        // table the bot itself placed (§10-D3), and a HARVEST may break a known crop at FULL
        // maturity (§4) — each gated on the LIVE state, so neither can leak onto other blocks.
        final boolean reclaimWaiver = reclaim && state.is(Blocks.CRAFTING_TABLE);
        final boolean harvestWaiver = harvest && isMatureCrop(state);
        if (!reclaimWaiver && !harvestWaiver
                && !cfg.mayBreak(state, state.getDestroySpeed(level, target))) {
            stop(level);
            return;
        }

        // Equip the tool FIRST so the destroy-progress read (and the visible held item) reflect it, then face +
        // swing like a mining player. Default (EITHER) equips the fastest correct tool; a gather with a silk
        // drop goal (Phase 2) requests a condition so the SILK/NO_SILK goal tool is equipped and the fastest-
        // tool re-select can't override it (BotGatherer has already verified a qualifying tool exists).
        if (cond == DropModel.ToolCondition.EITHER) {
            inv.selectBestHotbarTool(state);
        } else {
            inv.equipForCondition(level, state, cond);
        }
        lookAtCenter(target);
        bot.swing(InteractionHand.MAIN_HAND);

        float per = state.getDestroyProgress(bot, level, target);
        // Vanilla reports zero progress for an unbreakable block (negative destroy time). mayBreak above
        // already refused it unless mining.allowUnbreakable — so an opted-in bot GRINDS it at the tool-derived
        // stand-in rate the planner's breakCost charged (parity in time, not just permission): the bot's best
        // PICKAXE tier — measured against a canonical pickaxe block (STONE), the very same probe the planner's
        // inventory snapshot uses — sets the speed, so a diamond pick grinds faster; mining.unbreakableHardness
        // tunes the base. Without the opt-in per <= 0 can no longer occur here.
        boolean grind = per <= 0.0f;
        if (grind) {
            if (!cfg.allowUnbreakable()) { // defensive: mayBreak should have caught it
                stop(level);
                return;
            }
            int tier = MiningModel.classifyTier(inv.bestDestroySpeed(Blocks.STONE.defaultBlockState()));
            per = 1.0f / MiningModel.unbreakableTicks(tier);
        }
        progress += per;
        if (progress >= 1.0f) {
            level.destroyBlockProgress(bot.getId(), target, -1); // clear cracks
            bot.gameMode.destroyBlock(target);                    // real survival break: drops, XP, tool wear
            if (grind && !level.getBlockState(target).isAir()) {
                // The survival break path itself refuses vanilla-unbreakable blocks (that's what makes them
                // unbreakable) — after the honest grind, force the edit the way the legacy applyEdits does.
                WorldEdits.breakBlock(level, target);
            }
            // P7 attribution: one cold line per COMPLETED break (bounded by actual world edits, never
            // per-tick) tying the break to the step frame that requested it — the executor-side half of
            // the plan's brk count.
            if (bot instanceof AllyBotEntity ally) {
                BotNavigator nav = ally.navigator();
                OrebitCommon.LOGGER.info("[Orebit] break executed at ({},{},{}) for step {} -> ({},{},{})",
                        target.getX(), target.getY(), target.getZ(), ally.lastSteerMove,
                        nav.segToX(), nav.segToY(), nav.segToZ());
            }
            reset();
        } else {
            int stage = (int) (progress * 10.0f);                 // vanilla shows 10 crack stages (0..9)
            if (stage != lastStage) {
                level.destroyBlockProgress(bot.getId(), target, stage);
                lastStage = stage;
            }
        }
    }

    /** Whether {@code state} is a known crop at full maturity — the harvest waiver's live gate. */
    private static boolean isMatureCrop(BlockState state) {
        final com.orebit.mod.farming.CropKind kind = com.orebit.mod.farming.CropKinds.byState(state);
        return kind != null && kind.isMature(state);
    }

    /** Point the bot's head (yaw + pitch) at the centre of {@code pos} — the mining look, for the animation. */
    private void lookAtCenter(BlockPos pos) {
        double dx = pos.getX() + 0.5 - bot.getX();
        double dy = pos.getY() + 0.5 - bot.getEyeY();
        double dz = pos.getZ() + 0.5 - bot.getZ();
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, distXZ));
        bot.setYRot(yaw);
        bot.setYHeadRot(yaw);
        bot.setYBodyRot(yaw);
        bot.setXRot(pitch);
    }

    private void switchTarget(ServerLevel level, BlockPos pos) {
        if (target != null) {
            level.destroyBlockProgress(bot.getId(), target, -1); // wipe the old cell's cracks
        }
        target = pos.immutable();
        progress = 0.0f;
        lastStage = -1;
    }

    /** Abort any in-progress break and clear its overlay (nothing requested, block gone, or unbreakable). */
    private void stop(ServerLevel level) {
        if (target != null) {
            level.destroyBlockProgress(bot.getId(), target, -1);
        }
        reset();
    }

    private void reset() {
        target = null;
        progress = 0.0f;
        lastStage = -1;
    }
}
