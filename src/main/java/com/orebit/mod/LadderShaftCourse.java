package com.orebit.mod;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.orebit.mod.platform.ConfigDir;
import com.orebit.mod.platform.PlatformEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Headless LADDER-SHAFT harness — <b>can the bot survive a plan swap that lands MID-CLIMB, with no floor
 * under it?</b> Armed by {@code -Dorebit.ladder}; inert otherwise.
 *
 * <p><b>The case</b> (owner, 2026-08-21): "imagine a bot climbing a 200-block tall ladder. This will, by
 * necessity, involve a replan partway up the ladder with NO solid floor below it. This bot can't 'come to
 * rest'. It MUST cling to the ladder while swapping plans." Every candidate fix for the mid-climb adoption
 * wedge has so far been judged against a ONE-BLOCK vine, where the bot is never more than 12 mm from a cell
 * boundary and is always within a rung of a completed waypoint. That fixture cannot tell a correct fix from
 * one that merely happens to work at a boundary. This one can: {@link #HEIGHT} rungs guarantee the bot is
 * mid-rung, far from any completed waypoint, when the machinery has to act.
 *
 * <p><b>No artificial seal.</b> The replan is not forced by editing the world — a climb this long crosses
 * window boundaries on its own, so the window-slide/seam machinery replans mid-shaft as a matter of course.
 * That is the honest version of the owner's scenario, and it means a wedge here is a wedge in ordinary
 * operation, not an artefact of the harness. (A sealed variant can be added later if a harder case is
 * wanted; the plumbing below is deliberately trigger-free.)
 *
 * <p><b>The scene</b> — a pillar in the void, so the ladder is the ONLY way up and the only thing holding
 * the bot:
 * <ul>
 *   <li>Stone floor at {@link #Y0}, one cell, at {@code (BASE_X, BASE_Z)}.</li>
 *   <li>A stone spine at {@code BASE_X + 1} running {@code Y0+1 .. Y0+HEIGHT+2}, with a LADDER on its west
 *       face at {@code BASE_X} for the same span (facing WEST — a ladder's facing points AWAY from the
 *       block it hangs on).</li>
 *   <li>A 3×3 stone landing at the top, {@code Y0+HEIGHT}, and the goal standing on it.</li>
 *   <li>Everything else is air. A slip is unrecoverable and shows up as a FAIL with the exact height it
 *       started from, not as a quiet detour.</li>
 * </ul>
 *
 * <p><b>What the result proves.</b> {@code maxY} says how far it got; {@code slipFrom}/{@code slipTo} record
 * the LARGEST downward excursion after the climb began — a bot that stops clinging during a swap shows up
 * here as a multi-block slide even if it later recovers; {@code swaps} counts block-plan installs, so a run
 * with {@code swaps=0} means the boundary machinery never serviced the climb at all (pair it with the
 * "boundary REFUSED" forensic in {@code BotNavigator}, which names the half that refused).
 */
public final class LadderShaftCourse {

    private LadderShaftCourse() {}

    private static final String RESULT_FILE = "orebit-ladder-result.properties";
    private static final int Y0 = 60;
    private static final int BASE_X = 1400, BASE_Z = 1400;
    /** Rungs to climb. Long enough that a window boundary lands mid-shaft, which is the whole point. */
    private static final int HEIGHT = 200;
    /** Generous: the climb alone is ~{@code HEIGHT / 0.2} = 1000 ticks at vanilla's +0.2/t ladder rate. */
    private static final int BUDGET_TICKS = 4000;
    /** A downward excursion this large is a SLIP, not the sink/grab jitter of an ordinary cling. */
    private static final double SLIP_BLOCKS = 1.5;
    /** Chunks to load around the shaft so the follower's ring-shaped nav-readiness gate can open. */
    private static final int PRIME_RADIUS_CHUNKS = 12;
    /** Ticks to let the nav grid build the whole shaft before the goal is issued — see the tick() comment. */
    /**
     * Ticks to let the nav grid build the whole VICINITY before the goal is issued (owner 2026-08-21).
     *
     * <p>Not merely the shaft: the region tier is optimistic about UNBUILT space, so while the surrounding
     * superflat is unbuilt every sideways/downward route looks cheap and the cascade prefers them to the
     * shaft. The monkey-patch is to make the alternatives REAL — run the server at view/simulation distance
     * 20 and prime a wide chunk ring — so the region tier can prove there is no passage that way and the
     * (dearly-priced) ascent becomes the cheapest remaining route. 800 ticks is the drain budget for that
     * much terrain; a shorter delay measured the optimism instead of the truth.
     */
    private static final int ISSUE_DELAY_TICKS = 800;

    public static void register(PlatformEvents events) {
        if (System.getProperty("orebit.ladder") == null) {
            return;
        }
        Course course = new Course();
        events.onServerStarted(course::start);
        events.onWorldTickEnd(course::tick);
        OrebitCommon.LOGGER.info(
                "[Orebit/ladder] armed: {}-rung {} shaft at ({},{}), floor y={} (raw height prop={})",
                HEIGHT, System.getProperty("orebit.ladder.kind", "vine"), BASE_X, BASE_Z, Y0,
                System.getProperty("orebit.ladder.height", "<unset>"));
    }

    private static final class Course {
        MinecraftServer server;
        ServerLevel level;
        AllyBotEntity bot;
        FakePlayerEntity owner;
        BufferedWriter trace;

        int ticks;
        boolean done;
        boolean issued;
        BlockPos goal;

        double maxY = -Double.MAX_VALUE;
        /** Running peak since the last slip reset — the reference a downward excursion is measured from. */
        double peakSinceLow = -Double.MAX_VALUE;
        double slipFrom, slipTo;   // the LARGEST recorded downward excursion
        double biggestSlip;
        int swaps;
        Object lastPlanRef;

        void start(MinecraftServer server) {
            this.server = server;
            Debug.ENABLED = true;
            Debug.VERBOSE = true;
            this.level = server.overworld();
            paint();
            // The goal is a stance ON the ring, one cell west of the shaft — not the shaft's own top cell,
            // which is air beside a ladder and not a place to stand.
            this.goal = new BlockPos(BASE_X - 1, Y0 + HEIGHT + 1, BASE_Z);
            owner = new FakePlayerEntity(server, level, new GameProfile(
                    UUID.nameUUIDFromBytes("OrebitLadder:owner".getBytes(StandardCharsets.UTF_8)),
                    "LadderShaft"));
            // NB: no addFreshEntity — BotManager.spawnBotFor owns placement, and adding a connection-less
            // FakePlayerEntity to the level NPEs on the first packet send.
            owner.setPos(BASE_X + 0.5, Y0 + 1, BASE_Z + 0.5);
            BotManager.spawnBotFor(owner);
            bot = BotManager.botFor(owner);
            if (bot == null) {
                finish("bot never spawned");
                return;
            }
            try {
                trace = Files.newBufferedWriter(ConfigDir.serverDir(server).resolve("orebit-ladder-trace.txt"),
                        StandardCharsets.UTF_8);
                trace.write("tick y footY onGround climbable climbBelow driveState\n");
            } catch (IOException ignored) {
                // trace is best-effort; the result file is the contract
            }
        }

        /** Floor, a stone spine with a ladder on its west face, a landing on top. Everything else void. */
        void paint() {
            BlockState stone = Blocks.STONE.defaultBlockState();
            BlockState air = Blocks.AIR.defaultBlockState();
            // THE RUNG KIND (-Dorebit.ladder.kind, default VINE). The two climbables are NOT interchangeable
            // to the region tier, and vine is the one that isolates the bug we are actually chasing:
            //   * VINE  - SHAPE_EMPTY, so it is `passable`. A vine column clears FragmentBuilder's HEADROOM
            //             test and fails only the FOOTING test (`cellWater || standable-below` — no climbable
            //             term), so it exercises exactly one missing rule.
            //   * LADDER - a 3px panel, so NOT `passable`. A ladder column fails footing AND headroom, and the
            //             headroom half needs the whole shaft-transit-wiggle arc (the 0.6x0.6 XZ-projection
            //             bit + block-tier vertical transit admission, "both halves or neither"). Out of scope
            //             here; kept behind the flag so the distinction stays testable.
            final boolean ladderKind = "ladder".equalsIgnoreCase(System.getProperty("orebit.ladder.kind", "vine"));
            // A ladder's HORIZONTAL_FACING points AWAY from its support; a vine's boolean face names the side
            // it HANGS ON. Spine is at BASE_X+1 (east of the column), so: ladder faces WEST, vine attaches EAST.
            BlockState ladder = ladderKind
                    ? Blocks.LADDER.defaultBlockState()
                            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
                            .setValue(BlockStateProperties.WATERLOGGED, false)
                    : Blocks.VINE.defaultBlockState().setValue(VineBlock.EAST, Boolean.TRUE);

            for (int dx = -2; dx <= 3; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int y = Y0 - 2; y <= Y0 + HEIGHT + 4; y++) {
                        level.setBlockAndUpdate(new BlockPos(BASE_X + dx, y, BASE_Z + dz), air);
                    }
                }
            }
            level.setBlockAndUpdate(new BlockPos(BASE_X, Y0, BASE_Z), stone);
            // BORE VARIANT (-Dorebit.ladder.bore): encase the shaft in stone on the three open sides, so the
            // scene matches the shape the unit suite already proves climbable (TrapdoorLadderClimbTest's 1x1
            // bore in solid rock). The default open-air spine is the honest version of the owner's scenario;
            // this variant exists to ISOLATE whether enclosure is what the planner is keying on, since the
            // open shaft produced zero plans at both 20 and 200 rungs.
            if (System.getProperty("orebit.ladder.bore") != null) {
                for (int y = Y0 + 1; y <= Y0 + HEIGHT + 1; y++) {
                    level.setBlockAndUpdate(new BlockPos(BASE_X - 1, y, BASE_Z), stone);
                    level.setBlockAndUpdate(new BlockPos(BASE_X, y, BASE_Z - 1), stone);
                    level.setBlockAndUpdate(new BlockPos(BASE_X, y, BASE_Z + 1), stone);
                }
            }
            for (int y = Y0 + 1; y <= Y0 + HEIGHT + 1; y++) {
                level.setBlockAndUpdate(new BlockPos(BASE_X + 1, y, BASE_Z), stone);       // the spine
                level.setBlockAndUpdate(new BlockPos(BASE_X, y, BASE_Z), ladder);          // the rungs
            }
            // Top landing: a stone RING at Y0+HEIGHT — 3x3 MINUS the shaft's own column, which must stay
            // open. A solid 3x3 here seals the ladder: the first cut of this course did exactly that, no
            // route to the goal existed, and the bot sat on the floor with swaps=0 and maxY=Y0+1. The bot
            // climbs the open column to feet Y0+HEIGHT+1 — level with the ring's top surface — and steps
            // off sideways onto it (a flat Traverse out of the shaft).
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;   // the shaft stays open
                    level.setBlockAndUpdate(new BlockPos(BASE_X + dx, Y0 + HEIGHT, BASE_Z + dz), stone);
                }
            }
            // PRIME THE VICINITY *LAST*. The follower's readiness gate polls a RING of built chunks around
            // the bot (BotNavigator.navVicinityReady -> NavStore.ringBuilt, radius navReadyRadiusChunks),
            // and a 1x1 shaft in the void touches exactly ONE chunk column — so nothing else ever loads,
            // the ring is never built, and the bot waits forever. Measured on the first cut of this course:
            // "nav grid active: 1 chunks built" followed by 4000 ticks of "WAIT (nav grid unbuilt)", with
            // swaps=0 and the bot still standing on the floor. Touching each chunk loads it, which is what
            // drives ChunkNavLoader's build.
            //
            // ORDER IS LOAD-BEARING: this MUST run AFTER the blocks are written. Priming first nav-builds
            // the chunks while they are still empty air (an all-air section is left UNBUILT, so there is no
            // built section for the later setBlockAndUpdate writes to patch into) and the whole shaft stays
            // invisible to the planner. Measured via `/bot trace`: every section along the column read
            // `navSection=UNBUILT`, the region cascade planned an optimistic skeleton straight through the
            // void, and the block A* expanded ZERO nodes against a window target 17 blocks off the shaft.
            final int cx = BASE_X >> 4, cz = BASE_Z >> 4;
            for (int rx = -PRIME_RADIUS_CHUNKS; rx <= PRIME_RADIUS_CHUNKS; rx++) {
                for (int rz = -PRIME_RADIUS_CHUNKS; rz <= PRIME_RADIUS_CHUNKS; rz++) {
                    level.getChunk(cx + rx, cz + rz);
                }
            }

        }

        void tick(ServerLevel lvl) {
            if (done || bot == null || lvl != level) {
                return;
            }
            // SETTLE BEFORE ISSUING (2026-08-21). The follower's readiness gate only asks for a RING of
            // chunks around the BOT; a 200-block shaft reaches far above that, and a goal issued the instant
            // the ring is ready builds its cascade from a grid that does not yet contain the upper shaft.
            // Measured: issuing at tick 2 produced a DESCENDING skeleton (window target (1399,-61,1399), the
            // void below) which then blamed a hop and gave up permanently, while a cascade built moments
            // later from the same world climbed correctly (window target (1400,104,1400)). The bot is not
            // wrong to give up on the data it had — the harness simply asked too early.
            if (ticks < ISSUE_DELAY_TICKS) {
                ticks++;
                return;
            }
            if (!issued) {
                issued = true;
                // TRACE MODE (-Dorebit.ladder.trace): dump what BOTH tiers see for this shaft instead of
                // driving. `/bot trace` and `/bot rtrace` are Brigadier commands and so unreachable headless,
                // but their entry points are plain methods — this is the same call each command makes. The
                // course produces no plan at all here, with no START-DEAD, so the question is which tier goes
                // silent: the region cascade dumps its skeleton, the block A* dumps every expansion.
                if (System.getProperty("orebit.ladder.trace") != null) {
                    OrebitCommon.LOGGER.info("[Orebit/ladder] rtrace -> {}", bot.regionTraceTo(goal.below()));
                    OrebitCommon.LOGGER.info("[Orebit/ladder] trace  -> {}", bot.traceTo(goal.below()));
                    finish("TRACE ONLY (no drive)");
                    return;
                }
                bot.comeTo(goal, 0.75, 0.75, 0); // exact: reach the precise cell (the GotoCommand form)
                return;
            }
            ticks++;

            final double y = bot.getY();
            if (y > maxY) maxY = y;
            if (y > peakSinceLow) peakSinceLow = y;
            final double drop = peakSinceLow - y;
            if (drop > biggestSlip) {
                biggestSlip = drop;
                slipFrom = peakSinceLow;
                slipTo = y;
            }
            if (drop > SLIP_BLOCKS) {
                peakSinceLow = y; // start a fresh excursion window so successive slides are counted apart
            }

            Object planRef = bot.navigator().currentPlan();
            if (planRef != null && planRef != lastPlanRef) {
                lastPlanRef = planRef;
                swaps++;
            }

            if (trace != null && ticks % 5 == 0) {
                try {
                    trace.write(String.format(Locale.ROOT, "%d %.3f %d %s %s %s %s%n",
                            ticks, y, bot.blockPosition().getY(), bot.grounded(), bot.onClimbable(),
                            bot.climbableBelow(), bot.navigator().driveState()));
                } catch (IOException ignored) {
                    // best-effort
                }
            }

            // PASS is a STANCE, not a distance (the vine-bridge lesson): standing on the top deck.
            if (bot.grounded() && bot.blockPosition().getY() >= Y0 + HEIGHT + 1) {
                finish("PASS standing on the top landing");
                return;
            }
            if (y < Y0 - 1) {
                finish("FAIL fell off the shaft");
                return;
            }
            if (ticks >= BUDGET_TICKS) {
                finish("FAIL budget exhausted (wedged mid-climb?)");
            }
        }

        void finish(String reason) {
            done = true;
            Path file = ConfigDir.serverDir(server).resolve(RESULT_FILE);
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write("completed=true\n");
                w.write("reason=" + reason + "\n");
                w.write("ticks=" + ticks + "\n");
                w.write("rungs=" + HEIGHT + "\n");
                w.write("floorY=" + Y0 + "\n");
                w.write("topY=" + (Y0 + HEIGHT + 1) + "\n");
                w.write(String.format(Locale.ROOT, "maxY=%.3f%n", maxY == -Double.MAX_VALUE ? -1 : maxY));
                w.write(String.format(Locale.ROOT, "biggestSlip=%.3f%n", biggestSlip));
                w.write(String.format(Locale.ROOT, "slipFrom=%.3f slipTo=%.3f%n", slipFrom, slipTo));
                w.write("swaps=" + swaps + "\n");
                w.write(String.format(Locale.ROOT, "finalPos=(%.2f,%.2f,%.2f)%n",
                        bot == null ? -1 : bot.getX(), bot == null ? -1 : bot.getY(),
                        bot == null ? -1 : bot.getZ()));
            } catch (IOException ignored) {
                // the log line below is the primary channel
            }
            OrebitCommon.LOGGER.info(
                    "[Orebit/ladder] RESULT {} ticks={} maxY={} biggestSlip={} ({}->{}) swaps={}",
                    reason, ticks, String.format(Locale.ROOT, "%.3f", maxY),
                    String.format(Locale.ROOT, "%.3f", biggestSlip),
                    String.format(Locale.ROOT, "%.3f", slipFrom), String.format(Locale.ROOT, "%.3f", slipTo),
                    swaps);
            try {
                if (trace != null) trace.close();
            } catch (IOException ignored) {
                // best-effort
            }
            server.halt(false);
        }
    }
}
