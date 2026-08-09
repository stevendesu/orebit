package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.platform.BlockLookup;
import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;

/**
 * Stage-1 (inert classification) proof for TRAPDOORS (DESIGN-trapdoors.md §2–§4). A {@link TrapDoorBlock}
 * packs its HORIZONTAL_FACING into the SHARED facing field (bits 8–9), HALF into the shared bit 10 (1=TOP),
 * OPEN into the shared bit 43, and hand-toggleable into the shared bit 50 (every trapdoor except IRON) —
 * ZERO new bits; the {@code openable} kind (14–15) disambiguates. Geometry stays generic (§2 table):
 * closed-BOTTOM → topY 3 {@code SHAPE_PARTIAL_LOW} standable plate; closed-TOP → topY 16 {@code SHAPE_OTHER}
 * standable hatch; open → topY 16 {@code SHAPE_OTHER} + NARROW_TOP wall panel, not standable.
 * {@link NavBlock#trapdoorBlockedFace} DERIVES the 6-way blocked face (§3); {@link NavBlock#ceilingMinY}
 * exposes the §4 uniform top-band heights; and the CRITICAL contract — pinned over EVERY registered
 * trapdoor blockstate — is that {@link NavBlock#withTrapdoorOpen} re-derives geometry so exactly that it
 * reproduces the interned descriptor of the real toggled state bit-for-bit (§2).
 */
class TrapdoorClassificationTest {

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

    private static BlockState td(Block block, Direction facing, boolean open, Half half, boolean waterlogged) {
        return block.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(BlockStateProperties.OPEN, open)
                .setValue(BlockStateProperties.HALF, half)
                .setValue(BlockStateProperties.WATERLOGGED, waterlogged);
    }

    // N=0 E=1 S=2 W=3 (the NavBlock cardinal convention).
    private static final int N = 0, E = 1, S = 2, W = 3;

    private static int ord(Direction d) {
        switch (d) {
            case EAST:  return E;
            case SOUTH: return S;
            case WEST:  return W;
            default:    return N;
        }
    }

    // ---- (1) all 6 collision shapes × {oak, iron, copper, waterlogged oak} pack the §2 table ---------

