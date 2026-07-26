# Cliff invalid-region-crossing repro — world builder (#5 invalidation-memory)

Rebuilds the frozen FLAT master world used by `scripts/run-autotest-cliff.ps1`. The world is a
single-structure cliff that the region tier prices as crossable but a no-place/no-break bot cannot
climb at the block tier (see `internal_docs/DIAGNOSIS-invalid-region-crossing-cliff.md`).

## Geometry (flat overworld, minY=-64)

```
   +X (east) ->                          cliff-top surface y=8 (feet y=9)  ── GOAL (144,9,0)
                          ┌──────────────────────────────────────────────
                          │ sheer 8-block WEST wall at x=64 (leaf/L1/L2 region boundary)
                          │ spans the FULL z-range [-40..40]  => no walk-around
   low-ground surface y=0 │
   (feet y=1)             │
   START (16,1,0) ────────┘
```
- Base stone slab, top y=0: x[-16..176], z[-40..40], y[-2..0].
- High mesa raise, top y=8: x[64..176], z[-40..40], y[1..8].
- Open sky above both ledges => the low-side air 6-connects OVER the lip to the high-side air =>
  the two region fragments' footprints overlap on the x=64 face => a caps-unconditional walk-portal
  is emitted, floor-anchored to the true 8-block rise. The block tier has no place/break move that
  gains that height => BLOCKED.
- START and GOAL are 128 blocks apart in X = two level-2 (64-block) regions ("a full region away").

## Rebuild steps

Requires JAVA_HOME -> JDK 21 and Python 3. From the mc-1.21 worktree root:

1. Stage a plain flat dedicated server in `run/` (back up your own `run/server.properties` first):
   ```
   cp run/server.properties run/server.properties.bak      # if you have one
   cp scripts/repro-cliff/build-server.properties run/server.properties
   ./gradlew :fabric:1.21.11:runServer                      # background; waits for RCON on :25599
   ```
2. Generate + apply the geometry over RCON (server must be up; password `orebit`):
   ```
   cd scripts/repro-cliff && python gen_fills.py            # writes cmds-build.txt
   # forceload is required because /fill does not load chunks:
   python rcon.py 127.0.0.1 25599 orebit <(printf 'forceload add -16 -40 176 40\n'; cat cmds-build.txt)
   python rcon.py 127.0.0.1 25599 orebit <(printf 'save-all flush\nstop\n')
   ```
3. Freeze the saved world (drop `session.lock` and the build-time persisted HPA so the region tier
   rebuilds fresh):
   ```
   rm -rf scripts/autotest-cliff/world-master/world
   cp -r run/repro-cliff-build scripts/autotest-cliff/world-master/world
   rm -f  scripts/autotest-cliff/world-master/world/session.lock
   rm -rf scripts/autotest-cliff/world-master/world/orebit
   cp run/server.properties.bak run/server.properties       # restore your own
   ```

The master lands at `scripts/autotest-cliff/world-master/world` (gitignored, ~5 MB — owner decides
whether it goes on the `autotest-world` orphan branch, like the main autotest master).

## Run the repro

```
scripts/run-autotest-cliff.ps1 -Rtrace -BotDebug            # region-tier proposal only, then halt
scripts/run-autotest-cliff.ps1 -BotDebug                    # full goto; reads navstat_* from the result file
```
