package com.magmaguy.magmacore.ai;

/**
 * Memory migration policy used when a running mind program is replaced.
 */
public enum StateTransfer {
    NONE,
    COMPATIBLE,
    PERSISTENT_ONLY
}
