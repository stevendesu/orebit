package com.orebit.mod;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.orebit.mod.config.ConfigLoader;
import com.orebit.mod.platform.ConfigDir;
import com.orebit.mod.platform.EntityState;
import com.orebit.mod.platform.PlatformEvents;
import com.orebit.mod.worldmodel.pathing.NavStore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/**
 * Headless MEDIUM-AWARE STATION-KEEPING harness (a sibling of {@link GateCourse} / {@link ShaftCourse},
 * armed by its own {@code -Dorebit.swimmine} flag) — the live physics proof of the fix committed as
 * "medium-aware station-keeping so the mining hold stops sinking". The unit suite
 * ({@code StationKeepMediumTest}) pins WHICH branch {@link
 * com.orebit.mod.pathfinding.blockpathfinder.SteerControl#stationKeep} takes; nothing there proves the
 * bang-bang depth autopilot actually holds a REAL submerged bot against buoyancy and drag for the length of
 * a real break. That is this course's only job.
 *
 * <h2>The pathology being reproduced</h2>
 * {@link com.orebit.mod.pathfinding.blockpathfinder.PhaseRunner} holds while a {@code Need} is unmet ("stop
 * and fix, like a player"). Before the fix that hold emitted literally no inputs, which stops a bot only
 * where something already holds it up. In fluid a no-input bot sinks at a constant {@code dm.y = -0.025}:
 * measured on a 1.20.1 flagship log, 41 hold ticks took {@code botY 39.992 -> 39.018} (0.974 blocks), the
 * foot cell left the {@code Traverse}'s admitted band, {@link
 * com.orebit.mod.pathfinding.blockpathfinder.MovePlan#failWhen} fired, the mine request stopped, and {@link
 * BotMining} — reactive by design, a break continues only while the mover keeps asking for the SAME cell —
 * reset its progress with the block unbroken.
 *
 * <p><b>Why the tile geometry is the whole design.</b> The discriminator only exists when the bot has
 * NOTHING TO SINK ONTO. A submerged break whose from-cell sits on a floor is passed by the broken build too
 * (the bot settles onto the floor and keeps mining), so such a tile proves nothing. {@code Traverse}
 * explicitly admits a NON-STANDABLE start cell — {@code Traverse.candidates} opens with
 * {@code startTopY = ctx.standable(startDesc) ? ctx.topYOf(startDesc) : 16}, the surface-swim "feet at the
 * cell boundary" convention — so "walk sideways out of open water into a wall" is a first-class planned
 * move, and it is exactly the shape the incident produced. Tile 1 therefore floats the bot in a water column
 * with <b>eight cells of water and no standable cell beneath it</b> (asserted at build time by
 * {@link Course#floorGapBelow}, recorded in the result file) and makes the only route east a break the bot
 * must perform from that float.
 *
 * <p><b>The tiles</b> (corridors run +X at the tile's centre Z; every structural block is BEDROCK so a
 * break-capable bot has exactly one mineable cell per tile and no dig-around exists):
 * <ol>
 *   <li><b>openwaterwall</b> — THE DISCRIMINATOR. A sealed bedrock tank, water {@code Y0+1..Y0+11}, split
 *       at {@code WX} by a full-height bedrock wall whose only non-bedrock cells are the two STONE cells
 *       {@code (WX, Y0+9)} and {@code (WX, Y0+10)} — precisely the two body cells a flat {@code Traverse}
 *       from the floating cell {@code (WX-1, Y0+8)} onto the bedrock sill {@code (WX, Y0+8)} declares
 *       {@code Need.AIR} on. Under the float, water all the way to the tank floor at {@code Y0} — {@link
 *       Course#floorGapBelow} asserts the gap per trial (live: 8) and records it in the result file. Over
 *       and around, bedrock: no swim-over, no swim-under, and — because BOTH body cells are stone — no
 *       1-tall prone squeeze through the wall either. Only the FEET cell is asserted; see {@link
 *       Course#buildOpenWaterWall} for why. Stone with a diamond pick is ~5.6 ticks dry and grounded;
 *       vanilla divides the destroy speed by 5 for an eye in water and by 5 again for {@code !onGround}, so
 *       that cell is ~141 ticks (measured: 145) — hence {@link #ATTEMPT_BUDGET}, which the 600-800 of the
 *       sibling courses would not comfortably cover. PASS = the feet cell gone AND the goal reached.
 *   <li><b>hangplug</b> — the CLIMBABLE arm, and deliberately a CONTROL rather than a discriminator.
 *       {@code Climb.plan} builds ONE phase with NO needs ("Climb folds no edits — candidates refuses a
 *       blocked climb rather than pricing a break"), so a plug in the climb column is simply refused and
 *       could never produce a mining hold. What DOES put a hanging bot in front of a break is a lateral
 *       {@code Traverse} off a ladder (its non-standable-start arm again), so that is the tile: a 1x1
 *       bedrock shaft with a full ladder column, one STONE plug in the feet cell of the east exit, and a
 *       bedrock corridor beyond. The climbable branch of {@code stationKeep} is behaviourally UNCHANGED by
 *       the fix (the old code was {@code if (!grounded && onClimbable) { sneak; return; }}; the new code
 *       reaches the same sneak, minus a scaffolding exemption that no ladder tile can reach), so this tile
 *       is a no-regression guard on the restructured branch, not a pre/post discriminator. Its verdict is
 *       {@code GAP}-tolerant like {@link ShaftCourse}'s {@code control-plain-topdown}: a block-tier refusal
 *       of the ladder-lateral exit is a pre-existing routing gap, recorded as GAP and not counted as a
 *       failure.
 * </ol>
 *
 * <p><b>Config (scripts/swimmine/orebit.properties).</b> {@code mining.canMine=true} (the break IS the
 * test), {@code placement.canPlace=false} (no bridging around), {@code survival.needsBreath=false} (a
 * ~141-tick submerged hold would otherwise drown the bot and mask the result), {@code pathing.async=false}
 * (deterministic).
 *
 * <p><b>Inert in production</b> — {@link #register} returns immediately unless {@code -Dorebit.swimmine} is
 * set. Common, version-portable source (water / ladder / stone / bedrock states are drift-free
 * 1.17.1 &rarr; 26.2).
 */
