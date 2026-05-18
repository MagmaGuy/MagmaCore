package com.magmaguy.easyminecraftgoals.internal;

/**
 * Global cap for outbound {@code ClientboundLevelParticlesPacket} particles of
 * type {@code DAMAGE_INDICATOR}.
 * <p>
 * Vanilla {@code Player.attack(Entity)} spawns one damage-indicator particle per
 * two points of damage dealt. Plugins that boost player melee damage into the
 * five- to seven-figure range therefore broadcast a single particle packet whose
 * {@code count} field asks the client to instantiate hundreds of thousands of
 * particles locally, which freezes the client.
 * <p>
 * When {@link #getMaxParticles()} returns a positive value, the per-version
 * netty handler installed by {@code PacketInteractionListener} mutates any
 * outbound damage-indicator particle packet whose count exceeds the cap so it
 * carries the cap value instead. Other particle types and packets with a count
 * within the cap pass through untouched.
 */
public final class DamageIndicatorClamp {

    private static volatile int maxParticles = 0;

    private DamageIndicatorClamp() {
    }

    public static int getMaxParticles() {
        return maxParticles;
    }

    public static void setMaxParticles(int max) {
        maxParticles = max;
    }

    public static boolean isEnabled() {
        return maxParticles > 0;
    }
}
