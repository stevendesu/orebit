package com.orebit.mod;

import com.orebit.mod.config.Config;
import com.orebit.mod.config.ConfigLoader;
import com.orebit.mod.pathfinding.blockpathfinder.MiningModel;
import com.orebit.mod.platform.BotInventory;
import com.orebit.mod.platform.WorldEdits;

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
        this.requested = pos;
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
        this.requested = null; // consume; the mover must re-request next tick to keep digging

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
        if (!cfg.mayBreak(state, state.getDestroySpeed(level, target))) {
            stop(level);
            return;
        }

        // Equip the fastest hotbar tool FIRST so the destroy-progress read (and the visible held item) reflect
        // it, then face + swing like a mining player.
        inv.selectBestHotbarTool(state);
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
            reset();
        } else {
            int stage = (int) (progress * 10.0f);                 // vanilla shows 10 crack stages (0..9)
            if (stage != lastStage) {
                level.destroyBlockProgress(bot.getId(), target, stage);
                lastStage = stage;
            }
        }
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
