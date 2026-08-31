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

    /**
     * {@code -Dorebit.vinebridge=grab} — the flagship (431,66,606) Climb-overshoot wedge, replayed as a
     * deterministic scene (run 2026-08-30-1, 00:05:26). The flagship geometry, mapped verbatim in shape
     * (flagship y-65 → Y0, flagship z-600 → BASE_Z; travel along +Z, corridor at x = BASE_X):
     *
     * <pre>
     *   z+0..2   3-wide start pad, floor Y0 (feet Y0+1)
     *   z+3      1-wide funnel, floor Y0
     *   z+4      up-step, floor Y0+1 (top Y0+2) — the flagship's bot-PLACED takeoff, pre-painted
     *   z+5      COCOA POD age 2 at floor level (collision top 12/16 — the flagship's toFloorTopY=12)
     *            on a jungle-log trunk at x−1, with a VINE curtain in the feet+head cells (west-attached)
     *   z+6      down-step, floor Y0 (top Y0+1) — the flagship's Descend wp2 floor
     *   z+7      up-step, floor Y0+1
     *   z+8..9   goal ledge, floor Y0+1 (goal feet at z+8)
     * </pre>
     *
     * The flagship plan through this strip was {@code Ascend(z4,pre-placed) → Climb(z5, lateral grab) →
     * Descend(z6) → Ascend(z7)}. The wedge: the Ascend arrives at the takeoff carrying ~0.106 b/t, the
     * Climb grab arms jump, and while AIRBORNE over the vine (control authority ~0.02/t) the
     * position-only {@code recenter} law goes dead 0.04 short of the column, coasts through it, flips
     * 180° and loses — the bot lands straddling the cocoa with its centre in the z+6 column, outside the
     * Climb's admitted cells AND outside the Descend's settle band, so the envelope fails and the
     * fail→hold policy parks it forever. EXPECTED (pre-servo-fix): FAIL budget exhausted with finalPos z
     * ≈ BASE_Z+6.03, y ≈ Y0+1.75; the log carries the same {@code recenter:dead} → 180°-flip → {@code
     * step FAILED (validity envelope) Climb} signature as the flagship. PASS = standing on the goal
     * ledge.
     */
    private static boolean grabVariant() {
        return "grab".equalsIgnoreCase(System.getProperty("orebit.vinebridge", ""));
    }

    /**
     * {@code -Dorebit.vinebridge=pathdiag} — the flagship (1215,65,1223) Diagonal cell-quantization
     * wedge (run 2026-08-30 16:25:04), replayed as a deterministic scene: a worn VILLAGE PATH crossing
     * a grass field diagonally. The flagship geometry (read out of the run save): {@code dirt_path}
     * cells (collision top 15/16, resting feet Y0+0.9375, foot cell Y0) on the diagonal, full-height
     * {@code grass_block}s (top Y0+1.000) at every crossing corner.
     *
     * <p>The wedge: a {@code Diagonal} between two path cells has band {@code [Y0, Y0]} (both floors
     * topY=15), but the diagonal track unavoidably straddles the corner grass mid-step — vanilla rests
     * the box on the highest overlapped surface, botY snaps {@code Y0+0.938 → Y0+1.000} (a physical
     * rise of 1/16), the quantized foot cell flips {@code Y0 → Y0+1}, and the envelope's CELL-test
     * upper bound reads a 0.062-block bump as "lifted off the step": {@code step FAILED (validity
     * envelope) Diagonal} → fail→hold forever. The corner COLUMNS are explicitly admitted by the
     * envelope's 2×2 XZ sweep; only their HEIGHT was not. EXPECTED (pre-envelope-fix): FAIL budget
     * exhausted, finalPos on the diagonal with botY = Y0+1.000 exactly. PASS = standing on the goal
     * pad past the path.
     *
     * <p>Scene (travel −X/+Z, matching the flagship's heading): 3×3 grass spawn pad → a 1/16 step down
     * onto {@code PATH_LEN} dirt-path cells on the diagonal {@code (BASE_X−i, Y0, BASE_Z+i)}, with HOP
     * 1's two crossing corners full grass (and only hop 1's — see the paint comment for why corners on
     * every hop hand the planner a parallel grass bypass). The GOAL is the path's far end — no terminal
     * step up. A stone CEILING at {@code Y0+3} covers the whole field: this card's first two cuts
     * taught that a pathfinder this good must be fenced honestly — cut 1 routed a parallel grass
     * diagonal, cut 2 {@code DiagonalParkour}'d pad → isolated corner → goal pad, never touching the
     * path. The ceiling kills every jump ARC (a takeoff's head tops out above {@code Y0+3}) while the
     * walking moves and the corner straddle-lift need none, so the path diagonals are finally the only
     * route. Void elsewhere.
     */
    private static boolean pathdiagVariant() {
        return "pathdiag".equalsIgnoreCase(System.getProperty("orebit.vinebridge", ""));
    }

    /**
     * {@code -Dorebit.vinebridge=pathdown} — {@link #pathdiagVariant pathdiag}'s corner-lift wedge, one
     * level down: the village path DESCENDS one block per diagonal hop, so the steps are
     * {@code DiagonalDescend}. The wedge grass sits at hop 1's FROM-floor level — exactly the cell the
     * candidates' own KNOWN RESIDUAL declines to sweep (corner at start-floor level), whose claim that a
     * solid there is "inside the envelope's corner band" holds only for FULL floors. On the 15/16 path
     * (resting fromFootY+0.938) the straddle lifts the box to exactly fromFootY+1.000 during the
     * GROUNDED walk-off (no jump, no race), the foot cell flips out of every column's cell band, and
     * fail→hold parks it. EXPECTED pre-fix: FAIL budget with {@code step FAILED (validity envelope)
     * DiagonalDescend}. The route never jumps, so a full ceiling at Y0+3 kills the pad-level parkour
     * arcs onto the isolated wedge grass; mid-path arcs are transit-blocked by the contiguous staircase
     * itself.
     */
    private static boolean pathdownVariant() {
        return "pathdown".equalsIgnoreCase(System.getProperty("orebit.vinebridge", ""));
    }

    /**
     * {@code -Dorebit.vinebridge=pathup} — the ASCENDING twin: {@code DiagonalAscend} steps up the
     * 15/16 path. Its candidates sweep corners {@code y+1..y+3}, so an in-2×2 landing-level corner is
     * refused outright and the wedge must come from just BEYOND the swept 2×2: the NEXT hop's from-level
     * corner terrain — full grass diagonally forward of hop 1's landing, at the landing's own floor
     * level (top landFootY+1.000). The jump arc lands past the cell centre with its leading edge over
     * that grass; a grounded touchdown (or first settle tick) at exactly landFootY+1.000 sits outside
     * the target column's {@code [landFootY−1, landFootY]} cell band → {@code step FAILED (validity
     * envelope) DiagonalAscend}. NOTE the cursor race: a lift AFTER the cursor advances to the next hop
     * lands in that hop's from-band (which already spans +1) and is tolerated — the card relies on the
     * landing-tick lift, so a green pre-fix run means the arc landed short; read the trace before
     * touching the fix. No full ceiling (the route IS jump arcs); the pad columns alone are capped at
     * Y0+4 so a rising DiagonalParkour cannot skip the wedge hops from the pad (the takeoff headroom
     * refusal), while P0 — outside the cap — keeps its own entry jump.
     */
    private static boolean pathupVariant() {
        return "pathup".equalsIgnoreCase(System.getProperty("orebit.vinebridge", ""));
    }

    /**
     * {@code -Dorebit.vinebridge=vineup} — the flagship (207,118,297) DiagonalAscend-into-a-vine
     * livelock (run 2026-08-30 22:10, attempt 3), replayed as a deterministic scene. The flagship
     * geometry (read out of the run save AND byte-identical in the master — no growth involved): a
     * plain diagonal-up step whose LANDING feet cell is the bottom of a 3-cell vine curtain
     * (landing feet + 2 above) over a solid floor, both crossing corners carrying from-level floors.
     *
     * <p>The wedge is the 3ccb23d jump gate, proven by same-cell before/after across flagship runs:
     * attempt 1 (old {@code footY() < landFootY} gate) executed the IDENTICAL step — {@code exec
     * DiagonalAscend wp1 -> (207,118,297)} — released jump mid-arc at botY=118.001 and landed clean;
     * attempt 3 (waypoint gate {@code !(grounded && atWaypoint)}) wedged. The onset trace refines
     * the mechanism to an <b>arrival-tick re-jump race</b> ({@code climb=false} through the whole
     * arc — no mid-flight grab): entered at CRUISE the descent is shallow, the box grounds at
     * exactly landFootY on the landing's LIP one tick before the foot cell flips, and the flip
     * tick's jump input — computed from the stale not-at-waypoint state — fires a full grounded
     * jump off the landing (cardinal Ascend's comment names this exact "mirror case" its release
     * rule stops). Now airborne with feet in the vine and jump held, the +0.2/t climb ratchet rides
     * the curtain to its top and bobs there forever — never grounded, so the gate never releases,
     * the envelope (gated on grounded/water/lava) never evaluates, and the steer pirouettes
     * 180°/tick over the dead-centred XZ. A STANDSTILL launch lands centre-first inside the
     * waypoint cell and releases cleanly (cuts 1–2 of this card, both PASS) — hence the lead-in
     * hop. EXPECTED pre-fix: FAIL budget exhausted, ZERO {@code step FAILED} lines, finalPos
     * hovering over the landing at y ≈ vineTop+0.9..1.1 (here Y0+4.9..Y0+5.1). PASS = standing on
     * the landing floor.
     *
     * <p>Scene — the flagship's own wp5..wp7 mapped verbatim (see {@code paintVineup}): grass pad →
     * FLAT Diagonal (−x,+z) through the vined to-x corner onto takeoff C (feet {@code Y0+1}) →
     * wedge DiagonalAscend (+x,+z) to D (feet {@code Y0+2}), whose column holds vines
     * {@code Y0+2..Y0+4} hung on a stone wall one further east (the flagship's trunk side). The
     * to-x corner carries the flagship's full from-level vine column — triple-duty: arc support,
     * the flat lead-in's transit cell, and the fence that kills the cardinal-ladder bypass (Ascend
     * cannot launch from a climbable feet cell). Void elsewhere; the goal IS the landing.
     */
    private static boolean vineupVariant() {
        return "vineup".equalsIgnoreCase(System.getProperty("orebit.vinebridge", ""));
    }

    /**
     * {@code -Dorebit.vinebridge=vineupgreen} — {@link #vineupVariant vineup}'s ROUTE-EXISTS sibling:
     * the same vined landing with cut 4's corner rung restored (from-level grass on the to-x corner),
     * which hands the planner the cardinal ladder — Traverse onto the rung, cardinal {@link
     * com.orebit.mod.pathfinding.blockpathfinder.movements.Ascend} up INTO the vined feet cell (its
     * climbable-transit discriminator lands it correctly), Traverse out along the strip. Post-arc-rule
     * this is the green gate proving vined-diagonal terrain still routes when ANY composable cells
     * exist (the plain vineup geometry deliberately offers none, so its post-fix expectation is a
     * clean refusal at the pad instead). EXPECTED: PASS crossing to the strip end.
     */
    private static boolean vineupGreenVariant() {
        return "vineupgreen".equalsIgnoreCase(System.getProperty("orebit.vinebridge", ""));
    }

    /** Either member of the vineup pair — shared scene, goal, spawn, and arrival oracle. */
    private static boolean vineupFamily() {
        return vineupVariant() || vineupGreenVariant();
    }

    /** Diagonal dirt-path cells in the path scenes — enough hops that one takes off with cruise carry. */
    private static final int PATH_LEN = 5;

    public static void register(PlatformEvents events) {
        if (System.getProperty("orebit.vinebridge") == null) {
            return;
        }
        Course course = new Course();
        events.onServerStarted(course::start);
        events.onWorldTickEnd(course::tick);
        OrebitCommon.LOGGER.info("[Orebit/vinebridge] armed: variant={}",
                vineupVariant() ? "VINEUP (DiagonalAscend into a vine curtain — flagship 207,118,297 replay)"
                        : vineupGreenVariant() ? "VINEUPGREEN (vined landing routed via the cardinal ladder — the route-exists gate)"
                        : pathdownVariant() ? "PATHDOWN (DiagonalDescend corner-lift)"
                        : pathupVariant() ? "PATHUP (DiagonalAscend corner-lift)"
                        : pathdiagVariant() ? "PATHDIAG (flagship Diagonal cell-quantization replay)"
                        : grabVariant() ? "GRAB (flagship Climb-overshoot replay)"
                        : floorVariant() ? "FLOOR (Traverse-owned, span=" + SPAN + ")"
                        : "FEET (Climb-owned curtain, span=" + SPAN + ")");
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
            // comeTo takes a FEET cell and targets floor = feet-1, so path goals are passed as
            // floor+1 even though a 15/16 floor SEATS the feet in the floor cell itself — passing the
            // path cell's own Y sends the search after a void floor one below (16 expansions, BLOCKED,
            // bot never leaves spawn; measured, pathdiag cut 3).
            this.goal = vineupFamily()
                    // The exit strip's far end, PAST the vined landing — D must be an intermediate
                    // waypoint or comeTo's arrival radius masks the race (cuts 6–7).
                    ? new BlockPos(BASE_X + 1, Y0 + 2, BASE_Z + 3)
                    : pathdownVariant()
                    ? new BlockPos(BASE_X - (PATH_LEN - 1), Y0 - (PATH_LEN - 1) + 1, BASE_Z + PATH_LEN - 1)
                    : pathupVariant()
                    // pathup's path floors run Y0+2..Y0+PATH_LEN (pad one level up) — P4 floor Y0+PATH_LEN.
                    ? new BlockPos(BASE_X - (PATH_LEN - 1), Y0 + PATH_LEN + 1, BASE_Z + PATH_LEN - 1)
                    : pathdiagVariant()
                    ? new BlockPos(BASE_X - (PATH_LEN - 1), Y0 + 1, BASE_Z + PATH_LEN - 1)
                    : grabVariant()
                    ? new BlockPos(BASE_X, Y0 + 2, BASE_Z + 8)
                    : new BlockPos(BASE_X + SPAN + 2, Y0 + 1, BASE_Z);
            owner = new FakePlayerEntity(server, level, new GameProfile(
                    UUID.nameUUIDFromBytes("OrebitVineBridge:owner".getBytes(StandardCharsets.UTF_8)),
                    "VineBridge"));
            // NB: no addFreshEntity — BotManager.spawnBotFor owns placement. Adding a connection-less
            // FakePlayerEntity to the level NPEs the first packet send (learned the hard way).
            if (pathupVariant()) {
                owner.setPos(BASE_X + 2.5, Y0 + 2, BASE_Z - 0.5);   // pathup's stone pad is one level up
            } else if (vineupFamily()) {
                owner.setPos(BASE_X + 2.5, Y0 + 1, BASE_Z - 1.5);   // middle of vineup's grass pad
            } else if (pathdiagVariant() || pathdownVariant()) {
                owner.setPos(BASE_X + 2.5, Y0 + 1, BASE_Z - 0.5);   // middle of the 3x3 grass spawn pad
            } else if (grabVariant()) {
                owner.setPos(BASE_X + 0.5, Y0 + 1, BASE_Z + 1.5);   // middle of the 3-wide start pad
            } else {
                owner.setPos(BASE_X + 0.5, Y0 + 1, BASE_Z + 0.5);
            }
            BotManager.spawnBotFor(owner);
            bot = BotManager.botFor(owner);
            if (bot == null) {
                finish("bot never spawned");
                return;
            }
            if (vineupFamily()) {
                // Deterministic start: spawn-beside-owner snapped the bot straight onto the wedge
                // TAKEOFF in cut 5, collapsing the whole approach into a standstill launch (which is
                // exactly the shape that does NOT arm the race). Pin it to the pad's far corner so
                // the plan is the full Traverse → flat-Diagonal → DiagonalAscend chain at cruise.
                bot.setPos(BASE_X + 3.5, Y0 + 1, BASE_Z - 2.5);
            }
            try {
                trace = Files.newBufferedWriter(ConfigDir.serverDir(server).resolve("orebit-vinebridge-trace.txt"),
                        StandardCharsets.UTF_8);
                trace.write("tick x y z footY onGround climbable climbBelow move driveState\n");
            } catch (IOException ignored) {
                // trace is best-effort; the result file is the contract
            }
        }

        /**
         * The flagship Climb-overshoot strip — see {@link #grabVariant} for the cell-by-cell map and the
         * flagship provenance. Everything else is void, so there is no way around the vine curtain.
         */
        void paintGrab() {
            BlockState stone = Blocks.STONE.defaultBlockState();
            BlockState air = Blocks.AIR.defaultBlockState();
            for (int dx = -4; dx <= 4; dx++) {
                for (int dy = -4; dy <= 10; dy++) {
                    for (int dz = -2; dz <= 12; dz++) {
                        level.setBlockAndUpdate(new BlockPos(BASE_X + dx, Y0 + dy, BASE_Z + dz), air);
                    }
                }
            }
            // 3-wide start pad (the bot spawns beside the owner; a 1-wide pad risks a void spawn).
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = 0; dz <= 2; dz++) {
                    level.setBlockAndUpdate(new BlockPos(BASE_X + dx, Y0, BASE_Z + dz), stone);
                }
            }
            // 1-wide funnel, then the pre-painted takeoff up-step (the flagship's bot-placed block).
            level.setBlockAndUpdate(new BlockPos(BASE_X, Y0, BASE_Z + 3), stone);
            level.setBlockAndUpdate(new BlockPos(BASE_X, Y0 + 1, BASE_Z + 4), stone);
            // The jungle trunk (west of the corridor), painted FIRST so the cocoa + vines survive their
            // placement support checks.
            for (int dy = 1; dy <= 3; dy++) {
                level.setBlockAndUpdate(new BlockPos(BASE_X - 1, Y0 + dy, BASE_Z + 5),
                        Blocks.JUNGLE_LOG.defaultBlockState());
            }
            // The cocoa pod: the flagship's 12/16-top floor cell (every cocoa age tops out at 12/16).
            level.setBlockAndUpdate(new BlockPos(BASE_X, Y0 + 1, BASE_Z + 5),
                    Blocks.COCOA.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.CocoaBlock.AGE, 2)
                            .setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING,
                                    net.minecraft.core.Direction.WEST));
            // The vine curtain in the feet + head cells, hanging on the trunk's east face.
            for (int dy = 2; dy <= 3; dy++) {
                level.setBlockAndUpdate(new BlockPos(BASE_X, Y0 + dy, BASE_Z + 5),
                        Blocks.VINE.defaultBlockState().setValue(VineBlock.WEST, Boolean.TRUE));
            }
            // Down-step past the curtain (the flagship Descend's floor), up-step, goal ledge.
            level.setBlockAndUpdate(new BlockPos(BASE_X, Y0, BASE_Z + 6), stone);
            level.setBlockAndUpdate(new BlockPos(BASE_X, Y0 + 1, BASE_Z + 7), stone);
            level.setBlockAndUpdate(new BlockPos(BASE_X, Y0 + 1, BASE_Z + 8), stone);
            level.setBlockAndUpdate(new BlockPos(BASE_X, Y0 + 1, BASE_Z + 9), stone);
        }

        /**
         * The village-path diagonal — see {@link #pathdiagVariant} for the cell map and the flagship
         * provenance. Everything else is void, so the path diagonal is the only route.
         */
        void paintPathdiag() {
            BlockState air = Blocks.AIR.defaultBlockState();
            BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
            BlockState path = Blocks.DIRT_PATH.defaultBlockState();
            for (int dx = -(PATH_LEN + 4); dx <= 5; dx++) {
                for (int dy = -4; dy <= 8; dy++) {
                    for (int dz = -4; dz <= PATH_LEN + 4; dz++) {
                        level.setBlockAndUpdate(new BlockPos(BASE_X + dx, Y0 + dy, BASE_Z + dz), air);
                    }
                }
            }
            // 3x3 grass spawn pad (feet Y0+1), east of the path head; its west edge touches P0.
            for (int dx = 1; dx <= 3; dx++) {
                for (int dz = -2; dz <= 0; dz++) {
                    level.setBlockAndUpdate(new BlockPos(BASE_X + dx, Y0, BASE_Z + dz), grass);
                }
            }
            // The worn path on the diagonal. Grass corners on HOP 1 ONLY — the exact flagship
            // neighbourhood at (1214..1215, 64, 1223..1224), entered with cruise carry from hop 0,
            // matching the flagship's previous-diagonal takeoff (vel 0.1167). Deliberately NOT on every
            // hop: corner cells of consecutive hops line up into grass diagonals PARALLEL to the path,
            // and the first cut of this card learned the planner will happily route along that flat
            // grass line and never touch the path at all (PASS in 72 ticks, wedge untouched). One
            // isolated corner pair has no such bypass: leaving the path over a corner costs an
            // Ascend+Descend pair against one flat Diagonal.
            for (int i = 0; i < PATH_LEN; i++) {
                level.setBlockAndUpdate(new BlockPos(BASE_X - i, Y0, BASE_Z + i), path);
            }
            level.setBlockAndUpdate(new BlockPos(BASE_X - 2, Y0, BASE_Z + 1), grass);
            level.setBlockAndUpdate(new BlockPos(BASE_X - 1, Y0, BASE_Z + 2), grass);
            // The ceiling that makes the path the ONLY route (see the variant Javadoc): low enough that
            // no jump arc fits (apex head > Y0+3), high enough that a walking body on any floor here —
            // grass feet Y0+1, body top Y0+2.8 — still clears it.
            BlockState stone = Blocks.STONE.defaultBlockState();
            for (int dx = -(PATH_LEN + 2); dx <= 4; dx++) {
                for (int dz = -3; dz <= PATH_LEN + 2; dz++) {
                    level.setBlockAndUpdate(new BlockPos(BASE_X + dx, Y0 + 3, BASE_Z + dz), stone);
                }
            }
        }

        /**
         * The DESCENDING path — see {@link #pathdownVariant}. Pad → path stepping one down per diagonal
         * hop, wedge grass at hop 1's from-floor level, full ceiling at Y0+3 (the route never jumps).
         */
        void paintPathdown() {
            BlockState air = Blocks.AIR.defaultBlockState();
            BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
            BlockState path = Blocks.DIRT_PATH.defaultBlockState();
            BlockState stone = Blocks.STONE.defaultBlockState();
            for (int dx = -(PATH_LEN + 4); dx <= 5; dx++) {
                for (int dy = -(PATH_LEN + 5); dy <= 9; dy++) {
                    for (int dz = -4; dz <= PATH_LEN + 4; dz++) {
                        level.setBlockAndUpdate(new BlockPos(BASE_X + dx, Y0 + dy, BASE_Z + dz), air);
                    }
                }
            }
            for (int dx = 1; dx <= 3; dx++) {
                for (int dz = -2; dz <= 0; dz++) {
                    level.setBlockAndUpdate(new BlockPos(BASE_X + dx, Y0, BASE_Z + dz), grass);
                }
            }
            for (int i = 0; i < PATH_LEN; i++) {
                level.setBlockAndUpdate(new BlockPos(BASE_X - i, Y0 - i, BASE_Z + i), path);
            }
            // The wedge: hop 1's crossing corners at hop 1's FROM-floor level (the candidates' unswept
            // start-floor-level residual). Full grass, tops at Y0 — a 1/16 ledge above P1's 15/16 rest.
            level.setBlockAndUpdate(new BlockPos(BASE_X - 2, Y0 - 1, BASE_Z + 1), grass);
            level.setBlockAndUpdate(new BlockPos(BASE_X - 1, Y0 - 1, BASE_Z + 2), grass);
            for (int dx = -(PATH_LEN + 2); dx <= 4; dx++) {
                for (int dz = -3; dz <= PATH_LEN + 2; dz++) {
                    level.setBlockAndUpdate(new BlockPos(BASE_X + dx, Y0 + 3, BASE_Z + dz), stone);
                }
            }
        }

        /**
         * The ASCENDING path — see {@link #pathupVariant}. Pad → full-stone P0 (flat entry, its own jump
         * stays clear) → path stepping one UP per diagonal hop; wedge grass forward-diagonal of hop 1's
         * landing at the landing's floor level; pad columns alone capped at Y0+4.
         */
        void paintPathup() {
            BlockState air = Blocks.AIR.defaultBlockState();
            BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
            BlockState path = Blocks.DIRT_PATH.defaultBlockState();
            BlockState stone = Blocks.STONE.defaultBlockState();
            for (int dx = -(PATH_LEN + 4); dx <= 5; dx++) {
                for (int dy = -4; dy <= PATH_LEN + 10; dy++) {
                    for (int dz = -4; dz <= PATH_LEN + 4; dz++) {
                        level.setBlockAndUpdate(new BlockPos(BASE_X + dx, Y0 + dy, BASE_Z + dz), air);
                    }
                }
            }
            // Stone pad + stone P0 one level up (feet Y0+2): the WEDGE hop is the FIRST jump, taken off
            // FULL STONE — the robust arc shape (cuts 1 and 2 of this variant: a stone→15/16-path
            // diagonal arc from a mid-course cell reliably landed z-short, first falling through a bare
            // corner, then parking on the corner support in an envelope-silent jump-in-place livelock —
            // a separate latent finding, logged in the audit memory).
            for (int dx = 1; dx <= 3; dx++) {
                for (int dz = -2; dz <= 0; dz++) {
                    level.setBlockAndUpdate(new BlockPos(BASE_X + dx, Y0 + 1, BASE_Z + dz), stone);
                }
            }
            level.setBlockAndUpdate(new BlockPos(BASE_X, Y0 + 1, BASE_Z), stone);   // P0, feet Y0+2
            // The ascending path: P1..P4 at floors Y0+2..Y0+5.
            for (int i = 1; i < PATH_LEN; i++) {
                level.setBlockAndUpdate(new BlockPos(BASE_X - i, Y0 + 1 + i, BASE_Z + i), path);
            }
            // Corner support at every hop's FROM level (rests inside the ascend's admitted band —
            // recovery terrain, not wedge sources). Hop 1's from-level corners are the wedge cells below.
            for (int i = 0; i < PATH_LEN - 1; i++) {
                if (i == 1) continue;
                level.setBlockAndUpdate(new BlockPos(BASE_X - i - 1, Y0 + 1 + i, BASE_Z + i), grass);
                level.setBlockAndUpdate(new BlockPos(BASE_X - i, Y0 + 1 + i, BASE_Z + i + 1), grass);
            }
            // The wedge: full grass diagonally FORWARD of hop 0's landing (P1), at P1's own floor level
            // (Y0+2, tops exactly landFootY+1.0) — just beyond hop 0's swept 2x2.
            level.setBlockAndUpdate(new BlockPos(BASE_X - 2, Y0 + 2, BASE_Z + 1), grass);
            level.setBlockAndUpdate(new BlockPos(BASE_X - 1, Y0 + 2, BASE_Z + 2), grass);
            // Pad-column cap: refuses rising-parkour takeoffs from the pad (headroom) without touching
            // P0's own wedge-hop jump one column west.
            for (int dx = 1; dx <= 3; dx++) {
                for (int dz = -3; dz <= 1; dz++) {
                    level.setBlockAndUpdate(new BlockPos(BASE_X + dx, Y0 + 5, BASE_Z + dz), stone);
                }
            }
        }

        /**
         * The vine-curtain landing — see {@link #vineupVariant} for the flagship provenance. One
         * DiagonalAscend hop; the landing feet cell is the bottom of a 3-vine curtain over solid stone.
         */
        void paintVineup() {
            BlockState air = Blocks.AIR.defaultBlockState();
            BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
            BlockState stone = Blocks.STONE.defaultBlockState();
            for (int dx = -4; dx <= 7; dx++) {
                for (int dy = -4; dy <= 11; dy++) {
                    for (int dz = -6; dz <= 6; dz++) {
                        level.setBlockAndUpdate(new BlockPos(BASE_X + dx, Y0 + dy, BASE_Z + dz), air);
                    }
                }
            }
            // 3x3 grass spawn pad (feet Y0+1). The route is the flagship's own (wp5..wp7 read out of
            // the attempt-3 log): Traverse +z along the pad → FLAT Diagonal (−x,+z) onto the wedge
            // takeoff C, WALKING THROUGH the vined corner at from-level (climbable=true flickers
            // mid-crossing in the flagship trace) → DiagonalAscend (+x,+z) up to the vined landing D.
            // The flat-diagonal lead-in is what arms the race (cuts 1–4 all PASSED without it): the
            // launch carries −x cruise AGAINST the wedge step's +x, the arc lands x-SHORT, grounding
            // at exactly landFootY on D's LIP one tick before the foot cell flips.
            for (int dx = 1; dx <= 3; dx++) {
                for (int dz = -3; dz <= -1; dz++) {
                    level.setBlockAndUpdate(new BlockPos(BASE_X + dx, Y0, BASE_Z + dz), grass);
                }
            }
            // Wedge takeoff C (flagship (206,117,296)) — same LEVEL as the pad; the lead-in is flat.
            level.setBlockAndUpdate(new BlockPos(BASE_X, Y0, BASE_Z), stone);
            // Vined landing D (flagship (207,117,297) floor, vines 118..120), one up from C.
            level.setBlockAndUpdate(new BlockPos(BASE_X + 1, Y0 + 1, BASE_Z + 1), stone);
            for (int dy = 2; dy <= 4; dy++) {   // host wall first (support check) — the trunk side
                level.setBlockAndUpdate(new BlockPos(BASE_X + 2, Y0 + dy, BASE_Z + 1), stone);
            }
            for (int dy = 2; dy <= 4; dy++) {
                level.setBlockAndUpdate(new BlockPos(BASE_X + 1, Y0 + dy, BASE_Z + 1),
                        Blocks.VINE.defaultBlockState().setValue(VineBlock.EAST, Boolean.TRUE));
            }
            // vineupgreen: cut 4's corner rung restored — the cardinal-ladder route exists (Traverse
            // onto the rung, cardinal Ascend into the vined landing via its discriminator).
            if (vineupGreenVariant()) {
                level.setBlockAndUpdate(new BlockPos(BASE_X + 1, Y0, BASE_Z), grass);
            }
            // (plain vineup) The TO-x corner (BASE_X+1,·,BASE_Z) is BARE VOID — a deliberate deviation from the
            // flagship's vined-and-supported corner (207,116..120,296), earned across cuts 4–8: any
            // occupancy there hands this pathfinder a route that skips the wedge hop (grass alone →
            // the cardinal Ascend ladder, cut 4; a full vine column → the Traverse-in/Climb-up
            // thread, cut 6; a lone host block → the shelf walk, cut 7; even ONE vine → a Climb
            // exit-top edge, cut 8). The flagship terrain blocked all of those with context this
            // card cannot afford to reproduce. Cost of the deviation: a z-short wedge arc falls
            // through the empty corner — a distinguishable "FAIL fell off the span", never a false
            // pass. Flat diagonals cross unsupported corners fine (pathdiag's non-wedge hops).
            // NO from-x corner support (deviation from the flagship's (206,116,297), deliberate):
            // with the exit strip in place it is the launch pad of an EXACT-COST-TIE bypass
            // (Traverse +z onto it, DiagonalAscend straight to the strip, skipping D), leaving the
            // wedge hop untested on a tie-break. Its only role was catching z-short arcs — those now
            // show as a distinguishable "FAIL fell off the span", not a false pass.
            // EXIT STRIP past D (+z), goal at its end: cuts 6–7 proved a goal ON D lets comeTo's
            // 0.75 arrival radius forgive any race that recovers onto the lip — the flagship's
            // wedge was an INTERMEDIATE waypoint with the plan wanting to continue, so the vine-top
            // bob (never grounded, cursor never advancing) parked it forever. Same shape here.
            level.setBlockAndUpdate(new BlockPos(BASE_X + 1, Y0 + 1, BASE_Z + 2), stone);
            level.setBlockAndUpdate(new BlockPos(BASE_X + 1, Y0 + 1, BASE_Z + 3), stone);
            // Pad-column cap at Y0+4: with the corner void, a rising gap-1 Parkour from the pad's
            // (BASE_X+1,·,BASE_Z−1) cell would clear the corner straight onto D — the cap refuses
            // its takeoff headroom (pathup's pad-cap idiom). C's column and everything the wedge
            // arc flies through stay uncapped.
            for (int dx = 1; dx <= 3; dx++) {
                for (int dz = -3; dz <= -1; dz++) {
                    level.setBlockAndUpdate(new BlockPos(BASE_X + dx, Y0 + 4, BASE_Z + dz), stone);
                }
            }
        }

        /** Start ledge -> SPAN vines on a 4-tall north wall -> end ledge. Everything else is void. */
        void paint() {
            if (vineupFamily()) {
                paintVineup();
                return;
            }
            if (pathdownVariant()) {
                paintPathdown();
                return;
            }
            if (pathupVariant()) {
                paintPathup();
                return;
            }
            if (pathdiagVariant()) {
                paintPathdiag();
                return;
            }
            if (grabVariant()) {
                paintGrab();
                return;
            }
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

            // Over the span: record the sub-cell dip that the cell-band envelope cannot see. (The grab
            // variant has no span; its whole verdict is the goal-ledge stance below.)
            final int bx = (int) Math.floor(bot.getX());
            if (!grabVariant() && bx >= BASE_X + 2 && bx < BASE_X + 2 + SPAN) {
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
            if (vineupFamily()) {
                // Standing at the strip's far end, PAST the vined landing — a stance the vine-top
                // bob (never grounded, cursor parked on D) can never reach. Radius-matched to
                // comeTo's own 0.75 arrival tolerance (cut 6's foot-cell-exact oracle turned a
                // legitimate lip arrival into a false red).
                if (d < 0.75 && bot.blockPosition().getY() == Y0 + 2 && bot.grounded()) {
                    finish("PASS crossed the vine-curtain landing to the strip end");
                    return;
                }
            } else if (pathdiagVariant() || pathdownVariant() || pathupVariant()) {
                if (bot.blockPosition().getX() <= BASE_X - (PATH_LEN - 1) && bot.grounded()) {
                    finish("PASS standing on the path's far end past the corner straddle");
                    return;
                }
            } else if (grabVariant()) {
                if (bot.blockPosition().getZ() >= BASE_Z + 8 && bot.grounded()) {
                    finish("PASS standing on the goal ledge past the vine grab");
                    return;
                }
            } else if (bot.blockPosition().getX() >= BASE_X + SPAN + 2 && bot.grounded()) {
                finish("PASS standing on the far ledge");
                return;
            }
            // The descending path legitimately walks below Y0 — its fell-floor tracks the path bottom.
            if (bot.getY() < (pathdownVariant() ? Y0 - PATH_LEN - 3 : Y0 - 3)) {
                finish("FAIL fell off the span");
                return;
            }
            if (ticks >= BUDGET_TICKS) {
                if (vineupVariant()) {
                    // Post-arc-rule, plain vineup's geometry has NO route (every composable cell is
                    // deliberately fenced), so the CORRECT outcome is a clean refusal: the bot never
                    // commits to the arc and stays parked on/near the pad. Distinguish that from the
                    // defect this card exists to pin — the never-grounded vine-top hover over D.
                    if (bot.getY() > Y0 + 3.5) {
                        finish("FAIL vine-top hover livelock (the arc-rule defect: held jump riding the curtain)");
                        return;
                    }
                    if (bot.getX() >= BASE_X + 0.5 && bot.getZ() <= BASE_Z - 0.5
                            && Math.abs(bot.getY() - (Y0 + 1)) < 0.5) {
                        finish("PASS-REFUSED parked at the pad (no route to the vined arc — the post-arc-rule expectation)");
                        return;
                    }
                }
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
