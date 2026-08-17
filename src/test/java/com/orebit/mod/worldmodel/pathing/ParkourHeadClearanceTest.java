package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * Headless pin of the parkour <b>apex head row</b> ({@code y+4} over every gap column — owner ruling
 * 2026-08-17, derivation on {@code Parkour}'s class Javadoc): a vanilla sprint-jump apex rises the feet
 * {@code +1.2522}, so the 1.8-tall bot's head-top reaches takeoff-feet {@code +3.0522} — five hundredths
 * into the FOURTH cell layer above the takeoff feet. The old prism stopped one row short
 * ({@code y+1..y+3} in node coordinates), so a ceiling at exactly takeoff-feet+3 beginning mid-gap was
 * invisible: the bot jumped, its head-corner hit the ceiling block's face at the apex, vanilla zeroed
 * the horizontal velocity axis-exact ({@code hcol}), and it dropped into the gap (tick-verified on the
 * jungle master world: falling gap-4 from floor (203,112,363) with jungle_leaves at (206..208,116,363)).
 *
 * <p>Ruling (verbatim): <i>"Modeling a bonk sounds complex and expensive. Refusing the jump is easier
 * and safe. The point of the parkour prism is supposed to ensure the full ballistic path is clear. If
 * we're not properly checking head clearance at every position in the jump, that's a bug."</i> The
 * accepted consequence — pinned by {@link #continuousThreeHighTunnelRefusesTheJump} — is that a jump
 * under a CONTINUOUS ceiling at takeoff-feet+3 (a 3-high tunnel) is now refused too, even though
 * vanilla would merely scrape the ceiling there: refusal over bonk-modelling, the safe direction.
 *
 * <p>Four fixtures:
 * <ul>
 *   <li><b>Specimen shape</b> (the jungle geometry, full search): terraced walkable ground under the
 *       whole "gap" with the feet+3 ceiling over its far half ⇒ no jump launched before the ceiling
 *       band ends in or past it; the band is crossed on the ground (Traverse + Descend/Fall). A short
 *       hop onto the near, ceiling-free half stays legal — the live jungle grass offers the same.</li>
 *   <li><b>Falling corridor</b> (candidate-level): the same falling gap-4/drop-2 shape sealed so the
 *       jump is the ONLY route — control (no ceiling) crosses as ONE Parkour (gap-4 falling itself is
 *       untouched — the envelope tables did not change); the mid-gap feet+3 ceiling ⇒ no route.</li>
 *   <li><b>Flat corridor</b>: control / mid-gap feet+3 ceiling / continuous 3-high tunnel / feet+4
 *       ceiling — the last pins the boundary: the head-top at {@code +3.0522} needs only the +3 LAYER,
 *       so a ceiling one row higher must NOT refuse.</li>
 *   <li><b>Diagonal corridor</b>: the same feet+3 / feet+4 pair for {@code DiagonalParkour}, plus a
 *       feet+3 ceiling over a swept CORNER column (the corner prisms grew the same row).</li>
 * </ul>
 * Lives in this package to reach {@link NavGridView}'s package-private synthetic constructor.
 */
class ParkourHeadClearanceTest {

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

    private static final RegionBound CORRIDOR = new RegionBound(0, 15, 0, 15, 0, 15);

    // ---------------------------------------------------------------- specimen shape (full search)

    /**
     * The jungle specimen, translated: takeoff plateau floor y=5 (x=1..2), walkable lower ground floor
     * y=4 under the whole "gap" (x=3..6), landing terrace floor y=3 (x=7..14); ceiling blocks at
     * takeoff-feet+3 (y=9) over the far half (x=5..7) — exactly the jungle_leaves band. The buggy prism
     * offered the takeoff plateau's gap-4/drop-2 jump straight through the ceiling; post-fix no jump
     * launched BEFORE the band may end in or past it, and the band itself is crossed on the ground
     * (Traverse under the ceiling, Descend/Fall off the terrace). A short drop-1 hop onto the NEAR half
     * of the lower ground (columns clear of the ceiling) stays legitimate — exactly as on the live
     * jungle terrain, whose grass at y=111 offers the same hop — so the pin is the band-crossing, not
     * "no parkour anywhere": from a floor-4 terrace node the ceiling sits at feet+4 and is legitimately
     * out of the arc.
     */
    @Test
    void specimenShapeRoutesAcrossTheGroundInsteadOfJumping() {
        NavGridView grid = buildTerraces(true);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 5, 8),
                new BlockPos(9, 3, 8), BotCaps.DEFAULT, CORRIDOR);

        assertNotNull(plan, "the ground route (Descend/Traverse over the y=4 terrace) must be found");
        // No jump step may START before the ceiling band (x < 5, where the takeoff feet+3 layer IS the
        // band's layer) and LAND in or past it — the buggy gap-4 arc's exact shape.
        BlockPos from = new BlockPos(2, 5, 8);
        for (int i = 0; i < plan.size(); i++) {
            if ((plan.movement(i) == MovementRegistry.PARKOUR
                    || plan.movement(i) == MovementRegistry.DIAGONAL_PARKOUR)
                    && from.getX() < 5 && plan.waypoint(i).getX() >= 5) {
                assertTrue(false, "a jump launched before the takeoff-feet+3 ceiling band must not"
                        + " end in/past it: " + from + " -> " + plan.waypoint(i));
            }
            from = plan.waypoint(i);
        }
        assertTrue(count(plan, MovementRegistry.TRAVERSE) >= 1,
                "the band itself should be crossed walking the terrace");
        assertTrue(count(plan, MovementRegistry.DESCEND) + count(plan, MovementRegistry.FALL) >= 1,
                "the route must step DOWN onto/off the terrace (Descend or Fall), never jump the band");
        BlockPos last = plan.waypoint(plan.size() - 1);
        assertTrue(Math.abs(last.getX() - 9) <= 1 && Math.abs(last.getZ() - 8) <= 1,
                "the plan should end at the goal; ended at " + last);
    }

    // ---------------------------------------------------------------- falling corridor (jump-only route)

    @Test
    void fallingGapFourIsStillOfferedUnderOpenSky() {
        // Control: the specimen's falling gap-4/drop-2 jump itself is untouched (the envelope tables did
        // not change) — with the chasm sealed it is the only route and must still be taken.
        NavGridView grid = buildFallingCourse(null);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 5, 8),
                new BlockPos(8, 3, 8), BotCaps.DEFAULT, CORRIDOR);

        assertNotNull(plan, "the falling gap-4 jump is the only route and must still be offered");
        assertEquals(1, count(plan, MovementRegistry.PARKOUR),
                "the crossing should be exactly one falling Parkour");
    }

    @Test
    void fallingGapFourIsRefusedUnderAMidGapCeilingAtTakeoffFeetPlusThree() {
        // The specimen refusal at candidate level: ceiling over gap columns 3 and 4 only (starts
        // mid-gap, where the arc is at its apex). The jump is the only route, so refusal = no plan.
        NavGridView grid = buildFallingCourse(new int[] {5, 6});
        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 5, 8),
                new BlockPos(8, 3, 8), BotCaps.DEFAULT, CORRIDOR);

        assertNull(plan, "the apex head row (takeoff-feet+3) is blocked mid-gap — the jump must be refused");
    }

    // ---------------------------------------------------------------- flat corridor

    @Test
    void flatJumpUnderOpenSkyIsStillOffered() {
        NavGridView grid = buildFlatCourse(-1, -1);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 5, 8),
                new BlockPos(8, 5, 8), BotCaps.DEFAULT, CORRIDOR);

        assertNotNull(plan, "the head row must not over-refuse: open sky above the gap stays jumpable");
        assertEquals(1, count(plan, MovementRegistry.PARKOUR),
                "the 2-gap should cross as exactly one Parkour");
    }

    @Test
    void midGapCeilingAtTakeoffFeetPlusThreeRefusesTheFlatJump() {
        // One ceiling block over the SECOND gap column only (starts mid-gap): invisible to the old
        // y+1..y+3 prism, fatal to the real arc — must refuse (and the corridor has no other route).
        NavGridView grid = buildFlatCourse(6, 9);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 5, 8),
                new BlockPos(8, 5, 8), BotCaps.DEFAULT, CORRIDOR);

        assertNull(plan, "a takeoff-feet+3 ceiling beginning mid-gap must refuse the jump");
    }

    /**
     * The ruling's accepted consequence, pinned: under a CONTINUOUS ceiling at takeoff-feet+3 — a 3-high
     * tunnel over the whole corridor — vanilla would merely scrape the ceiling at the apex, but the
     * owner ruled for refusal over bonk-modelling: <i>"Modeling a bonk sounds complex and expensive.
     * Refusing the jump is easier and safe."</i> A 3-high-tunnel parkour is therefore no longer offered.
     */
    @Test
    void continuousThreeHighTunnelRefusesTheJump() {
        NavGridView grid = buildFlatCourse(-2, 9);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 5, 8),
                new BlockPos(8, 5, 8), BotCaps.DEFAULT, CORRIDOR);

        assertNull(plan, "a continuous takeoff-feet+3 ceiling (3-high tunnel) refuses the jump (ruling)");
    }

    @Test
    void ceilingAtTakeoffFeetPlusFourStillPermitsTheJump() {
        // The boundary: the apex head-top is takeoff-feet +3.0522, so only the +3 LAYER is swept; a
        // ceiling one layer higher (feet+4, node y+5) is clear of the whole arc and must NOT refuse.
        NavGridView grid = buildFlatCourse(-2, 10);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 5, 8),
                new BlockPos(8, 5, 8), BotCaps.DEFAULT, CORRIDOR);

        assertNotNull(plan, "a feet+4 ceiling is above the arc — the jump must still be offered");
        assertEquals(1, count(plan, MovementRegistry.PARKOUR),
                "the 2-gap should still cross as exactly one Parkour");
    }

    // ---------------------------------------------------------------- diagonal corridor

    @Test
    void diagonalJumpUnderOpenSkyIsStillOffered() {
        NavGridView grid = buildDiagonalCourse(null);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 5, 2),
                new BlockPos(9, 5, 9), BotCaps.DEFAULT, CORRIDOR);

        assertNotNull(plan, "the diagonal 1-gap must still be offered under open sky");
        assertEquals(1, count(plan, MovementRegistry.DIAGONAL_PARKOUR),
                "the crossing should be exactly one DiagonalParkour");
    }

    @Test
    void ceilingAtTakeoffFeetPlusThreeOverTheDiagonalGapRefusesTheJump() {
        NavGridView grid = buildDiagonalCourse(new BlockPos(6, 9, 6));
        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 5, 2),
                new BlockPos(9, 5, 9), BotCaps.DEFAULT, CORRIDOR);

        assertNull(plan, "the diagonal gap cell's apex head row (feet+3) is blocked — refuse the jump");
    }

    @Test
    void ceilingAtTakeoffFeetPlusThreeOverASweptCornerRefusesTheJump() {
        // The corner columns are swept by the 0.6-wide hitbox mid-arc, apex included — their prisms
        // grew the same y+4 row. (6,5) is the transition corner the 1-gap jump crosses.
        NavGridView grid = buildDiagonalCourse(new BlockPos(6, 9, 5));
        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 5, 2),
                new BlockPos(9, 5, 9), BotCaps.DEFAULT, CORRIDOR);

        assertNull(plan, "a swept corner column's apex head row (feet+3) is blocked — refuse the jump");
    }

    @Test
    void ceilingAtTakeoffFeetPlusFourOverTheDiagonalGapStillPermitsTheJump() {
        NavGridView grid = buildDiagonalCourse(new BlockPos(6, 10, 6));
        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 5, 2),
                new BlockPos(9, 5, 9), BotCaps.DEFAULT, CORRIDOR);

        assertNotNull(plan, "a feet+4 ceiling is above the diagonal arc — the jump must still be offered");
        assertEquals(1, count(plan, MovementRegistry.DIAGONAL_PARKOUR),
                "the crossing should still be exactly one DiagonalParkour");
    }

    // ---------------------------------------------------------------- helpers

    private static int count(BlockPathPlan plan, Object move) {
        int n = 0;
        for (int i = 0; i < plan.size(); i++) {
            if (plan.movement(i) == move) n++;
        }
        return n;
    }

    private static PalettedContainer<BlockState> solidSection() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        PalettedContainer<BlockState> s = new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    s.set(x, y, z, stone);
                }
            }
        }
        return s;
    }

    private static NavGridView toGrid(PalettedContainer<BlockState> s) {
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

    /**
     * The terraced specimen shape (corridor at z=8): floor tops y=5 (x=1..2), y=4 (x=3..6), y=3
     * (x=7..14); body air carved to y=10 everywhere. {@code ceiling} places stone at y=9 (takeoff
     * feet+3) over x=5..7 — the far half of the "gap" plus the landing column, the jungle_leaves band.
     */
    private static NavGridView buildTerraces(boolean ceiling) {
        PalettedContainer<BlockState> s = solidSection();
        BlockState air = Blocks.AIR.defaultBlockState();
        final int z = 8;
        for (int x = 1; x <= 14; x++) {
            int floor = x <= 2 ? 5 : (x <= 6 ? 4 : 3);
            for (int y = floor + 1; y <= 10; y++) {
                s.set(x, y, z, air);
            }
        }
        if (ceiling) {
            BlockState stone = Blocks.STONE.defaultBlockState();
            for (int x = 5; x <= 7; x++) {
                s.set(x, 9, z, stone); // takeoff floor y=5 -> feet 6 -> feet+3 = 9
            }
        }
        return toGrid(s);
    }

    /**
     * The sealed falling course (corridor at z=8): takeoff platform floor y=5 (x=1..2), a bottomless
     * chasm x=3..6 (air to the section floor; below the grid is unbuilt so {@code Fall} never lands and
     * no shorter falling landing exists), landing platform floor y=3 (x=7..14) — the specimen's
     * gap-4/drop-2. Body air to y=10. {@code ceilingXs} places stone at y=9 (takeoff feet+3) at those x.
     */
    private static NavGridView buildFallingCourse(int[] ceilingXs) {
        PalettedContainer<BlockState> s = solidSection();
        BlockState air = Blocks.AIR.defaultBlockState();
        final int z = 8;
        for (int x = 1; x <= 2; x++) {
            for (int y = 6; y <= 10; y++) {
                s.set(x, y, z, air);
            }
        }
        for (int x = 3; x <= 6; x++) {
            for (int y = 0; y <= 10; y++) {
                s.set(x, y, z, air);
            }
        }
        for (int x = 7; x <= 14; x++) {
            for (int y = 4; y <= 10; y++) {
                s.set(x, y, z, air);
            }
        }
        if (ceilingXs != null) {
            BlockState stone = Blocks.STONE.defaultBlockState();
            for (int x : ceilingXs) {
                s.set(x, 9, z, stone); // takeoff floor y=5 -> feet 6 -> feet+3 = 9
            }
        }
        return toGrid(s);
    }

    /**
     * The sealed flat course (corridor at z=8, the {@code ParkourTest} shape): platforms floor y=5,
     * bottomless 2-gap at x=5..6, body air y=6..10. {@code ceilingX} places one stone at
     * {@code (ceilingX, ceilingY, 8)}; {@code ceilingX == -2} lays a CONTINUOUS ceiling at
     * {@code ceilingY} over the whole corridor (the tunnel case); {@code -1} places none.
     */
    private static NavGridView buildFlatCourse(int ceilingX, int ceilingY) {
        PalettedContainer<BlockState> s = solidSection();
        BlockState air = Blocks.AIR.defaultBlockState();
        final int z = 8;
        for (int x = 1; x <= 14; x++) {
            for (int y = 6; y <= 10; y++) {
                s.set(x, y, z, air);
            }
        }
        for (int x = 5; x <= 6; x++) {
            for (int y = 0; y <= 5; y++) {
                s.set(x, y, z, air); // the bottomless chasm
            }
        }
        BlockState stone = Blocks.STONE.defaultBlockState();
        if (ceilingX == -2) {
            for (int x = 1; x <= 14; x++) {
                s.set(x, ceilingY, z, stone);
            }
        } else if (ceilingX >= 0) {
            s.set(ceilingX, ceilingY, z, stone);
        }
        return toGrid(s);
    }

    /**
     * The sealed diagonal course ({@code DiagonalParkourTest}'s shape, carved one row higher): travel
     * cells {@code (t,t), t=2..12} floored at y=5 with body air y=6..10; swept corner columns bottomless
     * (never standable — seals cardinal zig-zags) except {@code (6,5)}, which keeps its stone floor (the
     * solid corner the arc must clear); the 1-cell diagonal chasm at {@code (6,6)}. {@code ceiling}
     * (nullable) places one stone block at that cell.
     */
    private static NavGridView buildDiagonalCourse(BlockPos ceiling) {
        PalettedContainer<BlockState> s = solidSection();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int t = 2; t <= 12; t++) {
            for (int y = 6; y <= 10; y++) {
                s.set(t, y, t, air);
            }
        }
        for (int t = 2; t <= 11; t++) {
            carveCorner(s, air, t + 1, t);
            carveCorner(s, air, t, t + 1);
        }
        for (int y = 0; y <= 10; y++) {
            s.set(6, y, 6, air); // the diagonal chasm cell
        }
        if (ceiling != null) {
            s.set(ceiling.getX(), ceiling.getY(), ceiling.getZ(), Blocks.STONE.defaultBlockState());
        }
        return toGrid(s);
    }

    private static void carveCorner(PalettedContainer<BlockState> s, BlockState air, int x, int z) {
        int yFrom = (x == 6 && z == 5) ? 6 : 0;
        for (int y = yFrom; y <= 10; y++) {
            s.set(x, y, z, air);
        }
    }
}
