/**
 * MemoryContext.java
 *
 * Main Component: memory/
 * Environment: MAIN
 *
 * This class captures the relevant context present at the time a memory was created.
 * It serves as a persistent snapshot of the world, bot state, and environmental cues
 * that existed when a `MemoryEntry` was formed. This information allows future systems—
 * especially memory decay strategies—to compare present conditions against historical ones
 * to determine if a memory is still relevant or should be forgotten.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Stores spatial and temporal information from the moment a memory was recorded
 * - Persists this context with the `MemoryEntry` for future decay decisions
 * - Enables strategies to detect changes in proximity, environment, ownership, etc.
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike a live-world context, this is a **frozen snapshot** of the past
 * - Unlike `VirtualPlayerContext`, this is not active or live—it is persisted
 * - Unlike `SimulationClock`, this includes more than just time—it captures bot/environmental cues
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Context is captured once at memory creation and never modified
 * - It is used only during decay evaluation or memory replay
 * - Stored context must be serializable for long-term persistence
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `Vec3d` or `BlockPos`: Bot’s position at time of memory creation
 * - `World` or `DimensionKey`: The dimension or environment the bot was in
 * - `long timestamp`: Simulation time or tick when the memory was created
 * - Optional: player proximity, active profile, visible mobs, etc.
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `MemoryEntry`: Stores this as part of the entry’s immutable metadata
 * - `MemoryDecayStrategy`: Uses this context to compare against present conditions
 * - `ReplayRecorder`: May surface this during playback
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Should be immutable or read-only once constructed
 * - May include helper methods like:
 *     - `boolean wasNear(BlockPos pos, double radius)`
 *     - `boolean wasInSameRegion(RegionID region)`
 *     - `long timeElapsed(SimulationClock now)`
 * - May support versioning if extended over time
 */
package com.orebit.mod.memory;
