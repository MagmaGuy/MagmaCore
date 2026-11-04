package com.magmaguy.magmacore.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A body profile keeps every setting when one of its with-methods changes another. */
class MindBodyProfileTest {
    private static final MindSwimWave WAVE = new MindSwimWave(0.5D, 40);

    @Test
    void aBodyRunsOutOfAirUnderWaterUnlessItsProfileSaysItBreathesThere() {
        MindBodyProfile profile = new MindBodyProfile(MindBodyLocomotion.FLYING, 1D, true, "minecraft:allay");
        assertFalse(profile.breathesUnderwater());
        assertTrue(profile.withBreathesUnderwater(true).breathesUnderwater());
    }

    @Test
    void breathingUnderwaterKeepsTheRestOfTheProfile() {
        MindBodyProfile wavy = new MindBodyProfile(MindBodyLocomotion.AMPHIBIOUS, 2D, false, "minecraft:horse")
                .withSwimWave(WAVE);
        MindBodyProfile breathing = wavy.withBreathesUnderwater(true);
        assertEquals(MindBodyLocomotion.AMPHIBIOUS, breathing.locomotion());
        assertEquals(2D, breathing.uniformScale());
        assertFalse(breathing.entityCollidable());
        assertEquals("minecraft:horse", breathing.carrierType());
        assertEquals(WAVE, breathing.swimWave());
    }

    @Test
    void aSwimWaveKeepsBreathingUnderwater() {
        MindBodyProfile profile = new MindBodyProfile(MindBodyLocomotion.FLYING, 1D, true, "minecraft:allay")
                .withBreathesUnderwater(true).withSwimWave(WAVE);
        assertTrue(profile.breathesUnderwater());
        assertEquals(WAVE, profile.swimWave());
    }
}
