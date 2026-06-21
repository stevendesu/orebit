/**
 * RelationshipManager.java
 *
 * Main Component: relationships/
 * Environment: MAIN
 *
 * This class manages the full lifecycle of inter-entity relationships in the world.
 * It owns the global `RelationshipGraph`, processes social events (such as help,
 * harm, proximity, or trade), and updates relationship scores accordingly. It also
 * provides runtime access to relationship state for use in AI decision-making,
 * task assignment, and dynamic behavior generation.
 *
 * This manager enables bots to form opinions of players and other bots over time,
 * develop alliances or rivalries, and respond to past interactions in nuanced ways.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Tracks and updates all known relationships between bots and other entities
 * - Listens for social triggers and adjusts relationship scores accordingly
 * - Provides lookup and evaluation functions to determine social alignment
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `Relationship`, this is a controller—not a data structure
 * - Unlike `RelationshipGraph`, this has behavior and update logic—not just storage
 * - Unlike `BehaviorManager`, this models history between entities—not internal traits
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Each bot can maintain many outgoing relationships
 * - Relationships are asymmetric and directional
 * - Social state is updated in response to gameplay events
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `RelationshipGraph`: Stores and retrieves all relationship data
 * - `Relationship`, `RelationshipScore`: Core data models
 * - (Optional) `EventBus`, `Memory`, or `TaskExecutor` to listen for triggers
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `AIStateMachine`: May adjust behavior based on relationship scores
 * - `TaskExecutor`: May prioritize helping allies or avoiding enemies
 * - `Debug tools`: May display social network state or annotate bots
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Should support methods like:
 *     - `recordInteraction(from, to, RelationshipAction type)`
 *     - `getRelationshipScore(from, to)`
 *     - `isFriendly(from, to)`
 *     - `tick()` to decay unused or stale relationships
 * - May use thresholds or tags to classify relationship type (e.g., hostile, neutral, ally)
 */
package com.orebit.mod.relationships;