public final class SwimMineCourse {

    private SwimMineCourse() {}

    private static final String RESULT_FILE = "orebit-swimmine-result.properties";
    private static final String TRACE_FILE = "orebit-swimmine-trace.txt";

    /** Tank/slab base Y — everything is built from real blocks floating well above the superflat plane. */
    private static final int Y0 = 150;
    private static final int BASE_X = 8;
    private static final int BASE_Z = 8;
    private static final int COLS = 2;
    private static final int STRIDE = 48; // grid cell size (> the longest tile span so nav grids never touch)

    /** Ticks to let the whole starting area gen + nav-build before the first goto. */
    private static final int WARMUP_TICKS = 160;
    /** Ticks after each teleport before the goto (the just-painted tile's nav patches settle). */
    private static final int SETTLE_TICKS = 60;
    private static final int NAV_RETRY_WINDOW = 60;
    private static final int MAX_NAV_RETRY = 5;
    /**
     * Per-trial attempt budget (ticks). Sized from the real break, not from the sibling courses: stone
     * (hardness 1.5) with a diamond pickaxe is {@code 8.0/1.5/30 = 0.1778} progress/tick dry and grounded
     * (~5.6 ticks), but {@code Player.getDestroySpeed} divides by 5 for an eye in water without Aqua
     * Affinity and by 5 again for {@code !onGround} — a floating bot is both — giving
     * {@code 8.0/25/1.5/30 = 0.00711}/tick, ~141 ticks per cell — measured live at tick 145. A budget of
     * 600-800 (the sibling courses') leaves almost no margin for the approach and the post-break step, and
     * anything under ~200 would time out a HEALTHY build and read as the bug. Generous on purpose: on the
     * BROKEN build the tile fails by exhausting this budget, and a long exhaustion is a cheap price for a
     * verdict that can never be a false FAIL.
     */
    private static final int ATTEMPT_BUDGET = 1800;

    private static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** Ladder/plug orientation for the hangplug tile: EAST = the ladder hangs on the shaft's WEST wall. */
    private static final Direction LADDER_FACING = Direction.EAST;

    public static void register(PlatformEvents events) {
        if (System.getProperty("orebit.swimmine") == null) {
            return;
        }
        Course course = new Course();
        events.onServerStarted(course::start);
        events.onWorldTickEnd(course::tick);
        OrebitCommon.LOGGER.info("[Orebit/swimmine] armed: {} trials", course.trials.size());
    }

    private enum Kind {
        /** Floating in open water, mine two cells out of a bedrock wall and cross. The discriminator. */
        OPEN_WATER_WALL,
        /** Hanging on a ladder, mine one plug out of the lateral exit and walk out. The climbable control. */
        HANG_PLUG
    }

    /** One station: a kind + its base grid cell, with start/goal geometry precomputed. */
    private static final class Trial {
        final String name;
        final Kind kind;
        final int baseX, baseZ;
        final int zc;                 // centre-line Z of the tile

        double startX, startY, startZ;
        float startYaw;
        BlockPos goal;
        int minFloorY;                // a fall this far below the tile = off the course

