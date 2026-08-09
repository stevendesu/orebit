package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Own-toggle forgiveness, the KIND rule (DESIGN-trapdoors.md §7; DESIGN-fence-gates.md §4/§7): {@link
 * NavGridUpdater#forgivableToggle} is the match rule deciding whether a pos-matched block change is the
 * openable toggle the follower announced — forgiven, no {@code foreignVersion} bump — or a foreign world
 * change that must bump it. Forgiveness requires a same-Block state flip of a DOOR, TRAPDOOR or FENCE GATE;
 * every kind wired into the executor verbs ({@code setDoorOpen}/{@code setTrapdoorOpen}/{@code setGateOpen})
 * must appear in this chain, or that kind's own toggles each read as foreign and burn a window re-search.
 * The slot machinery and the {@code foreignVersion} comparison around this rule are welded to a live
 * {@code ServerLevel} and cannot be stood up under the Knot test classloader (the split {@code
 * PathPlanOwnEditTest}, {@code NavGridEpochTest} and {@code NetherPortalIndexTest} document); this pins the
 * one headlessly-testable piece, over real interned {@link BlockState}s.
 */
class NavGridToggleForgivenessTest {

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
    }

    private static BlockState open(BlockState s, boolean open) {
        return s.setValue(BlockStateProperties.OPEN, open);
    }

    // ---- the three openable kinds: an OPEN flip of each is forgivable --------------------------------

    @Test
    void doorOpenFlipIsForgivable() {
        BlockState closed = open(Blocks.OAK_DOOR.defaultBlockState(), false);
        assertTrue(NavGridUpdater.forgivableToggle(closed, open(closed, true)),
                "a door OPEN flip is the announced toggle (same block, different state, door kind)");
    }

    @Test
    void trapdoorOpenFlipIsForgivable() {
        BlockState closed = open(Blocks.OAK_TRAPDOOR.defaultBlockState(), false);
        assertTrue(NavGridUpdater.forgivableToggle(closed, open(closed, true)),
                "a trapdoor OPEN flip is the announced toggle");
    }

    @Test
    void gateOpenFlipIsForgivableBothDirections() {
        BlockState closed = open(Blocks.OAK_FENCE_GATE.defaultBlockState(), false);
        BlockState opened = open(closed, true);
        assertTrue(NavGridUpdater.forgivableToggle(closed, opened),
                "a fence-gate SET_OPEN is the announced toggle — the gate arm of the kind chain "
                        + "(DESIGN-fence-gates.md §4: without it every own gate toggle burns a re-search)");
        assertTrue(NavGridUpdater.forgivableToggle(opened, closed),
                "the rule is direction-free: a SET_CLOSED of the same gate is equally forgivable");
    }

    // ---- refusals: everything that is NOT the announced openable flip --------------------------------

    @Test
    void sameStateIsNotForgivable() {
        BlockState closed = open(Blocks.OAK_FENCE_GATE.defaultBlockState(), false);
        assertFalse(NavGridUpdater.forgivableToggle(closed, closed),
                "no state change (interned states are reference-equal) is never a toggle");
    }

    @Test
    void blockChangeIsNotForgivable() {
        BlockState gate = open(Blocks.OAK_FENCE_GATE.defaultBlockState(), false);
        assertFalse(NavGridUpdater.forgivableToggle(gate, Blocks.AIR.defaultBlockState()),
                "an openable being BROKEN (block changes) is a foreign change, never forgiven");
        BlockState door = open(Blocks.OAK_DOOR.defaultBlockState(), false);
        assertFalse(NavGridUpdater.forgivableToggle(door, gate),
                "a block swap between two openable kinds is still a block change, not a toggle");
    }

    @Test
    void nonOpenableSameBlockFlipIsNotForgivable() {
        // The dangling-arm guard: a leftover one-shot over a cell now holding a non-openable must not
        // forgive that block's own state churn (the comment's comparator/observer case).
        BlockState observer = Blocks.OBSERVER.defaultBlockState()
                .setValue(BlockStateProperties.POWERED, false);
        assertFalse(NavGridUpdater.forgivableToggle(observer,
                        observer.setValue(BlockStateProperties.POWERED, true)),
                "a non-openable's same-block state flip (observer POWERED) is a foreign change");
        BlockState lever = Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.POWERED, false);
        assertFalse(NavGridUpdater.forgivableToggle(lever,
                        lever.setValue(BlockStateProperties.POWERED, true)),
                "a lever flip is a foreign change — the kind chain is doors/trapdoors/gates only");
    }
}
