package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.MovePlan;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.SteerControl;

/**
 * Ride an <b>UP bubble column</b> — the soul-sand conveyor (MOVEMENT-DESIGN.md, Tier 1 water; owner-ratified
 * UP-only scope). A bubble column is a directional water conveyor: an UP column (soul-sand floor,
 * {@code DRAG_DOWN=false}) pushes an entity upward (capped ~+0.7 b/tick submerged, ~+1.8 at the surface —
 * strong enough to eject the rider above the surface). You canNOT reliably hold position mid-column or step
 * out partway; only a <b>same-Y lateral exit at the ENDS</b> is reasonable. The descriptor already classifies
 * these cells ({@link MovementContext#bubbleUp}, from {@link net.minecraft.world.level.block.BubbleColumnBlock}'s
 * {@code DRAG_DOWN}), and they are otherwise WALLED OFF from the swim/walk vocabulary
 * ({@link MovementContext#water}/{@link MovementContext#passable} both reject a bubble cell), so this is the
 * ONE move that ever enters one — as a single MACRO candidate whose interior is never a resting node.
 *
 * <h2>Candidate geometry (why exactly this)</h2>
 * <ol>
 *   <li><b>Entry — lateral, from a STANDING node.</b> The move initiates only from a {@link
 *       MovementContext#MODE_STANDING} node (a bot on land / treading a surface — a submerged bot is
 *       {@code MODE_PRONE}, out of UP-only v1 scope): from floor cell {@code (x,y,z)} the bot's feet layer is
 *       {@code fy = y+1}, and a cardinal neighbour {@code (cx,fy,cz)} that is an {@link MovementContext#bubbleUp
 *       UP-column} cell is the column it swims laterally into (owner: allow lateral entry, the ride always ends
 *       at the top). Bubble cells can't be search nodes, so the candidate spans directly from the START node to
 *       the TOP exit node — the interior is never visited.</li>
 *   <li><b>Ride scan — to the top.</b> From the entry feet layer, climb the CONTIGUOUS {@code BUBBLE_UP} column
 *       up to its top cell ({@code topY} = the highest still-bubble feet layer; the first non-bubble cell above
 *       ends it). Vanilla columns are contiguous by construction (bubbles propagate up from the source), so a
 *       straight upward scan is exact. The column may terminate mid-water OR reach the surface — the cell
 *       directly above the top bubble decides which.</li>
 *   <li><b>Destination — a NORMAL node beside the top.</b> Every exit is a lateral cardinal neighbour of the
 *       COLUMN, emitted {@link MovementContext#MODE_STANDING} (nothing operates on a bubble cell, so the bot
 *       must land on an ordinary standable/swimmable cell from which walk/Surface/Start-sprint-swim resume):
 *       <ul>
 *         <li><b>Bank exit</b> (feet layer {@code topY+1}, node {@code (ex,topY,ez)}): a standable floor at
 *             {@code topY} with two clear body cells above — step out onto land flush with the column top. The
 *             classic soul-sand elevator: ride up, walk onto the shore. Works for both terminations (a bank
 *             beside a surface column, or a rare underwater ledge). Damaging bank floor priced by {@link
 *             MovementContext#floorHazardCost}.</li>
 *         <li><b>Mid-water float-out</b> (feet layer {@code topY+1}, node {@code (ex,topY,ez)}): when the
 *             column tops out into MORE water (the cell above the top bubble is swimmable), a lateral neighbour
 *             whose feet cell is swimmable water — the bot floats up off the last bubble and swims out sideways.
 *             The head is unconstrained: a submerged exit is handed to {@link StartSprintSwim} (2-deep → prone
 *             in place), a surfaced one to {@link Swim}.</li>
 *         <li><b>Surface float-out</b> (feet layer {@code topY}, node {@code (ex,topY-1,ez)}): when the column
 *             reaches the SURFACE (the cell above the top bubble is open air), the water surface IS the top
 *             bubble cell, so a lateral neighbour that is a surface-swim cell (feet water, head air) at that
 *             same level is the same-Y swim-out into the surrounding pool.</li>
 *       </ul>
 *       Bank (feet air) and mid-water float-out (feet water) are mutually exclusive per neighbour; surface
 *       float-out is one level lower, so a neighbour can at most contribute one node per level.</li>
 * </ol>
 *
 * <h2>Cost</h2>
 * {@link #RIDE_BASE} + {@code height ·} {@link #RIDE_PER_BLOCK}, in the tick currency, where {@code height =
 * topY − fy} is the blocks risen. {@link #RIDE_PER_BLOCK} = {@code 1/0.7 ≈ 1.43} ticks/block — the reciprocal
 * of the ~0.7 b/tick submerged conveyor push (the physical time to rise one block). {@link #RIDE_BASE} = a
 * small fixed allowance covering the lateral swim-in at the bottom + the settle/step-out at the top. Both terms
 * are non-negative and derived from the push constant; the ride cost is well above the search's minimum step
 * (≥ {@code RIDE_BASE}), so the octile heuristic stays admissible. This move fires only adjacent to a bubble
 * column (rare), so it adds no cost to the common per-node expansion beyond one {@code bubbleUp} probe.
 *
 * <h2>Follower — three STATE phases, no timers (no-arbitrary-timers)</h2>
 * The conveyor drives the vertical velocity; the follower must NOT fight it (an input-based {@code holdDepth}'s
 * ±0.04 can't overcome the push — that is exactly why bubble cells are walled off). So the plan is:
 * <ul>
 *   <li><b>ENTER</b> — swim laterally into the adjacent up-column (scan the feet-level cardinals via {@link
 *       BotSteering#bubbleUpAt}, face + forward). Advances once the bot is in water and its own feet cell is an
 *       up-column (it is being carried).</li>
 *   <li><b>RIDE</b> — hold horizontal centre on the bot's OWN block column (it is inside the column now), press
 *       NO jump/sink, and let the conveyor lift. Advances once the bot has risen to the exit level or left the
 *       column (feet no longer an up-column).</li>
 *   <li><b>SETTLE</b> — steer laterally to the exit cell and report done only once there AND vertically settled,
 *       by a <b>medium-aware</b> test: a grounded land exit is settled BY being {@code grounded()} (like every
 *       other ground move); the {@code |velY| <} {@link #SETTLE_VELY} stillness gate is applied ONLY to the
 *       buoyant in-water float-out (a bank bot's resting velY ≈ −0.078 never bleeds below SETTLE_VELY, so gating
 *       land on it would freeze the follower). So the next lateral move starts from rest, not mid-launch.
 *       State-based (grounded/velocity + medium), no tick counter.</li>
 * </ul>
 * The whole ride is an IRREVERSIBLE commitment ({@link #commitsAcrossArrival} — once carried up you cannot stop
 * mid-column), so a goal near a mid-column cell must not preempt it, exactly like a parkour arc.
 *
 * <p><b>Headless-testable seam:</b> the candidate geometry + cost read only through {@link MovementContext}
 * (MC-free), so they are unit-tested against a synthetic {@code NavGridView}. The follower phases exercise
 * bubble-column PHYSICS the synthetic grid does not simulate, so they are verified in-game (owner's domain),
 * like every other swim follower.
 */
