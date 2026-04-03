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

import java.lang.reflect.Method;

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

    static {
        initializeClasses();
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

    public static boolean isPaper() {
        try {
            Class.forName("io.papermc.paper.configuration.GlobalConfiguration");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static ServerLevel getServerLevel(World world) {
        try {
            return (ServerLevel) craftWorldGetHandle.invoke(world);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get ServerLevel from World", e);
        }
    }

    public static ServerLevel getServerLevel(Location location) {
        return getServerLevel(location.getWorld());
    }

    public static ServerPlayer getServerPlayer(Player player) {
        try {
            return (ServerPlayer) craftPlayerGetHandle.invoke(player);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get ServerPlayer from Player", e);
        }
    }

    public static Entity getNMSEntity(org.bukkit.entity.Entity entity) {
        try {
            return (Entity) craftEntityGetHandle.invoke(entity);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get NMS Entity from Bukkit Entity", e);
        }
    }

    public static net.minecraft.world.entity.LivingEntity getNMSLivingEntity(LivingEntity entity) {
        try {
            return (net.minecraft.world.entity.LivingEntity) craftLivingEntityGetHandle.invoke(entity);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get NMS LivingEntity from Bukkit LivingEntity", e);
        }
    }

    public static ItemStack asNMSCopy(org.bukkit.inventory.ItemStack itemStack) {
        try {
            return (ItemStack) craftItemStackAsNMSCopy.invoke(null, itemStack);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert ItemStack to NMS", e);
        }
    }

    public static BlockState getBlockState(BlockData blockData) {
        try {
            return (BlockState) craftBlockDataGetState.invoke(blockData);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get BlockState from BlockData", e);
        }
    }

    public static World getBukkitWorld(net.minecraft.world.level.Level level) {
        try {
            Method getWorld = level.getClass().getMethod("getWorld");
            return (World) getWorld.invoke(level);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get Bukkit World from Level", e);
        }
    }

    public static org.bukkit.entity.Entity getBukkitEntity(Entity nmsEntity) {
        try {
            Method getBukkitEntity = nmsEntity.getClass().getMethod("getBukkitEntity");
            return (org.bukkit.entity.Entity) getBukkitEntity.invoke(nmsEntity);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get Bukkit Entity from NMS Entity", e);
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

    public static String getEntityDimensionsFieldName() {
        return "dimensions";
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
