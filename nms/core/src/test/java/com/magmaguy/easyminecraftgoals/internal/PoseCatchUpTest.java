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
        PoseCatchUp.Recipients to = catchUp.split(viewers, true);
        assertSame(viewers, to.current());
        assertTrue(to.catchingUp().isEmpty());
    }

    @Test
    void aViewerBehindSkipsPosesAndCatchesUpOnceItsBundleLeft() {
        Runnable left = PoseBacklog.leaving(slow.getUniqueId());

        PoseCatchUp.Recipients skipped = catchUp.split(List.of(fast, slow), true);
        assertEquals(List.of(fast), skipped.current());
        assertTrue(skipped.catchingUp().isEmpty());
        assertTrue(catchUp.owesAny());

        left.run();
        PoseCatchUp.Recipients resumed = catchUp.split(List.of(fast, slow), true);
        assertEquals(List.of(fast), resumed.current());
        assertEquals(List.of(slow), resumed.catchingUp());
        assertEquals(List.of(fast, slow), resumed.all());
        assertFalse(catchUp.owesAny());

        assertEquals(List.of(fast, slow), catchUp.split(List.of(fast, slow), true).current());
    }

    @Test
    void skippingAnUpdateThatChangesNothingOwesNothing() {
        PoseBacklog.leaving(slow.getUniqueId());
        catchUp.split(List.of(slow), false);
        assertFalse(catchUp.owesAny());
    }

    @Test
    void aViewerCatchingUpGetsTheLastValueOfEverySlot() {
        catchUp.record(List.of(new Value(11, "rotation 1"), new Value(12, "scale 1")), Value::slot);
        catchUp.record(List.of(new Value(11, "rotation 2")), Value::slot);
        catchUp.record(null, Value::slot);
        assertEquals(List.of(new Value(11, "rotation 2"), new Value(12, "scale 1")), catchUp.everything());
    }

    @Test
    void anAdmittedOrDepartedViewerOwesNothing() {
        PoseBacklog.leaving(slow.getUniqueId());
        catchUp.split(List.of(slow), true);
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
        assertEquals(List.of(fast, slow), catchUp.split(List.of(fast, slow), true).current());
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
