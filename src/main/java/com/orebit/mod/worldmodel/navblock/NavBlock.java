package com.orebit.mod.worldmodel.navblock;

import java.util.HashMap;
import java.util.Map;

import com.orebit.mod.OrebitCommon;
import com.orebit.mod.platform.BlockKinds;
import com.orebit.mod.platform.BlockLookup;
import com.orebit.mod.platform.MineableTags;
import com.orebit.mod.platform.Replaceable;

import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Per-{@link BlockState} navigation fingerprint, stored as a packed 64-bit {@code long}.
 *
 * <h2>The model (PRD §6.1, decision #1)</h2>
 * Identity is <b>per-BlockState, not per-Block</b>: a north-facing stair and a south-facing
 * stair navigate differently, and that difference is intrinsic to the state (different solid
 * faces, different shape), so each state computes its own fingerprint and behaviourally-equal
 * states dedup losslessly. Because the fingerprint is a single packed {@code long}, the
 * descriptor table maps a {@code short} <b>navtype</b> index → that {@code long}; one array read
 * plus bit-extraction yields every field, with no objects and no pointer chasing on the hot path.
 *
 * <p><b>The packed {@code long} is simultaneously the dedup key and the descriptor</b> — two
 * states that pack to the same bits are the same navtype. Measured (profile.BlockStateDedupTest):
 * ~28k states collapse to a few hundred navtypes (≪ 65,536, so a {@code short} index fits with
 * ~100× headroom; the table is a few KB, L1-resident).
 *
 * <h2>What changed from the deprecated byte scheme</h2>
 * The old design keyed per-Block and packed a {@code (mode<<6)|counter} <b>byte</b> index, which
 * overflowed on several versions ("Too many blocks registered for mode 3") and forced lossy
 * lumping. It also bolted block <i>facing</i> onto the index via {@code registerDirection} —
 * needed only because identity was per-Block. Per-BlockState identity makes facing fall out of
 * the geometry fields for free, so {@code directional} and the direction machinery are gone.
 * Light emission is <b>not</b> stored here (it has up to 16 values per otherwise-identical block,
 * would inflate the navtype count ~15×, and does not affect traversal — it belongs in region
 * metadata for target selection). "Currently waterlogged" is not a separate field: a waterlogged
 * state reports water from {@link BlockState#getFluidState()}, so it is captured by the fluid field.
 *
 * <h2>Bit layout (37 bits used of 64; bits 37–63 reserved for future fields)</h2>
 * <pre>
 *   bits  width field
 *   0–4     5   topY        collision top surface, round(maxY*16), clamped 0..31
 *   5–7     3   shape       ShapeClass ordinal (EMPTY..OTHER) — see {@link #SHAPE_FULL} etc.
 *   8–13    6   faces       solid (sturdy) faces, one bit per Direction.ordinal()
 *   14–15   2   openable    0 none / 1 door / 2 trapdoor / 3 fence-gate
 *   16–17   2   fluid       0 none / 1 water / 2 lava (water includes waterlogged)
 *   18–19   2   surface     0 none / 1 slow / 2 slippery
 *   20      1   climbable
 *   21      1   gravity     falling block (sand/gravel/concrete-powder)
 *   22      1   damaging
 *   23      1   replaceable
 *   24–31   8   hardness    255 = unbreakable, else min(254, round(destroyTime*5))
 *   32–34   3   tool        Tool ordinal (NONE..SHEARS)
 *   35      1   toolRequired
 *   36      1   waterloggable (static: has the WATERLOGGED property — bucket-clutch fails)
 * </pre>
 */
public final class NavBlock {

    private NavBlock() {}

    // ---- ShapeClass ordinals (3 bits) --------------------------------------------------------
    /** No collision (air, plants, fluids) — passable. */
    public static final int SHAPE_EMPTY       = 0;
    /** Full cube — wall / floor. */
    public static final int SHAPE_FULL        = 1;
    /** Solid lower half (bottom slab) — walk-on step-up, no jump. */
    public static final int SHAPE_SLAB_BOTTOM = 2;
    /** Solid upper half (top slab) — stand at 1.0; eats headroom of the cell below. */
    public static final int SHAPE_SLAB_TOP    = 3;
    /** Stairs — half-step approach on the open side, full block on the back; facing via faces. */
    public static final int SHAPE_STAIR       = 4;
    /** Variable-height layer (snow/carpet) — step-up if {@code topY} small. */
    public static final int SHAPE_LAYER       = 5;
    /** Misc partial collision with top ≤ 0.5 — walk-on step-up. */
    public static final int SHAPE_PARTIAL_LOW = 6;
    /** Anything else (fence/wall/pane, oversized or irregular) — treat as obstacle. */
    public static final int SHAPE_OTHER       = 7;

    // ---- Tool (3 bits) -----------------------------------------------------------------------
    /** Best tool for breaking; ordinal is stored in the descriptor. */
    public enum Tool { NONE, PICKAXE, AXE, SHOVEL, HOE, SWORD, SHEARS }

    private static final int FLUID_NONE = 0, FLUID_WATER = 1, FLUID_LAVA = 2;
    private static final int SURFACE_NONE = 0, SURFACE_SLOW = 1, SURFACE_SLIPPERY = 2;
    private static final int OPEN_NONE = 0, OPEN_DOOR = 1, OPEN_TRAPDOOR = 2, OPEN_GATE = 3;

    // ---- Bit field shifts/masks --------------------------------------------------------------
    private static final int TOP_Y_SHIFT = 0,  TOP_Y_MASK = 0x1F;
    private static final int SHAPE_SHIFT = 5,  SHAPE_MASK = 0x07;
    private static final int FACES_SHIFT = 8,  FACES_MASK = 0x3F;
    private static final int OPEN_SHIFT  = 14, OPEN_MASK  = 0x03;
    private static final int FLUID_SHIFT = 16, FLUID_MASK = 0x03;
    private static final int SURF_SHIFT  = 18, SURF_MASK  = 0x03;
    private static final int CLIMB_BIT   = 1 << 20;
    private static final int GRAVITY_BIT = 1 << 21;
    private static final int DAMAGE_BIT  = 1 << 22;
    private static final int REPLACE_BIT = 1 << 23;
    private static final int HARD_SHIFT  = 24, HARD_MASK  = 0xFF;
    private static final int TOOL_SHIFT  = 32, TOOL_MASK  = 0x07;
    private static final long TOOLREQ_BIT  = 1L << 35;
    private static final long WLOGABLE_BIT = 1L << 36;

    // ---- The tables --------------------------------------------------------------------------
    // descriptor (packed long) -> navtype index, for lossless dedup at build time.
    private static final Map<Long, Short> DESCRIPTOR_TO_NAVTYPE = new HashMap<>();
    // navtype index -> descriptor (the table consulted at runtime). Grown during init, then frozen.
    private static long[] descriptors = new long[1024];
    private static int navtypeCount = 0;
    // BlockState -> navtype index. Built once at init; consulted per palette entry (not per block).
    private static final Map<BlockState, Short> STATE_TO_NAVTYPE = new HashMap<>();

    /** A conservative "solid full cube" descriptor used when a block's geometry query throws. */
    private static final long SOLID_FALLBACK =
            ((long) 16 << TOP_Y_SHIFT)
            | ((long) SHAPE_FULL << SHAPE_SHIFT)
            | ((long) FACES_MASK << FACES_SHIFT)
            | ((long) 8 << HARD_SHIFT)            // ~1.5 hardness
            | ((long) Tool.PICKAXE.ordinal() << TOOL_SHIFT);

    /** Navtype 0 is always air (a zeroed grid/palette defaults to passable air). */
    public static final short AIR;

    private static int errorCount = 0;

    static {
        // Air first so it is navtype 0. CAVE_AIR / VOID_AIR share its fingerprint and dedup to it.
        AIR = intern(fingerprint(Blocks.AIR, Blocks.AIR.defaultBlockState()));
        STATE_TO_NAVTYPE.put(Blocks.AIR.defaultBlockState(), AIR);

        BlockLookup.forEachBlock(block -> {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                if (STATE_TO_NAVTYPE.containsKey(state)) continue; // air already done
                short navtype;
                try {
                    navtype = intern(fingerprint(block, state));
                } catch (Throwable t) {
                    // A misbehaving (often modded) block whose collision/face query throws with a
                    // null world/pos: treat it as a solid full-cube obstacle rather than crash init.
                    errorCount++;
                    navtype = intern(SOLID_FALLBACK);
                }
                STATE_TO_NAVTYPE.put(state, navtype);
            }
        });

        OrebitCommon.LOGGER.info("[Orebit] NavBlock: {} states -> {} navtypes ({} B table, {} errors)",
                STATE_TO_NAVTYPE.size(), navtypeCount, navtypeCount * 8, errorCount);
    }

    // ---- Build-time fingerprinting -----------------------------------------------------------

    /** Intern a descriptor, returning its (existing or freshly assigned) navtype index. */
    private static short intern(long descriptor) {
        Short existing = DESCRIPTOR_TO_NAVTYPE.get(descriptor);
        if (existing != null) return existing;
        if (navtypeCount == descriptors.length) {
            long[] grown = new long[descriptors.length * 2];
            System.arraycopy(descriptors, 0, grown, 0, descriptors.length);
            descriptors = grown;
        }
        short navtype = (short) navtypeCount;
        descriptors[navtypeCount++] = descriptor;
        DESCRIPTOR_TO_NAVTYPE.put(descriptor, navtype);
        return navtype;
    }

    /** Compute the packed descriptor for one block state. */
    private static long fingerprint(Block block, BlockState state) {
        // Block-entity blocks, bamboo stalks and dripstone can NPE or mislead with a null
        // world/pos collision query; force a full solid cube for them (matches the dedup study).
        boolean special = block instanceof BaseEntityBlock
                || BlockKinds.isBambooStalk(block)
                || block instanceof PointedDripstoneBlock;

        VoxelShape shape = special ? null : state.getCollisionShape(null, null);

        int faces = 0;
        for (Direction d : Direction.values()) {
            boolean sturdy = special || state.isFaceSturdy(null, null, d);
            if (sturdy) faces |= 1 << d.ordinal();
        }

        int shapeClass = computeShape(block, state, shape, special);
        int topY = special ? 16
                : (shape == null || shape.isEmpty()) ? 0
                : clamp((int) Math.round(shape.max(Direction.Axis.Y) * 16.0), 0, 31);

        FluidState fs = state.getFluidState();
        int fluid = fs.isEmpty() ? FLUID_NONE
                : (fs.getType() == Fluids.LAVA || fs.getType() == Fluids.FLOWING_LAVA) ? FLUID_LAVA
                : FLUID_WATER;

        int openable = block instanceof DoorBlock ? OPEN_DOOR
                : block instanceof TrapDoorBlock ? OPEN_TRAPDOOR
                : block instanceof FenceGateBlock ? OPEN_GATE
                : OPEN_NONE;

        int surface = isSlippery(block) ? SURFACE_SLIPPERY
                : isSlow(block) ? SURFACE_SLOW
                : SURFACE_NONE;

        float destroyTime = block.defaultDestroyTime();
        int hardness = destroyTime < 0 ? 255 : clamp(Math.round(destroyTime * 5f), 0, 254);

        Tool tool = hardness > 1 ? bestTool(state) : Tool.NONE; // weak blocks just use hands
        boolean toolRequired = hardness > 1 && state.requiresCorrectToolForDrops();

        long d = 0L;
        d |= (long) (topY & TOP_Y_MASK) << TOP_Y_SHIFT;
        d |= (long) (shapeClass & SHAPE_MASK) << SHAPE_SHIFT;
        d |= (long) (faces & FACES_MASK) << FACES_SHIFT;
        d |= (long) (openable & OPEN_MASK) << OPEN_SHIFT;
        d |= (long) (fluid & FLUID_MASK) << FLUID_SHIFT;
        d |= (long) (surface & SURF_MASK) << SURF_SHIFT;
        if (isClimbable(block))               d |= CLIMB_BIT;
        if (block instanceof FallingBlock)    d |= GRAVITY_BIT;
        if (isDamaging(block))                d |= DAMAGE_BIT;
        if (Replaceable.isReplaceable(state)) d |= REPLACE_BIT;
        d |= (long) (hardness & HARD_MASK) << HARD_SHIFT;
        d |= (long) (tool.ordinal() & TOOL_MASK) << TOOL_SHIFT;
        if (toolRequired)                     d |= TOOLREQ_BIT;
        if (state.hasProperty(BlockStateProperties.WATERLOGGED)) d |= WLOGABLE_BIT;
        return d;
    }

    private static int computeShape(Block block, BlockState state, VoxelShape shape, boolean special) {
        if (special) return SHAPE_FULL;
        if (shape == null || shape.isEmpty()) return SHAPE_EMPTY;
        if (block instanceof StairBlock) return SHAPE_STAIR;
        if (state.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            SlabType t = state.getValue(BlockStateProperties.SLAB_TYPE);
            if (t == SlabType.DOUBLE) return SHAPE_FULL;
            return t == SlabType.TOP ? SHAPE_SLAB_TOP : SHAPE_SLAB_BOTTOM;
        }
        if (block instanceof SnowLayerBlock) return SHAPE_LAYER;
        AABB b = shape.bounds();
        boolean unitCube = b.minX == 0 && b.minY == 0 && b.minZ == 0
                && b.maxX == 1 && b.maxY == 1 && b.maxZ == 1;
        if (unitCube) return SHAPE_FULL;            // stairs/slabs already returned above
        return b.maxY <= 0.5 ? SHAPE_PARTIAL_LOW : SHAPE_OTHER;
    }

    private static boolean isClimbable(Block block) {
        return block == Blocks.LADDER || block == Blocks.SCAFFOLDING
                || block == Blocks.VINE
                || block == Blocks.CAVE_VINES || block == Blocks.CAVE_VINES_PLANT
                || block == Blocks.TWISTING_VINES || block == Blocks.TWISTING_VINES_PLANT
                || block == Blocks.WEEPING_VINES || block == Blocks.WEEPING_VINES_PLANT;
    }

    private static boolean isSlippery(Block block) {
        return block == Blocks.ICE || block == Blocks.PACKED_ICE
                || block == Blocks.BLUE_ICE || block == Blocks.FROSTED_ICE;
    }

    private static boolean isSlow(Block block) {
        return block == Blocks.SOUL_SAND || block == Blocks.SOUL_SOIL
                || block == Blocks.HONEY_BLOCK || block == Blocks.SLIME_BLOCK
                || block == Blocks.COBWEB;
    }

    private static boolean isDamaging(Block block) {
        return block == Blocks.LAVA || block == Blocks.FIRE || block == Blocks.SOUL_FIRE
                || block == Blocks.CAMPFIRE || block == Blocks.SOUL_CAMPFIRE
                || block == Blocks.MAGMA_BLOCK || block == Blocks.CACTUS
                || block == Blocks.SWEET_BERRY_BUSH || block == Blocks.WITHER_ROSE;
    }

    private static Tool bestTool(BlockState state) {
        if (state.is(Blocks.VINE) || state.is(Blocks.COBWEB)) return Tool.SHEARS;
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) return Tool.PICKAXE;
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) return Tool.AXE;
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) return Tool.SHOVEL;
        if (state.is(BlockTags.MINEABLE_WITH_HOE)) return Tool.HOE;
        if (MineableTags.swordEfficient(state)) return Tool.SWORD;
        return Tool.NONE;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    // ---- Lookup (cold / per-palette-entry) ---------------------------------------------------

    /** The navtype index for a block state ({@link #AIR} for unknown states). */
    public static short navtypeFor(BlockState state) {
        Short n = STATE_TO_NAVTYPE.get(state);
        return n == null ? AIR : n;
    }

    /** The packed descriptor for a navtype index. */
    public static long descriptor(short navtype) {
        return descriptors[navtype & 0xFFFF];
    }

    /** The packed descriptor for a block state (convenience: {@code descriptor(navtypeFor(state))}). */
    public static long descriptorFor(BlockState state) {
        return descriptors[navtypeFor(state) & 0xFFFF];
    }

    /** Number of distinct navtypes registered. */
    public static int navtypeCount() {
        return navtypeCount;
    }

    /** Size of the descriptor table in bytes (for cache analysis). */
    public static int tableBytes() {
        return navtypeCount * 8;
    }

    /** Number of states whose geometry query threw and fell back to a solid cube. */
    public static int errorCount() {
        return errorCount;
    }

    // ---- Field extraction (hot path: pure bit ops on the packed long) ------------------------

    /** Collision top surface in 1/16ths (0..31): e.g. 8 = half block, 16 = full. */
    public static int topY(long d)        { return (int) (d >>> TOP_Y_SHIFT) & TOP_Y_MASK; }
    /** ShapeClass ordinal — one of {@link #SHAPE_EMPTY}..{@link #SHAPE_OTHER}. */
    public static int shape(long d)        { return (int) (d >>> SHAPE_SHIFT) & SHAPE_MASK; }
    /** True if the given face is solid (sturdy / full square). */
    public static boolean isFaceSolid(long d, Direction face) {
        return ((int) (d >>> FACES_SHIFT) & (1 << face.ordinal())) != 0;
    }
    /** Raw 6-bit solid-face mask (bit per {@link Direction#ordinal()}). */
    public static int faceMask(long d)     { return (int) (d >>> FACES_SHIFT) & FACES_MASK; }
    /** Openable kind: 0 none, 1 door, 2 trapdoor, 3 fence-gate. */
    public static int openable(long d)     { return (int) (d >>> OPEN_SHIFT) & OPEN_MASK; }
    /** Fluid: 0 none, 1 water (incl. waterlogged), 2 lava. */
    public static int fluid(long d)        { return (int) (d >>> FLUID_SHIFT) & FLUID_MASK; }
    /** Surface: 0 none, 1 slow, 2 slippery. */
    public static int surface(long d)      { return (int) (d >>> SURF_SHIFT) & SURF_MASK; }
    public static boolean isClimbable(long d)   { return (d & CLIMB_BIT) != 0; }
    public static boolean hasGravity(long d)    { return (d & GRAVITY_BIT) != 0; }
    public static boolean isDamaging(long d)    { return (d & DAMAGE_BIT) != 0; }
    public static boolean isReplaceable(long d) { return (d & REPLACE_BIT) != 0; }
    /** Quantized hardness: 255 = unbreakable, else round(destroyTime*5). */
    public static int hardness(long d)     { return (int) (d >>> HARD_SHIFT) & HARD_MASK; }
    /** Best-tool ordinal ({@link Tool}). */
    public static int tool(long d)         { return (int) (d >>> TOOL_SHIFT) & TOOL_MASK; }
    public static boolean toolRequired(long d)  { return (d & TOOLREQ_BIT) != 0; }
    /** Static: the block can be waterlogged (a bucket-clutch on it is absorbed). */
    public static boolean isWaterloggable(long d) { return (d & WLOGABLE_BIT) != 0; }
    /** Derived: there is water in this cell right now (waterlogged or a water block). */
    public static boolean isWaterloggedNow(long d) { return fluid(d) == FLUID_WATER; }
    /** True if nothing collides here (air/plant/fluid). */
    public static boolean isPassable(long d) { return shape(d) == SHAPE_EMPTY; }
}
