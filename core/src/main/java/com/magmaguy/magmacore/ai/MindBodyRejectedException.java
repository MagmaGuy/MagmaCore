package com.magmaguy.magmacore.ai;

/**
 * Signals that the server refused to add a prepared Mind body to its world, for example because a
 * plugin cancelled its CreatureSpawnEvent. The host has already removed the unpublished body and
 * closed any Mind attached during preparation, so callers can treat it like any refused spawn.
 */
public final class MindBodyRejectedException extends IllegalStateException {
    public MindBodyRejectedException(String message) {
        super(message);
    }
}
