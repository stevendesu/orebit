package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.MiningModel;
import com.orebit.mod.pathfinding.blockpathfinder.MovementRegistry;
import com.orebit.mod.pathfinding.blockpathfinder.StepEdits;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * TRAPDOOR-LADDER CLIMB — the owner's shaft at the full-search level (DESIGN-trapdoor-ladder-climb.md
 * §3/§4/§7; the {@link GateToggleTest} corridor idiom): a sealed 1×1 bore, 10 cells rim-floor to
 * shaft-floor, a full ladder column, and a mouth trapdoor whose FACING equals the ladder's — the exact
 * geometry {@code LivingEntity.trapdoorUsableAsLadder} climbs (§1: OPEN + exactly {@code Blocks.LADDER}
 * below + EQUAL facing; HALF/waterlogging not consulted). Pinned:
 * <ul>
 *   <li><b>Bottom→top, OPEN mouth</b> — the climb-continue admits the mouth as a ladder extension: route
 *       found, a waypoint's FEET cell is the mouth cell, ZERO edits of any kind.</li>
 *   <li><b>Bottom→top, CLOSED oak mouth</b> — the §4 from-below toggle arm: route with exactly ONE
 *       {@code SET_OPEN} on the mouth cell, nothing broken or placed.</li>
 *   <li><b>Facing MISMATCH, open mouth</b> — vanilla's equal-facing conjunct: the mouth extends nothing,
 *       no route ever passes the mouth plane.</li>
 *   <li><b>Iron mouth</b> — the §1/§4 split: OPEN iron climbs free (the rule is {@code instanceof}, every
 *       trapdoor qualifies); CLOSED iron refuses the toggle ({@code handToggleable} false) and the shaft
 *       stays sealed, zero SETs anywhere.</li>
 *   <li><b>{@code doors.toggle=false} + closed oak</b> — the capability kill-switch: no SET arm, no
 *       route.</li>
 *   <li><b>Plain shaft (ladder to rim level, no trapdoor), bottom→top</b> — the PRE-EXISTING baseline the
 *       feature builds on (DESIGN-trapdoors.md §7 "plans naturally where the ladder continues"): route,
 *       zero edits.</li>
 *   <li><b>Top→bottom</b> — the §3.8 top-entry arm this arc ADDED (rim → mouth → sink onto the ladder):
 *       OPEN mouth enters free (zero edits), CLOSED oak folds exactly one {@code SET_OPEN}, CLOSED iron
 *       refuses. The PLAIN-shaft top-entry is a documented PRE-EXISTING GAP the arm deliberately does not
 *       close (a ladder plate is NARROW_TOP — no landing move accepts it, and no vanilla rule holds a body
 *       in a bare mouth-less air cell): that refusal is pinned as such, NOT as this arc's behavior.</li>
 * </ul>
 * PhaseRunner coverage is deliberately ABSENT: {@code Climb.plan()} declares NO needs — the mouth SET
 * rides the movement-agnostic {@code StepEdits} doors[] channel and {@code BotNavigator}'s
 * {@code requireTrapdoor}/{@code Need.OPEN} injection, the already-tested seam. Lives in this package for
 * {@link NavGridView}'s package-private synthetic constructor.
 */
