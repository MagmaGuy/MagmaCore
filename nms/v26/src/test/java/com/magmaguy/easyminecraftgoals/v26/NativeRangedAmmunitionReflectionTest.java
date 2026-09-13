package com.magmaguy.easyminecraftgoals.v26;

import net.minecraft.world.item.CrossbowItem;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Guards the v26 NMS shape used when loading crossbows without inventory ammunition. */
class NativeRangedAmmunitionReflectionTest {
    @Test
    void crossbowChargingSoundsIsResolvedAsAnInstanceMethod() throws Exception {
        Method chargingSounds = Arrays.stream(CrossbowItem.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("getChargingSounds"))
                .filter(method -> method.getParameterCount() == 1
                        && method.getParameterTypes()[0] == net.minecraft.world.item.ItemStack.class)
                .findFirst()
                .orElse(null);

        assertNotNull(chargingSounds);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Class.forName(NativeRangedAmmunition.class.getName(), true,
                NativeRangedAmmunition.class.getClassLoader());
        Field handleField = NativeRangedAmmunition.class.getDeclaredField("CHARGING_SOUNDS");
        handleField.setAccessible(true);
        MethodHandle handle = (MethodHandle) handleField.get(null);
        if (Modifier.isStatic(chargingSounds.getModifiers())) {
            assertEquals(1, handle.type().parameterCount());
            assertEquals(net.minecraft.world.item.ItemStack.class, handle.type().parameterType(0));
        } else {
            assertEquals(2, handle.type().parameterCount());
            assertEquals(CrossbowItem.class, handle.type().parameterType(0));
            assertEquals(net.minecraft.world.item.ItemStack.class, handle.type().parameterType(1));
        }
    }
}
