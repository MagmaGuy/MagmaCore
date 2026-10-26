package com.magmaguy.easyminecraftgoals.v26.mind;

import com.magmaguy.magmacore.ai.MindBehavior;
import com.magmaguy.magmacore.ai.MindContext;
import com.magmaguy.magmacore.ai.MindControl;
import com.magmaguy.magmacore.ai.MindExecutionPolicy;
import com.magmaguy.magmacore.ai.MindMemoryKey;
import com.magmaguy.magmacore.ai.MindMemoryStore;
import com.magmaguy.magmacore.ai.MindProgram;
import com.magmaguy.magmacore.ai.MindRunawayPolicy;
import com.magmaguy.magmacore.ai.MindSensor;
import com.magmaguy.magmacore.ai.MindStopReason;
import com.magmaguy.magmacore.scripting.LuaMindDefinition;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Probe intervals and sensor phases, through the real Brain that offers every stopped behavior a start each tick. */
class NativeMindBehaviorProbeTest {
    private static final long FIRST_TICK = 1_000L;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /**
     * A behavior holding MOVE that records the ticks its canStart runs on and starts when {@code allowed} says so;
     * it continues while {@code continuing} says so.
     */
    private static class Probed implements MindBehavior {
        final String identifier;
        final int priority;
        final int probeInterval;
        final LongPredicate allowed;
        LongPredicate continuing = tick -> true;
        final List<Long> asked = new ArrayList<>();
        final List<Long> started = new ArrayList<>();
        final List<MindStopReason> stopped = new ArrayList<>();

        Probed(String identifier, int priority, int probeInterval, LongPredicate allowed) {
            this.identifier = identifier;
            this.priority = priority;
            this.probeInterval = probeInterval;
            this.allowed = allowed;
        }

        @Override
        public String identifier() {
            return identifier;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public Set<MindControl> controls() {
            return Set.of(MindControl.MOVE);
        }

        @Override
        public int probeInterval() {
            return probeInterval;
        }

        @Override
        public boolean canStart(MindContext context) {
            asked.add(context.gameTick());
            return allowed.test(context.gameTick());
        }

        @Override
        public boolean canContinue(MindContext context) {
            return continuing.test(context.gameTick());
        }

        @Override
        public void start(MindContext context) {
            started.add(context.gameTick());
        }

        @Override
        public void tick(MindContext context) {
        }

        @Override
        public void stop(MindContext context, MindStopReason reason) {
            stopped.add(reason);
        }
    }

    /** One entity's Mind runtime on mocked Minecraft objects, ticked by hand. */
    private static final class Entity {
        private final ServerLevel level = mock(ServerLevel.class);
        private final MinecraftServer server = mock(MinecraftServer.class);
        private final NativeMindProgramRuntime runtime;
        private final MindMemoryStore store;

        Entity(UUID owner, MindBehavior... behaviors) {
            this(owner, program(MindExecutionPolicy.DEFAULT, behaviors), new NativeMindServerBudget());
        }

        Entity(UUID owner, MindProgram program, NativeMindServerBudget budget) {
            NativeMindSession session = mock(NativeMindSession.class);
            when(session.logicalOwner()).thenReturn(owner);
            NativeMindBody body = mock(NativeMindBody.class);
            when(body.mob()).thenReturn(mock(Mob.class));
            when(body.entity()).thenReturn(mock(LivingEntity.class));
            when(level.getServer()).thenReturn(server);
            store = new MindMemoryStore(program.schema());
            runtime = new NativeMindProgramRuntime(session, body, program, store, 1L, budget);
            runtime.buildBrain();
        }

        void tick(long gameTick) {
            when(level.getGameTime()).thenReturn(gameTick);
            when(server.getTickCount()).thenReturn((int) gameTick);
            runtime.beginTick(level);
            runtime.tickBrain(level);
        }

        void tickThrough(long lastTick) {
            for (long tick = FIRST_TICK; tick <= lastTick; tick++) tick(tick);
        }
    }

    private static MindProgram program(MindExecutionPolicy policy, MindBehavior... behaviors) {
        MindProgram.Builder builder = MindProgram.builder("fixture:probe", 1L).executionPolicy(policy);
        for (MindBehavior behavior : behaviors) builder.behavior(behavior);
        return builder.build();
    }

