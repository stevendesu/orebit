#!/usr/bin/env python3
"""Generate RCON commands to build the LATERAL-CLIMB repro geometry in a flat world.

Forces a no-place/no-break bot to cross an air gap by clinging to a single horizontal row of
VINES on the face of a wall and easing SIDEWAYS across (lateral Climb, Δy == 0), because every
other crossing is denied:

  - the floor GAP is 5 blocks wide  => beyond flat parkour (tops out at ~3);
  - the bot cannot place (no bridge) nor break (no dig);
  - the vine strip is ONE block tall at feet level => nothing to climb UP, so the bot can't
    ratchet up and walk the wall top as a bridge;
  - the backing wall is tall + solid (no walkable top reachable from the vine row);
  - the gap has no floor at ANY z (the vine at z=0 is the sole crossing) => no walk-around.

Geometry (feet coords for the bot; block coords for /fill; flat overworld, base slabs top y=0):

     +X (east) ->
   z=1 (south)  . . . . . [#####]  tall solid WALL  x[8..12] y[1..8]   (backs the vines)
   z=0 (cross)  WEST-LEDGE [VVVVV]  EAST-LEDGE       vines x[8..12] y=1 (single feet-level row)
                ledge y=0  <-gap->  ledge y=0
                x[-8..7]   x[8..12] x[13..28]
       START (3,1,0)  --walk-->  GRAB vine @x8 --lateral climb x8..12-->  step off @x13 --> GOAL (17,1,0)

  - West ledge : solid stone x[-8..7],  z[-8..8], y[-2..0]   (feet stand at y=1)
  - East ledge : solid stone x[13..28], z[-8..8], y[-2..0]
  - Gap        : x[8..12] (5 cells), z[-8..8] : NO floor (open air down to the flat surface far below)
  - Wall       : solid stone x[8..12], z=1 ONLY, y[1..8]     (floating; backs the vines to the +Z/south)
  - Vines      : minecraft:vine[south=true] at x[8..12], z=0, y=1 (attached to the wall at z+1) — the
                 single climbable row the bot clings to. Air above (y>=2) so there is nothing to climb UP.

  START feet = (3,1,0) on the west ledge; GOAL feet = (17,1,0) on the east ledge, 14 blocks away in +X.
  Both are within one level-1 (32-block) region [0..31] so the REGION tier is not the variable under test;
  the BLOCK tier's lateral-Climb emit is.
"""

X0, X1 = -8, 28           # corridor X extent
Z0, Z1 = -8, 8            # corridor Z extent
XG0, XG1 = 8, 12          # gap X extent (5 cells) — no ledge floor here
YBASE = -2
YLOW = 0                  # ledge top surface (feet at y=1)
WALL_Z = 1                # the backing wall sits one block south of the crossing line
WALL_YTOP = 8             # wall is 8 tall (y=1..8): genuinely tall, no reachable/walkable top
VINE_Y = 1               # single climbable row at feet level
BLOCK = "minecraft:stone"
VINE = "minecraft:vine[south=true,north=false,east=false,west=false,up=false]"
FILL_LIMIT = 32000        # vanilla /fill cap is 32768; stay under

START = (3, 1, 0)
GOAL = (17, 1, 0)


def boxes(x0, x1, y0, y1, z0, z1):
    """Yield (ax0,ay0,az0,ax1,ay1,az1) sub-boxes each <= FILL_LIMIT blocks, split along z."""
    xw = x1 - x0 + 1
    yh = y1 - y0 + 1
    per_z = xw * yh
    zstep = max(1, FILL_LIMIT // per_z)
    z = z0
    while z <= z1:
        ze = min(z1, z + zstep - 1)
        yield (x0, y0, z, x1, y1, ze)
        z = ze + 1


def main():
    lines = []
    lines.append("# --- world setup ---")
    lines.append("gamerule doDaylightCycle false")
    lines.append("gamerule doWeatherCycle false")
    lines.append("gamerule doMobSpawning false")
    lines.append("gamerule doFireTick false")
    lines.append("gamerule randomTickSpeed 0")
    lines.append("time set day")
    lines.append("weather clear 1000000")
    lines.append(f"setworldspawn {START[0]} {START[1]} {START[2]}")

    lines.append("# --- west ledge (top surface y=0) ---")
    for b in boxes(X0, XG0 - 1, YBASE, YLOW, Z0, Z1):
        lines.append(f"fill {b[0]} {b[1]} {b[2]} {b[3]} {b[4]} {b[5]} {BLOCK}")
    lines.append("# --- east ledge (top surface y=0) ---")
    for b in boxes(XG1 + 1, X1, YBASE, YLOW, Z0, Z1):
        lines.append(f"fill {b[0]} {b[1]} {b[2]} {b[3]} {b[4]} {b[5]} {BLOCK}")

    lines.append("# --- tall solid backing wall at z=1 over the gap (floats; backs the vines) ---")
    lines.append(f"fill {XG0} {VINE_Y} {WALL_Z} {XG1} {WALL_YTOP} {WALL_Z} {BLOCK}")

    lines.append("# --- single feet-level vine row on the gap face (z=0), attached south to the wall ---")
    for x in range(XG0, XG1 + 1):
        lines.append(f"setblock {x} {VINE_Y} 0 {VINE}")

    lines.append("# --- verify: ledges, gap, vines, wall, exit, goal ---")
    lines.append(f"execute if block {START[0]} 0 0 minecraft:stone run say VERIFY start-floor@{START[0]},0,0 = stone OK")
    lines.append("execute if block 7 0 0 minecraft:stone run say VERIFY west-edge@7,0,0 = stone OK")
    lines.append("execute if block 8 0 0 minecraft:air run say VERIFY gap-floor@8,0,0 = air OK")
    lines.append("execute if block 8 1 0 minecraft:vine run say VERIFY vine@8,1,0 = vine OK")
    lines.append("execute if block 12 1 0 minecraft:vine run say VERIFY vine@12,1,0 = vine OK")
    lines.append("execute if block 8 2 0 minecraft:air run say VERIFY vine-head@8,2,0 = air OK")
    lines.append("execute if block 8 1 1 minecraft:stone run say VERIFY wall@8,1,1 = stone OK")
    lines.append("execute if block 13 0 0 minecraft:stone run say VERIFY east-edge@13,0,0 = stone OK")
    lines.append(f"execute if block {GOAL[0]} 0 0 minecraft:stone run say VERIFY goal-floor@{GOAL[0]},0,0 = stone OK")
    lines.append(f"execute if block {GOAL[0]} 1 0 minecraft:air run say VERIFY goal-feet@{GOAL[0]},1,0 = air OK")
    lines.append("save-all flush")

    with open("cmds-build.txt", "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    nfill = sum(1 for l in lines if l.startswith("fill "))
    nset = sum(1 for l in lines if l.startswith("setblock "))
    print(f"wrote cmds-build.txt : {len(lines)} lines, {nfill} fills, {nset} setblocks")


if __name__ == "__main__":
    main()
