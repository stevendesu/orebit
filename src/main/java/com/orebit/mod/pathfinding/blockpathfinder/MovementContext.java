package com.orebit.mod.pathfinding.blockpathfinder;

import com.orebit.mod.pathfinding.blockpathfinder.cuboid.Axes;
import com.orebit.mod.pathfinding.blockpathfinder.cuboid.Cuboid;
import com.orebit.mod.pathfinding.blockpathfinder.cuboid.NavGridCuboidsView;
import com.orebit.mod.worldmodel.navblock.NavBlock;
import com.orebit.mod.worldmodel.pathing.NavFlags;
import com.orebit.mod.worldmodel.pathing.NavGridView;
import com.orebit.mod.worldmodel.pathing.TraversalGrid;

import net.minecraft.world.level.block.Blocks;

/**
 * The world-and-bot context a {@link Movement} reads while expanding a node: the {@link NavGridView}
 * (the cheap "is it built" gate + live per-cell geometry) and the {@link
 * BotCaps}. It also hosts the small set of geometry <i>predicates</i> every Tier 1 movement shares —
 * {@link #passable}, {@link #standable}, {@link #topYOf} — so each movement reads facts through one
 * vocabulary rather than re-deriving bit extraction. (These live on the context, a per-pathfind smart
 * object, not a static helper class.)
 *
 * <p>Single-threaded per pathfind (the underlying {@link NavGridView} reuses a cursor), matching the
 * search.
 */
public final class MovementContext {

    // ---- Vertical-clearance physics (canon; ALL heights in SIXTEENTHS of a block) ----------------------
    // A floor cell's real surface is its block base plus the NavBlock topY fraction (16 = full block,
    // 8 = bottom slab). Every "can I gain that floor" rule is therefore a RISE test between two real
    // surfaces, not a block-level compare: rise = dyBlocks·16 + destTopY − startTopY (see #rise). The
    // two budgets below are the only two vertical gains vanilla locomotion offers.

    /**
     * Sixteenths of rise ONE vanilla jump clears: the jump apex is 1.25 blocks above the feet
     * ({@code vy₀ = 0.42} with per-tick {@code vy ← (vy − 0.08)·0.98} peaks at ~1.25), and
     * {@code 1.25 × 16 = 20}. A move that must gain more than this between the START floor's top and
     * the DESTINATION floor's top cannot be jumped: e.g. slab → full block one level up is
     * {@code 16 + 16 − 8 = 24 > 20} (you cannot ascend 1.5 blocks), while full → full one up is
     * {@code 16 + 16 − 16 = 16 ≤ 20} (the everyday Ascend).
     */
    public static final int JUMP_RISE = 20;

    /**
     * Sixteenths of rise the vanilla step assist (0.6-block auto-step) clears without a jump:
     * {@code 9/16 = 0.5625 ≤ 0.6 < 10/16 = 0.625}, so 9 is the largest whole-sixteenth lip that fits
     * under the 0.6 threshold. (The historical value here was 10 — {@code 10/16 = 0.625 > 0.6} — which
     * over-admitted exactly-10-sixteenth lips; reconciled to the physics.) Measured between the two
     * floors' real tops via {@link #rise}: onto a slab one level up from a full block is
     * {@code 16 + 8 − 16 = 8 ≤ 9} (auto-step), from a slab onto the same slab-step is
     * {@code 16 + 8 − 8 = 16 > 9} (needs the jump).
     */
    public static final int STEP_ASSIST_MAX_RISE = 9;

    /**
     * The rise (sixteenths, negative = a drop) from standing on a floor with collision top
     * {@code startTopY} to standing on a floor {@code dyBlocks} block-levels away with collision top
     * {@code destTopY}: {@code dyBlocks·16 + destTopY − startTopY}. The single derivation point every
     * height-aware gate ({@link #STEP_ASSIST_MAX_RISE} / {@link #JUMP_RISE}) measures against — static
     * integer math, JIT-inlined, hot-path safe.
     */
    public static int rise(int dyBlocks, int destTopY, int startTopY) {
        return dyBlocks * 16 + destTopY - startTopY;
    }

    /**
     * The height (sixteenths, within the floor cell) of the surface the bot's feet rest on at node
     * {@code (x,y,z)} — the START-side input to every {@link #rise} test. For a {@link #standable}
     * floor this is its real collision top ({@code topY}: 16 full block, 8 slab, 2 repeater plate).
     * For a NON-standable "floor" it is <b>16</b>: the floor-cell convention gives float nodes a
     * non-solid floor (a surface-swim node's floor is the water cell below the feet — Swim decision C —
     * and a climb node's can be the ladder/vine), and such a bot's feet ride at the cell boundary
     * {@code y+1.0}, exactly where a full-block top would put them — so water/climb starts keep their
     * historical rise arithmetic (a shore exit is NOT a 16/16-deficit jump). One path-edit-aware
     * descriptor read (a PLACED floor correctly reads as the full cube).
     */
    public int floorSurface(int x, int y, int z) {
        long d = descriptorAt(x, y, z);
        return NavBlock.isStandable(d) ? NavBlock.topY(d) : 16;
    }

    // Stair FACING (NavBlock ordinal 0=N 1=E 2=S 3=W) as an (dx,dz) step — the side a BOTTOM stair's HIGH
    // 16/16 half sits on (StairVoxelProbe). N=(0,-1) E=(1,0) S=(0,1) W=(-1,0).
    private static final int[] FACING_DX = {0, 1, 0, -1};
    private static final int[] FACING_DZ = {-1, 0, 1, 0};

    /**
     * The collision top surface (sixteenths) of floor descriptor {@code d} as seen from the horizontal
     * edge {@code (edgeDx,edgeDz)} — the direction from THIS cell toward the neighbour whose transition is
     * being priced. For a BOTTOM stair this is the DIRECTIONAL surface: {@code 16} when the high 16/16 half
     * is on that edge (i.e. the edge points along the stair's FACING — see {@link NavBlock#stairFacing}),
     * else {@code 8} (the low 8/16 front, and both perpendicular edges — the straight-stair approximation,
     * corners ignored per v1 scope). For a TOP stair (flat 16/16 top) or any non-stair this is simply {@link
     * NavBlock#topY} — so every non-stair call is byte-identical to the old scalar {@code topYOf}, and the
     * stair branch is gated behind one predictable {@link NavBlock#isStair} test on the hot path.
     */
    public int directionalTopY(long d, int edgeDx, int edgeDz) {
        if (NavBlock.isStair(d) && NavBlock.stairHalf(d) == 0) {
            int f = NavBlock.stairFacing(d);
            return (edgeDx == FACING_DX[f] && edgeDz == FACING_DZ[f]) ? 16 : 8;
        }
        return NavBlock.topY(d);
    }

    /**
     * The START-side surface height toward edge {@code (edgeDx,edgeDz)} — {@link #floorSurface} made
     * directional for a stair takeoff cell. Non-standable floors keep the {@code 16} float-node convention
     * (water/climb starts); a standable stair reports its {@link #directionalTopY} toward the move; every
     * other standable floor reports its plain {@code topY} (identical to {@code floorSurface}). One
     * descriptor read (path-edit-aware), same as {@code floorSurface}.
     */
    public int floorSurfaceToward(int x, int y, int z, int edgeDx, int edgeDz) {
        long d = descriptorAt(x, y, z);
        return NavBlock.isStandable(d) ? directionalTopY(d, edgeDx, edgeDz) : 16;
    }

    /**
     * Whether the TAKEOFF FLOOR cell {@code (x,y,z)} is GENUINE SOLID FOOTING a running jump can launch
     * from: {@link #standable} (a real solid top the bot stands ON) <b>AND</b> not a {@link
     * NavBlock#isClimbable climbable} (ladder / scaffolding / vine family). A climbable is a CLIMBING
     * state, not a ground state — vanilla gives NO {@code 0.42} horizontal launch off a ladder/vine: the
     * jump key only climbs UP faster and horizontal input merely EJECTS the bot from the climbable, so a
     * jump whose takeoff cell is a climbable is physically impossible. This is the single "you can only
     * initiate a jump from solid ground" gate the jump-takeoff movements ({@link
     * com.orebit.mod.pathfinding.blockpathfinder.movements.Parkour}/{@code DiagonalParkour}) test at the
     * top of {@code candidates}. Both conjuncts are load-bearing: a VINE (empty shape) is caught by
     * {@code standable} (it is not solid-topped); a LADDER / SCAFFOLDING is a {@link NavBlock#SHAPE_OTHER}
     * with a full-block collision top, so it reads {@code standable == true} and is caught ONLY by the
     * {@code !climbable} term. Without this gate a mid-vine/-ladder node — which {@link
     * com.orebit.mod.pathfinding.blockpathfinder.movements.Climb} keeps at {@link #MODE_STANDING} — is
     * silently offered a jump ({@link #floorSurface} even reports the {@code 16} full-block sentinel for a
     * non-standable floor, so the envelope treats a vine takeoff as a full block).
     *
     * <p>Reads the floor descriptor the SAME way {@link #reducesJump} / {@link #floorSurface} do
     * (path-edit-aware, so a PLACED floor reads as solid) — one descriptor read + two bit tests, hot-path
     * lean. A standable NON-climbable floor (stone / slab / stair / farmland / soul sand / honey — honey
     * is refused separately by {@link #reducesJump}) passes unchanged, so ordinary parkour is not
     * narrowed; an UNBUILT floor reads air ⇒ not standable ⇒ {@code false} (never jump from unknown,
     * matching the other takeoff gates).
     */
    public boolean solidFooting(int x, int y, int z) {
        long d = descriptorAt(x, y, z);
        return NavBlock.isStandable(d) && !NavBlock.isClimbable(d);
    }

