package com.magmaguy.easyminecraftgoals.v26.mind;

import com.magmaguy.magmacore.ai.MindBehavior;
import com.magmaguy.magmacore.ai.MindContext;
import com.magmaguy.magmacore.ai.MindControl;
import com.magmaguy.magmacore.ai.MindMemoryStore;
import com.magmaguy.magmacore.ai.MindProgram;
import com.magmaguy.magmacore.ai.MindStopReason;
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

/** A behavior's probe interval, through the real Brain that offers every stopped behavior a start each tick. */
class NativeMindBehaviorProbeTest {
    private static final long FIRST_TICK = 1_000L;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** A behavior that records the ticks its canStart runs on, and starts when {@code allowed} says so. */
    private static final class Probed implements MindBehavior {
        final String identifier;
        final int priority;
        final int probeInterval;
        final LongPredicate allowed;
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
            return true;
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

        Entity(UUID owner, MindBehavior... behaviors) {
            MindProgram.Builder builder = MindProgram.builder("fixture:probe", 1L);
            for (MindBehavior behavior : behaviors) builder.behavior(behavior);
            MindProgram program = builder.build();
            NativeMindSession session = mock(NativeMindSession.class);
            when(session.logicalOwner()).thenReturn(owner);
            NativeMindBody body = mock(NativeMindBody.class);
            when(body.mob()).thenReturn(mock(Mob.class));
            when(body.entity()).thenReturn(mock(LivingEntity.class));
            when(level.getServer()).thenReturn(server);
            runtime = new NativeMindProgramRuntime(session, body, program, new MindMemoryStore(program.schema()), 1L,
                    new NativeMindServerBudget());
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

    private static List<Long> every(long first, long step, long last) {
        List<Long> ticks = new ArrayList<>();
        for (long tick = first; tick <= last; tick += step) ticks.add(tick);
        return ticks;
    }

    @Test
    void aBehaviorProbedEveryFiveTicksIsAskedEveryFifthTick() {
        UUID owner = new UUID(1L, 2L);
        Probed probed = new Probed("fixture:drift", 20, 5, tick -> false);
        new Entity(owner, probed).tickThrough(FIRST_TICK + 39);

        long first = FIRST_TICK + NativeMindProbeSchedule.phase(owner, "fixture:drift", 5);
        assertEquals(every(first, 5, FIRST_TICK + 39), probed.asked);
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
        UUID second = new UUID(0L, 1L);
        while (NativeMindProbeSchedule.phase(second, "fixture:drift", 10)
                == NativeMindProbeSchedule.phase(first, "fixture:drift", 10)) {
            second = new UUID(0L, second.getLeastSignificantBits() + 1L);
        }
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

        long phaseTick = FIRST_TICK + NativeMindProbeSchedule.phase(owner, "fixture:follow", 5);
        long expected = phaseTick;
        while (expected < ready) expected += 5;
        assertEquals(List.of(FIRST_TICK), hover.started);
        assertEquals(List.of(expected), follow.started);
        assertEquals(List.of(MindStopReason.PREEMPTED), hover.stopped);
        assertTrue(follow.asked.stream().allMatch(tick -> (tick - phaseTick) % 5 == 0));
    }
}