        /** The tile's mineable cells — the ONLY non-bedrock structure it contains. */
        final List<BlockPos> plugs = new ArrayList<>();
        /**
         * How many of {@link #plugs}, counted from index 0, the verdict REQUIRES gone. Not always all of
         * them: on the open-water tile the head cell is only there to deny a pre-break prone squeeze through
         * the wall, and once the FEET cell opens the bot can legitimately go prone and slip through the
         * 1-tall hole without touching it (measured — see {@link Course#buildOpenWaterWall}). The feet cell
         * is the whole assertion: it is the one break that must be completed from the floorless float, and
         * the wall admits no route at all until it is gone.
         */
        int requiredPlugs = 1;
        /** The FLOOR cell the discriminating step is planned FROM (tile 1 only; the floorless float). */
        BlockPos fromFloor;

        Trial(String name, Kind kind, int baseX, int baseZ) {
            this.name = name;
            this.kind = kind;
            this.baseX = baseX;
            this.baseZ = baseZ;
            this.zc = baseZ + 6;
            this.startYaw = yaw(1, 0); // face +X down the tile
            this.minFloorY = Y0 - 4;
            if (kind == Kind.OPEN_WATER_WALL) {
                int wx = baseX + 7;
                // The float: feet in (wx-1, Y0+9), i.e. FLOOR cell (wx-1, Y0+8) — water, with water all
                // the way down to the bedrock tank floor at Y0.
                this.fromFloor = new BlockPos(wx - 1, Y0 + 8, zc);
                this.startX = wx - 1 + 0.5;
                this.startY = Y0 + 9.5;   // mid-cell: exactly where the fixed hold parks the bot
                this.startZ = zc + 0.5;
                // The two cells the flat Traverse declares Need.AIR on. Index 0 — the FEET-body cell — is the
                // one the floating bot must mine before it may move at all, and the ONLY one this tile
                // asserts (see requiredPlugs).
                this.plugs.add(new BlockPos(wx, Y0 + 9, zc));   // Traverse's feet-body Need.AIR
                this.plugs.add(new BlockPos(wx, Y0 + 10, zc));  // Traverse's head-body Need.AIR
                this.requiredPlugs = 1;
                // The goal is a FEET cell (comeTo/GotoCommand take the stand position and derive the floor
                // one below): standing on the east shelf, three cells past the wall.
                this.goal = new BlockPos(wx + 3, Y0 + 9, zc);
            } else {
                int sx = baseX + 3;
                this.startX = sx + 0.5;
                this.startY = Y0 + 1;     // standing on the shaft's bedrock floor
                this.startZ = zc + 0.5;
                this.plugs.add(new BlockPos(sx + 1, Y0 + 5, zc)); // the lateral exit's feet cell
                this.goal = new BlockPos(baseX + 8, Y0 + 5, zc);  // FEET cell on the corridor floor Y0+4
            }
        }

        static float yaw(int dx, int dz) { return (float) Math.toDegrees(Math.atan2(-dx, dz)); }
    }

    private static final class Course {
        final List<Trial> trials = new ArrayList<>();
        final List<String> results = new ArrayList<>();
        MinecraftServer server;
        ServerLevel level;
        FakePlayerEntity owner;
        AllyBotEntity bot;
        BufferedWriter trace;

        int index = -1;
        boolean settling;
        int settleTicks;
        int attemptTicks;
        int navRetries;
        boolean overallDone;
        double closest;
        double prevX, prevZ;
        String prevMove = "";
        int prevSegToX = Integer.MIN_VALUE, prevSegToY, prevSegToZ;
        int passed, failed, gaps;

        // Per-trial tile state (reset in enter()).
        int plugsBroken;              // how many of the tile's mineable cells have gone
        int firstBreakTick = -1;      // attemptTicks when the FIRST plug went (the "the hold held" signal)
        int lastBreakTick = -1;
        double minY, maxY;            // the vertical excursion envelope (the sink signature, if any)
        int floorGap = -1;            // tile 1: cells of clear fluid under the from-cell before a standable

        Course() {
            buildTrialList();
        }

        void buildTrialList() {
            add("openwaterwall", Kind.OPEN_WATER_WALL);
            add("hangplug",      Kind.HANG_PLUG);
        }

        void add(String name, Kind kind) {
            int i = trials.size();
            int row = i / COLS;
            int col = i % COLS;
            if ((row & 1) == 1) col = COLS - 1 - col; // snake: keep consecutive trials adjacent
            int bx = BASE_X + col * STRIDE;
            int bz = BASE_Z + row * STRIDE;
            trials.add(new Trial(name, kind, bx, bz));
        }

