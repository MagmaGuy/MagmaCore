package com.magmaguy.easyminecraftgoals;

/**
 * Observable state of a {@link PathfindingHandle}.
 */
public enum PathfindingStatus {
    /** No destination is assigned. */
    IDLE,
    /** A destination is assigned, but another goal or combat target currently owns movement. */
    WAITING,
    /** Native navigation is following a complete path. */
    MOVING,
    /** The entity reached the destination within the adapter's arrival tolerance. */
    ARRIVED,
    /** Native navigation could not create a complete path. */
    NO_PATH,
    /** Native navigation ended before the entity reached the destination. */
    ENDED_SHORT,
    /** The entity stopped making meaningful progress while navigation was active. */
    STUCK,
    /** The handle was disposed and cannot accept another destination. */
    CLOSED
}
