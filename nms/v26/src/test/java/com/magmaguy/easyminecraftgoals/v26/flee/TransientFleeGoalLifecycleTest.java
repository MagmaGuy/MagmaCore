package com.magmaguy.easyminecraftgoals.v26.flee;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransientFleeGoalLifecycleTest {
    @Test
    void registrationAndIdempotentCloseOnlyAddAndRemoveTheTemporaryGoal() {
        AtomicInteger registrations = new AtomicInteger();
        AtomicInteger removals = new AtomicInteger();
        TransientGoalRegistration registration = new TransientGoalRegistration(
                registrations::incrementAndGet,
                removals::incrementAndGet);

        registration.register();
        registration.register();
        assertEquals(1, registrations.get());

        registration.close();
        registration.close();
        assertEquals(1, removals.get());
    }
}