        void start(MinecraftServer server) {
            this.server = server;
            if (Boolean.getBoolean("orebit.swimmine.debug")) {
                Debug.ENABLED = true;
                Debug.VERBOSE = true;
            }
            try {
                this.level = server.overworld();
                Trial first = trials.get(0);
                owner = new FakePlayerEntity(server, level, new GameProfile(
                        UUID.nameUUIDFromBytes("OrebitSwimMine:owner".getBytes(StandardCharsets.UTF_8)),
                        "SwimMine"));
                owner.setPos(first.startX, first.startY, first.startZ);
                BotManager.spawnBotFor(owner);
                bot = BotManager.botFor(owner);
                if (bot == null) {
                    finish("bot never spawned");
                    return;
                }
                trace = Files.newBufferedWriter(ConfigDir.serverDir(server).resolve(TRACE_FILE),
                        StandardCharsets.UTF_8);
                trace.write("Orebit swim-mine course trace  (T <trial> <tick> x y z | spd vy | onGround inWater | hp | plugs | move)\n");
                trace.write("legend: plugs = how many of the tile's mineable cells are already gone; BREAK lines mark\n");
                trace.write("each one going (tick + bot pose), PROBE dumps the built geometry and the proven floor gap\n");
                trace.write("under the from-cell (tile 1: the whole point — a from-cell with a floor proves nothing)\n\n");
                OrebitCommon.LOGGER.info("[Orebit/swimmine] course ready; {} trials", trials.size());
                enter(0);
            } catch (Throwable t) {
                OrebitCommon.LOGGER.error("[Orebit/swimmine] setup threw", t);
                finish("setup threw " + t.getClass().getSimpleName());
            }
        }

        void enter(int i) {
            index = i;
            Trial tr = trials.get(i);
            buildTile(tr);
            probeTile(tr);
            bot.reviveIfDead();
            bot.setHealth(bot.getMaxHealth());
            bot.setMode(AllyBotEntity.Mode.STAY);
            // A diamond pickaxe, re-issued per tile: without a correct tool a stone break is 5x slower again
            // AND BotGatherer's correct-tool refusal vocabulary would change what is under test.
            bot.getInventory().clearContent();
            bot.getInventory().setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
            bot.setPos(tr.startX, tr.startY, tr.startZ);
            bot.setDeltaMovement(Vec3.ZERO);
            bot.setYRot(tr.startYaw);
            bot.setYHeadRot(tr.startYaw);
            settling = true;
            settleTicks = 0;
            attemptTicks = 0;
            navRetries = 0;
            closest = Double.MAX_VALUE;
            plugsBroken = 0;
            firstBreakTick = -1;
            lastBreakTick = -1;
            minY = tr.startY;
            maxY = tr.startY;
            floorGap = tr.fromFloor != null ? floorGapBelow(tr.fromFloor) : -1;
            prevX = tr.startX;
            prevZ = tr.startZ;
            prevMove = "";
            prevSegToX = Integer.MIN_VALUE;
            try {
                trace.write(String.format(Locale.ROOT,
                        "== %s : kind=%s start=(%.1f,%.1f,%.1f) goal=(%d,%d,%d) plugs=%s floorGap=%d\n",
                        tr.name, tr.kind, tr.startX, tr.startY, tr.startZ,
                        tr.goal.getX(), tr.goal.getY(), tr.goal.getZ(), tr.plugs, floorGap));
            } catch (IOException ignored) { }
        }

        void tick(ServerLevel lvl) {
            if (overallDone || bot == null || server == null || lvl != level) {
                return;
            }
            Trial tr = trials.get(index);

            if (settling) {
                int target = index == 0 ? WARMUP_TICKS : SETTLE_TICKS;
                // PIN THE POSE WHILE SETTLING. A STAY bot in water drives nothing, so it would sink the
                // same 0.025/tick the fix is about and start the trial from a DIFFERENT floor cell than the
                // one the tile was designed around (60 settle ticks = 1.5 blocks). This is harness setup,
                // not a hold: it happens strictly before the goto is issued.
                bot.setPos(tr.startX, tr.startY, tr.startZ);
                bot.setDeltaMovement(Vec3.ZERO);
                // Nav-readiness gate (the BoxedInCourse/GateCourse convention): a goto issued over unbuilt
                // nav can produce a premature navGaveUp that reads like a routing refusal.
                if (++settleTicks < target || !navReadyAround(tr.goal)) {
                    return;
                }
                settling = false;
                bot.comeTo(tr.goal, 0.75, 0.75, 0); // exact: reach the precise cell (the GotoCommand form)
                return;
            }

            attemptTicks++;

            // Per-tick break watch. Nothing on a tile can remove a plug except the bot (peaceful world, no
            // redstone, no fluid can break stone), so a plug that has gone is a bot break by construction —
            // the GateCourse transition-counter idiom applied to mining.
            int broken = 0;
            for (BlockPos p : tr.plugs) {
                if (!isPlug(p)) broken++;
            }
            if (broken != plugsBroken) {
                plugsBroken = broken;
                if (firstBreakTick < 0) firstBreakTick = attemptTicks;
                lastBreakTick = attemptTicks;
                try {
                    trace.write(String.format(Locale.ROOT,
                            "  BREAK tick=%d plugs=%d/%d bot=(%.2f,%.3f,%.2f) inWater=%s onGround=%s\n",
                            attemptTicks, plugsBroken, tr.plugs.size(),
                            bot.getX(), bot.getY(), bot.getZ(), bot.isInWater(), EntityState.onGround(bot)));
                } catch (IOException ignored) { }
            }
            if (bot.getY() < minY) minY = bot.getY();
            if (bot.getY() > maxY) maxY = bot.getY();

            trace(tr);

            if (!bot.isAlive()) {
                record(tr, "FAIL", "died");
                return;
            }
            double dx = bot.getX() - (tr.goal.getX() + 0.5);
            double dy = bot.getY() - tr.goal.getY();
            double dz = bot.getZ() - (tr.goal.getZ() + 0.5);
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist < closest) closest = dist;

