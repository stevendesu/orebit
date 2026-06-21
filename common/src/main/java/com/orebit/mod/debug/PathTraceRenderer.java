/**
 * PathTraceRenderer.java
 *
 * Main Component: debug/
 * Environment: CLIENT ONLY
 *
 * This class renders visual representations of a bot’s current or recent pathfinding trace.
 * It is used to debug the output of the path planner and follower, helping developers
 * understand how bots interpret the world, choose movement targets, and handle obstacles.
 * This renderer draws lines or shapes in-world to show block-level paths, region transitions,
 * and even failed or fallback routes, if enabled.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Renders visual overlays for:
 *     - Current planned path (waypoints or full A* trace)
 *     - Portal transitions between regions
 *     - Recent path history (breadcrumb trail)
 *     - Pathfinding failures (in red or dashed lines)
 * - Updates each frame with the bot’s current navigation state
 * - Can be toggled or filtered per bot, task type, or state
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `BotDebuggerOverlay`, this renderer is focused solely on navigation/pathing
 * - Unlike `PathPlan` or `PathFollower`, this class has no logic—it is visual only
 * - Unlike `DebugCommandHandler`, this provides no input—only output
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Runs only on modded clients (e.g., in singleplayer or dev setup)
 * - Queries shared `main/` state for bot path data
 * - Overlay behavior can be toggled via debug settings or commands
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `PathPlan`, `PathFollower`: Provide data for the trace
 * - `VirtualPlayerContext`: Used to locate the bot and access debug flags
 * - Minecraft client rendering systems (matrix stack, line rendering)
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `ClientInit`: Registers this renderer during client mod init
 * - `DebugCommandHandler`: May toggle this overlay per bot or globally
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Should use optimized geometry batching or vertex buffering for performance
 * - Supports both 2D (top-down) and 3D in-world rendering
 * - Can be extended to support animation of path progression or trace fading
 * - May draw numeric labels or tick-count timestamps for advanced introspection
 */
package com.orebit.mod.debug;
