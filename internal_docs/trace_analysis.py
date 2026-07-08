#!/usr/bin/env python
"""Analyze an Orebit A* trace (orebit-trace.txt) — text stats + a visualization PNG.

Usage: python trace_analysis.py <trace.txt> [out.png]
"""
import re, sys, math
from collections import Counter, defaultdict
try: sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception: pass

TRACE = sys.argv[1] if len(sys.argv) > 1 else \
    r"C:\Users\steve\Repos\personal\orebit\versions\26.2\run\orebit-trace.txt"
OUT = sys.argv[2] if len(sys.argv) > 2 else TRACE.replace(".txt", ".png")

E = re.compile(r"^E (\d+) (-?\d+) (-?\d+) (-?\d+) g=([\d.]+) f=([\d.]+) via=(\w+)")
HDR = re.compile(r"start=BlockPos\{x=(-?\d+), y=(-?\d+), z=(-?\d+)\}.*goal=BlockPos\{x=(-?\d+), y=(-?\d+), z=(-?\d+)\}")

start = goal = None
hdr_line = ""      # raw header text (checked for an explicit goal-tolerance, if the format ever grows one)
result = ""        # the trailing "RESULT: ..." line (the true outcome; the goal-reaching pop is NOT E-logged,
                   # since the goal-test breaks before the E line, so DON'T infer FOUND/FAIL from expansions)
rows = []          # (seq,x,y,z,g,f,via)
cand_total = Counter()   # candidate outcomes per move (OK/worse/corridor)
with open(TRACE, "r", encoding="utf-8", errors="replace") as fh:
    for line in fh:
        if start is None:
            m = HDR.search(line)
            if m:
                start = tuple(int(v) for v in m.group(1, 2, 3))
                goal = tuple(int(v) for v in m.group(4, 5, 6))
                hdr_line = line
        if line.startswith("RESULT:"):
            result = line.split("RESULT:", 1)[1].strip()
        m = E.match(line)
        if m:
            rows.append((int(m.group(1)), int(m.group(2)), int(m.group(3)),
                         int(m.group(4)), float(m.group(5)), float(m.group(6)), m.group(7)))
        elif line.startswith("  C "):
            p = line.split()
            cand_total[(p[1], p[-1])] += 1   # (move, outcome)

gx, gy, gz = goal
sx, sy, sz = start
n = len(rows)
print(f"start={start} goal={goal}  expansions={n}")
print(f"goal is {gy-sy:+d} in y, {gx-sx:+d} in x, {gz-sz:+d} in z  (a {abs(gy-sy)}-block vertical climb)\n")

xs = [r[1] for r in rows]; ys = [r[2] for r in rows]; zs = [r[3] for r in rows]
print(f"explored bounding box:  x[{min(xs)}..{max(xs)}]  y[{min(ys)}..{max(ys)}]  z[{min(zs)}..{max(zs)}]")
print(f"  goal column is x={gx} z={gz};  horizontal spread = "
      f"x ±{max(abs(min(xs)-gx),abs(max(xs)-gx))}, z ±{max(abs(min(zs)-gz),abs(max(zs)-gz))}")
above = sum(1 for y in ys if y > sy); below = sum(1 for y in ys if y < sy); same = sum(1 for y in ys if y == sy)
print(f"  vertical: {below} nodes BELOW start ({below*100//n}%), {same} at start level, {above} above ({above*100//n}%)")
print(f"  highest reached: y={max(ys)} ({max(ys)-sy:+d} from start, goal needs {gy-sy:+d})\n")

via = Counter(r[6] for r in rows)
print("expansions by the move that reached them:")
for mv, c in via.most_common():
    print(f"  {mv:10s} {c:6d}  ({c*100//n}%)")

print("\non-goal-column (x==gx and z==gz) vs off-column expansions:")
on_col = sum(1 for r in rows if r[1] == gx and r[3] == gz)
print(f"  on-column:  {on_col:6d}  ({on_col*100//n}%)   <- the only cells the optimal pillar uses")
print(f"  off-column: {n-on_col:6d}  ({(n-on_col)*100//n}%)   <- the flood/cone\n")

