package com.magmaguy.magmacore.menus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NightbreakSetupIconsTest {

    @AfterEach
    void resetAvailabilitySupplier() {
        NightbreakSetupIcons.setAdditionalResourcePackAvailableSupplier(() -> false);
    }

    @Test
    void acceptsAdditionalResourcePackProvider() {
        NightbreakSetupIcons.setAdditionalResourcePackAvailableSupplier(() -> true);

        assertTrue(NightbreakSetupIcons.hasUsableResourcePack());
    }

    @Test
    void keepsVanillaFallbackWithoutAResourcePackProvider() {
        NightbreakSetupIcons.setAdditionalResourcePackAvailableSupplier(() -> false);

        assertFalse(NightbreakSetupIcons.hasUsableResourcePack());
    }

    @Test
    void keepsVanillaFallbackWhenProviderCheckFails() {
        NightbreakSetupIcons.setAdditionalResourcePackAvailableSupplier(() -> {
            throw new IllegalStateException("provider unavailable");
        });

        assertFalse(NightbreakSetupIcons.hasUsableResourcePack());
    }
}
