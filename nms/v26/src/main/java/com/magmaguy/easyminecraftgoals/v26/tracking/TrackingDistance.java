package com.magmaguy.easyminecraftgoals.v26.tracking;

import com.magmaguy.easyminecraftgoals.v26.CraftBukkitBridge;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Reads the entity tracker's own range for an entity and a viewer: {@code ChunkMap.TrackedEntity.updatePlayer} sends
 * the entity while the viewer's horizontal distance is within {@code min(getEffectiveRange(), view distance * 16)}.
 * Both methods are private, so they are read through method handles; a server without them reports -1.
 */
public final class TrackingDistance {
    private static final MethodHandle EFFECTIVE_RANGE;
    private static final MethodHandle PLAYER_VIEW_DISTANCE;

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
}
