package com.magmaguy.magmacore.match;

/** Why a participant left. Passed to onLeave and to the plugin API adapter. */
public enum LeaveReason {
    QUIT, DISCONNECT, DIED, COMPLETED, MATCH_ENDED, SHUTDOWN
}