    /**
     * Whether the TAKEOFF FLOOR cell {@code (x,y,z)} — the standable cell a jump move launches FROM, the
     * same cell {@link #floorSurface} reads — is a {@link NavBlock#reducesJump REDUCED-JUMP} floor (honey
     * block, jump factor 0.5): the jump apex there (~0.384 blocks) clears nothing, so every jump-takeoff
     * movement ({@link com.orebit.mod.pathfinding.blockpathfinder.movements.Ascend}/{@code Pillar}/{@code
     * Parkour}/{@code DiagonalParkour}) refuses at the top of its {@code candidates}. Reads the floor
     * descriptor the SAME way {@link #floorSurface} does (path-edit-aware) so jump-factor and topY agree on
     * the identical cell; an UNBUILT floor reads as air (jump factor 1.0), so this returns {@code false} —
     * never gate on unknown. One descriptor read + bit test.
     */
    public boolean reducesJump(int x, int y, int z) {
        return NavBlock.reducesJump(descriptorAt(x, y, z));
    }

    /**
     * Whether the bot's TAKEOFF BODY space over floor {@code (x,y,z)} — the feet cell {@code (x,y+1,z)} and
     * head cell {@code (x,y+2,z)} the bot occupies while standing here — holds a {@link NavBlock#TRANSIT_HEAVY
     * HEAVY through-slow} block (cobweb, the only vanilla case). A cobweb the body is INSIDE applies vanilla's
     * {@code stuckSpeedMultiplier} to the WHOLE move vector each tick — the Y component too ({@code
     * Entity.move}: {@code vec3 = vec3.multiply(stuckSpeedMultiplier)}), and cobweb's Y multiplier is {@code
     * 0.05} ({@code WebBlock.entityInside} → {@code new Vec3(0.25, 0.05, 0.25)}), so a jump's {@code 0.42}
     * take-off velocity becomes {@code 0.021} — apex ~0.021 blocks, un-jumpable (owner-verified in-game: only
     * step-assist leaves a cobweb, never a jump). So the jump-takeoff movements ({@link
     * com.orebit.mod.pathfinding.blockpathfinder.movements.Ascend}/{@code Pillar}/{@code Parkour}/{@code
     * DiagonalParkour}) refuse at the top of their {@code candidates}; the WALKING moves are deliberately not
     * gated (walking / step-assist through cobweb is fine).
     *
     * <p>This is the BODY cell, distinct from {@link #reducesJump}'s FLOOR cell (honey, a solid block you
     * stand ON): honey throttles jump power via {@code getJumpFactor}, cobweb throttles jump velocity via the
     * stuck multiplier of the passable block you stand IN. Only HEAVY qualifies: the LIGHT through-slow blocks
     * (sweet berry bush Y {@code 0.75} → apex ~0.76; powder snow Y {@code 1.5} → boosts) leave a jumpable
     * apex, so this returns {@code false} for them — the existing HEAVY/LIGHT split IS the vertical-jump-kill
     * discriminator, no new descriptor bit needed.
     *
     * <p>Hot-path lean: reads the floor cell's precomputed {@link NavFlags#SLOW_TRANSIT} prefilter first —
     * the common (no through-slow block in the body) case is one bit test with ZERO extra grid reads. Only
     * when that fires (rare) are the two body descriptors read to distinguish HEAVY (gate) from LIGHT (don't).
     * The prefilter is column-local and seam-exact (see {@link #bodyTransitCost(int, int, int, int)}), and an
     * UNBUILT floor reads flags {@code 0} → {@code false} (never gate on unknown), matching {@code
     * reducesJump}.
     */
    public boolean noJumpFromBody(int x, int y, int z) {
        if (!NavFlags.slowTransit(flagsAt(x, y, z))) return false;
        return NavBlock.transitSlow(descriptorAt(x, y + 1, z)) == NavBlock.TRANSIT_HEAVY
                || NavBlock.transitSlow(descriptorAt(x, y + 2, z)) == NavBlock.TRANSIT_HEAVY;
    }

    /**
     * Whether the bot's TAKEOFF BODY space over floor {@code (x,y,z)} — the feet cell {@code (x,y+1,z)} and
     * head cell {@code (x,y+2,z)} — holds a {@link NavBlock#TRANSIT_LIGHT LIGHT through-slow} block (sweet
     * berry bush / powder snow): the {@code occBucket} input to {@link
     * com.orebit.mod.pathfinding.blockpathfinder.movements.ParkourEnvelope}. A jump launched with a body
     * cell inside a slow block loses horizontal reach (berry's {@code stuckSpeedMultiplier} scales the whole
     * move vector), so the envelope reads a TIGHTER row — this predicate is that row's gate.
     *
     * <p>Prefiltered on the floor's {@link NavFlags#SLOW_TRANSIT} bit exactly like {@link #noJumpFromBody}
     * (one bit test, zero grid reads in the common clear case); an UNBUILT floor reads flags {@code 0} →
     * {@code false} (never gate on unknown). Powder snow is ALSO {@code TRANSIT_LIGHT}; pricing it with the
     * berry row (marginally tighter) is deliberate and safe — a slow body cell may only ever REDUCE reach,
     * never fabricate it (see {@code ParkourEnvelope}'s no-help clamp). {@link #noJumpFromBody} has already
     * refused the HEAVY (cobweb) case before any jump move consults this, so a set prefilter here is LIGHT.
     */
    public boolean bodyTransitLight(int x, int y, int z) {
        if (!NavFlags.slowTransit(flagsAt(x, y, z))) return false;
        return NavBlock.transitSlow(descriptorAt(x, y + 1, z)) == NavBlock.TRANSIT_LIGHT
                || NavBlock.transitSlow(descriptorAt(x, y + 2, z)) == NavBlock.TRANSIT_LIGHT;
    }

    /**
     * Sentinel a {@link #packedAt} read returns for an unbuilt cell — re-exported from {@link
     * NavGridView#UNBUILT} so a movement compares against it without importing the grid view.
     */
    public static final int UNBUILT = NavGridView.UNBUILT;

    /**
     * Quantized hardness value {@link NavBlock} uses for an unbreakable block (bedrock, barrier, …):
     * such a cell can never be folded into a break-set however capable the bot.
     */
    public static final int UNBREAKABLE_HARDNESS = 255;

    // ---- Movement MODE — the hitbox/pose state carried in the search node key (x,y,z,mode) -------------
    // A small dimension so the same cell visited in a different pose is a DISTINCT search row: it lets the
    // search model state moves whose legal continuations depend on how you arrived (sprint-swim must
    // INITIATE in 2-deep water, then RETAINS the prone pose through 1-deep). A movement gates on the current
    // node's mode via {@link #mode()}; the transition moves (StartSprintSwim / Surface) emit a destination in
    // a new mode via the mode-carrying CandidateSink.accept overloads.
    //
    // There are only TWO modes because vanilla has only two relevant POSES: standing and prone. Vanilla has
    // NO separate "crawl" pose — going prone under a 1-tall ceiling on land uses the SAME Pose.SWIMMING
    // (0.6-tall hitbox) as sprint-swimming. So a single PRONE mode covers both sprint-swim and (future)
    // crawl; whether a PRONE node behaves as swim or crawl is decided by the cell's water-ness at expansion
    // time (geometry), NOT a second mode bit. The key reserves 2 bits, leaving room if a third pose ever
    // proves necessary.

    /** Upright (1.8-tall hitbox) — the default/start mode; all ground moves + surface Swim live here. */
    public static final int MODE_STANDING = 0;
    /**
     * Prone (0.6-tall, {@code Pose.SWIMMING}) — entered via StartSprintSwim (2-deep water) or, later,
     * StartCrawl (low ceiling). In water it's sprint-swim; on land in a 1-tall gap it's crawl — same pose,
     * the medium is read from geometry. Left via Surface/StandUp back to {@link #MODE_STANDING}.
     */
    public static final int MODE_PRONE = 1;

    // ---- Break / place cost model (REAL TICKS — PRD §10 Phase 1d, physically-derived-costs) ------------
    // The whole search cost unit is real game ticks (20 = 1 s); break and place costs are the actual time
    // (and, for place, the inventory value) the bot spends, NOT tuned magic numbers. Break cost is the
    // resident mining-tick table (MiningModel); place cost is a tick-to-place plus an inventory premium.

    /**
     * The DEFAULT flat cost (ticks) charged per block placed — NOT a physical placement time. Placing a block
     * in-game is ~1 tick (face, reach, interact); this {@code 6} is a deliberate <b>behavioral "reluctance to
     * place"</b> penalty: the place interaction plus a few ticks of positioning/facing overhead beyond the bare
     * move, plus a bias against needless scaffolding (so A* prefers walking or digging around to building when a
     * comparable route exists). It is intentionally well below the old Baritone-seeded {@code 20} — at {@code 6}
     * against a ~4.6-tick walk the bot is appreciably more build-happy (it will pillar/bridge a short way rather
     * than take a long detour), which is the intended trade.
     *
     * <p>This static value is the DEFAULT and the value the headless/benchmark/trace/test paths use (they pass
     * no live bot). It is also what {@link com.orebit.mod.pathfinding.blockpathfinder.cuboid.GoalForcedCost}
     * derives its anti-flood pillar premium from (the heuristic probe has no per-bot context). A live follower's
     * actual g-cost place base is the configurable {@code placement.placeBaseCost} knob, threaded in via {@link
     * InventoryView#placeBaseCost()} (this constant is the fallback when no snapshot is supplied).
     */
    public static final float PLACE_BASE_COST = 6.0f;

