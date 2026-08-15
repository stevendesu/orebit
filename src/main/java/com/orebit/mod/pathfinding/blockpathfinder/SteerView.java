package com.orebit.mod.pathfinding.blockpathfinder;

/**
 * The planned trajectory a {@link Movement} steers along, exposed to the cold execution hooks
 * ({@link Movement#steer}) as a small, MC-type-free seam — the trajectory counterpart to the
 * {@link BotSteering} actuator seam. Where {@code BotSteering} reads/writes the bot's pose and
 * velocity, {@code SteerView} describes <i>where the bot should be going</i>: the current path
 * <b>segment</b> (the line from the previous waypoint to the one being approached) plus a one-step
 * look-ahead to the waypoint after it (so a move can ease momentum before a turn).
 *
 * <p><b>Why a segment, not just a waypoint.</b> The old open-loop follower aimed the bot at the next
 * waypoint <i>centre</i> and floored the throttle, so it cut corners and drifted wide off the planned
 * line with no correction. Closed-loop tracking needs the whole line — start <i>and</i> end — to
 * compute cross-track error (how far off the line the bot is) and a look-ahead pursuit point that
 * pulls it back on. The follower owns a single reusable implementation it re-points each tick
 * (no per-tick allocation); {@link SteerControl} does the geometry.
 *
 * <p><b>Coordinate frame: feet-target world space.</b> All accessors return entity-space
 * {@code double}s already converted to the position the bot's <i>feet</i> should occupy at that
 * waypoint — block centres horizontally ({@code cell + 0.5}) and, vertically, the <b>base of the feet
 * cell</b> ({@code cell.y + 0.0}), which is exactly where a grounded bot's feet rest in that cell.
 *
 * <p><b>The vertical used to be {@code cell.y + 1.0}</b> — the feet cell's CEILING — and that was scrapped
 * on 2026-08-15 (owner ruling). Waypoints are feet cells ({@link Movement#atWaypoint} tests {@code footY()
 * == wy}), so {@code +1.0} named the cell ABOVE the one the bot was supposed to occupy. Ground code papered
 * over it by subtracting the block straight back off ({@code p.ty() - 1.0} carried the comment "the target
 * FEET CELL's floor"), but the swim servos consumed it RAW as a depth set-point and drove the bot to the very
 * top of its own cell — one rounding step from reading as the next cell up. That is the off-by-one behind the
 * swim&rarr;ground handoff failures (the flagship {@code Diagonal} at {@code (154,-8,103)}; the submerged
 * {@code sidegapwet} shape). The justification given for the old value — "a floating bot rises until it
 * breaches, so it settles near the TOP of its feet cell" — is not how vanilla behaves: with no input a bot
 * SINKS, and while jump is held it keeps rising for as long as its feet are in fluid, so there is no
 * equilibrium at a cell top to aim at. See {@link SteerControl#SWIM_RIDE}.
 */
public interface SteerView {

    /** Segment start (the previous waypoint / plan start), feet-target world coordinates. */
    double sx();
    double sy();
    double sz();

    /** The waypoint currently being approached (segment end), feet-target world coordinates. */
    double tx();
    double ty();
    double tz();

    /** Whether a waypoint exists beyond {@link #tx} — i.e. the look-ahead {@code n*} values are valid. */
    boolean hasNext();

    /** The waypoint after the current one (look-ahead for turn anticipation), feet-target world coords;
     *  only meaningful when {@link #hasNext()} is {@code true}. */
    double nx();
    double ny();
    double nz();
}
