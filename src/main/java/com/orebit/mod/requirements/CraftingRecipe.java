/**
 * CraftingRecipe.java
 *
 * Main Component: requirements/
 * Environment: MAIN
 *
 * This abstract class represents a logical, AI-usable description of a crafting
 * recipe—not tied to Minecraft's item registry or vanilla recipe system. Each
 * subclass defines the input requirements, output item, and prerequisite conditions
 * (such as needing a crafting table, furnace, or smithing table).
 *
 * These recipe classes are used by the requirements system to determine how to
 * fulfill higher-level goals like "acquire a diamond sword" by recursively computing
 * sub-requirements for tools, materials, and crafting stations.
 *
 * These recipes are intended to be human-authored and deterministic—not discovered,
 * generated, or parsed from game data. They can also represent multi-step or
 * conditional crafting logic (e.g., combining ingredients with intermediate steps).
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Serves as the abstract base class for all crafting recipes
 * - Encodes input → output → requirement mappings for recursive task planning
 * - Provides an interface for querying prerequisites or cost
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike vanilla `Recipe`, this is purely logical and used by the AI
 * - Unlike `Requirement`, this is one *way* to fulfill a requirement—not the requirement itself
 * - Unlike `Task`, this doesn't describe a running operation—just a structural rule
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Each recipe class is immutable, deterministic, and serializable if needed
 * - One recipe produces one item (or item stack)
 * - Inputs and dependencies are defined as `Requirement` objects or predicates
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `Requirement`: Used to define input ingredients or crafting context
 * - (Optionally) `InventorySnapshot` or `ItemType` for inventory simulation
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `RequirementSolver`: May query recipes when expanding goal trees
 * - `TaskExecutor`: Eventually receives derived tasks once a recipe plan is chosen
 * - `CraftingRequirement` or `CraftItemRequirement`: May embed a reference to one or more recipes
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Should define methods like:
 *     - `ItemType getOutput()`
 *     - `List<Requirement> getIngredients()`
 *     - `boolean requiresCraftingTable()`
 *     - `int getEstimatedCost()`
 * - Subclasses may include:
 *     - `IronPickaxeRecipe`
 *     - `EnchantedBookRecipe`
 *     - `NetheriteSwordRecipe`
 */
package com.orebit.mod.requirements;
