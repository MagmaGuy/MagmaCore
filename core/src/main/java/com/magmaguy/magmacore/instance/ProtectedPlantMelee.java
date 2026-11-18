package com.magmaguy.magmacore.instance;

import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Lets a melee swing reach a mob standing in a plant of a protected world.
 *
 * <p>The client aims at a plant's outline before the mob inside it, so the swing arrives as "start breaking this
 * block" and no attack is sent. In an unprotected world that swing removes the plant and the next one lands. A
 * protected world keeps the plant, so every swing is spent on it. Here the cancelled swing is passed on, once, to the
 * mob the player is looking at, through the server's own attack so cooldown, enchantments, damage events and every
 * plugin's cancellation run as for a direct hit.
 *
 * <p>Limits, each on the side of doing nothing:
 * <ul>
 * <li>Only a block without collision that this swing would have broken at once (plants, and the few other such
 * blocks: torches, redstone dust, tripwire, fire). Blocks that take time to break are left as they are.</li>
 * <li>The ray stops at the first block with a collision shape and at the player's entity interaction range, measured
 * from the eye to the point hit on the mob's box, as the client measures its own pick.</li>
 * <li>One hit per swing. Holding the button resends the same dig each time the client sees the plant come back, and
 * a fresh press sends the identical packet, so the two can only be told apart by time: digs that follow one another
 * closely are one swing. A player clicking faster than that loses pass-through hits and never gains one.</li>
 * <li>Survival players only: creative breaks without this event and adventure never sends the dig.</li>
 * </ul>
 */
final class ProtectedPlantMelee {
    /**
     * The one switch. Off at startup with {@code -Dmagmacore.protectedPlantMelee=false}; a plugin can also call
     * {@link InstanceProtector#setPlantMeleeEnabled(boolean)}.
     */
    static boolean enabled = !"false".equalsIgnoreCase(System.getProperty("magmacore.protectedPlantMelee"));

    private static final double DEFAULT_ENTITY_REACH = 3.0;
    private static final Attribute ENTITY_REACH = entityReachAttribute();
    private static final Map<Player, SwingLatch> latches = new WeakHashMap<>();

    private ProtectedPlantMelee() {
    }

    /** Called for a survival player's block damage that the protector has just cancelled. */
    static void onCancelledSwing(Player player, Block block, boolean instantBreak) {
        if (!enabled) return;
        if (!instantBreak || !block.isPassable()) return;
        if (player.getGameMode() != GameMode.SURVIVAL) return;
        // Recorded for every such dig, hit or not, so a held button stays one swing from its first dig.
        boolean newSwing = latches.computeIfAbsent(player, ignored -> new SwingLatch())
                .isNewSwing(player.getTicksLived(), System.nanoTime() / 1_000_000L, player.getPing());
        if (!newSwing) return;
        if (hasOwnAttackRules(player.getInventory().getItemInMainHand())) return;
        LivingEntity target = aimedAt(player);
        if (target != null) player.attack(target);
    }

    static void forget(Player player) {
        latches.remove(player);
    }

    static void shutdown() {
        latches.clear();
    }

    /** The living entity a normal swing could have hit: nearest along the view, in reach, with no solid block before it. */
    private static LivingEntity aimedAt(Player player) {
        Location eye = player.getEyeLocation();
        RayTraceResult hit = player.getWorld().rayTrace(eye, eye.getDirection(), entityReach(player),
                FluidCollisionMode.NEVER, true, 0, candidate -> canBeHit(player, candidate));
        // A block with a collision shape nearer than any entity comes back as a block hit: no pass-through.
        if (hit == null || !(hit.getHitEntity() instanceof LivingEntity living)) return null;
        return living;
    }

    private static boolean canBeHit(Player player, Entity candidate) {
        if (!(candidate instanceof LivingEntity living)) return false;
        if (candidate.equals(player) || living.isDead() || !living.isValid()) return false;
        if (candidate instanceof Player other && other.getGameMode() == GameMode.SPECTATOR) return false;
        if (candidate instanceof ArmorStand stand && stand.isMarker()) return false;
        if (!player.canSee(candidate)) return false;
        return !rootVehicle(candidate).equals(rootVehicle(player));
    }

    private static Entity rootVehicle(Entity entity) {
        Entity root = entity;
        while (root.getVehicle() != null) root = root.getVehicle();
        return root;
    }

    private static double entityReach(Player player) {
        AttributeInstance reach = ENTITY_REACH == null ? null : player.getAttribute(ENTITY_REACH);
        return reach == null ? DEFAULT_ENTITY_REACH : reach.getValue();
    }

    private static Attribute entityReachAttribute() {
        try {
            Attribute attribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("entity_interaction_range"));
            return attribute != null ? attribute
                    : Registry.ATTRIBUTE.get(NamespacedKey.minecraft("player.entity_interaction_range"));
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    /**
     * Items that set their own reach or minimum charge are checked by the server when it reads an attack packet,
     * which {@link Player#attack(Entity)} skips. Their swings are left alone.
     */
    private static boolean hasOwnAttackRules(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        String components;
        try {
            components = item.getItemMeta().getAsComponentString();
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
        return components.contains("attack_range") || components.contains("minimum_attack_charge")
                || components.contains("piercing_weapon");
    }

    /**
     * Tells a new swing from the repeats of a held button. While the button is held on a plant the server keeps,
     * the client resends the dig every time it sees the plant return: one round trip plus two or three ticks apart.
     * A dig is a new swing only when the previous one is further back than that, in ticks and in time, so a slow
     * server tick or a slow connection cannot split one held swing into two.
     */
    static final class SwingLatch {
        static final int BASE_TICKS = 6;
        static final long BASE_MILLIS = 300;
        private boolean seen;
        private long lastTick;
        private long lastMillis;

        boolean isNewSwing(long tick, long millis, int pingMillis) {
            int ping = Math.max(0, pingMillis);
            long tickWindow = BASE_TICKS + 2L * ((ping + 49) / 50);
            long milliWindow = BASE_MILLIS + 2L * ping;
            boolean fresh = !seen || (tick - lastTick > tickWindow && millis - lastMillis > milliWindow);
            // A tick counter that went backwards (the player's entity was replaced) starts over.
            if (seen && tick < lastTick) fresh = millis - lastMillis > milliWindow;
            seen = true;
            lastTick = tick;
            lastMillis = millis;
            return fresh;
        }
    }
}
