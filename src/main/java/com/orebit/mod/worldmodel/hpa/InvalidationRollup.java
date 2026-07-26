package com.orebit.mod.worldmodel.hpa;

import java.util.Arrays;

/**
 * The Phase-2b <b>invalidation ROLL-UP fold</b> (DESIGN-persisted-invalidation-memory.md §4b): when a
 * crossing invalidation is recorded into a {@link RegionCrossingMemory}, decide AT RECORD TIME whether the
 * PARENT-level crossing it sits under is now dead — dead iff <b>all</b> of its constituent child-level
 * crossings are dead — and if so record a {@link RegionCrossingMemory#PROV_ROLLED_UP} row at the parent
 * level, recursing upward to {@link RegionAddress#MAX_COARSE_LEVEL} (a ROLLED_UP row at level {@code n} may
 * itself complete level {@code n+1}'s kill-set).
 *
 * <h2>Why</h2>
 * Without the fold, a proven-dead wall only ever accumulates level-0 rows: a coarse skeleton keeps proposing
 * the PARENT hop over the wall, each refinement re-walks the dead L0 crossings, and only the per-plan
 * escalation (session-only {@code PROV_ESCALATION} inference) learns better — and forgets on restart. The
 * fold turns a completed set of child proofs into a parent-level THEOREM that persists ({@code
 * CostPyramidCodec} writes {@code PROOF} and {@code ROLLED_UP} rows; only {@code ESCALATION} is filtered),
 * so a re-seeded plan refuses the parent hop outright.
 *
 * <h2>Constituents (the kill-set)</h2>
 * For a parent crossing {@code (parentA, fragPA) → (parentB, fragPB)} across face {@code F}, the
 * constituents are exactly the child-level <b>walk openings</b> through that face the region A* would
 * realize: for each child of {@code parentA} flush with {@code F}
 * ({@link PyramidMerger#childFlushWithParentFace}) paired with its face-neighbour child of {@code parentB},
 * every fragment pair {@code (fa, fb)} where {@code fa} belongs to {@code fragPA}, {@code fb} to
 * {@code fragPB}, both touch the shared face, and their footprints {@linkplain PyramidMerger#overlap
 * overlap} — the same {@code touchesFace + footprintsOverlap} predicates {@code RegionPathfinder}'s relax
 * uses for its walk edges. Children with no fragment records contribute the same items the pyramid merge
 * gives them: a uniform AIR/WATER or collapsed-but-passable child is one full-face item keyed fragment 0
 * (exactly how the A* keys a uniform node); a uniform SOLID child contributes none (a wall).
 *
 * <p><b>The frontier property.</b> An UNBUILT/unloaded child reads as the optimistic uniform-AIR synthetic
 * (full-face footprint) — an <b>always-alive</b> constituent that no recorded row can kill, so a fold at an
 * exploration frontier never kills the parent. And a crossing with <b>zero</b> enumerated constituents
 * (an all-sealed face — the A* would only propose canBreak dig edges there) is never killed either: the
 * fold proves only what the walk-opening enumeration covers, and killing on vacuous truth would blacklist a
 * dig a stronger proof never examined.
 *
 * <h2>Sig rule (soundness)</h2>
 * The fold is evaluated at {@code S0}, the effective sig of the row being recorded. A constituent counts as
 * dead iff the memory holds a {@code PROV_PROOF}/{@code PROV_ROLLED_UP} row for that exact crossing whose
 * sig DOMINATES-or-equals {@code S0} ({@link RegionCrossingMemory#holdsProofDominating}); {@code
 * PROV_ESCALATION} rows never count (cascade inference, not proof). The resulting ROLLED_UP row carries sig
 * {@code S0}. This is <b>sound</b>: seeding binds a bot {@code C} iff the row's sig dominates {@code C}'s,
 * and dominance is transitive (each disjoint sig field is ordered independently) — so for any {@code C} the
 * parent row binds, every constituent's sig dominates {@code S0} dominates {@code C}, i.e. every walk
 * opening through the face is proven dead FOR {@code C}. And it is <b>conservative</b>: a constituent dead
 * only under a weaker or incomparable sig does not count, so the fold under-approximates the true dead set
 * rather than ever over-binding a stronger bot.
 *
 * <h2>Containment (which parent fragment a child fragment belongs to)</h2>
 * {@link PyramidMerger#combineFragments} does not retain its child→parent-fragment mapping (thread-local
 * scratch, reset every merge), so the fold RE-DERIVES it on demand: the same item gathering (same child
 * order, same uniform/collapsed/unbuilt item rules) and the same internal-face union-find, whose component
 * numbering is partition-invariant (ids are assigned in order of each component's least item index), so the
 * derived component ids equal the parent record's live fragment ids. A guard cross-checks the derived
 * component count against the parent record's {@code fragmentCount} and bails on mismatch (a deferred
 * Stage-2 merge / stale record — no fold rather than a wrong one). No new retained mapping, no new
 * persisted data.
 *
 * <h2>Cost &amp; threading</h2>
 * Runs ONLY on the record path ({@code HierarchicalRegionPlan.onBlocked}, after the level-0 {@code
 * PROV_PROOF} record) — a few per goal, tick-confined like every region-tier write. Zero work on region-A*
 * relax or any per-tick path. Per-fold work is bounded: two ≤{@link PyramidMerger#MAX_ITEMS}-item
 * union-finds plus the pair enumeration, per level climbed. Allocation-free past one-time thread-local
 * scratch (the {@code RollupFoldBenchmark} pins the ns/op envelope). The escalation record site
 * ({@code blameTubeConfined}) deliberately does NOT invoke the fold — ESCALATION rows neither trigger nor
 * count.
 *
 * <p>Lives in {@code worldmodel/hpa} (needs {@link CostPyramid}/{@link RegionFragments} internals); like
 * {@link RegionCrossingMemory} it takes the dominance order as an injected
 * {@link RegionCrossingMemory.Dominance} so the layer keeps zero pathfinding imports — the consumer
 * ({@code HierarchicalRegionPlan}) passes {@code BotCaps::sigDominates}.
 */
