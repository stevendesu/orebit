package com.orebit.mod.worldmodel.region;

import java.util.Arrays;

import net.minecraft.core.BlockPos;

public class LeafRegion extends Region {
    private static final int MAX_BLOCKS = 4096;
    private final short[] blockOffsets = new short[MAX_BLOCKS];
    private short blockCount = 0;
    private BlockPos origin;

    public LeafRegion(long id, BlockPos origin) {
        super(id);
        this.origin = origin;
    }

    public static LeafRegion create(long id, BlockPos origin) {
        LeafRegion region = RegionPool.getLeafRegion(id, origin);
        return region;
    }

    public BlockPos getOrigin() {
        return origin;
    }

    public void setOrigin(BlockPos origin) {
        this.origin = origin;
    }

    public void addBlockUnsorted(BlockPos pos) {
        if (blockCount >= MAX_BLOCKS) return;
        blockOffsets[blockCount++] = encodeOffset(pos);
    }

    public void addBlock(BlockPos pos) {
        short encoded = encodeOffset(pos);
        insertSorted(encoded);
    }

    public void sortBlocks() {
        Arrays.sort(blockOffsets, 0, blockCount);
    }

    private void insertSorted(short encoded) {
        int index = Arrays.binarySearch(blockOffsets, 0, blockCount, encoded);
        if (index >= 0) return; // already exists
        int insertPos = -(index + 1);
        System.arraycopy(blockOffsets, insertPos, blockOffsets, insertPos + 1, blockCount - insertPos);
        blockOffsets[insertPos] = encoded;
        blockCount++;
    }

    public BlockPos getHighestBlock() {
        if (blockCount == 0) return null;
        return decodeOffset(blockOffsets[blockCount - 1]);
    }
    
    public BlockPos getLowestBlock() {
        if (blockCount == 0) return null;
        return decodeOffset(blockOffsets[0]);
    }

    private short encodeOffset(BlockPos pos) {
        int dx = pos.getX() - origin.getX();
        int dy = pos.getY() - origin.getY();
        int dz = pos.getZ() - origin.getZ();
        return (short) (((dy & 0x1F) << 10) | ((dx & 0x1F) << 5) | (dz & 0x1F));
    }
    
    private BlockPos decodeOffset(short encoded) {
        int dy = (encoded >> 10) & 0x1F;
        int dx = (encoded >> 5) & 0x1F;
        int dz = encoded & 0x1F;
        return origin.offset(dx, dy, dz);
    }    

    @Override
    public boolean contains(BlockPos pos) {
        if (!boundingBox.contains(pos)) return false;
        short encoded = encodeOffset(pos);
        return Arrays.binarySearch(blockOffsets, 0, blockCount, encoded) >= 0;
    }

    @Override
    public void reset() {
        super.reset();
        origin = null;
        blockCount = 0;
        for (int i = 0; i < blockOffsets.length; i++) {
            blockOffsets[i] = 0;
        }
    }
}

