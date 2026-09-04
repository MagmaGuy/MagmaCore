package com.magmaguy.easyminecraftgoals.v26.mind;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeMindMovementOverrideGateTest {
    @Test
    void independentLeasesKeepMovementOverriddenUntilTheLastLeaseCloses() {
        NativeMindMovementOverrideGate gate = new NativeMindMovementOverrideGate();

        NativeMindMovementOverrideGate.Lease first = gate.acquire();
        NativeMindMovementOverrideGate.Lease second = gate.acquire();
        assertTrue(gate.isOverridden());

        first.close();
        first.close();
        assertTrue(gate.isOverridden());

        second.close();
        assertFalse(gate.isOverridden());
    }
}
