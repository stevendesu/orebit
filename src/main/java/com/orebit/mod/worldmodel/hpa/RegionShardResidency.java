package com.orebit.mod.worldmodel.hpa;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-dimension shard-residency index — the state that lets the pyramid merge drivers
 * ({@link PyramidMerger} / {@link com.orebit.mod.worldmodel.resource.ResourceMerger}) tell
 * <b>"a child region is absent because it is genuinely unexplored"</b> from
 * <b>"a child region is absent because its shard was persisted to disk but is not currently paged into RAM"</b>
 * (the Stage-2 bounded-region-RAM design, DESIGN-worldmodel-persistence.md — the clobber-guard invariant).
 *
 * <h2>Why this exists (the correctness invariant it enables)</h2>
 * The roll-up merge ({@code combineFragments} / {@code recomputeParent}) treats an <b>absent</b> child
 * ({@link CostPyramid#rowIfPresent} {@code < 0}) <b>optimistically</b> — a coarse cell over unexplored terrain
 * rolls up as open/empty rather than a wall, so the planner never refuses a real route through the unknown, and
 * an empty resource column stays empty. Under today's <b>eager-load-all</b> startup ({@code RegionPersistence}
 * loads every level 0..5 shard + the coarse levels) that optimism is correct: an absent child is always
 * genuinely unexplored, because everything persisted is resident.
 *
 * <p>Under a future <b>coarse-only startup</b> (a later Stage-2 increment loads only the coarse levels and pages
 * shard leaves in on demand), that assumption breaks: the first live chunk build re-merges a coarse cell from a
 * mostly-absent child set and would <b>overwrite the correct, fully-explored persisted coarse value with a
 * partial rollup</b> — because the merge cannot see that the missing children are sitting on disk, not missing
 * from the world. This index is the discriminator the merge consults to DEFER (leave the persisted coarse value
 * intact) rather than clobber.
 *
 * <h2>State</h2>
 * <ul>
 *   <li><b>{@code persistedShardIndex}</b> — the level-5 shard keys ({@link RegionAddress#SHARD_LEVEL}) that have
 *       a shard file on disk (populated from a directory listing at startup by {@code RegionPersistence} in the
 *       startup-flip increment).</li>
 *   <li><b>{@code residentShards}</b> — the shard keys currently paged into RAM (their rows interned in the
 *       pyramids).</li>
 *   <li><b>{@code pending}</b> — per-level reconcile-pending set: coarse nodes whose recompute was DEFERRED
 *       because a persisted-non-resident child was seen. A later increment drains this to reconcile those nodes
 *       once the missing shards page in.</li>
 * </ul>
 * A shard is <b>persisted-non-resident</b> iff it has a file on disk AND is not currently paged in — exactly the
 * case where the merge must not overwrite the persisted coarse value.
 *
 * <h2>Byte-identical no-op under eager-load-all (this increment)</h2>
 * For this increment the sets are left <b>empty</b> by default: {@code RegionPersistence} does not yet populate
 * {@code persistedShardIndex}, so {@link #isPersistedNonResident} always returns {@code false}, the guard in the
 * merge drivers never fires, and behaviour is byte-identical to today's eager-load-all merge. The value here is
 * the API + the guard consulting it; the wiring lands with the startup-flip increment.
 *
 * <h2>House style</h2>
 * Pure data, <b>no Minecraft API</b> (so the merge drivers and their headless unit tests stay MC-free — the
 * per-dimension registry keyed by {@code ServerLevel}, mirroring {@link RegionGrid}'s {@code BY_LEVEL} idiom,
 * lives with {@code RegionPersistence}/{@code RegionGrid} in the startup-flip increment, not here). The sets are
 * {@link ConcurrentHashMap}-backed because {@code combineFragments} can run on the planner thread
 * ({@code RegionGrid.ensureLevel}) as well as the tick thread ({@code mergeUpFragments}); marking is cold (once
 * per deferred coarse node), off any per-node search hot path.
 */
public final class RegionShardResidency {

    /** Level-5 shard keys ({@link #shardKey}) that have a persisted file on disk. */
    private final Set<Long> persistedShardIndex = ConcurrentHashMap.newKeySet();
    /** Level-5 shard keys currently paged into RAM (their rows interned in the pyramids). */
    private final Set<Long> residentShards = ConcurrentHashMap.newKeySet();
    /** Coarse nodes whose merge was deferred by the clobber-guard, keyed by level → set of packed (rx,ry,rz). */
    private final Map<Integer, Set<Long>> pending = new ConcurrentHashMap<>();
    /**
     * Shard keys the guard / grid have requested be paged in (the on-demand load queue — Stage-2 atomic lazy-load
     * increment). A {@link ConcurrentSkipListSet} so the loader (in {@code persistence}) drains it in a stable,
     * insertion-order-independent ascending sequence via {@link #pollLoadRequest} — the same deterministic-drain
     * discipline {@code HpaMaintenance}'s dirty set uses. The producing side (the clobber-guard on the tick /
     * planner thread, {@code RegionGrid.ensureLeaf}) only ENQUEUES; the consuming loader lives one package up, so
     * this queue is how a load is requested WITHOUT an {@code hpa → persistence} dependency (the layering the
     * whole guard was built around). Cold: one add per deferred coarse node / unresident-leaf touch.
     */
    private final ConcurrentSkipListSet<Long> loadRequests = new ConcurrentSkipListSet<>();

    /**
     * Per-resident-shard <b>last-touch recency stamp</b> (Stage-2 cold-shard eviction, increment 4) — the LRU
     * ordering the evictor ({@code RegionEvictor}) picks the COLDEST evictable shard by. Keyed by {@link #shardKey}
     * → the {@link #touchClock} value at the last touch; a higher value is more-recently touched. NOT a wall clock
     * and NOT a timeout (the no-arbitrary-timers rule): it is a pure monotonic ordering, stamped on a shard load
     * ({@link #markResident}) and on the region build/read path that (re)builds one of its leaves
     * ({@code RegionGrid.rebuildLeaf} → {@link #touchShard}). An unstamped shard reads as {@code 0} (oldest), so a
     * never-touched persisted shard sorts before any touched one. Dropped on {@link #markEvicted} (an evicted shard
     * is no longer resident, so it has no recency).
     */
    private final Map<Long, Long> lastTouched = new ConcurrentHashMap<>();
    /** Monotonic touch sequence issuer (see {@link #lastTouched}); a recency ordering, not a clock. */
    private final AtomicLong touchClock = new AtomicLong();

    public RegionShardResidency() {}

    /** Pack a level-5 shard coord pair into one key (identical layout to {@code RegionPersistence.shardKey}). */
    public static long shardKey(int shardX, int shardZ) {
        return ((long) shardX << 32) | (shardZ & 0xFFFFFFFFL);
    }

    /** The shard X packed into a {@link #shardKey}. */
    public static int shardX(long key) {
        return (int) (key >> 32);
    }

    /** The shard Z packed into a {@link #shardKey}. */
    public static int shardZ(long key) {
        return (int) key;
    }

    // ---------------------------------------------------------------------------------------------------
    // The discriminator the merge consults
    // ---------------------------------------------------------------------------------------------------

    /**
     * {@code true} iff the level-5 shard {@code (shardX, shardZ)} has a persisted file on disk AND is not
     * currently paged into RAM — i.e. an absent child in that shard is "on disk, not yet loaded", NOT "genuinely
     * unexplored". This is the exact condition under which the merge must DEFER rather than clobber the persisted
     * coarse value. Empty by default (this increment) ⇒ always {@code false} ⇒ the guard is a no-op.
     */
    public boolean isPersistedNonResident(int shardX, int shardZ) {
        final long k = shardKey(shardX, shardZ);
        return persistedShardIndex.contains(k) && !residentShards.contains(k);
    }

    /** Whether the shard has a persisted file on disk (regardless of residency). */
    public boolean isPersisted(int shardX, int shardZ) {
        return persistedShardIndex.contains(shardKey(shardX, shardZ));
    }

    /** Whether the shard is currently paged into RAM. */
    public boolean isResident(int shardX, int shardZ) {
        return residentShards.contains(shardKey(shardX, shardZ));
    }

    // ---------------------------------------------------------------------------------------------------
    // Mutators — populated by RegionPersistence in the startup-flip increment
    // ---------------------------------------------------------------------------------------------------

    /**
     * Replace the persisted-shard index with {@code shardKeys} (level-5 {@link #shardKey}s), e.g. from a
     * startup directory listing of the dimension's shard files. Copies the contents (does not retain the
     * argument).
     */
    public void setPersistedIndex(Collection<Long> shardKeys) {
        persistedShardIndex.clear();
        persistedShardIndex.addAll(shardKeys);
    }

    /** Record that shard {@code (shardX, shardZ)} has a persisted file on disk. */
    public void markPersisted(int shardX, int shardZ) {
        persistedShardIndex.add(shardKey(shardX, shardZ));
    }

    /** Record that shard {@code (shardX, shardZ)} is now paged into RAM (its rows interned). Also drops any pending
     *  load request for it (it is loaded now) and stamps it most-recently-touched (a fresh page-in). */
    public void markResident(int shardX, int shardZ) {
        final long k = shardKey(shardX, shardZ);
        residentShards.add(k);
        loadRequests.remove(k);
        lastTouched.put(k, touchClock.incrementAndGet());
    }

    /** Record that shard {@code (shardX, shardZ)} was evicted from RAM (rows dropped; the file stays on disk).
     *  Drops its recency stamp (it is no longer resident). */
    public void markEvicted(int shardX, int shardZ) {
        final long k = shardKey(shardX, shardZ);
        residentShards.remove(k);
        lastTouched.remove(k);
    }

    // ---------------------------------------------------------------------------------------------------
    // LRU recency (Stage-2 cold-shard eviction, increment 4) — a monotonic touch ordering, NOT a timer.
    // ---------------------------------------------------------------------------------------------------

    /**
     * Stamp shard {@code (shardX, shardZ)} as touched NOW (a monotonic recency bump), so the evictor treats it as
     * more-recently-used than any lower-stamped shard. Called on the region build/read path that actually (re)builds
     * one of the shard's leaves ({@code RegionGrid.rebuildLeaf}) and by {@link #markResident} on a fresh page-in.
     * Cold (once per leaf build / shard load), thread-safe (a {@link ConcurrentHashMap} put + an {@link AtomicLong}
     * increment) — never on the per-node search hot path.
     */
    public void touchShard(int shardX, int shardZ) {
        lastTouched.put(shardKey(shardX, shardZ), touchClock.incrementAndGet());
    }

    /**
     * The last-touch recency stamp of shard {@code (shardX, shardZ)}, or {@code 0} (oldest) if never touched — the
     * key the evictor sorts evictable shards by (lowest == coldest == evicted first).
     */
    public long lastTouched(int shardX, int shardZ) {
        final Long v = lastTouched.get(shardKey(shardX, shardZ));
        return v == null ? 0L : v;
    }

    // ---------------------------------------------------------------------------------------------------
    // Reconcile-pending set (drained by a later increment once the missing shards page in)
    // ---------------------------------------------------------------------------------------------------

    /**
     * Record that the coarse node {@code (level, rx, ry, rz)}'s merge was DEFERRED because a persisted-non-resident
     * child was seen — so a later increment can reconcile (re-merge) it once the missing shards are paged in. Keyed
     * by level; the node is the packed {@link RegionAddress#packLevelKey}.
     */
    public void markReconcilePending(int level, int rx, int ry, int rz) {
        pending.computeIfAbsent(level, l -> ConcurrentHashMap.newKeySet())
                .add(RegionAddress.packLevelKey(rx, ry, rz));
    }

    /** Whether {@code (level, rx, ry, rz)} is currently marked reconcile-pending. */
    public boolean isReconcilePending(int level, int rx, int ry, int rz) {
        final Set<Long> s = pending.get(level);
        return s != null && s.contains(RegionAddress.packLevelKey(rx, ry, rz));
    }

    /** Number of reconcile-pending nodes at {@code level} (0 if none). */
    public int pendingCount(int level) {
        final Set<Long> s = pending.get(level);
        return s == null ? 0 : s.size();
    }

    /** Whether any node at any level is reconcile-pending. */
    public boolean hasPending() {
        for (Set<Long> s : pending.values()) {
            if (!s.isEmpty()) return true;
        }
        return false;
    }

    /**
     * Remove and return the reconcile-pending set for {@code level} (packed {@link RegionAddress#packLevelKey}s),
     * or an empty set if none — the drain seam for the reconciliation increment.
     */
    public Set<Long> drainPending(int level) {
        final Set<Long> s = pending.remove(level);
        return s == null ? Collections.emptySet() : s;
    }

    // ---------------------------------------------------------------------------------------------------
    // On-demand load-request queue (Stage-2 atomic lazy-load) — enqueued by the guard / grid on the tick /
    // planner thread, drained by the loader (persistence) on the tick thread. Keeps the hpa→persistence
    // dependency out: the guard requests a load by adding a key here, never by calling the loader.
    // ---------------------------------------------------------------------------------------------------

    /**
     * Request that shard {@code (shardX, shardZ)} be paged into RAM. Idempotent (a set) and a no-op if the shard
     * is already resident. Called by the clobber-guard when it DEFERS on a persisted-non-resident child, and by
     * {@code RegionGrid.ensureLeaf} when a planner touches an unbuilt leaf whose shard is persisted-non-resident —
     * both COLD paths (no per-node-search hot loop). No disk I/O here: the {@code RegionShardLoader} drain does the
     * atomic load next tick.
     */
    public void enqueueLoad(int shardX, int shardZ) {
        final long k = shardKey(shardX, shardZ);
        if (residentShards.contains(k)) return; // already paged in
        loadRequests.add(k);
    }

    /** Whether any shard load is currently requested (the loader's cheap per-tick gate). */
    public boolean hasLoadRequests() {
        return !loadRequests.isEmpty();
    }

    /**
     * Atomically claim (remove + return) the least-keyed requested shard, or {@code null} if none — the loader's
     * budgeted drain seam. Ascending order makes the drain SEQUENCE a deterministic function of the queue's
     * contents (the {@code HpaMaintenance.pollFirst} discipline).
     */
    public Long pollLoadRequest() {
        return loadRequests.pollFirst();
    }
}
