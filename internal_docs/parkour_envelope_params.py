#!/usr/bin/env python3
"""
Parkour envelope — TAKEOFF-CONDITION-PARAMETERIZED physics model (the bake SPEC).

Extends internal_docs/parkour_envelope_verify.py (validated base model) so the admitted
max gap per movement class is a function of the takeoff CONDITIONS:

  P1  takeoffSurfaceY  — fractional height the feet leave from (full=1.0, slab=0.5, stair edge)
  P2  gsf              — Block.getSpeedFactor() of the floor STOOD ON (soul sand = 0.4)
  P3  occH, occV       — stuckSpeedMultiplier of a passable slow block the BODY occupies
                         (berry (0.8,0.75), powder snow (0.9,1.5); cobweb is hard-refused)

Landing surface is conservatively assumed full-block (landingSurfaceY = 1.0).
Takeoff point stays the executor's +0.35 trigger (ratified: the ~0.45-block hitbox-overhang
runway the plan never schedules is the anti-tick-perfect wiggle room). MAX_CLEARED_AIR = 3.0.

Run:  python internal_docs/parkour_envelope_params.py
No Java, no production code. This file is the derivation the Java ParkourEnvelope bakes.

------------------------------------------------------------------------------------------
VERIFIED MC MECHANICS (1.21.11 mojang-mapped, Vineflower on the loom-cached merged jar)
------------------------------------------------------------------------------------------
Entity.move(MoverType, Vec3 movement):
  * stuckSpeedMultiplier (set by makeStuckInBlock last tick):
        if lengthSqr>1e-7 and type!=PISTON:  movement = movement.multiply(stuckSpeedMultiplier)
        then stuckSpeedMultiplier=ZERO; setDeltaMovement(ZERO)
    -> scales the POSITION DELTA on ALL THREE AXES the tick the AABB is inside; then zeroes
       stored velocity. (We model the conservative "in the block the whole arc" approximation
       per the task: scale the H budget by occH, scale vy0 by occV; the zeroing is NOT modeled
       -> a deliberate under-estimate of reach, the desired safe direction.)
  * getBlockSpeedFactor at the END of move:
        f = getBlockSpeedFactor();  setDeltaMovement(delta.multiply(f, 1.0, f))
    -> scales stored velocity X/Z ONLY (Y untouched), AFTER the move, i.e. affects NEXT tick.
       getBlockSpeedFactor() reads block-at-feet; if ==1.0 falls through to the block BELOW.
       Standing on soul sand -> below = soul sand -> 0.4. Once airborne (>~0.5 up) below = air
       -> 1.0, so the factor stops applying. On the JUMP tick the feet are only ~0.42 up so the
       block-below is still soul sand -> the jump tick's trailing drag is ALSO gsf'd.
  Block speed factors:  soul sand speedFactor(0.4F) [jumpFactor default 1.0 -> vy0 unchanged];
                        honey speedFactor(0.4F) jumpFactor(0.5F) [honey is REDUCED_JUMP-refused].
  Stuck multipliers:    cobweb (0.25,0.05,0.25) [hard refuse]; berry (0.8,0.75,0.8);
                        powder snow (0.9,1.5,0.9).
"""
import math

# ---- verified constants (unchanged from parkour_envelope_verify.py) ----
G     = 0.08
QV    = 0.98
QH    = 0.91
QG    = 0.6 * 0.91          # 0.546 ground drag
V0Y   = 0.42               # JUMP_STRENGTH default (blockJumpFactor 1.0 on soul sand)
BOOST = 0.2
SPEED = 0.1 * 1.3          # 0.13 sprint
INPUT = 0.98
A_G   = SPEED * (0.21600002 / (0.6 ** 3)) * INPUT   # ground input accel/tick = 0.127400
A_A   = 0.025999999 * INPUT                          # air input accel/tick    = 0.025480
TAKEOFF_EDGE       = 0.35   # cardinal takeoff trigger, blocks past cell centre
TAKEOFF_EDGE_ALONG = 0.40   # diagonal takeoff trigger, along-line
S2 = math.sqrt(2.0)
K  = G * QV / (1 - QV)      # 3.92 terminal
MAX_CLEARED_AIR = 3.0        # policy cap

# =========================================================================================
#  VERTICAL  (parameterized by occV: vy0 = V0Y * occV)
# =========================================================================================
def y(T, occV=1.0):
    """feet height above takeoff floor after T air ticks; vy(1) = 0.42*occV."""
    v0 = V0Y * occV
    return (v0 + K) * (1 - QV ** T) / (1 - QV) - K * T

