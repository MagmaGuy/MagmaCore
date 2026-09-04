package com.magmaguy.magmacore.ai;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record MindPosition(String world, double x, double y, double z) {
    public static final MindValueCodec<MindPosition> CODEC = new MindValueCodec<>() {
        @Override
        public Object encode(MindPosition value) {
            Map<String, Object> encoded = new LinkedHashMap<>();
            encoded.put("world", value.world);
            encoded.put("x", value.x);
            encoded.put("y", value.y);
            encoded.put("z", value.z);
            return encoded;
        }

        @Override
        public MindPosition decode(Object value) {
            if (!(value instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("Expected a persisted position map");
            }
            Object world = map.get("world");
            Object x = map.get("x");
            Object y = map.get("y");
            Object z = map.get("z");
            if (!(world instanceof String worldName)
                    || !(x instanceof Number xNumber)
                    || !(y instanceof Number yNumber)
                    || !(z instanceof Number zNumber)) {
                throw new IllegalArgumentException("Persisted position map is incomplete");
            }
            return new MindPosition(
                    worldName,
                    xNumber.doubleValue(),
                    yNumber.doubleValue(),
                    zNumber.doubleValue());
        }
    };

    public MindPosition {
        if (world == null || world.isBlank()) throw new IllegalArgumentException("world cannot be blank");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Mind positions must be finite");
        }
    }

    public static MindPosition from(Location location) {
        Objects.requireNonNull(location, "location");
        World world = Objects.requireNonNull(location.getWorld(), "location world");
        return new MindPosition(world.getName(), location.getX(), location.getY(), location.getZ());
    }

    public Location toLocation(World fallbackWorld) {
        World resolved = fallbackWorld;
        if (fallbackWorld == null || !fallbackWorld.getName().equals(world)) {
            resolved = org.bukkit.Bukkit.getWorld(world);
        }
        if (resolved == null) return null;
        return new Location(resolved, x, y, z);
    }
}
