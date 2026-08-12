#!/usr/bin/env python3
"""
Visualise the per-pop CELL-READ FOOTPRINT of the block A* expansion path.

Source of truth: internal_docs/INVENTORY-per-move-cell-reads.md (2026-08-11).

WHAT THIS IS: a HAND-ENCODED MODEL of the read sites that inventory documents — not
instrumentation. Each `r(...)` call below corresponds to one documented read CALL SITE, so a
cell touched by three movements accumulates three. It is an UPPER ENVELOPE: every arm of every
movement is assumed reached, which no single real pop does (arms `continue` past each other).
It answers "which cells does the search contend over, and how hard", not "what does one pop cost".

Scans that run to a cap (Fall's maxFall/SOFT_SCAN_LIMIT, Parkour's down-scan, the Pillar/MineDown
macro runs, RideBubbleColumn's column) are bounded by SCAN so the near field stays readable; the
tails are single-column and do not contend.

Usage:  python read_footprint.py [outdir]
Needs:  matplotlib, numpy
"""
import sys
import os
from collections import Counter

import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.colors import LinearSegmentedColormap

CARD = [(1, 0), (-1, 0), (0, 1), (0, -1)]
DIAG = [(1, 1), (1, -1), (-1, 1), (-1, -1)]
SIX = [(1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)]

SCAN = 3          # bound on capped vertical/gap scans
GAP = 4           # parkour gap columns modelled (envelope BASE row maxGapAll)

counts = Counter()
by_move = {}


def _mk(name):
    hits = []

    def r(dx, dy, dz, n=1):
        for _ in range(n):
            hits.append((dx, dy, dz))
    return r, hits


def emit(name, fn):
    r, hits = _mk(name)
    fn(r)
    by_move[name] = Counter(hits)
    counts.update(hits)


def fanout6(r, x, y, z):
    """MovementContext.placeable -> up to 6 supportsPlacement probes."""
    for dx, dy, dz in SIX:
        r(x + dx, y + dy, z + dz)


# --- shared per-pop prologue (BlockPathfinder -> setCurrentDoorEdge) ------------------
emit("prologue", lambda r: (r(0, 1, 0), r(0, 2, 0)))


# --- Traverse -------------------------------------------------------------------------
def _traverse(r):
    r(0, 0, 0)                                   # descriptorAt :113
    for dx, dz in CARD:
        r(dx, 0, dz)                             # packedAt :149
        r(dx, 1, dz); r(dx, 2, dz)               # requireBodyClearToward :193
        r(dx, 1, dz); r(dx, 2, dz)               # bodyTransitCost :196  (re-read)
        r(dx, 0, dz)                             # requireFloorOrToggle :213
        r(dx, 1, dz)                             # packedAt :232 step-assist
        r(dx, 2, dz); r(dx, 3, dz)               # requireBodyClearToward :243
        r(dx, 2, dz); r(dx, 3, dz)               # bodyTransitCost :246
        r(dx, 1, dz)                             # requireFloorOrToggle :258
        r(dx, 2, dz); r(dx, 3, dz)               # requireBodyClearToward :262
        r(dx, 2, dz); r(dx, 3, dz)               # bodyTransitCost :265
        r(dx, 0, dz)                             # requireFloor :285 bridge
        fanout6(r, dx, 0, dz)                    #   -> placeable fan-out
emit("Traverse", _traverse)


# --- Diagonal -------------------------------------------------------------------------
def _diagonal(r):
    r(0, 0, 0)                                   # descriptorAt :53
    for dx, dz in DIAG:
        r(dx, 0, dz)                             # packedAt :60
        r(dx, 1, dz); r(dx, 2, dz)               # passable :75
        r(dx, 1, 0); r(dx, 2, 0)                 # corner A  :84 :86
        r(0, 1, dz); r(0, 2, dz)                 # corner B  :88 :90
        r(dx, 1, dz); r(dx, 2, dz)               # bodyTransitCost :95 (re-read)
emit("Diagonal", _diagonal)


