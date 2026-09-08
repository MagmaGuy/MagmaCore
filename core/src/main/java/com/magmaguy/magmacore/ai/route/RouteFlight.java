package com.magmaguy.magmacore.ai.route;

import com.magmaguy.magmacore.ai.MindContext;
import org.bukkit.util.Vector;

/** Per-journey curve follower. Native movement supplies collision and passenger tracking. */
public final class RouteFlight {
    public enum Status { FLYING, ARRIVED, BLOCKED, UNSUPPORTED }
    private final CurvedRoute route;
    private final double maximumSpeed;
    private final double acceleration;
    private final double[] speedLimits;
    private Vector checkpoint;
    private double distance;
    private double speed;
    private int ticks;

    public RouteFlight(CurvedRoute route, double blocksPerSecond, double accelerationPerSecond) {
        if (!Double.isFinite(blocksPerSecond) || blocksPerSecond < 1 || blocksPerSecond > 32
                || !Double.isFinite(accelerationPerSecond) || accelerationPerSecond < 1 || accelerationPerSecond > 32)
            throw new IllegalArgumentException("Flight speed and acceleration must be between 1 and 32");
        this.route = route;
        maximumSpeed = blocksPerSecond / 20D;
        acceleration = accelerationPerSecond / 400D;
        speedLimits = speedProfile();
    }

    public double distance() { return distance; }

    public Status tick(MindContext context) {
        Vector position = context.entity().getLocation().toVector();
        if (checkpoint == null) checkpoint = position.clone();
        if (++ticks % 100 == 0) {
            if (checkpoint.distanceSquared(position) < .04) return stop(context, Status.BLOCKED);
            checkpoint = position.clone();
        }
        if (position.distanceSquared(route.at(distance)) > .25)
            return stop(context, Status.BLOCKED);
        double remaining = route.length() - distance;
        if (remaining < .05 && position.distanceSquared(route.at(route.length())) < .04)
            return stop(context, Status.ARRIVED);
        int index = Math.min(speedLimits.length - 1, (int) (distance / .5));
        speed = Math.min(speed + acceleration * .5, Math.min(speedLimits[index],
                Math.sqrt(acceleration * Math.max(.01, remaining))));
        distance = Math.min(route.length(), distance + speed);
        // Native move(), rather than teleportation, applies this displacement with collision and
        // passenger tracking. Following the sampled curve directly prevents accumulated turn drift.
        Vector velocity = route.at(distance).subtract(position);
        return context.actuator().steerFlight(velocity) ? Status.FLYING : Status.UNSUPPORTED;
    }

    private double[] speedProfile() {
        int count = (int) Math.ceil(route.length() / .5) + 1;
        double[] limits = new double[count];
        for (int i = 0; i < count - 1; i++) {
            double d = i * .5;
            Vector a = route.at(Math.max(0, d - .5));
            Vector b = route.at(d);
            Vector c = route.at(Math.min(route.length(), d + .5));
            Vector incoming = b.clone().subtract(a), outgoing = c.clone().subtract(b);
            double curvature = 0;
            if (incoming.lengthSquared() > 1E-8 && outgoing.lengthSquared() > 1E-8) {
                double span = (incoming.length() + outgoing.length()) / 2;
                curvature = incoming.normalize().angle(outgoing.normalize()) / span;
            }
            // Reserve half the acceleration for speeding up/braking and half for turning.
            limits[i] = curvature < 1E-6 ? maximumSpeed : Math.min(maximumSpeed, Math.sqrt(acceleration * .5 / curvature));
        }
        limits[count - 1] = 0;
        for (int i = count - 2; i >= 0; i--) {
            double segment = Math.min(.5, route.length() - i * .5);
            limits[i] = Math.min(limits[i], Math.sqrt(limits[i + 1] * limits[i + 1] + acceleration * segment));
        }
        return limits;
    }

    private Status stop(MindContext context, Status result) {
        context.actuator().stopMoving();
        return result;
    }
}