    /**
     * Extra ticks charged when a placement <b>consumes a real carried block</b> ({@code
     * placement.consumesBlocks} on) — Steve's "premium for the cost of the placed block." Spending one of the
     * bot's finite blocks is worth more than the place TIME alone: this surcharge makes A* prefer a route that
     * doesn't burn inventory when a comparable one exists, and biases pillaring/bridging toward the shortest
     * block spend. A flat premium (not a per-item market price) because the feasibility model is a cheap scalar
     * budget, not a per-type valuation (Baritone-style); when {@code consumesBlocks} is off (the default —
     * infinite conjured supply) it is NOT charged, so today's behaviour is unchanged. ~½ a place-time, enough
     * to tilt ties without dominating the real time cost.
     */
    public static final float PLACE_INVENTORY_PREMIUM = 10.0f;

    /** Geometry a path-placed block reads as — the cobblestone the follower actually places (full cube). */
    private static final long PLACED_DESC = NavBlock.descriptorFor(Blocks.COBBLESTONE.defaultBlockState());
    /** Geometry a path-broken cell reads as — air. */
    private static final long AIR_DESC = NavBlock.descriptor(NavBlock.AIR);

    private final NavGridView grid;
    private final BotCaps caps;

    /**
     * The per-pathfind inventory feasibility snapshot (PRD §10 Phase 1b/1c) — the cheap, Baritone-style cap
     * read from the bot's REAL inventory ONCE before the search loop (see {@link
     * com.orebit.mod.platform.BotInventory#feasibility}). {@code null} for the legacy / headless / test
     * searches that pass no bot (the benchmarks and {@code /bot trace}), in which case the gates fall back
     * to the historical caps-only behaviour (infinite throwaway blocks, insta-mine within the hardness cap)
     * — so nothing changes until a live bot supplies one. It is plain primitives + a resident-table handle;
     * the gate methods below read it, never the live {@link net.minecraft.world.entity.player.Inventory}, so
     * the hot path stays alloc-free (HOT-PATH-NO-ALLOC).
     */
    private InventoryView inventory;

    /** Reused per-move edit accumulator (single-threaded per pathfind, like the grid cursor). */
    private final EditScratch editScratch = new EditScratch(this);
    /** The planned edits along the path to the node being expanded — a diff over the grid (see below). */
    private final PathEdits pathEdits = new PathEdits();

    // ---- Macro-movement context (MACRO-IMPLEMENTATION.md §8). Null/zero when macros are off (the legacy
    //      micro search, or an unbounded search with no corridor): every macro-aware movement then emits its
    //      plain single micro step. Wired once per pathfind via setMacro(). ----
    /** The per-search cuboid query seam for macro collapse; {@code null} ⇒ macros off (legacy micro search). */
    private NavGridCuboidsView cuboids;
    /** The search goal (absolute world block coords) — a macro jump bounds its length to it (never overshoot). */
    private int goalX, goalY, goalZ;
    /**
     * The search's single <b>primary travel axis</b> {@code P} ({@link Axes#AXIS_X}/{@link Axes#AXIS_Y}/
     * {@link Axes#AXIS_Z}) — the dominant start→goal approach direction, computed once per pathfind. Only a
     * macro-aware movement whose own travel axis equals {@code P} extracts a cuboid and emits a macro jump;
     * a movement travelling any other axis takes its plain micro step (Option B).
     * This pins per-node extraction to ONE axis instead of up to three (Pillar/MineDown → Y, the Traverse
     * cardinals → X and Z), so a uniform region is extracted once per search, not once per axis. Defaults to
     * {@link Axes#AXIS_X}; meaningful only when {@link #cuboids} is non-null.
     */
    private int macroAxis = Axes.AXIS_X;
    /** A reusable {@link Cuboid} a macro movement fills via {@link #cuboids()} — no per-candidate allocation. */
    private final Cuboid cuboidScratch = new Cuboid();

    /**
     * The mode of the node currently being expanded (one of {@link #MODE_STANDING}/{@code MODE_SWIMMING}/
     * {@code MODE_CRAWLING}). Set by {@link BlockPathfinder} per expansion (like the relaxer's current g),
     * read by a movement's {@code candidates} to gate itself. Defaults to {@link #MODE_STANDING}.
     */
    private int currentMode = MODE_STANDING;

    public MovementContext(NavGridView grid, BotCaps caps) {
        this.grid = grid;
        this.caps = caps;
    }

    /**
     * Wire the macro-movement search context — the per-search cuboid view, the goal, and the primary travel
     * axis {@code P} — once per pathfind (after construction, before the search loop). Passing
     * {@code cuboids == null} leaves macros off, so every macro-aware movement falls back to its single micro
     * step (legacy parity). {@code macroAxis} (Option B) is the dominant start→goal approach axis the caller
     * computed; only a movement travelling that axis emits a macro jump (see {@link #macroAxis()}).
     */
    public void setMacro(NavGridCuboidsView cuboids, int goalX, int goalY, int goalZ, int macroAxis) {
        this.cuboids = cuboids;
        this.goalX = goalX;
        this.goalY = goalY;
        this.goalZ = goalZ;
        this.macroAxis = macroAxis;
    }

    /** The per-search cuboid query seam, or {@code null} when macro collapse is off (legacy / unbounded). */
    public NavGridCuboidsView cuboids() {
        return cuboids;
    }

    /**
     * The search's primary travel axis {@code P} ({@link Axes#AXIS_X}/{@link Axes#AXIS_Y}/{@link Axes#AXIS_Z})
     * — a macro-aware movement extracts a cuboid only when its own travel axis equals this, else it emits its
     * plain micro step (Option B, off-axis extraction elimination). Wired by {@link #setMacro}.
     */
    public int macroAxis() {
        return macroAxis;
    }

    /** The mode of the node being expanded — a movement gates on this (see {@link #MODE_STANDING} etc.). */
    public int mode() {
        return currentMode;
    }

    /** Set the current node's mode before its expansion (called by {@link BlockPathfinder} per popped node). */
    public void setMode(int mode) {
        this.currentMode = mode;
    }

    /** Goal X (absolute world block coord) — a macro jump never overshoots it. */
    public int goalX() {
        return goalX;
    }

    /** Goal Y (absolute world block coord). */
    public int goalY() {
        return goalY;
    }

    /** Goal Z (absolute world block coord). */
    public int goalZ() {
        return goalZ;
    }

    /** A reusable {@link Cuboid} out-param for a macro movement's {@link #cuboids()} query (no per-call alloc). */
    public Cuboid cuboidScratch() {
        return cuboidScratch;
    }

    /**
     * The per-path planned-edit diff. The search refills it from the current node's {@code cameFrom}
     * chain before expanding (so reads reflect the placed/broken blocks the moves so far made). See
     * {@link PathEdits}.
     */
    public PathEdits pathEdits() {
        return pathEdits;
    }

    /** The shared, reusable edit accumulator a movement fills while folding in breaks/places. */
    public EditScratch edits() {
        return editScratch;
    }

    public BotCaps caps() {
        return caps;
    }

    /**
     * The per-pathfind inventory feasibility snapshot a live bot supplies — read once from its REAL
     * inventory before the search (the decided cheap cap, NOT a per-node depleting budget). Carries:
     * <ul>
     *   <li><b>{@code mining}</b> — the {@link MiningModel.Snapshot}: the bot's per-tool-category best tier
     *       (so {@link #breakable} can gate on "this bot can actually mine this block") + the resident
     *       per-(navtype × tier) tick table handle stage 1d reads;</li>
     *   <li><b>{@code consumesBlocks}</b> — whether placement draws from inventory ({@code
     *       placement.consumesBlocks});</li>
     *   <li><b>{@code placeableBlocks}</b> — the snapshotted count of carried placeable blocks (the scalar
     *       throwaway budget the placement cap reads when {@code consumesBlocks}). When {@code consumesBlocks}
     *       is off this is ignored (infinite conjured supply).</li>
     *   <li><b>{@code placeRemovalPremium}</b> — the precomputed removal-premium (ticks) {@link #placeCost}
     *       adds to every placement: the block's mine-out time × the {@code placement.removalCostWeight}
     *       config weight (Steve's "cost of potentially having to mine this block out later"), so placing a
     *       hard-to-remove block (obsidian) costs more than a soft one (dirt). Computed ONCE in {@link
     *       com.orebit.mod.platform.BotInventory#feasibility} (cold) over the representative placed block, then
     *       read on the hot path as a plain field add. {@code 0} when there's no snapshot or the weight is 0.</li>
     *   <li><b>{@code placeBaseCost}</b> — the configured flat per-placement base cost (ticks) the live bot's
     *       {@code placement.placeBaseCost} knob supplies, used in place of the static {@link #PLACE_BASE_COST}
     *       default by {@link #placeCost}. A behavioral "reluctance to place" penalty, not a physical time (see
     *       {@link #PLACE_BASE_COST}); {@code >= 0}. With no snapshot the static default is used instead.</li>
     *   <li><b>{@code breakBaseCost}</b> — the configured flat surcharge (ticks) added to <b>every folded
     *       break</b> ({@code mining.breakBaseCost}, the mining-side mirror of {@code placeBaseCost}): a
     *       behavioral "reluctance to edit the world" penalty on top of the real mining ticks {@link
     *       #breakCost} charges, letting an owner discourage gratuitous digging/punching without forbidding
     *       it. {@code >= 0}, default {@code 0} (and {@code 0} with no snapshot), so the all-defaults and
     *       headless searches price breaks exactly as before.</li>
     * </ul>
     * A plain record of primitives + the (resident, read-only) {@link MiningModel.Snapshot}; passing it to
     * {@link #setInventory} costs the hot path nothing (the gates do a field load + array index).
     */
    public record InventoryView(MiningModel.Snapshot mining, boolean consumesBlocks, int placeableBlocks,
            float placeRemovalPremium, float placeBaseCost, float breakBaseCost) { }