# --- Ascend ---------------------------------------------------------------------------
def _ascend(r):
    r(0, 0, 0, 3)                                # reducesJump/solidFooting floor/descriptorAt
    r(0, 0, 0, 2)                                # noJumpFromBody flags + flagsAt :115
    r(0, 1, 0)                                   # solidFooting feet :110
    r(0, 1, 0); r(0, 2, 0)                       # noJumpFromBody fan-out
    for dx, dz in CARD:
        r(dx, 0, dz)                             # packedAt :252 same-level arm
        r(dx, 0, dz)                             # requireFloorOrToggle :273
        r(0, 3, 0)                               # requireAir :279 takeoff head
        r(dx, 3, dz)                             # requireAir :282
        r(dx, 1, dz); r(dx, 2, dz)               # requireBodyClearToward :285
        r(dx, 1, dz); r(dx, 2, dz)               # bodyTransitCost :290
        r(dx, 1, dz)                             # packedAt :160  (+1 dest floor)
        r(dx, 1, dz)                             # requireFloorOrToggle :201
        r(dx, 1, dz); fanout6(r, dx, 1, dz)      # requireFootingOn :211 footing
        r(dx, 0, dz); fanout6(r, dx, 0, dz)      #   -> support beneath
        r(0, 3, 0)                               # requireAir :218
        r(dx, 2, dz); r(dx, 3, dz)               # requireBodyClearToward :222
        r(dx, 2, dz); r(dx, 3, dz)               # bodyTransitCost :232
emit("Ascend", _ascend)


# --- Descend --------------------------------------------------------------------------
def _descend(r):
    for dx, dz in CARD:
        r(dx, -1, dz)                            # packedAt :64
        r(dx, -1, dz); fanout6(r, dx, -1, dz)    # requireFloor(OrToggle) :82/:84
        r(dx, 2, dz)                             # requireAir :97
        r(dx, 1, dz)                             # requireAirToward :98
        r(dx, 0, dz)                             # requireAirToward :99
        r(dx, 0, dz); r(dx, 1, dz)               # bodyTransitCost :109 (re-read)
emit("Descend", _descend)


# --- WalkOff (landing is 2 cells out) --------------------------------------------------
def _walkoff(r):
    for dx, dz in CARD:
        r(dx, 0, dz)                             # floorGapAt :120 (nibble)
        r(2 * dx, -1, 2 * dz)                    # packedAt :124 landing floor
        r(dx, 0, dz)                             # packedAt :131 gap foot level
        r(dx, -1, dz)                            # packedAt :137 gap one below
        r(dx, 1, dz); r(dx, 2, dz)               # passable :148 gap body
        r(2 * dx, 0, 2 * dz); r(2 * dx, 1, 2 * dz)   # passable :153 landing body
        r(dx, 1, dz); r(dx, 2, dz)               # bodyTransitCost :168
        r(2 * dx, 0, 2 * dz); r(2 * dx, 1, 2 * dz)   # bodyTransitCost :169
    r(0, 0, 0, 3); r(0, 1, 0); r(0, 2, 0)        # eligibleTakeoff gate :195-197
emit("WalkOff", _walkoff)


# --- MineDown / Pillar (vertical) ------------------------------------------------------
def _minedown(r):
    r(0, -1, 0)                                  # packedAt :81
    r(0, 0, 0, 2)                                # flagsAt :87 + requireAirVertical :98
    for k in range(1, SCAN + 1):                 # macro loop :124/:126
        r(0, -(k - 1), 0); r(0, -(k - 1), 0)
emit("MineDown", _minedown)


def _pillar(r):
    r(0, 0, 0, 3)                                # reducesJump/noJumpFromBody flags/floorSurface
    r(0, 1, 0); r(0, 2, 0)                       # noJumpFromBody fan-out
    r(0, 1, 0)                                   # packedAt :99
    r(0, 1, 0); fanout6(r, 0, 1, 0)              # requireFloor :126
    r(0, 3, 0)                                   # requireAirVertical :132
    for k in range(1, SCAN + 1):                 # macro loop :163/:164
        r(0, k, 0); r(0, k, 0); fanout6(r, 0, k, 0)
    r(0, SCAN + 1, 0); r(0, SCAN + 2, 0)         # landing body :176/:177
