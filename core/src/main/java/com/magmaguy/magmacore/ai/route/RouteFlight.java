package com.magmaguy.magmacore.ai.route;

import com.magmaguy.magmacore.ai.MindActuator;
import com.magmaguy.magmacore.ai.MindContext;
import org.bukkit.util.Vector;

/**
 * Advances a point along the authored curve each tick and steers the body to it. The author owns the route's
 * clearance. The route reports arrival when that point reaches the end, which assumes the body keeps up, so the
 * speed never exceeds {@link MindActuator#MAX_FLIGHT_SPEED}.
 */
public final class RouteFlight {
    public enum Status { FLYING, ARRIVED, UNSUPPORTED }
    private final CurvedRoute route;
    private final double maximumSpeed;
    private final double acceleration;
    private double distance;
    private double speed;

    public RouteFlight(CurvedRoute route, double blocksPerSecond, double accelerationPerSecond) {
        if (!Double.isFinite(blocksPerSecond) || blocksPerSecond <= 0
                || !Double.isFinite(accelerationPerSecond) || accelerationPerSecond <= 0)
            throw new IllegalArgumentException("Flight speed and acceleration must be finite and positive");
        this.route = route;
        maximumSpeed = Math.min(MindActuator.MAX_FLIGHT_SPEED, blocksPerSecond / 20D);
        acceleration = accelerationPerSecond / 400D;
    }

    public double distance() { return distance; }

    public Status tick(MindContext context) {
        if (distance >= route.length()) {
            context.actuator().stopMoving();
            return Status.ARRIVED;
        }
        speed = Math.min(maximumSpeed, speed + acceleration);
        distance = Math.min(route.length(), distance + speed);
        Vector velocity = route.at(distance).subtract(context.entity().getLocation().toVector());
        return context.actuator().steerFlight(velocity) ? Status.FLYING : Status.UNSUPPORTED;
    }
}
