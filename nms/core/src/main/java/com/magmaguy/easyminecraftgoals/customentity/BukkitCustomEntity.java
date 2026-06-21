package com.magmaguy.easyminecraftgoals.customentity;

import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.UUID;

/**
 * Custom Bedrock presentation bound to a real Bukkit entity.
 * <p>
 * The wrapped entity still exists normally on the server, so AI, damage,
 * persistence, targeting, Bukkit events and plugin interactions keep using the
 * real entity. This handle only prepares Bedrock clients to render that same
 * Java entity id as a registered custom entity and keeps runtime properties in
 * sync.
 * <p>
 * {@link #prepareSpawnFor(Player)} must be called before the viewer receives
 * the entity spawn packet. If the viewer is already tracking the entity, callers
 * need to force a normal server retrack/respawn before the custom Bedrock
 * definition can take effect.
 */
public interface BukkitCustomEntity extends CustomEntityHandle {
    Entity entity();

    void prepareSpawnFor(Player player);

    void prepareSpawnFor(Collection<? extends Player> players);

    void syncTo(Player player);

    void syncToViewers();

    void forgetViewer(Player player);

    void forgetViewer(UUID uuid);

    /**
     * Removes this custom presentation handle without removing the wrapped
     * Bukkit entity.
     */
    void remove();

    boolean isValid();

    interface Builder {
        Builder identifier(String identifier);

        Builder carrierEntityType(EntityType entityType);

        Builder dimensions(float width, float height);

        Builder scale(float scale);

        Builder color(Integer color);

        Builder variant(Integer variant);

        Builder tracked(boolean tracked);

        Builder propertySchema(CustomEntityPropertySchema schema);

        Builder onShow(CustomEntityViewerHook showHook);

        Builder onHide(CustomEntityViewerHook hideHook);

        Builder onRemove(CustomEntityLifecycleHook removeHook);

        BukkitCustomEntity build(Entity entity);
    }
}
