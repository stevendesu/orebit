package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.MovementRegistry;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Descend;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Swim;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * Pins {@link Swim}'s <b>walk-out clearance gate</b> on step-down entries (owner rule 2026-08-20: "we
 * shouldn't emit a step-down into water until the target has 3 blocks of headroom" — headroom "as tall as the
 * starting cell", exactly {@link Descend}'s current-cell-2-high / destination-3-high transit shape).
 *
 * <p>The conviction is the 2026-08-20 run-5 wedge ({@code flagship-r5-async-838blocks.log}): at
 * {@code (467,63,630) -> (467,62,631)} the planner emitted a step-down swim entry testing only the DESTINATION
 * feet (fluid) and head (non-solid), never the transit — but the executor walks a step-down out ABOVE the
 * destination at STANDING height like a Descend and only then drops, so a dirt overhang at {@code (467,64,631)}
 * (the walk-out head cell) blocked the walk-out and the bot pressed into it for ~53k ticks (Swim has no
 * {@code failWhen}). The only dive/crawl initiations are eyes-underwater or an (unmodeled) trapdoor crawl, so
 * from a dry start the planner must REFUSE the capped entry and let the route fold breaks via other moves or go
 * around; a submerged start keeps its step-down (eyes underwater = a dive, it never walks the lip).
 *
 * <p>Scenes are minimal single-lane fixtures in the {@link FluidMediumSwimTest} style; lives in this package to
 * reach {@link NavGridView}'s package-private synthetic constructor.
 */
