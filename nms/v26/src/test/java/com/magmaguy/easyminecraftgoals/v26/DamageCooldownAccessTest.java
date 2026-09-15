package com.magmaguy.easyminecraftgoals.v26;

import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DamageCooldownAccessTest {
    @Test
    void savesClearsAndRestoresOnlyTheNewHitCooldown() {
        SplitTimers entity = new SplitTimers();
        DamageCooldownAccess access = DamageCooldownAccess.resolve(SplitTimers.class);
        assertNotNull(access);
        int previous = access.get(entity);
        access.set(entity, 0);
        assertEquals(0, entity.damageCooldownTime);
        assertEquals(7, entity.invulnerableTime);
        assertEquals(3.5F, entity.lastHurt);
        access.set(entity, previous);
        assertEquals(16, entity.damageCooldownTime);
        assertEquals(7, entity.invulnerableTime);
        assertEquals(3.5F, entity.lastHurt);
    }

    @Test
    void absentSplitTimerUsesTheOlderBukkitContract() {
        assertNull(DamageCooldownAccess.resolve(OldTimer.class));
        com.magmaguy.easyminecraftgoals.NMSAdapter adapter = mock(
                com.magmaguy.easyminecraftgoals.NMSAdapter.class, CALLS_REAL_METHODS);
        LivingEntity entity = mock(LivingEntity.class);
        when(entity.getNoDamageTicks()).thenReturn(12);
        assertEquals(12, adapter.getDamageCooldownTicks(entity));
        adapter.setDamageCooldownTicks(entity, 0);
        verify(entity).setNoDamageTicks(0);
        verify(entity, never()).setLastDamage(anyDouble());
        verify(entity, never()).setInvulnerable(anyBoolean());
    }

    @Test
    void presentButIncompatibleFieldDoesNotFallBackToTheOldTimer() {
        for (Class<?> type : new Class<?>[]{WrongType.class, StaticTimer.class, FinalTimer.class, PrivateTimer.class}) {
            assertThrows(IllegalStateException.class, () -> DamageCooldownAccess.resolve(type), type.getName());
        }
    }

    @Test
    void currentNativeFieldResolvesWithoutStartingAServer() {
        assertNotNull(DamageCooldownAccess.current());
    }

    @Test
    void wrongNativeReceiverFailsExplicitly() {
        DamageCooldownAccess access = DamageCooldownAccess.resolve(SplitTimers.class);
        assertNotNull(access);
        assertThrows(IllegalStateException.class, () -> access.get(new OldTimer()));
        assertThrows(IllegalStateException.class, () -> access.set(new OldTimer(), 0));
    }

    public static class SplitTimers {
        public int damageCooldownTime = 16;
        public int invulnerableTime = 7;
        public float lastHurt = 3.5F;
    }
    public static class OldTimer { public int invulnerableTime = 16; }
    public static class WrongType { public long damageCooldownTime; }
    public static class StaticTimer { public static int damageCooldownTime; }
    public static class FinalTimer { public final int damageCooldownTime = 0; }
    public static class PrivateTimer { private int damageCooldownTime; }
}
