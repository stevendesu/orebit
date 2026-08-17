package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.EditScratch;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovePlan;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.PathEdits;
import com.orebit.mod.pathfinding.blockpathfinder.SteerControl;
import com.orebit.mod.pathfinding.blockpathfinder.SteerView;
import com.orebit.mod.pathfinding.blockpathfinder.cuboid.Axes;
import com.orebit.mod.pathfinding.blockpathfinder.cuboid.Cuboid;
import com.orebit.mod.pathfinding.blockpathfinder.cuboid.MacroJump;
import com.orebit.mod.pathfinding.blockpathfinder.cuboid.NavGridCuboidsView;

/**
 * Mine straight down one block (MOVEMENT-DESIGN.md §2) — the vertical-in-place counterpart to {@link
 * Pillar}, and the efficient way into a deep pit. The bot breaks the block it stands on and drops one
 * cell onto the block below. Where {@link Descend}'s dig-a-step-down folds ~3 breaks per level (the
 * diagonal transit column), MineDown is ONE break per level straight down — so A* uses it for descending
 * a shaft / deep pit instead of an expensive diagonal staircase, and the search reaches deep goals
 * within budget that the diagonal-only descent couldn't.
 *
 * <p><b>Geometry.</b> Floor cell {@code (x,y,z)} → {@code (x,y-1,z)}. The destination one below must be
 * standable (so the bot lands on solid ground — never "mines down" into a void or lava, which aren't
 * standable). The bot's current floor {@code (x,y,z)} is broken so it can drop into it; the resulting
 * feet cell ({@code y}, now broken air via the {@link com.orebit.mod.pathfinding.blockpathfinder.PathEdits}
 * diff) and head ({@code y+1}, the old feet) are both clear, so the new stand position is valid for the
 * following move.
 *
 * <p><b>Caps.</b> Requires {@link BotCaps#canBreak}; a non-breaking bot emits nothing. The floor must be
 * actually breakable (not bedrock/fluid) and not {@code RISKY_EDIT} (don't undermine a gravity stack —
 * strictly gravity since DESIGN-fluid-flow-prediction.md §4.1; a dig that would flood is PRICED through
 * the {@link EditScratch} fold funnel's verdict, never refused).
 *
 * <h2>Lava exposure (DESIGN-fluid-flow-prediction.md §6, the 2026-08-17 adversarial-review correction)</h2>
 * MineDown is the one move whose own dig CREATES the lava it then stands in: a {@code BROKEN_LAVA} fold
 * verdict (lava above the break, or the lateral funnel's tie) writes lava into the diff at the very cell
 * the bot drops its feet into before digging the next level. That diff-created lava is invisible to the
 * flags-based transit prefilters ({@code flagsAt} never layers {@code PathEdits}) and MineDown calls no
 * transit pricing — so without an explicit term the verdict would choose the edit KIND while adding ZERO
 * cost, and a mortal bot would price a lava-flooding shaft identically to a dry one (a free lethal
 * offer). The fix is the per-level <b>lava-exposure term</b> ({@link #lavaExposure}): each dig level
 * whose FEET cell reads lava through scratch+diff — the mouth pool for level 1, an earlier level's
 * {@code BROKEN_LAVA} chained down the shaft for the rest — charges that level's real mining ticks ×
 * {@link MovementContext#LAVA_HP_PER_TICK} × {@code costPerHitpoint}
 * ({@link MovementContext#lavaExposureCost}), gated on {@link BotCaps#takesDamage}. Physically derived
 * (the bot genuinely burns for the whole dig duration at vanilla's 4-HP-per-10-tick immersion rate), in
 * the one damage currency, and macro == micro exactly: the micro chain's deeper nodes see their
 * ancestors' {@code BROKEN_LAVA} through the diff, the macro loop sees its own earlier folds through the
 * scratch — same cells, same per-level mining ticks, same sum. An immune bot, a dry shaft and a
 * water-flooding shaft (water damages nothing) all add exactly nothing — bit-identical to the term not
 * existing. Lava never joins the submerged mining STANCE (vanilla's eye test is {@code FluidTags.WATER}
 * — the stance stays 1×); exposure is a separate damage term, not a speed penalty.
 *
 * <p><b>Trapdoors — the hatch-drop toggle arm (DESIGN-trapdoors.md §5).</b> The floor cell rides {@link
 * com.orebit.mod.pathfinding.blockpathfinder.EditScratch#requireAirVertical}: when the block stood on is a
 * toggleable CLOSED trapdoor (either half — a closed plate's blocked face is always vertical, and the drop
 * crosses both vertical faces), a {@code SET_OPEN} is folded INSTEAD of the break ({@link
 * MovementContext#DOOR_TOGGLE_COST} vs the real mining ticks — the toggle when permitted, else the break
 * path unchanged; iron / {@code doors.toggle}-off falls through). The opened panel is a wall-hugging
 * vertical, body-passable for the drop and for the landed stance's feet cell (§4), and the standard
 * descend checks run against that OPEN descriptor — "stand on hatch, open it, drop through". Note the
 * {@code canBreak} top gate still applies: a genuinely walk-only bot never runs MineDown at all, hatch or
 * not (an unratified widening deliberately not taken).
 *
 * <h2>Macro collapse (MACRO-IMPLEMENTATION.md §8.1)</h2>
 * A deep shaft is a long uniform run of "break the block underfoot, drop one." Rather than emit one
 * single-step candidate and let A* re-expand the shaft block-by-block, the macro path collapses the whole
 * uniform run into ONE candidate at the jump distance, folding the {@code J} per-step breaks into the
 * {@link EditScratch}. The jump length comes from {@link MacroJump#steps} over the cuboid containing the
 * start cell — so skipping the intermediate cells is sound precisely because the cuboid certifies the
 * orthogonal cross-section is uniform over the whole run (NON-NEGOTIABLE 1) and the escape-hedge keeps the
 * jump from over-shooting a cheaper sideways exit (NON-NEGOTIABLE 2).
 *
 * <p>The macro emit is gated on {@link BlockPathfinder#MACRO_MOVES} AND a non-null {@link
 * MovementContext#cuboids()}. When either is off, this movement reproduces the original single-step micro
 * candidate byte-for-byte — the macro work is a clean, revertible layer over the verified micro search.
 *
 * <p><b>Travel direction:</b> {@code (AXIS_Y, -1)} — straight down. Per step {@code k} (1-based), the move
 * breaks the cell at {@code (x, y-(k-1), z)} (the block the bot would be standing on at the start of step
 * {@code k}) and drops one cell. After {@code J} steps the destination floor is {@code (x, y-J, z)}.
 */
