/**
 * RelationshipGraph.java
 *
 * Main Component: relationships/
 * Environment: MAIN
 *
 * This class represents the directed social network between all bots and
 * players in the world. It stores and manages all known `Relationship`
 * instances—each representing a one-way link from one entity to another—
 * and provides efficient lookup, creation, and removal of these relationships.
 *
 * The graph allows bots to reason about alliances, rivalries, trust networks,
 * or social structures (e.g., groups, cliques, or mentors). It enables
 * emergent behaviors like gossip, conflict, and cooperative task sharing.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Stores the full network of known `Relationship` instances
 * - Allows bots to query how others feel about each other
 * - Facilitates propagation of emotional influence and history
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `Relationship`, this is a container—not a single link
 * - Unlike `RelationshipManager`, this does not drive behavior—only storage
 * - Unlike `Memory`, this tracks social state—not event history
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Graph is directed: A → B is distinct from B → A
 * - Bots only maintain relationships relevant to their experiences
 * - The graph may be pruned or decayed to control memory usage
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `Relationship`: Core edge data model
 * - `RelationshipScore`: Quantitative backing for relationship edges
 * - `UUID`, `BotId`, or entity refs for graph keys
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `RelationshipManager`: Uses this to resolve bot-to-bot dynamics
 * - `TaskExecutor`, `AIStateMachine`: May use graph to determine allies or enemies
 * - `Debug Tools`: Can visualize social structure or connected components
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Typically implemented as:
 *     - `Map<EntityId, Map<EntityId, Relationship>>`
 * - May support:
 *     - `getRelationship(A, B)`
 *     - `getAllRelationshipsOf(A)`
 *     - `removeEntity(A)`
 *     - `pruneWeakLinks()`
 */
package com.orebit.mod.relationships;
