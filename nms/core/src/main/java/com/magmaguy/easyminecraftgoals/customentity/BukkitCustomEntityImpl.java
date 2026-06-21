package com.magmaguy.easyminecraftgoals.customentity;

import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.easyminecraftgoals.internal.PacketEntityTracker;
import com.magmaguy.easyminecraftgoals.internal.TrackedPacketEntity;
import com.magmaguy.easyminecraftgoals.thirdparty.BedrockChecker;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BukkitCustomEntityImpl implements BukkitCustomEntity, TrackedPacketEntity {
    private final Entity entity;
    private final CustomEntityDefinition definition;
    private final Set<UUID> viewers = ConcurrentHashMap.newKeySet();
    private final CustomEntityPropertyState properties = new CustomEntityPropertyState();
    private volatile Float scale;
    private volatile Integer color;
    private volatile Integer variant;
    private volatile Float hitboxWidth;
    private volatile Float hitboxHeight;
    private volatile boolean removed;

    public BukkitCustomEntityImpl(Entity entity, CustomEntityDefinition definition) {
        if (entity == null) throw new IllegalArgumentException("entity cannot be null");
        if (definition == null) throw new IllegalArgumentException("definition cannot be null");
        this.entity = entity;
        this.definition = definition;
        this.scale = definition.scale();
        this.color = definition.color();
        this.variant = definition.variant();
        this.hitboxWidth = definition.width();
        this.hitboxHeight = definition.height();
        runBridgeSafely(() -> BedrockCustomEntityBridgeRegistry.bridge().registerDefinition(definition));
        if (definition.tracked()) {
            PacketEntityTracker.getInstance().register(this);
        }
    }

    @Override
    public CustomEntityDefinition definition() {
        return definition;
    }

    @Override
    public Entity entity() {
        return entity;
    }

    @Override
    public int getEntityId() {
        return entity.getEntityId();
    }

    @Override
    public Location getLocation() {
        return entity.getLocation();
    }

    @Override
    public UUID getUniqueId() {
        return entity.getUniqueId();
    }

    @Override
    public Set<UUID> getViewers() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(viewers));
    }

    @Override
    public void prepareSpawnFor(Player player) {
        if (player == null || removed || !isValid()) return;

        boolean bedrock = BedrockChecker.isBedrock(player);
        if (bedrock) {
            runBridgeSafely(() -> {
                BedrockCustomEntityBridge bridge = BedrockCustomEntityBridgeRegistry.bridge();
                bridge.registerDefinition(definition);
                bridge.prepareEntitySpawn(player, entity.getEntityId(), definition);
            });
        }

        boolean newViewer = viewers.add(player.getUniqueId());
        if (newViewer) {
            runHookSafely(() -> definition.showHook().handle(this, player));
        }

        if (bedrock) {
            schedulePostSpawnSync(player);
        }
    }

    @Override
    public void prepareSpawnFor(Collection<? extends Player> players) {
        if (players == null) return;
        players.forEach(this::prepareSpawnFor);
    }

    @Override
    public void syncTo(Player player) {
        if (player == null || removed || !isValid()) return;

        boolean newViewer = viewers.add(player.getUniqueId());
        if (newViewer) {
            runHookSafely(() -> definition.showHook().handle(this, player));
        }

        if (BedrockChecker.isBedrock(player)) {
            sendBedrockState(player);
        }
    }

    @Override
    public void syncToViewers() {
        for (UUID uuid : viewers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                syncTo(player);
            }
        }
    }

    @Override
    public void forgetViewer(Player player) {
        if (player == null) return;
        forgetViewer(player.getUniqueId());
    }

    @Override
    public void forgetViewer(UUID uuid) {
        if (uuid == null) return;
        boolean wasViewing = viewers.remove(uuid);
        if (wasViewing) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                runHookSafely(() -> definition.hideHook().handle(this, player));
            }
        }
    }

    @Override
    public void remove() {
        if (removed) return;
        removed = true;
        PacketEntityTracker.getInstance().unregister(this);
        for (UUID uuid : new LinkedHashSet<>(viewers)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                runHookSafely(() -> definition.hideHook().handle(this, player));
            }
        }
        runHookSafely(() -> definition.removeHook().handle(this));
        viewers.clear();
    }

    @Override
    public boolean isValid() {
        return !removed && entity.isValid();
    }

    @Override
    public Location getTrackingLocation() {
        Entity vehicle = getVehicle();
        return vehicle == null ? getLocation() : vehicle.getLocation();
    }

    @Override
    public World getWorld() {
        Location location = getLocation();
        return location == null ? null : location.getWorld();
    }

    @Override
    public void showToPlayer(Player player) {
        prepareSpawnFor(player);
    }

    @Override
    public void hideFromPlayer(Player player) {
        forgetViewer(player);
    }

    @Override
    public boolean isVisibleTo(Player player) {
        return player != null && viewers.contains(player.getUniqueId());
    }

    @Override
    public Set<UUID> getCurrentViewers() {
        return getViewers();
    }

    @Override
    public Entity getVehicle() {
        return entity.getVehicle();
    }

    @Override
    public void remount() {
        // The server owns real Bukkit passenger state; no packet remount is
        // needed for this custom Bedrock presentation handle.
    }

    @Override
    public void setScale(float scale) {
        this.scale = scale;
        sendEntityDataToBedrockViewers();
    }

    @Override
    public void setColor(Integer color) {
        this.color = color;
        sendEntityDataToBedrockViewers();
    }

    @Override
    public void setVariant(Integer variant) {
        this.variant = variant;
        sendEntityDataToBedrockViewers();
    }

    @Override
    public void setHitbox(float width, float height) {
        this.hitboxWidth = width;
        this.hitboxHeight = height;
        sendEntityDataToBedrockViewers();
    }

    @Override
    public void setProperties(Map<String, ?> properties) {
        this.properties.setAll(properties);
        sendPropertiesToBedrockViewers();
    }

    @Override
    public void setProperty(String identifier, Object value) {
        this.properties.set(identifier, value);
        sendPropertiesToBedrockViewers();
    }

    private void schedulePostSpawnSync(Player player) {
        Plugin plugin = NMSManager.pluginProvider;
        if (plugin == null || !plugin.isEnabled()) {
            sendBedrockState(player);
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && viewers.contains(player.getUniqueId()) && isValid()) {
                sendBedrockState(player);
            }
        }, 1L);
    }

    private void sendBedrockState(Player player) {
        sendEntityData(player);
        sendProperties(player);
    }

    private void sendEntityDataToBedrockViewers() {
        for (UUID uuid : viewers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && BedrockChecker.isBedrock(player)) {
                sendEntityData(player);
            }
        }
    }

    private void sendPropertiesToBedrockViewers() {
        for (UUID uuid : viewers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && BedrockChecker.isBedrock(player)) {
                sendProperties(player);
            }
        }
    }

    private void sendEntityData(Player player) {
        runBridgeSafely(() -> BedrockCustomEntityBridgeRegistry.bridge().sendEntityData(
                player,
                entity.getEntityId(),
                hitboxHeight,
                hitboxWidth,
                scale,
                color,
                variant));
    }

    private void sendProperties(Player player) {
        Map<String, Object> snapshot = properties.snapshot();
        if (snapshot.isEmpty()) return;
        runBridgeSafely(() -> BedrockCustomEntityBridgeRegistry.bridge().sendProperties(
                player,
                entity.getEntityId(),
                snapshot));
    }

    private void runBridgeSafely(Runnable runnable) {
        try {
            runnable.run();
        } catch (Throwable throwable) {
            if (NMSManager.pluginProvider != null) {
                NMSManager.pluginProvider.getLogger().warning(
                        "Bedrock custom entity bridge call failed for "
                                + definition.identifier() + ": " + throwable.getMessage());
            }
        }
    }

    private void runHookSafely(Runnable runnable) {
        try {
            runnable.run();
        } catch (Throwable throwable) {
            if (NMSManager.pluginProvider != null) {
                NMSManager.pluginProvider.getLogger().warning(
                        "Custom entity lifecycle hook failed for "
                                + definition.identifier() + ": " + throwable.getMessage());
            }
        }
    }
}
