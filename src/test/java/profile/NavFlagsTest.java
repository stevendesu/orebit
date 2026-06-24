package profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.orebit.mod.worldmodel.navblock.NavBlock;
import com.orebit.mod.worldmodel.pathing.NavFlags;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Exercises {@link NavFlags} over synthetic descriptor grids (no world). Builds a 16³ {@code long[]}
 * scratch in the canonical {@code (y<<8)|(z<<4)|x} order, places known blocks around an interior floor
 * cell, and asserts the resulting neighbour-property bitmask (headroom level, edit-hazard, walk-through
 * hazard, placeable-neighbour). Replaces the old {@code NavClassifierTest} (the 4-value class is gone).
 */
public class NavFlagsTest {

    private static long AIR;

    private static long[] freshGrid() {
        long[] g = new long[4096];
        Arrays.fill(g, AIR);
        return g;
    }

    private static void put(long[] g, int x, int y, int z, BlockState state) {
        g[(y << 8) | (z << 4) | x] = NavBlock.descriptorFor(state);
    }

    private static int flags(long[] g) {
        return NavFlags.compute(g, 8, 8, 8); // interior floor cell, away from edges
    }

    private static BlockState state(net.minecraft.world.level.block.Block b) {
        return b.defaultBlockState();
    }

    @Test
    void computesNeighbourFacts() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        AIR = NavBlock.descriptor(NavBlock.AIR);

        // --- HEADROOM levels (clearance above the floor) ----------------------------------------
        // Stone floor, all air above -> JUMP (3 clear cells), no other flags.
        long[] g = freshGrid();
        put(g, 8, 8, 8, state(Blocks.STONE));
        int f = flags(g);
        assertEquals(NavFlags.HEADROOM_JUMP, NavFlags.headroom(f), "stone floor, open above");
        assertFalse(NavFlags.risksEdit(f), "no edit hazard over open stone");
        assertFalse(NavFlags.clearableHazard(f), "no walk-through hazard");

        // Solid block directly above the floor -> NONE (can't even crawl).
        g = freshGrid();
        put(g, 8, 8, 8, state(Blocks.STONE));
        put(g, 8, 9, 8, state(Blocks.STONE));
        assertEquals(NavFlags.HEADROOM_NONE, NavFlags.headroom(flags(g)), "blocked at feet");

        // Clear at feet, solid at head -> CRAWL (1-tall gap).
        g = freshGrid();
        put(g, 8, 8, 8, state(Blocks.STONE));
        put(g, 8, 10, 8, state(Blocks.STONE));
        assertEquals(NavFlags.HEADROOM_CRAWL, NavFlags.headroom(flags(g)), "1-tall gap");

        // Clear feet + head, solid one higher -> WALK (2-tall, no room to jump).
        g = freshGrid();
        put(g, 8, 8, 8, state(Blocks.STONE));
        put(g, 8, 11, 8, state(Blocks.STONE));
        assertEquals(NavFlags.HEADROOM_WALK, NavFlags.headroom(flags(g)), "2-tall gap");

        // --- RISKY_EDIT (don't break/place here) -------------------------------------------------
        // A gravity block overhead -> editing the body space could drop it.
        g = freshGrid();
        put(g, 8, 8, 8, state(Blocks.STONE));
        put(g, 8, 10, 8, state(Blocks.SAND));
        assertTrue(NavFlags.risksEdit(flags(g)), "sand overhead = risky edit");

        // A fluid in a horizontal neighbour of the body space, not draining down -> risky.
        g = freshGrid();
        put(g, 8, 8, 8, state(Blocks.STONE));
        put(g, 7, 9, 8, state(Blocks.WATER)); // beside the feet cell; (7,8,8) stays air (not draining)
        assertTrue(NavFlags.risksEdit(flags(g)), "adjacent water = risky edit");

        // --- CLEARABLE_HAZARD (walk-through, cost not block) -------------------------------------
        // Fire in the body space over a solid floor.
        g = freshGrid();
        put(g, 8, 8, 8, state(Blocks.STONE));
        put(g, 8, 9, 8, state(Blocks.FIRE));
        f = flags(g);
        assertTrue(NavFlags.clearableHazard(f), "fire in headroom = clearable hazard");
        assertEquals(NavFlags.HEADROOM_JUMP, NavFlags.headroom(f), "fire is passable, so headroom stays");

        // --- PLACEABLE_NEIGHBOR (a face to bridge against) --------------------------------------
        // Empty floor cell with a solid horizontal neighbour.
        g = freshGrid();
        put(g, 7, 8, 8, state(Blocks.STONE));
        assertTrue(NavFlags.placeableNeighbor(flags(g)), "solid neighbour = bridgeable");

        // Open air with nothing around -> no placeable neighbour, JUMP headroom.
        g = freshGrid();
        f = flags(g);
        assertFalse(NavFlags.placeableNeighbor(f), "open air has no face to place against");
        assertEquals(NavFlags.HEADROOM_JUMP, NavFlags.headroom(f), "open air = full headroom");
    }
}
