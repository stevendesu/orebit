package profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.orebit.mod.worldmodel.pathing.NavSectionBuilder;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.world.chunk.Palette;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.SingularPalette;

/**
 * De-risk test: confirms Minecraft can be bootstrapped headlessly under the
 * fabric-loader-junit Knot classloader, and that a PalettedContainer can be
 * built and reflected into. If this passes, the JMH benchmark can use the same
 * approach.
 */
public class McBootstrapTest {

    @Test
    void bootstrapsAndBuildsContainer() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();

        BlockState air = Blocks.AIR.getDefaultState();
        PalettedContainer<BlockState> c = new PalettedContainer<>(
                Block.STATE_IDS, air, PalettedContainer.PaletteProvider.BLOCK_STATE);

        // All air -> SingularPalette, and get() returns air.
        Palette<BlockState> palette = NavSectionBuilder.getPaletteViaReflection(c);
        assertTrue(palette instanceof SingularPalette, "expected SingularPalette, got " + palette.getClass());
        assertEquals(air, c.get(0, 0, 0));

        System.out.println("[McBootstrapTest] OK - palette=" + palette.getClass().getSimpleName());
    }
}
