/**
 * VirtualPlayer.java
 *
 * Main Component: agent/
 * Environment: MAIN
 *
 * This class represents the core *puppet entity* controlled by the mod. It simulates a player
 * within the Minecraft world, visible to other players, capable of moving, interacting with the world,
 * using items, and being animated like a real client-side player.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Defines the VirtualPlayer entity type
 * - Extends a player-like class (e.g., ServerPlayerEntity or a custom subclass)
 * - Registers itself with the Minecraft server so it behaves like a legitimate player
 * - Manages entity lifecycle (initialization, despawning, visibility to others)
 * - Acts as the anchor point for controlling AI, animations, inventory, and simulation
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike real players, this entity is fully AI-controlled and not tied to a physical client
 * - Unlike MockClientSession or other mocks, this is an *in-world, ticking entity* with physics
 * - Unlike VirtualPlayerController, which *commands* this entity, this class defines its core behavior and presence
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - This entity must be registered as a custom type or subclass that Minecraft accepts as valid
 * - Its presence in the world must be synchronized to clients using mock network packets
 * - It can hold items, be affected by gravity, participate in combat, and be targeted
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `MockNetworkClient` (mocks/): For spawning, despawning, and packet updates
 * - `BotMetadata` (manager/): For identifying owner, task state, and profile
 * - `VirtualPlayerController` (agent/): Issues movement and interaction commands
 * - `InventorySimulator`, `HealthSimulator`, etc. (sim/): Provide simulated subsystems
 * - Minecraft server and entity registration system
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `VirtualPlayerController`: Controls this entity's motion and interactions
 * - `AnimationHandler`: Updates this entity's metadata or sends animation packets
 * - `TaskExecutor`, `AIStateMachine`: Drives high-level behavior
 * - `Debug tools` may inspect or manipulate this entity directly
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - May override core entity methods (tick, interact, attack, etc.)
 * - Must participate in entity updates without requiring a physical client connection
 * - Requires clean despawn logic to avoid memory leaks or phantom entities
 * - Should support tagging/metadata for debugging and tracing
 */
package com.orebit.mod.agent;