    /** The first tick at or after {@code tick} on this owner's grid for the identifier. */
    private static long grid(UUID owner, String identifier, int interval, long tick) {
        return tick + Math.floorMod(NativeMindProbeSchedule.phase(owner, identifier, interval) - tick, (long) interval);
    }

    private static List<Long> every(long first, long step, long last) {
        List<Long> ticks = new ArrayList<>();
        for (long tick = first; tick <= last; tick += step) ticks.add(tick);
        return ticks;
    }

    /** An owner, past {@code owner}, whose phase for the identifier differs from {@code owner}'s. */
    private static UUID otherPhase(UUID owner, String identifier, int interval) {
        UUID other = new UUID(owner.getMostSignificantBits(), owner.getLeastSignificantBits() + 1L);
        while (NativeMindProbeSchedule.phase(other, identifier, interval)
                == NativeMindProbeSchedule.phase(owner, identifier, interval)) {
            other = new UUID(other.getMostSignificantBits(), other.getLeastSignificantBits() + 1L);
        }
        return other;
    }

    @Test
    void aBehaviorProbedEveryFiveTicksIsAskedEveryFifthTick() {
        UUID owner = new UUID(1L, 2L);
        Probed probed = new Probed("fixture:drift", 20, 5, tick -> false);
        new Entity(owner, probed).tickThrough(FIRST_TICK + 39);

        assertEquals(every(grid(owner, "fixture:drift", 5, FIRST_TICK), 5, FIRST_TICK + 39), probed.asked);
    }

    @Test
    void aBehaviorWithTheDefaultIntervalIsAskedEveryTick() {
        Probed probed = new Probed("fixture:hover", 30, 1, tick -> false);
        new Entity(UUID.randomUUID(), probed).tickThrough(FIRST_TICK + 19);

        assertEquals(every(FIRST_TICK, 1, FIRST_TICK + 19), probed.asked);
    }

    @Test
    void twoEntitiesWithTheSameBehaviorAreAskedOnDifferentTicks() {
        UUID first = new UUID(0L, 0L);
        UUID second = otherPhase(first, "fixture:drift", 10);
        Probed firstProbed = new Probed("fixture:drift", 20, 10, tick -> false);
        Probed secondProbed = new Probed("fixture:drift", 20, 10, tick -> false);
        Entity firstEntity = new Entity(first, firstProbed);
        Entity secondEntity = new Entity(second, secondProbed);
        for (long tick = FIRST_TICK; tick < FIRST_TICK + 30; tick++) {
            firstEntity.tick(tick);
            secondEntity.tick(tick);
        }

        assertEquals(3, firstProbed.asked.size());
        assertEquals(3, secondProbed.asked.size());
        for (int index = 0; index < 3; index++) {
            assertNotEquals(firstProbed.asked.get(index), secondProbed.asked.get(index));
        }
    }

    @Test
    void aHigherPriorityBehaviorPreemptsOnItsNextProbeTick() {
        UUID owner = new UUID(3L, 4L);
        long ready = FIRST_TICK + 13;
        // Runs from the first tick, holding MOVE.
        Probed hover = new Probed("fixture:hover", 30, 1, tick -> true);
        // Wants MOVE from tick `ready`, but is only asked every 5 ticks.
        Probed follow = new Probed("fixture:follow", 10, 5, tick -> tick >= ready);
        new Entity(owner, hover, follow).tickThrough(FIRST_TICK + 30);

        long expected = grid(owner, "fixture:follow", 5, ready);
        assertEquals(List.of(FIRST_TICK), hover.started);
        assertEquals(List.of(expected), follow.started);
        assertEquals(List.of(MindStopReason.PREEMPTED), hover.stopped);
        long phaseTick = grid(owner, "fixture:follow", 5, FIRST_TICK);
        assertTrue(follow.asked.stream().allMatch(tick -> (tick - phaseTick) % 5 == 0));
    }

