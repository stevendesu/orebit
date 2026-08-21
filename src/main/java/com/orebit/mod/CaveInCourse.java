package com.orebit.mod;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.orebit.mod.platform.ConfigDir;
import com.orebit.mod.platform.PlatformEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Headless CAVE-IN harness — <b>will the bot undermine a gravity column and bury itself?</b>
 * Armed by {@code -Dorebit.cavein}; inert otherwise.
 *
 * <p><b>Why.</b> The 2026-08-21 flagship died of suffocation at {@code (968,56,905)} after 91 motionless
 * seconds. Reconstructed geometry: the bot stood in an air pocket, its HEAD cell held STONE, and above that
 * stone sat a gravel column. Breaking the stone to climb removed the column's support; two gravel blocks
 * fell into the bot's feet and head cells.
 *
 * <p>The nav grid never objected, for two compounding reasons, both since fixed (owner ruling 2026-08-21).
 * (1) <b>Frame.</b> The old {@code RISKY_EDIT} was FLOOR-framed — {@code NavFlags.compute} read a floor
 * cell's own body space ({@code ground} / {@code a1}=feet / {@code a2}=head) — so for a bot on floor
 * {@code y} the gravel at {@code y+3} was simply out of frame; and its two {@code unsupported}-gated terms
 * described only the OTHER hazard (a suspended ceiling a nearby update pokes loose), never the UNDERMINING
 * one: a gravity block that HAS a support, whose support is the cell being broken. The bit is now
 * {@code NavFlags.RISKS_GRAVITY}, CELL-CENTRED — "breaking or placing at THIS cell drops a gravity block" —
 * and covers both hazards. (2) <b>Gate site.</b> Even a correct bit would not have saved this bot: the
 * gate was seeded once per candidate from ONE floor cell and then applied to edits several cells away, and
 * {@code Pillar}'s landing-body breaks (the ones that removed the ceiling here) were gated by NOTHING. The
 * gate now lives inside {@code EditScratch}'s break/place fold, testing the cell actually being edited, so
 * no movement can forget it.
 *
 * <p><b>The scene</b> — a flat layered slab, so no route exists but straight up through the column:
 * <pre>
 *   y = FLOOR+7 ..   air            goal stands here (feet FLOOR+7)
 *   y = FLOOR+6      STONE          target floor
 *   y = FLOOR+5      GRAVEL         upper — falls when the lower one goes
 *   y = FLOOR+4      GRAVEL         lower — IS the support for the upper
 *   y = FLOOR+3      STONE          the column's support   [SUPPORTED variant only]
 *   y = FLOOR+2      air            bot head
 *   y = FLOOR+1      air            bot feet
 *   y = FLOOR ..     STONE          ground
 * </pre>
 * Every upward route must break either the stone ceiling or the lower gravel, and each of those is the
 * support of a gravity block.
 *
 * <p><b>Two variants, exercising DIFFERENT terms</b> — do not conflate them:
 * <ul>
 *   <li>{@code -Dorebit.cavein=supported} (default) — stone ceiling present. The flagship's exact geometry,
 *       and the case the OLD floor-framed bit could not express at all: nothing here is unsupported, so
 *       every {@code unsupported}-gated term was silent by construction. It is now covered by
 *       {@code RISKS_GRAVITY} <b>half A</b> — the stone at {@code FLOOR+3} is the support of the gravel at
 *       {@code FLOOR+4}, and the gravel at {@code FLOOR+4} is the support of the one at {@code FLOOR+5}, so
 *       both carry the bit and both candidate breaks are refused at the fold. <b>This variant is the
 *       acceptance criterion.</b></li>
 *   <li>{@code -Dorebit.cavein=suspended} — no stone ceiling; the gravel hangs over the pocket's air. The
 *       classic cave-in, covered by <b>half B</b> (the scatter). Kept as the CONTROL: it should hold both
 *       before and after the reframe, and a regression here means the change broke what already worked.</li>
 * </ul>
 *
 * <p><b>Painting must set {@code UPDATE_CLIENTS} (2) | {@code UPDATE_KNOWN_SHAPE} (16) |
 * {@code UPDATE_SKIP_ON_PLACE} (512) = 530.</b> Suppressing NEIGHBOUR updates alone is NOT enough to leave
 * a suspended column unarmed — {@code FallingBlock} arms itself down <b>two independent paths</b>, and the
 * first cut of this fixture (flag 2) silently voided its own SUSPENDED control because of both. Read out of
 * the 1.21.11 sources, not inferred:
 * <ul>
 *   <li><b>{@code onPlace}, suppressed by bit 512.</b> {@code LevelChunk.setBlockState} runs
 *       {@code if (!level.isClientSide() &amp;&amp; (i &amp; 512) == 0) blockState.onPlace(...)}, and
 *       {@code FallingBlock.onPlace} is an unconditional
 *       {@code level.scheduleTick(pos, this, getDelayAfterPlace())} — it consults nothing about supports or
 *       neighbours. So without 512 <i>every gravel block the harness paints schedules its own fall</i>.</li>
 *   <li><b>{@code updateShape}, suppressed by bit 16.</b> {@code Level.setBlock} then runs
 *       {@code if ((i &amp; 16) == 0 &amp;&amp; j > 0) { ... blockState.updateNeighbourShapes(...) ... }},
 *       which walks the 6 directions into {@code LevelAccessor.neighborShapeChanged} →
 *       {@code NeighborUpdater.executeShapeUpdate} → the neighbour's {@code updateShape}; and
 *       {@code FallingBlock.updateShape} <i>also</i> opens with
 *       {@code scheduledTickAccess.scheduleTick(pos, this, getDelayAfterPlace())}. So carving the pocket's
 *       air at {@code FLOOR+3} — directly beneath the column — arms the gravel above it even when 512
 *       already stopped {@code onPlace}. This is the path a 514-only run still tripped: the self-check
 *       below reported {@code ERROR fixture voided} at tick 200 with the bot untouched.</li>
 * </ul>
 * Bit 16 is safe for this scene: every block painted here (stone, gravel, bedrock, air) has no
 * connection/shape state for a neighbour recalculation to establish. The literals are spelled out rather
 * than referencing {@code Block.UPDATE_KNOWN_SHAPE} / {@code Block.UPDATE_SKIP_ON_PLACE} because this file
 * is COMMON source and must compile across 1.17.1 → 26.x, where those constants are not uniformly present.
 *
 * <p>Historical evidence for why this matters: a 16:41 flag-2 run reported {@code buried=true ticks=201}
 * with witness {@code feet(998,151,998)=gravel} while the bot's config had {@code mining.canMine=false} —
 * it could not possibly have undermined anything.
 *
 * <p><b>Fixture self-check.</b> Because a voided fixture reads exactly like a pass (or like a burial the
 * bot did not cause), the two gravel cells of the column are re-read at the tick the goal is issued; if
 * either has settled, the run finishes {@code ERROR fixture voided} instead of pretending to measure
 * anything.
 *
 * <p><b>The verdict is BURIAL, not arrival.</b> Refusing the climb is the CORRECT outcome: with break and
 * place granted and no other route, a planner that respects the hazard reports no route and stays in the
 * pocket. So {@code reachedGoal} is recorded but is not the assertion — {@code buried} is. A solid block in
 * the body space of a living bot means it undermined a gravity column, and that is the defect.
 */
