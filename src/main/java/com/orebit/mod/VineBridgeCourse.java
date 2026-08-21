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
 * Headless VINE-BRIDGE harness — <b>can the bot cross a span whose only footing is a run of vines?</b>
 * Armed by {@code -Dorebit.vinebridge}; inert otherwise.
 *
 * <p><b>Why.</b> The mid-climb adoption wedge (ReplanCourse {@code midclimb-t6..t10}) turns on a bot losing
 * a fraction of a block of height at a climbable handoff and falling out of its move's envelope. That
 * envelope is a foot-CELL band — for a flat step {@code fromFootY == toFootY}, so it has ZERO room below —
 * while the physical transient is sub-cell (a dip that step assist recovers). This course exercises the same
 * physics WITHOUT any replan: a long run of consecutive climbable steps, where every single one is a
 * handoff. If the cell-band envelope is the real problem, this fails on its own.
 *
 * <p><b>What crosses it.</b> Note it will NOT be {@link com.orebit.mod.pathfinding.blockpathfinder.movements.Traverse}:
 * lateral motion through a climbable is owned by {@code Climb} and priced as a sneak-speed cling
 * ({@code GRAB_LATERAL_COST = FLAT_COST / SNEAK_SPEED_FACTOR}, ~15.4 t/block vs 4.6 walking). The interesting
 * questions are therefore whether the planner routes across the vines at all, whether the cling survives
 * {@link #SPAN} consecutive steps, and what the per-tick Y actually does across each handoff.
 *
 * <p><b>The scene</b> — everything not listed is VOID, so there is no way around:
 * <ul>
 *   <li>Start ledge, stone floor at {@link #Y0} (feet {@code Y0+1}).</li>
 *   <li>A {@link #SPAN}-long run of vines at feet level {@code Y0+1}, each attached to the NORTH wall.</li>
 *   <li>That wall is stone, {@code Y0+1..Y0+4} — four tall, so the bot cannot ascend onto it and walk.</li>
 *   <li>Nothing beneath the vines: a fall is unrecoverable and shows up as a FAIL, not a detour.</li>
 *   <li>End ledge + goal.</li>
 * </ul>
 * The result file records arrival, the closest approach, the min feet-Y seen while over the span (the
 * sub-cell dip this whole investigation is about), and the movement mix.
 */
public final class VineBridgeCourse {

    private VineBridgeCourse() {}

    private static final String RESULT_FILE = "orebit-vinebridge-result.properties";
    private static final int Y0 = 150;
    private static final int BASE_X = 1000, BASE_Z = 1000;
    /** Consecutive vine cells to cross — long enough that a per-handoff loss compounds visibly. */
    private static final int SPAN = 8;
    private static final int BUDGET_TICKS = 1200;
    /**
     * {@code -Dorebit.vinebridge=floor} puts the vines at the bot's FLOOR level instead of its FEET level.
     * That distinction decides which movement owns the crossing, and it is the whole point of the variant:
     * <ul>
     *   <li><b>feet</b> (default) — the vine is IN the feet cell: a curtain. {@code Climb} owns it as a
     *       sneak-speed lateral cling, and it crosses cleanly (measured: minY 151.197 over 8 cells, never
     *       leaving the foot cell).</li>
     *   <li><b>floor</b> — the vine is the FLOOR cell and the feet cell is air: walking along the TOPS of
     *       vines, which is {@code Traverse}'s shape, not {@code Climb}'s. A vine has no collision, so
     *       there is nothing to stand on — the bot can only be there while actively holding an input.</li>
     * </ul>
     */
    private static boolean floorVariant() {
        return "floor".equalsIgnoreCase(System.getProperty("orebit.vinebridge", ""));
    }

    public static void register(PlatformEvents events) {
        if (System.getProperty("orebit.vinebridge") == null) {
            return;
        }
        Course course = new Course();
        events.onServerStarted(course::start);
        events.onWorldTickEnd(course::tick);
        OrebitCommon.LOGGER.info("[Orebit/vinebridge] armed: span={} vines, variant={}",
                SPAN, floorVariant() ? "FLOOR (Traverse-owned)" : "FEET (Climb-owned curtain)");
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
        double closest = Double.MAX_VALUE;
        double minYoverSpan = Double.MAX_VALUE;
        int minFootYoverSpan = Integer.MAX_VALUE;
        BlockPos goal;

        void start(MinecraftServer server) {
            this.server = server;
            Debug.ENABLED = true;
            Debug.VERBOSE = true;
            this.level = server.overworld();
            paint();
            this.goal = new BlockPos(BASE_X + SPAN + 2, Y0 + 1, BASE_Z);
            owner = new FakePlayerEntity(server, level, new GameProfile(
                    UUID.nameUUIDFromBytes("OrebitVineBridge:owner".getBytes(StandardCharsets.UTF_8)),
                    "VineBridge"));
            // NB: no addFreshEntity — BotManager.spawnBotFor owns placement. Adding a connection-less
            // FakePlayerEntity to the level NPEs the first packet send (learned the hard way).
            owner.setPos(BASE_X + 0.5, Y0 + 1, BASE_Z + 0.5);
            BotManager.spawnBotFor(owner);
            bot = BotManager.botFor(owner);
            if (bot == null) {
                finish("bot never spawned");
                return;
            }
            try {
                trace = Files.newBufferedWriter(ConfigDir.serverDir(server).resolve("orebit-vinebridge-trace.txt"),
                        StandardCharsets.UTF_8);
                trace.write("tick x y z footY onGround climbable climbBelow move driveState\n");
            } catch (IOException ignored) {
                // trace is best-effort; the result file is the contract
            }
        }

        /** Start ledge -> SPAN vines on a 4-tall north wall -> end ledge. Everything else is void. */
        void paint() {
            BlockState stone = Blocks.STONE.defaultBlockState();
            BlockState air = Blocks.AIR.defaultBlockState();
            // Clear a generous box so no worldgen remnant offers an alternate route.
            for (int dx = -3; dx <= SPAN + 6; dx++) {
                for (int dy = -4; dy <= 10; dy++) {
                    for (int dz = -4; dz <= 4; dz++) {
                        level.setBlockAndUpdate(new BlockPos(BASE_X + dx, Y0 + dy, BASE_Z + dz), air);
                    }
                }
            }
            // Start ledge (x = 0..1) and end ledge (x = SPAN+2 .. SPAN+3), floor at Y0.
            for (int dx = 0; dx <= 1; dx++) {
                level.setBlockAndUpdate(new BlockPos(BASE_X + dx, Y0, BASE_Z), stone);
            }
            for (int dx = SPAN + 2; dx <= SPAN + 3; dx++) {
                level.setBlockAndUpdate(new BlockPos(BASE_X + dx, Y0, BASE_Z), stone);
            }
            // The span: a 4-tall north wall carrying vines at the walking level, nothing underneath.
            for (int i = 0; i < SPAN; i++) {
                int x = BASE_X + 2 + i;
                for (int dy = floorVariant() ? 0 : 1; dy <= 4; dy++) {
                    level.setBlockAndUpdate(new BlockPos(x, Y0 + dy, BASE_Z - 1), stone);
                }
                // feet variant: vine at the walking level (Y0+1). floor variant: vine one lower (Y0), so
                // the feet cell above it is AIR and the crossing is a Traverse over non-collidable floor.
                level.setBlockAndUpdate(new BlockPos(x, floorVariant() ? Y0 : Y0 + 1, BASE_Z),
                        Blocks.VINE.defaultBlockState().setValue(VineBlock.NORTH, Boolean.TRUE));
            }
        }

        void tick(ServerLevel lvl) {
            if (done || bot == null || lvl != level) {
                return;
            }
            if (!issued) {
                issued = true;
                bot.comeTo(goal, 0.75, 0.75, 0); // exact: reach the precise cell (the GotoCommand form)
                return;
            }
            ticks++;
            double dx = bot.getX() - (goal.getX() + 0.5);
            double dz = bot.getZ() - (goal.getZ() + 0.5);
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < closest) closest = d;

            // Over the span: record the sub-cell dip that the cell-band envelope cannot see.
            final int bx = (int) Math.floor(bot.getX());
            if (bx >= BASE_X + 2 && bx < BASE_X + 2 + SPAN) {
                if (bot.getY() < minYoverSpan) minYoverSpan = bot.getY();
                if (bot.blockPosition().getY() < minFootYoverSpan) minFootYoverSpan = bot.blockPosition().getY();
            }
            if (trace != null && ticks % 2 == 0) {
                try {
                    trace.write(String.format(Locale.ROOT, "%d %.3f %.3f %.3f %d %s %s %s%n",
                            ticks, bot.getX(), bot.getY(), bot.getZ(), bot.blockPosition().getY(),
                            bot.grounded(), bot.onClimbable(), bot.navigator().driveState()));
                } catch (IOException ignored) {
                    // best-effort
                }
            }

            // ARRIVAL IS A STANCE, NOT A DISTANCE. A proximity test passes while the bot is still hovering on
            // the LAST vine — measured: PASS at closest=0.78 with finalPos x=1009.72, one whole cell short of
            // the ledge and not grounded on anything. The crossing is only proven when the bot is STANDING on
            // the far ledge, so that is what this asserts: feet on (or past) the ledge column AND on real
            // ground, which no cell of the span can satisfy.
            if (bot.blockPosition().getX() >= BASE_X + SPAN + 2 && bot.grounded()) {
                finish("PASS standing on the far ledge");
                return;
            }
            if (bot.getY() < Y0 - 3) {
                finish("FAIL fell off the span");
                return;
            }
            if (ticks >= BUDGET_TICKS) {
                finish("FAIL budget exhausted (wedged on the span?)");
            }
        }

        void finish(String reason) {
            done = true;
            Path file = ConfigDir.serverDir(server).resolve(RESULT_FILE);
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write("completed=true\n");
                w.write("reason=" + reason + "\n");
                w.write("ticks=" + ticks + "\n");
                w.write(String.format(Locale.ROOT, "closest=%.2f%n", closest));
                w.write(String.format(Locale.ROOT, "minYoverSpan=%.3f%n",
                        minYoverSpan == Double.MAX_VALUE ? -1 : minYoverSpan));
                w.write("minFootYoverSpan=" + (minFootYoverSpan == Integer.MAX_VALUE ? -1 : minFootYoverSpan) + "\n");
                w.write("spanFeetY=" + (Y0 + 1) + "\n");
                w.write(String.format(Locale.ROOT, "finalPos=(%.2f,%.2f,%.2f)%n",
                        bot == null ? -1 : bot.getX(), bot == null ? -1 : bot.getY(), bot == null ? -1 : bot.getZ()));
            } catch (IOException ignored) {
                // the log line below is the primary channel
            }
            OrebitCommon.LOGGER.info("[Orebit/vinebridge] RESULT {} ticks={} closest={} minYoverSpan={} minFootY={}",
                    reason, ticks, closest, minYoverSpan, minFootYoverSpan);
            try {
                if (trace != null) trace.close();
            } catch (IOException ignored) {
                // best-effort
            }
            server.halt(false);
        }
    }
}
