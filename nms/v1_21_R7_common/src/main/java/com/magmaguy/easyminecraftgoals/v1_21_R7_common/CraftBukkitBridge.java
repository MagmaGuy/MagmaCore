package com.magmaguy.easyminecraftgoals.v1_21_R7_common;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

/**
 * Bridge class that handles CraftBukkit package path differences between Paper and Spigot.
 * Paper uses: org.bukkit.craftbukkit.*
 * Spigot uses: org.bukkit.craftbukkit.v1_21_R7.*
 *
 * All methods are cached after first invocation for performance.
 */
public class CraftBukkitBridge {

    private static final boolean IS_PAPER;
    private static final String CB_PACKAGE;

    // Cached classes
    private static Class<?> craftWorldClass;
    private static Class<?> craftPlayerClass;
    private static Class<?> craftEntityClass;
    private static Class<?> craftLivingEntityClass;
    private static Class<?> craftItemStackClass;
    private static Class<?> craftBlockDataClass;

    // Cached methods
    private static Method craftWorldGetHandle;
    private static Method craftPlayerGetHandle;
    private static Method craftEntityGetHandle;
    private static Method craftLivingEntityGetHandle;
    private static Method craftItemStackAsNMSCopy;
    private static Method craftItemStackAsBukkitCopy;
    private static Method craftBlockDataGetState;

    // Hot accessors resolved once from the methods above, each typed (Object)Object.
    private static final MethodHandle WORLD_GET_HANDLE;
    private static final MethodHandle PLAYER_GET_HANDLE;
    private static final MethodHandle ENTITY_GET_HANDLE;
    private static final MethodHandle LIVING_ENTITY_GET_HANDLE;
    private static final MethodHandle ITEM_STACK_AS_NMS_COPY;
    private static final MethodHandle BLOCK_DATA_GET_STATE;
    // getWorld() and getBukkitEntity() are declared by the base classes and overridden covariantly,
    // so a handle on the base method dispatches to the same override the runtime-class lookup found.
    private static final MethodHandle LEVEL_GET_WORLD =
            optionalObjectHandle(net.minecraft.world.level.Level.class, "getWorld");
    private static final MethodHandle ENTITY_GET_BUKKIT_ENTITY =
            optionalObjectHandle(Entity.class, "getBukkitEntity");

    static {
        IS_PAPER = detectPaper();
        CB_PACKAGE = detectCraftBukkitPackage();
        initializeClasses();
        try {
            WORLD_GET_HANDLE = objectHandle(craftWorldGetHandle);
            PLAYER_GET_HANDLE = objectHandle(craftPlayerGetHandle);
            ENTITY_GET_HANDLE = objectHandle(craftEntityGetHandle);
            LIVING_ENTITY_GET_HANDLE = objectHandle(craftLivingEntityGetHandle);
            ITEM_STACK_AS_NMS_COPY = objectHandle(craftItemStackAsNMSCopy);
            BLOCK_DATA_GET_STATE = objectHandle(craftBlockDataGetState);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to initialize CraftBukkit bridge for " + (IS_PAPER ? "Paper" : "Spigot"), e);
        }
    }

