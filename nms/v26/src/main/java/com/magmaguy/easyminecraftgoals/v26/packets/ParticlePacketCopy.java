package com.magmaguy.easyminecraftgoals.v26.packets;

import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;

/** Rebuilds a particle packet with a different count while preserving its other values. */
final class ParticlePacketCopy {
    private static final ParticlePacketCopy INSTANCE = resolve();

    private final Constructor<ClientboundLevelParticlesPacket> constructor;
    private final Field[] fields;
    private final int countIndex;

    private ParticlePacketCopy(Constructor<ClientboundLevelParticlesPacket> constructor, Field[] fields, int countIndex) {
        this.constructor = constructor;
        this.fields = fields;
        this.countIndex = countIndex;
    }

    static ClientboundLevelParticlesPacket withCount(ClientboundLevelParticlesPacket packet, int count)
            throws ReflectiveOperationException {
        Object[] values = new Object[INSTANCE.fields.length];
        for (int index = 0; index < values.length; index++) {
            values[index] = index == INSTANCE.countIndex ? count : INSTANCE.fields[index].get(packet);
        }
        return INSTANCE.constructor.newInstance(values);
    }

    private static ParticlePacketCopy resolve() {
        Class<ClientboundLevelParticlesPacket> packetClass = ClientboundLevelParticlesPacket.class;
        // 26.3 adds per-axis speeds and randomization. The canonical record constructor
        // preserves those components as well as any later additions to this packet.
        RecordComponent[] components = packetClass.getRecordComponents();
        String[] names = components == null
                ? new String[]{"particle", "overrideLimiter", "alwaysShow", "x", "y", "z",
                "xDist", "yDist", "zDist", "maxSpeed", "count"}
                : Arrays.stream(components).map(RecordComponent::getName).toArray(String[]::new);
        try {
            Field[] fields = new Field[names.length];
            Class<?>[] types = new Class<?>[names.length];
            int countIndex = -1;
            for (int index = 0; index < names.length; index++) {
                Field field = packetClass.getDeclaredField(names[index]);
                field.setAccessible(true);
                fields[index] = field;
                types[index] = field.getType();
                if (names[index].equals("count") && field.getType() == int.class) countIndex = index;
            }
            if (countIndex == -1) throw new NoSuchFieldException("Particle packet count");
            return new ParticlePacketCopy(packetClass.getConstructor(types), fields, countIndex);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to resolve particle packet reconstruction", failure);
        }
    }
}
