package com.magmaguy.magmacore.instance;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * Per-world toggles read by {@link InstanceProtector}. Defaults match a
 * strict dungeon-style protection (everything blocked, no multipliers).
 *
 * Use the static factories or the fluent setters to build a ruleset and
 * pass it to {@link InstanceProtector#addProtectedWorld(org.bukkit.World, WorldProtectionRules)}.
 */
@Getter
@Setter
@Accessors(chain = true)
public class WorldProtectionRules {

    private boolean allowExplosions = false;
    private boolean allowLiquidFlow = false;
    private boolean allowElytra = false;
    private boolean preventFlyToggle = false;
    private boolean preventFriendlyFire = false;
    private boolean preventVanillaMobSpawning = true;
    private boolean preventRedstoneInteraction = false;
    private double fireDamageMultiplier = 1.0;
    private double poisonDamageMultiplier = 1.0;
    private double witherDamageMultiplier = 1.0;

    public static WorldProtectionRules strict() {
        return new WorldProtectionRules();
    }
}
