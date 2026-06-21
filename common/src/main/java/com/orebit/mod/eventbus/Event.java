/**
 * Event.java
 *
 * Main Component: eventbus/
 * Environment: MAIN
 *
 * This interface serves as a marker for all event types dispatched through
 * the mod’s `EventBus`. All event classes must implement this interface
 * (either directly or via inheritance) to be type-safe and eligible for dispatch.
 * It may later include optional base functionality like timestamping or
 * source metadata but is currently a pure marker interface.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Marks an object as a valid dispatchable event
 * - Enables generic typing across the event system (e.g., `EventListener<T extends Event>`)
 * - Provides a common root type for grouping, filtering, or logging events
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `EventListener`, this interface does not handle logic—it represents an event payload
 * - Unlike `EventBus`, this class is not a dispatcher—it is the thing being dispatched
 * - Unlike `SubscriptionManager` (if added), this class defines no lifecycle—it is passive
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - All event types implement this interface
 * - Events are typically immutable or treated as such after dispatch
 * - Events are routed based on their concrete class type
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - None; this is a root type
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - All event classes in the `eventbus/events/` package
 * - `EventListener<T extends Event>`: Requires this for typing
 * - `EventBus`: Validates types and routes only `Event` objects
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - May remain empty (marker-only) or evolve to include:
 *     - `long timestamp`
 *     - `UUID source`
 *     - `boolean isCancelable`
 * - All event definitions should implement this directly or indirectly
 */
package com.orebit.mod.eventbus;
