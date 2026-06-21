/**
 * EventListener.java
 *
 * Main Component: eventbus/
 * Environment: MAIN
 *
 * This is the functional interface used to define event subscribers.
 * Any system that wants to react to dispatched events implements or registers
 * an `EventListener<T>` for a specific event type. Listeners are invoked by the
 * `EventBus` whenever the corresponding event is emitted.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Declares a single method (`onEvent`) to handle events of type `T`
 * - Provides the contract used by the `EventBus` to call listeners
 * - Enables lambda-based or class-based listener definitions
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `EventBus`, this interface doesn't store listeners or dispatch—it is the listener itself
 * - Unlike event classes (e.g., `TaskCompletedEvent`), this is not data—it defines behavior
 * - Unlike `SubscriptionManager`, this class has no registration logic—it is passive
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Events are dispatched by type—listeners only receive the types they register for
 * - The method is invoked synchronously by default unless an async dispatcher is layered on top
 * - Listeners should avoid expensive or blocking logic unless explicitly managed
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `EventBus`: Invokes `onEvent()` when a matching event is dispatched
 * - Event type `T`: Must be a class or interface representing the event
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - All systems that want to observe behavior via event-driven logic
 * - `AnimationHandler`, `TaskExecutor`, `ReplayRecorder`, etc.
 * - `EventBus`: Registers and invokes these listeners
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Typically used as a generic functional interface:
 *     `EventListener<TaskCompletedEvent> listener = (event) -> { ... };`
 * - Can be implemented by classes with multiple subscriptions
 * - May support annotations or metadata in future extensions
 */
package com.orebit.mod.eventbus;