def T_dy(dy, occV=1.0):
    """LAST tick whose feet are still >= dy (max over the whole arc; the rising arc dips
    below +1 early then re-crosses, so this must be a max, not a first-failure walk)."""
    cands = [t for t in range(1, 80) if y(t, occV) >= dy]
    return max(cands) if cands else 0

# =========================================================================================
#  HORIZONTAL  (parameterized by gsf on the ground/jump tick, and occH scaling the whole arc)
# =========================================================================================
def horiz_params(gsf=1.0):
    """Returns (V_INF, V1, V2) with the ground/jump-tick speed factor gsf applied.
       gsf multiplies the stored velocity X/Z at the end of each GROUND move (incl. the jump
       tick, whose block-below is still the slow floor). Airborne ticks (t>=2) have gsf=1."""
    v_inf = A_G / (1 - QG * gsf)              # ground steady state: u = u*QG*gsf + A_G
    v1    = v_inf * QG * gsf + BOOST + A_G    # jump tick: incoming stored vel had QG*gsf
    v2    = v1 * QG * gsf + A_A               # jump tick trailing drag QG*gsf (still on floor)
    return v_inf, v1, v2

def X(T, gsf=1.0, occH=1.0):
    """cumulative centre-travel after T ticks (tick1=jump). occH scales the whole H budget
    (body-in-slow-block whole-arc conservative model: stuck mult scales the position delta
    each tick on the H axes -> X_occ = occH * X)."""
    if T < 1:
        return 0.0
    _, v1, v2 = horiz_params(gsf)
    M = A_A / (1 - QH)
    base = v1 + (T - 1) * M + (v2 - M) * (1 - QH ** (T - 1)) / (1 - QH)
    return occH * base

# =========================================================================================
#  GEOMETRY  (required centre-travel per gap; unchanged — takeoff point is fixed at +0.35)
# =========================================================================================
def D_req_card(g):  return g + 0.2 - TAKEOFF_EDGE            # = g - 0.15
def D_req_diag(g):  return (g + 1) * S2 - 0.5 * S2 - 0.3 * S2 - TAKEOFF_EDGE_ALONG  # = (g+0.2)*sqrt2 - 0.40

# =========================================================================================
#  EFFECTIVE DELTA-Y  (fold takeoff surface height; landing assumed full block = 1.0)
# =========================================================================================
def eff_dy(classDy, takeoffSurfaceY):
    """classDy = node-level offset (flat 0, rise +1, fall -d). Feet leave from takeoffSurfaceY
    (above the takeoff-node base) and land on a full-block top (1.0 above the landing node).
       effΔy = classDy + (1.0 - takeoffSurfaceY)."""
    return classDy + (1.0 - takeoffSurfaceY)

# =========================================================================================
#  ADMISSION  — one rule + the policy cap
# =========================================================================================
def max_gap(classDy, diagonal, takeoffSurfaceY=1.0, gsf=1.0, occH=1.0, occV=1.0):
    edy = eff_dy(classDy, takeoffSurfaceY)
    T   = T_dy(edy, occV)
    budget = X(T, gsf, occH)
    Dreq = D_req_diag if diagonal else D_req_card
    # cleared-air policy cap (keyed on NODE class, per base design): flat/rise <=3 cleared air;
    # falling <= 3+drop.  cardinal cleared air = g ; diagonal = g*sqrt2.
    drop = -classDy if classDy < 0 else 0
    cap_air = MAX_CLEARED_AIR + drop
    gmax = 0
    for g in range(1, 9):
        cleared = g * S2 if diagonal else g
        if cleared > cap_air + 1e-9:
            break
        if Dreq(g) <= budget + 1e-9:
            gmax = g
        else:
            break
    return gmax, T, budget

# =========================================================================================
#  1. verified numbers + BASE-CASE reproduction
# =========================================================================================
print("=" * 88)
print("VERIFIED CONSTANTS / BASE MODEL")
print("=" * 88)
print(f"A_G={A_G:.6f}  A_A={A_A:.6f}  K={K}  y(6)={y(6):+.4f} (apex; JUMP_RISE 20/16=1.25)")
vinf, v1, v2 = horiz_params(1.0)
print(f"gsf=1.0:  V_INF={vinf:.6f} (x20={vinf*20:.3f} m/s)  V1={v1:.6f}  V2={v2:.6f}")
print(f"landing ticks & budgets (base, occV=1):")
for dy in (1, 0, -1, -2, -3):
    T = T_dy(dy); print(f"   dy={dy:+d}: T={T:2d}  y(T)={y(T):+.4f}  X(T)={X(T):.4f}")

