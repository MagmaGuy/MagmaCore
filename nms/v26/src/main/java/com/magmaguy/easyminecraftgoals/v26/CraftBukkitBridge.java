package com.magmaguy.easyminecraftgoals.v26;

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
import java.lang.reflect.Modifier;

/**
 * Bridge class for CraftBukkit access on MC 26.1+.
 * Both Paper and Spigot use Mojang mappings and flat CraftBukkit packages.
 */
public class CraftBukkitBridge {

    private static final String CB_PACKAGE = "org.bukkit.craftbukkit";

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
        initializeClasses();
        try {
            WORLD_GET_HANDLE = objectHandle(craftWorldGetHandle);
            PLAYER_GET_HANDLE = objectHandle(craftPlayerGetHandle);
            ENTITY_GET_HANDLE = objectHandle(craftEntityGetHandle);
            LIVING_ENTITY_GET_HANDLE = objectHandle(craftLivingEntityGetHandle);
            ITEM_STACK_AS_NMS_COPY = objectHandle(craftItemStackAsNMSCopy);
            BLOCK_DATA_GET_STATE = objectHandle(craftBlockDataGetState);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to initialize CraftBukkit bridge", e);
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

            craftWorldGetHandle = craftWorldClass.getMethod("getHandle");
            craftPlayerGetHandle = craftPlayerClass.getMethod("getHandle");
            craftEntityGetHandle = craftEntityClass.getMethod("getHandle");
            craftLivingEntityGetHandle = craftLivingEntityClass.getMethod("getHandle");
            craftItemStackAsNMSCopy = craftItemStackClass.getMethod("asNMSCopy", org.bukkit.inventory.ItemStack.class);
            craftBlockDataGetState = craftBlockDataClass.getMethod("getState");

        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize CraftBukkit bridge", e);
        }
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
            return (org.bukkit.inventory.ItemStack) BukkitItemCopy.METHOD.invoke(null, itemStack);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to convert native item to Bukkit", e);
        }
    }

    // Resolve only when conversion is needed; a conversion incompatibility must not
    // prevent the bridge from providing unrelated world and entity handles.
    private static final class BukkitItemCopy {
        private static final Method METHOD = resolveMethod();

        private static Method resolveMethod() {
            // Spigot exposes ItemStack directly. Paper 26.2 exposes ItemInstance
            // publicly and makes its ItemStack overload private.
            Method selected = null;
            boolean ambiguous = false;
            for (Method method : craftItemStackClass.getMethods()) {
                if (!method.getName().equals("asBukkitCopy")
                        || !Modifier.isStatic(method.getModifiers())
                        || method.getParameterCount() != 1
                        || !method.getParameterTypes()[0].isAssignableFrom(ItemStack.class)
                        || !org.bukkit.inventory.ItemStack.class.isAssignableFrom(method.getReturnType()))
                    continue;
                if (method.getParameterTypes()[0] == ItemStack.class)
                    return method;
                ambiguous |= selected != null;
                selected = method;
            }
            if (ambiguous)
                throw new IllegalStateException("Ambiguous public CraftItemStack.asBukkitCopy overloads for "
                        + ItemStack.class.getName());
            if (selected == null)
                throw new IllegalStateException("No public CraftItemStack.asBukkitCopy overload accepts "
                        + ItemStack.class.getName());
            return selected;
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
            Method getWorld = level.getClass().getMethod("getWorld");
            return (World) getWorld.invoke(level);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get Bukkit World from Level", e);
        }
    }

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

    private static Method displayTeleportDurationMethod = null;

    public static void setDisplayTeleportDuration(net.minecraft.world.entity.Display display, int duration) {
        try {
            if (displayTeleportDurationMethod == null) {
                displayTeleportDurationMethod = net.minecraft.world.entity.Display.class
                        .getDeclaredMethod("setPosRotInterpolationDuration", int.class);
                displayTeleportDurationMethod.setAccessible(true);
            }
            displayTeleportDurationMethod.invoke(display, duration);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static Class<?> craftChatMessageClass;
    private static Method fromStringOrNullMethod;
    private static Method craftWorldCreateEntityMethod = null;

    public static Entity createNMSEntity(org.bukkit.entity.EntityType bukkitType, ServerLevel level, Location location) {
        try {
            if (craftWorldCreateEntityMethod == null) {
                craftWorldCreateEntityMethod = craftWorldClass.getMethod("createEntity",
                        Location.class, Class.class);
            }

            Class<? extends org.bukkit.entity.Entity> entityClass = bukkitType.getEntityClass();
            if (entityClass == null) {
                throw new RuntimeException("No entity class for type: " + bukkitType);
            }

            org.bukkit.entity.Entity bukkitEntity = (org.bukkit.entity.Entity)
                    craftWorldCreateEntityMethod.invoke(location.getWorld(), location, entityClass);

            Entity nmsEntity = getNMSEntity(bukkitEntity);

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

    public static net.minecraft.network.chat.Component fromLegacyText(String text) {
        if (text == null || text.isEmpty()) {
            return net.minecraft.network.chat.Component.literal("");
        }

        try {
            if (craftChatMessageClass == null) {
                craftChatMessageClass = Class.forName(CB_PACKAGE + ".util.CraftChatMessage");
                fromStringOrNullMethod = craftChatMessageClass.getMethod("fromStringOrNull", String.class);
            }

            net.minecraft.network.chat.Component component =
                    (net.minecraft.network.chat.Component) fromStringOrNullMethod.invoke(null, text);

            if (component != null) {
                return component;
            }
        } catch (Exception e) {
            // Fall through to literal fallback
        }

        return net.minecraft.network.chat.Component.literal(text);
    }
}