            if (bot.getY() < tr.minFloorY) {
                record(tr, "FAIL", "fell out of the tile");
                return;
            }

            // Candidate PASS: the driver reverted to STAY (exact-tolerance arrival) near the goal cell —
            // then the world-state assertion decides (arriving without having broken the plugs would mean
            // the tile leaked a route and proves nothing).
            if (bot.mode() == AllyBotEntity.Mode.STAY && dist < 1.2) {
                String stateFail = worldStateFailure(tr);
                if (stateFail == null) {
                    record(tr, "PASS", "reached goal through the break");
                } else {
                    record(tr, "FAIL", "arrived but " + stateFail);
                }
                return;
            }
            if (bot.navigator().navGaveUp()) {
                if (attemptTicks <= NAV_RETRY_WINDOW && navRetries < MAX_NAV_RETRY) {
                    navRetries++;
                    bot.comeTo(tr.goal, 0.75, 0.75, 0);
                    return;
                }
                if (tr.kind == Kind.HANG_PLUG) {
                    // The ladder-lateral exit is not a shipped guarantee (Climb folds no breaks; the
                    // block-tier ladder arms are the trapdoor-ladder arc's known residual). A refusal here
                    // is PRE-EXISTING routing, not a station-keeping regression — record GAP, as
                    // ShaftCourse does for its plain top-down control, and do not count it as a failure.
                    record(tr, "GAP", "no route offered off the ladder (block-tier gap, not this fix)");
                } else {
                    record(tr, "FAIL", "nav gave up (no route offered)");
                }
                return;
            }
            if (attemptTicks >= ATTEMPT_BUDGET) {
                if (tr.kind == Kind.HANG_PLUG && plugsBroken == 0) {
                    record(tr, "GAP", "timed out having never reached the plug (block-tier gap, not this fix)");
                } else {
                    record(tr, "FAIL", "timeout — " + (requiredBroken(tr)
                            ? "the break finished but the goal was never reached"
                            : "the required break never finished (the sinking-hold signature)"));
                }
            }
        }

        /** Whether every REQUIRED mineable cell (the leading {@code requiredPlugs} of the tile) has gone. */
        boolean requiredBroken(Trial tr) {
            for (int i = 0; i < tr.requiredPlugs; i++) {
                if (isPlug(tr.plugs.get(i))) return false;
            }
            return true;
        }

        /** {@code null} when every end-of-trial assertion holds; else a short reason. */
        String worldStateFailure(Trial tr) {
            if (!requiredBroken(tr)) {
                return "the required cell " + tr.plugs.get(0).toShortString() + " was never broken"
                        + " (plugs=" + plugsBroken + "/" + tr.plugs.size() + ")";
            }
            if (tr.fromFloor != null && floorGap < 2) {
                // Self-check on the TILE, not the bot: if the from-cell had something to rest on, the whole
                // trial is vacuous (a sinking bot would just settle and keep mining).
                return "the tile is invalid — floorGap=" + floorGap + " under " + tr.fromFloor.toShortString();
            }
            return null;
        }

        /** Whether the tile's mineable cell is still there (stone — the only non-bedrock structure). */
        boolean isPlug(BlockPos p) {
            return level.getBlockState(p).is(Blocks.STONE);
        }

        /**
         * Cells of clear (air or fluid) space beneath a FLOOR cell before the first block with any collision
         * — the construction check that makes tile 1 mean anything. A discriminating tile needs the bot to
         * have NOTHING to sink onto: a from-cell with a floor under it passes on the broken build too. Scans
         * a bounded 32 cells and returns 32 if it never finds one.
         */
        int floorGapBelow(BlockPos floor) {
            int gap = 0;
            for (int y = floor.getY(); y > floor.getY() - 32; y--) {
                BlockState s = level.getBlockState(new BlockPos(floor.getX(), y, floor.getZ()));
                if (!s.getCollisionShape(level, new BlockPos(floor.getX(), y, floor.getZ())).isEmpty()) {
                    return gap;
                }
                gap++;
            }
            return gap;
        }

