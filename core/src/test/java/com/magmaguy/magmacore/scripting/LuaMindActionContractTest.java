package com.magmaguy.magmacore.scripting;

import com.magmaguy.magmacore.ai.MindControl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuaMindActionContractTest {
    @Test
    void compilesActionLeaseAndBudgetFromLua() {
        LuaMindDefinition definition = LuaMindDefinition.validate("action-fixture.lua", """
                return ai.program {
                  id = "test:action_fixture",
                  revision = 1,
                  budget = { max_action_requests = 3 },
                  behaviors = {
                    ai.behavior {
                      id = "test:request_action",
                      controls = { ai.controls.action },
                      tick = function(context)
                        local navigation = context.perception:navigation()
                        if navigation.consecutive_stuck_ticks > 5 then
                          context.actions:request("test:dig", {
                            target = context.entity,
                            position = navigation.destination,
                            count = 1
                          })
                        end
                      end
                    }
                  }
                }
                """);

        var program = definition.instantiate();
        assertEquals(3, program.executionPolicy().maxActionRequestsPerEntityTick());
        assertTrue(program.behaviors().getFirst().controls().contains(MindControl.ACTION));
    }
}
