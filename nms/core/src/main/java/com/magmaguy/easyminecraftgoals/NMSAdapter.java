package com.magmaguy.easyminecraftgoals;

import com.magmaguy.easyminecraftgoals.constants.OverridableWanderPriority;
import com.magmaguy.easyminecraftgoals.customentity.BukkitCustomEntity;
import com.magmaguy.easyminecraftgoals.customentity.BukkitCustomEntityImpl;
import com.magmaguy.easyminecraftgoals.customentity.CustomEntityDefinition;
import com.magmaguy.easyminecraftgoals.customentity.CustomEntityLifecycleHook;
import com.magmaguy.easyminecraftgoals.customentity.CustomEntityPropertySchema;
import com.magmaguy.easyminecraftgoals.customentity.CustomEntityViewerHook;
import com.magmaguy.easyminecraftgoals.customentity.FakeCustomEntity;
import com.magmaguy.easyminecraftgoals.customentity.FakeCustomEntityImpl;
import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.easyminecraftgoals.internal.AbstractWanderBackToPoint;
import com.magmaguy.easyminecraftgoals.internal.DamageIndicatorClamp;
import com.magmaguy.easyminecraftgoals.internal.FakeItem;
import com.magmaguy.easyminecraftgoals.internal.FakeItemSettings;
import com.magmaguy.easyminecraftgoals.internal.FakeText;
import com.magmaguy.easyminecraftgoals.internal.FakeTextSettings;
import com.magmaguy.easyminecraftgoals.internal.PacketEntityInterface;
import com.magmaguy.easyminecraftgoals.internal.PacketEntityInteractionManager;
import com.magmaguy.easyminecraftgoals.internal.PacketInteractionEntity;
import com.magmaguy.easyminecraftgoals.internal.PacketModelEntity;
import com.magmaguy.easyminecraftgoals.internal.PacketTextEntity;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

public abstract class NMSAdapter {
    /**
     * Simply makes an entity move to a point.
     *
     * @param livingEntity   The entity to move
     * @param speedModifier  The speed modifier. Set 1.0 for the base movement speed attribute.
     * @param targetLocation The target location
     * @return Whether the objective is reachable.
     */
    public abstract boolean move(LivingEntity livingEntity, double speedModifier, Location targetLocation);

    public abstract boolean forcedMove(LivingEntity livingEntity, double speedModifier, Location location);

    /**
     * Pathfinding method which will move an entity to a point with a complete disregard for any pathfinding. This means
     * that no pathfinding will be done by Minecraft - the entity can get stuck on blocks, won't be able to jump or really
     * hardly any obstacle. Highly efficient, highly predictable.
     *
     * @param livingEntity  Entity to move
     * @param speedModifier Speed modifier
     * @param location      Location to move to
     */
    public abstract void universalMove(LivingEntity livingEntity, double speedModifier, Location location) ;

    public abstract AbstractWanderBackToPoint wanderBackToPoint(LivingEntity livingEntity,
                                                       Location blockLocation,
                                                       double maximumDistanceFromPoint,
                                                       int maxDurationTicks,
                                                       OverridableWanderPriority overridableWanderPriority);

    /**
     * Sets a custom hitbox size for an entity
     * @param entity The entity whose hitbox is about to get resized
     * @param width Width of the hitbox. Sets the X and Z axis at the same time, that's a Minecraft limitation
     * @param height The height of the hitbox, has to be lower than 64.
     * @param fixed Whether the hitbox can scale
     * @return
     */
    public abstract boolean setCustomHitbox(Entity entity, float width, float height, boolean fixed);

    /**
     * Sets a custom passenger riding offset for an entity, overriding where
     * passengers sit. The offset is relative to the entity's position.
     *
     * @param entity  The entity whose passenger position should be changed
     * @param offsetX X offset from entity center
     * @param offsetY Y offset from entity position (seat height)
     * @param offsetZ Z offset from entity center
     * @return true if the offset was successfully applied
     */
    public boolean setPassengerOffset(Entity entity, double offsetX, double offsetY, double offsetZ) {
        return false; // Not supported on older versions
    }

