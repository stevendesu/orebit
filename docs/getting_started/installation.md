## Prerequisites

Orebit is a mod for MineCraft **1.17.1 through 26.2**. See
[Supported Versions](./supported_versions.md) for the full breakdown of which loaders
cover which part of that range.

Orebit requires a mod loader:

 * [Fabric](https://fabricmc.net/use/installer/) is supported on **every** version.
 * [Forge](https://files.minecraftforge.net/net/minecraftforge/forge/) is supported
   from 1.17.1 through the 1.21.x line.
 * [NeoForge](https://neoforged.net/) is supported from 1.21 through the 1.21.x line.
 * Minecraft **26.x** is currently **Fabric only** (Forge/NeoForge builds will follow
   when the toolchain supports them).

Orebit needs no Java beyond what your Minecraft version already needs. Each build targets
the same Java the game does: **Java 17** below 1.20.5, **Java 21** from 1.20.5 up, and
**Java 25** for 26.x. If the game runs, Orebit runs.

## Installing Orebit

Download the latest version of Orebit from the
[releases page](https://github.com/stevendesu/orebit/releases). Then
drop the single `.jar` file into your `mods` folder. To find your
`mods` folder:

 * On Windows, it is located at `%AppData%/.minecraft/mods`
 * On Mac, it is located at `~/Library/Application Support/minecraft/mods`
 * On Linux, it is located at `~/.minecraft/mods`

On a dedicated server, the `mods` folder is the one your mod loader creates alongside the
server jar.
