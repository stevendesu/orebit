package com.orebit.mod.worldmodel.hpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.worldmodel.navblock.NavBlock;
import com.orebit.mod.worldmodel.pathing.NavSection;
import com.orebit.mod.worldmodel.pathing.NavSectionBuilder;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * Region-tier <b>climbable-as-air</b> flood connectivity (owner ruling 2026-08-09,
 * DESIGN-trapdoor-ladder-climb.md §6 follow-up — the openable-as-air sibling,
 * {@link OpenableFloodConnectivityTest}): {@link NavBlock#floodPassable} treats every CLIMBABLE cell
 * — ladder, scaffolding, the vine family — as passable for leaf flood membership, because "a ladder
 * is passable on 5/6 faces, and the other face adjoins a wall by definition. You can always walk
 * past a ladder, and it's arguably MORE passable than air since you can climb up and down it."
 * Unrealizable hops are absorbed by {@code RegionEdgeBlacklist} + the capsSig-keyed invalidations,
 * exactly as for openables.
 *
 * <p>Real-BlockState scenes classified through the production
 * {@link NavSectionBuilder#classifyInto} path (the {@link OpenableFloodConnectivityTest} idiom, no
 * hand-built masks), but with the SHAFT geometry the ruling came from: one 16³ {@link NavSection}
 * split by a full stone SLAB spanning y=6..9 into a lower and an upper air pocket, bored by a 1x1
 * column at (8, 6..9, 8) holding the candidate state. The two pockets read as ONE
 * {@link FragmentLeafComputer} component when the column is (a) ladder — the newly-admitted
 * SHAPE_OTHER plate — or (b) scaffolding — the newly-admitted SHAPE_FULL row; (c) vine already
 * connected BEFORE this ruling (SHAPE_EMPTY → geometric {@link NavBlock#isPassable}, pinned here so
 * the a/b cases are honestly the new disjunct's work). The sealed / plain-fence controls live in
 * {@link OpenableFloodConnectivityTest} and are not duplicated.
 */
class ClimbableFloodConnectivityTest {

    /** Slab band [SLAB_Y0..SLAB_Y1] (inclusive) — splits the section into lower/upper air pockets. */
    private static final int SLAB_Y0 = 6, SLAB_Y1 = 9;
    /** The 1x1 bore column. */
    private static final int COL_X = 8, COL_Z = 8;

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
    }

    /**
     * A 16³ section: air everywhere except a full stone slab over y={@value #SLAB_Y0}..{@value #SLAB_Y1},
     * whose ({@value #COL_X}, y, {@value #COL_Z}) column holds {@code columnState} — the vertical analog
     * of the openable test's wall-gap scene, matching the live bare-shaft geometry. Classified through
     * the production pass-1 kernel.
     */
    private static NavSection sectionWithShaft(BlockState columnState) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        PalettedContainer<BlockState> states = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int y = SLAB_Y0; y <= SLAB_Y1; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    states.set(x, y, z, stone);
                }
            }
        }
        for (int y = SLAB_Y0; y <= SLAB_Y1; y++) {
            states.set(COL_X, y, COL_Z, columnState);
        }
        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(states, false, section.getTraversalGrid());
        return section;
    }

    private static void assertShaftConnects(BlockState columnState, String who) {
        NavSection section = sectionWithShaft(columnState);
        RegionFragments out = new RegionFragments();
        FragmentLeafComputer.computeLeaf(section, out);
        assertFalse(out.isCollapsed(), who + ": two-pocket scenes stay far under the fragment cap");
        int below = FragmentLeafComputer.fragmentContaining(section, COL_X, 2, COL_Z);
        int above = FragmentLeafComputer.fragmentContaining(section, COL_X, 13, COL_Z);
        assertTrue(below >= 0 && above >= 0, who + ": both pockets resolve to a kept fragment");
        assertEquals(below, above, who + ": the climbable column joins the two pockets into ONE component");
        assertEquals(1, out.fragmentCount(), who + ": one fragment total");
    }

    // ---- (a)/(b): the two rows the CLIMB disjunct newly admits --------------------------------------

    @Test
    void ladderColumn_connects() {
        BlockState ladder = Blocks.LADDER.defaultBlockState();
        long d = NavBlock.descriptorFor(ladder);
        // Honesty pins: the connection below must be the NEW disjunct's work — a ladder still
        // collides geometrically (the SHAPE_OTHER wall plate) and carries the CLIMB bit.
        assertTrue(NavBlock.isClimbable(d), "ladder carries the CLIMB bit");
        assertFalse(NavBlock.isPassable(d), "ladder still collides (SHAPE_OTHER) — isPassable alone would seal");
        assertTrue(NavBlock.floodPassable(d), "the flood admits it (climbable-as-air)");
        assertShaftConnects(ladder, "1x1 ladder column");
    }

    @Test
    void scaffoldingColumn_connects() {
        BlockState scaffold = Blocks.SCAFFOLDING.defaultBlockState();
        long d = NavBlock.descriptorFor(scaffold);
        assertTrue(NavBlock.isClimbable(d), "scaffolding carries the CLIMB bit");
        assertEquals(NavBlock.SHAPE_FULL, NavBlock.shape(d),
                "scaffolding classifies SHAPE_FULL — isPassable alone would seal");
        assertTrue(NavBlock.floodPassable(d), "the flood admits it (climbable-as-air)");
        assertShaftConnects(scaffold, "1x1 scaffolding column");
    }

    // ---- (c): vines connected BEFORE the disjunct — pin the pre-existing path -----------------------

    /** Vines must pass via geometric {@link NavBlock#isPassable} even without the CLIMB disjunct —
     *  the pin that keeps (a)/(b) honest about what the new disjunct added. */
    @Test
    void vineColumn_alreadyConnects_viaGeometricPassability() {
        BlockState vine = Blocks.VINE.defaultBlockState();
        long d = NavBlock.descriptorFor(vine);
        assertTrue(NavBlock.isClimbable(d), "vine carries the CLIMB bit");
        assertEquals(NavBlock.SHAPE_EMPTY, NavBlock.shape(d),
                "vine is SHAPE_EMPTY — already passable with no help from the CLIMB disjunct");
        assertTrue(NavBlock.isPassable(d), "vine passes the geometric predicate on its own");
        assertShaftConnects(vine, "1x1 vine column");
    }
}
