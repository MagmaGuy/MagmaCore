package com.magmaguy.easyminecraftgoals.mindshared.mind;

import com.magmaguy.magmacore.ai.MindPersistentState;
import com.magmaguy.magmacore.ai.MindBodyLocomotion;
import com.magmaguy.magmacore.ai.MindBodyProfile;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class MindCarrierState {
    private static final NamespacedKey BODY = new NamespacedKey("magmacore", "mind_body");
    private static final NamespacedKey HOST = new NamespacedKey("magmacore", "mind_host");
    private static final NamespacedKey LOCOMOTION = new NamespacedKey("magmacore", "mind_locomotion");
    private static final NamespacedKey SCALE = new NamespacedKey("magmacore", "mind_scale");
    private static final NamespacedKey COLLIDABLE = new NamespacedKey("magmacore", "mind_collidable");
    private static final NamespacedKey CARRIER = new NamespacedKey("magmacore", "mind_carrier");
    private static final NamespacedKey OWNER = new NamespacedKey("magmacore", "mind_owner");
    private static final NamespacedKey PROGRAM = new NamespacedKey("magmacore", "mind_program");
    private static final NamespacedKey REVISION = new NamespacedKey("magmacore", "mind_revision");
    private static final NamespacedKey STATE = new NamespacedKey("magmacore", "mind_state");

    private MindCarrierState() {
    }

    static void markBody(LivingEntity entity, String hostIdentity, MindBodyProfile profile) {
        PersistentDataContainer data = entity.getPersistentDataContainer();
        data.set(BODY, PersistentDataType.BYTE, (byte) 1);
        data.set(HOST, PersistentDataType.STRING, hostIdentity);
        data.set(LOCOMOTION, PersistentDataType.STRING, profile.locomotion().name());
        data.set(SCALE, PersistentDataType.DOUBLE, profile.uniformScale());
        data.set(COLLIDABLE, PersistentDataType.BYTE, profile.entityCollidable() ? (byte) 1 : (byte) 0);
        data.set(CARRIER, PersistentDataType.STRING, profile.carrierType());
    }

    static boolean isMarked(LivingEntity entity) {
        return entity.getPersistentDataContainer().has(BODY, PersistentDataType.BYTE);
    }

    static boolean isMarked(LivingEntity entity, String hostIdentity) {
        PersistentDataContainer data = entity.getPersistentDataContainer();
        return isMarked(entity)
                && hostIdentity.equals(data.get(HOST, PersistentDataType.STRING));
    }

    static void write(
            LivingEntity entity,
            String hostIdentity,
            MindBodyProfile profile,
            UUID logicalOwner,
            String programIdentifier,
            long programRevision,
            MindPersistentState persistentState) {
        PersistentDataContainer data = entity.getPersistentDataContainer();
        markBody(entity, hostIdentity, profile);
        data.set(OWNER, PersistentDataType.STRING, logicalOwner.toString());
        data.set(PROGRAM, PersistentDataType.STRING, programIdentifier);
        data.set(REVISION, PersistentDataType.LONG, programRevision);
        data.set(STATE, PersistentDataType.STRING, encode(persistentState));
    }

    static Optional<Stored> read(LivingEntity entity, String hostIdentity) {
        PersistentDataContainer data = entity.getPersistentDataContainer();
        if (!isMarked(entity, hostIdentity)) return Optional.empty();

        String owner = data.get(OWNER, PersistentDataType.STRING);
        String program = data.get(PROGRAM, PersistentDataType.STRING);
        Long revision = data.get(REVISION, PersistentDataType.LONG);
        String state = data.get(STATE, PersistentDataType.STRING);
        if (owner == null || program == null || revision == null || state == null) {
            throw new IllegalStateException("MagmaCore mind carrier has an incomplete rehydration marker");
        }
        try {
            return Optional.of(new Stored(
                    UUID.fromString(owner),
                    program,
                    revision,
                    readProfile(data),
                    decode(state)));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("MagmaCore mind carrier has an invalid rehydration marker", exception);
        }
    }

    static void clear(LivingEntity entity, String hostIdentity) {
        PersistentDataContainer data = entity.getPersistentDataContainer();
        if (!isMarked(entity, hostIdentity)) return;
        data.remove(BODY);
        data.remove(HOST);
        data.remove(LOCOMOTION);
        data.remove(SCALE);
        data.remove(COLLIDABLE);
        data.remove(CARRIER);
        data.remove(OWNER);
        data.remove(PROGRAM);
        data.remove(REVISION);
        data.remove(STATE);
    }

    private static MindBodyProfile readProfile(PersistentDataContainer data) {
        String locomotion = data.get(LOCOMOTION, PersistentDataType.STRING);
        Double scale = data.get(SCALE, PersistentDataType.DOUBLE);
        Byte collidable = data.get(COLLIDABLE, PersistentDataType.BYTE);
        String carrier = data.get(CARRIER, PersistentDataType.STRING);
        if (locomotion == null && scale == null && collidable == null && carrier == null) {
            return MindBodyProfile.GROUNDED;
        }
        if (locomotion == null || scale == null || collidable == null) {
            throw new IllegalStateException("MagmaCore mind carrier has an incomplete body profile");
        }
        try {
            return new MindBodyProfile(
                    MindBodyLocomotion.valueOf(locomotion),
                    scale,
                    collidable != 0,
                    carrier == null ? MindBodyProfile.DEFAULT_CARRIER_TYPE : carrier);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("MagmaCore mind carrier has an invalid body profile", exception);
        }
    }

    private static String encode(MindPersistentState state) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", state.formatVersion());
        yaml.set("memories", state.memories());
        yaml.set("ttl", state.remainingTtlTicks());
        return yaml.saveToString();
    }

    private static MindPersistentState decode(String encoded) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(encoded);
        } catch (InvalidConfigurationException exception) {
            throw new IllegalArgumentException("Persisted mind state is not valid YAML", exception);
        }

        int format = yaml.getInt("format", -1);
        Map<String, Object> memories = sectionValues(yaml.getConfigurationSection("memories"));
        Map<String, Object> rawTtl = sectionValues(yaml.getConfigurationSection("ttl"));
        Map<String, Long> ttl = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : rawTtl.entrySet()) {
            if (!(entry.getValue() instanceof Number number)) {
                throw new IllegalArgumentException("Persisted mind TTL is not numeric: " + entry.getKey());
            }
            ttl.put(entry.getKey(), number.longValue());
        }
        return new MindPersistentState(format, memories, ttl);
    }

    private static Map<String, Object> sectionValues(ConfigurationSection section) {
        if (section == null) return Map.of();
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            values.put(key, normalize(section.get(key)));
        }
        return values;
    }

    private static Object normalize(Object value) {
        if (value instanceof ConfigurationSection section) return sectionValues(section);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), normalize(entry.getValue()));
            }
            return normalized;
        }
        if (value instanceof List<?> list) {
            List<Object> normalized = new ArrayList<>(list.size());
            for (Object entry : list) normalized.add(normalize(entry));
            return normalized;
        }
        return value;
    }

    record Stored(
            UUID logicalOwner,
            String programIdentifier,
            long programRevision,
            MindBodyProfile profile,
            MindPersistentState persistentState) {
    }
}
