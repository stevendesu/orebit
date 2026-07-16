package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Phase-0 (inert classification) proof for DOORS directional solidity. A {@link DoorBlock} packs its
 * HORIZONTAL_FACING into the SHARED facing field (bits 8–9), its DOOR_HINGE into bit 13, and OPEN into bit 43;
 * {@link NavBlock#doorBlockedEdge} DERIVES the single blocked cardinal edge (0=N 1=E 2=S 3=W) from those three
 * — CLOSED blocks the edge opposite facing, OPEN swings to a hinge-chosen perpendicular edge. This test pins the
 * bits AND asserts {@code doorBlockedEdge} against the owner's bytecode-verified ground truth table.
 */
class DoorClassificationTest {

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

    private static BlockState door(Block block, Direction facing, boolean open, DoorHingeSide hinge) {
        return block.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(BlockStateProperties.OPEN, open)
                .setValue(BlockStateProperties.DOOR_HINGE, hinge)
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
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

    // ---- (1) A door packs facing / hinge / open into the right bits, and is not a stair -------------

    @Test
    void doorPacksFacingHingeOpen() {
        long d = NavBlock.descriptorFor(door(Blocks.OAK_DOOR, Direction.EAST, true, DoorHingeSide.RIGHT));
        assertTrue(NavBlock.isDoor(d), "an oak door classifies as a door (openable == DOOR)");
        assertFalse(NavBlock.isStair(d), "a door is not a stair, despite sharing the facing bits");
        assertEquals(E, NavBlock.doorFacing(d), "EAST facing → ordinal 1 in the shared facing field");
        assertTrue(NavBlock.doorHinge(d), "RIGHT hinge → bit 13 set");
        assertTrue(NavBlock.doorOpen(d), "OPEN → bit 43 set");

        long closedLeftNorth = NavBlock.descriptorFor(door(Blocks.OAK_DOOR, Direction.NORTH, false, DoorHingeSide.LEFT));
        assertEquals(N, NavBlock.doorFacing(closedLeftNorth), "NORTH facing → ordinal 0");
        assertFalse(NavBlock.doorHinge(closedLeftNorth), "LEFT hinge → bit 13 clear");
        assertFalse(NavBlock.doorOpen(closedLeftNorth), "CLOSED → bit 43 clear");

        long stone = NavBlock.descriptorFor(Blocks.STONE.defaultBlockState());
        assertFalse(NavBlock.isDoor(stone), "a full block is not a door");
    }

    // ---- (2) doorBlockedEdge matches the owner's VERIFIED ground-truth table ------------------------

    @Test
    void closedDoorBlocksTheEdgeOppositeFacing() {
        // CLOSED: N→S, S→N, E→W, W→E.
        assertEquals(S, blocked(Direction.NORTH, false, DoorHingeSide.LEFT), "closed N blocks S");
        assertEquals(N, blocked(Direction.SOUTH, false, DoorHingeSide.LEFT), "closed S blocks N");
        assertEquals(W, blocked(Direction.EAST, false, DoorHingeSide.LEFT), "closed E blocks W");
        assertEquals(E, blocked(Direction.WEST, false, DoorHingeSide.LEFT), "closed W blocks E");
        // Hinge is irrelevant to a CLOSED door's blocked edge.
        assertEquals(S, blocked(Direction.NORTH, false, DoorHingeSide.RIGHT), "closed N blocks S regardless of hinge");
    }

    @Test
    void openDoorBlocksTheHingeChosenPerpendicularEdge() {
        // OPEN: facing N L→W R→E, S L→E R→W, E L→N R→S, W L→S R→N (owner's table).
        assertEquals(W, blocked(Direction.NORTH, true, DoorHingeSide.LEFT),  "open N hinge L blocks W");
        assertEquals(E, blocked(Direction.NORTH, true, DoorHingeSide.RIGHT), "open N hinge R blocks E");
        assertEquals(E, blocked(Direction.SOUTH, true, DoorHingeSide.LEFT),  "open S hinge L blocks E");
        assertEquals(W, blocked(Direction.SOUTH, true, DoorHingeSide.RIGHT), "open S hinge R blocks W");
        assertEquals(N, blocked(Direction.EAST, true, DoorHingeSide.LEFT),   "open E hinge L blocks N");
        assertEquals(S, blocked(Direction.EAST, true, DoorHingeSide.RIGHT),  "open E hinge R blocks S");
        assertEquals(S, blocked(Direction.WEST, true, DoorHingeSide.LEFT),   "open W hinge L blocks S");
        assertEquals(N, blocked(Direction.WEST, true, DoorHingeSide.RIGHT),  "open W hinge R blocks N");
    }

    private static int blocked(Direction facing, boolean open, DoorHingeSide hinge) {
        return NavBlock.doorBlockedEdge(NavBlock.descriptorFor(door(Blocks.OAK_DOOR, facing, open, hinge)));
    }

    // ---- (3) iron doors classify identically (P1 doesn't care about material) -----------------------

    @Test
    void ironDoorClassifiesLikeAnyDoor() {
        long openIron = NavBlock.descriptorFor(door(Blocks.IRON_DOOR, Direction.EAST, true, DoorHingeSide.RIGHT));
        assertTrue(NavBlock.isDoor(openIron), "an iron door is still a door (an open iron door is a passable doorway)");
        assertTrue(NavBlock.doorOpen(openIron), "the open iron door reads OPEN");
        assertEquals(S, NavBlock.doorBlockedEdge(openIron), "open E hinge R blocks S — material-independent");
    }

    // ---- (4) both door halves dedup to one navtype (facing/open/hinge shared, HALF not packed) -------

    @Test
    void bothHalvesShareOneDescriptor() {
        BlockState lower = door(Blocks.OAK_DOOR, Direction.SOUTH, true, DoorHingeSide.LEFT);
        BlockState upper = lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        assertEquals(NavBlock.descriptorFor(lower), NavBlock.descriptorFor(upper),
                "the two halves share FACING/OPEN/HINGE and don't pack HALF → identical descriptor (one navtype)");
    }

    // ---- (5) navtype budget: doors add a bounded number of navtypes, total stays under the 1024 cap --

    @Test
    void navtypeBudgetStaysUnderCap() {
        Set<Long> doorDescriptors = new HashSet<>();
        for (Block b : new Block[] { Blocks.OAK_DOOR, Blocks.IRON_DOOR }) {
            for (BlockState st : b.getStateDefinition().getPossibleStates()) {
                doorDescriptors.add(NavBlock.descriptorFor(st));
            }
        }
        int total = NavBlock.navtypeCount();
        System.out.println("[DoorClassificationTest] total navtypes = " + total
                + " (cap 1024); distinct oak+iron door descriptors = " + doorDescriptors.size());
        assertTrue(total < 1024, "navtype count must stay under the 10-bit grid cap; was " + total);
        // 4 facings × 2 hinges × 2 open = 16 configs per material; oak+iron ≈ 32 distinct door descriptors.
        assertTrue(doorDescriptors.size() <= 40,
                "door descriptors should be bounded (~16/material); was " + doorDescriptors.size());
    }
}
