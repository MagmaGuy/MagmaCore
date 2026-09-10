package com.magmaguy.magmacore.projectiles;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Projectile;
import org.bukkit.persistence.PersistentDataType;

/** Shared marker used by the magic engine and FMM's modeled-hitbox router. */
public final class MagicProjectileMarker {
    private MagicProjectileMarker() {
    }

    public static boolean isMarked(Projectile projectile) {
        return projectile != null && projectile.getPersistentDataContainer().has(
                key(), PersistentDataType.BYTE);
    }

    private static final String IMPACT_OWNER = "magmacore_magic_impact_owner";

    /** Route modeled hits back to the engine that launched this projectile, across shaded copies. */
    @SuppressWarnings("unchecked")
    public static boolean resolve(Projectile projectile, org.bukkit.entity.LivingEntity target) {
        for (var value : projectile.getMetadata(IMPACT_OWNER)) {
            if (value.getOwningPlugin() != null && value.getOwningPlugin().isEnabled()
                    && value.value() instanceof java.util.function.Consumer<?> handler) {
                ((java.util.function.Consumer<org.bukkit.entity.LivingEntity>) handler).accept(target);
                return true;
            }
        }
        return false;
    }

    static void mark(Projectile projectile, org.bukkit.plugin.Plugin owner,
                     java.util.function.Consumer<org.bukkit.entity.LivingEntity> impacts) {
        projectile.getPersistentDataContainer().set(key(), PersistentDataType.BYTE, (byte) 1);
        projectile.setMetadata(IMPACT_OWNER, new org.bukkit.metadata.FixedMetadataValue(owner, impacts));
    }

    private static NamespacedKey key() {
        return new NamespacedKey("magmacore", "magic_projectile");
    }
}
