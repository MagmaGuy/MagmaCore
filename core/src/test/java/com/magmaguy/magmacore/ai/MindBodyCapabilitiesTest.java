package com.magmaguy.magmacore.ai;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MindBodyCapabilitiesTest {
    private static final MindBodyCapabilities CAPABILITIES = new MindBodyCapabilities(
            EnumSet.allOf(MindBodyLocomotion.class),
            EnumSet.of(
                    MindBodyLocomotion.GROUNDED,
                    MindBodyLocomotion.FLYING,
                    MindBodyLocomotion.AQUATIC,
                    MindBodyLocomotion.AMPHIBIOUS),
            true,
            0.0625D,
            16.0D,
            false,
            true,
            false);

    @Test
    void acceptsEverySupportedProfileAndCollisionMode() {
        for (MindBodyLocomotion locomotion : MindBodyLocomotion.values()) {
            assertDoesNotThrow(() -> CAPABILITIES.validate(
                    new MindBodyProfile(locomotion, 2.0D, false)));
        }
        assertTrue(CAPABILITIES.canPathfind(MindBodyLocomotion.FLYING));
        assertTrue(CAPABILITIES.canPathfind(MindBodyLocomotion.AMPHIBIOUS));
        assertFalse(CAPABILITIES.canPathfind(MindBodyLocomotion.STATIONARY));
        assertFalse(CAPABILITIES.independentDimensions());
        assertFalse(CAPABILITIES.blockCollisionToggle());
    }

    @Test
    void rejectsScaleOutsideNativeBounds() {
        assertThrows(IllegalArgumentException.class, () -> CAPABILITIES.validate(
                new MindBodyProfile(MindBodyLocomotion.GROUNDED, 16.1D, true)));
    }

    @Test
    void rejectsDisabledCollisionWhenAdapterCannotToggleIt() {
        MindBodyCapabilities fixedCollision = new MindBodyCapabilities(
                EnumSet.of(MindBodyLocomotion.GROUNDED),
                EnumSet.of(MindBodyLocomotion.GROUNDED),
                false,
                1.0D,
                1.0D,
                false,
                false,
                false);

        assertThrows(IllegalArgumentException.class, () -> fixedCollision.validate(
                new MindBodyProfile(MindBodyLocomotion.GROUNDED, 1.0D, false)));
    }
}