    /**
     * Wire the per-pathfind inventory feasibility snapshot (once, after construction, before the search
     * loop) — see {@link #inventory}. Passing {@code null} leaves the gates in their historical caps-only
     * mode (headless / trace / tests). The live follower's plan path supplies one built from the bot's REAL
     * inventory via {@link com.orebit.mod.platform.BotInventory#feasibility}.
     */
    public void setInventory(InventoryView inventory) {
        this.inventory = inventory;
    }

    /** The wired inventory feasibility snapshot, or {@code null} when none was supplied (caps-only mode). */
    public InventoryView inventory() {
        return inventory;
    }

    /**
     * Whether cell {@code (x,y,z)} has built nav data — the cheap gate that keeps the search inside the
     * loaded radius (so the bot never plans into chunks it can't see). The precise checks below read
     * live geometry; this only answers "is it loaded enough to trust."
     */
    public boolean built(int x, int y, int z) {
        return grid.built(x, y, z);
    }

    /**
     * Packed {@link NavBlock} descriptor for the cell (fine geometry) — a flat read from the resident
     * navtype grid (a live block read only as a fallback outside the built area).
     */
    public long descriptorAt(int x, int y, int z) {
        if (!pathEdits.isEmpty()) {
            int kind = pathEdits.kindAt(x, y, z);
            if (kind == PathEdits.PLACED) return PLACED_DESC;
            if (kind == PathEdits.BROKEN) return AIR_DESC;
        }
        return grid.descriptorAt(x, y, z);
    }

    // ---- Read-once seam: resolve a cell's grid slot ONCE, derive flags + descriptor from it -----------
    // The movement prologue reads a candidate cell three ways today (built / flagsAt / descriptorAt — each
    // its own section resolve of the same slot). packedAt collapses those to one resolve; flagsOf and
    // descriptorOf turn the returned slot into the same facts the separate reads gave.

    /**
     * The cell's whole packed grid slot in one section resolve, or {@link #UNBUILT} if it isn't built —
     * the read-once replacement for a {@link #built} gate followed by {@link #flagsAt}/{@link
     * #descriptorAt} on the same cell. Derive flags with {@link #flagsOf} and the descriptor with {@link
     * #descriptorOf} (which still layers the path-edit diff).
     */
    public int packedAt(int x, int y, int z) {
        return grid.packedAt(x, y, z);
    }

    /** The {@link NavFlags} bitmask of a slot already read via {@link #packedAt} (caller ensures built). */
    public static int flagsOf(int packed) {
        return TraversalGrid.flagsOf(packed);
    }

    /**
     * The packed {@link NavBlock} descriptor for a slot already read via {@link #packedAt} — the read-once
     * form of {@link #descriptorAt} for a known-built cell. Layers the same path-edit diff (a placed/broken
     * cell reads as cobblestone/air) and otherwise turns the slot's navtype into its descriptor, with no
     * second section resolve and no live-block fallback ({@code packed} is already proven built).
     */
    public long descriptorOf(int x, int y, int z, int packed) {
        if (!pathEdits.isEmpty()) {
            int kind = pathEdits.kindAt(x, y, z);
            if (kind == PathEdits.PLACED) return PLACED_DESC;
            if (kind == PathEdits.BROKEN) return AIR_DESC;
        }
        return NavBlock.descriptor((short) TraversalGrid.navtypeOf(packed));
    }

    // ---- Neighbour-property flags (the precomputed NavFlags bitmask) --------------------------
    // These let a movement read multi-cell facts (body clearance, edit-hazard) in ONE grid access
    // instead of probing each cell with descriptorAt. Read the raw bitmask once per cell, decode both.

    /** {@code HEADROOM} level: ≥1 clear body cell (the cell directly above the floor is walk-clear). */
    public static final int HEADROOM_CRAWL = NavFlags.HEADROOM_CRAWL;
    /** {@code HEADROOM} level: ≥2 clear body cells (room to stand). */
    public static final int HEADROOM_WALK = NavFlags.HEADROOM_WALK;
    /** {@code HEADROOM} level: ≥3 clear body cells (room to jump up). */
    public static final int HEADROOM_JUMP = NavFlags.HEADROOM_JUMP;

    /** The raw 6-bit {@link NavFlags} bitmask at floor cell {@code (x,y,z)} (0 where unbuilt). */
    public int flagsAt(int x, int y, int z) {
        return grid.flagsAt(x, y, z);
    }

    /**
     * The E3 floorGap nibble at cell {@code (x,y,z)} ({@link TraversalGrid#floorGap}) —
     * {@link TraversalGrid#DEPTH_UNKNOWN} where unbuilt or unmaintained, in which case the caller
     * legacy-scans. The consumer ({@code Fall}) reads this through the same per-search chunk cache as the
     * flags slot it just read, so the second resolve is a cache-key compare plus an array index.
     */
    public int floorGapAt(int x, int y, int z) {
        return grid.floorGapAt(x, y, z);
    }

    /**
     * Whether the path's speculative edit diff is provably DISJOINT from the vertical column
     * {@code (x, yLo..yHi, z)} — the E3 nibble's trust gate: the floorGap memoizes COMMITTED state, so it
     * may replace a scan's reads only when no path edit can intersect the scanned column (a placed block is
     * a standable landing the nibble can't see; a broken one, a floor it wrongly reports). One empty test
     * plus at most six compares against the {@link PathEdits} bounding box — over-conservative only (a
     * false "relevant" costs the caller its legacy scan, never a wrong answer).
     */
    public boolean editsDisjointFromColumn(int x, int yLo, int yHi, int z) {
        PathEdits e = pathEdits;
        return e.isEmpty()
                || x < e.editMinX() || x > e.editMaxX()
                || z < e.editMinZ() || z > e.editMaxZ()
                || yHi < e.editMinY() || yLo > e.editMaxY();
    }

    /** Walkable clearance above the floor encoded in {@code flags} (none/crawl/walk/jump). */
    public static int headroom(int flags) {
        return NavFlags.headroom(flags);
    }

    /** Whether editing this floor's body space risks a fluid flow / gravity cascade (from {@code flags}). */
    public static boolean risksEdit(int flags) {
        return NavFlags.risksEdit(flags);
    }

    /**
     * Whether the resident HEADROOM bit <b>proves</b> floor {@code (.,y,.)} has at least {@code need}
     * walkable clearance ({@link #HEADROOM_WALK} / {@link #HEADROOM_JUMP}) — letting the caller skip the
     * per-cell {@code descriptorAt} probes entirely.
     *
     * <p>Two facts make this exact (MOVEMENT-DESIGN §8). (1) <b>The OOB bias is one-directional:</b>
     * out-of-section cells read as air, so a missing neighbour can only <i>inflate</i> the clearance
     * count, never hide a block — hence a {@code < need} reading is always trustworthy and is handled by
     * the {@code requireAir} fallback (which short-circuits on the first blocked in-section cell, so a
     * non-breaking bot rejects with no cross-section read; a breaking bot reads on to fold its breaks).
     * (2) <b>The trust threshold is per level:</b> clearance {@code N} is read from cells {@code y+1..y+N},
     * so the top cell needed is {@code y+need}; it stays inside the floor's own 16-tall section exactly
     * when {@code (y&15) + need <= 15}. So a WALK proof is exact up to {@code (y&15) <= 13} and only a JUMP
     * proof tightens to {@code <= 12} — verifying one fewer layer for the common walk case. A claims-clear
     * reading nearer the top face returns {@code false} here, so the caller verifies the real cells.
     *
     * <p>(The COLUMN build/patch path now computes near-seam bits against the section above — vertical
     * overscan, see {@code NavFlags} — so a live grid's top-row bits are honest, not just one-directional.
     * The per-level guard here is KEPT anyway: single-section producers without column context
     * ({@code classifyInto}, the neighbour-less {@code patchCell}) remain air-optimistic above, and the
     * guard costs one compare. Do not relax it while those paths exist.)
     */
    public boolean headroomProves(int flags, int y, int need) {
        return headroom(flags) >= need && (y & 15) + need <= 15;
    }

    /**
     * Ensure floor {@code (fx,fy,fz)}'s two body cells (feet + head) are clear for a walker, recording any
     * needed breaks on {@code e}. Fast path: the HEADROOM bit {@link #headroomProves proves} ≥ WALK
     * clearance in one grid read — no per-cell probes, no edits. Otherwise read the real cells via {@code
     * requireAir} (which folds breaks under {@code e}'s edit gate, or invalidates if blocked and the bot
     * can't/may-not break). {@code flags} is the cell's already-read {@link NavFlags} bitmask.
     */
    public void requireBodyClear(EditScratch e, int fx, int fy, int fz, int flags) {
        if (headroomProves(flags, fy, HEADROOM_WALK)) return;
        e.requireAir(fx, fy + 1, fz);
        e.requireAir(fx, fy + 2, fz);
    }

