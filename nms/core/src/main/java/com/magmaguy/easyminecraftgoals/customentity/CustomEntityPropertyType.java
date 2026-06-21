package com.magmaguy.easyminecraftgoals.customentity;

public enum CustomEntityPropertyType {
    BOOLEAN(Boolean.class),
    INT(Integer.class),
    FLOAT(Float.class),
    STRING(String.class);

    private final Class<?> valueClass;

    CustomEntityPropertyType(Class<?> valueClass) {
        this.valueClass = valueClass;
    }

    public Class<?> valueClass() {
        return valueClass;
    }
}