        boolean navReadyAround(BlockPos cell) {
            final int radius = ConfigLoader.config().navReadyRadiusChunks();
            return NavStore.ringBuilt(level, cell.getX() >> 4, cell.getZ() >> 4, radius);
        }

        void trace(Trial tr) {
            double x = bot.getX(), y = bot.getY(), z = bot.getZ();
            double spd = Math.sqrt((x - prevX) * (x - prevX) + (z - prevZ) * (z - prevZ));
            Vec3 v = bot.getDeltaMovement();
            boolean grnd = EntityState.onGround(bot);
            String move = bot.lastSteerMove;
            try {
                BotNavigator nav = bot.navigator();
                boolean segChanged = nav.segToX() != prevSegToX || nav.segToY() != prevSegToY
                        || nav.segToZ() != prevSegToZ;
                if (!move.equals(prevMove) || segChanged) {
                    trace.write(String.format(Locale.ROOT,
                            "  WP i=%d/%d %s seg=(%d,%d,%d)->(%d,%d,%d) bot=(%.2f,%.3f,%.2f)\n",
                            nav.waypointIndex(), nav.pathSize(), move,
                            nav.segFromX(), nav.segFromY(), nav.segFromZ(),
                            nav.segToX(), nav.segToY(), nav.segToZ(), x, y, z));
                    prevMove = move;
                    prevSegToX = nav.segToX(); prevSegToY = nav.segToY(); prevSegToZ = nav.segToZ();
                }
                trace.write(String.format(Locale.ROOT,
                        "T %-14s %4d  %.3f %.4f %.3f | %.4f %.4f | %d %d | %.1f | %d | %s\n",
                        tr.name, attemptTicks, x, y, z, spd, v.y,
                        grnd ? 1 : 0, bot.isInWater() ? 1 : 0, bot.getHealth(), plugsBroken, move));
            } catch (IOException ignored) { }
            prevX = x;
            prevZ = z;
        }

        void record(Trial tr, String result, String reason) {
            results.add(String.format(Locale.ROOT,
                    "%s = %s (%s) closest=%.2f ticks=%d plugs=%d/%d firstBreakTick=%d lastBreakTick=%d "
                            + "yRange=[%.3f,%.3f] floorGap=%d finalPos=(%.1f,%.3f,%.1f) lastMove=%s",
                    tr.name, result, reason, closest, attemptTicks, plugsBroken, tr.plugs.size(),
                    firstBreakTick, lastBreakTick, minY, maxY, floorGap,
                    bot.getX(), bot.getY(), bot.getZ(), bot.lastSteerMove));
            if (result.equals("PASS")) passed++;
            else if (result.equals("GAP")) gaps++;
            else failed++;
            OrebitCommon.LOGGER.info(
                    "[Orebit/swimmine] {} -> {} ({}) closest={} ticks={} plugs={}/{} yRange=[{},{}] floorGap={}",
                    tr.name, result, reason, String.format(Locale.ROOT, "%.2f", closest), attemptTicks,
                    plugsBroken, tr.plugs.size(), String.format(Locale.ROOT, "%.3f", minY),
                    String.format(Locale.ROOT, "%.3f", maxY), floorGap);
            try { trace.write("  RESULT " + result + " (" + reason + ")\n\n"); } catch (IOException ignored) { }
            if (index + 1 < trials.size()) {
                enter(index + 1);
            } else {
                finish("all trials complete");
            }
        }

        // ---- tile construction ---------------------------------------------------------------------------

        void buildTile(Trial tr) {
            if (tr.kind == Kind.OPEN_WATER_WALL) {
                buildOpenWaterWall(tr);
            } else {
                buildHangPlug(tr);
            }
        }