    /**
     * Can the bot's body occupy this cell? True only for non-colliding cells (air / plants) that hold
     * no fluid. Excludes water/lava (swimming is Tier 2) and any partial collision, so it's the
     * conservative "this cell is genuinely clear for feet or head" test the Tier 1 moves need.
     */
    public boolean passable(int x, int y, int z) {
        return passable(descriptorAt(x, y, z));
    }

    /**
     * {@link #passable(int, int, int)} on an already-read descriptor — the read-once form callers use
     * when they've already fetched the cell's descriptor (each {@code descriptorAt} still costs a section
     * lookup + array read — a live palette read outside the built grid — so re-reading the same cell
     * across predicates is wasteful).
     */
    public boolean passable(long d) {
        // Geometric "nothing collides" (the precomputed bit) AND no fluid AND not a teleport portal. The fluid
        // exclusion lives HERE, at the movement layer, not in the bit: water is geometrically passable (you can
        // float-walk it), but the WALK moves have no water cost, so a walker still treats a fluid cell as
        // non-clear. The SWIM moves use water() (below) for the cells passable() rejects for being water. The
        // portal exclusion (mirroring isSwimmableWater subtracting isBubble) keeps the bot's body — feet, head,
        // and every swept jump-arc prism cell — out of ALL teleport portals (nether/end/gateway), so the walker
        // routes AROUND them and never grazes one mid-path; the nether follower enters via a manual walk-in that
        // bypasses A*. This is the hottest predicate (read per node) — one branch-free AND on the loaded long.
        return NavBlock.isPassable(d) && NavBlock.fluid(d) == 0 && !NavBlock.isPortal(d);
    }

    /**
     * Can a running jump arc safely OVER this cell when it sits at the FLOOR level of a gap — the
     * "arced over like flat ground" rule shared by the aligned, offset and diagonal parkour scans. True
     * whenever the cell's collision top is no taller than a full block ({@code topY <= 16}): that covers
     * every {@link #passable} cell (empty shape ⇒ {@code topY == 0}) PLUS the non-passable-but-short
     * cells a sprint arc still clears — a fluid (a 1-wide lava/water pool has no collision box, {@code
     * topY == 0}; the arc keeps the hitbox above it, zero contact), décor, and full-block / slab tops
     * (jumped over like flat ground). A fence / wall (collision top ≈ 24) pokes above a full block into
     * the feet path and is NOT jumpable.
     *
     * <p>This is deliberately LOOSER than {@link #passable} in exactly one way — it admits fluids — so it
     * is correct for the floor OBSTACLE cell ONLY. The body-arc prism ({@code y+1..y+3}, the cells the
     * hitbox actually flies through) must still be proven strictly {@link #passable} by the caller, or a
     * tall lava/water column would read as jumpable and the bot would path through it and take damage.
     * The collision-first / hazard-second rule: a floor cell blocks a jump only when its collision top
     * clips the arc; a no-collision hazard (fluid, fire) is cleared by the arc and priced only on contact.
     */
    public boolean overJumpable(long d) {
        return NavBlock.topY(d) <= 16; // full-block top; a fence/wall (~24) clips the feet path
    }

    /**
     * Can the bot's body occupy this cell <b>while swimming</b> — a full water cell (no collision, holds
     * water)? The swim counterpart to {@link #passable}, which deliberately EXCLUDES fluids: surface- and
     * submerged-swim need exactly the cells {@code passable} rejects for being water. Lava (fluid 3) is
     * excluded — never swimmable. A <i>waterlogged solid</i> (a waterlogged slab/stair: water fluid
     * <b>plus</b> collision geometry) is also excluded — the bot's feet can't enter it — by the
     * {@link NavBlock#isPassable empty-shape} requirement, so this is "full water you can float in," not
     * merely "water is present." Reads the path-edit diff like the other predicates (a placed block reads
     * as cobblestone, a broken cell as air — neither is water).
     */
    public boolean water(int x, int y, int z) {
        return water(descriptorAt(x, y, z));
    }

    /** {@link #water(int, int, int)} on an already-read descriptor (read-once form). */
    public boolean water(long d) {
        // Swimmable = full water cell, no collision (not a waterlogged solid). Single source of truth in
        // NavBlock so the swim movements and the region-tier window-target agree on what "water" means.
        return NavBlock.isSwimmableWater(d);
    }

    /**
     * Whether {@code d} is a climbable block (ladder / scaffolding / the vine family — the {@link
     * NavBlock#isClimbable CLIMB} fingerprint bit) on an already-read descriptor (read-once form; the
     * climb move reads every cell exactly once via {@link #packedAt}/{@link #descriptorOf}). Note the two
     * climbable shapes diverge: ladder/scaffolding classify {@code SHAPE_OTHER} (tall non-empty collision)
     * — NOT {@link #passable}, NOT {@link #standable}, and read as <i>blocked</i> by the resident HEADROOM
     * bit — while vines are empty-shape and passable. Climb predicates must therefore never go through
     * {@link #requireBodyClear} (it would fold a break of the ladder itself); they read cells directly
     * against {@link #passableOrClimbable}.
     */
    public boolean isClimbable(long d) {
        return NavBlock.isClimbable(d);
    }

    /**
     * Body-cell test along a climb column, on an already-read descriptor (read-once form): the bot's feet
     * or head can occupy this cell while climbing — genuinely clear ({@link #passable}) <b>or</b> itself
     * climbable (the ladder/vine holds the body). The one predicate that unifies the wall-shaped
     * (ladder/scaffolding) and empty-shaped (vine) climbables.
     */
    public boolean passableOrClimbable(long d) {
        return passable(d) || NavBlock.isClimbable(d);
    }

    /**
     * Can the bot stand on top of this cell? True for any solid-topped shape (full / slab / stair /
     * layer / low partial) that isn't a fluid. Damaging floors (magma, cactus tops) ARE standable since
     * s52b — the damage is priced by {@link #floorHazardCost}, not walled off. Excludes
     * {@link NavBlock#SHAPE_OTHER} (fences/walls/panes — you don't get a clean footing on those) and
     * {@link NavBlock#SHAPE_EMPTY} (no floor at all).
     */
    public boolean standable(int x, int y, int z) {
        return standable(descriptorAt(x, y, z));
    }

    /** {@link #standable(int, int, int)} on an already-read descriptor (read-once form). */
    public boolean standable(long d) {
        return NavBlock.isStandable(d); // precomputed: solid-topped, no fluid, not damaging
    }

    /** The collision top of the cell in sixteenths (0..31); 16 = full block, 8 = slab. */
    public int topYOf(int x, int y, int z) {
        return NavBlock.topY(descriptorAt(x, y, z));
    }

    /** {@link #topYOf(int, int, int)} on an already-read descriptor (read-once form). */
    public int topYOf(long d) {
        return NavBlock.topY(d);
    }

    /** Whether an already-read descriptor is a stair — the one predictable gate the ground moves test before
     *  taking the {@link #directionalTopY} branch (the common non-stair case is this single bit compare). */
    public boolean isStair(long d) {
        return NavBlock.isStair(d);
    }

    /** True if standing on the cell incurs a slow surface (soul sand / honey / cobweb / slime). */
    public boolean isSlow(int x, int y, int z) {
        return NavBlock.surface(descriptorAt(x, y, z)) == 1; // SURFACE_SLOW
    }

    /** {@link #isSlow(int, int, int)} on an already-read descriptor (read-once form). */
    public boolean isSlow(long d) {
        return NavBlock.surface(d) == 1; // SURFACE_SLOW
    }

    // ---- Pass-through hazard + through-slow costs (per body cell transited) -------------------
    // Two per-cell g-side surcharges for PASSABLE cells the bot's body moves through, both keyed off the
    // precomputed NavFlags prefilter bits so the common (hazard-free) candidate pays ONE bit test and zero
    // extra grid reads; the exact per-cell magnitude is resolved from the two body descriptors only when a
    // prefilter bit is set (rare). Caps-honest like Fall's damage penalty: the DAMAGE term is zero for an
    // invulnerable bot; the SLOW terms apply to everyone (physics slows an immune bot just the same).

    // ---- Damage pricing: ONE currency, caps.costPerHitpoint() (ticks per HP) -----------------------
    // Each damaging-but-passable body cell transited (fire, soul fire, sweet berry bush, wither rose,
    // powder snow — lava is unreachable: never passable) is priced as 1 HP × BotCaps.costPerHitpoint()
    // (the pathing.costPerHitpoint config knob, default 100). The whole damaging set shares the flat 1-HP
    // charge (the descriptor has a single damaging bit): a berry-bush cell is over-charged relative to
    // its real ~0.5–1 damage, which is the safe direction (route around when a comparable route exists).
    //
    // TUNING / BREAK-EVEN (why the old hardcoded DAMAGE_TRANSIT_COST = 40 was replaced): the detour a
    // hazard cell can buy is  costPerHitpoint / Traverse.FLAT_COST  walk-blocks per cell (the surcharge
    // divided by the 4.633-tick per-block ruler). At 40 that was only ~8.6 blocks — fine for the
    // SINGLE-BUSH regime (one hazard cell vs a short detour: the bot routes around), but in the MAZE
    // regime (N hazard cells between bot and goal, detour length growing with N) each cell's share of the
    // detour soon exceeded its ~9-block allowance and the planner rationally plowed a berry-bush maze
    // LETHALLY — cumulative death was never priced. At the default 100 each cell buys ≈ 21.6 blocks, so a
    // handful of cells justifies a 30–80-block detour. This scalar conversion is the ratified SHORT-TERM
    // fix; the ratified successor (NOT yet built) is a cumulative health-aware damage BUDGET — a per-path
    // HP ledger measured against the bot's remaining health, so N cells price superlinearly as they
    // approach lethality — which will replace the flat per-cell term when it lands.

