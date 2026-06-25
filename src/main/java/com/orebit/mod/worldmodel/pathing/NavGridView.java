package com.orebit.mod.worldmodel.pathing;

import java.util.concurrent.ConcurrentHashMap;

import com.orebit.mod.platform.LevelBounds;
import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Read view over the world model for one level — the seam the pathfinder sits on. It serves three reads:
 *
 * <ul>
 *   <li><b>Built gate:</b> {@link #built} — whether a cell's chunk/section nav data is currently built.
 *       The cheap "is it loaded enough to trust" prefilter that keeps the search inside the loaded radius
 *       (replaces the old {@code classAt != null}; the 4-value class it returned was dead).
 *   <li><b>Neighbour flags:</b> {@link #flagsAt} — the precomputed {@link NavFlags} bitmask (headroom,
 *       edit-hazard, walk-through hazard, placeable-neighbour) from the high 6 bits of the resident grid.
 *   <li><b>Fine geometry:</b> {@link #descriptorAt} — the full {@link NavBlock} geometry for a cell. The
 *       grid stores the resolved navtype per cell (the low 10 bits of the same packed {@code short} — see
 *       {@link TraversalGrid}), so this is a flat masked array read plus one descriptor-table index — no
 *       live {@code getBlockState} palette walk, no navtype-map lookup. (Favour-cpu-over-ram: the
 *       movement layer reads geometry constantly during A*, so the navtype is kept resident rather than
 *       re-derived.) Cells <i>outside</i> the built grid fall back to a live read so a probe just past the
 *       loaded radius still returns real geometry.
 * </ul>
 *
 * <p>The nav grid is stored per-16³ {@link NavSection} (one array per chunk), but a path spans
 * sections and chunks, so a lookup finds the right chunk's section array, the section within it, and the
 * cell within that. Bound to a level and its min-Y (read once), so a lookup is a couple of shifts plus a
 * map get. {@link #built} returns {@code false} where that chunk's nav data isn't built (unloaded radius
 * / out of vertical bounds) — the pathfinder treats that as unknown.
 *
 * <p><b>Freshness:</b> because both reads come from the stored grid (not the live world), runtime block
 * edits — including the bot's own break/place — are reflected via the {@code LevelChunk.setBlockState}
 * mixin ({@link com.orebit.mod.platform.BlockChangeEvents} → {@link NavGridUpdater}), which patches the
 * affected cell of every tracked section as it changes. So a replan reads current terrain with no
 * per-replan rebuild (the old {@code refreshNavData} shim is retired).
 */
public final class NavGridView {

    /**
     * Sentinel returned by {@link #packedAt} for a cell whose chunk/section nav data isn't built — the
     * built gate folded into the packed value (a real slot is 0..65535, so {@code -1} can't collide). A
     * caller treats it exactly as {@code !built}: skip the cell (never derive flags/navtype from it, and
     * never path into it). This keeps the gate that {@link #descriptorAt}'s live-{@code getBlockState}
     * fallback rests on without paying a second section resolve.
     */
    public static final int UNBUILT = -1;

    private final ServerLevel level;
    private final int minY;
    // Reused for the on-demand geometry reads; safe because a view is used single-threaded per pathfind.
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    // The per-level chunk store, resolved once (a pathfind never spans a level), so a per-cell read skips
    // the BY_LEVEL.get(level) hop. {@code null} until the level has any nav data — then built() is always
    // false (no ground to plan over), which is the correct answer.
    private final ConcurrentHashMap<Long, NavSection[]> chunks;
    // Last-chunk cache: a node's ~100 cell reads cluster in one or two chunks, so caching the resolved
    // section array by chunk key turns those ~100 ConcurrentHashMap lookups into ~100 plain array reads.
    // Safe because the view is single-threaded per pathfind and the store doesn't mutate mid-search (all on
    // the tick thread). This single slot only catches CONSECUTIVE same-chunk reads, though — a flood that
    // weaves across chunk boundaries thousands of times missed it every crossing, and {@code chunks.get}
    // takes {@code Object}, so each miss BOXED the primitive long key onto the heap (the same boxing the
    // custom open-addressed maps elsewhere were built to avoid — docs/Optimizations/custom_hash_map.md).
    private long cacheChunkKey = Long.MIN_VALUE; // sentinel: nothing cached yet
    private NavSection[] cacheSections;          // sections for cacheChunkKey (null = that chunk isn't built)

    // Behind the single slot: a per-search open-addressed long→sections cache, so a chunk key is boxed at
    // most ONCE per distinct chunk (its cold miss) and every later crossing back to it is a primitive array
    // read — no boxing, no ConcurrentHashMap.get. Sized well above the distinct-chunk count any search
    // within MAX_EXPANSIONS touches (a 10k-node flood spans ~tens of chunks), so it never fills; the view is
    // per-pathfind, so it starts empty with no clearing. (When a time-based budget raises the node ceiling,
    // grow CC_CAP or make it resize.)
    private static final int CC_CAP = 512;
    private static final int CC_MASK = CC_CAP - 1;
    private static final NavSection[] CC_UNBUILT = new NavSection[0]; // negative-cache sentinel: chunk not built
    private final long[] ccKeys = new long[CC_CAP];
    private final NavSection[][] ccVals = new NavSection[CC_CAP][]; // null slot = cold (never looked up)

    public NavGridView(ServerLevel level) {
        this.level = level;
        this.minY = LevelBounds.minY(level);
        this.chunks = NavStore.chunksOf(level);
    }

    /**
     * Test / benchmark seam: a view over a synthetic, already-built section map with <b>no live level</b>.
     * Lets a headless JMH benchmark or determinism test (PRD §11) drive the pathfinder over a hand-built
     * grid without standing up a {@link ServerLevel}. Because {@code level} is {@code null}, {@link
     * #descriptorAt}'s live-{@code getBlockState} fallback must NEVER fire — the caller must keep every cell
     * the search can probe inside the built map (which is the realistic case anyway: the pathfinder only
     * plans over loaded terrain). Package-private so it isn't part of the public runtime surface.
     */
    NavGridView(int minY, ConcurrentHashMap<Long, NavSection[]> chunks) {
        this.level = null;
        this.minY = minY;
        this.chunks = chunks;
    }

    /**
     * Public seam for the HPA* leaf-cost computer (PRD §6.3–6.5; HPA-IMPLEMENTATION.md §1, §5): a view
     * over a synthetic, already-built section map with <b>no live level</b>, built by
     * {@link com.orebit.mod.worldmodel.hpa.LeafCostComputer} so a bounded one-section block-A* mini-pathfind
     * can run without standing up a {@link ServerLevel}. Delegates to the package-private test/benchmark
     * ctor — the same no-live-level contract applies: because {@code level} is {@code null},
     * {@link #descriptorAt}'s live-{@code getBlockState} fallback must never fire, so the caller keeps every
     * cell the search can probe inside the supplied built map.
     */
    public static NavGridView overSections(int minY, ConcurrentHashMap<Long, NavSection[]> chunks) {
        return new NavGridView(minY, chunks);
    }

    /**
     * Whether world cell {@code (x,y,z)} has built nav data — {@code false} if that chunk's nav data
     * isn't currently built (unloaded radius) or {@code y} is out of the level's vertical range. The
     * cheap gate the pathfinder uses to stay inside the loaded radius.
     */
    public boolean built(int x, int y, int z) {
        return sectionAt(x, y, z) != null;
    }

    /**
     * The precomputed {@link NavFlags} neighbour-property bitmask at world cell {@code (x,y,z)} (high 6
     * bits of the resident grid), or {@code 0} where that chunk's nav data isn't built — gate with
     * {@link #built} first if "unbuilt vs. genuinely flagless" matters.
     */
    public int flagsAt(int x, int y, int z) {
        NavSection section = sectionAt(x, y, z);
        return section == null ? 0 : section.getFlags(x & 15, y & 15, z & 15);
    }

    /**
     * The whole packed grid slot at world cell {@code (x,y,z)} — flags <i>and</i> navtype in a single
     * section resolve — or {@link #UNBUILT} where that chunk's nav data isn't built. The read-once seam
     * for the movement prologue: instead of a {@link #built} gate plus a {@link #flagsAt} plus a {@link
     * #descriptorAt} (three section resolves of the same cell), the caller resolves the slot once here and
     * derives built-ness ({@code != UNBUILT}), the flags ({@link TraversalGrid#flagsOf}) and the navtype/
     * descriptor ({@link TraversalGrid#navtypeOf} → {@link NavBlock#descriptor}) from it. Unlike {@link
     * #descriptorAt} there is no live-block fallback (the {@code UNBUILT} sentinel <i>is</i> the gate), so
     * a probe past the loaded radius is reported as unbuilt rather than read live.
     */
    public int packedAt(int x, int y, int z) {
        NavSection section = sectionAt(x, y, z);
        return section == null ? UNBUILT : section.getPacked(x & 15, y & 15, z & 15);
    }

    /**
     * The packed {@link NavBlock} descriptor (full geometry: shape, faces, fluid, hardness, …) for
     * world cell {@code (x,y,z)}. For a built cell this is the resident navtype turned into its
     * descriptor — a flat array read, no live block lookup. This is the fine-movement seam — the
     * movement layer reads it to decide jump clearance, stair half-steps, parkour gaps, swim, etc.
     * (the neighbour flags are a prefilter; this descriptor is the precise per-cell geometry).
     * Outside the built grid it falls back to a live read so a probe just past the loaded radius still
     * returns real geometry.
     */
    public long descriptorAt(int x, int y, int z) {
        NavSection section = sectionAt(x, y, z);
        if (section != null) {
            return NavBlock.descriptor((short) section.getNavtype(x & 15, y & 15, z & 15));
        }
        // Synthetic/bounded view (no live world — the HPA* leaf-cost mini-pathfind over a single section, or
        // a headless benchmark): there is no level to fall back to, so report AIR for any out-of-built probe.
        // A movement can read a cell just outside the bounded section WITHOUT a built()/packedAt() gate (e.g.
        // Ascend's place-footing collision check), and live-reading a null level NPEs the server tick. AIR is
        // the safe total answer: out-of-built cells are already !built/UNBUILT for the gated reads, and air is
        // passable-but-not-standable, so it keeps the search walled into the built region rather than crashing.
        if (level == null) {
            return NavBlock.descriptor(NavBlock.AIR);
        }
        BlockState state = level.getBlockState(cursor.set(x, y, z));
        return NavBlock.descriptorFor(state);
    }

    /** The {@link NavSection} covering world cell {@code (x,y,z)}, or {@code null} if it isn't built. */
    private NavSection sectionAt(int x, int y, int z) {
        int sectionIndex = (y - minY) >> 4;
        if (sectionIndex < 0) return null;
        long chunkKey = NavStore.key(x >> 4, z >> 4);
        NavSection[] sections;
        if (chunkKey == cacheChunkKey) {
            sections = cacheSections;
        } else {
            sections = lookupChunk(chunkKey);
            cacheChunkKey = chunkKey;
            cacheSections = sections;
        }
        if (sections == null || sectionIndex >= sections.length) return null;
        return sections[sectionIndex];
    }

    /**
     * Resolve a chunk's sections through the per-search cache: a primitive-keyed open-addressed lookup that
     * boxes the long key (one {@code chunks.get}) only on a chunk's first touch, then serves every later
     * crossing back to it from {@link #ccKeys}/{@link #ccVals} with no allocation. {@code null} is a real
     * cached value (chunk unbuilt), distinguished from an empty slot by the {@link #CC_UNBUILT} sentinel.
     */
    private NavSection[] lookupChunk(long key) {
        int slot = chunkSlot(key);
        // Bound the probe to the cache capacity. Normally a slot is found long before this (the cache is sized
        // well above the distinct-chunk count of a single bounded search). But a saturated cache — a single
        // unbounded search touching > CC_CAP distinct chunks (a very long open-ground walk), or a view reused
        // across many searches — would otherwise loop forever here: every slot is full and the key isn't
        // present. On saturation, fall back to a direct (boxing) store lookup: slow-but-correct, never a hang.
        // (Production builds a fresh view per pathfind and the HPA* corridor bound keeps a windowed search to a
        // handful of chunks, so this fallback should not fire on the live path.)
        for (int probes = 0; probes < CC_CAP; probes++) {
            NavSection[] v = ccVals[slot];
            if (v == null) { // cold slot — box once, resolve from the store, and cache (even a null result)
                NavSection[] sections = chunks == null ? null : chunks.get(key);
                ccKeys[slot] = key;
                ccVals[slot] = sections == null ? CC_UNBUILT : sections;
                return sections;
            }
            if (ccKeys[slot] == key) return v == CC_UNBUILT ? null : v;
            slot = (slot + 1) & CC_MASK;
        }
        return chunks == null ? null : chunks.get(key); // cache saturated — degrade to a direct lookup
    }

    /** Murmur3 64-bit finalizer → cache slot; spreads the structured chunk keys so probe chains stay short. */
    private static int chunkSlot(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return (int) k & CC_MASK;
    }
}
