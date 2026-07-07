# Supported Versions

Orebit ships as a single drag-and-drop JAR for **every Minecraft version from 1.17.1
through 26.2**, on **Fabric**, **Forge**, and **NeoForge** — with **zero runtime
dependencies**. No API mod to install alongside it, no library-version conflicts with
your other mods. You download one JAR for your version and loader, drop it in `mods`, and
it runs. This page explains what that covers and, for the curious, how one codebase spans
a range that crosses two Java versions, a Forge event-system rewrite, and Mojang
un-obfuscating the game.

## What's covered

| Loader | Minecraft range | Notes |
|:-------|:----------------|:------|
| **Fabric** | 1.17.1 → 26.2 | The full range, runtime-verified in-game. |
| **NeoForge** | 1.21 → 1.21.x | From NeoForge's floor upward. |
| **Forge** | 1.17.1 → 1.21.x | Built and shipped; see the note below. |

Around forty-five loader-and-version combinations are build-verified across the 1.20.1 →
1.21.x span alone, extended down to 1.17.1. Forge's *development launcher* is broken past
about 1.20.4 (a Java-modules split-package issue that's Forge's, not ours), so the Forge
builds are compile- and ship-tested rather than dev-launched — the resulting JAR still
runs on a player's server. Versions at or below 1.16.5 are deferred: Minecraft's 1.13
"flattening" and Mojang's official-mappings floor at 1.14.4 are a hard backward wall.

## How it holds together

The naive way to support twenty versions is one build that tries to compile against all of
them. That's impossible here — not merely hard. A single Gradle build resolves each plugin
to *one* version on a shared classpath, and the newest Minecraft needs build tooling the
oldest Forge can't tolerate. You cannot satisfy both at once.

So Orebit is organized by **toolchain era**, not by version. A branch owns a *span* of
versions that share one build toolchain (one Loom, one Gradle, one Java); within a branch,
a preprocessor covers the individual versions from a single source tree. There are two
eras today: one for **1.17.1 → 1.21.x** (Java 17/21) and one for **26.x** (Java 25).

The trunk holding all the version-portable code — the entire bot, the pathfinder, the
world model — carries *no* build-tool settings at all and deliberately doesn't build on its
own. It exists to be **merged into** each era. A bug fixed once on the trunk propagates to
every era with a clean merge that can never drag a toolchain difference along, because the
trunk never contains one. The pieces of code that genuinely differ between Minecraft
versions don't sprinkle version checks through the logic; they live behind a thin
**platform adapter** — small version-selected shims (how you read a block, how you spawn a
player) that the shared core calls without knowing which Minecraft it's running on.

## Two deliberate choices

Two decisions are worth calling out, because both cut against the ecosystem grain and both
are why the download is a single dependency-free JAR.

**No cross-loader API mod.** The common way to write one mod for Fabric and Forge is to
build against the Architectury *API* and ship it as a required dependency. Orebit doesn't:
the seam between the shared core and each loader is hand-written glue, one small file per
loader. The cost is that we absorb each loader's API churn ourselves (Forge's event-bus
rewrite at 1.21.6, for instance) — but that cost is localized to one branch, and the payoff
is yours: no second mod to install, and no "these two mods need different versions of the
same library" incompatibility inflicted on your server.

**The un-obfuscated 26.x twist.** Minecraft 26.1 ships **without obfuscation** — Mojang
publishes no mappings, and the community's Yarn mappings were retired. That broke the
usual Fabric+Forge build tooling, which still hard-requires a mappings step. Because
Orebit's source carries no Architectury API at all, its Fabric target builds on the plain,
stock Fabric toolchain with *no mappings step* — so the 26.x era shipped for Fabric while
the multi-loader tooling catches up. Forge and NeoForge for 26.x follow once their build
tools support the un-obfuscated game.

The result is the one thing a player should care about: whatever version and loader you
run, there's a JAR that just works, and it's the only file you need.
