package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.platform.BlockLookup;
import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Classification proof for FENCE GATES (DESIGN-fence-gates.md §2). A {@link FenceGateBlock} packs its
 * OPEN into the SHARED open bit (43) and hand-toggleable into the shared bit 50 — UNCONDITIONALLY (no
 * iron gate exists at any version, §1) — with ZERO new bits; the {@code openable} kind (14–15, GATE)
 * disambiguates. Geometry stays generic: closed → topY 24 {@code SHAPE_OTHER} + NARROW_TOP (a centered
 * 4/16 plate — a whole-cell blocker, never standable, never passable); open → {@code Shapes.empty()}
 * {@code SHAPE_EMPTY} passable air. FACING is deliberately NOT packed (behaviorally void — open collision
 * is empty regardless of facing, closed blocks every crossing regardless of axis) and POWERED/IN_WALL are
 * deliberately unread (IN_WALL is collision-invariant), so per open-state EVERY facing × powered × in_wall
 * combination of EVERY wood type interns to ONE navtype. The CRITICAL contract — pinned over EVERY
 * registered fence-gate blockstate, both directions — is that {@link NavBlock#withGateOpen} (and the
 * {@link NavBlock#withOpenableOpen} kind dispatch that routes to it) re-derives geometry so exactly that
 * it reproduces the interned descriptor of the real toggled state bit-for-bit (§2).
 */
class GateClassificationTest {

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

    private static BlockState gate(Block block, Direction facing, boolean open, boolean powered, boolean inWall) {
        return block.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(BlockStateProperties.OPEN, open)
                .setValue(BlockStateProperties.POWERED, powered)
                .setValue(BlockStateProperties.IN_WALL, inWall);
    }

    private static List<Block> allGates() {
        List<Block> gates = new ArrayList<>();
        BlockLookup.forEachBlock(b -> { if (b instanceof FenceGateBlock) gates.add(b); });
        return gates;
    }

    // ---- (1) closed gate — the §2/§3 whole-cell-blocker model ----------------------------------------

    @Test
    void closedGatePacksTheWholeCellBlockerModel() {
        long d = NavBlock.descriptorFor(gate(Blocks.OAK_FENCE_GATE, Direction.NORTH, false, false, false));

        assertTrue(NavBlock.isGate(d), "closed gate classifies as the GATE openable kind");
        assertFalse(NavBlock.isDoor(d), "a gate is not a door (openable kind discriminates)");
        assertFalse(NavBlock.isTrapdoor(d), "a gate is not a trapdoor (openable kind discriminates)");

        assertEquals(NavBlock.SHAPE_OTHER, NavBlock.shape(d), "closed gate is OTHER (a 4/16 centered plate)");
        assertEquals(24, NavBlock.topY(d), "closed gate collision top at 24/16 (fence height)");
        assertTrue(NavBlock.isNarrowTop(d), "closed gate's 4/16 thin axis is under the 0.6 body width");
        assertTrue(NavBlock.hasCollision(d), "closed gate has a collision face");
        assertFalse(NavBlock.isStandable(d), "topY 24 + narrow — never a floor");
        assertFalse(NavBlock.isPassable(d), "closed gate is a whole-cell blocker");

        assertFalse(NavBlock.gateOpen(d), "CLOSED → shared open bit (43) clear");
        assertEquals(0L, d & (1L << 43), "the open read IS bit 43 (the door/trapdoor shared field)");
        assertTrue(NavBlock.handToggleable(d), "every gate is hand-toggleable (no iron gate exists, §1)");
        assertEquals(1L << 50, d & (1L << 50), "the toggleable read IS bit 50 (the shared field)");

        assertFalse(NavBlock.isWaterloggable(d), "gates carry no WATERLOGGED property at any version (§1)");
    }

    // ---- (2) open gate — passable air, kind + shared bits intact -------------------------------------

    @Test
    void openGatePacksPassableAir() {
        long d = NavBlock.descriptorFor(gate(Blocks.OAK_FENCE_GATE, Direction.NORTH, true, false, false));

        assertTrue(NavBlock.isGate(d), "open gate keeps the GATE openable kind (distinct navtype from air)");
        assertEquals(NavBlock.SHAPE_EMPTY, NavBlock.shape(d), "open gate is Shapes.empty() — zero hitbox");
        assertEquals(0, NavBlock.topY(d), "open gate has no collision top");
        assertTrue(NavBlock.isPassable(d), "open gate is passable air");
        assertFalse(NavBlock.isNarrowTop(d), "empty shapes are never marked narrow");
        assertFalse(NavBlock.hasCollision(d), "open gate has no face to build against");
        assertFalse(NavBlock.isStandable(d), "neither gate state is a floor");

        assertTrue(NavBlock.gateOpen(d), "OPEN → shared open bit (43) set");
        assertEquals(1L << 43, d & (1L << 43), "the open read IS bit 43 (the door/trapdoor shared field)");
        assertTrue(NavBlock.handToggleable(d), "bit 50 stays set on the open state");
        assertFalse(NavBlock.isBreakable(d), "no collision → nothing to mine");
    }

    // ---- (3) breakable-unless-protected semantics are unchanged by the gate branch -------------------

    @Test
    void gateBreakableUnlessProtectedSemanticsUnchanged() {
        BlockState closed = gate(Blocks.OAK_FENCE_GATE, Direction.NORTH, false, false, false);
        BlockState open = closed.setValue(BlockStateProperties.OPEN, true);
        short closedBefore = NavBlock.navtypeFor(closed);
        short openBefore = NavBlock.navtypeFor(open);

        long dc = NavBlock.descriptorFor(closed);
        assertFalse(NavBlock.isProtected(dc), "base table is config-blind — no PROTECTED bit");
        assertTrue(NavBlock.isBreakable(dc), "unprotected closed gate is mineable (solid, wood hardness)");
        assertTrue(NavBlock.isOpenForPlace(NavBlock.descriptorFor(open)),
                "unprotected open gate is placeable-into (placement REPLACES the occupant)");

        NavBlock.applyProtected(s -> s.getBlock() instanceof FenceGateBlock);
        try {
            long pc = NavBlock.descriptorFor(closed);
            assertTrue(NavBlock.isProtected(pc), "protected gate carries the PROTECTED bit");
            assertFalse(NavBlock.isBreakable(pc), "the derived BREAKABLE bit excludes protected cells");
            assertTrue(NavBlock.hasCollision(pc), "protection changes breakability, not geometry");
            assertEquals(24, NavBlock.topY(pc), "geometry untouched by protection");
            assertTrue(NavBlock.isNarrowTop(pc), "geometry untouched by protection");
            assertTrue(NavBlock.handToggleable(pc),
                    "toggling bypasses PROTECTED — bit 50 survives (the only way through under default config)");
            assertFalse(NavBlock.gateOpen(pc), "shared open bit untouched by protection");

            long po = NavBlock.descriptorFor(open);
            assertTrue(NavBlock.isProtected(po), "open state protected too");
            assertFalse(NavBlock.isOpenForPlace(po), "OPEN_PLACE excludes protected cells");
            assertTrue(NavBlock.isPassable(po), "protection does not change passability");

            // withGateOpen must honor the carried PROTECTED bit both directions (no resurrected
            // BREAKABLE/OPEN_PLACE) — the bit-identity contract holds under protection too.
            assertEquals(po, NavBlock.withGateOpen(pc, true), "protected closed → open, bit-for-bit");
            assertEquals(pc, NavBlock.withGateOpen(po, false), "protected open → closed, bit-for-bit");
        } finally {
            NavBlock.applyProtected(s -> false);
        }

        assertEquals(closedBefore, NavBlock.navtypeFor(closed), "un-protect restores the base navtype");
        assertEquals(openBefore, NavBlock.navtypeFor(open), "un-protect restores the base navtype");
    }

    // ---- (4) COLLAPSE (§2): facing × powered × in_wall × wood type → ONE navtype per open-state ------

    @Test
    void facingPoweredInWallAndWoodTypeCollapseToOneNavtypePerOpenState() {
        List<Block> gates = allGates();
        assertTrue(gates.size() >= 8, "at least the 1.17.1 floor set (8 wood); found " + gates.size());

        for (boolean open : new boolean[] { false, true }) {
            Set<Short> navtypes = new HashSet<>();
            int states = 0;
            for (Block b : gates) {
                for (BlockState s : b.getStateDefinition().getPossibleStates()) {
                    if (s.getValue(BlockStateProperties.OPEN) != open) continue;
                    navtypes.add(NavBlock.navtypeFor(s));
                    states++;
                }
            }
            assertEquals(gates.size() * 16, states,
                    "every FACING(4) × POWERED(2) × IN_WALL(2) combination enumerated (open=" + open + ")");
            assertEquals(1, navtypes.size(),
                    "FACING is deliberately unpacked and POWERED/IN_WALL deliberately unread (§2) — all "
                            + states + " states must intern to ONE navtype (open=" + open + "); got " + navtypes);
        }
        assertTrue(NavBlock.navtypeCount() < 1024,
                "navtype count must stay under the 10-bit grid cap; was " + NavBlock.navtypeCount());
    }

    // ---- (5) CRITICAL (§2): withGateOpen reproduces EVERY real gate state bit-for-bit ----------------

    @Test
    void withGateOpenReproducesEveryRealGateStateBitForBit() {
        List<Block> gates = allGates();
        assertTrue(gates.size() >= 8, "at least the 1.17.1 floor set (8 wood); found " + gates.size());

        int checked = 0;
        for (Block b : gates) {
            for (BlockState s : b.getStateDefinition().getPossibleStates()) {
                long d = NavBlock.descriptorFor(s);
                for (boolean open : new boolean[] { false, true }) {
                    long expected = NavBlock.descriptorFor(s.setValue(BlockStateProperties.OPEN, open));
                    assertEquals(expected, NavBlock.withGateOpen(d, open),
                            () -> "withGateOpen(desc(" + s + "), " + open
                                    + ") must equal the interned descriptor of the real toggled state");
                    assertEquals(expected, NavBlock.withOpenableOpen(d, open),
                            () -> "withOpenableOpen must dispatch a GATE cell to withGateOpen, not the door"
                                    + " bit flip (desc(" + s + "), " + open + ")");
                    checked++;
                }
            }
        }
        System.out.println("[GateClassificationTest] bit-identity sweep: " + gates.size()
                + " fence-gate blocks, " + checked + " (state × target) pairs");
        assertTrue(checked > 0, "sweep must cover at least one state");
    }

    // ---- (6) region-tier flood admits BOTH gate states (§5 — connectivity pinned elsewhere) ----------

    @Test
    void floodPassableAdmitsBothGateStates() {
        assertTrue(NavBlock.floodPassable(NavBlock.descriptorFor(
                gate(Blocks.OAK_FENCE_GATE, Direction.NORTH, false, false, false))),
                "closed gate is flood-passable (openable kind admits it)");
        assertTrue(NavBlock.floodPassable(NavBlock.descriptorFor(
                gate(Blocks.OAK_FENCE_GATE, Direction.NORTH, true, false, false))),
                "open gate is flood-passable (plain passable air)");
    }
}