    /** Asserts the full §2 six-shape table for one material flavor (2 closed halves + 4 open facings). */
    private static void assertSixShapes(Block block, boolean waterlogged, boolean expectToggleable) {
        String who = block + (waterlogged ? " (waterlogged)" : "");

        // closed, half=BOTTOM → a 3/16 floor plate: topY 3, PARTIAL_LOW, standable (unless waterlogged).
        long cb = NavBlock.descriptorFor(td(block, Direction.NORTH, false, Half.BOTTOM, waterlogged));
        assertTrue(NavBlock.isTrapdoor(cb), who + " closed-bottom classifies as a trapdoor");
        assertFalse(NavBlock.isDoor(cb), who + " is not a door");
        assertFalse(NavBlock.isStair(cb), who + " is not a stair, despite sharing the facing/half bits");
        assertEquals(3, NavBlock.topY(cb), who + " closed-bottom top at 3/16");
        assertEquals(NavBlock.SHAPE_PARTIAL_LOW, NavBlock.shape(cb), who + " closed-bottom is a low plate");
        assertFalse(NavBlock.isNarrowTop(cb), who + " closed-bottom is a full-footprint plate");
        assertEquals(!waterlogged, NavBlock.isStandable(cb),
                who + " closed-bottom standable iff dry (fluid kills STANDABLE)");
        assertFalse(NavBlock.trapdoorHalfTop(cb), who + " BOTTOM half → bit 10 clear");
        assertFalse(NavBlock.trapdoorOpen(cb), who + " CLOSED → bit 43 clear");

        // closed, half=TOP → plate flush with the cell top: topY 16, OTHER, standable (unless waterlogged).
        long ct = NavBlock.descriptorFor(td(block, Direction.NORTH, false, Half.TOP, waterlogged));
        assertEquals(16, NavBlock.topY(ct), who + " closed-top top flush at 16/16");
        assertEquals(NavBlock.SHAPE_OTHER, NavBlock.shape(ct), who + " closed-top is OTHER (minY 13/16 ≠ cube)");
        assertFalse(NavBlock.isNarrowTop(ct), who + " closed-top is a full-footprint plate");
        assertEquals(!waterlogged, NavBlock.isStandable(ct), who + " closed-top standable iff dry");
        assertTrue(NavBlock.trapdoorHalfTop(ct), who + " TOP half → bit 10 set");

        // open × 4 facings → a 3/16 full-height wall panel: topY 16, OTHER + NARROW_TOP, never standable.
        for (Direction facing : new Direction[] { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST }) {
            long o = NavBlock.descriptorFor(td(block, facing, true, Half.BOTTOM, waterlogged));
            assertEquals(16, NavBlock.topY(o), who + " open " + facing + " panel is full-height");
            assertEquals(NavBlock.SHAPE_OTHER, NavBlock.shape(o), who + " open " + facing + " is OTHER");
            assertTrue(NavBlock.isNarrowTop(o), who + " open " + facing + " is a narrow wall panel");
            assertFalse(NavBlock.isStandable(o), who + " open " + facing + " is not a floor");
            // openTrapdoor is the §4 body-passable admit — WATERLOGGED cells are excluded (§10: they stay
            // conservative walls; the cell is a full water source the walk family must not enter).
            assertEquals(!waterlogged, NavBlock.openTrapdoor(o),
                    who + " open " + facing + " reads openTrapdoor iff dry");
            assertEquals(ord(facing), NavBlock.trapdoorFacing(o),
                    who + " open " + facing + " packs its facing into bits 8–9");
        }

        // Toggleable (shared bit 50) + fluid follow the material/waterlog, uniformly across the shapes.
        for (long d : new long[] { cb, ct }) {
            assertEquals(expectToggleable, NavBlock.handToggleable(d),
                    who + " hand-toggleable iff not iron (shared bit 50)");
            assertEquals(waterlogged, NavBlock.isWaterloggedNow(d), who + " fluid field tracks waterlogged");
            assertTrue(NavBlock.isWaterloggable(d), who + " trapdoors are statically waterloggable");
        }
    }

    @Test
    void oakPacksTheSixShapeTable() {
        assertSixShapes(Blocks.OAK_TRAPDOOR, false, true);
    }

    @Test
    void ironPacksTheSixShapeTableButIsNotToggleable() {
        assertSixShapes(Blocks.IRON_TRAPDOOR, false, false);
    }

    @Test
    void copperPacksTheSixShapeTableAndIsToggleable() {
        assertSixShapes(Blocks.COPPER_TRAPDOOR, false, true);
    }

    @Test
    void waterloggedOakPacksTheSixShapeTable() {
        assertSixShapes(Blocks.OAK_TRAPDOOR, true, true);
    }

    // ---- (2) blocked-FACE table (§3): closed → DOWN/UP per half; open → the cardinal opposite facing --

    @Test
    void closedTrapdoorBlocksTheVerticalFaceOfItsHalf() {
        for (Direction facing : new Direction[] { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST }) {
            assertEquals(NavBlock.FACE_DOWN, blocked(facing, false, Half.BOTTOM),
                    "closed BOTTOM (floor plate) blocks DOWN regardless of facing " + facing);
            assertEquals(NavBlock.FACE_UP, blocked(facing, false, Half.TOP),
                    "closed TOP (ceiling plate) blocks UP regardless of facing " + facing);
        }
    }

    @Test
    void openTrapdoorBlocksTheCardinalOppositeFacing() {
        // OPEN: panel hugs the wall opposite facing — N→S, S→N, E→W, W→E; HALF is irrelevant when open.
        for (Half half : new Half[] { Half.BOTTOM, Half.TOP }) {
            assertEquals(S, blocked(Direction.NORTH, true, half), "open N blocks S (half=" + half + ")");
            assertEquals(N, blocked(Direction.SOUTH, true, half), "open S blocks N (half=" + half + ")");
            assertEquals(W, blocked(Direction.EAST, true, half),  "open E blocks W (half=" + half + ")");
            assertEquals(E, blocked(Direction.WEST, true, half),  "open W blocks E (half=" + half + ")");
        }
    }

