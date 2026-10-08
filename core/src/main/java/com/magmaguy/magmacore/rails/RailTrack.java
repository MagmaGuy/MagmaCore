package com.magmaguy.magmacore.rails;

import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Rail;

/**
 * Follows connected rails one cell at a time at constant speed. Inside a cell the path is the straight
 * line between the midpoints of the two edges its shape connects, so curves cut their corner and
 * slopes climb one block across the cell.
 */
final class RailTrack {
    /** The rail shape at a cell, or null for no rail. Callers report unloaded cells as null. */
    interface Rails {
        Rail.Shape shapeAt(int x, int y, int z);
    }

    private final Rails rails;
    private int x, y, z;
    private Rail.Shape shape;
    private BlockFace entry, exit;
    private double length, progress;

    private RailTrack(Rails rails, int x, int y, int z, Rail.Shape shape, BlockFace entry, BlockFace exit) {
        this.rails = rails;
        enter(x, y, z, shape, entry, exit);
    }

    /**
     * Starts on the rail in the cell containing the position, heading out of whichever of its two ends
     * lies closest to the horizontal look direction. Returns null when that cell holds no rail.
     */
    static RailTrack start(Rails rails, double px, double py, double pz, double lookX, double lookZ) {
        int x = (int) Math.floor(px), y = (int) Math.floor(py), z = (int) Math.floor(pz);
        Rail.Shape shape = rails.shapeAt(x, y, z);
        if (shape == null) return null;
        BlockFace[] ends = ends(shape);
        boolean firstAhead = ends[0].getModX() * lookX + ends[0].getModZ() * lookZ
                >= ends[1].getModX() * lookX + ends[1].getModZ() * lookZ;
        RailTrack track = new RailTrack(rails, x, y, z, shape,
                firstAhead ? ends[1] : ends[0], firstAhead ? ends[0] : ends[1]);
        // Begin where the player stands along the cell instead of snapping to its entry edge.
        double ex = edgeX(track.entry), ez = edgeZ(track.entry);
        double dx = edgeX(track.exit) - ex, dz = edgeZ(track.exit) - ez;
        double along = ((px - x - ex) * dx + (pz - z - ez) * dz) / (dx * dx + dz * dz);
        track.progress = Math.max(0, Math.min(1, along)) * track.length;
        return track;
    }

    /** Moves along the track. Returns false at the end of the track, stopping on the last rail's far edge. */
    boolean advance(double distance) {
        progress += distance;
        while (progress > length) {
            double remaining = progress - length;
            if (!stepToNextCell()) {
                progress = length;
                return false;
            }
            progress = remaining;
        }
        return true;
    }

    double x() { return x + lerp(edgeX(entry), edgeX(exit)); }
    double y() { return y + lerp(height(shape, entry), height(shape, exit)); }
    double z() { return z + lerp(edgeZ(entry), edgeZ(exit)); }
    /** Horizontal travel direction in this cell. */
    double directionX() { return edgeX(exit) - edgeX(entry); }
    double directionZ() { return edgeZ(exit) - edgeZ(entry); }

    private boolean stepToNextCell() {
        int edgeHeight = y + (int) height(shape, exit);
        BlockFace arrival = exit.getOppositeFace();
        int nextX = x + exit.getModX(), nextZ = z + exit.getModZ();
        // Same-level rails and rising slopes start at the edge height; a falling slope sits one block lower.
        for (int nextY = edgeHeight; nextY >= edgeHeight - 1; nextY--) {
            Rail.Shape next = rails.shapeAt(nextX, nextY, nextZ);
            if (next == null) continue;
            BlockFace[] ends = ends(next);
            if (ends[0] != arrival && ends[1] != arrival) continue;
            if (nextY + (int) height(next, arrival) != edgeHeight) continue;
            enter(nextX, nextY, nextZ, next, arrival, ends[0] == arrival ? ends[1] : ends[0]);
            return true;
        }
        return false;
    }

    private void enter(int x, int y, int z, Rail.Shape shape, BlockFace entry, BlockFace exit) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.shape = shape;
        this.entry = entry;
        this.exit = exit;
        double dx = edgeX(exit) - edgeX(entry), dy = height(shape, exit) - height(shape, entry), dz = edgeZ(exit) - edgeZ(entry);
        length = Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double lerp(double from, double to) {
        return from + (to - from) * (progress / length);
    }

    private static double edgeX(BlockFace side) { return 0.5 + side.getModX() * 0.5; }
    private static double edgeZ(BlockFace side) { return 0.5 + side.getModZ() * 0.5; }

    /** The raised end of a slope is one block above the cell; every other edge is level with it. */
    private static double height(Rail.Shape shape, BlockFace side) {
        return switch (shape) {
            case ASCENDING_EAST -> side == BlockFace.EAST ? 1 : 0;
            case ASCENDING_WEST -> side == BlockFace.WEST ? 1 : 0;
            case ASCENDING_NORTH -> side == BlockFace.NORTH ? 1 : 0;
            case ASCENDING_SOUTH -> side == BlockFace.SOUTH ? 1 : 0;
            default -> 0;
        };
    }

    private static BlockFace[] ends(Rail.Shape shape) {
        return switch (shape) {
            case NORTH_SOUTH, ASCENDING_NORTH, ASCENDING_SOUTH -> new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH};
            case EAST_WEST, ASCENDING_EAST, ASCENDING_WEST -> new BlockFace[]{BlockFace.EAST, BlockFace.WEST};
            case SOUTH_EAST -> new BlockFace[]{BlockFace.SOUTH, BlockFace.EAST};
            case SOUTH_WEST -> new BlockFace[]{BlockFace.SOUTH, BlockFace.WEST};
            case NORTH_WEST -> new BlockFace[]{BlockFace.NORTH, BlockFace.WEST};
            case NORTH_EAST -> new BlockFace[]{BlockFace.NORTH, BlockFace.EAST};
        };
    }
}