public final class CaveInCourse {

    private CaveInCourse() {}

    private static final String RESULT_FILE = "orebit-cavein-result.properties";
    private static final int FLOOR = 150;
    private static final int BASE_X = 1000, BASE_Z = 1000;
    /** Half-width of the solid slab surrounding the pocket — the seal against a lateral escape. */
    private static final int PAD = 10;
    /** Half-width of the carved air pocket the bot starts in (a 5x5 room). */
    private static final int POCKET = 2;
    /** Let the nav grid build and any paint-time settling finish before the goal is issued. */
    private static final int ISSUE_DELAY_TICKS = 200;
    private static final int BUDGET_TICKS = 2400;

    /**
     * {@code Block.UPDATE_CLIENTS} (2) | {@code Block.UPDATE_KNOWN_SHAPE} (16) |
     * {@code Block.UPDATE_SKIP_ON_PLACE} (512). Bit 2 sends the change to clients without notifying
     * neighbours; bits 16 and 512 are the two that actually matter, because {@code FallingBlock} schedules
     * its own fall from BOTH {@code onPlace} (gated on 512 in {@code LevelChunk.setBlockState}) and
     * {@code updateShape} (reached through the {@code updateNeighbourShapes} walk that {@code Level.setBlock}
     * gates on 16). Omitting either one arms the column at paint time — see the class Javadoc for the
     * verified call chains. Spelled as literals, not as the {@code Block} constants, because this is common
     * source compiled across the whole 1.17.1 → 26.x range.
     */
    private static final int NO_NEIGHBOUR_UPDATE_NO_ON_PLACE = 2 | 16 | 512;

