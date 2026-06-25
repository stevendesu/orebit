package com.orebit.mod.pathfinding.blockpathfinder;

import java.util.List;

import com.orebit.mod.pathfinding.blockpathfinder.movements.Ascend;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Descend;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Diagonal;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Fall;
import com.orebit.mod.pathfinding.blockpathfinder.movements.MineDown;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Pillar;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Traverse;

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

    /**
     * Tier 1 (ground): walk + step-assist, diagonal walk, jump-up-1, step-down-1, safe drop, and the
     * vertical-in-place pair pillar-up / mine-down. Pillar/MineDown self-gate on place/break caps, so a
     * walk-only bot still gets only the plain ground moves.
     */
    public static final List<Movement> TIER1 =
            List.of(TRAVERSE, DIAGONAL, ASCEND, DESCEND, FALL, PILLAR, MINE_DOWN);
}
