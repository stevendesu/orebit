package com.orebit.mod.pathfinding.blockpathfinder;

import java.util.List;

import com.orebit.mod.pathfinding.blockpathfinder.movements.Ascend;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Descend;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Fall;
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
    public static final Movement ASCEND = new Ascend();
    public static final Movement DESCEND = new Descend();
    public static final Movement FALL = new Fall();

    /** Tier 1 (ground): walk + step-assist, jump-up-1, step-down-1, safe drop. */
    public static final List<Movement> TIER1 = List.of(TRAVERSE, ASCEND, DESCEND, FALL);
}
