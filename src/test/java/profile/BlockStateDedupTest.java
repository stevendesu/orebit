package profile;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.BambooBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.PointedDripstoneBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Direction;

/**
 * Counts how many DISTINCT navigation fingerprints all ~28k Minecraft block
 * states collapse to, if we dedup LOSSLESSLY on nav-relevant fields (no lumping).
 * Answers: does a per-BlockState NavBlock fit in a short (<65,536), and how big
 * is the descriptor table for cache analysis?
 *
 * Two fidelity levels:
 *   FULL  = exact hardness + exact collision-top-Y (max-lossless upper bound)
 *   PACKED = collision quantized to 1/16, hardness quantized to 8 bits (what we'd
 *            actually store in the 64-bit descriptor → the real table size)
 */
public class BlockStateDedupTest {

    @Test
    void countDistinctNavFingerprints() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();

        // Built after bootstrap — referencing Blocks.* before init throws.
        final Set<Block> SLIPPERY = Set.of(
                Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.FROSTED_ICE);
        final Set<Block> SLOW = Set.of(
                Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.HONEY_BLOCK, Blocks.SLIME_BLOCK, Blocks.COBWEB);
        // Climbable by identity (BlockTags.CLIMBABLE needs datapack tags, unbound headless).
        final Set<Block> CLIMBABLE = Set.of(
                Blocks.LADDER, Blocks.VINE, Blocks.SCAFFOLDING, Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT,
                Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT, Blocks.WEEPING_VINES, Blocks.WEEPING_VINES_PLANT);

        Set<String> full = new HashSet<>();
        Set<String> packed = new HashSet<>();
        int blocks = 0, states = 0, errors = 0;
        String firstErr = null;

        for (Block block : Registries.BLOCK) {
            blocks++;
            for (BlockState state : block.getStateManager().getStates()) {
                states++;
                try {
                    boolean special = block instanceof BlockWithEntity
                            || block instanceof BambooBlock
                            || block instanceof PointedDripstoneBlock;

                    // solid faces (6 bits)
                    StringBuilder faces = new StringBuilder();
                    for (Direction d : Direction.values()) {
                        boolean solid = special || state.isSideSolidFullSquare(null, null, d);
                        faces.append(solid ? '1' : '0');
                    }

                    double maxY = special ? 1.0 : state.getCollisionShape(null, null).getMax(Direction.Axis.Y);
                    float hardness = block.getHardness();
                    int luminance = state.getLuminance();
                    boolean replaceable = state.isReplaceable();
                    boolean toolReq = state.isToolRequired();
                    boolean waterloggable = state.contains(Properties.WATERLOGGED);
                    boolean climbable = CLIMBABLE.contains(block);
                    boolean gravity = block instanceof FallingBlock;

                    FluidState fs = state.getFluidState();
                    int fluid = fs.isEmpty() ? 0
                            : (fs.getFluid() == Fluids.LAVA || fs.getFluid() == Fluids.FLOWING_LAVA ? 2 : 1);

                    int openable = block instanceof DoorBlock ? 1
                            : block instanceof TrapdoorBlock ? 2
                            : block instanceof FenceGateBlock ? 3 : 0;

                    Block b = state.getBlock();
                    int penalty = SLIPPERY.contains(b) ? 2 : SLOW.contains(b) ? 1 : 0;

                    String common = faces + "|" + luminance + "|" + (replaceable ? 1 : 0)
                            + (toolReq ? 1 : 0) + (waterloggable ? 1 : 0) + (climbable ? 1 : 0)
                            + (gravity ? 1 : 0) + "|" + fluid + "|" + openable + "|" + penalty;

                    full.add(common + "|" + hardness + "|" + maxY);

                    int qHard = hardness < 0 ? 255 : Math.min(254, Math.round(hardness * 5f));
                    int qY = Math.min(31, (int) Math.round(maxY * 16));
                    packed.add(common + "|" + qHard + "|" + qY);
                } catch (Throwable e) {
                    errors++;
                    if (firstErr == null) {
                        StackTraceElement[] st = e.getStackTrace();
                        firstErr = e + (st.length > 0 ? " @ " + st[0] : "")
                                + " [block=" + Registries.BLOCK.getId(block) + "]";
                    }
                }
            }
        }

        System.out.println("==================== NAV FINGERPRINT DEDUP ====================");
        System.out.println("blocks                 : " + blocks);
        System.out.println("block states (total)   : " + states);
        System.out.println("errors (skipped)       : " + errors);
        System.out.println("first error            : " + firstErr);
        System.out.println("distinct FULL  (exact) : " + full.size());
        System.out.println("distinct PACKED (quant): " + packed.size());
        System.out.printf ("collapse ratio (packed): %.1fx  (%d states -> %d navtypes)%n",
                (double) (states - errors) / packed.size(), states - errors, packed.size());
        System.out.println("fits short (<65536)?   : " + (packed.size() < 65536 ? "YES" : "NO"));
        System.out.println("descriptor table size  : " + (packed.size() * 8) + " bytes (8B/navtype)");
        System.out.println("===============================================================");
    }
}
