package com.orebit.mod.farming;

import com.orebit.mod.platform.BlockLookup;
import com.orebit.mod.platform.ItemLookup;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * One farmable crop's facts and behavior — a Strategy class per crop (the owner's
 * abstract-classes-over-enums principle; DESIGN-bot-abilities.md §4), consumed by
 * {@link CropKinds}/{@code BotFarmer}. Each kind names its crop block, the item that plants it,
 * and its mature age; the vanilla {@link Block}/{@link Item} are resolved once at
 * {@link CropKinds#bake} through the id seams (never {@code Blocks.*}/{@code Items.*} constants —
 * crop block CLASS names drifted across versions, the ids never did).
 *
 * <p>Maturity reads the {@code age} integer property GENERICALLY off the state (name-matched via
 * the stable {@code StateHolder#getProperties}/{@code Property#getName} API) — no per-version
 * {@code CropBlock.AGE} reference, and the per-kind {@link #maxAge()} is cross-checked against
 * the real property's maximum in the unit tests.
 */
public abstract class CropKind {

    private Block block;
    private Item seedItem;
    private BlockState matureState;

    /** The crop BLOCK id (the thing standing on the farmland), e.g. {@code minecraft:wheat}. */
    public abstract String cropBlockId();

    /** The item that plants it (and is excluded from bridging), e.g. {@code minecraft:wheat_seeds}. */
    public abstract String seedItemId();

    /** The age at which the crop is fully grown (wheat/carrots/potatoes 7, beetroots 3). */
    public abstract int maxAge();

    /** The owner-facing name (the crop block id's path). */
    public String displayName() {
        final String id = cropBlockId();
        final int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    /** The resolved crop block ({@code null} before {@link CropKinds#bake} or on an odd version). */
    public Block block() {
        return block;
    }

    /** The resolved seed item ({@code null} before {@link CropKinds#bake} or on an odd version). */
    public Item seedItem() {
        return seedItem;
    }

    /** A fully-grown state of this crop (for tests/harness plot building; null when unresolved). */
    public BlockState matureState() {
        return matureState;
    }

    /** Whether {@code state} is this crop at full maturity (harvest-ready). */
    public boolean isMature(BlockState state) {
        return block != null && state.getBlock() == block && ageOf(state) >= maxAge();
    }

    /** Resolve the vanilla block/item through the id seams. Called once by {@link CropKinds#bake}. */
    void resolve() {
        this.block = BlockLookup.byId(cropBlockId());
        this.seedItem = ItemLookup.byId(seedItemId());
        this.matureState = null;
        if (block != null) {
            for (BlockState s : block.getStateDefinition().getPossibleStates()) {
                if (ageOf(s) >= maxAge()) {
                    this.matureState = s;
                    break;
                }
            }
        }
    }

    /**
     * The value of {@code state}'s integer {@code age} property, or {@code -1} when it has none —
     * the generic, version-stable maturity read (crop block classes drifted; the property name
     * never did).
     */
    public static int ageOf(BlockState state) {
        for (Property<?> p : state.getProperties()) {
            if ("age".equals(p.getName())) {
                final Comparable<?> v = state.getValue(p);
                if (v instanceof Integer age) return age;
                return -1;
            }
        }
        return -1;
    }
}
