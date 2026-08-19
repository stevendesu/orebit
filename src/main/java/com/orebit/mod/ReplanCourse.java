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
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPathfinder;
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
 * Headless SEAM-ANCHORED-REPLAN diagnostic harness (a sibling of {@link TrapdoorCourse} / {@link GateCourse},
 * armed by its own {@code -Dorebit.replan} flag) — the live follower-tier proof of DESIGN-replan-handoff.md
 * §9.2. Each tile is an isolated boxed-corridor station where the bot runs a multi-move Traverse leg and the
 * COURSE forces a mid-motion re-search by mutating the corridor (sealing the committed route, which fires the
 * §4 table's S5 plan-impacted launch site — on the far-seal tiles (detour/reversal) via the debounced
 * terrain recheck's {@code refreshWindow(impacted)}, on the near-seal tiles via the UNIFIED ratified design's
 * U1 same-tick prompt trigger (owner 2026-08-18) — riding the same seam-seeded submit path as the wedge's S2
 * forward-slide). The trials then assert the §5 adoption contract from the OUTSIDE: results park until the
 * seam, install only anchored on the walked plan, and the reversing first step executes cleanly.
 *
 * <p><b>Why the trigger is a route change toward an unchanged goal, not a {@code comeTo} retarget.</b> §9.2
 * says "scripted target change"; a GOAL-level change deliberately takes the S1 fresh-plan path, which the §4
 * per-site table leaves UNSEEDED (the 2026-07-30 planAnchor launch gate stays) — it cannot exercise the seam
 * machinery at all. The seeded sites all re-search toward the CURRENT window target from an incumbent plan,
 * so the harness changes the ROUTE (the world) instead: the committed corridor is sealed while an alternate
 * stays open, and the re-search's answer reverses/diverts the bot mid-leg — the same shape as the convicted
 * wedge (§1), forced deterministically.
 *
 * <p><b>The tiles</b> (corridors run +X, 1-wide, 2-tall, stone-boxed at {@link #Y0}; bases are aligned one
 * block inside a 16-block level-0 region and {@link #STRIDE} is a multiple of 16, so every tile sees the SAME
 * region-boundary layout and the incidental forward-slide re-searches — themselves seam-seeded S2 launches —
 * happen at known places):
 * <ol>
 *   <li><b>control</b> — a plain 11-move corridor entirely inside one level-0 region, no edit: the bot must
 *       reach the goal with ZERO plan swaps (pins that the seam machinery is silent when nothing changes;
 *       any churn here is a regression by itself).</li>
 *   <li><b>detour</b> — §9.2(a)+(b), the pure-ADOPT case. A straight corridor to the goal with a side bypass
 *       (north at J, east along a parallel row, rejoining east of the seal cell). Mid-leg the course seals
 *       the straight route AHEAD of both the bot and every reachable seam. The seeded result's plan diverges
 *       only AT its seam (it heads on east, then north at J), so no pre-seam settle lies on it: the pump
 *       KEEPs at every boundary before the seam — assertion (a), the result parks while the bot keeps
 *       walking the retained prefix — and installs exactly once, cursor 0, at a settled floor that is NOT on
 *       the new plan's body (the implicit start — the seam): assertion (b)'s adoption-AT-the-seam arm.
 *       The trial requires the adoption floor to sit ≥3 cells AHEAD of where the bot stood when the seal was
 *       placed (deferred, seam-anchored — never an install-at-edit), the bypass row to be walked, and the
 *       goal reached with no wedge.</li>
 *   <li><b>reversal</b> — §9.2(b)+(c), the wedge's own shape (§9.4). A corridor whose only alternative is a
 *       U-turn: back west to a link BEHIND the bot's progress, along a parallel return row, rejoining east.
 *       Sealing mid-corridor makes the seeded result REVERSE travel. Because a reversing plan's body
 *       necessarily contains the pre-seam settled cells, the §5 on-plan arm may legitimately adopt one
 *       boundary early (FAST-FORWARD, the reached-scan entering mid-plan) or at the seam itself (ADOPT) —
 *       which of the two fires depends only on where within the executing move the recheck timer lands, so
 *       the trial asserts the INVARIANT rather than the class: every install is anchored on the walked or
 *       the new plan (never an unrelated cell), exactly one install is the reroute, the bot then actually
 *       reverses (reaches the west link), walks the return row, and arrives with no {@code step FAILED}
 *       wedge. The class that fired is recorded in the result line for the log reader.</li>
 *   <li><b>prefixseal</b> — the unified ratified design's U1+U2 proof (owner 2026-08-18; supersedes the
 *       first §10 shape, which expected a drop here — the sync course failure that killed that shape was
 *       exactly the DEBOUNCED trigger letting the cursor advance onto the sealed step and the seam walk
 *       then seeding the re-search FROM the sealed cell). REVERSAL's exact corridor + U-link, but the
 *       seal lands on the NEXT step: armed off the live segment while the bot is mid-move INTO +11
 *       (x ≥ +10.7; the trigger tick — max approach 0.28 b/t — ends at x ≤ +10.98, strictly inside that
 *       move AND strictly short of +11's first touch, so the U1 prompt's next steer tick provably runs
 *       with the cursor still on the +11 move — the old drop-race caveat is gone by construction), it
 *       seals +12 — cursor+1, inside the unclamped async §3 horizon (Traverse FLAT_COST 4.633 &lt;
 *       budgetTicks 5 ⇒ the walk would span it, i.e. the U2 clamp is genuinely exercised). Expected
 *       shape — the SAME in BOTH pathing modes (the sync blind spot is exactly what U1+U2 close): NO
 *       drop at all. The U1 prompt trigger fires the S5 plan-impacted replan the SAME tick (debounce
 *       bypassed for a move-invalidating edit inside the remaining envelope — U4's floor/body wall arm);
 *       the U2 edit-aware seam walk clamps to the last safe waypoint — the current move's destination
 *       +11, sync's k = cursor degenerating to the same cell — and adoption is a clean seam-anchored
 *       install there: class ADOPT, cursor 0, zero installs between seal and reroute, zero drops,
 *       anchored on the bot's LIVE floor (the install-tick speed is recorded as an observable, NOT
 *       bounded: first-touch hot entry at the seam is the §5 contract, owned by §6's input zeroing +
 *       the lip-margin machinery). If the async search outlives the walk to the seam, the U2-extended
 *       CAUTION hold (next step move-invalidated + seeded search pending) parks the bot AT the seam —
 *       an intrinsic, ACCEPTED pause (U3), never failed as a wedge. Asserted: the bot NEVER enters the
 *       sealed cell, the reroute (U-link + return row) is walked, goal reached.</li>
 *   <li><b>currentseal</b> — U5, the one emergency, mode-agnostic. Same corridor; the seal lands in the
 *       CURRENT step's destination (+12), armed in the last half-block before its boundary (x ≥ +11.5;
 *       the ≤0.28 b/t approach means the trigger tick still ends short of the boundary, so the wall can
 *       at worst graze the leading bbox edge). No safe waypoint exists — the CURRENT move's own cells
 *       are invalidated — so the U1 prompt cuts inputs NOW and drops the plan (the dropBlockPlan idiom;
 *       any pending seeded search + parked result die with it): asserted as exactly one drop with ZERO
 *       at-rest ticks between seal and drop (a debounced/lazy detection leaves the bot pressing the
 *       wall at rest first and is convicted), zero installs between seal and drop, the bot never
 *       occupies +12 (the wall is its own brake), it rests in +11, and the rest-gated planless pickup
 *       (relaunch speed &lt; eps, step 0 adjacent to the rest floor — U5 and PANIC are the ONLY atRest
 *       consumers) reroutes through the U-link to the goal.</li>
 *   <li><b>currentseal-on-ice</b> — U5 under real carry, on BLUE ICE. The straight-tile currentseal
 *       shape physically CANNOT produce the ratified &gt;1-block slide: the sealed destination is ≤0.5
 *       blocks ahead, so the seal itself brakes the coast at ~0.2 blocks. The slide is instead forced —
 *       deterministically, still honoring the ratified trigger (seal ∩ CURRENT destination, mid-move) —
 *       at a TURN: the tile is a T-junction whose approach floor is blue ice; the fresh plan turns
 *       north at the T-link (+8), and the course seals that link cell the tick it becomes the current
 *       destination, while the bot still carries its full +X ice cruise (~0.21 b/t; ice acceleration is
 *       far too weak to redirect it in one tick). The U5 null then leaves the bot sliding east onto a
 *       dead-end ice runway the plan never used (decay ~×0.90/t on blue ice ⇒ ~1.9 blocks) — the
 *       genuinely free slide the KEPT rest gate ({@code BotSteering.atRest}, retained by U6) must
 *       outwait. Asserted: the one prompt drop, post-null travel &gt; 1 block ending EAST of the sealed
 *       turn on row A, NO install while sliding (relaunch sampled speed &lt; eps, step 0 adjacent to
 *       the rest floor), the backtracking L-link route — the only route left — walked to the goal.</li>
 * </ol>
 *
 * <p><b>The near-seal tiles' observables</b> (prefixseal / currentseal / currentseal-on-ice; the
 * 2026-08-18 UNIFIED ratified design — U1 same-tick prompt trigger on the edit-epoch advance, U2
 * edit-aware seam clamp + the extended seam CAUTION hold, U3 adoption unchanged, U4 move-compatibility
 * as the breakage predicate, U5 the one emergency, U6 retirement of the first §10 shape's
 * side-mechanism — superseding the first-shape wording the mainline's earlier field docs may still
 * carry). These tiles mutate the NEAR route (the current step or cursor+1). On the EMERGENCY tiles
 * (currentseal / currentseal-on-ice) the ratified behavior is drop-and-relaunch-from-rest: the course
 * records the drop tick + floor, counts at-rest ticks between seal and drop (promptness — a state
 * test, not a timer: any rest tick before the drop means the bot was left pressing a dead route),
 * counts installs between seal and drop (the pending seeded search + parked result must die with the
 * plan), accumulates post-drop travel until the relaunch (the ice slide), and samples the relaunch's
 * speed as the PREVIOUS tick's position delta — the carry the kept rest gate measured at launch (the
 * install tick's own delta already contains the new plan's first thrust). On PREFIXSEAL the ratified
 * behavior is same-tick seeded replan + clamped-seam adoption with NO drop: the course counts installs
 * between the seal and the reroute (must stay 0 — U1 fires once and re-baselines the debounce),
 * classifies the reroute install (must be ADOPT at the clamped seam), captures the bot's LIVE floor at
 * that install (must equal the settled anchor — an install anchored off the bot's actual cell is the
 * §1 hole), and records the install-tick speed sample WITHOUT bounding it (hot first-touch seam entry
 * is the §5 contract; only the emergency relaunches must be at rest, because U5 and PANIC are the ONLY
 * atRest consumers). {@link #REST_EPS} (0.04 b/t) sits just above the mainline gate
 * ({@code BotSteering.REST_HSPEED} = 0.02, a velocity test; position deltas lag it by one drag tick,
 * ×~0.9 on blue ice) and far below both walk cruise (~0.216) and any slide worth convicting (a 0.05
 * carry still coasts 0.45 blocks on blue ice — the §1 hazard). The trace marks the relaunch with a
 * {@code REINSTALL} line beside the existing SEAL/SWAP/CLASS/DROP markers.
 *
 * <p><b>What "no step FAILED" means here.</b> The fail→hold policy freezes a failed step in place, so a
 * validity-envelope failure is observable as a wedge: the course fails any trial whose bot stops making
 * progress for {@link #NO_PROGRESS_LIMIT} consecutive ticks (generously past every legitimate hold — the
 * planless WAIT while a fresh async search runs is a few ticks; no trial contains a committing step for the
 * §7 seam CAUTION, and the U2-EXTENDED seam hold prefixseal can legally take — next step move-invalidated,
 * seeded search still pending — spans only the search's in-flight ticks). PASS therefore certifies the
 * reversing/diverting step 0 executed cleanly.
 *
 * <p><b>Sync AND async are both first-class.</b> The course adapts to the loaded {@code pathing.async}: in
 * sync mode the §3 walk degenerates to the current move's destination and the park spans the remainder of
 * that move; in async (default) the seam sits ~{@code budgetTicks} of step-cost ahead and the park spans
 * whole boundaries. The assertions are mode-agnostic (the detour tile pins ADOPT in both modes; the reversal
 * tile records ADOPT vs FAST-FORWARD; prefixseal pins the SAME clamped-seam ADOPT shape in both modes — the
 * sync blind spot U1+U2 close — and the emergency tiles' drop shape is mode-agnostic by U5). Full §9.2
 * coverage = one run with {@code pathing.async=false} and one with the default {@code true} in the run
 * dir's {@code orebit.properties}; the active mode is stamped into the result file.
 *
 * <p><b>§9.2(d) — the PANIC case — is deliberately NOT in this course.</b> It cannot be forced
 * deterministically from a harness, for three reasons established against the shipped mechanism:
 * <ol>
 *   <li>The adoption pump runs at every boundary-gated tick and tests the seam FIRST, so a result that is
 *       parked when the bot settles at the seam always ADOPTs (case 2 precedes the past-seam cases). Reaching
 *       case 4 requires the seeded search to still be UNFINISHED when the bot settles past the seam.</li>
 *   <li>The §3 walk places the seam at least {@code budgetTicks} of per-step cost ahead of the cursor, and
 *       the search is hard-capped at that same budget — costs are real ticks (the physically-derived-costs
 *       invariant), so a within-budget search always lands before the bot does. The only overruns are
 *       scheduler slop and the 256-pop budget-check granularity: §5's "corner of a corner", which no script
 *       can summon on demand.</li>
 *   <li>Both levers a harness could pull fail: <i>delaying the result</i> (saturating the PlanExecutor queue
 *       with synthetic requests) turns the trial into a wall-clock race — the late drain must land within
 *       the walk-outrun Chebyshev tolerance past the seam (3 cells at the default budget), and outside that
 *       box the verdict is KEEP forever, wedging the bot into the sealed corridor; <i>denying the settle</i>
 *       is impossible without external force on the bot (teleport/knockback), which trips the fail→hold
 *       validity envelope first and destroys the very no-step-FAILED oracle the trial exists to check.</li>
 * </ol>
 * PANIC is instead covered by the §9.1 unit-level four-case adoption table test and observed live via the
 * §9.3/§9.4 flagship + wedge re-runs (a {@code seam-adopt PANIC} log line under {@code -Dorebit.replan.debug}
 * -style debug). The course still FAILS if a panic fires where none should: an unexpected mid-course plan
 * DROP is asserted zero in every non-emergency trial (control/detour/reversal, and now PREFIXSEAL — a drop
 * there is the retired first-§10-shape machinery resurfacing, U6); only the two U5 emergency tiles expect
 * EXACTLY the one ratified drop and assert its shape — a second drop fails them just the same.
 *
 * <p><b>Config (scripts/replan/orebit.properties).</b> Defaults are acceptable; recommended for honest
 * corridors: {@code mining.canMine=false} + {@code placement.canPlace=false} (the detours are already cheaper
 * than digging the seal, but this removes even that freedom), {@code doors.toggle} irrelevant. Run once with
 * {@code pathing.async=false} (fully deterministic single-thread schedule) and once with the default
 * {@code true} (the async park/drain pump; the only nondeterminism is WHICH gate tick drains a
 * microsecond-scale search — every assertion is robust to it). {@code -Dorebit.replan.debug} flips
 * {@link Debug#ENABLED}/{@link Debug#VERBOSE} for the {@code seam-seed}/{@code seam-park}/{@code seam-adopt}
 * and {@code advance SKIPPED} log lines that pair with this course's trace.
 *
 * <p><b>Inert in production</b> — {@link #register} returns immediately unless {@code -Dorebit.replan} is
 * set. Common, version-portable source (plain stone corridors, plus the ice tile's
 * {@code Blocks.BLUE_ICE} — a single constant, verified present under that name in the 1.17.1 Mojang
 * mappings and the 26.x unobfuscated jar, so it is version-STABLE across the whole range).
 */
public final class ReplanCourse {

    private ReplanCourse() {}

    private static final String RESULT_FILE = "orebit-replan-result.properties";
    private static final String TRACE_FILE = "orebit-replan-trace.txt";
    /** TEMP DIAGNOSTIC (owner 2026-08-18): region-tier trace armed for the REVERSAL trial only, to audit the
     *  false boxed-in seal proof (goal (base+24) proven "sealed" while the U-passage connected it to the bot).
     *  Every sealed-probe settle/candidate plus the SEAL-PROBE/SEAL-VERDICT lines land here. */
    private static final String RTRACE_FILE = "orebit-replan-rtrace.txt";

    /** Corridor-floor Y (feet stand at {@code Y0+1}); floating high so a fall off a tile is unambiguous. */
    private static final int Y0 = 150;
    /** Tile grid base — deliberately ONE block inside a 16-block level-0 region on both axes (17 mod 16 = 1),
     *  and {@link #STRIDE} is a multiple of 16, so every tile shares the same region-boundary layout: the
     *  incidental forward-slide re-search (itself a seam-seeded S2 launch) fires at a known corridor x. */
    private static final int BASE_X = 17;
    private static final int BASE_Z = 17;
    private static final int COLS = 2;
    private static final int STRIDE = 48; // grid cell size (> the longest tile span; 3×16 keeps alignment)

    /** Ticks to let the whole starting area gen + nav-build before the first goto. */
    private static final int WARMUP_TICKS = 160;
    /** Ticks after each teleport before the goto (the just-painted tile's nav patches settle). */
    private static final int SETTLE_TICKS = 60;
    private static final int NAV_RETRY_WINDOW = 60;
    private static final int MAX_NAV_RETRY = 5;
    /** Per-trial attempt budget (ticks). The longest trial (reversal) is ~60 moves ≈ 280 driven ticks. */
    private static final int ATTEMPT_BUDGET = 900;
    /** Consecutive no-horizontal-progress ticks that convict a wedge (fail→hold shows as a frozen bot; every
     *  legitimate still — fresh-plan WAIT, a parked-result boundary — is over an order of magnitude shorter). */
    private static final int NO_PROGRESS_LIMIT = 100;

    /** Course-side at-rest bound (blocks/tick, position-delta proxy) for the U5 emergency tiles' relaunch
     *  asserts (prefixseal's seam install is deliberately NOT rest-bounded — §5 hot first-touch entry).
     *  The mainline gate ({@code BotSteering.REST_HSPEED}) reads VELOCITY &lt; 0.02; the course samples
     *  per-tick position deltas, which lag the gated velocity by one drag tick (×~0.9 on blue ice), so
     *  the assert bound sits just above the gate at 0.04 — far below walk cruise (~0.216 b/t) and below
     *  any slide worth convicting (a 0.05 carry still coasts 0.45 blocks on blue ice, the §1 hazard). */
    private static final double REST_EPS = 0.04;
    /** Minimum post-null travel that counts as a real slide (the currentseal-on-ice tile's proof; the
     *  expected blue-ice slide from walk cruise is ~1.9 blocks, a braked stone coast ~0.2). */
    private static final double SLIDE_MIN = 1.0;

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    /** The ice tile's approach/runway floor (version-stable constant, 1.17→26.x — see class Javadoc). */
    private static final BlockState BLUE_ICE = Blocks.BLUE_ICE.defaultBlockState();

    public static void register(PlatformEvents events) {
        if (System.getProperty("orebit.replan") == null) {
            return;
        }
        Course course = new Course();
        events.onServerStarted(course::start);
        events.onWorldTickEnd(course::tick);
        OrebitCommon.LOGGER.info("[Orebit/replan] armed: {} trials", course.trials.size());
    }

    private enum Kind {
        CONTROL,        // plain corridor, no edit -> zero swaps, just arrive
        DETOUR,         // seal ahead of every reachable seam; bypass diverges AT the seam -> pure ADOPT (§5 case 2)
        REVERSAL,       // seal mid-corridor; only route is a U-turn -> reversing install (§5 case 2 or 3), the wedge shape
        PREFIXSEAL,     // U1+U2: seal cursor+1 mid-move -> same-tick prompt replan, seam CLAMPED to the current destination, ADOPT there — NO drop, both modes
        CURRENTSEAL,    // U5: seal the CURRENT destination mid-move -> inputs cut + plan dropped NOW, the wall brakes the coast, rest-gated relaunch
        CURRENTSEAL_ICE // U5 under carry: same trigger at a blue-ice turn -> the null leaves a real slide the kept rest gate must outwait
    }

    /** One replan challenge: a kind + its base grid cell, with corridor/seal/bypass geometry precomputed. */
    private static final class Trial {
        final String name;
        final Kind kind;
        final int baseX, baseZ;
        final int zc;               // centre-line Z of the main (A) corridor row

        double startX, startY, startZ;
        float startYaw;
        BlockPos goal;
        int minFloorY;              // a fall this far below the tile = off the course

        // Edit-tile geometry (unused for CONTROL).
        int sealX;                  // the corridor column the course seals mid-leg
        int sealZ;                  // the sealed cell's z (zc for the corridor tiles; the ICE tile seals its LINK cell at zc+1)
        double editX;               // bot-x threshold that arms the seal (state-based, no timer; near-seal tiles also gate on the live segment)
        int bypassZ;                // the parallel return/bypass row's Z
        int bypassX0, bypassX1;     // inclusive x-span of the bypass row
        BlockPos bypassProbe;       // a FLOOR cell only a rerouted plan can contain (reroute-swap detector)
        int westLinkX;              // REVERSAL: the U-turn link column the bot must reach back to

        Trial(String name, Kind kind, int baseX, int baseZ) {
            this.name = name;
            this.kind = kind;
            this.baseX = baseX;
            this.baseZ = baseZ;
            this.zc = baseZ + 6;
            this.minFloorY = Y0 - 6;
            this.startX = baseX + 0.5; // start INSIDE the corridor mouth (same level-0 region as the leg)
            this.startY = Y0 + 1;
            this.startZ = zc + 0.5;
            this.startYaw = yaw(1, 0); // face +X down the corridor
            this.sealZ = zc;           // corridor-row seal by default (the ICE tile overrides: its LINK cell)
            switch (kind) {
                case CONTROL: {
                    this.goal = new BlockPos(baseX + 11, Y0 + 1, zc);
                    break;
                }
                case DETOUR: {
                    // A: baseX..+28; bypass: north at J=+18, east row zc+2 to R=+26, south rejoin; seal +22.
                    // Straight = 28 moves, bypass = 32 -> the fresh plan commits to the straight leg. The
                    // recheck timer (40t) fires with the bot ~6..12 moves in, so every reachable seam
                    // (cursor + horizon, commit-free walk) lies WEST of J — the new plan diverges at its
                    // seam heading east, and no pre-seam settle can lie on its body: pure §5-case-2 ADOPT.
                    this.goal = new BlockPos(baseX + 28, Y0 + 1, zc);
                    this.sealX = baseX + 22;
                    this.editX = baseX + 3.5;
                    this.bypassZ = zc + 2;
                    this.bypassX0 = baseX + 18;
                    this.bypassX1 = baseX + 26;
                    this.bypassProbe = new BlockPos(baseX + 18, Y0, zc + 2);
                    break;
                }
                case PREFIXSEAL:
                case CURRENTSEAL: {
                    // REVERSAL's exact corridor + U-link (below) — only the seal cell and its arming
                    // threshold differ. The seal sits at +12 (region-interior; the incidental forward-
                    // slide x is +15, which the bot never reaches — PREFIXSEAL turns around at +11,
                    // CURRENTSEAL stops against the wall) and arms LATE off the live segment:
                    // PREFIXSEAL mid-move INTO +11 (x ≥ +10.7, so the trigger tick — max approach
                    // 0.28 b/t — ends at x ≤ +10.98, strictly inside that move AND short of +11's
                    // first touch, so the U1 prompt's next steer tick provably runs with the cursor
                    // still on the +11 move; the seal lands on the NEXT step, cursor+1 — inside the
                    // unclamped async §3 horizon since Traverse FLAT_COST 4.633 < budgetTicks 5, so
                    // the U2 clamp to the current destination +11 is genuinely exercised);
                    // CURRENTSEAL in the last half-block before +12's boundary (x ≥ +11.5, trigger
                    // tick ends at x ≤ +11.78 — the seal lands in the CURRENT step's destination with
                    // the bot short of first touch: U5's no-safe-waypoint emergency).
                    this.goal = new BlockPos(baseX + 24, Y0 + 1, zc);
                    this.sealX = baseX + 12;
                    this.editX = kind == Kind.PREFIXSEAL ? baseX + 10.7 : baseX + 11.5;
                    this.bypassZ = zc + 2;
                    this.bypassX0 = baseX + 1;
                    this.bypassX1 = baseX + 22;
                    this.bypassProbe = new BlockPos(baseX + 1, Y0, zc + 2);
                    this.westLinkX = baseX + 1;
                    break;
                }
                case CURRENTSEAL_ICE: {
                    // T-junction on blue ice. Row A runs -2..+12 (approach + a dead-end slide runway
                    // +9..+12); the goal row B (zc+2) runs -2..+16 with the goal at +16. Two links:
                    // T at +8 (the fresh plan's turn — 18 moves) and L at -2, which BACKTRACKS west
                    // past the start (22 moves; every monotone east/north alternative ties exactly, so
                    // only a backtracking link prices strictly longer — the deterministic tie-break).
                    // The course seals the T-LINK cell (+8, zc+1) the tick it becomes the current
                    // destination; the bot's +X ice cruise then slides it past onto the runway.
                    this.goal = new BlockPos(baseX + 16, Y0 + 1, zc + 2);
                    this.sealX = baseX + 8;
                    this.sealZ = zc + 1;
                    this.editX = 0; // unused — the trigger is the live SEGMENT, not an x threshold
                    this.bypassZ = zc + 2;
                    this.bypassX0 = baseX - 2;
                    this.bypassX1 = baseX + 6;  // row B's reroute-only stretch (the fresh plan enters B at +8)
                    this.bypassProbe = new BlockPos(baseX - 2, Y0, zc + 1);
                    this.westLinkX = baseX - 2;
                    break;
                }
                default: { // REVERSAL
                    // A: baseX..+24, goal +24; U-link WEST at +1 (north to row zc+2), return row east to
                    // +22, south rejoin; seal +16. Straight = 24 moves, U = ~29 -> fresh plan goes straight.
                    // After the seal the ONLY route from any reachable seam is back west through the cells
                    // the bot just walked — the reversing plan whose body contains the pre-seam settles.
                    this.goal = new BlockPos(baseX + 24, Y0 + 1, zc);
                    this.sealX = baseX + 16;
                    this.editX = baseX + 3.5;
                    this.bypassZ = zc + 2;
                    this.bypassX0 = baseX + 1;
                    this.bypassX1 = baseX + 22;
                    this.bypassProbe = new BlockPos(baseX + 1, Y0, zc + 2);
                    this.westLinkX = baseX + 1;
                    break;
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
        /** TEMP DIAGNOSTIC (owner 2026-08-18): the {@link #RTRACE_FILE} sink while the reversal trial has
         *  {@link RegionPathfinder#TRACE} armed; {@code null} outside it. */
        BufferedWriter rtrace;

        int index = -1;
        boolean settling;
        int settleTicks;
        int attemptTicks;
        int navRetries;
        boolean overallDone;
        double closest;
        int passed, failed;

        // Per-trial observation state (reset in enter()).
        BlockPathPlan prevPlanRef;      // follower plan identity last tick (swap/drop detector)
        boolean justRetried;            // a comeTo re-issue nulled the plan this tick — not a DROP
        boolean sealPlaced;
        int sealTick;
        BlockPos editFloor;             // the bot's settled floor when the seal landed ((a)'s "kept walking" datum)
        int swapCount;                  // installed plan->plan swaps observed after the goto
        int seamViolations;             // installs anchored on NEITHER the old nor the new plan (§1's hole)
        int planDrops;                  // unexpected plan->null mid-course (a PANIC would show here)
        int rerouteTick;                // first swap whose plan contains bypassProbe (the forced re-search's install)
        String rerouteClass;            // "ADOPT" (implicit-start entry) or "ONPLAN" (§5 case-3 body entry)
        BlockPos rerouteFloor;          // the settled floor that install was anchored on
        double preRerouteMaxX;          // farthest east before the reroute (must stay short of the seal)
        double postRerouteMinX;         // farthest back west after it (REVERSAL: must reach the U-link)
        boolean visitedBypass;
        int noProgressTicks;
        StringBuilder swapLog;          // compact per-swap summary for the result line
        // Pending swap: classified one tick AFTER detection — the reached-scan/phase handoff of the install
        // tick may finish a superseded phase first, so the cursor is only meaningful next tick.
        boolean pendSwap;
        int pendTick;
        BlockPos pendFloor;
        boolean pendOnNew, pendOnOld, pendReroute;
        double pendSpd;                 // the PREVIOUS tick's position delta at the install (the carry it rode in on)
        BlockPos pendLive;              // bot's LIVE floor cell at the install (vs pendFloor, the settled anchor)
        double prevX, prevZ;
        String prevMove = "";
        int prevSegToX = Integer.MIN_VALUE, prevSegToY, prevSegToZ;
        // Near-seal-tile observation state (prefixseal/currentseal/currentseal-on-ice; reset in enter()).
        // The drop/reinstall/rest/slide fields are the U5 EMERGENCY tiles' shape; preSeamInstalls and
        // rerouteSpd/rerouteLiveFloor are PREFIXSEAL's clamped-seam-adopt shape (no drop belongs there).
        int dropTick;                   // the ratified U5 plan->null (recorded only once seal is placed)
        BlockPos dropFloor;             // bot's live floor cell at the drop
        int restTicksBeforeDrop;        // post-seal ticks at rest BEFORE the drop (promptness: must stay 0)
        int swapsSealToDrop;            // plan->plan installs between seal and drop (the seeded search + parked result must die with the plan: must stay 0)
        int reinstallTick;              // first null->plan after the drop (the rest-gated relaunch)
        double reinstallSpd;            // PREVIOUS tick's position delta at the reinstall (must be < REST_EPS)
        BlockPos reinstallFloor;        // bot's live floor cell at the reinstall (the ratified "stop cell")
        BlockPos reinstallStep0;        // the fresh plan's step-0 floor (must be adjacent to reinstallFloor)
        boolean enteredSeal;            // bot's feet column ever matched the sealed cell (must stay false)
        double slideDistance;           // cumulative horizontal travel from the drop until the reinstall
        double lastMoved;               // previous tick's position delta (the transition block runs pre-sample)
        int preSeamInstalls;            // PREFIXSEAL: post-seal installs that are NOT the reroute, before it lands (must stay 0 — U1 fires once)
        double rerouteSpd;              // PREVIOUS tick's position delta at the reroute install (observable only — §5 hot seam entry is legal)
        BlockPos rerouteLiveFloor;      // bot's LIVE floor cell at the reroute install (must equal its settled anchor)

        Course() {
            add("control",            Kind.CONTROL);
            add("detour",             Kind.DETOUR);
            add("reversal",           Kind.REVERSAL);
            add("prefixseal",         Kind.PREFIXSEAL);
            add("currentseal",        Kind.CURRENTSEAL);
            add("currentseal-on-ice", Kind.CURRENTSEAL_ICE);
        }

        /** The tiles that seal the NEAR route (the current step or cursor+1) — the unified-design tiles;
         *  all three carry the sealed-cell tripwire. */
        static boolean nearSealKind(Kind k) {
            return k == Kind.PREFIXSEAL || k == Kind.CURRENTSEAL || k == Kind.CURRENTSEAL_ICE;
        }

        /** The U5 emergency tiles — the ONLY tiles where a plan drop is the ratified shape (the CURRENT
         *  move's own cells are invalidated, so no safe waypoint exists to clamp a seam to). */
        static boolean emergencyKind(Kind k) {
            return k == Kind.CURRENTSEAL || k == Kind.CURRENTSEAL_ICE;
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
            if (Boolean.getBoolean("orebit.replan.debug")) {
                Debug.ENABLED = true;
                Debug.VERBOSE = true;
            }
            try {
                this.level = server.overworld();
                Trial first = trials.get(0);
                owner = new FakePlayerEntity(server, level, new GameProfile(
                        UUID.nameUUIDFromBytes("OrebitReplan:owner".getBytes(StandardCharsets.UTF_8)),
                        "Replan"));
                owner.setPos(first.startX, first.startY, first.startZ);
                BotManager.spawnBotFor(owner);
                bot = BotManager.botFor(owner);
                if (bot == null) {
                    finish("bot never spawned");
                    return;
                }
                trace = Files.newBufferedWriter(ConfigDir.serverDir(server).resolve(TRACE_FILE),
                        StandardCharsets.UTF_8);
                trace.write("Orebit replan course trace  (T <trial> <tick> x y z | spd | onGround | i=cursor/plan"
                        + " | driveState | move)\n");
                trace.write("legend: SEAL = the course sealed the committed corridor (the forced mid-motion"
                        + " re-search); SWAP = a plan->plan install (floor anchor + old/new-plan membership);"
                        + " CLASS = ADOPT (implicit-start entry, §5 case 2) vs ONPLAN (body entry, §5 case 3);"
                        + " DROP = plan->null mid-course (the ratified U5 emergency drop on the currentseal"
                        + " tiles; a failure anywhere else, prefixseal included); REINSTALL = the rest-gated"
                        + " null->plan relaunch after a U5 drop\n");
                trace.write("mode: pathing.async=" + ConfigLoader.config().asyncPathing() + "\n\n");
                OrebitCommon.LOGGER.info("[Orebit/replan] course ready; {} trials (pathing.async={})",
                        trials.size(), ConfigLoader.config().asyncPathing());
                enter(0);
            } catch (Throwable t) {
                OrebitCommon.LOGGER.error("[Orebit/replan] setup threw", t);
                finish("setup threw " + t.getClass().getSimpleName());
            }
        }

        void enter(int i) {
            index = i;
            Trial tr = trials.get(i);
            // TEMP-DIAGNOSTIC scope guard: the reversal-only region trace must not stay armed into the
            // near-seal tiles that now FOLLOW reversal (it used to be disarmed in finish(), which was enough
            // only while reversal was the last trial).
            if (rtrace != null && tr.kind != Kind.REVERSAL) {
                RegionPathfinder.TRACE = false;
                RegionPathfinder.TRACE_OUT = null;
                try { rtrace.close(); } catch (IOException ignored) { }
                rtrace = null;
            }
            // TEMP DIAGNOSTIC (owner 2026-08-18): arm the region-tier trace for the REVERSAL trial so the
            // boxed-in sealed-probe flood dumps every settle + candidate edge (E/C lines) plus the
            // SEAL-PROBE/SEAL-VERDICT summaries — auditing WHY the flood never reached the observer through
            // the U-passage. One-trial scope: armed here, disarmed in done() (the course halts right after).
            if (tr.kind == Kind.REVERSAL && rtrace == null) {
                try {
                    rtrace = Files.newBufferedWriter(ConfigDir.serverDir(server).resolve(RTRACE_FILE),
                            StandardCharsets.UTF_8);
                    RegionPathfinder.TRACE_OUT = rtrace;
                    RegionPathfinder.TRACE = true;
                    OrebitCommon.LOGGER.info("[Orebit/replan] region trace ARMED -> {}", RTRACE_FILE);
                } catch (IOException e) {
                    OrebitCommon.LOGGER.error("[Orebit/replan] could not open {}", RTRACE_FILE, e);
                }
            }
            buildTile(tr);
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
            prevPlanRef = null;
            justRetried = false;
            sealPlaced = false;
            sealTick = -1;
            editFloor = null;
            swapCount = 0;
            seamViolations = 0;
            planDrops = 0;
            rerouteTick = -1;
            rerouteClass = "-";
            rerouteFloor = null;
            preRerouteMaxX = -Double.MAX_VALUE;
            postRerouteMinX = Double.MAX_VALUE;
            visitedBypass = false;
            noProgressTicks = 0;
            swapLog = new StringBuilder();
            pendSwap = false;
            dropTick = -1;
            dropFloor = null;
            restTicksBeforeDrop = 0;
            swapsSealToDrop = 0;
            reinstallTick = -1;
            reinstallSpd = 0;
            reinstallFloor = null;
            reinstallStep0 = null;
            enteredSeal = false;
            slideDistance = 0;
            lastMoved = 0;
            preSeamInstalls = 0;
            rerouteSpd = 0;
            rerouteLiveFloor = null;
            prevX = tr.startX;
            prevZ = tr.startZ;
            prevMove = "";
            prevSegToX = Integer.MIN_VALUE;
            try {
                trace.write(String.format(Locale.ROOT,
                        "== %s : kind=%s start=(%.1f,%.1f,%.1f) goal=(%d,%d,%d) seal=x%s\n",
                        tr.name, tr.kind, tr.startX, tr.startY, tr.startZ,
                        tr.goal.getX(), tr.goal.getY(), tr.goal.getZ(),
                        tr.kind == Kind.CONTROL ? "-" : String.valueOf(tr.sealX)));
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
                bot.comeTo(tr.goal, 0.75, 0.75, 0); // exact: reach the precise cell (the GotoCommand form)
                return;
            }

            attemptTicks++;
            BotNavigator nav = bot.navigator();
            BlockPathPlan plan = nav.currentPlan();

            // Finalize the previous tick's pending swap (the cursor is only meaningful one tick after the
            // install — the superseded step's phase handoff may run first on the install tick itself).
            if (pendSwap) {
                classifySwap(tr, nav);
            }

            // Plan-identity transitions. A plan->plan change is an INSTALL (the §5 pump adopted a parked
            // result, or a fresh replan landed); a plan->null mid-COME is a DROP (PANIC's signature — §5
            // case 4 nulls the incumbent). The initial null->plan and the arrival clear are neither.
            if (plan != prevPlanRef) {
                if (prevPlanRef != null && plan != null) {
                    if (emergencyKind(tr.kind) && sealPlaced && dropTick < 0) {
                        swapsSealToDrop++; // U5: the seeded search + parked result die with the plan — nothing may install first
                    }
                    pendSwap = true;
                    pendTick = attemptTicks;
                    pendFloor = nav.settledFloor();
                    pendLive = botFloorCell();
                    pendSpd = lastMoved; // the PREVIOUS tick's delta — the carry the install rode in on
                    pendOnNew = pendFloor != null && planContainsFloor(plan, pendFloor);
                    pendOnOld = pendFloor != null && planContainsFloor(prevPlanRef, pendFloor);
                    pendReroute = tr.kind != Kind.CONTROL && planContainsFloor(plan, tr.bypassProbe);
                    if (tr.kind == Kind.PREFIXSEAL && sealPlaced && rerouteTick < 0 && !pendReroute) {
                        preSeamInstalls++; // an install between the seal and the clamped-seam reroute: U1 double-fired or something stale adopted
                    }
                    try {
                        trace.write(String.format(Locale.ROOT,
                                "  SWAP tick=%d floor=%s live=%s spd=%.4f onNew=%b onOld=%b reroute=%b newPlan=%dwp bot=(%.2f,%.2f,%.2f)\n",
                                attemptTicks, pendFloor == null ? "?" : pendFloor.toShortString(),
                                pendLive.toShortString(), pendSpd,
                                pendOnNew, pendOnOld, pendReroute, plan.size(),
                                bot.getX(), bot.getY(), bot.getZ()));
                    } catch (IOException ignored) { }
                } else if (prevPlanRef != null && plan == null
                        && bot.mode() == AllyBotEntity.Mode.COME && !justRetried) {
                    planDrops++;
                    if (emergencyKind(tr.kind) && sealPlaced && dropTick < 0) {
                        // The ratified U5 emergency drop (the CURRENT move's cells are invalidated:
                        // inputs cut + plan dropped the same steer tick) — expected exactly once here.
                        dropTick = attemptTicks;
                        dropFloor = botFloorCell();
                        try {
                            trace.write(String.format(Locale.ROOT,
                                    "  DROP tick=%d floor=%s spd=%.4f — the ratified U5 emergency drop\n",
                                    attemptTicks, dropFloor.toShortString(), lastMoved));
                        } catch (IOException ignored) { }
                    } else {
                        try {
                            trace.write(String.format(Locale.ROOT,
                                    "  DROP tick=%d bot=(%.2f,%.2f,%.2f) — plan nulled mid-course (PANIC?)\n",
                                    attemptTicks, bot.getX(), bot.getY(), bot.getZ()));
                        } catch (IOException ignored) { }
                    }
                } else if (prevPlanRef == null && plan != null
                        && emergencyKind(tr.kind) && dropTick > 0 && reinstallTick < 0) {
                    // The rest-gated relaunch: the first plan AFTER the U5 drop. Its speed sample is
                    // the PREVIOUS tick's position delta — the carry the kept rest gate (U6: the
                    // planless pickup's atRest) measured at launch (this tick's delta already contains
                    // the new plan's first thrust).
                    reinstallTick = attemptTicks;
                    reinstallSpd = lastMoved;
                    reinstallFloor = botFloorCell();
                    reinstallStep0 = plan.size() > 0 ? plan.floor(0) : null; // null -> the adjacency assert convicts
                    if (rerouteTick < 0) {
                        rerouteTick = attemptTicks;
                        rerouteClass = "FRESH";
                        rerouteFloor = reinstallFloor;
                    }
                    try {
                        trace.write(String.format(Locale.ROOT,
                                "  REINSTALL tick=%d floor=%s spd=%.4f step0=%s probe=%b newPlan=%dwp\n",
                                attemptTicks, reinstallFloor.toShortString(), reinstallSpd,
                                reinstallStep0 == null ? "?" : reinstallStep0.toShortString(),
                                planContainsFloor(plan, tr.bypassProbe), plan.size()));
                    } catch (IOException ignored) { }
                }
                prevPlanRef = plan;
            }
            justRetried = false;

            // The forced mid-motion re-search: seal the committed corridor once the bot is a few moves into
            // the leg (state-based trigger — no timers; the terrain-recheck debounce supplies the cadence).
            // DETOUR/REVERSAL seal AHEAD of the bot and of every reachable seam, so the incumbent's retained
            // prefix stays walkable — §4's S5-impacted contract. The near-seal tiles instead seal the NEAR
            // route (cursor+1 for PREFIXSEAL — the U2 clamp's proof; the CURRENT destination for the U5
            // emergency tiles), armed off the live segment (see sealTriggered).
            if (tr.kind != Kind.CONTROL && !sealPlaced && plan != null && sealTriggered(tr, nav)) {
                set(tr.sealX, Y0 + 1, tr.sealZ, STONE);
                set(tr.sealX, Y0 + 2, tr.sealZ, STONE);
                sealPlaced = true;
                sealTick = attemptTicks;
                editFloor = nav.settledFloor();
                try {
                    trace.write(String.format(Locale.ROOT,
                            "  SEAL tick=%d x=%d editFloor=%s bot=(%.2f,%.2f,%.2f)\n",
                            attemptTicks, tr.sealX, editFloor == null ? "?" : editFloor.toShortString(),
                            bot.getX(), bot.getY(), bot.getZ()));
                } catch (IOException ignored) { }
            }

            // Progress observation for the (a)/(b)/(c) geometry asserts.
            if (rerouteTick < 0) {
                if (bot.getX() > preRerouteMaxX) preRerouteMaxX = bot.getX();
            } else {
                if (bot.getX() < postRerouteMinX) postRerouteMinX = bot.getX();
            }
            if (tr.kind != Kind.CONTROL
                    && (int) Math.floor(bot.getZ()) == tr.bypassZ
                    && bot.getX() >= tr.bypassX0 && bot.getX() <= tr.bypassX1 + 1) {
                visitedBypass = true;
            }

            // Progress sample BEFORE trace() — trace() advances prevX/prevZ to the CURRENT position,
            // so sampling after it reads a permanent 0 and the wedge detector convicts every trial
            // that outlives NO_PROGRESS_LIMIT (the 2026-08-18 always-wedged harness bug).
            double moved = Math.abs(bot.getX() - prevX) + Math.abs(bot.getZ() - prevZ);
            // Near-seal-tile per-tick observation (all state tests — no timers): the U5 emergency tiles'
            // promptness (at-rest ticks between seal and drop) + post-drop slide odometer, and ALL three
            // tiles' sealed-cell tripwire (PREFIXSEAL must never enter the seal either — the superseded
            // first shape's convicting failure was exactly the cursor advancing onto the sealed step).
            // The drop/reinstall detections above ran BEFORE this sample, so the drop tick itself never
            // counts as a pre-drop rest tick and the install tick's own delta never joins the slide.
            if (nearSealKind(tr.kind)) {
                if (emergencyKind(tr.kind)) {
                    if (sealPlaced && dropTick < 0 && moved < REST_EPS) {
                        restTicksBeforeDrop++;
                    }
                    if (dropTick > 0 && reinstallTick < 0) {
                        slideDistance += moved;
                    }
                }
                if (sealPlaced && (int) Math.floor(bot.getX()) == tr.sealX
                        && (int) Math.floor(bot.getZ()) == tr.sealZ) {
                    enteredSeal = true;
                }
            }
            lastMoved = moved;
            trace(tr, nav);

            if (!bot.isAlive()) {
                record(tr, "FAIL", "died");
                return;
            }
            if (bot.getY() < tr.minFloorY) {
                record(tr, "FAIL", "fell off the tile");
                return;
            }
            double dx = bot.getX() - (tr.goal.getX() + 0.5);
            double dy = bot.getY() - tr.goal.getY();
            double dz = bot.getZ() - (tr.goal.getZ() + 0.5);
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist < closest) closest = dist;

            // Candidate PASS: the driver reverted to STAY (exact-tolerance arrival) near the goal cell —
            // then the trial's adoption-contract assertions decide.
            if (bot.mode() == AllyBotEntity.Mode.STAY && dist < 1.2) {
                String contractFail = contractFailure(tr);
                if (contractFail == null) {
                    record(tr, "PASS", "reached goal, adoption contract held");
                } else {
                    record(tr, "FAIL", "arrived but " + contractFail);
                }
                return;
            }
            if (nav.navGaveUp()) {
                if (attemptTicks <= NAV_RETRY_WINDOW && navRetries < MAX_NAV_RETRY) {
                    navRetries++;
                    justRetried = true; // the re-issue clears the plan — not a DROP
                    bot.comeTo(tr.goal, 0.75, 0.75, 0);
                    return;
                }
                record(tr, "FAIL", "nav gave up (no route offered)");
                return;
            }
            // Wedge detector: a fail→hold freezes the bot in place with a plan loaded — the observable form
            // of "step FAILED". Every legitimate still (fresh-search WAIT, parked-result boundaries) is far
            // shorter than the limit.
            if (moved < 0.02 && bot.mode() == AllyBotEntity.Mode.COME) {
                if (++noProgressTicks >= NO_PROGRESS_LIMIT) {
                    record(tr, "FAIL", "wedged — no progress for " + NO_PROGRESS_LIMIT
                            + " ticks (step FAILED / fail->hold?)");
                    return;
                }
            } else {
                noProgressTicks = 0;
            }
            if (attemptTicks >= ATTEMPT_BUDGET) {
                record(tr, "FAIL", "timeout");
            }
        }

        /** One-tick-deferred swap classification + the seam-anchoring invariant (DESIGN-replan-handoff.md §5):
         *  every install must be anchored either ON the new plan's body (case-3 FAST-FORWARD entry) or at its
         *  implicit start — a cell of the plan it superseded (case-2 ADOPT at the seam). An install anchored
         *  on NEITHER is the §1 mid-move hole resurfacing and fails the trial. */
        void classifySwap(Trial tr, BotNavigator nav) {
            pendSwap = false;
            swapCount++;
            String cls = pendOnNew ? "ONPLAN" : "ADOPT";
            boolean anchored = pendOnNew || pendOnOld;
            if (!anchored) {
                seamViolations++;
                cls = "UNANCHORED";
            }
            if (pendReroute && rerouteTick < 0) {
                rerouteTick = pendTick;
                rerouteClass = cls;
                rerouteFloor = pendFloor;
                rerouteSpd = pendSpd;
                rerouteLiveFloor = pendLive;
            }
            swapLog.append(String.format(Locale.ROOT, "[t%d %s %s%s cur=%d]",
                    pendTick, cls, pendFloor == null ? "?" : pendFloor.toShortString(),
                    pendReroute ? " reroute" : "", nav.waypointIndex()));
            try {
                trace.write(String.format(Locale.ROOT, "  CLASS tick=%d %s%s cursorNow=%d\n",
                        pendTick, cls, pendReroute ? " (reroute)" : "", nav.waypointIndex()));
            } catch (IOException ignored) { }
        }

        /** State-based seal arming (no timers — the course's own convention). DETOUR/REVERSAL keep the
         *  plain x-threshold (the seal is far ahead; only "a few moves in" matters). The near-seal tiles
         *  arm off the LIVE segment so the seal provably lands relative to the CURRENT move, mid-move by
         *  construction — see each tile's geometry comment for the derivation of the thresholds. */
        boolean sealTriggered(Trial tr, BotNavigator nav) {
            switch (tr.kind) {
                case PREFIXSEAL:
                    // Mid-move INTO sealX-1 (x in [sealX-1.3, sealX-1)): the seal lands on the NEXT step.
                    return nav.segToX() == tr.sealX - 1 && nav.segToZ() == tr.zc
                            && bot.getX() >= tr.editX;
                case CURRENTSEAL:
                    // Last half-block before the CURRENT destination's boundary (x in [sealX-0.5, sealX)).
                    return nav.segToX() == tr.sealX && nav.segToZ() == tr.zc
                            && bot.getX() >= tr.editX;
                case CURRENTSEAL_ICE:
                    // The tick the cursor turns north: the destination IS the T-link cell, and the bot
                    // still carries its +X ice cruise (mid-move by construction — reached just fired).
                    return nav.segToX() == tr.sealX && nav.segToZ() == tr.sealZ;
                default:
                    return bot.getX() >= tr.editX;
            }
        }

        /** The bot's live FLOOR cell (feet cell minus one — exact on these full-block tiles). */
        BlockPos botFloorCell() {
            return new BlockPos((int) Math.floor(bot.getX()), (int) Math.floor(bot.getY()) - 1,
                    (int) Math.floor(bot.getZ()));
        }

        /** {@code null} when every end-of-trial assertion holds; else a short reason. The assertions are the
         *  §9.2 cases mapped onto observables — see the class Javadoc for the mapping. */
        String contractFailure(Trial tr) {
            if (pendSwap) { // a swap on the arrival tick itself never classifies — treat as anchored, count it
                pendSwap = false;
                swapCount++;
            }
            if (!emergencyKind(tr.kind) && planDrops > 0) {
                // Only the U5 emergency tiles ratify a drop; anywhere else — INCLUDING prefixseal,
                // whose first-§10-shape drop was explicitly retired (U6) — a drop is a failure by
                // itself. The emergency tiles expect exactly the ratified one, checked in
                // dropShapeFailure below.
                return planDrops + " unexpected plan drop(s) mid-course (a PANIC or the retired"
                        + " first-shape §10 machinery, where none belongs)";
            }
            if (seamViolations > 0) {
                return seamViolations + " install(s) anchored on neither plan (the §1 mid-move hole)";
            }
            switch (tr.kind) {
                case CONTROL: {
                    if (swapCount != 0) {
                        return "expected zero swaps on the untouched corridor, saw " + swapCount
                                + " " + swapLog;
                    }
                    return null;
                }
                case DETOUR: {
                    String common = editTileFailure(tr);
                    if (common != null) return common;
                    if (!"ADOPT".equals(rerouteClass)) {
                        // §5 case 2: the diverging plan shares no pre-seam cell, so the install MUST be the
                        // implicit-start entry at the seam — a body entry here means the pump adopted early.
                        return "reroute install was " + rerouteClass + ", expected pure ADOPT at the seam";
                    }
                    return null;
                }
                case REVERSAL: {
                    String common = editTileFailure(tr);
                    if (common != null) return common;
                    // The reversing plan must actually be WALKED back: the bot re-reaches the U-link column
                    // it had left ~15 cells behind — the clean-execution proof for the reversing step.
                    if (postRerouteMinX > tr.westLinkX + 1.5) {
                        return String.format(Locale.ROOT,
                                "never returned to the U-link (min x %.2f, link %d) — the reversal was not walked",
                                postRerouteMinX, tr.westLinkX);
                    }
                    return null;
                }
                case PREFIXSEAL: {
                    // The unified design closes the old sync/async split: ONE shape, both modes — the
                    // U1 same-tick prompt + the U2 clamp to the current move's destination, adopted
                    // seam-anchored. (The superseded first shape's drop branch is gone; a drop here
                    // already failed the generic check above.)
                    return prefixSealFailure(tr);
                }
                default: { // CURRENTSEAL / CURRENTSEAL_ICE — the U5 emergency: the drop shape, both modes
                    return dropShapeFailure(tr);
                }
            }
        }

        /** The U5 emergency-shape contract shared by the two currentseal tiles (the unified 2026-08-18
         *  design): the CURRENT move's cells were invalidated, so inputs cut NOW + exactly one prompt
         *  drop, nothing installed in between (the pending seeded search + parked result die with the
         *  plan), the relaunch anchored on the live floor AT REST (the kept planless-pickup rest gate —
         *  U5 and PANIC are the ONLY atRest consumers), and the reroute physically walked. Every check
         *  is a state test over what the course sampled — no timing windows. */
        String dropShapeFailure(Trial tr) {
            if (!sealPlaced) {
                return "the seal never armed (the trigger segment was never observed)";
            }
            if (enteredSeal) {
                return "the bot's feet column entered the sealed cell";
            }
            if (planDrops != 1) {
                return "expected exactly the one ratified U5 drop, saw " + planDrops + " " + swapLog;
            }
            if (dropTick < 0) {
                return "the plan drop preceded the seal (not the U5 drop)";
            }
            if (restTicksBeforeDrop > 0) {
                return "plan not dropped promptly — the bot sat at rest " + restTicksBeforeDrop
                        + " tick(s) before the drop (a debounced detection left it pressing a dead route)";
            }
            if (swapsSealToDrop > 0) {
                return swapsSealToDrop + " install(s) between seal and drop — the pending seeded"
                        + " search/parked result did not die with the plan";
            }
            if (tr.kind != Kind.CURRENTSEAL_ICE) {
                // CURRENTSEAL: the wall refuses entry to sealX, so the coast ends in the boundary cell
                // sealX-1 — the drop floor — and the bot never passes the seal column before relaunching.
                if (dropFloor == null || dropFloor.getX() != tr.sealX - 1 || dropFloor.getZ() != tr.zc) {
                    return "dropped at floor " + (dropFloor == null ? "?" : dropFloor.toShortString())
                            + ", expected the current step's boundary cell x=" + (tr.sealX - 1);
                }
                if (preRerouteMaxX >= tr.sealX) {
                    return String.format(Locale.ROOT,
                            "bot passed the sealed column (max x %.2f) before the relaunch", preRerouteMaxX);
                }
            } else if (dropFloor == null || dropFloor.getZ() != tr.zc) {
                // The ice tile's drop lands while the bot still rides row A (the slide is east along it).
                return "dropped at floor " + (dropFloor == null ? "?" : dropFloor.toShortString())
                        + ", expected the bot still on row A (z=" + tr.zc + ")";
            }
            if (reinstallTick < 0) {
                return "no fresh plan was installed after the drop " + swapLog;
            }
            if (reinstallSpd >= REST_EPS) {
                return String.format(Locale.ROOT,
                        "relaunch installed on a MOVING bot (spd %.4f >= eps %.2f) — the kept rest gate"
                                + " (the U5 planless pickup's atRest) did not hold", reinstallSpd, REST_EPS);
            }
            if (reinstallFloor == null || reinstallStep0 == null
                    || Math.max(Math.abs(reinstallStep0.getX() - reinstallFloor.getX()),
                                Math.abs(reinstallStep0.getZ() - reinstallFloor.getZ())) > 1
                    || Math.abs(reinstallStep0.getY() - reinstallFloor.getY()) > 1) {
                return "fresh plan's step 0 " + (reinstallStep0 == null ? "?" : reinstallStep0.toShortString())
                        + " is not adjacent to the rest floor "
                        + (reinstallFloor == null ? "?" : reinstallFloor.toShortString())
                        + " — the relaunch was not anchored on the live floor";
            }
            if (tr.kind != Kind.CURRENTSEAL_ICE
                    && (reinstallFloor.getX() != tr.sealX - 1 || reinstallFloor.getZ() != tr.zc)) {
                return "rest cell " + reinstallFloor.toShortString()
                        + " is not the boundary cell x=" + (tr.sealX - 1)
                        + " — the bot did not stop at its current step's boundary";
            }
            if (tr.kind == Kind.CURRENTSEAL_ICE) {
                if (reinstallFloor.getX() <= tr.sealX || reinstallFloor.getZ() != tr.zc) {
                    return "rest cell " + reinstallFloor.toShortString()
                            + " — expected the slide to carry the bot EAST past the sealed turn on row A";
                }
                if (slideDistance <= SLIDE_MIN) {
                    return String.format(Locale.ROOT,
                            "no real slide: post-null travel %.2f <= %.1f blocks", slideDistance, SLIDE_MIN);
                }
            }
            if (postRerouteMinX > tr.westLinkX + 1.5) {
                return String.format(Locale.ROOT,
                        "never returned to the %s (min x %.2f, link %d) — the reroute was not walked",
                        tr.kind == Kind.CURRENTSEAL_ICE ? "backtracking L-link" : "U-turn link",
                        postRerouteMinX, tr.westLinkX);
            }
            if (!visitedBypass) {
                return "the return row's reroute-only stretch was never walked";
            }
            return null;
        }

        /** PREFIXSEAL's contract — the unified design's U1+U2 shape, identical in BOTH pathing modes:
         *  the same-tick prompt replan, the seam CLAMPED to the last safe waypoint (never at or past the
         *  move-invalidated step — here the current move's destination, sealX-1), and a clean
         *  seam-anchored ADOPT there. No drop belongs here (convicted by the generic check before
         *  dispatch — the retired first shape's signature); the install's speed is recorded, not
         *  bounded, because hot first-touch entry at the seam is the §5 contract — the "never on a
         *  moving bot" invariant is asserted as ANCHOR integrity instead (settled anchor == the bot's
         *  live floor == the clamped seam). A parked pause AT the seam while the async search is still
         *  in flight (the U2-extended CAUTION hold) is legal and asserted by nothing here. */
        String prefixSealFailure(Trial tr) {
            if (!sealPlaced) {
                return "the seal never armed (the trigger segment was never observed)";
            }
            if (enteredSeal) {
                return "the bot's feet column entered the sealed cell";
            }
            if (rerouteTick < 0) {
                return "no rerouted plan was ever installed " + swapLog;
            }
            if (preSeamInstalls > 0) {
                return preSeamInstalls + " install(s) between the seal and the clamped-seam reroute —"
                        + " the U1 prompt double-fired or something stale adopted";
            }
            if (!"ADOPT".equals(rerouteClass)) {
                // U2 clamps the seam to the current move's destination and the result parks until the
                // bot settles THERE: the install must be the implicit-start entry (§5 case 2). A body
                // entry one boundary EARLY (the reversing plan contains the pre-seam cells) means the
                // pump adopted BEHIND the clamped seam.
                return "reroute install was " + rerouteClass + ", expected the clamped-seam ADOPT";
            }
            if (rerouteFloor == null) {
                return "reroute install had no settled anchor (installed while unsettled?)";
            }
            if (rerouteFloor.getX() != tr.sealX - 1 || rerouteFloor.getZ() != tr.zc) {
                return "reroute anchored at " + rerouteFloor.toShortString()
                        + ", expected the clamped seam — the current move's destination x=" + (tr.sealX - 1);
            }
            if (rerouteLiveFloor == null || !rerouteLiveFloor.equals(rerouteFloor)) {
                return "install anchored at " + rerouteFloor.toShortString() + " while the bot stood at "
                        + (rerouteLiveFloor == null ? "?" : rerouteLiveFloor.toShortString())
                        + " — an install anchored off the bot's live cell (the §1 shape)";
            }
            if (preRerouteMaxX >= tr.sealX) {
                return String.format(Locale.ROOT,
                        "bot reached the sealed column (max x %.2f) before the reroute installed",
                        preRerouteMaxX);
            }
            if (postRerouteMinX > tr.westLinkX + 1.5) {
                return String.format(Locale.ROOT,
                        "never returned to the U-turn link (min x %.2f, link %d) — the reroute was not walked",
                        postRerouteMinX, tr.westLinkX);
            }
            if (!visitedBypass) {
                return "the return row's reroute-only stretch was never walked";
            }
            return null;
        }

        /** The assertions shared by both edit tiles: the re-search fired, parked (adoption deferred to a
         *  cell AHEAD of the edit-time floor — §9.2(a)), never let the bot reach the seal on the dead route,
         *  and the reroute was genuinely followed (the bypass row was walked). */
        String editTileFailure(Trial tr) {
            if (!sealPlaced) {
                return "the seal never armed (bot finished before the edit trigger?)";
            }
            if (rerouteTick < 0) {
                return "no rerouted plan was ever installed " + swapLog;
            }
            if (rerouteFloor == null) {
                return "reroute install had no settled anchor (installed while unsettled?)";
            }
            // §9.2(a): between the edit and the install the bot kept executing the old plan's retained
            // prefix — the install anchors strictly AHEAD of where it stood when the world changed.
            // (The old ≥3-cell floor encoded the DEBOUNCED trigger's latency; under §10 U1 the prompt
            // replan fires the same tick, so the gap is the budget-derived §3 horizon — ~2 cells at the
            // 250 ms async budget, and the sync degenerate case is the current move's destination —
            // not a fixed constant.)
            if (editFloor != null && rerouteFloor.getX() <= editFloor.getX()) {
                return String.format(Locale.ROOT,
                        "install anchored at x=%d, not ahead of the edit floor x=%d — adoption was not"
                                + " deferred to the seam", rerouteFloor.getX(), editFloor.getX());
            }
            if (preRerouteMaxX >= tr.sealX) {
                return String.format(Locale.ROOT,
                        "bot reached the sealed column (max x %.2f) before the reroute installed", preRerouteMaxX);
            }
            if (!visitedBypass) {
                return "the bypass row was never walked — arrival did not use the rerouted plan";
            }
            return null;
        }

        void trace(Trial tr, BotNavigator nav) {
            double x = bot.getX(), y = bot.getY(), z = bot.getZ();
            double spd = Math.sqrt((x - prevX) * (x - prevX) + (z - prevZ) * (z - prevZ));
            boolean grnd = EntityState.onGround(bot);
            String move = bot.lastSteerMove;
            try {
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
                        "T %-9s %4d  %.3f %.3f %.3f | %.4f | %d | i=%d/%d | %s | %s\n",
                        tr.name, attemptTicks, x, y, z, spd, grnd ? 1 : 0,
                        nav.waypointIndex(), nav.pathSize(), nav.driveState(), move));
            } catch (IOException ignored) { }
            prevX = x;
            prevZ = z;
        }

        void record(Trial tr, String result, String reason) {
            String extra;
            if (emergencyKind(tr.kind)) {
                extra = String.format(Locale.ROOT,
                        " drop@%d dropFloor=%s restBeforeDrop=%d reinstall@%d spd=%.4f rest=%s slide=%.2f",
                        dropTick, dropFloor == null ? "?" : dropFloor.toShortString(),
                        restTicksBeforeDrop, reinstallTick, reinstallSpd,
                        reinstallFloor == null ? "?" : reinstallFloor.toShortString(), slideDistance);
            } else if (tr.kind == Kind.PREFIXSEAL) {
                // The clamped-seam adopt shape: the install's carry (observable, not bounded — §5 hot
                // entry), its live-floor anchor, and the between-seal-and-reroute install count.
                extra = String.format(Locale.ROOT,
                        " adoptSpd=%.4f adoptLive=%s preSeamInstalls=%d",
                        rerouteSpd, rerouteLiveFloor == null ? "?" : rerouteLiveFloor.toShortString(),
                        preSeamInstalls);
            } else {
                extra = "";
            }
            results.add(String.format(Locale.ROOT,
                    "%s = %s (%s) closest=%.2f ticks=%d seal@%d reroute@%d class=%s swaps=%d%s drops=%d"
                            + " finalPos=(%.1f,%.1f,%.1f) lastMove=%s",
                    tr.name, result, reason, closest, attemptTicks, sealTick, rerouteTick, rerouteClass,
                    swapCount, swapLog, planDrops, bot.getX(), bot.getY(), bot.getZ(), bot.lastSteerMove)
                    + extra);
            if (result.equals("PASS")) passed++; else failed++;
            OrebitCommon.LOGGER.info("[Orebit/replan] {} -> {} ({}) closest={} ticks={} reroute@{} class={} swaps={}",
                    tr.name, result, reason, String.format(Locale.ROOT, "%.2f", closest),
                    attemptTicks, rerouteTick, rerouteClass, swapCount);
            try { trace.write("  RESULT " + result + " (" + reason + ")\n\n"); } catch (IOException ignored) { }
            if (index + 1 < trials.size()) {
                enter(index + 1);
            } else {
                finish("all trials complete");
            }
        }

        /** Whether {@code floorCell} is the SEARCH-NATIVE floor of any step of {@code plan} — the same
         *  membership the §5 pump's on-plan arm tests (exact on these full-block ground corridors). */
        static boolean planContainsFloor(BlockPathPlan plan, BlockPos floorCell) {
            final int n = plan.size();
            for (int i = 0; i < n; i++) {
                if (plan.floor(i).equals(floorCell)) {
                    return true;
                }
            }
            return false;
        }

        // ---- tile construction ---------------------------------------------------------------------------

        void buildTile(Trial tr) {
            int bx = tr.baseX, zc = tr.zc;
            switch (tr.kind) {
                case CONTROL: {
                    // Plain 2-tall boxed corridor, entirely inside one 16-block level-0 region (x 1..14 of
                    // it) — no region crossing, no edit, and therefore no legitimate reason to swap plans.
                    box(bx, bx + 13, Y0, Y0 + 3, zc - 1, zc + 1);
                    carve(bx, bx + 11, Y0 + 1, Y0 + 2, zc);
                    break;
                }
                case DETOUR: {
                    // Main row A + a north bypass that leaves A at J=+18 (east of every reachable seam) and
                    // rejoins at +26, east of the seal column (+22, left OPEN until the course seals it).
                    box(bx, bx + 30, Y0, Y0 + 3, zc - 1, zc + 3);
                    carve(bx, bx + 28, Y0 + 1, Y0 + 2, zc);                 // row A
                    carve(bx + 18, bx + 26, Y0 + 1, Y0 + 2, zc + 2);        // bypass row B
                    carveCell(bx + 18, zc + 1);                             // north link at J
                    carveCell(bx + 26, zc + 1);                             // south rejoin link
                    break;
                }
                case CURRENTSEAL_ICE: {
                    // T-junction on blue ice (see the Trial ctor's geometry comment). Row A carries the
                    // approach + the dead-end slide runway; the L-link at -2 BACKTRACKS west past the
                    // start, so pre-seal the fresh plan deterministically turns north at the T-link (+8)
                    // — the cell the course seals. The approach + runway floor (+2..+12) is blue ice;
                    // the start mouth and the L-corner stay stone so launch acceleration and the
                    // reroute's westbound corner are ice-free (only STRAIGHT-line ice walking is asked
                    // of the follower; the pathological ice CORNER is exactly what the seal interrupts).
                    box(bx - 3, bx + 17, Y0, Y0 + 3, zc - 1, zc + 3);
                    carve(bx - 2, bx + 12, Y0 + 1, Y0 + 2, zc);             // row A: approach + runway
                    carve(bx - 2, bx + 16, Y0 + 1, Y0 + 2, zc + 2);         // row B: the goal row
                    carveCell(bx - 2, zc + 1);                              // L-link (backtracking, reroute-only)
                    carveCell(bx + 8, zc + 1);                              // T-link (the fresh plan's turn; sealed)
                    for (int x = bx + 2; x <= bx + 12; x++) {
                        set(x, Y0, zc, BLUE_ICE);                           // the ice approach + runway floor
                    }
                    break;
                }
                default: { // REVERSAL + PREFIXSEAL + CURRENTSEAL — the same corridor + U-link tile
                    // Main row A + the U-return: a link BEHIND the leg at +1, the return row to +22, a south
                    // rejoin east of the seal (+16 for reversal; the near-seal tiles seal +12, see the Trial
                    // ctor). The only post-seal route reverses the bot's travel.
                    box(bx, bx + 26, Y0, Y0 + 3, zc - 1, zc + 3);
                    carve(bx, bx + 24, Y0 + 1, Y0 + 2, zc);                 // row A
                    carve(bx + 1, bx + 22, Y0 + 1, Y0 + 2, zc + 2);         // return row B
                    carveCell(bx + 1, zc + 1);                              // west (U-turn) link
                    carveCell(bx + 22, zc + 1);                             // east rejoin link
                    break;
                }
            }
        }

        // ---- placement primitives ------------------------------------------------------------------------

        /** Solid stone fill over the inclusive box. */
        void box(int x0, int x1, int y0, int y1, int z0, int z1) {
            for (int x = x0; x <= x1; x++)
                for (int y = y0; y <= y1; y++)
                    for (int z = z0; z <= z1; z++)
                        set(x, y, z, STONE);
        }

        /** Carve a 1-wide corridor body (air) at {@code z} over the inclusive spans. */
        void carve(int x0, int x1, int y0, int y1, int z) {
            for (int x = x0; x <= x1; x++)
                for (int y = y0; y <= y1; y++)
                    set(x, y, z, AIR);
        }

        /** Carve one 2-tall corridor cell (a link column between the two rows). */
        void carveCell(int x, int z) {
            set(x, Y0 + 1, z, AIR);
            set(x, Y0 + 2, z, AIR);
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
                kv(w, "async", ConfigLoader.config().asyncPathing());
                kv(w, "trials", trials.size());
                kv(w, "passed", passed);
                kv(w, "failed", failed);
                for (String line : results) {
                    w.write(line);
                    w.write('\n');
                }
            } catch (IOException e) {
                OrebitCommon.LOGGER.error("[Orebit/replan] could not write {}", file, e);
            }
            try { if (trace != null) trace.close(); } catch (IOException ignored) { }
            // TEMP DIAGNOSTIC (owner 2026-08-18): disarm the reversal trial's region trace before halting.
            RegionPathfinder.TRACE = false;
            RegionPathfinder.TRACE_OUT = null;
            try { if (rtrace != null) rtrace.close(); } catch (IOException ignored) { }
            OrebitCommon.LOGGER.info("[Orebit/replan] DONE ({}) — {} passed / {} failed of {} — halting",
                    reason, passed, failed, trials.size());
            server.halt(false);
            Thread exiter = new Thread(() -> {
                server.halt(true);
                System.exit(0);
            }, "orebit-replan-exit");
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
