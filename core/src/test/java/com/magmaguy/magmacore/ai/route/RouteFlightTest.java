package com.magmaguy.magmacore.ai.route;

import com.magmaguy.magmacore.ai.MindActionRequester;
import com.magmaguy.magmacore.ai.MindActuator;
import com.magmaguy.magmacore.ai.MindContext;
import com.magmaguy.magmacore.ai.MindMemoryView;
import com.magmaguy.magmacore.ai.MindPerception;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RouteFlightTest {
    /** A body that flies wherever it is steered, at most {@link MindActuator#MAX_FLIGHT_SPEED} per tick. */
    private static MindContext body(Vector position, List<Vector> steered) {
        LivingEntity entity = mock(LivingEntity.class);
        when(entity.getLocation()).thenAnswer(invocation -> position.toLocation(null));
        MindActuator actuator = mock(MindActuator.class);
        when(actuator.steerFlight(any())).thenAnswer(invocation -> {
            Vector velocity = invocation.getArgument(0, Vector.class).clone();
            steered.add(velocity.clone());
            if (velocity.length() > MindActuator.MAX_FLIGHT_SPEED) {
                velocity.normalize().multiply(MindActuator.MAX_FLIGHT_SPEED);
            }
            position.add(velocity);
            return true;
        });
        return new MindContext(entity, 1L, 1L, mock(MindMemoryView.class), mock(MindPerception.class), actuator,
                mock(MindActionRequester.class));
    }

    @Test
    void aRouteFasterThanAnyBodyStillDeliversItsBodyToTheEnd() {
        CurvedRoute route = new CurvedRoute(List.of(new Vector(0D, 64D, 0D), new Vector(100D, 64D, 0D)));
        // 200 blocks per second is 10 blocks per tick, more than the cap; the acceleration reaches it at once.
        RouteFlight flight = new RouteFlight(route, 200D, 1.0E6D);
        Vector position = new Vector(0D, 64D, 0D);
        List<Vector> steered = new ArrayList<>();
        MindContext context = body(position, steered);

        int ticks = 0;
        while (flight.tick(context) == RouteFlight.Status.FLYING) assertTrue(++ticks < 1_000, "the route never ends");

        assertEquals(0D, position.distance(route.at(route.length())), 1.0E-6D, "arrived where the route ends");
        for (Vector velocity : steered) {
            assertTrue(velocity.length() <= MindActuator.MAX_FLIGHT_SPEED + 1.0E-9D,
                    "asked for " + velocity.length() + " blocks per tick");
        }
    }
}
