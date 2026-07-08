# RESEARCH: Headless in-game testing (gametest server / client gametest / embedded server)

> **STATUS: PROPOSED (research — no build changes made).**

Research date: 2026-07-07 (branch `wip/s52-cleanup`). Goal: evaluate the owner's ask for
"a unit test that headlessly drives a real Minecraft client so we can measure world
generation, server tick, and movement logic accurately", against what actually exists in
the Fabric/vanilla tooling TODAY, and recommend a concrete wiring for Orebit's two build
eras. Web findings verified against current Fabric docs and fabric-api javadocs (links
inline); everything marked UNVERIFIED has not been executed against this repo.

---

## §1 The ask, and what exists in-repo today

**The ask.** Measure (a) world generation cost, (b) server tick time with bots active,
(c) movement-execution correctness/latency — under a *real* running game, not a synthetic
`NavGridView`. Phrased as "headlessly drive a real Minecraft client", but note the three
measurements are all **server-side**: worldgen, ticking, and bot movement all happen on the
server (the bot IS a `ServerPlayerEntity`). A real *client* is only needed for
client-perspective verification (rendering, what a player would see), which is a separate,
optional goal.

**What we have now** (visible on this branch + per CLAUDE.md for the mc-1.21 branch):

- **`fabric-loader-junit` headless JUnit** (`src/test/java`, ~60 files): runs under the Knot
  classloader, calls `SharedConstants.tryDetectVersion(); Bootstrap.bootStrap()` — which
  initializes **registries only**. There is **no `MinecraftServer`, no `ServerLevel`, no
  ticking, no worldgen** in this harness. All pathfinding tests/benchmarks run against the
  synthetic `NavGridView(minY, chunks)` seam or `RegionGrid.headless(...)`.
- **JMH benchmarks** ride the same harness (`BenchmarkRunnerTest` drives JMH inside the Knot
  classloader, `forks=0`) — mc-1.21 era only.
- On THIS branch (26-era scripts) the test source set is **emptied**
  (`build.gradle.kts:50-56`) — the harness is deferred for unobfuscated 26.x; all live test
  wiring is on the `mc-1.21` branch.
- Nothing in the repo touches the vanilla GameTest framework or fabric's gametest modules
  (grep for `gametest` finds only the deferral comment).

So the gap is exactly: **no harness with a live level**. Everything below is about closing
that gap.

---

## §2 Option landscape

### 2.1 Server game tests — `fabric-gametest-api-v1` on a vanilla GameTestServer

