package com.orebit.mod.pathfinding.blockpathfinder.cuboid;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.EditFixtures;
import com.orebit.mod.pathfinding.blockpathfinder.PathEdits;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
import com.orebit.mod.worldmodel.pathing.NavGridView;
import com.orebit.mod.worldmodel.pathing.NavSection;
import com.orebit.mod.worldmodel.pathing.NavSectionBuilder;
import com.orebit.mod.worldmodel.pathing.NavStore;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * Guard for Option D (forward-only edit-shrink — CUBOID-PERF-OPTIONS.md §D). The speculative edit-shrink in
 * {@link NavGridCuboidsView#cuboidAt} now only scans the FORWARD half of the box along the macro jump's travel
 * direction. This test pins the two invariants of that change:
 *
 * <ol>
 *   <li><b>A forward edit must still shrink the box</b> — skipping it would let a macro jump pass through a
 *       cell the path placed/broke (an invalid path, the worst failure mode). For a {@code +Y} pillar jump a
 *       PLACED block ABOVE the start cell must clamp {@code maxY} below it.</li>
 *   <li><b>A behind edit must be ignored</b> — that is the whole optimization (the bot's own placed pillar /
 *       bridge trail sits behind the node and can't affect a forward jump). For the same {@code +Y} jump a
 *       PLACED block BELOW the start cell must leave the box's forward ({@code maxY}) extent intact.</li>
 * </ol>
 *
 * <p>The world is a uniform air column over a stone floor (the {@code overSections} synthetic view, as in
 * {@link CuboidExtractorScanTest}). All edits are registered through the real {@link PathEdits#add} path (via
 * {@link EditFixtures}), so the box ∩ edits AABB bookkeeping is exercised exactly as in a live search. The test
 * lives in the {@code cuboid} package so it can read {@link Cuboid}'s package-private bounds directly.
 */
class ForwardShrinkTest {

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
    }

    private static final int SX = 8, SZ = 8; // the pillar column
    // Start FLOOR cell of the +Y pillar jump. Pillar passes its new-floor cell (feet) to cuboidAt; we model
    // that directly. Sits well inside the air column so the box has room both above (forward) and below (back).
    private static final int START_Y = 20;

    @Test
    void forwardPlacedEditShrinksTheBox() {
        NavGridView grid = buildAirOverStone();
        RegionBound corridor = new RegionBound(0, 15, 0, 60, 0, 15);

        // 1) Discover the unedited (committed) box so we can place edits relative to its real forward extent.
        Cuboid base = new Cuboid();
        NavGridCuboidsView noEdits = new NavGridCuboidsView(grid, new PathEdits(), corridor);
        noEdits.cuboidAt(SX, START_Y, SZ, Axes.AXIS_Y, +1, base);
        assertTrue(base.isValid(), "air column over stone — the base box must be valid");
        assertTrue(base.maxY > START_Y, "the base box must extend forward (up) past the start cell");
        int baseMaxY = base.maxY;

        // 2) A forward (ABOVE the start) PLACED edit, strictly inside the box's forward extent, in the column.
        int forwardY = START_Y + (baseMaxY - START_Y) / 2; // halfway up the forward run, inside the box
        assertTrue(forwardY > START_Y && forwardY < baseMaxY, "forward edit must be strictly inside the box");
        PathEdits fwd = EditFixtures.withPlaced(BlockPos.asLong(SX, forwardY, SZ));

        Cuboid out = new Cuboid();
        NavGridCuboidsView withFwd = new NavGridCuboidsView(grid, fwd, corridor);
        withFwd.cuboidAt(SX, START_Y, SZ, Axes.AXIS_Y, +1, out);

        assertTrue(out.isValid(), "the box stays valid (the start cell is kept); only the forward extent shrinks");
        assertTrue(out.maxY < forwardY,
                "FORWARD edit at y=" + forwardY + " must clamp the box below it; got maxY=" + out.maxY
                        + " — forward-only must NOT skip a forward edit (that would jump through a placed cell)");
        assertTrue(!out.contains(SX, forwardY, SZ),
                "the box must not contain the forward placed cell (the through-a-placed-block failure)");
    }

    @Test
    void behindPlacedEditIsIgnored() {
        NavGridView grid = buildAirOverStone();
        RegionBound corridor = new RegionBound(0, 15, 0, 60, 0, 15);

        // Baseline forward extent with no edits.
        Cuboid base = new Cuboid();
        NavGridCuboidsView noEdits = new NavGridCuboidsView(grid, new PathEdits(), corridor);
        noEdits.cuboidAt(SX, START_Y, SZ, Axes.AXIS_Y, +1, base);
        assertTrue(base.isValid());
        int baseMaxY = base.maxY;
        assertTrue(base.minY < START_Y, "the base box must extend below the start cell (room for a behind edit)");

        // A BEHIND (BELOW the start) PLACED edit, strictly inside the box's backward extent, in the column.
        int behindY = START_Y - 1;
        assertTrue(behindY >= base.minY, "behind edit must be inside the box's backward extent");
        PathEdits behind = EditFixtures.withPlaced(BlockPos.asLong(SX, behindY, SZ));

        Cuboid out = new Cuboid();
        NavGridCuboidsView withBehind = new NavGridCuboidsView(grid, behind, corridor);
        withBehind.cuboidAt(SX, START_Y, SZ, Axes.AXIS_Y, +1, out);

        assertTrue(out.isValid(), "a behind-only edit must not invalidate the box");
        assertTrue(out.maxY == baseMaxY,
                "BEHIND edit at y=" + behindY + " must be IGNORED — the forward (maxY) extent must be unchanged "
                        + "(was " + baseMaxY + ", got " + out.maxY + "); this is the optimization actually firing");
    }

    /** Uniform air column over a single stone floor layer at y=0; minY=0, no live level (the synthetic view). */
    private static NavGridView buildAirOverStone() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();

        // Lowest section: stone floor at section-local y=0, air above.
        PalettedContainer<BlockState> floorStates = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY, air, PalettedContainer.Strategy.SECTION_STATES);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                floorStates.set(x, 0, z, stone);
            }
        }
        NavSection floor = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(floorStates, false, floor.getTraversalGrid());

        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY, air, PalettedContainer.Strategy.SECTION_STATES);
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());

        NavSection[] column = { floor, airSection, airSection, airSection }; // y 0..63
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), column);
        return NavGridView.overSections(0, chunks);
    }
}
