package com.magmaguy.easyminecraftgoals.visuals.terrain;

import org.bukkit.block.data.BlockData;

import java.util.List;
import java.util.Objects;

record TerrainImpactPlan(List<Tile> tiles) {
    TerrainImpactPlan {
        tiles = List.copyOf(tiles);
    }

    record Tile(
            int x,
            int y,
            int z,
            BlockData blockData,
            double radialDistance,
            double radialFalloff,
            Transform transform) {
        Tile {
            blockData = Objects.requireNonNull(blockData, "blockData").clone();
            transform = Objects.requireNonNull(transform, "transform");
        }

        @Override
        public BlockData blockData() {
            return blockData.clone();
        }
    }

    record Transform(
            double uniformScale,
            double tiltXDegrees,
            double tiltZDegrees,
            double lift,
            double translationX,
            double translationY,
            double translationZ) {
        double rotationX() {
            return quaternion().x();
        }

        double rotationY() {
            return quaternion().y();
        }

        double rotationZ() {
            return quaternion().z();
        }

        double rotationW() {
            return quaternion().w();
        }

        Point applyTo(double x, double y, double z) {
            Point rotated = quaternion().rotate(
                    x * uniformScale,
                    y * uniformScale,
                    z * uniformScale);
            return new Point(
                    rotated.x() + translationX,
                    rotated.y() + translationY,
                    rotated.z() + translationZ);
        }

        private Quaternion quaternion() {
            return Quaternion.fromTilts(tiltXDegrees, tiltZDegrees);
        }
    }

    record Point(double x, double y, double z) {}

    private record Quaternion(double x, double y, double z, double w) {
        private static Quaternion fromTilts(double tiltXDegrees, double tiltZDegrees) {
            double halfX = Math.toRadians(tiltXDegrees) * 0.5;
            double halfZ = Math.toRadians(tiltZDegrees) * 0.5;
            double sinX = Math.sin(halfX);
            double cosX = Math.cos(halfX);
            double sinZ = Math.sin(halfZ);
            double cosZ = Math.cos(halfZ);
            return new Quaternion(
                    cosZ * sinX,
                    sinZ * sinX,
                    sinZ * cosX,
                    cosZ * cosX);
        }

        private Point rotate(double pointX, double pointY, double pointZ) {
            double crossX = y * pointZ - z * pointY;
            double crossY = z * pointX - x * pointZ;
            double crossZ = x * pointY - y * pointX;
            double secondCrossX = y * crossZ - z * crossY;
            double secondCrossY = z * crossX - x * crossZ;
            double secondCrossZ = x * crossY - y * crossX;
            return new Point(
                    pointX + 2.0 * (w * crossX + secondCrossX),
                    pointY + 2.0 * (w * crossY + secondCrossY),
                    pointZ + 2.0 * (w * crossZ + secondCrossZ));
        }
    }
}