    /**
     * Ticks added per HEAVY through-slow body cell ({@link NavBlock#TRANSIT_HEAVY} — cobweb). A cobweb
     * cuts movement to ~5% of walk speed, so traversing one webbed block takes {@code FLAT_COST / 0.05 ≈
     * 92.7} ticks; the surcharge is that minus the base walk already charged ({@code ≈ 92.7 − 4.6 = 88},
     * i.e. {@code FLAT_COST × (1/0.05 − 1)}). Large enough (~19 walk-blocks) that the planner mines or
     * routes around a web unless it is genuinely the only way.
     */
    public static final float WEB_TRANSIT_COST =
            com.orebit.mod.pathfinding.blockpathfinder.movements.Traverse.FLAT_COST * (1f / 0.05f - 1f);

    /**
     * Ticks added per LIGHT through-slow body cell ({@link NavBlock#TRANSIT_LIGHT} — sweet berry bush,
     * powder snow, ~0.75× speed): {@code FLAT_COST × (1/0.75 − 1) ≈ 1.54} ticks. Small — a mild nudge that
     * charges an invulnerable bot too (the damage surcharge above prices the bush's pricks separately,
     * only for a mortal bot).
     */
    public static final float LIGHT_TRANSIT_COST =
            com.orebit.mod.pathfinding.blockpathfinder.movements.Traverse.FLAT_COST * (1f / 0.75f - 1f);

    /**
     * The pass-through surcharge (ticks) for the two body cells above floor {@code (fx,fy,fz)}, gated by
     * the cell's already-read {@link NavFlags} bitmask {@code flags} — the g-side price of walking a body
     * through fire / a berry bush / powder snow (damage, mortal bots only) or a cobweb / bush / powder
     * snow (through-slow, every bot). Fast path: neither prefilter bit set (or the hazard bit set on an
     * invulnerable bot with no slow bit) → {@code 0f} with ZERO grid reads — the common case for every
     * candidate everywhere. Only when a bit fires are the two body descriptors read (path-edit-aware, so a
     * planned break of the hazard clears the charge) and priced per cell via {@link #cellTransitCost}.
     *
     * <p>Boundary: the hazard/slow prefilter bits are column-local (they read only the cells straight
     * above the floor) and the nav build/patch now overscans the vertical seam (the top rows of every
     * section read the section above — see {@code NavFlags} "Boundary handling"), so the prefilter is
     * EXACT for this method: a hazard just across a section's top face IS flagged and charged. (The old
     * within-section computation left the top ~3 floor rows of every section stale-CLEAR — this zero-read
     * fast path then transited a seam-row berry-bush maze for free, lethally. Only the LATERALLY-read bits
     * — RISKY_EDIT's fluid scan, PLACEABLE_NEIGHBOR — remain air-optimistic at chunk-side faces; neither
     * is read here.) Solid damaging blocks (cactus / magma / campfire) in the body space also set the
     * hazard bit, but {@link #cellTransitCost} charges only passable cells, so a folded BREAK of such a
     * block is priced by its mining ticks alone.
     *
     * <p>The swim family deliberately does not consult this: a swimmable cell ({@link
     * NavBlock#isSwimmableWater}: water fluid + empty shape) can never be damaging — fire cannot coexist
     * with water, a waterlogged campfire is extinguished, and the solid damaging blocks fail the
     * empty-shape test — and no through-slow block is a water cell.
     */
    /**
     * Flat 1-HP contact charge for a move that ENDS standing on a damaging floor (magma, campfire —
     * standable since the s52b hazard-media change), in the ONE damage currency
     * ({@code caps.costPerHitpoint}, 0 for an immune bot). The same safe flat-HP convention as the
     * pass-through hazard charge above (magma's real cost ≈0.5 HP/cell walked with i-frames — the flat
     * 1 HP over-charge is the safe direction). Descriptor form: one bit test on a descriptor already in
     * hand (the ground moves all hold their destination floor descriptor).
     */
    public float floorHazardCost(long floorDesc) {
        return (caps.takesDamage() && NavBlock.isDamaging(floorDesc)) ? caps.costPerHitpoint() : 0f;
    }

    /** Coordinate form of {@link #floorHazardCost(long)} for callers without the floor descriptor in hand
     *  (Fall's landing). Reads the descriptor ONLY for a mortal bot — an immune bot pays zero reads. */
    public float floorHazardCost(int x, int y, int z) {
        if (!caps.takesDamage()) return 0f;
        return NavBlock.isDamaging(descriptorAt(x, y, z)) ? caps.costPerHitpoint() : 0f;
    }

    /**
     * The per-cell cost of swimming through LAVA (s52b hazard-media, owner-ratified hard-coded lava
     * adjustments — lava's magnitude derives from BEING lava, no stored data): the water swim cost ×
     * {@link #LAVA_SWIM_COST_FACTOR} (vanilla lava locomotion is far slower than water) plus
     * {@link #LAVA_HP_PER_CELL} × {@code costPerHitpoint} immersion damage for a mortal bot (≈4 HP per
     * 10-tick i-frame window over the ~23 ticks a lava cell takes, plus burn aftermath). At the default
     * 100 ticks/HP one lava cell prices ≈ 1000+ ticks (~220 walk-blocks of detour) — A* crosses lava only
     * when nothing else exists; an immune bot pays only the slow factor.
     */
    public float lavaSwimCellCost(float waterCellCost) {
        return waterCellCost * LAVA_SWIM_COST_FACTOR
                + (caps.takesDamage() ? LAVA_HP_PER_CELL * caps.costPerHitpoint() : 0f);
    }

    /** Lava locomotion slow factor vs water swimming (~0.02 vs ~0.05 travel acceleration ⇒ ~2.5×). */
    public static final float LAVA_SWIM_COST_FACTOR = 2.5f;
    /** Flat HP charged per lava cell swum by a mortal bot (immersion + burn; derivation on
     *  {@link #lavaSwimCellCost}). */
    public static final float LAVA_HP_PER_CELL = 10f;

    /** A swimmable LAVA cell ({@link NavBlock#isSwimmableLava}) — read-once descriptor form. */
    public boolean lava(long d) {
        return NavBlock.isSwimmableLava(d);
    }

    public float bodyTransitCost(int flags, int fx, int fy, int fz) {
        boolean hazard = NavFlags.clearableHazard(flags) && caps.takesDamage();
        if (!hazard && !NavFlags.slowTransit(flags)) return 0f;
        return cellTransitCost(descriptorAt(fx, fy + 1, fz))
                + cellTransitCost(descriptorAt(fx, fy + 2, fz));
    }

    /**
     * The <b>edit-folding</b> form of {@link #bodyTransitCost(int, int, int, int)} for the ground moves
     * that carry an {@link EditScratch} (Traverse / Ascend / Descend — the moves whose folded edits the
     * follower's one-shot {@code applyEdits} replays before steering the step): where transiting a
     * passable hazard / through-slow body cell <i>intact</i> would cost more than <b>punching it out</b>
     * ({@link #breakableThrough}: real mining ticks + the {@code mining.breakBaseCost} surcharge, via
     * {@link #breakCost}), a break of that cell is folded onto {@code e} and the transit charge for it is
     * dropped — the "punch the berry bush / cobweb and walk through" option. This is exact same-node
     * arbitration, not tuning: both options land the identical search node, cost is the only relaxation
     * criterion, and a broken cell reads at least as good as the intact one downstream (it becomes air in
     * the path diff), so folding the cheaper of the two per cell yields the same relaxed g as emitting
     * both candidates — without a second sink call.
     *
     * <p>Same zero-read fast path as the non-folding form: no prefilter bit (or the hazard bit on an
     * invulnerable bot with no slow bit) → {@code 0f}, no grid reads, no fold — ordinary cells pay the
     * one predictable branch and nothing else. The fold honours the scratch's {@code RISKY_EDIT} gate
     * ({@link EditScratch#editsAllowed}): a risky floor transits intact at full price, exactly as before.
     * Movements that fold no edits by design (Diagonal's corners, Fall's drop column, the airborne
     * Parkour family — nothing can usefully break mid-flight, and their executors have no break slot)
     * keep the non-folding form.
     */
    public float bodyTransitCost(EditScratch e, int flags, int fx, int fy, int fz) {
        boolean hazard = NavFlags.clearableHazard(flags) && caps.takesDamage();
        if (!hazard && !NavFlags.slowTransit(flags)) return 0f;
        return transitOrBreak(e, fx, fy + 1, fz) + transitOrBreak(e, fx, fy + 2, fz);
    }

    /**
     * One body cell of the folding transit read: charge {@link #cellTransitCost} for moving through it
     * intact, or — when the bot may break through it and that is strictly cheaper — fold the break onto
     * {@code e} (at {@link #breakCost}: real mining ticks + {@code mining.breakBaseCost}) and charge
     * nothing here (the break's own cost rides {@link EditScratch#extraCost}). Reached only when a
     * prefilter bit fired (rare); a zero-transit cell (the common case even then — one hazard cell of the
     * two) exits on the first compare with no further work.
     */
    private float transitOrBreak(EditScratch e, int x, int y, int z) {
        long d = descriptorAt(x, y, z);
        float transit = cellTransitCost(d);
        if (transit <= 0f) return 0f;
        if (e.editsAllowed() && breakableThrough(d)) {
            float breakThrough = breakCost(d);
            if (breakThrough < transit) {
                e.breakThrough(x, y, z, breakThrough);
                return 0f;
            }
        }
        return transit;
    }