print("\nBASE ENVELOPE (takeoffY=1.0, gsf=1.0, occ=none) — must be flat3/rise2/fall4/diag2:")
base = {}
for name, cdy, diag in (("flat", 0, False), ("rise+1", 1, False),
                        ("fall-1", -1, False), ("fall-2", -2, False), ("fall-3", -3, False),
                        ("diag", 0, True)):
    g, T, b = max_gap(cdy, diag)
    nxt_req = (D_req_diag if diag else D_req_card)(g + 1)
    base[name] = g
    print(f"   {name:7s} gmax={g}  (T={T:2d} budget={b:.4f}; g={g} req={(D_req_diag if diag else D_req_card)(g):.4f}"
          f"  g={g+1} req={nxt_req:.4f} short {nxt_req-b:+.4f})")
assert base["flat"] == 3 and base["rise+1"] == 2 and base["fall-1"] == 4 \
    and base["fall-2"] == 4 and base["fall-3"] == 4 and base["diag"] == 2, "BASE CASE REGRESSED"
print("   -> flat4 OUT, rise3 OUT, diag3 OUT confirmed (asserts pass)")

# =========================================================================================
#  2. PARAMETER SWEEP -> baked tables
# =========================================================================================
CLASSES = (("flat", 0, False), ("rise", 1, False),
           ("fall1", -1, False), ("fall2", -2, False), ("fall3", -3, False),
           ("diag", 0, True))

def envelope_row(takeoffSurfaceY, gsf, occH, occV):
    return {name: max_gap(cdy, diag, takeoffSurfaceY, gsf, occH, occV)[0]
            for name, cdy, diag in CLASSES}

def fmt(row):
    return f"flat {row['flat']}  rise {row['rise']}  fall {row['fall1']}/{row['fall2']}/{row['fall3']}  diag {row['diag']}"

OCC = (("none", 1.0, 1.0), ("berry", 0.8, 0.75), ("powder", 0.9, 1.5))
SURF = (("full 1.0", 1.0), ("slab 0.5", 0.5))
GSF = (("normal 1.0", 1.0), ("soulsand 0.4", 0.4))

def clamp_to(row, ceil):
    """A slow occupied block can never make a jump EASIER than the same takeoff WITHOUT it.
    Clamp each class to the same-(surface,gsf) occ=none ceiling -> the safe/conservative bake."""
    return {k: min(row[k], ceil[k]) for k in row}

print("\n" + "=" * 88)
print("FULL PARAMETER SWEEP  (RAW model, then NO-HELP CLAMP to same surf+gsf occ=none)")
print("  clamp rationale: occV=1.5 (powder) would fabricate reach a zeroed-velocity climb")
print("  block never gives; a slow body-cell only ever REDUCES reach.")
print("=" * 88)
seen = {}
for sname, sy in SURF:
    for gname, gf in GSF:
        ceil = envelope_row(sy, gf, 1.0, 1.0)          # occ=none ceiling for this surf+gsf
        for oname, oH, oV in OCC:
            raw = envelope_row(sy, gf, oH, oV)
            row = clamp_to(raw, ceil)
            key = tuple(row[c[0]] for c in CLASSES)
            seen.setdefault(key, []).append((sname, gname, oname))
            tag = "" if raw == row else f"   (raw {fmt(raw)} -> clamped)"
            print(f"  surf={sname:9s} gsf={gname:13s} occ={oname:7s} :  {fmt(row)}{tag}")

print("\n" + "=" * 88)
print("DISTINCT BAKED TABLES (clamped; which condition-combos collapse to the same row)")
print("=" * 88)
for i, (key, combos) in enumerate(seen.items(), 1):
    r = dict(zip((c[0] for c in CLASSES), key))
    print(f"  TABLE {i}: {fmt(r)}")
    for s, g, o in combos:
        print(f"           <- surf={s:9s} gsf={g:13s} occ={o}")

# ---- full takeoff-surface sweep for the COMMON axis (gsf=normal, occ=none) ----
print("\n" + "=" * 88)
print("TAKEOFF-SURFACE SWEEP  MAX_GAP[startTopY]  (gsf=normal, occ=none)")
print("  startTopY in sixteenths; takeoffSurfaceY = topY/16.  This is the primary Java table.")
print("=" * 88)
prev = None
for topY in range(16, 0, -1):
    row = envelope_row(topY / 16.0, 1.0, 1.0, 1.0)
    key = tuple(row[c[0]] for c in CLASSES)
    mark = "" if key != prev else "   (== above)"
    print(f"  topY={topY:2d} (surf={topY/16.0:.3f}) :  {fmt(row)}{mark}")
    prev = key
