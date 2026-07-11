package com.orebit.mod.pathfinding.blockpathfinder.movements;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;

/**
 * Pins the DERIVED {@link ParkourEnvelope} table against the validated physics model
 * ({@code internal_docs/parkour_envelope_params.py}). The static init contains NO hard-coded maxima — the
 * expected values live HERE, so a change to any physics constant (jump velocity, drag, the takeoff edge,
 * the {@link ParkourEnvelope#MAX_CLEARED_AIR} cap) fails loudly against these pins rather than silently
 * shifting what the planner offers. No Minecraft bootstrap needed (pure arithmetic).
 */
class ParkourEnvelopeTest {

    // Row order: {flat, rise, fall1, fall2, fall3, diag} (ParkourEnvelope.FLAT..DIAG).

    @Test
    void baseRowIsFlat3Rise2Fall444Diag2() {
        // Full-block takeoff (topY 16), normal floor, no slow body — the reference envelope.
        assertArrayEquals(new int[] {3, 2, 4, 4, 4, 2}, ParkourEnvelope.MAX_GAP[16][0][0],
                "BASE row must be flat 3 / rise 2 / fall 4/4/4 / diag 2 (rise-3, flat-4, diag-3 excluded)");
    }

    @Test
    void slabTakeoffRowIsTighter() {
        // Slab takeoff (topY 8): the +0.5 effective rise eats the flat and rise reach.
        assertArrayEquals(new int[] {2, 0, 3, 4, 4, 2}, ParkourEnvelope.MAX_GAP[8][0][0],
                "slab takeoff row must be flat 2 / rise 0 / fall 3/4/4 / diag 2");
    }

    @Test
    void soulSandTakeoffRowIsTighter() {
        // Soul sand (full block, speed factor 0.4): the cut horizontal budget tightens every class.
        assertArrayEquals(new int[] {2, 1, 2, 2, 3, 1}, ParkourEnvelope.MAX_GAP[16][1][0],
                "soul-sand takeoff row must be flat 2 / rise 1 / fall 2/2/3 / diag 1");
    }

    @Test
    void berryBodyRowIsTighter() {
        // A LIGHT through-slow body cell (berry) scales the whole horizontal arc.
        assertArrayEquals(new int[] {2, 0, 2, 3, 3, 1}, ParkourEnvelope.MAX_GAP[16][0][1],
                "berry-body takeoff row must be flat 2 / rise 0 / fall 2/3/3 / diag 1");
    }

    @Test
    void indexClampsIntoRange() {
        assertEquals(1, ParkourEnvelope.index(0), "topY 0 clamps to 1");
        assertEquals(1, ParkourEnvelope.index(-5), "negative clamps to 1");
        assertEquals(16, ParkourEnvelope.index(16), "16 stays 16");
        assertEquals(16, ParkourEnvelope.index(31), "over 16 clamps to 16");
        // The clamped-to-1 index 0 aliases row 1 (never queried, but must not NPE).
        assertArrayEquals(ParkourEnvelope.MAX_GAP[1][0][0], ParkourEnvelope.MAX_GAP[0][0][0],
                "row 0 aliases row 1 for index safety");
    }

    @Test
    void apexMatchesJumpRise() {
        // The vertical arc apex is at tick 6; y(6)·16 must reconcile with MovementContext.JUMP_RISE (20/16).
        double apex = ParkourEnvelope.y(6, 1.0);
        assertEquals(1.2522, apex, 1e-3, "apex feet height is ~1.2522 blocks");
        assertEquals(MovementContext.JUMP_RISE, apex * 16, 0.05,
                "y(6)·16 must reconcile with JUMP_RISE (20 sixteenths)");
    }

    @Test
    void landingTicksAndBudgetsMatchTheModel() {
        // The airtime / reach values printed by parkour_envelope_params.py — pin them so a constant edit
        // is caught before it shifts a table cell.
        assertEquals(11, ParkourEnvelope.tForDy(0.0, 1.0), "flat airtime is 11 ticks");
        assertEquals(3.3442, ParkourEnvelope.X(11, 1.0, 1.0), 1e-3, "flat reach budget ~3.344 blocks");
        assertEquals(13, ParkourEnvelope.tForDy(-1.0, 1.0), "fall-1 airtime is 13 ticks");
        assertEquals(3.9140, ParkourEnvelope.X(13, 1.0, 1.0), 1e-3, "fall-1 reach budget ~3.914 blocks");
    }

    @Test
    void flatAndDiagMarginsAreOnTheRightSideOfTheBudget() {
        // The six-outcome margins: at the admitted gmax the required travel fits the budget, and gmax+1
        // overshoots it — so nudging a physics constant flips a cell and trips a pin.
        double budget = ParkourEnvelope.X(11, 1.0, 1.0); // flat / diag share the Δy=0 airtime
        // flat: g=3 fits (2.85 <= 3.344), g=4 does not (3.85 > 3.344).
        assertTrue(ParkourEnvelope.dReqCard(3) <= budget, "flat 3-gap required travel must fit the budget");
        assertTrue(ParkourEnvelope.dReqCard(4) > budget, "flat 4-gap must exceed the budget (no flat-4 row)");
        // diag: g=2 fits (2.711 <= 3.344), g=3 does not (4.125 > 3.344).
        assertTrue(ParkourEnvelope.dReqDiag(2) <= budget, "diagonal 2-gap must fit the budget");
        assertTrue(ParkourEnvelope.dReqDiag(3) > budget, "diagonal 3-gap must exceed the budget");
    }

    @Test
    void aSlowBodyCellNeverIncreasesReach() {
        // The no-help clamp: every occ=berry cell is <= its occ=none ceiling for the same surface+gsf.
        for (int topY = 1; topY <= 16; topY++) {
            for (int gsf = 0; gsf < 2; gsf++) {
                int[] none = ParkourEnvelope.MAX_GAP[topY][gsf][0];
                int[] berry = ParkourEnvelope.MAX_GAP[topY][gsf][1];
                for (int k = 0; k < 6; k++) {
                    assertTrue(berry[k] <= none[k],
                            "a slow body cell must never INCREASE reach (topY=" + topY + " gsf=" + gsf
                                    + " class=" + k + ")");
                }
            }
        }
    }
}
