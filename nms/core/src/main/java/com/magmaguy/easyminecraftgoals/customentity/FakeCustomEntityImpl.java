package com.magmaguy.easyminecraftgoals.customentity;

import com.magmaguy.easyminecraftgoals.NMSAdapter;
import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.easyminecraftgoals.internal.PacketEntityInterface;
import com.magmaguy.easyminecraftgoals.internal.PacketEntityTracker;
import com.magmaguy.easyminecraftgoals.internal.TrackedPacketEntity;
import com.magmaguy.easyminecraftgoals.thirdparty.BedrockChecker;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FakeCustomEntityImpl implements FakeCustomEntity, TrackedPacketEntity {
    private final CustomEntityDefinition definition;
    private final PacketEntityInterface packetEntity;
    private final Set<UUID> viewers = ConcurrentHashMap.newKeySet();
    private final CustomEntityPropertyState properties = new CustomEntityPropertyState();
    private volatile Float scale;
    private volatile Integer color;
    private volatile Integer variant;
    private volatile Float hitboxWidth;
    private volatile Float hitboxHeight;
    private volatile boolean removed;

    public FakeCustomEntityImpl(NMSAdapter adapter, Location location, CustomEntityDefinition definition) {
        this.definition = definition;
        this.scale = definition.scale();
        this.color = definition.color();
        this.variant = definition.variant();
        this.hitboxWidth = definition.width();
        this.hitboxHeight = definition.height();
        this.packetEntity = adapter.createPacketEntity(definition.carrierEntityType(), location);
        BedrockCustomEntityBridgeRegistry.bridge().registerDefinition(definition);
        if (definition.tracked()) {
            PacketEntityTracker.getInstance().register(this);
        }
    }

    @Override
    public CustomEntityDefinition definition() {
        return definition;
    }

    @Override
    public PacketEntityInterface packetEntity() {
        return packetEntity;
    }

    @Override
    public int getEntityId() {
        return packetEntity.getEntityId();
    }

    @Override
    public Location getLocation() {
        return packetEntity.getLocation();
    }

    @Override
    public UUID getUniqueId() {
        return packetEntity.getUniqueId();
    }

    @Override
    public Set<UUID> getViewers() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(viewers));
    }

    @Override
    public void displayTo(Player player) {
        if (player == null || removed || viewers.contains(player.getUniqueId())) return;

        boolean bedrock = BedrockChecker.isBedrock(player);
        if (bedrock) {
            displayToBedrock(player);
            return;
        }

        packetEntity.displayTo(player.getUniqueId());
        viewers.add(player.getUniqueId());
        runHookSafely(() -> definition.showHook().handle(this, player));
    }

    private void displayToBedrock(Player player) {
        UUID uuid = player.getUniqueId();
        viewers.add(uuid);
        prepareBedrockSpawn(player);
        runHookSafely(() -> definition.showHook().handle(this, player));

        Plugin plugin = NMSManager.pluginProvider;
        if (plugin == null || !plugin.isEnabled()) {
            packetEntity.displayTo(uuid);
            schedulePostSpawnSync(player);
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player currentPlayer = Bukkit.getPlayer(uuid);
            if (currentPlayer == null || !currentPlayer.isOnline() || removed || !viewers.contains(uuid)) {
                return;
            }
            prepareBedrockSpawn(currentPlayer);
            packetEntity.displayTo(uuid);
            schedulePostSpawnSync(currentPlayer);
        }, 1L);
    }

    private void prepareBedrockSpawn(Player player) {
        runBridgeSafely(() -> {
            BedrockCustomEntityBridge bridge = BedrockCustomEntityBridgeRegistry.bridge();
            bridge.registerDefinition(definition);
            bridge.prepareEntitySpawn(player, packetEntity.getEntityId(), definition);
        });
    }

    @Override
    public void displayTo(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) displayTo(player);
    }

    @Override
    public void hideFrom(Player player) {
        if (player == null) return;
        hideFrom(player.getUniqueId());
    }

    @Override
    public void hideFrom(UUID uuid) {
        boolean wasViewing = viewers.remove(uuid);
        packetEntity.hideFrom(uuid);
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
        packetEntity.remove();
        viewers.clear();
    }

    @Override
    public void teleport(Location location) {
        packetEntity.teleport(location);
    }

    @Override
    public void setVisible(boolean visible) {
        packetEntity.setVisible(visible);
    }

    @Override
    public void mountTo(int vehicleEntityId) {
        packetEntity.mountTo(vehicleEntityId);
    }

    @Override
    public void dismount() {
        packetEntity.dismount();
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

    @Override
    public Location getTrackingLocation() {
        return getLocation();
    }

    @Override
    public World getWorld() {
        Location location = getLocation();
        return location == null ? null : location.getWorld();
    }

    @Override
    public void showToPlayer(Player player) {
        displayTo(player);
    }

    @Override
    public void hideFromPlayer(Player player) {
        hideFrom(player);
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
    public boolean isValid() {
        return !removed && getWorld() != null;
    }

    @Override
    public Entity getVehicle() {
        return null;
    }

    @Override
    public void remount() {
        // PacketEntityInterface already remembers its own mount packets; callers
        // that use custom vehicle rules can reissue mountTo after respawn/world changes.
    }

    private void schedulePostSpawnSync(Player player) {
        Plugin plugin = NMSManager.pluginProvider;
        if (plugin == null || !plugin.isEnabled()) {
            sendBedrockState(player);
            return;
        }
        schedulePostSpawnSync(player.getUniqueId(), 4L);
        schedulePostSpawnSync(player.getUniqueId(), 10L);
    }

    private void schedulePostSpawnSync(UUID uuid, long delayTicks) {
        Plugin plugin = NMSManager.pluginProvider;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline() && viewers.contains(uuid)) {
                sendBedrockState(player);
            }
        }, delayTicks);
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
                packetEntity.getEntityId(),
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
                packetEntity.getEntityId(),
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