public final class InvalidationRollup {

    private InvalidationRollup() {}

    /** The region part of a fragment-node key — {@code packLevelKey} bits 0..48 (mirrors
     *  {@link RegionCrossingMemory}'s mask; the 6-bit fragment id sits at bits 49..54).
     *  (2026-07 packLevelKey repack: ry narrowed 6→5 bits, region 0..48, frag shift 50→49.) */
    private static final long REGION_MASK = (1L << 49) - 1;
    /** The fragment id's shift in a fragment-node key (bits 49..54). */
    private static final int FRAG_SHIFT = 49;

    /**
     * The {@code (region, fragment)} node key — MUST stay bit-identical to
     * {@code RegionPathfinder.fragmentNodeKey} ({@code packLevelKey} XOR the 6-bit fragment id at bits
     * 49..54; pinned by {@code RollupFoldTest.keyLayoutMatchesRegionPathfinder}). Duplicated here because
     * {@code worldmodel/hpa} must not import {@code pathfinding} (the layering the injected Dominance
     * exists for).
     */
    static long fragmentKey(int rx, int ry, int rz, int frag) {
        return RegionAddress.packLevelKey(rx, ry, rz) ^ ((long) (frag & 0x3F) << FRAG_SHIFT);
    }

    /** The fragment id of a fragment-node key ({@code packLevelKey} bits 49+ are zero, so XOR reads back). */
    static int fragOf(long key) {
        return (int) ((key >>> FRAG_SHIFT) & 0x3F);
    }

    // ---------------------------------------------------------------------------------------------------
    // Per-side scratch (one parent's gathered child items + containment union-find). Thread-local like
    // PyramidMerger's merge scratch — the fold is tick-confined, this just keeps tests/tools safe too.
    // ---------------------------------------------------------------------------------------------------

