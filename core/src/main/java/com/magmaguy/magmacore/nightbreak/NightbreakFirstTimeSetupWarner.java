package com.magmaguy.magmacore.nightbreak;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.SpigotMessage;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.function.BooleanSupplier;

public class NightbreakFirstTimeSetupWarner implements Listener {
    private final JavaPlugin ownerPlugin;
    private final NightbreakFirstTimeSetupSpec spec;
    private final BooleanSupplier setupDoneSupplier;
    private final NightbreakPluginSpec pluginSpec;

    /**
     * Full constructor with capability-aware display.
     */
    public NightbreakFirstTimeSetupWarner(JavaPlugin ownerPlugin,
                                          NightbreakFirstTimeSetupSpec spec,
                                          BooleanSupplier setupDoneSupplier,
                                          NightbreakPluginSpec pluginSpec) {
        this.ownerPlugin = ownerPlugin;
        this.spec = spec;
        this.setupDoneSupplier = setupDoneSupplier;
        this.pluginSpec = pluginSpec;
    }

    /**
     * Backwards-compatible constructor that defaults to showing everything.
     */
    public NightbreakFirstTimeSetupWarner(JavaPlugin ownerPlugin,
                                          NightbreakFirstTimeSetupSpec spec,
                                          BooleanSupplier setupDoneSupplier) {
        this(ownerPlugin, spec, setupDoneSupplier, null);
    }

    @EventHandler
    public void onPlayerLogin(PlayerJoinEvent event) {
        if (setupDoneSupplier.getAsBoolean()) return;
        if (!event.getPlayer().hasPermission(spec.adminPermission())) return;

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!event.getPlayer().isOnline()) return;
                Logger.sendSimpleMessage(event.getPlayer(), "&8&m----------------------------------------------------");
                Logger.sendMessage(event.getPlayer(), "&fInitial setup message:");
                Logger.sendSimpleMessage(event.getPlayer(), "&7Welcome to " + spec.pluginDisplayName() + "! &c&lIt looks like setup is still pending.");

                boolean showPresetModes = pluginSpec == null || pluginSpec.hasPresetModes();
                boolean showContentPackages = pluginSpec == null || pluginSpec.hasContentPackages();

                if (showPresetModes && spec.initializeCommand() != null && !spec.initializeCommand().isEmpty()) {
                    String wording = showContentPackages
                            ? " &7to walk through the Nightbreak-powered content setup."
                            : " &7to walk through the first-time setup.";
                    event.getPlayer().spigot().sendMessage(
                            SpigotMessage.simpleMessage("&7Use "),
                            SpigotMessage.commandHoverMessage("&a" + spec.initializeCommand(),
                                    "&7Click to open the " + spec.pluginDisplayName() + " first-time setup flow.",
                                    spec.initializeCommand()),
                            SpigotMessage.simpleMessage(wording));
                }

                event.getPlayer().spigot().sendMessage(
                        SpigotMessage.simpleMessage("&7You can browse content any time with "),
                        SpigotMessage.commandHoverMessage("&a" + spec.setupCommand(),
                                "&7Click to open the " + spec.pluginDisplayName() + " setup menu.",
                                spec.setupCommand()),
                        SpigotMessage.simpleMessage("&7."));
                if (spec.supportUrl() != null && !spec.supportUrl().isEmpty()) {
                    event.getPlayer().spigot().sendMessage(
                            SpigotMessage.simpleMessage("&7Support: "),
                            SpigotMessage.hoverLinkMessage("&9&n" + spec.supportUrl(),
                                    "&7Click to open the support link.",
                                    spec.supportUrl()));
                }
                for (String warningNote : spec.warningNotes()) {
                    Logger.sendSimpleMessage(event.getPlayer(), warningNote);
                }
                Logger.sendSimpleMessage(event.getPlayer(), "&8&m----------------------------------------------------");
            }
        }.runTaskLater(ownerPlugin, 20L * 10);
    }
}
