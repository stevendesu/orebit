/**
 * InventorySimulator.java
 *
 * Main Component: sim/
 * Environment: MAIN
 *
 * This class simulates a full Minecraft-style inventory for bots, including main
 * inventory, hotbar, offhand, armor slots, and possibly crafting grid. It enables
 * bots to carry, search, use, and manipulate items for crafting, combat, hunger,
 * equipment, or trade—all without requiring a real player’s UI or controls.
 *
 * This simulator supports intelligent item usage, fast queries, and partial
 * matching—e.g. “Do I have any burnables?”, “Do I have at least 3 logs?”, or
 * “Which food restores the most saturation?”.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Simulates full player-like inventory layout for bots
 * - Supports insertion, removal, matching, and consumption of items
 * - Enables all in-game systems to interact with items generically
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `EquipmentManager`, this stores inactive items—not equipped gear
 * - Unlike `ItemLocator`, this stores items—it doesn’t search external containers
 * - Unlike `TaskExecutor`, this is passive and queryable—not an active system
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Bots store items in a virtual format (not native player inventory)
 * - Item lookup and usage is coordinated by tasks or AI
 * - Integration with `Crafting`, `Eating`, and `Trading` is common
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `BotSettings`: May limit inventory size or categories
 * - `SimulationToggles`: May disable or constrain item behavior
 * - `HealthSimulator`, `TaskExecutor`, `CraftingRecipe`, etc.
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `HungerSimulator`: Consumes food items
 * - `TaskExecutor`: Uses ingredients for crafting
 * - `EquipmentManager`: Pulls weapons/armor/tools from inventory
 * - `LLM interface`: May generate plans based on what the bot has
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Should simulate:
 *     - 36-slot main inventory
 *     - 9-slot hotbar
 *     - 4 armor slots
 *     - Offhand slot
 * - Should expose methods like:
 *     - `List<ItemStack> findMatching(Predicate<ItemStack>)`
 *     - `boolean hasItems(Ingredient ingredient, int count)`
 *     - `void consume(ItemStack item)`
 *     - `void insert(ItemStack item)`
 */
package com.orebit.mod.sim;
