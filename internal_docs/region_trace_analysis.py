#!/usr/bin/env python
"""Analyze an Orebit REGION-tier A* trace (orebit-region-trace.txt) — text stats + a PNG.

Mirror of internal_docs/trace_analysis.py (the BLOCK-tier analyzer), adapted to the
region-tier trace grammar. STDOUT stats are the priority; the PNG is best-effort.

Usage: python region_trace_analysis.py <trace.txt> [out.png]

Region trace grammar (verified against run/orebit-region-trace.txt):
  Header : Orebit REGION A* trace  start=BlockPos{x=..,y=..,z=..}  goal=BlockPos{...}  caps=BotCaps[...]
  Skeleton block ("== live cascade skeleton =="):
     L1.N   region=(rx,ry,rz) frag=N portal=(x,y,z)[tag] center=(x,y,z)[tag]
     Sn win|*TARGET region=(rx,ry,rz) frag=N kind=.. navSection=.. portal=..[tag] center=..[tag]
     tags observed: [air-no-floor] [unbuilt]  (no [stand] in this sample)
  Expansion : E <seq> L0 region=<rx>,<ry>,<rz> frag=<n> g=<g> f=<f> [<tag>]
              tag = air | solid | collapsed | mixed frags=N   (NODE classification, NOT the move)
              -- NOTE: E lines carry NO parent/via field. Parent (for direction) is
                 reconstructed from the last OK candidate relaxation targeting the cell.
  Candidate : (indented) C <kind> -> <rx>,<ry>,<rz> frag=<n> cost=<c> crossing=<x>,<y>,<z> <OK|worse>
              kinds: walk air-pillar air-fall solid-mine mine-sibling dig-through
                     mine-fallback mine-solid collapsed  (water-swim possible, not in sample)
  Result : RESULT: <N> regions (FOUND|PARTIAL ...) L0   then  [i] region=rx,ry,rz frag=N ...
"""
import re, sys, math
from collections import Counter, defaultdict
try: import statistics
except Exception: statistics = None
try: sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception: pass

TRACE = sys.argv[1] if len(sys.argv) > 1 else \
    r"C:\Users\steve\Repos\personal\orebit-mc121-wt\run\orebit-region-trace.txt"
OUT = sys.argv[2] if len(sys.argv) > 2 else TRACE.replace(".txt", ".png")

HDR   = re.compile(r"start=BlockPos\{x=(-?\d+), ?y=(-?\d+), ?z=(-?\d+)\}.*goal=BlockPos\{x=(-?\d+), ?y=(-?\d+), ?z=(-?\d+)\}")
E     = re.compile(r"^E (\d+) \S+ region=(-?\d+),(-?\d+),(-?\d+) frag=(\d+) g=([\d.]+) f=([\d.]+)\s*(?:\[([^]]*)\])?")
C     = re.compile(r"^\s*C (\S+) -> (-?\d+),(-?\d+),(-?\d+) frag=(\d+) cost=([\d.]+) crossing=(-?\d+),(-?\d+),(-?\d+) (\w+)")
GOALR = re.compile(r"goalRegion=\((-?\d+),(-?\d+),(-?\d+)\)")

def cap(name, hdr, default=None):
    m = re.search(re.escape(name) + r"=([^,\]]+)", hdr)
    return m.group(1) if m else default

start = goal = None
goal_region = None
hdr_line = ""
result = ""
caps = {}
rows = []                 # (seq, rx, ry, rz, frag, g, f, tag)
first_pop_f = {}          # (rx,ry,rz,frag) -> f at first pop
relax = {}                # (rx,ry,rz,frag) -> (parent_row, kind, cost)  last-OK relaxation (the cameFrom)
parent_of = {}            # seq -> (parent_ry, kind, cost, parent_g)
cand_kind = Counter()     # kind -> count (all candidates)
cand_kind_ok = Counter()  # kind -> OK count
cand_cost = defaultdict(list)  # kind -> [costs] (OK only — the ones that shaped the search)
cand_outcome = Counter()  # OK / worse
skel_tags = Counter()     # skeleton bracket-tag tally
e1_candidates = []        # full candidate dump for the FIRST expansion (all-air zone)

