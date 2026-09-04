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

    SwapReceipt replace(MindProgram next, StateTransfer transfer);

    MindSnapshot inspect();

    @Override
    void close();
}