**What it is.** Minecraft ships a test framework (exposed to mods since 1.17 / 21w13a per
the [Minecraft wiki GameTest page](https://minecraft.wiki/w/GameTest)). Test methods are
annotated `@GameTest`, receive a test-helper context, anchor to a structure template, and
run on a **dedicated `GameTestServer`**: a headless (no GL, no window, pure JVM) dedicated
server variant that boots, loads/creates a test world, runs every registered test with
**real `ServerLevel`s and real ticking**, then **exits with pass/fail** (exit code = number
of failures). Fabric's wrapper module is
[`fabric-gametest-api-v1`](https://maven.fabricmc.net/docs/fabric-api-0.91.4+1.20.5/net/fabricmc/fabric/api/gametest/v1/package-summary.html):
you register a test class under the `fabric-gametest` entrypoint in `fabric.mod.json` and
the module handles discovery/registration.

**How it launches** ([Fabric docs: Automated Testing](https://docs.fabricmc.net/develop/automatic-testing)):

- Classic (works on any Loom, all fabric-api versions since ~1.17): a **server run config
  with `-Dfabric-api.gametest`** as a JVM arg — the fabric module intercepts server boot and
  runs the GameTestServer path instead of a normal server. A JUnit-style XML report can be
  emitted (`-Dfabric-api.gametest.report-file=<path>` classic; the modern vanilla runner has
  `--report`, see below).
- Modern (Fabric Loom ≥ ~1.10): the Loom DSL
  [`fabricApi.configureTests { ... }`](https://docs.fabricmc.net/develop/loom/fabric-api)
  (`createSourceSet`, `modId`, `enableGameTests`, `enableClientGameTests`, `eula`,
  `clearRunDirectory`, `username`) generates a **`runGametest`** server run task (and
  `runClientGameTest`, §2.2). Per the docs, server game tests are then also run as part of
  `./gradlew build`.
- Pure vanilla (no Fabric needed, production jar): since the 1.21.5 overhaul the server jar
  contains a dedicated entry point —
  `java -DbundlerMainClass="net.minecraft.gametest.Main" -jar server.jar --tests <selection> --report <junit-xml> --universe <dir>`
  ([wiki](https://minecraft.wiki/w/GameTest)).

**World type — the critical caveat:** the GameTestServer runs tests **in a superflat/void
test world** (wiki: "the test framework expects to run tests in a separate superflat
world"; Fabric docs: "tests run against a flat world"). Each test is anchored to a
**structure template**; Fabric supplies an empty template
([`FabricGameTest.EMPTY_STRUCTURE`](https://maven.fabricmc.net/docs/fabric-api-0.54.0+1.19/net/fabricmc/fabric/api/gametest/v1/FabricGameTest.html))
so a test does not need a hand-authored `.nbt` — but the *terrain around it is flat*. Test
code is free to build arbitrary terrain programmatically (or load Lithium-style structure
NBTs) and to walk entities far outside the structure bounds, but **you cannot measure real
overworld worldgen here** — the dimension is flat by design.

**Version support / the 1.21.5 fault line.** The vanilla framework was **overhauled in
1.21.5 (25w03a)** into a registry/data-driven system (test instances + test environments in
datapack registries; the old code annotation system removed —
[Fabric 1.21.5 blog](https://fabricmc.net/2025/03/24/1215.html),
[wiki](https://minecraft.wiki/w/GameTest)). Fabric-api responded with **its own
`@GameTest` annotation**
([`net.fabricmc.fabric.api.gametest.v1.GameTest`, fabric-api 0.119.2+1.21.5](https://maven.fabricmc.net/docs/fabric-api-0.119.2+1.21.5/net/fabricmc/fabric/api/gametest/v1/GameTest.html))
whose options map onto the data-driven fields, so mod tests stay annotation-driven with no
per-test JSON. So:

- **≤1.21.4** (most of the mc-1.21 era): vanilla `net.minecraft.gametest.framework.GameTest`
  annotation + `FabricGameTest` interface. Mature, widely used (Lithium keeps its
  correctness tests in gametest source sets with structure NBTs —
  [CaffeineMC/lithium](https://github.com/CaffeineMC/lithium)).
- **1.21.5 → 26.x**: Fabric's own `@GameTest` annotation. The module is **alive on 26.x** —
  fabric-api 26.1/26.2 changelogs still ship gametest-module improvements (e.g. "improve
  gametest logging and error messages", #5385, in
  [0.149.1+26.1.2](https://modrinth.com/mod/fabric-api/version/0.149.1+26.1.2) /
  [0.149.1+26.2](https://modrinth.com/mod/fabric-api/version/0.149.1+26.2)), and vanilla
  keeps refining the framework in 26.x (env attributes like `minecraft:difficulty`,
  `clock_time` — wiki).
- A test source shared across the whole 1.17.1→1.21.11 era would need version-gated
  annotations at the 1.21.5 line. **Simplest: pin the gametest harness to ONE dev version
  per era**, exactly like the JMH harness pins to 1.21.4.

**Windows viability: fully headless, first-class.** No GL, no window, no xvfb — it is a
dedicated server. Runs identically on Windows 11.

**Maturity: HIGH** (in fabric-api since ~1.17; the vanilla framework is what Mojang itself
tests with).

### 2.2 Client game tests — `fabric-client-gametest-api-v1` (a REAL client, driven)

**What it is.** An **experimental** fabric-api module (announced with 1.21.5, by
Earthcomputer — [Fabric 1.21.5 blog](https://fabricmc.net/2025/03/24/1215.html); the
internal framework predates that and was being stabilized during 1.21.4, e.g. client
gametest screenshot changes in
[fabric-api 0.114.0+1.21.4](https://modrinth.com/mod/fabric-api/version/0.114.0+1.21.4)).
It boots the **actual Minecraft client** and drives it from a dedicated test thread:
register a class implementing `FabricClientGameTest` under the `fabric-client-gametest`
entrypoint; run via the Loom-generated **`runClientGameTest`** task. Javadoc:
[`net.fabricmc.fabric.api.client.gametest.v1` (0.125.3+1.21.5)](https://maven.fabricmc.net/docs/fabric-api-0.125.3+1.21.5/net/fabricmc/fabric/api/client/gametest/v1/package-summary.html).

**Capabilities** (javadoc + docs):

- [`TestWorldBuilder`](https://maven.fabricmc.net/docs/fabric-api-0.125.3+1.21.5/net/fabricmc/fabric/api/client/gametest/v1/world/TestWorldBuilder.html):
  `create()` → `TestSingleplayerContext` (integrated server), `createServer([Properties])` →
  `TestDedicatedServerContext` (**spins a real dedicated server and joins it with the real
  client** — online-mode off, EULA required). Defaults are test-optimized flat/seed=1, but
  `adjustSettings(Consumer<WorldCreator>)` can change "anything that can be changed in the
  create world screen, **including generation settings**", and
  `setUseConsistentSettings(false)` defaults to **the default world preset in survival** —
  i.e. **real worldgen is available here**, unlike the GameTestServer.
- `TestInput` (simulate keys/mouse — the client actually plays), screenshot capture +
  golden-image comparison, deterministic threading (game paused unless the test waits
  ticks; **exactly one server tick per client tick**; network synchronizer for reproducible
  packet ordering).
- Tests run sequentially; after each, back to title screen; game closes at the end.

**NOT headless.** The client **opens a real window** (Fabric docs are explicit). For CI the
documented path is Loom **production run tasks** with
`useXVFB = true` (`net.fabricmc.loom.task.prod.ClientProductionRunTask`,
[Production Run Tasks](https://docs.fabricmc.net/develop/loom/production-run-tasks)) —
**xvfb is Linux-only**. On Windows 11 (the dev machine): the run works, but a game window
appears and grabs focus/renders on the real GPU. True Windows headless would mean Mesa
llvmpipe software GL via an `opengl32.dll` drop-in (mesa-dist-win) — plausible in theory,
**unverified, unsupported, not recommended**. Practical posture: on Windows it is
"unattended but windowed"; genuinely headless client runs belong on a Linux CI box.

**Version support:** effectively **1.21.5+** as a public module (1.21.4 internal/unstable);
present in current fabric-api. 26.x presence: the *server* gametest module is confirmed
alive on 26.x (above); the client module has no contrary signal but its 26.x status is
**UNVERIFIED** — check `fabric-client-gametest-api-v1` exists in the fabric-api fat jar for
26.2 before planning on it.

**Maturity: EXPERIMENTAL** (explicitly so in the announcement), but actively maintained.

### 2.3 Raw embedded / production dedicated server (no test framework)

Two sub-flavors:

- **Loom production run tasks** (`ServerProductionRunTask`,
  [docs](https://docs.fabricmc.net/develop/loom/production-run-tasks)): run the REAL
  production dedicated server with the built mod jar. A tiny "autotest" companion mod (or a
  dev-only code path behind a system property) measures what we want — time
  `/worldgen`-style pregeneration, sample `ServerTickEvents` durations with N bots pathing,
  then `server.halt()`. This is the only listed option that measures **real worldgen on a
  real dedicated server** with zero framework constraints. No assertion framework, no
  structure anchoring, no JUnit — you own the harness. Headless and Windows-clean (it's a
  server). Maturity: the task is official Loom; the harness discipline is on us.
- **Programmatic `MinecraftServer` boot from a JUnit test** (the hypothetical
  "fabric-loader-junit + ServerLevel" pattern): **not a supported pattern**. fabric-loader-junit
  provides classloading + `Bootstrap`, not a game environment; hand-booting
  `DedicatedServer` inside JUnit means replicating loader entrypoint init, `server.properties`,
  EULA, level storage, and the server thread lifecycle — fragile, version-coupled, and
  exactly what GameTestServer already is. **Rejected**; if JUnit-report integration is
  wanted, the gametest server already emits JUnit XML.

### 2.4 Headless-GL hacks for a true headless client

- **xvfb**: Linux-only virtual framebuffer; first-class supported via Loom's
  `ClientProductionRunTask.useXVFB` (§2.2). Not available on Windows.
- **Mesa llvmpipe on Windows** (software GL via `opengl32.dll` drop-in): exists
  (mesa-dist-win), never validated with LWJGL3 + MC in this context. UNVERIFIED, fragile.
- **EGL/osmesa LWJGL surface hacks**: no supported MC path; the client gametest framework
  does not offer an offscreen mode today.

Bottom line: **there is no supported truly-headless client on Windows.** Headless client =
Linux CI + xvfb. On Windows, client tests run windowed-but-unattended.

---

## §3 Recommendation

**Wire the server gametest harness first, on the `mc-1.21` era branch, pinned to one
active version (1.21.4, same as the JMH harness): JDK 21, Architectury Loom 1.13.469,
vanilla `@GameTest` annotation era, launched as a `gametest` server run config with
`-Dfabric-api.gametest`.** It is fully headless on Windows, uses real `ServerLevel`s and
real ticking, and covers 2 of the 3 asks (server tick, movement execution) with high
fidelity. For the third (worldgen), add a **production-server measurement harness** (§2.3
Loom `ServerProductionRunTask` + an autotest flag) OR defer to the **client gametest
dedicated-server context** later. The client gametest API is the optional third phase, for
client-perspective verification (does the bot LOOK right from a joined player's seat), run
windowed on the dev machine / xvfb on Linux CI.

Why this order: the GameTestServer path is the most mature, the only one that is
first-class headless on Windows, and the one whose measurements map onto Orebit's actual
questions (the bot is a `ServerPlayerEntity`; everything we need to observe is
server-side). The flat world is a feature for *movement correctness* tests (controlled
terrain, built programmatically or from structure NBTs like Lithium) and only a limitation
for worldgen measurement.

### 3.1 Loom wiring sketch — mc-1.21 era (Architectury Loom 1.13.469) — UNVERIFIED SKETCH, not applied

Architectury Loom is a fork of Fabric Loom tracking upstream minors
([architectury/architectury-loom](https://github.com/architectury/architectury-loom)), so
Arch Loom 1.13 *should* carry Fabric Loom 1.13's `fabricApi.configureTests` DSL (upstream
gained it around Loom 1.10) — **UNVERIFIED against Arch Loom specifically; its fabricApi
extension has diverged before.** The classic run-config wiring below needs nothing from the
DSL and works on any Loom, so it is the safe primary plan:

```kotlin
// fabric/build.gradle.kts (mc-1.21 branch) — SKETCH ONLY
loom {
    runs {
        create("gametest") {
            server()
            configName = "Orebit GameTest"
            // Classic fabric-gametest-api-v1 activation (works on all Loom versions):
            vmArg("-Dfabric-api.gametest")
            vmArg("-Dfabric-api.gametest.report-file=${layout.buildDirectory.get()}/gametest/junit.xml")
            runDir("build/gametest-run")
        }
    }
}
```

- Test classes: a `gametest` source set (or gated classes in main) registered under
  `"fabric-gametest": ["com.orebit.mod.gametest.BotGameTests"]` in a **test-mod**
  `fabric.mod.json` (keep it out of the shipped jar — same discipline as the deferred
  coldstart harness patch).
- Stonecutter: run from the active node like everything else —
  `"Set active project to 1.21.4"` → `:fabric:1.21.4:runGametest`. Do NOT try to make the
  gametest source compile across all 28 versions (the 1.21.5 annotation fault line, §2.1);
  it is a pinned-version diagnostic harness like JMH.
- If `fabricApi { configureTests { createSourceSet = true; modId = "orebit-gametest";
  eula = true } }` turns out to exist and work on Arch Loom 1.13, prefer it (it wires the
  source set + entrypoint + `runGametest`/`runClientGameTest` tasks for us); treat that as
  a 30-minute experiment, with the manual run config as fallback.

Test shape (vanilla annotation era, ≤1.21.4):

```java
public class BotGameTests implements FabricGameTest {
    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 600)
    public void botWalksToTarget(GameTestHelper helper) {
        // spawn AllyBotEntity via BotManager against helper.getLevel(),
        // issue the goto (direct BotManager/AllyBotEntity call, not chat),
        // helper.succeedWhen(() -> botWithin(helper, target, 1.5));
    }
}
```

### 3.2 Loom wiring sketch — 26-era (`main`, pure Fabric Loom 1.17.12, unobfuscated) — UNVERIFIED SKETCH, not applied

Fabric Loom 1.17 definitely has the DSL (docs current). Unobfuscated 26.x should be a
non-issue for gametests — the run configs don't involve remapping, and this branch already
uses plain `implementation`/`jar` idioms; the gametest module is present in fabric-api
26.x. **Interaction with our Stonecutter multi-version root + the emptied test source set
is the unverified part.**

```kotlin
// build.gradle.kts (main / 26-era) — SKETCH ONLY
fabricApi {
    configureTests {
        createSourceSet = true          // keeps gametest code out of src/test (which is emptied)
        modId = "orebit-gametest"
        enableGameTests = true          // -> runGametest (server, headless)
        enableClientGameTests = true    // -> runClientGameTest (windowed on Windows!)
        eula = true
    }
}
```

- Tasks land per Stonecutter node: `"Set active project to 26.2"` →
  `:26.2:runGametest` / `:26.2:runClientGameTest` (expected shape — UNVERIFIED).
- Uses fabric-api's own `@GameTest` annotation (post-1.21.5 flavor) — a DIFFERENT import
  than the mc-1.21 era harness; the two eras' gametest sources will not be line-identical
  (acceptable: era-owned harness files, like the build scripts).
- Client gametest world for worldgen measurement:
  `context.worldBuilder().setUseConsistentSettings(false).adjustSettings(...).createServer()`
  → real default-preset worldgen on a real dedicated server, real client joined.

### 3.3 Worldgen measurement specifically

The GameTestServer **cannot** measure real worldgen (flat test world, §2.1). Two honest
options, in preference order:

1. **Production server harness** (mc-1.21 era or 26-era): `ServerProductionRunTask` +
   an autotest system property in Orebit (dev-only code path): on `SERVER_STARTED`, time
   forced chunk generation over an N×N radius at a fixed seed (with/without bots), log a
   machine-readable line, `server.halt()`. Headless, Windows-clean, zero framework
   constraints, runs the REAL production jar (also catches remap/packaging bugs).
2. **Client gametest dedicated-server context** (26-era / 1.21.5+): real worldgen + a real
   client attached; heavier, windowed on Windows, experimental API.

---

## §4 Measurement capability matrix

| Measurement | fabric-loader-junit (today) | Server gametest (GameTestServer) | Client gametest (real client) | Production/raw server harness |
|---|---|---|---|---|
| Real worldgen time | ✗ (no level) | ✗ (flat test world) | ✓ (default preset via `adjustSettings`) | ✓ (real world, any seed) |
| Server tick ms (bots pathing) | ✗ (no ticking) | ✓ (real ticking; time in-test or via mod hooks) | ✓ (integrated or dedicated server; 1 server tick per client tick — paced, good for correctness, distorted for throughput) | ✓ (production pacing — best fidelity) |
| Movement-execution correctness (spawn bot, goto, assert arrival ≤ N ticks) | ✗ | ✓ (`succeedWhen` + `timeoutTicks`; terrain built by test) | ✓ (plus the client's view of the bot) | Partial (no assertion framework; log-and-grep) |
| Bot visible/correct from a real client's perspective | ✗ | ✗ | ✓ (screenshots, golden images, input sim) | ✗ |
| Client FPS / rendering cost | ✗ | ✗ | Partial (screenshots yes; FPS numbers not a framework goal, and pacing is synthetic) | ✗ |
| Client-side prediction / real player input | ✗ | ✗ | ✓ (`TestInput`) | ✗ |
| Headless on Windows 11 | ✓ | ✓ | ✗ (window opens; xvfb is Linux-only) | ✓ |
| JUnit integration | ✓ (`./gradlew test`) | Partial (run task, NOT `test`; emits JUnit XML report) | ✗ (run task; screenshots/reports as artifacts) | ✗ (custom logging) |
| Maturity | In use today | HIGH (since 1.17) | EXPERIMENTAL (1.21.5+) | Task official; harness is ours |

---

## §5 Caveats + open questions

1. **Gametests are NOT JUnit tests.** They do not run under `./gradlew test` /
   fabric-loader-junit; they need the generated run task (`runGametest` /
   `runClientGameTest`) or the classic `-Dfabric-api.gametest` server run config. CI
   integration is via the JUnit-XML report file, not the JUnit platform. Keep the existing
   `src/test` harness as-is; gametests are an additional, separate source set.
2. **Flat-world constraint** (server gametests): terrain for movement tests must be built
   by the test (programmatic or structure `.nbt` à la Lithium). That is a *feature* for
   reproducible movement scenarios (cliff-slip, swim jank, portal-seek all reproducible on
   demand!) but rules out worldgen measurement (§3.3). Also verify: structure-anchored
   tests + a bot walking hundreds of blocks *outside* the structure bounds — the framework
   only forcibly cleans/asserts within the structure; long walks should work but the
   ticket/chunk-loading behavior around a far-wandering fake player on a GameTestServer is
   **UNVERIFIED**.
3. **Tick-time measurement pacing.** GameTestServer runs ticks as fast as tests allow (it
   is not throttled to 50 ms the way a live server idles); measure per-tick *duration*, not
   TPS. In client gametests the 1-server-tick-per-client-tick lockstep is great for
   determinism and wrong for throughput numbers. For "how many ms does a tick cost with 5
   bots pathing", the production server harness is the most honest instrument.
4. **The 1.21.5 annotation fault line** splits any cross-era gametest source (vanilla
   `@GameTest` ≤1.21.4 vs fabric's `@GameTest` ≥1.21.5). Recommendation: pinned-version
   harnesses per era (1.21.4 and 26.2), not a version-spanning test source.
5. **Architectury Loom 1.13 `configureTests`: UNVERIFIED.** The classic run-config wiring
   is the guaranteed path on the mc-1.21 branch. (Also note Loom "server gametests run with
   `build`" behavior — if the DSL does work, make sure it doesn't silently attach a
   GameTestServer boot to every `chiseledBuild`; 28 versions × a server boot would be
   painful. The manual run config avoids this entirely.)
6. **Client gametest on 26.x: UNVERIFIED** (server gametest module confirmed present in
   fabric-api 26.1/26.2; the client module very likely but not confirmed — check the
   fabric-api 26.2 jar for `fabric-client-gametest-api-v1` before phase 3).
7. **Windows headless client does not exist** (supported). Plan for windowed local runs;
   true headless client = Linux CI + xvfb via `ClientProductionRunTask.useXVFB`
   ([production run tasks](https://docs.fabricmc.net/develop/loom/production-run-tasks));
   known CI flake: `-Dfabric.client.gametest.disableNetworkSynchronizer=true` workaround
   (Fabric docs).
8. **EULA**: dedicated-server contexts (client gametest `createServer`) and production
   server runs require EULA acceptance (`eula = true` in the DSL / `eula.txt`).
9. **Bot-driving inside a gametest**: prefer direct `BotManager`/`AllyBotEntity` calls over
   `/bot` chat commands (no command-source plumbing needed, and assertions can read
   `PathStatus` directly). The bot being a real `ServerPlayerEntity` on a GameTestServer
   with **no real network connection** is exactly what `FakeNetworkHandler`/
   `FakeClientConnection` already handle in normal play — expected fine, but the first
   spawned-bot-on-GameTestServer run is the real smoke test.
10. **Prior art**: Lithium ships gametest source sets (correctness, structure NBTs) but
    does NOT benchmark tick time in CI — perf claims come from live-world measurement.
    Matches the split proposed here: gametest = correctness; production harness = numbers.

### Sources

- Fabric docs — Automated Testing: https://docs.fabricmc.net/develop/automatic-testing
- Fabric docs — Fabric API DSL (`configureTests`): https://docs.fabricmc.net/develop/loom/fabric-api
- Fabric docs — Production Run Tasks (xvfb): https://docs.fabricmc.net/develop/loom/production-run-tasks
- Fabric 1.21.5 announcement (client gametest module; gametest overhaul response): https://fabricmc.net/2025/03/24/1215.html
- fabric-api javadoc — server gametest pkg: https://maven.fabricmc.net/docs/fabric-api-0.91.4+1.20.5/net/fabricmc/fabric/api/gametest/v1/package-summary.html ; fabric `@GameTest` (1.21.5+): https://maven.fabricmc.net/docs/fabric-api-0.119.2+1.21.5/net/fabricmc/fabric/api/gametest/v1/GameTest.html ; `FabricGameTest.EMPTY_STRUCTURE`: https://maven.fabricmc.net/docs/fabric-api-0.54.0+1.19/net/fabricmc/fabric/api/gametest/v1/FabricGameTest.html
- fabric-api javadoc — client gametest pkg: https://maven.fabricmc.net/docs/fabric-api-0.125.3+1.21.5/net/fabricmc/fabric/api/client/gametest/v1/package-summary.html ; `TestWorldBuilder`: https://maven.fabricmc.net/docs/fabric-api-0.125.3+1.21.5/net/fabricmc/fabric/api/client/gametest/v1/world/TestWorldBuilder.html
- Minecraft wiki — GameTest (history, 25w03a overhaul, `net.minecraft.gametest.Main`, flat test world): https://minecraft.wiki/w/GameTest
- fabric-api 26.x changelogs (gametest module alive on 26.x): https://modrinth.com/mod/fabric-api/version/0.149.1+26.1.2 , https://modrinth.com/mod/fabric-api/version/0.149.1+26.2 ; client-gametest fixes on 1.21.4: https://modrinth.com/mod/fabric-api/version/0.114.0+1.21.4
- Architectury Loom (fork lineage): https://github.com/architectury/architectury-loom
- Lithium (gametest prior art): https://github.com/CaffeineMC/lithium
