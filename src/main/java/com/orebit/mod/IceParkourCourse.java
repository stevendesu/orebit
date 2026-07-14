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
import com.orebit.mod.platform.ConfigDir;
import com.orebit.mod.platform.EntityState;
import com.orebit.mod.platform.PlatformEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Headless ICE-PARKOUR diagnostic harness (a sibling of {@link ParkourCourse} / {@link IceCourse}, armed by
 * its own {@code -Dorebit.iceparkour} flag). It reproduces the MOMENTUM-OVERSHOOT-ONTO-ICE pathology: the bot
 * sprint-parkours onto a landing block that is ICE / BLUE_ICE, cannot brake its carried horizontal momentum on
 * the near-frictionless surface, and slides off the FAR end of the landing.
 *
 * <p><b>This is EVIDENCE GATHERING, not a fix.</b> The harness bakes in NO behaviour change and asserts NO
 * mechanism; it only reproduces and records, exactly like its siblings. The follower-brake PROTOTYPES under
 * test are gated by {@code -Dorebit.iceparkour.brake=<servo|brake>} read inside the follower and are reverted
 * after measurement.
 *
 * <p><b>Two templates.</b>
 * <ul>
 *   <li><b>TURN</b> — a 1-wide ice landing whose route then turns 90&deg; (+Z) to a stone goal walkway. The
 *       +X (jump-axis) continuation past the landing is VOID for {@code runout} cells then a drop, so a bot
 *       that overshoots the landing along the jump axis falls (an unambiguous FAIL). This is the "slides off
 *       the far end of the landing block" repro: the landing is where the bot must stop/redirect. The +Z
 *       redirect actively fights the +X momentum, so the min-runout it needs is the FAVOURABLE case.</li>
 *   <li><b>STRAIGHT</b> — the route continues +X: ice landing, then {@code runout} ice cells, goal on the LAST
 *       ice cell with VOID beyond it. The bot drives to the goal and must STOP there (no redirect to bleed the
 *       momentum). This is the pure travel-direction ICE COAST: min-runout here is the WORST case, the true
 *       stopping distance.</li>
 * </ul>
 *
 * <p><b>Measured per trial</b> (from the per-tick trajectory dump): the TAKEOFF speed (position-delta on the
 * ground&rarr;air flip), the LANDING speed (position-delta on the air&rarr;ground flip = the momentum arriving
 * on the ice), the PEAK OVERSHOOT (max along-jump-axis distance the bot's centre travels PAST the landing-cell
 * centre while still at landing height — the slide distance), and the PASS/FAIL verdict.
 *
 * <p><b>Config (scripts/iceparkour/orebit.properties).</b> {@code survival.takesDamage=true} (a fall kills &rarr;
 * FAIL), {@code placement.canPlace=false} + {@code mining.canMine=false} (the ONLY way across the gap is a jump;
 * the bot can't bridge), {@code pathing.async=false} (deterministic). Sprint is always available (hunger off).
 *
 * <p><b>Inert in production</b> — {@link #register} returns immediately unless {@code -Dorebit.iceparkour} is
 * set. Common, version-portable source (every MC surface it touches is range-stable).
 */
public final class IceParkourCourse {

    private IceParkourCourse() {}

    private static final String RESULT_FILE = "orebit-iceparkour-result.properties";
    private static final String TRACE_FILE = "orebit-iceparkour-trace.txt";

    /** Floor-cell Y of the takeoff platform (feet at {@code Y0+1}); high enough that a miss is fatal. */
    private static final int Y0 = 150;
    private static final int BASE_X = 8;
    private static final int BASE_Z = 8;
    private static final int COLS = 5;
    private static final int STRIDE = 28; // grid cell size (> the longest tile span so tiles never touch)
    /** Runway length in cells (the takeoff cell is the last); long enough to reach sprint terminal. */
    private static final int RUN = 7;
    /** Perpendicular (+Z) walkway length from a TURN landing to the goal (overshoot along +X falls off). */
    private static final int WALK = 5;

    private static final int WARMUP_TICKS = 140;
    private static final int SETTLE_TICKS = 45;
    private static final int NAV_RETRY_WINDOW = 45;
    private static final int MAX_NAV_RETRY = 5;
    private static final int ATTEMPT_BUDGET = 500;

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState ICE = Blocks.ICE.defaultBlockState();            // slip 0.98
    private static final BlockState BLUE_ICE = Blocks.BLUE_ICE.defaultBlockState();  // slip 0.989 (worst)

    public static void register(PlatformEvents events) {
        if (System.getProperty("orebit.iceparkour") == null) {
            return;
        }
        Course course = new Course();
        events.onServerStarted(course::start);
        events.onWorldTickEnd(course::tick);
        OrebitCommon.LOGGER.info("[Orebit/iceparkour] armed: {} trials", course.trials.size());
    }

    private enum Surface { STONE, ICE, BLUE_ICE }
    private enum Template {
        TURN,     // 1-wide ice landing, route turns +Z; +X overshoot past the landing falls (after runout).
                  //   The +Z waypoint is adopted while airborne, so the redirect ACTIVELY countersteers +X.
        STOP,     // the goal IS the ice landing cell: driveToward declares arrival (within arriveDist) and
                  //   ZEROES the forward input while the bot is still coasting in, so the bot slides on the
                  //   ice with NO braking input — the "the ice landing is (near) the destination" repro.
                  //   NOTE: arrival fires ~2.5 b early (mid-jump), so the sprint is CUT before touchdown →
                  //   the flat landing arrives SLOW. Use SHEET to preserve sprint onto the ice.
        SHEET,    // the landing is the FIRST cell of a `runout`-long 1-wide ice strip; the goal is the LAST
                  //   strip cell, with a lethal drop immediately past it. Because the goal is `runout` cells
                  //   beyond the landing, the bot HOLDS sprint/forward THROUGH the flat jump (the landing is
                  //   an intermediate waypoint) and cruises the ice at sprint speed, then must brake before
                  //   the end. This is the "sprint onto ice, slide off the far end" repro (the owner's case).
        CHAIN     // 1-wide ice landing that is the TAKEOFF for a SECOND parkour across another `gap`: the bot
                  //   lands on the ice already sprinting and RUNUPs the next jump — momentum is preserved
                  //   across the ice. Does it hold the 1-wide ice cell for the next takeoff, or slide into the
                  //   second gap? Goal is beyond the second (stone) landing.
    }

    private static final class Trial {
        final String name;
        final int gap;              // empty gap cells (flat sprint jump: gap 1/2/3)
        final int jdy;              // vertical of the jump: 0 flat, -1..-3 falling (more airtime → faster arrival)
        final Surface surface;
        final int runout;           // ice cells in +X past the landing before the void
        final Template template;
        final int baseX, baseZ, zc;

        final int takeoffX, takeoffZ;
        final int landX, landZ, landY;
        final int landedFeetY;
        final double startX, startZ;
        final float startYaw;
        final BlockPos goal;
        final double landCenterProj; // along-jump-axis projection (from takeoff centre) of the landing centre
        String plannerGap;           // != null: a KNOWN PLANNER GAP — a slide-off scores FAIL with this reason,
                                     // counted apart from real pass/fail (mirrors ParkourCourse's PLANNER-GAP)

        Trial(String name, int gap, int jdy, Surface surface, int runout, Template template, int baseX, int baseZ) {
            this.name = name;
            this.gap = gap;
            this.jdy = jdy;
            this.surface = surface;
            this.runout = runout;
            this.template = template;
            this.baseX = baseX;
            this.baseZ = baseZ;
            this.zc = baseZ;
            this.takeoffX = baseX + (RUN - 1);
            this.takeoffZ = zc;
            this.landX = takeoffX + gap + 1;
            this.landZ = zc;
            this.landY = Y0 + jdy;
            this.landedFeetY = landY + 1;
            this.startX = baseX + 0.5;
            this.startZ = zc + 0.5;
            this.startYaw = (float) Math.toDegrees(Math.atan2(-1, 0)); // face +X
            if (template == Template.TURN) {
                this.goal = new BlockPos(landX, landY + 1, landZ + WALK); // +Z walkway end
            } else if (template == Template.SHEET) {
                this.goal = new BlockPos(landX + runout, landY + 1, landZ); // far end of the ice strip
            } else if (template == Template.CHAIN) {
                // second parkour: landing2 (stone) at landX + gap + 1, goal 2 past it.
                this.goal = new BlockPos(landX + gap + 1 + 2, landY + 1, landZ);
            } else { // STOP
                this.goal = new BlockPos(landX, landY + 1, landZ); // the ice landing cell itself
            }
            this.landCenterProj = (landX + 0.5) - (takeoffX + 0.5); // = gap + 1
        }

        /** Along +X projection of a bot position, measured from the TAKEOFF cell centre. */
        double projX(double x) {
            return x - (takeoffX + 0.5);
        }
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
        boolean leftTakeoff;
        boolean landedOnce;
        int settleConfirm;          // consecutive grounded-and-nearly-stopped ticks (STOP template PASS)
        double takeoffSpeed = -1;
        double landingSpeed = -1;
        double peakOvershoot = -1e9; // max (projX - landCenterProj) seen while at landing height
        boolean wasGrounded = true;
        double prevX, prevZ;
        String prevMove = "";
        int passed, failed, plannerGap;   // plannerGap = intended RED reminders, counted apart from real fails

        Course() {
            buildTrialList();
        }

        void buildTrialList() {
            // ==== ROUND 2: sprint verification + sprint-PRESERVED flat-onto-ice geometries ==================
            // Priority 1 — sprint verification. STOP kills sprint mid-jump (arrival zeroes forward), so the
            // flat landing there arrives SLOW. SHEET keeps the goal `runout` cells PAST the landing, so the bot
            // HOLDS sprint through the flat jump and cruises the ice at sprint speed → the honest landing speed.
            // The trace now logs vx + the sprint flag through RUNUP/TAKEOFF/AIRBORNE/touchdown.
            //
            // ---- FLAT parkour onto a 1-wide ice SHEET, goal at the strip's far end, lethal drop past it.
            //      This is the owner's "sprint onto ice, slide off the far end" case. Sweep strip length.
            add("ctl.sheet.g3.s2", 3, 0, Surface.STONE, 2, Template.SHEET);   // control (stone strip)
            add("ctl.sheet.g3.s4", 3, 0, Surface.STONE, 4, Template.SHEET);
            add("ice.sheet.g3.s2", 3, 0, Surface.ICE, 2, Template.SHEET);
            add("ice.sheet.g3.s3", 3, 0, Surface.ICE, 3, Template.SHEET);
            add("ice.sheet.g3.s4", 3, 0, Surface.ICE, 4, Template.SHEET);
            add("ice.sheet.g3.s6", 3, 0, Surface.ICE, 6, Template.SHEET);
            add("ice.sheet.g3.s8", 3, 0, Surface.ICE, 8, Template.SHEET);
            add("blue.sheet.g3.s4",3, 0, Surface.BLUE_ICE, 4, Template.SHEET);
            add("blue.sheet.g3.s8",3, 0, Surface.BLUE_ICE, 8, Template.SHEET);
            add("ice.sheet.g2.s4", 2, 0, Surface.ICE, 4, Template.SHEET);     // gap-2 (walk-jump) for contrast
            add("ice.sheet.g2.s8", 2, 0, Surface.ICE, 8, Template.SHEET);
            // ---- CHAINED momentum: flat parkour onto a 1-wide ice cell that is the TAKEOFF for a SECOND
            //      parkour. The bot lands sprinting and RUNUPs the next jump — momentum preserved across ice.
            add("ice.chain.g3",   3, 0, Surface.ICE, 0, Template.CHAIN);
            add("ice.chain.g2",   2, 0, Surface.ICE, 0, Template.CHAIN);
            add("blue.chain.g3",  3, 0, Surface.BLUE_ICE, 0, Template.CHAIN);
            // ---- Reference STOP + TURN (round-1 anchors, sprint CUT / redirect) for the vx comparison.
            add("ice.stop.g3",    3, 0, Surface.ICE, 0, Template.STOP);
            add("ice.stop.g2",    2, 0, Surface.ICE, 0, Template.STOP);       // (gap-2 STOP = arrival-artifact)
            add("ice.turn.g3",    3, 0, Surface.ICE, 0, Template.TURN);
            // ==== PHASE 3: FALLING-onto-ice — the owner's ORIGINAL #1 pathology ==============================
            // "A 4-gap parkour jump descending-1, landing on BLUE ICE, has too much momentum to bleed off and
            // slips off." FLAT/RISING got the predictive airborne servo in Phase 2; FALLING was left on the old
            // open-loop Fall drop-control handoff, and no falling-onto-ice trial existed — so this pathology was
            // UNTESTED. STOP template = 1-wide landing (runout=0), goal ON it, lethal drop past → a slip FALLS.
            // The STONE control is the geometry discriminator: if it settles but blue ice slips, it's a
            // surface/servo problem, not the jump geometry. gap=4 descending-1 is the exact reported case; gap=3
            // is the cheaper shorter variant; ICE (0.98) alongside BLUE_ICE (0.989) brackets the friction.
            add("ctl.fall.g4",  4, -1, Surface.STONE,    0, Template.STOP);   // geometry control (stone)
            add("blue.fall.g4", 4, -1, Surface.BLUE_ICE, 0, Template.STOP);   // THE reported pathology (runout 0)
            add("blue.fall.g3", 3, -1, Surface.BLUE_ICE, 0, Template.STOP);   // shorter descending variant
            add("ice.fall.g4",  4, -1, Surface.ICE,      0, Template.STOP);   // plain-ice bracket
            // Runout sweep on the g4/blue-ice case: the 1-wide runout=0 landing is the EXTREME (a 4-gap's reach
            // momentum can't be fully bled on frictionless ice inside one cell — a reach-vs-brake conflict). Any
            // runout gives the servo/friction room to arrest, so these settle — showing the fix works whenever
            // the landing isn't the pathological 1-cell extreme (and quantifying how much runout g4 needs).
            add("blue.fall.g4.r1", 4, -1, Surface.BLUE_ICE, 1, Template.STOP);
            add("blue.fall.g4.r2", 4, -1, Surface.BLUE_ICE, 2, Template.STOP);
            // The runout-0 g4-onto-ice cases are the MEASURED physical extreme: the servo brakes a 4-gap fall to
            // its floor (~0.16 b/t) but a 4-gap's reach momentum can't be fully bled on frictionless ice inside a
            // single 1-wide cell (it falls ~0.09 b short). Any runout ≥ 1 settles (the .r1/.r2 trials above). So
            // these are KNOWN PLANNER GAPS (score FAIL with a PLANNER-GAP: reason, counted apart from real
            // pass/fail) — RED reminders that the planner should not offer a falling-4 onto a 1-wide zero-runout
            // ice landing; they become expected-refusal PASSes once the planner arc stops offering them.
            markPlannerGap("PLANNER-GAP: falling-4 onto 1-wide zero-runout ice", "blue.fall.g4", "ice.fall.g4");
        }

        /** Mark the named trials (already added) as KNOWN PLANNER GAPS: a slide-off scores FAIL with {@code
         *  reason}, counted separately from real pass/fail (see {@link Trial#plannerGap}). */
        void markPlannerGap(String reason, String... names) {
            for (String n : names) {
                for (Trial t : trials) {
                    if (t.name.equals(n)) { t.plannerGap = reason; break; }
                }
            }
        }

        void add(String name, int gap, int jdy, Surface surface, int runout, Template t) {
            int i = trials.size();
            int row = i / COLS;
            int col = i % COLS;
            if ((row & 1) == 1) col = COLS - 1 - col; // snake
            int bx = BASE_X + col * STRIDE;
            int bz = BASE_Z + row * STRIDE;
            trials.add(new Trial(name, gap, jdy, surface, runout, t, bx, bz));
        }

        void start(MinecraftServer server) {
            this.server = server;
            if (Boolean.getBoolean("orebit.iceparkour.debug")) {
                Debug.ENABLED = true;
                Debug.VERBOSE = true;
            }
            try {
                this.level = server.overworld();
                Trial first = trials.get(0);
                owner = new FakePlayerEntity(server, level, new GameProfile(
                        UUID.nameUUIDFromBytes("OrebitIceParkour:owner".getBytes(StandardCharsets.UTF_8)),
                        "IcePk"));
                owner.setPos(first.startX, Y0 + 1, first.startZ);
                BotManager.spawnBotFor(owner);
                bot = BotManager.botFor(owner);
                if (bot == null) {
                    finish("bot never spawned");
                    return;
                }
                trace = Files.newBufferedWriter(ConfigDir.serverDir(server).resolve(TRACE_FILE),
                        StandardCharsets.UTF_8);
                trace.write("Orebit ice-parkour trace  (T <trial> <tick> x y z | vx vz vy | spd | g=onGround spr=sprinting | move)\n");
                trace.write("legend: vx/vz/vy = getDeltaMovement (b/t, vx is the +X jump-axis velocity); spd = "
                        + "position-delta horizontal speed; spr = isSprinting; TAKEOFF/LAND mark ground<->air flips\n\n");
                OrebitCommon.LOGGER.info("[Orebit/iceparkour] course ready; {} trials", trials.size());
                enter(0);
            } catch (Throwable t) {
                OrebitCommon.LOGGER.error("[Orebit/iceparkour] setup threw", t);
                finish("setup threw " + t.getClass().getSimpleName());
            }
        }

        void enter(int i) {
            index = i;
            Trial tr = trials.get(i);
            buildTile(tr);
            bot.reviveIfDead();
            bot.setHealth(bot.getMaxHealth());
            bot.setMode(AllyBotEntity.Mode.STAY);
            bot.setPos(tr.startX, Y0 + 1, tr.startZ);
            bot.setDeltaMovement(Vec3.ZERO);
            bot.setYRot(tr.startYaw);
            bot.setYHeadRot(tr.startYaw);
            settling = true;
            settleTicks = 0;
            attemptTicks = 0;
            navRetries = 0;
            leftTakeoff = false;
            landedOnce = false;
            settleConfirm = 0;
            takeoffSpeed = -1;
            landingSpeed = -1;
            peakOvershoot = -1e9;
            wasGrounded = true;
            prevX = tr.startX;
            prevZ = tr.startZ;
            prevMove = "";
            try {
                trace.write(String.format(Locale.ROOT,
                        "== %s : gap=%d surface=%s runout=%d %s takeoff=(%d,%d,%d) land=(%d,%d,%d) goal=(%d,%d,%d)\n",
                        tr.name, tr.gap, tr.surface, tr.runout, tr.template,
                        tr.takeoffX, Y0, tr.takeoffZ, tr.landX, tr.landY, tr.landZ,
                        tr.goal.getX(), tr.goal.getY() - 1, tr.goal.getZ()));
            } catch (IOException ignored) { }
        }

        void tick(ServerLevel lvl) {
            if (overallDone || bot == null || server == null || lvl != level) {
                return;
            }
            Trial tr = trials.get(index);

            if (settling) {
                int target = index == 0 ? WARMUP_TICKS : SETTLE_TICKS;
                if (++settleTicks < target) return;
                settling = false;
                bot.comeTo(tr.goal);
                return;
            }

            attemptTicks++;
            trace(tr);

            if (!bot.isAlive()) {
                record(tr, "FAIL", "died");
                return;
            }
            double projx = tr.projX(bot.getX());
            if (projx > 0.6) leftTakeoff = true;
            double hspd = Math.sqrt(bot.getDeltaMovement().x * bot.getDeltaMovement().x
                    + bot.getDeltaMovement().z * bot.getDeltaMovement().z);
            // Track peak overshoot past the landing centre WHILE at (or near) landing height — i.e. before a
            // fall. Positive = slid past the landing-cell centre toward the far edge.
            if (landedOnce && bot.getY() > tr.landedFeetY - 1.0) {
                double overshoot = projx - tr.landCenterProj;
                if (overshoot > peakOvershoot) peakOvershoot = overshoot;
            }
            // FAIL: fell off (below the landing floor by > 1.6) after leaving the takeoff — THE pathology signal.
            // On a KNOWN-PLANNER-GAP trial the slide-off is the intended RED reminder (record() stamps it with
            // the PLANNER-GAP: reason + counts it apart from real fails); else it's a real follower FAIL.
            if (leftTakeoff && bot.getY() < tr.landedFeetY - 1.6) {
                record(tr, "FAIL", "fell (slid off landing)");
                return;
            }
            if (tr.template == Template.TURN || tr.template == Template.CHAIN) {
                // Goal is a DISTINCT cell past the ice (a +Z walkway, or beyond a second jump), so the driver
                // reverts to STAY only on TRUE arrival there — a clean reached-goal PASS.
                if (bot.mode() == AllyBotEntity.Mode.STAY && bot.getY() > tr.landedFeetY - 1.5) {
                    record(tr, "PASS", "reached goal");
                    return;
                }
            } else {
                // STOP / SHEET: the goal is on the ice (the landing itself, or the strip's far end), so mode
                // flips to STAY on arrival while still coasting in. PASS only once the bot has actually SETTLED
                // — grounded at landing height and nearly stopped for a sustained window (not fallen).
                if (landedOnce && EntityState.onGround(bot) && bot.getY() > tr.landedFeetY - 0.5 && hspd < 0.03) {
                    if (++settleConfirm >= 12) {
                        record(tr, "PASS", "settled");
                        return;
                    }
                } else {
                    settleConfirm = 0;
                }
            }
            if (bot.navigator().navGaveUp()) {
                if (attemptTicks <= NAV_RETRY_WINDOW && navRetries < MAX_NAV_RETRY) {
                    navRetries++;
                    bot.comeTo(tr.goal);
                    return;
                }
                record(tr, "FAIL", "nav gave up (no route offered)");
                return;
            }
            if (attemptTicks >= ATTEMPT_BUDGET) {
                // A STOP trial that neither fell nor settled but is still standing on valid ground = a slow
                // settle / gentle oscillation; report PASS-timeout so it isn't confused with a fall.
                if ((tr.template == Template.STOP || tr.template == Template.SHEET) && landedOnce
                        && EntityState.onGround(bot) && bot.getY() > tr.landedFeetY - 0.5) {
                    record(tr, "PASS", "on-landing at timeout (slow settle)");
                } else {
                    record(tr, "FAIL", "timeout");
                }
            }
        }

        void trace(Trial tr) {
            double x = bot.getX(), z = bot.getZ();
            double spd = Math.sqrt((x - prevX) * (x - prevX) + (z - prevZ) * (z - prevZ));
            Vec3 v = bot.getDeltaMovement();
            boolean onGround = EntityState.onGround(bot);
            String move = bot.lastSteerMove;
            try {
                if (!move.equals(prevMove)) {
                    BotNavigator nav = bot.navigator();
                    trace.write(String.format(Locale.ROOT, "  MOVE %s seg=(%d,%d,%d)->(%d,%d,%d)\n", move,
                            nav.segFromX(), nav.segFromY(), nav.segFromZ(),
                            nav.segToX(), nav.segToY(), nav.segToZ()));
                    prevMove = move;
                }
                boolean sprint = bot.isSprinting();
                if (wasGrounded && !onGround) {
                    takeoffSpeed = spd;
                    trace.write(String.format(Locale.ROOT, "  TAKEOFF vx=%.4f spd=%.4f spr=%d at x=%.3f (projX=%.3f)\n",
                            v.x, spd, sprint ? 1 : 0, x, tr.projX(x)));
                }
                if (!wasGrounded && onGround) {
                    // Air->ground flip. The FIRST such flip at/above landing height is the touchdown.
                    if (!landedOnce && bot.getY() > tr.landedFeetY - 1.0) {
                        landedOnce = true;
                        landingSpeed = spd;
                        trace.write(String.format(Locale.ROOT, "  LAND vx=%.4f spd=%.4f spr=%d at x=%.3f (projX=%.3f overshoot=%.3f)\n",
                                v.x, spd, sprint ? 1 : 0, x, tr.projX(x), tr.projX(x) - tr.landCenterProj));
                    }
                }
                trace.write(String.format(Locale.ROOT,
                        "T %-16s %3d  %.3f %.3f %.3f | %.4f %.4f %.4f | %.4f | g=%d spr=%d | %s\n",
                        tr.name, attemptTicks, x, bot.getY(), z, v.x, v.z, v.y, spd,
                        onGround ? 1 : 0, sprint ? 1 : 0, move));
            } catch (IOException ignored) { }
            wasGrounded = onGround;
            prevX = x;
            prevZ = z;
        }

        void record(Trial tr, String result, String reason) {
            // KNOWN-PLANNER-GAP: a FAIL is an INTENDED RED reminder — stamp the PLANNER-GAP: reason and count it
            // apart from real pass/fail (finish() reports the three buckets). A PASS (unexpected clear) is real.
            boolean gapFail = result.equals("FAIL") && tr.plannerGap != null;
            if (gapFail) reason = tr.plannerGap + " (" + reason + ")";
            String pk = peakOvershoot <= -1e8 ? "n/a" : String.format(Locale.ROOT, "%.3f", peakOvershoot);
            String tos = takeoffSpeed < 0 ? "n/a" : String.format(Locale.ROOT, "%.4f", takeoffSpeed);
            String las = landingSpeed < 0 ? "n/a" : String.format(Locale.ROOT, "%.4f", landingSpeed);
            results.add(String.format(Locale.ROOT,
                    "%s = %s (%s) gap=%d surface=%s runout=%d tmpl=%s takeoffSpd=%s landSpd=%s peakOvershoot=%s finalX=%.2f finalY=%.2f",
                    tr.name, result, reason, tr.gap, tr.surface, tr.runout, tr.template,
                    tos, las, pk, bot.getX(), bot.getY()));
            if (result.equals("PASS")) passed++; else if (gapFail) plannerGap++; else failed++;
            OrebitCommon.LOGGER.info("[Orebit/iceparkour] {} -> {} ({}) landSpd={} peakOvershoot={} finalY={}",
                    tr.name, result, reason, las, pk, String.format(Locale.ROOT, "%.2f", bot.getY()));
            try { trace.write("  RESULT " + result + " (" + reason + ") landSpd=" + las
                    + " peakOvershoot=" + pk + "\n\n"); } catch (IOException ignored) { }
            if (index + 1 < trials.size()) {
                enter(index + 1);
            } else {
                finish("all trials complete");
            }
        }

        // ---- tile construction ---------------------------------------------------------------------------

        BlockState surfaceState(Trial tr) {
            switch (tr.surface) {
                case ICE: return ICE;
                case BLUE_ICE: return BLUE_ICE;
                default: return STONE;
            }
        }

        void buildTile(Trial tr) {
            BlockState land = surfaceState(tr);
            // Runway: 3-wide stone at Y0 (so the bot centres and sprints up cleanly), z = zc. The takeoff cell
            // is the last runway cell; the `gap` cells in +X at Y0 stay VOID so the only crossing is a jump (a
            // FALLING jump also needs those Y0 cells open to fall through to the lower landing).
            for (int k = 0; k < RUN; k++) {
                int cx = tr.baseX + k;
                set(cx, Y0, tr.zc, STONE);
                set(cx, Y0, tr.zc - 1, STONE);
                set(cx, Y0, tr.zc + 1, STONE);
            }
            // Landing cell (1-wide in X, at landX, at landY = Y0+jdy) — the ice (or stone control).
            set(tr.landX, tr.landY, tr.landZ, land);
            // Run-out: `runout` more cells of the SAME surface in +X at the landing level (overshoot absorber).
            for (int r = 1; r <= tr.runout; r++) {
                set(tr.landX + r, tr.landY, tr.landZ, land);
            }
            if (tr.template == Template.TURN) {
                // Perpendicular +Z stone walkway to the goal (safe: the bot can stop on it). 1-wide.
                for (int k = 1; k <= WALK; k++) {
                    set(tr.landX, tr.landY, tr.landZ + k, STONE);
                }
            } else if (tr.template == Template.CHAIN) {
                // Second parkour: a SECOND `gap`-cell void past the 1-wide ice landing, then a 3-wide STONE
                // landing2 + goal pad. The bot lands on the ice STILL SPRINTING and must RUNUP+take off the
                // next jump from the 1-wide ice cell; if it slides off before taking off it falls in gap2.
                int l2x = tr.landX + tr.gap + 1; // second landing (stone)
                for (int k = 0; k <= 3; k++) {   // landing2 + a short goal run, 3-wide stone
                    set(l2x + k, tr.landY, tr.landZ, STONE);
                    set(l2x + k, tr.landY, tr.landZ - 1, STONE);
                    set(l2x + k, tr.landY, tr.landZ + 1, STONE);
                }
            }
            // STOP: goal is the landing cell (void past the run-out = the far drop).
            // SHEET: goal is landX+runout (the run-out cells built above ARE the ice strip); void past it = the drop.
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
                kv(w, "brakeMode", String.valueOf(System.getProperty("orebit.iceparkour.brake", "none")));
                kv(w, "trials", trials.size());
                kv(w, "passed", passed);
                kv(w, "failed", failed);                 // REAL follower failures (must be 0)
                kv(w, "knownPlannerGap", plannerGap);    // intended RED reminders, NOT follower regressions
                for (String line : results) {
                    w.write(line);
                    w.write('\n');
                }
            } catch (IOException e) {
                OrebitCommon.LOGGER.error("[Orebit/iceparkour] could not write {}", file, e);
            }
            try { if (trace != null) trace.close(); } catch (IOException ignored) { }
            OrebitCommon.LOGGER.info("[Orebit/iceparkour] DONE ({}) — {} passed / {} real-failed / {} known-planner-gap "
                    + "of {} — halting", reason, passed, failed, plannerGap, trials.size());
            server.halt(false);
            Thread exiter = new Thread(() -> {
                server.halt(true);
                System.exit(0);
            }, "orebit-iceparkour-exit");
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
