package com.orebit.mod.pathfinding.regionpathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionFragments;

/**
 * Key-level correctness fixtures for the <b>from-fragment in the search-node identity</b>
 * (DESIGN-virtual-start-fragment.md §0.5). These are pure static assertions over
 * {@link RegionPathfinder#approachRowKey} and {@link RegionEdgeBlacklist} — no {@code ServerLevel}, no grid,
 * no search — because the load-bearing win is entirely at the node-identity / blacklist-row layer: making
 * {@code (region, current-fragment, entry-face, from-fragment)} the node key lets two approaches that share a
 * region, a current-fragment, AND an entry face still be DISTINCT nodes when they were reached from different
 * predecessor fragments. That distinctness is what lets the search try the second approach after the first is
 * blamed (the two-hallways / A==G cliff fixes), and it is exactly what these tests pin.
 *
 * <p>Physical key layout after the 2026-07 repack (verified against {@link RegionAddress#packLevelKey} +
 * {@code RegionPathfinder.fragmentKey}/{@code searchKey}): region bits 0..48, current-fragment bits 49..54,
 * entry-face bits 55..57, from-fragment bits 58..63. The XOR assertions below assert the SOLE differing field
 * bit-exactly, which is a direct statement that before this change the two rows collided.
 *
 * <p>Scope note: a full end-to-end search fixture (build two hallways into one merged fragment, blame H1, watch
 * the search reroute via H2) is deliberately avoided — it needs a hand-seeded merged-fragment grid whose
 * flood/containment behaviour is fragile to reproduce, and the correctness mechanism it would exercise lives
 * entirely in the key/blacklist layer that these deterministic assertions cover exhaustively.
 */
public class VirtualStartFragmentKeyTest {

    private static final int S = RegionPathfinder.VIRTUAL_START_FRAG; // 62
    private static final int V = RegionPathfinder.VIRTUAL_GOAL_FRAG;  // 63

    /** Bit position of the 6-bit from-fragment field in the full search key. */
    private static final int FROM_FRAG_SHIFT = 58;
    /** Bit position of the 6-bit current-fragment field in the physical key. */
    private static final int CUR_FRAG_SHIFT = 49;

    // ===================================================================================================
    // Sentinel sanity — the reserved id space the whole design leans on.
    // ===================================================================================================
    @Test
    void sentinelsAreDistinctAndOutsideTheRealFragmentRange() {
        assertEquals(62, RegionPathfinder.VIRTUAL_START_FRAG, "S sentinel is the reserved id 62");
        assertEquals(63, RegionPathfinder.VIRTUAL_GOAL_FRAG, "V sentinel is 63");
        assertNotEquals(S, V, "S and V must be distinct sentinels");
        assertTrue(RegionPathfinder.isVirtualStart(S));
        assertFalse(RegionPathfinder.isVirtualStart(V));
        assertFalse(RegionPathfinder.isVirtualStart(0));
        assertFalse(RegionPathfinder.isVirtualStart(RegionFragments.MAX_FRAGMENTS - 1)); // 61, a real frag
        // Both sentinels sit ABOVE every real fragment id (0..MAX_FRAGMENTS-1), so a from-fragment field can
        // carry either a real predecessor id or the S sentinel without collision.
        assertTrue(S >= RegionFragments.MAX_FRAGMENTS, "S is beyond the real fragment id range");
        assertTrue(S <= 0x3F && V <= 0x3F, "both sentinels fit the 6-bit from/current-fragment field");
    }

