package com.magmaguy.easyminecraftgoals.customentity;

import org.bukkit.entity.EntityType;

public final class CustomEntityDefinition {
    private final String identifier;
    private final EntityType carrierEntityType;
    private final float width;
    private final float height;
    private final float scale;
    private final Integer color;
    private final Integer variant;
    private final boolean tracked;
    private final CustomEntityPropertySchema propertySchema;
    private final CustomEntityViewerHook showHook;
    private final CustomEntityViewerHook hideHook;
    private final CustomEntityLifecycleHook removeHook;

    private CustomEntityDefinition(Builder builder) {
        if (builder.identifier == null || builder.identifier.isBlank()) {
            throw new IllegalStateException("Custom entity identifier is required");
        }
        this.identifier = builder.identifier;
        this.carrierEntityType = builder.carrierEntityType;
        this.width = builder.width;
        this.height = builder.height;
        this.scale = builder.scale;
        this.color = builder.color;
        this.variant = builder.variant;
        this.tracked = builder.tracked;
        this.propertySchema = builder.propertySchema;
        this.showHook = builder.showHook;
        this.hideHook = builder.hideHook;
        this.removeHook = builder.removeHook;
    }

    public static Builder builder(String identifier) {
        return new Builder().identifier(identifier);
    }

    public String identifier() {
        return identifier;
    }

    public EntityType carrierEntityType() {
        return carrierEntityType;
    }

    public float width() {
        return width;
    }

    public float height() {
        return height;
    }

    public float scale() {
        return scale;
    }

    public Integer color() {
        return color;
    }

    public Integer variant() {
        return variant;
    }

    public boolean tracked() {
        return tracked;
    }

    public CustomEntityPropertySchema propertySchema() {
        return propertySchema;
    }

    public CustomEntityViewerHook showHook() {
        return showHook;
    }

    public CustomEntityViewerHook hideHook() {
        return hideHook;
    }

    public CustomEntityLifecycleHook removeHook() {
        return removeHook;
    }

    public static final class Builder {
        private String identifier;
        private EntityType carrierEntityType = EntityType.PIG;
        private float width = 0.1f;
        private float height = 0.1f;
        private float scale = 1.0f;
        private Integer color;
        private Integer variant;
        private boolean tracked;
        private CustomEntityPropertySchema propertySchema = CustomEntityPropertySchema.empty();
        private CustomEntityViewerHook showHook = (entity, player) -> {};
        private CustomEntityViewerHook hideHook = (entity, player) -> {};
        private CustomEntityLifecycleHook removeHook = entity -> {};

        public Builder identifier(String identifier) {
            this.identifier = identifier;
            return this;
        }

        public Builder carrierEntityType(EntityType carrierEntityType) {
            if (carrierEntityType == null) throw new IllegalArgumentException("carrierEntityType cannot be null");
            this.carrierEntityType = carrierEntityType;
            return this;
        }

        public Builder dimensions(float width, float height) {
            if (width < 0 || height < 0) throw new IllegalArgumentException("dimensions cannot be negative");
            this.width = width;
            this.height = height;
            return this;
        }

        public Builder scale(float scale) {
            this.scale = scale;
            return this;
        }

        public Builder color(Integer color) {
            this.color = color;
            return this;
        }

        public Builder variant(Integer variant) {
            this.variant = variant;
            return this;
        }

        public Builder tracked(boolean tracked) {
            this.tracked = tracked;
            return this;
        }

        public Builder propertySchema(CustomEntityPropertySchema propertySchema) {
            this.propertySchema = propertySchema == null ? CustomEntityPropertySchema.empty() : propertySchema;
            return this;
        }

        public Builder onShow(CustomEntityViewerHook showHook) {
            this.showHook = showHook == null ? (entity, player) -> {} : showHook;
            return this;
        }

        public Builder onHide(CustomEntityViewerHook hideHook) {
            this.hideHook = hideHook == null ? (entity, player) -> {} : hideHook;
            return this;
        }

        public Builder onRemove(CustomEntityLifecycleHook removeHook) {
            this.removeHook = removeHook == null ? entity -> {} : removeHook;
            return this;
        }

        public CustomEntityDefinition build() {
            return new CustomEntityDefinition(this);
        }
    }
}
