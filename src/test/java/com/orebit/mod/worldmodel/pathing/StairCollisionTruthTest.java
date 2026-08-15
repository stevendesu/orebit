package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * <b>Stair classification vs. vanilla's ACTUAL collision body</b> — the stair sibling of
 * {@link DoorCollisionTruthTest}, and the same kind of loop-closing.
 *
 * <p>A BOTTOM stair's walking surface is <b>direction-dependent</b>: full height on the half the raised step
 * occupies, half height on the other. {@code MovementContext.directionalTopY} encodes that as "16 when the
 * edge points along the stair's FACING, else 8", reading {@link NavBlock#stairFacing}. Nothing checked that
 * claim against Minecraft — and it is load-bearing for every takeoff, since {@code Parkour}'s launch point is
 * measured from the cell CENTRE on the assumption the surface runs to the far edge. On a stair whose tall
 * half faces AWAY from the jump, the surface ends AT the centre, which is a bigger error than
 * {@code Parkour.TAKEOFF_EDGE} itself.
 *
 * <p>Written after the flagship bot walked off a {@code waxed_oxidized_cut_copper_stairs facing=north}
 * takeoff at {@code (211,-37,11)} heading SOUTH and pressed jump one tick too late — the whole vertical
 * trace is free-fall ({@code -0.078, -0.156, -0.230, -0.304}), never the {@code +0.42} of a launch, because
 * vanilla only converts {@code jumping} to an impulse while {@code onGround}.
 *
 * <p>Ground truth is read off the collision body: for each cardinal edge, the tallest collision surface in
 * the 0.6-wide body band adjacent to that edge — i.e. what the bot's feet actually rest on at that lip.
 */
class StairCollisionTruthTest {

    private static final int N = 0, E = 1, S = 2, W = 3;
    private static final String[] NAME = {"N", "E", "S", "W"};
    /** Cardinal ordinal → unit step, the convention {@code MovementContext.FACING_DX/DZ} uses. */
    private static final int[] DX = {0, 1, 0, -1};
    private static final int[] DZ = {-1, 0, 1, 0};

    private static final double C0 = 0.2, C1 = 0.8;   // the 0.6-wide centred body band
    private static final double LIP = 0.25;           // how far in from an edge counts as "at that lip"

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static List<Block> stairs() {
        List<Block> out = new ArrayList<>();
        for (Block b : BuiltInRegistries.BLOCK) {
            if (b instanceof StairBlock) out.add(b);
        }
        return out;
    }

    private static BlockState state(Block b, Direction facing, Half half, StairsShape shape) {
        return b.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(BlockStateProperties.HALF, half)
                .setValue(BlockStateProperties.STAIRS_SHAPE, shape);
    }

    /**
     * The tallest collision surface (in sixteenths) within the body band adjacent to cardinal {@code edge} —
     * the height the bot's feet rest at when standing at that lip.
     */
    private static int surfaceAtEdge(VoxelShape shape, int edge) {
        double bx0 = C0, bx1 = C1, bz0 = C0, bz1 = C1;
        switch (edge) {
            case N -> { bz0 = 0.0; bz1 = LIP; }
            case S -> { bz0 = 1.0 - LIP; bz1 = 1.0; }
            case W -> { bx0 = 0.0; bx1 = LIP; }
            default -> { bx0 = 1.0 - LIP; bx1 = 1.0; }   // E
        }
        double top = 0.0;
        for (AABB a : shape.toAabbs()) {
            if (a.maxX > bx0 && a.minX < bx1 && a.maxZ > bz0 && a.minZ < bz1) {
                top = Math.max(top, a.maxY);
            }
        }
        return (int) Math.round(top * 16.0);
    }

    @Test
    void aBottomStairsFullHeightEdgeIsTheOneNavBlockCallsItsFacing() {
        List<String> table = new ArrayList<>();
        List<String> mismatches = new ArrayList<>();
        int checked = 0;

        for (Block block : stairs()) {
            String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                BlockState st = state(block, facing, Half.BOTTOM, StairsShape.STRAIGHT);
                VoxelShape shape = st.getCollisionShape(null, null);
                long d = NavBlock.descriptorFor(st);

                int[] surf = new int[4];
                for (int e = 0; e < 4; e++) surf[e] = surfaceAtEdge(shape, e);
                int ourFacing = NavBlock.stairFacing(d);
                checked++;

                // MEASURED (2026-08-14), and NOT the "one tall edge" shape I first assumed: the raised step
                // spans the FULL WIDTH of the cell, so it fills the two PERPENDICULAR edge bands as well.
                // Exactly one edge reads the low 8 — the one OPPOSITE the facing:
                //     facing=north | N=16 E=16 S= 8 W=16
                //     facing=east  | N=16 E=16 S=16 W= 8
                // So the invariant worth pinning is the LOW edge, not the tall one.
                int low = -1;
                int lowCount = 0;
                for (int e = 0; e < 4; e++) {
                    if (surf[e] < 16) { low = e; lowCount++; }
                }
                int expectedLow = (ourFacing + 2) & 3;
                String row = String.format("%-36s facing=%-5s | vanilla N=%2d E=%2d S=%2d W=%2d low=%s | ours facing=%s",
                        id, facing, surf[N], surf[E], surf[S], surf[W],
                        low < 0 ? "none" : NAME[low], NAME[ourFacing]);
                table.add(row);

                if (lowCount != 1 || low != expectedLow || surf[ourFacing] < 16) mismatches.add(row);
            }
        }

