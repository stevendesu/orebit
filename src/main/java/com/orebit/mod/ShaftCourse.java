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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/**
 * Headless TRAPDOOR-LADDER-ARC diagnostic harness (a sibling of {@link GateCourse}, armed by its own
 * {@code -Dorebit.shaft} flag) — the live follower-tier proof of DESIGN-trapdoor-ladder-climb.md §7. Each
 * tile is an isolated station over a flat world: a floating stone slab (11 layers) bored with a 1x1 shaft
 * {@link #DEPTH} cells deep, a full-height ladder column on the shaft's west wall (FACING = {@link #FACING}),
 * and — on the hatch tiles — a trapdoor in the MOUTH cell (the bore's top cell, level with the slab's top
 * layer) with FACING == the ladder's, the exact vanilla {@code trapdoorUsableAsLadder} geometry (§1); on the
 * PLAIN controls the ladder itself runs through the mouth cell to rim level (the
 * {@code TrapdoorLadderClimbTest.plainShaft} pin geometry — see {@code buildTile} for why a bare mouth over
 * a stopped-short ladder would instead be a two-sided block-tier dead end). The
 * course drives one goto per tile end to end: the §3 mouth rules (climb-continue through an open mouth, the
 * §3.8 rim top-entry), the §4 {@code SET_OPEN} folds (the {@code Need.OPEN} pre-pass on a Climb step), and
 * the §5 {@code climbableBelow} top-out fix under full vanilla physics.
 *
 * <p><b>Arrival means CLIMBED, not fell:</b> every arrival verdict requires the bot ALIVE at FULL health
 * for the WHOLE trial — a per-tick MINIMUM-health watch beside {@code maxBotY}, not an arrival sample,
 * because the course world regenerates (peaceful flat world, {@code naturalRegeneration} default-on,
 * hunger off pins food full → saturated fast-regen) and an arrival-only sample would let a small
 * recovered fall heal back to full inside the attempt budget. The clamp vanilla applies to a climbable
 * feet cell (−0.15/t) makes a real climb damage-free, while a {@code DEPTH}-deep plummet costs real
 * health under {@code survival.takesDamage=true} — so "never dipped below full" is the climbed-vs-fell
 * discriminator, not a nicety.
 *
 * <p><b>The tiles</b> (hatch state × direction, then the controls):
 * <ol>
 *   <li><b>topdown-open</b> — OPEN oak mouth, rim → shaft bottom: the §3.8 top-entry + climb-down chain;
 *       expect arrival at the bottom undamaged with ZERO hatch state changes.</li>
 *   <li><b>bottomup-open</b> — OPEN oak mouth, shaft bottom → rim: climb-continue through the mouth, the
 *       §3.4 EXIT-TOP from the mouth node (the §5 {@code climbableBelow} hang), a rim walk-off; zero
 *       state changes.</li>
 *   <li><b>topdown-closed</b> — CLOSED oak mouth: PASS = the bot opens it ITSELF (the §3.8 arm's §4 fold —
 *       exactly ONE closed→open transition, none back), then arrives at the bottom undamaged.</li>
 *   <li><b>bottomup-closed</b> — CLOSED oak mouth from below: the §4 from-below toggle arm; same
 *       exactly-one-own-toggle assertion, arrival on the rim.</li>
 *   <li><b>control-plain-topdown</b> — NO trapdoor (the ladder runs through the mouth cell to rim
 *       level), rim → bottom: the remaining gap is BLOCK-TIER-ONLY. The climbable-as-air region flood
 *       (owner ruling 2026-08-09, DESIGN-trapdoor-ladder-climb.md §6 follow-up) connects the bare
 *       shaft, but no block-tier plain-ladder TOP-ENTRY onto a flush rim exists (§3 — a ladder plate is
 *       NARROW_TOP, refused as every landing/destination floor; tracked outside this arc; pinned by
 *       {@code TrapdoorLadderClimbTest.topToBottomPlainShaftIsThePreExistingGap} over this exact
 *       geometry). This tile records the verdict <b>GAP</b> (nav gives up or times out) rather than
 *       FAIL — pre-existing, not this arc — and GAP does not count into {@code failed}. An arrival
 *       (undamaged) records PASS: the gap closed.</li>
 *   <li value="6"><b>control-plain-bottomup</b> — NO trapdoor, shaft bottom → rim: since the
 *       climbable-as-air flood, this CONTROL guards the climbable-flood connectivity LIVE — the region
 *       tier connects the bare shaft (ladder cells are flood-passable) and, over this tile's
 *       ladder-to-rim geometry, the block tier's bottom-up climb + top-out route is the pre-existing
 *       baseline ({@code TrapdoorLadderClimbTest.plainShaftBottomToTopIsThePreExistingBaseline} — a
 *       geometry constraint, see {@code buildTile}), so the tile expects the NORMAL arrival PASS:
 *       alive + undamaged, zero hatch ops (there is no hatch). A no-route outcome is a real FAIL here,
 *       not a GAP.</li>
 *   <li value="7"><b>control-iron-bottomup</b> — IRON trapdoor CLOSED in the mouth: never hand-toggleable
 *       (§4), no mining, no placing → genuinely NO route. PASS = an honest give-up
 *       ({@link BotNavigator#navGaveUp}, the {@link GateCourse} toggleoff verdict pattern incl. its
 *       nav-readiness gate) with the hatch untouched, ZERO state changes, and the bot never past the mouth
 *       plane. Crossing, arrival, or timeout-without-give-up = FAIL.</li>
 * </ol>
 *
 * <p><b>The per-tick hatch watch</b> is an own-toggle counter without instrumenting the bot: no tile has
 * redstone, the world is peaceful, and no external flip exists on this course, so EVERY observed state
 * change is bot-authored by construction (the {@link GateCourse} transition-counter idiom). Per-tile PROBE
 * lines dump the ACTUALLY-PLACED mouth + top-rung BlockStates — the axis/facing double-check (a
 * facing-mismatched pair fails vanilla's rule and would silently turn every hatch tile into a refusal).
 *
 * <p><b>Config (scripts/shaft/orebit.properties).</b> {@code doors.toggle=true} (the §4 folds are the arc
 * under test), {@code mining.canMine=false} (mining the hatch or digging a bypass shaft would fake every
 * verdict, incl. the iron refusal), {@code placement.canPlace=false} (no pillaring out of the bore),
 * {@code survival.takesDamage=true} (the undamaged-arrival discriminator above), {@code pathing.async=false}
 * (deterministic).
 *
 * <p><b>Inert in production</b> — {@link #register} returns immediately unless {@code -Dorebit.shaft} is
 * set. Common, version-portable source (ladder/trapdoor state properties are drift-free 1.17.1 → 26.2, §1).
 */
