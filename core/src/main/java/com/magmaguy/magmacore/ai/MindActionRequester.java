package com.magmaguy.magmacore.ai;

/** Guarded action-request channel available to the active Mind callback. */
@FunctionalInterface
public interface MindActionRequester {
    MindActionResult request(MindActionRequest request);

    static MindActionRequester rejecting() {
        return request -> MindActionResult.REJECTED;
    }
}
