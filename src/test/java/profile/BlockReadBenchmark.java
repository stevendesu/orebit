package profile;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import com.orebit.mod.worldmodel.pathing.NavSectionBuilder;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.collection.IndexedIterable;
import net.minecraft.util.collection.Int2ObjectBiMap;
import net.minecraft.util.collection.PaletteStorage;
import net.minecraft.world.chunk.ArrayPalette;
import net.minecraft.world.chunk.BiMapPalette;
import net.minecraft.world.chunk.IdListPalette;
import net.minecraft.world.chunk.Palette;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.SingularPalette;

/**
 * Reconstructs the block-reading optimization journey from
 * docs/Optimizations/block_reading.md as a rigorous JMH benchmark, so we can get
 * trustworthy, warmed-up ns/block numbers for the three read strategies that
 * survive the "bypass the World API" step:
 *
 *   containerGet   -> STEP 2/3: container.get(x,y,z) (megamorphic palette.get)
 *   reflectIndexed -> STEP 4: reflect into palette internals, per-type switch, indexed access
 *   reflectForEach -> STEP 5: reflect once, then PaletteStorage.forEach sequential scan
 *
 * STEP 1 (world.getBlockState) is the documented ~3,000,000 ns baseline; it
 * requires a live World and is intentionally not synthesized here.
 *
 * Each benchmark scans all 4096 cells of a 16^3 section and consumes every
 * BlockState (the read is the differentiator; downstream classification cost is
 * identical across strategies). Containers are built to force each palette type.
 * Result is divided by 4096 in reporting to get ns/block.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class BlockReadBenchmark {

    public enum Scenario {
        /** All air -> SingularPalette (the ~60% common case). */
        SINGULAR(0),
        /** ~8 distinct blocks -> ArrayPalette. */
        ARRAY(8),
        /** ~64 distinct blocks -> BiMapPalette. */
        BIMAP(64);

        final int distinct;
        Scenario(int distinct) { this.distinct = distinct; }
    }

    private static final int CELLS = 16 * 16 * 16; // 4096

    @Param({"SINGULAR", "ARRAY", "BIMAP"})
    private Scenario scenario;

    private PalettedContainer<BlockState> container;

    private static boolean bootstrapped = false;

    @Setup(Level.Trial)
    public void setup() {
        if (!bootstrapped) {
            SharedConstants.createGameVersion();
            Bootstrap.initialize();
            bootstrapped = true;
        }

        container = newContainer(scenario.distinct);

        // Report which palette type the scenario actually produced, and confirm
        // PaletteStorage.forEach visits all 4096 cells (guards reflectForEach
        // against silently measuring nothing on, e.g., an empty storage).
        Palette<BlockState> palette = NavSectionBuilder.getPaletteViaReflection(container);
        PaletteStorage storage = NavSectionBuilder.getStorageViaReflection(container);
        int[] visited = {0};
        storage.forEach(id -> visited[0]++);
        System.out.printf("[setup] scenario=%s palette=%s storage=%s forEachVisited=%d%n",
                scenario, palette.getClass().getSimpleName(),
                storage.getClass().getSimpleName(), visited[0]);
    }

    /** Build a container whose distinct-block count forces a particular palette type. */
    private static PalettedContainer<BlockState> newContainer(int distinct) {
        BlockState air = Blocks.AIR.getDefaultState();
        PalettedContainer<BlockState> c = new PalettedContainer<>(
                Block.STATE_IDS, air, PalettedContainer.PaletteProvider.BLOCK_STATE);

        if (distinct <= 0) {
            return c; // all air -> singular
        }

        List<BlockState> states = new ArrayList<>(distinct);
        for (Block b : Registries.BLOCK) {
            BlockState s = b.getDefaultState();
            if (s == air) continue;
            states.add(s);
            if (states.size() >= distinct) break;
        }

        int i = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    c.set(x, y, z, states.get(i % states.size()));
                    i++;
                }
            }
        }
        return c;
    }

    // ---- STEP 2/3: the standard container path (megamorphic palette.get) ----

    @Benchmark
    public void containerGet(Blackhole bh) {
        PalettedContainer<BlockState> c = container;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    bh.consume(c.get(x, y, z));
                }
            }
        }
    }

    // ---- STEP 4: reflect into palette internals, switch on type, indexed access ----

    @Benchmark
    public void reflectIndexed(Blackhole bh) {
        PalettedContainer<BlockState> c = container;
        PaletteStorage storage = NavSectionBuilder.getStorageViaReflection(c);
        Palette<BlockState> palette = NavSectionBuilder.getPaletteViaReflection(c);
        switch (palette) {
            case SingularPalette<BlockState> sp -> {
                BlockState entry = palette.get(0);
                for (int i = 0; i < CELLS; i++) { bh.consume(entry); }
            }
            case ArrayPalette<BlockState> ap -> {
                Object[] array = NavSectionBuilder.getArrayFieldViaReflection(ap);
                for (int i = 0; i < CELLS; i++) { bh.consume((BlockState) array[storage.get(i)]); }
            }
            case BiMapPalette<BlockState> bp -> {
                Int2ObjectBiMap<BlockState> map = NavSectionBuilder.getBiMapFieldViaReflection(bp);
                for (int i = 0; i < CELLS; i++) { bh.consume(map.get(storage.get(i))); }
            }
            case IdListPalette<BlockState> ip -> {
                IndexedIterable<BlockState> idList = NavSectionBuilder.getIdListFieldViaReflection(ip);
                for (int i = 0; i < CELLS; i++) { bh.consume(idList.get(storage.get(i))); }
            }
            default -> throw new IllegalStateException("Unexpected palette: " + palette);
        }
    }

    // ---- STEP 5: reflect once, then PaletteStorage.forEach sequential scan ----

    @Benchmark
    public void reflectForEach(Blackhole bh) {
        PalettedContainer<BlockState> c = container;
        PaletteStorage storage = NavSectionBuilder.getStorageViaReflection(c);
        Palette<BlockState> palette = NavSectionBuilder.getPaletteViaReflection(c);
        switch (palette) {
            case SingularPalette<BlockState> sp -> {
                BlockState entry = palette.get(0);
                storage.forEach(id -> bh.consume(entry));
            }
            case ArrayPalette<BlockState> ap -> {
                Object[] array = NavSectionBuilder.getArrayFieldViaReflection(ap);
                storage.forEach(id -> bh.consume((BlockState) array[id]));
            }
            case BiMapPalette<BlockState> bp -> {
                Int2ObjectBiMap<BlockState> map = NavSectionBuilder.getBiMapFieldViaReflection(bp);
                storage.forEach(id -> bh.consume(map.get(id)));
            }
            case IdListPalette<BlockState> ip -> {
                IndexedIterable<BlockState> idList = NavSectionBuilder.getIdListFieldViaReflection(ip);
                storage.forEach(id -> bh.consume(idList.get(id)));
            }
            default -> throw new IllegalStateException("Unexpected palette: " + palette);
        }
    }
}
