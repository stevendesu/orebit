package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * Headless proof that ZERO-COLLISION décor block entities (signs, banners) are invisible to movement — a
 * trivial 1-block floor gap flanked by signs at foot level must be a clean PARKOUR jump, not a
 * break-and-pillar detour.
 *
 * <p><b>The bug.</b> Signs extend {@code BaseEntityBlock}; {@code NavBlock.fingerprint} blanket-forced every
 * {@code BaseEntityBlock} to a full solid cube (its {@code special} guard), so a sign classified
 * SHAPE_FULL / non-passable / standable even though its real collision shape is EMPTY — you walk through a
 * sign like air. That made the takeoff-feet and landing-feet cells (both occupied by signs) non-passable,
 * so the parkour candidate was never generated and A* fell back to breaking a sign, placing a block, and
 * ascending onto it. The fix reads the block entity's REAL collision shape (empty ⇒ passable).
 *
 * <p>Geometry (owner-reported; side view, columns 0..5 at {@code z=8}, floor {@code y=1}, solid below):
 * <pre>
 *   foot row y=2   B . S . S .     bot feet col0, signs at col2 (takeoff feet) + col4 (landing feet)
 *   floor row y=1  # # # . # #     gap at col3; solid col0-2, col4-5
 *   support y=0    # # # # # #
 * </pre>
 * The only clean route is takeoff col2 → parkour over the col3 gap → land col4.
 */
class SignPassabilityParkourTest {

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

    // ---- (1) the repro: signs at both foot cells → clean parkour, no edits --------------------------

    @Test
    void signGap_cleanParkour_noBreakNoPlace() {
        BlockPathPlan plan = search(footDecorGap(Blocks.OAK_SIGN.defaultBlockState()), 0, 1, 8, 4, 1, 8);

        assertNotNull(plan, "a route to col4 must exist");
        assertTrue(contains(plan, MovementRegistry.PARKOUR),
                "the 1-gap flanked by signs must be a PARKOUR jump (was a place-and-pillar detour)");
        assertNoEdits(plan, "the bot must NOT break or place any block — signs are walk-through décor");
        assertReaches(plan, 4, 1, 8);
    }

    // ---- (2) variants: other zero-collision decor at the foot cells ---------------------------------

    @Test
    void wallSignGap_cleanParkour() {
        assertCleanParkour(footDecorGap(Blocks.OAK_WALL_SIGN.defaultBlockState()), "oak wall sign");
    }

    @Test
    void bannerGap_cleanParkour() {
        // Banner: the OTHER empty-collision block entity the fix un-walls (BaseEntityBlock, empty shape).
        assertCleanParkour(footDecorGap(Blocks.WHITE_BANNER.defaultBlockState()), "white banner");
    }

    @Test
    void buttonGap_cleanParkour() {
        // Button/pressure-plate are NOT block entities (already passable pre-fix) — a regression guard that
        // ordinary zero-collision decor still parkours clean.
        assertCleanParkour(footDecorGap(Blocks.STONE_BUTTON.defaultBlockState()), "stone button");
    }

    @Test
    void pressurePlateGap_cleanParkour() {
        assertCleanParkour(footDecorGap(Blocks.STONE_PRESSURE_PLATE.defaultBlockState()), "stone pressure plate");
    }

    // ---- (3) NEGATIVE guards: do NOT over-generalize into "any no-collision block is free air" ------

    @Test
    void cobweb_stillSlowsAndBlocksTakeoff_notFreeAir() {
        // Cobweb is zero-collision but genuinely SLOWS — it must NOT be reclassified as free air. It is not
        // a block entity, so the fix leaves it untouched: passable but TRANSIT_HEAVY, and a cobweb body cell
        // still refuses a parkour takeoff (noJumpFromBody). Assert the descriptor is unchanged in kind…
        long d = NavBlock.descriptorFor(Blocks.COBWEB.defaultBlockState());
        assertTrue(NavBlock.isPassable(d), "cobweb stays geometrically passable");
        assertTrue(NavBlock.transitSlow(d) == NavBlock.TRANSIT_HEAVY, "cobweb must still register HEAVY through-slow");
        // …and behaviourally: cobweb at the takeoff/landing feet does NOT yield the clean free parkour the
        // signs do (the heavy-transit takeoff cell kills the jump), so A* is forced to a costlier plan.
        BlockPathPlan cobweb = search(footDecorGap(Blocks.COBWEB.defaultBlockState()), 0, 1, 8, 4, 1, 8);
        assertNotNull(cobweb, "a route still exists (mine/route around), but not the free parkour");
        assertFalse(contains(cobweb, MovementRegistry.PARKOUR) && !hasAnyEdit(cobweb),
                "cobweb at the foot cells must NOT produce an edit-free parkour — it is not free air");
    }

