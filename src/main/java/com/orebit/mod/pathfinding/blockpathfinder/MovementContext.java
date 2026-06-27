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
     * </ul>
     * A plain record of primitives + the (resident, read-only) {@link MiningModel.Snapshot}; passing it to
     * {@link #setInventory} costs the hot path nothing (the gates do a field load + array index).
     */
    public record InventoryView(MiningModel.Snapshot mining, boolean consumesBlocks, int placeableBlocks,
            float placeRemovalPremium, float placeBaseCost) { }

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
        // but the WALK moves have no water cost, so a walker still treats a fluid cell as non-clear. The
        // SWIM moves use water() (below) for the cells passable() rejects for being water.
        return NavBlock.isPassable(d) && NavBlock.fluid(d) == 0;
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
        // FLUID_WATER == 1 (low fluid bit set, high/lava bit clear) AND no collision (empty shape) — a full
        // water cell the bot can float in, not a waterlogged solid.
        return NavBlock.fluid(d) == 1 && NavBlock.isPassable(d);
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
        // Precomputed geometry (solid, no fluid, not unbreakable) AND the bot may break AND the block is
        // within the bot's configured mining-hardness cap. The cap is read straight off caps (a field
        // load), so a movement still pays one comparison, not a derivation.
        if (!(caps.canBreak()
                && NavBlock.isBreakable(d)
                && NavBlock.hardness(d) <= caps.maxBreakHardness())) {
            return false;
        }
        // Phase 1c tool-feasibility gate: when a live bot supplied an inventory snapshot, additionally
        // require that the bot actually carries a tool able to mine this block (a tool-required block — ore,
        // obsidian — is un-minable without the correct tool category; a non-tool-required block is always
        // mineable bare-handed, only slower). The snapshot read is a field load + a couple of bit extracts +
        // one array index — no live Inventory access, hot-path safe. With no snapshot (headless / trace /
        // tests) this stays the historical caps-only gate, so nothing changes until a bot supplies one.
        InventoryView inv = inventory;
        return inv == null || inv.mining().canMine(d);
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
        int h = NavBlock.hardness(d);
        if (h == UNBREAKABLE_HARDNESS) return "unbreakable";
        if (h > caps.maxBreakHardness()) return "tooHard(h=" + h + ">cap=" + caps.maxBreakHardness() + ")";
        InventoryView inv = inventory;
        if (inv != null && !inv.mining().canMine(d)) return "noTool";
        return "?"; // gates all passed yet breakable() is false — a packing/logic slip worth seeing
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
        // Phase 1b placement-from-inventory feasibility cap: when a live bot supplied a snapshot AND
        // placement consumes inventory, the bot can only place while it still carries a throwaway block. This
        // is the cheap scalar Baritone-style cap (the snapshotted carried-block count), NOT a per-node
        // depleting budget — a rare mid-path stack-exhaustion is netted by partial-path + replan. When
        // consumesBlocks is off (the default — infinite conjured supply) or no snapshot is present (headless
        // / trace / tests), this is a no-op, so the geometry test below is unchanged from today.
        InventoryView inv = inventory;
        if (inv != null && inv.consumesBlocks() && inv.placeableBlocks() <= 0) return false;
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
     * <p>The caller ({@link EditScratch#requireAir}) only reaches here after {@link #breakable} has proven the
     * block mineable, so the table never returns {@link MiningModel#UNMINEABLE} on this path.
     */
    public float breakCost(long d) {
        InventoryView inv = inventory;
        return inv == null ? MiningModel.bareHandTicks(d) : inv.mining().ticksFor(d);
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

    /** Whether a cell has any solid collision (a face to build against) — full block, slab, stair, … */
    private boolean hasSolidCollision(int x, int y, int z) {
        return NavBlock.hasCollision(descriptorAt(x, y, z)); // precomputed: solid, no fluid
    }
}
