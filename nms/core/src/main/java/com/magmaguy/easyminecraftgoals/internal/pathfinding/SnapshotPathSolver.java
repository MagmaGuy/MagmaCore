package com.magmaguy.easyminecraftgoals.internal.pathfinding;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Pure A* search over immutable chunk snapshots. No Bukkit world state is read here. */
final class SnapshotPathSolver {
    private static final int[][] HORIZONTAL_DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };
    private static final int[] GROUND_Y_OFFSETS = {0, 1, -1, -2, -3};

    private SnapshotPathSolver() {
    }

    static Result solve(
            Terrain terrain,
            Point requestedStart,
            Point requestedGoal,
            BodyProfile body,
            int maximumVisitedNodes) {
        Point start = nearestValid(terrain, requestedStart, body, 2, 8);
        Point goal = nearestValid(terrain, requestedGoal, body, 8, 48);
        if (start == null || goal == null) return Result.noPath(false);
        if (start.equals(goal)) return new Result(List.of(start), false);

        PriorityQueue<SearchNode> open = new PriorityQueue<>(Comparator.comparingDouble(SearchNode::score));
        Map<Point, Double> distances = new HashMap<>();
        Map<Point, Point> previous = new HashMap<>();
        Set<Point> closed = new HashSet<>();
        distances.put(start, 0D);
        open.add(new SearchNode(start, heuristic(start, goal)));

        int visited = 0;
        while (!open.isEmpty()) {
            SearchNode next = open.poll();
            Point current = next.point();
            if (!closed.add(current)) continue;
            if (++visited > maximumVisitedNodes) return Result.noPath(true);
            if (current.equals(goal)) return new Result(reconstruct(previous, current), false);

            for (Point neighbour : neighbours(terrain, current, body)) {
                if (closed.contains(neighbour)) continue;
                double candidateDistance = distances.get(current) + movementCost(terrain, current, neighbour, body);
                if (candidateDistance + 1.0E-9D >= distances.getOrDefault(neighbour, Double.POSITIVE_INFINITY)) {
                    continue;
                }
                previous.put(neighbour, current);
                distances.put(neighbour, candidateDistance);
                open.add(new SearchNode(neighbour, candidateDistance + heuristic(neighbour, goal)));
            }
        }
        return Result.noPath(false);
    }

    private static List<Point> neighbours(Terrain terrain, Point current, BodyProfile body) {
        return switch (body.mode()) {
            case GROUND -> groundNeighbours(terrain, current, body);
            case FLYING, AQUATIC -> volumeNeighbours(terrain, current, body);
        };
    }

    private static List<Point> groundNeighbours(Terrain terrain, Point current, BodyProfile body) {
        List<Point> neighbours = new ArrayList<>(10);
        for (int[] direction : HORIZONTAL_DIRECTIONS) {
            int dx = direction[0];
            int dz = direction[1];
            Point candidate = groundTransition(terrain, current, dx, dz, body);
            if (candidate == null) continue;
            if (dx != 0 && dz != 0
                    && (groundTransition(terrain, current, dx, 0, body) == null
                    || groundTransition(terrain, current, 0, dz, body) == null)) {
                continue;
            }
            neighbours.add(candidate);
        }

        Material feet = terrain.blockType(current.x(), current.y(), current.z());
        if (isWater(feet)) {
            Point above = current.add(0, 1, 0);
            Point below = current.add(0, -1, 0);
            if (isValid(terrain, above, body)) neighbours.add(above);
            if (isValid(terrain, below, body)) neighbours.add(below);
        }
        return neighbours;
    }

    private static Point groundTransition(Terrain terrain, Point current, int dx, int dz, BodyProfile body) {
        for (int offset : GROUND_Y_OFFSETS) {
            Point candidate = current.add(dx, offset, dz);
            if (isValid(terrain, candidate, body)) return candidate;
        }
        return null;
    }

    private static List<Point> volumeNeighbours(Terrain terrain, Point current, BodyProfile body) {
        List<Point> neighbours = new ArrayList<>(26);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    Point candidate = current.add(dx, dy, dz);
                    if (isValid(terrain, candidate, body)) neighbours.add(candidate);
                }
            }
        }
        return neighbours;
    }

    private static Point nearestValid(
            Terrain terrain,
            Point requested,
            BodyProfile body,
            int horizontalRadius,
            int verticalRadius) {
        Point best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
            for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
                for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
                    Point candidate = requested.add(dx, dy, dz);
                    double distance = dx * dx + dz * dz + dy * dy * 0.25D;
                    if (distance >= bestDistance || !isValid(terrain, candidate, body)) continue;
                    best = candidate;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private static boolean isValid(Terrain terrain, Point point, BodyProfile body) {
        if (point.y() < terrain.minimumY() || point.y() + body.heightBlocks() > terrain.maximumY()) return false;
        return switch (body.mode()) {
            case GROUND -> hasGroundClearance(terrain, point, body);
            case FLYING -> hasVolumeClearance(terrain, point, body, false);
            case AQUATIC -> hasVolumeClearance(terrain, point, body, true);
        };
    }

    private static boolean hasGroundClearance(Terrain terrain, Point point, BodyProfile body) {
        boolean inWater = false;
        for (int dx = -body.horizontalRadius(); dx <= body.horizontalRadius(); dx++) {
            for (int dz = -body.horizontalRadius(); dz <= body.horizontalRadius(); dz++) {
                Material feet = terrain.blockType(point.x() + dx, point.y(), point.z() + dz);
                if (!isPassable(feet)) return false;
                inWater |= isWater(feet);
                for (int height = 1; height < body.heightBlocks(); height++) {
                    if (!isPassable(terrain.blockType(point.x() + dx, point.y() + height, point.z() + dz))) {
                        return false;
                    }
                }
                Material support = terrain.blockType(point.x() + dx, point.y() - 1, point.z() + dz);
                if (!isSupport(support) && !isWater(feet)) return false;
            }
        }
        return inWater || !isHazard(terrain.blockType(point.x(), point.y() - 1, point.z()));
    }

    private static boolean hasVolumeClearance(
            Terrain terrain,
            Point point,
            BodyProfile body,
            boolean requireWater) {
        for (int dx = -body.horizontalRadius(); dx <= body.horizontalRadius(); dx++) {
            for (int dz = -body.horizontalRadius(); dz <= body.horizontalRadius(); dz++) {
                for (int height = 0; height < body.heightBlocks(); height++) {
                    Material material = terrain.blockType(point.x() + dx, point.y() + height, point.z() + dz);
                    if (requireWater ? !isWater(material) : !isPassable(material)) return false;
                }
            }
        }
        return true;
    }

    private static double movementCost(Terrain terrain, Point from, Point to, BodyProfile body) {
        int dx = Math.abs(to.x() - from.x());
        int dy = Math.abs(to.y() - from.y());
        int dz = Math.abs(to.z() - from.z());
        double cost = Math.sqrt(dx * dx + dy * dy + dz * dz);
        Material feet = terrain.blockType(to.x(), to.y(), to.z());
        if (isWater(feet) && body.mode() == MovementMode.GROUND) cost += 3D;
        if (isWoodenDoor(feet)) cost += 2D;
        if (to.y() > from.y()) cost += 0.35D;
        return cost;
    }

    private static double heuristic(Point from, Point to) {
        int dx = to.x() - from.x();
        int dy = to.y() - from.y();
        int dz = to.z() - from.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static List<Point> reconstruct(Map<Point, Point> previous, Point end) {
        List<Point> reversed = new ArrayList<>();
        Point current = end;
        while (current != null) {
            reversed.add(current);
            current = previous.get(current);
        }
        java.util.Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    private static boolean isPassable(Material material) {
        return material != null && !isHazard(material)
                && (!material.isSolid() || isWoodenDoor(material) || isWater(material));
    }

    private static boolean isSupport(Material material) {
        if (material == null || !material.isSolid() || isHazard(material)) return false;
        String name = material.name();
        return !name.endsWith("_FENCE")
                && !name.endsWith("_WALL")
                && !name.endsWith("_FENCE_GATE")
                && !name.endsWith("_DOOR")
                && !name.endsWith("_TRAPDOOR")
                && !name.endsWith("_CAULDRON");
    }

    private static boolean isWater(Material material) {
        return material == Material.WATER || material != null && material.name().equals("BUBBLE_COLUMN");
    }

    private static boolean isWoodenDoor(Material material) {
        return material != null && material.name().endsWith("_DOOR") && material != Material.IRON_DOOR;
    }

    private static boolean isHazard(Material material) {
        if (material == null) return true;
        String name = material.name();
        return material == Material.LAVA
                || name.equals("FIRE")
                || name.equals("SOUL_FIRE")
                || name.equals("CACTUS")
                || name.equals("SWEET_BERRY_BUSH")
                || name.equals("POWDER_SNOW")
                || name.equals("WITHER_ROSE")
                || name.equals("MAGMA_BLOCK")
                || name.endsWith("CAMPFIRE");
    }

    interface Terrain {
        Material blockType(int x, int y, int z);

        int minimumY();

        int maximumY();
    }

    enum MovementMode {
        GROUND,
        FLYING,
        AQUATIC
    }

    record BodyProfile(MovementMode mode, int horizontalRadius, int heightBlocks) {
        BodyProfile {
            if (horizontalRadius < 0) throw new IllegalArgumentException("horizontalRadius must not be negative");
            if (heightBlocks < 1) throw new IllegalArgumentException("heightBlocks must be positive");
        }
    }

    record Point(int x, int y, int z) {
        Point add(int dx, int dy, int dz) {
            return new Point(x + dx, y + dy, z + dz);
        }
    }

    record Result(List<Point> points, boolean exhaustedNodeBudget) {
        Result {
            points = List.copyOf(points);
        }

        static Result noPath(boolean exhaustedNodeBudget) {
            return new Result(List.of(), exhaustedNodeBudget);
        }

        boolean found() {
            return !points.isEmpty();
        }
    }

    private record SearchNode(Point point, double score) {
    }
}