emit("Pillar", _pillar)


# --- Fall -----------------------------------------------------------------------------
def _fall(r):
    r(0, 1, 0); r(0, 0, 0)                       # hang prologue :291 :295
    for dx, dz in CARD:
        r(dx, 0, dz)                             # flagsAt :311
        r(dx, 1, dz); r(dx, 2, dz)               # passable :314 seam verify
        r(dx, 0, dz)                             # floorGapAt :327 (nibble)
        for k in range(1, SCAN + 1):
            r(dx, -k, dz)                        # phase-1 column scan :357
        r(dx, -SCAN, dz)                         # water cushion / softness :433 :439
        for k in range(1, SCAN + 1):
            r(dx, -k, dz)                        # transit loop :514
        r(dx, -SCAN, dz)                         # floorHazardCost :546
emit("Fall", _fall)


# --- Parkour (cardinal gaps + prisms + down-scan + rising + offset tier) ---------------
def _parkour(r):
    r(0, 0, 0, 5)                                # 5 distinct prologue resolves of F
    r(0, 1, 0); r(0, 2, 0)                       # noJumpFromBody / bodyTransitLight fan-out
    r(0, 3, 0)                                   # packedAt :474 takeoff head
    for dx, dz in CARD:
        for c in range(1, GAP + 2):
            r(c * dx, 0, c * dz)                 # forward walk :561
        for c in range(1, GAP + 1):
            r(c * dx, 1, c * dz)                 # flat feet :619 / rising detect :689
            r(c * dx, 2, c * dz)                 # flat head :626
            for dr in range(1, SCAN + 1):
                r(c * dx, -dr, c * dz)           # falling down-scan :735
        for k in range(1, GAP + 1):              # prisms :800/:804/:808
            r(k * dx, 1, k * dz); r(k * dx, 2, k * dz); r(k * dx, 3, k * dz)
        for k in range(0, GAP + 1):              # rising raised-arc row :866
            r(k * dx, 4, k * dz)
        for c in (2, 3):                         # offset tier :916/:955/:960
            for side in (1, -1):
                lx, lz = -dz * side, dx * side
                r(c * dx + lx, 0, c * dz + lz)
                r(c * dx + lx, 1, c * dz + lz)
                r(c * dx + lx, 2, c * dz + lz)
emit("Parkour", _parkour)


# --- DiagonalParkour (corner-dominated) ------------------------------------------------
def _dparkour(r):
    r(0, 0, 0, 5); r(0, 1, 0); r(0, 2, 0); r(0, 3, 0)
    for dx, dz in DIAG:
        for c in range(1, 4):
            r(c * dx, 0, c * dz)                 # forward walk :216
        r(3 * dx, 1, 3 * dz); r(3 * dx, 2, 3 * dz)   # landing body :253/:254
        for k in range(1, 3):                    # prisms :317/:321/:325
            r(k * dx, 1, k * dz); r(k * dx, 2, k * dz); r(k * dx, 3, k * dz)
        for k in range(1, 4):                    # corner pair :357 + body :365
            for cx, cz in ((k * dx, (k - 1) * dz), ((k - 1) * dx, k * dz)):
                r(cx, 0, cz)
                r(cx, 1, cz); r(cx, 2, cz); r(cx, 3, cz)
emit("DiagonalParkour", _dparkour)


# --- Climb ----------------------------------------------------------------------------
def _climb(r):
    r(0, 1, 0)                                   # packedAt :204 feet
    r(0, 0, 0)                                   # packedAt :213 trapdoor stance
    r(0, 2, 0)                                   # packedAt :223 continue
    r(0, 3, 0)                                   # packedAt :232/:268/:312 head
    r(0, 0, 0)                                   # packedAt :322 climb-down
    r(0, -1, 0)                                  # packedAt :328 base dismount
    r(0, 0, 0)                                   # descriptorAt :438 hang test
    for dx, dz in CARD:
        r(dx, 0, dz)                             # packedAt :395 top-entry mouth
        r(dx, -1, dz)                            # packedAt :399 ladder below
        r(dx, 1, dz); r(dx, 2, dz)               # packedAt :409/:414 crossing
        r(dx, 1, dz)                             # packedAt :446 grab loop
        r(dx, 0, dz); r(dx, 2, dz)               # dismount :480/:485
        r(dx, 0, dz); r(dx, -1, dz)              # grab guard :527/:530
