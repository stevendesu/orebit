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
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.phys.Vec3;

/**
 * Headless PARKOUR-MOVEMENT diagnostic harness (a sibling of {@link HeadlessAutotest}, armed by its own
 * {@code -Dorebit.parkour} flag). It builds a synthetic obstacle course floating high over a flat world and
 * drives the bot through a SERIES of isolated single-jump challenges — each a specific parkour SHAPE under a
 * specific APPROACH condition — recording pass/fail plus a per-tick trajectory, so the over/undershoot
 * pathologies can be diagnosed from data.
 *
 * <p><b>Why a bespoke course.</b> Over/undershoot is an EXECUTOR pathology (the physical jump misses its
 * landing), invisible to a route-level pass/fail. Isolating one jump on a known platform with a lethal drop
 * on a miss makes each shape a reproducible experiment; the trajectory dump captures the arc and the takeoff
 * speed (position-delta on the ground&rarr;air tick), the quantity the physics envelope assumes.
 *
 * <p><b>Generalised tile model.</b> A trial's APPROACH direction is independent of its JUMP vector, so the
 * same builder stages a head-on cardinal jump, a diagonal jump, a {@code (c,±1)} OFFSET jump (the gated
 * "2-forward-1-lateral" tier), and a 90°-TURN approach (run one way, jump another — the misaligned-approach
 * case the owner suspects behind the real-play undershoot). Each shape runs under both precursor conditions:
 * <ul>
 *   <li><b>walkin</b> — a short runway of non-sprinting {@code Traverse}/{@code Diagonal} steps, so the bot
 *       enters the takeoff block carrying walk-{@code v∞} momentum (the Traverse-preceded case);</li>
 *   <li><b>rest</b> — teleported onto the takeoff block AT REST, so the RUNUP must sprint up from a
 *       standstill within that one block (the pillar/fall-preceded worst case).</li>
 * </ul>
 * The harness bakes in NO fix and asserts NO expected outcome. Placement and mining are OFF in the course
 * config so the ONLY way across a gap is a jump; a miss falls ~200 blocks and dies (an unambiguous FAIL).
 *
 * <p><b>Inert in production</b> — {@link #register} returns immediately unless {@code -Dorebit.parkour} is
 * set. Common, version-portable source (every MC surface it touches is range-stable).
 */
public final class ParkourCourse {

    private ParkourCourse() {}

    private static final String RESULT_FILE = "orebit-parkour-result.properties";
    private static final String TRACE_FILE = "orebit-parkour-trace.txt";

    /** Floor-cell Y of the takeoff platform (feet stand at {@code Y0+1}); high enough that a miss is fatal. */
    private static final int Y0 = 150;
    private static final int BASE_X = 8;
    private static final int BASE_Z = 8;
    /** Tiles are laid in a compact GRID (snake ordering, so consecutive trials are always adjacent and
     *  teleports stay inside the loaded+built nav bubble — the long linear course left far tiles unbuilt). */
    private static final int COLS = 6;
    private static final int STRIDE = 26; // grid cell size (> the longest tile span so tiles never touch)
    /** Runway length in cells (the takeoff cell is the last). */
    private static final int RUN = 7;
    /** REACH landing platform length beyond the landing cell (generous). */
    private static final int GOAL_LEN = 6;
    /** Where on the landing platform the goal sits (cells past the landing, along the continuation axis). */
    private static final int GOAL_REACH = 4;
    /** PRECISION walkway length (perpendicular to the jump line) from the 1-wide landing to the goal. */
    private static final int WALK = 5;

    /** Honey honest-cross: landing-platform length (cells past the landing edge) — long enough to catch an
     *  overshoot AND host the far goal, so a real crossing has runout and the goal is a genuine platform cell. */
    private static final int HONEY_LAND_LEN = 6;
    /** Honey honest-cross: how many cells PAST the landing edge the goal sits. Must exceed the 2.5-block
     *  arrival radius measured from the honey lip, so a honey-edge teeter can never score "arrived". */
    private static final int HONEY_GOAL_PAST = 3;

    /** Blocks of clearance above each stair floor for the staircase-trial ceiling (see {@code buildStairs}):
     *  3 clear body cells — the cover that blocks a jump's apex head but not a step-assist's ~0.5 head-rise. */
    private static final int STAIR_CEILING_GAP = 4;

    /** Ticks to let the WHOLE starting area's nav grid build before the first goto (chunk gen + nav build). */
    private static final int WARMUP_TICKS = 120;
    /** Ticks to let the local nav grid build after each subsequent teleport before issuing the goto. */
    private static final int SETTLE_TICKS = 40;
    /** If nav gives up within this many attempt ticks, treat it as nav-not-yet-built and re-issue (up to
     *  {@link #MAX_NAV_RETRY}) rather than failing — a harness robustness measure, not a bot behaviour. */
    private static final int NAV_RETRY_WINDOW = 40;
    private static final int MAX_NAV_RETRY = 5;
    /** Per-trial attempt budget (ticks). */
    private static final int ATTEMPT_BUDGET = 400;

