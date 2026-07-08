# PERF DESIGN — Boot-time warm-up searches + eager-size scratch (CONDENSED)

> **STATUS (2026-07-03): half ADOPTED, half REVERTED.**
> **Warm-up: ADOPTED, LIVE, default true** — `worldmodel/pathing/NavWarmup.java`, synchronous in the
> third `onServerStarted` handler (after ConfigLoader + MiningModel), plateau-detected (min 4 / max 8
> rounds), `pathing.warmup` + `pathing.warmupBudgetMs` (1500) keys. Measured (fresh-JVM cold-start
> harness, 10 runs/arm): first search **21.8 → 0.67 ms p50 (32×)**, p90 30.0 → 0.81 ms; boot cost
> 475 ms median. Post-adoption it also sweeps `computeDepth` (depth-nibble parity with live grids).
> **Eager-size scratch: FALSIFIED + REVERTED** — `Nodes(8192, 8192)` cost pinned SHORT **+4–7%**
> (`Nodes.reset()` fills `mapRow` over CAPACITY → +28 KB fill per flood-free search).
> Results: `PERF-RESULTS-2026-07-03.md` §E5 (warm-up) / §E5b (eager-size).

**Mechanism:** ~500 synthetic searches over an in-memory `NavGridView` fixture at SERVER_STARTED walk the
whole pathfinder class graph through C2 before the first real command; the 16 ms cold first search was
~61% JIT warm-up, not allocation. The shipped mechanism is authoritative in `NavWarmup.java`; async
amendment (pool threads self-warm, warmth is JVM-global) in `DESIGN-background-pathfinding.md` §4.6.

**§ map (cited by code):** §1 problem statement (S1/S2/S3 profile attribution); §2 what exactly is cold
(decomposition of the 16 ms); §3 the `NavWarmup` mechanism — synthetic-grid seam, fixture construction
(no `PalettedContainer`), scenario set, hook point, synchronous-on-server-thread choice, cleanup;
§4 outcome + pointers; **§5 (section dropped post-adoption, still cited by `BlockPathfinder` ~line 250)
= the eager-size one-liner (512,1024 → 8192,8192) — FALSIFIED as above; any future eager sizing needs
lazy clearing / epoch-stamped slots, a design pass not a one-liner.**
