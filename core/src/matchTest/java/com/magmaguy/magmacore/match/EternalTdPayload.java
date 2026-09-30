package com.magmaguy.magmacore.match;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Writes a lease exactly as EternalTD's MatchPlayerState.encode does (format v1), so the test
 * proves leases left by a crash before the upgrade still restore.
 */
final class EternalTdPayload {
    private static final int MAGIC = 0x45544453; // ETDS

    private EternalTdPayload() {
    }

    static byte[] encode(ItemStack[] contents, int heldSlot, float flySpeed, boolean allowFlight, boolean flying,
                         Location returnLocation) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream output = new BukkitObjectOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(1);
            output.writeInt(contents.length);
            for (ItemStack itemStack : contents) output.writeObject(itemStack == null ? null : itemStack.clone());
            output.writeInt(heldSlot);
            output.writeFloat(flySpeed);
            output.writeBoolean(allowFlight);
            output.writeBoolean(flying);
            output.writeBoolean(true);
            output.writeLong(returnLocation.getWorld().getUID().getMostSignificantBits());
            output.writeLong(returnLocation.getWorld().getUID().getLeastSignificantBits());
            output.writeUTF(returnLocation.getWorld().getName());
            output.writeDouble(returnLocation.getX());
            output.writeDouble(returnLocation.getY());
            output.writeDouble(returnLocation.getZ());
            output.writeFloat(returnLocation.getYaw());
            output.writeFloat(returnLocation.getPitch());
        }
        return bytes.toByteArray();
    }
}