    private static final BlockState FLOOR = Blocks.STONE.defaultBlockState();
    private static final BlockState SLAB = Blocks.SMOOTH_STONE_SLAB.defaultBlockState();
    /** MAGMA: a full-block DAMAGING floor (hurts an entity standing on it) — the takeoff-timing hazard the
     *  planner OVERFLIES and Fix 3 must not let the bot stand on during runup. */
    private static final BlockState MAGMA = Blocks.MAGMA_BLOCK.defaultBlockState();
    /** HONEY: the only vanilla JUMP-SUPPRESSING block (jumpFactor 0.5) — also slow (speedFactor 0.4). The
     *  planner overflies it (slow trigger); Fix 3 keeps the center off it so the launch reads full jumpFactor. */
    private static final BlockState HONEY = Blocks.HONEY_BLOCK.defaultBlockState();
    /** Soul sand: a full-block SLOW floor (speedFactor 0.4, jumpFactor 1.0 — NOT reduced-jump like honey),
     *  so it reaches the envelope's soul-sand row and tightens the offered gaps. */
    private static final BlockState SOUL = Blocks.SOUL_SAND.defaultBlockState();
    /** A BOTTOM straight stair FACING EAST (+X): its HIGH 16/16 half is on +X, LOW 8/16 front on -X (verified
     *  empirically, StairVoxelProbe). Climbing +X (or descending -X) walks its low front → high back. */
    private static final BlockState STAIR_EAST = Blocks.STONE_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
            .setValue(BlockStateProperties.HALF, Half.BOTTOM)
            .setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.STRAIGHT);

    public static void register(PlatformEvents events) {
        if (System.getProperty("orebit.parkour") == null) {
            return;
        }
        Course course = new Course();
        events.onServerStarted(course::start);
        events.onWorldTickEnd(course::tick);
        OrebitCommon.LOGGER.info("[Orebit/parkour] armed: {} trials", course.trials.size());
    }

    private enum Template { REACH, PRECISION, OFFSET }
    private enum Approach { WALKIN, REST }

    /** One jump challenge: an approach direction + a jump vector + a landing template + a precursor condition,
     *  with all world geometry precomputed from its base X band. */
    private static final class Trial {
        final String name;
        boolean slabRunway;
        boolean soulRunway;             // soul-sand takeoff+runway (slow floor — tighter envelope row)
        boolean stairRun;               // a staircase-traversal trial (custom build + pass/fail), not a jump
        int stairSteps;                 // number of stair blocks in the run
        BlockState gapFloor;            // magma/honey placed in the FIRST gap cell (null = normal void gap)
        boolean fastEntry;              // owner-gate: force a full-SPRINT approach (real 3-stone run) into the jump
        boolean assertNoDamage;         // magma-overhang: PASS requires the bot took ZERO damage
        boolean expectRefusal;          // beyond-envelope geometry the planner rightly declines -> PASS = clean refusal
        String refuseNote;              // optional note appended to an expectRefusal PASS reason (e.g. "conservative")
        String plannerGap;              // != null: a KNOWN PLANNER GAP — any FAIL uses this reason, counted apart
        int gapFloorX, gapFloorZ;       // the first gap cell just past the takeoff lip (Fix 3 hazard site)
        final Approach approach;
        final int rdx, rdz;             // approach (runway) direction
        final int jdx, jdy, jdz;        // jump vector: takeoff cell -> landing cell
        final Template template;
        final boolean walled;           // block diagonal corner-cuts (force the cardinal jump after a turn)
        final int baseX, baseZ;
        final int takeoffX, takeoffZ;
        final int landX, landZ, landY;
        final int landedFeetY;          // expected feet Y on a clean landing (= landY + 1)
        final int cdx, cdz;             // continuation axis (dominant horizontal of the jump vector)
        final boolean wideRunway;       // 3-wide straight runway (only when approach == continuation, cardinal)
        final boolean diagRunway;       // 1-wide diagonal runway strip
        boolean ownerRepro;             // owner-gate: lay the owner's honey-flyover course (buildOwnerTile)
        boolean honestCross;            // owner-gate: STRICT verdict — PASS requires a REAL airborne crossing that
                                        //   lands on the far platform; goal pushed well past the landing so the
                                        //   2.5-block arrival radius can't score a honey-edge teeter as "arrived".
        boolean honeyRunup;             // owner-gate: WALKIN + full stone runway + fastEntry sprint-arrival (the
                                        //   run-up twin — does the walk-off cross CLEANLY when it reaches the lip
                                        //   at/near sprint, vs the standstill spawn that arrives at walk speed?).
        BlockPos goal;                  // (non-final: ownerRepro overrides it to the owner's goto target cell)
        final double startX, startZ;
        final float startYaw;
        final double ujx, ujz;          // normalized horizontal jump direction (for along-line projection)

        Trial(String name, Approach approach, int rdx, int rdz, int jdx, int jdy, int jdz,
                Template template, boolean walled, int baseX, int baseZ) {
            this.name = name;
            this.approach = approach;
            this.rdx = rdx;
            this.rdz = rdz;
            this.jdx = jdx;
            this.jdy = jdy;
            this.jdz = jdz;
            this.template = template;
            this.walled = walled;
            this.baseX = baseX;
            this.baseZ = baseZ;
            this.takeoffX = baseX + (RUN - 1) * rdx;
            this.takeoffZ = baseZ + (RUN - 1) * rdz;
            this.landX = takeoffX + jdx;
            this.landZ = takeoffZ + jdz;
            this.landY = Y0 + jdy;
            this.landedFeetY = landY + 1;
            // Continuation axis: the dominant horizontal component of the jump (where the landing platform and
            // goal extend). Diagonal (|jdx|==|jdz|) and offset (|jdx|>|jdz|) both continue along X here.
            if (Math.abs(jdx) >= Math.abs(jdz)) { this.cdx = Integer.signum(jdx); this.cdz = 0; }
            else { this.cdx = 0; this.cdz = Integer.signum(jdz); }
            this.diagRunway = rdx != 0 && rdz != 0;
            this.wideRunway = template == Template.REACH && !diagRunway && rdx == cdx && rdz == cdz;
            if (template == Template.PRECISION) {
                int px = -cdz, pz = cdx; // perpendicular walkway (overshoot along the jump falls off the ledge)
                this.goal = new BlockPos(landX + WALK * px, landY + 1, landZ + WALK * pz);
            } else { // REACH / OFFSET continue along the continuation axis
                this.goal = new BlockPos(landX + GOAL_REACH * cdx, landY + 1, landZ + GOAL_REACH * cdz);
            }
            if (approach == Approach.WALKIN) {
                this.startX = baseX + 0.5;
                this.startZ = baseZ + 0.5;
                this.startYaw = (float) Math.toDegrees(Math.atan2(-rdx, rdz)); // face the approach
            } else {
                this.startX = takeoffX + 0.5;
                this.startZ = takeoffZ + 0.5;
                this.startYaw = (float) Math.toDegrees(Math.atan2(-jdx, jdz)); // face the jump
            }
            double len = Math.sqrt((double) (jdx * jdx + jdz * jdz));
            this.ujx = jdx / len;
            this.ujz = jdz / len;
            // The first gap cell just past the takeoff lip (node level Y0) — where an overhang/honey-in-gap
            // trial places its hazard block, and the cell Fix 3's early-takeoff keeps the bot's center off.
            this.gapFloorX = takeoffX + Integer.signum(jdx);
            this.gapFloorZ = takeoffZ + Integer.signum(jdz);
        }

        /** Along the jump axis, the projection of the LANDING-cell centre from the takeoff centre (= the jump
         *  displacement {@code sqrt(jdx²+jdz²)}). A honey-in-gap diagnostic's shortfall is this minus the max
         *  projection the bot actually reached. */
        double landCenterProj() {
            return Math.sqrt((double) (jdx * jdx + jdz * jdz));
        }

        double proj(double x, double z) {
            return (x - (takeoffX + 0.5)) * ujx + (z - (takeoffZ + 0.5)) * ujz;
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
        boolean wentAirborne;       // (honestCross) the bot was airborne past the takeoff lip — a real walk-off
        boolean reachedLanding;     // (honestCross) the bot stood on the far landing platform (dropped 1, past gap)
        boolean stairAirborne;      // (stair trials) the bot left the ground during the run — i.e. it JUMPED
        double takeoffSpeed = -1;   // position-delta horizontal speed the tick the bot left the ground
        boolean wasGrounded = true;
        double prevX, prevZ;
        String prevMove = "";
        int passed, failed, plannerGap;   // plannerGap = intended RED reminders, counted apart from real fails
        double minHealth;           // lowest HP seen this trial (magma-overhang damage detection)
        double maxProj = -1e9;      // furthest along-jump-axis projection reached (honey-gap shortfall)

        Course() {
            buildTrialList();
        }

        /** The catalogue. Cardinal head-on shapes test the envelope; PRECISION (1-wide ledge) tests overshoot;
         *  OFFSET (c,±1) and 90°-TURN approaches hunt the real-play undershoot; the WALLED turn isolates whether
         *  that undershoot is the planner's diagonal corner-cut or the executor's misaligned-momentum takeoff. */
        void buildTrialList() {
            // Fast-iteration gate: -Dorebit.parkour.owneronly builds ONLY the owner's exact in-game honey-flyover
            // regression gate (NeoForge 1.21.11, 100% consistent void-fall) so a run is ~1 min instead of ~15.
            if (System.getProperty("orebit.parkour.owneronly") != null) {
                ownerRepro();
                return;
            }
            // Cardinal head-on (approach == jump == +X). name, jump(dx,dy,dz), template.
            card("flat1", 2, 0, 0, Template.REACH);
            card("flat2", 3, 0, 0, Template.REACH);
            card("flat3", 4, 0, 0, Template.REACH);
            card("rise1", 2, 1, 0, Template.REACH);
            card("rise2", 3, 1, 0, Template.REACH);
            card("rise3", 4, 1, 0, Template.REACH); // ratified-OUT (rising-3 unmakeable) — confirm it misses
            card("fall1", 2, -1, 0, Template.PRECISION); // overshoot (shallow ledge)
            card("fall2", 3, -1, 0, Template.PRECISION); // overshoot (shallow ledge)
            card("fall3", 4, -1, 0, Template.REACH);
            card("fall4", 5, -1, 0, Template.REACH);
            card("falld2g4", 5, -2, 0, Template.REACH);
            card("falld3g4", 5, -3, 0, Template.REACH);
            // Diagonal (approach == jump == +X+Z). diag3 (a 3-gap, 4-step diagonal) is the ratified-OUT row
            // the design doc derives as unmakeable (DIAG_MAX 3→2) — tested HEAD-ON here to confirm it misses
            // even with a clean aligned approach (it's what the turnflat corner-cut routes).
            diag("diag1", 2, 0, 2);
            diag("diag2", 3, 0, 3);
            diag("diag3", 4, 0, 4);
            // OFFSET (c,±1): approach +X, jump lands 1 cell OFF the cardinal line — the gated tier. The cardinal
            // line is kept pure gap (1-wide runway + 1-wide off-axis landing) so the aligned scan finds no
            // landing and ARMS the offset probe; nothing else can reach the landing. Both lateral signs.
            offset("offset2p", 2, 1);
            offset("offset3p", 3, 1);
            offset("offset2n", 2, -1);
            // 90°-TURN approach: run +Z, jump +X (walkin only — the turn IS the condition). Tight shapes.
            turn("turnflat2", 3, 0, 0, false);
            turn("turnflat3", 4, 0, 0, false);
            turn("turnrise2", 3, 1, 0, false);
            // WALLED turn: same turn, but a 2-high wall along the +X side of the runway blocks the diagonal
            // corner-cut, forcing the CARDINAL +X jump. If it still undershoots -> executor (misaligned
            // momentum) is the root; if it passes -> the planner's corner-cut choice was.
            turn("turnflat2w", 3, 0, 0, true);
            turn("turnflat3w", 4, 0, 0, true);
            // ---- Slab-takeoff reach trials ----
            slabCard("slabflat2", 3, 0, 0);           // slab takeoff, node-flat 2-gap (physically +0.5 rise)
            slabCard("slabflat3", 4, 0, 0);           // slab takeoff, node-flat 3-gap (the reach-reduction case)
            slabCard("slabrise1", 2, 1, 0);           // slab takeoff, rise+1 — expect the rise() gate to refuse
            // ---- Soul-sand-takeoff reach trials (slow-floor envelope row) ----
            // Soul sand is BOTH a slow floor (0.4 speed factor) AND a sunk block (collision top 14/16), so
            // the derived envelope's flat cap off it is just 1 (ParkourEnvelope.MAX_GAP[14][soul][none] =
            // flat 1). soulflat1 is inside that reduced cap -> the bot makes the OFFERED (reduced) jump;
            // soulflat2 is EXCLUDED (flat 2 not offered from soul sand) -> nav must cleanly refuse (no route
            // offered), never attempt-and-fall. The tile isolates the jump (bottomless drop on a miss), so a
            // wrongly-offered flat-2 would FAIL(fell) while the correct behaviour is a clean nav-gave-up.
            soulCard("soulflat1", 2, 0, 0);
            soulCard("soulflat2", 3, 0, 0);
            // ---- Staircase-traversal trials (directional-stair model) ----
            // A run of BOTTOM stairs FACING=EAST, each +1 up and +1 over, under a ceiling. stairup climbs +X
            // under a TIGHT 2-block ceiling (WALK fits, a JUMP's 3rd cell is blocked) — the discriminator that
            // proves the walk-up must read as a step-assist, not an Ascend jump (bug 1). stairdown descends -X;
            // its ceiling sits one higher (Descend's step-off needs 3 clear over the dest cell) so the DOWN
            // move is never head-blocked — it isolates the feet-Y / reached model (bug 2), not the jump gate.
            stairUp("stairup", 4);
            stairDown("stairdown", 4);

            // ==== PHASE 2 additions =========================================================================
            // (A) FLAT PRECISION-on-stone — the REAL overshoot validation. flat1/flat2 above use a WIDE REACH
            //     platform, so an overshoot still lands (masked). These single-block (1-wide landing, drop past
            //     it) versions FALL on an overshoot, so they only pass if the airborne servo centres the landing.
            //     flatp1 = displacement 2 (walk); flatp2 = displacement 3 (sprint).
            cardPrec("flatp1", 2);
            cardPrec("flatp2", 3);
            // (B) OVERHANG hazards — the planner jumps OVER a floor-level magma / honey block (g2 flat, so the
            //     jump-over is cheaper than walk-onto-then-jump and the planner routes the jump). Fix 3 fires the
            //     jump before the center crosses the lip, so the bot never stands on the hazard. REACH landing —
            //     the assertion is "no damage" (magma) / "still clears" (honey), not precision. The magma trial
            //     is WALKIN-ONLY (the spec's "straight path"): a REST start teleported onto the takeoff cell
            //     immediately adjacent to the magma has NO runway, so the unavoidable ~3-tick liftoff latency
            //     carries the center onto the magma before it can leave the ground (sprint) — or a sneak/slow
            //     start falls short of the gap. That is a PLANNER concern (don't offer a hazard-overfly from a
            //     no-runup standstill), parallel to the honey-gap reach limit, not a follower fix. Honey needs
            //     no such carve-out (no contact damage) and passes from both approaches.
            overhang("magmaov", 3, MAGMA, true, false);   // walkin = real PASS (zero damage)
            // magmaov.REST is a KNOWN PLANNER GAP: a REST start teleported onto the takeoff cell immediately
            // adjacent to the magma has NO runway, so the unavoidable ~3-tick liftoff latency carries the center
            // onto the magma (sprint → damage) or a slow/sneak start falls short — measured unmakeable by the
            // follower (Phase 2). It's RED as a reminder that the planner should not offer a hazard-overfly from
            // a no-runup standstill; it becomes an expected-refusal PASS once the planner arc stops offering it.
            plannerGapTrial("magmaov.rest", Approach.REST, 3, 0, 0, Template.REACH, MAGMA, true,
                    "PLANNER-GAP: hazard-overfly from no-runway standstill");
            overhang("honeyov", 3, HONEY, false, true);   // both approaches = real PASS (jump clears)
            // (C) HONEY-IN-FIRST-GAP-BLOCK across tiers (owner-requested). Honey in the first gap cell + a
            //     single-block (PRECISION) landing so a miss FALLS. The PLANNER FIX (reduced gsf-0.4 envelope is
            //     now selected when the FIRST flyover cell is slow, not only the takeoff cell) makes the planner
            //     REFUSE these over-reduced-envelope tiers — so they are now EXPECTED-REFUSAL negative-tests
            //     (nav gives up → the bot never attempts → PASS), like the 10 refusals below. Reduced caps
            //     (surface 16 / gsf-0.4): flat 2, rise 1, fall 2/2/3, diag 1. rise2 (needs rise 2), flat3
            //     (flat 3), fall4 (fall 4) exceed them; diag2 (needs diag 2) too — CONSERVATIVELY refused (a
            //     gap-2 diag WAS makeable), owner-accepted over-conservatism. They flip to attempt-and-fall
            //     FAIL only if the planner ever wrongly OFFERS one again.
            honeyGap("hgap.rise2", 3, 1, 0);
            honeyGap("hgap.flat3", 4, 0, 0);
            honeyGap("hgap.fall4", 5, -1, 0);
            honeyGap("hgap.diag2", 3, 0, 3);
            markRefusal("hgap.rise2", "hgap.flat3", "hgap.fall4", "hgap.diag2");
            markRefuseNote("hgap.diag2",
                    "conservatively refused (gap-2 diag was makeable; owner-accepted over-conservatism)");
            // ==== PHASE 2 negative-tests: the conservative-refusal invariant ================================
            // The planner rightly DECLINES these beyond-envelope / reduced-takeoff geometries (owner-confirmed
            // correct). Mark them expectRefusal so a CLEAN "nav gave up" scores PASS — and, crucially, a day the
            // planner wrongly starts OFFERING one of these impossible jumps (bot leaps to its death) scores FAIL.
            markRefusal("rise3.walkin", "rise3.rest",
                    "diag3.walkin", "diag3.rest",
                    "slabflat3.walkin", "slabflat3.rest",
                    "slabrise1.walkin", "slabrise1.rest",
                    "soulflat2.walkin", "soulflat2.rest");

            // ==== OWNER'S EXACT IN-GAME REPRODUCTION (NeoForge 1.21.11, 100% consistent void-fall) ============
            // The permanent gate for the honey-flyover-without-runup void-fall. See ownerRepro() for the geometry.
            ownerRepro();
        }

        /** Permanent regression gate: the owner's EXACT in-game honey-flyover failure, faithfully reproduced.
         *
         *  <p><b>Owner geometry</b> (VOID everywhere except 7 blocks; NeoForge 1.21.11; travel &minus;X):
         *  <pre>
         *    Y-56:  stone(83) stone(82) stone(81=spawn) HONEY(80)
         *    Y-57:                                              stone(78) stone(77=goto) stone(76)
         *    along -X:  81 stone(spawn) -> 80 HONEY -> 79 VOID gap -> 78 stone(land, 1 LOWER) -> 77 -> 76
         *  </pre>
         *  A honey-first-flyover, gap-2, descent-1 jump: the bot takes off from the SOLID stone (81), flies OVER
         *  the honey (80, node-level) and the void gap (79), and must land on the stone (78) one block lower.
         *  Crucially the bot spawns AT the takeoff cell (81), so it has essentially NO run-up (~1 block, 81&rarr;80)
         *  and launches with very little speed. Owner: "jumping over the honey without sufficient speed and
         *  falling into the void." A miss falls to its death = an unambiguous FAIL.
         *
         *  <p><b>HONEST GATE (2026-07-15).</b> These trials are now {@code honestCross}: PASS requires a REAL
         *  airborne walk-off that DROPS onto the far landing platform and then reaches a goal pushed
         *  {@link #HONEY_GOAL_PAST} cells PAST the landing edge — so a bot that merely teeters onto the honey
         *  edge and lands inside the 2.5-block arrival radius no longer scores a (false) PASS. The landing
         *  platform is extended ({@link #HONEY_LAND_LEN}) so an overshoot has runout and the far goal is real.
         *  The bot crosses via WalkOff FROM the honey (Traverse onto the honey, then advance-2/descend-1) —
         *  honey {@code reducesJump}-gates a jump, so WalkOff is the sole crosser; soul sand is NOT jump-gated,
         *  so the plan Traverses onto it and JUMPS FROM it (Parkour), the decisive honey/soul control.
         *
         *  <p>Three trials: the owner's STANDSTILL spawn ({@code REST}, ~1-block run-up, arrives at walk speed),
         *  a RUN-UP twin ({@code honeyRunup}: WALKIN + full stone runway + {@code fastEntry} sprint-arrival — does
         *  a walk-off cross CLEANLY when it reaches the lip at sprint?), and the soul-sand jump-from-sand
         *  positive control. */
        void ownerRepro() {
            ownerReproTrial("owner.honeyflyover", HONEY, false);       // standstill (owner's spawn) — the hard case
            ownerReproTrial("owner.honeyflyover.runup", HONEY, true);  // run-up (sprint arrival) — is honey solved?
            ownerReproTrial("owner.soulflyover", SOUL, false);         // control: jump-from-soul really crosses
        }

        /** One owner honey/soul crossing trial. {@code runup} = WALKIN + full stone runway + {@code fastEntry}
         *  (arrive at the lip at sprint terminal); else {@code REST} at the takeoff (the owner's standstill). */
        void ownerReproTrial(String name, BlockState gapBlock, boolean runup) {
            int[] b = nextBase();
            Trial t = new Trial(name, runup ? Approach.WALKIN : Approach.REST, -1, 0, -3, -1, 0,
                    Template.REACH, false, b[0], b[1]);
            t.ownerRepro = true;
            t.honestCross = true;                                // strict: must really cross onto the far platform
            t.honeyRunup = runup;
            t.fastEntry = runup;                                 // pin sprint terminal on the approach to the lip
            t.gapFloor = gapBlock;                               // honey/soul at the walk-off takeoff cell (owner's 80)
            // Goal pushed HONEY_GOAL_PAST cells past the landing edge (owner's 78) — beyond the 2.5-block arrival
            // radius from the honey, so ONLY a real crossing that stands on the landing platform can "arrive".
            t.goal = new BlockPos(t.landX + HONEY_GOAL_PAST * t.cdx, t.landY + 1,
                    t.landZ + HONEY_GOAL_PAST * t.cdz);
            trials.add(t);
        }

        /** Flat cardinal jump with the single-block PRECISION landing (overshoot falls), both precursors. */
        void cardPrec(String name, int jdx) {
            addTrial(name + ".walkin", Approach.WALKIN, 1, 0, jdx, 0, 0, Template.PRECISION, false);
            addTrial(name + ".rest", Approach.REST, 1, 0, jdx, 0, 0, Template.PRECISION, false);
        }

        /** A flat jump OVER a hazard block ({@code gapState}) placed in the first gap cell. REACH landing;
         *  {@code assertNoDamage} makes any HP loss a FAIL (the magma case). {@code restToo} adds the REST
         *  precursor (dropped for magma — a no-runup standstill next to magma is a planner concern). */
        void overhang(String name, int jdx, BlockState gapState, boolean assertNoDamage, boolean restToo) {
            addHazardGapTrial(name + ".walkin", Approach.WALKIN, jdx, 0, 0, Template.REACH, gapState,
                    assertNoDamage, null);
            if (restToo) {
                addHazardGapTrial(name + ".rest", Approach.REST, jdx, 0, 0, Template.REACH, gapState,
                        assertNoDamage, null);
            }
        }

        /** Honey-in-first-gap-block trial for one tier (walkin). PRECISION landing so a short jump FALLS. The
         *  planner now REFUSES these (reduced envelope on a slow first flyover), so the caller marks them
         *  {@code expectRefusal} (nav gives up → PASS); a wrongly-OFFERED route that then falls scores FAIL. */
        void honeyGap(String name, int jdx, int jdy, int jdz) {
            addHazardGapTrial(name, Approach.WALKIN, jdx, jdy, jdz, Template.PRECISION, HONEY, false, null);
        }

        /** A hazard-overfly trial that is a KNOWN PLANNER GAP (a REST magma-overhang): any FAIL is reported with
         *  {@code plannerGap} as the reason and counted separately from real pass/fail. */
        void plannerGapTrial(String name, Approach a, int jdx, int jdy, int jdz, Template t,
                BlockState gapState, boolean assertNoDamage, String plannerGap) {
            addHazardGapTrial(name, a, jdx, jdy, jdz, t, gapState, assertNoDamage, plannerGap);
        }

        void addHazardGapTrial(String name, Approach a, int jdx, int jdy, int jdz, Template t,
                BlockState gapState, boolean assertNoDamage, String plannerGap) {
            int rdx = jdx >= 0 ? 1 : -1;
            int rdz = (jdz != 0 && jdx == 0) ? (jdz >= 0 ? 1 : -1) : 0;
            // For a diagonal jump, approach along the diagonal (matches diag()); else along the jump axis.
            if (jdx != 0 && jdz != 0) { rdx = 1; rdz = 1; }
            int[] b = nextBase();
            Trial tr = new Trial(name, a, rdx, rdz, jdx, jdy, jdz, t, false, b[0], b[1]);
            tr.gapFloor = gapState;
            tr.assertNoDamage = assertNoDamage;
            tr.plannerGap = plannerGap;
            trials.add(tr);
        }

        /** Mark the named trials (already added) as conservative-refusal negative-tests: a clean "nav gave up"
         *  is their PASS; an offered route (attempt-and-fall, or reaching the goal) is their FAIL. */
        void markRefusal(String... names) {
            for (String n : names) {
                for (Trial t : trials) {
                    if (t.name.equals(n)) { t.expectRefusal = true; break; }
                }
            }
        }

        /** Append a note to a refusal trial's PASS reason (e.g. hgap.diag2 is CONSERVATIVELY refused). */
        void markRefuseNote(String name, String note) {
            for (Trial t : trials) {
                if (t.name.equals(name)) { t.refuseNote = note; break; }
            }
        }

        /** The grid base (snake-ordered) for the trial at position {@code trials.size()}. */
        int[] nextBase() {
            int i = trials.size();
            int row = i / COLS;
            int col = i % COLS;
            if ((row & 1) == 1) col = COLS - 1 - col; // snake: keep consecutive trials adjacent
            return new int[]{ BASE_X + col * STRIDE, BASE_Z + row * STRIDE };
        }

        void addTrial(String name, Approach a, int rdx, int rdz, int jdx, int jdy, int jdz,
                Template t, boolean walled) {
            int[] b = nextBase();
            trials.add(new Trial(name, a, rdx, rdz, jdx, jdy, jdz, t, walled, b[0], b[1]));
        }

        /** Cardinal-approach shape (approach dir = dominant jump axis) under both precursor conditions. */
        void card(String name, int jdx, int jdy, int jdz, Template t) {
            int rdx = jdx >= 0 ? 1 : -1;
            addTrial(name + ".walkin", Approach.WALKIN, rdx, 0, jdx, jdy, jdz, t, false);
            addTrial(name + ".rest", Approach.REST, rdx, 0, jdx, jdy, jdz, t, false);
        }

        /** Slab-takeoff variant of {@link #card}: the whole runway is bottom-slabs (surface +0.5). */
        void slabCard(String name, int jdx, int jdy, int jdz) {
            int rdx = jdx >= 0 ? 1 : -1;
            addSlabTrial(name + ".walkin", Approach.WALKIN, rdx, 0, jdx, jdy, jdz);
            addSlabTrial(name + ".rest", Approach.REST, rdx, 0, jdx, jdy, jdz);
        }

        void addSlabTrial(String name, Approach a, int rdx, int rdz, int jdx, int jdy, int jdz) {
            int[] b = nextBase();
            Trial t = new Trial(name, a, rdx, rdz, jdx, jdy, jdz, Template.REACH, false, b[0], b[1]);
            t.slabRunway = true;
            trials.add(t);
        }

        /** Soul-sand-takeoff variant of {@link #card}: the whole runway is soul sand (slow floor, 0.4 speed
         *  factor), so the bot leaves the takeoff cell with the reduced horizontal budget the envelope's
         *  soul-sand row assumes. */
        void soulCard(String name, int jdx, int jdy, int jdz) {
            int rdx = jdx >= 0 ? 1 : -1;
            addSoulTrial(name + ".walkin", Approach.WALKIN, rdx, 0, jdx, jdy, jdz);
            addSoulTrial(name + ".rest", Approach.REST, rdx, 0, jdx, jdy, jdz);
        }

        void addSoulTrial(String name, Approach a, int rdx, int rdz, int jdx, int jdy, int jdz) {
            int[] b = nextBase();
            Trial t = new Trial(name, a, rdx, rdz, jdx, jdy, jdz, Template.REACH, false, b[0], b[1]);
            t.soulRunway = true;
            trials.add(t);
        }

        /** Ascending staircase climbing +X: {@code steps} stairs, each +1 up/+1 over, under a tight 2-block
         *  ceiling. The bot walks the flat runway then up the stairs to a goal on the top platform. */
        void stairUp(String name, int steps) {
            int[] b = nextBase();
            Trial t = new Trial(name, Approach.WALKIN, 1, 0, steps, steps, 0, Template.REACH, false, b[0], b[1]);
            t.stairRun = true;
            t.stairSteps = steps;
            trials.add(t);
        }

        /** Descending staircase walked -X and down: the bot starts on the top runway and walks down {@code steps}
         *  stairs to a goal on the bottom platform. Its ceiling clears the down-step (see {@link #buildStairs}). */
        void stairDown(String name, int steps) {
            int[] b = nextBase();
            Trial t = new Trial(name, Approach.WALKIN, -1, 0, -steps, -steps, 0, Template.REACH, false, b[0], b[1]);
            t.stairRun = true;
            t.stairSteps = steps;
            trials.add(t);
        }

        void diag(String name, int jdx, int jdy, int jdz) {
            addTrial(name + ".walkin", Approach.WALKIN, 1, 1, jdx, jdy, jdz, Template.REACH, false);
            addTrial(name + ".rest", Approach.REST, 1, 1, jdx, jdy, jdz, Template.REACH, false);
        }

        /** OFFSET (c,±lat) knight's-move jump, both precursor conditions. */
        void offset(String name, int c, int lat) {
            addTrial(name + ".walkin", Approach.WALKIN, 1, 0, c, 0, lat, Template.OFFSET, false);
            addTrial(name + ".rest", Approach.REST, 1, 0, c, 0, lat, Template.OFFSET, false);
        }

        /** 90°-turn: approach along +Z, jump along +X — walkin only. */
        void turn(String name, int jdx, int jdy, int jdz, boolean walled) {
            addTrial(name + ".turn", Approach.WALKIN, 0, 1, jdx, jdy, jdz, Template.REACH, walled);
        }

        void start(MinecraftServer server) {
            this.server = server;
            if (Boolean.getBoolean("orebit.parkour.debug")) {
                Debug.ENABLED = true;
                Debug.VERBOSE = true;
            }
            try {
                this.level = server.overworld();
                Trial first = trials.get(0);
                owner = new FakePlayerEntity(server, level, new GameProfile(
                        UUID.nameUUIDFromBytes("OrebitParkour:owner".getBytes(StandardCharsets.UTF_8)),
                        "Parkour"));
                owner.setPos(first.startX, Y0 + 1, first.startZ);
                BotManager.spawnBotFor(owner);
                bot = BotManager.botFor(owner);
                if (bot == null) {
                    finish("bot never spawned");
                    return;
                }
                trace = Files.newBufferedWriter(ConfigDir.serverDir(server).resolve(TRACE_FILE),
                        StandardCharsets.UTF_8);
                trace.write("Orebit parkour course trace  (T <trial> <tick> x y z | spd vy | onGround | move)\n");
                trace.write("legend: spd = position-delta horizontal speed (b/t); TAKEOFF marks the onGround->air flip\n\n");
                OrebitCommon.LOGGER.info("[Orebit/parkour] course ready; {} trials", trials.size());
                enter(0);
            } catch (Throwable t) {
                OrebitCommon.LOGGER.error("[Orebit/parkour] setup threw", t);
                finish("setup threw " + t.getClass().getSimpleName());
            }
        }

        void enter(int i) {
            index = i;
            Trial tr = trials.get(i);
            buildTile(tr); // each trial owns a distinct grid cell — build it once on entry
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
            wentAirborne = false;
            reachedLanding = false;
            stairAirborne = false;
            takeoffSpeed = -1;
            wasGrounded = true;
            minHealth = bot.getMaxHealth();
            maxProj = -1e9;
            prevX = tr.startX;
            prevZ = tr.startZ;
            prevMove = "";
            try {
                trace.write(String.format(Locale.ROOT,
                        "== %s : approach(%d,%d) jump(%d,%d,%d) %s %s takeoff=(%d,%d,%d) land=(%d,%d,%d) goal=(%d,%d,%d)\n",
                        tr.name, tr.rdx, tr.rdz, tr.jdx, tr.jdy, tr.jdz, tr.template, tr.approach,
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
                // The first trial waits for the whole starting area to gen + nav-build; later trials only need
                // the short local settle (snake ordering keeps each teleport inside the already-built bubble).
                int target = index == 0 ? WARMUP_TICKS : SETTLE_TICKS;
                if (++settleTicks < target) return;
                settling = false;
                bot.comeTo(tr.goal);
                return;
            }

            attemptTicks++;
            trace(tr);

            // owner-gate (honeyRunup): simulate the real-play FULL-SPRINT approach — while grounded in the
            // pre-takeoff window, pin the horizontal velocity to sprint terminal along the jump axis + hold
            // sprint, so the bot enters the jump on the flatter, honey-skimming arc (the harness's walk-terminal
            // WALKIN entry clears the same jump; the real 3-stone run is a sprint). Only touches velocity/sprint.
            if (tr.fastEntry && EntityState.onGround(bot)) {
                double pj = tr.proj(bot.getX(), bot.getZ());
                if (pj > -2.0 && pj < 0.45) {
                    Vec3 dm = bot.getDeltaMovement();
                    bot.setDeltaMovement(tr.ujx * 0.2806, dm.y, tr.ujz * 0.2806);
                    bot.setSprinting(true);
                }
            }

            if (tr.stairRun) { tickStair(tr); return; }

            double proj = tr.proj(bot.getX(), bot.getZ());
            if (proj > maxProj) maxProj = proj;
            double hp = bot.getHealth();
            if (hp < minHealth) minHealth = hp;
            if (proj > 0.6) leftTakeoff = true;

            boolean fell = leftTakeoff && bot.getY() < tr.landedFeetY - 1.6;
            boolean atGoal = bot.mode() == AllyBotEntity.Mode.STAY && bot.getY() > tr.landedFeetY - 1.5;

            // honestCross tracking: a REAL crossing goes AIRBORNE past the lip, then DROPS onto the far landing
            // platform — Y falls to the landing floor (< landedFeetY+0.5, which EXCLUDES the honey top ≈
            // landedFeetY+0.94) AND proj reaches the landing (past the gap, so a honey-edge teeter at proj≈1.6
            // never qualifies). landCenterProj = the landing-cell-centre projection.
            if (!EntityState.onGround(bot) && proj > 0.6) wentAirborne = true;
            if (EntityState.onGround(bot) && bot.getY() < tr.landedFeetY + 0.5
                    && proj >= tr.landCenterProj() - 0.6) reachedLanding = true;

            // A KNOWN-PLANNER-GAP trial (honey-in-first-gap max-reach tier, or the no-runway magma-overhang) is
            // scored by the normal fell/atGoal logic below — but record() rewrites any FAIL to its PLANNER-GAP
            // reason and counts it apart from real pass/fail (so it reads as an intended RED reminder, never a
            // follower regression). It goes green only if the follower unexpectedly clears it (a real PASS).

            // NEGATIVE-TEST: a beyond-envelope geometry the planner is EXPECTED to decline. A clean refusal
            // (nav gave up, bot never took a route) is the PASS; an OFFERED route (it attempted+fell, or
            // reached the goal) is the FAIL — that would mean the planner started offering an impossible jump.
            if (tr.expectRefusal) {
                if (!bot.isAlive() || fell) {
                    record(tr, "FAIL", "route OFFERED then fell (expected a refusal)");
                    return;
                }
                if (atGoal) {
                    record(tr, "FAIL", "route OFFERED, reached goal (expected a refusal)");
                    return;
                }
                if (bot.navigator().navGaveUp()) {
                    if (attemptTicks <= NAV_RETRY_WINDOW && navRetries < MAX_NAV_RETRY) {
                        navRetries++;
                        bot.comeTo(tr.goal);
                        return;
                    }
                    record(tr, "PASS", "correctly refused (no route offered)"
                            + (tr.refuseNote != null ? " — " + tr.refuseNote : ""));
                    return;
                }
                if (attemptTicks >= ATTEMPT_BUDGET) {
                    record(tr, leftTakeoff ? "FAIL" : "PASS",
                            leftTakeoff ? "left the takeoff cell without a clean refusal (timeout)"
                                        : "refused (held at takeoff, no route taken)");
                }
                return;
            }

            // HONEST HONEY/SOUL CROSSING (2026-07-15): PASS demands a REAL airborne walk-off/jump that DROPS
            // onto the far landing platform AND reaches the far goal — a honey-edge teeter inside the 2.5-block
            // arrival radius (the old false PASS) no longer counts, because the goal is HONEY_GOAL_PAST cells
            // beyond the landing and reachedLanding requires the Y-drop + past-the-gap proj.
            if (tr.honestCross) {
                if (!bot.isAlive() || fell) {
                    record(tr, "FAIL", String.format(Locale.ROOT,
                            "fell into the void — under-reached (maxProj %.2f of %.2f)",
                            maxProj, tr.landCenterProj()));
                    return;
                }
                if (reachedLanding && atGoal) {
                    record(tr, "PASS", String.format(Locale.ROOT,
                            "real crossing onto the far landing (airborne=%s, maxProj %.2f)",
                            wentAirborne, maxProj));
                    return;
                }
                if (bot.navigator().navGaveUp()) {
                    if (attemptTicks <= NAV_RETRY_WINDOW && navRetries < MAX_NAV_RETRY) {
                        navRetries++;
                        bot.comeTo(tr.goal);
                        return;
                    }
                    record(tr, "FAIL", "nav gave up (no crossing offered)");
                    return;
                }
                if (attemptTicks >= ATTEMPT_BUDGET) {
                    record(tr, "FAIL", reachedLanding
                            ? "reached the landing but never got to the far goal (timeout)"
                            : String.format(Locale.ROOT,
                                    "never crossed — teetered/short at maxProj %.2f of %.2f (shortfall %.2f), "
                                    + "no airborne walk-off onto the landing",
                                    maxProj, tr.landCenterProj(), tr.landCenterProj() - maxProj));
                }
                return;
            }

            if (!bot.isAlive()) {
                record(tr, "FAIL", "died");
                return;
            }
            if (atGoal) {
                // Magma-overhang: reaching the goal is not enough — any HP lost means the bot stood on the
                // hazard during takeoff (Fix 3 failed), so that is a FAIL.
                if (tr.assertNoDamage && minHealth < bot.getMaxHealth() - 0.01) {
                    record(tr, "FAIL", String.format(Locale.ROOT,
                            "reached goal but took %.1f damage (stood on the hazard)",
                            bot.getMaxHealth() - minHealth));
                } else {
                    record(tr, "PASS", "reached goal");
                }
                return;
            }
            if (fell) {
                record(tr, "FAIL", "fell");
                return;
            }
            if (bot.navigator().navGaveUp()) {
                // Nav-not-yet-built after a teleport looks like a give-up; retry the goto a few times before
                // calling it a real failure (the identical jump can pass once its grid finishes building).
                if (attemptTicks <= NAV_RETRY_WINDOW && navRetries < MAX_NAV_RETRY) {
                    navRetries++;
                    bot.comeTo(tr.goal);
                    return;
                }
                record(tr, "FAIL", "nav gave up (no route offered)");
                return;
            }
            if (attemptTicks >= ATTEMPT_BUDGET) {
                record(tr, "FAIL", "timeout");
            }
        }

        /** Pass/fail for a staircase-traversal trial: unlike a jump, the bot spends the whole trial low on the
         *  stairs, so the jump-centric proj/leftTakeoff/fell logic can't be reused. PASS = arrived (mode back to
         *  STAY) at the goal height; FAIL = died, fell off the structure into the void, nav gave up, or timeout. */
        void tickStair(Trial tr) {
            if (!bot.isAlive()) {
                record(tr, "FAIL", "died");
                return;
            }
            // A step-assist WALK up/down stairs stays grounded; a JUMP leaves the ground. On the ASCENDING
            // trial that is the whole discriminator: the pre-fix model reads each +0.5 stair riser as a +1.0
            // Ascend and JUMPS the steps, so "reached the goal but went airborne on the way" is the mispriced
            // walk-up and FAILS — the fix makes the bot walk it (grounded throughout).
            boolean ascending = tr.jdy > 0;
            if (!EntityState.onGround(bot)) stairAirborne = true;
            if (bot.mode() == AllyBotEntity.Mode.STAY && bot.getY() > tr.landedFeetY - 1.5) {
                if (ascending && stairAirborne) {
                    record(tr, "FAIL", "climbed by jumping (walk-up mispriced as a jump)");
                } else {
                    record(tr, "PASS", "reached goal");
                }
                return;
            }
            int lowestFloor = Math.min(Y0, tr.landY); // runway (Y0) for stairup, bottom platform (landY) for down
            if (bot.getY() < lowestFloor - 5) {        // missed the structure entirely — a real fall to the void
                record(tr, "FAIL", "fell");
                return;
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
                record(tr, "FAIL", "timeout");
            }
        }

        void trace(Trial tr) {
            double x = bot.getX(), z = bot.getZ();
            double spd = Math.sqrt((x - prevX) * (x - prevX) + (z - prevZ) * (z - prevZ));
            Vec3 v = bot.getDeltaMovement();
            boolean onGround = EntityState.onGround(bot);
            String move = bot.lastSteerMove;
            try {
                // On a move change, dump the ACTUAL segment the planner routed (from/to cells) — so a greedy
                // diagonal corner-cut off a turn shows its real takeoff/landing cells, not the intended jump.
                if (!move.equals(prevMove)) {
                    BotNavigator nav = bot.navigator();
                    trace.write(String.format(Locale.ROOT, "  MOVE %s seg=(%d,%d,%d)->(%d,%d,%d)\n", move,
                            nav.segFromX(), nav.segFromY(), nav.segFromZ(),
                            nav.segToX(), nav.segToY(), nav.segToZ()));
                    prevMove = move;
                }
                if (wasGrounded && !onGround) {
                    takeoffSpeed = spd;
                    trace.write(String.format(Locale.ROOT, "  TAKEOFF spd=%.4f at x=%.3f z=%.3f (proj=%.3f)\n",
                            spd, x, z, tr.proj(x, z)));
                }
                trace.write(String.format(Locale.ROOT,
                        "T %-16s %3d  %.3f %.3f %.3f | %.4f %.4f | %d | %s\n",
                        tr.name, attemptTicks, x, bot.getY(), z, spd, v.y, onGround ? 1 : 0, move));
            } catch (IOException ignored) { }
            wasGrounded = onGround;
            prevX = x;
            prevZ = z;
        }

        void record(Trial tr, String result, String reason) {
            // KNOWN-PLANNER-GAP: a FAIL on such a trial is an INTENDED RED reminder, not a follower regression —
            // stamp it with the PLANNER-GAP: reason and count it apart from real pass/fail (finish() reports the
            // three buckets separately). A PASS (the follower unexpectedly cleared it) counts as a real pass.
            boolean gapFail = result.equals("FAIL") && tr.plannerGap != null;
            if (gapFail) reason = tr.plannerGap + " (shortfall " + String.format(Locale.ROOT, "%.2f",
                    tr.landCenterProj() - maxProj) + ")";
            // maxProj / shortfall / minHP are the Phase-2 diagnostics (honey-gap reach shortfall + magma HP
            // loss); harmless-but-uninformative for the stair/refusal trials.
            String proj = maxProj <= -1e8 ? "n/a" : String.format(Locale.ROOT, "%.2f", maxProj);
            String shortfall = maxProj <= -1e8 ? "n/a"
                    : String.format(Locale.ROOT, "%.2f", tr.landCenterProj() - maxProj);
            results.add(String.format(Locale.ROOT,
                    "%s = %s (%s) takeoffSpd=%s finalY=%.2f maxProj=%s shortfall=%s minHP=%.1f",
                    tr.name, result, reason,
                    takeoffSpeed < 0 ? "n/a" : String.format(Locale.ROOT, "%.4f", takeoffSpeed),
                    bot.getY(), proj, shortfall, minHealth));
            if (result.equals("PASS")) passed++; else if (gapFail) plannerGap++; else failed++;
            OrebitCommon.LOGGER.info("[Orebit/parkour] {} -> {} ({}) takeoffSpd={} finalY={}",
                    tr.name, result, reason,
                    takeoffSpeed < 0 ? "n/a" : String.format(Locale.ROOT, "%.3f", takeoffSpeed),
                    String.format(Locale.ROOT, "%.2f", bot.getY()));
            try { trace.write("  RESULT " + result + " (" + reason + ")\n\n"); } catch (IOException ignored) { }
            if (index + 1 < trials.size()) {
                enter(index + 1);
            } else {
                finish("all trials complete");
            }
        }

        /** Place a trial's blocks: runway + landing/goal geometry, one solid layer. Chunks sync-load on write. */
        void buildTile(Trial tr) {
            if (tr.ownerRepro) { buildOwnerTile(tr); return; }
            // Runway along the approach direction, ending at the takeoff cell.
            for (int k = 0; k < RUN; k++) {
                int cx = tr.baseX + k * tr.rdx;
                int cz = tr.baseZ + k * tr.rdz;
                if (tr.slabRunway) placeState(cx, Y0, cz, SLAB);
                else if (tr.soulRunway) placeState(cx, Y0, cz, SOUL);
                else if (tr.wideRunway) placeWide(cx, Y0, cz, tr.rdx, tr.rdz);
                else place(cx, Y0, cz);
            }
            // PHASE 2: a hazard block in the FIRST gap cell (magma/honey overhang, honey-in-gap diagnostic).
            // At node level Y0 (the arc passes over it); the planner overflies it, Fix 3 keeps the bot's center
            // off it during the grounded runup.
            if (tr.gapFloor != null) {
                placeState(tr.gapFloorX, Y0, tr.gapFloorZ, tr.gapFloor);
            }
            if (tr.walled) {
                // A 2-high wall along the +continuation side of the runway (every runway cell BEFORE the takeoff
                // row) blocks any diagonal corner-cut out of the runway — its corner column is walled, so the
                // DiagonalParkour candidate is rejected and only the cardinal jump from the takeoff cell remains.
                for (int k = 0; k < RUN - 1; k++) {
                    int cx = tr.baseX + k * tr.rdx + tr.cdx;
                    int cz = tr.baseZ + k * tr.rdz + tr.cdz;
                    place(cx, Y0 + 1, cz);
                    place(cx, Y0 + 2, cz);
                }
            }
            if (tr.template == Template.REACH) {
                for (int k = 0; k <= GOAL_LEN; k++) {
                    int cx = tr.landX + k * tr.cdx;
                    int cz = tr.landZ + k * tr.cdz;
                    placeWide(cx, tr.landY, cz, tr.cdx, tr.cdz); // 3-wide landing platform
                }
            } else if (tr.template == Template.OFFSET) {
                // 1-wide landing strip along the continuation axis, OFF the cardinal line — so the aligned scan
                // finds no landing (arming the offset probe) and the only reachable floor is the offset cell.
                for (int k = 0; k <= GOAL_LEN; k++) {
                    place(tr.landX + k * tr.cdx, tr.landY, tr.landZ + k * tr.cdz);
                }
            } else { // PRECISION: 1-wide landing cell + a perpendicular walkway to the goal (drop beyond it)
                place(tr.landX, tr.landY, tr.landZ);
                int px = -tr.cdz, pz = tr.cdx;
                for (int k = 1; k <= WALK; k++) place(tr.landX + k * px, tr.landY, tr.landZ + k * pz);
            }
            if (tr.stairRun) buildStairs(tr); // fill the diagonal staircase + its ceiling between the platforms
        }

        /** Fill the diagonal staircase (BOTTOM stairs FACING=EAST) between the takeoff cell and the landing,
         *  plus a following ceiling. buildTile has already laid the flat runway (start level) and the 3-wide
         *  REACH platform (end level); this bridges them with {@code stairSteps} stairs, each +1 over ({@code sx})
         *  and +1 in Y ({@code sy}).
         *
         *  <p><b>Ceiling height = the bug-1 discriminator.</b> Each step is covered {@link #STAIR_CEILING_GAP}
         *  blocks above its own floor (3 clear body cells). This is the one cover that separates a WALK from a
         *  JUMP: a vanilla jump's apex raises the head ~3.05 blocks above the feet, so the apex head clips the
         *  3-clear ceiling — while step-assist raises the head only ~0.5, which fits under it. So a bot that can
         *  ONLY jump the steps (the pre-fix model, which reads each +0.5 stair riser as a +1.0 Ascend) tries to
         *  jump, bonks the ceiling and never gains the step; a bot that reads the directional stair surface takes
         *  the step-assist WALK and climbs. (A tighter 2-clear cover would block the walk too — vanilla's
         *  step-assist transiently raises the head into the same source+3 cell a jump-block fills — so it can't
         *  demonstrate a PASS; a looser 4-clear cover lets the jump through and stops discriminating.) */
        void buildStairs(Trial tr) {
            int sx = Integer.signum(tr.jdx);        // +1 = climb +X (stairup), -1 = walk -X down (stairdown)
            int sy = Integer.signum(tr.jdy);        // +1 ascending, -1 descending
            int n = tr.stairSteps;
            for (int s = 1; s <= n; s++) {          // the stair blocks (the s=N cell coincides with landX/landY)
                placeState(tr.takeoffX + sx * s, Y0 + sy * s, tr.baseZ, STAIR_EAST);
            }
            for (int s = 0; s <= n; s++) {          // ceiling over the takeoff cell + every stair (1-wide)
                place(tr.takeoffX + sx * s, Y0 + sy * s + STAIR_CEILING_GAP, tr.baseZ);
            }
        }

        /** Lay the owner's EXACT 7-block honey-flyover course (see {@link #ownerRepro}) — nothing else, void all
         *  around, so the geometry the bot sees is byte-for-byte the owner's in-game setup. rdx=-1 (travel -X):
         *  takeoff cell = takeoffX (owner 81), two back stones behind it (owner 82,83), a single HONEY in the
         *  first flyover cell (owner 80), a void gap (owner 79), then a 1-wide landing strip ONE block lower
         *  (owner 78/77/76). */
        void buildOwnerTile(Trial tr) {
            int z = tr.takeoffZ;
            // Runway of stone ending at the takeoff cell (owner 81). A honeyRunup trial lays the FULL RUN-cell
            // runway (baseX..takeoffX) so a WALKIN spawn has real stone to sprint down before the honey; the
            // standstill (REST) trial keeps the owner's short ~3-stone approach (81,82,83).
            int backStones = tr.honeyRunup ? RUN : 3;
            for (int k = 0; k < backStones; k++) place(tr.takeoffX - k * tr.rdx, Y0, z); // rdx=-1 ⇒ +X is "behind"
            // The walk-off takeoff block (honey / soul sand — owner 80), node-level with the takeoff stone.
            placeState(tr.gapFloorX, Y0, tr.gapFloorZ, tr.gapFloor);
            // (the cell at gapFloorX+cdx == owner 79 is left VOID — the gap the walk-off crosses)
            // Landing platform ONE block lower (owner 78,77,76,… at Y-57), along the -X continuation axis —
            // extended (HONEY_LAND_LEN) so an overshoot has runout and the far honest goal is a real cell.
            for (int k = 0; k <= HONEY_LAND_LEN; k++) place(tr.landX + k * tr.cdx, tr.landY, tr.landZ + k * tr.cdz);
        }

        void place(int x, int y, int z) {
            level.setBlockAndUpdate(new BlockPos(x, y, z), FLOOR);
        }

        void placeState(int x, int y, int z, BlockState s) {
            level.setBlockAndUpdate(new BlockPos(x, y, z), s);
        }

        /** Place a cell plus its two perpendicular neighbours (a 3-wide platform along {@code (ux,uz)}). */
        void placeWide(int x, int y, int z, int ux, int uz) {
            int px = -uz, pz = ux;
            place(x, y, z);
            place(x + px, y, z + pz);
            place(x - px, y, z - pz);
        }

        void finish(String reason) {
            overallDone = true;
            Path file = ConfigDir.serverDir(server).resolve(RESULT_FILE);
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                kv(w, "completed", "true");
                kv(w, "reason", reason);
                kv(w, "trials", trials.size());
                kv(w, "passed", passed);
                kv(w, "failed", failed);                 // REAL follower failures (must be 0)
                kv(w, "knownPlannerGap", plannerGap);    // intended RED reminders, NOT follower regressions
                for (String line : results) {
                    w.write(line);
                    w.write('\n');
                }
            } catch (IOException e) {
                OrebitCommon.LOGGER.error("[Orebit/parkour] could not write {}", file, e);
            }
            try { if (trace != null) trace.close(); } catch (IOException ignored) { }
            OrebitCommon.LOGGER.info("[Orebit/parkour] DONE ({}) — {} passed / {} real-failed / {} known-planner-gap "
                    + "of {} — halting", reason, passed, failed, plannerGap, trials.size());
            server.halt(false);
            Thread exiter = new Thread(() -> {
                server.halt(true);
                System.exit(0);
            }, "orebit-parkour-exit");
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
