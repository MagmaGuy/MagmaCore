package com.magmaguy.easyminecraftgoals.internal;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientFrameLatencyTest {
    private static final long MS = 1_000_000L;

    /** Answers pings 200 ms apart after the network round trip plus the wait for the client's next frame. */
    private static ClientFrameLatency.Client client(long networkMs, long frameMs, int pings) {
        ClientFrameLatency.Client client = new ClientFrameLatency.Client();
        for (int i = 0; i < pings; i++) {
            long sent = i * 200 * MS;
            int id = client.register(sent);
            // Pings land at spread-out points of the frame, so the waits cover it evenly.
            long frameWait = frameMs * MS * ((i * 37L) % 100) / 100;
            assertTrue(client.answer(id, sent + networkMs * MS + frameWait));
        }
        return client;
    }

    @Test
    void slowClientReadsAsItsFrameTime() {
        assertEquals(40, client(60, 40, ClientFrameLatency.FLOOR_SAMPLES).frameMillis(), 4);
    }

    @Test
    void fastClientReadsAsFastWhateverTheNetwork() {
        assertEquals(1, client(150, 1, ClientFrameLatency.FLOOR_SAMPLES).frameMillis(), 1);
    }

    @Test
    void noEstimateUntilEnoughAnswers() {
        assertTrue(Double.isNaN(client(60, 40, ClientFrameLatency.MINIMUM_SAMPLES - 1).frameMillis()));
        assertFalse(Double.isNaN(client(60, 40, ClientFrameLatency.MINIMUM_SAMPLES).frameMillis()));
    }

    @Test
    void pingSlotsAreSpacedOut() {
        ClientFrameLatency.Client client = new ClientFrameLatency.Client();
        assertTrue(client.claimSlot(0));
        assertFalse(client.claimSlot(199 * MS));
        assertTrue(client.claimSlot(200 * MS));
    }

    @Test
    void onlyPendingTaggedPongsAreClaimed() {
        ClientFrameLatency.Client client = new ClientFrameLatency.Client();
        int id = client.register(0);
        assertFalse(client.answer(id + 1, MS));
        assertTrue(client.answer(id, MS));
        assertFalse(client.answer(id, 2 * MS));
        // Anti-cheat transaction ids are 16-bit and never ours.
        assertFalse(ClientFrameLatency.answer(UUID.randomUUID(), -3, MS));
        assertFalse(ClientFrameLatency.answer(UUID.randomUUID(), id, MS));
    }
}
