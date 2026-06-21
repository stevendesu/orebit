package com.orebit.mod.worldmodel.pathing;

/**
 * In order to bypass the hidden safety checks in MineCraft's block reading
 * logic and access raw bits, we use reflection to view the private fields
 * on various internal classes:
 *  * PalettedContainer (holds block data)
 *     * PlatteStorage (interface for the raw bits)
 *        * EmptyPaletteStorage (when there are no blocks in the chunk)
 *        * PackedIntegerArray (run-length encoded block indices)
 *     * Palette (interface for mapping block indices to BlockStates)
 *        * SingularPalette (all blocks are the same BlockState)
 *        * ArrayPalette (palette is stored as an array -- simple index lookup)
 *        * BiMapPalette (palette is stored as a BiMap -- hashed lookup)
 *        * IdListPalette (for older worlds -- indices are block IDs)
 * This class both encapsulates all of our Reflection hackery and provides
 * a clean interface on top of it.
 */
public class ReflectionHelper {
    // TBD - I'll copy implementation details from NavSectionBuilder in the next diff
}
