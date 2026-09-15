package com.magmaguy.easyminecraftgoals.v26;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** 26.3 split hit cooldown from invulnerableTime; older 26.x Bukkit access remains correct. */
final class DamageCooldownAccess {
    private final MethodHandle getter;
    private final MethodHandle setter;

    private DamageCooldownAccess(MethodHandle getter, MethodHandle setter) {
        this.getter = getter;
        this.setter = setter;
    }

    static DamageCooldownAccess current() {
        return NativeAccess.INSTANCE;
    }

    private static final class NativeAccess {
        private static final DamageCooldownAccess INSTANCE = resolve(net.minecraft.world.entity.LivingEntity.class);
    }

    static DamageCooldownAccess resolve(Class<?> livingEntityType) {
        final Field field;
        try {
            field = livingEntityType.getDeclaredField("damageCooldownTime");
        } catch (NoSuchFieldException absentOnOlderVersion) {
            return null;
        }
        int modifiers = field.getModifiers();
        if (field.getType() != int.class || !Modifier.isPublic(modifiers)
                || Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
            throw new IllegalStateException("Unexpected native hit cooldown field: " + field);
        }
        try {
            return new DamageCooldownAccess(
                    MethodHandles.lookup().unreflectGetter(field).asType(MethodType.methodType(int.class, Object.class)),
                    MethodHandles.lookup().unreflectSetter(field).asType(MethodType.methodType(void.class, Object.class, int.class)));
        } catch (IllegalAccessException | RuntimeException failure) {
            throw new IllegalStateException("Unable to access the native hit cooldown field: " + field, failure);
        }
    }

    int get(Object entity) {
        try {
            return (int) getter.invokeExact(entity);
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to read the native hit cooldown", failure);
        }
    }

    void set(Object entity, int ticks) {
        try {
            setter.invokeExact(entity, ticks);
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to write the native hit cooldown", failure);
        }
    }
}