    private static final class Side {
        final int[] slot = new int[PyramidMerger.MAX_ITEMS];       // child slot i (bit0=X, bit1=Z, bit2=Y)
        final int[] mask = new int[PyramidMerger.MAX_ITEMS];       // 6-bit face mask
        final int[] fp = new int[PyramidMerger.MAX_ITEMS * 6];     // packed footprint per face (NO_FACE ok)
        final int[] frag = new int[PyramidMerger.MAX_ITEMS];       // the child's OWN fragment id (0 synthetic)
        final boolean[] unbuilt = new boolean[PyramidMerger.MAX_ITEMS]; // synthetic from an UNBUILT child
        final int[] uf = new int[PyramidMerger.MAX_ITEMS];
        final int[] comp = new int[PyramidMerger.MAX_ITEMS];       // derived parent-fragment id per item
        int n;
        int compCount;
        boolean collapsed;
        /** false ⇒ per-item {@link #comp}; true ⇒ every item maps to parent fragment 0 (uniform/collapsed
         *  /unbuilt parent record — exactly how the A* keys such a node). */
        boolean allZero;
        int prx, pry, prz; // the gathered parent's region coords (for child-coord recovery)
        int children;      // childCount at the parent's level (8 octree / 4 quadtree)
    }

    private static final ThreadLocal<Side> SIDE_A = ThreadLocal.withInitial(Side::new);
    private static final ThreadLocal<Side> SIDE_B = ThreadLocal.withInitial(Side::new);
    private static final ThreadLocal<long[]> PARENT_KEYS = ThreadLocal.withInitial(() -> new long[2]);

    // ---------------------------------------------------------------------------------------------------
    // Entry point
    // ---------------------------------------------------------------------------------------------------

    /**
     * Fold the just-recorded dead crossing {@code fromKey → toKey} at {@code level} (sig {@code capsSig},
     * provenance {@code PROOF} or {@code ROLLED_UP} — never call this for an ESCALATION record) upward:
     * while the parent-level crossing's kill-set is complete, record a {@code PROV_ROLLED_UP} row for it in
     * {@code mem} (sig {@code capsSig}) and continue from that row, up to
     * {@link RegionAddress#MAX_COARSE_LEVEL}. Returns the number of ROLLED_UP rows recorded (0 = the parent
     * stayed alive / the crossing was parent-internal). Read-only on the pyramid (no interning, no builds).
     */
    public static int foldFrom(CostPyramid p, RegionCrossingMemory mem, int level, long fromKey, long toKey,
                               long capsSig, RegionCrossingMemory.Dominance dominance) {
        int recorded = 0;
        long f = fromKey;
        long t = toKey;
        final long[] parentKeys = PARENT_KEYS.get();
        while (level < RegionAddress.MAX_COARSE_LEVEL) {
            if (!foldOnce(p, mem, level, f, t, capsSig, dominance, parentKeys)) {
                break;
            }
            mem.record(level + 1, parentKeys[0], parentKeys[1], capsSig,
                    RegionCrossingMemory.PROV_ROLLED_UP, dominance);
            recorded++;
            f = parentKeys[0];
            t = parentKeys[1];
            level++;
        }
        return recorded;
    }

