package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * <b>Door classification vs. vanilla's ACTUAL collision body</b> — the loop {@link DoorClassificationTest}
 * leaves open.
 *
 * <p>That test pins {@link NavBlock#doorBlockedEdge} against a table someone wrote down ("the owner's
 * bytecode-verified ground truth"). A transcription slip in the table is therefore invisible to it: the
 * model and the test would agree with each other and disagree with Minecraft. This one asks the game
 * instead — {@code state.getCollisionShape(null, null)}, the same query {@code NavBlock} itself classifies
 * with — and derives the truth from the geometry.
 *
 * <p><b>What is asserted</b> is deliberately the unambiguous half: which horizontal AXIS a 0.6-wide body
 * can travel through the cell on. A door's collision is a thin slab hugging one side, so it blocks travel
 * along the axis PERPENDICULAR to that slab and leaves the other axis clear — and our model, which names a
 * single blocked edge, implies exactly one blocked axis. Those two must agree or the planner is reading a
 * door wrong. The FACE-level detail (which of the two faces on that axis we name, versus which side the
 * slab actually hugs) is printed rather than asserted, because "blocked edge" is a naming convention and
 * the direction-aware callers are what give it meaning — see the report this test prints.
 *
 * <p>Every door in the registry is enumerated rather than a hand-listed set, so a new wood type or the next
 * copper oxidation tier is covered the day it ships. The copper family is the reason this exists: the
 * flagship autotest holds forever at a closed {@code waxed_oxidized_copper_door}
 * ({@code Traverse ... needs AIR at (216,-26,6)}, folding neither a toggle nor a break).
 */
class DoorCollisionTruthTest {

    private static final int N = 0, E = 1, S = 2, W = 3;
    private static final String[] NAME = {"N", "E", "S", "W"};

    /** Vanilla's body half-width; the corridor a travelling bot actually sweeps. */
    private static final double BODY = 0.6;
    private static final double LO = (1.0 - BODY) / 2.0, HI = 1.0 - LO;

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** Every {@link DoorBlock} the registry knows — wood, iron, and the copper family incl. waxed tiers. */
    private static List<Block> doors() {
        List<Block> out = new ArrayList<>();
        for (Block b : BuiltInRegistries.BLOCK) {
            if (b instanceof DoorBlock) out.add(b);
        }
        return out;
    }

    private static BlockState state(Block b, Direction facing, DoorHingeSide hinge, boolean open,
            DoubleBlockHalf half) {
        return b.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(BlockStateProperties.DOOR_HINGE, hinge)
                .setValue(BlockStateProperties.OPEN, open)
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, half);
    }

    /**
     * Which horizontal axis vanilla's collision body BLOCKS for a travelling body: sweep the 0.6-wide
     * corridor along each axis and test it against the shape's bounds. Returns 0 for the Z (north/south)
     * axis, 1 for the X (east/west) axis, −1 for "neither" (an open door that collides with nothing in the
     * corridor), −2 for "both" (a shape that is not a thin slab at all).
     */
    private static int blockedAxis(VoxelShape shape) {
        if (shape.isEmpty()) return -1;
        AABB s = shape.bounds();
        // Travelling along Z sweeps the full Z extent, 0.6 wide in X. Blocked iff the shape reaches into it.
        boolean zBlocked = s.maxX > LO && s.minX < HI;
        // Travelling along X sweeps the full X extent, 0.6 wide in Z.
        boolean xBlocked = s.maxZ > LO && s.minZ < HI;
        if (zBlocked && xBlocked) return -2;
        if (zBlocked) return 0;
        if (xBlocked) return 1;
        return -1;
    }

    /** Which cardinal face the slab HUGS, read off the bounds; −1 if it is not a one-sided thin slab. */
    private static int huggedFace(VoxelShape shape) {
        if (shape.isEmpty()) return -1;
        AABB s = shape.bounds();
        if (s.maxZ - s.minZ < 0.5) return s.minZ < 0.01 ? N : (s.maxZ > 0.99 ? S : -1);
        if (s.maxX - s.minX < 0.5) return s.minX < 0.01 ? W : (s.maxX > 0.99 ? E : -1);
        return -1;
    }

    private static int axisOfEdge(int edge) { return (edge == N || edge == S) ? 0 : 1; }

    @Test
    void ourBlockedEdgeAgreesWithVanillaCollisionOnEveryDoorAndState() {
        List<String> mismatches = new ArrayList<>();
        List<String> table = new ArrayList<>();
        int checked = 0;

        for (Block block : doors()) {
            String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                for (DoorHingeSide hinge : DoorHingeSide.values()) {
                    for (boolean open : new boolean[] {false, true}) {
                        BlockState st = state(block, facing, hinge, open, DoubleBlockHalf.LOWER);
                        VoxelShape shape = st.getCollisionShape(null, null);
                        int vanillaAxis = blockedAxis(shape);
                        int hugged = huggedFace(shape);

                        long d = NavBlock.descriptorFor(st);
                        int ourEdge = NavBlock.doorBlockedEdge(d);
                        int ourAxis = axisOfEdge(ourEdge);

                        checked++;
                        String row = String.format(
                                "%-34s facing=%-5s hinge=%-5s open=%-5s | vanilla slab=%s axis=%s | ours edge=%s axis=%s%s",
                                id, facing, hinge, open,
                                hugged < 0 ? "?" : NAME[hugged],
                                vanillaAxis == 0 ? "NS" : vanillaAxis == 1 ? "EW"
                                        : vanillaAxis == -1 ? "none" : "BOTH",
                                NAME[ourEdge], ourAxis == 0 ? "NS" : "EW",
                                (hugged >= 0 && hugged != ourEdge) ? "   <-- names the OPPOSITE face" : "");
                        table.add(row);

                        // The assertion: a solid slab must block the axis our model says it blocks. An OPEN
                        // door that collides with nothing in the corridor is not a disagreement — our model
                        // still names an edge, but nothing is there to block, which the callers handle via
                        // the OPEN bit. Only a REAL collision that lands on the other axis is a defect.
                        if (vanillaAxis >= 0 && vanillaAxis != ourAxis) {
                            mismatches.add(row);
                        }
                    }
                }
            }
        }

        System.out.println("=== door collision vs NavBlock.doorBlockedEdge (" + checked + " states, "
                + doors().size() + " door blocks) ===");
        table.forEach(System.out::println);

        assertTrue(mismatches.isEmpty(),
                "vanilla's collision body blocks a different AXIS than our model claims:\n"
                        + String.join("\n", mismatches));
    }

    /**
     * Toggling must MOVE the slab, not merely relabel it: vanilla's own open and closed shapes have to
     * differ, and our model has to name a different edge for each. A door type that violated this (the
     * hypothetical "copper opens inverted") would show up here as an unchanged shape or an unchanged edge.
     */
    @Test
    void toggllingMovesTheSlabForEveryDoorType() {
        List<String> odd = new ArrayList<>();
        for (Block block : doors()) {
            String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                for (DoorHingeSide hinge : DoorHingeSide.values()) {
                    AABB closed = state(block, facing, hinge, false, DoubleBlockHalf.LOWER)
                            .getCollisionShape(null, null).bounds();
                    VoxelShape openShape = state(block, facing, hinge, true, DoubleBlockHalf.LOWER)
                            .getCollisionShape(null, null);
                    int closedEdge = NavBlock.doorBlockedEdge(
                            NavBlock.descriptorFor(state(block, facing, hinge, false, DoubleBlockHalf.LOWER)));
                    int openEdge = NavBlock.doorBlockedEdge(
                            NavBlock.descriptorFor(state(block, facing, hinge, true, DoubleBlockHalf.LOWER)));
                    if (!openShape.isEmpty() && closed.equals(openShape.bounds())) {
                        odd.add(id + " " + facing + "/" + hinge + ": open and closed collision are IDENTICAL");
                    }
                    if (closedEdge == openEdge) {
                        odd.add(id + " " + facing + "/" + hinge + ": our model names the same edge "
                                + NAME[closedEdge] + " open and closed");
                    }
                }
            }
        }
        assertTrue(odd.isEmpty(), "toggling did not move the door:\n" + String.join("\n", odd));
    }

    /**
     * Every door type must agree with every other on geometry — the "copper is inverted" hypothesis. Wood
     * is the reference because it is the oldest and most-exercised family.
     */
    @Test
    void everyDoorTypeHasTheSameGeometryAsOak() {
        Block oak = net.minecraft.world.level.block.Blocks.OAK_DOOR;
        List<String> deviants = new ArrayList<>();
        for (Block block : doors()) {
            if (block == oak) continue;
            String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                for (DoorHingeSide hinge : DoorHingeSide.values()) {
                    for (boolean open : new boolean[] {false, true}) {
                        VoxelShape a = state(oak, facing, hinge, open, DoubleBlockHalf.LOWER)
                                .getCollisionShape(null, null);
                        VoxelShape b = state(block, facing, hinge, open, DoubleBlockHalf.LOWER)
                                .getCollisionShape(null, null);
                        if (a.isEmpty() != b.isEmpty()
                                || (!a.isEmpty() && !a.bounds().equals(b.bounds()))) {
                            deviants.add(id + " " + facing + "/" + hinge + "/open=" + open
                                    + " oak=" + (a.isEmpty() ? "empty" : a.bounds())
                                    + " this=" + (b.isEmpty() ? "empty" : b.bounds()));
                        }
                    }
                }
            }
        }
        assertTrue(deviants.isEmpty(),
                "a door type deviates from oak's collision geometry:\n" + String.join("\n", deviants));
    }
}
