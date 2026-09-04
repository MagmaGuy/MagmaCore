package com.magmaguy.magmacore.scripting;

import com.magmaguy.magmacore.ai.MindActuator;
import com.magmaguy.magmacore.ai.MindContext;
import com.magmaguy.magmacore.ai.MindMemoryStore;
import com.magmaguy.magmacore.ai.MindNavigationStatus;
import com.magmaguy.magmacore.ai.MindPerception;
import com.magmaguy.magmacore.ai.MindProgram;
import com.magmaguy.magmacore.ai.MindRunawayException;
import com.magmaguy.magmacore.ai.MindSchema;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class LuaMindSchedulingBudgetContractTest {
    @Test
    void softCallbackBudgetDoesNotBecomeTheLuaRunawayLimit() {
        MindProgram program = LuaMindDefinition.validate("soft-budget.lua", """
                return ai.program {
                  id = "test:soft_budget",
                  revision = 1,
                  budget = {
                    callback_micros = 1,
                    entity_micros = 2000,
                    server_micros = 20000
                  },
                  runaway = {
                    cpu_micros = 50000,
                    max_instructions = 50000
                  },
                  behaviors = {
                    ai.behavior {
                      id = "test:finite_work",
                      tick = function(context)
                        local total = 0
                        for i = 1, 5000 do total = total + i end
                      end
                    }
                  }
                }
                """).instantiate();

        assertEquals(1_000L, program.executionPolicy().maxCallbackNanos());
        assertEquals(
                50_000_000L,
                program.executionPolicy().runawayPolicy().maxCpuNanosPerCallback());
        assertEquals(
                50_000L,
                program.executionPolicy().runawayPolicy().maxInstructionsPerCallback());
        assertDoesNotThrow(() -> program.behaviors().getFirst().tick(context()));
    }

    @Test
    void hardLuaRunawayLimitHasASeparateFailureType() {
        MindProgram program = LuaMindDefinition.validate("runaway.lua", """
                return ai.program {
                  id = "test:runaway",
                  revision = 1,
                  runaway = {
                    cpu_micros = 1000,
                    max_instructions = 2000
                  },
                  behaviors = {
                    ai.behavior {
                      id = "test:never_returns",
                      tick = function(context)
                        local total = 0
                        for i = 1, 1000000 do total = total + i end
                      end
                    }
                  }
                }
                """).instantiate();

        MindRunawayException failure = assertTimeout(
                Duration.ofSeconds(2),
                () -> assertThrows(
                        MindRunawayException.class,
                        () -> program.behaviors().getFirst().tick(context())));

        assertTrue(failure.getMessage().contains("budget exceeded"));
    }

    private static MindContext context() {
        MindMemoryStore memory = new MindMemoryStore(MindSchema.empty());
        return new MindContext(
                mock(LivingEntity.class),
                1L,
                1L,
                memory.view(1L),
                new EmptyPerception(),
                new NoOpActuator());
    }

    private static final class EmptyPerception implements MindPerception {
        @Override
        public Optional<LivingEntity> currentTarget() {
            return Optional.empty();
        }

        @Override
        public Optional<Player> nearestPlayer(double maxDistance) {
            return Optional.empty();
        }

        @Override
        public boolean lineOfSight(LivingEntity target) {
            return false;
        }

        @Override
        public MindNavigationStatus navigation() {
            return MindNavigationStatus.idle();
        }
    }

    private static final class NoOpActuator implements MindActuator {
        @Override
        public boolean moveTo(Location destination, double speedModifier) {
            return false;
        }

        @Override
        public void stopMoving() {
        }

        @Override
        public void lookAt(Location destination) {
        }

        @Override
        public void jump() {
        }

        @Override
        public void setTarget(LivingEntity target) {
        }

        @Override
        public void clearTarget() {
        }

        @Override
        public void attack(LivingEntity target) {
        }

        @Override
        public void stopAll() {
        }
    }
}
