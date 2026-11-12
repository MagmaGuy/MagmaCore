package com.magmaguy.easyminecraftgoals.mindshared.mind;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * The two marks a borrowed mob carries.
 *
 * <p>The hold is Bukkit metadata, which lives only in memory: it says which plugin's Mind drives the mob now, so a
 * second Mind, of this or any other plugin shading MagmaCore, leaves it alone. It is never saved.</p>
 *
 * <p>The receipt is saved with the mob. A borrowed mob has its AI switched off, and Minecraft saves that flag: it
 * writes loaded entities to disk on every autosave, copies them when they change dimension, and copies the flag onto
 * whatever a mob turns into. The receipt records what the mob had before, so a copy that outlives its borrow, after
 * a crash say, gets its AI back the next time any MagmaCore Mind host sees it: when its chunk loads, or when a host
 * starts. Its key is the same in every shaded copy of MagmaCore for that reason.</p>
 */
final class NativeMindBorrowedMarker {
    private static final String HOLD = "magmacore_mind_borrowed";
    private static final NamespacedKey RECEIPT = new NamespacedKey("magmacore", "mind_borrowed");
    private static final byte HAD_NO_AI = 1;
    private static final byte HAD_NO_GRAVITY = 2;

    private NativeMindBorrowedMarker() {
    }

    /** Whether a Mind of an enabled plugin drives the mob now. */
    static boolean held(LivingEntity entity) {
        for (MetadataValue value : entity.getMetadata(HOLD)) {
            Plugin owner = value.getOwningPlugin();
            if (owner != null && owner.isEnabled()) return true;
        }
        return false;
    }

    static void hold(LivingEntity entity, Plugin plugin, String hostIdentity, boolean hadNoAi, boolean hadNoGravity) {
        entity.setMetadata(HOLD, new FixedMetadataValue(plugin, hostIdentity));
        byte had = 0;
        if (hadNoAi) had |= HAD_NO_AI;
        if (hadNoGravity) had |= HAD_NO_GRAVITY;
        entity.getPersistentDataContainer().set(RECEIPT, PersistentDataType.BYTE, had);
    }

    static void letGo(LivingEntity entity, Plugin plugin) {
        entity.removeMetadata(HOLD, plugin);
        entity.getPersistentDataContainer().remove(RECEIPT);
    }

    /**
     * Gives a mob that carries the receipt of a borrow nobody holds any more the AI and gravity it had before.
     *
     * @return whether it carried one
     */
    static boolean redeem(LivingEntity entity) {
        PersistentDataContainer data = entity.getPersistentDataContainer();
        Byte had = data.get(RECEIPT, PersistentDataType.BYTE);
        if (had == null || held(entity)) return false;
        entity.setAI((had & HAD_NO_AI) == 0);
        entity.setGravity((had & HAD_NO_GRAVITY) == 0);
        data.remove(RECEIPT);
        return true;
    }
}