    private static int blocked(Direction facing, boolean open, Half half) {
        return NavBlock.trapdoorBlockedFace(NavBlock.descriptorFor(td(Blocks.OAK_TRAPDOOR, facing, open, half, false)));
    }

    // ---- (3) ceilingMinY (§4): only uniform full-footprint top bands qualify --------------------------

    @Test
    void ceilingMinYAdmitsOnlyUniformTopBands() {
        assertEquals(13, NavBlock.ceilingMinY(NavBlock.descriptorFor(
                td(Blocks.OAK_TRAPDOOR, Direction.NORTH, false, Half.TOP, false))), "closed-top trapdoor → 13");
        assertEquals(8, NavBlock.ceilingMinY(NavBlock.descriptorFor(
                Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE,
                        net.minecraft.world.level.block.state.properties.SlabType.TOP))), "top slab → 8");
        assertEquals(0, NavBlock.ceilingMinY(NavBlock.descriptorFor(
                td(Blocks.OAK_TRAPDOOR, Direction.NORTH, false, Half.BOTTOM, false))), "closed-bottom (floor plate) → 0");
        assertEquals(0, NavBlock.ceilingMinY(NavBlock.descriptorFor(
                td(Blocks.OAK_TRAPDOOR, Direction.NORTH, true, Half.TOP, false))), "open panel → 0");
        assertEquals(0, NavBlock.ceilingMinY(NavBlock.descriptorFor(
                Blocks.STONE.defaultBlockState())), "full cube → 0 (not a top band)");
        assertEquals(0, NavBlock.ceilingMinY(NavBlock.descriptorFor(
                Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HALF, Half.TOP))),
                "TOP-half stair → 0 (riser descends to the floor on one side — unsound, §4)");
        assertEquals(0, NavBlock.ceilingMinY(NavBlock.descriptorFor(
                Blocks.AIR.defaultBlockState())), "air → 0 (callers test passability first)");
        // §10 conservative walls: a WATERLOGGED band never admits — walking under it would put the bot's
        // head inside a water source (unpriced, breath-blind); pre-arc these cells were walls.
        assertEquals(0, NavBlock.ceilingMinY(NavBlock.descriptorFor(
                td(Blocks.OAK_TRAPDOOR, Direction.NORTH, false, Half.TOP, true))),
                "waterlogged closed-top trapdoor → 0 (conservative wall)");
        assertEquals(0, NavBlock.ceilingMinY(NavBlock.descriptorFor(
                Blocks.OAK_SLAB.defaultBlockState()
                        .setValue(BlockStateProperties.SLAB_TYPE,
                                net.minecraft.world.level.block.state.properties.SlabType.TOP)
                        .setValue(BlockStateProperties.WATERLOGGED, true))),
                "waterlogged top slab → 0 (conservative wall)");
    }

    // ---- (4) doors keep their behavior on the now-shared bit 50 (the rename/widening guard) ----------

    @Test
    void doorToggleableSemanticsUnchangedOnSharedBit() {
        BlockState oakDoor = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        BlockState ironDoor = Blocks.IRON_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        long oak = NavBlock.descriptorFor(oakDoor);
        long iron = NavBlock.descriptorFor(ironDoor);
        assertTrue(NavBlock.doorToggleable(oak), "oak door still hand-toggleable");
        assertTrue(NavBlock.handToggleable(oak), "kind-neutral read agrees for doors");
        assertFalse(NavBlock.doorToggleable(iron), "iron door still redstone-only");
        assertFalse(NavBlock.handToggleable(iron), "kind-neutral read agrees for iron door");
        assertFalse(NavBlock.isTrapdoor(oak), "a door is not a trapdoor (openable kind discriminates)");
    }

    // ---- (5) POWERED is unread → powered variants dedup; per-block descriptor count is bounded --------

    @Test
    void poweredVariantsDedupAndOakDescriptorCountIsExact() {
        BlockState base = td(Blocks.OAK_TRAPDOOR, Direction.EAST, true, Half.TOP, false);
        assertEquals(NavBlock.descriptorFor(base),
                NavBlock.descriptorFor(base.setValue(BlockStateProperties.POWERED, true)),
                "POWERED is deliberately unread — powered variants dedup");

        Set<Long> oakDescriptors = new HashSet<>();
        for (BlockState st : Blocks.OAK_TRAPDOOR.getStateDefinition().getPossibleStates()) {
            oakDescriptors.add(NavBlock.descriptorFor(st));
        }
        // 4 facings × 2 halves × 2 open × 2 waterlogged = 32 (POWERED folds away): the §2 navtype budget.
        assertEquals(32, oakDescriptors.size(),
                "oak trapdoor descriptors: facing×half×open×waterlogged, powered dedup'd");
        assertTrue(NavBlock.navtypeCount() < 1024,
                "navtype count must stay under the 10-bit grid cap; was " + NavBlock.navtypeCount());
    }

    // ---- (6) CRITICAL (§2): withTrapdoorOpen reproduces EVERY real trapdoor state bit-for-bit ---------

    @Test
    void withTrapdoorOpenReproducesEveryRealTrapdoorStateBitForBit() {
        List<Block> trapdoors = new ArrayList<>();
        BlockLookup.forEachBlock(b -> { if (b instanceof TrapDoorBlock) trapdoors.add(b); });
        assertTrue(trapdoors.size() >= 9, "at least the 1.17.1 floor set (8 wood + iron); found " + trapdoors.size());

        int checked = 0;
        for (Block b : trapdoors) {
            for (BlockState s : b.getStateDefinition().getPossibleStates()) {
                long d = NavBlock.descriptorFor(s);
                for (boolean open : new boolean[] { false, true }) {
                    long expected = NavBlock.descriptorFor(s.setValue(BlockStateProperties.OPEN, open));
                    long actual = NavBlock.withTrapdoorOpen(d, open);
                    assertEquals(expected, actual, () -> "withTrapdoorOpen(desc(" + s + "), " + open
                            + ") must equal the interned descriptor of the real toggled state");
                    checked++;
                }
            }
        }
        System.out.println("[TrapdoorClassificationTest] bit-identity sweep: " + trapdoors.size()
                + " trapdoor blocks, " + checked + " (state × target) pairs");
        assertTrue(checked > 0, "sweep must cover at least one state");
    }

    // ---- (7) withOpenableOpen dispatches by kind: door = bit flip, trapdoor = geometry re-derivation --

    @Test
    void withOpenableOpenDispatchesOnKind() {
        // Door: dispatch lands on withDoorOpen — bit-identical to the real open/closed states.
        BlockState closedDoor = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                .setValue(BlockStateProperties.OPEN, false)
                .setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.RIGHT)
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        long dClosed = NavBlock.descriptorFor(closedDoor);
        long dOpen = NavBlock.descriptorFor(closedDoor.setValue(BlockStateProperties.OPEN, true));
        assertEquals(dOpen, NavBlock.withOpenableOpen(dClosed, true), "door SET_OPEN resolves via withDoorOpen");
        assertEquals(dClosed, NavBlock.withOpenableOpen(dOpen, false), "door SET_CLOSED resolves via withDoorOpen");

        // Trapdoor: dispatch lands on withTrapdoorOpen — full geometry re-derivation, both directions.
        BlockState closedTd = td(Blocks.OAK_TRAPDOOR, Direction.SOUTH, false, Half.BOTTOM, false);
        long tClosed = NavBlock.descriptorFor(closedTd);
        long tOpen = NavBlock.descriptorFor(closedTd.setValue(BlockStateProperties.OPEN, true));
        assertEquals(tOpen, NavBlock.withOpenableOpen(tClosed, true), "trapdoor SET_OPEN re-derives geometry");
        assertEquals(tClosed, NavBlock.withOpenableOpen(tOpen, false), "trapdoor SET_CLOSED re-derives geometry");
    }
}