    public abstract float getBodyRotation(Entity entity);

    public abstract PacketModelEntity createPacketArmorStandEntity(Location location);

    public abstract PacketModelEntity createPacketDisplayEntity(Location location);

    public abstract PacketTextEntity createPacketTextArmorStandEntity(Location location);

    public abstract void doNotMove(LivingEntity livingEntity);

    /**
     * Makes a mob wander back to a point when it reaches a certain distance from that point, acting as a leash.
     * Please note that not all mobs in minecraft are able to pathfind - mobs like slimes can't. This leash can be
     * set to teleport them back, but if they're not set to teleport and they are set to walk back they will be unable
     * to do anything.
     * Pathfinding entities should extend Creature from the Spigot API. Non-creature entities are only allowable here
     * under the assumption that they will be set to teleport.
     *
     * @param livingEntity             Entity to leash
     * @param blockLocation            Location to leash to
     * @param maximumDistanceFromPoint Maximum distance before the leash is applied
     * @param maxDurationTicks         Maximum duration of the navigation resulting form the leash
     * @return The instance of the WanderBackToPoint. Can be used in a builder pattern!
     */
    @Nullable
    public AbstractWanderBackToPoint wanderBackToPoint(@NonNull LivingEntity livingEntity,
                                                       @NonNull Location blockLocation,
                                                       double maximumDistanceFromPoint,
                                                       int maxDurationTicks) {
        OverridableWanderPriority overridableWanderPriority;
        try {
            overridableWanderPriority = OverridableWanderPriority.valueOf(livingEntity.getType().name());
        } catch (Exception ex) {
            NMSManager.pluginProvider.getLogger().warning("[EasyMinecraftPathfinding] Attempted to assign return point to entity type " + livingEntity.getType().name() + " which is not a currently accepted entity type!");
            return null;
        }
        return wanderBackToPoint(
                livingEntity,
                blockLocation,
                maximumDistanceFromPoint,
                maxDurationTicks,
                overridableWanderPriority);
    }

    public boolean canReach(LivingEntity livingEntity, Location location){
        //Gets overriden by the correct adapter
        return false;
    }

    public abstract void setBlockInNativeDataPalette(World world, int x, int y, int z, BlockData blockData, boolean applyPhysics);

    public abstract AbstractPacketBundle createPacketBundle();

    /**
     * Creates a generic packet entity of the specified type.
     * The entity exists only in packets - not added to the world.
     * Use getBukkitEntity() to modify properties, then syncMetadata() to update viewers.
     * All MagmaCore-supported adapters are expected to implement this.
     *
     * @param entityType The Bukkit entity type to create
     * @param location   The spawn location
     * @return A packet entity that can be shown to players
     */
    public PacketEntityInterface createPacketEntity(EntityType entityType, Location location) {
        throw new UnsupportedOperationException("createPacketEntity is not supported by this adapter");
    }

    /**
     * Creates a packet-only custom visual entity. Java viewers receive the carrier
     * entity packets directly; Bedrock viewers first receive the registered custom
     * Bedrock definition id through the active BedrockCustomEntityBridge.
     */
    public FakeCustomEntity createFakeCustomEntity(Location location, CustomEntityDefinition definition) {
        return new FakeCustomEntityImpl(this, location, definition);
    }

    public FakeCustomEntity.Builder fakeCustomEntityBuilder() {
        return new FakeCustomEntityBuilderImpl(this);
    }

    /**
     * Creates a custom Bedrock presentation bound to an existing Bukkit entity.
     * The Bukkit entity remains the authoritative server entity for AI,
     * targeting, damage, persistence and events. Call
     * {@link BukkitCustomEntity#prepareSpawnFor(org.bukkit.entity.Player)}
     * before Bedrock viewers receive the entity spawn packet.
     */
    public BukkitCustomEntity createBukkitCustomEntity(Entity entity, CustomEntityDefinition definition) {
        return new BukkitCustomEntityImpl(entity, definition);
    }

    public BukkitCustomEntity.Builder bukkitCustomEntityBuilder() {
        return new BukkitCustomEntityBuilderImpl(this);
    }

