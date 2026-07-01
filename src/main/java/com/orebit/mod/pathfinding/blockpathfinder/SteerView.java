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
 * waypoint — block centres horizontally ({@code cell + 0.5}) and the top face of the floor cell
 * vertically ({@code cell.y + 1.0}, since a bot standing on floor cell {@code y} has its feet at
 * world {@code y+1}). The follower applies that convention when it points the view, so the controller
 * stays pure geometry with no knowledge of the floor-cell encoding.
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