public final class MineDown implements Movement {

    /**
     * Base cost, in <b>ticks</b> = one move step ({@link Traverse#FLAT_COST}) — essentially a one-block Fall
     * (the drop + settle), but the floor must be broken first. That break adds its own REAL mining ticks
     * ({@link MovementContext#breakCost}, the resident-table mining time), so a mine-down step costs {@code
     * FLAT_COST + breakCost} — and a soft shaft (dirt) is cheap to dig while a stone shaft is a real trade-off
     * against routing around. Mining down is chosen only when descending is actually wanted.
     */
    public static final float COST = Traverse.FLAT_COST;

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        if (ctx.mode() != MovementContext.MODE_STANDING) return; // dig-down-in-place — only while upright
        if (!ctx.caps().canBreak()) return;

        int dy = y - 1; // destination floor one below
        int packed = ctx.packedAt(x, dy, z);
        if (packed == MovementContext.UNBUILT) return;
        if (!ctx.standable(ctx.descriptorOf(x, dy, z, packed))) return; // must land on solid ground

        // Break the block the bot currently stands on so it can drop into it. RISKY_EDIT on that cell
        // forbids the break (don't undermine a gravity stack — strictly gravity, DESIGN-fluid-flow-
        // prediction.md §4.1); unbreakable floor → invalid.
        int flags = ctx.flagsAt(x, y, z);