public final class ShaftCourse {

    private ShaftCourse() {}

    private static final String RESULT_FILE = "orebit-shaft-result.properties";
    private static final String TRACE_FILE = "orebit-shaft-trace.txt";

    /** Slab-top / mouth-cell Y. Rim feet stand at {@code Y0+1}; floating high so a fall off the slab edge
     *  is unambiguous. */
    private static final int Y0 = 150;
    /** Bore depth in cells, mouth included: shaft air {@code Y0-DEPTH+1..Y0}, stone floor {@code Y0-DEPTH},
     *  bottom feet {@code Y0-DEPTH+1}. Deep enough that a free fall costs REAL health (the climbed-vs-fell
     *  discriminator) without being lethal. */
    private static final int DEPTH = 10;
    /** Ladder + trapdoor FACING (vanilla's rule requires equality, §1). EAST = both hang on the WEST shaft
     *  wall: the ladder's support block and the open panel's flush wall are the same face. */
    private static final Direction FACING = Direction.EAST;

    private static final int BASE_X = 8;
    private static final int BASE_Z = 8;
    private static final int COLS = 2;
    private static final int STRIDE = 40; // grid cell size (> the slab span so nav grids never touch)

    /** Ticks to let the whole starting area gen + nav-build before the first goto. */
    private static final int WARMUP_TICKS = 160;
    /** Ticks after each teleport before the goto (the just-painted tile's nav patches settle). */
    private static final int SETTLE_TICKS = 60;
    private static final int NAV_RETRY_WINDOW = 60;
    private static final int MAX_NAV_RETRY = 5;
    /** Per-trial attempt budget (ticks). A shaft is ~{@code DEPTH} cells at the vanilla 0.2 b/t climb rate
     *  plus one toggle pre-pass; a stuck/looping bot resolves well within it. */
    private static final int ATTEMPT_BUDGET = 600;

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    public static void register(PlatformEvents events) {
        if (System.getProperty("orebit.shaft") == null) {
            return;
        }
        Course course = new Course();
        events.onServerStarted(course::start);
        events.onWorldTickEnd(course::tick);
        OrebitCommon.LOGGER.info("[Orebit/shaft] armed: {} trials", course.trials.size());
    }

