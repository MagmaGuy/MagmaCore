package com.magmaguy.magmacore.ai;

/**
 * Exclusive channels that a running mind behavior may control.
 */
public enum MindControl {
    MOVE,
    LOOK,
    JUMP,
    TARGET,
    ATTACK,
    USE_ITEM,
    /** Exclusive permission to submit semantic requests to the host action sink. */
    ACTION
}
