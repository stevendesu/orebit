package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.platform.BlockLookup;
import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;

/**
 * Classification proof for LADDER FACING (DESIGN-trapdoor-ladder-climb.md §2). A {@link LadderBlock}
 * joins the shared horizontal-facing family (bits 8–9, stair XOR door XOR trapdoor XOR ladder) with
 * ZERO new bits, fanning the single ladder navtype into 4 per fluid flavor (waterlogged already splits
 * via the fluid field). The discriminator {@link NavBlock#isLadder} = {@code CLIMB ∧ SHAPE_OTHER} is
 * derived from existing bits, so its soundness rests on a TABLE INVARIANT rather than a kind field:
 * no non-ladder navtype may ever satisfy it, or the shared facing bits would be misread — the
 * load-bearing pin here is the sweep over the ENTIRE interned navtype table. Vines/scaffolding
 * (the rest of the closed climbable list) must classify UNCHANGED: no facing packed, never the
 * discriminator, because the vanilla trapdoor-over-ladder rule is ladder-IDENTITY-only (§1 —
 * {@code LivingEntity.trapdoorUsableAsLadder}, bytecode-pinned 1.17.1→26.2). The §3 neighbor
 * predicate {@link MovementContext#trapdoorClimbable} is pinned at descriptor level: OPEN trapdoor +
 * ladder below + EQUAL facing — HALF not consulted, iron qualifies ({@code instanceof} + state
 * openness), waterlogged mouths stay conservative walls via the leading {@link NavBlock#openTrapdoor}
 * bit test.
 */
