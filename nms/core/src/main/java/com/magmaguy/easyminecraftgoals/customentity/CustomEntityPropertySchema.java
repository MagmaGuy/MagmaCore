package com.magmaguy.easyminecraftgoals.customentity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CustomEntityPropertySchema {
    public static final int MAX_PROPERTIES = 32;

    private static final CustomEntityPropertySchema EMPTY =
            new CustomEntityPropertySchema(List.of());

    private final List<CustomEntityPropertyDefinition> properties;
    private final Map<String, CustomEntityPropertyDefinition> byIdentifier;

    private CustomEntityPropertySchema(List<CustomEntityPropertyDefinition> properties) {
        this.properties = List.copyOf(properties);
        Map<String, CustomEntityPropertyDefinition> indexed = new LinkedHashMap<>();
        for (CustomEntityPropertyDefinition property : properties) {
            indexed.put(property.identifier(), property);
        }
        this.byIdentifier = Collections.unmodifiableMap(indexed);
    }

    public static CustomEntityPropertySchema empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<CustomEntityPropertyDefinition> properties() {
        return properties;
    }

    public boolean contains(String identifier) {
        return byIdentifier.containsKey(identifier);
    }

    public CustomEntityPropertyDefinition get(String identifier) {
        return byIdentifier.get(identifier);
    }

    public int size() {
        return properties.size();
    }

    public static final class Builder {
        private final Map<String, CustomEntityPropertyDefinition> properties = new LinkedHashMap<>();

        public Builder add(String identifier, CustomEntityPropertyType type) {
            if (properties.containsKey(identifier)) return this;
            if (properties.size() >= MAX_PROPERTIES) {
                throw new IllegalStateException("Bedrock custom entities support at most "
                        + MAX_PROPERTIES + " properties per definition");
            }
            properties.put(identifier, new CustomEntityPropertyDefinition(identifier, type));
            return this;
        }

        public Builder addBoolean(String identifier) {
            return add(identifier, CustomEntityPropertyType.BOOLEAN);
        }

        public Builder addInt(String identifier) {
            return add(identifier, CustomEntityPropertyType.INT);
        }

        public Builder addFloat(String identifier) {
            return add(identifier, CustomEntityPropertyType.FLOAT);
        }

        public Builder addString(String identifier) {
            return add(identifier, CustomEntityPropertyType.STRING);
        }

        public Builder addPackedBooleans(String propertyPrefix, int booleanCount) {
            int propertyCount = PackedBooleanPropertySet.requiredPropertyCount(booleanCount);
            for (int i = 0; i < propertyCount; i++) {
                addInt(propertyPrefix + i);
            }
            return this;
        }

        public CustomEntityPropertySchema build() {
            return new CustomEntityPropertySchema(new ArrayList<>(properties.values()));
        }
    }
}