    private enum Kind {
        TOPDOWN_OPEN,    // open oak mouth, rim -> bottom: §3.8 top-entry, zero toggles
        BOTTOMUP_OPEN,   // open oak mouth, bottom -> rim: mouth continue + §3.4/§5 top-out, zero toggles
        TOPDOWN_CLOSED,  // closed oak mouth, rim -> bottom: §3.8 + the §4 fold, exactly one own SET_OPEN
        BOTTOMUP_CLOSED, // closed oak mouth, bottom -> rim: the §4 from-below arm, exactly one own SET_OPEN
        PLAIN_TOPDOWN,   // CONTROL: no trapdoor, rim -> bottom — the BLOCK-TIER-ONLY top-entry gap
        PLAIN_BOTTOMUP,  // CONTROL: no trapdoor, bottom -> rim — guards climbable-flood connectivity live
        IRON_BOTTOMUP;   // CONTROL: iron closed mouth — honest give-up, hatch untouched

        boolean topdown() { return this == TOPDOWN_OPEN || this == TOPDOWN_CLOSED || this == PLAIN_TOPDOWN; }
        boolean hasHatch() { return this != PLAIN_TOPDOWN && this != PLAIN_BOTTOMUP; }
        boolean startsOpen() { return this == TOPDOWN_OPEN || this == BOTTOMUP_OPEN; }
    }

    /** One shaft challenge: a kind + its base grid cell, with start/goal geometry precomputed. */
    private static final class Trial {
        final String name;
        final Kind kind;
        final int baseX, baseZ;
        final int sx, sz;           // the shaft column
        final BlockPos mouth;       // the bore's top cell (the trapdoor cell on hatch tiles)

        double startX, startY, startZ;
        float startYaw;
        BlockPos goal;
        int minFloorY;              // a fall this far below the slab = off the course
        boolean expectOpen;         // the OPEN state the mouth must END in (hatch tiles only)

