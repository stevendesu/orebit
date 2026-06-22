package com.orebit.mod.worldmodel.navblock;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class NavBlock {

    /*
     * NavBlock methods
     */
    public boolean isDirectional() {
        return directional;
    }

    public boolean isWaterloggable() {
        return waterloggable;
    }

    /*
     * Must be defined before calling register() below
     */
    private static final int MAX_ENTRIES = 256;
    private static final Map<Block, Byte> blockToIndex = new HashMap<>();
    private static final Map<NavBlock, Byte> navBlockToIndex = new HashMap<>();
    private static final NavBlock[] indexToNavBlock = new NavBlock[MAX_ENTRIES];
    private static final byte[] nextIndex = new byte[4];
    private static final int[] MAX_INDEX = {64, 32, 16, 8};

    private static final Set<Direction> ALL_FACES = EnumSet.allOf(Direction.class);
    private static final Set<Direction> NO_FACES = EnumSet.noneOf(Direction.class);
    private static final VoxelShape FULL_CUBE = Shapes.block();

    private static boolean blockIsClimbable(Block block) {
        return block == Blocks.LADDER ||
            block == Blocks.VINE ||
            block == Blocks.CAVE_VINES ||
            block == Blocks.SCAFFOLDING;
    }

    private static boolean blockWillCauseDamage(Block block) {
        return block == Blocks.LAVA ||
            block == Blocks.FIRE ||
            block == Blocks.SOUL_FIRE ||
            block == Blocks.CAMPFIRE ||
            block == Blocks.SOUL_CAMPFIRE ||
            block == Blocks.WITHER_ROSE;
    }

    private static boolean blockWillCauseSlow(Block block) {
        return block == Blocks.HONEY_BLOCK ||
            block == Blocks.SLIME_BLOCK ||
            block == Blocks.SOUL_SAND ||
            block == Blocks.SOUL_SOIL ||
            block == Blocks.COBWEB;
    }

    private static Tool getBestToolForBlock(BlockState state) {
        if (/*state.is(Blocks.WOOL) || state.is(Blocks.LEAVES) || */state.is(Blocks.VINE) || state.is(Blocks.COBWEB)) {
            return Tool.SHEARS;
        }
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
            return Tool.PICKAXE; // or any other pickaxe
        } else if (state.is(BlockTags.MINEABLE_WITH_AXE)) {
            return Tool.AXE; // or any other axe
        } else if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
            return Tool.SHOVEL; // or any other shovel
        } else if (state.is(BlockTags.MINEABLE_WITH_HOE)) {
            return Tool.HOE; // or any other hoe
        } else if (MineableTags.swordEfficient(state)) {
            return Tool.SWORD;
        }
        return Tool.NONE; // No specific tool
    }

    private static boolean blockIsDirectional(Block block) {
        return (
            block instanceof DoorBlock ||
            block instanceof TrapDoorBlock ||
            block instanceof SlabBlock ||
            block instanceof StairBlock ||
            block instanceof SnowLayerBlock
        );
    }

    public final static byte AIR = register(
        NavBlock.builder()
            .solidFaces(NO_FACES)
            .replaceable(true)
            .build()
    );

    static {
        BlockLookup.forEachBlock(block -> {
            final BlockState bs = block.defaultBlockState();
            Set<Direction> solidFaces;
            VoxelShape shape;
            if (
                block instanceof BaseEntityBlock ||
                BlockKinds.isBambooStalk(block) ||
                block instanceof PointedDripstoneBlock
            ) {
                solidFaces = ALL_FACES;
                shape = FULL_CUBE;
            } else {
                solidFaces = Arrays.stream(Direction.values())
                    .filter(direction -> {
                        return bs.isFaceSturdy(null, null, direction);
                    })
                    .collect(Collectors.toSet());
                shape = bs.getCollisionShape(null, null);
            }
            NavBlock navBlock = NavBlock.builder()
                .height(shape.max(Direction.Axis.Y))
                .solidFaces(solidFaces)
                // closedFaces
                .climbable(blockIsClimbable(block))
                // fluid
                .gravity(block instanceof FallingBlock)
                .damaging(blockWillCauseDamage(block))
                .slowing(blockWillCauseSlow(block))
                .replaceable(Replaceable.isReplaceable(bs))
                .hardness(block.defaultDestroyTime())
                .tool(getBestToolForBlock(bs))
                .toolRequired(bs.requiresCorrectToolForDrops())
                .directional(blockIsDirectional(block))
                .waterloggable(bs.hasProperty(BlockStateProperties.WATERLOGGED))
                .build();
            byte index = register(navBlock);
            map(block, index);
        });

        System.out.println("Number of registered navblocks: " + Arrays.toString(nextIndex));
        System.out.println("Mapped MineCraft blocks: " + blockMappings().size() + " / " + BlockLookup.blockCount());
        dumpBlockCsv();
    }

    /*
     * NavBlock class
     */
    private enum Tool {
        NONE, PICKAXE, AXE, SHOVEL, HOE, SWORD, SHEARS;
    }

    private final float height;
    private final Set<Direction> solidFaces;
    private final Set<Direction> closedFaces;
    private final boolean climbable;
    private final boolean fluid;
    private final boolean gravity;
    private final boolean damaging;
    private final boolean slowing;
    private final boolean replaceable;
    private final byte hardnessLog;
    private final Tool tool;
    private final boolean toolRequired;
    private final boolean directional;
    private final boolean waterloggable;

    private NavBlock(Builder builder) {
        // Fluids don't have solid faces
        if (builder.fluid) {
            builder.solidFaces = NO_FACES;
        }
        // If it's intangible, height is irrelevant
        this.height = builder.solidFaces.isEmpty() ? 1.0f : (float) Math.floor(builder.height * 5.0f) / 5.0f;
        this.solidFaces = builder.solidFaces;
        this.closedFaces = builder.closedFaces;
        this.climbable = builder.climbable;
        this.fluid = builder.fluid;
        this.gravity = builder.gravity;
        this.damaging = builder.damaging;
        this.slowing = builder.slowing;
        this.replaceable = builder.replaceable;
        this.hardnessLog = (byte) (
            builder.hardness == Float.MAX_VALUE ? 127 :
            builder.directional
            ? (builder.hardness < 2 ? 3 : 5) // Massively compress directional blocks
            : Math.max(0, Math.round(Math.log(builder.hardness+0.001)/Math.log(2))+3)
        );
        this.tool = hardnessLog > 1 ? builder.tool : Tool.NONE; // Weak blocks should just use hands
        this.toolRequired = hardnessLog > 1 && builder.toolRequired;
        this.directional = builder.directional;
        this.waterloggable = builder.waterloggable;
    }

    private static void writeCsv(String filePath, String data) {
        try (FileWriter fileWriter = new FileWriter(filePath);
             PrintWriter printWriter = new PrintWriter(fileWriter)) {
            printWriter.println(data);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void dumpBlockCsv() {
        StringBuilder sb = new StringBuilder();
            sb.append("Block name,NavBlock index,Height,Solid Faces,Climbable,Fluid,Gravity,Damaging,Slowing,Replaceable,Hardness,Tool,Tool Required,Directional,Waterloggable\n");
            blockToIndex.forEach((key, idx) -> {
                NavBlock debugBlock = indexToNavBlock[idx & 0xFF];
                sb.append(key.getName().getString());
                sb.append(",");
                sb.append(Integer.toString(idx & 0xFF));
                sb.append(",");
                sb.append(debugBlock.height);
                sb.append(",\"");
                sb.append(debugBlock.solidFaces);
                sb.append("\",");
                sb.append(debugBlock.climbable);
                sb.append(",");
                sb.append(debugBlock.fluid);
                sb.append(",");
                sb.append(debugBlock.gravity);
                sb.append(",");
                sb.append(debugBlock.damaging);
                sb.append(",");
                sb.append(debugBlock.slowing);
                sb.append(",");
                sb.append(debugBlock.replaceable);
                sb.append(",");
                sb.append(debugBlock.hardnessLog);
                sb.append(",");
                sb.append(debugBlock.tool);
                sb.append(",");
                sb.append(debugBlock.toolRequired);
                sb.append(",");
                sb.append(debugBlock.directional);
                sb.append(",");
                sb.append(debugBlock.waterloggable);
                sb.append("\n");
            });
            writeCsv("./too-many-blocks.csv", sb.toString());
    }

    /*
     * NavBlock index
     */
    public static byte register(NavBlock navBlock, Block... blocks) {
        if (navBlockToIndex.containsKey(navBlock)) {
            return navBlockToIndex.get(navBlock);
        }

        int mode = (navBlock.isWaterloggable() ? 0b01 : 0b00) | (navBlock.isDirectional() ? 0b10 : 0b00);
        if (nextIndex[mode] >= MAX_INDEX[mode]) {
            dumpBlockCsv();
            throw new IllegalStateException("Too many blocks registered for mode: " + mode);
        }
        int index = (mode << 6) | (nextIndex[mode] % MAX_INDEX[mode]);
        nextIndex[mode]++;

        navBlockToIndex.put(navBlock, (byte) index);
        indexToNavBlock[index & 0xFF] = navBlock;
        return (byte) index;
    }

    public static byte registerDirection(NavBlock navBlock, byte baseIndex, int directionIndex) {
        if (baseIndex == 0) throw new IllegalStateException("Base block must be registered before direction");
        if ((baseIndex & 0b1000000) == 0) throw new IllegalStateException("Base block must be directional");
        int index = baseIndex | (directionIndex << 3);
        navBlockToIndex.put(navBlock, (byte) index);
        indexToNavBlock[index & 0xFF] = navBlock;
        return (byte) index;
    }

    private static void map(Block block, byte index) {
        blockToIndex.put(block, index);
    }

    public static byte getIndexForBlock(Block block) {
        return blockToIndex.getOrDefault(block, AIR);
    }

    public static NavBlock getNavBlockForIndex(byte index) {
        return indexToNavBlock[index & 0xFF];
    }

    public static Set<Map.Entry<Block, Byte>> blockMappings() {
        return blockToIndex.entrySet();
    }

    public static Set<NavBlock> getAllNavBlocks() {
        return navBlockToIndex.keySet();
    }

    /*
     * NavBlock builder
     */
    private static Builder builder() {
        return new Builder();
    }

    private static final class Builder {
        private float height = 1.0f;
        private Set<Direction> solidFaces = ALL_FACES;
        private Set<Direction> closedFaces = ALL_FACES;
        private boolean climbable = false;
        private boolean fluid = false;
        private boolean gravity = false;
        private boolean damaging = false;
        private boolean slowing = false;
        private boolean replaceable = false;
        private float hardness = 1.5f;
        private Tool tool = Tool.PICKAXE;
        private boolean toolRequired = false;
        private boolean directional = false;
        private boolean waterloggable = false;

        public Builder height(double value) {
            if (value < 0) {
                // MineCraft uses -Infinity to represent intangible blocks
                // This is a problem because Infinity != Infinity, so they're
                // seen as different blocks
                this.height = 1.0f;
            } else {
                this.height = (float) value;
            }
            return this;
        }

        public Builder solidFaces(Set<Direction> faces) {
            this.solidFaces = faces;
            return this;
        }

        public Builder closedFaces(Set<Direction> faces) {
            this.closedFaces = faces;
            return this;
        }

        public Builder climbable(boolean value) {
            this.climbable = value;
            return this;
        }

        public Builder fluid(boolean value) {
            this.fluid = value;
            return this;
        }

        public Builder gravity(boolean value) {
            this.gravity = value;
            return this;
        }

        public Builder damaging(boolean value) {
            this.damaging = value;
            return this;
        }

        public Builder slowing(boolean value) {
            this.slowing = value;
            return this;
        }

        public Builder replaceable(boolean value) {
            this.replaceable = value;
            return this;
        }

        public Builder hardness(float value) {
            if (value < 0) this.hardness = Float.MAX_VALUE;
            else this.hardness = value;
            return this;
        }

        public Builder tool(Tool tool) {
            this.tool = tool;
            return this;
        }

        public Builder toolRequired(boolean value) {
            this.toolRequired = value;
            return this;
        }

        public Builder directional(boolean value) {
            this.directional = value;
            return this;
        }

        public Builder waterloggable(boolean value) {
            this.waterloggable = value;
            return this;
        }

        public NavBlock build() {
            return new NavBlock(this);
        }
    }

    /*
     * Specialized equality check for HashMaps
     */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof NavBlock other)) return false;
        return Float.compare(height, other.height) == 0 &&
            Objects.equals(solidFaces, other.solidFaces) &&
            Objects.equals(closedFaces, other.closedFaces) &&
            fluid == other.fluid &&
            gravity == other.gravity &&
            damaging == other.damaging &&
            slowing == other.slowing &&
            replaceable == other.replaceable &&
            hardnessLog == other.hardnessLog &&
            tool == other.tool &&
            toolRequired == other.toolRequired &&
            directional == other.directional &&
            waterloggable == other.waterloggable;
    }

    @Override
    public int hashCode() {
        return Objects.hash(height, solidFaces, closedFaces, climbable, fluid, gravity, damaging, slowing, replaceable, hardnessLog, tool, toolRequired, waterloggable);
    }
}
