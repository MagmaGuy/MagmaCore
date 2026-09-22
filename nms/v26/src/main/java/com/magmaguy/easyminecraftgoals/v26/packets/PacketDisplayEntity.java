package com.magmaguy.easyminecraftgoals.v26.packets;

import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.easyminecraftgoals.internal.PacketModelEntity;
import com.magmaguy.easyminecraftgoals.v26.CraftBukkitBridge;
import com.mojang.math.Transformation;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Display;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.util.EulerAngle;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;


public class PacketDisplayEntity extends AbstractPacketEntity<Display.ItemDisplay> implements PacketModelEntity {
    private static final EntityDataAccessor<Quaternionfc> RIGHT_ROTATION = resolveRightRotationAccessor();

    @SuppressWarnings("unchecked")
    private static EntityDataAccessor<Quaternionfc> resolveRightRotationAccessor() {
        try {
            // 26.x uses Mojang field names on both Paper and Spigot. Resolve once;
            // reading this component must not compose an otherwise unused matrix.
            Field field = Display.class.getDeclaredField("DATA_RIGHT_ROTATION_ID");
            field.setAccessible(true);
            return (EntityDataAccessor<Quaternionfc>) field.get(null);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to access the display right rotation", failure);
        }
    }

    private ItemStack carrierItem;
    private net.minecraft.world.item.ItemStack nmsCarrierItem;
    private Display.ItemDisplay itemDisplay;

    public PacketDisplayEntity(Location location) {
        super(location);
    }

    /** Converts XYZ Euler angles in radians to a quaternion in Rz * Ry * Rx order. */
    private static Quaternionf eulerToQuaternion(double rotationX, double rotationY, double rotationZ) {
        double cy = Math.cos(rotationZ * 0.5);
        double sy = Math.sin(rotationZ * 0.5);
        double cp = Math.cos(rotationY * 0.5);
        double sp = Math.sin(rotationY * 0.5);
        double cr = Math.cos(rotationX * 0.5);
        double sr = Math.sin(rotationX * 0.5);

        double w = cr * cp * cy + sr * sp * sy;
        double x = sr * cp * cy - cr * sp * sy;
        double y = cr * sp * cy + sr * cp * sy;
        double z = cr * cp * sy - sr * sp * cy;

        return new Quaternionf(x, y, z, w);
    }

    @Override
    protected Display.ItemDisplay createEntity(Location location) {
        // This doesn't create a real entity until it gets added to the world, which for packet entity purposes is never
        return new Display.ItemDisplay(PacketEntityTypes.ITEM_DISPLAY, getNMSLevel(location));
    }

    /**
     * Initializes the client-side item model and wires up tinting via CustomModelData colors.
     * Your items JSON should include:
     * "tints": [ { "type": "minecraft:custom_model_data", "index": 0, "default": 16777215 } ]
     * and the bone model's faces should have "tintindex": 0.
     */
    public void initializeModel(Location location, String modelID) {
        itemDisplay = entity;

        // Interpolation defaults (safe on R5)
        itemDisplay.setTransformationInterpolationDelay(-1);
        itemDisplay.setTransformationInterpolationDuration(0);

        // Set teleport interpolation duration
        // Using CraftBukkitBridge so the method call gets properly remapped by specialsource for Spigot
        CraftBukkitBridge.setDisplayTeleportDuration(itemDisplay, 1);

        // Carrier item (any item works; LEATHER_HORSE_ARMOR kept for continuity)
        carrierItem = new ItemStack(Material.LEATHER_HORSE_ARMOR);

        // Set the explicit item model ID (Paper API)
        ItemMeta meta = carrierItem.getItemMeta();
        meta.setItemModel(NamespacedKey.fromString(modelID));

        // Initialize CustomModelData component with a default white tint (index 0)
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setColors(List.of(Color.WHITE)); // index 0 -> tintindex: 0 on your model
        meta.setCustomModelDataComponent(cmd);

        carrierItem.setItemMeta(meta);

        // Hand to the display
        nmsCarrierItem = CraftBukkitBridge.asNMSCopy(carrierItem);
        itemDisplay.setItemStack(nmsCarrierItem);
        itemDisplay.setWidth(0);
        itemDisplay.setHeight(0);
        itemDisplay.setViewRange(30);
    }

    /**
     * Updates the tint color by writing to CustomModelData.colors[0].
     * This is what the client reads for "minecraft:custom_model_data" tints.
     */
    @Override
    public void setHorseLeatherArmorColor(Color color) {
        if (carrierItem == null) return;

        ItemMeta meta = carrierItem.getItemMeta();
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();

        // If the pack ever uses multiple tint slots, add more entries here in index order.
        cmd.setColors(List.of(color));

        meta.setCustomModelDataComponent(cmd);
        carrierItem.setItemMeta(meta);

        nmsCarrierItem = CraftBukkitBridge.asNMSCopy(carrierItem);
        itemDisplay.setItemStack(nmsCarrierItem);
    }

    @Override
    public void sendLocationAndRotationPacket(Location location, EulerAngle eulerAngle) {
        move(location);
        Quaternionf quaternionf = eulerToQuaternion(
                eulerAngle.getX(), eulerAngle.getY(), eulerAngle.getZ());
        rotate(quaternionf);
        sendPacketToAll(createEntityDataPacket());
    }

