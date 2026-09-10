package com.magmaguy.magmacore.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Optional, conditional restoration for script-owned gravity/scale changes. */
public final class OwnedEntityState implements AutoCloseable {
    private final Entity entity;
    private final Plugin plugin;
    private final String key;
    private final String token = UUID.randomUUID().toString();
    private final BooleanSupplier unchanged;
    private final Runnable restore;
    private boolean closed;

    private OwnedEntityState(Entity entity, Plugin plugin, String property, BooleanSupplier unchanged, Runnable restore) {
        this.entity = entity;
        this.plugin = plugin;
        key = "nightbreak_owned_" + property;
        this.unchanged = unchanged;
        this.restore = restore;
        entity.setMetadata(key, new FixedMetadataValue(plugin, token));
    }

    public static OwnedEntityState gravity(Entity entity, boolean replacement, Plugin plugin) {
        if (!available(entity, plugin, "gravity")) return null;
        boolean original = entity.hasGravity();
        OwnedEntityState lease = new OwnedEntityState(entity, plugin, "gravity",
                () -> entity.hasGravity() == replacement, () -> entity.setGravity(original));
        try { entity.setGravity(replacement); }
        catch (RuntimeException failure) { lease.close(); throw failure; }
        return lease;
    }

    public static OwnedEntityState scale(LivingEntity entity, double replacement, Plugin plugin) {
        if (!Double.isFinite(replacement) || replacement <= 0) throw new IllegalArgumentException("Invalid scale");
        if (!available(entity, plugin, "scale")) return null;
        var attribute = org.bukkit.Registry.ATTRIBUTE.get(org.bukkit.NamespacedKey.minecraft("scale"));
        if (attribute == null) attribute = org.bukkit.Registry.ATTRIBUTE.get(org.bukkit.NamespacedKey.minecraft("generic.scale"));
        var instance = attribute == null ? null : entity.getAttribute(attribute);
        if (instance == null) return null;
        double original = instance.getBaseValue();
        OwnedEntityState lease = new OwnedEntityState(entity, plugin, "scale",
                () -> Double.compare(instance.getBaseValue(), replacement) == 0, () -> instance.setBaseValue(original));
        try { instance.setBaseValue(replacement); }
        catch (RuntimeException failure) { lease.close(); throw failure; }
        return lease;
    }

    /** Restores only the still-owned active potion, retaining the original effect's elapsed game ticks. */
    public static OwnedEntityState potion(LivingEntity entity, org.bukkit.potion.PotionEffect replacement, Plugin plugin) {
        if (replacement.getDuration() < 1) throw new IllegalArgumentException("Owned potion duration must be positive");
        String property = "potion_" + replacement.getType().getKey().getKey();
        if (!available(entity, plugin, property)) return null;
        var original = entity.getPotionEffect(replacement.getType());
        int started = entity.getTicksLived();
        OwnedEntityState lease = new OwnedEntityState(entity, plugin, property,
                () -> samePotion(entity.getPotionEffect(replacement.getType()), replacement,
                        Math.max(0, entity.getTicksLived() - started)),
                () -> {
                    entity.removePotionEffect(replacement.getType());
                    if (original == null) return;
                    int elapsed = Math.max(0, entity.getTicksLived() - started);
                    int remaining = original.getDuration() < 0 ? original.getDuration() : original.getDuration() - elapsed;
                    if (remaining > 0 || original.getDuration() < 0)
                        entity.addPotionEffect(new org.bukkit.potion.PotionEffect(original.getType(), remaining,
                                original.getAmplifier(), original.isAmbient(), original.hasParticles(), original.hasIcon()));
                });
        try {
            if (!entity.addPotionEffect(replacement) || !samePotion(entity.getPotionEffect(replacement.getType()), replacement, 0)) {
                lease.close(false);
                return null;
            }
        } catch (RuntimeException failure) { lease.close(); throw failure; }
        return lease;
    }

    private static boolean samePotion(org.bukkit.potion.PotionEffect current, org.bukkit.potion.PotionEffect applied, int elapsed) {
        return current != null && current.getType().equals(applied.getType())
                && current.getAmplifier() == applied.getAmplifier() && current.isAmbient() == applied.isAmbient()
                && current.hasParticles() == applied.hasParticles() && current.hasIcon() == applied.hasIcon()
                // Scheduler callbacks and entity potion ticks can straddle a tick boundary.
                && Math.abs((long) current.getDuration() - ((long) applied.getDuration() - elapsed)) <= 1;
    }

    private static boolean available(Entity entity, Plugin plugin, String property) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Entity ownership requires the server thread");
        return plugin.isEnabled() && entity != null && entity.isValid() && !entity.hasMetadata("nightbreak_owned_" + property);
    }

    @Override public void close() {
        close(true);
    }

    private void close(boolean restoreState) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Entity restoration requires the server thread");
        if (closed) return;
        closed = true;
        boolean owned = entity.getMetadata(key).stream()
                .anyMatch(value -> value.getOwningPlugin() == plugin && token.equals(value.asString()));
        if (!owned) return;
        entity.removeMetadata(key, plugin);
        if (restoreState && entity.isValid() && unchanged.getAsBoolean()) restore.run();
    }
}