        // ---- Micro fallback: macros off, no cuboid seam (legacy unbounded search), OR (Option B) this
        //      movement's travel axis (Y) is not the search's primary axis P — an off-P movement skips
        //      cuboidAt + MacroJump so a uniform region is extracted on ONE axis only. Byte-for-byte the
        //      original single-step behaviour — emit exactly one mine-down candidate. ----
        NavGridCuboidsView cuboids = ctx.cuboids();
        if (!BlockPathfinder.MACRO_MOVES || cuboids == null || ctx.macroAxis() != Axes.AXIS_Y) {
            EditScratch e = ctx.edits().reset(!MovementContext.risksEdit(flags));
            // The floor stood on: break it — or, when it is a toggleable CLOSED trapdoor, fold the §5
            // SET_OPEN instead (the hatch-drop; class doc) and drop through the opened wall panel.
            e.requireAirVertical(x, y, z);
            if (e.valid()) {
                // Lava-exposure term (see the class doc's "Lava exposure" section): this node digs with
                // its FEET in a cell reading lava (the mouth pool, or an ancestor's BROKEN_LAVA via the
                // diff) — charge the mining time × the vanilla immersion rate in the one damage currency.
                // Dry/water feet, or an immune bot, add exactly nothing (bit-identical costs).
                out.accept(x, dy, z, COST + e.extraCost() + lavaExposure(ctx, e, x, y, z, 0f), e);
            }
            return;
        }

        // ---- Macro path: collapse the uniform shaft into one jump. ----
        // Per-level flood-aware stance (DESIGN-fluid-flow-prediction.md §7, superseding the 2026-08-16
        // one-shot wet-column latch): EditScratch.addBreak's fold funnel decides per broken level whether
        // the opened cell floods (vertical certainty AND the lateral tier-1/2 prediction), and the chain of
        // in-scratch BROKEN_WATER/BROKEN_LAVA kinds is what each deeper level's stance is derived from —
        // so the diff and the price agree level for level, including a shaft that only STARTS flooding at
        // some mid-depth lateral pool. Level 1 prices at the node's own per-pop stance (its eye may still
        // be dry); each level k ≥ 2 digs grounded (standing on the level it breaks) and submerged exactly
        // when ITS head cell — level k−2's already-folded break, or the original mouth for k == 2 — reads
        // water scratch-first (EditScratch.readsWater; lava does not submerge the eye).
        EditScratch e = ctx.edits().reset(!MovementContext.risksEdit(flags));

        // Per-step move cost = base move + the break folded onto each level. Use the REAL break cost of the
        // column substrate (the floor cell the bot is currently standing on), not a literal — cheap moves
        // get large jumps, expensive ones small (NON-NEGOTIABLE 2). The shaft is uniform by construction
        // (the cuboid certifies one navtype), so the start cell's break cost is the per-step edit cost —
        // priced at the DEEP-level stance (pre-queried from the funnel's no-fold probe: a flooding level-1
        // break chains water down the whole shaft), which dominates any jump worth collapsing (J ≥ 2).
        int deepKind = e.peekBreakKind(x, y, z);
        float deepMult = deepKind == PathEdits.BROKEN_WATER
                ? MovementContext.SUBMERGED_MINING_MULT : 1f;
        long shaftDesc = ctx.descriptorAt(x, y, z);
        float moveCost = COST + ctx.breakCost(shaftDesc, deepMult);
        // A lava-flooding shaft's per-step price is DOMINATED by the lava-exposure term (class doc "Lava
        // exposure"): a level-1 BROKEN_LAVA verdict chains lava down the whole shaft exactly as water
        // chains the submerged stance, so include the deep-level exposure in the jump sizing — the
        // escape-hedge must see the real per-step price or it over-jumps a cheaper sideways exit
        // (NON-NEGOTIABLE 2). Zero for an immune bot and on every non-lava shaft (sizing unchanged).
        if (deepKind == PathEdits.BROKEN_LAVA) {
            moveCost += ctx.lavaExposureCost(ctx.breakCost(shaftDesc, deepMult) - ctx.breakBaseCost());
        }

        Cuboid box = ctx.cuboidScratch();
        cuboids.cuboidAt(x, y, z, Axes.AXIS_Y, -1, box); // travel -Y (digs straight down)
        int J = MacroJump.steps(box, x, y, z, Axes.AXIS_Y, -1, moveCost,
                ctx.goalX(), ctx.goalY(), ctx.goalZ());

