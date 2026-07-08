package com.orebit.mod.worldmodel.pathing;

import com.orebit.mod.platform.BlockChangeEvents;
import com.orebit.mod.platform.LevelBounds;
import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Keeps the nav grid live by patching it on every block change — the block-update hook that retires the
 * follower's per-replan {@code refreshNavData} rebuild. Registers a {@link BlockChangeEvents.Listener};
 * for a server-side change inside a built section it updates that cell's navtype and recomputes the small
 * flag neighbourhood via {@link NavSectionBuilder#patchCell} (no palette scan) — including, across the
 * vertical seam, the top rows of the section BELOW (whose flags read this section through their upward
 * overscan) and overscan reads INTO the section above. Changes in chunks we don't track yet are ignored —
 * they build fresh on load.
 *
 * <p>The trigger is the {@code setBlockState} mixin firing {@link BlockChangeEvents#fire}; until that
 * overlay is wired this listener simply never runs (registering it is harmless).
 */
public final class NavGridUpdater {
    private NavGridUpdater() {}

    /**
     * Per-level count of TRACKED-grid block edits (bumped once per patched cell). This is the cheap
     * "did the world change at all?" signal the follower's terrain-recheck debounce gates on: an
     * unchanged epoch means no built nav cell changed since the plan's last window search, so the
     * periodic re-search would be byte-identical and is skipped entirely (a stationary bot in a quiet
     * world never re-searches). Tick-thread confined (the mixin fires on the server thread; the driver
     * reads on the server thread) — no synchronization. Known coarseness, documented: the epoch is
     * level-global (an edit anywhere re-arms every bot's recheck — one wasted-but-correct search), and
     * it includes the bot's OWN plan edits (excluding those needs per-edit attribution; a plan's own
     * assumed edits are already modelled by PathEdits, so those re-searches return equivalent routes).
     */
    private static final java.util.WeakHashMap<ServerLevel, int[]> EDIT_EPOCH = new java.util.WeakHashMap<>();

    /** The current edit epoch for {@code level} (0 until its first tracked edit). Server thread only. */
    public static int editEpoch(ServerLevel level) {
        final int[] c = EDIT_EPOCH.get(level);
        return c == null ? 0 : c[0];
    }

    /**
     * Advance the epoch for a NON-block-change grid mutation — chunk nav sections built or dropped
     * ({@code ChunkNavLoader}). A newly BUILT area is exactly as plan-relevant as an edited one: without
     * this, a bot whose first search ran before its chunks built (seconds after world open) had no signal
     * to re-search until some block changed (the s52b cold-open false START-DEAD). Server thread only.
     */
    public static void bumpEpoch(ServerLevel level) {
        EDIT_EPOCH.computeIfAbsent(level, l -> new int[1])[0]++;
    }

    /** Register the nav-grid patcher against the block-change seam (once, at init). */
    public static void register() {
        BlockChangeEvents.register(NavGridUpdater::onBlockChanged);
    }

    private static void onBlockChanged(Level level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (!(level instanceof ServerLevel server)) return; // server authority only
        if (oldState == newState) return;                    // interned states: reference-equal == no change

        int sectionIndex = (pos.getY() - LevelBounds.minY(server)) >> 4;
        if (sectionIndex < 0) return;
        NavSection[] sections = NavStore.get(server, NavStore.key(pos.getX() >> 4, pos.getZ() >> 4));
        if (sections == null || sectionIndex >= sections.length) return; // chunk not tracked
        NavSection section = sections[sectionIndex];
        if (section == null) return;

        final int lx = pos.getX() & 15, ly = pos.getY() & 15, lz = pos.getZ() & 15;
        final short newNavtype = NavBlock.navtypeFor(newState); // interned ONCE here, passed down to patchCell

        // NAVTYPE NO-OP EARLY-OUT: a state change that interns to the SAME navtype (redstone power
        // flips, crop growth stages, observer churn) changes nothing the nav grid or any plan can see —
        // this cell's descriptor is identical, so every neighbour's flag/depth window (which reads only
        // navtypes) is identical too. Skipping the patch here is pure saved work; skipping the epoch
        // bump is what keeps the follower's terrain-recheck debounce MEANINGFUL — without it a single
        // redstone clock anywhere in the level re-arms every bot's periodic re-search forever
        // (PERF-DESIGN-navgrid-edit-batching.md phase 0).
        if (!changesGrid(section, lx, ly, lz, newNavtype)) {
            return;
        }

        // A tracked cell is about to be patched — the world visibly changed for every plan over this level.
        EDIT_EPOCH.computeIfAbsent(server, l -> new int[1])[0]++;

        // Nether-portal index maintenance (NetherPortalIndex incremental feed). Read the cell's OLD navtype
        // BEFORE patching (the patch overwrites it): a portal patched out is removed, a portal patched in is
        // added. Two descriptor bit-tests per block change — the index mutates only when a portal actually
        // toggles (vanishingly rare), and this path is per-block-change, never per-A*-node.
        boolean wasPortal = NavBlock.isPortal(
                NavBlock.descriptor((short) section.getTraversalGrid().navtype(lx, ly, lz)));
        boolean nowPortal = NavBlock.isPortal(NavBlock.descriptor(newNavtype));
        if (wasPortal != nowPortal) {
            if (nowPortal) NetherPortalIndex.add(server, pos.getX(), pos.getY(), pos.getZ());
            else NetherPortalIndex.removeCell(server, pos.getX(), pos.getY(), pos.getZ());
        }

        // Vertical column neighbours (same chunk, straight out of the NavStore entry): the ABOVE section
        // feeds the patch's overscan reads (a top-rows recompute must see the blocks over the face); the
        // BELOW section receives the seam propagation (a bottom-rows change is a flags change for its top
        // floor cells). Null at the world edges — patchCell treats that as air / skips, matching build.
        NavSection above = sectionIndex + 1 < sections.length ? sections[sectionIndex + 1] : null;
        NavSection below = sectionIndex > 0 ? sections[sectionIndex - 1] : null;
        NavSectionBuilder.patchCell(section, above, below, lx, ly, lz, newNavtype);
    }

    /**
     * The grid-visibility decision the no-op early-out gates on (package-private: the headless epoch
     * test drives this seam directly — {@code onBlockChanged} itself needs a live {@code ServerLevel},
     * which cannot be stood up under the Knot test classloader). {@code false} means the change is
     * invisible to the nav grid: equal navtype ⇒ equal descriptor ⇒ identical inputs to every
     * neighbour's flag/depth window ⇒ {@code patchCell} would recompute byte-identical values, so
     * skipping BOTH the patch and the epoch bump exactly satisfies the epoch contract above
     * (unchanged epoch ⇒ re-search byte-identical).
     */
    static boolean changesGrid(NavSection section, int lx, int ly, int lz, short newNavtype) {
        return newNavtype != (short) section.getTraversalGrid().navtype(lx, ly, lz);
    }
}
