/**
 * EventBus.java
 *
 * Main Component: eventbus/
 * Environment: MAIN
 *
 * This class is the core dispatcher for the mod’s internal event system.
 * It manages the registration of listeners and the dispatching of event objects
 * to those listeners based on event type. It supports simple pub/sub mechanics,
 * allowing systems to decouple behavior through event-driven communication.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Allows registration of listeners for specific event types
 * - Dispatches events to all relevant listeners, in registration order
 * - Supports multiple listeners per event type
 * - Enforces type-safe event routing via generic typing
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `EventListener`, this class contains the routing logic—not the callback definitions
 * - Unlike individual events (e.g., `BotSpawnedEvent`), this class does not declare behavior—it delivers it
 * - Unlike `SubscriptionManager` (if implemented), this is the active dispatcher—not just a registry
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Listeners are lightweight and fast (ideally not blocking)
 * - Events are immutable or treated as such during propagation
 * - Event classes are POJOs (Plain Old Java Objects) with a shared base or marker interface
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `EventListener<T>`: Defines the callback signature for subscribers
 * - `events/` package: Contains the event types dispatched through this bus
 * - Optional: logging utilities for debugging
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - All systems that need to emit or observe events (e.g., AI state changes, path updates, memory writes)
 * - Debug and replay systems that log or mirror events
 * - `VirtualPlayerController`, `TaskExecutor`, `AnimationHandler`, etc.
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Internally stores a `Map<Class<T>, List<EventListener<T>>>`
 * - Supports dynamic registration and unregistration of listeners
 * - May support priority-based ordering or cancellation in the future
 * - Dispatch methods may be synchronous or async depending on usage patterns
 * - Should be thread-safe if used across worker systems
 */
package com.orebit.mod.eventbus;