    @Test
    void aProbeSkippedForTheCallbackCapIsAskedOnTheNextTick() {
        // Two behaviors due on the same ticks, and room for one callback a tick: the first takes it.
        UUID owner = new UUID(5L, 0L);
        while (NativeMindProbeSchedule.phase(owner, "fixture:first", 5)
                != NativeMindProbeSchedule.phase(owner, "fixture:second", 5)) {
            owner = new UUID(5L, owner.getLeastSignificantBits() + 1L);
        }
        Probed first = new Probed("fixture:first", 1, 5, tick -> false);
        Probed second = new Probed("fixture:second", 2, 5, tick -> false);
        MindExecutionPolicy oneCallback = new MindExecutionPolicy(2_000_000L, 4_000_000L, 20_000_000L, 1, 8,
                MindRunawayPolicy.DEFAULT);
        new Entity(owner, program(oneCallback, first, second), new NativeMindServerBudget())
                .tickThrough(FIRST_TICK + 19);

        long grid = grid(owner, "fixture:first", 5, FIRST_TICK);
        assertEquals(every(grid, 5, FIRST_TICK + 19), first.asked);
        assertEquals(every(grid + 1, 5, FIRST_TICK + 19), second.asked);
    }

    @Test
    void aStartDeferredForBudgetReleasesItsControlsAndRetriesOnTheNextTick() {
        UUID owner = new UUID(6L, 0L);
        long grid = grid(owner, "fixture:slow", 5, FIRST_TICK);
        // Its first ask spends the whole entity budget, so the scheduler defers its start; later asks say no.
        Probed slow = new Probed("fixture:slow", 10, 5, tick -> tick == grid) {
            @Override
            public boolean canStart(MindContext context) {
                if (asked.isEmpty()) {
                    long until = System.nanoTime() + 3_000_000L;
                    while (System.nanoTime() < until) Thread.onSpinWait();
                }
                return super.canStart(context);
            }
        };
        // Wants MOVE from the slow behavior's first ask on, and can only have it once the slow one lets it go.
        Probed after = new Probed("fixture:after", 30, 1, tick -> tick >= grid);
        MindExecutionPolicy oneMillisecond = new MindExecutionPolicy(1_000_000L, 1_000_000L, 20_000_000L, 128, 8,
                MindRunawayPolicy.DEFAULT);
        new Entity(owner, program(oneMillisecond, slow, after), new NativeMindServerBudget())
                .tickThrough(grid + 11);

        assertEquals(List.of(), slow.started);
        assertEquals(List.of(grid, grid + 1, grid + 5, grid + 10), slow.asked);
        assertEquals(List.of(grid + 1), after.started);
    }

    @Test
    void aBehaviorThatStopsIsAskedAgainOnItsGrid() {
        UUID owner = new UUID(7L, 0L);
        long grid = grid(owner, "fixture:perch", 10, FIRST_TICK);
        Probed perch = new Probed("fixture:perch", 18, 10, tick -> tick == grid);
        perch.continuing = tick -> tick < grid + 7;
        new Entity(owner, perch).tickThrough(grid + 25);

        assertEquals(List.of(grid), perch.started);
        assertEquals(List.of(MindStopReason.COMPLETED), perch.stopped);
        assertEquals(List.of(grid, grid + 10, grid + 20), perch.asked);
    }

