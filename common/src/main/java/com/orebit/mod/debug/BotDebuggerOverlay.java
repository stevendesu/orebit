/**
 * BotDebuggerOverlay.java
 *
 * Main Component: debug/
 * Environment: CLIENT ONLY
 *
 * This class provides an in-world visual overlay to help developers and server admins
 * debug bot behavior in real time. It renders bounding boxes, paths, goal targets,
 * and other visual cues directly into the game world around active bots. The overlay
 * is only active in singleplayer or on modded clients and has no impact on server logic.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Renders visual cues like:
 *     - Current path (line segments between waypoints)
 *     - Bot attention or targeting direction
 *     - AI state annotations above the entity
 *     - Region or navigation graph overlays (optional)
 * - Hooks into the Minecraft render loop to draw over the 3D world
 * - May use tick, AI, or pathfinding data from shared `main/` structures
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `DebugCommandHandler`, this class is purely visual
 * - Unlike `PerformanceMonitor`, this class doesn’t track logic—it displays it
 * - Unlike server-side logging, this is graphical and immersive for client-side testing
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - This file is only compiled and loaded on the client
 * - It will never run on a dedicated server and should be guarded appropriately
 * - Shared logic (e.g., task state, path plans) must be exposed via `main/` and synchronized or mocked
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - Minecraft’s render event system (e.g., `WorldRenderEvents`)
 * - Shared `VirtualPlayer`, `PathPlan`, and `AIStateMachine` data from `main/`
 * - Optional debug flags or tick hooks (e.g., `DebugToggleFlags`)
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `ClientInit` or a Fabric client mod initializer: Registers this renderer
 * - `Debug tools`: May toggle or interact with overlays at runtime
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - May batch rendering by bot or by render pass
 * - Must transform bot-local data into world-space coordinates
 * - Should draw using Minecraft’s `Tessellator` or modern matrix stack APIs
 * - Future overlays may include memory traces, entity graphs, or scripting indicators
 */
package com.orebit.mod.debug;