    @Override
    public void sendLocationAndRotationAndScalePacket(Location location, EulerAngle eulerAngle, float scale) {
        generateLocationAndRotationAndScalePackets(new PacketBundle(), location, eulerAngle, scale).send();
    }

    @Override
    public void sendLocationAndRotationAndScalePacket(Location location, EulerAngle eulerAngle, float scaleX, float scaleY, float scaleZ) {
        generateLocationAndRotationAndScalePackets(new PacketBundle(), location, eulerAngle, scaleX, scaleY, scaleZ).send();
    }

    @Override
    public AbstractPacketBundle generateLocationAndRotationAndScalePackets(
            AbstractPacketBundle packetBundle, Location location, EulerAngle eulerAngle, float scale) {
        return generateLocationAndRotationAndScalePackets(packetBundle, location, eulerAngle, scale, scale, scale);
    }

    @Override
    public AbstractPacketBundle generateLocationAndRotationAndScalePackets(
            AbstractPacketBundle packetBundle, Location location, EulerAngle eulerAngle, float scaleX, float scaleY, float scaleZ) {

        // Both packets belong to the same update and use the same viewer snapshot.
        List<Player> updateViewers = getViewersAsPlayers();
        packetBundle.addPacket(generateMovePacket(location), updateViewers);

        // Always update transformation for rotation/scale
        Quaternionf quaternionf = eulerToQuaternion(
                eulerAngle.getX(), eulerAngle.getY(), eulerAngle.getZ());

        Transformation transformation = new Transformation(
                new Vector3f(0, 0, 0),  // keep translation out of the transformation
                quaternionf,
                new Vector3f(scaleX, scaleY, scaleZ),
                entity.getEntityData().get(RIGHT_ROTATION)
        );

        entity.setTransformation(transformation);

        // Per-tick metadata: send only the changed (dirty) data values instead of the full
        // non-default snapshot, which re-serializes the entire item-display item blob every
        // tick. packDirty() returns just the transformation that actually changed (and null
        // when nothing did). Display-entity bones are shown to Java viewers only — Bedrock V2
        // uses a separate path — so vanilla delta-metadata semantics apply cleanly here.
        // Full snapshots still go out on displayTo (spawn) and the periodic resync.
        if (com.magmaguy.easyminecraftgoals.internal.PacketEntityTuning.useDeltaMetadataUpdates) {
            net.minecraft.network.protocol.Packet<?> dirty = createDirtyEntityDataPacket();
            if (dirty != null) packetBundle.addPacket(dirty, updateViewers);
        } else {
            packetBundle.addPacket(createEntityDataPacket(), updateViewers);
        }

        return packetBundle;
    }

    @Override
    public void displayTo(Player player) {
        super.displayTo(player);
    }

    public void displayTo(UUID player) {
        displayTo(Bukkit.getPlayer(player));
    }

    @Override
    public void addViewer(UUID player) {
        displayTo(player);
    }

    public Vector3f getScale() {
        Vector3fc scale = getTransformation().scale();
        return new Vector3f(scale.x(), scale.y(), scale.z());
    }

    @Override
    public void setScale(float scale) {
        setScale(new Vector3f(scale, scale, scale));
    }

    public void setScale(Vector3f scale) {
        Transformation transformation = getTransformation();
        Transformation newTransformation = new Transformation(
                transformation.translation(),
                transformation.leftRotation(),
                scale,
                transformation.rightRotation());
        setTransformation(newTransformation);
    }

    public Vector3f getTranslation() {
        Vector3fc translation = getTransformation().translation();
        return new Vector3f(translation.x(), translation.y(), translation.z());
    }

    public void setTranslation(Vector3f translation) {
        Transformation transformation = getTransformation();
        Transformation newTransformation = new Transformation(
                translation,
                transformation.leftRotation(),
                transformation.scale(),
                transformation.rightRotation());
        setTransformation(newTransformation);
    }

    public Quaternionf getLeftRotation() {
        Quaternionfc leftRotation = getTransformation().leftRotation();
        return new Quaternionf(leftRotation.x(), leftRotation.y(), leftRotation.z(), leftRotation.w());
    }

    public void setLeftRotation(Quaternionf rotation) {
        Transformation transformation = getTransformation();
        Transformation newTransformation = new Transformation(
                transformation.translation(),
                rotation,
                transformation.scale(),
                transformation.rightRotation());
        setTransformation(newTransformation);
    }

    public Quaternionf getRightRotation() {
        Quaternionfc rightRotation = entity.getEntityData().get(RIGHT_ROTATION);
        return new Quaternionf(rightRotation.x(), rightRotation.y(), rightRotation.z(), rightRotation.w());
    }

    public void setRightRotation(Quaternionf rotation) {
        Transformation transformation = getTransformation();
        Transformation newTransformation = new Transformation(
                transformation.translation(),
                transformation.leftRotation(),
                transformation.scale(),
                rotation);
        setTransformation(newTransformation);
    }

    public Transformation getTransformation() {
        // The native factory already returns a fresh transformation.
        return Display.createTransformation(this.entity.getEntityData());
    }

    private void setTransformation(Transformation transformation) {
        entity.setTransformation(transformation);
    }

    private void rotate(Quaternionf rotation) {
        if (rotation == null) return;
        setLeftRotation(rotation);
    }
}
