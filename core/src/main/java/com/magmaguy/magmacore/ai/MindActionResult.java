package com.magmaguy.magmacore.ai;

/**
 * Immediate disposition returned by a host for one semantic Mind action request.
 */
public enum MindActionResult {
    /** The host took ownership of the request, whether it executes now or from a host-owned queue. */
    ACCEPTED,
    /** The host did not take ownership. The behavior may retry on a later tick. */
    DEFERRED,
    /** The host will not accept the unchanged request. */
    REJECTED
}