    /**
     * Can the bot clear a <b>passable</b> hazard / through-slow cell by punching it out (the break-through
     * counterpart to {@link #breakable}, which deliberately requires real collision)? True only for an
     * empty-shape cell that actually charges a transit surcharge (damaging, or {@link NavBlock#transitSlow}
     * — a bush / cobweb / powder snow / fire), holds no fluid (water/lava aren't "broken"), when the bot
     * {@link BotCaps#canBreak may break}, the cell is not owner-{@link NavBlock#isProtected protected}
     * (never broken), and it is within the bot's {@link BotCaps#maxBreakHardness} cap — or is the
     * unbreakable sentinel with {@link BotCaps#allowUnbreakable} opted in (its own axis, priced at the
     * stand-in). No tool gate, matching {@link MiningModel.Snapshot#canMine}'s
     * stone-bare-hand rule: a missing tool only inflates the mining TIME ({@link #breakCost}), so the
     * cost comparison in {@link #transitOrBreak} arbitrates it (a bare-handed cobweb dig is ~400 ticks —
     * dearer than wading through — while a sword cuts it in ~20). Pure bit tests + caps field loads.
     */
    public boolean breakableThrough(long d) {
        if (!caps.canBreak()) return false;
        if (!NavBlock.isPassable(d) || NavBlock.fluid(d) != 0) return false;
        if (!NavBlock.isDamaging(d) && NavBlock.transitSlow(d) == NavBlock.TRANSIT_NONE) return false;
        // Owner-protected cells are never broken — this gate doesn't ride the BREAKABLE bit (passable
        // cells aren't "breakable geometry"), so the PROTECTED bit is tested explicitly here.
        if (NavBlock.isProtected(d)) return false;
        int h = NavBlock.hardness(d);
        // The unbreakable sentinel is its OWN axis (mining.allowUnbreakable), not ordered against the
        // maxBreakHardness cap — the stand-in breakCost is so large the transit comparison rejects it in
        // practice, but the gate stays parity-honest with the executor's grind path.
        if (h == UNBREAKABLE_HARDNESS) return caps.allowUnbreakable();
        return h <= caps.maxBreakHardness();
    }

    /**
     * The pass-through surcharge (ticks) for ONE already-read body-cell descriptor — the per-cell form for
     * movements that read the transited cells themselves ({@code Fall}'s drop column, {@code Diagonal}'s
     * corner columns), where no flags prefilter applies. {@code 1 HP ×} {@link BotCaps#costPerHitpoint}
     * for a damaging-but-passable cell when the bot {@link BotCaps#takesDamage takes damage} (the unified
     * damage currency — see the damage-pricing section comment above), plus the through-slow term ({@link
     * #WEB_TRANSIT_COST} / {@link #LIGHT_TRANSIT_COST}) for every bot. Pure bit tests on the descriptor
     * plus a caps field load — no reads, no allocation.
     */
    public float cellTransitCost(long d) {
        float c = 0f;
        if (caps.takesDamage() && NavBlock.isDamaging(d) && NavBlock.isPassable(d)) {
            c += caps.costPerHitpoint(); // 1 HP per damaging cell transited, in ticks-per-HP
        }
        int t = NavBlock.transitSlow(d);
        if (t == NavBlock.TRANSIT_HEAVY) c += WEB_TRANSIT_COST;
        else if (t == NavBlock.TRANSIT_LIGHT) c += LIGHT_TRANSIT_COST;
        return c;
    }

    // ---- Break / place (MOVEMENT-DESIGN.md §1, decision 1) ------------------------------------

    /**
     * Can the bot clear a body-blocking cell by mining it? True only when the bot {@link
     * BotCaps#canBreak may break}, the cell has real collision worth removing (not air/plant, which is
     * already passable), isn't a fluid (water/lava aren't "broken" — swim/avoid handle those), isn't
     * unbreakable (bedrock/barrier), <b>and is no harder than the bot's {@link BotCaps#maxBreakHardness}
     * mining cap</b> (the config knob: a soft-tool / no-tool bot can be limited to mining up to a given
     * hardness, while the default 255 means "mine anything breakable"). Reuses the existing {@code
     * shape}/{@code fluid}/{@code hardness} facts — no new NavBlock bit. (Tool / durability gating beyond
     * this hardness cap arrives with the inventory subsystem.)
     */
    public boolean breakable(int x, int y, int z) {
        return breakable(descriptorAt(x, y, z));
    }

    /** {@link #breakable(int, int, int)} on an already-read descriptor (read-once form). */
    public boolean breakable(long d) {
        // Precomputed geometry (solid, no fluid, not unbreakable, not owner-PROTECTED — the derived
        // BREAKABLE bit folds all four) AND the bot may break AND the block is within the bot's configured
        // mining-hardness cap. The cap is read straight off caps (a field load), so a movement still pays
        // one comparison, not a derivation. This fast path is byte-identical to the historical gate.
        if (caps.canBreak()
                && NavBlock.isBreakable(d)
                && NavBlock.hardness(d) <= caps.maxBreakHardness()) {
            // Phase 1c tool-feasibility gate: when a live bot supplied an inventory snapshot, additionally
            // require that the bot actually carries a tool able to mine this block (a tool-required block —
            // ore, obsidian — is un-minable without the correct tool category; a non-tool-required block is
            // always mineable bare-handed, only slower). The snapshot read is a field load + a couple of bit
            // extracts + one array index — no live Inventory access, hot-path safe. With no snapshot
            // (headless / trace / tests) this stays the historical caps-only gate.
            InventoryView inv = inventory;
            return inv == null || inv.mining().canMine(d);
        }
        // Slow tail (reached only where the historical gate said NO): the mining.allowUnbreakable opt-in.
        // A vanilla-unbreakable solid (hardness sentinel 255 — bedrock, barrier, portal frame) fails the
        // BREAKABLE bit above but may be ground out at the fixed stand-in cost when the owner opted in.
        // Its OWN axis: deliberately NOT subject to maxBreakHardness (the sentinel doesn't order against
        // real hardness) and NOT the tool gate (no tool mines bedrock; the stand-in is tool-independent).
        // PROTECTED always wins. With the flag off (the default) this is one predictable field-load branch
        // on the already-false path — behaviour unchanged.
        return caps.allowUnbreakable() && caps.canBreak()
                && NavBlock.hasCollision(d)
                && NavBlock.hardness(d) == UNBREAKABLE_HARDNESS
                && !NavBlock.isProtected(d);
    }

    /**
     * <b>Cold-path diagnostic only</b> (the failed-search column dump, {@code BlockPathfinder.dumpColumn}):
     * the reason {@link #breakable} is false for an already-read descriptor of a cell with collision, or
     * {@code null} when the cell is breakable (or is air/fluid — nothing to break). Maps each of the
     * {@link #breakable} gates to a short tag so a "solid wall the search won't dig through" reports WHY:
     * <ul>
     *   <li>{@code unbreakable} — quantized {@link #UNBREAKABLE_HARDNESS hardness 255} (vanilla {@code
     *       destroyTime < 0}: bedrock/barrier — or a mis-classified block reading negative destroy time);</li>
     *   <li>{@code tooHard(h=N>cap=M)} — real hardness {@code N} exceeds the bot's {@link
     *       BotCaps#maxBreakHardness} cap {@code M};</li>
     *   <li>{@code noTool} — a live inventory snapshot reports no carried tool can mine this block;</li>
     *   <li>{@code noBreakCap} — the bot {@link BotCaps#canBreak cannot break} at all.</li>
     * </ul>
     * Allocates a String (and concatenates) — acceptable because it runs only on a search FAILURE, once per
     * dumped cell, never in the search loop.
     */
    public String breakBlockedReason(long d) {
        if (!NavBlock.hasCollision(d)) return null; // air / plant / fluid: nothing to break, not a wall
        if (breakable(d)) return null;              // it IS breakable — the dump's 'k' tag already says so
        if (!caps.canBreak()) return "noBreakCap";
        if (NavBlock.isProtected(d)) return "protected"; // mining.protectedBlocks — never broken
        int h = NavBlock.hardness(d);
        if (h == UNBREAKABLE_HARDNESS) return "unbreakable"; // opt in via mining.allowUnbreakable
        if (h > caps.maxBreakHardness()) return "tooHard(h=" + h + ">cap=" + caps.maxBreakHardness() + ")";
        InventoryView inv = inventory;
        if (inv != null && !inv.mining().canMine(d)) return "noTool";
        return "?"; // gates all passed yet breakable() is false — a packing/logic slip worth seeing
    }

    /**
     * Can the bot create footing at an empty floor cell by placing a throwaway block? True only when the
     * bot {@link BotCaps#canPlace may place}, the cell is open ({@link NavBlock#isReplaceable} or genuinely
     * empty — so we don't try to place into a solid) and at least one of the SIX adjacent cells holds a
     * non-replaceable occupant to place against (the owner-verified vanilla rule, s52b). The exact
     * against-face is chosen by the follower at execution time from whatever neighbour is still solid then.
     */
    public boolean placeable(int x, int y, int z) {
        return placeable(x, y, z, descriptorAt(x, y, z));
    }

