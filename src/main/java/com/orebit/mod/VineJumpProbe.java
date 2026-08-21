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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Headless PHYSICS PROBE — <b>does holding jump keep a bot up when its feet are ABOVE a one-block vine?</b>
 * Armed by {@code -Dorebit.vinejump}; inert otherwise.
 *
 * <p><b>The question.</b> The mid-climb adoption wedge (ReplanCourse {@code midclimb-t6..t10}) ends with the
 * bot at {@code y=152.114} with {@code climbable=false climbBelow=true} — it has topped out ABOVE a
 * one-block vine — and then falling back a cell, which strands it off the newly-installed plan's frame. The
 * proposed rule is "feet NOT climbable, floor climbable and NOT solid -> hold jump". Whether that actually
 * holds the bot is a physics question, not a design question: vanilla only honours jump while
 * {@code onClimbable()}, and that is a FEET-block test, so at {@code y>=152.0} the feet block is air and the
 * jump should be inert. The bot may nonetheless hover, if it sinks a hair, re-grabs the vine at {@code
 * y<152.0}, climbs at {@code +0.2/t}, and repeats — an oscillation that never leaves {@code y>=152.0} in
 * practice. This probe measures it instead of arguing about it.
 *
 * <p><b>The scene</b> (a pillar in the void, so nothing else can hold the bot up): solid floor at
 * {@link #Y0}, ONE vine at {@code Y0+1} on the east wall, air above. Two legs, each {@link #TICKS} long:
 * <ol>
 *   <li><b>feet-in-vine</b> — spawn at {@code Y0+1} (feet INSIDE the vine, the ordinary climb pose).</li>
 *   <li><b>topped-out</b> — spawn at {@code Y0+2.114}, reproducing the wedge pose exactly.</li>
 * </ol>
 * Each leg holds jump on EVERY tick and records y per tick. The result file reports the min/max/final y and
 * the full series, so the claim "holding jump keeps it at y >= Y0+2" is answered by a number.
 *
 * <p>A CONTROL leg (no jump) runs first for each pose, so the jump legs are read against the fall they are
 * supposed to prevent rather than against an assumption.
 */
public final class VineJumpProbe {

    private VineJumpProbe() {}

    private static final String RESULT_FILE = "orebit-vinejump-result.properties";
    /** Floor level; the vine sits at {@code Y0+1} and the top-out pose is {@code Y0+2.114}. */
    private static final int Y0 = 150;
    private static final int X = 1000, Z = 1000;
    /** Ticks per leg — long enough for an oscillation to show its floor, short enough to stay cheap. */
    private static final int TICKS = 60;

    public static void register(PlatformEvents events) {
        if (System.getProperty("orebit.vinejump") == null) {
            return;
        }
        Probe probe = new Probe();
        events.onServerStarted(probe::start);
        events.onWorldTickEnd(probe::tick);
        OrebitCommon.LOGGER.info("[Orebit/vinejump] armed: {} legs", Leg.values().length);
    }

    /** The four measurements: each pose with and without the jump input. */
    private enum Leg {
        FEET_IN_VINE_NOJUMP,
        FEET_IN_VINE_JUMP,
        TOPPED_OUT_NOJUMP,
        TOPPED_OUT_JUMP;

        boolean jump() { return this == FEET_IN_VINE_JUMP || this == TOPPED_OUT_JUMP; }
        /** Spawn feet height: inside the vine, or the wedge's measured top-out pose. */
        double spawnY() { return (this == TOPPED_OUT_JUMP || this == TOPPED_OUT_NOJUMP) ? Y0 + 2.114 : Y0 + 1; }
    }

    private static final class Probe {
        MinecraftServer server;
        ServerLevel level;
        AllyBotEntity bot;
        FakePlayerEntity owner;

        int legIndex = -1;
        int legTicks;
        boolean done;
        final StringBuilder report = new StringBuilder();
        double minY, maxY;
        StringBuilder series;

        void start(MinecraftServer server) {
            this.server = server;
            this.level = server.overworld();
            paint();
            owner = new FakePlayerEntity(server, level, new GameProfile(
                    UUID.nameUUIDFromBytes("OrebitVineJump:owner".getBytes(StandardCharsets.UTF_8)),
                    "VineJump"));
            // No addFreshEntity: BotManager.spawnBotFor owns placement, and adding a connection-less
            // FakePlayerEntity to the level NPEs on the first packet send.
            owner.setPos(X + 0.5, Y0 + 1, Z + 0.5);
            BotManager.spawnBotFor(owner);
            bot = BotManager.botFor(owner);
            nextLeg();
        }

        /** A solid floor with ONE vine above it, everything else air — nothing but the vine can hold the bot. */
        void paint() {
            BlockState stone = Blocks.STONE.defaultBlockState();
            BlockState air = Blocks.AIR.defaultBlockState();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    level.setBlockAndUpdate(new BlockPos(X + dx, Y0, Z + dz), stone);
                    for (int dy = 1; dy <= 8; dy++) {
                        level.setBlockAndUpdate(new BlockPos(X + dx, Y0 + dy, Z + dz), air);
                    }
                }
            }
            // The vine's support: a wall to the EAST of the vine column, and the vine facing it.
            level.setBlockAndUpdate(new BlockPos(X + 1, Y0 + 1, Z), stone);
            level.setBlockAndUpdate(new BlockPos(X, Y0 + 1, Z),
                    Blocks.VINE.defaultBlockState().setValue(VineBlock.EAST, Boolean.TRUE));
        }

        void nextLeg() {
            legIndex++;
            if (legIndex >= Leg.values().length) {
                finish();
                return;
            }
            Leg leg = Leg.values()[legIndex];
            legTicks = 0;
            minY = Double.MAX_VALUE;
            maxY = -Double.MAX_VALUE;
            series = new StringBuilder();
            bot.setPos(X + 0.5, leg.spawnY(), Z + 0.5);
            bot.setDeltaMovement(0, 0, 0);
            bot.setJumping(false);
        }

        void tick(ServerLevel lvl) {
            if (done || bot == null || lvl != level) {
                return;
            }
            Leg leg = Leg.values()[legIndex];
            // The whole experiment: hold (or withhold) jump, touch nothing else.
            bot.setJumping(leg.jump());
            bot.setForward(0f);
            bot.setStrafe(0f);

            double y = bot.getY();
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
            if (legTicks < 24) { // the first 24 ticks are where any oscillation establishes itself
                series.append(String.format(Locale.ROOT, "%.3f ", y));
            }
            legTicks++;
            if (legTicks >= TICKS) {
                report.append(String.format(Locale.ROOT,
                        "%s: spawnY=%.3f finalY=%.3f minY=%.3f maxY=%.3f heldAtOrAbove_%d=%s%n    series=%s%n",
                        leg, leg.spawnY(), y, minY, maxY, Y0 + 2, minY >= Y0 + 2 ? "YES" : "NO", series));
                nextLeg();
            }
        }

        void finish() {
            done = true;
            Path file = ConfigDir.serverDir(server).resolve(RESULT_FILE);
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write("completed=true\n");
                w.write(report.toString());
            } catch (IOException ignored) {
                // the log line below is the primary channel
            }
            OrebitCommon.LOGGER.info("[Orebit/vinejump] RESULT\n{}", report);
            server.halt(false);
        }
    }
}