    /**
     * One fold step: derive the parent crossing of {@code fromKey → toKey} at {@code level} and test its
     * kill-set. Returns {@code true} with {@code out[0]/out[1]} = the parent {@code (from, to)} fragment-node
     * keys iff the parent crossing is DEAD at {@code capsSig}; {@code false} on any of: parent-internal
     * crossing (same parent cell, incl. the intra-region mine case), a live/unbuilt constituent, zero
     * constituents, or a containment mismatch (stale/deferred parent record — bail, never guess).
     */
    private static boolean foldOnce(CostPyramid p, RegionCrossingMemory mem, int level, long fromKey,
                                    long toKey, long capsSig, RegionCrossingMemory.Dominance dominance,
                                    long[] out) {
        final long fromCell = fromKey & REGION_MASK;
        final long toCell = toKey & REGION_MASK;
        final int caRX = RegionAddress.unpackRX(fromCell), caRY = RegionAddress.unpackRY(fromCell),
                  caRZ = RegionAddress.unpackRZ(fromCell);
        final int cbRX = RegionAddress.unpackRX(toCell), cbRY = RegionAddress.unpackRY(toCell),
                  cbRZ = RegionAddress.unpackRZ(toCell);

        // The crossing face, from the child-cell delta. A non-face-adjacent pair (the intra-region
        // fragment→fragment mine crossing has delta 0) maps to no parent face — nothing to fold.
        final int face = faceOf(cbRX - caRX, cbRY - caRY, cbRZ - caRZ);
        if (face < 0) {
            return false;
        }

        final int parentLevel = level + 1;
        final int pAx = RegionAddress.parentRX(caRX), pAz = RegionAddress.parentRZ(caRZ),
                  pAy = RegionAddress.parentRY(caRY, level);
        final int pBx = RegionAddress.parentRX(cbRX), pBz = RegionAddress.parentRZ(cbRZ),
                  pBy = RegionAddress.parentRY(cbRY, level);
        if (pAx == pBx && pAy == pBy && pAz == pBz) {
            return false; // parent-internal crossing — no parent face to die
        }
        // Face-adjacent children with distinct parents ⇒ the parents are neighbours across the same face.
        if (pBx != RegionAddress.neighborRX(pAx, face) || pBy != RegionAddress.neighborRY(pAy, face)
                || pBz != RegionAddress.neighborRZ(pAz, face)) {
            return false; // defensive: malformed keys
        }

        final Side a = SIDE_A.get();
        final Side b = SIDE_B.get();
        if (!gather(p, parentLevel, pAx, pAy, pAz, a) || !gather(p, parentLevel, pBx, pBy, pBz, b)) {
            return false; // Stage-2 persisted-non-resident child — defer, like combineFragments' guard
        }

        // Locate the trigger crossing's items to name the parent fragments the crossing belongs to.
        final int fPA = parentFragOfChild(a, caRX, caRY, caRZ, fragOf(fromKey));
        final int fPB = parentFragOfChild(b, cbRX, cbRY, cbRZ, fragOf(toKey));
        if (fPA < 0 || fPB < 0) {
            return false; // trigger fragment not derivable (renumbered/stale) — bail
        }

        // Enumerate the kill-set: every walk opening through the parent face between fragPA and fragPB.
        final int opp = RegionAddress.opposite(face);
        int constituents = 0;
        for (int ka = 0; ka < a.n; ka++) {
            if (parentFragOf(a, ka) != fPA) continue;
            final int sa = a.slot[ka];
            if (!PyramidMerger.childFlushWithParentFace(face, sa & 1, (sa >> 2) & 1, (sa >> 1) & 1, a.children)) continue;
            if (((a.mask[ka] >> face) & 1) == 0) continue;
            for (int kb = 0; kb < b.n; kb++) {
                if (parentFragOf(b, kb) != fPB) continue;
                final int sb = b.slot[kb];
                if (!PyramidMerger.childFlushWithParentFace(opp, sb & 1, (sb >> 2) & 1, (sb >> 1) & 1, b.children)) continue;
                if (((b.mask[kb] >> opp) & 1) == 0) continue;
                if (!pairedAcross(face, sa, sb, a.children)) continue;
                if (!PyramidMerger.overlap(a.fp[ka * 6 + face], b.fp[kb * 6 + opp])) continue;

                constituents++;
                if (a.unbuilt[ka] || b.unbuilt[kb]) {
                    return false; // frontier optimism: an unbuilt child is an always-alive constituent
                }
                final long cFrom = fragmentKey(RegionAddress.childRX(a.prx, sa),
                        RegionAddress.childRY(a.pry, sa, parentLevel),
                        RegionAddress.childRZ(a.prz, sa), a.frag[ka]);
                final long cTo = fragmentKey(RegionAddress.childRX(b.prx, sb),
                        RegionAddress.childRY(b.pry, sb, parentLevel),
                        RegionAddress.childRZ(b.prz, sb), b.frag[kb]);
                if (!mem.holdsProofDominating(level, cFrom, cTo, capsSig, dominance)) {
                    return false; // a live constituent — the parent survives
                }
            }
        }
        if (constituents == 0) {
            return false; // vacuous kill-set (all-sealed face) — never kill what was never enumerated
        }
        out[0] = fragmentKey(pAx, pAy, pAz, fPA);
        out[1] = fragmentKey(pBx, pBy, pBz, fPB);
        return true;
    }

