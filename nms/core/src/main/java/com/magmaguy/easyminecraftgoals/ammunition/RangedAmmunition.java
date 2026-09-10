package com.magmaguy.easyminecraftgoals.ammunition;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.BooleanSupplier;

/** Owns real item-use sessions while a caller grants ordinary ammunition. No inventory ammunition is inserted. */
public abstract class RangedAmmunition implements Listener, AutoCloseable {
    private static final NamespacedKey FREE_AMMUNITION = new NamespacedKey("magmacore", "granted_arrow");
    private final Plugin plugin;
    private final Predicate<Player> eligible;
    private final String handlerName;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Channel> channels = new HashMap<>();
    private BukkitTask ticker;
    private volatile boolean closed;

    protected RangedAmmunition(Plugin plugin, Predicate<Player> eligible) {
        this.plugin = plugin;
        this.eligible = eligible;
        handlerName = "magmacore_ammunition_" + plugin.getName();
    }

    public final RangedAmmunition start() {
        if (ticker != null || closed) throw new IllegalStateException("Ammunition service already started or closed");
        try {
            for (Player player : Bukkit.getOnlinePlayers()) inject(player);
            Bukkit.getPluginManager().registerEvents(this, plugin);
            ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
            return this;
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    protected abstract Channel channel(Player player);
    protected abstract boolean isReleasePacket(Object packet);
    /** Keeps release in the same native packet queue as preceding use and following hand changes. */
    protected abstract Object orderedRelease(Player player, Object packet, BooleanSupplier release);
    /** Returns null for a paid projectile, charged crossbow, or another item already in use. */
    protected abstract Use begin(Player player, EquipmentSlot hand);

    protected interface Use {
        boolean valid();
        void tick();
        void release();
        void cancel();
    }

    private record Session(Player player, UUID world, Use use) {}

    private boolean eligible(Player player) {
        return player.isOnline() && !player.isDead()
                && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE)
                && eligible.test(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public final void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (closed || !channels.containsKey(player.getUniqueId()) || !eligible(player)
                || event.useItemInHand() == Event.Result.DENY || event.getHand() == null) return;
        if (player.getOpenInventory().getType() != InventoryType.CRAFTING) return;
        ItemStack item = event.getItem();
        if (item == null || (item.getType() != Material.BOW && item.getType() != Material.CROSSBOW)) return;
        if (event.getClickedBlock() != null && event.getClickedBlock().getType().isInteractable()
                && !player.isSneaking() && event.useInteractedBlock() != Event.Result.DENY) return;
        // Observe the final protection result. Starting native use does not require cancelling this event:
        // BowItem/CrossbowItem.use do not consume ammunition or stop a use that is already active.
        if (sessions.containsKey(player.getUniqueId())) return;
        Use use = begin(player, event.getHand());
        if (use == null) return;
        sessions.put(player.getUniqueId(), new Session(player, player.getWorld().getUID(), use));
    }

    private void tick() {
        for (Session session : sessions.values()) {
            if (!eligible(session.player()) || !session.world().equals(session.player().getWorld().getUID())
                    || !session.use().valid()) {
                cancel(session.player());
                continue;
            }
            try {
                session.use().tick();
            } catch (RuntimeException failure) {
                cancel(session.player());
                plugin.getLogger().severe("Native ammunition update failed: " + failure);
            }
        }
    }

    private void release(Session expected) {
        if (!sessions.remove(expected.player().getUniqueId(), expected)) return;
        try {
            if (eligible(expected.player()) && expected.world().equals(expected.player().getWorld().getUID())
                    && expected.use().valid()) expected.use().release();
        } finally {
            expected.use().cancel();
        }
    }

    private void cancel(Player player) {
        Session session = sessions.remove(player.getUniqueId());
        if (session != null) session.use().cancel();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public final void validateGrantedShot(EntityShootBowEvent event) {
        if (isGranted(event.getConsumable()) && (!(event.getEntity() instanceof Player player) || !eligible(player)))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public final void preventPickup(EntityShootBowEvent event) {
        // The marker is on the charged projectile too, so handing over or saving a loaded crossbow cannot mint arrows.
        if (isGranted(event.getConsumable()) && event.getProjectile() instanceof AbstractArrow arrow)
            arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
    }

    protected static ItemStack grantedArrow() {
        ItemStack arrow = new ItemStack(Material.ARROW, 64);
        var meta = arrow.getItemMeta();
        meta.getPersistentDataContainer().set(FREE_AMMUNITION, PersistentDataType.BYTE, (byte) 1);
        arrow.setItemMeta(meta);
        return arrow;
    }

    protected static boolean ordinary(ItemStack arrow) {
        return arrow.getType() == Material.ARROW && !arrow.hasItemMeta();
    }

    private static boolean isGranted(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(FREE_AMMUNITION, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public final void onJoin(PlayerJoinEvent event) {
        try { inject(event.getPlayer()); }
        catch (RuntimeException failure) {
            plugin.getLogger().severe("Cannot enable native ammunition for " + event.getPlayer().getUniqueId() + ": " + failure);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR) public final void onQuit(PlayerQuitEvent event) { cancel(event.getPlayer()); uninject(event.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR) public final void onWorld(PlayerChangedWorldEvent event) { cancel(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public final void onDeath(PlayerDeathEvent event) { cancel(event.getEntity()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public final void onSlot(PlayerItemHeldEvent event) { cancel(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public final void onSwap(PlayerSwapHandItemsEvent event) { cancel(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public final void onDrop(PlayerDropItemEvent event) { cancel(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public final void onTeleport(PlayerTeleportEvent event) { cancel(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public final void onInventory(InventoryOpenEvent event) { if (event.getPlayer() instanceof Player player) cancel(player); }

    private void inject(Player player) {
        Channel channel = channel(player);
        if (channel == null) throw new IllegalStateException("No item-use channel for " + player.getUniqueId());
        if (channel.pipeline().get(handlerName) != null) throw new IllegalStateException("Duplicate ammunition owner: " + handlerName);
        UUID playerId = player.getUniqueId();
        channel.pipeline().addBefore("packet_handler", handlerName, new ChannelDuplexHandler() {
            @Override public void channelRead(ChannelHandlerContext context, Object packet) throws Exception {
                if (!closed && isReleasePacket(packet)) {
                    packet = orderedRelease(player, packet, () -> {
                        Session session = sessions.get(playerId);
                        if (session == null) return false;
                        release(session);
                        return true;
                    });
                }
                super.channelRead(context, packet);
            }
        });
        channels.put(playerId, channel);
    }

    private void uninject(UUID player) {
        Channel channel = channels.remove(player);
        if (channel != null && channel.pipeline().get(handlerName) != null) channel.pipeline().remove(handlerName);
    }

    @Override public final void close() {
        closed = true;
        if (ticker != null) { ticker.cancel(); ticker = null; }
        HandlerList.unregisterAll(this);
        for (Session session : sessions.values()) session.use().cancel();
        sessions.clear();
        for (UUID player : channels.keySet().toArray(UUID[]::new)) uninject(player);
    }

    /** Signatures survive Spigot remapping; resolve once, reject missing or ambiguous native entry points. */
    protected static MethodHandle nativeMethod(Class<?> owner, Class<?> result, boolean isStatic, Class<?>... arguments) {
        MethodHandle method = findNativeMethod(owner, result, isStatic, arguments);
        if (method == null) throw new IllegalStateException("Missing native ammunition method on " + owner
                + " with parameters " + Arrays.toString(arguments));
        return method;
    }

    /** Paper exposes bow force separately for its shoot event; Spigot has no separate argument. */
    protected static MethodHandle nativeShootMethod(Class<?> owner, Class<?>... arguments) {
        Class<?>[] withForce = Arrays.copyOf(arguments, arguments.length + 1);
        withForce[arguments.length] = float.class;
        MethodHandle method = findNativeMethod(owner, void.class, false, withForce);
        if (method != null) return method;
        method = nativeMethod(owner, void.class, false, arguments);
        return MethodHandles.dropArguments(method, method.type().parameterCount(), float.class);
    }

    private static MethodHandle findNativeMethod(Class<?> owner, Class<?> result, boolean isStatic, Class<?>... arguments) {
        Method found = null;
        for (Method method : owner.getDeclaredMethods()) {
            if (method.getReturnType() != result || Modifier.isStatic(method.getModifiers()) != isStatic
                    || !Arrays.equals(method.getParameterTypes(), arguments)) continue;
            if (found != null) throw new IllegalStateException("Ambiguous native ammunition method on " + owner);
            found = method;
        }
        if (found == null) return null;
        try { found.setAccessible(true); return MethodHandles.lookup().unreflect(found); }
        catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
    }

    protected static Field nativeField(Class<?> owner, Class<?> type) {
        Field found = null;
        for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getType() != type || Modifier.isStatic(field.getModifiers())) continue;
                if (found != null) throw new IllegalStateException("Ambiguous native connection field on " + owner);
                found = field;
            }
        }
        if (found == null) throw new IllegalStateException("Missing native connection field on " + owner);
        found.setAccessible(true);
        return found;
    }
}
