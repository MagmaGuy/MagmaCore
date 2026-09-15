package com.magmaguy.easyminecraftgoals.v26.packets;

import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Keeps absolute packet-entity teleports compatible with the 26.3 position-path format. */
final class EntityPositionPackets {
    private static final MethodHandle CREATE = resolveConstructor();

    private EntityPositionPackets() { }

    static ClientboundEntityPositionSyncPacket create(int id, Vec3 position, float yaw, float pitch) {
        try {
            return (ClientboundEntityPositionSyncPacket) CREATE.invokeExact(id, position, yaw, pitch, true);
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to create the entity position packet", failure);
        }
    }

    private static MethodHandle resolveConstructor() {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            try {
                MethodHandle constructor = lookup.findConstructor(ClientboundEntityPositionSyncPacket.class,
                        MethodType.methodType(void.class, int.class, PositionMoveRotation.class, boolean.class));
                MethodHandle position = lookup.findConstructor(PositionMoveRotation.class,
                        MethodType.methodType(void.class, Vec3.class, Vec3.class, float.class, float.class));
                position = MethodHandles.insertArguments(position, 1, Vec3.ZERO);
                return MethodHandles.collectArguments(constructor, 1, position);
            } catch (NoSuchMethodException legacyConstructorAbsent) {
                Class<?> positionPath = Class.forName("net.minecraft.world.entity.PositionPath");
                MethodHandle constructor = lookup.findConstructor(ClientboundEntityPositionSyncPacket.class,
                        MethodType.methodType(void.class, int.class, positionPath, float.class, float.class, boolean.class));
                MethodHandle position = lookup.findStatic(positionPath, "of",
                        MethodType.methodType(positionPath, Vec3.class));
                return MethodHandles.filterArguments(constructor, 1, position);
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to resolve the entity position packet constructor", failure);
        }
    }
}