class LadderClassificationTest {

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
        BlockPathfinder.LOG_TIMING = false;
        Debug.ENABLED = false;
    }

    // N=0 E=1 S=2 W=3 (the NavBlock cardinal convention).
    private static final int N = 0, E = 1, S = 2, W = 3;
    private static final Direction[] CARDINALS = { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST };
    private static final long FACING_FIELD = 0x3L << 8;

    private static int ord(Direction d) {
        switch (d) {
            case EAST:  return E;
            case SOUTH: return S;
            case WEST:  return W;
            default:    return N;
        }
    }

    private static BlockState ladder(Direction facing, boolean waterlogged) {
        return Blocks.LADDER.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(BlockStateProperties.WATERLOGGED, waterlogged);
    }

    private static BlockState td(Block block, Direction facing, boolean open, Half half, boolean waterlogged) {
        return block.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(BlockStateProperties.OPEN, open)
                .setValue(BlockStateProperties.HALF, half)
                .setValue(BlockStateProperties.WATERLOGGED, waterlogged);
    }

    private static List<Block> allLadders() {
        List<Block> ladders = new ArrayList<>();
        BlockLookup.forEachBlock(b -> { if (b instanceof LadderBlock) ladders.add(b); });
        return ladders;
    }

    // ---- (1) descriptor pins: CLIMB ∧ OTHER, facing in bits 8–9, 4-navtype fan per fluid flavor ------

    @Test
    void ladderPacksFacingIntoSharedBitsAndFansFourNavtypesPerFluidFlavor() {
        Set<Short> all = new HashSet<>();
        for (boolean waterlogged : new boolean[] { false, true }) {
            Set<Short> navtypes = new HashSet<>();
            for (Direction facing : CARDINALS) {
                BlockState s = ladder(facing, waterlogged);
                long d = NavBlock.descriptorFor(s);
                String who = "ladder " + facing + (waterlogged ? " (waterlogged)" : "");

                assertTrue(NavBlock.isClimbable(d), who + " carries the CLIMB bit");
                assertEquals(NavBlock.SHAPE_OTHER, NavBlock.shape(d), who + " is OTHER (the 3/16 wall plate)");
                assertTrue(NavBlock.isLadder(d), who + " satisfies the CLIMB ∧ OTHER discriminator");
                assertEquals(16, NavBlock.topY(d), who + " plate is full-height");
                assertTrue(NavBlock.isNarrowTop(d), who + " 3/16 thin axis is under the 0.6 body width");
                assertFalse(NavBlock.isStandable(d), who + " narrow tops are not floors");
                assertFalse(NavBlock.isPassable(d), who + " is not passable air (shape != EMPTY)");
                assertFalse(NavBlock.isStair(d), who + " is not a stair, despite sharing the facing bits");
                assertFalse(NavBlock.isDoor(d), who + " is not a door (openable kind stays NONE)");
                assertFalse(NavBlock.isTrapdoor(d), who + " is not a trapdoor (openable kind stays NONE)");
                assertFalse(NavBlock.isGate(d), who + " is not a gate (openable kind stays NONE)");

                assertEquals(ord(facing), NavBlock.ladderFacing(d), who + " packs its facing ordinal");
                assertEquals((long) ord(facing) << 8, d & FACING_FIELD,
                        who + " the facing read IS bits 8–9 (the shared stair/door/trapdoor field)");

                assertEquals(waterlogged, NavBlock.isWaterloggedNow(d), who + " fluid field tracks waterlogged");
                assertTrue(NavBlock.isWaterloggable(d), who + " ladders are statically waterloggable");

                navtypes.add(NavBlock.navtypeFor(s));
            }
            assertEquals(4, navtypes.size(),
                    "facing fans the ladder into 4 navtypes (waterlogged=" + waterlogged + "); got " + navtypes);
            all.addAll(navtypes);
        }
        assertEquals(8, all.size(), "the fluid field already splits the flavors — 4 × 2 distinct navtypes");
        assertTrue(NavBlock.navtypeCount() < 1024,
                "navtype count must stay under the 10-bit grid cap; was " + NavBlock.navtypeCount());
    }

    // ---- (2) bit-identity over EVERY registered ladder state: facing is the ONLY moving bit ----------

    @Test
    void facingIsTheOnlyDescriptorDifferenceAcrossEveryRegisteredLadderState() {
        List<Block> ladders = allLadders();
        assertTrue(ladders.size() >= 1, "at least Blocks.LADDER; found " + ladders.size());

        int checked = 0;
        for (Block b : ladders) {
            for (BlockState s : b.getStateDefinition().getPossibleStates()) {
                long d = NavBlock.descriptorFor(s);
                int f = ord(s.getValue(BlockStateProperties.HORIZONTAL_FACING));
                assertTrue(NavBlock.isLadder(d), () -> "registered ladder state " + s + " must read isLadder");
                assertEquals(f, NavBlock.ladderFacing(d),
                        () -> "packed facing must reproduce the state's FACING property (" + s + ")");
                long north = NavBlock.descriptorFor(
                        s.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
                assertEquals((north & ~FACING_FIELD) | ((long) f << 8), d,
                        () -> "bits 8–9 must be the ONLY difference across the facing sweep of " + s
                                + " — any other drift means facing leaked into geometry/fluid packing");
                checked++;
            }
        }
        System.out.println("[LadderClassificationTest] bit-identity sweep: " + ladders.size()
                + " ladder blocks, " + checked + " states");
        assertTrue(checked >= 8, "the facing × waterlogged sweep must cover the 8 vanilla states; saw " + checked);
    }

    // ---- (3) LOAD-BEARING: isLadder uniqueness over the ENTIRE interned navtype table ----------------

    @Test
    void isLadderDiscriminatorIsUniqueOverTheEntireInternedTable() {
        List<Block> ladders = allLadders();
        Set<Long> ladderDescriptors = new HashSet<>();
        for (Block b : ladders)
            for (BlockState s : b.getStateDefinition().getPossibleStates())
                ladderDescriptors.add(NavBlock.descriptorFor(s));

        // A PROTECTED-split ladder row is still a ladder (protection changes breakability, not climb
        // geometry — the facing bits it carries are valid), and applyProtected never removes rows, so a
        // shared-JVM table may carry such rows from another test's protection pass (the default
        // mining.protectedBlocks list includes minecraft:ladder). Intern them here deterministically so
        // the grading below covers them as REAL ladders instead of flaking on suite order.
        short baseNavtype = NavBlock.navtypeFor(ladder(Direction.NORTH, false));
        NavBlock.applyProtected(s -> s.getBlock() instanceof LadderBlock);
        try {
            for (Block b : ladders)
                for (BlockState s : b.getStateDefinition().getPossibleStates())
                    ladderDescriptors.add(NavBlock.descriptorFor(s));
        } finally {
            NavBlock.applyProtected(s -> false);
        }
        assertEquals(baseNavtype, NavBlock.navtypeFor(ladder(Direction.NORTH, false)),
                "un-protect must restore the base navtype mapping");

        int total = NavBlock.navtypeCount();
        int ladderRows = 0;
        for (int t = 0; t < total; t++) {
            final short tt = (short) t;
            final long d = NavBlock.descriptor(tt);
            boolean real = ladderDescriptors.contains(d);
            assertEquals(real, NavBlock.isLadder(d),
                    () -> "navtype " + tt + " (descriptor 0x" + Long.toHexString(d) + "): isLadder must hold"
                            + " for exactly the ladder-state rows — a non-ladder satisfying CLIMB ∧ OTHER"
                            + " would read bogus facing bits out of the shared field");
            if (real) ladderRows++;
        }
        System.out.println("[LadderClassificationTest] uniqueness sweep: " + total
                + " interned navtypes, " + ladderRows + " ladder rows");
        assertTrue(ladderRows >= 8, "the sweep must not be vacuous — expected the 4-facing × 2-fluid rows"
                + " at minimum; saw " + ladderRows);
    }

    // ---- (4) vines/scaffolding UNCHANGED: no facing packed, never the discriminator ------------------

    @Test
    void nonLadderClimbablesPackNoFacingAndNeverSatisfyTheDiscriminator() {
        // The two shape anchors the discriminator leans on (§2): vine = EMPTY, scaffolding = FULL.
        long vine = NavBlock.descriptorFor(Blocks.VINE.defaultBlockState());
        assertTrue(NavBlock.isClimbable(vine), "vine keeps the CLIMB bit");
        assertEquals(NavBlock.SHAPE_EMPTY, NavBlock.shape(vine), "vine stays passable EMPTY");
        assertFalse(NavBlock.isLadder(vine), "EMPTY shape keeps vines off the discriminator");

        long scaffold = NavBlock.descriptorFor(Blocks.SCAFFOLDING.defaultBlockState());
        assertTrue(NavBlock.isClimbable(scaffold), "scaffolding keeps the CLIMB bit");
        assertEquals(NavBlock.SHAPE_FULL, NavBlock.shape(scaffold), "scaffolding stays FULL");
        assertFalse(NavBlock.isLadder(scaffold), "FULL shape keeps scaffolding off the discriminator");

        // Whole-registry sweep: the vanilla rule is ladder-identity-only (§1), so every OTHER climbable
        // state must leave the shared facing field untouched — a set bit here would be dead-at-best,
        // misread-at-worst data.
        Set<Block> seen = new HashSet<>();
        BlockLookup.forEachBlock(b -> {
            if (b instanceof LadderBlock) return;
            for (BlockState s : b.getStateDefinition().getPossibleStates()) {
                long d = NavBlock.descriptorFor(s);
                if (!NavBlock.isClimbable(d)) continue;
                assertEquals(0L, d & FACING_FIELD,
                        () -> "non-ladder climbable " + s + " must not pack facing (bits 8–9)");
                assertFalse(NavBlock.isLadder(d),
                        () -> "non-ladder climbable " + s + " must not satisfy the discriminator");
                seen.add(b);
            }
        });
        assertTrue(seen.contains(Blocks.VINE) && seen.contains(Blocks.SCAFFOLDING),
                "sweep must have graded the vine/scaffolding families; saw " + seen.size() + " blocks");
    }

    // ---- (5) the §3 neighbor predicate, at descriptor level ------------------------------------------

    @Test
    void trapdoorClimbableTruthTable() {
        // trapdoorClimbable reads only the two descriptors — an empty synthetic grid suffices.
        MovementContext ctx = new MovementContext(new NavGridView(0, new ConcurrentHashMap<>()), BotCaps.DEFAULT);

        long ladderN = NavBlock.descriptorFor(ladder(Direction.NORTH, false));
        long ladderE = NavBlock.descriptorFor(ladder(Direction.EAST, false));
        long openOakN = NavBlock.descriptorFor(td(Blocks.OAK_TRAPDOOR, Direction.NORTH, true, Half.BOTTOM, false));
        long closedOakN = NavBlock.descriptorFor(td(Blocks.OAK_TRAPDOOR, Direction.NORTH, false, Half.BOTTOM, false));
        long openIronN = NavBlock.descriptorFor(td(Blocks.IRON_TRAPDOOR, Direction.NORTH, true, Half.BOTTOM, false));
        long openOakTopN = NavBlock.descriptorFor(td(Blocks.OAK_TRAPDOOR, Direction.NORTH, true, Half.TOP, false));
        long openOakNWet = NavBlock.descriptorFor(td(Blocks.OAK_TRAPDOOR, Direction.NORTH, true, Half.BOTTOM, true));
        long vine = NavBlock.descriptorFor(Blocks.VINE.defaultBlockState());
        long scaffold = NavBlock.descriptorFor(Blocks.SCAFFOLDING.defaultBlockState());

        assertTrue(ctx.trapdoorClimbable(openOakN, ladderN),
                "OPEN trapdoor + ladder below + EQUAL facing climbs (§1)");
        assertTrue(ctx.trapdoorClimbable(openIronN, ladderN),
                "IRON open trapdoor climbs — the vanilla rule is instanceof + state openness (§1)");
        assertTrue(ctx.trapdoorClimbable(openOakTopN, ladderN),
                "HALF is not consulted by vanilla (§1) — a TOP-half open mouth climbs");
        assertFalse(ctx.trapdoorClimbable(closedOakN, ladderN), "CLOSED never climbs");
        assertFalse(ctx.trapdoorClimbable(openOakN, ladderE), "facing mismatch refuses");
        assertFalse(ctx.trapdoorClimbable(openOakN, vine),
                "vine below refuses — the vanilla rule is Blocks.LADDER identity, not the climbable tag");
        assertFalse(ctx.trapdoorClimbable(openOakN, scaffold),
                "scaffolding below refuses — identity, not the climbable tag");
        assertFalse(ctx.trapdoorClimbable(openOakNWet, ladderN),
                "waterlogged open mouth refuses — the leading openTrapdoor test keeps §10's conservative walls");
        assertFalse(ctx.trapdoorClimbable(ladderN, ladderN),
                "a plain ladder feet cell is not a trapdoor mouth (the kind gate leads the conjunction)");
    }
}
