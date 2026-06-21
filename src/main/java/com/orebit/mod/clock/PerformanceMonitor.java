/**
 * PerformanceMonitor.java
 *
 * Main Component: clock/
 * Environment: MAIN
 *
 * This class tracks high-level performance metrics across systems like AI, task processing,
 * memory, and simulation. It provides a global snapshot of bot activity and system strain
 * to help with monitoring, throttling, or auto-scaling behavior. It is designed to support
 * admin dashboards, logging, or internal runtime adaptation.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Aggregates statistics across systems (e.g., number of active bots, avg AI tick time)
 * - Tracks tick durations, per-frame cost, active state distribution, and task latency
 * - Emits data for logging, debugging, or runtime dashboards
 * - Can optionally trigger warnings or throttling when soft limits are exceeded
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `AITickProfiler`, this collects *global* statistics, not per-bot timing
 * - Unlike `AILoadBalancer`, it does not rebalance or act—it observes
 * - Unlike `DebugCommandHandler`, this is internal-facing, not user-driven
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Systems reporting into this monitor call hooks like `recordTickCost(...)`
 * - Metrics are reset or averaged at regular intervals (e.g., every 20 ticks)
 * - The monitor is enabled in config or debug mode
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - May receive data from `AITickScheduler`, `TaskExecutor`, `InventorySimulator`, etc.
 * - Settings may define alert thresholds or enable/disable specific metrics
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `Debug overlays`: For displaying tick pressure, memory usage, and system stats
 * - `AILoadBalancer`: May consult this monitor to improve rebalancing strategy
 * - `Server watchdogs` or future self-throttling systems
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Internally tracks rolling averages or exponential smoothing per metric
 * - May include time series buffer or min/max tracking
 * - Should avoid expensive measurement itself—intended to be very lightweight
 * - May emit `PerformanceAlertEvent` if thresholds are exceeded
 */
package com.orebit.mod.clock;
