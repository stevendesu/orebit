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
import com.orebit.mod.platform.BotTeleport;
import com.orebit.mod.platform.ConfigDir;
import com.orebit.mod.platform.EntityState;
import com.orebit.mod.platform.PlatformEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/**
 * Headless SWIMMING-MOVEMENT diagnostic harness (a sibling of {@link ParkourCourse}, armed by its own
 * {@code -Dorebit.swim} flag). It builds a grid of self-contained water TANKS floating high over a flat
 * world and drives the bot through a SERIES of isolated water challenges — surface crossings, dives, column
 * rises, a prone 1×1 thread, bubble columns (up + down), kelp, and a waterlogged fence — recording pass/fail
 * plus a per-tick trajectory dump with the water-specific state (in-water, submerged, the prone
 * {@code Pose.SWIMMING}, sprint, vertical velocity) so the catatonic/oscillation/exit pathologies can be
 * diagnosed from data.
 *
 * <p><b>Why a bespoke course.</b> The reported swim pathologies — walks into water and freezes, sinks to the
 * bottom and spins on one square, can't climb out, tries to thread a 1×1 gap in the upright pose — are all
 * EXECUTOR/follower problems invisible to a route-level pass/fail. Isolating each shape in its own tank makes
 * it a reproducible experiment; the trajectory dump captures exactly what the bot did (position frozen,
 * position cycling, wrong pose at the gap, the depth-autopilot fighting a bubble column's push).
 *
 * <p><b>Contained-tank verdict model (owner-chosen).</b> Each tile is a fully-walled stone tank (a solid shell
 * with an open top, filled with water sources) with dry approach/exit platforms. Nothing is lethal by design:
 * a stuck/frozen/oscillating bot simply never reaches its goal and FAILS by timeout, with the trace
 * explaining why. {@code needsBreath} is OFF (see {@code scripts/swim/orebit.properties}) so a slow-but-working
 * deep swim is never killed mid-diagnosis — "stuck" and "slow" stay distinguishable in the data. The harness
 * bakes in NO fix and asserts NO mechanism; it only reproduces and records, exactly like {@link ParkourCourse}.
 *
 * <p><b>Inert in production</b> — {@link #register} returns immediately unless {@code -Dorebit.swim} is set.
 * Common, version-portable source (every MC surface it touches is range-stable).
 */
public final class SwimCourse {

    private SwimCourse() {}

    private static final String RESULT_FILE = "orebit-swim-result.properties";
    private static final String TRACE_FILE = "orebit-swim-trace.txt";

    /** Water-surface / lip / platform level: solid platform & wall tops and the top water layer all sit at S,
     *  so a bot stands at S+1 and steps DOWN one cell into the surface (the realistic contained-pool lip). */
    /**
     * <b>MEASURED, UNEXPLAINED: the course is sensitive to its absolute Y</b> (2026-08-15). Relocating every
     * tile by moving S from 160 to 172 left 15 of 17 verdicts character-identical but flipped two in OPPOSITE
     * directions — {@code gap} PASS&rarr;FAIL and {@code sidegapwet} FAIL&rarr;PASS. The tested hypothesis
     * (that {@code S=160} sits on a level-0 region Y seam — it does; {@link
     * com.orebit.mod.worldmodel.hpa.RegionAddress#LEAF_SIZE} is 16 over a world floor of -64, so rows break at
     * {@code y = 0 (mod 16)}) was REFUTED as the cause: not one of the nine "nav gave up" surface cards
     * changed verdict. Left at 160 because 172 bought nothing and cost {@code gap}.
     *
     * <p>Do not read this as a knob to tune. The positional sensitivity is a real unexplained signal and the
     * two flipped cards are the only handle on it; changing S again without a mechanism just reshuffles which
     * card is broken.
     */
    private static final int S = 160;
    private static final int BASE_X = 8;
    private static final int BASE_Z = 8;
    /** Tiles laid in a compact GRID (snake ordering, so consecutive trials are adjacent and teleports stay
     *  inside the loaded+built nav bubble). */
    private static final int COLS = 4;
    private static final int STRIDE = 22; // grid cell size (> the longest tile span so tiles never touch)

    /** Vertical-shaft geometry shared by {@link Kind#SIDE_GAP_WET} / {@link Kind#SIDE_GAP_DRY}: the shaft's
     *  topmost and bottom-most carved cells, and the FEET level of the 2-tall side pocket. The pocket sits
     *  mid-column on purpose — with carved cells both above and below it, {@code fluidTop} at the pocket
     *  level is 1.0 and {@code solidAt(wy+2)} is false, so NEITHER of {@code reachedSwim}'s two clamps fires
     *  and its target stays the unmodified {@code wy + 1.0}. That is the whole point of the fixture. */
    private static final int SHAFT_TOP_Y = S - 2;
    private static final int SHAFT_BOTTOM_Y = S - 10;
    private static final int SIDE_GAP_Y = S - 6;

    private static final int WARMUP_TICKS = 120;
    private static final int SETTLE_TICKS = 40;
    private static final int NAV_RETRY_WINDOW = 40;
    private static final int MAX_NAV_RETRY = 5;
    /** Per-trial attempt budget (ticks). Swimming is slow (surface paddle 9.09 t/block), and a deep dive +
     *  rise + long crossing is legitimately long — a generous budget so a working-but-slow swim never trips a
     *  false timeout, while a genuine freeze still resolves within it (~40 s). */
    private static final int ATTEMPT_BUDGET = 800;
    /** Consecutive fully-out-of-water ticks (far from goal) that count as an ejection (debounced so a 1-tick
     *  surface bob doesn't false-fire). */
    private static final int EJECT_CONSECUTIVE_TICKS = 5;

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    /** A slippery ice lip: reduced friction settles the bot at a sub-block-center offset at the pool edge. */
    private static final BlockState ICE = Blocks.ICE.defaultBlockState();
    /** Soul sand under a water column makes vanilla form an UPWARD bubble column (pushes entities up). */
    private static final BlockState SOUL_SAND = Blocks.SOUL_SAND.defaultBlockState();
    /** Magma under a water column makes vanilla form a DOWNWARD bubble column (drags entities down); also
     *  damaging on contact (takesDamage bots) — the down-drag can pull a surface swimmer under toward it. */
    private static final BlockState MAGMA = Blocks.MAGMA_BLOCK.defaultBlockState();
    /** Kelp: a plant IN water — empty collision shape + water fluid → classified SWIMMABLE (passable), the
     *  "waterlogged but passable" case. (Even if it decays untended, the cell reverts to plain water — still
     *  swimmable — so the classification under test is stable for the run.) */
    private static final BlockState KELP = Blocks.KELP_PLANT.defaultBlockState();
    /** A WATERLOGGED fence: fence collision shape + water fluid → classified NOT swimmable (a wall the bot
     *  must route around), the "waterlogged but NOT passable" case. */
    private static final BlockState WLOG_FENCE = Blocks.OAK_FENCE.defaultBlockState()
            .setValue(BlockStateProperties.WATERLOGGED, true);
    /** Honey on the step-off lip: a sticky full block that slows horizontal movement and prevents jumping,
     *  helping settle the bot at ~zero velocity dry on the lip (part of the lip-stuck repro). */
    private static final BlockState HONEY = Blocks.HONEY_BLOCK.defaultBlockState();
    /** Cobweb in the body cells above the honey lip: crushes the bot's speed to ~zero so it perches DRY on
     *  the lip (the arrival-with-no-momentum precondition of the lip-stuck bug). */
    private static final BlockState COBWEB = Blocks.COBWEB.defaultBlockState();
    /** End portal BLOCK (no frame): teleports on any box contact (Portal transition time 0), persists
     *  frameless, and is planner-avoided via PORTAL_BIT — the SWIM_MAZE_PORTAL wall material. */
    private static final BlockState END_PORTAL = Blocks.END_PORTAL.defaultBlockState();
    /** OPEN oak fence gate: collisionless (FenceGateBlock.getCollisionShape returns Shapes.empty() when OPEN
     *  — javap-verified 1.21.11), so a bot carried upward passes straight through into the lava resting above;
     *  the gate cell still holds the lava up, because a fluid can never flow into an OCCUPIED cell. CLOSED was
     *  tried first and refuted by the owner (2026-08-16): a closed gate's wall ARRESTS the typical seam-clip
     *  below the lava — it protects the bot instead of convicting it. */
    private static final BlockState GATE_OPEN =
            Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(BlockStateProperties.OPEN, true);
    private static final BlockState LAVA = Blocks.LAVA.defaultBlockState();