public final class RideBubbleColumn implements Movement {

    /**
     * Per-block ride cost (ticks) = {@code 1 / 0.7 ≈ 1.43} — the reciprocal of the ~0.7 blocks/tick submerged
     * upward push of an UP bubble column, i.e. the physical time to be carried one block higher. Derived from
     * the push constant so it re-scales if the measured push is refined.
     */
    public static final float RIDE_PER_BLOCK = 1f / 0.7f;

    /**
     * Fixed ride allowance (ticks) added once per ride, independent of height — the lateral swim-in at the
     * bottom plus the settle/step-out at the top (~2 pose-transition steps' worth, cf. {@link Surface#COST} /
     * {@link StartSprintSwim#COST} of {@code 2} each). Keeps a zero-height ride (surface-adjacent entry)
     * priced above the search minimum step so the heuristic stays admissible.
     */
    public static final float RIDE_BASE = 4f;

    /** Vertical-velocity threshold (blocks/tick) below which the top ejection is treated as SETTLED —
     *  <b>the WATER-only stillness gate</b>. It applies ONLY to the buoyant in-water float-out exit, whose velY
     *  oscillates ±0.04 while the bot bobs; sized just above that ±0.04 increment so a floating bot reads settled
     *  and a freshly-ejected one does not. A GROUNDED (dry bank) exit does NOT use this gate — a resting bot's
     *  velY stays at gravity×drag ≈ −0.078 (ground collision never zeroes {@code getDeltaMovement().y}), so a
     *  {@code |velY|<SETTLE_VELY} test would NEVER fire on land and would freeze the follower (no recovery); a
     *  grounded exit is settled BY being grounded, like every other ground move. See {@link #reached}. */
    public static final double SETTLE_VELY = 0.06;

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        if (ctx.mode() != MovementContext.MODE_STANDING) return; // ride initiates from a standing/tread node
        int fy = y + 1;                                          // the bot's feet layer

