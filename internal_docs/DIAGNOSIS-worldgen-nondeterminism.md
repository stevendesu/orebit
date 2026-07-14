# Diagnosis — same-seed worldgen is non-deterministic for VEGETATION (autotest start cell)

Session 2026-07-13, headless mc-1.21 era, node `1.21.11`, JDK 21. Read-only, no bot behavior changed.

## TL;DR

The `HeadlessAutotest` start cell `-32,134,153` (seed `-6453171316297072906`, `level-type=normal`) was
believed to be an owner-scouted **jungle treetop**. It is not reliably anything:

1. **Terrain is deterministic; vegetation is NOT.** Across **5 fresh regenerations** of the same seed +
   version + settings (world deleted each run), the **stone/dirt/grass column is byte-identical** (md5
   `3b4e…6547`), but the **trees produced 3 distinct layouts** (`signature=` in the probe dump):
   - run 1 → oak canopy only, **no jungle tree** (`1c83a774eb1540b5`)
   - runs 2,3 → jungle tree + an oak leaf at y=109 (`a87cf76ab07f0b41`)
   - runs 4,5 → jungle tree, no oak leaf at y=109 (`a2ba768663566e74`)
   Not a cold-vs-warm/JIT artifact (runs 4,5 differ from 2,3 — all warm).
2. **The start cell is MID-AIR in every run.** The canopy never exceeds ~y=120; `y=134` is 14–24 blocks of
   air above it. The "y=134 treetop" was almost certainly a *rare extra-tall/mega jungle-tree* generation —
   exactly what this non-determinism makes non-reproducible. (Same class of "mid-air start" the
   `DIAGNOSIS-origin-shortpath-wander` doc flagged for the OLD coord.)

## Mechanism (evidence-consistent, mechanism not exhaustively proven)

Minecraft noise terrain (heightmap, stone/dirt/grass) is reproducible for a fixed seed. **Feature (tree)
decoration runs on the parallel chunk-generation worker pool**, and large trees whose canopies cross chunk
boundaries get committed to shared border cells in **thread-scheduling order** — so which tree "wins" a
border varies run to run. The clean terrain-deterministic / vegetation-non-deterministic split is the
fingerprint of this. (Unrun confirmation: `-Dmax.bg.threads=1` should collapse the variance if the cause is
purely parallel-gen ordering.)

## Resolution — FROZEN master world (owner-chosen)

Fighting the parallel-gen is unnecessary; **freeze a pre-generated world and copy a pristine master into the
run dir each run.** Minecraft only mutates the COPY, so the master stays byte-identical → every run starts
from the exact same blocks (deterministic), and the bot's broken leaves / placed cobble + MC's own
`session.lock`/`level.dat`/chunk writes are discarded next run (edit-safe). This also keeps the *natural*
world cheap (caves/lava/ravines/tall trees from one gen, not hand-built) and preserves the canopy-start
scenario the owner wants (cheap 3D leaf-breaking → the flooding pathology; tall tree → the get-down puzzle).

- **Owner generates the master** in single-player and provides **fixed start coords**; the harness already
  takes `-Porebit.autotest.start/goal`.
- **Runner:** `scripts/run-autotest.ps1 -MasterWorld <path>` copies master → `run/autotest/world` each run
  (else legacy seed-regen). See that file's `-MasterWorld` doc.
- **CAVEAT — corridor coverage:** a chunk ABSENT from the master is generated on-the-fly from the seed →
  back to non-deterministic vegetation. The **start-area** chunks (where the early tree-descent / fall bug
  lives) are covered as long as the owner explored to the tree; the **full start→goal corridor** must be
  pre-generated in the master or the goal shortened.

## Artifacts (worktree `orebit-mc121-wt`, uncommitted)

- `HeadlessAutotest.java` — read-only probe seam `-Dorebit.autotest.probeOnly=true` (`probeStartCell`):
  dumps the start column + 5×5 `topSolidY` grid + a determinism `signature=` to
  `run/autotest/orebit-autotest-startprobe.txt`, then halts (no bot). Reusable to validate any start cell.
- `fabric/build.gradle.kts` — forwards `orebit.autotest.probeOnly`.
- `scripts/run-startprobe.ps1` — runs the probe N times on fresh regens, collects `scripts/startprobe-out/
  run-*.txt`, and compares signatures. This is how the non-determinism above was proven.
- `scripts/run-autotest.ps1` — `-MasterWorld <path>` frozen-world mode.

## Next

- Owner supplies the master world + start coords → run `run-autotest.ps1 -MasterWorld <path> -Start <x,y,z>`
  and (with `-Trace`) capture the place→descend→fall sequence to diagnose the "fall below the block you're
  standing on" bug (Fall's `candidates()` only ever lands in a CARDINAL-NEIGHBOR column, so the bug is
  either a follower mis-execution or an invalid place→descend→fall *sequence* — the trace disambiguates).
- Propagation: `HeadlessAutotest.java` is common (→ `core`); `scripts/*` + the `fabric/build.gradle.kts`
  autotest key are era/tooling.