# how high did the ON-COLUMN pillar actually get, and at what expansion seq?
col = sorted([r for r in rows if r[1] == gx and r[3] == gz], key=lambda r: r[2])
if col:
    top = max(col, key=lambda r: r[2])
    print(f"on-column highest node: y={top[2]} at expansion seq={top[0]} (g={top[4]:.0f} f={top[5]:.1f})")
    print(f"  -> to pillar straight up needs ~{gy-sy} expansions; it took {top[0]} (the rest is flood)\n")

# f frontier over expansion order
print("f at expansion #:", "  ".join(f"{s}:{rows[s][5]:.0f}" for s in
      [0, n//10, n//4, n//2, 3*n//4, n-1] if s < n))

# closest approach
def oct3(x,y,z):
    a,b,c = sorted((abs(x-gx),abs(y-gy),abs(z-gz)))
    return 2.0*(a*math.sqrt(3)+(b-a)*math.sqrt(2)+(c-b))
closest = min(rows, key=lambda r: oct3(r[1],r[2],r[3]))
print(f"\nclosest approach to goal: ({closest[1]},{closest[2]},{closest[3]}) at seq={closest[0]} "
      f"(still {abs(closest[1]-gx)+abs(closest[2]-gy)+abs(closest[3]-gz)} blocks, h≈{oct3(closest[1],closest[2],closest[3]):.0f})")

# ---- cost reference for the f-vs-expansion panel ----------------------------
# If an expansion sits at/within the goal-arrival tolerance, its g IS the true cost-to-goal.
# Header tolerance wins if present; else exact goal cell; else BlockPathfinder's documented
# arrival tolerance (±1 horizontal / ±2 vertical, Chebyshev). NOTE the goal-reaching pop is
# NOT E-logged (the goal test breaks before the E line), so a FOUND search may still show no
# in-tolerance expansion — then the reference falls back to the closest approach (min octile
# distance to goal), reporting its g and remaining distance.
tol_m = re.search(r"tol(?:erance)?\D*?(\d+)\D+?(\d+)", hdr_line)
def in_tol(r, txz, ty):
    return max(abs(r[1]-gx), abs(r[3]-gz)) <= txz and abs(r[2]-gy) <= ty
if tol_m:
    goal_rows = [r for r in rows if in_tol(r, int(tol_m.group(1)), int(tol_m.group(2)))]
else:
    goal_rows = [r for r in rows if (r[1], r[2], r[3]) == (gx, gy, gz)]
    if not goal_rows:
        goal_rows = [r for r in rows if in_tol(r, 1, 2)]
if goal_rows:
    ref_row = min(goal_rows, key=lambda r: r[4])
    ref_g = ref_row[4]
    ref_label = f"true cost to goal = {ref_g:.1f}"
    print(f"\ngoal FOUND in-trace: expansion seq={ref_row[0]} at ({ref_row[1]},{ref_row[2]},{ref_row[3]}) "
          f"-> true cost to goal = {ref_g:.1f}")
else:
    ref_g = closest[4]
    dist_blocks = oct3(closest[1], closest[2], closest[3]) / 2.0   # oct3 is tick-scaled x2
    ref_label = f"closest approach: g={ref_g:.1f} at {dist_blocks:.1f} blocks out"
    print(f"\nno in-tolerance goal expansion (partial/failed search, or the goal pop was unlogged) "
          f"-> reference = closest approach: g={ref_g:.1f} at {dist_blocks:.1f} blocks out")

print("\ncandidate outcomes by move (OK=relaxed, worse=dominated, corridor=out of bounds):")
moves = sorted(set(k[0] for k in cand_total))
for mv in moves:
    ok = cand_total.get((mv,'OK'),0); ws = cand_total.get((mv,'worse'),0); co = cand_total.get((mv,'corridor'),0)
    print(f"  {mv:10s} OK={ok:6d}  worse={ws:6d}  corridor={co}")

# ---------------------------------------------------------------- visualization
try:
    import numpy as np, matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    # keep the LOWEST f seen per cell (the value that decided expansion order)
    bestf = {}
    for s,x,y,z,g,f,v in rows:
        for key in ((x,z,'xz'),(x,y,'xy')):
            if key not in bestf or f < bestf[key]:
                bestf[key] = f
    fig, ax = plt.subplots(2, 2, figsize=(15, 11))
    fig.suptitle(f"Orebit A* — start={start} goal={goal}  ({n} logged expansions)"
                 + (f"  RESULT: {result}" if result else ""), fontsize=13)

    # (0,0) top-down XZ, colour = min f
    pts = [(x,z,f) for (x,z,p),f in bestf.items() if p=='xz']
    X=[p[0] for p in pts]; Z=[p[1] for p in pts]; F=[p[2] for p in pts]
    sc=ax[0,0].scatter(X,Z,c=F,cmap='viridis',s=14,marker='s')
    ax[0,0].scatter([gx],[gz],c='red',marker='*',s=220,label='goal col',zorder=5)
    ax[0,0].scatter([sx],[sz],c='white',edgecolors='black',marker='o',s=90,label='start',zorder=5)
    ax[0,0].set_title("top-down (X-Z), colour = min f at cell\n(the horizontal ground flood)")
    ax[0,0].set_xlabel("x"); ax[0,0].set_ylabel("z"); ax[0,0].legend(); ax[0,0].set_aspect('equal')
    plt.colorbar(sc,ax=ax[0,0],label='min f')

    # (0,1) side XY, colour = min f
    pts=[(x,y,f) for (x,y,p),f in bestf.items() if p=='xy']
    X=[p[0] for p in pts]; Y=[p[1] for p in pts]; F=[p[2] for p in pts]
    sc=ax[0,1].scatter(X,Y,c=F,cmap='viridis',s=14,marker='s')
    ax[0,1].scatter([gx],[gy],c='red',marker='*',s=220,label='goal',zorder=5)
    ax[0,1].scatter([sx],[sy],c='white',edgecolors='black',marker='o',s=90,label='start',zorder=5)
    ax[0,1].axhline(gy,color='red',ls=':',lw=1)
    ax[0,1].set_title("side (X-Y), colour = min f\n(how little it climbed vs how wide it spread)")
    ax[0,1].set_xlabel("x"); ax[0,1].set_ylabel("y (up)"); ax[0,1].legend()
    plt.colorbar(sc,ax=ax[0,1],label='min f')

    # (1,0) nodes per y-level
    yc=Counter(ys); yy=sorted(yc)
    ax[1,0].barh(yy,[yc[y] for y in yy],color='steelblue')
    ax[1,0].axhline(sy,color='black',ls='--',lw=1,label='start y')
    ax[1,0].axhline(gy,color='red',ls=':',lw=1,label='goal y')
    ax[1,0].set_title("expansions per Y level\n(flood concentrated at/near the floor)")
    ax[1,0].set_xlabel("# expanded"); ax[1,0].set_ylabel("y"); ax[1,0].legend()

    # (1,1) f vs expansion order (the rising frontier)
    step=max(1,n//3000)
    ax[1,1].plot([r[0] for r in rows[::step]],[r[5] for r in rows[::step]],lw=0.6,color='purple')
    ax[1,1].axhline(ref_g,color='gray',ls=':',label=ref_label)
    ax[1,1].set_title("f of each popped node vs expansion order\n(the rising frontier vs the cost reference)")
    ax[1,1].set_xlabel("expansion #"); ax[1,1].set_ylabel("f (popped)"); ax[1,1].legend()

    plt.tight_layout(rect=[0,0,1,0.97])
    plt.savefig(OUT, dpi=110)
    print(f"\nwrote visualization -> {OUT}")
except Exception as ex:
    print(f"\n(plot skipped: {ex})")