    public static void register(PlatformEvents events) {
        if (System.getProperty("orebit.swim") == null) {
            return;
        }
        Course course = new Course();
        events.onServerStarted(course::start);
        events.onWorldTickEnd(course::tick);
        OrebitCommon.LOGGER.info("[Orebit/swim] armed: {} trials", course.trials.size());
    }

    private enum Kind {
        CROSS_SURFACE,  // 1-deep pool: a pure surface Swim across (no 2-deep → no sprint-swim init)
        CROSS_DEEP,     // 4-deep pool: dive → prone SprintSwim just below the surface → surface & exit
        DIVE,           // deep shaft, goal at the BOTTOM: dive down and hold the bottom (the "spins on the
                        //   lowest square" oscillation case)
        SINK,           // deep pool, goal a FLOORLESS mid-water cell: surface-swim then dive & hold mid-depth
        RISE,           // deep shaft, START submerged at the bottom, goal at the TOP: swim up & climb out
        SUBMERGED_GAP,  // 2-deep init, 1-deep run, a 1-tall hole in a wall, a dry bank goal — the in-game
                        //   StatefulSwimTest shape; only a PRONE (sprint-swim) bot threads it (pathology #4)
        GAP_NO_INIT,    // same, but the init cell is only 1-deep → nowhere to go prone → expect nav give-up
        BUBBLE_UP,      // soul-sand floor → UPWARD bubble column in the centre lane; CROSS to the far exit
                        //   platform — the impassable column forces a route AROUND it via a side lane
        BUBBLE_DOWN,    // magma floor → DOWNWARD bubble column in the centre lane; CROSS to the far exit
                        //   platform — likewise route AROUND the impassable column
        KELP,           // kelp-filled pool: swim across, should behave exactly like open water
        FENCE,          // a waterlogged-fence wall blocking the centre lane: route around it (fence = wall)
        GAP_2X1,        // surface crossing with an over-height wall mid-pool; only passage is a 2-tall x 1-wide
                        //   center gap at S-1..S — bot must submerge + go prone to thread (skims & slams wall)
        GAP_1X1_ANGLE,  // 5-lane pool, over-height wall with a single 1x1 hole at (wallX,S-1,zc); start offset
                        //   +2 lanes at the surface — angled + descending approach to the hole
        LIP_ICE,        // surface cross whose step-off lip block is ICE — friction settles the bot at a
                        //   sub-block-center offset at the lip (long-shot dry-perch repro)
        LIP_DOWN,       // bot starts DRY on a honey pool-lip, buried in cobweb (≈zero velocity), goal at the
                        //   pool BOTTOM directly adjacent → the first in-water move is a DOWN dive, not a
                        //   lateral step; reproduces the lip-stuck bug (dry-perch, cursor advances past the
                        //   water-entry waypoint, bot tries to swim down while still dry and stalls)
        SIDE_GAP_WET,   // a sealed 1x1 vertical water SHAFT with a 2-tall SUBMERGED side pocket (solid floor,
                        //   solid cap) partway up. The bot swims up the column, stops level with the pocket, and
                        //   steps LATERALLY out of the water column onto the pocket floor — a Swim -> Traverse
                        //   handoff (Traverse.candidates never requires a standable START floor, so it emits the
                        //   edge straight off a floating node). THE DEADLOCK PROBE: the pocket level has water
                        //   above AND below it, so fluidTop == 1.0 and nothing is solid at wy+2 — neither the
                        //   ceiling clamp nor the surface clamp fires, and reachedSwim's target stays the full
                        //   wy + 1.0, i.e. feet cell wy+1 while the Traverse is framed for wy. Fully sealed, so
                        //   there is no fluid flow and the fixture is bit-stable.
        SIDE_GAP_DRY,   // the owner's SWS/SWA/SWA/SWS shape with a genuinely DRY pocket. Sealed geometry cannot
                        //   express it — a water source beside air floods it — so the column is a WATERFALL: a
                        //   source at the top over an open shaft draining into a wide shallow basin. Vanilla's
                        //   flowing-down rule then suppresses lateral spread and the pocket stays dry, which is
                        //   exactly why (154,-7,103) held water while (155,-7,103) read fl0.000 on the flagship.
                        //   Same handoff as SIDE_GAP_WET, plus the water -> air medium change on the way out.
        SWIM_TURN,      // a SUBMERGED 1-wide sprint-swim tunnel (stone ceiling → prone the whole way) that runs
                        //   a LONG +X approach, then turns 90° to +Z; an UP-bubble-column sits STRAIGHT AHEAD of
                        //   the corner (the +X-overshoot cell). The only route is +X→turn→+Z (the column is
                        //   impassable, off-route), so a clean cruise routes around it — but the current
                        //   SprintSwim drives full setForward(1.0), so carried-forward momentum should COAST the
                        //   bot straight past the corner into the column (drift off the planned path). Repro of
                        //   the cruise-overshoot; walls seal every diagonal so the corner can't be cut.
        SWIM_MAZE,      // a SUBMERGED bubble-column SERPENTINE: a 1-wide safe STONE-floored channel winds
                        //   boustrophedon (+X leg, one lane over, -X leg, one lane over, +X leg) through a tank
                        //   whose every OTHER water cell is a SOUL_SAND up-bubble-column (the maze "walls" — the
                        //   inverse of the centre-column trials). At each turn the cell straight ahead along the
                        //   incoming leg is a column, so cruise momentum that fails to brake into the turn drifts
                        //   the bot into an impassable column. Only the FIRST leg is roofed (kept submerged/prone
                        //   for the opening cruise); over every later leg the sky is OPEN, so a column clip EJECTS
                        //   the bot out of the water (loses the prone Pose.SWIMMING) with no ceiling to hold it
                        //   down — the lethality mechanism. Designed as the momentum-overshoot repro, but the
                        //   walls are LEGAL vanilla movement: since 2026-08-15 the planner rides one over the
                        //   wall (RideBubbleColumn) and this card verifies the MULTI-COLUMN RIDE instead — the
                        //   serpentine-cruise duty moved to the two sibling kinds below.
        SWIM_MAZE_PORTAL, // the serpentine with END-PORTAL-block walls — the CRUISE verifier the ride shortcut
                        //   took away from SWIM_MAZE. End portal is the one block that punishes a wall clip
                        //   INSTANTLY and catastrophically (all javap-verified on 1.21.11): its Portal transition
                        //   time is the interface default 0 (nether portals wait the ~80-tick gamerule for
                        //   survival players — a drive-by clip does nothing), entityInside fires on any box
                        //   intersection, and a frameless setblock persists (NetherPortalBlock.updateShape pops
                        //   to AIR; EndPortalBlock has no frame check). The planner needs no fixture-side help:
                        //   PORTAL_BIT is walker-avoidance ("routes AROUND every portal, never occupies one
                        //   mid-path") and a portal cell is not bubbleUp, so no ride edges exist — the serpentine
                        //   is the ONLY plannable route, and any overshoot clip teleports the bot to the End,
                        //   caught by the dimension tripwire. Owner-designed 2026-08-16.
        SWIM_MAZE_LAVA  // the serpentine under an OPEN-FENCE-GATE ceiling with a LAVA blanket resting on it —
                        //   keeps the cruise lethal under a ceiling, which neither a stone roof NOR a closed
                        //   gate can do (the swimturn lesson: a solid roof turns a column clip into a
                        //   recoverable pin, and a CLOSED gate's wall was owner-refuted 2026-08-16 for the same
                        //   reason — it arrests the conveyed bot below the lava). An OPEN gate is collisionless
                        //   (getCollisionShape returns Shapes.empty() when OPEN, javap-verified), so a clipped
                        //   bot conveyed upward passes straight through into the lava — burn damage is the clip
                        //   verdict (health tripwire; survival.takesDamage is ON in this course) — while the
                        //   gate cell still HOLDS the lava, because a fluid can never flow into an occupied
                        //   cell. MEASURED SURPRISE (first run, 2026-08-16): the open gate reads as air to the
                        //   model, so the planner DOES still offer the surface ride — and executing it is SAFE:
                        //   the settle exits the column laterally at feet 159-160, and the vanilla surface FLING
                        //   never fires because the cell above the column is occupied (even collisionless) —
                        //   the swimturn any-ceiling-pins-the-column lesson generalizing to non-solid ceilings.
                        //   So the card verifies cruise + corners + a safe under-ceiling ride; the burn tripwire
                        //   stays armed for genuine wall clips, whose ballistic pop through the collisionless
                        //   gate CAN cross into the lava. Owner-designed 2026-08-16.
    }

