package com.orebit.mod.pathfinding.blockpathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link SteerControl#stationKeep} must hold the bot in <b>whatever medium it is actually in</b> — the
 * 2026-08-10 submerged-wall break.
 *
 * <h2>The witnessed failure (MC 1.20.1, verbose follower log)</h2>
 * The bot swam to a submerged wall, its {@code Traverse} declared {@link MovePlan.Need#AIR} on the
 * destination's body cells, {@link PhaseRunner} found one still blocked and held to mine it — emitting
 * literally no inputs ({@code src=hold, fwd=0.00, str=0.00, jump=false}). On the ground that IS a hold. In
 * water it is a slow sink: 41 hold ticks at a constant {@code dm.y = -0.025} took {@code botY 39.992 ->
 * 39.018}, 0.974 blocks. The foot cell left the move's admitted band, {@link MovePlan#failWhen} fired, the
 * step FAILED, the mine request stopped, and {@code BotMining}'s progress reset — a break only continues
 * while the mover keeps asking for the SAME cell. The bot could never finish an underwater break.
 *
 * <h2>What these tests pin</h2>
 * The hold is a per-tick "what holds me HERE" question with three answers and no timer:
 * <ul>
 *   <li><b>fluid</b> &rarr; {@link SteerControl#holdDepthAt} inside the bot's OWN feet cell (the vertical
 *       twin of the own-column re-centre) — never the step's planned depth, which would drive the bot
 *       toward the cell it has just been stopped from entering. WHICH height in the cell is decided by
 *       {@link BotSteering#standableBelow}: with a floor under the feet the target is the cell FLOOR, so
 *       the bot settles onto it and becomes {@code grounded} (5&times; the mining rate — see the
 *       floor-beats-buoyancy tests); with nothing to stand on it is the cell CENTRE, the height furthest
 *       from either boundary, because buoyancy is then the only hold there is;</li>
 *   <li><b>climbable</b> &rarr; sneak, <b>except scaffolding</b>, which vanilla excludes from the sneak-hold
 *       by name and whose deck shape a sneak deletes outright;</li>
 *   <li><b>ground</b> &rarr; nothing at all, byte-identical to before.</li>
 * </ul>
 *
 * <p><b>Fluid outranks climbable</b> because vanilla says so, not by preference (javap, 1.21.11 named
 * bytecode): {@code LivingEntity.travel} branches to {@code travelInFluid} whenever
 * {@code shouldTravelInFluid}, and {@code handleOnClimbable} — the whole {@code -0.15} clamp plus the
 * sneaking-Player hold — has exactly one caller, inside {@code travelInAir}. On a ladder in water the
 * clamp never executes, so sneak is not a weaker hold there; it is no hold at all.
 */
class StationKeepMediumTest {

    /** The witnessed cell: the bot swims at feet cell y=39, its plan framed there. */
    private static final int FOOT_Y = 39;
    private static final int X = 10, Z = 20;

    /** A configurable-medium bot that records every input the hold presses. */
    private static final class MediumBot implements BotSteering {
        double x = X + 0.5, y = FOOT_Y + 0.5, z = Z + 0.5;
        boolean grounded, water, lava, climbable, scaffoldBelow, climbBelow, standBelow;
        boolean jumping, sneaking;
        int sank;
        float forward = Float.NaN;
        int minedX = Integer.MIN_VALUE, minedY, minedZ;

        MediumBot atY(double y) { this.y = y; return this; }
        MediumBot submerged() { this.water = true; return this; }
        /** A standable block directly under the feet cell — the shaft floor a MineDown stands on. */
        MediumBot overAFloor() { this.standBelow = true; return this; }

        @Override public double x() { return x; }
        @Override public double y() { return y; }
        @Override public double z() { return z; }
        @Override public double velX() { return 0; }
        @Override public double velY() { return 0; }
        @Override public double velZ() { return 0; }
        @Override public int footX() { return (int) Math.floor(x); }
        @Override public int footY() { return (int) Math.floor(y); }
        @Override public int footZ() { return (int) Math.floor(z); }
        @Override public boolean grounded() { return grounded; }
        @Override public boolean inWater() { return water; }
        @Override public boolean inLava() { return lava; }
        @Override public boolean prone() { return false; }
        @Override public boolean onClimbable() { return climbable; }
        @Override public boolean climbableBelow() { return climbBelow; }
        @Override public boolean scaffoldingBelow() { return scaffoldBelow; }
        @Override public boolean standableBelow() { return standBelow; }
        @Override public void faceHorizontally(double dx, double dz) { }
        @Override public void faceTowards(double dx, double dy, double dz) { }
        @Override public void setForward(float zza) { forward = zza; }
        @Override public void setSprinting(boolean s) { }
        @Override public void setJumping(boolean j) { jumping = j; }
        @Override public void setSneak(boolean s) { sneaking = s; }
        @Override public void sinkInWater() { sank++; }
        @Override public boolean solidAt(int bx, int by, int bz) { return true; }
        @Override public boolean airAt(int bx, int by, int bz) { return true; }
        /** Exactly one cell obstructs — the live geometry that makes the runner hold. Defaults to the
         *  witnessed submerged wall (the destination's lower body cell); a shaft test points it downward. */
        int blockX = X + 1, blockY = FOOT_Y, blockZ = Z;
        MediumBot obstructedAt(int bx, int by, int bz) {
            blockX = bx; blockY = by; blockZ = bz; return this;
        }
        @Override public boolean movementBlockedAt(int bx, int by, int bz, int dx, int dz) {
            return bx == blockX && by == blockY && bz == blockZ;
        }
        @Override public void mine(int bx, int by, int bz) { minedX = bx; minedY = by; minedZ = bz; }
        @Override public void place(int bx, int by, int bz) { }
        @Override public void setDoorOpen(int bx, int by, int bz, boolean open) { }
        @Override public boolean doorOpenAt(int bx, int by, int bz) { return true; }
        @Override public boolean swimHazardAt(int bx, int by, int bz) { return false; }
        @Override public boolean bubbleUpAt(int bx, int by, int bz) { return false; }
        @Override public double slipperinessAt(int bx, int by, int bz) { return 0.6; }
        @Override public boolean gapFloorHazardAt(int bx, int by, int bz) { return false; }
    }

    /** A lateral step at the bot's own height (the witnessed Traverse). */
    private static SteerView lateral() {
        return seg(FOOT_Y, FOOT_Y);
    }

    /** A segment from feet {@code fromFoot} to feet {@code toFoot}, in SteerView's feet-cell-plus-one frame. */
    private static SteerView seg(int fromFoot, int toFoot) {
        return new SteerView() {
            @Override public double sx() { return X + 0.5; }
            @Override public double sy() { return fromFoot + 1.0; }
            @Override public double sz() { return Z + 0.5; }
            @Override public double tx() { return X + 1.5; }
            @Override public double ty() { return toFoot + 1.0; }
            @Override public double tz() { return Z + 0.5; }
            @Override public boolean hasNext() { return false; }
            @Override public double nx() { return 0; }
            @Override public double ny() { return 0; }
            @Override public double nz() { return 0; }
        };
    }

    // ---- FLUID: the fix --------------------------------------------------------------------------------

    @Test
    void submergedHoldHighInItsCellSinksBackTowardTheCellCentre() {
        MediumBot b = new MediumBot().submerged().atY(FOOT_Y + 0.992); // the witnessed botY 39.992
        SteerControl.stationKeep(b, lateral());

        assertEquals(1, b.sank,
                "a no-input hold in water sinks 0.025/tick — 0.974 blocks over the witnessed 41-tick break. "
                        + "The depth autopilot is the medium's station-keep");
        assertFalse(b.jumping, "above the cell centre the autopilot sinks, it does not rise");
    }

    @Test
    void submergedHoldLowInItsCellRisesBackTowardTheCellCentre() {
        MediumBot b = new MediumBot().submerged().atY(FOOT_Y + 0.02);
        SteerControl.stationKeep(b, lateral());

        assertTrue(b.jumping, "below the cell centre the autopilot presses jump (vanilla jumpInLiquid, +0.04/t)");
        assertEquals(0, b.sank, "and never both");
    }

    @Test
    void submergedHoldAtTheCellCentreIsQuiet() {
        MediumBot b = new MediumBot().submerged().atY(FOOT_Y + 0.5);
        SteerControl.stationKeep(b, lateral());

        assertFalse(b.jumping, "inside the WATER_RISE_DEADBAND the bang-bang controller must not chatter");
        assertEquals(0, b.sank, "inside the deadband the bang-bang controller must not chatter");
        assertEquals(0.0f, b.forward, 1e-6f, "and it still holds its own column at exact zero forward");
    }

    @Test
    void theHeldDepthIsTheBotsOwnCellNeverTheStepsPlannedDepth() {
        // A HOLD IS A HOLD, including vertically: the runner has STOPPED this move, so the step's vertical
        // intent is irrelevant. Aiming at p.ty() would drive the bot at the cell it was stopped from
        // entering — the vertical twin of the (58,133,189) wedge that made the hold re-centre on its OWN
        // column rather than the target.
        MediumBot down = new MediumBot().submerged().atY(FOOT_Y + 0.5);
        SteerControl.stationKeep(down, seg(FOOT_Y, FOOT_Y - 3));   // the step wants to dive three blocks
        assertEquals(0, down.sank, "a descending step must not make the HOLD dive");
        assertFalse(down.jumping);

        MediumBot up = new MediumBot().submerged().atY(FOOT_Y + 0.5);
        SteerControl.stationKeep(up, seg(FOOT_Y, FOOT_Y + 3));     // the step wants to rise three blocks
        assertFalse(up.jumping, "a rising step must not make the HOLD climb");
        assertEquals(0, up.sank);
    }

    @Test
    void lavaHoldsDepthToo() {
        MediumBot b = new MediumBot().atY(FOOT_Y + 0.992);
        b.lava = true;
        SteerControl.stationKeep(b, lateral());

        assertEquals(1, b.sank, "the autopilot is fluid-general — lava swims like slow water (holdDepth's own rule)");
    }

    @Test
    void aLadderInWaterHoldsDepthNotSneak() {
        // javap, 1.21.11: handleOnClimbable — the -0.15 clamp AND the sneaking-Player hold — has exactly one
        // caller, inside travelInAir. LivingEntity.travel takes travelInFluid whenever shouldTravelInFluid,
        // so in water the climbable clamp never executes and sneak holds nothing.
        MediumBot b = new MediumBot().submerged().atY(FOOT_Y + 0.992);
        b.climbable = true;
        SteerControl.stationKeep(b, lateral());

        assertEquals(1, b.sank, "fluid outranks climbable: only the depth autopilot can hold a bot in water");
        assertFalse(b.sneaking, "sneak's climbable hold lives in vanilla's LAND travel branch — it is inert here");
    }

    @Test
    void aBotStandingOnThePoolFloorPressesNothing() {
        MediumBot b = new MediumBot().submerged().atY(FOOT_Y);
        b.grounded = true;   // submerged but supported — the floor already holds it
        SteerControl.stationKeep(b, lateral());

        assertFalse(b.jumping,
                "a grounded bot needs no vertical input, and pressing jump in water would swim it OFF its own floor");
        assertEquals(0, b.sank);
        assertFalse(b.sneaking);
    }

    // ---- FLUID: a FLOOR beats buoyancy (2026-08-15) ------------------------------------------------------
    //
    // Underwater mining pays TWO vanilla penalties, and only one of them is forced. Player.getDestroySpeed
    // divides by 5 for !onGround and multiplies by the submerged-mining attribute (0.2 without Aqua
    // Affinity) for an eye in water; BotMining accumulates BlockState.getDestroyProgress, so both land on
    // the bot. Floating to mine therefore costs 25x, standing costs 5x, and the eye stays wet either way —
    // so the whole difference is whether the hold chooses to stand when it could.
    //
    // Measured on the vd=16 flagship, 2026-08-15: a MineDown shaft at (195,.,68) held `hold:depth` for
    // 5,121 ticks — 43% of a 12,000-tick budget — bobbing between botY -13.67 and -13.72, grounded=false,
    // with a full deepslate block 0.30 below the feet. One block per ~1,200 ticks, and it ran out of
    // budget with 14 still to go.

    @Test
    void submergedHoldOverAFloorSettlesOntoItInsteadOfFloating() {
        MediumBot b = new MediumBot().submerged().overAFloor().atY(FOOT_Y + 0.5);
        SteerControl.stationKeep(b, lateral());

        assertEquals(1, b.sank,
                "with something to stand on, the hold aims at the cell FLOOR, not its centre — the last "
                        + "third of a block is closed under its own power and the block stops it");
        assertFalse(b.jumping, "and it must never press jump, which would swim the bot back off its floor");
    }

    @Test
    void theSameHoldWithoutAFloorStillFloatsAtTheCellCentre() {
        // The contrast that makes the rule a rule: identical pose, identical medium, no floor. Buoyancy is
        // then the ONLY hold available, so the target stays the centre — the height furthest from either
        // cell boundary, which is what keeps an arbitrarily slow break inside the foot cell.
        MediumBot b = new MediumBot().submerged().atY(FOOT_Y + 0.5);
        SteerControl.stationKeep(b, lateral());

        assertEquals(0, b.sank, "no floor: hold the centre, exactly as before");
        assertFalse(b.jumping);
    }

    @Test
    void onceOnTheFloorTheHoldIsQuiet() {
        MediumBot b = new MediumBot().submerged().overAFloor().atY(FOOT_Y + 0.05);
        SteerControl.stationKeep(b, lateral());

        assertEquals(0, b.sank, "inside the deadband around the cell floor the bang-bang controller must not chatter");
        assertFalse(b.jumping);
        assertEquals(0.0f, b.forward, 1e-6f, "and the own-column re-centre is still exact zero");
    }

    @Test
    void theFloorHoldIsONE_SIDED_itCanOnlySink() {
        // The property that makes the floor target safe to press, and it is structural rather than tuned:
        // the target is footY(), which is derived from the bot's OWN position, so `y() >= footY()` holds by
        // construction and holdDepthAt's rise branch is UNREACHABLE here. The floor hold can therefore only
        // ever press SINK — it can never swim the bot back up off the block it has just settled on, which
        // is the one way a "settle on the floor" rule could have fought the grounded short-circuit above it.
        //
        // The corollary is the honest limit of this rule: nothing in the SERVO stops a descent. If the floor
        // is a lie the bot keeps sinking a cell at a time. That is deliberate — standableBelow() is a live
        // world read, and for MineDown the floor breaking underfoot IS the movement.
        for (double f = 0.0; f < 1.0; f += 0.05) {
            MediumBot b = new MediumBot().submerged().overAFloor().atY(FOOT_Y + f);
            SteerControl.stationKeep(b, lateral());
            assertFalse(b.jumping, "the floor hold must never press jump — at footY + " + f);
        }
    }

    @Test
    void lavaSettlesOntoAFloorToo() {
        MediumBot b = new MediumBot().overAFloor().atY(FOOT_Y + 0.5);
        b.lava = true;
        SteerControl.stationKeep(b, lateral());

        assertEquals(1, b.sank, "the rule is fluid-general, like the autopilot it retargets");
    }

    @Test
    void theSubmergedShaftHoldMinesTheBlockItIsStandingOn() {
        // MineDown's own shape, end to end: the bot stands on the block its plan declares AIR, so the hold
        // must issue the break AND settle onto that very block on the same tick. (It breaks a moment later
        // and the bot drops — that IS the movement.)
        MovePlan plan = new MovePlan();
        plan.phase("descend").need(MovePlan.Need.AIR, X, FOOT_Y - 1, Z);
        PhaseRunner runner = new PhaseRunner();
        runner.begin(plan);

        MediumBot b = new MediumBot().submerged().overAFloor()
                .obstructedAt(X, FOOT_Y - 1, Z).atY(FOOT_Y + 0.5);
        assertFalse(runner.run(b, seg(FOOT_Y, FOOT_Y - 1)), "the shaft floor is unmet — the move cannot complete");
        assertEquals(X, b.minedX, "the hold must still issue the break on the block underfoot");
        assertEquals(FOOT_Y - 1, b.minedY);
        assertEquals(1, b.sank, "the hold settles the bot onto the block it is breaking, where it mines 5x faster");
    }

    // ---- CLIMBABLE: scaffolding is exempt ---------------------------------------------------------------

    @Test
    void aHangOnALadderStillSneaks() {
        MediumBot b = new MediumBot().atY(FOOT_Y);
        b.climbable = true;
        SteerControl.stationKeep(b, lateral());

        assertTrue(b.sneaking, "unchanged: sneak zeroes the -0.15 climbable slide for a sneaking Player");
        assertFalse(b.jumping, "jump would CLIMB from inside a curtain, not hold");
    }

    @Test
    void scaffoldingIsExemptFromTheSneakHold() {
        // Vanilla's hold reads `y<0 && !getInBlockState().is(Blocks.SCAFFOLDING) && isSuppressingSliding...`
        // — scaffolding excluded BY NAME — and ScaffoldingBlock.getCollisionShape returns Shapes.empty() for
        // a DESCENDING context, so a sneak also deletes the deck the bot is resting on. Sneak on scaffolding
        // is a descend input; the honest hold presses nothing and lets the bot settle onto the next plate.
        MediumBot b = new MediumBot().atY(FOOT_Y);
        b.climbable = true;
        b.scaffoldBelow = true;
        SteerControl.stationKeep(b, lateral());

        assertFalse(b.sneaking, "sneaking in scaffolding DESCENDS — it is the opposite of a hold");
        assertFalse(b.jumping, "and jump would climb the column; nothing holds a bot inside scaffolding");
    }

    // ---- GROUND: provably unchanged ---------------------------------------------------------------------

    @Test
    void theGroundHoldIsUntouched() {
        MediumBot b = new MediumBot().atY(FOOT_Y);
        b.grounded = true;
        SteerControl.stationKeep(b, lateral());

        assertEquals(0.0f, b.forward, 1e-6f, "centred on its own column: exactly zero forward");
        assertFalse(b.jumping);
        assertFalse(b.sneaking, "the !grounded() conjunct is load-bearing (flagship 58.43 -> 212.55 without it)");
        assertEquals(0, b.sank, "and the fluid autopilot is a no-op out of fluid");
    }

    // ---- end to end through the runner ------------------------------------------------------------------

    @Test
    void theSubmergedRunnerHoldMinesAndStationKeepsOnTheSameTick() {
        MovePlan plan = new MovePlan().moveDir(1, 0);
        plan.phase("walk")
                .need(MovePlan.Need.AIR, X + 1, FOOT_Y, Z)
                .need(MovePlan.Need.AIR, X + 1, FOOT_Y + 1, Z);
        PhaseRunner runner = new PhaseRunner();
        runner.begin(plan);

        MediumBot b = new MediumBot().submerged().atY(FOOT_Y + 0.992);
        assertFalse(runner.run(b, lateral()), "the obstruction is unmet — the move cannot complete");

        assertEquals(X + 1, b.minedX, "the hold must still issue the break (BotMining is reactive: releasing resets it)");
        assertEquals(1, b.sank,
                "and it must hold depth on the SAME tick — the 41-tick sink is what stopped the break finishing");
        assertEquals(MovePlan.Need.AIR, runner.holdNeed());
    }
}
