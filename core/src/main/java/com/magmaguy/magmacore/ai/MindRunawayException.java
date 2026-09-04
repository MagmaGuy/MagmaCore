package com.magmaguy.magmacore.ai;

/** Signals that a hard script runaway limit interrupted a Mind callback before completion. */
public final class MindRunawayException extends RuntimeException {
    public MindRunawayException(String message, Throwable cause) {
        super(message, cause);
    }
}