    /** The canonical face for a unit child-cell step, or −1 when not face-adjacent. */
    private static int faceOf(int dx, int dy, int dz) {
        if (dy == 0 && dz == 0) {
            if (dx == 1) return 1;
            if (dx == -1) return 0;
        } else if (dx == 0 && dz == 0) {
            if (dy == 1) return 3;
            if (dy == -1) return 2;
        } else if (dx == 0 && dy == 0) {
            if (dz == 1) return 5;
            if (dz == -1) return 4;
        }
        return -1;
    }

    /**
     * Whether child slots {@code sa} (flush with {@code face}) and {@code sb} (flush with the opposite face,
     * in the neighbouring parent) are face-ADJACENT: equal on every non-face axis bit. The face axis itself
     * is pinned by the flush checks.
     */
    private static boolean pairedAcross(int face, int sa, int sb, int children) {
        final int axisMask = face <= 1 ? 1 : (face <= 3 ? 4 : 2); // X→bit0, Y→bit2, Z→bit1
        final int bits = children == 8 ? 7 : 3;
        return ((sa ^ sb) & bits & ~axisMask) == 0;
    }

    // ---------------------------------------------------------------------------------------------------
    // Containment re-derivation (the trimmed combineFragments-equivalent union-find)
    // ---------------------------------------------------------------------------------------------------