cur_parent = None
first_e_seen = False
in_skeleton = False
with open(TRACE, "r", encoding="utf-8", errors="replace") as fh:
    for line in fh:
        if start is None:
            m = HDR.search(line)
            if m:
                start = tuple(int(v) for v in m.group(1, 2, 3))
                goal = tuple(int(v) for v in m.group(4, 5, 6))
                hdr_line = line
                for k in ("canPlace", "canBreak", "greedyWeight", "maxNodes",
                          "costPerHitpoint", "maxBreakHardness", "allowUnbreakable",
                          "jumpHeight", "safeFallDistance", "maxFallDistance", "takesDamage"):
                    caps[k] = cap(k, line)
                continue
        if goal_region is None:
            m = GOALR.search(line)
            if m:
                goal_region = tuple(int(v) for v in m.group(1, 2, 3))
        if line.startswith("== live cascade skeleton"):
            in_skeleton = True
        if in_skeleton and not first_e_seen:
            for t in re.findall(r"\[([a-z][a-z-]*)\]", line):
                if t in ("air-no-floor", "unbuilt", "stand", "air-has-floor", "solid", "collapsed"):
                    skel_tags[t] += 1
        if line.startswith("RESULT:"):
            result = line.split("RESULT:", 1)[1].strip()
            in_skeleton = False

        m = E.match(line)
        if m:
            first_e_seen = True
            in_skeleton = False
            seq = int(m.group(1))
            rx, ry, rz = int(m.group(2)), int(m.group(3)), int(m.group(4))
            frag = int(m.group(5)); g = float(m.group(6)); f = float(m.group(7))
            tag = (m.group(8) or "").strip()
            row = (seq, rx, ry, rz, frag, g, f, tag)
            rows.append(row)
            key = (rx, ry, rz, frag)
            if key not in first_pop_f:
                first_pop_f[key] = f
            pr = relax.get(key)
            if pr is not None:
                pcur, pkind, pcost = pr
                parent_of[seq] = (pcur[2], pkind, pcost, pcur[5])  # parent_ry, kind, cost, parent_g
            cur_parent = row
            continue

        m = C.match(line)
        if m:
            kind = m.group(1)
            trx, tryy, trz = int(m.group(2)), int(m.group(3)), int(m.group(4))
            tfrag = int(m.group(5)); cost = float(m.group(6))
            outcome = m.group(10)
            cand_kind[kind] += 1
            cand_outcome[outcome] += 1
            if outcome == "OK":
                cand_kind_ok[kind] += 1
                cand_cost[kind].append(cost)
                if cur_parent is not None:
                    relax[(trx, tryy, trz, tfrag)] = (cur_parent, kind, cost)
            # capture the full candidate set of expansion #1 (first popped node)
            if cur_parent is not None and cur_parent[0] == 1:
                e1_candidates.append((kind, (trx, tryy, trz), cost, outcome, cur_parent[5]))

# ------------------------------------------------------------------ report
n = len(rows)
sx, sy, sz = start; gx, gy, gz = goal
print("=" * 78)
print("OREBIT REGION-TIER A* TRACE ANALYSIS")
print("=" * 78)
print(f"start block = {start}")
print(f"goal  block = {goal}   (goal is {gy-sy:+d} in y, {gx-sx:+d} in x, {gz-sz:+d} in z)")
if goal_region:
    print(f"goal REGION = {goal_region}  (region-tier coords rx,ry,rz)")
print("caps: " + "  ".join(f"{k}={caps.get(k)}" for k in
      ("canPlace", "canBreak", "greedyWeight", "maxNodes", "costPerHitpoint")))
print("      " + "  ".join(f"{k}={caps.get(k)}" for k in
      ("takesDamage", "maxBreakHardness", "allowUnbreakable", "jumpHeight",
       "safeFallDistance", "maxFallDistance")))