emit("Climb", _climb)


# --- STANDING fluid family -------------------------------------------------------------
def _swim(r):
    r(0, 1, 0)                                   # packedAt :119 feet
    r(0, 2, 0)                                   # packedAt :127 rise feet
    r(0, 3, 0)                                   # bodyClear :195 rise head
    r(0, 0, 0)                                   # packedAt :136 sink feet
    r(0, 2, 0, 2); r(0, 0, 0, 2)                 # dominance :234/:236 (built+water)
    for dx, dz in CARD:
        r(dx, 1, dz, 2)                          # prone-progress probe :242
        for wf in range(1, -SCAN, -1):           # lateral down-scan :169/:170
            r(dx, wf, dz, 2)
            r(dx, wf + 1, dz, 2)                 # head test :176/:177
emit("Swim", _swim)


def _startsprintswim(r):
    r(0, 1, 0, 2); r(0, 2, 0, 2); r(0, 0, 0, 2)  # :32 :37 :45  (built+water each)
emit("StartSprintSwim", _startsprintswim)


def _ridebubble(r):
    for dx, dz in CARD:
        r(dx, 1, dz, 2)                          # built + bubbleUp :128 — EVERY standing pop
    for k in range(2, SCAN + 2):                 # column scan :132 (one column modelled)
        r(1, k, 0)
emit("RideBubbleColumn", _ridebubble)


# ======================= report =======================================================
total = sum(counts.values())
distinct = len(counts)
print(f"read CALLS  : {total}")
print(f"distinct cells: {distinct}")
print(f"amplification : {total / distinct:.2f}x")
print()
print("top 15 contended cells (dx,dy,dz) -> calls  [movements]")
for cell, n in counts.most_common(15):
    movers = sorted(m for m, c in by_move.items() if cell in c)
    print(f"  {str(cell):>14} -> {n:3d}  [{len(movers)} moves] {', '.join(movers)}")
print()
print("reads by movement:")
for m, c in sorted(by_move.items(), key=lambda kv: -sum(kv[1].values())):
    print(f"  {m:<18} {sum(c.values()):4d} calls / {len(c):3d} cells")

# ======================= plots ========================================================
outdir = sys.argv[1] if len(sys.argv) > 1 else "."
os.makedirs(outdir, exist_ok=True)

xs = [c[0] for c in counts]
ys = [c[1] for c in counts]
zs = [c[2] for c in counts]
X0, X1 = min(xs), max(xs)
Y0, Y1 = min(ys), max(ys)
Z0, Z1 = min(zs), max(zs)
vmax = max(counts.values())

CMAP = LinearSegmentedColormap.from_list(
    "reads", ["#f7fbff", "#c6dbef", "#6baed6", "#2171b5", "#08306b"])

# ---- (1)+(3) X/Y slice per Z, annotated with the read count --------------------------
zvals = sorted({c[2] for c in counts})
ncol = min(4, len(zvals))
nrow = (len(zvals) + ncol - 1) // ncol
fig, axes = plt.subplots(nrow, ncol, figsize=(4.6 * ncol, 4.4 * nrow), squeeze=False)
flat = [a for row in axes for a in row]
for a in flat[len(zvals):]:
    a.axis("off")