    /**
     * Creates a packet-only Interaction entity for detecting player clicks.
     * The entity exists only in packets - it's invisible but clickable.
     * Use setRightClickCallback/setLeftClickCallback to handle interactions.
     *
     * @param location The spawn location
     * @param width    The width (and depth) of the interaction hitbox
     * @param height   The height of the interaction hitbox
     * @return A packet interaction entity that can receive click events
     */
    public PacketInteractionEntity createPacketInteractionEntity(Location location, float width, float height) {
        throw new UnsupportedOperationException("createPacketInteractionEntity is only supported in Minecraft 1.21.11+");
    }

    /**
     * Initializes the packet interaction listener for detecting clicks on packet-only entities.
     * This should be called once during plugin startup after the NMSAdapter is loaded.
     * Default implementation does nothing - version-specific adapters should override.
     *
     * @param plugin The plugin instance
     */
    public void initializePacketInteractionListener(Plugin plugin) {
        // Default no-op - version-specific adapters override this
    }

    /**
     * Shuts down the packet interaction listener.
     * Default implementation does nothing - version-specific adapters should override.
     */
    public void shutdownPacketInteractionListener() {
        // Default no-op
        PacketEntityInteractionManager.getInstance().shutdown();
    }

    /**
     * Sets the maximum number of {@code DAMAGE_INDICATOR} particles that
     * outbound {@code ClientboundLevelParticlesPacket} packets may carry.
     * <p>
     * Vanilla {@code Player.attack(Entity)} spawns one particle per two HP of
     * damage dealt and packs the count into a single packet, so plugins that
     * scale player melee damage into the millions broadcast packets that ask
     * the client to instantiate hundreds of thousands of particles locally and
     * freeze. Setting a small positive cap (e.g. {@code 16}) collapses the
     * visual to a normal-looking puff without altering damage or any other
     * game state. Pass {@code 0} or a negative value to disable.
     * <p>
     * Only takes effect on server versions whose adapter installs the netty
     * write interceptor (currently 1.21+).
     */
    public void setDamageIndicatorParticleCap(int max) {
        DamageIndicatorClamp.setMaxParticles(max);
    }

    public int getDamageIndicatorParticleCap() {
        return DamageIndicatorClamp.getMaxParticles();
    }

    /**
     * Hides the numeric score column for a sidebar objective while keeping score values available
     * for ordering. Supported on modern versions with scoreboard number formats.
     */
    public boolean hideScoreboardNumbers(org.bukkit.scoreboard.Objective objective) {
        return false;
    }

    /**
     * Creates a FakeText that automatically uses TextDisplay for Java Edition players
     * and ArmorStand for Bedrock Edition players.
     *
     * @param location The spawn location
     * @param settings The text display settings
     * @return A FakeText instance
     */
    public abstract FakeText createFakeText(Location location, FakeTextSettings settings);

    /**
     * Creates a builder for FakeText with customizable styling options.
     *
     * @return A new FakeText.Builder
     */
    public FakeText.Builder fakeTextBuilder() {
        return new FakeTextBuilderImpl(this);
    }

    /**
     * Checks if FakeItem is supported on this server version.
     * FakeItem requires Minecraft 1.21.4+.
     *
     * @return true if FakeItem is supported, false otherwise
     */
    public boolean supportsFakeItems() {
        return false;
    }

    /**
     * Creates a FakeItem that displays an item visually without being pickable.
     * Uses ItemDisplay for visual representation.
     *
     * @param location The spawn location
     * @param settings The item display settings
     * @return A FakeItem instance
     */
    public FakeItem createFakeItem(Location location, FakeItemSettings settings) {
        throw new UnsupportedOperationException("createFakeItem is only supported in Minecraft 1.21.4+");
    }

    /**
     * Creates a builder for FakeItem with customizable options.
     *
     * @return A new FakeItem.Builder
     */
    public FakeItem.Builder fakeItemBuilder() {
        return new FakeItemBuilderImpl(this);
    }