if result:
    print(f"RESULT: {result}")
print(f"\ntotal expansions (E lines) = {n}")

# ---- 2. per-edge-KIND candidate counts + cost stats ------------------------
def stats(vals):
    if not vals: return "-"
    lo = min(vals); hi = max(vals)
    med = statistics.median(vals) if statistics else sorted(vals)[len(vals)//2]
    return f"min={lo:8.2f}  med={med:8.2f}  max={hi:8.2f}"
print("\n" + "-" * 78)
print("CANDIDATE EDGES BY KIND  (cost stats over OK/relaxed candidates)")
print("-" * 78)
print(f"  {'kind':14s} {'total':>7s} {'OK':>7s} {'worse':>7s}   cost(OK)")
for kind in sorted(cand_kind, key=lambda k: -cand_kind[k]):
    tot = cand_kind[kind]; ok = cand_kind_ok[kind]; ws = tot - ok
    print(f"  {kind:14s} {tot:7d} {ok:7d} {ws:7d}   {stats(cand_cost[kind])}")
print(f"  {'TOTAL':14s} {sum(cand_kind.values()):7d} "
      f"{cand_outcome['OK']:7d} {cand_outcome['worse']:7d}")
print("  (air-pillar = climb UP one region by pillaring; air-fall = drop DOWN;")
print("   walk = lateral/step; *-mine/dig-* = break through solid.)")

# ---- 3. VERTICAL BIAS ANALYSIS ---------------------------------------------
print("\n" + "-" * 78)
print("VERTICAL BIAS ANALYSIS  (each expansion's move direction vs its parent)")
print("-" * 78)
rys = [r[2] for r in rows]
print(f"  expanded ry range: [{min(rys)} .. {max(rys)}]   start ry region row varies; "
      f"goal ry = {goal_region[1] if goal_region else '?'}")
if goal_region:
    gry = goal_region[1]
    away = "UP is AWAY from goal (goal is BELOW start)" if gry < rys[0] else \
           "DOWN is AWAY from goal (goal is ABOVE start)" if gry > rys[0] else "goal at start ry"
    print(f"  first-expansion ry = {rys[0]},  goal ry = {gry}  ->  {away}")

dir_count = Counter(); dir_f = defaultdict(list)
classified = 0; g_match = 0
for r in rows:
    seq = r[0]; pr = parent_of.get(seq)
    if pr is None:
        dir_count["START/orphan"] += 1
        continue
    pry, kind, cost, pg = pr
    classified += 1
    if abs((pg + cost) - r[5]) < 0.01:
        g_match += 1
    d = "UP" if r[2] > pry else "DOWN" if r[2] < pry else "LATERAL"
    dir_count[d] += 1
    dir_f[d].append(r[6])
print(f"  (parent reconstructed for {classified}/{n} expansions via last-OK relaxation; "
      f"g==parent_g+cost held for {g_match}/{classified} — a parent-attribution confidence check)")
print(f"  {'direction':14s} {'count':>7s} {'pct':>5s}   mean-f")
tot_cl = sum(v for k, v in dir_count.items() if k in ("UP", "DOWN", "LATERAL"))
for d in ("UP", "LATERAL", "DOWN", "START/orphan"):
    c = dir_count.get(d, 0)
    mf = (sum(dir_f[d]) / len(dir_f[d])) if dir_f[d] else float("nan")
    pct = f"{c*100//tot_cl}%" if (tot_cl and d != "START/orphan") else "-"
    print(f"  {d:14s} {c:7d} {pct:>5s}   {mf:8.2f}")
if goal_region:
    gry = goal_region[1]
    toward = "DOWN" if gry < rys[0] else "UP"
    away = "UP" if gry < rys[0] else "DOWN"
    ct = dir_count.get(toward, 0); ca = dir_count.get(away, 0)
    print(f"  toward-goal vertical ({toward}) = {ct};  away-from-goal vertical ({away}) = {ca}"
          f"   -> {'AWAY dominates' if ca > ct else 'toward dominates'} "
          f"({ca}:{ct} away:toward)")

# ---- 4. WHY UP BEAT LATERAL — first-expansion candidate dump ----------------
print("\n" + "-" * 78)
print("WHY 'UP' BEAT 'LATERAL'  (candidate set of expansion #1, the all-air start)")
print("-" * 78)
if e1_candidates:
    p1 = rows[0]
    print(f"  expansion #1 node: region=({p1[1]},{p1[2]},{p1[3]}) frag={p1[4]} "
          f"g={p1[5]:.2f} f={p1[6]:.2f} tag=[{p1[7]}]")
    print(f"  each candidate: g_child = parent_g + cost;  f_pop = f when that region was")
    print(f"  FIRST actually expanded in the trace (may be via a cheaper parent — '-' = never popped)")
    print(f"  {'kind':12s} {'target(rx,ry,rz)':>18s} {'dir':>7s} {'cost':>9s} {'g_child':>9s} {'f_pop':>10s} {'out':>6s}")
    for kind, tgt, cost, outcome, pg in e1_candidates:
        d = "UP" if tgt[1] > p1[2] else "DOWN" if tgt[1] < p1[2] else "LAT"
        fpop = first_pop_f.get((tgt[0], tgt[1], tgt[2], 0))
        fps = f"{fpop:10.2f}" if fpop is not None else f"{'-':>10s}"
        print(f"  {kind:12s} {str(tgt):>18s} {d:>7s} {cost:9.2f} {pg+cost:9.2f} {fps} {outcome:>6s}")
    # explicit up-vs-lateral summary among OK candidates
    oks = [(k, t, c, first_pop_f.get((t[0], t[1], t[2], 0))) for k, t, c, o, pg in e1_candidates if o == "OK"]
    ups = [x for x in oks if x[1][1] > p1[2]]
    lats = [x for x in oks if x[1][1] == p1[2]]
    downs = [x for x in oks if x[1][1] < p1[2]]
    def cheap(lst):
        got = [x for x in lst if x[3] is not None]
        return min(got, key=lambda x: x[3]) if got else None
    cu, cl, cd = cheap(ups), cheap(lats), cheap(downs)
    print("\n  cheapest-f OK candidate per direction (f = value the search ranked on):")
    for label, cc in (("UP", cu), ("LATERAL", cl), ("DOWN", cd)):
        if cc:
            print(f"    {label:8s} -> {str(cc[1]):>16s} cost={cc[2]:.2f}  f_pop={cc[3]:.2f}")
        else:
            print(f"    {label:8s} -> (no OK candidate reached that direction / none popped)")
    print("  NOTE: all air-pillar (UP) candidates cost ~96 (a full region climb); walk (LATERAL)")
    print("  candidates are ~1-12 when air, but jump to ~208-216 when they must drop/mine. The")
    print("  f_pop column shows which the greedy-weighted frontier actually expanded first.")
else:
    print("  (no candidates captured for expansion #1)")

# ---- 5. explored bounding box + skeleton ry progression --------------------
print("\n" + "-" * 78)
print("EXPLORED REGION BOUNDING BOX + SKELETON")
print("-" * 78)
rxs = [r[1] for r in rows]; rzs = [r[3] for r in rows]
print(f"  explored: rx[{min(rxs)}..{max(rxs)}]  ry[{min(rys)}..{max(rys)}]  rz[{min(rzs)}..{max(rzs)}]")
if goal_region:
    print(f"  goal region = {goal_region};  "
          f"rx off by up to {max(abs(min(rxs)-goal_region[0]),abs(max(rxs)-goal_region[0]))}, "
          f"ry off by up to {max(abs(min(rys)-goal_region[1]),abs(max(rys)-goal_region[1]))}, "
          f"rz off by up to {max(abs(min(rzs)-goal_region[2]),abs(max(rzs)-goal_region[2]))}")
# node-tag tally
etag = Counter(r[7] for r in rows)
print("  expanded-node tag tally:")
for t, c in etag.most_common():
    print(f"    {t or '(none)':16s} {c:6d}")

# ---- 6. skeleton tag tally --------------------------------------------------
print("\n" + "-" * 78)
print("SKELETON CELL TAG TALLY  ([unbuilt] vs [air-no-floor] vs [stand] ...)")
print("-" * 78)
if skel_tags:
    for t, c in skel_tags.most_common():
        print(f"    {t:16s} {c:6d}")
else:
    print("    (no skeleton tags parsed)")

# ---- ry progression down the E stream (are we descending over time?) --------
print("\n  ry of popped node at expansion #: " + "  ".join(
    f"{s}:{rows[s][2]}" for s in [0, n//10, n//4, n//2, 3*n//4, n-1] if s < n))
print(f"  f at expansion #:                 " + "  ".join(
    f"{s}:{rows[s][6]:.0f}" for s in [0, n//10, n//4, n//2, 3*n//4, n-1] if s < n))

# ---------------------------------------------------------------- visualization
try:
    import numpy as np, matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    seqs = [r[0] for r in rows]
    fig, ax = plt.subplots(2, 2, figsize=(15, 11))
    fig.suptitle(f"Orebit REGION A* — start_region≈({rows[0][1]},{rows[0][2]},{rows[0][3]}) "
                 f"goal_region={goal_region}  ({n} expansions)"
                 + (f"  {result}" if result else ""), fontsize=12)
    # (0,0) side rx-ry colored by expansion order
    sc = ax[0, 0].scatter(rxs, rys, c=seqs, cmap='viridis', s=10)
    if goal_region:
        ax[0, 0].scatter([goal_region[0]], [goal_region[1]], c='red', marker='*', s=220, zorder=5, label='goal')
        ax[0, 0].axhline(goal_region[1], color='red', ls=':', lw=1)
    ax[0, 0].scatter([rows[0][1]], [rows[0][2]], c='white', edgecolors='black', s=80, zorder=5, label='start')
    ax[0, 0].set_title("side (rx-ry), colour = expansion order")
    ax[0, 0].set_xlabel("rx"); ax[0, 0].set_ylabel("ry (up)"); ax[0, 0].legend()
    plt.colorbar(sc, ax=ax[0, 0], label='seq')
    # (0,1) top-down rx-rz colored by f
    fs = [r[6] for r in rows]
    sc = ax[0, 1].scatter(rxs, rzs, c=fs, cmap='plasma', s=10)
    if goal_region:
        ax[0, 1].scatter([goal_region[0]], [goal_region[2]], c='red', marker='*', s=220, zorder=5, label='goal')
    ax[0, 1].scatter([rows[0][1]], [rows[0][3]], c='white', edgecolors='black', s=80, zorder=5, label='start')
    ax[0, 1].set_title("top-down (rx-rz), colour = f"); ax[0, 1].set_xlabel("rx"); ax[0, 1].set_ylabel("rz")
    ax[0, 1].legend(); plt.colorbar(sc, ax=ax[0, 1], label='f')
    # (1,0) expansions per ry
    yc = Counter(rys); yy = sorted(yc)
    ax[1, 0].barh(yy, [yc[y] for y in yy], color='steelblue')
    if goal_region:
        ax[1, 0].axhline(goal_region[1], color='red', ls=':', lw=1, label='goal ry')
    ax[1, 0].set_title("expansions per ry level"); ax[1, 0].set_xlabel("# expanded"); ax[1, 0].set_ylabel("ry")
    ax[1, 0].legend()
    # (1,1) f vs expansion order
    step = max(1, n // 3000)
    ax[1, 1].plot(seqs[::step], fs[::step], lw=0.6, color='purple')
    ax[1, 1].set_title("f of popped node vs expansion order"); ax[1, 1].set_xlabel("expansion #"); ax[1, 1].set_ylabel("f")
    plt.tight_layout(rect=[0, 0, 1, 0.97])
    plt.savefig(OUT, dpi=110)
    print(f"\nwrote visualization -> {OUT}")
except Exception as ex:
    print(f"\n(plot skipped: {ex})")
