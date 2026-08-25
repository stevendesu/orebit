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
import com.orebit.mod.pathfinding.blockpathfinder.movements.ParkourEnvelope;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.platform.ConfigDir;
import com.orebit.mod.platform.EntityState;
import com.orebit.mod.platform.PlatformEvents;
import com.orebit.mod.worldmodel.pathing.ChunkNavLoader;
import com.orebit.mod.worldmodel.pathing.NavStore;

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
    /** Grace window (ticks past the settle target) for the nav-residency gate before the harness declares
     *  ITSELF broken — enter()'s explicit footprint build normally satisfies the gate on the first check. */
    private static final int NAV_BUILD_WAIT = 200;

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
    private static final BlockState BAMBOO = Blocks.BAMBOO.defaultBlockState();
    private static final BlockState ICE_BLK = Blocks.ICE.defaultBlockState();          // friction 0.98
    private static final BlockState BLUE_ICE_BLK = Blocks.BLUE_ICE.defaultBlockState();// friction 0.989
    /** A BOTTOM straight stair FACING EAST (+X): its HIGH 16/16 half is on +X, LOW 8/16 front on -X (verified
     *  empirically, StairVoxelProbe). Climbing +X (or descending -X) walks its low front → high back. */
    private static final BlockState STAIR_EAST = Blocks.STONE_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
            .setValue(BlockStateProperties.HALF, Half.BOTTOM)
            .setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.STRAIGHT);
    /** Climbable-transit palette (the 2026-07-31 held-jump × vine-transit elevator repro family).
     *  VINE_SOUTH clings to the wall block at z+1 (its SOUTH face); VINE_WEST to the block at x−1 —
     *  always place the supporting solid FIRST so no neighbour update pops an unsupported vine. */
    private static final BlockState VINE_SOUTH = Blocks.VINE.defaultBlockState()
            .setValue(BlockStateProperties.SOUTH, true);
    private static final BlockState VINE_WEST = Blocks.VINE.defaultBlockState()
            .setValue(BlockStateProperties.WEST, true);
    /** Persistent leaves (no decay): the incident's canopy — the cap the elevator pins under and the
     *  blocked forward face it presses into. Full-cube collision, but the REAL block for fidelity. */
    private static final BlockState LEAF = Blocks.OAK_LEAVES.defaultBlockState()
            .setValue(BlockStateProperties.PERSISTENT, true);

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

    /** Climbable-transit card shapes (see {@code buildClimb}): the held-jump × vine-transit elevator
     *  repro family. ASC_PIN = the faithful flagship pin (curtain over the takeoff column + blocked
     *  forward face + canopy cap); ASC_FACE = curtain IN the landing stance cells with an open escape
     *  (does done/failWhen ever recover?); CLIFF = vine-curtain climb-down a 4-block face (the
     *  fall-arrest + descend-beside-wall regression pin for the vine-bounce fix); LATERAL = the
     *  run-autotest-climb scenario miniaturized — a feet-level vine row on a wall face across a
     *  floorless gap, the ONLY crossing a no-capability bot has (grab + sideways ease + step-off);
     *  repro for the 2026-07-31 lateral-cling latch found when that autotest was finally re-run. */
    private enum ClimbKind { ASC_PIN, ASC_FACE, CLIFF, LATERAL, DIAG_TOP }

    /** One jump challenge: an approach direction + a jump vector + a landing template + a precursor condition,
     *  with all world geometry precomputed from its base X band. */
    private static final class Trial {
        final String name;
        boolean slabRunway;
        boolean soulRunway;             // soul-sand takeoff+runway (slow floor — tighter envelope row)
        boolean soulTakeoff;            // STONE runway but the takeoff cell ONLY is soul sand (issue-1 repro:
                                        //   +X momentum builds on stone, only the launch block is slow)
        boolean stairRun;               // a staircase-traversal trial (custom build + pass/fail), not a jump
        int stairSteps;                 // number of stair blocks in the run
        BlockState gapFloor;            // magma/honey placed in the FIRST gap cell (null = normal void gap)
        boolean fastEntry;              // owner-gate: force a full-SPRINT approach (real 3-stone run) into the jump
        boolean descendRunway;          // raised (Y0+1) approach stepping DOWN onto the takeoff cell — the
                                        //   HOT-ENTRY chained-momentum condition (owner ruling 2026-07-31)
        ClimbKind climb;                // != null: a climbable-transit card — buildClimb adds the vine/leaf
                                        //   structure onto the standard runway/landing geometry
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
        BlockState centrePost;          // != null: a 2-high stalk planted at the TAKEOFF CELL'S CENTRE —
                                        // passable to the planner, but its collision post occupies exactly
                                        // the cell centre a carry gate would hold at. See centrePostTrial.
        BlockState iceRunway;           // != null: runway AND takeoff cell are this slippery block, so the
                                        // bot must launch from ICE. See iceRestTrial.
        boolean padTakeoff;             // the takeoff cell ALONE is raised one block, so the route must
                                        // ASCEND onto a 1-wide pad and launch from ON TOP of it -- the
                                        // no-lateral-runway shape. See padParkourTrial.
        boolean jumpSpeedGate;          // STRICT verdict -- PASS requires the measured launch speed to reach
                                        // ParkourEnvelope.modelJumpTickSpeed. See offCentreTrial.
        double offCentre;               // REST spawn offset ALONG the jump axis, in blocks past the takeoff
                                        // cell's centre (0 = centre, which every other card uses).
        boolean slowStep;               // STRICT verdict — PASS requires the bot to actually STAND ON the
                                        // partial-height slow block (honey / soul sand), never merely teeter
                                        // on the full block beside it. See slowStepTrial.
        boolean honestCross;            // owner-gate: STRICT verdict — PASS requires a REAL airborne crossing that
                                        //   lands on the far platform; goal pushed well past the landing so the
                                        //   2.5-block arrival radius can't score a honey-edge teeter as "arrived".
        boolean honeyRunup;             // owner-gate: WALKIN + full stone runway + fastEntry sprint-arrival (the
                                        //   run-up twin — does the walk-off cross CLEANLY when it reaches the lip
                                        //   at/near sprint, vs the standstill spawn that arrives at walk speed?).
        BlockPos goal;                  // (non-final: ownerRepro overrides it to the owner's goto target cell)
        double startX, startZ;   // non-final: offCentreTrial shifts them along the jump axis
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
        boolean stoodOnSlow;        // (slowStep) the bot got its feet ONTO the partial-height slow block
        boolean stairAirborne;      // (stair trials) the bot left the ground during the run — i.e. it JUMPED
        double takeoffSpeed = -1;   // position-delta horizontal speed the tick the bot left the ground
        boolean wasGrounded = true;
        double prevX, prevZ;
        String prevMove = "";
        int passed, failed, plannerGap;   // plannerGap = intended RED reminders, counted apart from real fails
        double minHealth;           // lowest HP seen this trial (magma-overhang damage detection)
        double maxProj = -1e9;      // furthest along-jump-axis projection reached (honey-gap shortfall)
        // THE LAUNCH MEASUREMENT (2026-08-24). takeoffSpeed above captures EVERY grounded->airborne
        // transition, so it is overwritten by landing bounces and, on a padTakeoff card, by the route's own
        // Ascend hop onto the pad. These two capture the PARKOUR launch specifically -- the first departure
        // from inside the takeoff cell -- and are never overwritten afterwards.
        double launchSpeed = -1;         // position-delta horizontal speed on the tick it left the takeoff cell
        double launchProj = Double.NaN;  // along-axis projection it launched FROM (0.00 = the cell centre)

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
            // Fast-iteration gate: -Dorebit.parkour.souldiag builds ONLY the issue-1 soul-sand-takeoff repro
            // (stone +X runway, soul-sand takeoff cell, gap-1 NE diagonal jump over a void) so a run is ~1 min.
            if (System.getProperty("orebit.parkour.souldiag") != null) {
                soulDiag("souldiag");
                return;
            }
            // Fast-iteration gate: -Dorebit.parkour.climbonly builds ONLY the climbable-transit cards (the
            // vine-elevator repro family) so a run is ~1 min. Explicit bases — identical either way.
            if (System.getProperty("orebit.parkour.climbonly") != null) {
                climbCards();
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
            card("falld2g4", 5, -2, 0, Template.REACH);
            card("falld3g4", 5, -3, 0, Template.REACH);
            // Small-gap DEEP-drop family — the coverage hole the flagship-GOTO cliff exposed: a (gap 1, −3)
            // Parkour ((65,158,261)→(67,155,261)) grounded ONE CELL PAST its landing column (envelope
            // fail→HOLD). Long airtime + short horizontal need = the overshoot regime, so PRECISION (the
            // 1-wide ledge; an overshoot falls) — the deep-drop counterpart of fall1/fall2, closing the
            // hole between them (−1 only) and falld2g4/falld3g4 (gap-4 only, REACH).
            card("falld2g1", 2, -2, 0, Template.PRECISION);
            card("falld3g1", 2, -3, 0, Template.PRECISION); // the flagship-GOTO cliff shape
            card("falld2g2", 3, -2, 0, Template.PRECISION);
            card("falld3g2", 3, -3, 0, Template.PRECISION);
            // Diagonal (approach == jump == +X+Z). diag3 (a 3-gap, 4-step diagonal) is the ratified-OUT row
            // the design doc derives as unmakeable (DIAG_MAX 3→2) — tested HEAD-ON here to confirm it misses
            // even with a clean aligned approach (it's what the turnflat corner-cut routes).
            diag("diag1", 2, 0, 2);
            diag("diag2", 3, 0, 3);
            diag("diag3", 4, 0, 4);
            // The region-corner pin lives beside the diag cards because it shares their shape — but it is
            // a REGION-tier fixture, not a parkour one. See regionCornerPin().
            regionCornerPin();
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

            // ==== ICE-FROM-REST PINS (2026-08-24) ============================================================
            // Motionless on ice, jumps the planner admits. Expected RED at the top of each row -- see
            // iceRestTrial() for why the follower provably cannot close the gap and why this is a planner pin.
            iceRestTrial("icerest.g1f0", ICE_BLK, 2, 0, 0);        // well inside the envelope: should PASS
            iceRestTrial("icerest.g2f0", ICE_BLK, 3, 0, 0);
            iceRestTrial("icerest.g3f0", ICE_BLK, 4, 0, 0);        // max FLAT reach: expected RED
            iceRestTrial("icerest.g4f2", ICE_BLK, 5, -2, 0);       // the flagship row on ice: expected RED
            iceRestTrial("icerest.blue.g3f0", BLUE_ICE_BLK, 4, 0, 0);

            // ==== LAUNCH-SPEED PINS (2026-08-24, the flagship death at (432,-7,506)) =========================
            // The whole Parkour family was GREEN while the bot was dying in-game on a jump the envelope
            // admits, because every card approaches its takeoff over flat ground with a clean lateral
            // run-up and scores pass/fail on whether the bot LANDED. These cards attack both halves of that
            // blind spot: they start the bot where the run-up is short, and they gate on the LAUNCH SPEED
            // rather than the landing.
            //
            // offcentre.g4f2.* is the flagship jump (gap 4, fall 2) from progressively further past centre.
            // 0.208 is the exact flagship pose; the rest bracket it. EXPECT RED until the run-up re-centre
            // is fixed -- these are pins, not regressions.
            offCentreTrial("offcentre.g4f2.centre", 0.00, 5, -2, 0);   // control: must hit the model exactly
            offCentreTrial("offcentre.g4f2.p21",    0.208, 5, -2, 0);  // the literal flagship pose
            offCentreTrial("offcentre.g4f2.p30",    0.30, 5, -2, 0);
            offCentreTrial("offcentre.g4f2.p34",    0.34, 5, -2, 0);   // a hair inside TAKEOFF_EDGE (0.35)
            // A SHORT jump from the same bad pose: the launch is equally slow, but the gap is well inside
            // the envelope, so it should still land. Separates "the servo under-launches" (fails here too)
            // from "the servo under-launches only at max reach" (passes here, fails on g4f2).
            offCentreTrial("offcentre.g2f0.p30",    0.30, 3, 0, 0);
            // The in-game shape: ascend onto a 1-wide pad, launch from on top of it, no lateral runway.
            padParkourTrial("padparkour.g4f2", 5, -1, 0);
            padParkourTrial("padparkour.g2f0", 3, 1, 0);

            // ==== OWNER'S EXACT IN-GAME REPRODUCTION (NeoForge 1.21.11, 100% consistent void-fall) ============
            // The permanent gate for the honey-flyover-without-runup void-fall. See ownerRepro() for the geometry.
            ownerRepro();

            // HOT-ENTRY chained hand-offs (owner ruling 2026-07-31; see descendCard): a Descend lands the
            // bot on the takeoff already past the trigger with carried momentum. hotoffset3 is the literal
            // 2026-07-30 flagship wedge shape (Descend → offset (3,+1)); hotdiag1 is a CARDINAL +X descend
            // into the NE diagonal jump (the soulDiag approach shape — Descend is cardinal-only vocabulary,
            // so the raised runway must be cardinal). Both at natural walk/descend carry (a fastEntry pin
            // runs along the JUMP axis and would drift the bot off the 1-wide raised runway).
            //
            // EXPLICIT BASES, one stride WEST of column 0, beside the owner's permanent position — NOT
            // nextBase(). The snake's tail is NAV-DEAD (discovered 2026-07-31): every tile beyond the boot
            // view-distance bubble (snake distance ≳200 from spawn, position ≥48 / z ≥ 216) fails
            // "nav gave up (no route offered)" with ZERO searches — buildTile's sync-load on entry does not
            // route through the nav chunk-load path, so the grid never builds and the readiness gate times
            // out. That single artifact accounts for the suite's standing tail failures AND makes the
            // expect-refusal PASSes out there vacuous (they would "pass" dead or alive). Appending these
            // cards put them on dead cells; a mid-list insertion instead shifted turnrise2/turnflat2w onto
            // dead cells. West-of-column-0 cells sit inside the boot bubble and stay loaded all run via the
            // owner's chunk ticket (the owner idles at trial 0's start), and the STRIDE guarantee keeps the
            // tiles disjoint from column 0. Root-causing the dead zone is separate harness work.
            descendCard("hotoffset3", 1, 0, 3, 0, 1, Template.OFFSET, false, BASE_X - STRIDE, BASE_Z);
            descendCard("hotdiag1", 1, 0, 2, 0, 2, Template.REACH, false, BASE_X - STRIDE, BASE_Z + STRIDE);

            // ==== CLIMBABLE-TRANSIT cards (the 2026-07-31 flagship vine-elevator class) =====================
            // Vanilla reinterprets held inputs when the FEET cell is #climbable: (jumping ||
            // horizontalCollision) && onClimbable → +0.2/t climb, regardless of what the move intended.
            // ascvine.pin is the faithful flagship shape — an Ascend whose jump transits a vine curtain
            // hung over its own takeoff column, with the forward face blocked ABOVE the landing head (the
            // leaf underside) and a canopy cap: the held jump turns into a runaway climb past the target,
            // and no settled state exists for done/resetWhen/failWhen to fire on. ascvine.face puts the
            // curtain IN the landing stance cells with an open escape (contrast: does the envelope ever
            // recover?). desvine.cliff climbs DOWN a vine curtain beside a 4-block face — the fall-arrest
            // vocabulary + the descend vine-bounce regression pin (COLUMN_DEADBAND, owner ruling
            // 2026-07-31). Explicit west bases, continuing the descendCard column (nav-dead-tail rule).
            climbCards();
        }

        /** The climbable-transit card set — also the {@code climbonly} fast gate's whole course.
         *  pin runs under BOTH precursors: WALKIN passes even pre-fix (walk momentum carries the feet
         *  across the column boundary before they ever sample the takeoff curtain — measured 2026-07-31),
         *  while REST is the flagship's chained-Ascend condition (zero momentum at the face, the feet DO
         *  sample the takeoff-column vine mid-jump — the faithful elevator entry). */
        void climbCards() {
            climbCard("ascvine.pin", Approach.WALKIN, ClimbKind.ASC_PIN, 1, 1,
                    BASE_X - STRIDE, BASE_Z + 2 * STRIDE);
            climbCard("ascvine.face", Approach.WALKIN, ClimbKind.ASC_FACE, 1, 1,
                    BASE_X - STRIDE, BASE_Z + 3 * STRIDE);
            climbCard("desvine.cliff", Approach.WALKIN, ClimbKind.CLIFF, 1, -4,
                    BASE_X - STRIDE, BASE_Z + 4 * STRIDE);
            climbCard("ascvine.pin.rest", Approach.REST, ClimbKind.ASC_PIN, 1, 1,
                    BASE_X - STRIDE, BASE_Z + 5 * STRIDE);
            // jdx=5 puts the REACH landing platform (and goal) PAST a 4-cell floorless gap; buildClimb
            // hangs the feet-level vine row across it. The no-capability config makes the lateral cling
            // the only realizable crossing — the run-autotest-climb scenario, miniaturized + per-tick.
            // Diagonal off a vine TOP-OUT — the (662,70,616) long-flagship wedge, miniaturised. The
            // approach runs along +z (rdx=0, rdz=1) so the -x support column is lateral to the runway.
            climbDiagCard("diagvine.top", Approach.WALKIN, ClimbKind.DIAG_TOP, 1, 3, 1,
                    Template.OFFSET, BASE_X - STRIDE, BASE_Z + 7 * STRIDE);
            climbDiagCard("diagvine.top.rest", Approach.REST, ClimbKind.DIAG_TOP, 1, 3, 1,
                    Template.OFFSET, BASE_X - STRIDE, BASE_Z + 8 * STRIDE);
            climbCard("latvine", Approach.WALKIN, ClimbKind.LATERAL, 5, 0,
                    BASE_X - STRIDE, BASE_Z + 6 * STRIDE);
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
         *  <p><b>THE FLYOVER TRIALS ARE GONE (2026-08-24).</b> They asserted a jump OVER the honey, and
         *  {@code Parkour.candidates} now refuses that by ratified owner decision: a SLOW floor is deliberately
         *  not overflown (the {@code HoneyBlock.doSlideMovement} wall-slide steals ~88% of horizontal momentum
         *  on a fast descent beside honey and drops the bot into the void, and special-casing it is the bandaid
         *  class the model avoids). The overfly trigger is {@code (damaging && takesDamage) || topY < 12};
         *  honey's top is 15/16 and soul sand's 14/16, so neither qualifies and the scan dead-ends on them —
         *  magma, being damaging, still overflies, which is why {@code magmaov} behaves differently. The
         *  model's route for a slow obstacle is instead "Traverse ONTO it, then a reduced-envelope Parkour
         *  off it".
         *
         *  <p>So this tile now hosts the SLOW-STEP pair, which isolates step one of that route: walk from the
         *  stone takeoff onto the partial-height slow block and stand on it. They are a deliberate RED pin —
         *  the 2026-08-24 course run showed the follower cannot do it, teetering on the takeoff stone's edge
         *  at y == Y0+1.0 in a 2-tick +-0.0346 limit cycle with its centre already over the slow cell, never
         *  descending the 1/16 onto it. Until that is fixed, every slow-obstacle crossing is unreachable at
         *  its first move. See {@link #slowStepTrial}. */
        void ownerRepro() {
            slowStepTrial("slowstep.honey", HONEY);
            slowStepTrial("slowstep.soul", SOUL);
        }

        /**
         * ONE OFF-CENTRE LAUNCH TRIAL — the 2026-08-24 flagship-death pin.
         *
         * <p>Spawns AT REST inside the takeoff cell but {@code offset} blocks PAST its centre along the jump
         * axis, then asks for a jump the envelope admits. Everything else is a plain REACH card.
         *
         * <p><b>Why the offset alone decides it.</b> {@code ParkourEnvelope}'s reach table is derived from
         * {@code vRunup} — the ground speed after accelerating from REST across {@code RUNUP_BLOCKS = 0.5}
         * — and the takeoff trigger is POSITIONAL at {@code TAKEOFF_EDGE = 0.35} past centre. From the
         * centre that budget buys THREE run-up ticks and the launch speed comes out at {@code 0.4557},
         * exactly {@link ParkourEnvelope#modelJumpTickSpeed}. Start 0.208 past centre and only ONE tick fits
         * before the trigger fires, so the launch is {@code 0.4069} — 89%. On a max-reach gap the missing
         * 11% is the difference between clearing the far lip and clipping its near face 0.07 blocks low,
         * which is precisely how the bot died at (432,-7,506) on the 2026-08-24 long flagship.
         *
         * <p>{@code Parkour}'s run-up phase already owns a re-centre for exactly this case — it should walk
         * the bot back to centre and only then launch — so a PASS here means that re-centre armed and a
         * FAIL means it did not. The verdict deliberately does NOT accept a lucky landing: it gates on the
         * measured launch speed (see the {@code jumpSpeedGate} branch), because an 11% shortfall is
         * invisible to a pass/fail-on-landing test until the geometry happens to cross the cliff into a
         * miss. That is why the whole green ParkourCourse family missed this.
         */
        void offCentreTrial(String name, double offset, int jdx, int jdy, int jdz) {
            int[] b = nextBase();
            Trial t = new Trial(name, Approach.REST, 1, 0, jdx, jdy, jdz, Template.REACH, false, b[0], b[1]);
            t.offCentre = offset;
            t.jumpSpeedGate = true;
            t.startX += offset * t.ujx;
            t.startZ += offset * t.ujz;
            trials.add(t);
        }

        /**
         * ONE PAD-TAKEOFF TRIAL — the in-game shape the flagship actually died on.
         *
         * <p>Flat approach, then the takeoff cell alone raised one block ({@link #buildTile}'s
         * {@code padTakeoff} branch), so the route must ASCEND onto a 1-wide pad and the bot arrives
         * standing ON TOP of it with no lateral runway at all. In the flagship the pad was cobblestone the
         * bot PLACED on a ledge lip; a pre-built pad reproduces the same arrival without depending on the
         * planner choosing to place, which keeps the fixture deterministic.
         *
         * <p>NOTE the frame: {@code jdy} is measured from {@code Y0}, but the pad sits at {@code Y0 + 1}, so
         * the drop RELATIVE TO THE TAKEOFF is {@code jdy - 1}. The flagship jump was gap-4 fall-2, which is
         * {@code jdx = 5, jdy = -1} here.
         */
        void padParkourTrial(String name, int jdx, int jdy, int jdz) {
            int[] b = nextBase();
            Trial t = new Trial(name, Approach.WALKIN, 1, 0, jdx, jdy, jdz, Template.REACH, false, b[0], b[1]);
            t.padTakeoff = true;
            t.jumpSpeedGate = true;
            trials.add(t);
        }

        /**
         * ONE ICE-FROM-REST TRIAL — can the follower launch a planner-emitted jump off ice at all?
         *
         * <p>Spawns MOTIONLESS at the centre of an ICE takeoff cell, on an ice runway, and asks for a jump
         * the planner admits. This is the one shape nothing else covers: the existing IceParkourCourse cards
         * either LAND on ice (their takeoff speed is 0.4775, bit-identical to the stone control, which is how
         * we know their takeoff is stone) or take off from ice CARRYING momentum from a previous hop
         * (ice.chain.g3 launches at 0.3934). Taking off from ice at REST is untested, and it is reachable in
         * play: {@code Fall} re-centres on its target and zeroes horizontal momentum, so any Fall-then-Parkour
         * on ice lands here, as does a seam pause, an Ascend onto ice, or a plan that simply starts there.
         *
         * <p><b>These are expected RED at the top of each row, and the planner is the reason.</b>
         * {@code ParkourEnvelope.MAX_GAP} is indexed {@code [startTopY][gsfBucket][occBucket]} with NO
         * friction dimension, {@code A_G} is baked at stone friction 0.6, and {@code SURFACE_SLIPPERY} was
         * deleted from NavBlock on 2026-08-10 as dead. So the planner offers stone-length jumps on ice.
         *
         * <p>The follower CANNOT make up the difference, and that is arithmetic rather than a servo bug.
         * Vanilla scales input accel by {@code 0.216/f^3} and drag by {@code f*0.91}, so ice accelerates
         * 4.36x slower than stone (0.02924 vs 0.12740 per tick) while topping out at almost the same speed
         * (0.2702 vs 0.2806). From rest, the run-up available inside ONE cell is at most ~0.99 blocks --
         * back edge at local 0.3 (body-limited, half-width 0.3) to the last supported tick near local 1.29 --
         * which reaches 84% of the stone launch speed. Even granting the best case BOTH levers (start at the
         * back edge AND delay takeoff to the lip, worth +0.231 blocks of launch position), total reach lands
         * 0.19-0.44 blocks SHORT of the stone model across every flight time, and the deficit grows with
         * time aloft. Matching stone needs 4.66 blocks / 25 ticks of run-up -- several cells, not one.
         *
         * <p>So a red card here is a PLANNER pin, not a follower one: the fix is a friction dimension on the
         * envelope (surface values 2-3 are still free, so restoring SURFACE_SLIPPERY costs no bits), not a
         * cleverer servo. Sub-maximal rows have margin and should pass; the split between them is the actual
         * measurement this card exists to take.
         */
        void iceRestTrial(String name, BlockState ice, int jdx, int jdy, int jdz) {
            int[] b = nextBase();
            Trial t = new Trial(name, Approach.REST, 1, 0, jdx, jdy, jdz, Template.REACH, false, b[0], b[1]);
            t.iceRunway = ice;
            t.jumpSpeedGate = true;
            trials.add(t);
        }

        /**
         * ONE CENTRE-POST TURN TRIAL — does a movement ENTER a cell whose blocking occupant it planned to
         * break but never broke?
         *
         * <p>An L-shaped route: the runway approaches along +Z, then the plan turns 90 degrees and Traverses
         * +X out of the corner cell, which holds a 2-high BAMBOO stalk. Bamboo classifies as a FULL blocking
         * cube ({@code SHAPE_OTHER}, solid, {@code COLLISION_BIT}, and {@code topY = 16} because its
         * collision {@code box(6.5,0,6.5,9.5,16,9.5)} reaches the top of the cell), so the planner correctly
         * prices breaking it: on the 2026-08-25 long flagship the emitted step was
         * {@code Traverse d(0,0,1) ->(358,64,499) [brk=2 plc=0]} with
         * {@code planned edits: BREAK(358,65,499) BREAK(358,66,499)} — both body cells.
         *
         * <p><b>The bug is that the breaks never executed and the Traverse advanced anyway.</b> Zero
         * {@code break executed} lines at either coordinate, the stalk still standing in the saved world,
         * and 16 successful breaks elsewhere in the same run — so breaking works, these two just did not
         * happen. The phase declares {@code need(AIR)} on exactly those two cells (floor-frame
         * {@code fy+1}/{@code fy+2} over floor {@code (358,64,499)}), which should have held it.
         *
         * <p>What the flagship then SHOWED is only the downstream symptom: standing inside the stalk, the
         * bot's carry gate armed and {@code SteerControl.stepOffGate} held at {@code footX()+0.5,
         * footZ()+0.5} — the cell centre, which is precisely where bamboo's collision post sits
         * ({@code 0.406..0.594} in X and Z). It drove {@code fwd=1.00} into the stalk with
         * {@code hcol=true} for the remaining ~47k ticks and never emitted a {@code step FAILED}, because a
         * hold is not a failure. Do NOT read that as a {@code stepOffGate} defect — the gate was handed a
         * bot standing somewhere no plan ever said it should be.
         *
         * <p>The turn is load-bearing: {@code stepOffGate} only runs behind {@code carryGateArmed}, which
         * needs CROSS-AXIS carry, and a 90-degree entry is the only way a Traverse gets it (hence
         * {@code arrestCarryFrom} on {@code k == 1}). A bot merely spawned on the corner cell starts at rest
         * and reproduces nothing — which is why a {@code -Start} coordinate could not stand in for this tile.
         *
         * <p>Deterministic on purpose: bamboo GROWS on random ticks, so the flagship reaches this geometry
         * by luck and with a different stalk height every run.
         */
        void centrePostTrial(String name, BlockState post) {
            int[] b = nextBase();
            // rdx=0, rdz=1: approach along +Z. jdx=1: leave along +X. The 90-degree turn is the point.
            Trial t = new Trial(name, Approach.WALKIN, 0, 1, 1, 0, 0, Template.REACH, false, b[0], b[1]);
            t.centrePost = post;
            trials.add(t);
        }

        /** One SLOW-STEP trial: walk from a full stone takeoff onto the adjacent PARTIAL-HEIGHT slow block
         *  (honey 15/16, soul sand 14/16) and stand on it. Reuses {@code buildOwnerTile}'s geometry — runway,
         *  takeoff stone, the slow block in the next cell, a void, then a landing strip — but the goal is the
         *  SLOW BLOCK ITSELF, so the trial isolates the single Traverse the planner's slow-obstacle route
         *  depends on. Partial top ⇒ standing on it puts the feet cell at Y0, the block's own cell. */
        void slowStepTrial(String name, BlockState slowBlock) {
            int[] b = nextBase();
            Trial t = new Trial(name, Approach.REST, -1, 0, -3, -1, 0, Template.REACH, false, b[0], b[1]);
            t.ownerRepro = true;   // lay buildOwnerTile's terrain
            t.slowStep = true;     // strict: feet actually ON the slow block, not a teeter beside it
            t.gapFloor = slowBlock;
            // Goal is the FAR LANDING, not the slow block itself. Aiming at the slow cell directly makes the
            // planner refuse outright ("nav gave up", maxProj 0.00) -- a partial block's own cell is not a
            // goal it will path into -- which would test nothing. Aimed past the gap, the planner produces
            // exactly the route its slow-obstacle decision documents: Traverse ONTO the slow block, then a
            // reduced-envelope Parkour off it. stoodOnSlow then pins STEP ONE of that route independently of
            // whether the crossing ever completes.
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

        /** ISSUE-1 repro: a STONE +X runway (momentum builds on stone) ending at a SINGLE soul-sand TAKEOFF cell,
         *  then a gap-1 NE diagonal jump (jump vector (2,0,2)) onto a stone REACH landing, with the diagonal gap
         *  cell left VOID (a miss falls → {@code fell} FAIL). WALKIN only — the +X runup IS the condition. The
         *  bot must redirect its +X momentum into an NE launch off the slow soul takeoff; the suspicion is it
         *  walks off the +X edge of the soul into the void instead of launching NE. The {@code soulTakeoff} flag
         *  keeps the runway stone and lays soul on the takeoff cell only, and suppresses the 3-wide runway (so
         *  there is no stone side-cell to corner-cut the diagonal from — the jump MUST come off the soul cell).
         *
         *  <p>Template OFFSET (not REACH) lays a 1-WIDE landing strip at (landX,landZ) running +X, so the ONLY
         *  reachable landing is the intended gap-1 diagonal off the soul cell (14,8)->(16,10). A 3-wide REACH
         *  platform instead let the planner corner-cut a gap-2 diagonal FROM the stone runway cell (13,8) onto
         *  the platform's far corner (16,11), sidestepping the soul takeoff entirely (observed: it PASSED). */
        void soulDiag(String name) {
            int[] b = nextBase();
            Trial t = new Trial(name + ".walkin", Approach.WALKIN, 1, 0, 2, 0, 2, Template.OFFSET, false, b[0], b[1]);
            t.soulTakeoff = true;
            trials.add(t);
            // STANDSTILL twin (owner's "Setup A"): IDENTICAL jump/landing geometry (gap-1 NE diagonal jump
            // (2,0,2) off the soul-sand takeoff cell onto the 1-wide OFFSET landing strip over a void, same goal),
            // but Approach.REST spawns the bot ON the soul takeoff cell AT REST (setDeltaMovement ZERO, zero
            // horizontal momentum) — no +X runup. The soulTakeoff flag still lays soul on the takeoff cell only.
            // This isolates THE question: is the diagonal-off-soul jump makeable from a standstill (→ walkin's
            // failure is driver-side runup-momentum) or does it miss too (→ physically unmakeable off a slow
            // block = a pathing problem)? A miss falls into the void (fell → FAIL), a make reaches the goal (PASS).
            int[] b2 = nextBase();
            Trial s = new Trial(name + ".standstill", Approach.REST, 1, 0, 2, 0, 2, Template.OFFSET, false, b2[0], b2[1]);
            s.soulTakeoff = true;
            trials.add(s);
            // HAZARD FLYOVER twin: a gap-1 NE diagonal (2,0,2), +X runup, 1-wide OFFSET landing over a void, with
            // MAGMA laid in the DIAGONAL gap cell (gapFloorX,gapFloorZ) the arc flies over — the diagonal-parkour
            // counterpart of the cardinal magma-overhang. A 1-wide STONE runway/takeoff (NOT soulTakeoff — soul's
            // 0.875 collision top sits BELOW the magma's full 1.0 top, so a soul takeoff would rest the bot's 0.6
            // hitbox corner UP on the taller magma and clip it for reasons unrelated to the driver; a stone takeoff
            // is the fair flyover test, same height as the magma). This confirms the runup fix (velocity alignment
            // + Fix-3 early takeoff) keeps the bot's grounded center off the magma and clears it mid-arc.
            // assertNoDamage → any HP lost is a FAIL (minHP must stay 20); a make reaches the goal.
            int[] b3 = nextBase();
            Trial m = new Trial(name + ".magma", Approach.WALKIN, 1, 0, 2, 0, 2, Template.OFFSET, false, b3[0], b3[1]);
            m.gapFloor = MAGMA;
            m.assertNoDamage = true;
            trials.add(m);
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
            addDiagTrial(name + ".walkin", Approach.WALKIN, jdx, jdy, jdz);
            addDiagTrial(name + ".rest", Approach.REST, jdx, jdy, jdz);
        }

        /**
         * A DIAGONAL-approach trial, with its tile nudged OFF the region-corner degeneracy.
         *
         * <p>A (+1,+1) diagonal run-in from {@code (bx,bz)} steps through blocks {@code (bx+k, bz+k)}, so it
         * crosses its x REGION boundary at {@code k = (16 - bx%16) % 16} and its z boundary at
         * {@code k = (16 - bz%16) % 16} ({@code RegionAddress.LEAF_SIZE == 16}). Those coincide exactly when
         * {@code bx % 16 == bz % 16} — and on that one step the chain moves CORNER-TO-CORNER between two
         * diagonally-adjacent regions, entering neither orthogonal neighbour.
         *
         * <p>The region tier's adjacency is strictly 6-FACE ({@code for (int f = 0; f < 6; f++)} in
         * RegionPathfinder, FragmentBuilder, InvalidationRollup and PyramidMerger), so no edge exists for
         * that hop. When both corner-adjacent regions are also untraversable under the bot's caps — pure-air
         * neighbours with no place capability, the case here — region A* returns FAIL, the skeleton is NONE,
         * and the bot refuses to move AT ALL even though block-tier A* paths the chain happily.
         *
         * <p>That is a REAL bot bug, and it is pinned on purpose by {@code regioncorner.walkin} below. It is
         * emphatically NOT what the diag cards test — they test diagonal PARKOUR — and it reached them only
         * because a 2026-08-24 trial-list edit shifted diag1's tile from x=60 (60%16=12 vs 138%16=10, two
         * separate face crossings) onto x=138 (10 vs 10, one corner crossing). One block of z decouples the
         * two crossings again; applied only when they would collide, so every other tile is untouched.
         */
        void addDiagTrial(String name, Approach a, int jdx, int jdy, int jdz) {
            int[] b = nextBase();
            if (Math.floorMod(b[0] - b[1], RegionAddress.LEAF_SIZE) == 0) {
                b[1] += 1;
            }
            trials.add(new Trial(name, a, 1, 1, jdx, jdy, jdz, Template.REACH, false, b[0], b[1]));
        }

        /**
         * REGION-CORNER PIN (2026-08-24) — deliberately placed ON the degeneracy {@link #addDiagTrial}
         * avoids, so the diagonal run-in must cross a region CORNER.
         *
         * <p>Geometrically trivial: a 1-wide diagonal stone chain with open air above, which block-tier A*
         * paths as six plain {@code Diagonal} steps. It fails at the REGION tier, which has no 6-face edge
         * for the corner hop and no traversable intermediate (both corner-adjacent regions are pure air and
         * the trial's bot cannot place). Expected verdict today: {@code nav gave up (no route offered)},
         * with the bot never leaving its spawn block — a RED pin, not a marked refusal, because the bot
         * genuinely should be able to walk this.
         *
         * <p>Base is pinned OUTSIDE the snake grid (negative, like {@code hotdiag1}'s explicit base) so the
         * degeneracy is a property of the fixture rather than of wherever the trial list happens to put it:
         * {@code -18 % 16 == -18 % 16}, and the chain's x and z crossings coincide at {@code k = 2}.
         */
        void regionCornerPin() {
            trials.add(new Trial("regioncorner.walkin", Approach.WALKIN, 1, 1, 2, 0, 2,
                    Template.REACH, false, BASE_X - STRIDE, BASE_Z - STRIDE));
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

        /** HOT-ENTRY chained hand-off (owner ruling 2026-07-31): a RAISED (Y0+1) runway steps DOWN onto
         *  the takeoff cell immediately before the jump, so the bot grounds on the takeoff already past
         *  the takeoff trigger with descend-carried momentum. Pre-fix, the runner's drive-then-advance
         *  gap never pressed jump — the bot walked straight off the platform (the 2026-07-30 23:48:47
         *  flagship wedge, a Descend chained into an offset (3,+1) Parkour). WALKIN-only — the chained
         *  momentum IS the condition. The offset/diagonal shapes are deliberate: no DIRECT falling jump
         *  from the raised runway exists in the movement vocabulary (falling is aligned-only,
         *  offset/diagonal are flat-only), so the planner MUST chain Descend → Parkour. */
        void descendCard(String name, int rdx, int rdz, int jdx, int jdy, int jdz, Template t,
                boolean fast, int baseX, int baseZ) {
            Trial tr = new Trial(name + ".walkin", Approach.WALKIN, rdx, rdz, jdx, jdy, jdz, t,
                    false, baseX, baseZ);
            tr.descendRunway = true;
            tr.fastEntry = fast;
            trials.add(tr);
        }

        /** One climbable-transit card: the standard +X runway/REACH geometry (a unit "jump" of
         *  {@code (1, jdy, 0)} — for CLIFF the drop, for the ascvine family the one-up step), plus the
         *  vine/leaf structure {@code buildClimb} lays for its {@link ClimbKind}. Explicit base — these
         *  live in the west column, never on the snake (the nav-dead-tail rule, see descendCard). */
        void climbCard(String name, Approach a, ClimbKind kind, int jdx, int jdy, int baseX, int baseZ) {
            Trial tr = new Trial(name, a, 1, 0, jdx, jdy, 0, Template.REACH, false,
                    baseX, baseZ);
            tr.climb = kind;
            trials.add(tr);
        }

        /** A {@link ClimbKind} card with a DIAGONAL jump vector and an explicit template — {@link
         *  #climbCard} hardcodes {@code jdz = 0} and {@code Template.REACH}, neither of which works for
         *  {@link ClimbKind#DIAG_TOP} (see the case in {@code buildClimb} for why REACH voids the test). */
        void climbDiagCard(String name, Approach a, ClimbKind kind, int jdx, int jdy, int jdz,
                Template template, int baseX, int baseZ) {
            Trial tr = new Trial(name, a, 0, 1, jdx, jdy, jdz, template, false, baseX, baseZ);
            tr.climb = kind;
            trials.add(tr);
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
                trace.write("Orebit parkour course trace  (T <trial> <tick> x y z | spd vy | vx vz | onGround"
                        + " | j c h | move)\n");
                trace.write("legend: spd = position-delta horizontal speed (b/t); TAKEOFF marks the onGround->air"
                        + " flip; j = jump input held, c = onClimbable (feet cell), h = horizontalCollision —"
                        + " j/h with c=1 is the vanilla +0.2/t climb capture\n\n");
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
            // NAV-DEAD-TAIL FIX (2026-07-31): a tile beyond the boot view-distance bubble writes its
            // blocks fine (transient sync chunk fetch) but never fires a durable CHUNK_LOAD, so the nav
            // pipeline never builds it — every search there reads AIR, the readiness gate times out, and
            // the verdict is a silent "nav gave up" (or a VACUOUS expectRefusal PASS: refusal-by-dead-
            // grid). Build the tile's chunk footprint NOW, synchronously, over the just-written blocks.
            navBuildFootprint(tr);
            bot.reviveIfDead();
            bot.setHealth(bot.getMaxHealth());
            bot.setMode(AllyBotEntity.Mode.STAY);
            bot.setPos(tr.startX, Y0 + 1 + (tr.descendRunway ? 1 : 0), tr.startZ); // raised-runway spawn +1
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
            stoodOnSlow = false;
            stairAirborne = false;
            takeoffSpeed = -1;
            launchSpeed = -1;
            launchProj = Double.NaN;
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
                // Nav-residency gate (the BoxedInCourse pattern): never issue the goto until the grid around
                // BOTH endpoints is built — a tick-counted settle alone converts an unbuilt tile into a silent
                // "nav gave up" FAIL or a vacuous refusal PASS. enter()'s explicit footprint build makes this
                // pass immediately; if it still holds past the grace window the HARNESS is broken, and that is
                // its own explicit verdict, never a bot verdict.
                if (!navReadyAround(tr)) {
                    if (settleTicks >= target + NAV_BUILD_WAIT) {
                        record(tr, "FAIL", "HARNESS: nav never built around the tile (waited "
                                + settleTicks + "t)");
                    }
                    return;
                }
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

            // slowStep tracking: the bot has genuinely STEPPED DOWN onto the partial-height slow block only
            // when it is grounded IN that cell BELOW the neighbouring full block's top. This is exactly the
            // discriminator the 2026-08-24 course run needed: a bot whose CENTRE is over the slow cell while
            // its 0.6-wide box still overlaps the takeoff stone stays supported at y == Y0 + 1.0, so a
            // cell-only test would score that teeter as success. Honey's top is 15/16 and soul sand's 14/16,
            // so a real step-down always reads strictly below Y0 + 0.99.
            if (EntityState.onGround(bot)
                    && (int) Math.floor(bot.getX()) == tr.gapFloorX
                    && (int) Math.floor(bot.getZ()) == tr.gapFloorZ
                    && bot.getY() < Y0 + 0.99) {
                stoodOnSlow = true;
            }

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
            // LAUNCH-SPEED GATE (2026-08-24). PASS requires BOTH that the bot reached the landing AND that
            // it launched at the speed ParkourEnvelope's reach table is derived from. The second half is the
            // point: an 11% launch shortfall clears every gap except the max-reach ones, so a landing-only
            // verdict reports GREEN right up until the geometry crosses into a miss -- which is exactly how
            // the whole Parkour family stayed green while the bot died in-game at (432,-7,506).
            //
            // The floor is 97% of the model rather than 100% because the launch is measured by POSITION
            // DELTA over one tick (the only frame-safe way to read it -- the exec log's vel field is
            // post-friction deltaMovement, which is what produced a wrong diagnosis the first time round),
            // and a dead stop at centre reproduces the model to four decimals, so the true margin is far
            // tighter than 3%.
            if (tr.jumpSpeedGate) {
                double model = ParkourEnvelope.modelJumpTickSpeed(1.0);
                if (!bot.isAlive() || fell) {
                    record(tr, "FAIL", String.format(Locale.ROOT,
                            "fell — launched %.4f from proj %.3f (model %.4f, %.0f%%)",
                            launchSpeed, launchProj, model, 100.0 * launchSpeed / model));
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
                if (atGoal) {
                    if (launchSpeed < 0) {
                        record(tr, "FAIL", "reached the goal without ever leaving the takeoff cell airborne");
                    } else if (launchSpeed < 0.97 * model) {
                        record(tr, "FAIL", String.format(Locale.ROOT,
                                "landed, but UNDER-LAUNCHED: %.4f from proj %.3f (model %.4f, %.0f%%)",
                                launchSpeed, launchProj, model, 100.0 * launchSpeed / model));
                    } else {
                        record(tr, "PASS", String.format(Locale.ROOT,
                                "launched %.4f from proj %.3f (model %.4f, %.0f%%)",
                                launchSpeed, launchProj, model, 100.0 * launchSpeed / model));
                    }
                    return;
                }
                if (attemptTicks >= ATTEMPT_BUDGET) {
                    record(tr, "FAIL", String.format(Locale.ROOT,
                            "timeout — launched %s from proj %s (model %.4f), maxProj %.2f of %.2f",
                            launchSpeed < 0 ? "never" : String.format(Locale.ROOT, "%.4f", launchSpeed),
                            Double.isNaN(launchProj) ? "n/a" : String.format(Locale.ROOT, "%.3f", launchProj),
                            model, maxProj, tr.landCenterProj()));
                }
                return;
            }

            // SLOW-STEP (2026-08-24): PASS demands the bot actually stand ON the partial-height slow block.
            // Deliberately a RED pin at introduction — the follower cannot do this yet (it teeters on the
            // takeoff stone's edge in a 2-tick limit cycle and never descends the 1/16), and the planner's
            // own documented route for a slow obstacle is "Traverse onto it, then a reduced-envelope Parkour
            // off it", so step one failing blocks the whole intent.
            if (tr.slowStep) {
                if (!bot.isAlive() || fell) {
                    record(tr, "FAIL", "fell off the slow block");
                    return;
                }
                if (stoodOnSlow) {
                    record(tr, "PASS", "stepped down onto the slow block");
                    return;
                }
                if (bot.navigator().navGaveUp()) {
                    if (attemptTicks <= NAV_RETRY_WINDOW && navRetries < MAX_NAV_RETRY) {
                        navRetries++;
                        bot.comeTo(tr.goal);
                        return;
                    }
                    record(tr, "FAIL", "nav gave up (no step-on offered)");
                    return;
                }
                if (attemptTicks >= ATTEMPT_BUDGET) {
                    record(tr, "FAIL", String.format(Locale.ROOT,
                            "never stepped onto the slow block — teetered at y=%.3f (needs < %.2f)",
                            bot.getY(), Y0 + 0.99));
                }
                return;
            }

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
                if (wasGrounded && !onGround && launchSpeed < 0
                        && Math.abs(tr.proj(prevX, prevZ)) <= 0.6) {
                    launchSpeed = spd;                       // left the ground from INSIDE the takeoff cell
                    launchProj = tr.proj(prevX, prevZ);      // => this departure IS the parkour launch
                }
                if (wasGrounded && !onGround) {
                    takeoffSpeed = spd;
                    trace.write(String.format(Locale.ROOT,
                            "  TAKEOFF spd=%.4f at x=%.3f z=%.3f (proj=%.3f) vx=%.4f vz=%.4f\n",
                            spd, x, z, tr.proj(x, z), v.x, v.z));
                }
                trace.write(String.format(Locale.ROOT,
                        "T %-16s %3d  %.3f %.3f %.3f | %.4f %.4f | vx=%.4f vz=%.4f | %d | j=%d s=%d c=%d h=%d | %s\n",
                        tr.name, attemptTicks, x, bot.getY(), z, spd, v.y, v.x, v.z, onGround ? 1 : 0,
                        bot.jumpHeld() ? 1 : 0, bot.sneakHeld() ? 1 : 0, bot.onClimbable() ? 1 : 0,
                        bot.horizontalCollision ? 1 : 0, move));
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

        /** Synchronously nav-build every chunk a trial can touch: the bounding box over its base, takeoff,
         *  landing and goal cells, padded by the readiness ring — so the settle gate's {@code ringBuilt}
         *  passes on its first check for BOTH endpoints. Already-built chunks are skipped (the near-spawn
         *  common case); an unbuilt one runs the exact per-chunk path the tick drain runs. */
        void navBuildFootprint(Trial tr) {
            int pad = ConfigLoader.config().navReadyRadiusChunks() + 1;
            int minX = Math.min(Math.min(tr.baseX, tr.takeoffX), Math.min(tr.landX, tr.goal.getX()));
            int maxX = Math.max(Math.max(tr.baseX, tr.takeoffX), Math.max(tr.landX, tr.goal.getX()));
            int minZ = Math.min(Math.min(tr.baseZ, tr.takeoffZ), Math.min(tr.landZ, tr.goal.getZ()));
            int maxZ = Math.max(Math.max(tr.baseZ, tr.takeoffZ), Math.max(tr.landZ, tr.goal.getZ()));
            for (int cx = (minX >> 4) - pad; cx <= (maxX >> 4) + pad; cx++) {
                for (int cz = (minZ >> 4) - pad; cz <= (maxZ >> 4) + pad; cz++) {
                    ChunkNavLoader.buildNow(level, cx, cz);
                }
            }
        }

        /** The settle gate's residency test: the readiness ring around the bot's start AND the goal. */
        boolean navReadyAround(Trial tr) {
            int r = ConfigLoader.config().navReadyRadiusChunks();
            return NavStore.ringBuilt(level, ((int) Math.floor(tr.startX)) >> 4,
                            ((int) Math.floor(tr.startZ)) >> 4, r)
                    && NavStore.ringBuilt(level, tr.goal.getX() >> 4, tr.goal.getZ() >> 4, r);
        }

        /** Place a trial's blocks: runway + landing/goal geometry, one solid layer. Chunks sync-load on write. */
        void buildTile(Trial tr) {
            if (tr.ownerRepro) { buildOwnerTile(tr); return; }
            // Runway along the approach direction, ending at the takeoff cell.
            for (int k = 0; k < RUN; k++) {
                int cx = tr.baseX + k * tr.rdx;
                int cz = tr.baseZ + k * tr.rdz;
                if (tr.iceRunway != null) placeState(cx, Y0, cz, tr.iceRunway);
                else if (tr.slabRunway) placeState(cx, Y0, cz, SLAB);
                else if (tr.soulRunway) placeState(cx, Y0, cz, SOUL);
                // soulTakeoff: 1-wide STONE runway with soul sand ONLY on the last (takeoff) cell. Placed BEFORE
                // the wideRunway branch so no stone side-cell exists to corner-cut the diagonal from.
                else if (tr.soulTakeoff) placeState(cx, Y0, cz, k == RUN - 1 ? SOUL : FLOOR);
                // descendRunway (owner ruling 2026-07-31): RAISED approach stepping DOWN onto the takeoff
                // cell right before the jump — the planner must chain Descend into the Parkour, grounding
                // the bot on the takeoff with carried momentum (the hot-entry condition).
                else if (tr.descendRunway) place(cx, k == RUN - 1 ? Y0 : Y0 + 1, cz);
                // padTakeoff: the MIRROR of descendRunway -- flat approach, then the takeoff cell ALONE
                // raised one block, so the route must ASCEND onto it and the bot arrives standing ON TOP of
                // a 1-wide pad rather than running across it. Deliberately in this 1-wide branch chain
                // (ahead of wideRunway) so the pad stays 1x1: a 3-wide takeoff would hand the bot lateral
                // room the in-game shape never has.
                else if (tr.padTakeoff) place(cx, k == RUN - 1 ? Y0 + 1 : Y0, cz);
                else if (tr.wideRunway) placeWide(cx, Y0, cz, tr.rdx, tr.rdz);
                else place(cx, Y0, cz);
            }
            // PHASE 2: a hazard block in the FIRST gap cell (magma/honey overhang, honey-in-gap diagnostic).
            // At node level Y0 (the arc passes over it); the planner overflies it, Fix 3 keeps the bot's center
            // off it during the grounded runup.
            // A CENTRE POST in the takeoff cell (2026-08-25). Planted AFTER the runway so it sits on
            // top of the takeoff floor rather than replacing it. Two high = the bot's full body.
            if (tr.centrePost != null) {
                placeState(tr.takeoffX, Y0 + 1, tr.takeoffZ, tr.centrePost);
                placeState(tr.takeoffX, Y0 + 2, tr.takeoffZ, tr.centrePost);
            }
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
            if (tr.climb != null) buildClimb(tr); // vine/leaf structure over the standard geometry
        }

        /** Lay a climbable-transit card's vine/leaf structure onto the standard runway/landing geometry.
         *  Supports are always placed BEFORE their vines (an unsupported vine pops on its own neighbour
         *  update). All cells are in the z = baseZ travel row; support walls sit at z+1 (lateral, outside
         *  the corridor) or inside the cliff face.
         *
         *  <p><b>ASC_PIN — the faithful flagship elevator.</b> The Ascend {@code (takeoff,Y0) →
         *  (land,Y0+1)} is offered legitimately: its checked cells (landing feet/head at Y0+2/Y0+3, takeoff
         *  head-clearance at Y0+3 — a PASSABLE vine) are all clear. But the jump's feet transit
         *  {@code (takeoffX, Y0+2)} — a vine — and vanilla's {@code (jumping || horizontalCollision) &&
         *  onClimbable → +0.2/t} converts the held jump into a climb. The forward face is leaf-blocked at
         *  Y0+4/Y0+5 (the incident's canopy underside): by the time the ±0.15/t clamped horizontal drift
         *  reaches the cell boundary the bot's head is already in the blocked band, so it cannot squeeze
         *  out through the open landing corridor — it presses the leaf face (hcol arm), rides the curtain
         *  to the cap and pins, with no grounded/fluid state for done/resetWhen/failWhen to fire on.
         *
         *  <p><b>ASC_FACE — capture with an open escape.</b> The curtain occupies the landing STANCE cells
         *  themselves (feet Y0+2, head Y0+3), nothing blocks forward or above: does the follower's
         *  done/failWhen machinery ever recover the step once the arrival cell is a climbable?
         *
         *  <p><b>CLIFF — climb-down regression pin.</b> A 4-block face under the takeoff lip with the
         *  curtain down it: the route falls off the lip into the curtain (hang-arrest), then climbs down
         *  beside the wall — the geometry the descend vine-bounce fix (COLUMN_DEADBAND exact-zero input)
         *  must keep clean: any wall-press would +0.2-ratchet the bot back up the vine. */
        void buildClimb(Trial tr) {
            int z = tr.baseZ;
            switch (tr.climb) {
                case ASC_PIN:
                    for (int y = Y0 + 2; y <= Y0 + 5; y++) place(tr.takeoffX, y, z + 1);    // curtain support
                    for (int y = Y0 + 2; y <= Y0 + 5; y++) placeState(tr.takeoffX, y, z, VINE_SOUTH);
                    placeState(tr.landX, Y0 + 4, z, LEAF);   // the blocked forward face (canopy underside)…
                    placeState(tr.landX, Y0 + 5, z, LEAF);   // …landing corridor Y0+2..Y0+3 stays OPEN
                    placeState(tr.takeoffX, Y0 + 6, z, LEAF); // the canopy cap the elevator pins under
                    break;
                case ASC_FACE:
                    for (int y = Y0 + 2; y <= Y0 + 3; y++) place(tr.landX, y, z + 1);       // curtain support
                    for (int y = Y0 + 2; y <= Y0 + 3; y++) placeState(tr.landX, y, z, VINE_SOUTH);
                    break;
                case CLIFF:
                    for (int y = Y0 - 4; y <= Y0 - 1; y++) place(tr.takeoffX, y, z);        // the cliff face
                    for (int y = Y0 - 3; y <= Y0; y++) placeState(tr.landX, y, z, VINE_WEST); // the curtain
                    break;
                case DIAG_TOP: {
                    // WALK OFF THE TOP OF A VINE **DIAGONALLY** (2026-08-25). Pins the long-flagship wedge
                    // at (662,70,616): a Climb tops out HANGING on the vine (the "floor" is the climbable,
                    // toFloorTopY=0), the next step is a lateral Diagonal, and before this arc the bot
                    // simply let go and fell.
                    //
                    // THE FIXTURE'S WHOLE DIFFICULTY is that a vine needs a solid block to attach to, and
                    // that block is itself a standable orthogonal option — so the naive layout hands the
                    // planner a two-step L route and the Diagonal is never emitted. The way out is that a
                    // vine has FOUR possible support faces: pick the one that is neither of the diagonal's
                    // two corner cells nor the approach lane. With the diagonal running (+x,+z):
                    //
                    //   corner A (tx+1, tz)   must stay AIR        \ either one being solid gives an
                    //   corner B (tx, tz+1)   must stay AIR        / orthogonal step and voids the test
                    //   support  (tx-1, tz)   VINE_WEST attaches here — opposite the diagonal
                    //   approach  along +z    so the support column never blocks the walk-in
                    //
                    // The support runs to Y0+6, well over the topped-out bot's head, so its own top is not
                    // a reachable floor either. Both corners are air over VOID, so they are not standable
                    // and no orthogonal step exists at all — the Diagonal is FORCED, not merely preferred
                    // by the greedy heuristic. The course's canPlace=false / canMine=false closes the last
                    // escape (no pillaring up, no digging through the support).
                    //
                    // NOTE the card MUST be Template.OFFSET. Template.REACH lays a 3-WIDE landing platform
                    // perpendicular to the continuation axis, which places a block at corner A and quietly
                    // hands back the orthogonal route — the fixture would pass while testing nothing.
                    for (int y = Y0 + 1; y <= Y0 + 6; y++) place(tr.takeoffX - 1, y, tr.takeoffZ);
                    for (int y = Y0 + 1; y <= Y0 + 3; y++) placeState(tr.takeoffX, y, tr.takeoffZ, VINE_WEST);
                    break;
                }
                case LATERAL:
                    // A feet-level vine row across the floorless gap (takeoffX+1 .. landX-1), backed by
                    // a TWO-high wall face at z+1 (supports placed first). Two-high is load-bearing: a
                    // 1-high wall's top was a standable bridge one up — the wide REACH runway's z+1 row
                    // let the bot Ascend onto it and WALK the gap, never touching a vine (measured
                    // 2026-07-31). With the face 151..152 no move gains its top (rise 2) and its body
                    // blocks the Ascend landing, so the vine cling is the only realizable crossing.
                    for (int x = tr.takeoffX + 1; x < tr.landX; x++) {
                        place(x, Y0 + 1, z + 1);
                        place(x, Y0 + 2, z + 1);
                    }
                    for (int x = tr.takeoffX + 1; x < tr.landX; x++) placeState(x, Y0 + 1, z, VINE_SOUTH);
                    break;
            }
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