        // Fold the J per-step breaks into the scratch, replaying the SAME per-step requirement the micro
        // move checks (requireAir at the level broken on step k). Clamp J to the last valid step — an
        // over-claimed jump must SHRINK, never grow (conservative-only, MACRO-MOVEMENTS §3b).
        float exposure = 0f; // Σ per-level lava-exposure (class doc "Lava exposure"); 0 on dry/water shafts
        for (int k = 1; k <= J; k++) {
            int by = y - (k - 1); // the floor cell broken on step k
            // Re-evaluate RISKY_EDIT per level (the micro move does, since each shaft cell is its own node):
            // don't undermine a gravity stack mid-shaft just because the START cell was safe. The start
            // level (k==1) was already gated by the reset() above. Clamp the jump above a risky cell.
            if (k > 1 && MovementContext.risksEdit(ctx.flagsAt(x, by, z))) { J = k - 1; break; }
            // Break — or §5-toggle a closed hatch — the step-k floor. Level 1 digs at the node's own stance;
            // each deeper level at ITS OWN flood-latched stance (the explicit-stance overload): grounded,
            // submerged exactly when the level's head cell (by+2) reads water through the scratch chain.
            float mult = 0f; // ≤ 0 = the node's own per-pop stance (level 1's, matching the micro chain)
            if (k == 1) {
                e.requireAirVertical(x, by, z);
            } else {
                mult = e.readsWater(x, by + 2, z) ? MovementContext.SUBMERGED_MINING_MULT : 1f;
                e.requireAirVertical(x, by, z, mult);
            }
            if (!e.valid()) {                // this level can't be broken — clamp to the last valid step
                J = k - 1;
                break;
            }
            // Per-level lava exposure (class doc "Lava exposure"): step k digs with its feet in the cell
            // level k−1 opened (or the original mouth for k == 1) — its BROKEN_LAVA kind is in THIS
            // scratch, which is why the read must be scratch-first (readsLava), exactly like the stance
            // read above. Charged at step k's own mining ticks, so macro == micro level for level.
            exposure += lavaExposure(ctx, e, x, by, z, mult);
        }
        if (J < 1) return; // nothing valid — not even the first step

