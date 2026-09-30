package com.magmaguy.magmacore.match;

/** Where a match is in its lifecycle. Phases only move forward. */
public enum MatchPhase {
    WAITING, STARTING, ONGOING, ENDED, DESTROYED
}