    /**
     * Default implementation of FakeText.Builder.
     */
    private static class FakeTextBuilderImpl implements FakeText.Builder {
        private final NMSAdapter adapter;
        private final FakeTextSettings settings = new FakeTextSettings();

        FakeTextBuilderImpl(NMSAdapter adapter) {
            this.adapter = adapter;
        }

        @Override
        public FakeText.Builder text(String text) {
            settings.setText(text);
            return this;
        }

        @Override
        public FakeText.Builder backgroundColor(org.bukkit.Color color) {
            settings.setBackgroundColor(color);
            return this;
        }

        @Override
        public FakeText.Builder backgroundColor(int argb) {
            settings.setBackgroundArgb(argb);
            return this;
        }

        @Override
        public FakeText.Builder textOpacity(byte opacity) {
            settings.setTextOpacity(opacity);
            return this;
        }

        @Override
        public FakeText.Builder billboard(org.bukkit.entity.Display.Billboard billboard) {
            settings.setBillboard(billboard);
            return this;
        }

        @Override
        public FakeText.Builder alignment(FakeText.TextAlignment alignment) {
            settings.setAlignment(alignment);
            return this;
        }

        @Override
        public FakeText.Builder shadow(boolean shadow) {
            settings.setShadow(shadow);
            return this;
        }

        @Override
        public FakeText.Builder seeThrough(boolean seeThrough) {
            settings.setSeeThrough(seeThrough);
            return this;
        }

        @Override
        public FakeText.Builder lineWidth(int width) {
            settings.setLineWidth(width);
            return this;
        }

        @Override
        public FakeText.Builder viewRange(float range) {
            settings.setViewRange(range);
            return this;
        }

        @Override
        public FakeText.Builder scale(float scale) {
            settings.setScale(scale);
            return this;
        }

        @Override
        public FakeText.Builder translation(float x, float y, float z) {
            settings.setTranslation(x, y, z);
            return this;
        }

        @Override
        public FakeText build(Location location) {
            return adapter.createFakeText(location, settings);
        }
    }

    /**
     * Default implementation of FakeItem.Builder.
     */
    private static class FakeItemBuilderImpl implements FakeItem.Builder {
        private final NMSAdapter adapter;
        private final FakeItemSettings settings = new FakeItemSettings();

        FakeItemBuilderImpl(NMSAdapter adapter) {
            this.adapter = adapter;
        }

        @Override
        public FakeItem.Builder itemStack(ItemStack itemStack) {
            settings.setItemStack(itemStack);
            return this;
        }

        @Override
        public FakeItem.Builder billboard(org.bukkit.entity.Display.Billboard billboard) {
            settings.setBillboard(billboard);
            return this;
        }

        @Override
        public FakeItem.Builder scale(float scale) {
            settings.setScale(scale);
            return this;
        }

        @Override
        public FakeItem.Builder viewRange(float range) {
            settings.setViewRange(range);
            return this;
        }

        @Override
        public FakeItem.Builder glowing(boolean glowing) {
            settings.setGlowing(glowing);
            return this;
        }

        @Override
        public FakeItem.Builder customName(String name) {
            settings.setCustomName(name);
            return this;
        }

        @Override
        public FakeItem.Builder customNameVisible(boolean visible) {
            settings.setCustomNameVisible(visible);
            return this;
        }

        @Override
        public FakeItem build(Location location) {
            return adapter.createFakeItem(location, settings);
        }
    }

    private static class FakeCustomEntityBuilderImpl implements FakeCustomEntity.Builder {
        private final NMSAdapter adapter;
        private final CustomEntityDefinition.Builder definitionBuilder =
                CustomEntityDefinition.builder("magmacore:custom_entity");

        FakeCustomEntityBuilderImpl(NMSAdapter adapter) {
            this.adapter = adapter;
        }

        @Override
        public FakeCustomEntity.Builder identifier(String identifier) {
            definitionBuilder.identifier(identifier);
            return this;
        }

        @Override
        public FakeCustomEntity.Builder carrierEntityType(EntityType entityType) {
            definitionBuilder.carrierEntityType(entityType);
            return this;
        }