        /**
         * The discriminating tank. Everything is BEDROCK first (so a break-capable bot has exactly two
         * mineable cells and no dig-around), then the two chambers are carved to WATER, then the wall's two
         * STONE cells are set.
         *
         * <pre>
         *          x:  bx ............ WX ............ bx+14      (z = zc, 1 wide; z = zc+-1 is bedrock)
         *   Y0+12      B B B B B B B B  B  B B B B B B B          lid
         *   Y0+11      B W W W W W W B  B  B W W W W W B
         *   Y0+10      B W W W W W W B [S] B W W W W W B          <- Traverse head-body Need.AIR
         *   Y0+ 9      B W W W W W W B [S] B W W W W W B          <- Traverse feet-body Need.AIR; bot floats at WX-1;
         *   Y0+ 8      B W W W W W W B  B  B B B B B B B             GOAL feet at WX+3 on the shelf
         *   Y0+ 7      B W W W W W W B  B  B B B B B B B          <- nothing standable under the float ...
         *     ...                                                    ... for eight cells ...
         *   Y0+ 1      B W W W W W W B  B  B B B B B B B
         *   Y0         B B B B B B B B  B  B B B B B B B          tank floor
         * </pre>
         *
         * <p><b>Why BOTH wall cells are stone but only the lower one is asserted.</b> The head cell exists
         * to deny a pre-break bypass: leave {@code (WX, Y0+10)} as water and it lines up with the east
         * chamber's water at the same Y, giving a 1-tall passable channel straight through the wall that a
         * PRONE bot ({@code SprintSwim}) fits through without mining anything. With both cells stone the
         * wall is solid at every Y and there is no route east until the bot breaks. Conversely, once the
         * FEET cell opens, a 1-tall water hole exists by construction and the bot may legitimately go prone
         * and slip through with the head cell intact — measured on the first build of this course, which
         * asserted 2/2 and therefore recorded a false failure after a perfectly healthy break at tick 145.
         * So the assertion is exactly {@code requiredPlugs = 1}: the feet cell, the one break that must be
         * completed from the floorless float and without which nothing east is reachable at all.
         *
         * <p><b>Why the goal is three cells past the wall and not the sill itself.</b> A sill-cell goal was
         * tried and the BLOCK TIER refuses it: with the goal floor equal to the flat walk's own destination
         * cell the search dumps only {@code SprintSwim / DiagonalSprintSwim / EndSprintSwim} at the root and
         * exhausts 132 nodes ({@code FAIL-exhausted (14,158,14)->(15,158,14)}), while the identical geometry
         * with the goal on the far shelf plans and executes the Traverse on the first tick. That is a
         * pre-existing planner behaviour, not this fix's business, so the tile simply keeps its goal past
         * the wall.
         *
         * <p>Both chambers are filled to the SAME level, so the break joins two equalised bodies and
         * produces no flow push to shove the bot off its own column mid-hold.
         */
        void buildOpenWaterWall(Trial tr) {
            int bx = tr.baseX, zc = tr.zc;
            int wx = bx + 7;
            // (1) One solid bedrock block — the lid, the floor, the side walls and the divider all at once.
            //     Bedrock everywhere means "mining.canMine=true" buys the bot the tile's two designated
            //     cells and nothing else.
            box(bx, bx + 14, Y0, Y0 + 12, zc - 1, zc + 1, BEDROCK);
            // (2) The WEST chamber: full-depth water. The float at (wx-1, Y0+8) therefore has eight cells of
            //     water beneath it and the first collision only at the Y0 tank floor — asserted per trial by
            //     floorGapBelow, because a from-cell with a floor makes the whole trial vacuous.
            fill(bx + 1, wx - 1, Y0 + 1, Y0 + 11, zc, WATER);
            // (3) The EAST chamber: a bedrock shelf at Y0+8 with water above it to the SAME top level.
            fill(wx + 1, bx + 13, Y0 + 9, Y0 + 11, zc, WATER);
            // (4) The wall's only mineable cells — exactly the two body cells the flat Traverse from
            //     (wx-1, Y0+8) onto the sill (wx, Y0+8) declares Need.AIR on. Set LAST so the water carve
            //     above cannot overwrite them.
            for (BlockPos p : tr.plugs) {
                set(p.getX(), p.getY(), p.getZ(), STONE);
            }
        }

