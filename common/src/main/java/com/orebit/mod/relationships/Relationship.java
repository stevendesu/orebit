/**
 * Relationship.java
 *
 * Main Component: relationships/
 * Environment: MAIN
 *
 * This class represents a single, directed relationship between a bot and
 * another entity (either a player or another bot). A `Relationship` tracks
 * the accumulated social state between the source and target—such as trust,
 * affection, suspicion, or hostility—and is updated over time based on
 * interactions, proximity, shared experiences, and external influences.
 *
 * Each bot maintains its own set of `Relationship` instances representing
 * how it feels about others. These relationships are not necessarily mutual;
 * Bot A might like Bot B, even if Bot B dislikes Bot A.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Encapsulates the state of a relationship between two entities
 * - Tracks scores, moods, flags, or recent interaction history
 * - Used to determine behavior toward others (e.g., follow, avoid, assist)
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `RelationshipScore`, this is the full relationship state—not just a number
 * - Unlike `RelationshipGraph`, this is a node-edge-level object—not the whole system
 * - Unlike `BehaviorSettings`, this is not about personality—it's about history
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - One `Relationship` represents a one-way connection (A → B)
 * - Relationships can decay, intensify, or flip based on input
 * - May store multiple scoring dimensions (trust, like, respect, etc.)
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `RelationshipScore`: May define how scores are tracked and updated
 * - (Optional) `UUID`, `BotId`, or `EntityReference` for identity tracking
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `RelationshipManager`: Owns and updates all relationships per bot
 * - `AIStateMachine`: May use relationship status to influence social decisions
 * - `TaskGenerator`: May propose tasks based on relationships (e.g., help a friend)
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - May store:
 *     - Source and target IDs
 *     - Last interaction timestamps
 *     - Trust, like, or fear scores
 *     - Relationship tags (e.g., "enemy", "mentor", "sibling")
 * - Should support serialization and incremental updates
 */
package com.orebit.mod.relationships;