class SwimEntryHeadroomTest {

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
        BlockPathfinder.LOG_TIMING = false;
        Debug.ENABLED = false;
    }

    private static final RegionBound BOUND = new RegionBound(0, 15, 0, 15, 0, 15);

    // ---- 1. THE REGRESSION: the run-5 shape — a capped step-down entry must not be emitted --------------

    /**
     * Dry bot on a lip at floor {@code (4,5,8)} (feet y=6), water one below its feet level in the neighbour
     * column ({@code (5,5,8)}), and a solid cap at the walk-out head cell {@code (5,7,8)} — the
     * {@code (467,64,631)} dirt overhang, minimised. The basin is the only route to the goal and
     * {@link BotCaps#DEFAULT} can neither break nor place, so if the gate holds there is NO path at all.
     * Before the gate this scene planned straight through the capped entry (verified failing-first).
     */
    @Test
    void aCappedStepDownEntryIsRefusedFromADryStart() {
        BlockPathPlan plan = BlockPathfinder.findPath(basin(false, false),
                new BlockPos(4, 5, 8), new BlockPos(7, 4, 8), BotCaps.DEFAULT, BOUND);

        assertNull(plan, "a step-down swim entry under a solid walk-out cap is the run-5 wedge: the executor "
                + "walks out at standing height like a Descend and presses into the cap forever, so the "
                + "planner must refuse it (owner rule 2026-08-20)" + dump(plan));
    }

    // ---- 2. CONTROL: the identical scene without the cap is entered -------------------------------------

    /** The same lip and basin with the walk-out head cell {@code (5,7,8)} carved to air: the destination
     *  column now has the full 3 blocks of headroom over its feet, and the step-down entry is offered. */
    @Test
    void anUncappedStepDownEntryIsStillEmitted() {
        BlockPathPlan plan = BlockPathfinder.findPath(basin(true, false),
                new BlockPos(4, 5, 8), new BlockPos(7, 4, 8), BotCaps.DEFAULT, BOUND);

        assertNotNull(plan, "with the walk-out head cell clear the step-down entry is legal — the gate must "
                + "only bind CAPPED walk-outs, never refuse bank entry wholesale");
        assertTrue(contains(plan, MovementRegistry.SWIM),
                "the basin is entered by the Swim step-down rung" + dump(plan));
    }

    // ---- 3. SAME-LEVEL ENTRIES ARE UNAFFECTED -----------------------------------------------------------

    /**
     * A same-level entry ({@code wf == feetY}) into the submerged body of a fall — destination feet AND head
     * both water — with solid directly above the destination head ({@code (5,8,8)}) and above the bot's own
     * head. The gate binds step-downs only (a same-level entry crosses at swim height, there is no elevated
     * walk-out), so no 3-high headroom may be demanded here: this is exactly the §3.2 waterfall-body entry and
     * it must keep working under a low ceiling.
     */
    @Test
    void aSameLevelEntryUnderALowCeilingIsStillEmitted() {
        BlockPathPlan plan = BlockPathfinder.findPath(span(),
                new BlockPos(4, 5, 8), new BlockPos(7, 5, 8), BotCaps.DEFAULT, BOUND);

        assertNotNull(plan, "a same-level (wf == feetY) entry is not a step-down: the walk-out clearance gate "
                + "must not touch it, low ceiling or not (owner rule 2026-08-20 binds step-downs only)");
        assertTrue(contains(plan, MovementRegistry.SWIM),
                "the fall body is entered by the same-level Swim rung" + dump(plan));
    }

    // ---- 4. A SUBMERGED START KEEPS THE DIVE ------------------------------------------------------------

    /**
     * The bot starts with feet AND head in water ({@code (4,6..7,8)}) beside the same capped step-down column
     * as the regression scene. Eyes underwater is a dive initiation (owner rule 2026-08-20): a submerged bot
     * swims down and out under the cap rather than walking the lip at standing height, so the step-down entry
     * stays on offer and the basin is reachable.
     */
    @Test
    void aSubmergedStartKeepsItsCappedStepDown() {
        BlockPathPlan plan = BlockPathfinder.findPath(basin(false, true),
                new BlockPos(4, 5, 8), new BlockPos(7, 4, 8), BotCaps.DEFAULT, BOUND);

        assertNotNull(plan, "a submerged start (start head fluid) is a dive initiation — the walk-out "
                + "clearance gate is for dry lips only and must not seal a diver out of the basin");
        assertTrue(contains(plan, MovementRegistry.SWIM),
                "the dive is a Swim step-down rung" + dump(plan));
        assertTrue(lastWaypoint(plan).getX() >= 6,
                "the plan must actually cross the basin toward the goal, not park at the entry" + dump(plan));
    }

    // ---- scene builders ---------------------------------------------------------------------------------

    /**
     * The run-5 lip-and-basin lane on {@code z=8}: a stand column at {@code x=4} (floor stone {@code y=5},
     * body carved — or WATER when {@code wetStart}, the submerged-start variant), and a 1-deep basin at
     * {@code x=5..7} (water feet at {@code y=5} over a stone floor at {@code y=4}, air at {@code y=6}). The
     * walk-out head cell {@code (5,7,8)} stays SOLID (the overhang) unless {@code uncapped}. Everything off
     * the lane is solid, so the capped column is the only way in.
     */
    private static NavGridView basin(boolean uncapped, boolean wetStart) {
        PalettedContainer<BlockState> s = solid();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState startBody = wetStart ? water : air;
        s.set(4, 6, 8, startBody);
        s.set(4, 7, 8, startBody);
        for (int x = 5; x <= 7; x++) {
            s.set(x, 4, 8, Blocks.STONE.defaultBlockState()); // basin floor (already stone; explicit)
            s.set(x, 5, 8, water);                            // basin feet — ONE below the start feet level
            s.set(x, 6, 8, air);                              // basin surface air (the destination head)
        }
        if (uncapped) s.set(5, 7, 8, air);                    // the walk-out head cell — solid unless carved
        return grid(s);
    }

    /** The same-level lane: stand column at {@code x=4}, and a submerged span at {@code x=5..7} with water in
     *  BOTH body cells ({@code y=6..7} — a fall interior at the bot's own feet level) under the default solid
     *  ceiling at {@code y=8} (the low cap the gate must ignore on a same-level entry). */
    private static NavGridView span() {
        PalettedContainer<BlockState> s = solid();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        s.set(4, 6, 8, air);
        s.set(4, 7, 8, air);
        for (int x = 5; x <= 7; x++) {
            s.set(x, 6, 8, water);
            s.set(x, 7, 8, water);
        }
        return grid(s);
    }

    private static PalettedContainer<BlockState> solid() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        PalettedContainer<BlockState> s = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++)
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    s.set(x, y, z, stone);
        return s;
    }

    private static NavGridView grid(PalettedContainer<BlockState> s) {
        BlockState air = Blocks.AIR.defaultBlockState();
        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(s, false, section.getTraversalGrid());
        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());
        NavSection[] column = { section, airSection, airSection, airSection };
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), column);
        return new NavGridView(0, chunks);
    }

    private static boolean contains(BlockPathPlan plan, Object move) {
        if (plan == null) return false;
        for (int i = 0; i < plan.size(); i++) if (plan.movement(i) == move) return true;
        return false;
    }

    private static BlockPos lastWaypoint(BlockPathPlan plan) {
        return plan.waypoint(plan.size() - 1);
    }

    /** The plan, rendered for an assertion message (the {@link FluidMediumSwimTest} idiom — a failure here is
     *  almost always "which rungs did it actually pick"). */
    private static String dump(BlockPathPlan plan) {
        if (plan == null) return "\n  (no plan)";
        StringBuilder sb = new StringBuilder("\n  plan:");
        for (int i = 0; i < plan.size(); i++) {
            sb.append("\n    ").append(i).append(' ').append(plan.waypoint(i)).append(" via ")
              .append(plan.movement(i) == null ? "-" : plan.movement(i).getClass().getSimpleName());
        }
        return sb.toString();
    }
}
