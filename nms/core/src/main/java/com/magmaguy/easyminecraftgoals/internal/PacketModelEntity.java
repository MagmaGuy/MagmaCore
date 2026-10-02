package com.magmaguy.easyminecraftgoals.internal;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.util.EulerAngle;

public interface PacketModelEntity extends PacketEntityInterface {
    void sendLocationAndRotationPacket(Location location, EulerAngle eulerAngle);

    void sendLocationAndRotationAndScalePacket(Location location, EulerAngle eulerAngle, float scale);

    default void sendLocationAndRotationAndScalePacket(Location location, EulerAngle eulerAngle, float scaleX, float scaleY, float scaleZ) {
        sendLocationAndRotationAndScalePacket(location, eulerAngle, scaleX);
    }

    AbstractPacketBundle generateLocationAndRotationAndScalePackets(AbstractPacketBundle packetBundle, Location location, EulerAngle eulerAngle, float scale);

    default AbstractPacketBundle generateLocationAndRotationAndScalePackets(AbstractPacketBundle packetBundle, Location location, EulerAngle eulerAngle, float scaleX, float scaleY, float scaleZ) {
        return generateLocationAndRotationAndScalePackets(packetBundle, location, eulerAngle, scaleX);
    }

    default void initializeModel(Location location, int modelID) {
        throw new UnsupportedOperationException("Integer modelID not supported by this implementation.");
    }

    default void initializeModel(Location location, String modelID) {
        throw new UnsupportedOperationException("String modelID not supported by this implementation.");
    }

    void setScale(float scale);

    void setHorseLeatherArmorColor(Color color);

    boolean hasViewers();

    // Particle sprite controls. Implementations without item displays ignore them.

    default void setBillboard(org.bukkit.entity.Display.Billboard billboard) {
    }

    /** Fixed light level, 0 to 15, in place of the light at the entity's position. */
    default void setBrightness(int blockLight, int skyLight) {
    }

    default void setViewRange(float range) {
    }

    /**
     * Sets translation, left rotation and scale. Viewers interpolate from what they show now to
     * this target over {@code interpolationTicks}; 0 snaps.
     */
    default void setTransformation(org.joml.Vector3f translation, org.joml.Quaternionf leftRotation,
                                   org.joml.Vector3f scale, int interpolationTicks) {
    }

    /** Swaps the displayed item model and its custom-model-data tint. */
    default void setItemModel(String modelID, Color tint) {
    }

    /** Adds the pending metadata changes for current viewers to the bundle. */
    default void queueMetadataUpdate(AbstractPacketBundle packetBundle) {
    }
}