        for (int[] d : CARDINALS) {
            int cx = x + d[0], cz = z + d[1];                   // candidate column (a cardinal neighbour)
            // Entry: a laterally-adjacent UP-column cell at the bot's feet level (owner: allow lateral entry).
            if (!ctx.built(cx, fy, cz) || !ctx.bubbleUp(cx, fy, cz)) continue;

            // Ride scan: climb the contiguous BUBBLE_UP column to its top cell (first non-bubble above ends it).
            int topY = fy;
            while (ctx.bubbleUp(cx, topY + 1, cz)) topY++;      // bubbleUp is false for unbuilt/air ⇒ terminates

            float rideCost = RIDE_BASE + (topY - fy) * RIDE_PER_BLOCK;

            // What sits directly above the top bubble decides the exit geometry.
            long aboveDesc = ctx.built(cx, topY + 1, cz) ? ctx.descriptorAt(cx, topY + 1, cz) : 0L;
            boolean builtAbove = ctx.built(cx, topY + 1, cz);
            boolean aboveWater = builtAbove && ctx.water(aboveDesc);                    // mid-water termination
            boolean aboveAir = builtAbove && ctx.passable(aboveDesc) && !ctx.water(aboveDesc); // surface reached

            for (int[] e : CARDINALS) {
                int ex = cx + e[0], ez = cz + e[1];

                // Exit A — step/float out at feet layer topY+1 (node floor = topY).
                if (ctx.built(ex, topY, ez) && ctx.built(ex, topY + 1, ez) && ctx.built(ex, topY + 2, ez)) {
                    long floorDesc = ctx.descriptorAt(ex, topY, ez);
                    if (ctx.standable(floorDesc)
                            && ctx.passable(ex, topY + 1, ez) && ctx.passable(ex, topY + 2, ez)) {
                        // Bank: solid footing flush with the column top + two clear body cells (feet air).
                        out.accept(ex, topY, ez, rideCost + ctx.floorHazardCost(floorDesc),
                                MovementContext.MODE_STANDING);
                    } else if (aboveWater && ctx.water(ex, topY + 1, ez)) {
                        // Mid-water float-out: the column tops into water and the neighbour feet is swimmable
                        // (feet water — mutually exclusive with the bank case). Head free (Surface / StartSprintSwim take over).
                        out.accept(ex, topY, ez, rideCost, MovementContext.MODE_STANDING);
                    }
                }

                // Exit B — surface float-out at feet layer topY (node floor = topY-1), only when the column
                // reached the surface (open air above the top bubble). A same-Y lateral swim-out into the
                // surrounding surface pool: neighbour feet swimmable water, head open air.
                if (aboveAir && ctx.built(ex, topY, ez) && ctx.built(ex, topY + 1, ez)
                        && ctx.water(ex, topY, ez)
                        && ctx.passable(ex, topY + 1, ez) && !ctx.water(ex, topY + 1, ez)) {
                    out.accept(ex, topY - 1, ez, rideCost, MovementContext.MODE_STANDING);
                }
            }
        }
    }

    /**
     * The ride is an irreversible cross-arrival commitment (once the conveyor carries the bot up it cannot stop
     * mid-column), so a goal within the arrival radius of a mid-column cell must not preempt it — the same rule
     * a parkour arc uses (see {@link Movement#commitsAcrossArrival}).
     */
    @Override
    public boolean commitsAcrossArrival() {
        return true;
    }

    @Override
    public boolean reached(BotSteering b, int wx, int wy, int wz) {
        // Arrived only once at the exit cell AND vertically settled. The settle test is MEDIUM-AWARE: a grounded
        // land exit is settled BY being grounded (like every other ground move — a resting bot's velY stays at
        // gravity×drag ≈ −0.078, never zeroed by ground collision, so a |velY|<SETTLE_VELY gate would NEVER fire
        // on a dry bank and freeze the follower); the velY-stillness gate applies ONLY to the buoyant in-water
        // float-out (velY oscillates ±0.04 while bobbing). Y is not block-matched — a water exit bobs.
        return b.footX() == wx && b.footZ() == wz
                && (b.grounded() || (b.inWater() && Math.abs(b.velY()) < SETTLE_VELY));
    }

    @Override
    public MovePlan plan(int fx, int fy, int fz, int tx, int ty, int tz) {
        MovePlan plan = new MovePlan();
        // ENTER: swim laterally into the adjacent up-column. Done once carried (in water + feet in the column).
        plan.phase("enter")
                .drive((b, v) -> driveIntoColumn(b))
                .advanceWhen(b -> b.inWater() && b.bubbleUpAt(b.footX(), b.footY(), b.footZ()));
        // RIDE: hold horizontal centre on the bot's own column; NO jump/sink — the conveyor drives velY. Done
        // once risen to the exit level or out of the column (feet no longer an up-column).
        plan.phase("ride")
                .drive((b, v) -> holdColumn(b))
                .advanceWhen(b -> b.footY() >= ty || !b.bubbleUpAt(b.footX(), b.footY(), b.footZ()));
        // SETTLE: steer laterally to the exit cell; done once there AND vertically settled. Medium-aware, mirror
        // of reached(): a grounded land exit settles on grounded(); the |velY|<SETTLE_VELY stillness gate is
        // water-only (a resting bank bot's velY ≈ −0.078 never bleeds below SETTLE_VELY).
        plan.phase("settle")
                .drive((b, v) -> { b.setSprinting(false); SteerControl.recenterOnTarget(b, v); })
                .done(b -> b.footX() == tx && b.footZ() == tz
                        && (b.grounded() || (b.inWater() && Math.abs(b.velY()) < SETTLE_VELY)));
        return plan;
    }

    /** ENTER drive: face + forward toward the up-column found at a feet-level cardinal neighbour (state probe,
     *  no timer); nothing found (drifted) → hold. Never sprints (a slow, controlled entry into the conveyor). */
    private static void driveIntoColumn(BotSteering b) {
        int bx = b.footX(), by = b.footY(), bz = b.footZ();
        for (int[] d : CARDINALS) {
            if (b.bubbleUpAt(bx + d[0], by, bz + d[1])) {
                b.faceHorizontally(d[0], d[1]);
                b.setForward(1.0f);
                b.setSprinting(false);
                return;
            }
        }
        b.setForward(0.0f); // no adjacent column at feet level — hold rather than wander
    }

    /** RIDE drive: hold horizontal centre on the bot's OWN block column (it is inside the column) with a
     *  proportional pull, and press NO vertical input — the up-column conveyor owns velY. */
    private static void holdColumn(BotSteering b) {
        double cx = (b.footX() + 0.5) - b.x();
        double cz = (b.footZ() + 0.5) - b.z();
        double dist = Math.sqrt(cx * cx + cz * cz);
        if (dist > 1.0e-4) {
            b.faceHorizontally(cx, cz);
            b.setForward((float) Math.min(1.0, dist)); // ~0 when centred, re-pushes on drift toward the flank
        } else {
            b.setForward(0.0f);
        }
        b.setSprinting(false);
    }
}
