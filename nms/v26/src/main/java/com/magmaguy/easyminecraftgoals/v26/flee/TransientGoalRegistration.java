package com.magmaguy.easyminecraftgoals.v26.flee;

import java.util.Objects;

/** Idempotent ownership boundary for one temporary GoalSelector entry. */
final class TransientGoalRegistration implements AutoCloseable {
    private final Runnable registerAction;
    private final Runnable unregisterAction;
    private boolean registered;

    TransientGoalRegistration(Runnable registerAction, Runnable unregisterAction) {
        this.registerAction = Objects.requireNonNull(registerAction, "registerAction");
        this.unregisterAction = Objects.requireNonNull(unregisterAction, "unregisterAction");
    }

    void register() {
        if (registered) return;
        registerAction.run();
        registered = true;
    }

    @Override
    public void close() {
        if (!registered) return;
        registered = false;
        unregisterAction.run();
    }
}
