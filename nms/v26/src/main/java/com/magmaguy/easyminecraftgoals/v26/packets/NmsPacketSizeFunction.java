package com.magmaguy.easyminecraftgoals.v26.packets;

import com.magmaguy.easyminecraftgoals.internal.PacketSizeEstimator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Measures the REAL serialized size of a packet by encoding it through its public
 * {@code STREAM_CODEC} into a throwaway buffer and reading the byte count. Used by the
 * FMM packet sampler to report true per-tick bandwidth instead of a flat estimate.
 *
 * <p>Resolution is reflective (the {@code STREAM_CODEC} static field is the modern NMS
 * convention) and fully defensive: any packet whose codec can't be found or whose encode
 * throws yields {@code -1}, and the caller falls back to its own estimate. Codecs are
 * cached per packet class. This runs only while the sampler is armed, so the reflective
 * cost is irrelevant.</p>
 */
public final class NmsPacketSizeFunction implements PacketSizeEstimator.PacketSizeFunction {

    // Sentinel so we can cache "this class has no usable STREAM_CODEC" in a null-hostile map.
    private static final StreamCodec<?, ?> NO_CODEC = StreamCodec.of((b, v) -> {
    }, b -> null);

    private final Map<Class<?>, StreamCodec<?, ?>> codecCache = new ConcurrentHashMap<>();
    private volatile RegistryAccess registryAccess;

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public int sizeOf(Object packetObj) {
        if (!(packetObj instanceof Packet<?> packet)) return -1;

        StreamCodec codec = resolveCodec(packet.getClass());
        if (codec == null || codec == NO_CODEC) return -1;

        RegistryAccess access = registryAccess();
        if (access == null) return -1;

        ByteBuf raw = Unpooled.buffer();
        try {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(raw, access);
            codec.encode(buf, packet);
            return buf.readableBytes();
        } catch (Throwable t) {
            return -1;
        } finally {
            raw.release();
        }
    }

    private StreamCodec<?, ?> resolveCodec(Class<?> clazz) {
        StreamCodec<?, ?> cached = codecCache.get(clazz);
        if (cached != null) return cached;
        StreamCodec<?, ?> resolved = NO_CODEC;
        try {
            Field field = clazz.getField("STREAM_CODEC");
            Object value = field.get(null);
            if (value instanceof StreamCodec<?, ?> sc) resolved = sc;
        } catch (Throwable ignored) {
            // leave as NO_CODEC sentinel
        }
        codecCache.put(clazz, resolved);
        return resolved;
    }

    private RegistryAccess registryAccess() {
        RegistryAccess access = registryAccess;
        if (access != null) return access;
        try {
            MinecraftServer server = MinecraftServer.getServer();
            if (server != null) {
                access = server.registryAccess();
                registryAccess = access;
            }
        } catch (Throwable ignored) {
        }
        return access;
    }
}
