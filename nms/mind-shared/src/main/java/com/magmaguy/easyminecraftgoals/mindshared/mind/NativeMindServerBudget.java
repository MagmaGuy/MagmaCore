package com.magmaguy.easyminecraftgoals.mindshared.mind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

final class NativeMindServerBudget {
    private int serverTick = Integer.MIN_VALUE;
    private long spentNanos;
    private int nextFirstSession;

    /** Returns one immutable round-robin view of the current insertion-ordered sessions. */
    <T> List<T> fairOrder(List<T> sessions) {
        Objects.requireNonNull(sessions, "sessions");
        if (sessions.isEmpty()) return List.of();
        int first = Math.floorMod(nextFirstSession, sessions.size());
        nextFirstSession = first + 1 == sessions.size() ? 0 : first + 1;
        ArrayList<T> ordered = new ArrayList<>(sessions.size());
        for (int offset = 0; offset < sessions.size(); offset++) {
            ordered.add(sessions.get((first + offset) % sessions.size()));
        }
        return Collections.unmodifiableList(ordered);
    }

    boolean permits(int currentServerTick, long maximumNanos) {
        resetIfNeeded(currentServerTick);
        return spentNanos < maximumNanos;
    }

    void record(int currentServerTick, long elapsedNanos) {
        resetIfNeeded(currentServerTick);
        long next = spentNanos + Math.max(0L, elapsedNanos);
        spentNanos = next < spentNanos ? Long.MAX_VALUE : next;
    }

    private void resetIfNeeded(int currentServerTick) {
        if (serverTick == currentServerTick) return;
        serverTick = currentServerTick;
        spentNanos = 0L;
    }
}
