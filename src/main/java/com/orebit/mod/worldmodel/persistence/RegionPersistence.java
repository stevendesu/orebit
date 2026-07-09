package com.orebit.mod.worldmodel.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.orebit.mod.OrebitCommon;
import com.orebit.mod.config.ConfigLoader;
import com.orebit.mod.worldmodel.hpa.CostPyramid;
import com.orebit.mod.worldmodel.hpa.PyramidMerger;
import com.orebit.mod.worldmodel.hpa.RegionGrid;
import com.orebit.mod.worldmodel.resource.ResourceMerger;
import com.orebit.mod.worldmodel.resource.ResourcePyramid;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Persists the per-dimension HPA region tier — the {@link CostPyramid} routing fragments and the
 * {@link ResourcePyramid} resource tallies — to the world save so the bot's memory of explored terrain survives
 * a server restart (DESIGN-worldmodel-persistence.md). This closes the last deferred remainder of the HPA arc
 * (§11): without it, after a restart the region A* and {@code /bot report} silently degrade to their optimistic
 * defaults until chunks physically reload.
 *
 * <h2>Why this exists / the primary trigger</h2>
 * The target deployment auto-stops after a short idle period and restarts constantly (an Exaroton-style
 * graceful shutdown), so <b>restart-survival is critical and the clean-stop flush is the primary trigger</b>.
 * A budgeted periodic flush is layered on as crash insurance.
 *
 * <h2>Mechanism — plain files, not vanilla {@code SavedData}</h2>
 * Mirrors the {@link com.orebit.mod.BotManager} precedent exactly: {@code server.getWorldPath(LevelResource.ROOT)}
 * + {@code java.nio.file.Files}, our own byte format, zero NBT / SavedData (whose API drifts hard across the
 * 1.17→26.x matrix — §3). Layout (§4, per the locked plain-blob decision — no region sharding):
 * <pre>
 *   &lt;world&gt;/orebit/&lt;dim&gt;/hpa.bin   cost-pyramid level-0 leaves   (CostPyramidCodec)
 *   &lt;world&gt;/orebit/&lt;dim&gt;/res.bin   resource-pyramid level-0 tallies (ResourcePyramidCodec)
 * </pre>
 * where {@code <dim>} is {@code level.dimension().location().toString()} (e.g. {@code minecraft:overworld})
 * sanitized to a single filesystem-safe directory name ({@code minecraft_overworld}).
 *
 * <h2>Lifecycle (§5, locked decisions)</h2>
 * <ul>
 *   <li><b>Load — eager, at {@code SERVER_STARTED}</b> ({@link #loadAll}), on the tick thread before any player
 *       joins. For every level with a file, interns its level-0 rows and replays {@code mergeUp*} to rebuild the
 *       coarse levels. No lazy / planner-thread loading — that sidesteps the epoch-concurrency hazard entirely.</li>
 *   <li><b>Flush — authoritative, at {@code SERVER_STOPPING}</b> ({@link #flushAll}), on the tick thread after
 *       the tick loop has halted (no concurrent writer).</li>
 *   <li><b>Periodic flush</b> ({@link #tick}) — every {@code hpa.persistIntervalTicks} off the existing
 *       {@code onWorldTickEnd} cadence, re-writing only dimensions marked {@linkplain #markDirty dirty} since the
 *       last flush. Crash insurance.</li>
 * </ul>
 *
 * <h2>Cache, never source of truth (§5, §7)</h2>
 * Every file carries a magic + version header; on a bad magic / version mismatch / any IO or decode error the
 * file is treated as absent (logged once, throttled) and the live world rebuilds it. Persistence never crashes
 * the server. And the live world always wins: a physically-loaded chunk's {@code onChunkNavBuilt} overwrites its
 * leaf from live geometry, and the decoders never clobber a leaf already built this session.
 *
 * <h2>Concurrency</h2>
 * All load/flush work runs on the tick thread (or after it halts), so it never races a planner-thread search or
 * a {@code CostPyramid}/{@code ResourcePyramid} array grow. The dirty set is concurrent only so the marking side
 * stays lock-free; marking happens from tick-thread write sites in {@link com.orebit.mod.worldmodel.hpa.HpaMaintenance}.
 *
 * <p>Static utility, mirroring {@code BotManager}'s shape. All I/O here is COLD (server start/stop/periodic), so
 * normal allocation is fine.
 */
public final class RegionPersistence {

    private RegionPersistence() {}

    /** Sub-directory of the world save that holds all Orebit region data. */
    private static final String ROOT_DIR = "orebit";
    private static final String COST_FILE = "hpa.bin";
    private static final String RESOURCE_FILE = "res.bin";

    /** Dimensions with unflushed leaf changes since their last flush — the periodic-flush work set (§5.2). */
    private static final Set<ServerLevel> DIRTY = ConcurrentHashMap.newKeySet();
    /** Per-dimension tick counter for the periodic flush cadence. Tick-thread only. */
    private static final Map<ServerLevel, Integer> TICKS_SINCE_FLUSH = new ConcurrentHashMap<>();

    /** Load-failure log throttle. */
    private static volatile long loadFailures = 0;
    /** Flush-failure log throttle. */
    private static volatile long flushFailures = 0;

    // ---------------------------------------------------------------------------------------------------
    // Load (eager, at SERVER_STARTED)
    // ---------------------------------------------------------------------------------------------------

    /**
     * Eager-load every dimension's persisted region tier at server start (§5.3). For each level that has an
     * {@code hpa.bin} / {@code res.bin}, creates its {@link RegionGrid}, interns the level-0 rows, and replays
     * the coarse roll-up. Dimensions with no files are left untouched (no grid materialized). Runs on the tick
     * thread before any player can join, so there is no concurrent reader/writer.
     */
    public static void loadAll(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            Path dir = dimDir(server, level);
            Path costFile = dir.resolve(COST_FILE);
            Path resFile = dir.resolve(RESOURCE_FILE);
            boolean hasCost = Files.isRegularFile(costFile);
            boolean hasRes = Files.isRegularFile(resFile);
            if (!hasCost && !hasRes) continue;

            RegionGrid grid = RegionGrid.of(level);
            if (hasCost) loadCost(grid, costFile);
            if (hasRes) loadResource(grid, resFile);
        }
    }

    /** Decode {@code hpa.bin} into the grid's cost pyramid, then replay {@code mergeUpFragments} per leaf. */
    private static void loadCost(RegionGrid grid, Path file) {
        try {
            CostPyramid p = grid.pyramid();
            try (InputStream in = Files.newInputStream(file)) {
                CostPyramidCodec.decode(in, p);
            }
            // Rebuild the coarse pyramid from the loaded leaves (coarse levels are not persisted, §2.1).
            int rows = p.rowCount(0);
            for (int r = 0; r < rows; r++) {
                if (p.isBuilt(0, r)) {
                    PyramidMerger.mergeUpFragments(p, p.rowRX(0, r), p.rowRY(0, r), p.rowRZ(0, r));
                }
            }
        } catch (Throwable t) {
            onLoadFailure(file, t);
        }
    }

    /** Decode {@code res.bin} into the grid's resource pyramid, then replay {@code mergeUpTallies} per leaf. */
    private static void loadResource(RegionGrid grid, Path file) {
        try {
            ResourcePyramid p = grid.resourcePyramid();
            try (InputStream in = Files.newInputStream(file)) {
                ResourcePyramidCodec.decode(in, p);
            }
            int rows = p.rowCount(0);
            for (int r = 0; r < rows; r++) {
                if (p.isBuilt(0, r)) {
                    ResourceMerger.mergeUpTallies(p, p.rowRX(0, r), p.rowRY(0, r), p.rowRZ(0, r));
                }
            }
        } catch (Throwable t) {
            onLoadFailure(file, t);
        }
    }

    private static void onLoadFailure(Path file, Throwable t) {
        long n = ++loadFailures;
        if (n == 1 || n % 64 == 0) {
            OrebitCommon.LOGGER.warn("[Orebit] region persistence: could not load {} [{} total] — "
                    + "treating as absent; the live world rebuilds it", file, n, t);
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // Flush (authoritative at SERVER_STOPPING; periodic as insurance)
    // ---------------------------------------------------------------------------------------------------

    /**
     * The authoritative full flush (§5.2 #1) — write every live dimension's region tier. Called on
     * {@code SERVER_STOPPING} after the tick loop halts, so there is no concurrent writer. Clears the dirty set
     * (everything is now on disk).
     */
    public static void flushAll(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            RegionGrid grid = RegionGrid.peek(level);
            if (grid == null) continue; // never planned/explored in this dimension → nothing to persist
            flushLevel(server, level, grid);
            DIRTY.remove(level);
        }
    }

    /** Write one dimension's {@code hpa.bin} + {@code res.bin} (atomic replace; each half independent). */
    private static void flushLevel(MinecraftServer server, ServerLevel level, RegionGrid grid) {
        Path dir = dimDir(server, level);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            onFlushFailure(dir, e);
            return;
        }
        if (hasBuiltLeaves(grid.pyramid())) {
            writeAtomic(dir.resolve(COST_FILE), out -> CostPyramidCodec.encode(grid.pyramid(), out));
        }
        if (hasResourceLeaves(grid.resourcePyramid())) {
            writeAtomic(dir.resolve(RESOURCE_FILE), out -> ResourcePyramidCodec.encode(grid.resourcePyramid(), out));
        }
    }

    private static boolean hasBuiltLeaves(CostPyramid p) {
        int rows = p.rowCount(0);
        for (int r = 0; r < rows; r++) {
            if (p.isBuilt(0, r) && p.fragmentRecord(0, r) != null) return true;
        }
        return false;
    }

    private static boolean hasResourceLeaves(ResourcePyramid p) {
        int rows = p.rowCount(0);
        for (int r = 0; r < rows; r++) {
            if (p.isBuilt(0, r)) return true;
        }
        return false;
    }

    /** A codec write against an {@link OutputStream}, allowed to throw. */
    @FunctionalInterface
    private interface StreamWriter {
        void write(OutputStream out) throws IOException;
    }

    /**
     * Write via a temp file then move into place, so a crash mid-write (the periodic flush runs while the server
     * is live) never leaves a half-written {@code .bin} — the previous good file survives. Falls back to a plain
     * replace when the filesystem refuses an atomic move. Never throws onto the tick thread.
     */
    private static void writeAtomic(Path file, StreamWriter writer) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                writer.write(out);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable t) {
            onFlushFailure(file, t);
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort — a stray .tmp is harmless (overwritten next flush)
            }
        }
    }

    private static void onFlushFailure(Path file, Throwable t) {
        long n = ++flushFailures;
        if (n == 1 || n % 64 == 0) {
            OrebitCommon.LOGGER.warn("[Orebit] region persistence: could not write {} [{} total] — "
                    + "persisted data may be stale until the next flush", file, n, t);
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // Periodic flush (crash insurance) + dirty tracking
    // ---------------------------------------------------------------------------------------------------

    /**
     * Mark a dimension as having unflushed leaf changes (§5.2). Called from the tick-thread write sites
     * ({@link com.orebit.mod.worldmodel.hpa.HpaMaintenance}); the next periodic {@link #tick} (or the stop flush)
     * writes it. Idempotent, cheap (one set add), off any hot search path.
     */
    public static void markDirty(ServerLevel level) {
        DIRTY.add(level);
    }

    /**
     * The periodic-flush driver, wired off {@code onWorldTickEnd}. Every {@code hpa.persistIntervalTicks} ticks
     * for a level, if it has been marked dirty since the last flush, write it and clear the mark. A
     * non-positive interval disables the periodic flush (the stop flush still runs). Runs on the tick thread.
     */
    public static void tick(ServerLevel level) {
        int interval = ConfigLoader.config().persistIntervalTicks();
        if (interval <= 0) return;
        int t = TICKS_SINCE_FLUSH.merge(level, 1, Integer::sum);
        if (t < interval) return;
        TICKS_SINCE_FLUSH.put(level, 0);
        if (!DIRTY.remove(level)) return; // nothing changed since the last flush
        RegionGrid grid = RegionGrid.peek(level);
        if (grid == null) return;
        MinecraftServer server = level.getServer();
        if (server != null) flushLevel(server, level, grid);
    }

    /** Drop a dimension's dirty/tick bookkeeping (server stop). */
    public static void clear() {
        DIRTY.clear();
        TICKS_SINCE_FLUSH.clear();
    }

    // ---------------------------------------------------------------------------------------------------
    // Paths
    // ---------------------------------------------------------------------------------------------------

    /** {@code <world>/orebit/<sanitized-dim>} for a level. {@code LevelResource.ROOT} is stable 1.17→26.x. */
    private static Path dimDir(MinecraftServer server, ServerLevel level) {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve(ROOT_DIR)
                .resolve(sanitize(dimensionId(level)));
    }

    /**
     * The dimension id string, e.g. {@code "minecraft:overworld"}. Only {@code .toString()} is called on the
     * location so the {@code ResourceLocation}/{@code Identifier} <i>type</i> is never named (it was renamed at
     * 1.21.11) — the whole expression compiles unchanged across the matrix.
     */
    private static String dimensionId(ServerLevel level) {
        return level.dimension().location().toString();
    }

    /** Map a dimension id to one flat, filesystem-safe directory name (Windows-safe: {@code :}/{@code /} → {@code _}). */
    static String sanitize(String dimId) {
        StringBuilder sb = new StringBuilder(dimId.length());
        for (int i = 0; i < dimId.length(); i++) {
            char c = dimId.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_';
            sb.append(safe ? c : '_');
        }
        return sb.toString();
    }
}
