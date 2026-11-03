package com.magmaguy.easyminecraftgoals.internal;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoseCatchUpTest {
    /** An entity-data value: its slot and what it holds. */
    private record Value(int slot, String held) { }

    private static final int ROTATION = 11, SCALE = 12;

    private final PoseCatchUp<Value> catchUp = new PoseCatchUp<>();
    private final Player fast = player(), slow = player();

    @AfterEach
    void reset() {
        PoseBacklog.clear();
        PacketEntityTuning.skipPosesWhileBehind = true;
    }

    @Test
    void viewersKeepingUpGetTheChange() {
        List<Player> viewers = List.of(fast, slow);
        PoseCatchUp.Recipients to = catchUp.split(viewers, record(ROTATION, "rotation 1"), true);
        assertSame(viewers, to.current());
        assertTrue(to.catchingUp().isEmpty());
    }

    @Test
    void aViewerBehindSkipsPosesAndCatchesUpOnceItsBundleLeft() {
        Runnable left = PoseBacklog.leaving(slow.getUniqueId());

        PoseCatchUp.Recipients skipped = catchUp.split(List.of(fast, slow), record(ROTATION, "rotation 1"), true);
        assertEquals(List.of(fast), skipped.current());
        assertTrue(skipped.catchingUp().isEmpty());
        assertTrue(catchUp.owesAny());

        left.run();
        PoseCatchUp.Recipients resumed = catchUp.split(List.of(fast, slow), record(ROTATION, "rotation 2"), true);
        assertEquals(List.of(fast), resumed.current());
        assertEquals(1, resumed.catchingUp().size());
        assertEquals(List.of(slow), resumed.catchingUp().getFirst().viewers());
        assertEquals(List.of(fast, slow), resumed.all());
        assertFalse(catchUp.owesAny());

        assertEquals(List.of(fast, slow), catchUp.split(List.of(fast, slow), 0, true).current());
    }

    @Test
    void aViewerCatchingUpGetsOnlyTheSlotsItMissedAndThisUpdatesChanges() {
        // Both slots reach everyone, then the slow viewer misses a rotation and a later scale arrives with its catch-up.
        catchUp.split(List.of(fast, slow), record(ROTATION, "rotation 1") | record(SCALE, "scale 1"), true);
        Runnable left = PoseBacklog.leaving(slow.getUniqueId());
        catchUp.split(List.of(fast, slow), record(ROTATION, "rotation 2"), true);
        left.run();

        PoseCatchUp.Recipients nothingNew = catchUp.split(List.of(fast, slow), 0, true);
        assertEquals(List.of(new Value(ROTATION, "rotation 2")), catchUp.values(nothingNew.catchingUp().getFirst().slots()));

        Runnable leftAgain = PoseBacklog.leaving(slow.getUniqueId());
        catchUp.split(List.of(slow), record(ROTATION, "rotation 3"), true);
        leftAgain.run();
        PoseCatchUp.Recipients withScale = catchUp.split(List.of(slow), record(SCALE, "scale 2"), true);
        assertEquals(List.of(new Value(ROTATION, "rotation 3"), new Value(SCALE, "scale 2")),
                catchUp.values(withScale.catchingUp().getFirst().slots()));
    }

    @Test
    void skippingAnUpdateThatChangesNothingOwesNothing() {
        PoseBacklog.leaving(slow.getUniqueId());
        catchUp.split(List.of(slow), 0, false);
        assertFalse(catchUp.owesAny());
    }

    @Test
    void aSkippedMoveIsOwedEvenWithoutDataChanges() {
        Runnable left = PoseBacklog.leaving(slow.getUniqueId());
        catchUp.split(List.of(slow), 0, true);
        assertTrue(catchUp.owesAny(), "the next update must reach the viewer with a move");

        left.run();
        PoseCatchUp.Recipients resumed = catchUp.split(List.of(slow), 0, true);
        assertEquals(List.of(slow), resumed.all());
        assertTrue(catchUp.values(resumed.catchingUp().getFirst().slots()).isEmpty());
    }

    @Test
    void everySlotIsTheLastValueSentForIt() {
        catchUp.record(List.of(new Value(ROTATION, "rotation 1"), new Value(SCALE, "scale 1")), Value::slot);
        catchUp.record(List.of(new Value(ROTATION, "rotation 2")), Value::slot);
        assertEquals(0, catchUp.record(null, Value::slot));
        assertEquals(List.of(new Value(ROTATION, "rotation 2"), new Value(SCALE, "scale 1")),
                catchUp.values(PoseCatchUp.EVERY_SLOT));
        assertEquals(List.of(new Value(SCALE, "scale 1")), catchUp.values(1L << SCALE));
    }

    @Test
    void aSlotBeyondTheMaskCountsAsEverySlot() {
        assertEquals(PoseCatchUp.EVERY_SLOT, catchUp.record(List.of(new Value(64, "beyond")), Value::slot));
    }

    @Test
    void viewersOwedDifferentSlotsCatchUpSeparately() {
        Player other = player();
        catchUp.split(List.of(slow, other), record(ROTATION, "rotation 1") | record(SCALE, "scale 1"), true);
        Runnable slowLeft = PoseBacklog.leaving(slow.getUniqueId());
        catchUp.split(List.of(slow, other), record(ROTATION, "rotation 2"), true);
        Runnable otherLeft = PoseBacklog.leaving(other.getUniqueId());
        catchUp.split(List.of(slow, other), record(SCALE, "scale 2"), true);
        slowLeft.run();
        otherLeft.run();

        List<PoseCatchUp.CatchingUp> groups = catchUp.split(List.of(slow, other), 0, true).catchingUp();
        assertEquals(2, groups.size());
        assertEquals(List.of(slow), groups.get(0).viewers());
        assertEquals((1L << ROTATION) | (1L << SCALE), groups.get(0).slots());
        assertEquals(List.of(other), groups.get(1).viewers());
        assertEquals(1L << SCALE, groups.get(1).slots());
    }

    @Test
    void anAdmittedOrDepartedViewerOwesNothing() {
        PoseBacklog.leaving(slow.getUniqueId());
        catchUp.split(List.of(slow), record(ROTATION, "rotation 1"), true);
        catchUp.forget(slow.getUniqueId());
        assertFalse(catchUp.owesAny());
    }

    @Test
    void aBundleCountsOnceHoweverOftenItReportsLeaving() {
        UUID id = slow.getUniqueId();
        Runnable first = PoseBacklog.leaving(id), second = PoseBacklog.leaving(id);
        first.run();
        first.run();
        assertTrue(PoseBacklog.behind(id), "the second bundle has not left");
        second.run();
        assertFalse(PoseBacklog.behind(id));
    }

    @Test
    void theSwitchSendsEveryPoseHoweverFarBehind() {
        PoseBacklog.leaving(slow.getUniqueId());
        PacketEntityTuning.skipPosesWhileBehind = false;
        assertEquals(List.of(fast, slow), catchUp.split(List.of(fast, slow), record(ROTATION, "rotation 1"), true).current());
    }

    /** Records one value as an update would and returns the slots it changed. */
    private long record(int slot, String held) {
        return catchUp.record(List.of(new Value(slot, held)), Value::slot);
    }

    private static Player player() {
        UUID uuid = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Player " + uuid;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
