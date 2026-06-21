package com.magmaguy.easyminecraftgoals.customentity;

@FunctionalInterface
public interface CustomEntityLifecycleHook {
    void handle(CustomEntityHandle entity);
}
