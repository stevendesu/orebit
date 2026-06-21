package com.orebit.mod.worldmodel.region;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;

public class RegionMetadata {
    private static final int MAX_BLOCK_TYPES = RegionBlockIndex.MAX_INDEX;

    // Each entry approximately encodes log₂(block count)
    // A value of 0 means no blocks of that type are present.
    // 1 = 1 block, 2 = 2-3 blocks, 3 = 4-7 blocks, 4 = 8-15 blocks, etc.
    private final byte[] blockStats = new byte[MAX_BLOCK_TYPES];

    private byte minLight = Byte.MAX_VALUE;
    private byte maxLight = 0;
    private float avgLight = 0f;

    public void scan(Level world, BlockPos origin, short[] offsets, int count) {
        int[] rawBlockCounts = new int[MAX_BLOCK_TYPES];
        int totalLight = 0;

        minLight = Byte.MAX_VALUE;
        maxLight = 0;

        for (int i = 0; i < count; i++) {
            BlockPos pos = decodeOffset(origin, offsets[i]);

            BlockState state = world.getBlockState(pos);
            Block block = state.getBlock();
            int type = RegionBlockIndex.getIndexForBlock(block);
            if (type >= 0) rawBlockCounts[type]++;

            int light = world.getMaxLocalRawBrightness(pos);
            minLight = (byte) Math.min(minLight, light);
            maxLight = (byte) Math.max(maxLight, light);
            totalLight += light;
        }

        for (int i = 0; i < MAX_BLOCK_TYPES; i++) {
            if (rawBlockCounts[i] > 0) {
                blockStats[i] = encodeLog2(rawBlockCounts[i]);
            }
        }

        avgLight = (count > 0) ? (float) totalLight / count : 0f;
    }

    private BlockPos decodeOffset(BlockPos origin, short offset) {
        int dx = (offset >> 8) & 0xF;
        int dy = (offset >> 4) & 0xF;
        int dz = offset & 0xF;
        return origin.offset(dx, dy, dz);
    }

    public void reset() {
        Arrays.fill(blockStats, (byte) 0);
        minLight = Byte.MAX_VALUE;
        maxLight = 0;
        avgLight = 0f;
    }

    public void setBlockStat(int blockType, int rawCount) {
        blockStats[blockType] = encodeLog2(rawCount);
    }

    public void merge(RegionMetadata child) {
        for (int i = 0; i < MAX_BLOCK_TYPES; i++) {
            blockStats[i] = mergeLogCounts(blockStats[i], child.blockStats[i]);
        }
        this.minLight = (byte) Math.min(this.minLight, child.minLight);
        this.maxLight = (byte) Math.max(this.maxLight, child.maxLight);
        this.avgLight = (this.avgLight + child.avgLight) * 0.5f;
    }

    public byte getBlockPresence(int blockType) {
        return blockStats[blockType];
    }

    /**
     * Returns an estimated count, clamped to 2^31 if actual is unknown.
     */
    public int getEstimatedBlockCount(int blockType) {
        int shift = blockStats[blockType] & 0x1F; // 0–31
        return 1 << shift;
    }

    public boolean hasAtLeast(int blockType, int minCount) {
        int shift = blockStats[blockType] & 0x1F;
        return (1 << shift) >= minCount;
    }

    public byte getMinLight() { return minLight; }
    public byte getMaxLight() { return maxLight; }
    public float getAvgLight() { return avgLight; }

    public void setLightStats(byte min, byte max, float avg) {
        this.minLight = min;
        this.maxLight = max;
        this.avgLight = avg;
    }

    // Approximate log₂(n). Actually computes floor(log₂(n+1)-1)
    public static byte encodeLog2(int count) {
        return (byte) (32 - Integer.numberOfLeadingZeros(count));
    }

    // Merge two log₂ counts
    public static byte mergeLogCounts(byte a, byte b) {
        int ua = a & 0xFF;
        int ub = b & 0xFF;

        if (ua > ub) return a;
        if (ub > ua) return b;
        return (byte) (ua + 1); // log₂(x) + log₂(x) = log₂(2x)
    }
}
