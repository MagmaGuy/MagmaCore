package com.magmaguy.easyminecraftgoals.v1_21_R5;

import com.magmaguy.easyminecraftgoals.ammunition.RangedAmmunition;
import io.netty.channel.Channel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.stats.Stats;
import net.minecraft.util.Unit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.BooleanSupplier;

/** Native ammunition substitution. Vanilla still owns charge timing, enchantments, shots and weapon durability. */
public final class NativeRangedAmmunition extends RangedAmmunition {
    private static final EntityDataAccessor<Byte> USE_FLAGS = useFlags();
    private static final Field CONNECTION = nativeField(ServerCommonPacketListenerImpl.class, Connection.class);
    private static final Field CHANNEL = nativeField(Connection.class, Channel.class);
    private static final MethodHandle DRAW = nativeMethod(ProjectileWeaponItem.class, List.class, true,
            ItemStack.class, ItemStack.class, LivingEntity.class);
    private static final MethodHandle SHOOT = nativeShootMethod(ProjectileWeaponItem.class,
            ServerLevel.class, LivingEntity.class, InteractionHand.class, ItemStack.class, List.class,
            float.class, float.class, boolean.class, LivingEntity.class);
    private static final MethodHandle CHARGING_SOUNDS = nativeMethod(CrossbowItem.class, CrossbowItem.ChargingSounds.class,
            false, ItemStack.class);

    public NativeRangedAmmunition(Plugin plugin, Predicate<Player> eligible) { super(plugin, eligible); }

    private static ServerPlayer handle(Player player) { return ((org.bukkit.craftbukkit.v1_21_R5.entity.CraftPlayer) player).getHandle(); }
    private static ItemStack nativeCopy(org.bukkit.inventory.ItemStack item) { return org.bukkit.craftbukkit.v1_21_R5.inventory.CraftItemStack.asNMSCopy(item); }
    private static org.bukkit.inventory.ItemStack bukkitCopy(ItemStack item) { return org.bukkit.craftbukkit.v1_21_R5.inventory.CraftItemStack.asBukkitCopy(item); }

    @Override protected Channel channel(Player player) {
        try { return (Channel) CHANNEL.get(CONNECTION.get(handle(player).connection)); }
        catch (IllegalAccessException failure) { throw new IllegalStateException("Cannot access item-use channel", failure); }
    }

    @Override protected boolean isReleasePacket(Object packet) {
        return packet instanceof ServerboundPlayerActionPacket action
                && action.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM;
    }

    @Override protected Object orderedRelease(Player player, Object packet, BooleanSupplier release) {
        ServerboundPlayerActionPacket original = (ServerboundPlayerActionPacket) packet;
        return new Packet<ServerGamePacketListener>() {
            @Override public PacketType<? extends Packet<ServerGamePacketListener>> type() { return original.type(); }
            @Override public void handle(ServerGamePacketListener listener) {
                PacketUtils.ensureRunningOnSameThread(this, listener, (ServerLevel) NativeRangedAmmunition.handle(player).level());
                if (!release.getAsBoolean()) original.handle(listener);
            }
        };
    }

    @Override protected Use begin(Player player, EquipmentSlot slot) {
        ServerPlayer entity = handle(player);
        if (entity.isUsingItem()) return null;
        InteractionHand hand = slot == EquipmentSlot.OFF_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        ItemStack weapon = entity.getItemInHand(hand);
        if (entity.getCooldowns().isOnCooldown(weapon)) return null;
        if (!(weapon.getItem() instanceof BowItem) && !(weapon.getItem() instanceof CrossbowItem)) return null;
        if (weapon.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(weapon)) return null;
        ItemStack selected = entity.getProjectile(weapon);
        if (!selected.isEmpty() && !ordinary(bukkitCopy(selected))) return null;
        NativeUse use = new NativeUse(entity, hand, weapon);
        try {
            entity.startUsingItem(hand);
            syncUse(entity);
            return use;
        } catch (RuntimeException failure) {
            use.cancel();
            throw failure;
        }
    }

