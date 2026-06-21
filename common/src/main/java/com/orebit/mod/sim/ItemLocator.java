/**
 * ItemLocator.java
 *
 * Main Component: sim/
 * Environment: MAIN
 *
 * This class provides a mechanism for locating external sources of items
 * in the world, beyond the bot’s current inventory. It is responsible for
 * finding nearby containers, dropped items, tradable bots, or other entities
 * that may fulfill a material requirement.
 *
 * The item locator enables crafting, foraging, quest fulfillment, and scavenging
 * by helping bots answer questions like:
 * - “Is there any coal nearby?”
 * - “Can I loot enough wood to craft a tool?”
 * - “Is this chest worth visiting?”
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Scans the environment for item sources (e.g. containers, entities, drops)
 * - Filters results by tag, category, proximity, or quantity
 * - Enables bots to act on incomplete or opportunistic item acquisition
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `InventorySimulator`, this finds items—not stores them
 * - Unlike `CraftingRecipe`, this helps determine what’s possible—not how to do it
 * - Unlike `RegionKnowledge`, this provides concrete item locations—not cognitive metadata
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Access to the world model for spatial lookups
 * - Bots have range limits or awareness radius for scans
 * - Scanning cost is amortized or batched to avoid lag
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `WorldModel`, `BlockMap`, or direct world access
 * - `InventorySimulator`: May filter based on current needs
 * - `Pathfinder`: To evaluate cost of reaching containers
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `RequirementSolver`: Uses this to satisfy missing items
 * - `TaskExecutor`: May issue a pickup or looting sub-task
 * - `CraftingPlanner`: May prefer to use nearby materials before mining
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Should expose methods like:
 *     - `List<ItemSource> findNearbySources(ItemQuery query)`
 *     - `Optional<BlockPos> locateNearestItem(ItemPredicate)`
 *     - `boolean canReach(ItemSource)`
 * - May support:
 *     - Caching, cooldowns, or region-scoped scans
 *     - Integration with `Memory` to avoid repeat scans
 */
package com.orebit.mod.sim;