    // ===================================================================================================
    // (1) TWO HALLWAYS — one fragment F reached from two predecessors H1,H2 via the SAME face. Before this
    // change both approaches interned to the SAME node (F,+X) and the cheaper (H1) shadowed H2, so blaming
    // H1 disconnected F entirely (false give-up). With from-fragment in the KEY the two are distinct rows,
    // so blaming (F|from=H1) leaves (F|from=H2) alive — the search can still try H2.
    // ===================================================================================================
    @Test
    void twoHallways_sameFace_distinctFromFragment_areDistinctRows() {
        final int rx = 5, ry = 3, rz = 7, fragF = 4;
        final int face = 1;      // +X, the SAME entry face for both approaches
        final int h1 = 1, h2 = 2; // two distinct predecessor fragments

        long keyH1 = RegionPathfinder.approachRowKey(rx, ry, rz, fragF, face, h1);
        long keyH2 = RegionPathfinder.approachRowKey(rx, ry, rz, fragF, face, h2);

        assertNotEquals(keyH1, keyH2,
                "two approaches into F on the same face but from different predecessors must be DISTINCT nodes");

        // The rows differ in the from-fragment field ALONE — everything else (region, current-fragment,
        // entry-face) is byte-identical. This is the precise statement that, before from-fragment entered the
        // key, keyH1 == keyH2 (the collapse the fix removes).
        long expectedDelta = ((long) (h1 & 0x3F) ^ (long) (h2 & 0x3F)) << FROM_FRAG_SHIFT;
        assertEquals(expectedDelta, keyH1 ^ keyH2,
                "the sole differing field between the two approaches is the from-fragment (bits 58..63)");

        // Determinism: the builder is a pure function of its inputs.
        assertEquals(keyH1, RegionPathfinder.approachRowKey(rx, ry, rz, fragF, face, h1));
    }

    @Test
    void twoHallways_blacklistDistinguishesTheTwoApproaches() {
        final int rx = 5, ry = 3, rz = 7, fragF = 4;
        final int face = 1;
        final int h1 = 1, h2 = 2;
        // V-approach rows key the FROM side on the full node key; the TO side is the physical V node.
        long vKey = RegionPathfinder.fragmentNodeKey(rx, ry, rz, V);

        long fromH1 = RegionPathfinder.approachRowKey(rx, ry, rz, fragF, face, h1);
        long fromH2 = RegionPathfinder.approachRowKey(rx, ry, rz, fragF, face, h2);

        RegionEdgeBlacklist bl = new RegionEdgeBlacklist();
        bl.add(fromH1, vKey); // the H1 approach is proven dead

        assertTrue(bl.contains(fromH1, vKey), "the blamed H1 approach is forbidden");
        assertFalse(bl.contains(fromH2, vKey),
                "the H2 approach into the SAME fragment on the SAME face survives — the from-fragment-in-key win: "
                        + "the search can still reach F (and thence V) via H2 after H1 is blamed");
        assertEquals(1, bl.size(), "only the H1 edge was forbidden");
    }

    // ===================================================================================================
    // (2) SPIRAL STAIRCASE — stacked distinct fragments in ONE region, all entered from the same face with
    // the same from-fragment, are already distinct nodes by CURRENT-fragment id. Regression guard: adding
    // from-fragment to the key did NOT break stacked-fragment disambiguation.
    // ===================================================================================================
    @Test
    void spiralStaircase_stackedFragmentsInOneRegion_haveDistinctKeys() {
        final int rx = 2, ry = 4, rz = -3; // one region; negative rz exercises the masked-coord path
        final int face = 3;                // +Y, the same entry face for every step
        final int fromFrag = 0;            // same predecessor for all — only current-fragment varies

        long[] keys = new long[RegionFragments.MAX_FRAGMENTS];
        for (int frag = 0; frag < keys.length; frag++) {
            keys[frag] = RegionPathfinder.approachRowKey(rx, ry, rz, frag, face, fromFrag);
        }
        for (int a = 0; a < keys.length; a++) {
            for (int b = a + 1; b < keys.length; b++) {
                assertNotEquals(keys[a], keys[b],
                        "stacked steps frag=" + a + " and frag=" + b + " in one region must be distinct nodes");
            }
        }

        // And the sole differing field between two consecutive steps is the CURRENT-fragment field (bits
        // 49..54) — from-fragment/entry/region are held fixed, so from-fragment addition is orthogonal to it.
        long delta = keys[1] ^ keys[2];
        long expected = ((long) (1 & 0x3F) ^ (long) (2 & 0x3F)) << CUR_FRAG_SHIFT;
        assertEquals(expected, delta,
                "consecutive staircase steps differ ONLY in the current-fragment field");
    }