    private static boolean detectPaper() {
        try {
            Class.forName("io.papermc.paper.configuration.GlobalConfiguration");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static String detectCraftBukkitPackage() {
        if (IS_PAPER) return "org.bukkit.craftbukkit";
        // Try versioned package first (pre-26.1 Spigot), then flat package (26.1+)
        try {
            Class.forName("org.bukkit.craftbukkit.v1_21_R7.CraftServer");
            return "org.bukkit.craftbukkit.v1_21_R7";
        } catch (ClassNotFoundException e) {
            return "org.bukkit.craftbukkit";
        }
    }

    private static void initializeClasses() {
        try {
            craftWorldClass = Class.forName(CB_PACKAGE + ".CraftWorld");
            craftPlayerClass = Class.forName(CB_PACKAGE + ".entity.CraftPlayer");
            craftEntityClass = Class.forName(CB_PACKAGE + ".entity.CraftEntity");
            craftLivingEntityClass = Class.forName(CB_PACKAGE + ".entity.CraftLivingEntity");
            craftItemStackClass = Class.forName(CB_PACKAGE + ".inventory.CraftItemStack");
            craftBlockDataClass = Class.forName(CB_PACKAGE + ".block.data.CraftBlockData");

            // Cache methods
            craftWorldGetHandle = craftWorldClass.getMethod("getHandle");
            craftPlayerGetHandle = craftPlayerClass.getMethod("getHandle");
            craftEntityGetHandle = craftEntityClass.getMethod("getHandle");
            craftLivingEntityGetHandle = craftLivingEntityClass.getMethod("getHandle");
            craftItemStackAsNMSCopy = craftItemStackClass.getMethod("asNMSCopy", org.bukkit.inventory.ItemStack.class);
            craftItemStackAsBukkitCopy = craftItemStackClass.getMethod("asBukkitCopy", ItemStack.class);
            craftBlockDataGetState = craftBlockDataClass.getMethod("getState");

        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize CraftBukkit bridge for " + (IS_PAPER ? "Paper" : "Spigot"), e);
        }
    }

    public static boolean isPaper() {
        return IS_PAPER;
    }

    public static ServerLevel getServerLevel(World world) {
        try {
            Object handle = WORLD_GET_HANDLE.invokeExact((Object) world);
            return (ServerLevel) handle;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to get ServerLevel from World", e);
        }
    }

    public static ServerLevel getServerLevel(Location location) {
        return getServerLevel(location.getWorld());
    }

    public static ServerPlayer getServerPlayer(Player player) {
        try {
            Object handle = PLAYER_GET_HANDLE.invokeExact((Object) player);
            return (ServerPlayer) handle;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to get ServerPlayer from Player", e);
        }
    }

    public static Entity getNMSEntity(org.bukkit.entity.Entity entity) {
        try {
            Object handle = ENTITY_GET_HANDLE.invokeExact((Object) entity);
            return (Entity) handle;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to get NMS Entity from Bukkit Entity", e);
        }
    }

    public static net.minecraft.world.entity.LivingEntity getNMSLivingEntity(LivingEntity entity) {
        try {
            Object handle = LIVING_ENTITY_GET_HANDLE.invokeExact((Object) entity);
            return (net.minecraft.world.entity.LivingEntity) handle;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to get NMS LivingEntity from Bukkit LivingEntity", e);
        }
    }

    public static org.bukkit.inventory.ItemStack asBukkitCopy(ItemStack itemStack) {
        try {
            return (org.bukkit.inventory.ItemStack) craftItemStackAsBukkitCopy.invoke(null, itemStack);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to convert native item to Bukkit", e);
        }
    }

    public static ItemStack asNMSCopy(org.bukkit.inventory.ItemStack itemStack) {
        try {
            Object copy = ITEM_STACK_AS_NMS_COPY.invokeExact((Object) itemStack);
            return (ItemStack) copy;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to convert ItemStack to NMS", e);
        }
    }

    public static BlockState getBlockState(BlockData blockData) {
        try {
            Object state = BLOCK_DATA_GET_STATE.invokeExact((Object) blockData);
            return (BlockState) state;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to get BlockState from BlockData", e);
        }
    }

    /**
     * Gets the Bukkit World from an NMS Level.
     * Uses reflection to avoid the versioned CraftWorld return type issue.
     */
    public static World getBukkitWorld(net.minecraft.world.level.Level level) {
        MethodHandle getWorld = LEVEL_GET_WORLD;
        if (getWorld == null) return getBukkitWorldReflectively(level);
        try {
            Object world = getWorld.invokeExact((Object) level);
            return (World) world;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to get Bukkit World from Level", e);
        }
    }

    private static World getBukkitWorldReflectively(net.minecraft.world.level.Level level) {
        try {
            // Call getWorld() via reflection to avoid compile-time binding to CraftWorld
            Method getWorld = level.getClass().getMethod("getWorld");
            return (World) getWorld.invoke(level);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get Bukkit World from Level", e);
        }
    }

    /**
     * Gets the Bukkit Entity from an NMS Entity.
     * Uses reflection to avoid the versioned CraftEntity return type issue.
     */
    public static org.bukkit.entity.Entity getBukkitEntity(Entity nmsEntity) {
        MethodHandle getBukkitEntity = ENTITY_GET_BUKKIT_ENTITY;
        if (getBukkitEntity == null) return getBukkitEntityReflectively(nmsEntity);
        try {
            Object bukkitEntity = getBukkitEntity.invokeExact((Object) nmsEntity);
            return (org.bukkit.entity.Entity) bukkitEntity;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to get Bukkit Entity from NMS Entity", e);
        }
    }

    private static org.bukkit.entity.Entity getBukkitEntityReflectively(Entity nmsEntity) {
        try {
            // Call getBukkitEntity() via reflection to avoid compile-time binding to CraftEntity
            Method getBukkitEntity = nmsEntity.getClass().getMethod("getBukkitEntity");
            return (org.bukkit.entity.Entity) getBukkitEntity.invoke(nmsEntity);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get Bukkit Entity from NMS Entity", e);
        }
    }

    /**
     * Resolves a hot accessor once as a handle typed {@code (Object)Object}, so each call is a plain
     * invocation instead of a reflective one. The methods are the ones each call used to invoke.
     */
    private static MethodHandle objectHandle(Method method) throws IllegalAccessException {
        return MethodHandles.publicLookup().unreflect(method)
                .asType(MethodType.methodType(Object.class, Object.class));
    }

    /**
     * {@code null} when the base class has no such public method; the caller then keeps the
     * per-call lookup on the runtime class, with the same result or failure as before.
     */
    private static MethodHandle optionalObjectHandle(Class<?> owner, String name) {
        try {
            return objectHandle(owner.getMethod(name));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    // Cached method for Display teleport duration
    private static Method displayTeleportDurationMethod = null;

    /**
     * Sets the teleport/position interpolation duration on a Display entity.
     * Uses reflection with fallback since the method is private and string literals aren't remapped.
     * Mojang mapping: setPosRotInterpolationDuration
     */
    public static void setDisplayTeleportDuration(net.minecraft.world.entity.Display display, int duration) {
        try {
            if (displayTeleportDurationMethod == null) {
                displayTeleportDurationMethod = findDisplayTeleportDurationMethod();
            }
            displayTeleportDurationMethod.invoke(display, duration);
        } catch (Exception e) {
            // Non-critical - just log and continue
            e.printStackTrace();
        }
    }

    private static Method findDisplayTeleportDurationMethod() throws NoSuchMethodException {
        Class<?> displayClass = net.minecraft.world.entity.Display.class;

        // Try Mojang name first (Paper, and Spigot 26.1+ which is unobfuscated)
        try {
            Method method = displayClass.getDeclaredMethod("setPosRotInterpolationDuration", int.class);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException ignored) {
        }

        // Fall back to obfuscated name for pre-26.1 Spigot
        Method method = displayClass.getDeclaredMethod("d", int.class);
        method.setAccessible(true);
        return method;
    }

    // Cached method/class for CraftChatMessage
    private static Class<?> craftChatMessageClass;
    private static Method fromStringOrNullMethod;

    // Cached method for creating entities
    private static Method craftWorldCreateEntityMethod = null;

    /**
     * Creates an NMS Entity from a Bukkit EntityType.
     * Uses CraftWorld.createEntity() via reflection to handle the NMS creation.
     *
     * @param bukkitType The Bukkit entity type
     * @param level      The server level
     * @param location   The spawn location
     * @return The created NMS entity, or null if creation fails
     */
    public static Entity createNMSEntity(org.bukkit.entity.EntityType bukkitType, ServerLevel level, Location location) {
        try {
            // Use CraftWorld.createEntity via reflection
            if (craftWorldCreateEntityMethod == null) {
                craftWorldCreateEntityMethod = craftWorldClass.getMethod("createEntity",
                        Location.class, Class.class);
            }

            // Get the entity class from the Bukkit EntityType
            Class<? extends org.bukkit.entity.Entity> entityClass = bukkitType.getEntityClass();
            if (entityClass == null) {
                throw new RuntimeException("No entity class for type: " + bukkitType);
            }

            // Create the Bukkit entity using CraftWorld
            org.bukkit.entity.Entity bukkitEntity = (org.bukkit.entity.Entity)
                    craftWorldCreateEntityMethod.invoke(location.getWorld(), location, entityClass);

            // Get the NMS entity from the Bukkit entity
            Entity nmsEntity = getNMSEntity(bukkitEntity);

            // Set position and rotation
            if (nmsEntity != null) {
                nmsEntity.setPos(location.getX(), location.getY(), location.getZ());
                if (location.getYaw() != 0) nmsEntity.setYRot(location.getYaw());
                if (location.getPitch() != 0) nmsEntity.setXRot(location.getPitch());
            }

            return nmsEntity;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create NMS entity for type: " + bukkitType, e);
        }
    }

    /**
     * Converts a legacy color-coded string (with § codes) to a Minecraft Component.
     * Uses CraftChatMessage.fromStringOrNull() to properly parse color codes including hex colors.
     *
     * @param text The text with legacy color codes
     * @return The NMS Component, or a literal component if conversion fails
     */
    public static net.minecraft.network.chat.Component fromLegacyText(String text) {
        if (text == null || text.isEmpty()) {
            return net.minecraft.network.chat.Component.literal("");
        }

        try {
            // Initialize CraftChatMessage class and method if not done yet
            if (craftChatMessageClass == null) {
                craftChatMessageClass = Class.forName(CB_PACKAGE + ".util.CraftChatMessage");
                fromStringOrNullMethod = craftChatMessageClass.getMethod("fromStringOrNull", String.class);
            }

            // Call CraftChatMessage.fromStringOrNull(text)
            net.minecraft.network.chat.Component component =
                    (net.minecraft.network.chat.Component) fromStringOrNullMethod.invoke(null, text);

            if (component != null) {
                return component;
            }
        } catch (Exception e) {
            // Fall through to literal fallback
        }

        // Fallback to literal if conversion fails
        return net.minecraft.network.chat.Component.literal(text);
    }
}