    /**
     * Gather parent {@code (parentLevel, prx, pry, prz)}'s child items and derive the child-fragment →
     * parent-fragment containment, mirroring {@link PyramidMerger#combineFragments}'s item pass and
     * internal-face union-find exactly (same child order, same uniform/collapsed/unbuilt item rules, same
     * adjacency/overlap unions) so the derived component ids equal the live parent record's fragment ids.
     * Skips the footprint projection and every write — read-only. Returns {@code false} only on the Stage-2
     * deferral condition (an absent/unbuilt child in a persisted-non-resident shard — the live record may be
     * the persisted one, so containment cannot be trusted) or a component-count mismatch against the parent
     * record ({@code allZero} handling aside) — the caller then skips the fold.
     */
    private static boolean gather(CostPyramid p, int parentLevel, int prx, int pry, int prz, Side s) {
        final int childLevel = parentLevel - 1;
        final int children = RegionAddress.childCount(parentLevel);
        s.prx = prx; s.pry = pry; s.prz = prz;
        s.children = children;
        s.n = 0;
        s.collapsed = false;

        final RegionShardResidency residency = p.residency();
        int n = 0;
        for (int i = 0; i < children; i++) {
            final int crx = RegionAddress.childRX(prx, i);
            final int crz = RegionAddress.childRZ(prz, i);
            final int cry = RegionAddress.childRY(pry, i, parentLevel);
            final int childRow = p.rowIfPresent(childLevel, crx, cry, crz);
            final boolean built = childRow >= 0 && p.isBuilt(childLevel, childRow);
            final RegionFragments rf = built ? p.fragmentRecord(childLevel, childRow) : null;

            if (rf == null) {
                // Mirror the Stage-2 clobber-guard: if this absent/unbuilt child's shard is persisted but not
                // resident, the parent's live record may be the (fuller) persisted one — containment derived
                // from the partial children would misnumber, so refuse the fold. Under eager-load-all the
                // persisted index is empty and this never fires.
                if (residency != null && childLevel <= RegionAddress.SHARD_LEVEL
                        && residency.isPersistedNonResident(RegionAddress.shardOf(crx, childLevel),
                                RegionAddress.shardOf(crz, childLevel))) {
                    return false;
                }
                n = addSynthetic(s, n, i, true);
                continue;
            }
            switch (rf.kind()) {
                case RegionFragments.KIND_SOLID:
                    break; // a wall — no item
                case RegionFragments.KIND_AIR:
                case RegionFragments.KIND_WATER:
                    n = addSynthetic(s, n, i, false);
                    break;
                default: // MIXED
                    final int fc = rf.fragmentCount();
                    if (fc == 0) {
                        if (rf.passFrac() > 0) {
                            n = addSynthetic(s, n, i, false);
                        }
                    } else {
                        for (int f = 0; f < fc && n < PyramidMerger.MAX_ITEMS; f++) {
                            final int base = n * 6;
                            s.slot[n] = i;
                            s.mask[n] = rf.faceMask(f);
                            for (int face = 0; face < 6; face++) {
                                s.fp[base + face] = rf.footprint(f, face);
                            }
                            s.frag[n] = f;
                            s.unbuilt[n] = false;
                            n++;
                        }
                    }
                    break;
            }
        }
        s.n = n;

        // Union-find across the parent's internal split faces — byte-for-byte the combineFragments loop.
        final int[] uf = s.uf;
        for (int k = 0; k < n; k++) uf[k] = k;
        for (int x = 0; x < n; x++) {
            for (int y = x + 1; y < n; y++) {
                final int sx = s.slot[x], sy = s.slot[y];
                final int xor = sx ^ sy;
                if (xor != 1 && xor != 2 && xor != 4) continue;
                final int axis = xor == 1 ? 0 : (xor == 2 ? 1 : 2);
                if (axis == 2 && children == 4) continue;
                final int lo = (sx & xor) == 0 ? x : y;
                final int hi = lo == x ? y : x;
                final int fLo = PyramidMerger.plusFace(axis);
                final int fHi = PyramidMerger.minusFace(axis);
                if (((s.mask[lo] >> fLo) & 1) == 0) continue;
                if (((s.mask[hi] >> fHi) & 1) == 0) continue;
                if (PyramidMerger.overlap(s.fp[lo * 6 + fLo], s.fp[hi * 6 + fHi])) PyramidMerger.union(uf, x, y);
            }
        }

        // Components numbered in order of least item index — combineFragments' partition-invariant numbering.
        final int[] comp = s.comp;
        Arrays.fill(comp, 0, n, -1);
        int compCount = 0;
        for (int k = 0; k < n; k++) {
            final int root = PyramidMerger.find(uf, k);
            int c = comp[root];
            if (c == -1) {
                if (compCount >= RegionFragments.MAX_FRAGMENTS) { s.collapsed = true; continue; }
                c = compCount++;
                comp[root] = c;
            }
            comp[k] = c;
        }
        s.compCount = compCount;

        // Mapping mode from the LIVE parent record — the ids consumers key crossings by.
        final int parentRow = p.rowIfPresent(parentLevel, prx, pry, prz);
        final RegionFragments prf = (parentRow >= 0 && p.isBuilt(parentLevel, parentRow))
                ? p.fragmentRecord(parentLevel, parentRow) : null;
        final boolean recordHasFrags =
                prf != null && prf.kind() == RegionFragments.KIND_MIXED && prf.fragmentCount() > 0;
        if (recordHasFrags) {
            if (s.collapsed || s.compCount != prf.fragmentCount()) {
                return false; // stale/deferred record — the derived partition cannot be trusted as its ids
            }
            s.allZero = false;
        } else {
            // Uniform kind, collapsed mass, fc==0, or unbuilt/absent record: the A* keys such a node's single
            // abstract fragment as 0, so every item maps to parent fragment 0.
            s.allZero = true;
        }
        return true;
    }

