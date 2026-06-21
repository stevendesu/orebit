/**
 * TaskInspector.java
 *
 * Main Component: debug/
 * Environment: CLIENT ONLY
 *
 * This class provides a visual and interactive interface for inspecting the current task tree
 * of a selected bot. It allows developers to understand how the bot is interpreting its goals,
 * which task branches are active, what dependencies are pending, and how progress is evolving.
 * It can display active, queued, and failed `TaskNode` structures in a tree-like or list format.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Visualizes the structure of the current and queued `TaskNode` graphs
 * - Highlights which nodes are active, complete, blocked, or interrupted
 * - Displays task metadata such as:
 *     - Estimated cost or priority
 *     - Resource dependencies
 *     - Completion status or failure reason
 * - Supports mouseover or click-to-expand interaction
 * - May sync with task planning or execution systems
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `ReplayViewer`, this tool inspects *live task state*, not past logs
 * - Unlike `DebugCommandHandler`, this is interactive and graphical
 * - Unlike `PathTraceRenderer`, this focuses on planning logic—not movement
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - The client has access to the bot’s task tree, either via sync or local sim
 * - Task trees are relatively shallow and visually inspectable
 * - This class runs only on the client, typically in dev/test environments
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `TaskNode`: The structure being visualized
 * - `VirtualPlayerContext`: For selecting which bot’s tasks to view
 * - Client-side UI framework (e.g., Minecraft overlays, HUD, or custom GUI tools)
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `ClientInit`: Registers this tool when running in a modded client
 * - `DebugCommandHandler`: May allow toggling this UI or targeting specific bots
 * - `BotDebuggerOverlay`: May visually highlight the current task target in the world
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Tasks may be drawn as collapsible trees, dependency graphs, or flow diagrams
 * - Should update in real-time as tasks change
 * - Could support pinning, search, or cross-referencing with LLM/task planner
 * - Ideal for debugging failures, task loops, or decision errors
 */
package com.orebit.mod.debug;
