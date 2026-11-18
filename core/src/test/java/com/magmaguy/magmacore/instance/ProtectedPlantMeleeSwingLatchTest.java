package com.magmaguy.magmacore.instance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One hit per swing: a held button's repeated digs are one swing, a later press is a new one. */
class ProtectedPlantMeleeSwingLatchTest {

    @Test
    void theFirstDigIsANewSwing() {
        assertTrue(new ProtectedPlantMelee.SwingLatch().isNewSwing(100, 5_000, 20));
    }

    @Test
    void aHeldButtonOnAGoodConnectionIsOneSwing() {
        // The client resends the dig about a round trip plus two or three ticks after the last one.
        assertEquals(1, newSwings(new ProtectedPlantMelee.SwingLatch(), 40, 3, 150, 20));
    }

    @Test
    void aHeldButtonOnASlowConnectionIsOneSwing() {
        assertEquals(1, newSwings(new ProtectedPlantMelee.SwingLatch(), 40, 9, 450, 200));
        assertEquals(1, newSwings(new ProtectedPlantMelee.SwingLatch(), 40, 15, 750, 500));
    }

    @Test
    void aHeldButtonOnALaggingServerIsOneSwing() {
        // Few ticks pass while much time does: the tick window alone keeps the swing together.
        assertEquals(1, newSwings(new ProtectedPlantMelee.SwingLatch(), 20, 3, 900, 20));
    }

    @Test
    void aBurstOfServerTicksDoesNotSplitAHeldSwing() {
        // Many ticks pass in little time while the server catches up: the time window keeps the swing together.
        assertEquals(1, newSwings(new ProtectedPlantMelee.SwingLatch(), 20, 12, 120, 20));
    }

    @Test
    void pressesAtASwordsFullChargePaceAreEachANewSwing() {
        assertEquals(10, newSwings(new ProtectedPlantMelee.SwingLatch(), 10, 13, 650, 50));
    }

    @Test
    void aPressAfterAHeldSwingIsANewSwingOnlyOnceTheHoldHasEnded() {
        ProtectedPlantMelee.SwingLatch latch = new ProtectedPlantMelee.SwingLatch();
        assertEquals(1, newSwings(latch, 10, 3, 150, 20));
        // The hold's last dig was at tick 27, 1350 ms. Inside the window nothing new starts.
        assertFalse(latch.isNewSwing(31, 1_550, 20));
        // Each refused dig moves the window, so the pause is counted from the last dig seen.
        assertFalse(latch.isNewSwing(38, 1_900, 20));
        assertTrue(latch.isNewSwing(47, 2_350, 20));
    }

    @Test
    void anUnknownPingIsTreatedAsNone() {
        ProtectedPlantMelee.SwingLatch latch = new ProtectedPlantMelee.SwingLatch();
        assertTrue(latch.isNewSwing(0, 0, -1));
        assertFalse(latch.isNewSwing(6, 300, -1));
        assertTrue(latch.isNewSwing(13, 650, -1));
    }

    private static int newSwings(ProtectedPlantMelee.SwingLatch latch, int digs, int ticksApart, int millisApart, int ping) {
        int count = 0;
        for (int dig = 0; dig < digs; dig++)
            if (latch.isNewSwing((long) dig * ticksApart, (long) dig * millisApart, ping)) count++;
        return count;
    }
}
