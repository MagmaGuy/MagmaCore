package com.magmaguy.magmacore.ai;

import java.util.Objects;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Version-independent physical and locomotion request for a native Mind body. Scale affects the
 * complete hitbox uniformly. {@code entityCollidable} controls collisions with other entities;
 * block collision support is reported separately by {@link MindBodyCapabilities}.
 */
public record MindBodyProfile(
        MindBodyLocomotion locomotion,
        double uniformScale,
        boolean entityCollidable,
        String carrierType) {

    public static final String DEFAULT_CARRIER_TYPE = "minecraft:zombie";
    private static final Pattern CARRIER_KEY =
            Pattern.compile("[a-z0-9._-]+:[a-z0-9/._-]+");

    public static final MindBodyProfile GROUNDED =
            new MindBodyProfile(
                    MindBodyLocomotion.GROUNDED,
                    1.0D,
                    true,
                    DEFAULT_CARRIER_TYPE);

    /** Source-compatible constructor for the historical neutral zombie carrier. */
    public MindBodyProfile(
            MindBodyLocomotion locomotion,
            double uniformScale,
            boolean entityCollidable) {
        this(locomotion, uniformScale, entityCollidable, DEFAULT_CARRIER_TYPE);
    }

    public MindBodyProfile {
        Objects.requireNonNull(locomotion, "locomotion");
        if (!Double.isFinite(uniformScale) || uniformScale <= 0.0D) {
            throw new IllegalArgumentException("uniformScale must be finite and positive");
        }
        carrierType = Objects.requireNonNull(carrierType, "carrierType")
                .toLowerCase(Locale.ROOT);
        if (!CARRIER_KEY.matcher(carrierType).matches()) {
            throw new IllegalArgumentException("carrierType must be a namespaced Minecraft entity key");
        }
    }

    public static MindBodyProfile standard(MindBodyLocomotion locomotion) {
        return new MindBodyProfile(locomotion, 1.0D, true, DEFAULT_CARRIER_TYPE);
    }

    public static MindBodyProfile forCarrier(
            String carrierType,
            MindBodyLocomotion locomotion) {
        return new MindBodyProfile(locomotion, 1.0D, true, carrierType);
    }
}