for ax, z in zip(flat, zvals):
    grid = np.zeros((Y1 - Y0 + 1, X1 - X0 + 1))
    for (cx, cy, cz), n in counts.items():
        if cz == z:
            grid[cy - Y0, cx - X0] = n
    ax.imshow(grid, origin="lower", cmap=CMAP, vmin=0, vmax=vmax,
              extent=[X0 - .5, X1 + .5, Y0 - .5, Y1 + .5])
    for yy in range(Y0, Y1 + 1):
        for xx in range(X0, X1 + 1):
            v = grid[yy - Y0, xx - X0]
            if v:
                ax.text(xx, yy, int(v), ha="center", va="center", fontsize=9,
                        color="white" if v > vmax * .55 else "#08306b")
    ax.add_patch(plt.Rectangle((-.5, -.5), 1, 1, fill=False, ec="#d62728", lw=1.8))
    tot = int(grid.sum())
    ax.set_title(f"dz = {z:+d}" + ("  (bot column)" if z == 0 else "") + f"   —  {tot} calls",
                 fontsize=11, fontweight="bold" if z == 0 else "normal")
    ax.set_xlabel("dx"); ax.set_ylabel("dy  (up)")
    ax.set_xticks(range(X0, X1 + 1)); ax.set_yticks(range(Y0, Y1 + 1))
    ax.tick_params(labelsize=8); ax.grid(color="#dddddd", lw=.4)
fig.suptitle("Block A* per-pop cell-read footprint — X/Y slice per Z  (STANDING pop, upper envelope)\n"
             f"{total} read calls over {distinct} cells = {total/distinct:.1f}x amplification"
             "   · red box = popped floor cell F · numbers = read CALLS", fontsize=10)
fig.tight_layout(rect=[0, 0, 1, 0.955])
p1 = os.path.join(outdir, "read_footprint_slices.png")
fig.savefig(p1, dpi=150); plt.close(fig)

# ---- (2)+(4) 3D, darker = read more -------------------------------------------------
cells = list(counts)
cs = np.array([counts[c] for c in cells], float)
cx_ = np.array([c[0] for c in cells]); cy_ = np.array([c[1] for c in cells])
cz_ = np.array([c[2] for c in cells])
# emphasise the hot core: area grows ~quadratically with read count, cold cells stay pinpricks
size = 12 + 620 * (cs / vmax) ** 1.7

PANELS = [(20, -60, None, f"all {distinct} cells touched"),
          (20, -60, 10, "the contended core (>= 10 read calls)")]
fig = plt.figure(figsize=(16, 7.2))
for idx, (elev, azim, thresh, ttl) in enumerate(PANELS):
    ax = fig.add_subplot(1, 2, idx + 1, projection="3d")
    m = cs >= thresh if thresh else np.ones(len(cs), bool)
    if thresh:  # ghost the cold cells so the core keeps its spatial context
        ax.scatter(cx_[~m], cz_[~m], cy_[~m], c="#dddddd", s=6, alpha=.35, depthshade=False)
    ax.scatter(cx_[m], cz_[m], cy_[m], c=cs[m], cmap=CMAP, vmin=0, vmax=vmax,
               s=size[m], alpha=.95, edgecolors="#37474f", linewidths=.45, depthshade=False)
    ax.scatter([0], [0], [0], marker="*", s=520, c="#d62728",
               edgecolors="k", linewidths=.8, depthshade=False, zorder=10)
    ax.set_xlabel("dx", labelpad=4); ax.set_ylabel("dz", labelpad=4)
    ax.set_zlabel("dy  (up)", labelpad=4)
    ax.set_title(ttl, fontsize=11, pad=2)
    ax.tick_params(labelsize=8, pad=1)
    ax.set_box_aspect((1, 1, .85))
    ax.view_init(elev=elev, azim=azim)
sm = plt.cm.ScalarMappable(cmap=CMAP, norm=plt.Normalize(0, vmax))
fig.colorbar(sm, ax=fig.axes, shrink=.60, pad=.11,
             label="read CALLS per cell  (darker + larger = read more often)")
fig.suptitle("Block A* per-pop cell-read footprint — 3D  (STANDING pop, upper envelope)"
             "    red star = popped floor cell F", fontsize=12, y=.97)
fig.subplots_adjust(left=.02, right=.80, top=.90, bottom=.03, wspace=.06)
p2 = os.path.join(outdir, "read_footprint_3d.png")
fig.savefig(p2, dpi=150); plt.close(fig)

print(f"\nwrote {p1}\nwrote {p2}")