    @Test
    void entitiesThatStopTogetherOrAreSkippedTogetherKeepTheirOwnPhases() {
        UUID first = new UUID(8L, 0L);
        UUID second = otherPhase(first, "fixture:perch", 10);
        long stopAt = FIRST_TICK + 40;
        // Every pet's perch ends on the same tick, as at nightfall.
        Probed firstPerch = new Probed("fixture:perch", 18, 10, tick -> tick < stopAt);
        Probed secondPerch = new Probed("fixture:perch", 18, 10, tick -> tick < stopAt);
        firstPerch.continuing = secondPerch.continuing = tick -> tick < stopAt;
        NativeMindServerBudget shared = new NativeMindServerBudget();
        Entity firstEntity = new Entity(first, program(MindExecutionPolicy.DEFAULT, firstPerch), shared);
        Entity secondEntity = new Entity(second, program(MindExecutionPolicy.DEFAULT, secondPerch), shared);
        long skipFrom = FIRST_TICK + 80, skipUntil = skipFrom + 10, last = skipUntil + 30;
        for (long tick = FIRST_TICK; tick <= last; tick++) {
            // The shared server budget is spent for a whole interval: every ask in it is deferred.
            if (tick >= skipFrom && tick < skipUntil) shared.record((int) tick, Long.MAX_VALUE);
            firstEntity.tick(tick);
            secondEntity.tick(tick);
        }

        assertEquals(List.of(MindStopReason.COMPLETED), firstPerch.stopped);
        assertEquals(List.of(MindStopReason.COMPLETED), secondPerch.stopped);
        assertTrue(firstPerch.asked.contains(skipUntil) && secondPerch.asked.contains(skipUntil),
                "both ask as soon as the budget allows");
        for (Probed perch : List.of(firstPerch, secondPerch)) {
            UUID owner = perch == firstPerch ? first : second;
            List<Long> afterStop = perch.asked.stream().filter(tick -> tick > stopAt && tick < skipFrom).toList();
            assertEquals(every(grid(owner, "fixture:perch", 10, stopAt + 1), 10, skipFrom - 1), afterStop);
            List<Long> afterSkip = perch.asked.stream().filter(tick -> tick > skipUntil).toList();
            assertEquals(every(grid(owner, "fixture:perch", 10, skipUntil + 1), 10, last), afterSkip);
        }
        assertNotEquals(firstPerch.asked.stream().filter(tick -> tick > skipUntil).toList(),
                secondPerch.asked.stream().filter(tick -> tick > skipUntil).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aLuaBehaviorWithoutCanContinueStillAsksCanStartEveryTickWhileRunning() {
        long startFrom = FIRST_TICK + 12;
        MindProgram program = LuaMindDefinition.validate("lua_probe.lua", """
                return ai.program { id = "fixture:lua_probe", revision = 1,
                  memories = { last = 'integer' },
                  behaviors = { ai.behavior { id = "fixture:lua_probe", probe_interval = 5,
                    can_start = function(c) c.memory:set('last', c.tick) return c.tick >= %d end,
                    tick = function(c) end } } }
                """.formatted(startFrom)).instantiate();
        MindMemoryKey<Long> last = (MindMemoryKey<Long>) program.schema().memory("fixture:last");
        UUID owner = new UUID(9L, 0L);
        Entity entity = new Entity(owner, program, new NativeMindServerBudget());
        long started = grid(owner, "fixture:lua_probe", 5, startFrom);
        List<Long> asked = new ArrayList<>();
        for (long tick = FIRST_TICK; tick <= started + 10; tick++) {
            entity.tick(tick);
            Long lastAsk = entity.store.view(tick).get(last).orElse(null);
            if (lastAsk != null && lastAsk == tick) asked.add(tick);
        }

        List<Long> expected = new ArrayList<>(every(grid(owner, "fixture:lua_probe", 5, FIRST_TICK), 5, started - 1));
        expected.addAll(every(started, 1, started + 10));
        assertEquals(expected, asked);
    }

    @Test
    void entitiesCreatedTogetherSenseOnTheirOwnPhases() {
        UUID first = new UUID(10L, 0L);
        UUID second = otherPhase(first, "fixture:sense", 10);
        List<Long> firstSensed = new ArrayList<>(), secondSensed = new ArrayList<>();
        Entity firstEntity = new Entity(first, sensing(firstSensed), new NativeMindServerBudget());
        Entity secondEntity = new Entity(second, sensing(secondSensed), new NativeMindServerBudget());
        for (long tick = FIRST_TICK; tick < FIRST_TICK + 30; tick++) {
            firstEntity.tick(tick);
            secondEntity.tick(tick);
        }

        assertEquals(every(grid(first, "fixture:sense", 10, FIRST_TICK), 10, FIRST_TICK + 29), firstSensed);
        assertEquals(every(grid(second, "fixture:sense", 10, FIRST_TICK), 10, FIRST_TICK + 29), secondSensed);
    }

    /** A program with one sensor every 10 ticks that records the ticks it senses on. */
    private static MindProgram sensing(List<Long> sensed) {
        return MindProgram.builder("fixture:sensing", 1L).sensor(new MindSensor() {
            @Override
            public String identifier() {
                return "fixture:sense";
            }

            @Override
            public int intervalTicks() {
                return 10;
            }

            @Override
            public void sense(MindContext context) {
                sensed.add(context.gameTick());
            }
        }).build();
    }
}
