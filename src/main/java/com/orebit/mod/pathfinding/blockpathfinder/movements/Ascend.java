package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.EditScratch;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;

/**
 * Jump up one block onto a cardinal-adjacent floor cell that's a full step up (MOVEMENT-DESIGN.md §2,
 * Tier 1). Distinct from {@link Traverse}'s step-assist: this is a destination whose collision top is
 * <i>above</i> {@link MovementContext#STEP_ASSIST_MAX_TOP_Y}, so gaining it needs a real jump.
 *
 * <p><b>The head-clearance fix.</b> A jump from the source column needs the cell <i>above the bot's own
 * head</i> (source {@code y+3}) clear, or the bot bonks the ceiling and never gains the block — the cell
 * the floor-centric grid can't represent (the "head-in-block" / "2-high dirt wall reads as a step" class
 * of bug, commit {@code 7beda91}). Both that and the landing body clearance are now read through the
 * resident HEADROOM bit: the source's own feet/head are already clear (the bot stands there), so its
 * HEADROOM is {@code JUMP} exactly when {@code y+3} is clear; the landing needs {@code WALK}. Cells the
 * bit can't prove (near a section face, or genuinely blocked) are read and — when the bot may break and
 * the edit isn't {@code RISKY_EDIT} — folded into a break-set.
 *
 * <p><b>Break / place modifiers (MOVEMENT-DESIGN §1, decision 1).</b> Two folds give the bot upward
 * mobility through this one kind: a blocked body/takeoff cell is <i>broken</i> (dig a staircase up into a
 * hillside), and a missing destination floor is <i>placed</i> — including, when the footing one-up-and-over
 * has no face of its own, a second <b>support</b> block beneath it placed against the floor the bot stands
 * on ({@link com.orebit.mod.pathfinding.blockpathfinder.EditScratch#requireFootingOn the two-block step}).
 * So repeated Ascend+place builds a diagonal staircase up through <i>open air</i> — off a ledge, out of a
 * cave, up to a hovering owner — not just up against existing terrain. It's two placements per step, so A*
 * picks it only when it's the cheapest way up; a straight vertical Pillar (cheaper per block, but a
 * one-way death-trap you can't descend) stays a separate kind the search will weigh against this once built.
 */
public final class Ascend implements Movement {

    /**
     * Base cost, in <b>ticks</b> = one walk step ({@link Traverse#FLAT_COST}). Ascending is "walk forward
     * while jumping": the jump impulse overlaps the forward motion, so gaining a horizontal AND a vertical
     * cell up an existing step takes about as long as a flat walk — climbing pre-existing terrain (stairs,
     * a hillside) is no dearer than a Traverse, matching Baritone, whose {@code MovementAscend} charges
     * ≈ {@code WALK_ONE_BLOCK_COST} for the traversal and adds the build cost only when it must place. A
     * folded placement (building a step in open air) adds its own real place ticks ({@link
     * MovementContext#placeCost}), so building up is naturally avoided unless it's the only way.
     */
    public static final float COST = Traverse.FLAT_COST;

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        if (ctx.caps().jumpHeight() < 1) return;
        int uy = y + 1;

        // Source facts are the same for all four directions — read once. The bot stands on (x,y,z) so its
        // feet/head are clear; HEADROOM == JUMP iff the takeoff head-clearance (y+3) is also clear.
        int srcFlags = ctx.flagsAt(x, y, z);
        boolean srcClear = ctx.headroomProves(srcFlags, y, MovementContext.HEADROOM_JUMP);
        boolean srcRisky = MovementContext.risksEdit(srcFlags);

        for (int[] d : CARDINALS) {
            int nx = x + d[0];
            int nz = z + d[1];

            // The destination floor (nx,uy,nz) is read three ways below (standable, topY, flags) — resolve
            // its grid slot ONCE and derive each from it.
            int dstPacked = ctx.packedAt(nx, uy, nz);
            if (dstPacked == MovementContext.UNBUILT) continue;
            long dstDesc = ctx.descriptorOf(nx, uy, nz, dstPacked);

            // A low partial already one up is Traverse's step-assist (a no-jump auto-step), not an Ascend —
            // leave it to Traverse. (Only when footing already exists; a placed step is a full block.)
            boolean dstStandable = ctx.standable(dstDesc);
            if (dstStandable && ctx.topYOf(dstDesc) <= MovementContext.STEP_ASSIST_MAX_TOP_Y) continue;

            int dstFlags = MovementContext.flagsOf(dstPacked);
            EditScratch e = ctx.edits().reset(!(srcRisky || MovementContext.risksEdit(dstFlags)));
            // Footing: stand on the block that's there, or BUILD A STEP UP. If the footing one-up-and-over
            // has no face of its own (open air / a ledge), a support block is placed beneath it against the
            // floor the bot stands on, then the footing on top — the two-block staircase step. requireFootingOn
            // folds 0, 1 or 2 places; invalid if the bot can't place or the spot is RISKY_EDIT.
            if (!dstStandable) e.requireFootingOn(nx, uy, nz, nx, y, nz);
            // The takeoff head-clearance (source y+3) and the landing body (feet+head) must be clear; cells
            // the HEADROOM bit can't prove are read and — when allowed — folded into a break-set (dig up).
            if (!srcClear) e.requireAir(x, y + 3, z);
            ctx.requireBodyClear(e, nx, uy, nz, dstFlags);
            if (e.valid()) out.accept(nx, uy, nz, COST + e.extraCost(), e);
        }
    }
}