    /** One water challenge: a kind + its tank dimensions, with start/goal geometry precomputed from its base. */
    private static final class Trial {
        final String name;
        final Kind kind;
        final int depth;            // water layers (surface at S, solid floor at S-depth)
        final int poolLen;          // pool length along +X (interior)
        final int baseX, baseZ;

        final int zc;               // centre-line Z
        final int approachX0;       // first approach-platform cell
        final int poolX0, poolX1;   // interior water span (X)
        final int yFloor;           // solid floor Y (= S - depth)

        double startX, startY, startZ;
        float startYaw;
        BlockPos goal;

        Trial(String name, Kind kind, int depth, int poolLen, int baseX, int baseZ) {
            this.name = name;
            this.kind = kind;
            this.depth = depth;
            this.poolLen = poolLen;
            this.baseX = baseX;
            this.baseZ = baseZ;
            this.zc = baseZ + 4;
            this.approachX0 = baseX + 1;
            this.poolX0 = approachX0 + 3;            // 3-wide approach, then the west wall at poolX0-1
            this.poolX1 = poolX0 + poolLen - 1;
            this.yFloor = S - depth;
            int centerX = (poolX0 + poolX1) / 2;
            this.startYaw = yaw(1, 0);               // every trial runs / faces +X

            if (kind == Kind.SUBMERGED_GAP || kind == Kind.GAP_NO_INIT) {
                // Special geometry (see buildGap): a solid block with a carved water channel at feet-level
                // yB = S-1. Start submerged in the init cell; goal on the dry bank two cells past the hole.
                int yB = S - 1;
                this.startX = baseX + 2 + 0.5;
                this.startY = yB;                    // feet in the init water cell (submerged when 2-deep)
                this.startZ = zc + 0.5;
                this.goal = new BlockPos(baseX + 8, yB, zc);
                return;
            }

            if (kind == Kind.GAP_1X1_ANGLE) {
                // 5-lane pool (built by buildAngleGap). Start at the surface, offset +2 lanes; the single 1x1
                // hole is in the CENTER lane one block below the surface — an angled + descending approach.
                this.startX = approachX0 + 1 + 0.5;
                this.startY = S + 1;
                this.startZ = zc + 2 + 0.5;
                this.goal = new BlockPos(poolX1 + 2, S + 1, zc);
                return;
            }

            if (kind == Kind.LIP_DOWN) {
                // Start standing DRY directly on the honey pool-lip (feet at S+1), buried in cobweb so it sits
                // at ~zero velocity. The goal is the pool BOTTOM of the nearest water column (adjacent to the
                // lip), so the planned first in-water move after the water-entry waypoint heads DOWN (a dive),
                // not laterally. This is the lip-stuck repro shape.
                this.startX = poolX0 - 1 + 0.5;      // directly on the lip block (poolX0-1), centre lane
                this.startY = S + 1;                 // feet on top of the honey lip
                this.startZ = zc + 0.5;
                this.goal = new BlockPos(poolX0, yFloor + 1, zc);
                return;
            }

            if (kind == Kind.SIDE_GAP_WET || kind == Kind.SIDE_GAP_DRY) {
                // Vertical shaft (see buildSideGap). Start submerged at the BOTTOM of the column so the approach
                // to the pocket is a genuine swim RISE — the direction the flagship failed in — and the goal is
                // the far end of the pocket, which is only reachable by leaving the water column sideways at
                // exactly the pocket's feet level. Nothing else in the tile is passable, so an off-by-one-cell
                // handoff cannot be walked off; the bot either exits at the right height or never exits.
                this.startX = baseX + 2 + 0.5;
                this.startY = SHAFT_BOTTOM_Y;
                this.startZ = zc + 0.5;
                this.goal = new BlockPos(baseX + 6, SIDE_GAP_Y, zc);
                return;
            }

            if (kind == Kind.SWIM_TURN) {
                // Custom submerged L-tunnel (see buildSwimTurn). Start submerged at the tunnel entry, facing +X,
                // so the bot is prone-sprint-swimming BEFORE it reaches the corner (this tests the CRUISE, not
                // the initiation). The +X leg is long (entry baseX+2 .. corner baseX+10 = 9 cells) to build full
                // cruise momentum; then a 90° turn to +Z runs to the goal at the end of the +Z leg. The impassable
                // up-bubble column sits at (baseX+11, zc) — the cell STRAIGHT AHEAD of the corner in +X.
                int xEntry = baseX + 2;
                this.startX = xEntry + 0.5;
                this.startY = yFloor + 1;            // feet in the bottom water layer → fully submerged
                this.startZ = zc + 0.5;
                int xTurn = baseX + 10;
                int zEnd = zc + 8;
                this.goal = new BlockPos(xTurn, yFloor + 1, zEnd);   // end of the +Z leg, submerged
                return;
            }

            if (kind == Kind.SWIM_MAZE || kind == Kind.SWIM_MAZE_PORTAL || kind == Kind.SWIM_MAZE_LAVA) {
                // Submerged serpentine (see buildMazeCore) — same frame for all three wall materials. Start
                // submerged at the WEST end of the first +X leg, facing +X, so the bot is already
                // prone-sprint-swimming before the first turn (this tests the CRUISE, not the initiation).
                // Three ~9-cell legs (boustrophedon: +X at z=zc, -X at z=zc+2, +X at z=zc+4) build real
                // momentum; each turn has a wall cell straight ahead (the overshoot cell). Goal at the EAST
                // end of leg 3, with a wall one cell past it.
                int xW = baseX + 2;                  // west channel end
                int xE = baseX + 10;                 // east channel end
                this.startX = xW + 0.5;
                this.startY = yFloor + 1;            // feet in the bottom water layer → fully submerged
                this.startZ = zc + 0.5;
                this.goal = new BlockPos(xE, yFloor + 1, zc + 4);   // end of leg 3 (+X), submerged
                return;
            }

            // Generic tank kinds.
            if (kind == Kind.RISE) {
                // Start submerged at the bottom of the shaft; climb out to the top far platform.
                this.startX = centerX + 0.5;
                this.startY = yFloor + 1;
                this.startZ = zc + 0.5;
                this.goal = new BlockPos(poolX1 + 2, S + 1, zc);
            } else {
                // Everyone else starts on the approach platform at the surface.
                this.startX = approachX0 + 1 + 0.5;
                this.startY = S + 1;
                this.startZ = zc + 0.5;
                if (kind == Kind.DIVE) {
                    // Goal at the bottom centre — dive to it.
                    this.goal = new BlockPos(centerX, yFloor + 1, zc);
                } else if (kind == Kind.SINK) {
                    // Goal at a FLOORLESS mid-water cell (3 below the surface, water still below it) — the bot
                    // must dive and HOLD depth against buoyancy with no floor to rest on.
                    this.goal = new BlockPos(centerX, S - 3, zc);
                } else {
                    // CROSS_SURFACE / CROSS_DEEP / BUBBLE_UP / BUBBLE_DOWN / KELP / FENCE: cross to the far exit
                    // platform. The BUBBLE trials put a full-height bubble column in the CENTRE lane (z=zc) — now
                    // that a bubble column is classified impassable, the only route across is AROUND it through a
                    // side lane (z=zc±1, ordinary water), so both bubble trials test ROUTING, not diving in.
                    this.goal = new BlockPos(poolX1 + 2, S + 1, zc);
                }
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
        double closest;             // closest 3-D approach to the goal seen this trial (diagnostic)
        boolean everInWater;        // the bot has been in water at least once this trial (arms the eject guard)
        int ejectTicks;             // consecutive fully-out-of-water ticks (far from goal) since last in water
        double prevX, prevZ;
        boolean prevSwimming;
        String prevMove = "";
        int prevSegToX = Integer.MIN_VALUE, prevSegToY, prevSegToZ;
        /** Last plan reference already dumped (identity) — a new/replanned plan re-triggers the PLAN block. */
        com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan lastDumpedPlan;
        int passed, failed;

        Course() {
            buildTrialList();
        }

        void buildTrialList() {
            add("cross",       Kind.CROSS_SURFACE, 2, 6);
            add("crossdeep",   Kind.CROSS_DEEP,    4, 6);
            add("dive",        Kind.DIVE,          6, 3);
            add("sink",        Kind.SINK,          6, 3);
            add("rise",        Kind.RISE,          6, 3);
            add("gap",         Kind.SUBMERGED_GAP, 0, 0);
            add("gapnoinit",   Kind.GAP_NO_INIT,   0, 0);
            add("bubbleup",    Kind.BUBBLE_UP,     5, 3);
            add("bubbledown",  Kind.BUBBLE_DOWN,   5, 6);
            add("kelp",        Kind.KELP,          3, 6);
            add("fence",       Kind.FENCE,         3, 5);
            add("gap2x1",      Kind.GAP_2X1,       3, 6);
            add("gap1x1angle", Kind.GAP_1X1_ANGLE, 3, 6);
            add("lipice",      Kind.LIP_ICE,       2, 6);
            add("lipdown",     Kind.LIP_DOWN,      4, 6);
            add("swimturn",    Kind.SWIM_TURN,     4, 8);
            add("swimmaze",    Kind.SWIM_MAZE,     4, 8);
            add("sidegapwet",  Kind.SIDE_GAP_WET, 10, 8);
            add("sidegapdry",  Kind.SIDE_GAP_DRY, 10, 8);
            add("mazeportal",  Kind.SWIM_MAZE_PORTAL, 4, 8);
            add("mazelava",    Kind.SWIM_MAZE_LAVA,   4, 8);
        }

        void add(String name, Kind kind, int depth, int poolLen) {
            int i = trials.size();
            int row = i / COLS;
            int col = i % COLS;
            if ((row & 1) == 1) col = COLS - 1 - col; // snake
            int bx = BASE_X + col * STRIDE;
            int bz = BASE_Z + row * STRIDE;
            trials.add(new Trial(name, kind, depth, poolLen, bx, bz));
        }

        void start(MinecraftServer server) {
            this.server = server;
            if (Boolean.getBoolean("orebit.swim.debug")) {
                Debug.ENABLED = true;
                Debug.VERBOSE = true;
            }
            try {
                this.level = server.overworld();
                Trial first = trials.get(0);
                owner = new FakePlayerEntity(server, level, new GameProfile(
                        UUID.nameUUIDFromBytes("OrebitSwim:owner".getBytes(StandardCharsets.UTF_8)),
                        "Swim"));
                owner.setPos(first.startX, S + 1, first.startZ);
                BotManager.spawnBotFor(owner);
                bot = BotManager.botFor(owner);
                if (bot == null) {
                    finish("bot never spawned");
                    return;
                }
                trace = Files.newBufferedWriter(ConfigDir.serverDir(server).resolve(TRACE_FILE),
                        StandardCharsets.UTF_8);
                trace.write("Orebit swim course trace  (T <trial> <tick> x y z | vy spd | grnd inW subm swim spr | move)\n");
                trace.write("legend: grnd=onGround inW=inWater subm=isUnderWater swim=prone Pose.SWIMMING spr=sprinting\n\n");
                OrebitCommon.LOGGER.info("[Orebit/swim] course ready; {} trials", trials.size());
                enter(0);
            } catch (Throwable t) {
                OrebitCommon.LOGGER.error("[Orebit/swim] setup threw", t);
                finish("setup threw " + t.getClass().getSimpleName());
            }
        }

        void enter(int i) {
            index = i;
            Trial tr = trials.get(i);
            buildTile(tr);
            bot.reviveIfDead();
            bot.setHealth(bot.getMaxHealth());
            bot.clearFire();
            // A mazeportal wall clip left the bot in the END last trial — setPos below is same-level only, so
            // bring it back through the cross-dimension seam first (platform/BotTeleport, both era flavors).
            if (bot.level() != level) {
                BotTeleport.to(bot, level, tr.startX, tr.startY, tr.startZ, tr.startYaw, 0f);
            }
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
            everInWater = false;
            ejectTicks = 0;
            prevX = tr.startX;
            prevZ = tr.startZ;
            prevSwimming = false;
            prevMove = "";
            prevSegToX = Integer.MIN_VALUE;
            lastDumpedPlan = null;
            try {
                trace.write(String.format(Locale.ROOT,
                        "== %s : kind=%s depth=%d start=(%.1f,%.1f,%.1f) goal=(%d,%d,%d)\n",
                        tr.name, tr.kind, tr.depth, tr.startX, tr.startY, tr.startZ,
                        tr.goal.getX(), tr.goal.getY(), tr.goal.getZ()));
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
                bot.comeTo(tr.goal, 0.75, 0.75, 0);
                return;
            }

            attemptTicks++;
            trace(tr);

            // DIMENSION TRIPWIRE (all trials — no card legitimately changes dimension): an end-portal wall
            // teleports on CONTACT (Portal transition time 0, javap-verified), so on the mazeportal card a
            // box-clip of a wall cell moves the bot to the End the same tick. Checked FIRST — every later
            // read (distance, water state) is meaningless about a bot in another dimension. enter() recovers
            // the bot cross-dimension for the next trial.
            if (bot.level() != level) {
                record(tr, "FAIL", "teleported out of the course dimension (clipped a portal wall)");
                return;
            }

            // BURN TRIPWIRE (mazelava): the lava blanket is unreachable from the channel — the ONLY way to
            // touch it is a wall-column clip conveying the bot up between the gate walls. Any health loss is
            // therefore the clip verdict, long before the burn would kill. Card-gated: bubbledown DESIGNS
            // magma damage in (see the properties header), so a course-wide health oracle would convict it.
            if (tr.kind == Kind.SWIM_MAZE_LAVA && bot.getHealth() < bot.getMaxHealth() - 1.0e-3f) {
                // Mechanism-neutral on purpose: the lava is reachable two ways — a wall-column clip conveying
                // the bot up, or the PLANNED RideBubbleColumn ride whose surface launch crosses into the
                // blanket (the current red: the planner prices no hazard on the launch corridor). Either way,
                // touching the ceiling is the failure; the trace names which mechanism.
                record(tr, "FAIL", "burned (reached the lava ceiling)");
                return;
            }

            if (!bot.isAlive()) {
                record(tr, "FAIL", "died");
                return;
            }
            double dx = bot.getX() - (tr.goal.getX() + 0.5);
            double dy = bot.getY() - tr.goal.getY();
            double dz = bot.getZ() - (tr.goal.getZ() + 0.5);
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist < closest) closest = dist;

            // EJECTION GUARD: the bot loses the prone swim pose by fully leaving the water mid-route (a bubble
            // column shoots it out). Only armed once the bot has actually been in water (so a dry-start trial
            // like lipdown doesn't false-fire), debounced over EJECT_CONSECUTIVE_TICKS (so a 1-tick surface bob
            // doesn't trip it), and gated on dist > ARRIVE_DIST (so exiting onto a bank AT the goal is a PASS).
            //
            // A tick steered by RideBubbleColumn is EXEMPT (2026-08-15): riding an open-sky surface column
            // LAUNCHES the rider ~1.7 blocks above the water for ~10-15 airborne ticks — vanilla physics, and
            // the move's own documented contract ("strong enough to eject the rider above the surface"). The
            // guard predates that move ever being planned mid-route; convicting the one move whose PLAN includes
            // an airborne window turns a correct ride into a FAIL (swimmaze died at z=103.99, one tick of arc
            // from the exit water). The exemption window is exactly the ride: lastSteerMove stays
            // RideBubbleColumn until its reached() fires, which requires the bot settled back IN water (or
            // grounded). Every cruise move remains fully guarded — a drift-clip into a wall column mid-
            // SprintSwim still convicts, which is this course's whole lethality mechanism.
            boolean plannedLaunch = "RideBubbleColumn".equals(bot.lastSteerMove);
            if (bot.isInWater()) {
                everInWater = true;
                ejectTicks = 0;
            } else if (everInWater && !plannedLaunch) {
                ejectTicks++;
                if (ejectTicks >= EJECT_CONSECUTIVE_TICKS && dist > BotNavigator.ARRIVE_DIST) {
                    record(tr, "FAIL", "ejected (left water mid-route, lost prone)");
                    return;
                }
            }

            // PASS: the driver reverted to STAY (comeTo drops to STAY only on TRUE arrival — a nav give-up
            // holds in COME), and the bot is genuinely near the goal cell.
            if (bot.mode() == AllyBotEntity.Mode.STAY && dist < 1.8) {
                record(tr, "PASS", "reached goal");
                return;
            }
            if (bot.navigator().navGaveUp()) {
                // GAP_NO_INIT is a NEGATIVE control — a clean nav-give-up there is the CORRECT outcome.
                if (tr.kind == Kind.GAP_NO_INIT) {
                    record(tr, "PASS", "nav correctly refused (no 2-deep to initiate)");
                    return;
                }
                // Otherwise: a give-up right after a teleport can just be nav-not-yet-built; retry a few times.
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

        void trace(Trial tr) {
            double x = bot.getX(), y = bot.getY(), z = bot.getZ();
            double spd = Math.sqrt((x - prevX) * (x - prevX) + (z - prevZ) * (z - prevZ));
            Vec3 v = bot.getDeltaMovement();
            boolean grnd = EntityState.onGround(bot);
            boolean inW = bot.isInWater();
            boolean subm = bot.isUnderWater();
            boolean swim = bot.isSwimming();
            boolean spr = bot.isSprinting();
            String move = bot.lastSteerMove;
            try {
                BotNavigator nav = bot.navigator();
                com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan plan = nav.currentPlan();
                if (plan != null && plan != lastDumpedPlan) {
                    trace.write(String.format(Locale.ROOT, "PLAN %s size=%d\n", tr.name, plan.size()));
                    for (int j = 0; j < plan.size(); j++) {
                        BlockPos wp = plan.waypoint(j);
                        trace.write(String.format(Locale.ROOT, "  P %d %d %d %d %s\n",
                                j, wp.getX(), wp.getY(), wp.getZ(),
                                plan.movement(j).getClass().getSimpleName()));
                    }
                    lastDumpedPlan = plan;
                }
                boolean segChanged = nav.segToX() != prevSegToX || nav.segToY() != prevSegToY
                        || nav.segToZ() != prevSegToZ;
                if (!move.equals(prevMove) || segChanged) {
                    // Log on EVERY waypoint advance (segment change), not just move-name change, and include
                    // the waypoint index + the bot's actual position so overshoot past an in-place pose-flip
                    // waypoint (StartSprintSwim / Surface) is measurable.
                    trace.write(String.format(Locale.ROOT,
                            "  WP i=%d/%d %s seg=(%d,%d,%d)->(%d,%d,%d) bot=(%.2f,%.2f,%.2f)\n",
                            nav.waypointIndex(), nav.pathSize(), move,
                            nav.segFromX(), nav.segFromY(), nav.segFromZ(),
                            nav.segToX(), nav.segToY(), nav.segToZ(), x, y, z));
                    prevMove = move;
                    prevSegToX = nav.segToX(); prevSegToY = nav.segToY(); prevSegToZ = nav.segToZ();
                }
                if (swim != prevSwimming) {
                    trace.write(String.format(Locale.ROOT, "  POSE %s at y=%.3f vy=%.3f inW=%d subm=%d spr=%d\n",
                            swim ? "STAND->PRONE" : "PRONE->STAND", y, v.y, inW ? 1 : 0, subm ? 1 : 0, spr ? 1 : 0));
                }
                trace.write(String.format(Locale.ROOT,
                        "T %-12s %3d  %.3f %.3f %.3f | %.4f %.4f | %d %d %d %d %d | %s | drive=%s wp=%d/%d fwd=%.2f"
                                + " | lava=%d fire=%d inv=%d hp=%.1f\n",
                        tr.name, attemptTicks, x, y, z, v.y, spd,
                        grnd ? 1 : 0, inW ? 1 : 0, subm ? 1 : 0, swim ? 1 : 0, spr ? 1 : 0, move,
                        nav.driveState(), nav.waypointIndex(), nav.pathSize(), bot.zza,
                        // Mortality probe (2026-08-16, the lava-immunity dig): what the GAME thinks — is the
                        // bot in lava, is it on fire, is the abilities-level shield up, and the live health.
                        // These four disambiguate "effects chain dead" (lava=1, fire=0) from "damage blocked"
                        // (fire>0 or lava=1 with inv=1) from "never actually touched lava" (lava=0 throughout).
                        bot.isInLava() ? 1 : 0, bot.getRemainingFireTicks(),
                        bot.getAbilities().invulnerable ? 1 : 0, bot.getHealth()));
                // GEO: vanilla ground-truth Y-indexing probe (additive; DIAGNOSTIC ONLY — no behavior change).
                // Only the three named trials, to keep the trace compact. Logs bot.getY() vs the REAL AABB
                // extent vs the REAL world block states straddling the hitbox, plus footY and the waypoint Y —
                // so a Y off-by-one between our computed model and vanilla ground truth is directly readable.
                if (tr.name.equals("gap1x1angle") || tr.name.equals("crossdeep") || tr.name.equals("dive")) {
                    net.minecraft.world.phys.AABB bb = bot.getBoundingBox();
                    BlockPos foot = bot.blockPosition();
                    int footX = foot.getX(), footZ = foot.getZ();
                    int y0 = (int) Math.floor(bb.minY) - 1;
                    int y1 = (int) Math.floor(bb.maxY) + 1;
                    StringBuilder cells = new StringBuilder();
                    for (int wy = y0; wy <= y1; wy++) {
                        BlockPos cp = new BlockPos(footX, wy, footZ);
                        BlockState cs = level.getBlockState(cp);
                        char c;
                        if (cs.getFluidState().is(FluidTags.WATER)) c = 'W';
                        else if (!cs.getCollisionShape(level, cp).isEmpty()) c = 'S';
                        else c = 'A';
                        cells.append('Y').append(wy).append('=').append(c).append(' ');
                    }
                    trace.write(String.format(Locale.ROOT,
                            "GEO %s %d byY=%.3f aabb=[%.3f,%.3f] footY=%d wpY=%d cells{%s} inW=%d subm=%d swim=%d\n",
                            tr.name, attemptTicks, y, bb.minY, bb.maxY, foot.getY(), nav.segToY(),
                            cells.toString().trim(), inW ? 1 : 0, subm ? 1 : 0, swim ? 1 : 0));
                }
            } catch (IOException ignored) { }
            prevSwimming = swim;
            prevX = x;
            prevZ = z;
        }

        void record(Trial tr, String result, String reason) {
            results.add(String.format(Locale.ROOT,
                    "%s = %s (%s) closest=%.2f ticks=%d finalPos=(%.1f,%.1f,%.1f) inWater=%b swimming=%b lastMove=%s",
                    tr.name, result, reason, closest, attemptTicks,
                    bot.getX(), bot.getY(), bot.getZ(), bot.isInWater(), bot.isSwimming(), bot.lastSteerMove));
            if (result.equals("PASS")) passed++; else failed++;
            OrebitCommon.LOGGER.info("[Orebit/swim] {} -> {} ({}) closest={} ticks={} finalY={}",
                    tr.name, result, reason, String.format(Locale.ROOT, "%.2f", closest),
                    attemptTicks, String.format(Locale.ROOT, "%.2f", bot.getY()));
            try { trace.write("  RESULT " + result + " (" + reason + ")\n\n"); } catch (IOException ignored) { }
            if (index + 1 < trials.size()) {
                enter(index + 1);
            } else {
                finish("all trials complete");
            }
        }

        // ---- tile construction ---------------------------------------------------------------------------

        void buildTile(Trial tr) {
            if (tr.kind == Kind.SUBMERGED_GAP) { buildGap(tr, true); return; }
            if (tr.kind == Kind.GAP_NO_INIT)   { buildGap(tr, false); return; }
            if (tr.kind == Kind.GAP_1X1_ANGLE) { buildAngleGap(tr); return; }
            if (tr.kind == Kind.SWIM_TURN)     { buildSwimTurn(tr); return; }
            if (tr.kind == Kind.SWIM_MAZE)     { buildSwimMaze(tr); return; }
            if (tr.kind == Kind.SWIM_MAZE_PORTAL) { buildMazePortal(tr); return; }
            if (tr.kind == Kind.SWIM_MAZE_LAVA)   { buildMazeLava(tr); return; }
            if (tr.kind == Kind.SIDE_GAP_WET)  { buildSideGap(tr, false); return; }
            if (tr.kind == Kind.SIDE_GAP_DRY)  { buildSideGap(tr, true); return; }
            buildTank(tr);
            switch (tr.kind) {
                case BUBBLE_UP:   floorFeature(tr, SOUL_SAND); break; // upward bubble column in the shaft
                case BUBBLE_DOWN: floorFeature(tr, MAGMA);     break; // downward column mid-crossing
                case KELP:        fillLane(tr, KELP);          break; // swap the centre water lane for kelp
                case FENCE:       fenceWall(tr);               break; // block the centre lane with a fence wall
                case GAP_2X1:     gap2x1Wall(tr);              break; // over-height wall, 2-tall center gap
                case LIP_ICE:     iceLip(tr);                  break; // ice on the step-off lip block
                case LIP_DOWN:    honeyLip(tr);                break; // honey lip + cobweb body cells (≈0 vel)
                default: break;
            }
        }

        /** A fully-walled stone tank (solid shell, open top) filled with water sources, plus dry approach and
         *  exit platforms at the surface level S. Contained on all four horizontal sides + the floor, so the
         *  water is stable (every interior cell is a source with only air ABOVE it). */
        void buildTank(Trial tr) {
            int wx0 = tr.poolX0 - 1, wx1 = tr.poolX1 + 1;   // west/east walls
            int wz0 = tr.zc - 2, wz1 = tr.zc + 2;           // ±z walls
            for (int x = wx0; x <= wx1; x++) {
                for (int z = wz0; z <= wz1; z++) {
                    set(x, tr.yFloor, z, STONE);            // floor slab
                    boolean perimeter = (x == wx0 || x == wx1 || z == wz0 || z == wz1);
                    if (perimeter) {
                        // N/S walls rise to S+2 (an UNWALKABLE rim — no dry bypass around the pool); the E/W center
                        // lips stay at S so the bot can still step in at the west and climb out at the east. This is
                        // what forces the only approach->exit route to go THROUGH the water.
                        int wallTop = (z == wz0 || z == wz1) ? S + 2 : S;
                        for (int y = tr.yFloor + 1; y <= wallTop; y++) set(x, y, z, STONE);
                    }
                }
            }
            for (int x = tr.poolX0; x <= tr.poolX1; x++) {  // interior water
                for (int z = tr.zc - 1; z <= tr.zc + 1; z++) {
                    for (int y = tr.yFloor + 1; y <= S; y++) set(x, y, z, WATER);
                }
            }
            for (int x = tr.approachX0; x <= tr.poolX0 - 1; x++) {   // approach platform (layer at S)
                for (int z = tr.zc - 1; z <= tr.zc + 1; z++) set(x, S, z, STONE);
            }
            for (int x = tr.poolX1 + 1; x <= tr.poolX1 + 4; x++) {   // exit platform (layer at S)
                for (int z = tr.zc - 1; z <= tr.zc + 1; z++) set(x, S, z, STONE);
            }
        }

        /** Replace the centre-column floor block with a bubble-column source (soul sand → up, magma → down),
         *  so vanilla forms the bubble column through the water above it. */
        void floorFeature(Trial tr, BlockState floor) {
            int cx = (tr.poolX0 + tr.poolX1) / 2;
            set(cx, tr.yFloor, tr.zc, floor);
        }

        /** Swap the centre water lane for another swimmable fill (kelp). */
        void fillLane(Trial tr, BlockState fill) {
            for (int x = tr.poolX0; x <= tr.poolX1; x++) {
                for (int y = tr.yFloor + 1; y <= S; y++) set(x, y, tr.zc, fill);
            }
        }

        /** A full-height WATERLOGGED-fence wall across the centre lane mid-pool, leaving the two side lanes
         *  (z = zc±1) open — so the only route across is AROUND the wall, proving the planner treats the
         *  waterlogged fence as impassable (not swimmable). */
        void fenceWall(Trial tr) {
            int cx = (tr.poolX0 + tr.poolX1) / 2;
            for (int y = tr.yFloor + 1; y <= S; y++) set(cx, y, tr.zc, WLOG_FENCE);
        }

        /** A full-width, over-height wall across all 3 lanes mid-pool, sealing both THROUGH and OVER, leaving
         *  only a 2-tall × 1-wide gap (world-Y S-1..S) in the CENTER lane. An upright 1.8-tall bot skimming the
         *  surface can't fit (its head hits the STONE at S+1); it must submerge + go prone to thread. */
        void gap2x1Wall(Trial tr) {
            int wallX = (tr.poolX0 + tr.poolX1) / 2;
            for (int z = tr.zc - 1; z <= tr.zc + 1; z++) {
                for (int y = tr.yFloor + 1; y <= S + 2; y++) set(wallX, y, z, STONE);
            }
            set(wallX, S, tr.zc, WATER);       // top of the 2-tall gap (surface layer)
            set(wallX, S - 1, tr.zc, WATER);   // bottom of the 2-tall gap (one below surface)
            // (wallX, S-2, zc) stays STONE (below the gap); (wallX, S+1..S+2, zc) stays STONE (over the surface).
        }

        /** Replace the step-off lip block (the last approach-platform cell adjacent to the pool, center lane)
         *  with ICE — reduced friction settles the bot at a sub-block-center offset at the lip. */
        void iceLip(Trial tr) {
            set(tr.poolX0 - 1, S, tr.zc, ICE);
        }

        /** The lip-stuck repro: make the step-off lip block HONEY (sticky, no-jump) and bury the two body
         *  cells directly above it (S+1 feet, S+2 head) in COBWEB, so the bot — teleported to start dry ON
         *  the lip — sits at ≈zero velocity. With the goal at the pool bottom directly adjacent, the plan's
         *  first in-water move is a DOWN dive, so a dry-perched bot that advances its cursor past the
         *  water-entry waypoint tries to swim downward while still dry and stalls. */
        void honeyLip(Trial tr) {
            int lx = tr.poolX0 - 1;
            set(lx, S, tr.zc, HONEY);        // the lip block itself → honey
            set(lx, S + 1, tr.zc, COBWEB);   // feet cell → cobweb
            set(lx, S + 2, tr.zc, COBWEB);   // head cell → cobweb
        }

        /** A widened 5-lane tank (interior z = zc-2..zc+2, walls at zc-3/zc+3) with an over-height wall across
         *  all 5 lanes mid-pool, pierced by a SINGLE 1×1 water hole at (wallX, S-1, zc) — one block below the
         *  surface, center lane. Start is surface-level offset +2 lanes → an angled + descending approach. */
        void buildAngleGap(Trial tr) {
            int wx0 = tr.poolX0 - 1, wx1 = tr.poolX1 + 1;   // west/east walls
            int wz0 = tr.zc - 3, wz1 = tr.zc + 3;           // ±z walls (5 interior lanes between them)
            for (int x = wx0; x <= wx1; x++) {
                for (int z = wz0; z <= wz1; z++) {
                    set(x, tr.yFloor, z, STONE);            // floor slab
                    boolean perimeter = (x == wx0 || x == wx1 || z == wz0 || z == wz1);
                    if (perimeter) {
                        int wallTop = (z == wz0 || z == wz1) ? S + 2 : S;
                        for (int y = tr.yFloor + 1; y <= wallTop; y++) set(x, y, z, STONE);
                    }
                }
            }
            for (int x = tr.poolX0; x <= tr.poolX1; x++) {  // interior water (5 lanes)
                for (int z = tr.zc - 2; z <= tr.zc + 2; z++) {
                    for (int y = tr.yFloor + 1; y <= S; y++) set(x, y, z, WATER);
                }
            }
            for (int x = tr.approachX0; x <= tr.poolX0 - 1; x++) {   // approach platform (5 lanes wide)
                for (int z = tr.zc - 2; z <= tr.zc + 2; z++) set(x, S, z, STONE);
            }
            for (int x = tr.poolX1 + 1; x <= tr.poolX1 + 4; x++) {   // exit platform
                for (int z = tr.zc - 2; z <= tr.zc + 2; z++) set(x, S, z, STONE);
            }
            int wallX = (tr.poolX0 + tr.poolX1) / 2;                 // over-height wall across all 5 lanes
            for (int z = tr.zc - 2; z <= tr.zc + 2; z++) {
                for (int y = tr.yFloor + 1; y <= S + 2; y++) set(wallX, y, z, STONE);
            }
            set(wallX, S - 1, tr.zc, WATER);                        // the single 1×1 hole, one below the surface
        }

        /** The prone 1×1-thread maze (the in-game {@code StatefulSwimTest} shape): a solid stone block with a
         *  water channel carved along z=zc at feet level yB = S-1. init cell (2-deep when {@code deepInit},
         *  else 1-deep), a 1-deep run, a 1-tall hole in a wall (water feet, stone above), then a dry bank. */
        void buildGap(Trial tr, boolean deepInit) {
            int yB = S - 1;
            int x0 = tr.baseX + 1, x1 = tr.baseX + 9;
            int z0 = tr.zc - 2, z1 = tr.zc + 2;
            for (int x = x0; x <= x1; x++) {                // the solid enclosing block
                for (int z = z0; z <= z1; z++) {
                    for (int y = yB - 2; y <= yB + 4; y++) set(x, y, z, STONE);
                }
            }
            int z = tr.zc;
            set(tr.baseX + 2, yB, z, WATER);                            // init feet
            set(tr.baseX + 2, yB + 1, z, deepInit ? WATER : AIR);       // init head: water=2-deep / air=1-deep
            for (int x = tr.baseX + 3; x <= tr.baseX + 5; x++) {        // 1-deep run
                set(x, yB, z, WATER);
                set(x, yB + 1, z, AIR);
            }
            set(tr.baseX + 6, yB, z, WATER);                           // the 1-TALL hole: water feet, stone wall above
            for (int x = tr.baseX + 7; x <= tr.baseX + 8; x++) {       // dry bank (floor stays stone at yB-1)
                set(x, yB, z, AIR);
                set(x, yB + 1, z, AIR);
            }
        }

        /**
         * The owner's {@code SWS / SWA / SWA / SWS} shape (2026-08-15): a 1-wide vertical water column with a
         * 2-tall side pocket partway up, carved out of one solid stone block. The bot starts submerged at the
         * BOTTOM of the column, swims up, and must leave the column SIDEWAYS at exactly the pocket's feet level.
         *
         * <p><b>What it isolates.</b> The lateral escape is a {@link
         * com.orebit.mod.pathfinding.blockpathfinder.movements.Traverse Traverse}, not a swim move — {@code
         * Traverse.candidates} reads {@code startTopY = standable(startDesc) ? topYOf : 16} and so never requires
         * a standable START floor, which lets it emit the edge straight off a floating node. So the tile is a
         * pure <b>swim &rarr; ground handoff</b>, and the pocket is deliberately mid-column so that neither of
         * {@code Swim.reachedSwim}'s clamps applies: water above the pocket level means {@code fluidTop == 1.0}
         * (no surface clamp) and a carved cell at {@code wy+2} means {@code solidAt} is false (no ceiling clamp).
         * The swim's ride target therefore stays the full {@code wy + 1.0} — feet cell {@code wy+1} — while the
         * Traverse is framed for feet cell {@code wy}. That is the off-by-one-cell handoff, with nothing masking
         * it. The flagship's {@code (154,-8,103)} failure is the SAME defect seen through the ceiling clamp,
         * which happens to pull the target back down into the correct cell there.
         *
         * <p><b>No walk-off.</b> Every cell in the tile that is not the shaft or the pocket is solid, so a bot
         * that leaves the column at the wrong height has nowhere to go — it cannot accidentally recover by
         * wandering. The trial either exits at the right level or times out with the trace showing why.
         *
         * <p><b>Why {@code dry} needs a waterfall.</b> Sealed geometry cannot express {@code SWA}: a water source
         * beside air floods it. Vanilla only leaves a dry cell next to water when the water is FLOWING DOWN,
         * which suppresses lateral spread (see the fluid-spread model notes) — which is precisely why the
         * flagship's {@code (154,-7,103)} held water while {@code (155,-7,103)} logged {@code fl0.000}. So the
         * dry variant is a real waterfall: one source at {@link #SHAFT_TOP_Y} over an OPEN shaft that drains into
         * a wide shallow basin below. The basin is sized inside water's 7-block spread limit in every direction,
         * so it stabilises about one block deep and never backs the column up — the column keeps falling, and the
         * pocket stays dry, for the whole trial. The wet variant is fully sealed and has no fluid motion at all,
         * which makes it the bit-stable oracle of the pair; the dry one is the faithful reproduction.
         */
        void buildSideGap(Trial tr, boolean dry) {
            final int x0 = tr.baseX + 1, x1 = tr.baseX + 8;
            final int z0 = tr.zc - 2, z1 = tr.zc + 2;
            final int yBase = SHAFT_BOTTOM_Y - 2;   // solid floor under the basin
            final int sx = tr.baseX + 2;            // the shaft column
            final int zc = tr.zc;

            for (int x = x0; x <= x1; x++) {        // one solid block; everything below is carved out of it
                for (int z = z0; z <= z1; z++) {
                    for (int y = yBase; y <= S + 1; y++) set(x, y, z, STONE);
                }
            }

            // The vertical column. WET fills it with sources (sealed, static); DRY carves it open and puts a
            // single source at the top so vanilla runs it as a falling column.
            for (int y = SHAFT_BOTTOM_Y; y <= SHAFT_TOP_Y; y++) {
                set(sx, y, zc, dry ? AIR : WATER);
            }
            if (dry) {
                set(sx, SHAFT_TOP_Y, zc, WATER);                    // the waterfall's only source
                for (int x = x0; x <= x1; x++) {                    // drain basin: one open layer under the shaft
                    for (int z = z0; z <= z1; z++) set(x, SHAFT_BOTTOM_Y - 1, z, AIR);
                }
            }

            // The 2-tall side pocket: feet at SIDE_GAP_Y, head one above. Its floor (SIDE_GAP_Y-1) and cap
            // (SIDE_GAP_Y+2) are simply the surrounding stone, left untouched — that is the S in SWS.
            for (int x = tr.baseX + 3; x <= tr.baseX + 6; x++) {
                set(x, SIDE_GAP_Y, zc, dry ? AIR : WATER);
                set(x, SIDE_GAP_Y + 1, zc, dry ? AIR : WATER);
            }
        }

        /** The cruise-overshoot repro: a fully-enclosed, 1-wide SUBMERGED sprint-swim L-tunnel carved out of a
         *  solid stone block. A LONG +X approach leg (z=zc) runs to a corner, where the tunnel turns 90° to +Z.
         *  Stone floor + stone CEILING (y = S+1) over every carved cell keep the bot prone/submerged the whole
         *  way (no surface to breach → it stays in fast SprintSwim). The single passable straight-ahead branch
         *  off the corner (baseX+11, zc) is an UP-bubble-column (soul-sand floor + water) — classified
         *  impassable, so the ONLY route is +X→corner→+Z, and the planner routes AROUND the column. But that
         *  column sits exactly where +X cruise momentum overshoots the corner: a full-forward SprintSwim should
         *  coast the bot straight into it (drift off-path) instead of decelerating into the turn. Every cell that
         *  is not the tunnel or the column is solid stone, so the corner cannot be cut diagonally. */
        void buildSwimTurn(Trial tr) {
            int zc = tr.zc;
            int yFloor = tr.yFloor;              // = S - depth
            int yTop = S;                        // top water layer
            int yCeil = S + 1;                   // stone ceiling (forces submerged prone swimming)
            int xEntry = tr.baseX + 2;
            int xTurn = tr.baseX + 10;           // corner cell (xTurn, zc)
            int xBubble = xTurn + 1;             // (baseX+11, zc): the +X straight-ahead overshoot cell
            int zEnd = zc + 8;                   // end of the +Z leg (the goal cell)

            // (1) Fill the whole tile bounding box (a 1-cell wall margin around every carved cell) with solid
            //     stone. Carving then leaves ONLY the 1-wide tunnel + the bubble-column cell open.
            int bx0 = xEntry - 1, bx1 = xBubble + 1;   // baseX+1 .. baseX+12
            int bz0 = zc - 1,     bz1 = zEnd + 1;      // (zc-1) .. (zEnd+1)
            for (int x = bx0; x <= bx1; x++) {
                for (int z = bz0; z <= bz1; z++) {
                    for (int y = yFloor - 1; y <= yCeil; y++) set(x, y, z, STONE);
                }
            }

            // (2) Carve the +X approach leg (z=zc), full water from just above the floor to the top layer.
            for (int x = xEntry; x <= xTurn; x++) {
                for (int y = yFloor + 1; y <= yTop; y++) set(x, y, zc, WATER);
            }

            // (3) Carve the +Z leg (x=xTurn), same water column — the corner cell (xTurn,zc) is shared.
            for (int z = zc; z <= zEnd; z++) {
                for (int y = yFloor + 1; y <= yTop; y++) set(xTurn, y, z, WATER);
            }

            // (4) The UP-bubble-column straight ahead of the corner: soul-sand floor + water above (vanilla
            //     forms the column). Reuses the bubbleup trial's construction (floorFeature places SOUL_SAND at
            //     the floor with water above → an impassable upward column). It is cardinal-adjacent to the
            //     corner (so momentum can drift the bot into it) but walled off from the +Z leg (so it is a
            //     dead-end branch the planner routes AROUND, never through).
            set(xBubble, yFloor, zc, SOUL_SAND);
            for (int y = yFloor + 1; y <= yTop; y++) set(xBubble, y, zc, WATER);
        }

        /** The bubble-column serpentine (SWIM_MAZE): walls = SOUL_SAND-floored water columns → vanilla forms an
         *  UP-bubble-column in each. Historically the momentum-overshoot repro; since the 2026-08-15 planned-
         *  column ride fix the planner legally RIDES a wall column over the maze, so this card now verifies the
         *  multi-column ride — the serpentine-cruise duty lives in {@link #buildMazePortal}/{@link #buildMazeLava}. */
        void buildSwimMaze(Trial tr) {
            buildMazeCore(tr, SOUL_SAND, WATER, true);
        }

        /** The end-portal serpentine (SWIM_MAZE_PORTAL): walls = frameless END_PORTAL columns on stone floors.
         *  Planner-impassable (PORTAL_BIT walker avoidance) and not bubbleUp → no ride edges: the serpentine is
         *  the ONLY plannable route. Physically, any box-clip of a wall cell teleports INSTANTLY (Portal
         *  transition time 0 — javap-verified, see the Kind doc), so an overshoot is caught by the course's
         *  dimension tripwire the tick it happens. The cruise verifier the ride shortcut took from SWIM_MAZE. */
        void buildMazePortal(Trial tr) {
            buildMazeCore(tr, STONE, END_PORTAL, true);
        }

        /** The lava-ceiling serpentine (SWIM_MAZE_LAVA): the bubble maze under an OPEN-fence-gate layer at S+1
         *  with a LAVA blanket at S+2 (stone rim ring containing it). The open gates are collisionless
         *  (Shapes.empty() when OPEN — a closed gate's wall would ARREST the conveyed bot below the lava and
         *  protect it, owner-refuted 2026-08-16), so a wall-column clip conveys the bot straight through into
         *  the lava: burn damage (the course's health tripwire) is the clip verdict — which a plain stone roof
         *  cannot deliver (the swimturn lesson: a roofed column clip is a recoverable pin). The lava is static
         *  because a fluid can never flow into an OCCUPIED cell. See the Kind doc for the model tension this
         *  deliberately creates with RideBubbleColumn's surface exit (open gate = model air). */
        void buildMazeLava(Trial tr) {
            buildMazeCore(tr, SOUL_SAND, WATER, false);
            int yTop = S;
            int rx0 = tr.baseX + 1, rx1 = tr.baseX + 11;
            int rz0 = tr.zc,        rz1 = tr.zc + 4;
            // Containment rim FIRST (so the lava sources are born enclosed), then the gate layer, then the lava.
            for (int x = rx0 - 1; x <= rx1 + 1; x++) {
                for (int z = rz0 - 1; z <= rz1 + 1; z++) {
                    boolean rim = x < rx0 || x > rx1 || z < rz0 || z > rz1;
                    if (rim) { set(x, yTop + 1, z, STONE); set(x, yTop + 2, z, STONE); }
                }
            }
            for (int x = rx0; x <= rx1; x++) {
                for (int z = rz0; z <= rz1; z++) set(x, yTop + 1, z, GATE_OPEN);
            }
            for (int x = rx0; x <= rx1; x++) {
                for (int z = rz0; z <= rz1; z++) set(x, yTop + 2, z, LAVA);
            }
        }

        /** Shared serpentine-tank core. The tank water is 4-deep (floor at yFloor = S-4, water yFloor+1..S). A
         *  1-wide safe CHANNEL winds boustrophedon through it — a +X leg at z=zc, one lane over in +Z, a -X leg
         *  at z=zc+2, one more lane over, a +X leg at z=zc+4 — its floor plain STONE, its body WATER. EVERY
         *  other rectangle cell is a WALL: floor {@code wallFloor}, body {@code wallFill} (soul-sand+water →
         *  bubble columns; stone+end-portal → teleport walls). Adjacent lanes on either side of the channel are
         *  walls, so at each turn the cell STRAIGHT AHEAD along the incoming leg is a wall: cruise momentum that
         *  fails to brake into the turn drifts the bot into it. {@code roofFirstLeg} pins the bot submerged/
         *  prone through the opening +X cruise (STONE at S+1 over leg 1 only); later legs are left open-sky.
         *  Cells outside the water region are solid stone so the corners can't be cut diagonally. */
        void buildMazeCore(Trial tr, BlockState wallFloor, BlockState wallFill, boolean roofFirstLeg) {
            int zc = tr.zc;
            int yFloor = tr.yFloor;              // = S - 4
            int yTop = S;                        // top water layer
            int xW = tr.baseX + 2;               // west channel end (leg starts/ends)
            int xE = tr.baseX + 10;              // east channel end (9-cell legs)
            int z0 = zc;                         // leg 1 lane (+X)
            int z1 = zc + 2;                     // leg 2 lane (-X)
            int z2 = zc + 4;                     // leg 3 lane (+X)

            // Water region (holds the channel + every wall), and the surrounding stone margin.
            int rx0 = tr.baseX + 1, rx1 = tr.baseX + 11;   // x span of the water rectangle
            int rz0 = z0,           rz1 = z2;              // z span (zc .. zc+4)

            // (1) Fill the whole tile bounding box (water rectangle + a 1-cell stone margin, from the floor base
            //     up to the surface) with solid stone. Carving then leaves ONLY the water columns open.
            int bx0 = rx0 - 1, bx1 = rx1 + 1;
            int bz0 = rz0 - 1, bz1 = rz1 + 1;
            for (int x = bx0; x <= bx1; x++) {
                for (int z = bz0; z <= bz1; z++) {
                    for (int y = yFloor - 1; y <= yTop; y++) set(x, y, z, STONE);
                }
            }

            // (2) Carve every water-rectangle cell into a WALL column (the default; the safe channel overrides
            //     both floor and body below).
            for (int x = rx0; x <= rx1; x++) {
                for (int z = rz0; z <= rz1; z++) {
                    set(x, yFloor, z, wallFloor);
                    for (int y = yFloor + 1; y <= yTop; y++) set(x, y, z, wallFill);
                }
            }

            // (3) Stamp the safe serpentine channel: STONE floor, WATER body. Boustrophedon — leg 1 (+X) at z0,
            //     turn up to z1, leg 2 (-X) at z1, turn up to z2, leg 3 (+X) at z2. The turn connectors run
            //     through the intermediate wall lanes (zc+1 / zc+3) at the leg ends, so the only way across is
            //     a genuine 90° corner (the overshoot cell straight ahead stays a wall).
            for (int x = xW; x <= xE; x++) stampChannel(x, z0, yFloor, yTop);   // leg 1: +X at z0
            for (int z = z0; z <= z1; z++)  stampChannel(xE, z, yFloor, yTop);  // turn 1: +Z at the east end
            for (int x = xW; x <= xE; x++) stampChannel(x, z1, yFloor, yTop);   // leg 2: -X at z1
            for (int z = z1; z <= z2; z++)  stampChannel(xW, z, yFloor, yTop);  // turn 2: +Z at the west end
            for (int x = xW; x <= xE; x++) stampChannel(x, z2, yFloor, yTop);   // leg 3: +X at z2

            // (4) Optionally roof the first leg (STONE ceiling at S+1) so the bot stays pinned submerged/prone
            //     through the opening +X cruise; later legs stay open unless the variant adds its own ceiling.
            if (roofFirstLeg) {
                for (int x = xW; x <= xE; x++) set(x, yTop + 1, z0, STONE);
            }
        }

        /** One channel cell: plain STONE floor, plain WATER body (yFloor+1..yTop). */
        private void stampChannel(int x, int z, int yFloor, int yTop) {
            set(x, yFloor, z, STONE);
            for (int y = yFloor + 1; y <= yTop; y++) set(x, y, z, WATER);
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
                for (String line : results) {
                    w.write(line);
                    w.write('\n');
                }
            } catch (IOException e) {
                OrebitCommon.LOGGER.error("[Orebit/swim] could not write {}", file, e);
            }
            try { if (trace != null) trace.close(); } catch (IOException ignored) { }
            OrebitCommon.LOGGER.info("[Orebit/swim] DONE ({}) — {} passed / {} failed of {} — halting",
                    reason, passed, failed, trials.size());
            server.halt(false);
            Thread exiter = new Thread(() -> {
                server.halt(true);
                System.exit(0);
            }, "orebit-swim-exit");
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
