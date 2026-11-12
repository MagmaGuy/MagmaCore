package com.magmaguy.magmacore.ai;

public interface MindHandle extends AutoCloseable {
    void attachBody(MobBody body);

    void detachBody();

    /**
     * Detaches a body being serialized by Minecraft while retaining its rehydration marker. The
     * logical session remains open and can accept the restored body later.
     */
    void suspendBody();

    /**
     * Pauses or resumes this logical Mind without detaching its body.
     *
     * <p>Pausing is idempotent. A paused Mind retains its program, memory, body marker, and
     * pending replacement, but performs no Brain ticks or semantic actions until resumed.</p>
     */
    void setPaused(boolean paused);

    /**
     * Releases this Mind's body to its native movement, or takes it back.
     *
     * <p>A released Mind keeps its program, memory and body marker but runs no Brain ticks and
     * leaves the body alone: no NoAI, no paused physics, no movement overrides. Use it while a
     * rider's client steers the body. Memory time stands still as it does while paused. Releasing
     * is idempotent and independent of pause; inside a Brain tick it applies after the tick.</p>
     */
    void setReleased(boolean released);

    SwapReceipt replace(MindProgram next, StateTransfer transfer);

    MindSnapshot inspect();

    /**
     * Whether a body is attached now, as {@code inspect().bodyEntityId().isPresent()} would say, without building a
     * snapshot. A session loses its body when the body dies, unloads or leaves its world, and a borrowed mob is
     * handed back at that moment. Primary thread only.
     */
    default boolean hasBody() {
        return inspect().bodyEntityId().isPresent();
    }

    /**
     * Whether the Mind runs the behavior with this identifier now, as {@code inspect().activeBehaviors()} would say,
     * without building a snapshot: a caller asking about many Minds each tick pays a loop, not a copy of each one's
     * memory. Primary thread only.
     */
    default boolean isRunning(String behaviorIdentifier) {
        return inspect().activeBehaviors().contains(behaviorIdentifier);
    }

    /**
     * Whether the behavior with this identifier could not start now because a running behavior that comes before it
     * (lower priority, then identifier) holds one of its controls. Such a behavior is not even asked to start (see
     * {@link MindBehavior#canStart}), so a caller enlisting this Mind for that behavior, as a group activity enlists
     * its members, can leave it out. False when the behavior runs already, when the Mind's program has no such
     * behavior or no body to run it on, and from implementations that do not arbitrate controls. Primary thread only.
     */
    default boolean isBlocked(String behaviorIdentifier) {
        return false;
    }

    @Override
    void close();
}
