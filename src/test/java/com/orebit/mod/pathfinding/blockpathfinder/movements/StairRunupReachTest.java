package com.orebit.mod.pathfinding.blockpathfinder.movements;

import org.junit.jupiter.api.Test;

/**
 * <b>How far can a bot jump off a stair?</b> — the measurement behind the {@code (211,-37,11)} wedge
 * (owner question, 2026-08-14).
 *
 * <p>{@link ParkourEnvelope}'s whole table is baked at {@link ParkourEnvelope#vInf} — TERMINAL sprint speed,
 * i.e. a run-up long enough to reach it. On a BOTTOM stair crossed along its facing axis the tall half ends
 * at the CELL CENTRE ({@code StairCollisionTruthTest}), so that assumption is violated twice over: the bot
 * launches from the 8/16 half, and it has only the tall half to accelerate on.
 *
 * <p><b>The window this measures.</b> The bot is 0.6 wide, so:
 * <ul>
 *   <li><b>0.30</b> — the furthest BACK the centre can sit without the hitbox overhanging the neighbouring
 *       column (which may be fire, lava, or solid, so overhang is not safe to assume);
 *   <li><b>0.80</b> — the furthest FORWARD it can be while the box still overlaps the tall half
 *       ({@code 0.80 − 0.30 = 0.50}, the tall half's far edge), i.e. the last tick it is still supported at
 *       full height.
 * </ul>
 * That is a <b>0.50-block run-up from a standstill</b>, and the jump fires at 0.30 past the cell centre
 * rather than the table's assumed {@link ParkourEnvelope#TAKEOFF_EDGE} of 0.35.
 *
 * <p>This is a REPORT, not a gate: it prints the reach against every landing class and fails only if the
 * physics helpers regress, so the numbers can be read straight out of the build log when the fix is chosen.
 */
class StairRunupReachTest {

    private static final double GSF = 1.0;   // ordinary (non-slow) floor
    private static final double OCC = 1.0;   // no through-slow occupancy
    /** Hitbox half-width — the reason the back limit is 0.30 and not 0.00. */
    private static final double HALF = 0.3;
    /** The tall half of a bottom stair, crossed along its facing axis. */
    private static final double TALL_HALF_END = 0.5;

    /** Simulate the grounded sprint from rest across {@code dist}, returning the speed at the lip. */
    private static double runupSpeed(double dist) {
        double v = 0.0;
        double travelled = 0.0;
        for (int t = 0; t < 200 && travelled < dist; t++) {
            v = ParkourEnvelope.vGroundStep(v, GSF);
            travelled += v;
        }
        return v;
    }

    /** Largest cardinal gap clearable with jump-tick speed {@code vJump0} onto a landing {@code classDy}
     *  below/above, launching from a surface of height {@code takeoffSurfaceY} at offset {@code edge}. */
    private static int maxGap(double vJump0, int classDy, double takeoffSurfaceY, double edge) {
        double edy = ParkourEnvelope.effDy(classDy, takeoffSurfaceY);
        int t = ParkourEnvelope.tForDy(edy, 1.0);
        double budget = ParkourEnvelope.xFrom(t, vJump0, GSF, OCC);
        int best = 0;
        for (int g = 1; g <= 8; g++) {
            double cleared = g;
            int drop = classDy < 0 ? -classDy : 0;
            if (cleared > ParkourEnvelope.MAX_CLEARED_AIR + drop + 1e-9) break;
            if (g + 0.2 - edge <= budget + 1e-9) best = g; else break;
        }
        return best;
    }

    @Test
    void reportStairRunupReach() {
        final double backLimit = HALF;                       // 0.30 — no overhang into the neighbour
        final double lipLimit = TALL_HALF_END + HALF;        // 0.80 — last tick still on the tall half
        final double runup = lipLimit - backLimit;           // 0.50
        final double edgeFromCentre = lipLimit - 0.5;        // 0.30 past centre (table assumes 0.35)

        double vLip = runupSpeed(runup);
        double vTerm = ParkourEnvelope.vInf(GSF);
        double jShort = ParkourEnvelope.vJumpFrom(vLip, GSF);
        double jFull = ParkourEnvelope.vJumpFrom(vTerm, GSF);

        System.out.println("=== stair run-up reach (back 0.30 -> lip 0.80, a " + runup + "-block run-up) ===");
        System.out.printf("  ground speed at the lip   %.4f   (terminal %.4f, %.1f%%)%n",
                vLip, vTerm, 100.0 * vLip / vTerm);
        System.out.printf("  jump-tick speed           %.4f   (terminal-run-up %.4f, %.1f%%)%n",
                jShort, jFull, 100.0 * jShort / jFull);
        System.out.println();
        System.out.println("  class      | short run-up, 8/16 launch | short run-up, full block | TABLE (terminal, full)");

        int[] classes = {0, 1, -1, -2, -3};
        String[] names = {"flat", "rise+1", "fall-1", "fall-2", "fall-3"};
        for (int i = 0; i < classes.length; i++) {
            int gStairShort = maxGap(jShort, classes[i], 0.5, edgeFromCentre);
            int gFlatShort = maxGap(jShort, classes[i], 1.0, edgeFromCentre);
            int gTable = maxGap(jFull, classes[i], 1.0, 0.35);
            System.out.printf("  %-9s  |            %d             |            %d             |          %d%n",
                    names[i], gStairShort, gFlatShort, gTable);
        }
        System.out.println("  (Parkour emits flat 1-3, rising 1-2, falling up to 4 — compare against those.)");

        // Guard only the physics helpers, so the report can never fail spuriously: a 0.5-block run-up must
        // still get most of the way to terminal (MC ground acceleration is very fast), and the refactor that
        // exposed these must keep X == xFrom(vJump(terminal)).
        org.junit.jupiter.api.Assertions.assertTrue(vLip > 0.5 * vTerm,
                "a 0.5-block sprint from rest should reach well over half terminal speed; got " + vLip);
        org.junit.jupiter.api.Assertions.assertEquals(
                ParkourEnvelope.X(8, GSF, OCC),
                ParkourEnvelope.xFrom(8, ParkourEnvelope.vJumpFrom(
                        ParkourEnvelope.vRunup(GSF), GSF), GSF, OCC), 1e-12,
                "X must delegate to xFrom at the RUN-UP jump speed — that is what the table now bakes");
    }
}
