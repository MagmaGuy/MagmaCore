package com.magmaguy.easyminecraftgoals.mindshared.mind;

/** Counts independent temporary movement owners without exposing Mind behavior controls. */
final class NativeMindMovementOverrideGate {
    private int leases;

    Lease acquire() {
        if (leases == Integer.MAX_VALUE) {
            throw new IllegalStateException("Native Mind movement override lease count is exhausted");
        }
        leases++;
        return new Lease();
    }

    boolean isOverridden() {
        return leases > 0;
    }

    final class Lease implements AutoCloseable {
        private boolean closed;

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (leases <= 0) {
                throw new IllegalStateException("Native Mind movement override lease underflow");
            }
            leases--;
        }
    }
}
