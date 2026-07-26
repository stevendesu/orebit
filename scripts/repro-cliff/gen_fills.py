#!/usr/bin/env python3
"""Generate RCON /fill commands to build the cliff repro geometry in a flat world.

Geometry (feet coords for bot; block coords for fills):
  - A solid stone BASE slab across the whole corridor, top surface at y=YLOW (=0):
        x in [X0..X1], z in [Z0..Z1], y in [YBASE..YLOW]
  - A solid stone HIGH MESA raising the +X half to surface y=YHIGH (=8):
        x in [XW..X1], z in [Z0..Z1], y in [YLOW+1..YHIGH]
  => low ground (x<XW) top at y=0 (feet y=1); cliff-top (x>=XW) top at y=8 (feet y=9).
  => a sheer 8-block WEST face of the mesa at x=XW spanning the FULL z-range [Z0..Z1]
     (extends far in +-Z => no walk-around within the region).
  => open sky above both ledges => the low-side air is 6-connected OVER the lip to the
     high-side air => the two region fragments' footprints overlap on the x=XW face =>
     the region tier emits a (caps-unconditional) walk-portal across the cliff, priced at
     the true 8-block rise; a no-place/no-break bot cannot realize it at the block tier.

  START feet = (16, 1, 0) on the low ground; GOAL feet = (144, 9, 0) on the cliff-top,
  128 blocks (= two level-2 / 64-block regions) away in +X. Cliff face x=64 is a clean
  leaf(16)/level-1(32)/level-2(64) region boundary.
"""

XW   = 64
X0, X1 = -16, 176
Z0, Z1 = -40, 40
YBASE = -2
YLOW  = 0
YHIGH = 8
BLOCK = "minecraft:stone"
FILL_LIMIT = 32000   # vanilla /fill cap is 32768; stay under

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
    lines.append(f"setworldspawn 16 1 0")
    lines.append("# --- base slab (top surface y=0 across whole corridor) ---")
    for (ax0,ay0,az0,ax1,ay1,az1) in boxes(X0,X1,YBASE,YLOW,Z0,Z1):
        lines.append(f"fill {ax0} {ay0} {az0} {ax1} {ay1} {az1} {BLOCK}")
    lines.append("# --- high mesa raise (top surface y=8 for x>=XW) ---")
    for (ax0,ay0,az0,ax1,ay1,az1) in boxes(XW,X1,YLOW+1,YHIGH,Z0,Z1):
        lines.append(f"fill {ax0} {ay0} {az0} {ax1} {ay1} {az1} {BLOCK}")
    # verification probes
    lines.append("# --- verify: low floor, cliff wall column, high floor ---")
    lines.append("execute if block 16 0 0 minecraft:stone run say VERIFY low-floor@16,0,0 = stone OK")
    lines.append("execute if block 63 1 0 minecraft:air run say VERIFY low-air@63,1,0 = air OK")
    lines.append("execute if block 64 1 0 minecraft:stone run say VERIFY wall@64,1,0 = stone OK")
    lines.append("execute if block 64 8 0 minecraft:stone run say VERIFY mesatop@64,8,0 = stone OK")
    lines.append("execute if block 64 9 0 minecraft:air run say VERIFY mesa-air@64,9,0 = air OK")
    lines.append("execute if block 144 8 0 minecraft:stone run say VERIFY high-floor@144,8,0 = stone OK")
    lines.append("execute if block 144 9 0 minecraft:air run say VERIFY high-air@144,9,0 = air OK")
    lines.append("save-all flush")
    with open("cmds-build.txt", "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    nfill = sum(1 for l in lines if l.startswith("fill "))
    print(f"wrote cmds-build.txt : {len(lines)} lines, {nfill} fill commands")

if __name__ == "__main__":
    main()
