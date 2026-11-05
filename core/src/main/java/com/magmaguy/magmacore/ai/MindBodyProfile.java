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
 * a flying jellyfish whose carrier would drown. {@code swimSpeed} is how fast an
 * {@link MindBodyLocomotion#AMPHIBIOUS} body swims in water, as a multiple of the speed it walks
 * on grass ({@link #DEFAULT_SWIM_SPEED} unless set).
 */
public record MindBodyProfile(
        MindBodyLocomotion locomotion,
        double uniformScale,
        boolean entityCollidable,
        String carrierType,
        MindSwimWave swimWave,
        boolean breathesUnderwater,
        double swimSpeed) {

    public static final String DEFAULT_CARRIER_TYPE = "minecraft:zombie";
    /** Half again its grass walk: how fast an amphibious body swims unless its profile says otherwise. */
    public static final double DEFAULT_SWIM_SPEED = 1.5D;
    private static final Pattern CARRIER_KEY =
            Pattern.compile("[a-z0-9._-]+:[a-z0-9/._-]+");

    public static final MindBodyProfile GROUNDED =
            new MindBodyProfile(
                    MindBodyLocomotion.GROUNDED,
                    1.0D,
                    true,
                    DEFAULT_CARRIER_TYPE);

    /** A body that swims at the default speed. */
    public MindBodyProfile(
            MindBodyLocomotion locomotion,
            double uniformScale,
            boolean entityCollidable,
            String carrierType,
            MindSwimWave swimWave,
            boolean breathesUnderwater) {
        this(locomotion, uniformScale, entityCollidable, carrierType, swimWave, breathesUnderwater, DEFAULT_SWIM_SPEED);
    }

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
        if (!Double.isFinite(swimSpeed) || swimSpeed <= 0.0D) swimSpeed = DEFAULT_SWIM_SPEED;
    }

    /** This profile swimming along the given wave in water. */
    public MindBodyProfile withSwimWave(MindSwimWave wave) {
        return new MindBodyProfile(locomotion, uniformScale, entityCollidable, carrierType, wave, breathesUnderwater, swimSpeed);
    }

    /** This profile with its air kept full under water, or left to vanilla. */
    public MindBodyProfile withBreathesUnderwater(boolean breathes) {
        return new MindBodyProfile(locomotion, uniformScale, entityCollidable, carrierType, swimWave, breathes, swimSpeed);
    }

    /** This profile swimming at the given multiple of its grass walk; 0 or less swims at the default. */
    public MindBodyProfile withSwimSpeed(double speed) {
        return new MindBodyProfile(locomotion, uniformScale, entityCollidable, carrierType, swimWave, breathesUnderwater, speed);
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