    private static boolean suspendedVariant() {
        return "suspended".equalsIgnoreCase(System.getProperty("orebit.cavein", ""));
    }

    public static void register(PlatformEvents events) {
        if (System.getProperty("orebit.cavein") == null) {
            return;
        }
        Course course = new Course();
        events.onServerStarted(course::start);
        events.onWorldTickEnd(course::tick);
        OrebitCommon.LOGGER.info("[Orebit/cavein] armed: variant={} pocket=({},{},{})",
                suspendedVariant() ? "SUSPENDED (control - existing terms)" : "SUPPORTED (the flagship gap)",
                BASE_X, FLOOR + 1, BASE_Z);
    }

    private static final class Course {
        MinecraftServer server;
        ServerLevel level;
        AllyBotEntity bot;
        FakePlayerEntity owner;

        int ticks;
        boolean done;
        boolean issued;
        BlockPos goal;
        String burialWitness;

        void start(MinecraftServer server) {
            this.server = server;
            Debug.ENABLED = true;
            Debug.VERBOSE = true;
            this.level = server.overworld();
            paint();
            this.goal = new BlockPos(BASE_X, FLOOR + 7, BASE_Z);
            owner = new FakePlayerEntity(server, level, new GameProfile(
                    UUID.nameUUIDFromBytes("OrebitCaveIn:owner".getBytes(StandardCharsets.UTF_8)), "CaveIn"));
            // No addFreshEntity - BotManager.spawnBotFor owns placement (a connection-less
            // FakePlayerEntity added to the level NPEs on its first packet send).
            owner.setPos(BASE_X + 0.5, FLOOR + 1, BASE_Z + 0.5);
            BotManager.spawnBotFor(owner);
            bot = BotManager.botFor(owner);
            if (bot == null) {
                finish("ERROR bot never spawned");
            }
        }

        void paint() {
            BlockState stone = Blocks.STONE.defaultBlockState();
            BlockState gravel = Blocks.GRAVEL.defaultBlockState();
            BlockState air = Blocks.AIR.defaultBlockState();
            for (int dx = -PAD; dx <= PAD; dx++) {
                for (int dz = -PAD; dz <= PAD; dz++) {
                    int x = BASE_X + dx, z = BASE_Z + dz;
                    // Clear well above so nothing from worldgen offers an alternate route or extra support.
                    for (int y = FLOOR + 7; y <= FLOOR + 24; y++) {
                        set(x, y, z, air);
                    }
                    // A SOLID slab, not a layered floor. The first cut of this fixture left the pocket open
                    // at its rim and the bot simply walked out sideways and climbed the outside — it
                    // finished at x=BASE+9.17 having undermined nothing, which reads as a pass and proves
                    // nothing. Everything is stone unless carved below.
                    for (int y = FLOOR - 4; y <= FLOOR + 6; y++) {
                        set(x, y, z, stone);
                    }
                    set(x, FLOOR + 4, z, gravel);       // lower gravel - support of the upper
                    set(x, FLOOR + 5, z, gravel);       // upper gravel
                }
            }
            // BEDROCK SHELL. Stone walls are not a seal for a bot with mining rights: cut two of this
            // fixture leaked here. Run 1 walked off the open rim; run 2 priced a 25-waypoint lateral TUNNEL
            // (cost 3399.7) to x=BASE+PAD and stepped out of the slab entirely, ending on an Ascend at
            // (1010,152,999) — undermining nothing and reporting a false PASS. Bedrock is vanilla-unbreakable
            // and is detected as such at classification (hardness < 0, no hardcoded list), so the planner
            // refuses it without any config. This makes the gravity column the ONLY exit, which is the whole
            // premise of the test.
            for (int dx = -PAD; dx <= PAD; dx++) {
                for (int dz = -PAD; dz <= PAD; dz++) {
                    if (dx != -PAD && dx != PAD && dz != -PAD && dz != PAD) {
                        continue;                       // perimeter only
                    }
                    for (int y = FLOOR - 4; y <= FLOOR + 24; y++) {
                        set(BASE_X + dx, y, BASE_Z + dz, Blocks.BEDROCK.defaultBlockState());
                    }
                }
            }
            // Carve the SEALED pocket. PAD blocks of solid material surround it on every side, so the only
            // exit is straight up through the gravity column.
            for (int dx = -POCKET; dx <= POCKET; dx++) {
                for (int dz = -POCKET; dz <= POCKET; dz++) {
                    int x = BASE_X + dx, z = BASE_Z + dz;
                    set(x, FLOOR + 1, z, air);          // bot feet
                    set(x, FLOOR + 2, z, air);          // bot head
                    if (suspendedVariant()) {
                        set(x, FLOOR + 3, z, air);      // no ceiling: the gravel above hangs unsupported
                    }
                }
            }
        }