    /**
     * {@link #placeable(int, int, int)} on the cell's already-read descriptor {@code d} (read-once form;
     * the neighbour cells are still read on demand — they're distinct cells, each read once).
     */
    public boolean placeable(int x, int y, int z, long d) {
        if (!caps.canPlace()) return false;
        // Phase 1b placement-from-inventory feasibility cap: when a live bot supplied a snapshot AND
        // placement consumes inventory, the bot can only place while it still carries a throwaway block. This
        // is the cheap scalar Baritone-style cap (the snapshotted carried-block count), NOT a per-node
        // depleting budget — a rare mid-path stack-exhaustion is netted by partial-path + replan. When
        // consumesBlocks is off (the default — infinite conjured supply) or no snapshot is present (headless
        // / trace / tests), this is a no-op, so the geometry test below is unchanged from today.
        InventoryView inv = inventory;
        if (inv != null && inv.consumesBlocks() && inv.placeableBlocks() <= 0) return false;
        if (!openForPlace(d)) return false;        // need an open cell to fill (replaceable/empty — fluids count)
        // Placement support (owner-verified vanilla semantics, s52b): a solid full cube — the only thing
        // the bot ever places — can be placed against ANY ADJACENT block (all SIX faces, including the
        // ceiling above) that is not vanilla-REPLACEABLE. Not "standable" (that's a walkability question —
        // it wrongly excluded damaging floors like magma and made bridging out of a magma field
        // impossible), and not "has a solid collision shape" either (torches/grass/dripstone are all
        // legal support in vanilla despite empty collision). A replaceable neighbour (air, water, lava,
        // fire, tall grass) is the one true non-support: placing "on" it would replace IT — the wrong
        // cell. The two vanilla caveats don't apply here: the bot never places slabs (slab-merge) nor
        // support-requiring blocks (torches). REPLACE_BIT is true per-state vanilla replaceability,
        // interned at classification via the Replaceable seam — pure descriptor bit math, no
        // block-identity checks.
        return supportsPlacement(x, y - 1, z) || supportsPlacement(x, y + 1, z)
                || supportsPlacement(x + 1, y, z) || supportsPlacement(x - 1, y, z)
                || supportsPlacement(x, y, z + 1) || supportsPlacement(x, y, z - 1);
    }

    /**
     * Whether {@code d} is an open target a placed block could fill — vanilla-replaceable (which includes
     * water AND lava — placing into a fluid is valid and seals it, owner ruling s52b) or genuinely empty.
     * This is the "is the cell free" half of {@link #placeable}, split out because a
     * staircase step places a footing whose face comes from a freshly-placed <i>support</i> beneath it
     * (not from the footing's own neighbours), so {@code EditScratch} needs the open test on its own.
     */
    public boolean openForPlace(long d) {
        return NavBlock.isOpenForPlace(d); // precomputed: replaceable/empty, no fluid
    }

    /** Real mining-time cost (ticks) to fold one break of cell {@code (x,y,z)} in. */
    public float breakCost(int x, int y, int z) {
        return breakCost(descriptorAt(x, y, z));
    }

    /**
     * Real mining-time cost (ticks) to fold one break of an already-read descriptor (read-once form) — the
     * resident {@link MiningModel} table value for this block × the bot's best tool for its category, NOT a
     * magic-number stand-in (PRD §10 Phase 1d). When a live bot supplied an inventory snapshot the bot's own
     * tools set the speed; with no snapshot (headless / trace / benchmarks) it falls back to {@link
     * MiningModel#bareHandTicks bare-hand} ticks, so those searches use the same real-tick model with the
     * worst tool. Pure resident-table read (a field-key shift+mask + array index) — no per-node arithmetic,
     * no live inventory, hot-path safe (HOT-PATH-NO-ALLOC, favour-cpu-over-ram).
     *
     * <p>Every folded break additionally carries the flat {@code mining.breakBaseCost} surcharge ({@link
     * InventoryView#breakBaseCost} — the mining-side mirror of the place base cost): a behavioral
     * "reluctance to edit the world" penalty the owner can raise to discourage gratuitous digging. Default
     * {@code 0} (and {@code 0} with no snapshot), so nothing changes until the knob is set.
     *
     * <p>The callers ({@link EditScratch#requireAir}, the break-through fold) only reach here after {@link
     * #breakable}/{@link #breakableThrough} has proven the block mineable, so the table never returns
     * {@link MiningModel#UNMINEABLE} on this path.
     */
    public float breakCost(long d) {
        InventoryView inv = inventory;
        // A vanilla-unbreakable block (reachable only via the mining.allowUnbreakable arm of breakable/
        // breakableThrough) has NO physical mining time — the resident tables hold the UNMINEABLE sentinel —
        // so it is priced at the tool-derived stand-in the executor's grind actually spends (parity in time,
        // not just permission): mining.unbreakableHardness through the pickaxe formula at the bot's best
        // pickaxe tier, so a diamond pick prices cheaper than a stone one. No snapshot (headless/trace) ⇒
        // bare-hand tier. One extract + compare, only on the (rare) break-folding path.
        if (NavBlock.hardness(d) == UNBREAKABLE_HARDNESS) {
            int tier = inv != null ? inv.mining().bestTierOrdinal(NavBlock.Tool.PICKAXE.ordinal())
                                   : MiningModel.Tier.BARE.ordinal();
            return MiningModel.unbreakableTicks(tier) + (inv != null ? inv.breakBaseCost() : 0f);
        }
        return inv == null ? MiningModel.bareHandTicks(d)
                : inv.mining().ticksFor(d) + inv.breakBaseCost();
    }

    /**
     * The configured flat per-break surcharge ({@code mining.breakBaseCost}) alone — {@code 0} with no
     * snapshot. Read once per search by the {@code GoalForcedCost} dig-face probe so its premium tracks
     * {@link #breakCost}'s real per-block price while staying an admissible lower bound (real break =
     * {@code ticksFor ≥ fastestTicks}, plus this same base — mirroring how {@link #pillarPlaceCost}
     * carries the configured place base). A field load + a branch, never per node.
     */
    public float breakBaseCost() {
        InventoryView inv = inventory;
        return inv != null ? inv.breakBaseCost() : 0f;
    }

    /** Real cost (ticks) to fold one block placement at cell {@code (x,y,z)} in — tick-to-place + premium. */
    public float placeCost(int x, int y, int z) {
        return placeCost();
    }

    /**
     * Real cost (ticks) to fold one block placement in: the place-base term (the configured {@link
     * InventoryView#placeBaseCost} when a live bot supplied a snapshot, else the static {@link #PLACE_BASE_COST}
     * default for headless / trace / tests) plus a precomputed <b>removal premium</b> ({@link
     * InventoryView#placeRemovalPremium} — the placed block's mine-out time × {@code placement.removalCostWeight},
     * the cost of potentially having to mine it out later, so a hard block like obsidian is disfavoured vs. a
     * soft one like dirt) plus, when placement draws from the bot's REAL inventory ({@code
     * placement.consumesBlocks} on, carried on the snapshot), the {@link #PLACE_INVENTORY_PREMIUM} for spending
     * one of its finite blocks. With no snapshot (headless / trace / tests) the base falls back to the static
     * default, the premium is 0, and {@code consumesBlocks} is off, so those searches use the default base
     * unchanged. Every term is a precomputed SCALAR (a field load), NOT computed per node, so the formula stays
     * field loads + adds + a branch — hot-path safe. Position-independent today (a flat per-block model), but
     * exposed per-cell so a future per-block valuation can refine it without touching callers.
     */
    public float placeCost() {
        InventoryView inv = inventory;
        return (inv != null ? inv.placeBaseCost() : PLACE_BASE_COST)
                + (inv != null ? inv.placeRemovalPremium() : 0f)
                + (inv != null && inv.consumesBlocks() ? PLACE_INVENTORY_PREMIUM : 0f);
    }

    /**
     * Build-face place cost for the {@code GoalForcedCost} anti-flood heuristic: the configured/default place
     * base ({@link InventoryView#placeBaseCost} when a live bot supplied a snapshot, else the static {@link
     * #PLACE_BASE_COST}) plus the placed block's removal premium ({@link InventoryView#placeRemovalPremium}),
     * but deliberately WITHOUT the {@link #PLACE_INVENTORY_PREMIUM} term — so it stays an admissible LOWER
     * bound on the real per-block place cost {@link #placeCost} charges. The follower places the SOFTEST block
     * it carries (the one the snapshot's removal premium is measured from); running out only makes the real
     * cost higher, so under-crediting the inventory premium keeps the heuristic admissible. With no snapshot
     * (headless / trace / tests) this falls back to the static base alone, leaving those searches unchanged.
     * A precomputed SCALAR (field loads + a branch), read once per search by the probe — no per-node cost.
     */
    public float pillarPlaceCost() {
        InventoryView inv = inventory;
        return (inv != null ? inv.placeBaseCost() : PLACE_BASE_COST)
                + (inv != null ? inv.placeRemovalPremium() : 0f);
    }

    /** Whether a cell can SUPPORT a solid-cube placement on/against it: any occupant that is not
     *  vanilla-replaceable (owner-verified rule — includes torches, grass, dripstone, slabs, magma;
     *  excludes air, fluids, fire, plants). An unbuilt cell reads AIR → replaceable → no support. */
    private boolean supportsPlacement(int x, int y, int z) {
        return !NavBlock.isReplaceable(descriptorAt(x, y, z));
    }
}
