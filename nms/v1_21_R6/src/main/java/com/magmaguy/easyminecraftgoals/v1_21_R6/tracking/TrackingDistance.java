package com.magmaguy.easyminecraftgoals.v1_21_R6.tracking;

import org.bukkit.craftbukkit.v1_21_R6.entity.CraftEntity;
import org.bukkit.craftbukkit.v1_21_R6.entity.CraftPlayer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.spigotmc.TrackingRange;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.function.Predicate;

/**
 * The server's entity tracker ({@code ChunkMap.TrackedEntity.updatePlayer}) sends an entity to a player while their
 * horizontal distance is within {@code min(getEffectiveRange(), ChunkMap.getPlayerViewDistance(player) * 16)}, and
 * {@code getEffectiveRange()} starts from the tracker's {@code range} field. All three are private, and Spigot
 * obfuscates their names before 26.1, so each is found by its signature, which is unique on every supported Spigot
 * and Paper version: the tracker's only int field, its only private {@code int()} method, and the chunk map's only
 * {@code int(ServerPlayer)} method. A server where one is missing or ambiguous reports -1 and changes nothing.
 */
public final class TrackingDistance {
    private static final Field RANGE;
    private static final MethodHandle EFFECTIVE_RANGE;
    private static final MethodHandle PLAYER_VIEW_DISTANCE;

    static {
        Field range = null;
        MethodHandle effectiveRange = null;
        MethodHandle playerViewDistance = null;
        try {
            range = only(Arrays.stream(ChunkMap.TrackedEntity.class.getDeclaredFields())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()) && field.getType() == int.class)
                    .toArray(Field[]::new), "int field of the entity tracker");
            // Final: the tracker sets it once from the entity type and the server's configured ranges.
            range.setAccessible(true);
            effectiveRange = handle(ChunkMap.TrackedEntity.class, method -> Modifier.isPrivate(method.getModifiers())
                    && method.getParameterCount() == 0, "private int() method of the entity tracker");
            playerViewDistance = handle(ChunkMap.class, method -> method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == ServerPlayer.class, "int(ServerPlayer) method of the chunk map");
        } catch (ReflectiveOperationException | RuntimeException e) {
            range = null;
            effectiveRange = null;
            playerViewDistance = null;
            Bukkit.getLogger().warning("[MagmaCore] Entity tracking ranges are unavailable on this server: " + e);
        }
        RANGE = range;
        EFFECTIVE_RANGE = effectiveRange;
        PLAYER_VIEW_DISTANCE = playerViewDistance;
    }

    private TrackingDistance() {
    }

    private static <T> T only(T[] members, String what) {
        if (members.length != 1) throw new IllegalStateException(members.length + " candidates for the " + what);
        return members[0];
    }

    private static MethodHandle handle(Class<?> owner, Predicate<Method> shape, String what)
            throws ReflectiveOperationException {
        Method method = only(Arrays.stream(owner.getDeclaredMethods())
                .filter(candidate -> !Modifier.isStatic(candidate.getModifiers())
                        && candidate.getReturnType() == int.class && shape.test(candidate))
                .toArray(Method[]::new), what);
        return MethodHandles.privateLookupIn(owner, MethodHandles.lookup()).unreflect(method);
    }

    private static ChunkMap.TrackedEntity tracker(net.minecraft.world.entity.Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) return null;
        return level.getChunkSource().chunkMap.entityMap.get(entity.getId());
    }

    /** How far the tracker sends the entity to the viewer, exactly as {@code TrackedEntity.updatePlayer} computes it. */
    public static double get(Entity entity, Player viewer) {
        if (RANGE == null || entity == null || viewer == null) return -1;
        try {
            net.minecraft.world.entity.Entity handle = ((CraftEntity) entity).getHandle();
            if (!(handle.level() instanceof ServerLevel level)) return -1;
            ChunkMap chunkMap = level.getChunkSource().chunkMap;
            ChunkMap.TrackedEntity tracker = chunkMap.entityMap.get(handle.getId());
            if (tracker == null) return -1;
            ServerPlayer player = ((CraftPlayer) viewer).getHandle();
            int range = (int) EFFECTIVE_RANGE.invokeExact(tracker);
            int viewChunks = (int) PLAYER_VIEW_DISTANCE.invokeExact(chunkMap, player);
            return Math.min(range, viewChunks * 16);
        } catch (Throwable e) {
            return -1;
        }
    }

    /** The tracker's own range for the entity, before its passengers raise it; -1 when unknown. */
    public static int range(Entity entity) {
        if (RANGE == null || entity == null) return -1;
        try {
            ChunkMap.TrackedEntity tracker = tracker(((CraftEntity) entity).getHandle());
            return tracker == null ? -1 : RANGE.getInt(tracker);
        } catch (Throwable e) {
            return -1;
        }
    }

    /**
     * Sets the tracker's own range for the entity, or restores the configured one when {@code range} is negative,
     * then rechecks every player in its world. The tracker only rechecks a player when the player or the entity
     * crosses into another chunk section, so without that a player already inside the new range would wait.
     */
    public static boolean setRange(Entity entity, int range) {
        if (RANGE == null || entity == null || !Bukkit.isPrimaryThread()) return false;
        try {
            net.minecraft.world.entity.Entity handle = ((CraftEntity) entity).getHandle();
            ChunkMap.TrackedEntity tracker = tracker(handle);
            if (tracker == null) return false;
            // ChunkMap.addEntity computes the configured range the same way on Spigot and Paper.
            int value = range >= 0 ? range
                    : TrackingRange.getEntityTrackingRange(handle, handle.getType().clientTrackingRange() * 16);
            RANGE.setInt(tracker, value);
            tracker.updatePlayers(((ServerLevel) handle.level()).players());
            return true;
        } catch (Throwable e) {
            return false;
        }
    }
}
