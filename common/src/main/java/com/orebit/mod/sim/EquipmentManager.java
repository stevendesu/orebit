/**
 * EquipmentManager.java
 *
 * Main Component: sim/
 * Environment: MAIN
 *
 * This class simulates the armor and held item slots of a bot. It tracks the
 * bot's currently equipped weapon, armor pieces, tools, and other wearable
 * items. Unlike a real player whose equipment is managed by Minecraft’s
 * entity logic, this class offers full control and observability of gear
 * selection and updates.
 *
 * EquipmentManager supports intelligent equipment handling—such as automatically
 * switching to the best available tool for a task, equipping armor when under
 * threat, or selecting a weapon for combat.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Tracks equipped items: armor, weapon, offhand, etc.
 * - Provides methods for equipping, unequipping, and switching items
 * - May support logic for selecting optimal gear based on context
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `InventorySimulator`, this does not simulate storage—only active equipment
 * - Unlike `TaskExecutor`, this is passive—it does not trigger changes, only enables them
 * - Unlike `HungerSimulator`, this affects stats through items—not biology
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - All equipment changes are initiated externally (via task, AI, or script)
 * - Items come from a simulated or real inventory
 * - Slots are limited and correspond to standard Minecraft layout
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `InventorySimulator`: Source of items to equip or unequip
 * - (Optional) `TaskExecutor`: Issues requests to change equipment
 * - `CombatSystem`, `DefenseEvaluator`, `CraftingState`: May query for current gear
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `TaskExecutor`, `AIState`: May equip/unequip for different goals
 * - `PathfindingCostEstimator`: May increase traversal cost when wearing heavy armor
 * - `CombatAI`: May evaluate current weapon or switch based on threat
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Should track:
 *     - `ItemStack mainHand`
 *     - `ItemStack offHand`
 *     - `ItemStack[] armorSlots`
 * - Should expose:
 *     - `void equip(ItemStack item)`
 *     - `boolean isEquipped(ItemStack item)`
 *     - `ItemStack getEquipped(SlotType type)`
 */
package com.orebit.mod.sim;
