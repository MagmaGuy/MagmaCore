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
    void anAmphibiousBodySwimsHalfAgainItsWalkUnlessItsProfileSaysOtherwise() {
        MindBodyProfile profile = new MindBodyProfile(MindBodyLocomotion.AMPHIBIOUS, 1D, true, "minecraft:horse");
        assertEquals(MindBodyProfile.DEFAULT_SWIM_SPEED, profile.swimSpeed());
        assertEquals(1.5D, profile.swimSpeed());
        assertEquals(3D, profile.withSwimSpeed(3D).swimSpeed());
        assertEquals(1.5D, profile.withSwimSpeed(0D).swimSpeed());
        assertEquals(1.5D, profile.withSwimSpeed(-2D).swimSpeed());
        assertEquals(1.5D, profile.withSwimSpeed(Double.NaN).swimSpeed());
    }

    @Test
    void theSwimSpeedAndTheRestOfTheProfileSurviveEachOther() {
        MindBodyProfile fast = new MindBodyProfile(MindBodyLocomotion.AMPHIBIOUS, 2D, false, "minecraft:horse")
                .withSwimSpeed(3D);
        MindBodyProfile changed = fast.withSwimWave(WAVE).withBreathesUnderwater(true);
        assertEquals(3D, changed.swimSpeed());
        MindBodyProfile slower = changed.withSwimSpeed(2D);
        assertEquals(WAVE, slower.swimWave());
        assertTrue(slower.breathesUnderwater());
        assertEquals(2D, slower.uniformScale());
        assertEquals("minecraft:horse", slower.carrierType());
    }

    @Test
    void aSwimWaveKeepsBreathingUnderwater() {
        MindBodyProfile profile = new MindBodyProfile(MindBodyLocomotion.FLYING, 1D, true, "minecraft:allay")
                .withBreathesUnderwater(true).withSwimWave(WAVE);
        assertTrue(profile.breathesUnderwater());
        assertEquals(WAVE, profile.swimWave());
    }
}
