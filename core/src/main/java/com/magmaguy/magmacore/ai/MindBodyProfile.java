package com.magmaguy.magmacore.ai;

import java.util.Objects;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Version-independent physical and locomotion request for a native Mind body. Scale affects the
 * complete hitbox uniformly. {@code entityCollidable} controls collisions with other entities;
 * block collision support is reported separately by {@link MindBodyCapabilities}. {@code swimWave}
 * is the rise and dip an {@link MindBodyLocomotion#AMPHIBIOUS} body swims along in water; other
 * locomotions ignore it. {@code breathesUnderwater} keeps the body's air full under water, as
 * {@link MindBodyLocomotion#AQUATIC} and {@link MindBodyLocomotion#AMPHIBIOUS} bodies always are:
 * a flying jellyfish whose carrier would drown.
 */
public record MindBodyProfile(
        MindBodyLocomotion locomotion,
        double uniformScale,
        boolean entityCollidable,
        String carrierType,
        MindSwimWave swimWave,
        boolean breathesUnderwater) {

    public static final String DEFAULT_CARRIER_TYPE = "minecraft:zombie";
    private static final Pattern CARRIER_KEY =
            Pattern.compile("[a-z0-9._-]+:[a-z0-9/._-]+");

    public static final MindBodyProfile GROUNDED =
            new MindBodyProfile(
                    MindBodyLocomotion.GROUNDED,
                    1.0D,
                    true,
                    DEFAULT_CARRIER_TYPE);

    /** A body that breathes under water only if its locomotion does. */
    public MindBodyProfile(
            MindBodyLocomotion locomotion,
            double uniformScale,
            boolean entityCollidable,
            String carrierType,
            MindSwimWave swimWave) {
        this(locomotion, uniformScale, entityCollidable, carrierType, swimWave, false);
    }

    /** A body that swims level, as every body did before swim waves. */
    public MindBodyProfile(
            MindBodyLocomotion locomotion,
            double uniformScale,
            boolean entityCollidable,
            String carrierType) {
        this(locomotion, uniformScale, entityCollidable, carrierType, MindSwimWave.NONE);
    }

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
        if (swimWave == null) swimWave = MindSwimWave.NONE;
    }

    /** This profile swimming along the given wave in water. */
    public MindBodyProfile withSwimWave(MindSwimWave wave) {
        return new MindBodyProfile(locomotion, uniformScale, entityCollidable, carrierType, wave, breathesUnderwater);
    }

    /** This profile with its air kept full under water, or left to vanilla. */
    public MindBodyProfile withBreathesUnderwater(boolean breathes) {
        return new MindBodyProfile(locomotion, uniformScale, entityCollidable, carrierType, swimWave, breathes);
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