        @Override
        public FakeCustomEntity.Builder dimensions(float width, float height) {
            definitionBuilder.dimensions(width, height);
            return this;
        }

        @Override
        public FakeCustomEntity.Builder scale(float scale) {
            definitionBuilder.scale(scale);
            return this;
        }

        @Override
        public FakeCustomEntity.Builder color(Integer color) {
            definitionBuilder.color(color);
            return this;
        }

        @Override
        public FakeCustomEntity.Builder variant(Integer variant) {
            definitionBuilder.variant(variant);
            return this;
        }

        @Override
        public FakeCustomEntity.Builder tracked(boolean tracked) {
            definitionBuilder.tracked(tracked);
            return this;
        }

        @Override
        public FakeCustomEntity.Builder propertySchema(CustomEntityPropertySchema schema) {
            definitionBuilder.propertySchema(schema);
            return this;
        }

        @Override
        public FakeCustomEntity.Builder onShow(CustomEntityViewerHook showHook) {
            definitionBuilder.onShow(showHook);
            return this;
        }

        @Override
        public FakeCustomEntity.Builder onHide(CustomEntityViewerHook hideHook) {
            definitionBuilder.onHide(hideHook);
            return this;
        }

        @Override
        public FakeCustomEntity.Builder onRemove(CustomEntityLifecycleHook removeHook) {
            definitionBuilder.onRemove(removeHook);
            return this;
        }

        @Override
        public FakeCustomEntity build(Location location) {
            return adapter.createFakeCustomEntity(location, definitionBuilder.build());
        }
    }

    private static class BukkitCustomEntityBuilderImpl implements BukkitCustomEntity.Builder {
        private final NMSAdapter adapter;
        private final CustomEntityDefinition.Builder definitionBuilder =
                CustomEntityDefinition.builder("magmacore:custom_entity");
        private boolean carrierEntityTypeSet;

        BukkitCustomEntityBuilderImpl(NMSAdapter adapter) {
            this.adapter = adapter;
        }

        @Override
        public BukkitCustomEntity.Builder identifier(String identifier) {
            definitionBuilder.identifier(identifier);
            return this;
        }

        @Override
        public BukkitCustomEntity.Builder carrierEntityType(EntityType entityType) {
            definitionBuilder.carrierEntityType(entityType);
            carrierEntityTypeSet = true;
            return this;
        }

        @Override
        public BukkitCustomEntity.Builder dimensions(float width, float height) {
            definitionBuilder.dimensions(width, height);
            return this;
        }

        @Override
        public BukkitCustomEntity.Builder scale(float scale) {
            definitionBuilder.scale(scale);
            return this;
        }

        @Override
        public BukkitCustomEntity.Builder color(Integer color) {
            definitionBuilder.color(color);
            return this;
        }

        @Override
        public BukkitCustomEntity.Builder variant(Integer variant) {
            definitionBuilder.variant(variant);
            return this;
        }

        @Override
        public BukkitCustomEntity.Builder tracked(boolean tracked) {
            definitionBuilder.tracked(tracked);
            return this;
        }

        @Override
        public BukkitCustomEntity.Builder propertySchema(CustomEntityPropertySchema schema) {
            definitionBuilder.propertySchema(schema);
            return this;
        }

        @Override
        public BukkitCustomEntity.Builder onShow(CustomEntityViewerHook showHook) {
            definitionBuilder.onShow(showHook);
            return this;
        }

        @Override
        public BukkitCustomEntity.Builder onHide(CustomEntityViewerHook hideHook) {
            definitionBuilder.onHide(hideHook);
            return this;
        }

        @Override
        public BukkitCustomEntity.Builder onRemove(CustomEntityLifecycleHook removeHook) {
            definitionBuilder.onRemove(removeHook);
            return this;
        }

        @Override
        public BukkitCustomEntity build(Entity entity) {
            if (entity == null) throw new IllegalArgumentException("entity cannot be null");
            if (!carrierEntityTypeSet) {
                definitionBuilder.carrierEntityType(entity.getType());
            }
            return adapter.createBukkitCustomEntity(entity, definitionBuilder.build());
        }
    }

}