    @SuppressWarnings("unchecked")
    private static EntityDataAccessor<Byte> useFlags() {
        Field found = null;
        for (Field field : LivingEntity.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != EntityDataAccessor.class
                    || !(field.getGenericType() instanceof ParameterizedType type)
                    || type.getActualTypeArguments()[0] != Byte.class) continue;
            if (found != null) throw new IllegalStateException("Ambiguous living item-use flags");
            found = field;
        }
        if (found == null) throw new IllegalStateException("Missing living item-use flags");
        try { found.setAccessible(true); return (EntityDataAccessor<Byte>) found.get(null); }
        catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
    }

    private static void syncUse(ServerPlayer player) {
        // Include zero explicitly on cancellation; non-default snapshots omit it. Do not drain tracker dirty state.
        player.connection.send(new ClientboundSetEntityDataPacket(player.getId(), List.of(
                SynchedEntityData.DataValue.create(USE_FLAGS, player.getEntityData().get(USE_FLAGS)))));
    }

    private static final class NativeUse implements Use {
        private final ServerPlayer player;
        private final InteractionHand hand;
        private final ItemStack weapon;

        private NativeUse(ServerPlayer player, InteractionHand hand, ItemStack weapon) {
            this.player = player;
            this.hand = hand;
            this.weapon = weapon;
        }

        @Override public boolean valid() {
            return !player.isImmobile() && player.isUsingItem() && player.getUseItem() == weapon && player.getItemInHand(hand) == weapon;
        }

        @Override public void tick() {
            if (!(weapon.getItem() instanceof CrossbowItem) || CrossbowItem.isCharged(weapon)
                    || player.getTicksUsingItem() < CrossbowItem.getChargeDuration(weapon, player)) return;
            // Bukkit tasks run before entity ticks. Vanilla decrements useItemRemaining AFTER onUseTick;
            // at this boundary the complete native charge has elapsed and vanilla has not consumed an arrow yet.
            List<ItemStack> arrows = draw();
            if (arrows.isEmpty()) return;
            weapon.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(arrows));
            try {
                CrossbowItem.ChargingSounds sounds = (CrossbowItem.ChargingSounds) CHARGING_SOUNDS.invoke(weapon.getItem(), weapon);
                sounds.end().ifPresent(sound -> player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                        sound.value(), SoundSource.PLAYERS, 1F, 1F));
            } catch (Throwable failure) { throw new IllegalStateException("Native crossbow loading failed", failure); }
            player.getBukkitEntity().updateInventory();
        }

        @SuppressWarnings("unchecked")
        private List<ItemStack> draw() {
            try {
                // This stack never enters an inventory. draw retains multishot and ammunition enchantment rules.
                List<ItemStack> arrows = (List<ItemStack>) DRAW.invoke(weapon, nativeCopy(grantedArrow()), player);
                for (ItemStack arrow : arrows) arrow.set(DataComponents.INTANGIBLE_PROJECTILE, Unit.INSTANCE);
                return arrows;
            } catch (Throwable failure) { throw new IllegalStateException("Native ammunition draw failed", failure); }
        }

        @Override public void release() {
            if (weapon.getItem() instanceof CrossbowItem) { tick(); return; }
            float power = BowItem.getPowerForTime(player.getTicksUsingItem());
            if (power < .1F) return;
            List<ItemStack> arrows = draw();
            if (arrows.isEmpty()) return;
            try {
                ProjectileWeaponItem item = (ProjectileWeaponItem) weapon.getItem();
                SHOOT.invoke(item, (ServerLevel) player.level(), player, hand, weapon, arrows,
                        power * 3F, 1F, power == 1F, null, power);
                player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ARROW_SHOOT,
                        SoundSource.PLAYERS, 1F, 1F / (player.getRandom().nextFloat() * .4F + 1.2F) + power * .5F);
                player.awardStat(Stats.ITEM_USED.get(item));
            } catch (Throwable failure) { throw new IllegalStateException("Native bow release failed", failure); }
        }

        @Override public void cancel() {
            if (player.getUseItem() == weapon) {
                player.stopUsingItem();
                syncUse(player);
            }
        }
    }
}
