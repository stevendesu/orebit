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

    /**
     * A floor cell whose collision top is at or below this many sixteenths is a <b>low step</b> — a
     * slab / single snow layer / stair lip the player auto-steps onto (~0.6 blocks) without jumping.
     * Above it, gaining the cell needs a real jump (Ascend). 10/16 ≈ 0.625, just past the auto-step.
     */
    public static final int STEP_ASSIST_MAX_TOP_Y = 10;

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

    // ---- Break / place cost model (tick-relative, Baritone-seedable) --------------------------
    /** Flat cost of folding one break into a move, before the hardness term. */
    public static final float BREAK_BASE_COST = 2.0f;
    /**
     * Added per quantized-hardness unit (≈ {@code destroyTime*5}) of the broken block, so soft leaves
     * (hardness 1) barely cost more than a step while stone (≈8) is a real detour-or-dig trade-off. A
     * coarse stand-in for true mining ticks until tool/haste-aware timings land.
     */
    public static final float BREAK_PER_HARDNESS = 0.25f;
    /** Flat cost of folding one block placement (bridge / footing) into a move. */
    public static final float PLACE_COST = 3.0f;

    /** Geometry a path-placed block reads as — the cobblestone the follower actually places (full cube). */
    private static final long PLACED_DESC = NavBlock.descriptorFor(Blocks.COBBLESTONE.defaultBlockState());
    /** Geometry a path-broken cell reads as — air. */
    private static final long AIR_DESC = NavBlock.descriptor(NavBlock.AIR);

    private final NavGridView grid;
    private final BotCaps caps;
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
     * a movement travelling any other axis takes its plain micro step (Option B, {@code CUBOID-PERF-OPTIONS.md}).
     * This pins per-node extraction to ONE axis instead of up to three (Pillar/MineDown → Y, the Traverse
     * cardinals → X and Z), so a uniform region is extracted once per search, not once per axis. Defaults to
     * {@link Axes#AXIS_X}; meaningful only when {@link #cuboids} is non-null.
     */
    private int macroAxis = Axes.AXIS_X;
    /** A reusable {@link Cuboid} a macro movement fills via {@link #cuboids()} — no per-candidate allocation. */
    private final Cuboid cuboidScratch = new Cuboid();

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

    /** {@code HEADROOM} level: ≥2 clear body cells (room to stand). */
    public static final int HEADROOM_WALK = NavFlags.HEADROOM_WALK;
    /** {@code HEADROOM} level: ≥3 clear body cells (room to jump up). */
    public static final int HEADROOM_JUMP = NavFlags.HEADROOM_JUMP;

    /** The raw 6-bit {@link NavFlags} bitmask at floor cell {@code (x,y,z)} (0 where unbuilt). */
    public int flagsAt(int x, int y, int z) {
        return grid.flagsAt(x, y, z);
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
        // Geometric "nothing collides" (the precomputed bit) AND no fluid. The fluid exclusion lives HERE,
        // at the movement layer, not in the bit: water is geometrically passable (you can float-walk it),
        // but Tier-1 has no swim/water cost yet, so we keep treating a fluid cell as non-clear for now.
        return NavBlock.isPassable(d) && NavBlock.fluid(d) == 0;
    }

    /**
     * Can the bot stand on top of this cell? True for any solid-topped shape (full / slab / stair /
     * layer / low partial) that isn't a fluid and isn't damaging (lava, magma, cactus, fire). Excludes
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

    /** True if standing on the cell incurs a slow surface (soul sand / honey / cobweb / slime). */
    public boolean isSlow(int x, int y, int z) {
        return NavBlock.surface(descriptorAt(x, y, z)) == 1; // SURFACE_SLOW
    }

    /** {@link #isSlow(int, int, int)} on an already-read descriptor (read-once form). */
    public boolean isSlow(long d) {
        return NavBlock.surface(d) == 1; // SURFACE_SLOW
    }

    // ---- Break / place (MOVEMENT-DESIGN.md §1, decision 1) ------------------------------------

    /**
     * Can the bot clear a body-blocking cell by mining it? True only when the bot {@link
     * BotCaps#canBreak may break}, the cell has real collision worth removing (not air/plant, which is
     * already passable), isn't a fluid (water/lava aren't "broken" — swim/avoid handle those) and isn't
     * unbreakable (bedrock/barrier). Reuses the existing {@code shape}/{@code fluid}/{@code hardness}
     * facts — no new NavBlock bit. (First-cut: any breakable block is assumed tool-satisfiable; tool /
     * durability gating arrives with the inventory subsystem.)
     */
    public boolean breakable(int x, int y, int z) {
        return breakable(descriptorAt(x, y, z));
    }

    /** {@link #breakable(int, int, int)} on an already-read descriptor (read-once form). */
    public boolean breakable(long d) {
        // Precomputed geometry (solid, no fluid, not unbreakable) AND the bot may break.
        return caps.canBreak() && NavBlock.isBreakable(d);
    }

    /**
     * Can the bot create footing at an empty floor cell by placing a throwaway block? True only when the
     * bot {@link BotCaps#canPlace may place}, the cell is open ({@link NavBlock#isReplaceable} or genuinely
     * empty — so we don't try to place into a solid) and at least one orthogonal/below neighbour offers a
     * sturdy face to place the block against (so the placement is physically valid). Reuses {@code
     * replaceable}/{@code faces} — no new bit. The exact against-face is chosen by the follower at
     * execution time from whatever neighbour is still solid then.
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
        if (!openForPlace(d)) return false;        // need a clear, non-fluid cell to fill
        // A sturdy neighbour to place against: the four sides, plus the block below.
        return standable(x, y - 1, z)
                || hasSolidCollision(x + 1, y, z) || hasSolidCollision(x - 1, y, z)
                || hasSolidCollision(x, y, z + 1) || hasSolidCollision(x, y, z - 1);
    }

    /**
     * Whether {@code d} is an open target a placed block could fill — replaceable or genuinely empty, and
     * holding no fluid. This is the "is the cell free" half of {@link #placeable}, split out because a
     * staircase step places a footing whose face comes from a freshly-placed <i>support</i> beneath it
     * (not from the footing's own neighbours), so {@code EditScratch} needs the open test on its own.
     */
    public boolean openForPlace(long d) {
        return NavBlock.isOpenForPlace(d); // precomputed: replaceable/empty, no fluid
    }

    /** Tick cost to fold one break of cell {@code (x,y,z)} in — flat base plus a hardness term. */
    public float breakCost(int x, int y, int z) {
        return breakCost(descriptorAt(x, y, z));
    }

    /** {@link #breakCost(int, int, int)} on an already-read descriptor (read-once form). */
    public float breakCost(long d) {
        return BREAK_BASE_COST + NavBlock.hardness(d) * BREAK_PER_HARDNESS;
    }

    /** Whether a cell has any solid collision (a face to build against) — full block, slab, stair, … */
    private boolean hasSolidCollision(int x, int y, int z) {
        return NavBlock.hasCollision(descriptorAt(x, y, z)); // precomputed: solid, no fluid
    }
}