        /** Paint without neighbour updates, without {@code onPlace} and without the shape-update walk, so a
         *  suspended column is not armed at paint time — see {@link #NO_NEIGHBOUR_UPDATE_NO_ON_PLACE}. */
        void set(int x, int y, int z, BlockState state) {
            level.setBlock(new BlockPos(x, y, z), state, NO_NEIGHBOUR_UPDATE_NO_ON_PLACE);
        }

        /** Both cells of the gravity column, read live: the fixture is only meaningful while they stand. */
        boolean columnIntact() {
            return level.getBlockState(new BlockPos(BASE_X, FLOOR + 4, BASE_Z)).is(Blocks.GRAVEL)
                    && level.getBlockState(new BlockPos(BASE_X, FLOOR + 5, BASE_Z)).is(Blocks.GRAVEL);
        }

        void tick(ServerLevel lvl) {
            if (done || bot == null || lvl != level) {
                return;
            }
            ticks++;
            if (!issued) {
                if (ticks < ISSUE_DELAY_TICKS) {
                    return;
                }
                // FIXTURE SELF-CHECK (both variants). The premise of this harness is that the gravity
                // column is still standing when the goal is issued; if it settled during the delay the run
                // measures nothing, and a silent void reads exactly like a pass (or like a burial the bot
                // did not cause). Convert it into a diagnosable ERROR instead.
                if (!columnIntact()) {
                    finish("ERROR fixture voided - column settled before the goal");
                    return;
                }
                issued = true;
                bot.comeTo(goal, 0.75, 0.75, 0);
                OrebitCommon.LOGGER.info("[Orebit/cavein] goal issued at tick {} -> {}", ticks, goal);
                return;
            }

            // THE ASSERTION. A solid block in the body space of a living bot means a gravity column came
            // down on it - the exact death the flagship took.
            BlockPos feet = bot.blockPosition();
            BlockPos head = feet.above();
            if (!level.getBlockState(feet).isAir() || !level.getBlockState(head).isAir()) {
                burialWitness = String.format(Locale.ROOT, "feet%s=%s head%s=%s",
                        feet, level.getBlockState(feet).getBlock(),
                        head, level.getBlockState(head).getBlock());
                finish("FAIL buried - the bot undermined a gravity column");
                return;
            }
            if (!bot.isAlive()) {
                finish("FAIL bot died");
                return;
            }
            if (bot.blockPosition().getY() >= FLOOR + 7) {
                finish("REACHED the target floor without burial");
                return;
            }
            if (ticks >= BUDGET_TICKS) {
                // The EXPECTED pass for the supported variant: no safe route exists, so the bot stays put.
                finish("PASS refused the climb - never buried");
            }
        }

        void finish(String reason) {
            done = true;
            Path file = ConfigDir.serverDir(server).resolve(RESULT_FILE);
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write("completed=true\n");
                w.write("reason=" + reason + "\n");
                w.write("variant=" + (suspendedVariant() ? "suspended" : "supported") + "\n");
                w.write("ticks=" + ticks + "\n");
                w.write("buried=" + (burialWitness != null) + "\n");
                if (burialWitness != null) {
                    w.write("burialWitness=" + burialWitness + "\n");
                }
                w.write("alive=" + (bot != null && bot.isAlive()) + "\n");
                w.write(String.format(Locale.ROOT, "finalPos=(%.2f,%.2f,%.2f)%n",
                        bot == null ? -1 : bot.getX(), bot == null ? -1 : bot.getY(),
                        bot == null ? -1 : bot.getZ()));
                w.write("pocketFeetY=" + (FLOOR + 1) + "\n");
                w.write("goalFeetY=" + (FLOOR + 7) + "\n");
            } catch (IOException ignored) {
                // the log line below is the primary channel
            }
            OrebitCommon.LOGGER.info("[Orebit/cavein] RESULT {} variant={} ticks={} buried={} {}",
                    reason, suspendedVariant() ? "suspended" : "supported", ticks,
                    burialWitness != null, burialWitness == null ? "" : burialWitness);
            server.halt(false);
        }
    }
}
