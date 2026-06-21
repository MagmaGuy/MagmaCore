package com.magmaguy.easyminecraftgoals.customentity;

import java.util.Objects;

public record CustomEntityPropertyDefinition(String identifier, CustomEntityPropertyType type) {
    public CustomEntityPropertyDefinition {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("Property identifier cannot be blank");
        }
        Objects.requireNonNull(type, "type");
    }
}
