package com.orebit.mod.pathfinding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
import net.minecraft.world.level.chunk.Strategy;

/**
 * Bug-guard for the P6 sea-floor-swimming fix ({@link WindowTargeting#rederiveSwimTargetY} + the
 * {@link WindowTargeting#projectToStandableFloor(NavGridView, int, BlockPos, int) CENTER projection}'s water
 * clause). The pathology: a portal centroid's vertical coordinate is the region tier's Standable-Δy anchor —
 * the BOTTOM of the passable opening (or the region-row bottom for a uniform-water face) — so a raw swimmable
 * window target sat at seafloor depth and the block tier's ±2 goal tolerance forced the bot to dive to it at
 * every window commit. The fix re-derives a swimmable target's Y — and ONLY its Y — to the water cell in its
 * own column nearest the bot's feet, bounded to the raw cell's 16-tall region row and stopped at the first
 * non-swimmable cell; every non-water path must stay byte-identical.
 *
 * <p>Headless: hand-classified {@link NavSection}s behind the synthetic {@link NavGridView#overSections} view
 * (the {@code CuboidExtractorScanTest} pattern) — no {@code ServerLevel}, which is also why the helpers under
 * test are package-private statics taking an explicit grid.
 */
class WindowTargetingWaterYTest {

    private static final int MINY = 0;

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
    }

    // ---- (a) a raw mid-water target follows the bot's feet Y ------------------------------------------

    @Test
    void rederivesMidWaterTargetTowardBotFeetY() {
        NavGridView grid = oceanWorld();
        // Raw portal-anchor target near the opening BOTTOM (y=2, one above the seafloor); bot swims at feet
        // y=12 in the same region row. The re-derived target must be the bot's level, not the anchor depth.
        BlockPos raw = new BlockPos(8, 2, 8);
        assertEquals(new BlockPos(8, 12, 8),
                WindowTargeting.rederiveSwimTargetY(grid, MINY, raw, 12),
                "a swimmable target's Y must re-derive to the bot's feet Y within the water column");
    }

    @Test
    void clampsToTheTargetRegionRowTop() {
        NavGridView grid = oceanWorld();
        // Bot far above the target's region row (feet y=25, row 0 = y 0..15): the re-derived Y clamps to the
        // row's swimmable top (15) — the target must stay inside the skeleton step's region.
        assertEquals(new BlockPos(8, 15, 8),
                WindowTargeting.rederiveSwimTargetY(grid, MINY, new BlockPos(8, 2, 8), 25),
                "re-derivation is bounded to the raw cell's own 16-tall region row");
    }

    @Test
    void clampsToTheSwimmableSpanBottom() {
        NavGridView grid = oceanWorld();
        // Bot below the span (feet y=0 is the stone seafloor): clamp to the lowest swimmable cell (y=1),
        // never into the floor.
        assertEquals(new BlockPos(8, 1, 8),
                WindowTargeting.rederiveSwimTargetY(grid, MINY, new BlockPos(8, 10, 8), 0),
                "re-derivation never leaves the swimmable span (no snap into the seafloor)");
    }

    // ---- (b) non-water paths are byte-identical -------------------------------------------------------

    @Test
    void nonWaterTargetsPassThroughUntouched() {
        NavGridView grid = dryWorld();
        BlockPos stone = new BlockPos(8, 5, 8); // inside the stone slab
        BlockPos air = new BlockPos(8, 12, 8);  // open air above it
        assertSame(stone, WindowTargeting.rederiveSwimTargetY(grid, MINY, stone, 12),
                "a solid cell must pass through as the SAME object (no re-derivation, no allocation)");
        assertSame(air, WindowTargeting.rederiveSwimTargetY(grid, MINY, air, 3),
                "a dry air cell must pass through as the SAME object (no re-derivation, no allocation)");
    }

    @Test
    void dryCenterProjectionIsUnchanged() {
        NavGridView grid = dryWorld();
        // The pre-change nearest-first standable scan from cy=12 finds the slab top at y=8; the water clause
        // must not perturb it (bot feet Y is never read on a dry column).
        assertEquals(new BlockPos(8, 8, 8),
                WindowTargeting.projectToStandableFloor(grid, MINY, new BlockPos(8, 12, 8), 3),
                "the dry-column CENTER projection must keep the pre-change standable result");
    }

    @Test
    void dryCenterProjectionStillNullWithNoFloor() {
        NavGridView grid = dryWorld();
        // Row 1 (y 16..31) is pure air: the projection found nothing before the fix and must still find
        // nothing (the caller then uses the raw center).
        assertNull(WindowTargeting.projectToStandableFloor(grid, MINY, new BlockPos(8, 24, 8), 3),
                "an all-air column must still project to null");
    }

    // ---- (c) the CENTER projection's water clause -----------------------------------------------------

    @Test
    void centerProjectionSwimsLevelInsteadOfDivingToTheSeafloor() {
        NavGridView grid = oceanWorld();
        // Pre-fix, the nearest-first standable scan from a mid-water center (cy=10) found the SEAFLOOR at
        // y=0 (the row's only standable cell) — the dive. The water clause must return the bot's level.
        BlockPos projected = WindowTargeting.projectToStandableFloor(grid, MINY, new BlockPos(8, 10, 8), 3);
        assertEquals(new BlockPos(8, 3, 8), projected,
                "a swimmable center must project to the water cell nearest the bot's feet Y");
        assertNotEquals(0, projected.getY(), "the CENTER projection must not dive to the seafloor");
    }

    @Test
    void centerProjectionLevelsInAnAllWaterRow() {
        NavGridView grid = oceanWorld();
        // Row 1 (y 16..31) holds water y 16..20 under air: pre-fix the scan found no standable cell and
        // returned null (→ the raw row-center was used). Now a swimmable center yields a level target, with
        // the span stopping at the first non-swimmable cell (the air at y=21).
        assertEquals(new BlockPos(8, 19, 8),
                WindowTargeting.projectToStandableFloor(grid, MINY, new BlockPos(8, 18, 8), 19),
                "an open-ocean center must yield the bot-level water cell (span stops at the surface)");
    }

    // ---- fixtures -------------------------------------------------------------------------------------

    /**
     * One chunk (0,0), minY=0: a stone seafloor layer at y=0, water y=1..20, air above (y 21..63). Row 0
     * (y 0..15) is the mixed seafloor row; row 1 (y 16..31) is water-under-air (the sea-surface row).
     */
    private static NavGridView oceanWorld() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();

        PalettedContainer<BlockState> s0 = emptyStates();
        fillLayer(s0, 0, stone);
        for (int y = 1; y < 16; y++) {
            fillLayer(s0, y, water);
        }
        PalettedContainer<BlockState> s1 = emptyStates();
        for (int y = 0; y <= 4; y++) { // world y 16..20
            fillLayer(s1, y, water);
        }
        return view(classify(s0, false), classify(s1, false), airSection(), airSection());
    }

    /** One chunk (0,0), minY=0: a stone slab y=0..8, air above (y 9..63) — the dry control fixture. */
    private static NavGridView dryWorld() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        PalettedContainer<BlockState> s0 = emptyStates();
        for (int y = 0; y <= 8; y++) {
            fillLayer(s0, y, stone);
        }
        return view(classify(s0, false), airSection(), airSection(), airSection());
    }

    private static PalettedContainer<BlockState> emptyStates() {
        return new PalettedContainer<>(Blocks.AIR.defaultBlockState(),
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
    }

    private static void fillLayer(PalettedContainer<BlockState> states, int yLocal, BlockState state) {
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                states.set(x, yLocal, z, state);
            }
        }
    }

    private static NavSection classify(PalettedContainer<BlockState> states, boolean allAir) {
        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(states, allAir, section.getTraversalGrid());
        return section;
    }

    private static NavSection airSection() {
        return classify(emptyStates(), true);
    }

    private static NavGridView view(NavSection... column) {
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), column);
        return NavGridView.overSections(MINY, chunks);
    }
}
