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
        this.requested = null; // consume; the mover must re-request next tick to keep digging
        this.requestedCondition = DropModel.ToolCondition.EITHER;

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

        // These hands are the DELIBERATE-action path (an owner-commanded gather target, a harvest,
        // a table reclaim, /bot mine) — mining.protectedBlocks is a PATHING policy (owner ruling,
        // 2026-07-29): it stops the PLANNER and the path executor (applyEdits/place, the gather
        // LOS-occluder dig, builder clears, the PhaseRunner reconcile's mine seam in
        // AllyBotEntity.mine — each of those call sites checks Config.mayBreak itself) from
        // chewing through builds en route, and deliberately does NOT gate a task that exists to
        // break the block (protecting logs must not refuse `/bot gather wood`).
        // The one physics gate that DOES belong here: a vanilla-unbreakable block (negative
        // destroy time — bedrock, barriers, …) still refuses without the mining.allowUnbreakable
        // opt-in; the opted-in grind below handles the rest.
        Config cfg = ConfigLoader.config();
        if (state.getDestroySpeed(level, target) < 0 && !cfg.allowUnbreakable()) {
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
            // Announce the mutation BEFORE it lands, so the plan's own prescribed break is not read back as
            // the world diverging from it (PathPlan.expectOwnEdit). This is the completion point — mine() is
            // re-issued every tick while the break runs, and only the next line changes the world — and the
            // announcement is a one-shot slot consumed by the change it predicts, so it must precede it.
            if (bot instanceof AllyBotEntity pre) {
                pre.navigator().expectOwnEdit(target.getX(), target.getY(), target.getZ(), true);
            }
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

    /**
     * Re-apply the current target's head-aim. Safe to call at ANY point in the tick, including before
     * {@code doTick}: {@link #lookAtCenter} routes through
     * {@link com.orebit.mod.pathfinding.blockpathfinder.BotSteering#aimPreservingInput}, which re-solves
     * the movement keys so the commanded world-space velocity survives the yaw change.
     *
     * <p><b>History, because the naive version was shipped and had to be reverted.</b> An earlier build
     * called this ahead of {@code doTick} and justified it as "visuals only". It was not: yaw is half of
     * the velocity command, so aiming the head at the block also aimed the bot's THRUST at it, and the bot
     * drove into the very block it was mining. The straddle probe caught it — the exec line logged the
     * servo's yaw sweeping 174 to 126 degrees while physics used a near-constant -101, which is exactly
     * atan2 toward the target's centre. The aim itself was never the problem; writing half a command was.
     */
    public void reaim() {
        if (target != null) {
            lookAtCenter(target);
        }
    }

    /**
     * Point the bot's head (yaw + pitch) at the centre of {@code pos} — the mining look.
     *
     * <p>Routed through {@code aimPreservingInput} for an {@link AllyBotEntity}, so the yaw change carries
     * the movement keys with it and the COMMANDED velocity is unchanged (see {@link #reaim}). A bare
     * {@code setYRot} here is a steering change, not an animation: vanilla thrusts {@code zza}/{@code xxa}
     * along the yaw, so re-aiming without re-solving the keys silently re-points — and near 180 degrees
     * inverts — whatever drive was standing. Aiming at feet/head cells (a {@code dy} of either sign) is
     * exactly when the yaw swing is largest, which is when getting this wrong hurts most.
     *
     * <p>The plain-{@code ServerPlayer} fallback keeps the old behaviour for any non-Orebit bot: those
     * write no movement keys, so there is no pair to preserve.
     */
    private void lookAtCenter(BlockPos pos) {
        double dx = pos.getX() + 0.5 - bot.getX();
        double dy = pos.getY() + 0.5 - bot.getEyeY();
        double dz = pos.getZ() + 0.5 - bot.getZ();
        if (bot instanceof AllyBotEntity ally) {
            ally.aimPreservingInput(dx, dy, dz);
            return;
        }
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
