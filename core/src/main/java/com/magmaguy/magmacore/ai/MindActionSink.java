package com.magmaguy.magmacore.ai;

/**
 * Host adapter for semantic actions requested by Mind behaviors.
 *
 * <p>The sink runs synchronously on the server thread inside the requesting callback's execution
 * budget. It must return a disposition and must not retain the physical body as actor identity.
 * Use {@link MindActionContext#logicalOwner()} for durable ownership. A thrown exception fails the
 * active Mind callback. A null result is invalid.</p>
 */
@FunctionalInterface
public interface MindActionSink {
    MindActionResult request(MindActionContext context, MindActionRequest request);

    static MindActionSink rejecting() {
        return (context, request) -> MindActionResult.REJECTED;
    }
}
