package com.magmaguy.magmacore;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class DrawLine {
    private DrawLine() {
    }

    public static LineData drawLine(Location start, Location end, float width, Material material, int tickDuration) {
        float height = (float) start.distance(end);
        if (height <= 0f) return null;

        // Width/depth and height
        final float sx = width;
        final float sy = height;
        final float sz = width;

        // Rotation: align local +Y with (end - start)
        Vector dir = end.toVector().subtract(start.toVector()).normalize();
        Vector3f target = new Vector3f((float) dir.getX(), (float) dir.getY(), (float) dir.getZ());
        Quaternionf rotation = new Quaternionf().rotateTo(new Vector3f(0, 1, 0), target);

        // Midpoint in world space
        Vector mid = start.toVector().add(end.toVector()).multiply(0.5);
        Location spawnLoc = new Location(end.getWorld(), mid.getX(), mid.getY(), mid.getZ());

        // Compute world-space offset that centers the scaled 1x1x1 block around the spawn point
        // Local offset to center a [0..1]^3 scaled box is (-sx/2, -sy/2, -sz/2)
        Vector3f localCenterOffset = new Vector3f(-sx / 2f, -sy / 2f, -sz / 2f);
        Vector3f worldCenterOffset = rotation.transform(new Vector3f(localCenterOffset));
        spawnLoc.add(worldCenterOffset.x, worldCenterOffset.y, worldCenterOffset.z);

        BlockDisplay display = spawnLoc.getWorld().spawn(spawnLoc, BlockDisplay.class, entity -> {
            entity.setBlock(Bukkit.createBlockData(material));
            entity.setInterpolationDuration(0);
            entity.setViewRange(128);
            entity.setBrightness(new Display.Brightness(15, 15));
            entity.setPersistent(false);
            entity.setTransformation(new Transformation(
                    new Vector3f(0, 0, 0),
                    rotation,
                    new Vector3f(sx, sy, sz),
                    new Quaternionf()
            ));
        });

        if (tickDuration > 0) {
            Bukkit.getScheduler().runTaskLater(
                    MagmaCore.getInstance().getRequestingPlugin(),
                    display::remove,
                    tickDuration
            );
        }

        return new LineData(display, start, end, width);
    }

    public static void updateLine(LineData lineData, Location start, Location end) {
        if (lineData == null || lineData.getDisplay() == null || !lineData.getDisplay().isValid()) {
            return;
        }

        float height = (float) start.distance(end);
        if (height <= 0f) return;

        BlockDisplay display = lineData.getDisplay();
        float width = lineData.getWidth();

        // Width/depth and height
        final float sx = width;
        final float sy = height;
        final float sz = width;

        // Rotation: align local +Y with (end - start)
        Vector dir = end.toVector().subtract(start.toVector()).normalize();
        Vector3f target = new Vector3f((float) dir.getX(), (float) dir.getY(), (float) dir.getZ());
        Quaternionf rotation = new Quaternionf().rotateTo(new Vector3f(0, 1, 0), target);

        // Midpoint in world space
        Vector mid = start.toVector().add(end.toVector()).multiply(0.5);
        Location newLoc = new Location(end.getWorld(), mid.getX(), mid.getY(), mid.getZ());

        // Compute world-space offset that centers the scaled 1x1x1 block around the spawn point
        Vector3f localCenterOffset = new Vector3f(-sx / 2f, -sy / 2f, -sz / 2f);
        Vector3f worldCenterOffset = rotation.transform(new Vector3f(localCenterOffset));
        newLoc.add(worldCenterOffset.x, worldCenterOffset.y, worldCenterOffset.z);

        // Teleport to new location
        display.teleport(newLoc);

        // Update transformation
        display.setTransformation(new Transformation(
                new Vector3f(0, 0, 0),
                rotation,
                new Vector3f(sx, sy, sz),
                new Quaternionf()
        ));

        // Update the stored positions in the LineData
        lineData.updatePositions(start, end);
    }

    // Class to hold line data - mutable since we'll be updating it
    public static class LineData {
        private final BlockDisplay display;
        private final float width;
        private Location start;
        private Location end;

        public LineData(BlockDisplay display, Location start, Location end, float width) {
            this.display = display;
            this.start = start.clone();
            this.end = end.clone();
            this.width = width;
        }

        public BlockDisplay getDisplay() {
            return display;
        }

        public Location getStart() {
            return start.clone();
        }

        public Location getEnd() {
            return end.clone();
        }

        public float getWidth() {
            return width;
        }

        public void updatePositions(Location newStart, Location newEnd) {
            this.start = newStart.clone();
            this.end = newEnd.clone();
        }

        public void remove() {
            if (display != null && display.isValid()) {
                display.remove();
            }
        }
    }
}
