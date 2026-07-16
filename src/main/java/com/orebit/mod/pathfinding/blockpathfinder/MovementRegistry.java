package com.orebit.mod.pathfinding.blockpathfinder;

import java.util.List;

import com.orebit.mod.pathfinding.blockpathfinder.movements.Ascend;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Climb;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Descend;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Diagonal;
import com.orebit.mod.pathfinding.blockpathfinder.movements.DiagonalParkour;
import com.orebit.mod.pathfinding.blockpathfinder.movements.DiagonalSprintSwim;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Fall;
import com.orebit.mod.pathfinding.blockpathfinder.movements.MineDown;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Parkour;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Pillar;
import com.orebit.mod.pathfinding.blockpathfinder.movements.RideBubbleColumn;
import com.orebit.mod.pathfinding.blockpathfinder.movements.SprintSwim;
import com.orebit.mod.pathfinding.blockpathfinder.movements.StartSprintSwim;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Surface;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Swim;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Traverse;
import com.orebit.mod.pathfinding.blockpathfinder.movements.WalkOff;

/**
 * The set of {@link Movement} strategies the block A* expands each node with (MOVEMENT-DESIGN.md §7).
 * Movements are stateless singletons, so one shared, immutable list serves every pathfind. Widening the
 * bot's repertoire (climb, parkour, swim, break/place — Tiers 2–3) is adding an entry here plus its
 * class; the search loop and the existing movements never change.
 */
public final class MovementRegistry {

    private MovementRegistry() {}

    public static final Movement TRAVERSE = new Traverse();
    public static final Movement DIAGONAL = new Diagonal();
    public static final Movement ASCEND = new Ascend();
    public static final Movement DESCEND = new Descend();
    public static final Movement FALL = new Fall();
    public static final Movement PILLAR = new Pillar();
    public static final Movement MINE_DOWN = new MineDown();
    public static final Movement SWIM = new Swim();
    public static final Movement SPRINT_SWIM = new SprintSwim();
    public static final Movement START_SPRINT_SWIM = new StartSprintSwim();
    public static final Movement SURFACE = new Surface();
    public static final Movement CLIMB = new Climb();
    public static final Movement PARKOUR = new Parkour();
    public static final Movement DIAGONAL_PARKOUR = new DiagonalParkour();
    public static final Movement WALK_OFF = new WalkOff();
    public static final Movement DIAGONAL_SPRINT_SWIM = new DiagonalSprintSwim();
    public static final Movement RIDE_BUBBLE_COLUMN = new RideBubbleColumn();

    /**
     * Tier 1 (ground + water): walk + step-assist, diagonal walk, jump-up-1, step-down-1, safe drop, the
     * vertical-in-place pair pillar-up / mine-down, the water pair normal-swim / sprint-swim, the
     * pose-transition pair start-sprint-swim / surface (STANDING↔PRONE, the stateful sprint-swim rule),
     * ladder/vine climb, and the gap-jump pair (cardinal parkour — flat/rising/falling landings — and its
     * diagonal counterpart, mirroring the Traverse/Diagonal split).
     * Every move self-gates: the ground moves on {@code MODE_STANDING}, the sprint-swim + surface on
     * {@code MODE_PRONE}, Pillar/MineDown on place/break caps, the swim moves on the presence of water, the
     * climb on a climbable feet/neighbour cell — so a walk-only bot on dry land still gets only the plain
     * ground moves and never changes pose. WalkOff (a no-jump gap-1/descend-1 crossing) is appended last;
     * it self-gates on a jump-REFUSED start cell (honey / cobweb-body / low ceiling), so where a jump is
     * legal Parkour owns the destination and WalkOff stays silent — the honey crosser without a hijack.
     *
     * <p><b>Order matters on cost ties only</b>: relaxation rejects non-strict improvements, so the
     * earlier-listed movement wins an equal-g destination. New movements are appended at the END so the
     * verified moves keep tie priority (Climb's grab ties Traverse's flat step at {@code FLAT_COST}; the
     * plain walk should win the tie); {@code nodes.move[]} stores the per-search list index and is never
     * persisted, so appending is unconditionally safe.
     */
    public static final List<Movement> TIER1 =
            List.of(TRAVERSE, DIAGONAL, ASCEND, DESCEND, FALL, PILLAR, MINE_DOWN, SWIM, SPRINT_SWIM,
                    START_SPRINT_SWIM, SURFACE, CLIMB, PARKOUR, DIAGONAL_PARKOUR, WALK_OFF,
                    DIAGONAL_SPRINT_SWIM, RIDE_BUBBLE_COLUMN);
}
