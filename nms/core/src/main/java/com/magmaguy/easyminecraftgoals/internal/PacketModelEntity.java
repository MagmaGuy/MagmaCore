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

    /** How many ticks viewers take to glide to a new position after a move. */
    default void setTeleportDuration(int ticks) {
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

    /** True when viewers can place this display as a passenger of another entity through its transformation. */
    default boolean supportsRiddenPlacement() {
        return false;
    }

    /**
     * Pose of a display that rides another entity. The client puts a passenger at its vehicle's seat every
     * tick, so no position packet goes out: {@code translation} is the world-axis offset from that seat,
     * rotation and scale apply as on the free path, and only the entity data that changed is queued.
     * {@code seat} is where the client will put the display, kept on the server copy so new viewers spawn it
     * there. Viewers interpolate the pose over {@code interpolationTicks}; 0 snaps.
     */
    default void generateRiddenTransformationPackets(AbstractPacketBundle packetBundle, Location seat,
                                                     org.joml.Vector3f translation, EulerAngle eulerAngle,
                                                     float scaleX, float scaleY, float scaleZ, int interpolationTicks) {
    }

    /** True when the ridden pose can send its camera viewer their own view (see below). */
    default boolean supportsCameraView() {
        return false;
    }

    /**
     * The ridden pose, with {@code cameraViewer} (the player steering the vehicle, or null) sent its own view: the
     * pose turned back by {@code modelYaw}, the yaw it was built at, with a vertical billboard. That player's client
     * then turns the display with their camera yaw at once instead of waiting for the server's next pose. Everyone
     * else gets the pose as given. Call every tick while a camera viewer is set, and once more after it clears, so a
     * re-shown display and a former viewer get their view back. Versions without it send the same pose to everyone.
     */
    default void generateRiddenTransformationPackets(AbstractPacketBundle packetBundle, Location seat,
                                                     org.joml.Vector3f translation, EulerAngle eulerAngle,
                                                     float scaleX, float scaleY, float scaleZ, int interpolationTicks,
                                                     java.util.UUID cameraViewer, float modelYaw) {
        generateRiddenTransformationPackets(packetBundle, seat, translation, eulerAngle, scaleX, scaleY, scaleZ,
                interpolationTicks);
    }
}
