package com.orebit.mod;

/**
 * Single in-game debug toggle for the bot's runtime diagnostics — the path-particle overlay, the per-plan /
 * region / window log lines, the swim/stuck dumps, and the chat progress messages. Replaces the scatter of
 * per-class {@code DEBUG}/{@code DEBUG_PATH}/{@code DEBUG_CHAT} booleans with one switch, flipped live by
 * {@code /bot debug on|off} ({@link com.orebit.mod.commands.DebugCommand}) so a player can turn the firehose
 * on while reproducing a routing bug and off again without a rebuild. Default OFF (ship-safe).
 *
 * <p>This is deliberately ONLY the in-game logging/visualization noise. The test/benchmark instrumentation
 * knobs that the unit + JMH suites toggle independently — {@link
 * com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder#LOG_TIMING} (per-window node counts),
 * {@link com.orebit.mod.worldmodel.hpa.LeafCostComputer#INSTRUMENT} (leaf-build timing accumulators),
 * {@link com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder#TRACE} (the heavy {@code /bot trace}
 * dump), and the {@code PARTIAL_PATH} behaviour seam — stay separate on purpose: they are not play-noise and
 * the harness flips them in isolation.
 */
public final class Debug {

    private Debug() {}

    /** When true, the bot emits its runtime path/region/window logs, particle overlay, and chat progress. */
    public static boolean ENABLED = false;
}
