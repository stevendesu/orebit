/**
 * SimulationClock.java
 *
 * Main Component: clock/
 * Environment: MAIN
 *
 * This class provides a centralized virtual clock that represents logical time
 * as used by systems like AI, memory, task decay, and simulations. While Minecraft
 * already has a global tick counter, this clock can introduce abstraction layers
 * for controlling tick pacing, batching, or future extensions like fast-forwarding
 * and pausing simulations.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Tracks a virtual tick count used across non-critical systems (e.g., memory decay)
 * - Supports offsetting, pausing, or accelerating time for scripted/test environments
 * - Acts as a dependency-free, deterministic clock for replay and logic replay use
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike Minecraft's native tick count, this can be paused, controlled, or overridden
 * - Unlike `AITickScheduler`, it is passive—it does not initiate ticks, only reports them
 * - Unlike `PerformanceMonitor`, this has no insight into system load or timing cost
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - All consumers of the clock use this system for tick references
 * - The simulation clock is advanced once per real tick unless explicitly paused
 * - Side effects based on time should respect this clock, not raw world time
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - May be advanced via tick hook in `AITickScheduler` or a global mod tick handler
 * - Settings may control pause/play speed (for testing or scripted behaviors)
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `BotMemory`: For decay and journaling logic
 * - `Debug tools`: For testing frame-by-frame behavior
 * - `ReplayRecorder`, `ScriptHookManager`: For controlled execution
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Internally tracks tick count, time scaling factor, and pause state
 * - May include helper methods like `every(n)`, `since(tick)`, or `delayUntil(t)`
 * - Future extensions may include frame-step, timeline branching, or rewind capability
 */
package com.orebit.mod.clock;