        int dz = Axes.stepY(Axes.AXIS_Y, -1) * J; // = -J (the Y delta of a straight-down jump)
        out.accept(x, y + dz, z, J * COST + e.extraCost() + exposure, e);
    }

    /**
     * The per-level <b>lava-exposure</b> term (DESIGN-fluid-flow-prediction.md §6, the 2026-08-17
     * adversarial-review correction — see also the class doc's "Lava exposure" section): the damage cost
     * of digging the level whose floor is {@code (x,by,z)} while the bot's FEET cell {@code (x,by+1,z)}
     * reads lava through this candidate's own scratch first, then the path diff
     * ({@link EditScratch#readsLava}). Zero — with no further reads — for an immune bot or a non-lava
     * feet cell, so dry, water-only and damage-immune searches are bit-identical to the term not
     * existing. When it fires, the exposed time is the level's REAL mining ticks — the same
     * {@link MovementContext#breakCost} the fold just charged ({@code stanceMult ≤ 0} = the node's
     * per-pop stance, the {@link EditScratch#requireAirVertical(int, int, int, float)} convention) minus
     * the non-time {@code mining.breakBaseCost} surcharge — priced at
     * {@link MovementContext#lavaExposureCost}'s vanilla immersion rate. A level that folds NO break (a
     * diff-opened cell passed free, or a §5 hatch-toggle) spends no mining time standing still and
     * charges nothing, matching the fold's own zero mining cost for that level.
     */
    private static float lavaExposure(MovementContext ctx, EditScratch e, int x, int by, int z,
                                      float stanceMult) {
        if (!ctx.caps().takesDamage() || !e.readsLava(x, by + 1, z)) return 0f;
        long fd = ctx.descriptorAt(x, by, z); // the pre-break level cell (scratch folds are invisible here)
        if (ctx.bodyPassable(fd) || ctx.trapdoorSetClearsVertical(fd)) return 0f; // no mining at this level
        float miningTicks = (stanceMult > 0f ? ctx.breakCost(fd, stanceMult) : ctx.breakCost(fd))
                - ctx.breakBaseCost();
        return ctx.lavaExposureCost(miningTicks);
    }

    /**
     * Stay centred on the shaft column while digging down: face the column and apply forward input only
     * proportional to any horizontal drift, so the bot recentres before each break-and-drop instead of
     * walking off the shaft. Gravity does the descent; no jump.
     */
    @Override
    public void steer(BotSteering b, SteerView path) {
        SteerControl.recenterOnTarget(b, path);
    }

    /**
     * The phase-model execution plan (the reactive counterpart to {@link Pillar#plan}). MineDown is a pure
     * vertical shaft: the bot stands on the from-floor {@code (fx,fy,fz)} and drops to stand on the to-floor
     * {@code (fx,ty,fz)}, breaking every block between the two floors <b>top-down, one per tick</b> — the
     * destination floor {@code ty} is the standable landing and is never broken. Unlike Pillar there is no
     * kinematic handoff: the break is on the block directly under the feet, which a grounded player mines and
     * falls into (the natural action), so a <b>single</b> {@code "descend"} phase is both sufficient and
     * cleanest.
     *
     * <p><b>Need ordering is load-bearing, not cosmetic.</b> {@code PhaseRunner} mines the FIRST still-solid
     * {@link MovePlan.Need#AIR} need each tick, holding until it turns to air before advancing. Declared
     * top-down ({@code fy} first, {@code ty+1} last), the first solid cell is always the one directly beneath
     * the bot's feet as it descends: break {@code fy} → fall onto {@code fy-1} → {@code fy-1} is now the first
     * solid → break it → … → land on {@code ty}. A bottom-up list would target {@code ty+1} (the shaft floor,
     * far below and out of reach) first — wrong and unreachable.
     *
     * <p>The {@code AIR} need set is exactly the {@code J}-cell fold {@code candidates()} folds into its
     * {@link EditScratch} ({@code requireAir} at {@code (fx, fy..ty+1, fz)}) — a complete, exact cover, with
     * <b>zero placements</b> (MineDown folds no {@code requireFloor}). Body clearance at the landing needs no
     * extra need: feet cell {@code ty+1} is the last broken cell, and head cell {@code ty+2} is either a broken
     * cell (for {@code J≥2}) or the old head, clear by the node body-clearance invariant (for {@code J==1}).
     *
     * <p><b>No {@code resetWhen}.</b> The plan is single-phase (the cursor never leaves 0, and the runner only
     * consults {@code resetWhen} while {@code cursor > 0}), and MineDown is monotonic/irreversible: once the
     * first block {@code fy} is broken it no longer exists, so the bot cannot fall back to its start floor —
     * there is no regression state to recover from (contrast Pillar, whose footing can fail to take). Stranded
     * cases are handled by the follower's grounded-stall recovery + replan.
     *
     * <p>This is the fix for the instant-multi-block-drop bug: the legacy path replays all {@code J} folded
     * breaks through {@code applyEdits} in one shot (the whole shaft vanishes on one tick and the bot free-falls
     * {@code J} blocks at once). Routing the same breaks through {@code plan()} makes each a {@code Need.AIR}
     * cleared by the timed, one-break-per-tick {@code bot.mine} loop — combined with the top-down ordering the
     * runner always breaks exactly the block underfoot, the bot falls one cell, then the next break begins,
     * pacing the descent to real mining speed.
     */
    @Override
    public MovePlan plan(int fx, int fy, int fz, int tx, int ty, int tz, int fromFootY, int toFootY) {
        final int landedFeetBlockY = toFootY; // feet BLOCK Y once standing on the destination floor (topY-aware)
        MovePlan plan = new MovePlan();

        MovePlan.Phase descend = plan.phase("descend");
        // Declare the breaks TOP-DOWN: fy first, ty+1 last. Ordering is REQUIRED (see javadoc) — the runner
        // mines the first still-solid AIR need, always the cell directly under the descending feet.
        for (int j = fy; j >= ty + 1; j--) {
            descend.need(MovePlan.Need.AIR, fx, j, fz);
        }
        descend
                .drive(SteerControl::recenterOnTarget) // hold the column; gravity does the descent, no jump
                // Complete only once actually STANDING on the destination floor. PhaseRunner short-circuits
                // while any AIR need is still solid, so this is evaluated only after the whole shaft is air.
                .done(b -> b.grounded() && atWaypoint(b, fx, landedFeetBlockY, fz));

        return plan;
    }
}
