package com.magmaguy.magmacore.ai.route;

import org.bukkit.util.Vector;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Centripetal Catmull-Rom through authored points, sampled by distance for predictable speed. */
public final class CurvedRoute {
    private final List<Vector> samples;
    private final double[] distances;

    public CurvedRoute(List<Vector> points) {
        if (points.size() < 2 || points.size() > 256)
            throw new IllegalArgumentException("A route needs 2 to 256 waypoints");
        List<Vector> nodes = points.stream().map(Vector::clone).toList();
        double chords = 0;
        for (int i = 0; i < nodes.size(); i++) {
            Vector p = nodes.get(i);
            if (!Double.isFinite(p.getX()) || !Double.isFinite(p.getY()) || !Double.isFinite(p.getZ()))
                throw new IllegalArgumentException("Waypoint coordinates must be finite");
            if (i > 0) {
                double length = p.distance(nodes.get(i - 1));
                if (length < .05 || length > 2048)
                    throw new IllegalArgumentException("Consecutive waypoints must be 0.05 to 2048 blocks apart");
                chords += length;
            }
        }
        if (chords > 16384) throw new IllegalArgumentException("Route exceeds 16384 blocks");
        ArrayList<Vector> result = new ArrayList<>();
        result.add(nodes.getFirst().clone());
        for (int i = 0; i < nodes.size() - 1; i++) {
            Vector b = nodes.get(i), c = nodes.get(i + 1);
            Vector a = i == 0 ? b.clone().multiply(2).subtract(c) : nodes.get(i - 1);
            Vector d = i + 2 == nodes.size() ? c.clone().multiply(2).subtract(b) : nodes.get(i + 2);
            int steps = Math.max(16, (int) Math.ceil(b.distance(c) * 8));
            for (int step = 1; step <= steps; step++)
                result.add(interpolate(a, b, c, d, (double) step / steps));
        }
        samples = List.copyOf(result);
        distances = new double[samples.size()];
        for (int i = 1; i < distances.length; i++)
            distances[i] = distances[i - 1] + samples.get(i).distance(samples.get(i - 1));
    }

    public double length() { return distances[distances.length - 1]; }

    public Vector at(double distance) {
        if (!Double.isFinite(distance)) throw new IllegalArgumentException("Distance must be finite");
        if (distance <= 0) return samples.getFirst().clone();
        if (distance >= length()) return samples.getLast().clone();
        int index = Arrays.binarySearch(distances, distance);
        if (index >= 0) return samples.get(index).clone();
        int next = -index - 1;
        double fraction = (distance - distances[next - 1]) / (distances[next] - distances[next - 1]);
        return mix(samples.get(next - 1), samples.get(next), fraction);
    }

    private static Vector interpolate(Vector a, Vector b, Vector c, Vector d, double fraction) {
        double t0 = 0, t1 = Math.sqrt(a.distance(b)), t2 = t1 + Math.sqrt(b.distance(c));
        double t3 = t2 + Math.sqrt(c.distance(d)), t = t1 + fraction * (t2 - t1);
        Vector a1 = mix(a, b, (t - t0) / (t1 - t0));
        Vector a2 = mix(b, c, (t - t1) / (t2 - t1));
        Vector a3 = mix(c, d, (t - t2) / (t3 - t2));
        Vector b1 = mix(a1, a2, (t - t0) / (t2 - t0));
        Vector b2 = mix(a2, a3, (t - t1) / (t3 - t1));
        return mix(b1, b2, (t - t1) / (t2 - t1));
    }

    private static Vector mix(Vector a, Vector b, double fraction) {
        return a.clone().multiply(1 - fraction).add(b.clone().multiply(fraction));
    }
}
