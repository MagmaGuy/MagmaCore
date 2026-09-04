package com.magmaguy.magmacore.ai;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MindHostTickContractTest {
    @Test
    void pluginOwnerCanDriveNativeSessionsThroughTheHostInterface() {
        RecordingMindHost host = new RecordingMindHost();

        host.tick();
        host.tick();

        assertEquals(2, host.ticks);
    }

    @Test
    void mindHandleExposesIdempotentPauseAsPartOfTheSharedContract() {
        RecordingMindHandle handle = new RecordingMindHandle();

        handle.setPaused(true);
        handle.setPaused(true);
        assertTrue(handle.paused);
        assertEquals(1, handle.transitions);

        handle.setPaused(false);
        handle.setPaused(false);
        assertFalse(handle.paused);
        assertEquals(2, handle.transitions);
    }

    private static final class RecordingMindHandle implements MindHandle {
        private boolean paused;
        private int transitions;

        @Override
        public void attachBody(MobBody body) {
        }

        @Override
        public void detachBody() {
        }

        @Override
        public void suspendBody() {
        }

        @Override
        public void setPaused(boolean paused) {
            if (this.paused == paused) return;
            this.paused = paused;
            transitions++;
        }

        @Override
        public SwapReceipt replace(MindProgram next, StateTransfer transfer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MindSnapshot inspect() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }

    private static final class RecordingMindHost implements MindHost {
        private int ticks;

        @Override
        public MindHandle open(
                UUID logicalOwner,
                MindProgram program,
                MindPersistentState restoredState) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MindBodyCapabilities bodyCapabilities() {
            throw new UnsupportedOperationException();
        }

        @Override
        public MobBody spawnBody(Location location, MindBodyProfile profile) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<MindBodyRehydration> rehydrateBody(LivingEntity carrier) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void tick() {
            ticks++;
        }
    }
}
