package com.orebit.mod.worldmodel.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.orebit.mod.OrebitCommon;
import com.orebit.mod.config.ConfigLoader;
import com.orebit.mod.platform.DimensionId;
import com.orebit.mod.worldmodel.hpa.CostPyramid;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionGrid;
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
 * 1.17→26.x matrix — §3).
 *
 * <h2>Stage-1 sharded layout (a behavior-preserving on-disk FORMAT change)</h2>
 * The region tier is written as Minecraft-{@code .mca}-style per-region SHARD files rather than one blob per
 * dimension. A shard is one level-5 region ({@link RegionAddress#SHARD_LEVEL}; {@code sideOf(5) == 512} blocks ==
 * 32×32 chunks), and — crucially — the coarse pyramid levels are persisted DIRECTLY so reload needs no
 * {@code mergeUp} recompute (measured 34–880 ms/shard of tick-thread merge on an L0-only reload):
 * <pre>
 *   &lt;world&gt;/orebit/&lt;dim&gt;/hpa.&lt;X&gt;.&lt;Z&gt;.bin   cost levels 0..5 for shard (X=chunkX&gt;&gt;5, Z=chunkZ&gt;&gt;5)
 *   &lt;world&gt;/orebit/&lt;dim&gt;/hpa.coarse.bin      cost level 6 (per-dimension; MAX_COARSE_LEVEL)
 *   &lt;world&gt;/orebit/&lt;dim&gt;/res.&lt;X&gt;.&lt;Z&gt;.bin   resource levels 0..5 for shard
 *   &lt;world&gt;/orebit/&lt;dim&gt;/res.coarse.bin      resource levels 6..21 (per-dimension; global /bot find/report)
 * </pre>
 * where {@code <dim>} is the dimension id (e.g. {@code minecraft:overworld}, via the {@link DimensionId} seam)
 * sanitized to a single filesystem-safe directory name ({@code minecraft_overworld}). The new file magics
 * ({@code OBHS}/{@code OBHC}/{@code OBRS}/{@code OBRC}) distinguish these from the old {@code hpa.bin}/{@code res.bin}
 * blobs, which are simply IGNORED (never read; left on disk — no user data deleted). This is a FORMAT change
 * only; RAM/paging behaviour is unchanged (everything is still held resident — Stage-2 lazy paging is future).
 *
 * <h2>Lifecycle (§5)</h2>
 * <ul>
 *   <li><b>Load — eager, at {@code SERVER_STARTED}</b> ({@link #loadAll}), on the tick thread before any player
 *       joins. For every dimension with shard/coarse files, interns their rows DIRECTLY into their levels — no
 *       {@code mergeUp} replay (the coarse levels are persisted). RAM still holds everything (Stage 1).</li>
 *   <li><b>Flush — authoritative, at {@code SERVER_STOPPING}</b> ({@link #flushAll}), on the tick thread after
 *       the tick loop has halted (no concurrent writer). Writes every non-empty shard + both coarse files.</li>
 *   <li><b>Periodic flush</b> ({@link #tick}) — every {@code hpa.persistIntervalTicks} off the existing
 *       {@code onWorldTickEnd} cadence, re-writing only the SHARDS marked {@linkplain #markDirty dirty} (plus the
 *       coarse files when a leaf changed) since the last flush. Crash insurance.</li>
 * </ul>
 *
 * <h2>Cache, never source of truth (§5, §7)</h2>
 * Every file carries a magic + version header; on a bad magic / version mismatch / any IO or decode error the
 * file is treated as absent (logged once, throttled) and the live world rebuilds it. Persistence never crashes
 * the server. And the live world always wins: a physically-loaded chunk's {@code onChunkNavBuilt} overwrites its
 * leaf from live geometry, and the decoders never clobber a row already built this session.
 *
 * <h2>Concurrency</h2>
 * All load/flush work runs on the tick thread (or after it halts), so it never races a planner-thread search or
 * a {@code CostPyramid}/{@code ResourcePyramid} array grow. The dirty-shard sets are concurrent only so the
 * marking side stays lock-free; marking happens from tick-thread write sites in
 * {@link com.orebit.mod.worldmodel.hpa.HpaMaintenance}.
 *
 * <p>Static utility, mirroring {@code BotManager}'s shape. All I/O here is COLD (server start/stop/periodic), so
 * normal allocation is fine.
 */
public final class RegionPersistence {

    private RegionPersistence() {}

    /** Sub-directory of the world save that holds all Orebit region data. */
    private static final String ROOT_DIR = "orebit";
    /** Per-dimension coarse file names (cost L6; resource L6..21). */
    private static final String COST_COARSE_FILE = "hpa.coarse.bin";
    private static final String RESOURCE_COARSE_FILE = "res.coarse.bin";
    /** Directory globs that enumerate every cost / resource file (shard files AND the coarse file). Never match
     *  the old {@code hpa.bin}/{@code res.bin} blobs (no dot-separated middle segment) — see the class Javadoc. */
    private static final String COST_GLOB = "hpa.*.bin";
    private static final String RESOURCE_GLOB = "res.*.bin";

    /** Per-dimension dirty-SHARD sets — the periodic-flush work set (§5.2). Key = {@link #shardKey}. */
    private static final Map<ServerLevel, Set<Long>> DIRTY_SHARDS = new ConcurrentHashMap<>();
    /** Dimensions whose coarse pyramid levels changed since their last flush (any leaf edit re-merges to L6). */
    private static final Set<ServerLevel> COARSE_DIRTY = ConcurrentHashMap.newKeySet();
    /** Per-dimension tick counter for the periodic flush cadence. Tick-thread only. */
    private static final Map<ServerLevel, Integer> TICKS_SINCE_FLUSH = new ConcurrentHashMap<>();

    /** Load-failure log throttle. */
    private static volatile long loadFailures = 0;
    /** Flush-failure log throttle. */
    private static volatile long flushFailures = 0;

    // ---------------------------------------------------------------------------------------------------
    // Load (eager, at SERVER_STARTED) — decode shards + coarse DIRECTLY into their levels, no mergeUp
    // ---------------------------------------------------------------------------------------------------

    /**
     * Eager-load every dimension's persisted region tier at server start (§5.3). For each dimension with any
     * shard/coarse file, creates its {@link RegionGrid} and interns every row directly at its stored level — with
     * <b>no {@code mergeUp} replay</b> (the coarse levels are persisted, which is the whole point of Stage 1).
     * Dimensions with no files are left untouched. Runs on the tick thread before any player can join, so there
     * is no concurrent reader/writer.
     */
    public static void loadAll(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            Path dir = dimDir(server, level);
            if (!Files.isDirectory(dir)) continue;

            List<Path> costFiles = listMatching(dir, COST_GLOB);
            List<Path> resFiles = listMatching(dir, RESOURCE_GLOB);
            if (costFiles.isEmpty() && resFiles.isEmpty()) continue;

            RegionGrid grid = RegionGrid.of(level);
            for (Path f : costFiles) loadCostFile(grid, f);
            for (Path f : resFiles) loadResourceFile(grid, f);
        }
    }

    /** List the files in {@code dir} matching {@code glob}; never throws (an unreadable dir → empty). */
    private static List<Path> listMatching(Path dir, String glob) {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, glob)) {
            for (Path p : ds) {
                if (Files.isRegularFile(p)) out.add(p);
            }
        } catch (IOException e) {
            onLoadFailure(dir, e);
        }
        return out;
    }

    /** Decode one cost file (shard or coarse) DIRECTLY into the grid's cost pyramid — no coarse re-merge. */
    private static void loadCostFile(RegionGrid grid, Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            CostPyramidCodec.decode(in, grid.pyramid());
        } catch (Throwable t) {
            onLoadFailure(file, t);
        }
    }

    /** Decode one resource file (shard or coarse) DIRECTLY into the grid's resource pyramid — no coarse re-merge. */
    private static void loadResourceFile(RegionGrid grid, Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            ResourcePyramidCodec.decode(in, grid.resourcePyramid());
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
     * The authoritative full flush (§5.2 #1) — write every live dimension's region tier (all non-empty shards +
     * both coarse files). Called on {@code SERVER_STOPPING} after the tick loop halts, so there is no concurrent
     * writer. Clears the dirty bookkeeping (everything is now on disk).
     */
    public static void flushAll(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            RegionGrid grid = RegionGrid.peek(level);
            if (grid == null) continue; // never planned/explored in this dimension → nothing to persist
            flushDimension(server, level, grid, null, true); // null shards = write ALL non-empty shards
            DIRTY_SHARDS.remove(level);
            COARSE_DIRTY.remove(level);
        }
    }

    /**
     * Write a dimension's shard + coarse files (each an independent atomic replace). {@code onlyShards} scopes the
     * shard writes: {@code null} writes every non-empty shard (the stop flush); a set writes only those shard keys
     * (the periodic flush; an empty set writes no shards). {@code writeCoarse} gates the two per-dimension coarse
     * files.
     */
    private static void flushDimension(MinecraftServer server, ServerLevel level, RegionGrid grid,
                                       Set<Long> onlyShards, boolean writeCoarse) {
        Path dir = dimDir(server, level);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            onFlushFailure(dir, e);
            return;
        }

        final CostPyramid cp = grid.pyramid();
        final ResourcePyramid rp = grid.resourcePyramid();

        // Cost shard files — one per shard that holds a built level-0..5 cost row.
        for (long key : enumerateCostShards(cp)) {
            if (onlyShards != null && !onlyShards.contains(key)) continue;
            final int sx = shardX(key), sz = shardZ(key);
            writeAtomic(dir.resolve("hpa." + sx + "." + sz + ".bin"),
                    out -> CostPyramidCodec.encodeShard(cp, sx, sz, out));
        }
        // Resource shard files — one per shard that holds a built level-0..5 resource tally.
        for (long key : enumerateResourceShards(rp)) {
            if (onlyShards != null && !onlyShards.contains(key)) continue;
            final int sx = shardX(key), sz = shardZ(key);
            writeAtomic(dir.resolve("res." + sx + "." + sz + ".bin"),
                    out -> ResourcePyramidCodec.encodeShard(rp, sx, sz, out));
        }

        // Per-dimension coarse files.
        if (writeCoarse) {
            if (hasBuiltCostLevel(cp, RegionAddress.MAX_COARSE_LEVEL)) {
                writeAtomic(dir.resolve(COST_COARSE_FILE), out -> CostPyramidCodec.encodeCoarse(cp, out));
            }
            if (hasBuiltResourceCoarse(rp)) {
                writeAtomic(dir.resolve(RESOURCE_COARSE_FILE), out -> ResourcePyramidCodec.encodeCoarse(rp, out));
            }
        }
    }

    /** Shard keys (level-5 region) that hold ≥1 built, record-bearing cost row across levels 0..5. */
    private static Set<Long> enumerateCostShards(CostPyramid p) {
        Set<Long> shards = new HashSet<>();
        for (int level = 0; level <= RegionAddress.SHARD_LEVEL; level++) {
            int rows = p.rowCount(level);
            for (int r = 0; r < rows; r++) {
                if (!p.isBuilt(level, r) || p.fragmentRecord(level, r) == null) continue;
                shards.add(shardKey(RegionAddress.shardOf(p.rowRX(level, r), level),
                                    RegionAddress.shardOf(p.rowRZ(level, r), level)));
            }
        }
        return shards;
    }

    /** Shard keys that hold ≥1 built resource tally across levels 0..5. */
    private static Set<Long> enumerateResourceShards(ResourcePyramid p) {
        Set<Long> shards = new HashSet<>();
        for (int level = 0; level <= RegionAddress.SHARD_LEVEL; level++) {
            int rows = p.rowCount(level);
            for (int r = 0; r < rows; r++) {
                if (!p.isBuilt(level, r)) continue;
                shards.add(shardKey(RegionAddress.shardOf(p.rowRX(level, r), level),
                                    RegionAddress.shardOf(p.rowRZ(level, r), level)));
            }
        }
        return shards;
    }

    private static boolean hasBuiltCostLevel(CostPyramid p, int level) {
        int rows = p.rowCount(level);
        for (int r = 0; r < rows; r++) {
            if (p.isBuilt(level, r) && p.fragmentRecord(level, r) != null) return true;
        }
        return false;
    }

    private static boolean hasBuiltResourceCoarse(ResourcePyramid p) {
        for (int level = RegionAddress.MAX_COARSE_LEVEL; level <= ResourcePyramid.RESOURCE_TOP_LEVEL; level++) {
            int rows = p.rowCount(level);
            for (int r = 0; r < rows; r++) {
                if (p.isBuilt(level, r)) return true;
            }
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
    // Periodic flush (crash insurance) + per-shard dirty tracking
    // ---------------------------------------------------------------------------------------------------

    /**
     * Mark the SHARD containing chunk {@code (chunkX, chunkZ)} as having unflushed changes, and the dimension's
     * coarse levels too (any leaf change re-merges up to {@link RegionAddress#MAX_COARSE_LEVEL}, §5.2). Called
     * from the tick-thread write sites ({@link com.orebit.mod.worldmodel.hpa.HpaMaintenance}); the next periodic
     * {@link #tick} (or the stop flush) writes the touched shards. Idempotent, cheap (two set adds), off any hot
     * search path.
     */
    public static void markDirty(ServerLevel level, int chunkX, int chunkZ) {
        long key = shardKey(RegionAddress.shardOf(chunkX, 0), RegionAddress.shardOf(chunkZ, 0));
        DIRTY_SHARDS.computeIfAbsent(level, l -> ConcurrentHashMap.newKeySet()).add(key);
        COARSE_DIRTY.add(level);
    }

    /**
     * The periodic-flush driver, wired off {@code onWorldTickEnd}. Every {@code hpa.persistIntervalTicks} ticks
     * for a level, if any shard was marked dirty (or the coarse levels changed) since the last flush, write those
     * shards + coarse files and clear the marks. A non-positive interval disables the periodic flush (the stop
     * flush still runs). Runs on the tick thread.
     */
    public static void tick(ServerLevel level) {
        int interval = ConfigLoader.config().persistIntervalTicks();
        if (interval <= 0) return;
        int t = TICKS_SINCE_FLUSH.merge(level, 1, Integer::sum);
        if (t < interval) return;
        TICKS_SINCE_FLUSH.put(level, 0);

        // Resolve the flush targets BEFORE claiming (removing) the dirty marks: if there is nothing to flush to
        // (no live grid, or no server), an early return here must NOT consume the marks — otherwise unflushed
        // changes would be silently dropped without ever hitting disk. The SERVER_STOPPING flush is the net.
        RegionGrid grid = RegionGrid.peek(level);
        if (grid == null) return;
        MinecraftServer server = level.getServer();
        if (server == null) return;

        Set<Long> dirty = DIRTY_SHARDS.remove(level);        // claim this interval's dirty shards
        boolean coarseDirty = COARSE_DIRTY.remove(level);
        boolean haveShards = dirty != null && !dirty.isEmpty();
        if (!haveShards && !coarseDirty) return;             // nothing changed since the last flush

        Set<Long> onlyShards = (dirty == null) ? Collections.emptySet() : dirty;
        flushDimension(server, level, grid, onlyShards, coarseDirty);
    }

    /** Drop all dirty/tick bookkeeping (server stop). */
    public static void clear() {
        DIRTY_SHARDS.clear();
        COARSE_DIRTY.clear();
        TICKS_SINCE_FLUSH.clear();
    }

    // ---------------------------------------------------------------------------------------------------
    // Shard-key packing (two ints → one long)
    // ---------------------------------------------------------------------------------------------------

    private static long shardKey(int shardX, int shardZ) {
        return ((long) shardX << 32) | (shardZ & 0xFFFFFFFFL);
    }

    private static int shardX(long key) {
        return (int) (key >> 32);
    }

    private static int shardZ(long key) {
        return (int) key;
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
     * The dimension id string, e.g. {@code "minecraft:overworld"}, through the {@link DimensionId} platform
     * seam — {@code ResourceKey.location()} was renamed to {@code identifier()} at the 1.21.11 deobfuscation,
     * so the version-divergent call is hidden behind the overlay.
     */
    private static String dimensionId(ServerLevel level) {
        return DimensionId.of(level);
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
