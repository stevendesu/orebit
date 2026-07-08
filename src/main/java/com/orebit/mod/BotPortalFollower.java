package com.orebit.mod;

import com.orebit.mod.platform.Worlds;
import com.orebit.mod.worldmodel.navblock.NavBlock;
import com.orebit.mod.worldmodel.pathing.NetherPortalIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The cross-dimension follow concern, owned by {@link AllyBotEntity} (see {@link BotMining} for the component
 * pattern): when the owner changed dimension while the bot is in FOLLOW/COME, seek the nearest KNOWN nether
 * portal (via {@link NetherPortalIndex}), path to it with the normal two-tier driver
 * ({@link BotNavigator#driveToward}), and hand off to the ENTER terminal state (face the portal + walk
 * straight in) so vanilla's portal process teleports the bot. Success is EVENT-detected (the post-doTick
 * level change in {@code AllyBotEntity.tick} → {@code onLevelChanged} → {@link #resetPortalSeek}).
 */
final class BotPortalFollower {

    private final AllyBotEntity bot;

    /** Horizontal distance (blocks) from the portal cell centre at which pathing hands off to the ENTER
     *  terminal state (face the portal + walk straight in). Slightly under {@link BotNavigator#ARRIVE_DIST}
     *  so the handoff also fires when {@link BotNavigator#driveToward} declares arrival first (its 2.5-block
     *  tolerance stops the bot adjacent to the portal, never inside it — hence a dedicated terminal state). */
    private static final double PORTAL_ENTER_DIST = 2.0;
    /**
     * Upper bound (ticks) a bot may stand INSIDE a live portal column before the teleport is declared
     * genuinely broken. DERIVED from vanilla's own machinery, not tuned: the survival portal wait
     * ({@code Player.getPortalWaitTime()}) is 80 ticks — and the bot's abilities flag tracks
     * {@code survival.takesDamage}, so with damage ON it stands the full 80 — plus the ~10-tick player
     * portal cooldown a just-completed transit leaves behind, plus a 10-tick engine margin. Counts ONLY
     * in-column ticks (the approach is unmetered — a jammed walk-in is a visible pathology to fix, not a
     * thing to time away). Success is EVENT-detected (the post-doTick level change in
     * {@code AllyBotEntity.tick}); a dead portal is STATE-detected (the live isPortal read in
     * {@link #enterPortalTick}); this bound only catches "standing in a live portal and vanilla never
     * fired" — which indicates a real bug (e.g. the fake-player portal process not ticking). (s52: replaced
     * the 200-tick attempt timer + backoff walk.)
     */
    private static final int PORTAL_PROCESS_BOUND_TICKS = 80 + 10 + 10;

    /** Bottom NETHER_PORTAL cell of the column the bot is heading into (null = none chosen yet). Targeting
     *  the BOTTOM cell makes the pathing goal floor ({@code portalTarget.below()}) the standable obsidian
     *  frame base rather than another intangible portal cell. */
    private BlockPos portalTarget;
    /** ENTER terminal state: face the portal column, walk in, then stand still — path steering suppressed
     *  (like the STAY hold) while vanilla's portal process ticks the wait inside the bot's own baseTick. */
    private boolean enteringPortal;
    private int portalEnterTicks;    // IN-COLUMN ticks of the current ENTER attempt (vanilla-wait bound)
    private int portalEnterRetries;  // attempts consumed after the first (one retry, then give up)
    private boolean portalSeekAnnounced; // one "heading for the portal" chat line per seek
    private boolean portalSeekGaveUp;    // no known portal / entry failed → hold + one chat line

    BotPortalFollower(AllyBotEntity bot) {
        this.bot = bot;
    }

    /**
     * FOLLOW/COME cross-dimension guard. When the owner is in another level, the normal goal is
     * meaningless in the bot's level — instead the bot seeks the nearest KNOWN nether portal and walks
     * in (vanilla teleports it; {@code AllyBotEntity.onLevelChanged} re-anchors on arrival). Level equality
     * is re-evaluated every tick, so an owner returning to the bot's dimension aborts the seek mid-route
     * and normal behaviour resumes immediately.
     *
     * @return {@code true} if the owner is elsewhere and the portal-follow consumed this tick.
     */
    boolean followThroughPortal() {
        if (Worlds.of(bot.owner()) == Worlds.of(bot)) {
            if (portalTarget != null || enteringPortal || portalSeekGaveUp || portalSeekAnnounced) {
                resetPortalSeek(); // owner came back — abandon the seek and resume normal FOLLOW/COME
            }
            return false;
        }
        portalSeekTick();
        return true;
    }

    /**
     * One portal-seek tick: pick the nearest known portal (once), path to it with the normal two-tier
     * driver, and hand off to the {@link #enterPortalTick ENTER} terminal state on approach. No known
     * portal → one chat line + hold (the {@code navGaveUp}-style hold: no blind straight-line walking).
     */
    private void portalSeekTick() {
        if (portalSeekGaveUp) { // hold; cleared when the owner returns, the mode changes, or we teleport
            bot.setForward(0.0f);
            return;
        }
        if (enteringPortal) {
            enterPortalTick();
            return;
        }

        final ServerLevel level = (ServerLevel) Worlds.of(bot);
        if (portalTarget == null) {
            BlockPos p = NetherPortalIndex.nearest(level, bot.blockPosition());
            if (p == null) {
                portalSeekGaveUp = true;
                bot.setForward(0.0f);
                bot.chat("I don't know a portal to follow you through.");
                return;
            }
            // Descend to the BOTTOM portal cell of the column so the pathing goal floor (below it) is the
            // standable obsidian frame base. Live-world read (cold, a few cells, once per seek).
            BlockPos below = p.below();
            while (NavBlock.isPortal(NavBlock.descriptorFor(level.getBlockState(below)))) {
                p = below;
                below = p.below();
            }
            portalTarget = p.immutable();
            if (!portalSeekAnnounced) {
                portalSeekAnnounced = true;
                bot.chat("You left this world — heading for the portal at "
                        + AllyBotEntity.compact(portalTarget) + ".");
            }
        }

        // Path to the portal. isGoal tolerance (±1 xz / ±2 y) and ARRIVE_DIST both stop the bot ADJACENT
        // to the portal, so arrival (or proximity) switches to the dedicated ENTER state below.
        boolean arrived = bot.navigator().driveToward(portalTarget.getX() + 0.5, portalTarget.getY(),
                portalTarget.getZ() + 0.5, portalTarget.below());
        double dx = portalTarget.getX() + 0.5 - bot.getX();
        double dy = portalTarget.getY() - bot.getY();
        double dz = portalTarget.getZ() + 0.5 - bot.getZ();
        if (arrived || (dx * dx + dz * dz <= PORTAL_ENTER_DIST * PORTAL_ENTER_DIST
                && Math.abs(dy) <= BotNavigator.ARRIVE_Y)) {
            enteringPortal = true;
            portalEnterTicks = 0;
            bot.navigator().clearPlan(); // ENTER suppresses replan/steer (like the STAY hold): face + walk-in only
        }
    }

    /**
     * The ENTER terminal state: face the portal cell centre and walk forward (through the
     * {@link com.orebit.mod.pathfinding.blockpathfinder.BotSteering} seam) until the feet occupy the portal
     * column, then stand still — no jump, no steering — and let vanilla do everything
     * ({@code NetherPortalBlock.entityInside} marks the entity, the portal process ticks its wait inside the
     * bot's own {@code baseTick}, then the dimension-change path runs; its client packets are absorbed by
     * FakeClientConnection). <b>Success is an event</b>, not a poll: the completed teleport surfaces as the
     * post-{@code doTick} level change in {@code AllyBotEntity.tick} → {@code onLevelChanged} →
     * {@link #resetPortalSeek}. Failure is state-based (s52 — no attempt timer, no backoff walk-away):
     * a portal that DIED under us (blocks broken) is detected immediately by a live one-cell read and
     * re-seeks from a fresh index query; standing in a LIVE column longer than vanilla's own wait
     * ({@link #PORTAL_PROCESS_BOUND_TICKS}, derived) means the teleport machinery genuinely failed —
     * one fresh-query retry, then give up with a chat line.
     */
    private void enterPortalTick() {
        // Portal-broken check (state, not timer): the one fact that invalidates this whole terminal state,
        // read live — a single block read per tick.
        final ServerLevel level = (ServerLevel) Worlds.of(bot);
        if (!NavBlock.isPortal(NavBlock.descriptorFor(level.getBlockState(portalTarget)))) {
            retryOrGiveUpPortal("the portal broke");
            return;
        }
        if (bot.footX() == portalTarget.getX() && bot.footZ() == portalTarget.getZ()) {
            bot.setForward(0.0f); // inside the portal column: stand still while the portal process ticks
            // ENTER forensics (owner couldn't see the bot from the other dimension): announce the wait
            // start + a heartbeat every second, so a never-firing portal process is legible from chat/log.
            if (Debug.VERBOSE && (portalEnterTicks == 0 || portalEnterTicks % 20 == 19)) {
                bot.vlog("portal ENTER: in column " + (portalEnterTicks + 1) + "t/"
                        + PORTAL_PROCESS_BOUND_TICKS + "t, waiting on vanilla");
            }
            if (++portalEnterTicks >= PORTAL_PROCESS_BOUND_TICKS) {
                retryOrGiveUpPortal("it never took me anywhere");
            }
        } else {
            // Walk straight in. Deliberately unmetered: a walk-in that jams on the frame is a real,
            // VISIBLE pathology to diagnose and fix (the portal-broken check above still exits if the
            // portal dies meanwhile) — not something to paper over with a timed walk-away.
            if (Debug.VERBOSE && walkInTicks++ % 20 == 0) {
                bot.vlog("portal ENTER: walking in — feet (" + bot.footX() + "," + bot.footZ()
                        + ") → column (" + portalTarget.getX() + "," + portalTarget.getZ() + ")"
                        + (walkInTicks > 20 ? " [STILL NOT IN COLUMN — walk-in jam?]" : ""));
            }
            bot.faceHorizontally(portalTarget.getX() + 0.5 - bot.getX(),
                    portalTarget.getZ() + 0.5 - bot.getZ());
            bot.setForward(1.0f);
        }
    }

    /** VERBOSE-only walk-in tick counter (diagnostic heartbeat; reset with the seek state). */
    private int walkInTicks;

    /** One fresh-query retry (re-SEEK: new nearest-portal lookup + normal pathed approach), then a terminal
     *  give-up with a chat line. Shared by the two state-detected ENTER failures. */
    private void retryOrGiveUpPortal(String why) {
        if (portalEnterRetries == 0) {
            portalEnterRetries = 1;
            portalEnterTicks = 0;
            enteringPortal = false;
            portalTarget = null; // SEEK re-queries the index; portalEnterRetries stays consumed
        } else {
            enteringPortal = false;
            portalSeekGaveUp = true;
            bot.setForward(0.0f);
            bot.chat("I couldn't get through the portal — " + why + ".");
        }
    }

    /** Drop all portal-seek/ENTER state (owner returned, mode changed, or the teleport completed). */
    void resetPortalSeek() {
        portalTarget = null;
        enteringPortal = false;
        portalEnterTicks = 0;
        portalEnterRetries = 0;
        portalSeekAnnounced = false;
        portalSeekGaveUp = false;
        walkInTicks = 0;
    }
}
