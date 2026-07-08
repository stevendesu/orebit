package profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.orebit.mod.worldmodel.pathing.NavSectionBuilder;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.SingleValuePalette;

/**
 * De-risk test: confirms Minecraft can be bootstrapped headlessly under the
 * fabric-loader-junit Knot classloader, and that a PalettedContainer can be
 * built and reflected into. If this passes, the JMH benchmark can use the same
 * approach.
 */
public class McBootstrapTest {

    @Test
    void bootstrapsAndBuildsContainer() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        BlockState air = Blocks.AIR.defaultBlockState();
        PalettedContainer<BlockState> c = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));

        // All air -> SingleValuePalette, and get() returns air.
        Palette<BlockState> palette = NavSectionBuilder.getPaletteViaReflection(c);
        assertTrue(palette instanceof SingleValuePalette, "expected SingleValuePalette, got " + palette.getClass());
        assertEquals(air, c.get(0, 0, 0));

        System.out.println("[McBootstrapTest] OK - palette=" + palette.getClass().getSimpleName());
    }
}
