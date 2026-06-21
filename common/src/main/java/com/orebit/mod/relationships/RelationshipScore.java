/**
 * RelationshipScore.java
 *
 * Main Component: relationships/
 * Environment: MAIN
 *
 * This class represents a quantitative score used to track and evaluate
 * a specific aspect of a relationship between two entities. While a
 * `Relationship` object may store multiple social dimensions (e.g., trust,
 * affinity, respect), each of these can be modeled as a separate
 * `RelationshipScore` with its own rules for accumulation, decay, and bounds.
 *
 * The scoring system allows bots to reason about complex social behavior
 * (e.g., "I trust this player, but I don't like them"), and adapt their
 * responses accordingly. Each score may be affected by specific interaction
 * types (helping, attacking, gifting, proximity), and may decay naturally
 * over time if not reinforced.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Encapsulates a numeric score representing a relationship dimension
 * - Supports clamping, decay, and incremental updates
 * - Can be embedded into a `Relationship` object or used for evaluation
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `Relationship`, this is a reusable score tracker—not a full relationship
 * - Unlike `BehaviorSettings`, this is not preference-based—it tracks social history
 * - Unlike `RelationshipGraph`, this does not define connections—just state
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Scores are scalar (usually floats or ints)
 * - All updates are directional (A → B), not mutual
 * - May use configurable bounds (e.g., [-100, +100]) or normalized scale [0.0, 1.0]
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - None directly, but used by `Relationship` and `RelationshipManager`
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `Relationship`: Owns one or more scores
 * - `RelationshipManager`: Updates scores based on interaction events
 * - `AIStateMachine`: May query scores when selecting allies or threats
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Should support:
 *     - `adjust(float delta)`
 *     - `decayOverTime(float dt)`
 *     - `clamp(min, max)`
 *     - `isAboveThreshold()`
 * - May define standard types like trust, like, fear, anger, etc.
 * - Optionally supports separate short-term and long-term components
 */
package com.orebit.mod.relationships;
