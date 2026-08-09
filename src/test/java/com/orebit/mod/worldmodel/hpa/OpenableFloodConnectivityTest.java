package com.orebit.mod.worldmodel.hpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * Region-tier <b>openable-as-air</b> flood connectivity (DESIGN-trapdoors.md §8b, owner-ratified
 * 2026-08-09): {@link NavBlock#floodPassable} treats EVERY openable cell — doors, trapdoors, fence
 * gates, <b>iron included</b> — as passable for leaf flood membership, because an openable blocks at
 * most 1 of its 6 faces ("5/6 faces are passable — there's a chance you can path through it just
 * fine — and if you can't, we'll figure that out and invalidate"; unrealizable hops are absorbed by
 * {@code RegionEdgeBlacklist} + the capsSig-keyed persisted invalidations).
 *
 * <p>Real-BlockState scenes (the {@code HpaMilestoneTest}/trapdoor-suite Bootstrap idiom, classified
 * through the production {@link NavSectionBuilder#classifyInto} path — no hand-built masks): one 16³
 * {@link NavSection} split by a full stone wall plane at {@code x=8} into two air pockets, with a
 * single candidate cell in the wall. The two pockets read as ONE {@link FragmentLeafComputer}
 * component when the candidate is any openable — (a) CLOSED oak door, (b) CLOSED-bottom oak
 * trapdoor, (c) closed oak fence gate, (d) IRON door — and stay TWO components with (e) a plain oak
 * fence (not openable — correctly a wall) or (f) solid stone (the sealed control). Also pins that
 * ONLY the flood mask changed: a closed-bottom trapdoor cell keeps its {@code standable[]} verdict
 * (still a FLOOR) and its geometric {@link NavBlock#isPassable} verdict (still collides).
 */
class OpenableFloodConnectivityTest {

    /** Wall plane X (splits the section into x&lt;8 and x&gt;8 pockets). */
    private static final int WALL_X = 8;
    /** The single candidate cell in the wall. */
    private static final int GAP_Y = 8, GAP_Z = 8;

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
     * A 16³ section: air everywhere except a full stone wall plane at {@code x=}{@value #WALL_X},
     * whose cell ({@value #WALL_X}, {@value #GAP_Y}, {@value #GAP_Z}) holds {@code gapState}
     * (stone = the sealed control). Classified through the production pass-1 kernel.
     */
    private static NavSection sectionWithWallGap(BlockState gapState) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        PalettedContainer<BlockState> states = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                states.set(WALL_X, y, z, stone);
            }
        }
        states.set(WALL_X, GAP_Y, GAP_Z, gapState);
        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(states, false, section.getTraversalGrid());
        return section;
    }

    /** Flood the leaf and return {@code {leftFragment, rightFragment, fragmentCount}} for the two pockets. */
    private static int[] floodSides(NavSection section) {
        RegionFragments out = new RegionFragments();
        FragmentLeafComputer.computeLeaf(section, out);
        assertFalse(out.isCollapsed(), "two-pocket scenes stay far under the fragment cap");
        int left = FragmentLeafComputer.fragmentContaining(section, 2, GAP_Y, GAP_Z);
        int right = FragmentLeafComputer.fragmentContaining(section, 13, GAP_Y, GAP_Z);
        return new int[] { left, right, out.fragmentCount() };
    }

    private static void assertConnected(BlockState gapState, String who) {
        int[] r = floodSides(sectionWithWallGap(gapState));
        assertTrue(r[0] >= 0 && r[1] >= 0, who + ": both pockets resolve to a kept fragment");
        assertEquals(r[0], r[1], who + ": the openable joins the two pockets into ONE component");
        assertEquals(1, r[2], who + ": one fragment total");
    }

    private static void assertSealed(BlockState gapState, String who) {
        int[] r = floodSides(sectionWithWallGap(gapState));
        assertTrue(r[0] >= 0 && r[1] >= 0, who + ": both pockets resolve to a kept fragment");
        assertNotEquals(r[0], r[1], who + ": the wall keeps the two pockets apart");
        assertEquals(2, r[2], who + ": two fragments total");
    }

    // ---- (a)–(d): every openable kind — incl. IRON — is flood-passable ------------------------------

    @Test
    void closedOakDoor_connects() {
        assertConnected(Blocks.OAK_DOOR.defaultBlockState(), "closed oak door");
    }

    @Test
    void closedBottomTrapdoor_connects() {
        assertConnected(Blocks.OAK_TRAPDOOR.defaultBlockState(), "closed-bottom oak trapdoor");
    }

    @Test
    void closedFenceGate_connects() {
        assertConnected(Blocks.OAK_FENCE_GATE.defaultBlockState(), "closed oak fence gate");
    }

    /** The owner's explicit ruling: iron (un-toggleable by hand) is STILL transparent to the flood —
     *  realizability is the invalidation memory's job, not the connectivity model's. */
    @Test
    void ironDoor_connects() {
        assertConnected(Blocks.IRON_DOOR.defaultBlockState(), "iron door");
    }

    // ---- (e)–(f): non-openables stay walls ----------------------------------------------------------

    @Test
    void plainFence_staysSealed() {
        assertSealed(Blocks.OAK_FENCE.defaultBlockState(), "plain oak fence (not openable)");
    }

    @Test
    void solidStone_staysSealed() {
        assertSealed(Blocks.STONE.defaultBlockState(), "solid stone (sealed control)");
    }

    // ---- goal-in-doorway resolution + the standable[] non-change pin --------------------------------

    /** A cell INSIDE the doorway itself is a member of the joined fragment — the
     *  {@code RegionGrid.goalDigSeeds} exposed-goal alignment (§8b): a goal standing in a
     *  doorway/hatch cell resolves into the fragment it now belongs to. */
    @Test
    void doorwayCellItself_isAMemberOfTheJoinedFragment() {
        NavSection section = sectionWithWallGap(Blocks.OAK_DOOR.defaultBlockState());
        int side = FragmentLeafComputer.fragmentContaining(section, 2, GAP_Y, GAP_Z);
        int gap = FragmentLeafComputer.fragmentContaining(section, WALL_X, GAP_Y, GAP_Z);
        assertTrue(gap >= 0, "the doorway cell resolves to a kept fragment");
        assertEquals(side, gap, "…and it is the SAME fragment as the pocket beside it");
    }

    /** Pins that the predicate swap touched ONLY flood membership: the closed-bottom trapdoor cell's
     *  descriptor still reads standable (the plate is a FLOOR — what fills {@code standable[]}) and
     *  still fails geometric {@link NavBlock#isPassable} (the plate collides), while
     *  {@link NavBlock#floodPassable} admits it. */
    @Test
    void closedBottomTrapdoor_keepsStandableVerdict() {
        NavSection section = sectionWithWallGap(Blocks.OAK_TRAPDOOR.defaultBlockState());
        long d = NavBlock.descriptor((short) section.getNavtype(WALL_X, GAP_Y, GAP_Z));
        assertTrue(NavBlock.isTrapdoor(d), "gap cell classified as a trapdoor");
        assertTrue(NavBlock.isStandable(d), "closed-bottom plate remains a FLOOR (standable[] source unchanged)");
        assertFalse(NavBlock.isPassable(d), "geometric passability unchanged — the plate still collides");
        assertTrue(NavBlock.floodPassable(d), "but the region flood admits it (openable-as-air)");
    }
}
