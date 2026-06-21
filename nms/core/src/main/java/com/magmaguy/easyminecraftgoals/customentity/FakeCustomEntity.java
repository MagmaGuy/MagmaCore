package com.magmaguy.easyminecraftgoals.customentity;

import com.magmaguy.easyminecraftgoals.internal.PacketEntityInterface;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface FakeCustomEntity extends CustomEntityHandle {
    CustomEntityDefinition definition();

    PacketEntityInterface packetEntity();

    int getEntityId();

    Location getLocation();

    UUID getUniqueId();

    Set<UUID> getViewers();

    void displayTo(Player player);

    void displayTo(UUID uuid);

    void hideFrom(Player player);

    void hideFrom(UUID uuid);

    void remove();

    void teleport(Location location);

    void setVisible(boolean visible);

    void mountTo(int vehicleEntityId);

    void dismount();

    void setScale(float scale);

    void setColor(Integer color);

    void setVariant(Integer variant);

    void setHitbox(float width, float height);

    void setProperties(Map<String, ?> properties);

    void setProperty(String identifier, Object value);

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

        FakeCustomEntity build(Location location);
    }
}