    /** Append one synthetic full-face item (uniform-open or unbuilt child) at child slot {@code i}. */
    private static int addSynthetic(Side s, int n, int i, boolean unbuilt) {
        if (n >= PyramidMerger.MAX_ITEMS) return n;
        s.slot[n] = i;
        s.mask[n] = 0x3F;
        final int base = n * 6;
        for (int f = 0; f < 6; f++) s.fp[base + f] = RegionFragments.NO_FACE;
        s.frag[n] = 0;
        s.unbuilt[n] = unbuilt;
        return n + 1;
    }

    /** The parent fragment id item {@code k} belongs to (derived component, or 0 in all-zero mode). */
    private static int parentFragOf(Side s, int k) {
        return s.allZero ? 0 : s.comp[k];
    }

    /**
     * The parent fragment containing child fragment {@code (crx, cry, crz, frag)}, from the gathered side,
     * or −1 when no matching item exists (the child's record no longer carries that fragment id — a rebuild
     * renumbered it, or the crossing named a fragment the merge never saw).
     */
    private static int parentFragOfChild(Side s, int crx, int cry, int crz, int frag) {
        final int bitX = crx & 1;
        final int bitZ = crz & 1;
        final int bitY = s.children == 8 ? (cry & 1) : 0;
        final int slot = bitX | (bitZ << 1) | (bitY << 2);
        for (int k = 0; k < s.n; k++) {
            if (s.slot[k] == slot && s.frag[k] == frag) {
                return parentFragOf(s, k);
            }
        }
        return -1;
    }

    /**
     * The parent fragment id (at {@code parentLevel}) that child fragment {@code (crx,cry,crz):frag} at
     * {@code parentLevel-1} merged into — the same gather + internal-face union-find the fold uses, exposed
     * read-only for the coarse start/goal anchor walk ({@link RegionGrid#containedFragment}): exact membership
     * walked up the pyramid instead of a nearest-centroid guess. Returns {@code 0} through an all-zero parent
     * (uniform/collapsed/fragmentless record — exactly how the A* keys such a node) and {@code -1} when
     * containment cannot be trusted (Stage-2 deferral, a stale record's component-count mismatch, or a child
     * fragment id the merge never saw) — the caller falls back to nearest-centroid.
     */
    static int containedParentFragment(CostPyramid p, int parentLevel, int prx, int pry, int prz,
                                       int crx, int cry, int crz, int frag) {
        return containedParentFragment(p, parentLevel, prx, pry, prz, crx, cry, crz, frag, null);
    }

    /** As {@link #containedParentFragment}, appending the refusal reason to {@code trace} when non-null
     *  (COLD diagnostics only — the anchor-walk trace in the region FAIL post-mortem). */
    static int containedParentFragment(CostPyramid p, int parentLevel, int prx, int pry, int prz,
                                       int crx, int cry, int crz, int frag, StringBuilder trace) {
        final Side s = SIDE_A.get();
        if (!gather(p, parentLevel, prx, pry, prz, s)) {
            if (trace != null) {
                final int parentRow = p.rowIfPresent(parentLevel, prx, pry, prz);
                final RegionFragments prf = (parentRow >= 0 && p.isBuilt(parentLevel, parentRow))
                        ? p.fragmentRecord(parentLevel, parentRow) : null;
                trace.append(" [gather-refused n=").append(s.n).append(" comps=").append(s.compCount)
                        .append(s.collapsed ? " collapsed" : "")
                        .append(" recFc=").append(prf == null ? -1 : prf.fragmentCount()).append(']');
            }
            return -1;
        }
        final int f = parentFragOfChild(s, crx, cry, crz, frag);
        if (f < 0 && trace != null) {
            trace.append(" [no-item child=(").append(crx).append(',').append(cry).append(',').append(crz)
                    .append("):f").append(frag).append(" n=").append(s.n).append(']');
        }
        return f;
    }
}
