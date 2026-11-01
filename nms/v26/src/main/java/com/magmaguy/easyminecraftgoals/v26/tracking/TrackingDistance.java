package com.magmaguy.easyminecraftgoals.v26.tracking;

import com.magmaguy.easyminecraftgoals.v26.CraftBukkitBridge;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.spigotmc.TrackingRange;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;

/**
 * Reads the entity tracker's own range for an entity and a viewer: {@code ChunkMap.TrackedEntity.updatePlayer} sends
 * the entity while the viewer's horizontal distance is within {@code min(getEffectiveRange(), view distance * 16)}.
 * {@code getEffectiveRange()} starts from the tracker's {@code range} field and raises it to each passenger's type
 * range. Those members are private, so they are reached through method handles and reflection; a server without
 * them reports -1 and changes nothing.
 */
public final class TrackingDistance {
    private static final MethodHandle EFFECTIVE_RANGE;
    private static final MethodHandle PLAYER_VIEW_DISTANCE;
    private static final MethodHandle RANGE;
    private static final Field RANGE_FIELD;

    static {
        MethodHandle effectiveRange = null;
        MethodHandle playerViewDistance = null;
        try {
            effectiveRange = MethodHandles.privateLookupIn(ChunkMap.TrackedEntity.class, MethodHandles.lookup())
                    .findVirtual(ChunkMap.TrackedEntity.class, "getEffectiveRange", MethodType.methodType(int.class));
            playerViewDistance = MethodHandles.privateLookupIn(ChunkMap.class, MethodHandles.lookup())
                    .findVirtual(ChunkMap.class, "getPlayerViewDistance", MethodType.methodType(int.class, ServerPlayer.class));
        } catch (ReflectiveOperationException | RuntimeException e) {
            effectiveRange = null;
            playerViewDistance = null;
            Bukkit.getLogger().warning("[MagmaCore] Entity tracking ranges are unavailable on this server: " + e);
        }
        EFFECTIVE_RANGE = effectiveRange;
        PLAYER_VIEW_DISTANCE = playerViewDistance;

        MethodHandle range = null;
        Field rangeField = null;
        try {
            // The field is final: the tracker sets it once from the entity type and the server's configured ranges.
            rangeField = ChunkMap.TrackedEntity.class.getDeclaredField("range");
            rangeField.setAccessible(true);
            range = MethodHandles.privateLookupIn(ChunkMap.TrackedEntity.class, MethodHandles.lookup())
                    .unreflectGetter(rangeField);
        } catch (ReflectiveOperationException | RuntimeException e) {
            range = null;
            rangeField = null;
            Bukkit.getLogger().warning("[MagmaCore] Entity tracking ranges cannot be changed on this server: " + e);
        }
        RANGE = range;
        RANGE_FIELD = rangeField;
    }

    private TrackingDistance() {
    }

    public static double get(Entity entity, Player viewer) {
        if (EFFECTIVE_RANGE == null || entity == null || viewer == null) return -1;
        try {
            net.minecraft.world.entity.Entity nmsEntity = CraftBukkitBridge.getNMSEntity(entity);
            if (!(nmsEntity.level() instanceof ServerLevel level)) return -1;
            ChunkMap chunkMap = level.getChunkSource().chunkMap;
            ChunkMap.TrackedEntity tracked = chunkMap.entityMap.get(nmsEntity.getId());
            if (tracked == null) return -1;
            int range = (int) EFFECTIVE_RANGE.invokeExact(tracked);
            int viewChunks = (int) PLAYER_VIEW_DISTANCE.invokeExact(chunkMap, CraftBukkitBridge.getServerPlayer(viewer));
            return Math.min(range, viewChunks * 16);
        } catch (Throwable e) {
            return -1;
        }
    }

    /** The tracker's own range for the entity, before its passengers raise it; -1 when unknown. */
    public static int range(Entity entity) {
        if (RANGE == null || entity == null) return -1;
        try {
            net.minecraft.world.entity.Entity nmsEntity = CraftBukkitBridge.getNMSEntity(entity);
            if (!(nmsEntity.level() instanceof ServerLevel level)) return -1;
            ChunkMap.TrackedEntity tracked = level.getChunkSource().chunkMap.entityMap.get(nmsEntity.getId());
            return tracked == null ? -1 : (int) RANGE.invokeExact(tracked);
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
        if (RANGE_FIELD == null || entity == null || !Bukkit.isPrimaryThread()) return false;
        try {
            net.minecraft.world.entity.Entity nmsEntity = CraftBukkitBridge.getNMSEntity(entity);
            if (!(nmsEntity.level() instanceof ServerLevel level)) return false;
            ChunkMap.TrackedEntity tracked = level.getChunkSource().chunkMap.entityMap.get(nmsEntity.getId());
            if (tracked == null) return false;
            // ChunkMap.addEntity computes the configured range the same way.
            int value = range >= 0 ? range
                    : TrackingRange.getEntityTrackingRange(nmsEntity, nmsEntity.getType().clientTrackingRange() * 16);
            RANGE_FIELD.setInt(tracked, value);
            tracked.updatePlayers(level.players());
            return true;
        } catch (Throwable e) {
            return false;
        }
    }
}
