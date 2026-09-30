package com.magmaguy.magmacore.match;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Crash-safe custody, ported from EternalTD's MatchPlayerState. The player's original state is
 * written into their playerdata and flushed before the match changes anything, so a hard crash
 * persists either the untouched player or the match state, and both carry enough to restore
 * the player on their next join. Normal leaving and crash recovery use the same idempotent path.
 */
final class SnapshotCustody implements PlayerCustody {
    private static final int MAGIC = 0x4D434D53; // MCMS
    // EternalTD wrote the same layout under its own magic before it moved onto the core.
    private static final int ETERNAL_TD_MAGIC = 0x45544453; // ETDS
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_INVENTORY_SLOTS = 100;
    private static final int MAX_PAYLOAD_BYTES = 8 * 1024 * 1024;

    private final NamespacedKey key;
    private final Consumer<Player> flush;
    private final Map<UUID, Scoreboard> scoreboards = new HashMap<>();

    SnapshotCustody(NamespacedKey key) {
        this(key, Player::saveData);
    }

    /** {@code flush} writes playerdata to disk; tests substitute it because MockBukkit cannot. */
    SnapshotCustody(NamespacedKey key, Consumer<Player> flush) {
        this.key = key;
        this.flush = flush;
    }

    private static byte[] encode(Snapshot snapshot) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (BukkitObjectOutputStream output = new BukkitObjectOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeInt(FORMAT_VERSION);
                output.writeInt(snapshot.inventoryContents().length);
                for (ItemStack itemStack : snapshot.inventoryContents())
                    output.writeObject(itemStack == null ? null : itemStack.clone());
                output.writeInt(snapshot.heldSlot());
                output.writeFloat(snapshot.flySpeed());
                output.writeBoolean(snapshot.allowFlight());
                output.writeBoolean(snapshot.flying());
                snapshot.returnLocation().write(output);
            }
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > MAX_PAYLOAD_BYTES)
                throw new IllegalStateException("Match custody payload exceeds " + MAX_PAYLOAD_BYTES + " bytes");
            return encoded;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode match custody state", exception);
        }
    }

    private static Snapshot decode(byte[] encoded) {
        if (encoded.length == 0 || encoded.length > MAX_PAYLOAD_BYTES)
            throw new IllegalArgumentException("Invalid match custody payload size " + encoded.length);
        try (BukkitObjectInputStream input = new BukkitObjectInputStream(new ByteArrayInputStream(encoded))) {
            int magic = input.readInt();
            if (magic != MAGIC && magic != ETERNAL_TD_MAGIC)
                throw new IllegalArgumentException("Unknown custody payload magic");
            int version = input.readInt();
            if (version != FORMAT_VERSION)
                throw new IllegalArgumentException("Unsupported custody payload version " + version);

            int slotCount = input.readInt();
            if (slotCount < 0 || slotCount > MAX_INVENTORY_SLOTS)
                throw new IllegalArgumentException("Invalid custody inventory size " + slotCount);
            ItemStack[] contents = new ItemStack[slotCount];
            for (int index = 0; index < slotCount; index++) {
                Object item = input.readObject();
                if (item != null && !(item instanceof ItemStack))
                    throw new IllegalArgumentException("Invalid custody item in slot " + index);
                contents[index] = item == null ? null : ((ItemStack) item).clone();
            }

            int heldSlot = input.readInt();
            if (heldSlot < 0 || heldSlot > 8)
                throw new IllegalArgumentException("Invalid held inventory slot " + heldSlot);
            float flySpeed = input.readFloat();
            if (!Float.isFinite(flySpeed) || flySpeed < -1F || flySpeed > 1F)
                throw new IllegalArgumentException("Invalid fly speed " + flySpeed);
            boolean allowFlight = input.readBoolean();
            boolean flying = input.readBoolean();
            if (flying && !allowFlight)
                throw new IllegalArgumentException("Custody state flies without flight permission");
            StoredLocation location = StoredLocation.read(input);
            if (input.read() != -1)
                throw new IllegalArgumentException("Custody payload has trailing data");
            return new Snapshot(contents, heldSlot, flySpeed, allowFlight, flying, location);
        } catch (EOFException exception) {
            throw new IllegalArgumentException("Match custody payload is truncated", exception);
        } catch (IOException | ClassNotFoundException exception) {
            throw new IllegalArgumentException("Could not decode match custody payload", exception);
        }
    }

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++)
            copy[index] = contents[index] == null ? null : contents[index].clone();
        return copy;
    }

    /** Must be the last step before the match changes the player. */
    @Override
    public void capture(Player player) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        if (data.get(key, PersistentDataType.BYTE_ARRAY) != null)
            throw new IllegalStateException("Player " + player.getUniqueId() + " already has a match custody lease");

        byte[] encoded = encode(Snapshot.capture(player));
        data.set(key, PersistentDataType.BYTE_ARRAY, encoded);
        try {
            // The lease must reach the playerdata file that later receives inventory autosaves
            // before the match changes any of them.
            flush.accept(player);
        } catch (RuntimeException exception) {
            data.remove(key);
            throw new IllegalStateException("Could not persist match custody state for " + player.getUniqueId(), exception);
        }
        scoreboards.put(player.getUniqueId(), player.getScoreboard());
    }

    @Override
    public void restore(Player player) {
        quarantineOnFailure(player, restoreIfOwned(player, false));
    }

    @Override
    public void recover(Player player) {
        quarantineOnFailure(player, restoreIfOwned(player, true));
    }

    /**
     * A retained lease can later overwrite newly acquired items, so a player whose restore
     * failed must not keep playing until the same idempotent recovery succeeds on a later join.
     */
    private void quarantineOnFailure(Player player, boolean restored) {
        if (restored || !player.isOnline()) return;
        player.kickPlayer(ChatColor.RED + "Your pre-match state could not be restored safely. "
                + "Please rejoin; if this repeats, ask an administrator to check the server log.");
    }

    /** Returns false only when a lease exists and could not be restored. */
    private boolean restoreIfOwned(Player player, boolean returnPlayer) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        byte[] encoded = data.get(key, PersistentDataType.BYTE_ARRAY);
        // Markerless players are left alone: their state may belong to another plugin.
        if (encoded == null) return true;

        try {
            Snapshot snapshot = decode(encoded);
            apply(player, snapshot, scoreboards.get(player.getUniqueId()), returnPlayer);
            // Persist the restored state while the lease is still present. If the process dies
            // here, the next join safely repeats recovery.
            flush.accept(player);
            data.remove(key);
            try {
                flush.accept(player);
            } catch (RuntimeException finalSaveFailure) {
                // Keep later autosaves recoverable if the lease removal could not be flushed.
                data.set(key, PersistentDataType.BYTE_ARRAY, encoded);
                throw finalSaveFailure;
            }
            scoreboards.remove(player.getUniqueId());
            return true;
        } catch (RuntimeException exception) {
            Logger.warn("Could not restore match custody state for " + player.getUniqueId()
                    + "; the lease was kept: " + exception.getMessage());
            return false;
        }
    }

    private static void apply(Player player, Snapshot snapshot, Scoreboard scoreboard, boolean returnPlayer) {
        int currentSlotCount = player.getInventory().getContents().length;
        if (snapshot.inventoryContents().length != currentSlotCount)
            throw new IllegalStateException("Custody inventory has " + snapshot.inventoryContents().length
                    + " slots, current inventory has " + currentSlotCount);

        player.setFlying(false);
        player.getInventory().clear();
        player.getInventory().setContents(cloneContents(snapshot.inventoryContents()));
        player.getInventory().setHeldItemSlot(snapshot.heldSlot());
        player.setFlySpeed(snapshot.flySpeed());
        player.setAllowFlight(snapshot.allowFlight());
        if (snapshot.allowFlight() && snapshot.flying()) player.setFlying(true);
        if (scoreboard != null) player.setScoreboard(scoreboard);
        if (!returnPlayer) return;

        Location returnLocation = snapshot.returnLocation().resolve();
        if (returnLocation != null && !player.teleport(returnLocation))
            Logger.warn("Return teleport was rejected for " + player.getUniqueId()
                    + "; the inventory and flight state were still restored");
    }

    private record Snapshot(
            ItemStack[] inventoryContents,
            int heldSlot,
            float flySpeed,
            boolean allowFlight,
            boolean flying,
            StoredLocation returnLocation) {
        private Snapshot {
            inventoryContents = cloneContents(inventoryContents);
        }

        private static Snapshot capture(Player player) {
            return new Snapshot(
                    player.getInventory().getContents(),
                    player.getInventory().getHeldItemSlot(),
                    player.getFlySpeed(),
                    player.getAllowFlight(),
                    player.isFlying(),
                    StoredLocation.capture(player.getLocation()));
        }
    }

    private record StoredLocation(
            UUID worldId,
            String worldName,
            double x,
            double y,
            double z,
            float yaw,
            float pitch) {
        private static StoredLocation capture(Location location) {
            World world = location.getWorld();
            return new StoredLocation(
                    world == null ? null : world.getUID(),
                    world == null ? null : world.getName(),
                    location.getX(), location.getY(), location.getZ(),
                    location.getYaw(), location.getPitch());
        }

        private static StoredLocation read(BukkitObjectInputStream input) throws IOException {
            UUID worldId = null;
            String worldName = null;
            if (input.readBoolean()) {
                worldId = new UUID(input.readLong(), input.readLong());
                worldName = input.readUTF();
                if (worldName.isEmpty()) worldName = null;
            }
            double x = input.readDouble();
            double y = input.readDouble();
            double z = input.readDouble();
            float yaw = input.readFloat();
            float pitch = input.readFloat();
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch))
                throw new IllegalArgumentException("Custody location contains non-finite coordinates");
            return new StoredLocation(worldId, worldName, x, y, z, yaw, pitch);
        }

        private void write(BukkitObjectOutputStream output) throws IOException {
            output.writeBoolean(worldId != null);
            if (worldId != null) {
                output.writeLong(worldId.getMostSignificantBits());
                output.writeLong(worldId.getLeastSignificantBits());
                output.writeUTF(worldName == null ? "" : worldName);
            }
            output.writeDouble(x);
            output.writeDouble(y);
            output.writeDouble(z);
            output.writeFloat(yaw);
            output.writeFloat(pitch);
        }

        private Location resolve() {
            if (worldId == null) return null;
            World world = Bukkit.getWorld(worldId);
            if (world == null && worldName != null) world = Bukkit.getWorld(worldName);
            if (world == null) return null;
            return new Location(world, x, y, z, yaw, pitch);
        }
    }
}