    @Test
    void realCollisionBlockAtFootCell_stillBlocks() {
        // A genuine collision block (stone) at the foot cells is a real obstacle: no clean, edit-free
        // parkour col2→col4 is possible (the feet cells are solid), so the fix must NOT let it through.
        BlockPathPlan plan = search(footDecorGap(Blocks.STONE.defaultBlockState()), 0, 1, 8, 4, 1, 8);
        if (plan != null) {
            assertFalse(contains(plan, MovementRegistry.PARKOUR) && !hasAnyEdit(plan),
                    "a solid block at the feet cells must never yield an edit-free parkour");
        }
    }

    @Test
    void sign_placementAndFluidSemanticsUntouched() {
        // The movement/passability view changed; the placement + fluid facts must not. A sign is NOT
        // replaceable (a placement can't silently overwrite it) and holds NO fluid (fluid-flow modelling —
        // the fluid field — is unaffected; NavBlock never modelled "stops fluid flow" and still doesn't).
        long d = NavBlock.descriptorFor(Blocks.OAK_SIGN.defaultBlockState());
        assertFalse(NavBlock.isReplaceable(d), "a sign must not be marked replaceable");
        assertTrue(NavBlock.fluid(d) == 0, "a sign holds no fluid");
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private static void assertCleanParkour(NavGridView g, String what) {
        BlockPathPlan plan = search(g, 0, 1, 8, 4, 1, 8);
        assertNotNull(plan, "a route to col4 must exist over the " + what);
        assertTrue(contains(plan, MovementRegistry.PARKOUR), what + " gap must be a clean PARKOUR jump");
        assertNoEdits(plan, "the bot must not break/place over " + what);
        assertReaches(plan, 4, 1, 8);
    }

    /**
     * Floor at y=1 with a 1-cell gap at col3 (solid support at y=0 below it); {@code decor} placed at the
     * FOOT cells (y=2) of the takeoff (col2) and landing (col4) columns; everything else air.
     */
    private static NavGridView footDecorGap(BlockState decor) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        PalettedContainer<BlockState> s = solidBlock();
        // Carve air above the floor for the bodies (y=2..7), all six columns.
        for (int x = 0; x <= 5; x++)
            for (int y = 2; y <= 7; y++)
                s.set(x, y, 8, air);
        // Floor row y=1: solid everywhere except the gap at col3.
        for (int x = 0; x <= 5; x++)
            s.set(x, 1, 8, stone);
        s.set(3, 1, 8, air); // the gap (support at y=0 stays solid)
        // Décor at the foot cells of the takeoff (col2) and landing (col4).
        s.set(2, 2, 8, decor);
        s.set(4, 2, 8, decor);
        return grid(s);
    }

    private static PalettedContainer<BlockState> solidBlock() {
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

    private static BlockPathPlan search(NavGridView g, int sx, int sy, int sz, int gx, int gy, int gz) {
        return BlockPathfinder.findPath(g, new BlockPos(sx, sy, sz), new BlockPos(gx, gy, gz),
                BotCaps.DEFAULT, BOUND);
    }

    private static boolean contains(BlockPathPlan plan, Object move) {
        for (int i = 0; i < plan.size(); i++)
            if (plan.movement(i) == move) return true;
        return false;
    }

    private static boolean hasAnyEdit(BlockPathPlan plan) {
        for (int i = 0; i < plan.size(); i++)
            if (plan.edits(i) != null) return true;
        return false;
    }

    private static void assertNoEdits(BlockPathPlan plan, String msg) {
        assertFalse(hasAnyEdit(plan), msg);
    }

    private static void assertReaches(BlockPathPlan plan, int gx, int gy, int gz) {
        BlockPos last = plan.waypoint(plan.size() - 1);
        assertTrue(Math.abs(last.getX() - gx) <= 1 && Math.abs(last.getZ() - gz) <= 1
                        && Math.abs(last.getY() - gy) <= 1,
                "the plan should end at the goal (" + gx + "," + gy + "," + gz + "); ended at " + last);
    }
}