        System.out.println("=== BOTTOM stair surface per edge vs NavBlock.stairFacing ("
                + checked + " states, " + stairs().size() + " stair blocks) ===");
        table.forEach(System.out::println);
        assertTrue(mismatches.isEmpty(),
                "vanilla's low (8/16) edge is not the one opposite NavBlock.stairFacing:\n"
                        + String.join("\n", mismatches));
    }

    /**
     * The planner's own {@code directionalTopY} must equal vanilla on EVERY edge, not just the two that lie
     * along the facing axis. It used to report 8 on the two PERPENDICULAR edges, described as a conservative
     * approximation — which it is for the START of a move (a bigger apparent rise) and is NOT for the
     * DESTINATION, where a smaller apparent rise admits a step the bot cannot make. That is what walked the
     * flagship bot into the side of a stair at {@code (205,-38,57)}; see the corrected javadoc there.
     */
    @Test
    void directionalTopYMatchesVanillaOnEveryEdge() {
        List<String> bad = new ArrayList<>();
        for (Block block : stairs()) {
            String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                for (Half half : Half.values()) {
                    BlockState st = state(block, facing, half, StairsShape.STRAIGHT);
                    VoxelShape shape = st.getCollisionShape(null, null);
                    long d = NavBlock.descriptorFor(st);
                    for (int e = 0; e < 4; e++) {
                        int vanilla = surfaceAtEdge(shape, e);
                        int ours = com.orebit.mod.pathfinding.blockpathfinder.MovementContext
                                .directionalTopY(d, DX[e], DZ[e]);
                        if (vanilla != ours) {
                            bad.add(id + " " + facing + "/" + half + " edge " + NAME[e]
                                    + ": vanilla=" + vanilla + " ours=" + ours);
                        }
                    }
                }
            }
        }
        assertTrue(bad.isEmpty(),
                "directionalTopY disagrees with vanilla's collision surface:\n" + String.join("\n", bad));
    }

    /** A TOP stair has a flat full-height top, so every edge reads 16 and directionalTopY's stair branch is
     *  correctly skipped ({@code stairHalf != 0}). */
    @Test
    void aTopStairIsFullHeightOnEveryEdge() {
        for (Block block : stairs()) {
            String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                VoxelShape shape = state(block, facing, Half.TOP, StairsShape.STRAIGHT)
                        .getCollisionShape(null, null);
                for (int e = 0; e < 4; e++) {
                    assertEquals(16, surfaceAtEdge(shape, e),
                            id + " " + facing + " TOP: edge " + NAME[e] + " should be a flat full-height top");
                }
            }
        }
    }

    /** The "is copper special" check, as for doors: every stair type must match oak's geometry. */
    @Test
    void everyStairTypeHasTheSameGeometryAsOak() {
        Block oak = net.minecraft.world.level.block.Blocks.OAK_STAIRS;
        List<String> deviants = new ArrayList<>();
        for (Block block : stairs()) {
            if (block == oak) continue;
            String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                for (Half half : Half.values()) {
                    VoxelShape a = state(oak, facing, half, StairsShape.STRAIGHT).getCollisionShape(null, null);
                    VoxelShape b = state(block, facing, half, StairsShape.STRAIGHT).getCollisionShape(null, null);
                    for (int e = 0; e < 4; e++) {
                        if (surfaceAtEdge(a, e) != surfaceAtEdge(b, e)) {
                            deviants.add(id + " " + facing + "/" + half + " edge " + NAME[e]
                                    + ": oak=" + surfaceAtEdge(a, e) + " this=" + surfaceAtEdge(b, e));
                        }
                    }
                }
            }
        }
        assertTrue(deviants.isEmpty(), "a stair type deviates from oak:\n" + String.join("\n", deviants));
    }

    /**
     * The consequence that matters for {@code Parkour}: launching AWAY from the facing edge, the surface the
     * bot leaves from is the LOW half, and it ends at the cell CENTRE — half a block short of the far edge
     * that {@code TAKEOFF_EDGE} (0.35 past centre) assumes. Pins the geometry behind the (211,-37,11) wedge.
     */
    @Test
    void launchingAwayFromTheFacingEdgeLeavesFromTheLowHalf() {
        // The witnessed block/state: cut copper stairs, facing NORTH, bottom half; the jump travelled SOUTH.
        Block witnessed = null;
        for (Block b : stairs()) {
            if ("waxed_oxidized_cut_copper_stairs".equals(BuiltInRegistries.BLOCK.getKey(b).getPath())) {
                witnessed = b;
                break;
            }
        }
        assertTrue(witnessed != null, "the witnessed stair block should exist in this version's registry");
        BlockState st = state(witnessed, Direction.NORTH, Half.BOTTOM, StairsShape.STRAIGHT);
        VoxelShape shape = st.getCollisionShape(null, null);
        assertEquals(16, surfaceAtEdge(shape, N), "the tall half is on the FACING (north) edge");
        assertEquals(8, surfaceAtEdge(shape, S),
                "travelling SOUTH the bot leaves from the 8/16 half — the takeoff lip is half a block lower "
                        + "and half a block nearer than a full-cell floor, which is what swallowed the jump");
    }
}