        Trial(String name, Kind kind, int baseX, int baseZ) {
            this.name = name;
            this.kind = kind;
            this.baseX = baseX;
            this.baseZ = baseZ;
            this.sx = baseX + 6;
            this.sz = baseZ + 6;
            this.mouth = new BlockPos(sx, Y0, sz);
            this.minFloorY = Y0 - DEPTH - 6;
            int bottomFeetY = Y0 - DEPTH + 1;
            if (kind.topdown()) {
                this.startX = sx + 3.5;
                this.startY = Y0 + 1;
                this.startZ = sz + 0.5;
                this.goal = new BlockPos(sx, bottomFeetY, sz);
            } else {
                this.startX = sx + 0.5;
                this.startY = bottomFeetY;
                this.startZ = sz + 0.5;
                this.goal = new BlockPos(sx + 3, Y0 + 1, sz);
            }
            // -X: topdown faces the mouth; bottomup presses into the ladder face (the west shaft wall) —
            // the §1 rise gate is horizontalCollision || jumping, and the steer keeps it held either way.
            this.startYaw = yaw(-1, 0);
            // Open tiles stay OPEN (untouched); closed oak tiles END open (the bot's own SET sticks);
            // the iron control must still be CLOSED (never operated).
            this.expectOpen = kind.hasHatch() && kind != Kind.IRON_BOTTOMUP;
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
        boolean prevOpen;           // last observed mouth OPEN state (the transition-counter baseline)
        int opensSeen;              // closed->open transitions observed this trial
        int closesSeen;             // open->closed transitions observed this trial
        double maxBotY;             // highest bot feet-Y this trial (IRON: the never-crossed proof)
        double minHp;               // lowest health seen this trial (regen-proof climbed-vs-fell watch)

        Course() {
            buildTrialList();
        }

        void buildTrialList() {
            add("topdown-open",           Kind.TOPDOWN_OPEN);
            add("bottomup-open",          Kind.BOTTOMUP_OPEN);
            add("topdown-closed",         Kind.TOPDOWN_CLOSED);
            add("bottomup-closed",        Kind.BOTTOMUP_CLOSED);
            add("control-plain-topdown",  Kind.PLAIN_TOPDOWN);
            add("control-plain-bottomup", Kind.PLAIN_BOTTOMUP);
            add("control-iron-bottomup",  Kind.IRON_BOTTOMUP);
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
            if (Boolean.getBoolean("orebit.shaft.debug")) {
                Debug.ENABLED = true;
                Debug.VERBOSE = true;
            }
            try {
                this.level = server.overworld();
                Trial first = trials.get(0);
                owner = new FakePlayerEntity(server, level, new GameProfile(
                        UUID.nameUUIDFromBytes("OrebitShaft:owner".getBytes(StandardCharsets.UTF_8)),
                        "Shaft"));
                owner.setPos(first.startX, first.startY, first.startZ);
                BotManager.spawnBotFor(owner);
                bot = BotManager.botFor(owner);
                if (bot == null) {
                    finish("bot never spawned");
                    return;
                }
                trace = Files.newBufferedWriter(ConfigDir.serverDir(server).resolve(TRACE_FILE),
                        StandardCharsets.UTF_8);
                trace.write("Orebit shaft course trace  (T <trial> <tick> x y z | spd vy | onGround | hp | hatch=<O/C/-> | move)\n");
                trace.write("legend: hatch = live OPEN state of the tile's mouth trapdoor (O=open C=closed, '-'=no hatch on the\n");
                trace.write("plain-control tiles); HATCH lines mark state transitions — EVERY one is a bot toggle (peaceful, no\n");
                trace.write("redstone, no external flips on this course); PROBE dumps the placed mouth + top-rung states (the\n");
                trace.write("facing double-check: vanilla's rule requires trapdoor FACING == ladder FACING)\n\n");
                OrebitCommon.LOGGER.info("[Orebit/shaft] course ready; {} trials", trials.size());
                enter(0);
            } catch (Throwable t) {
                OrebitCommon.LOGGER.error("[Orebit/shaft] setup threw", t);
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
            bot.setPos(tr.startX, tr.startY, tr.startZ);
            bot.setDeltaMovement(Vec3.ZERO);
            bot.setYRot(tr.startYaw);
            bot.setYHeadRot(tr.startYaw);
            settling = true;
            settleTicks = 0;
            attemptTicks = 0;
            navRetries = 0;
            closest = Double.MAX_VALUE;
            prevOpen = openAt(tr.mouth);
            opensSeen = 0;
            closesSeen = 0;
            maxBotY = tr.startY;
            minHp = bot.getMaxHealth();
            prevX = tr.startX;
            prevZ = tr.startZ;
            prevMove = "";
            prevSegToX = Integer.MIN_VALUE;
            try {
                trace.write(String.format(Locale.ROOT,
                        "== %s : kind=%s start=(%.1f,%.1f,%.1f) goal=(%d,%d,%d) mouth=(%d,%d,%d)\n",
                        tr.name, tr.kind, tr.startX, tr.startY, tr.startZ,
                        tr.goal.getX(), tr.goal.getY(), tr.goal.getZ(),
                        tr.mouth.getX(), tr.mouth.getY(), tr.mouth.getZ()));
            } catch (IOException ignored) { }
        }

        void tick(ServerLevel lvl) {
            if (overallDone || bot == null || server == null || lvl != level) {
                return;
            }
            Trial tr = trials.get(index);

            if (settling) {
                int target = index == 0 ? WARMUP_TICKS : SETTLE_TICKS;
                // Also gate the goto on nav residency around the goal (the BoxedInCourse readiness gate):
                // the iron tile PASSES on a give-up, so its goto must never be issued over unbuilt nav or a
                // premature navGaveUp would fake the refusal. Applied uniformly — for the climbing tiles it
                // only ever delays the goto, never changes the outcome.
                if (++settleTicks < target || !navReadyAround(tr.goal)) {
                    return;
                }
                settling = false;
                bot.comeTo(tr.goal, 0.75, 0.75, 0); // exact: reach the precise cell (the GotoCommand form)
                return;
            }

            attemptTicks++;

            // The per-tick hatch state watch: count transitions in both directions. Nothing else on a tile
            // can change the mouth (peaceful, no redstone, no external flips on this course), so every
            // transition is a bot toggle.
            if (tr.kind.hasHatch()) {
                boolean open = openAt(tr.mouth);
                if (open != prevOpen) {
                    if (open) opensSeen++; else closesSeen++;
                    try {
                        trace.write(String.format(Locale.ROOT,
                                "  HATCH tick=%d %s bot=(%.2f,%.2f,%.2f) opens=%d closes=%d\n",
                                attemptTicks, open ? "C->O" : "O->C",
                                bot.getX(), bot.getY(), bot.getZ(), opensSeen, closesSeen));
                    } catch (IOException ignored) { }
                    prevOpen = open;
                }
            }
            if (bot.getY() > maxBotY) {
                maxBotY = bot.getY();
            }
            if (bot.getHealth() < minHp) {
                minHp = bot.getHealth();
            }

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
                record(tr, "FAIL", "fell off the tile");
                return;
            }

            if (tr.kind == Kind.IRON_BOTTOMUP) {
                tickIron(tr, dist);
                return;
            }
            // Since the climbable-as-air flood only the TOP-DOWN plain tile keeps the GAP verdict (the
            // block-tier-only top-entry gap); PLAIN_BOTTOMUP now expects the normal arrival PASS below
            // — it guards the climbable-flood connectivity live (no hatch, so the world-state
            // assertions are trivially zero hatch ops).
            if (tr.kind == Kind.PLAIN_TOPDOWN) {
                tickPlainControl(tr, dist);
                return;
            }

            // Candidate PASS: the driver reverted to STAY (exact-tolerance arrival) near the goal cell —
            // then ALIVE at FULL health for the WHOLE trial (the per-tick minHp watch: the climbed-vs-fell
            // discriminator — a DEPTH-deep plummet costs real health, and the course world's regen would
            // heal a small recovered fall back to full by arrival) and the tile's WORLD-STATE assertions
            // decide.
            if (bot.mode() == AllyBotEntity.Mode.STAY && dist < 1.2) {
                if (minHp < bot.getMaxHealth() - 0.01f) {
                    record(tr, "FAIL", String.format(Locale.ROOT,
                            "took damage mid-trial (minHp=%.1f/%.1f — fell instead of climbing)",
                            minHp, bot.getMaxHealth()));
                    return;
                }
                String stateFail = worldStateFailure(tr);
                if (stateFail == null) {
                    record(tr, "PASS", "reached goal alive + undamaged, world state as expected");
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
                record(tr, "FAIL", "nav gave up (no route offered)");
                return;
            }
            if (attemptTicks >= ATTEMPT_BUDGET) {
                record(tr, "FAIL", "timeout");
            }
        }

        /**
         * The IRON control verdict (the {@link GateCourse} toggleoff pattern): refusing is the correct
         * outcome. A CLOSED iron trapdoor is never hand-toggleable (§4), mining and placing are off, so
         * the mouth is a hard lid with no route: PASS = an honest give-up with the hatch untouched and the
         * bot never past the mouth plane. Crossing/arrival = the toggleable gate leaked; a timeout without
         * the give-up = no honest refusal (a park/livelock — the BoxedInCourse tomb convention).
         */
        void tickIron(Trial tr, double dist) {
            if (bot.getY() > tr.mouth.getY() + 0.5) {
                record(tr, "FAIL", "rose past the closed iron mouth (a toggle/route leak)");
                return;
            }
            if (bot.mode() == AllyBotEntity.Mode.STAY && dist < 1.2) {
                record(tr, "FAIL", "arrived at the rim goal with the iron hatch closed");
                return;
            }
            if (bot.navigator().navGaveUp()) {
                String stateFail = worldStateFailure(tr);
                if (stateFail == null) {
                    record(tr, "PASS", "gave up honestly; hatch untouched");
                } else {
                    record(tr, "FAIL", "gave up but " + stateFail);
                }
                return;
            }
            if (attemptTicks >= ATTEMPT_BUDGET) {
                record(tr, "FAIL", "timeout — never gave up (parked without an honest refusal)");
            }
        }

        /**
         * The plain-shaft TOP-DOWN control verdict (PLAIN_TOPDOWN only — PLAIN_BOTTOMUP rides the
         * normal arrival path since the climbable-as-air flood): NO trapdoor, and the remaining gap is
         * BLOCK-TIER-ONLY — the region tier connects the bare shaft, but no block-tier plain-ladder
         * TOP-ENTRY exists (§3, NARROW_TOP; tracked outside this arc), so a no-route outcome is
         * PRE-EXISTING, NOT this arc's failure. Those outcomes record the distinct verdict GAP
         * (excluded from {@code failed}); a genuine undamaged arrival records PASS (the gap closed);
         * only anomalies (death, falling off the slab — caught in the shared tick above) FAIL.
         */
        void tickPlainControl(Trial tr, double dist) {
            if (bot.mode() == AllyBotEntity.Mode.STAY && dist < 1.2) {
                if (minHp < bot.getMaxHealth() - 0.01f) {
                    record(tr, "GAP", String.format(Locale.ROOT,
                            "CONTROL: arrived but took damage (minHp=%.1f/%.1f) — the only plain route costs health; pre-existing, not this arc",
                            minHp, bot.getMaxHealth()));
                } else {
                    record(tr, "PASS", "CONTROL: arrived undamaged — a plain-shaft route exists (no gap on this tile)");
                }
                return;
            }
            if (bot.navigator().navGaveUp()) {
                if (attemptTicks <= NAV_RETRY_WINDOW && navRetries < MAX_NAV_RETRY) {
                    navRetries++;
                    bot.comeTo(tr.goal, 0.75, 0.75, 0);
                    return;
                }
                record(tr, "GAP", "CONTROL: nav gave up — the BLOCK-TIER-ONLY plain-ladder top-entry gap, not this arc");
                return;
            }
            if (attemptTicks >= ATTEMPT_BUDGET) {
                record(tr, "GAP", "CONTROL: timeout without a route — the block-tier-only top-entry gap, not this arc");
            }
        }

        /** {@code null} when every end-of-trial assertion holds; else a short reason. */
        String worldStateFailure(Trial tr) {
            if (!tr.kind.hasHatch()) {
                return null; // plain controls: no hatch to assert
            }
            boolean open = openAt(tr.mouth);
            if (open != tr.expectOpen) {
                return "hatch " + tr.mouth.toShortString() + " is " + (open ? "OPEN" : "CLOSED")
                        + ", expected " + (tr.expectOpen ? "OPEN" : "CLOSED");
            }
            switch (tr.kind) {
                case TOPDOWN_CLOSED:
                case BOTTOMUP_CLOSED: {
                    // Exactly one own toggle: one closed->open transition and none back.
                    if (opensSeen != 1 || closesSeen != 0) {
                        return "expected exactly one own SET_OPEN (opens=" + opensSeen
                                + " closes=" + closesSeen + ")";
                    }
                    break;
                }
                case TOPDOWN_OPEN:
                case BOTTOMUP_OPEN: {
                    // Zero toggles: the pre-opened mouth must never have changed.
                    if (opensSeen != 0 || closesSeen != 0) {
                        return "expected zero hatch toggles (opens=" + opensSeen
                                + " closes=" + closesSeen + ")";
                    }
                    break;
                }
                default: { // IRON_BOTTOMUP
                    if (opensSeen != 0 || closesSeen != 0) {
                        return "expected zero hatch toggles (opens=" + opensSeen
                                + " closes=" + closesSeen + ")";
                    }
                    if (maxBotY > tr.mouth.getY() + 0.5) {
                        return "bot rose past the mouth plane (maxY=" + fmt(maxBotY) + ")";
                    }
                    break;
                }
            }
            return null;
        }

        boolean openAt(BlockPos pos) {
            BlockState s = level.getBlockState(pos);
            return s.getBlock() instanceof TrapDoorBlock && s.getValue(BlockStateProperties.OPEN);
        }

        String hatchStr(Trial tr) {
            if (!tr.kind.hasHatch()) return "-";
            return openAt(tr.mouth) ? "O" : "C";
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
                            "  WP i=%d/%d %s seg=(%d,%d,%d)->(%d,%d,%d) bot=(%.2f,%.2f,%.2f)\n",
                            nav.waypointIndex(), nav.pathSize(), move,
                            nav.segFromX(), nav.segFromY(), nav.segFromZ(),
                            nav.segToX(), nav.segToY(), nav.segToZ(), x, y, z));
                    prevMove = move;
                    prevSegToX = nav.segToX(); prevSegToY = nav.segToY(); prevSegToZ = nav.segToZ();
                }
                trace.write(String.format(Locale.ROOT,
                        "T %-22s %3d  %.3f %.3f %.3f | %.4f %.4f | %d | %.1f | hatch=%s | %s\n",
                        tr.name, attemptTicks, x, y, z, spd, v.y,
                        grnd ? 1 : 0, bot.getHealth(), hatchStr(tr), move));
            } catch (IOException ignored) { }
            prevX = x;
            prevZ = z;
        }

        void record(Trial tr, String result, String reason) {
            String extra = tr.kind.hasHatch()
                    ? " opens=" + opensSeen + " closes=" + closesSeen
                    : " opens=- closes=-";
            if (tr.kind == Kind.IRON_BOTTOMUP) {
                extra += " maxY=" + fmt(maxBotY);
            }
            results.add(String.format(Locale.ROOT,
                    "%s = %s (%s) closest=%.2f ticks=%d finalPos=(%.1f,%.1f,%.1f) hp=%.1f minHp=%.1f hatch=%s%s lastMove=%s",
                    tr.name, result, reason, closest, attemptTicks,
                    bot.getX(), bot.getY(), bot.getZ(), bot.getHealth(), minHp, hatchStr(tr), extra,
                    bot.lastSteerMove));
            if (result.equals("PASS")) passed++;
            else if (result.equals("FAIL")) failed++;
            else gaps++;
            OrebitCommon.LOGGER.info("[Orebit/shaft] {} -> {} ({}) closest={} ticks={} hatch={} opens={} closes={}",
                    tr.name, result, reason, String.format(Locale.ROOT, "%.2f", closest),
                    attemptTicks, hatchStr(tr), opensSeen, closesSeen);
            try { trace.write("  RESULT " + result + " (" + reason + ")\n\n"); } catch (IOException ignored) { }
            if (index + 1 < trials.size()) {
                enter(index + 1);
            } else {
                finish("all trials complete");
            }
        }

        private static String fmt(double d) {
            return String.format(Locale.ROOT, "%.2f", d);
        }

        // ---- tile construction ---------------------------------------------------------------------------

        void buildTile(Trial tr) {
            // The slab: 13x13, DEPTH+1 layers, top at Y0 — the bore stays entirely inside stone and the
            // rim offers real footing all around the mouth.
            box(tr.baseX, tr.baseX + 12, Y0 - DEPTH, Y0, tr.baseZ, tr.baseZ + 12);
            // The 1x1 bore: air from the mouth down to the bottom feet cell (stone floor at Y0-DEPTH).
            for (int y = Y0 - DEPTH + 1; y <= Y0; y++) {
                set(tr.sx, y, tr.sz, AIR);
            }
            // The ladder column, hung on the west wall (the wall exists before the ladders — placement
            // order matters, ladders pop without support). Hatch tiles stop it at the rung below the
            // mouth (vanilla trapdoorUsableAsLadder wants exactly Blocks.LADDER in the cell below the
            // trapdoor); the PLAIN controls run it THROUGH the mouth cell to rim level — the
            // TrapdoorLadderClimbTest plainShaft geometry BOTH plain pins are stated over. A ladder
            // stopping below a BARE mouth is a TWO-sided block-tier gap (probed 2026-08-09: findPath
            // returns null in either direction — the §3.4 exit-top tops out ON the NARROW_TOP plate one
            // below rim feet, which no jump/walk move may leave), so that shape would break tile 6's
            // arrival expectation while proving nothing about the flood.
            int ladderTop = tr.kind.hasHatch() ? Y0 - 1 : Y0;
            for (int y = Y0 - DEPTH + 1; y <= ladderTop; y++) {
                set(tr.sx, y, tr.sz, ladder());
            }
            // The mouth cell: an equal-facing trapdoor on the hatch tiles; the ladder's own top rung on
            // the plain controls (that difference IS the control).
            if (tr.kind.hasHatch()) {
                set(tr.sx, Y0, tr.sz, trapdoor(tr.kind == Kind.IRON_BOTTOMUP, tr.kind.startsOpen()));
            }
        }

        /** Dump the ACTUALLY-PLACED mouth + top-rung BlockStates — the facing double-check (vanilla's rule
         *  requires trapdoor FACING == ladder FACING; a transposed pair silently turns every hatch tile
         *  into a refusal that reads like an arc bug). */
        void probeTile(Trial tr) {
            BlockState mouth = level.getBlockState(tr.mouth);
            BlockState rung = level.getBlockState(tr.mouth.below());
            OrebitCommon.LOGGER.info("[Orebit/shaft] {} probe mouth {} = {} | rung {} = {}",
                    tr.name, tr.mouth.toShortString(), mouth, tr.mouth.below().toShortString(), rung);
            try {
                trace.write(String.format(Locale.ROOT, "  PROBE mouth %s = %s\n  PROBE rung  %s = %s\n",
                        tr.mouth.toShortString(), mouth, tr.mouth.below().toShortString(), rung));
            } catch (IOException ignored) { }
        }

        // ---- placement primitives ------------------------------------------------------------------------

        static BlockState ladder() {
            return Blocks.LADDER.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, FACING);
        }

        static BlockState trapdoor(boolean iron, boolean open) {
            return (iron ? Blocks.IRON_TRAPDOOR : Blocks.OAK_TRAPDOOR).defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, FACING)
                    .setValue(BlockStateProperties.OPEN, open);
        }

        /** Solid stone fill over the inclusive box. */
        void box(int x0, int x1, int y0, int y1, int z0, int z1) {
            for (int x = x0; x <= x1; x++)
                for (int y = y0; y <= y1; y++)
                    for (int z = z0; z <= z1; z++)
                        set(x, y, z, STONE);
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
                kv(w, "controlGaps", gaps);
                for (String line : results) {
                    w.write(line);
                    w.write('\n');
                }
            } catch (IOException e) {
                OrebitCommon.LOGGER.error("[Orebit/shaft] could not write {}", file, e);
            }
            try { if (trace != null) trace.close(); } catch (IOException ignored) { }
            OrebitCommon.LOGGER.info("[Orebit/shaft] DONE ({}) — {} passed / {} failed / {} control-gaps of {} — halting",
                    reason, passed, failed, gaps, trials.size());
            server.halt(false);
            Thread exiter = new Thread(() -> {
                server.halt(true);
                System.exit(0);
            }, "orebit-shaft-exit");
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
