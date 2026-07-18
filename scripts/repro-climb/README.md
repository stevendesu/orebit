# Lateral-climb repro — world builder

Rebuilds the frozen FLAT master world used by `scripts/run-autotest-climb.ps1`. The world FORCES a
no-place/no-break bot to cross an air gap by clinging to a single feet-level row of VINES and easing
SIDEWAYS across (lateral `Climb`, Δy == 0) — the crossing every other move is denied.

## Geometry (flat overworld, minY=-64, base slabs top y=0, feet at y=1)

```
   +X (east) ->
   z=1 (south)  . . . . . [#####]  tall solid WALL  x[8..12] y[1..8]   (backs the vines, floats over the gap)
   z=0 (cross)  WEST-LEDGE [VVVVV]  EAST-LEDGE       vines  x[8..12] y=1 (single feet-level climbable row)
                ledge y=0  <-gap->  ledge y=0
                x[-8..7]   x[8..12] x[13..28]

   START (3,1,0) --walk +X--> edge x=7 --GRAB vine x8--> lateral climb x8..12 --step off x13--> GOAL (17,1,0)
```

- **West ledge** : solid stone `x[-8..7]`, `z[-8..8]`, `y[-2..0]` (feet stand at y=1).
- **East ledge** : solid stone `x[13..28]`, `z[-8..8]`, `y[-2..0]`.
- **Gap**        : `x[8..12]` (5 cells), `z[-8..8]` : NO floor — open air down to the flat surface far
  below. 5 wide = beyond flat parkour (~3), and no floor at ANY z => no walk-around; the z=0 vine is the
  sole crossing.
- **Wall**       : solid stone `x[8..12]`, `z=1` only, `y[1..8]` — floats over the gap, backs the vines to
  the south (+Z). Tall + solid with no top reachable from the vine row => the bot can't ratchet up and walk
  it as a bridge.
- **Vines**      : `minecraft:vine[south=true]` at `x[8..12]`, `z=0`, `y=1` — ONE row at feet level (air
  above at y>=2, so there is nothing to climb UP; lateral is the only Climb the row admits). Over the gap
  there is no standable ground beneath the vines, so `Climb`'s standability guard does not suppress the
  lateral grab.

Why lateral Climb is the FORCED, only route:
- can't place (no bridge), can't break (no dig);
- gap 5 > flat-parkour max (~3);
- single-row vine => no climb-up => no walk-across-the-top bridge;
- gap floorless at all z => no walk-around;
- => the block tier's ONLY realizable crossing is `Climb`'s lateral grab (x8) + cling (x9..12), then a
  `Traverse` dismount onto the east ledge (x13). START/GOAL sit in one level-1 (32-block) region so the
  region tier is not the variable — the block-tier lateral-Climb emit is.

## Rebuild steps

Requires JAVA_HOME -> JDK 21 and Python 3. From the mc-1.21 worktree root:

1. Stage a plain flat dedicated server in `run/` (back up your own `run/server.properties` first):
   ```
   cp run/server.properties run/server.properties.bak      # if you have one
   cp scripts/repro-climb/build-server.properties run/server.properties
   ./gradlew :fabric:1.21.11:runServer                      # background; waits for RCON on :25599
   ```
2. Generate + apply the geometry over RCON (server must be up; password `orebit`). forceload is required
   because /fill and /setblock do not load chunks:
   ```
   cd scripts/repro-climb && python gen_fills.py            # writes cmds-build.txt
   python rcon.py 127.0.0.1 25599 orebit <(printf 'forceload add -32 -32 48 32\n'; cat cmds-build.txt)
   python rcon.py 127.0.0.1 25599 orebit <(printf 'save-all flush\nstop\n')
   ```
3. Freeze the saved world (drop `session.lock` and the build-time persisted HPA so the region tier
   rebuilds fresh):
   ```
   rm -rf scripts/autotest-climb/world-master/world
   cp -r run/repro-climb-build scripts/autotest-climb/world-master/world
   rm -f  scripts/autotest-climb/world-master/world/session.lock
   rm -rf scripts/autotest-climb/world-master/world/orebit
   cp run/server.properties.bak run/server.properties       # restore your own
   ```

The master lands at `scripts/autotest-climb/world-master/world` (gitignored, ~5 MB — owner decides
whether it goes on the `autotest-world` orphan branch, like the main autotest master).

## Run the repro

```
scripts/run-autotest-climb.ps1 -BotDebug                    # full goto; PASS = arrived via lateral climb
scripts/run-autotest-climb.ps1 -Rtrace -BotDebug            # region-tier proposal only, then halt
```

Confirm from `run/autotest/logs/latest.log` (with -BotDebug): the crossing shows `[Orebit] exec Climb ->
<cell> (air)` lines (lateral grabs), the successive Climb lines are ~0.77 s apart (sneak speed
~15.44 t/block, ~3x a Traverse block), and `result=PASS reason=arrived`.
