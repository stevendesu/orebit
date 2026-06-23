package profile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.orebit.mod.worldmodel.navblock.NavBlock;
import com.orebit.mod.worldmodel.pathing.NavClassifier;
import com.orebit.mod.worldmodel.pathing.TraversalClass;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Exercises {@link NavClassifier} over synthetic descriptor grids (no world). Builds a 16³
 * {@code long[]} scratch in the canonical {@code (y<<8)|(z<<4)|x} order, places known blocks, and
 * asserts the resulting {@link TraversalClass} for a test cell.
 */
public class NavClassifierTest {

    private static long[] grid;
    private static long AIR;

    private static long[] freshGrid() {
        long[] g = new long[4096];
        Arrays.fill(g, AIR);
        return g;
    }

    private static void put(long[] g, int x, int y, int z, BlockState state) {
        g[(y << 8) | (z << 4) | x] = NavBlock.descriptorFor(state);
    }

    private static TraversalClass classify(long[] g) {
        return NavClassifier.classify(g, 8, 8, 8); // interior cell, away from edges
    }

    private static BlockState state(net.minecraft.world.level.block.Block b) {
        return b.defaultBlockState();
    }

    @Test
    void classifiesKnownArrangements() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        AIR = NavBlock.descriptor(NavBlock.AIR);

        // Solid floor, clear headroom -> CLEAR.
        long[] g = freshGrid();
        put(g, 8, 8, 8, state(Blocks.STONE));
        assertEquals(TraversalClass.CLEAR, classify(g), "stone floor with headroom");

        // Solid floor but a solid block in the body space -> BLOCKED (non-trivial headroom).
        g = freshGrid();
        put(g, 8, 8, 8, state(Blocks.STONE));
        put(g, 8, 9, 8, state(Blocks.STONE));
        assertEquals(TraversalClass.BLOCKED, classify(g), "stone ceiling blocks headroom");

        // Unbreakable ceiling -> BLOCKED (255 hardness must dominate, fixing the old -1 sentinel bug).
        g = freshGrid();
        put(g, 8, 8, 8, state(Blocks.STONE));
        put(g, 8, 9, 8, state(Blocks.BEDROCK));
        assertEquals(TraversalClass.BLOCKED, classify(g), "bedrock ceiling");

        // Open air with nothing to stand on or attach to -> SLOW (a fall).
        g = freshGrid();
        assertEquals(TraversalClass.SLOW, classify(g), "open air = fall");

        // Open air with a solid neighbour to bridge from -> EASY.
        g = freshGrid();
        put(g, 7, 8, 8, state(Blocks.STONE));
        assertEquals(TraversalClass.EASY, classify(g), "bridgeable gap");

        // Slippery surface -> SLOW.
        g = freshGrid();
        put(g, 8, 8, 8, state(Blocks.ICE));
        assertEquals(TraversalClass.SLOW, classify(g), "ice floor");

        // Bottom slab is standable -> CLEAR.
        g = freshGrid();
        put(g, 8, 8, 8, state(Blocks.OAK_SLAB));
        assertEquals(TraversalClass.CLEAR, classify(g), "bottom slab floor");

        // Lava as floor -> BLOCKED (damaging).
        g = freshGrid();
        put(g, 8, 8, 8, state(Blocks.LAVA));
        assertEquals(TraversalClass.BLOCKED, classify(g), "lava floor");

        // Water as floor -> BLOCKED (not standable; swim is a later movement, not the coarse grid).
        g = freshGrid();
        put(g, 8, 8, 8, state(Blocks.WATER));
        assertEquals(TraversalClass.BLOCKED, classify(g), "water floor");

        // Gravity block in the upper body space -> BLOCKED (would cascade if disturbed).
        g = freshGrid();
        put(g, 8, 8, 8, state(Blocks.STONE));
        put(g, 8, 10, 8, state(Blocks.SAND));
        assertEquals(TraversalClass.BLOCKED, classify(g), "sand overhead");

        // Clearable hazard (fire) in the body space over a solid floor -> SLOW.
        g = freshGrid();
        put(g, 8, 8, 8, state(Blocks.STONE));
        put(g, 8, 9, 8, state(Blocks.FIRE));
        assertEquals(TraversalClass.SLOW, classify(g), "fire in headroom");
    }
}