class TrapdoorLadderClimbTest {

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
        // Real mining-tick table (the GateToggleTest idiom) so refusals are refusals of a REAL alternative
        // model, not artifacts of an unbaked table.
        MiningModel.buildTable(true, 0);
    }

    private static BotCaps caps(boolean canBreak, boolean canPlace, boolean toggle) {
        return new BotCaps(1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, false,
                BotCaps.DEFAULT_COST_PER_HITPOINT, canBreak, canPlace,
                100, false, BotCaps.DEFAULT_MAX_NODES, 1.0f, toggle);
    }

    /** Walk-only + doors.toggle ON: a crossing proves a free admit or a SET fold — never a break/place. */
    private static final BotCaps WALK_TOGGLE = caps(false, false, true);
    /** Walk-only + doors.toggle OFF: the flag kill-switch regression control. */
    private static final BotCaps WALK_OFF = caps(false, false, false);

    /** The shaft column (SX, SZ); mouth cell at (SX, MOUTH_Y, SZ), rim floor plane == MOUTH_Y. */
    private static final int SX = 8, SZ = 8, MOUTH_Y = 12;
    /** Bottom stance: floor (SX,2,SZ) stone, feet (SX,3,SZ) in the lowest rung. */
    private static final BlockPos BOTTOM = new BlockPos(SX, 2, SZ);
    /** Rim stance three cells off the mouth — far enough that goal tolerance (XZ 1) cannot bridge it. */
    private static final BlockPos RIM = new BlockPos(SX, MOUTH_Y, SZ + 3);

    // ================================================================================================
    // (1) Bottom→top, OPEN mouth: the ladder-extension continue — free, feet through the mouth cell.
    // ================================================================================================

    @Test
    void bottomToTopThroughOpenMouthClimbsFreeZeroEdits() {
        NavGridView grid = shaft(td(Blocks.OAK_TRAPDOOR, Direction.NORTH, true));
        BlockPathPlan plan = BlockPathfinder.findPath(grid, BOTTOM, RIM, WALK_TOGGLE);

        assertNotNull(plan, "an open equal-facing mouth extends the ladder — the bot climbs out");
        BlockPos last = plan.waypoint(plan.size() - 1);
        assertTrue(Math.abs(last.getX() - RIM.getX()) <= 1 && Math.abs(last.getZ() - RIM.getZ()) <= 1,
                "ends on the rim at the goal; ended " + last);
        // Waypoints are FEET cells: the climb threads the bot's feet through the mouth cell itself.
        assertTrue(contains(plan, SX, MOUTH_Y, SZ), "a waypoint's feet cell is the mouth cell");
        assertTrue(containsMove(plan, MovementRegistry.CLIMB), "the shaft is exited by CLIMB");
        assertNoEditsAtAll(plan);
    }

    // ================================================================================================
    // (2) Bottom→top, CLOSED oak mouth: the §4 from-below toggle arm — exactly ONE SET_OPEN.
    // ================================================================================================

    @Test
    void bottomToTopClosedOakMouthFoldsExactlyOneSetOpen() {
        NavGridView grid = shaft(td(Blocks.OAK_TRAPDOOR, Direction.NORTH, false));
        BlockPathPlan plan = BlockPathfinder.findPath(grid, BOTTOM, RIM, WALK_TOGGLE);

        assertNotNull(plan, "a walk-only toggle bot climbs out of the shaft — by opening the mouth");
        assertTrue(contains(plan, SX, MOUTH_Y, SZ), "the feet still pass through the (now open) mouth");
        assertMouthSets(plan, 1);
    }

    // ================================================================================================
    // (3) Facing MISMATCH: an open mouth whose FACING differs from the ladder's extends NOTHING
    //     (vanilla's equal-facing conjunct) — and closed it may not be toggled either (the fold's own
    //     facing test uses the packed CLOSED facing, which a toggle never changes).
    // ================================================================================================

    @Test
    void facingMismatchedOpenMouthIsNoLadderExtension() {
        NavGridView grid = shaft(td(Blocks.OAK_TRAPDOOR, Direction.SOUTH, true));
        BlockPathPlan plan = BlockPathfinder.findPath(grid, BOTTOM, RIM, WALK_TOGGLE);
        assertNeverPassesMouthPlane(plan, "an open mouth facing SOUTH over a NORTH ladder is not climbable");
    }

    // ================================================================================================
    // (4) Iron: OPEN climbs free (instanceof — every trapdoor qualifies), CLOSED refuses the toggle
    //     (not hand-toggleable) with no break fallback — the shaft stays sealed.
    // ================================================================================================

    @Test
    void ironClosedMouthRefuses() {
        NavGridView grid = shaft(td(Blocks.IRON_TRAPDOOR, Direction.NORTH, false));
        BlockPathPlan plan = BlockPathfinder.findPath(grid, BOTTOM, RIM, WALK_TOGGLE);
        assertNeverPassesMouthPlane(plan, "a closed IRON mouth is never hand-toggled");
        assertNoSetsAnywhere(plan);
    }

    @Test
    void ironOpenMouthClimbsFreeZeroEdits() {
        NavGridView grid = shaft(td(Blocks.IRON_TRAPDOOR, Direction.NORTH, true));
        BlockPathPlan plan = BlockPathfinder.findPath(grid, BOTTOM, RIM, WALK_TOGGLE);
        assertNotNull(plan, "an OPEN iron mouth climbs exactly like wood — openness is state, not material");
        assertTrue(contains(plan, SX, MOUTH_Y, SZ), "the feet pass through the open iron mouth");
        assertNoEditsAtAll(plan);
    }

    // ================================================================================================
    // (5) doors.toggle=false + closed oak: the capability kill-switch — no SET arm, no route.
    // ================================================================================================

    @Test
    void toggleOffClosedMouthRefuses() {
        NavGridView grid = shaft(td(Blocks.OAK_TRAPDOOR, Direction.NORTH, false));
        BlockPathPlan plan = BlockPathfinder.findPath(grid, BOTTOM, RIM, WALK_OFF);
        assertNeverPassesMouthPlane(plan, "flag OFF: a closed mouth is a hard ceiling to a walk-only bot");
        assertNoSetsAnywhere(plan);
    }

    // ================================================================================================
    // (6) Plain shaft, bottom→top: the pre-existing baseline this feature builds on — the ladder itself
    //     runs to rim level, EXIT-TOP fires from the top rung, and the rim is walked. Zero edits.
    // ================================================================================================

    @Test
    void plainShaftBottomToTopIsThePreExistingBaseline() {
        BlockPathPlan plan = BlockPathfinder.findPath(plainShaft(), BOTTOM, RIM, WALK_TOGGLE);
        assertNotNull(plan, "a ladder that itself reaches rim level climbs out with no trapdoor involved");
        assertTrue(containsMove(plan, MovementRegistry.CLIMB), "the shaft is exited by CLIMB");
        assertNoEditsAtAll(plan);
    }

    // ================================================================================================
    // (7) Top→bottom — the §3.8 top-entry arm this arc added: from rim footing, step INTO the mouth at
    //     floor level and sink one cell onto the ladder. Open enters free; closed oak folds the §4 SET;
    //     closed iron refuses.
    // ================================================================================================

    @Test
    void topToBottomThroughOpenMouthTopEntryZeroEdits() {
        NavGridView grid = shaft(td(Blocks.OAK_TRAPDOOR, Direction.NORTH, true));
        BlockPathPlan plan = BlockPathfinder.findPath(grid, RIM, BOTTOM, WALK_TOGGLE);

        assertNotNull(plan, "the §3.8 top-entry threads the rim bot into the shaft through the open mouth");
        assertTrue(contains(plan, SX, MOUTH_Y, SZ), "the entry waypoint's feet cell is the mouth cell");
        BlockPos last = plan.waypoint(plan.size() - 1);
        assertTrue(last.getX() == SX && last.getZ() == SZ && last.getY() <= 5,
                "ends down the shaft within goal tolerance of the bottom stance; ended " + last);
        assertNoEditsAtAll(plan);
    }

    @Test
    void topToBottomClosedOakMouthFoldsExactlyOneSetOpen() {
        NavGridView grid = shaft(td(Blocks.OAK_TRAPDOOR, Direction.NORTH, false));
        BlockPathPlan plan = BlockPathfinder.findPath(grid, RIM, BOTTOM, WALK_TOGGLE);

        assertNotNull(plan, "the top-entry opens the closed oak mouth itself and descends");
        assertTrue(contains(plan, SX, MOUTH_Y, SZ), "the entry waypoint's feet cell is the mouth cell");
        assertMouthSets(plan, 1);
    }

    @Test
    void topToBottomIronClosedMouthRefuses() {
        NavGridView grid = shaft(td(Blocks.IRON_TRAPDOOR, Direction.NORTH, false));
        BlockPathPlan plan = BlockPathfinder.findPath(grid, RIM, BOTTOM, WALK_TOGGLE);
        assertNeverEntersShaft(plan, "a closed IRON mouth refuses the top-entry — never hand-toggled");
        assertNoSetsAnywhere(plan);
    }

    /**
     * PRE-EXISTING GAP, pinned AS a gap (DESIGN-trapdoor-ladder-climb.md §3 descent finding): no
     * plain-ladder top-entry exists at all — a ladder plate is NARROW_TOP, so Traverse/Descend/Fall/
     * WalkOff refuse it as a destination floor and MineDown as a landing, and no vanilla rule holds a
     * body in a bare mouth-less air cell (the §3.8 arm deliberately requires the trapdoor mouth). This
     * test documents that refusal; it is NOT a behavior this arc introduced, and closing it is out of
     * scope (§8). If it starts passing a route, a top-entry for bare ladders was built — retire this pin
     * in favor of positive assertions.
     */
    @Test
    void topToBottomPlainShaftIsThePreExistingGap() {
        BlockPathPlan plan = BlockPathfinder.findPath(plainShaft(), RIM, BOTTOM, WALK_TOGGLE);
        assertNeverEntersShaft(plan,
                "GAP (pre-existing): no move enters a bare ladder shaft from its rim");
    }

    // ---- scene builders (the GateToggleTest / TrapdoorToggleTest single-section idiom) --------------

    private static BlockState td(Block block, Direction facing, boolean open) {
        // HALF is deliberately BOTTOM and untested elsewhere: vanilla's trapdoorUsableAsLadder never
        // consults it (§1).
        return block.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(BlockStateProperties.OPEN, open)
                .setValue(BlockStateProperties.HALF, Half.BOTTOM)
                .setValue(BlockStateProperties.WATERLOGGED, false);
    }

    private static BlockState ladder() {
        return Blocks.LADDER.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(BlockStateProperties.WATERLOGGED, false);
    }

    /**
     * The owner's shaft: solid stone, open sky y=13..15 (rim floor plane y=12, feet 13), a 1×1 bore at
     * (SX, SZ) — stone floor at y=2, ladder rungs y=3..11 (facing NORTH), and {@code mouth} at
     * (SX, 12, SZ), flush with the rim floor plane. Ten cells rim floor → shaft floor.
     */
    private static NavGridView shaft(BlockState mouth) {
        PalettedContainer<BlockState> s = rimWorld();
        for (int y = 3; y <= 11; y++) s.set(SX, y, SZ, ladder());
        s.set(SX, MOUTH_Y, SZ, mouth);
        return single(s);
    }

    /** The same bore with NO trapdoor: the ladder itself continues through the mouth cell to rim level —
     *  the geometry the trapdoor arc's Climb already planned ("where the ladder continues"). */
    private static NavGridView plainShaft() {
        PalettedContainer<BlockState> s = rimWorld();
        for (int y = 3; y <= MOUTH_Y; y++) s.set(SX, y, SZ, ladder());
        return single(s);
    }

    /** Stone fill with open sky above the rim floor plane (air y=13..15). */
    private static PalettedContainer<BlockState> rimWorld() {
        BlockState air = Blocks.AIR.defaultBlockState();
        PalettedContainer<BlockState> s = filled(Blocks.STONE.defaultBlockState());
        for (int x = 0; x < 16; x++)
            for (int y = MOUTH_Y + 1; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    s.set(x, y, z, air);
        return s;
    }

    private static PalettedContainer<BlockState> filled(BlockState shell) {
        PalettedContainer<BlockState> s = new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++)
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    s.set(x, y, z, shell);
        return s;
    }

    private static NavGridView single(PalettedContainer<BlockState> s) {
        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(s, false, section.getTraversalGrid());
        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());
        NavSection[] column = { section, airSection, airSection, airSection };
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), column);
        return new NavGridView(0, chunks);
    }

    // ---- assertions ---------------------------------------------------------------------------------

    private static boolean contains(BlockPathPlan plan, int x, int y, int z) {
        for (int i = 0; i < plan.size(); i++) {
            BlockPos wp = plan.waypoint(i);
            if (wp.getX() == x && wp.getY() == y && wp.getZ() == z) return true;
        }
        return false;
    }

    private static boolean containsMove(BlockPathPlan plan, Object move) {
        for (int i = 0; i < plan.size(); i++) {
            if (plan.movement(i) == move) return true;
        }
        return false;
    }

    /** Exactly {@code open} SET_OPENs, every SET on the mouth cell (SX, MOUTH_Y, SZ), no SET_CLOSED, and
     *  ZERO breaks/places anywhere in the plan. */
    private static void assertMouthSets(BlockPathPlan plan, int open) {
        int setOpen = 0, setClosed = 0, breaks = 0, places = 0;
        for (int i = 0; i < plan.size(); i++) {
            StepEdits e = plan.edits(i);
            if (e == null) continue;
            breaks += e.breakCount();
            places += e.placeCount();
            for (int j = 0; j < e.doorSetCount(); j++) {
                assertEquals(BlockPos.asLong(SX, MOUTH_Y, SZ), e.doorSetAt(j),
                        "the only SET is on the mouth cell");
                if (e.doorSetOpenAt(j)) setOpen++;
                else setClosed++;
            }
        }
        assertEquals(open, setOpen, "exactly " + open + " SET_OPEN — the mouth is opened, not smashed");
        assertEquals(0, setClosed, "no spurious SET_CLOSED");
        assertEquals(0, breaks, "nothing broken");
        assertEquals(0, places, "nothing placed");
    }

    /** The plan crossed with ZERO edits of any kind — the free-admit proof. */
    private static void assertNoEditsAtAll(BlockPathPlan plan) {
        for (int i = 0; i < plan.size(); i++) {
            StepEdits e = plan.edits(i);
            if (e == null) continue;
            assertEquals(0, e.breakCount(), "no breaks folded");
            assertEquals(0, e.placeCount(), "no places folded");
            assertEquals(0, e.doorSetCount(), "no SETs folded");
        }
    }

    /** No SET edits anywhere in a (possibly partial, possibly null) plan. */
    private static void assertNoSetsAnywhere(BlockPathPlan plan) {
        if (plan == null) return;
        for (int i = 0; i < plan.size(); i++) {
            StepEdits e = plan.edits(i);
            if (e == null) continue;
            assertEquals(0, e.doorSetCount(), "no SET is folded anywhere");
        }
    }

    /** Bottom→top refusal: no waypoint's FEET cell ever reaches the mouth plane (y == MOUTH_Y) — the
     *  highest legal feet cell is the top rung at y == MOUTH_Y − 1. Partial plans allowed. */
    private static void assertNeverPassesMouthPlane(BlockPathPlan plan, String msg) {
        if (plan == null) return;
        for (int i = 0; i < plan.size(); i++) {
            assertTrue(plan.waypoint(i).getY() < MOUTH_Y, msg + "; reached " + plan.waypoint(i));
        }
    }

    /** Top→bottom refusal: every waypoint's FEET cell stays at rim feet level or above (y ≥ MOUTH_Y + 1)
     *  — the bot never gets a foothold inside the bore. Partial plans allowed. */
    private static void assertNeverEntersShaft(BlockPathPlan plan, String msg) {
        if (plan == null) return;
        for (int i = 0; i < plan.size(); i++) {
            assertTrue(plan.waypoint(i).getY() > MOUTH_Y, msg + "; reached " + plan.waypoint(i));
        }
    }
}