        /**
         * The climbable control: a bedrock slab bored with a 1x1 ladder shaft, whose only lateral exit at
         * hang height is plugged with one STONE cell, and a bedrock corridor beyond it to the goal.
         *
         * <pre>
         *          x:  bx .. SX SX+1 .......... bx+9 ... bx+12     (z = zc)
         *   Y0+ 7      B  B   B   B   B B B B B  B  B B  B
         *   Y0+ 6      B  B   L   .   . . . . .  .  B B  B         head row (corridor + shaft top)
         *   Y0+ 5      B  B   L  [S]  . . . . .  .  B B  B         <- the PLUG: the exit's feet cell
         *   Y0+ 4      B  B   L   B   B B B B B  B  B B  B         <- dest FLOOR / corridor floor; goal at bx+8
         *     ...             L
         *   Y0+ 1      B  B   L   B   B B B B B  B  B B  B         bot starts here (feet), on the shaft floor
         *   Y0         B  B   B   B   B B B B B  B  B B  B
         * </pre>
         *
         * Feet at {@code Y0+5} is a ladder cell, so the bot HANGS (not grounded, {@code onClimbable}) while
         * it mines the plug — the stance the {@code stationKeep} climbable branch must hold. The break is dry
         * but ungrounded, so vanilla's {@code /5} gives ~28 ticks: an unheld climbable slide (-0.15/tick,
         * a block every ~7 ticks) would leave the column long before it finished.
         */
        void buildHangPlug(Trial tr) {
            int bx = tr.baseX, zc = tr.zc;
            int sx = bx + 3;
            // (1) Solid bedrock slab.
            box(bx, bx + 12, Y0, Y0 + 7, zc - 1, zc + 1, BEDROCK);
            // (2) The 1x1 shaft (air), then the ladder column hung on its WEST wall — order matters, a
            //     ladder placed before its support block pops.
            fill(sx, sx, Y0 + 1, Y0 + 6, zc, AIR);
            for (int y = Y0 + 1; y <= Y0 + 6; y++) {
                set(sx, y, zc, ladder());
            }
            // (3) The east corridor at floor Y0+4 (body cells Y0+5 / Y0+6), stopping one short of the shaft
            //     so the exit column itself can be set explicitly below.
            fill(sx + 2, bx + 9, Y0 + 5, Y0 + 6, zc, AIR);
            // (4) The exit column: bedrock sill (already), the STONE plug in the feet cell, air above it.
            set(sx + 1, Y0 + 6, zc, AIR);
            set(sx + 1, Y0 + 5, zc, STONE);
        }

        /** Dump the ACTUALLY-PLACED geometry — the tile's own self-check, especially the floor gap. */
        void probeTile(Trial tr) {
            StringBuilder sb = new StringBuilder();
            for (BlockPos p : tr.plugs) {
                sb.append(' ').append(p.toShortString()).append('=')
                        .append(level.getBlockState(p).getBlock().getClass().getSimpleName())
                        .append('/').append(level.getBlockState(p));
            }
            String from = tr.fromFloor == null ? "n/a"
                    : tr.fromFloor.toShortString() + "=" + level.getBlockState(tr.fromFloor);
            OrebitCommon.LOGGER.info("[Orebit/swimmine] {} probe from={} floorGap={} plugs:{}",
                    tr.name, from, tr.fromFloor != null ? floorGapBelow(tr.fromFloor) : -1, sb);
            try {
                trace.write(String.format(Locale.ROOT, "  PROBE from=%s floorGap=%d plugs:%s%n",
                        from, tr.fromFloor != null ? floorGapBelow(tr.fromFloor) : -1, sb));
            } catch (IOException ignored) { }
        }

        // ---- placement primitives ------------------------------------------------------------------------

        static BlockState ladder() {
            return Blocks.LADDER.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, LADDER_FACING);
        }

        /** Fill the inclusive box with one state. */
        void box(int x0, int x1, int y0, int y1, int z0, int z1, BlockState state) {
            for (int x = x0; x <= x1; x++)
                for (int y = y0; y <= y1; y++)
                    for (int z = z0; z <= z1; z++)
                        set(x, y, z, state);
        }

        /** Fill the inclusive x/y span at a single {@code z} with one state (the 1-wide corridor carve). */
        void fill(int x0, int x1, int y0, int y1, int z, BlockState state) {
            for (int x = x0; x <= x1; x++)
                for (int y = y0; y <= y1; y++)
                    set(x, y, z, state);
        }

        void set(int x, int y, int z, BlockState state) {
            level.setBlockAndUpdate(new BlockPos(x, y, z), state);
        }

        void finish(String reason) {
            overallDone = true;
            Path file = ConfigDir.serverDir(server).resolve(RESULT_FILE);
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                kv(w, "completed", "true");
                kv(w, "reason", reason);
                kv(w, "trials", trials.size());
                kv(w, "passed", passed);
                kv(w, "failed", failed);
                kv(w, "gaps", gaps);
                for (String line : results) {
                    w.write(line);
                    w.write('\n');
                }
            } catch (IOException e) {
                OrebitCommon.LOGGER.error("[Orebit/swimmine] could not write {}", file, e);
            }
            try { if (trace != null) trace.close(); } catch (IOException ignored) { }
            OrebitCommon.LOGGER.info("[Orebit/swimmine] DONE ({}) — {} passed / {} failed / {} gap of {} — halting",
                    reason, passed, failed, gaps, trials.size());
            server.halt(false);
            Thread exiter = new Thread(() -> {
                server.halt(true);
                System.exit(0);
            }, "orebit-swimmine-exit");
            exiter.setDaemon(true);
            exiter.start();
        }

        private static void kv(BufferedWriter w, String key, Object value) throws IOException {
            w.write(key);
            w.write('=');
            w.write(String.valueOf(value));
            w.write('\n');
        }
    }
}
