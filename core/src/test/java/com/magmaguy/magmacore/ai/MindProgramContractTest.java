package com.magmaguy.magmacore.ai;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class MindProgramContractTest {
    @Test
    void immutableControlSetDoesNotRequireNullLookupSupport() {
        MindBehavior behavior = new MindBehavior() {
            @Override
            public String identifier() {
                return "test:immutable_controls";
            }

            @Override
            public Set<MindControl> controls() {
                return Set.of(MindControl.MOVE, MindControl.LOOK);
            }

            @Override
            public boolean canStart(MindContext context) {
                return true;
            }

            @Override
            public void tick(MindContext context) {
            }
        };

        assertDoesNotThrow(() -> MindProgram.builder("test:program", 1L)
                .behavior(behavior)
                .build());
    }
}
