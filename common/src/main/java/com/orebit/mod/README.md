# Orebit

Orebit is a highly modular, AI-powered Minecraft server mod that introduces autonomous bots capable of complex, long-term behavior. Each bot acts as a virtual player in the world: exploring, crafting, navigating, responding to danger, building relationships, and more.

> ⚠️ This mod is **server-side only** and is fully compatible with **vanilla clients**. No client-side installation is required to use or play with bots.

---

## 🌍 What Is Orebit?

Orebit adds **AI-driven entities** that behave like players. They have:

- **Physical presence** (they load chunks, have hunger, health, inventories, etc)
- **Smart goals** ("craft enchanted netherite armor", "explore the nearest cave")
- **Social awareness** (form friendships, defend allies, reject commands from strangers)
- **Autonomous initiative** (wander, survive, or work even when idle)

Bots are modular and extensible, with configurable personalities and server-defined behavior. They can either follow direct player commands or operate as autonomous members of the world—similar to villagers, but smarter.

---

## 🧠 Design Philosophy

Orebit is not just a mob AI mod. It is an attempt to bring structured, extensible **agent-based reasoning** to Minecraft.

We prioritize:

### ✅ Determinism Over Black Boxes
LLMs are only used for **intent recognition**, not for planning. When a player says, _"help me gear up"_, the LLM determines the desired outcome (e.g., "craft diamond armor"). The actual plan is built deterministically via a requirements system—no AI hallucinations involved.

### ✅ Modular, Observable, Transparent Systems
Each subsystem (pathfinding, memory, behavior, etc.) is testable and debuggable. The goal is clarity and traceability over complexity.

### ✅ Smart Objects Over Managers
Instead of `BotManager`, `TaskUtils`, or `AIUtils`, logic is embedded in intelligent, composable objects. Avoid utility classes unless abstraction is truly needed.

### ✅ Server-Side Only
The mod is designed for **vanilla client compatibility**. All AI logic, physics, simulation, and rendering is handled server-side. Debug overlays and UI are optional client-side tools used during development, but bots function fully without them.

---

## 🧱 Key Modules

Each top-level package serves a focused purpose in the system:

| Module           | Purpose |
|------------------|---------|
| `agent/`         | Controls the bot's physical body and movement controller |
| `ai/`            | Manages the bot's current activity state and interruptions |
| `behavior/`      | Defines bot personalities (e.g., wanderer, fighter, builder) |
| `clock/`         | Manages tick simulation, supports pause, step, and warp |
| `commands/`      | Parses chat commands into bot instructions |
| `config/`        | Global mod configuration with live reload support |
| `data/`          | Handles serialization of bot state and world overlays |
| `debug/`         | Provides debug overlays and visual tools (optional client-side) |
| `eventbus/`      | A lightweight event system for intra-mod communication |
| `integration/`   | Connects to external services like LLMs |
| `manager/`       | Tracks ownership, lifecycle, and coordination of all bots |
| `memory/`        | Stores long-term, decaying memories unique to each bot |
| `mocks/`         | Provides mocked versions of Minecraft network/server classes |
| `pathfinding/`   | Sophisticated pathfinding with dynamic block mutation support |
| `relationships/` | Models bot social relationships with players and each other |
| `requirements/`  | Generates tree-structured plans to satisfy player or bot goals |
| `scripts/`       | (Reserved) Potential hook system for scripted behaviors |
| `settings/`      | Per-bot configuration (e.g., invincibility, block-breaking) |
| `sim/`           | Simulates in-game systems like inventory, hunger, and health |
| `tasks/`         | Executable units of behavior (e.g., "craft", "equip", "fight") |
| `worldmodel/`    | Hierarchical representation of the world for efficient reasoning |

---

## 🧠 Architecture in Action

Here’s how a high-level command flows through the system:

1. **User input**: A player says “I need help gearing up.”
2. **LLM translation**: The `integration/` module interprets the message as an intent to “craft enchanted diamond armor”.
3. **Goal assignment**: The `requirements/` system recursively determines what’s needed (diamond, anvil, enchantments, etc.)
4. **Plan generation**: A tree of `PlanNode`s is created from a registry of satisfiers.
5. **Execution**: The `ai/` module enters a `CraftingState` and uses `pathfinding/` to reach required locations.
6. **Adaptation**: If attacked, the bot's `AIStateMachine` interrupts with a combat behavior.
7. **Persistence**: Progress, memories, and region maps are saved to disk and reloaded later.

---

## 💡 Key Innovations

- **Recursive Requirements System**  
  Bots construct behavior trees based on needs, not scripts. Want netherite armor? The bot works out every step: crafting, smelting, mining, enchanting.

- **Region-Based World Model**  
  Efficient planning using a hierarchical region tree. Regions contain portals, subregions, and approximate costs between connections.

- **Simulation-Safe Pathfinding**  
  Pathfinding accounts for block placement/breaking as valid operations, allowing bots to escape caves, bridge gaps, or break into structures.

- **Relationship Graph**  
  Bots remember who helped or harmed them, affecting social behavior and willingness to follow commands.

---

## 🧪 Status

Orebit is currently in active architecture and scaffolding. We are still finalizing key structures like:

- `PlanNode` vs `Satisfier` design
- Profile configuration and extension
- Region-building heuristics

---

## 👋 Getting Involved

Interested in contributing?

- Read through the module summaries in this README
- Review block comments in each class for implementation notes
- See the `debug/` tools to visualize AI state and navigation
- Suggest enhancements, optimizations, or new subsystems

---

## 🦊 Credits

Orebit is inspired by sandbox AI research, simulation theory, and the desire to make Minecraft **feel alive** without sacrificing control or performance.

Bots don’t just respond.  
They **remember**, **reason**, and **grow**.

---