    // ===================================================================================================
    // (3) A==G CLIFF ANALOG — the bot starts in the same region+fragment (A) as the goal. The S-side
    // approach to V (entry=ENTRY_START, from=S) and the go-around approach that RE-ENTERS A from the
    // staircase face (entry=<face>, from=<neighbourFrag>) must be DISTINCT rows, so blaming the S-side
    // approach leaves the staircase approach alive. Under the OLD entry-stripped physical key both collapsed
    // to fragmentNodeKey(A) and one blacklist row disconnected V entirely (the false give-up).
    // ===================================================================================================
    @Test
    void aEqualsG_startApproachAndStaircaseApproach_areDistinctRows() {
        final int rx = 9, ry = 2, rz = 9, fragA = 3;
        final int staircaseFace = 0;   // -X, the face the go-around re-enters A through
        final int neighbourFrag = 5;   // the fragment the search hopped FROM on the staircase route

        // The S-side approach: the search root sits in A, so its approach to V carries entry=ENTRY_START and
        // from=VIRTUAL_START_FRAG.
        long sApproach = RegionPathfinder.approachRowKey(
                rx, ry, rz, fragA, RegionPathfinder.ENTRY_START, S);
        // The go-around approach: A re-entered from a real neighbour through a real face.
        long staircaseApproach = RegionPathfinder.approachRowKey(
                rx, ry, rz, fragA, staircaseFace, neighbourFrag);

        assertNotEquals(sApproach, staircaseApproach,
                "the two approaches to the SAME physical fragment A must be distinct rows (A==G no longer collapses)");

        // The collapse the fix removes: under the OLD entry-stripped row (physical (region,fragment) only) BOTH
        // approaches shared this single key, so one blacklist entry killed every approach to V.
        long oldEntryStrippedRow = RegionPathfinder.fragmentNodeKey(rx, ry, rz, fragA);
        assertNotEquals(oldEntryStrippedRow, sApproach,
                "the new approach key is NOT the entry-stripped physical key (the collapse is gone)");
        assertNotEquals(oldEntryStrippedRow, staircaseApproach);
    }

    @Test
    void aEqualsG_blamingTheStartApproachLeavesTheStaircaseApproachAlive() {
        final int rx = 9, ry = 2, rz = 9, fragA = 3;
        final int staircaseFace = 0, neighbourFrag = 5;
        long vKey = RegionPathfinder.fragmentNodeKey(rx, ry, rz, V);

        long sApproach = RegionPathfinder.approachRowKey(
                rx, ry, rz, fragA, RegionPathfinder.ENTRY_START, S);
        long staircaseApproach = RegionPathfinder.approachRowKey(
                rx, ry, rz, fragA, staircaseFace, neighbourFrag);

        RegionEdgeBlacklist bl = new RegionEdgeBlacklist();
        bl.add(sApproach, vKey); // the direct-climb (S-side) approach floods and is blamed

        assertTrue(bl.contains(sApproach, vKey), "the S-side approach is forbidden after the flood");
        assertFalse(bl.contains(staircaseApproach, vKey),
                "the go-around (staircase re-entry) approach to V survives — the region tier can re-derive the "
                        + "walk-around skeleton instead of the false give-up (INV-2)");
    }

    // ===================================================================================================
    // Parity — approachRowKey is the SINGLE shared builder used by both the search-time V-approach check
    // (relaxVirtualGoal) and the blame add-side (PathPlan.blockedHop). It must be a pure fold of searchKey ∘
    // fragmentKey, so a row minted on one side matches the query on the other bit-for-bit.
    // ===================================================================================================
    @Test
    void approachRowKey_isDeterministicAndFieldOrthogonal() {
        final int rx = -11, ry = 6, rz = 4, frag = 7;
        // Changing ONLY the from-fragment changes ONLY bits 58..63.
        long base = RegionPathfinder.approachRowKey(rx, ry, rz, frag, 2, 10);
        long fromChanged = RegionPathfinder.approachRowKey(rx, ry, rz, frag, 2, 11);
        assertEquals(((long) (10 & 0x3F) ^ (long) (11 & 0x3F)) << FROM_FRAG_SHIFT, base ^ fromChanged,
                "from-fragment lives in bits 58..63 and nowhere else");
        // Changing ONLY the entry-face changes ONLY bits 55..57.
        long entryChanged = RegionPathfinder.approachRowKey(rx, ry, rz, frag, 4, 10);
        assertEquals(((long) (2 & 0x7) ^ (long) (4 & 0x7)) << 55, base ^ entryChanged,
                "entry-face lives in bits 55..57 and nowhere else");
        // Same inputs → same key (pure).
        assertEquals(base, RegionPathfinder.approachRowKey(rx, ry, rz, frag, 2, 10));
    }
}
