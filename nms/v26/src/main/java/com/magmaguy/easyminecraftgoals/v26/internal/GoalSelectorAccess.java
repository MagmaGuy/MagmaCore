package com.magmaguy.easyminecraftgoals.v26.internal;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;

/** Shared goal-selector access for the 26.1 and 26.2 adapter. */
public final class GoalSelectorAccess {
    private static final MethodHandle ACCESSOR = resolveAccessor();

    private GoalSelectorAccess() {
    }

    public static GoalSelector get(Mob mob) {
        try {
            return (GoalSelector) ACCESSOR.invokeExact(mob);
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to access the Mob goal selector on this server version.", failure);
        }
    }

    private static MethodHandle resolveAccessor() {
        try {
            try {
                // 26.2 exposes a getter that is absent on Paper 26.1.2.
                return MethodHandles.lookup().unreflect(Mob.class.getMethod("getGoalSelector"))
                        .asType(MethodType.methodType(GoalSelector.class, Mob.class));
            } catch (NoSuchMethodException ignored) {
                for (Class<?> currentClass = Mob.class; currentClass != null; currentClass = currentClass.getSuperclass()) {
                    try {
                        Field field = currentClass.getDeclaredField("goalSelector");
                        field.setAccessible(true);
                        return MethodHandles.lookup().unreflectGetter(field)
                                .asType(MethodType.methodType(GoalSelector.class, Mob.class));
                    } catch (NoSuchFieldException absent) {
                        // Continue searching the Mob hierarchy, as the existing wander goal did.
                    }
                }
                throw new NoSuchFieldException("Mob.goalSelector");
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IllegalStateException("Unable to resolve the Mob goal selector on this server version.", failure);
        }
    }
}
